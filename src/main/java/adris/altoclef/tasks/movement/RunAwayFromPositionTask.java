package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;

public class RunAwayFromPositionTask extends CustomBaritoneGoalTask {

    private final BlockPos[] _dangerBlocks;
    private final double _distance;
    private final Integer _maintainY;

    public RunAwayFromPositionTask(double distance, BlockPos... toRunAwayFrom) {
        this(distance, null, toRunAwayFrom);
    }

    public RunAwayFromPositionTask(double distance, Integer maintainY, BlockPos... toRunAwayFrom) {
        _distance = distance;
        _dangerBlocks = toRunAwayFrom;
        _maintainY = maintainY;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // (2026-09-21 210114 死锁实锤) GoalRunAway 半径 +1: baritone isInGoal 用
        // 【BlockPos 整数】口径 (dx²+dz² >= range² 达标即站桩), 而调用方的"还没逃够"
        // 判据 (inRangeXZ) 用【Vec3d 方块中心】口径 (<range² 才派) —— 两个 30 差
        // 最多 ~0.7 格, bot 站进边界带 (blockPos 901>=900 达标 / vec3d 899.5<900
        // 没达标) 时: 上层每 tick 重派同一 RunAway (isEqual 保活), baritone 每次
        // 都认为已达标不动 → 站桩死锁 (210114 会话 18s "不搭高" + 龙息逃跑 5s 站桩
        // 被火球打死, 均此机制)。+1 让 baritone 多推 1 格, 派发条件必翻 false,
        // 上层自然推进到下一分支 (垫高等)。
        return new GoalRunAway(_distance + 1, _maintainY, _dangerBlocks);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromPositionTask task) {
            return Arrays.equals(task._dangerBlocks, _dangerBlocks);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Running away from " + Arrays.toString(_dangerBlocks);
    }
}
