package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 「跳跃卡死」的挖障判定 (2026-09-11)。
 *
 * 背景: 会话 20260911-151743 抓到 9 条硬样本 —— baritone 真的强制按下了前进 +
 * 跳跃 (bIn[fwd=true jump=true]), 但玩家 hSpeed≈0.000, 同一格内 y 只上下震荡
 * 0.33 格 (正常跳跃应上升约 1.25 格), 2~6s 后超时转 wander, 下次导航又选同一条
 * 路。用户拍板: ① 的处置用「挖障碍」, 改世界让 baritone 重算时路径自然变。
 *
 * 核心问题: **挖哪块?** 答案不是猜, 而是问 baritone 自己"你下一步要去哪":
 *   - {@code IPathingBehavior.getCurrent().getPosition()} 给出当前执行到的路径
 *     节点索引, {@code IPath.positions()} 给出整条路径的格子序列 → 索引 +1 就是
 *     baritone **下一个想站的格子** (next)。
 *   - 结合玩家当前格 P, 一次跳跃真正需要的通行空间只有 4 格:
 *       P.up(1) / P.up(2)  —— 起跳空间 (身高 1.8, 跳跃净升 ~1.25 格)
 *       next / next.up(1)  —— 落点空间 (站人 + 头顶)
 *     这 4 格里第一个"实心且可挖"的就是挡路的那块。顺序即优先级: 起跳空间被封
 *     时后面全都白搭。
 *
 * 候选格由 {@link #candidates} 统一给出: 路径 next 节点拿不到时, 朝向前方两格
 * 本身就是兜底候选, 无需在 nextPathNode 里再做一层退化。
 *
 * 闸门 (每条都对应一类误挖风险):
 *   - 空气/液体 (水/岩浆) → 不是障碍 → 水里卡住不会误触发挖障 (交给 [SwimUp] /
 *     GoToSurfaceTask)。
 *   - (2026-09-12 放宽) 非实心植物类 (azalea/草/苔藓毯等) 【可以挖】: 实锤
 *     会话 20260912-110137 — baritone 把 azalea 当可穿过方块规划路径, 但物理上
 *     它有碰撞判定, bot 顶着它 hSpeed=0 弹跳 90s; 旧 isSolid 闸门把它排除 →
 *     永远 "no obstruction" → forceCancel 重算又选回同一条路。反正候选格都在
 *     baritone 自己规划的路线上, 卡死实证在先, 挖掉即改世界。
 *   - 重力方块 (沙/沙砾/混凝土粉末) → 挖了会塌回原处, 白挖还改地形 → 跳过。
 *   - 硬度 < 0 (基岩/屏障/传送门框架) → 挖不动 → 跳过。
 *   - shouldAvoidBreaking (行为栈谓词, 如 PortalGuard "永不拆门") → 跳过。
 *   - 带 BlockEntity 的方块 (箱子/熔炉/漏斗/告示牌) → 挖了内容物撒一地 → 跳过。
 *   - 距玩家 > {@link #MAX_REACH_MANHATTAN} 格 → 不挖 (够不着, 只会引入新的卡死)。
 *
 * 找不到任何可挖障碍时返回 null, 调用方应改为"撤销当前路径逼 baritone 换路"
 * (baritone 没有路径级禁行注入, IPathingBehavior 只暴露 forceCancel/cancelEverything,
 * 详见 CustomBaritoneGoalTask 的注释)。
 */
public final class JumpObstructionHelper {

    private JumpObstructionHelper() {
    }

    /** 只会考察玩家附近这几格, 再远就不是"这一步跳不过去"的问题了。 */
    private static final int MAX_REACH_MANHATTAN = 3;

    /**
     * 找出挡在 baritone 下一步路线上、且可挖的那一格。
     *
     * @return 可挖的挡路方块; null = 不是"有方块挡路"这类卡死 (应改为换路)。
     */
    public static BlockPos findObstruction(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        for (BlockPos cand : candidates(mod, feet)) {
            if (isDiggableObstruction(mod, feet, cand)) return cand;
        }
        return null;
    }

    /**
     * 候选格 (按优先级): 起跳空间 → 落点空间 → 朝向前方 (2026-09-12 增补)。
     * 起跳空间优先 —— 它被封时无论落点多宽敞都跳不上去。
     * 朝向前方两格兜底: 会话 20260912-110137 实锤 baritone 规划的 next 节点可能
     * 落在障碍之后 (真正顶住玩家的是朝向前方那格 azalea), 不看朝向就漏挖。
     */
    private static List<BlockPos> candidates(AltoClef mod, BlockPos feet) {
        List<BlockPos> list = new ArrayList<>(6);
        addUnique(list, feet.up(1));
        addUnique(list, feet.up(2));
        BlockPos next = nextPathNode(mod, feet);
        if (next != null) {
            addUnique(list, next);
            addUnique(list, next.up(1));
        }
        try {
            BlockPos ahead = feet.offset(mod.getPlayer().getHorizontalFacing());
            addUnique(list, ahead);
            addUnique(list, ahead.up(1));
        } catch (Throwable ignored) {
        }
        return list;
    }

    /**
     * baritone 下一个想站的格子。
     *
     * 从 {@code exec.getPosition()} 起往后找第一个 != 玩家当前格 的路径节点:
     * getPosition() 的语义是"当前执行到的 segment 起点", 可能与玩家所在格相同,
     * 直接 +1 在边界上会取到错节点, 所以按内容过滤更稳。
     */
    private static BlockPos nextPathNode(AltoClef mod, BlockPos feet) {
        try {
            IPathExecutor exec = mod.getClientBaritone().getPathingBehavior().getCurrent();
            if (exec == null) return fallbackAhead(mod, feet);
            IPath path = exec.getPath();
            if (path == null) return fallbackAhead(mod, feet);
            List<BetterBlockPos> positions = path.positions();
            if (positions == null || positions.isEmpty()) return fallbackAhead(mod, feet);
            int start = Math.max(0, Math.min(exec.getPosition(), positions.size() - 1));
            for (int i = start; i < positions.size(); i++) {
                BlockPos p = positions.get(i);
                if (!p.equals(feet)) return p;
            }
        } catch (Throwable t) {
            // baritone 状态读取异常 → 走兜底, 绝不让诊断/处置本身抛异常
        }
        return fallbackAhead(mod, feet);
    }

    /** 无路径信息时的兜底: 玩家朝向的前方一格。 */
    private static BlockPos fallbackAhead(AltoClef mod, BlockPos feet) {
        try {
            return feet.offset(mod.getPlayer().getHorizontalFacing());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addUnique(List<BlockPos> list, BlockPos pos) {
        if (pos == null) return;
        if (!list.contains(pos)) list.add(pos);
    }

    /** 够近 + 可挖 + 挖了有意义 (见类注释的闸门清单)。 */
    private static boolean isDiggableObstruction(AltoClef mod, BlockPos feet, BlockPos pos) {
        if (pos.equals(feet)) return false;
        if (manhattan(pos, feet) > MAX_REACH_MANHATTAN) return false;
        try {
            if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
            // (2026-09-12 放宽) 空气/液体不是障碍; 其余非实心 (azalea/草/毯等
            // 植物类) 也算 —— baritone 会把它们当可穿过方块规划路径, 物理上却
            // 可能挡人 (azalea 实锤), 卡死实证在先, 挖掉即改世界。
            BlockState state = mod.getWorld().getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) return false;
            Block block = state.getBlock();
            if (block instanceof FallingBlock) return false;            // 沙/沙砾: 挖了塌回来
            if (mod.getWorld().getBlockEntity(pos) != null) return false; // 箱子/熔炉等: 内容物会撒
            if (state.getHardness(mod.getWorld(), pos) < 0) return false;
            if (mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }
}
