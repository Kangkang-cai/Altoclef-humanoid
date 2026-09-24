package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.item.Item;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.HashMap;

// Collects everything that's catalogued for a recipe.
public class CollectRecipeCataloguedResourcesTask extends Task {

    private final RecipeTarget[] _targets;
    private final boolean _ignoreUncataloguedSlots;
    // (2026-09-24 隔离实例) true = 派材料子任务时走 TaskCatalogue.newDetachedItemTask,
    // 不命中共享活实例槽 (盾牌看门狗: 自己烧 1 铁, 不挂 gear 的 x9 炉子)。
    private final boolean _detached;
    private boolean _finished = false;
    // (2026-08-31 材料收集粘滞) 缓存"当前正在收集的某个材料子任务"实例。
    // 一旦派发了 getItemTask(某材料) 且未完成, 就持续返回同一实例, 不因每 tick
    // 重扫材料表而换个材料叉开 —— 否则做熔炉时"采石→造台→(下一tick)又切采石",
    // 采集节奏被反复打断 (日志 18:22:36 采石被 Craft 2x2 crafting_table 打断)。
    // 只有当前材料子任务完成后才放行去收集下一个缺失材料。
    private Task _activeCollectTask = null;

    public CollectRecipeCataloguedResourcesTask(boolean ignoreUncataloguedSlots, RecipeTarget... targets) {
        this(ignoreUncataloguedSlots, false, targets);
    }

    public CollectRecipeCataloguedResourcesTask(boolean ignoreUncataloguedSlots, boolean detached, RecipeTarget... targets) {
        _targets = targets;
        _ignoreUncataloguedSlots = ignoreUncataloguedSlots;
        _detached = detached;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _finished = false;
        _activeCollectTask = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-08-31 粘滞) 正在收集的某个材料子任务完成前, 持续复用, 不重扫换材料。
        if (_activeCollectTask != null && !_activeCollectTask.isFinished(mod)) {
            return _activeCollectTask;
        }
        _activeCollectTask = null;

        // TODO: Cache this once instead of doing it every frame.

        // Stuff to get, both catalogued + individual items.
        HashMap<String, Integer> catalogueCount = new HashMap<>();
        HashMap<Item, Integer> itemCount = new HashMap<>();

        for (RecipeTarget target : _targets) {
            // Ignore this recipe if we have its item.
            //if (mod.getItemStorage().targetMet(target.getItem())) continue;

            // null = empty which is always met.
            if (target == null) continue;

            // (2026-08-30 分而治之: 背包权威计数) 用真实背包而非 500ms 缓存,
            // 避免"缺什么其实有什么"(副手盾/手持铁剑/护甲被缓存排除) → 递归
            // 不再发散去做铁剑/熔炉/挖圆石。
            int weNeed = target.getTargetCount() - mod.getItemStorage().getItemCountReal(target.getOutputItem());

            if (weNeed > 0) {
                CraftingRecipe recipe = target.getRecipe();
                // Default, just go through the recipe slots and collect the first one.
                for (int i = 0; i < recipe.getSlotCount(); ++i) {
                    ItemTarget slot = recipe.getSlot(i);
                    if (slot == null || slot.isEmpty()) continue;
                    int numberOfRepeats = (int) Math.floor(-0.1 + (double) weNeed / target.getRecipe().outputCount()) + 1;
                    if (!slot.isCatalogueItem()) {
                        if (slot.getMatches().length != 1) {
                            // (2026-08-27 卡死修复) 多重匹配槽(如炉子: 圆石/黑石/
                            // 深板岩圆石, 任一都能填) 旧逻辑只打 warning 不收集 →
                            // 材料没齐却不触发去拿, isFinished 反复重置 → 无限卡死
                            // ("没有材料怎么不去获取", 用户实测)。任选一个可收集的
                            // 匹配项去收集: 优先选已有进度的, 否则取第一个(炉子即
                            // 圆石)。
                            // 变体选择用真实背包计数, 优先选手上已有的变体(如深板岩圆石),
                            // 而非走缓存(可能把所有变体都判成 0)。
                            Item pick = slot.getMatches()[0];
                            for (Item m : slot.getMatches()) {
                                if (mod.getItemStorage().getItemCountReal(m) > 0) {
                                    pick = m;
                                    break;
                                }
                            }
                            itemCount.put(pick, itemCount.getOrDefault(pick, 0) + numberOfRepeats);
                        } else {
                            Item item = slot.getMatches()[0];
                            itemCount.put(item, itemCount.getOrDefault(item, 0) + numberOfRepeats);
                        }
                    } else {
                        String targetName = slot.getCatalogueName();
                        // How many "repeats" of a recipe we will need.
                        catalogueCount.put(targetName, catalogueCount.getOrDefault(targetName, 0) + numberOfRepeats);
                    }
                }
            }
        }


        // (Cache this with the above stuff!!)
        // Grab materials
        for (String catalogueMaterialName : catalogueCount.keySet()) {
            int count = catalogueCount.get(catalogueMaterialName);
            if (count > 0) {
                ItemTarget itemTarget = new ItemTarget(catalogueMaterialName, count);
                // (2026-08-31 材料重复收集修复) 用统一真实持有判定, 替代缓存的
                // itemTargetsMet。旧逻辑走缓存 getItemCount, 刚收集/合成进背包的
                // 材料(如木棍)被缓存滞后判成"不足" → 反复派发 getItemTask → 重复造
                // (用户实测: 背包十几根木棍仍反复做木棍 + 放木板拿回振荡)。
                // (2026-09-01 合成格并入: 材料放进行 2x2 格等待合成时也算"已有",
                // 否则背包计数不含合成格 → 材料一进合成格就"变没" → 反复收集喂光)
                if (!StorageHelper.itemTargetOwnedIncludingCraftGrid(mod, StorageHelper.Ownership.POSSESS, itemTarget)) {
                    setDebugState("Getting " + itemTarget);
                    // (2026-09-24) detached: 独立实例, 不命中共享活实例槽。
                    _activeCollectTask = _detached
                            ? TaskCatalogue.newDetachedItemTask(catalogueMaterialName, count)
                            : TaskCatalogue.getItemTask(catalogueMaterialName, count);
                    Debug.logTrace("craft-material", "派发收集材料 " + catalogueMaterialName + " x" + count
                            + ", 真实持有(±合成格)=" + StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, itemTarget.getMatches())
                            + ", 目标=" + itemTarget.toString());
                    return _activeCollectTask;
                }
            }
        }
        for (Item item : itemCount.keySet()) {
            int count = itemCount.get(item);
            if (count > 0) {
                // (2026-09-01 合成格并入, 见上) 非 catalogue 材料同样把 2x2 合成格中的
                // 计入"已有", 避免材料进合成格后被误判缺失反复收集。
                if (mod.getItemStorage().getItemCountReal(item) + StorageHelper.countItemsInCraftGrid(mod, item) < count) {
                    setDebugState("Getting " + item.getTranslationKey());
                    // (2026-09-24) detached: 独立实例, 不命中共享活实例槽。
                    _activeCollectTask = _detached
                            ? TaskCatalogue.newDetachedItemTask(item, count)
                            : TaskCatalogue.getItemTask(item, count);
                    return _activeCollectTask;
                }
            }
        }
        _finished = true;
        // (2026-09-01 验证日志, 用户要求) 完成一次材料收集(即将放行合成)时打印
        // 实际目标物品、目标数、真实持有数, 便于确认"收集完成即放行"是否生效
        // (替代反复的 [craft-material] 口径不一致日志)。
        Debug.logTrace("craft-material", "== 材料集齐, 放行合成: targets=" + Arrays.toString(_targets)
                + " (全部真实背包判定达标)");

        return null;
    }


    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectRecipeCataloguedResourcesTask task) {
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Collect Recipe Resources: " + ArrayUtils.toString(_targets);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-01 口径统一, 消灭反复合成) 完全信任 onTick 的判定: onTick 已用
        // 统一【真实背包】计数(itemTargetOwned POSSESS + getItemCountReal)严谨判断过
        // 材料是否齐、material 收集是否完成, 只在收集完所有缺失材料后才置 _finished=true。
        // 原实现 isFinished 又用另一套 hasRecipeMaterialsOrTarget 去"复核"并推翻 _finished:
        // 两套口径对"合成格中的材料"计数不一致(hasRecipeMaterialsOrTarget 额外计入 2x2
        // 合成格, onTick 的真实背包计数不含合成格) → 材料已搬进合成格等待合成时,
        // onTick 判定完成、isFinished 却判"缺材料"重置 false → 每 tick 反复重发
        // getItemTask(如 wooden_axe),  永远合不出来。日志实锤: altoclef_trace.log
        // "[craft-material] isFinished 判定材料不足...配方=wooden_axe x1" 每 1 秒重复一次。
        // 现在 isFinished 不再用 hasRecipeMaterialsOrTarget 推翻 _finished, 与 onTick 口径
        // 完全一致, 避免做木斧/台子/镐等反复横跳。
        return _finished;
    }
}
