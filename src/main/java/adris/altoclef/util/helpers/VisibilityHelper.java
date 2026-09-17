package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    // (2026-09-03) 方块 LOS 短缓存: 同一 BlockPos 的射线结果缓存
    // LOS_CACHE_TTL_MS, 之后退化为查表。见 isBlockVisible 注释。
    private static final Map<BlockPos, long[]> _losCache = new HashMap<>();
    private static final Object _losCacheLock = new Object();
    private static final long LOS_CACHE_TTL_MS = 400;
    private static final int LOS_CACHE_MAX_ENTRIES = 4096;
    // (2026-09-04) LOS 缓存按玩家位置失效: 玩家位移超过该距离(平方)即重算。
    // 2 格 = 移动/挖洞后立即重新评估, 杜绝用旧位置的 LOS 结果造成短暂透视。
    private static final long LOS_CACHE_MOVE_DRIFT_SQ = 4;

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
    // (2026-09-16 石头类放宽, 用户"站在树上感知不到地下的石头, 石头肯定在地下呀")
    // 石头家族是【连续分布的地层介质】: 站在树上/地表, 脚下十几格内必然是石头,
    // 这是人类常识级推断, 不需要亲眼看到那一格。原 4 格豁免太短 (用户实测站在
    // 树上感知不到地下石头)。放宽到 16 格球形、不限方向 —— 与 MineAndCollectTask
    // 的世界扫描半径 (16) 同口径, 主世界/洞穴四周也一并覆盖。
    // 边界: 只放宽石头【介质】, 稀有矿/容器/流体/床仍走各自门控, 不给透视。
    private static final Set<Block> STONE_MEDIUM_BLOCKS = Set.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
            Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE,
            Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF,
            Blocks.BLACKSTONE, Blocks.BASALT, Blocks.NETHERRACK
    );
    private static final double STONE_PERCEIVE_DISTANCE = 16.0;
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

    // (2026-09-09 树木结构推断, 用户"我都看到树了它还没看到木头") 原木类方块
    // 集合。与 MineAndCollectTask.WOOD_LOG_BLOCKS / WorldMemory.WOOD_LOG_BLOCKS
    // 口径一致 (三处各自私有, 本项目既有惯例)。
    private static final Set<Block> WOOD_BLOCKS = Set.of(
            Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG,
            Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
            Blocks.CRIMSON_STEM, Blocks.WARPED_STEM,
            Blocks.MUSHROOM_STEM,
            Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_SPRUCE_LOG,
            Blocks.STRIPPED_JUNGLE_LOG, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_DARK_OAK_LOG,
            Blocks.STRIPPED_CRIMSON_STEM, Blocks.STRIPPED_WARPED_STEM,
            Blocks.OAK_WOOD, Blocks.BIRCH_WOOD, Blocks.SPRUCE_WOOD,
            Blocks.JUNGLE_WOOD, Blocks.ACACIA_WOOD, Blocks.DARK_OAK_WOOD,
            Blocks.CRIMSON_HYPHAE, Blocks.WARPED_HYPHAE
    );
    // 树叶透明 LOS 射线的最大距离。原木感知选取半径最大 64 格 (MineAndCollectTask
    // wood cacheRange), 超过 64 的原木不会被选取, 无需为它们步进射线。
    private static final double WOOD_RAY_MAX_DISTANCE = 64.0;
    // 射线步进采样步长 (格): 0.5 格保证 1 格宽的原木体内至少落一个采样点。
    private static final double WOOD_RAY_STEP = 0.5;
    // 视为"透明"的树冠方块: 树叶 + 下界巨型菌盖 + 蘑菇盖。这些方块只存在于
    // 树的可见部分, 本身就是"这有一棵树"的视觉证据 — 穿过树冠 ≠ 穿过岩壁。
    private static final Set<Block> CANOPY_OCCLUDERS = Set.of(
            Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK,
            Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK
            // LeavesBlock 类型在射线逻辑里用 instanceof 判定, 不在此列
    );

    public static boolean isWoodBlock(Block block) {
        return WOOD_BLOCKS.contains(block);
    }

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

    // (2026-09-04 贵重物资"见过即记忆", 用户"岩浆/床/重要矿石见过之后就应存在
    // 记忆中, 可以一直感知, 不用管限制") 真正感知过 (isBlockPerceivable 通过 =
    // 有真视线/表面暴露, 非透视) 一次的贵重方块进长期记忆; 之后即使背对/被
    // 自己搭的建筑挡住, 也可凭记忆导航回取 — 人看到过岩浆池就记得它在哪。
    // 反透视边界不变: 记忆只收【真感知过】的坐标, 没见过的绝不进表。
    // 按维度+方块类型分表 (同名坐标不同维度不冲突); 上限截断防大岩浆湖刷爆内存。
    private static final Map<String, Map<Block, Set<BlockPos>>> _valuableMemory = new HashMap<>();
    private static final Object _valuableMemoryLock = new Object();
    private static final int VALUABLE_MEMORY_CAP_PER_DIM = 512;

    private static boolean isValuableBlock(Block block) {
        return block == Blocks.LAVA || block == Blocks.WATER
                || block == Blocks.ANCIENT_DEBRIS || block == Blocks.SPAWNER
                || block instanceof BedBlock
                || isOreBlock(block);
    }

    private static void rememberValuable(AltoClef mod, Block block, BlockPos pos) {
        String dim = WorldHelper.getCurrentDimension().name();
        synchronized (_valuableMemoryLock) {
            Map<Block, Set<BlockPos>> byBlock = _valuableMemory.computeIfAbsent(dim, k -> new HashMap<>());
            Set<BlockPos> set = byBlock.computeIfAbsent(block, k -> new HashSet<>());
            int total = 0;
            for (Set<BlockPos> s : byBlock.values()) total += s.size();
            if (total >= VALUABLE_MEMORY_CAP_PER_DIM) {
                byBlock.clear(); // 粗暴截断: 最坏情况丢记忆重新学, 换内存有界
                set = byBlock.computeIfAbsent(block, k -> new HashSet<>());
            }
            // (2026-09-17 数据集录制) 真正新增才推进版本号 — 录制器据此只写变化。
            if (set.add(pos.toImmutable())) {
                _valuableRevision++;
            }
        }
    }

    /**
     * (2026-09-04) 该坐标的贵重方块是否曾被真正感知过 (进过"见过即记忆"表)。
     * 供收集类任务放宽 FOV/LOS 门控: 凭记忆走回去, 不要求此刻可见。
     */
    public static boolean isValuableRemembered(AltoClef mod, Block block, BlockPos pos) {
        String dim = WorldHelper.getCurrentDimension().name();
        synchronized (_valuableMemoryLock) {
            Map<Block, Set<BlockPos>> byBlock = _valuableMemory.get(dim);
            if (byBlock == null) return false;
            Set<BlockPos> set = byBlock.get(block);
            return set != null && set.contains(pos.toImmutable());
        }
    }

    /**
     * (2026-09-05) 取该维度该方块类型的全部"见过即记忆"坐标快照。
     * 感知列表 (BlockTracker) 有重扫周期/LRU, 池子可能被挤出列表 —— 记忆表
     * 必须能独立提供候选, 否则"见过"的池子在重扫间隙一样会"失忆"乱跑
     * (实测 2026-09-05 11:28:38: 重扫瞬间 found→lost, 触发 Exploring)。
     */
    public static Set<BlockPos> getRememberedValuable(AltoClef mod, Block block) {
        String dim = WorldHelper.getCurrentDimension().name();
        synchronized (_valuableMemoryLock) {
            Map<Block, Set<BlockPos>> byBlock = _valuableMemory.get(dim);
            if (byBlock == null) return Set.of();
            Set<BlockPos> set = byBlock.get(block);
            return set != null ? Set.copyOf(set) : Set.of();
        }
    }

    // (2026-09-17 数据集录制) 记忆写版本号: rememberValuable 真正【新增】一条时 +1。
    // 录制器据此判断"记忆有没有变" — 变了才写记忆快照, 没变就不写 (计划里
    // "记忆通道只写变化" 的实现方式)。volatile 保证录制线程/主线程可见。
    private static volatile long _valuableRevision = 0;

    public static long getValuableMemoryRevision() {
        return _valuableRevision;
    }

    /**
     * (2026-09-17 数据集录制) 当前维度"见过即记忆"贵重方块的全量快照
     * (方块注册名 → 坐标列表)。只读导出, 供记忆通道序列化。
     */
    public static java.util.Map<String, java.util.List<BlockPos>> dumpValuableMemory() {
        java.util.Map<String, java.util.List<BlockPos>> out = new java.util.LinkedHashMap<>();
        String dim = WorldHelper.getCurrentDimension().name();
        synchronized (_valuableMemoryLock) {
            Map<Block, Set<BlockPos>> byBlock = _valuableMemory.get(dim);
            if (byBlock == null) return out;
            for (Map.Entry<Block, Set<BlockPos>> e : byBlock.entrySet()) {
                String id;
                try {
                    id = net.minecraft.util.registry.Registry.BLOCK.getId(e.getKey()).toString();
                } catch (Throwable t) {
                    id = String.valueOf(e.getKey());
                }
                out.put("valuable:" + id, new java.util.ArrayList<>(e.getValue()));
            }
        }
        return out;
    }

    /**
     * Whether a block is perceivable: visible (LOS), a bed (structure
     * insurance — through walls OK, "一墙之隔的床" 2026-08-21), a container
     * inferred near a SEEN structure ("看到村庄就知道屋里有箱子"), or a
     * common block close enough that a human would know it's there.
     *
     * 2026-08-21 revision 2 (地下遗迹透视修复): structure blocks no longer
     * bypass the occlusion raycast — a dungeon spawner/chest buried under
     * rock is NOT perceivable until the bot actually has a sightline to it.
     *
     * (2026-09-04 见过即记忆) 判定通过时, 贵重方块 (岩浆/水/矿/床/刷怪笼/
     * 远古残骸) 自动记入长期记忆表 — 供后续"此刻看不见但见过"的场景使用。
     */
    public static boolean isBlockPerceivable(AltoClef mod, BlockPos pos) {
        String source = perceiveSourceInner(mod, pos);
        if (source != null) {
            Block seen = mod.getWorld().getBlockState(pos).getBlock();
            if (isValuableBlock(seen)) {
                rememberValuable(mod, seen, pos);
            }
            return true;
        }
        return false;
    }

    /**
     * (2026-09-17 数据集录制) 该坐标【为什么】可感知 —— 返回感知来源标签, 不可感知
     * 返回 null。这是 {@link #isBlockPerceivableInner} 的公开只读视图, 与判定逻辑
     * 共用同一份分支实现 (单一事实来源, 不会出现"判定改了分类没改"的漂移)。
     *
     * <p>标签含义 (数据集用 "observed / inferred / remembered" 三级归纳):
     * <ul>
     *   <li>{@code los} / {@code fluid_los} / {@code fluid_surface} → observed (真视线)</li>
     *   <li>{@code portal} / {@code bed} → observed (显式无视遮挡的结构保险, 人类知识)</li>
     *   <li>{@code crafting_table_near} / {@code stone_medium} / {@code experience}
     *       / {@code canopy_inference} / {@code structure_inference} → inferred (推断)</li>
     * </ul>
     *
     * <p>纯只读: 不写 _valuableMemory (记忆写入由 isBlockPerceivable 负责)。
     */
    public static String perceiveSource(AltoClef mod, BlockPos pos) {
        return perceiveSourceInner(mod, pos);
    }

    private static String perceiveSourceInner(AltoClef mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        // (2026-09-14 远距 LOS 假阳性定案) 射线射程之外的目标,"LOS 通过"是空洞证据。
        // 根因: LookHelper.raycast 对 |end-start| > maxRange 的目标会把终点**截断**到
        // maxRange 再 cast (LookHelper.java:211-213) → 目标永远不在射线的可达范围内 →
        // raycast 必然 MISS/命中别的方块 → 而 cleanLineOfSight(Entity,BlockPos,double)
        // 对 MISS 直接 `return true`(LookHelper.java:150-153, "保持原语义: 视为可见")。
        // 于是"392 格外的熔炉是否可感知"退化成"玩家眼前 256 格内有没有遮挡":
        // bot 在开阔地/沿通道 → true; 挖进隧道 → false。实测 (20260914-132420)
        // isItemOwned(USE,FURNACE) 随之 1~4s 翻面 → DoStuffInContainerTask L194/L195
        // 在"走去 392 格外的炉"与"现做炉+挖圆石"之间横跳。
        // 修法: 超出射程即判不可感知 (fail-closed)。只收紧不放宽 —— portal/bed 的
        // "显式无视距离"在下方分支里, 不受影响; 其余分支自带的距离上限
        // (工作台 16/结构推断 32/原木 64) 都远小于射程, 行为不变。
        // 注意: 不能去改 cleanLineOfSight 的 MISS→true — isFluidSurfaceVisible 的目标
        // 就是流体上方的空气块, 正是靠 MISS 才成立, 改了会破坏流体感知。
        boolean withinRayRange = isWithinRayRange(mod, pos);
        // (2026-08-23 流体表面可见感知) 水/岩浆: 流体正上方是空气 (表面暴露)
        // 且玩家能看到该空气位置 (LOS 无遮挡) 即感知。用户实测侧面看平坦岩浆
        // 湖时, LOS 到岩浆方块中心被湖边缘/地面挡住 (视线几乎平行湖面) → 判
        // 不可见; 但岩浆表面上方是空气, 只要能看到表面就能感知。比上一版
        // "暴露即感知"(任一邻位非实心) 更严格 (需要 LOS), 不会透视地下被包围
        // 的岩浆。
        // (2026-09-03 洞穴侧视水修复, 用户"地下看到水却被回地面抢走") 旧实现只认
        // "表面可见", 洞穴里从侧面直视图里的一汪水, 若其正上方贴着岩壁(上方非空气
        // → isFluidSurfaceVisible=false) 就被判"不可感知" → 不收集 → 落到回地表。
        // 明明看到了却装不了水。改: 流体方块【自身 LOS 可见】(侧视直见) 或【表面
        // 暴露可见】任一满足即可感知 — 两者都带 LOS, 不会透视墙后/被岩石完全包围
        // 的流体。
        // (2026-09-04 透视岩浆修复, 用户"隔着岩壁/被包裹的岩浆也感知+跳进岩浆")
        // 仅"自身 LOS 可见"不够: 6面/边缘判定在薄岩壁、拐角等几何下可能对隔墙流体
        // 误判可见 → 被岩石包裹/隔岩壁的岩浆也被感知 (透视)。加固: 流体感知必须
        // 【先通过"表面暴露"前置】= 流体正上方是空气/洞穴空气。被岩石完全包裹或
        // 隔岩壁的岩浆, 其上方必然是岩石(表面被封) → 前置失败 → 绝不感知。洞穴里
        // 侧视可见的水池/岩浆 (上方是空气) 不受影响, 仍可感知收集。
        if (block == Blocks.WATER || block == Blocks.LAVA) {
            if (!withinRayRange) return null; // 射程外: LOS 证据空洞, 防远距流体假阳性
            BlockPos above = pos.up();
            BlockState aboveState = mod.getWorld().getBlockState(above);
            if (!aboveState.isAir() && aboveState.getBlock() != Blocks.CAVE_AIR) {
                return null; // 表面未暴露 (被岩石封顶/被包裹) → 不感知, 防透视
            }
            if (isBlockVisible(mod, pos)) return "fluid_los";   // 侧视直见流体方块本身
            return isFluidSurfaceVisible(mod, pos) ? "fluid_surface" : null;
        }
        // (2026-08-23 传送门持续可感知) 下界/主世界传送门无视 FOV/距离/LOS,
        // 持续可感知 (用户设计: "传送门的位置无视FOV, 持续可感知")。传送门是
        // 维度切换的关键节点, 记忆坐标要一直能用; 且玩家通常记得自己建的
        // 传送门在哪, 不该因为背对/遮挡而"忘记"。
        if (block == Blocks.NETHER_PORTAL) {
            return "portal";
        }
        // Beds: 16 color variants (WHITE_BED ... BLACK_BED) — instanceof
        // covers them all. Beds are "sleep insurance": awareness through
        // walls is the whole point (床保险/睡觉寻路, 2026-08-21).
        if (block instanceof BedBlock) {
            return "bed";
        }
        // (2026-08-22) 工作台 16 格内必感知: 见 CRAFTING_TABLE_PERCEIVE_DISTANCE
        // 注释 (矿洞里 LOS 判定不稳 → 拾取与挖掘判定不一致 → 横跳)。
        if (block == Blocks.CRAFTING_TABLE) {
            return (mod.getPlayer() != null
                    && mod.getPlayer().getBlockPos().isWithinDistance(pos, CRAFTING_TABLE_PERCEIVE_DISTANCE))
                    ? "crafting_table_near" : null;
        }
        // Containers near a structure we have SEEN (village / ruined portal /
        // fortress remembered by KnowledgeMemory): inferred to exist even
        // without a direct sightline — chests sit INSIDE village houses, a
        // wall must not hide them once we've seen the village itself.
        // Underground dungeons are never remembered as structures, so their
        // buried chests stay invisible until we actually walk in.
        // (2026-09-13 用户"堡垒里面的箱子必须看见才能触发贪婪开箱") 推断豁免
        // 只留主世界: 下界堡垒因"看见下界砖"被记住后, 32 格内【从未看见过】
        // 的箱子/木桶也全被推断为可感知 → 就近搜刮隔墙开箱 (透视, 日志实锤
        // 23:05:26 Looting a container)。下界容器一律走下方真实 LOS
        // (isBlockVisible), 亲眼看见才进贪婪开箱。
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL) {
            if (WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.OVERWORLD
                    && mod.getKnowledgeMemory().isNearRememberedStructure(pos, STRUCTURE_INFERENCE_RADIUS)) {
                return "structure_inference";
            }
        }
        // (2026-09-09 树木结构推断, 用户"我都看到树了它还没看到木头") 原木目标:
        // 树叶透明 LOS。人看到整棵树 (树冠可见) 就推断树干有木头 — 这是反透视
        // 线索优先级里的"结构推断"层级, 不是 X-ray: 树叶只存在于树的可见部分,
        // 穿过树冠看到自家树干 ≠ 穿过岩壁透视。21:07 日志实锤: 353 个缓存原木
        // 全部被树冠挡住 (perceivable=0, occluded ray to center) → bot 在林边
        // 瞎逛 4 分钟不砍树。
        if (isWoodBlock(block) && isWoodVisibleThroughLeaves(mod, pos)) {
            return "canopy_inference";
        }
        if (!withinRayRange) return null; // (2026-09-14) 射程外: 不让 isBlockVisible 的 MISS 兜底放行
        if (isBlockVisible(mod, pos)) {
            // (2026-08-22 防透视) 普通矿石被岩石完全包围时, 即使 LOS 射线
            // 判定通过也视为不可感知 — 用户实测 19:19: 被岩石包住的铁矿
            // bot 却"透视"感知到并去挖。暴露在洞穴/通道壁的矿 (有空气面)
            // 不受影响, 正常感知。挖隧道探索挖开岩层后矿自然暴露。
            if (isBuriedOre(mod, pos, block)) return null;
            return "los";
        }
        // (2026-09-16 石头介质 16 格豁免) 石头家族优先走放宽口径: 只要不是
        // EXPERIENCE_KNOWN_BLOCKS 成员 (如 MOSSY_COBBLESTONE/BLACKSTONE/BASALT)
        // 也一并放行, 它们同属地层介质, 判定一致。
        if (STONE_MEDIUM_BLOCKS.contains(block)) {
            return (mod.getPlayer() != null
                    && mod.getPlayer().getBlockPos().isWithinDistance(pos, STONE_PERCEIVE_DISTANCE))
                    ? "stone_medium" : null;
        }
        if (!EXPERIENCE_KNOWN_BLOCKS.contains(block)) return null;
        return (mod.getPlayer() != null
                && mod.getPlayer().getBlockPos().isWithinDistance(pos, EXPERIENCE_PERCEIVE_DISTANCE))
                ? "experience" : null;
    }

    /**
     * (2026-09-14) 目标是否落在 LOS 射线的可达射程内 (眼睛到方块中心)。
     *
     * 超出 {@link #BLOCK_RAY_MAX_DISTANCE} 时, 射线会被截断而够不到目标,
     * {@link LookHelper#cleanLineOfSight} 的 MISS 分支依然返回 true —— 那是空洞
     * 证据, 必须在调用侧挡住 (见 isBlockPerceivableInner 顶部注释)。
     */
    private static boolean isWithinRayRange(AltoClef mod, BlockPos pos) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null) return false;
        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return player.getCameraPosVec(1.0f).squaredDistanceTo(center)
                <= BLOCK_RAY_MAX_DISTANCE * BLOCK_RAY_MAX_DISTANCE;
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
     * (2026-09-09 树木结构推断, 用户"我都看到树了它还没看到木头") 原木目标的
     * 树叶透明 LOS: 从眼睛向目标原木步进采样, 树叶/菌盖等树冠方块视为透明,
     * 其他实心方块仍算遮挡。人看到树冠即推断树干存在 (反透视"结构推断"层级);
     * 树冠只生成在树的可见部位, 穿过树冠不等于隔墙透视。
     * 距离超 WOOD_RAY_MAX_DISTANCE (64) 时直接 false — 原木选取半径不超过 64,
     * 更远的原木无需步进射线 (走普通严格 LOS 判定)。
     */
    private static boolean isWoodVisibleThroughLeaves(AltoClef mod, BlockPos pos) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null || mod.getWorld() == null) return false;
        Vec3d eye = LookHelper.getCameraPos(player);
        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3d delta = center.subtract(eye);
        double dist = delta.length();
        if (dist > WOOD_RAY_MAX_DISTANCE) return false;
        Vec3d dir = delta.multiply(1.0 / dist);
        for (double t = WOOD_RAY_STEP; t < dist; t += WOOD_RAY_STEP) {
            Vec3d p = eye.add(dir.multiply(t));
            BlockPos bp = new BlockPos(p.x, p.y, p.z);
            if (bp.equals(pos)) return true; // 到达目标原木
            BlockState st = mod.getWorld().getBlockState(bp);
            Block b = st.getBlock();
            // 树冠透明: 树叶 (所有变种) + 下界菌盖 + 蘑菇盖
            if (b instanceof LeavesBlock || CANOPY_OCCLUDERS.contains(b)) continue;
            if (!st.isSolidBlock(mod.getWorld(), bp)) continue; // 空气/草等非实心不挡
            return false; // 其他实心方块 → 遮挡 (岩壁/泥土/其他树的树干等)
        }
        // 走完无遮挡 (终点紧贴目标块边缘) → 可见
        return true;
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
     *
     * (2026-09-03 LOS 短缓存, 用户"找水每 tick 对缓存全部水打 LOS → 200ms/帧")
     * cleanLineOfSight 是 DDA 射线, 每 tick 对 BlockTracker radius128 缓存里
     * 全部水/矿 (最多 100 处/类型) 各打一束 = 帧卡 (矿石 500ms 卡顿同款根因)。
     * 方块可见性只取决于玩家眼睛位置与目标 (不依赖朝向), 400ms 内玩家位移
     * <~1 格, 结果稳定 → 加短缓存: 同一位置 400ms 内不重打射线, 全量评估一次
     * 后每 tick 退化为查表, 帧率恢复。近区 5Hz 扫描新暴露的方块自然进入缓存。
     */
    public static boolean isBlockVisible(AltoClef mod, BlockPos pos) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null) return false;
        long now = System.currentTimeMillis();
        // (2026-09-04 缓存跨位置漏洞修复) 缓存记录当时玩家坐标, 玩家位移超过
        // LOS_CACHE_MOVE_DRIFT (方块级) 即失效重算 — 否则 bot 移动/挖洞后 400ms
        // 内仍用旧位置的 LOS 结果, 可能把"曾经可见"判成当前可见 = 短暂透视。
        BlockPos pPos = player.getBlockPos();
        synchronized (_losCacheLock) {
            long[] cached = _losCache.get(pos);
            if (cached != null && (now - cached[0]) < LOS_CACHE_TTL_MS) {
                long dx = pPos.getX() - cached[2];
                long dy = pPos.getY() - cached[3];
                long dz = pPos.getZ() - cached[4];
                if (dx * dx + dy * dy + dz * dz <= LOS_CACHE_MOVE_DRIFT_SQ) {
                    return cached[1] == 1L;
                }
            }
            if (_losCache.size() > LOS_CACHE_MAX_ENTRIES) {
                _losCache.clear(); // 满则整体清空, 简单安全 (下一 tick 自然重建)
            }
        }
        // Use the BlockPos overload: it treats "ray hits the target block itself"
        // as visible. The Vec3d overload is for entities and would report the
        // target block as occluding itself (never visible).
        boolean visible = LookHelper.cleanLineOfSight(player, pos, BLOCK_RAY_MAX_DISTANCE);
        synchronized (_losCacheLock) {
            _losCache.put(pos, new long[]{now, visible ? 1L : 0L, pPos.getX(), pPos.getY(), pPos.getZ()});
        }
        return visible;
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
