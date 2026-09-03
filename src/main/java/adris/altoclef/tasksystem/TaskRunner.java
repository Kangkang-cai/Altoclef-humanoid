package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;

import java.util.ArrayList;

public class TaskRunner {

    private final ArrayList<TaskChain> _chains = new ArrayList<>();
    private final AltoClef _mod;
    private boolean _active;

    private TaskChain _cachedCurrentTaskChain = null;
    // [Diag-chain] throttle for "winner ticked 0 tasks" (the exact state behind
    // the overlay's "(no task running)"). Log the first occurrence immediately,
    // then at most once every ~5s so a stuck bot doesn't flood the log.
    private long _lastEmptyWinnerLogMs = 0;
    // (2026-09-03) JankChain 慢链日志节流 (0.5s)。实例字段: 局部变量每 tick 重置
    // 导致节流失效、每 tick 刷屏。
    private long _lastSlowChainLogMs = 0;

    // 决策降频：决策链（用户任务/食物/死亡恢复）的任务树每 N 个游戏 tick
    // 执行一次（默认 4 = 5Hz，更接近人类决策速度）。链仲裁（优先级抢占、
    // 反射链如 MLG/岩浆逃生/怪物防御）始终保持 20Hz，安全不受影响。
    // Settings.taskTickInterval = 1 可恢复每 tick 执行。
    private int _tickCounter = 0;

    public TaskRunner(AltoClef mod) {
        _mod = mod;
        _active = false;
    }

    public void tick() {
        if (!_active) return;
        // World not loaded (death respawn / disconnect / dimension switch):
        // freeze chain arbitration entirely. Running it anyway picks
        // UserTaskChain (fixed prio 50) as winner while every other chain
        // returns -inf, which spuriously interrupts the actually-running chain
        // (e.g. mid-retreat Mob Defense) and can leave task state corrupted —
        // observed ending in a permanent "no task running".
        if (!AltoClef.inGame()) return;
        ++_tickCounter;
        TaskSwitchLogger.setTick(_tickCounter);
        // Get highest priority chain and run
        TaskChain maxChain = null;
        float maxPriority = Float.NEGATIVE_INFINITY;
        final long MS = 1000_000L;
        // (2026-09-03 诊断) 每链 getPriority 耗时定位: JankDebug 显示 ~200ms 全在
        // TaskRunner.tick, 但不知道是哪条链的仲裁/任务树吃掉。单链>30ms 才记录,
        // 0.5s 节流防刷屏。
        for (TaskChain chain : _chains) {
            if (!chain.isActive()) continue;
            long c0 = System.nanoTime();
            float priority = chain.getPriority(_mod);
            long chainMs = (System.nanoTime() - c0) / MS;
            if (chainMs > 30 && System.currentTimeMillis() - _lastSlowChainLogMs > 500) {
                _lastSlowChainLogMs = System.currentTimeMillis();
                Debug.logToFile("altoclef_jank.log",
                        "[JankChain] " + chain.getName() + ".getPriority took " + chainMs + "ms"
                                + " tick=" + _tickCounter);
            }
            if (priority > maxPriority) {
                maxPriority = priority;
                maxChain = chain;
            }
        }
        if (_cachedCurrentTaskChain != null && maxChain != _cachedCurrentTaskChain) {
            // ==== [Diag-chain] ownership change — THE key log for "no task running" ====
            // Shows exactly when the bot switches masters (e.g. User Tasks ->
            // Mob Defense during a retreat, and crucially whether it ever
            // switches BACK). If the bot ends up idle, the last switch here
            // tells us who stole ownership and never gave it back.
            String m = "[Diag-chain] ownership change: " + _cachedCurrentTaskChain.getName()
                    + " -> " + (maxChain == null ? "NONE" : maxChain.getName())
                    + " (new prio " + maxPriority + ")";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            TaskSwitchLogger.owner(_cachedCurrentTaskChain, maxChain, maxPriority);
            _cachedCurrentTaskChain.onInterrupt(_mod, maxChain);
        }
        _cachedCurrentTaskChain = maxChain;

        if (maxChain != null) {
            // 决策链降频：非反射链每 taskTickInterval 个 tick 执行一次任务树。
            // 反射链（shouldTickEveryTick）保持 20Hz。仲裁本身不受影响。
            int interval = Math.max(1, _mod.getModSettings().getTaskTickInterval());
            if (maxChain.shouldTickEveryTick() || (_tickCounter % interval) == 0) {
                long t0 = System.nanoTime();
                maxChain.tick(_mod);
                long tickMs = (System.nanoTime() - t0) / MS;
                if (tickMs > 30 && System.currentTimeMillis() - _lastSlowChainLogMs > 500) {
                    _lastSlowChainLogMs = System.currentTimeMillis();
                    Debug.logToFile("altoclef_jank.log",
                            "[JankChain] " + maxChain.getName() + ".tick took " + tickMs + "ms"
                                    + " tick=" + _tickCounter + " tasks=" + maxChain.getTasks());
                }
            }
        }

        // ==== [Diag-chain] the winner ran NO task this tick — this is exactly
        // what renders as "(no task running)" in the overlay. Record who owns
        // the bot while idle, so we can trace which chain ate the user task.
        if (maxChain != null && maxChain.getTasks().isEmpty() && AltoClef.inGame()) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - _lastEmptyWinnerLogMs > 5000) {
                _lastEmptyWinnerLogMs = nowMs;
                String m = "[Diag-chain] winner " + maxChain.getName() + " (prio " + maxPriority
                        + ") ticked 0 tasks -> overlay shows \"no task running\""
                        + " | userTaskChainActive=" + _mod.getUserTaskChain().isActive()
                        + " | userTask=" + _mod.getUserTaskChain().getCurrentTask();
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
        } else {
            _lastEmptyWinnerLogMs = 0;
        }
    }

    public void addTaskChain(TaskChain chain) {
        _chains.add(chain);
    }

    public void enable() {
        if (!_active) {
            _mod.getBehaviour().push();
            _mod.getBehaviour().setPauseOnLostFocus(false);
            TaskSwitchLogger.sessionStart();
        }
        _active = true;
    }

    public void disable() {
        if (_active) {
            _mod.getBehaviour().pop();
        }
        for (TaskChain chain : _chains) {
            chain.stop(_mod);
        }
        _active = false;

        Debug.logMessage("Stopped");
    }

    public TaskChain getCurrentTaskChain() {
        return _cachedCurrentTaskChain;
    }

    // Kinda jank ngl
    public AltoClef getMod() {
        return _mod;
    }
}
