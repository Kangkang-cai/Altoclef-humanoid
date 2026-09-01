package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.CollectRecipeCataloguedResourcesTask;
import adris.altoclef.tasks.slot.EnsureFreeCursorSlotTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Crafts an item within the 2x2 inventory crafting grid.
 *
 * (2026-08-27 关键修复) 实现 ITaskUsesCraftingGrid: 否则 ResourceTask.onTick 的
 * 网格清理器判断"链上没有任何用合成格的任务"→ 只要 2x2 格里有物品就派
 * EnsureFreePlayerCraftingGridTask 清空 → 正在做木板/木棍的 2x2 合成被反复清掉,
 * 死循环 (用户实测: 做盾牌卡 Waiting for recipe / 反复换木板, 日志 14:33:01-11
 * 木板合成一直被 "Clearing the 2x2 crafting grid" 打断)。
 */
public class CraftInInventoryTask extends ResourceTask implements ITaskUsesCraftingGrid {

    private final RecipeTarget _target;
    private final boolean _collect;
    private final boolean _ignoreUncataloguedSlots;
    private boolean _fullCheckFailed = false;

    public CraftInInventoryTask(RecipeTarget target, boolean collect, boolean ignoreUncataloguedSlots) {
        super(new ItemTarget(target.getOutputItem(), target.getTargetCount()));
        _target = target;
        _collect = collect;
        _ignoreUncataloguedSlots = ignoreUncataloguedSlots;
    }

    public CraftInInventoryTask(RecipeTarget target) {
        this(target, true, false);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // (2026-08-31 修复 craft thrash, 专项深挖) 必须返回 true: 本任务作为
        // ResourceTask, 父类 onTick 会用 shouldPickupDrops 去捡 _itemTargets(即要
        // 合成的输出, 如 stick) 的【地面掉落物】。这会让"正在合成 stick"的同时又被
        // "去捡地面 stick 掉落物"抢走 → 合成/捡/收材料 每 tick 三态互抢, TaskPingPong
        // (日志 08:47 TaskPingPong 6x: CraftInInventoryTask 在 PickupDropped ↔ CollectRecipe
        // 间 thrash)。而本任务的输出根本不经地面捡: 输出从【合成格输出槽】由
        // ReceiveCraftingOutputSlotTask 收走, 材料走 CollectRecipeCataloguedResourcesTask
        // (子任务自带拾取)。所以合成进行中不该被地面同物掉落物打断, 返回 true 屏蔽拾取。
        return true;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        // Our inventory slots are here for conversion
        int recSlot = 0;
        for (Slot slot : PlayerSlot.CRAFT_INPUT_SLOTS) {
            ItemTarget valid = _target.getRecipe().getSlot(recSlot++);
            mod.getBehaviour().markSlotAsConversionSlot(slot, stack -> valid.matches(stack.getItem()));
        }

        _fullCheckFailed = false;
        StorageHelper.closeScreen(); // Just to be safe I guess
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Grab from output FIRST
        if (StorageHelper.isPlayerInventoryOpen()) {
            // [2026-08-21 光标卡死修复] 光标上残留了非本配方的物品(如被中断
            // 的点击留下的床)时: 输出格无法接收光标物品 → 点输出全部无效,
            // 手动合成也会乱点 → 永久卡在"反复点输出格无效"。先放回背包。
            // 排除配方材料/输出本身: 手动合成任务的合法点击序列中间态会
            // 短暂持有它们, 不能打断。
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (!cursor.isEmpty() && !isCursorPartOfCraft(cursor)) {
                setDebugState("Returning stranded cursor item: " + cursor.getItem().getTranslationKey());
                return new EnsureFreeCursorSlotTask();
            }
            if (cursor.isEmpty()) {
                Item outputItem = StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT).getItem();
                for (ItemTarget target : _itemTargets) {
                    if (target.matches(outputItem)) {
                        return new ReceiveCraftingOutputSlotTask(PlayerSlot.CRAFT_OUTPUT_SLOT, target.getTargetCount());
                    }
                }
            }
        }

        ItemTarget toGet = _itemTargets[0];
        Item toGetItem = toGet.getMatches()[0];
        if (_collect && !StorageHelper.hasRecipeMaterialsOrTarget(mod, _target)) {
            // Collect recipe materials
            setDebugState("Collecting materials");
            return collectRecipeSubTask(mod);
        }

        // No need to free inventory, output gets picked up.

            setDebugState("Crafting in inventory... for " + toGet);
            return mod.getModSettings().shouldUseCraftingBookToCraft() ? new CraftGenericWithRecipeBooksTask(_target) : new CraftGenericManuallyTask(_target);


    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CraftInInventoryTask task) {
            if (!task._target.equals(_target)) return false;
            return isCraftingEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return toCraftingDebugStringName() + " " + _target;
    }

    // virtual. By default assumes subtasks are CATALOGUED (in TaskCatalogue.java)
    protected Task collectRecipeSubTask(AltoClef mod) {
        return new CollectRecipeCataloguedResourcesTask(_ignoreUncataloguedSlots, _target);
    }

    /**
     * [2026-08-21 光标卡死修复] 光标物品是否属于本配方流程(材料或输出)。
     * 手动合成任务点击序列的中间态会短暂把材料/输出放在光标上, 属合法
     * 状态; 其余物品(如被中断点击残留的床)必须清走, 否则点输出格无效。
     */
    private boolean isCursorPartOfCraft(ItemStack cursor) {
        Item item = cursor.getItem();
        for (ItemTarget target : _itemTargets) {
            if (target.matches(item)) return true;
        }
        for (int slot = 0; slot < _target.getRecipe().getSlotCount(); ++slot) {
            ItemTarget slotTarget = _target.getRecipe().getSlot(slot);
            if (slotTarget != null && !slotTarget.isEmpty() && slotTarget.matches(item)) return true;
        }
        return false;
    }

    protected String toCraftingDebugStringName() {
        return "Craft 2x2 Task";
    }

    protected boolean isCraftingEqual(CraftInInventoryTask other) {
        return true;
    }

    public RecipeTarget getRecipeTarget() {
        return _target;
    }
}
