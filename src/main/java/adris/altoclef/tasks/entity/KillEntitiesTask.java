package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.entity.Entity;

import java.util.Collections;
import java.util.List;
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
        return true;
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
        return "Killing hostiles";
    }
}
