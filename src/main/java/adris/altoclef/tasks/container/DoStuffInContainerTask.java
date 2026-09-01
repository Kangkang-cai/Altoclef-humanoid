package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Optional;


/**
 * Interacts with a container, obtaining and placing one if none were found nearby.
 */
public abstract class DoStuffInContainerTask extends Task {

    private final ItemTarget _containerTarget;
    private final Block[] _containerBlocks;

    private final PlaceBlockNearbyTask _placeTask;
    // If we decided on placing, force place for at least 10 seconds
    private final TimerGame _placeForceTimer = new TimerGame(10);
    // If we just placed something, stop placing and try going to the nearest container.
    private final TimerGame _justPlacedTimer = new TimerGame(3);
    private BlockPos _cachedContainerPosition = null;
    private Task _openTableTask;

    public DoStuffInContainerTask(Block[] containerBlocks, ItemTarget containerTarget) {
        _containerBlocks = containerBlocks;
        _containerTarget = containerTarget;

        _placeTask = new PlaceBlockNearbyTask(_containerBlocks);
    }

    public DoStuffInContainerTask(Block containerBlock, ItemTarget containerTarget) {
        this(new Block[]{containerBlock}, containerTarget);
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (_openTableTask == null) {
            _openTableTask = new DoToClosestBlockTask(InteractWithBlockTask::new, _containerBlocks);
        }

        mod.getBlockTracker().trackBlock(_containerBlocks);

        // Protect container since we might place it.
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(ItemHelper.blocksToItems(_containerBlocks));
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // If we're placing, keep on placing.
        if (mod.getItemStorage().hasItem(ItemHelper.blocksToItems(_containerBlocks)) && _placeTask.isActive() && !_placeTask.isFinished(mod)) {
            setDebugState("Placing container");
            return _placeTask;
        }

        if (isContainerOpen(mod)) {
            return containerSubTask(mod);
        }

        // infinity if such a container does not exist.
        double costToWalk = Double.POSITIVE_INFINITY;

        Optional<BlockPos> nearest;

        Vec3d currentPos = mod.getPlayer().getPos();
        BlockPos override = overrideContainerPosition(mod);

        if (override != null && mod.getBlockTracker().blockIsValid(override, _containerBlocks)) {
            // We have an override so go there instead.
            nearest = Optional.of(override);
        } else {
            // Track nearest container
            nearest = mod.getBlockTracker().getNearestTracking(currentPos, blockPos -> WorldHelper.canReach(mod, blockPos), _containerBlocks);
        }
        if (nearest.isEmpty()) {
            // If all else fails, try using our placed task
            nearest = Optional.ofNullable(_placeTask.getPlaced());
            if (nearest.isPresent() && !mod.getBlockTracker().blockIsValid(nearest.get(), _containerBlocks)) {
                nearest = Optional.empty();
            }
        }
        if (nearest.isEmpty()) {
            // (2026-08-30 分而治之: 眼前真实容器优先于缓存) 容器可能来自上一个
            // 中断的任务(如已放置的熔炉), tracker 因重启/中断丢失记忆 → nearest
            // 空了就误走"造新容器"。抢在造之前, 主动扫 bot 近场真实方块, 看到
            // 一个目标容器(熔炉就在眼前)直接过去用, 不重做。
            nearest = findNearbyContainerBlock(mod, currentPos);
            if (nearest.isPresent()) {
                mod.getBlockTracker().trackBlock(_containerBlocks);
            }
        }
        if (nearest.isPresent()) {
            costToWalk = BaritoneHelper.calculateGenericHeuristic(currentPos, WorldHelper.toVec3d(nearest.get()));
        }

        // Make a new container if going to the container is a pretty bad cost.
        // Also keep on making the container if we're stuck in some
        if (costToWalk > getCostToMakeNew(mod)) {
            _placeForceTimer.reset();
        }
        // (2026-09-01 工作台 Place↔Walking 乒乓根因修复)
        boolean nearestMissing = nearest.isEmpty();
        boolean placeForceActive = !_placeForceTimer.elapsed()
                && StorageHelper.countItemsInInventoryReal(mod, _containerTarget.getMatches()) > 0;
        if (nearestMissing || placeForceActive) {
            // It's cheaper to make a new one, or our only option.

            // We're no longer going to our previous container.
            _cachedContainerPosition = null;

            // (2026-08-31 统一所有权模型) 判定"要不要现场造/拿一张新的同类容器"
            // 一律用统一的 USE 语义: 背包真有 或 世界里存在有效同类方块(已放置
            // 的台子/熔炉) 都算"我有可用的", 就不去再合成一张。旧逻辑
            // `!hasItem(_containerTarget)` 只看背包物品 —— 台子一放下就变成方块、
            // 不再是背包物品 → 恒 true → 反复"Getting container item → 现场再造",
            // 每轮再造进 2x2 合成格又被网格清理器打断, 造出十张台子/横跳
            // (日志 TaskPingPong 实锤)。统一用 USE, 一台即可复用, 不用给台子
            // 单独维护任何状态。
            if (!StorageHelper.isItemOwned(mod, StorageHelper.Ownership.USE, _containerTarget.getMatches())) {
                setDebugState("Getting container item");
                Debug.logTrace("craft-place", "要造/取新容器 " + _containerTarget.toString()
                        + ", isItemOwned(USE)=" + StorageHelper.isItemOwned(mod, StorageHelper.Ownership.USE, _containerTarget.getMatches())
                        + ", 背包实时数=" + StorageHelper.countItemsInInventoryReal(mod, _containerTarget.getMatches())
                        + ", nearest=" + nearest.orElse(null));
                return TaskCatalogue.getItemTask(_containerTarget);
            }

            if (StorageHelper.countItemsInInventoryReal(mod, _containerTarget.getMatches()) > 0) {
                // 台子真背在包里: 放下来用。
                setDebugState("Placing container...");
                Debug.logTrace("craft-place", "背包有容器 " + _containerTarget.toString() + ", 放下来用");
                _justPlacedTimer.reset();
                return _placeTask;
            }

            // 手头没有现成台子物品, 但世界里有(刚放/记忆中): 复用那一张, 不重造。
            BlockPos stillThere = _placeTask.getPlaced();
            if (stillThere != null && mod.getBlockTracker().blockIsValid(stillThere, _containerBlocks)) {
                setDebugState("Using the container we already placed");
                Debug.logTrace("craft-place", "复用已放置容器 " + _containerTarget.toString() + " @" + stillThere.toShortString());
                _cachedContainerPosition = stillThere;
                _placeForceTimer.reset();
                _justPlacedTimer.reset();
                return new DoToClosestBlockTask(InteractWithBlockTask::new, _containerBlocks);
            }
            // 记忆里有台子但刚放点已失效: 也先按"有可用"放一张, 不反复去合成。
            setDebugState("Placing container (re-using existing)...");
            Debug.logTrace("craft-place", "世界有容器但刚放点失效, 重新放置 " + _containerTarget.toString());
            _justPlacedTimer.reset();
            return _placeTask;
        }

        // This is insanely cursed.
        // TODO: Finish committing to optionals, this is ugly.
        _cachedContainerPosition = nearest.get();

        // Walk to it and open it

        // Wait for food
        if (mod.getFoodChain().isTryingToEat()) {
            setDebugState("Waiting for eating...");
            return null;
        }
        setDebugState("Walking to container... " + nearest.get().toShortString());

        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            setDebugState("Clearing cursor slot (otherwise this causes BIG problems)");
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false).or(() -> StorageHelper.getGarbageSlot(mod));
            if (toMoveTo.isEmpty()) {
                return new EnsureFreeInventorySlotTask();
            } else {
                return new ClickSlotTask(toMoveTo.get());
            }
        }
        return _openTableTask;
        //return new GetToBlockTask(nearest, true);
    }

    public ItemTarget getContainerTarget() {
        return _containerTarget;
    }

    /**
     * (2026-08-30 分而治之: 眼前真实容器优先于缓存) 当 tracker 无目标容器记忆
     * (如上一个中断任务留下的熔炉), 主动扫 bot 附近真实方块, 返回第一个可到达的
     * 目标容器。仅当遍历式查找失败时调用(触发频率极低), 故直接用世界扫描可接受。
     */
    private Optional<BlockPos> findNearbyContainerBlock(AltoClef mod, Vec3d center) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return Optional.empty();
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int radius = 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    Block block = client.world.getBlockState(pos).getBlock();
                    for (Block containerBlock : _containerBlocks) {
                        if (block == containerBlock && WorldHelper.canReach(mod, pos)) {
                            return Optional.of(pos);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    // Virtual
    protected BlockPos overrideContainerPosition(AltoClef mod) {
        return null;
    }

    protected BlockPos getTargetContainerPosition() {
        return _cachedContainerPosition;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_containerBlocks);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoStuffInContainerTask task) {
            if (!Arrays.equals(task._containerBlocks, _containerBlocks)) return false;
            if (!task._containerTarget.equals(_containerTarget)) return false;
            return isSubTaskEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing stuff in " + _containerTarget + " container";
    }

    protected abstract boolean isSubTaskEqual(DoStuffInContainerTask other);

    protected abstract boolean isContainerOpen(AltoClef mod);

    protected abstract Task containerSubTask(AltoClef mod);

    protected abstract double getCostToMakeNew(AltoClef mod);
}
