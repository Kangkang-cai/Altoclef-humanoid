package adris.altoclef.anomaly;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.TaskChain;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 异常看门狗 (2026-09-11)。
 *
 * 目标: 把"必须人盯着游戏才能发现的 bot 异常"变成自动检出 + 主动告警 ——
 * 反复横跳 (任务抖动)、时长异常 (原地停滞)、不可达 (算不出路径) 等。
 *
 * 数据流:
 * <pre>
 *   TaskRunner.tick 每 tick 调用 tick()  → 采样 AnomalyContext → 跑所有检测器
 *   TaskSwitchLogger 检出任务抖动时直接调用 report()  (事件式, 不适合轮询)
 *   两者汇入同一个判定 / 输出通道
 * </pre>
 *
 * 三条设计约束 (都是这个项目真实踩过的坑):
 * <ol>
 *   <li>检测层严格只读。tick() 不调用任何会改变任务状态的方法, 看门狗绝不介入
 *       调度 —— 否则它会成为新的异常源。</li>
 *   <li>冷却表是"触发条件层"的静态字段。项目教训: 实例层冷却会随任务实例重建
 *       而被绕过。</li>
 *   <li>告警必须带现场快照 (任务栈 / 坐标 / 血量 / baritone 状态)。没有快照的
 *       告警和人工翻日志的成本差不多。</li>
 * </ol>
 *
 * 健壮性: 所有检测器异常都被吞掉, 绝不向主循环外抛。一个坏掉的检测器最多让
 * 自己失效, 不能让游戏 tick 崩。
 */
public final class AnomalyWatchdog {

    private static final String LOG_FILE = "anomaly.log";

    /** 同一条异常的重复告警冷却 (触发条件层静态表)。 */
    private static final long DEFAULT_COOLDOWN_MS = 30_000;
    private static final long STALL_COOLDOWN_MS = 60_000;

    private static final HashMap<String, Long> _lastReportMs = new HashMap<>();
    private static final List<AnomalyDetector> _detectors = new ArrayList<>();

    private static boolean _registered = false;
    private static int _totalReports = 0;

    /** 上一次采样到的维度。变化即清空所有跨 tick 状态 (坐标不可比)。 */
    private static String _lastDimension = null;

    /** 事件式上报取现场快照用。由 tick() 维护。 */
    private static AltoClef _mod = null;

    private AnomalyWatchdog() {
    }

    // ------------------------------------------------------------------
    // 主循环入口
    // ------------------------------------------------------------------

    /**
     * 每 tick 调用一次 (TaskRunner.tick 末尾)。
     *
     * 必须在链执行之后调用: 这样快照里的任务栈才是本次 tick 的最终状态。
     *
     * @param winnerChain 本 tick 仲裁选出的控制链, 可为 null (bot 空闲)
     */
    public static void tick(AltoClef mod, TaskChain winnerChain, long tick) {
        if (mod == null) return;
        _mod = mod;
        if (!mod.getModSettings().isAnomalyWatchdogEnabled()) return;

        ensureRegistered();

        if (!AltoClef.inGame()) {
            if (_lastDimension != null) {
                _lastDimension = null;
                resetAll();
            }
            return;
        }

        AnomalyContext ctx;
        try {
            ctx = AnomalyContext.sample(mod, winnerChain, tick);
        } catch (Throwable t) {
            return;
        }

        // 跨维度: 坐标不可比, 清掉检测器的跨 tick 状态
        if (!ctx.dimension().equals(_lastDimension)) {
            _lastDimension = ctx.dimension();
            resetAll();
        }

        for (AnomalyDetector detector : _detectors) {
            try {
                Anomaly a = detector.check(ctx);
                if (a != null) emit(a, ctx);
            } catch (Throwable ignored) {
                // 坏掉的检测器只失效自己
            }
        }
    }

    /**
     * 事件式上报。抖动检测发生在任务切换的瞬间 (TaskSwitchLogger 内部),
     * 天然不适合放进每 tick 轮询, 由它直接调用这里。
     *
     * 去重 / 分级 / 输出全部与轮询式一致。
     */
    public static void report(Anomaly a) {
        if (a == null) return;
        if (_mod != null && !_mod.getModSettings().isAnomalyWatchdogEnabled()) return;
        emit(a, null);
    }

    /** 会话结束时调用: 输出汇总并清空状态。 */
    public static void onSessionEnd() {
        if (_totalReports <= 0) return;
        Debug.logToFile(LOG_FILE, "==== SESSION SUMMARY: " + _totalReports + " anomaly report(s) ====");
        Debug.logMessage("[Anomaly] 本次会话共检出异常 " + _totalReports + " 次 (详情见 " + LOG_FILE + ")");
        _totalReports = 0;
        _lastDimension = null;
        resetAll();
    }

    /** 一行状态摘要, 供调试或后续聊天命令使用。 */
    public static String status() {
        boolean enabled = _mod == null || _mod.getModSettings().isAnomalyWatchdogEnabled();
        return "enabled=" + enabled + " detectors=" + _detectors.size() + " reports=" + _totalReports;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static void ensureRegistered() {
        if (_registered) return;
        _registered = true;
        _detectors.add(new StallDetector());
        _detectors.add(new UnreachableDetector());
    }

    private static void resetAll() {
        for (AnomalyDetector detector : _detectors) {
            try {
                detector.reset();
            } catch (Throwable ignored) {
            }
        }
        _lastReportMs.clear();
    }

    /** 冷却过滤。同一 (type, key) 在冷却期内只报一次。 */
    private static void emit(Anomaly a, AnomalyContext ctx) {
        String dedupKey = a.type() + "|" + a.key();
        long now = System.currentTimeMillis();
        Long last = _lastReportMs.get(dedupKey);
        if (last != null && now - last < cooldownFor(a.type())) return;
        _lastReportMs.put(dedupKey, now);
        _totalReports++;
        writeAnomaly(a, ctx);
    }

    private static long cooldownFor(String type) {
        if ("stall".equals(type)) return STALL_COOLDOWN_MS;
        return DEFAULT_COOLDOWN_MS;
    }

    private static void writeAnomaly(Anomaly a, AnomalyContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(a.severity()).append("][").append(a.type()).append("] ")
                .append(a.summary());
        if (a.detail() != null) sb.append(" | ").append(a.detail());
        if (ctx != null) {
            sb.append("\n    tick=").append(ctx.tick())
                    .append(" pos=").append(formatPos(ctx.playerPos()))
                    .append(" dim=").append(ctx.dimension())
                    .append(" hp=").append(ctx.health() < 0 ? "?" : String.valueOf(ctx.health()))
                    .append(" food=").append(ctx.food() < 0 ? "?" : String.valueOf(ctx.food()));
            sb.append("\n    chain=").append(ctx.chainName() == null ? "none" : ctx.chainName())
                    .append(" screen=").append(ctx.hasScreen())
                    .append(" sleeping=").append(ctx.sleeping());
            sb.append("\n    stack=").append(ctx.stackPath());
            sb.append("\n    baritone: pathing=").append(ctx.baritonePathing())
                    .append(" pathPresent=").append(ctx.baritonePathPresent())
                    .append(" queried=").append(ctx.baritoneAvailable());
        }
        Debug.logToFile(LOG_FILE, sb.toString());

        // 聊天只给一行短提示, 完整现场在上面那段日志里
        if (a.severity() != AnomalySeverity.NOTICE) {
            Debug.logWarning("[Anomaly:" + a.type() + "] " + a.summary()
                    + " (详情见 " + LOG_FILE + ")");
        }
    }

    private static String formatPos(Vec3d pos) {
        if (pos == null) return "?";
        return "(" + Math.round(pos.x) + "," + Math.round(pos.y) + "," + Math.round(pos.z) + ")";
    }
}
