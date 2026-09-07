package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * (2026-09-07 用户拍板: "背包满想拿箱子里的东西 → 把垃圾存进这个箱子")
 * 站在容器前、背包全满时的首选腾位手段: 把背包里的垃圾/大堆廉价建材【放进
 * 当前打开的容器】腾出一格, 而不是扔在地上浪费物资 (人类做法: 都开箱了就顺手
 * 塞进去)。原链路只有"合并同类堆 + 扔地上"两条, 站在箱子前却把东西丢一地。
 * <p>
 * 状态机与 ConsolidateInventoryTask 同构: 光标空 → 从背包拿起一件可弃物;
 * 光标非空 → 点容器里能容纳它的槽放下。放不下(容器满)/没东西可存 → 结束,
 * 由上层 EnsureFreeInventorySlotTask 继续走"扔垃圾/大堆建材/随机保底"级联。
 * <p>
 * 只认箱子类容器 (CHEST/SHULKER): 熔炉/酿造台等的"容器槽"是输入槽, 塞垃圾
 * 进去会污染烧制配方, 必须排除。
 */
public class StoreGarbageInContainerTask extends Task {

    // 容器放不下 or 没有可存的垃圾 → 标记结束, 不再空转。
    private boolean _blocked = false;

    public static boolean isStorageContainerOpen() {
        return ContainerType.screenHandlerMatches(ContainerType.CHEST)
                || ContainerType.screenHandlerMatches(ContainerType.SHULKER);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _blocked = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_blocked) {
            return null;
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            // 阶段 2: 手上有东西 → 放进容器。
            List<Slot> fits = mod.getItemStorage().getSlotsThatCanFitInOpenContainer(cursor, true);
            if (!fits.isEmpty()) {
                setDebugState("Storing " + cursor.getItem().getTranslationKey() + " in container");
                return new ClickSlotTask(fits.get(0));
            }
            // 容器塞满了 → 结束, 交给上层强丢链。
            _blocked = true;
            Debug.logMessage("(StoreGarbageInContainerTask) container is full, cannot store "
                    + cursor.getItem().getTranslationKey());
            return null;
        }
        // 阶段 1: 从背包拿起一件可弃物 (垃圾优先, 其次大堆廉价建材)。
        Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
        Slot pick = garbage.orElseGet(() -> StorageHelper.getBulkDisposalSlot(mod));
        if (pick == null) {
            // 背包里没有能存的垃圾 → 结束, 交给上层强丢链。
            _blocked = true;
            return null;
        }
        ItemStack toStore = StorageHelper.getItemStackInSlot(pick);
        // 存之前先确认容器装得下, 否则会把物品拿在手上又放不回去 (光标死锁)。
        if (mod.getItemStorage().getSlotsThatCanFitInOpenContainer(toStore, true).isEmpty()) {
            _blocked = true;
            return null;
        }
        setDebugState("Storing junk into container: " + toStore.getItem().getTranslationKey());
        return new ClickSlotTask(pick);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 手上还拿着东西时必须继续放, 否则光标会被遗留在手上卡死后续操作。
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return false;
        }
        return _blocked;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _blocked = false;
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof StoreGarbageInContainerTask;
    }

    @Override
    protected String toDebugString() {
        return "Storing junk in container";
    }
}
