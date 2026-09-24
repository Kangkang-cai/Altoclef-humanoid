package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftGenericManuallyTask;
import adris.altoclef.tasks.CraftGenericWithRecipeBooksTask;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.resources.CollectRecipeCataloguedResourcesTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.CursorArbiter;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Crafts an item in a crafting table, obtaining and placing the table down if none was found.
 */
public class CraftInTableTask extends ResourceTask {

    private final RecipeTarget[] _targets;

    private final DoCraftInTableTask _craftTask;

    public CraftInTableTask(RecipeTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        // (2026-09-24) 构造瞬间捕获 detached 上下文: onTick 跨 tick 后上下文已消失。
        _craftTask = new DoCraftInTableTask(_targets, true, false, TaskCatalogue.isDetachedContext());
    }

    public CraftInTableTask(RecipeTarget target, boolean collect, boolean ignoreUncataloguedSlots) {
        super(new ItemTarget(target.getOutputItem(), target.getTargetCount()));
        _targets = new RecipeTarget[]{target};
        _craftTask = new DoCraftInTableTask(_targets, collect, ignoreUncataloguedSlots, TaskCatalogue.isDetachedContext());
    }

    public CraftInTableTask(RecipeTarget target) {
        this(target, true, false);
    }

    private static ItemTarget[] extractItemTargets(RecipeTarget[] recipeTargets) {
        List<ItemTarget> result = new ArrayList<>(recipeTargets.length);
        for (RecipeTarget target : recipeTargets) {
            result.add(new ItemTarget(target.getOutputItem(), target.getTargetCount()));
        }
        return result.toArray(ItemTarget[]::new);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        return _craftTask;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-13 料尽即收工) 只对 collect=false 的调用方生效("材料在调用方手上,
        // 本任务不负责补料"): 连一份都做不出时目标(可能是哨兵值 99999999, "全转换"
        // 语义)永不达成。默认 collect=true 的调用方语义不变 —— 仍走 ResourceTask 的
        // ItemTarget/POSSESS 目标达成判定。
        // 出口必须在这一层: 本类未覆写 isFinished, 继承的是 ResourceTask 的目标数量
        // 判定, 哨兵值下恒 false; 只让 DoCraftInTableTask._craftCount 置位不足以结束
        // 外层任务。_craftTask.isFinished 置位后仍会走 onResourceStop 关屏(3x3 残留
        // 由 vanilla 退料回背包)。
        if (!_craftTask.isCollecting() && !StorageHelper.hasMaterialsForOneCraft(mod, _targets)) {
            return true;
        }
        return super.isFinished(mod);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        // Close the crafting table screen
        StorageHelper.closeScreen();
        //mod.getControllerExtras().closeCurrentContainer();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CraftInTableTask task) {
            return _craftTask.isEqual(task._craftTask);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return _craftTask.toDebugString();
    }

    public RecipeTarget[] getRecipeTargets() {
        return _targets;
    }
}


class DoCraftInTableTask extends DoStuffInContainerTask {

    private final float CRAFT_RESET_TIMER_BONUS_SECONDS = 10;

    private final RecipeTarget[] _targets;

    private final boolean _collect;

    private final CollectRecipeCataloguedResourcesTask _collectTask;
    private final TimerGame _craftResetTimer = new TimerGame(CRAFT_RESET_TIMER_BONUS_SECONDS);
    private int _craftCount;
    // (2026-09-13 CursorArbiter 根修) 同 CraftInInventoryTask._receiveTask: 光标被腾位链
    // 租用时维持收输出子树不翻面, 避免"搬堆叠 ↔ 清光标"每 2s 互相拆台。
    private Task _receiveTask = null;

    public DoCraftInTableTask(RecipeTarget[] targets, boolean collect, boolean ignoreUncataloguedSlots, boolean detached) {
        super(Blocks.CRAFTING_TABLE, new ItemTarget("crafting_table"));
        _collectTask = new CollectRecipeCataloguedResourcesTask(ignoreUncataloguedSlots, detached, targets);
        _targets = targets;
        _collect = collect;
    }

    public DoCraftInTableTask(RecipeTarget[] targets) {
        this(targets, true, false, false);
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        _craftCount = 0;
        _receiveTask = null;
        StorageHelper.closeScreen();
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(getMaterialsArray());
        // Our crafting slots are here for conversion
        for (RecipeTarget target : _targets) {
            int recSlot = 0;
            for (Slot slot : CraftingTableSlot.INPUT_SLOTS) {
                ItemTarget valid = target.getRecipe().getSlot(recSlot++);
                mod.getBehaviour().markSlotAsConversionSlot(slot, stack -> {
                    // We already have the item
                    if (mod.getItemStorage().getItemCount(target.getOutputItem()) >= target.getTargetCount())
                        return false;
                    // We don't, consider ourselves crafting!
                    return valid.matches(stack.getItem());
                });
            }
        }


        // Reset our "finished" value in the collect recipe thing.
        _collectTask.reset();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        super.onStop(mod, interruptTask);
        mod.getBehaviour().pop();
        StorageHelper.closeScreen();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // TODO: This shouldn't be here.
        // This is duct tape for the following scenario:
        //
        //      The Collect Recipe Resources task does NOT actually grab all of the resources we "claim" to need.
        //      It will finish while we STILL need resources.
        //
        //
        //      When is this OK?
        //
        //      Only if we ASSUME that hasRecipeMaterials is TOO STRICT and the Collect Task is CORRECT.
        //

        // Grab from output FIRST
        if (StorageHelper.isPlayerInventoryOpen()) {
            // (2026-09-02 材料制作不被打断) 仅"输出就绪待收"时才清理光标残留。
            // 3x3 合成格操作不需要光标空, 旧逻辑每 tick 有非配方残留就派
            // EnsureFreeCursorSlotTask — 光标残留遇背包满时其内部反复点击横跳,
            // 打断材料收集(合木棍→合木板), 石镐缺木棍卡死 (tasks.log 14:25:07)。
            // 与 CraftInInventoryTask 同款修复。
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            RecipeTarget readyTarget = null;
            Item outputItem = StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT).getItem();
            for (RecipeTarget target : _targets) {
                if (target.getOutputItem() == outputItem
                        && mod.getItemStorage().getItemCount(target.getOutputItem()) < target.getTargetCount()) {
                    readyTarget = target;
                    break;
                }
            }
            if (readyTarget != null) {
                // (2026-09-13 CursorArbiter 根修) 光标被腾位链租用 = 下级正在用光标做
                // "拿起 A → 并进 B"的两拍中转, 不是外来滞留残留。此时翻面派
                // EnsureFreeCursorSlotTask 会把腾位子树整棵 STOP (2x2 版已由
                // altoclef_gridwar.log 实锤: consolidate 拿起 → ensurefree 换走, 每 2s
                // 一轮; 机制见 CursorArbiter 类注释)。复用同一 Receive 实例 = 树不换。
                if (!cursor.isEmpty() && !isCursorPartOfCraft(cursor)
                        && CursorArbiter.isHeldBy(CursorArbiter.INTENT_RELOCATE)) {
                    if (_receiveTask == null) {
                        _receiveTask = new ReceiveCraftingOutputSlotTask(
                                PlayerSlot.CRAFT_OUTPUT_SLOT, readyTarget.getTargetCount());
                    }
                    setDebugState("Cursor leased by free-space chain, holding receive");
                    return _receiveTask;
                }
                _receiveTask = null;
                if (!cursor.isEmpty() && !isCursorPartOfCraft(cursor)) {
                    setDebugState("Returning stranded cursor item: " + cursor.getItem().getTranslationKey());
                    return new EnsureFreeCursorSlotTask();
                }
                if (cursor.isEmpty()) {
                    return new ReceiveCraftingOutputSlotTask(PlayerSlot.CRAFT_OUTPUT_SLOT, readyTarget.getTargetCount());
                }
            }
        }

        if (_collect) {
            // (2026-09-06 烧铁 2.4s 死循环修复, 用户实测 19:08:21-53 六轮) 子链里有
            // 活跃的 MoveItemToSlotFromInventoryTask 时, 光标上必然是它正在搬运的
            // 中间态物品 (本例: 熔炉燃料 oak_planks, 第一步 PICKUP 已上光标、第二步
            // 点进 fuel 槽还没执行)。此刻材料判定会把光标木板计入 → materialsReady
            // 翻 true → super.onTick → 光标守卫("Clearing cursor slot")把熔炉子链
            // 抢断, 木板被点回背包 → 子链重启再拿 → 每 ~2.4s 死循环, 燃料永远放不
            // 进炉子, 铁一直开不了烧。搬运两步的中间态必须让它走完: 搬运进行中
            // 保持返回 _collectTask 不翻面, 搬完光标自然清空再正常评估。
            // 豁免边界: 搬运目标槽是【本工作台合成格】(CraftingTableSlot) 时不粘 —
            // 那是本任务自己的放料流程, 粘住会反过来掐断正常合成。
            if (thisOrChildSatisfies(task -> {
                if (!(task instanceof MoveItemToSlotFromInventoryTask move)) return false;
                Slot dest = move.getDestination();
                return !(dest instanceof CraftingTableSlot);
            })) {
                setDebugState("Child slot-move in flight, holding collect task...");
                return _collectTask;
            }
            // (2026-09-06 木镐差1木板死锁修复, 同时防 09-01"有木棍仍造木棍"横跳复发)
            //
            // 死锁实锤(2026-09-06 12:37:58 trace): _collectTask 判"集齐放行 wooden_pickaxe"
            // 时 4 块木板还在 2x2 合成格; 0.25s 后同链兄弟步骤(Craft 2x2 Task 造工作台)
            // 把木板全部消费 → 真实背包只剩 2 块(配方要 3)。旧闸门
            // `!_collectTask.isFinished() && !hasRecipeMaterialsOrTarget` 中 isFinished 是
            // 单向闩锁(见 CollectRecipeCataloguedResourcesTask.isFinished 注释), 闩死后
            // 闸门永久放行 → 台前 "Crafting" 空转, 配方书填不满 3x3, 12s 超时刷新无限循环。
            //
            // 新闸门语义:
            //  1. 以【每 tick 实时】hasRecipeMaterialsOrTarget 为唯一权威 —— 材料缺就回
            //     收集任务, 闩锁过期时 reset 一次让它重新盘点。
            //  2. 防横跳三道护栏(08-27 版"缺料就 reset"会横跳的根因逐一排除):
            //     a. 只在闩锁已置 true 时才 reset —— 收集任务进行中(其内部
            //        _activeCollectTask 正在做中间合成, 材料在 2x2 格/光标上)时 _finished
            //        必为 false, 外层绝不打断, 由其内部粘滞逻辑自己走完;
            //     b. 输出格已有本配方成品待收 → 材料已投入, 放行给下方 super.onTick 的
            //        Getting output 流程, 不误判"缺料"回收集;
            //     c. 光标上持有本配方物品(合成流程进行中) → 同样不打断。
            //     护栏 b/c 使"材料在合成流程中(格/输出/光标)"永不判缺; 护栏 a 使 reset
            //     只发生在收集任务确认闲置(闩死)且材料被外部真实消费的场景。
            //  3. (2026-09-06 2x2 中间合成与放台互拆修复, 用户实测 19:39:07-21 三轮
            //     ~15s 颠簸) 子链有活跃 CraftInInventoryTask (2x2 随身格中间配方,
            //     如木棍/木板) 且【已持有工作台物品】时, 保持返回 _collectTask 不翻面:
            //     此时中间材料在 2x2 格里会被 hasRecipeMaterialsOrTarget 计入 →
            //     materialsReady 误翻 true → super.onTick 走"放工作台"分支 →
            //     ResourceTask 网格清理器(EnsureFreePlayerCraftingGridTask)把进行中的
            //     2x2 合成清掉 → 中间产物没做出、材料回背包 → 又判缺料重新收集再占格
            //     → 再清, "Place crafting_table ↔ Breaking the crafting grid" 三轮。
            //     边界①: 工作台未持有时【不粘】—— super.onTick 的 "Getting container
            //     item" 本身就要用 2x2 合成工作台, 粘住会死锁。isItemOwned(USE) 同时
            //     认"世界里已有放置好的台子", 台子已在世界时也不急着翻面放台。
            //     边界②: 只认 CraftInInventoryTask, 不认 ReceiveCraftingOutputSlotTask
            //     等 —— 3x3 流程的 "Receiving output" 也实现 ITaskUsesCraftingGrid,
            //     若纳入会在正常合成收产物时误粘掐断合成。
            if (StorageHelper.isItemOwned(mod, StorageHelper.Ownership.USE, getContainerTarget().getMatches())
                    && thisOrChildSatisfies(task -> task instanceof CraftInInventoryTask)) {
                setDebugState("2x2 intermediate craft in flight, holding collect task...");
                return _collectTask;
            }
            boolean materialsReady = StorageHelper.hasRecipeMaterialsOrTarget(mod, _targets);
            if (!materialsReady) {
                boolean bigCraftingNow = (mod.getPlayer().currentScreenHandler instanceof CraftingScreenHandler);
                Slot outSlotId = bigCraftingNow ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;
                ItemStack outNow = StorageHelper.getItemStackInSlot(outSlotId);
                ItemStack cursorNow = StorageHelper.getItemStackInCursorSlot();
                // (2026-09-10 属主收紧, 19:52 场次 tasks.log t=310 实锤) 输出格/光标
                // 上的物品只认【第一个未满足目标】的 —— 旧口径扫全部 _targets, 多目标
                // 列表 (全套装备含 diamond_pickaxe) 时, 子合成 (镐) 放进输出格的成品
                // 被当成"本任务合成在飞" → materialsReady 被旁路放行 → 下方
                // containerSubTask 按序开工第一目标 (胸甲) → 打断镐的台格 → 循环。
                // 收紧后子合成产物不再放行, materialsReady=false → 返回 _collectTask,
                // 子链 (镐) 自然继续收尾; 单目标任务行为不变。
                RecipeTarget firstNeeded = null;
                for (RecipeTarget target : _targets) {
                    if (target.getOutputItem() == null) continue;
                    if (StorageHelper.countItemInInventoryReal(mod, target.getOutputItem()) < target.getTargetCount()) {
                        firstNeeded = target;
                        break;
                    }
                }
                boolean craftInFlight = false;
                if (firstNeeded != null) {
                    craftInFlight = (!outNow.isEmpty() && outNow.getItem() == firstNeeded.getOutputItem())
                            || (!cursorNow.isEmpty() && isCursorPartOfTargetCraft(cursorNow, firstNeeded));
                }
                if (craftInFlight) {
                    materialsReady = true;
                }
            }
            if (!materialsReady) {
                if (_collectTask.isFinished(mod)) {
                    _collectTask.reset();
                    Debug.logMessage("[CraftTable] 材料被同链步骤消费(收集闩锁过期), 重新盘点: "
                            + Arrays.toString(_targets));
                }
                setDebugState("craft does NOT have RECIPE MATERIALS: " + Arrays.toString(_targets));
                return _collectTask;
            }
        }

        if (!isContainerOpen(mod)) {
            _craftResetTimer.reset();
        }

        // Make sure our recipe items are accessible in our inventory
        if (!thisOrChildSatisfies(task -> task instanceof CraftInInventoryTask)) {
            for (RecipeTarget target : _targets) {
                for (int slot = 0; slot < target.getRecipe().getSlotCount(); ++slot) {
                    ItemTarget toCheck = target.getRecipe().getSlot(slot);
                    if (StorageHelper.isItemInaccessibleToContainer(mod, toCheck)) {
                        return new MoveInaccessibleItemToInventoryTask(toCheck);
                    }
                }
            }
        }

        return super.onTick(mod);
    }

    @Override
    protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
        if (other instanceof DoCraftInTableTask task) {
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }


    /**
     * [2026-08-21 光标卡死修复] 光标物品是否属于任一配方的流程(材料或输出)。
     */
    private boolean isCursorPartOfCraft(ItemStack cursor) {
        Item item = cursor.getItem();
        for (RecipeTarget target : _targets) {
            if (isCursorPartOfTargetCraft(cursor, target)) return true;
        }
        return false;
    }

    /** (2026-09-10 属主收紧) 光标物品是否属于【单个目标】配方的流程(材料或输出)。 */
    private boolean isCursorPartOfTargetCraft(ItemStack cursor, RecipeTarget target) {
        Item item = cursor.getItem();
        if (target.getOutputItem() == item) return true;
        for (int slot = 0; slot < target.getRecipe().getSlotCount(); ++slot) {
            ItemTarget slotTarget = target.getRecipe().getSlot(slot);
            if (slotTarget != null && !slotTarget.isEmpty() && slotTarget.matches(item)) return true;
        }
        return false;
    }

    @Override
    protected boolean isContainerOpen(AltoClef mod) {
        return (mod.getPlayer().currentScreenHandler instanceof CraftingScreenHandler);
    }

    @Override
    protected Task containerSubTask(AltoClef mod) {

        // Refresh crafting table Juuust in case
        _craftResetTimer.setInterval(mod.getModSettings().getContainerItemMoveDelay() * 10 + CRAFT_RESET_TIMER_BONUS_SECONDS);
        if (_craftResetTimer.elapsed()) {
            Debug.logMessage("Refreshing crafting table.");
            StorageHelper.closeScreen();
            return null;
        }

        // Reset refresh timer if we have an item in the output slot
        boolean bigCrafting = (mod.getPlayer().currentScreenHandler instanceof CraftingScreenHandler);
        Slot outputSlot = bigCrafting ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;
        if (!StorageHelper.getItemStackInSlot(outputSlot).isEmpty()) {
            _craftResetTimer.reset();
        }

        for (RecipeTarget target : _targets) {
            // (2026-08-28 修复: 输出格成品要先“点合成收进背包”, 不能算已到手)
            // 判定“这件好了没”只用【真实落袋背包】计数。
            int realHave = StorageHelper.countItemInInventoryReal(mod, target.getOutputItem());
            if (realHave >= target.getTargetCount())
                continue;
            ItemStack outSlot = StorageHelper.getItemStackInSlot(outputSlot);
            // 输出格已生成成品(配方书/手动合成已摆好材料、只差点一下收下):
            // 必须先点输出格把成品收进背包 — 原代码把输出格计数加进 realHave,
            // 导致这里判定“已到手”跳过, 永不点合成, 成品卡在输出格刷不出来
            // (用户实测: 材料摆好、配方书点了却一直不点合成 → 盾卡 Waiting for recipe)。
            // 收走后 realHave 下一 tick 满足, 自然切下一件, 不会掐断上一件。
            // (2026-08-31 反复"Getting output"修复) 输出槽【非空且匹配】才进收成品分支。
            // 旧代码只判 outSlot.getItem()==成品 —— 输出槽短暂渲染成"可合成"或客户端
            // 状态未同步时, 即使实际空槽也判定"有成品" → 反复派发 ReceiveCraftingOutput
            // (它内部读到空 → "Output slot empty" return) → 来回 4-5 次 (日志做石剑时 5 次
            // 反复实锤)。加 isNotEmpty() 保证只有真看到成品才收。
            if (!outSlot.isEmpty() && outSlot.getItem() == target.getOutputItem()) {
                setDebugState("Getting output");
                return new ReceiveCraftingOutputSlotTask(outputSlot, target.getTargetCount());
            }
            // No need to free, handled automatically I believe.
            setDebugState("Crafting");

            return mod.getModSettings().shouldUseCraftingBookToCraft()
                    ? new CraftGenericWithRecipeBooksTask(target)
                    : new CraftGenericManuallyTask(target);
        }

        setDebugState("DONE? Shouldn't be here");
        // (2026-09-05 金锭合成站死修复) 全目标已满足时必须标记完成: _craftCount 自
        // 上游整理 (8c30fd7c) 起从未被自增, isFinished 恒 false。此处不标, 任务
        // return null 后父任务 isEqual 判同实例不换子任务 → bot 在工作台前永久
        // 呆住 (2026-09-05 金锭合成 12s 静止实锤, 用户手动停止)。
        _craftCount = _targets.length;
        return null;
    }

    /** (2026-09-13) collect=false = "材料在调用方手上, 本任务不负责补料"。 */
    public boolean isCollecting() {
        return _collect;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _craftCount >= _targets.length;//_crafted;
    }

    @Override
    protected double getCostToMakeNew(AltoClef mod) {
        // TODO: If we have an axe, lower the cost.
        if (mod.getItemStorage().hasItem(ItemHelper.LOG) || mod.getItemStorage().getItemCount(ItemHelper.PLANKS) >= 4) {
            // We can craft it right now, so it's real cheap
            return 10;
        }
        // TODO: If cached and the closest log is really far away, strike the price UP
        return 100;
    }

    private Item[] getMaterialsArray() {
        List<Item> result = new ArrayList<>();
        for (RecipeTarget target : _targets) {
            for (int i = 0; i < target.getRecipe().getSlotCount(); ++i) {
                ItemTarget materialTarget = target.getRecipe().getSlot(i);
                if (materialTarget == null || materialTarget.getMatches() == null) continue;
                Collections.addAll(result, materialTarget.getMatches());
            }
        }

        return result.toArray(Item[]::new);
    }

}
