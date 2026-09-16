package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.item.ItemStack;

import java.util.Optional;

public class EnsureFreeCursorSlotTask extends Task {

    // (2026-08-30 卡死修复, 现场: 光标3木板+2x2格1木板+背包全满) getSlotThatCanFit
    // 在背包全满时反复返回一个"看似能放"的槽, 点击只是换位/链式换位, 光标永远
    // 空不掉 → "Moving cursor stack back" 每 tick 换槽无限循环 (~6s 无防御被骷髅
    // 打死, tasks.log 124573-124684 实锤)。给放置尝试次数设上限, 超限即踢进完整
    // 丢弃级联 (丢光标/垃圾/大堆/随机), 彻底打破死锁, 不再永久空转。
    private static final int MAX_PLACE_ATTEMPTS = 3;
    private int _placeAttempts = 0;
    // (2026-08-31 拿放拉锯修复) 锁定最终放置目标槽: 一次选中后就固定放到该槽,
    // 不再每个 tick 重新 getSlotThatCanFit 返回不同槽(日志 slot9↔slot0 反复5拍,
    // 用户实测背包很多空位仍拿放反复)。当光标里还是同一物品且我们还在待放时,
    // 复用上次的目标; 仅当目标槽已失效/放成功后回退为重新寻找。
    // (2026-09-01 起锁定槽只来自 findRealBackpackFit 的确定性扫描, 不会横跳。)
    private Slot _lockTargetSlot = null;
    // (2026-09-02 卡住封顶, 用户"清2x2格子光标腾位卡 7-8 秒") 光标里是同物堆叠槽时
    // ClickSlotTask(PICKUP/左键) 是【交换】而非【合并】, 光标永不空 → "_placeAttempts
    // 超限→抛→重锁同槽再放"反复横跳 (tasks.log 22:32:37-46: Clicking slot9 ↔ Throwing
    // Cursor 循环 5 拍)。放置尝试计数在抛丢分支被重置为 0, 计数上限形同虚设, 于是
    // 能拖到 7-8 秒。加硬时间兜底: 光标非空且任务激活超过 CURSOR_FREE_LIMIT_TICKS
    // 还没放下 → 直接跳过放置、强制进抛丢级联一次跑完, 把这类交换死锁封顶在几 tick
    // 内, 不无限拖。不影响"光标里有真空能放/贵重同物能合并"的正常快路径。
    private static final long CURSOR_FREE_LIMIT_TICKS = 40; // ~2s, 超过则强制抛丢
    private long _startTick = -1;

    // (2026-09-15 放置点击落地 latch, 用户"又是我介入才进行下去", 095835 会话
    //  09:58:59-10:00:05 实锤) 槽位点击生效有 1-2 tick 客户端同步延迟。放置点击
    // 刚发出就重判, 光标仍显示非空 → _placeAttempts 3 次烧完进抛丢级联派
    // ThrowCursor → 下一 tick 又回到放置 → 每 0.3s 一轮自我翻面, ThrowCursor
    // 瞄准反复清零, 几十轮零丢出。修法: 放置点击发出后挂起 2 tick 等同步落地
    // (返回同一实例, 任务树不换), 期间不烧尝试次数; 光标空了自然走收尾分支。
    private Task _pendingPlaceTask = null;
    private int _placeWaitTicks = 0;

    @Override
    protected void onStart(AltoClef mod) {
         // YEET
         _placeAttempts = 0;
         _lockTargetSlot = null;
         _startTick = WorldHelper.getTicks();
         _pendingPlaceTask = null;
         _placeWaitTicks = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();

        if (!cursor.isEmpty()) {
            // (2026-09-15 放置点击落地 latch) 刚发出的放置点击等 2 tick 再判。
            if (_pendingPlaceTask != null && _placeWaitTicks > 0) {
                _placeWaitTicks--;
                setDebugState("Waiting for place click to land...");
                return _pendingPlaceTask;
            }
            _pendingPlaceTask = null;
            // (2026-09-02 硬时间兜底) 光标长期放不下 → 直接强制抛丢级联, 不让
            // 交换死锁无限拖 (详见字段注释)。
            if (_startTick >= 0 && WorldHelper.getTicks() - _startTick > CURSOR_FREE_LIMIT_TICKS) {
                _placeAttempts = MAX_PLACE_ATTEMPTS + 1;
                Debug.logMessage("(EnsureFreeCursorSlotTask) cursor stuck > " + CURSOR_FREE_LIMIT_TICKS
                        + " ticks, forcing throw cascade for " + cursor.getItem().getTranslationKey());
            }
            _placeAttempts++;
            if (_placeAttempts <= MAX_PLACE_ATTEMPTS) {
                // 优先复用已锁定的目标槽 (锁定的槽若仍能放同物就不换)。
                if (_lockTargetSlot != null && canPlaceCursorIntoSlot(cursor, _lockTargetSlot)) {
                    setDebugState("Moving cursor stack back");
                    _pendingPlaceTask = new ClickSlotTask(_lockTargetSlot);
                    _placeWaitTicks = 2;
                    return _pendingPlaceTask;
                }
                // (2026-09-01 根因修复: "光标 44 木棍放不回, 背包空位很多") 旧真实扫描
                // 遍历 getCurrentScreenSlots() = 当前屏幕【全部】窗口槽, 在 2x2 背包
                // 界面会命中 合成输出槽(window 0) 与 合成格(window 1-4):
                //   - 点合成输出槽是空操作 → 光标永远清不掉 → "Moving cursor stack
                //     back (real empty/mergeable slot)" 无限循环;
                //   - 点合成格会把光标物品倒进合成格, 污染材料计数 (喂养"反复合成")。
                // 且工作台/熔炉/箱子界面下所有槽映射为对应 Slot 子类, 旧的
                // instanceof PlayerSlot 过滤会跳过一切 → 真实扫描整体失效。
                // 现改为只遍历真背包 (inventory slot 0..35 = 快捷栏+主背包), 经
                // getFromCurrentScreenInventory 映射到当前屏幕的正确窗口槽: 天然排除
                // 合成输出/合成格/护甲/副手, 且对任意界面生效。真实扫描读
                // currentScreenHandler 实时栈, 权威且便宜, 排在可能滞后的缓存之前。
                Slot realFit = findRealBackpackFit(cursor);
                if (realFit != null) {
                    _lockTargetSlot = realFit;
                    Debug.logMessage("[CursorRealScan] placing cursor into real empty/mergeable slot " + realFit);
                    // (2026-09-03 用户: 该验证标记走 logMessage 只进聊天栏, 不进日志
                    // 文件, 导致"以为没生效"). 同步写一份到 runDirectory 的
                    // altoclef_cursor.log, 便于以后日志核对达成情况。
                    Debug.logToFile("altoclef_cursor.log", "[CursorRealScan] placing cursor into real empty/mergeable slot " + realFit);
                    setDebugState("Moving cursor stack back (real empty/mergeable slot)");
                    _pendingPlaceTask = new ClickSlotTask(realFit);
                    _placeWaitTicks = 2;
                    return _pendingPlaceTask;
                }
                // 真实扫描无果 (真背包无空槽且无可合并堆) → 缓存兜底。
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                // (2026-09-09 [GridWar] 根修#2 缓存假空槽) 缓存答案可能过期:
                // 15:32 场次实锤缓存指向被 netherrackx52 占据的槽, 左键 PICKUP
                // 变 SWAP, 光标 log 被换成 netherrack, 东西在热栏倒来倒去不收敛。
                // 点击前按真实屏幕栈复核, 过期答案作废 → 落回下方丢弃级联。
                if (moveTo.isPresent() && canPlaceCursorIntoSlot(cursor, moveTo.get())) {
                    setDebugState("Moving cursor stack back");
                    _pendingPlaceTask = new ClickSlotTask(moveTo.get());
                    _placeWaitTicks = 2;
                    return _pendingPlaceTask;
                }
            }
            // 放不回去 (或多次放置仍腾不光标) → 丢弃级联, 与 EnsureFreeInventorySlotTask 一致。
            // 走到这里意味着真实扫描+缓存都找不到可放槽 (真背包满), 才允许丢弃腾位。
            _placeAttempts = 0;
            _lockTargetSlot = null;
            if (ItemHelper.canThrowAwayStack(mod, cursor)) {
                setDebugState("Incompatible cursor stack, throwing");
                return new ThrowCursorTask();
            } else {
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                if (garbage.isPresent()) {
                    // Pick up garbage so we throw it out next frame
                    setDebugState("Picking up garbage");
                    return new ClickSlotTask(garbage.get());
                }
                // (2026-08-30 补大堆/随机保底) 无垃圾可捡也绝不空转: 丢一把大堆廉价
                // 建材, 再不行随机丢一格, 腾出位置放下光标/合成产物。人类在背包塞满
                // 时也会先扔掉不重要的东西腾格子。
                Slot bulk = StorageHelper.getBulkDisposalSlot(mod);
                if (bulk != null) {
                    setDebugState("Cursor stuck: tossing bulk stack for room");
                    return new ClickSlotTask(bulk);
                }
                Slot randomThrow = StorageHelper.getRandomThrowableSlot(mod);
                if (randomThrow != null) {
                    setDebugState("Cursor stuck: random fallback toss");
                    return new ClickSlotTask(randomThrow);
                }
                setDebugState("STUCK! Everything's protected.");
                return null;
            }
        }
        _placeAttempts = 0;
        _lockTargetSlot = null;
        _pendingPlaceTask = null;
        _placeWaitTicks = 0;
        return null;
    }

    // 真实扫描当前屏幕的真背包槽 (inventory 0..35 = 快捷栏+主背包), 返回第一个能
    // 容纳光标物品的槽 (真空槽, 或同类未满可合并的堆), 无则 null。读
    // currentScreenHandler 实时栈, 不经过 tracker 缓存; 经 getFromCurrentScreenInventory
    // 映射, 背包/工作台/熔炉/箱子任意界面均正确。
    private Slot findRealBackpackFit(ItemStack cursor) {
        for (int i = 0; i <= 35; i++) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            // (2026-09-16 防御, 09:06:35 latest.log 实锤) 槽位映射对未注册界面
            // (Esc 菜单/聊天栏等 currentScreen 非空但不在 SlotScreenMapping 表里)
            // 会兜底退化成 CursorSlot.SLOT — 真实扫描全部 i 都返回光标槽, 点它 =
            // "Tried to click the cursor slot" 警告 + 永远放不回。跳过退化槽。
            if (Slot.isCursor(slot) || slot.getInventorySlot() < 0) continue;
            ItemStack in = StorageHelper.getItemStackInSlot(slot);
            if (in.isEmpty() || (cursor.isItemEqual(in) && in.getCount() < in.getMaxCount())) {
                return slot;
            }
        }
        return null;
    }

    // 目标槽是否仍能容纳当前光标物品 (读真实屏幕栈; 锁定槽只会来自 findRealBackpackFit)。
    private boolean canPlaceCursorIntoSlot(ItemStack cursor, Slot slot) {
        // (2026-09-16) 同上: 光标槽/无效槽永远不能作为放置目标 (点光标槽自己 =
        // 空操作警告; 锁定槽若因界面切换退化为光标槽, 读到的"内容"就是光标自己,
        // isItemEqual 恒真 → 会误判可放)。判不可放, 走重扫/丢弃级联。
        if (slot == null || Slot.isCursor(slot) || slot.getInventorySlot() < 0) return false;
        ItemStack in = StorageHelper.getItemStackInSlot(slot);
        return in.isEmpty() || (cursor.isItemEqual(in) && in.getCount() < in.getMaxCount());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EnsureFreeCursorSlotTask;
    }


    // And filling this in will make it look ok in the task tree
    @Override
    protected String toDebugString() {
        return "Breaking the cursor slot";
    }
}
