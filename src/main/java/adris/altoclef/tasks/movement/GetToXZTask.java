package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.util.math.BlockPos;

public class GetToXZTask extends CustomBaritoneGoalTask {

    private final int _x, _z;
    private final Dimension _dimension;
    // (2026-10-22 赶路带目的) 与 GetToBlockTask 同款：@goto 命令裸派本任务时
    // 栈上无语义父任务，目的由派发方注入（HUD/对端摘要读）。
    private String _navPurpose = null;

    public GetToXZTask(int x, int z) {
        this(x, z, null);
    }
    public GetToXZTask(int x, int z, Dimension dimension) {
        _x = x;
        _z = z;
        _dimension = dimension;
    }

    /** (2026-10-22 赶路带目的) 链式注入目的；不进 isEqual（纯元数据）。 */
    public GetToXZTask setNavPurpose(String purpose) {
        _navPurpose = purpose;
        return this;
    }

    /** (2026-10-22 赶路带目的) 只读访问；null = 未注入。 */
    public String getNavPurpose() {
        return _navPurpose;
    }
    @Override
    protected Task onTick(AltoClef mod) {
        if (_dimension != null && WorldHelper.getCurrentDimension() != _dimension) {
            return new DefaultGoToDimensionTask(_dimension);
        }
        return super.onTick(mod);
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalXZ(_x, _z);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToXZTask task) {
            return task._x == _x && task._z == _z && task._dimension == _dimension;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        BlockPos cur = mod.getPlayer().getBlockPos();
        return (cur.getX() == _x && cur.getZ() == _z && (_dimension == null || _dimension == WorldHelper.getCurrentDimension()));
    }

    @Override
    protected String toDebugString() {
        return "Getting to (" + _x + "," + _z + ")" + (_dimension != null ? " in dimension " + _dimension : "")
                + (_navPurpose != null ? "【" + _navPurpose + "】" : "");
    }
}
