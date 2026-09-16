package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.VisibilityHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Human-like "experience memory".
 *
 * A human that sees a village corner knows chests are probably nearby, even
 * without seeing the chests. This class models that: it remembers the type and
 * center of structures the player has actually SEEN (visibility filtered), and
 * can generate candidate resource areas near those structures.
 *
 * IMPORTANT: candidates are INFERENCES, not perceptions. Anything generated here
 * must be labeled as a guess during recording, never as an observation.
 */
public final class KnowledgeMemory {

    // How often we run a landmark scan (ticks).
    private static final int SCAN_INTERVAL_TICKS = 20;
    // =========================================================================
    // (2026-09-16 视力范围感知, 用户"24 格太小, 原本视力范围多远就用多远")
    // 旧实现自己扫 24 格箱子当候选源 → 隔 30+ 格亲眼望见的堡垒根本记不住。
    // 改为直接复用 BlockTracker 感知缓存当候选 (近区 5Hz 半径 16 + 远区 4s
    // 半径 128, 即 bot 现成的"视力范围"), 候选按距离排序后每轮只对最近的
    // MAX_LOS_CHECKS_PER_ROUND 个做 isBlockPerceivable 真视线判定 (防透视,
    // 开销有界, 与 FortressExploreTask 找下界砖同款护栏)。扫描迭代按位置
    // 遍历 + 集合判定, 多跟几种地标方块几乎不增加扫描成本; 缓存每类型
    // 封顶 100 (保最近的)。
    // =========================================================================
    private static final int MAX_LOS_CHECKS_PER_ROUND = 12;
    // Merge centers that are closer than this (blocks).
    private static final double MERGE_DISTANCE = 8;
    // Max remembered centers per structure type.
    private static final int MAX_CENTERS_PER_TYPE = 20;
    // Don't generate candidates from centers farther than this from the player.
    private static final double CANDIDATE_MAX_DISTANCE = 192;

    public enum StructureType {
        VILLAGE(
                new Block[]{Blocks.DIRT_PATH, Blocks.OAK_LOG, Blocks.FARMLAND},
                8, 32, 4),
        RUINED_PORTAL(
                new Block[]{Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN},
                4, 16, 3),
        NETHER_FORTRESS(
                new Block[]{Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE},
                16, 48, 5);

        final Block[] landmarks;
        final double minCandidateRadius;
        final double maxCandidateRadius;
        final int candidatesPerCenter;

        StructureType(Block[] landmarks, double minRadius, double maxRadius, int candidatesPerCenter) {
            this.landmarks = landmarks;
            this.minCandidateRadius = minRadius;
            this.maxCandidateRadius = maxRadius;
            this.candidatesPerCenter = candidatesPerCenter;
        }
    }

    private final AltoClef _mod;
    private final Map<StructureType, Set<BlockPos>> _knownStructures = new EnumMap<>(StructureType.class);
    private int _scanCounter = 0;

    public KnowledgeMemory(AltoClef mod) {
        _mod = mod;
    }

    // (2026-09-16) 地标方块首次进游戏时注册到 BlockTracker (引用计数 +1 且
    // 不释放, 与 FortressExploreTask 等任务的 track/stopTracking 计数共存)。
    private boolean _landmarksTracked = false;

    public void tick() {
        if (!AltoClef.inGame() || _mod.getPlayer() == null) return;
        // 首个游戏 tick 注册地标方块 (运行时注册, 避开构造期 tracker 未就绪)。
        if (!_landmarksTracked) {
            _landmarksTracked = true;
            for (StructureType type : StructureType.values()) {
                _mod.getBlockTracker().trackBlock(type.landmarks);
            }
        }
        _scanCounter++;
        if (_scanCounter % SCAN_INTERVAL_TICKS != 0) return;
        scanLandmarksByPerception();
    }

    /**
     * (2026-09-16 视力范围感知) 从 BlockTracker 感知缓存取地标候选 (bot 视力
     * 范围全量), 按距离排序, 每轮只对最近 MAX_LOS_CHECKS_PER_ROUND 个做真视线
     * 判定 —— 只有"亲眼看见"的地标才 remember, 不透视。
     */
    private void scanLandmarksByPerception() {
        BlockPos player = _mod.getPlayer().getBlockPos();
        for (StructureType type : StructureType.values()) {
            List<BlockPos> candidates = _mod.getBlockTracker().getKnownLocations(type.landmarks);
            if (candidates.isEmpty()) continue;
            candidates.sort((a, b) -> Double.compare(a.getSquaredDistance(player), b.getSquaredDistance(player)));
            int checks = 0;
            for (BlockPos pos : candidates) {
                // 只做有限次射线: 最近的几个都看不见 (背对/遮挡) 就等下一轮。
                if (++checks > MAX_LOS_CHECKS_PER_ROUND) break;
                if (VisibilityHelper.isBlockPerceivable(_mod, pos)) {
                    remember(type, pos);
                }
            }
        }
    }

    /**
     * Candidate resource areas inferred from remembered structures.
     * These are guesses, not observations.
     */
    public List<BlockPos> getCandidateAreas(Vec3d fromPos) {
        return getCandidateAreas(fromPos, StructureType.values());
    }

    /**
     * (2026-09-13 类型过滤版) 旧版把三种结构混在一起出候选 —— 其中
     * RUINED_PORTAL 的 landmark 是 OBSIDIAN/CRYING_OBSIDIAN, 而 bot 自建的
     * 下界门正是黑曜石 → 门被记成"废弃传送门地标", 候选区全锚在门附近。
     * 下界远征任务因此每轮都先绕门打转, 永远走不到"锁定方向"分支
     * ([Expedition] clue=memory 实锤: 目标全在门 ±50 格, radius 11~48 震荡,
     * bearing 每轮跳变)。按类型取用: 远征只认 NETHER_FORTRESS。
     */
    public List<BlockPos> getCandidateAreas(Vec3d fromPos, StructureType... types) {
        List<BlockPos> out = new ArrayList<>();
        for (StructureType type : types) {
            Set<BlockPos> centers = _knownStructures.getOrDefault(type, Collections.emptySet());
            for (BlockPos center : centers) {
                if (center.isWithinDistance(fromPos, CANDIDATE_MAX_DISTANCE)) {
                    for (int i = 0; i < type.candidatesPerCenter; i++) {
                        double r = type.minCandidateRadius + Math.random() * (type.maxCandidateRadius - type.minCandidateRadius);
                        double a = Math.random() * 2 * Math.PI;
                        out.add(center.add((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r)));
                    }
                }
            }
        }
        return out;
    }

    public boolean hasKnowledge() {
        return _knownStructures.values().stream().anyMatch(s -> !s.isEmpty());
    }

    /**
     * Whether {@code pos} is within {@code radius} of a structure center we
     * have actually SEEN (village / ruined portal / fortress). Used by
     * VisibilityHelper for inference-based perception: a chest inside a
     * village house is "known" once the village itself has been seen, even
     * though a wall blocks the direct sightline (2026-08-21). Underground
     * dungeons/mineshafts are never remembered here, so their buried
     * containers stay invisible until the bot walks in and sees them.
     */
    public boolean isNearRememberedStructure(BlockPos pos, double radius) {
        for (StructureType type : StructureType.values()) {
            for (BlockPos center : _knownStructures.getOrDefault(type, Collections.emptySet())) {
                if (center.isWithinDistance(pos, radius)) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // (2026-09-16 跨会话地标记忆, 用户"挖金时看到堡垒, 回头找堡垒任务要能想起
    // 来") 旧记忆三个缺口: ① 纯内存, 游戏重开全丢; ② 只有"随机偏移候选",
    // 找堡垒任务拿不到精确记忆点; ③ _knownStructures 不分维度, 换维度残留。
    // 修法: 地标中心按 存档:维度 落盘 logs/altoclef/memory/knowledge.log
    // (每行 "存档:维度 TYPE x y z", 与 fortress_visited.log 同款机制), 并新增
    // getRememberedCenters 直读接口 (按维度隔离, 不再被 getCandidateAreas 的
    // 192 格距离过滤挡掉)。
    // =========================================================================
    private static final String KNOWLEDGE_FILE = "altoclef_knowledge.log";
    private static final Map<String, Map<StructureType, LinkedHashSet<BlockPos>>> _centersByKey = new HashMap<>();
    private static boolean _centersLoaded = false;

    private static java.nio.file.Path knowledgeFile() {
        return adris.altoclef.Debug.memoryFile(KNOWLEDGE_FILE, "knowledge.log");
    }

    /** 当前 存档:维度 key (与 FortressExploreTask.fortressVisitedKey 同口径)。 */
    private String worldKey() {
        String levelName = "?";
        try {
            var server = MinecraftClient.getInstance().getServer();
            if (server != null && server.getSaveProperties() != null
                    && server.getSaveProperties().getLevelName() != null) {
                levelName = server.getSaveProperties().getLevelName();
            }
        } catch (Exception ignored) {
        }
        String dim = "?";
        try {
            if (_mod.getWorld() != null) {
                dim = _mod.getWorld().getRegistryKey().getValue().toString();
            }
        } catch (Exception ignored) {
        }
        return levelName + ":" + dim;
    }

    private static void ensureCentersLoaded() {
        if (_centersLoaded) return;
        _centersLoaded = true;
        try {
            java.nio.file.Path f = knowledgeFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 5) continue;
                StructureType type = structureTypeByName(parts[1]);
                if (type == null) continue;
                try {
                    BlockPos p = new BlockPos(Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                    Map<StructureType, LinkedHashSet<BlockPos>> byType =
                            _centersByKey.computeIfAbsent(parts[0], k -> new EnumMap<>(StructureType.class));
                    LinkedHashSet<BlockPos> set = byType.computeIfAbsent(type, t -> new LinkedHashSet<>());
                    if (set.size() < MAX_CENTERS_PER_TYPE) set.add(p);
                } catch (NumberFormatException ignored) {
                }
            }
            Debug.logMessage("[Knowledge] loaded "
                    + _centersByKey.values().stream().mapToInt(m -> m.values().stream().mapToInt(Set::size).sum()).sum()
                    + " remembered structure centers from " + KNOWLEDGE_FILE);
        } catch (Exception e) {
            Debug.logMessage("[Knowledge] failed to load " + KNOWLEDGE_FILE + ": " + e.getMessage());
        }
    }

    private static StructureType structureTypeByName(String name) {
        for (StructureType t : StructureType.values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    private void persistCenter(String key, StructureType type, BlockPos pos) {
        ensureCentersLoaded();
        Map<StructureType, LinkedHashSet<BlockPos>> byType =
                _centersByKey.computeIfAbsent(key, k -> new EnumMap<>(StructureType.class));
        LinkedHashSet<BlockPos> set = byType.computeIfAbsent(type, t -> new LinkedHashSet<>());
        if (set.contains(pos)) return;
        if (set.size() >= MAX_CENTERS_PER_TYPE) return;
        set.add(pos);
        try {
            java.nio.file.Files.write(knowledgeFile(),
                    (key + " " + type.name() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 落盘失败不影响本会话内的记忆。
        }
    }

    /**
     * (2026-09-16) "亲眼看见过的地标中心"直读 (含跨会话持久化, 按 存档:维度
     * 隔离)。与 getCandidateAreas 的随机推断候选不同, 这里返回的是真实记忆点 —
     * FortressExploreTask 外围模式可直接导航过去, 不受 CANDIDATE_MAX_DISTANCE
     * (192 格) 限制 —— 挖金路过看到堡垒后哪怕跑远了, 回头找堡垒任务仍能想起。
     */
    public List<BlockPos> getRememberedCenters(StructureType type, Vec3d fromPos, double maxDistance) {
        List<BlockPos> out = new ArrayList<>();
        ensureCentersLoaded();
        Map<StructureType, LinkedHashSet<BlockPos>> byType = _centersByKey.get(worldKey());
        if (byType == null) return out;
        Set<BlockPos> set = byType.get(type);
        if (set == null) return out;
        double maxSq = maxDistance * maxDistance;
        for (BlockPos p : set) {
            if (p.getSquaredDistance(fromPos) <= maxSq) out.add(p);
        }
        return out;
    }

    private void remember(StructureType type, BlockPos pos) {
        Set<BlockPos> centers = _knownStructures.computeIfAbsent(type, t -> new HashSet<>());
        // Merge with an existing nearby center if we have one.
        for (BlockPos existing : centers) {
            if (existing.isWithinDistance(pos, MERGE_DISTANCE)) {
                return;
            }
        }
        if (centers.size() >= MAX_CENTERS_PER_TYPE) {
            return;
        }
        BlockPos center = pos.toImmutable();
        centers.add(center);
        // (2026-09-16) 落盘 + 首记日志 (验证"挖金时看到堡垒被记住"用)。
        persistCenter(worldKey(), type, center);
        Debug.logMessage("[Knowledge] remembered " + type.name() + " center at " + center.toShortString());
    }

    private static boolean contains(Block[] arr, Block b) {
        for (Block block : arr) {
            if (block == b) return true;
        }
        return false;
    }
}
