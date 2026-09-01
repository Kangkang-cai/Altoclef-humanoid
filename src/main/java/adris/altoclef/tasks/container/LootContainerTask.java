package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;


public class LootContainerTask extends Task {
    private boolean _weDoneHere = false;
    private final Predicate<ItemStack> _check;
    public final BlockPos chest;
    public final List<Item> targets = new ArrayList<>();

    // (2026-08-27 拿了又放反复修复) 点箱子槽拿起物品后, 客户端光标有约 4-5 tick
    // 的同步延迟。旧逻辑每 tick 重扫: 光标仍读为空 + 箱子槽还没被清 → 又去点
    // 同一个箱子槽 — 而此时光标其实已拿着该物品 → 等于把物品放回箱子, 再拿起,
    // 反复几次光标才真正同步、才点到背包 (用户实测"拿了又放四次才放进背包")。
    // 这里记录刚发起的 PICKUP 槽位: 宽限期内光标未出现目标物品就不再重复点
    // 该槽(会拿起又放下), 等光标到位。
    private Slot _pendingPickup = null;
    private long _pendingPickupSinceMs = 0;
    private static final long PICKUP_SYNC_GRACE_MS = 500;

    public LootContainerTask(BlockPos chestPos, List<Item> items) {
        chest = chestPos;
        targets.addAll(items);
        _check = x -> true;
    }

    public LootContainerTask(BlockPos chestPos, List<Item> items, Predicate<ItemStack> pred) {
        chest = chestPos;
        targets.addAll(items);
        _check = pred;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        for (Item item : targets) {
            if (!mod.getBehaviour().isProtected(item)) {
                mod.getBehaviour().addProtectedItems(item);
            }
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if(!ContainerType.screenHandlerMatches(ContainerType.CHEST)) {
            setDebugState("Interact with container");
            return new InteractWithBlockTask(chest);
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            // 光标已拿到物品 → 放回背包, 并清空"待确认拿起"(拿起已完成)。
            _pendingPickup = null;
            Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
            if (toFit.isPresent()) {
                setDebugState("Putting cursor in inventory");
                return new ClickSlotTask(toFit.get());
            } else {
                setDebugState("Ensuring space");
                return new EnsureFreeInventorySlotTask();
            }
        }
        Optional<Slot> optimal = getAMatchingSlot(mod);
        if (optimal.isEmpty()) {
            _weDoneHere = true;
            return null;
        }
        // (2026-08-27 拿了又放反复修复) 光标仍空但刚点过的拿起槽还在同步宽限期:
        // 物品已拿起(光标稍后到位)却还没被此槽清掉 → 别再重复点它, 否则会把
        // 光标物品放回箱子(拿起又放下)。等光标真正到位。
        if (_pendingPickup != null
                && System.currentTimeMillis() - _pendingPickupSinceMs < PICKUP_SYNC_GRACE_MS
                && optimal.get().equals(_pendingPickup)) {
            Debug.logToFile("altoclef_diag.log",
                    "[ContainerSync] waiting for pickup of " + targets
                            + " from " + optimal.get() + " (cursor sync pending)");
            return null;
        }
        _pendingPickup = optimal.get();
        _pendingPickupSinceMs = System.currentTimeMillis();
        setDebugState("Looting items: " + targets);
        return new ClickSlotTask(optimal.get());
    }

    @Override
    protected void onStop(AltoClef mod, Task task) {
        StorageHelper.closeScreen();
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LootContainerTask && targets == ((LootContainerTask) other).targets;
    }

    private Optional<Slot> getAMatchingSlot(AltoClef mod) {
        for (Item item : targets) {
            List<Slot> slots = mod.getItemStorage().getSlotsWithItemContainer(item);
            if (!slots.isEmpty()) for (Slot slot : slots) {
                if (_check.test(StorageHelper.getItemStackInSlot(slot))) return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _weDoneHere || (ContainerType.screenHandlerMatchesAny() &&
                getAMatchingSlot(mod).isEmpty());
    }

    @Override
    protected String toDebugString() {
        return "Looting a container";
    }
}
