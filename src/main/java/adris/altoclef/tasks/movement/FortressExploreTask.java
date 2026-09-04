package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.helpers.VisibilityHelper;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * (2026-09-04 人类式找要塞) 替代 SearchChunkForBlockTask(NETHER_BRICKS) 的
 * "chunk 全方块透视扫描", 模拟人类在下界找烈焰人要塞的过程。
 *
 * 两种模式:
 *
 * 1. 外围探索 (没找到要塞): 带"方向感"的游走 —— 优先朝最近 N 秒内听到的
 *    烈焰人音效方向 (SoundTracker, 人类听觉), 其次朝"亲眼看见"(LOS+FOV,
 *    getPerceivedLocations)的下界砖方向, 再次朝 KnowledgeMemory 里"看见过
 *    的要塞地标"推断的候选区。什么线索都没有就沿一个大致方向随机走
 *    (不传送、不直奔目标, 保持人类式)。
 *
 * 2. 走廊探索 (踏上要塞地面, 用户核心需求): 只在要塞的可行走地面上探索
 *    —— 地面是下界砖(含楼梯/台阶)、上方两格空气的"走廊/桥面/房间"格子。
 *    像人一样沿着城堡的路走: 先走没走过的格子 (前沿), 全走过后按"最久
 *    没走"轮换巡逻。绝不因为"格子走完了"就跑回野外乱逛。
 *
 * 刷怪笼本身不在这里找: 上层 CollectBlazeRodsTask 用 getPerceivedLocations
 * (SPAWNER) 只认"亲眼看见"的刷怪笼; 走廊巡逻自然会把视野扫过要塞各处。
 */
public class FortressExploreTask extends Task implements ITaskRequiresGrounded {

    // 烈焰人音效 id 前缀 (ambient/burn/hurt/death/shoot 全系)。
    private static final String BLAZE_SOUND_PREFIX = "entity.blaze.";
    // 人类听觉放宽: MC 音效衰减约 16 格, 稍放宽到 24 用于定位方向。
    private static final double BLAZE_HEARING_RANGE = 24;

    // 要塞地面格子扫描: 半径 / 步长 / 玩家 Y 上下扩展 / 周期 (tick)。
    private static final int FLOOR_SCAN_RADIUS = 48;
    private static final int FLOOR_SCAN_STEP = 2;
    private static final int FLOOR_SCAN_Y_RANGE = 10;
    private static final int FLOOR_SCAN_INTERVAL_TICKS = 40;
    // 玩家到最近下界砖可见位置小于该距离 → 认为已"踏上要塞附近", 进走廊模式。
    private static final double FORTRESS_NEARBY_RANGE = 32;
    // 走廊格子到达判定距离。
    private static final double CORRIDOR_ARRIVED_DISTANCE = 3;

    // 外围探索一步多远, 偏置锥角 (仿 BiasedExploreTask)。
    private static final double EXPLORE_STEP = 48;
    private static final double BIAS_ANGLE_DEG = 12;
    private static final double RANDOM_JITTER_DEG = 25;
    // 外围探索连续失败的容忍次数 (超过后本任务结束, 交给上层重决策/兜底)。
    private static final int MAX_FAIL_COUNT = 10;

    private final MovementProgressChecker _progress = new MovementProgressChecker();

    // 走廊格子状态: 已走集合 + 最近一次走的时间戳 (用于"最久没走"巡逻轮换)。
    private final Set<BlockPos> _visitedCells = new HashSet<>();
    private final Map<BlockPos, Long> _lastVisitTicks = new HashMap<>();
    // 最近一次扫描到的走廊格子。
    private List<BlockPos> _floorTargets = new ArrayList<>();
    // 缓存的"看见的下界砖"位置 (每 FLOOR_SCAN_INTERVAL_TICKS 刷新一次, 控射线开销)。
    private Optional<BlockPos> _perceivedBrickCache = Optional.empty();

    private BlockPos _currentTarget;
    private boolean _corridorMode;
    private int _scanTicker;
    private int _failCounter;
    // 持久朝向 (外围随机游走不原地打转)。
    private double _heading = Double.NaN;
    // 本次到达目标是否走过 (用于日志)。
    private long _tickId = 0;

    @Override
    protected void onStart(AltoClef mod) {
        // 跟踪下界砖, 供"看见要塞"的视觉线索 (getPerceivedLocations 用)。
        mod.getBlockTracker().trackBlock(Blocks.NETHER_BRICKS);
        _progress.reset();
        _scanTicker = 0;
        _failCounter = 0;
        _currentTarget = null;
        _corridorMode = false;
        // 注意: _visitedCells / _lastVisitTicks 故意不清空 —— 上层在超时兜底
        // 与人类式探索间切换重跑本任务时, "走过的路"是人类式记忆, 不该失忆。
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.NETHER_BRICKS);
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        _tickId++;

        // 周期扫描: 要塞地面格子 + 可见下界砖。
        if (++_scanTicker >= FLOOR_SCAN_INTERVAL_TICKS) {
            _scanTicker = 0;
            _floorTargets = scanFortressFloorCells(mod);
            _perceivedBrickCache = findNearestPerceivedBrick(mod);
        }

        // 模式判定: 有走廊格子 / 看见了下界砖 → 上路。一旦进走廊模式,
        // 只在要塞地面轮换, 不回野外 (用户: 在城堡的路探索最像人、概率最大)。
        boolean corridorAvailable = !_floorTargets.isEmpty();
        if (_corridorMode && !corridorAvailable) {
            // 已在走廊模式: 扫描失败/没格子时保持上一个目标走, 别掉回野外。
            corridorAvailable = _currentTarget != null;
        }
        if (!_corridorMode && (corridorAvailable || _perceivedBrickCache.isPresent())) {
            _corridorMode = corridorAvailable || nearSeenBrick(mod);
        }

        if (_corridorMode) {
            return tickCorridor(mod);
        }
        return tickOutside(mod);
    }

    // ------------------------------------------------------------------ //
    // 走廊模式: 只踩要塞地面
    // ------------------------------------------------------------------ //

    private Task tickCorridor(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();

        if (_currentTarget == null) {
            _currentTarget = pickCorridorTarget(mod, player);
            if (_currentTarget == null) {
                setDebugState("On fortress but no walkable floor found yet");
                return null;
            }
            _progress.reset();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
            Debug.logMessage("[FortressExplore] corridor target " + _currentTarget.toShortString()
                    + " (floorTargets=" + _floorTargets.size() + ")");
            return null;
        }

        // 到达: 记账, 换下一个格子。
        if (player.isWithinDistance(_currentTarget, CORRIDOR_ARRIVED_DISTANCE)) {
            markVisited(_currentTarget);
            _currentTarget = null;
            return null;
        }

        // 卡住: 记账跳过, 换方向 (不重走同一条死路)。
        if (!_progress.check(mod)) {
            Debug.logMessage("[FortressExplore] corridor step failed, marking visited and re-picking.");
            markVisited(_currentTarget);
            _currentTarget = null;
            _failCounter++;
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
        }
        setDebugState("Patrolling fortress corridors");
        return null;
    }

    /**
     * 走廊目标选择 (人类式): 先走"没走过的" (前沿, 优先远的——把走廊走穿),
     * 全走过后按"最久没走"轮换巡逻 —— 像人在城堡里反复巡查各条路等刷怪。
     */
    private BlockPos pickCorridorTarget(AltoClef mod, BlockPos player) {
        BlockPos frontier = null;
        double bestFrontierSq = -1;
        BlockPos oldest = null;
        long oldestTick = Long.MAX_VALUE;

        for (BlockPos cell : _floorTargets) {
            double sq = cell.getSquaredDistance(player);
            if (!_visitedCells.contains(cell)) {
                if (sq > bestFrontierSq) {
                    bestFrontierSq = sq;
                    frontier = cell;
                }
            } else {
                Long t = _lastVisitTicks.get(cell);
                long tt = t == null ? 0 : t;
                if (tt < oldestTick) {
                    oldestTick = tt;
                    oldest = cell;
                }
            }
        }
        return frontier != null ? frontier : oldest;
    }

    private void markVisited(BlockPos cell) {
        _visitedCells.add(cell);
        _lastVisitTicks.put(cell, _tickId);
    }

    /**
     * 扫描玩家周围已加载区块里的"要塞可行走地面"格子:
     * 地面是下界砖 (砖/楼梯/台阶), 格子本身与上两格为空气 —— 即走廊/桥面/
     * 房间里人类真正会走的地方。
     */
    private List<BlockPos> scanFortressFloorCells(AltoClef mod) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos player = mod.getPlayer().getBlockPos();
        int yMin = player.getY() - FLOOR_SCAN_Y_RANGE;
        int yMax = player.getY() + FLOOR_SCAN_Y_RANGE;
        for (int dx = -FLOOR_SCAN_RADIUS; dx <= FLOOR_SCAN_RADIUS; dx += FLOOR_SCAN_STEP) {
            for (int dz = -FLOOR_SCAN_RADIUS; dz <= FLOOR_SCAN_RADIUS; dz += FLOOR_SCAN_STEP) {
                int x = player.getX() + dx;
                int z = player.getZ() + dz;
                for (int y = yMin; y <= yMax; ++y) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
                    if (isFortressFloorCell(mod, pos)) {
                        out.add(pos);
                    }
                }
            }
        }
        return out;
    }

    private boolean isFortressFloorCell(AltoClef mod, BlockPos pos) {
        Block ground = mod.getWorld().getBlockState(pos.down()).getBlock();
        return (ground == Blocks.NETHER_BRICKS
                || ground == Blocks.NETHER_BRICK_STAIRS
                || ground == Blocks.NETHER_BRICK_SLAB)
                && mod.getWorld().getBlockState(pos).isAir()
                && mod.getWorld().getBlockState(pos.up()).isAir();
    }

    // ------------------------------------------------------------------ //
    // 外围模式: 带方向感的游走 (音效 > 看见的下界砖 > 结构推断 > 随机)
    // ------------------------------------------------------------------ //

    private Task tickOutside(AltoClef mod) {
        Vec3d pos = mod.getPlayer().getPos();

        if (_currentTarget == null || mod.getPlayer().getBlockPos().isWithinDistance(_currentTarget, 3)) {
            _currentTarget = pickOutsideTarget(mod, pos);
            if (_currentTarget == null) {
                if (++_failCounter > MAX_FAIL_COUNT) {
                    Debug.logMessage("[FortressExplore] outside explore exhausted, handing back to parent.");
                    return null;
                }
                setDebugState("Wandering the nether (no clues yet)");
                return null;
            }
            _progress.reset();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 2));
            return null;
        }

        if (!_progress.check(mod)) {
            _failCounter++;
            _currentTarget = null;
            Debug.logMessage("[FortressExplore] outside step failed, picking a new direction.");
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 2));
        }
        setDebugState("Exploring the nether for a fortress");
        return null;
    }

    /**
     * 外围目标: 循声 (烈焰人音效) > 看见的下界砖 > 记忆推断 > 持久朝向随机。
     */
    private BlockPos pickOutsideTarget(AltoClef mod, Vec3d pos) {
        // 1. 听觉: 烈焰人音效, 人类能听到并大致辨向。
        Optional<Vec3d> heard = mod.getSoundTracker()
                .getNearestRecentSoundPos(BLAZE_SOUND_PREFIX, pos, BLAZE_HEARING_RANGE);
        if (heard.isPresent()) {
            setDebugState("Heading towards heard blaze sounds");
            return stepTowards(pos, heard.get().x, heard.get().z, heard.get().y);
        }

        // 2. 视觉: 亲眼看见的下界砖 (感知过滤后的缓存)。
        if (_perceivedBrickCache.isPresent()) {
            BlockPos brick = _perceivedBrickCache.get();
            setDebugState("Heading towards seen fortress bricks");
            return stepTowards(pos, brick.getX(), brick.getZ(), brick.getY());
        }

        // 3. 结构推断: KnowledgeMemory 里"看见过的要塞地标"生成的候选区
        //    (明确是 inference 不是 observation, 当方向感用)。
        List<BlockPos> candidates = mod.getKnowledgeMemory().getCandidateAreas(pos);
        if (!candidates.isEmpty()) {
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (BlockPos c : candidates) {
                double sq = c.getSquaredDistance(pos);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = c;
                }
            }
            if (best != null) {
                setDebugState("Heading towards remembered fortress area (inference)");
                return stepTowards(pos, best.getX(), best.getZ(), best.getY());
            }
        }

        // 4. 随机: 沿持久朝向 ±抖动, 不原地打转。
        double angle = nextRandomHeading();
        int candX = (int) Math.floor(pos.x + Math.cos(angle) * EXPLORE_STEP);
        int candZ = (int) Math.floor(pos.z + Math.sin(angle) * EXPLORE_STEP);
        return new BlockPos(candX, (int) Math.floor(pos.y), candZ);
    }

    /** 从当前位置朝 (tx, tz) 走一步 EXPLORE_STEP, y 取目标 y (要塞/声源高度)。 */
    private BlockPos stepTowards(Vec3d from, double tx, double tz, double targetY) {
        double toTarget = Math.atan2(tz - from.z, tx - from.x);
        double spread = Math.toRadians(BIAS_ANGLE_DEG);
        double angle = toTarget + (Math.random() * 2 - 1) * spread;
        _heading = angle;
        int candX = (int) Math.floor(from.x + Math.cos(angle) * EXPLORE_STEP);
        int candZ = (int) Math.floor(from.z + Math.sin(angle) * EXPLORE_STEP);
        return new BlockPos(candX, (int) Math.floor(targetY), candZ);
    }

    private double nextRandomHeading() {
        if (Double.isNaN(_heading)) {
            _heading = Math.random() * 2 * Math.PI;
        } else {
            _heading += (Math.random() * 2 - 1) * Math.toRadians(RANDOM_JITTER_DEG);
        }
        return _heading;
    }

    /**
     * 是否"靠近看见过的下界砖" (走得到才算, 缓存位置已在感知半径内)。
     */
    private boolean nearSeenBrick(AltoClef mod) {
        return _perceivedBrickCache.isPresent()
                && _perceivedBrickCache.get().isWithinDistance(mod.getPlayer().getPos(), FORTRESS_NEARBY_RANGE + 16);
    }

    /**
     * 找"亲眼看见"的最近下界砖: 缓存候选按距离排序, 只逐个做感知判定到
     * 第一个命中为止 (控制 LOS 射线开销, 参见 KnowledgeMemory 卡顿教训)。
     */
    private Optional<BlockPos> findNearestPerceivedBrick(AltoClef mod) {
        List<BlockPos> bricks = mod.getBlockTracker().getKnownLocations(Blocks.NETHER_BRICKS);
        if (bricks.isEmpty()) return Optional.empty();
        Vec3d from = mod.getPlayer().getPos();
        List<BlockPos> sorted = new ArrayList<>(bricks);
        sorted.sort((a, b) -> Double.compare(a.getSquaredDistance(from), b.getSquaredDistance(from)));
        int checks = 0;
        for (BlockPos pos : sorted) {
            // 只做有限次射线: 最近的几个都看不见 (背对着) 就等下一轮。
            if (++checks > 12) break;
            if (VisibilityHelper.isBlockPerceivable(mod, pos)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 永不自行结束: 找到刷怪笼由上层 (CollectBlazeRodsTask) 切任务,
        // 长期找不到由上层超时兜底开关接管。
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        // 身份比较: 上层缓存同一个实例每 tick 返回, 状态 (已走格子) 得以保留。
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Fortress explore (human-like)";
    }
}
