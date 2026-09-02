package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
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

    // 找一个水平 45° 递增的朝向里, "前方连通非实心距离"最长(封顶 MAX_LOOK)的那个
    // 方向, 直接 setYaw/setPitch。站脚层与头顶层都数, 取较短者 — 物品飞行高度
    // 附近都不能被挡。平手时保持当前朝向(避免无谓转身)。
    // (2026-09-01 用户"转向不对/盯着墙扔"): 旧 1~3 格计数在稍紧的空间里各方向
    // 分数接近 → 平手保持当前(朝墙) → 不转向。改为数"直到首个实心块"的连通距离,
    // 真正区分"朝洞内开敞"与"朝墙"。
    private static final int MAX_LOOK = 8;

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
            // 诊断: 确认转向是否真的生效 (setYaw 后 player.getYaw 应等于 bestYaw;
            // 若被 baritone/游戏 tick 覆盖, 这里能看到差值)。
            Debug.logToFile("altoclef_diag.log",
                    String.format("[TurnOpen] yaw %.1f -> %.1f (score %.0f), playerYawNow=%.1f",
                            currentYaw, bestYaw, bestScore, mod.getPlayer().getYaw()));
        } catch (Exception ignored) {
            // 设置视角失败也不阻断后续丢东西。
        }
    }

    // 该方向直到首个实心块的连通非实心格数 (站脚层 / 头顶层取较短者), 封顶 MAX_LOOK。
    // (2026-09-01 方向向量修正, 用户"面向墙丢"根因) MC 朝向约定: yaw 0=南(+Z),
    // 90=西(-X), 前向向量 = (-sin(yaw), 0, cos(yaw)) — 见 PlayerInteractionFixChain
    // /SwingThroughChain/ExploreStrongholdTask 同款算法。旧代码用 (+sin, -cos) 正好
    // 反向: 评分挑的 yaw 其"对面"才是开阔的, bot 面向最不开阔的墙丢东西。
    private double scoreDirection(AltoClef mod, BlockPos p, float yaw) {
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(rad));
        int dz = (int) Math.round(Math.cos(rad));
        int foot = clearLen(mod, p, dx, dz, 0);
        int head = clearLen(mod, p, dx, dz, 1);
        return Math.min(foot, head);
    }

    private int clearLen(AltoClef mod, BlockPos p, int dx, int dz, int dy) {
        for (int d = 1; d <= MAX_LOOK; d++) {
            if (WorldHelper.isSolid(mod, p.add(dx * d, dy, dz * d))) {
                return d - 1;
            }
        }
        return MAX_LOOK;
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
