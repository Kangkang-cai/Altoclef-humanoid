package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasks.slot.ThrowCursorTask;
import adris.altoclef.tasksystem.CraftingGridArbiter;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public class CraftGenericWithRecipeBooksTask extends Task implements ITaskUsesCraftingGrid {

    private final RecipeTarget _target;

    // (2026-09-07 [CraftClick] 收成品哑火检测) "台前合成熔炉卡死"案: 输出格点击
    // 77 次零产出。本任务实例在 containerSubTask 的 "Crafting"↔"Getting output"
    // 抖动中因 isEqual 去重而存活, 适合做跨抖动的哑火计时器: 进入 Getting output
    // 分支后, 若真实背包中成品计数 4s 纹丝不动, 打一条 INEFFECTIVE 行(5s 节流),
    // 与 SlotHandler 的逐点击 [CraftClick] 行联合定案。
    private long _gettingOutputSinceMs = 0;
    private int _invCountAtGettingOutputStart = -1;
    private long _lastIneffectiveLogMs = 0;

    // (2026-09-09 配方填充死锁修复 #3 停滞看门狗) 现场 17:29:54-17:30:20:
    // 2x2 输入格滞留原木x30(上一个木板 craftAll 批量合成残留) + 背包 46/46
    // 全满, 配方书填充 crafting_table 前服务端要把退料放回背包 → 静默失败 →
    // 本任务 "Waiting for recipe book click..." 返回 null 无超时死等 26s。
    // 进度签名 = (输出格, 输入格, 光标, 背包空位数) 任一变化即重置; 15s 零
    // 变化 → 打 [CRAFT-FILL-GIVEUP] 快照并放弃本轮填充, 30s 内同配方不再
    // 重发。冷却必须 static: 实例层标志会被上层每 tick 重建绕过(项目规矩:
    // 触发条件层 static 冷却)。
    private static final long FILL_STALL_GIVEUP_MS = 15000;
    private static final long FILL_GIVEUP_COOLDOWN_MS = 30000;
    private static Item _giveUpOutputItem = null;
    private static long _giveUpUntilMs = 0;
    private String _progressKey = "";
    private long _progressSinceMs = 0;

    public CraftGenericWithRecipeBooksTask(RecipeTarget target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-09 [GridWar] 根修) 配方书填充会写 2x2 格(配方书点击/材料
        // 归位/收成品), 与清格链互斥, 必须持 CRAFT 租约。父任务每 tick 重跑
        // 本 onTick 持续续租; 清格一拍完成即释放, 等待有界。
        if (!CraftingGridArbiter.begin(CraftingGridArbiter.INTENT_CRAFT)) {
            setDebugState("2x2 busy: clearing holds the grid, waiting");
            return null;
        }

        // (2026-09-09 放弃冷却) 刚因停滞放弃过的同配方, 冷却期内就地等待,
        // 不重发填充。
        long watchdogNowMs = System.currentTimeMillis();
        if (_giveUpOutputItem == _target.getOutputItem() && watchdogNowMs < _giveUpUntilMs) {
            setDebugState("Recipe fill gave up recently, cooling down");
            return null;
        }

        boolean bigCrafting = StorageHelper.isBigCraftingOpen();

        if (!bigCrafting && !StorageHelper.isPlayerInventoryOpen()) {
            // Make sure we're not in another screen before we craft,
            // otherwise crafting won't work
            StorageHelper.closeScreen();
            // Just to be safe
        }

        Slot outputSlot = bigCrafting ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;
        ItemStack output = StorageHelper.getItemStackInSlot(outputSlot);

        // (2026-09-09 停滞看门狗) 签名任一变化即算有进展 (收成品/腾位/清格
        // 都会改变签名, 不会误伤)。
        String progressKey = buildProgressSignature(mod, outputSlot);
        if (!progressKey.equals(_progressKey)) {
            _progressKey = progressKey;
            _progressSinceMs = watchdogNowMs;
        } else if (watchdogNowMs - _progressSinceMs > FILL_STALL_GIVEUP_MS) {
            Debug.logToFile("altoclef_craftclick.log",
                    "[CRAFT-FILL-GIVEUP] no progress for " + (FILL_STALL_GIVEUP_MS / 1000.0)
                            + "s, giving up this fill. state=" + progressKey);
            _giveUpOutputItem = _target.getOutputItem();
            _giveUpUntilMs = watchdogNowMs + FILL_GIVEUP_COOLDOWN_MS;
            _progressKey = "";
            setDebugState("Recipe fill stalled, giving up (cooldown "
                    + (FILL_GIVEUP_COOLDOWN_MS / 1000) + "s)");
            return null;
        }

        // (2026-08-31 反复"Getting output"修复) 输出槽【非空且匹配】才收成品 —— 与
        // containerSubTask/ReceiveCraftingOutput 一致, 避免空输出槽被短暂判成"有成品"
        // → 反复派发 ReceiveCraftingOutput(内部读到空 return) 造成 4-5 次反复。
        if (!output.isEmpty() && _target.getOutputItem() == output.getItem() && mod.getItemStorage().getItemCount(_target.getOutputItem()) < _target.getTargetCount()) {
            setDebugState("Getting output");
            // [CraftClick] 哑火计时: 真实背包成品计数 4s 不动即告警。
            long nowMs = System.currentTimeMillis();
            int invCountNow = mod.getItemStorage().getItemCountInventoryOnly(_target.getOutputItem());
            if (_gettingOutputSinceMs == 0 || invCountNow != _invCountAtGettingOutputStart) {
                _gettingOutputSinceMs = nowMs;
                _invCountAtGettingOutputStart = invCountNow;
            } else if (nowMs - _gettingOutputSinceMs > 4000 && nowMs - _lastIneffectiveLogMs > 5000) {
                _lastIneffectiveLogMs = nowMs;
                ItemStack cursorNow = StorageHelper.getItemStackInCursorSlot();
                Debug.logToFile("altoclef_craftclick.log",
                        String.format("[CraftClick] INEFFECTIVE: getting output %s for %.1fs, invCount stuck at %d, outputSlot=%sx%d, cursor=%s, handler=%s",
                                _target.getOutputItem(), (nowMs - _gettingOutputSinceMs) / 1000.0,
                                invCountNow, output.getItem(), output.getCount(),
                                cursorNow.isEmpty() ? "EMPTY" : cursorNow.getItem() + "x" + cursorNow.getCount(),
                                net.minecraft.client.MinecraftClient.getInstance().player.currentScreenHandler.getClass().getSimpleName()));
            }
            return new ReceiveCraftingOutputSlotTask(outputSlot, _target.getTargetCount());
        }
        // 输出格不再就绪 → 重置哑火计时
        _gettingOutputSinceMs = 0;

        // (2026-09-09 配方填充死锁修复 #1 残留成品接收) 输出格有【非本配方目标】
        // 的残留成品(上一个 craftAll 批量合成收第一批就达标滞留的, 如
        // oak_planksx4) — 旧逻辑直接掉到下方发配方填充, 而输出格被占着, 本配方
        // 产出永不出现 → "Waiting for recipe book click..." 死等 (17:29:54
        // 实锤)。先把残留收进背包; 放不下时 ReceiveCraftingOutputSlotTask 内部
        // 会走 EnsureFreeInventorySlotTask 腾位级联(全程过保留闸门)。
        if (!output.isEmpty() && _target.getOutputItem() != output.getItem()) {
            setDebugState("Receiving leftover output (not ours): " + output.getItem().getTranslationKey());
            return new ReceiveCraftingOutputSlotTask(outputSlot, true);
        }

        // If a material is found in cursor, move it to the inventory.
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();


        // Crafting book REQUIRES that all materials be in the inventory. Materials CANNOT be in the cursor
        if (Arrays.stream(_target.getRecipe().getSlots()).anyMatch(target -> target.matches(cursor.getItem()))) {
            setDebugState("CURSOR HAS MATERIAL! Moving out.");
            // (2026-09-01 同 LootContainerTask 根因) 删 `.or(getGarbageSlot)`: 背包满时
            // 点垃圾槽只交换不扔 → 走下方"放进合成格"真正腾光标。
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (toMoveTo.isPresent()) {
                return new ClickSlotTask(toMoveTo.get());
            } else {
                // If our inventory is full, but the material will be used for crafting, put it in the crafting grid.
                // TODO: Add as an alternative method to EnsureCursorSlotTask
                for (int recSlot = 0; recSlot < _target.getRecipe().getSlotCount(); ++recSlot) {
                    if (_target.getRecipe().getSlot(recSlot).matches(cursor.getItem())) {
                        Slot toMoveToPotential = bigCrafting? CraftingTableSlot.getInputSlot(recSlot, _target.getRecipe().isBig()) : PlayerSlot.getCraftInputSlot(recSlot);
                        ItemStack inRecipe = StorageHelper.getItemStackInSlot(toMoveToPotential);
                        if (ItemHelper.canStackTogether(cursor, inRecipe)) {
                            return new ClickSlotTask(toMoveToPotential);
                        }
                    }
                }
                // Crafting grid is full, and we still have the item, throw it away.
                return new ThrowCursorTask();
            }
        }

        // (2026-09-09 配方填充死锁修复 #2 填充前空间自查) 配方书填充时服务端要
        // 先把 2x2 输入格现存材料退回真实背包, 再从背包取料补齐配方; 背包放不下
        // 退料时填充静默失败 → 本配方产出永不出现 → 死等 (17:29:54 实锤: 原木
        // x30 滞留输入格 + 背包全满)。发填充前自查: 输入格有料且整堆放不回背包
        // (可并入现有堆叠算放得下) → 先走腾位级联。
        if (!bigCrafting) {
            for (Slot inSlot : PlayerSlot.CRAFT_INPUT_SLOTS) {
                ItemStack inStack = StorageHelper.getItemStackInSlot(inSlot);
                if (!inStack.isEmpty()
                        && !mod.getItemStorage().getSlotThatCanFitInPlayerInventory(inStack, false).isPresent()) {
                    setDebugState("Inventory can't take back grid materials, freeing space");
                    return new EnsureFreeInventorySlotTask();
                }
            }
        }

        // Request to fill in a recipe. Just piggy back off of the slot delay system.
        if (mod.getSlotHandler().canDoSlotAction()) {
            mod.getSlotHandler().registerSlotAction();
            StorageHelper.instantFillRecipeViaBook(mod, _target.getRecipe(), _target.getOutputItem(), true);
        }


        setDebugState("Waiting for recipe book click...");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    /** 停滞看门狗的进度签名: 输出格 + 输入格 + 光标 + 背包空位数。 */
    private String buildProgressSignature(AltoClef mod, Slot outputSlot) {
        StringBuilder sb = new StringBuilder();
        ItemStack out = StorageHelper.getItemStackInSlot(outputSlot);
        sb.append(out.isEmpty() ? "-" : out.getItem() + "x" + out.getCount());
        for (Slot s : (StorageHelper.isBigCraftingOpen()
                ? CraftingTableSlot.INPUT_SLOTS : PlayerSlot.CRAFT_INPUT_SLOTS)) {
            ItemStack st = StorageHelper.getItemStackInSlot(s);
            sb.append('|').append(st.isEmpty() ? "-" : st.getItem() + "x" + st.getCount());
        }
        ItemStack cur = StorageHelper.getItemStackInCursorSlot();
        sb.append("|cur=").append(cur.isEmpty() ? "-" : cur.getItem() + "x" + cur.getCount());
        if (mod.getPlayer() != null && mod.getPlayer().getInventory() != null) {
            int empty = 0;
            for (ItemStack st : mod.getPlayer().getInventory().main) {
                if (st.isEmpty()) empty++;
            }
            sb.append("|empty=").append(empty);
        }
        return sb.toString();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftGenericWithRecipeBooksTask task) {
            return task._target.equals(_target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Crafting (w/ RECIPE): " + _target;
    }
}
