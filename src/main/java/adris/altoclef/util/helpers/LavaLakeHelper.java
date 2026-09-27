package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * "岩浆湖"判据的唯一一份口径。
 *
 * <p>(2026-09-27 口径收编) 现场 20260927-111838: bot 在地下深处把**一条单格源岩浆瀑布**
 * 当成了"岩浆湖"锚点 (锚点 -399,8,175), 于是必须挤在 1.5 格高的地下洞里浇铸黑曜石 ——
 * 底托格 -399,7,175 正好是 bot 自己的头格, 方块放不进自己身体, 每 2s 判负 → 摆放↔wander
 * 无限横跳。用户口径: "一定是岩浆湖", 单格渗漏/小水洼不算。
 *
 * <p>判据本体原先只存在于 {@code KnowledgeMemory.scanLavaLakeByLiteralPerception()}
 * (记忆入账用), 锚点扫描那边是另一份"只要有岩浆就选"的旧口径 ⇒ 同一概念两套判据,
 * 正是 {@code detail/03-consistency.md} 记的最高频根因。现收编到本类, 记忆入账与
 * 传送门锚点扫描共用:
 * <ul>
 *   <li>{@link #isExposedLavaSource} —— 暴露的**静止岩浆源** (level==8 且正上方空气);
 *       被岩石压住的不算, 流动岩浆不算;</li>
 *   <li>{@link #countExposedLavaSourcesAround} —— 锚点周围 h8/v4 盒内的暴露静止源计数;</li>
 *   <li>{@link #isLake} —— 暴露静止源且计数 ≥ {@link #MIN_EXPOSED_SOURCES}(12) 才算"湖"。</li>
 * </ul>
 */
public final class LavaLakeHelper {

    /** 锚点周围 h8/v4 盒内暴露静止源 >= 12 才算"湖" (用户"一定是岩浆湖")。 */
    public static final int MIN_EXPOSED_SOURCES = 12;
    public static final int CONFIRM_RADIUS_XZ = 8;
    public static final int CONFIRM_RADIUS_Y = 4;

    private LavaLakeHelper() {
    }

    /** 静止岩浆源 (level 8) 且正上方是空气 —— 即"湖面"上可被看见/可舀的方块。 */
    public static boolean isExposedLavaSource(AltoClef mod, BlockPos pos) {
        var world = mod.getWorld();
        if (world == null) return false;
        var state = world.getBlockState(pos);
        if (state.getBlock() != Blocks.LAVA) return false;
        var fluid = state.getFluidState();
        if (!fluid.isStill() || fluid.getLevel() != 8) return false;
        return world.getBlockState(pos.up()).isAir();
    }

    /** 以 center 为中心 h8/v4 盒内暴露静止源的数量。 */
    public static int countExposedLavaSourcesAround(AltoClef mod, BlockPos center) {
        int count = 0;
        for (int dy = -CONFIRM_RADIUS_Y; dy <= CONFIRM_RADIUS_Y; dy++) {
            for (int dx = -CONFIRM_RADIUS_XZ; dx <= CONFIRM_RADIUS_XZ; dx++) {
                for (int dz = -CONFIRM_RADIUS_XZ; dz <= CONFIRM_RADIUS_XZ; dz++) {
                    if (isExposedLavaSource(mod, center.add(dx, dy, dz))) count++;
                }
            }
        }
        return count;
    }

    /**
     * 该格是否属于一个"岩浆湖"(成片 ≥12 暴露静止源)。单格源/瀑布一律 false。
     * 注意本判据要扫 h8/v4 盒 (~2600 格), 调用方需自行限次 (见 findLavaLake)。
     */
    public static boolean isLake(AltoClef mod, BlockPos pos) {
        if (!isExposedLavaSource(mod, pos)) return false;
        return countExposedLavaSourcesAround(mod, pos) >= MIN_EXPOSED_SOURCES;
    }
}
