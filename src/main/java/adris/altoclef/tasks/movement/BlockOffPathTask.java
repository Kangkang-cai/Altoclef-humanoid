package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.PathReachabilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;

/**
 * Blocks the path between us and a remembered high-threat mob: we find a
 * walkable route from us to the mob, pick a narrow choke point on it a few
 * blocks away, and wall it off. If there's no choke point (open area) we give
 * up immediately — blocking an open field is useless.
 */
public class BlockOffPathTask extends Task implements ITaskRequiresGrounded {

    private static final Block[] WALL_BLOCKS = new Block[]{Blocks.DIRT, Blocks.COBBLESTONE, Blocks.NETHERRACK};
    private static final int MIN_DISTANCE_FROM_PLAYER = 4;
    private static final int MAX_DISTANCE_FROM_PLAYER = 16;
    private static final int TIMEOUT_TICKS = 20 * 30;

    private final Entity _threat;
    private BlockPos _chokePoint;
    private boolean _done = false;
    private long _startTick = -1;

    public BlockOffPathTask(Entity threat) {
        _threat = threat;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _chokePoint = null;
        _done = false;
        _startTick = WorldHelper.getTicks();
        findChokePoint(mod);
    }

    private void findChokePoint(AltoClef mod) {
        Optional<List<BlockPos>> path = PathReachabilityHelper.findPath(
                mod.getWorld(), mod.getPlayer().getPos(), _threat.getPos(), 30);
        if (path.isEmpty()) {
            _done = true; // not reachable, nothing to block.
            return;
        }
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : path.get()) {
            double dist = Math.sqrt(player.getSquaredDistance(pos));
            if (dist < MIN_DISTANCE_FROM_PLAYER) continue;
            if (dist > MAX_DISTANCE_FROM_PLAYER) break;
            if (isNarrowPassage(mod, pos)) {
                _chokePoint = pos;
                return;
            }
        }
        // Open area: blocking won't help, don't waste materials.
        Debug.logMessage("Block off path: no choke point found, skipping.");
        _done = true;
    }

    private boolean isNarrowPassage(AltoClef mod, BlockPos p) {
        int solidSides = 0;
        for (BlockPos n : new BlockPos[]{p.north(), p.south(), p.east(), p.west()}) {
            if (mod.getWorld().getBlockState(n).isSolidBlock(mod.getWorld(), n)) {
                solidSides++;
            }
        }
        return solidSides >= 3;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_done || _chokePoint == null) return null;
        if (!mod.getWorld().getBlockState(_chokePoint).isAir()) {
            _done = true; // already blocked.
            return null;
        }
        setDebugState("Blocking path at " + _chokePoint.toShortString());
        return new PlaceBlockTask(_chokePoint, WALL_BLOCKS);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Child tasks clean up after themselves.
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // Not started yet: findChokePoint hasn't run, so we can't be "done".
        if (!isActive()) return false;
        if (_done) return true;
        if (_chokePoint == null) return true;
        if (WorldHelper.getTicks() - _startTick > TIMEOUT_TICKS) {
            adris.altoclef.util.helpers.TimeoutLog.hit("BlockOffPathTask.timeout", "30s blocking without success, giving up");
            return true;
        }
        return !mod.getWorld().getBlockState(_chokePoint).isAir();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BlockOffPathTask task && task._threat == _threat;
    }

    @Override
    protected String toDebugString() {
        return "Block path from " + _threat.getType().getTranslationKey();
    }
}
