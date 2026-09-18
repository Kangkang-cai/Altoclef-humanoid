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
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public class TradeWithPiglinsTask extends ResourceTask {

    // TODO: Settings? Custom parameter?
    private static final boolean AVOID_HOGLINS = true;
    // (2026-09-18 18:10 语义对齐蛮兵两层) hoglin 64 格硬禁 = 同构问题: 一只
    // 游荡疣猪兽就把 64 格内所有猪灵全灭 (173821 会话 17:54+ 洪流, 18:06:53
    // 防御链 "Block path from hoglin" 实锤 hoglin 在场)。改为 8 格硬禁 +
    // 64 格软偏好 (优先无 hoglin 辐射的猪灵, 全被辐射照常交易)。
    private static final double HOGLIN_AVOID_TRADE_RADIUS = 64;
    private static final double HOGLIN_HARD_TRADE_RADIUS = 8;
    // (2026-09-18 蛮兵攻击范围内不交易, 用户"蛮兵攻击范围内的猪灵不能选为交易
    // 目标") 32 = 原版 mob follow_range 默认仇恨半径: 贴近该猪灵必撞进蛮兵
    // 视野, 蛮兵见玩家即敌对且不受安抚 (12:02:56 实锤被蛮兵打死)。与 hoglin
    // 同款模式: 目标过滤 + 交易中途入场即停。
    // (2026-09-18 17:05 语义修改, 用户拍板"32格太严格") 蛮兵 32 格 (原版仇恨
    // 半径) 不再硬禁交易 —— 堡垒区蛮兵常驻, 32 格硬禁 = 变相禁交易 (165136
    // 会话 16:53:58 实锤: 交易成功出货后目标池被清空, 贴脸猪灵也不交易)。
    // 改为两层: 8 格内硬禁 (交易一笔约 6s, 8 格内蛮兵必撞脸, 打断=白丢金锭);
    // 8~32 格降级为软偏好 —— 优先选无蛮兵辐射的猪灵, 全被辐射时照常交易。
    private static final double BRUTE_AVOID_TRADE_RADIUS = 32;
    private static final double BRUTE_HARD_TRADE_RADIUS = 8;
    // If we're too far away from a trading piglin, we risk deloading them and losing the trade.
    private static final double TRADING_PIGLIN_TOO_FAR_AWAY = 64 + 8;
    private final int _goldBuffer;
    // (2026-09-14 掉物吸附误拉黑) 持有交易子任务的具名引用, 供父层在"猪灵丢出
    // 战利品/掉物消失"时重置其 barterTimeout (见 onResourceTick barterDrop 分支)。
    private final PerformTradeWithPiglin _tradeTask = new PerformTradeWithPiglin();
    // 上一 tick 是否正处于"捡拾交易战利品"分支 (用于检测掉物消失的边沿)。
    private boolean _pickingUpLoot = false;
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

    // =========================================================================
    // (2026-09-14 盾牌看门狗让位心跳, 232015 场次) 盾牌看门狗 (prio 55) 在交易
    // 进行中插队合成 → 开背包屏打断丢金锭节奏, 且插队半途被拉回时留下半成品
    // (4 木板留 2x2 格 + 合成台留输出格, 整场未清)。WorldSurvivalChain 据此
    // 跳过本轮看门狗。心跳+TTL 范式 (铁律): 只在任务真跑着且金锭可丢时每 tick
    // 刷新, 任务停了/实例重建 3s 后自动失效 —— 不依赖 onStart/onStop 清理。
    // =========================================================================
    private static volatile long _lastTradeFlowMs = 0;

    /** 交易流程真正进行中 (已过预算闸且身上有金锭可丢, 3s 心跳窗口)。 */
    public static boolean isTradeInProgress() {
        return System.currentTimeMillis() - _lastTradeFlowMs < 3000;
    }

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
     * (2026-09-13) 交易进行中 (已过预算闸且身上还有金锭可丢) 暂缓"清空合成格"
     * 兜底, 别在丢金锭的节奏里插一段开背包+挪光标。卡在 2x2 格里的 leftover
     * 不会丢: 交易自然结束 (达标 / 金锭耗尽) 后本钩子返回 false, 基类照常清。
     */
    @Override
    protected boolean shouldDeferCraftingGridClear(AltoClef mod) {
        return _startedTrading
                && StorageHelper.countItemInInventoryReal(mod, Items.GOLD_INGOT) > 0;
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
        // (2026-09-14 盾牌看门狗让位) 交易流程进行中每 tick 刷新心跳 (见字段注释)。
        if (goldIngots > 0) {
            _lastTradeFlowMs = System.currentTimeMillis();
        }

        // (2026-09-13 交易掉物捡拾升级) 先捡眼前的交易战利品再谈别的。注意
        // 这里只换"本层返回的子任务", 本任务实例不销毁, _startedTrading(static)
        // 不丢 → 捡完回来继续交易, 不会触发 32 金锭收集 (用户红线)。
        Optional<Entity> barterDrop = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                e -> e instanceof ItemEntity ie && ie.isAlive() && ie.isInRange(mod.getPlayer(), 16)
                        && isValuableBarterDrop(ie.getStack()),
                ItemEntity.class);
        if (barterDrop.isPresent()) {
            // (2026-09-14 用户定位) 猪灵丢出战利品 = 上一笔交易已成功。掉物在
            // 地上的每一 tick 都重置交易超时——捡拾期间 2s barterTimeout 绝不
            // 到期 (084153 场次: 贴身 1.7 格, 腾空掉物朝玩家飞、撞身入包仅
            // 10 tick, 捡拾任务空手而归, 超时在窗口内到期 → 刚做完成功交易
            // 的猪灵被误拉黑 "Failed bartering, blacklisting")。
            _tradeTask.notifyBarterLootCycle();
            _pickingUpLoot = true;
            setDebugState("Picking up piglin barter drop");
            return new PickupDroppedItemTask(new ItemTarget[]{
                    new ItemTarget(Items.POTION, 1),
                    new ItemTarget(Items.SPLASH_POTION, 1),
                    new ItemTarget(Items.ENDER_PEARL, 1),
                    new ItemTarget(Items.OBSIDIAN, 1),
                    new ItemTarget(Items.CRYING_OBSIDIAN, 1)
            }, true);
        }
        if (_pickingUpLoot) {
            // 掉物从候选消失: 多半已被原版吸附机制收进背包 (贴身交易时猪灵
            // 朝玩家抛物, 高概率撞身入包, 捡拾任务根本来不及导航)。捡拾周期
            // 结束, 给交易子任务一个全新的超时窗口, 重新贴近续下一笔交易。
            _pickingUpLoot = false;
            _tradeTask.notifyBarterLootCycle();
            Debug.logToFile("altoclef_diag.log",
                    "[PiglinTrade]\tloot cycle done (drop gone, likely vacuumed into inventory) — barter timeout reset");
        }

        // If we have no piglin nearby, explore until we find piglin.
        // (2026-09-18) 幼年猪灵不算"发现": 堡垒 (bastion) 里大多是幼年猪灵 +
        // 蛮兵, 幼年不能交易但会让 entityFound 恒真 → bot 被钉死在堡垒里
        // wander, 永不切回探索找成年猪灵 (173821 会话 17:54~17:56 no-target
        // 洪流 + 偶发单笔成功, 与此完全吻合)。
        if (!mod.getEntityTracker().entityFound(
                e -> !(e instanceof LivingEntity le && le.isBaby()), PiglinEntity.class)) {
            // (2026-09-18 追最后已知位置) 20s 感知记忆过期但仍在 30s 宽限期内
            // → 继续交易子任务 (其内部会走向最后已知位置), 而不是掉回探索。
            // 场景: bot 在猪灵头顶下挖接近, 视线/声音断流满 20s 被判丢失
            // (20260918-154845 会话 15:52:22 实锤), 其实 bot 一直知道它在哪。
            Entity stalePiglin = mod.getEntityTracker().getStaleKnownEntity(PiglinEntity.class);
            if (stalePiglin != null) {
                setDebugState("Chasing piglin last-known position");
                return _tradeTask;
            }
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
        // (2026-09-18 黑名单 TTL) 拉黑原先会话级永久 —— 视野内猪灵全被拉黑后,
        // entityFound(PiglinEntity) 恒真, 外层永不切回探索模式, bot 无限
        // "wander→选目标→池空" 再也不找新猪灵 (165136 会话用户实测)。
        // 改为 60s 过期自动解禁: 短期避开失败对象, 长期不锁死交易能力。
        private static final long BLACKLIST_TTL_MS = 60_000;
        private final HashMap<Entity, Long> _blacklistedAtMs = new HashMap<>();

        private void blacklist(Entity entity) {
            _blacklisted.add(entity);
            _blacklistedAtMs.put(entity, System.currentTimeMillis());
        }

        private boolean isBlacklisted(Entity entity) {
            Long at = _blacklistedAtMs.get(entity);
            if (at == null) return false;
            if (System.currentTimeMillis() - at > BLACKLIST_TTL_MS) {
                _blacklisted.remove(entity);
                _blacklistedAtMs.remove(entity);
                Debug.logToFile("altoclef_diag.log",
                        "[PiglinTrade]\tblacklist expired, retrying piglin " + entity.getBlockPos().toShortString());
                return false;
            }
            return true;
        }
        private Entity _currentlyBartering = null;
        // (2026-09-18 蛮兵拒选节流日志, 用户"没有日志吗, 注意节流") getEntityTarget
        // 的蛮兵过滤原先是静默 return false, 实跑完全无法验证。落盘不进聊天
        // ([Diag-creeper] 先例), 2s 节流。
        private long _lastBruteGuardLogMs = 0;
        // (2026-09-18 空池取证) 5s 节流。
        private long _lastEmptyPoolDiagMs = 0;
        // (2026-09-14 212757 场次 误拉黑宽限窗) 上一次"猪灵丢出战利品"边沿的
        // 墙钟时间。丢战利品 = 上一笔交易成功的铁证; 之后即使 2s barterTimeout
        // 因任务切换延迟 (见 notifyBarterLootCycle 注释) 到期, 也不该拉黑刚
        // 做完成功交易的猪灵。真实交易失败不会产生战利品, 不会误进此宽限。
        private long _lastLootCycleWallMs = 0;

        /**
         * (2026-09-14 掉物吸附误拉黑) 猪灵丢出战利品 = 上一笔交易成功的明确信号。
         * 父层在捡拾期间每 tick / 捡拾结束的边沿调用: 重置 barterTimeout 与
         * intervalTimeout, 保证"捡战利品"这段成功后的合法空窗不会吃掉 2 秒
         * 超时, 导致刚做完交易的猪灵被误拉黑。
         * (2026-09-14 212757 场次补记) 边沿重置本身会被 Task.tickInternal 的
         * 2s 横跳去抖拖住回切 (捡拾僵尸任务霸占 _sub, 期间零 interact tick),
         * 单次边沿重置挡不住超时到期 → 另加 3s 墙钟宽限窗兜底 (拉黑分支)。
         */
        public void notifyBarterLootCycle() {
            _lastLootCycleWallMs = System.currentTimeMillis();
            _barterTimeout.reset();
            _intervalTimeout.reset();
        }

        public PerformTradeWithPiglin() {
            // (2026-09-13 用户"交易时距离远了点, 再靠近") 3 → 2: 接近距离取
            // 2 格 (射程 3.2 内, 交互判定稳过), 站位贴到人类交易的自然距离,
            // 准心也更容易落在猪灵身上。
            super(2);
        }

        // (2026-09-13 用户"交易距离没生效"根因) approach 目标虽改成 2 格, 但
        // 父类交互分支的进入门槛 = 射程 3.2 —— 还没走到 2 格, 每 tick 重评估
        // 就在 3.2 格切进交互开丢金锭, maintainDistance 形同虚设。收紧本任务
        // 的交互门槛: 进 2.0 / 出 2.5 (迟滞防边界横跳), 真正贴脸交易。
        @Override
        protected double getInteractEnterDistance(AltoClef mod) {
            return 2.0;
        }

        @Override
        protected double getInteractExitDistance(AltoClef mod) {
            return 2.5;
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
                    return !isBlacklisted(entity);
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
                // (2026-09-14 212757 场次 宽限窗) 距上次战利品边沿 <3s = 刚做完
                // 一笔成功交易, 超时到期多半是任务切换延迟 (去抖吞掉回切) 造成
                // 的空窗, 不拉黑, 只重开 2s 窗口续交。
                if (System.currentTimeMillis() - _lastLootCycleWallMs < 3000) {
                    _barterTimeout.reset();
                } else {
                    // We failed bartering.
                    Debug.logMessage("Failed bartering with current piglin, blacklisting.");
                    // (2026-09-18 补取证) 上面这条只进聊天 HUD 不落盘, 连环拉黑
                    // 在文件里全盲 (171546 会话: 无 loot 记录却 no-target 洪流,
                    // 疑"丢金锭失败 → 2s 超时连环拉黑", 无法确证)。
                    Debug.logToFile("altoclef_diag.log", "[PiglinTrade]\tbarter timeout -> blacklist piglin "
                            + _currentlyBartering.getBlockPos().toShortString()
                            + " isTradingPiglin=" + EntityHelper.isTradingPiglin(_currentlyBartering)
                            + " dist=" + String.format("%.1f", Math.sqrt(_currentlyBartering.squaredDistanceTo(mod.getPlayer()))));
                    blacklist(_currentlyBartering);
                    _barterTimeout.reset();
                    _currentlyBartering = null;
                    return null;
                }
            }

            if (AVOID_HOGLINS && _currentlyBartering != null && !EntityHelper.isTradingPiglin(_currentlyBartering)) {
                Optional<Entity> closestHoglin = mod.getEntityTracker().getClosestEntity(_currentlyBartering.getPos(), HoglinEntity.class);
                if (closestHoglin.isPresent() && closestHoglin.get().isInRange(entity, HOGLIN_HARD_TRADE_RADIUS)) {
                    Debug.logMessage("Aborting further trading because a hoglin showed up");
                    blacklist(_currentlyBartering);
                    _barterTimeout.reset();
                    _currentlyBartering = null;
                }
            }

            // (2026-09-18 蛮兵入场即停, 用户) 交易中途蛮兵摸进【8 格】→ 立即拉黑
            // 终止 (原 32 格, 17:05 用户拍板缩小: 32 格硬禁导致堡垒区变相禁交易)。
            // 不查猪灵交易状态 (与 hoglin 分支不同): 蛮兵贴脸时任何交易都不该继续。
            // 8~32 格不打断: 交易战利品已在路上, 打断 = 白丢金锭。
            if (_currentlyBartering != null) {
                Optional<Entity> closestBrute = mod.getEntityTracker().getClosestEntity(_currentlyBartering.getPos(), PiglinBruteEntity.class);
                boolean bruteHardClose = closestBrute.isPresent()
                        && (closestBrute.get().isInRange(_currentlyBartering, BRUTE_HARD_TRADE_RADIUS)
                            || closestBrute.get().isInRange(mod.getPlayer(), BRUTE_HARD_TRADE_RADIUS));
                if (closestBrute.isPresent() && bruteHardClose) {
                    Debug.logMessage("Aborting trade: piglin brute within " + (int) BRUTE_HARD_TRADE_RADIUS + " blocks");
                    blacklist(_currentlyBartering);
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

        /** 蛮兵是否在 entity 的 radius 内 (两层蛮兵语义的公共判定)。 */
        private boolean bruteNear(AltoClef mod, Entity entity, double radius) {
            Optional<Entity> closestBrute = mod.getEntityTracker().getClosestEntity(entity.getPos(), PiglinBruteEntity.class);
            return closestBrute.isPresent() && closestBrute.get().isInRange(entity, radius);
        }

        /** hoglin 是否在 entity 的 radius 内 (两层 hoglin 语义的公共判定)。 */
        private boolean hoglinNear(AltoClef mod, Entity entity, double radius) {
            Optional<Entity> closestHoglin = mod.getEntityTracker().getClosestEntity(entity.getPos(), HoglinEntity.class);
            return closestHoglin.isPresent() && closestHoglin.get().isInRange(entity, radius);
        }

        @Override
        protected Optional<Entity> getEntityTarget(AltoClef mod) {
            // (2026-09-15 111618 场次 approach 期误拉黑) "猪灵欣赏中重置
            // barterTimeout"原先只写在 onEntityInteract 分支里 —— approach
            // 模式不进交互分支, 期间零重置。实测: 猪灵漂到 3.2 格 (> 出 2.5
            // 迟滞带) 逼进 approach, 随后在 2.1-2.2 悬停 6 秒 (进 2.0 门槛够
            // 不着), 期间猪灵一直在欣赏金锭、欣赏结束还丢出了战利品 (被吸附
            // 入包, 没产生 loot 边沿); 回到交互的首个 tick 超时已 6s > 2s、
            // 距上次 loot 边沿 12.5s > 3s 宽限 → 刚完成交易的猪灵被拉黑。
            // 上移到本方法 (每 tick 两分支都走) 后: 欣赏中 barterTimeout 恒
            // 被重置, 只有"猪灵吃锭后连续 2 秒不欣赏也不出货"才拉黑。
            if (_currentlyBartering != null && EntityHelper.isTradingPiglin(_currentlyBartering)) {
                _barterTimeout.reset();
            }
            // (2026-09-15 114524 场次 补口) 上一修只盖"欣赏中"。实测: 猪灵
            // 边欣赏边爬坡跑远 (dist 7.7), 丢完战利品后不欣赏了, bot approach
            // 3.7s 才贴回 —— 距上次重置 3.75s, 恰好超出 2s 超时+3s 宽限的
            // 5s 覆盖 0.75s → 又拉黑刚完成交易的猪灵。补上语义正解: 猪灵不
            // 在交互门槛 (进 2.0, 即交互分支真正能运行的范围) 内时
            // barterTimeout 不计时 —— bot 在赶路, 不是在交易失败; 超时只在
            // "贴脸 + 不欣赏 + 无战利品"时累计, 2s 即真实失败 (金锭没被吃/
            // 欣赏被打断且不出货)。
            else if (_currentlyBartering != null
                    && !_currentlyBartering.isInRange(mod.getPlayer(), getInteractEnterDistance(mod))) {
                _barterTimeout.reset();
            }

            // Ignore trading piglins
            // (2026-09-18 17:05 两层蛮兵语义, 用户拍板) 先在"蛮兵 32 格无辐射"
            // 的猪灵里选; 全被辐射才退到"8 格内无蛮兵"的池子 (硬底线)。堡垒区
            // 蛮兵常驻, 单层 32 格硬禁 = 贴脸也无猪灵可交易 (165136 会话 16:53:58 实锤)。
            java.util.function.Predicate<Entity> baseFilter = entity -> {
                if (isBlacklisted(entity)
                        || EntityHelper.isTradingPiglin(entity)
                        || (entity instanceof LivingEntity && ((LivingEntity) entity).isBaby())
                        || (_currentlyBartering != null && !entity.isInRange(_currentlyBartering, PIGLIN_NEARBY_RADIUS))) {
                    return false;
                }
                return true;
            };
            // 第一层: 蛮兵 32 / hoglin 64 双软偏好池。
            Optional<Entity> found = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                    baseFilter
                            .and(entity -> !bruteNear(mod, entity, BRUTE_AVOID_TRADE_RADIUS))
                            .and(entity -> !hoglinNear(mod, entity, HOGLIN_AVOID_TRADE_RADIUS)),
                    PiglinEntity.class);
            // 第二层 (硬底线): 蛮兵 / hoglin 均贴近 8 格内才排除。
            if (found.isEmpty()) {
                found = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                        baseFilter.and(entity -> {
                            if (bruteNear(mod, entity, BRUTE_HARD_TRADE_RADIUS)
                                    || hoglinNear(mod, entity, HOGLIN_HARD_TRADE_RADIUS)) {
                                long nowMs = System.currentTimeMillis();
                                if (nowMs - _lastBruteGuardLogMs > 2000) {
                                    _lastBruteGuardLogMs = nowMs;
                                    Debug.logToFile("altoclef_diag.log", "[PiglinTrade]\tskip piglin "
                                            + entity.getBlockPos().toShortString() + " — brute/hoglin within hard radius "
                                            + (int) BRUTE_HARD_TRADE_RADIUS);
                                }
                                return false;
                            }
                            return true;
                        }),
                        PiglinEntity.class);
            }
            if (found.isEmpty()) {
                if (_currentlyBartering != null && (isBlacklisted(_currentlyBartering) || !_currentlyBartering.isAlive())) {
                    _currentlyBartering = null;
                }
                found = Optional.ofNullable(_currentlyBartering);
            }
            // (2026-09-18 空池取证) no-target 的分类原因此前完全不可见 (黑名单/
            // 蛮兵两轮排查都靠"猜测+排除法")。空池时按过滤链顺序给每个猪灵
            // 归因, 5s 节流落盘。
            if (found.isEmpty()) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - _lastEmptyPoolDiagMs > 5000) {
                    _lastEmptyPoolDiagMs = nowMs;
                    List<PiglinEntity> all = mod.getEntityTracker().getTrackedEntities(PiglinEntity.class);
                    int admiring = 0, baby = 0, blacklisted = 0, farFromBartering = 0, hoglinBlocked = 0, bruteHard = 0;
                    for (Entity p : all) {
                        if (isBlacklisted(p)) { blacklisted++; continue; }
                        if (EntityHelper.isTradingPiglin(p)) { admiring++; continue; }
                        if (p instanceof LivingEntity le && le.isBaby()) { baby++; continue; }
                        if (_currentlyBartering != null && !p.isInRange(_currentlyBartering, PIGLIN_NEARBY_RADIUS)) { farFromBartering++; continue; }
                        Optional<Entity> hoglin = AVOID_HOGLINS
                                ? mod.getEntityTracker().getClosestEntity(p.getPos(), HoglinEntity.class) : Optional.empty();
                        if (hoglin.isPresent() && hoglin.get().isInRange(p, HOGLIN_HARD_TRADE_RADIUS)) { hoglinBlocked++; continue; }
                        if (bruteNear(mod, p, BRUTE_HARD_TRADE_RADIUS)) { bruteHard++; continue; }
                        // 走到这里 = 只被 32 格软偏好挡的 (两层池都有它才算"可用")
                    }
                    Debug.logToFile("altoclef_diag.log", "[PiglinTrade-DIAG]\tno target: tracked=" + all.size()
                            + " admiring=" + admiring + " baby=" + baby + " blacklisted=" + blacklisted
                            + " farFromBartering=" + farFromBartering + " hoglinBlocked=" + hoglinBlocked
                            + " bruteHard=" + bruteHard
                            + " currentlyBartering=" + (_currentlyBartering == null ? "null" : _currentlyBartering.getBlockPos().toShortString())
                            + " player=" + mod.getPlayer().getBlockPos().toShortString());
                }
            }
            return found;
        }

        @Override
        protected String toDebugString() {
            return "Trading with piglin";
        }
    }

}
