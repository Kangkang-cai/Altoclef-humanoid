package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.trackers.KnowledgeMemory;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.BiomeKeys;

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
 *    的要塞地标"推断的候选区。什么线索都没有就沿一个【持久锁定的方位角】
 *    持续前进 (跨任务实例存活, 只在 ±5° 内微调) —— 即"远征": 不原地打转、
 *    不回头, 靠持续位移穿过下界生物群系去撞结构 (不传送、不直奔目标, 保持人类式)。
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
    // (2026-09-05 通用化: 找猪灵) 猪灵音效前缀 (ambient/jealous/admiring 等,
    // 不含 piglin_brute — 猪灵蛮兵不交易, 循它的声会带偏)。前缀带 '.' 不会
    // 误匹配 "entity.piglin_brute.*"。
    private static final String PIGLIN_SOUND_PREFIX = "entity.piglin.";
    private static final double PIGLIN_HEARING_RANGE = 32;

    // 要塞地面格子扫描: 半径 / 步长 / 玩家 Y 上下扩展 / 周期 (tick)。
    private static final int FLOOR_SCAN_RADIUS = 48;
    private static final int FLOOR_SCAN_STEP = 2;
    private static final int FLOOR_SCAN_Y_RANGE = 10;
    private static final int FLOOR_SCAN_INTERVAL_TICKS = 40;
    // 玩家到最近下界砖可见位置小于该距离 → 认为已"踏上要塞附近", 进走廊模式。
    private static final double FORTRESS_NEARBY_RANGE = 32;
    // 走廊格子到达判定距离。
    private static final double CORRIDOR_ARRIVED_DISTANCE = 3;

    // =========================================================================
    // (2026-09-05 走廊记忆稳定性 + 跨会话持久化, 用户"探索 fortress 没有矿洞
    // 类似的记忆机制, 一直小区域打转") 两个问题一起修:
    //
    // 1. 【打转根因】扫描网格以玩家坐标为原点 (x = player.getX() + dx, 步长 2),
    //    玩家移动后采样点奇偶随之漂移 —— 同一块物理地板会以相邻的 BlockPos 重新
    //    出现在"没走过"集合里, frontier 永远有"新"目标, 实际是在已走过的走廊
    //    来回打转。修法: 归一到【世界原点锚定】的 2 格网格 (floorDiv), 与玩家
    //    位置彻底无关。
    // 2. 【跨会话记忆】走过的走廊格子持久化到游戏目录 altoclef_fortress_visited.log
    //    (每行 "存档:维度 x y z"), key 与矿洞 altoclef_explored.log 同款
    //    (存档名+维度, 不同世界不共享)。任务重启/游戏重开都不再重走已探索走廊。
    // =========================================================================
    private static final String FORTRESS_VISITED_FILE = "altoclef_fortress_visited.log";
    // 单 key (存档:维度) 的走廊格子记忆上限, 防文件/内存无限增长。
    private static final int MAX_PERSISTED_CELLS = 8192;
    private static final java.util.Map<String, java.util.LinkedHashSet<BlockPos>> _visitedCellsByKey
            = new java.util.HashMap<>();
    private static boolean _visitedCellsLoaded = false;

    // 外围探索一步多远, 偏置锥角 (仿 BiasedExploreTask)。
    private static final double EXPLORE_STEP = 48;
    private static final double BIAS_ANGLE_DEG = 12;
    // (2026-09-13 远征) 25° → 5°: 原 ±25° 抖动 + 48 格步长 = 布朗运动,
    // 净位移 ~√N 而非 N —— 即使实例不重建也是小区域打转。降到 5° 后是
    // "锁定方向一路向" (用户选定), 净位移 ~N。
    private static final double RANDOM_JITTER_DEG = 5;
    // 外围探索连续失败的容忍次数 (超过后本任务结束, 交给上层重决策/兜底)。
    private static final int MAX_FAIL_COUNT = 10;
    // (2026-09-13 远征取证) 诊断周期 200 tick = 10s; 距锚点半径每增长 128 格
    // 额外打一行 —— 此前日志里没有任何坐标/群系遥测, 无法证明"是否真在远征"。
    private static final int EXPEDITION_DIAG_INTERVAL_TICKS = 200;
    private static final double EXPEDITION_RADIUS_MILESTONE = 128;

    // =========================================================================
    // (2026-09-09 探深后再巡逻, 用户"还没探索完整怎么就触发巡逻了, 连刷怪笼
    // 都没找到") 根因: 走廊格子扫描半径只有 FLOOR_SCAN_RADIUS=48, bot 站在
    // 堡垒入口时"没走过的格子"只统计 48 格内的 — 前沿很快走空就被误判"探索
    // 完了"转入最久没走巡逻, 深处走廊/刷怪笼根本没进过扫描视野。
    // 修法: 前沿走空时先"探深" — 朝最远已走格再往外探 EXPLORE_EXTEND_BLOCKS,
    // 把更深的走廊拉进扫描半径; 连续 EXPAND_MAX_FAIL 次探深都没发现新格子,
    // 才承认真的走完了, 转入轮换巡逻。
    // =========================================================================
    private static final int EXPLORE_EXTEND_BLOCKS = 16;
    private static final int EXPAND_MAX_FAIL = 3;
    private int _expandFailStreak = 0;
    // 当前目标是否为"探深"目标 (不是真实走廊格, 到达/失败都不记 visited)。
    private boolean _currentIsProbe = false;

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
    // 上次诊断采样点 / 群系标签 / 上次打点时的最大半径 / 本次目标线索来源。
    private Vec3d _lastDiagPos;
    private String _lastBiomeLabel;
    private double _lastLoggedRadius = 0;
    private String _lastClue = "none";

    // =========================================================================
    // (2026-09-13 远征: 方向记忆跨任务实例) 用户明确"bot 在下界打转"。
    // 根因: _heading / _currentTarget 是实例字段, onStart 复位; 而上层
    // (TradeWithPiglinsTask / CollectBlazeRodsTask) 每 4-12 分钟被更上层销毁
    // 重建一次 (日志实锤: TradeWithPiglinsTask START 3 次, "-> null" 后立刻
    // 重新 SUB) → 每段路指向无关的随机方向, 正负抵消 → 净位移趋近 0。
    // 修法: 朝向 / 远征锚点 / 已达最大半径一律放 static, 按【存档:维度】隔离
    // (与走廊记忆 _visitedCellsByKey 同款 key), 实例重建不再失忆。
    // 注: 仅内存不落盘 —— 要修的是"单次会话内的任务重建"; 跨会话续走意义
    // 不大且锚点语义会变质, 属刻意收敛的范围。
    // =========================================================================
    private static final java.util.Map<String, Double> _headingByKey = new java.util.HashMap<>();
    private static final java.util.Map<String, BlockPos> _anchorByKey = new java.util.HashMap<>();
    private static final java.util.Map<String, Double> _maxRadiusByKey = new java.util.HashMap<>();

    // =========================================================================
    // (2026-09-13 记忆分支"查讫"机制) [Expedition] 日志实锤: clue 全是 memory,
    // radius 11~48 震荡、max 卡 49 —— bot 在记忆推断区之间往返, 走不到锁定
    // 方向分支。根因: KnowledgeMemory.getCandidateAreas 每次调用都【随机重新
    // 生成】候选点, 永远消耗不完; 且旧版把 bot 自建门的黑曜石记成
    // RUINED_PORTAL 地标, 候选全锚在门附近。
    // 修法: ① 只认 NETHER_FORTRESS 类型候选 ( RUINED_PORTAL 黑名单语义放
    // KnowledgeMemory 过载里); ② 记忆候选【到达扑空 / 寻路失败】即登记"查讫",
    // 32 格内的候选不再重复前往, 全部查讫 → 自然落空到锁定方向分支。
    // =========================================================================
    private static final java.util.Map<String, java.util.Set<BlockPos>> _checkedMemoryByKey = new java.util.HashMap<>();
    private static final double MEMORY_CHECK_RADIUS = 32;
    private static final int MAX_CHECKED_MEMORY = 64;
    // 当前目标是否为记忆推断目标 (到达/失败时登记"查讫"用)。
    private BlockPos _currentMemoryTarget;

    // =========================================================================
    // (2026-09-05 通用化) 同一套人类式探索复用于不同下界目标: 音效线索前缀 /
    // 听觉范围 / 视觉线索方块 / 是否启用走廊地面模式。默认构造 = 找要塞
    // (行为与 2026-09-04 版本完全一致); forPiglins() = 找猪灵。
    // =========================================================================
    private final String _soundPrefix;
    private final double _hearingRange;
    private final Block[] _clueBlocks;
    private final boolean _corridorEnabled;

    /** 默认: 人类式找烈焰人要塞 (循声 + 可见下界砖 + 走廊地面巡逻)。 */
    public FortressExploreTask() {
        this(BLAZE_SOUND_PREFIX, BLAZE_HEARING_RANGE,
                new Block[]{Blocks.NETHER_BRICKS}, true);
    }

    /**
     * (2026-09-05 找猪灵真探索) 循猪灵音效 + 亲眼看见的堡垒黑石砖/绯红森林
     * 地形 (猪灵生成地), 无走廊模式 — 看见猪灵本体由上层 (TradeWithPiglinsTask)
     * 切交易任务。绯红菌岩是大面积地表方块, "看到就往那走"符合人类直觉。
     */
    public static FortressExploreTask forPiglins() {
        return new FortressExploreTask(PIGLIN_SOUND_PREFIX, PIGLIN_HEARING_RANGE,
                new Block[]{Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
                        Blocks.CRIMSON_NYLIUM},
                false);
    }

    private FortressExploreTask(String soundPrefix, double hearingRange,
                                Block[] clueBlocks, boolean corridorEnabled) {
        _soundPrefix = soundPrefix;
        _hearingRange = hearingRange;
        _clueBlocks = clueBlocks;
        _corridorEnabled = corridorEnabled;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // 跟踪线索方块, 供"亲眼看见"的视觉线索 (getPerceivedLocations 用)。
        for (Block clue : _clueBlocks) {
            mod.getBlockTracker().trackBlock(clue);
        }
        _progress.reset();
        _scanTicker = 0;
        _failCounter = 0;
        _currentTarget = null;
        _corridorMode = false;
        // (2026-09-09) 探深状态随任务重开复位 (走廊记忆 _visitedCells 仍保留)。
        _expandFailStreak = 0;
        _currentIsProbe = false;
        // (2026-09-13 远征) 实例重建后恢复朝向/锚点 —— 新实例不重新随机方向,
        // 接着上一段的方向继续走。key 全程可用 (猪灵模式也走外围)。
        _persistKey = fortressVisitedKey(mod);
        Double keptHeading = _headingByKey.get(_persistKey);
        if (keptHeading != null) {
            _heading = keptHeading;
            Debug.logMessage("[Expedition] resumed heading " + fmtDeg(keptHeading)
                    + "° for " + _persistKey);
        } else {
            _heading = Double.NaN;
        }
        if (mod.getPlayer() != null) {
            _anchorByKey.computeIfAbsent(_persistKey, k -> mod.getPlayer().getBlockPos());
        }
        // 注意: _visitedCells / _lastVisitTicks 故意不清空 —— 上层在超时兜底
        // 与人类式探索间切换重跑本任务时, "走过的路"是人类式记忆, 不该失忆。
        // (2026-09-05 跨会话) 走廊模式再把上次会话走过的格子从文件里读回来
        // —— 重启任务/重开游戏也不重走老走廊。
        if (_corridorEnabled) {
            seedVisitedCellsFromDisk(_persistKey);
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        for (Block clue : _clueBlocks) {
            mod.getBlockTracker().stopTracking(clue);
        }
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

        // (2026-09-13 远征取证) 周期 / 里程碑 / 群系切换三种触发, 拿"是否真在
        // 远征"的硬数据 (此前日志无坐标/群系遥测)。
        maybeLogExpedition(mod);

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
        // (2026-09-09) 探深目标不是走廊格: 到达不记 visited (避免垃圾格子落盘),
        // 等 ≤2s 后的周期扫描把更深走廊拉进 _floorTargets 再选目标。
        if (player.isWithinDistance(_currentTarget, CORRIDOR_ARRIVED_DISTANCE)) {
            if (_currentIsProbe) {
                _currentIsProbe = false;
            } else {
                markVisited(_currentTarget);
            }
            _currentTarget = null;
            return null;
        }

        // 卡住: 记账跳过, 换方向 (不重走同一条死路)。
        // (2026-09-09) 探深目标卡住只放弃该目标, 不记 visited。
        if (!_progress.check(mod)) {
            Debug.logMessage("[FortressExplore] corridor step failed, marking visited and re-picking.");
            if (_currentIsProbe) {
                _currentIsProbe = false;
            } else {
                markVisited(_currentTarget);
            }
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
            // (2026-09-06 修复"2:1 坐标压缩走飞", 用户实测"转几圈就离开要塞
            // 导航到非城堡区域") 归一 cell 只做【记忆 key】(visited 判重/巡逻
            // 时间戳/落盘), 导航目标必须是【真实 cell】—— 旧版把 (x/2,z/2) 的
            // 归一坐标直接当 GoalNear 目标, bot 朝压缩后的世界坐标走, 一路
            // 走出要塞 (日志实锤: 要塞在 z~565, 目标却是 (64,63,254))。
            BlockPos key = canonCell(cell);
            double sq = cell.getSquaredDistance(player);
            if (!_visitedCells.contains(key)) {
                if (sq > bestFrontierSq) {
                    bestFrontierSq = sq;
                    frontier = cell;
                }
            } else {
                Long t = _lastVisitTicks.get(key);
                long tt = t == null ? 0 : t;
                if (tt < oldestTick) {
                    oldestTick = tt;
                    oldest = cell;
                }
            }
        }
        if (frontier != null) {
            // 有前沿 = 扫描半径内还有没走过的走廊, 探深失败计数清零。
            _expandFailStreak = 0;
            _currentIsProbe = false;
            return frontier;
        }
        // (2026-09-09 探深后再巡逻) 前沿走空 ≠ 城堡走完 — 48 格扫描半径外的
        // 深处走廊还看不见。先朝"最远已走格"再往外探一段, 把新走廊拉进扫描
        // 视野; 连续 EXPAND_MAX_FAIL 次都没探出新格子才承认走完, 转轮换巡逻。
        if (_expandFailStreak < EXPAND_MAX_FAIL) {
            BlockPos farthest = null;
            double bestSq = -1;
            for (BlockPos cell : _floorTargets) {
                double sq = cell.getSquaredDistance(player);
                if (sq > bestSq) {
                    bestSq = sq;
                    farthest = cell;
                }
            }
            if (farthest != null) {
                double dx = farthest.getX() - player.getX();
                double dz = farthest.getZ() - player.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                BlockPos probe;
                if (len < 1.0) {
                    probe = farthest;
                } else {
                    probe = new BlockPos(
                            farthest.getX() + (int) Math.round(dx / len * EXPLORE_EXTEND_BLOCKS),
                            farthest.getY(),
                            farthest.getZ() + (int) Math.round(dz / len * EXPLORE_EXTEND_BLOCKS));
                }
                _expandFailStreak++;
                _currentIsProbe = true;
                Debug.logMessage("[FortressExplore] corridor frontier empty within scan radius, probing deeper to "
                        + probe.toShortString() + " (attempt " + _expandFailStreak + "/" + EXPAND_MAX_FAIL + ")");
                return probe;
            }
        }
        if (_expandFailStreak >= EXPAND_MAX_FAIL) {
            Debug.logMessage("[FortressExplore] no new corridors after " + EXPAND_MAX_FAIL
                    + " deeper probes, switching to oldest-visit patrol.");
            _expandFailStreak = 0;
        }
        _currentIsProbe = false;
        return oldest;
    }

    private void markVisited(BlockPos cell) {
        BlockPos canonical = canonCell(cell);
        _visitedCells.add(canonical);
        _lastVisitTicks.put(canonical, _tickId);
        persistVisitedCell(_persistKey, canonical);
    }

    // 当前存档:维度 的持久化 key (onStart 缓存; 维度内本任务不换世界)。
    private String _persistKey;

    /**
     * (2026-09-05 走廊格子归一) 归一到世界原点锚定的 2 格网格: 旧版扫描网格
     * 以玩家坐标为原点, 玩家移动后同一块地板会以相邻 BlockPos 重新出现在
     * "没走过"集合 → 在已走过的走廊来回打转。floorDiv 后与玩家位置无关。
     */
    private static BlockPos canonCell(BlockPos p) {
        return new BlockPos(Math.floorDiv(p.getX(), 2), p.getY(), Math.floorDiv(p.getZ(), 2));
    }

    // ------------------------------------------------------------------ //
    // 走廊记忆跨会话持久化 (altoclef_fortress_visited.log, 每行 "存档:维度 x y z")
    // ------------------------------------------------------------------ //

    private static String fortressVisitedKey(AltoClef mod) {
        String levelName = "?";
        try {
            var server = MinecraftClient.getInstance().getServer();
            if (server != null && server.getSaveProperties() != null && server.getSaveProperties().getLevelName() != null) {
                levelName = server.getSaveProperties().getLevelName();
            }
        } catch (Exception ignored) {
        }
        return levelName + ":" + mod.getWorld().getRegistryKey().getValue().toString();
    }

    private static java.nio.file.Path fortressVisitedFile() {
        // (2026-09-10) 迁入 logs/altoclef/memory/, 旧根目录文件自动迁移。
        return adris.altoclef.Debug.memoryFile(FORTRESS_VISITED_FILE, "fortress_visited.log");
    }

    private static void ensureVisitedCellsLoaded() {
        if (_visitedCellsLoaded) return;
        _visitedCellsLoaded = true;
        try {
            java.nio.file.Path f = fortressVisitedFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 4) continue;
                try {
                    BlockPos p = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    java.util.LinkedHashSet<BlockPos> set = _visitedCellsByKey.computeIfAbsent(parts[0], k -> new java.util.LinkedHashSet<>());
                    if (set.size() < MAX_PERSISTED_CELLS) set.add(p);
                } catch (NumberFormatException ignored) {
                }
            }
            Debug.logMessage("[FortressExplore] loaded "
                    + _visitedCellsByKey.values().stream().mapToInt(Set::size).sum()
                    + " visited corridor cells from " + FORTRESS_VISITED_FILE);
        } catch (Exception e) {
            Debug.logMessage("[FortressExplore] failed to load " + FORTRESS_VISITED_FILE + ": " + e.getMessage());
        }
    }

    private static void appendVisitedCellLine(String key, BlockPos pos) {
        try {
            java.nio.file.Files.write(fortressVisitedFile(),
                    (key + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Debug.logMessage("[FortressExplore] failed to append visited cell: " + e.getMessage());
        }
    }

    /** onStart 时把上次会话走过的格子读回内存 (只针对当前 存档:维度)。 */
    private void seedVisitedCellsFromDisk(String key) {
        ensureVisitedCellsLoaded();
        java.util.Set<BlockPos> persisted = _visitedCellsByKey.get(key);
        if (persisted == null || persisted.isEmpty()) return;
        _visitedCells.addAll(persisted);
        Debug.logMessage("[FortressExplore] seeded " + persisted.size()
                + " previously visited corridor cells (key=" + key + ")");
    }

    /** 到达/跳过一个走廊格子时持久化 (追加一行, 与矿洞 altoclef_explored.log 同款)。 */
    private void persistVisitedCell(String key, BlockPos canonical) {
        if (key == null || canonical == null) return;
        ensureVisitedCellsLoaded();
        java.util.LinkedHashSet<BlockPos> set = _visitedCellsByKey.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>());
        if (set.contains(canonical)) return; // 已记过, 不重复追加
        if (set.size() >= MAX_PERSISTED_CELLS) return;
        set.add(canonical);
        appendVisitedCellLine(key, canonical);
    }

    /**
     * 扫描玩家周围已加载区块里的"要塞可行走地面"格子:
     * 地面是下界砖 (砖/楼梯/台阶), 格子本身与上两格为空气 —— 即走廊/桥面/
     * 房间里人类真正会走的地方。
     * (2026-09-05 通用化) 非走廊模式 (找猪灵等) 直接跳过这个 48x48x20 的
     * 周期扫描 — 猪灵不绑定走廊地面, 省掉无谓开销。
     */
    private List<BlockPos> scanFortressFloorCells(AltoClef mod) {
        List<BlockPos> out = new ArrayList<>();
        if (!_corridorEnabled) return out;
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
                        // (2026-09-06) 存【真实坐标】: 记忆 key 由 pickCorridorTarget/
                        // markVisited 现场归一。旧版在这里就存归一坐标, 导航目标跟着
                        // 被压缩成半比例世界坐标, bot 直接走出要塞 (2:1 压缩 bug)。
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

    /** 水平面 (xz) 欧氏距离。外围目标用 GoalXZ, 到达判定必须同为 2D。 */
    private static double xzDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Task tickOutside(AltoClef mod) {
        Vec3d pos = mod.getPlayer().getPos();

        // (2026-09-13 浮空目标) 到达判定改 2D: GoalXZ 只约束 xz, 站到目标列任意
        // 高度都算到。旧 3D isWithinDistance(3) 配 GoalXZ 会因 y 差 >3 判"没到",
        // 卡到 progress 超时才换目标, 白走。
        if (_currentTarget == null || xzDistance(mod.getPlayer().getBlockPos(), _currentTarget) <= 4) {
            // (2026-09-13) 到达即"查讫": 记忆推断目标扑空一次就登记, 不再去
            // 同一片记忆区反复撞 (getCandidateAreas 每次随机重生成候选点)。
            if (_currentTarget != null && _currentTarget.equals(_currentMemoryTarget)) {
                markMemoryChecked(_currentTarget);
                _currentMemoryTarget = null;
            }
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
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(_currentTarget.getX(), _currentTarget.getZ()));
            return null;
        }

        if (!_progress.check(mod)) {
            _failCounter++;
            // (2026-09-13) 寻路失败同样记"查讫" —— 走不到的地方不该反复试。
            if (_currentTarget != null && _currentTarget.equals(_currentMemoryTarget)) {
                markMemoryChecked(_currentTarget);
                _currentMemoryTarget = null;
            }
            _currentTarget = null;
            Debug.logMessage("[FortressExplore] outside step failed, picking a new direction.");
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(_currentTarget.getX(), _currentTarget.getZ()));
        }
        setDebugState("Exploring the nether (human-like)");
        return null;
    }

    /**
     * 外围目标: 循声 (烈焰人音效) > 看见的下界砖 > 记忆推断 > 持久朝向随机。
     */
    private BlockPos pickOutsideTarget(AltoClef mod, Vec3d pos) {
        // 1. 听觉: 目标生物音效 (烈焰人/猪灵), 人类能听到并大致辨向。
        Optional<Vec3d> heard = mod.getSoundTracker()
                .getNearestRecentSoundPos(_soundPrefix, pos, _hearingRange);
        if (heard.isPresent()) {
            _lastClue = "sound";
            setDebugState("Heading towards heard sounds (" + _soundPrefix + ")");
            return stepTowards(pos, heard.get().x, heard.get().z, heard.get().y);
        }

        // 2. 视觉: 亲眼看见的下界砖 (感知过滤后的缓存)。
        if (_perceivedBrickCache.isPresent()) {
            _lastClue = "brick";
            BlockPos brick = _perceivedBrickCache.get();
            setDebugState("Heading towards seen fortress bricks");
            return stepTowards(pos, brick.getX(), brick.getZ(), brick.getY());
        }

        // 3. 结构推断: 记忆地标生成的候选区 (明确是 inference 不是 observation,
        //    当方向感用)。只认 NETHER_FORTRESS —— RUINED_PORTAL 的 landmark 是
        //    黑曜石, 会把 bot 自建的下界门记成地标, 候选全锚在门附近导致绕门
        //    打转 (20260913-202230 [Expedition] clue=memory 实锤)。
        //    "查讫"过的区域 (32 格内已扑空过) 直接跳过, 全查讫 → 落到锁定方向。
        _currentMemoryTarget = null;
        List<BlockPos> candidates = mod.getKnowledgeMemory()
                .getCandidateAreas(pos, KnowledgeMemory.StructureType.NETHER_FORTRESS);
        candidates.removeIf(this::isCheckedMemory);
        if (!candidates.isEmpty()) {
            _lastClue = "memory";
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
                _currentMemoryTarget = best;
                setDebugState("Heading towards remembered fortress area (inference)");
                return stepTowards(pos, best.getX(), best.getZ(), best.getY());
            }
        }

        // 4. 随机: 沿持久锁定方位角 (远征) ±5° 微调, 不原地打转。
        _lastClue = "heading";
        double angle = nextRandomHeading();
        int candX = (int) Math.floor(pos.x + Math.cos(angle) * EXPLORE_STEP);
        int candZ = (int) Math.floor(pos.z + Math.sin(angle) * EXPLORE_STEP);
        // y 只作日志参考 (导航用 GoalXZ 不约束高度, 见 stepTowards 注释)。
        return new BlockPos(candX, (int) Math.floor(pos.y), candZ);
    }

    /** 从当前位置朝 (tx, tz) 走一步 EXPLORE_STEP。y 只作日志参考 —— 导航用 GoalXZ, 不约束高度。 */
    private BlockPos stepTowards(Vec3d from, double tx, double tz, double targetY) {
        double toTarget = Math.atan2(tz - from.z, tx - from.x);
        double spread = Math.toRadians(BIAS_ANGLE_DEG);
        double angle = toTarget + (Math.random() * 2 - 1) * spread;
        // 注意: 这里只更新实例级 _heading (日志用), 故意【不】写回 _headingByKey
        // —— 声音/下界砖等瞬态线索不该永久扭转远征方位角, 线索消失后下一轮
        // nextRandomHeading 会回到锁定的远征方向 (否则瞬态线索反复把方向来回
        // 掰, 又变成小区域打转)。
        _heading = angle;
        int candX = (int) Math.floor(from.x + Math.cos(angle) * EXPLORE_STEP);
        int candZ = (int) Math.floor(from.z + Math.sin(angle) * EXPLORE_STEP);
        // (2026-09-13 浮空目标) 旧版 y 复制出发脚高: 48 格外地形起伏 ±20 格时
        // 目标悬空, GoalNear(三维) 逼 baritone 搭柱子上天 (用户"空旷环境总搭到
        // 空中去")。改 GoalXZ 后 y 不参与寻路, 这里只留出发脚高供日志观察。
        return new BlockPos(candX, (int) Math.floor(targetY), candZ);
    }

    /**
     * (2026-09-13 远征) 锁定方向: 首次随机, 之后只在 ±RANDOM_JITTER_DEG 内微调,
     * 并写回 static —— 跨任务实例存活。旧版每次实例重建重新随机 = 小区域打转。
     */
    private double nextRandomHeading() {
        Double stored = _headingByKey.get(expeditionKey());
        if (stored == null || Double.isNaN(stored)) {
            stored = Math.random() * 2 * Math.PI;
        } else {
            stored += (Math.random() * 2 - 1) * Math.toRadians(RANDOM_JITTER_DEG);
        }
        _headingByKey.put(expeditionKey(), stored);
        _heading = stored;
        return stored;
    }

    // ------------------------------------------------------------------ //
    // (2026-09-13 远征取证) 诊断: 模式 / 群系 / 方位角 / 距锚点半径 / 线索来源
    // ------------------------------------------------------------------ //

    private String expeditionKey() {
        return _persistKey != null ? _persistKey : "?:unknown";
    }

    /** 该候选是否落在已"查讫"(扑空过) 的记忆区 32 格内。 */
    private boolean isCheckedMemory(BlockPos candidate) {
        java.util.Set<BlockPos> checked = _checkedMemoryByKey.get(expeditionKey());
        if (checked == null || checked.isEmpty()) return false;
        for (BlockPos p : checked) {
            if (p.isWithinDistance(candidate, MEMORY_CHECK_RADIUS)) return true;
        }
        return false;
    }

    private void markMemoryChecked(BlockPos target) {
        if (target == null) return;
        java.util.Set<BlockPos> checked = _checkedMemoryByKey
                .computeIfAbsent(expeditionKey(), k -> new java.util.HashSet<>());
        if (checked.size() >= MAX_CHECKED_MEMORY) return;
        if (checked.add(target.toImmutable())) {
            Debug.logMessage("[Expedition] memory area checked-empty at " + target.toShortString()
                    + " (" + checked.size() + "/" + MAX_CHECKED_MEMORY + " total)");
        }
    }

    private int checkedMemoryCount() {
        java.util.Set<BlockPos> checked = _checkedMemoryByKey.get(expeditionKey());
        return checked == null ? 0 : checked.size();
    }

    private void maybeLogExpedition(AltoClef mod) {
        if (mod.getPlayer() == null) return;
        Vec3d now = mod.getPlayer().getPos();
        BlockPos p = mod.getPlayer().getBlockPos();
        String key = expeditionKey();
        BlockPos anchor = _anchorByKey.computeIfAbsent(key, k -> p);
        double radius = Math.hypot(p.getX() - anchor.getX(), p.getZ() - anchor.getZ());
        double maxRadius = Math.max(_maxRadiusByKey.getOrDefault(key, 0.0), radius);
        _maxRadiusByKey.put(key, maxRadius);
        String biome = netherBiomeLabel(mod);
        String prevBiome = _lastBiomeLabel;
        boolean biomeChanged = prevBiome != null && !prevBiome.equals(biome);
        boolean periodic = _tickId % EXPEDITION_DIAG_INTERVAL_TICKS == 0;
        boolean milestone = maxRadius - _lastLoggedRadius >= EXPEDITION_RADIUS_MILESTONE;
        if (!periodic && !milestone && !biomeChanged) return;

        _lastLoggedRadius = maxRadius;
        String moved = _lastDiagPos == null ? "-"
                : String.format(java.util.Locale.ROOT, "%.1f", _lastDiagPos.distanceTo(now));
        _lastDiagPos = now;
        _lastBiomeLabel = biome;
        Debug.logMessage(String.format(java.util.Locale.ROOT,
                "[Expedition] mode=%s biome=%s bearing=%s anchor=(%d,%d) radius=%.0f max=%.0f"
                        + " target=%s moved=%s clue=%s fails=%d memCk=%d",
                _corridorMode ? "corridor" : "outside", biome,
                Double.isNaN(_heading) ? "?" : fmtDeg(_heading),
                anchor.getX(), anchor.getZ(), radius, maxRadius,
                _currentTarget == null ? "-" : _currentTarget.toShortString(),
                moved, _lastClue, _failCounter, checkedMemoryCount()));
        if (biomeChanged) {
            Debug.logMessage("[Expedition] biome switch " + prevBiome + " -> " + biome);
        }
    }

    /** 当前群系标签 (只认下界五群系, 其余归 "other"; 异常返 "?")。 */
    private static String netherBiomeLabel(AltoClef mod) {
        try {
            var biome = mod.getWorld().getBiome(mod.getPlayer().getBlockPos());
            if (biome.matchesKey(BiomeKeys.BASALT_DELTAS)) return "basalt_deltas";
            if (biome.matchesKey(BiomeKeys.SOUL_SAND_VALLEY)) return "soul_sand_valley";
            if (biome.matchesKey(BiomeKeys.CRIMSON_FOREST)) return "crimson_forest";
            if (biome.matchesKey(BiomeKeys.WARPED_FOREST)) return "warped_forest";
            if (biome.matchesKey(BiomeKeys.NETHER_WASTES)) return "nether_wastes";
            return "other";
        } catch (Exception e) {
            return "?";
        }
    }

    private static String fmtDeg(double rad) {
        double deg = Math.toDegrees(rad) % 360;
        if (deg < 0) deg += 360;
        return String.format(java.util.Locale.ROOT, "%.0f", deg);
    }

    /**
     * 是否"靠近看见过的下界砖" (走得到才算, 缓存位置已在感知半径内)。
     */
    private boolean nearSeenBrick(AltoClef mod) {
        return _perceivedBrickCache.isPresent()
                && _perceivedBrickCache.get().isWithinDistance(mod.getPlayer().getPos(), FORTRESS_NEARBY_RANGE + 16);
    }

    /**
     * 找"亲眼看见"的最近线索方块 (要塞=下界砖, 猪灵=堡垒黑石砖/绯红菌岩):
     * 缓存候选按距离排序, 只逐个做感知判定到第一个命中为止 (控制 LOS 射线
     * 开销, 参见 KnowledgeMemory 卡顿教训)。
     */
    private Optional<BlockPos> findNearestPerceivedBrick(AltoClef mod) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Block clue : _clueBlocks) {
            candidates.addAll(mod.getBlockTracker().getKnownLocations(clue));
        }
        if (candidates.isEmpty()) return Optional.empty();
        Vec3d from = mod.getPlayer().getPos();
        candidates.sort((a, b) -> Double.compare(a.getSquaredDistance(from), b.getSquaredDistance(from)));
        int checks = 0;
        for (BlockPos pos : candidates) {
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
        return _corridorEnabled ? "Fortress explore (human-like)" : "Nether explore (human-like)";
    }
}
