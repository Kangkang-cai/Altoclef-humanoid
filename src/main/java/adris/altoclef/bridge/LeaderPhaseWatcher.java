package adris.altoclef.bridge;

import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.BeatPhase;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * (2026-10-06 阶段跟随) <b>后台拉取主 bot 的 {@code /status.phase}</b>。
 *
 * <h3>为什么是这个方案</h3>
 * 客户端只下发 ~48 格内玩家的<b>位置</b>，看不到对方"在干什么"；而 {@code @follow} 也让
 * 被跟随者对自己当前的任务一无所知。所以"主 bot 干 X 时次 bot 跟随、干 Y 时次 bot 自主"
 * 必须有<b>跨进程的信息源</b>。用户 2026-10-06 22:5x 拍板走"次 bot 直连主 bot 的 bridge"
 * （本机 localhost，与 bridge 服务同源，不新增对外暴露面）。
 *
 * <h3>为什么在后台线程</h3>
 * HTTP 会阻塞（连接超时 1.5s）——绝不能放在客户端 tick 线程里。这里起一个 daemon 线程
 * 每 {@link #POLL_INTERVAL_MS} 拉一次，结果写进 volatile 字段，tick 侧只做无锁读。
 *
 * <h3>失败语义</h3>
 * 拉取失败（主 bot 没开 / 端口不通 / 超时）<b>保留上次值</b>，由 {@link #ageMs()} 表达陈旧度；
 * 调用方据"陈旧"决定是否退回保守策略（跟随）。这样主 bot 短暂卡顿不会让次 bot 乱切。
 *
 * <h3>(2026-10-07) 收窄职责 + 首拉提前</h3>
 * <ul>
 *   <li><b>坐标解析已移出</b>：{@code pos} 不再由本类处理，改由 {@link PeerPosWatcher}
 *       按玩家名维护（通用坐标共享）。同一份 JSON 只允许一个解析源（{@link StatusJson}）。</li>
 *   <li><b>首拉提前到线程启动时</b>：原实现"先 sleep 2s 再拉第一次"，导致每会话启动后约 2s 内
 *       {@link #phase()} 恒 {@code null}、{@code pos} 恒不可用 —— 1007 13:11:01 实测
 *       次 bot 在该窗口落回裸跟随并打印一条 "no coordinates yet"
 *       （`prep done` 后 53ms）。现在先拉再睡。</li>
 * </ul>
 */
public final class LeaderPhaseWatcher {

    /** 轮询间隔。阶段是"分钟级"变化，2 秒足够灵敏又几乎不耗资源。 */
    private static final long POLL_INTERVAL_MS = 2_000;

    /** 单次 HTTP 超时 —— 必须远小于轮询间隔，否则线程会被拖满。 */
    private static final int TIMEOUT_MS = 1_500;

    private static volatile BeatPhase _phase = null;   // null = 从未成功拉到
    private static volatile long _lastOkMs = 0;
    private static volatile int _port = 0;
    private static volatile boolean _started = false;

    private LeaderPhaseWatcher() {
    }

    /**
     * 幂等启动。{@code port <= 0} 时什么都不做（调用方据此判断"未启用"）。
     *
     * @param port 主 bot 的 bridge 端口（{@code bridge.json} 的 {@code leaderPort}）
     */
    public static synchronized void ensureStarted(int port) {
        if (port <= 0) return;
        if (_started) {
            if (_port != port) _port = port;   // 允许热改端口，不必重启线程
            return;
        }
        _port = port;
        _started = true;
        Thread t = new Thread(LeaderPhaseWatcher::loop, "leader-phase-watcher");
        t.setDaemon(true);                     // 绝不阻止 JVM 退出
        t.start();
    }

    /** 最近一次成功拉到的主 bot 阶段；从未成功 = {@code null}。 */
    public static BeatPhase phase() {
        return _phase;
    }

    /** 距上次成功拉取的毫秒数；从未成功 = {@link Long#MAX_VALUE}。 */
    public static long ageMs() {
        long ok = _lastOkMs;
        return ok == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - ok;
    }

    private static void loop() {
        while (true) {
            // (2026-10-07) 先拉再睡：消除"启动后首个 2s 窗口内阶段/坐标均不可用"的假象。
            int port = _port;
            if (port > 0) {
                try {
                    String json = httpGet("http://127.0.0.1:" + port + "/status");
                    BeatPhase p = StatusJson.parsePhase(json);
                    if (p != null) {
                        if (p != _phase) {
                            // 边沿落盘（阶段是分钟级变化，不会刷屏），供事后对齐两条时间线。
                            Debug.logToFile("altoclef_diag.log", "[LEADER-PHASE] -> " + p);
                        }
                        _phase = p;
                        _lastOkMs = System.currentTimeMillis();
                    }
                } catch (Throwable ignored) {
                    // 主 bot 未开/端口不通/超时：保留上次值，陈旧度交给 ageMs()
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("GET");
            try (InputStream in = c.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
