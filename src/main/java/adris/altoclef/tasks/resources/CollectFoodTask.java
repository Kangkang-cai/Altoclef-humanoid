package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftGenericWithRecipeBooksTask;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.BiasedExploreTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class CollectFoodTask extends Task {

    // Actually screw fish baritone does NOT play nice underwater.
    // Fish kinda suck to harvest so heavily penalize them.
    private static final double FISH_PENALTY = 0 * 0.03;

    // Represents order of preferred mobs to least preferred
    private static final CookableFoodTarget[] COOKABLE_FOODS = new CookableFoodTarget[]{
            new CookableFoodTarget("beef", CowEntity.class),
            new CookableFoodTarget("porkchop", PigEntity.class),
            new CookableFoodTarget("mutton", SheepEntity.class),
            new CookableFoodTargetFish("salmon", SalmonEntity.class),
            new CookableFoodTarget("chicken", ChickenEntity.class),
            new CookableFoodTargetFish("cod", CodEntity.class),
            new CookableFoodTarget("rabbit", RabbitEntity.class)
    };

    private static final Item[] ITEMS_TO_PICK_UP = new Item[]{
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.GOLDEN_CARROT,
            Items.BREAD,
            Items.BAKED_POTATO
    };

    private static final CropTarget[] CROPS = new CropTarget[]{
            new CropTarget(Items.WHEAT, Blocks.WHEAT),
            new CropTarget(Items.CARROT, Blocks.CARROTS),
            new CropTarget(Items.POTATO, Blocks.POTATOES),
            new CropTarget(Items.BEETROOT, Blocks.BEETROOTS)
    };

    // Any of these counts as "armed" for hunting food animals.
    private static final Item[] SWORDS = new Item[]{
            Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
            Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD
    };

    private final double _unitsNeeded;
    private final TimerGame _checkNewOptionsTimer = new TimerGame(10);
    private SmeltInFurnaceTask _smeltTask = null;
    // 终端资源任务槽 (到达型语义): 挖掘/采集/交互/合成/猎杀/拾取 —— 到达(或目的失效)
    // 之前独占 tick, 不被"路边更近的同类目标"打断。打断它 = 白走 + baritone 路径作废
    // (09-13 的 25 轮 10s 横跳正是这种打断)。发现通道不走这个槽。
    private Task _currentResourceTask = null;
    // (2026-09-13 拆槽, 用户"随机探索的意义是发现目标, 不是到达那个随机选择的坐标")
    // 探索是【过程型/发现】任务, 不是终端采集任务。原实现把它塞进 _currentResourceTask
    // (与挖干草/收作物/杀羊同槽) → 入口早退在探索运行期间把每 tick 的发现扫描(地面掉落/
    // 干草/作物/动物/浆果) 全部跳过 → 探索独占 tick 直到自己结束; 而它构造时未传
    // biasSources → _hadBiasOnce 恒 false → _biasExhausted 永不置位 → 永不结束
    // → 路过目标一律不管。过程型语义要求"每 tick 交回上层重判、发现即让路", 故拆出
    // 独立槽位: 终端任务照旧独占 tick, 探索则每 tick 都要先让发现扫描过一遍。
    private Task _exploreTask = null;

    // (2026-09-13 释放阀) 终端槽持有任务的"目标物品"(仅转换分支的合成任务赋值)。
    // 10s 重评估用它查配方填充是否刚被停滞看门狗判负 → 判负则释放槽位。
    // 必要性: 「isActive()==true 不等于在推进」—— 转换分支把 bread/wheat 的
    // targetCount 传成 99999999("全转换"语义), 材料耗尽后任务永久 active 且
    // 永不 isFinished; L290 的保留判据会永远提前 return, 上层连"现在 wheat>=3
    // 该改做面包"或"没料可转该收尾 return null"都走不到。现场 20260913-155648
    // 干草块耗尽 + 背包 0 空位 → 58.7s 零推进到用户手动停。
    // 通用规则: 凡以 isActive() 做粘性保留判据, 必须再配一条"无进展/自报停滞"
    // 的释放条件 —— 此处复用合成看门狗的自我宣告, 不引入新的进度假设。
    private Item _currentResourceItem = null;

    // (2026-08-31 攒够一批再统熔炼 rev, 用户) 统一开烧的生肉阈值 = 目标 units 所需
    // 生肉估算数 × 该比例。原固定 4 块被用户否决; 改为按目标所需比例(默认 0.6, 可在
    // 0.5~0.7 之间调), 攒到该比例即统一开烧。收尾段(potential>=unitsNeeded)的全量烧保留。
    private static final double COOK_BATCH_RATIO = 0.6;

    // (2026-08-31 捡掉落优先于找下一只, 用户) 地面有食物掉落时"粘性"捡取的进行中任务:
    // 一旦决定捡就持续返回直到地面无目标掉落, 不再被 Biased exploration(找下一只)抢占。
    private Task _foodDropPickupTask = null;
    private static final double FOOD_DROP_PICKUP_RANGE = 24;

    // (2026-09-13 不再每 10s 拆任务, 用户"三层都修") _checkNewOptionsTimer 到期原
    // 无条件 _currentResourceTask = null → 正在推进(甚至正在长途接近)的采集/探索子任务
    // 每 10s 被丢弃重建 → baritone 路径反复作废, 且目标在 hay(50 格外, +x) 与
    // BiasedExploreTask(-x 平原) 之间交替 → 20260913-144922 15:09-15:13 共 25 轮
    // 10s 交替、零推进。到期改为只清"已失效"的任务; 子任务树进入 wander 兜底
    // (TimeoutWanderTask = AbstractDoToClosestObjectTask 的标准等待/找下一个目标)
    // 也不再瞬时否决, 给 6s 宽限让它自己恢复。
    private int _wanderSinceTick = -1;
    private static final int STICKY_WANDER_GRACE_TICKS = 120;

    public CollectFoodTask(double unitsNeeded) {
        _unitsNeeded = unitsNeeded;
    }

    /**
     * (2026-08-27 主动烤制) 有生食(肉优先, 其次薯)就烤, 返回烤制任务; 无则 null。
     * 不像旧版那样受 _unitsNeeded 阈值卡死 (早期食物不够时生薯/生肉从不烤)。
     * (2026-08-31 攒够一批再统熔炼 rev) 攒到 "目标 units 所需生肉估算数 ×
     * COOK_BATCH_RATIO" 才统一开炉烤, 低于此量只继续收集 — 不再"来一份生肉就烧一份"
     * (用户 13:00 日志实锤), 又不会等太久。最终收尾段(potential>=_unitsNeeded)的全量烧保留。
     */
    private Task tryCookRawFood(AltoClef mod) {
        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            int rawCount = StorageHelper.countItemsInInventoryReal(mod, cookable.getRaw());
            // 攒够"目标熟食 units 所需生肉数"的比例: cookedUnits=单块熟食回给 units。
            int rawNeeded = estimateRawNeeded(cookable.getCookedUnits());
            if (rawCount >= rawNeeded) {
                int toSmelt = rawCount + StorageHelper.countItemsInInventoryReal(mod, cookable.getCooked());
                Debug.logToFile("altoclef_diag.log",
                        "[FOOD-FIX]\t[BatchCook]\taccumulated " + rawCount + "/" + rawNeeded
                                + " raw " + cookable.getRaw() + " (needs " + cookable.getCookedUnits()
                                + "/pc, target " + (int) _unitsNeeded + " units, ratio=" + COOK_BATCH_RATIO
                                + ") -> batch-smelt together");
                _smeltTask = new SmeltInFurnaceTask(new SmeltTarget(
                        new ItemTarget(cookable.cookedFood, toSmelt),
                        new ItemTarget(cookable.rawFood, rawCount)));
                _smeltTask.ignoreMaterials();
                return _smeltTask;
            }
        }
        int rawPotato = StorageHelper.countItemsInInventoryReal(mod, Items.POTATO);
        // 生薯同款: 单块烤薯回给 units(Baked Potato 5)折算所需生薯比例。
        int bakedUnits = Items.BAKED_POTATO.getFoodComponent() != null
                ? Items.BAKED_POTATO.getFoodComponent().getHunger() : 5;
        int potatoNeeded = estimateRawNeeded(bakedUnits);
        if (rawPotato >= potatoNeeded) {
            int toBake = rawPotato + StorageHelper.countItemsInInventoryReal(mod, Items.BAKED_POTATO);
            _smeltTask = new SmeltInFurnaceTask(new SmeltTarget(
                    new ItemTarget(Items.BAKED_POTATO, toBake),
                    new ItemTarget(Items.POTATO, rawPotato)));
            _smeltTask.ignoreMaterials();
            setDebugState("Smelting raw potatoes");
            return _smeltTask;
        }
        return null;
    }

    /**
     * (2026-08-31) 估算本次攒到多少块生肉就统一开烧:
     * 目标熟食 units ÷ 单块熟食回给 units = 约需该种生肉块数, 再乘 COOK_BATCH_RATIO。
     * 取整后下限 2 块, 防退化回"1 块就烧一份"。
     */
    private int estimateRawNeeded(int cookedUnitsPerPiece) {
        int rawNeeded = (int) Math.ceil((_unitsNeeded / cookedUnitsPerPiece) * COOK_BATCH_RATIO);
        return Math.max(2, rawNeeded);
    }

    private static double getFoodPotential(ItemStack food) {
        if (food == null) return 0;
        int count = food.getCount();
        if (count <= 0) return 0;
        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            if (food.getItem() == cookable.getRaw()) {
                assert cookable.getCooked().getFoodComponent() != null;
                return count * cookable.getCooked().getFoodComponent().getHunger();
            }
        }
        // We're just an ordinary item.
        if (food.getItem().isFood()) {
            assert food.getItem().getFoodComponent() != null;
            return count * food.getItem().getFoodComponent().getHunger();
        }
        return 0;
    }

    // Gets the units of food if we were to convert all of our raw resources to food.
    @SuppressWarnings("RedundantCast")
    private static double calculateFoodPotential(AltoClef mod) {
        double potentialFood = 0;
        // (2026-08-25 真实背包兜底) 直接扫玩家背包, 不走 tracker 缓存 — 缓存
        // 陈旧会让食物潜力在 _unitsNeeded 阈值附近抖动 → "合成面包"↔"收小麦"
        // 反复横跳 (用户实测做面包时制作卡顿, 09:25 日志 Harvesting↔Crafting
        // Bread 互掐)。
        if (mod.getPlayer() != null && mod.getPlayer().getInventory() != null) {
            for (ItemStack food : mod.getPlayer().getInventory().main) {
                if (food == null || food.isEmpty()) continue;
                potentialFood += getFoodPotential(food);
            }
            ItemStack offhand = mod.getPlayer().getInventory().offHand.get(0);
            if (offhand != null && !offhand.isEmpty()) {
                potentialFood += getFoodPotential(offhand);
            }
        }
        // (2026-09-13 材料口径改含合成格) 同上: 配方书"批量填满"会把整堆小麦/干草块
        // 搬进合成格 → 纯背包口径骤降 → foodPotential 跌破 _unitsNeeded → 判"食物不够"
        // → 把正在跑的面包/小麦合成切走 → 材料退回 → 又够 → 反复横跳。本函数开头的
        // 注释(L174-177)记录过同类抖动("做面包时制作卡顿"), 口径必须与"合成中的材料
        // 仍算我们的"这一事实一致。
        int wheatCount = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.WHEAT);
        int hayCount = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.HAY_BLOCK);
        int potentialBread = (wheatCount / 3) + hayCount * 3;
        potentialFood += Objects.requireNonNull(Items.BREAD.getFoodComponent()).getHunger() * potentialBread;
        // Check smelting
        ScreenHandler screen = mod.getPlayer().currentScreenHandler;
        if (screen instanceof FurnaceScreenHandler) {
            potentialFood += getFoodPotential(StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS));
            potentialFood += getFoodPotential(StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT));
        }
        return potentialFood;
    }

    /**
     * (2026-09-13 转换任务停滞释放) foodMet 分支派出的 wheat/bread 合成任务用的是
     * 哨兵数量 99999999 (表达"把手上的料全转完"), 于是它的 isFinished 永不成立。
     * 原料一旦耗尽, 该任务既推不动也结束不了 —— 而且派发时传的是 collect=false
     * (料在手上, 合成任务不负责补料), 缺料也不会回退去收集 → 被 onTick 的粘性
     * return 无限 keep。
     *
     * 现场 20260913-155648 实锤: 17 个干草块被批量转成 146 个小麦后 (16:03:12),
     * 到手动停止 (16:04:11) 共 58.7s 零推进; 期间只有 [CRAFT-FILL-GIVEUP]
     * (15s 无进展 → 30s 冷却) 空转 + [FoodPick] 每 10s 判 "keep running sub task"。
     *
     * 转换任务的派发前提就是"手上还有可转化的原料" (见下方两处含合成格判据),
     * 所以原料没了即视为该任务已完成 —— 与"全转换"语义自洽, 且不需要精确份数
     * (残留退料会净损 1 份, 精确目标反而可能永远差 1 而换一种卡)。
     * 释放后 onTick 会重新决策: 小麦够 → 转面包; 两者都不够 → 落到 foodMet 结束路径。
     */
    private boolean conversionSourceExhausted(AltoClef mod, Task task) {
        RecipeTarget target = null;
        if (task instanceof CraftInInventoryTask craftInInventory) {
            target = craftInInventory.getRecipeTarget();
        } else if (task instanceof CraftInTableTask craftInTable && craftInTable.getRecipeTargets().length > 0) {
            target = craftInTable.getRecipeTargets()[0];
        }
        if (target == null) return false;
        if (target.getOutputItem() == Items.WHEAT) {
            return StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.HAY_BLOCK) < 1;
        }
        if (target.getOutputItem() == Items.BREAD) {
            return StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.WHEAT) < 3;
        }
        return false;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // Protect ALL food
        mod.getBehaviour().addProtectedItems(ITEMS_TO_PICK_UP);
        for (CropTarget crop : CROPS) {
            mod.getBlockTracker().trackBlock(crop.cropBlock);
        }

        // Allow us to consume food.
        /*
        for (CookableFoodTarget food : COOKABLE_FOODS)
            mod.getBehaviour().addProtectedItems(food.getRaw(), food.getCooked());
            mod.getBehaviour().addProtectedItems(crop.cropItem);
        }
         */
        mod.getBehaviour().addProtectedItems(Items.HAY_BLOCK, Items.SWEET_BERRIES);

        mod.getBlockTracker().trackBlock(Blocks.HAY_BLOCK);
        mod.getBlockTracker().trackBlock(Blocks.SWEET_BERRY_BUSH);
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-01 根因修复: "羊肉已入包仍捡/杀 + bed↔food 父任务横跳")
        // 每 tick 先判【真实背包食物潜力】是否已达目标: 已达标 → 跳过所有采集类
        // 分支(捡掉落/猎杀动物/收作物/游荡找食), 直接进入"收尾制作/烤制"段(面包/烤
        // 薯/烧肉 → 全做完 isFinished=true → 任务结束)。
        double potentialFood = calculateFoodPotential(mod);
        boolean foodMet = potentialFood >= _unitsNeeded;

        // If we were previously smelting, keep on smelting.
        if (_smeltTask != null && _smeltTask.isActive() && !_smeltTask.isFinished(mod)) {
            // TODO: If we don't have cooking materials, cancel.
            setDebugState("Cooking...");
            return _smeltTask;
        }

        // (2026-08-27 主动烤制) 有生肉/生薯且有熔炉就主动去烤, 不必等存够
        // _unitsNeeded 才算。旧版烤制放在 potentialFood>=_unitsNeeded 分支内,
        // 早期食物远不够时生薯/生肉永远不烤 (用户实测: 背包有薯却不烤)。烤熟后
        // 每单位食物占用更少格子, 对攒足食物有帮助。
        Task cookTask = tryCookRawFood(mod);
        if (cookTask != null) {
            return cookTask;
        }

        if (_checkNewOptionsTimer.elapsed()) {
            // Try a new resource task
            _checkNewOptionsTimer.reset();
            // (2026-09-13) 只在当前资源任务已不可用时清空: 原代码无条件置 null,
            // 会把正在长途接近的子任务每 10s 拆一次(见字段注释)。
            // (2026-09-13 释放阀) 「不可用」还必须包含"自我宣告停滞": 转换分支的
            // 合成任务(99999999 哨兵目标)材料耗尽后永久 active 且永不 isFinished,
            // 只看 isActive/isFinished 会把它无限 keep(L290 提前 return, 上层走不到
            // 面包分支也走不到收尾 return null)。合成看门狗已给出停滞信号, 直接复用。
            boolean resourceStalled = _currentResourceItem != null
                    && CraftGenericWithRecipeBooksTask.isFillRecentlyStalled(_currentResourceItem);
            if (_currentResourceTask == null || !_currentResourceTask.isActive()
                    || _currentResourceTask.isFinished(mod) || resourceStalled) {
                if (resourceStalled) {
                    Debug.logToFile("altoclef_diag.log",
                            "[FoodPick] 10s re-eval: RELEASE stalled sub task "
                                    + _currentResourceTask + " (fill gave up on "
                                    + _currentResourceItem + ")");
                }
                _currentResourceTask = null;
                _currentResourceItem = null;
                _wanderSinceTick = -1;
            } else {
                Debug.logToFile("altoclef_diag.log",
                        "[FoodPick] 10s re-eval: keep running sub task " + _currentResourceTask);
            }
        }

        // (2026-09-13 停滞释放) 见 conversionSourceExhausted 注释: 转换任务(哨兵目标)
        // 在原料耗尽后不可能完成, 必须在下面的粘性 return 之前放行, 否则食物任务
        // 永久卡死。判据放在这里而不是 10s 重评估里, 是为了无延迟释放 (重评估最坏
        // 要等 10s 才轮到, 期间仍在空转)。
        if (_currentResourceTask != null && conversionSourceExhausted(mod, _currentResourceTask)) {
            Debug.logToFile("altoclef_diag.log",
                    "[FoodPick] conversion source exhausted, releasing sub task " + _currentResourceTask);
            _currentResourceTask = null;
            _wanderSinceTick = -1;
        }

        if (_currentResourceTask != null && _currentResourceTask.isActive() && !_currentResourceTask.isFinished(mod)) {
            if (!_currentResourceTask.thisOrChildAreTimedOut()) {
                _wanderSinceTick = -1;
                return _currentResourceTask;
            }
            // (2026-09-13 wander 宽限) 子任务树里出现 TimeoutWanderTask 只说明它暂时
            // 没目标(等待/流浪兜底), 不是"这个选项失败"。给宽限期, 超时仍不收敛才放弃。
            if (_wanderSinceTick < 0) {
                _wanderSinceTick = WorldHelper.getTicks();
                Debug.logToFile("altoclef_diag.log",
                        "[FoodPick] sub task entered wander, grace " + STICKY_WANDER_GRACE_TICKS
                                + " ticks before re-picking: " + _currentResourceTask);
            }
            if (WorldHelper.getTicks() - _wanderSinceTick < STICKY_WANDER_GRACE_TICKS) {
                return _currentResourceTask;
            }
            Debug.logToFile("altoclef_diag.log",
                    "[FoodPick] wander grace expired, re-picking (was " + _currentResourceTask + ")");
            _wanderSinceTick = -1;
        }

        // (2026-08-31 捡掉落优先于找下一只, 用户) 地面有食物掉落就优先去捡(粘性),
        // 而不是去 Biased exploration 找下一只 — 否则 "Pickup Dropped 刚启动就被
        // Searching for mob 抢占, 肉没捡走" (用户 13:01: mutton / white_wool 同构)。
        // (2026-09-01 回退: 用户"羊毛已入背包仍反复拾取, 修复失效") 移到达标判定之后
        // 反而观察不到改善 (羊毛经 CollectWool, 非本任务; 且会延迟地面拾取)。恢复原顺序:
        // 地面有食物掉落就优先捡(粘性), 不依赖缓存判定。
        // 已达标时跳过地面食物拾取, 把执行权让给收尾烤制或 isFinished 完成。
        Task dropPickup = foodMet ? null : tryPickupDroppedFood(mod);
        if (dropPickup != null) {
            return dropPickup;
        }

        if (foodMet) {
            // Convert our raw foods
            // PLAN:
            // - If we have hay/wheat, make it into bread
            // - If we have raw foods, smelt all of them

            // Convert Hay+Wheat -> Bread
            // (2026-09-13 材料口径改含合成格) 原先用纯背包口径 countItemsInInventoryReal。
            // 配方书"批量填满"会把整堆小麦/干草块搬进合成格(3x3 台格或 2x2 格), 纯背包
            // 口径随即归 0 → 本分支判"没小麦"→ 切走正在跑的面包/小麦合成 → 台格材料
            // 退回背包 → 计数又 >=3 → 再做 → 死循环。与 344bd043(做床时羊毛进 3x3 格
            // 判缺料翻面 ~15 轮) 同一模式。改含合成格口径(真实背包+护甲+副手+光标+
            // 2x2 格+3x3 台格), 语义 = "手上可用于合成的材料有多少"。
            if (StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.WHEAT) >= 3) {
                setDebugState("Crafting Bread");
                Item[] w = new Item[]{Items.WHEAT};
                Item[] o = null;
                // jank
                _currentResourceItem = Items.BREAD;
                _currentResourceTask = new CraftInTableTask(new RecipeTarget(Items.BREAD, 99999999, CraftingRecipe.newShapedRecipe("bread", new Item[][]{w, w, w, o, o, o, o, o, o}, 1)), false, false);
                return _currentResourceTask;
            }
            if (StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.HAY_BLOCK) >= 1) {
                setDebugState("Crafting Wheat");
                Item[] o = null;
                _currentResourceItem = Items.WHEAT;
                _currentResourceTask = new CraftInInventoryTask(new RecipeTarget(Items.WHEAT, 99999999, CraftingRecipe.newShapedRecipe("wheat", new Item[][]{new Item[]{Items.HAY_BLOCK}, o, o, o}, 9)), false, false);
                return _currentResourceTask;
            }
            // Convert raw foods -> cooked foods

            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                int rawCount = StorageHelper.countItemsInInventoryReal(mod, cookable.getRaw());
                if (rawCount > 0) {
                    //Debug.logMessage("STARTING COOK OF " + cookable.getRaw().getTranslationKey());
                    int toSmelt = rawCount + StorageHelper.countItemsInInventoryReal(mod, cookable.getCooked());
                    _smeltTask = new SmeltInFurnaceTask(new SmeltTarget(new ItemTarget(cookable.cookedFood, toSmelt), new ItemTarget(cookable.rawFood, rawCount)));
                    _smeltTask.ignoreMaterials();
                    return _smeltTask;
                }
            }

            // (2026-08-27 烤马铃薯, 用户) COOKABLE_FOODS 只含肉/鱼, 采到的
            // 生马铃薯(2 饥饿)只会被直接生吃, 从不放进熔炉烤成熟马铃薯
            // (Baked Potato, 5 饥饿)。有薯就烤: 烤当前全部 + 已有烤薯,
            // 烧完这批即停 (与上面的肉烤制同款封装)。
            int rawPotato = StorageHelper.countItemsInInventoryReal(mod, Items.POTATO);
            if (rawPotato > 0) {
                int toBake = rawPotato + StorageHelper.countItemsInInventoryReal(mod, Items.BAKED_POTATO);
                _smeltTask = new SmeltInFurnaceTask(new SmeltTarget(
                        new ItemTarget(Items.BAKED_POTATO, toBake),
                        new ItemTarget(Items.POTATO, rawPotato)));
                _smeltTask.ignoreMaterials();
                setDebugState("Smelting raw potatoes");
                return _smeltTask;
            }
        } else if (!foodMet) {
            // (2026-09-13 释放阀不变量) 本分支写的都是导航/交互/猎杀型终端任务, 不是
            // "转换合成"任务 → 目标物品清零, 防止上一轮转换分支残留的 _currentResourceItem
            // 在 10s 重评估时把这里正在推进的导航任务误判成"填充停滞"而释放。
            _currentResourceItem = null;
            // Pick up food items from ground
            for (Item item : ITEMS_TO_PICK_UP) {
                Task t = this.pickupTaskOrNull(mod, item);
                if (t != null) {
                    setDebugState("Picking up Food: " + item.getTranslationKey());
                    _currentResourceTask = t;
                    return _currentResourceTask;
                }
            }
            // Pick up raw/cooked foods on ground
            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                Task t = this.pickupTaskOrNull(mod, cookable.getRaw(), 20);
                if (t == null) t = this.pickupTaskOrNull(mod, cookable.getCooked(), 40);
                if (t != null) {
                    setDebugState("Picking up Cookable food");
                    _currentResourceTask = t;
                    return _currentResourceTask;
                }
            }
            // Hay
            Task hayTask = this.pickupBlockTaskOrNull(mod, Blocks.HAY_BLOCK, Items.HAY_BLOCK, 300);
            if (hayTask != null) {
                setDebugState("Collecting Hay");
                _currentResourceTask = hayTask;
                return _currentResourceTask;
            }
            // Crops
            for (CropTarget target : CROPS) {
                // If crops are nearby. Do not replant cause we don't care.
                Task t = pickupBlockTaskOrNull(mod, target.cropBlock, target.cropItem, (blockPos -> {
                    BlockState s = mod.getWorld().getBlockState(blockPos);
                    Block b = s.getBlock();
                    if (b instanceof CropBlock) {
                        boolean isWheat = !(b instanceof PotatoesBlock || b instanceof CarrotsBlock || b instanceof BeetrootsBlock);
                        if (isWheat) {
                            // Chunk needs to be loaded for wheat maturity to be checked.
                            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                                return false;
                            }
                            // Prune if we're not mature/fully grown wheat.
                            CropBlock crop = (CropBlock) b;
                            return crop.isMature(s);
                        }
                    }
                    // Unbreakable.
                    return WorldHelper.canBreak(mod, blockPos);
                    // We're not wheat so do NOT reject.
                }), 100);
                if (t != null) {
                    setDebugState("Harvesting " + target.cropItem.getTranslationKey());
                    _currentResourceTask = t;
                    return _currentResourceTask;
                }
            }
            // Cooked foods
            double bestScore = 0;
            Entity bestEntity = null;
            Item bestRawFood = null;
            for (CookableFoodTarget cookable : COOKABLE_FOODS) {
                if (!mod.getEntityTracker().entityFound(cookable.mobToKill)) continue;
                Optional<Entity> nearest = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), cookable.mobToKill);
                if (nearest.isEmpty()) continue; // ?? This crashed once?
                if (nearest.get() instanceof LivingEntity livingEntity) {
                    // Peta
                    if (livingEntity.isBaby()) continue;
                }
                // (2026-09-03 用户"收集食物被导航到海水里去") 目标食材(鱼/泡在水里的
                // 动物)若落在海洋 biome → 跳过, 不去海里收集(会溺水/低效)。与既有探索
                // 落水限制同一套判定(WorldHelper.isOcean, 海岸拒绝/河湖放行), 口径一致。
                // 鱼本身已用 FISH_PENALTY=0 排除, 这里兜底拦住泡在海里的陆生动物。
                if (WorldHelper.isOcean(mod.getWorld().getBiome(nearest.get().getBlockPos()))) continue;
                int hungerPerformance = cookable.getCookedUnits();
                double sqDistance = nearest.get().squaredDistanceTo(mod.getPlayer());
                double score = (double) 100 * hungerPerformance / (sqDistance);
                if (cookable.isFish()) {
                    score *= FISH_PENALTY;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestEntity = nearest.get();
                    bestRawFood = cookable.getRaw();
                }
            }
            if (bestEntity != null) {
                // Hunting with bare hands is painfully slow — craft a stone sword
                // first if we don't carry any weapon yet.
                if (!mod.getItemStorage().hasItem(SWORDS)) {
                    setDebugState("Crafting a stone sword before hunting");
                    _currentResourceTask = TaskCatalogue.getItemTask(Items.STONE_SWORD, 1);
                    return _currentResourceTask;
                }
                setDebugState("Killing " + bestEntity.getType().getTranslationKey());
                _currentResourceTask = killTaskOrNull(mod, bestEntity, bestRawFood);
                return _currentResourceTask;
            }

            // Sweet berries (separate from crops because they should have a lower priority than everything else cause they suck)
            Task berryPickup = pickupBlockTaskOrNull(mod, Blocks.SWEET_BERRY_BUSH, Items.SWEET_BERRIES, 100);
            if (berryPickup != null) {
                setDebugState("Getting sweet berries (no better foods are present)");
                _currentResourceTask = berryPickup;
                return _currentResourceTask;
            }
        }

        // 已达标: 不再游荡找食, isFinished 下一 tick 自然完成。
        if (foodMet) return null;

        // Look for food.
        setDebugState("Searching...");
        // (2026-09-03 用户"收集食物又被导航到水里", 海问题第2次复发) 旧逻辑
        // TimeoutWanderTask(30) 走 Baritone 原生随机探索(ExploreProcess), 落点
        // 可能在海洋 → 直接一路游进大海(用户实测游荡下水)。改用
        // BiasedExploreTask(foodExplore=true): 它自带海洋落点拒绝(isTargetOcean,
        // 过河放行) + 平原生物群系偏置(动物/作物更集中, 找食物更快), 不再往
        // 海洋方向探索。结合上方"跳过海洋 biome 的猎杀目标"双管齐下。
        // (2026-09-03 修复"走一格就换线找食物", 用户) 此处必须粘性复用探索实例:
        // BiasedExploreTask.isEqual 是 identity(other==this), 若直接 return new,
        // 每 tick 都是新实例 → 框架判定不同 → 每 tick 重启、onStart 重置 _heading
        // 为随机 → 每走一格就换方向, 永远走不出去找食物(日志 09:53 Biased
        // exploration 每 tick STOP/START)。
        // (2026-09-13 拆槽) 粘性复用改为存进独立槽 _exploreTask: 它不参与入口
        // (_currentResourceTask != null ... return) 的"终端任务独占 tick"早退,
        // 因此每 tick 都会先跑完发现扫描(掉落/干草/作物/动物/浆果), 只有全部扫不到
        // 才回落到这里继续探索 —— 过程型语义: 成功 = 发现目标(不是到达那个随机坐标),
        // 目的达成即让路。这是 09-13 "路过目标不管"的直接修复。
        // 复用同一实例仍是硬要求(isEqual 是 identity): 每 tick new 会重启 onStart
        // 把 _heading 重置为随机 → 每走一格换方向。仅当上一实例已结束(失败 10 次)
        // 才换新实例 —— 换新会让框架真正重启它。
        if (_exploreTask == null || _exploreTask.isFinished(mod)) {
            _exploreTask = new BiasedExploreTask(true);
        }
        return _exploreTask;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.HAY_BLOCK);
        mod.getBlockTracker().stopTracking(Blocks.SWEET_BERRY_BUSH);
        for (CropTarget crop : CROPS) {
            mod.getBlockTracker().stopTracking(crop.cropBlock);
        }
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-01 用户: 羊肉已入包却仍在拾取/猎杀) 完成判定改用和 onTick 决策
        // 一致的【真实背包】食物潜力 calculateFoodPotential, 不再用
        // calculateInventoryFoodScore(tracker 缓存)。tracker 缓存陈旧会把
        // isFinished 永远卡在 false → 任务不完成 → 即使羊肉已在背包仍持续捡取/
        // 杀羊, 并高频重建拾取任务(altoclef_diag.log [PickupFirst] 1 秒 16 次实锤)。
        // 与项目"计数必须用真实背包, 杜绝 tracker 缓存误判"的原则一致。
        return calculateFoodPotential(mod) >= _unitsNeeded;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectFoodTask task) {
            return task._unitsNeeded == _unitsNeeded;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Collect " + _unitsNeeded + " units of food.";
    }

    /**
     * Returns a task that mines a block and picks up its output.
     * Returns null if task cannot reasonably run.
     */
    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, Predicate<BlockPos> accept, double maxRange) {
        Predicate<BlockPos> acceptPlus = (blockPos) -> {
            if (!WorldHelper.canBreak(mod, blockPos)) return false;
            return accept.test(blockPos);
        };
        Optional<BlockPos> nearestBlock = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), acceptPlus, blockToCheck);

        if (nearestBlock.isPresent() && !nearestBlock.get().isWithinDistance(mod.getPlayer().getPos(), maxRange)) {
            nearestBlock = Optional.empty();
        }

        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), itemToGrab);
        }
        boolean spotted = nearestBlock.isPresent() || nearestDrop.isPresent();
        // Collect hay until we have enough.
        if (spotted) {
            if (nearestDrop.isPresent()) {
                return new PickupDroppedItemTask(itemToGrab, Integer.MAX_VALUE);
            } else {
                return new DoToClosestBlockTask(DestroyBlockTask::new, acceptPlus, blockToCheck);
            }
        }
        return null;
    }

    private Task pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, double maxRange) {
        return pickupBlockTaskOrNull(mod, blockToCheck, itemToGrab, toAccept -> true, maxRange);
    }

    private Task killTaskOrNull(AltoClef mod, Entity entity, Item itemToGrab) {
        return new KillAndLootTask(entity.getClass(), new ItemTarget(itemToGrab, 1));
    }

    /**
     * (2026-08-31 捡掉落优先于找下一只) 地面有可食用的掉落物(生/熟肉 + 常见熟食)
     * 且在一定范围内时, 返回一个"粘性"捡取任务。粘性: 派发后持续返回同一实例直到
     * 地面无目标掉落, 不被 Biased exploration(找下一只) / 超时探索半路切走 —
     * 修复用户 13:01 实测: Pickup Dropped 刚启动 50ms 就被 "Searching for mob"
     * 抢占, 肉没捡走就去杀下一只。
     */
    private Task tryPickupDroppedFood(AltoClef mod) {
        if (_foodDropPickupTask != null) {
            // 粘性: 正在捡就继续, 直到捡完 (isFinished = 地面无匹配掉落)。
            if (_foodDropPickupTask.isActive() && !_foodDropPickupTask.isFinished(mod)) {
                return _foodDropPickupTask;
            }
            _foodDropPickupTask = null;
        }
        List<Item> edibleDrops = new ArrayList<>();
        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            edibleDrops.add(cookable.getRaw());
            edibleDrops.add(cookable.getCooked());
        }
        edibleDrops.addAll(Arrays.asList(ITEMS_TO_PICK_UP));
        Vec3d playerPos = mod.getPlayer().getPos();
        ItemEntity best = null;
        double bestSq = FOOD_DROP_PICKUP_RANGE * FOOD_DROP_PICKUP_RANGE;
        for (ItemEntity drop : mod.getEntityTracker().getDroppedItems()) {
            if (drop == null || !drop.isAlive()) continue;
            if (!edibleDrops.contains(drop.getStack().getItem())) continue;
            double sq = drop.squaredDistanceTo(playerPos);
            if (sq < bestSq) {
                bestSq = sq;
                best = drop;
            }
        }
        if (best != null) {
            _foodDropPickupTask = new PickupDroppedItemTask(new ItemTarget(best.getStack().getItem()), true);
            setDebugState("Picking up food drops (priority over hunting)");
            Debug.logToFile("altoclef_diag.log",
                    "[FOOD-FIX]\t[PickupFirst]\tground food drop " + best.getStack().getItem()
                            + " within " + FOOD_DROP_PICKUP_RANGE + " -> sticky pickup before finding next mob");
            return _foodDropPickupTask;
        }
        return null;
    }

    /**
     * Returns a task that picks up a dropped item.
     * Returns null if task cannot reasonably run.
     */
    private Task pickupTaskOrNull(AltoClef mod, Item itemToGrab, double maxRange) {
        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), itemToGrab);
        }
        if (nearestDrop.isPresent()) {
            if (nearestDrop.get().isInRange(mod.getPlayer(), maxRange)) {
                return new PickupDroppedItemTask(new ItemTarget(itemToGrab), true);
            }
            //return new GetToBlockTask(nearestDrop.getBlockPos(), false);
        }
        return null;
    }

    private Task pickupTaskOrNull(AltoClef mod, Item itemToGrab) {
        return pickupTaskOrNull(mod, itemToGrab, Double.POSITIVE_INFINITY);
    }

    @SuppressWarnings("rawtypes")
    private static class CookableFoodTarget {
        public String rawFood;
        public String cookedFood;
        public Class mobToKill;

        public CookableFoodTarget(String rawFood, String cookedFood, Class mobToKill) {
            this.rawFood = rawFood;
            this.cookedFood = cookedFood;
            this.mobToKill = mobToKill;
        }

        public CookableFoodTarget(String rawFood, Class mobToKill) {
            this(rawFood, "cooked_" + rawFood, mobToKill);
        }

        private Item getRaw() {
            return Objects.requireNonNull(TaskCatalogue.getItemMatches(rawFood))[0];
        }

        private Item getCooked() {
            return Objects.requireNonNull(TaskCatalogue.getItemMatches(cookedFood))[0];
        }

        public int getCookedUnits() {
            assert getCooked().getFoodComponent() != null;
            return getCooked().getFoodComponent().getHunger();
        }

        public boolean isFish() {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    private static class CookableFoodTargetFish extends CookableFoodTarget {

        public CookableFoodTargetFish(String rawFood, String cookedFood, Class mobToKill) {
            super(rawFood, cookedFood, mobToKill);
        }

        public CookableFoodTargetFish(String rawFood, Class mobToKill) {
            super(rawFood, mobToKill);
        }

        @Override
        public boolean isFish() {
            return true;
        }
    }

    private static class CropTarget {
        public Item cropItem;
        public Block cropBlock;

        public CropTarget(Item cropItem, Block cropBlock) {
            this.cropItem = cropItem;
            this.cropBlock = cropBlock;
        }
    }

}
