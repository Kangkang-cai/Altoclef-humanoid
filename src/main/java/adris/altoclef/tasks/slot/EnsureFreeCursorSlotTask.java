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

    @Override
    protected void onStart(AltoClef mod) {
         // YEET
         _placeAttempts = 0;
         _lockTargetSlot = null;
         _startTick = WorldHelper.getTicks();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();

        if (!cursor.isEmpty()) {
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
                    return new ClickSlotTask(_lockTargetSlot);
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
                    setDebugState("Moving cursor stack back (real empty/mergeable slot)");
                    return new ClickSlotTask(realFit);
                }
                // 真实扫描无果 (真背包无空槽且无可合并堆) → 缓存兜底。
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                if (moveTo.isPresent()) {
                    setDebugState("Moving cursor stack back");
                    return new ClickSlotTask(moveTo.get());
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
        return null;
    }

    // 真实扫描当前屏幕的真背包槽 (inventory 0..35 = 快捷栏+主背包), 返回第一个能
    // 容纳光标物品的槽 (真空槽, 或同类未满可合并的堆), 无则 null。读
    // currentScreenHandler 实时栈, 不经过 tracker 缓存; 经 getFromCurrentScreenInventory
    // 映射, 背包/工作台/熔炉/箱子任意界面均正确。
    private Slot findRealBackpackFit(ItemStack cursor) {
        for (int i = 0; i <= 35; i++) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            ItemStack in = StorageHelper.getItemStackInSlot(slot);
            if (in.isEmpty() || (cursor.isItemEqual(in) && in.getCount() < in.getMaxCount())) {
                return slot;
            }
        }
        return null;
    }

    // 目标槽是否仍能容纳当前光标物品 (读真实屏幕栈; 锁定槽只会来自 findRealBackpackFit)。
    private boolean canPlaceCursorIntoSlot(ItemStack cursor, Slot slot) {
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
