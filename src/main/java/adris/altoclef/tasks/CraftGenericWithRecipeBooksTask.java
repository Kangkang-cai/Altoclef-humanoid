package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask;
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

    // (2026-09-10 残留兜底) "非本配方残留成品已单拿一份"标志, 防止收成品↔
    // 新产出循环(输入格还有料时输出格会立刻再产出一份)。全清后复位。
    private boolean _leftoverOutputTaken = false;

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

        // (2026-09-10 铁剑超量合成修复 #2 残留兜底改退料) 输出格有【非本配方目标】
        // 的残留成品 — 91b0408d 旧逻辑 ReceiveCraftingOutputSlotTask(outputSlot, true)
        // 走 all=true → takeAll 恒 true → QUICK_MOVE 输出格 = vanilla 按格内余料
        // 连续合成到清空, 格内超量材料(配方书 craftAll 填的整堆铁/棍)一口气全变成
        // 残留品(铁剑 5 把连出, 15:56 场次实锤)。改为四步状态机:
        //  ① 光标有料先放回背包(③退料 PICKUP 的后续);
        //  ② 先赚一份: 单拿(PICKUP)残留成品一次, flag 防循环;
        //  ③ 逐格清输入格退料回背包 — 退第一格即破坏配方, 输出格新产出随之消失
        //     (净损 1 份材料, 相比旧逻辑"全部超量制作"可接受);
        //  ④ 输入格已空后输出格若仍有成品, 单拿收尾(输入格空, 取输出不会触发
        //     再合成 — 同 EnsureFreePlayerCraftingGridTask 09-09 原则)。
        //  全清后 flag 复位, 掉到下方正常发填充。放不下背包一律先走腾位级联。
        if (!output.isEmpty() && _target.getOutputItem() != output.getItem()) {
            setDebugState("Draining leftover output + grid (not ours): " + output.getItem().getTranslationKey());
            ItemStack cursorNow = StorageHelper.getItemStackInCursorSlot();
            if (!cursorNow.isEmpty()) {
                Optional<Slot> fitCursor = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorNow, false);
                if (fitCursor.isPresent()) {
                    return new ClickSlotTask(fitCursor.get());
                }
                return new EnsureFreeCursorSlotTask();
            }
            if (!_leftoverOutputTaken) {
                if (!mod.getItemStorage().getSlotThatCanFitInPlayerInventory(output, true).isPresent()) {
                    setDebugState("Freeing space for leftover output");
                    return new EnsureFreeInventorySlotTask();
                }
                _leftoverOutputTaken = true;
                return new ClickSlotTask(outputSlot);
            }
            Slot[] drainSlots = bigCrafting ? CraftingTableSlot.INPUT_SLOTS : PlayerSlot.CRAFT_INPUT_SLOTS;
            for (Slot drainSlot : drainSlots) {
                if (!StorageHelper.getItemStackInSlot(drainSlot).isEmpty()) {
                    return new ClickSlotTask(drainSlot);
                }
            }
            ItemStack outNow = StorageHelper.getItemStackInSlot(outputSlot);
            if (!outNow.isEmpty()) {
                if (!mod.getItemStorage().getSlotThatCanFitInPlayerInventory(outNow, true).isPresent()) {
                    setDebugState("Freeing space for drained output");
                    return new EnsureFreeInventorySlotTask();
                }
                return new ClickSlotTask(outputSlot);
            }
            _leftoverOutputTaken = false;
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
        // (2026-09-10 铁剑超量合成修复) craftAll=true = shift 点配方书 = 按背包
        // 材料尽量填满格子(10 铁+整堆棍全进 3x3)。目标只要 1 把剑时, 收 1 把达标
        // 后格内余料经"残留成品接收"分支被 QUICK_MOVE 连续合成榨干 — 全部铁锭
        // 变铁剑(用户实测 15:56 场次)。以前不暴露是因为旧逻辑残留时死等 12s 关台,
        // 关台副作用把超量材料退回背包; 91b0408d 修死循环后副作用消失。
        // 改 craftAll=false: fill 只填精确 1 份配方, 格子永不超量, 下游收货/清格
        // 全部自然安全。速度损失 = 每份多一轮 fill 点击, 目标数量普遍 ≤2, 可忽略。
        if (mod.getSlotHandler().canDoSlotAction()) {
            mod.getSlotHandler().registerSlotAction();
            StorageHelper.instantFillRecipeViaBook(mod, _target.getRecipe(), _target.getOutputItem(), false);
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
