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
                if (_fileLogWriter == null || !filename.equals(_fileLogPath)) {
                    if (_fileLogWriter != null) {
                        _fileLogWriter.flush();
                        _fileLogWriter.close();
                    }
                    File file = new File(filename);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    _fileLogWriter = new PrintWriter(new BufferedWriter(new FileWriter(file, true), 8192), true);
                    _fileLogPath = filename;
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
