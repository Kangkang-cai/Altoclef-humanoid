package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-08-22) 回到地表通用前置任务。
 *
 * 用户原则: 地表资源(原木/食物/羊毛等)的收集任务, 当 bot 深处地下
 * (矿洞/深水)时, 应先显式"回到地表"再收集, 而不是让收集任务硬啃
 * 跨地形长路径 — Baritone 垂直挖穿几十格岩层耗时数分钟, 期间会被
 * 收集任务的进度检查误判"不可达"而放弃 ("挖一半就不挖了")。
 *
 * 实现: 目标 Y 取附近陆地地表 (getLandSurfaceY), 用 GoalYLevel 让
 * Baritone 自己挖上去; 头顶是海洋时退化为"挖到海平面之上"。
 * 以 isDeepUnderground 变 false (头顶无盖且高于海平面下限) 为完成
 * 判据, 而非到达某个固定 Y — 挖到山腰露头也算回到地表。
 *
 * 峡谷搭高扩展 (2026-08-22): 谷底能见天但两侧岩壁高出 12+ 格
 * (getPitEscapeY) 时也视为"未到地表" — Baritone 步行寻路找不到
 * 上坡路径, 探索任务在谷底永远打转 (tasks.log 11:21-11:36 循环
 * 16+ 次)。GetToYTask 会让 Baritone 搭方块上去。
 *
 * 已有 CollectWoolTask 的私有实现是本模式的首次应用 (羊只在地表),
 * 此类是抽出后的通用版, 供 BeatMinecraft2Task 的 wood/food 分支用。
 */
public class GoToSurfaceTask extends Task {

    private Integer _cachedSurfaceY = null;
    // 峡谷脱困目标 Y (getPitEscapeY): 壁顶+1。搭到壁顶后水平走出去。
    // 每 1 秒重算一次 (getPitEscapeY 扫 960 个方块, 不能每 tick 调)。
    private Integer _pitEscapeY = null;
    private final adris.altoclef.util.time.TimerGame _pitEscapeTimer = new adris.altoclef.util.time.TimerGame(1);

    // ===== 向上挖掘开路 (2026-09-04) =====
    // 用户"上方全是海洋, 导航找不到落脚点, 但可以游泳上去" (现场: y=50 干燥洞穴
    // 地面, 上方是岩石/海洋)。baritone 的 GoalYLevel(58) 在"头顶上方是水/需要跨水
    // 挖穿"时寻路失败 → 原地卡住 (日志实锤 y=50 卡 43s, 无任何进展)。修复: y 长
    // 时间不涨且头顶是可挖方块 → 强制派 DestroyBlockTask 挖正上方, 逐格凿开洞穴顶;
    // 一旦挖穿进入水, CustomBaritoneGoalTask 的水中上浮逻辑接管游出水面。
    private int _lastSurfaceY = Integer.MIN_VALUE;
    private int _surfaceStallTicks = 0;
    private boolean _digUpActive = false;
    // baritone 挖一格岩石约 1~2s, 超过该 tick 数 y 仍不涨即判卡住强制挖。
    private static final int SURFACE_STALL_TICKS = 60;

    private boolean inPit(AltoClef mod) {
        if (_pitEscapeY == null || _pitEscapeTimer.elapsed()) {
            _pitEscapeY = WorldHelper.getPitEscapeY(mod);
            _pitEscapeTimer.reset();
        }
        return _pitEscapeY >= 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        boolean deep = WorldHelper.isDeepUnderground(mod);
        if (!deep) {
            _digUpActive = false;
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
        // (2026-09-04 向上挖掘开路) 正在凿顶: 持续挖正上方直到打通 (进入水/空气)。
        if (_digUpActive) {
            BlockPos up = player.up();
            if (WorldHelper.isSolid(mod, up) && WorldHelper.canBreak(mod, up)) {
                setDebugState("Digging upward through cave ceiling");
                return new DestroyBlockTask(up);
            }
            _digUpActive = false; // 头顶已非实心 (挖穿/是水/是空气), 交还 baritone
        }
        // 无进展检测: y 不涨累计超过阈值且头顶可挖 → 强制挖开 (baritone 卡住)。
        if (player.getY() != _lastSurfaceY) {
            _lastSurfaceY = player.getY();
            _surfaceStallTicks = 0;
        } else {
            _surfaceStallTicks++;
            if (_surfaceStallTicks >= SURFACE_STALL_TICKS) {
                BlockPos up = player.up();
                if (WorldHelper.isSolid(mod, up) && WorldHelper.canBreak(mod, up)) {
                    Debug.logMessage("[GoToSurface] stuck at y=" + player.getY()
                            + ", digging upward to escape cave");
                    _digUpActive = true;
                    setDebugState("Stuck, digging upward to escape cave");
                    return new DestroyBlockTask(up);
                }
                _surfaceStallTicks = 0; // 头顶不可挖, 重置再等 (避免每 tick 重判)
            }
        }
        // (2026-08-27 表面目标高度 63→58, 用户改判) 旧版固定 63 (海平面), 卡死现场
        // (15:34): bot 已到 y≈63 但抬起头扫描仍判深地下, GetToYTask(63) 目标==当前位置
        // 立刻"完成", 再无上升动力 → 锁死在 63 走不动。降为 58 与 isDeepUnderground 的
        // 硬下限一致: 挖到 58 露头 (头顶不再被盖) 即视为回到表面即可, 不必强行爬到 63。
        if (_cachedSurfaceY == null) {
            _cachedSurfaceY = 58;
        }
        setDebugState("Digging up to surface (target y=" + _cachedSurfaceY + ")");
        return new GetToYTask(_cachedSurfaceY);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (WorldHelper.isDeepUnderground(mod)) return false;
        return !inPit(mod);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _pitEscapeY = null;
        _lastSurfaceY = Integer.MIN_VALUE;
        _surfaceStallTicks = 0;
        _digUpActive = false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
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
