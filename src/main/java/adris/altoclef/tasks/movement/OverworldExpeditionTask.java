package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

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

    @Override
    protected boolean diagSwitchState(AltoClef mod) {
        return mod.getWorld() != null
                && WorldHelper.isOcean(mod.getWorld().getBiome(mod.getPlayer().getBlockPos()));
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
