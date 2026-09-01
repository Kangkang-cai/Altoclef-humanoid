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
 * 本任务直接在洞内导航: baritone GoalNear 直奔玩家当前连通空间里【最远的
 * 天然空气】(排除 MineDownExploreTask 静态记录的自挖块, 防"自己挖的隧道
 * 网络"被当成洞); 到达后重扫, 目标随玩家推进不断指向洞穴更深处。没有天然
 * 空气可达(洞探索完/根本不是洞)时立即结束, 交还父任务继续挖矿。
 *
 * 定位: 挖矿主线【内部的有界支线】, 不设独立优先级、不抢主线 — 由
 * MineDownExploreTask 的 25s 看门狗兜底, 超时即回挖矿。
 */
public class ExploreCaveTask extends Task implements ITaskRequiresGrounded {

    // 到达当前导航目标的判定距离(格)。
    private static final double ARRIVED_DISTANCE = 2.5;

    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    // 导航失败(不可达/无进展)的目标, 不再重复选取。
    private final Set<BlockPos> _failedTargets = new HashSet<>();
    private BlockPos _currentTarget;
    private boolean _done = false;

    @Override
    protected void onStart(AltoClef mod) {
        _progressChecker.reset();
        _failedTargets.clear();
        _currentTarget = null;
        _done = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_done) return null;
        BlockPos center = mod.getPlayer().getBlockPos();

        // 无目标 / 已到达当前目标: 重扫并选取最远的天然洞空气(跟深)。
        if (_currentTarget == null || arrived(mod)) {
            _currentTarget = MineDownExploreTask.pickFarthestNaturalCaveAir(mod, center, _failedTargets);
            if (_currentTarget == null) {
                // 没有可达的天然空气: 洞已探索完(或触发条件消失) — 结束, 回挖矿。
                Debug.logToFile("altoclef_diag.log",
                        "[CaveExplore] no natural cave air reachable, cave exploration done. player="
                                + center.toShortString());
                _done = true;
                return null;
            }
            _progressChecker.reset();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
            setDebugState("Walking into cave toward " + _currentTarget.toShortString());
            return null;
        }

        // 无进展(不可达): 换下一个天然空气目标。
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

    private boolean arrived(AltoClef mod) {
        return mod.getPlayer().getBlockPos().isWithinDistance(_currentTarget, ARRIVED_DISTANCE);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 父任务(MineDownExploreTask)依赖本判定释放支线: 没有可达天然空气即完成。
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
