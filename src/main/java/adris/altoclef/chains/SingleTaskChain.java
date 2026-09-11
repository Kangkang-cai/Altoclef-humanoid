package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasksystem.TaskSwitchLogger;
import adris.altoclef.util.time.Stopwatch;

public abstract class SingleTaskChain extends TaskChain {

    private final Stopwatch _taskStopwatch = new Stopwatch();
    protected Task _mainTask = null;
    private boolean _interrupted = false;

    private AltoClef _mod;

    public SingleTaskChain(TaskRunner runner) {
        super(runner);
        _mod = runner.getMod();
    }

    @Override
    protected void onTick(AltoClef mod) {
        if (!isActive()) return;

        if (_interrupted) {
            _interrupted = false;
            if (_mainTask != null) {
                _mainTask.reset();
            }
            TaskSwitchLogger.chainResumed(this, _mainTask);
            // [Diag-chain] this chain just regained ownership after being
            // interrupted (e.g. user task resuming after a mob-defense
            // retreat). If "no task running" shows up AFTER a retreat but we
            // never see this line for "User Tasks", the resume path is broken.
            String m = "[Diag-chain] " + getName() + " resuming after interruption, mainTask=" + _mainTask;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }

        if (_mainTask != null) {
            if ((_mainTask.isFinished(mod)) || _mainTask.stopped()) {
                TaskSwitchLogger.chainTaskEnded(this, _mainTask, _mainTask.isFinished(mod), _mainTask.stopped());
                // [Diag-chain] capture WHY the main task ended — "no task
                // running" after retreats traces back here. isFinished on a
                // speedrun task means CreditsScreen; stopped means someone
                // called Task.stop() on it.
                String m = "[Diag-chain] " + getName() + " main task ending: isFinished=" + _mainTask.isFinished(mod)
                        + " stopped=" + _mainTask.stopped() + " task=" + _mainTask;
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
                onTaskFinish(mod);
                if (_mainTask != null && (_mainTask.isFinished(mod) || _mainTask.stopped())) {
                    // The subclass's onTaskFinish did not replace the dead task:
                    // drop it so we don't sit on a finished/stopped task forever
                    // (a winning chain with a dead task ticks nothing and renders
                    // as "(no task running)" while also re-logging every tick).
                    // Safe for all chains: every SingleTaskChain except
                    // UserTaskChain overrides isActive() to true, so they keep
                    // getting getPriority() called and can setTask() a fresh
                    // task when they need one again.
                    _mainTask = null;
                }
            } else {
                _mainTask.tick(mod, this);
            }
        }
    }

    protected void onStop(AltoClef mod) {
        if (isActive() && _mainTask != null) {
            _mainTask.stop(mod);
            _mainTask = null;
        }
    }

    public void setTask(Task task) {
        if (_mainTask == null || !_mainTask.equals(task)) {
            if (_mainTask != null) {
                _mainTask.stop(_mod, task);
            }
            // [Diag-chain] include the chain's own priority so logs show which
            // chain (by name+priority) holds this MAIN task.
            // 注意: 绝不能在 setTask 里调用 getPriority(_mod) —— getPriority 会触发链决策
            // (如 MobDefense.stateEvaluate 内部再 setTask), 会造成 setTask→getPriority→
            // setTask 无限递归 StackOverflow(2026-08-31 实崩)。日志里优先级用固定 0 占位,
            // 真实优先级仍在你 TaskRunner 仲裁逐 tick 打印, 不影响溯源。
            TaskSwitchLogger.mainTask(this, 0f, _mainTask, task);
            _mainTask = task;
            if (task != null) task.reset();
        }
    }


    @Override
    public boolean isActive() {
        return _mainTask != null;
    }

    protected abstract void onTaskFinish(AltoClef mod);

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        Debug.logInternal("Chain Interrupted: " + this + " by " + other.toString());
        TaskSwitchLogger.chainSuspended(this, other);
        // Stop our task. When we're started up again, let our task know we need to run.
        _interrupted = true;
        if (_mainTask != null && _mainTask.isActive()) {
            _mainTask.interrupt(mod, null);
        }
    }

    protected boolean isCurrentlyRunning(AltoClef mod) {
        // (2026-09-11 16:40 客户端崩溃修复) 原实现缺 _mainTask != null 判空:
        // "_mainTask 为 null" 对绝大多数链是**常态** —— 链按需 setTask, 平时没有任务
        // (如 WorldSurvivalChain 只在岩浆/火焰/溺水时才派任务)。而 getPriority() 是
        // TaskRunner 每 tick 对【所有】链调用的 (不是只调 active 的链), 所以任何
        // "在 getPriority 里裸调 isCurrentlyRunning" 的链, 只要恰好 _mainTask 为 null
        // 且进入该分支就会 NPE 崩掉整个客户端。
        // 实崩: WorldSurvivalChain.tryDrowningEscape:195 —— bot 挖穿掉进封闭水体后
        // 空气 <25% 触发防溺水, _mainTask 为 null → NPE (crash 16.40.19-client.txt)。
        // 语义上"没有任务"当然不算"正在跑任务", 加判空是纯防御, 对非 null 路径零行为变化。
        return !_interrupted && _mainTask != null && _mainTask.isActive() && !_mainTask.isFinished(mod);
    }

    public Task getCurrentTask() {
        return _mainTask;
    }
}
