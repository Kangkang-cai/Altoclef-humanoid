package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;

/**
 * (2026-09-20 找诡异森林远征, 用户"没有记忆就远征寻找森林, 远征策略参考已有的、
 * 可复用的") 复用基类 {@link BearingExpeditionTask} 的锁定方位角远征机制,
 * 用在收珍珠场景:
 *
 * <ul>
 *   <li>上层 (BeatMinecraft2Task.getEnderPearlTask) 兜底顺序:
 *       看得见林子 → 蹲守; 记忆中有林子 (KnowledgeMemory.WARPED_FOREST, 跨会话
 *       落盘) → 直接奔最近的记忆中心; 都没有 → 本任务定向远征, 靠持续位移穿过
 *       下界生物群系去撞林子。取代原 SearchChunkForBlockTask 的"chunk 全方块
 *       透视扫描 + 无方向 wander" (堡垒 09-04 已淘汰同款机制, 森林按用户
 *       "堡垒行, 森林就做同样处理"原则跟进)。</li>
 *   <li>途中就近原则由上层保证: findVisibleSignal 每轮照扫, 一旦看见林子立刻
 *       切蹲守, 本任务被打断是预期行为 (蹲守优先级永远高于赶路)。</li>
 *   <li>拒绝条件: 目标列脚下附近有【岩浆】一律拒绝; 未加载 chunk 保守拒绝
 *       (与 NetherExpeditionTask 同款)。</li>
 *   <li>结束判定: 刻意【永不自然结束】—— 踩进林子由上层"脚下群系快路径"
 *       O(1) 重锚接管; 硬超时 600s 兜底交回上层重新规划 (下界地形可能被岩浆
 *       海围死, 换个方向比死磕强)。</li>
 * </ul>
 *
 * 诊断标签: [FOREST-EXPEDITION]。
 */
public class WarpedForestExpeditionTask extends BearingExpeditionTask {

    public WarpedForestExpeditionTask() {
        super(600); // 硬超时兜底
    }

    @Override
    protected String diagTag() {
        return "[FOREST-EXPEDITION]";
    }

    /** 目标列脚下 (y-3 ~ y+2) 是否岩浆 (未加载 chunk 保守视为有)。 */
    @Override
    protected boolean rejectTarget(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return true;
        if (!mod.getChunkTracker().isChunkLoaded(target)) return true;
        for (int dy = -3; dy <= 2; dy++) {
            if (mod.getWorld().getBlockState(target.add(0, dy, 0)).isOf(Blocks.LAVA)) return true;
        }
        return false;
    }

    @Override
    protected boolean diagSwitchState(AltoClef mod) {
        return isInWarpedForest(mod);
    }

    @Override
    protected String diagSwitchLabel() {
        return "inForest";
    }

    @Override
    protected boolean naturalFinish(AltoClef mod) {
        // 刻意不自然结束: 看见林子/踩进林子由上层接管 (见类注释)。
        return false;
    }

    @Override
    protected String timeoutDetail(AltoClef mod) {
        return "no forest found: inWarpedForest=" + isInWarpedForest(mod);
    }

    private static boolean isInWarpedForest(AltoClef mod) {
        try {
            return mod.getWorld().getBiome(mod.getPlayer().getBlockPos())
                    .matchesKey(BiomeKeys.WARPED_FOREST);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected String toDebugString() {
        return "Warped forest expedition";
    }
}
