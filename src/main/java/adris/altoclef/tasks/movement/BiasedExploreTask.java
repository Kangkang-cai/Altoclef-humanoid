package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Exploration that is not completely random.
 *
 * The bot still "explores" (it does NOT teleport to or directly pursue targets),
 * but when a recent known location of a target exists in the background cache,
 * it steers the exploration direction towards it with some probability and within
 * an angle range. This speeds up finding targets while keeping human-like behavior.
 *
 * If no bias candidates exist, it degrades to random exploration.
 */
public class BiasedExploreTask extends Task implements ITaskRequiresGrounded {

    // Probability that a new exploration step is steered towards a known target.
    private static final double P_BIASED = 0.85;
    // How wide the steering cone around the exact target direction is (degrees).
    private static final double BIAS_ANGLE_DEG = 12;
    // How far each exploration step goes.
    private static final double EXPLORE_STEP = 48;
    // Max jitter applied to the persistent heading between random steps (degrees).
    private static final double RANDOM_JITTER_DEG = 25;
    // Only steer towards targets within this distance. 256 ≈ render distance:
    // structure loot is now perceivable with no distance cap (2026-08-21), so
    // far-away village chests must also be able to STEER exploration, otherwise
    // we'd sense them but never walk to them.
    private static final double BIAS_MAX_DISTANCE = 256;
    // Give up after this many failed steps.
    private static final int MAX_FAIL_COUNT = 10;
    // Distance to consider a step "arrived".
    private static final double ARRIVED_DISTANCE = 3;
    // Attempts to pick a non-water, non-failed target before counting the step as failed.
    private static final int MAX_PICK_ATTEMPTS = 12;

    // [PLAINS-BIAS] 找食物/找动物专用的平原偏置 (foodExplore=true 时才启用)。
    // 草原是动物/作物收获概率最高的地表 biome, 这是用户需求: 找食物/动物时
    // 探索机制内部优先 100% 导航到平原, 提高收获。挖矿/砍木等 non-food 探索
    // 一律不启用 (foodExplore=false), 绝不改跑去别的 biome。
    private static final int PLAINS_SCAN_INTERVAL_TICKS = 50; // 每 50 tick(~2.5s) 周期扫描, 足够节流
    // 已加载区块内扫描半径 (格)。略小于步长 48*? 保持远, 但小于 BIAS_MAX_DISTANCE(256) 才会被选中。
    private static final int PLAINS_SCAN_RADIUS = 160;
    // 扫描采样步长 (格): 每 8 格取一列判 biome, 平衡开销与覆盖。
    private static final int PLAINS_SCAN_STEP = 8;
    // "没检测到平原" 日志的低节流: 每 N 次空扫描才写一行, 不刷屏。
    private static final int PLAINS_EMPTY_LOG_INTERVAL = 10;

    private final List<Supplier<Collection<BlockPos>>> _biasSources;

    // [PLAINS-BIAS] 是否"找食物/找动物"的探索 (仅 KillAndLootTask 创建时为 true)。
    // true 时在已加载区块内周期性扫描平原 biome, 并作为最高优先级 bias 目标
    // 优先 100% 导航过去。挖矿/砍木等 non-food 探索保持 false, 完全不受影响。
    private final boolean _foodExplore;
    // 最近一次扫描到玩家周围已加载区块里的平原系地表点 (找食物探索专用)。
    private List<BlockPos> _plainsTargets = new ArrayList<>();
    private int _scanTicker = 0;
    private int _plainsEmptyLogCounter = 0;
    private int _lastFoundCount = -1;

    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final Set<BlockPos> _failedTargets = new HashSet<>();
    // Bias targets we already steered towards (arrived or failed to reach).
    // A human doesn't keep walking back to the same spot they already checked.
    private final Set<BlockPos> _visitedBiasTargets = new HashSet<>();

    private BlockPos _currentTarget;
    // The bias target the current step is steered towards (null = random step).
    private BlockPos _currentBiasTarget;
    private Vec3d _origin;
    private int _failCounter;
    private boolean _lastWasBiased;
    // True once we steered towards at least one bias target. Used to tell
    // "no targets ever existed" (keep random-exploring) apart from "all targets
    // checked" (finish — the parent re-decides what to do next).
    private boolean _hadBiasOnce = false;
    // All bias targets have been visited: nothing left to steer towards.
    private boolean _biasExhausted = false;
    // Persistent heading (radians) so random exploration keeps going one way.
    private double _heading = Double.NaN;
    // [SlowTickDebug] counts consecutive ticks where baritone had no active path and
    // we re-issued the same unreachable goal. High number = stuck "computing, not moving".
    private int _repathCount = 0;

    @SafeVarargs
    public BiasedExploreTask(boolean foodExplore, Supplier<Collection<BlockPos>>... biasSources) {
        _foodExplore = foodExplore;
        _biasSources = Arrays.asList(biasSources);
    }

    // 默认构造: 非"找食物/找动物"探索 (挖矿/砍木等), 不启用平原偏置。
    @SafeVarargs
    public BiasedExploreTask(Supplier<Collection<BlockPos>>... biasSources) {
        this(false, biasSources);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _origin = mod.getPlayer().getPos();
        _progressChecker.reset();
        _failCounter = 0;
        _currentTarget = null;
        _currentBiasTarget = null;
        _hadBiasOnce = false;
        _biasExhausted = false;
        _heading = Double.NaN;
        _repathCount = 0;
        _plainsTargets = new ArrayList<>();
        _scanTicker = 0;
        _plainsEmptyLogCounter = 0;
        _lastFoundCount = -1;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // All bias targets checked: nothing left to explore towards. End so the
        // parent task can re-decide (e.g. go dig instead of circling the surface).
        if (_biasExhausted) return null;

        // [PLAINS-BIAS] 仅在找食物/找动物的探索 (foodExplore=true) 上周期性扫描
        // 玩家附近已加载区块里的平原 biome, 作为最高优先级导航目标。其它探索走
        // 原逻辑, 不影响。纯增量, 不加递归、不碰 setTask/subTask/getPriority()。
        if (_foodExplore) {
            updatePlainsBias(mod);
        }

        if (_currentTarget == null || arrivedAtCurrentTarget(mod)) {
            // Consume the bias target we just finished walking towards.
            if (_currentBiasTarget != null) {
                _visitedBiasTargets.add(_currentBiasTarget);
            }
            // Pick a new exploration step.
            _currentTarget = chooseNewTarget(mod, mod.getPlayer().getPos());
            if (_currentTarget == null) return null;
            _progressChecker.reset();
            // [SlowTickDebug] setGoalAndPath kicks off a baritone path search. If the
            // goal is unreachable (e.g. inside a cave wall) baritone can re-path
            // forever and the bot never moves — a prime suspect for "computing but
            // not moving". Sometimes this call alone is slow.
            long tPath = System.nanoTime();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
            long pathMs = (System.nanoTime() - tPath) / 1000_000L;
            if (pathMs > 300) {
                String m = "[SlowTickDebug] BiasedExplore goal to " + _currentTarget + " setGoalAndPath returned after " + pathMs + "ms";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_slowtick.log", m);
            }
            return null;
        }

        // We're not making progress toward this step: pick a different direction.
        if (!_progressChecker.check(mod)) {
            _failCounter++;
            _failedTargets.add(_currentTarget);
            // Unreachable step: don't steer towards its bias target again either.
            if (_currentBiasTarget != null) {
                _visitedBiasTargets.add(_currentBiasTarget);
            }
            Debug.logMessage("Explore step failed, picking a new direction.");
            _currentTarget = null;
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            // [SlowTickDebug] An unreachable goal flips baritone's process idle,
            // so this branch re-paths EVERY tick = the bot looks busy but never
            // moves. Log how often + the cost.
            long tRe = System.nanoTime();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
            long reMs = (System.nanoTime() - tRe) / 1000_000L;
            _repathCount++;
            // Logging EVERY repath: this branch only runs while baritone has no active
            // path (i.e. we're likely stalled). If the count climbs to the hundreds in
            // a second, that's the "computing but not moving" loop. Written to file so
            // it's inspectable without flooding chat.
            String m = "[SlowTickDebug] BiasedExplore RE-path to " + _currentTarget + " took " + reMs + "ms (repath#" + _repathCount + ")";
            Debug.logToFile("altoclef_slowtick.log", m);
            if (reMs > 300) Debug.logMessage(m);
        }
        setDebugState(_lastWasBiased ? "Exploring (biased toward known target)" : "Exploring (randomly)");
        return null;
    }

    private boolean arrivedAtCurrentTarget(AltoClef mod) {
        return mod.getPlayer().getBlockPos().isWithinDistance(_currentTarget, ARRIVED_DISTANCE);
    }

    private BlockPos chooseNewTarget(AltoClef mod, Vec3d pos) {
        // (2026-09-04 用户"在水里不需要什么高级导航") 玩家已在水里(海洋)时, 探索
        // 应直接游向最近陆地, 而不是拒绝所有海洋方向 → 四周全海 → "no dry target"
        // 每 5s 失败重试卡死 (日志实锤: [No-Ocean] ... refusing to swim + Explore found
        // no dry target direction)。水里直接游出海洋即可, 不需要 baritone 的
        // dry-point 高级寻路。
        boolean swimming = isPlayerInWater(mod);

        Optional<BlockPos> biased = pickBiasedTarget(pos);
        if (biased.isEmpty() && _hadBiasOnce) {
            // We used to have bias targets but checked them all: done here.
            // End the task so the parent re-decides (dig, follow new memory, ...).
            _biasExhausted = true;
            Debug.logMessage("All remembered targets checked, biased exploration done.");
            return null;
        }
        for (int attempt = 0; attempt < MAX_PICK_ATTEMPTS; ++attempt) {
            double angle;
            if (swimming) {
                // 水里: 朝最近陆地游 (非海洋方向), 全海时随机方向兜底 (先动起来)。
                BlockPos land = findNearestLand(mod);
                if (land != null) {
                    angle = Math.atan2(land.getZ() - pos.z, land.getX() - pos.x)
                            + (Math.random() * 2 - 1) * Math.toRadians(BIAS_ANGLE_DEG);
                } else {
                    angle = nextRandomHeading();
                }
                _heading = angle;
                _currentBiasTarget = null;
                _lastWasBiased = false;
            } else if (biased.isPresent() && Math.random() < P_BIASED) {
                // Steer towards the target, with some randomness around its direction.
                double toTarget = Math.atan2(biased.get().getZ() - pos.z, biased.get().getX() - pos.x);
                double spread = Math.toRadians(BIAS_ANGLE_DEG);
                angle = toTarget + (Math.random() * 2 - 1) * spread;
                _heading = angle;
                _currentBiasTarget = biased.get();
                _hadBiasOnce = true;
                _lastWasBiased = true;
            } else {
                // Keep heading roughly the same direction instead of spinning around.
                angle = nextRandomHeading();
                _currentBiasTarget = null;
                _lastWasBiased = false;
            }

            // (2026-08-31 探索目的地定位到地表, 用户"explore 床/羊目标定位到地下/天上")
            // 原实现 candidate.y = 玩家当前 y: 玩家在地下洞里探索找羊时, 目标也落在
            // 地下(y 低) — baritone 朝地下走, 而羊/床都在地表永远找不到; 在高处探索时
            // 目标又落到天上。动物/床/树/村庄都是地表目标, 探索 step 的 y 应取该 xz
            // 抵达位置的地表高度, 而非玩家当前 y。
            int candX = (int) Math.floor(pos.x + Math.cos(angle) * EXPLORE_STEP);
            int candZ = (int) Math.floor(pos.z + Math.sin(angle) * EXPLORE_STEP);
            int candY = surfaceYAt(mod, candX, candZ, (int) Math.floor(pos.y));
            BlockPos candidate = new BlockPos(candX, candY, candZ);
            // Avoid retrying the exact directions we already failed on.
            if (_failedTargets.contains(candidate)) continue;
            // Don't explore into water: swimming is slow, drowning is a risk and
            // targets (mobs, ores, ...) are rarely found in the middle of a lake.
            // EXCEPTION (2026-08-21 过河探索): when we're steering towards a
            // remembered target (e.g. village loot across a river) the step
            // candidate often lands ON the river surface — that's not "exploring
            // a lake", that's a crossing. Keep it: Baritone swims surface water
            // fine and the bias keeps pulling us to the far shore.
            // (2026-09-01 下海约束, 用户"过河可以, 下海干什么") biased 跨水放行只适用
            // 于【河/湖】(biome 非 ocean)。目标落点是【海洋】时一律拒绝 —— 海洋里
            // 既没有要找的目标(动物/作物/结构), 一路游过去还浪费时间与溺水风险
            // (用户实测: 随机探索跑进海里)。非随机(biased)也拦: 海洋不该被当成
            // "跨河目标"。
            // (2026-09-04) 玩家已在水里时放行海洋方向 (直接游出海洋), 拒绝仅对
            // 陆地上的探索生效 (不下海)。否则四周全海 → no dry target → 卡死。
            if (isTargetInWater(mod, candidate) && !_lastWasBiased && !swimming) {
                _failedTargets.add(candidate);
                continue;
            }
            if (isTargetInWater(mod, candidate) && !swimming) {
                // 目标是海洋水域 → 拒绝(下海无意义); 河/湖 → 放行过河。
                if (isTargetOcean(mod, candidate)) {
                    _failedTargets.add(candidate);
                    Debug.logMessage("[No-Ocean] target " + candidate.toShortString()
                            + " is ocean water, refusing to swim into the sea.");
                    continue;
                }
                Debug.logMessage("[River-cross] heading across water towards remembered target "
                        + _currentBiasTarget + " (swimming).");
            }
            if (swimming && isTargetOcean(mod, candidate)) {
                Debug.logMessage("[Swim-out] already in water, heading towards " + candidate.toShortString());
            }
            return candidate;
        }
        // Every direction we tried was failed/water: count it as a failed step so
        // we eventually give up instead of spinning in place forever.
        _failCounter++;
        Debug.logMessage("Explore found no dry target direction, counting as a failed step.");
        return null;
    }

    /**
     * True if walking to {@code target} would put us in water: the topmost
     * non-air block of the target's column is water (lake/ocean surface).
     * Columns in unloaded chunks are treated as dry (we can't know yet).
     */
    private boolean isTargetInWater(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        for (int y = mod.getWorld().getHeight() - 1; y >= 0; --y) {
            BlockPos check = new BlockPos(target.getX(), y, target.getZ());
            if (!mod.getChunkTracker().isChunkLoaded(check)) return false;
            BlockState state = mod.getWorld().getBlockState(check);
            if (state.isAir()) continue;
            return state.getBlock() == Blocks.WATER;
        }
        return false;
    }

    /**
     * (2026-09-01 下海约束, 用户"过河可以, 下海干什么") 目标块所在的列是否是
     * 【海洋】biome (而非河/湖)。海洋里没有要找的目标(动物/作物/牧羊/结构),
     * 探索目标落到海洋时直接拒绝, 不游进大海; 河/湖(biome 非 ocean)仍可过河。
     */
    private boolean isTargetOcean(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        if (!mod.getChunkTracker().isChunkLoaded(target)) return false;
        return WorldHelper.isOcean(mod.getWorld().getBiome(target));
    }

    /**
     * (2026-09-04) 玩家是否已在水里 (游泳/泡水)。水里不需要"高级导航"——
     * 直接游出海洋即可, 若仍拒绝所有海洋方向会无路可走卡死。
     */
    private boolean isPlayerInWater(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return false;
        if (mod.getPlayer().isTouchingWater()) return true;
        BlockPos feet = mod.getPlayer().getBlockPos();
        return mod.getChunkTracker().isChunkLoaded(feet)
                && mod.getWorld().getBlockState(feet).isOf(Blocks.WATER);
    }

    /**
     * (2026-09-04) 沿 8 方向扫描, 找最近的"非海洋 biome"陆地 XZ; 全海返回 null。
     * 玩家在水里时探索目标朝此方向游, 直接离开海洋。
     */
    private BlockPos findNearestLand(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int dist = 16; dist <= 128; dist += 16) {
            for (int d = 0; d < 8; d++) {
                double ang = Math.PI / 4.0 * d;
                int x = p.getX() + (int) Math.round(Math.cos(ang) * dist);
                int z = p.getZ() + (int) Math.round(Math.sin(ang) * dist);
                BlockPos cand = new BlockPos(x, p.getY(), z);
                if (mod.getChunkTracker().isChunkLoaded(cand)
                        && !WorldHelper.isOcean(mod.getWorld().getBiome(cand))) {
                    return cand;
                }
            }
        }
        return null;
    }

    private double nextRandomHeading() {
        if (Double.isNaN(_heading)) {
            _heading = Math.random() * 2 * Math.PI;
        } else {
            double jitter = Math.toRadians(RANDOM_JITTER_DEG);
            _heading += (Math.random() * 2 - 1) * jitter;
        }
        return _heading;
    }

    /**
     * (2026-08-31) 该 xz 坐标的地表站立高度 (最高非空气方块上方一格)。列未加载时
     * 返回 fallback(玩家当前 y), 避免坏算。用于把探索目的地 y 固定在地表, 防止
     * 找床/羊/树/村庄时目标定位到地下或天上。
     */
    private int surfaceYAt(AltoClef mod, int x, int z, int fallbackY) {
        if (mod.getWorld() == null) return fallbackY;
        for (int y = mod.getWorld().getHeight() - 1; y >= 0; --y) {
            BlockPos check = new BlockPos(x, y, z);
            if (!mod.getChunkTracker().isChunkLoaded(check)) return fallbackY;
            BlockState st = mod.getWorld().getBlockState(check);
            if (st.isAir()) continue;
            return y + 1;
        }
        return fallbackY;
    }

    private Optional<BlockPos> pickBiasedTarget(Vec3d pos) {
        // [PLAINS-BIAS] 找食物/找动物探索: 只要附近已加载区块内扫到平原点,
        // 就 100% 优先导航到最近的平原点 (直奔草原再找动物/作物)。这些平原点
        // 是最高优先级, 其它 bias (动物记忆点等) 只有在没有平原点时才考虑。
        if (_foodExplore && !_plainsTargets.isEmpty()) {
            BlockPos best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (BlockPos candidate : _plainsTargets) {
                if (candidate == null) continue;
                if (_visitedBiasTargets.contains(candidate)) continue;
                if (!candidate.isWithinDistance(pos, BIAS_MAX_DISTANCE)) continue;
                double d = candidate.getSquaredDistance(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = candidate;
                }
            }
            if (best != null) return Optional.of(best);
        }

        if (_biasSources.isEmpty()) return Optional.empty();
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Supplier<Collection<BlockPos>> source : _biasSources) {
            Collection<BlockPos> candidates = source == null ? null : source.get();
            if (candidates == null) continue;
            for (BlockPos candidate : candidates) {
                if (candidate == null) continue;
                // Already walked to this one — don't steer back to it forever.
                if (_visitedBiasTargets.contains(candidate)) continue;
                if (candidate.isWithinDistance(pos, BIAS_MAX_DISTANCE)) {
                    double d = candidate.getSquaredDistance(pos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = candidate;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * [PLAINS-BIAS] (2026-08-31, 用户需求) 周期扫描玩家周围已加载区块里的
     * 平原系 biome, 收集其地表点存入 {@code _plainsTargets} 供 pickBiasedTarget
     * 100% 优先导航。为什么只在 foodExplore 上启用 (见 onTick): 只有找食物/找动物
     * 的探索才需要直奔草原提高收获; 挖矿/砍木走洞穴/森林, 不应被带偏到平原。
     *
     * 节流: 每 PLAINS_SCAN_INTERVAL_TICKS tick 扫一次; 找不到平原时按
     * PLAINS_EMPTY_LOG_INTERVAL 低节流记日志, 不刷屏。
     */
    private void updatePlainsBias(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return;
        if (++_scanTicker < PLAINS_SCAN_INTERVAL_TICKS) return;
        _scanTicker = 0;

        BlockPos player = mod.getPlayer().getBlockPos();
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -PLAINS_SCAN_RADIUS; dx <= PLAINS_SCAN_RADIUS; dx += PLAINS_SCAN_STEP) {
            for (int dz = -PLAINS_SCAN_RADIUS; dz <= PLAINS_SCAN_RADIUS; dz += PLAINS_SCAN_STEP) {
                BlockPos check = new BlockPos(player.getX() + dx, player.getY(), player.getZ() + dz);
                // 只扫已加载区块, 未加载的不算数。
                if (!mod.getChunkTracker().isChunkLoaded(check)) continue;
                // getBiome 按 xz 列返回 biome, 仿 WorldHelper.isOcean 用 matchesKey 判平原。
                if (WorldHelper.isPlains(mod.getWorld().getBiome(check))) {
                    // 目标定位到该列地表 (最高实心方块上方一格), 否则 bot 会往地下/天上走。
                    int sy = WorldHelper.getGroundHeight(mod, check.getX(), check.getZ());
                    // 只取一个代表点: 每列地表; 附近 160 格最多约 (160*2/8)^2 个点,
                    // 足够导航又不至于塞爆 visited 集合。
                    found.add(sy >= 0 ? new BlockPos(check.getX(), sy + 1, check.getZ()) : check);
                }
            }
        }

        _plainsTargets = found;

        if (!found.isEmpty()) {
            BlockPos nearest = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (BlockPos p : found) {
                double d = p.getSquaredDistance(mod.getPlayer().getPos());
                if (d < bestSq) { bestSq = d; nearest = p; }
            }
            _plainsEmptyLogCounter = 0;
            // 数量变化或首次发现时才记, 降低刷屏 (不只记一次, 平原点随移动变化时仍可观测);
            // 玩家pos + 最近平原点 + 数量。
            int n = found.size();
            if (_lastFoundCount != n || _lastFoundCount == -1) {
                String m = "[PLAINS-BIAS] food explore: player=" + player
                        + " nearest plains=" + nearest + " plainsPoints=" + n;
                Debug.logToFile("altoclef_diag.log", m);
            }
            _lastFoundCount = n;
        } else {
            _plainsEmptyLogCounter++;
            // 没检测到平原: 保持原探索行为 (不乱改去别的 biome), 低节流记一次。
            if (_plainsEmptyLogCounter >= PLAINS_EMPTY_LOG_INTERVAL) {
                Debug.logToFile("altoclef_diag.log",
                        "[PLAINS-BIAS] food explore: no plains nearby, player=" + player + " (keeping default behavior)");
                _plainsEmptyLogCounter = 0;
            }
            _lastFoundCount = 0;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _biasExhausted || _failCounter > MAX_FAIL_COUNT;
    }

    @Override
    protected boolean isEqual(Task other) {
        // Identity comparison. Callers that cache this task return the SAME
        // instance every tick (isEqual true -> no restart, state preserved).
        // When a caller swaps in a NEW instance after isFinished, the
        // framework must actually replace the old (dead) one — plain
        // "instanceof" equality would swallow the swap and freeze the bot.
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Biased exploration";
    }
}
