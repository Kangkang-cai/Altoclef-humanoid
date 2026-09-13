package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.function.Predicate;

/**
 * 2026-08-23: 从流动水追踪到源头
 * 策略：从流动水位置，找相邻的 level 更高的水方块，逐步追踪到 level 8 的源
 */
public class TraceFlowingWaterToSourceTask extends Task {

    private final Predicate<BlockPos> _isFlowingWater;
    private BlockPos _currentTracePos;
    // (2026-09-04 溯源失败黑名单, 用户"看到水流应定位水源") 记录本次溯源的起始流动水
    // 位置与是否放弃。父任务(CollectBucketLiquidTask)在溯源失败后把该起点加入黑名单,
    // 避免"溯源失败→重试同一处→溯源↔回地表打架"。
    private BlockPos _startPos;
    private boolean _gaveUp = false;
    private int _traceAttempts = 0;
    private static final int MAX_TRACE_ATTEMPTS = 50;
    private final Set<BlockPos> _visited = new HashSet<>();
    // (2026-09-13 v25 锁定追踪修复, 20260913-091045 实锤: 931 条 "tracing to"
    // 每 50ms 换一格, <Approaching> 任务 46-53ms 即被顶掉 = 人根本不动) 旧 onTick
    // 每 tick 找下一个更高 level 邻居并 new GetCloseToBlockTask —— 框架铁律
    // "返回任务≠任务会跑完": 下一 tick 返回新实例, 旧的立刻 STOP, baritone 一步
    // 都没走。修: 人类溯源行为 = 走到当前格【之后】再评估下一个上游格 ——
    // 在途子任务未完成时每 tick 原样续租同一实例。
    private Task _approachTask = null;
    private BlockPos _approachTarget = null;
    // (2026-09-13 v26 无进展超时 + 可达性门控, 20260913-093823 实锤: attempt 1
    // 目标 122,7,-269 而玩家在 y58+, 垂直差 51 格 → GetCloseToBlockTask 无路 →
    // 每 3-4s 一轮 Wander 空转 16s 后被上层放弃) 溯源只应跟随同一层的近邻水流,
    // 跨层水流走不到也没意义。
    private int _lastProgressTick = 0;
    private static final int TRACE_STALL_TIMEOUT_TICKS = 20 * 15;
    private static final int TRACE_MAX_VERTICAL_DELTA = 12;
    // (2026-09-13 v27 真实接近进度, 20260913-094812 实锤: 溯源 09:48:37.318 起
    // 目标 122,7,-269, 到 09:48:47.813 被父任务直接分支顶掉 —— 10.5s 只有 attempt 1
    // 一条日志 = 一步没推进; 期间 JumpStuck 卡墙 + 42.9-44.5 原地一进一退
    // (x122→123→123→122)。旧 _lastProgressTick 只在【换目标】时刷新, "朝一个到不了
    // 的格硬走 10 秒"完全漏检, 15s 总超时也够不着。改为按【与目标距离是否变小】
    // 判定: 4s 内没有实质接近 → 这条水流链走不通, 立刻放弃让父任务直取分支接手。
    private double _bestApproachSq = Double.MAX_VALUE;
    private int _lastApproachImproveTick = 0;
    private static final int TRACE_APPROACH_STALL_TICKS = 20 * 4;

    public TraceFlowingWaterToSourceTask(Predicate<BlockPos> isFlowingWater) {
        _isFlowingWater = isFlowingWater;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // 找一个流动水位置作为起点
        _startPos = findFlowingWater(mod);
        _currentTracePos = _startPos;
        _traceAttempts = 0;
        _gaveUp = false;
        _visited.clear();
        _approachTask = null;
        _approachTarget = null;
        _lastProgressTick = WorldHelper.getTicks();
        _bestApproachSq = Double.MAX_VALUE;
        _lastApproachImproveTick = WorldHelper.getTicks();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_currentTracePos == null || _traceAttempts >= MAX_TRACE_ATTEMPTS) {
            Debug.logMessage("TraceFlowingWater: giving up after " + _traceAttempts + " attempts");
            _gaveUp = true;
            return new TimeoutWanderTask(10f);
        }

        // (2026-09-13 v26) 无进展超时: 15s 没推进一格 → 放弃 (旧代码会无限
        // wander 空转到上层任务放弃, 20260913-093823 空转 16s)。
        if (WorldHelper.getTicks() - _lastProgressTick > TRACE_STALL_TIMEOUT_TICKS) {
            Debug.logMessage("TraceFlowingWater: no progress for "
                    + (TRACE_STALL_TIMEOUT_TICKS / 20) + "s, giving up at " + _currentTracePos);
            _gaveUp = true;
            return new TimeoutWanderTask(5f);
        }

        // 检查当前位置是否已经是源
        if (isSource(mod, _currentTracePos)) {
            Debug.logMessage("TraceFlowingWater: found source at " + _currentTracePos);
            return null; // 找到了，任务完成
        }

        // (2026-09-13 v25) 在途子任务未完成 → 续租同一实例, 不评估下一个目标。
        // 到达 (GetCloseToBlockTask.isFinished) 后才落到底部的"推进下一格"。
        if (_approachTask != null && _approachTarget != null
                && _approachTarget.equals(_currentTracePos)
                && !_approachTask.isFinished(mod)) {
            // (2026-09-13 v27) 真实接近进度判定 —— 见字段注释。到达前每 tick 只做
            // 这一件事: 距离实质变小就刷新计时, 长时间没接近就放弃这条水流链。
            double sq = mod.getPlayer().getPos().squaredDistanceTo(Vec3d.ofCenter(_approachTarget));
            if (sq < _bestApproachSq - 0.25) {
                _bestApproachSq = sq;
                _lastApproachImproveTick = WorldHelper.getTicks();
            } else if (WorldHelper.getTicks() - _lastApproachImproveTick > TRACE_APPROACH_STALL_TICKS) {
                Debug.logMessage("TraceFlowingWater: no approach progress for "
                        + (TRACE_APPROACH_STALL_TICKS / 20) + "s toward "
                        + _approachTarget.toShortString() + " → giving up");
                _gaveUp = true;
                return new TimeoutWanderTask(5f);
            }
            return _approachTask;
        }

        // 找相邻的 level 更高的水方块
        BlockPos higherLevel = findHigherLevelNeighbor(mod, _currentTracePos);
        if (higherLevel == null) {
            Debug.logMessage("TraceFlowingWater: no higher level neighbor found, wandering");
            _traceAttempts++;
            _currentTracePos = findFlowingWater(mod);
            _gaveUp = true;
            return new TimeoutWanderTask(5f);
        }

        // (2026-09-13 v26) 目标不可达 / 跨层太远 → 这条水流链到不了源头, 放弃
        // (父任务黑名单起点 + 15s 全局冷却), 不要派 GetCloseToBlockTask 空转。
        // v27: 走近的是 higherLevel.up() (见下), 所以两格都要判可达 —— 与父任务
        // 直接分支 isSourceLiquid 的 canReach(pos) && canReach(pos.up()) 同口径。
        int dy = Math.abs(higherLevel.getY() - mod.getPlayer().getBlockPos().getY());
        if (dy > TRACE_MAX_VERTICAL_DELTA
                || !WorldHelper.canReach(mod, higherLevel)
                || !WorldHelper.canReach(mod, higherLevel.up())) {
            Debug.logMessage("TraceFlowingWater: target " + higherLevel.toShortString()
                    + " unreachable (dy=" + dy + ") → giving up");
            _gaveUp = true;
            return new TimeoutWanderTask(5f);
        }

        // 移动到更高 level 的位置 (只在真正推进时计数/记日志 —— 每 tick 一次
        // 的旧口径让 attempts 2.5s 刷爆 50 上限, 931 条日志全是噪音)。
        _currentTracePos = higherLevel;
        _traceAttempts++;
        _lastProgressTick = WorldHelper.getTicks();
        _visited.add(higherLevel);

        Debug.logMessage("TraceFlowingWater: tracing to " + higherLevel + " (attempt " + _traceAttempts + ")");
        _approachTarget = higherLevel;
        // (2026-09-13 v27) 走近的是液体格【上方】—— 与父任务直接分支
        // GetCloseToBlockTask(blockPos.up()) 同一约定: 液体格本身站不了, 直接朝它
        // 寻路会让 baritone 绕墙/爬高 (20260913-094812: 目标 122,7,-269 是水格,
        // bot 爬到 y10 卡墙)。
        _approachTask = new GetCloseToBlockTask(higherLevel.up());
        _bestApproachSq = Double.MAX_VALUE;
        _lastApproachImproveTick = WorldHelper.getTicks();
        return _approachTask;
    }

    /** (2026-09-04) 溯源是否已失败放弃(找不到源)。供父任务黑名单起点用。 */
    public boolean gaveUp() {
        return _gaveUp;
    }

    /** (2026-09-04) 本次溯源的起始流动水位置(失败时父任务黑名单它)。 */
    public BlockPos getStartPos() {
        return _startPos;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 失败放弃或已定位到源都算完成, 父任务据此清理 _traceTask。
        if (_gaveUp) return true;
        return _currentTracePos != null && isSource(mod, _currentTracePos);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TraceFlowingWaterToSourceTask;
    }

    @Override
    protected String toDebugString() {
        return "Tracing flowing water to source";
    }

    private BlockPos findFlowingWater(AltoClef mod) {
        // 从 BlockTracker 找一个流动水位置
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        BlockPos playerPos = mod.getPlayer().getBlockPos();

        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.WATER)) {
            if (_isFlowingWater.test(pos) && !_visited.contains(pos)) {
                double dist = pos.getSquaredDistance(playerPos);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = pos;
                }
            }
        }
        return closest;
    }

    private boolean isSource(AltoClef mod, BlockPos pos) {
        var state = MinecraftClient.getInstance().world.getBlockState(pos);
        return state.getBlock() == Blocks.WATER && state.getFluidState().isStill();
    }

    private BlockPos findHigherLevelNeighbor(AltoClef mod, BlockPos pos) {
        var currentState = MinecraftClient.getInstance().world.getBlockState(pos);
        int currentLevel = currentState.getFluidState().getLevel();

        BlockPos best = null;
        int bestLevel = currentLevel;

        // 检查所有相邻位置（包括上方）
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (_visited.contains(neighbor)) continue;

            var neighborState = MinecraftClient.getInstance().world.getBlockState(neighbor);
            if (neighborState.getBlock() != Blocks.WATER) continue;

            int neighborLevel = neighborState.getFluidState().getLevel();
            // 找 level 更高的（更接近源）
            if (neighborLevel > bestLevel) {
                bestLevel = neighborLevel;
                best = neighbor;
            }
        }

        return best;
    }
}
