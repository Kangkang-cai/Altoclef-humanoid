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
 *   记忆读写/任务切换/合成/受伤等稀疏事件            → events.jsonl.gz
 *   配置与世界信息                                    → meta.json
 * </pre>
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

    // 通道启用位 (阶段2 逐项打开; 关闭的通道不建 sink、不占盘)
    private static final boolean PHASE2_PERCEPTION = true;
    private static final boolean PHASE2_MEMORY = true;

    /**
     * 是否正在录制 (供阶段2 的记忆写入点插桩做零成本早退)。热路径上只是一次
     * 静态读, 开销可忽略。
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
            if (!AltoClef.inGame()) return;
            if (_frames == null && !startSession(mod)) return;
            if (_frames.getError() != null) return; // 写盘线程已挂, 别再堆队列

            long seq = ++_seq;
            _frames.write(FrameSampler.sample(mod, seq));
            _framesQueued++;

            if (PHASE2_PERCEPTION && _perception != null && _perception.getError() == null) {
                _perception.write(PerceptionSampler.sample(mod));
            }
            if (PHASE2_MEMORY && _memory != null && _memory.getError() == null) {
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

    /** 会话收尾: 排空队列、补齐 gzip 尾部、写 meta 的结束统计。幂等。 */
    public static void onSessionEnd() {
        if (_frames == null) return;
        try {
            _frames.closeAndFlush();
            if (_perception != null) _perception.closeAndFlush();
            if (_memory != null) _memory.closeAndFlush();
            if (_events != null) _events.closeAndFlush();
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
        _tickEventKeys.clear();
        _lastHp = -1;
        _lastDim = null;
        _wasDead = false;
        _lastChainName = null;
        _lastLeafClass = null;
        _frames = new JsonlSink(dir.resolve("frames.jsonl.gz"));
        if (PHASE2_PERCEPTION) _perception = new JsonlSink(dir.resolve("perception.jsonl.gz"));
        if (PHASE2_MEMORY) {
            _memory = new JsonlSink(dir.resolve("memory.jsonl.gz"));
            _events = new JsonlSink(dir.resolve("events.jsonl.gz"));
        }
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
            sb.append(",\n  \"channels\": {\"frames\":\"A时间/B标签/C自身/G动作 (每帧, 一行一 tick)\", ");
            sb.append("\"perception\":\"D可见世界: blocks/entities/sounds (每帧, 仅录 bot 真能感知的东西)\", ");
            sb.append("\"memory\":\"E记忆快照: world/knowledge/valuable 三块 (只在写版本号变化时才写)\", ");
            sb.append("\"events\":\"稀疏事件: memory_write/memory_read/damage/heal/death/respawn/chain_switch/leaf_switch\"}");
            sb.append(",\n  \"eventNotes\": {\"memory_read\":\"只在决策入口 (getNearest*/getRememberedCenters) 记, ");
            sb.append("按类型同 tick 去重; 内层循环里的 getXxxLocations 不记以免淹掉事件流\", ");
            sb.append("\"craft_item_change\":\"故意不记 —— 背包逐帧全量已含该信息, 重复导出只是翻倍体积\"}");
            sb.append(",\n  \"labels\": {\"levels\": [\"L1 语义\", \"L2 操作带参\", \"L3 原语\"], ");
            sb.append("\"fieldMap\": {\"c\":\"任务类简单名\", \"d\":\"toDebugString 结构文案(含参数)\", ");
            sb.append("\"s\":\"setDebugState 语义状态(L1 标签源)\", \"f\":\"isFinished\"}, ");
            sb.append("\"note\": \"录制端只导原始任务栈, 不做层级判定; L1/L2/L3 词表由离线收割得到, ");
            sb.append("手写词表会漏掉新增任务类\"}");
            sb.append(",\n  \"perception\": {\"losCacheTtlMs\": 400, ");
            sb.append("\"note\": \"可见性判定复用 VisibilityHelper._losCache (400ms TTL), 因此 perception ");
            sb.append("通道的可见性最多滞后 400ms —— 这是刻意的'感知延迟', 不是瞬时真值\"}");
            sb.append(",\n  \"selfPerception\": {\"note\": \"感知来源分级: observed=真视线/真近场, ");
            sb.append("inferred=结构推断/介质豁免/经验常识, remembered=记忆中的旧位置\"}");
            sb.append(",\n  \"perceiveSourceMap\": {\"observed\": [\"los\", \"fluid_los\", \"fluid_surface\", ");
            sb.append("\"portal\", \"bed\", \"sight\", \"sound\"], \"inferred\": [\"structure_inference\", ");
            sb.append("\"canopy_inference\", \"stone_medium\", \"experience\", \"crafting_table_near\"], ");
            sb.append("\"remembered\": [\"实体 20s 感知记忆窗口内的 remembered; 方块侧的见过即记忆在 memory 通道\"]}");
            sb.append(",\n  \"perceptionCaps\": {\"maxBlocks\": ");
            sb.append(s == null ? "null" : String.valueOf(s.getRecordPerceptionMaxBlocks()));
            sb.append(", \"maxEntities\": ");
            sb.append(s == null ? "null" : String.valueOf(s.getRecordPerceptionMaxEntities()));
            sb.append(", \"range\": ");
            sb.append(s == null ? "null" : Json.f(s.getRecordPerceptionRange()));
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

    /** 清除"出错自禁用"状态, 让 {@code @record on} 能在下一 tick 重新开始一次会话。 */
    public static void clearDisabled() {
        _disabled = false;
        _disabledReason = null;
        _errors = 0;
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
                + (_disabled ? "  (已自禁用: " + _disabledReason + ")" : ""));
        if (_frames == null) {
            out.add("[Record] 当前未录制。@record on 开始。输出目录: <游戏运行目录>/record/<会话时间戳>/");
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