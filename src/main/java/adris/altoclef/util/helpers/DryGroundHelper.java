package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-10-02 冰原禁挖) 「真干陆地」判据 —— 单一判定源。
 *
 * 现场 20261002-172442 实锤：冰原地形（地表 ice/packed_ice，冰层下方就是水），
 * bot 在下挖找铁时反复「3 连败 → 水平撤离 40~64 格 → 重开下挖 → 又撞水」，
 * water skipped 3 → 29 → 59 → 93 单调递增零收敛，两次撤离还都是 120s 超时收尾。
 *
 * 为什么旧判据放行冰面：{@code BiasedExploreTask.isDryLanding} 的口径只是
 * 「本体与脚下方块都不含水」，而冰不是水 → 冰面（浮冰壳）一律被判成合格落点。
 * 冰面能站（heightmap 也算它），但冰下即水，站上去往下挖必然全水。
 *
 * 本判据把「能站」升级为「站得住 + 往下挖得到岩土」：
 *   ① 站立格本体不是水/岩浆；
 *   ② 其下 {@value #GROUND_DEPTH} 格内不得出现冰（浮冰壳/冰原本体）、不得出现水/岩浆；
 *   ③ 上述连续格必须都是非空气的实心材料（悬空/踩空气不算）。
 *
 * 注意与 {@code isDryLanding} 的分工：后者服务「食物探索的落点」，语义只到
 * 「终点不是水面」；本判据服务「下挖起点 / 撤离落点」，语义是「冰原之外的真岩土」。
 * 两者口径不同是刻意的（食物探索允许走在冰上找动物），不要互相替换。
 *
 * 成本：每采样点 1(本体) + {@value #GROUND_DEPTH} 次 getBlockState，纯内存读，
 * 不碰 baritone/trackBlock；只在换通道/撤离/开工的离散事件里调用，不在每 tick 路径上。
 */
public final class DryGroundHelper {

    private DryGroundHelper() {
    }

    /**
     * 向下探的格数。浮冰壳 = 1~3 格冰 + 下方水，{@value #GROUND_DEPTH} 格内既不遇冰也不遇水
     * 即按「实心陆地」处理（雪原的雪层 + 泥土/石头同样通过）。
     */
    public static final int GROUND_DEPTH = 4;

    /** 冰类方块（浮冰壳 / 冰原本体 / 冻冰 / 蓝冰）。 */
    public static boolean isIce(BlockState st) {
        var b = st.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE
                || b == Blocks.BLUE_ICE || b == Blocks.FROSTED_ICE;
    }

    /**
     * {@code stand} = 站立格（玩家脚所在的空气格）。
     *
     * @return true = 真干陆地（脚下连续实心岩土，4 格内无冰无水）；
     *         false = 水面/岩浆上方、冰面浮壳、脚下悬空、或区块未加载（未知即不选）。
     */
    public static boolean isSolidDryGround(AltoClef mod, BlockPos stand) {
        if (mod.getWorld() == null || stand == null) return false;
        if (!mod.getChunkTracker().isChunkLoaded(stand)) return false;
        BlockState at = mod.getWorld().getBlockState(stand);
        if (!at.getFluidState().isEmpty()) return false; // 落点本体在水/岩浆里
        for (int i = 1; i <= GROUND_DEPTH; i++) {
            BlockPos p = stand.down(i);
            if (!mod.getChunkTracker().isChunkLoaded(p)) return false; // 未知 → 不选
            BlockState st = mod.getWorld().getBlockState(p);
            if (!st.getFluidState().isEmpty()) return false; // 见水/岩浆 → 浮壳或水域
            if (isIce(st)) return false;                     // 见冰 → 冰原/浮冰
            if (st.isAir()) return false;                    // 悬空 → 站不住 / 不是陆地
        }
        return true;
    }
}
