package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;

/**
 * 不睡觉，熬到天亮（2026-10-03 `@sleep wait-dawn`）。
 *
 * <p>动机（用户 1003 反问"不想睡觉想跳过睡觉怎么办"）：多人服里睡觉要求<b>全员</b>入睡
 * （或达到 {@code playersSleepingPercentage}）。凑不齐人时，躺着等天亮和站着等天亮对
 * "过夜"是等价的 —— 但躺床上会把 bot 钉在床边，且一旦有人始终不睡就永久卡住。
 * 本任务给"我不睡，就这么熬着"这条出口。
 *
 * <p>行为：站桩不动（{@code onTick} 返回 null），天亮即结束 —— 与
 * {@link SleepThroughNightTask} 的结束判据同源（白天区间）。
 */
public class WaitForDawnTask extends Task {

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof WaitForDawnTask;
    }

    @Override
    protected String toDebugString() {
        return "Waiting for dawn";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 与 SleepThroughNightTask 同判据: 白天 (0..13000) 即结束
        int time = (int) (mod.getWorld().getTimeOfDay() % 24000);
        return 0 <= time && time < 13000;
    }
}
