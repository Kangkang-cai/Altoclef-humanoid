package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.CraftGenericManuallyTask;
import adris.altoclef.tasks.CraftGenericWithRecipeBooksTask;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.resources.CollectRecipeCataloguedResourcesTask;
import adris.altoclef.tasks.slot.MoveInaccessibleItemToInventoryTask;
import adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
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
        _craftTask = new DoCraftInTableTask(_targets);
    }

    public CraftInTableTask(RecipeTarget target, boolean collect, boolean ignoreUncataloguedSlots) {
        super(new ItemTarget(target.getOutputItem(), target.getTargetCount()));
        _targets = new RecipeTarget[]{target};
        _craftTask = new DoCraftInTableTask(_targets, collect, ignoreUncataloguedSlots);
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

    public DoCraftInTableTask(RecipeTarget[] targets, boolean collect, boolean ignoreUncataloguedSlots) {
        super(Blocks.CRAFTING_TABLE, new ItemTarget("crafting_table"));
        _collectTask = new CollectRecipeCataloguedResourcesTask(ignoreUncataloguedSlots, targets);
        _targets = targets;
        _collect = collect;
    }

    public DoCraftInTableTask(RecipeTarget[] targets) {
        this(targets, true, false);
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        _craftCount = 0;
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
            // (2026-09-01 回归原版简洁, 用户"有木棍仍造木棍 + 制作放置反复")
            // 原版语义: 只有 _collectTask 尚未完成 且 确实缺配方材料 时, 才滞留
            // 在材料收集任务上; 一旦收集完成(_collectTask.isFinished, 背包已备齐
            // 材料)就自然放行到下面正常合成。恢复此结构为唯一闸门。
            // 旧版(2026-08-27 我们加的)把 isFinished 放进材料判断内, 并在材料不足
            // 时对 _collectTask.reset() 强制重来 —— 背包明明已备齐木棍/木板, 收集
            // 任务一 isFinished 就被重置重建, 下一 tick 又判定"缺材料" → 反复进入
            // 收集链, 表现为"有木棍仍造木棍 / 制作放置反复"(对照原版 CraftInTable
            // 原始实现, 这是原版没有的 reintroduced 行为)。
            // (2026-09-01 二次修订: 撤销"木板当量"兜底旁路)
            // 该兜底是修0(itemTargetOwnedIncludingCraftGrid 数量BUG)存在期间的
            // 权宜之计: 当时 _collectTask(材料收集)有1件木板就误判齐、永远不再
            // 合成木板, 只能靠当量旁路放行。但修0已让 _collectTask 恢复正确:
            // 缺3木板时会主动派 CraftInInventory 把原木合成木板。此时当量旁路
            // 反而有害 — 用户实测(15:54): 台子放好后做木斧, 旁路判"原木×4当量够"
            // 直接放行上台合成, 但真实木板≤1块填不满3x3格 → 台前 Waiting 空转
            // ↔ Doing-something-to-closest-block 每2秒乒乓。
            // 恢复原版语义: 材料未真实齐 → 回 _collectTask 合成/收集缺失材料。
            if (!_collectTask.isFinished(mod) && !StorageHelper.hasRecipeMaterialsOrTarget(mod, _targets)) {
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
            if (target.getOutputItem() == item) return true;
            for (int slot = 0; slot < target.getRecipe().getSlotCount(); ++slot) {
                ItemTarget slotTarget = target.getRecipe().getSlot(slot);
                if (slotTarget != null && !slotTarget.isEmpty() && slotTarget.matches(item)) return true;
            }
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
