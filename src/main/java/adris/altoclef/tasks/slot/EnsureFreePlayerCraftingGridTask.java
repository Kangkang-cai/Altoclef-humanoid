package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.CraftingGridArbiter;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

public class EnsureFreePlayerCraftingGridTask extends Task {
    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-09-09 [GridWar] 根修) 清格是所有"要 2x2 格空"流量的单一收口,
        // 必须先拿到租约才动手: 配方链(CRAFT)持有时就地等待, 不再互抢半截
        // 动作(15:32 实锤: 清格刚拿出 log, 配方链又放回, 50 秒净进度为零)。
        // 等待有界: 持有方要么完成整拍停止续租, 要么被 STOP 后租约 3s 过期。
        if (!CraftingGridArbiter.begin(CraftingGridArbiter.INTENT_CLEAR)) {
            setDebugState("2x2 busy: crafting holds the grid, waiting");
            return null;
        }
        setDebugState("Clearing the 2x2 crafting grid");
        for(Slot slot : PlayerSlot.CRAFT_INPUT_SLOTS) {
            ItemStack items = StorageHelper.getItemStackInSlot(slot);
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if(!cursor.isEmpty()){
                return new EnsureFreeCursorSlotTask();
            }
            if(!items.isEmpty()){
                return new ClickSlotTask(slot);
            }
        }
        // (2026-09-09 收成品残留) 输入格已空后, 输出格若还有残留成品(上一个
        // craftAll 批量合成收第一批即达标滞留的, 17:29:53 实锤 oak_planksx4)
        // 一并收走: 输入格已空, 取输出不会触发再合成。光标被占先清光标; 背包
        // 放不下先走腾位级联(全程过保留闸门)。
        ItemStack outStack = StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT);
        if (!outStack.isEmpty()) {
            if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                return new EnsureFreeCursorSlotTask();
            }
            if (!mod.getItemStorage().getSlotThatCanFitInPlayerInventory(outStack, true).isPresent()) {
                setDebugState("Freeing space for leftover crafting output");
                return new EnsureFreeInventorySlotTask();
            }
            return new ClickSlotTask(PlayerSlot.CRAFT_OUTPUT_SLOT, SlotActionType.QUICK_MOVE);
        }
        // 格子已空: 立即释放租约, 配方链马上可以接手(不等 3s 过期)。
        CraftingGridArbiter.release(CraftingGridArbiter.INTENT_CLEAR);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // (2026-09-28 租约泄漏根修) 被抢占 STOP 后本任务不再续租, 但租约不会自己
        // 消失 —— 必须等满 LEASE_MS(3s) 才过期, 对方在这 3 秒里纯空等。持有方
        // 已停手, 留着租约只伤人, 故 STOP 即释放。release() 只对持有者本人生效,
        // 无副作用。现场 20260928-084126: took=16.4s 内夹 2 次 3s 空等。
        CraftingGridArbiter.release(CraftingGridArbiter.INTENT_CLEAR);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EnsureFreePlayerCraftingGridTask;
    }

    @Override
    protected String toDebugString() {
        return "Breaking the crafting grid";
    }
}
