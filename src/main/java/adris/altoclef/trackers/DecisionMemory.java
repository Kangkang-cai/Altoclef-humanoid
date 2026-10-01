package adris.altoclef.trackers;

import adris.altoclef.Debug;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * (2026-09-30 决策台账) 跨进程重启的"一次性决策"持久化。
 *
 * 背景: 任务里大量"永久放弃/拉黑"类决策 (如 BeatMinecraft2Task 的
 * _diamondCollectGivenUp、_notRuinedPortalChests) 存在实例字段/static 里,
 * 进程重启即归零 → 重启后 bot 重新去干已经放弃过的任务 (用户实锤: 放弃
 * 钻石任务后重启, 又从头挖钻石)。与时间冷却类 (30s/180s giveUpUntil,
 * 本来就该过期) 不同, 这类一次性决策必须跨重启存活。
 *
 * 设计: 仿 KnowledgeMemory 的静态落盘范式 —— 按行追加
 * logs/altoclef/memory/decisions.log, 每行 "存档目录名 \t 决策名 \t 值"
 * (WorldIdentity.encodeLine 统一 TAB 编解码, 存档名含空格安全)。
 *
 * key 口径: 只用 {@link adris.altoclef.util.helpers.WorldIdentity#saveDirName()}
 * (存档目录名), 【不含维度】—— 钻石放弃/箱子拉黑这类是"这一局"的决策,
 * 跨维度有效; 与 knowledge.log (存档:维度) 的差异是刻意的。
 *
 * 两种读语义并存 (加载时每行同时进两个容器, 读侧按需取):
 *  - 标量 last-wins: {@link #put} —— 同名重复写取最后一行 (累计时长快照);
 *  - 多值并集: {@link #add} —— 同名所有行去重收集 (坐标拉黑表)。
 *
 * 同进程换存档: 走 WorldStateGuard 丢弃语义 (清内存、不重载磁盘,
 * {@link #onWorldBoundary} 保持 _loaded=true), 与 KnowledgeMemory 一致。
 */
public final class DecisionMemory {

    private static final String FILE_NAME = "altoclef_decisions.log";

    /** key → (决策名 → 最后写入值), 标量读语义。 */
    private static final Map<String, Map<String, String>> _scalarsByKey = new HashMap<>();
    /** key → (决策名 → 历史全部值去重), 多值读语义。 */
    private static final Map<String, Map<String, LinkedHashSet<String>>> _multiByKey = new HashMap<>();
    private static boolean _loaded = false;

    private DecisionMemory() {
    }

    private static java.nio.file.Path decisionsFile() {
        return Debug.memoryFile(FILE_NAME, "decisions.log");
    }

    private static void ensureLoaded() {
        if (_loaded) return;
        _loaded = true;
        int count = 0;
        try {
            java.nio.file.Path f = decisionsFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = adris.altoclef.util.helpers.WorldIdentity.decodeLine(line, 2);
                if (parts == null) continue;
                String key = parts[0], name = parts[1], value = parts[2];
                _scalarsByKey.computeIfAbsent(key, k -> new HashMap<>()).put(name, value);
                _multiByKey.computeIfAbsent(key, k -> new HashMap<>())
                        .computeIfAbsent(name, n -> new LinkedHashSet<>()).add(value);
                count++;
            }
            if (count > 0) {
                Debug.logMessage("[DecisionMemory] loaded " + count + " decision entries from " + FILE_NAME);
            }
        } catch (Exception e) {
            Debug.logMessage("[DecisionMemory] failed to load " + FILE_NAME + ": " + e.getMessage());
        }
    }

    /** 当前 key (存档目录名)。取不到世界 (主菜单/启动早期) 返回 null, 调用方静默跳过。 */
    private static String currentKey() {
        String key = adris.altoclef.util.helpers.WorldIdentity.saveDirName();
        return "?".equals(key) ? null : key;
    }

    /**
     * 标量决策写入 (last-wins)。值未变化时不重复落盘, 防止高频快照刷爆文件。
     */
    public static void put(String name, String value) {
        String key = currentKey();
        if (key == null) return;
        ensureLoaded();
        Map<String, String> scalars = _scalarsByKey.computeIfAbsent(key, k -> new HashMap<>());
        if (value.equals(scalars.get(name))) return;
        scalars.put(name, value);
        append(key, name, value);
    }

    /**
     * 多值决策追加 (并集去重)。用于"每个条目一行"的拉黑表; 已记过的值不落盘。
     */
    public static void add(String name, String value) {
        String key = currentKey();
        if (key == null) return;
        ensureLoaded();
        if (!_multiByKey.computeIfAbsent(key, k -> new HashMap<>())
                .computeIfAbsent(name, n -> new LinkedHashSet<>()).add(value)) {
            return;
        }
        append(key, name, value);
    }

    private static void append(String key, String name, String value) {
        try {
            java.nio.file.Files.write(decisionsFile(),
                    adris.altoclef.util.helpers.WorldIdentity.encodeLine(key, name, value)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 落盘失败不影响本会话内的决策。
        }
    }

    /** 标量读 (last-wins)。无记录返回 null。 */
    public static String get(String name) {
        String key = currentKey();
        if (key == null) return null;
        ensureLoaded();
        Map<String, String> scalars = _scalarsByKey.get(key);
        return scalars == null ? null : scalars.get(name);
    }

    public static boolean getBool(String name, boolean def) {
        String v = get(name);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public static long getLong(String name, long def) {
        String v = get(name);
        if (v == null) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 多值读 (并集去重)。无记录返回空表。 */
    public static java.util.List<String> getAll(String name) {
        String key = currentKey();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (key == null) return out;
        ensureLoaded();
        Map<String, LinkedHashSet<String>> multi = _multiByKey.get(key);
        if (multi != null && multi.get(name) != null) {
            out.addAll(multi.get(name));
        }
        return out;
    }

    /**
     * 换存档边界: 清内存 (丢弃语义)。保持 _loaded=true 阻止磁盘重载,
     * 与 KnowledgeMemory.onWorldBoundary 同款约定。
     */
    public static void onWorldBoundary() {
        _scalarsByKey.clear();
        _multiByKey.clear();
    }
}
