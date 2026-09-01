package adris.altoclef.util.helpers;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Cheap, bounded reachability check between two world positions.
 *
 * A human "knows" whether a mob can still walk to them and roughly how long
 * the route is. We approximate that with a small BFS over walkable blocks,
 * capped at a short path length so it stays cheap even when called a few
 * times per second.
 *
 * This is intentionally NOT a full pathfinder: it only answers "is there a
 * route of at most N blocks" and returns one such route for blocking.
 */
public final class PathReachabilityHelper {

    // Max route length we care about (blocks walked).
    private static final int DEFAULT_MAX_DISTANCE = 30;
    // Hard cap on BFS nodes so a pathological open area can't stall the tick.
    private static final int MAX_NODES = 4096;

    private static final int[][] NEIGHBOR_DXZ = new int[][]{
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private PathReachabilityHelper() {}

    /**
     * Whether a route of at most {@code maxDistance} walkable blocks exists
     * between {@code from} and {@code to}.
     */
    public static boolean isReachableWithin(ClientWorld world, Vec3d from, Vec3d to, double maxDistance) {
        return findPath(world, from, to, maxDistance).isPresent();
    }

    public static boolean isReachableWithin(ClientWorld world, Vec3d from, Vec3d to) {
        return isReachableWithin(world, from, to, DEFAULT_MAX_DISTANCE);
    }

    /**
     * Returns a walkable route (list of block positions, start first) from
     * {@code from} to {@code to}, or empty if none exists within the distance cap.
     */
    public static Optional<List<BlockPos>> findPath(ClientWorld world, Vec3d from, Vec3d to, double maxDistance) {
        if (world == null) return Optional.empty();
        BlockPos start = new BlockPos(from.x, from.y, from.z);
        BlockPos target = new BlockPos(to.x, to.y, to.z);

        int maxSteps = Math.max(8, (int) Math.ceil(maxDistance));

        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Integer> steps = new HashMap<>();
        queue.add(start);
        steps.put(start, 0);
        cameFrom.put(start, null);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int step = steps.get(current);

            if (step >= maxSteps) continue;
            if (reached(current, target)) {
                return Optional.of(reconstructPath(cameFrom, current));
            }

            for (int[] dxz : NEIGHBOR_DXZ) {
                BlockPos neighbor = current.add(dxz[0], 0, dxz[1]);
                // Try one up (step onto a block), same level, then one down
                // (drop) — whichever is walkable first.
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos candidate = neighbor.up(dy);
                    if (isWalkable(world, candidate) && !steps.containsKey(candidate)) {
                        int nextStep = step + 1;
                        if (nextStep > maxSteps) continue;
                        if (steps.size() >= MAX_NODES) return Optional.empty();
                        steps.put(candidate, nextStep);
                        cameFrom.put(candidate, current);
                        queue.add(candidate);
                        break;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A mob is "at" us when it can stand right next to (or on) the target block.
     */
    private static boolean reached(BlockPos current, BlockPos target) {
        int dx = Math.abs(current.getX() - target.getX());
        int dy = Math.abs(current.getY() - target.getY());
        int dz = Math.abs(current.getZ() - target.getZ());
        return dx + dz <= 1 && dy <= 1;
    }

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        LinkedList<BlockPos> path = new LinkedList<>();
        BlockPos cursor = end;
        while (cursor != null) {
            path.addFirst(cursor);
            cursor = cameFrom.get(cursor);
        }
        return path;
    }

    /**
     * Whether a mob can stand at this block: the block itself isn't solid
     * and it has solid ground directly beneath it (or it's the start node).
     */
    private static boolean isWalkable(ClientWorld world, BlockPos pos) {
        BlockState self = world.getBlockState(pos);
        BlockState below = world.getBlockState(pos.down());
        return !self.isSolidBlock(world, pos) && below.isSolidBlock(world, pos.down());
    }
}
