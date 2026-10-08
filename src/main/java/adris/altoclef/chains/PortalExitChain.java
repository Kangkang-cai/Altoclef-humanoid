package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.PortalExitHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-10-08) "刚换维度、人还压在到达门门块上"的主动出闸持有者。
 *
 * <h2>问题现场 (20261008-162800, bot1 三连弹回)</h2>
 * 16:40:09 进下界 → 到达点 (-49.5,48,87) 紧贴到达门门块 (AABB 横跨门块) →
 * 进门后的首个导航任务 baritone 卡算路 (path present=false / calc inProgress 挂死) →
 * bot 原地压门 4s+ → vanilla 弹回主世界。16:40 / 17:07×2 / 17:09 四次同签名。
 * 弹回后主世界侧又在门里被二次弹走, 进门-弹回循环。
 *
 * <p>这是床闸修好 (4f7488aa) 后"进门真正成功"才暴露的到达侧隐患: 以前进门本身
 * 就失败, 到达行为根本没被触发。它不是 4f7488aa 的 stepOut 跑偏 (本次会话
 * stepOut 一次都没执行过, 它只消费于主世界→下界方向的床闸)。
 *
 * <h2>为什么用直连位移而不是 GetToBlockTask</h2>
 * 冻结根因是 baritone 自身 (calc 挂死 / 无路径), 再派一个 baritone 导航只会
 * 一起冻住。出闸只需横向挪 1~2 格, 用 nudge 直连位移 (同 PlaceBlockTask#
 * nudgeAwayFromTarget 模式): lookAt 门外方向 + hold MOVE_FORWARD, 2s 预算,
 * 到点 (碰撞箱脱离门块) 即收口松键。
 *
 * <h2>触发窗口与守卫</h2>
 * <ul>
 *   <li>只在【换维度后 {@value #ARRIVAL_WINDOW_SECONDS}s 内】且碰撞箱与门块
 *       相交时触发 (pollDimensionChange 每 tick 武装窗口) — 覆盖 vanilla
 *       传送冷却 + 4s 累计的上限, 窗口外绝不插手;</li>
 *   <li>主线任务栈上有 {@link EnterNetherPortalTask} (有意站在门里等传送 /
 *       10s 超时 wander) 时绝不抢 — 20261008 17:07:52 (弹回后 27s) 的再进门
 *       就落在这窗口内, 漏了这条会挡住合法进门;</li>
 *   <li>预算超时 = 松键放行 (isFinished 收口), 窗口期内最多重试窗口/预算次,
 *       有界; 窗口关闭后回到现状 (最多 vanilla 弹回, 不比以前差)。</li>
 * </ul>
 *
 * <h2>优先级</h2>
 * {@link #ACTIVE_PRIORITY} = 56 — 高于 User Tasks(50)/SurfaceExit(52)/Food(55):
 * 4s 传送窗等不起一顿饭; 让位 Death(70)/MobDefense(95)/WorldSurvival(100)/MLG(100)。
 */
public class PortalExitChain extends SingleTaskChain {

    private static final float ACTIVE_PRIORITY = 56.0f;

    /** 换维度后多久内的"压门"才算到达残留。 */
    private static final double ARRIVAL_WINDOW_SECONDS = 30.0;

    /** 单次直连位移预算 (ticks, ~2s)。超时松键收口, 由窗口期的重试兜底。 */
    private static final int NUDGE_MAX_TICKS = 40;

    private Dimension _lastDimension = null;
    private final TimerGame _sinceArrival = new TimerGame(ARRIVAL_WINDOW_SECONDS);

    public PortalExitChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority(AltoClef mod) {
        pollDimensionChange(mod);
        if (!eligible(mod)) {
            releaseHold();
            return Float.NEGATIVE_INFINITY;
        }
        if (!isCurrentlyRunning(mod)) {
            Debug.logToFile("altoclef_diag.log", "[PortalExit] arrival portal overlap, taking over player="
                    + mod.getPlayer().getBlockPos().toShortString()
                    + " dim=" + WorldHelper.getCurrentDimension());
        }
        // 幂等: isEqual 比较类型, 同类任务 setTask 是 no-op。
        setTask(new StepOutNudgeTask());
        return ACTIVE_PRIORITY;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // isFinished 已松键 (onStop); 清掉任务让窗口期内的下一拍重新评估。
        setTask(null);
    }

    /**
     * 每 tick (getPriority 对所有链常驻调用) 轮询维度, 换维度即武装到达窗口。
     * TimerGame 新建即到期 (SurfaceExitChain v6 教训), 默认窗口关闭, 不误触发。
     */
    private void pollDimensionChange(AltoClef mod) {
        if (mod.getPlayer() == null) return;
        Dimension current = WorldHelper.getCurrentDimension();
        if (_lastDimension == null) {
            _lastDimension = current;
            return;
        }
        if (current != _lastDimension) {
            _lastDimension = current;
            _sinceArrival.reset();
            Debug.logToFile("altoclef_diag.log", "[PortalExit] dimension entry " + current
                    + ", arrival window armed " + (int) ARRIVAL_WINDOW_SECONDS + "s");
        }
    }

    private boolean eligible(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        if (_sinceArrival.elapsed()) return false;
        if (!PortalExitHelper.isPlayerIntersectingNetherPortal(mod)) return false;
        // 有意进门时绝不抢: EnterNetherPortalTask 要的就是站在门里等传送。
        Task root = mod.getUserTaskChain().getCurrentTask();
        if (root != null && root.thisOrChildSatisfies(t -> t instanceof EnterNetherPortalTask)) {
            return false;
        }
        return true;
    }

    /** 松键 + 清任务 (窗口关闭/不相交时每 tick 调, 幂等)。 */
    private void releaseHold() {
        if (_mainTask != null) {
            setTask(null);
        }
    }

    /**
     * 直连位移出闸: 朝最近门块的反方向按前进, 碰撞箱脱离门块即完成。
     * 预算超时也算完成 (松键), 窗口期内下一拍重评 — 有界重试, 不按死。
     */
    private class StepOutNudgeTask extends Task {

        private long _sinceTick = -1;

        @Override
        protected void onStart(AltoClef mod) {
            _sinceTick = WorldHelper.getTicks();
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (isFinished(mod)) {
                return null;
            }
            BlockPos portal = PortalExitHelper.nearestNetherPortalBlock(mod);
            if (portal != null) {
                Vec3d p = mod.getPlayer().getPos();
                double dx = p.x - (portal.getX() + 0.5);
                double dz = p.z - (portal.getZ() + 0.5);
                if (dx == 0 && dz == 0) {
                    dx = 1; // 中心重合理论上不可达 (相交≠包含), 兜底朝 +x
                }
                LookHelper.lookAt(mod, new Vec3d(p.x + dx, p.y, p.z + dz));
            }
            // 不让 baritone 抢输入 (与 PlaceBlockTask#nudgeAwayFromTarget 同模式)
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.SNEAK);
            setDebugState("Stepping out of arrival portal (direct nudge)");
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            if (_sinceTick < 0) return false;
            if (!PortalExitHelper.isPlayerIntersectingNetherPortal(mod)) return true;
            return WorldHelper.getTicks() - _sinceTick > NUDGE_MAX_TICKS;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this || (other instanceof StepOutNudgeTask);
        }

        @Override
        public String toString() {
            return "Stepping out of arrival portal";
        }

        @Override
        public String toDebugString() {
            return "Stepping out of arrival portal (direct nudge)";
        }
    }

    @Override
    public String getName() {
        return "Portal Exit Chain";
    }
}
