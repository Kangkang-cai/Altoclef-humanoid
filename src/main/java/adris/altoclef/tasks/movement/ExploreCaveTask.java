package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * (2026-09-01 真正走进矿洞, 用户"导航探索所谓的矿洞空气你也没实现")
 *
 * 旧实现复用 BiasedExploreTask: bias 只提供一个方向角, 真正的落点沿该方向
 * 48 格外、且 candY 被强制抬到【地表高度】 — "进洞探索"实际执行的是
 * "朝洞的方向走到 48 格外的地表", 从未在洞里走。
 *
 * 本任务直接在洞内导航: baritone GoalNear 直奔【离探索起点最远的天然空气】
 * (排除 MineDownExploreTask 静态记录的自挖块, 防"自己挖的隧道网络"被当成洞;
 * 排除 visited 与 failed)。无天然空气可达(洞探索完/根本不是洞)时立即结束。
 *
 * (2026-09-01 策略修正, 用户"随机探索原地打转") 旧版"从玩家当前位置取最远"
 * 在 collectConnectedAir(±8格)的小窗口内来回翻转 → 原地绕圈。新策略:
 *   - 距离参考点固定为探索起点(_exploreStart): 每个新目标都朝"离入口更远"
 *     的方向推, 一致向外、不左右横跳;
 *   - _visited 记录已到达的目标及其邻域: 到过的区域绝不回头 (单调推进);
 *   - 到达即 markVisited → 下一次必然选更靠外的未访问空气。
 * 结果: 从入口开始做一次连贯的"向外扫掠", 覆盖整个连通洞穴后自然结束。
 *
 * 定位: 挖矿主线【内部的有界支线】, 不设独立优先级、不抢主线 — 由
 * MineDownExploreTask 的 90s 看门狗兜底, 超时即回挖矿。
 */
public class ExploreCaveTask extends Task implements ITaskRequiresGrounded {

    // 到达当前导航目标的判定距离(格)。
    private static final double ARRIVED_DISTANCE = 2.5;
    // 到达目标后标记"已探索"的邻域半径(格)。覆盖目标周边一小片, 防止
    // 在小空间里反复选回刚离开的位置。
    private static final int VISITED_RADIUS = 3;

    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    // 已到达/已探索的天然空气, 绝不回头。
    private final Set<BlockPos> _visited = new HashSet<>();
    // 导航失败(不可达/无进展)的目标, 不再重复选取。
    private final Set<BlockPos> _failedTargets = new HashSet<>();
    // 探索起点: 距离参考点, 所有目标都朝"离它更远"的方向选。
    private BlockPos _exploreStart;
    private BlockPos _currentTarget;
    private boolean _done = false;
    // (2026-09-02 目标深度) 钻石任务等深挖目标下, 探索导航只选目标深度附近的
    // 天然空气 (maxY = targetY + CAVE_ABOVE_TARGET_BUFFER), 防止在洞里向外推时
    // 把目标选到上方浅层、离开钻石层 (用户"下挖到合法深度却导航到上方矿洞")。
    private final int _targetY;

    public ExploreCaveTask() {
        this(10);
    }

    public ExploreCaveTask(int targetY) {
        _targetY = targetY;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _progressChecker.reset();
        _visited.clear();
        _failedTargets.clear();
        _exploreStart = mod.getPlayer().getBlockPos();
        _currentTarget = null;
        _done = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_done) return null;
        BlockPos center = mod.getPlayer().getBlockPos();

        // 到达当前目标: 标记已探索, 选下一个更靠外的目标。
        if (_currentTarget != null && arrived(mod)) {
            markVisited(_currentTarget);
            _currentTarget = null;
        }

        // 无目标: 选离探索起点最远的未访问天然空气(一致向外推)。
        if (_currentTarget == null) {
            _currentTarget = pickNext(mod, center);
            if (_currentTarget == null) {
                // 没有未访问的天然空气可达: 洞已探索完 — 结束, 回挖矿。
                Debug.logToFile("altoclef_diag.log",
                        "[CaveExplore] no unvisited natural cave air, cave exploration done. player="
                                + center.toShortString() + " visited=" + _visited.size());
                _done = true;
                return null;
            }
            _progressChecker.reset();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
            setDebugState("Walking into cave toward " + _currentTarget.toShortString());
            return null;
        }

        // 无进展(不可达): 换下一个目标。
        if (!_progressChecker.check(mod)) {
            Debug.logMessage("[CaveExplore] no progress toward " + _currentTarget.toShortString()
                    + ", picking a different cave air target.");
            _failedTargets.add(_currentTarget);
            _currentTarget = null;
            return null;
        }

        // baritone 空闲(路径走完/被打断)时重新下目标。
        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
        }
        setDebugState("Exploring cave interior (toward " + _currentTarget.toShortString() + ")");
        return null;
    }

    private BlockPos pickNext(AltoClef mod, BlockPos center) {
        Set<BlockPos> exclude = new HashSet<>(_visited);
        exclude.addAll(_failedTargets);
        return MineDownExploreTask.pickFarthestNaturalCaveAir(mod, center, _exploreStart, exclude,
                _targetY + MineDownExploreTask.CAVE_ABOVE_TARGET_BUFFER);
    }

    /**
     * 标记目标及其 VISITED_RADIUS 邻域为已探索, 防止回到刚离开的区域。
     */
    private void markVisited(BlockPos target) {
        for (int dx = -VISITED_RADIUS; dx <= VISITED_RADIUS; dx++) {
            for (int dy = -VISITED_RADIUS; dy <= VISITED_RADIUS; dy++) {
                for (int dz = -VISITED_RADIUS; dz <= VISITED_RADIUS; dz++) {
                    _visited.add(target.add(dx, dy, dz));
                }
            }
        }
    }

    private boolean arrived(AltoClef mod) {
        return mod.getPlayer().getBlockPos().isWithinDistance(_currentTarget, ARRIVED_DISTANCE);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 父任务(MineDownExploreTask)依赖本判定释放支线: 没有未访问天然空气即完成。
        return _done;
    }

    @Override
    protected boolean isEqual(Task other) {
        // 身份比较: 父任务每次进洞创建新实例, 框架必须真正换入新任务。
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Exploring cave interior";
    }
}
