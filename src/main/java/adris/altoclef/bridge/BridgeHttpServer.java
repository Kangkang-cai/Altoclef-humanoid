package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ClientTickEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * bridge 层 HTTP 服务：模组内置的只读感知 + 命令入口。
 *
 * <p>契约 §3.1 / §3.2 的模组侧实现：
 * <ul>
 *   <li>用 JDK 自带 {@link com.sun.net.httpserver.HttpServer}，零额外依赖；</li>
 *   <li>只绑 {@code 127.0.0.1}，端口 + botId 从 {@link BridgeConfig}（ConfigHelper 体系）
 *       读，每实例独立；</li>
 *   <li>三个端点：{@code GET /status}（低频摘要快照）、{@code GET /events}（事件缓冲游标分页）、
 *       {@code POST /command}（命令入口，转交 {@link CommandExecutor}）；</li>
 *   <li>全部 handler 不在 HTTP 线程触碰游戏状态：查询走"客户端线程定期写入 volatile 快照"
 *       模式，命令用 {@code MinecraftClient.getInstance().execute(...)} 派发回客户端线程。</li>
 * </ul>
 *
 * <p>生命周期：{@link AltoClef#onInitializeLoad()} 末尾调 {@link #start}；进程退出时由 HttpServer
 * 自带的接受线程兜底。重复 start 幂等（先 stop 旧的）。
 */
public final class BridgeHttpServer {

    /** 快照刷新间隔（墙钟 ms）。5000 = 5s，落在契约 §3.3 "5~10s 低频"区间下沿。 */
    private static final long SNAPSHOT_INTERVAL_MS = 5000;
    /** 待聚合事件刷出间隔（墙钟 ms）。1000 = 1s，让 3s 聚合窗口到期的事件尽快可见。 */
    private static final long FLUSH_INTERVAL_MS = 1000;
    /** POST /command body 上限，防滥用。 */
    private static final int MAX_COMMAND_BODY_BYTES = 4096;

    private static volatile BridgeHttpServer _instance;

    private final AltoClef _mod;
    private final int _port;
    private final String _botId;
    private final SummaryGenerator _gen = new SummaryGenerator();
    private final EventBuffer _buffer;
    private final EventCollector _collector;

    /** 客户端线程定期写入，HTTP 线程只读。 */
    private volatile BotSnapshot _snapshot;
    private HttpServer _server;
    private Subscription<ClientTickEvent> _tickSub;
    private long _lastFlushMs = 0;
    private long _lastSnapshotMs = 0;

    private BridgeHttpServer(AltoClef mod, BridgeConfig cfg) {
        this._mod = mod;
        this._port = cfg.port;
        this._botId = cfg.botId;
        this._buffer = new EventBuffer(cfg.botId);
        this._collector = new EventCollector(mod, _buffer, cfg.botId);
        // 初始快照：离线占位，避免 /status 在首次 tick 前返回 null
        this._snapshot = BotSnapshot.offline(cfg.botId);
    }

    /** 启动 bridge（幂等：重复调用会先停掉旧实例）。在 AltoClef.onInitializeLoad 末尾调用。 */
    public static synchronized void start(AltoClef mod) {
        if (mod == null) return;
        BridgeConfig cfg;
        try {
            cfg = BridgeConfig.getInstance();
        } catch (Throwable t) {
            Debug.logWarning("[Bridge] 配置加载失败，bridge 不启动: " + t);
            return;
        }
        if (cfg.port <= 0) {
            Debug.logMessage("[Bridge] port<=0，bridge 不启动（配置关闭）");
            return;
        }
        stop();
        try {
            BridgeHttpServer s = new BridgeHttpServer(mod, cfg);
            s.startInternal();
            _instance = s;
        } catch (Throwable t) {
            Debug.logWarning("[Bridge] 启动失败: " + t);
        }
    }

    /** 停止 bridge（幂等）。 */
    public static synchronized void stop() {
        BridgeHttpServer s = _instance;
        if (s == null) return;
        _instance = null;
        s.stopInternal();
    }

    private void startInternal() throws IOException {
        // 只绑 127.0.0.1：bridge 是 localhost-only 桥，绝不对外暴露。
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetSocketAddress addr = new InetSocketAddress(loopback, _port);
        _server = HttpServer.create(addr, 0);
        _server.createContext("/status", new StatusHandler());
        _server.createContext("/events", new EventsHandler());
        _server.createContext("/command", new CommandHandler());
        // 2 线程固定池：低频轮询足够，且一个慢请求不会冻死另一个。
        _server.setExecutor(Executors.newFixedThreadPool(2));
        _server.start();

        // 客户端线程定期刷新快照 + 刷出待聚合事件
        _tickSub = EventBus.subscribe(ClientTickEvent.class, e -> onClientTick());
        // 事件采集订阅（TaskFinishedEvent / ChatMessageEvent + 背包增量）
        _collector.subscribe();

        Debug.logMessage("[Bridge] 已启动: 127.0.0.1:" + _port + " botId=" + _botId
                + " (endpoints: GET /status, GET /events?cursor=, POST /command)");
    }

    private void stopInternal() {
        if (_tickSub != null) {
            EventBus.unsubscribe(_tickSub);
            _tickSub = null;
        }
        if (_collector != null) {
            _collector.dispose();
        }
        if (_server != null) {
            _server.stop(0); // 立即停（0s 等待）
            _server = null;
        }
        Debug.logMessage("[Bridge] 已停止 (port=" + _port + ")");
    }

    /**
     * 客户端线程 tick 钩子：用墙钟节流，每 1s 刷一次待聚合事件，每 5s 重建一次状态快照。
     * 跑在 ClientTickEvent 发布线程（= 渲染线程），读 player/ taskRunner 安全。
     */
    private void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - _lastFlushMs >= FLUSH_INTERVAL_MS) {
            _lastFlushMs = now;
            _buffer.flushPending();
        }
        if (now - _lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
            _lastSnapshotMs = now;
            _snapshot = BotSnapshot.snapshot(_mod, _gen, _botId);
        }
    }

    // ===================== Handlers =====================

    /** GET /status → 返回当前快照 JSON。 */
    private final class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ensureGet(ex)) return;
            BotSnapshot s = _snapshot; // volatile 读，HTTP 线程不碰游戏状态
            if (s == null) s = BotSnapshot.offline(_botId);
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            BridgeJson.str(sb, "bot_id"); sb.append(':'); BridgeJson.str(sb, s.botId);
            sb.append(','); BridgeJson.str(sb, "ts"); sb.append(':'); BridgeJson.str(sb, s.ts);
            sb.append(','); BridgeJson.str(sb, "summary"); sb.append(':'); BridgeJson.str(sb, s.summary);
            sb.append(','); BridgeJson.str(sb, "task"); sb.append(':'); BridgeJson.str(sb, s.task);
            sb.append(','); BridgeJson.str(sb, "pos"); sb.append(':');
            sb.append("{\"x\":").append(BridgeJson.f1(s.x))
              .append(",\"y\":").append(BridgeJson.f1(s.y))
              .append(",\"z\":").append(BridgeJson.f1(s.z))
              .append(","); BridgeJson.str(sb, "dim"); sb.append(':'); BridgeJson.str(sb, s.dim);
            sb.append('}');
            sb.append(','); BridgeJson.str(sb, "health"); sb.append(':'); sb.append(BridgeJson.f1(s.health));
            // ---- 契约扩展字段（顺序对齐对端解析样例）：hunger/level/equipment/food/foodCount/keyItems ----
            sb.append(','); BridgeJson.str(sb, "hunger"); sb.append(':').append(BridgeJson.i(s.hunger));
            sb.append(','); BridgeJson.str(sb, "level"); sb.append(':').append(BridgeJson.i(s.level));
            sb.append(','); BridgeJson.str(sb, "equipment"); sb.append(':'); BridgeJson.str(sb, s.equipment);
            sb.append(','); BridgeJson.str(sb, "food"); sb.append(':'); BridgeJson.str(sb, s.food);
            sb.append(','); BridgeJson.str(sb, "foodCount"); sb.append(':').append(BridgeJson.i(s.foodCount));
            sb.append(','); BridgeJson.str(sb, "keyItems"); sb.append(':'); BridgeJson.str(sb, s.keyItems);
            sb.append(','); BridgeJson.str(sb, "online"); sb.append(':'); sb.append(Boolean.toString(s.online));
            sb.append('}');
            sendJson(ex, 200, sb.toString());
        }
    }

    /** GET /events?cursor=<string> → 返回该游标之后的事件。 */
    private final class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!ensureGet(ex)) return;
            String cursor = parseQuery(ex.getRequestURI().getQuery(), "cursor");
            EventBuffer.PollResult r = _buffer.poll(cursor); // 内部会 flushPending
            StringBuilder sb = new StringBuilder(512);
            sb.append('{');
            BridgeJson.str(sb, "events"); sb.append(":[");
            List<BridgeEvent> evs = r.events;
            for (int i = 0; i < evs.size(); i++) {
                if (i > 0) sb.append(',');
                BridgeEvent e = evs.get(i);
                sb.append('{');
                sb.append("\"id\":"); sb.append(Long.toString(e.id));
                sb.append(','); BridgeJson.str(sb, "bot_id"); sb.append(':'); BridgeJson.str(sb, e.botId);
                sb.append(','); BridgeJson.str(sb, "ts"); sb.append(':'); BridgeJson.str(sb, e.ts);
                sb.append(','); BridgeJson.str(sb, "type"); sb.append(':'); BridgeJson.str(sb, e.type);
                sb.append(','); BridgeJson.str(sb, "text"); sb.append(':'); BridgeJson.str(sb, e.text);
                sb.append(','); BridgeJson.str(sb, "strong"); sb.append(':'); sb.append(Boolean.toString(e.strong));
                sb.append('}');
            }
            sb.append("],");
            BridgeJson.str(sb, "nextCursor"); sb.append(':'); BridgeJson.str(sb, r.nextCursor);
            sb.append('}');
            sendJson(ex, 200, sb.toString());
        }
    }

    /** POST /command {"cmd":"@goto 100 64 100"} → 转交 CommandExecutor 执行。 */
    private final class CommandHandler implements HttpHandler {
        // 容忍空格的 cmd 提取正则；匹配 "cmd":"<value>"，value 支持 \" \\ 等转义。
        private final Pattern CMD_RE =
                Pattern.compile("\"cmd\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if (!"POST".equalsIgnoreCase(method)) {
                sendJson(ex, 405, "{\"ok\":false,\"error\":\"method not allowed, use POST\"}");
                return;
            }
            byte[] body;
            try {
                body = ex.getRequestBody().readNBytes(MAX_COMMAND_BODY_BYTES);
            } catch (Throwable t) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"failed to read body\"}");
                return;
            }
            if (body == null || body.length == 0) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"empty body\"}");
                return;
            }
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            Matcher m = CMD_RE.matcher(bodyStr);
            if (!m.find()) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing \\\"cmd\\\" field\"}");
                return;
            }
            String cmd = unescape(m.group(1));
            if (cmd == null || cmd.isEmpty()) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"empty cmd\"}");
                return;
            }

            // 校验（HTTP 线程安全：只读 _commandSheet + prefix 字符串，不碰游戏状态）
            CommandExecutor executor = AltoClef.getCommandExecutor();
            if (executor == null) {
                sendJson(ex, 503, "{\"ok\":false,\"error\":\"command executor not ready\"}");
                return;
            }
            if (!executor.isClientCommand(cmd)) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"missing command prefix\"}");
                return;
            }
            String firstToken = firstToken(cmd);
            if (firstToken != null) {
                Command registered = executor.get(firstToken);
                if (registered == null) {
                    sendJson(ex, 404, "{\"ok\":false,\"error\":\"unknown command: "
                            + jsonEscape(firstToken) + "\"}");
                    return;
                }
            }

            // 派发回客户端线程执行（不在 HTTP 线程碰游戏状态）
            MinecraftClient.getInstance().execute(() -> {
                try {
                    executor.execute(cmd);
                } catch (Throwable t) {
                    Debug.logWarning("[Bridge] command dispatch threw: " + t);
                }
            });
            sendJson(ex, 200, "{\"ok\":true}");
        }

        /** 取去掉 prefix 后的第一个 token（命令名）。prefix 取自 mod settings（跨包可见）。 */
        private String firstToken(String cmd) {
            try {
                String prefix = _mod.getModSettings().getCommandPrefix();
                if (prefix == null) prefix = "";
                String noPrefix = cmd.startsWith(prefix) ? cmd.substring(prefix.length()).trim() : cmd.trim();
                int sp = noPrefix.indexOf(' ');
                int semi = noPrefix.indexOf(';');
                int end = -1;
                if (sp == -1) end = semi;
                else if (semi == -1) end = sp;
                else end = Math.min(sp, semi);
                String name = end == -1 ? noPrefix : noPrefix.substring(0, end);
                return name.isEmpty() ? null : name;
            } catch (Throwable t) {
                return null;
            }
        }

        private String jsonEscape(String s) {
            StringBuilder sb = new StringBuilder();
            BridgeJson.str(sb, s);
            return sb.toString();
        }
    }

    // ===================== 工具 =====================

    /** 只接受 GET，否则 405。 */
    private boolean ensureGet(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"method not allowed, use GET\"}");
            return false;
        }
        return true;
    }

    /** 解析 query string 里某个 key 的值（URL-decoded）。 */
    private static String parseQuery(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq == -1 ? pair : pair.substring(0, eq);
            String v = eq == -1 ? "" : pair.substring(eq + 1);
            if (k.equals(key)) {
                try {
                    return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
                } catch (Throwable t) {
                    return v;
                }
            }
        }
        return "";
    }

    private static void sendJson(HttpExchange ex, int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // localhost 桥，加宽松 CORS 方便浏览器直连手测（对端走代理不需要）
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        try {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
            // 客户端断开等，忽略
        } catch (Throwable t) {
            Debug.logWarning("[Bridge] sendJson threw: " + t);
        } finally {
            ex.close();
        }
    }

    /** 极简 JSON 字符串反转义（把 \" \\ \n \r \t 还原）。 */
    private static String unescape(String s) {
        if (s == null) return null;
        if (s.indexOf('\\') == -1) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> { sb.append('\\'); sb.append(n); }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
