package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatMemory;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ThreatEvaluator;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.Material;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.tag.BlockTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Attacks an entity, but the target entity must be specified.
 */
public abstract class AbstractKillEntityTask extends AbstractDoToEntityTask {

    private static final double OTHER_FORCE_FIELD_RANGE = 2;

    // Not the "striking" distance, but the "ok we're close enough, lower our guard for other mobs and focus on this one" range.
    private static final double CONSIDER_COMBAT_RANGE = 10;

    // Continuous shield holding beyond this many ticks forces a counter-attack,
    // so we never just turtle behind the shield forever (2s at 20tps).
    private static final int SHIELD_HOLD_TIMEOUT_TICKS = 40;
    // Minimum ticks between counter-attacks, so we don't spam wasted swings
    // (and, critically, don't drop the shield to swing too often).
    private static final int COUNTER_COOLDOWN_TICKS = 26;
    // (2026-08-30 多怪自适应格挡节奏, 用户) 每次反击段连挥几下而已。
    private static final int COUNTER_HITS_PER_PHASE = 2;
    // 连续举盾到此 tick 仍没格满 n 下 (小白哑火) → 强制开反击, 避免干举盾不打。
    // (2026-08-30 加大耐心, 用户"等一会儿没格挡就放弃") 30→80: 之前 1.5s 就放盾
    // 去 counter, 箭还在飞就露出挨箭; 现在耐心格挡 4s, 只有小白长时间哑火才出手。
    private static final int BLOCK_FORCE_COUNTER_TICKS = 80;
    // (2026-09-06 烈焰人波次盾战, 用户) 烈焰人一轮连发 3 颗小火球 (间隔极短),
    // 把整轮视为一波: 波内只举盾硬格, 波结束 (连续这么多 tick 无弹射物逼近,
    // 烈焰人进入 ~3s 装填冷却) 才放盾反击。小白 (单箭节奏) 不受影响。
    private static final int BLAZE_WAVE_GAP_TICKS = 25;
    // (2026-08-30 贴身, 用户实测) 小白会自己后退, 逼近目标太松会不停失去攻击
    // 距离。盾战逼近距离收紧到贴身 (原 reach-0.5≈2.7 → 0.8), 保证一旦出手就在
    // 武器命中范围内、且格挡时也粘住小白不掉距。
    // (2026-08-31 用户最终值) 一会儿"2~3格太远"(原 default playerReach-1), 一会儿"0.8/0.4太近",
// 最后定 1.5 格为近战贴身距离 (小白/羊牛无盾同用)。
    // (2026-09-01 用户"打小白/僵尸距离太近, 放远一倍") 1.5 → 3.0: 骷髅箭/僵尸
    // 近身太贴容易白挨打, 放远到 3 格仍可命中 (剑 reach ~3), 更安全。
    private static final double MELEE_HUG_DISTANCE = 3.0;
    // (2026-09-01 与 MobDefenseChain 一致) 正在 fuse 的苦力怕接爆范围。
    private static final double CREEPER_SHIELD_BLOCK_RANGE = 16;
    // (2026-08-31 无盾近战并行诊断) 移动+攻击并行关键 tick 节流 (1 秒一条)。
    private static long _lastMeleeFixLog = 0;

    // =====================================================================
    // (2026-09-13 草挡准心, 用户"杀猪被草挡住好几秒, 猪走开才打到猪")
    // 反透视下新增的一类"人眼可见但代码判不出来"的遮障 —— 软植物:
    //   · MC 准心射线走 OUTLINE 形状 → 草/高草/藤蔓/蕨/枯木/花/作物会把准心
    //     吃掉 (PlayerExtraController.attack().isCrosshairOn == false → 一刀不挥);
    //   · 本项目自己的"被挡"判定 (AbstractDoToEntityTask.findObstructingBlock /
    //     computeVisibleAimPoint) 走 COLLIDER 形状 → 上述植物全部穿透 →
    //     判定"没被挡" → 既不进绕位, 也进不了 onObstructionPersistent 清障。
    // 于是 bot 站在射程内反复"干瞄准 + 被拦刀", 一路到 killGiveUpSeconds
    // 超时拉黑。人类玩家在这种场合会顺手把草砍掉, 这里照做: 准心被软植物挡住
    // 时按左键清掉, 下一 tick 准心自然落到目标身上。
    // 实心方块 (树叶/石头/墙) 不走这里 —— 它们 COLLIDER 可测, 交给既有绕位 +
    // 树叶清障 (onObstructionPersistent)。
    // 会话 20260913-155648 现场: 猪死时猪排掉落位 (334,68,-691) 的 itemBlock
    // =grass_block 上的 grass —— 猪站在高草里, 命中前先被自己脚下的草挡住。
    // =====================================================================
    /** 连续清草 tick 上限: 正常 1~4 tick 清完一层草, 1s 清不完就不是"一层草"的问题。 */
    private static final int PLANT_CLEAR_MAX_TICKS = 20;
    /** 清草放弃后的冷却 (写在放弃那一刻, 不放 onStop)。 */
    private static final int PLANT_CLEAR_COOLDOWN_TICKS = 40;
    // 下面三项必须是 static: MobDefenseChain 会按秒重建 `<Killing entity.X>` 实例
    // (tasks.log 里 START/STOP 成对出现), 实例字段会被重建清零 → 清草计数永远
    // 回不到 20 (上限失效)、冷却永远被重置 (等于没写)。本项目铁律: 冷却/计数
    // 放触发条件层, 不放实例。
    /** 是否由本任务按住左键清草 —— 只松开"自己按下的", 不抢别人的左键。 */
    private static boolean _plantClickHeld = false;
    /** 本次连续清草 tick 数。 */
    private static int _plantClearTicks = 0;
    /** 清草放弃后的冷却到期 tick。 */
    private static long _plantClearCooldownUntil = Long.MIN_VALUE;
    /** 清草 / 被拦刀诊断日志节流 (各 1 秒一条)。 */
    private static long _lastPlantClearLogMs = 0;
    private static long _lastCrosshairDeclineLogMs = 0;

    // ---- 盾战状态 (2026-08-30 状态机) ----
    private boolean _arrowInbound = false;   // 当前有箭正朝我们飞 (轨迹预测)
    private int _blockStreak = 0;            // 已格挡箭数 (每放过一支 +1)
    private int _counterHitsLeft = 0;        // 反击段还剩几刀
    private long _blockPhaseStartTick = 0;   // 本次格挡从哪 tick 开始 (超时兜底用)
    // (2026-08-23 反击窗口) 放盾窗口总时长: 收盾后保持右键释放这么久, 让"放下
    // 盾"这个动作清楚可见。举盾(使用盾)会重置攻击冷却, 放盾后立刻挥剑是虚弱
    // 攻击(近0伤害) — 必须等冷却恢复 (SHIELD_DOWN_MIN_GAP ≥ 0.75s)。
    // (2026-08-30 缩短窗口, 用户) 30t → 18t: 旧值 1.5s 放盾太久, 骷髅放箭常
    // 落在这窗口里被漏掉; 现在只留"够挥一刀 + 一点余量"(≥15t 冷却 + 3t), 盾
    // 更快回到手上, 减少漏箭。
    private static final int SHIELD_LOWER_WINDOW_TICKS = 18;
    // 收盾后至少等这么多 tick (0.75s) 才挥剑 — 用户要求"放下盾和反击的间隔
    // 要够大", 否则反击无效。
    private static final int SHIELD_DOWN_MIN_GAP_TICKS = 15;
    // (2026-08-23 打末影人瞄准) 末影人身高 2.9, 眼睛在 ~2.55。用户要求瞄准时
    // "高一格到一格半" (约 1.5 格), 让准心落在头/眼睛区域 — 激怒末影人 +
    // 打到本体。当前 aim 点是 getPos() (脚底), 所以加 1.5。
    private static final double ENDERMAN_AIM_OFFSET = 1.5;

    /**
     * (2026-09-09 凋零骷髅特殊对待, 用户"打凋零靠的太近了, 必须控制靠近距离")
     * 凋零骷髅近战带凋零效果, 贴身白挨凋零太亏。它不参与小白那套"贴身 3.0":
     * 接近距离 = 攻击射程 (entityReachRange, 默认 3.2), 一进射程就停, 绝不往它
     * 身上凑; 它自己冲脸时原地举盾硬打, 不后退 (用户确认不后退)。其余生物维持
     * MELEE_HUG_DISTANCE 不变。
     */
    private static double approachDistance(AltoClef mod, Entity entity) {
        if (entity instanceof WitherSkeletonEntity) {
            return Math.max(2.6, mod.getModSettings().getEntityReachRange());
        }
        return MELEE_HUG_DISTANCE;
    }

    private static final Item[] WEAPON_ITEMS = new Item[]{
            Items.DIAMOND_SWORD,
            Items.IRON_SWORD,
            Items.STONE_SWORD,
            Items.WOODEN_SWORD,
            Items.GOLDEN_SWORD,
            Items.DIAMOND_AXE,
            Items.IRON_AXE,
            Items.STONE_AXE,
            Items.WOODEN_AXE,
            Items.GOLDEN_AXE
    };

    // Tick we started holding the shield up (for the force counter-attack timeout).
    private long _shieldHoldStartTick = -1;

    // (2026-09-07 击杀超时兜底, 用户"蜘蛛躲在暗处 bot 傻傻对准, 必须绕路才能
    // 打到, 也没有击杀超时兜底") 准心校验拟人化后"打不到就是真打不到", 必须
    // 有止损: 连续 killGiveUpSeconds 既没砍中一刀、也没在接近目标 (距离无净
    // 缩短) → 放弃, 标记 CombatMemory 不可达冷却 (unreachableCooldownSeconds),
    // 守卫派杀 / KillEntitiesTask / DoToClosestEntityTask 的目标选择冷却期内
    // 跳过该实体。冷却过后再试一次 (怪可能自己走出来了)。
    private long _lastHitTick = Long.MIN_VALUE;
    private double _prevDistSq = Double.MAX_VALUE;
    private boolean _gaveUp = false;
    // (2026-09-11 树叶清障) 上一次尝试清除的遮挡方块 + 尝试 tick: DestroyBlockTask
    // 对够不着的遮挡点 2s 自弃后, 钩子下一 tick 又会拿同一个点再试 → 死循环。
    // 单槽位冷却 100 tick (5s) 内不重试同一点。
    private BlockPos _lastClearedObstruction = null;
    private long _lastClearAttemptTick = Long.MIN_VALUE;
    // (2026-09-06 烈焰人波次) 最近一次"有弹射物逼近"的 tick — 判定一波连发是否结束。
    private long _lastInboundTick = Long.MIN_VALUE;
    // Last tick we actually swung back, to pace our counter-attacks.
    private long _lastCounterTick = Long.MIN_VALUE;
    // (2026-08-23 反击窗口) 在此之前保持右键释放 (盾放下), 到点重新举盾。
    private long _shieldLowerUntilTick = Long.MIN_VALUE;
    // 反击窗口开始 tick (算"盾放下了多久"用)。
    private long _counterWindowStartTick = Long.MIN_VALUE;

    public AbstractKillEntityTask() {
        // (2026-08-31 用户: 杀小白/羊牛无盾也要贴身) 原默认走 2 参构造 → super(-1)
        // → 父类用 playerReach-1≈2~3格 接近(无盾实测两三格)。改为默认贴身 MELEE_HUG_DISTANCE,
        // 近战接近阶段直奔贴身。子类仍有自己的贴身/战术需要可用显式 3 参构造覆盖。
        this(MELEE_HUG_DISTANCE, CONSIDER_COMBAT_RANGE, OTHER_FORCE_FIELD_RANGE);
    }

    public AbstractKillEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        // 同上: 凡经此构造的都视为近战贴身; 若子类明确不想贴身再用 3 参构造传自定义 mainDist。
        super(MELEE_HUG_DISTANCE, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    public AbstractKillEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(maintainDistance, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    public static void equipWeapon(AltoClef mod) {
        for (Item item : WEAPON_ITEMS) {
            if (mod.getItemStorage().hasItem(item)) {
                mod.getSlotHandler().forceEquipItem(item);
                return;
            }
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        tryEquipShield(mod);
        _lastHitTick = WorldHelper.getTicks();
        _prevDistSq = Double.MAX_VALUE;
        _gaveUp = false;
    }

    /**
     * (2026-09-07 击杀超时兜底) 超过 killGiveUpSeconds 既没命中一刀、距离又无
     * 净缩短 → 放弃该目标: 标记 CombatMemory 冷却并停止本任务的动作 (返回
     * null), 上层 (熔炉守卫 / KillEntitiesTask / DoToClosestEntityTask) 下一
     * tick 重选目标时会跳过冷却中的实体。0 = 永不放弃 (Settings 开关)。
     */
    @Override
    protected Task onTick(AltoClef mod) {
        int giveUpSeconds = mod.getModSettings().getKillGiveUpSeconds();
        Optional<Entity> target = getEntityTarget(mod);
        if (target.isPresent() && giveUpSeconds > 0) {
            UUID id = target.get().getUuid();
            long now = WorldHelper.getTicks();
            long lastSwing = mod.getControllerExtras().getLastSwingTickFor(id);
            if (lastSwing > _lastHitTick) _lastHitTick = lastSwing;
            double distSq = target.get().squaredDistanceTo(mod.getPlayer());
            // 距离仍在净缩短 = 接近有进展, 刷新止损窗口 (长途追击不误杀)。
            boolean closingIn = distSq < _prevDistSq - 0.25;
            _prevDistSq = distSq;
            if (closingIn) _lastHitTick = now;
            if (!_gaveUp && now - _lastHitTick > giveUpSeconds * 20L) {
                int cooldown = mod.getModSettings().getUnreachableCooldownSeconds();
                CombatMemory.markUnreachable(id, cooldown * 20);
                _gaveUp = true;
                _lastHitTick = now; // 防冷却期内每 tick 刷日志
                setDebugState("Kill timeout: target unreachable, giving up");
                Debug.logMessage("[KillTimeout] giving up on " + target.get().getType().getTranslationKey()
                        + " (no hit and no progress for " + giveUpSeconds + "s, cooldown " + cooldown + "s)");
                return null;
            }
        }
        return super.onTick(mod);
    }

    // (2026-08-23 打末影人) 末影人机制: 你不盯着它, 它不会主动攻击/追你。
    // 把它设为击杀目标后, 接近途中就持续把准心对准它 → 激怒它, 它瞬移
    // 到 bot 面前主动接战, bot 原地打不用追; 同时持续对准 = 持续感知
    // (EntityTracker 20 秒感知记忆兜底), 不会跟丢。瞄准用眼睛高度
    // (aimPoint), 而不是脚底。
    @Override
    protected void onEntityApproach(AltoClef mod, Entity entity) {
        if (entity instanceof EndermanEntity) {
            LookHelper.lookAt(mod, aimPoint(entity));
        }
    }

    /**
     * (2026-09-11 树叶清障, 用户"猪躲在树叶下 bot 只会干瞄准然后放弃") 视线被挡
     * 绕位 8s 仍无效时 (父类 AbstractDoToEntityTask 钩子), 若遮挡方块是树叶类
     * → 直接砍掉 (拟人: 玩家遇到挡路的树叶会顺手砍掉, 而不是绕着树打转)。
     * 走 DestroyBlockTask 标准挖方块管线, 全局 avoidBlockBreaking 谓词 (含黑曜石
     * 门保护等禁挖层) 依然生效; 非树叶遮挡 (墙/石头) 返回 null 维持绕位, 不挖墙。
     */
    @Override
    protected Task onObstructionPersistent(AltoClef mod, Entity entity, BlockPos obstruction) {
        long now = WorldHelper.getTicks();
        if (obstruction.equals(_lastClearedObstruction) && now - _lastClearAttemptTick < 100) {
            return null; // 同一点刚试过失败, 冷却期内继续绕位
        }
        if (mod.getWorld().getBlockState(obstruction).isIn(BlockTags.LEAVES)) {
            _lastClearedObstruction = obstruction;
            _lastClearAttemptTick = now;
            setDebugState("Clearing leaves blocking target at " + obstruction.toShortString());
            Debug.logToFile("altoclef_diag.log", "[LOS-Clear]\tkill target blocked by leaves at "
                    + obstruction.toShortString() + ", destroying");
            return new DestroyBlockTask(obstruction);
        }
        return null;
    }

    // (2026-08-23) 瞄准点: 末影人瞄准眼睛 (脚底 +1.5), 其余生物默认脚底。
    // (2026-08-31 用户: 瞄脚部不对) 其余生物改为瞄身体中心 (bounding box 中心),
    // 准心落在身体中段而非脚底。
    private static Vec3d aimPoint(Entity entity) {
        if (entity instanceof EndermanEntity) {
            return entity.getPos().add(0, ENDERMAN_AIM_OFFSET, 0);
        }
        return entity.getBoundingBox().getCenter();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        super.onStop(mod, interruptTask);
        // Don't leave right-click held down (shield would stay raised forever).
        mod.getInputControls().release(Input.CLICK_RIGHT);
        _shieldHoldStartTick = -1;
        _lastCounterTick = Long.MIN_VALUE;
        _shieldLowerUntilTick = Long.MIN_VALUE;
        _counterWindowStartTick = Long.MIN_VALUE;
        _lastInboundTick = Long.MIN_VALUE;
        _lastClearedObstruction = null;
        _lastClearAttemptTick = Long.MIN_VALUE;
        // (2026-09-13 草挡准心) 清草按下的左键要松开, 否则残留长按变成持续挖方块。
        // 清草计数与冷却都是 static (跨实例), 这里不动 —— 上层重建实例时不重置,
        // 冷却从"放弃那一刻"起算, 不被 START/STOP 洗掉。
        if (_plantClickHeld) {
            _plantClickHeld = false;
            mod.getInputControls().release(Input.CLICK_LEFT);
        }
    }

    @Override
    protected Task onEntityInteract(AltoClef mod, Entity entity) {
        // Face the enemy even when attacking without a shield. 末影人瞄准眼睛。
        // (2026-09-12 瞄头) 父类多点视线判定出的可见点优先 —— 目标只露头时
        // (贴方块沿/蹲掩体) 准心直接落在头部, crosshairTarget=目标实体 →
        // isCrosshairOn 通过, 能真实出刀; 旧逻辑死瞄包围盒中心, 中心被挡
        // 准心落在方块上 → 永不出手。没有可见点才退回中心/眼睛兜底。
        Vec3d aim = getVisibleAimPoint() != null ? getVisibleAimPoint() : aimPoint(entity);
        LookHelper.lookAt(mod, aim);

        // (2026-08-28 战斗中即刻装盾) 盾通常只在背包、未必已换到副手。
        // 开战瞬间若副手没盾, 旧代码 isOffHand 判定 false → 整段走"直接
        // 攻击"分支, 根本不用盾格挡, 打骷髅白吃箭。每次交互先 try 装盾
        // (已在副手则立即返回), 再判定 —— 只要背包有盾, 战斗 tick 就
        // 能进盾战格挡。
        tryEquipShield(mod);
        // Shield combat: block, wait for the enemy to strike, then counter-attack.
        // (2026-08-23 rev3) 盾牌只用于小白 (骷髅/凋零骷髅射箭)、苦力怕 (爆炸) 和
        // 烈焰人 (火球, 2026-09-06 用户加入 — 落单烈焰人不浪费防火药水, 改走盾战)。
        // 盾牌本质是防远程投射物和爆炸; 纯近战怪 (僵尸/末影人) 直接剑砍。
        // 烈焰人与小白节奏不同: 一轮连发 3 球, 走"波内只格挡、波次间反击"分支
        // (见 shieldCombatTick 内 blazeTarget 处理)。
        boolean hasShield = mod.getPlayer().getOffHandStack().getItem() == Items.SHIELD;
        boolean shieldTarget = entity instanceof AbstractSkeletonEntity || entity instanceof CreeperEntity
                || entity instanceof BlazeEntity;
        if (hasShield && shieldTarget) {
            return shieldCombatTick(mod, entity);
        }

        // No shield equipped or shield not applicable: PERSISTENT approach + melee,
        // running in parallel like the shield branch.
        // (2026-08-31 无盾近战并行, 用户) 每 tick 恒定返回"贴近 move"(GetToEntityTask,
        // MELEE_HUG_DISTANCE=1.5, 与盾战一致), 到达攻击判定且攻击冷却到位时用
        // attack() 作为【侧写】叠加在移动之上 — 不再"靠近→停→打一下→靠近"的交替
        // (用户实测杀小白/羊/牛无盾时交替停顿)。到射程内才抬手, 避免空挥。
        // (2026-09-09 凋零骷髅) 接近距离按目标类型区分: 凋零骷髅=射程即停,
        // 其余维持 MELEE_HUG_DISTANCE 贴身。
        Task move = new GetToEntityTask(entity, approachDistance(mod, entity));
        double reach = mod.getModSettings().getEntityReachRange();
        boolean inReach = entity.squaredDistanceTo(mod.getPlayer()) <= reach * reach;
        // Equip weapon
        equipWeapon(mod);
        // (2026-09-13 草挡准心) 准心被软植物挡住时先清草 —— 这一 tick 不出刀
        // (草一碰即碎, 下一 tick 准心自然落到目标)。清草与攻击冷却无关, 放冷却
        // 判断之前, 不浪费攻击冷却窗口。
        if (inReach && clearPlantBlockingCrosshair(mod)) {
            return move;
        }
        float hitProg = mod.getPlayer().getAttackCooldownProgress(0);
        if (inReach && hitProg >= 0.75f) {
            // (2026-09-13 被拦刀诊断) 出刀前先记录准心是否真在目标身上: attack()
            // 内部 isCrosshairOn 不通过就只登记瞄准、不挥刀, 而旧日志写在调用之后
            // 且不分"出手/被拦" —— 现场无法区分。这里补一条被拦记录。
            boolean crosshairOnTarget = mod.getControllerExtras().isCrosshairOn(entity);
            mod.getControllerExtras().attack(entity);
            if (!crosshairOnTarget) logDeclinedSwing(mod, entity);
            long nowMs = System.currentTimeMillis();
            if (nowMs - _lastMeleeFixLog > 1000) {
                _lastMeleeFixLog = nowMs;
                Debug.logToFile("altoclef_diag.log",
                        "[MELEE-FIX]\t[MovingAndAttacking]\tmelee: attack() side-effect while returning GetToEntityTask(0.8)");
            }
        }
        return move;
    }

    /**
     * (2026-09-13 草挡准心) 准心被"软植物"挡住 → 人类式左键清掉, 本 tick 不出刀。
     *
     * 纪律 (与 InstantBreakTask / MineAndCollectTask 清草同一套, 教训见
     * InstantBreakTask:71-74): ① 必须确认准心射线真的命中该植物再按左键,
     * 否则攻击会发生在"看得见怪"的错觉下、打到别的东西; ② 只松开本任务自己
     * 按下的左键, 不碰别的模块 (挖矿 / InstantBreakTask) 持有的左键。
     *
     * @return true = 本 tick 左键已用在清草上, 调用方不要再 attack()
     */
    private boolean clearPlantBlockingCrosshair(AltoClef mod) {
        long now = WorldHelper.getTicks();
        if (now < _plantClearCooldownUntil) return false;

        BlockPos plant = null;
        HitResult result = MinecraftClient.getInstance().crosshairTarget;
        if (result instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockState state = mod.getWorld().getBlockState(bhr.getBlockPos());
            if (isSoftPlantBlock(mod, state, bhr.getBlockPos())) plant = bhr.getBlockPos();
        }

        if (plant == null) {
            // 准心已离开植物 (清完了 / 目标挪开了) → 松开自己按的那次左键。
            if (_plantClickHeld) {
                _plantClickHeld = false;
                _plantClearTicks = 0;
                mod.getInputControls().release(Input.CLICK_LEFT);
            }
            return false;
        }

        if (_plantClearTicks >= PLANT_CLEAR_MAX_TICKS) {
            // 止损: 清了一秒还没清完, 不是"一层草"的问题 → 放弃并按冷却
            // (冷却写在放弃这一刻, 不写 onStop)。
            _plantClickHeld = false;
            _plantClearTicks = 0;
            _plantClearCooldownUntil = now + PLANT_CLEAR_COOLDOWN_TICKS;
            mod.getInputControls().release(Input.CLICK_LEFT);
            setDebugState("Gave up clearing plant at " + plant.toShortString());
            Debug.logToFile("altoclef_diag.log", "[MELEE-Clear]\tTIMEOUT " + PLANT_CLEAR_MAX_TICKS
                    + "t clearing " + blockName(mod, plant) + " at " + plant.toShortString()
                    + ", cooldown " + PLANT_CLEAR_COOLDOWN_TICKS + "t");
            return false;
        }

        _plantClearTicks++;
        _plantClickHeld = true;
        mod.getInputControls().hold(Input.CLICK_LEFT);
        setDebugState("Clearing plant blocking melee target at " + plant.toShortString());
        long nowMs = System.currentTimeMillis();
        if (nowMs - _lastPlantClearLogMs > 1000) {
            _lastPlantClearLogMs = nowMs;
            Debug.logToFile("altoclef_diag.log", "[MELEE-Clear]\tcrosshair blocked by "
                    + blockName(mod, plant) + " at " + plant.toShortString() + ", left-click swinging");
        }
        return true;
    }

    /**
     * (2026-09-13) "软植物"判据 = 有轮廓 (能挡准心) + 无碰撞 (COLLIDER 视线射线
     * 穿透) + hardness 0 (一碰即碎)。草/高草/蕨/藤蔓/枯木/树苗/花/作物都落在这里;
     * 树叶 (hardness 0.2) / 竹 / 石头 / 墙 不满足, 仍走既有绕位与树叶清障。
     * 流体防御性排除 (准心射线本来就不测流体, 但别让 WATER/LAVA 的 material 混进来)。
     */
    private static boolean isSoftPlantBlock(AltoClef mod, BlockState state, BlockPos pos) {
        if (state.isAir()) return false;
        Material material = state.getMaterial();
        if (material == Material.WATER || material == Material.LAVA) return false;
        return state.getHardness(mod.getWorld(), pos) == 0.0f
                && state.getCollisionShape(mod.getWorld(), pos).isEmpty()
                && !state.getOutlineShape(mod.getWorld(), pos).isEmpty();
    }

    /** 方块名 (诊断用, 失败不抛)。 */
    private static String blockName(AltoClef mod, BlockPos pos) {
        try {
            return Registry.BLOCK.getId(mod.getWorld().getBlockState(pos).getBlock()).getPath();
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * (2026-09-13 被拦刀诊断) 射程内 + 攻击冷却就绪, 但准心没落在目标身上 →
     * 这一刀被 isCrosshairOn 拦下 (拟人化出刀纪律, 不是 bug)。把准心实际命中的
     * 东西记下来: 下次"打不到怪"就有现场证据 (block 名 + 是否软植物 / entity /
     * miss)。1 秒一条节流。
     */
    private void logDeclinedSwing(AltoClef mod, Entity entity) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - _lastCrosshairDeclineLogMs < 1000) return;
        _lastCrosshairDeclineLogMs = nowMs;
        String what;
        HitResult result = MinecraftClient.getInstance().crosshairTarget;
        if (result instanceof EntityHitResult ehr) {
            what = "entity " + ehr.getEntity().getType().getTranslationKey();
        } else if (result instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            BlockState state = mod.getWorld().getBlockState(pos);
            boolean soft = isSoftPlantBlock(mod, state, pos);
            what = "block " + blockName(mod, pos) + " at " + pos.toShortString()
                    + (soft ? " [soft-plant]" : "");
        } else if (result == null) {
            what = "null";
        } else {
            what = result.getType().toString();
        }
        Debug.logToFile("altoclef_diag.log", "[MELEE-Crosshair]\tswing DECLINED on "
                + entity.getType().getTranslationKey() + " (crosshair not on target), crosshair=" + what);
    }

    /**
     * Moves a shield from the inventory to the off-hand if we have one.
     */
    private void tryEquipShield(AltoClef mod) {
        if (mod.getPlayer().getOffHandStack().getItem() == Items.SHIELD) return;
        List<Slot> shieldSlots = mod.getItemStorage().getSlotsWithItemScreen(Items.SHIELD);
        if (shieldSlots.isEmpty()) return;
        mod.getSlotHandler().clickSlot(PlayerSlot.OFFHAND_SLOT, shieldSlots.get(0).getInventorySlot(), SlotActionType.SWAP);
    }

    private Task shieldCombatTick(AltoClef mod, Entity entity) {
        // (2026-08-23 苦力怕最高优先级) 正在 fuse 的苦力怕爆炸伤害远超骷髅/
        // 近战怪, 必须最高优先级防御。旧逻辑 faceOptimalBlockDirection 用
        // ThreatEvaluator 加权 (骷髅 60 > 苦力怕 40), 结果举盾朝骷髅、背对
        // 苦力怕。检测到 fuse 苦力怕时直接朝它举盾, 其他威胁一律让位。
        CreeperEntity fusingCreeper = getClosestFusingCreeper(mod);
        // (2026-09-01 接爆范围与 MobDefenseChain 一致 10→16: 苦力怕在 10.7 格引爆
        // 时旧判定刚超界, 杀怪任务不举盾, 被链层"逃跑"接管)
        if (fusingCreeper != null && fusingCreeper.isInRange(mod.getPlayer(), CREEPER_SHIELD_BLOCK_RANGE)) {
            setDebugState("Shield up vs fusing creeper (highest priority)");
            // (2026-09-07 防御朝向优先) 面朝 fuse 苦力怕走仲裁防御槽, 不被
            // 攻击瞄准覆盖。
            mod.getControllerExtras().requestDefenseAim(fusingCreeper.getPos());
            mod.getInputControls().hold(Input.CLICK_RIGHT);
            return null;
        }

        String targetName = entity.getType().getTranslationKey();
    long nowTick = WorldHelper.getTicks();

    // (2026-08-30 多怪自适应格挡节奏, 用户) 状态机:
    //   格挡 n 下 (n = 感知到的远程威胁数) → 逼近中持续举盾 → 到射程后抽空反击 2 下。
    // 不再用固定"放盾1.5s窗口" (不适用多只小白)。危险信号只用"有无正朝我们飞的箭"。

    // 每 tick 确保副手有盾 (战斗中随时可能被进食/合成换走)。
    tryEquipShield(mod);

    double reach = mod.getModSettings().getEntityReachRange();
    boolean inReach = entity.squaredDistanceTo(mod.getPlayer()) <= reach * reach;
    int n = Math.max(1, countNearbyRangedThreats(mod)); // 感知到几只远程威胁 (至少算1)

    // 打小白时盾要正对小白方向; 不再用多威胁加权平均 (会偏开射来的箭)。
    // (2026-09-12 瞄头) 同近战分支: 可见点 (头) 优先, 旧 entity.getPos() 是脚底,
    // 小白只露头时盾/准心对着方块沿, 既格挡不了也打不出。
    LookHelper.lookAt(mod, getVisibleAimPoint() != null ? getVisibleAimPoint() : aimPoint(entity));

    // 箭轨迹追踪: 有箭飞来 → _arrowInbound=true; 放过 → 记一次"已格挡"。
    updateArrowStreak(mod);

    // (2026-08-30 全程持续逼近, 用户) 不论格挡、反击还是等待, 每个 tick 都返回
    // 持续的"往小白脸前贴"(0.8)子任务 —— GetToEntityTask 只是子动作, 不会顶掉
    // 杀怪父任务; 盾的 hold / 挥剑作为侧写叠加在移动之上跨 tick 生效。这样小白
    // 后退时 bot 始终粘着贴, 不会停原地眼睁睁掉距。
    // (2026-09-09 凋零骷髅) 盾战逼近同样按类型区分: 凋零骷髅=射程即停不贴身。
    Task move = new GetToEntityTask(entity, approachDistance(mod, entity));

    if (!inReach) {
        _counterHitsLeft = 0;
        _blockStreak = 0;
        _blockPhaseStartTick = nowTick;
        if (_arrowInbound) mod.getInputControls().hold(Input.CLICK_RIGHT);
        else mod.getInputControls().release(Input.CLICK_RIGHT);
        return move;
    }

    // 有箭正飞来: 立即举盾 (会打断反击), 优先防御。
    if (_arrowInbound) {
        setDebugState("Shield blocking " + targetName + " (arrow inbound)");
        _counterHitsLeft = 0;
        _blockPhaseStartTick = nowTick;
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        return move;
    }

    // 反击阶段: 放盾挥 COUNTER_HITS_PER_PHASE 下。
    if (_counterHitsLeft > 0) {
        setDebugState("Counter-attacking " + targetName + " (" + _counterHitsLeft + " left)");
        mod.getInputControls().release(Input.CLICK_RIGHT);
        if (mod.getPlayer().getAttackCooldownProgress(0) >= 0.75f) {
            equipWeapon(mod);
            mod.getControllerExtras().attack(entity);
            _counterHitsLeft--;
        }
        return move;
    }

    // (2026-09-06 烈焰人波次, 用户) 已格满 n 下 (小白: 单箭节奏) 或 举盾超时
    // (哑火兜底) → 开反击阶段。烈焰人例外: 一轮连发 3 球, 按 _blockStreak>=n
    // 会在波内提前开反击窗然后立刻被打断 — 改为"一波结束才反击":
    // 连续 BLAZE_WAVE_GAP_TICKS 无弹射物逼近且本波至少格到过 1 颗。
    boolean blazeTarget = entity instanceof BlazeEntity;
    boolean enoughBlocks;
    if (blazeTarget) {
        long sinceLastInbound = (_lastInboundTick == Long.MIN_VALUE) ? Long.MAX_VALUE
                : nowTick - _lastInboundTick;
        enoughBlocks = !_arrowInbound && _blockStreak > 0 && sinceLastInbound >= BLAZE_WAVE_GAP_TICKS;
    } else {
        enoughBlocks = _blockStreak >= n;
    }
    boolean forced = (nowTick - _blockPhaseStartTick) > BLOCK_FORCE_COUNTER_TICKS;
    if (enoughBlocks || forced) {
        setDebugState("Opening counter window (" + n + " threats, " + _blockStreak + " blocked)");
        _counterHitsLeft = COUNTER_HITS_PER_PHASE;
        _blockStreak = 0;
        _blockPhaseStartTick = nowTick;
        mod.getInputControls().release(Input.CLICK_RIGHT);
        return move;
    }

    // 继续格挡计数。
    setDebugState("Shield blocking " + targetName + " (streak " + _blockStreak + "/" + n + ")");
    mod.getInputControls().hold(Input.CLICK_RIGHT);
    return move;
    }

    /**
     * (2026-08-23) 最近正在 fuse 的苦力怕 (爆炸优先级最高的威胁)。
     */
    private CreeperEntity getClosestFusingCreeper(AltoClef mod) {
        CreeperEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (CreeperEntity creeper : mod.getEntityTracker().getTrackedEntities(CreeperEntity.class)) {
            if (creeper == null || !creeper.isAlive()) continue;
            if (creeper.getClientFuseTime(1) < 0.001f) continue; // 未在 fuse
            double d = creeper.squaredDistanceTo(mod.getPlayer());
            if (d < bestDist) {
                bestDist = d;
                best = creeper;
            }
        }
        return best;
    }

    /**
     * Faces the direction that best blocks incoming damage: a threat-weighted
     * average of all perceived hostiles. Close melee enemies dominate the direction
     * (weight / max(1, dist/8)), while distant skeletons still pull the facing a bit
     * so their arrows get blocked too.
     */
    private void faceOptimalBlockDirection(AltoClef mod, Entity primary) {
        // (2026-08-23 打末影人) 末影人必须先被盯着 (看头/眼睛) 才会被激怒并
        // 攻击; 同时举盾格挡也要面朝它。直接面朝末影人的眼睛 (aimPoint),
        // 跳过威胁加权 (加权方向是水平向 8 格, 不会抬准心看头 → 激怒不了)。
        if (primary instanceof EndermanEntity) {
            LookHelper.lookAt(mod, aimPoint(primary));
            return;
        }
        List<Entity> threats = new ArrayList<>();
        if (primary != null && primary.isAlive()) threats.add(primary);
        threats.addAll(mod.getEntityTracker().getHostiles());
        // Distant skeletons shooting us may not be in "hostiles" (26-block cap), but
        // they are still perceived; include them so we can block their arrows too.
        for (Entity tracked : mod.getEntityTracker().getTrackedEntities(AbstractSkeletonEntity.class)) {
            if (tracked.isAlive() && tracked != primary && EntityHelper.isAngryAtPlayer(mod, tracked)) {
                threats.add(tracked);
            }
        }
        if (threats.isEmpty()) return;

        Vec3d playerPos = mod.getPlayer().getPos();
        Vec3d weightedDirection = Vec3d.ZERO;
        for (Entity threat : threats) {
            if (threat == null || !threat.isAlive()) continue;
            Vec3d toThreat = threat.getPos().subtract(playerPos);
            double dist = toThreat.length();
            if (dist < 0.01) continue;
            double weight = ThreatEvaluator.evaluate(mod, threat);
            if (weight <= 0) continue;
            // Distance-penalize so a zombie in your face outweighs a far-away skeleton,
            // while the skeleton still contributes enough to turn our facing slightly.
            weightedDirection = weightedDirection.add(toThreat.multiply(weight / Math.max(1.0, dist / 8.0) / dist));
        }
        if (weightedDirection.lengthSquared() < 1e-4) return;
        LookHelper.lookAt(mod, playerPos.add(weightedDirection.normalize().multiply(8)));
    }

    private boolean isEnemyAttacking(Entity entity) {
        if (entity instanceof AbstractSkeletonEntity skeleton) {
            // Skeletons attack by aiming their bow; block while they do so.
            return skeleton.isAttacking();
        }
        if (entity instanceof LivingEntity living) {
            // Melee mobs "swing" when they hit us.
            return living.getHandSwingProgress(1.0f) > 0.4f;
        }
        return false;
    }

    /**
     * (2026-08-30 箭轨迹预测, 用户) 是否有箭/投射物"即将命中我们"。改版:
     * 旧用的是"箭当前在 10 格半径内" —— 近身时箭飞极快(约3格/tick), 进半径到
     * 命中只 1 tick, 几乎采不到 → 盾举不起来。改用【轨迹最近接近点】: 按箭当前
     * 速度外推它未来是否会贴近玩家, 远近都可靠, 能在箭到之前提前举盾。
     * 视觉对应: 箭离弦 → 预测会命中 → 举盾; 箭落地/放过 → 才放手。
     */
    private boolean incomingArrowNear(AltoClef mod) {
        try {
            for (adris.altoclef.util.baritone.CachedProjectile p : mod.getEntityTracker().getProjectiles()) {
                if (p == null || p.position == null) continue;
                if (p.projectileType == net.minecraft.entity.projectile.FireballEntity.class
                        || p.projectileType == net.minecraft.entity.projectile.DragonFireballEntity.class) continue;
                net.minecraft.util.math.Vec3d expectedHit = adris.altoclef.util.helpers.ProjectileHelper.calculateArrowClosestApproach(p, mod.getPlayer());
                net.minecraft.util.math.Vec3d delta = mod.getPlayer().getPos().subtract(expectedHit);
                double horizontalDistanceSq = delta.x * delta.x + delta.z * delta.z;
                double verticalDistance = Math.abs(delta.y);
                // 轨迹会在水平约1.5格、垂直2格内经过玩家 → 会被命中
                if (horizontalDistanceSq < 2.25 && verticalDistance < 2.0) return true;
            }
        } catch (Exception e) {
            // 追踪器并发访问偶尔抛错, 忽略
        }
        return false;
    }

    /**
     * (2026-08-30) 感知到的远程威胁数量 (愤怒的骷髅/凋零骷髅 + 附近的烈焰人)。
     * 决定本轮要"格挡多少下"再反击 — n 只小白就多挡, 反击才够安全。
     */
    private int countNearbyRangedThreats(AltoClef mod) {
        double RANGED_THREAT_RANGE = 20.0;
        int count = 0;
        for (Entity tracked : mod.getEntityTracker().getTrackedEntities(AbstractSkeletonEntity.class)) {
            if (tracked.isAlive() && EntityHelper.isAngryAtPlayer(mod, tracked)
                    && tracked.squaredDistanceTo(mod.getPlayer()) <= RANGED_THREAT_RANGE * RANGED_THREAT_RANGE) count++;
        }
        for (Entity tracked : mod.getEntityTracker().getTrackedEntities(BlazeEntity.class)) {
            if (tracked.isAlive()
                    && tracked.squaredDistanceTo(mod.getPlayer()) <= RANGED_THREAT_RANGE * RANGED_THREAT_RANGE) count++;
        }
        return count;
    }

    /**
     * (2026-08-30) 箭轨迹追踪: 有箭正朝我们飞 → 置 _arrowInbound; 放过该箭 → 记
     * 一次"已格挡" (_blockStreak++)。用于"格挡 n 下"的计数。
     */
    private void updateArrowStreak(AltoClef mod) {
        boolean now = incomingArrowNear(mod);
        if (now) {
            _arrowInbound = true;
            // (2026-09-06 烈焰人波次) 记录最近一次逼近 tick, 供"一波连发结束"判定。
            _lastInboundTick = WorldHelper.getTicks();
        } else if (_arrowInbound) {
            _blockStreak++;
            _arrowInbound = false;
        }
    }
}
