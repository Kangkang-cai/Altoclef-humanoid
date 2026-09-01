package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

public class CollectCobblestoneTask extends ResourceTask {

    private final int _count;
    // (2026-08-25 用户实测纠正) 黑石/深板岩圆石与圆石功能完全相同 (合成熔炉/
    // 石工具), 都算"圆石类"目标。
    private static final Item[] STONE_ITEMS = new Item[]{Items.COBBLESTONE, Items.BLACKSTONE, Items.COBBLED_DEEPSLATE};

    public CollectCobblestoneTask(int targetCount) {
        super(new ItemTarget(STONE_ITEMS, targetCount));
        _count = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // 挖 STONE(得圆石)/COBBLESTONE/DEEPSLATE(得深板岩圆石)/BLACKSTONE(得黑石)
        // 都满足目标 — 深板岩区/下界也能就地收集, 不再需要回地表 (上一版误加的
        // isDeepUnderground 回地表逻辑已撤, 用户实测深板岩圆石/黑石均可合成熔炉)。
        return new MineAndCollectTask(new ItemTarget(STONE_ITEMS, _count),
                new Block[]{Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.BLACKSTONE}, MiningRequirement.WOOD);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectCobblestoneTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect Cobblestone";
    }
}
