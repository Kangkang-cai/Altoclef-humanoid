package adris.altoclef.bridge;

import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.BeatPhase;
import adris.altoclef.util.Dimension;
import net.minecraft.util.math.Vec3d;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 */
public final class LeaderPhaseWatcher {

    /** 轮询间隔。阶段是"分钟级"变化，2 秒足够灵敏又几乎不耗资源。 */
    private static final long POLL_INTERVAL_MS = 2_000;

    /** 单次 HTTP 超时 —— 必须远小于轮询间隔，否则线程会被拖满。 */
    private static final int TIMEOUT_MS = 1_500;

    /**
     * 从 /status JSON 里抠 {@code "phase":"XXX"}。
     *
     * <p>只认<b>全大写</b>枚举名（≥3 字符）—— 这恰好避开 {@code activeAction.phase} 那个
     * 同名键（它的值是 {@code "running"} / {@code "accepted"} 这类小写状态字），不需要写
     * 完整的 JSON 解析。用正则而不是 Jackson 是为了不给热路径引入解析依赖。
     */
    private static final Pattern PHASE_RE = Pattern.compile("\"phase\"\\s*:\\s*\"([A-Z_]{3,})\"");

    /**
     * (2026-10-07 步骤2) 从同一份 /status 里抠 {@code "pos"} 对象：
     * {@code "pos":{"x":1.0,"y":2.0,"z":3.0,"dim":"overworld"}}。
     *
     * <p>用途：主 bot 走出客户端 ~48 格实体跟踪范围后，次 bot 本地<b>拿不到任何坐标</b>,
     * {@code FollowPlayerTask} 只能停在 "online but too far" 原地干等。主 bot 上报的坐标
     * 就是现成的跨进程坐标源（同机 localhost，不新增对外暴露面）。
     *
     * <p>{@code f1} 用 {@code Locale.ROOT} 定死点号小数，故正则只认 {@code [0-9.-]}。
     */
    private static final Pattern POS_RE = Pattern.compile(
            "\"pos\"\\s*:\\s*\\{\\s*\"x\"\\s*:\\s*(-?[0-9.]+)\\s*,\\s*\"y\"\\s*:\\s*(-?[0-9.]+)"
                    + "\\s*,\\s*\"z\"\\s*:\\s*(-?[0-9.]+)\\s*,\\s*\"dim\"\\s*:\\s*\"([a-z_]+)\"");

    private static volatile BeatPhase _phase = null;   // null = 从未成功拉到
    private static volatile long _lastOkMs = 0;
    private static volatile int _port = 0;
    private static volatile boolean _started = false;

    // (2026-10-07 步骤2) 主 bot 坐标快照。与 phase 共用同一次轮询，但各自独立打时间戳，
    // 一方解析失败不清另一方的值（陈旧度分别由 ageMs() / posAgeMs() 表达）。
    private static volatile double _posX = 0;
    private static volatile double _posY = 0;
    private static volatile double _posZ = 0;
    private static volatile Dimension _posDim = null;   // null = 从未成功拉到
    private static volatile long _posOkMs = 0;

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
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            int port = _port;
            if (port <= 0) continue;
            try {
                String json = httpGet("http://127.0.0.1:" + port + "/status");
                long now = System.currentTimeMillis();
                BeatPhase p = parse(json);
                if (p != null) {
                    if (p != _phase) {
                        // 边沿落盘（阶段是分钟级变化，不会刷屏），供事后对齐两条时间线。
                        Debug.logToFile("altoclef_diag.log", "[LEADER-PHASE] -> " + p);
                    }
                    _phase = p;
                    _lastOkMs = now;
                }
                // (2026-10-07 步骤2) 同一份 /status 顺带解析主 bot 坐标。
                parsePosInto(json, now);
            } catch (Throwable ignored) {
                // 主 bot 未开/端口不通/超时：保留上次值，陈旧度交给 ageMs()/posAgeMs()
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

    private static BeatPhase parse(String json) {
        if (json == null || json.isEmpty()) return null;
        Matcher m = PHASE_RE.matcher(json);
        if (!m.find()) return null;
        try {
            return BeatPhase.valueOf(m.group(1));
        } catch (IllegalArgumentException e) {
            // 对面是旧 jar（还没有 phase 键）或枚举不认识的未来值 → 当作未知
            return null;
        }
    }

    // ==================================================================== //
    // (2026-10-07 步骤2) 主 bot 坐标
    // ==================================================================== //

    /**
     * 最近一次成功拉到的主 bot 坐标（世界坐标，非反透视——是主 bot 自己上报的）。
     * 从未成功 = {@code null}。调用方<b>必须</b>同时看 {@link #posAgeMs()} 与
     * {@link #posDimension()}：过期的或跨维度的坐标直接拿去导航 = 走向错误地点。
     */
    public static Vec3d pos() {
        return _posDim == null ? null : new Vec3d(_posX, _posY, _posZ);
    }

    /** 主 bot 上报名所属维度；从未成功 = {@code null}。 */
    public static Dimension posDimension() {
        return _posDim;
    }

    /** 距上次成功拉到坐标的毫秒数；从未成功 = {@link Long#MAX_VALUE}。 */
    public static long posAgeMs() {
        long ok = _posOkMs;
        return ok == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - ok;
    }

    private static void parsePosInto(String json, long now) {
        if (json == null || json.isEmpty()) return;
        Matcher m = POS_RE.matcher(json);
        if (!m.find()) return;
        try {
            double x = Double.parseDouble(m.group(1));
            double y = Double.parseDouble(m.group(2));
            double z = Double.parseDouble(m.group(3));
            Dimension d = dimFromToken(m.group(4));
            if (d == null) return;
            _posX = x;
            _posY = y;
            _posZ = z;
            _posDim = d;
            _posOkMs = now;
        } catch (NumberFormatException ignored) {
            // 数值畸形（理论上不会）：保留上次值。
        }
    }

    /** /status 的 dim token → 枚举（取值口径与 ActionDispatcher/NamedLandmarks 一致）。 */
    private static Dimension dimFromToken(String t) {
        if (t == null) return null;
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "overworld" -> Dimension.OVERWORLD;
            case "nether", "the_nether" -> Dimension.NETHER;
            case "end", "the_end" -> Dimension.END;
            default -> null;
        };
    }
}
