package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * (2026-09-05 传送门记忆跨会话持久化)
 *
 * 用户实测：下界打烈焰棒中途重启游戏，回家传送门的位置全丢 —— 传送门是维度
 * 切换的必需品，下界再弄一扇新门（猪灵交易换黑曜石）既贵又绕。人类穿过一扇门
 * 之后就记得它在哪，换个存档也不会忘。
 *
 * 与矿洞 altoclef_explored.log / 要塞走廊 altoclef_fortress_visited.log 同款方案：
 * 每行 "key type x y z"，key = 存档名(去空格) + ':' + 维度，不同存档/维度不共享。
 *
 * 两类记录：
 *  - used：bot 亲手穿过的门（MiscBlockTracker，最可靠的"回家门"），每 维度 只留最新；
 *  - seen：bot 亲眼看见过的门（WorldMemory，可见性过滤后入表），每 key 上限 64。
 *
 * 过期自清：消费方（MiscBlockTracker/WorldMemory）在区块已加载且门已不存在时
 * 会同时调 removeUsed/removeSeen，防止重启后反复走向已拆掉的门。
 */
public final class PortalMemoryStore {

    // (2026-09-10 记忆文件迁入 logs/altoclef/memory/, 旧根目录文件由
    // Debug.memoryFile 首次调用时自动迁移) FILE 仅作旧文件迁移名。
    private static final String FILE = "altoclef_portals.log";
    private static final int MAX_SEEN_PER_KEY = 64;

    private static final Map<String, Set<BlockPos>> _usedByKey = new HashMap<>();
    private static final Map<String, Set<BlockPos>> _seenByKey = new HashMap<>();
    private static boolean _loaded = false;

    private PortalMemoryStore() {
    }

    /** key = "w2:" + 存档目录名 + ':' + 维度名。
     * (2026-09-10 跨世界污染修复, 2026-09-10 17:02 场次实锤) 旧 key 用 levelName
     * (存档显示名): 新建世界同名 (默认"新的世界"系列) 就互相灌门记录 —— 本世界从未
     * 造过门, 却拿上个世界的门坐标当导航目标 (650 格外假寻路卡死 29s)。改用存档
     * 目录名 (每个新世界自动递增, 物理唯一) + "w2:" 版本前缀, 旧格式 key 永不匹配,
     * 存量脏记录自动作废。 */
    private static String key(AltoClef mod, Dimension dim) {
        // (2026-09-17 统一口径) 目录名 + "w2:" 版本前缀的实现已抽到 WorldIdentity,
        // 与地标/堡垒/矿洞/远征四处共用同一份 —— 口径只此一处, 不再各写一份。
        return adris.altoclef.util.helpers.WorldIdentity.key(dim);
    }

    private static Path file() {
        return adris.altoclef.Debug.memoryFile(FILE, "portals.log");
    }

    private static void ensureLoaded() {
        if (_loaded) return;
        _loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 5) continue;
                try {
                    BlockPos p = new BlockPos(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                    Map<String, Set<BlockPos>> target = parts[1].equals("used") ? _usedByKey : _seenByKey;
                    Set<BlockPos> set = target.computeIfAbsent(parts[0], k -> new LinkedHashSet<>());
                    if (set.size() < (parts[1].equals("used") ? 8 : MAX_SEEN_PER_KEY)) set.add(p);
                } catch (NumberFormatException ignored) {
                }
            }
            int total = _usedByKey.values().stream().mapToInt(Set::size).sum()
                    + _seenByKey.values().stream().mapToInt(Set::size).sum();
            if (total > 0) {
                adris.altoclef.Debug.logMessage("[PortalMemory] loaded " + total + " portal records from " + FILE);
            }
        } catch (Exception e) {
            adris.altoclef.Debug.logMessage("[PortalMemory] failed to load " + FILE + ": " + e.getMessage());
        }
    }

    private static void appendLine(String key, String type, BlockPos pos) {
        try {
            Files.write(file(),
                    (key + " " + type + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n")
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            adris.altoclef.Debug.logMessage("[PortalMemory] failed to append: " + e.getMessage());
        }
    }

    /** bot 亲手穿过的门（每 维度 只留最新，重开会覆盖内存但文件里追加新行，取加载时"最后一条"语义）。 */
    public static void recordUsed(AltoClef mod, Dimension dim, BlockPos pos) {
        if (mod == null || mod.getWorld() == null || dim == null || pos == null) return;
        ensureLoaded();
        String k = key(mod, dim);
        Set<BlockPos> set = _usedByKey.computeIfAbsent(k, x -> new LinkedHashSet<>());
        set.remove(pos); // LinkedHashSet: 移除再追加 = 最新在后
        set.add(pos.toImmutable());
        appendLine(k, "used", pos);
    }

    /** bot 亲眼看见过的门。 */
    public static void recordSeen(AltoClef mod, Dimension dim, BlockPos pos) {
        if (mod == null || mod.getWorld() == null || dim == null || pos == null) return;
        ensureLoaded();
        String k = key(mod, dim);
        Set<BlockPos> set = _seenByKey.computeIfAbsent(k, x -> new LinkedHashSet<>());
        if (set.contains(pos)) return;
        if (set.size() >= MAX_SEEN_PER_KEY) return;
        set.add(pos.toImmutable());
        appendLine(k, "seen", pos);
    }

    /** 消费方判定门已不存在时同步清掉，防止重启后反复导航去空地。 */
    public static void removeUsed(AltoClef mod, Dimension dim, BlockPos pos) {
        if (dim == null || pos == null) return;
        ensureLoaded();
        Set<BlockPos> set = _usedByKey.get(key(mod, dim));
        if (set != null) set.remove(pos);
    }

    public static void removeSeen(AltoClef mod, Dimension dim, BlockPos pos) {
        if (dim == null || pos == null) return;
        ensureLoaded();
        Set<BlockPos> set = _seenByKey.get(key(mod, dim));
        if (set != null) set.remove(pos);
    }

    /** 重启后回填"用过的门"：该维度的最新一条。 */
    public static Optional<BlockPos> loadLatestUsed(AltoClef mod, Dimension dim) {
        ensureLoaded();
        Set<BlockPos> set = _usedByKey.get(key(mod, dim));
        if (set == null || set.isEmpty()) return Optional.empty();
        // LinkedHashSet 保持插入序，最后一条 = 最近一次使用
        BlockPos last = null;
        for (BlockPos p : set) last = p;
        return Optional.ofNullable(last);
    }

    /** 重启后回填"见过的门"集合（调用方自行做区块校验/清理）。 */
    public static Set<BlockPos> loadSeen(AltoClef mod, Dimension dim) {
        ensureLoaded();
        Set<BlockPos> set = _seenByKey.get(key(mod, dim));
        return set != null ? new HashSet<>(set) : new HashSet<>();
    }

    /**
     * (2026-09-17 世界边界丢弃) 换存档时清空内存。
     * 刻意不动 `_loaded`: 保持 true 才是不重载磁盘(用户拍板的"丢弃"语义) ——
     * 否则下次访问会把另一个存档的记录读回来, 正是要修的跨世界污染。
     * 新存档此后靠 bot 亲眼穿过/看见重新积累, 并照常落盘。
     */
    public static void onWorldBoundary() {
        _usedByKey.clear();
        _seenByKey.clear();
    }
}
