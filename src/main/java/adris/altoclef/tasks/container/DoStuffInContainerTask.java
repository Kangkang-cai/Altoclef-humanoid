package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasks.slot.ThrowCursorTask;
import adris.altoclef.tasksystem.CursorArbiter;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Optional;


/**
 * Interacts with a container, obtaining and placing one if none were found nearby.
 */
public abstract class DoStuffInContainerTask extends Task {

    private final ItemTarget _containerTarget;
    private final Block[] _containerBlocks;

    private final PlaceBlockNearbyTask _placeTask;
    // If we decided on placing, force place for at least 10 seconds
    private final TimerGame _placeForceTimer = new TimerGame(10);
    // If we just placed something, stop placing and try going to the nearest container.
    private final TimerGame _justPlacedTimer = new TimerGame(3);
    private BlockPos _cachedContainerPosition = null;
    private Task _openTableTask;

    // (2026-09-14 腾位闸门实例缓存) 两处"满包腾位"闸门此前每 tick new
    // EnsureFreeInventorySlotTask, 违反铁律 #3(侧线/清障子任务必须实例缓存
    // keep-alive): 内部状态(穿甲搬运中间态等)随实例丢弃, 上层任何 STOP 都让
    // 两拍搬运从头再来 → cursor air<->boots 每 2s 死循环 (20260914-221044 实锤)。
    private EnsureFreeInventorySlotTask _freeTask = null;

    private EnsureFreeInventorySlotTask acquireFreeTask() {
        if (_freeTask == null) {
            _freeTask = new EnsureFreeInventorySlotTask();
        }
        return _freeTask;
    }

    /**
     * (2026-09-15) 腾位是否正在进行中: 光标非空且被腾位链租用 (垃圾已上光标,
     * 正由 ThrowCursorTask 瞄准/挖掘/丢出)。此刻背包里"多出来的空位"是垃圾刚
     * 被拿起的槽, 不代表腾位完成 —— 用它判定会翻面走路/收产物, 把丢弃掐断
     * (095835 会话 10:00 实锤, ThrowCursor aim 每 2s 被打断, 进度清零)。
     */
    private boolean isFreeingInFlight() {
        return !StorageHelper.getItemStackInCursorSlot().isEmpty()
                && CursorArbiter.isHeldBy(CursorArbiter.INTENT_RELOCATE);
    }

    public DoStuffInContainerTask(Block[] containerBlocks, ItemTarget containerTarget) {
        _containerBlocks = containerBlocks;
        _containerTarget = containerTarget;

        _placeTask = new PlaceBlockNearbyTask(_containerBlocks);
    }

    public DoStuffInContainerTask(Block containerBlock, ItemTarget containerTarget) {
        this(new Block[]{containerBlock}, containerTarget);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _freeTask = null;
        if (_openTableTask == null) {
            // (2026-09-11 开容器目标错位修复) 交互目标优先用父层本 tick 选出的
            // _cachedContainerPosition (canReach 过滤+实时有效), 而不是 tracker 的
            // "最近同类方块"——后者无 canReach 校验, 缓存里有已挖掉/被埋不可交互的
            // 旧容器位时, 交互任务会朝它导航到天荒地老。132539 场次实锤: walk 目标
            // = -134,14,214(刚放的真炉, canReach), 交互目标= -134,15,214(tracker 最近,
            // 旧炉残位) → NAV-STUCK active-stale 冻结, wander 兜底 9s 一轮不收敛。
            // 父层每 tick 先于子任务重算并刷新该字段, 取到时必非 null 且新鲜。
            _openTableTask = new DoToClosestBlockTask(
                    (java.util.function.Supplier<Vec3d>) null,
                    // (2026-09-11 熔炉误判事故) 容器交互关闭"失败拉黑+wander": 站在
                    // 炉前重试点击即可, 拉黑会让父任务三路查找误判"无可用容器"而
                    // 重做熔炉 (炉内物料成孤儿)。
                    pos -> new InteractWithBlockTask(pos, false),
                    pos -> Optional.ofNullable(_cachedContainerPosition),
                    null,
                    _containerBlocks);
        }

        mod.getBlockTracker().trackBlock(_containerBlocks);

        // Protect container since we might place it.
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(ItemHelper.blocksToItems(_containerBlocks));
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // If we're placing, keep on placing.
        if (mod.getItemStorage().hasItem(ItemHelper.blocksToItems(_containerBlocks)) && _placeTask.isActive() && !_placeTask.isFinished(mod)) {
            setDebugState("Placing container");
            return _placeTask;
        }

        if (isContainerOpen(mod)) {
            // (2026-09-03 满包收物死锁修复, 用户"丢出撞面前方块弹回→入包→又丢死循环")
            // 容器界面打开、正要收产物时若背包无真实空位, 先腾位掉一件(丢垃圾/挖面前
            // 方块), 腾出空格再收, 避免 ReceiveCraftingOutputSlotTask 满包时派
            // ThrowCursorTask 挖面前方块 → DestroyBlockTask closeScreen 关界面 → 父链
            // isContainerOpen=false 切回 walk&open 重开界面, 挖方块永远被打断 → 兜底
            // 直接丢 → 撞面前方块弹回背包 → 无限循环。此刻界面开着, 由 EnsureFree
            // 负责真正腾出空格; EnsureFree 内部能合并/丢垃圾/挖面前方块, 腾完再收。
            // 判定用【背包真实空槽数==0】(getSlotThatCanFit 依赖光标物品会误判)。
            // (2026-09-15 freeingInFlight) 垃圾刚从槽里拿上光标 (腾位进行中, 待
            // ThrowCursor 丢出) 的瞬间背包就"多了一个空位", 仅凭 getEmptySlot()<0
            // 判定会在这里翻面收产物/走路, 把瞄准中的丢弃掐断 (095835 会话 10:00
            // 实锤: ThrowCursor aim 每 2s 被 walk/收产物打断, 进度清零)。光标被
            // 腾位链租用 = 腾位仍在进行, 继续维持腾位子树。
            if (mod.getPlayer() != null
                    && (mod.getPlayer().getInventory().getEmptySlot() < 0 || isFreeingInFlight())) {
                // 关键: 必须先关掉容器界面, 否则 EnsureFree→ThrowCursorTask→DestroyBlockTask
                // 挖面前方块时 closeScreen 关界面, 下一 tick 父链又把它切回 walk&open,
                // 挖方块永远被掐断(根因就是这里)。主动关界面后, 父链 isContainerOpen=false
                // 看到的是"我们自己在腾位", 会停留(不切 walk&open 抢先), 腾完那件掉、
                // 背包空出一个格, 再回来 open 收产物。等价于"关界面→腾位→重开"。
                StorageHelper.closeScreen();
                setDebugState("Freeing inventory before receiving output");
                return acquireFreeTask();
            }
            return containerSubTask(mod);
        }

        // infinity if such a container does not exist.
        double costToWalk = Double.POSITIVE_INFINITY;

        // (2026-09-14 距离判据统一) 本函数内"容器可用性"只允许有一个距离上限:
        // getMaxWalkDistance (熔炉 40 格)。旧实现只在 L155 用它置空 nearest, 而
        // L194 的 isItemOwned(USE) 是距离-blind 的、L231 又用 p->true 无过滤查找 ——
        // 三个回合里前一个说"太远不用", 后两个把同一台 392 格外的炉捡回来。
        // 提取到函数级, 让 L231 复用同一上限 (默认子类 Double.MAX_VALUE → 平方为
        // Infinity, 判定恒真, 行为与旧版完全一致)。
        double maxWalk = getMaxWalkDistance(mod);
        double maxWalkSq = maxWalk * maxWalk;

        Optional<BlockPos> nearest;

        Vec3d currentPos = mod.getPlayer().getPos();
        BlockPos override = overrideContainerPosition(mod);

        if (override != null && mod.getBlockTracker().blockIsValid(override, _containerBlocks)) {
            // We have an override so go there instead.
            nearest = Optional.of(override);
        } else {
            // Track nearest container
            nearest = mod.getBlockTracker().getNearestTracking(currentPos, blockPos -> WorldHelper.canReach(mod, blockPos), _containerBlocks);
        }
        if (nearest.isEmpty()) {
            // If all else fails, try using our placed task
            nearest = Optional.ofNullable(_placeTask.getPlaced());
            if (nearest.isPresent() && !mod.getBlockTracker().blockIsValid(nearest.get(), _containerBlocks)) {
                nearest = Optional.empty();
            }
        }
        if (nearest.isEmpty()) {
            // (2026-08-30 分而治之: 眼前真实容器优先于缓存) 容器可能来自上一个
            // 中断的任务(如已放置的熔炉), tracker 因重启/中断丢失记忆 → nearest
            // 空了就误走"造新容器"。抢在造之前, 主动扫 bot 近场真实方块, 看到
            // 一个目标容器(熔炉就在眼前)直接过去用, 不重做。
            nearest = findNearbyContainerBlock(mod, currentPos);
            if (nearest.isPresent()) {
                mod.getBlockTracker().trackBlock(_containerBlocks);
            }
        }
        if (nearest.isPresent()) {
            costToWalk = BaritoneHelper.calculateGenericHeuristic(currentPos, WorldHelper.toVec3d(nearest.get()));
            // (2026-09-03 远熔炉该现做而非远走, 用户"定位了特别远的熔炉, 还不如现做一个")
            // 已有容器超过 walk 距离上限(熔炉=40格)时, 把 walk 成本抬到远大于现做
            // 成本, 强制走下方"现做/放置容器"分支。Baritone 启发式对上百格平坦/水路
            // 路线系统性低估, 曾导致 bot 舍近求远去上百格熔炉(日志 10:35 Walking to
            // container 118,70,449)。近炉(≤上限)照常复用, 不受影响。
            if (nearest.get().getSquaredDistance(currentPos) > maxWalkSq) {
                costToWalk = Float.MAX_VALUE;
                // 视同为"无可用近容器": 置空 nearest 让下方 nearestMissing=true,
                // 即使后备背包无熔炉物品也走现做/获取容器分支, 而非傻走去远炉。
                nearest = Optional.empty();
            }
        }

        // Make a new container if going to the container is a pretty bad cost.
        // Also keep on making the container if we're stuck in some
        if (costToWalk > getCostToMakeNew(mod)) {
            _placeForceTimer.reset();
        }
        // (2026-09-07 收熔炉死循环根因) 子类可声明"附近没容器时绝不允许就地新造"。
        // 默认分支的语义是"我要用容器干活, 没有就造一个再用", 对"取走/拆掉眼前
        // 已有容器"的子类 (EmptyFurnaceTask) 是纯错误: 它跑去放一个新熔炉, 打开
        // 空炉立刻判定"内容已取尽", 炉子却留在原地 → 触发条件自持, 永不收敛
        // (2026-09-07 23:07 日志: 60 秒内 3 轮 "Furnace emptied", 从未拆炉)。
        boolean nearestMissing = nearest.isEmpty();
        boolean placeForceActive = !_placeForceTimer.elapsed()
                && StorageHelper.countItemsInInventoryReal(mod, _containerTarget.getMatches()) > 0;
        if (nearestMissing || placeForceActive) {
            if (!isAllowedToPlaceNewContainer()) {
                setDebugState("No container to take from, placing is not allowed here");
                return null; // 交给子类自行兜底 (如直接拆走可挖的容器)。
            }
            // It's cheaper to make a new one, or our only option.

            // We're no longer going to our previous container.
            _cachedContainerPosition = null;

            // (2026-08-31 统一所有权模型) 判定"要不要现场造/拿一张新的同类容器"
            // 一律用统一的 USE 语义: 背包真有 或 世界里存在有效同类方块(已放置
            // 的台子/熔炉) 都算"我有可用的", 就不去再合成一张。旧逻辑
            // `!hasItem(_containerTarget)` 只看背包物品 —— 台子一放下就变成方块、
            // 不再是背包物品 → 恒 true → 反复"Getting container item → 现场再造",
            // 每轮再造进 2x2 合成格又被网格清理器打断, 造出十张台子/横跳
            // (日志 TaskPingPong 实锤)。统一用 USE, 一台即可复用, 不用给台子
            // 单独维护任何状态。
            if (!StorageHelper.isItemOwned(mod, StorageHelper.Ownership.USE, _containerTarget.getMatches())) {
                setDebugState("Getting container item");
                Debug.logTrace("craft-place-get", "要造/取新容器 " + _containerTarget.toString()
                        + ", isItemOwned(USE)=" + StorageHelper.isItemOwned(mod, StorageHelper.Ownership.USE, _containerTarget.getMatches())
                        + ", 背包实时数=" + StorageHelper.countItemsInInventoryReal(mod, _containerTarget.getMatches())
                        + ", nearest=" + nearest.orElse(null)
                        + ", maxWalk=" + maxWalk);
                return TaskCatalogue.getItemTask(_containerTarget);
            }

            if (StorageHelper.countItemsInInventoryReal(mod, _containerTarget.getMatches()) > 0) {
                // 台子真背在包里: 放下来用。
                setDebugState("Placing container...");
                Debug.logTrace("craft-place-reuse", "背包有容器 " + _containerTarget.toString() + ", 放下来用");
                _justPlacedTimer.reset();
                return _placeTask;
            }

            // 手头没有现成台子物品, 但世界里有(刚放/记忆中): 复用那一张, 不重造。
            BlockPos stillThere = _placeTask.getPlaced();
            if (stillThere != null && mod.getBlockTracker().blockIsValid(stillThere, _containerBlocks)) {
                setDebugState("Using the container we already placed");
                Debug.logTrace("craft-place-reuse", "复用已放置容器 " + _containerTarget.toString() + " @" + stillThere.toShortString());
                _cachedContainerPosition = stillThere;
                _placeForceTimer.reset();
                _justPlacedTimer.reset();
                // (2026-09-11) 直接交互刚验证过的 stillThere, 不走 tracker "最近"
                // (同 _openTableTask 错位修复: 最近位可能是已挖掉/被埋的旧容器位)。
                // (2026-09-11) 同 _openTableTask: 无游荡模式, 失败不拉黑容器。
                return new InteractWithBlockTask(stillThere, false);
            }
            // (2026-09-08 空放置修复, 用户"手上没有熔炉谁触发的放置?") 手头无
            // 容器物品时, "重新放置"是纯游荡: PlaceBlockNearbyTask 无物可放,
            // 只会 Wander→超时→放弃, 每轮呆住 5~20s, 还逼上层再合成一张
            // (18:06 实测: 炉就在脚边, canReach 过滤误判 nearest 空 → 走到这里
            // → 空放置 20s → 又做一张新炉, 场上越堆越多)。isItemOwned(USE)
            // 刚才为 true 说明 tracker 明确知道世界里有同类容器 → 去开它
            // (不带 canReach 过滤, 走到跟前自然能开), 而不是空着手假装能放。
            // (2026-09-14 远炉横跳定案) 补上 getMaxWalkDistance 距离过滤: 旧版
            // `p->true` 会把 L163 刚按 40 格上限排除掉的 392 格远炉捡回来, 与
            // L202 的距离-blind `isItemOwned(USE)` 一起造成"走去远炉 ↔ 现做炉"
            // 每 1~4 秒横跳 (根因是 isBlockPerceivable 对射程外目标的假阳性,
            // 已在 VisibilityHelper 加距离门; 这里是调用侧的第二道闸)。
            Optional<BlockPos> trackedExisting = mod.getBlockTracker()
                    .getNearestTracking(currentPos, blockPos -> blockPos.getSquaredDistance(currentPos) <= maxWalkSq, _containerBlocks);
            if (trackedExisting.isPresent()) {
                setDebugState("Using existing container (tracker knows one)");
                Debug.logTrace("craft-place-reuse", "复用 tracker 已知容器 " + _containerTarget.toString()
                        + " @" + trackedExisting.get().toShortString()
                        + ", dist=" + (int) Math.sqrt(trackedExisting.get().getSquaredDistance(currentPos))
                        + ", maxWalk=" + maxWalk);
                _cachedContainerPosition = trackedExisting.get();
                _placeForceTimer.reset();
                _justPlacedTimer.reset();
                // (2026-09-11) 同 _openTableTask: 无游荡模式, 失败不拉黑容器。
                return new InteractWithBlockTask(trackedExisting.get(), false);
            }
            // tracker 完全没货 (理论上到不了: isItemOwned(USE)=true 需要世界里有):
            // 兜底回"获取容器物品"重造, 别空手放置。
            setDebugState("Getting container item (no usable container found)");
            Debug.logTrace("craft-place-get", "距离上限 " + maxWalk + " 格内没有容器, 世界与手头都没有可用容器, 回去造 " + _containerTarget.toString());
            return TaskCatalogue.getItemTask(_containerTarget);
        }

        // This is insanely cursed.
        // TODO: Finish committing to optionals, this is ugly.
        _cachedContainerPosition = nearest.get();

        // (2026-09-03 满包收物死锁修复, 用户"丢出撞面前方块弹回→入包→又丢死循环")
        // 打开容器前先保证背包至少有一个真实空位。根因链: 满包收合成产物时,
        // ReceiveCraftingOutputSlotTask 派 EnsureFreeInventorySlotTask → ThrowCursorTask
        // 挖面前方块; 但容器界面开着, 挖方块 DestroyBlockTask 会 closeScreen() 关界面,
        // 下一 tick 父类 isContainerOpen=false → 立刻切回 walk&open 重开界面, 把挖方块
        // 子链掐断 → 面前方块永远挖不完 → 兜底直接丢 → 撞面前方块弹回背包 → 无限循环
        // (tasks.log 14:11 实锤, Destroy block 97,-45,449 每 tick 被打断)。
        // 在"打开容器前"加闸门: 若背包无真实空位, 先回 EnsureFreeInventorySlotTask
        // 腾位掉一件(此刻界面未开, 父链停留在此任务上, 挖面前方块能完整跑完) —
        // 腾出空格后父链才真正走去打开容器, 不再死锁。
        // 判定用【背包真实空槽数==0】(而非 getSlotThatCanFit, 后者依赖光标物品 —
        // 满包收物时光标常为空, 会误判放得下而跳过腾位)。
        // (2026-09-15 freeingInFlight) 同上: 垃圾上光标腾位进行中别翻去走路。
        if (mod.getPlayer() != null
                && (mod.getPlayer().getInventory().getEmptySlot() < 0 || isFreeingInFlight())) {
            setDebugState("Freeing inventory before opening container");
            return acquireFreeTask();
        }

        // Walk to it and open it

        // Wait for food
        if (mod.getFoodChain().isTryingToEat()) {
            setDebugState("Waiting for eating...");
            return null;
        }
        setDebugState("Walking to container... " + nearest.get().toShortString());

        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            // (2026-09-14 CursorArbiter 接线) 光标被腾位链租用 = 下级正在做
            // "拿起→放进盔甲槽"的两拍中转, 不是外来滞留残留。此处点回背包会把
            // 刚腾出的空位又填上, 与腾位链互相拆台 (20260914-221044 实锤: 拿起
            // iron_boots → 本守卫 getSlotThatCanFit 命中新空位 → ClickSlot 点回
            // → 每 2s 一轮)。与 CraftInTableTask 收产物分支同款豁免: 维持腾位
            // 子树, 让两拍走完。
            if (CursorArbiter.isHeldBy(CursorArbiter.INTENT_RELOCATE)) {
                setDebugState("Cursor leased by free-space chain, holding free task");
                return acquireFreeTask();
            }
            setDebugState("Clearing cursor slot (otherwise this causes BIG problems)");
            // (2026-09-01 同 LootContainerTask 根因) 删 `.or(getGarbageSlot)` —
            // 背包满时点垃圾槽只交换不扔, 光标杂物永远腾不掉 → 走 EnsureFree 真正扔出。
            // (2026-09-03 满包腾位时"拿垃圾→被点回"死循环修复) 这个分支原本把光标物品
            // "放回背包某个能放下的真槽"——但若该物品正是满包腾位时从 slot 里主动拿起的
            // **垃圾**(攻击家化等低价值物), 放回等于抵消腾位, 且与游走腾位/挖方块链互相
            // 拆台(日志 14:31 t=598 反复: EnsureFree 拿 slot16→Throwing→DestroyBlock→父容器
            // Clearing cursor→Click 点回 slot16→又拿…… 2秒一轮死循环)。因此这里不再"放回",
            // 而是干脆把光标垃圾真正【扔出】青远, 由 ThrowCursorTask(含挖面前方块兜底)处理。
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false);
            if (toMoveTo.isEmpty() || ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                return new ThrowCursorTask();
            } else {
                return new ClickSlotTask(toMoveTo.get());
            }
        }
        return _openTableTask;
        //return new GetToBlockTask(nearest, true);
    }

    public ItemTarget getContainerTarget() {
        return _containerTarget;
    }

    /**
     * 附近没有可用容器时, 是否允许就地造/放一个新的。
     *
     * 默认 true — 合成/熔炼等"我要用容器"的场景本来就该自己造一张。
     * (2026-09-07) 语义为"取走/拆掉眼前已有容器"的子类 (EmptyFurnaceTask) 必须
     * 覆写返回 false: 对新造出来的容器做"取尽并拆走"是自相矛盾的, 新放的容器
     * 又让上层的"附近还有容器可收"判定恒成立 → 死循环。
     */
    protected boolean isAllowedToPlaceNewContainer() {
        return true;
    }

    /**
     * (2026-08-30 分而治之: 眼前真实容器优先于缓存) 当 tracker 无目标容器记忆
     * (如上一个中断任务留下的熔炉), 主动扫 bot 附近真实方块, 返回第一个可到达的
     * 目标容器。仅当遍历式查找失败时调用(触发频率极低), 故直接用世界扫描可接受。
     *
     * (2026-09-11 熔炉误判事故) 去掉 WorldHelper.canReach 过滤: 该函数实际是
     * "BlockTracker 黑名单取反"(非几何距离)。交互任务在 bot 冻结时会把眼前熔炉
     * requestBlockUnreachable → 此处带过滤就扫不出 1 格外的炉 → 父任务误判
     * "无可用容器"→ 重做熔炉 (22:51 日志实锤, 炉内 6 生羊肉成孤儿)。4 格内的
     * 同类容器几乎不可能"真不可达", 黑名单多为瞬时误标, 直接采信真实方块。
     */
    private Optional<BlockPos> findNearbyContainerBlock(AltoClef mod, Vec3d center) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return Optional.empty();
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int radius = 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    Block block = client.world.getBlockState(pos).getBlock();
                    for (Block containerBlock : _containerBlocks) {
                        if (block == containerBlock) {
                            return Optional.of(pos);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    // Virtual
    protected BlockPos overrideContainerPosition(AltoClef mod) {
        return null;
    }

    protected BlockPos getTargetContainerPosition() {
        return _cachedContainerPosition;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_containerBlocks);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoStuffInContainerTask task) {
            if (!Arrays.equals(task._containerBlocks, _containerBlocks)) return false;
            if (!task._containerTarget.equals(_containerTarget)) return false;
            return isSubTaskEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing stuff in " + _containerTarget + " container";
    }

    protected abstract boolean isSubTaskEqual(DoStuffInContainerTask other);

    protected abstract boolean isContainerOpen(AltoClef mod);

    protected abstract Task containerSubTask(AltoClef mod);

    protected abstract double getCostToMakeNew(AltoClef mod);

    /**
     * (2026-09-03 远容器该现做而非远走, 用户) 已有容器超过该距离(格)即被视作"不可达",
     * 强制走"现做/放置容器"分支。默认无限(不限制现行为), 子类可收紧(如
     * SmeltInFurnaceTask 熔炉=40格)。见 onTick 内 costToWalk 处的超距判断。
     */
    protected double getMaxWalkDistance(AltoClef mod) {
        return Double.MAX_VALUE;
    }
}
