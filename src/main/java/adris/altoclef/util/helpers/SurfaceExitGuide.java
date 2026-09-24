package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.Dimension;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * (2026-09-24 出洞安全引导) 解决"洞口边缘横跳":
 * bot 从深处爬回、SurfaceHelper 刚判地表的瞬间 (开放式大洞穴里往往是人在
 * 高处岩台、透过洞腔看见坡上草, 头顶还压着十几格岩层), 紧接着的觅食导航
 * 若使用纯 XZ 目标 (BearingExpeditionTask.GoalXZWithin), baritone 会选
 * 下坡穿洞的便宜路径, bot 走回深处 → 5s 后又判地下 → GoToSurface 爬回
 * 洞口 → 再穿洞 (20260924-111207 实锤: y=98 岩台 ↔ y=77 洞底横跳 5 轮)。
 *
 *
 * <h3>机制 (用户拍板)</h3>
 * SurfaceHelper 每次 below→surface 翻面即【武装】: 出洞后的前
 * {@link #REQUIRED_ARRIVALS} 个导航目标必须是:<ol>
 *   <li><b>强地表签名落点</b> —— 脚下是草方块/灰化土/菌丝/沙/红沙/雪块/陶瓦
 *       (复用 {@link SurfaceHelper#isStrongSurfaceSignature} 单一真相源);</li>
 *   <li><b>离洞有距离</b> —— 距武装位置 (洞口) 水平 ≥ {@link #MIN_ANCHOR_DIST},
 *       跳过洞口边缘光片区, 逐跳远离;</li>
 *   <li><b>带真实高度</b> —— 落点 y 取列地表站立高度 (NO_LEAVES→MOTION_BLOCKING
 *       三级取值), 消费方必须用带 y 的 GoalNear 而非纯 XZ 目标;</li>
 *   <li>干燥可站、非海洋 biome、头/脚格无碰撞。</li>
 * </ol>
 * 强签名点全灭时【降级】为任意露天干燥实体地面 (排除树叶/原木冠层),
 * 仍找不到则本 tick 不给点 (消费方不发导航, 不退回穿洞目标), 直到
 * {@link #ARM_TIMEOUT_MS} 超时自动解除。
 *
 * <h3>计数口径</h3>
 * 按【成功到达】计数 ({@link #recordArrived}), 不按派发次数: 安全点导航
 * 失败重选不浪费配额; 到达校验 = 距目标 XZ ≤ {@link #ARRIVE_XZ} 且当前
 * 确实在地表 (SurfaceHelper 迟滞口径)。横跳循环里 bot 从没真正走上草地,
 * 计数不会被错误消耗; 每次重新 below→surface 翻面都重新武装 (配额清零)。
 *
 * <h3>作用域</h3>
 * 仅主世界、仅地表资源探索消费方自愿接入 (OverworldExpeditionTask 与
 * foodExplore=true 的 BiasedExploreTask); 下界远征 / 砍木挖矿探索不接入。
 * static 状态按世界隔离, 已挂 WorldStateGuard.registerAll。
 *
 * <p>诊断标签: {@code [SURFACE-EXIT]}。
 */
public final class SurfaceExitGuide {

    private SurfaceExitGuide() {
    }

    /** 出洞后强制安全落点导航的成功次数。 */
    private static final int REQUIRED_ARRIVALS = 3;
    /** 安全落点距出洞位置 (洞口) 的最小水平距离 (用户拍板 24)。 */
    private static final double MIN_ANCHOR_DISTANCE = 24;
    /** 距当前玩家的最小步长 (避免选回脚下), 最大扫描半径。 */
    private static final double MIN_PLAYER_DISTANCE = 16;
    private static final int SCAN_MAX_RADIUS = 64;
    /** 武装总时长: 超时找不到 3 个可达安全点则解除, 退回普通探索防新卡死。 */
    private static final long ARM_TIMEOUT_MS = 90_000;
    /** 到达判定: 距安全目标 XZ ≤ 此值算走到 (与远征 ARRIVE_XZ=5 相容)。 */
    private static final double ARRIVE_XZ = 6;
    /** 找不到点时空扫描结果缓存 (调用方每 tick 重选, 119 列扫描不能每 tick 跑)。 */
    private static final long NULL_CACHE_MS = 250;

    /** 角度锥 (弧度, 0=偏好方向): 偏好角优先, 双向逐级放宽到 180°。 */
    private static final double[] ANGLE_OFFSETS = {
            0.0,
            Math.toRadians(22.5), -Math.toRadians(22.5),
            Math.toRadians(45), -Math.toRadians(45),
            Math.toRadians(67.5), -Math.toRadians(67.5),
            Math.toRadians(90), -Math.toRadians(90),
            Math.toRadians(112.5), -Math.toRadians(112.5),
            Math.toRadians(135), -Math.toRadians(135),
            Math.toRadians(157.5), -Math.toRadians(157.5),
            Math.PI
    };
    /** 距离环 (相对玩家, 格): 第一跳 24 起步逐档放远。 */
    private static final int[] DISTANCES = {24, 28, 32, 40, 48, 56, 64};

    // ===== 武装状态 (static: 跨探索任务实例存活, 与 SurfaceHelper 翻面同源) =====
    private static boolean _armed = false;
    private static int _arrivedCount = 0;
    private static long _armedAtMs = 0;
    private static int _anchorX;
    private static int _anchorZ;
    private static BlockPos _lastArrivedTarget = null;

    // 空扫描缓存 (玩家 2 格网格键 + 时间)
    private static BlockPos _nullCachePos = null;
    private static long _nullCacheAt = 0;

    /** 消费方导航失败的安全点拉黑 (防确定性重选同一个不可达点), tick 时间戳 TTL。 */
    private static final java.util.Map<BlockPos, Integer> _rejected = new java.util.HashMap<>();
    private static final int REJECT_TTL_TICKS = 6000; // 5 min @20tps
    private static final int REJECT_MAX = 64;

    /** 换存档复位 (接入 WorldStateGuard)。 */
    public static void onWorldBoundary() {
        _armed = false;
        _arrivedCount = 0;
        _armedAtMs = 0;
        _lastArrivedTarget = null;
        _nullCachePos = null;
        _nullCacheAt = 0;
        _rejected.clear();
    }

    /** 消费方报告某安全点不可达 (寻路失败): 拉黑一段时间, 扫描时跳过。 */
    public static void reject(BlockPos target) {
        if (target == null) return;
        pruneRejected();
        if (_rejected.size() >= REJECT_MAX) return;
        _rejected.put(target.toImmutable(), WorldHelper.getTicks());
        Debug.logMessage("[SURFACE-EXIT] safe landing unreachable, blacklisted "
                + target.toShortString() + " (size=" + _rejected.size() + "/" + REJECT_MAX + ")");
    }

    private static void pruneRejected() {
        int now = WorldHelper.getTicks();
        _rejected.entrySet().removeIf(e -> now - e.getValue() >= REJECT_TTL_TICKS);
    }

    private static boolean isRejected(BlockPos target) {
        if (_rejected.isEmpty()) return false;
        Integer at = _rejected.get(target);
        if (at == null) return false;
        if (WorldHelper.getTicks() - at >= REJECT_TTL_TICKS) {
            _rejected.remove(target);
            return false;
        }
        return true;
    }

    /**
     * SurfaceHelper below→surface 翻面时调用: 武装 / 重新武装。
     * 每次重新出洞都把配额清零 —— 横跳循环里每轮都重新给满 3 次机会。
     */
    public static void onReachedSurface(AltoClef mod) {
        if (mod.getPlayer() == null) return;
        BlockPos p = mod.getPlayer().getBlockPos();
        _armed = true;
        _arrivedCount = 0;
        _armedAtMs = System.currentTimeMillis();
        _anchorX = p.getX();
        _anchorZ = p.getZ();
        _lastArrivedTarget = null;
        _nullCachePos = null;
        _rejected.clear();
        Debug.logMessage("[SURFACE-EXIT] armed at " + p + ": next "
                + REQUIRED_ARRIVALS + " navigation(s) forced onto surface-signature landings");
    }

    /** SurfaceHelper 翻回 below 时调用: 不解除 (配额保留), 仅诊断。 */
    public static void onBackBelow(AltoClef mod) {
        if (!_armed || _arrivedCount >= REQUIRED_ARRIVALS) return;
        Debug.logMessage("[SURFACE-EXIT] back below after " + _arrivedCount + "/"
                + REQUIRED_ARRIVALS + " safe arrivals, quota kept (re-arm on next surface edge)");
    }

    /** 引导是否生效中 (消费方每次选导航目标前查)。 */
    public static boolean isArmed(AltoClef mod) {
        if (!_armed || _arrivedCount >= REQUIRED_ARRIVALS) return false;
        if (mod != null && WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) return false;
        if (System.currentTimeMillis() - _armedAtMs > ARM_TIMEOUT_MS) {
            disarm("timeout after " + (ARM_TIMEOUT_MS / 1000) + "s");
            return false;
        }
        return true;
    }

    /** 剩余强制次数 (诊断用)。 */
    public static int remaining() {
        return _armed ? Math.max(0, REQUIRED_ARRIVALS - _arrivedCount) : 0;
    }

    private static void disarm(String reason) {
        if (!_armed) return;
        _armed = false;
        _nullCachePos = null;
        Debug.logMessage("[SURFACE-EXIT] disarmed (" + reason + ", arrivals="
                + _arrivedCount + "/" + REQUIRED_ARRIVALS + ")");
    }

    /**
     * 沿偏好方位角扫描一个安全地表落点。
     * 角度锥双向逐级放宽; 每个方向先找强签名地面, 全灭后降级为任意露天干地面。
     *
     * @param preferredAngleRad 消费方当前锁定的远征/探索方位角 (弧度)
     * @return 带真实站立 y 的落点; 找不到返回 null (调用方不得改派穿洞目标)
     */
    public static BlockPos findSafeLanding(AltoClef mod, double preferredAngleRad) {
        if (!isArmed(mod) || mod.getPlayer() == null || mod.getWorld() == null) return null;
        Vec3d pos = mod.getPlayer().getPos();

        // 空扫描缓存: 找不到点时调用方每 tick 重入, 同 2 格网格 250ms 内不重扫。
        BlockPos cacheKey = new BlockPos((int) pos.x & ~1, 0, (int) pos.z & ~1);
        long now = System.currentTimeMillis();
        if (_nullCachePos != null && _nullCachePos.equals(cacheKey)
                && now - _nullCacheAt < NULL_CACHE_MS) {
            return null;
        }

        BlockPos degraded = null;
        for (double off : ANGLE_OFFSETS) {
            double angle = preferredAngleRad + off;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            for (int d : DISTANCES) {
                if (d > SCAN_MAX_RADIUS) break;
                int x = (int) Math.floor(pos.x + cos * d);
                int z = (int) Math.floor(pos.z + sin * d);
                // 不许折回洞口方向: 距出洞锚点必须 ≥ MIN_ANCHOR_DISTANCE。
                double anchorDist = Math.hypot((x + 0.5) - (_anchorX + 0.5),
                        (z + 0.5) - (_anchorZ + 0.5));
                if (anchorDist < MIN_ANCHOR_DISTANCE) continue;
                double playerDist = Math.hypot((x + 0.5) - pos.x, (z + 0.5) - pos.z);
                if (playerDist < MIN_PLAYER_DISTANCE) continue;
                BlockPos landing = surfaceStanding(mod, x, z);
                if (landing == null) continue; // 未加载/空壳/虚空: 不猜
                if (isRejected(landing)) continue; // 消费方已证实不可达
                if (!isDryStandable(mod, landing)) continue;
                if (WorldHelper.isOcean(mod.getWorld().getBiome(landing))) continue;
                boolean strong = SurfaceHelper.isStrongSurfaceSignature(
                        mod.getWorld().getBlockState(landing.down()));
                if (strong) {
                    _nullCachePos = null;
                    Debug.logMessage("[SURFACE-EXIT] safe landing " + (_arrivedCount + 1)
                            + "/" + REQUIRED_ARRIVALS + " -> " + landing.toShortString()
                            + " ground=" + mod.getWorld().getBlockState(landing.down()).getBlock()
                            + " dAnchor=" + (int) anchorDist + " bearing="
                            + String.format(java.util.Locale.ROOT, "%.0f", Math.toDegrees(angle))
                            + "°");
                    return landing;
                }
                // 降级候选: 露天干地面但无强签名 (石头/泥土坡面)。树叶/原木冠层已在
                // isDryStandable 排除, 不会让 bot 爬树冠。
                if (degraded == null) degraded = landing;
            }
        }

        if (degraded != null) {
            _nullCachePos = null;
            Debug.logMessage("[SURFACE-EXIT] no strong-signature column in scan, degraded to open-air "
                    + "dry ground " + degraded.toShortString()
                    + " ground=" + mod.getWorld().getBlockState(degraded.down()).getBlock());
            return degraded;
        }

        _nullCachePos = cacheKey;
        _nullCacheAt = now;
        Debug.logMessage("[SURFACE-EXIT] no safe landing around anchor=(" + _anchorX + "," + _anchorZ
                + ") (r<=" + SCAN_MAX_RADIUS + "), keeping surface-exit forced this tick");
        return null;
    }

    /**
     * 消费方到达当前安全目标时调用。距目标 ≤ {@link #ARRIVE_XZ} 且当前确在
     * 地表才计一次成功; 同一目标不重复计数。
     */
    public static void recordArrived(AltoClef mod, BlockPos safeTarget) {
        if (!_armed || safeTarget == null || mod.getPlayer() == null) return;
        if (safeTarget.equals(_lastArrivedTarget)) return;
        Vec3d p = mod.getPlayer().getPos();
        double xz = Math.hypot(p.x - (safeTarget.getX() + 0.5), p.z - (safeTarget.getZ() + 0.5));
        if (xz > ARRIVE_XZ) return;
        // 走到了列点却仍在地下 (数据洞/落点误判) → 不计, 避免配额被穿洞路径消耗。
        if (SurfaceHelper.isBelowSurfaceDebounced(mod)) return;
        _lastArrivedTarget = safeTarget.toImmutable();
        _arrivedCount++;
        Debug.logMessage("[SURFACE-EXIT] reached safe landing " + _arrivedCount + "/"
                + REQUIRED_ARRIVALS + " at " + mod.getPlayer().getBlockPos());
        if (_arrivedCount >= REQUIRED_ARRIVALS) {
            disarm("3 safe surface arrivals completed");
        }
    }

    /**
     * 该 xz 列的地表站立点 (脚格 y)。与 BiasedExploreTask.surfaceYNoLeavesAt
     * 同口径: ① MOTION_BLOCKING_NO_LEAVES 有效 (新区块, 剥树冠) → 用它;
     * ② 空壳 (旧区块恒 bottomY) → MOTION_BLOCKING; ③ 都无效 → null (不猜,
     * 安全模式绝不回退玩家当前 y, 否则落点砸进洞壁/石头)。
     * getTopY 返回最高匹配方块【上方站立空气格】y, 直接用, 不 +1/-1。
     */
    private static BlockPos surfaceStanding(AltoClef mod, int x, int z) {
        if (mod.getWorld() == null) return null;
        if (!mod.getChunkTracker().isChunkLoaded(new BlockPos(x, 0, z))) return null;
        int bottomPlus1 = mod.getWorld().getBottomY() + 1;
        int noLeaves = mod.getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (noLeaves > bottomPlus1) return new BlockPos(x, noLeaves, z);
        int motionBlocking = mod.getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        if (motionBlocking > bottomPlus1) return new BlockPos(x, motionBlocking, z);
        return null;
    }

    /**
     * 落点是否干燥可站: ground 为实体地面 (非流体/非树叶/非原木), 脚格与头格
     * 无碰撞且非流体。草丛/花 (blocksMovement=false) 放行 —— 人能站进花草里。
     */
    private static boolean isDryStandable(AltoClef mod, BlockPos landing) {
        if (mod.getWorld() == null) return false;
        if (!mod.getChunkTracker().isChunkLoaded(landing)
                || !mod.getChunkTracker().isChunkLoaded(landing.up())
                || !mod.getChunkTracker().isChunkLoaded(landing.down())) {
            return false;
        }
        BlockState ground = mod.getWorld().getBlockState(landing.down());
        BlockState feet = mod.getWorld().getBlockState(landing);
        BlockState head = mod.getWorld().getBlockState(landing.up());
        // 地面: 有碰撞 + 非流体 + 非树冠 (降级档不爬树)。
        if (!ground.getMaterial().blocksMovement()) return false;
        if (!ground.getFluidState().isEmpty()) return false;
        if (ground.isIn(BlockTags.LOGS)) return false;
        if (ground.isIn(BlockTags.LEAVES)) return false;
        // 脚/头: 无碰撞 (空气/花草) 且干燥。
        if (feet.getMaterial().blocksMovement() || !feet.getFluidState().isEmpty()) return false;
        if (head.getMaterial().blocksMovement() || !head.getFluidState().isEmpty()) return false;
        // 与 BiasedExploreTask 落点闸门同口径 (2026-09-24 审查加固): blocksMovement=false
        // 但 baritone canWalkThrough 不拦、真走进去会受害的方块 —— 细雪(无皮靴下沉
        // 冻伤) 与各传送门 (废墟门柱停留 4s 被传走) 显式拒绝。
        if (isWalkThroughHazard(feet.getBlock()) || isWalkThroughHazard(head.getBlock())) return false;
        return true;
    }

    private static boolean isWalkThroughHazard(net.minecraft.block.Block b) {
        return b == Blocks.POWDER_SNOW
                || b == Blocks.NETHER_PORTAL
                || b == Blocks.END_PORTAL
                || b == Blocks.END_GATEWAY;
    }
}
