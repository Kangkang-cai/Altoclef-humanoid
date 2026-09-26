package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-15 地表觅食远征, 用户"连续长时间没找到食物就远走, 尽量不要回头,
 * 不要远征走到海里去了") 锁定方位角远征机制已上提到基类
 * {@link BearingExpeditionTask} (2026-09-16, 与下界挖金远征共用), 本类只提供
 * 主世界觅食的差异参数:
 *
 * - 拒绝条件: 目标列 biome 是【海洋】一律拒绝 (不下海); 河/湖放行
 *   (baritone 游河面没问题)。
 * - 游泳引导开启: 已在水里时直接朝最近非海洋陆地游出去。
 * - 永不自行 isFinished: 是否结束由上层 (CollectFoodTask 食物达标) 决定,
 *   不设硬超时。
 *
 * 诊断标签: [FOOD-EXPEDITION]。
 */
public class OverworldExpeditionTask extends BearingExpeditionTask {

    public OverworldExpeditionTask() {
        super(-1); // 无硬超时, 收队由上层判定
    }

    @Override
    protected String diagTag() {
        return "[FOOD-EXPEDITION]";
    }

    /** 目标列是否海洋 biome (与 BiasedExploreTask.isTargetOcean 同口径)。 */
    @Override
    protected boolean rejectTarget(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        if (!mod.getChunkTracker().isChunkLoaded(target)) return false;
        return WorldHelper.isOcean(mod.getWorld().getBiome(target));
    }

    @Override
    protected boolean swimSteering() {
        return true;
    }

    /** (2026-09-24) 主世界觅食远征开启出洞安全引导 (前 3 个导航强制草方块等地表落点)。 */
    @Override
    protected boolean useSurfaceExitGuide() {
        return true;
    }

    @Override
    protected boolean diagSwitchState(AltoClef mod) {
        return mod.getWorld() != null
                && WorldHelper.isOcean(mod.getWorld().getBiome(mod.getPlayer().getBlockPos()));
    }

    // ---- (2026-09-26 恶劣地形换向, 用户"远征遇到极端恶劣地形应该换远征方向,
    //      地形好坏参考偏置探索的地形偏好") ----
    // 岸上选步时沿本步直线采样连续水面 (复用 BiasedExploreTask 的宽水口径,
    // RIVER_CROSS_MAX_BLOCKS=24 单源): 超限 = 宽河/湖/湾, 拒绝该候选步。
    // 连续全灭 → _failCounter 累计 → 基类遇障试探同侧加档 (+60°…±150°) →
    // 试探走出整步程转正为新锁定方位角 = 远征换方向。窄河照旧放行 ("过河可以"
    // 定案不推翻); 已落水时不进本钩子 (游泳引导优先游出去)。
    /** 诊断日志节流: 选步失败时每 tick 最多 16 次尝试, 2s 一条足够取证。 */
    private static long _lastTerrainRejectLogMs = 0;

    @Override
    protected boolean rejectTerrainAlong(AltoClef mod, Vec3d from, double angle, double step) {
        int span = BiasedExploreTask.maxWaterSpanAlongLine(mod, from, angle, step);
        if (span <= BiasedExploreTask.RIVER_CROSS_MAX_BLOCKS) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - _lastTerrainRejectLogMs >= 2000) {
            _lastTerrainRejectLogMs = now;
            Debug.logToFile("altoclef_diag.log",
                    "[FOOD-EXPEDITION]\tterrain-reject wide water on bearing "
                            + (int) Math.toDegrees(angle) + " deg: span=" + span
                            + " > " + BiasedExploreTask.RIVER_CROSS_MAX_BLOCKS
                            + " step=" + (int) step
                            + " player=" + (mod.getPlayer() != null
                                    ? mod.getPlayer().getBlockPos().toShortString() : "?"));
        }
        return true;
    }

    @Override
    protected String diagSwitchLabel() {
        return "ocean";
    }

    @Override
    protected String toDebugString() {
        return "Overworld food expedition";
    }
}
