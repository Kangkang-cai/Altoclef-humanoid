package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.bridge.BridgeConfig;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasks.movement.SubBotBootstrapTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.util.helpers.BotRole;
import adris.altoclef.util.time.Stopwatch;

// A task chain that runs a user defined task at the same priority.
// This basically replaces our old Task Runner.
@SuppressWarnings("ALL")
public class UserTaskChain extends SingleTaskChain {

    private final Stopwatch _taskStopwatch = new Stopwatch();
    private Runnable _currentOnFinish = null;

    private boolean _runningIdleTask;
    private boolean _nextTaskIdleFlag;

    public UserTaskChain(TaskRunner runner) {
        super(runner);
    }

    private static String prettyPrintTimeDuration(double seconds) {
        int minutes = (int) (seconds / 60);
        int hours = minutes / 60;
        int days = hours / 24;

        String result = "";
        if (days != 0) {
            result += days + " days ";
        }
        if (hours != 0) {
            result += (hours % 24) + " hours ";
        }
        if (minutes != 0) {
            result += (minutes % 60) + " minutes ";
        }
        if (!result.equals("")) {
            result += "and ";
        }
        result += String.format("%.3f", (seconds % 60));
        return result;
    }

    @Override
    protected void onTick(AltoClef mod) {

        // Pause if we're not loaded into a world.
        if (!mod.inGame()) return;

        super.onTick(mod);
    }

    /**
     * (2026-10-06 次 bot 自动引导) {@code role=sub} 且 {@code bridge.json} 配了 {@code leader}
     * 时，任务链一空闲就自动拉起 {@link SubBotBootstrapTask} —— 不依赖对端发命令，也不需要在
     * settings 里配 {@code idleCommand}。
     *
     * <p>⚠️ <b>调用方必须是 {@code AltoClef.onClientTick()}，不能挂在本链自己的 {@code onTick}</b>：
     * {@code TaskRunner.tick} 用 {@code if (!chain.isActive()) continue;} 过滤仲裁候选，且只对
     * <b>胜出的那条链</b>调 {@code tick()}；而 {@code UserTaskChain.isActive()} 就是
     * {@code _mainTask != null}（SingleTaskChain:98，本链刻意不覆写 —— 见 SingleTaskChain:60-63）。
     * 于是"链空闲"恰恰是"本链 isActive()=false"的时刻 ⇒ 挂在 onTick 上<b>永远不会执行</b>。
     * 同一坑 {@code DeathRecoveryChain:122-124} 有记录。</p>
     *
     * <p>幂等：引导任务是<b>常驻</b>的（只换内部子任务，自身几乎不结束），所以拉起一次之后
     * {@code _mainTask} 恒非空，不会重复拉起。</p>
     *
     * <p>与 {@code idleCommand} 的关系：次 bot 引导优先。若两者都启用，引导任务先占住任务位，
     * {@code idleCommand} 自然不会被触发（它只在链空闲时跑）。</p>
     *
     * <p>关闭方式：把 {@code leader} 清空，或把 {@code role} 改回 {@code main}。</p>
     */
    public void maybeStartSubBotBootstrap(AltoClef mod) {
        if (_mainTask != null) return;
        if (!BotRole.isSub()) return;
        String leader = BridgeConfig.getInstance().leader;
        if (leader == null || leader.isBlank()) return;
        runTask(mod, new SubBotBootstrapTask(leader.trim()), null);
    }

    public void cancel(AltoClef mod) {
        if (_mainTask != null && _mainTask.isActive()) {
            stop(mod);
            onTaskFinish(mod);
        }
    }

    @Override
    public float getPriority(AltoClef mod) {
        // (2026-09-23 睡眠窗口优先, 事故 20260923-115851) beat 夜间睡眠/弄床
        // 路由活跃时提升到 58 (>Food 收集食物 55): 否则睡眠任务在链层永远被
        // Food 抢占, 一个 tick 都跑不到, 还与 Food 的悬空让位构成结构性死锁。
        // 严格跟随上一拍路由结果: 睡觉失败 30s 冷却 / 找床约 30s 无机会放弃
        // 期间路由返回 null, 优先级立即回落 50, 保持"长期找不到床不堵塞有
        // 价值的事"的既有设计。Mob Defense 65、World Survival 95 仍高于此。
        if (_mainTask instanceof BeatMinecraft2Task beat && beat.isNightSleepRoutingActive()) {
            return 58;
        }
        return 50;
    }

    @Override
    public String getName() {
        return "User Tasks";
    }

    public void runTask(AltoClef mod, Task task, Runnable onFinish) {
        _runningIdleTask = _nextTaskIdleFlag;
        _nextTaskIdleFlag = false;

        _currentOnFinish = onFinish;

        if (!_runningIdleTask) {
            Debug.logMessage("User Task Set: " + task.toString());
        }
        mod.getTaskRunner().enable();
        _taskStopwatch.begin();
        setTask(task);

        if (mod.getModSettings().failedToLoad()) {
            Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the" +
                    " file to re-load working settings.");
        }
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        boolean shouldIdle = mod.getModSettings().shouldRunIdleCommandWhenNotActive();
        {
            // [Diag-chain] the user task chain just went inactive — from here on
            // the bot idles ("no task running") until a new command is issued.
            // shouldIdle=false additionally DISABLES the whole task runner.
            String m = "[Diag-chain] UserTaskChain.onTaskFinish: shouldIdle=" + shouldIdle
                    + (shouldIdle ? " (idle command will run)" : " (task runner will be DISABLED)");
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
        // (1002 17:1x 修复，对端现场锁定) **必须先抓 oldTask**：下面的 disable() 会经
        // TaskRunner.disable → 每链 TaskChain.stop → SingleTaskChain.onStop
        // 执行 `_mainTask.stop(mod); _mainTask = null;`。旧顺序在此之后才读 _mainTask
        // ⇒ 拿到 null ⇒ 发布 TaskFinishedEvent(seconds, null) ⇒ ActionDispatcher
        // 认不出结束的任务（不发 action_done、_active 永久残留，探针继续对死行动报"已到位"），
        // EventCollector 也只能退化成"完成了一项任务"。
        // 触发条件：shouldIdle=false（idleCommand 为空）才会走到 disable()。
        // 任务本身仍由 onStop 正常 stop()，行为不缺失。_mainTask = null 保持幂等。
        double seconds = _taskStopwatch.time();
        Task oldTask = _mainTask;
        if (!shouldIdle) {
            // Stop.
            mod.getTaskRunner().disable();
            // Extra reset. Sometimes baritone is laggy and doesn't properly reset our press
            mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
        }
        _mainTask = null;
        if (_currentOnFinish != null) {
            //noinspection unchecked
            _currentOnFinish.run();
        }
        // our `onFinish` might have triggered more tasks.
        boolean actuallyDone = _mainTask == null;
        if (actuallyDone) {
            if (!_runningIdleTask) {
                Debug.logMessage("User task FINISHED. Took %s seconds.", prettyPrintTimeDuration(seconds));
                EventBus.publish(new TaskFinishedEvent(seconds, oldTask));
            }
            if (shouldIdle) {
                AltoClef.getCommandExecutor().executeWithPrefix(mod.getModSettings().getIdleCommand());
                signalNextTaskToBeIdleTask();
                _runningIdleTask = true;
            }
        }
    }

    public boolean isRunningIdleTask() {
        return isActive() && _runningIdleTask;
    }

    // The next task will be an idle task.
    public void signalNextTaskToBeIdleTask() {
        _nextTaskIdleFlag = true;
    }
}
