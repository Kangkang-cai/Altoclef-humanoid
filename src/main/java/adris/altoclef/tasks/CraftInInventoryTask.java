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
    // (2026-09-02 收集粘性, 消除"集齐 vs 缺料"空转) 当前持住的材料收集子任务实例。
    // 与 CraftInTableTask._collectTask 同构: 一旦派发就持续复用直到 isFinished, 完成
    // 后放行正式合成, 不再每 tick 用 hasRecipeMaterialsOrTarget 反复推翻重派收集。
    private Task _collectTask = null;

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
        _collectTask = null;
        StorageHelper.closeScreen(); // Just to be safe I guess
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Grab from output FIRST
        if (StorageHelper.isPlayerInventoryOpen()) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            // (2026-09-02 材料制作不被打断) 收输出才是真正需要"光标空"的时机:
            // 光标有非配方残留会挡点输出格 (2026-08-21 卡死修复的初衷)。但旧逻辑
            // 每 tick 只要光标有非配方物品就派 EnsureFreeCursorSlotTask — 在
            // 【收集材料/合成阶段】 (2x2 合成格操作不需要光标空) 也会被打断; 且
            // 光标残留遇背包满放不回时 EnsureFreeCursorSlotTask 内部反复点击横跳
            // (tasks.log 14:10:40 Clicking slot3 ↔ slot1 实锤), 把"合木棍→合木板"
            // 这类简单材料制作无限打断 → 石镐缺木棍卡死 (tasks.log 14:25:07 实锤)。
            // 现改为: 仅当输出槽已就绪本配方产物时, 才检查并清理光标残留。
            ItemTarget readyTarget = null;
            Item outputItem = StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT).getItem();
            for (ItemTarget target : _itemTargets) {
                if (target.matches(outputItem)) {
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

        ItemTarget toGet = _itemTargets[0];
        Item toGetItem = toGet.getMatches()[0];
        if (_collect && !StorageHelper.hasRecipeMaterialsOrTarget(mod, _target)) {
            // (2026-09-02 收集粘性 + 完成即放行, 用户实测 21:37 卡 8 秒) 旧逻辑每
            // tick 只要 hasRecipeMaterialsOrTarget 判"缺料"就重派全新收集子任务; 而
            // CollectRecipeCataloguedResourcesTask 用【真实背包 + 2x2 合成格】判定,
            // 一旦 `_finished` 残留 true, isEqual 复用同一实例 → 一直答"材料集齐"
            // (null), 但切片材料(木板)其实没落袋 → hasRecipeMaterialsOrTarget 却读
            // real=0 → 两口径互相矛盾: 收集永远"完成"、正式合成永远启动不了
            // (日志: [craft-material] 集齐 + [CRAFT-LIMIT] plank=0 每 1s, 8 秒零切换,
            // 上层 DoStuffInContainerTask 每 1s 重发合工作台)。
            // 修复: 持住一个收集子任务, 未完成就一直返回它(粘滞, 不换材料); 一旦它
            // isFinished 就信任其"真齐"(它用实时真实计数) → 清理 _collectTask 放行
            // 下方正式合成, 不再用 hasRecipeMaterialsOrTarget 反复推翻重派收集。
            // 仅当收集尚未完成时才返回它做子任务。
            if (_collectTask == null) {
                _collectTask = collectRecipeSubTask(mod);
            }
            if (!_collectTask.isFinished(mod)) {
                setDebugState("Collecting materials");
                return _collectTask;
            }
            _collectTask = null;
            // 收集已宣告完成: 放行, 下方直接进入正式合成。
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
