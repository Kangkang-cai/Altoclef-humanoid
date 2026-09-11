package adris.altoclef.anomaly;

import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;

import java.util.ArrayDeque;

/**
 * (2026-09-11 异常看门狗) 目标不可达检测。
 *
 * 信号完全取自 baritone 自身, 因此**不需要知道任务的具体目标坐标**:
 *
 *   任务栈上存在 CustomBaritoneGoalTask (说明"期望在寻路")
 *   但 baritone 既不在 pathing、也没有可用 path, 且持续 HOLE_MS
 *   → 记一次"寻路空洞"; 滚动窗口内空洞次数达到 MAX_HOLES → 判定目标不可达。
 *
 * 与 {@link StallDetector} 正交: 那边看"位置动没动", 这边看"路算不算得出来"。
 * 两者同时命中时, 快照里的 baritone 字段能让人一眼分清是哪种。
 *
 * 阈值说明: CustomBaritoneGoalTask 自己的 active-stale 闸门是 4s, 所以单次空洞
 * 门槛取 6s 更保守 —— 不会把远距离分段算路误判成空洞。再叠加"60s 内至少 3 次"
 * 的要求, 才算真正判死。
 */
public final class UnreachableDetector implements AnomalyDetector {

    /** 连续多久"期望寻路但算不出路"才算一次空洞。 */
    private static final long HOLE_MS = 6_000;
    /** 空洞计数的滚动窗口。 */
    private static final long WINDOW_MS = 60_000;
    /** 窗口内空洞次数达到此值判定不可达。 */
    private static final int MAX_HOLES = 3;

    private boolean _inHole = false;
    private long _holeSinceMs = 0;
    private final ArrayDeque<Long> _holes = new ArrayDeque<>();

    @Override
    public String name() {
        return "unreachable";
    }

    @Override
    public void reset() {
        _inHole = false;
        _holeSinceMs = 0;
        _holes.clear();
    }

    @Override
    public Anomaly check(AnomalyContext ctx) {
        if (!ctx.inGame() || ctx.paused()) return null;
        // baritone 没读到状态 → 不下任何结论 (未就绪 / 跨世界切换)
        if (!ctx.baritoneAvailable()) {
            _inHole = false;
            return null;
        }
        // 当前没有任务在期待寻路 → 不是空洞
        if (!ctx.stackHasTask(CustomBaritoneGoalTask.class)) {
            _inHole = false;
            return null;
        }
        // baritone 在干活 → 正常
        if (ctx.baritonePathing() || ctx.baritonePathPresent()) {
            _inHole = false;
            return null;
        }

        long now = System.currentTimeMillis();
        if (!_inHole) {
            _inHole = true;
            _holeSinceMs = now;
            return null;
        }
        if (now - _holeSinceMs < HOLE_MS) return null;

        // 一次空洞成立。立即解除空洞态, 避免同一次空洞被反复计数。
        _inHole = false;
        _holes.addLast(now);
        while (!_holes.isEmpty() && now - _holes.peekFirst() > WINDOW_MS) {
            _holes.pollFirst();
        }
        if (_holes.size() < MAX_HOLES) return null;

        String summary = "目标疑似不可达: " + (WINDOW_MS / 1000) + "s 内 " + _holes.size()
                + " 次算不出路径 (" + ctx.deepestTaskClass() + ")";
        String key = ctx.chainName() == null ? "none" : ctx.chainName();

        return new Anomaly("unreachable", key, AnomalySeverity.WARN, summary, null);
    }
}
