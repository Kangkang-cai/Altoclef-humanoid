package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Digs a tunnel away from the threat (mostly for caves/underground where
 * running away isn't possible). We pick the main horizontal axis closest to
 * "away from the mobs", then alternate between breaking the block in front and
 * walking forward until we're far enough away, blocked, or timed out.
 */
public class DigOutEscapeTask extends Task implements ITaskRequiresGrounded {

    // How far (blocks) we want to get from where we started digging.
    private static final double ESCAPE_DISTANCE = 30;
    private static final int TIMEOUT_TICKS = 20 * 120;
    // Walk this many blocks forward before checking the tunnel again.
    private static final int WALK_STEP = 3;

    private final Vec3d _awayDirection;
    private BlockPos _origin;
    private int _dirX = 1;
    private int _dirZ = 0;
    private long _startTick = -1;
    private boolean _blocked = false;

    public DigOutEscapeTask(Vec3d awayDirection) {
        _awayDirection = awayDirection;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _origin = mod.getPlayer().getBlockPos();
        _startTick = WorldHelper.getTicks();
        _blocked = false;
        // Pick the dominant horizontal axis pointing away from the threat.
        double dx = _awayDirection.x, dz = _awayDirection.z;
        if (Math.abs(dx) >= Math.abs(dz)) {
            _dirX = (int) Math.signum(dx);
            _dirZ = 0;
        } else {
            _dirX = 0;
            _dirZ = (int) Math.signum(dz);
        }
        if (_dirX == 0 && _dirZ == 0) _dirX = 1;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        BlockPos front = player.add(_dirX, 0, _dirZ);

        // Can't dig through bedrock or liquids: give up (caller escalates).
        BlockState frontState = mod.getWorld().getBlockState(front);
        BlockState frontUpState = mod.getWorld().getBlockState(front.up());
        boolean frontUnbreakable = isUnbreakable(frontState);
        boolean frontUpUnbreakable = isUnbreakable(frontUpState);

        if (frontUnbreakable || frontUpUnbreakable) {
            Debug.logMessage("Dig escape: blocked by unbreakable block, giving up.");
            _blocked = true;
            return null;
        }

        // Human-like: don't punch hard stone without a pickaxe. If the way ahead
        // is hard rock and we have no pickaxe, prefer a nearby soft direction.
        if (isHardRock(frontState) && !hasPickaxe(mod)) {
            if (redirectToSoftDirection(mod, player)) {
                front = player.add(_dirX, 0, _dirZ);
                frontState = mod.getWorld().getBlockState(front);
                frontUpState = mod.getWorld().getBlockState(front.up());
                setDebugState("Dig escape: soft-block detour");
            }
        }

        if (frontState.isSolidBlock(mod.getWorld(), front)) {
            setDebugState("Dig escape: breaking block ahead");
            return new DestroyBlockTask(front);
        }
        if (frontUpState.isSolidBlock(mod.getWorld(), front.up())) {
            setDebugState("Dig escape: breaking head-height block");
            return new DestroyBlockTask(front.up());
        }

        // Clear: walk forward a step.
        setDebugState("Dig escape: advancing");
        return new GetToXZTask(player.getX() + _dirX * WALK_STEP, player.getZ() + _dirZ * WALK_STEP);
    }

    // Hardness >= this means "rock that's painful to break by hand".
    private static final double SOFT_HARDNESS_LIMIT = 1.0;

    private boolean isHardRock(BlockState state) {
        if (state.isAir()) return false;
        return state.getHardness(null, BlockPos.ORIGIN) >= SOFT_HARDNESS_LIMIT;
    }

    private boolean hasPickaxe(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE,
                Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
    }

    // Try to steer to a horizontally-adjacent soft block instead of punching rock.
    private boolean redirectToSoftDirection(AltoClef mod, BlockPos player) {
        BlockPos[] dirs = new BlockPos[]{
                new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
                new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
        };
        for (BlockPos d : dirs) {
            if (d.getX() == _dirX && d.getZ() == _dirZ) continue;
            BlockPos candidate = player.add(d);
            BlockState s = mod.getWorld().getBlockState(candidate);
            if (s.isSolidBlock(mod.getWorld(), candidate) && !isHardRock(s)) {
                _dirX = d.getX();
                _dirZ = d.getZ();
                return true;
            }
        }
        return false;
    }

    private boolean isUnbreakable(BlockState state) {
        if (state.isAir()) return false;
        net.minecraft.block.Block b = state.getBlock();
        return b == Blocks.BEDROCK || b == Blocks.LAVA || b == Blocks.WATER || b == Blocks.BARRIER;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Child tasks clean up after themselves.
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // Not started yet: origin/start tick aren't set, never treat a fresh
        // instance as finished (that used to trap the state machine).
        if (!isActive()) return false;
        if (_blocked) return true;
        if (_startTick < 0) return false;
        long nowTick = WorldHelper.getTicks();
        if (nowTick - _startTick > TIMEOUT_TICKS) return true;
        BlockPos player = mod.getPlayer().getBlockPos();
        // Horizontal distance from the digging origin is what matters underground.
        double dx = player.getX() - _origin.getX();
        double dz = player.getZ() - _origin.getZ();
        return Math.sqrt(dx * dx + dz * dz) > ESCAPE_DISTANCE;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof DigOutEscapeTask;
    }

    @Override
    protected String toDebugString() {
        return "Dig escape";
    }
}
