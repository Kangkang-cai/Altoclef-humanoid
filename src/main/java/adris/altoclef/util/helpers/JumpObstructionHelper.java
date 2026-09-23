package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
 *   - 结合玩家当前格 P, 一次跳跃真正需要的通行空间原本只算 4 格:
 *       P.up(1) / P.up(2)  —— 起跳空间 (身高 1.8, 跳跃净升 ~1.25 格)
 *       next / next.up(1)  —— 落点空间 (站人 + 头顶)
 *     这 4 格里第一个"实心且可挖"的就是挡路的那块。顺序即优先级: 起跳空间被封
 *     时后面全都白搭。
 *   - **(2026-09-17 P0-a) 补第 5、6 格: next.up(2) / ahead.up(2)** —— 「斜上台阶的
 *     落点头顶」。详见 {@link #candidates} 注释: 这是 09-11 建文件时就带的盲区,
 *     不是新引入的。
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
 *   - 比玩家脚底更低的位置 → 不挖 (2026-09-12): 本模块语义是"跳不上去/顶不过去",
 *     向下挖解决不了问题, 且会触发 DestroyBlockTask 的"站在危险方块上方"分支 →
 *     任务环 (见 {@link #isDiggableObstruction} 注释)。
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
     * 候选格 (按优先级): 起跳空间 → 落点空间 → 朝向前方 → 两个"落点头顶"(最后兜底)。
     * 起跳空间优先 —— 它被封时无论落点多宽敞都跳不上去。
     * 朝向前方两格兜底: 会话 20260912-110137 实锤 baritone 规划的 next 节点可能
     * 落在障碍之后 (真正顶住玩家的是朝向前方那格 azalea), 不看朝向就漏挖。
     *
     * <h3>(2026-09-17 P0-a) 补 {@code next.up(2)} / {@code ahead.up(2)}</h3>
     *
     * <b>缺口</b>: 玩家高 1.8, 站进一格要占【脚 + 头】两层。水平走过去时脚在
     * {@code ahead}、头在 {@code ahead.up(1)} —— 有查; 但**斜上一步(上台阶)**的落点是
     * {@code ahead.up(1)}, 此时脚在 {@code ahead.up(1)}、**头在 {@code ahead.up(2)}** ——
     * 没人查。next 节点同理 ({@code next.up(2)})。于是"台阶正上方那格实心"这种卡死,
     * 本函数永远返回 null → 调用方只能走 forceCancel 换路那条弱处置 (baritone 没有路径级
     * 黑名单, 换路往往又选回同一条)。
     *
     * <b>是老的还是新引入的: 老 bug, 09-11 建文件时就带进来的。</b> 取证三件套:
     * <ol>
     *   <li>{@code git log -S "ahead.up(2)"} / {@code -S "next.up(2)"} 在本文件上
     *       <b>0 命中</b> —— 从 {@code 7fe0bdf5} (2026-09-11 新建) 至今从未出现过,
     *       不是哪次改动删掉的。</li>
     *   <li>但它一直没暴露: 09-11 的调用方 {@code CustomBaritoneGoalTask.tickJumpStuck}
     *       要求"连续 20 tick ({@code JUMP_STUCK_TICKS}) 强制按住跳跃键"
     *       ({@code isJumpStuck} 里 {@code isInputForcedDown(JUMP)} 必须当拍为 true)。
     *       而 baritone 只在**起跳那一瞬**按跳、人在空中即释放 → streak 永远凑不满,
     *       整条检测是<u>死代码</u>, 本函数根本没被执行过几次。</li>
     *   <li>09-12 {@code 00bbdc82} 补上"贴地顶墙 (强制前进、不跳)"这第三条证据 +
     *       hSpeed 阈值 0.05→0.1 —— <b>而地道/台阶里的卡死正是这一条形态</b>。
     *       从这一刻起本函数才第一次被真正高频执行, 盲区才变成活 bug。</li>
     * </ol>
     * 所以"以前一直没遇到"= 这条代码路径以前跑不到, 不是以前没有这个 bug。
     *
     * <b>实证 (会话 20260917-120830)</b>: 33 条 {@code [JumpStuck]} 现场快照里
     * <b>15 条</b>的候选集合内唯一实心块恰好只在 {@code aheadUp2}
     * (形如 {@code pos=(-46,64,106) facing=east blocks[ahead=air aheadUp1=air
     * aheadUp2=stone]}, 1 格宽阶梯地道), 而同一份 diag 里
     * {@code [JumpStuck] clearing obstruction} <b>0 次</b>、
     * {@code no obstruction -> forceCancel} <b>65 次</b> —— 候选表的盲区被实证。
     *
     * <b>为什么追加在表尾</b>: 两个新候选排在所有既有候选之后, **不改动既有候选的
     * 优先级** —— 起跳/落点/朝向这三类仍是第一顺位, 新增格只在"前面的都没找到"时才
     * 生效。这样本次改动是严格增量: 原本返回非 null 的场景结果完全不变, 只把原本
     * 返回 null 的一部分救回"挖障"分支。挖障闸门 (可挖/够得着/不挖脚下/重力方块/
     * BlockEntity/avoidBreaking) 对它们一视同仁。
     *
     * <b>诚实边界</b>: 日志只打印 {@code ahead*} 六格, 看不到 {@code next}/{@code next.up(1)}
     * 的实际取值, 所以<b>无法断言每一条 no obstruction 都是因为缺 up(2)</b>; 能证明的
     * 是"候选表在这 15/33 条上是盲的"。挖掉那一格是否真能解卡仍需实跑验证 —— 次要
     * 理由是"改世界本身就会逼 baritone 重算"(见类注释的两个杠杆)。
     */
    private static List<BlockPos> candidates(AltoClef mod, BlockPos feet) {
        List<BlockPos> list = new ArrayList<>(8);
        addUnique(list, feet.up(1));
        addUnique(list, feet.up(2));
        BlockPos next = nextPathNode(mod, feet);
        if (next != null) {
            addUnique(list, next);
            addUnique(list, next.up(1));
        }
        // null = 朝向读不到 (addUnique 对 null 直接跳过, 与旧版的 try 包围同效)。
        BlockPos ahead = fallbackAhead(mod, feet);
        if (ahead != null) {
            addUnique(list, ahead);
            addUnique(list, ahead.up(1));
        }
        // (2026-09-17 P0-a) 两个"落点头顶": 追加在最后, 不影响上面任何一条的优先级。
        if (next != null) {
            addUnique(list, next.up(2));
        }
        if (ahead != null) {
            addUnique(list, ahead.up(2));
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
        // (2026-09-12 14:00:53 假死现场) 不挖比自己脚底更低的位置。
        // 本模块的语义是"跳不上去 / 顶不过去", 向下挖既解决不了, 还会踩中
        // DestroyBlockTask 的"站在危险方块上方"分支 —— 它返回 RunAwayFromPositionTask
        // (一个 CustomBaritoneGoalTask), 那个任务回头又调 JumpStuckResolver, 若
        // resolver 正在租用这个 DestroyBlockTask 就构成任务环 (单 tick 无限嵌套 →
        // 假死)。现场: bot 在浅水给自己垫了一块方块站上去, baritone 路径的 next
        // 节点落到脚下那块垫块 (140,62,44) → 被判"障碍" → 要挖自己脚下的方块。
        if (pos.getY() < feet.getY()) return false;
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

    // ===== (2026-09-23) 近场植物清场 =====
    // 背景实锤 (会话 20260923-081029 08:18:36): bot 被双格高草丛卡住, 单点
    // InstantBreakTask 在"眼睛位于目标方块内 + baritone 平滑转头未完成"时出手 →
    // 准星实际没对准草丛 → 打空, 租约 50ms 即 done 释放不重试, 最终任务 28.9s
    // 超时放弃。用户拍板: 不赌单点, 卡死时把 2 格内所有植物清干净。
    /** 清场半径 (切比雪夫): x/z ±R, y 从 feet.y-1 到 feet.y+2。 */
    public static final int VEGETATION_RADIUS = 2;

    /**
     * 可清植物白名单 (显式列举, 避免把玻璃/玻璃板/栅栏等"非实心但非植物"块误清)。
     * 任意 {@link LeavesBlock} 另由 {@link #isClearableVegetation} 直接放行。
     */
    private static final HashSet<Block> VEGETATION_BLOCKS = new HashSet<>();
    static {
        VEGETATION_BLOCKS.addAll(Arrays.asList(
                Blocks.GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
                Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
                Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES,
                Blocks.VINE,
                Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
                Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
                Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
                Blocks.NETHER_SPROUTS,
                Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF,
                Blocks.MOSS_CARPET, Blocks.HANGING_ROOTS, Blocks.SPORE_BLOSSOM
        ));
        // ItemHelper.FLOWER 含小花与 LILAC/PEONY/ROSE_BUSH/SUNFLOWER 双格花丛。
        VEGETATION_BLOCKS.addAll(Arrays.stream(ItemHelper.FLOWER)
                .map(Block::getBlockFromItem).toList());
    }

    /**
     * 收集玩家近场 (x/z ±{@link #VEGETATION_RADIUS}, y=feet.y-1..feet.y+2) 全部可清植物。
     * 每 tick 从【当前位置】重算 —— bot 挪位后集合跟着变, 清到集合为空即完成。
     */
    public static List<BlockPos> collectVegetation(AltoClef mod, BlockPos feet) {
        List<BlockPos> result = new ArrayList<>();
        if (!mod.getChunkTracker().isChunkLoaded(feet)) return result;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -VEGETATION_RADIUS; dx <= VEGETATION_RADIUS; dx++) {
                for (int dz = -VEGETATION_RADIUS; dz <= VEGETATION_RADIUS; dz++) {
                    BlockPos pos = feet.add(dx, dy, dz);
                    if (isClearableVegetation(mod, pos)) result.add(pos);
                }
            }
        }
        return result;
    }

    /** 近场是否存在可清植物 (无单点障碍时的兜底判定, 避免直接走弱 forceCancel)。 */
    public static boolean hasVegetationNearby(AltoClef mod, BlockPos feet) {
        return !collectVegetation(mod, feet).isEmpty();
    }

    /**
     * 植物清除闸门: 显式白名单 / 任意树叶, 且非空气非液体、硬度 ≥0 (挖得动)、
     * 非重力方块 (挖了塌回来)、无 BlockEntity (不会撒内容物)、未被行为栈禁挖。
     */
    public static boolean isClearableVegetation(AltoClef mod, BlockPos pos) {
        try {
            if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
            BlockState state = mod.getWorld().getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) return false;
            Block block = state.getBlock();
            boolean listed = VEGETATION_BLOCKS.contains(block) || block instanceof LeavesBlock;
            if (!listed) return false;
            if (block instanceof FallingBlock) return false;
            if (mod.getWorld().getBlockEntity(pos) != null) return false;
            if (state.getHardness(mod.getWorld(), pos) < 0) return false;
            if (mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
