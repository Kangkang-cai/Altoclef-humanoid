package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.Blocks;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-10-02 岩浆邻域判定 —— 单一判定源)
 *
 * <p>现场来源 (会话 20261002-175747, 18:56)：
 * <pre>
 *   18:56:16.2  (DestroyBlockTask) unreachable after 42 ticks, abandoning -698,23,231  (×3 拉黑重试)
 *   18:56:21.03 [LavaNav] 触发: 脚下邻域岩浆(水平&lt;=2/下2/上1)   ← 已报警, 但只管导航开关
 *   18:56:21.0~25.3 坐标恒为 -698,20,227 (4.3s 零位移, 一直对着 y=22~23 的方块敲)
 *   18:56:25.98 health=16.0                                ← 被流过来的岩浆泡住
 *   18:56:26.03 [LavaEscape] 启动 → 18:56:28.43 [DEATH] cause=lava
 * </pre>
 * 结论：从"邻域有岩浆"到"泡进去"有 4.9 秒窗口，期间作业侧没有任何"停手撤离"动作；
 * 泡进去之后物理逃生只有 2.45 秒预算，baritone 拿到第一条路径实测要 3.35 秒 —— 来不及。
 * 用户定调："根本就不应该让 bot 泡进岩浆"，作业侧模仿避水原则（WATER-EXIT）。
 *
 * <p>口径与 {@code AltoClef.tickLavaNavGuard} 的**触发内圈**一致
 * (水平切比雪夫 ≤2 / 下 2 / 上 1)。那边判的是"旋转跳该不该关"，这边判的是
 * "人是不是已经站在会被岩浆波及的位置" —— 两者必须同一把尺子，故此处抽为常量源，
 * AltoClef 侧改为引用本类常量（零行为改动）。
 *
 * <p>取向说明：区块未加载时 {@code getBlockState} 返回空气 ⇒ 判"无岩浆"。
 * 与 LavaNav 的"读不到 ≠ 有岩浆"取向相同（宁可漏判也不误触发）。玩家 ±2 格
 * 必然落在已加载区块内，实际不构成盲区。
 */
public final class LavaVicinityHelper {

    private LavaVicinityHelper() {
    }

    /** 危险判据水平半径（切比雪夫，格）。 */
    public static final int LAVA_VICINITY_RADIUS = 2;
    /** 危险判据向下深度（格）。 */
    public static final int LAVA_VICINITY_DOWN = 2;
    /** 危险判据向上高度（格）。 */
    public static final int LAVA_VICINITY_UP = 1;

    /**
     * 以 center 为中心、按 {@link #LAVA_VICINITY_RADIUS}/{@link #LAVA_VICINITY_DOWN}/
     * {@link #LAVA_VICINITY_UP} 围成的盒内是否存在岩浆方块。
     * 成本 = 5×4×5 = 100 次 getBlockState，仅在做决策的 tick 调用。
     */
    public static boolean hasLavaVicinity(World world, BlockPos center) {
        if (world == null || center == null) return false;
        for (int dx = -LAVA_VICINITY_RADIUS; dx <= LAVA_VICINITY_RADIUS; dx++) {
            for (int dz = -LAVA_VICINITY_RADIUS; dz <= LAVA_VICINITY_RADIUS; dz++) {
                for (int dy = -LAVA_VICINITY_DOWN; dy <= LAVA_VICINITY_UP; dy++) {
                    if (world.getBlockState(center.add(dx, dy, dz)).getBlock() == Blocks.LAVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 玩家当前脚格是否处于岩浆邻域（作业侧闸门的触发判据）。 */
    public static boolean playerInLavaVicinity(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return false;
        return hasLavaVicinity(mod.getWorld(), mod.getPlayer().getBlockPos());
    }
}
