package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
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

import java.util.Optional;

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

    // (2026-09-19) 4 类信号方块是否已【静默注册】进 BlockTracker 追踪集合。
    // 这个 static 只是"注册过"的标志: 追踪集合是全局的 (缓存才按维度分开), 所以跨
    // 世界/跨存档都不需要重新注册, 也不像别的 static 状态那样要 onWorldBoundary 清理。
    private static boolean _signalsTracked = false;

    /**
     * (2026-09-19 就近原则, 用户拍板"只要看到就采取就近原则") bot 用"自己的视力"找
     * 诡异森林信号方块 —— 与"看见堡垒"完全同一条路径 (缓存查表 → FOV 锥 → LOS 射线)。
     *
     * <p><b>为什么之前是瞎的:</b> 候选来自 BlockTracker 缓存, 而缓存里只会有<b>已
     * trackBlock 的类型</b>。这 4 类信号方块此前刻意【没有】注册 —— trackBlock 首次
     * 加入新类型会 setDirty + forceElapse → {@code rescanWorld} → baritone
     * {@code MineProcess.searchWorld} 对全部已追踪方块扫三个维度的所有已加载区块;
     * 2026-09-18 22:46 正是这样把客户端 CPU 打满、被服务端超时踢掉的
     * (性能红线, 见 detail/12-perf-redline.md)。代价就是"视力恒瞎": 2026-09-19
     * 17:56 会话里 bot 站在林子边缘 (脚下不是 warped_forest 群系), 眼前就是诡异森林,
     * 却一路 {@code Get to chunk: [57,-11] → [57,-12] → …} 往远处跑 (tasks.log 18:02)。
     *
     * <p><b>现在怎么绕开这个两难:</b> 首次调用时用 {@code trackBlockQuiet} <b>静默
     * 注册</b> —— 进追踪集合, 但<b>不当场触发重扫</b>。数据由常规扫描自然填充: 近区
     * 扫描 (5Hz, 半径 16) 注册后最多 0.2s 就有近处数据, 远区扫描 (4s, 半径 128) 最多
     * 4s 覆盖到视距边缘, 周期性 rescanWorld (5s) 本就要跑、新类型搭便车一起扫。之后
     * 主线程只剩缓存查表 + 最多 {@code maxChecks} 条 LOS 射线 —— 与堡垒
     * (KnowledgeMemory 首个 tick 就 trackBlock NETHER_BRICKS) 同一架构。后台扫描用
     * HashSet 判定且所有类型共用同一遍遍历, 多这 4 类的边际成本≈0。
     *
     * @param maxRange 视力半径 (由设置 opportunisticPearlSightRange 给)
     * @param maxChecks 最多对几个最近候选做 LOS 射线 (射线最贵)
     * @return 最近的一个通过 FOV + LOS 的信号方块位置, 没有则 empty
     */
    public static Optional<BlockPos> findVisibleSignal(AltoClef mod, double maxRange, int maxChecks) {
        ensureSignalsTracked(mod);
        return mod.getBlockTracker().getNearestPerceivedInView(
                maxRange, maxChecks, WARPED_FOREST_SIGNAL_BLOCKS);
    }

    /** 首次调用时静默注册信号方块 (不触发全量重扫)。注册是全局的, 只需一次。 */
    private static void ensureSignalsTracked(AltoClef mod) {
        if (_signalsTracked) return;
        _signalsTracked = true;
        mod.getBlockTracker().trackBlockQuiet(WARPED_FOREST_SIGNAL_BLOCKS);
    }

    // 蹲守半径: 离锚点超过这个距离就先走回林子, 防止巡逻把 bot 带出林子。
    private static final double CAMP_RADIUS = 24;
    // 走回林子的"到位"判据: 进到锚点这么多格内就算回到林子, 交给巡逻接管。
    // 必须 < CAMP_RADIUS, 否则会出现"到位后仍在拉回半径外"的自相矛盾。
    private static final int RETURN_RADIUS = 8;
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

    /**
     * (2026-09-19 19:5x 横跳修复) 拉回模式的<b>闩 (latch)</b>。
     *
     * <p>旧判据是<b>单阈值瞬时</b>的: 只要"离锚点 ≤ CAMP_RADIUS 就巡逻"。现场
     * (20260919-195218) 实锤后果:
     * <pre>
     * 19:53:06.368 RETURN dist=24   ← 出界, 起拉回
     * 19:53:08.365 PATROL step-done ← 2 秒后? 因为 baritone 往回走了几格, 一进 24 就切巡逻
     * 19:53:11.465 RETURN dist=24   ← 又出界
     * ...(5 轮, 直到 60s 枯竭放弃)
     * </pre>
     * 拉回任务 (GetWithinRangeOfBlockTask, 目标是进到锚点 8 格内) <b>一次都没跑完</b> ——
     * 刚跨过 24 格边界就被换掉, 于是 bot 全程在 24 格边界反复折返, 从没真正走进林子,
     * 观感就是"离森林很远还在那打转 / 和 wander 反复抢"。
     *
     * <p>修法: 双阈值迟滞 (与项目里岩浆保险的双半径迟滞同一套路) —— 出界
     * ({@code > CAMP_RADIUS}) 上闩, 必须真正走进 {@code RETURN_RADIUS} 内才解锁。
     * 两个半径之间 (8~24) 是**保持当前模式**的死区, 不再每 tick 翻转。
     */
    private boolean _returning = false;

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
        // (2026-09-19 就近原则) 这里【不再】trackBlock(WARPED_FOREST_SIGNAL_BLOCKS)。
        // 两个理由:
        //   ① 性能红线: trackBlock 首次加入新方块类型会 setDirty + forceElapse →
        //      BlockTracker.rescanWorld → baritone MineProcess.searchWorld 对全部已追踪
        //      方块扫三个维度的所有已加载区块。2026-09-18 22:46 就是这样把客户端 CPU
        //      打满、被服务端超时踢掉的 (detail/12-perf-redline.md)。
        //   ② 它本来就漏配了 stopTracking (onResourceStop 里没有), 一旦调用就永久
        //      占用追踪名额, 会把别的方块类型挤出缓存。
        // 现在"看见林子"改由 {@link #findVisibleSignal} 按需采样 + 统一视线判据提供,
        // 不需要 BlockTracker 追踪, 蹲守也就不会再触发全量重扫。
        _campTimer = new TimerGame(mod.getModSettings().getOpportunisticPearlCampMaxSeconds());
        _dryTimer = new TimerGame(mod.getModSettings().getOpportunisticPearlDrySeconds());
        // (2026-09-19) 实例复用/二次进入时清零可变状态 (Task 框架铁律: onStart 必须
        // 把上一条命的闩/实例级状态清干净, 否则"带着上一次的拉回模式开局")。
        _returning = false;
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
        //
        //    (2026-09-19 横跳修复) 双阈值迟滞: 出界(> CAMP_RADIUS)上闩, 走到
        //    RETURN_RADIUS 内才解锁。中间 8~24 格是死区, 保持当前模式 —— 否则拉回
        //    任务刚跨过 24 就被巡逻换掉, bot 永远停在边界打转 (见 _returning 注释)。
        if (_anchor != null) {
            double distSq = mod.getPlayer().getPos().squaredDistanceTo(Vec3d.ofCenter(_anchor));
            if (distSq > CAMP_RADIUS * CAMP_RADIUS) {
                _returning = true;
            } else if (distSq <= RETURN_RADIUS * RETURN_RADIUS) {
                if (_returning) {
                    _returning = false;
                    Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tRETURN-ARRIVED dist="
                            + String.format("%.0f", Math.sqrt(distSq))
                            + " anchor=" + _anchor.toShortString()
                            + " (真正走进林心 " + RETURN_RADIUS + " 格内, 解锁回巡逻)");
                }
            }
        }
        if (_anchor != null && _returning) {
            setDebugState("Returning to warped forest camp");
            long nowMs = System.currentTimeMillis();
            if (nowMs - _lastReturnLogMs > RETURN_LOG_INTERVAL_MS) {
                _lastReturnLogMs = nowMs;
                Debug.logToFile("altoclef_diag.log", "[PEARL-OPP]\tRETURN dist="
                        + String.format("%.0f", Math.sqrt(mod.getPlayer().getPos().squaredDistanceTo(Vec3d.ofCenter(_anchor))))
                        + " anchor=" + _anchor.toShortString()
                        + " (巡逻走出了 " + (int) CAMP_RADIUS + " 格蹲守半径, 拉回中 — 闩住直到走进 "
                        + RETURN_RADIUS + " 格内)");
            }
            // 回林子用现成的 GetWithinRangeOfBlockTask (GoalNear, 进到 RETURN_RADIUS 内
            // 即算到), 而不是 GetToBlockTask (GoalBlock, 必须精确站到 _anchor.up()
            // 那一格): 锚点可能是菌菇/菌索这类非实体植被, "它上面那一格"未必站得住,
            // GoalBlock 够不着就永不 finished, 会把整段蹲守耗在绕圈上。顺带复用
            // CustomBaritoneGoalTask 的进度失败两豁免 (8d570054)。
            if (_goToAnchor == null || _goToAnchor.isFinished(mod)) {
                _goToAnchor = new GetWithinRangeOfBlockTask(_anchor, RETURN_RADIUS);
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
