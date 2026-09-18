package adris.altoclef.tasks.construction.compound;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.ClearLiquidTask;
import adris.altoclef.tasks.construction.ClearRegionTask;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

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

    // (2026-09-18 复用已有门结构) 见 findReusablePortalStructure 头注。
    //   PORTAL_REUSE_RADIUS     = 水平搜索半径 (格); 现场旧门距玩家 ~6 格, 16 足够。
    //   PORTAL_REUSE_VERTICAL   = 垂直搜索半径 (格); 门常与玩家同层, ±8 覆盖台阶差。
    //   PORTAL_REUSE_MIN_FRAMES = 认定阈值: 门框 10 格中至少已存在几块黑曜石。
    //     4 = "搭到 40% 以上"即认; 再小会把岩浆湖边天然/残留黑曜石误当门框。
    //   扫描量 ≈ (2*16+1) * (2*8+1) * (2*16+1) ≈ 1.85 万格, 与
    //   findNearestLavaSourceAround(16, 8) 同量级 (该处注释已认定开销可接受)。
    private static final int PORTAL_REUSE_RADIUS = 16;
    private static final int PORTAL_REUSE_VERTICAL = 8;
    private static final int PORTAL_REUSE_MIN_FRAMES = 4;
    // (2026-09-18 用户"必须保证是岩浆湖附近的框") 复用候选门**缺框时**的岩浆可及半径。
    // 缺 k 块 = 要浇 k 次 = 每次都得有可舀岩浆源。PlaceObsidianBucketTask 在缺桶时会走
    // TaskCatalogue.getItemTask(LAVA_BUCKET) 全域找岩浆 —— 门若远离岩浆湖, 这条路会让
    // bot 跑很远搬岩浆, 比就地新建还贵。8 的依据: getPortalableRegion 的 timeoutRange
    // 同为 8, 即"门应在岩浆旁 5-6 格"的既有设计。
    private static final int PORTAL_REUSE_LAVA_RADIUS = 8;
    private static final int PORTAL_REUSE_LAVA_VERTICAL = 8;
    private final TimerGame _lavaSearchTimer = new TimerGame(5);
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(5);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(25);
    // Stored here to cache lava blacklist
    // (2026-09-05 回退批量囤4桶, 用户"4桶就必须要4个桶, 没必要") CollectBucketLiquidTask
    // 的 count 语义是"先凑够 N 个空桶"(4 桶=12 铁), 不划算。乱跑问题已由
    // "见过即记忆"(VisibilityHelper 贵重记忆表) 从根上解决, 回到 1 桶/趟。
    private final Task _collectLavaTask = TaskCatalogue.getItemTask(Items.LAVA_BUCKET, 1);
    private final TimerGame _refreshTimer = new TimerGame(11);
    private BlockPos _portalOrigin = null;
    // (2026-09-18 复用已有门结构) 门的朝向: 0..3 = 绕 y 轴转 0/90/180/270 度。
    // 模板 PORTAL_FRAME / PORTAL_INTERIOR 是硬编码"法线 = x 轴"的那一个朝向;
    // 复用到 worldgen 废弃门时朝向可能不同, 故所有模板消费点都要过 rotateY()。
    private int _portalRotation = 0;
    // 原点来源: true = 复用附近已有框架 (框可能已齐, 不必浇铸), false = 全新建造。
    // 唯一行为差异 = 跳过"先囤 2 个桶"的预检 (见 onTick), 因为复用场景很可能
    // 一块都不需要浇铸; 真缺框时 PlaceObsidianBucketTask 内部自会去拿桶 (它自给自足)。
    private boolean _reuseMode = false;

    //private BlockPos _currentLavaTarget = null;
    private boolean _firstSearch = false;

    @Override
    protected void onStart(AltoClef mod) {

        mod.getBlockTracker().trackBlock(Blocks.LAVA);
        mod.getBehaviour().push();

        // Avoid breaking portal frame if we're obsidian.
        // Also avoid placing on the lava + water
        // Also avoid breaking the cast frame
        mod.getBehaviour().avoidBlockBreaking(block -> {
            if (_portalOrigin != null) {
                // Don't break frame
                for (Vec3i framePosRelative : PORTAL_FRAME) {
                    BlockPos framePos = _portalOrigin.add(rotateY(framePosRelative, _portalRotation));
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
            // 原点作废 => 复用判定与朝向一并作废, 下轮重新决定。
            _reuseMode = false;
            _portalRotation = 0;
        }

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering before retrying...");
            _progressChecker.reset();
            return _wanderTask;
        }


        // Get bucket if we don't have one.
        // (2026-09-18 复用模式豁免) 复用到的框架可能已完整 —— 那就一块都不用浇铸,
        // 却仍要跑去挖铁做两个桶, 纯白跑。复用模式下跳过这道预检; 真缺框时
        // PlaceObsidianBucketTask 内部自己会去拿桶 (自给自足, 见其 onTick L77-83/L146-149)。
        int bucketCount = mod.getItemStorage().getItemCount(Items.BUCKET, Items.LAVA_BUCKET, Items.WATER_BUCKET);
        if (!_reuseMode && bucketCount < 2) {
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
            // (2026-09-18 复用模式豁免) 复用到的框架已完整时一滴水都用不上;
            // 真缺框时 PlaceObsidianBucketTask 内部自己会去拿水 (其 onTick L146-149)。
            if (!_reuseMode && !mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                setDebugState("Getting water");
                _progressChecker.reset();
                return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            }

            boolean foundSpot = false;

            if (_firstSearch || _lavaSearchTimer.elapsed()) {
                _firstSearch = false;
                _lavaSearchTimer.reset();

                // (2026-09-18 复用已有门结构) 先看附近有没有可补全的框架, 再考虑新建。
                //
                // 背景 (现场 20260918-113247 实锤): bot 上一场在 -123,77,260 把门框整个搭好、
                // 只差清内部+点燃, 会话重启后 _portalOrigin 归零, 而本类**完全不查任何门记忆**
                // (WorldMemory.getPortalLocations 只认已激活的紫块, 半成品不在内), 于是在 6 格
                // 外的 -121,74,264 又浇了一座 —— 同一个岩浆湖边并排两座门。
                // 更讽刺的是它 11:33:06 刚把旧门框记成 RUINED_PORTAL 地标 (landmark = 黑曜石),
                // 即"看见了那 10 块黑曜石, 但语义上认成了世界生成的废弃门"。
                //
                // 命中后只做一件事: 把 _portalOrigin / _portalRotation 对齐上去。
                // 下游三条逻辑天然接管 —— 框循环对"已是黑曜石"的框位本就 continue, 内部清理
                // 与点燃也只看原点, 与"这扇门是谁搭的"无关。框全齐时连岩浆都不需要。
                BlockPos reusable = findReusablePortalStructure(mod);
                if (reusable != null) {
                    // 判定明细 (frames/missing) 已由 findReusablePortalStructure 打点
                    _portalOrigin = reusable;
                    _reuseMode = true;
                    foundSpot = true;
                }

                if (!foundSpot) {
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
                            _reuseMode = false;
                            _portalRotation = 0;
                            foundSpot = true;
                        }
                    } else {
                        Debug.logMessage("(lava lake not found)");
                    }
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
            BlockPos framePos = _portalOrigin.add(rotateY(framePosRelative, _portalRotation));
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
        // (2026-09-18 门内清障改 baritone clearArea) 原实现走 `_currentDestroyTarget = p`
        // 逐格派 DestroyBlockTask 手动挖。但门是 1×3×2 空腔、四周是刚浇铸的门框黑曜石,
        // bot 站门侧看内部格时视线必经门框 → LookHelper.getReach 恒缺 → DestroyBlockTask
        // 落进 tryClearOccluder, 把门框当"挡在目标前面的挡块"清掉。
        // 现场 20260918-111256 11:19:53.361:
        //   [OccluderClear] clearing -123, 77, 260 (obsidian) blocking target=-123, 76, 259
        //   —— 那正是 1.4s 前 11:19:51.961 刚 cast 出来的门框, 门当场被挖塌。
        // 这不是偶发: 门内任何非空气格都会触发同一条"视线必经门框"的路径。
        //
        // 改法 = 整块交 baritone 的 clearArea (ClearRegionTask):
        //   ① 它消费 avoidBlockBreaking 谓词 (本任务 onStart 已注册"别挖 PORTAL_FRAME
        //      的黑曜石"), 且门框本就在清除区域之外 —— 双重保险;
        //   ② 站位与挖穿顺序由 baritone 规划, 不走"视线被挡 → 挖挡块"这条手动路径。
        //
        // 清除区域严格 = PORTAL_INTERIOR 的轴对齐包围盒 (origin .. origin+(0,2,1),
        // 即 1×3×2 = 6 格)。门框位于 (0,-1,*)/(0,3,*)/(0,*,-1)/(0,*,2), 全在盒外。
        //
        // 已知缺口: ClearRegionTask 无可达/失败兜底 (见其文件头 TODO), 若 baritone
        // 因站位问题清不动, 会停在"重派 clearArea"循环 —— 实跑请盯 [NAV-STUCK] 与
        // 任务栈是否长期停在 Clearing inside of portal。
        // 清除区域 = 旋转后 PORTAL_INTERIOR 的轴对齐包围盒。rot=0 时恰为
        // origin .. origin+(0,2,1) (1×3×2); 其余朝向不手写 4 套常量, 逐格取 min/max 推出。
        int iMinX = Integer.MAX_VALUE, iMinY = Integer.MAX_VALUE, iMinZ = Integer.MAX_VALUE;
        int iMaxX = Integer.MIN_VALUE, iMaxY = Integer.MIN_VALUE, iMaxZ = Integer.MIN_VALUE;
        for (Vec3i offs : PORTAL_INTERIOR) {
            Vec3i r = rotateY(offs, _portalRotation);
            iMinX = Math.min(iMinX, r.getX()); iMaxX = Math.max(iMaxX, r.getX());
            iMinY = Math.min(iMinY, r.getY()); iMaxY = Math.max(iMaxY, r.getY());
            iMinZ = Math.min(iMinZ, r.getZ()); iMaxZ = Math.max(iMaxZ, r.getZ());
        }
        BlockPos interiorMin = _portalOrigin.add(iMinX, iMinY, iMinZ);
        BlockPos interiorMax = _portalOrigin.add(iMaxX, iMaxY, iMaxZ);
        for (Vec3i offs : PORTAL_INTERIOR) {
            BlockPos p = _portalOrigin.add(rotateY(offs, _portalRotation));
            assert MinecraftClient.getInstance().world != null;
            if (!MinecraftClient.getInstance().world.getBlockState(p).isAir()) {
                setDebugState("Clearing inside of portal");
                return new ClearRegionTask(interiorMin, interiorMax);
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
    /**
     * (2026-09-18 用户"必须保证是岩浆湖附近的框") 统计 center 附近**可舀**的岩浆源方块数。
     * 判据与 {@link #findNearestLavaSourceAround} 完全一致 (isStill + level 8) —— 那就是
     * "能不能舀起来"的口径; 流动岩浆舀不起, 不计入。
     *
     * 用途 = 判定"这扇门是不是岩浆湖边的", 并把数量写进日志供实跑核对。
     * 为什么返回**数量**而不是布尔: 数量是诊断信息 —— 看到 `lavaNearby=1` 就知道这扇门
     * 旁边只有孤零零一个源, 值得警惕; 但**不**拿它去跟缺框数硬比 (理由见调用方 ⑤)。
     *
     * 开销 = (2h+1)^2 * (2v+1) 次 getBlockState, 与 findNearestLavaSourceAround 同量级。
     */
    private int countLavaSourcesNearby(AltoClef mod, BlockPos center, int hRadius, int vRadius) {
        int count = 0;
        for (int dx = -hRadius; dx <= hRadius; dx++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dy = -vRadius; dy <= vRadius; dy++) {
                    BlockState s = MinecraftClient.getInstance().world.getBlockState(center.add(dx, dy, dz));
                    if (s.getBlock() != Blocks.LAVA) continue;
                    if (!s.getFluidState().isStill() || s.getFluidState().getLevel() != 8) continue;
                    count++;
                }
            }
        }
        return count;
    }

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

    // ==================== (2026-09-18) 复用已有门结构 ====================

    /**
     * 绕 y 轴把模板偏移旋转 quarterTurns × 90°。
     *
     * 模板 (PORTAL_FRAME / PORTAL_INTERIOR) 只描述了**一个**朝向: 门平面法线 = x 轴,
     * 门宽 2 沿 z, 门高 3 沿 y。复用到 worldgen 废弃传送门时朝向可能不同, 故所有
     * 模板消费点 (框循环 / 内部循环 / onStart 的框保护谓词) 都必须过本函数。
     *
     * 旋转公式 (右手系, y 向上, θ=90°): (x, y, z) -> (z, y, -x)。
     * 自检: rot=1 时内部 (0,0,1) -> (1,0,0), 门宽由"沿 z"变"沿 x", 法线随之转 90° ✓
     */
    private static Vec3i rotateY(Vec3i v, int quarterTurns) {
        switch (((quarterTurns % 4) + 4) % 4) {
            case 1:  return new Vec3i(v.getZ(), v.getY(), -v.getX());
            case 2:  return new Vec3i(-v.getX(), v.getY(), -v.getZ());
            case 3:  return new Vec3i(-v.getZ(), v.getY(), v.getX());
            default: return v;
        }
    }

    /**
     * (2026-09-18) 在玩家附近找一座"可补全的门"—— 自己上一场留下的半成品, 或
     * worldgen 的废弃传送门。命中即把 {@link #_portalRotation} 设为该朝向并返回门
     * 内部左下角 ({@code _portalOrigin} 口径); 无命中返回 null (调用方照旧新建)。
     *
     * 判据 = 用刚性门框模板做拟合。门框 10 格的相对位置是固定的, 所以任取一块黑曜石
     * 假设它对应模板中的某一格、再枚举 4 个朝向, 就能反推出候选原点:
     *   {@code origin = 黑曜石 - rotateY(某个框偏移, rot)}
     * 对每个 (原点, 朝向) 组合统计**该朝向下**真正命中的框数, 取最高分且 &gt;= 阈值者。
     *
     * 为什么不用 WorldMemory.getPortalLocations(): 那套只保留 Blocks.NETHER_PORTAL
     * (已激活的紫块), 半成品门根本不在其列 —— 这正是本函数存在的理由。
     *
     * 另有两道闸门, 都偏保守 (宁可放弃复用去新建):
     *   · 歧义闸门 —— 最高分候选若落在**不同的位置**, 说明信息量不足以区分, 放弃 (见 ③);
     *   · 岩浆可及闸门 —— 缺框时必须就近有可舀岩浆源, 否则复用等于把 bot 支使到远处
     *     搬岩浆, 反而比就地新建贵 (见 ⑤)。
     *
     * 开销 ≈ 1.85 万格 getBlockState (仅"重新决定门位置"时调用, 5s 节流) + 黑曜石数×40
     * 次打分; 黑曜石通常几十块, 可忽略。
     */
    private BlockPos findReusablePortalStructure(AltoClef mod) {
        // ① 收集附近黑曜石 (门框的两种材质)
        List<BlockPos> obsidians = new ArrayList<>();
        BlockPos center = mod.getPlayer().getBlockPos();
        for (int dx = -PORTAL_REUSE_RADIUS; dx <= PORTAL_REUSE_RADIUS; dx++) {
            for (int dz = -PORTAL_REUSE_RADIUS; dz <= PORTAL_REUSE_RADIUS; dz++) {
                for (int dy = -PORTAL_REUSE_VERTICAL; dy <= PORTAL_REUSE_VERTICAL; dy++) {
                    BlockPos p = center.add(dx, dy, dz);
                    Block b = mod.getWorld().getBlockState(p).getBlock();
                    if (b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN) {
                        obsidians.add(p.toImmutable());
                    }
                }
            }
        }
        if (obsidians.isEmpty()) return null;

        // ② 逐朝向拟合。分朝向跑的原因是 score 必须"单朝向"统计: 若把 4 个朝向的候选
        //    混进同一个 map, 同一原点的命中数会被跨朝向累加而虚高。
        //    同分候选按"内部盒子最小角"归并 —— 同一组位置的不同参数化会落到同一个角。
        int bestHits = 0;
        Map<BlockPos, PortalCandidate> bestByCorner = new HashMap<>();
        for (int rot = 0; rot < 4; rot++) {
            Map<BlockPos, Integer> hits = new HashMap<>();
            for (BlockPos obs : obsidians) {
                for (Vec3i frameOff : PORTAL_FRAME) {
                    Vec3i r = rotateY(frameOff, rot);
                    BlockPos origin = obs.add(-r.getX(), -r.getY(), -r.getZ());
                    hits.merge(origin, 1, Integer::sum);
                }
            }
            for (Map.Entry<BlockPos, Integer> e : hits.entrySet()) {
                int hit = e.getValue();
                if (hit < PORTAL_REUSE_MIN_FRAMES) continue;
                BlockPos origin = e.getKey();
                if (!isPortalShapeUsable(mod, origin, rot)) continue;
                if (hit > bestHits) {
                    bestHits = hit;
                    bestByCorner.clear();
                }
                if (hit == bestHits) {
                    bestByCorner.putIfAbsent(interiorMinCorner(origin, rot),
                            new PortalCandidate(origin, rot));
                }
            }
        }
        if (bestHits == 0) return null;

        // ③ 歧义闸门 (2026-09-18 穷举验证后加)。
        //    "两根侧柱各有相邻 2 格"这类半成品 (恰好 4 块框) 存在**本质歧义**: 它既满足
        //    "门 = 当前高度", 也满足"门 = 下移一格", 两者得分相同、内部又都空 —— 信息量
        //    不足, 任何局部判据都消不掉。穷举 4 朝向的全部框子集: 恰好 4 块时 8/840 会分裂,
        //    >=5 块时 0。选错的后果是把门补到错位一格 (等于拆错方块), 所以保守放弃:
        //    宁可走新建, 也不赌。代价仅 ~1% 的"4 块框"场景不复用。
        //    已知局限: 附近若恰好有两座**框数相同**的门, 也会落到这里不复用 —— 同样偏保守。
        if (bestByCorner.size() > 1) {
            Debug.logMessage("(Portal reuse ambiguous: " + bestByCorner.size()
                    + " distinct positions @" + bestHits + " frames — skip reuse, build new)");
            return null;
        }

        // ④ 落定朝向与原点 (同角候选互为等价参数化, 取任一, 对下游完全等价)
        PortalCandidate winner = bestByCorner.values().iterator().next();

        // ⑤ 缺框 -> 必须"岩浆就在手边" (2026-09-18 用户: "就算有完成一半的框, 也必须保证
        //    是岩浆湖附近的框, 不然没有多余岩浆补齐门框")。
        //    复用与新建的**根本差别**: 新建的落点可选 (getPortalableRegion 会挑岩浆湖边的
        //    空地, 浇铸时岩浆就在脚边); 而复用的门位置是**人家给定的**, 可能远离岩浆 ——
        //    那样 bot 会被支使着来回搬岩浆。所以复用前必须确认"这扇门够得着岩浆"。
        //    放行条件 (缺框时, 满足其一): ① 背包里有岩浆桶; ② 就近(±8/±8)有可舀岩浆源。
        //    刻意**不**卡"源数量 >= 缺框数": ±8 的计数会低估 (岩浆湖常延伸到半径外, bot
        //    走几步就能舀), 硬卡会误拒掉本来划算的复用; 而且拒绝的后果是退回新建 (要浇满
        //    10 块, 需求**更大**), 反而更费岩浆。
        //    框全齐 (missing == 0) 时**不设此限**: 一块都不用浇, 复用是纯赚, 与位置远近无关。
        int missing = PORTAL_FRAME.length - bestHits;
        int lavaBuckets = mod.getItemStorage().getItemCount(Items.LAVA_BUCKET);
        int nearbySources = -1;
        if (missing > 0 && lavaBuckets == 0) {
            nearbySources = countLavaSourcesNearby(mod, winner.origin,
                    PORTAL_REUSE_LAVA_RADIUS, PORTAL_REUSE_LAVA_VERTICAL);
            if (nearbySources == 0) {
                Debug.logMessage("(Portal reuse rejected: needs " + missing + " casts but no lava "
                        + "bucket and no lava source within " + PORTAL_REUSE_LAVA_RADIUS
                        + " of " + winner.origin.toShortString()
                        + " — frame is not lava-lake-side, build new beside one)");
                return null;
            }
        }

        _portalRotation = winner.rotation;
        Debug.logMessage("(Portal reuse accepted: origin=" + winner.origin.toShortString()
                + " rot=" + winner.rotation + " frames=" + bestHits + "/" + PORTAL_FRAME.length
                + " missing=" + missing + " lavaBuckets=" + lavaBuckets
                + " lavaNearby=" + (nearbySources < 0 ? "n/a" : String.valueOf(nearbySources)) + ")");
        return winner.origin;
    }

    /**
     * (2026-09-18) 候选原点是否"像一座值得接手补全的门"。排除四类:
     *   ① 门内已有 NETHER_PORTAL 紫块 —— 那是**已激活**的门, 不该由建造任务碰
     *      (紫块硬度 -1 挖不动, 走"清内部"会当场卡死);
     *   ② 门内有岩浆 —— 浇铸前得先当障碍处理, 且会被持续回填, 不值得接手;
     *   ③ 门内有基岩 —— 挖不动, 永远清不掉, 接手就是无底洞;
     *   ④ 门内已是黑曜石 —— 框不可能长在门里面。这条是**消歧主力**: 半成品门被
     *      误解释成"下移一格/上移一格"时, 错解的"内部"必然压住真门已建成的框
     *      (穷举验证: 加上这条后, 门框 >=5 块时同分候选全部等价, 0 误判)。
     * 泥土/石头/水等一律放行: 清内部本来就是本任务的既定步骤。
     */
    private boolean isPortalShapeUsable(AltoClef mod, BlockPos origin, int rot) {
        for (Vec3i offs : PORTAL_INTERIOR) {
            Vec3i r = rotateY(offs, rot);
            Block b = mod.getWorld().getBlockState(origin.add(r.getX(), r.getY(), r.getZ())).getBlock();
            if (b == Blocks.NETHER_PORTAL || b == Blocks.LAVA || b == Blocks.BEDROCK
                    || b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN) {
                return false;
            }
        }
        return true;
    }

    /**
     * (2026-09-18) 门内部包围盒的最小角, 用作"这扇门占哪儿"的规范化指纹。
     * 同一组位置的不同参数化 (rot=0/origin=X 与 rot=2/origin=X+某偏移) 会得到
     * 同一个角 —— 正是靠它把"等价描述"合并、把"真歧义"识别出来 (见调用方)。
     */
    private static BlockPos interiorMinCorner(BlockPos origin, int rot) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (Vec3i offs : PORTAL_INTERIOR) {
            Vec3i r = rotateY(offs, rot);
            minX = Math.min(minX, r.getX());
            minY = Math.min(minY, r.getY());
            minZ = Math.min(minZ, r.getZ());
        }
        return origin.add(minX, minY, minZ);
    }

    /** 拟合出的一个候选门: 原点 + 朝向。 */
    private static final class PortalCandidate {
        final BlockPos origin;
        final int rotation;
        PortalCandidate(BlockPos origin, int rotation) {
            this.origin = origin;
            this.rotation = rotation;
        }
    }
}
