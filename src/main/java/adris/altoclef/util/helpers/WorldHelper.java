package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.mixins.ClientConnectionAccessor;
import adris.altoclef.mixins.EntityAccessor;
import adris.altoclef.util.Dimension;
import baritone.api.BaritoneAPI;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.process.MineProcess;
import baritone.utils.BlockStateInterface;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.ClientConnection;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.registry.RegistryEntry;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.*;

/**
 * Super useful helper functions for getting information about the world.
 */
public interface WorldHelper {

    // God bless 1.18
    int WORLD_CEILING_Y = 255;
    int WORLD_FLOOR_Y = 0;

    /**
     * Get the number of in-game ticks the game/world has been active for.
     *
     * (2026-08-23 崩溃修复) 主菜单/世界加载中 networkHandler 为 null,
     * 旧实现 Objects.requireNonNull 直接 NPE → 主线程崩溃 (JankDebug
     * 在主菜单也会触发 frameMs>60 → 调这里)。无连接/未进世界时返回 0。
     */
    static int getTicks() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) return 0;
        ClientConnection con = client.getNetworkHandler().getConnection();
        if (con == null) return 0;
        return ((ClientConnectionAccessor) con).getTicks();
    }

    /**
     * (2026-10-07 口径单源化, 现场 20261007-171943 实锤 bot1 背包攒出 3 水桶+1 空桶)
     * 背包内桶的三形态合计: 空桶 + 水桶 + 岩浆桶。
     * 消费方: ConstructNetherPortalBucketTask 预取闸 (预算 = 1 水 + 1 岩浆 = 2 桶容)。
     * ⚠️ CollectBucketLiquidTask 的 bucketsNeeded 是"单液体任务自需"口径 (空桶 +
     * 本液体已装桶), 故意不算另一形态已装桶 —— 水桶舀不了岩浆, 不并入本 helper。
     */
    static int countTotalBuckets(AltoClef mod) {
        return mod.getItemStorage().getItemCount(net.minecraft.item.Items.BUCKET)
                + mod.getItemStorage().getItemCount(net.minecraft.item.Items.WATER_BUCKET)
                + mod.getItemStorage().getItemCount(net.minecraft.item.Items.LAVA_BUCKET);
    }

    static Vec3d toVec3d(BlockPos pos) {
        if (pos == null) return null;
        return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    static Vec3d toVec3d(Vec3i pos) {
        return new Vec3d(pos.getX(), pos.getY(), pos.getZ());
    }

    static Vec3i toVec3i(Vec3d pos) {
        return new Vec3i(pos.getX(), pos.getY(), pos.getZ());
    }
    static BlockPos toBlockPos(Vec3d pos) {
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean isSourceBlock(AltoClef mod, BlockPos pos, boolean onlyAcceptStill) {
        BlockState s = mod.getWorld().getBlockState(pos);
        if (s.getBlock() instanceof FluidBlock) {
            // Only accept still fluids.
            if (!s.getFluidState().isStill() && onlyAcceptStill) return false;
            int level = s.getFluidState().getLevel();
            BlockState above = mod.getWorld().getBlockState(pos.up());
            if (above.getBlock() instanceof FluidBlock) {
                // (2026-09-18 上方淌水的水源) "上方是液体 → 不算源"原为排除【下落水】
                // (FallingFluid 的 getLevel() 同样返回 8) 的粗糙代理 —— 但真源与下落水
                // 可以用 isStill() 精确区分: 源 = level 8 + still(true); 下落水 =
                // level 8 + still(false)。一刀切拒绝会把"上方淌着流水/压着水"的真
                // 水源全部排除, 而这正是洞穴渗水最常见的形态 (用户: "水源被流水挡住
                // 的情况 bot 能直接收集吗" —— 当时答案是不能, 现在能)。
                return level == 8 && s.getFluidState().isStill();
            }
            return level == 8;
        }
        return false;
    }

    static double distanceXZSquared(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        return delta.x*delta.x + delta.z*delta.z;
    }
    static double distanceXZ(Vec3d from, Vec3d to) {
        return Math.sqrt(distanceXZSquared(from, to));
    }
    static boolean inRangeXZ(Vec3d from, Vec3d to, double range) {
        return distanceXZSquared(from, to) < range*range;
    }
    static boolean inRangeXZ(BlockPos from, BlockPos to, double range) {
        return inRangeXZ(toVec3d(from), toVec3d(to), range);
    }
    static boolean inRangeXZ(Entity entity, Vec3d to, double range) {
        return inRangeXZ(entity.getPos(), to, range);
    }
    static boolean inRangeXZ(Entity entity, BlockPos to, double range) {
        return inRangeXZ(entity, toVec3d(to), range);
    }
    static boolean inRangeXZ(Entity entity, Entity to, double range) {
        return inRangeXZ(entity, to.getPos(), range);
    }

    // (2026-09-30 提升为 public: 供跨包 bridge 层 SummaryGenerator / BotSnapshot
    // 复用同一维度判据，避免在 bridge 包内复制 isUltrawarm/isNatural 逻辑造成口径分叉)
    public static Dimension getCurrentDimension() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return Dimension.OVERWORLD;
        if (world.getDimension().isUltrawarm()) return Dimension.NETHER;
        if (world.getDimension().isNatural()) return Dimension.OVERWORLD;
        return Dimension.END;
    }


    // (2026-09-03 提升为 public: 供跨包 ThrowCursorTask 主动挖开封闭空间用)
    public static boolean isSolid(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).isSolidBlock(mod.getWorld(), pos);
    }

    /**
     * (2026-08-26 用户要求: 导航目标尽量是空气, 别定位到墙/方块内部) 找 target
     * 附近可站立的空气位置 (供导航用): 检查 target 及其 6 邻域, 方块本身非实心
     * (能站进去) + 脚下实心 (能站住), 取离玩家最近者。找不到返回 target 原值
     * (调用方自行兜底)。典型用途: 走到某地牢砖块/方块旁边时, 目标是"旁边的空气",
     * 而不是砖块本身 — baritone 才不会钻进墙里乱挖。
     */
    public static BlockPos findStandableAirNear(AltoClef mod, BlockPos target) {
        BlockPos player = mod.getPlayer().getBlockPos();
        BlockPos[] candidates = new BlockPos[]{
                target,
                target.up(), target.down(),
                target.north(), target.south(), target.east(), target.west()
        };
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos c : candidates) {
            // 站立格本身不能是实心 (要能站进去), 脚下要实心 (能站住)。
            if (isSolid(mod, c)) continue;
            if (!isSolid(mod, c.down())) continue;
            double d = c.getSquaredDistance(player);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best != null ? best : target;
    }

    /**
     * (2026-09-17 溯源/舀液接近点口径, 20260917-220709 场实锤) 找 liquid 附近
     * 【干燥、可站立】的地面格 —— 供"走到水流/岩浆旁边"的导航用。
     *
     * 为什么不能直接用 {@link #findStandableAirNear}: 它的候选含 target 自身,
     * 而液体格 (水/岩浆) 的 isSolidBlock 为 false → 液体格本身会被选中。玩家
     * "站进去"实际会沉到液体格内部 (BlockPos 降一层), 于是 `GoalNear(液体格上方, 0)`
     * 这类精确目标**永不满足** → baritone 全展开后 pathing=false、无路径、原地冻结
     * (现场: 玩家 (-44.51,-52,-121.77) 距目标 1 格, 4s 零位移, 把整条溯源链判死)。
     *
     * 候选 = liquid 的水平 8 邻域 (不含自身); 要求 ①自身非液体且非实心 (能站进去)
     * ②头位非实心 (不卡半砖) ③脚下实心 (能站住)。取离玩家最近者。
     *
     * @return 可站格; 四周全是液体/墙 (找不到干燥落脚点) 时返回 null, 调用方自行放弃。
     */
    public static BlockPos findStandableNearLiquid(AltoClef mod, BlockPos liquid) {
        BlockPos player = mod.getPlayer().getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos c = liquid.add(dx, 0, dz);
                if (!isDryStandable(mod, c)) continue;
                double d = c.getSquaredDistance(player);
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
        }
        return best;
    }

    /** 干燥可站立: 自身非液体且非实心, 头位非实心, 脚下实心。 */
    public static boolean isDryStandable(AltoClef mod, BlockPos pos) {
        if (mod.getWorld().getBlockState(pos).getBlock() instanceof FluidBlock) return false;
        if (isSolid(mod, pos)) return false;
        if (isSolid(mod, pos.up())) return false;
        return isSolid(mod, pos.down());
    }

    /**
     * Get the "head" of a block with a bed, if the block is a bed.
     */
    static BlockPos getBedHead(AltoClef mod, BlockPos posWithBed) {
        BlockState state = mod.getWorld().getBlockState(posWithBed);
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.get(BedBlock.FACING);
            if (mod.getWorld().getBlockState(posWithBed).get(BedBlock.PART).equals(BedPart.HEAD)) {
                return posWithBed;
            }
            return posWithBed.offset(facing);
        }
        return null;
    }
    /**
     * Get the "foot" of a block with a bed, if the block is a bed.
     */
    static BlockPos getBedFoot(AltoClef mod, BlockPos posWithBed) {
        BlockState state = mod.getWorld().getBlockState(posWithBed);
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.get(BedBlock.FACING);
            if (mod.getWorld().getBlockState(posWithBed).get(BedBlock.PART).equals(BedPart.FOOT)) {
                return posWithBed;
            }
            return posWithBed.offset(facing.getOpposite());
        }
        return null;
    }

    /**
     * (2026-10-04 多人抢床) 床是否已被别的玩家占用 (vanilla {@code BedBlock.OCCUPIED})。
     *
     * <p>玩家睡进去后服务端会把床置 OCCUPIED=true; 之后别的玩家再点这张床, vanilla
     * {@code BedBlock.onUse} 直接走 "block.minecraft.bed.occupied" 分支并 return,
     * {@code trySleep} 根本不执行 ⇒ 不管怎么点都睡不进去、重生点也不会设。
     *
     * <p>本项目此前选床只判"从某站侧能不能点到床方块"({@code bedHasClickableLine}),
     * 不判占用 ⇒ 对着别人正睡的床无限重点 (现场 20261004: 三 bot 抢一张床,
     * kangkang 在 (-886,66,563) 连点 15 次 / 30s 未睡)。占用判据单一源, 供
     * {@code PlaceBedAndSetSpawnTask}(选床睡) 与 {@code CollectBedTask}(选床拆) 共用。
     *
     * <p>两半块都查: vanilla onUse 会先 offset 到 HEAD 再读该块属性, 但不同实现可能
     * 只置一侧, 查两侧零成本更稳。
     */
    static boolean isBedOccupied(AltoClef mod, BlockPos pos) {
        if (mod == null || pos == null) return false;
        ClientWorld world = mod.getWorld();
        if (world == null) return false;
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) return false;
        if (isOccupiedBed(state)) return true;
        BlockPos other = (state.get(BedBlock.PART) == BedPart.HEAD)
                ? pos.offset(state.get(BedBlock.FACING).getOpposite())
                : pos.offset(state.get(BedBlock.FACING));
        return isOccupiedBed(world.getBlockState(other));
    }

    static boolean isOccupiedBed(BlockState state) {
        return state.getBlock() instanceof BedBlock && state.get(BedBlock.OCCUPIED);
    }

    // Get the left side of a chest, given a block pos.
    // Used to consistently identify whether a double chest is part of the same chest.
    static BlockPos getChestLeft(AltoClef mod, BlockPos posWithChest) {
        BlockState state = mod.getWorld().getBlockState(posWithChest);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.get(ChestBlock.CHEST_TYPE);
            if (type == ChestType.SINGLE || type == ChestType.LEFT) {
                return posWithChest;
            }
            Direction facing = state.get(ChestBlock.FACING);
            return posWithChest.offset(facing.rotateYCounterclockwise());
        }
        return null;
    }

    static boolean isChestBig(AltoClef mod, BlockPos posWithChest) {
        BlockState state = mod.getWorld().getBlockState(posWithChest);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.get(ChestBlock.CHEST_TYPE);
            return (type == ChestType.RIGHT || type == ChestType.LEFT);
        }
        return false;
    }

    static int getGroundHeight(AltoClef mod, int x, int z) {
        for (int y = WORLD_CEILING_Y; y >= WORLD_FLOOR_Y; --y) {
            BlockPos check = new BlockPos(x, y, z);
            if (isSolid(mod, check)) return y;
        }
        return -1;
    }

    static BlockPos getADesertTemple(AltoClef mod) {
        // (2026-09-04 人类式感知) 沙漠神殿定位是"硬决策"(确定那儿有神殿走过去),
        // 改用 getPerceivedLocations: 只认亲眼看到的压力板, 不再隔沙透视。
        for (BlockPos pos : mod.getBlockTracker().getPerceivedLocations(Blocks.STONE_PRESSURE_PLATE)) {
            if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.STONE_PRESSURE_PLATE && // Duct tape
                    mod.getWorld().getBlockState(pos.down()).getBlock() == Blocks.CUT_SANDSTONE &&
                    mod.getWorld().getBlockState(pos.down(2)).getBlock() == Blocks.TNT) {
                return pos;
            }
        }
        return null;
    }

    /**
     * 「是否需要回到地表」：本方法自 2026-09-17 起**只是 {@link SurfaceHelper}
     * 的统一判据的转发口**，自身不再含任何阈值。
     *
     * <h3>演进史（别再往回改）</h3>
     * ① 旧版 y&lt;58 + 头顶 24 格长扫描 → 站 y=63 村庄被远处屋顶扫到 1 个实心即
     * 判深 → 反复 Returning to surface 派 GetToYTask(58)（58&lt;63 反而往下挖）
     * 横跳打转（2026-08-27），遂砍成长扫描、只留 y&lt;58。
     * ② v7 (23ece415) 把"头顶无天空视线"并入本判据 → 树冠/门框/屋檐下每走一步
     * 翻转，本方法有 5+ 个共享调用点（睡觉优先 / 囤木头 / 找羊 / 扔眼），
     * 睡觉↔囤木分支每 550ms 抖三次（20260912-195954）→ 回滚。
     * ③ 2026-09-17 全项目判据统一：v7 失败的真因是【单点采样 + 零迟滞】，
     * 不是"天空口径"本身。{@link SurfaceHelper} 用 5 点多数投票抹平单列抖动
     * + 5s 驻留吸收过渡期翻面，正是 ② 缺的那两样。故本方法改为转发它，
     * y&lt;58 硬阈值（含"露天山谷 y=57 误判地下"的已知缺陷）随之废弃。
     *
     * 保留方法名而非删除：本方法有 5+ 个调用点，改名会放大改动面而无收益。
     */
    static boolean isDeepUnderground(AltoClef mod) {
        return SurfaceHelper.isBelowSurfaceDebounced(mod);
    }

    /**
     * (2026-08-22 峡谷搭高脱困) 玩家是否被困在低洼处(峡谷底/深坑): 能见
     * 天(非深地下)但周围岩壁比玩家高出很多, 步行无路可上。十字采样 4
     * 方向 × 4/8/12/16 距离的柱子, 玩家头顶 +12 格以上出现【连续 4 格
     * 实心方块】即视为岩壁(连续性要求排除了树叶/浮空桥误判)。返回需
     * 要脱困到达的目标 Y(最高岩壁顶+1), 未被困返回 -1。
     *
     * 用户设计 (2026-08-22): bot 在峡谷底收集木头, 两侧壁高 50 格,
     * BiasedExplore 在谷底永远打转 (tasks.log 11:21-11:36 "Returning to
     * surface to collect wood" ↔ "Biased exploration" 循环 16+ 次)。
     * 探索无果后应触发搭高 (GetToYTask 让 Baritone 边跳边放方块)。
     * 注意: 该策略只用于收集【地表资源】(木头/食物) 的前置 — 找矿物
     * 时往下挖是正确行为, 不适用。
     *
     * 水中无法放方块 (用户提醒): 玩家在水中时不判定为被困, 交给
     * GoToSurfaceTask 的海底退化逻辑(挖到海平面之上)处理。
     *
     * (2026-09-27 可见性放开) 由 package-private 改 public: 出洞收尾链
     * (chains.SurfaceExitChain) 需要"现在是不是蹲在浅坑/峡谷里"这同一个判据,
     * 而本仓铁律是"同一概念只留一个判据源" —— 放开可见性复用, 不另写一份。
     */
    public static int getPitEscapeY(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return -1;
        if (mod.getPlayer().isTouchingWater()) return -1;
        if (isDeepUnderground(mod)) return -1; // 深地下由常规逻辑处理
        BlockPos p = mod.getPlayer().getBlockPos();
        int scanStart = p.getY() + 12;

        // (2026-08-31 峡谷误判修复, 用户定位) 旧逻辑任一方向任一距离扫到"连续4格
        // 实心"就算被困 → 仅【单侧】高山也会误判成"峡谷深坑", 劫持"回到表面"
        // 去算动态搭高高度(日志 108→109→110 来回跳, 绕过固定表面高度58)。真正的
        // 峡谷是【两侧对向】都是高墙。改为要求"对向两个方向都扫到高墙"才判定被困:
        // 东西对向 或 南北对向, 单侧高山不再误判 → 走固定表面高度逻辑。

        // 4 个采样方向 (E, W, S, N) 各自扫出的最高岩壁顶 Y, -1 表示该方向无高墙。
        int eastTop = -1, westTop = -1, southTop = -1, northTop = -1;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int idx = 0; idx < dirs.length; ++idx) {
            int top = -1;
            for (int d : new int[]{4, 8, 12, 16}) {
                int x = p.getX() + dirs[idx][0] * d;
                int z = p.getZ() + dirs[idx][1] * d;
                int run = 0;
                int runTop = -1;
                for (int y = scanStart; y < scanStart + 60 && y < mod.getWorld().getHeight(); ++y) {
                    BlockPos check = new BlockPos(x, y, z);
                    if (!mod.getChunkTracker().isChunkLoaded(check)) break;
                    if (mod.getWorld().getBlockState(check).isOpaqueFullCube(mod.getWorld(), check)) {
                        run++;
                        runTop = y;
                        if (run >= 4 && runTop > top) {
                            top = runTop;
                        }
                    } else {
                        run = 0;
                    }
                }
            }
            if (idx == 0) eastTop = top;
            else if (idx == 1) westTop = top;
            else if (idx == 2) southTop = top;
            else northTop = top;
        }

        // 需对向成对: (E 且 W) 或 (S 且 N) 都是高墙才算被困。取两者较高者作为攀顶目标。
        int bestTop = -1;
        if (eastTop >= 0 && westTop >= 0) bestTop = Math.max(eastTop, westTop);
        if (southTop >= 0 && northTop >= 0) bestTop = Math.max(bestTop, Math.max(southTop, northTop));
        return bestTop < 0 ? -1 : bestTop + 1; // 壁顶站立位
    }

    /**
     * Finds a standing Y level on dry land at (centerX, centerZ) or in rings
     * around it (up to {@code radius} blocks out). Returns the first land
     * column's surface standing Y, or -1 when everything nearby is water or
     * unloaded (e.g. we're underneath an ocean).
     */
    static int getLandSurfaceY(AltoClef mod, int centerX, int centerZ, int radius) {
        for (int r = 0; r <= radius; ++r) {
            for (int dx = -r; dx <= r; ++dx) {
                for (int dz = -r; dz <= r; ++dz) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring cells only
                    int surfaceY = getLandColumnSurfaceY(mod, centerX + dx, centerZ + dz);
                    if (surfaceY >= 0) return surfaceY;
                }
            }
        }
        return -1;
    }

    /**
     * (2026-08-25 开船过海) 判断从 from 到 to 的直线路径上是否横跨连续
     * ≥ minWidth 格的大水面 (需要开船)。沿直线每 4 格采样一列, 该列在海平面
     * 附近 (y=58~70) 是否为水。陆地列会打断连续水域计数。
     */
    static boolean isLargeWaterCrossing(AltoClef mod, BlockPos from, BlockPos to, int minWidth) {
        Vec3d d = new Vec3d(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
        double len = Math.sqrt(d.x * d.x + d.z * d.z);
        if (len < minWidth) return false;
        Vec3d dir = d.multiply(1.0 / len);
        int bestRun = 0;
        int curRun = 0;
        for (double s = 0; s <= len; s += 4) {
            BlockPos p = new BlockPos((int) (from.getX() + dir.x * s), 0, (int) (from.getZ() + dir.z * s));
            if (isSeaLevelWater(mod, p)) {
                curRun++;
                bestRun = Math.max(bestRun, curRun);
            } else {
                curRun = 0;
            }
        }
        return bestRun * 4 >= minWidth;
    }

    /** 该列在海平面附近 (y=58~70) 是否有水 (水面)。 */
    static boolean isSeaLevelWater(AltoClef mod, BlockPos xz) {
        for (int y = 70; y >= 58; y--) {
            BlockPos c = new BlockPos(xz.getX(), y, xz.getZ());
            if (!mod.getChunkTracker().isChunkLoaded(c)) continue;
            Block b = mod.getWorld().getBlockState(c).getBlock();
            if (b == Blocks.WATER) return true;
            if (b != Blocks.AIR) return false; // 陆地/其他方块
        }
        return false;
    }

    /**
     * (2026-08-25 开船过海: 远岸目标) 从 startXZ 沿 dir (水平单位向量) 扫描,
     * 找到第一块【越过一段连续水域之后的陆地】 (即水域尽头), 作为船的远岸目标。
     * 找不到 (水域超过 maxScan) 返回 null, 调用方回退用原目标。
     */
    static BlockPos findFarShore(AltoClef mod, BlockPos startXZ, Vec3d dir, int maxScan, int minWaterWidth) {
        boolean inWater = false;
        int waterRun = 0;
        for (int s = 4; s <= maxScan; s += 4) {
            BlockPos p = new BlockPos((int) (startXZ.getX() + dir.x * s), 0, (int) (startXZ.getZ() + dir.z * s));
            if (isSeaLevelWater(mod, p)) {
                inWater = true;
                waterRun += 4;
            } else if (inWater) {
                // 水域结束 → 这是远岸 (水域宽度够才算是"跨海")。
                if (waterRun >= minWaterWidth) {
                    return p;
                }
                inWater = false;
                waterRun = 0;
            }
        }
        return null;
    }

    /**
     * Standing Y (topmost non-air block + 1) for a single column, or -1 if the
     * column is a lake/ocean (water surface) or not chunk-loaded.
     */
    static int getLandColumnSurfaceY(AltoClef mod, int x, int z) {
        for (int y = WORLD_CEILING_Y; y >= WORLD_FLOOR_Y; --y) {
            BlockPos check = new BlockPos(x, y, z);
            if (!mod.getChunkTracker().isChunkLoaded(check)) return -1;
            BlockState state = mod.getWorld().getBlockState(check);
            if (state.isAir()) continue;
            if (state.getBlock() == Blocks.WATER) return -1; // lake/ocean column
            return y + 1;
        }
        return -1;
    }

    static boolean isUnopenedChest(AltoClef mod, BlockPos pos) {
        return mod.getItemStorage().getContainerAtPosition(pos).isEmpty();
    }

    static int getGroundHeight(AltoClef mod, int x, int z, Block... groundBlocks) {
        Set<Block> possibleBlocks = new HashSet<>(Arrays.asList(groundBlocks));
        for (int y = WORLD_CEILING_Y; y >= WORLD_FLOOR_Y; --y) {
            BlockPos check = new BlockPos(x, y, z);
            if (possibleBlocks.contains(mod.getWorld().getBlockState(check).getBlock())) return y;

        }
        return -1;
    }

    // (2026-09-03 提升为 public: 供跨包 ThrowCursorTask 主动挖开封闭空间用)
    public static boolean canBreak(AltoClef mod, BlockPos pos) {
        // JANK: Temporarily check if we can break WITHOUT paused interactions.
        // Not doing this creates bugs where we loop back and forth through the nether portal and stuff.
        boolean prevInteractionPaused = mod.getExtraBaritoneSettings().isInteractionPaused();
        mod.getExtraBaritoneSettings().setInteractionPaused(false);
        boolean result = mod.getWorld().getBlockState(pos).getHardness(mod.getWorld(), pos) >= 0
                && !mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)
                && MineProcess.plausibleToBreak(new CalculationContext(mod.getClientBaritone()), pos)
                && canReach(mod, pos);
        mod.getExtraBaritoneSettings().setInteractionPaused(prevInteractionPaused);
        return result;
    }

    /**
     * (2026-09-09 23:20 床链判别日志, 零行为改动) canBreak 的四个子条件逐个
     * 报告失败点: hardness / avoidBreaking / plausibleToBreak / canReach
     * (canReach=区块可达+tracker unreachable)。仅供诊断路径调用 — 与 canBreak
     * 相同的检查顺序, 不改任何判定。返回 null = 可挖。
     */
    public static String canBreakFailReason(AltoClef mod, BlockPos pos) {
        boolean prevInteractionPaused = mod.getExtraBaritoneSettings().isInteractionPaused();
        mod.getExtraBaritoneSettings().setInteractionPaused(false);
        try {
            if (mod.getWorld().getBlockState(pos).getHardness(mod.getWorld(), pos) < 0) return "hardness<0";
            if (mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)) return "avoidBreaking";
            if (!MineProcess.plausibleToBreak(new CalculationContext(mod.getClientBaritone()), pos)) return "not-plausible";
            if (!canReach(mod, pos)) return "canReach:false(tracker-unreachable?)";
            return null;
        } finally {
            mod.getExtraBaritoneSettings().setInteractionPaused(prevInteractionPaused);
        }
    }

    /**
     * (2026-08-30 没镐别挖石头墙, 用户) 供 Baritone 寻路 avoidBlockBreak 谓词用:
     * 该方块所需采矿等级 > 当前等级 → 应避开 (没镐时石头/矿石都挖不动, 让寻路
     * 改走门)。关键: 只用 vanilla BlockState.isToolRequired (经
     * MiningRequirement.getMinimumRequirementForBlock), 【绝不能】调
     * plausibleToBreak/CalculationContext —— 它们会回调 shouldAvoidBreaking
     * (本谓词), 互相递归 → StackOverflow 崩溃 (上次实测 11:28)。可能有寻路线程
     * 读世界, 用 try/catch 兜底 (World.getBlockState 本身不抛, 未加载返回 air)。
     */
    static boolean shouldAvoidUnminable(AltoClef mod, adris.altoclef.util.MiningRequirement currentReq, BlockPos pos) {
        try {
            Block block = mod.getWorld().getBlockState(pos).getBlock();
            adris.altoclef.util.MiningRequirement blockReq = adris.altoclef.util.MiningRequirement.getMinimumRequirementForBlock(block);
            return blockReq.compareTo(currentReq) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 目标方块的 6 个相邻面是否贴着液体（水/岩浆，含流动状态）。
     * 用于"远离液体"策略：挖开贴着液体的方块会让水/岩浆灌进通道，
     * 精确堵水需要流体 BFS 反推源头 + 放置规划 + 失败重试，复杂度过高，
     * 因此挖掘目标选择和通道挖掘都回避这类方块。
     */
    public static boolean isAdjacentToLiquid(AltoClef mod, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (!mod.getWorld().getBlockState(pos.offset(dir)).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static boolean isInNetherPortal(AltoClef mod) {
        if (mod.getPlayer() == null)
            return false;
        return ((EntityAccessor)mod.getPlayer()).isInNetherPortal();
    }

    static boolean dangerousToBreakIfRightAbove(AltoClef mod, BlockPos toBreak) {
        // There might be mumbo jumbo next to it, we fall and we get killed by lava or something.
        if (MovementHelper.avoidBreaking(mod.getClientBaritone().bsi, toBreak.getX(), toBreak.getY(), toBreak.getZ(), mod.getWorld().getBlockState(toBreak))) {
            return true;
        }
        // Fall down
        for (int dy = 1; dy <= toBreak.getY() - WORLD_FLOOR_Y; ++dy) {
            BlockPos check = toBreak.down(dy);
            BlockState s = mod.getWorld().getBlockState(check);
            boolean tooFarToFall = dy > mod.getClientBaritoneSettings().maxFallHeightNoWater.value;
            // Don't fall in lava
            if (MovementHelper.isLava(s))
                return true;
            // Always fall in water
            // TODO: If there's a 1 meter thick layer of water and then a massive drop below, the bot will think it is safe.
            if (MovementHelper.isWater(s))
                return false;
            // We hit ground, depends
            if (WorldHelper.isSolid(mod, check))
                return tooFarToFall;
        }
        // At this point we probably fall through the void, so not safe.
        return true;
    }

    // (2026-09-12 v18) 包私有 → public: GoToSurfaceTask pick-escape 需要扫描
    // 身旁可放置空格 (跳过无谓的挖脚下)。判定语义不变。
    public static boolean canPlace(AltoClef mod, BlockPos pos) {
        return !mod.getExtraBaritoneSettings().shouldAvoidPlacingAt(pos)
                && canReach(mod, pos);
    }

    /**
     * (2026-10-04) 该格是否被流体淹没（上方一格是流体）—— **放置专用**判据。
     *
     * 现场 20261004-121411: bot 站在被水淹没的水底（inWater=true onGround=true
     * @-709,62,713），PlaceBlockNearbyTask 却把放置目标选在它脚下那格
     * (-709,61,713)（水面下），反复 "Trying to place" 共 79 秒：16 轮
     * LinearProgressChecker 4s 零进展 + Blacklist 同格 Try 19/4 无出口。
     * 水下放置必然失败: reachable 判空(Getting to our goal)、且通常无可依附面。
     *
     * ⚠ **禁止并入 canReach** —— canReach 的消费方含 CollectBucketLiquidTask
     * (舀水/舀岩浆)、TraceFlowingWaterToSourceTask(追水源)、
     * DoStuffInContainerTask(找容器)，一律需要在水面操作，并入会全线瘫痪。
     *
     * 性能: 只查一格 fluidState，不走 getGroundHeight 的全高度扫描(性能红线)。
     * 未加载区块的 fluidState 返回 EMPTY ⇒ 不会误拦未加载区。
     */
    static boolean isUnderwaterAt(AltoClef mod, BlockPos pos) {
        return !mod.getWorld().getFluidState(pos.up()).isEmpty();
    }

    static boolean canReach(AltoClef mod, BlockPos pos) {
        if (mod.getModSettings().shouldAvoidOcean()) {
            // 45 is roughly the ocean floor. We add 2 just cause why not.
            // This > 47 can clearly cause a stuck bug.
            if (mod.getPlayer().getY() > 47 && mod.getChunkTracker().isChunkLoaded(pos) && isOcean(mod.getWorld().getBiome(pos))) { // But if we stuck, add more oceans
                // Block is in an ocean biome. If it's below sea level...
                if (pos.getY() < 64 && getGroundHeight(mod, pos.getX(), pos.getZ(), Blocks.WATER) > pos.getY()) {
                    return false;
                }
            }
        }
        return !mod.getBlockTracker().unreachable(pos);
    }

    public static boolean isOcean(RegistryEntry<Biome> b){
        return (b.matchesKey(BiomeKeys.OCEAN)
        || b.matchesKey(BiomeKeys.COLD_OCEAN)
        || b.matchesKey(BiomeKeys.DEEP_COLD_OCEAN)
        || b.matchesKey(BiomeKeys.DEEP_OCEAN)
        || b.matchesKey(BiomeKeys.DEEP_FROZEN_OCEAN)
        || b.matchesKey(BiomeKeys.DEEP_LUKEWARM_OCEAN)
        || b.matchesKey(BiomeKeys.LUKEWARM_OCEAN)
        || b.matchesKey(BiomeKeys.WARM_OCEAN)
        || b.matchesKey(BiomeKeys.FROZEN_OCEAN));
    }

    /**
     * (2026-09-26 食物远征第 5 档兜底, 用户"应对四周都是沙漠的情况")
     * 【真荒芜】群系判定 —— 这类地形没有常规被动动物与作物产出, 作为远征目标
     * 毫无意义, 与 {@link #isOcean} 一起构成"不该去"的完整黑名单:
     *
     * <ul>
     *   <li>四档偏好池 (平原/橡木森林/白桦/深色橡木林) = 最优目标, 走
     *       {@link #isPlains} / {@link #isRichFoodForest} / {@link #isBirchForest}
     *       / {@link #isDarkForest};</li>
     *   <li><b>第 5 档兜底</b> = 既不在偏好池、也不是本判定的群系 (针叶林/热带
     *       草原/丛林/沼泽/雪原/山地…) —— 产出低于偏好池但远好过沙漠;</li>
     *   <li>本判定 true (沙漠/恶地/石岸/蘑菇岛/海洋) → 连兜底都不收, 只剩
     *       "沿锁定方位穿过去"一条路。</li>
     * </ul>
     *
     * <p>为什么必须分开: 旧口径把"不在四档偏好池"一律当荒芜, 于是"前方是针叶林"
     * 也被判成荒芜而触发折返 —— 而针叶林其实能出食物, 应该直接走过去。口径与
     * isOcean 同款 (matchesKey 读 biome 地形数据, 不涉及实体感知/透视)。
     */
    static boolean isBarrenBiome(RegistryEntry<Biome> b){
        return isOcean(b)
        || b.matchesKey(BiomeKeys.DESERT)
        || b.matchesKey(BiomeKeys.BADLANDS)
        || b.matchesKey(BiomeKeys.ERODED_BADLANDS)
        || b.matchesKey(BiomeKeys.WOODED_BADLANDS)
        || b.matchesKey(BiomeKeys.STONY_SHORE)
        || b.matchesKey(BiomeKeys.MUSHROOM_FIELDS)
        || b.matchesKey(BiomeKeys.THE_VOID);
    }

    /**
     * (2026-08-31 [FOOD-BIOME]) 平原系 biome 判定 (含向日葵平原) —— 食物富集
     * 群系【第一档】: 找食物/找动物时 bot 最优先导航到草原, 动物/作物收获概率最高。
     * 模仿 isOcean 用 matchesKey 判 biome, 无需实际地形检查。
     */
    static boolean isPlains(RegistryEntry<Biome> b){
        return (b.matchesKey(BiomeKeys.PLAINS)
        || b.matchesKey(BiomeKeys.SUNFLOWER_PLAINS));
    }

    /**
     * (2026-09-23 [FOOD-BIOME], 用户拍板"森林也是有差别的, 不是所有森林都食物
     * 丰富") 森林按食物产出再分三档, 导航选择顺序:
     * 平原 → 动物记忆点 → 橡木/花森林 → 白桦系 → 深色橡木林。
     *
     * <h3>三档的原版机制依据 (1.18.2 生成规则)</h3>
     * 被动动物自然生成需要草方块+光照&gt;8。完整导航顺序五级:
     * ①平原 ②动物记忆点(在 BiasedExploreTask.pickBiasedTarget)
     * ③橡木/花森林 ④白桦系 ⑤深色橡木林:
     * <ul>
     *   <li>第③级 {@link #isRichFoodForest} —— {@link BiomeKeys#FOREST}
     *       (橡树+桦树混交, 动物刷怪表齐全且橡树落叶掉苹果) 与
     *       {@link BiomeKeys#FLOWER_FOREST} (冠层开阔光照足, 动物正常刷)。</li>
     *   <li>第④级 {@link #isBirchForest} —— {@link BiomeKeys#BIRCH_FOREST} /
     *       {@link BiomeKeys#OLD_GROWTH_BIRCH_FOREST}: 刷怪表有动物但有效密度
     *       偏低, 白桦不掉苹果也无浆果。</li>
     *   <li>第⑤级 {@link #isDarkForest} —— {@link BiomeKeys#DARK_FOREST}:
     *       致密深色橡树冠近乎全遮光 (地面光照 4~5), 动物光照条件基本不满足,
     *       白天也刷怪; 仅有红/棕蘑菇 (不做蘑菇煲不是即食食物)。用户拍板
     *       保留在【最低档】: 只在前四级全扫不到时才去, 而非完全排除。</li>
     * </ul>
     * 注意: biome 是随区块下发的【地形数据】(与 baritone 读已加载方块同一可知性
     * 口径), 不是实体感知 —— 动物实体仍全程走 EntityTracker 的 FOV/LOS/记忆
     * 统一视力, 本判定不涉及任何透视。
     */
    static boolean isRichFoodForest(RegistryEntry<Biome> b){
        return (b.matchesKey(BiomeKeys.FOREST)
        || b.matchesKey(BiomeKeys.FLOWER_FOREST));
    }

    /** [FOOD-BIOME] 第④级森林: 白桦林 / 原始白桦林 (见 {@link #isRichFoodForest})。 */
    static boolean isBirchForest(RegistryEntry<Biome> b){
        return (b.matchesKey(BiomeKeys.BIRCH_FOREST)
        || b.matchesKey(BiomeKeys.OLD_GROWTH_BIRCH_FOREST));
    }

    /** [FOOD-BIOME] 第⑤级(最低)森林: 深色橡木林 (见 {@link #isRichFoodForest})。 */
    static boolean isDarkForest(RegistryEntry<Biome> b){
        return b.matchesKey(BiomeKeys.DARK_FOREST);
    }

    static boolean isAir(AltoClef mod, BlockPos pos) {
        // (2026-09-05 真实世界直查, 用户"挖掉之后任务还是那个方块, 卡住不动")
        // 原实现走 BlockTracker.blockIsValid(AIR/CAVE_AIR/VOID_AIR), 但
        // blockIsValid 的第一道闸门是 blockUnreachable 黑名单: 位置一旦被标
        // "不可达", 哪怕真实世界已经是空气也返回 false。恶果成环 (17:36 日志实锤):
        //   挖掉方块 → isAir 被黑名单否决=false → MovementProgressChecker 不认
        //   "已挖开" → 判无进展 → DestroyBlockTask 把位置 requestBlockUnreachable
        //   (坐实黑名单) → isFinished 永远 false → 对着空气 "Getting to block..."
        //   卡死, 悬空时反复重启 builder → "Done building" 刷屏。
        // isAir 的语义是"这格现在是不是空气", 与"值不值得走过去"无关, 改为直查
        // 真实世界; 无世界/区块未加载时维持旧语义 (视为空气)。
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return true;
        }
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            return true;
        }
        return world.getBlockState(pos).isAir();
    }

    static boolean isAir(Block block) {
        // (2026-10-03 隐形光源零关联) 光源方块 (minecraft:light, InvisibleLightHelper
        // 经 RCON 放置) 客户端语义等同空气 —— 见 LightBlockAirMixin。所有走本方法的
        // 世界扫描 (BlockTracker/MineAndCollectTask 等) 自动把光源当空气。
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR
                || block instanceof net.minecraft.block.LightBlock;
    }

    static boolean isInteractableBlock(AltoClef mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return (block instanceof ChestBlock
                || block instanceof EnderChestBlock
                || block instanceof CraftingTableBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof LoomBlock
                || block instanceof CartographyTableBlock
                || block instanceof EnchantingTableBlock
                || block instanceof RedstoneOreBlock
                || block instanceof BarrelBlock
        );
    }

    /**
     * pos 这一格是否与玩家碰撞箱相交 (即"方块放不进自己身体里"的那几格)。
     *
     * (2026-09-12 收窄) 旧实现 `pos.isWithinDistance(player.getPos(), 2)` 把
     * "离玩家 2 格以内"整片区域都判成"在自己身上", 两个后果:
     *   ① 放置选点 (PlaceBlockNearbyTask.locateClosePlacePos /
     *      getCurrentlyLookingBlockPlace) 被迫外移到 2 格外 —— bot 走到目标
     *      旁边时画面上像"往自己脚边放/放不下" (现场 20260912-084704 目标
     *      20,57,152 vs bot 站格 21,57,152);
     *   ② PillarJumpHelper 的腾空放置窗口目标恒为 feet.down() (距离必然 <2),
     *      被恒真排除 → 垫塔永远放不出方块。
     * 改为"方块格 box 与玩家碰撞箱是否相交", 只排除脚/头实际占住的格。
     */
    static boolean isInsidePlayer(AltoClef mod, BlockPos pos) {
        return mod.getPlayer().getBoundingBox().intersects(new Box(pos));
    }

    static Iterable<BlockPos> getBlocksTouchingPlayer(AltoClef mod) {
        return getBlocksTouchingBox(mod, mod.getPlayer().getBoundingBox());
    }
    
    static Iterable<BlockPos> getBlocksTouchingBox(AltoClef mod, Box box) {
        BlockPos min = new BlockPos(box.minX, box.minY, box.minZ);
        BlockPos max = new BlockPos(box.maxX, box.maxY, box.maxZ);
        return scanRegion(mod, min, max);
    }

    static Iterable<BlockPos> scanRegion(AltoClef mod, BlockPos start, BlockPos end) {
        return () -> new Iterator<>() {
            int x = start.getX(), y = start.getY(), z = start.getZ();

            @Override
            public boolean hasNext() {
                return y <= end.getY() && z <= end.getZ() && x <= end.getX();
            }

            @Override
            public BlockPos next() {
                BlockPos result = new BlockPos(x, y, z);
                ++x;
                if (x > end.getX()) {
                    x = start.getX();
                    ++z;
                    if (z > end.getZ()) {
                        z = start.getZ();
                        ++y;
                    }
                }
                return result;
            }
        };
    }

    static boolean fallingBlockSafeToBreak(BlockPos pos) {
        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());
        World w = MinecraftClient.getInstance().world;
        assert w != null;
        while (isFallingBlock(pos)) {
            if (MovementHelper.avoidBreaking(bsi, pos.getX(), pos.getY(), pos.getZ(), w.getBlockState(pos)))
                return false;
            pos = pos.up();
        }
        return true;
    }

    static boolean isFallingBlock(BlockPos pos) {
        World w = MinecraftClient.getInstance().world;
        assert w != null;
        return w.getBlockState(pos).getBlock() instanceof FallingBlock;
    }

    static Entity getSpawnerEntity(AltoClef mod, BlockPos pos) {
        BlockState state = mod.getWorld().getBlockState(pos);
        if (state.getBlock() instanceof SpawnerBlock) {
            BlockEntity be = mod.getWorld().getBlockEntity(pos);
            if (be instanceof MobSpawnerBlockEntity blockEntity) {
                return blockEntity.getLogic().getRenderedEntity(mod.getWorld());
            }
        }
        return null;
    }

    /**
     * (2026-09-09 熔炉非空检测, 客户端只读) 收炉侧线只应被【非空】熔炉触发。
     *
     * 检测两级, 都是客户端可靠可读的:
     * 1. {@code FurnaceBlock.LIT} 方块状态 — blockstate 恒同步, 燃料在烧必然非空
     *    (含"肉还在炉里烧"的关键场景);
     * 2. {@link FurnaceBlockEntity} 三槽 (0=input / 1=fuel / 2=output) — chunk 加载
     *    快照 + LIT 翻转时随方块更新重发, 兜住"烧完了但产物/燃料躺在炉里"。
     * 误判方向代价: 误判"非空"(BE 略滞后) → 走过去打开发现空的, 立即转拆炉, 代价小;
     * 误判"空"需要炉内容物在无 LIT 翻转、无 bot 交互下变化, 实际不发生。
     */
    static boolean isFurnaceNonEmpty(AltoClef mod, BlockPos pos) {
        if (mod.getWorld() == null) return false;
        if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
        BlockState state = mod.getWorld().getBlockState(pos);
        if (state.getBlock() != Blocks.FURNACE) return false;
        if (state.get(FurnaceBlock.LIT)) return true;
        BlockEntity be = mod.getWorld().getBlockEntity(pos);
        if (be instanceof FurnaceBlockEntity furnace) {
            for (int slot = 0; slot < 3; slot++) {
                if (!furnace.getStack(slot).isEmpty()) return true;
            }
        }
        return false;
    }

    static Vec3d getOverworldPosition(Vec3d pos) {
        if (getCurrentDimension() == Dimension.NETHER) {
            pos = pos.multiply(8.0, 1, 8.0);
        }
        return pos;
    }
    static BlockPos getOverworldPosition(BlockPos pos) {
        if (getCurrentDimension() == Dimension.NETHER) {
            pos = new BlockPos(pos.getX()*8, pos.getY(), pos.getZ()*8);
        }
        return pos;
    }

    static boolean isChest(AltoClef mod, BlockPos block) {
        Block b = mod.getWorld().getBlockState(block).getBlock();
        return isChest(b);
    }
    static boolean isChest(Block b) {
        return b instanceof ChestBlock || b instanceof EnderChestBlock;
    }

    static boolean isBlock(AltoClef mod, BlockPos pos, Block block) {
        return mod.getWorld().getBlockState(pos).getBlock() == block;
    }

    static boolean canSleep() {
        int time = 0;
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world != null) {
            // You can sleep during thunderstorms
            if (world.isThundering() && world.isRaining())
                return true;
            time = (int)(world.getTimeOfDay() % 24000);
        }
        // https://minecraft.fandom.com/wiki/Daylight_cycle
        // (2026-10-04 白天还点床) 旧上界 23992 是错的: vanilla 可睡上界是 23458
        // (ServerPlayerEntity.startSleepInBed 的 isDay 判据)。23459~23992 这段
        // 太阳已升起、服务端必拒, mod 却以为还是夜里继续点床 (1004 15:29 实锤:
        // time=23600→23980 期间连点 8 次, 每次服务端只回"已设置重生点")。
        return 12542 <= time && time <= 23458;
    }

    /**
     * (2026-10-04) 非雷暴且时间确定性地处于 vanilla 不可睡时段 (夜窗
     * 12542..23458 之外)。与 {@link #canSleep()}=false 的区别: 排除 world
     * 未加载/时间未同步的暂态 —— 只有这种"确定性关闭"才允许立即撕掉上游
     * 30s 雷暴去抖宽限 (BeatMinecraft2Task effectiveCanSleep), 否则天亮后
     * 还会再点 30s 必被服务端拒绝的床。
     */
    static boolean isVanillaSleepWindowTimeClosed() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return false;
        if (world.isThundering() && world.isRaining()) return false;
        int time = (int)(world.getTimeOfDay() % 24000);
        return time < 12542 || time > 23458;
    }

    /**
     * (2026-10-04) 当前"可睡"是否由雷暴豁免撑起 (vanilla: 雷暴白天也可睡)。
     * 用于区分 30s 去抖宽限该护谁 —— 雷暴 isThundering 秒级抖动 vs 夜→昼的
     * 确定性时间转换 (后者不吃宽限)。
     */
    static boolean isSleepByThunder() {
        ClientWorld world = MinecraftClient.getInstance().world;
        return world != null && world.isThundering() && world.isRaining();
    }

    /**
     * (2026-08-22 睡醒判定) 晴朗白天区间: 睡觉成功的自然结果 (时间跳回
     * 早晨), 睡意应立即解除, 而非等 30s 去抖宽限自然过期。雷暴天除外
     * (雷暴白天也可睡, 不算"睡醒")。
     */
    static boolean isBrightDaytime() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return false;
        if (world.isThundering() && world.isRaining()) return false;
        int time = (int)(world.getTimeOfDay() % 24000);
        return time < 12542;
    }

    /**
     * 把当前 MC 准星 (`crosshairTarget`) 指向什么渲染成一行短文本, 供诊断日志使用。
     *
     * (2026-09-28 提取) 原先只存在于 `PlaceBedAndSetSpawnTask` 的私有实现; 现在
     * "卡住挥击反应" (`SwingThroughChain`) 也要用 → 提到这里**两处共用, 不留副本**。
     *
     * **刻意读 MC 的 `crosshairTarget` 而不是自己算射线**: 前者才是决定 vanilla
     * `doItemUse()` 走 `BLOCK` 分支还是 `MISS` 分支的那份数据
     * (判据同源铁律, 见 detail/03-consistency.md 第 3/4 节)。
     */
    static String describeCrosshair(AltoClef mod) {
        HitResult target = MinecraftClient.getInstance().crosshairTarget;
        if (target == null) return "null";
        if (target instanceof EntityHitResult ehr) {
            return "entity:" + ehr.getEntity().getType().getName().getString();
        }
        if (target instanceof BlockHitResult bhr) {
            if (bhr.getType() == HitResult.Type.MISS) return "MISS";
            return mod.getWorld().getBlockState(bhr.getBlockPos()).getBlock().getName().getString()
                    + "@" + bhr.getBlockPos().toShortString();
        }
        return String.valueOf(target.getType());
    }
}
