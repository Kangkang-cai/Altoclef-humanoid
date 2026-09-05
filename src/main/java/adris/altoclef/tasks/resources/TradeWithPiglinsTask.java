package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.AbstractDoToEntityTask;
import adris.altoclef.tasks.movement.FortressExploreTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask.PerformTradeWithPiglin;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.HashSet;
import java.util.Optional;

public class TradeWithPiglinsTask extends ResourceTask {

    // TODO: Settings? Custom parameter?
    private static final boolean AVOID_HOGLINS = true;
    private static final double HOGLIN_AVOID_TRADE_RADIUS = 64;
    // If we're too far away from a trading piglin, we risk deloading them and losing the trade.
    private static final double TRADING_PIGLIN_TOO_FAR_AWAY = 64 + 8;
    private final int _goldBuffer;
    private final Task _tradeTask = new PerformTradeWithPiglin();
    private Task _goldTask = null;
    // (2026-09-05 找猪灵真探索) 无猪灵时不再用 TimeoutWanderTask 假探索 —
    // 那玩意进度检查器一失败就切 GoalRunAway 随机向, 表现为"选个目的地走
    // 几步就换路线" (用户实测)。改用与找要塞同款的人类式探索: 循猪灵音效
    // / 亲眼看见的堡垒黑石砖/绯红菌岩, 持久朝向游走, 目标走到头再换方向。
    // 缓存实例保状态 (朝向不丢)。
    private final Task _piglinSearcher = FortressExploreTask.forPiglins();
    // (2026-08-24 交易预算判定) 是否已开始交易。未开始交易时先确保金锭达到
    // _goldBuffer (熔炼生金矿等); 一旦开始交易, 金锭会随每次丢出而消耗,
    // 只要还剩金锭就继续交易, 不因少于预算而中途回去收集 (否则交易中少 1
    // 锭就回 CollectGoldIngotTask 合成 → 打开工作台 → 卡死, 用户实测)。
    private boolean _startedTrading = false;

    public TradeWithPiglinsTask(int goldBuffer, ItemTarget[] itemTargets) {
        super(itemTargets);
        _goldBuffer = goldBuffer;
    }

    public TradeWithPiglinsTask(int goldBuffer, ItemTarget target) {
        super(target);
        _goldBuffer = goldBuffer;
    }

    public TradeWithPiglinsTask(int goldBuffer, Item item, int targetCount) {
        super(item, targetCount);
        _goldBuffer = goldBuffer;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _startedTrading = false;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Collect gold if we don't have it.
        if (_goldTask != null && _goldTask.isActive() && !_goldTask.isFinished(mod)) {
            setDebugState("Collecting gold");
            return _goldTask;
        }
        // (2026-08-24 修复) 只有"有金锭"就交易太宽松: 用户实测背包 23 锭 +
        // 16 生金矿 = 39 ≥ 32, 却"没把生金矿熔炼, 直接去交易"。原因: 熔炼
        // 子任务 (SmeltInFurnaceTask) 在收集燃料/合成木板时被围蔽、清光标等
        // 打断 → 回到这里 hasItem(GOLD_INGOT) 恒真 → 跳过收集 → 直接交易,
        // 背包里的 raw_gold 永远不熔炼。必须等金锭数量达到交易预算
        // _goldBuffer 才交易; 不足则继续 CollectGoldIngotTask (其下界分支
        // 会优先熔炼背包已有的 raw_gold → 再合成金粒, 直到凑满 _goldBuffer)。
        // (2026-08-24 真实背包兜底) 用 countItemInInventoryReal 扫真实背包,
        // 不依赖 tracker 缓存 — 启动瞬间缓存可能陈旧 (用户实测按 esc 才刷新),
        // 导致"有金锭却判定没有" → 一直挖金。
        // (2026-08-24 交易预算再修) 只在"未开始交易且金锭 < _goldBuffer"或
        // "金锭耗尽(=0)"时回去收集。一旦开始交易, 每次丢金锭都会消耗, 若
        // 一低于预算就回 CollectGoldIngotTask 会频繁打断交易 (金锭 32→31 就
        // 去合成 1 锭 → 打开工作台 → 卡死, 用户实测 12:21 日志)。
        int goldIngots = StorageHelper.countItemInInventoryReal(mod, Items.GOLD_INGOT);
        if (goldIngots == 0 || (!_startedTrading && goldIngots < _goldBuffer)) {
            if (_goldTask == null || !_goldTask.isActive()) {
                _goldTask = TaskCatalogue.getItemTask(Items.GOLD_INGOT, _goldBuffer);
            }
            setDebugState("Collecting gold (budget " + _goldBuffer + ")");
            return _goldTask;
        }
        _startedTrading = true;

        // If we have no piglin nearby, explore until we find piglin.
        if (!mod.getEntityTracker().entityFound(PiglinEntity.class)) {
            setDebugState("Exploring for piglins (human-like)");
            return _piglinSearcher;
        }

        // If we have a trading piglin that's too far away, get closer to it.

        // Find gold and trade with a piglin
        setDebugState("Trading with Piglin");
        return _tradeTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof TradeWithPiglinsTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Trading with Piglins";
    }

    static class PerformTradeWithPiglin extends AbstractDoToEntityTask {

        private static final double PIGLIN_NEARBY_RADIUS = 10;
        private final TimerGame _barterTimeout = new TimerGame(2);
        private final TimerGame _intervalTimeout = new TimerGame(10);
        private final HashSet<Entity> _blacklisted = new HashSet<>();
        private Entity _currentlyBartering = null;

        public PerformTradeWithPiglin() {
            super(3);
        }

        @Override
        protected void onStart(AltoClef mod) {
            super.onStart(mod);

            mod.getBehaviour().push();

            // Don't throw away our gold lol
            mod.getBehaviour().addProtectedItems(Items.GOLD_INGOT);

            // Don't attack piglins unless we've blacklisted them.
            mod.getBehaviour().addForceFieldExclusion(entity -> {
                if (entity instanceof PiglinEntity) {
                    return !_blacklisted.contains(entity);
                }
                return false;
            });
            //_blacklisted.clear();
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            mod.getBehaviour().pop();
            super.onStop(mod, interruptTask);
        }

        @Override
        protected boolean isSubEqual(AbstractDoToEntityTask other) {
            return other instanceof PerformTradeWithPiglin;
        }

        @Override
        protected Task onEntityInteract(AltoClef mod, Entity entity) {

            // (2026-08-24 交易不生效修复) 切到交易时容器界面可能还开着 (熔炉
            // 收金锭收尾), 界面开启时 interactEntity 右键被界面拦截 → 交易
            // 不触发 → Approach 猪灵瞬间完成又反复 = 卡死循环 (用户实测
            // 13:52)。交互前先确保所有界面关闭 (幂等, 无界面无操作)。
            StorageHelper.closeScreen();

            // (2026-08-23 捡猪灵掉物) 猪灵收走金锭后会把随机物品朝玩家方向丢
            // 出来 (防火药水/末影珍珠等, 都是 ItemEntity)。用户实测: 猪灵丢了
            // 瓶防火药水 bot 没捡 ("可能是没看到") — 交易前先捡最近的猪灵掉物。
            // 优先防火药水 (POTION/SPLASH_POTION + FIRE_RESISTANCE 效果, 用
            // NBT 感知判定, 排除水瓶), 其次末影珍珠。ItemTarget 只比 Item 类型,
            // 所以先用 isFireResPotionStack 确认最近的掉落物值得捡, 命中才派
            // PickupDroppedItemTask (满背包会自动腾位)。
            Optional<Entity> drop = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                    e -> e instanceof ItemEntity ie && ie.isAlive() && ie.isInRange(mod.getPlayer(), 12),
                    ItemEntity.class);
            if (drop.isPresent() && drop.get() instanceof ItemEntity itemEntity) {
                ItemStack dropStack = itemEntity.getStack();
                if (ItemHelper.isFireResPotionStack(dropStack) || dropStack.getItem() == Items.ENDER_PEARL) {
                    setDebugState("Picking up piglin barter drop");
                    return new PickupDroppedItemTask(new ItemTarget[]{
                            new ItemTarget(Items.POTION, 1),
                            new ItemTarget(Items.SPLASH_POTION, 1),
                            new ItemTarget(Items.ENDER_PEARL, 1)
                    }, true);
                }
            }

            // If we didn't run this in a while, we can retry bartering.
            if (_intervalTimeout.elapsed()) {
                // We didn't interact for a while, continue bartering as usual.
                _barterTimeout.reset();
                _intervalTimeout.reset();
            }

            // We're trading so reset the barter timeout
            if (EntityHelper.isTradingPiglin(_currentlyBartering)) {
                _barterTimeout.reset();
            }

            // We're bartering a new entity.
            if (!entity.equals(_currentlyBartering)) {
                _currentlyBartering = entity;
                _barterTimeout.reset();
            }

            if (_barterTimeout.elapsed()) {
                // We failed bartering.
                Debug.logMessage("Failed bartering with current piglin, blacklisting.");
                _blacklisted.add(_currentlyBartering);
                _barterTimeout.reset();
                _currentlyBartering = null;
                return null;
            }

            if (AVOID_HOGLINS && _currentlyBartering != null && !EntityHelper.isTradingPiglin(_currentlyBartering)) {
                Optional<Entity> closestHoglin = mod.getEntityTracker().getClosestEntity(_currentlyBartering.getPos(), HoglinEntity.class);
                if (closestHoglin.isPresent() && closestHoglin.get().isInRange(entity, HOGLIN_AVOID_TRADE_RADIUS)) {
                    Debug.logMessage("Aborting further trading because a hoglin showed up");
                    _blacklisted.add(_currentlyBartering);
                    _barterTimeout.reset();
                    _currentlyBartering = null;
                }
            }

            setDebugState("Trading with piglin");

            if (mod.getSlotHandler().forceEquipItem(Items.GOLD_INGOT)) {
                mod.getController().interactEntity(mod.getPlayer(), entity, Hand.MAIN_HAND);
                _intervalTimeout.reset();
            }
            return null;
        }

        @Override
        protected Optional<Entity> getEntityTarget(AltoClef mod) {
            // Ignore trading piglins
            Optional<Entity> found = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                    entity -> {
                        if (_blacklisted.contains(entity)
                                || EntityHelper.isTradingPiglin(entity)
                                || (entity instanceof LivingEntity && ((LivingEntity) entity).isBaby())
                                || (_currentlyBartering != null && !entity.isInRange(_currentlyBartering, PIGLIN_NEARBY_RADIUS))) {
                            return false;
                        }

                        if (AVOID_HOGLINS) {
                            // Avoid trading if hoglin is anywhere remotely nearby.
                            Optional<Entity> closestHoglin = mod.getEntityTracker().getClosestEntity(entity.getPos(), HoglinEntity.class);
                            return closestHoglin.isEmpty() || !closestHoglin.get().isInRange(entity, HOGLIN_AVOID_TRADE_RADIUS);
                        }
                        return true;
                    }, PiglinEntity.class
            );
            if (found.isEmpty()) {
                if (_currentlyBartering != null && (_blacklisted.contains(_currentlyBartering) || !_currentlyBartering.isAlive())) {
                    _currentlyBartering = null;
                }
                found = Optional.ofNullable(_currentlyBartering);
            }
            return found;
        }

        @Override
        protected String toDebugString() {
            return "Trading with piglin";
        }
    }

}
