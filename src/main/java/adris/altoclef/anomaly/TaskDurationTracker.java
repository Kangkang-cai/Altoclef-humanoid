package adris.altoclef.anomaly;

import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务时长记录与统计 (2026-09-11, P1)。
 *
 * 职责: 记录每个任务**实例**的运行时长, 并按任务**类**聚合出分布
 * (p50 / p90 / max), 作为 {@link DurationDetector} 判定"时长异常"的依据。
 *
 * 为什么用自适应分布而不是硬编码期望时长表: 同一个任务类在不同阶段的耗时
 * 能差一个数量级 (挖第一块木头 vs 挖第 200 块), 硬编码表必然要么天天误报,
 * 要么形同虚设。用自身历史 p90 做基线, 只抓"显著偏离自己常态"的个例。
 *
 * 时长口径: 从任务实例最近一次 START 到 STOP 的**墙钟**时间。任务被更高
 * 优先级链抢占 (Task.interrupt) 后会重新 START, 计时随之归零 —— 这是有意的:
 * 被打断说明有正当理由, 不该算进"卡住"。真正卡死的任务是**连续**跑很久。
 */
public final class TaskDurationTracker {

    private static final String DURATION_LOG = "task_durations.log";

    /** 每个任务类保留的历史样本数 (环形, 只留最近这些次)。 */
    private static final int MAX_SAMPLES_PER_CLASS = 64;
    /** 追踪中的实例上限。极端情况下任务未走 stop 就被丢弃时兜底, 防止表无限膨胀。 */
    private static final int MAX_TRACKED_INSTANCES = 8192;
    /** 太短的样本没有统计意义 (每 tick 重建的调度型任务), 不记入分布。 */
    private static final long MIN_RECORDED_MS = 200;
    /** 统计样本少于此数时不给出阈值 —— 冷启动期只记录, 不判定。 */
    private static final int MIN_SAMPLES_FOR_THRESHOLD = 8;
    /** 自适应上限的下限。防止短任务样本把阈值压得过紧。 */
    private static final long FLOOR_THRESHOLD_MS = 30_000;

    private static final IdentityHashMap<Task, Long> _startNs = new IdentityHashMap<>();
    private static final HashMap<String, ArrayDeque<Long>> _samples = new HashMap<>();

    private TaskDurationTracker() {
    }

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    /** 任务实例开始 (或被打断后恢复) 时调用。重复调用会重置计时起点。 */
    public static void onTaskStarted(Task task) {
        if (task == null) return;
        if (_startNs.size() >= MAX_TRACKED_INSTANCES) {
            _startNs.clear();
        }
        _startNs.put(task, System.nanoTime());
    }

    /** 任务实例停止时调用: 结算时长并写入分布样本。 */
    public static void onTaskStopped(Task task) {
        if (task == null) return;
        Long start = _startNs.remove(task);
        if (start == null) return;
        long ms = (System.nanoTime() - start) / 1_000_000L;
        if (ms < MIN_RECORDED_MS) return;
        ArrayDeque<Long> list = _samples.computeIfAbsent(className(task), k -> new ArrayDeque<>());
        list.addLast(ms);
        while (list.size() > MAX_SAMPLES_PER_CLASS) {
            list.pollFirst();
        }
    }

    /** 任务实例已运行的毫秒数; 未在追踪中返回 -1。 */
    public static long elapsedMs(Task task) {
        if (task == null) return -1;
        Long start = _startNs.get(task);
        if (start == null) return -1;
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /**
     * 该任务类的自适应超时上限 (毫秒)。
     * 返回 -1 表示样本不足, 调用方应当**跳过检测**而不是放行。
     */
    public static long timeoutThresholdMs(String className) {
        ArrayDeque<Long> list = _samples.get(className);
        if (list == null || list.size() < MIN_SAMPLES_FOR_THRESHOLD) return -1;
        long p90 = percentile(list, 0.90);
        return Math.max(p90 * 3, FLOOR_THRESHOLD_MS);
    }

    /** 该任务类已收集的样本数。 */
    public static int sampleCount(String className) {
        ArrayDeque<Long> list = _samples.get(className);
        return list == null ? 0 : list.size();
    }

    public static void reset() {
        _startNs.clear();
        _samples.clear();
    }

    // ------------------------------------------------------------------
    // 汇总输出
    // ------------------------------------------------------------------

    /**
     * 把各任务类的时长分布写入 task_durations.log, 按 p90 降序。
     * 供会话结束时调用 —— "跑一晚上, 第二天读一份报告"。
     */
    public static void dumpSummary(String reason) {
        if (_samples.isEmpty()) return;

        List<Map.Entry<String, long[]>> rows = new ArrayList<>();
        for (Map.Entry<String, ArrayDeque<Long>> e : _samples.entrySet()) {
            ArrayDeque<Long> list = e.getValue();
            if (list.isEmpty()) continue;
            rows.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), new long[]{
                    list.size(),
                    percentile(list, 0.50),
                    percentile(list, 0.90),
                    maxOf(list)
            }));
        }
        rows.sort(Comparator.comparingLong((Map.Entry<String, long[]> r) -> r.getValue()[2]).reversed());

        StringBuilder sb = new StringBuilder();
        sb.append("==== TASK DURATION SUMMARY (").append(reason).append(") ====");
        sb.append("\n    ").append(pad("task class", 38))
                .append(pad("n", 5)).append(pad("p50", 10))
                .append(pad("p90", 10)).append("max");
        for (Map.Entry<String, long[]> r : rows) {
            long[] v = r.getValue();
            sb.append("\n    ").append(pad(r.getKey(), 38))
                    .append(pad(String.valueOf(v[0]), 5))
                    .append(pad(formatMs(v[1]), 10))
                    .append(pad(formatMs(v[2]), 10))
                    .append(formatMs(v[3]));
        }
        Debug.logToFile(DURATION_LOG, sb.toString());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 人类可读的时长: 850ms / 4.2s / 2m10s。 */
    public static String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long totalSec = ms / 1000;
        return (totalSec / 60) + "m" + (totalSec % 60) + "s";
    }

    private static String className(Task task) {
        return task.getClass().getSimpleName();
    }

    /** 升序取分位数 (nearest-rank)。 */
    private static long percentile(Collection<Long> values, double q) {
        long[] arr = new long[values.size()];
        int i = 0;
        for (long v : values) arr[i++] = v;
        Arrays.sort(arr);
        int idx = (int) Math.ceil(q * arr.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= arr.length) idx = arr.length - 1;
        return arr[idx];
    }

    private static long maxOf(Collection<Long> values) {
        long m = 0;
        for (long v : values) m = Math.max(m, v);
        return m;
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s + " ";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }
}
