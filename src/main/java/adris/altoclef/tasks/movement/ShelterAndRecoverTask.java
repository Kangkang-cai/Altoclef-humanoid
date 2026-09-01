package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Sheltering in place: quickly wall ourselves in to break hostile line of
 * sight / approach, recover (eat + heal), then open a hole and resume.
 *
 * Phases: BUILD -> HEAL -> OPEN. Every phase has a hard timeout so this task
 * can never loop forever; it always ends (open or give up).
 */
public class ShelterAndRecoverTask extends Task implements ITaskRequiresGrounded {

    private static final Block[] SHELTER_BLOCKS = new Block[]{
            Blocks.DIRT, Blocks.COBBLESTONE, Blocks.NETHERRACK, Blocks.STONE, Blocks.COBBLED_DEEPSLATE
    };
    private static final Item[] SHELTER_ITEMS = new Item[]{
            Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.STONE, Items.COBBLED_DEEPSLATE
    };

    private static final int MIN_BLOCKS = 2;
    private static final int BUILD_TIMEOUT_TICKS = 20 * 30;
    private static final int HEAL_TIMEOUT_TICKS = 20 * 45;
    private static final int OPEN_TIMEOUT_TICKS = 20 * 15;
    private static final double HEAL_HEALTH = 18.0;

    private final List<BlockPos> _walls = new ArrayList<>();
    private int _wallIndex = 0;
    private int _phase = 0; // 0=BUILD 1=HEAL 2=OPEN
    private long _phaseStartTick = -1;
    private boolean _gaveUp = false;

    @Override
    protected void onStart(AltoClef mod) {
        _walls.clear();
        _wallIndex = 0;
        _phase = 0;
        _gaveUp = false;
        _phaseStartTick = WorldHelper.getTicks();
        planShelter(mod);
    }

    /**
     * Wall in the air spots around us (4 sides + ceiling). We don't need a
     * perfect box: covering the most open sides already breaks the mobs' path.
     */
    private void planShelter(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        for (BlockPos pos : new BlockPos[]{p.north(), p.south(), p.east(), p.west(), p.up()}) {
            if (mod.getWorld().getBlockState(pos).isAir()) {
                _walls.add(pos);
            }
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long nowTick = WorldHelper.getTicks();

        switch (_phase) {
            case 0: // BUILD
                if (_gaveUp || nowTick - _phaseStartTick > BUILD_TIMEOUT_TICKS) {
                    _phase = 1;
                    _phaseStartTick = nowTick;
                    return null;
                }
                if (_wallIndex >= _walls.size()) {
                    _phase = 1;
                    _phaseStartTick = nowTick;
                    return null;
                }
                // Place the next wall block.
                BlockPos wall = _walls.get(_wallIndex);
                if (mod.getWorld().getBlockState(wall).isAir()) {
                    if (!mod.getItemStorage().hasItem(SHELTER_ITEMS)) {
                        // Out of materials: can't build a proper shelter, give up.
                        Debug.logMessage("Shelter: no building materials, giving up.");
                        _gaveUp = true;
                        return null;
                    }
                    setDebugState("Shelter: building wall " + (_wallIndex + 1) + "/" + _walls.size());
                    return new PlaceBlockTask(wall, SHELTER_BLOCKS);
                }
                _wallIndex++;
                return null;

            case 1: // HEAL
                if (nowTick - _phaseStartTick > HEAL_TIMEOUT_TICKS) {
                    _phase = 2;
                    _phaseStartTick = nowTick;
                    return null;
                }
                if (mod.getPlayer().getHealth() >= HEAL_HEALTH && !mod.getFoodChain().needsToEat(mod)) {
                    _phase = 2;
                    _phaseStartTick = nowTick;
                    return null;
                }
                setDebugState("Shelter: recovering (health " + (int) mod.getPlayer().getHealth() + ")");
                return null;

            case 2: // OPEN
            default:
                if (_walls.isEmpty()) {
                    return null; // nothing was built, just done.
                }
                BlockPos toBreak = _walls.get(0);
                if (!mod.getWorld().getBlockState(toBreak).isAir()) {
                    setDebugState("Shelter: opening wall");
                    return new DestroyBlockTask(toBreak);
                }
                return null;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Nothing to release; child tasks clean up after themselves.
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // Not started yet: must not report "finished" before onStart plans the walls.
        if (!isActive()) return false;
        if (_walls.isEmpty()) return true; // nothing to build, done immediately.
        if (_phase < 2) return false;
        long nowTick = WorldHelper.getTicks();
        // Open phase is done once the first wall is gone (or we timed out).
        if (nowTick - _phaseStartTick > OPEN_TIMEOUT_TICKS) return true;
        return mod.getWorld().getBlockState(_walls.get(0)).isAir();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ShelterAndRecoverTask;
    }

    @Override
    protected String toDebugString() {
        return "Shelter and recover";
    }
}
