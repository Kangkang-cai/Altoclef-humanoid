package adris.altoclef.anomaly;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.trackers.DecisionMemory;
import net.minecraft.util.math.Vec3d;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;

/**
 * 卡死任务软重启守卫 (2026-10-04)。
 *
 * 解决什么: bot 卡在原地不动, 任务永远跑不完 —— 现有看门狗只会告警, 没人消费。
 * 本守卫是第一个"动手"的消费者: 时长异常 + 位移停滞双条件成立时, 对用户主任务
 * 做一次软重启。
 *
 * 条件一 + 条件二 缺一不可 (这正是"合法长任务不误杀"的关键):
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
 * <p>(2026-10-09) 第三条<b>独立</b>触发线: 任务横跳死锁。信号源 = TaskSwitchLogger
 * 的 [TaskPingPong] WARN (同键 A&lt;-&gt;B 5s 窗口 &gt;= 6 次; 0911 口径校准后回放
 * 0 误报), 同键告警经 10s 节流仍持续 &gt;= 60s (约 4 轮) 才动手 —— 瞬时抖动不杀。
 * 横跳与位移停滞/时长无关 (来回切换时每个子任务都很短, bot 也可能在动), 故为
 * 独立条件。周期 &gt;= 3 的任务环 (TaskCycleDetector) 是自认低精度的启发式
 * (只落盘), 刻意不消费。配额与"位移停滞"触发共用同一预算: 同一任务类 10min
 * 窗口 2 次。
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
 * 取证 (2026-10-04 追加, 用户要求"事后能核对是不是真卡了"):
 * <ul>
 *   <li>守卫每秒采样位置留最近 {@link #POS_HISTORY_MAX} 秒 ({@link #_posHistory});
 *       触发重启/耗尽时整段落盘 —— 事后可直接看出"触发前到底动没动"。</li>
 *   <li>所有事件写专用 {@link #FORENSIC_LOG} (restart_guard.log), 带 baritone
 *       状态 / 血量 / 食物 / 维度 / 任务栈完整现场; altoclef_diag.log 保留单行索引。</li>
 *   <li>重启 60s 后自动"结算": 记录重启后位移、当前任务、是否再度超时
 *       ([POST-OK] / [POST-STILL-BAD]) —— 直接回答"真卡了还是误杀、救活没有"。</li>
 *   <li>事件同步 DecisionMemory (跨会话持久, 按存档 key): add=重启/结算流水,
 *       put=耗尽标量。</li>
 * </ul>
 * 注意: 经 {@link AnomalyWatchdog#report} 走的告警在 anomaly.log 中不带 ctx 快照
 * (report 传 ctx=null), 所以完整现场以本守卫自己的取证日志为准。
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

    /**
     * (2026-10-09 条件三) 横跳告警间隙: 距上一条 [TaskPingPong] 超过此时长视为
     * 横跳中断, 重新起算确认窗口。告警按键 10s 节流, 持续横跳约每 10~15s 一条,
     * 20s 间隙刚好接上不间断的告警流。
     */
    private static final long OSC_GAP_MS = 20_000;
    /** 横跳须持续多久才判"横跳死锁"并重启 (约 4 轮告警, 过滤瞬时抖动)。 */
    private static final long OSC_CONFIRM_MS = 60_000;
    /** 取证: 保留最近多少条横跳签名。 */
    private static final int OSC_KEYS_MAX = 12;

    /** 重启后等待多久做"结算" (位移/是否复发)。 */
    private static final long POST_CHECK_MS = 60_000L;

    /** 取证位置轨迹容量: 1 样本/秒, 覆盖触发前 90s。 */
    private static final int POS_HISTORY_MAX = 90;

    private static final String DIAG_LOG = "altoclef_diag.log";
    private static final String FORENSIC_LOG = "restart_guard.log";

    private static final SimpleDateFormat _timeFmt = new SimpleDateFormat("HH:mm:ss");

    /** 停滞锚点 (跨 tick, 换世界/大位移自动重置)。 */
    private static Vec3d _anchor = null;
    private static long _stillSinceMs = 0;
    private static long _lastCheckMs = 0;

    /** 取证轨迹: double[]{timeMs, x, y, z}, 每秒一采, 环形丢弃最老。 */
    private static final ArrayDeque<double[]> _posHistory = new ArrayDeque<>();

    /** 待结算的最近一次重启 (同时只跟踪一次; 结算后清空)。 */
    private static String _pendingPostClass = null;
    private static long _pendingPostAtMs = 0;
    private static Vec3d _pendingPostPos = null;
    private static String _pendingPostDim = null;
    private static int _pendingPostCount = 0;

    /** 按任务类名 (稳定标识) 记账; 任务实例每 tick 重建, 不能拿实例做键。 */
    private static final class RestartRecord {
        int count = 0;
        long lastRestartMs = 0;
        long lastExhaustedAlertMs = 0;
    }

    private static final HashMap<String, RestartRecord> _records = new HashMap<>();

    /** 横跳信号状态 (条件三, 由 TaskSwitchLogger 喂入; 世界切换/掉线时清零)。 */
    private static long _oscSinceMs = 0;
    private static long _lastOscMs = 0;
    private static int _oscWarnCount = 0;
    private static String _lastOscKey = null;
    private static final ArrayDeque<String> _recentOscKeys = new ArrayDeque<>();

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
            resetOscillation();
            _posHistory.clear();
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
        // (2026-10-04) bridge @idle 走 IdleCommand → runUserTask, **不置** _runningIdleTask
        // (那是 settings idleCommand 路径专用标志) ⇒ 上面那道闸拦不住它。待命 (定时或无限期)
        // 的原地静止是设计意图, 且定时 idle 时长可远超 p90 ⇒ 必须按任务类型显式豁免。
        if (mainTask instanceof adris.altoclef.tasks.movement.IdleTask) {
            resetStillness();
            return;
        }

        String cls = mainTask.getClass().getSimpleName();
        Vec3d pos = ctx.playerPos();

        // ---- 取证: 位置轨迹采样 (1 样本/秒, 本方法节流保证) ----
        if (_posHistory.size() >= POS_HISTORY_MAX) _posHistory.pollFirst();
        _posHistory.addLast(new double[]{now, pos.x, pos.y, pos.z});

        // ---- 取证: 上一轮重启的 60s 结算 (在任何条件判定之前, 只依赖"到时间了") ----
        if (_pendingPostClass != null && now - _pendingPostAtMs >= POST_CHECK_MS) {
            settlePost(ctx, mainTask, now);
        }

        // ---- 条件三 (2026-10-09): 横跳死锁 -> 软重启 (独立于条件一/二, 详见类注释) ----
        // 信号由 TaskSwitchLogger 在 [TaskPingPong] 告警时喂入 (notifyOscillation,
        // 事件驱动, 不依赖本 tick 的采样); 这里只判"持续足够久 + 配额未耗尽"。
        // 屏幕开着时上面已 return, 动作顺延到界面关闭的 tick —— 横跳本身常伴随
        // 界面开关, 告警流不受影响。
        if (isOscillationThrashing(now)) {
            RestartRecord rec = _records.computeIfAbsent(cls, k -> new RestartRecord());
            if (now - rec.lastRestartMs > WINDOW_MS) {
                rec.count = 0;
            }
            if (now - rec.lastRestartMs < MIN_RESTART_INTERVAL_MS) return;

            if (rec.count >= MAX_RESTARTS_PER_WINDOW) {
                // 出口: 配额耗尽 (与停滞触发共用同一预算) -> 停手, 只节流告警。
                if (now - rec.lastExhaustedAlertMs > EXHAUSTED_ALERT_MS) {
                    rec.lastExhaustedAlertMs = now;
                    String summary = cls + " 横跳持续 (告警 " + _oscWarnCount + " 条), 软重启 "
                            + rec.count + " 次后仍在横跳 -> 守卫停手, 需人工/上层介入";
                    Debug.logWarning("[RestartGuard] " + summary);
                    Debug.logToFile(DIAG_LOG, "[RestartGuard][OSC-EXHAUSTED] " + summary
                            + " | oscKeys=" + oscKeysDump());
                    Debug.logToFile(FORENSIC_LOG, oscSnapshot(ctx, cls, now)
                            + "\n    判定: 横跳软重启 " + rec.count + " 次后依旧 -> 配额耗尽, 守卫停手");
                    try {
                        DecisionMemory.put("restartGuardOscExhausted", cls);
                    } catch (Throwable ignored) {
                    }
                    AnomalyWatchdog.report(new Anomaly("oscillation-restart-exhausted", cls,
                            AnomalySeverity.CRITICAL, summary, "oscKeys=" + oscKeysDump()));
                }
                return;
            }

            // 动作: 软重启主任务 (与停滞触发同一条 interrupt 路径)。
            rec.count++;
            rec.lastRestartMs = now;
            String summary = cls + " 任务横跳持续 " + ((now - _oscSinceMs) / 1000) + "s (告警 "
                    + _oscWarnCount + " 条) -> 软重启 (" + rec.count + "/"
                    + MAX_RESTARTS_PER_WINDOW + ")";
            Debug.logMessage("[RestartGuard] " + summary);
            Debug.logToFile(DIAG_LOG, "[RestartGuard][OSC-RESTART] " + summary
                    + " | oscKeys=" + oscKeysDump());
            Debug.logToFile(FORENSIC_LOG, oscSnapshot(ctx, cls, now)
                    + "\n    动作: Task.interrupt 软重启 (横跳触发, " + rec.count + "/"
                    + MAX_RESTARTS_PER_WINDOW + "), " + (POST_CHECK_MS / 1000) + "s 后自动结算");
            try {
                DecisionMemory.add("restartGuardOsc", _timeFmt.format(new Date(now))
                        + " " + cls + " thrash=" + ((now - _oscSinceMs) / 1000) + "s"
                        + " warns=" + _oscWarnCount);
            } catch (Throwable ignored) {
            }
            try {
                // interrupt: 递归 onStop 清理子树、置 _first、保持 active;
                // 下一拍主任务重跑 onStart, 时长计时经 TaskSwitchLogger.taskStarted 归零。
                mainTask.interrupt(mod, null);
            } catch (Throwable t) {
                // 重启动作自身失败不能反噬调度; 本次计数已记, 下个窗口自然重试。
                Debug.logToFile(DIAG_LOG, "[RestartGuard] interrupt failed: " + t);
                Debug.logToFile(FORENSIC_LOG, "    ⚠ interrupt 抛异常: " + t);
                return;
            }
            _pendingPostClass = cls;
            _pendingPostAtMs = now;
            _pendingPostPos = pos;
            _pendingPostDim = ctx.dimension();
            _pendingPostCount = rec.count;
            AnomalyWatchdog.report(new Anomaly("oscillation-restart", cls,
                    AnomalySeverity.WARN, summary, "oscKeys=" + oscKeysDump()));
            return;
        }

        // ---- 条件一: 时长异常 (判定源单一: TaskDurationTracker) ----
        long limit = TaskDurationTracker.timeoutThresholdMs(cls);
        if (limit <= 0) return; // 样本不足 (冷启动) -> 不判定
        long elapsed = TaskDurationTracker.elapsedMs(mainTask);
        if (elapsed < 0 || elapsed <= limit) {
            // 时长正常 -> 一切不动。合法长任务 (一直在移动) 从这里豁免。
            return;
        }

        // ---- 条件二: 位移停滞 (锚点口径与 StallDetector 一致) ----
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
                        + " pos=" + formatPos(pos)
                        + " elapsed=" + TaskDurationTracker.formatMs(elapsed)
                        + " limit=" + TaskDurationTracker.formatMs(limit);
                Debug.logWarning("[RestartGuard] " + summary);
                Debug.logToFile(DIAG_LOG, "[RestartGuard][EXHAUSTED] " + summary + " | " + detail);
                String forensic = snapshot(ctx, cls, elapsed, limit, stillMs)
                        + "\n    判定: 重启 " + rec.count + " 次后停滞依旧 -> 配额耗尽, 守卫停手\n"
                        + posHistoryDump();
                Debug.logToFile(FORENSIC_LOG, forensic);
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
                + " pos=" + formatPos(pos)
                + " dim=" + ctx.dimension()
                + " hp=" + ctx.health() + " food=" + ctx.food()
                + " baritone(pathing=" + ctx.baritonePathing()
                + " pathPresent=" + ctx.baritonePathPresent() + ")";
        Debug.logMessage("[RestartGuard] " + summary);
        Debug.logToFile(DIAG_LOG, "[RestartGuard][RESTART] " + summary + " | " + detail);
        String forensic = snapshot(ctx, cls, elapsed, limit, stillMs)
                + "\n    动作: Task.interrupt 软重启 (" + rec.count + "/" + MAX_RESTARTS_PER_WINDOW
                + "), " + (POST_CHECK_MS / 1000) + "s 后自动结算\n"
                + posHistoryDump();
        Debug.logToFile(FORENSIC_LOG, forensic);
        try {
            DecisionMemory.add("restartGuardRestart", _timeFmt.format(new Date(now))
                    + " " + cls + " elapsed=" + TaskDurationTracker.formatMs(elapsed)
                    + " still=" + (stillMs / 1000) + "s"
                    + " pos=" + formatPos(pos));
        } catch (Throwable ignored) {
        }
        try {
            // interrupt: 递归 onStop 清理子树、置 _first、保持 active;
            // 下一拍主任务重跑 onStart, 时长计时经 TaskSwitchLogger.taskStarted 归零。
            mainTask.interrupt(mod, null);
        } catch (Throwable t) {
            // 重启动作自身失败不能反噬调度; 本次计数已记, 下个窗口自然重试。
            Debug.logToFile(DIAG_LOG, "[RestartGuard] interrupt failed: " + t);
            Debug.logToFile(FORENSIC_LOG, "    ⚠ interrupt 抛异常: " + t);
            return;
        }
        // 挂起 60s 后的结算任务 (成功 interrupt 之后才挂, 失败不结算)。
        _pendingPostClass = cls;
        _pendingPostAtMs = now;
        _pendingPostPos = pos;
        _pendingPostDim = ctx.dimension();
        _pendingPostCount = rec.count;
        AnomalyWatchdog.report(new Anomaly("duration-restart", cls,
                AnomalySeverity.WARN, summary, detail));
    }

    // ------------------------------------------------------------------
    // 横跳信号 (条件三)
    // ------------------------------------------------------------------

    /**
     * TaskSwitchLogger 在每次 [TaskPingPong] WARN 时喂入 (同键 10s 节流后的每次
     * 告警算一票)。只认 UserTaskChain 的横跳 —— 反射链的抖动不在重启范围。
     * 距上次告警超过 {@link #OSC_GAP_MS} 视为一轮新横跳, 重新起算确认窗口。
     */
    public static void notifyOscillation(TaskChain chain, String key) {
        if (key == null || !(chain instanceof UserTaskChain)) return;
        long now = System.currentTimeMillis();
        if (_oscSinceMs == 0 || now - _lastOscMs > OSC_GAP_MS) {
            _oscSinceMs = now;
            _oscWarnCount = 0;
        }
        _lastOscMs = now;
        _oscWarnCount++;
        if (!key.equals(_lastOscKey)) {
            if (_recentOscKeys.size() >= OSC_KEYS_MAX) _recentOscKeys.pollFirst();
            _recentOscKeys.addLast(_timeFmt.format(new Date(now)) + " " + key);
            _lastOscKey = key;
        }
    }

    /** 横跳确认: 告警流未断 (在间隙内) 且本轮已持续 >= {@link #OSC_CONFIRM_MS}。 */
    private static boolean isOscillationThrashing(long now) {
        return _oscSinceMs > 0
                && now - _lastOscMs < OSC_GAP_MS
                && now - _oscSinceMs >= OSC_CONFIRM_MS;
    }

    /** 横跳状态清零 (世界切换 / 掉线 / 暂停时)。 */
    private static void resetOscillation() {
        _oscSinceMs = 0;
        _lastOscMs = 0;
        _oscWarnCount = 0;
        _lastOscKey = null;
        _recentOscKeys.clear();
    }

    /** 最近横跳签名 (取证, 最多 {@link #OSC_KEYS_MAX} 条)。 */
    private static String oscKeysDump() {
        if (_recentOscKeys.isEmpty()) return "(空)";
        return String.join(" || ", _recentOscKeys);
    }

    /** 横跳事件的现场快照 (多行, 进 restart_guard.log)。不走 snapshot(): 那套
     *  参数口径是时长/停滞, 横跳触发的 elapsed/stillMs 无意义。 */
    private static String oscSnapshot(AnomalyContext ctx, String cls, long now) {
        return "[" + _timeFmt.format(new Date(now)) + "][" + ctx.dimension() + "] " + cls
                + " 横跳持续=" + ((now - _oscSinceMs) / 1000) + "s 告警数=" + _oscWarnCount
                + " pos=" + formatPos(ctx.playerPos())
                + " hp=" + ctx.health() + " food=" + ctx.food()
                + "\n    stack=" + ctx.stackPath()
                + "\n    oscKeys=" + oscKeysDump();
    }

    // ------------------------------------------------------------------
    // 取证
    // ------------------------------------------------------------------

    /**
     * 重启后 {@link #POST_CHECK_MS} 结算: 位移了多少 / 还是不是同一任务 / 是否再度超时。
     * 结论直接回答"真卡了还是误杀" —— 真卡死重启后应见位移恢复; 误杀则重启前后
     * 都在合法移动 (这种情况本方法很少到达, 因为停滞条件很难满足)。
     */
    private static void settlePost(AnomalyContext ctx, Task currentTask, long now) {
        String cls = _pendingPostClass;
        _pendingPostClass = null; // 先清, 结算自身异常不重入
        try {
            double moved = 0;
            if (_pendingPostPos != null && ctx.playerPos() != null
                    && ctx.dimension().equals(_pendingPostDim)) {
                moved = Math.sqrt(ctx.playerPos().squaredDistanceTo(_pendingPostPos));
            }
            String curCls = currentTask == null ? "(null)" : currentTask.getClass().getSimpleName();
            long curElapsed = currentTask == null ? -1 : TaskDurationTracker.elapsedMs(currentTask);
            long curLimit = TaskDurationTracker.timeoutThresholdMs(curCls);
            boolean overAgain = curLimit > 0 && curElapsed > curLimit;
            String verdict = overAgain ? "POST-STILL-BAD (重启后 " + (POST_CHECK_MS / 1000)
                    + "s 内又超时, 大概率真卡死/该类 p90 基线失真)"
                    : "POST-OK (重启后未再超时)";
            String line = "[" + verdict + "] " + cls
                    + " | 重启后位移=" + String.format("%.1f", moved) + "格"
                    + " | 当前任务=" + curCls
                    + " elapsed=" + (curElapsed < 0 ? "?" : TaskDurationTracker.formatMs(curElapsed))
                    + " limit=" + (curLimit <= 0 ? "样本不足" : TaskDurationTracker.formatMs(curLimit))
                    + " | 重启序号=" + _pendingPostCount;
            Debug.logToFile(FORENSIC_LOG, "[" + _timeFmt.format(new Date(now)) + "] " + line);
            Debug.logToFile(DIAG_LOG, "[RestartGuard][" + verdict.split(" ")[0] + "] " + line);
            try {
                DecisionMemory.add("restartGuardPost", _timeFmt.format(new Date(now))
                        + " " + cls + " moved=" + String.format("%.1f", moved) + "格"
                        + (overAgain ? " 又超时" : " 未复发"));
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 事件现场快照 (多行, 进 restart_guard.log)。 */
    private static String snapshot(AnomalyContext ctx, String cls, long elapsed, long limit, long stillMs) {
        return "[" + _timeFmt.format(new Date(System.currentTimeMillis())) + "][" + ctx.dimension() + "] "
                + cls
                + " elapsed=" + TaskDurationTracker.formatMs(elapsed)
                + " limit=" + TaskDurationTracker.formatMs(limit)
                + " 停滞=" + (stillMs / 1000) + "s"
                + " pos=" + formatPos(ctx.playerPos())
                + " hp=" + ctx.health() + " food=" + ctx.food()
                + " screen=" + ctx.hasScreen() + " sleeping=" + ctx.sleeping()
                + " baritone(pathing=" + ctx.baritonePathing()
                + " pathPresent=" + ctx.baritonePathPresent()
                + " available=" + ctx.baritoneAvailable() + ")"
                + "\n    stack=" + ctx.stackPath();
    }

    /** 触发前的位置轨迹 (最近 90s, 1 样本/秒), 带相邻两点位移。 */
    private static String posHistoryDump() {
        if (_posHistory.isEmpty()) return "    pos轨迹: (空)";
        StringBuilder sb = new StringBuilder("    pos轨迹 (最近 ")
                .append(_posHistory.size()).append("s, 时间(HH:mm:ss) 坐标 与上一点距离):");
        double[] prev = null;
        int i = 0;
        for (double[] s : _posHistory) {
            double d = 0;
            if (prev != null) {
                double dx = s[1] - prev[1], dy = s[2] - prev[2], dz = s[3] - prev[3];
                d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            if (i % 5 == 0) sb.append("\n      ");
            sb.append(_timeFmt.format(new Date((long) s[0])))
                    .append('(').append(Math.round(s[1])).append(',').append(Math.round(s[2]))
                    .append(',').append(Math.round(s[3])).append(')')
                    .append(prev == null ? "" : String.format("Δ%.1f", d)).append("  ");
            prev = s;
            i++;
        }
        return sb.toString();
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
