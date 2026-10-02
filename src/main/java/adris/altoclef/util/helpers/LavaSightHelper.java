package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-10-02) 作业侧「看得见的岩浆」判定 —— <b>单一判定源</b>。
 *
 * <p>实现与调参结论逐字搬迁自 {@code MineDownExploreTask}（2026-09-14 起设计、
 * 2026-09-17 用户拍板、09-17/09-23 两轮 churn 修复），raw 设计注释仍留在那边的方法
 * 与字段区，这里只记搬迁边界与共用理由。
 *
 * <p>为什么要抽：这套判据此前**只长在 {@code MineDownExploreTask}（下挖隧道）里**。
 * 20261002-175747 现场证明那是个缺口 ——
 * <pre>
 *   18:56:00.6  &lt;Digging down to find ores&gt; 挖到天然洞穴
 *   18:56:04.1  STOP &lt;Digging down to find ores&gt; by &lt;Destroy block at -698,23,231&gt;   ← 交棒
 *   18:56:08.3  &lt;Collect visible ores&gt; → &lt;Mine And Collect: [[raw_iron] x 1]&gt; → DestroyBlockTask
 *   18:56:16.2  unreadable 42 ticks ×3 拉黑重试 (够不着洞穴里的铁矿)
 *   18:56:21.03 [LavaNav] 已报"脚下邻域岩浆" —— 但那只管导航开关
 *   18:56:21~25.3 坐标恒 -698,20,227 (4.3s 零位移)
 *   18:56:25.98 被流过来的岩浆泡住 → 18:56:28.43 [DEATH] cause=lava
 * </pre>
 * 即：一旦从"挖隧道"切到"洞穴里挖具体矿块"，**弃道判据整段失效**。所以本类供两处共用：
 * <ol>
 *   <li>{@code MineDownExploreTask}（委托本类，行为不变）；</li>
 *   <li>{@code DestroyBlockTask} 的 LAVA-EXIT 闸门（洞穴/一般挖矿，见该类字段区注释）。</li>
 * </ol>
 *
 * <p><b>口径（用户 2026-09-17 拍板，勿改）</b>：
 * 「只判前一格是为了不透视；岩浆在前面几格也装作看不见是不对的 —— 只要视觉范围内扫到的
 * 岩浆，那就叫看见了，看见了就该弃坑。」
 * <ul>
 *   <li><b>主判据</b> = 椭球 {@value #SIGHT_RADIUS_XZ}/{@value #SIGHT_RADIUS_Y} 内
 *       {@link VisibilityHelper#isBlockPerceivable}（表面暴露 + LOS 射线）。
 *       隔着岩壁 / 被岩石包裹的源头岩浆 → 不可见 → 不触发（<b>反透视边界不变</b>）。</li>
 *   <li><b>贴身兜底</b> = {@link #NEAR_FLUID_OFFSETS} 11 格。理由：① LOS 射线在脚下 /
 *       紧贴方块处几何本就不稳；② 贴身即"危险已贴上身体"，不能因射线没打准就放过。</li>
 * </ul>
 *
 * <p><b>后撤距离（09-17 重定）</b>：主世界 {@value #RETREAT_OVERWORLD} /
 * 下界 {@value #RETREAT_NETHER}。必须 &gt; 视觉半径 {@value #SIGHT_RADIUS_XZ}，否则
 * "撤到半径边缘→仍看得见同一处→立刻再弃道"会变成 100ms 一轮的 churn（旧值 6/12 的
 * 现场见 MineDownExploreTask 字段区注释）。留 4~8 格余量吃岩浆继续扩散。
 *
 * <p><b>与水刻意分开（勿合并参数）</b>：水的处置是"能走过去 → 累计 2 次才撤、撤 10 格、
 * 撤完回原地继续挖"；岩浆是"走过去=死 → 第一次遇到就弃道、撤 {@value #RETREAT_OVERWORLD}~{@value #RETREAT_NETHER}
 * 格、不回原道"。两者物理性质相反。
 *
 * <p><b>与 AltoClef 的 LavaNav 判据刻意分开（勿合并）</b>：LavaNav 是**导航开关**口径的
 * 贴身盲扫（水平 ≤2/下 2/上 1，判要不要关旋转跳；误触发代价只是路径变长，故允许盲扫、
 * 半径也很小）；本类是**作业弃道**口径的视觉感知（误触发代价 = 放弃一个目标 + 撤
 * 16~20 格），必须过反透视边界、半径也必须足够早（12 格）。两者的数值参数没有可共用性。
 *
 * <p>状态说明：节流与宽限期原本是 {@code MineDownExploreTask} 的实例字段，抽到本类后改为
 * <b>static</b> —— 单客户端进程内只有一处岩浆感知，两个消费方共享同一节流是正确语义
 * （同一次扫描不该因为多了一个消费方而翻倍）。
 */
public final class LavaSightHelper {

    private LavaSightHelper() {
    }

    // ===== 后撤距离（09-17 重定；必须 > SIGHT_RADIUS_XZ）=====
    public static final int RETREAT_NETHER = 20;
    public static final int RETREAT_OVERWORLD = 16;

    // ===== 视觉感知扫描半径（椭球）=====
    /** 水平半径。取 12 而非全渲染距离：只关心"会挡到自己这条通道"的近场。 */
    public static final int SIGHT_RADIUS_XZ = 12;
    /** 垂直半径。压到 6：下界岩浆海常在头顶正上方一层，再远的头顶岩浆不构成"挖出来的危险"。 */
    public static final int SIGHT_RADIUS_Y = 6;

    /** 视觉扫描节流：椭球约 8k 格 getBlockState，200ms 一次。 */
    private static final long SIGHT_SCAN_INTERVAL_MS = 200;
    /** 贴身兜底节流：11 次 getBlockState 极便宜。 */
    private static final long NEAR_SCAN_INTERVAL_MS = 100;
    /** 撤离刚结束的宽限期：让撤退动作真正做完再重新评估（churn 修复）。 */
    private static final long SIGHT_GRACE_MS = 2000;

    private static long _lastSightScanMs = 0;
    private static long _lastNearScanMs = 0;
    private static long _sightGraceUntilMs = 0;

    /**
     * 贴身流体偏移表（2026-09-23 起岩浆 {@link #findAdjacentLava} 与
     * {@code MineDownExploreTask.findFloodedTunnelWater} 共用：同一"贴身 11 格"几何概念
     * 只留一份）。顺序刻意从"最贴近玩家"开始：玩家所在格 → 水平四邻 → 脚下 → 斜下 → 头顶。
     */
    public static final BlockPos[] NEAR_FLUID_OFFSETS = {
            new BlockPos(0, 0, 0),
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(0, -1, 0), new BlockPos(0, 1, 0),
            new BlockPos(1, -1, 0), new BlockPos(-1, -1, 0),
            new BlockPos(0, -1, 1), new BlockPos(0, -1, -1),
    };

    /** 当前维度该后撤多少格（> {@link #SIGHT_RADIUS_XZ} 才能"撤完就看不见它"）。 */
    public static int retreatDistance() {
        return WorldHelper.getCurrentDimension() == Dimension.NETHER ? RETREAT_NETHER : RETREAT_OVERWORLD;
    }

    /**
     * 撤离动作完成后调用：给自己一个宽限期，期间视觉路径不再触发。
     * (churn 修复；贴身兜底 {@link #findAdjacentLava} 不受宽限期影响 ——
     * 危险已经贴到身上时，不能因为"刚撤过"就不救。)
     */
    public static void grantSightGrace() {
        _sightGraceUntilMs = System.currentTimeMillis() + SIGHT_GRACE_MS;
    }

    /** 任务重启时清宽限期（原 MineDownExploreTask.onStart 的复位）。 */
    public static void resetSightGrace() {
        _sightGraceUntilMs = 0;
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
     * (2026-09-17 视觉岩浆感知, 主判据) 在玩家周围（水平 {@value #SIGHT_RADIUS_XZ} /
     * 垂直 {@value #SIGHT_RADIUS_Y} 椭球）找【看得见】的岩浆，返回最近一处坐标，否则 null。
     *
     * <p>"看得见"走仓内统一视觉入口 {@link VisibilityHelper#isBlockPerceivable}：岩浆先过
     * "表面暴露(正上方是空气/洞穴空气)"前置，再过 LOS 射线。故：
     * 侧壁/脚下渗进已挖空间的岩浆（上方即通道空气）→ 暴露 → 看得见 → 弃道；
     * 隔着岩壁或被岩石包裹的源头岩浆 → 表面被封 → 不可见 → 不触发。
     */
    public static BlockPos findSeenLava(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        long now = System.currentTimeMillis();
        if (now < _sightGraceUntilMs) return null;
        if (now - _lastSightScanMs < SIGHT_SCAN_INTERVAL_MS) return null;
        _lastSightScanMs = now;
        BlockPos feet = mod.getPlayer().getBlockPos();
        int xzSq = SIGHT_RADIUS_XZ * SIGHT_RADIUS_XZ;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dy = -SIGHT_RADIUS_Y; dy <= SIGHT_RADIUS_Y; dy++) {
            for (int dx = -SIGHT_RADIUS_XZ; dx <= SIGHT_RADIUS_XZ; dx++) {
                for (int dz = -SIGHT_RADIUS_XZ; dz <= SIGHT_RADIUS_XZ; dz++) {
                    int hSq = dx * dx + dz * dz;
                    if (hSq > xzSq) continue; // 椭球水平截断
                    BlockPos p = feet.add(dx, dy, dz);
                    if (!isLava(mod, p)) continue; // 便宜的血缘复核 (区块未加载/已非岩浆直接跳过)
                    if (!VisibilityHelper.isBlockPerceivable(mod, p)) continue; // 贵的那步: 表面暴露 + LOS
                    double d = hSq + (double) dy * dy;
                    if (d < bestSq) {
                        bestSq = d;
                        best = p; // 取最近的一处: 撤离目标/后撤方向都更准
                    }
                }
            }
        }
        return best;
    }

    /**
     * (2026-09-17 视觉岩浆感知, 贴身兜底) 扫【与玩家紧邻的 11 格】({@link #NEAR_FLUID_OFFSETS})，
     * 命中返回该岩浆格坐标，否则 null。
     *
     * <p>为什么在视觉判据之外还要留这一层：① LOS 射线对脚下/紧贴方块的几何本就不稳；
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

    /**
     * 主判据 {@link #findSeenLava}（看得见）+ 兜底 {@link #findAdjacentLava}（贴身）。
     * 返回 null = 当前没有需要弃道的岩浆。这是消费方唯一应该调用的入口。
     */
    public static BlockPos findLavaHazard(AltoClef mod) {
        BlockPos seen = findSeenLava(mod);
        return seen != null ? seen : findAdjacentLava(mod);
    }
}
