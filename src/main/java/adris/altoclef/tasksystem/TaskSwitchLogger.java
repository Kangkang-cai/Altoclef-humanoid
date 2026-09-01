package adris.altoclef.tasksystem;

import adris.altoclef.Debug;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
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
 * 同时保留抖动检测: 相同切换 (同链/同父/同 from->to 类型) 在窗口内
 * 反复出现时在聊天里告警 "[TaskPingPong]"。
 */
public final class TaskSwitchLogger {

    // (2026-08-30 每会话新文件) 不再是固定 "altoclef_tasks.log" 持续追加 —
    // 每次 SESSION START 换成一个带时间戳的文件, 避免 20MB+ 越滚越大。
    private static String LOG_FILE = "altoclef_tasks.log";
    // 目录里保留最近多少份任务日志, 更旧的自动删除。
    private static final int KEEP_SESSIONS = 20;

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
        // (2026-08-30 每会话新日志文件, 用户) 每次测试生成 altoclef_tasks-yyyyMMdd-HHmmss.log,
        // 清理到保留最近 KEEP_SESSIONS 份。Debug.logToFile 见新文件名会自动关旧开新(appendix
        // 改为新文件), 天然形成"每测试一份、按时间定位"。
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        LOG_FILE = "altoclef_tasks-" + ts + ".log";
        pruneOldTaskLogs();
        Debug.logToFile(LOG_FILE, "");
        Debug.logToFile(LOG_FILE, "==== SESSION START ====");
    }

    /**
     * (2026-08-30) 删除最早的任务日志, 目录里只保留最近 KEEP_SESSIONS-1 份旧的
     * (本次会话马上会新开第 KEEP_SESSIONS 份)。删除失败被忽略, 不影响运行。
     */
    private static void pruneOldTaskLogs() {
        try {
            File dir = new File(LOG_FILE).getAbsoluteFile().getParentFile();
            File base = dir != null ? dir : new File(".");
            File[] logs = base.listFiles(f -> f.isFile()
                    && f.getName().startsWith("altoclef_tasks") && f.getName().endsWith(".log"));
            if (logs == null || logs.length <= KEEP_SESSIONS - 1) return;
            Arrays.sort(logs, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP_SESSIONS - 1; i < logs.length; i++) {
                if (!logs[i].getName().equals(new File(LOG_FILE).getName())) {
                    logs[i].delete();
                }
            }
        } catch (Exception ignored) {
            // 清理失败不阻塞日志写入
        }
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
    }

    /** A task began executing (onStart, incl. restart after resume). */
    public static void taskStarted(Task task, TaskChain chain) {
        write("START   [" + name(chain) + "] " + desc(task));
    }

    /** A task stopped. {@code interruptor} == null means a clean stop. */
    public static void taskStopped(Task task, Task interruptor) {
        write("STOP    " + desc(task) + (interruptor == null ? "  (clean)" : "  by " + desc(interruptor)));
    }

    /** A chain's main task ended this tick (finished naturally or stopped). */
    public static void chainTaskEnded(TaskChain chain, Task task, boolean finished, boolean stopped) {
        write("END     [" + name(chain) + "] " + desc(task) + " finished=" + finished + " stopped=" + stopped);
    }

    // ------------------------------------------------------------------

    private static void write(String message) {
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
                Debug.logWarning(msg);
                write("!!!! " + msg);
            }
        }
    }

    private static String name(TaskChain chain) {
        return chain == null ? "null" : chain.getName();
    }

    private static String desc(Object task) {
        if (task == null) return "null";
        String s = task.toString();
        if (s.length() > 110) s = s.substring(0, 110) + "...";
        return s;
    }

    private static String className(Object task) {
        return task == null ? "null" : task.getClass().getSimpleName();
    }
}
