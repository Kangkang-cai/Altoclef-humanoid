package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.entity.AbstractDoToEntityTask;
import adris.altoclef.tasks.movement.FortressExploreTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask.PerformTradeWithPiglin;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

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
    // (2026-09-13 改 static, 用户"任务打断拾取了他们后, 32金子任务又触发了")
    // 上层每 4-12 分钟重建本任务实例 (FortressExploreTask 注释实锤), 实例字段
    // 一重建就丢 → 拾取掉落物/实例重建后 gold<32 又触发 CollectGoldIngotTask,
    // bot 半路跑去开工作台。改 static 后整个会话只"预热"一次: 金锭耗尽 (=0)
    // 仍会照常续挖, 预算闸只挡"中途因实例重建误触发"。
    private static boolean _startedTrading = false;

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
        // (2026-09-13) 交易/捡掉落物期间保护金锭与交易战利品, 防"满背包扔杂物"
        // 把刚到手的黑曜石/珍珠扔了。基类 ResourceTask.onStart 已 push、onStop 已
        // pop, 这里只往本层添加, 无需自己配对。
        mod.getBehaviour().addProtectedItems(
                Items.GOLD_INGOT, Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.ENDER_PEARL);
        // 注意: _startedTrading 是 static, 这里绝不能再置 false (见字段注释)。
    }

    /**
     * (2026-09-13 交易掉物捡拾升级) 猪灵以物易物会朝玩家丢随机战利品, 这些
     * 一律捡走: 防火药水 (NBT 感知判定, 排除水瓶)、末影珍珠、黑曜石、哭泣的
     * 黑曜石 (猪灵两种黑曜石都会丢, 门框只认普通黑曜石, 哭泣的先拿上不亏)。
     * 用户实测: 猪灵丢黑曜石/珍珠 bot 视而不见。
     * 位置放在父层 onResourceTick: 每 tick 先于子任务判定, 接近猪灵途中、
     * 准心等待中也能捡 (原来只挂在 onEntityInteract 里, 接近阶段视而不见)。
     */
    private static boolean isValuableBarterDrop(ItemStack stack) {
        return ItemHelper.isFireResPotionStack(stack)
                || stack.getItem() == Items.ENDER_PEARL
                || stack.getItem() == Items.OBSIDIAN
                || stack.getItem() == Items.CRYING_OBSIDIAN;
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

        // (2026-09-13 交易掉物捡拾升级) 先捡眼前的交易战利品再谈别的。注意
        // 这里只换"本层返回的子任务", 本任务实例不销毁, _startedTrading(static)
        // 不丢 → 捡完回来继续交易, 不会触发 32 金锭收集 (用户红线)。
        Optional<Entity> barterDrop = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                e -> e instanceof ItemEntity ie && ie.isAlive() && ie.isInRange(mod.getPlayer(), 16)
                        && isValuableBarterDrop(ie.getStack()),
                ItemEntity.class);
        if (barterDrop.isPresent()) {
            setDebugState("Picking up piglin barter drop");
            return new PickupDroppedItemTask(new ItemTarget[]{
                    new ItemTarget(Items.POTION, 1),
                    new ItemTarget(Items.SPLASH_POTION, 1),
                    new ItemTarget(Items.ENDER_PEARL, 1),
                    new ItemTarget(Items.OBSIDIAN, 1),
                    new ItemTarget(Items.CRYING_OBSIDIAN, 1)
            }, true);
        }

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
            // (2026-09-13 用户"交易时距离远了点, 再靠近") 3 → 2: 接近距离取
            // 2 格 (射程 3.2 内, 交互判定稳过), 站位贴到人类交易的自然距离,
            // 准心也更容易落在猪灵身上。
            super(2);
        }

        @Override
        protected void onStart(AltoClef mod) {
            super.onStart(mod);
            // (2026-09-09) 准心等待/挖除状态不跨轮残留。
            _aimWaitStartTick = Long.MIN_VALUE;
            _clearTask = null;

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

            // (2026-09-13 捡猪灵掉物上移) 原先这里 (2026-08-23 版) 捡防火药水/
            // 末影珍珠, 但只在"已贴近猪灵且准心落上"时才判, 接近途中视而不见,
            // 且不认黑曜石 (用户实测无视)。整块上移到父层 onResourceTick 的
            // barterDrop 分支 (16 格、含黑曜石/哭泣黑曜石), 父层每 tick 先判,
            // 本处成为死代码, 已删除。

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

            // (2026-09-09 交易拟人化, 用户"必须准心指向猪灵无遮挡才允许交易,
            // 使用通用的自动瞄准") 通用自动瞄准 = 战斗出刀同款: 先 LookHelper.lookAt
            // 猪灵身体中心, 再用客户端 crosshairTarget 校验准心真的落在猪灵身上
            // (与玩家画面看到的准心一致, 隐含视线无遮挡) 才丢金锭 interactEntity。
            // 准心没落上就等待转头, 等待期不拉黑猪灵 (barterTimeout 持续重置)。
            LookHelper.lookAt(mod, entity.getBoundingBox().getCenter());
            if (!mod.getControllerExtras().isCrosshairOn(entity)) {
                long now = WorldHelper.getTicks();
                if (_aimWaitStartTick == Long.MIN_VALUE) _aimWaitStartTick = now;
                _barterTimeout.reset();
                if (now - _aimWaitStartTick > 60) {
                    // 3s 准心仍未落上: 多半准线路径被方块挡 (父层 LOS 判定 COLLIDER
                    // 口径与准心 raycast 有差异) 或转头太慢。每 3s 节流记一条诊断;
                    // 持续被挡 8s 后父层 onObstructionPersistent 会接管挖除。
                    Debug.logToFile("altoclef_diag.log",
                            "[PiglinTrade]\tcrosshair not on piglin for 3s (waiting rotation / possibly obstructed)");
                    _aimWaitStartTick = now;
                }
                setDebugState("Aiming crosshair at piglin before trading");
                return null;
            }
            _aimWaitStartTick = Long.MIN_VALUE;

            setDebugState("Trading with piglin");

            if (mod.getSlotHandler().forceEquipItem(Items.GOLD_INGOT)) {
                mod.getController().interactEntity(mod.getPlayer(), entity, Hand.MAIN_HAND);
                _intervalTimeout.reset();
            }
            return null;
        }

        // (2026-09-09 交易遮挡消除, 用户"先走动, 卡住才挖") 父层绕位 8s 仍被同一
        // 遮挡挡住 → 挖掉它。传送门本体/门框/死门记忆邻域/箱子/木桶/刷怪笼绝不挖。
        private Task _clearTask = null;
        private long _aimWaitStartTick = Long.MIN_VALUE;

        @Override
        protected Task onObstructionPersistent(AltoClef mod, Entity entity, BlockPos obstruction) {
            if (!isSafeToMineForTrade(mod, obstruction)) {
                Debug.logToFile("altoclef_diag.log", "[PiglinTrade]\tobstruction "
                        + obstruction.toShortString() + " is protected, keep repositioning");
                return null; // 维持父层绕位
            }
            setDebugState("Mining obstructing block for trade LOS: " + obstruction.toShortString());
            if (_clearTask == null || !_clearTask.isActive()) {
                _clearTask = new DestroyBlockTask(obstruction);
            }
            return _clearTask;
        }

        /**
         * (2026-09-09) 交易挖除遮挡的安全闸: 与 BotBehaviour.isPortalBodyBlock
         * 对齐 (传送门/门框/死门记忆邻域黑曜石), 另加战利品容器与刷怪笼。
         */
        private static boolean isSafeToMineForTrade(AltoClef mod, BlockPos pos) {
            Block b = mod.getWorld().getBlockState(pos).getBlock();
            if (b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL || b == Blocks.END_PORTAL_FRAME
                    || b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN) return false;
            if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL
                    || b == Blocks.SPAWNER) return false;
            for (BlockPos lost : mod.getWorldMemory().getLostPortalLocations()) {
                if (lost.isWithinDistance(pos, 1.0)) return false;
            }
            return true;
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
