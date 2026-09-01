package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public class CollectSticksTask extends ResourceTask {

    private final int _targetCount;

    public CollectSticksTask(int targetCount) {
        super(Items.STICK, targetCount);
        _targetCount = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(Blocks.DEAD_BUSH);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // (2026-08-31 木棍重复合成修复) 先查背包真实已有木棍是否达标, 达标就不再
        // 无条件去合成。旧逻辑每 tick 无条件返回 CraftInInventoryTask(合木棍)——
        // 即便背包已有十几根也照样重新合, 触发"放木板→拿回"振荡(日志反复5拍)。
        // 用真实背包计数(getItemCountReal), 不走缓存, 避免刚收进背包的木棍被
        // 缓存滞后判成"不足"。
        if (mod.getItemStorage().getItemCountReal(Items.STICK) >= _targetCount) {
            setDebugState("Already have enough sticks");
            return null;
        }
        Optional<BlockPos> nearestBush = mod.getBlockTracker().getNearestTracking(Blocks.DEAD_BUSH);
        // If there's a dead bush within range, go get it
        if (nearestBush.isPresent() && nearestBush.get().isWithinDistance(mod.getPlayer().getPos(), 20)) {
            return new MineAndCollectTask(Items.DEAD_BUSH, 999999, new Block[]{Blocks.DEAD_BUSH}, MiningRequirement.HAND);
        }
        // else craft from wood
        return new CraftInInventoryTask(new RecipeTarget(Items.STICK, _targetCount, CraftingRecipe.newShapedRecipe("sticks", new ItemTarget[]{new ItemTarget("planks"), null, new ItemTarget("planks"), null},4)));
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.DEAD_BUSH);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectSticksTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting " + _targetCount + " sticks";
    }
}
