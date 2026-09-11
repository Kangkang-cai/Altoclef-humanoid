package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.PillarJumpHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.item.Item;
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
 * (2026-09-11 v5) 挖一垫一 —— 取代 v4 的 DIG_UP。
 *
 * v4 的失败教训 (会话 20260911-151743 实锤):
 *  - DIG_UP 是唯一"凿穿后游泳上去"的入口, 触发条件是"阶梯 16→8→4→2 走完
 *    仍无路"。但这要 20s 且期间不能被抢占 —— MLG 等反射链每次抢占都会
 *    重跑 onStart 把 _stepIdx 归零, 13 分钟会话里只有 2 次 shrinking step
 *    to 8, DIG_UP **一次都没进去过** (纯死代码)。
 *  - 就算进去了, v4 假设"凿穿顶板 → 水必然流下 → 入水游泳上去"。用户实测
 *    反例: 头顶是"大片沙砾 + 水"时凿穿了水流不下来, bot 干站在洞里,
 *    [SwimUp] 上浮被撞头, 于是一次次超时重来。
 *
 * v5 用户拍板方案 (两条腿, 不依赖任何"水会自己流下来"的假设):
 *   ① **头顶挖一格**: 头顶有可挖实心就凿掉, 给自己开上升通道。
 *   ② **脚下垫一格**: 头顶通了就直接 pillar-jump 把自己垫高 1 格
 *      (PillarJumpHelper, 与 TowerEscapeTask 共用实现)。
 *   循环 ①② 净上升 1 格。只要上方有水, 垫上去**必然**接触水
 *   ("头上挖一格脚下搭高一格, 这样子就一定会入水, 这是肯定的" —— 用户)。
 *   入水后交 GetToYTask, 由 CustomBaritoneGoalTask 的 [SwimUp] 上浮。
 *
 * 触发入口 (两处, 都要求"确实卡住"而不是"看到水就凿" —— 防 09-07 22:04
 * 洞穴小水池事故复发):
 *   - **卡住检测** (新, 主动): baritone 自称有路径/正在寻路, 但玩家 XZ
 *     连续 4s 位移 < 0.05 格 → 判定"有路走不动", 立即进挖垫模式。
 *     (不再等阶梯耗尽, 从根上绕开"抢占重置 _stepIdx"的死循环。)
 *   - **阶梯耗尽** (旧入口, 保留): 最短段(2格)仍无 baritone 路。
 *
 * 退出闸门 (必须有, 否则死循环):
 *   - 无进展: 连续 15s 玩家 Y 没涨 → 放弃。
 *   - 总超时: 90s → 放弃。
 *   - 放弃 → **触发条件层 static 冷却 60s** (实例层冷却会被任务重建绕过,
 *     20de6b0c 教训), 期间不再进挖垫模式, 回 baritone 普通导航。
 *
 * 事故史 (防止回归, 每次改动都要回头看):
 *  - 16:11 对 y<58 一律逐格凿 + 每 tick 重算目标 → 三方乒乓, 一块没挖穿。
 *    → 修: 目标粘性 + 只在可保证见水时凿。
 *  - 21:36 "Baritone 失速 60s → 降级 DIG_UP"兜底实锤失败 (干燥处凿了也不爬,
 *    digging up -22→-18 而 player 恒 -23)。→ 现在不成立: v5 凿完会**垫**,
 *    干燥处也能逐格爬, 不再依赖"凿了就会爬"。
 *  - 22:04 头顶 16 格内有任意水就 DIG_UP: 洞穴小水池触发, 凿到离水差 3 格
 *    够不着 (水不会流下) → 原地卡死 + 换列重挖 + 疯狂重导航。→ v5 闸门:
 *    必须"确实卡住"+"头顶有可挖实心"才进, 且带无进展/总超时退出。
 *  - 2026-09-11 DIG_UP 死代码 (见上)。
 */
public class GoToSurfaceTask extends Task {

    private enum AscentMode {
        /** 默认: baritone 分段导航, 不挖不垫。 */
        BARITONE,
        /** 卡住 / 无路: 头顶挖一格 + 脚下垫一格, 逐格爬上去。 */
        SCAFFOLD_UP
    }

    /** 头顶向上找"可挖实心"的扫描上限 (玩家身体占 2 格, 所以从 dy=1 扫安全)。 */
    private static final int DIG_WINDOW = 4;

    /** 导航分段阶梯 (格): 找不到路时逐级缩短, 让 bot 几格几格贴上去。 */
    private static final int[] STEP_LADDER = {16, 8, 4, 2};

    // ===== 卡住检测 (2026-09-11) =====
    /** baritone 自称有路却连续这么久没位移 → 判定卡住, 直接进挖垫模式。
     *  (2026-09-11 用户: 4s 太长, 改 2s。baritone 自己跳几次就判失败转 wander,
     *   等 4s 时它已经把"放弃"流程走完一轮了。) */
    private static final int STUCK_TICKS_TO_SCAFFOLD = 20 * 2;
    /** 挖垫模式连续这么久 Y 没涨 → 放弃。 */
    private static final int SCAFFOLD_NO_PROGRESS_TICKS = 20 * 15;
    /** 挖垫模式总时长上限。 */
    private static final int SCAFFOLD_TOTAL_TIMEOUT_TICKS = 20 * 90;
    /** 放弃后的冷却 (触发条件层 static: 任务实例会被重建, 实例层冷却无效)。 */
    private static final int SCAFFOLD_GIVEUP_COOLDOWN_TICKS = 20 * 60;
    private static long _scaffoldGiveUpUntilTick = 0;

    private AscentMode _mode = AscentMode.BARITONE;

    // ===== BARITONE 分段导航状态 =====
    private int _stepIdx = 0;                 // STEP_LADDER 指针
    private int _stepTargetY = Integer.MIN_VALUE;
    private TimerGame _noPathTimer = null;    // baritone 无路径持续时间
    // "找不到落脚点/不可挖垫"的节流诊断 (防刷屏)。
    private final TimerGame _blockedLogTimer = new TimerGame(5);

    // ===== SCAFFOLD_UP 状态 =====
    private long _scaffoldStartTick = -1;
    private int _scaffoldLastY = Integer.MIN_VALUE;
    private TimerGame _scaffoldNoProgressTimer = null;
    private final TimerGame _scaffoldLogTimer = new TimerGame(2);

    // ===== 卡住检测状态 (两种模式共用, 每 tick 采样) =====
    private double _lastSampleX = Double.NaN;
    private double _lastSampleZ = Double.NaN;
    private int _stuckTicks = 0;

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

    /** baritone 当前是否真的有路 (有正在执行的路径)。 */
    private boolean baritoneHasPath(AltoClef mod) {
        try {
            return mod.getClientBaritone().getPathingBehavior().hasPath();
        } catch (Throwable t) {
            return false;
        }
    }

    /** baritone 是否自称正在寻路 (与 hasPath 互补: 有 state 无 path 也算"自称能动")。 */
    private boolean pathingActive(AltoClef mod) {
        try {
            return mod.getClientBaritone().getPathingBehavior().isPathing();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 每 tick 采样 XZ 位移: 没动就累计, 动了就清零。 */
    private void sampleMovement(AltoClef mod) {
        double x = mod.getPlayer().getX();
        double z = mod.getPlayer().getZ();
        if (Double.isNaN(_lastSampleX)) {
            _lastSampleX = x;
            _lastSampleZ = z;
            return;
        }
        double moved = Math.hypot(x - _lastSampleX, z - _lastSampleZ);
        if (moved < 0.05) {
            _stuckTicks++;
        } else {
            _stuckTicks = 0;
        }
        _lastSampleX = x;
        _lastSampleZ = z;
    }

    /**
     * 头顶向上找第一个"可挖的实心方块"。
     * 第一个实心若不可挖 (基岩/放置保护) → 返回 null (上面更没戏, 不继续找)。
     */
    private BlockPos findOverheadDigTarget(AltoClef mod, BlockPos player) {
        for (int dy = 1; dy <= DIG_WINDOW; dy++) {
            BlockPos cand = player.up(dy);
            if (!mod.getChunkTracker().isChunkLoaded(cand)) return null;
            if (!WorldHelper.isSolid(mod, cand)) continue;
            if (mod.getExtraBaritoneSettings().shouldAvoidBreaking(cand)) return null;
            float hardness = mod.getWorld().getBlockState(cand).getHardness(mod.getWorld(), cand);
            if (hardness < 0) return null; // 不可破坏
            return cand;
        }
        return null;
    }

    /**
     * 能不能切"挖一垫一"模式。
     *
     * 闸门 (每条都对应一次事故, 别削):
     *  - 放弃冷却未到期 → 不进 (防"放弃→立刻重进"死循环)。
     *  - 头顶 DIG_WINDOW 内必须存在【可挖实心】—— 有东西可凿才进。
     *    纯水柱/纯空气不触发 (交给 baritone 或游泳), 这是 09-07 22:04
     *    "洞穴小水池误触发原地卡死"的直接防线。
     */
    private boolean canScaffold(AltoClef mod, BlockPos player) {
        if (WorldHelper.getTicks() < _scaffoldGiveUpUntilTick) return false;
        return findOverheadDigTarget(mod, player) != null;
    }

    private void enterScaffold(AltoClef mod, BlockPos player) {
        _mode = AscentMode.SCAFFOLD_UP;
        _scaffoldStartTick = WorldHelper.getTicks();
        _scaffoldLastY = player.getY();
        if (_scaffoldNoProgressTimer == null) {
            _scaffoldNoProgressTimer = new TimerGame(SCAFFOLD_NO_PROGRESS_TICKS / 20.0);
        }
        _scaffoldNoProgressTimer.reset();
        _scaffoldLogTimer.reset();
        _stuckTicks = 0;
    }

    /** 放弃挖垫模式: 记录触发条件层 static 冷却, 回 baritone。 */
    private Task giveUpScaffold(AltoClef mod, BlockPos player, String reason) {
        Debug.logToFile("altoclef_diag.log", "[GoToSurface] SCAFFOLD give up (" + reason + ") at " + player
                + ", cooldown " + (SCAFFOLD_GIVEUP_COOLDOWN_TICKS / 20) + "s");
        _scaffoldGiveUpUntilTick = WorldHelper.getTicks() + SCAFFOLD_GIVEUP_COOLDOWN_TICKS;
        _mode = AscentMode.BARITONE;
        _stepIdx = 0;
        _stepTargetY = Integer.MIN_VALUE;
        _noPathTimer = null;
        _scaffoldStartTick = -1;
        PillarJumpHelper.release(mod);
        return baritoneStep(mod, player);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        sampleMovement(mod);
        boolean deep = WorldHelper.isDeepUnderground(mod);
        if (!deep) {
            if (_mode == AscentMode.SCAFFOLD_UP) {
                PillarJumpHelper.release(mod);
                _mode = AscentMode.BARITONE;
                _scaffoldStartTick = -1;
            }
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

        if (_mode == AscentMode.SCAFFOLD_UP) {
            return tickScaffoldUp(mod, player);
        }
        return baritoneStep(mod, player);
    }

    /**
     * baritone 分段导航: 目标 y = min(58, 当前 + 段长)。
     * 找不到路 (hasPath()==false 持续 N 秒) → 段长逐级缩短 (16→8→4→2)。
     * 另外新增**卡住检测**: baritone 自称有路但人不动 → 立即进挖垫模式。
     */
    private Task baritoneStep(AltoClef mod, BlockPos player) {
        int y = player.getY();
        int step = STEP_LADDER[_stepIdx];
        // 到达本段目标 (或首次) → 下一段目标。
        if (_stepTargetY == Integer.MIN_VALUE || y >= _stepTargetY) {
            _stepTargetY = Math.min(SURFACE_TARGET_Y, y + step);
        }

        // (2026-09-11) 卡住 → 直接进"挖一垫一", 不再等阶梯耗尽。
        // 旧入口要 20s 且不能被打断, 而每次抢占都会重跑 onStart 把 _stepIdx
        // 归零 → 永远到不了最短段 (20260911-151743 实锤)。这里用"人不动但
        // baritone 自称有路"直接判定, 与阶梯进度解耦。
        if (_stuckTicks >= STUCK_TICKS_TO_SCAFFOLD
                && (baritoneHasPath(mod) || pathingActive(mod))
                && canScaffold(mod, player)) {
            Debug.logToFile("altoclef_diag.log", "[GoToSurface] stuck " + (_stuckTicks / 20.0)
                    + "s while baritone claims a path at " + player + " -> SCAFFOLD_UP");
            enterScaffold(mod, player);
            return tickScaffoldUp(mod, player);
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
            } else if (canScaffold(mod, player)) {
                // 最短段仍无路 → 挖一垫一。
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] no foothold (shortest step) at "
                        + player + " -> SCAFFOLD_UP");
                enterScaffold(mod, player);
                return tickScaffoldUp(mod, player);
            } else if (_blockedLogTimer.elapsed()) {
                _blockedLogTimer.reset();
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] no baritone foothold at " + player
                        + " and nothing diggable overhead -> keep waiting on baritone");
            }
        }
        setDebugState("Going up via baritone (step " + STEP_LADDER[_stepIdx] + ", target y=" + _stepTargetY + ")");
        return new GetToYTask(_stepTargetY);
    }

    /**
     * (2026-09-11 v5 核心) 头顶挖一格 + 脚下垫一格, 逐格爬上去。
     *
     * 顺序不能颠倒:
     *   1. 先凿穿头顶实心 (在水里也一样 —— 用户场景就是"泡在水下但头顶被
     *      沙砾封死", 此时 [SwimUp] 撞头, 必须先开通道)。
     *   2. 头顶通了且已入水 → 交 GetToYTask 游泳上浮。
     *   3. 头顶通了但还没水 → 垫塔把自己送上去 (这一步是"不靠水流下来"的关键)。
     *   4. 没得挖也没材料垫 → 放弃。
     */
    private Task tickScaffoldUp(AltoClef mod, BlockPos player) {
        if (_scaffoldStartTick < 0) _scaffoldStartTick = WorldHelper.getTicks();
        if (WorldHelper.getTicks() - _scaffoldStartTick > SCAFFOLD_TOTAL_TIMEOUT_TICKS) {
            return giveUpScaffold(mod, player, "total timeout " + (SCAFFOLD_TOTAL_TIMEOUT_TICKS / 20) + "s");
        }
        if (_scaffoldNoProgressTimer == null) {
            _scaffoldNoProgressTimer = new TimerGame(SCAFFOLD_NO_PROGRESS_TICKS / 20.0);
        }
        if (player.getY() > _scaffoldLastY) {
            _scaffoldLastY = player.getY();
            _scaffoldNoProgressTimer.reset();
        } else if (_scaffoldNoProgressTimer.elapsed()) {
            return giveUpScaffold(mod, player, "no Y progress for " + (SCAFFOLD_NO_PROGRESS_TICKS / 20) + "s");
        }

        // 1) 头顶有可挖实心 → 凿穿 (给自己开上升通道)。
        BlockPos dig = findOverheadDigTarget(mod, player);
        if (dig != null) {
            PillarJumpHelper.release(mod);
            if (_scaffoldLogTimer.elapsed()) {
                _scaffoldLogTimer.reset();
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] SCAFFOLD digging up at " + dig
                        + " player=" + player);
            }
            setDebugState("Scaffold: digging up at " + dig);
            return new DestroyBlockTask(dig);
        }

        // 2) 头顶已通 + 已入水 → 交 GetToYTask (CustomBaritoneGoalTask 的 [SwimUp] 接管)。
        if (mod.getPlayer().isTouchingWater()) {
            PillarJumpHelper.release(mod);
            setDebugState("Scaffold: breached into water, swimming up");
            return new GetToYTask(SURFACE_TARGET_Y);
        }

        // 3) 头顶已通但还没水 → 垫塔送进上方空间。
        Item[] materials = PillarJumpHelper.allMaterials();
        if (PillarJumpHelper.hasMaterials(mod, materials)) {
            if (_scaffoldLogTimer.elapsed()) {
                _scaffoldLogTimer.reset();
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] SCAFFOLD pillaring up at " + player);
            }
            setDebugState("Scaffold: pillaring up");
            PillarJumpHelper.step(mod, materials);
            return null;
        }

        // 4) 没得挖也没材料垫 → 放弃 (回 baritone + 进冷却)。
        return giveUpScaffold(mod, player, "nothing diggable overhead and no pillar materials");
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
        _scaffoldStartTick = -1;
        _scaffoldLastY = Integer.MIN_VALUE;
        _scaffoldNoProgressTimer = null;
        _scaffoldLogTimer.reset();
        _stuckTicks = 0;
        _lastSampleX = Double.NaN;
        _lastSampleZ = Double.NaN;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 垫塔期间 hold 的跳跃键必须在所有退出路径上松开 (含被反射链抢占)。
        PillarJumpHelper.release(mod);
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
