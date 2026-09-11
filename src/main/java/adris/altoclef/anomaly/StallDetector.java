package adris.altoclef.anomaly;

import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-11 异常看门狗) 原地停滞检测。
 *
 * 判据: 有任务在跑 (winner chain 非 null) 但玩家位置长时间不移动。
 *
 * 白名单 —— 以下"静止"是合法的, 必须直接跳过, 否则第一天就会误报到被人关掉:
 *   - 没有链在跑     → bot 在等指令, 是空闲不是卡住
 *   - 打开了 GUI     → 在翻箱子 / 合成 / 看背包
 *   - 正在睡觉       → 位置本就不变
 *   - 游戏暂停 / 不在游戏中
 *
 * 位移判据用"锚点": 位置偏离锚点超过 MOVE_THRESHOLD 格即视为动过并重置锚点。
 * 之所以不用"坐标精确不变", 是因为卡在方块里时 bot 常常会在 1 格范围内来回微抖,
 * 那种情况同样是卡死, 但精确比较抓不到。
 */
public final class StallDetector implements AnomalyDetector {

    /** 偏离锚点多少格算"动过"。 */
    private static final double MOVE_THRESHOLD = 0.5;
    private static final double MOVE_THRESHOLD_SQ = MOVE_THRESHOLD * MOVE_THRESHOLD;

    /** 静止多久升到 WARN / CRITICAL。 */
    private static final long WARN_MS = 45_000;
    private static final long CRITICAL_MS = 120_000;

    private Vec3d _anchor = null;
    private long _stillSinceMs = 0;

    @Override
    public String name() {
        return "stall";
    }

    @Override
    public void reset() {
        _anchor = null;
        _stillSinceMs = 0;
    }

    @Override
    public Anomaly check(AnomalyContext ctx) {
        if (!ctx.inGame() || ctx.paused() || ctx.playerPos() == null) {
            reset();
            return null;
        }
        if (ctx.chainName() == null || ctx.hasScreen() || ctx.sleeping()) {
            reset();
            return null;
        }

        long now = System.currentTimeMillis();
        Vec3d pos = ctx.playerPos();
        if (_anchor == null || pos.squaredDistanceTo(_anchor) > MOVE_THRESHOLD_SQ) {
            _anchor = pos;
            _stillSinceMs = now;
            return null;
        }

        long stillMs = now - _stillSinceMs;
        if (stillMs < WARN_MS) return null;

        AnomalySeverity severity = stillMs >= CRITICAL_MS
                ? AnomalySeverity.CRITICAL
                : AnomalySeverity.WARN;

        String summary = "原地停滞 " + (stillMs / 1000) + "s: " + ctx.chainName()
                + " / " + ctx.deepestTaskClass();

        return new Anomaly("stall", ctx.chainName(), severity, summary, null);
    }
}
