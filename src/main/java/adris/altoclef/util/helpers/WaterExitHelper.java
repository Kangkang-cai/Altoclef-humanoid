package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * (2026-09-16) 「泡水漂浮 → 停止挖掘并离开水区」的例外判据：水洞检测。
 *
 * 背景（日志会话 20260915-231536，23:22:41-23:23:23 实锤）：bot 在水面
 * water-bob（inWater=true onGround=false）30s+，DestroyBlockTask 照常挖石头
 * （DestroyBlockTask L397 把 isTouchingWater 与 isOnGround 同权）。水中悬空挖掘
 * 有 5x 水 × 5x 悬空 = 25x 惩罚，浮着挖不现实（SwimDiveChain 注释同结论）。
 * 用户定规矩：泡水漂浮挖掘时【停止挖掘并离开水区】；例外 = 水洞情形 —— 附近
 * 没有直接连通且可达的两格空气（封闭水体里根本没得出路，继续挖）。
 *
 * 判定口径：
 *  - "直接连通" = 与玩家身体所处格子同属一个【空气/水连通域】（BFS 只经空气和
 *    水扩散；隔着实心石头的洞空气不连通，不算出口 —— 与 WorldSurvivalChain
 *    .findNearestBreathable 的 connected 口径同族）。
 *  - "两格空气" = 某空气格其上一格也是空气（可站/可呼吸的最小竖直空间）。
 *  - "可达" = 在连通域内且在本类搜索半径内（连通域本身即可游达，不依赖
 *    WorldHelper.canReach —— 后者受 avoidOceanBlocks 海洋规则干扰，见
 *    LandHelper 类注释）。
 *  - 命中任一出口空气 → 有出路 → 应离水；BFS 扩散完（或撞节点保险丝）没有 →
 *    封闭水洞 → 例外成立，继续挖。
 *
 * 设计约束（与 WaterStandHelper 同款）：无状态纯函数式判定，调用方负责行为；
 * 每 tick 调用安全，节点上限兜底成本。
 */
public final class WaterExitHelper {

    private WaterExitHelper() {
    }

    /** "附近"半径（相对玩家脚格的切比雪夫距离，格）。 */
    private static final int SEARCH_RADIUS = 8;
    /** BFS 节点上限（成本保险丝；超大水体探不完按"没找到出口"处理）。 */
    private static final int MAX_NODES = 2000;

    /**
     * 附近是否存在"直接连通且可达的两格空气"。
     * true = 有出路（调用方应停止挖掘并离开水区）；false = 水洞例外（继续挖）。
     */
    public static boolean hasReachableAirExit(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        BlockPos feet = mod.getPlayer().getBlockPos().toImmutable();
        // 起点 = 玩家身体占用的两格（浮动时可能一水一气）。身体格天然"连通"。
        BlockPos head = feet.up();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(feet);
        visited.add(head);
        queue.add(feet);
        queue.add(head);
        int expanded = 0;
        while (!queue.isEmpty()) {
            if (expanded >= MAX_NODES) {
                return false; // 保险丝：超大连通域探不完，按水洞处理
            }
            BlockPos cur = queue.poll();
            expanded++;
            if (isExitAir(mod, cur)) return true;
            for (Direction d : Direction.values()) {
                BlockPos next = cur.offset(d);
                if (!visited.add(next)) continue;
                if (Math.abs(next.getX() - feet.getX()) > SEARCH_RADIUS
                        || Math.abs(next.getY() - feet.getY()) > SEARCH_RADIUS
                        || Math.abs(next.getZ() - feet.getZ()) > SEARCH_RADIUS) {
                    continue; // 出"附近"范围
                }
                if (!mod.getChunkTracker().isChunkLoaded(next)) continue;
                if (!isAirOrWater(mod, next)) continue;
                queue.add(next);
            }
        }
        return false;
    }

    /** 出口空气：本体是空气（非水），且其上一格也是空气 —— 两格竖直空间。 */
    private static boolean isExitAir(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
        if (!mod.getWorld().getBlockState(pos).isAir()) return false;
        BlockPos above = pos.up();
        if (!mod.getChunkTracker().isChunkLoaded(above)) return false;
        return mod.getWorld().getBlockState(above).isAir();
    }

    /** 可扩散介质：空气或水（与 findNearestBreathable 的连通判据同口径）。 */
    private static boolean isAirOrWater(AltoClef mod, BlockPos pos) {
        BlockState st = mod.getWorld().getBlockState(pos);
        return st.isAir() || st.getBlock() == Blocks.WATER;
    }
}
