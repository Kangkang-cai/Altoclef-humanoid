package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.Dimension;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-09-17 判据统一) 「bot 是否在地表/地下」的全项目唯一判据。
 *
 * <h3>为什么要有这个类</h3>
 * 统一前全项目有 7 种互不相同的口径，同一个问题给出 7 个不同答案：
 * <pre>
 *   A WorldHelper.isDeepUnderground            y &lt; 58            纯 y 硬阈值
 *   B BeatMinecraft2Task.isUndergroundDebounced 包装 A + 2s      纯 y + 双稳态
 *   D BeatMinecraft2Task.isBelowSeaLevelDebounced y &lt; 55        纯 y + 1s(水下熬夜专用)
 *   E MineAndCollectTask (内联)                 y &lt; 63-2 = 61     纯 y 硬编码
 *   F MobDefenseChain.isUnderground            头顶 1..12 格扫实心 单列头顶扫描
 *   G GoToSurfaceTask.isDeepForEscape          y&lt;58 或 !isSkyVisible(pos.up())  单点天空
 *   I CollectBucketLiquidTask.isBelowSurface   5 点多数投票 + 5s 迟滞  ← 本类搬过来的
 * </pre>
 *
 * <h3>统一结果（2026-09-17，本类为唯一实现）</h3>
 * <table>
 *   <tr><td>A</td><td>→ {@link #isBelowSurfaceDebounced}（{@code WorldHelper.isDeepUnderground} 仅剩转发壳，方法名保留以免改 5+ 调用点）</td></tr>
 *   <tr><td>B</td><td>→ 转发 A，删掉那层多余的 2s 双稳态（否则真实翻面要 5s+2s）</td></tr>
 *   <tr><td>E</td><td>→ {@link #isBelowSurfaceDebounced}（原 y&lt;61 漏判 y=62，见下）</td></tr>
 *   <tr><td>F</td><td>→ {@link #isBelowSurface}（<b>无迟滞</b>：本方法只在每 tick 重算的状态机里用一次，要"当下这一刻"；树冠/屋檐下不再误判地下）</td></tr>
 *   <tr><td>G</td><td>→ {@link #isBelowSurfaceDebounced}</td></tr>
 *   <tr><td>I</td><td>→ {@link #isBelowSurfaceDebounced}（本类就是它的搬移，日志标签 [LiquidSearch] 并入 [Surface]）</td></tr>
 *   <tr><td>D</td><td><b>保留未动</b>：判的是"潜到水下熬夜继续挖"的【深度】，语义不同，见下</td></tr>
 * </table>
 *
 * <h3>翻车史（这两类都不准，别再回去）</h3>
 * 纯 y 阈值：
 * <ul>
 *   <li>露天山谷 y=57 在地表却被判"深地下" → bot 反复爬向山顶。</li>
 *   <li>20260917-120830 场次：{@code MineAndCollectTask} 的"先爬回海平面再找树"
 *       锚点用 {@code y < 63-2}，bot 恰在 y=62 → 锚点不注入 → bot 在 1 格宽
 *       阶梯地道里拿洞外 y=79 的原木当目标，35 分钟上不去。</li>
 * </ul>
 * 单点天空采样：{@code isSkyVisible(pos.up())} 在水面漂浮 bobbing 时
 * 交替水/空气，高度图把水算遮挡 → 每 tick 抖一次
 * (20260916-1146 场次 rejectedFlaps 90+/5s)。
 *
 * <h3>口径</h3>
 * <b>5 点海拔采样 + 空间多数投票 + 时间迟滞</b>：取玩家【脚上方】与【四个水平
 * 邻柱同高】共 5 个点，{@code ≥3 点未见天} 判"地下"。单列抖动被票数抹平，
 * 真洞穴/树冠下 5 点全遮 → 稳定判地下。再加 5s 最短驻留做兜底。
 *
 * <h3>非主世界维度</h3>
 * 下界有基岩天花板、末地有主岛穹顶，{@code isSkyVisible} 恒 false → 采样会
 * 永久判"地下"，与"在主世界地下"完全不是一回事。故非主世界退化到 y &lt; 58
 * 硬阈值（= 统一前 {@code isDeepUnderground} 的行为），保证下界/末地分支零回归。
 *
 * <h3>不适用本判据的例外（语义不同，别硬套）</h3>
 * <ul>
 *   <li>{@code BeatMinecraft2Task.isBelowSeaLevelDebounced}（y&lt;55）：判的是
 *       "潜到水下熬夜继续挖"的【深度】，不是"见不见天"。</li>
 *   <li>{@code WorldHelper.getPitEscapeY}（十字 4 向 × 4/8/12/16 扫对向高墙）：
 *       判的是"被困峡谷低洼"，地形法，独立语义。</li>
 *   <li>{@code GoToSurfaceTask.inShaft}：判"站在自挖竖井底部"，与海拔判据互补
 *       （井底 5 点全露天，采样会说"在地表"，但四面实心出不去）。</li>
 * </ul>
 *
 * <h3>换存档</h3>
 * 迟滞状态是 static，已接入 {@code WorldStateGuard.registerAll()} 的
 * {@code SurfaceHelper::onWorldBoundary} 复位。
 */
public final class SurfaceHelper {

    private SurfaceHelper() {
    }

    /** 非主世界退化的 y 阈值，取值与统一前的 {@code WorldHelper.isDeepUnderground} 一致。 */
    private static final int NON_OVERWORLD_DEEP_Y = 58;

    /** 多数投票线: 5 点里 ≥3 点未见天才算地下。 */
    private static final int COVERED_MAJORITY = 3;

    /** 迟滞: 结论翻面后至少驻留这么久才允许再翻。 */
    private static final long MIN_DWELL_MS = 5000;

    // ===== 迟滞状态 (static: 结论必须跨任务实例存活, 且全局唯一) =====
    // 放 static 的理由与语义: "bot 现在在不在表面"是【全局事实】, 所有调用方
    // 理应看到同一个答案。若各调用方各持一份迟滞, 就会出现 A 说在地表、B 说
    // 在地下 —— 那正是统一前 5 种口径互相打架的根源。
    private static boolean _below = false;
    private static long _setAt = 0;
    private static boolean _init = false;
    private static long _flapRejected = 0;

    /**
     * 换存档时复位 (接入 {@code WorldStateGuard})。迟滞状态属于"上个存档的结论",
     * 留着会让新存档的首个判定被旧结论拖 5s。
     */
    public static void onWorldBoundary() {
        _below = false;
        _setAt = 0;
        _init = false;
        _flapRejected = 0;
    }

    /**
     * 5 点海拔采样原始值: 返回"未见天"的点数 (0..5)。无状态、无迟滞，供诊断与
     * 调用方自定义阈值使用。
     *
     * 采样点 = 玩家脚上方 + 四个水平邻柱【同高】。用邻柱同高而不是邻柱顶部，
     * 是因为在 1 格宽的阶梯地道/竖井里邻柱整列都是实心，取顶部会得到远处山体
     * 的高度而失去"我这一格通不通天"的信息。
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
     * 判"在地下"原始值 (无迟滞)：5 点里 ≥3 点未见天。
     *
     * 需要"一次判定立刻生效"的场合用它；需要稳定结论的场合用
     * {@link #isBelowSurfaceDebounced}。
     *
     * 非主世界退化到 y 阈值 (见类注释)。
     */
    public static boolean isBelowSurface(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            return mod.getPlayer().getBlockPos().getY() < NON_OVERWORLD_DEEP_Y;
        }
        return skyCoveredCount(mod) >= COVERED_MAJORITY;
    }

    /**
     * 判"在地下"的迟滞版 (5s 最短驻留)：全项目应当优先用这个。
     *
     * 迟滞的存在理由见类注释 —— 空间投票能抹平【单列】抖动，但整片地形过渡
     * (走出洞口、走进树冠) 仍会让票数缓慢爬过多数线；5s 驻留把这类过渡期的
     * 反复翻面收敛成一次。
     *
     * 翻面被拒时累计 {@code _flapRejected}，翻面成功时打一行日志并清零 ——
     * 这个计数是判断"阈值定得对不对"的唯一观测量 (20260916 场次就是靠
     * rejectedFlaps 90+/5s 定位到单点抖动的)。
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
            int covered = skyCoveredCount(mod);
            Debug.logMessage("[Surface] mode -> " + (raw ? "below(under cover)" : "surface(open sky)")
                    + " (y=" + (mod.getPlayer() != null ? mod.getPlayer().getBlockPos().getY() : -1)
                    + ", covered=" + covered + "/5"
                    + ", dim=" + WorldHelper.getCurrentDimension()
                    + ", rejectedFlaps=" + _flapRejected + ")");
            _flapRejected = 0;
            _below = raw;
            _setAt = now;
        }
        return _below;
    }
}
