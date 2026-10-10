package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
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
    /**
     * (2026-10-06 多 bot 随行) 本实例角色 {@code "main"|"sub"}，取自
     * {@link BridgeConfig#role}。实例级静态配置，不随世界/任务变化 ——
     * 因此只在这里存一份并在 {@code /status} 输出，不进每帧采样的 BotSnapshot。
     */
    private final String _role;
    private final SummaryGenerator _gen = new SummaryGenerator();
    private final EventBuffer _buffer;
    private final EventCollector _collector;
    /** 阶段三行动派发器：白名单/限流/回执/主线干预/现场执行状态。 */
    private final ActionDispatcher _dispatcher;
    /** botStatus 存活判据（位置+背包+任务态 30s 全不变 → stuck）。 */
    private final LivenessDigest _liveness;
    /** 背包读失败防抖：EventCollector 采样判定 + 快照沿用，两处共用同一实例。 */
    private final InventoryGuard _invGuard = new InventoryGuard();

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
        this._role = (cfg.role == null || cfg.role.trim().isEmpty()) ? "main" : cfg.role.trim().toLowerCase(java.util.Locale.ROOT);
        this._buffer = new EventBuffer(cfg.botId);
        this._dispatcher = new ActionDispatcher(mod, _buffer);
        this._liveness = new LivenessDigest(mod, _buffer, _dispatcher);
        this._collector = new EventCollector(mod, _buffer, cfg.botId, this._invGuard, this._dispatcher);
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

    /**
     * 事件缓冲访问（NamedLandmarks 的 landmark_saved/failed 回执用）。
     * bridge 未启动返回 null —— 调用方跳过事件（游戏内手动 @remember 仍可用）。
     */
    static EventBuffer buffer() {
        BridgeHttpServer s = _instance;
        return s == null ? null : s._buffer;
    }

    /**
     * 聊天栏入口（2026-10-10）：把 {@code @pause_main} / {@code @resume_main} 伪命令送进
     * {@link ActionDispatcher} <b>同一条流水线</b>（rateCheck → accept → runOnClientThread），
     * 保证与 HTTP {@code POST /command} 入口共享同一套状态簿记（{@code _paused} 令牌 /
     * activeAction 现场状态 / action_accepted+action_done 事件），聊天与 AI 两种来源无行为差异。
     *
     * <p>动机（用户 1010）：伪命令按阶段三设计"不在 CommandExecutor 注册"，聊天栏输入
     * 静默失败（CommandException 只进日志，聊天框无任何回显），人工测试只能 curl。
     * 现注册两个同名聊天命令（{@code PauseMainCommand}/{@code ResumeMainCommand}）转发到此。
     *
     * <p>线程：调用方 = 聊天命令 = 客户端线程；这里仍包一层 {@code client.execute} 与 HTTP
     * 路径同构（已在客户端线程时立即执行）。限流与 AI 共享同一额度——人工测试高频时也会
     * 被拦，这是有意的（同一台账才好对账）。
     *
     * @param token 伪命令 token（"pause_main" / "resume_main"）
     * @return 给聊天栏的回执文案；null = token 不是伪命令（调用方不应发生，防御用）
     */
    public static String dispatchPseudoFromChat(String token) {
        BridgeHttpServer s = _instance;
        if (s == null) {
            return "bridge 未启动（port<=0 或启动失败），主线干预命令不可用";
        }
        boolean pause = ActionDispatcher.PAUSE.equals(token);
        boolean resume = ActionDispatcher.RESUME.equals(token);
        if (!pause && !resume) return null;
        if (!BridgeConfig.getInstance().enablePauseResume) {
            return "bridge.json enablePauseResume=false，主线干预命令已禁用";
        }
        int retryAfterSec = s._dispatcher.rateCheckAndRecord();
        if (retryAfterSec > 0) {
            return "限流中：与 AI 行动共享每分钟额度，" + retryAfterSec + "s 后可再试";
        }
        String cmd = "@" + token;
        String actionId = s._dispatcher.allocateActionId(null);
        s._dispatcher.accept(actionId, token, cmd);
        MinecraftClient.getInstance().execute(() ->
                s._dispatcher.runOnClientThread(cmd, token, actionId));
        return (pause ? "已受理 @pause_main" : "已受理 @resume_main") + " [" + actionId + "]，回执见 /events";
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
        // 行动回执订阅（action_done 关联顶层任务结束）
        _dispatcher.subscribe();

        Debug.logMessage("[Bridge] 已启动: 127.0.0.1:" + _port + " botId=" + _botId
                + " (endpoints: GET /status, GET /events?cursor=, POST /command)");

        // (2026-10-07 bot 间坐标共享) 主/次 bot 都启动：主 bot 也可能需要 @follow 某个次 bot。
        // 自己端口由 PeerPosWatcher 内部排除；peerPorts 留空则用默认窗口 8760~8769。
        try {
            PeerPosWatcher.ensureStarted(_port, BridgeConfig.getInstance().peerPorts);
        } catch (Throwable t) {
            Debug.logWarning("[PEER-POS] 启动失败（坐标共享不可用，跟随退回 48 格内）: " + t);
        }
    }

    private void stopInternal() {
        if (_tickSub != null) {
            EventBus.unsubscribe(_tickSub);
            _tickSub = null;
        }
        if (_collector != null) {
            _collector.dispose();
        }
        if (_dispatcher != null) {
            _dispatcher.dispose();
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
        // 现场状态评估（每 tick，不节流）：活跃行动进度 + botStatus 存活判据
        if (_dispatcher != null) _dispatcher.onClientTick();
        if (_liveness != null) _liveness.tick();
        if (now - _lastFlushMs >= FLUSH_INTERVAL_MS) {
            _lastFlushMs = now;
            _buffer.flushPending();
        }
        if (now - _lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
            _lastSnapshotMs = now;
            _snapshot = BotSnapshot.snapshot(_mod, _gen, _botId, _invGuard,
                    _dispatcher == null ? null : _dispatcher.activeAction(),
                    _liveness == null ? "" : _liveness.status(),
                    _liveness == null ? "" : _liveness.reason());
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
            // (2026-10-07 bot 间坐标共享) 游戏内玩家名 —— 同伴据此把"要跟的人"翻译成
            // "去哪个端口取坐标"（@follow/@give 都按游戏内名寻址，botId 不行）。
            sb.append(','); BridgeJson.str(sb, "player"); sb.append(':'); BridgeJson.str(sb, s.player);
            // (2026-10-07 follow 关系治理) 本 bot 此刻在跟谁（空串 = 没在跟随）。同伴据此检测
            // "对向跟随"（A 跟 B 且 B 跟 A）并让临时关系一侧退让 —— 见 FollowRelation。
            sb.append(','); BridgeJson.str(sb, "following"); sb.append(':'); BridgeJson.str(sb, s.following);
            // (2026-10-06 多 bot 随行) 角色: "main" | "sub"。对端据此区分主次 bot
            // （把"跟着谁走""向谁要料""目标量"这几类决策分开）。取值恒非空, 不省略。
            sb.append(','); BridgeJson.str(sb, "role"); sb.append(':'); BridgeJson.str(sb, _role);
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
            // (1002 16:5x 对端新增) 主手此刻拿着的物品中文名；空手空串
            sb.append(','); BridgeJson.str(sb, "holding"); sb.append(':'); BridgeJson.str(sb, s.holding);
            sb.append(','); BridgeJson.str(sb, "food"); sb.append(':'); BridgeJson.str(sb, s.food);
            sb.append(','); BridgeJson.str(sb, "foodCount"); sb.append(':').append(BridgeJson.i(s.foodCount));
            sb.append(','); BridgeJson.str(sb, "keyItems"); sb.append(':'); BridgeJson.str(sb, s.keyItems);
            // (2026-10-07) 完整背包【原名】清单（stripItemName 口径 = @give 认的名字）。
            // ⚠️ keyItems 是中文名（对端做变化检测用），对端发 @give 时必须【从这里照抄】原名，
            // 不要凭记忆写 ID（"牛排"→steak 是错的，cooked_beef 才对）。空则整键省略。
            if (s.inventory != null && !s.inventory.isEmpty()) {
                sb.append(','); BridgeJson.str(sb, "inventory"); sb.append(':'); BridgeJson.str(sb, s.inventory);
            }
            // ---- 阶段三感知补全：任务细化 + 环境感知（主线没在跑时省略 mainlineProgress）----
            sb.append(','); BridgeJson.str(sb, "taskDetail"); sb.append(':'); BridgeJson.str(sb, s.taskDetail);
            // (2026-10-06 多 bot 阶段跟随) 主线「大阶段」枚举（BeatPhase）——给次 bot 判"跟不跟"。
            // 刻意不用 taskDetail 当契约: 那是给人看的显示串、随时会改。非主线在跑时为 UNKNOWN。
            sb.append(','); BridgeJson.str(sb, "phase"); sb.append(':');
            BridgeJson.str(sb, BeatMinecraft2Task.getCurrentPhase().name());
            // (2026-10-06 23:1x 用户点名) 次 bot 的「当前目的」—— 让对端看出它在为主 bot 做什么:
            // mode=COLLECT = "流动仓库"（主 bot 缺料就对它发 @give）。只有 role=sub 会写本类,
            // 主 bot / 空闲时 mode=NONE ⇒ 整键省略（对端据此一眼分清哪台是跟班）。
            if (SubBotDuty.mode() != SubBotDuty.Mode.NONE) {
                sb.append(','); BridgeJson.str(sb, "duty"); sb.append(":{");
                BridgeJson.str(sb, "for"); sb.append(':'); BridgeJson.str(sb, SubBotDuty.forLeader());
                sb.append(','); BridgeJson.str(sb, "mode"); sb.append(':'); BridgeJson.str(sb, SubBotDuty.mode().name());
                sb.append(','); BridgeJson.str(sb, "label"); sb.append(':'); BridgeJson.str(sb, SubBotDuty.label());
                sb.append('}');
            }
            if (s.mainlineProgress != null && !s.mainlineProgress.isEmpty()) {
                sb.append(','); BridgeJson.str(sb, "mainlineProgress"); sb.append(':'); BridgeJson.str(sb, s.mainlineProgress);
            }
            sb.append(','); BridgeJson.str(sb, "timeOfDay"); sb.append(':'); BridgeJson.str(sb, s.timeOfDay);
            sb.append(','); BridgeJson.str(sb, "weather"); sb.append(':'); BridgeJson.str(sb, s.weather);
            sb.append(','); BridgeJson.str(sb, "environment"); sb.append(':'); BridgeJson.str(sb, s.environment);
            sb.append(','); BridgeJson.str(sb, "biome"); sb.append(':'); BridgeJson.str(sb, s.biome);
            sb.append(','); BridgeJson.str(sb, "nearbyHostiles"); sb.append(':').append(BridgeJson.i(s.nearbyHostiles));
            // ---- (2026-10-03 重要位置记忆共享, 定稿 §4.1) 空时整键省略 ----
            if (s.landmarks != null && !s.landmarks.isEmpty()) {
                sb.append(','); BridgeJson.str(sb, "landmarks"); sb.append(':').append(s.landmarks);
            }
            // ---- (2026-10-03 多人协调: 谁在线 / 谁在睡) 空时整键省略 ----
            if (s.onlinePlayers != null && !s.onlinePlayers.isEmpty()) {
                sb.append(','); BridgeJson.str(sb, "onlinePlayers"); sb.append(':'); BridgeJson.str(sb, s.onlinePlayers);
            }
            if (s.sleepingPlayers != null && !s.sleepingPlayers.isEmpty()) {
                sb.append(','); BridgeJson.str(sb, "sleepingPlayers"); sb.append(':'); BridgeJson.str(sb, s.sleepingPlayers);
            }
            // ---- 阶段三感知补全：现场执行状态（在线时才有意义；离线快照省略）----
            if (s.botStatus != null && !s.botStatus.isEmpty()) {
                sb.append(','); BridgeJson.str(sb, "botStatus"); sb.append(':'); BridgeJson.str(sb, s.botStatus);
                if (s.stuckReason != null && !s.stuckReason.isEmpty()) {
                    sb.append(','); BridgeJson.str(sb, "stuckReason"); sb.append(':'); BridgeJson.str(sb, s.stuckReason);
                }
            }
            if (s.activeAction != null) {
                ActiveAction a = s.activeAction;
                sb.append(','); BridgeJson.str(sb, "activeAction"); sb.append(":{");
                BridgeJson.str(sb, "actionId"); sb.append(':'); BridgeJson.str(sb, a.actionId);
                sb.append(','); BridgeJson.str(sb, "cmd"); sb.append(':'); BridgeJson.str(sb, a.cmd);
                sb.append(','); BridgeJson.str(sb, "phase"); sb.append(':'); BridgeJson.str(sb, a.phase);
                sb.append(','); BridgeJson.str(sb, "detail"); sb.append(':'); BridgeJson.str(sb, a.detail);
                sb.append(','); BridgeJson.str(sb, "sinceSec"); sb.append(':').append(BridgeJson.i(a.sinceSec));
                sb.append('}');
            }
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
                if (e.detail != null) {
                    // detail 已是 JSON 片段（ActionDispatcher.detailJson 内部走 BridgeJson 转义）
                    sb.append(','); BridgeJson.str(sb, "detail"); sb.append(':').append(e.detail);
                }
                sb.append('}');
            }
            sb.append("],");
            BridgeJson.str(sb, "nextCursor"); sb.append(':'); BridgeJson.str(sb, r.nextCursor);
            sb.append('}');
            sendJson(ex, 200, sb.toString());
        }
    }

    /** POST /command {"cmd":"@goto 100 64 100","actionId":"a-1"} → 白名单+限流+派发。 */
    private final class CommandHandler implements HttpHandler {
        // 容忍空格的 cmd 提取正则；匹配 "cmd":"<value>"，value 支持 \" \\ 等转义。
        private final Pattern CMD_RE =
                Pattern.compile("\"cmd\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        /** 可选的 actionId（对端生成，用于回执关联）。 */
        private final Pattern ACTION_ID_RE =
                Pattern.compile("\"actionId\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

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

            // 校验链（HTTP 线程安全：只读 _commandSheet + prefix + 配置，不碰游戏状态）
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
            if (firstToken == null) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"empty cmd\"}");
                return;
            }
            // 主线干预伪命令不在 CommandExecutor 注册，由 ActionDispatcher 分流，跳过 404 检查。
            boolean pseudo = ActionDispatcher.PAUSE.equals(firstToken)
                    || ActionDispatcher.RESUME.equals(firstToken);
            if (!pseudo) {
                Command registered = executor.get(firstToken);
                if (registered == null) {
                    sendJson(ex, 404, "{\"ok\":false,\"error\":\"unknown command: "
                            + jsonEscape(firstToken) + "\"}");
                    return;
                }
            }

            // AI 行动白名单（阶段三）：白名单外的命令一律 403
            ActionDispatcher dispatcher = _dispatcher;
            if (!dispatcher.isAllowedToken(firstToken)) {
                sendJson(ex, 403, "{\"ok\":false,\"error\":\"not allowed\",\"reason\":"
                        + jsonEscape("command '" + firstToken + "' is not in the AI action whitelist") + "}");
                return;
            }
            // 限流（只读命令不占额度）
            if (!dispatcher.isReadOnlyToken(firstToken)) {
                int retryAfterSec = dispatcher.rateCheckAndRecord();
                if (retryAfterSec > 0) {
                    sendJson(ex, 429, "{\"ok\":false,\"error\":\"rate limited\",\"retryAfterSec\":"
                            + retryAfterSec + "}");
                    return;
                }
            }

            // 记账 + 同步受理回执（action_accepted 进事件流，让 LLM 下一轮知道话递到了）
            Matcher am = ACTION_ID_RE.matcher(bodyStr);
            String givenActionId = am.find() ? unescape(am.group(1)) : null;
            String actionId = dispatcher.allocateActionId(givenActionId);
            dispatcher.accept(actionId, firstToken, cmd);

            // 派发回客户端线程执行（不在 HTTP 线程碰游戏状态）
            MinecraftClient.getInstance().execute(() -> {
                try {
                    dispatcher.runOnClientThread(cmd, firstToken, actionId);
                } catch (Throwable t) {
                    Debug.logWarning("[Bridge] command dispatch threw: " + t);
                }
            });
            sendJson(ex, 200, "{\"ok\":true,\"actionId\":" + jsonEscape(actionId) + ",\"accepted\":true}");
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
