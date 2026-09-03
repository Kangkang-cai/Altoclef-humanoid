package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Finds the closest reachable block and runs a task on that block.
 */
public class DoToClosestBlockTask extends AbstractDoToClosestObjectTask<BlockPos> {

    private final Block[] _targetBlocks;

    private final Supplier<Vec3d> _getOriginPos;
    private final Function<Vec3d, Optional<BlockPos>> _getClosest;

    private final Function<BlockPos, Task> _getTargetTask;

    private final Predicate<BlockPos> _isValid;


    public DoToClosestBlockTask(Supplier<Vec3d> getOriginSupplier, Function<BlockPos, Task> getTargetTask, Function<Vec3d, Optional<BlockPos>> getClosestBlock, Predicate<BlockPos> isValid, Block... blocks) {
        _getOriginPos = getOriginSupplier;
        _getTargetTask = getTargetTask;
        _getClosest = getClosestBlock;
        _isValid = isValid;
        _targetBlocks = blocks;
    }

    public DoToClosestBlockTask(Function<BlockPos, Task> getTargetTask, Function<Vec3d, Optional<BlockPos>> getClosestBlock, Predicate<BlockPos> isValid, Block... blocks) {
        this(null, getTargetTask, getClosestBlock, isValid, blocks);
    }
    public DoToClosestBlockTask(Function<BlockPos, Task> getTargetTask, Predicate<BlockPos> isValid, Block... blocks) {
        this(null, getTargetTask, null, isValid, blocks);
    }
    public DoToClosestBlockTask(Function<BlockPos, Task> getTargetTask, Block... blocks) {
        this(getTargetTask, null, blockPos -> true, blocks);
    }

    @Override
    protected Vec3d getPos(AltoClef mod, BlockPos obj) {
        return WorldHelper.toVec3d(obj);
    }

    @Override
    protected Optional<BlockPos> getClosestTo(AltoClef mod, Vec3d pos) {
        if (_getClosest != null) {
            return _getClosest.apply(pos);
        }
        Optional<BlockPos> fromCache = mod.getBlockTracker().getNearestTracking(pos, _isValid, _targetBlocks);
        if (fromCache.isPresent()) return fromCache;
        // (2026-09-02 近场兜底, 用户"收熔炉没定位到身边几格的熔炉→无限探索")
        // BlockTracker 缓存滞后/重启丢记忆时, 身边的容器(熔炉/工作台)可能不在缓存
        // → getNearestTracking 空 → AbstractDoToClosestObjectTask 走 wander 无限探索
        // (tasks.log 15:29 Emptying furnace Walking to container 120,19,393 → Doing
        // something to closest block → Wander for Infinity 实锤)。扫玩家近场真实方块
        // 兜底, 眼前的目标方块直接命中, 不重做。
        return findNearbyTargetBlock(mod, pos);
    }

    /**
     * (2026-09-02) 玩家近场(半径 6)真实世界扫描, 找最近的目标方块(只看匹配块,
     * chunk 已加载)。缓存 miss 时的兜底, 防"身边容器却无限探索"。
     */
    private Optional<BlockPos> findNearbyTargetBlock(AltoClef mod, Vec3d center) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return Optional.empty();
        BlockPos player = mod.getPlayer().getBlockPos();
        final int R = 6;
        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (int dx = -R; dx <= R; dx++) {
            for (int dy = -R; dy <= R; dy++) {
                for (int dz = -R; dz <= R; dz++) {
                    BlockPos p = player.add(dx, dy, dz);
                    if (!mod.getChunkTracker().isChunkLoaded(p)) continue;
                    Block actual = mod.getWorld().getBlockState(p).getBlock();
                    boolean match = false;
                    for (Block target : _targetBlocks) {
                        if (actual == target) { match = true; break; }
                    }
                    if (!match) continue;
                    if (_isValid != null && !_isValid.test(p)) continue;
                    double dSq = p.getSquaredDistance(center);
                    if (dSq < bestSq) {
                        bestSq = dSq;
                        best = p;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    protected Vec3d getOriginPos(AltoClef mod) {
        if (_getOriginPos != null) {
            return _getOriginPos.get();
        }
        return mod.getPlayer().getPos();
    }

    @Override
    protected Task getGoalTask(BlockPos obj) {
        // 每 tick 重估目标 lambda — 目标 lambda 可能是状态机 (先 GetCloseToBlockTask
        // 导航, 靠近后切 InteractWithBlockTask 交互, 如 CollectBucketLiquidTask 装水)。
        // 不能缓存: 缓存会让导航任务一直 active 被复用, 永远切不到交互 (曾破坏装水)。
        // 放末影之眼的目标切换 PingPong 改由 AbstractDoToClosestObjectTask 的
        // pursuit 粘住逻辑解决 (当前目标有效就不随便换), 不在这里缓存。
        return _getTargetTask.apply(obj);
    }

    @Override
    protected boolean isValid(AltoClef mod, BlockPos obj) {
        // Assume we're valid since we're in the same chunk.
        if (!mod.getChunkTracker().isChunkLoaded(obj)) return true;
        // Our valid predicate
        if (_isValid != null && !_isValid.test(obj)) return false;
        // Correct block
        return mod.getBlockTracker().blockIsValid(obj, _targetBlocks);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(_targetBlocks);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_targetBlocks);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoToClosestBlockTask task) {
            return Arrays.equals(task._targetBlocks, _targetBlocks);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing something to closest block...";
    }
}