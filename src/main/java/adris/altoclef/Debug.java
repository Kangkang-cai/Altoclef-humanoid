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
    private static volatile PrintWriter _fileLogWriter;
    private static volatile String _fileLogPath;

    // (2026-09-09 分会话日志轮转, 用户"总和 diag 68MB 全是以前没用现象") 每个
    // JVM 会话固定一个时间戳, logToFile 把 "xxx.log" 落盘为 "xxx-<stamp>.log" —
    // diag/debugstate/trace/craftclick 等从此按会话分文件, 与 tasks 日志同款,
    // 不再跨会话往同一文件堆历史。
    public static final String SESSION_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    // 构建标识: 写进每个新日志文件头 + 床链诊断行。用于秒判"运行的 class 是不是
    // 新构建" (2026-09-09 22:58 场次: 行为特征=旧 jar 但 mods jar 字节含新代码,
    // 无法定案, 只能用运行时标记直接对质)。
    public static final String BUILD_ID = "beddiag-20260909-2320";
    private static final java.util.Set<String> _sessionHeaderWritten = new java.util.HashSet<>();

    private static String toSessionFile(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) return filename;
        String ext = filename.substring(dot);
        if (ext.length() > 6) return filename; // 非常规扩展名不动
        return filename.substring(0, dot) + "-" + SESSION_STAMP + ext;
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
                String sessionFile = toSessionFile(filename);
                if (_fileLogWriter == null || !sessionFile.equals(_fileLogPath)) {
                    if (_fileLogWriter != null) {
                        _fileLogWriter.flush();
                        _fileLogWriter.close();
                    }
                    File file = new File(sessionFile);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    _fileLogWriter = new PrintWriter(new BufferedWriter(new FileWriter(file, true), 8192), true);
                    _fileLogPath = sessionFile;
                    // 每会话每文件只写一次头行: 会话起点 + 构建标识。
                    if (_sessionHeaderWritten.add(sessionFile)) {
                        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                        _fileLogWriter.println("==== SESSION START " + ts + " build=" + BUILD_ID + " ====");
                    }
                }
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                _fileLogWriter.println(ts + "  " + message);
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

    public static void logMessage(String message, boolean prefix) {
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
