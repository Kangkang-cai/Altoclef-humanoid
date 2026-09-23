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
import net.minecraft.screen.PlayerScreenHandler;
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

    // ---- (2026-09-08 贪婪食物峰值, 用户) ----
    // 库存食物分数一旦达到过 settings.getFoodPeakUnits()(默认 240) 即永久锁存。
    // 锁存后: 吃东西导致分数回落【不再】触发食物收集 (消灭"120 目标吃一口就
    // 重复触发"的循环), 只有跌破 settings.getMinimumFoodAllowed() 真断粮才重新
    // 收集。静态字段: BeatMinecraft2Task 每 tick 重建实例, 锁存必须跨实例存活。
    public static boolean s_foodPeakReached = false;

    // ---- (2026-09-15 觅食远征, 用户"连续长时间没找到食物就远走, 不要走到海里") ----
    // CollectFoodTask 每轮觅食周期维护 s_foodSearchStartMs / s_foodSearchBestPotential:
    // 食物潜力高水位无增长持续超过 settings.foodSearchExpeditionTimeoutSeconds →
    // 置 s_foodExpeditionActive=true (远征模式: 锁定方位角一路向, 不回头、不下海),
    // 收集目标降为 settings.foodExpeditionReducedUnits。按降低量达标收官时一并置
    // s_foodPeakReached=true (峰值语义锁存, 收完不再立刻冲峰值, 真断粮才重新收集)。
    // static: FoodChain/BeatMinecraft2Task 每 tick 重建 CollectFoodTask 实例,
    // 超时状态必须跨实例存活 (与 s_foodPeakReached 同理)。
    public static boolean s_foodExpeditionActive = false;
    public static long s_foodSearchStartMs = -1;
    public static double s_foodSearchBestPotential = -1;
    // (2026-09-22 防尖峰重置) 潜力新高的"持续确认"起点: 新高必须持续
    // FOOD_GAIN_CONFIRM_MS (CollectFoodTask) 才重置无增长时钟。static 跨实例
    // 存续, 与上方三个字段同理。
    public static long s_foodGainPendingSinceMs = -1;

    /**
     * (2026-09-17 世界边界) 换存档时清空食物哨兵。
     * `s_foodPeakReached` 是【锁存】语义(达到过峰值后, 分数回落不再触发收集) ——
     * 上个存档"吃满了"的结论不该管到新存档, 新存档背包本来就空, 复位反而更准确。
     * 远征状态 (锁定方位角/搜索基线) 同理, 留着会让新世界的觅食沿用旧方位。
     */
    public static void onWorldBoundary() {
        s_foodPeakReached = false;
        s_foodExpeditionActive = false;
        s_foodSearchStartMs = -1;
        s_foodSearchBestPotential = -1;
        s_foodGainPendingSinceMs = -1;
    }

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
                    + (blocked == null || blocked.isEmpty() ? "" : " blocked=" + blocked)
                    // (2026-09-18 背包食物明细) hasFood=0 但用户肉眼看见背包有
                    // 食物时, 靠这一行定位到底哪一摞被当成了"不可吃"。
                    + " inv=" + describeFoodInventory(mod);
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Exception ignored) { /* 诊断日志绝不影响主逻辑 */ }
    }

    /**
     * (2026-09-18 背包食物明细诊断) 列出背包里所有 isFood 的物品与数量,
     * 受保护(被判"不可吃")的打 [PROT] 标记。只读, 不影响任何决策。
     */
    private String describeFoodInventory(AltoClef mod) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (!stack.isFood()) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(stack.getItem().toString()).append('x').append(stack.getCount());
            if (!ItemHelper.canThrowAwayStack(mod, stack)) sb.append("[PROT]");
        }
        return sb.length() == 0 ? "empty" : sb.toString();
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

        // (2026-09-14 空中禁食, 用户"baritone 空中跳跃中途触发吃东西, 减速掉下去了")
        // 使用物品强制减速 → 跳跃/坠落中开吃 = 跳距砍半, 落点差一格就是岩浆/深坑。
        // 口径与 ITaskRequiresGrounded 相同 (onGround/swimming/touchingWater/
        // climbing 任一即算"稳定"), 空中一律不吃, 落地下一 tick 自动恢复 —— 最多
        // 晚 1~2 秒吃上, 换跳跃全程满速。挡住前台进食 (62) 和异步补食两条路。
        // (2026-09-23 收窄, 事故 20260923-140827) 旧实现空中直接 return
        // NEGATIVE_INFINITY, 连收集食物 (55) 一起退出 —— baritone 赶路自己跳地形
        // 悬空一拍 → Food 退场 beat 50 接管, 落地又拿回, 每 0.4s 横跳、导航反复
        // 中断。改为只标记 airborne: 进食路径跳过, 收集判定照常。
        boolean airborne = !(mod.getPlayer().isOnGround() || mod.getPlayer().isSwimming()
                || mod.getPlayer().isTouchingWater() || mod.getPlayer().isClimbing());
        if (airborne) {
            stopEat(mod);
            logFoodDiag(mod, "wait", "airborne");
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

        // (2026-09-23) airborne 时不进食: 整个 if/else 跳过, 落入下方收集食物
        // 判定 (stopEat 已在上面调用)。
        if (airborne) {
            // no-op: fall through to collection decision
        } else if (hasFood && (needEat || _requestFillup) && _cachedPerfectFood.isPresent() && !mod.getMLGBucketChain().isChorusFruiting()) {
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
                //
                // (2026-09-22 熔炉合成卡死修复) 旧的 facing-interactable 闸门
                // (tryAvoidingInteractable) 挡在本分支之前, 且带两个破坏性副作用:
                // closeScreen() + randomOrientation()。needEat=true 期间它每 tick
                // 重评 —— 用户任务刚把工作台界面打开就被关掉、准星对准工作台就被
                // 转走, 而让位后用户任务又转回来 → Food↔User Tasks ownership
                // 每 1~2 tick 互换 (121524 会话 6.2s 内 50 次), 合成空转报 clean,
                // 熔炉 51s 没合成出来。现在: 评估路径完全只读 (LookHelper.
                // isFacingInteractable), 视角由 EatItemTask 自己负责 (面对容器先
                // 低头再吃); 唯一保留的让位条件是容器界面正开着 —— 那是用户任务
                // 正在操作界面, 抢了只会两边都卡。
                if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) {
                    logFoodDiag(mod, "wait", "container-screen-open");
                    return Float.NEGATIVE_INFINITY;
                }
                stopEat(mod);
                setTask(new EatItemTask(_config.eatTaskTimeoutSeconds));
                logFoodDiag(mod, "foreground-eat", "");
                return _config.eatForegroundPriority;
            }
            // 异步补食 (不饿只补满): 保留只读闸门 —— 面向容器时右键会开界面
            // 而不是吃, 让位即可; 但不 closeScreen/不转视角 (副作用已移出评估路径)。
            if (!LookHelper.isFacingInteractable(mod)) {
                startEat(mod, _cachedPerfectFood.get());
                logFoodDiag(mod, "async-topup", "");
            } else {
                logFoodDiag(mod, "wait", "facing-interactable");
            }
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

        // (2026-09-08 贪婪食物峰值) 峰值检测: 达到过一次即锁存, 吃掉回落不再触发。
        int peakUnits = Math.max(settings.getFoodUnitsToCollect(), settings.getFoodPeakUnits());
        if (peakUnits > 0 && !s_foodPeakReached && inventoryFoodScore >= peakUnits) {
            s_foodPeakReached = true;
            _needsFood = false;
            Debug.logToFile("altoclef_diag.log",
                    "[FOOD-FIX]\t[FoodPeak]\tpeak reached food=" + inventoryFoodScore
                            + "/" + peakUnits + " -> LATCHED; eating back down will NOT re-trigger collection");
        }
        // 锁存前: 沿用旧触发 (跌破 minimumFoodAllowed 锁定 _needsFood, 收集到达峰);
        // 锁存后: 只有跌破 minimumFoodAllowed 真断粮才重新收集, 目标直接冲峰值。
        // (2026-09-15 觅食远征) 超时远征锁存期间, 本轮收集目标用降低值
        // (foodExpeditionReducedUnits), 不再死磕峰值。
        int collectTarget = peakUnits;
        if (s_foodExpeditionActive) {
            int reduced = settings.getFoodExpeditionReducedUnits();
            if (reduced > 0 && reduced < collectTarget) {
                collectTarget = reduced;
            }
        }
        boolean wantCollectFood = s_foodPeakReached
                // (2026-09-18 断粮兜底恒假修复) minimumFoodAllowed 默认 0,
                // 而食物分数最低就是 0 —— 原式 inventoryFoodScore < 0 恒为
                // false, 一旦峰值锁存, 哪怕背包被清空也永远不再触发收集。
                // 0918 会话实测: 20:34:29 锁存后, 20:38:23 起 hasFood=0 持续
                // 7 分半, Food 链再没拿到过 owner (bot 在下界饿着跑烈焰棒)。
                // 改成 <= 0: 库存分数归零 = 真断粮, 该补货。
                ? inventoryFoodScore <= 0
                : (_needsFood || _cachedFoodScore < settings.getMinimumFoodAllowed())
                    && inventoryFoodScore < collectTarget;
        if (wantCollectFood) {
            _needsFood = true;

            // Only collect if we don't have enough food.
            // If the user inputs invalid settings, the bot would get stuck here.
            if (inventoryFoodScore < collectTarget) {
                setTask(new CollectFoodTask(collectTarget));
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
        // (2026-08-23 吃不上饭修复) 副手持盾时右键被盾抢走, 先把盾停到光标再进食。
        // (2026-09-03 背包满举盾卡死修复) 停盾失败 (光标被占) 时本 tick 绝不
        // hold 右键 (右键 = 举盾 → 原地举盾卡死)。
        if (!parkShieldOffHands(mod)) {
            stopEat(mod);
            return;
        }
        mod.getSlotHandler().forceEquipItem(new Item[]{food}, true); //"true" because it's food
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
    }

    /**
     * (2026-09-06 21:38 主手盾+副手面包 卡死修复) 把盾"停"到不挡右键的地方。
     *
     * 历史包袱: 旧换盾方案 (盾↔空背包格 / 盾↔食物互换) 需要 1~3 步槽位
     * 点击序列, 与 containerItemMoveDelay 节流竞态 —— 某步被静默丢弃后落在
     * 中间态 (两处实锤: ①SWAP 按钮 9 无效 no-op 一个月; ②21:20 主手盾+
     * 副手面包永久卡死)。
     *
     * 新方案: 一把 forced PICKUP 把副手盾捡到光标 —— 光标不是手, 不参与
     * 右键判定, 主手食物直接开吃。吃完由 restoreShieldFromCursor 放回。
     * 全程 1 次点击, 无节流竞态, 不需要空背包格。
     *
     * @return true = 副手已无盾 (没盾 / 已捡上光标); false = 光标被占, 无法停盾。
     */
    public static boolean parkShieldOffHands(AltoClef mod) {
        if (mod.getPlayer().getOffHandStack().getItem() != Items.SHIELD) {
            return true;
        }
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return false;
        }
        mod.getSlotHandler().clickSlotForced(
                adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT, 0,
                net.minecraft.screen.slot.SlotActionType.PICKUP);
        return mod.getPlayer().getOffHandStack().getItem() != Items.SHIELD;
    }

    /**
     * (2026-09-06) 进食结束后把光标上的盾放回: 优先副手 (空), 否则背包空格。
     * forced 点击, 立即生效。光标上不是盾时不动作。
     */
    public static void restoreShieldFromCursor(AltoClef mod) {
        if (StorageHelper.getItemStackInCursorSlot().getItem() != Items.SHIELD) {
            return;
        }
        if (mod.getPlayer().getOffHandStack().isEmpty()) {
            mod.getSlotHandler().clickSlotForced(
                    adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT, 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP);
            return;
        }
        adris.altoclef.util.slots.Slot emptySlot = StorageHelper.getRealBackpackSlots().stream()
                .filter(s -> StorageHelper.getItemStackInSlot(s).isEmpty())
                .findFirst().orElse(null);
        if (emptySlot != null) {
            mod.getSlotHandler().clickSlotForced(emptySlot, 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP);
            return;
        }
        // 背包满 (2026-09-06 22:33 补): 找一摞可丢弃的非食物垃圾, PICKUP 换位
        // (盾入背包, 垃圾上光标), 再把垃圾丢地上 (vanilla 点击屏幕外=丢弃光标物品)。
        adris.altoclef.util.slots.Slot junkSlot = null;
        for (adris.altoclef.util.slots.Slot s : StorageHelper.getRealBackpackSlots()) {
            ItemStack stack = StorageHelper.getItemStackInSlot(s);
            if (!stack.isEmpty() && stack.getItem() != Items.SHIELD && !stack.isFood()
                    && ItemHelper.canThrowAwayStack(mod, stack)) {
                junkSlot = s;
                break;
            }
        }
        if (junkSlot != null) {
            mod.getSlotHandler().clickSlotForced(junkSlot, 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP);
            mod.getSlotHandler().clickSlotForced(adris.altoclef.util.slots.Slot.UNDEFINED, 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP);
        } else {
            Debug.logToFile("altoclef_diag.log",
                    "[FOOD-EAT] shield parked on cursor, no empty slot / junk to toss");
        }
    }

    /**
     * (2026-09-20 10:26 用户"拿着盾发愣, 不吃东西") 光标被占时的自愈: 把光标上的
     * 【任意】物品放回背包 (放不进就换一摞可丢垃圾, 垃圾丢地上)。
     *
     * 与 {@link #restoreShieldFromCursor} 同源, 差别是①不限盾 ②不优先副手 (副手
     * 很可能正被那块停不下来的盾占着)。20260920-102209 实锤: 10:23:44~10:23:53
     * 光标上卡着一把钻石剑 (换武器序列被槽位节流吞掉一步留下的中间态), 副手又有盾
     * → {@link #parkShieldOffHands} 判"光标被占"直接放弃 → 进食整段干等 9 秒。
     * 原实现只有"干等"(等别的模块把光标清掉), 没有任何自愈路径 —— 本方法补上。
     *
     * @return true = 光标已清空 (可以继续停盾/进食)。
     */
    public static boolean restoreCursorItemToInventory(AltoClef mod) {
        if (StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return true;
        }
        adris.altoclef.util.slots.Slot emptySlot = StorageHelper.getRealBackpackSlots().stream()
                .filter(s -> StorageHelper.getItemStackInSlot(s).isEmpty())
                .findFirst().orElse(null);
        if (emptySlot != null) {
            mod.getSlotHandler().clickSlotForced(emptySlot, 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP);
            return StorageHelper.getItemStackInCursorSlot().isEmpty();
        }
        // 背包满: 找一摞可丢弃的垃圾换位 (光标物品入背包, 垃圾上光标), 再把垃圾丢地。
        for (adris.altoclef.util.slots.Slot s : StorageHelper.getRealBackpackSlots()) {
            ItemStack stack = StorageHelper.getItemStackInSlot(s);
            if (!stack.isEmpty() && ItemHelper.canThrowAwayStack(mod, stack)) {
                mod.getSlotHandler().clickSlotForced(s, 0,
                        net.minecraft.screen.slot.SlotActionType.PICKUP);
                mod.getSlotHandler().clickSlotForced(adris.altoclef.util.slots.Slot.UNDEFINED, 0,
                        net.minecraft.screen.slot.SlotActionType.PICKUP);
                return StorageHelper.getItemStackInCursorSlot().isEmpty();
            }
        }
        Debug.logToFile("altoclef_diag.log",
                "[FOOD-EAT] cursor item stuck, no empty slot / junk to toss: "
                        + StorageHelper.getItemStackInCursorSlot());
        return false;
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
            // (2026-09-06) 进食期间盾停在光标上, 结束时放回副手/背包空格。
            restoreShieldFromCursor(mod);
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
                // (2026-09-18 受保护食物兜底进食, 用户"背包有食物但他不吃")
                // 旧代码把受保护物品整条 continue —— 保护(protect)的语义是
                // 【别当垃圾丢掉】, 不等于【别吃】。而 CollectFoodTask.onStart
                // 恰好把自己要收集的熟食全加进 protectedItems
                // (CollectFoodTask:276 金苹果/面包/烤马铃薯, :302-303 各
                // raw/cooked 肉), 于是刚收集回来的牛排猪排被判"不可吃":
                // calculateFood 返回 foodTotal=0 / bestFood=none → FoodChain 走
                // no-food 分支, 一口不吃; 而 StorageHelper
                // .calculateInventoryFoodScore 不排除受保护物品, 库存分数 > 0
                // 又让"去补货"永不触发 —— 双向死锁, bot 背包有食物却活活饿死
                // (0918 会话实测: hunger 6、health 20→8.7, 7 分半 hasFood=0
                // bestFood=none 全程不动)。
                // 修法: 受保护食物不再跳过, 而是加"最后兜底"惩罚 —— 有普通食物
                // 时优先吃普通的; 只剩受保护食物时才轮到它。foodTotal 照常计入,
                // 这样 hasFood 与 calculateInventoryFoodScore 口径也重新对齐。
                boolean protectedFood = !ItemHelper.canThrowAwayStack(mod, stack);

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

                float score;
                boolean healingMode = health < _config.alwaysEatWhenBelowHealth;
                if (healingMode) {
                    // (2026-09-06 22:40 羊排不吃修复, 用户"背包有13块羊排怎么不吃")
                    // 低血=为回血而吃: 回血速度由【饱食度】驱动, 大餐 (熟羊肉
                    // hunger6/sat0.8) 恢复量全面优于面包 (hunger5/sat0.6)。
                    // 但旧公式在高饥饿时被"浪费惩罚"反转: hunger=17 时熟羊肉
                    // gainedHunger=3 浪费 3 点 → -6, 面包浪费 2 → -4; 饱食加成
                    // 只差 0.2×8=1.6, 净分面包 0.8 > 羊肉 0.4 → 永远选面包。
                    // 低血时取消一切浪费/未填满惩罚, 直接最大化进食后恢复量。
                    // (2026-09-23 残血吃浆果事故, 用户"回血时先吃饱食度恢复最快的")
                    // 受保护 -1000 惩罚在回血分支同样取消: 09-18 保护惩罚的本意是
                    // "普通饥饿时别消耗任务储备粮", 但任务保护(如 SmeltInFurnaceTask
                    // 挂起未释放把熟肉全锁 [PROT]) 与保命需求冲突时保命优先 —
                    // 16:21 实锤 health=4.5 背包熟肉27块全 [PROT], bot 连吃5个
                    // 甜浆果(+2/个)才补满, 回血窗口被浪费。回血分支只认恢复量。
                    score = saturationGoodScore + gainedHunger;
                } else {
                    score = saturationGoodScore - saturationLossPenalty - hungerLossPenalty - hungerNotFilledPenalty;
                    // (2026-09-18 受保护食物兜底进食) 见上方 protectedFood 注释:
                    // 重罚到普通食物之后, 只剩受保护食物时才轮到它 (防饿死)。
                    // (2026-09-23) 仅普通饥饿分支生效; 回血分支保命优先, 不罚。
                    if (protectedFood) {
                        score -= _config.foodPickProtectedPenalty;
                    }
                }

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
        // (2026-09-18 受保护食物兜底进食) 受保护食物的排序惩罚: 保护语义是
        // "别当垃圾丢", 不是"别吃"。重罚让它排在普通食物之后 —— 背包只剩
        // 受保护食物时仍会被选中, 不至于饿死。
        public float foodPickProtectedPenalty = 1000;
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
