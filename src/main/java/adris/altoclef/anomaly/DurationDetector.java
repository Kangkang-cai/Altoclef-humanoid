package adris.altoclef.anomaly;

import adris.altoclef.tasksystem.Task;

/**
 * (2026-09-11 异常看门狗, P1) 时长异常检测。
 *
 * 判据: 任务栈上某个任务实例**连续**运行的时长超过了它所属任务类的自适应上限。
 *
 * 上限来自 {@link TaskDurationTracker} 的历史分布 —— {@code max(p90 × 3, 30s)}。
 * 用自适应而不是硬编码期望表的原因: 同一个任务类在不同阶段的耗时能差一个数量级
 * (挖第一块木头 vs 挖第 200 块), 硬编码表要么天天误报, 要么形同虚设。
 *
 * 冷启动行为: 样本不足 (小于 8 次) 时不给出阈值, 本检测器保持沉默 —— 只记录,
 * 不判定。也就是说这个检测器上线后需要先"学"一阵子才开始工作。
 *
 * 为什么看"连续"时长: 任务被更高优先级链抢占 (战斗 / 逃生) 后会重新 START,
 * 计时归零。被打断是有正当理由的, 不该算进"卡住"; 真正卡死的任务是连续跑很久。
 */
public final class DurationDetector implements AnomalyDetector {

    /** 时长是慢变量, 1 秒检查一次即可, 不必每 tick 算。 */
    private static final long CHECK_INTERVAL_MS = 1_000;

    private long _lastCheckMs = 0;

    @Override
    public String name() {
        return "duration";
    }

    @Override
    public void reset() {
        _lastCheckMs = 0;
    }

    @Override
    public Anomaly check(AnomalyContext ctx) {
        if (!ctx.inGame() || ctx.paused()) return null;

        long now = System.currentTimeMillis();
        if (now - _lastCheckMs < CHECK_INTERVAL_MS) return null;
        _lastCheckMs = now;

        Task worst = null;
        long worstElapsed = 0;
        long worstLimit = 0;

        for (Task t : ctx.taskStack()) {
            String cls = t.getClass().getSimpleName();
            long limit = TaskDurationTracker.timeoutThresholdMs(cls);
            if (limit <= 0) continue;                 // 样本不足 -> 不判定
            long elapsed = TaskDurationTracker.elapsedMs(t);
            if (elapsed < 0 || elapsed <= limit) continue;
            if (elapsed > worstElapsed) {
                worst = t;
                worstElapsed = elapsed;
                worstLimit = limit;
            }
        }

        if (worst == null) return null;

        String cls = worst.getClass().getSimpleName();
        String summary = cls + " 已连续运行 " + TaskDurationTracker.formatMs(worstElapsed)
                + ", 超过自适应上限 " + TaskDurationTracker.formatMs(worstLimit)
                + " (p90 x3, n=" + TaskDurationTracker.sampleCount(cls) + ")";

        return new Anomaly("duration", cls, AnomalySeverity.WARN, summary, null);
    }
}
