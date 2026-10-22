package adris.altoclef.util.helpers;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (2026-10-22) 水路失败走廊拉黑 —— 「确保脱困后不重走错误路线」的唯一判定源。
 *
 * <h2>为什么建在任务派发层而不是 baritone 层</h2>
 * 本仓字节码取证实锤（detail/08-baritone-api.md §水代价取证）：baritone 没有任何
 * settings 级水代价杠杆（walkOnWaterOnePenalty 死代码 / waterWalkSpeed 硬编码 /
 * 全局启发式死钩子），且无路径级禁行注入（blocksToAvoid 硬禁=A* 起点判据全灭）。
 * 所以"这条失败路线别再走"只能由我们自己的导航派发层拦截 —— 本类等价于
 * 「只对失败过的那条水线加无穷惩罚」，不影响正常过河。
 *
 * <h2>粒度：走廊，不是点</h2>
 * 渡口坐标 ±N 格拉黑是概率性的：大水域有的是别的渡口，新路线可以从未拉黑的点
 * 再穿一次。这里以「失败位置簇 → 导航目标」的直线为轴 ±{@link #CORRIDOR_HALF_WIDTH}
 * 格建走廊，语义是"这个方向这片水穿不过"。判定 = 新线段与任一走廊在 XZ 平面
 * 相交（含宽度膨胀）。
 *
 * <h2>消费方契约（拉黑必须被所有选点路径消费，漏一路=循环照旧，1015 容器拉黑教训）</h2>
 * <ul>
 *   <li>{@code WaterTrapEscapeChain} —— 触发时写走廊 + 侧偏动作（唯一写入方）；</li>
 *   <li>{@code GetToChunkTask.onWander} —— wander 朝向选点消费；</li>
 *   <li>新增"必须到达型导航"重建点时必须接 {@link #isRouteBlocked}。</li>
 * </ul>
 *
 * <h2>出口</h2>
 * 走廊 TTL {@link #CORRIDOR_TTL_MS}（水情会变，过期允许重试，重试即重新用守卫
 * 判据验证，不算"重走"）；侧偏配额 {@link #MAX_SIDE_OFFSETS_PER_TARGET} 耗尽后
 * 上报对端止损 —— 两种结局都不存在无限重走。
 */
public final class WaterRouteBlacklist {

    private WaterRouteBlacklist() {}

    /** 走廊半宽（格）：轴线 ±24。 */
    public static final double CORRIDOR_HALF_WIDTH = 24.0;
    /** 走廊有效期：10 分钟。 */
    public static final long CORRIDOR_TTL_MS = 10 * 60_000L;
    /** 同一导航目标最多侧偏次数，耗尽 = 上报对端止损。 */
    public static final int MAX_SIDE_OFFSETS_PER_TARGET = 3;
    /** 侧偏计数/水流出口计数的遗忘窗口。 */
    private static final long COUNTER_TTL_MS = 30 * 60_000L;

    /** 一条失败走廊：XZ 平面线段 (a→b) + 过期时刻。 */
    private static final class Corridor {
        final double ax, az, bx, bz;
        final long expiresAtMs;
        final String label;

        Corridor(double ax, double az, double bx, double bz, long expiresAtMs, String label) {
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
            this.expiresAtMs = expiresAtMs;
            this.label = label;
        }
    }

    private static final List<Corridor> _corridors = new ArrayList<>();
    /** 导航目标 → 侧偏已用次数。 */
    private static final Map<String, int[]> _sideOffsets = new ConcurrentHashMap<>();
    /** WATER-EXIT 事件总数（DestroyBlockTask 水中闩锁触发时 +1），守卫读窗口增量。 */
    private static final java.util.concurrent.atomic.AtomicLong _waterExits = new java.util.concurrent.atomic.AtomicLong();

    /** DestroyBlockTask 水中闩锁触发时调用（边沿，一次触发一次）。 */
    public static void noteWaterExit() {
        _waterExits.incrementAndGet();
    }

    /** 守卫读：WATER-EXIT 事件总数（配合窗口基线算增量）。 */
    public static long waterExitTotal() {
        return _waterExits.get();
    }

    /** 记一条失败走廊（脱困守卫触发时调用）。 */
    public static synchronized void blockCorridor(BlockPos from, BlockPos to, String label) {
        long now = System.currentTimeMillis();
        // 简单去重：同轴近似（两端都在旧走廊 24 格内）则只续期。
        for (Corridor c : _corridors) {
            if (c.expiresAtMs < now) continue;
            if (pointNearSeg(from.getX(), from.getZ(), c) && pointNearSeg(to.getX(), to.getZ(), c)) {
                return;
            }
        }
        _corridors.add(new Corridor(from.getX(), from.getZ(), to.getX(), to.getZ(),
                now + CORRIDOR_TTL_MS, label));
        // 顺手清理过期。
        _corridors.removeIf(c -> c.expiresAtMs < now);
    }

    /**
     * 唯一判定源：from→to 的 XZ 线段是否与任一未过期走廊相交（含 {@link
     * #CORRIDOR_HALF_WIDTH} 宽度膨胀）。所有"必须到达型导航"的派发/重建点必须消费。
     */
    public static synchronized boolean isRouteBlocked(BlockPos from, BlockPos to) {
        long now = System.currentTimeMillis();
        _corridors.removeIf(c -> c.expiresAtMs < now);
        for (Corridor c : _corridors) {
            if (segNearSeg(from.getX(), from.getZ(), to.getX(), to.getZ(), c)) {
                return true;
            }
        }
        return false;
    }

    /** 当前生效走廊数（诊断）。 */
    public static synchronized int activeCorridors() {
        long now = System.currentTimeMillis();
        _corridors.removeIf(c -> c.expiresAtMs < now);
        return _corridors.size();
    }

    /** 同一导航目标的侧偏配额：bump 后返回已用次数。 */
    public static int bumpSideOffset(BlockPos target) {
        String key = target.toShortString();
        int[] v = _sideOffsets.computeIfAbsent(key, k -> new int[]{0, (int) (System.currentTimeMillis() / 1000)});
        v[0]++;
        v[1] = (int) (System.currentTimeMillis() / 1000);
        return v[0];
    }

    public static int sideOffsetCount(BlockPos target) {
        int[] v = _sideOffsets.get(target.toShortString());
        return v == null ? 0 : v[0];
    }

    // ===== 几何：均为 XZ 平面 =====

    /** 点到线段距离 ≤ 半宽。 */
    private static boolean pointNearSeg(double px, double pz, Corridor c) {
        return segPointDist(px, pz, c.ax, c.az, c.bx, c.bz) <= CORRIDOR_HALF_WIDTH;
    }

    /** 线段 (px,pz)-(qx,qz) 与走廊线段距离 ≤ 半宽。 */
    private static boolean segNearSeg(double px, double pz, double qx, double qz, Corridor c) {
        // 精确够用即可：任一端点在走廊带内，或两线段真相交。
        if (pointNearSeg(px, pz, c) || pointNearSeg(qx, qz, c)) return true;
        return segSegIntersect(px, pz, qx, qz, c.ax, c.az, c.bx, c.bz);
    }

    private static double segPointDist(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx, cz = az + t * dz;
        return Math.hypot(px - cx, pz - cz);
    }

    private static boolean segSegIntersect(double ax, double az, double bx, double bz,
                                           double cx, double cz, double dx, double dz) {
        double d1 = cross(cx, cz, dx, dz, ax, az);
        double d2 = cross(cx, cz, dx, dz, bx, bz);
        double d3 = cross(ax, az, bx, bz, cx, cz);
        double d4 = cross(ax, az, bx, bz, dx, dz);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double cross(double ax, double az, double bx, double bz, double px, double pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }
}
