package adris.altoclef.tasks.construction;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/**
 * Place any throwaway block at a position
 */
public class PlaceStructureBlockTask extends PlaceBlockTask {
    public PlaceStructureBlockTask(BlockPos target) {
        super(target, new Block[]{}, true, true);
    }

    /** (2026-09-30 悬空垫脚) 垫脚链子任务: 剩余下探深度递减继承, 防无界递归。 */
    public PlaceStructureBlockTask(BlockPos target, int scaffoldMaxDepth) {
        super(target, new Block[]{}, true, true, scaffoldMaxDepth);
    }
}