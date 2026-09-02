package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.Rotation;
import net.minecraft.util.math.BlockPos;

public class ThrowCursorTask extends Task {

    private final Task _throwTask = new ClickSlotTask(Slot.UNDEFINED);

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-08-27 防止丢垃圾掉脚边无限循环, 用户实测) 丢物品前先把视角转成
        // 水平、朝向最空旷的方向, 让被扔的物品有水平初速度飞出去, 而不是竖直掉
        // 回脚下又被满背包立刻捡回 (旧版未控制视角: 相机朝天时垃圾垂直落回脚边,
        // 前方两侧又有方块 → 一直扔一直捡, 背包永远满, 用户人工干预才脱身)。
        // 物品掉落方向由玩家当前朝向决定, 这里主动瞄准"3 格内实心块最少"的方向。
        steerLookAwayFromWalls(mod);
        return _throwTask;
    }

    // 找一个水平 45° 递增的朝向里, 前方(1~3 格, 站脚层 + 头顶 1 层)实心块最少的
    // 那个方向, 把玩家视角水平对准它。pitch 放 0(正前方), 物品会带水平初速度。
    private void steerLookAwayFromWalls(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return;
        BlockPos p = mod.getPlayer().getBlockPos();
        double bestScore = Double.NEGATIVE_INFINITY;
        float bestYaw = mod.getPlayer().getYaw();
        // 8 个水平方向, yaw 0 = -Z(北), 逆时针为正, 这里转成朝向并测空旷度。
        for (int i = 0; i < 8; ++i) {
            double rad = Math.PI * 2.0 * i / 8.0;
            float yaw = (float) (Math.toDegrees(Math.PI / 4.0 * i));
            int dx = (int) Math.round(Math.sin(rad));
            int dz = -(int) Math.round(Math.cos(rad));
            double score = 0;
            for (int d = 1; d <= 3; ++d) {
                BlockPos a = p.add(dx * d, 0, dz * d);   // 站脚层
                BlockPos b = p.add(dx * d, 1, dz * d);   // 头顶 1 层
                if (!WorldHelper.isSolid(mod, a)) score += 1;
                if (!WorldHelper.isSolid(mod, b)) score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                bestYaw = yaw;
            }
        }
        try {
            LookHelper.lookAt(mod, new Rotation(bestYaw, 0));
        } catch (Exception ignored) {
            // 设置视角失败也不阻断丢东西。
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof ThrowCursorTask;
    }

    @Override
    protected String toDebugString() {
        return "Throwing Cursor";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _throwTask.isFinished(mod);
    }
}
