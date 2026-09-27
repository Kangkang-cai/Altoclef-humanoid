package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.SurfaceExitGuide;
import adris.altoclef.util.helpers.SurfaceHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-09-27) "出地表后必须走离洞口一段距离"的**唯一持有者**。
 *
 * <h2>为什么要单独立一条链, 而不是让各个任务自己去消费配额</h2>
 * 出洞安全引导 ({@link SurfaceExitGuide}, {@code 87d7c9ad} 引入 / {@code b94d0eb1} 加固)
 * 原本是一个**被动配额**: 翻面到地表时武装, 然后**指望某个导航任务主动来消费**那 3 次
 * "强制落到 ≥24 格外的强签名地表点"。于是"谁该消费"变成了一份隐式契约 ——
 * 实现在 3 个远征/探索任务类里, 而"回到表面"这条主路径
 * ({@code GoToSurfaceTask → GetToYTask}) 一处都没接。
 *
 * <p>更致命的是**时序**: 引导在 {@code SurfaceHelper} 判定翻面的**同一拍**武装, 而
 * "什么时候还需要回表面"用的正是同一个 {@code isBelowSurfaceDebounced} —— 也就是说
 * 武装发生的瞬间, 上层就已经把 {@code GoToSurfaceTask} 换成别的任务了。所以就算把
 * 消费点接到 {@code GoToSurfaceTask} 的导航上, 它也**永远等不到武装**。
 *
 * <p>现场 20260927-152940 实锤: 15:32:23.568 打出
 * {@code [SURFACE-EXIT] armed ... next 3 navigation(s)}, 同一毫秒 15:32:23.569 大脑切到
 * {@code Stockpiling wood}; 配额 3 一次没用上, 引导一路空转到超时 —— 用户原话
 * "这个之前做过, 但似乎没有统一做到所有回表面任务"。
 *
 * <p>所以根治办法不是再补一个消费方, 而是**把被动配额换成主动持有者**: 本链在武装期内
 * 自己拿控制权把 bot 走到安全落点。于是
 * <ul>
 *   <li>覆盖**所有**出洞路径 —— 不管是 {@code GoToSurfaceTask} 挖上去、自己凿上来的、
 *       垫塔逃出来的还是复活出来的, 都不需要任何调用点改动;</li>
 *   <li>隐式契约消失 —— 不再有"某个任务忘了消费 → 引导空转"这种失败模式;</li>
 *   <li>与逃生/战斗/死亡的优先级关系由 {@code getPriority} 统一仲裁, 不靠各任务自觉。</li>
 * </ul>
 *
 * <h2>优先级</h2>
 * {@link #ACTIVE_PRIORITY} = 52 —— 只比 User Tasks(50) 高一点: "先把路走完再干活",
 * 但让位给 FoodChain(55)、DeathRecovery(70)、MobDefense(95)、WorldSurvival(100)、
 * MLGBucket(100)。取 52 而不是 55+ 的原因: 与吃饭并存时吃饭优先, 本链可以等。
 *
 * <h2>与既有 3 个消费方的关系</h2>
 * 远征/探索任务的引导代码保留不动。它们跑在 FoodChain(55) 或 User Tasks(50) 之下:
 * 前者优先级高于本链 → 由它们自己消费配额 (结果相同); 后者低于本链 → 本链先接管,
 * 它们根本进不了武装窗口。两条路径都收敛到"走够 3 个落点", 不会双重消费 ——
 * 因为配额是 {@link SurfaceExitGuide} 的静态计数, 谁先到达谁计数。
 */
public class SurfaceExitChain extends SingleTaskChain {

    /**
     * 见类注释。必须高于 User Tasks(50), 低于 FoodChain(55)/DeathRecovery(70)/
     * MobDefense(95)/WorldSurvival(100)/MLGBucket(100)。
     */
    private static final float ACTIVE_PRIORITY = 52.0f;

    /**
     * 单个落点的预算 (墙钟秒)。落点本身由 {@link SurfaceExitGuide} 保证是"露天干地面
     * 的强签名列", 正常都在 24~64 格内, 直接可达; 但"可站立"不等于"可达" (中间可能
     * 隔水/隔崖)。超预算即用 {@link SurfaceExitGuide#reject} 拉黑换下一个 ——
     * 否则一个不可达的落点会把本链钉满整个 90s 武装窗口。
     */
    private static final double TARGET_BUDGET_SECONDS = 20;

    private BlockPos _target = null;
    private final TimerGame _targetTimer = new TimerGame(TARGET_BUDGET_SECONDS);

    public SurfaceExitChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    public float getPriority(AltoClef mod) {
        if (!eligible(mod)) {
            releaseHold();
            return Float.NEGATIVE_INFINITY;
        }
        if (_target == null) {
            // 偏好方位角传 0: 出洞收尾不挑方向 ("往哪个方向走都行"), 角度锥会双向
            // 放宽到 180°。找不到落点 (未加载/全拒/全海洋) → 本链不接管, 让
            // 上层任务照常跑; 引导自带扩环与 90s 超时兜底。
            _target = SurfaceExitGuide.findSafeLanding(mod, 0.0);
            if (_target == null) {
                releaseHold();
                return Float.NEGATIVE_INFINITY;
            }
            _targetTimer.reset(); // (v6 教训) TimerGame 新建即到期, 必须立刻 reset
            Debug.logToFile("altoclef_diag.log", "[SURFACE-EXIT] chain taking over: walking out to "
                    + _target.toShortString() + " player=" + mod.getPlayer().getBlockPos().toShortString()
                    + " (" + SurfaceExitGuide.remaining() + " landing(s) left)");
        }
        // 幂等: _mainTask 同为该落点时 setTask 是 no-op (Task.equals → isEqual 比坐标)。
        setTask(new GetToBlockTask(_target));
        return ACTIVE_PRIORITY;
    }

    @Override
    protected void onTick(AltoClef mod) {
        super.onTick(mod);
        if (!eligible(mod)) {
            releaseHold();
            return;
        }
        if (_target != null && _targetTimer.elapsed()) {
            Debug.logToFile("altoclef_diag.log", "[SURFACE-EXIT] walk-out budget "
                    + (int) TARGET_BUDGET_SECONDS + "s exceeded for " + _target.toShortString()
                    + " -> reject & repick");
            SurfaceExitGuide.reject(_target);
            _target = null;
            setTask(null);
        }
    }

    /**
     * 走到落点 (或任务被 stop) 都会到这里。用 {@link SurfaceExitGuide#recordArrived} 的
     * 唯一口径确认计数, 没确认就拉黑换点 —— **绝不对同一个落点原地重派**,
     * 否则"已站在 GoalBlock 上但 guide 因 Y 容差/仍判定埋深而不计数"会变成
     * setTask/stop 每 tick 一轮的 churn (本仓"瞬时判据横跳"族)。
     */
    @Override
    protected void onTaskFinish(AltoClef mod) {
        if (_target == null) return;
        if (SurfaceExitGuide.recordArrived(mod, _target)) {
            Debug.logToFile("altoclef_diag.log", "[SURFACE-EXIT] chain reached " + _target.toShortString()
                    + " (" + SurfaceExitGuide.remaining() + " landing(s) left)");
        } else {
            SurfaceExitGuide.reject(_target);
        }
        _target = null;
        setTask(null);
    }

    @Override
    public String getName() {
        return "Surface Exit Chain";
    }

    /**
     * 本链此刻是否该接管。四个前提全部满足:
     * <ol>
     *   <li>引导仍在武装期 ({@link SurfaceExitGuide#isArmed}: 维度=主世界 + 未满 3 次 + 90s 内);</li>
     *   <li>**当下确已在地表** —— 引导的配额在翻回地下时是刻意保留的
     *       ({@code onBackBelow}: "quota kept"), 没有这道闸, 刚出洞又下去的
     *       {@code MineDownExploreTask} 会被本链硬拽回地表;</li>
     *   <li>不在浅坑/峡谷里 —— 那时出洞是"垫塔/凿穿"的活, 归 {@code GoToSurfaceTask}, 走路出不去。
     *       判据复用 {@link WorldHelper#getPitEscapeY} (与 {@code GoToSurfaceTask.inPit} 同一个源);</li>
     *   <li>不在水里 —— 先游上岸/上浮, 与远征消费方的"水中不接管"同一豁免
     *       (2026-09-24 审查 Medium-5: 不绕宽水闸门)。</li>
     * </ol>
     */
    private boolean eligible(AltoClef mod) {
        if (!AltoClef.inGame() || mod.getPlayer() == null || mod.getWorld() == null) return false;
        if (!SurfaceExitGuide.isArmed(mod)) return false;
        if (SurfaceHelper.isBelowSurfaceDebounced(mod)) return false;
        if (mod.getPlayer().isTouchingWater()) return false;
        if (WorldHelper.getPitEscapeY(mod) >= 0) return false;
        return true;
    }

    /** 让出控制权 + 清本链状态 (幂等)。 */
    private void releaseHold() {
        _target = null;
        if (getCurrentTask() != null) {
            setTask(null);
        }
    }
}
