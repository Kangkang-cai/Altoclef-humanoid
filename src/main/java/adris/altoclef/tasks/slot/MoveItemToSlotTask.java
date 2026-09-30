package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.FurnaceScreenHandler;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class MoveItemToSlotTask extends Task implements ITaskCanForce {

    // (2026-09-17 搬运在途登记) 光标上正被两拍搬运的目标物品表 (item -> 登记时间戳)。
    // 背景: 20260917-090520 场, bot 往熔炉塞 raw_iron 的两拍之间 (拿起→放下),
    // EnsureFreeInventorySlotTask 的丢弃级联把光标上的 raw_iron 当"外来滞留"整堆
    // 扔进坑 → 又派 Pickup 捡回 → 再塞再扔 ("扔了又捡"死循环, 用户确认症状)。
    // 塞料任务没有 CursorArbiter 租约 (RELOCATE 是腾位链自己持有的, 同 intent
    // 豁免对持租者本人无效), 故用独立在途表: 拿起目标物时登记 (1s 滑动过期),
    // 腾位链丢弃前查询让路。滑动过期 = Moving 实例每 tick 重建不空窗、
    // Moving 卡死/被停 1s 后保护自动解除, 不会永久遮蔽腾位。
    private static final Map<Item, Long> _inFlightCursorItems = new HashMap<>();
    // (2026-09-17 TTL 1s→3s) 对齐 CursorArbiter.LEASE_MS: 竞争链 (EnsureFree)
    // 连续上位期间 Moving 停 tick, 1s 过期会让保护在拉锯中途脱落; 3s 覆盖
    // EnsureFree 单轮丢弃动作的典型时长, Moving 卡死场景 3s 后仍自动解除。
    private static final long IN_FLIGHT_TTL_MS = 3000L;

    /** 光标物品是否正被某次两拍搬运持有 (腾位/丢弃派发前查询)。 */
    public static boolean isCursorItemInFlight(Item item) {
        if (item == null) return false;
        long now = System.currentTimeMillis();
        Long since = _inFlightCursorItems.get(item);
        return since != null && now - since <= IN_FLIGHT_TTL_MS;
    }

    private static synchronized void markInFlight(Item item) {
        if (item != null) _inFlightCursorItems.put(item, System.currentTimeMillis());
    }

    private final ItemTarget _toMove;
    private final Slot _destination;
    private final Function<AltoClef, List<Slot>> _getMovableSlots;

    // (2026-09-16 两拍搬运防拆, 20260916-103703 场次实锤) 搬运中间态被父级
    // 每 tick 重估翻面拆树的死循环: 内层 CraftInTableTask([diamond_pickaxe])
    // 的 "Getting container item" 子树正在收 crafting_table 输出, 背包满 →
    // EnsureFreeInventorySlotTask "穿背包铁靴腾格子" → Moving iron_boots
    // (PICKUP 已上光标、PLACE 未落地); 下一 tick 父级 "Child slot-move in
    // flight"/缺料判定 return _collectTask 翻面 → 本任务整棵 STOP, 靴子放回
    // 背包 → 收输出每轮重启、腾位每轮被拆, 8s/轮永动到用户手动退出
    // (10:39:15/23 两次 "Moving [iron_boots] interrupted by Collect Recipe
    // Resources diamond_pickaxe" 实锤)。
    // 修=ITaskCanForce: 中间态期间声明不可被父级翻面打断 (只挡 tickInternal
    // 换子任务, 不挡整棵树被上层替换, 无死锁面), 并带 3s 保险丝防搬运异常
    // 卡死时永久不可救。
    private static final long MOVE_FORCE_FUSE_MS = 3000;
    // (2026-09-30 保险丝按进度续期, detail/31) 进度 = 光标快照或目标槽快照变化。
    // 旧版 3s 保险丝从 onStart 一刀切, 且保护窗口只覆盖"光标持目标物∥拾取宽限":
    // 腾位穿甲的第一拍还在槽位冷却/同步等待时(光标仍空、宽限 500ms 已过)完全裸奔,
    // 父链翻面即拆树 → 胸甲穿戴 12 次/55s 零完成 (20260930-183102 实锤)。
    // 改两点: ① 保险丝从"最后一次真实进度"起算, 进度在推进就持续保护, 无进度 3s
    // 后照旧放行上层救援(与 detail/24 跳跃卡死"进度在推进才豁免"同口径);
    // ② shouldForce 增加"点击在途"窗口(_lastClickTask 未完成 = 点击已派发等执行)。
    private long _lastProgressMs = 0;
    private ItemStack _lastCursorSnapshot = null;
    private ItemStack _lastTargetSnapshot = null;

    // (2026-08-24 拿起放下反复修复) PICKUP 源槽后客户端光标状态有同步延迟
    // (约 4-5 tick), 旧逻辑下一 tick 若光标仍读到空就再次 PICKUP → 把光标
    // 已有材料换回源槽 = "拿起又放下", 反复几次才放进去 (用户实测 4-5 次,
    // 对应 0.2s 点击节流 + 同步延迟)。这里记录刚发起的 PICKUP 槽位, 在
    // 宽限期内不再重复点击, 等光标真正出现目标材料。
    private Slot _pendingPickup = null;
    private long _pendingPickupSinceMs = 0;
    private static final long PICKUP_SYNC_GRACE_MS = 500;

    // (2026-09-16 冷却期防 churn) 最近一次派发的点击任务。冷却期 (canDoSlotAction
    // =false) 返回它而不是 null —— null 会让框架停掉在跑的 ClickSlotTask, 冷却一过
    // 又 new 一个, 每个点击节流周期 START/STOP 一轮 (230201 场 23:15:32-34 两秒
    // 8 次、全场 82 次实锤, 用户观感"烧铁时任务变化不对")。ClickSlotTask.onTick
    // 自带 canDoSlotAction 闸门, 冷却期 tick 它是安全 no-op。
    private ClickSlotTask _lastClickTask = null;

    public MoveItemToSlotTask(ItemTarget toMove, Slot destination, Function<AltoClef, List<Slot>> getMovableSlots) {
        _toMove = toMove;
        _destination = destination;
        _getMovableSlots = getMovableSlots;
    }

    /**
     * (2026-09-06 烧铁 2.4s 死循环修复) 供父级容器任务判断"子链里正在搬运的
     * 目标槽是否属于自己的合成流程": 若不属于 (如熔炉 fuel 槽), 搬运中间态
     * (PICKUP 已上光标、还没点进目标槽) 期间父级不得翻面/清光标抢断。
     */
    public Slot getDestination() {
        return _destination;
    }

    /**
     * (2026-09-17 屏幕身份校验) 目标槽所属屏幕是否真在前台。槽位读写统一走
     * currentScreenHandler.getSlot(windowSlot) —— 屏幕一旦漂移（同链其他子树
     * 开了合成台/玩家背包屏，或挖方块清遮挡把界面关了），FurnaceSlot 的
     * window 0 会被错误套到非熔炉屏的 0 号槽（= 2x2/3x3 合成产物格，只读
     * 不可写）：右键 place-one 全部哑火、读取恒 EMPTY。20260917-181747 实锤：
     * [SlotClick] btn=1 slot=0 | slot EMPTY->EMPTY | cursor raw_ironx22->x22
     * ×6，place-one 循环 0 产出 = 用户所见"放置铁失败"。
     * 目前只校验 FurnaceSlot 族（本次事故现场）；其他槽族语义未动。
     */
    private boolean isDestinationScreenOpen() {
        if (!(_destination instanceof FurnaceSlot)) return true;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return player != null && player.currentScreenHandler instanceof FurnaceScreenHandler;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _lastProgressMs = System.currentTimeMillis();
        // (2026-09-17 在途登记时机前移) 旧版只在"光标已持有目标物"分支 mark,
        // 但竞争链 (EnsureFree) 上位后 Moving 不再被 tick → mark 永不执行 →
        // 在途表空 → 双层防御查不到 → 光标上的 raw_iron 被扔 (092555+100000
        // 两场复现实锤)。改为: onStart 就登记全部目标物, onTick 顶部续期 ——
        // Moving 存活期间在途表持续命中, 被停 1s 后过期解除。
        markAllTargetsInFlight();
    }

    private void markAllTargetsInFlight() {
        for (Item it : _toMove.getMatches()) {
            markInFlight(it);
        }
    }

    /**
     * (2026-09-30 进度跟踪, detail/31) 光标或目标槽快照变化 = 搬运有真实进展,
     * 续期保险丝。放 onTick 顶部: 冷却期也要跟踪, 否则两拍之间保护脱落。
     */
    private void trackProgress(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        ItemStack atTarget = StorageHelper.getItemStackInSlot(_destination);
        boolean changed = _lastCursorSnapshot == null || _lastTargetSnapshot == null
                || !ItemStack.areEqual(cursor, _lastCursorSnapshot)
                || !ItemStack.areEqual(atTarget, _lastTargetSnapshot);
        if (changed) {
            _lastProgressMs = System.currentTimeMillis();
            _lastCursorSnapshot = cursor.copy();
            _lastTargetSnapshot = atTarget.copy();
        }
    }

    /** (2026-09-30) 保险丝窗口内是否有真实进度。供父级 hold 判据使用: 无进度的
     *  搬运(卡死/物品消失)不得把父级钉死, 3s 后放行翻面救援。 */
    public boolean isRecentlyActive() {
        return System.currentTimeMillis() - _lastProgressMs <= MOVE_FORCE_FUSE_MS;
    }

    /**
     * (2026-09-16 两拍搬运防拆) 搬运中间态 = 光标已持有目标物品待放, 或
     * PICKUP 同步宽限期内 (光标即将出现材料)。此窗口内父级翻面会把搬运
     * 整棵拆掉, 物品回背包、进度清零。带 3s 保险丝: 超时后不再 force,
     * 让上层仲裁可以介入救卡死。
     */
    @Override
    public boolean shouldForce(AltoClef mod, Task interruptingCandidate) {
        // (2026-09-17 达标即放行) 目标槽已满足时不再保护: 搬运目的已达成,
        // 父级切走是正确行为。旧版只看"光标持有目标物" → 达标后仍挡父级
        // 接管 3s (MOVE_FORCE_FUSE_MS), 期间 place-one 循环继续把整堆料塞
        // 进炉 (20260917-173950 实锤: 目标 raw_iron x1, 实塞 12; 燃料任务
        // oak_planks/coal x1/coal x2 三次接管全被 [ForceProtect] 挡回,
        // 3.0s 保险丝到期才切走 = 用户所见"烧东西横跳"; 燃料侧同样盲点,
        // fuel 卡 x1 仍连点 10 次 3.1s)。
        // (2026-09-17 屏幕身份校验) 熔炉界面不在前台时不持盾 —— 此时点击必哑火,
        // 父级切去重开屏幕是唯一正确动作 (现场: [SlotClick] 6 连击全哑火, 父级
        // 重开炉的接管被挡满 3s 保险丝)。
        if (!isDestinationScreenOpen()) return false;
        if (isFinished(mod)) return false;
        // (2026-09-30 进度续期保险丝, detail/31) 从"最后一次真实进度"起算,
        // 替代旧 onStart 一刀切; 无进度 3s 后放行上层救援。
        if (System.currentTimeMillis() - _lastProgressMs > MOVE_FORCE_FUSE_MS) return false;
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        boolean cursorHoldsTarget = !cursor.isEmpty()
                && Arrays.asList(_toMove.getMatches()).contains(cursor.getItem());
        boolean pickupInGrace = _pendingPickup != null
                && System.currentTimeMillis() - _pendingPickupSinceMs < PICKUP_SYNC_GRACE_MS;
        // (2026-09-30 点击在途窗口, detail/31) 第一拍 PICKUP 已派发但还在槽位
        // 冷却/同步等待(光标仍空、500ms 宽限已过) —— 旧版此处零保护, 父链此刻
        // 翻面必拆 (18:44:53 胸甲穿戴被拆 12 次全是这个窗口)。点击在途但从未
        // 产生进度(哑火点击)时保险丝 3s 兜底放行, 不改变卡死自救语义。
        boolean clickInFlight = _lastClickTask != null && !_lastClickTask.isFinished(mod);
        return cursorHoldsTarget || pickupInGrace || clickInFlight;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-09-17 在途登记续期) 放在 canDoSlotAction 闸门之前 —— 冷却期
        // 也要续期, 保证 Moving 存活期间在途表持续命中 (100000 场: 竞争链上位
        // 后 Moving 停 tick, 旧时机 mark 永不执行导致防御落空)。
        markAllTargetsInFlight();
        // (2026-09-30 进度跟踪, detail/31) 同理放闸门之前: 冷却期快照不变但有
        // 点击在途, 状态一变即续期保险丝。
        trackProgress(mod);
        // (2026-09-17 屏幕漂移即放行) 目标屏幕不在前台 = 本任务点击必然哑火
        // (window 0 会套到合成产物格上), 且 isFinished 永假 → "达标即放行"
        // 救不了"永远不达标"的场景。立即放弃保护并停手, 让父级切去重开屏幕
        // (20260917-181747: 父级每 tick 都想切 Interact 重开炉, 被本任务保护
        // 挡满 3s 保险丝 took=3.0s, 期间 6 连击全部哑火)。
        if (!isDestinationScreenOpen()) {
            Debug.logToFile("altoclef_diag.log", "[MoveDiag]\tbail: destination screen NOT open, dest=" + _destination);
            return null;
        }
        if (!mod.getSlotHandler().canDoSlotAction()) {
            // (2026-09-16 冷却期防 churn) 旧实现冷却期直接 return null → 框架停掉
            // 在跑的 ClickSlotTask, 冷却一过又 new 一个 → 每个冷却周期 START/STOP
            // 一轮 (230201 场 23:15:32-34 两秒 8 次、全场 82 次实锤, 用户观感
            // "烧铁时任务变化不对")。改为: 冷却期返回上一个【未完成】的点击任务
            // —— ClickSlotTask.onTick 自带 canDoSlotAction 闸门, 冷却期 tick 它
            // 是安全 no-op, 到点自动执行; 已完成/尚无则维持 null (真正无活可干)。
            if (_lastClickTask != null && !_lastClickTask.isFinished(mod)) {
                return _lastClickTask;
            }
            return null;
        }
        // Rough plan
        // - If empty slot or wrong item
        //      Find best matching item (smallest count over target, or largest count if none over)
        //      Click on it (one turn)
        // - If held slot has < items than target count
        //      Left click on destination slot (one turn)
        // - If held slot has > items than target count
        //      Right click on destination slot (one turn)
        ItemStack currentHeld = StorageHelper.getItemStackInCursorSlot();
        ItemStack atTarget = StorageHelper.getItemStackInSlot(_destination);

        // Items that CAN be moved to that slot.
        Item[] validItems = _toMove.getMatches();//Arrays.stream(_toMove.getMatches()).filter(item -> mod.getItemStorage().getItemCount(item) >= _toMove.getTargetCount()).toArray(Item[]::new);

        // We need to deal with our cursor stack OR put an item there (to move).
        boolean wrongItemHeld = !Arrays.asList(validItems).contains(currentHeld.getItem());

        // (2026-08-24 拿起放下反复修复) 光标已拿到目标材料 → 放到目标槽,
        // 并清空"待确认拿起"状态。
        if (!currentHeld.isEmpty() && !wrongItemHeld) {
            // (2026-09-17 搬运在途登记) 光标持有目标物 = 两拍中转进行中,
            // 登记防腾位链整堆扔掉 (见 _inFlightCursorItems 注释)。
            markInFlight(currentHeld.getItem());
            _pendingPickup = null;
            int currentlyPlaced = Arrays.asList(validItems).contains(atTarget.getItem()) ? atTarget.getCount() : 0;
            if (currentHeld.getCount() + currentlyPlaced <= _toMove.getTargetCount()) {
                // Just place all of 'em
                _lastClickTask = new ClickSlotTask(_destination);
                return _lastClickTask;
            } else {
                // Place one at a time.
                // (2026-09-17 诊断) place-one 每次点击记一行: 达标后本分支
                // 不该再被走到 (shouldForce 已加达标即放行), 若仍出现连续
                // place-one 即父级接管仍被挡/未接管, 直接量化"多塞了几个"。
                Debug.logToFile("altoclef_diag.log", "[MoveDiag]\tplace-one dest=" + _destination
                        + " held=" + currentHeld.getCount() + " placed=" + currentlyPlaced
                        + " target=" + _toMove.getTargetCount());
                _lastClickTask = new ClickSlotTask(_destination, 1);
                return _lastClickTask;
            }
        }

        if (currentHeld.isEmpty()) {
            // 需要拿起。若刚发起的 PICKUP 还在同步宽限期内 (光标应很快
            // 出现材料), 直接推进到"放目标槽" — 既不重复点击源槽 (否则
            // 拿起又放下反复), 也不返回 null (否则父任务 containerSubTask
            // 的光标兜底抢跑, 把光标上的材料 Dumping 放回背包)。
            if (_pendingPickup != null
                    && System.currentTimeMillis() - _pendingPickupSinceMs < PICKUP_SYNC_GRACE_MS) {
                // (2026-09-17 诊断) 宽限期内点目标槽时光标仍空 = PICKUP 未
                // 落地, 这一击会从目标槽【拿起】(若目标槽非空) — 即"拿起
                // 又放下"引擎。20260917-173950 燃料侧 fuel 卡 x1 连点 10 次
                // 疑似此路径, 记日志定案。
                Debug.logToFile("altoclef_diag.log", "[MoveDiag]\tgrace-branch destination click with EMPTY cursor, source="
                        + _pendingPickup + " dest=" + _destination + " (dest stack=" + atTarget.getItem() + " x" + atTarget.getCount() + ")");
                _lastClickTask = new ClickSlotTask(_destination);
                return _lastClickTask;
            }
            Optional<Slot> toPlace = getBestSlotToPickUp(mod, validItems);
            if (toPlace.isEmpty()) {
                Debug.logError("Called MoveItemToSlotTask when item/not enough item is available! valid items: " + StlHelper.toString(validItems, Item::getTranslationKey));
                return null;
            }
            _pendingPickup = toPlace.get();
            _pendingPickupSinceMs = System.currentTimeMillis();
            _lastClickTask = new ClickSlotTask(toPlace.get());
            return _lastClickTask;
        } else {
            // 拿着错物品 → 先放回背包 (或直接换)。
            Optional<Slot> toPlace = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(currentHeld, true);
            if (toPlace.isEmpty()) {
                // If all else fails, just swap it.
                toPlace = getBestSlotToPickUp(mod, validItems);
            }
            if (toPlace.isEmpty()) {
                Debug.logError("Called MoveItemToSlotTask when item/not enough item is available! valid items: " + StlHelper.toString(validItems, Item::getTranslationKey));
                return null;
            }
            _lastClickTask = new ClickSlotTask(toPlace.get());
            return _lastClickTask;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-10 x0 挂死修复) 目标数量 <= 0 = 没东西可搬, 立即视为完成。
        // 旧判定要求目标槽已有该物品才算完, count=0 时目标槽为空 → matches
        // 恒 false → 任务挂死不结束 (15:14 场次: <Moving [raw_iron,iron_ore]
        // x 0> 起来后 29s 零动作, 光标还被 fuel 残留的橡木板占住)。
        if (_toMove.getTargetCount() <= 0) return true;
        ItemStack atDestination = StorageHelper.getItemStackInSlot(_destination);
        return (_toMove.matches(atDestination.getItem()) && atDestination.getCount() >= _toMove.getTargetCount());
    }

    @Override
    protected boolean isEqual(Task obj) {
        if (obj instanceof MoveItemToSlotTask task) {
            return task._toMove.equals(_toMove) && task._destination.equals(_destination);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Moving " + _toMove + " to " + _destination;
    }

    private Optional<Slot> getBestSlotToPickUp(AltoClef mod, Item[] validItems) {
        Slot bestMatch = null;
        for (Slot slot : _getMovableSlots.apply(mod)) {
            if (Slot.isCursor(slot))
                continue;
            if (!_toMove.matches(StorageHelper.getItemStackInSlot(slot).getItem()))
                continue;
            if (bestMatch == null) {
                bestMatch = slot;
                continue;
            }
            int countBest  = StorageHelper.getItemStackInSlot(bestMatch).getCount();
            int countCheck = StorageHelper.getItemStackInSlot(slot).getCount();
            if (   (countBest < _toMove.getTargetCount() && countCheck > countBest)
                    || (countBest >= _toMove.getTargetCount() && countCheck >= _toMove.getTargetCount() && countCheck > countBest) ) {
                // If we don't have enough, go for largest
                // If we have too much, go for smallest over the limit.
                bestMatch = slot;
            }
        }
        return Optional.ofNullable(bestMatch);
    }
}
