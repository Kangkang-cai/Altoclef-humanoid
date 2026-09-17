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
    // (2026-09-05) 落盘回填只做一次: 见过的门从 altoclef_portals.log 读回内存。
    private boolean _portalSeeded = false;
    // (2026-09-09 门熄灭诊断+框保护) 曾经见过、但本次会话里确认已熄灭 (紫块消失)
    // 的门内坐标。用途有二:
    // ① 诊断: getPortalLocations 清除死门时记录 [PortalGuard] PURPLE LOST 日志
    //    (附玩家位置与 32 格内恶魂/苦力怕快照) — 2026-09-09 11:11 场次实锤下界门
    //    紫块全没而框"看似完整"(框列缺口被工作台/熔炉填了), 当时无任何日志能定案
    //    是谁干的, 下次复现直接看日志。
    // ② 框保护兜底: BotBehaviour.isPortalBodyBlock 的门框判定依赖"相邻还有紫块",
    //    门一旦熄灭框就裸奔; 用本集合让死门相邻的黑曜石继续受保护。
    private final Map<Dimension, Set<BlockPos>> _lostPortalMemory = new HashMap<>();
    // 死门记忆上限: 门位置是有限个手工/生成结构, 正常远达不到; 仅防御异常刷入。
    private static final int LOST_PORTAL_CAP = 256;
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
        // (2026-09-16) 死因来源名(已剥 "death.attack." 前缀), 如 "drown"/"lava";
        // 非致命原因场景为 null 或其它名字。DeathRecoveryChain 据此决定是否弃墓。
        public final String deathCause;
        // (2026-09-16) 该墓是否已派发过"替代主任务"(食物/原木)。每个墓只派一次,
        // 防止 DeathRecoveryChain 每 tick 重派(否则会反复抢占其它链)。
        public boolean altTaskDispatched = false;
        DeathGraveRecord(BlockPos position, long deathGameTick, String deathCause) {
            this.position = position;
            this.deathGameTick = deathGameTick;
            this.deathCause = deathCause;
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
        Optional<BlockPos> r = nearest(getOreLocations(blocks), pos, ORE_MEMORY_RETRIEVE_RANGE);
        logMemoryRead("nearest_ore", r);
        return r;
    }

    public List<BlockPos> getAnimalLocations(Class<?>... types) {
        return getEntityLocations(_animalMemory, types);
    }

    public Optional<BlockPos> getNearestAnimal(Vec3d pos, Class<?>... types) {
        Optional<BlockPos> r = nearest(getAnimalLocations(types), pos, ANIMAL_MEMORY_RETRIEVE_RANGE);
        logMemoryRead("nearest_animal", r);
        return r;
    }

    /**
     * (2026-09-17 数据集录制) 记忆【读】事件: 只插在真正的决策入口 (getNearest*),
     * 不在 getXxxLocations 这类内层循环被反复调用的地方 —— 后者每 tick 可能几十次,
     * 会淹掉事件流。RecordManager.eventThrottled 再按 (类型,键) 做同 tick 去重,
     * 保证事件量有界。未录制时是两次静态读的零成本早退。
     */
    private static void logMemoryRead(String kind, Optional<BlockPos> result) {
        adris.altoclef.record.RecordManager.eventThrottled("memory_read", kind,
                result.map(p -> "{\"kind\":" + "\"" + kind + "\",\"hit\":true,\"pos\":["
                                + p.getX() + "," + p.getY() + "," + p.getZ() + "]}")
                        .orElse("{\"kind\":\"" + kind + "\",\"hit\":false}"));
    }

    public List<BlockPos> getPortalLocations() {
        Dimension dim = WorldHelper.getCurrentDimension();
        // (2026-09-05 跨会话回填) 游戏重启后从 altoclef_portals.log 读回本维度见过的门。
        if (!_portalSeeded) {
            _portalSeeded = true;
            _portalMemory.computeIfAbsent(dim, d -> new HashSet<>())
                    .addAll(PortalMemoryStore.loadSeen(_mod, dim));
        }
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
                    // (2026-09-09 门熄灭诊断) 记死门 + 打 [PortalGuard] 快照日志,
                    // 供 BotBehaviour 死门框保护与事后定案使用。
                    rememberLostPortal(dim, p);
                    // 门已不存在: 同步清掉落盘记录, 防止重启后又回填死位置。
                    PortalMemoryStore.removeSeen(_mod, dim, p);
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
        Optional<BlockPos> r = nearest(getPortalLocations(), pos, Double.POSITIVE_INFINITY);
        logMemoryRead("nearest_portal", r);
        return r;
    }

    /**
     * (2026-09-09 门熄灭诊断+框保护) 本会话内确认已熄灭的门内坐标 (当前维度)。
     * 消费方: BotBehaviour.isPortalBodyBlock — 死门相邻的黑曜石 (框) 继续受
     * 破坏/放置保护, 死门内部位置禁止放置。不做跨会话持久化 (重启后没见过就没见过)。
     */
    public Set<BlockPos> getLostPortalLocations() {
        return _lostPortalMemory.getOrDefault(WorldHelper.getCurrentDimension(), Collections.emptySet());
    }

    /**
     * (2026-09-09) 记住一扇熄灭的门并打诊断快照: 玩家位置 + 32 格内恶魂/苦力怕
     * (爆炸是"紫块全没、黑曜石框无损"的头号嫌疑 — 爆炸对方块选择性破坏)。
     * 零行为改动, 只写 altoclef_diag.log 与聊天栏一条。
     */
    private void rememberLostPortal(Dimension dim, BlockPos p) {
        Set<BlockPos> set = _lostPortalMemory.computeIfAbsent(dim, d -> new HashSet<>());
        if (set.size() >= LOST_PORTAL_CAP || !set.add(p)) return;
        _memoryRevision++;
        adris.altoclef.record.RecordManager.event("memory_write", memoryWriteJson("portal_lost", null, p));
        String player = (_mod.getPlayer() != null)
                ? _mod.getPlayer().getBlockPos().toShortString() : "?";
        String hostiles = describeNearbyExplosiveMobs(p);
        String line = "[PortalGuard]\tPURPLE LOST @" + p.toShortString()
                + " dim=" + dim + " player=" + player + hostiles;
        adris.altoclef.Debug.logToFile("altoclef_diag.log", line);
        adris.altoclef.Debug.logMessage("[PortalGuard] Nether portal blocks lost @ "
                + p.toShortString() + hostiles.replace('\t', ' '));
    }

    /**
     * (2026-09-09) 快照门坐标 32 格内能造成爆炸的生物 (恶魂火球/苦力怕), 附最近距离。
     * 空串 = 32 格内没有爆炸源 (那就得怀疑 bot 自己或 baritone 了)。
     */
    private String describeNearbyExplosiveMobs(BlockPos p) {
        if (_mod.getWorld() == null) return "";
        try {
            net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                    p.add(-32, -32, -32), p.add(32, 32, 32));
            StringBuilder sb = new StringBuilder();
            appendNearby(sb, _mod.getWorld().getEntitiesByClass(
                    net.minecraft.entity.mob.GhastEntity.class, box, e -> true), p, "ghast");
            appendNearby(sb, _mod.getWorld().getEntitiesByClass(
                    net.minecraft.entity.mob.CreeperEntity.class, box, e -> true), p, "creeper");
            return sb.toString();
        } catch (Exception e) {
            return "\tnearby-scan-error=" + e.getClass().getSimpleName();
        }
    }

    private void appendNearby(StringBuilder sb, List<? extends Entity> found, BlockPos p, String label) {
        if (found == null || found.isEmpty()) return;
        Entity nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : found) {
            double sq = e.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            if (sq < bestSq) {
                bestSq = sq;
                nearest = e;
            }
        }
        sb.append("\tnearby-").append(label).append("=x").append(found.size())
                .append("@").append(String.format("%.1f", Math.sqrt(bestSq))).append("m");
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
        Optional<BlockPos> r = nearest(getBedLocations(), pos, BED_MEMORY_RETRIEVE_RANGE);
        logMemoryRead("nearest_bed", r);
        return r;
    }

    private void rememberBed(Dimension dim, BlockPos pos) {
        Set<BlockPos> set = _bedMemory.computeIfAbsent(dim, d -> new HashSet<>());
        if (set.size() < MAX_PER_CATEGORY && set.add(pos)) {
            bumpRevision("bed", null, pos);
        }
    }

    private void rememberOre(Dimension dim, Block block, BlockPos pos) {
        Map<Block, Set<BlockPos>> byBlock = _oreMemory.computeIfAbsent(dim, d -> new HashMap<>());
        Set<BlockPos> set = byBlock.computeIfAbsent(block, b -> new HashSet<>());
        if (set.size() < MAX_PER_CATEGORY && set.add(pos)) {
            bumpRevision("ore", blockId(block), pos);
        }
    }

    private void rememberAnimal(Dimension dim, Class<?> type, BlockPos pos) {
        Map<Class<?>, Set<BlockPos>> byType = _animalMemory.computeIfAbsent(dim, d -> new HashMap<>());
        Set<BlockPos> set = byType.computeIfAbsent(type, t -> new HashSet<>());
        // Regional dedup: don't record the same herd 50 times as it walks.
        for (BlockPos existing : set) {
            if (existing.isWithinDistance(pos, ANIMAL_DEDUP_RADIUS)) return;
        }
        if (set.size() < MAX_PER_CATEGORY && set.add(pos)) {
            bumpRevision("animal", type.getSimpleName(), pos);
        }
    }

    private void rememberPortal(Dimension dim, BlockPos pos) {
        Set<BlockPos> set = _portalMemory.computeIfAbsent(dim, d -> new HashSet<>());
        if (set.size() < MAX_PER_CATEGORY) {
            if (set.add(pos)) {
                bumpRevision("portal", null, pos);
            }
            // (2026-09-05 传送门记忆跨会话持久化) 见过的门落盘, 重启游戏不丢。
            PortalMemoryStore.recordSeen(_mod, dim, pos);
        }
    }

    // ===== (2026-09-17 数据集录制) 记忆只读导出 + 写事件 =====

    /** 记忆写版本号: 任一 remember* 真正新增一条时 +1。录制器据此只写"变化的"快照。 */
    private volatile long _memoryRevision = 0;

    public long getMemoryRevision() {
        return _memoryRevision;
    }

    /**
     * (2026-09-17 数据集录制) 记忆写入的统一出口: 推进版本号 + 记一条
     * memory_write 事件 (记忆"完整录快照 + 读写事件"双轨里的写事件轨)。
     * 只在真的新增时调用, 重复写入不产生噪音。
     */
    private void bumpRevision(String kind, String key, BlockPos pos) {
        _memoryRevision++;
        adris.altoclef.record.RecordManager.event("memory_write", memoryWriteJson(kind, key, pos));
    }

    private static String memoryWriteJson(String kind, String key, BlockPos pos) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"kind\":");
        adris.altoclef.record.RecordManager.quote(sb, kind);
        sb.append(",\"key\":");
        adris.altoclef.record.RecordManager.quote(sb, key);
        sb.append(",\"pos\":[")
                .append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append("]}");
        return sb.toString();
    }

    private static String blockId(Block block) {
        try {
            return net.minecraft.util.registry.Registry.BLOCK.getId(block).toString();
        } catch (Throwable t) {
            return String.valueOf(block);
        }
    }

    // (2026-09-17 数据集录制) 动物记忆按 Class 存, 但 Class.getSimpleName() 在发布
    // jar 里是 intermediary 名 (动物类是 Minecraft 类 → 会变成 "class_1479")。
    // 用 EntityType 反查注册名 ("minecraft:sheep")。表只覆盖 ANIMAL_TYPES 那 4 种,
    // 与该文件既有的"各口径私有集合"惯例一致; 查不到时退回类名 (不静默丢数据)。
    private static final Map<Class<?>, net.minecraft.entity.EntityType<?>> ANIMAL_ENTITY_TYPES = Map.of(
            SheepEntity.class, net.minecraft.entity.EntityType.SHEEP,
            CowEntity.class, net.minecraft.entity.EntityType.COW,
            PigEntity.class, net.minecraft.entity.EntityType.PIG,
            ChickenEntity.class, net.minecraft.entity.EntityType.CHICKEN);

    private static String animalKey(Class<?> type) {
        net.minecraft.entity.EntityType<?> et = ANIMAL_ENTITY_TYPES.get(type);
        if (et == null) return "unknown:" + type.getSimpleName();
        try {
            return net.minecraft.util.registry.Registry.ENTITY_TYPE.getId(et).toString();
        } catch (Throwable t) {
            return "unknown:" + type.getSimpleName();
        }
    }

    /**
     * (2026-09-17 数据集录制) 当前维度全部记忆的只读快照 (自描述键 → 坐标列表)。
     * 键形如 {@code ore:minecraft:iron_ore} / {@code animal:SheepEntity} /
     * {@code portal} / {@code portal_lost} / {@code bed} / {@code grave}。
     * 内部结构 (哪些 Map、怎么去重) 留在本类内, 录制端不感知。
     */
    public Map<String, List<BlockPos>> dumpMemoryForRecord() {
        Map<String, List<BlockPos>> out = new java.util.LinkedHashMap<>();
        Dimension dim = WorldHelper.getCurrentDimension();
        try {
            Map<Block, Set<BlockPos>> ores = _oreMemory.get(dim);
            if (ores != null) {
                for (Map.Entry<Block, Set<BlockPos>> e : ores.entrySet()) {
                    out.put("ore:" + blockId(e.getKey()), new ArrayList<>(e.getValue()));
                }
            }
            Map<Class<?>, Set<BlockPos>> animals = _animalMemory.get(dim);
            if (animals != null) {
                for (Map.Entry<Class<?>, Set<BlockPos>> e : animals.entrySet()) {
                    out.put("animal:" + animalKey(e.getKey()), new ArrayList<>(e.getValue()));
                }
            }
            Set<BlockPos> portals = _portalMemory.get(dim);
            if (portals != null && !portals.isEmpty()) out.put("portal", new ArrayList<>(portals));
            Set<BlockPos> lost = _lostPortalMemory.get(dim);
            if (lost != null && !lost.isEmpty()) out.put("portal_lost", new ArrayList<>(lost));
            Set<BlockPos> beds = _bedMemory.get(dim);
            if (beds != null && !beds.isEmpty()) out.put("bed", new ArrayList<>(beds));
            DeathGraveRecord grave = _deathGraveMemory.get(dim);
            if (grave != null) {
                out.put("grave", new ArrayList<>(List.of(grave.position)));
            }
        } catch (Throwable ignored) {
            // 导出失败就给个空快照, 绝不打断 tick
        }
        return out;
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
     * {@code deathCause} is the (prefix-stripped) DamageSource name sampled at
     * the moment of death (e.g. "drown"/"lava"); may be null if no damage was
     * captured. Previous records for the same dimension are overwritten because
     * the earlier drops will already have been picked up or have despawned.
     *
     * 注意: 连续死亡 streak(_consecutiveDeaths) 的累加逻辑【不改】, 这里只是
     * 顺带把死因一起记下来, 供 DeathRecoveryChain 判定是否弃墓。
     */
    public void rememberDeathGrave(Dimension dim, BlockPos pos, long deathGameTick, String deathCause) {
        _consecutiveDeaths++;
        _lastDeathGameTick = deathGameTick;
        _deathGraveMemory.put(dim, new DeathGraveRecord(new BlockPos(pos), deathGameTick, deathCause));
        // (2026-09-17 数据集录制) 墓碑是记忆的一条, 变化要进快照与事件轨。
        bumpRevision("grave", deathCause, pos);
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
