package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.RegistryEntry;
import net.minecraft.world.biome.Biome;

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

    // ---- (2026-09-26 远征地形匝, 用户"我设计的偏好平原和森林的代码才是我要的
    //      地形匝") ----
    // 移植 BiasedExploreTask [FOOD-BIOME] 的地形偏好 (判据全部单源复用 WorldHelper
    // 的 isPlains/isRichFoodForest/isBirchForest/isDarkForest, 不另写第二份):
    //   偏好档位: 平原系 > 橡木/花森林 > 白桦系 > 深色橡木林 (最低档兜底);
    //   排斥地形: 其余一切群系 (沙漠/丛林/针叶林/沼泽/雪原/恶地…) 不进偏好池,
    //   海洋由 rejectTarget 硬拦 —— 远征方向被偏好群系拉走, 不会一头扎进
    //   "无食物价值"的地形。
    //   ②级"动物记忆点"远征没有 biasSources, 与 CollectFoodTask 场景一致天然跳过。
    // 机制 (与 pickBiasedTarget 同精神, 适配锁定方位角行军):
    //   - 周期扫描 (2.5s, 半径 160 步长 8, 与 FOODBIOME 扫描同节拍同口径: 只扫
    //     已加载区块, 落点取忽略树冠的地表且必须干燥) 求各档最近干落点;
    //   - 扫到偏好点 (距离 > 一步程+到达判定, 贴着就算已到不拉) 时, 本步改为
    //     朝它走一步 (逐步行军, 不瞬指远点), 同样过水闸;
    //   - 四档全无 (荒芜) 时维持锁定方位角行军 (与 BiasedExploreTask "四池都空
    //     保持原探索行为"一致);
    //   - 水闸对所有步生效: ①湿落点沿同方位重采样干落点 (DRY_LANDING_DISTANCES
    //     {64,32,80,24,96,16}), 全水拒绝; ②沿步线连续水面 > 24 (宽河/湖/湾) 拒绝;
    //     窄河照旧放行 ("过河可以"定案不推翻)。连续全灭走基类试探机制换方位。
    //   - 已落水时不进本匝 (游泳引导优先游出去)。

    // 与 FOODBIOME_SCAN_INTERVAL_TICKS=50t(~2.5s) 同节拍; 扫描参数同款。
    private static final long BIOME_SCAN_INTERVAL_MS = 2500;
    private static final int BIOME_SCAN_RADIUS = 160;
    private static final int BIOME_SCAN_STEP = 8;
    // 偏好点距离下限: 一步程+到达判定 (≈ FOODBIOME_MIN_BIAS_TARGET_DIST 精神)。
    private static final double PREFER_MIN_DIST = EXPLORE_STEP + ARRIVE_XZ;

    private long _lastBiomeScanMs = 0;
    /** 各档最近干落点 (扫描期刷新; null = 该档荒芜)。 */
    private BlockPos _plainsPt, _richPt, _birchPt, _darkPt;
    private String _preferTierName = null;

    /** 诊断日志节流: 选步失败时每 tick 最多 16 次尝试, 2s 一条足够取证。 */
    private static long _lastTerrainLogMs = 0;

    private static void logTerrain(String reason, double angleDeg, String detail) {
        long now = System.currentTimeMillis();
        if (now - _lastTerrainLogMs < 2000) return;
        _lastTerrainLogMs = now;
        Debug.logToFile("altoclef_diag.log",
                "[FOOD-EXPEDITION]\tterrain-" + reason + " bearing=" + (int) angleDeg + "deg " + detail);
    }

    /**
     * 周期扫描偏好群系的最近干落点, 返回按档位顺序 (平原>橡木/花森林>白桦系>
     * 深色橡木林) 的第一个非空档; 全空返回 null。未到扫描节拍时用缓存结果。
     */
    private BlockPos nearestPreferred(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastBiomeScanMs >= BIOME_SCAN_INTERVAL_MS && mod.getWorld() != null && mod.getPlayer() != null) {
            _lastBiomeScanMs = now;
            _plainsPt = _richPt = _birchPt = _darkPt = null;
            BlockPos player = mod.getPlayer().getBlockPos();
            double bestP = Double.POSITIVE_INFINITY, bestR = bestP, bestB = bestP, bestD = bestP;
            for (int dx = -BIOME_SCAN_RADIUS; dx <= BIOME_SCAN_RADIUS; dx += BIOME_SCAN_STEP) {
                for (int dz = -BIOME_SCAN_RADIUS; dz <= BIOME_SCAN_RADIUS; dz += BIOME_SCAN_STEP) {
                    BlockPos check = new BlockPos(player.getX() + dx, player.getY(), player.getZ() + dz);
                    if (!mod.getChunkTracker().isChunkLoaded(check)) continue;
                    int tier; // 0=平原 1=橡木/花 2=白桦 3=深色橡木; -1=不偏好
                    RegistryEntry<Biome> biome = mod.getWorld().getBiome(check);
                    if (WorldHelper.isPlains(biome)) tier = 0;
                    else if (WorldHelper.isRichFoodForest(biome)) tier = 1;
                    else if (WorldHelper.isBirchForest(biome)) tier = 2;
                    else if (WorldHelper.isDarkForest(biome)) tier = 3;
                    else continue; // 排斥地形: 不进偏好池
                    // 与 FOODBIOME 扫描同口径: 落点取忽略树冠的地表, 必须干燥。
                    int sy = BiasedExploreTask.surfaceYNoLeavesAt(mod, check.getX(), check.getZ(), check.getY());
                    BlockPos landing = new BlockPos(check.getX(), sy, check.getZ());
                    if (!BiasedExploreTask.isDryLanding(mod, landing)) continue;
                    double d = Math.hypot(dx, dz);
                    switch (tier) {
                        case 0: if (d < bestP) { bestP = d; _plainsPt = landing; } break;
                        case 1: if (d < bestR) { bestR = d; _richPt = landing; } break;
                        case 2: if (d < bestB) { bestB = d; _birchPt = landing; } break;
                        default: if (d < bestD) { bestD = d; _darkPt = landing; } break;
                    }
                }
            }
            _preferTierName = _plainsPt != null ? "plains"
                    : _richPt != null ? "richForest"
                    : _birchPt != null ? "birch"
                    : _darkPt != null ? "darkForest" : null;
        }
        if (_plainsPt != null) return _plainsPt;
        if (_richPt != null) return _richPt;
        if (_birchPt != null) return _birchPt;
        return _darkPt;
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
        // 地形偏好: 附近扫到偏好群系 → 本步朝它走一步 (逐步行军); 步被水闸拒
        // 则回退锁定方位步 (偏好只是"拉", 水闸才是"闸", 防死锁)。
        BlockPos prefer = nearestPreferred(mod);
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
                    logTerrain("biome-steer", Math.toDegrees(steer),
                            _preferTierName + " " + prefer.toShortString()
                                    + " (dist=" + (int) dist + ") step->" + gated.toShortString());
                    return gated;
                }
                logTerrain("reject", Math.toDegrees(steer),
                        _preferTierName + " step blocked by water, fallback to bearing");
            }
        }
        // 荒芜 (四档全无) 或偏好步被水闸拒: 锁定方位步只过水闸。
        return applyWaterGates(mod, from, angle, step, candidate);
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
