package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import adris.altoclef.util.helpers.BaritoneHelper;
import baritone.api.pathing.goals.Goal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.SkeletonEntity;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs away from hostiles in short hops instead of one big A* run.
 *
 * Pathing straight to a far-away escape point is expensive: baritone has to
 * explore a huge area every time. Here we first target a small distance, and
 * the moment we're that far away we re-target further, ratcheting up to the
 * maximum. Escapes become more responsive, and we never pay for the full
 * 30-block search unless we actually have to.
 */
public class ProgressiveRunAwayTask extends CustomBaritoneGoalTask {

    private final double _maxDistance;
    private final double _step;
    private final boolean _includeSkeletons;
    private double _currentDistance;

    // ---- [RETREAT-FIX] (2026-09-01 用户) 复位开关与诊断 ----
    // _currentDistance 是 ratchet 的"当前逃跑目标距离"(步进式, 每次达成就 +step),
    // 而 isEqual 之前只比 _maxDistance/_step/_includeSkeletons, 导致新建的逃跑任务
    // 被 TaskRunner 视为与"上次残余 _currentDistance≈45 的旧实例"相同而【复用旧实例】
    // —— 于是同一只怪第二次触发逃跑时从残余进度起算, 只跑几格就判定完成/停止。
    // 现在: 1) isEqual 纳入 _currentDistance, 新任务不会被残余实例顶替;
    //       2) 每次【新触发】(新实例)复位起点 = 当前离最近威胁怪的实际距离,
    //          真正把怪拉开到阈值, 而非沿用上次跑过距离。
    private boolean _pendingStartReset = true;
    private static long _lastRetreatFixLog = 0;

    // ===== (2026-09-23 逃跑假完成修复) =====
    // 事故 09:54: 骷髅+僵尸追击时逃跑任务 1.2 秒即报 FINISHED。机制: 威胁怪从
    // entityTracker 短暂离池 (一帧追踪抖动) 时, GoalRunAwayFromEntities.isInGoal
    // 对【空实体列表】真空返回 true, onTick 的 ratchet 便在 6 tick 内把
    // 8→16→24→32→40→45 跳满, isFinished 立即真。
    // 契约修正: 威胁池为空时【既不 ratchet 也不完成】; 必须持续无威胁满
    // NO_THREAT_CONFIRM_TICKS 才认定怪真的脱离/死亡。窗口 2s 短于父类
    // CustomBaritoneGoalTask 的 3s 卡住检查, 不会误触 wander。共用 Goal 的
    // 语义不动 (苦力怕爆炸后即走等简单逃跑仍用旧契约), 影响面仅限本任务。
    private int _noThreatTicks;
    private static final int NO_THREAT_CONFIRM_TICKS = 40;

    public ProgressiveRunAwayTask(double maxDistance, double step, boolean includeSkeletons) {
        super(true);
        _maxDistance = maxDistance;
        _step = step;
        _includeSkeletons = includeSkeletons;
        _currentDistance = step;
    }

    public double getMaxDistance() {
        return _maxDistance;
    }

    public double getStep() {
        return _step;
    }

    public boolean isIncludeSkeletons() {
        return _includeSkeletons;
    }

    /** Curret ratcheted escape target distance (read-only, for diagnostics). */
    public double getCurrentDistance() {
        return _currentDistance;
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        // (2026-09-15 用户) 逃跑禁止挖墙: 上局被激怒猪灵追击时 baritone 边跑边
        // 挖墙, 挖出的洞被猪灵跟进 → 围殴致死。跑路只走现成通道 (allowBreak=false),
        // allowPlace 保持放开 (跳格垫块属防守性动作)。onStop 恢复, 不影响挖掘类任务。
        mod.getClientBaritoneSettings().allowBreak.value = false;
        _noThreatTicks = 0;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritoneSettings().allowBreak.value = true;
        super.onStop(mod, interruptTask);
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // (2026-09-01 用户) 新触发的逃跑统一复位起点 = 当前离最近威胁怪的实际距离,
        // 而不是沿用上次 ratchet 的残余 _currentDistance。仅"新实例"生效一次(pendingReset),
        // 续跑过程中 _pendingStartReset 已置 false, 不会每格反复重算误伤进度。
        if (_pendingStartReset) {
            _pendingStartReset = false;
            double nearest = nearestThreatDistance(mod);
            double old = _currentDistance;
            // 起点 = max(step, 当前离怪距离) 并封顶 maxDistance (怪已在更远则不再拉回)。
            _currentDistance = Math.min(_maxDistance, Math.max(_step, nearest));
            long nowMs = System.currentTimeMillis();
            if (nowMs - _lastRetreatFixLog > 1000) {
                _lastRetreatFixLog = nowMs;
                double remaining = Math.max(0, _maxDistance - _currentDistance);
                String m = "[RETREAT-FIX] escape start reset: startDist=" + String.format("%.1f", _currentDistance)
                        + " (oldResidual=" + String.format("%.1f", old) + ")"
                        + " targetDist=" + String.format("%.1f", _maxDistance)
                        + " remainRun=" + String.format("%.1f", remaining)
                        + " nearestThreat=" + String.format("%.1f", nearest);
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
        }
        // We want to run away NOW.
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return new GoalRunAwayFromHostiles(mod, _currentDistance);
    }

    /** 当前离最近存活威胁怪的水平距离; 附近无威胁怪时回退到 _step。 */
    private double nearestThreatDistance(AltoClef mod) {
        double best = Double.MAX_VALUE;
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            for (Entity e : mod.getEntityTracker().getHostiles()) {
                if (e == null || !e.isAlive()) continue;
                double d = Math.sqrt(e.squaredDistanceTo(mod.getPlayer()));
                if (d < best) best = d;
            }
        }
        return best == Double.MAX_VALUE ? _step : best;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // 每 tick 取一次威胁快照并维护"持续无威胁"计数 (isFinished 只读此状态,
        // 避免它在一 tick 内被多处调用导致计数虚增)。
        List<Entity> threats = collectThreats(mod);
        if (threats.isEmpty()) {
            _noThreatTicks++;
            if (_noThreatTicks == NO_THREAT_CONFIRM_TICKS) {
                String m = "[RETREAT-FIX] escape confirmed safe: no live threats for "
                        + (NO_THREAT_CONFIRM_TICKS / 20) + "s";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
        } else {
            _noThreatTicks = 0;
        }
        // We've reached the current milestone: extend the escape distance
        // further instead of declaring the escape over.
        // 威胁池为空时绝不 ratchet: 空列表下 goal.isInGoal 是真空 true, 不代表
        // 真的拉开了距离; 怪真脱离由 isFinished 的持续确认收工。
        if (_cachedGoal != null && !threats.isEmpty()
                && _cachedGoal.isInGoal(mod.getPlayer().getBlockPos())
                && _currentDistance < _maxDistance) {
            _currentDistance = Math.min(_maxDistance, _currentDistance + _step);
            _cachedGoal = null;
            _checker.reset();
            resetFailCount();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            setDebugState("Escaping, extending distance to " + (int) _currentDistance);
            return null;
        }
        return super.onTick(mod);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_cachedGoal == null) {
            _cachedGoal = newGoal(mod);
        }
        // 最近 tick 威胁池为空: 必须持续确认满窗口才算真安全 (防 tracker 抖动
        // 假完成); 威胁仍在时只有 ratchet 到最大距离且当下确实远离才收工。
        if (_noThreatTicks > 0) {
            return _noThreatTicks >= NO_THREAT_CONFIRM_TICKS;
        }
        return _currentDistance >= _maxDistance && _cachedGoal.isInGoal(mod.getPlayer().getBlockPos());
    }

    /**
     * 当前存活威胁快照; 口径与逃跑 Goal 的 getEntities 完全一致 —— 敌对生物,
     * _includeSkeletons=false 时排除骷髅。单一谓词, 不让 ratchet 判定和 goal
     * 判定各写一份过滤逻辑。
     */
    private List<Entity> collectThreats(AltoClef mod) {
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            Stream<Entity> stream = mod.getEntityTracker().getHostiles().stream();
            if (!_includeSkeletons) {
                stream = stream.filter(hostile -> !(hostile instanceof SkeletonEntity));
            }
            return stream.filter(e -> e != null && e.isAlive()).collect(Collectors.toList());
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ProgressiveRunAwayTask task) {
            // (2026-09-01 用户) 必须连 _currentDistance 一起比, 否则新建的复位
            // 逃跑任务会被 TaskRunner 视为与"残余进度旧实例"相同而复用旧实例,
            // 导致第二次逃跑从上次跑过的距离起算(只跑几格就完成)。同实例 self-equality
            // (MobDefense 每 tick setTask 同一个 _runAwayTask) 不受影响。
            return Math.abs(task._maxDistance - _maxDistance) < 1
                    && Math.abs(task._step - _step) < 1
                    && Math.abs(task._currentDistance - _currentDistance) < 1
                    && task._includeSkeletons == _includeSkeletons;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Escaping from hostiles (" + (int) _currentDistance + "/" + (int) _maxDistance + " blocks)";
    }

    private class GoalRunAwayFromHostiles extends GoalRunAwayFromEntities {

        public GoalRunAwayFromHostiles(AltoClef mod, double distance) {
            super(mod, distance, false, 0.8);
        }

        @Override
        protected List<Entity> getEntities(AltoClef mod) {
            // 与 ratchet/isFinished 共用同一份威胁快照口径, 不再各写一份过滤。
            return collectThreats(mod);
        }
    }
}
