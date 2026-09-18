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
 * <h3>口径 = 判据流水线 (任一判据成立即"地表"; 全部不成立才"地下"; 20260918 裁撤判据3 环探, 判据8 升主判据)</h3>
 * <pre>
 *   判据0 维度退化    非主世界 → 退回 y &lt; 58 (下界有基岩顶、末地有穹顶, 海拔法无意义)
 *   判据8 地表签名【主判据, 最先表决】 脚下踩着/身旁长着只在露天生成的方块 → 判地表。
 *        两级: 近扫(脚下+半径2 脚/头两高度) 与 远扫(自身高度 ±3、水平半径 12, 岩石山地/恶地坡面
 *        脚下是石头时靠它) = 人判地表的"脚下质感 + 身旁景物"通道; 只读脚下与身旁, 不依赖头顶柱
 *        数据, 爬升临破土场景它先说话 (用户拍板 20260918 "他是主要判据"); 液体护栏防湖底误判
 *   判据1 地形埋深    5 点采样(自身列 + 4 水平邻柱同高)的【头顶地形格数中位数】≤ 4 → 地表
 *   判据2 植被不算地板 取值时跳过树叶/原木/植物 → 树冠/藤蔓不构成"埋" (内建于扫描, 非独立判据)
 *   判据4 确认窗      非对称: 判"地下"须【持续】≥5s 才提交 (用户"一秒离开不许触发回升");
 *                     判"地表"只须 0.5s (临破土/出洞快确认)
 *   判据5 未知不翻面  采样列区块未加载 → 该列记 UNKNOWN; 有效点 &lt; 3 时维持现状, 不许翻面
 *   判据6 深度拦截    主世界 y &lt; 0 (负 Y 深板岩层) → 无条件判地下
 *        (依据: 天然地形不存在这么深的通天露天柱; 深处客户端头顶柱视图有整体缺失的异常,
 *         20260918-092740: 超大洞穴 y=-44 五列同时 buried=0 稳定 40s+, 深度物理事实兜底)
 *   判据7 高度图交叉  距最高非空气方块 (WORLD_SURFACE 高度图, 服务端下发、深处稳定) &gt; 32 格
 *        → 无条件判地下 (094844: y=0..9 判据6 拦不到, 扫描仍报"露天"而 wsTop=63; 深度物理事实兜底)
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
     * 逐块扫描的向上封顶: 头顶 128 格内没有任何非植被实心 → 判"露天"
     * (地表任意点离最近的实心地层不会超过 128; 真洞穴顶板必然在 128 内)。
     */
    private static final int SCAN_UP_MAX = 128;

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
     * 判据7 阈值: 自身列"高度图埋深" (最高非空气方块 y − 脚 y − 1) 超过本值 → 无条件判地下,
     * 不采信逐块扫描 (该深度带的客户端方块数据已被实测证明会整体缺失/错装空气)。
     * 取 32: 任意树冠 (含丛林最高 ~30) 以内; 天然洞穴顶板厚度普遍 &gt;32。
     * 已知代价: 通天峡谷/竖井深处会被判地下 (与判据6 同向, 可接受)。
     */
    private static final int SCAN_TRUST_MAX_HEIGHTMAP_BURIAL = 32;

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
        _sampleCache = null;
        _sampleAt = 0;
        _samplePos = null;
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
     * 向上扫描一列: 从 {@code yFrom+1} 扫到 {@code capTop}(不含), 返回第一个【地层】方块的 y。
     *
     * @return 第一个非植被实心(或流体)方块的 y; {@code -1} = 扫到顶都是空的(露天);
     *         {@link #UNKNOWN} = 区块未加载/世界未就绪。
     */
    private static int scanUpForCover(World w, int x, int z, int yFrom, int capTop) {
        if (w == null) return UNKNOWN;
        // 区块未加载 → 明确"不可判定", 绝不静默当露天 (20260918: 未加载哨兵 -64 被误读成露天是直接死因)
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return UNKNOWN;
        // WORLD_SURFACE 高度图客户端有真值 (isSkyVisible 历来靠它), 拿来封顶 + 哨兵自检
        int wsTop = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        if (wsTop <= w.getBottomY() + 1) return UNKNOWN; // 高度图哨兵 → 不可判定
        int top = Math.min(capTop, wsTop - 1);
        for (int y = yFrom + 1; y <= top; y++) {
            if (!isNotTerrain(w, x, y, z)) return y;
        }
        return -1;
    }

    /**
     * 某列"头顶之上的地层格数" = 第一个非植被实心方块的 y − (y+1)。
     *
     * <p>≤{@link #NEAR_SURFACE_MAX} 浅 → 一定在露天活动层; 数值 = 该列头顶往上要挖穿几格地层才能见天。
     * 头顶 {@link #SCAN_UP_MAX} 格内无地层 → 返回 0 (露天)。
     *
     * <p><b>不再查询 MOTION_BLOCKING_NO_LEAVES 高度图</b> —— 客户端上它恒返回世界底哨兵
     * (20260918-083636 反编译定案), 见类注释"为什么不能再用高度图"。
     *
     * @return 埋深格数; 0 = 露天(或头顶 128 格内无地层); {@link #UNKNOWN} = 区块未加载/世界未就绪。
     */
    public static int overheadTerrain(AltoClef mod, int x, int y, int z) {
        World w = mod.getWorld();
        if (w == null) return UNKNOWN;
        int cover = scanUpForCover(w, x, z, y, y + SCAN_UP_MAX);
        if (cover == UNKNOWN || cover < 0) return cover == UNKNOWN ? UNKNOWN : 0;
        return cover - (y + 1);
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
     * 诊断用: 玩家自身列"头顶植被层厚度" = 从头顶向上扫描时跳过的树叶/原木/植物格数
     * (直到第一个地层或封顶)。>0 说明正处在树冠/植被之下 —— 判据2 生效的地方。
     */
    public static int vegetationCapThickness(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return 0;
        BlockPos pos = mod.getPlayer().getBlockPos();
        World w = mod.getWorld();
        int veg = 0;
        int top = Math.min(pos.getY() + SCAN_UP_MAX,
                w.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ()) - 1);
        for (int y = pos.getY() + 1; y <= top; y++) {
            if (isNotTerrain(w, pos.getX(), y, pos.getZ())) veg++;
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

        // 判据8【主判据, 最先表决】: 地表签名 —— 脚下踩着/身旁长着只在露天生成的方块。
        // 人判地表的第一反应就是"我看到草和花了" (用户拍板 20260918: "判据8 必须前移, 他是主要判据")。
        // 快速、不依赖头顶柱数据 (只读脚下与身旁), 所以爬升临破土/刚破土的场景它先说话,
        // 不会被深部数据异常或迟滞拖住。 lush 洞穴的苔藓/杜鹃/粘土已刻意排除, 井下不会误触发。
        if (nearbySurfaceSignature(mod, pos)) return false;

        // 判据1(+判据2 内建): 5 点头顶地层格数中位数
        int buried = buriedDepth(mod);
        // 判据5: 采样不可信 (区块未加载等) → 维持现状, 绝不许把 UNKNOWN 误读成露天
        // (UNKNOWN = Integer.MIN_VALUE, 不挡这条就等价于 "埋深极小 → 恒判地表", 20260918 死因同款)
        if (buried == UNKNOWN) return _init && _below;

        // 判据6: 深度拦截 —— 负 Y 深板岩层不存在天然露天, 扫描在这里的"露天"结论不可信
        // (20260918-092740: 超大洞穴 y=-44, 客户端头顶柱视图整体缺失, 五列 buried=0 稳定 40s+)
        if (pos.getY() < DEEP_CRUST_Y) return true;

        // 判据7: 高度图埋深交叉验证 —— 距"最高非空气方块"超过 32 格时不许判地表。
        // 依据: WORLD_SURFACE 高度图是服务端随区块下发的, 深处一直稳定 (三次事故里 wsTop 均给真值),
        // 而客户端方块数据在深处会整体缺失/错装空气 (092740: y=-44 五列 buried=0; 094844: y=0..9
        // 仍 buried=0/1 而 wsTop=63)。距地形顶这么深的位置不可能是"露天活动层"。
        // 32 的依据: 任意树冠(含丛林最高 ~30)以内; 洞穴顶板普遍 >32。
        // 已知代价: 通天峡谷/竖井底部 (深 >32) 会被判地下 → GoToSurface 爬出 = 等价"走出峡谷"。
        World w = mod.getWorld();
        int wsTop = w.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
        if (wsTop > w.getBottomY() + 1 // 哨兵 (未加载/空高度图) → 跳过本判据, 交后续判据兜底
                && wsTop - (pos.getY() + 1) > SCAN_TRUST_MAX_HEIGHTMAP_BURIAL) {
            return true;
        }

        if (buried <= NEAR_SURFACE_MAX) return false;

        return true;
    }

    /**
     * 判据8: 地表签名方块 —— 只在露天表层天然生成的方块。
     *
     * <p>地形块 (脚下): 草方块/灰化土/菌丝体/沙/红沙/雪块/陶瓦。天然只在露天表层生成
     * (沙/红沙=沙漠/滩头/恶地, 雪块=雪原表面, 陶瓦=恶地表层)。
     * 植被 (脚/头高度, 半径 ≤2): 草/高草/蕨/大蕨、花(整族)、仙人掌、枯灌木、甘蔗 ——
     * 草和花需要天光才能存活, 仙人掌/枯灌木只在沙漠/恶地表层。
     *
     * <p>刻意<b>不纳入</b>: 树叶 (繁茂洞穴的杜鹃树也有, 且"跳过不算遮挡"已等价利用)、
     * 原木 (废弃矿井橡木柱干扰)、粘土 (湖底/河底水下 + 繁茂洞穴, 两处都不是露天活动层)、
     * 苔藓/杜鹃 (繁茂洞穴)、沙砾 (水下/地下都生成)、云层 (纯渲染, 无方块数据可查)。
     *
     * <p>液体护栏: 脚/头浸在水里时不认签名 (湖底/河底的沙和粘土不是露天活动层)。
     * 缓存 {@link #SIGNATURE_CACHE_MS}。
     */
    private static boolean isSurfaceSignature(net.minecraft.block.BlockState s) {
        net.minecraft.block.Block b = s.getBlock();
        return b == net.minecraft.block.Blocks.GRASS_BLOCK
                || b == net.minecraft.block.Blocks.PODZOL
                || b == net.minecraft.block.Blocks.MYCELIUM
                || b == net.minecraft.block.Blocks.SAND
                || b == net.minecraft.block.Blocks.RED_SAND
                || b == net.minecraft.block.Blocks.SNOW_BLOCK
                || b == net.minecraft.block.Blocks.TERRACOTTA
                // 植被 (只在露天天然生成)
                || b == net.minecraft.block.Blocks.GRASS
                || b == net.minecraft.block.Blocks.TALL_GRASS
                || b == net.minecraft.block.Blocks.FERN
                || b == net.minecraft.block.Blocks.LARGE_FERN
                || b == net.minecraft.block.Blocks.CACTUS
                || b == net.minecraft.block.Blocks.DEAD_BUSH
                || b == net.minecraft.block.Blocks.SUGAR_CANE
                || s.isIn(BlockTags.FLOWERS);
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
     * 判据8 主体 (两级): ①近扫 —— 脚下地形块 + 脚/头高度半径 ≤2 环内植被;
     * ②远扫 —— 自身高度 ±3、水平半径 ≤12 内任意露天签名块 (岩石山地/恶地坡面正例)。
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
        // ①近扫: 脚下地形块
        if (isSurfaceSignature(w.getBlockState(pos.down()))) {
            _sigYes = true;
            return true;
        }
        // ①近扫: 脚/头高度, 半径 ≤2 (25 格 × 2 高度 ≈ 50 次块读)
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (isSurfaceSignature(w.getBlockState(m.toImmutable()))) {
                        _sigYes = true;
                        return true;
                    }
                }
            }
        }
        // ②远扫: 自身高度 ±3, 水平半径 ≤12 (最坏 ~4400 次块读, 500ms 缓存 + 命中早退)
        for (int dy = -SIGNATURE_FAR_Y_TOLERANCE; dy <= SIGNATURE_FAR_Y_TOLERANCE; dy++) {
            for (int dx = -SIGNATURE_FAR_RADIUS; dx <= SIGNATURE_FAR_RADIUS; dx++) {
                for (int dz = -SIGNATURE_FAR_RADIUS; dz <= SIGNATURE_FAR_RADIUS; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (isSurfaceSignature(w.getBlockState(m.toImmutable()))) {
                        _sigYes = true;
                        return true;
                    }
                }
            }
        }
        return false;
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
        boolean raw = isBelowSurface(mod);
        long now = System.currentTimeMillis();
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
            hb = wsTop - (p.getY() + 1); // 判据7 的"高度图埋深"
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
