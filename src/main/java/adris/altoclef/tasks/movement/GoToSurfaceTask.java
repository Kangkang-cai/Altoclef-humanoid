package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.PillarJumpHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
 *  - 2026-09-12 (会话 20260912-121705) "sealed water 秒放弃" — v6 修复:
 *    bot 卡在自挖 1 宽水通道 (x119,y12,z57), 头顶实心, 31 分 46 秒在
 *    y=20/16/14 目标乒乓中一动不动。两个叠加根因:
 *    ① TimerGame 新建即"已到期" (_prevTime=0, 而 currentTime() 是连接
 *       ticks 累计秒数, 会话后期已是数千秒) → enterScaffold 置 null 的
 *       _waterStuckTimer 在进水第一 tick 就 elapsed → "sealed water"
 *       秒放弃 (本次 32 次), 挖垫模式在水中完全失效。修: 创建后立刻 reset。
 *    ② sealed-water 判据用 isTouchingWater (脚沾水就算), 但 v5 本来就是
 *       "泡在水里挖上去", 5s 宽限内根本出不了水。bot 站水通道 31 分钟
 *       没淹死 → 眼睛在水面之上。修: 守卫只在【眼睛在水下】(真正封闭
 *       含水层、无处呼吸) 时计时, 脚沾水头在空气的场景放行挖垫。
 *  - 2026-09-12 v7 重见天日 (会话 20260912-144157, 用户拍板 "没挖到天或
 *    连着天的海洋不算完"): isDeepUnderground 旧判据 y<58 让 y66 封闭湖底
 *    口袋里的本任务直接完工退场, 之后所有任务只寻路不挖掘 → 冻结 18+ 分钟。
 *    修: WorldHelper.isDeepUnderground 改为"头顶无天空视线" (水面也算盖);
 *    y>=58 顶板下头顶可挖时直接进挖一垫一, 阶梯目标不再向下 (58)。
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

    // ===== (2026-09-12 v8) 封闭空间做镐侧线 =====
    // 用户困境: 全封闭两格空间放不了工作台 → 没法做镐 → "没镐避让"谓词把
    // baritone 寻路和挖垫判定同时锁死 (avoidBreaking at y68 实锤)。用户拍板
    // 序列: 挖 1 格脚下腾位置 → 放工作台 → 合成石镐 → 换镐再挖穿。
    private enum PickEscapePhase {
        /** 未触发 / 不适用。 */
        NONE,
        /** 挖脚下 1 格 (豁免"没镐避让"), 掉落后腾出放台空间。 */
        DIG_FOOT,
        /** 在身旁放工作台。 */
        PLACE_TABLE,
        /** 合成石镐 (交给 TaskCatalogue 合成链)。 */
        CRAFT_PICK,
        /** 已完成或失败不再重试 (本实例生命周期)。 */
        DONE
    }

    private static final Item[] PICKAXE_ITEMS = new Item[]{
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
            Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE
    };

    /** 做镐侧线整体超时 (空手挖石 7.5s/格 + 放置 + 合成, 120s 足够, 卡死兜底)。 */
    private static final int PICK_ESCAPE_TIMEOUT_TICKS = 20 * 120;

    private PickEscapePhase _pickPhase = PickEscapePhase.NONE;
    private Task _pickSubTask = null;
    private int _pickEscapeStartTick = -1;
    /** DIG_FOOT 阶段锁定的脚下目标格: 派发后一直等它变非实心才推进 (防每 tick
     *  重选目标把"还没挖完"误判成"挖完了")。 */
    private BlockPos _footDigTarget = null;

    /** 导航分段阶梯 (格): 找不到路时逐级缩短, 让 bot 几格几格贴上去。 */
    private static final int[] STEP_LADDER = {16, 8, 4, 2};

    // ===== 卡住检测 (2026-09-11) =====
    /** baritone 自称有路却连续这么久没位移 → 判定卡住, 直接进挖垫模式。
     *  (2026-09-11 用户: 4s 太长, 改 2s。baritone 自己跳几次就判失败转 wander,
     *   等 4s 时它已经把"放弃"流程走完一轮了。) */
    private static final int STUCK_TICKS_TO_SCAFFOLD = 20 * 2;
    /** 挖垫模式连续这么久 Y 没涨 → 放弃。 */
    /** 挖垫无进展放弃阈值。(2026-09-12 v8) 15s→30s: 空手挖石 7.5s/格 (逃生
     *  豁免后没镐也挖), 挖穿一格+垫上经常 >15s, 15s 会把正常空手挖掘判成卡死。 */
    private static final int SCAFFOLD_NO_PROGRESS_TICKS = 20 * 30;
    /** 挖垫模式总时长上限。 */
    private static final int SCAFFOLD_TOTAL_TIMEOUT_TICKS = 20 * 90;
    /** (2026-09-12 重见天日) y>=58 封闭顶板下挖垫的总时长上限: 可能要空手
     *  凿穿十几格石头 (7.5s/格), 90s 不够; 无进展闸门 (15s) 负责拦真卡死。 */
    private static final int SCAFFOLD_TOTAL_TIMEOUT_TICKS_SKY = 20 * 300;
    /** 放弃后的冷却 (触发条件层 static: 任务实例会被重建, 实例层冷却无效)。 */
    private static final int SCAFFOLD_GIVEUP_COOLDOWN_TICKS = 20 * 60;
    private static long _scaffoldGiveUpUntilTick = 0;
    /** 挖穿进入水体后连续这么久仍未脱离 → 判定"封闭水体"(向上挖只会灌更多水), 放弃。
     *  5s 的取值: 现场空气从 1.00 掉到 0.30 只用 14s, 必须远早于溺水;
     *  而"挖穿海底/湖底 → 上浮出水"的正常场景几秒内就会脱离水体, 不会误伤。 */
    private static final int SEALED_WATER_TICKS = 20 * 5;

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
    /** 挖穿进水后的"封闭水体"计时 (见 SEALED_WATER_TICKS)。null = 未进过水。 */
    private TimerGame _waterStuckTimer = null;
    private final TimerGame _scaffoldLogTimer = new TimerGame(2);
    /** (2026-09-12 v6) findOverheadDigTarget 最近一次返回 null 的原因 (诊断用)。 */
    private String _noDigReason = null;

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
     * (2026-09-12 v6) 返回 null 时把原因写进 _noDigReason (4 条路径各不相同,
     * 之前一律"nothing diggable overhead"无法定位现场)。
     */
    private BlockPos findOverheadDigTarget(AltoClef mod, BlockPos player) {
        StringBuilder window = null;
        for (int dy = 1; dy <= DIG_WINDOW; dy++) {
            BlockPos cand = player.up(dy);
            if (!mod.getChunkTracker().isChunkLoaded(cand)) {
                _noDigReason = "chunk unloaded at " + cand;
                return null;
            }
            if (!WorldHelper.isSolid(mod, cand)) {
                if (window == null) window = new StringBuilder();
                window.append("dy=").append(dy).append(':')
                        .append(mod.getWorld().getBlockState(cand).getBlock()).append(' ');
                continue;
            }
            if (mod.getExtraBaritoneSettings().shouldAvoidBreaking(cand)) {
                _noDigReason = "avoidBreaking at " + cand;
                return null;
            }
            float hardness = mod.getWorld().getBlockState(cand).getHardness(mod.getWorld(), cand);
            if (hardness < 0) {
                _noDigReason = "unbreakable " + mod.getWorld().getBlockState(cand).getBlock() + " at " + cand;
                return null;
            }
            _noDigReason = null;
            return cand;
        }
        _noDigReason = "no solid within " + DIG_WINDOW + " above feet"
                + (window != null ? " (non-solid: " + window.toString().trim() + ")" : " (all unloaded/air?)");
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
        // (2026-09-12 v6) TimerGame 新建即"已到期" (_prevTime=0 vs 连接 ticks),
        // 这里必须创建 + reset, 否则进水第一 tick sealed-water 守卫就触发
        // (会话 20260912-121705 实锤: 32 次秒放弃, 挖垫模式在水中完全失效)。
        _waterStuckTimer = new TimerGame(SEALED_WATER_TICKS / 20.0);
        _waterStuckTimer.reset(); // 每次进挖垫都重新判定水体
        _scaffoldLogTimer.reset();
        _stuckTicks = 0;
    }

    /** (2026-09-12 v6) 眼睛是否在水下 (真正"无处呼吸"的封闭水体判据)。
     *  isTouchingWater 脚沾水就算, 会把"脚泡在水通道、头在空气"的场景
     *  (本可以放心挖上去) 误判成封闭含水层。 */
    private boolean eyesUnderwater(AltoClef mod) {
        Vec3d eye = mod.getPlayer().getEyePos();
        BlockPos eyeBlock = new BlockPos((int) eye.x, (int) eye.y, (int) eye.z);
        return !mod.getWorld().getBlockState(eyeBlock).getFluidState().isEmpty();
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

        // (2026-09-12 v8) 逃生挖掘豁免: 本任务在深地下执行期间, 放行 avoidBlockBreak
        // 谓词里的"没镐避让"(shouldAvoidUnminable) 那一条。必须在进 canScaffold 判定
        // 【之前】开 —— baritoneStep 的挖垫入口闸门也查 shouldAvoidBreaking, 若判定
        // 时刻豁免未开, 没镐时永远进不了挖垫 (193,68,-484 avoidBreaking 实锤)。
        // 单一出口每 tick 重算, onStop 兜底复位 —— 防止豁免残留导致平时乱挖。
        // 深地下之外 (峡谷塔逃等) 不豁免; 硬保护 (传送门/显式保护) 永远生效。
        mod.setEscapeDigOverride(deep);

        // (2026-09-12 v8 封闭空间做镐侧线): 挖 1 格脚下 → 放台 → 做石镐。
        // 完成后镐子在手, "没镐避让"自然解除, baritone 恢复寻路 + 快速挖掘。
        Task pickEscape = maybePickEscape(mod, player);
        if (pickEscape != null) return pickEscape;

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
        // (2026-09-12 重见天日) y>=58 且头顶被顶板封死 (isDeepUnderground 新判据
        // 才会让本任务在口袋里继续跑): 头顶 4 格内有可挖实心就直接进挖一垫一。
        // 旧版阶梯目标 min(58, y+step) 在口袋里等于"往下走" (y66 口袋被派
        // GetToYTask(58)), 是 20260912-144157 冻结现场的一半根因。
        boolean skyward = y >= SURFACE_TARGET_Y;
        if (skyward && canScaffold(mod, player)) {
            Debug.logToFile("altoclef_diag.log", "[GoToSurface] y=" + y
                    + " sealed under cover at " + player + " -> SCAFFOLD_UP (sky criterion)");
            enterScaffold(mod, player);
            return tickScaffoldUp(mod, player);
        }
        int step = STEP_LADDER[_stepIdx];
        // 到达本段目标 (或首次) → 下一段目标。
        // skyward 段只允许小步上探 (≤4 格): 头顶有盖但 4 格内无实心的少数场景
        // (悬空檐下), baritone 小步寻路走出盖子, 避免大目标触发搭塔。
        if (_stepTargetY == Integer.MIN_VALUE || y >= _stepTargetY) {
            _stepTargetY = skyward
                    ? Math.min(y + Math.min(step, 4), 250)
                    : Math.min(SURFACE_TARGET_Y, y + step);
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
            _noPathTimer.reset(); // (2026-09-12 v6) 新建即到期, 必须 reset (否则同 tick 直缩阶梯)
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
                        + " and nothing diggable overhead (" + _noDigReason + ") -> keep waiting on baritone");
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
    /** bot 是否持有任意镐 (含物品栏+副手+光标口径)。 */
    private boolean hasAnyPickaxe(AltoClef mod) {
        for (Item pick : PICKAXE_ITEMS) {
            if (mod.getItemStorage().getItemCount(pick) > 0) return true;
        }
        return false;
    }

    /**
     * (2026-09-12 v8) 封闭空间做镐侧线。触发条件 (全部满足才进, 只试一次):
     *  - 没有任何镐;
     *  - 圆石 >= 3 且木棍 >= 2 (石镐配方);
     *  - 包里有工作台成品 (没台不试 —— 台收不回来是合成链 A 修复管的事,
     *    现场实锤台已做好在输出槽, A 修复后能收进包)。
     * 阶段推进 DIG_FOOT → PLACE_TABLE → CRAFT_PICK → DONE。
     * 返回 null = 本 tick 不占用控制权, 走正常导航/挖垫。
     */
    private Task maybePickEscape(AltoClef mod, BlockPos player) {
        if (_pickPhase == PickEscapePhase.DONE) return null;
        if (_pickPhase == PickEscapePhase.NONE) {
            if (!shouldTryPickEscape(mod)) {
                _pickPhase = PickEscapePhase.DONE;
                return null;
            }
            _pickPhase = PickEscapePhase.DIG_FOOT;
            _pickEscapeStartTick = WorldHelper.getTicks();
            _footDigTarget = null;
            _pickSubTask = null;
            Debug.logToFile("altoclef_diag.log",
                    "[GoToSurface] pick-escape START at " + player + " (no pickaxe, materials ready)");
        }
        if (WorldHelper.getTicks() - _pickEscapeStartTick > PICK_ESCAPE_TIMEOUT_TICKS) {
            Debug.logToFile("altoclef_diag.log",
                    "[GoToSurface] pick-escape TIMEOUT at " + player + ", phase=" + _pickPhase);
            finishPickEscape(mod);
            return null;
        }
        switch (_pickPhase) {
            case DIG_FOOT: {
                // 只挖 1 格 (用户: "不能让他挖几十个石头, 一两个就够了")。
                // 目标格锁定: 挖穿 (变非实心, 玩家掉入) 才推进, 不每 tick 重选。
                if (_footDigTarget != null && !WorldHelper.isSolid(mod, _footDigTarget)) {
                    _footDigTarget = null;
                    _pickPhase = PickEscapePhase.PLACE_TABLE;
                    _pickSubTask = null;
                    return null;
                }
                if (_footDigTarget == null) {
                    _footDigTarget = findFootDigTarget(mod, player);
                    if (_footDigTarget == null) {
                        // 脚下没实心可挖 (悬空/基岩) → 直接试放台。
                        _pickPhase = PickEscapePhase.PLACE_TABLE;
                        _pickSubTask = null;
                        return null;
                    }
                    Debug.logToFile("altoclef_diag.log",
                            "[GoToSurface] pick-escape digging foot at " + _footDigTarget);
                }
                setDebugState("Escape: digging foot space for crafting table");
                return new DestroyBlockTask(_footDigTarget);
            }
            case PLACE_TABLE: {
                if (mod.getBlockTracker().anyFound(Blocks.CRAFTING_TABLE)) {
                    _pickPhase = PickEscapePhase.CRAFT_PICK;
                    _pickSubTask = null;
                    return null;
                }
                // 实例缓存续租 (框架铁律: 每 tick 原样返回同一实例直到完成)。
                if (_pickSubTask == null || _pickSubTask.isFinished(mod)) {
                    _pickSubTask = new PlaceBlockNearbyTask(Blocks.CRAFTING_TABLE);
                }
                setDebugState("Escape: placing crafting table");
                return _pickSubTask;
            }
            case CRAFT_PICK: {
                if (hasAnyPickaxe(mod)) {
                    Debug.logToFile("altoclef_diag.log",
                            "[GoToSurface] pick-escape SUCCESS at " + player + " (pickaxe in hand)");
                    finishPickEscape(mod);
                    return null;
                }
                if (_pickSubTask == null || _pickSubTask.isFinished(mod)) {
                    _pickSubTask = TaskCatalogue.getItemTask("stone_pickaxe", 1);
                }
                setDebugState("Escape: crafting stone pickaxe");
                return _pickSubTask;
            }
            default:
                return null;
        }
    }

    private boolean shouldTryPickEscape(AltoClef mod) {
        if (hasAnyPickaxe(mod)) return false;
        if (mod.getItemStorage().getItemCount(Items.COBBLESTONE) < 3) return false;
        if (mod.getItemStorage().getItemCount(Items.STICK) < 2) return false;
        // 只在"台已在包里"时试 (现场实锤台在输出槽, A 修复后可收)。
        return mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) >= 1;
    }

    private void finishPickEscape(AltoClef mod) {
        _pickPhase = PickEscapePhase.DONE;
        _pickSubTask = null;
    }

    /**
     * 脚下可挖目标 (挖 1 格腾放置空间)。只挑"挖掉后掉落安全"的:
     * 下下方必须也是实心 (不掉进更深空洞), hardness>=0 (基岩除外)。
     * "没镐避让"谓词在逃生豁免下已放行, 这里不再查 shouldAvoidBreaking。
     */
    private BlockPos findFootDigTarget(AltoClef mod, BlockPos player) {
        BlockPos below = player.down();
        if (!WorldHelper.isSolid(mod, below)) return null;
        if (!WorldHelper.isSolid(mod, below.down())) return null;
        float hardness = mod.getWorld().getBlockState(below).getHardness(mod.getWorld(), below);
        if (hardness < 0) return null;
        return below;
    }

    private Task tickScaffoldUp(AltoClef mod, BlockPos player) {
        if (_scaffoldStartTick < 0) _scaffoldStartTick = WorldHelper.getTicks();
        int totalTicks = player.getY() >= SURFACE_TARGET_Y
                ? SCAFFOLD_TOTAL_TIMEOUT_TICKS_SKY : SCAFFOLD_TOTAL_TIMEOUT_TICKS;
        if (WorldHelper.getTicks() - _scaffoldStartTick > totalTicks) {
            return giveUpScaffold(mod, player, "total timeout " + (totalTicks / 20) + "s");
        }

        // (2026-09-11 16:40 崩溃现场) 封闭水体判定 —— 必须在"挖头顶"之前拦住。
        // 现场: 挖穿含水层 y=40 后水灌进来, 头顶 1~3 格变水(挖穿的口子), 第 4 格
        // 仍是石头 → findOverheadDigTarget 接着挖 y=45 → 水又灌一格 → 玩家被顶上去
        // (39→41→43)。空气 14 秒耗尽(1.00→0.30)淹死, 而"无进展"闸门因为 Y 一直在涨
        // 永不触发, 90s 总超时远来不及。
        // 核心事实: **封闭含水层里向上挖是无效的** —— 挖一格水灌一格, Y 涨但永远
        // 见不到空气。所以判据只能是"泡在水里够久还没出去" → 立即停止一切挖掘
        // (停止后水稳定, 玩家自然下沉回自己挖上来的通道), 放弃并进冷却。
        // 之后的逃生交给 WorldSurvivalChain 的防溺水 (priority 95): 它会找 16 格内
        // 最近可呼吸方块 —— 在封闭水体里那就是自己刚挖上来的那条通道。
        //
        // (2026-09-12 v6 修正) 判据 isTouchingWater → eyesUnderwater:
        // 脚沾水就算的旧口径把"脚泡在自挖水通道、头在空气"的场景 (本次
        // 20260912-121705 卡死现场, bot 站 31 分钟没淹死 = 有呼吸) 也当成
        // 封闭水体秒放弃。真正要拦的是"眼睛在水下无处呼吸"的封闭含水层。
        if (eyesUnderwater(mod)) {
            if (_waterStuckTimer == null) {
                _waterStuckTimer = new TimerGame(SEALED_WATER_TICKS / 20.0);
                _waterStuckTimer.reset(); // (2026-09-12 v6) 新建即到期, 必须 reset
            }
            if (_waterStuckTimer.elapsed()) {
                return giveUpScaffold(mod, player, "sealed water: " + (SEALED_WATER_TICKS / 20)
                        + "s in water without surfacing (digging up only floods more)");
            }
        } else if (_waterStuckTimer != null) {
            _waterStuckTimer.reset(); // 出水 → 重新计时
        }

        if (_scaffoldNoProgressTimer == null) {
            _scaffoldNoProgressTimer = new TimerGame(SCAFFOLD_NO_PROGRESS_TICKS / 20.0);
            _scaffoldNoProgressTimer.reset(); // (2026-09-12 v6) 新建即到期, 必须 reset
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
            // (2026-09-12 重见天日) y>=58 的口袋挖穿进水时目标继续向上, 不能用
            // 固定 58 (y66 口袋挖穿进水, 58 在脚下 → 往下游)。海里 (y<58) 仍是
            // 游到 58 的旧语义。
            int swimTarget = Math.max(SURFACE_TARGET_Y, player.getY() + 8);
            setDebugState("Scaffold: breached into water, swimming up (target y=" + swimTarget + ")");
            return new GetToYTask(swimTarget);
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
        _waterStuckTimer = null;
        _scaffoldLogTimer.reset();
        // (2026-09-12 v8) 做镐侧线状态归零: 新场景重新评估 ( isEqual 恒 true,
        // 框架不会因每 tick new 实例重跑这里, 字段跨抢占保留 —— 框架铁律)。
        _pickPhase = PickEscapePhase.NONE;
        _pickSubTask = null;
        _footDigTarget = null;
        _pickEscapeStartTick = -1;
        _stuckTicks = 0;
        _lastSampleX = Double.NaN;
        _lastSampleZ = Double.NaN;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 垫塔期间 hold 的跳跃键必须在所有退出路径上松开 (含被反射链抢占)。
        PillarJumpHelper.release(mod);
        // (2026-09-12 v8) 逃生挖掘豁免兜底复位: 本任务是唯一持有者, 所有退出
        // 路径 (完工/被抢占/链停止) 都必须关掉, 否则平时会乱挖"没镐避让"方块。
        mod.setEscapeDigOverride(false);
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
