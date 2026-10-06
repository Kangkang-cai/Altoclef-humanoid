package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.speedrun.BeatMinecraftConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.SleepOverride;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.SleepHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;

import java.util.function.Supplier;

/**
 * (2026-10-06 次 bot 自动引导, 用户拍板"一次性前置 → 自动转跟随") <b>次 bot 的常驻宿主</b>:
 * 空闲就自动把木头/食物补到"次 bot 档", 达标后自动进入随行 —— <b>完全不需要对端发命令</b>。
 *
 * <h3>三阶段</h3>
 * <ol>
 *   <li><b>前置</b>: 木头 &lt; {@code woodLogsTarget(8)} 或 食物 &lt; {@code foodTarget(40)}
 *       → 跑 {@link BeatMinecraft2Task} <b>本体</b>（完全复用主线的砍树/造斧/回地表/收食物/
 *       烹饪/夜间路由 —— 用户 20:1x 拍板"还是得用原本主线的智能程度"）。达标 → 置 static 闩,
 *       进随行。<b>每个 JVM 会话只做一次</b>（与 BeatMinecraft2Task 的 {@code _fireResPhaseDone}
 *       同款 static 闩）—— 之后即使背包丢了也不重做, 缺口按设计由主 bot 用 {@code @give} 补。</li>
 *   <li><b>睡眠镜像</b>: 多人服「全员入睡才跳夜」⇒ 主 bot 躺下时次 bot 也必须躺。详见
 *       {@link #tickMirrorSleep}。</li>
 *   <li><b>随行</b>: 返回 {@link FollowAndWorkTask}(leader) —— 贴 3 格 + 顺手搜刮/挖矿。</li>
 * </ol>
 *
 * <h3>睡眠镜像（2026-10-06 20:5x 用户拍板）</h3>
 * 语义 = <b>"主 bot 躺下了咱就睡，没床就触发收集；30 秒没睡成就今晚彻底放弃"</b>。
 * <ul>
 *   <li><b>进入</b>: 夜晚 且 主 bot 持续在睡 1s（{@code isSleeping} 去抖）。</li>
 *   <li><b>执行</b>: {@link SleepThroughNightTask} —— 有床就地睡; 没床它会<b>自己派发
 *       {@code CollectBedTask}</b>（96 格内找床 / 羊毛+木板造床）。</li>
 *   <li><b>放弃</b>（两条, 阈值与主线同源 30s）: ① 未上床时"就近无机会"（{@link
 *       SleepHelper#hasSleepChanceTonight}); ② 已上床后 30s 仍未天亮（有人没睡）。
 *       两条都走 {@link SleepOverride#requestSkipThisNight()} = 本夜不睡, 白天自动恢复。</li>
 *   <li><b>结束</b>: 天亮。</li>
 * </ul>
 * ⚠️ 主 bot 侧有对称的出口（{@code BeatMinecraft2Task} 内 {@code _inBedNoDawnTimer},
 * 打出 {@code [SleepPeerWait]}）—— 两边阈值一致, <b>无需 bot 间通信</b>即可同步放弃。
 *
 * <h3>为什么它是"常驻不结束"的</h3>
 * 本类只换内部子任务、自身几乎不结束。这点是<b>硬要求</b>: 一旦它结束, 链会走
 * {@code UserTaskChain.onTaskFinish} 的"空闲→自动重新拉起"路径（自动引导入口见
 * {@code AltoClef.onClientTick} 内的 maybeStartSubBotBootstrap）, 若它瞬间就结束会形成
 * 每 tick 一次的拉起循环。唯一结束出口是"跟随连续失败 {@link #MAX_CONSECUTIVE_FAILURES} 次"。
 *
 * <h3>⚠️ 出口写在调用侧</h3>
 * {@code Task.tickInternal} 不消费子任务的 {@code isFinished}, 替换权在父链。所以前置子任务
 * 是否跑完、跟随子任务是否终止, 都由这里每拍主动轮询。
 *
 * <h3>与 @follow 的关系</h3>
 * 本任务是 {@code role=sub} 且 {@code bridge.json} 配了 {@code leader} 时自动拉起的等价物;
 * 对端手动 {@code @follow <名> work} 走的是 {@link FollowAndWorkTask} 本身（无前置、无睡眠镜像）。
 */
public class SubBotBootstrapTask extends Task {

    /** 跟随目标不可达时的重试间隔。 */
    private static final long RETRY_COOLDOWN_MS = 60_000;

    /** 跟随连续失败多少次后结束本次引导（把控制权交回链, 由上层冷却后重新拉起）。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** 睡眠放弃阈值（秒）: 与主线 {@code _inBedNoDawnTimer} / {@code _nightBedFindTimer} 同源。 */
    private static final double SLEEP_GIVEUP_SEC = 30;

    /** leader "持续在睡"去抖时长: 避免瞬时 isSleeping 抖动误触发整个睡眠镜像。 */
    private static final long LEADER_SLEEP_DEBOUNCE_MS = 1_000;

    /** 前置是否已完成。static = 跨实例/跨重连存活, 同一 JVM 会话只做一次。 */
    private static boolean _prepDone = false;

    private final String _leader;

    /** 前置子任务缓存与它的种类标记 —— 每 tick new 会打断任务自己的进度。 */
    private Task _prep = null;
    private String _prepKind = null;

    /** 跟随子任务缓存。 */
    private FollowAndWorkTask _follow = null;

    private long _retryAfterMs = 0;
    private int _failures = 0;
    private boolean _finished = false;

    // ------------------------------------------------------------------ //
    // 睡眠镜像状态
    // ------------------------------------------------------------------ //

    /** 睡觉子任务缓存（SleepThroughNightTask 每 tick new 会让下层无法维持进度）。 */
    private SleepThroughNightTask _sleepTask = null;
    /** 本夜是否已进入睡眠镜像。 */
    private boolean _mirrorSleep = false;
    /** 本夜是否已放弃睡觉（白天自动清除, 避免反复进入镜像空转）。 */
    private boolean _mirrorSleepGivenUpTonight = false;
    /** "未上床"阶段的 30s 计时（有就近机会则重置继续找床）。 */
    private final TimerGame _mirrorBedFindTimer = new TimerGame(SLEEP_GIVEUP_SEC);
    /** "已上床"阶段的 30s 计时（到点仍未天亮 = 有人没睡）。 */
    private final TimerGame _mirrorInBedTimer = new TimerGame(SLEEP_GIVEUP_SEC);
    /** leader 连续在睡的起点（0 = 当前不在睡）。 */
    private long _leaderSleepSeenMs = 0;

    public SubBotBootstrapTask(String leader) {
        _leader = leader;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_finished) return null;
        if (!_prepDone) return tickPrep(mod);
        Task sleep = tickMirrorSleep(mod);
        if (sleep != null) return sleep;
        return tickFollow(mod);
    }

    // ------------------------------------------------------------------ //
    // 阶段一: 前置（跑主线本体, 达标即切）
    // ------------------------------------------------------------------ //

    /**
     * 前置: 木头/食物任一不足 → 返回 {@link BeatMinecraft2Task} <b>本体</b>。
     *
     * <p>为什么不自己写精简版（旧实现）: 精简版没有造石斧、没有回地表、没有食物的峰值锁存/
     * 熔炼/远征、没有夜间路由 —— 用户实测"特别不智能"。跑主线本体则<b>一行不复刻</b>地拿到
     * 全部智能; 次 bot 是 {@code role=sub}, 目标已被 {@code BotRole} 降到 8/40, 达标即被切走,
     * 不会一路跑到下界。
     *
     * <p><b>刻意不设总超时</b>（用户 20:2x "不用"）: 主线本体不会卡死（它一直在推进）,
     * 没有"永久卡在前置"的风险。
     */
    private Task tickPrep(AltoClef mod) {
        BeatMinecraftConfig cfg = BeatMinecraft2Task.getConfig();
        int woodTarget = (cfg == null) ? 0 : cfg.woodLogsTarget();
        int foodTarget = (cfg == null) ? 0 : cfg.foodTarget();

        int wood = StorageHelper.countItemsInInventoryReal(mod, ItemHelper.LOG);
        int food = (int) StorageHelper.calculateInventoryFoodScore(mod);

        if (wood >= woodTarget && food >= foodTarget) {
            _prepDone = true;
            String m = "[SUB-BOOT] prep done (wood " + wood + "/" + woodTarget
                    + ", food " + food + "/" + foodTarget + "), entering follow of " + _leader;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return null;
        }
        setDebugState("[次bot引导] 前置(主线智能) 木头 " + wood + "/" + woodTarget
                + " 食物 " + food + "/" + foodTarget);
        return cachedPrep(mod, "mainline", BeatMinecraft2Task::new);
    }

    /**
     * 前置子任务缓存。每 tick `new` 会打断任务自己的进度（囤木会不停地从零重来）,
     * 所以同种类复用同一实例; 只有"换种类"或"已跑完"才重建。
     * （"跑完要重建"这条来自框架铁律: tickInternal 不消费子任务 isFinished, 得由这里收。）
     */
    private Task cachedPrep(AltoClef mod, String kind, Supplier<Task> maker) {
        if (!kind.equals(_prepKind) || _prep == null || _prep.isFinished(mod)) {
            _prepKind = kind;
            _prep = maker.get();
        }
        return _prep;
    }

    // ------------------------------------------------------------------ //
    // 阶段二: 睡眠镜像（主 bot 躺下 → 次 bot 跟着睡）
    // ------------------------------------------------------------------ //

    /**
     * 镜像主 bot 的睡眠。<b>优先级最高</b>——盖过随行与顺手干活: 多人服「全员入睡才跳夜」,
     * 次 bot 不睡主 bot 就一直躺着干等。
     *
     * @return 要执行的任务（睡觉），或 null（本拍不睡）
     */
    private Task tickMirrorSleep(AltoClef mod) {
        // 天亮 = 本夜结束: 解除全部本夜闩锁（含"已放弃"，让下一夜可以重新尝试）。
        if (WorldHelper.isBrightDaytime()) {
            if (_mirrorSleep) {
                Debug.logToFile("altoclef_diag.log", "[SUB-BOOT] mirror sleep done (dawn), back to follow");
            }
            _mirrorSleep = false;
            _mirrorSleepGivenUpTonight = false;
            _leaderSleepSeenMs = 0;
            return null;
        }
        // 本夜已放弃（自身放弃 或 对端 @sleep skip）→ 不再进入。
        if (_mirrorSleepGivenUpTonight || SleepOverride.isSkipping()) return null;

        if (!_mirrorSleep) {
            if (!leaderSleepingDebounced(mod)) return null;
            _mirrorSleep = true;
            _mirrorBedFindTimer.reset();
            _mirrorInBedTimer.reset();
            Debug.logToFile("altoclef_diag.log",
                    "[SUB-BOOT] mirror sleep start: leader '" + _leader + "' is sleeping");
        }

        if (_sleepTask == null) _sleepTask = new SleepThroughNightTask();

        boolean inBed = mod.getPlayer() != null && mod.getPlayer().isSleeping();
        if (inBed) {
            // 已上床: 找床计时无意义, 改等跳夜。
            _mirrorBedFindTimer.reset();
            if (_mirrorInBedTimer.elapsed()) {
                // 躺下 30s 仍未天亮 = 有人没睡（例如第三个 bot）→ 本夜放弃（与主线同源）。
                SleepHelper.wakeUpIfSleeping(mod, "SubBotPeerWait");
                _mirrorSleep = false;
                _mirrorSleepGivenUpTonight = true;
                SleepOverride.requestSkipThisNight();
                Debug.logToFile("altoclef_diag.log",
                        "[SUB-BOOT] mirror sleep give up tonight (in bed 30s without dawn)");
                return null;
            }
        } else {
            // 未上床: 等跳夜计时无意义, 改找床计时。
            _mirrorInBedTimer.reset();
            if (_mirrorBedFindTimer.elapsed()) {
                if (SleepHelper.hasSleepChanceTonight(mod)) {
                    _mirrorBedFindTimer.reset();   // 就近还有机会, 继续找/做床
                } else {
                    _mirrorSleep = false;
                    _mirrorSleepGivenUpTonight = true;
                    SleepOverride.requestSkipThisNight();
                    Debug.logToFile("altoclef_diag.log",
                            "[SUB-BOOT] mirror sleep give up tonight (no bed chance in 30s)");
                    return null;
                }
            }
        }
        setDebugState("[次bot引导] 随主bot睡觉 " + _leader);
        return _sleepTask;
    }

    /** leader 是否"持续在睡"≥{@link #LEADER_SLEEP_DEBOUNCE_MS}。 */
    private boolean leaderSleepingDebounced(AltoClef mod) {
        if (mod.getEntityTracker() == null) return false;
        boolean sleeping = mod.getEntityTracker().getPlayerEntity(_leader)
                .map(p -> p.isSleeping()).orElse(false);
        long now = System.currentTimeMillis();
        if (!sleeping) {
            _leaderSleepSeenMs = 0;
            return false;
        }
        if (_leaderSleepSeenMs == 0) {
            _leaderSleepSeenMs = now;
            return false;
        }
        return now - _leaderSleepSeenMs >= LEADER_SLEEP_DEBOUNCE_MS;
    }

    // ------------------------------------------------------------------ //
    // 阶段三: 随行
    // ------------------------------------------------------------------ //

    private Task tickFollow(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now < _retryAfterMs) {
            setDebugState("[次bot引导] 等待重试随行 " + _leader);
            return null;
        }
        if (_follow == null) {
            _follow = new FollowAndWorkTask(_leader, new FollowAndWorkTask.GreedySideWorkPicker());
        }
        if (_follow.isFinished(mod)) {
            _failures++;
            Debug.logMessage("[SUB-BOOT] 随行结束（第 " + _failures + " 次）：目标 "
                    + _leader + " 不可达");
            _follow = null;
            if (_failures >= MAX_CONSECUTIVE_FAILURES) {
                Debug.logMessage("[SUB-BOOT] 连续 " + _failures + " 次失败，本次引导结束"
                        + "（上层会在冷却后重新拉起）");
                _finished = true;
                return null;
            }
            _retryAfterMs = now + RETRY_COOLDOWN_MS;
            return null;
        }
        setDebugState("[次bot引导] 随行 " + _leader);
        return _follow;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 被顶掉时只丢句柄, 不动 static 闩 —— 前置"只做一次"是跨实例语义。
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SubBotBootstrapTask t && t._leader.equals(_leader);
    }

    @Override
    protected String toDebugString() {
        return "Sub-bot bootstrap (leader: " + _leader + ")";
    }
}
