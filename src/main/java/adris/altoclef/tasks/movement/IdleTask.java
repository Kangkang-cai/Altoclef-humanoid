package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Playground;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.HumanoidGaze;

/**
 * 原地站立待命。
 *
 * <p>(2026-10-04) 新增定时口径：{@link #IdleTask(int)} 传正整数秒 → 待命满时长后
 * {@code isFinished} 为真，顶层任务自然结束（UserTaskChain.onTaskFinish →
 * TaskFinishedEvent → ActionDispatcher 回执 + 自动回主线）。无参构造保持旧语义
 * （无限期待命，直到下一条指令顶掉）。判定源单一：时长只在 isFinished 判断。
 *
 * <p>(2026-10-23 拟人头部观察) 旧版 onTick 完全不动 → 相机滞留在上一任务的朝向
 * （常是低头挖矿姿态），表现为"待命时一直看向脚"。现接 {@link HumanoidGaze}：
 * 平视扫视四周 + 时不时看向附近玩家/bot/生物，转向经 SmoothLookController 帧级
 * 平滑。不动用任何移动输入，只动头部。
 */
public class IdleTask extends Task {
    /** 定时时长（毫秒）；<=0 = 无限期（旧语义）。 */
    private final long _durationMs;
    /** onStart 记下的起始时刻（二次 onStart 会被 reset，语义正确：重新计时）。 */
    private long _startMs = 0;
    // (2026-10-23 拟人头部观察) 待机视线调度器。无锚点模式（纯环境扫视+看人）。
    private final HumanoidGaze _gaze = new HumanoidGaze();

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
        // 复用实例重进时强制立即重选注视目标。
        _gaze.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-10-23 拟人头部观察) 原 Playground.IDLE_TEST_TICK_FUNCTION 保留;
        // 新增待机视线: 每 tick 重登记注视目标, 由 SmoothLookController 帧级平滑。
        // 注意: IdleTask 无限期待命期间这里持续持有相机所有权, 任务被顶掉后
        // SmoothLookController 10 拍超时自然归还 —— 无残留。
        _gaze.tick(mod);
        setDebugState("Idle, " + _gaze.describe());
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
