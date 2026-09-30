package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalChunk;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import net.minecraft.util.math.ChunkPos;

public class GetToChunkTask extends CustomBaritoneGoalTask {

    private final ChunkPos _pos;

    public GetToChunkTask(ChunkPos pos) {
        // Override checker to be more lenient, as we are traversing entire chunks here.
        _checker = new MovementProgressChecker(20, 0.5, 5, 0.001, 3);
        _pos = pos;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalChunk(_pos);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToChunkTask task) {
            return task._pos.equals(_pos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Get to chunk: " + _pos.toString();
    }

    @Override
    protected void onWander(AltoClef mod) {
        // (2026-09-30 朝目标直进) 区块未加载导致寻路失败时, 基类 wander 默认随机
        // 朝向 (20260930-084411 实锤: Get to chunk [1,12] → Wander bearing=1 随机,
        // 40s 零接近)。每次失速把 wander 朝向锁到目标 chunk 中心方向 —— 至少单调
        // 接近, 配合区块流式加载尽快拿到真路径。bearing 数学坐标系 x=cos/z=sin,
        // 与 TimeoutWanderTask 的 GoalXZ 换算同源。
        if (mod.getPlayer() != null) {
            double dx = (_pos.getStartX() + 8) - mod.getPlayer().getX();
            double dz = (_pos.getStartZ() + 8) - mod.getPlayer().getZ();
            if (dx * dx + dz * dz > 1.0) {
                _wanderTask.setBearingDeg(Math.toDegrees(Math.atan2(dz, dx)));
            }
        }
    }
}
