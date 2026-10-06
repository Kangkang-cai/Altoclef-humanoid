package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.speedrun.BeatMinecraftConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;

import java.util.function.Supplier;

/**
 * (2026-10-06 次 bot 自动引导, 用户拍板"一次性前置 → 自动转跟随") <b>次 bot 的常驻宿主</b>:
 * 空闲就自动把木头/食物补到"次 bot 档", 达标后自动进入随行 —— <b>完全不需要对端发命令</b>。
 *
 * <h3>两阶段</h3>
 * <ol>
 *   <li><b>前置</b>: 木头 &lt; {@code woodLogsTarget(8)} → 去囤木; 否则食物 &lt; {@code foodTarget(40)}
 *       → 去收食物; 都达标 → 置 static 闩, 进随行。<b>每个 JVM 会话只做一次</b>
 *       （与 BeatMinecraft2Task 的 {@code _fireResPhaseDone} 同款 static 闩）——
 *       之后即使背包丢了也不重做, 缺口按设计由主 bot 用 {@code @give} 补。</li>
 *   <li><b>随行</b>: 返回 {@link FollowAndWorkTask}(leader) —— 贴 3 格 + 顺手搜刮/挖矿。</li>
 * </ol>
 *
 * <h3>为什么它是"常驻不结束"的</h3>
 * 本类只换内部子任务、自身几乎不结束。这点是<b>硬要求</b>: 一旦它结束, 链会走
 * {@code UserTaskChain.onTaskFinish} 的"空闲→自动重新拉起"路径（自动引导入口就是靠
 * 这条路径补位的, 见 UserTaskChain.maybeStartSubBotBootstrap), 若它瞬间就结束会形成
 * 每 tick 一次的拉起循环。唯一的结束出口是"跟随连续失败 {@link #MAX_CONSECUTIVE_FAILURES} 次"
 * —— 那是"leader 根本不存在"这类持久错误, 结束后由上层冷却数分钟再拉起, 不会高频循环。
 *
 * <h3>出口（铁律: 一切等待都必须有出口）</h3>
 * <ul>
 *   <li>前置阶段总超时 {@link #PREP_TIMEOUT_MS}（15 分钟）—— 这局没有树/食物时不至于永久卡住;</li>
 *   <li>跟随子任务终止（leader 不在服务器 / 跨维度）→ 等 {@link #RETRY_COOLDOWN_MS} 后重试;</li>
 *   <li>连续失败到上限 → 本任务结束, 把控制权交回链。</li>
 * </ul>
 *
 * <h3>⚠️ 出口写在调用侧</h3>
 * {@code Task.tickInternal} 不消费子任务的 {@code isFinished}, 替换权在父链。所以前置子任务
 * 是否跑完、跟随子任务是否终止, 都由这里每拍主动轮询。
 */
public class SubBotBootstrapTask extends Task {

    /** 前置阶段总超时: 这局要是没有树/食物, 不能永久卡住。 */
    private static final long PREP_TIMEOUT_MS = 15 * 60_000;

    /** 跟随目标不可达时的重试间隔。 */
    private static final long RETRY_COOLDOWN_MS = 60_000;

    /** 跟随连续失败多少次后结束本次引导（把控制权交回链, 由上层冷却后重新拉起）。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** 前置是否已完成。static = 跨实例/跨重连存活, 同一 JVM 会话只做一次。 */
    private static boolean _prepDone = false;
    /** 前置阶段起始时刻（static: 实例可能被顶掉重建, 计时不能跟着重置）。 */
    private static long _prepStartedMs = 0;

    private final String _leader;

    /** 前置子任务缓存与它的种类标记 —— 每 tick new 会打断任务自己的进度。 */
    private Task _prep = null;
    private String _prepKind = null;

    /** 跟随子任务缓存。 */
    private FollowAndWorkTask _follow = null;

    private long _retryAfterMs = 0;
    private int _failures = 0;
    private boolean _finished = false;

    public SubBotBootstrapTask(String leader) {
        _leader = leader;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (_prepStartedMs == 0) {
            _prepStartedMs = System.currentTimeMillis();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_finished) return null;
        if (!_prepDone) {
            return tickPrep(mod);
        }
        return tickFollow(mod);
    }

    // ------------------------------------------------------------------ //
    // 阶段一: 前置（补木头 → 补食物）
    // ------------------------------------------------------------------ //

    private Task tickPrep(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (_prepStartedMs == 0) _prepStartedMs = now;

        BeatMinecraftConfig cfg = BeatMinecraft2Task.getConfig();
        int woodTarget = (cfg == null) ? 0 : cfg.woodLogsTarget();
        int foodTarget = (cfg == null) ? 0 : cfg.foodTarget();

        int wood = StorageHelper.countItemsInInventoryReal(mod, ItemHelper.LOG);
        int food = (int) StorageHelper.calculateInventoryFoodScore(mod);

        if (now - _prepStartedMs >= PREP_TIMEOUT_MS) {
            _prepDone = true;
            Debug.logMessage("[SUB-BOOT] 前置超时 " + (PREP_TIMEOUT_MS / 60_000) + " 分钟"
                    + " (木头 " + wood + "/" + woodTarget + ", 食物 " + food + "/" + foodTarget
                    + ")，直接进入随行");
            return null;
        }

        // 木头优先: 它是工具/燃料的地基。
        if (wood < woodTarget) {
            setDebugState("[次bot引导] 补木头 " + wood + "/" + woodTarget);
            return cachedPrep(mod, "wood", () -> TaskCatalogue.getItemTask("log", woodTarget));
        }
        if (food < foodTarget) {
            setDebugState("[次bot引导] 补食物 " + food + "/" + foodTarget);
            return cachedPrep(mod, "food", () -> new CollectFoodTask(foodTarget));
        }

        _prepDone = true;
        Debug.logMessage("[SUB-BOOT] 前置完成 (木头 " + wood + "/" + woodTarget
                + ", 食物 " + food + "/" + foodTarget + ")，转入随行 " + _leader);
        return null;
    }

    /**
     * 前置子任务缓存。每 tick `new` 会打断任务自己的进度（囤木会不停地从零重来），
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
    // 阶段二: 随行
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
