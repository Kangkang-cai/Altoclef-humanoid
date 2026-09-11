package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-09-11) 「回岸上」共用逻辑。
 *
 * 原实现散落在 CollectBucketLiquidTask 里 (私有方法), 现抽出来供两个场景共用:
 *   ① 找液体 (lava/water): 泡在海里时先朝最近陆地游;
 *   ② 放置方块前 (PlaceBlockNearbyTask): 泡在水里放不了方块时先上岸。
 *
 * 为什么 ② 也必须回岸 —— 根因不在"找不到点位", 而在配置层:
 *   Settings.avoidOceanBlocks 默认 true → WorldHelper.canReach 在
 *   【海洋 biome + 玩家 y>47 + 候选点 y<64 + 该列水线高于候选点】时恒返回 false。
 *   即 bot 一旦泡在大洋里, 附近所有放置/交互点位全被标为"不可达",
 *   PlaceBlockNearbyTask.locateClosePlacePos 必然返回 null → 无限 Wander。
 *   现场 20260911-174220: 出水后停在 (-102,61,212) 泡水, 放工作台 15.5s
 *   失败到用户手动停。正解只有一个: 先上岸。
 */
public interface LandHelper {

    /**
     * 玩家是否"在海洋里需要回岸": 海洋 biome 且【真泡在水里】。
     *
     * (2026-09-04 v2 用户"遇到海洋转向不丝滑思考很久") 原只看 biome — bot 走
     * 在海边陆地/沙滩但 biome 采样为 ocean 时误判"在海洋" → 反复 On ocean →
     * findNearestLand(近处陆地) 与探索分支打架, heading 每段大跳 + 反复寻路停滞
     * (日志 15:16:49-15:17:09 On ocean→land ↔ Exploring 每 1-7s 切, heading
     * 262→299→353)。收紧为真泡水: 岸上(biome ocean 但没泡水)走正常探索, 不再僵硬折返。
     */
    static boolean isPlayerInOcean(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null
                || !WorldHelper.isOcean(mod.getWorld().getBiome(mod.getPlayer().getBlockPos()))) {
            return false;
        }
        if (mod.getPlayer().isTouchingWater()) return true;
        BlockPos feet = mod.getPlayer().getBlockPos();
        return mod.getChunkTracker().isChunkLoaded(feet)
                && mod.getWorld().getBlockState(feet).isOf(Blocks.WATER);
    }

    /**
     * 该 XZ 列的顶部 (最高非空气方块) 是否为水 (海面/浅水)。
     * 列未加载视为非水 (不知道, 先信任 biome)。列顶部是水 = "游过去也上不了岸"。
     *
     * (合并自 CollectBucketLiquidTask.isWaterAt / isColumnWater —— 两者逐行等价)
     */
    static boolean isColumnWater(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        for (int y = mod.getWorld().getHeight() - 1; y >= 0; --y) {
            BlockPos check = new BlockPos(target.getX(), y, target.getZ());
            if (!mod.getChunkTracker().isChunkLoaded(check)) return false;
            BlockState state = mod.getWorld().getBlockState(check);
            if (state.isAir()) continue;
            return state.getBlock() == Blocks.WATER;
        }
        return false;
    }

    /**
     * 沿 8 方向扫描, 找最近的"非海洋 biome 且列顶部非水"的干燥陆地 XZ;
     * 128 格内全海/全湿返回 null。返回点的 Y 沿用玩家当前 Y (垂直交给寻路处理)。
     *
     * (2026-09-04 v2 用户"寻找lava老是定位到水面上") 原只查 biome 非 ocean — biome
     * 是陆地但该 XZ 实际是海面/浅水时, bot GetToXZTask 游到水面停住不上岸 (日志
     * 14:19:28-38 On ocean heading to land -> (216,285)/(228,279) 反复到达水面)。
     * 加 isColumnWater: 只有真正干燥 (列顶部非水) 的陆地才作为上岸目标。
     */
    static BlockPos findNearestLand(AltoClef mod) {
        return findNearestLand(mod, 0);
    }

    /**
     * (2026-09-11) 带方位偏移的版本: dirOffset 让扫描从第 dirOffset 个方向开始。
     *
     * 用途 —— 水里卡住时"重新规划路线"(现场 20260911-184733, 用户明确要求)。
     * 原扫描固定从正东开始, 卡住重算会**反复选中同一个到不了的目标**;
     * 每次重规划把 offset +1, 就会优先换一个方位找岸, 才叫真的换了路线。
     */
    static BlockPos findNearestLand(AltoClef mod, int dirOffset) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int dist = 16; dist <= 128; dist += 16) {
            for (int i = 0; i < 8; i++) {
                int d = (i + dirOffset) & 7;
                double ang = Math.PI / 4.0 * d;
                int x = p.getX() + (int) Math.round(Math.cos(ang) * dist);
                int z = p.getZ() + (int) Math.round(Math.sin(ang) * dist);
                BlockPos cand = new BlockPos(x, p.getY(), z);
                if (mod.getChunkTracker().isChunkLoaded(cand)
                        && !WorldHelper.isOcean(mod.getWorld().getBiome(cand))
                        && !isColumnWater(mod, cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    /**
     * (2026-09-11) 玩家是否"悬在水里" —— 身体沾水 且 脚下没有实体方块。
     *
     * 这是"放不下方块 / 站不住"的判定口径: 站在浅水底或沙滩上时脚下有实体,
     * 仍可正常放置; 只有整个人泡在水体里踩水 (脚下是水/空) 时才必须先上岸。
     * 用它而不是裸 isTouchingWater(), 是为了不误伤"涉水过河 / 岸边浅水"的正常放置。
     */
    static boolean isFloatingInWater(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        if (!mod.getPlayer().isTouchingWater()) return false;
        return !WorldHelper.isSolid(mod, mod.getPlayer().getBlockPos().down());
    }
}
