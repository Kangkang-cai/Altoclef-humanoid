package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;

/**
 * [SNAPSHOT] (2026-09-11 任务栈全栈快照日志, 纯只读诊断)
 *
 * 背景: 现有日志全部是事件驱动 + 节流 (gridwar 400ms / craftclick 300ms /
 * debugstate 仅状态变化时写 / tasks.log 只记 START/STOP/SUB 切换), 因此
 * "父任务每 tick 重派同一子任务 (被 isEqual 挡下)" 与 "子任务每 tick 空转
 * 返回 null" 这类【无切换】的死锁在日志里完全不可见。
 *
 * 22:17 冻结 39s 实锤 (20260911-220819 会话): CollectFuelTask 燃料缺口 4.5
 * 每 tick 重派 "Crafting 3 planks" vs CollectPlanksTask 木板目标 3/3 已达成
 * 走清格分支 null-spin。两端证据分属不同文件且都被节流吞掉, 事后只能靠
 * Arbiter 日志节奏 + 源码逐层反推定位。
 *
 * 本快照一次性写出: 完整任务栈 + 【每层 isFinished】 + 关键资源计数 +
 * 2x2 格/光标内容。有了每层 isFinished, "叶子任务 finished=true 却仍挂在
 * 栈上" (框架从不检查中间层 isFinished) 与 "父任务 finished=false 且状态
 * 长期不变" 这两类死锁一眼可见。
 *
 * 频控: 常态 5s 一条; NAV-STUCK 冻结期间 1s 一条 (isStuck() 由看门狗提供)。
 * 零行为改动: 只读, 任何异常吞掉, 绝不影响主逻辑。
 */
public final class TaskStackSnapshot {

    private TaskStackSnapshot() {}

    private static final long NORMAL_INTERVAL_MS = 5000;
    private static final long STUCK_INTERVAL_MS = 1000;

    private static long _lastSnapMs = 0;

    /** 由 AltoClef.onClientTick 每游戏 tick 调用 (内部自节流)。 */
    public static void periodicTick(AltoClef mod) {
        try {
            long now = System.currentTimeMillis();
            long interval = NavStuckWatchdog.isStuck() ? STUCK_INTERVAL_MS : NORMAL_INTERVAL_MS;
            if (now - _lastSnapMs < interval) return;
            _lastSnapMs = now;
            dump(mod, NavStuckWatchdog.isStuck() ? "stuck" : "periodic");
        } catch (Throwable ignored) {
            // 诊断日志绝不影响主逻辑
        }
    }

    /** 立即写一份快照 (供其它诊断点按需调用)。 */
    public static void dump(AltoClef mod, String reason) {
        try {
            TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
            List<Task> tasks = chain != null ? chain.getTasks() : null;
            if (tasks == null || tasks.isEmpty()) return; // 空闲不刷屏

            StringBuilder sb = new StringBuilder();
            sb.append("[SNAPSHOT] reason=").append(reason)
                    .append(" chain=").append(chain.getName())
                    .append(" depth=").append(tasks.size());
            for (int i = 0; i < tasks.size(); i++) {
                Task t = tasks.get(i);
                String state;
                String fin;
                try {
                    state = t.toString();
                } catch (Throwable e) {
                    state = "<toString-err>";
                }
                try {
                    fin = String.valueOf(t.isFinished(mod));
                } catch (Throwable e) {
                    fin = "err";
                }
                sb.append("\n  L").append(i).append(' ').append(state).append(" finished=").append(fin);
            }

            // 关键资源计数 (木板/原木/煤/燃料单位) — 死锁常是"父任务缺口 vs
            // 子任务目标"口径脱节, 同一行对齐才能一眼看出来。
            int planks = mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
            int logs = mod.getItemStorage().getItemCount(ItemHelper.LOG);
            int coal = mod.getItemStorage().getItemCount(Items.COAL);
            double fuelUnits = StorageHelper.calculateInventoryFuelCount(mod);
            sb.append("\n  RES planks=").append(planks)
                    .append(" logs=").append(logs)
                    .append(" coal=").append(coal)
                    .append(" fuelUnits=").append(String.format("%.1f", fuelUnits));

            // 2x2 合成格 + 输出格 + 光标
            sb.append("\n  GRID");
            for (int i = 0; i < PlayerSlot.CRAFT_INPUT_SLOTS.length; i++) {
                sb.append(" in").append(i + 1).append('=').append(fmtStack(
                        StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_INPUT_SLOTS[i])));
            }
            sb.append(" out=").append(fmtStack(StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT)))
                    .append(" cursor=").append(fmtStack(StorageHelper.getItemStackInCursorSlot()));

            Debug.logToFile("altoclef_snapshot.log", sb.toString());
        } catch (Throwable ignored) {
            // 诊断日志绝不影响主逻辑
        }
    }

    private static String fmtStack(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "EMPTY"
                : stack.getItem().toString() + "x" + stack.getCount();
    }
}
