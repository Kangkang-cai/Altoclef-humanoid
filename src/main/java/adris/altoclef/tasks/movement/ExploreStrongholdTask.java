package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * (2026-08-25 用户设计: 人类式地牢探索找传送门)
 *
 * 在地牢内部按格栅 cell 探索, 记忆走过的 cell 防止重复。传送门框架需要
 * "走进视野 (FOV) + LOS 可见" 才算真正看到 (非透视), 看到后登记到 BlockTracker。
 *
 * (2026-08-26 用户修改):
 * - 格栅 4×4 → 12×12 (覆盖地牢房间/走廊, 粒度足够)。
 * - 前重式探索: 选取未访问 cell 时偏好玩家前进方向 (正面), 类似人类"往前探索"
 *   (用户指定 12×12 里前面占 8 格, 后面占 4 格 — 前重)。
 * - 只认地牢空间: 一段时间视野内看不到地牢砖块 → 不在牢里 (挖进矿洞/洞穴) →
 *   先回最后砖块, 不在这瞎探索浪费。
 * (2026-08-26 rev 用户要求):
 * - 在地牢内【允许挖方块】: 之前 allowBreak=false 只沿走廊走, bot 进不了地牢内部
 *   (有墙挡路), 一直在"离开砖块 → 回砖块"之间跳。改为探索期间允许挖掘, 凿墙也能
 *   进到地牢内部。
 * - 优先探索【有地砖且没探索过】的 cell: 记录"看到过砖块的 cell", 选下一个目标时
 *   大幅偏向这些 cell, 避免跑进空矿洞/纯岩石区浪费时间。
 */
public class ExploreStrongholdTask extends Task {

    // (2026-08-26 用户修改) 格栅 12×12。
    // (2026-08-27 用户实测: 总是导航到十几格外的位置 — 12×12 格栅目标天然
    // 在 6~18 格开外) 调小到 6×6, 让导航目标近一些 (3~9 格), 同时 7×7 采样
    // (±3) 正好覆盖整个 6 格 cell, 严格条件也更容易命中。
    private static final int CELL_SIZE = 6;
    // 前重: 玩家前方 (朝向点积>0) 的未访问 cell 距离权重减半 → 优先探索前方。
    private static final double FRONT_BIAS = 0.5;
    // 探索半径 (逐层扩大)。(2026-08-26 用户要求: 水平探索范围不能过大 — 48/48/192
    // 太大, bot 会跑很远; 缩小到 24/24/96, 配合"8 分钟透视兜底"覆盖远距离。)
    private static final double EXPLORE_RADIUS_START = 24;
    private static final double EXPLORE_RADIUS_STEP = 24;
    private static final double EXPLORE_RADIUS_MAX = 96;
    // 传送门"看到"距离 (视野内 + LOS)。
    private static final int PORTAL_SEE_RANGE = 12;
    // (2026-09-21 零行为诊断) 已打过 near-miss 点的门框位置, 每个只报一次。
    private final Set<BlockPos> _nearMissLogged = new HashSet<>();
    // 扫描节流: 避免每 tick 扫 12³/20³ 个方块 (卡顿)。
    private static final int SCAN_INTERVAL_TICKS = 10;
    // (2026-08-26 只认砖块空间) 视野内多久没看到地牢砖块就认为不在地牢 (ms)。
    // 用户原指定 1 秒, 但允许挖方块后 bot 凿墙进地牢内部时会暂时看不见砖块 —
    // 1 秒就触发"回砖块"会让它反复横跳。放宽到 4 秒: 既给凿墙/穿过房间留时间,
    // 又能在真的挖进空矿洞时及时回退。
    private static final long BRICK_VERIFY_MS = 4000;
    // (2026-08-26 rev 用户要求: 优先探索有地砖的 cell) 已知有地牢砖块的 cell
    // 距离权重乘这个系数 → 大幅偏向它们。
    private static final double BRICK_PREFER_FACTOR = 0.25;

    private final BlockPos _center;
    private final Set<BlockPos> _visitedCells = new HashSet<>();
    // (2026-08-26 rev 用户要求) 看到过地牢砖块的 cell → 选目标时优先探索这些 cell。
    private final Set<BlockPos> _brickCells = new HashSet<>();
    private double _exploreRadius = EXPLORE_RADIUS_START;
    private boolean _foundPortal = false;
    private int _scanTicks = 0;
    // (2026-08-26 只认砖块空间) 最近一次看到地牢砖块的时间 + 位置。
    // (2026-08-26 用户修正: 走丢时回"最后记忆的那个地牢砖块旁边", 不是回
    // _center — 根本没有"地牢中心", 中心是估计坐标可能离砖很远。)
    private long _lastBrickSeenMs = 0;
    private BlockPos _lastBrickPos = null;
    // (2026-08-26 修复: cell 导航每 tick 被替换, bot 从没真正走过去) 持久化
    // 当前导航子任务 — 同一任务反复返回直到真正走完 (像 GoToStrongholdPortalTask
    // 的 _approachTask/_digTask)。旧实现每 tick 新建 GetToXZTask, 父 onTick 每
    // tick 全量重估 → 子任务每 tick 被替换 (目标 cell 已标 visited 又选新 cell),
    // bot 只在地图上标 visited 从不移动, 1 tick 一个 cell 疯狂横跳 (日志实锤:
    // TaskPingPong 101x/5s, 几秒内把半径 96 的 cell 全"探索"完)。_goingBackToBrick
    // 标记当前导航是否为"回最后砖块" (回砖块导航期间不再重复新建, 防同样横跳)。
    private Task _navTask = null;
    private boolean _goingBackToBrick = false;

    public ExploreStrongholdTask(BlockPos center) {
        _center = center;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = System.currentTimeMillis();
        BlockPos player = mod.getPlayer().getBlockPos();
        // (2026-08-27 修复: 走过的地方必须算已访问) 旧代码只在"没有活动导航"的
        // tick 才标记当前 cell (原 step4), 导航期间走过的中间 cell 全漏记 →
        // bot 反复导航到相近位置 (用户实测)。改为每 tick 都标记当前 cell。
        _visitedCells.add(toCell(player));
        // 1. 扫描传送门 + 地牢砖块 (节流) — 无论是否在导航中都跑, 沿途都能
        // 发现传送门/刷新"在地牢"标记。
        if (++_scanTicks >= SCAN_INTERVAL_TICKS) {
            _scanTicks = 0;
            if (trySeePortal(mod)) {
                _foundPortal = true;
                setDebugState("Found the end portal!");
                return null;
            }
            // (2026-08-26 只认砖块空间) 视野内看到地牢砖块 → 刷新"在地牢"标记 +
            // 记忆砖块位置 (走丢时回这里, 不是回中心) + 记录该砖块所在 cell
            // (优先探索有地砖的 cell)。
            BlockPos brick = findVisibleStrongholdBrick(mod);
            if (brick != null) {
                _lastBrickSeenMs = now;
                _lastBrickPos = brick;
                _brickCells.add(toCell(brick));
            }
        }

        // 2. (2026-08-26 修复: 持久导航) 有未完成的导航子任务 → 保持同一任务
        // 直到真正走完, 不再每 tick 新建 (否则 bot 从没移动, 只标记 visited)。
        if (_navTask != null && !_navTask.isFinished(mod)) {
            // 走向 cell 途中长时间看不到砖块 (真挖进空矿洞) → 改回最后砖块。
            // 已在回砖块途中 (_goingBackToBrick) → 保持, 不重复新建。
            if (!_goingBackToBrick
                    && _lastBrickPos != null
                    && now - _lastBrickSeenMs > BRICK_VERIFY_MS) {
                setDebugState("Lost sight of stronghold bricks, heading back to last seen brick ("
                        + _lastBrickPos.getX() + "," + _lastBrickPos.getY() + "," + _lastBrickPos.getZ() + ").");
                _navTask = new GetToBlockTask(WorldHelper.findStandableAirNear(mod, _lastBrickPos));
                _goingBackToBrick = true;
            }
            return _navTask;
        }
        _navTask = null;
        _goingBackToBrick = false;

        // 3. (2026-08-26 用户要求: 区分地牢空间 + 修正走丢目标) 一段时间看不到
        // 地牢砖块 → 认为不在地牢 (挖进矿洞/洞穴/巧合丢视野)。【关键】只有"曾经
        // 看过砖块"才触发走丢回退 (那是在地牢里走丢了); 从没看过砖块 = 还在找
        // 地牢, 不该回退, 继续水平探索。回退目标是【最后记忆的砖块旁边】, 不是
        // _center (根本没有"地牢中心", 用户实测 bot 巧合丢视野被误判→回错误中心)。
        if (_lastBrickPos != null && now - _lastBrickSeenMs > BRICK_VERIFY_MS) {
            // (2026-08-26 用户要求: 导航目标尽量是空气) 目标是砖块旁边的可站立
            // 空气, 不是砖块本身 — 否则定位到墙里, baritone 乱挖/卡住。
            setDebugState("Lost sight of stronghold bricks, heading back to last seen brick ("
                    + _lastBrickPos.getX() + "," + _lastBrickPos.getY() + "," + _lastBrickPos.getZ() + ").");
            _navTask = new GetToBlockTask(WorldHelper.findStandableAirNear(mod, _lastBrickPos));
            _goingBackToBrick = true;
            return _navTask;
        }

        // 4. (标记当前 cell 已访问已移到 onTick 顶部, 见上 — 走过的地方都算)

        // 5. 前重式走向最近未访问 cell (持久导航, 出发前标记, 失败也不重选)。
        // (2026-08-27 用户要求: 导航终点必须是地牢空气 XYZ) 旧代码 GetToXZTask
        // (2D, 只带 XZ, Y 沿用当前高度) → bot 被导航到非地牢位置 (岩石/墙里,
        // 日志实锤总是定位到错误 Y)。改为先在该 cell 找【已验证的地牢空气站立点】
        // (区块已加载 + 本身是空气 + 脚下实心 + 上下 8 格内各至少一块地牢砖块 —
        // 地牢走廊/房间地板+天花板都是石砖, 是可靠的地牢空间签名), 用 3D
        // GetToBlockTask 导航到正确 Y 的空气格。
        // 区块已加载但验证不出 (纯岩石/矿洞 cell) → 标废直接跳过, 重新选下一个,
        // 绝不导航进墙; 仅当区块未加载 (远处 cell) 才降级 2D 先走过去加载再细化
        // (诚实说明: 远处 cell 只能分两步)。
        BlockPos target = findClosestUnvisitedCell(mod, player);
        if (target != null) {
            int cx = target.getX() * CELL_SIZE + CELL_SIZE / 2;
            int cz = target.getZ() * CELL_SIZE + CELL_SIZE / 2;
            BlockPos airPos = findStrongholdAirAt(mod, cx, cz, player);
            if (airPos != null) {
                _visitedCells.add(target);
                setDebugState("Exploring stronghold, heading to stronghold air ("
                        + airPos.getX() + "," + airPos.getY() + "," + airPos.getZ() + ")");
                _navTask = new GetToBlockTask(airPos);
            } else if (!mod.getChunkTracker().isChunkLoaded(new BlockPos(cx, player.getY(), cz))) {
                _visitedCells.add(target);
                setDebugState("Exploring stronghold, heading to cell ("
                        + target.getX() + "," + target.getZ() + ") to load chunk");
                _navTask = new GetToXZTask(cx, cz);
            } else {
                // 区块已加载但该 cell 没有地牢空气 (岩石/矿洞) → 标废, 重新选。
                _visitedCells.add(target);
                setDebugState("Cell " + target.getX() + "," + target.getZ()
                        + " has no stronghold air, skipping.");
                return null;
            }
            return _navTask;
        }

        // 6. 半径内全访问完 → 扩大半径重试。
        if (_exploreRadius < EXPLORE_RADIUS_MAX) {
            _exploreRadius += EXPLORE_RADIUS_STEP;
            setDebugState("Explored radius " + _exploreRadius + ", expanding.");
            return null;
        }

        // 7. 全部探索完仍未找到 (地牢估计位置可能偏): 清空记忆回地牢中心重新来。
        setDebugState("Explored all cells without finding portal, retrying from center.");
        _visitedCells.clear();
        _exploreRadius = EXPLORE_RADIUS_START;
        _navTask = new GetToXZTask(_center.getX(), _center.getZ());
        return _navTask;
    }

    /**
     * 扫描附近传送门框架: 视野内 + LOS 可见才算"看到", 登记到 BlockTracker。
     *
     * <p>(2026-09-21 1245 后记) 近距豁免被用户否决 ("不能近距离豁免, 刚刚明显
     * 看到了, 扫到了") —— 已撤回, 恢复纯 FOV+LOS 判据。用户观察 ("扫到了却没停")
     * 与判据的矛盾尚未定位, 加零行为诊断: 门框在 12 格内但 FOV/LOS 判负时打点
     * ([PortalSee] near-miss), 下次实跑定量验证差在哪一环, 再决定改判据还是改
     * 其他环节。
     */
    private boolean trySeePortal(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : WorldHelper.scanRegion(mod,
                player.add(-PORTAL_SEE_RANGE, -PORTAL_SEE_RANGE, -PORTAL_SEE_RANGE),
                player.add(PORTAL_SEE_RANGE, PORTAL_SEE_RANGE, PORTAL_SEE_RANGE))) {
            if (mod.getWorld().getBlockState(pos).getBlock() != Blocks.END_PORTAL_FRAME) {
                continue;
            }
            boolean inView = VisibilityHelper.isBlockInView(mod, pos);
            boolean visible = VisibilityHelper.isBlockVisible(mod, pos);
            if (inView && visible) {
                return portalSeen(mod, pos, "los");
            }
            // 零行为诊断: 每个门框位置只报一次, 不改任何行为。
            if (_nearMissLogged.add(pos.toImmutable())) {
                Debug.logMessage("[PortalSee] near-miss: frame in " + PORTAL_SEE_RANGE
                        + "r at " + pos.toShortString()
                        + " but FOV=" + inView + " LOS=" + visible);
            }
        }
        return false;
    }

    /** 看到门框的统一出口: 登记 BlockTracker + 记忆落盘 (要塞+门都记)。 */
    private boolean portalSeen(AltoClef mod, BlockPos framePos, String how) {
        mod.getBlockTracker().addBlock(Blocks.END_PORTAL_FRAME, framePos);
        // (2026-09-21) 记下亲眼确认的门框, 供父任务链回填 BeatMinecraft2Task
        // 的 _endPortalCenterLocation (修复"看到门后挂机" — 131443 实锤)。
        _portalFramePos = framePos.toImmutable();
        // (2026-09-21 要塞/末地门记忆) 门框亲眼确认 → END_PORTAL 中心落盘
        // knowledge.log, 跨会话重启后 GoToStrongholdPortalTask 直接导航过来,
        // 不再丢眼定位。
        mod.getKnowledgeMemory().rememberAt(
                adris.altoclef.trackers.KnowledgeMemory.StructureType.END_PORTAL, framePos);
        Debug.logMessage("[Explore] End portal frame " + how + " at "
                + framePos.getX() + "," + framePos.getY() + "," + framePos.getZ()
                + " -> remembered (knowledge END_PORTAL).");
        return true;
    }

    /** 视野内 + LOS 可见的地牢砖块 (区分地牢空间 vs 矿洞/洞穴), 返回位置。 */
    private BlockPos findVisibleStrongholdBrick(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : WorldHelper.scanRegion(mod,
                player.add(-PORTAL_SEE_RANGE, -PORTAL_SEE_RANGE, -PORTAL_SEE_RANGE),
                player.add(PORTAL_SEE_RANGE, PORTAL_SEE_RANGE, PORTAL_SEE_RANGE))) {
            if (isStrongholdBrick(mod.getWorld().getBlockState(pos).getBlock())
                    && VisibilityHelper.isBlockInView(mod, pos)
                    && VisibilityHelper.isBlockVisible(mod, pos)) {
                return pos;
            }
        }
        return null;
    }

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

    private BlockPos toCell(BlockPos pos) {
        return new BlockPos(Math.floorDiv(pos.getX(), CELL_SIZE), 0, Math.floorDiv(pos.getZ(), CELL_SIZE));
    }

    private boolean isSolidBlock(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).isSolidBlock(mod.getWorld(), pos);
    }

    private boolean isAir(Block b) {
        return b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR;
    }

    /** (2026-08-27 用户要求: 导航终点必须是地牢空气 XYZ, 且【必须严格】上下
     * 各至少一块地牢砖) 在目标 XZ 找"地牢空气"站立点:
     * - 本身必须真是空气 (排除半砖/楼梯/墙)
     * - 脚下实心 (能站住)
     * - 上下 8 格内各至少一块地牢砖 (地牢走廊/房间地板+天花板都是石砖)
     * 用户实测: 放宽成"上或下任一方向有砖"会定位到非法位置 (非地牢空气),
     * 必须保持上下都要求。区块未加载 → null (先 2D 走过去加载再细化);
     * 验证不出 → null (跳过该 cell, 不导航进墙)。
     * 7×7 (±3) 采样正好覆盖整个 6×6 cell (cell 中心落在墙/柱上也尽量命中);
     * 优先选离玩家最近者。 */
    private BlockPos findStrongholdAirAt(AltoClef mod, int x, int z, BlockPos player) {
        if (!mod.getChunkTracker().isChunkLoaded(new BlockPos(x, player.getY(), z))) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        // 从玩家高度往上 20 格扫到往下 60 格, 覆盖地牢层 (楼梯/房间落差/玩家
        // 当前 Y 可能偏)。
        int fromY = player.getY() + 20;
        int toY = Math.max(player.getY() - 60, 5);
        for (int y = fromY; y >= toY; y--) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = new BlockPos(x + dx, y, z + dz);
                    if (!isAir(mod.getWorld().getBlockState(pos).getBlock())) continue; // 必须真是空气
                    if (!isSolidBlock(mod, pos.down())) continue;                        // 脚下要实心
                    if (!hasBrickBelow(mod, pos) || !hasBrickAbove(mod, pos)) continue;  // 上下各至少一块地牢砖
                    double d = pos.getSquaredDistance(player);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** 该位置下方 1~8 格内是否有地牢砖块 (地牢地板通常就是石砖)。 */
    private boolean hasBrickBelow(AltoClef mod, BlockPos pos) {
        for (int dy = 1; dy <= 8; dy++) {
            if (isStrongholdBrick(mod.getWorld().getBlockState(pos.down(dy)).getBlock())) return true;
        }
        return false;
    }

    /** 该位置上方 1~8 格内是否有地牢砖块 (地牢天花板通常就是石砖)。 */
    private boolean hasBrickAbove(AltoClef mod, BlockPos pos) {
        for (int dy = 1; dy <= 8; dy++) {
            if (isStrongholdBrick(mod.getWorld().getBlockState(pos.up(dy)).getBlock())) return true;
        }
        return false;
    }

    /** 在探索半径内找最近未访问 cell (2D 距离, 前方 cell 优先 = 前重式探索)。
     * (2026-08-26 rev 用户要求: 优先探索【有地砖且没探索过】的 cell) 已知有
     * 地牢砖块的 cell 距离权重乘 BRICK_PREFER_FACTOR → 大幅偏向它们, 让 bot
     * 在地牢结构本体里探索, 而不是跑进空矿洞/纯岩石区。 */
    private BlockPos findClosestUnvisitedCell(AltoClef mod, BlockPos player) {
        int cells = (int) Math.ceil(_exploreRadius / CELL_SIZE);
        BlockPos playerCell = toCell(player);
        // 前重: 玩家朝向的前方 cell 优先 (类似人类"往前探索")。
        float yaw = mod.getPlayer().getYaw();
        double fx = -Math.sin(Math.toRadians(yaw));
        double fz = Math.cos(Math.toRadians(yaw));
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -cells; dx <= cells; dx++) {
            for (int dz = -cells; dz <= cells; dz++) {
                BlockPos cell = new BlockPos(playerCell.getX() + dx, 0, playerCell.getZ() + dz);
                if (_visitedCells.contains(cell)) continue;
                double px = cell.getX() * CELL_SIZE + CELL_SIZE / 2.0 - player.getX();
                double pz = cell.getZ() * CELL_SIZE + CELL_SIZE / 2.0 - player.getZ();
                double dist = px * px + pz * pz;
                // 前方 (点积>0) 的 cell 距离权重减半 → 优先探索。
                if (fx * px + fz * pz > 0) {
                    dist *= FRONT_BIAS;
                }
                // 有地砖的 cell 权重大幅降低 → 优先探索地牢结构本体。
                if (_brickCells.contains(cell)) {
                    dist *= BRICK_PREFER_FACTOR;
                }
                if (dist < bestDist) {
                    bestDist = dist;
                    best = cell;
                }
            }
        }
        return best;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _foundPortal;
    }

    // (2026-09-21) portalSeen 记下的门框位置 (未见过 → null)。
    private BlockPos _portalFramePos;

    /** 探索阶段亲眼确认的门框位置 (FOV+LOS 看到, 非 8 分钟兜底透视)。 */
    public BlockPos getPortalFramePos() {
        return _portalFramePos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // (2026-08-26 rev 用户要求: 在地牢内允许挖方块) 不再关闭 baritone 的
        // allowBreak — 让 bot 能凿墙进到地牢内部, 而不是只在走廊/房间之间绕。
        _lastBrickSeenMs = System.currentTimeMillis();
        _navTask = null;
        _goingBackToBrick = false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ExploreStrongholdTask;
    }

    @Override
    protected String toDebugString() {
        return "Exploring stronghold for end portal";
    }
}
