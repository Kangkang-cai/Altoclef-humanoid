package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.item.Items;

/**
 * Make sure we have a tool at or above a mining level.
 */
public class SatisfyMiningRequirementTask extends Task {

    private final MiningRequirement _requirement;

    public SatisfyMiningRequirementTask(MiningRequirement requirement) {
        _requirement = requirement;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // (2026-08-23 诊断) 需要镐却触发本任务 → 记录当前背包镐状态, 排查
        // "背包有铁/钻石镐却还要重做镐" (用户反馈: 它似乎把背包里的东西跳过)。
        int wooden = mod.getItemStorage().getItemCountInventoryOnly(Items.WOODEN_PICKAXE);
        int stone = mod.getItemStorage().getItemCountInventoryOnly(Items.STONE_PICKAXE);
        int iron = mod.getItemStorage().getItemCountInventoryOnly(Items.IRON_PICKAXE);
        int golden = mod.getItemStorage().getItemCountInventoryOnly(Items.GOLDEN_PICKAXE);
        int diamond = mod.getItemStorage().getItemCountInventoryOnly(Items.DIAMOND_PICKAXE);
        int netherite = mod.getItemStorage().getItemCountInventoryOnly(Items.NETHERITE_PICKAXE);
        String m = "(SatisfyMiningReq) " + _requirement + " not met. Pickaxes: wood=" + wooden
                + " stone=" + stone + " iron=" + iron + " golden=" + golden + " diamond=" + diamond + " netherite=" + netherite;
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_requirement) {
            case HAND:
                // Will never happen if you program this right
                break;
            case WOOD:
                return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
            case STONE:
                return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
            case IRON:
                return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
            case DIAMOND:
                return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SatisfyMiningRequirementTask task) {
            return task._requirement == _requirement;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Satisfy Mining Req: " + _requirement;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.miningRequirementMetInventory(mod, _requirement);
    }
}
