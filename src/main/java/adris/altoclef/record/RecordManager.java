package adris.altoclef.record;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Settings;
import net.minecraft.client.MinecraftClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * (2026-09-17 数据集录制) 训练数据集导出总控 —— 六通道逐帧全量快照。
 *
 * <h3>挂载点</h3>
 * 由 {@code AltoClef.onClientTick()} 在 {@code _taskRunner.tick()} 与
 * {@code getControllerExtras().applyCombatAim()} <b>之后</b> 调用。这是全 tick 里
 * 唯一"tracker 已刷新 / 任务树已定型 / 动作已下发"三态一致的采样点。
 *
 * <h3>通道 → 文件</h3>
 * <pre>
 * A 时间溯源 + B 任务标签 + C 自身状态 + G 动作信号  → frames.jsonl.gz      (每帧)
 * D 可见世界                                        → perception.jsonl.gz  (每帧)
 * E 记忆快照                                        → memory.jsonl.gz      (变化时才写)
 *   记忆读写/受伤/死亡/任务切换等稀疏事件            → events.jsonl.gz
 *   配置与世界信息 (种子/维度/口径/分级表)            → meta.json
 * </pre>
 *
 * <p>维护这份模块的长期记忆见仓库根目录 {@code RECORDING.md} (设计取舍、坑位清单、
 * 已见的失败模式、维护检查表)。改本模块前先读它。
 *
 * <h3>为什么不在 logs/altoclef/ 下</h3>
 * {@code Debug.pruneOldSessionsOnce} 会把 {@code logs/altoclef/} 里最旧会话目录
 * (KEEP_SESSIONS=10) 整目录删掉 —— 训练语料绝不能放在会被自动清理的地方。
 * 这里放 {@code <游戏运行目录>/record/<会话时间戳>/}。
 *
 * <h3>纪律 (硬性)</h3>
 * <ul>
 *   <li>严格只读: 不调用任何有副作用的 API (尤其绝不重复调 TaskChain.getPriority,
 *       WorldSurvivalChain 那个会 setTask)。</li>
 *   <li>绝不影响行为: 全包 try/catch; 出错只计数, 连续出错直接自禁用, 不重试刷屏。</li>
 *   <li>零开销: 开关关闭时只有一次布尔判断 + null 检查。</li>
 *   <li>写盘异步: 主线程只入队, 队列满丢行计数, 绝不阻塞游戏 tick。</li>
 * </ul>
 */
public final class RecordManager {

    private RecordManager() {}

    /** 连续出错达到该次数即自禁用 (宁可不录, 也绝不拖慢/干扰 bot)。 */
    private static final int MAX_ERRORS_BEFORE_DISABLE = 20;
    private static final int MAX_ERROR_LOGS = 3;

    private static JsonlSink _frames;
    private static JsonlSink _perception;
    private static JsonlSink _memory;
    private static JsonlSink _events;

    private static long _seq = 0;
    private static long _startedAtMs = 0;
    private static int _sessionIndex = 0;
    private static Path _sessionDir = null;
    private static boolean _disabled = false;
    private static String _disabledReason = null;
    private static int _errors = 0;
    private static long _framesQueued = 0;

    /**
     * (2026-09-17 停 bot 不再自动重开) 停 bot (Alt+U / {@code @stop} → TaskRunner.disable)
     * 是"一次 bot 运行"的终点, 但它【不离开世界】—— 若下一 tick 又因 {@code _frames == null}
     * 自动 startSession, 就把"停 bot"变成了一次会话切分。实测 20260917-155653: 16:12:14.233
     * 正常收尾, 41ms 后 (16:12:14.274) 自动开了 -r2, 用户站在世界里没动却又录了 13 分钟。
     * 加闩后: 要重开需 {@code @record on} (clearDisabled) 或离开世界再进来 (见 tick 的
     * {@code !inGame()} 分支)。
     */
    private static boolean _pausedAfterStop = false;

    // (2026-09-17) 同 tick 事件去重集合 (只对 eventThrottled 生效)。每 tick 清空。
    private static final java.util.HashSet<String> _tickEventKeys = new java.util.HashSet<>();

    // (2026-09-17) 派生事件的上次观测值 (受伤/死亡/任务切换)。这些事件都能从
    // frame 流里"算"出来, 但显式记一条能让训练时的分段/索引便宜很多, 代价只是
    // 每 tick 比几个数字。合成/物品变化事件【故意不记】—— 背包逐帧全量已含该信息,
    // 重复导出只是翻倍体积。
    private static double _lastHp = -1;
    private static String _lastDim = null;
    private static boolean _wasDead = false;
    private static String _lastChainName = null;
    private static String _lastLeafClass = null;

    // 通道启用位: 四个通道都已在用。刻意不设开关 —— 少一档配置就少一处"关着的通道
    // 为什么没数据"的排查成本; 要减体积请调 perceptionCaps 或关 recordInventoryPerTick。

    /**
     * 是否正在录制 (供记忆写入点插桩做零成本早退)。热路径上只是一次静态读,
     * 开销可忽略。
     */
    public static boolean isRecording() {
        return _frames != null && !_disabled;
    }

    /** (2026-09-17) 当前帧序号 (供同帧的其它通道对齐到 frames 行)。 */
    static long currentSeq() {
        return _seq;
    }

    /**
     * (2026-09-17) JSON 字符串转义入口 (供 trackers 里的事件代码复用, 避免它们
     * 依赖本包的 package-private 助手)。
     */
    public static void quote(StringBuilder sb, String s) {
        Json.str(sb, s);
    }

    /** 每游戏 tick 调用一次 (由 AltoClef.onClientTick 挂载)。 */
    public static void tick(AltoClef mod) {
        if (_disabled) {
            // (2026-09-17) 出错自禁用后仍要把已开的会话收掉 —— gzip 流不 close
            // 就是半个文件损坏, 之前录的都读不出来。onSessionEnd 幂等。
            onSessionEnd();
            return;
        }
        try {
            Settings s = mod.getModSettings();
            if (s == null || !s.isRecordDatasetEnabled()) {
                // 设置被关掉 (@record off / 文件改回 false / reload_settings):
                // 同样必须走收尾, 让队列排空、gzip 尾部写全、meta 补上统计。
                onSessionEnd();
                return;
            }
            if (!AltoClef.inGame()) {
                // (2026-09-17 会话边界) 离开世界 / 死亡菜单 / 断线: 收尾本次会话。
                // 不收的话 ①退出游戏时 gzip 尾部永远缺失 (20260917-100000-r2 就是
                // 4 个只有 10 字节 gzip 头的残片); ②再进世界 (或换世界) 会往同一个
                // 会话里继续写, 而新世界的 tick 从 0 重算、种子/维度都变了, seq↔t
                // 的对应关系直接作废。再进世界时 startSession 会开一个新目录 (-rN)。
                // 顺带清掉"停 bot"闩: 离开过世界 = 上一次运行已彻底结束。
                _pausedAfterStop = false;
                onSessionEnd();
                return;
            }
            ensureShutdownHook();
            if (_pausedAfterStop) return; // 停 bot 收尾后不自动重开 (见 _pausedAfterStop)
            if (_frames == null && !startSession(mod)) return;
            if (_frames.getError() != null) return; // 写盘线程已挂, 别再堆队列

            long seq = ++_seq;
            _frames.write(FrameSampler.sample(mod, seq));
            _framesQueued++;

            if (_perception != null && _perception.getError() == null) {
                _perception.write(PerceptionSampler.sample(mod));
            }
            if (_memory != null && _memory.getError() == null) {
                String mem = MemoryRecorder.sample(mod);
                if (mem != null) _memory.write(mem);
            }

            emitDerivedEvents(mod);
            _tickEventKeys.clear();
        } catch (Throwable t) {
            onError("tick", t);
        }
    }

    /**
     * (2026-09-17 听觉通道) 由 {@code SoundSystemMixin} 在声音引擎总入口调用 ——
     * 服务端广播 / 实体语音 / 客户端本地音 (自己挖/走、环境音) 在这里全量汇齐,
     * 喂给感知通道的录制专用声音缓冲。不进 SoundTracker (那是 bot 的决策输入,
     * 语义不能动)。未录制时零成本早退。
     */
    public static void onSoundPlayed(String idPath, String category,
                                     double x, double y, double z, float volume, float pitch) {
        if (!isRecording() || _perception == null) return;
        try {
            PerceptionSampler.onClientSound(idPath, category, x, y, z, volume, pitch);
        } catch (Throwable t) {
            onError("onSoundPlayed", t);
        }
    }

    /**
     * 记录一条稀疏事件 (记忆读写/任务切换/受伤等)。
     * 未开始录制时是零成本早退, 因此可以在任意行为点安全插桩。
     */
    public static void event(String type, String jsonPayload) {
        if (_frames == null || _disabled || _events == null || _events.getError() != null) return;
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"t\":").append(adris.altoclef.util.helpers.WorldHelper.getTicks());
            sb.append(",\"wt\":").append(System.currentTimeMillis());
            sb.append(",\"seq\":").append(_seq);
            sb.append(",\"type\":");
            Json.str(sb, type);
            if (jsonPayload != null) {
                sb.append(",\"data\":").append(jsonPayload);
            }
            sb.append('}');
            _events.write(sb.toString());
        } catch (Throwable t) {
            onError("event", t);
        }
    }

    /**
     * 同 tick 内按 (类型, 去重键) 只记一条的事件。
     *
     * <p>用于"每 tick 可能被调用几十次"的决策入口 (如记忆读查询) —— 既保留
     * "这个 tick bot 查了哪类记忆"的信号, 又不让事件流被同一个查询刷爆。
     */
    public static void eventThrottled(String type, String dedupeKey, String jsonPayload) {
        if (_frames == null || _disabled || _events == null) return;
        try {
            if (!_tickEventKeys.add(type + '|' + dedupeKey)) return;
            event(type, jsonPayload);
        } catch (Throwable t) {
            onError("eventThrottled", t);
        }
    }

    // ===== (2026-09-21 录制同步) 诊断日志入流 =====
    // Debug.logToFile 是全项目诊断 (diag / chat 镜像 / trace) 的唯一汇聚点。这里按
    // (每文件 250ms 一条 + 全局 25 条每秒) 节流入 events 通道, 超限丢弃只计数,
    // 每 5s 汇报一次 (diag_dropped)。稀疏决策日志 ([JumpStuck] ORDER/forceCancel、
    // [VOID-GUARD]、巡逻候选、ChunkSearch 零行为诊断) 频率远低于阈值, 不会被节流。
    private static final Object _diagMutex = new Object();
    private static final java.util.HashMap<String, Long> _diagLastMs = new java.util.HashMap<>();
    private static long _diagSecStart = 0;
    private static int _diagThisSec = 0;
    private static long _diagDropped = 0;
    private static long _diagDropLastReportMs = 0;
    private static final long DIAG_FILE_INTERVAL_MS = 250;
    private static final int DIAG_MAX_PER_SEC = 25;

    /**
     * (2026-09-21) 由 {@code Debug.logToFile} 在落盘的同时调用 —— 诊断日志进录制
     * 的唯一入口。任何异常静默吞掉 (绝不走 onError, 那条路会再打日志成环)。
     */
    public static void onDiagLog(String filename, String message) {
        if (_frames == null || _disabled || _events == null || _events.getError() != null) return;
        try {
            long now = System.currentTimeMillis();
            synchronized (_diagMutex) {
                if (now - _diagSecStart >= 1000) {
                    _diagSecStart = now;
                    _diagThisSec = 0;
                }
                Long last = _diagLastMs.get(filename);
                if (last != null && now - last < DIAG_FILE_INTERVAL_MS) {
                    _diagDropped++;
                    reportDiagDrops(now);
                    return;
                }
                if (_diagThisSec >= DIAG_MAX_PER_SEC) {
                    _diagDropped++;
                    reportDiagDrops(now);
                    return;
                }
                _diagLastMs.put(filename, now);
                _diagThisSec++;
            }
            StringBuilder sb = new StringBuilder(96 + message.length());
            sb.append("{\"file\":");
            Json.str(sb, filename);
            sb.append(",\"msg\":");
            Json.str(sb, message);
            sb.append('}');
            event("diag", sb.toString());
        } catch (Throwable ignored) {
            // 诊断入流绝不能反噬行为
        }
    }

    /** 丢弃计数汇报: 最多 5s 一条, 让离线分析知道 diag 通道被节流了多少。 */
    private static void reportDiagDrops(long now) {
        if (_diagDropped > 0 && now - _diagDropLastReportMs >= 5000) {
            long n = _diagDropped;
            _diagDropped = 0;
            _diagDropLastReportMs = now;
            event("diag_dropped", "{\"n\":" + n + "}");
        }
    }

    private static void resetDiagThrottle() {
        synchronized (_diagMutex) {
            _diagLastMs.clear();
            _diagSecStart = 0;
            _diagThisSec = 0;
            _diagDropped = 0;
            _diagDropLastReportMs = 0;
        }
    }

    /** 从逐帧状态派生"需要显式标出来"的稀疏事件: 受伤/死亡/任务切换。 */
    private static void emitDerivedEvents(AltoClef mod) {
        adris.altoclef.tasksystem.TaskRunner runner = mod.getTaskRunner();
        String chainName = null;
        String leafClass = null;
        double hp = -1;
        boolean dead = false;
        String dim = null;
        try {
            if (runner != null && runner.getCurrentTaskChain() != null) {
                chainName = runner.getCurrentTaskChain().getName();
                var tasks = runner.getCurrentTaskChain().getTasks();
                if (!tasks.isEmpty()) {
                    leafClass = tasks.get(tasks.size() - 1).getClass().getSimpleName();
                }
            }
            var p = mod.getPlayer();
            if (p != null) {
                hp = p.getHealth();
                dead = p.isDead();
            }
            if (mod.getWorld() != null) dim = mod.getWorld().getRegistryKey().getValue().toString();
        } catch (Throwable ignored) {
            return;
        }

        boolean sameDim = dim != null && dim.equals(_lastDim);
        if (hp >= 0 && _lastHp >= 0 && sameDim && !dead && !_wasDead) {
            double delta = hp - _lastHp;
            if (delta < -0.001) {
                event("damage", "{\"from\":" + Json.f(_lastHp) + ",\"to\":" + Json.f(hp)
                        + ",\"amount\":" + Json.f(-delta) + "}");
            } else if (delta > 0.001) {
                event("heal", "{\"from\":" + Json.f(_lastHp) + ",\"to\":" + Json.f(hp) + "}");
            }
        }
        if (dead && !_wasDead) {
            event("death", "{\"dim\":" + quoteToJson(dim) + "}");
        }
        if (!dead && _wasDead) {
            event("respawn", "{\"dim\":" + quoteToJson(dim) + "}");
        }

        if (chainName != null && _lastChainName != null && !chainName.equals(_lastChainName)) {
            event("chain_switch", "{\"from\":" + quoteToJson(_lastChainName) + ",\"to\":" + quoteToJson(chainName) + "}");
        }
        if (leafClass != null && _lastLeafClass != null && !leafClass.equals(_lastLeafClass)) {
            event("leaf_switch", "{\"chain\":" + quoteToJson(chainName)
                    + ",\"from\":" + quoteToJson(_lastLeafClass) + ",\"to\":" + quoteToJson(leafClass) + "}");
        }

        _lastHp = hp;
        _lastDim = dim;
        _wasDead = dead;
        _lastChainName = chainName;
        _lastLeafClass = leafClass;
    }

    private static String quoteToJson(String s) {
        StringBuilder sb = new StringBuilder(32);
        Json.str(sb, s);
        return sb.toString();
    }

    // (2026-09-17 退出兜底) JVM 关闭钩子。没有它的话, 直接退出游戏 (而不是先
    // @record off / 离开世界) 会让四个 .gz 停在"只有 gzip 头"的状态 —— 没有尾部,
    // 多数读取器直接报错, 整段数据白录 (20260917-100000-r2 实测 4 个 10 字节文件)。
    private static boolean _shutdownHookInstalled = false;
    private static volatile boolean _shuttingDown = false;

    private static void ensureShutdownHook() {
        if (_shutdownHookInstalled) return;
        _shutdownHookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                _shuttingDown = true;
                try {
                    onSessionEnd();
                } catch (Throwable ignored) {
                }
            }, "altoclef-record-close"));
        } catch (Throwable ignored) {
            // 装不上钩子不影响正常收尾路径 (@record off / 离开世界 / 停 bot 都还会收)
        }
    }

    /**
     * (2026-09-17) 停 bot 收尾入口: 由 {@code TaskRunner.disable()} 调用 (Alt+U / {@code @stop}
     * 的用户任务是唯一出口)。收尾 + 上闩, 让本世界内不再自动开新会话。
     */
    public static void onBotStopped() {
        _pausedAfterStop = true;
        onSessionEnd();
    }

    /**
     * (2026-10-29) 按住态自然结束、回主线之前调用：只解"停 bot"闩，不动
     * {@code _disabled}/{@code _errors}（那是出错自禁用，与"人有没有停 bot"无关）。
     *
     * 为什么需要它：{@code @idle}/{@code @sleep} 是"按住态但有自然终点"。到期时
     * {@code UserTaskChain.onTaskFinish} 判 {@code shouldIdle=false}（idleCommand 为空）
     * → {@code TaskRunner.disable()} → 本类 {@link #onBotStopped()} 上闩；随后
     * {@code ActionDispatcher} 才 resumeMainline 把主线拉起来。顺序上 disable 恒在
     * resume 之前 ⇒ 主线回来了、录制却永久停（闩只在 {@code @record on} 或离开世界时清）。
     * 现场 20261025-222222: 乔乔 22:52:08 发 {@code @idle 5}，22:52:13
     * "[Record] 会话结束 frames=22282"，之后主线自启却 36 分钟零帧，正好错过 bot1
     * 零位移卡死段（22:59:47–23:29:00）。
     *
     * 与 {@code @stop} 的区别：{@code @stop}/Alt+U 不走 resumeMainline ⇒ 仍上闩，
     * 09-17 的原始意图（停 bot 不切分会话）不变。
     *
     * <p><b>(2026-10-29 审查修正) 措辞纠正</b>：解闩<b>不等于"会话继续"</b>。
     * {@link #onBotStopped()} 已经调过 {@link #onSessionEnd()} 把 {@code _frames} 收尾，
     * 所以解闩后下一个 tick 走 {@link #tick()} → {@link #startSession()}，
     * {@code _sessionIndex++} ⇒ 记录落到<b>新的 {@code -rN} 目录</b>，{@code _seq} 从 0 重来。
     * 真实语义是"恢复录制，但切新段"，不是"续写同一会话"。依赖"单目录=单次运行"的
     * 离线收割脚本要注意这一点。</p>
     */
    public static void onBotResumed() {
        _pausedAfterStop = false;
    }

    /** 会话收尾: 排空队列、补齐 gzip 尾部、写 meta 的结束统计。幂等。 */
    public static void onSessionEnd() {
        if (_frames == null) return;
        try {
            // 关闭钩子里给短超时: 游戏退出不该被写盘卡住, 但 gzip 尾部必须写完。
            long wait = _shuttingDown ? 1200 : 3000;
            _frames.closeAndFlush(wait);
            if (_perception != null) _perception.closeAndFlush(wait);
            if (_memory != null) _memory.closeAndFlush(wait);
            if (_events != null) _events.closeAndFlush(wait);
            writeMeta(true);
            StringBuilder m = new StringBuilder("[Record] 会话结束 session=")
                    .append(_sessionDir)
                    .append(" frames=").append(_framesQueued);
            appendSinkStats(m, "perception", _perception);
            appendSinkStats(m, "memory", _memory);
            appendSinkStats(m, "events", _events);
            if (_frames.getDroppedLines() > 0) {
                m.append(" dropped(frames)=").append(_frames.getDroppedLines());
            }
            Debug.logMessage(m.toString());
            Debug.logToFile("altoclef_diag.log", m.toString());
        } catch (Throwable ignored) {
        } finally {
            _frames = null;
            _perception = null;
            _memory = null;
            _events = null;
            _sessionDir = null;
            MemoryRecorder.reset();
            PerceptionSampler.reset();
            resetDiagThrottle();
            _tickEventKeys.clear();
        }
    }

    private static void appendSinkStats(StringBuilder sb, String name, JsonlSink sink) {
        if (sink == null) return;
        sb.append(' ').append(name).append('=').append(sink.getWrittenLines());
        if (sink.getDroppedLines() > 0) sb.append("(dropped ").append(sink.getDroppedLines()).append(')');
    }

    private static boolean startSession(AltoClef mod) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.runDirectory == null) return false;
        _sessionIndex++;
        String name = _sessionIndex == 1 ? Debug.SESSION_STAMP : Debug.SESSION_STAMP + "-r" + _sessionIndex;
        Path dir = mc.runDirectory.toPath().resolve("record").resolve(name);
        try {
            Files.createDirectories(dir);
        } catch (Throwable t) {
            _disabled = true;
            _disabledReason = "无法创建录制目录 " + dir + ": " + t;
            Debug.logWarning("[Record] 无法创建录制目录 " + dir + ", 录制已禁用: " + t);
            return false;
        }
        _sessionDir = dir;
        _startedAtMs = System.currentTimeMillis();
        _seq = 0;
        _errors = 0;
        _framesQueued = 0;
        // 跨会话状态复位: 记忆通道必须在新会话首帧就写一份完整快照 (哪怕内容为空),
        // 派生事件也不该把"上一局的最后状态"当成基线。
        MemoryRecorder.reset();
        PerceptionSampler.reset();
        resetDiagThrottle();
        _tickEventKeys.clear();
        _lastHp = -1;
        _lastDim = null;
        _wasDead = false;
        _lastChainName = null;
        _lastLeafClass = null;
        _frames = new JsonlSink(dir.resolve("frames.jsonl.gz"));
        _perception = new JsonlSink(dir.resolve("perception.jsonl.gz"));
        _memory = new JsonlSink(dir.resolve("memory.jsonl.gz"));
        _events = new JsonlSink(dir.resolve("events.jsonl.gz"));
        writeMeta(false);
        String msg = "[Record] 开始录制 -> " + dir + " (决策 20Hz; 感知通道的可见性判定走 "
                + "VisibilityHelper 的 400ms LOS 缓存, 属感知延迟)";
        Debug.logMessage(msg);
        Debug.logToFile("altoclef_diag.log", msg);
        return true;
    }

    /**
     * 写 meta.json。区分"开录时(配置快照)"与"收尾时(追加统计)"两态 —— 结束时整体
     * 重写一份完整的, 保证任何一次会话目录里都有一份自洽的元信息。
     */
    private static void writeMeta(boolean finished) {
        try {
            Settings s = Debug.jankModInstance != null ? Debug.jankModInstance.getModSettings() : null;
            MinecraftClient mc = MinecraftClient.getInstance();
            StringBuilder sb = new StringBuilder(1024);
            sb.append("{\n");
            sb.append("  \"session\": ");
            Json.str(sb, _sessionDir == null ? null : _sessionDir.getFileName().toString());
            sb.append(",\n  \"build\": ");
            Json.str(sb, Debug.BUILD_ID);
            sb.append(",\n  \"startedAtMs\": ").append(_startedAtMs);
            sb.append(",\n  \"startedAt\": ");
            Json.str(sb, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(_startedAtMs)));
            sb.append(",\n  \"decisionHz\": 20,\n  \"taskTickInterval\": ");
            sb.append(s == null ? "null" : String.valueOf(s.getTaskTickInterval()));
            sb.append(",\n  \"channels\": {\"frames\":\"A时间+环境(env)/B标签/C自身/G动作+容器槽位(slots) (每帧, 一行一 tick)\", ");
            sb.append("\"perception\":\"D可见世界: blocks/entities/sounds (每帧, 仅录 bot 真能感知的东西)\", ");
            sb.append("\"memory\":\"E记忆快照: world/knowledge/valuable 三块 + blockcache(BlockTracker 决策候选集, ");
            sb.append("rev 变化且距上次≥3s 才写) (只在变化时才写)\", ");
            sb.append("\"events\":\"稀疏事件: memory_write/memory_read/damage/heal/death/respawn/chain_switch/leaf_switch/blacklist/anomaly/");
            sb.append("fireball_evade/fireball_repath/breath_touch/breath_clear/breath_near/breath_near_clear/");
            sb.append("diag/diag_dropped\"}");
            sb.append(",\n  \"eventNotes\": {\"memory_read\":\"只在决策入口 (getNearest*/getRememberedCenters) 记, ");
            sb.append("按类型同 tick 去重; 内层循环里的 getXxxLocations 不记以免淹掉事件流\", ");
            sb.append("\"craft_item_change\":\"故意不记 —— 背包逐帧全量已含该信息, 重复导出只是翻倍体积\", ");
            sb.append("\"blacklist\":\"AbstractObjectBlacklist.blackListItem 唯一汇聚点: 方块+实体的不可达判定全量, ");
            sb.append("banned=true = 该目标已被搜索排除 (bot 放弃/绕路的官方归因)\", ");
            sb.append("\"anomaly\":\"AnomalyWatchdog.emit: stall/oscillation/unreachable/idle/duration 看门狗结论, ");
            sb.append("自带 (type,key) 冷却去重\", ");
            sb.append("\"diag\":\"Debug.logToFile 全项目诊断单点入流 (diag/chat 镜像/trace): ");
            sb.append("[JumpStuck] 强制干预 / [VOID-GUARD] / 巡逻候选 / ChunkSearch 零行为诊断等决策依据全在此; ");
            sb.append("节流 = 每文件 250ms 一条 + 全局 25 条每秒\", ");
            sb.append("\"diag_dropped\":\"diag 被节流丢弃的条数汇总 (5s 至多一条)\"}");
            sb.append(",\n  \"labels\": {\"levels\": [\"L1 语义\", \"L2 操作带参\", \"L3 原语\"], ");
            sb.append("\"fieldMap\": {\"c\":\"任务类简单名\", \"d\":\"toDebugString 结构文案(含参数)\", ");
            sb.append("\"s\":\"setDebugState 语义状态(L1 标签源)\", \"f\":\"isFinished\"}, ");
            sb.append("\"note\": \"录制端只导原始任务栈, 不做层级判定; L1/L2/L3 词表由离线收割得到, ");
            sb.append("手写词表会漏掉新增任务类\"}");
            sb.append(",\n  \"perception\": {\"losCacheTtlMs\": 400, ");
            sb.append("\"note\": \"可见性判定复用 VisibilityHelper.perceiveSource (与 bot 决策同一套规则) + ");
            sb.append("_losCache 400ms TTL, 因此感知最多滞后 400ms —— 这是刻意的'感知延迟', 不是瞬时真值\", ");
            sb.append("\"scan\": \"近场半径内 step=1 逐格扫 + 远处按 farStep 稀疏扫, 与 BlockTracker 双区扫描同构; ");
            sb.append("按 X 列分期, 每 tick 只扫 1 列 (slices = 2*nearR+1)\", ");
            sb.append("\"sightMemory\": \"看得见即写入录制器自持的视觉记忆 (pos→最后可见 tick); ");
            sb.append("本圈内确认过 → 用其来源分级, 仅窗口内未再确认 → remembered\", ");
            sb.append("\"sounds\": \"SoundSystemMixin 在声音引擎总入口截获 (服务端广播/实体语音/自己动作音/环境音), ");
            sb.append("8s 窗口+128 上限; 可闻过滤 d<=16*volume (游戏衰减机制近似); ");
            sb.append("cat=类别 v=音量 pt=音高; SoundTracker 面向 bot 决策的语义零改动\", ");
            sb.append("\"blockstate\": \"d<=recordPerceptionStateRadius 且本扫描圈确认过 (fresh) 的方块带 st 属性 ");
            sb.append("(门开没开/熔炉燃没燃/作物第几段); 非 fresh 永不带 st —— 不给老记忆造假新状态\"}");
            sb.append(",\n  \"selfPerception\": {\"note\": \"感知来源分级: observed=真视线/真近场, ");
            sb.append("inferred=结构推断/介质豁免/经验常识, remembered=记忆中的旧位置\"}");
            sb.append(",\n  \"perceiveSourceMap\": {\"observed\": [\"los\", \"fluid_los\", \"fluid_surface\", ");
            sb.append("\"portal\", \"bed\", \"sight\", \"sound\"], \"inferred\": [\"structure_inference\", ");
            sb.append("\"canopy_inference\", \"stone_medium\", \"experience\", \"crafting_table_near\"], ");
            sb.append("\"remembered\": [\"方块: 本圈未再确认但仍在 sightMemorySeconds 窗口内; ");
            sb.append("实体: 20s 感知记忆窗口内\"]}");
            sb.append(",\n  \"perceptionCaps\": {\"maxBlocks\": ");
            sb.append(s == null ? "null" : String.valueOf(s.getRecordPerceptionMaxBlocks()));
            sb.append(", \"maxEntities\": ");
            sb.append(s == null ? "null" : String.valueOf(s.getRecordPerceptionMaxEntities()));
            sb.append(", \"range\": ");
            sb.append(s == null ? "null" : Json.f(s.getRecordPerceptionRange()));
            sb.append(", \"nearScanRadius\": ");
            sb.append(s == null ? "null" : String.valueOf(s.getRecordNearScanRadius()));
            sb.append(", \"farScanStep\": ");
            sb.append(s == null ? "null" : String.valueOf(s.getRecordFarScanStep()));
            sb.append(", \"sightMemorySeconds\": ");
            sb.append(s == null ? "null" : Json.f(s.getRecordSightMemorySeconds()));
            sb.append(", \"stateRadius\": ");
            sb.append(s == null ? "null" : Json.f(s.getRecordPerceptionStateRadius()));
            sb.append("}");
            sb.append(",\n  \"settings\": {\"cheatScan\": ");
            sb.append(s == null ? "null" : String.valueOf(s.isCheatScan()));
            sb.append(", \"inventoryPerTick\": ");
            sb.append(s == null ? "null" : String.valueOf(s.shouldRecordInventoryPerTick()));
            sb.append(", \"mobDefense\": ");
            sb.append(s == null ? "null" : String.valueOf(s.isMobDefense()));
            sb.append("}");
            sb.append(",\n  \"world\": {");
            sb.append("\"dimension\": ");
            Json.str(sb, dimension());
            sb.append(", \"seed\": ");
            sb.append(seed(mc));
            sb.append(", \"levelName\": ");
            Json.str(sb, levelName(mc));
            sb.append(", \"worldId\": ");
            Json.str(sb, worldId());
            sb.append("}");
            sb.append(",\n  \"config\": {\"cheatScanNote\": \"cheatScan=true 表示该会话的感知是透视的, ");
            sb.append("训练时不可与拟人会话语料混用\"}");
            if (finished) {
                sb.append(",\n  \"endedAtMs\": ").append(System.currentTimeMillis());
                sb.append(",\n  \"frames\": ").append(_framesQueued);
                sb.append(",\n  \"sinkStats\": {");
                sb.append("\"framesWritten\": ").append(_frames == null ? 0 : _frames.getWrittenLines());
                sb.append(", \"perceptionWritten\": ").append(_perception == null ? 0 : _perception.getWrittenLines());
                sb.append(", \"memoryWritten\": ").append(_memory == null ? 0 : _memory.getWrittenLines());
                sb.append(", \"eventsWritten\": ").append(_events == null ? 0 : _events.getWrittenLines());
                sb.append("}");
            }
            sb.append("\n}\n");
            Files.write(_sessionDir.resolve("meta.json"), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            onError("writeMeta", t);
        }
    }

    private static String dimension() {
        try {
            AltoClef mod = Debug.jankModInstance;
            if (mod == null || mod.getWorld() == null) return null;
            return mod.getWorld().getRegistryKey().getValue().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String seed(MinecraftClient mc) {
        try {
            if (mc == null || !mc.isInSingleplayer()) return "null";
            return String.valueOf(mc.getServer().getOverworld().getSeed());
        } catch (Throwable t) {
            return "null";
        }
    }

    private static String levelName(MinecraftClient mc) {
        try {
            if (mc == null || !mc.isInSingleplayer()) return null;
            return mc.getServer().getSaveProperties().getLevelName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * (2026-09-29 多人服阶段一 🔴#3 联动) 世界身份串 —— 与跨会话记忆的 key 口径
     * 完全一致（{@link adris.altoclef.util.helpers.WorldIdentity#saveDirName()}）：
     * 单人 = 存档目录名，多人 = {@code srv:<服务器地址>}。
     *
     * <p>为什么新加字段而不去改 {@link #levelName}：levelName 是"存档显示名"，
     * 语义与值域都不能覆盖多人服（客户端拿不到服务端世界名），且改名世界会与
     * 记忆 key 不一致。worldId 保证了【训练数据 ↔ 跨会话记忆日志】可按同一口径
     * join；seed 在多人下客户端确实拿不到，保持 "null"（不伪造）。
     */
    private static String worldId() {
        try {
            return adris.altoclef.util.helpers.WorldIdentity.saveDirName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void onError(String where, Throwable t) {
        _errors++;
        if (_errors <= MAX_ERROR_LOGS) {
            Debug.logWarning("[Record] " + where + " 异常 (第 " + _errors + " 次): " + t);
            Debug.logToFile("altoclef_diag.log", "[Record] " + where + " 异常: " + t);
        }
        if (_errors >= MAX_ERRORS_BEFORE_DISABLE) {
            _disabled = true;
            _disabledReason = where + " 连续异常 " + _errors + " 次: " + t;
            Debug.logWarning("[Record] 连续异常达 " + MAX_ERRORS_BEFORE_DISABLE + " 次, 本次会话录制已禁用 (不影响 bot 行为)");
        }
    }

    // ===== (2026-09-17) 聊天命令支持 (@record) =====

    /**
     * 清除"出错自禁用"状态与"停 bot"闩, 让 {@code @record on} 能在下一 tick 重新开始
     * 一次会话 (两者都是"录制为什么没在跑"的原因, 由同一条命令统一解除)。
     */
    public static void clearDisabled() {
        _disabled = false;
        _disabledReason = null;
        _errors = 0;
        _pausedAfterStop = false;
    }

    /** 当前是否有活跃录制会话 (与"设置开关是否 ON" 是两回事)。 */
    public static boolean hasActiveSession() {
        return _frames != null;
    }

    public static String getSessionDirString() {
        return _sessionDir == null ? null : _sessionDir.toString();
    }

    /** {@code @record} 的状态报告 (人类可读多行, 交给 mod.log 输出)。 */
    public static List<String> statusReport(AltoClef mod) {
        List<String> out = new java.util.ArrayList<>();
        boolean enabled = false;
        try {
            enabled = mod.getModSettings() != null && mod.getModSettings().isRecordDatasetEnabled();
        } catch (Throwable ignored) {
        }
        out.add("[Record] 设置开关=" + (enabled ? "ON" : "OFF")
                + "  活跃会话=" + (_frames != null ? "YES" : "NO")
                + (_disabled ? "  (已自禁用: " + _disabledReason + ")" : "")
                + (_pausedAfterStop ? "  (停 bot 后已暂停)" : ""));
        if (_frames == null) {
            out.add("[Record] 当前未录制。@record on 开始。输出目录: <游戏运行目录>/record/<会话时间戳>/");
            if (_pausedAfterStop) {
                out.add("[Record] 原因: 停 bot 已收尾本世界的会话 (不自动重开) —— @record on 或离开世界再进可开新会话。");
            }
            out.add("[Record] 四个通道: frames(每帧) / perception(每帧) / memory(变化时) / events(稀疏); meta.json 记录口径与配置。");
            return out;
        }
        out.add("[Record] 会话目录: " + _sessionDir);
        out.add("[Record] 已采样 " + _framesQueued + " 帧 (seq=" + _seq + "), 异常 " + _errors + " 次");
        out.add("[Record] 已落盘  frames=" + sinkLines(_frames)
                + "  perception=" + sinkLines(_perception)
                + "  memory=" + sinkLines(_memory)
                + "  events=" + sinkLines(_events));
        long droppedAll = dropped(_frames) + dropped(_perception) + dropped(_memory) + dropped(_events);
        if (droppedAll > 0) {
            out.add("[Record] 警告: 有 " + droppedAll + " 行因写盘跟不上被丢弃 (队列满, 磁盘可能卡了)");
        }
        try {
            Settings s = mod.getModSettings();
            if (s != null) {
                out.add("[Record] 感知截断: 方块≤" + s.getRecordPerceptionMaxBlocks()
                        + " 实体≤" + s.getRecordPerceptionMaxEntities()
                        + " 距离≤" + s.getRecordPerceptionRange() + "格 (meta.json 有分级口径)");
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String sinkLines(JsonlSink sink) {
        return sink == null ? "-" : String.valueOf(sink.getWrittenLines());
    }

    private static long dropped(JsonlSink sink) {
        return sink == null ? 0 : sink.getDroppedLines();
    }
}