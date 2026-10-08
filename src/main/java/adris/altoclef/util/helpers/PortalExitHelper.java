package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * (2026-10-08) 下界门"人在门里"判定的唯一判定源 (判定源单一化铁律)。
 *
 * <h2>为什么必须是碰撞箱口径 (反编译 1.18.2 yarn 1.18.2+build.3 实证)</h2>
 * vanilla 传送链路: {@code NetherPortalBlock#onEntityCollision} 在实体【碰撞箱】
 * 与门块相交时被调用 → {@code Entity.setInNetherPortal(pos)} 置
 * {@code inNetherPortal=true} → {@code Entity#tickNetherPortal} 里
 * {@code netherPortalTime++} 达 80 ticks (=4s) 后强传。
 * onEntityCollision 由碰撞箱相交驱动, 与脚方块 {@code getBlockPos()} 无关。
 *
 * <p>用脚方块判"在门里"会在贴门框站立时漏判: 玩家 x=-302.22 时脚方块是
 * floor(-302.22)=-303 (非门块), 但碰撞箱 x∈[-302.52,-301.92] 横跨门块 -302。
 * 现场 20261008-101824 实锤: 点火后玩家 xyz=(-302.22,62,691.47) 全程未动,
 * 脚方块恒非门块, 撤出分支没进, 点亮恰 4s 被 vanilla 强传进下界。
 * 消费方: DefaultGoToDimensionTask (进门前撤出) / PortalExitChain (到达后出闸)。
 */
public interface PortalExitHelper {

    /** 玩家碰撞箱覆盖的方块里是否有下界门块 (与 vanilla onEntityCollision 同口径)。 */
    static boolean isPlayerIntersectingNetherPortal(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        Box box = mod.getPlayer().getBoundingBox();
        BlockPos min = new BlockPos(box.minX, box.minY, box.minZ);
        BlockPos max = new BlockPos(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.NETHER_PORTAL) {
                return true;
            }
        }
        return false;
    }

    /**
     * 玩家脚部附近 (±2 立方) 最近的下界门块, 用于选"往外走"的方向。
     * 相交判定为 true 时至少存在一个, 但仍容错返回 null (调用方自行兜底)。
     */
    static BlockPos nearestNetherPortalBlock(AltoClef mod) {
        if (mod.getPlayer() == null) return null;
        BlockPos center = mod.getPlayer().getBlockPos();
        BlockPos nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(center.add(-2, -2, -2), center.add(2, 2, 2))) {
            if (mod.getWorld().getBlockState(pos).getBlock() != Blocks.NETHER_PORTAL) continue;
            double sq = pos.getSquaredDistance(mod.getPlayer().getPos());
            if (sq < bestSq) {
                bestSq = sq;
                nearest = pos.toImmutable();
            }
        }
        return nearest;
    }
}
