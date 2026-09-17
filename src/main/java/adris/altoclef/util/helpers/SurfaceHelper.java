package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.Dimension;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * (2026-09-17 判据统一 → 2026-09-17 二次重构·多判据) 「bot 是否在地下」的全项目唯一判据。
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
 * <h3>口径 = 判据流水线 (任一判据成立即"地表"; 全部不成立才"地下")</h3>
 * <pre>
 *   判据0 维度退化    非主世界 → 退回 y &lt; 58 (下界有基岩顶、末地有穹顶, 海拔法无意义)
 *   判据1 地形埋深    5 点采样(自身列 + 4 水平邻柱同高)的【头顶地形格数中位数】≤ 4 → 地表
 *   判据2 植被不算地板 判据1 的"地形"用排除树叶的高度图 → 树冠/藤蔓/水面不构成"埋"
 *   判据3 水平近邻露天 埋深在 5..8 的临界带时, 半径 3 的水平环内有列埋深 ≤ 4 → 地表
 *        (语义: "头顶被岩檐遮住, 但一步之外就是露天" → 仍在活动层)
 *   判据4 时间迟滞    结论翻面须距上次翻面 ≥ 5s (抹平地形过渡/边界抖动)
 * </pre>
 * 判据1 的取值语义: {@code topNoLeaves(x,z) - (玩家脚 y + 1)} = "<b>该列头顶之上压着几格地层</b>"。
 * ≤0 表示头顶之上无地层(露天或只有植被) → 一定在地表; 数值越大越深。
 * 取 5 点的<b>中位数</b>而不是最小/最大, 是因为两种极端都会翻车:
 * <ul>
 *   <li>取最小 → 深竖井(自己挖的、上方直通露天)会被判"地表", 上层会以为在地表而不去爬出来
 *       (20260917-120830 场次: bot 在 1 格宽竖井里拿洞外 y=79 的原木当目标, 35 分钟上不去);</li>
 *   <li>取最大 → 站在地表但旁边有高墙/树干, 一格之差就判"深地下"。</li>
 * </ul>
 * 中位数 = "多数采样点看到的世界": 竖井里 4 个邻柱都深 → 判地下; 树冠里 5 个点都浅 → 判地表。
 *
 * <h3>为什么必须是"排除树叶"的高度图</h3>
 * {@code MOTION_BLOCKING_NO_LEAVES} = 阻挡移动且非树叶的方块(含流体)的顶 + 1。
 * 树叶在 vanilla 里 <b>没有碰撞体积</b>(材料 LEAVES 的 blocksMovement=false), 它挡光、挡视线,
 * 但从来不挡"我脚下有几格地层"。用 {@code WORLD_SURFACE}(什么都算) 或
 * {@code world.isSkyVisible}(走 WORLD_SURFACE 高度图) 都会把"树冠"读成"天花板"—— 这正是
 * 20260917-143636 事故的直接成因, 见下。
 *
 * <h3>翻车史（这些都是实测事故, 别再回去）</h3>
 * 纯 y 阈值:
 * <ul>
 *   <li>露天山谷 y=57 在地表却被判"深地下" → bot 反复爬向山顶。</li>
 *   <li>20260917-120830: {@code MineAndCollectTask} 的"先爬回海平面再找树"锚点用
 *       {@code y < 63-2}, bot 恰在 y=62 → 锚点不注入 → 在 1 格宽阶梯地道里拿洞外
 *       y=79 的原木当目标, 35 分钟上不去。</li>
 * </ul>
 * 单点天空采样: {@code isSkyVisible(pos.up())} 在水面漂浮 bobbing 时交替水/空气, 高度图把水算遮挡
 * → 每 tick 抖一次 (20260916-1146 场次 rejectedFlaps 90+/5s)。
 *
 * <b>5 点 isSkyVisible 多数投票（本类上一版, 已废弃）</b>: 把"树叶遮挡"当"地下"——
 * <ul>
 *   <li>20260917-143636 (用户现场确认"bot 站在橡树树冠里自上而下砍树干"): 14:38:02
 *       {@code [Surface] mode -> below(under cover) (y=66, covered=4/5)}, 4 个水平邻柱
 *       全是 {@code minecraft:oak_leaves}, 判成地下 → 囤木门控(地下必须先把原木攒到 4 以上)
 *       当 tick 把整条床链顶成 {@code GoToSurfaceTask} —— 3 羊毛已到手、只差 3 块木板,
 *       就此整夜再没做成床。树冠 = 地表, 这里判反了。</li>
 *   <li>同类: 自挖 1 格宽竖井/树坑(水平邻柱全是同高的土石壁)也被判地下, 尽管头顶直通露天。</li>
 * </ul>
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
 *   <li>{@code GoToSurfaceTask.inShaft}: 判"站在自挖竖井底部"。与判据1 互补——竖井里"多数点看到的地层"
 *       已经把竖井判对, 但井底若是露天(5 点全浅)则靠它补"四面实心出不去"。</li>
 * </ul>
 *
 * <h3>成本</h3>
 * 判据1 = 5 次高度图查询 (O(1) 数组读); 判据3 仅在中位数 5..8 时执行 16 次查询, 且带 500ms 缓存。
 * 迟滞与缓存的 static 状态已接入 {@code WorldStateGuard.registerAll()} 的
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
     * 4 与 5 之间的边界不敏感——判据3 还会在 5..8 的临界带再做一次水平探测。
     */
    private static final int NEAR_SURFACE_MAX = 4;

    /** 判据3 只在"埋深 ≤ 本值"的临界带启用 (再深就没有"一步之外"可言了)。 */
    private static final int RING_PROBE_MAX = 8;

    /** 判据3 的水平环半径 (距离 1 已含在判据1 的邻柱里, 故从 2 开始扫到本值)。 */
    private static final int RING_PROBE_RADIUS = 3;

    /** 迟滞: 结论翻面后至少驻留这么久才允许再翻。 */
    private static final long MIN_DWELL_MS = 5000;

    /** 判据3 的缓存时长 (16 次查询没必要每 tick 做; 5s 迟滞下 0.5s 陈旧看不出来)。 */
    private static final long RING_CACHE_MS = 500;

    // ===== 迟滞状态 (static: 结论必须跨任务实例存活, 且全局唯一) =====
    // 放 static 的理由与语义: "bot 现在在不在表面"是【全局事实】, 所有调用方
    // 理应看到同一个答案。若各调用方各持一份迟滞, 就会出现 A 说在地表、B 说
    // 在地下 —— 那正是统一前 5 种口径互相打架的根源。
    private static boolean _below = false;
    private static long _setAt = 0;
    private static boolean _init = false;
    private static long _flapRejected = 0;

    // 判据3 的缓存 (同样 static: 结论是全局事实, 没必要每个调用方各算一次)
    private static boolean _ringOpen = false;
    private static long _ringAt = 0;

    /**
     * 换存档时复位 (接入 {@code WorldStateGuard})。迟滞状态属于"上个存档的结论",
     * 留着会让新存档的首个判定被旧结论拖 5s。
     */
    public static void onWorldBoundary() {
        _below = false;
        _setAt = 0;
        _init = false;
        _flapRejected = 0;
        _ringOpen = false;
        _ringAt = 0;
    }

    // ==================================================================
    // 判据取值 (公开: 供诊断日志、单元式验证、以及需要自定义阈值的调用方)
    // ==================================================================

    /**
     * 某列"头顶之上的地层格数" = {@code topNoLeaves(x,z) - (y+1)}。
     *
     * <p>≤0 = 该列头顶之上没有地层(露天, 或只被树叶/藤蔓/水遮住) → 一定在露天活动层。
     * 数值 = 该列从头顶往上要挖穿几格地层才能见天。
     *
     * <p>用 {@code MOTION_BLOCKING_NO_LEAVES} 而非 {@code WORLD_SURFACE}/
     * {@code isSkyVisible}: 树叶没有碰撞体积、不该被当成"压在我头顶的地层"
     * (这才是"站在树冠里不算地下"的实现方式)。
     */
    public static int overheadTerrain(AltoClef mod, int x, int y, int z) {
        if (mod.getWorld() == null) return 0;
        return mod.getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - (y + 1);
    }

    /**
     * 判据1 的 5 点采样: 下标 0 = 玩家自身列, 1..4 = 东/西/南/北邻柱(<b>同高</b>采样)。
     *
     * <p>邻柱取"同高"而不是"邻柱顶部": 在 1 格宽阶梯地道/竖井里邻柱整列都是实心, 取顶部会
     * 得到远处山体的高度而失去"我这一格被压了多少地层"的信息。
     */
    public static int[] overheadSamples(AltoClef mod) {
        int[] out = new int[5];
        if (mod.getPlayer() == null) return out;
        BlockPos pos = mod.getPlayer().getBlockPos();
        int[][] dirs = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < 5; i++) {
            out[i] = overheadTerrain(mod, pos.getX() + dirs[i][0], pos.getY(), pos.getZ() + dirs[i][1]);
        }
        return out;
    }

    /**
     * 判据1 的判定值: 5 点头顶地层格数的【中位数】。见类注释"为什么取中位数"。
     */
    public static int buriedDepth(AltoClef mod) {
        int[] s = overheadSamples(mod);
        java.util.Arrays.sort(s);
        return s[2];
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
     * 诊断用: 玩家所在列"顶部植被层厚度" = WORLD_SURFACE 顶 − 排除树叶的顶。
     * >0 说明头上那层是树叶/草/藤蔓之类"挡视线不挡路"的东西 —— 判据2 生效的地方。
     */
    public static int vegetationCapThickness(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return 0;
        BlockPos pos = mod.getPlayer().getBlockPos();
        int worldTop = mod.getWorld().getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
        int terrainTop = mod.getWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        return Math.max(0, worldTop - terrainTop);
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

        // 判据0: 非主世界 → 海拔退化 (下界基岩顶/末地穹顶会让高度图恒判"地下")
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            return pos.getY() < NON_OVERWORLD_DEEP_Y;
        }

        // 判据1(+判据2 内建): 5 点头顶地层格数中位数
        int buried = buriedDepth(mod);
        if (buried <= NEAR_SURFACE_MAX) return false;

        // 判据3: 临界带 (5..8) 再做一次水平环探测 —— "头顶被压住, 但一步之外就是露天"
        if (buried <= RING_PROBE_MAX && nearbyOpenColumn(mod, pos)) return false;

        return true;
    }

    /**
     * 判据3: 以玩家为心、切比雪夫半径 2..{@link #RING_PROBE_RADIUS} 的水平环内, 是否存在
     * 一列"头顶之上地层 ≤ {@link #NEAR_SURFACE_MAX} 格"(即挪过去就地是露天活动层)。
     *
     * <p>语义: 站在岩檐/薄顶棚下面时判据1 会给出中等埋深, 但人其实一步就能走到露天 ——
     * 这种位置干"回地表"的活代价是零, 不该被当成必须挖上去的深地下。
     * 距离 1 不重复扫 (已含在判据1 的邻柱里); 未加载区块的列一律跳过 ——
     * 未加载列的高度图会返回世界底部, 被误读成"露天"。结果缓存 {@link #RING_CACHE_MS}。
     */
    private static boolean nearbyOpenColumn(AltoClef mod, BlockPos pos) {
        long now = System.currentTimeMillis();
        if (_ringAt != 0 && now - _ringAt < RING_CACHE_MS) return _ringOpen;
        _ringAt = now;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -RING_PROBE_RADIUS; dx <= RING_PROBE_RADIUS; dx++) {
            for (int dz = -RING_PROBE_RADIUS; dz <= RING_PROBE_RADIUS; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) continue; // 距离 1 已含在判据1
                m.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                if (!mod.getChunkTracker().isChunkLoaded(m)) continue;
                if (overheadTerrain(mod, m.getX(), pos.getY(), m.getZ()) <= NEAR_SURFACE_MAX) {
                    _ringOpen = true;
                    return true;
                }
            }
        }
        _ringOpen = false;
        return false;
    }

    /**
     * 判"在地下"的迟滞版 (5s 最短驻留): 全项目应当优先用这个。
     *
     * <p>迟滞的存在理由: 判据1 的空间聚合已经抹平"单列"抖动, 但整片地形过渡
     * (走出洞口/跳下坑/走进树冠) 仍会让中位数在当前 tick 翻面; 5s 驻留把这类过渡
     * 收敛成一次, 避免上层门控(囤木/收食物/回地表)在边界上来回抢链。
     *
     * <p>翻面被拒时累计 {@code _flapRejected}, 翻面成功时打一行日志并清零 ——
     * 这个计数是判断"阈值定得对不对"的唯一观测量 (20260916 场次靠 rejectedFlaps
     * 90+/5s 定位到单点抖动的)。
     */
    public static boolean isBelowSurfaceDebounced(AltoClef mod) {
        boolean raw = isBelowSurface(mod);
        long now = System.currentTimeMillis();
        if (!_init) {
            _init = true;
            _below = raw;
            _setAt = now;
            return raw;
        }
        if (raw != _below) {
            if (now - _setAt < MIN_DWELL_MS) {
                _flapRejected++;
                return _below;
            }
            logTransition(mod, raw);
            _flapRejected = 0;
            _below = raw;
            _setAt = now;
        }
        return _below;
    }

    /**
     * 翻面日志。字段固定为可 grep 的形状 {@code [Surface] mode -> ...}:
     * <ul>
     *   <li>{@code y}      玩家脚所在 y;</li>
     *   <li>{@code buried} 判据1 的中位数 = 头顶压着几格地层 (拍板判据);</li>
     *   <li>{@code oh=[..]} 5 点原始采样, 下标 0 = 自身列, 其余为 东/西/南/北;</li>
     *   <li>{@code vegCap} 自身列顶部植被层厚度 (>0 = 判据2 正在起作用, 头顶那层是树叶/草);</li>
     *   <li>{@code covered} 旧口径 (isSkyVisible) 票数 —— 仅用于对照"判据换了结论差在哪"。</li>
     * </ul>
     */
    private static void logTransition(AltoClef mod, boolean raw) {
        int[] oh = overheadSamples(mod);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < oh.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(oh[i]);
        }
        int y = mod.getPlayer() != null ? mod.getPlayer().getBlockPos().getY() : -1;
        Debug.logMessage("[Surface] mode -> "
                + (raw ? "below(terrain)" : "surface(open)")
                + " (y=" + y
                + ", buried=" + buriedDepth(mod)
                + ", oh=[" + sb + "]"
                + ", vegCap=" + vegetationCapThickness(mod)
                + ", covered=" + skyCoveredCount(mod) + "/5"
                + ", dim=" + WorldHelper.getCurrentDimension()
                + ", rejectedFlaps=" + _flapRejected + ")");
    }
}
