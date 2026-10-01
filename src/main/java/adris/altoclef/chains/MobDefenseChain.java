package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.entity.BaitCreeperDetonationTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.trackers.KnowledgeMemory;
import adris.altoclef.control.KillAura;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.CreeperBaitSignal;
import adris.altoclef.util.helpers.HitRegistry;
import adris.altoclef.util.helpers.PathReachabilityHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.ThreatEvaluator;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;

import java.util.UUID;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ProjectileHelper;
import adris.altoclef.util.helpers.SurfaceHelper;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

@SuppressWarnings("rawtypes")
public class MobDefenseChain extends SingleTaskChain {

    private static final double CREEPER_KEEP_DISTANCE = 10;
    // (2026-09-01 用户"有盾还跑苦力怕, 应举盾格挡爆炸") 举盾接爆的有效范围。
    // 原 closeEnough 硬编码 10 格: 苦力怕在 10.7 格引爆时刚好超界 → 有盾也跑
    // (日志实锤 fuse=0.39 hasShield=true distance=10.7)。苦力怕爆炸波及 ~6 格,
    // 引爆距离实际可到 10+ 格, 扩大接爆范围到 16 格: 有盾时 fuse 的苦力怕一律
    // 原地举盾(doShieldNuke 的 fuse<ABORT 会自动放盾), 不再因边界擦边逃跑。
    private static final double CREEPER_SHIELD_BLOCK_RANGE = 16;

    // ---- 盾牌核弹 (Creeper Shield Nuke, 2026-08-23) ----
    // 苦力怕 fuse 在 [0.0, 1.0]，30 tick=1.5s 爆炸；举盾生效需 ~5 tick(0.25s)。
    // TRIGGER_FUSE=0.45 → 距爆炸 0.825s(16.5 tick)，留 11 tick 余量应对转向滞后。
    // (2026-09-01 用户"苦力怕修复仍失效被炸死"根因) ABORT_FUSE 0.2 对【刚起 fuse 的
    // 苦力怕】致命: 苦力怕 fuse 从 0 开始上升, 启动时 fuse=0.07(日志实锤) < 0.2 →
    // 盾核弹第 2 tick 就因 fuseDropped 放盾 → 永远坚持不到爆炸, 期间被骷髅战(95)/
    // 逃跑接管 → 被炸。改为 0.001: 只在 fuse 真正回落到 0(苦力怕去 fuse/离开)才放盾,
    // 起 fuse 的苦力怕一直举盾接到爆炸。
    private static final float SHIELD_NUKE_ABORT_FUSE = 0.001f;
    private static final float SHIELD_NUKE_MIN_HEALTH_RATIO = 0.40f;
    private static final int SHIELD_NUKE_MAX_HOSTILES = 3;
    // 本次举盾持续 tick 数，用于爆炸后保护判定
    private long _shieldNukeStartTick = -1;
    private CreeperEntity _shieldNukeTarget = null;
    // (2026-08-23) 是否正在持盾格挡弹射物 (箭/烈焰人火球)。弹射物消失时
    // 需 release 放盾, 否则盾一直举着移动极慢。
    private boolean _projectileBlocking = false;
    private static final double ARROW_KEEP_DISTANCE_HORIZONTAL = 2;//4;
    private static final double ARROW_KEEP_DISTANCE_VERTICAL = 10;//15;

    // 逃离怪物的目标距离 (2026-08-21 用户要求从 30 提到 45): 30 格时怪物
    // (尤其骷髅弓箭手 15 格射程 + 追击) 很快重新进入威胁范围, 反复触发
    // "逃离→刚停下→又逃离" 的抖动; 45 格一次跑出真实安全区。
    // (2026-09-30 用户拍板, 推翻 08-21 的 45) 逃跑距离最多【直线 24 格】:
    // 180851 现场死亡恢复期"反复逃跑 32/45 格"骚扰拾取与进食, 跑太远纯损耗。
    // 棘轮步进不变 (8→16→24 封顶)。LONG_RETREAT(弃区 100 格) 是另一机制, 未动。
    private static final double DANGER_KEEP_DISTANCE = 24;

    private static final double SAFE_KEEP_DISTANCE = 8;
    // (2026-09-01 用户) 纯近战怪数量达到此值才逃跑(被围殴), 1~2 只直接打。
    // 远程攻击者(骷髅/烈焰等能射)与苦力怕(爆炸)不受此限制: 永远保留逃跑路径。
    private static final int RETREAT_MELEE_THRESHOLD = 3;
    // (2026-09-18 用户拍板) 低血量硬闸: 残血时不管"近战少怪直接打"一律撤。
    // 6 = 3 颗心 —— 末影人/僵尸猪灵一击 4.5, 低于此值再挨一下就死。
    private static final float LOW_HEALTH_FORCE_RETREAT = 6.0f;
    // (2026-09-02 贴脸近战立即击杀, 用户"僵尸在脸上早该杀了, 快死才反应")
    // 近战怪在此距离内视为"贴脸正在攻击我们" → 立即进击杀清单, 不等
    // killHostileWhenCloseForSeconds(12s) 计时。否则僵尸贴脸 12 秒早把人打残,
    // 直到血量跌进 isVulnurable 才触发击杀 (日志实锤 19:04:34 僵尸 0.9 格贴脸,
    // 快死才 FIGHT)。
    private static final double MELEE_ATTACK_RANGE = 3.0;
    // Kind of a silly solution
    // (20260915 加入 EndermanEntity) FIGHT 击杀任务按此类别过滤候选, 缺末影人时
    // _fightTargets 非空但 KillEntitiesTask 候选集为空 → wander 空转 20s 挨打
    // (日志实锤 20260915-115534 12:02:23~43, 血量 20→10.4)。
    public static Class[] HOSTILE_ANNOYING_CLASSES = new Class[]{SkeletonEntity.class, ZombieEntity.class, SpiderEntity.class, CaveSpiderEntity.class, WitchEntity.class, PiglinEntity.class, PiglinBruteEntity.class, HoglinEntity.class, ZoglinEntity.class, BlazeEntity.class, WitherSkeletonEntity.class, PillagerEntity.class, DrownedEntity.class, EndermanEntity.class};
    private final KillAura _killAura = new KillAura();
    private final HashMap<Entity, TimerGame> _closeAnnoyingEntities = new HashMap<>();
    private Entity _targetEntity;
    private boolean _doingFunkyStuff = false;
    private boolean _wasPuttingOutFire = false;
    private CustomBaritoneGoalTask _runAwayTask;
    // (2026-09-25 183242 实锤) 诱爆任务引用: 防御链自己派发并跨 tick 持有,
    // 没有它, 点燃分支每 tick 都在 核弹→战斗→逃跑 里打转, 诱爆永远不启动。
    private BaitCreeperDetonationTask _baitTask;
    // (2026-09-25 224856 实锤) 派发/持有半径常量移入 CreeperBaitSignal (DISPATCH_
    // RANGE_SQ=8²/HOLD_RANGE_SQ=10²) —— AbstractKillEntityTask 重定向端也要用,
    // 单一事实源防两边口径漂移。

    private float _cachedLastPriority;

    // ---- [MOB-DIAG] 只读诊断 (2026-08-31) ----
    // 节流时间戳 (毫秒): 优先级/状态机、威胁感知、逃跑实测各用一个,
    // 避免互相吃掉配额、也避免刷屏。全部只读, 绝不调用 getPriority()。
    private long _lastPriorityDiagLogMs = 0;
    private long _lastThreatDiagLogMs = 0;
    private long _lastEscapeDiagLogMs = 0;
    private static final long MOB_DIAG_INTERVAL_MS = 500;
    // 上一次记录到的防御状态机, 用于 [MOB-STATE] 状态切换判定。
    private DefenseState _lastLoggedState = null;
    // getNearbyHostiles 是 static, 用 static 节流变量。
    private static long _lastNearbyHostileDiagMs = 0;
    // (2026-09-01) [RETREAT-DEC] 逃/打判决 节流时间戳 (0.5s)。
    private long _lastRetreatDecLogMs = 0;
    // (2026-09-16) [Diag-creeper] 苦力怕 fuse 诊断节流时间戳 (0.5s, 复用 MOB_DIAG_INTERVAL_MS)。
    // fusing 苦力怕存在时 stateEvaluate 每 tick 都到这, 不节流会每 tick 刷屏聊天+日志。
    private long _lastCreeperDiagMs = 0;

    // ---- Defense state machine ----
    // The defense chain is now a small state machine so that repeated fights /
    // failed retreats escalate (shelter -> dig away -> long retreat -> cooldown)
    // instead of looping "fight/run" forever in the same spot.
    // FIGHT (2026-08-21): once we decide to fight a batch of hostiles we stay
    // in FIGHT until the whole batch is dead (or timeout / threat flips) —
    // this fixes the "hit once then ping-pong back to the user task" behavior.
    private enum DefenseState {
        EVALUATE, FIGHT, RETREAT, SHELTER, DIG_ESCAPE, LONG_RETREAT, BLOCK_PATH, COOLDOWN,
        // (2026-09-07 塔式逃生) 逃跑失败升级时, 纯近战围殴场景优先"逃到空旷搭塔"。
        TOWER_ESCAPE
    }
    private DefenseState _defenseState = DefenseState.EVALUATE;
    private int _retreatEscalation = 0;
    private long _cooldownEndTick = 0;
    private long _combatStartTick = 0;
    private boolean _inCombat = false;
    private Entity _blockThreat = null;
    private long _lastReachabilityCheckTick = 0;
    private long _blockPathCooldownTick = 0;
    // Escape snapshot: saved right before we switch to BLOCK_PATH so that
    // after blocking we can resume the SAME kind of escape (RETREAT vs
    // LONG_RETREAT) with the original parameters. Otherwise a 100-block long
    // retreat would silently get demoted to a 30-block escape.
    private DefenseState _escapeStateBeforeBlock = DefenseState.EVALUATE;
    private double _savedEscapeMaxDistance;
    private double _savedEscapeStep;
    private boolean _savedEscapeIncludeSkeletons;
    // The currently running defensive tasks. We keep these around so the state
    // machine checks completion on the REAL running instance (created once) and
    // not on a freshly constructed one that never got to start.
    private ShelterAndRecoverTask _shelterTask;
    private DigOutEscapeTask _digTask;
    private BlockOffPathTask _blockTask;
    // (2026-09-07 塔式逃生) 塔逃任务实例 (同 shelter/dig: 持有真实运行实例判完成)。
    private TowerEscapeTask _towerTask;

    // ---- FIGHT state (集中击杀, 2026-08-21) ----
    // The batch of hostiles we committed to killing. The kill task holds a
    // LIVE reference to this list, so targets added mid-fight are picked up
    // without rebuilding the task (and restarting its pathing).
    private final List<Entity> _fightTargets = new ArrayList<>();
    private KillEntitiesTask _fightTask = null;
    // (2026-08-23) 本场战斗是否经"自信战斗"提交。自信战斗的中途复查
    // 不走护甲威胁公式 (铁/钻石阶段未穿满甲时公式必判必输, 实测 00:07
    // 打 5 秒就被公式踢去逃跑), 只在血量跌破 40% 时撤退。
    private boolean _confidentFight = false;
    // Beyond this distance a fight target is considered "fled" and dropped —
    // we chase to finish a fight, but not across half the world.
    private static final double FIGHT_MAX_CHASE_DISTANCE = 30;

    private static final int COMBAT_TIMEOUT_TICKS = 20 * 60;      // 60s of fighting without progress
    private static final int RETREAT_FAIL_LIMIT = 8;              // retreat stuck this many times -> escalate (2026-08-31 3->8: 逃跑被追易误判卡住,fail3就放弃→跑不远被杀)
    // (2026-09-30 C 掠夺者/卫道士死战, 用户拍板) 没剑先做剑: 材料全在背包才派
    // (squash 链只补合成, 不外出挖料), 15s 做不出来就空手战, 不无限等。
    private static final long RAID_SWORD_CRAFT_TIMEOUT_TICKS = 15 * 20;
    private Task _swordCraftTask = null;
    private long _swordCraftStartTick = 0;
    private boolean _raidSwordCraftFailed = false;
    private static final Item[] RAID_LOG_ITEMS = {
            Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG,
            Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG
    };
    private static final Item[] RAID_PLANK_ITEMS = {
            Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS,
            Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS
    };
    private static final int COOLDOWN_TICKS = 20 * 5;             // rest 5s after an escalation
    private static final double LONG_RETREAT_DISTANCE = 100;
    private static final double ESCAPE_STEP = 8;          // normal escape ratchets 8 -> 16 -> ... -> 45
    private static final double LONG_RETREAT_STEP = 20;   // long retreat ratchets 20 -> 40 -> ... -> 100
    private static final int REACHABILITY_CHECK_INTERVAL = 20 * 2; // every 2s
    private static final int BLOCK_PATH_COOLDOWN_TICKS = 20 * 30;  // don't re-block the same path for 30s
    private static final double REACHABILITY_MIN_THREAT = 50;
    private static final int REACHABILITY_MIN_DISTANCE = 8;
    // (2026-08-30 已开火骷髅必须杀, 用户) 已经开始攻击 bot 的骷髅: 逃跑不是
    // 选项(被追击+射击, 尤其血量低时), 直接锁定击杀, 优先级最高, 不被基础
    // 任务抢占。95 高于普通战斗(65)/障碍封路(90), 但低于致命级 (岩浆/MLG 100)。
    private static final float ACTIVE_ATTACKER_PRIORITY = 95.0f;
    // 是否正处在"必须击杀已开火骷髅"的加急战斗 (期间不因血量低撤退)。
    private boolean _mustKillAttacker = false;
    // (2026-09-17 死亡 #2 取证, 场次 20260917-103520) must-kill 期间的**贴身近战
    // 优先清理**口径。现场: 骷髅在 10.2 格外射箭, 真正把 bot 从 20 打到 0 的是
    // 1 格内的僵尸 (class_1642) —— 而 must-kill 的击杀名单只装了骷髅, bot 眼里
    // 根本没有贴脸的僵尸, 一边追骷髅一边被白嫖。用户 2026-09-17 拍板:
    // **小白必须击杀, 但先解决贴脸的**。做法 = must-kill 触发时把该半径内的近战怪
    // 一并纳入 _fightTargets; KillEntitiesTask 走 DoToClosestEntityTask 的"最近优先",
    // 于是自动先清贴脸怪、再回头打骷髅。图钉与 prio 95 全程不变 (绝不转逃)。
    private static final double MUST_KILL_MELEE_KEEP_DISTANCE = 3.0;   // 近战"贴脸"判定半径
    // (2026-09-19 用户) 击杀小白的触发距离缩小为"刚好触发小白"的距离:
    // 骷髅索敌就在 16 格 (开火射程 15), 旧值 18 会先手去招惹还没瞄准我们的
    // 骷髅, 在堡垒走廊里白白多拉一波仇恨。女巫维持 18 不变。
    // (2026-09-24 用户"缩小派发击杀敌人的距离, 离很远没触发攻击就去杀") 再
    // 收紧到 12: bot 不该主动跑 16 格去猎杀还没开火的骷髅; 真开火命中的走
    // must-kill 实锤通道 (24 格), 未开火的只在近距离+它看见我们时才计时清场。
    private static final int SKELETON_KILL_TRIGGER_RANGE = 12;

    // (20261001 激怒猪灵接战触发窗, 20261001-185104 场次实锤) 挖金惹怒猪灵后,
    // 猪灵 6-10 格追击而 bot 不还手 24s (19:03:46-19:04:10): 激怒猪灵此前吃
    // 通用 5 格默认触发窗, 追击距离够不着; isInDanger 又被 isVulnurable 挡。
    // 激怒猪灵 (HitRegistry 被打记忆 / ANGRY_AT 记忆指向 bot, 见
    // EntityHelper.isPiglinProvoked 单一判定源) 已有明确攻击意图, 接战窗抬到
    // 12 格 (与小白 SKELETON_KILL_TRIGGER_RANGE 同档)。中性猪灵照旧 5 格,
    // 不会主动引战。
    private static final int PIGLIN_ANGRY_KILL_TRIGGER_RANGE = 12;

    // (2026-09-30 主动索敌, 用户拍板"不要被打一下后才锁定") 骷髅【亲眼可见且敌对】
    // 即触发 must-kill 的接敌半径。1.18.2 客户端无攻击者包 ⇒ HitRegistry 箭伤
    // 归因恒缺 (0930 会话五连死实锤: mustKill=true 全场 0 次), 0924 的
    // "hit-confirmed 才锁定"根修在本版本恒死。半径取 16: 与小白触发口径同数量级
    // (箭程 15, 16 内的可见骷髅下一拍就在射我们), 听见墙后怪仍不算 (反透视结论保住)。
    private static final double SKELETON_PROACTIVE_ENGAGE_RANGE = 16;

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public boolean shouldTickEveryTick() {
        // 反射链：战斗/逃生的攻防时机需要 tick 级反应，不能降频。
        return true;
    }

    public static double getCreeperSafety(Vec3d pos, CreeperEntity creeper) {
        double distance = creeper.squaredDistanceTo(pos);
        float fuse = creeper.getClientFuseTime(1);

        // Not fusing.
        if (fuse <= 0.001f) return distance;
        return distance * 0.2; // less is WORSE
    }

    @Override
    public float getPriority(AltoClef mod) {
        // [PIGLIN-DIAG] (2026-09-06 Phase 0 只读诊断) 猪灵系激怒信号验证, 0.5s 节流, 不改行为。
        if (AltoClef.inGame()) EntityHelper.logPiglinDiag(mod);
        _cachedLastPriority = getPriorityInner(mod);
        // [MOB-DIAG] #3/#4 只读: 打印最终返回的优先级 + 状态机 (0.5s 节流)。
        logPriorityAndState(mod);
        return _cachedLastPriority;
    }
    private float getPriorityInner(AltoClef mod) {
        if (!AltoClef.inGame()) {
            return Float.NEGATIVE_INFINITY;
        }

        if (!mod.getModSettings().isMobDefense()) {
            return Float.NEGATIVE_INFINITY;
        }

        // Pause if we're not loaded into a world.
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        // Put out fire if we're standing on one like an idiot
        BlockPos fireBlock = isInsideFireAndOnFire(mod);
        if (fireBlock != null) {
            putOutFire(mod, fireBlock);
        } else if (_wasPuttingOutFire) {
            // Stop putting stuff out if we no longer need to put out a fire.
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            _wasPuttingOutFire = false;
        }

        // (2026-09-02 苦力怕盾牌核弹持久化根因修复) 举盾接爆一旦启动就必须持续 hold
        // 到苦力怕爆炸/目标消失/fuse 回落, 绝不能被状态机打断。旧实现 stateEvaluate
        // 用 "_defenseState = RETREAT 假装" 承接盾牌核弹, 但 stateRetreat 开头会
        // 无条件 release 盾 + 清 _shieldNukeStartTick → 盾牌核弹只撑 1 tick
        // (日志实锤 11:00:25.18 EVALUATE->RETREAT(75) 后 0.05s 就被打回 EVALUATE(0),
        // 0.8~1.5 格的苦力怕在 prio=0 下引爆炸死)。改为: 盾牌核弹激活期间每 tick
        // 在状态机之前直接续 hold, 返回高优先, 保证赢过进食/普通任务。
        if (_shieldNukeStartTick >= 0 && _shieldNukeTarget != null) {
            if (doShieldNuke(mod, _shieldNukeTarget)) {
                return 90;
            }
            // 已结束(爆炸/目标消失/fuse 回落): doShieldNuke 内部已放盾清状态。
            // 若是在集中战斗中接爆的, 保留 FIGHT 上下文让骷髅战无缝续打。
            _defenseState = (_fightTask != null || !_fightTargets.isEmpty())
                    ? DefenseState.FIGHT : DefenseState.EVALUATE;
        }

        if (prioritizeEating(mod) && !_inCombat) {
            // (2026-08-30 战斗中不进食, 用户) 打斗中(尤其举盾 vs 骷髅/苦力怕)
            // 不再让位给"紧急进食"—— 吃东西和举盾都是右键互抢, 且战斗中进食
            // = 挨打。战斗结束(脱离 _inCombat)才考虑吃。
            return Float.NEGATIVE_INFINITY;
        }

        long nowTick = WorldHelper.getTicks();

        switch (_defenseState) {
            case FIGHT:
                return stateFight(mod, nowTick);
            case RETREAT:
                return stateRetreat(mod);
            case SHELTER:
                return stateShelter(mod);
            case DIG_ESCAPE:
                return stateDigEscape(mod);
            case TOWER_ESCAPE:
                return stateTowerEscape(mod);
            case LONG_RETREAT:
                return stateLongRetreat(mod);
            case BLOCK_PATH:
                return stateBlockPath(mod);
            case COOLDOWN:
                if (nowTick >= _cooldownEndTick) {
                    _defenseState = DefenseState.EVALUATE;
                    _retreatEscalation = 0;
                    _inCombat = false;
                    _runAwayTask = null;
                    return 0;
                }
                // During cooldown we only react defensively (run away if a mob is
                // literally on us), we don't pick new fights.
                if (isInDanger(mod)) {
                    // Create-once: don't rebuild a new task (and restart A*)
                    // every tick while the danger persists.
                    if (_runAwayTask == null || _runAwayTask.isFinished(mod)) {
                        _runAwayTask = new ProgressiveRunAwayTask(DANGER_KEEP_DISTANCE, ESCAPE_STEP, true);
                    }
                    setTask(_runAwayTask);
                    return 70;
                } else {
                    // No longer in danger: drop the stale task so the next
                    // danger event creates a fresh one (clean fail count etc).
                    _runAwayTask = null;
                }
                return 0;
            case EVALUATE:
            default:
                return stateEvaluate(mod, nowTick);
        }
    }

    // ------------------------------------------------------------------
    // [MOB-DIAG] 只读诊断辅助 (2026-08-31)
    // ------------------------------------------------------------------

    /**
     * #3/#4 优先级 + 状态机诊断: 在 getPriority 末尾打印链条实时返回的优先级
     * 与防御状态机(0.5s 节流), 以及状态切换([MOB-STATE], 切换本身罕见不节流)。
     * 只读 _cachedLastPriority 与 _defenseState —— 绝不调用 getPriority(),
     * 避免链决策→setTask→递归 StackOverflow。
     */
    private void logPriorityAndState(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (_defenseState != _lastLoggedState && _lastLoggedState != null) {
            String from = _lastLoggedState.name();
            _lastLoggedState = _defenseState;
            String m = "[MOB-STATE] " + from + " -> " + _defenseState + " (prio=" + _cachedLastPriority + ")";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
        if (_lastLoggedState == null) _lastLoggedState = _defenseState;
        if (now - _lastPriorityDiagLogMs < MOB_DIAG_INTERVAL_MS) return;
        _lastPriorityDiagLogMs = now;
        try {
            String p = "[MOB-DIAG] prio=" + _cachedLastPriority + " state=" + _defenseState
                    + " inCombat=" + _inCombat + " mustKill=" + _mustKillAttacker
                    + " player=" + mod.getPlayer().getBlockPos();
            Debug.logToFile("altoclef_diag.log", p);
        } catch (Exception ignored) { /* 诊断日志绝不影响主逻辑 */ }
    }

    /**
     * #1 威胁感知诊断: 打印 getActiveRangedAttacker 的判定结果 (是否有正在攻击
     * bot 的骷髅射手), 以及玩家/怪 pos、水平距离、FOV(closeness)、LOS。
     */
    private void logRangedAttackerDiag(AltoClef mod, Entity best) {
        long now = System.currentTimeMillis();
        if (now - _lastThreatDiagLogMs < MOB_DIAG_INTERVAL_MS) return;
        _lastThreatDiagLogMs = now;
        try {
            StringBuilder sb = new StringBuilder("[MOB-DIAG] activeRangedAttacker="
                    + (best == null ? "none" : best.getClass().getSimpleName()));
            if (best != null) {
                Vec3d pp = mod.getPlayer().getPos();
                Vec3d mp = best.getPos();
                double hz = Math.sqrt((mp.x - pp.x) * (mp.x - pp.x) + (mp.z - pp.z) * (mp.z - pp.z));
                sb.append(" player=").append(pp)
                        .append(" mobPos=").append(mp)
                        .append(" hzDist=").append(String.format("%.1f", hz))
                        .append(" fovCloseness=").append(String.format("%.2f", LookHelper.getLookCloseness(mod.getPlayer(), mp)))
                        .append(" seesPlayer=").append(LookHelper.seesPlayer(best, mod.getPlayer(), 24));
            }
            Debug.logToFile("altoclef_diag.log", sb.toString());
        } catch (Exception ignored) { }
    }

    /**
     * #1 威胁枚举诊断: 打印 16 格内感知到的敌对怪清单 (类型/pos/水平距离/
     * 是否仇恨玩家/怪是否看到玩家)。用于定位"感知小白不跑"。
     */
    private static void logNearbyHostilesDiag(AltoClef mod, List<Entity> near) {
        long now = System.currentTimeMillis();
        if (now - _lastNearbyHostileDiagMs < MOB_DIAG_INTERVAL_MS) return;
        _lastNearbyHostileDiagMs = now;
        try {
            Vec3d pp = mod.getPlayer().getPos();
            StringBuilder sb = new StringBuilder("[MOB-DIAG] nearbyHostiles=" + near.size());
            for (Entity e : near) {
                Vec3d ep = e.getPos();
                double hz = Math.sqrt((ep.x - pp.x) * (ep.x - pp.x) + (ep.z - pp.z) * (ep.z - pp.z));
                sb.append(" | ").append(e.getClass().getSimpleName())
                        .append("@").append(ep)
                        .append(" hz=").append(String.format("%.1f", hz))
                        .append(" angry=").append(EntityHelper.isAngryAtPlayer(mod, e))
                        .append(" sees=").append(LookHelper.seesPlayer(e, mod.getPlayer(), 16));
            }
            Debug.logToFile("altoclef_diag.log", sb.toString());
        } catch (Exception ignored) { }
    }

    /**
     * #2 逃跑实测诊断: 打印当前逃跑目标距离/step/maxDistance/failCount、
     * 玩家实时 pos、最近威胁怪及水平距离 —— 看"逃跑到底跑到几格、fail 到几次、
     * 为何停下"。在 RETREAT/LONG_RETREAT 运行处 0.5s 节流调用。
     */
    private void logEscapeDiag(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastEscapeDiagLogMs < MOB_DIAG_INTERVAL_MS) return;
        _lastEscapeDiagLogMs = now;
        try {
            String info;
            if (_runAwayTask instanceof ProgressiveRunAwayTask p) {
                info = "curDist=" + p.getCurrentDistance() + " step=" + p.getStep()
                        + " maxDist=" + p.getMaxDistance() + " failCount=" + p.getFailCount();
            } else if (_runAwayTask != null) {
                info = "class=" + _runAwayTask.getClass().getSimpleName()
                        + " finished=" + _runAwayTask.isFinished(mod);
            } else {
                info = "null";
            }
            Vec3d pp = mod.getPlayer().getPos();
            String nearest = "none";
            double bestDist = Double.MAX_VALUE;
            for (Entity e : mod.getEntityTracker().getHostiles()) {
                if (e == null || !e.isAlive()) continue;
                double d = e.squaredDistanceTo(mod.getPlayer());
                if (d < bestDist) {
                    bestDist = d;
                    nearest = e.getClass().getSimpleName() + "@hz=" + String.format("%.1f", Math.sqrt(d));
                }
            }
            String m = "[MOB-DIAG] escape state=" + _defenseState + " (" + info + ")"
                    + " player=" + pp + " nearestThreat=" + nearest;
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------
    // State machine implementations
    // ------------------------------------------------------------------

    /**
     * (2026-08-23 自信战斗判定) 血量 ≥70% 满血 且 有任意剑,
     * 且 16 格内存活敌对怪 ≤3 只且全是普通怪 (非 boss 级) → 直接决定
     * 战斗, 不走威胁公式。boss 级 (凋灵/末影龙/劫掠兽) 出现或怪太多时
     * 回退公式/逃跑评估。
     * (2026-08-26 用户修改: 去掉"必须有盾牌"要求 — 单只小白威胁低, 血量
     * 健康 + 有剑就该打, 无盾时威胁公式 threat=60 vs our=56 反而判 RUN,
     * 实测 bot 一直被小白射还一直跑。15:50 日志: hasShield=false +
     * "Combat decision: RUN (threat=60.0, our=56.0, enemies=1)"。)
     */
    private static boolean isConfidentFight(AltoClef mod, List<Entity> nearbyHostiles) {
        if (nearbyHostiles.isEmpty()) return false;
        if (nearbyHostiles.size() > 3) return false;
        for (Entity hostile : nearbyHostiles) {
            if (isBossLike(hostile)) return false;
        }
        if (mod.getPlayer().getHealth() < mod.getPlayer().getMaxHealth() * 0.7f) return false;
        return mod.getItemStorage().hasItem(Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
                Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);
    }

    /** boss 级怪物: 不适用"自信战斗", 必须走威胁公式。 */
    private static boolean isBossLike(Entity entity) {
        return entity instanceof WitherEntity
                || entity instanceof EnderDragonEntity
                || entity instanceof RavagerEntity;
    }

    /**
     * (2026-09-01 用户) "远程攻击者"判定: 能隔距离射击/投掷的怪。这类怪隔山
     * 打你, 逃跑/举盾才有意义, 不在"近战少怪直接打"豁免范围内。
     */
    public static boolean isRangedThreat(Entity e) {
        // (2026-09-07 塔式逃生) 改 public: TowerEscapeTask 塔上监视复用同一判定。
        // 普通骷髅/流浪者/沼泽骷髅会射箭; 凋灵骷髅是近战挥剑(且算 universally
        // dangerous), 不归为远程。
        if (e instanceof AbstractSkeletonEntity && !(e instanceof WitherSkeletonEntity)) return true;
        return e instanceof WitchEntity
                || e instanceof BlazeEntity
                || e instanceof GhastEntity
                || e instanceof PillagerEntity
                || e instanceof GuardianEntity
                || e instanceof ShulkerEntity
                || e instanceof DrownedEntity; // 溺尸会扔三叉戟
    }

    /**
     * (2026-09-01 用户) "要不要逃跑"最终判决。
     * 逃跑仅保留给三类: (a) 存在远程攻击者(能射); (b) 存在苦力怕(爆炸需举盾/
     * 远离); (c) 纯近战怪数量 ≥ RETREAT_MELEE_THRESHOLD(被围殴)。
     * 其余 (近战怪 1~2 只) → 返回 false, 走"直接战斗"。
     * (2026-09-30 用户拍板) 掠夺者(弩)/卫道士(斧)跑不掉, 不逃: 卫道士会冲刺,
     * 掠夺者边追边射, 被追时逃跑 = 边逃边挨打只延长挨打时间 —— 与末影人 0921
     * 同口径, 全数量级死战 (单目标锁保证一次接战一只)。唤魔者明确不在内 (本体
     * 不追人, 尖牙/恼鬼另算)。低血量逃生豁免见下方 LOW_HEALTH_FORCE_RETREAT 闸。
     * 附带 [RETREAT-DEC] 判决日志 (0.5s 节流)。
     */
    private boolean shouldRetreatMelee(AltoClef mod, List<Entity> hostiles) {
        boolean anyRanged = false, anyCreeper = false;
        int meleeCount = 0;
        // (2026-09-30 用户拍板) 掠夺者/卫道士"跑不掉"计数: 不参与 anyRanged /
        // meleeCount, 因此不再触发任何逃跑分支 (含 ≥3 围殴门槛)。
        int raidIllagerCount = 0;
        // (2026-09-15 用户) 激怒猪灵围殴实测打不过 3 只(持剑): 感知到的猪灵系
        // 超过 2 只直接跑, 不进 melee-few 判定硬打。只数"看得见"的 (模仿人类,
        // 墙后/没看见的不算, 与 GOLD-GATE 挖金闸同口径)。激怒猪灵已在敌对
        // 名单里 (ANGRY_AT/被打记忆), 走到这里的本身就是"激怒了"。
        int piglinSwarm = 0;
        // (2026-09-18 用户) 猪灵蛮兵单只也必须跑: 蛮兵 40 血金斧打不过, 见玩家即
        // 敌对且不受金锭/金甲安抚。piglinSwarm>2 的门槛只对可安抚的普通猪灵有意义,
        // 蛮兵混在里面会被"近战 1 只直接打"的分支吃掉 (12:02:44 实锤)。蛮兵在
        // getUniversallyDangerousMob 的 6 格保留圈之外时由这里兜底。
        int bruteCount = 0;
        // (2026-09-18 用户拍板) 末影人【跑不掉】, 不要跑: 它瞬移贴脸, retreat 时
        // bot 从 x=806 跑到 812 而末影人始终贴着 1.2~3.0 格, 逃跑只延长挨打时间
        // (22:46:50~53 实锤, 且 retreat 还因"威胁短暂离池"被假完成)。用户定: 单只
        // 末影人 → 战斗到底。
        // (2026-09-21 用户"打末影人不能逃跑, 修过好几次了怎么还是触发") 删掉
        // "两只及以上仍走围殴判定"的触发: 20260921-173522 实锤末地双末影人愤怒
        // → RETREAT 跑了 6 秒 (17:36:08~14), 末影人瞬移始终贴 0.9~4.2 格,
        // bot 照样死 (17:36:14 RESPAWN death #1) —— 逃跑对末影人零收益, 且末地
        // 直线逃跑还可能跑向岛缘虚空。单目标锁 (KillEntitiesTask) 已保证一次只
        // 接战一只, 第二只只是"在场愤怒", 不构成"正在被围殴"。
        int endermanCount = 0;
        for (Entity h : hostiles) {
            if (h == null || !h.isAlive()) continue;
            // (2026-09-25 苦力怕诱爆) 诱爆目标不算威胁: 它正被拉到安全环带
            // 等自爆, "存在苦力怕→必逃"判据会抢在诱爆前面触发逃跑。
            if (CreeperBaitSignal.isBaiting(h)) continue;
            if (h instanceof EndermanEntity) { endermanCount++; continue; }
            if (h instanceof CreeperEntity) { anyCreeper = true; continue; }
            // (2026-09-30 用户拍板) 掠夺者/卫道士 → 死战名单, 先于 isRangedThreat
            // (掠夺者弩在远程名单里, 不截走会照旧触发必逃)。
            if (h instanceof PillagerEntity || h instanceof VindicatorEntity) {
                raidIllagerCount++;
                continue;
            }
            if (isRangedThreat(h)) { anyRanged = true; continue; }
            if (h instanceof PiglinBruteEntity) {
                bruteCount++;
                continue;
            }
            if ((h instanceof PiglinEntity || h instanceof PiglinBruteEntity)
                    && VisibilityHelper.isEntityVisible(mod, h)) {
                piglinSwarm++;
            }
            meleeCount++;
        }
        // (2026-09-30 用户拍板"碰到苦力怕不应该逃跑, 不管有没有盾") 苦力怕不再
        // 参与 retreat 触发: anyCreeper 原先是硬触发项, 但两个专用机制 (盾核弹
        // /诱爆) 都只在 stateEvaluate 更早的分支里 —— 未点火苦力怕两者都不接
        // (核弹要 fuse>0.001, 诱爆要 !canShieldNuke), 全部落到本判据无脑逃
        // (20260930-174725 实锤: 17:58:35 [RETREAT-DEC] isCreeper=true
        // decide=retreat health=16.6, 全场 0 条 [Diag-creeper]/[BAIT])。逃跑
        // 又导致苦力怕永远凑不近 3 格点火 → 核弹永远等不到 → 死循环逃。
        // 未点火 = 不理会 (走近点火后由前置分支接管: 有盾举盾接爆 / 没盾诱爆)。
        boolean retreat = piglinSwarm > 2 || bruteCount > 0 || anyRanged
                || meleeCount >= RETREAT_MELEE_THRESHOLD;
        // (2026-09-21) 末影人不再参与 retreat 触发: 原判据 endermanCount>=2 已删,
        // 理由见上方 endermanCount 声明处注释。
        // (2026-09-18 用户拍板) 低血量硬闸: 血量 ≤ 6 (3 颗心) 无条件撤 —— 一击必死
        // 的残血状态不该还在"近战少怪直接打"。末影人场景除外: 它跑不掉, 撤退只是
        // 把死亡拖长, 按用户拍板战斗到底 (至少能打死它拿珍珠)。
        // (2026-09-21) 末影人已全数量级不触发 retreat, 本行 endermanCount==0 的
        // 豁免保留语义不变: 场上有末影人时不因低血量强制撤 (撤也撤不掉)。
        if (!retreat && endermanCount == 0 && mod.getPlayer().getHealth() <= LOW_HEALTH_FORCE_RETREAT) {
            // (2026-09-30 用户拍板) 掠夺者/卫道士在场时, 低血量逃生改为"有食物才撤":
            // 撤退目的 = 拉开距离吃一口回血再回来打 (撤退结束 → EVALUATE → 进食链
            // 接管回血, 恢复后下一轮接战即 FIGHT); 没食物撤了也是白挨打 → 必须击杀。
            if (raidIllagerCount == 0 || StorageHelper.calculateInventoryFoodScore(mod) > 0) {
                retreat = true;
            }
        }
        // (2026-09-19 18:55 用户"有药水效果还逃跑应该避免"/"墙完工坚守到8分钟") 安全
        // 屋豁免: 威胁只有烈焰人时 (火伤被防火效果免疫 / 被围场墙挡), 人在自己的围场
        // 里不撤 —— 留在墙里打到收药冷却到点, 由 CollectBlazeRodsTask 主动出去收药。
        //
        // (2026-09-20 10:26 用户"有药水还错误触发了举盾和逃跑"/"bot 跑了, 他没有反击")
        // 扩容到【建墙期】: 原判据复用 isEnclosureSafeHaven → 要求围场【完工 95%】,
        // 于是"边建墙边被追"整段都判不了坚守。20260920-102209 实锤 10:24:02:
        //   [RETREAT-DEC] ... isRanged=true health=11.1 safeHavenHold=false decide=retreat
        // 当时墙只到 75.7% (18 缺块, 10:23:39 扫描)、bot 10:23:37 刚喝过防火药水、
        // 威胁清一色烈焰人 → 仍撤退, 一路跑出 45 格。而建墙期恰恰最需要留在原地:
        // 跑掉以后墙永远建不完, 下一轮回来还得从头挨打。改判"有自己的刷怪笼围场 +
        // 有防火效果"即可坚守。
        // 保命底线: 血量 ≤ LOW_HEALTH_FORCE_RETREAT(6) 时【不豁免】照旧撤 (用户原话
        // "血量还没到很低的时候", 真残血撤离是对的)。骷髅/苦力怕/猪灵围殴等其他
        // 威胁照旧撤; 末影人除外 (0921: 全数量级战斗到底, 场上有末影人时不强制撤)。
        boolean safeHavenHold = false;
        if (retreat && !anyCreeper && bruteCount == 0 && piglinSwarm <= 2
                && meleeCount < RETREAT_MELEE_THRESHOLD && endermanCount < 2
                && mod.getPlayer().getHealth() > LOW_HEALTH_FORCE_RETREAT
                && CollectBlazeRodsTask.isBlazeFarmHold(mod)) {
            boolean allRangedBlaze = hostiles.stream()
                    .filter(h -> h != null && h.isAlive() && isRangedThreat(h))
                    .allMatch(h -> h instanceof BlazeEntity);
            if (allRangedBlaze) {
                retreat = false;
                safeHavenHold = true;
            }
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - _lastRetreatDecLogMs > 500) {
            _lastRetreatDecLogMs = nowMs;
            String m = "[RETREAT-DEC] meleeCount=" + meleeCount
                    + " endermanCount=" + endermanCount
                    + " raidIllagerCount=" + raidIllagerCount
                    + " piglinSwarm=" + piglinSwarm
                    + " bruteCount=" + bruteCount
                    + " isRanged=" + anyRanged
                    + " isCreeper=" + anyCreeper
                    + " health=" + String.format("%.1f", mod.getPlayer().getHealth())
                    + " safeHavenHold=" + safeHavenHold
                    + " decide=" + (retreat ? "retreat" : "fight");
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
        return retreat;
    }

    /**
     * (2026-09-01 用户) "近战少怪直接打"提交: 把给定近战怪批次拉进 FIGHT, 按
     * 自信战斗处理(中途不因威胁公式撤退)。不 setTask(由 stateFight 统一派发
     * KillEntitiesTask), 且绝不动 getPriority —— 无递归风险。
     */
    private float commitMeleeFight(AltoClef mod, long nowTick, List<Entity> hostiles) {
        Debug.logMessage("Combat decision: FIGHT (melee-few, no ranged/creeper), enemies=" + hostiles.size());
        _confidentFight = true;
        _inCombat = true;
        _combatStartTick = nowTick;
        _runAwayTask = null;
        for (Entity h : hostiles) {
            if (h != null && h.isAlive() && !_fightTargets.contains(h)) _fightTargets.add(h);
        }
        _defenseState = DefenseState.FIGHT;
        return 65.0f;
    }

    /**
     * (2026-09-30 C, 用户拍板) 掠夺者/卫道士死战覆盖 —— 修复"追了一路却被证据门
     * 过滤成不存在"的缺口 (20260930-120804 实锤: 掠夺者 4.5 格 angry 正追, bot
     * 背包有石剑, 但全程零伤害事件 + 目标不在 FOV 内 → filterEngagementEvidence
     * 过滤后清单为空 → 跳过 shouldRetreatMelee 直落无条件逃跑, 全程 0 条
     * [RETREAT-DEC])。激怒状态的掠夺者/卫道士【本身就是交战证据】, 直接进战斗,
     * 不再经证据门; 不限数量 (0930 拍板), 未点燃苦力怕不拦死战 (点燃中的苦力怕
     * 由 stateEvaluate 前置诱爆/举盾分支处理)。
     * 没剑时: 材料齐 (全在背包) → 先做剑 (15s 超时兜底); 材料不够或做剑失败
     * → 空手也必须战 (用户"跑不过, 跑也没用")。
     *
     * @return 非 null = 已接管 (FIGHT / 做剑), 调用方直接返回该优先级;
     *         null = 本覆盖不适用 (附近没有激怒掠夺者/卫道士), 走原逻辑。
     */
    private Float tryRaidIllagerOverride(AltoClef mod, long nowTick, List<Entity> hostiles) {
        List<Entity> raid = null;
        for (Entity h : hostiles) {
            if (h == null || !h.isAlive()) continue;
            if (h instanceof PillagerEntity || h instanceof VindicatorEntity) {
                if (raid == null) raid = new ArrayList<>();
                raid.add(h);
            }
        }
        if (raid == null) {
            // 掠夺者/卫道士清空后重置做剑尝试标记, 下次遇袭允许重新尝试做剑。
            _raidSwordCraftFailed = false;
            _swordCraftTask = null;
            return null;
        }
        if (hasWeaponToFight(mod)) {
            return commitRaidIllagerFight(mod, nowTick, raid, "armed");
        }
        // 没剑: 材料够 (全在背包, squash 链只补合成不外出挖料) 且本波还没失败过 → 先做剑。
        if (!_raidSwordCraftFailed) {
            if (_swordCraftTask == null) {
                Task craft = makeSwordCraftTaskIfMaterials(mod);
                if (craft != null) {
                    _swordCraftTask = craft;
                    _swordCraftStartTick = nowTick;
                    Debug.logMessage("[RAID-ILLAGER] no sword but materials ready -> crafting sword first");
                } else {
                    _raidSwordCraftFailed = true;
                    Debug.logMessage("[RAID-ILLAGER] no sword, no materials -> bare-handed fight");
                }
            }
            if (_swordCraftTask != null) {
                boolean timedOut = nowTick - _swordCraftStartTick > RAID_SWORD_CRAFT_TIMEOUT_TICKS;
                // isFinished 需要任务至少跑过一 tick (先 start 再查完成)。
                boolean finished = nowTick > _swordCraftStartTick && _swordCraftTask.isFinished(mod);
                if (finished || timedOut) {
                    boolean gotSword = finished && hasWeaponToFight(mod);
                    _raidSwordCraftFailed = true;
                    _swordCraftTask = null;
                    Debug.logMessage("[RAID-ILLAGER] sword craft "
                            + (gotSword ? "done" : (timedOut ? "timed out" : "failed"))
                            + " -> fight");
                    return commitRaidIllagerFight(mod, nowTick, raid, gotSword ? "sword crafted" : "bare-handed");
                }
                setTask(_swordCraftTask);
                return 60.0f;
            }
        }
        return commitRaidIllagerFight(mod, nowTick, raid, "bare-handed");
    }

    /**
     * (2026-09-30 C) 做剑目标: 材料【全在背包内】才派 (squash 链只补合成步骤
     * —— 木棍/木板, 不外出挖料; 追击场景外出挖料 = 白给)。档位: 圆石 ≥2 → 石剑
     * (0929 补剑闸同款 "材料齐备才选该档"), 否则木剑; 1 根原木可出 4 木板
     * (2 板 + 棍足够)。都凑不齐返回 null (空手战)。
     */
    private static Task makeSwordCraftTaskIfMaterials(AltoClef mod) {
        int cobble = StorageHelper.countItemInInventoryReal(mod, Items.COBBLESTONE);
        int stick = StorageHelper.countItemInInventoryReal(mod, Items.STICK);
        int logs = 0;
        for (Item l : RAID_LOG_ITEMS) logs += StorageHelper.countItemInInventoryReal(mod, l);
        int planks = 0;
        for (Item p : RAID_PLANK_ITEMS) planks += StorageHelper.countItemInInventoryReal(mod, p);
        boolean woodBase = logs >= 1 || (planks >= 2 && stick >= 1);
        if (cobble >= 2 && (stick >= 1 || woodBase)) {
            return TaskCatalogue.getSquashedItemTask(new ItemTarget(Items.STONE_SWORD, 1));
        }
        if (woodBase) {
            return TaskCatalogue.getSquashedItemTask(new ItemTarget(Items.WOODEN_SWORD, 1));
        }
        return null;
    }

    /**
     * (2026-09-30 C) 掠夺者/卫道士死战提交: 与 commitMeleeFight 同构, 但战斗
     * 名单只收掠夺者/卫道士 + 贴脸近战怪 (苦力怕不进名单, 交给诱爆/举盾分支)。
     * _confidentFight=true = 死战 (中途不因威胁公式撤退), 0930 拍板"不限数量"。
     */
    private float commitRaidIllagerFight(AltoClef mod, long nowTick, List<Entity> raid, String note) {
        Debug.logMessage("Combat decision: FIGHT (raid-illager must-kill, " + note + ", raid=" + raid.size() + ")");
        _confidentFight = true;
        _inCombat = true;
        _combatStartTick = nowTick;
        _runAwayTask = null;
        for (Entity h : raid) {
            if (h != null && h.isAlive() && !_fightTargets.contains(h)) _fightTargets.add(h);
        }
        addCloseMeleeTargets(mod);
        _swordCraftTask = null;
        _defenseState = DefenseState.FIGHT;
        return 65.0f;
    }

    /**
     * (2026-09-30 B: 逃跑前先清贴脸, 用户拍板"战斗中优先打近的怪") 决定 RUN 之前
     * 的最后闸门: 批次里有【贴脸(攻击距离内)+已敌对】的近战怪时, 先杀掉再跑 ——
     * 0930 会话五连死实锤 (death#1 铁剑+盾 / death#4 石剑+盾 仍全程 RETREAT 到死):
     * shouldRetreatMelee 的 anyRanged 判据让"10 格外有只骷髅"就把贴脸僵尸也一起
     * 逃掉, 背对着贴脸怪跑 = 白挨刀, 且逃逸 maxDist=45 对箭程 15 的骷髅本来就
     * 逃不掉。这不是新索敌 (贴脸立即杀 L1038 / addCloseMeleeTargets 最近优先
     * 早就存在), 是把被整批撤退吃掉的那条既有拍板放行。
     * 门槛照旧: 有剑 (08-30"没剑不硬杀") + 血量 > 6 (09-18"残血无条件撤",
     * 末影人豁免口径不变)。只清贴脸的, 批次其余成员不动 —— 杀完回 EVALUATE
     * 重新判, 骷髅还够得着就由 must-kill (A: 亲眼可见主动索敌) 接管。
     */
    private boolean tryClearAdjacentBeforeRetreat(AltoClef mod, long nowTick, List<Entity> candidates) {
        if (!hasWeaponToFight(mod)) return false;
        if (mod.getPlayer().getHealth() <= LOW_HEALTH_FORCE_RETREAT) return false;
        List<Entity> adjacent = new ArrayList<>(candidates.size());
        for (Entity h : candidates) {
            if (h == null || !h.isAlive()) continue;
            if (h instanceof CreeperEntity) continue;   // 苦力怕走举盾接爆/诱爆分支
            if (isRangedThreat(h)) continue;            // 远程怪不是"贴脸啃"的来源
            if (!h.isInRange(mod.getPlayer(), MELEE_ATTACK_RANGE)) continue;
            if (!EntityHelper.isAngryAtPlayer(mod, h)) continue;
            adjacent.add(h);
        }
        if (adjacent.isEmpty()) return false;
        Debug.logMessage("Combat decision: CLEAR-ADJACENT-BEFORE-RUN (point-blank melee, enemies="
                + adjacent.size() + ") — kill them first, then re-evaluate retreat");
        commitMeleeFight(mod, nowTick, adjacent);
        return true;
    }

    /** 16 格内存活敌对怪列表 (自信战斗的感知口径)。 */
    // (20261001) "烦人怪进击杀清单"的触发半径, 单一判定源: stateEvaluate 派发段
    // 与 stateFight 吸收段共用 (此前两处各写一份三元表达式, 已统一)。
    // 激怒猪灵走 12 格 (PIGLIN_ANGRY_KILL_TRIGGER_RANGE), 其余近战怪 5 格。
    private static int annoyingKillTriggerRange(Entity hostile) {
        if (hostile instanceof SkeletonEntity) return SKELETON_KILL_TRIGGER_RANGE;
        if (hostile instanceof WitchEntity) return 18;
        if (hostile instanceof PiglinEntity && EntityHelper.isPiglinProvoked(hostile)) {
            return PIGLIN_ANGRY_KILL_TRIGGER_RANGE;
        }
        return 5;
    }

    private static List<Entity> getNearbyHostiles(AltoClef mod) {
        List<Entity> hostiles;
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            hostiles = mod.getEntityTracker().getHostiles();
        }
        List<Entity> near = new ArrayList<>();
        boolean anySilverfish = false;
        for (Entity hostile : hostiles) {
            if (hostile.isAlive() && hostile.isInRange(mod.getPlayer(), 16)) {
                if (hostile instanceof SilverfishEntity) anySilverfish = true;
                near.add(hostile);
            }
        }
        // (2026-09-21 用户"距离传送门6格远的虫子就不下杀死死命令") 传送门锚点
        // 6 格内的蠹虫从威胁清单剔除: 开门/拆笼作业不被 FIGHT 批次抢占 (蠹虫
        // 威胁极低, 误杀方向代价 >> 忽略方向)。锚点 = BlockTracker 已登记的门框
        // + KnowledgeMemory 的 END_PORTAL 中心 (跨会话也生效); 附近无蠹虫时
        // 零成本跳过。只剔蠹虫, 骷髅等远程威胁照常处理 (must-kill 语义不变)。
        if (anySilverfish && near.removeIf(h -> h instanceof SilverfishEntity
                && isNearEndPortalAnchor(mod, h.getBlockPos()))) {
            Debug.logMessage("[MOB-DIAG] silverfish within " + SILVERFISH_PORTAL_IGNORE_RADIUS
                    + " of end portal anchor dropped from threat list (user rule).");
        }
        // [MOB-DIAG] #1 只读: 该触发点的威胁清单 (0.5s 节流)。
        logNearbyHostilesDiag(mod, near);
        return near;
    }

    /**
     * (2026-09-28 低血量进食豁免, 用户"12格内无怪物就触发吃东西") 最近存活敌对怪
     * 的距离; 16 格内无怪 → {@link Double#MAX_VALUE}。复用 {@link #getNearbyHostiles}
     * 感知口径 (含听觉与 20s 记忆 —— 听见的怪也算威胁, 进食判定从保守, 不会为了
     * 吃饭无视墙后的怪)。只读, 不改变威胁评估本身。
     */
    public static double nearestHostileDistance(AltoClef mod) {
        double best = Double.MAX_VALUE;
        for (Entity h : getNearbyHostiles(mod)) {
            double d = mod.getPlayer().distanceTo(h);
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * (2026-09-24 派发证据门, 事故 20260924-230344) 主动猎杀类派发 (自信战斗/
     * 近战直打) 只认有"交战证据"的怪: 【当前亲眼可见】或【实际伤害过 bot】
     * (60s 窗口)。getNearbyHostiles 的感知口径含听觉与 20s 记忆 —— 墙后/
     * 地下的怪只是"听见"就进批次会让 bot 主动跑去猎杀从未攻击过我们的敌人
     * (= 透视观感)。仅用于派发门槛; 开战后的批次粘滞/追击不受影响
     * (_fightTargets 持续到死/逃离), 中途吸收新目标走 stateFight 的
     * seesPlayer 门, 口径不变。盾牌核弹/塔逃的【计数】消费方不经过本门
     * (听见的怪计入数量只会更保守, 不会误开战)。
     */
    private static List<Entity> filterEngagementEvidence(AltoClef mod, List<Entity> candidates) {
        List<Entity> evidenced = new ArrayList<>(candidates.size());
        for (Entity e : candidates) {
            if (e == null || !e.isAlive()) continue;
            if (VisibilityHelper.isEntityVisible(mod, e) || HitRegistry.hasRecentlyDamagedPlayer(e)) {
                evidenced.add(e);
            }
        }
        return evidenced;
    }

    /** (2026-09-21) 传送门 6 格内蠹虫豁免的判定半径 (用户指定 6 格)。 */
    private static final double SILVERFISH_PORTAL_IGNORE_RADIUS = 6.0;

    /**
     * (2026-09-22 用户"未点火直接就不管了才对, 瞎wander干什么") 未点火苦力怕
     * 一律不进战斗批次: 它不在 KillEntitiesTask 候选类别里 (近战打苦力怕=错误),
     * 进了批次只会让"批次清空"判据永远不满足 → KillEntitiesTask 空转 wander
     * 对峙 (112130 实锤: 僵尸 2.3s 击杀后, 未点火苦力怕滞留批次 wander 14s)。
     * 点火后由 stateEvaluate/stateFight 前置 fusing 分支接手 (盾核/撤退, 保批次),
     * 与本豁免不冲突。口径与 {@link #getClosestFusingCreeper} 一致 (fuse<0.001=未点火)。
     */
    private static boolean isUnfusingCreeper(Entity e) {
        return e instanceof CreeperEntity && ((CreeperEntity) e).getClientFuseTime(1) < 0.001;
    }

    /**
     * 传送门锚点 = BlockTracker 已登记的 END_PORTAL_FRAME (本会话亲眼见过)
     * ∪ KnowledgeMemory 的 END_PORTAL 中心 (跨会话记忆)。都没有 → false。
     */
    private static boolean isNearEndPortalAnchor(AltoClef mod, BlockPos mobPos) {
        for (BlockPos frame : mod.getBlockTracker().getKnownLocations(Blocks.END_PORTAL_FRAME)) {
            if (frame != null && frame.isWithinDistance(mobPos, SILVERFISH_PORTAL_IGNORE_RADIUS)) {
                return true;
            }
        }
        Vec3d player = mod.getPlayer().getPos();
        for (BlockPos center : mod.getKnowledgeMemory().getRememberedCenters(
                KnowledgeMemory.StructureType.END_PORTAL, player, SILVERFISH_PORTAL_IGNORE_RADIUS + 16)) {
            if (center != null && center.isWithinDistance(mobPos, SILVERFISH_PORTAL_IGNORE_RADIUS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * (2026-09-19 20:10 用户"造墙任务被 Staying committed 抢了, 一直打不造墙")
     * 围场施工让位: CollectBlazeRodsTask 正在造围墙 ({@code _enclosureBuildActive})
     * 且附近敌对怪【全是】烈焰人时, 防御链对烈焰人整体让位 —— 它自有一套施工期
     * 语义 (到笼先喝药 :319 / 围场内限定击杀 / 断药转收药 / 血量低逃跑兜底),
     * 防御链的 FIGHT(prio 65) / RETREAT(prio 80) 一插手优先级都高于造墙所在的
     * 用户任务, 而刷怪笼持续出怪 → FIGHT 批次永远吸收新怪清不完 → 造墙饿死
     * (195218 实锤: 20:02:33 进 FIGHT 后一直在 Killing blaze, 墙卡在 10 缺块)。
     * 只要有任何非烈焰人敌对怪 (骷髅/苦力怕/猪灵等) 在场 → 不让位, 照常处理。
     */
    private boolean shouldYieldBlazesToEnclosureBuild(AltoClef mod) {
        if (!CollectBlazeRodsTask.isEnclosureBuildWorkActive()) return false;
        List<Entity> hostiles;
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            hostiles = mod.getEntityTracker().getHostiles();
        }
        boolean anyHostile = false;
        for (Entity h : hostiles) {
            if (h == null || !h.isAlive()) continue;
            if (!(h instanceof BlazeEntity)) return false;
            anyHostile = true;
        }
        return anyHostile;
    }

    /**
     * (2026-08-23) 尝试自信战斗: 条件满足则提交 FIGHT 批次 (16 格内全部
     * 存活怪), 返回 true。调用方直接 return 65。
     */
    private boolean tryConfidentFight(AltoClef mod, long nowTick) {
        // (2026-09-24 派发证据门) 只对"亲眼可见或实际打过我们"的怪开自信战斗,
        // 听见墙后怪不猎杀 (见 filterEngagementEvidence 注释)。
        List<Entity> near = filterEngagementEvidence(mod, getNearbyHostiles(mod));
        if (!isConfidentFight(mod, near)) return false;
        _confidentFight = true;
        _inCombat = true;
        _combatStartTick = nowTick;
        _runAwayTask = null;
        for (Entity hostile : near) {
            // (2026-09-22 用户"未点火直接不管") 未点火苦力怕不进批次, 见 isUnfusingCreeper。
            if (isUnfusingCreeper(hostile)) continue;
            if (!_fightTargets.contains(hostile)) _fightTargets.add(hostile);
        }
        if (_fightTargets.isEmpty()) {
            // 批次全是未点火苦力怕 → 不开战, 交回 EVALUATE (用户任务继续)。
            // (2026-09-30) 日志移到本检查之后: 原先"CONFIDENT FIGHT"先打、
            // 空批次再返回 false, 同一秒打出逃跑判决 (174725 实锤 17:58:35),
            // 日志自相矛盾误导取证。
            _confidentFight = false;
            _inCombat = false;
            _combatStartTick = 0;
            return false;
        }
        Debug.logMessage("Combat decision: CONFIDENT FIGHT (health=%.0f%%, shield+sword, enemies=%d)",
                100.0 * mod.getPlayer().getHealth() / Math.max(1, mod.getPlayer().getMaxHealth()),
                near.size());
        _defenseState = DefenseState.FIGHT;
        return true;
    }

    private float stateEvaluate(AltoClef mod, long nowTick) {
        // Force field
        doForceField(mod);

        // Run away if a weird mob is close by.
        Optional<Entity> universallyDangerous = getUniversallyDangerousMob(mod);
        if (universallyDangerous.isPresent()) {
            _runAwayTask = new ProgressiveRunAwayTask(DANGER_KEEP_DISTANCE, ESCAPE_STEP, true);
            setTask(_runAwayTask);
            _defenseState = DefenseState.RETREAT;
            return 70;
        }

        _doingFunkyStuff = false;
        // Run away from creepers (or shield-block the explosion when safe).
        CreeperEntity blowingUp = getClosestFusingCreeper(mod);
        // (2026-09-25 183242 实锤缺口) 原先本分支只做 核弹→自信战斗→逃跑 三选一,
        // 从不创建指向苦力怕的 kill task → AbstractKillEntityTask 的诱爆重定向
        // 永不触发, [BAIT] 一条没打 (18:46:48 现场走了 FIGHT→RETREAT 横跳)。
        // 改为: 没盾时在本分支直接派发/持有诱爆任务 (用户拍板: 苦力怕一律诱爆不近战)。
        // (2026-09-25 191451/224856 两次实跑) 持有先行, 单一事实源 = CreeperBaitSignal:
        // 批次内 AbstractKillEntityTask 重定向创建的诱爆实例不经过链级 _baitTask
        // 字段, 旧持有判据看不见它 → 批次重派 → 重定向再新建, 每 0.5s Task
        // STOP/START + [BAIT] start ×17 (22:50 实锤)。信号目标存活且在持有半径内:
        // ① 链级实例还活着 → 继续持有; ② 非守卫持有 → 收编为链级持有 (一次替换,
        // 之后稳定); ③ 守卫持有 (keepClear 语义) → 不抢。有盾仍让位核弹。
        if (!canShieldNuke(mod) && isBaitSignalTargetInHoldRange(mod)) {
            CreeperEntity sigTarget = getBaitSignalTarget(mod);
            if (sigTarget != null) {
                if (_baitTask != null && !_baitTask.isFinished(mod)) {
                    _doingFunkyStuff = true;
                    setTask(_baitTask);
                    _defenseState = DefenseState.EVALUATE;
                    return 55;
                }
                if (!CreeperBaitSignal.isGuarded()) {
                    _baitTask = new BaitCreeperDetonationTask(sigTarget, null);
                    _doingFunkyStuff = true;
                    setTask(_baitTask);
                    _defenseState = DefenseState.EVALUATE;
                    return 55;
                }
            }
        }
        // 派发提前: 未点燃但 ≤8 格逼近的苦力怕也派发 (LURE 相提前占位控距);
        // 单槽信号: 已有诱爆在跑 (守卫) 时不另派第二只, 照旧走逃跑兜底。
        CreeperEntity baitCandidate = (blowingUp != null) ? blowingUp : getNearestBaitableCreeper(mod);
        if (baitCandidate != null && !canShieldNuke(mod)
                && (!CreeperBaitSignal.isActive() || CreeperBaitSignal.isBaiting(baitCandidate))) {
            if (CreeperBaitSignal.isBaiting(baitCandidate)) {
                if (_baitTask == null || _baitTask.isFinished(mod)) {
                    // 诱爆由其他链 (熔炉守卫) 持有 → 不抢, 保持原豁免口径不干预
                    blowingUp = null;
                }
            } else {
                // 没盾 → 派发诱爆, 优先于自信战斗与逃跑
                _baitTask = new BaitCreeperDetonationTask(baitCandidate, null);
                _doingFunkyStuff = true;
                setTask(_baitTask);
                _defenseState = DefenseState.EVALUATE;
                return 55;
            }
        }
        if (blowingUp != null) {
            float fuse = blowingUp.getClientFuseTime(1);
            // (2026-09-16) [Diag-creeper] 原每 tick 都 Debug.logMessage 刷聊天, 苦力怕引信期间
            // 每秒 20 条刷屏。改为只落盘 altoclef_diag.log (不进聊天), 并加 500ms 节流,
            // 与同文件 [RETREAT-DEC]/[MOB-DIAG] 诊断日志节流写法一致。注意: 只动这一处,
            // 不动 [Diag-flow]/[Diag-chain]/[Diag-blind]/[Diag-bed] 等其他 tag。
            long creeperDiagNow = System.currentTimeMillis();
            if (creeperDiagNow - _lastCreeperDiagMs > MOB_DIAG_INTERVAL_MS) {
                _lastCreeperDiagMs = creeperDiagNow;
                Debug.logToFile("altoclef_diag.log", String.format(
                        "[Diag-creeper] fuse=%.2f, health=%.1f/%.1f, hasShield=%s, nearbyHostiles=%d, distance=%.1f",
                        fuse, mod.getPlayer().getHealth(), mod.getPlayer().getMaxHealth(),
                        ThreatEvaluator.hasShield(mod), getNearbyHostiles(mod).size(),
                        Math.sqrt(blowingUp.squaredDistanceTo(mod.getPlayer()))));
            }
            // (2026-08-30 有盾就立刻格挡, 用户"副手拿盾也跑") 去掉了 fuse>=0.3 门槛:
            // fuse 早期(<0.3) bot 之前也直接逃, 即使副手拿盾。改为: 有盾且苦力怕
            // 在 10 格内 → 任意 fuse 都原地举盾接爆, 不再逃。只有没盾/被围攻才
            // 降到自信战斗或逃跑兜底。
            boolean closeEnough = blowingUp.squaredDistanceTo(mod.getPlayer())
                    <= CREEPER_SHIELD_BLOCK_RANGE * CREEPER_SHIELD_BLOCK_RANGE; // 有盾接爆范围(16格)内才是威胁
            if (closeEnough && canShieldNuke(mod)) {
                _doingFunkyStuff = true;
                boolean shielding = doShieldNuke(mod, blowingUp);
                if (shielding) {
                    // (2026-09-02 苦力怕修复根因) 不再假装 RETREAT: stateRetreat
                    // 开头会无条件放盾 + 清 _shieldNukeStartTick, 盾牌核弹第 2 tick
                    // 就被打断。举盾持久化改由 getPriorityInner 顶部统一接管,
                    // 这里保持 EVALUATE, 下一 tick 顶部检查 _shieldNukeStartTick>=0
                    // 继续 hold。优先级 90 确保抢在普通 fight/逃跑前。
                    _defenseState = DefenseState.EVALUATE;
                    _runAwayTask = null;
                    return 90;
                }
            } else if (closeEnough) {
                Debug.logMessage("Shield Nuke unavailable (no shield or swarmed): fuse=%.2f, hasShield=%s, nearbyHostiles=%d",
                        fuse, ThreatEvaluator.hasShield(mod), getNearbyHostiles(mod).size());
            }
            // (2026-08-23) 苦力怕fuse<0.3时，如果满足自信战斗条件，应该尝试战斗而不是逃跑
            // 这样可以避免"看到苦力怕就跑"的行为，让bot更智能地处理威胁
            if (fuse < 0.3f && tryConfidentFight(mod, nowTick)) {
                return 65;
            }
            _doingFunkyStuff = true;
            _runAwayTask = new RunAwayFromCreepersTask(CREEPER_KEEP_DISTANCE);
            setTask(_runAwayTask);
            _defenseState = DefenseState.RETREAT;
            return 50 + blowingUp.getClientFuseTime(1) * 50;
        }

        // 清理残留的举盾格挡 (弹射物已消失/远离时放盾) — 2026-08-23。
        if (_projectileBlocking && !isProjectileClose(mod)) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            _projectileBlocking = false;
        }
        // (2026-08-30 已开火骷髅必须杀, 用户) 有【已经开始攻击 bot 的骷髅】时,
        // 锁定击杀并提到最高优先: 逃跑不是选项 (骷髅边追边射, 尤其血量低时
        // 背对着跑=白吃箭等着死), 直接上。直到目标死/逃出范围才交还控制。
        // (2026-09-24 根修) "已开始攻击"的判定从 hostile+感知 收紧为
        // HitRegistry 实锤 (实际伤害过 bot, 60s 窗口) —— 见 getActiveRangedAttacker。
        Entity activeAttacker = getActiveRangedAttacker(mod);
        // (2026-08-30 没剑不硬杀, 用户) 刚死复活身上啥都没有时, must-kill 是
        // 自杀 —— 没武器(连剑都没有)却锁死"必须击杀"只会原地转圈找视线被活活
        // 射死 (用户实测卡在 "Killing entity.minecraft.skeleton / repositioning
        // for line of sight")。有剑才值得拼杀; 没剑就交给下方正常威胁评估
        // (无胜算 → 逃跑/躲), 不进入加急锁定。
        if (activeAttacker != null && hasWeaponToFight(mod)) {
            // (2026-09-24) hit-confirmed 标记: 能走到这的骷髅必然实际伤害过 bot
            // (HitRegistry 实锤), 与"听见/看见但未开火"严格区分。
            Debug.logMessage("Combat decision: MUST-KILL active skeleton shooter (hit-confirmed) at "
                    + activeAttacker.getBlockPos().toShortString());
            _mustKillAttacker = true;
            _inCombat = true;
            _combatStartTick = nowTick;
            if (!_fightTargets.contains(activeAttacker)) _fightTargets.add(activeAttacker);
            // (2026-09-17 用户拍板: 小白必须击杀, 但先解决贴脸的) 把贴脸近战怪一并
            // 纳入击杀名单。KillEntitiesTask 按"最近优先"选目标, 于是先清掉 1 格的
            // 僵尸, 再回头打 10 格外的骷髅。图钉与 prio 95 全程保持, 绝不转逃。
            addCloseMeleeTargets(mod);
            _runAwayTask = null;
            _defenseState = DefenseState.FIGHT;
            return ACTIVE_ATTACKER_PRIORITY;
        }
        // 弹射物 (箭/烈焰人火球): 持盾格挡替换躲火球 (2026-08-23 用户设计)。
        // 旧逻辑 DodgeProjectilesTask 让 bot 躲开弹射物 → 打烈焰人打一下躲一下
        // 反复横跳。现在改为: 有盾时面朝弹射物举盾格挡 (不躲, 原地站撸);
        // 没盾才退回躲避。格挡结束弹射物消失时上方统一放盾。
        // (2026-08-23 战斗中不抢占) 正在打任务目标 (_targetEntity != null) 时
        // 不触发本链格挡 — 小白/苦力怕/烈焰人 (2026-09-06 加入盾战) 均由
        // KillEntityTask 的 shieldCombatTick 处理 (举盾+反击), 否则这里
        // prio 65 抢占 → bot 一直举盾不打 (用户: "盾牌还是不放")。
        if (_targetEntity == null && !mod.getFoodChain().isTryingToEat() && mod.getModSettings().isDodgeProjectiles() && isProjectileClose(mod)) {
            // (2026-09-19 搭路禁举盾, 用户) baritone 正在搭方块 (搭柱/搭桥/上行/
            // 斜行或 builder) 时【绝对禁止】举盾, 也不要躲避: 格挡会 cancelEverything,
            // 防御瞄准(requestDefenseAim)会抢 baritone 放方块需要的转头 → 搭路中断,
            // 半空就是坠落 (0919 搭路靠近烈焰人摔死实锤)。搭路期间弹射物防御整体
            // 让位, 走完这段再防 (有防火药水时烈焰火球本就不构成致命威胁)。
            if (BaritoneHelper.isBaritoneBuilding()) {
                logShieldSuppressedForBuilding(mod);
            } else {
                _doingFunkyStuff = true;
                if (doProjectileBlock(mod)) {
                    _projectileBlocking = true;
                    _runAwayTask = null;
                    return 65;
                }
                _projectileBlocking = false;
                _runAwayTask = null;
                setTask(new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL));
                return 65;
            }
        }

        // Dodge all mobs cause we boutta die son
        if (isInDanger(mod)) {
            _doingFunkyStuff = true;
            if (_targetEntity == null) {
                // (2026-08-23 自信战斗提前) isVulnurable 按护甲值判定
                // (armor<5 && health<18 即脆弱) — 铁阶段只做剑+盾没穿甲的
                // bot 掉 2 颗心就触发本分支逃跑 (prio 70), 排在后面的自信
                // 战斗分支 (annoying hostiles 判定内) 根本执行不到 (用户实测
                // 00:02 连续 Escaping from hostiles)。用户规则: 健康看血量
                // (≥70%) 不看护甲, 有盾有剑 + ≤3 只普通怪 → 直接打。
                if (tryConfidentFight(mod, nowTick)) {
                    return 65;
                }
                // (2026-09-30 C 掠夺者/卫道士死战覆盖) 激怒的掠夺者/卫道士本身就
                // 是交战证据, 直接进战斗/先做剑, 不再被下方证据门过滤成"无证据"
                // (120804 实锤: 追了一路的掠夺者被过滤 → 无条件逃跑, 判决 0 条)。
                Float raidOverride = tryRaidIllagerOverride(mod, nowTick, getNearbyHostiles(mod));
                if (raidOverride != null) return raidOverride;
                // (2026-09-01 用户) 纯近战少怪(1~2, 无远程/无苦力怕)直接打, 不逃。
                // 保留有剑门槛: 手无寸铁硬顶也没用, 交给下方逃跑兜底。
                // (2026-09-24 派发证据门) 只对"亲眼可见或实际打过我们"的怪近战直打,
                // 听见墙后怪不猎杀; 全无证据 → 落到下方逃跑兜底 (危险时可先躲)。
                List<Entity> nearMelee = filterEngagementEvidence(mod, getNearbyHostiles(mod));
                // (2026-09-30 血量下限, 180851 18:11:47 实锤) 6.1 血先 commitMeleeFight
                // 进 FIGHT, 47ms 后 stateFight 中盘 "RUN mid-confident-fight (health
                // low)" (<40%) 踢去逃跑 —— 入场与中盘两道闸不同线, 自我打架。入场
                // 就用同一条 40% 线: 血量不够近战直打 → 直接落到下方逃跑, 不演 FIGHT。
                boolean meleeHpOk = mod.getPlayer().getHealth() >= mod.getPlayer().getMaxHealth() * 0.4f;
                if (!nearMelee.isEmpty() && meleeHpOk && hasWeaponToFight(mod) && !shouldRetreatMelee(mod, nearMelee)) {
                    return commitMeleeFight(mod, nowTick, nearMelee);
                }
                // (2026-09-30 B) 整批判逃前, 贴脸近战怪先清掉再跑 (0930 五连死缺口)。
                if (tryClearAdjacentBeforeRetreat(mod, nowTick, nearMelee)) {
                    return 65.0f;
                }
                _runAwayTask = new ProgressiveRunAwayTask(DANGER_KEEP_DISTANCE, ESCAPE_STEP, true);
                setTask(_runAwayTask);
                _defenseState = DefenseState.RETREAT;
                return 70;
            }
        }

        if (mod.getModSettings().shouldDealWithAnnoyingHostiles()) {
            // Deal with hostiles because they are annoying.
            List<Entity> hostiles;
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                hostiles = mod.getEntityTracker().getHostiles();
            }

            List<Entity> toDealWith = new ArrayList<>();

            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                // (2026-09-19 20:10 造墙施工让位) 施工中且威胁全是烈焰人 → 烈焰人
                // 不进击杀清单, 交给 CollectBlazeRodsTask 自己处理, 防御链不插手。
                boolean yieldBlazes = shouldYieldBlazesToEnclosureBuild(mod);
                for (Entity hostile : hostiles) {
                    if (yieldBlazes && hostile instanceof BlazeEntity) continue;
                    // (2026-09-22 用户"未点火直接不管") 苦力怕不进击杀清单: 未点火
                    // 无视 (点火 → stateEvaluate 前置 fusing 分支已接手), 否则 12s
                    // 计时进批次后 KillEntitiesTask 无候选 → wander 对峙 (112130 实锤)。
                    if (hostile instanceof CreeperEntity) continue;
                    // (2026-09-21 用户"距离传送门6格远的虫子就不下杀死死命令") 威胁
                    // 公式路径不走 getNearbyHostiles 漏斗, 必须在这里同款剔除 ——
                    // 否则蠹虫照样进 _closeAnnoyingEntities 计时 → FIGHT (131443
                    // 实锤: nearbyHostiles=0 仍提交 FIGHT enemies=1)。
                    if (hostile instanceof SilverfishEntity
                            && isNearEndPortalAnchor(mod, hostile.getBlockPos())) {
                        continue;
                    }
                    // (2026-09-19 用户) 小白触发距离 18 → 16 (刚好触发小白的距离), 见 SKELETON_KILL_TRIGGER_RANGE。
                    // (20261001) 触发半径统一走 annoyingKillTriggerRange (激怒猪灵 12 格)。
                    int annoyingRange = annoyingKillTriggerRange(hostile);
                    boolean isClose = hostile.isInRange(mod.getPlayer(), annoyingRange);

                    if (isClose) {
                        isClose = LookHelper.seesPlayer(hostile, mod.getPlayer(), annoyingRange);
                    }

                    // Give each hostile a timer, if they're close for too long deal with them.
                    if (isClose) {
                        // (2026-09-02 贴脸近战立即击杀, 用户"僵尸在脸上早该杀了, 快死才反应")
                        // 近战怪贴脸(攻击距离内)且正在攻击我们 → 立即进击杀清单, 不等 12s 计时
                        // —— 否则 bot 满血硬吃僵尸 12 秒(日志实锤 19:04 僵尸 0.9 格贴脸, 直到
                        // 血量跌进 isVulnurable 才 FIGHT, 差点死)。只有远处烦人怪才走计时。
                        // (20261001) 激怒猪灵 (EntityHelper.isPiglinProvoked 单一判定源:
                        // HitRegistry 被打记忆 / ANGRY_AT 记忆指向 bot) 同样跳过 12s 计时:
                        // 它已在主动攻击, "不猎杀偶然靠近的怪"的计时目的对它不适用;
                        // 中性猪灵 (无金甲兜底激怒) 仍走 5 格 + 计时口径, 不主动引战。
                        if (!isRangedThreat(hostile)
                                && (EntityHelper.isPiglinProvoked(hostile)
                                        || (hostile.isInRange(mod.getPlayer(), MELEE_ATTACK_RANGE)
                                                && EntityHelper.isAngryAtPlayer(mod, hostile)))) {
                            toDealWith.add(hostile);
                            _closeAnnoyingEntities.remove(hostile);
                        } else {
                            if (!_closeAnnoyingEntities.containsKey(hostile)) {
                                _closeAnnoyingEntities.put(hostile, new TimerGame(mod.getModSettings().getKillHostileWhenCloseForSeconds()));
                                _closeAnnoyingEntities.get(hostile).reset();
                            }
                            if (_closeAnnoyingEntities.get(hostile).elapsed()) {
                                toDealWith.add(hostile);
                            }
                        }
                    } else {
                        _closeAnnoyingEntities.remove(hostile);
                    }
                }

                // Clear dead/non existing hostiles
                List<Entity> toRemove = new ArrayList<>();
                for (Entity check : _closeAnnoyingEntities.keySet()) {
                    if (!check.isAlive()) {
                        toRemove.add(check);
                    }
                }
                for (Entity remove : toRemove) _closeAnnoyingEntities.remove(remove);

                int numberOfProblematicEntities = toDealWith.size();

                if (numberOfProblematicEntities > 0) {

                    // (2026-08-23 自信战斗) 血量健康(≥70%) + 有盾牌 +
                    // 有任意剑 + 16 格内存活敌对怪 ≤3 只且全是普通怪 →
                    // 跳过威胁公式直接开打 (用户设计: 公式偏保守, 有盾有
                    // 剑还老是跑)。isInDanger 分支同款前置检查 (见上方)。
                    if (tryConfidentFight(mod, nowTick)) {
                        return 65;
                    }

                    // (2026-09-30 C 掠夺者/卫道士死战覆盖) 同款: 公式判输也不逃
                    // (跑不过), 死战/先做剑。苦力怕在上方已被剔出 toDealWith。
                    Float raidOverride = tryRaidIllagerOverride(mod, nowTick, toDealWith);
                    if (raidOverride != null) return raidOverride;

                    // Fight or flight: estimate the combined threat and our own strength.
                    double totalThreat = 0;
                    for (Entity hostile : toDealWith) {
                        totalThreat += ThreatEvaluator.evaluate(mod, hostile);
                    }
                    double ourStrength = ThreatEvaluator.estimatePlayerStrength(mod);

                    // (2026-09-11 用户: 空手一律逃不例外) 没剑时连"威胁公式判赢"
                    // 也不进 FIGHT — 空手 ourStrength 低但仍可能赢过单只近战怪,
                    // 照样追着打 (实锤 20260911-205655 场次 21:10:07 无装备追骷髅
                    // 2m48s)。空手 → 直接走下方逃跑。自信战斗/近战直打/must-kill
                    // 入口已有剑门槛, 此处补齐最后一个 FIGHT 入口。
                    if (hasWeaponToFight(mod) && ThreatEvaluator.canWin(totalThreat, ourStrength)) {
                        // We can take them: commit to a FIGHT and stay in it until
                        // the whole batch is dead (集中击杀, 2026-08-21) — the old
                        // code re-evaluated every tick from EVALUATE, so the moment
                        // a hostile stepped out of range the chain released control
                        // back to the user task and we ping-ponged "hit once /
                        // resume task / hit again".
                        Debug.logMessage("Combat decision: FIGHT (threat=%.1f, our=%.1f, enemies=%d)", totalThreat, ourStrength, numberOfProblematicEntities);
                        _confidentFight = false;
                        _inCombat = true;
                        _combatStartTick = nowTick;
                        _runAwayTask = null;
                        for (Entity hostile : toDealWith) {
                            if (!_fightTargets.contains(hostile)) _fightTargets.add(hostile);
                        }
                        _defenseState = DefenseState.FIGHT;
                        return 65;
                    } else {
                        // (2026-09-01 用户) 纯近战少怪直接打: 连威胁公式判输
                        // 也不逃(近战一两只会砍不过), 只有远程/苦力怕/被围殴
                        // 才保留逃跑。需有剑。
                        if (hasWeaponToFight(mod) && !shouldRetreatMelee(mod, toDealWith)) {
                            return commitMeleeFight(mod, nowTick, toDealWith);
                        }
                        // Not worth it: run.
                        // (2026-09-30 B) 整批判逃前, 贴脸近战怪先清掉再跑 (0930 五连死缺口)。
                        if (tryClearAdjacentBeforeRetreat(mod, nowTick, toDealWith)) {
                            return 65.0f;
                        }
                        Debug.logMessage("Combat decision: RUN (threat=%.1f, our=%.1f, enemies=%d)", totalThreat, ourStrength, numberOfProblematicEntities);
                        _inCombat = false;
                        _runAwayTask = new ProgressiveRunAwayTask(DANGER_KEEP_DISTANCE, ESCAPE_STEP, true);
                        setTask(_runAwayTask);
                        _defenseState = DefenseState.RETREAT;
                        return 80;
                    }
                } else {
                    _inCombat = false;
                    _combatStartTick = 0;
                }
            }
        }

        // Continuous reachability awareness: while not fighting, if a
        // remembered high-threat mob can still walk to us, wall off its path.
        handleReachabilityThreats(mod, nowTick);
        if (_defenseState == DefenseState.BLOCK_PATH) {
            return 90;
        }

        // By default if we aren't "immediately" in danger but were running away, keep running away until we're good.
        if (_runAwayTask != null && !_runAwayTask.isFinished(mod)) {
            // (2026-09-25 苦力怕诱爆) 诱爆进行中, 苦力怕逃跑任务失效:
            // 旧 RunAwayFromCreepersTask 的 "≥10 格才算完成" 会把 bot 从诱爆
            // 环带 (5~7 格) 反复拽走 → 熄引信 → 苦力怕重新逼近 → 再逃, 死循环
            // (172433 会话横跳根源之一)。清掉让位给诱爆任务。
            if (CreeperBaitSignal.isActive() && _runAwayTask instanceof RunAwayFromCreepersTask) {
                _runAwayTask = null;
            } else {
                setTask(_runAwayTask);
                return _cachedLastPriority;
            }
        }

        return 0;
    }

    /**
     * FIGHT state (集中击杀): we committed to killing a batch of hostiles.
     * Stay here until the batch is wiped (or timeout / threat flips / we get
     * vulnerable), only THEN hand control back to the user task.
     */
    private float stateFight(AltoClef mod, long nowTick) {
        // A fusing creeper is the one thing that interrupts a committed fight —
        // it can one-shot us, no amount of "focus fire" fixes that.
        CreeperEntity blowingUp = getClosestFusingCreeper(mod);
        // (2026-09-25 183242 实锤缺口) 同 stateEvaluate: 战斗中苦力怕点燃时
        // 原先只做 核弹→逃跑, 从不派发诱爆。没盾时改为派发/持有诱爆,
        // 爆炸后无缝回到本批次战斗; 有盾仍走核弹接管。
        // (2026-09-25 191451/224856) 同 stateEvaluate: 持有先行 (信号单一事实源,
        // 覆盖熄火期), 非守卫持有的诱爆收编为链级持有, 守卫持有不抢, 有盾让位核弹。
        if (!canShieldNuke(mod) && isBaitSignalTargetInHoldRange(mod)) {
            CreeperEntity sigTarget = getBaitSignalTarget(mod);
            if (sigTarget != null) {
                if (_baitTask != null && !_baitTask.isFinished(mod)) {
                    _doingFunkyStuff = true;
                    setTask(_baitTask);
                    float fuse = sigTarget.getClientFuseTime(1);
                    return 60 + fuse * 50;
                }
                if (!CreeperBaitSignal.isGuarded()) {
                    _baitTask = new BaitCreeperDetonationTask(sigTarget, null);
                    _doingFunkyStuff = true;
                    setTask(_baitTask);
                    return 60 + sigTarget.getClientFuseTime(1) * 50;
                }
            }
        }
        CreeperEntity baitCandidate = (blowingUp != null) ? blowingUp : getNearestBaitableCreeper(mod);
        if (baitCandidate != null && !canShieldNuke(mod)
                && (!CreeperBaitSignal.isActive() || CreeperBaitSignal.isBaiting(baitCandidate))) {
            if (CreeperBaitSignal.isBaiting(baitCandidate)) {
                if (_baitTask == null || _baitTask.isFinished(mod)) {
                    // 诱爆由其他链 (熔炉守卫) 持有 → 不抢
                    blowingUp = null;
                }
            } else {
                _baitTask = new BaitCreeperDetonationTask(baitCandidate, null);
                _doingFunkyStuff = true;
                setTask(_baitTask);
                return 60 + baitCandidate.getClientFuseTime(1) * 50;
            }
        }
        if (blowingUp != null) {
            float fuse = blowingUp.getClientFuseTime(1);
            // (2026-09-02 苦力怕修复根因) 去掉 fuse>=0.45 门槛: 苦力怕点燃是从
            // fuse≈0 开始上升的, 战斗中刚点燃的苦力怕 fuse=0.04(日志实锤, 11:00:22.9
            // FIGHT->RETREAT prio=51.78) 远小于 0.45 → 直接走逃跑分支(逃跑必放盾)
            // → 有盾也被炸死。改与 stateEvaluate 口径一致: 有盾且苦力怕在接爆范围
            // (16格)内, 任意 fuse 都原地举盾硬接(用户规则: 有盾接爆 > 逃跑)。
            boolean closeEnough = blowingUp.squaredDistanceTo(mod.getPlayer())
                    <= CREEPER_SHIELD_BLOCK_RANGE * CREEPER_SHIELD_BLOCK_RANGE;
            if (closeEnough && canShieldNuke(mod)) {
                _doingFunkyStuff = true;
                boolean shielding = doShieldNuke(mod, blowingUp);
                if (shielding) {
                    // 保留 FIGHT 状态，不丢 fight targets/task，爆炸后无缝继续打
                    return 60 + fuse * 50;
                }
            }
            _doingFunkyStuff = true;
            _runAwayTask = new RunAwayFromCreepersTask(CREEPER_KEEP_DISTANCE);
            setTask(_runAwayTask);
            _defenseState = DefenseState.RETREAT;
            return 50 + blowingUp.getClientFuseTime(1) * 50;
        }

        // Keep the melee force field up around us while we fight (same as the
        // old EVALUATE-based fighting did).
        doForceField(mod);

        // 1. Drop targets that died, despawned, or genuinely fled.
        // (2026-09-21) 已进批次的蠹虫若落在传送门 6 格内 → 立即弃杀 (用户规则),
        // 否则 KillEntitiesTask 会对着豁免目标 wander 空转找怪 (131443 实锤)。
        _fightTargets.removeIf(e -> e == null || !e.isAlive()
                || !mod.getEntityTracker().isEntityReachable(e)
                || (e instanceof SilverfishEntity && isNearEndPortalAnchor(mod, e.getBlockPos()))
                || Math.sqrt(e.squaredDistanceTo(mod.getPlayer())) > FIGHT_MAX_CHASE_DISTANCE);

        // 2. Absorb hostiles that joined the fight (集中击杀: since we're
        // already fighting here, finish the newcomers too instead of letting
        // them chip away at us while we path back to the user task).
        // (2026-09-19 20:10 造墙施工让位) 施工中且威胁全是烈焰人 → 既不吸收新
        // 烈焰人, 已提交批次若也全是烈焰人就立即交还控制 —— 刷怪笼持续出怪会
        // 让批次永远清不完, 造墙饿死 (195218 实锤)。
        boolean yieldBlazes = shouldYieldBlazesToEnclosureBuild(mod);
        if (yieldBlazes) {
            boolean onlyBlazes = _fightTargets.stream().allMatch(t -> t instanceof BlazeEntity);
            if (onlyBlazes) {
                Debug.logMessage("Combat finished: yielding blaze-only batch back to enclosure build.");
                exitFightState();
                _defenseState = DefenseState.EVALUATE;
                return 0;
            }
        }
        if (mod.getModSettings().shouldDealWithAnnoyingHostiles()) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                for (Entity hostile : mod.getEntityTracker().getHostiles()) {
                    if (_fightTargets.contains(hostile)) continue;
                    if (yieldBlazes && hostile instanceof BlazeEntity) continue;
                    // (2026-09-22 用户"未点火直接不管") 苦力怕不吸收进批次, 见 isUnfusingCreeper;
                    // 若已点火, 本步之前的 stateFight 前置 fusing 分支已接手 (不会走到这)。
                    if (hostile instanceof CreeperEntity) continue;
                    // (2026-09-21) 已开打的批次也不吸收传送门 6 格内的蠹虫。
                    if (hostile instanceof SilverfishEntity
                            && isNearEndPortalAnchor(mod, hostile.getBlockPos())) {
                        continue;
                    }
                    // (2026-09-19 用户) 小白触发距离 18 → 16 (刚好触发小白的距离), 见 SKELETON_KILL_TRIGGER_RANGE。
                    // (20261001) 触发半径统一走 annoyingKillTriggerRange (激怒猪灵 12 格)。
                    int annoyingRange = annoyingKillTriggerRange(hostile);
                    if (hostile.isInRange(mod.getPlayer(), annoyingRange)
                            && LookHelper.seesPlayer(hostile, mod.getPlayer(), annoyingRange)) {
                        _fightTargets.add(hostile);
                    }
                }
            }
        }

        // 3. Batch wiped (or everyone fled): fight over, resume the user task.
        if (_fightTargets.isEmpty()) {
            Debug.logMessage("Combat finished: batch cleared, resuming previous task.");
            exitFightState();
            _defenseState = DefenseState.EVALUATE;
            return 0;
        }

        // 4. Fighting too long without progress: stop tunneling, retreat.
        if (nowTick - _combatStartTick > COMBAT_TIMEOUT_TICKS) {
            Debug.logMessage("Combat decision: TIMEOUT after " + COMBAT_TIMEOUT_TICKS / 20 + "s, forcing retreat.");
            exitFightState();
            _runAwayTask = new ProgressiveRunAwayTask(DANGER_KEEP_DISTANCE, ESCAPE_STEP, true);
            setTask(_runAwayTask);
            _defenseState = DefenseState.RETREAT;
            return 80;
        }

        // 5. Re-evaluate with the (possibly grown) batch: if we can no longer
        // win, or we got low on health, bail out.
        // (2026-08-30 已开火骷髅不撤退, 用户) mustKill 加急战斗中被攻击时,
        // 血量低也【不撤退】—— 逃跑正对骷髅背身, 边跑边被射只会更早死,
        // "特别是血量低的时候, 需要击杀"。只由超时(第 4 步)/目标逃离收场。
        // (2026-09-11 空手一律逃不例外, 用户) 战斗批次实例能跨死亡/复活存活 —
        // 实锤 20260911-205655 场次: 21:10:07 开打 → 21:10:13 死亡复活空手
        // → 同一 KillEntitiesTask 继续追骷髅。每 tick 复查武器, 丢剑(死亡
        // 复活是主要来源)立即转逃; must-kill 也不例外 (入口闸门保证空手进
        // 不了 must-kill, 这里兜底复活后清空背包的场景)。
        // (2026-09-21 末影人战斗到底, 用户"他还是逃跑") shouldRetreatMelee 只管
        // 【进不进战斗】, 本步中盘复评没有末影人豁免 — 181847 实锤: 满血自信战斗
        // 1.5s 内被打到 30.7% → "health low" RUN (prio 80), 无 RETREAT-DEC 日志
        // (判决不在 shouldRetreatMelee)。末影人瞬移贴脸逃不掉 (0918 拍板),
        // 全末影人批次低血量/威胁公式一律不转逃, 只由超时/无武器/清场收场。
        if (!hasWeaponToFight(mod)) {
            Debug.logMessage("Combat decision: RUN (no weapon mid-fight, enemies=%d)", _fightTargets.size());
            exitFightState();
            _runAwayTask = new ProgressiveRunAwayTask(DANGER_KEEP_DISTANCE, ESCAPE_STEP, true);
            setTask(_runAwayTask);
            _defenseState = DefenseState.RETREAT;
            return 80;
        }
        boolean allEndermen = !_fightTargets.isEmpty()
                && _fightTargets.stream().allMatch(t -> t instanceof EndermanEntity);
        if (!_mustKillAttacker) {
            if (_confidentFight) {
                // (2026-08-23) 自信战斗中途不走威胁公式/护甲脆弱判定 (无甲时
                // 公式必判必输 → 刚开打就被踢去逃跑, 用户实测 "打 5 秒就跑")。
                // 唯一撤退条件: 血量跌破 40% (苦力怕/超时保护仍然生效)。
                // (2026-09-30 C 用户拍板) 掠夺者/卫道士死战批次低血量豁免:
                // 有食物才撤 (撤 → 拉开距离吃回血 → 再回来打, 恢复链 124638
                // 已验证: 逃跑结束后 Food 链 prio 85 接管); 没食物必须击杀
                // (跑不过, 撤只是拖长挨打)。末影人 0921 同口径先例。
                boolean anyRaidIllager = _fightTargets.stream()
                        .anyMatch(t -> t instanceof PillagerEntity || t instanceof VindicatorEntity);
                boolean raidNoFood = anyRaidIllager
                        && StorageHelper.calculateInventoryFoodScore(mod) <= 0;
                if (!allEndermen && !raidNoFood
                        && mod.getPlayer().getHealth() < mod.getPlayer().getMaxHealth() * 0.4f) {
                    Debug.logMessage("Combat decision: RUN mid-confident-fight (health low, enemies=%d)", _fightTargets.size());
                    exitFightState();
                    _runAwayTask = new ProgressiveRunAwayTask(DANGER_KEEP_DISTANCE, ESCAPE_STEP, true);
                    setTask(_runAwayTask);
                    _defenseState = DefenseState.RETREAT;
                    return 80;
                }
            } else {
                double totalThreat = 0;
                for (Entity hostile : _fightTargets) {
                    totalThreat += ThreatEvaluator.evaluate(mod, hostile);
                }
                double ourStrength = ThreatEvaluator.estimatePlayerStrength(mod);
                if (!allEndermen && (!ThreatEvaluator.canWin(totalThreat, ourStrength) || isInDanger(mod))) {
                    Debug.logMessage("Combat decision: RUN mid-fight (threat=%.1f, our=%.1f, enemies=%d)", totalThreat, ourStrength, _fightTargets.size());
                    exitFightState();
                    _runAwayTask = new ProgressiveRunAwayTask(DANGER_KEEP_DISTANCE, ESCAPE_STEP, true);
                    setTask(_runAwayTask);
                    _defenseState = DefenseState.RETREAT;
                    return 80;
                }
            }
        }

        // 6. Keep fighting. Reuse ONE kill task instance: its predicate holds a
        // live reference to _fightTargets, so mid-fight additions/removals are
        // seen without restarting the task (and its pathing).
        if (_fightTask == null) {
            _fightTask = new KillEntitiesTask(_fightTargets::contains, HOSTILE_ANNOYING_CLASSES);
        }
        _runAwayTask = null;
        setTask(_fightTask);
        // (2026-08-30) 加急击杀骷髅时维持高优先, 否则每 tick 被普通 65 拉回
        // 基础任务, "成功击杀前应一直是最高级"。
        return _mustKillAttacker ? ACTIVE_ATTACKER_PRIORITY : 65.0f;
    }

    private void exitFightState() {
        _inCombat = false;
        _combatStartTick = 0;
        _confidentFight = false;
        _mustKillAttacker = false;
        _fightTargets.clear();
        _fightTask = null;
    }

    /**
     * (2026-09-17 用户拍板"小白必须击杀, 但先解决贴脸的") 把
     * MUST_KILL_MELEE_KEEP_DISTANCE 内的近战怪加入击杀名单; 已在名单内则跳过
     * (幂等, 每 tick 调用不会刷屏/重复)。
     * 排除两类, 与 shouldRetreatMelee 的威胁分类同口径:
     *  (a) 苦力怕 — 走举盾接爆专门分支, 不是持续 DPS 来源;
     *  (b) 远程怪 (骷髅射手/女巫/烈焰人等) — 贴脸不是它们的主要伤害方式,
     *      且骷髅本身就是 must-kill 的目标, 排除后才不会自我抵消。
     * 名单由 KillEntitiesTask → DoToClosestEntityTask 按"最近优先"消费, 所以贴脸的
     * 必然先被打掉; 骷髅留在名单里, 清完近战后自动回头打它。
     */
    private void addCloseMeleeTargets(AltoClef mod) {
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            for (Entity h : mod.getEntityTracker().getHostiles()) {
                if (h == null || !h.isAlive()) continue;
                if (h instanceof CreeperEntity) continue;
                if (isRangedThreat(h)) continue;
                if (!h.isInRange(mod.getPlayer(), MUST_KILL_MELEE_KEEP_DISTANCE)) continue;
                if (_fightTargets.contains(h)) continue;
                _fightTargets.add(h);
                Debug.logMessage("[MUSTKILL-CLOSE] added close melee %s at %.1fm to kill list (before distant skeleton)",
                        h.getClass().getSimpleName(), h.distanceTo(mod.getPlayer()));
            }
        }
    }

    /**
     * (2026-08-30 没剑不硬杀) must-kill 触发前置: 打骷髅至少需要一把剑, 否则
     * 无望击杀只会站着/转圈被射死。按 isConfidentFight 同款剑判定。
     */
    private static boolean hasWeaponToFight(AltoClef mod) {
        // (2026-09-30 用户"斧子也是武器对吧") 斧子同阶伤害≥剑, 纳入近战武器判定
        // —— 旧口径只有剑族, 只有斧子时 hasWeaponToFight=false → 有武器也一律逃。
        return mod.getItemStorage().hasItem(Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
                Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD,
                Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
                Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);
    }

    /**
     * (2026-08-30) 当前最近的【已被拖入攻击、且在范围内】的骷髅射手。
     * (2026-09-24 根修, 事故 20260924-230344) 旧实现只认 "hostile+已感知",
     * 但骷髅对玩家恒 hostile, 感知又含【听觉】(vanilla 音效穿墙) —— 地下
     * 9 格、从未被看见也从未开火的骷髅走两步就触发 MUST-KILL (prio 95),
     * bot 直接钻洞猎杀 = "无缘无故锁敌看不见的敌人"。
     * (2026-09-30 主动索敌, 用户拍板) 1.18.2 客户端无攻击者包 ⇒ 箭伤永远归因
     * 不到骷髅 (0930 会话五连死实锤: mustKill=true 全场 0 次, 骷髅贴脸 0.3 格
     * 仍在 RETREAT) —— "被打实锤才锁定"在本版本恒死, 用户明确【不要】
     * "被打一下后才锁定"。改为主动索敌: 【亲眼可见】且已敌对、在
     * {@link #SKELETON_PROACTIVE_ENGAGE_RANGE} 内的骷髅即算 active shooter,
     * 不等挨打。亲眼可见门槛保住 09-24 反透视结论: 听见墙后怪照样不算。
     * 被打实锤 (HitRegistry, 60s 窗口) 仍保留 —— 有实锤时范围放宽到 24 格。
     */
    private Entity getActiveRangedAttacker(AltoClef mod) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        double RANGE = 24.0;
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            for (Entity e : mod.getEntityTracker().getHostiles()) {
                if (e == null || !e.isAlive()) continue;
                if (!(e instanceof AbstractSkeletonEntity)) continue;
                if (!EntityHelper.isAngryAtPlayer(mod, e)) continue;
                // (2026-09-30 主动索敌) 两条证据任一即入围:
                // ① 亲眼可见 + 在主动接敌半径内 (不等挨打, 用户拍板);
                // ② HitRegistry 实锤打到过 bot (60s 窗口, 范围 24 格)。
                // 都没有 (只是听见/记忆里的骷髅) 不进 must-kill。
                boolean hitConfirmed = HitRegistry.hasRecentlyDamagedPlayer(e);
                boolean visible = VisibilityHelper.isEntityVisible(mod, e)
                        && e.isInRange(mod.getPlayer(), SKELETON_PROACTIVE_ENGAGE_RANGE);
                if (!hitConfirmed && !visible) {
                    logUnprovenShooterDiag(mod, e);
                    continue;
                }
                double d = e.squaredDistanceTo(mod.getPlayer());
                double range = hitConfirmed ? RANGE : SKELETON_PROACTIVE_ENGAGE_RANGE;
                if (d <= range * range && d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
        }
        // [MOB-DIAG] #1 只读: 打印 active ranged attacker 判定结果 (0.5s 节流)。
        logRangedAttackerDiag(mod, best);
        return best;
    }

    // [MOB-DIAG] 有已感知的骷髅但缺"实际伤害过 bot"实锤 → 不算 active shooter。
    // 只读诊断 (2s 节流, 只落盘不进聊天), 用于实机验证 must-kill 根修。
    private static long _lastUnprovenShooterDiagMs = 0;

    private static void logUnprovenShooterDiag(AltoClef mod, Entity skeleton) {
        long now = System.currentTimeMillis();
        if (now - _lastUnprovenShooterDiagMs < 2000) return;
        _lastUnprovenShooterDiagMs = now;
        try {
            Debug.logToFile("altoclef_diag.log", String.format(
                    "[MOB-DIAG] skeleton perceived but never damaged us -> NOT must-kill pos=%s dist=%.1f visible=%s",
                    skeleton.getBlockPos().toShortString(),
                    Math.sqrt(skeleton.squaredDistanceTo(mod.getPlayer())),
                    VisibilityHelper.isEntityVisible(mod, skeleton)));
        } catch (Exception ignored) { /* 诊断日志绝不影响主逻辑 */ }
    }

    private float stateRetreat(AltoClef mod) {
        // (2026-09-02 苦力怕修复) 逃跑途中若点燃的苦力怕逼近(16格内)且副手有盾,
        // 立即转原地举盾接爆 —— 不再边逃边被炸 (用户规则: 有盾接爆 > 逃跑, 任意 fuse)。
        // 举盾启动后由 getPriorityInner 顶部每 tick 续 hold, 不会被下方放盾逻辑打断。
        CreeperEntity escapeCreeper = getClosestFusingCreeper(mod);
        if (escapeCreeper != null) {
            boolean closeEnough = escapeCreeper.squaredDistanceTo(mod.getPlayer())
                    <= CREEPER_SHIELD_BLOCK_RANGE * CREEPER_SHIELD_BLOCK_RANGE;
            if (closeEnough && canShieldNuke(mod)) {
                _doingFunkyStuff = true;
                if (doShieldNuke(mod, escapeCreeper)) {
                    _runAwayTask = null;
                    return 90;
                }
            }
        }
        // (2026-08-23) 进入逃跑状态时，如果之前在做盾牌核弹，必须释放盾牌
        // 否则举盾状态会一直持续，导致移动极慢（用户反馈）
        if (_shieldNukeStartTick > 0) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
            _shieldNukeStartTick = -1;
            _shieldNukeTarget = null;
        }
        // (2026-08-23 持盾格挡的盾也要放) 若之前在格挡弹射物 (doProjectileBlock
        // 举盾), 切到逃跑时同样要 release, 否则盾一直举着移动极慢 (用户:
        // "想逃跑时又不放下盾牌")。
        if (_projectileBlocking) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            _projectileBlocking = false;
        }
        
        // (2026-08-30 逃跑绝不举盾, 用户 "逃跑时必不能举盾") 逃跑唯一目标就是快跑
        // 脱离; 举盾会大幅减速 (使用盾牌移速骤降), 以前的"边跑边举盾打近身敌人"
        // 只会让 bot 背着盾慢慢挪。逃跑期间一律 release, 绝不举盾。若真需要反打
        // 会走 fight/盾牌核弹路径, 不该在逃跑里 hold。
        mod.getInputControls().release(Input.CLICK_RIGHT);
        
        if (_runAwayTask == null) {
            _defenseState = DefenseState.EVALUATE;
            return 0;
        }
        if (_runAwayTask.isFinished(mod)) {
            // Successfully retreated far enough.
            _runAwayTask = null;
            _retreatEscalation++;
            if (_retreatEscalation >= 3) {
                // We keep having to retreat: give up on this spot for a bit.
                String m = "[Diag-retreat] retreat FINISHED (escalation=" + _retreatEscalation + ") -> COOLDOWN, releasing bot";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
                enterCooldown(mod);
            } else {
                String m = "[Diag-retreat] retreat FINISHED (escalation=" + _retreatEscalation + ") -> EVALUATE, user task should resume";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
                _defenseState = DefenseState.EVALUATE;
            }
            return 0;
        }
        // While escaping, keep an eye out for a choke point we can wall off:
        // if one shows up, block it immediately instead of just running.
        handleReachabilityThreats(mod, WorldHelper.getTicks());
        if (_defenseState == DefenseState.BLOCK_PATH) {
            return 90;
        }
        // Retreat is hopeless (stuck / no path): escalate to shelter / dig / long retreat.
        if (_runAwayTask.getFailCount() >= RETREAT_FAIL_LIMIT) {
            Debug.logMessage("Retreat failed repeatedly, escalating defense.");
            _runAwayTask = null;
            _defenseState = escalate(mod);
            return priorityForState();
        }
        // (2026-09-03 逃跑也反击, 用户"逃跑时也要继续击打身边的怪, 不能只跑") 逃跑
        // 不等于放弃抵抗: 维持 KillAura 贴身近战(左键), 边跑边打咬上来的近战怪。
        // 不冲突"逃跑绝不举盾"(那是右键), 也不与盾牌核弹路径冲突(举盾时上面已
        // return 90, 不会走到这; doForceField 内部对 fuse 苦力怕也有盾牌保护)。
        doForceField(mod);
        // [MOB-DIAG] #2 只读: 逃跑实测 (0.5s 节流)。
        logEscapeDiag(mod);
        setTask(_runAwayTask);
        return _cachedLastPriority;
    }

    private float stateShelter(AltoClef mod) {
        // In the shelter we allow eating / healing.
        _doingFunkyStuff = false;
        if (_shelterTask == null) {
            _shelterTask = new ShelterAndRecoverTask();
            setTask(_shelterTask);
        }
        // Only check completion on the running instance (it needs at least one
        // tick to start and plan its walls).
        if (_shelterTask.isFinished(mod)) {
            _runAwayTask = null;
            _shelterTask = null;
            _retreatEscalation++;
            if (_retreatEscalation >= 2) {
                // Shelter didn't resolve things: dig/long-retreat away instead.
                _defenseState = escalate(mod);
            } else {
                _defenseState = DefenseState.EVALUATE;
            }
        }
        return 90;
    }

    private float stateDigEscape(AltoClef mod) {
        if (_digTask == null) {
            _digTask = new DigOutEscapeTask(awayFromThreatsDirection(mod));
            setTask(_digTask);
        }
        if (_digTask.isFinished(mod)) {
            _runAwayTask = null;
            _digTask = null;
            enterCooldown(mod);
        }
        return 90;
    }

    /**
     * (2026-09-07 塔式逃生) 塔逃状态: TowerEscapeTask 自带四阶段状态机
     * (找空旷→搭高→进食→站桩打怪) 与超时兜底, 这里只管结果分流:
     *   SUCCESS              → 冷却, 交还用户任务 (16 格内怪已清空)。
     *   ABORT_THREAT 且塔已完成 → SHELTER: ShelterAndRecoverTask 会在塔顶
     *                          四面+顶盖封箱, 天然掩体 (首版封顶方案, 用户拍板)。
     *   其他 (ABORT/TIMEOUT)  → LONG_RETREAT (与旧链失败降级一致)。
     */
    private float stateTowerEscape(AltoClef mod) {
        // 运行中开关被关掉: 立刻退回旧链。
        if (!mod.getModSettings().isTowerEscapeEnabled()) {
            _towerTask = null;
            _defenseState = DefenseState.LONG_RETREAT;
            return priorityForState();
        }
        if (_towerTask == null) {
            _towerTask = new TowerEscapeTask();
            setTask(_towerTask);
        }
        if (_towerTask.isFinished(mod)) {
            TowerEscapeTask.Result result = _towerTask.getResult();
            boolean complete = _towerTask.isTowerComplete(mod);
            _towerTask = null;
            _runAwayTask = null;
            String m = "[TowerEscape] finished result=" + result + " towerComplete=" + complete;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            if (result == TowerEscapeTask.Result.SUCCESS) {
                enterCooldown(mod);
                return 0;
            }
            if (complete) {
                // 在塔顶遭遇远程怪/苦力怕: 塔顶封箱变掩体 (复用现有 SHELTER)。
                _defenseState = DefenseState.SHELTER;
                return priorityForState();
            }
            _defenseState = DefenseState.LONG_RETREAT;
            return priorityForState();
        }
        return 90;
    }

    private float stateLongRetreat(AltoClef mod) {
        if (_runAwayTask == null) {
            _runAwayTask = new ProgressiveRunAwayTask(LONG_RETREAT_DISTANCE, LONG_RETREAT_STEP, true);
            setTask(_runAwayTask);
        }
        // Same as RETREAT: while escaping long-distance, still check for
        // choke points we can wall off immediately.
        handleReachabilityThreats(mod, WorldHelper.getTicks());
        if (_defenseState == DefenseState.BLOCK_PATH) {
            return 90;
        }
        if (_runAwayTask.isFinished(mod)) {
            _runAwayTask = null;
            enterCooldown(mod);
            return 0;
        }
        // Long retreat is already the highest escalation. If it's still stuck,
        // give up on fighting this area entirely and cool down.
        if (_runAwayTask.getFailCount() >= RETREAT_FAIL_LIMIT) {
            Debug.logMessage("Long retreat stuck repeatedly, entering cooldown.");
            _runAwayTask = null;
            enterCooldown(mod);
            return 0;
        }
        // [MOB-DIAG] #2 只读: 长距离逃跑实测 (0.5s 节流)。
        logEscapeDiag(mod);
        setTask(_runAwayTask);
        return 90;
    }

    private float stateBlockPath(AltoClef mod) {
        if (_blockThreat == null) {
            _blockTask = null;
            _defenseState = (_runAwayTask != null) ? DefenseState.RETREAT : DefenseState.EVALUATE;
            return 0;
        }
        if (_blockTask == null) {
            _blockTask = new BlockOffPathTask(_blockThreat);
            setTask(_blockTask);
        }
        if (_blockTask.isFinished(mod)) {
            _blockTask = null;
            _blockThreat = null;
            _blockPathCooldownTick = WorldHelper.getTicks() + BLOCK_PATH_COOLDOWN_TICKS;
            // Resume the interrupted escape with a fresh run task (the old one
            // was stopped, so it can't be reused to judge "escape finished").
            // Restore the EXACT escape state + parameters we saved before the
            // interruption, so a long retreat (100) doesn't silently become a
            // short escape (30) and includeSkeletons stays consistent.
            if (_escapeStateBeforeBlock == DefenseState.LONG_RETREAT
                    || _escapeStateBeforeBlock == DefenseState.RETREAT) {
                _runAwayTask = new ProgressiveRunAwayTask(
                        _savedEscapeMaxDistance,
                        _savedEscapeStep,
                        _savedEscapeIncludeSkeletons);
                setTask(_runAwayTask);
                _defenseState = _escapeStateBeforeBlock;
            } else {
                _runAwayTask = null;
                _defenseState = DefenseState.EVALUATE;
            }
        }
        return 90;
    }

    // ------------------------------------------------------------------
    // Helpers for the state machine
    // ------------------------------------------------------------------

    /**
     * Pick the next, more aggressive defense after a failed attempt.
     * Escalation: shelter -> (underground: dig away / surface: long retreat) -> long retreat.
     */
    private DefenseState escalate(AltoClef mod) {
        _retreatEscalation++;
        if (_retreatEscalation >= 3) return DefenseState.LONG_RETREAT;
        // (2026-09-07 塔式逃生, 用户设计) 逃跑失败一次就优先试塔逃: 纯近战
        // 围殴时"挖通道"来不及 (导航会钻墙), "搭高 3 格悬空"是确定性安全位。
        // 条件不满足时行为与旧链完全一致。 LONG_RETREAT 之前的两个层级都可
        // 以进 (escalation 1 与 2)。
        if (canTryTowerEscape(mod)) {
            String m = "[TowerEscape] escalate -> TOWER_ESCAPE (escalation=" + _retreatEscalation + ")";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return DefenseState.TOWER_ESCAPE;
        }
        if (_retreatEscalation >= 2 && isUnderground(mod)) return DefenseState.DIG_ESCAPE;
        return DefenseState.SHELTER;
    }

    /**
     * (2026-09-07 塔式逃生) 塔逃进入条件: 开关打开 + 追兵为纯近战
     * (无远程怪/无苦力怕, 用户前提) + 身上有 ≥3 个可放置方块。
     * 不检查"当前是否空旷" —— 空旷由 TowerEscapeTask 的 FIND_OPEN 阶段
     * 边跑边找 (用户要求: 快速策略, 小范围搜索+先跑, 不站着等导航)。
     */
    private boolean canTryTowerEscape(AltoClef mod) {
        if (!mod.getModSettings().isTowerEscapeEnabled()) return false;
        for (Entity h : getNearbyHostiles(mod)) {
            if (h == null || !h.isAlive()) continue;
            if (h instanceof CreeperEntity || isRangedThreat(h)) return false;
        }
        return mod.getItemStorage().getItemCount(TowerEscapeTask.getTowerItems()) >= 3;
    }

    private float priorityForState() {
        return 90;
    }

    private void enterCooldown(AltoClef mod) {
        _cooldownEndTick = WorldHelper.getTicks() + COOLDOWN_TICKS;
        _defenseState = DefenseState.COOLDOWN;
        exitFightState();
        _runAwayTask = null;
    }

    /**
     * "我们在地下吗": 走全项目统一判据 {@link SurfaceHelper}。
     * <p>
     * (2026-09-17 判据统一) 旧实现是"头顶 1..12 格逐格扫实心"，与全项目其他
     * 4 份口径互不相同: 站在树冠下/1 格屋檐下 → 扫到实心 → 误判"地下" →
     * 高逃跑升级时错误进 DIG_ESCAPE (往下挖着逃)，而人实际在地表。
     * 统一后语义 = "5 点采样里 ≥3 点未见天"。
     * <p>
     * 用【无迟滞】的 {@link SurfaceHelper#isBelowSurface}(而非 Debounced) 是
     * 有意为之: 本方法只在 {@code _retreatEscalation >= 2} 的每 tick 状态选择
     * 里用一次, 状态机本身每 tick 重算 —— 需要的是"当下这一刻", 不是"上一个
     * 稳定结论"。5 点多数投票已抹平单列抖动, 不需要再叠时间迟滞。
     */
    private boolean isUnderground(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        return SurfaceHelper.isBelowSurface(mod);
    }

    /**
     * Direction pointing away from all perceived hostiles (for digging out).
     */
    private Vec3d awayFromThreatsDirection(AltoClef mod) {
        Vec3d playerPos = mod.getPlayer().getPos();
        Vec3d sum = Vec3d.ZERO;
        for (Entity e : mod.getEntityTracker().getHostiles()) {
            if (e == null || !e.isAlive()) continue;
            Vec3d d = e.getPos().subtract(playerPos);
            if (d.lengthSquared() < 1e-4) continue;
            sum = sum.add(d.normalize());
        }
        if (sum.lengthSquared() < 1e-4) return new Vec3d(1, 0, 0);
        return sum.normalize().multiply(-1);
    }

    /**
     * Reachability awareness: while not fighting (including while we're running
     * away), check whether a high-threat mob can still walk to us within 30
     * blocks and, if so, wall off its path. Throttled to every 2 seconds with a
     * long re-block cooldown.
     */
    private void handleReachabilityThreats(AltoClef mod, long nowTick) {
        if (_inCombat) return;
        if (nowTick - _lastReachabilityCheckTick < REACHABILITY_CHECK_INTERVAL) return;
        if (nowTick < _blockPathCooldownTick) return;
        _lastReachabilityCheckTick = nowTick;

        Entity best = null;
        double bestScore = REACHABILITY_MIN_THREAT;
        Vec3d playerPos = mod.getPlayer().getPos();
        for (Entity e : mod.getEntityTracker().getHostiles()) {
            if (e == null || !e.isAlive()) continue;
            double threat = ThreatEvaluator.evaluate(mod, e);
            if (threat < bestScore) continue;
            double dist = Math.sqrt(e.squaredDistanceTo(mod.getPlayer()));
            if (dist < REACHABILITY_MIN_DISTANCE) continue;
            if (dist > 64) continue;
            if (PathReachabilityHelper.isReachableWithin(mod.getWorld(), playerPos, e.getPos(), 30)) {
                bestScore = threat;
                best = e;
            }
        }
        if (best != null) {
            Debug.logMessage("Reachable high-threat mob detected, blocking its path.");
            // Snapshot the current escape context before we interrupt it so
            // we can resume the exact same escape (distance / step / state)
            // once the wall is up.
            if ((_defenseState == DefenseState.RETREAT || _defenseState == DefenseState.LONG_RETREAT)
                    && _runAwayTask instanceof ProgressiveRunAwayTask progressive) {
                _escapeStateBeforeBlock = _defenseState;
                _savedEscapeMaxDistance = progressive.getMaxDistance();
                _savedEscapeStep = progressive.getStep();
                _savedEscapeIncludeSkeletons = progressive.isIncludeSkeletons();
            } else {
                _escapeStateBeforeBlock = DefenseState.EVALUATE;
            }
            _blockThreat = best;
            _blockTask = null;
            _defenseState = DefenseState.BLOCK_PATH;
        }
    }

    private BlockPos isInsideFireAndOnFire(AltoClef mod) {
        boolean onFire = mod.getPlayer().isOnFire();
        if (!onFire) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        BlockPos[] toCheck = new BlockPos[]{
                p,
                p.add(1, 0, 0),
                p.add(1, 0, 1),
                p.add(1, 0, -1),
                p.add(0, 0, 1),
                p.add(0, 0, -1),
                p.add(-1, 0, 1),
                p.add(-1, 0, -1)
        };
        for (BlockPos check : toCheck) {
            Block b = mod.getWorld().getBlockState(check).getBlock();
            if (b instanceof AbstractFireBlock) {
                return check;
            }
        }
        return null;
    }

    private void putOutFire(AltoClef mod, BlockPos pos) {
        IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
        Optional<Rotation> reachable = RotationUtils.reachableOffset(ctx.player(), pos, new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), ctx.playerController().getBlockReachDistance(), false);
        if (reachable.isPresent()) {
            mod.getClientBaritone().getLookBehavior().updateTarget(reachable.get(), true);
            if (ctx.isLookingAt(pos)) {
                _wasPuttingOutFire = true;
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
        }
    }

    private boolean prioritizeEating(AltoClef mod) {
        return mod.getFoodChain().needsToEatCritical(mod);
    }

    private void doForceField(AltoClef mod) {

        _killAura.tickStart(mod);

        // Hit all hostiles close to us.
        List<Entity> entities = mod.getEntityTracker().getCloseEntities();
        try {
            for (Entity entity : entities) {
                boolean shouldForce = false;
                if (mod.getBehaviour().shouldExcludeFromForcefield(entity)) continue;
                if (entity instanceof Monster) {
                    if (EntityHelper.isGenerallyHostileToPlayer(mod, entity)) {
                        if (LookHelper.seesPlayer(entity, mod.getPlayer(), 10)) {
                            shouldForce = true;
                        }
                    }
                } else if (entity instanceof FireballEntity) {
                    // Ghast ball
                    shouldForce = true;
                } else if (entity instanceof PlayerEntity player && mod.getBehaviour().shouldForceFieldPlayers()) {
                    if (!player.equals(mod.getPlayer())) {
                        String name = player.getName().getString();
                        if (!mod.getButler().isUserAuthorized(name)) {
                            shouldForce = true;
                        }
                    }
                }
                if (shouldForce) {
                    applyForceField(mod, entity);
                }
            }
        } catch (Exception e) {
            Debug.logWarning("Weird exception caught and ignored while doing force field.");
            e.printStackTrace();
        }

        // (2026-09-25 火弹宽半径接管, 20260925 岩浆湖搭路被火弹炸落事故)
        // getCloseEntities 只收 reach (3.2 格) 内实体, 火弹速度 ~1.6 格/tick,
        // 穿越该圈只剩 ~2 tick, "看到再打"必死。改为从实体登记表 (感知侧已在
        // 16 格内豁免 FOV, "听到就回头", 用户拍板) 取火弹提前喂给 KillAura。
        // 只接管冲着 bot 飞来的火弹 (速度朝向点积 ≥0.6), 飞偏的不理会 ——
        // 减少搭路/寻路时无谓的回头干扰 (0919 搭路让位规矩只禁举盾/躲避,
        // KillAura 挥拳在搭路期间本来就是放行的, 不违背该规矩)。
        for (FireballEntity fireball : mod.getEntityTracker().getTrackedEntities(FireballEntity.class)) {
            if (fireball.squaredDistanceTo(mod.getPlayer())
                    > EntityTracker.FIREBALL_AWARE_RANGE * EntityTracker.FIREBALL_AWARE_RANGE) continue;
            Vec3d vel = fireball.getVelocity();
            if (vel.lengthSquared() < 1e-4) continue;
            Vec3d toBot = mod.getPlayer().getPos().subtract(fireball.getPos()).normalize();
            if (vel.normalize().dotProduct(toBot) < 0.6) continue;
            applyForceField(mod, fireball);
        }

        // (2026-09-01 用户"苦力怕近身被迫左键击打, 与举盾矛盾导致举盾失效")
        // 举盾接爆(fuse 苦力怕在接爆范围内且有盾)时, 禁止 KillAura 左键攻击 —
        // 否则盾举着的同时 killaura 对苦力怕挥砍, 盾牌动作被破坏, 爆炸格挡失败。
        // 举盾只持续到苦力怕爆炸/远离(fuse<ABORT 自动放盾), 期间放弃近战合理。
        CreeperEntity fusingCreeper = getClosestFusingCreeper(mod);
        boolean shieldBlockingCreeper = ThreatEvaluator.hasShield(mod)
                && fusingCreeper != null
                && fusingCreeper.squaredDistanceTo(mod.getPlayer())
                <= CREEPER_SHIELD_BLOCK_RANGE * CREEPER_SHIELD_BLOCK_RANGE;
        if (!shieldBlockingCreeper) {
            _killAura.tickEnd(mod);
        }
    }

    private void applyForceField(AltoClef mod, Entity entity) {
        if (_targetEntity != null && _targetEntity.equals(entity)) return;
        _killAura.applyAura(mod, entity);
    }

    // ==================================================================
    // 盾牌核弹 (Creeper Shield Nuke) — 将苦力怕自爆的最高威胁防御
    // 策略，由"永远逃"改为"条件允许时举盾硬接"，避免反复炸飞+跑
    // 到 45 格外又折返的巨量横跳（用户要求，2026-08-23）。
    // ==================================================================

    /** 判定是否满足盾牌核弹前提：有盾 + ≤3 只普通怪。 (2026-08-30 去血量门槛:
     * 用户"有盾时最优是举盾等苦力怕爆炸"。MC 举盾正面朝苦力怕几乎完全格挡爆炸
     * (只吃击退), 血量低不低不该阻止举盾接爆 — 旧的血量≥40% 门槛让残血有盾的
     * bot 反而去逃 10 格送死。) */
    private boolean canShieldNuke(AltoClef mod) {
        if (!ThreatEvaluator.hasShield(mod)) return false;
        // 怪物计数口径与自信战斗一致 (16 格内存活怪 ≤3 且不含 boss)
        List<Entity> near = getNearbyHostiles(mod);
        if (near.size() > SHIELD_NUKE_MAX_HOSTILES) return false;
        for (Entity e : near) if (isBossLike(e)) return false;
        return true;
    }

    /**
     * 执行盾牌核弹：把盾牌从背包装进副手（若需要）、面朝苦力怕、持续举盾、
     * 禁止移动。优先级已经在 stateEvaluate 由 blowingUp 分支保证，此处
     * 只做"原地举盾"动作，不派发 Task（避免链切任务释放控制）。
     *
     * 返回 true 表示正在进行盾牌核弹（调用方直接 return priority），
     * 返回 false 表示已完成（苦力怕死了 / fuse 回落 / 目标丢失），调用方
     * 应重置状态并走原有逃跑/战斗逻辑。
     */
    private boolean doShieldNuke(AltoClef mod, CreeperEntity creeper) {
        long nowTick = WorldHelper.getTicks();
        float fuse = creeper.getClientFuseTime(1);
        boolean targetGone = creeper == null || !creeper.isAlive();
        boolean fuseDropped = !targetGone && fuse < SHIELD_NUKE_ABORT_FUSE && _shieldNukeStartTick > 0;

        if (targetGone || fuseDropped) {
            // 结束：释放盾 + 放开移动锁 + 清状态
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
            _shieldNukeStartTick = -1;
            _shieldNukeTarget = null;
            return false;
        }

        // ① 保证盾牌在副手 (不在的话用 SWAP 装过去)
        // (2026-09-30 光标恢复, 180851 双杀实锤) 进食装备链 startEat→parkShieldOffHands
        // 会把副手盾【捡到光标】(CraftClick slot=45 PICKUP) 腾出右键 —— 光标不在
        // getSlotsWithItemScreen 结果里, 本分支查不到盾就不装 → 空手举盾接爆
        // (18:10:11 一炸 0.07HP、18:10:20 二炸死亡)。光标持盾时直接 PICKUP 进副手。
        if (mod.getPlayer().getOffHandStack().getItem() != Items.SHIELD) {
            List<adris.altoclef.util.slots.Slot> shieldSlots = mod.getItemStorage().getSlotsWithItemScreen(Items.SHIELD);
            if (!shieldSlots.isEmpty()) {
                mod.getSlotHandler().clickSlot(adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT,
                        shieldSlots.get(0).getInventorySlot(),
                        net.minecraft.screen.slot.SlotActionType.SWAP);
            } else if (StorageHelper.getItemStackInCursorSlot().getItem() == Items.SHIELD) {
                mod.getSlotHandler().clickSlot(adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT, 0,
                        net.minecraft.screen.slot.SlotActionType.PICKUP);
            }
        }

        // ② 面朝苦力怕 (脚位置即可，爆炸来自脚下)
        // (2026-09-07 防御朝向优先) 走准心仲裁的防御槽, tick 尾统一 lookAt 且
        // 压过攻击瞄准 —— 旧直写会被 applyCombatAim 覆盖, 盾面朝错方向白举。
        // (2026-09-30 举盾误开容器, 用户"举盾时意外点到工作台") 举盾=按住右键,
        // 准心落在工作台/熔炉等可交互方块上会打开容器界面而不是举盾。检测到准心
        // 方块可交互时防御瞄准改指脚前地面 (低头落空), 让右键不落在容器上;
        // 苦力怕在数格外、低头 ~50° 仍在盾牌格挡锥内, 不影响接爆。
        if (LookHelper.isFacingInteractable(mod)) {
            Vec3d playerPos = mod.getPlayer().getPos();
            Vec3d flat = Vec3d.fromPolar(0, mod.getPlayer().getYaw()).multiply(2.0);
            mod.getControllerExtras().requestDefenseAim(
                    new Vec3d(playerPos.x + flat.x, playerPos.y - 1.2, playerPos.z + flat.z));
        } else {
            mod.getControllerExtras().requestDefenseAim(creeper.getPos());
        }

        // ③ 持续举盾（hold 保证每 tick 不下压）
        // (避免每 tick 刷屏: 只在首次启动和爆炸瞬间各打一条日志)
        if (fuse >= 0.9f && _shieldNukeStartTick >= 0 && nowTick - _shieldNukeStartTick < 2) {
            Debug.logMessage("Shield Nuke: about to absorb explosion (fuse=%.2f)", fuse);
        }
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        }
        // ④ 锁死所有平移输入，防止为了转向挪步导致盾面角度不对
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);

        if (_shieldNukeStartTick < 0) {
            _shieldNukeStartTick = nowTick;
            _shieldNukeTarget = creeper;
            Debug.logMessage("Shield Nuke STARTED: creeper fuse=%.2f, health=%.0f/%.0f, shield=%s",
                    fuse, mod.getPlayer().getHealth(), mod.getPlayer().getMaxHealth(),
                    mod.getPlayer().getOffHandStack().getItem() == Items.SHIELD ? "OFFHAND" : "INVENTORY");
        }
        return true;
    }

    // ==================================================================
    // 持盾格挡弹射物 (Projectile Shield Block, 2026-08-23) — 替换旧的
    // DodgeProjectilesTask (躲火球)。用户设计: 有盾时面朝弹射物举盾格挡,
    // 原地不躲, 打烈焰人/骷髅时不再"打一下躲一下"; 弹射物消失后放盾。
    // ==================================================================

    /**
     * 持盾格挡弹射物: 把盾从背包换到副手 (若需要)、面朝最近的弹射物、
     * 持续举盾、取消寻路 (原地不动)。返回 true = 正在格挡 (调用方 return 65);
     * 返回 false = 没盾或没有弹射物 (调用方走躲避/正常逻辑, 且会放盾)。
     */
    private boolean doProjectileBlock(AltoClef mod) {
        // 没盾 (副手/背包都没有) 就不能格挡, 交给 DodgeProjectilesTask 躲
        if (!ThreatEvaluator.hasShield(mod)) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            return false;
        }
        // 副手没装盾先换过来 (SWAP 一次)
        // (2026-09-30 光标恢复) 同 doShieldNuke ①: 进食链把盾停在光标时这里要能捡回。
        if (mod.getPlayer().getOffHandStack().getItem() != Items.SHIELD) {
            List<adris.altoclef.util.slots.Slot> shieldSlots = mod.getItemStorage().getSlotsWithItemScreen(Items.SHIELD);
            if (!shieldSlots.isEmpty()) {
                mod.getSlotHandler().clickSlot(adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT,
                        shieldSlots.get(0).getInventorySlot(),
                        net.minecraft.screen.slot.SlotActionType.SWAP);
            } else if (StorageHelper.getItemStackInCursorSlot().getItem() == Items.SHIELD) {
                mod.getSlotHandler().clickSlot(adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT, 0,
                        net.minecraft.screen.slot.SlotActionType.PICKUP);
            }
        }
        CachedProjectile nearest = getNearestProjectile(mod);
        if (nearest == null) {
            // 弹射物没了: 放盾
            mod.getInputControls().release(Input.CLICK_RIGHT);
            return false;
        }
        // 面朝弹射物 + 举盾 + 取消寻路 (原地格挡, 不躲)
        // (2026-09-07 防御朝向优先) 同盾牌核弹, 走防御槽不被攻击瞄准覆盖。
        // (2026-09-30 举盾误开容器) 同 doShieldNuke ②: 准心在可交互方块上改低头。
        if (LookHelper.isFacingInteractable(mod)) {
            Vec3d playerPos = mod.getPlayer().getPos();
            Vec3d flat = Vec3d.fromPolar(0, mod.getPlayer().getYaw()).multiply(2.0);
            mod.getControllerExtras().requestDefenseAim(
                    new Vec3d(playerPos.x + flat.x, playerPos.y - 1.2, playerPos.z + flat.z));
        } else {
            mod.getControllerExtras().requestDefenseAim(nearest.position);
        }
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        if (BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
            // (2026-09-14 搭路中断取证, 用户) 用户观察到"空中搭路没搭好掉进岩浆",
            // 怀疑格挡逻辑抢了 baritone, 但现象未经确证 → 只加零行为诊断: 每次真要
            // cancel 寻路时记录当时的位置/速度/脚下方块/是否悬空/是否在搭建 + 弹射物
            // 距离。下个会话带 [ShieldVsPath] 复盘即可判定格挡是不是元凶
            // (若没出现该行, 则去查 JumpStuckResolver / [FakePath] / 上层拆任务)。
            logPathCancelForShieldBlock(mod, nearest);
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        }
        return true;
    }

    /**
     * (2026-09-14) 格挡抢占 baritone 寻路时的取证日志 —— 见调用点注释。
     * 诊断不参与决策, 任何异常都吞掉, 绝不影响格挡本身。
     */
    private void logPathCancelForShieldBlock(AltoClef mod, CachedProjectile proj) {
        try {
            Vec3d v = mod.getPlayer().getVelocity();
            BlockPos below = mod.getPlayer().getBlockPos().down();
            boolean builder = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().isActive();
            double projDist = proj == null || proj.position == null
                    ? -1 : Math.sqrt(proj.position.squaredDistanceTo(mod.getPlayer().getPos()));
            Debug.logToFile("altoclef_diag.log", "[ShieldVsPath]\tCANCEL baritone pathing for shield block"
                    + " pos=" + mod.getPlayer().getBlockPos().toShortString()
                    + " y=" + String.format("%.2f", mod.getPlayer().getY())
                    + " vel=" + String.format("%.2f/%.2f/%.2f", v.x, v.y, v.z)
                    + " onGround=" + mod.getPlayer().isOnGround()
                    + " below=" + mod.getWorld().getBlockState(below).getBlock().getTranslationKey()
                    + " builder=" + builder
                    + " projDist=" + String.format("%.1f", projDist));
        } catch (Throwable ignored) {
        }
    }

    // (2026-09-19 搭路禁举盾) 抑制日志的节流戳 (最多 1 秒一条)。
    private long _lastShieldSuppressLogMs = 0;

    /**
     * (2026-09-19 搭路禁举盾) 因 baritone 正在搭方块而抑制弹射物防御时的取证日志。
     * 只诊断不参与决策; 限流 1s 一条, 防止刷屏。
     */
    private void logShieldSuppressedForBuilding(AltoClef mod) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - _lastShieldSuppressLogMs < 1000) return;
        _lastShieldSuppressLogMs = nowMs;
        try {
            Debug.logToFile("altoclef_diag.log", "[ShieldVsPath]\tSUPPRESS projectile block/dodge (baritone building)"
                    + " pos=" + mod.getPlayer().getBlockPos().toShortString()
                    + " y=" + String.format("%.2f", mod.getPlayer().getY())
                    + " onGround=" + mod.getPlayer().isOnGround());
        } catch (Throwable ignored) {
        }
    }

    /** 最近的弹射物 (箭/烈焰人火球等, 忽略恶魂大球和龙息)。 */
    private CachedProjectile getNearestProjectile(AltoClef mod) {
        CachedProjectile best = null;
        double bestDist = Double.MAX_VALUE;
        try {
            for (CachedProjectile p : mod.getEntityTracker().getProjectiles()) {
                if (p == null || p.position == null) continue;
                if (p.projectileType == FireballEntity.class || p.projectileType == DragonFireballEntity.class) continue;
                double d = p.position.squaredDistanceTo(mod.getPlayer().getPos());
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
        } catch (Exception e) {
            Debug.logWarning("Weird exception while scanning projectiles for blocking.");
        }
        return best;
    }

    /**
     * (2026-09-25 191451 派发提前) 最近的可诱爆苦力怕: 未点燃也算, 半径
     * {@link #BAIT_DISPATCH_RANGE_SQ} (8 格) 内取最近。与 getClosestFusingCreeper
     * 的区别: 那个只看点燃中的 —— 派发口径扩宽后, 苦力怕逼近阶段就接管控距,
     * 不等它贴脸点燃才反应 (1.5s 引信从 2.9 格往外撤太贴边)。
     */
    private CreeperEntity getNearestBaitableCreeper(AltoClef mod) {
        CreeperEntity target = null;
        double best = Double.MAX_VALUE;
        try {
            for (CreeperEntity creeper : mod.getEntityTracker().getTrackedEntities(CreeperEntity.class)) {
                if (creeper == null || !creeper.isAlive()) continue;
                // (2026-09-25 224856 用户"这是透视") 与其他击杀派发同口径的证据门:
                // 墙后实体同样出现在客户端实体登记表 (服务器发包范围内), 不设
                // 可见性门就是透视派发 (熔炉守卫 833f251e 同款 VisibilityHelper)。
                if (!VisibilityHelper.isEntityVisible(mod, creeper)) continue;
                double d = creeper.squaredDistanceTo(mod.getPlayer());
                if (d <= CreeperBaitSignal.DISPATCH_RANGE_SQ && d < best) {
                    best = d;
                    target = creeper;
                }
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            // 与 getClosestFusingCreeper 同口径: 偶发并发异常静默跳过本拍
            return target;
        }
        return target;
    }

    /**
     * (2026-09-25 224856 持有判据单一事实源) 按 CreeperBaitSignal 目标 UUID 从
     * 实体登记表解析当前诱爆目标。原先只认链级 _baitTask 字段 —— 批次内
     * AbstractKillEntityTask 重定向创建的诱爆实例不经过该字段 → 持有判据失效
     * → 批次每 tick 重派 → 重定向再新建 (22:50 一分钟 [BAIT] start ×17 实锤)。
     */
    private CreeperEntity getBaitSignalTarget(AltoClef mod) {
        UUID id = CreeperBaitSignal.getTargetId();
        if (id == null) return null;
        try {
            for (CreeperEntity creeper : mod.getEntityTracker().getTrackedEntities(CreeperEntity.class)) {
                if (creeper != null && id.equals(creeper.getUuid())) return creeper;
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            return null;
        }
        return null;
    }

    /**
     * (2026-09-25 191451 持有口径扩宽, 224856 改信号源) 诱爆信号目标是否仍在
     * 持有半径内: 目标存活且距 bot ≤10 格。覆盖"KITE 撤离成功→引信熄灭"窗口
     * —— 原持有判据锚"正在点燃", 引信一熄 getClosestFusingCreeper 即返回 null
     * → 诱爆被战斗批次顶掉 → bot 走回苦力怕 → 再点燃 → 再逃, 死循环 (19:20:09
     * RETREAT(70) 逃了 49s 实锤)。目标跑出持有半径 → 释放控制权, 任务自身有
     * 24 格跑丢+25s 超时兜底。
     */
    private boolean isBaitSignalTargetInHoldRange(AltoClef mod) {
        CreeperEntity target = getBaitSignalTarget(mod);
        return target != null && target.isAlive()
                && target.squaredDistanceTo(mod.getPlayer()) <= CreeperBaitSignal.HOLD_RANGE_SQ;
    }

    private CreeperEntity getClosestFusingCreeper(AltoClef mod) {
        double worstSafety = Float.POSITIVE_INFINITY;
        CreeperEntity target = null;
        try {
            List<CreeperEntity> creepers = mod.getEntityTracker().getTrackedEntities(CreeperEntity.class);
            for (CreeperEntity creeper : creepers) {

                if (creeper == null) continue;
                if (creeper.getClientFuseTime(1) < 0.001) continue;

                // We want to pick the closest creeper, but FIRST pick creepers about to blow
                // At max fuse, the cost goes to basically zero.
                double safety = getCreeperSafety(mod.getPlayer().getPos(), creeper);
                // (2026-09-01 bugfix) 原代码忘了更新 worstSafety, 条件恒真 → 永远选
                // 列表里【最后一个】fuse 苦力怕, 而不是最近/最危险的 — 有多个苦力怕时
                // 会盯错目标(甚至选到远处不构成威胁的)导致近处引爆的反而被忽略。
                if (safety < worstSafety) {
                    worstSafety = safety;
                    target = creeper;
                }
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            // IDK why but these exceptions happen sometimes. It's extremely bizarre and I have no idea why.
            Debug.logWarning("Weird Exception caught and ignored while scanning for creepers: " + e.getMessage());
            return target;
        }
        return target;
    }

    private boolean isProjectileClose(AltoClef mod) {
        List<CachedProjectile> projectiles = mod.getEntityTracker().getProjectiles();

        try {
            for (CachedProjectile projectile : projectiles) {

                boolean isGhastBall = projectile.projectileType == FireballEntity.class;
                if (isGhastBall) {
                    // Ignore ghast balls
                    continue;
                }
                if (projectile.projectileType == DragonFireballEntity.class) {
                    // Ignore dragon fireballs
                    continue;
                }
                // (2026-09-19 用户"有防火效果时不要触发举盾策略") 烈焰人小火球
                // 的伤害全是火焰, 身上有 FIRE_RESISTANCE 时被命中完全不掉血 →
                // 不再触发举盾格挡/躲避 (小白箭/三叉戟等非火焰弹射物不受影响)。
                if (projectile.projectileType == SmallFireballEntity.class
                        && mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
                    continue;
                }

                Vec3d expectedHit = ProjectileHelper.calculateArrowClosestApproach(projectile, mod.getPlayer());

                Vec3d delta = mod.getPlayer().getPos().subtract(expectedHit);

                double horizontalDistanceSq = delta.x * delta.x + delta.z * delta.z;
                double verticalDistance = Math.abs(delta.y);

                if (horizontalDistanceSq < ARROW_KEEP_DISTANCE_HORIZONTAL*ARROW_KEEP_DISTANCE_HORIZONTAL && verticalDistance < ARROW_KEEP_DISTANCE_VERTICAL)
                    return true;
            }
        } catch (ConcurrentModificationException e) {
            Debug.logWarning("Weird exception caught and ignored while checking for nearby projectiles.");
        }
        return false;
    }

    private Optional<Entity> getUniversallyDangerousMob(AltoClef mod) {
        // Wither skeletons are dangerous because of the wither effect. Oof kinda obvious.
        // If we merely force field them, we will run into them and get the wither effect which will kill us.
        if (mod.getEntityTracker().entityFound(WitherSkeletonEntity.class)) {
            Optional<Entity> entity = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), WitherSkeletonEntity.class);
            if (entity.isPresent()) {
                double range = SAFE_KEEP_DISTANCE - 2;
                if (entity.get().squaredDistanceTo(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, entity.get())) {
                    return entity;
                }
            }
        }
        // Hoglins are dangerous because we can't push them with the force field.
        // If we merely force field them and stand still our health will slowly be chipped away until we die
        if (mod.getEntityTracker().entityFound(HoglinEntity.class, ZoglinEntity.class)) {
            if (mod.getPlayer().getHealth() < 10) {
                Optional<Entity> entity = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), HoglinEntity.class, ZoglinEntity.class);
                if (entity.isPresent()) {
                    double range = SAFE_KEEP_DISTANCE - 1;
                    if (entity.get().squaredDistanceTo(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, entity.get())) {
                        return entity;
                    }
                }
            }
        }
        // (2026-09-18 猪灵蛮兵绝对回避, 用户"太危险了, 不应该靠近猪灵蛮兵")
        // 蛮兵 40 血金斧、不接受金锭安抚、无视金甲中立, 单只也打不过 —— 20260918-115412
        // 场次实锤: 12:02:44 CONFIDENT FIGHT(1 只蛮兵) 7 秒血量见底, 12:02:56 被蛮兵
        // 打进下界岩浆海 (cause=lava, death #2)。蛮兵无 anger 概念 (见 EntityHelper
        // 注释: 见玩家即敌对), 故这里不查 isAngryAtPlayer, 只要可见且在保留距离内
        // 就立即脱离, 优先于一切战斗决策 (与凋零骷髅同待遇)。范围外的蛮兵由
        // shouldRetreatMelee 的 bruteCount 兜底。
        if (mod.getEntityTracker().entityFound(PiglinBruteEntity.class)) {
            Optional<Entity> entity = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), PiglinBruteEntity.class);
            if (entity.isPresent()) {
                double range = SAFE_KEEP_DISTANCE - 2;
                if (entity.get().squaredDistanceTo(mod.getPlayer()) < range * range) {
                    return entity;
                }
            }
        }
        return Optional.empty();
    }

    private boolean isInDanger(AltoClef mod) {

        if (isVulnurable(mod)) {
            // If hostile mobs are nearby...
            try {
                ClientPlayerEntity player = mod.getPlayer();
                List<Entity> hostiles = mod.getEntityTracker().getHostiles();
                // (2026-09-19 20:10 造墙施工让位) 施工中且威胁全是烈焰人 → 烈焰人
                // 不算"危险", 防 RETREAT(prio 80) 也把造墙抢掉 (195218 实锤 20:01:44)。
                boolean yieldBlazes = shouldYieldBlazesToEnclosureBuild(mod);
                for (Entity entity : hostiles) {
                    // Ignore skeletons
                    if (entity instanceof SkeletonEntity) continue;
                    // (2026-09-30 用户拍板"碰到苦力怕不应该逃跑, 不管有没有盾")
                    // 未点火苦力怕不构成"危险": isInDanger 是证据门滤空后逃跑
                    // 兜底 (20260930-174725 17:59:04/45 两轮 RETREAT 无判决日志,
                    // nearestThreat 全是苦力怕) 的危险源, 不摘掉会绕过 shouldRetreatMelee
                    // 的苦力怕豁免继续逃。点火苦力怕到不了这里 (stateEvaluate
                    // 更早的核弹/诱爆/避爆分支已接管)。
                    if (isUnfusingCreeper(entity)) continue;
                    if (yieldBlazes && entity instanceof BlazeEntity) continue;
                    if (entity.isInRange(player, SAFE_KEEP_DISTANCE) && !mod.getBehaviour().shouldExcludeFromForcefield(entity) && EntityHelper.isAngryAtPlayer(mod, entity)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                Debug.logWarning("Weird multithread exception. Will fix later.");
            }
        }

        return false;
    }

    private boolean isVulnurable(AltoClef mod) {
        int armor = mod.getPlayer().getArmor();
        float health = mod.getPlayer().getHealth();
        if (armor <= 15 && health < 3) return true;
        if (armor < 10 && health < 10) return true;
        return armor < 5 && health < 18;
    }

    public void setTargetEntity(Entity entity) {
        _targetEntity = entity;
    }
    public void resetTargetEntity() {
        _targetEntity = null;
    }

    /** (2026-08-30) 是否正处在已承诺的战斗 (集中击杀/加急击杀) 中。 */
    public boolean isInCombat() {
        return _inCombat;
    }

    /**
     * (2026-09-30 盾核弹禁食, 180851 双杀实锤) 盾牌防御动作（盾核弹/弹射物格挡）
     * 是否正在进行。FoodChain 据此无条件禁食 —— 吃与举盾都按右键: 进食装备链
     * startEat→parkShieldOffHands 会把副手盾捡到光标腾右键, 盾核弹装不回盾
     * (光标不在 getSlotsWithItemScreen 里) → 空手接爆; 且旧口径 isInCombat
     * 不含盾核弹, "战斗中禁食"闸对它不生效 (18:10:07 needEat 在核弹启动前
     * 2.6s 抢跑吃完装填, 18:10:18 二次进食撞上第二只苦力怕点火 → 死亡)。
     */
    public boolean isShieldBlockingActive() {
        return _shieldNukeStartTick >= 0 || _projectileBlocking;
    }

    public void setForceFieldRange(double range) {
        _killAura.setRange(range);
    }

    public void resetForceField() {
        _killAura.setRange(Double.POSITIVE_INFINITY);
    }

    public boolean isDoingAcrobatics() {
        return _doingFunkyStuff;
    }

    @Override
    public boolean isActive() {
        // We're always checking for mobs
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Task is done, so I guess we move on?
    }

    @Override
    public String getName() {
        return "Mob Defense";
    }
}
