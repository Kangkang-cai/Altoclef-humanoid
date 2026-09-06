package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class MoveItemToSlotTask extends Task {

    private final ItemTarget _toMove;
    private final Slot _destination;
    private final Function<AltoClef, List<Slot>> _getMovableSlots;

    // (2026-08-24 拿起放下反复修复) PICKUP 源槽后客户端光标状态有同步延迟
    // (约 4-5 tick), 旧逻辑下一 tick 若光标仍读到空就再次 PICKUP → 把光标
    // 已有材料换回源槽 = "拿起又放下", 反复几次才放进去 (用户实测 4-5 次,
    // 对应 0.2s 点击节流 + 同步延迟)。这里记录刚发起的 PICKUP 槽位, 在
    // 宽限期内不再重复点击, 等光标真正出现目标材料。
    private Slot _pendingPickup = null;
    private long _pendingPickupSinceMs = 0;
    private static final long PICKUP_SYNC_GRACE_MS = 500;

    public MoveItemToSlotTask(ItemTarget toMove, Slot destination, Function<AltoClef, List<Slot>> getMovableSlots) {
        _toMove = toMove;
        _destination = destination;
        _getMovableSlots = getMovableSlots;
    }

    /**
     * (2026-09-06 烧铁 2.4s 死循环修复) 供父级容器任务判断"子链里正在搬运的
     * 目标槽是否属于自己的合成流程": 若不属于 (如熔炉 fuel 槽), 搬运中间态
     * (PICKUP 已上光标、还没点进目标槽) 期间父级不得翻面/清光标抢断。
     */
    public Slot getDestination() {
        return _destination;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getSlotHandler().canDoSlotAction()) {
            // Rough plan
            // - If empty slot or wrong item
            //      Find best matching item (smallest count over target, or largest count if none over)
            //      Click on it (one turn)
            // - If held slot has < items than target count
            //      Left click on destination slot (one turn)
            // - If held slot has > items than target count
            //      Right click on destination slot (one turn)
            ItemStack currentHeld = StorageHelper.getItemStackInCursorSlot();
            ItemStack atTarget = StorageHelper.getItemStackInSlot(_destination);

            // Items that CAN be moved to that slot.
            Item[] validItems = _toMove.getMatches();//Arrays.stream(_toMove.getMatches()).filter(item -> mod.getItemStorage().getItemCount(item) >= _toMove.getTargetCount()).toArray(Item[]::new);

            // We need to deal with our cursor stack OR put an item there (to move).
            boolean wrongItemHeld = !Arrays.asList(validItems).contains(currentHeld.getItem());

            // (2026-08-24 拿起放下反复修复) 光标已拿到目标材料 → 放到目标槽,
            // 并清空"待确认拿起"状态。
            if (!currentHeld.isEmpty() && !wrongItemHeld) {
                _pendingPickup = null;
                int currentlyPlaced = Arrays.asList(validItems).contains(atTarget.getItem()) ? atTarget.getCount() : 0;
                if (currentHeld.getCount() + currentlyPlaced <= _toMove.getTargetCount()) {
                    // Just place all of 'em
                    return new ClickSlotTask(_destination);
                } else {
                    // Place one at a time.
                    return new ClickSlotTask(_destination, 1);
                }
            }

            if (currentHeld.isEmpty()) {
                // 需要拿起。若刚发起的 PICKUP 还在同步宽限期内 (光标应很快
                // 出现材料), 直接推进到"放目标槽" — 既不重复点击源槽 (否则
                // 拿起又放下反复), 也不返回 null (否则父任务 containerSubTask
                // 的光标兜底抢跑, 把光标上的材料 Dumping 放回背包)。
                if (_pendingPickup != null
                        && System.currentTimeMillis() - _pendingPickupSinceMs < PICKUP_SYNC_GRACE_MS) {
                    return new ClickSlotTask(_destination);
                }
                Optional<Slot> toPlace = getBestSlotToPickUp(mod, validItems);
                if (toPlace.isEmpty()) {
                    Debug.logError("Called MoveItemToSlotTask when item/not enough item is available! valid items: " + StlHelper.toString(validItems, Item::getTranslationKey));
                    return null;
                }
                _pendingPickup = toPlace.get();
                _pendingPickupSinceMs = System.currentTimeMillis();
                return new ClickSlotTask(toPlace.get());
            } else {
                // 拿着错物品 → 先放回背包 (或直接换)。
                Optional<Slot> toPlace = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(currentHeld, true);
                if (toPlace.isEmpty()) {
                    // If all else fails, just swap it.
                    toPlace = getBestSlotToPickUp(mod, validItems);
                }
                if (toPlace.isEmpty()) {
                    Debug.logError("Called MoveItemToSlotTask when item/not enough item is available! valid items: " + StlHelper.toString(validItems, Item::getTranslationKey));
                    return null;
                }
                return new ClickSlotTask(toPlace.get());
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        ItemStack atDestination = StorageHelper.getItemStackInSlot(_destination);
        return (_toMove.matches(atDestination.getItem()) && atDestination.getCount() >= _toMove.getTargetCount());
    }

    @Override
    protected boolean isEqual(Task obj) {
        if (obj instanceof MoveItemToSlotTask task) {
            return task._toMove.equals(_toMove) && task._destination.equals(_destination);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Moving " + _toMove + " to " + _destination;
    }

    private Optional<Slot> getBestSlotToPickUp(AltoClef mod, Item[] validItems) {
        Slot bestMatch = null;
        for (Slot slot : _getMovableSlots.apply(mod)) {
            if (Slot.isCursor(slot))
                continue;
            if (!_toMove.matches(StorageHelper.getItemStackInSlot(slot).getItem()))
                continue;
            if (bestMatch == null) {
                bestMatch = slot;
                continue;
            }
            int countBest  = StorageHelper.getItemStackInSlot(bestMatch).getCount();
            int countCheck = StorageHelper.getItemStackInSlot(slot).getCount();
            if (   (countBest < _toMove.getTargetCount() && countCheck > countBest)
                    || (countBest >= _toMove.getTargetCount() && countCheck >= _toMove.getTargetCount() && countCheck > countBest) ) {
                // If we don't have enough, go for largest
                // If we have too much, go for smallest over the limit.
                bestMatch = slot;
            }
        }
        return Optional.ofNullable(bestMatch);
    }
}
