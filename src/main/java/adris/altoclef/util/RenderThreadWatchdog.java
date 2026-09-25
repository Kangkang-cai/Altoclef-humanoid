package adris.altoclef.util;

import adris.altoclef.AltoClef;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * (2026-09-25 渲染线程冻结取证看门狗)
 *
 * 背景 (172433 会话实锤): 17:37:50 Render thread 末条日志后客户端整个定格,
 * 集成服务器又跑了 20 秒 (17:38:01 服务器记录死亡、17:38:10 连接超时被踢),
 * crash-reports 无新文件 —— 是主线程 hang 不是 crash。冻结期间【零日志零栈】,
 * 无法区分是 BlockTracker 重扫、任务互抢还是寻路计算吃死了主线程。
 *
 * 机制:
 *  · Render thread 每 client tick 打一次心跳 (AltoClef.onClientTick 顶部);
 *  · 守护线程每 500ms 检查: 心跳落后超过 FREEZE_THRESHOLD_MS 判冻结,
 *    立即把【全部线程栈】+ 当前任务链快照落盘 watchdog.log, 冻结期间每 5s
 *    追加一帧 (持续冻结可拿到多份样本对齐阻塞点);
 *  · 心跳恢复时记一条 "freeze ENDED, lasted Xs"。
 *
 * 线程安全注意: 刻意【不走 Debug.logToFile】 —— 它持有全局 _fileLogMutex,
 * 而冻结的 Render thread 可能正卡在 logToFile 里握着这把锁, 看门狗再进去
 * 取锁就会跟着死锁, 永远写不出取证文件。本类自带独立 writer 互斥。
 *
 * 误报面: 系统休眠/恢复、JVM 断点会表现为一次长心跳间隔, 落一份 dump 而
 * 已, 无行为影响。dump 只在冻结时发生, 常态零成本 (一次 volatile 读)。
 */
public final class RenderThreadWatchdog {

    /** 心跳落后该值判冻结。5s: 远大于任何合法长帧 (JankDebug 只记 >100ms), 远小于用户可感知的"完全卡住"。 */
    private static final long FREEZE_THRESHOLD_MS = 5000;
    /** 冻结期间 dump 的最小间隔 (持续冻结拿多份样本)。 */
    private static final long DUMP_INTERVAL_MS = 5000;
    private static final long CHECK_INTERVAL_MS = 500;

    private static volatile long _lastHeartbeatNano = 0;
    private static volatile boolean _frozen = false;
    private static boolean _started = false;

    // 看门狗线程私有状态 (仅 monitorLoop 访问), 无需同步。
    private static long _freezeStartWallMs = 0;
    private static long _lastDumpWallMs = 0;
    private static int _dumpCount = 0;

    private static AltoClef _modRef = null;
    private static final Object _writerMutex = new Object();
    private static PrintWriter _writer = null;

    private RenderThreadWatchdog() {
    }

    /** Render thread 每 tick 调用 (onClientTick 顶部), 纯 volatile 写。 */
    public static void heartbeat() {
        _lastHeartbeatNano = System.nanoTime();
    }

    /** 启动守护监控线程; 幂等 (AltoClef.onInitializeLoad 调用)。 */
    public static synchronized void start(AltoClef mod) {
        if (_started) return;
        _started = true;
        _modRef = mod;
        heartbeat();
        Thread monitor = new Thread(RenderThreadWatchdog::monitorLoop, "altoclef-render-watchdog");
        monitor.setDaemon(true);
        monitor.start();
    }

    private static void monitorLoop() {
        while (true) {
            try {
                //noinspection BusyWait
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            long lastNano = _lastHeartbeatNano;
            if (lastNano == 0) continue; // 首次心跳未到
            long nowWall = System.currentTimeMillis();
            long lastHeartbeatWall = lastNano / 1_000_000L;
            long gapMs = nowWall - lastHeartbeatWall;
            if (gapMs > FREEZE_THRESHOLD_MS) {
                if (!_frozen) {
                    _frozen = true;
                    // 冻结起点以"最后一次心跳"计, 比发现时刻更贴近真实冻结点。
                    _freezeStartWallMs = lastHeartbeatWall;
                    _lastDumpWallMs = 0;
                    _dumpCount = 0;
                }
                if (nowWall - _lastDumpWallMs >= DUMP_INTERVAL_MS) {
                    _lastDumpWallMs = nowWall;
                    _dumpCount++;
                    dumpFrozen(nowWall - _freezeStartWallMs);
                }
            } else if (_frozen) {
                _frozen = false;
                logLine("freeze ENDED: lasted " + (nowWall - _freezeStartWallMs) + "ms, dumps=" + _dumpCount);
            }
        }
    }

    /** 冻结现场转储: 全线程栈 + 任务链快照。任何一步失败都不能拖垮看门狗。 */
    private static void dumpFrozen(long frozenMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== RENDER THREAD FROZEN ").append(frozenMs).append("ms (dump #").append(_dumpCount).append(") ====");
        try {
            AltoClef mod = _modRef;
            if (mod != null) {
                var chain = mod.getTaskRunner() != null ? mod.getTaskRunner().getCurrentTaskChain() : null;
                sb.append("\ntaskChain=").append(chain == null ? "null" : chain.getTasks().toString());
            }
        } catch (Throwable ignored) {
            // 游戏状态读取异常不影响线程栈转储
        }
        try {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                sb.append("\n\n-- thread ").append(t.getName())
                        .append(" state=").append(t.getState());
                StackTraceElement[] frames = e.getValue();
                // 栈顶 60 帧足够定位阻塞点, 控制单份 dump 体积
                for (int i = 0; i < frames.length && i < 60; i++) {
                    sb.append("\n    at ").append(frames[i]);
                }
                if (frames.length > 60) sb.append("\n    ... (").append(frames.length - 60).append(" more)");
            }
        } catch (Throwable ignored) {
        }
        logLine(sb.toString());
    }

    /**
     * 独立 writer 落盘 (与 Debug 的 _fileLogMutex 完全隔离, 见类注释)。
     * 目录沿用会话目录约定: logs/altoclef/<stamp>/watchdog.log。
     */
    private static void logLine(String message) {
        synchronized (_writerMutex) {
            try {
                if (_writer == null) {
                    String path = adris.altoclef.Debug.SESSION_DIR + "/watchdog.log";
                    File file = new File(path);
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    _writer = new PrintWriter(new BufferedWriter(
                            new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8), 8192), true);
                    String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                    _writer.println("==== SESSION START " + ts + " build=" + adris.altoclef.Debug.BUILD_ID + " ====");
                }
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                _writer.println(ts + "  " + message);
            } catch (Exception e) {
                System.err.println("ALTO CLEF: watchdog failed to write dump: " + e);
            }
        }
    }
}
