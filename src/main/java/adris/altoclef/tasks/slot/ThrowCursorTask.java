package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TurnToOpenAreaTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * 丢弃光标上的物品。
 *
 * (2026-09-01 重设计, 用户"先转向空旷, 然后再丢") 容器界面点窗外(-999)丢光标
 * 物品是 dropItem(stack,true)=随机初速度, 完全不读玩家朝向 — 任何"转向"对它都
 * 无效, 东西随机落回脚边被满背包立刻捡回 (用户两轮实测)。方向可控的唯一可靠
 * 路径是 Q 键 dropSelectedItem (dropItem(stack,false) 用朝向速度), 但 Q 丢的是
 * 【选中的快捷栏槽】。流程分三步, 每步清晰可见:
 *   1. 交换: 把光标物品交换进快捷栏并选中 (方向可控丢的必要前提);
 *   2. 转向: 交给独立 TurnToOpenAreaTask 先转向开阔处 (视角稳定后完成);
 *   3. 丢出: 关界面(光标旧物自动回背包), 按 Q 用朝向初速度丢向开阔处。
 */
public class ThrowCursorTask extends Task {

    // 阶段: 0=交换到快捷栏, 1=转向开阔处, 2=Q键丢出。
    private int _phase = 0;
    // 要丢弃的物品类型 (交换生效判定用)。
    private Item _throwItem;
    // 目标快捷栏槽 (PlayerInventory 0..8)。
    private int _hotbarIndex = -1;
    // 等待交换生效的 tick 上限 (防网络/UI 延迟卡死)。
    private int _swapWaitTicks = 0;
    private static final int SWAP_WAIT_MAX_TICKS = 10; // 0.5s
    // 独立转向任务实例 (阶段 1 复用同一实例直到完成)。
    private TurnToOpenAreaTask _turnTask;

    @Override
    protected void onStart(AltoClef mod) {
        _phase = 0;
        _throwItem = StorageHelper.getItemStackInCursorSlot().getItem();
        _hotbarIndex = -1;
        _swapWaitTicks = 0;
        _turnTask = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (cursor.isEmpty()) {
            // 光标已空(没有可丢的物品) → 完成。
            return null;
        }

        if (_phase == 0) {
            // 找快捷栏槽 (优先空的), 点击交换: 光标物品进快捷栏。
            if (_hotbarIndex == -1) {
                for (int i = 0; i < 9; i++) {
                    if (StorageHelper.getItemStackInSlot(Slot.getFromCurrentScreenInventory(i)).isEmpty()) {
                        _hotbarIndex = i;
                        break;
                    }
                }
                if (_hotbarIndex == -1) {
                    _hotbarIndex = mod.getPlayer().getInventory().selectedSlot;
                }
                return new ClickSlotTask(Slot.getFromCurrentScreenInventory(_hotbarIndex));
            }
            // 等待交换生效 (快捷栏槽里确实变成要丢的物品), 超时放弃(不误丢)。
            if (++_swapWaitTicks > SWAP_WAIT_MAX_TICKS) {
                StorageHelper.closeScreen();
                return null; // 交换失败, 关界面放回光标物品, 不 Q 丢避免丢错
            }
            ItemStack inHotbar = StorageHelper.getItemStackInSlot(Slot.getFromCurrentScreenInventory(_hotbarIndex));
            if (inHotbar.isEmpty() || inHotbar.getItem() != _throwItem) {
                return null; // 交换还没生效, 继续等
            }
            mod.getPlayer().getInventory().selectedSlot = _hotbarIndex;
            _phase = 1;
            _turnTask = new TurnToOpenAreaTask();
            return _turnTask;
        }

        if (_phase == 1) {
            // 独立转向任务: 先转向开阔处, 视角稳定后才丢。
            if (_turnTask != null && !_turnTask.isFinished(mod)) {
                return _turnTask;
            }
            _turnTask = null;
            _phase = 2;
        }

        // 阶段 2: 关界面(光标旧物自动回背包) → Q 键用朝向初速度丢向开阔处。
        StorageHelper.closeScreen();
        mod.getPlayer().dropSelectedItem(true);
        return null;
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
        // 光标已空(物品已处理/丢弃) 即视为完成。
        return StorageHelper.getItemStackInCursorSlot().isEmpty();
    }
}
