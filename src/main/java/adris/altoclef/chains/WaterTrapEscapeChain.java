package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToChunkTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.WaterRouteBlacklist;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.List;

/**
 * (2026-10-22) 水困脱困守卫 —— 「必须到达型导航」陷在水中反复空转时的主动换路者。
 *
 * <h2>事故背景（20261020-220030 bot2）</h2>
 * 次 bot 远程接近领队，直线跨大片水域。从 22:36 陷进水区到 23:29 会话结束约 53 分钟
 * 零净推进（WATER-EXIT 49 次）：水面抖动骗过瞬时 isTouchingWater → 每 1.2s forceCancel
 * 撤路线；岸缝障碍轮着派挖障 → 挖障任务"泡水先上岸"闩锁反复打断。反应层修复见
 * {@code JumpStuckResolver}（2026-10-22）；本链负责【战略层不收敛】—— 反应层修完后，
 * 若导航仍然在原地打转（真·绕不过去的水域），必须有"认输换路"的出口，否则就是下一个
 * 53 分钟。
 *
 * <h2>触发判据（全部可静态计算，防误伤）</h2>
 * <ol>
 *   <li>当前胜出链是 User Tasks，任务栈里存在"必须到达型导航"叶子
 *       （{@link GetToBlockTask} / {@link GetToChunkTask}），且目标 ≥90s 未变；</li>
 *   <li>90s 内水平净位移 &lt; {@link #STAGNATION_DIST} 格（对照起点快照）；</li>
 *   <li>期间 {@code WATER-EXIT}（DestroyBlockTask 水中闩锁）新增 ≥{@link #MIN_WATER_EXITS} 次。</li>
 * </ol>
 * 三条同时满足 = "导航卡在水上原地空转"，普通慢赶路（位移正常）绝不触发。
 *
 * <h2>动作（one-shot，逐级升级）</h2>
 * <ol>
 *   <li>把「卡死位置簇 → 导航目标」写进 {@link WaterRouteBlacklist} 走廊拉黑；</li>
 *   <li>侧偏：垂直目标方向 ±90°、{@link #SIDE_OFFSET_DIST} 格的 waypoint（先过
 *       isRouteBlocked 筛不命中的方向），派 {@link GetToXZTask} 先离开水域；</li>
 *   <li>同一导航目标侧偏 {@link WaterRouteBlacklist#MAX_SIDE_OFFSETS_PER_TARGET} 次仍
 *       被走廊围死 → 上报对端「前往 X 的路线被大水封死」止损，本链对同一目标冷却
 *       {@link #REPORT_COOLDOWN_MS}，把决定权交给人/LLM。</li>
 * </ol>
 * 保证：要么从错误路线集合里走出去，要么有限步内上报 —— 不存在无限重走。
 *
 * <h2>优先级</h2>
 * 53 —— 高于 SurfaceExitChain(52)/User Tasks(50)，低于 Food(55)/Death(70)/Defense(95)。
 * 脱困可以等吃完饭，但不能被主线导航按住。
 */
public class WaterTrapEscapeChain extends SingleTaskChain {

    /** 见类注释。 */
    private static final float ACTIVE_PRIORITY = 53.0f;

    /** 导航目标稳定多久才允许判定（90s）。 */
    private static final int STAGNATION_TICKS = 20 * 90;
    /** 判定窗口内允许的最大水平净位移（格）。 */
    private static final double STAGNATION_DIST = 8.0;
    /** 窗口内至少新增多少次 WATER-EXIT 才算"水困"证据。 */
    private static final int MIN_WATER_EXITS = 2;
    /** 侧偏 waypoint 距离（格）。 */
    private static final int SIDE_OFFSET_DIST = 32;
    /** waypoint 预算：超时视为该次侧偏失败，释放重评。 */
    private static final double WAYPOINT_BUDGET_SECONDS = 90;
    /** 上报止损后对同一目标的沉默期。 */
    private static final long REPORT_COOLDOWN_MS = 5 * 60_000L;

    private BlockPos _navTarget = null;
    /** 目标稳定性追踪：首见 tick / 起点快照 / WATER-EXIT 基线。 */
    private int _firstSeenTick = 0;
    private BlockPos _stagnationStartPos = null;
    private long _baselineWaterExits = -1;
    /** 当前脱困 waypoint（null = 未在脱困中）。 */
    private BlockPos _waypoint = null;
    private final TimerGame _waypointTimer = new TimerGame(WAYPOINT_BUDGET_SECONDS);
    /** 上报止损的冷却（key = 导航目标）。 */
    private BlockPos _reportedTarget = null;
    private final TimerGame _reportCooldown = new TimerGame(REPORT_COOLDOWN_MS / 1000.0);

    public WaterTrapEscapeChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority(AltoClef mod) {
        if (!AltoClef.inGame() || mod.getPlayer() == null) {
            releaseHold();
            return Float.NEGATIVE_INFINITY;
        }

        // —— 脱困进行中：预算耗尽即释放（waypoint 本身可能也被地形卡住）——
        if (_waypoint != null) {
            if (_waypointTimer.elapsed()) {
                Debug.logToFile("altoclef_diag.log", "[WATER-TRAP] waypoint budget "
                        + (int) WAYPOINT_BUDGET_SECONDS + "s exceeded for " + _waypoint.toShortString()
                        + " -> release, guard will re-evaluate");
                releaseHold();
                return Float.NEGATIVE_INFINITY;
            }
            setTask(new GetToXZTask(_waypoint.getX(), _waypoint.getZ()));
            return ACTIVE_PRIORITY;
        }

        // —— 评估触发 ——
        BlockPos navTarget = findNavTarget(mod);
        if (navTarget == null) {
            resetStagnation();
            releaseHold();
            return Float.NEGATIVE_INFINITY;
        }
        int nowTick = WorldHelper.getTicks();
        if (!navTarget.equals(_navTarget)) {
            // 新目标：重开稳定性窗口；旧目标遗留的走廊拉黑仍在 TTL 内继续生效。
            _navTarget = navTarget;
            _firstSeenTick = nowTick;
            _stagnationStartPos = mod.getPlayer().getBlockPos();
            _baselineWaterExits = WaterRouteBlacklist.waterExitTotal();
            releaseHold();
            return Float.NEGATIVE_INFINITY;
        }

        // 上报止损冷却期内不再动手（决定权已交出去）。
        if (_reportedTarget != null && _reportedTarget.equals(navTarget) && !_reportCooldown.elapsed()) {
            return Float.NEGATIVE_INFINITY;
        }

        BlockPos cur = mod.getPlayer().getBlockPos();
        double netDisp = _stagnationStartPos == null ? 0
                : Math.hypot(cur.getX() - _stagnationStartPos.getX(),
                             cur.getZ() - _stagnationStartPos.getZ());
        long newWaterExits = WaterRouteBlacklist.waterExitTotal() - _baselineWaterExits;
        if (nowTick - _firstSeenTick < STAGNATION_TICKS
                || netDisp >= STAGNATION_DIST
                || newWaterExits < MIN_WATER_EXITS) {
            return Float.NEGATIVE_INFINITY;
        }

        // —— 触发 ——
        _firstSeenTick = nowTick;   // 重开窗口：下一次判定从本动作之后重新积累
        _stagnationStartPos = cur;
        _baselineWaterExits = WaterRouteBlacklist.waterExitTotal();

        WaterRouteBlacklist.blockCorridor(cur, navTarget,
                "watertrap@" + cur.toShortString() + "->" + navTarget.toShortString());

        int used = WaterRouteBlacklist.sideOffsetCount(navTarget);
        if (used >= WaterRouteBlacklist.MAX_SIDE_OFFSETS_PER_TARGET) {
            String m = "[WATER-TRAP] 前往 " + navTarget.toShortString()
                    + " 的路线被大水封死（已试 " + used + " 个方向，坐标 "
                    + cur.toShortString() + "），本次赶路建议放弃或换目标";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", "[WATER-TRAP] quota exhausted -> report: " + m);
            _reportedTarget = navTarget;
            _reportCooldown.reset();
            return Float.NEGATIVE_INFINITY;
        }

        BlockPos wp = pickSideOffset(mod, navTarget);
        if (wp == null) {
            Debug.logToFile("altoclef_diag.log", "[WATER-TRAP] no unblocked side offset found at "
                    + cur.toShortString() + " -> treat as quota step");
            WaterRouteBlacklist.bumpSideOffset(navTarget);
            return Float.NEGATIVE_INFINITY;
        }
        WaterRouteBlacklist.bumpSideOffset(navTarget);
        _waypoint = wp;
        _waypointTimer.reset();
        String m = "[WATER-TRAP] 导航疑似被水困住（目标 " + navTarget.toShortString() + " "
                + (int) (STAGNATION_TICKS / 20) + "s 净位移 " + String.format("%.1f", netDisp)
                + " 格，水中上岸失败 " + newWaterExits + " 次）→ 走廊拉黑 + 侧偏到 "
                + wp.toShortString() + "（第 " + (used + 1) + "/"
                + WaterRouteBlacklist.MAX_SIDE_OFFSETS_PER_TARGET + " 次）";
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
        setTask(new GetToXZTask(wp.getX(), wp.getZ()));
        return ACTIVE_PRIORITY;
    }

    /**
     * 从 User Tasks 任务栈里找最深的"必须到达型导航"目标；无则 null。
     * GetToChunkTask 取区块中心（与 GoalChunk 语义一致）。
     */
    private BlockPos findNavTarget(AltoClef mod) {
        try {
            TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
            if (chain == null || !"User Tasks".equals(chain.getName())) return null;
            List<Task> tasks = chain.getTasks();
            BlockPos found = null;
            for (Task t : tasks) {
                if (t instanceof GetToBlockTask g) {
                    found = g.getTargetPos();
                } else if (t instanceof GetToChunkTask) {
                    // 拿不到私有 ChunkPos，走 debug 字符串太脆 —— 用栈里更下层的
                    // GetToBlockTask 目标；单独出现时从 toDebugString 反解。
                    found = parseChunkCenter(t);
                }
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    /** "Get to chunk: [20, -10]" → 区块中心 (startX+8, y=0, startZ+8)。 */
    private BlockPos parseChunkCenter(Task t) {
        try {
            String s = t.toString();
            int l = s.indexOf('['), r = s.indexOf(']');
            if (l < 0 || r < 0) return null;
            String[] parts = s.substring(l + 1, r).split(",");
            int cx = Integer.parseInt(parts[0].trim());
            int cz = Integer.parseInt(parts[1].trim());
            ChunkPos pos = new ChunkPos(cx, cz);
            return new BlockPos(pos.getStartX() + 8, 0, pos.getStartZ() + 8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 侧偏点：垂直目标方向 ±90°（先右后左，都被走廊挡就 ±45° 扇区里找），
     * 距离 {@link #SIDE_OFFSET_DIST}；候选本身必须不被 isRouteBlocked 拦下。
     */
    private BlockPos pickSideOffset(AltoClef mod, BlockPos target) {
        BlockPos cur = mod.getPlayer().getBlockPos();
        double dx = target.getX() - cur.getX();
        double dz = target.getZ() - cur.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1) return null;
        dx /= len;
        dz /= len;
        double[][] candidates = {
                {dz, -dx},   // 右 90°
                {-dz, dx},   // 左 90°
                {(dz - dx) / 1.414, (-dx - dz) / 1.414},  // 右 45°
                {(-dz + dx) / 1.414, (dx + dz) / 1.414},  // 左 45°
        };
        for (double[] c : candidates) {
            BlockPos wp = new BlockPos(cur.getX() + (int) Math.round(c[0] * SIDE_OFFSET_DIST),
                    cur.getY(), cur.getZ() + (int) Math.round(c[1] * SIDE_OFFSET_DIST));
            if (!WaterRouteBlacklist.isRouteBlocked(cur, wp)) {
                return wp;
            }
        }
        return null;
    }

    private void resetStagnation() {
        _navTarget = null;
        _firstSeenTick = 0;
        _stagnationStartPos = null;
        _baselineWaterExits = -1;
    }

    /** 让出控制权 + 清本链状态（幂等）。 */
    private void releaseHold() {
        _waypoint = null;
        if (getCurrentTask() != null) {
            setTask(null);
        }
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        if (_waypoint != null) {
            Debug.logToFile("altoclef_diag.log", "[WATER-TRAP] side offset reached "
                    + _waypoint.toShortString() + " -> release, navigation resumes from here");
        }
        releaseHold();
    }

    @Override
    public String getName() {
        return "Water Trap Escape Chain";
    }

    /**
     * 恒 true —— 本仓链的铁律（SurfaceExitChain 类注释 §isActive）：漏了 = 本链不存在。
     */
    @Override
    public boolean isActive() {
        return true;
    }
}
