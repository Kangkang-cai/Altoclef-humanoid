package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-09-01 用户"丢东西前先转向空旷, 然后再丢") 丢东西前的独立小动作:
 * 扫描 8 个水平方向, 选"前方 1~3 格实心最少"的开阔方向, 直接 setYaw/setPitch
 * 转向它, 等视角稳定后完成 — 让调用方(如 ThrowCursorTask)在真正丢出前先面朝
 * 开阔处。Q 键丢弃(dropSelectedItem)用 player.getYaw() 算朝向初速度, 转向必须
 * 真正生效才有意义, 故此处用【直接 setYaw】(baritone RotationMoveEvent 在
 * 背包/容器界面打开时不生效, 用户实测视角从不转动)。
 */
public class TurnToOpenAreaTask extends Task {

    // 转向发出后等几 tick 让视角稳定, 再宣告完成 (Q 键丢会读当前朝向)。
    private static final int STABLE_TICKS = 3;
    private int _ticks = 0;

    @Override
    protected void onStart(AltoClef mod) {
        _ticks = 0;
        steerLookAwayFromWalls(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (++_ticks >= STABLE_TICKS) {
            return null; // 完成, 交还调用方去丢
        }
        return null;
    }

    // 找一个水平 45° 递增的朝向里, 前方(1~3 格, 站脚层 + 头顶 1 层)实心块最少的
    // 那个方向, 直接 setYaw/setPitch。平手时保持当前朝向(避免无谓转身)。
    private void steerLookAwayFromWalls(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return;
        BlockPos p = mod.getPlayer().getBlockPos();
        float currentYaw = mod.getPlayer().getYaw();
        double bestScore = scoreDirection(mod, p, currentYaw);
        float bestYaw = currentYaw;
        for (int i = 0; i < 8; ++i) {
            double rad = Math.PI * 2.0 * i / 8.0;
            float yaw = (float) (Math.toDegrees(Math.PI / 4.0 * i));
            double score = scoreDirection(mod, p, yaw);
            if (score > bestScore) {
                bestScore = score;
                bestYaw = yaw;
            }
        }
        try {
            mod.getPlayer().setYaw(bestYaw);
            mod.getPlayer().setPitch(0);
        } catch (Exception ignored) {
            // 设置视角失败也不阻断后续丢东西。
        }
    }

    private double scoreDirection(AltoClef mod, BlockPos p, float yaw) {
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(Math.sin(rad));
        int dz = -(int) Math.round(Math.cos(rad));
        double score = 0;
        for (int d = 1; d <= 3; ++d) {
            BlockPos a = p.add(dx * d, 0, dz * d);
            BlockPos b = p.add(dx * d, 1, dz * d);
            if (!WorldHelper.isSolid(mod, a)) score += 1;
            if (!WorldHelper.isSolid(mod, b)) score += 1;
        }
        return score;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TurnToOpenAreaTask;
    }

    @Override
    protected String toDebugString() {
        return "Turning to open area";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _ticks >= STABLE_TICKS;
    }
}
