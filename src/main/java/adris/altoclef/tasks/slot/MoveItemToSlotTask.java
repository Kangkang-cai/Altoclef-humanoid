package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

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
    private static final long IN_FLIGHT_TTL_MS = 1000L;

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
    private long _startedMs = 0;

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

    @Override
    protected void onStart(AltoClef mod) {
        _startedMs = System.currentTimeMillis();
    }

    /**
     * (2026-09-16 两拍搬运防拆) 搬运中间态 = 光标已持有目标物品待放, 或
     * PICKUP 同步宽限期内 (光标即将出现材料)。此窗口内父级翻面会把搬运
     * 整棵拆掉, 物品回背包、进度清零。带 3s 保险丝: 超时后不再 force,
     * 让上层仲裁可以介入救卡死。
     */
    @Override
    public boolean shouldForce(AltoClef mod, Task interruptingCandidate) {
        if (System.currentTimeMillis() - _startedMs > MOVE_FORCE_FUSE_MS) return false;
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        boolean cursorHoldsTarget = !cursor.isEmpty()
                && Arrays.asList(_toMove.getMatches()).contains(cursor.getItem());
        boolean pickupInGrace = _pendingPickup != null
                && System.currentTimeMillis() - _pendingPickupSinceMs < PICKUP_SYNC_GRACE_MS;
        return cursorHoldsTarget || pickupInGrace;
    }

    @Override
    protected Task onTick(AltoClef mod) {
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
