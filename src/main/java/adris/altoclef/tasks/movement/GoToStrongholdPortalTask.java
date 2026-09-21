package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.trackers.KnowledgeMemory;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GoToStrongholdPortalTask extends Task {

    // (2026-09-21 用户拍板: 鱼骨窗口 [25,-55]) 1.18.2 生成数学 (StrongholdFeature
    // → StructurePiecesCollector.shiftInto(63,-64,rand,10)): 要塞最高点 Y∈
    // [heightY-63,53), 传送门房在起点下方 10~50 格 → 实际分布约 [45,-55],
    // 主体 0~40。窗口以上直挖+近扫足够; 窗口内每降 FISHBONE_LEVEL_STEP 格
    // 四向各挖 5 格支道 (FishboneLevelTask); 扫穿窗口仍无砖 → 转水平探索。
    private static final int FISHBONE_TOP_Y = 25;
    private static final int FISHBONE_BOTTOM_Y = -55;
    private static final int FISHBONE_LEVEL_STEP = 5;
    // 鱼骨层间近扫半径 (无视 LOS/FOV, 仅限已加载区块; 支道长 5 + 层深 5,
    // 8 格半径正好覆盖"隔壁支道擦过要塞墙"的情形)。
    private static final int FISHBONE_SCAN_RADIUS = 8;
    // (2026-08-26 用户设计: 8 分钟探索兜底) 格栅探索超过 8 分钟还没找到传送门
    // → 直接回估计坐标深挖找传送门 (传送门就在估计位置正下方, 不再瞎转)。
    private static final long EXPLORE_FALLBACK_MS = 8 * 60 * 1000L;
    // 砖块"看到"距离 (视野内 + LOS)。
    private static final int BRICK_SEE_RANGE = 10;
    // (2026-08-26 用户修改: 8 分钟兜底 = 直接透视传送门) 兜底时扫世界找传送门
    // 框架的半径 (无视 LOS/FOV, 仅限已加载区块)。地牢传送门房一般离入口 ~50 格内。
    private static final int PORTAL_PERCEIVE_RANGE = 40;
    // 扫描节流: 下挖/探索期间扫描砖块避免每 tick 扫 20³=8000 个方块卡顿。
    private static final int SCAN_INTERVAL_TICKS = 10;

    // 阶段: 0=走到估计XZ, 1=直挖到窗口顶, 2=鱼骨下挖, 3=挖进地牢(暴露空气), 4=格栅探索。
    private static final int PHASE_WALK = 0;
    private static final int PHASE_DIG = 1;
    private static final int PHASE_FISHBONE = 2;
    private static final int PHASE_ENTER = 3;
    private static final int PHASE_EXPLORE = 4;

    private final LocateStrongholdCoordinatesTask _locateCoordsTask;
    private BlockPos _strongholdCoordinates;
    private final int _targetEyes;
    // 持久导航任务 (2026-08-26 修复: 每 tick 新建 → 开挖横跳)。
    private Task _approachTask = null;
    private Task _digTask = null;
    // 探索任务持久化 (记忆走过的 cell + 找到的传送门)。
    private ExploreStrongholdTask _exploreTask = null;
    private int _phase = PHASE_WALK;
    private long _exploreStartMs = 0;
    private int _scanTicks = 0;
    // 阶段 2 挖进地牢: 最多打掉的砖块数, 防止看到满墙砖无限凿墙。
    private int _enterBricksBroken = 0;
    private static final int ENTER_MAX_BRICKS = 8;
    // (2026-08-26 用户要求: 第一次导航要可靠走进地牢) 直挖可能偏几格挖偏
    // (眼睛落点 XZ 与地牢实际投影差几格 → 挖到 y=0 没见砖 → 转大范围搜索)。
    // 下挖途中每挖 PROBE_EVERY_BLOCKS 格, 在井底附近【近距扫描】地牢砖块,
    // 找到 → 挖过去从那里进地牢; 没有 → 继续下挖。保证第一次下挖大概率命中。
    private static final int PROBE_EVERY_BLOCKS = 6;
    private static final int PROBE_SCAN_RADIUS = 4;
    // 下挖起点 Y: 用于统计已挖深度, 判断何时探路。
    private int _digStartY = Integer.MAX_VALUE;
    // 鱼骨当前层 (2026-09-21 鱼骨下挖)。
    private FishboneLevelTask _fishboneLevelTask = null;
    // (2026-09-21 记忆门框直达) END_PORTAL 记忆命中后, 先走到门框旁可站立格
    // 再进格栅探索 —— 否则人在门旁 5 格还在反方向扫格子 (174359 实锤:
    // recall (9833,20,-10698), 人 (9834,18,-10693), 探索却 heading
    // (9840,24,-10662) 越走越远)。不限距离: 区块未加载先走区块 (GetToChunkTask),
    // 加载后再选可站立格。24 格只是 BeatMinecraft2Task "锁定门中心"的验证半径。
    private BlockPos _recallFramePos = null;
    // 已解析的可站立格 (区块加载后才有; null = 还在走区块阶段)。
    private BlockPos _recallStandTarget = null;
    // 接近任务缓存实例 (每 tick 新建 = 寻路横跳, 0920 实锤)。
    private Task _recallApproachTask = null;
    // 近扫导航完成后连续几轮扫描仍未见砖的计数 (防同目标反复重建导航)。
    private int _probeNavMiss = 0;

    public GoToStrongholdPortalTask(int targetEyes){
        _targetEyes = targetEyes;
        _strongholdCoordinates = null;
        _locateCoordsTask = new LocateStrongholdCoordinatesTask(targetEyes);
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_strongholdCoordinates == null) {
            _strongholdCoordinates = _locateCoordsTask.getStrongholdCoordinates().orElse(null);
            if (_strongholdCoordinates == null) {
                // (2026-09-21 要塞/末地门记忆通路, 用户"有记忆时就不用再丢眼睛了")
                // 跨会话: knowledge.log 里记过的 END_PORTAL/STRONGHOLD 中心直接
                // 当坐标用, 跳过丢眼定位 (否则每次重启游戏都从丢第一颗眼开始)。
                // 记录点是"亲眼确认"时刻 (丢眼垂直确认 / 探索看见门框), 不是推断。
                // 末端门槛位离要塞走廊可能差几十格 → PHASE_WALK 走到 XZ 再下挖,
                // 探索阶段自然会收敛到真门位。
                // (2026-09-21 用户"看见一个传送门=全看见") END_PORTAL 命中 = 门框
                // 精确位置, 不是要塞走廊坐标 —— 把它当"要塞估计点"走到精确 XZ,
                // 那个点就是门框环中心, 13/16 高的门框块挡脚 → baritone 到不了
                // 精确点在原地 wander 卡死 (150956 实锤: recall (9833,20,-10698)
                // 后 GetToXZ wander, bot 人就在门旁边 4 格)。直接进探索: 探索
                // 看见门框即完成, BeatMinecraft2Task 回填门中心。
                RecallResult recalled = recallKnowledge(mod, false, true);
                if (recalled != null) {
                    _strongholdCoordinates = recalled.pos;
                    if (recalled.isEndPortal) {
                        _phase = PHASE_EXPLORE;
                        // (2026-09-21 记忆门框直达) 记忆点 = 亲眼看到的门框方块
                        // (ExploreStrongholdTask.portalSeen 存 framePos), 不是环中心。
                        // 不限距离先走向门框: 区块未加载 → GetToChunkTask 朝区块走;
                        // 加载后 → 框旁选【有头部空间】的可站立格 (175921 实锤:
                        // findStandableAirNear 选中框正下方一格, 头顶实心框,
                        // 两格高 bot 站不进去 → baritone 冻结 NAV-STUCK)。目标绝不
                        // 能是框本身 (实心, GoalBlock 永不满足 — 150956 卡脚同族)。
                        _recallFramePos = recalled.pos;
                        setDebugState("Recalled END_PORTAL from knowledge, heading to door frame at "
                                + recalled.pos.toShortString() + ".");
                    } else {
                        setDebugState("Recalled stronghold/portal from knowledge, going there directly.");
                    }
                    return null;
                }
                // 直接扫世界找掉落眼睛 (32 格覆盖飞行落点), 不依赖陈旧缓存。
                boolean ridingBoat = mod.getPlayer() != null
                        && mod.getPlayer().getVehicle() instanceof BoatEntity;
                if (!ridingBoat
                        && mod.getItemStorage().getItemCount(Items.ENDER_EYE) < _targetEyes
                        && findNearbyDroppedEye(mod, 32) != null) {
                    setDebugState("Picking up dropped eye");
                    return new PickupDroppedItemTask(Items.ENDER_EYE, _targetEyes);
                }
                // (2026-08-26 用户要求: 一进任务就看到地牢砖块 → 不要跑回地面丢
                // 珍珠) 新任务/中途重启时 _locateCoordsTask 是新实例, _stronghold
                // Coordinates 必为 null → 默认会走丢珍珠定位 (Locate 会挖回地表丢眼)。
                // 若此刻视野内已经能看到地牢砖块 (bot 本来就在强地牢位置), 说明
                // 定位已完成, 直接跳过丢眼, 以当前位置为起点进入格栅探索, 省掉
                // "挖回地表 → 丢珍珠 → 再下来" 的无谓往返。
                if (findVisibleStrongholdBrick(mod) != null) {
                    setDebugState("Already seeing stronghold bricks, exploring directly.");
                    _strongholdCoordinates = mod.getPlayer().getBlockPos();
                    _phase = PHASE_EXPLORE;
                    return null;
                }
                setDebugState("Triangulating stronghold...");
                return _locateCoordsTask;
            }
        }

        // 阶段 0: 走到估计 XZ (持久, 走完才进下挖)。
        if (_phase == PHASE_WALK) {
            if (_approachTask != null && !_approachTask.isFinished(mod)) {
                return _approachTask;
            }
            _approachTask = null;
            if (Math.abs(mod.getPlayer().getX() - _strongholdCoordinates.getX()) > 1
                    || Math.abs(mod.getPlayer().getZ() - _strongholdCoordinates.getZ()) > 1) {
                if (WorldHelper.isLargeWaterCrossing(mod, mod.getPlayer().getBlockPos(), _strongholdCoordinates, 30)) {
                    setDebugState("Crossing large water by boat to stronghold ("
                            + _strongholdCoordinates.getX() + ", " + _strongholdCoordinates.getZ() + ")");
                    _approachTask = new BoatCrossingTask(_strongholdCoordinates);
                } else {
                    setDebugState("Walking to stronghold estimate ("
                            + _strongholdCoordinates.getX() + ", " + _strongholdCoordinates.getZ() + ")");
                    _approachTask = new GetToXZTask(_strongholdCoordinates.getX(), _strongholdCoordinates.getZ());
                }
                return _approachTask;
            }
            _digStartY = Integer.MAX_VALUE; // 重新进入下挖 → 重置探路起点
            _phase = PHASE_DIG;
        }

        // 阶段 1: 直挖到鱼骨窗口顶 (持久)。下挖过程中看到地牢砖块 → 挖进地牢;
        // Y<=FISHBONE_TOP_Y → 转鱼骨下挖 (阶段 2)。
        if (_phase == PHASE_DIG) {
            // (2026-09-21 鱼骨硬前置, 用户"必须保证背包里有水桶") 窗口内任何
            // 坠落都靠 MLGBucketFallChain 放水接住 — 没水桶不许挖, 先收集;
            // 收集卡住会显式暴露在 debug state 上, 不静默裸挖。
            if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                setDebugState("Dig precondition: fetching water bucket (fishbone fall protection).");
                return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            }
            // 直挖只负责到鱼骨窗口顶, 窗口内交给鱼骨。
            if (mod.getPlayer().getBlockPos().getY() <= FISHBONE_TOP_Y) {
                Debug.logMessage("[Fishbone] entered window at y="
                        + mod.getPlayer().getBlockPos().getY() + ", switching to fishbone.");
                _digTask = null;
                _fishboneLevelTask = null;
                _phase = PHASE_FISHBONE;
                return null;
            }
            if (_digStartY == Integer.MAX_VALUE) {
                _digStartY = mod.getPlayer().getBlockPos().getY();
            }
            if (_digTask != null && !_digTask.isFinished(mod)) {
                if (++_scanTicks >= SCAN_INTERVAL_TICKS) {
                    _scanTicks = 0;
                    if (findVisibleStrongholdBrick(mod) != null) {
                        setDebugState("Saw stronghold brick while digging, entering stronghold.");
                        _digTask = null;
                        _phase = PHASE_ENTER;
                    } else if (mod.getPlayer().getY() <= _digStartY - PROBE_EVERY_BLOCKS) {
                        // (2026-08-26 用户要求: 第一次导航可靠进地牢) 挖了
                        // PROBE_EVERY_BLOCKS 格没见砖 → 井底近距扫描附近地牢砖块;
                        // 找到 → 挖过去 (偏几格也能进地牢); 没有 → 重新计段继续下挖。
                        _digTask = null;
                        _digStartY = mod.getPlayer().getBlockPos().getY();
                        BlockPos probeBrick = findNearbyStrongholdBrick(mod, PROBE_SCAN_RADIUS);
                        if (probeBrick != null) {
                            setDebugState("Probe found stronghold brick nearby ("
                                    + probeBrick.getX() + "," + probeBrick.getY() + "," + probeBrick.getZ()
                                    + "), digging to it to enter.");
                            // (2026-08-26 用户要求: 导航目标尽量是空气) 目标是砖块
                            // 旁边的可站立空气, 不是砖块本身 (定位到墙里会乱挖)。
                            _digTask = new GetToBlockTask(WorldHelper.findStandableAirNear(mod, probeBrick));
                        } else {
                            _digTask = new GetToYTask(FISHBONE_TOP_Y);
                        }
                    }
                }
                return _digTask;
            }
            _digTask = null;
            if (findVisibleStrongholdBrick(mod) != null) {
                _phase = PHASE_ENTER;
                return null;
            }
            _digTask = new GetToYTask(FISHBONE_TOP_Y);
            return _digTask;
        }

        // 阶段 2: 鱼骨下挖 (2026-09-21 用户设计, 窗口 [25,-55])。
        // 每降 FISHBONE_LEVEL_STEP 格 → 四向各挖 5 格支道; 视野+LOS 看到砖
        // 或近扫 (半径 8) 命中 → 挖入 (阶段 3)。感知全在父任务节流扫描,
        // FishboneLevelTask 只负责移动; 支道里挖出砖块时, 近扫天然覆盖。
        if (_phase == PHASE_FISHBONE) {
            // 水桶硬前置同直挖 (MLG 中途耗水后补)。
            if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                setDebugState("Fishbone precondition: refetching water bucket.");
                return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            }
            // 感知优先 (节流): 视野砖 → 挖入; 近扫砖 → 导航过去 (sticky)。
            if (++_scanTicks >= SCAN_INTERVAL_TICKS) {
                _scanTicks = 0;
                if (findVisibleStrongholdBrick(mod) != null) {
                    setDebugState("Fishbone saw stronghold brick, entering stronghold.");
                    _fishboneLevelTask = null;
                    _digTask = null;
                    _phase = PHASE_ENTER;
                    return null;
                }
                BlockPos probe = findNearbyStrongholdBrick(mod, FISHBONE_SCAN_RADIUS);
                if (probe != null) {
                    // 贴脸 (<3 格) 直接进挖入阶段 (凿墙); 否则导航到砖旁空气。
                    if (probe.isWithinDistance(mod.getPlayer().getBlockPos(), 3)) {
                        setDebugState("Fishbone adjacent to stronghold brick, opening passage.");
                        _fishboneLevelTask = null;
                        _digTask = null;
                        _phase = PHASE_ENTER;
                        return null;
                    }
                    Debug.logMessage("[Fishbone] probe found stronghold brick at "
                            + probe.toShortString() + ", digging to it.");
                    _fishboneLevelTask = null;
                    _probeNavMiss = 0;
                    _digTask = new GetToBlockTask(WorldHelper.findStandableAirNear(mod, probe));
                    return _digTask;
                }
            }
            // 近扫导航完成但连续 2 轮扫描仍未见砖 (砖在背后/被挡) → 放弃探扫,
            // 从当前位置转格栅探索 (已在要塞隔壁, EXPLORE 有砖 cell 偏好)。
            if (_digTask != null && _digTask.isFinished(mod)) {
                _probeNavMiss++;
                if (_probeNavMiss >= 2) {
                    Debug.logMessage("[Fishbone] probe navigation finished without direct sight, falling to explore.");
                    _digTask = null;
                    _fishboneLevelTask = null;
                    _phase = PHASE_EXPLORE;
                    return null;
                }
            } else {
                _probeNavMiss = 0;
            }
            if (_digTask != null && !_digTask.isFinished(mod)) {
                return _digTask;
            }
            _digTask = null;
            // 层循环: 上一层走完 → 井底继续降一层; 扫穿窗口 → 水平探索。
            if (_fishboneLevelTask != null && !_fishboneLevelTask.isFinished(mod)) {
                return _fishboneLevelTask;
            }
            _fishboneLevelTask = null;
            int playerY = mod.getPlayer().getBlockPos().getY();
            if (playerY <= FISHBONE_BOTTOM_Y) {
                Debug.logMessage("[Fishbone] window exhausted (y=" + playerY
                        + " <= " + FISHBONE_BOTTOM_Y + ") without bricks, horizontal search.");
                _phase = PHASE_EXPLORE;
                return null;
            }
            _fishboneLevelTask = new FishboneLevelTask(
                    Math.max(playerY - FISHBONE_LEVEL_STEP, FISHBONE_BOTTOM_Y));
            return _fishboneLevelTask;
        }

        // 阶段 2: 挖进地牢 (打掉可见砖块, 暴露地牢空气, 打开通道)。
        // (2026-08-26 用户要求: 刚看到砖块可能只是擦边, 必须挖出地牢空气才算
        // 打开通道, 之后搜索才有意义。)
        if (_phase == PHASE_ENTER) {
            BlockPos brick = findVisibleStrongholdBrick(mod);
            if (brick != null && _enterBricksBroken < ENTER_MAX_BRICKS) {
                _enterBricksBroken++;
                setDebugState("Breaking stronghold brick to open passage (brick " + _enterBricksBroken + ").");
                return new DestroyBlockTask(brick);
            }
            _phase = PHASE_EXPLORE;
            return null;
        }

        // 阶段 3: 格栅探索 (12×12 前重, 只认砖块空间, 8 分钟兜底)。
        if (_phase == PHASE_EXPLORE) {
            // (2026-09-21 记忆门框直达) 先走到记忆门框旁, 再进格栅探索。
            if (_recallFramePos != null) {
                if (_recallApproachTask != null) {
                    if (!_recallApproachTask.isFinished(mod)) {
                        setDebugState("Walking to remembered door frame"
                                + (_recallStandTarget != null
                                        ? " at " + _recallStandTarget.toShortString()
                                        : " (frame chunk loading)"));
                        return _recallApproachTask;
                    }
                    if (_recallStandTarget != null) {
                        // 走到了框旁 → 直达完成, 交回探索 (探索会 LOS 看到门框收尾)。
                        Debug.logMessage("[PortalApproach] arrived at remembered door frame.");
                        _recallApproachTask = null;
                        _recallStandTarget = null;
                        _recallFramePos = null;
                    } else {
                        // 区块到达 → 重析可站立格 (下一轮分支处理)。
                        _recallApproachTask = null;
                    }
                }
                if (_recallFramePos != null) {
                    if (mod.getChunkTracker().isChunkLoaded(_recallFramePos)) {
                        BlockPos stand = findStandableWithHeadroom(mod, _recallFramePos);
                        if (stand != null) {
                            _recallStandTarget = stand;
                            _recallApproachTask = new GetToBlockTask(stand);
                            setDebugState("Walking to remembered door frame at " + stand.toShortString());
                            return _recallApproachTask;
                        }
                        // 框旁找不到可站立格 → 放弃直达, 交回探索。
                        Debug.logMessage("[PortalApproach] no standable spot with headroom near "
                                + _recallFramePos.toShortString() + ", falling to explore.");
                        _recallFramePos = null;
                    } else {
                        _recallApproachTask = new GetToChunkTask(new ChunkPos(_recallFramePos));
                        setDebugState("Walking toward remembered door frame (loading chunks)");
                        return _recallApproachTask;
                    }
                }
            }
            if (_exploreTask == null) {
                _exploreTask = new ExploreStrongholdTask(_strongholdCoordinates);
                _exploreStartMs = System.currentTimeMillis();
            }
            if (_exploreTask.isFinished(mod)) {
                return null;
            }
            // (2026-08-26 用户修改: 8 分钟兜底 = 直接透视传送门位置, 不返回估计
            // 坐标) 探索超时还没找到 → 直接扫世界找传送门框架 (无视 LOS/FOV 感知,
            // 仅限已加载区块)。找到 → 直接走向传送门深挖; 没感知到 (区块没加载) →
            // 继续探索等感知。
            if (System.currentTimeMillis() - _exploreStartMs > EXPLORE_FALLBACK_MS) {
                BlockPos portal = findPortalFramePerceived(mod);
                if (portal != null) {
                    setDebugState("Explored for 8min, going straight to perceived portal ("
                            + portal.getX() + ", " + portal.getZ() + ").");
                    _exploreTask = null;
                    _enterBricksBroken = 0;
                    _strongholdCoordinates = portal; // 目标改为透视到的传送门位置
                    _phase = PHASE_WALK; // 走到传送门, 再下挖
                    return null;
                }
                setDebugState("Explored for 8min but portal not perceived yet, continuing to explore.");
                return _exploreTask;
            }
            return _exploreTask;
        }

        return null;
    }

    /** 透视找传送门框架 (无视 LOS/FOV, 仅限已加载区块 — 未加载区块 getBlockState=空气)。
     * (2026-08-26 用户修改: 8 分钟兜底直接透视传送门位置, 不返回估计坐标。) */
    private BlockPos findPortalFramePerceived(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : WorldHelper.scanRegion(mod,
                player.add(-PORTAL_PERCEIVE_RANGE, -PORTAL_PERCEIVE_RANGE, -PORTAL_PERCEIVE_RANGE),
                player.add(PORTAL_PERCEIVE_RANGE, PORTAL_PERCEIVE_RANGE, PORTAL_PERCEIVE_RANGE))) {
            if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.END_PORTAL_FRAME) {
                return pos;
            }
        }
        return null;
    }

    /**
     * (2026-09-21 要塞/末地门记忆通路) 从 KnowledgeMemory 取回记忆点:
     * 优先 END_PORTAL (门框精确位置), 其次 STRONGHOLD (丢眼确认位置)。
     * 都没有 → null (走原丢眼定位流程)。距离上限 4096 格 (同诡异森林消费
     * 口径) —— 记录点就是本存档实测, 太远也没意义。
     * static 公开: BeatMinecraft2Task 在内联眼睛闸之前也用它判"是否旁路收眼"
     * (口径单一来源, 两处消费同一份记忆)。
     */
    private static final double KNOWLEDGE_RECALL_RANGE = 4096;

    /** 记忆召回结果: 坐标 + 类型 (END_PORTAL 命中要走"门口直达"路径, 不走精确 XZ)。 */
    private static final class RecallResult {
        final BlockPos pos;
        final boolean isEndPortal;
        RecallResult(BlockPos pos, boolean isEndPortal) {
            this.pos = pos;
            this.isEndPortal = isEndPortal;
        }
    }

    /** 只查 END_PORTAL 记忆 (供 BeatMinecraft2Task 每帧直认门中心用, 静默防刷屏)。 */
    public static BlockPos recallEndPortalFromKnowledge(AltoClef mod) {
        RecallResult r = recallKnowledge(mod, true, false);
        return r != null ? r.pos : null;
    }

    public static BlockPos recallStrongholdFromKnowledge(AltoClef mod) {
        RecallResult r = recallKnowledge(mod, false, true);
        return r != null ? r.pos : null;
    }

    private static RecallResult recallKnowledge(AltoClef mod, boolean endPortalOnly, boolean verbose) {
        Vec3d pos = mod.getPlayer().getPos();
        KnowledgeMemory knowledge = mod.getKnowledgeMemory();
        List<BlockPos> portals = knowledge.getRememberedCenters(
                KnowledgeMemory.StructureType.END_PORTAL, pos, KNOWLEDGE_RECALL_RANGE);
        if (!portals.isEmpty()) {
            BlockPos best = nearestTo(pos, portals);
            if (verbose) {
                Debug.logMessage("[PortalMemory] recalled END_PORTAL center "
                        + best.toShortString() + " (of " + portals.size() + ") -> skip eye throwing.");
            }
            return new RecallResult(best, true);
        }
        if (endPortalOnly) {
            return null;
        }
        List<BlockPos> strongholds = knowledge.getRememberedCenters(
                KnowledgeMemory.StructureType.STRONGHOLD, pos, KNOWLEDGE_RECALL_RANGE);
        if (!strongholds.isEmpty()) {
            BlockPos best = nearestTo(pos, strongholds);
            if (verbose) {
                Debug.logMessage("[PortalMemory] recalled STRONGHOLD center "
                        + best.toShortString() + " (of " + strongholds.size() + ") -> skip eye throwing.");
            }
            return new RecallResult(best, false);
        }
        return null;
    }

    private static BlockPos nearestTo(Vec3d from, List<BlockPos> candidates) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos p : candidates) {
            double sq = p.getSquaredDistance(from);
            if (sq < bestSq) {
                bestSq = sq;
                best = p;
            }
        }
        return best;
    }

    /**
     * (2026-09-21) 门框旁【有头部空间】的可站立格: 脚、头两格空气 (排除框块/
     * 岩浆/水 — {@code isAir} 双保险), 脚下实心。175921 实锤教训:
     * {@link WorldHelper#findStandableAirNear} 不查头部空间, 选中框正下方一格
     * (头顶实心框, 两格高 bot 站不进去) → baritone 冻结 NAV-STUCK。
     * 框 ±2 水平邻域、y±1 内找离玩家最近者; 找不到返回 null。
     */
    private BlockPos findStandableWithHeadroom(AltoClef mod, BlockPos frame) {
        Vec3d player = mod.getPlayer().getPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos c = frame.add(dx, dy, dz);
                    if (!mod.getChunkTracker().isChunkLoaded(c)) continue;
                    if (!mod.getWorld().getBlockState(c).isAir()) continue;
                    if (!mod.getWorld().getBlockState(c.up()).isAir()) continue;
                    if (!WorldHelper.isSolid(mod, c.down())) continue;
                    double sq = c.getSquaredDistance(player);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    /** 视野内 + LOS 可见的地牢砖块。 */
    private BlockPos findVisibleStrongholdBrick(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : WorldHelper.scanRegion(mod,
                player.add(-BRICK_SEE_RANGE, -BRICK_SEE_RANGE, -BRICK_SEE_RANGE),
                player.add(BRICK_SEE_RANGE, BRICK_SEE_RANGE, BRICK_SEE_RANGE))) {
            if (isStrongholdBrick(mod.getWorld().getBlockState(pos).getBlock())
                    && VisibilityHelper.isBlockInView(mod, pos)
                    && VisibilityHelper.isBlockVisible(mod, pos)) {
                return pos;
            }
        }
        return null;
    }

    /** 井底附近近距扫描地牢砖块 (无视 LOS/FOV, 仅限已加载区块, 小范围)。
     * (2026-08-26 用户要求: 第一次导航要可靠走进地牢) 直挖偏几格时, 在挖井底部
     * 周围扫一圈找地牢砖块, 找到 → 挖过去从那里进地牢, 不用等挖到 y=0 才转
     * 大范围搜索。范围小 (半径 4), 不算大范围透视。 */
    private BlockPos findNearbyStrongholdBrick(AltoClef mod, int radius) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : WorldHelper.scanRegion(mod,
                player.add(-radius, -4, -radius),
                player.add(radius, 2, radius))) {
            if (isStrongholdBrick(mod.getWorld().getBlockState(pos).getBlock())) {
                return pos;
            }
        }
        return null;
    }

    /** 地牢砖块 (石砖/裂纹石砖/苔藓石砖/虫蚀 + 石砖楼梯/台阶), 用于"看到砖=在地牢"。 */
    private boolean isStrongholdBrick(Block b) {
        return b == Blocks.STONE_BRICKS
                || b == Blocks.CRACKED_STONE_BRICKS
                || b == Blocks.MOSSY_STONE_BRICKS
                || b == Blocks.INFESTED_STONE_BRICKS
                || b == Blocks.INFESTED_CRACKED_STONE_BRICKS
                || b == Blocks.INFESTED_MOSSY_STONE_BRICKS
                || b == Blocks.STONE_BRICK_STAIRS
                || b == Blocks.STONE_BRICK_SLAB;
    }

    private ItemEntity findNearbyDroppedEye(AltoClef mod, int range) {
        Vec3d p = mod.getPlayer().getPos();
        double r2 = range * range;
        for (Entity e : mod.getWorld().getEntities()) {
            if (e instanceof ItemEntity ie
                    && ie.getStack().getItem() == Items.ENDER_EYE
                    && ie.isAlive()
                    && ie.squaredDistanceTo(p) < r2) {
                return ie;
            }
        }
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _exploreTask != null && _exploreTask.isFinished(mod);
    }

    /**
     * (2026-09-21) 探索阶段亲眼确认的门框位置 (未确认 → null)。
     * 供 BeatMinecraft2Task 回填 _endPortalCenterLocation, 修"看到门后挂机"。
     */
    public BlockPos getDiscoveredPortalFrame() {
        return _exploreTask != null ? _exploreTask.getPortalFramePos() : null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GoToStrongholdPortalTask;
    }

    @Override
    protected String toDebugString() {
        return "Locating Stronghold";
    }
}
