package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TurnToOpenAreaTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.slots.Slot;

/**
 * 丢弃光标上的物品。
 *
 * (2026-09-01 用户纠正: 丢出是朝向视角的, 不是随机初速度 — 之前我误判成随机,
 * Q键/快捷栏重设计已废弃回退) 点窗外(-999)丢光标物品的方向由玩家朝向决定,
 * 所以: 先交给独立 TurnToOpenAreaTask 转向"前方最开阔"的方向, 视角稳定后再
 * 点窗外丢出 — 物品带朝向初速度飞向开阔处, 不再落回脚下被满背包立刻捡回。
 */
public class ThrowCursorTask extends Task {

    private final Task _throwTask = new ClickSlotTask(Slot.UNDEFINED);
    // 独立转向任务: 先转向开阔处, 视角稳定后才丢。
    private TurnToOpenAreaTask _turnTask;

    @Override
    protected void onStart(AltoClef mod) {
        _turnTask = new TurnToOpenAreaTask();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // 先转向开阔处 (未完成就持续返回转向任务)。
        if (_turnTask != null && !_turnTask.isFinished(mod)) {
            return _turnTask;
        }
        _turnTask = null;
        // 转向完成 → 点窗外丢出 (朝向速度, 面向开阔处)。
        return _throwTask;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof ThrowCursorTask;
    }

    @Override
    protected String toDebugString() {
        return "Throwing Cursor";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _throwTask.isFinished(mod);
    }
}
