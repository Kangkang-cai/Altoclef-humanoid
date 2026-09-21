package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatMemory;
import adris.altoclef.util.helpers.EntityHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Kill all entities of a type
 */
public class KillEntitiesTask extends DoToClosestEntityTask {

    /**
     * (2026-09-19 22:44) 额外候选来源 —— 绕过人类式感知, 交给
     * {@link DoToClosestEntityTask#additionalCandidates}。null = 只用感知路径 (原行为)。
     */
    private final Supplier<List<Entity>> _candidatesOverride;

    /**
     * (2026-09-20 现场 213856 实锤) 击杀链路的兜底游荡改为【有限距离】:
     * 基类默认 getWanderTask = 无参 TimeoutWanderTask(true) = 无限直线接力
     * (128 格/段), 目标进 25s 不可达冷却后 bot 沿方位角一路跑穿地图
     * ("wander 超远, 打完回来林子都不见了")。击杀类任务的游荡目的只是
     * "换个视角/引出躲藏的怪", 24 格一跳足够; 缓存单实例复用 —— 方位角
     * 跨轮持久 (方向纪律), 每轮重走时 onStart 重置原点。基类默认对挖矿/
     * 寻物族 (靠无限探索找资源) 保持不变。
     */
    private Task _wanderTask = null;

    private static final float WANDER_DISTANCE = 24;

    // ===== (2026-09-21 末影人单目标锁收编) =====
    // "一次只吸引一只末影人"这条纪律 2026-09-20 只长在诡异森林蹲守任务
    // (CollectEnderPearlsInForestTask) 里; 末地缺珍珠走的是【通用击杀链】
    // (WaitForDragonAndPearlTask:81 → TaskCatalogue → KillAndLootTask → 本类),
    // 没有锁 → 末地末影人密度高, 打一只时被盯愤怒的另一只只要"显著更近"
    // 就切目标 (closerTargetMayBreakSticky=true)。现场实锤 20260921-171828
    // tasks.log: Mob Defense "Switching to significantly closer target" 连切末影人。
    // 同类场景一致处理 (用户"堡垒行, 森林就做同样处理"): 锁收编进本类,
    // 末影人击杀全维度生效 (主世界狩猎同规则)。
    //   - 锁定语义与林子任务一致: 击杀锁定那只之前绝不吸引/去打第二只;
    //   - 例外 = 已经愤怒的末影人 (已在攻击我们, 打它是自保不是招新);
    //   - 锁定的不可达 (CombatMemory 冷却) 且无愤怒替代 → 返回 empty 走
    //     wander 等 (锁保留), 不把新目标交出去。
    private final boolean _endermanHunt;
    private final Predicate<Entity> _shouldKillStore;
    private UUID _engagedId = null;

    public KillEntitiesTask(Predicate<Entity> shouldKill, Class... entities) {
        this(null, shouldKill, entities);
    }

    /**
     * (2026-09-19 22:44 用户"明明有烈焰人在区域内任务还是等待生成") 带补充候选的构造。
     * 用于"目标肯定还在, 只是 bot 被自己砌的墙挡住视线"的场景 —— 不传 = 原行为。
     */
    public KillEntitiesTask(Supplier<List<Entity>> candidatesOverride, Predicate<Entity> shouldKill, Class... entities) {
        super(KillEntityTask::new, shouldKill, entities);
        _candidatesOverride = candidatesOverride;
        _shouldKillStore = shouldKill;
        _endermanHunt = Arrays.stream(entities)
                .anyMatch(c -> c != null && EndermanEntity.class.isAssignableFrom(c));
    }

    public KillEntitiesTask(Class... entities) {
        this(null, entity -> true, entities);
    }

    @Override
    protected List<Entity> additionalCandidates(AltoClef mod) {
        return _candidatesOverride == null ? Collections.emptyList() : _candidatesOverride.get();
    }

    @Override
    protected boolean closerTargetMayBreakSticky(AltoClef mod) {
        // (2026-09-18 战斗近距优先, 用户"击打目标优先靠的近的") 杀怪场景允许
        // "显著更近"的新目标打破粘滞 — 僵尸贴脸时不再死追远处骷髅 31.5 秒。
        // 0.85 系数在基类里防横跳。
        // (2026-09-21) 末影人狩猎例外: getClosestTo 已被单目标锁接管, 不会返回
        // "显著更近"的第二只, 本开关对末影人不再生效 (非末影人狩猎保持原语义)。
        return !_endermanHunt;
    }

    /**
     * (2026-09-21 单目标锁) 末影人狩猎时接管目标选择: 锁定存在期间只返回锁定的
     * 那一只。非末影人狩猎原样走基类 (最近目标 + 粘滞)。
     */
    @Override
    protected Optional<Entity> getClosestTo(AltoClef mod, Vec3d pos) {
        if (!_endermanHunt) return super.getClosestTo(mod, pos);
        List<EndermanEntity> endermen = mod.getEntityTracker().getTrackedEntities(EndermanEntity.class);

        // 1) 解锁: 锁定的已死 / 彻底从感知 (EntityTracker 20s 记忆窗口) 消失。
        //    只是瞬移到墙后时它仍在感知里, 锁不释放 (与林子任务同口径)。
        EndermanEntity engaged = findEngaged(endermen);
        if (_engagedId != null && engaged == null) {
            Debug.logToFile("altoclef_diag.log", "[ENDERMAN-LOCK]\tENGAGE-UNLOCK id=" + _engagedId
                    + " (锁定的末影人已死/彻底消失, 锁释放)");
            _engagedId = null;
        }

        // 2) 上锁: 已愤怒的最近一只优先 (自保); 一只愤怒的都没有 → 锁最近的一只
        //    (它就是即将被吸引的目标)。候选必须过 shouldKill (MobDefenseChain 等
        //    调用方会带过滤谓词) 且不在 CombatMemory 不可达冷却里 (与基类 accept 同口径)。
        if (_engagedId == null && !endermen.isEmpty()) {
            EndermanEntity pick = nearestMatching(mod, endermen, null, true);
            if (pick == null) pick = nearestMatching(mod, endermen, null, false);
            if (pick != null) {
                _engagedId = pick.getUuid();
                engaged = pick;
                boolean angry = EntityHelper.isGenerallyHostileToPlayer(mod, pick);
                Debug.logToFile("altoclef_diag.log", "[ENDERMAN-LOCK]\tENGAGE-LOCK id=" + _engagedId
                        + " dist=" + String.format("%.1f", pick.distanceTo(mod.getPlayer()))
                        + (angry ? " (已愤怒, 自保)" : " (吸引目标; 击杀它之前不再吸引/攻击第二只)"));
            }
        }

        if (engaged == null) {
            // 一只都没感知到 → 交回基类空分支 (wander; 父层探索任务负责找)。
            return Optional.empty();
        }

        // 3) 锁定存在: 不可达 (寻路失败冷却) 时的例外条款 —— 有其它【已愤怒】的
        //    可以打 (自保); 没有 → 返回 empty (wander 等它现身/冷却到期), 锁保留。
        if (CombatMemory.isUnreachable(engaged.getUuid())) {
            EndermanEntity other = nearestMatching(mod, endermen, _engagedId, true);
            if (other != null) {
                setDebugState("Locked enderman unreachable; killing another ALREADY-angry one");
                return Optional.of(other);
            }
            return Optional.empty();
        }
        return Optional.of(engaged);
    }

    /** 感知列表里找锁定的那只 (isAlive 过滤; 死亡/消失返回 null → 上层解锁)。 */
    private EndermanEntity findEngaged(List<EndermanEntity> list) {
        if (_engagedId == null) return null;
        for (EndermanEntity e : list) {
            if (e != null && e.isAlive() && _engagedId.equals(e.getUuid())) return e;
        }
        return null;
    }

    /**
     * 候选选择: angryOnly=true 只看已愤怒的; excludeId 排除锁定的那只 (例外条款用)。
     * 必须过 _shouldKillStore + 不在 CombatMemory 不可达冷却 (与基类 accept 同口径)。
     * 愤怒判据复用 EntityHelper.isGenerallyHostileToPlayer (1.18.2 反编译: 实际等于
     * EndermanEntity dataTracker ANGRY, 客户端同步, 与林子任务同口径不新写)。
     */
    private EndermanEntity nearestMatching(AltoClef mod, List<EndermanEntity> list, UUID excludeId, boolean angryOnly) {
        EndermanEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EndermanEntity e : list) {
            if (e == null || !e.isAlive()) continue;
            if (excludeId != null && excludeId.equals(e.getUuid())) continue;
            if (!_shouldKillStore.test(e)) continue;
            if (CombatMemory.isUnreachable(e.getUuid())) continue;
            if (angryOnly && !EntityHelper.isGenerallyHostileToPlayer(mod, e)) continue;
            double d = e.squaredDistanceTo(mod.getPlayer());
            if (d < bestDistSq) {
                bestDistSq = d;
                best = e;
            }
        }
        return best;
    }

    @Override
    protected Task getWanderTask(AltoClef mod) {
        if (_wanderTask == null) {
            _wanderTask = new TimeoutWanderTask(WANDER_DISTANCE);
        }
        return _wanderTask;
    }

    @Override
    protected String toDebugString() {
        return _endermanHunt ? "Killing hostiles (enderman single-lock)" : "Killing hostiles";
    }
}
