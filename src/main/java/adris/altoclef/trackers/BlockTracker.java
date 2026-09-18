package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.trackers.blacklisting.WorldLocateBlacklist;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.StlHelper;
import baritone.Baritone;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.pathing.movement.CalculationContext;
import baritone.process.MineProcess;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Tracks blocks the way we want it, when we want it.
 *
 * Gives you a "Check and don't care" interface where you can check for blocks and their locations over and over again
 * without scanning the world over and over again.
 *
 * Also keeps track of blacklists for unreachable blocks
 */
public class BlockTracker extends Tracker {

    private static BlockTrackerConfig _config = new BlockTrackerConfig();
    static {
        ConfigHelper.loadConfig("configs/block_tracker.json", BlockTrackerConfig::new, BlockTrackerConfig.class, newConfig -> _config = newConfig);
    }

    // This should be moved to an instance variable
    // but if set to true, block scanning will happen
    // asynchronously to spread out the expensive cost of scanning.
    private static final boolean ASYNC_SCANNING = true;

    private final HashMap<Dimension, PosCache> _caches = new HashMap<>();

    //private final PosCache _cache = new PosCache(100, 64*1.5);

    private final TimerGame _timer = new TimerGame(_config.scanInterval);

    private final TimerGame _forceElapseTimer = new TimerGame(_config.scanIntervalWhenNewBlocksFound);

    // (2026-08-23 双区扫描) 近区+远区采样扫描 (用户性能设计 2026-08-23):
    // - 近区: 5Hz 高频, 小范围 (可视范围 + 薄薄看不见区域一点)。
    //   Y 只扫 ±NEARBY_SCAN_Y_RANGE (薄层) — 玩家眼前/脚下的矿立即感知,
    //   看不见的地下矿不需要高频 (等挖过去远区低频发现)。单次开销小。
    // - 远区: 低频 (FAR_SCAN_INTERVAL 秒一次), 范围广 (radius 128),
    //   Y 全覆盖 0~255 — 覆盖不缩水, 频率低所以总开销小。
    // 单次: 近区 16²×17 ≈ 4.4k 次 × 5Hz ≈ 2.2万/秒; 远区 128²/16×255≈26万?
    //   不, 远区 128²/(4²)×(255/2)≈26万? 实际 (256/4)²=4096 × 128层=52万 太大,
    //   所以远区 step=4 且 Y step=2 (64 层): (256/4)²×64 ≈ 26 万 / 8s ≈ 3.3万/秒。
    //   合计 ~5.5 万/秒, 较原 27 万/秒降 5 倍, 且近区高频覆盖可视 + 远区广覆盖。
    // (2026-09-01 用户"探视力太瞎, 挺近有钻石没看到, 稀疏扫描太稀疏太慢")
    // 旧参数: 近区 Y 只 ±8 层 → 玩家正下方 10+ 格的矿(挖隧道最常见)完全不被
    // 近区覆盖; 远区 step=4 会漏掉 1-2 块的矿脉(钻石), 8s 一次太慢。而近区/远区
    // 都在 Baritone 后台线程异步执行, 主 tick 无卡顿 — 上一轮"卡顿修复"把感知
    // 缩到盲区反而没修好卡顿(真卡顿是 LOS 射线, 已用三重护栏解决)。放宽:
    //   近区 Y ±8 → ±24 (竖直覆盖挖隧道落矿的常见场景);
    //   远区 step 4 → 2 (不漏 1-2 块矿脉), 间隔 8s → 4s (更快刷新)。
    private static final double NEARBY_SCAN_INTERVAL = 0.2;   // 5Hz
    private static final int NEARBY_SCAN_RADIUS = 16;          // 可视范围 + 一点
    private static final int NEARBY_SCAN_Y_RANGE = 24;         // 玩家 ±24 层 (原8, 覆盖正上下方矿)
    private static final double FAR_SCAN_INTERVAL = 4.0;       // 低频 (原8s, 刷新更快)
    private static final int FAR_SCAN_RADIUS = 128;            // 广范围
    private static final int FAR_SCAN_STEP = 2;                // 稀疏采样 (原4, 不漏1-2块矿脉)
    private final TimerGame _nearbyTimer = new TimerGame(NEARBY_SCAN_INTERVAL);
    private final TimerGame _farTimer = new TimerGame(FAR_SCAN_INTERVAL);

    // A scan can last no more than 15 seconds
    private final TimerGame _asyncForceResetScanFlag = new TimerGame(15);
    // (2026-08-23) 旧远区子块轮换字段已移除 (用户新设计: 远区直接低频广覆盖,
    // 不再分块)。

    private final Map<Block, Integer> _trackingBlocks = new HashMap<>();

    private final Object _scanMutex = new Object();

    private boolean _scanning = false;

    // Only perform scans at the END of our frame
    private final Semaphore _endOfFrameMutex = new Semaphore(1);

    //private Block _currentlyTracking = null;
    private final AltoClef _mod;

    public BlockTracker(AltoClef mod, TrackerManager manager) {
        super(manager);
        _mod = mod;
        // First time, track immediately
        _timer.forceElapse();
        _forceElapseTimer.forceElapse();

        // Listen for block placement
        EventBus.subscribe(BlockPlaceEvent.class, evt -> addBlock(evt.blockState.getBlock(), evt.blockPos));
    }

    @Override
    protected void updateState() {
        // (2026-08-23 双区扫描) 近区 5Hz 高频小范围 + 远区低频广范围 (用户设计)。
        if (_nearbyTimer.elapsed()) {
            _nearbyTimer.reset();
            if (_config.scanAsynchronously) {
                Baritone.getExecutor().execute(() -> rescanNearby());
            } else {
                rescanNearby();
            }
        }
        if (_farTimer.elapsed()) {
            _farTimer.reset();
            if (_config.scanAsynchronously) {
                Baritone.getExecutor().execute(() -> rescanFar());
            } else {
                rescanFar();
            }
        }
        if (shouldUpdate()) {
            update();
        }
    }

    /**
     * (2026-08-23) 近区扫描: 5Hz, 半径 16, Y 玩家 ±8 层 (薄层)。
     * 只覆盖可视范围 + 一点看不见区域 — 眼前的矿/水/岩浆即时感知,
     * 看不见的地下矿不需高频 (远区低频广覆盖负责)。
     */
    private void rescanNearby() {
        if (!AltoClef.inGame()) return;
        Block[] blocksToScan;
        synchronized (_trackingBlocks) {
            blocksToScan = _trackingBlocks.keySet().toArray(new Block[0]);
        }
        if (blocksToScan.length == 0) return;
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return;
        BlockPos playerPos = _mod.getPlayer().getBlockPos();
        if (playerPos == null) return;

        int minY = Math.max(playerPos.getY() - NEARBY_SCAN_Y_RANGE, 0);
        int maxY = Math.min(playerPos.getY() + NEARBY_SCAN_Y_RANGE, 255);
        scanRegionBySampling(world, blocksToScan, playerPos, NEARBY_SCAN_RADIUS, 1, minY, maxY);
    }

    /**
     * (2026-08-23) 远区扫描: 低频 (8s), 半径 128, Y 全覆盖 0~255 (广范围)。
     * step=4 稀疏采样 ("4 块扫 1")。覆盖不缩水, 频率低所以总开销小。
     */
    private void rescanFar() {
        if (!AltoClef.inGame()) return;
        Block[] blocksToScan;
        synchronized (_trackingBlocks) {
            blocksToScan = _trackingBlocks.keySet().toArray(new Block[0]);
        }
        if (blocksToScan.length == 0) return;
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return;
        BlockPos playerPos = _mod.getPlayer().getBlockPos();
        if (playerPos == null) return;

        scanRegionBySampling(world, blocksToScan, playerPos, FAR_SCAN_RADIUS, FAR_SCAN_STEP, 0, 255);
    }

    private void scanRegionBySampling(ClientWorld world, Block[] blocks, BlockPos center, int radius, int step, int minY, int maxY) {
        scanRegionBySamplingZRange(world, blocks, center, radius, step, minY, maxY,
                center.getZ() - radius, center.getZ() + radius);
    }

    private void scanRegionBySamplingZRange(ClientWorld world, Block[] blocks, BlockPos center, int radius, int step, int minY, int maxY, int zStart, int zEnd) {
        if (blocks.length == 0) return;
        Set<Block> targetSet = new HashSet<>();
        Collections.addAll(targetSet, blocks);

        minY = Math.max(minY, 0);
        maxY = Math.min(maxY, 255);
        int yStep = Math.max(1, step / 2); // 竖直方向更稀疏也能覆盖到，因为资源多在地表/地下分层

        for (int x = center.getX() - radius; x <= center.getX() + radius; x += step) {
            for (int z = zStart; z <= zEnd; z += step) {
                for (int y = minY; y <= maxY; y += yStep) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!_mod.getChunkTracker().isChunkLoaded(pos)) continue;
                    Block b;
                    try {
                        b = world.getBlockState(pos).getBlock();
                    } catch (Exception e) {
                        continue;
                    }
                    if (targetSet.contains(b)) {
                        synchronized (_scanMutex) {
                            currentCache().addBlock(_mod, b, pos);
                        }
                    }
                }
            }
        }
    }

    // We want our `_trackingBlocks` value to be read AFTER tasks have finished ticking
    public void preTickTask() {
        try {
            _endOfFrameMutex.acquire();
        } catch (InterruptedException e) {
            Debug.logWarning("Pre-tick failed to acquire block track mutex! (send logs)");
            e.printStackTrace();
        }
    }
    public void postTickTask() {
        _endOfFrameMutex.release();
    }

    @Override
    protected void reset() {
        // Tasks will handle de-tracking blocks.
        for (PosCache cache : _caches.values()) {
            cache.clear();
        }
    }

    public boolean isTracking(Block block) {
        synchronized (_trackingBlocks) {
            return _trackingBlocks.containsKey(block) && _trackingBlocks.get(block) > 0;
        }
    }

    /**
     * (2026-09-17 数据集录制) 当前【正在被任务追踪】的方块类型快照 (只读副本)。
     * 这是"bot 此刻在关注哪些方块类型"的准确口径 —— 也是录制感知通道的候选集合。
     * 已停止追踪 (计数为 0) 的不算, 与 {@link #isTracking} 口径一致。
     */
    public Set<Block> getTrackedBlockTypes() {
        synchronized (_trackingBlocks) {
            Set<Block> out = new HashSet<>();
            for (Map.Entry<Block, Integer> e : _trackingBlocks.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) out.add(e.getKey());
            }
            return out;
        }
    }

    /**
     * Starts tracking/pay attention to some blocks.
     * <b>IMPORTANT:</b> ALWAYS pair with {@link #stopTracking(Block...) stopTracking}! Otherwise this block type will be
     * tracked forever (not the end of the world, but other block types will be lost.
     */
    public void trackBlock(Block... blocks) {
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (!_trackingBlocks.containsKey(block)) {
                    // We're tracking a new block, so we're not updated.
                    setDirty();
                    _trackingBlocks.put(block, 0);
                    // Force a rescan if these are new blocks and we aren't doing this like every frame.
                    if (_forceElapseTimer.elapsed()) {
                        _timer.forceElapse();
                        _forceElapseTimer.reset();
                        _forceElapseTimer.setInterval(_config.scanIntervalWhenNewBlocksFound);
                    }
                }
                _trackingBlocks.put(block, _trackingBlocks.get(block) + 1);
            }
        }
    }

    /**
     * Stops tracking some blocks, after calling {@link #trackBlock(Block...) trackBlock}.
     *
     * Only call this once for every {@link #trackBlock(Block...) trackBlock}.
     */
    public void stopTracking(Block... blocks) {
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (_trackingBlocks.containsKey(block)) {
                    int current = _trackingBlocks.get(block);
                    if (current == 0) {
                        Debug.logWarning("Untracked block " + block + " more times than necessary. BlockTracker stack is unreliable from this point on.");
                    } else {
                        _trackingBlocks.put(block, current - 1);
                        if (_trackingBlocks.get(block) <= 0) {
                            _trackingBlocks.remove(block);
                        }
                    }
                }
            }
        }
    }

    /**
     * Manually add a block at a position.
     */
    public void addBlock(Block block, BlockPos pos) {
        if (blockIsValid(pos, block)) {
            synchronized (_scanMutex) {
                currentCache().addBlock(_mod, block, pos);
            }
        } else {
            Debug.logInternal("INVALID SET: " + block + " " + pos);
        }
    }

    public boolean anyFound(Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            if (_mod.getModSettings().isCheatScan()) return currentCache().anyFound(blocks);
            for (BlockPos pos : currentCache().getKnownLocations(blocks)) {
                if (VisibilityHelper.isBlockPerceivable(_mod, pos)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Checks whether any blocks of a type have been found.
     * @param isValidTest A filter predicate, returns true if a block at a position should be included.
     * @param blocks The blocks to check for
     */
    public boolean anyFound(Predicate<BlockPos> isValidTest, Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            if (_mod.getModSettings().isCheatScan()) return currentCache().anyFound(isValidTest, blocks);
            for (BlockPos pos : currentCache().getKnownLocations(blocks)) {
                if (VisibilityHelper.isBlockPerceivable(_mod, pos) && isValidTest.test(pos)) {
                    return true;
                }
            }
            return false;
        }
    }

    public Optional<BlockPos> getNearestTracking(Block... blocks) {
        // Add juuust a little, to prevent digging down all the time/bias towards blocks BELOW the player
        return getNearestTracking(_mod.getPlayer().getPos().add(0, 0.6f, 0), blocks);
    }
    public Optional<BlockPos> getNearestTracking(Vec3d pos, Block... blocks) {
        return getNearestTracking(pos, p -> true, blocks);
    }
    public Optional<BlockPos> getNearestTracking(Predicate<BlockPos> isValidTest, Block... blocks) {
        return getNearestTracking(_mod.getPlayer().getPos(), isValidTest, blocks);
    }

    /**
     * (2026-09-15 告警节流) 未追踪告警按方块名去重限频, 每 30s 至多一次:
     * per-tick 查询 (如 nightBedOpportunityNearby 查 WOOL) 原来每次都
     * logWarning, CHAT+STDOUT 每秒几十条刷屏。
     */
    private static final long UNTRACKED_WARN_INTERVAL_MS = 30_000;
    private final Map<Block, Long> _untrackedWarnAt = new HashMap<>();

    private void warnUntrackedThrottled(Block block) {
        long now = System.currentTimeMillis();
        Long last = _untrackedWarnAt.get(block);
        if (last == null || now - last >= UNTRACKED_WARN_INTERVAL_MS) {
            _untrackedWarnAt.put(block, now);
            Debug.logWarning("BlockTracker: Not tracking block " + block + " right now.");
        }
    }

    /**
     * Gets the nearest tracked block.
     * @param pos From what position? (defaults to the player's position)
     * @param isValidTest Filter predicate
     * @param blocks The blocks to check for
     * @return Optional.of(block position) if found, otherwise Optional.empty
     */
    public Optional<BlockPos> getNearestTracking(Vec3d pos, Predicate<BlockPos> isValidTest, Block... blocks) {
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (!_trackingBlocks.containsKey(block)) {
                    warnUntrackedThrottled(block);
                    return Optional.empty();
                }
            }
        }
        // Make sure we've scanned the first time if we need to.
        updateState();
        synchronized (_scanMutex) {
            return currentCache().getNearest(_mod, pos, isValidTest, blocks);
        }
    }

    /**
     * Returns the locations of all tracked blocks of a given type
     */
    public List<BlockPos> getKnownLocations(Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            return currentCache().getKnownLocations(blocks);
        }
    }

    /**
     * (2026-09-04 人类式感知) getKnownLocations 的"可感知"版本。
     *
     * 核心原则: 方块缓存是"记忆", 可以当探索的方向感用, 但不允许当
     * "已确认存在"用 —— 凡是拿缓存做"我确定那儿有 X, 走过去"这种硬决策
     * 的调用方, 必须用本接口, 只拿到 VisibilityHelper.isBlockPerceivable
     * (LOS + FOV + 近距豁免) 通过的位置。
     *
     * 注意: 内部会对每个候选做 LOS 射线, 候选多时开销大 (参见 KnowledgeMemory
     * 2026-08-23 卡顿修复的教训)。调用方应只对少量关键方块 (spawner/门框等)
     * 使用, 不要对海量方块 (矿石/岩浆) 全量过滤。
     */
    public List<BlockPos> getPerceivedLocations(Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            List<BlockPos> result = new ArrayList<>();
            for (BlockPos pos : currentCache().getKnownLocations(blocks)) {
                if (VisibilityHelper.isBlockPerceivable(_mod, pos)) {
                    result.add(pos);
                }
            }
            return result;
        }
    }

    /**
     * (2026-09-18 统一"看见某类方块"口径) 全项目唯一入口 —— 别再各处各写一份
     * (本项目已有"地表判据 7 份口径各自漂移"的教训, 见 detail/03-consistency.md)。
     *
     * <p>三段式, 逐段收紧、越贵越靠后:
     * <ol>
     *   <li><b>候选</b> = 近区/远区扫描喂进来的缓存 ({@code getKnownLocations}),
     *       且不超过 {@code maxRange} —— 缓存里没有 = bot 压根没"探到"过;</li>
     *   <li><b>FOV 锥</b> ({@code VisibilityHelper.isBlockInView}): 背对的不算看见。
     *       纯向量点积, 极便宜, 放在射线之前能砍掉绝大多数候选;</li>
     *   <li><b>LOS 射线</b> ({@code VisibilityHelper.isBlockPerceivable}): 最贵,
     *       只对最近的 {@code maxChecks} 个做 —— 缓存动辄上万条, 全量射线扛不住。</li>
     * </ol>
     *
     * <p>与 {@link #getPerceivedLocations} 的关系: 那个只做全量 LOS、不做 FOV 也不限量
     * (老口径, 有调用方依赖其语义, 不动); 需要"拟人地看见"请用本方法。
     *
     * @return 最近的一个通过 FOV + LOS 的位置, 没有则 empty。
     */
    public Optional<BlockPos> getNearestPerceivedInView(double maxRange, int maxChecks, Block... blocks) {
        updateState();
        if (_mod.getPlayer() == null) return Optional.empty();
        BlockPos player = _mod.getPlayer().getBlockPos();
        double maxRangeSq = maxRange * maxRange;
        List<BlockPos> candidates = new ArrayList<>();
        synchronized (_scanMutex) {
            for (BlockPos pos : currentCache().getKnownLocations(blocks)) {
                if (pos.getSquaredDistance(player) <= maxRangeSq) {
                    candidates.add(pos);
                }
            }
        }
        if (candidates.isEmpty()) return Optional.empty();
        // FOV 锥先筛 (便宜): 背后的不算"看见"。
        candidates.removeIf(pos -> !VisibilityHelper.isBlockInView(_mod, pos));
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(player)));
        int limit = Math.min(maxChecks, candidates.size());
        for (int i = 0; i < limit; ++i) {
            BlockPos check = candidates.get(i);
            if (VisibilityHelper.isBlockPerceivable(_mod, check)) {
                return Optional.of(check);
            }
        }
        return Optional.empty();
    }

    public Optional<BlockPos> getNearestWithinRange(BlockPos pos, double range, Block... blocks) {
        return getNearestWithinRange(new Vec3d(pos.getX(), pos.getY(), pos.getZ()), range, blocks);
    }

    /**
     * Scans a radius for the closest block of a given type .
     * @param pos The center of this radius
     * @param range Radius to scan for
     * @param blocks What blocks to check for
     */
    public Optional<BlockPos> getNearestWithinRange(Vec3d pos, double range, Block... blocks) {
        int minX = (int) Math.floor(pos.x - range),
                maxX = (int) Math.floor(pos.x + range),
                minY = (int) Math.floor(pos.y - range),
                maxY = (int) Math.floor(pos.y + range),
                minZ = (int) Math.floor(pos.z - range),
                maxZ = (int) Math.floor(pos.z + range);
        double closestDistance = Float.POSITIVE_INFINITY;
        BlockPos nearest = null;
        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                for (int z = minZ; z <= maxZ; ++z) {
                    BlockPos check = new BlockPos(x, y, z);
                    synchronized (_scanMutex) {
                        if (currentCache().blockUnreachable(check)) continue;
                    }

                    assert MinecraftClient.getInstance().world != null;
                    Block b = MinecraftClient.getInstance().world.getBlockState(check).getBlock();
                    boolean valid = false;
                    for (Block type : blocks) {
                        if (type == b) {
                            valid = true;
                            break;
                        }
                    }
                    if (!valid) continue;
                    if (!_mod.getModSettings().isCheatScan() && !VisibilityHelper.isBlockPerceivable(_mod, check)) continue;
                    if (check.isWithinDistance(pos, range)) {
                        double sq = check.getSquaredDistance(pos);
                        if (sq < closestDistance) {
                            closestDistance = sq;
                            nearest = check;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    private boolean shouldUpdate() {
        return _timer.elapsed();
    }

    private void update() {
        // Perform a baritone scan
        _timer.reset();
        _timer.setInterval(_config.scanInterval);
        CalculationContext ctx = new CalculationContext(_mod.getClientBaritone(), _config.scanAsynchronously);
        if (_config.scanAsynchronously) {
            if (_scanning && _asyncForceResetScanFlag.elapsed()) {
                Debug.logMessage("SCANNING TOOK TOO LONG! Will assume it ended mid way. Hopefully this won't break anything...");
                _scanning = false;
            }
            if (!_scanning) {
                Baritone.getExecutor().execute(() -> {
                    _scanning = true;
                    _asyncForceResetScanFlag.reset();
                    rescanWorld(ctx, true);
                    _scanning = false;
                });
            }
        } else {
            // Synchronous scanning.
            rescanWorld(ctx, false);
        }
    }

    private void rescanWorld(CalculationContext ctx, boolean async) {
        Block[] blocksToScan;
        if (async) {
            // Wait for end of frame
            try {
                _endOfFrameMutex.acquire();
                _endOfFrameMutex.release();
            } catch (InterruptedException e) {
                Debug.logWarning("RESCAN INTERRUPTED! Will SKIP the scan (see logs)");
                _endOfFrameMutex.release();
                e.printStackTrace();
                return;
            }
        }
        synchronized (_trackingBlocks) {
            Debug.logInternal("Rescanning world for " + _trackingBlocks.size() + " blocks... Hopefully not dummy slow.");
            blocksToScan = new Block[_trackingBlocks.size()];
            _trackingBlocks.keySet().toArray(blocksToScan);
        }

        List<BlockPos> knownBlocks;
        synchronized (_scanMutex) {
            knownBlocks = currentCache().getKnownLocations(blocksToScan);
        }

        // Clear invalid block pos before rescan
        for (BlockPos check : knownBlocks) {
            if (!blockIsValid(check, blocksToScan)) {
                //Debug.logInternal("Removed at " + check);
                synchronized (_scanMutex) {
                    currentCache().removeBlock(check, blocksToScan);
                }
            }
        }

        // The scanning may run asynchronously.
        BlockOptionalMetaLookup boml = new BlockOptionalMetaLookup(blocksToScan);
        List<BlockPos> found = MineProcess.searchWorld(ctx, boml, _config.maxCacheSizePerBlockType, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        synchronized (_scanMutex) {
            if (MinecraftClient.getInstance().world != null) {
                for (BlockPos pos : found) {
                    Block block = MinecraftClient.getInstance().world.getBlockState(pos).getBlock();
                    synchronized (_trackingBlocks) {
                        if (_trackingBlocks.containsKey(block)) {
                            //Debug.logInternal("Good: " + block + " at " + pos);
                            currentCache().addBlock(_mod, block, pos);
                        }
                    }
                }

                // Purge if we have too many blocks tracked at once.
                currentCache().smartPurge(_mod, _mod.getPlayer().getPos());
            }
        }
    }

    // Checks whether it would be WRONG to say "at pos the block is block"
    // Returns true if wrong, false if correct OR undetermined/unsure.
    public boolean blockIsValid(BlockPos pos, Block... blocks) {
        synchronized (_scanMutex) {
            // We can't reach it, don't even try.
            if (currentCache().blockUnreachable(pos)) {
                return false;
            }
        }
        // It might be OK to remove this. Will have to test.
        if (!_mod.getChunkTracker().isChunkLoaded(pos)) {
            //Debug.logInternal("(failed chunkcheck: " + new ChunkPos(pos) + ")");
            //Debug.logStack();
            return true;
        }
        // I'm bored
        ClientWorld zaWarudo = MinecraftClient.getInstance().world;
        // No world, therefore we don't assume block is invalid.
        if (zaWarudo == null) {
            return true;
        }
        try {
            for (Block block : blocks) {
                if (zaWarudo.isAir(pos) && WorldHelper.isAir(block)) {
                    return true;
                }
                BlockState state = zaWarudo.getBlockState(pos);
                if (state.getBlock() == block) {
                    return true;
                }
            }
            return false;
        } catch (NullPointerException e) {
            // Probably out of chunk. This means we can't judge its state.
            return true;
        }
    }

    /**
     * @param pos BlockPos to check for
     * @return Whether that block is considered unreachable
     */
    public boolean unreachable(BlockPos pos) {
        synchronized (_scanMutex) {
            return currentCache().blockUnreachable(pos);
        }
    }

    /**
     * Inform the block tracker that the bot was NOT able to reach a block.
     * @param pos block that we were unable to reach
     * @param allowedFailures how many times we can try reaching before we finally declare this block "unreachable"
     */
    public void requestBlockUnreachable(BlockPos pos, int allowedFailures) {
        synchronized (_scanMutex) {
            currentCache().blacklistBlockUnreachable(_mod, pos, allowedFailures);
        }
    }

    public void requestBlockUnreachable(BlockPos pos) {
        requestBlockUnreachable(pos, _config.defaultUnreachableAttemptsAllowed);
    }

    private PosCache currentCache() {
        Dimension dimension = WorldHelper.getCurrentDimension();
        if (!_caches.containsKey(dimension)) {
            _caches.put(dimension, new PosCache());
        }
        return _caches.get(dimension);
    }


    static class PosCache {
        private final HashMap<Block, List<BlockPos>> _cachedBlocks = new HashMap<>();

        private final HashMap<BlockPos, Block> _cachedByPosition = new HashMap<>();

        private final WorldLocateBlacklist _blacklist = new WorldLocateBlacklist();

        public boolean anyFound(Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) return true;
            }
            return false;
        }

        public boolean anyFound(Predicate<BlockPos> isValidTest, Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) {
                    for (BlockPos pos : _cachedBlocks.get(block)) {
                        if (isValidTest.test(pos)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public List<BlockPos> getKnownLocations(Block... blocks) {
            List<BlockPos> result = new ArrayList<>();
            for (Block block : blocks) {
                List<BlockPos> found = _cachedBlocks.get(block);
                if (found != null) {
                    result.addAll(found);
                }
            }
            return result;
        }

        public void removeBlock(BlockPos pos, Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) {
                    _cachedBlocks.get(block).remove(pos);
                    _cachedByPosition.remove(pos);
                    if (_cachedBlocks.get(block).size() == 0) {
                        _cachedBlocks.remove(block);
                    }
                }
            }
        }

        public void addBlock(AltoClef mod, Block block, BlockPos pos) {
            if (blockUnreachable(pos)) return;
            if (_cachedByPosition.containsKey(pos)) {
                if (_cachedByPosition.get(pos) == block) {
                    // We're already tracked
                    return;
                } else {
                    // We're tracked incorrectly, fix
                    removeBlock(pos, block);
                }
            }
            List<BlockPos> list = _cachedBlocks.computeIfAbsent(block, k -> new ArrayList<>());
            // (2026-09-03 防海量方块撑爆缓存, 用户"海洋下缓存 24 万水方块 → 每 tick
            // 遍历全量 → 200ms/帧") 单类型缓存达到上限 (maxCacheSizePerBlockType=100)
            // 时先淘汰离玩家最远的一个再添加 — 像堆一样始终保留"最近的 100 个"。
            // 水/岩浆这类"铺天盖地"型方块在扫描时每格都命中, 旧实现无添加时上限,
            // 只有每 5s 的全量 smartPurge 才裁剪到 100, 期间可膨胀到 24 万 → 任何
            // anyFound/getKnownLocations 每 tick 遍历全量。矿石稀疏所以从来没事,
            // 这解释了"为什么只有找水/海洋下卡"。
            if (list.size() >= _config.maxCacheSizePerBlockType
                    && mod != null && mod.getPlayer() != null) {
                BlockPos player = mod.getPlayer().getBlockPos();
                BlockPos farthest = null;
                double farthestSq = -1;
                for (BlockPos p : list) {
                    double d = p.getSquaredDistance(player);
                    if (d > farthestSq) {
                        farthestSq = d;
                        farthest = p;
                    }
                }
                if (farthest != null) {
                    list.remove(farthest);
                    _cachedByPosition.remove(farthest);
                }
            }
            if (!list.contains(pos)) {
                list.add(pos);
                _cachedByPosition.put(pos, block);
            }
        }


        public void clear() {
            Debug.logInternal("CLEARED BLOCK CACHE");
            _cachedBlocks.clear();
            _cachedByPosition.clear();
            _blacklist.clear();
        }

        public int getBlockTrackCount() {
            int count = 0;
            for (List<BlockPos> list : _cachedBlocks.values()) {
                count += list.size();
            }
            return count;
        }

        public void blacklistBlockUnreachable(AltoClef mod, BlockPos pos, int allowedFailures) {
            _blacklist.blackListItem(mod, pos, allowedFailures);
        }

        public boolean blockUnreachable(BlockPos pos) {
            return _blacklist.unreachable(pos);
        }

        // Gets nearest block. For now does linear search. In the future might optimize this a bit
        public Optional<BlockPos> getNearest(AltoClef mod, Vec3d position, Predicate<BlockPos> isValid, Block... blocks) {
            if (!anyFound(blocks)) {
                //Debug.logInternal("(failed cataloguecheck for " + block.getTranslationKey() + ")");
                return Optional.empty();
            }

            BlockPos closest = null;
            double minScore = Double.POSITIVE_INFINITY;

            List<BlockPos> blockList = getKnownLocations(blocks);

            int toPurge = blockList.size() - _config.maxCacheSizePerBlockType;

            boolean closestPurged = false;

            for (BlockPos pos : blockList) {
                // If our current block isn't valid, fix it up. This cleans while we're iterating.
                if (!mod.getBlockTracker().blockIsValid(pos, blocks)) {
                    removeBlock(pos, blocks);
                    continue;
                }
                if (!isValid.test(pos)) continue;
                if (!mod.getModSettings().isCheatScan() && !VisibilityHelper.isBlockPerceivable(mod, pos)) continue;

                double score = BaritoneHelper.calculateGenericHeuristic(position, WorldHelper.toVec3d(pos));

                boolean currentlyClosest = false;
                boolean purged = false;

                if (score < minScore) {
                    minScore = score;
                    closest = pos;
                    currentlyClosest = true;
                }

                if (toPurge > 0) {
                    double sqDist = position.squaredDistanceTo(WorldHelper.toVec3d(pos));
                    if (sqDist > _config.cutoffDistance * _config.cutoffDistance) {
                        // cut this one off.
                        for (Block block : blocks) {
                            if (_cachedBlocks.containsKey(block)) {
                                removeBlock(pos, block);
                            }
                        }
                        toPurge--;
                        purged = true;
                    }
                }

                if (currentlyClosest) {
                    closestPurged = purged;
                }
            }

            while (toPurge > 0) {
                if (blockList.size() == 0) {
                    //noinspection UnusedAssignment
                    toPurge = 0;
                    break;
                }
                blockList.remove(blockList.size() - 1);
                toPurge--;
            }

            // Special case: Our closest was purged. Add us back.
            if (closestPurged) {
                Debug.logInternal("Rare edge case: Closest block was purged cause it was real far away, it will now be added back.");
                blockList.add(closest);
            }

            return Optional.ofNullable(closest);
        }

        /**
         * Purge enough blocks so our size is small enough
         */
        public void smartPurge(AltoClef mod, Vec3d playerPos) {

            // Clear cached by position blocks, as they can be a handful.
            try {
                int MAX_CACHE_SIZE = _config.maxTotalCacheSize;
                if (_cachedByPosition.size() > MAX_CACHE_SIZE) {
                    List<BlockPos> toRemoveList = new ArrayList<>(_cachedByPosition.size() - MAX_CACHE_SIZE);
                    // Just purge randomly.
                    for (BlockPos pos : _cachedByPosition.keySet()) {
                        if (_cachedByPosition.size() - toRemoveList.size() < MAX_CACHE_SIZE) {
                            break;
                        }
                        toRemoveList.add(pos);
                    }
                    for (BlockPos toDelete : toRemoveList) {
                        _cachedByPosition.remove(toDelete);
                    }
                }
            } catch (Exception e) {
                Debug.logWarning("Failed to purge/reduce _cachedByPosition cache.: Its size remains at " + _cachedByPosition.size());
            }

            // ^^^ TODO: Something about that feels fishy, particularly how it's disconnected from the _cachedBlocks purging.
            // I smell a dangerous edge case bug.

            for (Block block : _cachedBlocks.keySet()) {
                List<BlockPos> tracking = _cachedBlocks.get(block);

                // Clear blacklisted blocks
                try {
                    // Untrack the blocks further away
                    tracking = tracking.stream()
                            .filter(pos -> !_blacklist.unreachable(pos))
                            // This is invalid, because some blocks we may want to GO TO not BREAK.
                            //.filter(pos -> !mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos))
                            .distinct()
                            .sorted(StlHelper.compareValues((BlockPos blockpos) -> blockpos.getSquaredDistance(playerPos)))
                            .collect(Collectors.toList());
                    tracking = tracking.stream()
                            .limit(_config.maxCacheSizePerBlockType)
                            .collect(Collectors.toList());
                    // This won't update otherwise.
                    _cachedBlocks.put(block, tracking);
                } catch (IllegalArgumentException e) {
                    // Comparison method violates its general contract: Sometimes transitivity breaks.
                    // In which case, ignore it.
                    Debug.logWarning("Failed to purge/reduce block search count for " + block + ": It remains at " + tracking.size());
                }
            }
        }
    }

    static class BlockTrackerConfig {
        public double scanInterval = 5;  // 远区全量兜底扫描间隔（baritone searchWorld）
        public double scanIntervalWhenNewBlocksFound = 1;
        public boolean scanAsynchronously = true;
        public int maxTotalCacheSize = 10000;
        public int maxCacheSizePerBlockType = 100;
        public double cutoffDistance = 64*2;
        public int defaultUnreachableAttemptsAllowed = 4;
    }
}
