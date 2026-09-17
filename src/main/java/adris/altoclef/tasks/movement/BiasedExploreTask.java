package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Exploration that is not completely random.
 *
 * The bot still "explores" (it does NOT teleport to or directly pursue targets),
 * but when a recent known location of a target exists in the background cache,
 * it steers the exploration direction towards it with some probability and within
 * an angle range. This speeds up finding targets while keeping human-like behavior.
 *
 * If no bias candidates exist, it degrades to random exploration.
 *
 * <h3>(2026-09-17) 两处 churn 修复</h3>
 * 现场 20260917-120830: bot 困在 1 格宽阶梯地道, `[NAV-STUCK]` ×419 + `Explore step
 * failed` ×137 + `BiasedExplore RE-path` 每 150ms 一次 (repath# 275)。两个独立缺口:
 * <ol>
 *   <li><b>卡住没人处置</b> — 本类只 extends Task, 从不调用
 *       {@link JumpStuckResolver}(它只有 CustomBaritoneGoalTask / ExploreCaveTask
 *       两个调用方) → 原地弹跳/顶墙时不挖障、不换路、不 wander。见 {@link #onTick} 开头。</li>
 *   <li><b>拉黑被重建清空</b> — {@link #_visitedBiasTargets}/{@link #_failedTargets}/
 *       {@code _failCounter} 都是实例字段, 父任务 (MineAndCollectTask:1996) 每 5s
 *       重建全新实例 → 又选中同一根够不着的原木 → 死循环 35 分钟。
 *       修: {@link #_unreachableTargets} 跨实例 + 按方块坐标记 + 5 分钟 TTL。</li>
 * </ol>
 *
 * <h3>(2026-09-17) 偏置步真正能走到目标: 目标作废时机 + 步长截断</h3>
 * 现场 20260917-135722 (用户"偏置探索的目标会切换, 一会选前面那个目标, 一会选后面那个,
 * 从未真正做出过贡献"): 36.7s 内同一个实例走出三段 46 格的腿, 其中前两段是
 * <b>48 格出 + 46 格原路折回</b> —— 总路径约 140 格, 净位移只有 25 格。
 * 排除项 (均有日志): {@code Explore step failed} 0 次, {@code All remembered targets
 * checked} 0 次, 无 slowtick.log, {@code exploration recreated} 全场仅 2 次
 * (即实例未被重建) → 不是卡死、不是拉黑、不是重建清空, 是<b>每步换方向</b>。
 * <p>
 * 两个代码级缺口 (都与"目标距离"无关地写死了 48):
 * <ol>
 *   <li><b>耗时/作废时机错</b> — {@link #onTick} 到达的是 48 格外的【落脚点】
 *       ({@code ARRIVED_DISTANCE=3} 是相对落脚点判的), 却在这一刻把
 *       {@code _currentBiasTarget} 记进 {@link #_visitedBiasTargets}。于是
 *       <b>任何 > BIAS_TARGET_REACH 的目标都只贡献一次 48 格位移就被永久作废</b>,
 *       78~250 格的记忆目标根本不可能被抵达; 而作废后重选的"最近未访问目标"
 *       常在身后 (刚朝 A 走了 48 格, 最近的往往是出发那侧) → 掉头, 一来一回。</li>
 *   <li><b>步长不看目标距离</b> — {@code EXPLORE_STEP=48} 恒定。目标 46 格时一步
 *       跨过头 2 格, 却因"落脚点到达"被判成访问完成。</li>
 * </ol>
 * 修法 (三条, 都在本文件内):
 * <ol>
 *   <li>步长截断: {@code min(EXPLORE_STEP, 目标距离 - ARRIVED_DISTANCE)}, 下限
 *       {@link #MIN_EXPLORE_STEP} —— 目标 ≤51 格时一步正好落在目标跟前。</li>
 *   <li>多步逼近 (锁定): {@link #chooseNewTarget} 顶部若 {@code _currentBiasTarget}
 *       仍有效且跳数未超上限, 就继续朝<b>同一个</b>目标走, 不再每步重选最近的
 *       —— 这是"> 48 格的记忆目标能被多步逼近"的关键。</li>
 *   <li>作废时机: 只有玩家进到 {@link #BIAS_TARGET_REACH} 内才记 visited;
 *       追满 {@link #BIAS_MAX_HOPS_PER_TARGET} 步仍未靠近则判"走不到"作废。</li>
 * </ol>
 * 另: {@link #logBiasStep} 新增 {@code [BiasStep]} 每步决策日志 —— 本次排查时
 * <b>没有任何一行日志记录"这一步选了哪个 bias 目标、多远、第几跳"</b>, 只能靠
 * {@code [MOB-DIAG] player=} 的位置采样反推, 白花一轮。
 */
public class BiasedExploreTask extends Task implements ITaskRequiresGrounded {

    // Probability that a new exploration step is steered towards a known target.
    private static final double P_BIASED = 0.85;
    // How wide the steering cone around the exact target direction is (degrees).
    private static final double BIAS_ANGLE_DEG = 12;
    // How far each exploration step goes.
    private static final double EXPLORE_STEP = 48;
    // Max jitter applied to the persistent heading between random steps (degrees).
    private static final double RANDOM_JITTER_DEG = 25;
    // Only steer towards targets within this distance. 256 ≈ render distance:
    // structure loot is now perceivable with no distance cap (2026-08-21), so
    // far-away village chests must also be able to STEER exploration, otherwise
    // we'd sense them but never walk to them.
    private static final double BIAS_MAX_DISTANCE = 256;
    // Give up after this many failed steps.
    private static final int MAX_FAIL_COUNT = 10;
    // Distance to consider a step "arrived".
    private static final double ARRIVED_DISTANCE = 3;

    // ===== (2026-09-17 目标作废时机 + 步长截断 + 多步逼近) 见类注释第三节 =====
    // 判定"真的走到 bias 目标了"的距离。只有玩家进到这个距离内, 才把该目标记为已访问。
    // 原实现是"到达 48 格外的【落脚点】就记为已访问" → 目标只贡献一次位移就作废。
    private static final double BIAS_TARGET_REACH = 16;
    // 同一个目标最多连续追这么多步。48 * 6 = 288 > BIAS_MAX_DISTANCE(256), 够走到最远
    // 目标; 追满仍没靠近 = 这个目标实际走不到 → 作废换下一个, 防死磕。
    private static final int BIAS_MAX_HOPS_PER_TARGET = 6;
    // 步长下限: 目标只剩 9 格时不做 6 格的碎步, 至少走这么多。
    private static final double MIN_EXPLORE_STEP = 8;

    // Attempts to pick a non-water, non-failed target before counting the step as failed.
    private static final int MAX_PICK_ATTEMPTS = 12;
    // (2026-09-16 用户"收集食物时偏置探索的终点经常在水面上, bot 在水上不好获取食物")
    // 找食物探索 (foodExplore) 的落点必须是【干燥陆地】。落点在水面时沿同一方向按
    // 下面这些距离重新采样: 先更远(64/80/96 = 跨到对岸), 再更近(32/24/16 = 退回本岸)。
    // 全部落水才判该方向不可用, 换角度重试。
    private static final double[] DRY_LANDING_DISTANCES = {64, 32, 80, 24, 96, 16};

    // [PLAINS-BIAS] 找食物/找动物专用的平原偏置 (foodExplore=true 时才启用)。
    // 草原是动物/作物收获概率最高的地表 biome, 这是用户需求: 找食物/动物时
    // 探索机制内部优先 100% 导航到平原, 提高收获。挖矿/砍木等 non-food 探索
    // 一律不启用 (foodExplore=false), 绝不改跑去别的 biome。
    private static final int PLAINS_SCAN_INTERVAL_TICKS = 50; // 每 50 tick(~2.5s) 周期扫描, 足够节流
    // 已加载区块内扫描半径 (格)。略小于步长 48*? 保持远, 但小于 BIAS_MAX_DISTANCE(256) 才会被选中。
    private static final int PLAINS_SCAN_RADIUS = 160;
    // 扫描采样步长 (格): 每 8 格取一列判 biome, 平衡开销与覆盖。
    private static final int PLAINS_SCAN_STEP = 8;
    // "没检测到平原" 日志的低节流: 每 N 次空扫描才写一行, 不刷屏。
    private static final int PLAINS_EMPTY_LOG_INTERVAL = 10;

    private final List<Supplier<Collection<BlockPos>>> _biasSources;

    // [PLAINS-BIAS] 是否"找食物/找动物"的探索 (仅 KillAndLootTask 创建时为 true)。
    // true 时在已加载区块内周期性扫描平原 biome, 并作为最高优先级 bias 目标
    // 优先 100% 导航过去。挖矿/砍木等 non-food 探索保持 false, 完全不受影响。
    private final boolean _foodExplore;
    // 最近一次扫描到玩家周围已加载区块里的平原系地表点 (找食物探索专用)。
    private List<BlockPos> _plainsTargets = new ArrayList<>();
    private int _scanTicker = 0;
    private int _plainsEmptyLogCounter = 0;
    private int _lastFoundCount = -1;

    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final Set<BlockPos> _failedTargets = new HashSet<>();
    // Bias targets we already steered towards (arrived or failed to reach).
    // A human doesn't keep walking back to the same spot they already checked.
    private final Set<BlockPos> _visitedBiasTargets = new HashSet<>();

    // ===== (2026-09-17 P0 拉黑) 跨实例"够不着"的 bias 目标拉黑表 =====
    //
    // 现场 (20260917-120830 场次): bot 在 1 格宽阶梯地道里, 已知原木缓存 206 条
    // 全部不可感知 (最近的在 74m 外)。每次实例这样结束一轮:
    //   选最近的已知原木当 bias 目标 → 朝它走 48 格 → progressChecker 6s 无位移
    //   判失败 (chat 里 "Explore step failed" ×137) → 11 次后 _failCounter > 10
    //   → isFinished → 父任务 (MineAndCollectTask:1996, 5s 冷却后) 重建【全新实例】。
    // _visitedBiasTargets / _failedTargets / _failCounter 都是**实例字段**, 新实例
    // 三样全空 → 又选中同一根原木 → 死循环 (35 分钟)。
    //
    // 为什么只按【bias 目标】跨实例拉黑, 不拉黑 step 目标:
    //   - bias 目标来自 "最近已知位置", 在同一位置是**确定性重选** (每次都是那一条),
    //     正是死循环的引擎;
    //   - step 目标带 25° 随机抖动, 同一 BlockPos 几乎不会重现, 实例内的
    //     _failedTargets 已覆盖同轮重试, 跨实例拉黑反而可能压掉后来合法的探索方向。
    //
    // 为什么要 TTL (而不是永久拉黑): 同一个存档里, bot 一旦真爬回地表/绕出地道,
    // 那根"够不着"的原木就重新可达了。永久拉黑 = 目标明明在眼前却再也不去。
    // TTL 到点自动放行; 父任务重建节奏是 5s, 5 分钟足够压掉 ~60 次重建。
    //
    // key 用 BlockPos (方块坐标) —— 与调用方 (_visitedBiasTargets) 同键口径;
    // 换存档必须清 (旧存档的坐标在新世界无意义) → onWorldBoundary, 已挂 WorldStateGuard。
    private static final Map<BlockPos, Integer> _unreachableTargets = new HashMap<>();
    private static final int UNREACHABLE_TTL_TICKS = 6000; // 5 min @20tps
    private static final int UNREACHABLE_MAX = 512;        // 上限: 防无限膨胀

    private BlockPos _currentTarget;
    // The bias target the current step is steered towards (null = random step).
    // (2026-09-17) 语义收紧: 它是"我正在逼近的那个记忆目标", 会被【跨步锁定】——
    // 随机抖动的步不再把它清空 (见 chooseNewTarget 的 random 分支), 直到真的走进
    // BIAS_TARGET_REACH 或追满 BIAS_MAX_HOPS_PER_TARGET 步才换下一个。
    private BlockPos _currentBiasTarget;
    // 自上一个目标被"消费"以来, 已经朝 bias 目标走了几步 (每个 biased 步 +1)。
    // 到 BIAS_MAX_HOPS_PER_TARGET 仍未靠近 → 作废当前目标 (见 onTick 到达分支)。
    private int _biasHops;
    private Vec3d _origin;
    private int _failCounter;
    private boolean _lastWasBiased;
    // True once we steered towards at least one bias target. Used to tell
    // "no targets ever existed" (keep random-exploring) apart from "all targets
    // checked" (finish — the parent re-decides what to do next).
    private boolean _hadBiasOnce = false;
    // All bias targets have been visited: nothing left to steer towards.
    private boolean _biasExhausted = false;
    // Persistent heading (radians) so random exploration keeps going one way.
    private double _heading = Double.NaN;
    // [SlowTickDebug] counts consecutive ticks where baritone had no active path and
    // we re-issued the same unreachable goal. High number = stuck "computing, not moving".
    private int _repathCount = 0;

    @SafeVarargs
    public BiasedExploreTask(boolean foodExplore, Supplier<Collection<BlockPos>>... biasSources) {
        _foodExplore = foodExplore;
        _biasSources = Arrays.asList(biasSources);
    }

    // 默认构造: 非"找食物/找动物"探索 (挖矿/砍木等), 不启用平原偏置。
    @SafeVarargs
    public BiasedExploreTask(Supplier<Collection<BlockPos>>... biasSources) {
        this(false, biasSources);
    }

    // ===== 跨实例拉黑表操作 (static, 见字段区注释) =====

    /** 换存档复位 (WorldStateGuard): 旧存档的方块坐标在新世界无意义。 */
    public static void onWorldBoundary() {
        _unreachableTargets.clear();
    }

    /**
     * 把一个"够不着"的 bias 目标拉黑。日志标记 {@code [BiasBlacklist]} —— 验证时
     * grep 它: 每行 = 一次"本来会回到同一个目标"的死循环节拍点被拦下。
     */
    private static void markUnreachable(BlockPos target, AltoClef mod) {
        if (target == null) return;
        pruneUnreachable();
        _unreachableTargets.put(target, WorldHelper.getTicks());
        Debug.logToFile("altoclef_diag.log", "[BiasBlacklist] unreachable " + target.toShortString()
                + " (size=" + _unreachableTargets.size() + "/" + UNREACHABLE_MAX
                + ", ttl=" + UNREACHABLE_TTL_TICKS + "t)"
                + " player=" + (mod.getPlayer() != null ? mod.getPlayer().getBlockPos().toShortString() : "?"));
    }

    /** 该目标是否仍在拉黑期内。过期即顺手移除 (惰性清理)。 */
    private static boolean isUnreachable(BlockPos target) {
        if (target == null || _unreachableTargets.isEmpty()) return false;
        Integer markedAt = _unreachableTargets.get(target);
        if (markedAt == null) return false;
        if (WorldHelper.getTicks() - markedAt >= UNREACHABLE_TTL_TICKS) {
            _unreachableTargets.remove(target);
            return false;
        }
        return true;
    }

    /** 清理过期项 + 压到上限 (超限时按拉黑时刻从早到晚丢)。 */
    private static void pruneUnreachable() {
        if (_unreachableTargets.isEmpty()) return;
        int now = WorldHelper.getTicks();
        _unreachableTargets.entrySet().removeIf(e -> now - e.getValue() >= UNREACHABLE_TTL_TICKS);
        int over = _unreachableTargets.size() - UNREACHABLE_MAX;
        if (over <= 0) return;
        List<BlockPos> oldest = new ArrayList<>(_unreachableTargets.keySet());
        oldest.sort(Comparator.comparingInt(p -> _unreachableTargets.getOrDefault(p, 0)));
        for (int i = 0; i < over && i < oldest.size(); ++i) {
            _unreachableTargets.remove(oldest.get(i));
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        _origin = mod.getPlayer().getPos();
        _progressChecker.reset();
        _failCounter = 0;
        _currentTarget = null;
        _currentBiasTarget = null;
        _biasHops = 0;
        _hadBiasOnce = false;
        _biasExhausted = false;
        _heading = Double.NaN;
        _repathCount = 0;
        _plainsTargets = new ArrayList<>();
        _scanTicker = 0;
        _plainsEmptyLogCounter = 0;
        _lastFoundCount = -1;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-09-17 P0-b) 跳跃卡死「检测+处置」共享件。本任务原先直接 extends Task,
        // 【完全不调用】它 —— 而它只有两个调用方 (CustomBaritoneGoalTask / ExploreCaveTask),
        // 所以本任务里 bot 原地弹跳/顶墙时没有任何处置: 不挖障、不 forceCancel 换路、
        // 也不强制 wander, 只剩 _progressChecker 每 6s 判一次"这一步失败"然后换方向。
        // 20260917-120830 场次实锤: [NAV-STUCK] ×419 (netXZ 0.01~0.45, yRange 3~4,
        // mode=frozen) 全程伴随 "Explore step failed" ×137 —— 就是"检测有了、处置没有"。
        //
        // 与 CustomBaritoneGoalTask:160 同款前置return。处置任务返回期间【重置】进度
        // 检查器: 挖障/wander 期间 bot 本来就不产生水平位移, 不重置的话处置一结束
        // 检查器会立刻误报"这一步失败", 而这一步的失败会把正在被处置的 bias 目标
        // 拉黑 (markUnreachable) —— 处置刚生效、目标就被自己拉黑, 自相矛盾。
        Task jumpFix = JumpStuckResolver.tick(mod);
        if (jumpFix != null) {
            _progressChecker.reset();
            return jumpFix;
        }

        // All bias targets checked: nothing left to explore towards. End so the
        // parent task can re-decide (e.g. go dig instead of circling the surface).
        if (_biasExhausted) return null;

        // [PLAINS-BIAS] 仅在找食物/找动物的探索 (foodExplore=true) 上周期性扫描
        // 玩家附近已加载区块里的平原 biome, 作为最高优先级导航目标。其它探索走
        // 原逻辑, 不影响。纯增量, 不加递归、不碰 setTask/subTask/getPriority()。
        if (_foodExplore) {
            updatePlainsBias(mod);
        }

        if (_currentTarget == null || arrivedAtCurrentTarget(mod)) {
            // (2026-09-17 作废时机修正) 到的是 48 格外的【落脚点】, 不是目标点。
            // 原实现在这里无条件把 _currentBiasTarget 记为已访问 → 每个目标只贡献
            // 一次位移就永久作废 (78~250 格的记忆目标永远到不了), 且重选出的"最近
            // 未访问"常在身后 → 48 格出 + 46 格回 (现场 20260917-135722 实锤)。
            // 现在只有两种情况才换目标:
            //   ① 玩家真的进到 BIAS_TARGET_REACH 内 (走到了);
            //   ② 追满 BIAS_MAX_HOPS_PER_TARGET 步仍未靠近 (走不到, 别死磕)。
            // 其余情况保留 _currentBiasTarget, 下一步继续朝它走 (多步逼近)。
            if (_currentBiasTarget != null) {
                if (arrivedAtBiasTarget(mod)) {
                    _visitedBiasTargets.add(_currentBiasTarget);
                    logBiasStep(mod, _currentTarget, -1, -1, "bias target REACHED, next");
                    _currentBiasTarget = null;
                    _biasHops = 0;
                } else if (_biasHops >= BIAS_MAX_HOPS_PER_TARGET) {
                    _visitedBiasTargets.add(_currentBiasTarget);
                    logBiasStep(mod, _currentTarget, -1, -1, "bias target GIVE UP after hop cap");
                    _currentBiasTarget = null;
                    _biasHops = 0;
                }
            }
            // Pick a new exploration step.
            _currentTarget = chooseNewTarget(mod, mod.getPlayer().getPos());
            if (_currentTarget == null) return null;
            _progressChecker.reset();
            // [SlowTickDebug] setGoalAndPath kicks off a baritone path search. If the
            // goal is unreachable (e.g. inside a cave wall) baritone can re-path
            // forever and the bot never moves — a prime suspect for "computing but
            // not moving". Sometimes this call alone is slow.
            long tPath = System.nanoTime();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
            long pathMs = (System.nanoTime() - tPath) / 1000_000L;
            if (pathMs > 300) {
                String m = "[SlowTickDebug] BiasedExplore goal to " + _currentTarget + " setGoalAndPath returned after " + pathMs + "ms";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_slowtick.log", m);
            }
            return null;
        }

        // We're not making progress toward this step: pick a different direction.
        if (!_progressChecker.check(mod)) {
            _failCounter++;
            _failedTargets.add(_currentTarget);
            // Unreachable step: don't steer towards its bias target again either.
            // (2026-09-17) 只在【本步确实朝它走】时才拉黑 (随机抖动步失败跟它无关,
            // 不该把一个还在逼近中的目标打成够不着)。
            if (_currentBiasTarget != null && _lastWasBiased) {
                _visitedBiasTargets.add(_currentBiasTarget);
                // (2026-09-17 P0 拉黑) 本实例的下场就是被父任务丢弃重建 —— 拉黑只写在
                // 实例字段里等于没写 (新实例三样全空, 又选回同一根原木)。见字段区注释。
                markUnreachable(_currentBiasTarget, mod);
                logBiasStep(mod, _currentTarget, -1, -1, "step timed out, bias target blacklisted");
                _currentBiasTarget = null;
                _biasHops = 0;
            }
            Debug.logMessage("Explore step failed, picking a new direction.");
            _currentTarget = null;
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            // [SlowTickDebug] An unreachable goal flips baritone's process idle,
            // so this branch re-paths EVERY tick = the bot looks busy but never
            // moves. Log how often + the cost.
            long tRe = System.nanoTime();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
            long reMs = (System.nanoTime() - tRe) / 1000_000L;
            _repathCount++;
            // Logging EVERY repath: this branch only runs while baritone has no active
            // path (i.e. we're likely stalled). If the count climbs to the hundreds in
            // a second, that's the "computing but not moving" loop. Written to file so
            // it's inspectable without flooding chat.
            String m = "[SlowTickDebug] BiasedExplore RE-path to " + _currentTarget + " took " + reMs + "ms (repath#" + _repathCount + ")";
            Debug.logToFile("altoclef_slowtick.log", m);
            if (reMs > 300) Debug.logMessage(m);
        }
        setDebugState(_lastWasBiased ? "Exploring (biased toward known target)" : "Exploring (randomly)");
        return null;
    }

    private boolean arrivedAtCurrentTarget(AltoClef mod) {
        return mod.getPlayer().getBlockPos().isWithinDistance(_currentTarget, ARRIVED_DISTANCE);
    }

    /**
     * (2026-09-17) 玩家是否真的走到了 bias 目标跟前。只有它为真 (或跳数超限) 才换目标 ——
     * 原实现用的是 arrivedAtCurrentTarget (相对 48 格外的落脚点), 那是"这一步走完了",
     * 不是"目标到了"。见类注释第三节。
     */
    private boolean arrivedAtBiasTarget(AltoClef mod) {
        return _currentBiasTarget != null
                && mod.getPlayer().getBlockPos().isWithinDistance(_currentBiasTarget, BIAS_TARGET_REACH);
    }

    /**
     * [BiasStep] 偏置探索【每步决策】的零行为日志: 这一步选了哪个 bias 目标、离多远、
     * 第几跳、本步多远、落脚点在哪。
     *
     * <p>为什么加它: 现场 20260917-135722 排查"偏置探索目标来回切换"时, <b>没有任何
     * 一行日志记录"这一步选了哪个目标"</b> —— 只能拿 {@code [MOB-DIAG] player=}
     * 的位置采样去反推目标方向和步长, 白花一轮。决策点没有日志 = 没法证伪, 以后
     * 这类地方一律先落日志。
     *
     * <p>验证 grep: 每行 = 一步。正常的"多步逼近"应看到 target 保持不变而 dist 单调
     * 变小直到 hop 归零 (REACHED); 若 dist 忽大忽小且 target 反复变 = 还在折返。
     */
    private void logBiasStep(AltoClef mod, BlockPos stepPoint, double biasDist, double stepLen, String note) {
        StringBuilder sb = new StringBuilder("[BiasStep] ");
        sb.append(_lastWasBiased ? "biased" : "random");
        sb.append(" target=").append(_currentBiasTarget == null ? "none" : _currentBiasTarget.toShortString());
        sb.append(" dist=").append(biasDist >= 0 ? String.valueOf((int) Math.round(biasDist)) : "-");
        sb.append(" hop=").append(_biasHops).append("/").append(BIAS_MAX_HOPS_PER_TARGET);
        sb.append(" step=").append(stepLen < 0 ? "-" : String.valueOf((int) Math.round(stepLen)));
        sb.append(" -> ").append(stepPoint == null ? "?" : stepPoint.toShortString());
        if (mod.getPlayer() != null) sb.append(" player=").append(mod.getPlayer().getBlockPos().toShortString());
        if (note != null) sb.append(" (").append(note).append(')');
        Debug.logToFile("altoclef_diag.log", sb.toString());
    }

    private BlockPos chooseNewTarget(AltoClef mod, Vec3d pos) {
        // (2026-09-04 用户"在水里不需要什么高级导航") 玩家已在水里(海洋)时, 探索
        // 应直接游向最近陆地, 而不是拒绝所有海洋方向 → 四周全海 → "no dry target"
        // 每 5s 失败重试卡死 (日志实锤: [No-Ocean] ... refusing to swim + Explore found
        // no dry target direction)。水里直接游出海洋即可, 不需要 baritone 的
        // dry-point 高级寻路。
        boolean swimming = isPlayerInWater(mod);

        // (2026-09-17 多步逼近/锁定) 若手上还有一个"正在逼近"的目标 (仍有效、未被拉黑、
        // 跳数未超上限、仍在 BIAS_MAX_DISTANCE 内), 就继续朝【同一个】目标走, 不再每步
        // 重选一次最近目标。原实现每步都 pickBiasedTarget(最近未访问) —— 刚朝 A 走了
        // 48 格, 最近的未访问往往是身后那批 → 掉头, 目标永远走不到
        // (现场 20260917-135722 实锤: 46 格出 + 46 格原路回)。
        boolean locked = _currentBiasTarget != null
                && _biasHops < BIAS_MAX_HOPS_PER_TARGET
                && !_visitedBiasTargets.contains(_currentBiasTarget)
                && !isUnreachable(_currentBiasTarget)
                && _currentBiasTarget.isWithinDistance(pos, BIAS_MAX_DISTANCE);

        Optional<BlockPos> biased = locked ? Optional.of(_currentBiasTarget) : pickBiasedTarget(pos);
        if (biased.isEmpty() && _hadBiasOnce) {
            // We used to have bias targets but checked them all: done here.
            // End the task so the parent re-decides (dig, follow new memory, ...).
            _biasExhausted = true;
            Debug.logMessage("All remembered targets checked, biased exploration done.");
            return null;
        }
        // 本步锁定的 bias 目标离玩家的水平距离 (无偏置时为 +inf)。
        double biasDist = biased.isPresent()
                ? Math.sqrt(biased.get().getSquaredDistance(pos)) : Double.POSITIVE_INFINITY;
        for (int attempt = 0; attempt < MAX_PICK_ATTEMPTS; ++attempt) {
            double angle;
            if (swimming) {
                // 水里: 朝最近陆地游 (非海洋方向), 全海时随机方向兜底 (先动起来)。
                BlockPos land = findNearestLand(mod);
                if (land != null) {
                    angle = Math.atan2(land.getZ() - pos.z, land.getX() - pos.x)
                            + (Math.random() * 2 - 1) * Math.toRadians(BIAS_ANGLE_DEG);
                } else {
                    angle = nextRandomHeading();
                }
                _heading = angle;
                // 游泳是另一套模式 (朝最近陆地), 与记忆目标无关: 清掉逼近线程。
                _currentBiasTarget = null;
                _biasHops = 0;
                _lastWasBiased = false;
            } else if (biased.isPresent() && Math.random() < P_BIASED) {
                // Steer towards the target, with some randomness around its direction.
                double toTarget = Math.atan2(biased.get().getZ() - pos.z, biased.get().getX() - pos.x);
                double spread = Math.toRadians(BIAS_ANGLE_DEG);
                angle = toTarget + (Math.random() * 2 - 1) * spread;
                _heading = angle;
                _currentBiasTarget = biased.get();
                _hadBiasOnce = true;
                _lastWasBiased = true;
            } else {
                // Keep heading roughly the same direction instead of spinning around.
                angle = nextRandomHeading();
                // (2026-09-17) 这里原先把 _currentBiasTarget 清空 —— 一次 15% 概率的随机
                // 抖动就把"我正在逼近 A"这条线断掉, 下一步重选最近目标 → 又可能掉头。
                // 改成保留: 随机抖动只影响【这一步的方向】, 不影响"在追谁"
                // (哪一步真正朝目标走由 _lastWasBiased 单独记录)。
                _lastWasBiased = false;
            }

            // (2026-09-17 步长截断) 步长不再恒为 48: 目标比 48 格近时只走到目标跟前,
            // 免得"一步跨过头"却因为落脚点到达而把目标判成访问完成 (见类注释)。
            double stepLen = _lastWasBiased && Double.isFinite(biasDist)
                    ? Math.max(MIN_EXPLORE_STEP, Math.min(EXPLORE_STEP, biasDist - ARRIVED_DISTANCE))
                    : EXPLORE_STEP;

            // (2026-08-31 探索目的地定位到地表, 用户"explore 床/羊目标定位到地下/天上")
            // 原实现 candidate.y = 玩家当前 y: 玩家在地下洞里探索找羊时, 目标也落在
            // 地下(y 低) — baritone 朝地下走, 而羊/床都在地表永远找不到; 在高处探索时
            // 目标又落到天上。动物/床/树/村庄都是地表目标, 探索 step 的 y 应取该 xz
            // 抵达位置的地表高度, 而非玩家当前 y。
            int candX = (int) Math.floor(pos.x + Math.cos(angle) * stepLen);
            int candZ = (int) Math.floor(pos.z + Math.sin(angle) * stepLen);
            int candY = surfaceYAt(mod, candX, candZ, (int) Math.floor(pos.y));
            BlockPos candidate = new BlockPos(candX, candY, candZ);
            // Avoid retrying the exact directions we already failed on.
            // (2026-09-17) 跨实例拉黑目标同样跳过: 它可能恰好落在这个候选点上。
            if (_failedTargets.contains(candidate) || isUnreachable(candidate)) continue;
            // (2026-09-16 用户"收集食物时偏置探索的终点经常在水面上, bot 在水上不好获取
            // 食物") 找食物探索: 落点必须是干燥陆地。原逻辑对 biased (河/湖按"过河放行"
            // 处理) 与 swimming 两种情况直接放行水面落点, 于是 bot 走到水面上再开始找
            // 动物 —— 水里既没有动物/作物, 游动又慢又有溺水风险。落点是水时沿同一方向
            // 重新采样 (更远=跨到对岸, 更近=退回本岸), 仍全是水才换方向。
            // 非 foodExplore (村庄箱子等"过河取物") 保持原样放行, 不推翻既有
            // "过河可以、下海不行"的定论。
            if (_foodExplore && !isDryLanding(mod, candidate)) {
                BlockPos dry = findDryLandingAlong(mod, pos, angle);
                if (dry == null || _failedTargets.contains(dry) || isUnreachable(dry)) {
                    _failedTargets.add(candidate);
                    Debug.logToFile("altoclef_diag.log",
                            "[DRY-LANDING] food explore: no dry landing along "
                                    + (int) Math.toDegrees(angle) + " deg from " + pos
                                    + " (wet " + candidate.toShortString() + "), picking another direction");
                    continue;
                }
                Debug.logToFile("altoclef_diag.log",
                        "[DRY-LANDING] food explore: wet landing " + candidate.toShortString()
                                + " -> dry landing " + dry.toShortString());
                candidate = dry;
            }
            // Don't explore into water: swimming is slow, drowning is a risk and
            // targets (mobs, ores, ...) are rarely found in the middle of a lake.
            // EXCEPTION (2026-08-21 过河探索): when we're steering towards a
            // remembered target (e.g. village loot across a river) the step
            // candidate often lands ON the river surface — that's not "exploring
            // a lake", that's a crossing. Keep it: Baritone swims surface water
            // fine and the bias keeps pulling us to the far shore.
            // (2026-09-01 下海约束, 用户"过河可以, 下海干什么") biased 跨水放行只适用
            // 于【河/湖】(biome 非 ocean)。目标落点是【海洋】时一律拒绝 —— 海洋里
            // 既没有要找的目标(动物/作物/结构), 一路游过去还浪费时间与溺水风险
            // (用户实测: 随机探索跑进海里)。非随机(biased)也拦: 海洋不该被当成
            // "跨河目标"。
            // (2026-09-04) 玩家已在水里时放行海洋方向 (直接游出海洋), 拒绝仅对
            // 陆地上的探索生效 (不下海)。否则四周全海 → no dry target → 卡死。
            if (isTargetInWater(mod, candidate) && !_lastWasBiased && !swimming) {
                _failedTargets.add(candidate);
                continue;
            }
            if (isTargetInWater(mod, candidate) && !swimming) {
                // 目标是海洋水域 → 拒绝(下海无意义); 河/湖 → 放行过河。
                if (isTargetOcean(mod, candidate)) {
                    _failedTargets.add(candidate);
                    Debug.logMessage("[No-Ocean] target " + candidate.toShortString()
                            + " is ocean water, refusing to swim into the sea.");
                    continue;
                }
                Debug.logMessage("[River-cross] heading across water towards remembered target "
                        + _currentBiasTarget + " (swimming).");
            }
            if (swimming && isTargetOcean(mod, candidate)) {
                Debug.logMessage("[Swim-out] already in water, heading towards " + candidate.toShortString());
            }
            // (2026-09-17) 只有真的朝 bias 目标走的步才算一跳 (随机抖动步不计),
            // 跳数到 BIAS_MAX_HOPS_PER_TARGET 就作废当前目标 (见 onTick 到达分支)。
            if (_lastWasBiased) ++_biasHops;
            logBiasStep(mod, candidate, biasDist, stepLen, locked ? "locked" : null);
            return candidate;
        }
        // Every direction we tried was failed/water: count it as a failed step so
        // we eventually give up instead of spinning in place forever.
        _failCounter++;
        Debug.logMessage("Explore found no dry target direction, counting as a failed step.");
        return null;
    }

    /**
     * True if walking to {@code target} would put us in water: the topmost
     * non-air block of the target's column is water (lake/ocean surface).
     * Columns in unloaded chunks are treated as dry (we can't know yet).
     */
    private boolean isTargetInWater(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        for (int y = mod.getWorld().getHeight() - 1; y >= 0; --y) {
            BlockPos check = new BlockPos(target.getX(), y, target.getZ());
            if (!mod.getChunkTracker().isChunkLoaded(check)) return false;
            BlockState state = mod.getWorld().getBlockState(check);
            if (state.isAir()) continue;
            return state.getBlock() == Blocks.WATER;
        }
        return false;
    }

    /**
     * (2026-09-16 用户"收集食物时偏置探索的终点经常在水面上") 落点 (地表上方一格的
     * 站立点) 是否干燥 —— 站立处与脚下方块都不含水。与水面的区别: 过河时【路径】经过
     * 水没关系, 但【终点】不能是水面。区块未加载时无法判定, 沿用 isTargetInWater 的
     * "未知即放行"口径返回 true。
     */
    private boolean isDryLanding(AltoClef mod, BlockPos landing) {
        if (mod.getWorld() == null) return true;
        BlockPos ground = landing.down();
        if (!mod.getChunkTracker().isChunkLoaded(landing)
                || !mod.getChunkTracker().isChunkLoaded(ground)) return true;
        return !isWaterAt(mod, landing) && !isWaterAt(mod, ground);
    }

    /** 该位置是否是水 (含流动水/含水方块, 如海草、睡莲下的水)。 */
    private boolean isWaterAt(AltoClef mod, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        // getFluidState 覆盖 WATER / 流动水以及含水方块 (海草等); 岩浆同样非空,
        // 一并拒绝 (站在岩浆上不是落点)。
        return !state.getFluidState().isEmpty() || state.getBlock() == Blocks.WATER;
    }

    /**
     * (2026-09-16) 沿 {@code angle} 方向按 {@link #DRY_LANDING_DISTANCES} 重新采样,
     * 返回第一个干燥落点; 全是水返回 null。y 取该列地表 (surfaceYAt), 与正常 step
     * 落点同样的口径。
     */
    private BlockPos findDryLandingAlong(AltoClef mod, Vec3d pos, double angle) {
        int fallbackY = (int) Math.floor(pos.y);
        for (double dist : DRY_LANDING_DISTANCES) {
            int x = (int) Math.floor(pos.x + Math.cos(angle) * dist);
            int z = (int) Math.floor(pos.z + Math.sin(angle) * dist);
            int y = surfaceYAt(mod, x, z, fallbackY);
            BlockPos cand = new BlockPos(x, y, z);
            if (isDryLanding(mod, cand)) return cand;
        }
        return null;
    }

    /**
     * (2026-09-01 下海约束, 用户"过河可以, 下海干什么") 目标块所在的列是否是
     * 【海洋】biome (而非河/湖)。海洋里没有要找的目标(动物/作物/牧羊/结构),
     * 探索目标落到海洋时直接拒绝, 不游进大海; 河/湖(biome 非 ocean)仍可过河。
     */
    private boolean isTargetOcean(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        if (!mod.getChunkTracker().isChunkLoaded(target)) return false;
        return WorldHelper.isOcean(mod.getWorld().getBiome(target));
    }

    /**
     * (2026-09-04) 玩家是否已在水里 (游泳/泡水)。水里不需要"高级导航"——
     * 直接游出海洋即可, 若仍拒绝所有海洋方向会无路可走卡死。
     */
    private boolean isPlayerInWater(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return false;
        if (mod.getPlayer().isTouchingWater()) return true;
        BlockPos feet = mod.getPlayer().getBlockPos();
        return mod.getChunkTracker().isChunkLoaded(feet)
                && mod.getWorld().getBlockState(feet).isOf(Blocks.WATER);
    }

    /**
     * (2026-09-04) 沿 8 方向扫描, 找最近的"非海洋 biome"陆地 XZ; 全海返回 null。
     * 玩家在水里时探索目标朝此方向游, 直接离开海洋。
     */
    private BlockPos findNearestLand(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int dist = 16; dist <= 128; dist += 16) {
            for (int d = 0; d < 8; d++) {
                double ang = Math.PI / 4.0 * d;
                int x = p.getX() + (int) Math.round(Math.cos(ang) * dist);
                int z = p.getZ() + (int) Math.round(Math.sin(ang) * dist);
                BlockPos cand = new BlockPos(x, p.getY(), z);
                if (mod.getChunkTracker().isChunkLoaded(cand)
                        && !WorldHelper.isOcean(mod.getWorld().getBiome(cand))) {
                    return cand;
                }
            }
        }
        return null;
    }

    private double nextRandomHeading() {
        if (Double.isNaN(_heading)) {
            _heading = Math.random() * 2 * Math.PI;
        } else {
            double jitter = Math.toRadians(RANDOM_JITTER_DEG);
            _heading += (Math.random() * 2 - 1) * jitter;
        }
        return _heading;
    }

    /**
     * (2026-08-31) 该 xz 坐标的地表站立高度 (最高非空气方块上方一格)。列未加载时
     * 返回 fallback(玩家当前 y), 避免坏算。用于把探索目的地 y 固定在地表, 防止
     * 找床/羊/树/村庄时目标定位到地下或天上。
     */
    private int surfaceYAt(AltoClef mod, int x, int z, int fallbackY) {
        if (mod.getWorld() == null) return fallbackY;
        for (int y = mod.getWorld().getHeight() - 1; y >= 0; --y) {
            BlockPos check = new BlockPos(x, y, z);
            if (!mod.getChunkTracker().isChunkLoaded(check)) return fallbackY;
            BlockState st = mod.getWorld().getBlockState(check);
            if (st.isAir()) continue;
            return y + 1;
        }
        return fallbackY;
    }

    private Optional<BlockPos> pickBiasedTarget(Vec3d pos) {
        // [PLAINS-BIAS] 找食物/找动物探索: 只要附近已加载区块内扫到平原点,
        // 就 100% 优先导航到最近的平原点 (直奔草原再找动物/作物)。这些平原点
        // 是最高优先级, 其它 bias (动物记忆点等) 只有在没有平原点时才考虑。
        if (_foodExplore && !_plainsTargets.isEmpty()) {
            BlockPos best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (BlockPos candidate : _plainsTargets) {
                if (candidate == null) continue;
                if (_visitedBiasTargets.contains(candidate)) continue;
                if (isUnreachable(candidate)) continue;
                if (!candidate.isWithinDistance(pos, BIAS_MAX_DISTANCE)) continue;
                double d = candidate.getSquaredDistance(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = candidate;
                }
            }
            if (best != null) return Optional.of(best);
        }

        if (_biasSources.isEmpty()) return Optional.empty();
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Supplier<Collection<BlockPos>> source : _biasSources) {
            Collection<BlockPos> candidates = source == null ? null : source.get();
            if (candidates == null) continue;
            for (BlockPos candidate : candidates) {
                if (candidate == null) continue;
                // Already walked to this one — don't steer back to it forever.
                if (_visitedBiasTargets.contains(candidate)) continue;
                // (2026-09-17 P0 拉黑) 跨实例: 上一个实例判过"够不着"的目标, 本实例
                // (父任务重建出来的) 也不该再选它。TTL 到期自动放行。
                if (isUnreachable(candidate)) continue;
                if (candidate.isWithinDistance(pos, BIAS_MAX_DISTANCE)) {
                    double d = candidate.getSquaredDistance(pos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = candidate;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * [PLAINS-BIAS] (2026-08-31, 用户需求) 周期扫描玩家周围已加载区块里的
     * 平原系 biome, 收集其地表点存入 {@code _plainsTargets} 供 pickBiasedTarget
     * 100% 优先导航。为什么只在 foodExplore 上启用 (见 onTick): 只有找食物/找动物
     * 的探索才需要直奔草原提高收获; 挖矿/砍木走洞穴/森林, 不应被带偏到平原。
     *
     * 节流: 每 PLAINS_SCAN_INTERVAL_TICKS tick 扫一次; 找不到平原时按
     * PLAINS_EMPTY_LOG_INTERVAL 低节流记日志, 不刷屏。
     */
    private void updatePlainsBias(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return;
        if (++_scanTicker < PLAINS_SCAN_INTERVAL_TICKS) return;
        _scanTicker = 0;

        BlockPos player = mod.getPlayer().getBlockPos();
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -PLAINS_SCAN_RADIUS; dx <= PLAINS_SCAN_RADIUS; dx += PLAINS_SCAN_STEP) {
            for (int dz = -PLAINS_SCAN_RADIUS; dz <= PLAINS_SCAN_RADIUS; dz += PLAINS_SCAN_STEP) {
                BlockPos check = new BlockPos(player.getX() + dx, player.getY(), player.getZ() + dz);
                // 只扫已加载区块, 未加载的不算数。
                if (!mod.getChunkTracker().isChunkLoaded(check)) continue;
                // getBiome 按 xz 列返回 biome, 仿 WorldHelper.isOcean 用 matchesKey 判平原。
                if (WorldHelper.isPlains(mod.getWorld().getBiome(check))) {
                    // 目标定位到该列地表 (最高实心方块上方一格), 否则 bot 会往地下/天上走。
                    int sy = WorldHelper.getGroundHeight(mod, check.getX(), check.getZ());
                    // 只取一个代表点: 每列地表; 附近 160 格最多约 (160*2/8)^2 个点,
                    // 足够导航又不至于塞爆 visited 集合。
                    BlockPos landing = sy >= 0 ? new BlockPos(check.getX(), sy + 1, check.getZ()) : check;
                    // (2026-09-16 用户"收集食物不要在水上") 平原 biome 也可能铺在水面
                    // (河/湖上的 plains 列, 最高实心方块是水下沙/泥) → 落点在水里。
                    // 这类点不作为导航目标: 直奔它会把 bot 带到水面。
                    if (!isDryLanding(mod, landing)) continue;
                    found.add(landing);
                }
            }
        }

        _plainsTargets = found;

        if (!found.isEmpty()) {
            BlockPos nearest = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (BlockPos p : found) {
                double d = p.getSquaredDistance(mod.getPlayer().getPos());
                if (d < bestSq) { bestSq = d; nearest = p; }
            }
            _plainsEmptyLogCounter = 0;
            // 数量变化或首次发现时才记, 降低刷屏 (不只记一次, 平原点随移动变化时仍可观测);
            // 玩家pos + 最近平原点 + 数量。
            int n = found.size();
            if (_lastFoundCount != n || _lastFoundCount == -1) {
                String m = "[PLAINS-BIAS] food explore: player=" + player
                        + " nearest plains=" + nearest + " plainsPoints=" + n;
                Debug.logToFile("altoclef_diag.log", m);
            }
            _lastFoundCount = n;
        } else {
            _plainsEmptyLogCounter++;
            // 没检测到平原: 保持原探索行为 (不乱改去别的 biome), 低节流记一次。
            if (_plainsEmptyLogCounter >= PLAINS_EMPTY_LOG_INTERVAL) {
                Debug.logToFile("altoclef_diag.log",
                        "[PLAINS-BIAS] food explore: no plains nearby, player=" + player + " (keeping default behavior)");
                _plainsEmptyLogCounter = 0;
            }
            _lastFoundCount = 0;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _biasExhausted || _failCounter > MAX_FAIL_COUNT;
    }

    @Override
    protected boolean isEqual(Task other) {
        // Identity comparison. Callers that cache this task return the SAME
        // instance every tick (isEqual true -> no restart, state preserved).
        // When a caller swaps in a NEW instance after isFinished, the
        // framework must actually replace the old (dead) one — plain
        // "instanceof" equality would swallow the swap and freeze the bot.
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Biased exploration";
    }
}
