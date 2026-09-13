package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Some generic tasks require us to go to the nether/overworld/end.
 * <p>
 * The user should be able to specify how this should be done in settings
 * (ex, craft a new portal from scratch or check particular portal areas first or highway or whatever)
 */
public class DefaultGoToDimensionTask extends Task {

    private final Dimension _target;
    // Cached to keep build properties alive if this task pauses/resumes.
    private final Task _cachedNetherBucketConstructionTask = new ConstructNetherPortalBucketTask();

    public DefaultGoToDimensionTask(Dimension target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() == _target) return null;

        switch (_target) {
            case OVERWORLD:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        return goToOverworldFromNetherTask(mod);
                    case END:
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case NETHER:
                switch (WorldHelper.getCurrentDimension()) {
                    case OVERWORLD:
                        return goToNetherFromOverworldTask(mod);
                    case END:
                        // First go to the overworld
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case END:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        // First go to the overworld
                        return goToOverworldFromNetherTask(mod);
                    case OVERWORLD:
                        return goToEndTask(mod);
                }
                break;
        }

        setDebugState(WorldHelper.getCurrentDimension() + " -> " + _target + " is NOT IMPLEMENTED YET!");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DefaultGoToDimensionTask task) {
            return task._target == _target;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Going to dimension: " + _target + " (default version)";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == _target;
    }

    private Task goToOverworldFromNetherTask(AltoClef mod) {
        // (2026-09-13 用户"黑曜石够且有打火石/材料就直接造门, 别走原来的门")
        // 有料直接原地造门回家, 不再长途跋涉去翻 last-used / 记忆旧门 (旧门
        // 记忆可能过期/死门, 徒步也可能几百上千格)。唯一例外: 眼前 64 格内就
        // 有已点亮的门, 走进去比烧 10 块黑曜石划算, 人类也会直接进。
        // 材料闸收死: 黑曜石 ≥10, 且有打火石或"铁锭+燧石"能现场合成 ——
        // 绝不让 ConstructNetherPortalObsidianTask 在下界触发黑曜石收集
        // (那条路绕猪灵交易, 是另一个阶段的事)。
        if (canBuildPortalHome(mod)) {
            Optional<BlockPos> litNearby = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), Blocks.NETHER_PORTAL);
            if (litNearby.isEmpty() || !litNearby.get().isWithinDistance(mod.getPlayer().getPos(), 64)) {
                setDebugState("Has obsidian + flint&steel (or materials), building portal home directly");
                return new ConstructNetherPortalObsidianTask();
            }
            setDebugState("Lit nether portal within 64 blocks, entering instead of building");
        }

        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }

        Optional<BlockPos> closest = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
        if (closest.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(closest.get());
        }

        Optional<BlockPos> remembered = mod.getWorldMemory().getNearestPortal(mod.getPlayer().getPos());
        if (remembered.isPresent()) {
            setDebugState("Going to remembered nether portal pos");
            return new GetToBlockTask(remembered.get());
        }

        setDebugState("Constructing nether portal with obsidian");
        return new ConstructNetherPortalObsidianTask();
    }

    private Task goToOverworldFromEndTask(AltoClef mod) {
        setDebugState("TODO: Go to center portal (at 0,0). If it doesn't exist, kill ender dragon lol");
        return null;
    }

    private Task goToNetherFromOverworldTask(AltoClef mod) {
        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }

        Optional<BlockPos> lastUsed = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.OVERWORLD);
        if (lastUsed.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(lastUsed.get());
        }

        Optional<BlockPos> remembered = mod.getWorldMemory().getNearestPortal(mod.getPlayer().getPos());
        if (remembered.isPresent()) {
            setDebugState("Going to remembered nether portal pos");
            return new GetToBlockTask(remembered.get());
        }

        return switch (mod.getModSettings().getOverworldToNetherBehaviour()) {
            case BUILD_PORTAL_VANILLA -> _cachedNetherBucketConstructionTask;
            case GO_TO_HOME_BASE -> new GetToBlockTask(mod.getModSettings().getHomeBasePosition());
        };
    }

    private Task goToEndTask(AltoClef mod) {
        // Keep in mind that getting to the end requires going to the nether first.
        setDebugState("TODO: Get to End, Same as BeatMinecraft");
        return null;
    }

    private boolean netherPortalIsClose(AltoClef mod) {
        if (mod.getBlockTracker().anyFound(Blocks.NETHER_PORTAL)) {
            Optional<BlockPos> closest = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), Blocks.NETHER_PORTAL);
            return closest.isPresent() && closest.get().isWithinDistance(mod.getPlayer().getPos(), 2000);
        }
        return false;
    }

    /**
     * (2026-09-13) 下界回家"直接造门"材料闸: 黑曜石 ≥10 (门框最低 10 块无四角),
     * 且有打火石, 或有铁锭+燧石可现场合成打火石。缺任一都走旧的找门链。
     */
    private boolean canBuildPortalHome(AltoClef mod) {
        if (mod.getItemStorage().getItemCount(Items.OBSIDIAN) < 10) return false;
        if (mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)) return true;
        return mod.getItemStorage().hasItem(Items.IRON_INGOT) && mod.getItemStorage().hasItem(Items.FLINT);
    }

    public enum OVERWORLD_TO_NETHER_BEHAVIOUR {
        BUILD_PORTAL_VANILLA,
        GO_TO_HOME_BASE
    }
}
