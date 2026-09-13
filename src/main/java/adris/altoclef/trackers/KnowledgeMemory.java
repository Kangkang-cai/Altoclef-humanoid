package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.VisibilityHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
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
    // How far around the player we scan for landmark blocks (blocks).
    private static final int SCAN_RADIUS = 24;
    // Slice thickness for round-robin scanning (keeps per-tick cost low).
    private static final int SLICE_WIDTH = 8;
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

    public void tick() {
        if (!AltoClef.inGame() || _mod.getPlayer() == null) return;
        _scanCounter++;
        if (_scanCounter % SCAN_INTERVAL_TICKS != 0) return;

        BlockPos player = _mod.getPlayer().getBlockPos();
        // Round-robin a vertical slice of the scan box so each tick stays cheap.
        int slices = (2 * SCAN_RADIUS) / SLICE_WIDTH;
        int slice = (_scanCounter / SCAN_INTERVAL_TICKS) % slices;
        int xMin = player.getX() - SCAN_RADIUS + slice * SLICE_WIDTH;

        // (2026-08-23 卡顿修复) 原实现每 20 tick 对 ~5.7 万位置 (3 结构 ×
        // 49y × 49z × 8x) 逐个做 LOS 射线 (isBlockVisible), 单次 100~670ms —
        // jank/slowtick 日志实锤为"每 1 秒一次规律性卡顿"的根源。优化:
        // ① 先跳过未加载区块 (看不见也扫不到, 省掉 getBlockState 的区块查找);
        // ② 每位置只取一次方块类型, 先判断是否是地标方块 (廉价过滤,
        //    地标在扫描体积里占比极小);
        // ③ 只有真正的地标方块才做昂贵的 LOS 射线。射线数从 ~5.7 万次降到
        //    每次扫描的实际地标数 (~几十次)。remember 有 8 格合并 + 每类型
        //    20 中心上限, 多记不会无界增长。
        for (int y = player.getY() - SCAN_RADIUS; y <= player.getY() + SCAN_RADIUS; y++) {
            for (int z = player.getZ() - SCAN_RADIUS; z <= player.getZ() + SCAN_RADIUS; z++) {
                for (int x = xMin; x < xMin + SLICE_WIDTH; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!_mod.getChunkTracker().isChunkLoaded(pos)) continue;
                    Block block = _mod.getWorld().getBlockState(pos).getBlock();
                    // 廉价过滤: 该方块是否属于某结构的地标方块 (无射线)。
                    for (StructureType type : StructureType.values()) {
                        if (!contains(type.landmarks, block)) continue;
                        // Only "remember" structures we can actually see (LOS)。
                        if (VisibilityHelper.isBlockVisible(_mod, pos)) {
                            remember(type, pos);
                        }
                    }
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
        centers.add(pos);
    }

    private static boolean contains(Block[] arr, Block b) {
        for (Block block : arr) {
            if (block == b) return true;
        }
        return false;
    }
}
