package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

public class ReceiveCraftingOutputSlotTask extends Task implements ITaskUsesCraftingGrid {

    private final int _toTake;
    private final Slot _slot;

    // (2026-08-27 输出拾取防抖) 点过输出格后, 若输出格状态没变(数量相同)且光标
    // 仍空, 说明客户端同步还没到位 — 别每 tick 再点(旧代码反复
    // "Receiving output→Picking up output→Clicking 输出格" 永不收, 用户实测做
    // 石镐时卡死横跳)。宽限期内等同步。
    private long _lastOutputClickMs = 0;
    private int _lastClickedOutputCount = -1;
    private static final long OUTPUT_CLICK_GRACE_MS = 500;

    public ReceiveCraftingOutputSlotTask(Slot slot, int toTake) {
        _slot = slot;
        _toTake = toTake;
    }
    public ReceiveCraftingOutputSlotTask(Slot slot, boolean all) {
        this(slot, all? Integer.MAX_VALUE : 1);
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack inOutput = StorageHelper.getItemStackInSlot(_slot);
        // (2026-08-31 空转修复, 用户"做镐子摆好了却总失败") 产物已被 QUICK_MOVE 收走或
        // 配方暂未产出时, 输出槽为空; 旧防抖条件 `inOutput.getCount()>0` 在空输出槽下
        // 恒 false → 每 tick 都对空输出槽点 QUICK_MOVE(空操作), 无限空转 "Quick moving
        // output"(日志 t=2956-3023 每~100ms 一次)。输出槽空时不该再点: 立即交还上层,
        // 让上层按背包里是否【真实】已拿到镐来判断完成、或重新摆配方。
        if (inOutput.isEmpty()) {
            setDebugState("Output slot empty");
            return null;
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        boolean cursorSlotFree = cursor.isEmpty();
        if(!cursorSlotFree && !ItemHelper.canStackTogether(inOutput, cursor)) {
            return new EnsureFreeCursorSlotTask();
        }

        // [2026-08-22 铁剑卡死修复] 光标已拿着可堆叠的输出物品时, 再 PICKUP
        // 点输出格在 MC 里是【无操作】(输出格不接收光标物品)——旧代码走到
        // 这里每次都重新点输出格, 输出产物永远卡在光标上 (tasks.log 11:39:17
        // -11:40:04: iron_sword 每 2 秒点一次输出格循环 23 次无进展, 用户
        // 看到"卡在合成界面, 手上有东西")。必须先把光标物品放进背包, 光标
        // 清空后才能继续收输出。
        if (!cursorSlotFree) {
            // 光标拿到产物了 → 重置防抖, 走放置/腾位。
            _lastOutputClickMs = 0;
            _lastClickedOutputCount = -1;
            Optional<Slot> fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (fit.isPresent()) {
                setDebugState("Placing held output into inventory");
                return new ClickSlotTask(fit.get());
            }
            // 背包放不下光标物品: 丢垃圾腾位 (EnsureFreeInventorySlotTask 内部
            // 会清理可丢弃槽位; 全保护时返回自身不动, 上层重置计时器兜底)。
            setDebugState("No room for held output, freeing inventory");
            return new EnsureFreeInventorySlotTask();
        }

        int craftCount = inOutput.getCount() * getCraftMultipleCount(mod);
        int weWantToAddToInventory = _toTake - mod.getItemStorage().getItemCountInventoryOnly(inOutput.getItem());
        boolean takeAll = weWantToAddToInventory >= craftCount;
        // (2026-08-27 输出拾取防抖) 刚点过输出格且输出格数量没变(产物还在原位、
        // 光标仍空) → 客户端同步未到位, 这 500ms 内不要再点输出格, 否则每 tick
        // 重复 PICKUP, 产物永远收不进 (用户实测: 做石镐反复 "Picking up output→
        // Clicking 输出格" 死循环)。
        long now = System.currentTimeMillis();
        if (inOutput.getCount() > 0
                && now - _lastOutputClickMs < OUTPUT_CLICK_GRACE_MS
                && inOutput.getCount() == _lastClickedOutputCount) {
            return null;
        }
        if (takeAll) {
            // [2026-08-22] 背包放不下输出时不能落到 PICKUP (拿起后没有后续
            // 放置步骤, 见上), 先腾背包再 QUICK_MOVE。
            if (!mod.getItemStorage().getSlotThatCanFitInPlayerInventory(inOutput, true).isPresent()) {
                setDebugState("Making room for output");
                return new EnsureFreeInventorySlotTask();
            }
            _lastOutputClickMs = now;
            _lastClickedOutputCount = inOutput.getCount();
            setDebugState("Quick moving output");
            return new ClickSlotTask(_slot, SlotActionType.QUICK_MOVE);
        }
        _lastOutputClickMs = now;
        _lastClickedOutputCount = inOutput.getCount();
        setDebugState("Picking up output");
        return new ClickSlotTask(_slot);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ReceiveCraftingOutputSlotTask task) {
            return task._slot.equals(_slot) && task._toTake == _toTake;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Receiving output";
    }

    // How many multiples of the current crafting recipe can we craft?
    private static int getCraftMultipleCount(AltoClef mod) {
        int minNonZero = Integer.MAX_VALUE;
        boolean found = false;
        for (Slot check : (StorageHelper.isBigCraftingOpen()? CraftingTableSlot.INPUT_SLOTS :PlayerSlot.CRAFT_INPUT_SLOTS)) {
            ItemStack stack = StorageHelper.getItemStackInSlot(check);
            if (!stack.isEmpty()) {
                minNonZero = Math.min(stack.getCount(), minNonZero);
                found = true;
            }
        }
        if (!found)
            return 0;
        return minNonZero;
    }
}
