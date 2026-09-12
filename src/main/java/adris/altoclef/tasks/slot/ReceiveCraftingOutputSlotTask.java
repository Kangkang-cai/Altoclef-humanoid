package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
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
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();

        boolean cursorSlotFree = cursor.isEmpty();

        if (inOutput.isEmpty()) {
            // (2026-08-31 空转修复...) 输出槽空时不该再点 QUICK_MOVE(空操作), 交还上层。
            setDebugState("Output slot empty");
            return null;
        }
        if (!cursorSlotFree && !ItemHelper.canStackTogether(inOutput, cursor)) {
            return new EnsureFreeCursorSlotTask();
        }

        // [2026-08-22 铁剑卡死修复] 光标已拿着可堆叠的输出物品时...
        if (!cursorSlotFree) {
            _lastOutputClickMs = 0;
            _lastClickedOutputCount = -1;
            Optional<Slot> fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (fit.isPresent()) {
                setDebugState("Placing held output into inventory");
                return new ClickSlotTask(fit.get());
            }
            // 背包放不下光标物品: 丢弃腾位兜底 (EnsureFreeInventorySlotTask 内部
            // 会清理可丢弃槽位; 全保护时返回自身不动, 上层重置计时器兜底)。
            setDebugState("No room for held output, freeing inventory");
            return new EnsureFreeInventorySlotTask();
        }

        int craftCount = inOutput.getCount() * getCraftMultipleCount(mod);
        int weWantToAddToInventory = _toTake - mod.getItemStorage().getItemCountInventoryOnly(inOutput.getItem());
        boolean takeAll = weWantToAddToInventory >= craftCount;
        // (2026-08-27 输出拾取防抖) ...
        long now = System.currentTimeMillis();
        if (takeAll) {
            // [2026-08-22] 背包放不下输出时不能落到 PICKUP (拿起后没有后续
            // 放置步骤, 见上)。
            if (!mod.getItemStorage().getSlotThatCanFitInPlayerInventory(inOutput, true).isPresent()) {
            // (2026-09-03 回退) 曾用"交换进合成格"腾位(借垃圾槽→QUICK_MOVE产物
            // →垃圾暂存合成格)。实测 3x3 工作台合成钻石头盔时该状态机制造
            // 借位↔放回死循环(12s 内 borrow/move/park 各 ~70 次 ping-pong,
            // 且被 Throwing Cursor 打断真丢物品)。根因: 交换借出的位马上又被
            // 产物占据, 净空格没增加, getSlotThatCanFit 恒 false → 反复重启交换。
            // 回退为直接走 EnsureFreeInventorySlotTask 腾位。
            // (2026-09-12 收产物互锁实修) fit 判定与 MC 权威背包不一致时以现实为准:
            // craftgrid_diag 16:11:49 实锤 — 2x2 界面下产物 crafting_table x1 已在
            // 输出槽、合成格 4 木板就位, MC 权威背包尚有 hotbar 空位(window 36),
            // 但 getSlotThatCanFit 恒 false → "Making room for output"→EnsureFree
            // no-op 分支互锁, 30s+ 空转直到被上层抢占(16:09:49/16:10:19 两次)。
            // 光标为空且背包真有空位时, 直接 QUICK_MOVE shift 进空位, 一步到位。
            boolean realEmptyMain = mod.getPlayer() != null
                    && mod.getPlayer().getInventory() != null
                    && mod.getPlayer().getInventory().getEmptySlot() >= 0;
            if (realEmptyMain && cursorSlotFree) {
                Debug.logToFile("altoclef_diag.log",
                        "[CraftFitMismatch] fit=false but realEmptyMain=true -> QUICK_MOVE output anyway"
                                + " | output=" + inOutput.getItem().getTranslationKey() + "x" + inOutput.getCount()
                                + " handler=" + (mod.getPlayer().currentScreenHandler != null
                                        ? mod.getPlayer().currentScreenHandler.getClass().getSimpleName() : "null"));
                setDebugState("Quick moving output (fit mismatch fallback)");
                return new ClickSlotTask(_slot, SlotActionType.QUICK_MOVE);
            }
            setDebugState("Making room for output");
            return new EnsureFreeInventorySlotTask();
            }
            if (now - _lastOutputClickMs < OUTPUT_CLICK_GRACE_MS
                    && inOutput.getCount() == _lastClickedOutputCount) {
                return null;
            }
            _lastOutputClickMs = now;
            _lastClickedOutputCount = inOutput.getCount();
            setDebugState("Quick moving output");
            return new ClickSlotTask(_slot, SlotActionType.QUICK_MOVE);
        }
        if (now - _lastOutputClickMs < OUTPUT_CLICK_GRACE_MS
                && inOutput.getCount() == _lastClickedOutputCount) {
            return null;
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
