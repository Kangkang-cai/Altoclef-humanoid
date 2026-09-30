package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasks.slot.ThrowCursorTask;
import adris.altoclef.tasksystem.CursorArbiter;
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

    // (2026-09-13 填充份数决策留痕) 决策(bulk/remaining/perCraft)变化时才打一条
    // [CraftFill], 供校验"无限目标是否真的走了批量、真实需求是否仍走单份"。
    private String _lastFillLogKey = "";

    public CraftGenericWithRecipeBooksTask(RecipeTarget target) {
        _target = target;
    }

    // (2026-09-10 台格属主标记) 当前占据工作台 3x3 合成格内容的配方的输出物品,
    // null = 无属主 (格空或历史遗留)。存储层 hasRecipeMaterialsOrTarget 只把台格
    // 里的物品计入"属主配方"的材料 —— 否则镐合成放进台格的 3 颗钻会被胸甲的
    // 缺料判定当成自己的第 6/7/8 颗 (背包 5 + 台格 3 = 8) → 胸甲抢切镐合成 →
    // 格被清 → 又缺料 → 循环 (19:42 场次 tasks.log t=99~523 四轮实锤)。
    // 用输出物品而非 RecipeTarget 实例做属主: 任务链每 tick 可能重建任务,
    // 同一配方的实例不同但输出物品恒定。
    private static volatile Item _gridOwnerOutput = null;

    /** 台格无属主 (视为可自由计入) 或属主即本配方时返回 true。 */
    public static boolean isCraftGridOwnedBy(RecipeTarget target) {
        Item owner = _gridOwnerOutput;
        return owner == null || (target != null && target.getOutputItem() == owner);
    }

    /** (2026-09-10) 是否有合成正占据工作台/随身合成格 (任何配方的填充/收尾在飞)。 */
    public static boolean isCraftInFlight() {
        return _gridOwnerOutput != null;
    }

    /**
     * (2026-09-17 世界边界) 换存档时清空 static 冷却与台格归属。
     * `_giveUpOutputItem` 按【物品名】记 30s 放弃 —— 上个存档放弃过的产物
     * (木板 / 木棍这类常见品) 在新存档开局会被直接跳过, 且没有任何存档判定;
     * `_gridOwnerOutput` 指向的台格在新存档并不存在。
     */
    public static void onWorldBoundary() {
        _giveUpOutputItem = null;
        _giveUpUntilMs = 0;
        _gridOwnerOutput = null;
    }

    // (2026-09-13 释放阀下移) 原先暴露在此的 isFillRecentlyStalled(Item) 已删除 ——
    // 它唯一的消费方是 CollectFoodTask 的 10s 重评估释放阀, 而释放职责已下移到合成类
    // 自身(CraftInInventoryTask / CraftInTableTask 在 collect=false 且
    // StorageHelper.hasMaterialsForOneCraft() 为 false 时自行 isFinished)。
    // 注意下面 onTick 里的 15s 停滞看门狗本体**保留**: 它的作用不是给上层发信号, 而是
    // "本轮填充推不动就放弃 + 30s 冷却", 避免合成任务无脑重发填充请求。

    @Override
    protected void onStart(AltoClef mod) {
        _gridOwnerOutput = _target.getOutputItem();
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
                // (2026-09-15) 丢弃流程持有光标时禁止放回: 腾位链刚把垃圾拿上光标、
                // ThrowCursorTask 正瞄准坑准备丢出, 这里把它"存回背包"会把丢弃流程
                // 整个拆掉 (20260915-091355 gridwar.log 实锤: 花岗岩 x14 上光标 →
                // 瞄准坑 → 每 2s 被本分支放回 17 号槽 → 重拿 → 八轮循环零丢出)。
                // 丢弃有界 (2s 瞄准 + 25s/30s 挖掘超时兜底照丢), 等它丢完光标自空。
                // (2026-09-16 CRAFT/CLEAR 互等修复, 09:06:34-36 latest.log 实锤)
                // 原来的 "waiting → return null" 在本框架里 = STOP 当前子树
                // (Task.tickInternal: onTick 返回 null → _sub.stop(mod)) —— 恰好把
                // 正在瞄准的 ThrowCursorTask 拆掉, _activeInstance 清空 → 下一 tick
                // isDiscardInProgress=false → 走下方"放回背包" → 垃圾回包 → 重捡 →
                // 重拆, 同一 slot 6 每 0.3s 拿起/放回永动, 一次都没丢出去。
                // 修法: 丢弃在途 / 腾位租约在握 (拿垃圾→瞄准→丢出的两拍中转) 时,
                // 重发同类型腾位任务 keep-alive —— isEqual 命中在途实例不拆树,
                // 丢弃在自己子树里跑完; 光标空后本分支自然继续 drain。
                if (ThrowCursorTask.isDiscardInProgress()
                        || CursorArbiter.isHeldBy(CursorArbiter.INTENT_RELOCATE)) {
                    setDebugState("Cursor under discard/freeing, keeping cascade alive");
                    return new EnsureFreeInventorySlotTask();
                }
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
                // (2026-09-17 收敛 B 类复制) 旧实现合成格满直接扔光标配方材料;
                // 统一替换为 EnsureFreeCursorSlotTask 正版状态机——先试放回背包
                // (材料不浪费, 下次填充仍可用), 放不下才进丢弃级联。行为变化:
                // 不再无脑扔, 与 DoStuffInContainer/SmeltInFurnace/CraftGeneric
                // Manually 三处口径一致 (原 TODO "Add as an alternative method
                // to EnsureCursorSlotTask" 即此收敛)。
                setDebugState("Clearing cursor (grid full, returning materials)");
                return new EnsureFreeCursorSlotTask();
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
        // (2026-09-13 填充份数按需 — 取代 958108b5 的"一律只填 1 份")
        // 958108b5 为修"铁剑超量合成"把 craftAll 硬编码为 false(只填精确 1 份),
        // 注释判断"目标数量普遍 ≤2, 速度损失可忽略"。但目标数量并不总是小:
        // CollectFoodTask 把 bread/wheat 传成 99999999(食物按 unit 折算不出个数),
        // 于是退化成"每轮合成 1 个"(20260913-104826 实锤: 面包 1 秒 1 个、小麦
        // 每次只拆 1 个干草块, 20 秒才做 8 个面包)。
        // 现按"还需份数"决定, 恢复批量能力同时不超量:
        //   remaining = 目标总数 - 已有数  (与上方收货判定同一口径 getItemCount)
        //   remaining > 每份产出 → craftAll=true  (还需不止一次合成, 批量填满)
        //   remaining ≤ 每份产出 → craftAll=false (恰好一份就够, 精确填 1 份)
        // 超量由收货端兜住: ReceiveCraftingOutputSlotTask 的 takeAll 判定是
        // "还差多少 ≥ 格内能做的总量"才 QUICK_MOVE 榨干, 否则走 PICKUP 按需单拿。
        // 因此铁剑 x1 / 木板 x12 这类真实需求仍逐份按需拿取(不超量), 只有"无限"
        // 目标(还差数远大于格容量)才会一次榨干格内材料 —— 正是批量想要的行为。
        // 旧"收够后残留被 QUICK_MOVE 连带榨干"的路径已由残留分支改退料消除。
        int have = mod.getItemStorage().getItemCount(_target.getOutputItem());
        int remaining = _target.getTargetCount() - have;
        if (remaining <= 0) {
            // 已达标: 不再发填充(旧行为达标后仍会再 fill 一次 → 白多合成一份),
            // 格内余料交给上层清格(CraftInInventoryTask 的
            // EnsureFreePlayerCraftingGridTask / 3x3 关屏退料)处理, 本任务
            // 只等父任务判定完成切走。
            setDebugState("Target count met, letting parent move on");
            return null;
        }
        int perCraft = Math.max(1, _target.getRecipe().outputCount());
        boolean bulkFill = remaining > perCraft;
        logFillDecision(remaining, perCraft, bulkFill, have);
        if (mod.getSlotHandler().canDoSlotAction()) {
            mod.getSlotHandler().registerSlotAction();
            StorageHelper.instantFillRecipeViaBook(mod, _target.getRecipe(), _target.getOutputItem(), bulkFill);
        }


        setDebugState("Waiting for recipe book click...");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 仅在仍是本配方属主时清除, 避免清掉后继同物品配方的标记。
        if (_gridOwnerOutput == _target.getOutputItem()) {
            _gridOwnerOutput = null;
        }
        // (2026-09-28 租约泄漏根修) 被抢占 STOP 后停止续租, 但租约要等满
        // LEASE_MS(3s) 才过期 ⇒ 对方空等。与上方清理同处收口。现场 20260928-084126。
        CraftingGridArbiter.release(CraftingGridArbiter.INTENT_CRAFT);
    }

    /**
     * (2026-09-13) [CraftFill] 填充份数决策留痕: 决策或数量变化时打一条, 防刷屏。
     * 校验要点: item=bread/wheat(无限目标) 应恒 bulk=true; 工具/精确数量目标
     * (如 stick x4, pickaxe x1) 应 bulk=false 走精确单份。
     */
    private void logFillDecision(int remaining, int perCraft, boolean bulkFill, int have) {
        String key = bulkFill + "|" + remaining + "|" + perCraft;
        if (key.equals(_lastFillLogKey)) return;
        _lastFillLogKey = key;
        Debug.logToFile("altoclef_craftclick.log",
                "[CraftFill] item=" + _target.getOutputItem().getTranslationKey()
                        + " bulk=" + bulkFill + " remaining=" + remaining
                        + " perCraft=" + perCraft + " have=" + have
                        + " target=" + _target.getTargetCount()
                        // (2026-09-26) 界面换代关联: 1726 会话 syncId 每 250ms 递增,
                        // 填充与重开的先后关系靠它对齐。
                        + " syncId=" + (net.minecraft.client.MinecraftClient.getInstance().player != null
                                && net.minecraft.client.MinecraftClient.getInstance().player.currentScreenHandler != null
                                ? net.minecraft.client.MinecraftClient.getInstance().player.currentScreenHandler.syncId : -1));
    }

    /** 停滞看门狗的进度签名: 输出格 + 输入格 + 光标 + 背包空位数。 */
    private String buildProgressSignature(AltoClef mod, Slot outputSlot) {        StringBuilder sb = new StringBuilder();
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

    /** (2026-09-30 收货互踩修复) 供父任务判断"子树里的合成任务是谁的"。 */
    public RecipeTarget getTarget() {
        return _target;
    }

    /** (2026-09-30 收货互踩修复) 本配方是否处于放弃冷却期 (与 onTick:123 同口径)。
     *  冷却期中本任务不会收货/填充, 父任务不应因此保持原子树, 需另派独立收货。 */
    public boolean isInGiveUpCooldown() {
        return _giveUpOutputItem == _target.getOutputItem() && System.currentTimeMillis() < _giveUpUntilMs;
    }

    @Override
    protected String toDebugString() {
        return "Crafting (w/ RECIPE): " + _target;
    }
}
