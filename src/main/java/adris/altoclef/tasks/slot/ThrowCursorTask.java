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

    // (2026-09-01 用户"丢东西没转向开阔处, 东西丢了马上被拾回")
    // LookHelper.lookAt 走 baritone 平滑旋转(带速度上限, 非瞬转)。旧版在同一
    // tick 里设定转向目标后立刻返回抛掷任务 — 视角还没实际转过去, 物品就朝
    // 旧朝向(可能撞墙/垂直落回脚下)被丢出, 又被满背包立刻捡回。现在:
    //   onStart 设定"前方实心最少"的目标朝向; onTick 等玩家【实际转到】目标
    //   (isLookingAt) 才抛掷; 超过 STEER_WAIT_MAX_TICKS 仍未转到位(卡住)也抛,
    //   避免死等。
    private Rotation _steerTarget = null;
    private int _steerWaitTicks = 0;
    private static final int STEER_WAIT_MAX_TICKS = 15; // 0.75s @20tps

    @Override
    protected void onStart(AltoClef mod) {
        _steerWaitTicks = 0;
        _steerTarget = steerLookAwayFromWalls(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_steerTarget != null && !LookHelper.isLookingAt(mod, _steerTarget) && _steerWaitTicks < STEER_WAIT_MAX_TICKS) {
            _steerWaitTicks++;
            return null; // 等 baritone 平滑转到开阔方向, 期间不抛掷
        }
        return _throwTask;
    }

    // 找一个水平 45° 递增的朝向里, 前方(1~3 格, 站脚层 + 头顶 1 层)实心块最少的
    // 那个方向, 把玩家视角水平对准它。pitch 放 0(正前方), 物品会带水平初速度。
    // 平手时保持当前朝向(避免无谓转身)。返回目标 Rotation 供 onTick 等待到位。
    private Rotation steerLookAwayFromWalls(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        float currentYaw = mod.getPlayer().getYaw();
        double bestScore = scoreDirection(mod, p, currentYaw);
        float bestYaw = currentYaw;
        for (int i = 0; i < 8; ++i) {
            float yaw = (float) Math.toDegrees(Math.PI / 4.0 * i);
            double score = scoreDirection(mod, p, yaw);
            if (score > bestScore) {
                bestScore = score;
                bestYaw = yaw;
            }
        }
        Rotation target = new Rotation(bestYaw, 0);
        try {
            LookHelper.lookAt(mod, target);
        } catch (Exception ignored) {
            // 设置视角失败也不阻断丢东西。
        }
        return target;
    }

    // 该朝向水平射线上 1~3 格(站脚层 + 头顶 1 层)的非实心块计数, 越高越开阔。
    private double scoreDirection(AltoClef mod, BlockPos p, float yaw) {
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(Math.sin(rad));
        int dz = -(int) Math.round(Math.cos(rad));
        double score = 0;
        for (int d = 1; d <= 3; ++d) {
            BlockPos a = p.add(dx * d, 0, dz * d);   // 站脚层
            BlockPos b = p.add(dx * d, 1, dz * d);   // 头顶 1 层
            if (!WorldHelper.isSolid(mod, a)) score += 1;
            if (!WorldHelper.isSolid(mod, b)) score += 1;
        }
        return score;
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
