package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;


public class ClearRegionTask extends Task implements ITaskRequiresGrounded {

    private final BlockPos _from;
    private final BlockPos _to;

    // TODO: Progress checkers in the event of a failure.
    // Progress checker 1 for movement
    // Progress checker 2 for if block breaking isn't happening
    // Make it an "and", as in both MUST fail for a failure to count.

    public ClearRegionTask(BlockPos from, BlockPos to) {
        _from = from;
        _to = to;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
            mod.getClientBaritone().getBuilderProcess().clearArea(_from, _to);
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getBuilderProcess().onLostControl();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-18 off-by-one 修正) 原循环是 `xx < Math.abs(x)`, 少算一圈 ——
        // 区域在**任一轴只有 1 格宽**时 (Math.abs(轴差)=0) 该轴循环体一次都不执行,
        // 三轴全为 1 格宽 (单格区域) 时直接 return true = 一启动就"已完成"。
        // 本缺陷预先存在 (类自 2021 起如此), 只是从未被 1 格宽区域暴露过 ——
        // 门内清障区域 (1 x 3 x 2, x 轴恰为 1 格宽) 是第一个这种调用方。
        // 改 `<=`: 遍历 0..|差| 共 |差|+1 格, 与 clearArea 的轴对齐盒一致。
        int x = _from.getX() - _to.getX();
        int y = _from.getY() - _to.getY();
        int z = _from.getZ() - _to.getZ();
        for (int xx = 0; xx <= Math.abs(x); ++xx) {
            for (int yy = 0; yy <= Math.abs(y); ++yy) {
                for (int zz = 0; zz <= Math.abs(z); ++zz) {
                    BlockPos toCheck = new BlockPos(_from).add(xx * -Integer.signum(x), yy * -Integer.signum(y), zz * -Integer.signum(z));
                    assert MinecraftClient.getInstance().world != null;
                    if (!MinecraftClient.getInstance().world.isAir(toCheck)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ClearRegionTask) {
            ClearRegionTask task = (ClearRegionTask) other;
            return (task._from.equals(_from) && task._to.equals(_to));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Clear region from " + _from.toShortString() + " to " + _to.toShortString();
    }
}
