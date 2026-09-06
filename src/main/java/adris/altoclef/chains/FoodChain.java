package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Settings;
import adris.altoclef.chains.FoodChain.FoodChainConfig;
import adris.altoclef.tasks.misc.EatItemTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Pair;

import java.util.Objects;
import java.util.Optional;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class FoodChain extends SingleTaskChain {

    private static FoodChainConfig _config;
    static {
        ConfigHelper.loadConfig("configs/food_chain_settings.json", FoodChainConfig::new, FoodChainConfig.class, newConfig -> _config = newConfig);
    }

    private boolean _isTryingToEat = false;
    private boolean _requestFillup = false;
    private boolean _needsFood = false;

    private int _cachedFoodScore;
    private Optional<Item> _cachedPerfectFood = Optional.empty();

    // ---- [FOOD-DIAG] (2026-09-06 战后不进食排查, 只读诊断) ----
    // FoodChain 原先零诊断日志, "为什么不吃" 完全不可见。0.5s 节流打印
    // 决策现场: 饥饿/血量/各闸门状态/选中食物/动作。
    private long _lastDiagLogMs = 0;
    private static final long FOOD_DIAG_INTERVAL_MS = 500;
    // 最近一次 needsToEat() 结果 (getPriority 主流程写入, 诊断用)。
    private boolean _lastNeedsToEat = false;

    private void logFoodDiag(AltoClef mod, String action, String blocked) {
        long now = System.currentTimeMillis();
        if (now - _lastDiagLogMs < FOOD_DIAG_INTERVAL_MS) return;
        _lastDiagLogMs = now;
        try {
            int hunger = mod.getPlayer().getHungerManager().getFoodLevel();
            float health = mod.getPlayer().getHealth();
            String m = "[FOOD-DIAG] hunger=" + hunger + " health=" + health
                    + " autoEat=" + mod.getModSettings().isAutoEat()
                    + " acro=" + mod.getMobDefenseChain().isDoingAcrobatics()
                    + " combat=" + mod.getMobDefenseChain().isInCombat()
                    + " falling=" + mod.getMLGBucketChain().isFallingOhNo(mod)
                    + " needEat=" + _lastNeedsToEat
                    + " hasFood=" + _cachedFoodScore
                    + " bestFood=" + (_cachedPerfectFood.isPresent()
                            ? _cachedPerfectFood.get().toString() : "none")
                    + " action=" + action
                    + (blocked == null || blocked.isEmpty() ? "" : " blocked=" + blocked);
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Exception ignored) { /* 诊断日志绝不影响主逻辑 */ }
    }

    public FoodChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority(AltoClef mod) {

        if (!AltoClef.inGame()) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }

        if (!mod.getModSettings().isAutoEat()) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }

        // do NOT eat while in lava if we are escaping it (spaghetti code dependencies go brrrr)
        if (mod.getPlayer().isInLava()) {
            stopEat(mod);
            return Float.NEGATIVE_INFINITY;
        }

        /*
        - Eats if:
        - We're hungry and have food that fits
            - We're low on health and maybe a little bit hungry
            - We're very low on health and are even slightly hungry
        - We're kind of hungry and have food that fits perfectly
         */

        // We're in danger, don't eat now!!
        if (mod.getMobDefenseChain().isDoingAcrobatics() || mod.getMLGBucketChain().isFallingOhNo(mod)) {
            stopEat(mod);
            logFoodDiag(mod, "idle", "acrobatics-or-falling");
            return Float.NEGATIVE_INFINITY;
        }

        // (2026-08-30 战斗中举盾不吃东西, 用户) 正在打斗(尤其举盾 vs 骷髅/苦力怕)
        // 时别自动吃—— 吃东西和举盾都是右键, 会互相抢 (会是顶走去吃 → 盾放下不
        // 格挡 → 挨射), 且战斗中进食等于站桩挨打。让战斗优先, 战斗结束再吃。
        if (mod.getMobDefenseChain().isInCombat()) {
            stopEat(mod);
            logFoodDiag(mod, "idle", "in-combat");
            return Float.NEGATIVE_INFINITY;
        }

        Pair<Integer, Optional<Item>> calculation = calculateFood(mod);
        _cachedFoodScore = calculation.getLeft();
        _cachedPerfectFood = calculation.getRight();

        boolean hasFood = _cachedFoodScore > 0;
        boolean needEat = needsToEat(mod);
        _lastNeedsToEat = needEat;

        // If we requested a fillup but we're full, stop.
        if (_requestFillup && mod.getPlayer().getHungerManager().getFoodLevel() == 20) {
            _requestFillup = false;
        }
        // If we no longer have food, we no longer can eat.
        if (!hasFood) {
            _requestFillup = false;
        }

        if (hasFood && (needEat || _requestFillup) && _cachedPerfectFood.isPresent() && !mod.getMLGBucketChain().isChorusFruiting()) {
            // Make sure we're not facing a container
            if (!LookHelper.tryAvoidingInteractable(mod)) {
                logFoodDiag(mod, "wait", "facing-interactable");
                return Float.NEGATIVE_INFINITY;
            }
            if (needEat) {
                // (2026-09-06 战后前台进食修复, 用户"打完架血量低不恢复, 继续挖铁")
                // 真饿/低血时不再走异步后台进食 —— 异步右键会被获胜任务链 tick 里的
                // 装备/举盾/放方块等动作静默抢断 (MineAndCollectTask 单物品 forceEquipItem
                // 重载无 isTryingToEat 闸门), 且 FoodChain 原先零诊断日志, 出问题完全
                // 不可见 (用户实测: 血量 2、饥饿 11~17、背包 129 食物, 战后无任何进食
                // 动作直接挖铁)。改为前台接管: bot 站定吃完再回去干活。优先级
                // eatForegroundPriority(默认 62) 高于用户任务(50), 低于逃跑/冷却(70)、
                // 自信战斗(65 之下不冲突——有怪时战斗优先), MLG(100)/加急战斗(95) 可随时
                // 抢占保命, 抢占时 EatItemTask.onStop 释放右键。
                stopEat(mod);
                setTask(new EatItemTask(_config.eatTaskTimeoutSeconds));
                logFoodDiag(mod, "foreground-eat", "");
                return _config.eatForegroundPriority;
            }
            startEat(mod, _cachedPerfectFood.get());
            logFoodDiag(mod, "async-topup", "");
        } else {
            stopEat(mod);
            logFoodDiag(mod, "idle", hasFood ? (needEat ? "no-fit-food" : "not-needed") : "no-food");
        }

        Settings settings = mod.getModSettings();

        // IMPORTANT: the "should we collect food" decision MUST use the same
        // score function as CollectFoodTask.isFinished (which calls
        // StorageHelper.calculateInventoryFoodScore). The old code compared
        // _cachedFoodScore from calculateFood(), which EXCLUDES protected
        // stacks and spider eyes. A backpack full of protected food (bread /
        // golden apples — items CollectFoodTask itself marks protected!)
        // scored 0 here but >= threshold in isFinished: the chain preempted
        // (55 > user task 50), the task finished instantly without ticking,
        // ownership bounced back, and the bot ping-ponged between "sleep in
        // bed" and "collect food" every tick while doing nothing.
        int inventoryFoodScore = StorageHelper.calculateInventoryFoodScore(mod);
        if (_needsFood || _cachedFoodScore < settings.getMinimumFoodAllowed()) {
            _needsFood = inventoryFoodScore < settings.getFoodUnitsToCollect();

            // Only collect if we don't have enough food.
            // If the user inputs invalid settings, the bot would get stuck here.
            if (inventoryFoodScore < settings.getFoodUnitsToCollect()) {
                setTask(new CollectFoodTask(settings.getFoodUnitsToCollect()));
                return 55f;
            }
        }


        // Food eating is handled asynchronously.
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Nothing.
    }

    private void startEat(AltoClef mod, Item food) {
        //Debug.logInternal("EATING " + toUse.getTranslationKey() + " : " + test);
        _isTryingToEat = true;
        _requestFillup = true;
        // (2026-08-23 吃不上饭修复) 副手持盾时右键被盾抢走, 先换盾再进食。
        tryClearShieldFromOffhand(mod);
        // (2026-09-03 背包满举盾卡死修复, 用户"要吃东西结果举盾卡住不动") 换盾
        // 后【立即验证】副手是否真的不再是盾: 若仍是盾(背包满无空槽 → 没换成;
        // 或 SWAP 网络包还没生效), 本 tick 绝不 hold 右键 —
        // 否则右键 = 举盾, 而 interactionPaused 又把 baritone 导航暂停 → 原地
        // 举盾卡死 (用户实锤: 背包满 + 副手盾 + 主手已装食物 → 持续举盾不动)。
        // 放弃本次进食, 等背包腾出空位/下 tick 重试, 最多延迟 1 tick 不卡死。
        if (mod.getPlayer().getOffHandStack().getItem() == Items.SHIELD) {
            stopEat(mod);
            return;
        }
        mod.getSlotHandler().forceEquipItem(new Item[]{food}, true); //"true" because it's food
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
    }

    /**
     * (2026-09-06 从 startEat 提取) 副手持盾时把盾换到背包空槽, 空出副手好进食。
     *
     * (2026-09-03 2x2合成格污染根因, 用户"背包格东西被塞进合成格") 给副手盾找
     * 放位的空槽绝不能用 getSlotsWithItemPlayerInventory(true, AIR) —— 该查询走
     * tracker, 而 PlayerScreenHandler 下(无 GUI 时 MC 默认 handler 也是它)合成
     * 输出(window0)+2x2合成格(window1-4)被 isSlotInPlayerInventory 归入"玩家背包
     * 槽"。背包真满时唯一搜到的"空槽"就是合成格 → SWAP 点合成格 → 背包物品被
     * 换进 2x2 格。只认真背包 inventory 0..35 (getRealBackpackSlots,
     * 任意界面均天然排除合成格/输出槽/护甲/副手/光标); 无真背包装空槽则不换。
     *
     * @return true = 副手已不是盾 (没盾 / 换盾成功); false = 仍持盾 (背包满等)。
     */
    public static boolean tryClearShieldFromOffhand(AltoClef mod) {
        if (mod.getPlayer().getOffHandStack().getItem() != Items.SHIELD) {
            return true;
        }
        adris.altoclef.util.slots.Slot emptySlot = adris.altoclef.util.helpers.StorageHelper.getRealBackpackSlots().stream()
                .filter(s -> adris.altoclef.util.helpers.StorageHelper.getItemStackInSlot(s).isEmpty())
                .findFirst().orElse(null);
        if (emptySlot != null) {
            mod.getSlotHandler().clickSlot(emptySlot,
                    adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT.getInventorySlot(),
                    net.minecraft.screen.slot.SlotActionType.SWAP);
        }
        return mod.getPlayer().getOffHandStack().getItem() != Items.SHIELD;
    }

    /**
     * (2026-09-06 前台进食) 当前背包里"最优可吃食物" (排除受保护物品与蜘蛛眼,
     * 按恢复量优先排序)。空 = 没有可吃的。
     */
    public Optional<Item> getBestFoodToEat(AltoClef mod) {
        return calculateFood(mod).getRight();
    }

    private void stopEat(AltoClef mod) {
        if (_isTryingToEat) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _isTryingToEat = false;
            _requestFillup = false;
        }
    }

    public boolean needsToEat(AltoClef mod) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        assert player != null;
        int foodLevel = player.getHungerManager().getFoodLevel();
        float health = player.getHealth();

        //Debug.logMessage("FOOD: " + foodLevel + " -- HEALTH: " + health);
        if (foodLevel >= 20) {
            // We can't eat.
            return false;
        } else {
            // Eat if we're desparate/need to heal ASAP
            if (player.isOnFire() || player.hasStatusEffect(StatusEffects.WITHER) || health < _config.alwaysEatWhenWitherOrFireAndHealthBelow) {
                return true;
            } else if (foodLevel > _config.alwaysEatWhenBelowHunger) {
                if (health < _config.alwaysEatWhenBelowHealth) {
                    return true;
                }
            } else {
                // We have half hunger
                return true;
            }
        }

        // Eat if we're  units hungry and we have a perfect fit.
        if (foodLevel < _config.alwaysEatWhenBelowHungerAndPerfectFit && _cachedPerfectFood.isPresent()) {
            int need = 20 - foodLevel;
            Item best = _cachedPerfectFood.get();
            int fills = (best.getFoodComponent() != null) ? best.getFoodComponent().getHunger() : -1;
            return fills == need;
        }

        return false;
    }

    public boolean isTryingToEat() {
        return _isTryingToEat;
    }

    @Override
    public boolean isActive() {
        // We're always checking for food.
        return true;
    }

    @Override
    public String getName() {
        return "Food";
    }

    @Override
    protected void onStop(AltoClef mod) {
        stopEat(mod);
        super.onStop(mod);
    }

    // If we need to eat like, NOW.
    public boolean needsToEatCritical(AltoClef mod) {
        int foodLevel = mod.getPlayer().getHungerManager().getFoodLevel();
        float health = mod.getPlayer().getHealth();
        int armor = mod.getPlayer().getArmor();
        if (health < _config.runDontEatMaxHealth && foodLevel < _config.runDontEatMaxHunger) return false; // RUN NOT EAT
        return armor >= _config.canTankHitsAndEatArmor && foodLevel < _config.canTankHitsAndEatMaxHunger; // EAT WE CAN TAKE A FEW HITS
    }

    private Pair<Integer, Optional<Item>> calculateFood(AltoClef mod) {
        Item bestFood = null;
        double bestFoodScore = Double.NEGATIVE_INFINITY;
        int foodTotal = 0;
        ClientPlayerEntity player = mod.getPlayer();
        float health = player != null? player.getHealth() : 20;
        //float toHeal = player != null? 20 - player.getHealth() : 0;
        float hunger = player != null? player.getHungerManager().getFoodLevel() : 20;
        float saturation = player != null? player.getHungerManager().getSaturationLevel() : 20;
        // Get best food item + calculate food total
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (stack.isFood()) {
                // Ignore protected items
                if (!ItemHelper.canThrowAwayStack(mod, stack)) continue;

                // Ignore spider eyes
                if (stack.getItem() == Items.SPIDER_EYE) {
                    continue;
                }

                FoodComponent food = stack.getItem().getFoodComponent();

                assert food != null;
                float hungerIfEaten = Math.min(hunger + food.getHunger(), 20);
                float saturationIfEaten = Math.min(hungerIfEaten, saturation + food.getSaturationModifier());
                float gainedSaturation = (saturationIfEaten - saturation);
                float gainedHunger = (hungerIfEaten - hunger);
                float hungerNotFilled = 20 - hungerIfEaten;

                float saturationWasted = food.getSaturationModifier() - gainedSaturation;
                float hungerWasted = food.getHunger() - gainedHunger;

                // (2026-08-30 优先高恢复, 用户) 始终以"恢复度(饱食)"为主排序,
                // 优先选恢复量最高的食物, 不再因"溢出浪费"惩罚而一直去选完美贴合
                // 的小食物而不用高恢复的大餐。腐肉惩罚仍保留 (score 里扣)。
                boolean prioritizeSaturation = true;
                float saturationGoodScore = prioritizeSaturation ? gainedSaturation * _config.foodPickPrioritizeSaturationSaturationMultiplier : gainedSaturation;
                float saturationLossPenalty = prioritizeSaturation ? 0 : saturationWasted * _config.foodPickSaturationWastePenaltyMultiplier;
                float hungerLossPenalty = hungerWasted * _config.foodPickHungerWastePenaltyMultiplier;
                float hungerNotFilledPenalty = hungerNotFilled * _config.foodPickHungerNotFilledPenaltyMultiplier;

                float score = saturationGoodScore - saturationLossPenalty - hungerLossPenalty - hungerNotFilledPenalty;

                if (stack.getItem() == Items.ROTTEN_FLESH) {
                    score -= _config.foodPickRottenFleshPenalty;
                }
                if (score > bestFoodScore) {
                    bestFoodScore = score;
                    bestFood = stack.getItem();
                }

                foodTotal += Objects.requireNonNull(stack.getItem().getFoodComponent()).getHunger() * stack.getCount();
            }
        }

        return new Pair<>(foodTotal, Optional.ofNullable(bestFood));
    }

    static class FoodChainConfig {
        public int alwaysEatWhenWitherOrFireAndHealthBelow = 6;
        public int alwaysEatWhenBelowHunger = 10;
        public int alwaysEatWhenBelowHealth = 14;
        public int alwaysEatWhenBelowHungerAndPerfectFit = 20 - 5;
        public int prioritizeSaturationWhenBelowHealth = 8;
        public float foodPickPrioritizeSaturationSaturationMultiplier = 8;
        public float foodPickSaturationWastePenaltyMultiplier = 1;
        public float foodPickHungerWastePenaltyMultiplier = 2;
        public float foodPickHungerNotFilledPenaltyMultiplier = 1;
        public float foodPickRottenFleshPenalty = 100;
        public float runDontEatMaxHealth = 3;
        public int runDontEatMaxHunger = 3;
        public int canTankHitsAndEatArmor = 15;
        public int canTankHitsAndEatMaxHunger = 3;
        // (2026-09-06 前台进食) 真饿/低血时的前台进食优先级: > 用户任务(50),
        // < 逃跑/冷却(70)、加急战斗(95)、MLG(100)。
        public float eatForegroundPriority = 62;
        // 前台进食任务超时 (秒): 换盾失败/装备失败等异常时兜底退出, 防永久卡死。
        public double eatTaskTimeoutSeconds = 20;
    }
}
