package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.Dimension;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.util.math.Vec3d;

/**
 * (2026-10-09 用户拍板"跨维度+精确坐标") <b>玩家维度/坐标 RCON oracle</b>：
 * 目标玩家在服务器里但超出客户端实体跟踪范围（~48 格，跨维度必超）时，
 * 通过服务端 RCON 查 {@code data get entity <名> Dimension / Pos} 拿到其
 * <b>真实维度与坐标</b>，让 {@code FollowPlayerTask} 能跨维度派发、同维度精确导航。
 *
 * <h3>为什么存在</h3>
 * 原版协议没有"按名字查坐标"的请求包，客户端只下发跟踪范围内实体 ——
 * {@link adris.altoclef.bridge.PeerPosWatcher} 只覆盖本机 bot（真人不暴露 /status）。
 * 自建专用服已有 RCON（隐形光源在用，先例存在），它是唯一可靠的真人坐标源。
 * 第三方服 RCON 不可达 ⇒ 查询失败进入退避，调用方落回既有等待语义（行为不变）。
 *
 * <h3>线程模型</h3>
 * RCON 网络调用（2s connect 超时）<b>绝不占用游戏线程</b>：单 daemon 后台线程执行，
 * 结果写入 {@code volatile} 缓存；游戏线程只做读缓存 + "是否需要再查"的廉价判定。
 * 查询节流 {@link #QUERY_MIN_INTERVAL_MS}，失败退避 {@link #FAILURE_BACKOFF_MS}。
 */
public final class RconPlayerOracle {

    /** 同一玩家的查询最小间隔（坐标秒级变化，2s 足够灵敏）。 */
    private static final long QUERY_MIN_INTERVAL_MS = 2_000;

    /** 快照鲜度上限：超过视为陈旧，调用方不得当导航目标。 */
    public static final long FRESH_MS = 5_000;

    /** 查询失败（RCON 不可达/拒绝）后的退避：期间不再发起查询，避免刷日志刷连接。 */
    private static final long FAILURE_BACKOFF_MS = 30_000;

    /**
     * 一次查询包含两条 RCON 命令（短连接 ×2），留 {@link #FRESH_MS} 一半的裕量；
     * 超过视为"这次查询挂了"，允许重新发起。
     */
    private static final long IN_FLIGHT_TIMEOUT_MS = 2_500;

    /** 玩家名（小写）→ 最近快照。 */
    private static final Map<String, Snapshot> _cache = new ConcurrentHashMap<>();
    /** 玩家名（小写）→ 查询发起时间；0 = 无在途查询。 */
    private static final Map<String, Long> _inFlight = new ConcurrentHashMap<>();
    /** 全局失败退避截止时间（单机单服，不按玩家区分）。 */
    private static volatile long _backoffUntilMs = 0;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rcon-player-oracle");
        t.setDaemon(true);   // 绝不阻止 JVM 退出
        return t;
    });

    /** 一次成功的维度+坐标快照。 */
    public record Snapshot(Vec3d pos, Dimension dim, long ts) {
        /** 取整格坐标（导航任务按方块寻址）。 */
        public net.minecraft.util.math.BlockPos blockPos() {
            return new net.minecraft.util.math.BlockPos(
                    (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
        }
    }

    private RconPlayerOracle() {
    }

    /**
     * 请求刷新一名玩家的快照（游戏线程调用，<b>恒不阻塞</b>）：
     * 无在途查询、缓存超过最小间隔、且不在失败退避期 ⇒ 投递后台查询。
     */
    public static void request(AltoClef mod, String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now < _backoffUntilMs) return;
        String key = key(playerName);
        Snapshot s = _cache.get(key);
        if (s != null && now - s.ts < QUERY_MIN_INTERVAL_MS) return;
        Long since = _inFlight.get(key);
        if (since != null && now - since < IN_FLIGHT_TIMEOUT_MS) return;

        final String host = mod.getModSettings().getRconHost();
        final int port = mod.getModSettings().getRconPort();
        final String password = mod.getModSettings().getRconPassword();
        final String name = playerName;
        _inFlight.put(key, now);
        EXECUTOR.execute(() -> {
            try {
                String dimFeedback = RconClient.sendCommand(host, port, password,
                        "data get entity " + name + " Dimension");
                String posFeedback = RconClient.sendCommand(host, port, password,
                        "data get entity " + name + " Pos");
                Snapshot snapshot = parse(name, dimFeedback, posFeedback);
                if (snapshot != null) {
                    _cache.put(key, snapshot);
                } else {
                    // 玩家不在服务器（No entity was found）—— 清掉旧快照防止拿废坐标导航。
                    _cache.remove(key);
                }
            } catch (IOException e) {
                // RCON 不可达（第三方服/服务端未开 rcon）：退避，调用方落回既有等待语义。
                _backoffUntilMs = System.currentTimeMillis() + FAILURE_BACKOFF_MS;
                Debug.logWarning("[RconOracle] 查询 " + name + " 失败 ("
                        + e + ")，" + (FAILURE_BACKOFF_MS / 1000) + "s 内不再尝试");
            } finally {
                _inFlight.remove(key);
            }
        });
    }

    /** 最近一次成功快照；无记录或已陈旧 ⇒ 空。 */
    public static Optional<Snapshot> snapshotOf(String playerName) {
        if (playerName == null) return Optional.empty();
        Snapshot s = _cache.get(key(playerName));
        if (s == null) return Optional.empty();
        if (System.currentTimeMillis() - s.ts > FRESH_MS) return Optional.empty();
        return Optional.of(s);
    }

    /** 是否存在该玩家的任何快照（含陈旧，仅用于日志/诊断）。 */
    public static boolean hasEverSeen(String playerName) {
        return playerName != null && _cache.containsKey(key(playerName));
    }

    // ==================================================================== //
    // 解析（1.18.2 原版 data 命令回包格式）
    // ==================================================================== //

    /** {@code Tseng has the following entity data: "minecraft:the_nether"} */
    private static final Pattern DIM_PATTERN = Pattern.compile("\"minecraft:(\\w+)\"");
    /** {@code Tseng has the following entity data: [123.4d, 64.0d, -5.6d]} */
    private static final Pattern POS_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)d");

    private static Snapshot parse(String name, String dimFeedback, String posFeedback) {
        if (dimFeedback == null || posFeedback == null) return null;
        // 玩家离线/不存在: "No entity was found" / "No entity was found"
        if (dimFeedback.contains("No entity was found")
                || posFeedback.contains("No entity was found")) {
            return null;
        }
        Matcher dm = DIM_PATTERN.matcher(dimFeedback);
        if (!dm.find()) return null;
        Dimension dim = switch (dm.group(1)) {
            case "overworld" -> Dimension.OVERWORLD;
            case "the_nether" -> Dimension.NETHER;
            case "the_end" -> Dimension.END;
            default -> null;
        };
        if (dim == null) return null;

        Matcher pm = POS_PATTERN.matcher(posFeedback);
        double[] xyz = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!pm.find()) return null;
            xyz[i] = Double.parseDouble(pm.group(1));
        }
        return new Snapshot(new Vec3d(xyz[0], xyz[1], xyz[2]), dim, System.currentTimeMillis());
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
