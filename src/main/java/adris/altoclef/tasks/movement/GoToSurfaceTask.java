package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-08-22) 回到地表通用前置任务。
 *
 * 用户原则: 地表资源(原木/食物/羊毛等)的收集任务, 当 bot 深处地下
 * (矿洞/深水)时, 应先显式"回到地表"再收集, 而不是让收集任务硬啃
 * 跨地形长路径 — Baritone 垂直挖穿几十格岩层耗时数分钟, 期间会被
 * 收集任务的进度检查误判"不可达"而放弃 ("挖一半就不挖了")。
 *
 * 实现: 目标 Y 取 58 (isDeepUnderground 硬下限), 以 isDeepUnderground
 * 变 false (头顶无盖且高于海平面下限) 为完成判据, 而非到达某个固定 Y
 * — 挖到山腰露头也算回到地表。峡谷/深坑判定 (getPitEscapeY) 保留。
 *
 * ============================================================================
 * (2026-09-07 v3) 回地表分流 — 三次事故迭代后的最终形态, 用户要求
 * "检测海洋智能点, 一有一点水就 dig_up 不好; 挖的距离要可控, 确保挖出必有
 * 水流下; 自己静态推演所有情况"。
 *
 * 核心事实 (三次会话日志实锤 + 用户确认):
 *  1. bot 在 DIG_UP 下不会爬高 (16:12 / 21:36 两次实锤: digging up 目标一路
 *     上移而 player Y 恒定), 凿竖井够不到顶板 = 原地废。峡谷脱困"GetToYTask
 *     会搭方块上去"从未验证, 不可作设计前提。
 *  2. 逐格凿的价值【只在"凿穿即见水"】: 水流下来 → bot 入水 → GetToYTask
 *     的 GoalYLevel 触发 CustomBaritoneGoalTask 水中垂直上浮 (2026-09-04
 *     [SwimUp] 实锤有效) — 这是唯一被验证的"不搭高也能上升"手段。
 *  3. 22:04 事故: 洞穴小水池在头顶 dy≤16 就触发 DIG_UP, bot 凿到离水还差
 *     3 格够不着, 既没水下来又不会爬 → 原地卡死 + 换列重挖 + 疯狂重导航。
 *
 * 因此 DIG_UP 触发条件收紧为【凿击学上可保证】的形态:
 *    水面距脚 dy ≤ DIG_WINDOW(5) 且 从水面起连续水柱深 ≥ minWaterDepth(3)。
 *  - dy≤5: 水面在凿击窗口内 → 从下往上把窗口内顶板逐块凿穿后, 水必然落到
 *    bot 头上 (挖的距离可控, "挖出必有水流下")。
 *  - depth≥3: 只有成片的水 (池/海) 才值得凿 — 滴水/1~2 格小水洼凿穿了也
 *    游不起来 (用户"一有一点水就 dig_up 不好")。
 *  - 其余一切情况 (无水 / 水在 6~16 格 / 水太浅) → BARITONE 正常寻路;
 *    bot 被带上爬、水进入 5 格窗口后每 1s 重扫自然接管。
 *  - 挖穿进水后 (isTouchingWater) 不再凿水上方的方块, 立即交 GetToYTask(58)
 *    游泳上浮; 出水后水柱重扫判"水消失"自动回 BARITONE。
 *
 * 事故史 (防止回归):
 *  - 16:11: 对 y<58 一律逐格凿 + 每 tick 重算目标 → 三方乒乓, 没挖穿一块。
 *    修: 目标粘性 + 只在可保证见水时凿。
 *  - 21:36: "Baritone 失速 60s → 降级 DIG_UP"兜底 (v1) 实锤失败, 已删。
 *    干燥 stall 交给 baritone 自有 checker/wander。
 *  - 22:04: 16 格内有任意水就 DIG_UP (v2) 实锤失败 → v3 收紧为上述条件。
 */
public class GoToSurfaceTask extends Task {

    private enum AscentMode {
        /** 头顶无"凿穿必见水"形态: Baritone 正常寻路 (默认)。 */
        BARITONE,
        /** 头顶 dy≤5 有深≥3 的水柱: 逐格向上凿, 挖穿必有水流下。 */
        DIG_UP
    }

    /** 凿击窗口: 与 tickDigUp 的 dy 扫描上限一致。水面必须在此窗口内。 */
    private static final int DIG_WINDOW = 5;

    private AscentMode _mode = AscentMode.BARITONE;

    // 头顶水检测每 1s 重扫一次 (列扫描最多 16 格, 开销可忽略)。
    private final TimerGame _waterScanTimer = new TimerGame(1);
    // "看到水但不满足凿击条件"的诊断日志节流 (防刷屏)。
    private final TimerGame _waterDiagLogTimer = new TimerGame(5);

    // 逐格凿粘性目标 (DIG_UP 模式)。
    private BlockPos _digTarget = null;
    // 挖掘诊断日志节流 (旧版每 tick 一行, 16:11 事故打了 930 行)。
    private final TimerGame _digLogTimer = new TimerGame(1);

    // 峡谷脱困目标 Y (getPitEscapeY): 壁顶+1。搭到壁顶后水平走出去。
    // 每 1 秒重算一次 (getPitEscapeY 扫 960 个方块, 不能每 tick 调)。
    private Integer _pitEscapeY = null;
    private final TimerGame _pitEscapeTimer = new TimerGame(1);

    // (2026-08-27) 表面目标高度 63→58, 与 isDeepUnderground 的硬下限一致:
    // 挖到 58 露头 (头顶不再被盖) 即视为回到表面即可。
    private static final int SURFACE_TARGET_Y = 58;

    private boolean inPit(AltoClef mod) {
        if (_pitEscapeY == null || _pitEscapeTimer.elapsed()) {
            _pitEscapeY = WorldHelper.getPitEscapeY(mod);
            _pitEscapeTimer.reset();
        }
        return _pitEscapeY >= 0;
    }

    /**
     * (2026-09-07 v3) 头顶列水况实测。
     *
     * @return {firstWaterDy, waterDepth}: firstWaterDy = 第一个水格相对脚的
     *         距离 (1..range), waterDepth = 从该格起【连续】水格数; 无水返回
     *         {-1, 0}。未加载区块视为边界 (按无水处理)。
     *         只认 WATER/FLOWING_WATER (岩浆不算 — 凿穿岩浆天花板会死)。
     */
    private int[] scanWaterOverhead(AltoClef mod, BlockPos player) {
        int range = mod.getModSettings().getGoToSurfaceWaterDetectRange();
        if (range <= 0) return new int[]{-1, 0};
        int firstDy = -1;
        int depth = 0;
        for (int dy = 1; dy <= range; dy++) {
            BlockPos pos = player.up(dy);
            if (!mod.getChunkTracker().isChunkLoaded(pos)) break;
            FluidState fluid = mod.getWorld().getBlockState(pos).getFluidState();
            boolean water = fluid != null && !fluid.isEmpty()
                    && (fluid.getFluid() == Fluids.WATER || fluid.getFluid() == Fluids.FLOWING_WATER);
            if (!water) {
                if (firstDy > 0) break; // 连续水柱统计结束
                continue;
            }
            if (firstDy < 0) firstDy = dy;
            depth++;
        }
        return new int[]{firstDy, depth};
    }

    @Override
    protected Task onTick(AltoClef mod) {
        boolean deep = WorldHelper.isDeepUnderground(mod);
        if (!deep) {
            // 峡谷/深坑判定 (仅地表资源前置场景使用本任务)。
            if (!inPit(mod)) {
                return null; // 真正的地表, 等待 isFinished 结束
            }
            BlockPos player = mod.getPlayer().getBlockPos();
            if (player.getY() >= _pitEscapeY) {
                // 已到壁顶: 高墙不再围住我们, 结束 (水平走交给后续任务)。
                _pitEscapeY = null;
                return null;
            }
            setDebugState("Towering up out of a pit (target y=" + _pitEscapeY + ")");
            return new GetToYTask(_pitEscapeY);
        }
        _pitEscapeY = null;
        BlockPos player = mod.getPlayer().getBlockPos();

        // 每 1s 实测头顶水况, 双向驱动模式。
        // DIG_UP 只在"凿穿必见水"形态下进入: 水面 dy≤DIG_WINDOW 且水深≥min。
        if (_waterScanTimer.elapsed()) {
            _waterScanTimer.reset();
            int[] w = scanWaterOverhead(mod, player);
            int firstDy = w[0], depth = w[1];
            int minDepth = mod.getModSettings().getGoToSurfaceMinWaterDepth();
            boolean digEligible = firstDy > 0 && firstDy <= DIG_WINDOW && depth >= minDepth;
            if (digEligible && _mode != AscentMode.DIG_UP) {
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] diggable water overhead (dy="
                        + firstDy + " depth=" + depth + ") at " + player + " -> DIG_UP");
                _mode = AscentMode.DIG_UP;
            } else if (!digEligible && _mode == AscentMode.DIG_UP) {
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] water gone/unsuitable at "
                        + player + " -> back to BARITONE");
                _mode = AscentMode.BARITONE;
                _digTarget = null;
            } else if (firstDy > 0 && !digEligible && _waterDiagLogTimer.elapsed()) {
                // 有水但不值得凿 (太远/太浅): 节流记录, 留诊断证据。
                _waterDiagLogTimer.reset();
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] water overhead but NOT diggable (dy="
                        + firstDy + " depth=" + depth + ", need dy<=" + DIG_WINDOW
                        + " depth>=" + minDepth + ") at " + player + " -> stay BARITONE");
            }
        }

        if (_mode == AscentMode.DIG_UP) {
            return tickDigUp(mod, player);
        }

        // ===== BARITONE 模式 (默认): 无"凿穿必见水"形态, 正常寻路上去 =====
        setDebugState("Going up to surface via baritone (target y=" + SURFACE_TARGET_Y + ")");
        return new GetToYTask(SURFACE_TARGET_Y);
    }

    /**
     * (2026-09-07 v3) 逐格向上凿 (仅"水面 dy≤5 且水柱深≥min"时进入)。
     *
     * 关键保证: 水面在凿击窗口内 → 窗口内顶板全部凿穿后水必然落到 bot 头上
     * ("挖出必有水流下"), bot 入水即走游泳上浮 (唯一被验证的不搭高上升手段)。
     *
     * 入水后不再凿水上方的方块 — 那是 22:04 卡死的另一种形态 (凿穿池底后
     * 继续够池顶)。目标粘性: 仍实心且未越过就保持, 不随 bot 微动换列。
     */
    private Task tickDigUp(AltoClef mod, BlockPos player) {
        // 挖穿进水 → 停凿, 交 GetToYTask(58): 水中由 CustomBaritoneGoalTask
        // 垂直上浮接管 ([SwimUp] 实锤), 浅水/出水后 baritone 正常寻路。
        if (mod.getPlayer().isTouchingWater()) {
            _digTarget = null;
            setDebugState("In water after breach, swimming up");
            return new GetToYTask(SURFACE_TARGET_Y);
        }
        // 粘性: 目标仍实心且未越过 → 保持, 不随 bot 微动换列重置挖掘进度。
        if (_digTarget != null) {
            if (!WorldHelper.isSolid(mod, _digTarget) || player.getY() >= _digTarget.getY()) {
                _digTarget = null; // 已挖穿 / 已爬到该高度
            }
        }
        if (_digTarget == null) {
            // 从脚+1 起 (越过身体格) 向上找第一块实心: 可挖则为目标,
            // 不可挖 (基岩等) 停止扫描。窗口内全空 (顶板已凿穿但水未落) →
            // 交 GetToYTask 兜底 (baritone 处理流动水/浅水)。
            for (int dy = 1; dy <= DIG_WINDOW; dy++) {
                BlockPos cand = player.up(dy);
                if (WorldHelper.isSolid(mod, cand)) {
                    float h = mod.getWorld().getBlockState(cand).getHardness(mod.getWorld(), cand);
                    if (h >= 0 && !mod.getExtraBaritoneSettings().shouldAvoidBreaking(cand)) {
                        _digTarget = cand;
                    }
                    break; // 遇到实心 (可挖或不可挖/基岩) 就停
                }
            }
        }
        if (_digTarget != null) {
            if (_digLogTimer.elapsed()) {
                _digLogTimer.reset();
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] digging up at "
                        + _digTarget + " player=" + player);
            }
            setDebugState("Digging upward to water");
            return new DestroyBlockTask(_digTarget);
        }
        setDebugState("Ceiling breached, waiting water / going to y=" + SURFACE_TARGET_Y);
        return new GetToYTask(SURFACE_TARGET_Y);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (WorldHelper.isDeepUnderground(mod)) return false;
        return !inPit(mod);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _pitEscapeY = null;
        _mode = AscentMode.BARITONE;
        _waterScanTimer.reset();
        _digTarget = null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _digTarget = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GoToSurfaceTask;
    }

    @Override
    protected String toDebugString() {
        return "Returning to surface";
    }
}
