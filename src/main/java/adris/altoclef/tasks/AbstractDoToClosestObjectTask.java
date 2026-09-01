package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Optional;

/**
 * Use this whenever you want to travel to a target position that may change.
 * <p>
 * https://www.notion.so/Closest-threshold-ing-system-utility-c3816b880402494ba9209c9f9b62b8bf
 */
public abstract class AbstractDoToClosestObjectTask<T> extends Task {

    private final HashMap<T, CachedHeuristic> _heuristicMap = new HashMap<>();
    private T _currentlyPursuing = null;
    private boolean _wasWandering;
    private Task _goalTask = null;

    protected abstract Vec3d getPos(AltoClef mod, T obj);

    protected abstract Optional<T> getClosestTo(AltoClef mod, Vec3d pos);

    protected abstract Vec3d getOriginPos(AltoClef mod);

    protected abstract Task getGoalTask(T obj);

    protected abstract boolean isValid(AltoClef mod, T obj);

    // Virtual
    protected Task getWanderTask(AltoClef mod) {
        return new TimeoutWanderTask(true);
    }

    public void resetSearch() {
        _currentlyPursuing = null;
        _heuristicMap.clear();
        _goalTask = null;
    }

    public boolean wasWandering() {
        return _wasWandering;
    }

    private double getCurrentCalculatedHeuristic(AltoClef mod) {
        Optional<Double> ticksRemainingOp = mod.getClientBaritone().getPathingBehavior().ticksRemainingInSegment();
        return ticksRemainingOp.orElse(Double.POSITIVE_INFINITY);
    }

    private boolean isMovingToClosestPos(AltoClef mod) {
        return _goalTask != null;// && _goalTask.isActive() && !_goalTask.isFinished(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {

        _wasWandering = false;

        // Reset our pursuit if our pursuing object no longer is pursuable.
        if (_currentlyPursuing != null && !isValid(mod, _currentlyPursuing)) {
            // This is probably a good idea, no?
            _heuristicMap.remove(_currentlyPursuing);
            _currentlyPursuing = null;
        }

        // Get closest object
        // [SlowTickDebug] getClosestTo runs EVERY tick while this task is active. If
        // the bot "stands still computing", this is a top suspect: it can iterate
        // every cached block position and do a raycast each time.
        final long MS = 1000_000L;
        long tClosest = System.nanoTime();
        Optional<T> checkNewClosest = getClosestTo(mod, getOriginPos(mod));
        long closestMs = (System.nanoTime() - tClosest) / MS;
        if (closestMs > 300) {
            String m = "[SlowTickDebug] getClosestTo took " + closestMs + "ms (this task: " + toDebugString() + ")";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_slowtick.log", m);
        }

        // Receive closest object and position
        if (checkNewClosest.isPresent() && !checkNewClosest.get().equals(_currentlyPursuing)) {
            T newClosest = checkNewClosest.get();
            // Different closest object
            if (_currentlyPursuing == null) {
                // We don't have a closest object
                _currentlyPursuing = newClosest;
            } else {
                if (isMovingToClosestPos(mod)) {
                    setDebugState("Moving towards closest...");
                    double currentHeuristic = getCurrentCalculatedHeuristic(mod);
                    double closestDistanceSqr = getPos(mod, _currentlyPursuing).squaredDistanceTo(mod.getPlayer().getPos());
                    int lastTick = WorldHelper.getTicks();

                    CachedHeuristic h = _heuristicMap.computeIfAbsent(_currentlyPursuing, k -> new CachedHeuristic());
                    // (2026-08-25 接近中不切换, 用户指定) 正在接近当前目标
                    // (当前距离 < 历史最近距离) 时, 即使发现更近的新目标也不
                    // 切换 — 否则挖干草块/小麦等走到一半就换目标, 来回横跳
                    // (用户实测: "距离比较远走到一半就换目标, 距离目标越来越
                    // 近时不应随意切换")。只有没在接近 (远离/停滞, 可能不可达)
                    // 才评估切换到更优目标, 保留原兜底。
                    double prevClosestSqr = h.getClosestDistanceSqr();
                    boolean closingIn = closestDistanceSqr < prevClosestSqr;
                    h.updateHeuristic(currentHeuristic);
                    h.updateDistance(closestDistanceSqr);
                    h.setTickAttempted(lastTick);
                    if (closingIn) {
                        // 正在接近当前目标: 保持不动, 不因新目标打断
                        setDebugState("Closing in on target, keeping pursuit");
                    } else if (_heuristicMap.containsKey(newClosest)) {
                        // Our new object has a past potential heuristic calculated, if it's better try it out.
                        CachedHeuristic maybeReAttempt = _heuristicMap.get(newClosest);
                        double maybeClosestDistance = getPos(mod, newClosest).squaredDistanceTo(mod.getPlayer().getPos());
                        // Get considerably closer (divide distance by 2)
                        if (maybeReAttempt.getHeuristicValue() < h.getHeuristicValue() || maybeClosestDistance < maybeReAttempt.getClosestDistanceSqr() / 4) {
                            setDebugState("Retrying old heuristic!");
                            // The currently closest previously calculated heuristic is better, move towards it!
                            _currentlyPursuing = newClosest;
                            // In theory, this next line shouldn't need to be run,
                            // but it's CRITICAL to making this work for some reason
                            maybeReAttempt.updateDistance(maybeClosestDistance);
                        }
                    } else {
                        // (2026-08-27 修复: pursuit 粘住) 旧代码无条件 "Trying out
                        // NEW pursuit" 切到新目标 — 放末影之眼时多个等价框架距离
                        // 不动 → 每 tick 切目标 → 交互 (InteractWithBlockTask) 每次
                        // 1 tick 就被换掉, 点击永远完不成 ("Waiting for click" +
                        // requestBlockUnreachable → 换下一个 → 死循环, 日志实锤
                        // 6x/5s)。当前 pursuit 仍有效就不随便换, 盯住直到它失效/
                        // 被填/不可达 (isValid 兜底, 上方行 65 重置为 null)。
                        if (isValid(mod, _currentlyPursuing)) {
                            setDebugState("Sticking with current pursuit");
                            // 保持 _currentlyPursuing 不变
                        } else {
                            setDebugState("Trying out NEW pursuit");
                            // Our new object does not have a heuristic, TRY IT OUT!
                            _currentlyPursuing = newClosest;
                        }
                    }
                } else {
                    setDebugState("Waiting for move task to kick in...");
                    // We should keep moving towards our object until we get some new info.
                }
            }
        }

        if (_currentlyPursuing != null) {
            _goalTask = getGoalTask(_currentlyPursuing);
            return _goalTask;
        } else {
            _goalTask = null;
        }

        //noinspection ConstantConditions
        if (checkNewClosest.isEmpty() && _currentlyPursuing == null) {
            setDebugState("Waiting for calculations I think (wandering)");
            _wasWandering = true;
            return getWanderTask(mod);
        }

        setDebugState("Waiting for calculations I think (NOT wandering)");
        return null;
    }

    private static class CachedHeuristic {

        private double _closestDistanceSqr;
        private int _tickAttempted;
        private double _heuristicValue;

        public CachedHeuristic() {
            _closestDistanceSqr = Double.POSITIVE_INFINITY;
            _heuristicValue = Double.POSITIVE_INFINITY;
        }

        public CachedHeuristic(double closestDistanceSqr, int tickAttempted, double heuristicValue) {
            _closestDistanceSqr = closestDistanceSqr;
            _tickAttempted = tickAttempted;
            _heuristicValue = heuristicValue;
        }

        public double getHeuristicValue() {
            return _heuristicValue;
        }

        public void updateHeuristic(double heuristicValue) {
            _heuristicValue = Math.min(_heuristicValue, heuristicValue);
        }

        public double getClosestDistanceSqr() {
            return _closestDistanceSqr;
        }

        public void updateDistance(double closestDistanceSqr) {
            _closestDistanceSqr = Math.min(_closestDistanceSqr, closestDistanceSqr);
        }

        public int getTickAttempted() {
            return _tickAttempted;
        }

        public void setTickAttempted(int tickAttempted) {
            _tickAttempted = tickAttempted;
        }
    }
}
