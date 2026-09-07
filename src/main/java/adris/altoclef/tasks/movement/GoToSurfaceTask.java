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
 * — 挖到山腰露头也算回到地表。
 *
 * 峡谷搭高扩展 (2026-08-22): 谷底能见天但两侧岩壁高出 12+ 格
 * (getPitEscapeY) 时也视为"未到地表" — Baritone 步行寻路找不到
 * 上坡路径, 探索任务在谷底永远打转 (tasks.log 11:21-11:36 循环
 * 16+ 次)。GetToYTask 会让 Baritone 搭方块上去。
 *
 * 已有 CollectWoolTask 的私有实现是本模式的首次应用 (羊只在地表),
 * 此类是抽出后的通用版, 供 BeatMinecraft2Task 的 wood/food 分支用。
 *
 * (2026-09-07 分流重写, 用户"他还非常深就触发了之前用于头顶是海洋的
 * 情况, 这不对, 他现在头顶也不是海洋, 他需要正常上去地面")
 * 旧版对 y<58 一律"逐格向上凿" (该逻辑 2026-09-04 为"头顶是海洋、
 * baritone 找不到落脚点"现场所加), 干燥洞穴里也垂直凿天井。现场确认
 * (2026-09-07 16:11 tasks.log): 干燥洞穴 y=-40, 44s 内 Destroy(191)↔
 * Destroy(192)↔GetToYTask 三方乒乓 (TaskPingPong 5s 7x), bot 没升一格,
 * 一个方块没挖穿, 最终整个任务树 limit reached 放弃。
 *
 * 新分流:
 *  - 头顶列 (扫到建造上限/区块未加载为止) 出现任意水 (海洋/含水层/
 *    含水方块) → "头顶有水" → 逐格向上凿 + 挖穿进水后 GetToYTask 触发
 *    CustomBaritoneGoalTask 水中垂直上浮 (保留 2026-09-04 海洋逻辑)。
 *  - 头顶无水 (默认) → Baritone 正常寻路上去 (GetToYTask(58), 洞穴
 *    穿行/楼梯), 不再盲目垂直凿。
 *  - Baritone 失速兜底: Y 净高度持续 goToSurfaceBaritoneFallbackSeconds
 *    (默认 60, 0=永不降级) 无增长 → 降级逐格凿 (2026-09-04 实锤干燥
 *    低顶洞穴 baritone 卡 43s 的旧现场仍需兜底)。
 *  - 逐格凿目标改粘性: 目标仍实心且未越过就保持, 不随 bot 微动换列
 *    (修本次乒乓根因: 每 tick 重算"头顶 5 格内第一块实心", 相邻列间
 *    反复横跳重置挖掘进度)。
 */
public class GoToSurfaceTask extends Task {

    private enum AscentMode {
        /** 头顶无水: Baritone 正常寻路 (默认)。 */
        BARITONE,
        /** 头顶有水或 Baritone 失速降级: 逐格向上凿。 */
        DIG_UP
    }

    private AscentMode _mode = AscentMode.BARITONE;

    // 头顶水检测每 1s 重扫一次 (列扫描最多 ~380 格, 不能每 tick 调)。
    private final TimerGame _waterScanTimer = new TimerGame(1);
    private boolean _waterOverhead = false;

    // BARITONE 模式失速兜底: 记录任务内最高 Y, 超时无增长 → 降级逐格凿。
    private int _baritoneBestY = Integer.MIN_VALUE;
    private TimerGame _baritoneStallTimer = null;

    // 逐格凿粘性目标 (DIG_UP 模式)。
    private BlockPos _digTarget = null;
    // 挖掘诊断日志节流 (旧版每 tick 一行, 本次事故打了 930 行)。
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
     * (2026-09-07) 头顶列是否有水: 从头顶向上扫到建造上限或区块未加载为止,
     * 任意格含水 (海水/湖/含水层/含水方块如水海带) 即 true。
     * "头顶是海洋"的判定不再靠猜测, 而是实测水柱。未加载视为无水 (深地下
     * 上方区块通常已加载; 海洋场景 y=50 时上方也必然已加载)。
     */
    private boolean scanWaterOverhead(AltoClef mod, BlockPos player) {
        // 384 格 = 1.18 全建造高度, 从任意深度出发都覆盖到顶。
        int top = player.getY() + 384;
        for (int y = player.getY() + 1; y < top; y++) {
            BlockPos pos = new BlockPos(player.getX(), y, player.getZ());
            if (!mod.getChunkTracker().isChunkLoaded(pos)) {
                return false; // 再往上不可知, 按无水处理
            }
            FluidState fluid = mod.getWorld().getBlockState(pos).getFluidState();
            if (fluid != null && !fluid.isEmpty()
                    && (fluid.getFluid() == Fluids.WATER || fluid.getFluid() == Fluids.FLOWING_WATER)) {
                return true;
            }
        }
        return false;
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

        // 每 1s 实测头顶列: 有水 → 切 DIG_UP (海洋/含水层场景)。
        if (_waterScanTimer.elapsed()) {
            _waterScanTimer.reset();
            boolean water = scanWaterOverhead(mod, player);
            if (water && !_waterOverhead) {
                Debug.logToFile("altoclef_diag.log", "[GoToSurface] water overhead detected at "
                        + player + " -> DIG_UP mode");
            }
            _waterOverhead = water;
            if (_waterOverhead && _mode != AscentMode.DIG_UP) {
                _mode = AscentMode.DIG_UP;
            }
        }

        if (_mode == AscentMode.DIG_UP) {
            return tickDigUp(mod, player);
        }

        // ===== BARITONE 模式 (默认): 头顶无水, 正常寻路上去 =====
        // 失速兜底: Y 净高度持续 N 秒无增长 → 降级逐格凿。
        int y = player.getY();
        if (_baritoneStallTimer == null) {
            int fb = mod.getModSettings().getGoToSurfaceBaritoneFallbackSeconds();
            _baritoneStallTimer = new TimerGame(fb > 0 ? fb : Double.MAX_VALUE);
        }
        if (_baritoneBestY == Integer.MIN_VALUE) {
            _baritoneBestY = y;
        }
        if (y > _baritoneBestY) {
            _baritoneBestY = y;
            _baritoneStallTimer.reset();
        } else if (_baritoneStallTimer.elapsed()) {
            Debug.logToFile("altoclef_diag.log", "[GoToSurface] baritone stalled (bestY=" + _baritoneBestY
                    + ", player=" + player + ") -> fallback to DIG_UP");
            _mode = AscentMode.DIG_UP;
            return tickDigUp(mod, player);
        }
        setDebugState("Going up to surface via baritone (target y=" + SURFACE_TARGET_Y + ")");
        return new GetToYTask(SURFACE_TARGET_Y);
    }

    /**
     * (2026-09-07) 逐格向上凿 (仅头顶有水 / Baritone 失速降级时)。
     * 目标 = 头顶 5 格内第一块实心可挖方块, 粘性保持直到挖穿或越过,
     * 挖开后 bot 跳上一格 (GetToYTask), 继续向上; 挖穿进水后 GetToYTask
     * 的 GoalYLevel 会触发 CustomBaritoneGoalTask 水中垂直上浮。
     */
    private Task tickDigUp(AltoClef mod, BlockPos player) {
        // 粘性: 目标仍实心且未越过 → 保持, 不随 bot 微动换列重置挖掘进度。
        if (_digTarget != null) {
            if (!WorldHelper.isSolid(mod, _digTarget) || player.getY() >= _digTarget.getY()) {
                _digTarget = null; // 已挖穿 / 已爬到该高度
            }
        }
        if (_digTarget == null) {
            // 从脚+1 起 (越过身体格) 向上找第一块实心: 可挖则为目标,
            // 不可挖 (基岩等) 停止扫描。头顶 5 格内全空 → 交 GetToYTask。
            for (int dy = 1; dy <= 5; dy++) {
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
            setDebugState("Digging upward to surface");
            return new DestroyBlockTask(_digTarget);
        }
        setDebugState("Digging up to surface (target y=" + SURFACE_TARGET_Y + ")");
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
        _waterOverhead = false;
        _waterScanTimer.reset();
        _baritoneBestY = Integer.MIN_VALUE;
        _baritoneStallTimer = null;
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
