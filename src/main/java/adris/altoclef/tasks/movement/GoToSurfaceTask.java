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
        // (2026-09-04 回地表直接向上挖, 用户"上方海洋 baritone 找不到落脚点" 现场:
        // y=50 干燥洞穴顶低, 头顶 y=51 是岩石) baritone 的 GoalYLevel(58) 在"洞穴
        // 顶上方是水/需跨水挖穿"时寻路失败原地卡死 (日志实锤 y=50 卡 43s)。修复:
        // deep (y<58) 时只要头顶是可挖实心就【持续挖正上方】逐格凿穿洞穴顶 — 不
        // 依赖 baritone 规划。挖穿进入空气/水后: 空气 → 交 GetToYTask (baritone
        // 正常上); 水 → 交 CustomBaritoneGoalTask 的水中上浮 (sprint+瞄准) 游出水面。
        // (2026-09-04 v3 修复) 前两版用 player.up() 挖顶是错的: up() 是玩家【身体所在
        // 格】(通常空气, isSolid=false) → 挖顶永不触发 (日志实锤: jar 已最新、游戏
        // 12:12:58 启动加载新 jar, 但 [GoToSurface] 从未出现)。正确目标 = 头顶第一格
        // 实心方块: 从 up(1) 向上扫描 (脚+1 起, 越过身体格), 找第一个实心且可挖的挖。
        // 挖开后 bot 跳上一格 (GetToYTask), 继续向上找下一格, 逐格凿穿洞穴顶。
        BlockPos digTarget = null;
        for (int dy = 1; dy <= 5; dy++) {
            BlockPos cand = player.up(dy);
            if (WorldHelper.isSolid(mod, cand)) {
                float h = mod.getWorld().getBlockState(cand).getHardness(mod.getWorld(), cand);
                if (h >= 0 && !mod.getExtraBaritoneSettings().shouldAvoidBreaking(cand)) {
                    digTarget = cand;
                }
                break; // 遇到实心 (可挖或不可挖/基岩) 就停
            }
        }
        if (digTarget != null) {
            Debug.logToFile("altoclef_diag.log", "[GoToSurface] digging up at "
                    + digTarget + " player=" + player);
            setDebugState("Digging upward to surface");
            return new DestroyBlockTask(digTarget);
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
