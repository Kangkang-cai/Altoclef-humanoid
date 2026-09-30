package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

public class GetToBlockTask extends CustomBaritoneGoalTask implements ITaskRequiresGrounded {

    private final BlockPos _position;
    private final boolean _preferStairs;
    private final Dimension _dimension;
    // (2026-09-30 方案A) 严格到达: 必须真正站进目标格 (禁用父类"≤2 格 holding"
    // 豁免)。step-out 让位场景必开, 否则 1.4 格外停走会让位永不完成。
    private final boolean _strictCellArrivalFlag;

    public GetToBlockTask(BlockPos position, boolean preferStairs) {
        this(position, preferStairs, null);
    }
    public GetToBlockTask(BlockPos position, Dimension dimension) {
        this(position, false, dimension);
    }

    public GetToBlockTask(BlockPos position, boolean preferStairs, Dimension dimension) {
        this(position, preferStairs, dimension, false);
    }

    /** (2026-09-30 方案A) strictCellArrival=true 时启用严格到达 (见字段注释)。 */
    public GetToBlockTask(BlockPos position, boolean preferStairs, boolean strictCellArrival) {
        this(position, preferStairs, null, strictCellArrival);
    }

    public GetToBlockTask(BlockPos position, boolean preferStairs, Dimension dimension, boolean strictCellArrival) {
        _dimension = dimension;
        _position = position;
        _preferStairs = preferStairs;
        _strictCellArrivalFlag = strictCellArrival;
        _strictCellArrival = strictCellArrival; // 父类开关
    }

    public GetToBlockTask(BlockPos position) {
        this(position, false);
    }
    @Override
    protected Task onTick(AltoClef mod) {
        if (_dimension != null && WorldHelper.getCurrentDimension() != _dimension) {
            return new DefaultGoToDimensionTask(_dimension);
        }
        // (2026-08-23 区块外导航) 目标方块在未加载 chunk 时, baritone 无法
        // 计算路径 (目标方块未知/不可达) → 寻路失败 → 标记 unreachable →
        // 随机 wander, 永远到不了记忆中的远处传送门/床/资源。修复: 先
        // GetToChunkTask 走到目标所在 chunk, 玩家靠近时 chunk 自动加载,
        // 之后回到 GoalBlock 正常寻路。用户确认: "记着的坐标在区块外还能
        // 正确导航吗" — 原实现不能, 此修复让 bot 分步接近。
        if (!mod.getChunkTracker().isChunkLoaded(_position)) {
            setDebugState("Target chunk not loaded, going to chunk first");
            return new GetToChunkTask(new ChunkPos(_position));
        }
        return super.onTick(mod);
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (_preferStairs) {
            mod.getBehaviour().push();
            mod.getBehaviour().setPreferredStairs(true);
        }
        super.onStart(mod);
    }


    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_preferStairs) {
            mod.getBehaviour().pop();
        }
        super.onStop(mod, interruptTask);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToBlockTask task) {
            return task._position.equals(_position) && task._preferStairs == _preferStairs
                    && task._dimension == _dimension && task._strictCellArrivalFlag == _strictCellArrivalFlag;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return super.isFinished(mod) && (_dimension == null || _dimension == WorldHelper.getCurrentDimension());
    }

    @Override
    protected String toDebugString() {
        return "Getting to block " + _position + (_dimension != null ? " in dimension " + _dimension : "");
    }


    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalBlock(_position);
    }

    @Override
    protected void onWander(AltoClef mod) {
        mod.getBlockTracker().requestBlockUnreachable(_position);
        super.onWander(mod);
    }
}
