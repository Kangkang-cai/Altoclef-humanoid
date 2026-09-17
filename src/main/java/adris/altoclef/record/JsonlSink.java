package adris.altoclef.record;

import adris.altoclef.Debug;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * (2026-09-17 数据集录制) 异步 gzip JSONL 落盘。
 *
 * <p>每通道一个 sink, 各自独立后台线程。主线程 {@link #write(String)} 绝不阻塞、
 * 绝不做 IO —— 队列满时直接丢行并计数, 宁可数据缺一点也绝不拖慢游戏 tick。
 * 沿用 {@code Debug.logToFile} 的纪律: 任何异常都吞掉, 录制失败不能影响 bot。
 *
 * <p>用完必须 {@link #closeAndFlush()}: 让线程把队列排空、把 gzip 尾部写全,
 * 否则最后一段数据会丢 (gzip 流没 close 等于半个文件损坏)。
 *
 * <p>(2026-09-17) 另一个兜底: 线程一启动就先落一个合法空 gzip 占位 (见 {@link #EMPTY_GZIP}),
 * 首行到来才开真流覆盖它 —— 这样"会话刚建立就被硬杀"留下的也是能正常读出 0 行的文件,
 * 而不是只有 gzip 头的残片。
 */
final class JsonlSink {

    /** 队列容量: 20Hz 下约 400 秒的缓冲, 正常写盘速度远超消费, 只有磁盘卡死才会满。 */
    private static final int QUEUE_CAPACITY = 8192;
    private static final String POISON = "\u0000__close__";

    /**
     * (2026-09-17 空文件兜底) 一个"完整、已终结的空 gzip" (20 字节):
     * 头(10) + 空 deflate 终块 03 00(2) + CRC32(4) + ISIZE(4, 全 0)。
     * 见 {@link #run()} 里"先落占位"的用法说明。
     */
    private static final byte[] EMPTY_GZIP = {
            0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03,
            0x03, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    private final Path _path;
    private final BlockingQueue<String> _queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread _thread;
    private volatile boolean _closing = false;
    private volatile long _droppedLines = 0;
    private volatile long _writtenLines = 0;
    private volatile String _error = null;

    JsonlSink(Path path) {
        _path = path;
        _thread = new Thread(this::run, "altoclef-record-" + path.getFileName());
        _thread.setDaemon(true);
        _thread.start();
    }

    /** 主线程调用: 只入队, 不阻塞、不做 IO。 */
    void write(String line) {
        if (_closing) return;
        if (!_queue.offer(line)) {
            _droppedLines++;
        }
    }

    private void run() {
        Writer out = null;
        try {
            Files.createDirectories(_path.getParent());
            // (2026-09-17 空文件兜底) 先在盘上落一个"完整、已终结的空 gzip"(20 字节),
            // 而不是立刻开流式成员: 开流后 GameClient 被硬杀 (shutdown hook 没跑 / 任务
            // 管理器结束进程) 会在盘上留下【只有 gzip 头 10 字节、读不出来的残片】
            // (20260917-100000-r2 实测 4 个 10 字节文件)。占位文件是合法 gzip, 任何
            // 读取器都能正常读出 0 行。
            // 首行真正到来时才开流式成员并【覆盖】占位 —— 保持"单成员"格式, 不引入
            // 多成员 gzip (Windows PowerShell 的 GZipStream 只读第一个成员, 多成员
            // 会让它把整份数据读成 0 行)。
            Files.write(_path, EMPTY_GZIP);
            while (true) {
                String line = _queue.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    // 队列空 → 冲刷。崩溃时最多丢最后 200ms 的数据, 换来"随时可读"。
                    if (out != null) out.flush();
                    continue;
                }
                if (POISON.equals(line)) break;
                if (out == null) {
                    out = new OutputStreamWriter(
                            new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(_path.toFile()), 1 << 16)),
                            StandardCharsets.UTF_8);
                }
                out.write(line);
                out.write('\n');
                _writtenLines++;
                if ((_writtenLines & 0x3F) == 0) out.flush();
            }
        } catch (InterruptedException ignored) {
            // 关闭流程打断, 正常
        } catch (Throwable t) {
            _error = String.valueOf(t);
            Debug.logWarning("[Record] 写盘线程异常 " + _path + ": " + t);
        } finally {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 关闭并入队剩余数据 (最多等 3s, 绝不挂死游戏退出)。幂等。 */
    void closeAndFlush() {
        closeAndFlush(3000);
    }

    /**
     * (2026-09-17) 带超时的收尾。JVM 关闭钩子走短超时 (游戏退出不该被写盘卡住,
     * 但 gzip 尾部必须写完 —— 没尾部的文件多数读取器直接报错)。
     */
    void closeAndFlush(long maxWaitMs) {
        if (_closing) return;
        _closing = true;
        try {
            long deadline = System.currentTimeMillis() + Math.min(500, maxWaitMs);
            while (!_queue.offer(POISON, 50, TimeUnit.MILLISECONDS)) {
                if (System.currentTimeMillis() > deadline) break;
            }
            _thread.join(Math.max(100, maxWaitMs));
        } catch (Throwable ignored) {
        }
    }

    long getWrittenLines() {
        return _writtenLines;
    }

    long getDroppedLines() {
        return _droppedLines;
    }

    String getError() {
        return _error;
    }

    String getName() {
        return _path.getFileName().toString();
    }
}