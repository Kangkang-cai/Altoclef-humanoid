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

    // (2026-09-03 主动挖开堆头用) 记录本次转向选中的最佳方向与评分, 供调用方
    // (ThrowCursorTask) 判断四周是否封闭: 若最佳评分仍 <= cleared(前方紧贴实心) 则
    // 说明四周无开阔处, 应主动挖开该方向的可挖方块打通空间, 而不是把物品丢到贴脸
    // 处被满背包立刻捡回。
    private float _bestYaw = 0;
    private int _bestScore = 0;

    // 该门槛: 最佳方向前方本层与头顶层连通非实心格数取短者, 达到该值才视为"开阔"。
    // (2026-09-03 用户"丢出撞到上面方块, 认为的开阔还不够开阔": 旧值 2 太宽容 —
    // 判定时两层纵深只到 1~2 格就被当开阔, 物品飞出碰到前方/头顶 2 格外的方块。
    // 丢弃需要物品真正飞离抓取范围, 前方脚层与头顶层都必须有足够纵深。提高为 3。
    // 注意与 ThrowCursorTask.MAX_DIG=4 配套: 判定为封闭时开挖能打通到 ≥3 格。
    // (2026-09-11 再提为 4, 现场 11:12:53 实锤: 门槛 3 时首个实心块在 4 格外仍
    // 判"开阔", 物品飞 3-4 格撞墙反弹落点 1-2 格被原版拾取吸回 → 扔/捡死循环。
    // 4 格纵深下物品落点 ≥3 格, 超出原版拾取半径; 不足时由 ThrowCursorTask 熔断器
    // (先离落点/60s 冷却) 兜底。)
    public static final int OPEN_SCORE_THRESHOLD = 4;
    public boolean isEnclosed() {
        return _bestScore < OPEN_SCORE_THRESHOLD;
    }
    public float getBestYaw() {
        return _bestYaw;
    }

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
        _bestYaw = bestYaw;
        _bestScore = (int) bestScore;
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
