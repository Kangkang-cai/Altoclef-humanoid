package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.bridge.FollowRelation;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.resources.CollectVisibleOresTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * (2026-10-06 多 bot 随行, 用户拍板) <b>跟随宿主</b>: 默认贴 [leader] 3 格跟随,
 * 顺手时"受控偏离"去干掉就近的正事, 干完立刻回来接着跟。
 *
 * <h3>语义（用户 2026-10-06 18:4x 明确）</h3>
 * <ul>
 *   <li><b>默认态 = 跟随</b>: 永远贴 3 格, 是一条非常明显的尾巴。没有正事可干时
 *       一直停在这里 —— <b>不存在"半径内自由活动"</b>。</li>
 *   <li><b>偏离态 = 干正事</b>: 只有"就近真有一件事值得干"才离开, 偏离期间不限制
 *       工作距离（用户口径"正常去干"）, 但受 32 格防跟丢线兜底。</li>
 *   <li><b>尽量跟随</b>: 正事扫描以 leader 为中心, 且必须落在 {@link #WORK_SCAN_RANGE}
 *       内; 单次正事最多 {@link #WORK_MAX_MS}。</li>
 * </ul>
 *
 * <h3>为什么跟随内核一行不改</h3>
 * {@link FollowPlayerTask} 单参构造的默认距离本来就是 3 格, 环形站定 + 0.5 格滞回
 * 也已经实现（2026-10-05）。本类只负责"在跟随外层加一层受控偏离", 不谈距离语义。
 *
 * <h3>⚠️ 出口必须写在调用侧</h3>
 * {@code Task.tickInternal} <b>不消费</b>子任务的 {@code isFinished}（替换权在父链,
 * 见 Task.java 的 tickInternal）。所以本类每拍主动做两件事:
 * <ol>
 *   <li>轮询 {@code _work.isFinished(mod)} —— 正事自己不会结束, 得由这里收;</li>
 *   <li>轮询 {@code _follow.stopped()} —— {@link FollowPlayerTask} 的终态（不在服务器 /
 *       跨维度 / 走到最后已知位置仍找不到人）是"自己 stop 自己", 这里把它翻译成本宿主
 *       的 {@link #isFinished}。否则 FollowPlayerTask 每拍重启并每拍 logWarning 刷屏。</li>
 * </ol>
 *
 * <h3>不做的事</h3>
 * 不改 {@code @follow} 的旧语义（{@code @follow <名字>} 仍是纯跟随）; 不新增 bot↔bot
 * 通信; 不碰 BeatMinecraft2Task 的调度。
 */
public class FollowAndWorkTask extends Task {

    /** 跟随保持距离: 用户 2026-10-06 点名"跟随时直接就是 3 格"。 */
    private static final double FOLLOW_DISTANCE = 3;

    /**
     * 偏离上限 (格): 距 leader 超过此值立刻放弃正事回跟随。
     * 依据: 客户端只下发 ~48 格内的玩家实体 (entity-tracking-range.players),
     * 32 格留 16 格余量, 保证偏离期间不会把 leader 跟丢。
     */
    private static final double STRAY_LIMIT = 32;

    /** 正事扫描半径 (格), 以 leader 为中心。必须 < STRAY_LIMIT, 否则"选了必然放弃的目标"。(用户 18:4x 拍板 32 格上限) */
    private static final double WORK_SCAN_RANGE = 24;

    /** 单次正事硬上限 (用户 2026-10-06 拍板 30s)。 */
    private static final long WORK_MAX_MS = 30_000;

    /**
     * 单次正事最短时长。框架的子任务切换去抖是 2s (Task.SWITCH_DEBOUNCE_MS):
     * 若偏离短于 2s 就退回跟随, 而"跟随"正是上一次被 stop 的子任务, 会被去抖
     * 判定为"来回横跳"而拒绝切换 —— 结果是偏离与跟随互相卡住。故设 2s 地板。
     */
    private static final long WORK_MIN_MS = 2_000;

    /** 正事结束回跟随后冷却, 防"回来→立刻又挑中同一个目标→再跑掉"。 */
    private static final long WORK_COOLDOWN_MS = 3_000;

    /** 正事探测间隔。容器扫描是 BlockTracker 缓存读, 便宜。 */
    private static final long PROBE_INTERVAL_MS = 1_000;

    /** 正事提供者: 返回 null = 此刻没有值得干的, 保持跟随。 */
    public interface WorkPicker {
        Task pick(AltoClef mod, PlayerEntity leader);
    }

    private final String _leaderName;
    private final WorkPicker _picker;

    /**
     * (2026-10-07 follow 关系治理) 跟随总时长上限，透传给内部 {@link FollowPlayerTask}；
     * {@code 0} = 不限时。
     *
     * <p>{@code @follow <名> work}（命令级）传 {@link FollowRelation#COMMAND_FOLLOW_MAX_MS}；
     * bootstrap 宿主内部用 {@code 0}（常驻，生命周期由宿主的跟丢/阶段判定管）。
     */
    private final long _maxDurationMs;

    private boolean _init = false;

    /** 当前正事; null = 处于跟随态。 */
    private Task _work = null;
    private long _workStartMs = 0;

    /** 跟随子任务的实例句柄, 只为观察它有没有"自我 stop"（= 终态）。切到正事时清空。 */
    private FollowPlayerTask _follow = null;

    private long _cooldownUntilMs = 0;
    private long _lastProbeMs = 0;

    /** FollowPlayerTask 已自我终止 → 本宿主一并结束（终态）。 */
    private boolean _terminal = false;

    public FollowAndWorkTask(String leaderName) {
        this(leaderName, null, 0);
    }

    public FollowAndWorkTask(String leaderName, WorkPicker picker) {
        this(leaderName, picker, 0);
    }

    public FollowAndWorkTask(String leaderName, WorkPicker picker, long maxDurationMs) {
        _leaderName = leaderName;
        _picker = picker;
        _maxDurationMs = maxDurationMs;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // ⚠️ 只在首次进入初始化。被战斗链 interrupt 后 onStart 会二次进入
        // (Task.interrupt 置 _first=true), 此时清状态会把干到一半的正事丢掉。
        if (!_init) {
            _init = true;
            _work = null;
            _follow = null;
            _cooldownUntilMs = 0;
            _lastProbeMs = 0;
            _terminal = false;
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_terminal || mod.getPlayer() == null) return null;

        Optional<PlayerEntity> leader = mod.getEntityTracker().getPlayerEntity(_leaderName);

        // ---------- 偏离态 ----------
        if (_work != null) {
            String abort = workAbortReason(mod, leader);
            if (abort != null) {
                Debug.logMessage("[FOLLOW-WORK] 放弃正事 (" + abort + ") → 回跟随 " + _leaderName);
                endWork();
            } else if (_work.isFinished(mod)) {
                Debug.logMessage("[FOLLOW-WORK] 正事完成 → 回跟随 " + _leaderName);
                endWork();
            } else {
                setDebugState("跟随 \"" + _leaderName + "\" 中，顺手干活");
                return _work;
            }
        }

        // ---------- 跟随态 ----------
        // 走到这里 _work == null。FollowPlayerTask 的终态是"自己 stop 自己", 这里翻译成
        // 本宿主的完成 —— 否则它会每拍重启、每拍重复打印同一条警告。
        if (_follow != null && _follow.stopped()) {
            Debug.logMessage("[FOLLOW-WORK] 跟随目标不可达，结束随行 " + _leaderName);
            _terminal = true;
            return null;
        }
        if (_follow == null) {
            _follow = new FollowPlayerTask(_leaderName, FOLLOW_DISTANCE, _maxDurationMs, true);
        }

        // 探一下有没有顺手的正事。leader 不在加载范围时探测无意义（以 leader 为锚点）。
        long now = System.currentTimeMillis();
        if (_picker != null && leader.isPresent() && now >= _cooldownUntilMs
                && now - _lastProbeMs >= PROBE_INTERVAL_MS) {
            _lastProbeMs = now;
            Task work = _picker.pick(mod, leader.get());
            if (work != null) {
                Debug.logMessage("[FOLLOW-WORK] 顺手接下正事 → " + work);
                _work = work;
                _workStartMs = now;
                _follow = null; // 交出去的那一个会被框架 stop; 句柄留着会被误判成"终态"
                setDebugState("跟随 \"" + _leaderName + "\" 中，顺手干活");
                return _work;
            }
        }

        setDebugState("跟随 \"" + _leaderName + "\"（距 3 格）");
        return _follow;
    }

    /** 正事是否必须立刻放弃; null = 继续干。 */
    private String workAbortReason(AltoClef mod, Optional<PlayerEntity> leader) {
        long now = System.currentTimeMillis();
        long elapsed = now - _workStartMs;
        if (elapsed >= WORK_MAX_MS) return "单次超 " + (WORK_MAX_MS / 1000) + "s";
        // 地板期不判任何退出条件, 见 WORK_MIN_MS 注释。
        if (elapsed < WORK_MIN_MS) return null;
        if (leader.isEmpty()) return "目标离开加载范围";
        double dist = mod.getPlayer().getPos().distanceTo(leader.get().getPos());
        if (dist > STRAY_LIMIT) return "偏离 " + (int) dist + " 格";
        return null;
    }

    private void endWork() {
        _work = null;
        _cooldownUntilMs = System.currentTimeMillis() + WORK_COOLDOWN_MS;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 被顶掉时丢弃正事句柄; 不碰 _follow —— interrupt 路径下它还有效。
        _work = null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _terminal;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FollowAndWorkTask t && t._leaderName.equals(_leaderName);
    }

    @Override
    protected String toDebugString() {
        return "Follow + side work \"" + _leaderName + "\"";
    }

    // ===================================================================== //
    // 第一版正事清单：所有"贪心"类 + 就近容器
    // ===================================================================== //

    /**
     * (2026-10-06 用户拍板"第一版干正事接入所有贪心任务") 贪心正事选择器。
     *
     * <p>按"越顺手越优先"排序, 取第一个命中的:
     * <ol>
     *   <li><b>就近搜刮容器</b> —— CHEST/TRAPPED_CHEST/BARREL, 以 leader 为圆心
     *       {@link #WORK_SCAN_RANGE} 格内、LOS 可见、没派发过的最近一个。
     *       物品清单复用 {@link ItemHelper#lootableItemsForNearbySweep}, 与主线同一份口径。</li>
     *   <li><b>贪心挖可见矿</b> —— {@link CollectVisibleOresTask} 自带 8 格可见范围,
     *       天然"就近"; 用它的静态纯函数探针判定, 不建临时实例（建了会订阅 BlockTracker
     *       却不走 onStop, 引用计数只加不减 —— 见该类 hasAnyVisibleOreWork 注释）。</li>
     * </ol>
     *
     * <p><b>刻意不接的两个</b>:
     * <ul>
     *   <li><b>顺手收珍珠</b>（诡异森林蹲守）—— 它是"驻留刷怪"型, 最小有效时长 60s 起步,
     *       与 {@link #WORK_MAX_MS} 30s 上限和"尽量跟随"直接冲突。它属于"会合后各自蹲守"
     *       那一档, 需要单独设计。</li>
     *   <li><b>顺手拿合成台/熔炉</b> —— 主线的拾取逻辑带 16 格限制 + 90s 超时 + 失败两次
     *       冷却 3 分钟的整套状态机; 复刻一份不划算, 抽公共又超出本轮范围。</li>
     * </ul>
     *
     * <p>TODO(已知残留): 未接主线那条"8 格内有刷怪笼时不开箱"的抑制。随行时开箱抢不走
     * 挖刷怪笼的任务（那是主线的活）, 风险低于主线场景, 暂不引入。
     */
    public static class GreedySideWorkPicker implements WorkPicker {

        /**
         * 贪心挖矿探测间隔 (独立于容器探测)。{@code CollectVisibleOresTask.hasAnyVisibleOreWork}
         * 是<b>纯函数版世界扫描</b>（不走 BlockTracker 缓存, 实测口径见该类注释）, 主线
         * 那边也是 3s 一次 —— 跟随本是 1s 探一次, 直接套会把这个扫描放大三倍。故单独降频。
         */
        private static final long ORE_PROBE_INTERVAL_MS = 3_000;

        /** 派发过的容器, 永不重复（即使当时开失败）—— 与主线 _sweptContainers 同策略。 */
        private final Set<BlockPos> _sweptContainers = new HashSet<>();
        private boolean _tracked = false;
        private long _lastOreProbeMs = 0;

        @Override
        public Task pick(AltoClef mod, PlayerEntity leader) {
            if (!_tracked) {
                // 幂等; 但注意 BlockTracker 只统计"新加入的方块类型", 首次登记可能触发
                // 一次世界重扫 —— 只做一次, 之后走缓存。
                mod.getBlockTracker().trackBlock(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
                _tracked = true;
            }

            BlockPos center = leader.getBlockPos();
            BlockPos nearest = null;
            double bestSq = WORK_SCAN_RANGE * WORK_SCAN_RANGE;
            for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL)) {
                if (_sweptContainers.contains(pos)) continue;
                // 感知闸: 不许透视。与主线就近搜刮同一口径 (LOS)。
                if (!VisibilityHelper.isBlockPerceivable(mod, pos)) continue;
                double sq = pos.getSquaredDistance(center);
                if (sq < bestSq) {
                    bestSq = sq;
                    nearest = pos;
                }
            }
            if (nearest != null) {
                // 派发即拉黑: 开到一半超时/走不到也不会下次再挑同一个, 防死循环。
                _sweptContainers.add(nearest);
                return new LootContainerTask(nearest, ItemHelper.lootableItemsForNearbySweep(mod));
            }

            long now = System.currentTimeMillis();
            if (now - _lastOreProbeMs >= ORE_PROBE_INTERVAL_MS) {
                _lastOreProbeMs = now;
                if (CollectVisibleOresTask.hasAnyVisibleOreWork(mod)) {
                    return new CollectVisibleOresTask();
                }
            }
            return null;
        }
    }
}
