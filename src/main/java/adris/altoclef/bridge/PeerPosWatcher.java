package adris.altoclef.bridge;

import adris.altoclef.Debug;
import adris.altoclef.util.Dimension;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (2026-10-07 用户拍板"<b>关键还是让每个 bot 知道别的 bot 的坐标</b>")
 * <b>本机 bot 间坐标共享</b>：后台线程轮询本机各 bot 实例的 {@code /status}，
 * 维护 {@code 游戏内玩家名 → (坐标, 维度, 时间戳, 端口)} 表。
 *
 * <h3>为什么需要它（1007 13:1x 现场）</h3>
 * 客户端只下发 ~48 格内其他玩家的<b>实体</b>（{@code entity-tracking-range.players}），
 * 超过就<b>连坐标都拿不到</b>。于是：
 * <ul>
 *   <li>对端 {@code @follow kangkang}（13:15:29 实测）→ 裸 {@link
 *       adris.altoclef.tasks.movement.FollowPlayerTask} 停在
 *       "online but too far: no coordinates yet"，<b>持续 6 分钟原地干等</b>；</li>
 *   <li>1007 11:0x 的远程接近只写在 {@link adris.altoclef.tasks.movement.SubBotBootstrapTask}
 *       内部、且只认 {@code leaderPort} 那一台 —— 换个人跟就失效。</li>
 * </ul>
 * 而"主 bot 自己知道自己在哪"这件事，通过 {@code /status.pos} 已经是现成的跨进程信息源
 * （同机 localhost，不新增对外暴露面）。本类把它做成<b>通用能力</b>：任何 bot 跟随/会合
 * 任何本机 bot 都走同一条路，不再依赖"谁是 leaderPort"。
 *
 * <h3>单一判定源</h3>
 * "本地坐标不可用 / 已陈旧时该不该改走远程、走哪儿"这个判断只写在
 * {@link #remoteNav} 里，{@code FollowPlayerTask} 与 {@code SubBotBootstrapTask}
 * 共用 —— 禁止在调用侧各自复刻一套阈值。
 *
 * <h3>边界（刻意不做）</h3>
 * <ul>
 *   <li><b>只覆盖本机 bot</b>：能回答坐标的前提是对方暴露了 bridge {@code /status}。
 *       真人玩家不跑本模组 ⇒ 永远拿不到坐标，仍受 48 格物理限制（原版协议无"按名字查坐标"
 *       的请求包，见 detail/43）。</li>
 *   <li><b>跨维度不做</b>：同 XZ 在下界与主世界不是同一地点，乱走是负收益。由
 *       {@link #remoteNav} 直接判空（调用方已有的"跨维度不追"语义不变）。</li>
 *   <li>不做 UDP 广播 / 不做中心注册表：四实例共用一台机器，扫端口是最短路径。</li>
 * </ul>
 */
public final class PeerPosWatcher {

    /** 轮询间隔。坐标是秒级变化，2s 足够灵敏又几乎不耗资源（局部回环，载荷 ~1KB）。 */
    private static final long POLL_INTERVAL_MS = 2_000;

    /** 单次 HTTP 超时 —— 必须远小于轮询间隔。端口没人监听时是立即 RST，不会真的等满。 */
    private static final int TIMEOUT_MS = 1_500;

    /** 坐标可用鲜度上限。超过视为陈旧（对面关了/卡了/网络抖动），不拿来当导航目标。 */
    public static final long MAX_AGE_MS = 10_000;

    /**
     * 本地"最后已知坐标"与 peer 上报坐标之差在此以内 → 认为本地信息够用，走常规跟随。
     * 作用有二：① 压掉"本地有旧坐标但人已走远"的错向导航；② 压掉边界处本地/远程反复切换。
     * 16 格来自实测口径（实体 48 格 + 2s 上报延迟按疾跑 5.6 m/s 计）。
     */
    public static final double TRUST_LOCAL_DIST = 16.0;

    /**
     * 远程目标移动超过该距离才重建导航任务 —— 否则每 2s 一次轮询都 new 一个
     * {@code GetToBlockTask}，会不停打断 baritone 已算好的路径。
     */
    public static final double RETARGET_DIST = 8.0;

    /**
     * {@code peerPorts} 未配置时的默认扫描窗口。与既有四实例端口定档一致
     * （根 8760 / F1 8761 / F2 8762 / F3 8763），留余量到 8769 供临时多开。
     */
    private static final int DEFAULT_PORT_FROM = 8760;
    private static final int DEFAULT_PORT_TO = 8769;

    /** 远程导航目标：坐标 + 维度（维度恒等于调用方当前维度，见 {@link #remoteNav}）。 */
    public record Nav(Vec3d pos, Dimension dim) {
        /** 取整格坐标（导航任务按方块寻址）。 */
        public BlockPos blockPos() {
            return new BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
        }
    }

    private record Peer(String name, Vec3d pos, Dimension dim, long ts, int port) {
        boolean fresh(long now) {
            return now - ts <= MAX_AGE_MS;
        }
    }

    /** 玩家名（小写）→ 最近一次上报。玩家名大小写不敏感（MC 账号名本身大小写不敏感）。 */
    private static final Map<String, Peer> _peers = new ConcurrentHashMap<>();

    private static volatile boolean _started = false;
    private static volatile int _selfPort = 0;
    private static volatile List<Integer> _ports = List.of();

    private PeerPosWatcher() {
    }

    /**
     * 幂等启动（在每个 bot 实例的 bridge 起来时调用；主/次 bot 都启动 —— 主 bot 也可能
     * 需要 {@code @follow} 某个次 bot）。
     *
     * @param selfPort   本实例端口，从扫描列表里排除（自己不需要问自己）
     * @param configured {@code bridge.json} 的 {@code peerPorts}；空 ⇒ 用默认窗口
     */
    public static synchronized void ensureStarted(int selfPort, List<Integer> configured) {
        _selfPort = selfPort;
        List<Integer> ports = new ArrayList<>();
        if (configured != null && !configured.isEmpty()) {
            ports.addAll(configured);
        } else {
            for (int p = DEFAULT_PORT_FROM; p <= DEFAULT_PORT_TO; p++) ports.add(p);
        }
        ports.removeIf(p -> p == null || p <= 0 || p == selfPort);
        _ports = List.copyOf(ports);
        if (_started) {
            Debug.logMessage("[PEER-POS] 端口表已更新: self=" + selfPort + ", peers=" + _ports);
            return;
        }
        _started = true;
        Thread t = new Thread(PeerPosWatcher::loop, "peer-pos-watcher");
        t.setDaemon(true);   // 绝不阻止 JVM 退出
        t.start();
        Debug.logMessage("[PEER-POS] 坐标共享已启用: self=" + selfPort + ", peers=" + _ports);
    }

    /** 是否已启用（未启用时所有查询恒空 ⇒ 调用方行为与旧版一致）。 */
    public static boolean enabled() {
        return _started;
    }

    /** 最近一次上报的坐标 + 维度；无记录或已陈旧 ⇒ 空。 */
    public static Optional<Nav> navOf(String playerName) {
        Peer p = fresh(playerName);
        return p == null ? Optional.empty() : Optional.of(new Nav(p.pos(), p.dim()));
    }

    /** 距该玩家上次上报的毫秒数；无记录 = {@link Long#MAX_VALUE}。用于日志/诊断。 */
    public static long ageMsOf(String playerName) {
        if (playerName == null) return Long.MAX_VALUE;
        Peer p = _peers.get(key(playerName));
        return p == null ? Long.MAX_VALUE : System.currentTimeMillis() - p.ts();
    }

    /** 该玩家所在的 peer 端口；无记录 = {@code 0}。 */
    public static int portOf(String playerName) {
        if (playerName == null) return 0;
        Peer p = _peers.get(key(playerName));
        return p == null ? 0 : p.port();
    }

    /**
     * <b>唯一判定源</b>：本地坐标不可用（或明显陈旧）时，是否应改走 peer 上报坐标。
     *
     * <p>返回空（= 走常规跟随）的四种情形：① 无该玩家的新鲜上报（> {@link #MAX_AGE_MS}
     * 或从没上报过）；② 维度不同（跨维度不追，交回既有跟丢路径）；③ 本地有坐标且与上报
     * 坐标相差 ≤ {@link #TRUST_LOCAL_DIST}（本地够用，不必远程补位）；④ 本类未启用。
     *
     * <p>注意 ③ 顺带覆盖了"目标当前可见"的情形：对方可见时本地坐标每拍刷新、必然与
     * 其自报坐标接近 ⇒ 本方法返回空 ⇒ 行为与旧实现完全一致。
     *
     * @param playerName 目标游戏内玩家名
     * @param localPos   本地"最后已知坐标"（{@code EntityTracker.getPlayerMostRecentPosition}）
     * @param currentDim 当前所在维度
     */
    public static Optional<Nav> remoteNav(String playerName, Optional<Vec3d> localPos, Dimension currentDim) {
        if (!_started || playerName == null || currentDim == null) return Optional.empty();
        Peer p = fresh(playerName);
        if (p == null || p.dim() != currentDim) return Optional.empty();
        if (localPos != null && localPos.isPresent()
                && localPos.get().squaredDistanceTo(p.pos()) <= TRUST_LOCAL_DIST * TRUST_LOCAL_DIST) {
            return Optional.empty();
        }
        return Optional.of(new Nav(p.pos(), p.dim()));
    }

    // ==================================================================== //
    // 后台轮询
    // ==================================================================== //

    private static Peer fresh(String playerName) {
        if (!_started || playerName == null) return null;
        Peer p = _peers.get(key(playerName));
        if (p == null) return null;
        return p.fresh(System.currentTimeMillis()) ? p : null;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static void loop() {
        while (true) {
            try {
                pollAll();
            } catch (Throwable ignored) {
                // 单轮失败不影响下一轮；陈旧度由 fresh() 表达
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void pollAll() {
        for (int port : _ports) {
            try {
                String json = httpGet("http://127.0.0.1:" + port + "/status");
                String name = StatusJson.parsePlayer(json);
                if (name == null) continue;                 // 旧 jar 还没有 player 键 → 跳过
                StatusJson.Pos p = StatusJson.parsePos(json);
                if (p == null) continue;
                _peers.put(key(name), new Peer(name, p.pos(), p.dim(), System.currentTimeMillis(), port));
            } catch (Throwable ignored) {
                // 端口没人监听 / 对面未在游戏内：跳过（本实例自己的端口已在 ensureStarted 排除）
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
