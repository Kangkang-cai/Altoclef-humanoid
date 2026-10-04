package adris.altoclef.anomaly;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.trackers.DecisionMemory;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;

/**
 * 卡死任务软重启守卫 (2026-10-04)。
 *
 * 解决什么: bot 卡在原地不动, 任务永远跑不完 —— 现有看门狗只会告警, 没人消费。
 * 本守卫是第一个"动手"的消费者: 时长异常 + 位移停滞双条件成立时, 对用户主任务
 * 做一次软重启。
 *
 * 双条件缺一不可 (这正是"合法长任务不误杀"的关键):
 * <ul>
 *   <li><b>时长异常</b>: 主任务连续运行超过其任务类的自适应上限
 *       ({@code max(p90×3, 30s)}, 判定源单一: {@link TaskDurationTracker})。
 *       样本不足时该值为 -1, 直接跳过 —— 冷启动期不判定。</li>
 *   <li><b>位移停滞</b>: 偏离 0.5 格锚点持续 {@link #STALL_CONFIRM_MS} 毫秒
 *       (与 StallDetector 同一套锚点口径)。开着 GUI / 睡觉 / 无链控制时的静止
 *       属合法行为, 停滞计时重置 —— 冶炼等待等"站桩但不卡死"场景靠这条豁免。</li>
 * </ul>
 * 在移动的长任务 (远征探索 / fishbone 挖下) 永远不满足第二条件, 不会被杀。
 *
 * 重启杠杆 = {@link Task#interrupt}: 与链抢占恢复完全同一条框架路径 ——
 * 递归 onStop 清理子树、置 _first、保持 active, 下一拍任务重跑 onStart,
 * TaskSwitchLogger.taskStarted 随即把时长计时归零。不碰链所有权、不丢用户任务。
 *
 * 出口 (无出口死循环是本项目铁律红线):
 * <ul>
 *   <li>重启后计时归零, 条件自然失效 —— 无需闩锁;</li>
 *   <li>同一任务类在 {@link #WINDOW_MS} 窗口内最多重启
 *       {@link #MAX_RESTARTS_PER_WINDOW} 次, 且两次重启间隔不小于
 *       {@link #MIN_RESTART_INTERVAL_MS};</li>
 *   <li>超限后<b>停止一切动作</b>, 只按 {@link #EXHAUSTED_ALERT_MS} 节流发
 *       CRITICAL 告警 + DecisionMemory 落盘 —— 交给人和既有检测器, 绝不无限重启。</li>
 * </ul>
 *
 * 范围刻意收窄: 只处理 UserTaskChain 主任务, 且跳过 idle 任务 (原地等待指令是
 * 合法静止)。反射链 (岩浆逃生 / MLG / 怪物防御) 的任务绝不被本守卫打断 ——
 * 在逃生中途重启安全链任务可能直接致死; 它们的停滞由 StallDetector 告警覆盖。
 *
 * 与看门狗的边界: AnomalyWatchdog 检测层严格只读 (AnomalyContext 类型层面
 * 拿不到 mod 引用), 本守卫是独立的动作组件, 由 TaskRunner.tick 在看门狗之后
 * 调用, 自身异常被调用方吞掉, 绝不影响任务调度。
 */
public final class StuckTaskRestartGuard {

    /** 位移停滞须持续多久才判"卡死"。与 StallDetector.WARN_MS 同值: 告警层认为停滞时才动手。 */
    private static final long STALL_CONFIRM_MS = 45_000;
    /** 偏离锚点超过 0.5 格即"动过" (与 StallDetector 同一口径)。 */
    private static final double MOVE_THRESHOLD_SQ = 0.5 * 0.5;
    /** 同一任务类在一个窗口内的软重启次数上限。 */
    private static final int MAX_RESTARTS_PER_WINDOW = 2;
    /** 重启计数窗口: 距上次重启超过此时长则清零 (任务类后来跑顺了不背旧账)。 */
    private static final long WINDOW_MS = 10 * 60_000L;
    /** 两次重启动作的最小间隔 (防计时异常时的连发兜底)。 */
    private static final long MIN_RESTART_INTERVAL_MS = 60_000L;
    /** "重启配额耗尽"告警节流: 同一任务类每窗口最多一条。 */
    private static final long EXHAUSTED_ALERT_MS = 10 * 60_000L;
    /** 慢变量, 1 秒检查一次 (与 DurationDetector 同频)。 */
    private static final long CHECK_INTERVAL_MS = 1_000;

    private static final String DIAG_LOG = "altoclef_diag.log";

    /** 停滞锚点 (跨 tick, 换世界/大位移自动重置)。 */
    private static Vec3d _anchor = null;
    private static long _stillSinceMs = 0;
    private static long _lastCheckMs = 0;

    /** 按任务类名 (稳定标识) 记账; 任务实例每 tick 重建, 不能拿实例做键。 */
    private static final class RestartRecord {
        int count = 0;
        long lastRestartMs = 0;
        long lastExhaustedAlertMs = 0;
    }

    private static final HashMap<String, RestartRecord> _records = new HashMap<>();

    private StuckTaskRestartGuard() {
    }

    /**
     * 每 tick 调用 (TaskRunner.tick 末尾, 看门狗之后)。调用方须吞掉本方法一切异常。
     *
     * @param winnerChain 本 tick 仲裁选出的控制链, 可为 null (bot 空闲)
     */
    public static void tick(AltoClef mod, TaskChain winnerChain) {
        if (!mod.getModSettings().isDurationRestartGuardEnabled()) return;

        long now = System.currentTimeMillis();
        if (now - _lastCheckMs < CHECK_INTERVAL_MS) return;
        _lastCheckMs = now;

        // 只读快照复用: 与检测器同一采样源 (inGame/paused/screen/sleeping/坐标),
        // 不另行摸 MinecraftClient, 避免第二套现场口径。
        AnomalyContext ctx;
        try {
            ctx = AnomalyContext.sample(mod, winnerChain, 0);
        } catch (Throwable t) {
            return;
        }

        // 合法静止白名单 (与 StallDetector 一致): 命中即重置停滞计时, 不判定。
        if (!ctx.inGame() || ctx.paused() || ctx.playerPos() == null) {
            resetStillness();
            return;
        }
        if (ctx.chainName() == null || ctx.hasScreen() || ctx.sleeping()) {
            resetStillness();
            return;
        }

        // 范围闸门 1: 只管用户主任务链。
        if (!(winnerChain instanceof UserTaskChain userChain)) return;
        // 范围闸门 2: idle 任务的原地等待是合法静止, 不判不重启。
        if (userChain.isRunningIdleTask()) {
            resetStillness();
            return;
        }
        Task mainTask = userChain.getCurrentTask();
        if (mainTask == null) return;

        String cls = mainTask.getClass().getSimpleName();

        // ---- 条件一: 时长异常 (判定源单一: TaskDurationTracker) ----
        long limit = TaskDurationTracker.timeoutThresholdMs(cls);
        if (limit <= 0) return; // 样本不足 (冷启动) -> 不判定
        long elapsed = TaskDurationTracker.elapsedMs(mainTask);
        if (elapsed < 0 || elapsed <= limit) {
            // 时长正常 -> 一切不动。合法长任务 (一直在移动) 从这里豁免。
            return;
        }

        // ---- 条件二: 位移停滞 (锚点口径与 StallDetector 一致) ----
        Vec3d pos = ctx.playerPos();
        if (_anchor == null || pos.squaredDistanceTo(_anchor) > MOVE_THRESHOLD_SQ) {
            _anchor = pos;
            _stillSinceMs = now;
            return; // 刚动过/锚点重置, 停滞不成立
        }
        long stillMs = now - _stillSinceMs;
        if (stillMs < STALL_CONFIRM_MS) return; // 超时但还在动 -> 只告警不干预

        // ---- 双条件成立: 记账 + 出口检查 ----
        RestartRecord rec = _records.computeIfAbsent(cls, k -> new RestartRecord());
        if (now - rec.lastRestartMs > WINDOW_MS) {
            rec.count = 0;
        }
        if (now - rec.lastRestartMs < MIN_RESTART_INTERVAL_MS) return;

        if (rec.count >= MAX_RESTARTS_PER_WINDOW) {
            // 出口: 配额耗尽 -> 停止动作, 只节流告警。绝不无限重启。
            if (now - rec.lastExhaustedAlertMs > EXHAUSTED_ALERT_MS) {
                rec.lastExhaustedAlertMs = now;
                String summary = cls + " 软重启 " + rec.count + " 次后仍在停滞 (无位移 "
                        + (stillMs / 1000) + "s), 守卫已停手, 需人工/上层介入";
                String detail = "stack=" + ctx.stackPath()
                        + " pos=" + formatPos(ctx.playerPos())
                        + " elapsed=" + TaskDurationTracker.formatMs(elapsed)
                        + " limit=" + TaskDurationTracker.formatMs(limit);
                Debug.logWarning("[RestartGuard] " + summary);
                Debug.logToFile(DIAG_LOG, "[RestartGuard][EXHAUSTED] " + summary + " | " + detail);
                try {
                    DecisionMemory.put("restartGuardExhausted", cls);
                } catch (Throwable ignored) {
                }
                AnomalyWatchdog.report(new Anomaly("duration-restart-exhausted", cls,
                        AnomalySeverity.CRITICAL, summary, detail));
            }
            return;
        }

        // ---- 动作: 软重启 (与链抢占恢复同一条框架路径) ----
        rec.count++;
        rec.lastRestartMs = now;
        String summary = cls + " 连续运行 " + TaskDurationTracker.formatMs(elapsed)
                + " 超上限 " + TaskDurationTracker.formatMs(limit)
                + " 且无位移 " + (stillMs / 1000) + "s → 软重启 ("
                + rec.count + "/" + MAX_RESTARTS_PER_WINDOW + ")";
        String detail = "stack=" + ctx.stackPath()
                + " pos=" + formatPos(ctx.playerPos());
        Debug.logMessage("[RestartGuard] " + summary);
        Debug.logToFile(DIAG_LOG, "[RestartGuard][RESTART] " + summary + " | " + detail);
        try {
            // interrupt: 递归 onStop 清理子树、置 _first、保持 active;
            // 下一拍主任务重跑 onStart, 时长计时经 TaskSwitchLogger.taskStarted 归零。
            mainTask.interrupt(mod, null);
        } catch (Throwable t) {
            // 重启动作自身失败不能反噬调度; 本次计数已记, 下个窗口自然重试。
            Debug.logToFile(DIAG_LOG, "[RestartGuard] interrupt failed: " + t);
            return;
        }
        AnomalyWatchdog.report(new Anomaly("duration-restart", cls,
                AnomalySeverity.WARN, summary, detail));
    }

    private static void resetStillness() {
        _anchor = null;
        _stillSinceMs = 0;
    }

    private static String formatPos(Vec3d pos) {
        if (pos == null) return "?";
        return "(" + Math.round(pos.x) + "," + Math.round(pos.y) + "," + Math.round(pos.z) + ")";
    }
}
