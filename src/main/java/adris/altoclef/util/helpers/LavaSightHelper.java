package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-10-02 建 / 2026-10-03 收窄) 作业侧岩浆判定的共用件。
 *
 * <p><b>本类现在只提供两件事</b>：
 * <ol>
 *   <li>{@link #findAdjacentLava} —— 贴身 11 格兜底（危险已贴上身体）；</li>
 *   <li>{@link #retreatDistance} / {@link #grantSightGrace} / {@link #isSightGraceActive}
 *       —— 后撤距离与撤离后的感知宽限。</li>
 * </ol>
 *
 * <p><b>2026-10-03 删除了原来的"主判据" {@code findSeenLava}</b>（以玩家为圆心扫 12/6
 * 椭球内"看得见的岩浆"）。删除依据（用户原话）：
 * 「<b>是在自己要挖的通道中遇到岩浆才放弃并换通道</b>」——
 * 判据必须锚定【**我要挖的那个东西 / 我挖出来的那条通道**】，不是【**我周围有什么**】。
 * 现场 20261003-152002 实锤：`[MineDown-Lava] ... (cause=seen) mode=tunnel step=0`
 * —— 隧道刚启动、一步未挖，就因 10 格外（玩家下方 6 格、且不在隧道轴线上）的岩浆弃道。
 * 同一套球扫被误搬到 {@code DestroyBlockTask}（开阔洞穴里挖指定方块）后更致命：目标一被
 * 派发 2 毫秒就弃道拉黑，2 分 21 秒 26 轮死循环、零产出（见该类撤除注释）。
 *
 * <p>现在"在通道中遇到岩浆"由两处负责，都在 {@code MineDownExploreTask}：
 * <b>队首</b>（{@code skipAirAndWater}，即将挖穿）+ <b>自挖通道渗入</b>
 * （{@code findFloodedTunnelLava}，与水的 {@code findFloodedTunnelWater} 逐行对称）。
 * 本类只留**贴身兜底**。
 *
 * <p><b>后撤距离（09-17 重定）</b>：主世界 {@value #RETREAT_OVERWORLD} /
 * 下界 {@value #RETREAT_NETHER}。这个量级是为"撤完就看不见那处岩浆"定的（旧值 6/12
 * 造成 100ms 一轮的 churn，现场见 {@code MineDownExploreTask} 字段区注释），
 * 留 4~8 格余量吃岩浆继续扩散。
 *
 * <p><b>与水刻意分开（勿合并参数）</b>：水的处置是"能走过去 → 累计 2 次才撤、撤 10 格、
 * 撤完回原地继续挖"；岩浆是"走过去=死 → 第一次遇到就弃道、撤
 * {@value #RETREAT_OVERWORLD}~{@value #RETREAT_NETHER} 格、不回原道"。两者物理性质相反。
 *
 * <p><b>与 AltoClef 的 LavaNav 判据刻意分开（勿合并）</b>：LavaNav 是**导航开关**口径的
 * 贴身盲扫（水平 ≤2/下 2/上 1，判要不要关旋转跳；误触发代价只是路径变长，故允许盲扫）；
 * 本类是**作业弃道**口径（误触发代价 = 放弃一条通道 + 撤 16~20 格）。数值不可共用。
 *
 * <p>状态说明：节流与宽限期原本是 {@code MineDownExploreTask} 的实例字段，抽到本类后改为
 * <b>static</b>（单客户端进程内只有一处岩浆感知）。
 */
public final class LavaSightHelper {

    private LavaSightHelper() {
    }

    // ===== 后撤距离（09-17 重定）=====
    public static final int RETREAT_NETHER = 20;
    public static final int RETREAT_OVERWORLD = 16;

    /** 贴身兜底节流：11 次 getBlockState 极便宜。 */
    private static final long NEAR_SCAN_INTERVAL_MS = 100;
    /** 撤离刚结束的宽限期：让撤退动作真正做完再重新评估（churn 修复）。 */
    private static final long SIGHT_GRACE_MS = 2000;

    private static long _lastNearScanMs = 0;
    private static long _sightGraceUntilMs = 0;

    /**
     * 贴身流体偏移表（2026-09-23 起由岩浆 {@link #findAdjacentLava} 与
     * {@code MineDownExploreTask} 的 {@code findFloodedTunnelWater/findFloodedTunnelLava}
     * 共用：同一"贴身 11 格"几何概念只留一份）。顺序刻意从"最贴近玩家"开始：
     * 玩家所在格 → 水平四邻 → 脚下 → 斜下 → 头顶。
     */
    public static final BlockPos[] NEAR_FLUID_OFFSETS = {
            new BlockPos(0, 0, 0),
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(0, -1, 0), new BlockPos(0, 1, 0),
            new BlockPos(1, -1, 0), new BlockPos(-1, -1, 0),
            new BlockPos(0, -1, 1), new BlockPos(0, -1, -1),
    };

    /** 当前维度该后撤多少格（"撤完就看不见它"的量级，见类注释）。 */
    public static int retreatDistance() {
        return WorldHelper.getCurrentDimension() == Dimension.NETHER ? RETREAT_NETHER : RETREAT_OVERWORLD;
    }

    /**
     * 撤离动作完成后调用：给自己一个宽限期，期间"自挖通道渗入"这一档不再触发。
     * (churn 修复；贴身兜底 {@link #findAdjacentLava} 不受宽限期影响 ——
     * 危险已经贴到身上时，不能因为"刚撤过"就不救。)
     */
    public static void grantSightGrace() {
        _sightGraceUntilMs = System.currentTimeMillis() + SIGHT_GRACE_MS;
    }

    /** 任务重启时清宽限期（原 {@code MineDownExploreTask.onStart} 的复位）。 */
    public static void resetSightGrace() {
        _sightGraceUntilMs = 0;
    }

    /** 撤离收尾后的宽限期内 → true（供"自挖通道渗入"档使用；贴身兜底不查）。 */
    public static boolean isSightGraceActive() {
        return System.currentTimeMillis() < _sightGraceUntilMs;
    }

    /**
     * (2026-09-14 岩浆弃道) 岩浆判定。用 {@code Blocks.LAVA} 而不是流体状态：
     * 源岩浆与流动岩浆是同一个 Block 实例（只是 state.level 不同），一律算危险。
     * 区块未加载时不判危险（未加载读作空气，与本仓 isAir 同款语义）。
     */
    public static boolean isLava(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
        return mod.getWorld().getBlockState(pos).getBlock() == Blocks.LAVA;
    }

    /**
     * (2026-09-17 贴身兜底) 扫【与玩家紧邻的 11 格】({@link #NEAR_FLUID_OFFSETS})，
     * 命中返回该岩浆格坐标，否则 null。
     *
     * <p>为什么留着这一层：① LOS 射线对脚下/紧贴方块的几何本就不稳；
     * ② 紧邻格已是岩浆意味着危险已经贴上身体，不能因为射线没打准就放过。
     */
    public static BlockPos findAdjacentLava(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        long now = System.currentTimeMillis();
        if (now - _lastNearScanMs < NEAR_SCAN_INTERVAL_MS) return null;
        _lastNearScanMs = now;
        BlockPos feet = mod.getPlayer().getBlockPos();
        for (BlockPos off : NEAR_FLUID_OFFSETS) {
            BlockPos p = feet.add(off);
            if (isLava(mod, p)) return p;
        }
        return null;
    }
}
