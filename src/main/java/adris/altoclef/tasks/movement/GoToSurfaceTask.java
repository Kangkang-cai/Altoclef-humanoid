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
 * 跨地形长路径。完成判据是 isDeepUnderground 变 false (头顶无盖且高于
 * 海平面下限), 而非到达某个固定 Y。峡谷/深坑判定 (getPitEscapeY) 保留。
 *
 * ============================================================================
 * (2026-09-07 v4) 回地表分流 — 四次事故迭代后的最终形态。
 * 用户定调 (2026-09-07 22:43): "之前加这个进水机制是因为头顶是一片海洋;
 * 为了不透视原则, bot 的策略是能不进水就不进水, 由 baritone 自己逐步导航
 * 上去; 但当 baritone 找不到导航路线 (上次遇到海洋就是这样) 时, 希望它缩小
 * 导航长度, 几格几格逐步导航到头, 而不是最后到海洋前十几格傻等; 实在导航
 * 找不到落脚点时才采用 dig_up, 自然就游泳上去。如果不是海洋在头顶, baritone
 * 怎么会找不到路呢?"
 *
 * 三条设计原则:
 *  1. 【默认不进水】水面/水体本身【不再】触发凿击。只要 baritone 找得到路,
 *     无论头顶是干燥岩层还是洞穴小水池, 一律 baritone 正常导航 (拟人 + 反
 *     透视: 能不进水就不进水)。
 *  2. 【逐步缩短导航长度】检测到 baritone 找不到路线 (hasPath()==false 持续
 *     goToSurfaceNoPathSeconds) 时, 不傻等也不放弃, 而是把目标从 y=58 缩成
 *     "当前 +16 → +8 → +4 → +2" 的短段, 一段一段贴上去 (海底场景里这十几
 *     格是"找不到落脚点"的高发区, 短段导航让 bot 一格格挪到头顶顶板上)。
 *  3. 【找不到落脚点才进水凿穿】最短段 (2 格) 仍然长时间无路 → 判定"确实
 *     找不到落脚点"。此时【且仅当】头顶是可安全进入的水体 (连续水柱 ≥min
 *     深度, 且水柱上方有空气 — 封闭水洞会淹死) 才切 DIG_UP: 逐块凿穿顶板
 *     → 水落到头上 → 入水 → GetToYTask 触发 CustomBaritoneGoalTask 水中垂直
 *     上浮 ([SwimUp] 2026-09-04 实锤, 唯一被验证的"不搭高也能上升"手段)。
 *
 * 事故史 (防止回归, 每次改动都要回头看):
 *  - 16:11 对 y<58 一律逐格凿 + 每 tick 重算目标 → 三方乒乓, 一块没挖穿。
 *    → 修: 目标粘性 + 只在可保证见水时凿。
 *  - 21:36 "Baritone 失速 60s → 降级 DIG_UP"兜底实锤失败 (干燥处凿了也不爬,
 *    digging up -22→-18 而 player 恒 -23)。→ 修: 删除该兜底, 干燥 stall 交
 *    baritone 自有 checker/wander。
 *  - 22:04 头顶 16 格内有任意水就 DIG_UP: 洞穴小水池触发, 凿到离水差 3 格
 *    够不着 (水不会流下) → 原地卡死 + 换列重挖 + 疯狂重导航。→ v3 加严,
 *    v4 干脆改成"水不触发, 只有 baritone 找不到路才触发"。
 *  - 未验证机制 (峡谷脱困"GetToYTask 会搭方块上去") 不得作为设计前提。
 */
public class GoToSurfaceTask extends Task {

    private enum AscentMode {
        /** 默认: baritone 分段导航, 不进水。 */
        BARITONE,
        /** baritone 最短段仍找不到落脚点 + 头顶可安全进水: 凿穿游泳上去。 */
        DIG_UP
    }

    /** 凿击窗口: 与 tickDigUp 的 dy 扫描上限一致。 */
    private static final int DIG_WINDOW = 5;

    /** 导航分段阶梯 (格): 找不到路时逐级缩短, 让 bot 几格几格贴上去。 */
    private static final int[] STEP_LADDER = {16, 8, 4, 2};

    private AscentMode _mode = AscentMode.BARITONE;

    // ===== BARITONE 分段导航状态 =====
    private int _stepIdx = 0;                 // STEP_LADDER 指针
    private int _stepTargetY = Integer.MIN_VALUE;
    private TimerGame _noPathTimer = null;    // baritone 无路径持续时间

    // 头顶水检测每 1s 重扫一次 (仅在需要判定"能否安全进水"时用)。
    private final TimerGame _waterScanTimer = new TimerGame(1);
    // "找不到落脚点/不可进水"的节流诊断 (防刷屏)。
    private final TimerGame _blockedLogTimer = new TimerGame(5);

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
     * (2026-09-07 v4) 头顶列是否是【可安全进入】的水体。
     *
     * @return {firstWaterDy, depth, hasAirAbove}: firstWaterDy = 第一个水格相对
     *         脚的距离 (1..range, 无水 -1); depth = 从该格起连续水格数;
     *         hasAirAbove = 水柱上方是否出现空气 (封闭水洞会淹死, 不可进)。
     *         只认 WATER/FLOWING_WATER (岩浆不算)。未加载区块视为边界 (按无水)。
     */
    private int[] scanWaterOverhead(AltoClef mod, BlockPos player) {
        int range = mod.getModSettings().getGoToSurfaceWaterDetectRange();
        if (range <= 0) return new int[]{-1, 0, 0};
        int firstDy = -1;
        int depth = 0;
        int hasAir = 0;
        for (int dy = 1; dy <= range; dy++) {
            BlockPos pos = player.up(dy);
            if (!mod.getChunkTracker().isChunkLoaded(pos)) break;
            FluidState fluid = mod.getWorld().getBlockState(pos).getFluidState();
            boolean water = fluid != null && !fluid.isEmpty()
                    && (fluid.getFluid() == Fluids.WATER || fluid.getFluid() == Fluids.FLOWING_WATER);
            if (water) {
                if (firstDy < 0) firstDy = dy;
                depth++;
                continue;
            }
            if (firstDy > 0) {
                // 水柱结束: 上方是空气 (有呼吸空间) 还是实心 (会淹死)?
                hasAir = WorldHelper.isSolid(mod, pos) ? 0 : 1;
                break;
            }
        }
        return new int[]{firstDy, depth, hasAir};
    }

    /** baritone 当前是否真的有路 (有正在执行的路径)。 */
    private boolean baritoneHasPath(AltoClef mod) {
        return mod.getClientBaritone().getPathingBehavior().hasPath();
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

        if (_mode == AscentMode.DIG_UP) {
            // 水消失/条件不再满足 → 回 baritone (防一次误判锁死)。
            if (_waterScanTimer.elapsed()) {
                _waterScanTimer.reset();
                if (!canEnterWater(mod, player)) {
                    Debug.logToFile("altoclef_diag.log", "[GoToSurface] water no longer usable at "
                            + player + " -> back to BARITONE");
                    _mode = AscentMode.BARITONE;
                    _digTarget = null;
                    return baritoneStep(mod, player);
                }
            }
            return tickDigUp(mod, player);
        }

        return baritoneStep(mod, player);
    }

    /**
     * (2026-09-07 v4 核心) baritone 分段导航: 目标 y = min(58, 当前 + 段长)。
     * 找不到路 (hasPath()==false 持续 N 秒) → 段长逐级缩短 (16→8→4→2), 让 bot
     * 几格几格贴到头顶顶板; 最短段仍然无路 = 确实找不到落脚点 → 若头顶可安全
     * 进水则切 DIG_UP, 否则继续等 baritone (干燥处凿了也不会爬, 旧事故教训)。
     */
    private Task baritoneStep(AltoClef mod, BlockPos player) {
        int y = player.getY();
        int step = STEP_LADDER[_stepIdx];
        // 到达本段目标 (或首次) → 下一段目标。
        if (_stepTargetY == Integer.MIN_VALUE || y >= _stepTargetY) {
            _stepTargetY = Math.min(SURFACE_TARGET_Y, y + step);
        }

        if (_noPathTimer == null) {
            int secs = mod.getModSettings().getGoToSurfaceNoPathSeconds();
            _noPathTimer = new TimerGame(secs > 0 ? secs : 1);
        }
        if (baritoneHasPath(mod)) {
            _noPathTimer.reset();
        } else if (_noPathTimer.elapsed()) {
            _noPathTimer.reset();
            if (_stepIdx < STEP_LADDER.length - 1) {
                // 缩小导航长度重试 (海底前十几格别傻等, 一段段贴上去)。
                _stepIdx++;
                _stepTargetY = Math.min(SURFACE_TARGET_Y, y + STEP_LADDER[_stepIdx]);
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] no baritone path, shrinking step to "
                        + STEP_LADDER[_stepIdx] + " at " + player);
            } else {
                // 最短段仍无路 = 找不到落脚点。
                int[] w = scanWaterOverhead(mod, player);
                boolean canEnter = canEnterWater(mod, player);
                if (canEnter) {
                    Debug.logToFile("altoclef_diag.log", "[GoToSurface] no foothold (shortest step) at "
                            + player + " and water overhead (dy=" + w[0] + " depth=" + w[1]
                            + ") -> DIG_UP");
                    _mode = AscentMode.DIG_UP;
                    return tickDigUp(mod, player);
                }
                if (_blockedLogTimer.elapsed()) {
                    _blockedLogTimer.reset();
                    Debug.logToFile("altoclef_diag.log", "[GoToSurface] no baritone foothold at " + player
                            + " but water not usable (dy=" + w[0] + " depth=" + w[1] + " airAbove=" + w[2]
                            + ") -> keep waiting on baritone");
                }
            }
        }
        setDebugState("Going up via baritone (step " + STEP_LADDER[_stepIdx] + ", target y=" + _stepTargetY + ")");
        return new GetToYTask(_stepTargetY);
    }

    /**
     * 头顶是否是"凿穿后能安全进水再浮上去"的水体: 有水、水柱够深 (≥
     * goToSurfaceMinWaterDepth, 滴水/小水洼不值得)、且水柱上方有空气
     * (封闭水洞凿进去会淹死)。水面必须在凿击窗口 (dy≤DIG_WINDOW) 内 —
     * "挖出必有水流下": 窗口内顶板全部凿穿后水必然落到 bot 头上。
     */
    private boolean canEnterWater(AltoClef mod, BlockPos player) {
        int[] w = scanWaterOverhead(mod, player);
        int minDepth = mod.getModSettings().getGoToSurfaceMinWaterDepth();
        return w[0] > 0 && w[0] <= DIG_WINDOW && w[1] >= minDepth && w[2] == 1;
    }

    /**
     * (2026-09-07 v4) 逐格向上凿 (仅在"baritone 找不到落脚点 + 头顶可安全进水"
     * 时进入)。入水即停凿交 GetToYTask 游泳上浮, 不凿水上方的方块。
     * 目标粘性: 仍实心且未越过就保持, 不随 bot 微动换列重置挖掘进度。
     */
    private Task tickDigUp(AltoClef mod, BlockPos player) {
        if (mod.getPlayer().isTouchingWater()) {
            _digTarget = null;
            setDebugState("In water after breach, swimming up");
            return new GetToYTask(SURFACE_TARGET_Y);
        }
        if (_digTarget != null) {
            if (!WorldHelper.isSolid(mod, _digTarget) || player.getY() >= _digTarget.getY()) {
                _digTarget = null; // 已挖穿 / 已爬到该高度
            }
        }
        if (_digTarget == null) {
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
        _stepIdx = 0;
        _stepTargetY = Integer.MIN_VALUE;
        _noPathTimer = null;
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
