package adris.altoclef.anomaly;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * (2026-09-11 异常看门狗, P2) 任务环检测。
 *
 * 与 {@code TaskSwitchLogger} 的抖动检测互补:
 *   - 抖动检测抓的是**周期 2** 的来回横跳 (A<->B), 且只看 5s 窗口。
 *   - 本检测器抓**周期 >= 3** 的循环 (A->B->C->A->B->C...), 以及周期长到 5s
 *     窗口装不下的慢速循环。
 *
 * 判据: 把任务派发事件压缩成"节点序列" (节点 = 父任务:旧子 -> 新子), 连续相同的
 * 节点只记一次; 若序列尾部能解析出周期 p (3 <= p <= 8) 且**连续重复 4 轮**,
 * 判定为任务环。
 *
 * 为什么不报 WARN 而是 NOTICE: 这是个**启发式**判据 —— 合法行为也可能在短时间内
 * 呈现固定节奏 (例如连续采同一批方块、循环合成)。这类无法靠白名单枚举干净的
 * 检测, 先只落盘、不刷聊天, 用几场数据验证精度之后再决定要不要升级成实时告警。
 * 这也符合本项目"告警必须经得起误报审视"的一贯约束。
 *
 * 事件式而非轮询式: 环只在任务切换的瞬间才产生, 每 tick 轮询拿不到转场序列。
 * 由 {@code TaskSwitchLogger} 在每次派发时调用 {@link #observe(String)}。
 */
public final class TaskCycleDetector {

    /** 周期下界: 2 交给抖动检测, 这里不重复管。 */
    private static final int MIN_PERIOD = 3;
    /** 周期上界: 再长的"环"更像正常流程而非死循环。 */
    private static final int MAX_PERIOD = 8;
    /** 连续重复多少轮才判定为环。 */
    private static final int MIN_REPEATS = 4;
    /** 节点序列保留长度 (够放下 MAX_PERIOD * MIN_REPEATS 还留余量)。 */
    private static final int MAX_HISTORY = 64;
    /** 一个环必须在此时长内走完这 MIN_REPEATS 轮, 否则是正常的长流程。 */
    private static final long WINDOW_MS = 90_000;

    private static final ArrayDeque<String> _tokens = new ArrayDeque<>();
    private static final ArrayDeque<Long> _times = new ArrayDeque<>();
    private static String _lastToken = null;

    private TaskCycleDetector() {
    }

    /**
     * 记录一次任务派发。token 应能唯一标识"哪一步" (父任务 + 旧子 -> 新子),
     * 由 TaskSwitchLogger 构造。
     */
    public static void observe(String token) {
        if (token == null) return;
        // 看门狗关着时不积累历史, 否则开启瞬间会把关闭期间攒的环误报出来。
        if (!AnomalyWatchdog.isEnabled()) {
            reset();
            return;
        }
        // 连续两次同一个节点不算新节点 (任务树每 tick 重跑会重复派发同一件事)。
        if (token.equals(_lastToken)) return;
        _lastToken = token;

        long now = System.currentTimeMillis();
        _tokens.addLast(token);
        _times.addLast(now);
        while (_tokens.size() > MAX_HISTORY) {
            _tokens.pollFirst();
            _times.pollFirst();
        }
        while (!_times.isEmpty() && now - _times.peekFirst() > WINDOW_MS) {
            _times.pollFirst();
            _tokens.pollFirst();
        }

        int n = _tokens.size();
        if (n < MIN_PERIOD * MIN_REPEATS) return;

        String[] arr = _tokens.toArray(new String[0]);
        for (int p = MIN_PERIOD; p <= MAX_PERIOD; p++) {
            if (n < p * MIN_REPEATS) continue;
            if (!tailIsPeriodic(arr, n, p)) continue;

            String[] period = Arrays.copyOfRange(arr, n - p, n);
            String signature = canonical(period);
            AnomalyWatchdog.report(new Anomaly(
                    "cycle", signature, AnomalySeverity.NOTICE,
                    "疑似任务环 (周期 " + p + ", 重复 " + MIN_REPEATS + " 轮 / "
                            + (WINDOW_MS / 1000) + "s 内): " + signature,
                    null));
            // 一次事件最多报一条: 取最小周期即为该环的最简描述。
            return;
        }
    }

    public static void reset() {
        _tokens.clear();
        _times.clear();
        _lastToken = null;
    }

    /**
     * 尾部是否为目标周期。把序列尾部按 p 切成块 B0(末尾) / B1 / B2 ...,
     * 要求相邻两块逐元素相等且共计 MIN_REPEATS 块 —— 即
     * {@code arr[n-1-i] == arr[n-1-i-p]} 对 i = 0 .. (MIN_REPEATS-2)*p-1 全成立。
     */
    private static boolean tailIsPeriodic(String[] arr, int n, int p) {
        for (int i = 0; i < (MIN_REPEATS - 1) * p; i++) {
            int a = n - 1 - i;
            int b = a - p;
            if (b < 0) return false;
            if (!arr[a].equals(arr[b])) return false;
        }
        return true;
    }

    /**
     * 把环规范化为唯一签名: 旋转到字典序最小, 使同一环的任意起点产生同一个 key。
     * 目的: watchdog 的去重键必须稳定, 否则同一环会因为"从哪个节点开始截取"
     * 不同而绕过去重, 变成每转一圈报一次。
     */
    private static String canonical(String[] period) {
        int p = period.length;
        int best = 0;
        for (int i = 1; i < p; i++) {
            for (int k = 0; k < p; k++) {
                int cmp = period[i + k >= p ? i + k - p : i + k]
                        .compareTo(period[best + k >= p ? best + k - p : best + k]);
                if (cmp < 0) {
                    best = i;
                    break;
                }
                if (cmp > 0) break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < p; k++) {
            if (k > 0) sb.append(" -> ");
            sb.append(period[best + k >= p ? best + k - p : best + k]);
        }
        return sb.toString();
    }
}
