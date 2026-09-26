package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.RegistryEntry;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * (2026-09-15 地表觅食远征, 用户"连续长时间没找到食物就远走, 尽量不要回头,
 * 不要远征走到海里去了") 锁定方位角远征机制已上提到基类
 * {@link BearingExpeditionTask} (2026-09-16, 与下界挖金远征共用), 本类只提供
 * 主世界觅食的差异参数:
 *
 * - 拒绝条件: 目标列 biome 是【海洋】一律拒绝 (不下海); 河/湖放行
 *   (baritone 游河面没问题)。
 * - 游泳引导开启: 已在水里时直接朝最近非海洋陆地游出去。
 * - 永不自行 isFinished: 是否结束由上层 (CollectFoodTask 食物达标) 决定,
 *   不设硬超时。
 *
 * 诊断标签: [FOOD-EXPEDITION]。
 *
 * <h2>(2026-09-26 方向门 + 折返提交, 用户"加方向门, 方向必须能够兼容完全折返
 * (比如遇到海洋或沙漠)" + "切记不能在边界反复折返")</h2>
 *
 * 地形偏好的三档判据 (地形口径仍全部单源复用 WorldHelper 的 isPlains /
 * isRichFoodForest / isBirchForest / isDarkForest 与 BiasedExploreTask 的
 * 干落点口径, 不另写第二份):
 *
 * <ol>
 *   <li><b>方向门</b>: 偏好点必须落在【当前有效方位角 ± {@link #GATE_DEG}°】
 *       的锥内才允许拉。旧版只比无符号距离 —— bot 走到河边后, 正后方 60 格的
 *       平原点成了"最近偏好点", 于是被系统性拉回头; 走回平原后该点距离又 < 29
 *       而不拉, 锁定方位再把它送出去, 形成 22 秒周期、净位移为零的极限环
 *       (20260926-220319 帧通道复算实锤)。锥心取 {@code effectiveBearing()}
 *       (锁定方位 + 试探偏置), 让门随试探一起转, 不与试探机制对拉。</li>
 *   <li><b>档位降级</b>: 每档先剔除"已到达"的点 ({@code dist < PREFER_MIN_DIST})
 *       —— 站在平原上时扫描网格必含玩家自身列 (dx=dz=0), 旧版该点 dist=0 却
 *       仍按档提前 return, 让森林三档永远轮不到。现在平原档无可用远点就顺延
 *       下一档: 小平原口袋 → 走到相邻偏好森林; 大平原仍有远点 → 继续在平原里
 *       走 (不被莫名拉去森林)。</li>
 *   <li><b>完全折返 (barren-ahead 豁免)</b>: 锥内一个偏好点都没有, 且【正前方
 *       一步落点的群系不在偏好池】(沙漠/恶地/雪原…等无食物地形) 时, 放开 360°
 *       取最近的未到达偏好点, 朝它折返。这是"遇到沙漠必须回头"的唯一入口;
 *       海洋仍由 {@link #rejectTarget} 硬拦。</li>
 * </ol>
 *
 * <b>折返必须真换向</b> (用户 2026-09-26 硬约束): 豁免一旦触发, 立刻把新方位
 * 经 {@link #commitBearing} 写回锁定方位角, 并让折返目标带粘滞
 * ({@link #REVERSAL_STICKY_MS} 内一直走到它才重选)。只做"一步例外"的话, 下一
 * 步方位没刷新、锥心还在旧方向, 身后偏好点会再次把它拉回去 —— 那就是边界上的
 * 反复折返。粘滞目标被水闸拒 / 超时 / 走丢时立即释放, 不跨不过去的水死磕。
 *
 * 四档全荒芜 (周围 160 格内无任何偏好群系) 时不折返: 无处可去的折返等于原地
 * 打转, 维持锁定方位行军由上层 40s 远征超时兜底。
 */
public class OverworldExpeditionTask extends BearingExpeditionTask {

    public OverworldExpeditionTask() {
        super(-1); // 无硬超时, 收队由上层判定
    }

    @Override
    protected String diagTag() {
        return "[FOOD-EXPEDITION]";
    }

    /** 目标列是否海洋 biome (与 BiasedExploreTask.isTargetOcean 同口径)。 */
    @Override
    protected boolean rejectTarget(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        if (!mod.getChunkTracker().isChunkLoaded(target)) return false;
        return WorldHelper.isOcean(mod.getWorld().getBiome(target));
    }

    @Override
    protected boolean swimSteering() {
        return true;
    }

    /** (2026-09-24) 主世界觅食远征开启出洞安全引导 (前 3 个导航强制草方块等地表落点)。 */
    @Override
    protected boolean useSurfaceExitGuide() {
        return true;
    }

    @Override
    protected boolean diagSwitchState(AltoClef mod) {
        return mod.getWorld() != null
                && WorldHelper.isOcean(mod.getWorld().getBiome(mod.getPlayer().getBlockPos()));
    }

    // ------------------------------------------------------------------ //
    // (2026-09-26 远征地形匝) 参数
    // ------------------------------------------------------------------ //

    // 与 FOODBIOME_SCAN_INTERVAL_TICKS=50t(~2.5s) 同节拍; 扫描参数同款。
    private static final long BIOME_SCAN_INTERVAL_MS = 2500;
    private static final int BIOME_SCAN_RADIUS = 160;
    private static final int BIOME_SCAN_STEP = 8;
    /** 偏好点距离下限: 一步程+到达判定 (≈ FOODBIOME_MIN_BIAS_TARGET_DIST 精神)。 */
    private static final double PREFER_MIN_DIST = EXPLORE_STEP + ARRIVE_XZ;
    /** 方向门张角 (§类注释 1): 偏好点须落在有效方位角 ± 此角内。 */
    private static final double GATE_DEG = 60;
    /** 折返提交冷却: 两次"完全折返"的最小间隔 (§类注释"折返必须真换向")。 */
    private static final long REVERSAL_COOLDOWN_MS = 8000;
    /** 折返目标粘滞上限: 提交后一直走到它, 期间不再改主意。 */
    private static final long REVERSAL_STICKY_MS = 25000;
    /** 档位名 (索引 = 扫描时判定的 tier)。 */
    private static final String[] TIER_NAMES = {"plains", "richForest", "birch", "darkForest"};

    private long _lastBiomeScanMs = 0;
    /** 各档扫到的干落点池 (扫描期整体刷新; 空 = 该档荒芜)。 */
    private final List<BlockPos> _plainsPts = new ArrayList<>();
    private final List<BlockPos> _richPts = new ArrayList<>();
    private final List<BlockPos> _birchPts = new ArrayList<>();
    private final List<BlockPos> _darkPts = new ArrayList<>();

    /** 折返方案 (§类注释 3): 提交后粘滞走向它, 直到到达/走丢/被水闸拒。 */
    private BlockPos _reversalTarget = null;
    private long _reversalSinceMs = 0;
    /** 只存时间戳 (不含坐标), 跨世界无坐标污染; 冷却窗口 ≤8s, 残留无影响。 */
    private static long _lastReversalMs = 0;

    /** 诊断日志节流: 选步失败时每 tick 最多 16 次尝试, 2s 一条足够取证。 */
    private static long _lastTerrainLogMs = 0;

    private static void logTerrain(String reason, double angleDeg, String detail) {
        long now = System.currentTimeMillis();
        if (now - _lastTerrainLogMs < 2000) return;
        _lastTerrainLogMs = now;
        Debug.logToFile("altoclef_diag.log",
                "[FOOD-EXPEDITION]\tterrain-" + reason + " bearing=" + (int) angleDeg + "deg " + detail);
    }

    /** 选中的偏好点 + 它属于哪一档 (日志与粘滞判定用)。 */
    private static final class Steer {
        final BlockPos point;
        final String tier;
        final double dist;

        Steer(BlockPos point, String tier, double dist) {
            this.point = point;
            this.tier = tier;
            this.dist = dist;
        }
    }

    // ------------------------------------------------------------------ //
    // 偏好点扫描 (与 FOODBIOME 同节拍同口径)
    // ------------------------------------------------------------------ //

    /**
     * 周期扫描偏好群系的干落点入各档池 (未到节拍时保留上轮结果)。
     *
     * <p>视力口径与节流完全沿用 BiasedExploreTask.updateFoodBiomeBias: 只读已加载
     * 区块的 biome; 落点取忽略树冠的地表 (MOTION_BLOCKING_NO_LEAVES) 且必须干燥;
     * 走 {@link BiasedExploreTask#surfaceYNoLeavesAt} / {@link BiasedExploreTask#isDryLanding}
     * 单源口径, 不另写第二份。
     */
    private void rescanPreferred(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastBiomeScanMs < BIOME_SCAN_INTERVAL_MS) return;
        if (mod.getWorld() == null || mod.getPlayer() == null) return;
        _lastBiomeScanMs = now;

        _plainsPts.clear();
        _richPts.clear();
        _birchPts.clear();
        _darkPts.clear();

        BlockPos player = mod.getPlayer().getBlockPos();
        for (int dx = -BIOME_SCAN_RADIUS; dx <= BIOME_SCAN_RADIUS; dx += BIOME_SCAN_STEP) {
            for (int dz = -BIOME_SCAN_RADIUS; dz <= BIOME_SCAN_RADIUS; dz += BIOME_SCAN_STEP) {
                BlockPos check = new BlockPos(player.getX() + dx, player.getY(), player.getZ() + dz);
                if (!mod.getChunkTracker().isChunkLoaded(check)) continue;
                RegistryEntry<Biome> biome = mod.getWorld().getBiome(check);
                List<BlockPos> tierPool;
                if (WorldHelper.isPlains(biome)) tierPool = _plainsPts;
                else if (WorldHelper.isRichFoodForest(biome)) tierPool = _richPts;
                else if (WorldHelper.isBirchForest(biome)) tierPool = _birchPts;
                else if (WorldHelper.isDarkForest(biome)) tierPool = _darkPts;
                else continue; // 排斥地形 (沙漠/恶地/雪原…): 不进偏好池
                int sy = BiasedExploreTask.surfaceYNoLeavesAt(mod, check.getX(), check.getZ(), check.getY());
                BlockPos landing = new BlockPos(check.getX(), sy, check.getZ());
                if (!BiasedExploreTask.isDryLanding(mod, landing)) continue;
                tierPool.add(landing);
            }
        }
    }

    /** 第 t 档的点池。 */
    private List<BlockPos> poolOf(int tier) {
        switch (tier) {
            case 0: return _plainsPts;
            case 1: return _richPts;
            case 2: return _birchPts;
            default: return _darkPts;
        }
    }

    /**
     * 按档序 (平原 → 橡木/花森林 → 白桦 → 深色橡木林) 取第一个可用点。
     *
     * @param requireCone true = 只收方向门锥内的点 (判据 1);
     *                    false = 放开 360° (判据 3 的折返豁免)。
     * @return 该档最近的"未到达"(dist ≥ PREFER_MIN_DIST) 可用点; 全档无 → null。
     */
    private Steer pickTiered(Vec3d from, double bearing, boolean requireCone) {
        for (int tier = 0; tier < 4; ++tier) {
            BlockPos best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (BlockPos c : poolOf(tier)) {
                if (c == null) continue;
                double dx = c.getX() + 0.5 - from.x;
                double dz = c.getZ() + 0.5 - from.z;
                double d = Math.hypot(dx, dz);
                // 判据 2: 该点已经"到达"就不算可用 —— 让档位降级的判据收在
                // 选点这里, 而不是事后在 terrainAdjust 里对"已选中的点"补一刀
                // (后者会让平原档抢先占位、森林三档永远轮不到, 见类注释 2)。
                if (d < PREFER_MIN_DIST) continue;
                if (requireCone) {
                    double rel = Math.abs(normalizeDeg(Math.toDegrees(Math.atan2(dz, dx) - bearing)));
                    if (rel > GATE_DEG) continue;
                }
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
            if (best != null) return new Steer(best, TIER_NAMES[tier], bestDist);
        }
        return null;
    }

    /**
     * 选偏好点 (§类注释三条判据的落点)。
     *
     * <p>顺序: 0) 折返粘滞优先 —— 已提交的折返目标仍有效就直奔它, 不看门也不看
     * 档位 (这是"折返后不被拉回去"的机制保证); 1) 方向门锥内按档取点;
     * 2) 锥内全无 且 正前方是非偏好地形 → 放开 360° 折返并提交新方位。
     */
    private BlockPos selectPreferred(AltoClef mod, Vec3d from, double bearing, double step) {
        long now = System.currentTimeMillis();

        if (_reversalTarget != null) {
            boolean alive = now - _reversalSinceMs <= REVERSAL_STICKY_MS
                    && _reversalTarget.isWithinDistance(from, BIOME_SCAN_RADIUS)
                    && mod.getChunkTracker().isChunkLoaded(_reversalTarget)
                    && BiasedExploreTask.isDryLanding(mod, _reversalTarget)
                    && xzDistance(mod.getPlayer().getBlockPos(), _reversalTarget) > PREFER_MIN_DIST;
            if (alive) return _reversalTarget;
            _reversalTarget = null;
        }

        rescanPreferred(mod);

        // 判据 1: 方向门。
        Steer gated = pickTiered(from, bearing, true);
        if (gated != null) return gated.point;

        // 判据 3: 完全折返豁免 —— 只在"前方确实是非偏好地形"时才放开 360°。
        if (now - _lastReversalMs < REVERSAL_COOLDOWN_MS) return null;
        if (!aheadIsBarren(mod, from, bearing, step)) return null;
        Steer any = pickTiered(from, bearing, false);
        if (any == null) return null; // 四档全荒芜: 折返等于原地打转, 维持锁定方位

        double steer = Math.atan2(any.point.getZ() + 0.5 - from.z, any.point.getX() + 0.5 - from.x);
        _reversalTarget = any.point;
        _reversalSinceMs = now;
        _lastReversalMs = now;
        // 关键: 真换向, 不是一步例外 (用户 2026-09-26 硬约束)。
        commitBearing(steer, "barren ahead -> reverse to " + any.tier);
        logTerrain("gate-reverse", Math.toDegrees(steer),
                any.tier + " " + any.point.toShortString()
                        + " (was " + (int) Math.toDegrees(bearing) + "deg, dist=" + (int) any.dist + ")");
        return any.point;
    }

    /**
     * (判据 3) 正前方一步落点的群系是否【不在偏好池】—— 沙漠/恶地/雪原/沼泽/
     * 针叶林等无食物地形。未加载区块返回 false (不在知情范围内不豁免, 与
     * isDryLanding/isTargetInWater 的"未知不拦"口径一致)。
     */
    private boolean aheadIsBarren(AltoClef mod, Vec3d from, double bearing, double step) {
        if (mod.getWorld() == null) return false;
        BlockPos ahead = new BlockPos(
                (int) Math.floor(from.x + Math.cos(bearing) * step),
                (int) Math.floor(from.y),
                (int) Math.floor(from.z + Math.sin(bearing) * step));
        if (!mod.getChunkTracker().isChunkLoaded(ahead)) return false;
        RegistryEntry<Biome> b = mod.getWorld().getBiome(ahead);
        return !WorldHelper.isPlains(b) && !WorldHelper.isRichFoodForest(b)
                && !WorldHelper.isBirchForest(b) && !WorldHelper.isDarkForest(b);
    }

    /** 角度归一到 (-180, 180], 供方向门的相对角判定。 */
    private static double normalizeDeg(double deg) {
        deg %= 360;
        if (deg > 180) deg -= 360;
        if (deg <= -180) deg += 360;
        return deg;
    }

    /** 水闸 (对所有步生效, 含偏好步): 干落点重采样 + 宽水拦截。null = 拒绝该步。 */
    private BlockPos applyWaterGates(AltoClef mod, Vec3d from, double angle, double step, BlockPos candidate) {
        double angleDeg = Math.toDegrees(angle);
        // ① 干落点 (y 取该列忽略树冠的地表, 与 foodExplore 同口径)
        int sy = BiasedExploreTask.surfaceYNoLeavesAt(mod, candidate.getX(), candidate.getZ(),
                (int) Math.floor(from.y));
        BlockPos landing = new BlockPos(candidate.getX(), sy, candidate.getZ());
        if (!BiasedExploreTask.isDryLanding(mod, landing)) {
            BlockPos dry = BiasedExploreTask.findDryLandingAlong(mod, from, angle, Double.POSITIVE_INFINITY);
            if (dry != null) {
                logTerrain("resample", angleDeg, "wet landing " + candidate.toShortString()
                        + " -> dry " + dry.toShortString());
                return dry;
            }
            logTerrain("reject", angleDeg, "wet landing " + candidate.toShortString()
                    + ", no dry landing along bearing, repick");
            return null;
        }
        // ② 宽水拦截
        int span = BiasedExploreTask.maxWaterSpanAlongLine(mod, from, angle, step);
        if (span > BiasedExploreTask.RIVER_CROSS_MAX_BLOCKS) {
            logTerrain("reject", angleDeg, "wide water span=" + span
                    + " > " + BiasedExploreTask.RIVER_CROSS_MAX_BLOCKS
                    + " step=" + (int) step + " player=" + (mod.getPlayer() != null
                            ? mod.getPlayer().getBlockPos().toShortString() : "?"));
            return null;
        }
        return candidate;
    }

    @Override
    protected BlockPos terrainAdjust(AltoClef mod, Vec3d from, double angle, double step, BlockPos candidate) {
        // 地形偏好: 按三条判据选一个偏好点 → 本步朝它走一步 (逐步行军), 同样过
        // 水闸; 步被水闸拒则回退锁定方位步 (偏好只是"拉", 水闸才是"闸", 防死锁)。
        double bearing = effectiveBearing();
        BlockPos prefer = selectPreferred(mod, from, bearing, step);
        if (prefer != null) {
            double dx = prefer.getX() + 0.5 - from.x;
            double dz = prefer.getZ() + 0.5 - from.z;
            double dist = Math.hypot(dx, dz);
            if (dist > PREFER_MIN_DIST) {
                double steer = Math.atan2(dz, dx);
                BlockPos steered = new BlockPos(
                        (int) Math.floor(from.x + Math.cos(steer) * step),
                        (int) Math.floor(from.y),
                        (int) Math.floor(from.z + Math.sin(steer) * step));
                BlockPos gated = applyWaterGates(mod, from, steer, step, steered);
                if (gated != null) {
                    // 粘滞中的折返目标不用再打 biome-steer (每步一行会刷屏), 只有
                    // 普通偏好步记日志 —— 取证看 gate-reverse 那行即可。
                    if (prefer != _reversalTarget) {
                        double rel = normalizeDeg(Math.toDegrees(steer - bearing));
                        logTerrain("biome-steer", Math.toDegrees(steer),
                                tierNameOf(prefer) + " " + prefer.toShortString()
                                        + " (dist=" + (int) dist + " rel=" + (int) rel + "deg) step->"
                                        + gated.toShortString());
                    }
                    return gated;
                }
                // 水闸拒: 折返目标跨不过去的水就放弃, 防对着同一片水每步重试。
                if (prefer == _reversalTarget) {
                    _reversalTarget = null;
                }
                logTerrain("reject", Math.toDegrees(steer),
                        tierNameOf(prefer) + " step blocked by water, fallback to bearing");
            }
        }
        // 荒芜 (四档全无) 或偏好步被水闸拒: 锁定方位步只过水闸。
        return applyWaterGates(mod, from, angle, step, candidate);
    }

    /** 该点在哪个档池里 (日志用); 找不到返回 "prefer"。 */
    private String tierNameOf(BlockPos p) {
        if (_plainsPts.contains(p)) return TIER_NAMES[0];
        if (_richPts.contains(p)) return TIER_NAMES[1];
        if (_birchPts.contains(p)) return TIER_NAMES[2];
        if (_darkPts.contains(p)) return TIER_NAMES[3];
        return "prefer";
    }

    @Override
    protected String diagSwitchLabel() {
        return "ocean";
    }

    @Override
    protected String toDebugString() {
        return "Overworld food expedition";
    }
}
