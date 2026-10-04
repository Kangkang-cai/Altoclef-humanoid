package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Playground;
import adris.altoclef.tasksystem.Task;

/**
 * Do nothing（原地站立待命）。
 *
 * <p>(2026-10-04) 新增定时口径：{@link #IdleTask(int)} 传正整数秒 → 待命满时长后
 * {@code isFinished} 为真，顶层任务自然结束（UserTaskChain.onTaskFinish →
 * TaskFinishedEvent → ActionDispatcher 回执 + 自动回主线）。无参构造保持旧语义
 * （无限期待命，直到下一条指令顶掉）。判定源单一：时长只在 isFinished 判断。
 */
public class IdleTask extends Task {
    /** 定时时长（毫秒）；<=0 = 无限期（旧语义）。 */
    private final long _durationMs;
    /** onStart 记下的起始时刻（二次 onStart 会被 reset，语义正确：重新计时）。 */
    private long _startMs = 0;

    public IdleTask() {
        this(0);
    }

    /** @param durationSec 待命秒数；<=0 视为无限期。 */
    public IdleTask(int durationSec) {
        _durationMs = durationSec > 0 ? durationSec * 1000L : 0;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _startMs = System.currentTimeMillis();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Do nothing except maybe test code
        Playground.IDLE_TEST_TICK_FUNCTION(mod);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 无限期 = never finish（旧口径）；定时 = 到时即结束。
        return _durationMs > 0 && _startMs > 0
                && System.currentTimeMillis() - _startMs >= _durationMs;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof IdleTask;
    }

    @Override
    protected String toDebugString() {
        return _durationMs > 0 ? "Idle(" + (_durationMs / 1000) + "s)" : "Idle";
    }
}
