package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.TimeoutWanderTask;

import java.util.function.Predicate;

public abstract class Task {

    private String _oldDebugState = "";
    private String _debugState = "";

    private Task _sub = null;

    private boolean _first = true;

    private boolean _stopped = false;

    private boolean _active = false;

    // (2026-08-22 横跳去抖) 任务 onTick 每 tick 全量重估子任务, 当多个候选
    // 子任务条件同时满足 (如合成/熔炉链: 缺材料↔缺容器↔清光标) 时, onTick
    // 返回值会在候选间高频横跳 (用户实测 17:12: DoStuffInFurnaceTask ↔
    // MineAndCollect、CraftInTable ↔ Craft 2x2 crafting_table 等 6x/5s)。
    // 记录"上一次被 stop 的子任务": 若新子任务与之相同 (来回横跳 A→B→A→B)
    // 且距上次切换 < 2 秒, 拒绝切换 — 保持当前 _sub 让任务稳定推进。全新
    // 目标 (单向切换) 不受影响。仅抑制"来回横跳", 不掩盖正常切换。
    private Task _lastStoppedSub = null;
    private long _lastSwitchMs = 0;
    private static final long SWITCH_DEBOUNCE_MS = 2000;

    // 递归保险丝：正常任务链深度 ~10。任务环（A 要 B、B 又要 A 的新实例）
    // 会在单个 tick 内无限递归直至 StackOverflowError 崩游戏
    // （2026-08-21 11:46 崩溃实例：做斧→合成台→木板→收集原木→又要做斧）。
    // 超过深度上限时记错误日志并放弃本 tick，游戏不再崩溃。
    private static final int MAX_TICK_DEPTH = 128;
    private static int _tickDepth = 0;

    // (2026-09-15 统一超时标签) 当前正在 onTick 的任务, 供 TimeoutLog 在
    // 进度检查器/任务超时分支里归属"是哪个任务被超时逼跳"。tick() 进出时
    // 保存/恢复, 子任务 tick 期间自然指向子任务, 零行为影响。
    private static final ThreadLocal<Task> _currentTicking = new ThreadLocal<>();

    public static Task getCurrentTickingTask() {
        return _currentTicking.get();
    }

    public void tick(AltoClef mod, TaskChain parentChain) {
        if (_tickDepth >= MAX_TICK_DEPTH) {
            Debug.logInternal("(task) TASK CHAIN RECURSION OVERFLOW (depth " + _tickDepth
                    + ")! Task cycle detected, skipping tick. Top: " + this
                    + " sub: " + _sub);
            return;
        }
        _tickDepth++;
        Task prevTicking = _currentTicking.get();
        _currentTicking.set(this);
        try {
            tickInternal(mod, parentChain);
        } finally {
            _currentTicking.set(prevTicking);
            _tickDepth--;
        }
    }

    private void tickInternal(AltoClef mod, TaskChain parentChain) {
        parentChain.addTaskToChain(this);
        if (_first) {
            Debug.logInternal("Task START: " + this);
            TaskSwitchLogger.taskStarted(this, parentChain);
            _active = true;
            onStart(mod);
            _first = false;
            _stopped = false;
        }
        if (_stopped) return;

        Task newSub = onTick(mod);
        // Debug state print
        if (!_oldDebugState.equals(_debugState)) {
            Debug.logInternal(toString());
            // (2026-09-03 日志系统完善, 用户"应有一文件保存左上角 debugState 变化历史",
            // 便于复现卡死时精确定位状态机走到哪一步) debugState 变化时同步持久化到
            // altoclef_debugstate.log (位于游戏目录)。toString() 已含 toDebugString +
            // 当前 debugState, 与左上角显示一致。变化才写, 不刷屏。
            Debug.logToFile("altoclef_debugstate.log", toString());
            _oldDebugState = _debugState;
        }
        // We have a sub task
        if (newSub != null) {
            if (!newSub.isEqual(_sub)) {
                if (canBeInterrupted(mod, _sub, newSub)) {
                    // (2026-08-22 横跳去抖) 若新子任务 == 上一次被 stop 的
                    // 子任务 (来回横跳 A→B→A→B) 且距上次切换 < 2 秒 → 拒绝
                    // 切换, 保持当前 _sub。全新目标不受影响。
                    // (2026-09-14 豁免, 212757 场次) 当前 _sub 已 isFinished
                    // (僵尸兜底任务: 掉物已消失还在 wander) 时不再占位 ——
                    // 父层边沿回切被去抖吞掉 1.55s, 期间零 interact tick,
                    // 2s barterTimeout 照样到期 → 刚成功的猪灵被误拉黑。
                    long nowMs = System.currentTimeMillis();
                    boolean subZombie = _sub != null && _sub.isFinished(mod);
                    boolean bounceBack = !subZombie
                            && newSub.isEqual(_lastStoppedSub)
                            && (nowMs - _lastSwitchMs) < SWITCH_DEBOUNCE_MS;
                    if (bounceBack && subZombie) {
                        Debug.logInternal("(Task.tickInternal: bounce debounce bypassed, current sub isFinished zombie)");
                    }
                    if (!bounceBack) {
                        // Our sub task is new
                        if (_sub != null) {
                            // Our previous sub must be interrupted.
                            _sub.stop(mod, newSub);
                        }
                        // [Diag-chain] include owning chain's priority so the
                        // log shows which chain/priority this branch runs under.
                        TaskSwitchLogger.subTask(parentChain, 0f, this, _sub, newSub);
                        _lastStoppedSub = _sub;
                        _lastSwitchMs = nowMs;
                        _sub = newSub;
                    }
                }
            }

            // (2026-08-23 NPE 防御) 极端情况下 (isEqual(null) 误判 / 竞态 /
            // canBeInterrupted 异常) newSub 非空但 _sub 仍为 null — 直接
            // _sub.tick 会 NPE 崩游戏 (2026-08-23 15:58 进下界崩溃实例:
            // Task.tickInternal:98 _sub null)。newSub 是真实子任务, 强制
            // 赋上再 tick; 若 _sub 非空 (正在执行), 保持原值不受影响。
            if (_sub == null) {
                _sub = newSub;
            }

            // Run our child
            _sub.tick(mod, parentChain);
        } else {
            // We are null
            if (_sub != null && canBeInterrupted(mod, _sub, null)) {
                // Our previous sub must be interrupted.
                _sub.stop(mod);
                TaskSwitchLogger.subTask(parentChain, 0f, this, _sub, null);
                _sub = null;
            }
        }
    }

    public void reset() {
        _first = true;
        _active = false;
        _stopped = false;
    }

    public void stop(AltoClef mod) {
        stop(mod, null);
    }

    /**
     * Stops the task. Next time it's run it will run `onStart`
     */
    public void stop(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        TaskSwitchLogger.taskStopped(this, interruptTask);
        Debug.logInternal("Task STOP: " + this + ", interrupted by " + interruptTask);
        if (!_first) {
            onStop(mod, interruptTask);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.stop(mod, interruptTask);
        }

        _first = true;
        _active = false;
        _stopped = true;
    }

    /**
     * Lets the task know it's execution has been "suspended"
     *
     * STILL RUNS `onStop`
     *
     * Doesn't stop it all-together (meaning `isActive` still returns true)
     */
    public void interrupt(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        if (!_first) {
            onStop(mod, interruptTask);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.interrupt(mod, interruptTask);
        }

        _first = true;
    }

    protected void setDebugState(String state) {
        if (state == null) {
            state = "";
        }
        _debugState = state;
    }

    // Virtual
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    public boolean isActive() {
        return _active;
    }

    public boolean stopped() {
        return _stopped;
    }

    protected abstract void onStart(AltoClef mod);

    protected abstract Task onTick(AltoClef mod);

    // interruptTask = null if the task stopped cleanly
    protected abstract void onStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqual(Task other);

    protected abstract String toDebugString();

    /**
     * (2026-09-12 诊断) 本任务当前操作的"目标方块"坐标, 供 TaskStackSnapshot
     * 打印目标位及邻块现场 (放置不下/交互失败时唯一缺失的证据: 目标格是什么
     * 方块、有没有可放置面、是否已被 BlockTracker 拉黑)。
     *
     * 默认 null = 该任务无单一目标位。纯只读, 不参与任何决策, 不改变行为;
     * 只在需要现场证据的任务里覆写 (放置/交互)。
     */
    public net.minecraft.util.math.BlockPos getDiagTargetBlock() {
        return null;
    }

    @Override
    public String toString() {
        return "<" + toDebugString() + "> " + _debugState;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Task task) {
            return isEqual(task);
        }
        return false;
    }

    public boolean thisOrChildSatisfies(Predicate<Task> pred) {
        Task t = this;
        while (t != null) {
            if (pred.test(t)) return true;
            t = t._sub;
        }
        return false;
    }

    public boolean thisOrChildAreTimedOut() {
        return thisOrChildSatisfies(task -> task instanceof TimeoutWanderTask);
    }

    /**
     * Sometimes a task just can NOT be bothered to be interrupted right now.
     * For instance, if we're in mid air and MUST complete the parkour movement.
     */
    private boolean canBeInterrupted(AltoClef mod, Task subTask, Task toInterruptWith) {
        if (subTask == null) return true;
        // Our task can declare that is FORCES itself to be active NOW.
        return (subTask.thisOrChildSatisfies(task -> {
            if (task instanceof ITaskCanForce canForce) {
                return !canForce.shouldForce(mod, toInterruptWith);
            }
            return true;
        }));
    }
}
