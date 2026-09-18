package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-18 顺手收珍珠) 下界诡异森林蹲守打末影人。
 *
 * <p><b>为什么单独做一个任务而不是复用 {@code KillAndLootTask}:</b> 末影珍珠任务
 * 原本只在"烈焰棒 / 防火药水"两件主线事【都做完之后】才启动
 * ({@code BeatMinecraft2Task} 下界分支末尾)。但珍珠不需要任何前置 —— 它不像
 * 进要塞打烈焰人那样必须等防火药水齐备, 任何时候路过诡异森林都能顺手刷。
 * 用户拍板: 执行另外两个任务(7 根烈焰棒 / 2 瓶防火药水)期间, 只要【亲眼看见】
 * 诡异森林, 就驻留下来把珍珠刷够, 但烈焰棒的优先级始终比珍珠高一点。
 *
 * <p>{@code KillAndLootTask} 在"没有末影人"时走 {@code BiasedExploreTask} 到处游荡
 * (偏向记忆里的动物位置), 会直接走出林子、把主线拖走 —— 那不是"顺手"。本任务
 * 改为<b>锚点驻留</b>: 以一块看得见的林子信号方块为锚点, 离锚点太远就走回去,
 * 在锚点附近做小步巡逻(TimeoutWanderTask, 每次 10 格就重新起算, 由锚点半径拉回),
 * 感知到末影人才开打。这样"顺手"的边界是明确的: 不会为了珍珠走到地平线以外。
 *
 * <h3>两条放弃线(防止蹲守吃掉主线)</h3>
 * <ol>
 *   <li><b>总时长上限</b> {@code opportunisticPearlCampMaxSeconds} —— 蹲守再香也不能
 *       无限刷, 到点交还主线。</li>
 *   <li><b>枯竭线</b> {@code opportunisticPearlDrySeconds} —— 这么久一只末影人都没
 *       感知到说明这片林子没货, 立刻放弃并进入冷却(由上层起冷却)。</li>
 * </ol>
 * 放弃后 {@link #hasGivenUp()} 为真 —— 本任务<b>不会</b>自己结束 (ResourceTask 的
 * isFinished 只看物品目标), 必须由上层读这个标记把它换掉, 否则会原地空转。
 */
public class CollectEnderPearlsInForestTask extends ResourceTask {

    /** 诡异森林的"林子信号"方块: 地表菌岩 + 菌类/菌索/疣块 (看见任一块即算看见林子)。 */
    public static final Block[] WARPED_FOREST_SIGNAL_BLOCKS = new Block[]{
            Blocks.WARPED_NYLIUM,
            Blocks.WARPED_FUNGUS,
            Blocks.WARPED_ROOTS,
            Blocks.WARPED_WART_BLOCK
    };

    // 蹲守半径: 离锚点超过这个距离就先走回林子, 防止巡逻把 bot 带出林子。
    private static final double CAMP_RADIUS = 24;
    // 单步巡逻距离: TimeoutWanderTask 走够这个距离即结束, 由本任务重新起算。
    private static final float PATROL_STEP = 10;

    private final int _count;
    private BlockPos _anchor;

    private final Task _killTask = new KillEntitiesTask(EndermanEntity.class);
    private Task _goToAnchor = null;
    private TimeoutWanderTask _patrol = null;

    private TimerGame _campTimer = null;
    private TimerGame _dryTimer = null;

    private boolean _gaveUp = false;
    private String _giveUpReason = null;

    // 巡逻可观测性: 上次见到末影人的时刻 (墙钟), 以及回锚点/心跳两条日志的节流戳。
    private long _lastEndermanSeenMs = 0;
    private long _lastReturnLogMs = 0;
    private long _lastPatrolBeatMs = 0;
    private static final long RETURN_LOG_INTERVAL_MS = 5000;
    private static final long PATROL_BEAT_INTERVAL_MS = 30000;

    public CollectEnderPearlsInForestTask(int count, BlockPos anchor) {
        super(new ItemTarget(Items.ENDER_PEARL, count));
        _count = count;
        _anchor = anchor;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(WARPED_FOREST_SIGNAL_BLOCKS);
        _campTimer = new TimerGame(mod.getModSettings().getOpportunisticPearlCampMaxSeconds());
        _dryTimer = new TimerGame(mod.getModSettings().getOpportunisticPearlDrySeconds());
        _lastEndermanSeenMs = System.currentTimeMillis();
        Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tSTART anchor="
                + (_anchor == null ? "none" : _anchor.toShortString())
                + " pearls=" + mod.getItemStorage().getItemCount(Items.ENDER_PEARL) + "/" + _count
                + " campMax=" + (int) mod.getModSettings().getOpportunisticPearlCampMaxSeconds()
                + "s dry=" + (int) mod.getModSettings().getOpportunisticPearlDrySeconds() + "s");
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (_gaveUp) {
            // 上层还没来得及换掉我们 (同一 tick 内), 原地不动即可。
            setDebugState("Gave up pearl camp (" + _giveUpReason + "), yielding to main line");
            return null;
        }

        // 1) 总时长上限
        if (_campTimer != null && _campTimer.elapsed()) {
            giveUp(mod, "camp-timeout");
            return null;
        }

        // 2) 有末影人就打 (KillEntitiesTask 内部含末影人激怒/瞄准的专门处理)
        if (mod.getEntityTracker().entityFound(EndermanEntity.class)) {
            _dryTimer.reset();
            _lastEndermanSeenMs = System.currentTimeMillis();
            setDebugState("Killing enderman for pearls ("
                    + mod.getItemStorage().getItemCount(Items.ENDER_PEARL) + "/" + _count + ")");
            return _killTask;
        }

        // 3) 枯竭线: 这么久一只都没见到 → 这片林子没货
        if (_dryTimer != null && _dryTimer.elapsed()) {
            giveUp(mod, "no-enderman-seen");
            return null;
        }

        // 4) 没末影人: 回到锚点 / 在林子里小步巡逻等刷新
        if (_anchor != null
                && mod.getPlayer().getPos().squaredDistanceTo(Vec3d.ofCenter(_anchor)) > CAMP_RADIUS * CAMP_RADIUS) {
            setDebugState("Returning to warped forest camp");
            long nowMs = System.currentTimeMillis();
            if (nowMs - _lastReturnLogMs > RETURN_LOG_INTERVAL_MS) {
                _lastReturnLogMs = nowMs;
                Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tRETURN dist="
                        + String.format("%.0f", Math.sqrt(mod.getPlayer().getPos().squaredDistanceTo(Vec3d.ofCenter(_anchor))))
                        + " anchor=" + _anchor.toShortString() + " (巡逻走出了 " + (int) CAMP_RADIUS + " 格蹲守半径, 拉回)");
            }
            if (_goToAnchor == null || _goToAnchor.isFinished(mod)) {
                _goToAnchor = new GetToBlockTask(_anchor.up(), false);
            }
            return _goToAnchor;
        }

        // 林内巡逻: TimeoutWanderTask 每段走 PATROL_STEP 格换一次方向, 是【无偏置随机
        // 游荡】—— 刻意没用 BiasedExploreTask (步长 48 且会朝记忆目标连走多步, 本
        // 项目已有"偏置探索折返、净位移≈0"的前科, 见 detail/04-symptoms.md)。末影人
        // 在诡异森林刷新率本来就高, 靠小步游荡扫视野足够, 且永远被锚点半径兜住。
        setDebugState("Patrolling warped forest for endermen ("
                + mod.getItemStorage().getItemCount(Items.ENDER_PEARL) + "/" + _count + ")");
        if (_patrol == null) {
            _patrol = new TimeoutWanderTask(PATROL_STEP);
            Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tPATROL begin anchor="
                    + (_anchor == null ? "none" : _anchor.toShortString())
                    + " radius=" + (int) CAMP_RADIUS + " step=" + (int) PATROL_STEP);
        } else if (_patrol.isFinished(mod)) {
            // 实例级任务: 不 reset 的话原点停在上一次的位置, 一开局就"走够了"空转。
            _patrol.resetForReuse(mod);
            Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tPATROL step-done pearls="
                    + mod.getItemStorage().getItemCount(Items.ENDER_PEARL) + "/" + _count + " (换方向继续找)");
        }
        // 心跳: 证明是在"等刷新 / 找末影人"而不是卡死原地 (30s 一条)。
        long beatNow = System.currentTimeMillis();
        if (beatNow - _lastPatrolBeatMs > PATROL_BEAT_INTERVAL_MS) {
            _lastPatrolBeatMs = beatNow;
            Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tPATROL waiting noEndermanFor="
                    + ((beatNow - _lastEndermanSeenMs) / 1000) + "s pearls="
                    + mod.getItemStorage().getItemCount(Items.ENDER_PEARL) + "/" + _count
                    + " dryLimit=" + (int) mod.getModSettings().getOpportunisticPearlDrySeconds() + "s");
        }
        return _patrol;
    }

    private void giveUp(AltoClef mod, String reason) {
        _gaveUp = true;
        _giveUpReason = reason;
        Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tGIVEUP reason=" + reason
                + " pearls=" + mod.getItemStorage().getItemCount(Items.ENDER_PEARL) + "/" + _count
                + " anchor=" + (_anchor == null ? "none" : _anchor.toShortString()));
    }

    /** 蹲守已放弃 (枯竭/超时)。上层必须据此把本任务换掉 —— 本任务不会自己结束。 */
    public boolean hasGivenUp() {
        return _gaveUp;
    }

    public String getGiveUpReason() {
        return _giveUpReason;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tSTOP pearls="
                + mod.getItemStorage().getItemCount(Items.ENDER_PEARL) + "/" + _count
                + " gaveUp=" + _gaveUp + (_giveUpReason == null ? "" : "(" + _giveUpReason + ")")
                + " interruptedBy=" + (interruptTask == null ? "none" : interruptTask.toString()));
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // 掉在地上的珍珠要捡 —— 这是本任务的唯一目标物品。
        return false;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectEnderPearlsInForestTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Camp warped forest for " + _count + " ender pearls";
    }
}
