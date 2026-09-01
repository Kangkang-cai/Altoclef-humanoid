package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;

/**
 * (2026-08-27 用户要求: 可堆叠物品应放一起省空间, 放在丢垃圾之前)
 * 把同类部分堆合并: 拿起小堆 A → 点到大堆 B (同类且有空间) → A 全部并进 B,
 * A 腾空, 省一个格子。不丢任何物品。直到没有可合并的对才结束。
 * 状态机: 光标空 → 找合并对并拿起 A; 光标非空 → 放到 B (合并) 或放回原处。
 */
public class ConsolidateInventoryTask extends Task {

    // 拿起 A 后要合并进去的目标槽 (B)。
    private Slot _mergeInto = null;

    @Override
    protected void onStart(AltoClef mod) {
        _mergeInto = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        // 阶段 2: 光标有东西 → 继续并入 _mergeInto, 或放回安全槽。绝不丢物品。
        if (!cursor.isEmpty()) {
            if (_mergeInto != null) {
                ItemStack target = StorageHelper.getItemStackInSlot(_mergeInto);
                // 目标仍同型且有空间 → 继续点它直到并完全部。原代码点一次就
                // 清掉目标, 若一次没并完 (点出"换位"手里仍剩) 就丢目标乱点
                // 其它槽 → 在 32/33/34 之间抖动 (日志实锤 TaskPingPong)。
                if (!target.isEmpty()
                        && cursor.getItem() == target.getItem()
                        && target.getCount() < target.getMaxCount()) {
                    return new ClickSlotTask(_mergeInto);
                }
                // 目标已无效 (被换走/占满) → 放弃, 落到放回逻辑。
                _mergeInto = null;
            }
            // 光标有东西但没(可用)目标: 放回能容纳的槽位, 绝不丢物品。
            Slot back = mod.getItemStorage()
                    .getSlotThatCanFitInPlayerInventory(cursor, false)
                    .or(() -> mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, true))
                    .orElse(null);
            if (back != null) {
                return new ClickSlotTask(back);
            }
            return null; // 放不回去 → 结束, 交给其它任务处理光标
        }
        // 阶段 1: 光标空 → 找下一对可合并的 (A 部分堆, B 同类有空间)。
        Slot[] pair = StorageHelper.findMergeableStackPair(mod);
        if (pair != null) {
            _mergeInto = pair[1];
            return new ClickSlotTask(pair[0]); // 拿起 A
        }
        // 没有可合并的了 → 完成。
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.getItemStackInCursorSlot().isEmpty()
                && StorageHelper.findMergeableStackPair(mod) == null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _mergeInto = null;
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof ConsolidateInventoryTask;
    }

    @Override
    protected String toDebugString() {
        return "Consolidating stacks";
    }
}
