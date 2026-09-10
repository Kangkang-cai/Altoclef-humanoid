package adris.altoclef;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

// TODO: Debug library or use Minecraft's built in debugger
public class Debug {

    // Cached file writer for our on-disk debug log (creates the file lazily).
    private static final Object _fileLogMutex = new Object();

    // (2026-09-10 会话目录, 用户"日志系统太乱七八糟") 一次会话一个目录:
    // logs/altoclef/<stamp>/, 所有会话日志 (diag/tasks/chat/trace/debugstate/...)
    // 都落在这里, 游戏根目录不再堆文件。文件名里的 altoclef_ 前缀与
    // "-<stamp>" 后缀一并去掉 (目录本身就是会话标识)。
    public static final String SESSION_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    public static final String SESSION_DIR = "logs/altoclef/" + SESSION_STAMP;
    // 持久记忆目录: 跨会话记忆文件 (传送门/要塞走廊/矿洞/自挖区块), 不是日志,
    // 不随会话目录清理。
    public static final String MEMORY_DIR = "logs/altoclef/memory";
    // 构建标识: 写进每个新日志文件头 + 床链诊断行。用于秒判"运行的 class 是不是
    // 新构建" (2026-09-09 22:58 场次: 行为特征=旧 jar 但 mods jar 字节含新代码,
    // 无法定案, 只能用运行时标记直接对质)。
    public static final String BUILD_ID = "logdir-20260910-1930";
    private static final java.util.Set<String> _sessionHeaderWritten = new java.util.HashSet<>();
    // (2026-09-10) 每文件的常开 writer 缓存。旧实现只缓存一个 writer, diag 与
    // chat 每 tick 交替写 = 每条日志都关旧开新文件句柄, 纯浪费。每会话文件数
    // 个位数, 缓存无上限压力。
    private static final java.util.Map<String, PrintWriter> _writers = new java.util.HashMap<>();
    private static boolean _prunedOldSessions = false;

    /** 每会话目录只保留最近 KEEP_SESSIONS 份, 更旧的整目录删除 (首次写日志时执行一次)。 */
    private static final int KEEP_SESSIONS = 10;

    private static String toSessionFile(String filename) {
        String name = filename == null ? "unnamed.log" : filename;
        if (name.startsWith("altoclef_")) name = name.substring("altoclef_".length());
        return SESSION_DIR + "/" + name;
    }

    private static void pruneOldSessionsOnce() {
        if (_prunedOldSessions) return;
        _prunedOldSessions = true;
        try {
            java.io.File base = new File("logs/altoclef");
            File[] dirs = base.listFiles(File::isDirectory);
            if (dirs == null || dirs.length <= KEEP_SESSIONS) return;
            Arrays.sort(dirs, java.util.Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP_SESSIONS; i < dirs.length; i++) {
                File cur = dirs[i];
                if (cur.getName().equals(SESSION_STAMP)) continue;
                deleteRecursively(cur);
            }
        } catch (Exception ignored) {
            // 清理失败不阻塞日志
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 持久记忆文件解析: logs/altoclef/memory/<newName>。首次调用时把旧的游戏
     * 根目录文件 (<legacyName>) 自动迁过来, 跨会话记忆 (门/要塞/矿洞) 不丢。
     */
    public static java.nio.file.Path memoryFile(String legacyName, String newName) {
        java.nio.file.Path base = MinecraftClient.getInstance() != null
                ? MinecraftClient.getInstance().runDirectory.toPath()
                : java.nio.file.Paths.get(".");
        java.nio.file.Path target = base.resolve(MEMORY_DIR).resolve(newName);
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Path legacy = base.resolve(legacyName);
            if (!java.nio.file.Files.exists(target) && java.nio.file.Files.exists(legacy)) {
                java.nio.file.Files.move(legacy, target);
            }
        } catch (Exception ignored) {
            // 迁移失败不影响读取/追加
        }
        return target;
    }

    public static void logInternal(String message) {
        System.out.println("ALTO CLEF: " + message);
    }

    public static void logInternal(String format, Object... args) {
        logInternal(String.format(format, args));
    }

    /**
     * Appends a timestamped line to a file on disk, so long/perf logs don't have
     * to be manually copied out of chat/stdout. The file is created in the
     * working directory (the run/ or .minecraft dir) the first time it's used.
     * Writing is flushed every line and guarded against crashes + exceptions so
     * an IO hiccup can never break the game tick.
     */
    public static void logToFile(String filename, String message) {
        synchronized (_fileLogMutex) {
            try {
                pruneOldSessionsOnce();
                String path = toSessionFile(filename);
                PrintWriter writer = _writers.get(path);
                if (writer == null) {
                    File file = new File(path);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    writer = new PrintWriter(new BufferedWriter(new FileWriter(file, true), 8192), true);
                    _writers.put(path, writer);
                    // 每会话每文件只写一次头行: 会话起点 + 构建标识。
                    if (_sessionHeaderWritten.add(path)) {
                        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                        writer.println("==== SESSION START " + ts + " build=" + BUILD_ID + " ====");
                    }
                }
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                writer.println(ts + "  " + message);
            } catch (Exception e) {
                System.err.println("ALTO CLEF: failed to write debug log " + filename + ": " + e);
            }
        }
    }

    // [TRACE] (2026-09-01) 制作/收集石头/木头三链的专用追踪日志。写
    // altoclef_trace.log (位于 .minecraft)。同一 tag 1 秒内只写一次 (节流),
    // 避免刷屏的同时保证"百分百追逐过程"——状态跳到新值时立即记录, 反复抖动
    // 时会因节流只留首条 + 末条可见趋势。tag 用于区分制作(craft)/石头(cobble)/
    // 木头(wood)。
    private static final java.util.Map<String, Long> _traceThrottle = new java.util.HashMap<>();
    public static void logTrace(String tag, String message) {
        String filename = "altoclef_trace.log";
        long nowMs = System.currentTimeMillis();
        synchronized (_traceThrottle) {
            Long last = _traceThrottle.get(tag);
            if (last != null && nowMs - last < 1000) {
                return; // 1 秒节流, 同一 tag 不重复刷
            }
            _traceThrottle.put(tag, nowMs);
        }
        logToFile(filename, "[" + tag + "] " + message);
    }

    public static AltoClef jankModInstance;

    private static String getLogPrefix() {
        if (jankModInstance != null) {
            return jankModInstance.getModSettings().getChatLogPrefix();
        }
        return "[Alto Clef] ";
    }

    // (2026-09-10 聊天输出落盘) 聊天屏只留最后约 100 条且退出即失, 用户在
    // 聊天栏看到的现场线索 (如 [Liquid-avoid] 刷屏) 从未进过任何文件——
    // logMessage 有玩家时只走 player.sendMessage, 不经过 logInternal/stdout。
    // 从此每条聊天输出同步写 altoclef_chat.log (在 .minecraft, 分会话), 与
    // 聊天栏一一对应, 排查"聊天里看见了但日志里没有"的问题。
    private static String stripFormatting(String message) {
        return message == null ? "null" : message.replaceAll("\u00a7.", "");
    }

    public static void logMessage(String message, boolean prefix) {
        logToFile("altoclef_chat.log", "[CHAT] " + stripFormatting(message));
        if (MinecraftClient.getInstance() != null && MinecraftClient.getInstance().player != null) {
            if (prefix) {
                message = "\u00A72\u00A7l\u00A7o" + getLogPrefix() + "\u00A7r" + message;
            }
            MinecraftClient.getInstance().player.sendMessage(Text.of(message), false);
            //MinecraftClient.getInstance().player.sendChatMessage(msg);
        } else {
            logInternal(message);
        }
    }

    public static void logMessage(String message) {
        logMessage(message, true);
    }

    public static void logMessage(String format, Object... args) {
        logMessage(String.format(format, args));
    }

    public static void logWarning(String message) {
        logInternal("WARNING: " + message);
        logToFile("altoclef_chat.log", "[WARN] " + stripFormatting(message));
        if (jankModInstance != null && !jankModInstance.getModSettings().shouldHideAllWarningLogs()) {
            if (MinecraftClient.getInstance() != null && MinecraftClient.getInstance().player != null) {
                String msg = "\u00A72\u00A7l\u00A7o" + getLogPrefix() + "\u00A7c" + message + "\u00A7r";
                MinecraftClient.getInstance().player.sendMessage(Text.of(msg), false);
                //MinecraftClient.getInstance().player.sendChatMessage(msg);
            }
        }
    }

    public static void logWarning(String format, Object... args) {
        logWarning(String.format(format, args));
    }

    public static void logError(String message) {
        String stacktrace = getStack(2);
        System.err.println(message);
        System.err.println("at:");
        System.err.println(stacktrace);
        if (MinecraftClient.getInstance() != null && MinecraftClient.getInstance().player != null) {
            String msg = "\u00A72\u00A7l\u00A7c" + getLogPrefix() + "[ERROR] " + message + "\nat:\n" + stacktrace + "\u00A7r";
            MinecraftClient.getInstance().player.sendMessage(Text.of(msg), false);
        }
    }

    public static void logError(String format, Object... args) {
        logError(String.format(format, args));
    }

    public static void logStack() {
        logInternal("STACKTRACE: \n" + getStack(2));
    }

    private static String getStack(int toSkip) {
        StringBuilder stacktrace = new StringBuilder();
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            if (toSkip-- <= 0) {
                stacktrace.append(ste.toString()).append("\n");
            }
        }
        return stacktrace.toString();
    }
}
