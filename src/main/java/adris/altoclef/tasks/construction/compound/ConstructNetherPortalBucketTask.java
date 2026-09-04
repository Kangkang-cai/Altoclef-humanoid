package adris.altoclef.tasks.construction.compound;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.ClearLiquidTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceObsidianBucketTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.HashSet;

/**
 * Build a nether portal by casting each piece with water + lava.
 *
 * Currently the most reliable portal building method.
 */
public class ConstructNetherPortalBucketTask extends Task {

    // Order here matters
    private static final Vec3i[] PORTAL_FRAME = new Vec3i[]{
            // Left side
            new Vec3i(0, 0, -1),
            new Vec3i(0, 1, -1),
            new Vec3i(0, 2, -1),
            // Right side
            new Vec3i(0, 0, 2),
            new Vec3i(0, 1, 2),
            new Vec3i(0, 2, 2),
            // Bottom
            new Vec3i(0, -1, 0),
            new Vec3i(0, -1, 1),
            // Top
            new Vec3i(0, 3, 0),
            new Vec3i(0, 3, 1)
    };

    private static final Vec3i[] PORTAL_INTERIOR = new Vec3i[]{
            new Vec3i(0, 0, 0),
            new Vec3i(0, 1, 0),
            new Vec3i(0, 2, 0),
            new Vec3i(0, 0, 1),
            new Vec3i(0, 1, 1),
            new Vec3i(0, 2, 1)
    };

    // The "portalable" region includes the portal (1 x 6 x 4 structure) and an outer buffer for its construction and water bullshit.
    // The "portal origin relative to region" corresponds to the portal origin with respect to the "portalable" region (see _portalOrigin).
    // This can only really be explained visually, sorry!
    private static final Vec3i PORTALABLE_REGION_SIZE = new Vec3i(4, 6, 6);
    private static final Vec3i PORTAL_ORIGIN_RELATIVE_TO_REGION = new Vec3i(1, 0, 2);
    private final TimerGame _lavaSearchTimer = new TimerGame(5);
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(5);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(25);
    // Stored here to cache lava blacklist
    // (2026-09-04 批量舀岩浆) 原 1 桶/轮: 每浇一块黑曜石都回池子舀一次; 且倒完
    // 面朝 cast、池子常在身后, FOV/LOS 感知门控找不到源 → "Exploring for lava"
    // 越走越远 (用户"池子里明明还有岩浆", 日志 23:08:33-48 heading 102→104° 走向
    // 448,458)。改为一次囤 4 桶, 往返减 4 倍; 一趟在池边连续舀 4 个相邻源。
    private final Task _collectLavaTask = TaskCatalogue.getItemTask(Items.LAVA_BUCKET, 4);
    private final TimerGame _refreshTimer = new TimerGame(11);
    private BlockPos _portalOrigin = null;

    //private BlockPos _currentLavaTarget = null;
    private BlockPos _currentDestroyTarget = null;
    private boolean _firstSearch = false;

    @Override
    protected void onStart(AltoClef mod) {

        _currentDestroyTarget = null;

        mod.getBlockTracker().trackBlock(Blocks.LAVA);
        mod.getBehaviour().push();

        // Avoid breaking portal frame if we're obsidian.
        // Also avoid placing on the lava + water
        // Also avoid breaking the cast frame
        mod.getBehaviour().avoidBlockBreaking(block -> {
            if (_portalOrigin != null) {
                // Don't break frame
                for (Vec3i framePosRelative : PORTAL_FRAME) {
                    BlockPos framePos = _portalOrigin.add(framePosRelative);
                    if (block.equals(framePos)) {
                        return mod.getWorld().getBlockState(framePos).getBlock() == Blocks.OBSIDIAN;
                    }
                }
            }
            return false;
        });

        // Protect some used items
        mod.getBehaviour().addProtectedItems(Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);

        _progressChecker.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_refreshTimer.elapsed()) {
            Debug.logMessage("Duct tape: Refreshing inventory again just in case");
            _refreshTimer.reset();
            mod.getSlotHandler().refreshInventory();
        }

        //If too far, reset. (2026-08-23) 2000 → 100: 旧逻辑在玩家离开原点
        // 2000 格才重置, 导致 bot 在远处 (如收集岩浆处) 装完岩浆后跑回旧原点
        // 建造 — 传送门与刚收集的岩浆位置脱节 (实测: 岩浆 722,64,414 但原点
        // 在 317,34,84, 相距 400+ 格)。改成 100 格, bot 远离原点后重新就近选点。
        if (_portalOrigin != null && !_portalOrigin.isWithinDistance(mod.getPlayer().getPos(), 100)) {
            _portalOrigin = null;
            _currentDestroyTarget = null;
        }

        if (_currentDestroyTarget != null) {
            if (!WorldHelper.isSolid(mod, _currentDestroyTarget)) {
                _currentDestroyTarget = null;
            } else {
                return new DestroyBlockTask(_currentDestroyTarget);
            }
        }


        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering before retrying...");
            _progressChecker.reset();
            return _wanderTask;
        }


        // Get bucket if we don't have one.
        int bucketCount = mod.getItemStorage().getItemCount(Items.BUCKET, Items.LAVA_BUCKET, Items.WATER_BUCKET);
        if (bucketCount < 2) {
            setDebugState("Getting buckets");
            _progressChecker.reset();
            // If we have lava/water, get the inverse. Otherwise we dropped a bucket, just get a bucket.
            if (mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
                return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            } else if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                return TaskCatalogue.getItemTask(Items.LAVA_BUCKET, 1);
            }
            if (mod.getEntityTracker().itemDropped(Items.WATER_BUCKET, Items.LAVA_BUCKET)) {
                return new PickupDroppedItemTask(new ItemTarget(new Item[]{Items.WATER_BUCKET, Items.LAVA_BUCKET}, 1), true);
            }
            return TaskCatalogue.getItemTask(Items.BUCKET, 2);
        }

        // Get flint & steel if we don't have one
        if (!mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL) && !mod.getItemStorage().hasItem(Items.FIRE_CHARGE)) {
            setDebugState("Getting flint & steel");
            _progressChecker.reset();
            return TaskCatalogue.getItemTask(Items.FLINT_AND_STEEL, 1);
        }

        boolean needsToLookForPortal = _portalOrigin == null;
        if (needsToLookForPortal) {
            _progressChecker.reset();
            // Get water before searching, just for convenience.
            if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                setDebugState("Getting water");
                _progressChecker.reset();
                return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            }

            boolean foundSpot = false;

            if (_firstSearch || _lavaSearchTimer.elapsed()) {
                _firstSearch = false;
                _lavaSearchTimer.reset();
                Debug.logMessage("(Searching for lava lake with portalable spot nearby...)");
                BlockPos lavaPos = findLavaLake(mod, mod.getPlayer().getBlockPos());
                if (lavaPos != null) {
                    // We have a lava lake, set our portal origin!
                    // (2026-08-23) timeoutRange 20 → 8: 就近原则, 传送门应在岩浆
                    // 旁 5-6 格, 不在岩浆上 (用户设计)。20 会外推到很远的可放置点,
                    // 让传送门远离刚收集的岩浆。
                    BlockPos foundPortalRegion = getPortalableRegion(mod, lavaPos, mod.getPlayer().getBlockPos(), new Vec3i(-1, 0, 0), PORTALABLE_REGION_SIZE, 8);
                    if (foundPortalRegion == null) {
                        Debug.logWarning("Failed to find portalable region nearby. Consider increasing the search timeout range");
                    } else {
                        _portalOrigin = foundPortalRegion.add(PORTAL_ORIGIN_RELATIVE_TO_REGION);
                        foundSpot = true;
                    }
                } else {
                    Debug.logMessage("(lava lake not found)");
                }
            }

            if (!foundSpot) {
                // (2026-09-04 用户"wander 100 无意义, 不是走过去的导航观察探索")
                // 找不到岩浆湖时派 TimeoutWanderTask(100): 它不感知岩浆(感知靠
                // BlockTracker), 只是随机游荡/explore, 对找岩浆毫无帮助。改为派
                // _collectLavaTask (CollectLavaBucketTask) — 完整找岩浆策略(感知
                // 岩浆源/地下回地表/有界游荡), 会真正移动探索+收集岩浆桶。
                setDebugState("(timeout: Looking for lava lake, collecting lava instead)");
                return _collectLavaTask;
            }
        }

        // We have a portal, now build it.
        for (Vec3i framePosRelative : PORTAL_FRAME) {
            BlockPos framePos = _portalOrigin.add(framePosRelative);
            Block frameBlock = mod.getWorld().getBlockState(framePos).getBlock();
            if (frameBlock == Blocks.OBSIDIAN) {
                // Already satisfied, clear water above if need be.
                BlockPos waterCheck = framePos.up();
                if (mod.getWorld().getBlockState(waterCheck).getBlock() == Blocks.WATER && WorldHelper.isSourceBlock(mod, waterCheck, true)) {
                    setDebugState("Clearing water from cast");
                    return new ClearLiquidTask(waterCheck);
                }
                continue;
            }

            // Get lava early so placing it is faster
            if (!mod.getItemStorage().hasItem(Items.LAVA_BUCKET) && frameBlock != Blocks.LAVA) {
                setDebugState("Collecting lava");
                _progressChecker.reset();
                return _collectLavaTask;
            }

            // We need to place obsidian here.
            return new PlaceObsidianBucketTask(framePos);

        }

        // Now, clear the inside.
        for (Vec3i offs : PORTAL_INTERIOR) {
            BlockPos p = _portalOrigin.add(offs);
            assert MinecraftClient.getInstance().world != null;
            if (!MinecraftClient.getInstance().world.getBlockState(p).isAir()) {
                setDebugState("Clearing inside of portal");
                _currentDestroyTarget = p;
                return null;
                //return new DestroyBlockTask(p);
            }
        }

        setDebugState("Flinting and Steeling");

        // Flint and steel it baby
        return new InteractWithBlockTask(new ItemTarget(new Item[]{Items.FLINT_AND_STEEL, Items.FIRE_CHARGE}, 1), Direction.UP, _portalOrigin.down(), true);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.LAVA);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ConstructNetherPortalBucketTask;
    }

    @Override
    protected String toDebugString() {
        return "Construct Nether Portal";
    }

    private BlockPos findLavaLake(AltoClef mod, BlockPos playerPos) {
        // (2026-09-04 现场修复) 先字面扫描玩家附近: 用户实测 bot 站在地表池子旁,
        // 池子却不在 BlockTracker 记忆里 (刚舀掉源/区块未重扫), 记忆里最近的
        // 反而是 y=17 深洞岩浆 → 跑 47 格深的洞里搭门。眼前的世界比记忆优先:
        // 人在池子旁, 脚边字面扫描一扫即中, 锚点就地落在池边 ±10 格。
        BlockPos literal = findNearestLavaSourceAround(mod, playerPos, 16, 8);
        if (literal != null) {
            Debug.logMessage("(Lava anchor: literal scan found source near player at " + literal.toShortString() + ")");
            return literal;
        }
        double nearestSqDistance = Double.POSITIVE_INFINITY;
        BlockPos nearestLake = null;
        // 限制搜索距离，避免导航到过远的地方（2026-08-23）
        double MAX_SEARCH_DISTANCE = 100; // 最多搜索100格内的岩浆
        double maxSearchSqDistance = MAX_SEARCH_DISTANCE * MAX_SEARCH_DISTANCE;

        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.LAVA)) {
            double sqDist = playerPos.getSquaredDistance(pos);
            // 跳过过远的岩浆位置
            if (sqDist > maxSearchSqDistance) {
                Debug.logMessage("Skipping lava at " + pos + " (distance=" + Math.sqrt(sqDist) + " > " + MAX_SEARCH_DISTANCE + ")");
                continue;
            }
            // (2026-09-04 高度差约束) 记忆兜底不选与玩家高度差过大的岩浆:
            // 深洞岩浆 (Δy=47) 被选为锚点后 bot 会钻深洞搭门, 清障不可达 →
            // 黑名单 RESET 死循环 (2026-09-04 实测 420,17,341)。
            // 锚点应在玩家 ±16 格内; 真要下洞时 bot 走进洞后 Δy 自然变小。
            if (Math.abs(pos.getY() - playerPos.getY()) > 16) {
                Debug.logMessage("Skipping lava at " + pos + " (height diff " + Math.abs(pos.getY() - playerPos.getY()) + " > 16)");
                continue;
            }
            if (sqDist < nearestSqDistance) {
                // (2026-08-23 不要求深度) 用户设计: 去掉深度要求, 只要有岩浆
                // 就选为传送门锚点 (CAST_FRAME 造墙机制支持单格岩浆源, 不依赖
                // 湖的深浅)。旧逻辑 depth>=12 再降到 >=4 都误伤过, 直接取消。
                nearestSqDistance = sqDist;
                nearestLake = pos;
            }
        }

        return nearestLake;
    }

    /**
     * (2026-09-04) 字面扫描玩家周围找最近岩浆【源】方块 (不依赖 BlockTracker
     * 记忆, 直接查世界)。只认 still 源 (level 8), 与 getNumberOfBlocksAdjacent
     * 的源判定一致。扫描量 ~1.9 万格, 仅锚点搜索 (每 5s 一次) 时调用, 开销可接受。
     */
    private BlockPos findNearestLavaSourceAround(AltoClef mod, BlockPos center, int hRadius, int vRadius) {
        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (int dx = -hRadius; dx <= hRadius; dx++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dy = -vRadius; dy <= vRadius; dy++) {
                    BlockPos p = center.add(dx, dy, dz);
                    BlockState s = MinecraftClient.getInstance().world.getBlockState(p);
                    if (s.getBlock() != Blocks.LAVA) continue;
                    if (!s.getFluidState().isStill() || s.getFluidState().getLevel() != 8) continue;
                    double sq = p.getSquaredDistance(center);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    // Used to flood-scan for blocks of lava.
    private int getNumberOfBlocksAdjacent(HashSet<BlockPos> alreadyExplored, BlockPos origin) {
        // Base case: We already explored this one
        if (alreadyExplored.contains(origin)) return 0;
        alreadyExplored.add(origin);

        // Base case: We hit a non-full lava block.
        assert MinecraftClient.getInstance().world != null;
        BlockState s = MinecraftClient.getInstance().world.getBlockState(origin);
        if (s.getBlock() != Blocks.LAVA) {
            return 0;
        } else {
            // We may not be a full lava block
            if (!s.getFluidState().isStill()) return 0;
            int level = s.getFluidState().getLevel();
            //Debug.logMessage("TEST LEVEL: " + level + ", " + height);
            // Only accept FULL SOURCE BLOCKS
            if (level != 8) return 0;
        }

        BlockPos[] toCheck = new BlockPos[]{origin.north(), origin.south(), origin.east(), origin.west(), origin.up(), origin.down()};

        int bonus = 0;
        for (BlockPos check : toCheck) {
            // This block is new! Explore out from it.
            bonus += getNumberOfBlocksAdjacent(alreadyExplored, check);
        }

        return bonus + 1;
    }

    // Get a region that a portal can fit into
    private BlockPos getPortalableRegion(AltoClef mod, BlockPos lava, BlockPos playerPos, Vec3i sizeOffset, Vec3i sizeAllocation, int timeoutRange) {
        Vec3i[] directions = new Vec3i[]{new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)};

        double minDistanceToPlayer = Double.POSITIVE_INFINITY;
        BlockPos bestPos = null;

        for (Vec3i direction : directions) {

            // Inch along
            for (int offs = 1; offs < timeoutRange; ++offs) {

                Vec3i offset = new Vec3i(direction.getX() * offs, direction.getY() * offs, direction.getZ() * offs);

                boolean found = true;
                boolean solidFound = false;
                // check for collision with lava in box
                // We have an extra buffer to make sure we never break a block NEXT to lava.
                moveAlongLine:
                for (int dx = -1; dx < sizeAllocation.getX() + 1; ++dx) {
                    for (int dz = -1; dz < sizeAllocation.getZ() + 1; ++dz) {
                        for (int dy = -1; dy < sizeAllocation.getY(); ++dy) {
                            BlockPos toCheck = lava.add(offset).add(sizeOffset).add(dx, dy, dz);
                            assert MinecraftClient.getInstance().world != null;
                            BlockState state = MinecraftClient.getInstance().world.getBlockState(toCheck);
                            if (state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.BEDROCK) {
                                found = false;
                                break moveAlongLine;
                            }
                            // Also check for at least 1 solid block for us to place on...
                            if (dy <= 1 && !solidFound && WorldHelper.isSolid(mod, toCheck)) {
                                solidFound = true;
                            }
                        }
                    }
                }
                // (2026-08-23 无支撑也能建) 旧逻辑要求 box 内至少有 1 个实心
                // 方块 (solidFound) 才接受该位置, 否则放弃整个方向 → 平坦岩浆湖
                // 周围找不到"有地面"的位置时, getPortalableRegion 返回 null →
                // 一直 wander 找不到传送门结构。PlaceObsidianBucketTask 的
                // CAST_FRAME 会自己造支撑 (下/前后/左右/右上 6 个方向放方块围住
                // 岩浆), 所以即使地面是纯岩浆/悬空, 也能造出支撑铸造黑曜石。
                // 不再因 solidFound=false 放弃 — 优先有支撑的位置, 无支撑也接受。
                // if (!solidFound) { break; }

                if (found) {
                    BlockPos foundBoxCorner = lava.add(offset).add(sizeOffset);
                    double sqDistance = foundBoxCorner.getSquaredDistance(playerPos);
                    if (sqDistance < minDistanceToPlayer) {
                        minDistanceToPlayer = sqDistance;
                        bestPos = foundBoxCorner;
                    }
                    break;
                }
            }

        }

        return bestPos;
    }
}
