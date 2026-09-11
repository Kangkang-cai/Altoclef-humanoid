package adris.altoclef.anomaly;

import java.util.List;

/**
 * (2026-09-11 异常看门狗, P2) 空转检测。
 *
 * 判据: **有链持有控制权, 但该链的任务栈是空的** —— 也就是 overlay 上显示
 * "(no task running)" 的那个状态。TaskRunner 里早有对应的
 * {@code [Diag-chain] winner X ticked 0 tasks} 日志, 那是这个项目排查过的真实
 * 故障形态 (某条链抢走控制权后再也不派发任务, bot 就永远杵在那)。
 *
 * 与其它检测器正交:
 *   - {@link StallDetector} 看"位置动没动" —— 空转时位置同样不动, 两者会同时命中,
 *     但空转能给出更精确的结论: 不是"卡在某一步", 而是"根本没有一步可走"。
 *   - {@link DurationDetector} 看"某个任务跑太久" —— 任务栈空的时候它无从下手。
 *
 * 白名单 (以下"没有任务"是合法的):
 *   - 没有链在跑       → bot 在等指令, 空闲不是故障
 *   - 正在睡觉 / 暂停  → 位置与调度都停摆
 *
 * 门槛 10s: 链在一次 tick 内先 setTask 再被读快照, 中间态理论上不该被看到;
 * 连续 10s 都是空栈就只能是真故障, 不是采样抖动。
 */
public final class NoTaskWinnerDetector implements AnomalyDetector {

    /** 空转持续多久才算异常。 */
    private static final long IDLE_MS = 10_000;

    private long _emptySinceMs = 0;

    @Override
    public String name() {
        return "idle";
    }

    @Override
    public void reset() {
        _emptySinceMs = 0;
    }

    @Override
    public Anomaly check(AnomalyContext ctx) {
        if (!ctx.inGame() || ctx.paused() || ctx.sleeping()) {
            _emptySinceMs = 0;
            return null;
        }
        // 没有链在跑 = 真空闲, 不是异常
        if (ctx.chainName() == null) {
            _emptySinceMs = 0;
            return null;
        }
        List<?> stack = ctx.taskStack();
        if (stack != null && !stack.isEmpty()) {
            _emptySinceMs = 0;
            return null;
        }

        long now = System.currentTimeMillis();
        if (_emptySinceMs == 0) {
            _emptySinceMs = now;
            return null;
        }
        if (now - _emptySinceMs < IDLE_MS) return null;

        // 注意: 命中后**不**重置 _emptySinceMs —— 空转是真状态, 会一直成立,
        // 由 watchdog 的冷却表控制重复告警频率 (否则每 10s 重新计一次, 退化成刷屏)。
        String summary = "空转 " + ((now - _emptySinceMs) / 1000) + "s: " + ctx.chainName()
                + " 持有控制权但任务栈为空";
        return new Anomaly("idle", ctx.chainName(), AnomalySeverity.WARN, summary, null);
    }
}
