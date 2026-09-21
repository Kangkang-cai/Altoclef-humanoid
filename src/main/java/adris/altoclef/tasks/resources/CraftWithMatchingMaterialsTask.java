package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.item.Item;

public abstract class CraftWithMatchingMaterialsTask extends ResourceTask {

    private final ItemTarget _target;
    private final CraftingRecipe _recipe;
    private final boolean[] _sameMask;

    private final ItemTarget _sameResourceTarget;
    private final int _sameResourceRequiredCount;
    private final int _sameResourcePerRecipe;

    public CraftWithMatchingMaterialsTask(ItemTarget target, CraftingRecipe recipe, boolean[] sameMask) {
        super(target);
        _target = target;
        _recipe = recipe;
        _sameMask = sameMask;
        int sameResourceRequiredCount = 0;
        ItemTarget sameResourceTarget = null;
        if (recipe.getSlotCount() != sameMask.length) {
            Debug.logError("Invalid CraftWithMatchingMaterialsTask constructor parameters: Recipe size must equal \"sameMask\" size.");
        }
        for (int i = 0; i < recipe.getSlotCount(); ++i) {
            if (sameMask[i]) {
                sameResourceRequiredCount++;
                sameResourceTarget = recipe.getSlot(i);
            }
        }
        _sameResourceTarget = sameResourceTarget;
        int craftsNeeded = (int) (1 + Math.floor((double) target.getTargetCount() / recipe.outputCount() - 0.001));
        _sameResourcePerRecipe = sameResourceRequiredCount;
        _sameResourceRequiredCount = sameResourceRequiredCount * craftsNeeded;
    }

    private static CraftingRecipe generateSamedRecipe(CraftingRecipe diverseRecipe, Item sameItem, boolean[] sameMask) {
        ItemTarget[] result = new ItemTarget[diverseRecipe.getSlotCount()];
        for (int i = 0; i < result.length; ++i) {
            if (sameMask[i]) {
                result[i] = new ItemTarget(sameItem, 1);
            } else {
                result[i] = diverseRecipe.getSlot(i);
            }
        }
        return CraftingRecipe.newShapedRecipe(result, diverseRecipe.outputCount());
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        // TODO: Scenario of
        //      Command: Get 3 beds
        //
        //      We have 3 red wool
        //      We have 6 white wool
        //
        //      The system should craft 1 red bed + 2 white beds
        //      BUT since it needs 9 of the --SAME WOOL-- it keeps going.
        //      You should MAP for each type how many can fit into --_sameResourcePerRecipe-- and grab from THAT list.

        /*
         * 0) Figure out the "same resource" item target
         * 1) Get the "same resource" item matches array
         * 2) Figure out how many of the "same resource" we need
         * 3) Get the most frequent occurrence of said material
         * 4) If the most frequent occurrence is NOT met, return TaskCatalogue.getItemTask("same resource" item target)
         * 5) If the most frequent occurrence IS met, run CraftInTable with a custom recipe that only has the frequent material.`
         */

        // For each "same" item: How many items can we craft with it?
        // For instance, if we have 7 red wool, we can craft 2 beds
        // sameFullCraftsPermitted[Items.RED_WOOL] = 2;
        int canCraftTotal = 0;
        int majorityCraftCount = 0;
        Item majorityCraftItem = null;
        for (Item sameCheck : _sameResourceTarget.getMatches()) {
            int count = getExpectedTotalCountOfSameItem(mod, sameCheck);
            int canCraft = (count / _sameResourcePerRecipe) * _recipe.outputCount();
            canCraftTotal += canCraft;
            if (canCraft > majorityCraftCount) {
                majorityCraftCount = canCraft;
                majorityCraftItem = sameCheck;
            }
        }

        // If we already have some of our target, we need less "same" materials.
        int currentTargetCount = StorageHelper.countItemsInInventoryReal(mod, _target.getMatches());
        int currentTargetsRequired = _target.getTargetCount() - currentTargetCount;

        if (canCraftTotal >= currentTargetsRequired) {
            // We have enough of the same resource!!!
            // Handle crafting normally.

            // We may need to convert our raw materials into our "matching" materials.
            int trueCanCraftTotal = 0;
            for (Item sameCheck : _sameResourceTarget.getMatches()) {
                // (2026-09-09 含合成台格口径) 与上方 canCraftTotal 判定保持同一口径:
                // 材料被配方书填充搬进 3x3 合成台格后从真实背包"消失", 纯背包计数会
                // 误判"实数不够" → getSpecificSameResourceTask(床类未覆写, logError+null)。
                int trueCount = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, sameCheck);
                int trueCanCraft = (trueCount / _sameResourcePerRecipe) * _recipe.outputCount();
                trueCanCraftTotal += trueCanCraft;
            }
            if (trueCanCraftTotal < currentTargetsRequired) {
                return getSpecificSameResourceTask(mod, _sameResourceTarget.getMatches());
            }

            CraftingRecipe samedRecipe = generateSamedRecipe(_recipe, majorityCraftItem, _sameMask);
            Item output = getSpecificItemCorrespondingToMajorityResource(majorityCraftItem);
            // (2026-09-21 只合成缺口, 162614 实锤) 旧: toCraftTotal = majorityCraftCount
            // + currentTargetCount —— currentTargetCount 是【全色系】目标成品持有量,
            // 而 RecipeTarget 的成品是 majority 单色 → 混色持有场景把别色成品也记进
            // "要合成数" → 目标 11 张、已有 10 张(只差 1)仍按 11 张收料 (缺口 12 羊毛
            // → "Collect 12 wool" 回地表找羊, 用户"修复生效了吗"现场)。
            // 新: 合成数 = 缺口张数(currentTargetsRequired)与 majority 材料可造数的
            // 较小值, 加上【单色】已有持有量 (CraftInTableTask 完成判定按单色持有,
            // 不补会被已持有同色成品误判提前翻 true 空转)。材料不足时按可造数渐进
            // 合成, 余下缺口下一轮由收料分支补。
            int outputHeld = mod.getItemStorage().getItemCount(output);
            int toCraftTotal = outputHeld + Math.min(Math.max(0, currentTargetsRequired), majorityCraftCount);
            toCraftTotal = Math.min(toCraftTotal, _target.getTargetCount());
            RecipeTarget recipeTarget = new RecipeTarget(output, toCraftTotal, samedRecipe);
            return _recipe.isBig() ? new CraftInTableTask(recipeTarget) : new CraftInInventoryTask(recipeTarget);
        }
        // Collect SAME resources first!!!
        return getAllSameResourcesTask(mod);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    // Virtual
    protected Task getAllSameResourcesTask(AltoClef mod) {
        ItemTarget infinityVersion = new ItemTarget(_sameResourceTarget, 999999);
        return TaskCatalogue.getItemTask(infinityVersion);
    }

    // Virtual
    // (2026-09-01 "bed<->food 父任务横跳 + 羊毛已在背包仍 Collect 999999"
    //  根因之一) 默认实现用 tracker 缓存 getItemCount, 刚捡的羊毛/材料被
    //  判 0 -> canCraftTotal<need -> getAllSameResourcesTask -> Collect 999999
    //  wool -> 无意义地杀羊/游荡, 永不退回合成路径; 改真实背包计数, 与
    //  ResourceTask / CollectFoodTask / CollectWoolTask 口径统一。
    // (2026-09-09 含合成格口径, 修做床 ping-pong) 真实背包计数不含合成格:
    //  配方书一键填充(instantFillRecipeViaBook)把羊毛搬进 3x3 台格后计数 3->0,
    //  canCraftTotal<need → 每轮填充后翻面 Collect 999999 wool 打断自家合成,
    //  关屏羊毛回背包 → 再填充 → 再打断, 08:27 tasks log 实锤 ~15 轮到天亮。
    //  改含格计数(2x2+3x3)后材料进格不再触发翻面, 合成一路走完。
    protected int getExpectedTotalCountOfSameItem(AltoClef mod, Item sameItem) {
        return StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, sameItem);
    }

    // Virtual
    protected Task getSpecificSameResourceTask(AltoClef mod, Item[] toGet) {
        Debug.logError("Uh oh!!! getSpecificSameResourceTask should be implemented!!!! Now we're stuck.");
        return null;
    }

    protected abstract Item getSpecificItemCorrespondingToMajorityResource(Item majority);
}
