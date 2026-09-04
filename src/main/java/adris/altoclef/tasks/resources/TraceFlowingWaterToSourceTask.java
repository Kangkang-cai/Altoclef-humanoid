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
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_currentTracePos == null || _traceAttempts >= MAX_TRACE_ATTEMPTS) {
            Debug.logMessage("TraceFlowingWater: giving up after " + _traceAttempts + " attempts");
            _gaveUp = true;
            return new TimeoutWanderTask(10f);
        }

        // 检查当前位置是否已经是源
        if (isSource(mod, _currentTracePos)) {
            Debug.logMessage("TraceFlowingWater: found source at " + _currentTracePos);
            return null; // 找到了，任务完成
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

        // 移动到更高 level 的位置
        _currentTracePos = higherLevel;
        _traceAttempts++;
        _visited.add(higherLevel);

        Debug.logMessage("TraceFlowingWater: tracing to " + higherLevel + " (attempt " + _traceAttempts + ")");
        return new GetCloseToBlockTask(higherLevel);
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
