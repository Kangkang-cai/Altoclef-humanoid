package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Human-like long-term positional memory.
 *
 * A human who walked past a coal vein (or a herd of sheep, or a nether portal)
 * remembers roughly where it was and can come back later without seeing it
 * again right now. This class stores the positions of things the player has
 * actually SEEN (visibility filtered) so resource tasks can query them later.
 *
 * Unlike {@link BlockTracker}/{@link EntityTracker}, these positions survive
 * after the target leaves the render distance or the tracking task stops.
 */
public final class WorldMemory {

    // How often we run a landmark scan (ticks).
    private static final int SCAN_INTERVAL_TICKS = 20;
    // How far around the player we scan for remembered blocks (blocks).
    private static final int SCAN_RADIUS = 24;
    // Slice thickness for round-robin scanning (keeps per-tick cost low).
    private static final int SLICE_WIDTH = 8;
    // Cap remembered positions per category so memory doesn't grow forever.
    private static final int MAX_PER_CATEGORY = 200;
    // Don't add a new animal memory if there's already one of the same type within this radius.
    // Animal memory is regional ("there are sheep there"), not precise ("one sheep stood here").
    private static final int ANIMAL_DEDUP_RADIUS = 10;

    // ==== 记忆检索距离上限（2026-08-21 用户原则：距离过远就舍弃）====
    // 记住的矿/动物/床若离玩家太远，就不再作为"直接走向它"的获取目标
    // —— 300 格外的记忆矿不值得专程跑一趟（那是 BiasExploreTask 的
    // bias 活儿，它有自己的 BIAS_MAX_DISTANCE）。只有走近后记忆才重新
    // 变为可用（人类也是：只记得"大概那个方向"，不会为远期记忆跑腿）。
    private static final double ORE_MEMORY_RETRIEVE_RANGE = 64;
    private static final double ANIMAL_MEMORY_RETRIEVE_RANGE = 64;
    private static final double BED_MEMORY_RETRIEVE_RANGE = 128;

    private static final Set<Block> ORE_BLOCKS = Set.of(
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE,
            Blocks.ANCIENT_DEBRIS
    );

    // Trees / huge fungi only exist on the surface (or in the nether ceiling at y>30).
    // A human remembers "there was an oak forest over there" — we store log/stem positions
    // alongside ores so MineAndCollectTask's getNearestOre / getOreLocations can steer
    // the bot toward remembered trees instead of digging holes looking for wood.
    private static final Set<Block> WOOD_LOG_BLOCKS = Set.of(
            Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG,
            Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
            Blocks.CRIMSON_STEM, Blocks.WARPED_STEM,
            Blocks.MUSHROOM_STEM,
            Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_SPRUCE_LOG,
            Blocks.STRIPPED_JUNGLE_LOG, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_DARK_OAK_LOG,
            Blocks.STRIPPED_CRIMSON_STEM, Blocks.STRIPPED_WARPED_STEM,
            Blocks.OAK_WOOD, Blocks.BIRCH_WOOD, Blocks.SPRUCE_WOOD,
            Blocks.JUNGLE_WOOD, Blocks.ACACIA_WOOD, Blocks.DARK_OAK_WOOD,
            Blocks.CRIMSON_HYPHAE, Blocks.WARPED_HYPHAE
    );

    // Beds: a human knows exactly where any bed they've placed (or seen) is, and a
    // bed is important enough that we remember it REGARDLESS of whether it's in the
    // field of view right now. Once placed, the bot can find it again to sleep in or
    // to pick back up instead of re-crafting one every night.
    private static final Set<Block> BED_BLOCKS = Set.of(
            Blocks.RED_BED, Blocks.BLACK_BED, Blocks.BLUE_BED, Blocks.BROWN_BED,
            Blocks.CYAN_BED, Blocks.GRAY_BED, Blocks.GREEN_BED, Blocks.LIGHT_BLUE_BED,
            Blocks.LIGHT_GRAY_BED, Blocks.LIME_BED, Blocks.MAGENTA_BED, Blocks.ORANGE_BED,
            Blocks.PINK_BED, Blocks.PURPLE_BED, Blocks.WHITE_BED, Blocks.YELLOW_BED
    );

    // The cache key type is still Block (generic), so reusing _oreMemory for logs
    // keeps getOreLocations(...) working with zero plumbing changes.
    private static final Set<Block> REMEMBERED_MINING_BLOCKS;
    static {
        Set<Block> union = new HashSet<>(ORE_BLOCKS.size() + WOOD_LOG_BLOCKS.size());
        union.addAll(ORE_BLOCKS);
        union.addAll(WOOD_LOG_BLOCKS);
        REMEMBERED_MINING_BLOCKS = Collections.unmodifiableSet(union);
    }

    private static final Set<Class<?>> ANIMAL_TYPES = Set.of(
            SheepEntity.class, CowEntity.class, PigEntity.class, ChickenEntity.class
    );

    private final AltoClef _mod;

    private final Map<Dimension, Map<Block, Set<BlockPos>>> _oreMemory = new HashMap<>();
    private final Map<Dimension, Map<Class<?>, Set<BlockPos>>> _animalMemory = new HashMap<>();
    private final Map<Dimension, Set<BlockPos>> _portalMemory = new HashMap<>();
    private final Map<Dimension, Set<BlockPos>> _bedMemory = new HashMap<>();

    // Death grave memory: the most recent death point per dimension.
    // Each entry records where and when we died, so DeathRecoveryChain can
    // decide whether to return and pick up drops before they despawn (5 min).
    // Only the latest death per dimension is kept because previous drops have
    // either been recovered or already despawned.
    // `deathGameTick` is the value of `World.getTime()` at the moment of death.
    public static final class DeathGraveRecord {
        public final BlockPos position;
        public final long deathGameTick;
        DeathGraveRecord(BlockPos position, long deathGameTick) {
            this.position = position;
            this.deathGameTick = deathGameTick;
        }
    }
    private final Map<Dimension, DeathGraveRecord> _deathGraveMemory = new HashMap<>();

    // Consecutive-death streak: if the bot keeps dying (e.g. over and over on
    // the way back to a grave), returning for the drops is pointless — it will
    // just die again. DeathRecoveryChain gives up once the streak grows too
    // long. The streak resets after surviving this many ticks without dying.
    private static final long DEATH_STREAK_RESET_TICKS = 20L * 60L * 10L; // 10 real minutes alive
    private int _consecutiveDeaths = 0;
    private long _lastDeathGameTick = Long.MIN_VALUE;

    private int _scanCounter = 0;

    public WorldMemory(AltoClef mod) {
        _mod = mod;
    }

    public void tick() {
        if (!AltoClef.inGame() || _mod.getPlayer() == null) return;

        // Reset the consecutive-death streak once we've survived a while —
        // old streaks shouldn't disable recovery forever.
        if (_consecutiveDeaths > 0 && MinecraftClient.getInstance().world != null) {
            long now = MinecraftClient.getInstance().world.getTime();
            if (now - _lastDeathGameTick > DEATH_STREAK_RESET_TICKS) {
                _consecutiveDeaths = 0;
            }
        }

        _scanCounter++;
        if (_scanCounter % SCAN_INTERVAL_TICKS != 0) return;

        Dimension dim = WorldHelper.getCurrentDimension();
        BlockPos player = _mod.getPlayer().getBlockPos();

        // Round-robin a horizontal slice of blocks so each tick stays cheap.
        int slices = Math.max(1, (2 * SCAN_RADIUS) / SLICE_WIDTH);
        int slice = (_scanCounter / SCAN_INTERVAL_TICKS) % slices;
        int xMin = player.getX() - SCAN_RADIUS + slice * SLICE_WIDTH;

        for (int y = player.getY() - SCAN_RADIUS; y <= player.getY() + SCAN_RADIUS; y++) {
            for (int z = player.getZ() - SCAN_RADIUS; z <= player.getZ() + SCAN_RADIUS; z++) {
                for (int x = xMin; x < xMin + SLICE_WIDTH; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    // PERFORMANCE: check block type FIRST (cheap), then visibility (raycast, expensive).
                    // 99% of blocks are stone/dirt — we skip the raycast for them.
                    Block block = _mod.getWorld().getBlockState(pos).getBlock();
                    if (REMEMBERED_MINING_BLOCKS.contains(block)) {
                        if (VisibilityHelper.isBlockVisible(_mod, pos)) {
                            rememberOre(dim, block, pos);
                        }
                    } else if (block == Blocks.NETHER_PORTAL) {
                        if (VisibilityHelper.isBlockVisible(_mod, pos)) {
                            rememberPortal(dim, pos);
                        }
                    } else if (BED_BLOCKS.contains(block)) {
                        // Beds are remembered ALWAYS (inside the scan volume), even if
                        // the player isn't currently looking at them — a placed bed at
                        // your feet would otherwise fail the FOV cone test and be
                        // forgotten, forcing a re-craft next night.
                        rememberBed(dim, pos);
                    }
                }
            }
        }

        // Remember animals we can currently see.
        if (MinecraftClient.getInstance().world != null) {
            for (Entity entity : MinecraftClient.getInstance().world.getEntities()) {
                if (!isAnimal(entity)) continue;
                if (!VisibilityHelper.isEntityVisible(_mod, entity)) continue;
                Class<?> animalType = matchAnimalType(entity);
                if (animalType != null) {
                    rememberAnimal(dim, animalType, entity.getBlockPos());
                }
            }
        }
    }

    public List<BlockPos> getOreLocations(Block... blocks) {
        return getBlockLocations(_oreMemory, blocks);
    }

    public Optional<BlockPos> getNearestOre(Vec3d pos, Block... blocks) {
        return nearest(getOreLocations(blocks), pos, ORE_MEMORY_RETRIEVE_RANGE);
    }

    public List<BlockPos> getAnimalLocations(Class<?>... types) {
        return getEntityLocations(_animalMemory, types);
    }

    public Optional<BlockPos> getNearestAnimal(Vec3d pos, Class<?>... types) {
        return nearest(getAnimalLocations(types), pos, ANIMAL_MEMORY_RETRIEVE_RANGE);
    }

    public List<BlockPos> getPortalLocations() {
        Dimension dim = WorldHelper.getCurrentDimension();
        Set<BlockPos> raw = _portalMemory.getOrDefault(dim, Collections.emptySet());
        if (raw.isEmpty()) return new ArrayList<>();
        // Validate portals we can actually see right now (chunk loaded).
        // Broken portals get purged so we don't keep walking toward empty space.
        List<BlockPos> out = new ArrayList<>(raw.size());
        Iterator<BlockPos> it = raw.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (_mod.getChunkTracker().isChunkLoaded(p)) {
                if (_mod.getWorld().getBlockState(p).getBlock() == Blocks.NETHER_PORTAL) {
                    out.add(p);
                } else {
                    it.remove();
                    changed = true;
                }
            } else {
                out.add(p);
            }
        }
        if (changed) {
            // If we just purged entries and the set is now empty, drop the dimension key.
            if (raw.isEmpty()) _portalMemory.remove(dim);
        }
        return out;
    }

    public Optional<BlockPos> getNearestPortal(Vec3d pos) {
        // 传送门不加距离上限：稀有且关键（维度切换必需），远也值得走。
        return nearest(getPortalLocations(), pos, Double.POSITIVE_INFINITY);
    }

    public List<BlockPos> getBedLocations() {
        Dimension dim = WorldHelper.getCurrentDimension();
        Set<BlockPos> raw = _bedMemory.getOrDefault(dim, Collections.emptySet());
        if (raw.isEmpty()) return new ArrayList<>();
        // Purge beds that have actually been mined/moved (chunk loaded and no longer a bed)
        // so we don't keep walking to an empty spot.
        List<BlockPos> out = new ArrayList<>(raw.size());
        Iterator<BlockPos> it = raw.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (_mod.getChunkTracker().isChunkLoaded(p)) {
                if (BED_BLOCKS.contains(_mod.getWorld().getBlockState(p).getBlock())) {
                    out.add(p);
                } else {
                    it.remove();
                }
            } else {
                out.add(p);
            }
        }
        return out;
    }

    public Optional<BlockPos> getNearestBed(Vec3d pos) {
        return nearest(getBedLocations(), pos, BED_MEMORY_RETRIEVE_RANGE);
    }

    private void rememberBed(Dimension dim, BlockPos pos) {
        Set<BlockPos> set = _bedMemory.computeIfAbsent(dim, d -> new HashSet<>());
        if (set.size() < MAX_PER_CATEGORY) set.add(pos);
    }

    private void rememberOre(Dimension dim, Block block, BlockPos pos) {
        Map<Block, Set<BlockPos>> byBlock = _oreMemory.computeIfAbsent(dim, d -> new HashMap<>());
        Set<BlockPos> set = byBlock.computeIfAbsent(block, b -> new HashSet<>());
        if (set.size() < MAX_PER_CATEGORY) set.add(pos);
    }

    private void rememberAnimal(Dimension dim, Class<?> type, BlockPos pos) {
        Map<Class<?>, Set<BlockPos>> byType = _animalMemory.computeIfAbsent(dim, d -> new HashMap<>());
        Set<BlockPos> set = byType.computeIfAbsent(type, t -> new HashSet<>());
        // Regional dedup: don't record the same herd 50 times as it walks.
        for (BlockPos existing : set) {
            if (existing.isWithinDistance(pos, ANIMAL_DEDUP_RADIUS)) return;
        }
        if (set.size() < MAX_PER_CATEGORY) set.add(pos);
    }

    private void rememberPortal(Dimension dim, BlockPos pos) {
        Set<BlockPos> set = _portalMemory.computeIfAbsent(dim, d -> new HashSet<>());
        if (set.size() < MAX_PER_CATEGORY) set.add(pos);
    }

    private List<BlockPos> getBlockLocations(Map<Dimension, Map<Block, Set<BlockPos>>> memory, Block... blocks) {
        Dimension dim = WorldHelper.getCurrentDimension();
        Map<Block, Set<BlockPos>> byBlock = memory.getOrDefault(dim, Collections.emptyMap());
        List<BlockPos> out = new ArrayList<>();
        for (Block block : blocks) {
            Set<BlockPos> set = byBlock.get(block);
            if (set == null) continue;
            Iterator<BlockPos> it = set.iterator();
            boolean changed = false;
            while (it.hasNext()) {
                BlockPos p = it.next();
                // Validate ore still exists when chunk is loaded.
                // If it was mined / exploded / etc., purge it from memory.
                if (_mod.getChunkTracker().isChunkLoaded(p)) {
                    if (_mod.getWorld().getBlockState(p).getBlock() == block) {
                        out.add(p);
                    } else {
                        it.remove();
                        changed = true;
                    }
                } else {
                    out.add(p);
                }
            }
            if (changed && set.isEmpty()) {
                byBlock.remove(block);
                if (byBlock.isEmpty()) memory.remove(dim);
            }
        }
        return out;
    }

    private List<BlockPos> getEntityLocations(Map<Dimension, Map<Class<?>, Set<BlockPos>>> memory, Class<?>... types) {
        Dimension dim = WorldHelper.getCurrentDimension();
        Map<Class<?>, Set<BlockPos>> byType = memory.getOrDefault(dim, Collections.emptyMap());
        List<BlockPos> out = new ArrayList<>();
        for (Class<?> type : types) {
            // Accept subclasses (e.g. dyed sheep count as sheep) via isInstance lookup.
            for (Map.Entry<Class<?>, Set<BlockPos>> e : byType.entrySet()) {
                if (type.isAssignableFrom(e.getKey()) || e.getKey().isAssignableFrom(type)) {
                    out.addAll(e.getValue());
                }
            }
        }
        return out;
    }

    private Optional<BlockPos> nearest(Collection<BlockPos> positions, Vec3d pos, double maxRange) {
        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        double maxSq = maxRange * maxRange;
        for (BlockPos p : positions) {
            double d = p.getSquaredDistance(pos);
            if (d >= maxSq) continue; // 距离过远的记忆：舍弃，不作为获取目标
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Matches entity against known animal types (accepts subclasses). */
    private static boolean isAnimal(Entity entity) {
        for (Class<?> c : ANIMAL_TYPES) {
            if (c.isInstance(entity)) return true;
        }
        return false;
    }

    /** Returns the canonical animal supertype from ANIMAL_TYPES that matches the entity. */
    private static Class<?> matchAnimalType(Entity entity) {
        for (Class<?> c : ANIMAL_TYPES) {
            if (c.isInstance(entity)) return c;
        }
        return null;
    }

    // ========================================================================
    // Death grave memory
    // ========================================================================

    /**
     * Record that the player just died at {@code pos} in {@code dim}.
     * {@code deathGameTick} should be {@code World.getTime()} at the instant
     * before respawning, so we can honour the 5-minute item despawn window.
     * Previous records for the same dimension are overwritten because the
     * earlier drops will already have been picked up or have despawned.
     */
    public void rememberDeathGrave(Dimension dim, BlockPos pos, long deathGameTick) {
        _consecutiveDeaths++;
        _lastDeathGameTick = deathGameTick;
        _deathGraveMemory.put(dim, new DeathGraveRecord(new BlockPos(pos), deathGameTick));
    }

    /**
     * Number of deaths in the current streak (no long surviving gap in
     * between). Used by DeathRecoveryChain to give up on recovering drops
     * when the bot keeps dying.
     */
    public int getConsecutiveDeathCount() {
        return _consecutiveDeaths;
    }

    /**
     * Returns the most recent death grave for the given dimension, if any.
     * Does NOT purge expired entries automatically so callers can see the
     * "abandoned because too old" case and log it.
     */
    public Optional<DeathGraveRecord> getDeathGrave(Dimension dim) {
        return Optional.ofNullable(_deathGraveMemory.get(dim));
    }

    /** Remove the death grave for {@code dim} (picked up, abandoned, or too old). */
    public void clearDeathGrave(Dimension dim) {
        _deathGraveMemory.remove(dim);
    }
}
