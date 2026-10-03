package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.Dimension;
import net.minecraft.tag.BlockTags;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * (2026-09-17 判据统一 → 0917 二次重构·多判据 → 0918 三次重构·弃高度图改逐块扫描)
 * 「bot 是否在地下」的全项目唯一判据。
 *
 * <h3>要回答的问题, 不是"看不看得见天"</h3>
 * 消费方要的是: <b>"我现在处在【露天活动层】吗?"</b>——能不能就地干地表活(砍树/收食物/做床),
 * 还是必须先挖回上面。这句话和"看得见天"并不等价:
 * <ul>
 *   <li>站在橡树树冠里砍树干 → 头顶是树叶, "看不见天", 但人就在地表;</li>
 *   <li>站在自己刚挖出的 1 格宽树坑/浅坑里 → 四周是土壁, "看不见天", 但离露天只有 2~3 格;</li>
 *   <li>站在露天山谷底部 y=57 → 看得见天, 但旧纯 y 阈值判它"深地下"。</li>
 * </ul>
 * 所以判据必须问的是【地球物理】问题: 我头顶之上有多少【地层】? 而不是【视觉】问题: 天空挡没挡住?
 *
 * <h3>口径 = 判据流水线 (短路 OR: 任一判据成立即"地表"; 全部不成立才"地下"; 20260923 判据6 提前)</h3>
 * <pre>
 *   判据0 维度退化    非主世界 → 退回 y &lt; 58 (下界有基岩顶、末地有穹顶, 海拔法无意义)
 *   判据6 深度拦截【最先表决, 20260923 提前】 主世界 y &lt; 0 (负 Y 深板岩层) → 无条件判地下。
 *        物理事实先于一切扫描; 旧位置在判据8 之后, 判据8 短路打响时它根本没机会执行。
 *   判据8 地表签名【主判据】 脚下踩着/身旁长着只在露天生成的方块 → 判地表。三级:
 *        强签名(脚下草方块/沙/陶瓦等地形块, 独立通道) ;
 *        弱签名(草丛/花/蕨等植被) 近扫(半径2 脚/头两高度), <b>近场出现繁茂洞穴专属方块
 *        (苔藓/洞穴藤蔓/孢子花/垂根/垂滴叶) 时否决</b> —— 苔藓块上天然长草(52%)不需天光;
 *        远扫(自身高度 ±3、水平半径12, 岩石山地/恶地坡面脚下是石头时靠它), 仅近场无地下标记时执行。
 *        全部命中需过 LOS 透视门; 液体护栏防湖底误判 (用户拍板 20260918 "他是主要判据")
 *   判据1 地形埋深    5 点采样(自身列 + 4 水平邻柱同高)的【头顶地形格数中位数】≤ 4 → 地表
 *   判据2 植被不算地板 取值时跳过树叶/原木/植物 → 树冠/藤蔓不构成"埋" (内建于扫描, 非独立判据)
 *   判据4 确认窗      非对称: 判"地下"须【持续】≥5s 才提交 (用户"一秒离开不许触发回升");
 *                     判"地表"只须 0.5s (临破土/出洞快确认)
 *   判据5 未知不翻面  采样列区块未加载 → 该列记 UNKNOWN; 有效点 &lt; 3 时维持现状, 不许翻面
 *   判据7 (已并入判据1) 20260918-1050: 判据1 换轴为"从 wsTop 向下拨植被到真实地形"后,
 *        服务端高度图本身就是埋深的锚, 数据洞走保守分支 —— 独立的 hb&gt;32 判据不再需要
 * </pre>
 * 判据1 的取值语义: "该列从头顶往上要挖穿几格【非植被地层】才能见天"。≤4 浅 → 地表; 越大越深。
 * 取 5 点的<b>中位数</b>而不是最小/最大: 取最小 → 深竖井被判地表(20260917-120830 35 分钟上不去);
 * 取最大 → 站地表旁边有高墙就判深地下。中位数 = "多数采样点看到的世界"。
 *
 * <h3>为什么不能再用高度图 (20260918 定案, 反编译实锤)</h3>
 * <ul>
 *   <li>20260918-083636: bot 在 y=-41..-47 的超大洞穴, 23 条 {@code [Diag-water] surfaceGate}
 *       清一色 {@code topY=-64}(世界底哨兵) → 埋深恒为负 → 恒判"地表"。洞有多大、附近有没有树
 *       都无关 —— 高度图压根没给真值。</li>
 *   <li>反编译 {@code Chunk.sampleHeightmap}: 高度图缺失时 {@code populateHeightmaps(EnumSet.of(type))}
 *       <b>现场新建一张全空高度图</b>, 全空 = 恒返回世界底。{@code MOTION_BLOCKING_NO_LEAVES}
 *       在客户端 WorldChunk 上正是这种"缺失后建出来但无人维护"的状态 (服务端 serialize 虽然写了它,
 *       客户端侧从未被跟踪更新; {@code WORLD_SURFACE}/{@code MOTION_BLOCKING} 则正常 ——
 *       旧版 {@code isSkyVisible} 走 WORLD_SURFACE 一直给真值)。</li>
 *   <li>结论: <b>本类不再查询任何 Heightmap</b>, 埋深一律用 {@link #SCAN_UP_MAX} 内的逐块扫描
 *       (块状态是权威数据, 客户端永远有值)。WORLD_SURFACE 高度图只用来给扫描封顶和做诊断对照。</li>
 * </ul>
 *
 * <h3>翻车史（这些都是实测事故, 别再回去）</h3>
 * 纯 y 阈值: 露天山谷 y=57 被判深地下反复爬山; 20260917-120830 y&lt;61 锚点失效 35 分钟上不去。
 * 单点天空采样: bobbing 每 tick 抖一次 (20260916-1146 rejectedFlaps 90+/5s)。
 * <b>5 点 isSkyVisible 投票</b>: 树叶算遮挡 → 树冠判地下, 床链被 GoToSurfaceTask 顶碎
 * (20260917-143636, 3 羊毛在手整夜没做成床)。
 * <b>NO_LEAVES 高度图</b>: 客户端恒哨兵 -64 → 一切洞穴判地表 (20260918-083636, 本类上一版)。
 *
 * <h3>与统一前 7 种口径的映射（名字保留, 实现在本类）</h3>
 * <pre>
 *   A WorldHelper.isDeepUnderground              → isBelowSurfaceDebounced
 *   B BeatMinecraft2Task.isUndergroundDebounced   → 转发 A (2s 双稳态已删)
 *   D BeatMinecraft2Task.isBelowSeaLevelDebounced → 保留未动 (语义是"潜到水下熬夜挖", 不是海拔)
 *   E MineAndCollectTask (内联 y &lt; 61)           → isBelowSurfaceDebounced
 *   F MobDefenseChain.isUnderground               → isBelowSurface (无迟滞: 状态机要"当下这一刻")
 *   G GoToSurfaceTask.isDeepForEscape             → isBelowSurfaceDebounced
 *   I CollectBucketLiquidTask.isBelowSurface      → isBelowSurfaceDebounced (标签并入 [Surface])
 * </pre>
 *
 * <h3>不适用本判据的例外（语义不同, 别硬套）</h3>
 * <ul>
 *   <li>{@code BeatMinecraft2Task.isBelowSeaLevelDebounced}（y&lt;55）: 判"潜到水下熬夜继续挖"的深度。</li>
 *   <li>{@code WorldHelper.getPitEscapeY}（十字 4 向 × 4/8/12/16 扫对向高墙）: 判"被困峡谷低洼"。</li>
 *   <li>{@code GoToSurfaceTask.inShaft}: 判"站在自挖竖井底部"。与判据1 互补, 删不得。</li>
 * </ul>
 *
 * <h3>成本</h3>
 * 每列最多扫 {@link #SCAN_UP_MAX}(128) 格; 5 点采样带 {@link #SAMPLE_CACHE_MS}(200ms) 缓存,
 * 每秒最多 ~3200 次块状态读 (远低于 MC 自身光照更新的量级)。static 状态已接入 {@code WorldStateGuard.registerAll()} 的
 * {@code SurfaceHelper::onWorldBoundary} 复位。
 */
public final class SurfaceHelper {

    private SurfaceHelper() {
    }

    /** 非主世界退化的 y 阈值，取值与统一前的 {@code WorldHelper.isDeepUnderground} 一致。 */
    private static final int NON_OVERWORLD_DEEP_Y = 58;

    /**
     * 判据1 的阈值: 5 点头顶地形格数中位数 ≤ 本值 → 仍算"露天活动层"。
     * 取 4 的依据: 自挖浅坑/树坑通常 ≤3 格; 而真矿道/洞穴顶板厚度普遍 >8 格。
     * (原判据3 水平环探 20260918 裁撤: 场景被判据8 签名 + 判据1 覆盖, 余下误判自愈成本低。)
     */
    private static final int NEAR_SURFACE_MAX = 4;

    /**
     * 判"地下"的确认窗 (20260918 用户"肯定不能一秒钟离开地表就触发地表回升"):
     * raw 判地下须【持续】满此时长才提交翻面/触发回升链 —— 瞬间低头、跳下浅坑、
     * 单 tick 数据抖动都不会打响 GoToSurface。
     */
    private static final long CONFIRM_BELOW_MS = 5000;

    /** 判"地表"的确认窗 (短): 临破土/出洞要快确认, 与"回升须谨慎"非对称。 */
    private static final long CONFIRM_SURFACE_MS = 500;

    /**
     * 判据1 向下走时允许跳过的最大植被/树冠厚度 (48: 丛林最高树 ~30 + 余量)。
     * 超过视为数据异常 → 保守按整柱实心算 (宁可判地下, 不把数据洞读成露天)。
     */
    private static final int MAX_CANOPY_WALK = 48;

    /** 5 点采样缓存时长 (逐块扫描比高度图贵一个量级, 200ms 内复用同一结论)。 */
    private static final long SAMPLE_CACHE_MS = 200;

    /**
     * 判据6 深度拦截: 主世界 y 低于本值 → 无条件判"地下"。
     * 依据: 负 Y 是深板岩层, <b>天然地形里不存在通天的露天柱</b> (最低的天然露天是海床 ~y=25-45)。
     * 所以"扫描说露天"在这个深度带必然是异常 (20260918-092740 实锤: 超大洞穴 y=-44, 客户端对
     * 头顶柱的视图整体缺失 40s+, rejectedFlaps=236, 五列同时 buried=0), 深度物理事实优先于扫描。
     * 已知代价: 1.18 峡谷(ravine)底可到 y&lt;0 且通天, 在那里会被判"地下" → GoToSurface 会尝试爬出去
     * —— 行为上等价于"走出峡谷", 可接受。
     */
    private static final int DEEP_CRUST_Y = 0;

    /**
     * 采样列"不可判定"哨兵 (区块未加载 / 世界未就绪)。
     * 参与中位数的只有有效点; 有效点 &lt; 3 时整轮判未知 → 维持现状不翻面 (判据5)。
     * <b>任何比较都必须先判 UNKNOWN</b> —— 它是 Integer.MIN_VALUE, 会被 "≤ NEAR_SURFACE_MAX" 命中。
     */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    // ===== 迟滞状态 (static: 结论必须跨任务实例存活, 且全局唯一) =====
    // 放 static 的理由与语义: "bot 现在在不在表面"是【全局事实】, 所有调用方
    // 理应看到同一个答案。若各调用方各持一份迟滞, 就会出现 A 说在地表、B 说
    // 在地下 —— 那正是统一前 5 种口径互相打架的根源。
    private static boolean _below = false;
    private static long _setAt = 0;
    private static boolean _init = false;
    private static long _flapRejected = 0;
    // 确认窗状态: raw 最近一次变化的时刻 (候选翻面已持续多久)
    private static boolean _lastRaw = false;
    private static long _rawChangedAt = 0;
    private static boolean _flapCounted = false;

    // 5 点采样缓存 (pos 相同且未过期时复用; 逐块扫描每秒全量做 5 次没必要)
    private static int[] _sampleCache = null;
    private static long _sampleAt = 0;
    private static BlockPos _samplePos = null;

    /**
     * 上一拍判定时的维度 (2026-09-21)。
     *
     * <p>判据口径本身是<b>维度相关</b>的: 主世界走"埋深"(判据1/判据8), 下界/末地退化为
     * {@code y < 58} (判据0)。跨维度后旧结论在语义上就是"另一个世界的答案", 必须整体作废
     * —— 不能靠 5s/0.5s 确认窗慢慢纠正。
     *
     * <p>实锤 (20260921-091405): bot 在下界 y=69 被判 {@code surface(open)} (判据0 退化
     * 口径, 见 chat.log 09:16:10), 09:18:46.7 穿门回主世界落在 y=19 的矿洞; 09:18:48.3
     * 定位任务查 {@code isDeepUnderground} 仍得 false (旧结论 false + 判"地下"要 5s 确认窗,
     * 而它只等了 1.6s) → 就地丢末影之眼, 紧接着 {@code setupWalk} 朝眼睛方向派 240 格远征。
     *
     * <p>为什么不能只靠 {@code WorldStateGuard}: 它比对的是<b>存档目录名</b>, 换存档才触发;
     * 同一存档内跨维度 (下界↔主世界) 完全不经过它。
     */
    private static Dimension _lastDim = null;

    /**
     * 维度/世界切换后, 一切"按坐标缓存"的采样结论都失效 —— 那些坐标属于另一个世界。
     * 不清会怎样: 采样缓存按 {@code BlockPos} 命中, 主世界与下界坐标空间不同却有重合值
     * (下界 (1271,87,-1377) 与主世界某处同坐标), 复活后可能直接吃到上个维度的埋深。
     */
    private static void invalidateCoordinateCaches() {
        _sampleCache = null;
        _sampleAt = 0;
        _samplePos = null;
        _sigPos = null;
        _sigAt = 0;
        _sigYes = false;
    }

    /**
     * 换存档时复位 (接入 {@code WorldStateGuard})。迟滞状态属于"上个存档的结论",
     * 留着会让新存档的首个判定被旧结论拖 5s。
     */
    public static void onWorldBoundary() {
        _below = false;
        _setAt = 0;
        _init = false;
        _flapRejected = 0;
        _lastRaw = false;
        _rawChangedAt = 0;
        _flapCounted = false;
        invalidateCoordinateCaches();
        _lastDim = null;
        _lastUndergroundVeto = false;
    }

    // ==================================================================
    // 判据取值 (公开: 供诊断日志、单元式验证、以及需要自定义阈值的调用方)
    // ==================================================================

    /**
     * 该列是否是植被/空气类"不算地层"的方块 (判据2 的实体)。
     * 树叶在 vanilla 里没有碰撞体积; 原木/竹子有碰撞但属于"树", 不是"压在我头顶的地层"
     * (不然站任何树旁都被判深埋)。植物/雪层本来就不挡移动。水/岩浆<b>算</b>地层 ——
     * 与旧 {@code MOTION_BLOCKING_NO_LEAVES} 语义(含流体)保持一致。
     */
    private static boolean isNotTerrain(World w, int x, int y, int z) {
        var state = w.getBlockState(new BlockPos(x, y, z));
        if (state.isAir()) return true;
        if (state.isIn(BlockTags.LEAVES)) return true;
        if (state.isIn(BlockTags.LOGS)) return true;
        if (state.isOf(Blocks.BAMBOO) || state.isOf(Blocks.BAMBOO_SAPLING)) return true;
        // 不挡移动且非流体的 → 植物/雪层之类, 不算地层
        return !state.getMaterial().blocksMovement() && !state.getMaterial().isLiquid();
    }

    /**
     * 判据1【20260918 10:50 三次换轴后的最终语义】某列的"地表埋深" =
     * <b>拨开头顶植被后, 距地表地形的格数</b>。
     *
     * <p>实现: 从服务端 WORLD_SURFACE 高度图顶 ({@code wsTop-1}) 向下走, 跳过树叶/原木/植物
     * (树冠不算地层), 遇到的第一块真实地形即"地表"; 埋深 = 地表 y − 脚 y。
     *
     * <p><b>为什么必须这样量</b> (20260918-105034 实锤): 旧"向上扫第一个遮挡"量的是
     * <b>洞顶厚度</b> —— bot 在 y=38 的矿洞里, 洞顶 2 格 → buried=2 → 误判"浅坑=地表",
     * 而真实距地表 32 格石层。"压在头顶的地层"必须追溯到天空, 一个薄洞顶不算数。
     *
     * <p>数据洞护栏: 若 {@code wsTop-1} 处客户端说是空气 (高度图≠方块数据, 092740/094844 的
     * 错装空气形态) → 不向下落, 直接按保守口径记埋深 = hb−1 (即整柱视为实心) —— 宁可判地下,
     * 不会把数据洞读成露天。
     *
     * <p><b>不再查询 MOTION_BLOCKING_NO_LEAVES 高度图</b> —— 客户端上它恒返回世界底哨兵
     * (20260918-083636 反编译定案)。WORLD_SURFACE 有真值 (三次事故定案), 植被由向下走跳过。
     *
     * @return 埋深格数 (负数 = 就站在地表上); {@link #UNKNOWN} = 区块未加载/世界未就绪。
     */
    public static int overheadTerrain(AltoClef mod, int x, int y, int z) {
        World w = mod.getWorld();
        if (w == null) return UNKNOWN;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return UNKNOWN;
        int wsTop = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        if (wsTop <= w.getBottomY() + 1) return UNKNOWN; // 高度图哨兵 → 不可判定
        BlockPos.Mutable m = new BlockPos.Mutable();
        int top = wsTop - 1;
        m.set(x, top, z);
        if (w.getBlockState(m.toImmutable()).isAir()
                && !(w.getBlockState(m.toImmutable()).getBlock() instanceof net.minecraft.block.LightBlock)) {
            // (2026-10-03) 光源方块豁免: 服务端高度图会计入隐形光源 (isAir=false),
            // 客户端语义它是空气 —— 不算数据洞, 落下去由 isNotTerrain (isAir) 正常跳过。
            // 高度图说这里非空气, 客户端方块却说是空气 → 数据洞 (092740/094844 形态)。
            // 保守: 整柱按实心算, 埋深 = hb − 1。绝不向下落 —— 落下去就会把洞穴顶当地表。
            return (wsTop - 1) - y;
        }
        int skipped = 0;
        int yy = top;
        while (yy > y) {
            m.set(x, yy, z);
            if (!isNotTerrain(w, x, yy, z)) break; // 第一块真实地形 = 地表
            if (++skipped > MAX_CANOPY_WALK) {     // 树冠不可能这么厚 → 数据异常, 保守处理
                return (wsTop - 1) - y;
            }
            yy--;
        }
        return yy - y;
    }

    /**
     * 判据1 的 5 点采样: 下标 0 = 玩家自身列, 1..4 = 东/西/南/北邻柱(<b>同高</b>采样)。
     * 不可判定的列为 {@link #UNKNOWN}。带 {@link #SAMPLE_CACHE_MS} 缓存。
     *
     * <p>邻柱取"同高"而不是"邻柱顶部": 在 1 格宽阶梯地道/竖井里邻柱整列都是实心, 取顶部会
     * 得到远处山体的高度而失去"我这一格被压了多少地层"的信息。
     */
    public static int[] overheadSamples(AltoClef mod) {
        BlockPos pos = mod.getPlayer() != null ? mod.getPlayer().getBlockPos() : null;
        long now = System.currentTimeMillis();
        if (pos != null && _sampleCache != null && _samplePos != null
                && _samplePos.equals(pos) && now - _sampleAt < SAMPLE_CACHE_MS) {
            return _sampleCache;
        }
        int[] out = new int[5];
        if (pos == null || mod.getWorld() == null) {
            for (int i = 0; i < 5; i++) out[i] = UNKNOWN;
            _sampleCache = out;
            _samplePos = pos;
            _sampleAt = now;
            return out;
        }
        int[][] dirs = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < 5; i++) {
            out[i] = overheadTerrain(mod, pos.getX() + dirs[i][0], pos.getY(), pos.getZ() + dirs[i][1]);
        }
        _sampleCache = out;
        _samplePos = pos;
        _sampleAt = now;
        return out;
    }

    /**
     * 判据1 的判定值: 5 点头顶地层格数的【中位数】(只取有效点; 有效点 &lt; 3 → {@link #UNKNOWN})。
     * 见类注释"为什么取中位数"。
     */
    public static int buriedDepth(AltoClef mod) {
        int[] s = overheadSamples(mod);
        int[] valid = new int[s.length];
        int n = 0;
        for (int v : s) {
            if (v != UNKNOWN) valid[n++] = v;
        }
        if (n < 3) return UNKNOWN; // 判据5: 采样不可信, 交上层"维持现状"
        java.util.Arrays.sort(valid, 0, n);
        return valid[n / 2];
    }

    /**
     * 诊断用原始值: 5 点里"看不见天"的点数 (0..5)。<b>旧口径, 已不参与判定</b>——
     * 它走 {@code isSkyVisible}(WORLD_SURFACE 高度图, 树叶算遮挡), 正是把树冠读成"天花板"
     * 的那条路径。留着只为在 [Surface] 日志里对照"判据换了之后结论差在哪"。
     */
    public static int skyCoveredCount(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return 0;
        BlockPos pos = mod.getPlayer().getBlockPos();
        int notVisible = 0;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            m.set(pos.getX() + d[0], pos.getY() + 1, pos.getZ() + d[1]);
            if (!mod.getWorld().isSkyVisible(m.toImmutable())) notVisible++;
        }
        return notVisible;
    }

    /**
     * 诊断用: 自身列"头顶植被层厚度" = 从高度图顶向下走到第一块真实地形之间跳过的
     * 树叶/原木/植物格数 (>0 = 判据2 在起作用, 头顶那层是树冠)。
     */
    public static int vegetationCapThickness(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return 0;
        BlockPos pos = mod.getPlayer().getBlockPos();
        World w = mod.getWorld();
        int wsTop = w.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
        if (wsTop <= w.getBottomY() + 1) return 0;
        int veg = 0;
        for (int yy = wsTop - 1; yy > pos.getY() && veg < MAX_CANOPY_WALK; yy--) {
            if (isNotTerrain(w, pos.getX(), yy, pos.getZ())) veg++;
            else break;
        }
        return veg;
    }

    // ==================================================================
    // 判定
    // ==================================================================

    /**
     * 判"在地下"原始值 (无迟滞): 走完整条判据流水线 (见类注释)。
     *
     * <p>需要"一次判定立刻生效"的场合用它 (如 {@code MobDefenseChain} 的每 tick 状态机);
     * 需要稳定结论的场合用 {@link #isBelowSurfaceDebounced}。
     */
    public static boolean isBelowSurface(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        BlockPos pos = mod.getPlayer().getBlockPos();

        // 判据0: 非主世界 → 海拔退化 (下界基岩顶/末地穹顶会让埋深法失真)
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            return pos.getY() < NON_OVERWORLD_DEEP_Y;
        }

        // 判据6: 深度拦截 (20260923 提前到判据8之前) —— 判据8 是短路表决, 若它先打响,
        // 深度拦截根本没机会执行; y&lt;0 是物理事实, 应最先表决。
        if (pos.getY() < DEEP_CRUST_Y) return true;

        // 判据8【主判据】: 地表签名 —— 脚下踩着/身旁/远扫范围内长着只在露天生成的方块,
        // 且【透视门 LOS】射线可达 (隔墙的草地不算, 20260918 用户点破)。
        // 人判地表的第一反应就是"我看到草和花了" (用户拍板 20260918: "他是主要判据")。
        // 快速、不依赖头顶柱数据 (只读脚下与身旁), 所以爬升临破土/刚破土的场景它先说话。
        // 20260923: 繁茂洞穴苔藓块上天然生成短草(52%)/高草(10%)不需天光, 弱签名曾因此误命中
        // → 近场出现 lush 专属方块时否决弱签名 (强签名脚下草方块保留, 真地表杜鹃树不受影响)。
        if (nearbySurfaceSignature(mod, pos)) return false;

        // 判据1【三次换轴后的最终语义】: 拨开头顶植被后, 距地表地形的格数 (中位数)。
        // 量的是"离地表还有多深"而不是"洞顶多薄" —— 105034 实锤: 矿洞 y=38 洞顶 2 格,
        // 旧"向上扫第一个遮挡"量出 buried=2 误判"浅坑=地表", 真实距地表 32 格石层。
        // 判据2 (植被不算地层) 内建于向下走的跳过集; 判据7 (hb>32) 已并入:
        // 从 wsTop 向下走本身就以服务端高度图为锚, 数据洞走保守分支, 不再需要独立判据。
        int buried = buriedDepth(mod);
        // 判据5: 采样不可信 (区块未加载/高度图哨兵) → 维持现状, 绝不许把 UNKNOWN 误读成露天
        // (UNKNOWN = Integer.MIN_VALUE, 不挡这条就等价于 "埋深极小 → 恒判地表", 20260918 死因同款)
        if (buried == UNKNOWN) return _init && _below;

        if (buried <= NEAR_SURFACE_MAX) return false;

        return true;
    }

    /**
     * 判据8 强签名【脚下地形块】: 草方块/灰化土/菌丝体/沙/红沙/雪块/陶瓦。
     * 天然只在露天表层生成。<b>不受地下反证影响</b>: 繁茂洞穴地面是苔藓块而非草方块,
     * bot 自行在地下放置这些方块的极端情况代价可接受。
     *
     * (2026-09-24) 改 public: {@link SurfaceExitGuide} 出洞安全落点扫描复用本集合
     * 作为"必落草方块等地表块"的唯一真相源, 不另起一份清单。
     */
    public static boolean isStrongSurfaceSignature(net.minecraft.block.BlockState s) {
        net.minecraft.block.Block b = s.getBlock();
        return b == net.minecraft.block.Blocks.GRASS_BLOCK
                || b == net.minecraft.block.Blocks.PODZOL
                || b == net.minecraft.block.Blocks.MYCELIUM
                || b == net.minecraft.block.Blocks.SAND
                || b == net.minecraft.block.Blocks.RED_SAND
                || b == net.minecraft.block.Blocks.SNOW_BLOCK
                || b == net.minecraft.block.Blocks.TERRACOTTA;
    }

    /**
     * 判据8 弱签名【植被】: 草丛/高草/蕨/大蕨、花(整族)、仙人掌、枯灌木、甘蔗。
     *
     * <p>旧注释假设"草和花需要天光才能存活"——<b>对草丛不成立</b>: 繁茂洞穴的苔藓块
     * 天然生成短草(52%)/高草(10%)/杜鹃, 骨粉催苔藓也能在无天光处长草 (20260923 实锤事故)。
     * 故弱签名表决时必须过 {@link #isUndergroundSignature} 的近场反证。
     */
    private static boolean isWeakSurfaceSignature(net.minecraft.block.BlockState s) {
        net.minecraft.block.Block b = s.getBlock();
        return b == net.minecraft.block.Blocks.GRASS
                || b == net.minecraft.block.Blocks.TALL_GRASS
                || b == net.minecraft.block.Blocks.FERN
                || b == net.minecraft.block.Blocks.LARGE_FERN
                || b == net.minecraft.block.Blocks.CACTUS
                || b == net.minecraft.block.Blocks.DEAD_BUSH
                || b == net.minecraft.block.Blocks.SUGAR_CANE
                || s.isIn(BlockTags.FLOWERS);
    }

    /**
     * 判据8 反证: <b>繁茂洞穴专属方块</b> —— 近场出现即说明"我在洞里, 看到的草是苔藓草,
     * 不是露天草", 弱签名一律否决 (20260923 用户拍板"地下签名反证")。
     *
     * <p>刻意<b>不纳入</b>: 杜鹃/杜鹃花丛 (地表杜鹃树本身, 真地表)、缠根泥土 (地表杜鹃树下
     * 即有)、原木/树叶 (废弃矿井与所有树木)、粘土 (湖底)。真地表杜鹃树下 bot 脚下是草方块,
     * 强签名通道独立判地表, 与本反证互不干扰。
     */
    private static boolean isUndergroundSignature(net.minecraft.block.BlockState s) {
        net.minecraft.block.Block b = s.getBlock();
        return b == net.minecraft.block.Blocks.MOSS_BLOCK
                || b == net.minecraft.block.Blocks.MOSS_CARPET
                || b == net.minecraft.block.Blocks.CAVE_VINES
                || b == net.minecraft.block.Blocks.CAVE_VINES_PLANT
                || b == net.minecraft.block.Blocks.SPORE_BLOSSOM
                || b == net.minecraft.block.Blocks.HANGING_ROOTS
                || b == net.minecraft.block.Blocks.BIG_DRIPLEAF
                || b == net.minecraft.block.Blocks.SMALL_DRIPLEAF;
    }

    /** 判据8 结果缓存时长 (近扫 ~50 次块读; 远扫最坏 ~4400 次, 靠 500ms 缓存 + 2 格网格键摊平)。 */
    private static final long SIGNATURE_CACHE_MS = 500;

    /**
     * 判据8 远扫的水平半径 (20260918 用户"岩石地貌脚下是石头, 远处一定范围内看到特殊方块也算地表"):
     * 石山/恶地坡面上脚下方块是石头, 但 ±12 格、自身高度 ±3 内必有草/花/红沙等露天签名。
     */
    private static final int SIGNATURE_FAR_RADIUS = 12;

    /** 判据8 远扫的纵向容差 (自身高度 ±3: 覆盖缓坡与 2 格高花草; 再大会把浅层洞穴顶上方的草地误认)。 */
    private static final int SIGNATURE_FAR_Y_TOLERANCE = 3;

    private static boolean _sigYes = false;
    private static long _sigAt = 0;
    private static BlockPos _sigPos = null;

    /**
     * 最近一次扫描的"近场地下反证"结果。仅在 true↔false 翻转时打一行诊断 (不按 tick 刷)
     * —— 验证繁茂洞穴修复是否生效的观测量: 进洞应见 {@code marker found -> ... vetoed}。
     */
    private static boolean _lastUndergroundVeto = false;

    /**
     * 判据8 主体: ①强签名 —— 脚下地形块直接判地表 (不经地下反证);
     * ②近扫 —— 脚/头高度半径 ≤2, 同时收集弱签名与地下标记; 近场有地下标记 → 弱签名否决;
     * ③远扫 —— 仅近场无地下标记时, 自身高度 ±3、水平半径 12 内任意签名 (岩石山地正例)。
     * 结果缓存 (键按 2 格网格取整, 走动时不必每 tick 全量重扫); 浸在液体里时恒 false
     * (水下沙/粘土不是露天活动层)。
     */
    private static boolean nearbySurfaceSignature(AltoClef mod, BlockPos pos) {
        // 2 格网格键: 移动中最多每 2 格重扫一次, 而不是每 tick
        BlockPos key = new BlockPos(pos.getX() & ~1, pos.getY(), pos.getZ() & ~1);
        long now = System.currentTimeMillis();
        if (_sigPos != null && _sigPos.equals(key) && now - _sigAt < SIGNATURE_CACHE_MS) return _sigYes;
        _sigAt = now;
        _sigPos = key;
        _sigYes = false;
        World w = mod.getWorld();
        if (w == null) return false;
        // 液体护栏: 头或脚浸在液体里 → 不认签名
        if (w.getBlockState(pos).getMaterial().isLiquid()
                || w.getBlockState(pos.up()).getMaterial().isLiquid()) return false;
        // ①强签名: 脚下地形块 —— 独立通道, 不受地下反证影响
        if (isStrongSurfaceSignature(w.getBlockState(pos.down()))) {
            _sigYes = true;
            return true;
        }
        // ②近扫 (半径 ≤2, 脚/头两高度 ≈ 50 次块读): 同时收集弱签名与地下标记。
        // 脚下也纳入地下标记 —— 苔藓地面是繁茂洞穴最可靠的锚。
        boolean underground = isUndergroundSignature(w.getBlockState(pos.down()));
        boolean weakVisible = false;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    var state = w.getBlockState(m.toImmutable());
                    if (isUndergroundSignature(state)) underground = true;
                    if (!weakVisible && isWeakSurfaceSignature(state)
                            && losToSignature(mod, m.toImmutable(), 6)) {
                        weakVisible = true;
                    }
                }
            }
        }
        if (underground != _lastUndergroundVeto) {
            _lastUndergroundVeto = underground;
            Debug.logMessage("[Surface] lush cave marker "
                    + (underground
                            ? "found -> weak signatures vetoed (y=" + pos.getY() + ")"
                            : "cleared -> weak signatures restored"));
        }
        // 近场在繁茂洞穴内 → 苔藓草弱签名否决, 远扫同样禁止 (±3 高度内扫到的仍是洞内)
        if (underground) return false;
        if (weakVisible) {
            _sigYes = true;
            return true;
        }
        // ③远扫: 自身高度 ±3, 水平半径 ≤12 (最坏 ~4400 次块读, 500ms 缓存 + 命中早退);
        // 每个命中都过透视门 LOS —— 隔着岩壁的草地不是"我看到地表" (20260918 用户点破)。
        // 强/弱签名都查: 石山旁的相邻台地可能直接是草方块。
        for (int dy = -SIGNATURE_FAR_Y_TOLERANCE; dy <= SIGNATURE_FAR_Y_TOLERANCE; dy++) {
            for (int dx = -SIGNATURE_FAR_RADIUS; dx <= SIGNATURE_FAR_RADIUS; dx++) {
                for (int dz = -SIGNATURE_FAR_RADIUS; dz <= SIGNATURE_FAR_RADIUS; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    var state = w.getBlockState(m.toImmutable());
                    if ((isStrongSurfaceSignature(state) || isWeakSurfaceSignature(state))
                            && losToSignature(mod, m.toImmutable(), SIGNATURE_FAR_RADIUS + 3)) {
                        _sigYes = true;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 透视门: bot 眼睛到签名方块是否有不被遮挡的视线。
     * 用 {@link LookHelper#cleanLineOfSight(Entity, BlockPos, double)} (DDA 射线, 6 面判定,
     * MISS=无遮挡=可见)。没有这道门, 隔着洞壁/岩层的高度图内草地会被远扫误认成地表
     * (20260918-1050 会话用户点破"没有给特殊方块加透视限制")。
     */
    private static boolean losToSignature(AltoClef mod, BlockPos block, double maxRange) {
        if (mod.getPlayer() == null) return false;
        return LookHelper.cleanLineOfSight(mod.getPlayer(), block, maxRange);
    }

    /**
     * 判"在地下"的确认窗版 (非对称): 全项目应当优先用这个。
     *
     * <p>存在理由 (20260918 用户定调): 判"地下"必须【持续】满 {@link #CONFIRM_BELOW_MS} 才提交 ——
     * "一秒钟离开地表就触发回升"被明确禁止; 判"地表"只须 {@link #CONFIRM_SURFACE_MS} 快速确认
     * (临破土/出洞不卡)。旧"距上次翻面 ≥5s"口径的问题: 驻留期一过, 一次瞬时翻面立即生效,
     * 正是"浅坑一秒触发回升"的根源。
     *
     * <p>翻面候选被窗拦下时计一次 {@code _flapRejected} (每个候选期只计一次, 不按 tick 刷),
     * 提交翻面时打日志并清零 —— 判断"阈值定得对不对"的观测量。
     */
    public static boolean isBelowSurfaceDebounced(AltoClef mod) {
        long now = System.currentTimeMillis();
        // 维度切换: 判据口径换轴 (主世界埋深 ↔ 下界 y<58), 旧迟滞结论一律作废 ——
        // 见 _lastDim 注释。首次判定 (_lastDim == null) 不触发, 保持原首拍语义。
        // world == null (世界重载中) 时不更新 _lastDim: 否则会吃到 getCurrentDimension()
        // 的 OVERWORLD 兜底值, 把"重载空窗"误当成一次维度切换。
        if (mod.getWorld() != null) {
            Dimension dim = WorldHelper.getCurrentDimension();
            if (dim != _lastDim) {
                Dimension from = _lastDim;
                _lastDim = dim;
                invalidateCoordinateCaches();
                if (from != null) {
                    // 跨维度瞬间区块未加载 → 采样走 UNKNOWN, 而 UNKNOWN 分支读的是 _below。
                    // 取"地下"让未知落在安全一侧: 丢眼/远征闸先回地表, 而真的在地表时
                    // 同源的 GoToSurfaceTask.isFinished 也会读到 true → 任务即刻完成, 不产生
                    // 动作; 0.5s 判地表窗后自动纠正。
                    _init = true;
                    _below = true;
                    _lastRaw = true;
                    _rawChangedAt = now;
                    _flapCounted = false;
                    Debug.logMessage("[Surface] dimension change " + from + " -> " + dim
                            + ": hysteresis dropped, conservative below=true (raw re-confirms next).");
                    return true;
                }
            }
        }
        boolean raw = isBelowSurface(mod);
        if (!_init) {
            _init = true;
            _below = raw;
            _lastRaw = raw;
            _rawChangedAt = now;
            return raw;
        }
        if (raw != _lastRaw) {
            _lastRaw = raw;
            _rawChangedAt = now;
            _flapCounted = false; // 新的翻面候选期
        }
        if (raw != _below) {
            long required = raw ? CONFIRM_BELOW_MS : CONFIRM_SURFACE_MS;
            if (now - _rawChangedAt < required) {
                if (!_flapCounted) {
                    _flapCounted = true;
                    _flapRejected++;
                }
                return _below;
            }
            logTransition(mod, raw);
            _flapRejected = 0;
            _below = raw;
            // (2026-09-24 出洞安全引导) 确认翻面才通知 (候选窗内的抖动不武装):
            // below→surface 武装"前 3 个导航必落草方块等地表块"; 翻回 below 保留配额。
            if (raw) {
                SurfaceExitGuide.onBackBelow(mod);
            } else {
                SurfaceExitGuide.onReachedSurface(mod);
            }
        }
        return _below;
    }

    /**
     * 翻面日志。字段固定为可 grep 的形状 {@code [Surface] mode -> ...}:
     * <ul>
     *   <li>{@code y}      玩家脚所在 y;</li>
     *   <li>{@code buried} 判据1 的中位数 = 头顶压着几格地层 (拍板判据; "unk"=有效点不足3);</li>
     *   <li>{@code oh=[..]} 5 点原始采样, 下标 0 = 自身列, 其余为 东/西/南/北; "?"=该列不可判定;</li>
     *   <li>{@code vegCap} 自身列头顶植被厚度 (>0 = 判据2 正在起作用, 头顶那层是树叶/原木/植物);</li>
     *   <li>{@code covered} 旧口径 (isSkyVisible) 票数 —— 仅用于对照"判据换了结论差在哪"。</li>
     * </ul>
     */
    private static void logTransition(AltoClef mod, boolean raw) {
        int[] oh = overheadSamples(mod);
        StringBuilder sb = new StringBuilder();
        int unkCount = 0;
        for (int i = 0; i < oh.length; i++) {
            if (i > 0) sb.append(',');
            if (oh[i] == UNKNOWN) {
                sb.append('?');
                unkCount++;
            } else {
                sb.append(oh[i]);
            }
        }
        int y = mod.getPlayer() != null ? mod.getPlayer().getBlockPos().getY() : -1;
        int buried = buriedDepth(mod);
        // 诊断: 自身列 WORLD_SURFACE 高度图 (扫描封顶用)。正常应≈真实地形顶; 若出现
        // 与 y 相近的怪值 → 客户端头顶柱数据异常 (20260918-092740 的深部判地表疑似此因)
        int wsTop = -999999;
        int hb = -999999;
        if (mod.getPlayer() != null && mod.getWorld() != null) {
            BlockPos p = mod.getPlayer().getBlockPos();
            wsTop = mod.getWorld().getTopY(Heightmap.Type.WORLD_SURFACE, p.getX(), p.getZ());
            hb = wsTop - (p.getY() + 1); // 高度图埋深 (判据7 已并入判据1, 此字段留作诊断对照)
        }
        Debug.logMessage("[Surface] mode -> "
                + (raw ? "below(terrain)" : "surface(open)")
                + " (y=" + y
                + ", buried=" + (buried == UNKNOWN ? "unk" : String.valueOf(buried))
                + ", oh=[" + sb + "]"
                + ", unk=" + unkCount
                + ", wsTop=" + wsTop
                + ", hb=" + hb
                + ", vegCap=" + vegetationCapThickness(mod)
                + ", covered=" + skyCoveredCount(mod) + "/5"
                + ", dim=" + WorldHelper.getCurrentDimension()
                + ", rejectedFlaps=" + _flapRejected + ")");
    }
}
