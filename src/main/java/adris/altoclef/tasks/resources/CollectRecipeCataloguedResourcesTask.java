package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.item.Item;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Collects everything that's catalogued for a recipe.
public class CollectRecipeCataloguedResourcesTask extends Task {

    private final RecipeTarget[] _targets;
    private final boolean _ignoreUncataloguedSlots;
    // (2026-09-24 隔离实例) true = 派材料子任务时走 TaskCatalogue.newDetachedItemTask,
    // 不命中共享活实例槽 (盾牌看门狗: 自己烧 1 铁, 不挂 gear 的 x9 炉子)。
    private final boolean _detached;
    private boolean _finished = false;
    // (2026-09-29 放弃态重试, detail/25) 材料子任务连续以"放弃"结束的轮数。
    // 达到 MAX_CONSECUTIVE_GIVEUPS 打一次告警并归零 —— 把"上游任务卡死"暴露到
    // 日志, 而不是像 20260929-192036 场次那样零日志静默空转 4305s。
    private int _consecutiveGiveUps = 0;
    private static final int MAX_CONSECUTIVE_GIVEUPS = 3;
    // (20261113 铁律②出口·层2) 本生命周期内"连续放弃达上限"的材料 → 跳过不派发。
    // key = 材料名/item.toString(), value = 放弃时的维度 id; 咨询时按当前维度校验,
    // 换维度后旧条目自动失效 (材料可重试)。刻意不在 onStart 清空: CraftInTableTask
    // 的 reset() 会重跑 onStart, 清空会让"死材料跳过"被无限重置。
    private final java.util.Map<String, String> _giveUpMaterials = new java.util.HashMap<>();
    // 当前派发材料的 key (层2 归因用): 派发时记录, 放弃时据此除名。
    private String _activeCollectMaterialKey = null;
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
        // (2026-09-29) 新实例/重入即重置放弃计数, 不跨生命周期累积。
        _consecutiveGiveUps = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-08-31 粘滞) 正在收集的某个材料子任务完成前, 持续复用, 不重扫换材料。
        if (_activeCollectTask != null && !_activeCollectTask.isFinished(mod)) {
            return _activeCollectTask;
        }
        // (2026-09-29 放弃态识别, detail/25) 上一轮材料子任务若以"放弃"结束
        // (Task#hasAbandoned: 放弃后 isFinished=true 但材料根本没到手, 如熔炉
        // giveUp 弃炉), 若再直接调 getItemTask, 会命中共享活实例槽里那个僵尸实例
        // —— 框架按 newSub.isEqual(_sub) 判"子任务没变"、继续 tick 它, 而它放弃后
        // onTick 恒返回 null ⇒ 整条链零动作零日志空转 (20260929-192036 场次 4305s)。
        // 对策: 改用 newDetachedItemTask 绕开活实例槽, 强制换一个真新实例重试;
        // 连续 N 轮仍放弃则打告警, 把"上游卡死"暴露到日志而非静默。
        boolean previousGaveUp = _activeCollectTask != null && _activeCollectTask.hasAbandoned();
        if (previousGaveUp) {
            _consecutiveGiveUps++;
            if (_consecutiveGiveUps >= MAX_CONSECUTIVE_GIVEUPS) {
                // (20261113 铁律②出口·层2) 同一材料连续 MAX 轮以放弃结束 → 本生命周期
                // 不再派发该材料 (维度校验: 换维度自动失效恢复), 放行去收集其余材料。
                // 层1 (MineAndCollectTask 维度不可得 abandon) 之后若继续派发同一死材料,
                // 会"3 轮放弃一循环"无限打转 —— 必须在收集器层给死材料一个除名出口。
                String failedKey = _activeCollectMaterialKey;
                if (failedKey != null) {
                    _giveUpMaterials.put(failedKey, ItemHelper.currentDimensionId(mod));
                    Debug.logWarning("[CraftMaterials] 材料 '" + failedKey + "' 连续 " + _consecutiveGiveUps
                            + " 轮以放弃结束 -> 本生命周期跳过该材料 (dim=" + ItemHelper.currentDimensionId(mod)
                            + "; 换维度自动恢复重试)");
                } else {
                    Debug.logWarning("[CraftMaterials] 材料子任务连续 " + _consecutiveGiveUps
                            + " 轮以放弃结束 (派发来源未记录, 见 detail/25)");
                }
                _consecutiveGiveUps = 0;
            }
        } else {
            _consecutiveGiveUps = 0;
        }
        _activeCollectTask = null;

        // TODO: Cache this once instead of doing it every frame.

        // Stuff to get, both catalogued + individual items.
        HashMap<String, Integer> catalogueCount = new HashMap<>();
        HashMap<Item, Integer> itemCount = new HashMap<>();
        // (2026-11-12 家族槽组结算) 多重匹配槽分组: key=匹配集合(同一配方里同一家族
        // 的多个槽位共享), value=该组总需求(槽位数 × 合成重复数)。
        java.util.LinkedHashMap<List<Item>, Integer> multiMatchSlotNeeded = new java.util.LinkedHashMap<>();

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
                            // (2026-11-12 家族槽组结算) 多重匹配槽不再逐槽挑变体, 改为
                            // 按"匹配集合"分组、合成后再统一结算 (见下方 multiMatchSlotNeeded
                            // 循环)。旧实现 (2026-08-27) 逐槽独立挑"第一个有真实数量的变体"并
                            // 按该变体单独计数 → 20261112-183557 场次实锤: 背包深板岩圆石
                            // x43+黑石x1 做石镐, 三个家族槽逐槽都挑中黑石 x1 → 派
                            // MineAndCollect(blackstone x3) → 挖黑石需要镐 → 嵌套
                            // SatisfyMiningReq 又偏好石镐 → 任务树 depth=128 递归爆炸,
                            // 43 个深板岩圆石全程无人使用。
                            List<Item> familyKey = java.util.Arrays.asList(slot.getMatches());
                            multiMatchSlotNeeded.merge(familyKey, numberOfRepeats, Integer::sum);
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


        // (2026-11-12 家族槽组结算) 多重匹配槽统一结算: 同组槽位共享家族总量 —
        // 需求 = 组内槽位数 × 重复数, 持有 = 家族全部变体的真实背包+2x2 格合计。
        // 家族总量够 → 不派收集, 直接合成 (台格填充本就支持混填, 2026-08-25 熔炉
        // 先例); 不够 → 挑真实持有量最大的变体 (43 深板岩圆石不会被黑石 x1 抢走,
        // 全零时取匹配序第一个 = 0827"炉子即圆石"语义), 只补差额。
        for (Map.Entry<List<Item>, Integer> group : multiMatchSlotNeeded.entrySet()) {
            List<Item> matches = group.getKey();
            int needed = group.getValue();
            int haveTotal = 0;
            for (Item m : matches) {
                haveTotal += mod.getItemStorage().getItemCountReal(m) + StorageHelper.countItemsInCraftGrid(mod, m);
            }
            if (haveTotal >= needed) continue;
            // (20261113 维度可获得性过滤) 挑变体的池子先滤"本维度可获得": 黑石只在
            // 下界生成, 主世界持有黑石 x1 时旧口径挑它派 MineAndCollect(blackstone)
            // → 主世界物理上挖不到 → 死亡恢复链挂 13m36s "does NOT have RECIPE
            // MATERIALS" (1113-113440 实锤; 与 1112 "黑石x1 抢走 43 深板岩圆石"
            // 同一根)。本维度变体全空才退回全变体 (不挡配方本身, 异维度差额由
            // 上层失败出口/跨维度旅行兜底)。
            List<Item> pool = new java.util.ArrayList<>(matches.size());
            for (Item m : matches) {
                if (ItemHelper.isItemObtainableInCurrentDimension(mod, m)) pool.add(m);
            }
            if (pool.isEmpty()) pool = matches;
            Item pick = pool.get(0);
            int pickHave = -1;
            for (Item m : pool) {
                int c = mod.getItemStorage().getItemCountReal(m) + StorageHelper.countItemsInCraftGrid(mod, m);
                if (c > pickHave) {
                    pickHave = c;
                    pick = m;
                }
            }
            // 目标口径 = 挑中变体最终应持有 needed-(其他变体已有量); 下方通用检查
            // (real+grid < count) 会按此派收集任务补差额, 家族总量达标即停。
            int targetForPick = needed - haveTotal + pickHave;
            itemCount.put(pick, itemCount.getOrDefault(pick, 0) + targetForPick);
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
                    // (20261113 层2) 本生命周期已判"连续放弃达上限"的材料跳过 (维度校验)。
                    String curDim = ItemHelper.currentDimensionId(mod);
                    if (curDim.equals(_giveUpMaterials.get(catalogueMaterialName))) {
                        continue;
                    }
                    setDebugState("Getting " + itemTarget);
                    // (2026-09-24) detached: 独立实例, 不命中共享活实例槽。
                    _activeCollectTask = (_detached || previousGaveUp)
                            ? TaskCatalogue.newDetachedItemTask(catalogueMaterialName, count)
                            : TaskCatalogue.getItemTask(catalogueMaterialName, count);
                    _activeCollectMaterialKey = catalogueMaterialName;
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
                    // (20261113 层2) 同上, 非 catalogue 材料的跳过口径。
                    String curDim = ItemHelper.currentDimensionId(mod);
                    if (curDim.equals(_giveUpMaterials.get(item.toString()))) {
                        continue;
                    }
                    setDebugState("Getting " + item.getTranslationKey());
                    // (2026-09-24) detached: 独立实例, 不命中共享活实例槽。
                    _activeCollectTask = (_detached || previousGaveUp)
                            ? TaskCatalogue.newDetachedItemTask(item, count)
                            : TaskCatalogue.getItemTask(item, count);
                    _activeCollectMaterialKey = item.toString();
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
