package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * Determines whether a block/entity is perceivable by a human player.
 *
 * BLOCKS (2026-08-21 relaxation): a block is perceivable when it has an
 * unoccluded line of sight, anywhere within loaded chunks — no distance cap
 * and no FOV cone. Rationale: the bot can't emulate a player constantly
 * turning their head, and MC's own render distance (loaded chunks, which is
 * all Baritone's searchWorld ever scans) is the natural perception boundary.
 * The occlusion raycast still prevents X-ray through solid rock.
 *
 * ENTITIES keep the full human model: view distance + FOV cone + angular
 * size + occlusion.
 */
public final class VisibilityHelper {

    // Human view parameters (tunable defaults) — used for ENTITIES.
    public static final double MAX_VIEW_DISTANCE = 64.0;
    public static final double FOV_HALF_DEGREES = 55.0; // half-width of our view cone (110 degrees)
    // Max ray length for block line-of-sight tests. 256 blocks = 16 chunks,
    // generous enough to cover any realistic render distance; blocks beyond
    // loaded chunks never enter the BlockTracker cache anyway.
    public static final double BLOCK_RAY_MAX_DISTANCE = 256.0;
    // Angular size below which a small object is too small to notice.
    // 0.5° keeps most mobs visible across most of the 64-block view distance
    // (a zombie/skeleton subtends ~0.5° at ~60 blocks).
    public static final double MIN_ANGULAR_SIZE_RAD = Math.toRadians(0.5);
    // Dropped items have tiny hitboxes but are eye-catching (color/contrast),
    // so they stay visible up to a fixed distance instead of an angular size test.
    public static final double ITEM_VISIBLE_DISTANCE = 32.0;

    // Common blocks a human knows are around them without needing to look:
    // stone/dirt/sand are "right under your feet". For these we relax the FOV /
    // occlusion test when they are very close.
    private static final Set<Block> EXPERIENCE_KNOWN_BLOCKS = Set.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE,
            Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF,
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.SAND,
            Blocks.GRAVEL, Blocks.NETHERRACK
    );
    // How close a common block must be to be "known" without direct sight.
    private static final double EXPERIENCE_PERCEIVE_DISTANCE = 4.0;
    // Containers within this radius of a remembered structure center are
    // inference-perceivable (chest inside a village house, behind a wall).
    private static final double STRUCTURE_INFERENCE_RADIUS = 32;
    // (2026-08-22) 工作台感知距离: 与 BeatMinecraft2Task 的"用完即收"拾取
    // 范围一致。工作台多为 bot 自己放置, 位置精确; 但矿洞里紧邻/刚放置时
    // LOS 射线判定不稳 (cleanLineOfSight 对脚下/紧贴方块不稳定), 曾导致:
    // ① 14:22 合成石镐后工作台留下不捡; ② 15:10 横跳 — 拾取检查 (cache+
    // 世界真实存在) 判定近处有工作台 → 派发 MineAndCollectTask, 但它内部
    // getNearestTracking 用 isBlockPerceivable(LOS) 找不到 16 格内工作台 →
    // 退化为挖隧道找矿 → 与"回地表收集木头"反复互相打断。工作台按感知
    // 目标原则 16 格内必感知, 消除拾取与挖掘的判定不一致。
    private static final double CRAFTING_TABLE_PERCEIVE_DISTANCE = 16.0;

    // Structure loot / functional blocks (2026-08-21 revision 2): these blocks
    // ignore the FOV cone and distance cap (see isBlockVisible), but they now
    // REQUIRE an unoccluded line of sight. The earlier "no limits at all"
    // rule let the bot X-ray dungeon spawners/chests through 40 blocks of
    // rock and dispatch loot sweeps toward underground ruins it never saw.
    // Containers keep an INFERENCE path (see isBlockPerceivable): a chest
    // near a structure the bot has actually seen is inferred to exist.
    // Objects within this many blocks of the eye are perceived no matter which
    // way the head points (e.g. a block/animal directly under your feet, which
    // would otherwise sit at ~90° and always fail the FOV cone test).
    // 6 (was 3): an ore patch 4-6 blocks below the player is squarely in a
    // human's near-field/peripheral awareness; 3 left a "blind floor" that made
    // the bot dig shafts straight past iron exposed at its feet.
    // (2026-08-23) 近矿记忆改在 MineAndCollectTask 内部 (只记真正感知过的矿),
    // 不在这里做任何"附近矿直接可感知"的放宽 — 那是透视作弊, 用户否决。
    private static final double FOV_EXEMPT_DISTANCE = 6.0;

    /**
     * Debug helper: WHY did isBlockPerceivable fail for this position?
     * Returns a human-readable reason (used by [Diag-blind] logging).
     * 2026-08-21: distance/FOV no longer reject blocks — the only remaining
     * reasons are "not a structure/experience block" and occlusion.
     */
    public static String perceivableFailReason(AltoClef mod, BlockPos pos) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null) return "no player";
        Vec3d eye = player.getCameraPosVec(1.0f);
        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        double dist = center.subtract(eye).length();
        return "occluded (ray to center blocked, dist " + (int) dist + ")";
    }

    private VisibilityHelper() {}

    /**
     * Whether a block is perceivable: visible (LOS), a bed (structure
     * insurance — through walls OK, "一墙之隔的床" 2026-08-21), a container
     * inferred near a SEEN structure ("看到村庄就知道屋里有箱子"), or a
     * common block close enough that a human would know it's there.
     *
     * 2026-08-21 revision 2 (地下遗迹透视修复): structure blocks no longer
     * bypass the occlusion raycast — a dungeon spawner/chest buried under
     * rock is NOT perceivable until the bot actually has a sightline to it.
     */
    public static boolean isBlockPerceivable(AltoClef mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        // (2026-08-23 流体表面可见感知) 水/岩浆: 流体正上方是空气 (表面暴露)
        // 且玩家能看到该空气位置 (LOS 无遮挡) 即感知。用户实测侧面看平坦岩浆
        // 湖时, LOS 到岩浆方块中心被湖边缘/地面挡住 (视线几乎平行湖面) → 判
        // 不可见; 但岩浆表面上方是空气, 只要能看到表面就能感知。比上一版
        // "暴露即感知"(任一邻位非实心) 更严格 (需要 LOS), 不会透视地下被包围
        // 的岩浆。
        if (block == Blocks.WATER || block == Blocks.LAVA) {
            return isFluidSurfaceVisible(mod, pos);
        }
        // (2026-08-23 传送门持续可感知) 下界/主世界传送门无视 FOV/距离/LOS,
        // 持续可感知 (用户设计: "传送门的位置无视FOV, 持续可感知")。传送门是
        // 维度切换的关键节点, 记忆坐标要一直能用; 且玩家通常记得自己建的
        // 传送门在哪, 不该因为背对/遮挡而"忘记"。
        if (block == Blocks.NETHER_PORTAL) {
            return true;
        }
        // Beds: 16 color variants (WHITE_BED ... BLACK_BED) — instanceof
        // covers them all. Beds are "sleep insurance": awareness through
        // walls is the whole point (床保险/睡觉寻路, 2026-08-21).
        if (block instanceof BedBlock) {
            return true;
        }
        // (2026-08-22) 工作台 16 格内必感知: 见 CRAFTING_TABLE_PERCEIVE_DISTANCE
        // 注释 (矿洞里 LOS 判定不稳 → 拾取与挖掘判定不一致 → 横跳)。
        if (block == Blocks.CRAFTING_TABLE) {
            return mod.getPlayer() != null
                    && mod.getPlayer().getBlockPos().isWithinDistance(pos, CRAFTING_TABLE_PERCEIVE_DISTANCE);
        }
        // Containers near a structure we have SEEN (village / ruined portal /
        // fortress remembered by KnowledgeMemory): inferred to exist even
        // without a direct sightline — chests sit INSIDE village houses, a
        // wall must not hide them once we've seen the village itself.
        // Underground dungeons are never remembered as structures, so their
        // buried chests stay invisible until we actually walk in.
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL) {
            if (mod.getKnowledgeMemory().isNearRememberedStructure(pos, STRUCTURE_INFERENCE_RADIUS)) {
                return true;
            }
        }
        if (isBlockVisible(mod, pos)) {
            // (2026-08-22 防透视) 普通矿石被岩石完全包围时, 即使 LOS 射线
            // 判定通过也视为不可感知 — 用户实测 19:19: 被岩石包住的铁矿
            // bot 却"透视"感知到并去挖。暴露在洞穴/通道壁的矿 (有空气面)
            // 不受影响, 正常感知。挖隧道探索挖开岩层后矿自然暴露。
            if (isBuriedOre(mod, pos, block)) return false;
            return true;
        }
        if (!EXPERIENCE_KNOWN_BLOCKS.contains(block)) return false;
        return mod.getPlayer() != null
                && mod.getPlayer().getBlockPos().isWithinDistance(pos, EXPERIENCE_PERCEIVE_DISTANCE);
    }

    /**
     * (2026-08-23) 流体 (水/岩浆) 表面可见感知: 流体正上方是空气/洞穴空气
     * (表面暴露), 且玩家能看到该上方空气位置 (LOS 无遮挡) → 可感知。被岩石
     * 完全包围的流体 (上方是实心) 或看不见表面的流体不可见。
     */
    private static boolean isFluidSurfaceVisible(AltoClef mod, BlockPos pos) {
        BlockPos above = pos.up();
        BlockState aboveState = mod.getWorld().getBlockState(above);
        if (!aboveState.isAir() && aboveState.getBlock() != Blocks.CAVE_AIR) {
            return false; // 上方不是空气 → 表面没暴露
        }
        // 能看到流体上方的空气位置 (LOS 到空气方块)
        return isBlockVisible(mod, above);
    }

    /**
     * (2026-08-22 防透视) 普通矿石的 6 个邻位是否全为实心 (被岩石完全包围)。
     * 是 → 真·看不见的矿, 拒绝感知。有任何空气/洞穴空气/液体邻位 → 暴露,
     * 正常感知。
     */
    private static boolean isBuriedOre(AltoClef mod, BlockPos pos, Block block) {
        if (!isOreBlock(block)) return false;
        for (Direction dir : Direction.values()) {
            BlockState neighbor = mod.getWorld().getBlockState(pos.offset(dir));
            if (neighbor.isAir()
                    || neighbor.getBlock() == Blocks.CAVE_AIR
                    || !neighbor.getFluidState().isEmpty()) {
                return false; // 有暴露面
            }
        }
        return true;
    }

    private static boolean isOreBlock(Block block) {
        return block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
                || block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE
                || block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE
                || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE
                || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE
                || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE
                // (2026-08-23 下界矿石防透视) 下界金矿 (NETHER_GOLD_ORE) 之前
                // 不在列表里 → isBuriedOre 防透视对它不生效 → bot 隔着岩壁"透视"
                // 感知并跑去挖看不见的下界金矿 (用户实测地狱透视)。加入下界
                // 金矿 (及石英, 防未来挖石英时同样问题)。
                || block == Blocks.NETHER_GOLD_ORE
                || block == Blocks.NETHER_QUARTZ_ORE;
    }

    /**
     * Whether a block would be visible to the player.
     *
     * Relaxed rule (2026-08-21 森林看不见修复): loaded chunks + unoccluded
     * line of sight = visible. The old 64-block distance cap and 110° FOV cone
     * rejected an entire forest sitting 80+ blocks away at the far end of the
     * loaded area — perfectly obvious to a human, invisible to the bot.
     * Distance/FOV no longer apply to blocks (see class doc); the raycast
     * still blocks "seeing" through solid rock, so no X-ray.
     */
    public static boolean isBlockVisible(AltoClef mod, BlockPos pos) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null) return false;
        // Use the BlockPos overload: it treats "ray hits the target block itself"
        // as visible. The Vec3d overload is for entities and would report the
        // target block as occluding itself (never visible).
        return LookHelper.cleanLineOfSight(player, pos, BLOCK_RAY_MAX_DISTANCE);
    }

    /**
     * (2026-08-22 贪心收集) 方块是否在玩家视野内 (与实体/现有判定一致的
     * FOV 锥, 6 格内豁免)。用户反馈"压根没看到铜 bot 却去挖" — 贪心检查
     * 只做 LOS 无 FOV 判定, 背后/侧面的矿也会触发。补上 FOV 使贪心行为
     * 与玩家观感一致。LOS 由调用方另行验证 (isBlockPerceivable)。
     */
    public static boolean isBlockInView(AltoClef mod, BlockPos pos) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null) return false;
        Vec3d eye = player.getCameraPosVec(1.0f);
        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return inFieldOfView(player, center.subtract(eye));
    }

    /**
     * Whether an entity would be visible to the player.
     */
    public static boolean isEntityVisible(AltoClef mod, Entity entity) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null) return false;
        Vec3d eye = player.getCameraPosVec(1.0f);
        Vec3d center = entity.getBoundingBox().getCenter();
        Vec3d toTarget = center.subtract(eye);
        double distSq = toTarget.lengthSquared();
        if (distSq > MAX_VIEW_DISTANCE * MAX_VIEW_DISTANCE) return false;
        if (!inFieldOfView(player, toTarget)) return false;

        double dist = Math.sqrt(distSq);
        if (entity instanceof ItemEntity) {
            // Items are tiny but eye-catching: visible up to a fixed distance.
            if (dist > ITEM_VISIBLE_DISTANCE) return false;
        } else {
            // Small + far away = too small to notice.
            double halfSize = entity.getBoundingBox().getAverageSideLength() / 2.0;
            double angularSize = Math.atan2(halfSize, dist);
            if (angularSize < MIN_ANGULAR_SIZE_RAD) return false;
        }

        return LookHelper.cleanLineOfSight(center, MAX_VIEW_DISTANCE);
    }

    private static boolean inFieldOfView(ClientPlayerEntity player, Vec3d toTarget) {
        // Very close objects (down at the player's feet, a handful of blocks away)
        // are within sight regardless of head angle.
        if (toTarget.length() <= FOV_EXEMPT_DISTANCE) return true;
        Vec3d look = player.getRotationVecClient();
        Vec3d dir = toTarget.normalize();
        return look.dotProduct(dir) >= Math.cos(Math.toRadians(FOV_HALF_DEGREES));
    }
}
