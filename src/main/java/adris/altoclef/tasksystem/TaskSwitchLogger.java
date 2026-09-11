package adris.altoclef.tasksystem;

import adris.altoclef.Debug;
import adris.altoclef.anomaly.Anomaly;
import adris.altoclef.anomaly.AnomalySeverity;
import adris.altoclef.anomaly.AnomalyWatchdog;
import adris.altoclef.anomaly.TaskCycleDetector;
import adris.altoclef.anomaly.TaskDurationTracker;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * 任务记录系统 (2026-08-21): 把任务调度/执行的完整生命周期写进
 * {@code altoclef_tasks.log}，用于事后排查"任务为什么这么跳"。
 *
 * 事件一览 (每行自动带墙钟时间戳 + 游戏 tick):
 *   SESSION 会话开始分隔线
 *   OWNER   链所有权切换 (谁在控制 bot, 含新旧优先级)
 *   SUSPEND 链被更高优先级链抢占 (任务树暂停, 等待恢复)
 *   RESUME  链恢复执行 (抢占结束后拿回控制权)
 *   MAIN    链主任务更换
 *   SUB     任务派发/更换子任务 (含所属链名) — 核心调度事件
 *   START   任务开始执行 (onStart, 含恢复后的重启)
 *   STOP    任务停止 (by X = 被 X 打断; clean = 正常停止)
 *   END     链主任务结束 (finished=自然完成 / stopped=被停)
 *
 * 排查方法: 在日志里按 tick 顺序看 OWNER/SUSPEND/RESUME 判断谁抢了
 * 控制权, 看 SUB/STOP 判断任务树内部的切换原因, 看 START 间隔判断
 * 任务是否反复重启 (抖动)。
 *
 * 同时保留抖动检测: 相同切换 (同链/同父/同 from->to 类型) 在窗口内反复出现时,
 * 往本文件写 "!!!!" 行, 并通过 AnomalyWatchdog 上报 (统一冷却 / 分级 / 现场快照,
 * 输出到 anomaly.log + 聊天)。
 *
 * (2026-09-11 P2) 每次 MAIN / SUB 切换还会喂给 TaskCycleDetector, 由它检测
 * **周期 >= 3** 的任务环 —— 抖动检测只管 A<->B 来回跳, 管不了 A->B->C->A。
 */
public final class TaskSwitchLogger {

    // (2026-09-10 会话目录, 用户"日志系统太乱七八糟") 文件名固定 tasks.log,
    // 落在 logs/altoclef/<会话时间戳>/ 里 — 目录本身就是会话隔离, 不再需要
    // 文件名时间戳与本地 prune (旧目录由 Debug.pruneOldSessionsOnce 统一清)。
    private static final String LOG_FILE = "tasks.log";

    // (2026-09-10 连续重复折叠, 用户"任务切换日志一定要真实清晰") 任务每 tick
    // 重跑调度时会产生大量逐字相同的事件行 (START/STOP/SUB 同一行刷几十条),
    // 真正的切换被淹没。同一事件连续出现时只写首条 + 每 REPEAT_FLUSH_MS 一条
    // "(同上 xN)" 计数行; 事件一变立即补一条收尾计数。抖动 (A<->B 交替) 两行
    // 内容不同, 不受折叠影响, 照常全量可见 + TaskPingPong 告警。
    private static final long REPEAT_FLUSH_MS = 5000;

    private static String _lastEvent = null;
    private static long _lastEventTick = -1;
    private static int _repeatCount = 0;
    private static long _lastRepeatFlushMs = 0;

    // If the same switch pair happens this many times within the window, warn.
    private static final int OSCILLATION_THRESHOLD = 6;
    private static final long OSCILLATION_WINDOW_MS = 5000;
    // Warn at most this often per switch key.
    private static final long WARN_THROTTLE_MS = 10000;

    // key = where|chain|parentClass|oldClass->newClass ; values = recent switch timestamps.
    private static final HashMap<String, ArrayDeque<Long>> _recentSwitches = new HashMap<>();
    private static final HashMap<String, Long> _lastWarnMs = new HashMap<>();

    // Monotonic game tick, updated by TaskRunner every tick (client session scope).
    private static volatile long _tick = -1;

    private TaskSwitchLogger() {
    }

    /** Called by TaskRunner at the start of every tick. */
    public static void setTick(long tick) {
        _tick = tick;
    }

    /** Session separator, written when the task runner (re)enables. */
    public static void sessionStart() {
        _lastEvent = null;
        _repeatCount = 0;
        Debug.logToFile(LOG_FILE, "");
        Debug.logToFile(LOG_FILE, "==== SESSION START ====");
    }

    /** Chain ownership change: who controls the bot now. */
    public static void owner(TaskChain from, TaskChain to, float newPriority) {
        write("OWNER   " + name(from) + " -> " + name(to) + " (prio " + newPriority + ")");
    }

    /** A chain was preempted by a higher-priority chain (task tree suspended). */
    public static void chainSuspended(TaskChain chain, TaskChain by) {
        write("SUSPEND " + name(chain) + " preempted by " + name(by));
    }

    /** A chain regained control after preemption. */
    public static void chainResumed(TaskChain chain, Task mainTask) {
        write("RESUME  " + name(chain) + " mainTask=" + desc(mainTask));
    }

    /**
     * A chain's main task was swapped (setTask).
     * {@code priority} is the chain's own getPriority() at this moment — lets
     * the reader see which chain (by name + priority) holds this MAIN task.
     */
    public static void mainTask(TaskChain chain, float priority, Task from, Task to) {
        write("MAIN    [" + name(chain) + " prio=" + priority + "] " + desc(from) + "  ->  " + desc(to));
        oscillationCheck("MAIN|" + name(chain), name(chain), from, to, "Chain.main");
        // (2026-09-11 P2 任务环) 周期 >= 3 的循环检测 (周期 2 由抖动检测负责)。
        TaskCycleDetector.observe("MAIN:" + name(chain) + ":"
                + className(from) + "->" + className(to));
    }

    /**
     * A task dispatched a different subtask (the core scheduling event).
     * {@code chainPrio} is the owning chain's getPriority() at this moment —
     * the priority at which this subtree runs, so "why did this branch run
     * instead of another chain" is readable without digging into sources.
     */
    public static void subTask(TaskChain chain, float chainPrio, Task parent, Task from, Task to) {
        write("SUB     [" + name(chain) + " prio=" + chainPrio + "] " + desc(parent) + ": " + desc(from) + "  ->  " + desc(to));
        oscillationCheck("SUB|" + name(chain) + "|" + className(parent), name(chain) + "/" + className(parent), from, to, "Task.sub");
        // (2026-09-11 P2 任务环) 周期 >= 3 的循环检测。
        TaskCycleDetector.observe("SUB:" + className(parent) + ":"
                + className(from) + "->" + className(to));
    }

    /** A task began executing (onStart, incl. restart after resume). */
    public static void taskStarted(Task task, TaskChain chain) {
        write("START   [" + name(chain) + "] " + desc(task));
        // (2026-09-11 P1 时长记录) 每次 START 重置计时起点: 被抢占后恢复即重新计时,
        // 只统计"连续"运行时长 (理由见 TaskDurationTracker 类注释)。
        TaskDurationTracker.onTaskStarted(task);
    }

    /**
     * A task stopped. {@code interruptor} == null means a clean stop.
     * (2026-09-11 P1) 行尾附带该实例本次连续运行的时长, 让 tasks.log 直接可读,
     * 不必再靠前后时间戳人工做减法。
     */
    public static void taskStopped(Task task, Task interruptor) {
        long tookMs = TaskDurationTracker.elapsedMs(task);
        write("STOP    " + desc(task) + (interruptor == null ? "  (clean)" : "  by " + desc(interruptor))
                + (tookMs >= 0 ? "  took=" + TaskDurationTracker.formatMs(tookMs) : ""));
        TaskDurationTracker.onTaskStopped(task);
    }

    /** A chain's main task ended this tick (finished naturally or stopped). */
    public static void chainTaskEnded(TaskChain chain, Task task, boolean finished, boolean stopped) {
        write("END     [" + name(chain) + "] " + desc(task) + " finished=" + finished + " stopped=" + stopped);
    }

    // ------------------------------------------------------------------

    private static void write(String message) {
        // (2026-09-10 连续重复折叠) 折叠键不含 tick — 同一事件逐 tick 重发时
        // 内容逐字相同, 只有计数行刷新时才用最新 tick 落盘。
        if (message.equals(_lastEvent)) {
            _repeatCount++;
            long now = System.currentTimeMillis();
            if (now - _lastRepeatFlushMs >= REPEAT_FLUSH_MS) {
                Debug.logToFile(LOG_FILE, "[t=" + _tick + "] (同上 x" + _repeatCount + ")");
                _lastRepeatFlushMs = now;
                _repeatCount = 0;
            }
            return;
        }
        if (_repeatCount > 0) {
            Debug.logToFile(LOG_FILE, "[t=" + _lastEventTick + "] (同上 x" + _repeatCount + ")");
            _repeatCount = 0;
        }
        _lastEvent = message;
        _lastEventTick = _tick;
        _lastRepeatFlushMs = System.currentTimeMillis();
        Debug.logToFile(LOG_FILE, "[t=" + _tick + "] " + message);
    }

    private static void oscillationCheck(String key, String parentDesc, Object from, Object to, String where) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = _recentSwitches.computeIfAbsent(key, k -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > OSCILLATION_WINDOW_MS) {
            times.pollFirst();
        }
        // Prune stale keys so memory stays bounded.
        if (_recentSwitches.size() > 512) {
            _recentSwitches.entrySet().removeIf(e ->
                    e.getValue().isEmpty() || now - e.getValue().peekLast() > OSCILLATION_WINDOW_MS * 4);
        }
        if (times.size() >= OSCILLATION_THRESHOLD) {
            Long lastWarn = _lastWarnMs.get(key);
            if (lastWarn == null || now - lastWarn > WARN_THROTTLE_MS) {
                _lastWarnMs.put(key, now);
                String msg = "[TaskPingPong] '" + parentDesc + "' switched '"
                        + desc(from) + "' <-> '" + desc(to) + "' " + times.size()
                        + "x in " + OSCILLATION_WINDOW_MS / 1000 + "s (@" + where + "). Bot is thrashing!";
                write("!!!! " + msg);
                // (2026-09-11) 抖动归入统一异常看门狗: 由它负责冷却、分级、输出到
                // anomaly.log + 聊天, 并附带现场快照 (任务栈 / 坐标 / baritone 状态)。
                // 这里只上报, 不再自己 logWarning —— 避免同一件事在两条通道各喊一遍。
                AnomalyWatchdog.report(new Anomaly(
                        "oscillation", key, AnomalySeverity.WARN, msg, null));
            }
        }
    }

    private static String name(TaskChain chain) {
        return chain == null ? "null" : chain.getName();
    }

    private static String desc(Object task) {
        if (task == null) return "null";
        String s = task.toString();
        // (2026-09-10 用户"看不懂") 110 -> 80: toString 常有超长参数列表,
        // 截断点收紧让每行任务描述在终端里一屏可读。
        if (s.length() > 80) s = s.substring(0, 80) + "...";
        return s;
    }

    private static String className(Object task) {
        return task == null ? "null" : task.getClass().getSimpleName();
    }
}
