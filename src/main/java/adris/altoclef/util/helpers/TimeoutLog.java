package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;

/**
 * (2026-09-15 统一超时标签) 所有"超时/无进展判负 → 放弃本任务或换兜底"的
 * 放弃点都打一行 {@link #TAG} 日志, 事后直接 grep diag.log 里的 "[TIMEOUT]"
 * 就能列出"哪些任务被超时逼迫跳过"。
 *
 * 用法: TimeoutLog.hit("站点名", "关键判据值");
 * 约定: 必须边沿触发 (判定从未超时翻到已超时/放弃那一刻) 打一次, 不要每 tick 刷。
 * 日志归属: 自动附上当前 tick 中的任务类名 (Task.getCurrentTickingTask)。
 * 落盘: altoclef_diag.log (会话目录下的 diag.log, 与其他诊断标签同源)。
 */
public final class TimeoutLog {

    public static final String TAG = "[TIMEOUT]";

    private TimeoutLog() {
    }

    /**
     * @param site   超时机制名, 一般填"任务类名+计时器用途", 如 "EatItemTask.timeout"
     * @param detail 判据细节 (阈值/实测值/位置等), 可为 null
     */
    public static void hit(String site, String detail) {
        Task cur = Task.getCurrentTickingTask();
        String owner = cur == null ? "?" : cur.getClass().getSimpleName();
        String line = TAG + "\tsite=" + site + "\ttask=" + owner
                + (detail == null || detail.isEmpty() ? "" : "\t" + detail);
        Debug.logToFile("altoclef_diag.log", line);
    }
}
