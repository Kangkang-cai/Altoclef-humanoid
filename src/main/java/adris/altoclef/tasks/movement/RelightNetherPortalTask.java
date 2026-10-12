package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * (2026-10-11 熄灭门到场重燃) 到已知下界门址导航并重燃。
 *
 * <p>背景（用户现场：bot 在下界找人，回程没走去来时的门）：旧链把"紫块不在"
 * 等同于"门废了" —— {@code MiscBlockTracker.getLastUsedNetherPortal} 与
 * {@code WorldMemory.getPortalLocations} 都会在 chunk 加载后把紫块缺失的门址
 * 从记忆（内存+落盘）里清除，导致跨维度回家永远不走来时的门，直接掉进重造链。
 * 实际上熄灭的门（恶魂火球/玩家打火熄灭）通常黑曜石框完好，到场打火石重燃
 * 比重造便宜得多。本任务由 {@link DefaultGoToDimensionTask} 的重燃分支调用，
 * 门址来源 = lastUsed（含熄灭）∪ 本会话确认熄灭的门（WorldMemory lost）。
 *
 * <p>行为：
 * <ol>
 *   <li>门址附近（站点 ±2xz / -2..+4y）扫到紫块 = 已点亮 → 成功出口（点亮后
 *       注册回 BlockTracker，外层 netherPortalIsClose 自然接管进门）；</li>
 *   <li>未到场先 {@link GetToBlockTask} 导航（到达判定 = 站点 XZ 8 格，与设点
 *       闸门同口径）；</li>
 *   <li>到场后自站点向下穿空气/紫块最多 {@value #FLOOR_SCAN_DEPTH} 格找黑曜石
 *       门框地板；找不到 = 框也没了 → 放弃；</li>
 *   <li>材料闸：有打火石直接点；有铁锭+燧石由 InteractWithBlockTask 内部
 *       现场合（2x2，无需工作台）；【下界】两者都缺 → 直接放弃 —— 下界没有
 *       铁矿/砾石，从头收集必然触发 CollectIronIngotTask → DefaultGoToDimension
 *       Task(OVERWORLD) 的嵌套递归；【主世界】允许从头收集（无递归风险）；</li>
 *   <li>点火复用 ConstructNetherPortalObsidianTask:160 的同一形状：
 *       InteractWithBlockTask(打火石, UP, 框内地板, walkInto)。</li>
 * </ol>
 *
 * <p>出口（调用侧消费，本仓"子任务 isFinished 不被框架消费"铁律）：
 * {@code isFinished()} = 点亮成功 或 放弃；放弃后由调用方把门址拉黑
 * （跨实例 static 集合）并回落旧链（找别的门/重造）。整体 3 分钟超时
 * （用户口径"3min 超时放弃"，从任务启动计时，覆盖导航+取物+点火全程）。
 */
public class RelightNetherPortalTask extends Task {

    /** 用户口径：重燃行为整体 3 分钟超时放弃。 */
    private static final long RELIGHT_TIMEOUT_MS = 180_000;
    /** 到场判定：站点 XZ 8 格内（与 DefaultGoToDimensionTask 设点闸门同口径）。 */
    private static final double ARRIVAL_RANGE_XZ = 8.0;
    /** 自站点向下找黑曜石框地板的最大深度。 */
    private static final int FLOOR_SCAN_DEPTH = 6;

    private final BlockPos _site;
    private long _startMs = 0L;
    private BlockPos _floorPos = null;
    private InteractWithBlockTask _lightTask = null;
    private boolean _lit = false;
    private boolean _gaveUp = false;

    public RelightNetherPortalTask(BlockPos site) {
        _site = site.toImmutable();
    }

    public boolean isForSite(BlockPos site) {
        return _site.equals(site);
    }

    /** 放弃（超时/框没了/缺料）——调用方据此把门址拉黑。 */
    public boolean gaveUp() {
        return _gaveUp;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _startMs = System.currentTimeMillis();
        _floorPos = null;
        _lightTask = null;
        _lit = false;
        _gaveUp = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // ① 已点亮 → 成功出口（顺手注册回 BlockTracker，外层进门链路立即可见）
        BlockPos lit = findLitPortalNearSite(mod);
        if (lit != null) {
            if (!_lit) {
                _lit = true;
                mod.getBlockTracker().addBlock(Blocks.NETHER_PORTAL, lit);
                Debug.logMessage("(PortalRelight) portal at " + _site.toShortString() + " is lit"
                        + (_lightTask != null ? " — relight succeeded" : " (already lit on arrival)"));
                Debug.logToFile("altoclef_diag.log", "[PortalRelight]\tLIT @" + _site.toShortString());
            }
            return null;
        }
        // ② 3 分钟总超时（导航+取物+点火全程，用户口径）
        if (System.currentTimeMillis() - _startMs > RELIGHT_TIMEOUT_MS) {
            giveUp(mod, "TIMEOUT " + (RELIGHT_TIMEOUT_MS / 1000) + "s");
            return null;
        }
        // ③ 未到场先导航
        if (!WorldHelper.inRangeXZ(mod.getPlayer(), WorldHelper.toVec3d(_site), ARRIVAL_RANGE_XZ)) {
            setDebugState("Going to remembered portal site (may be extinguished)");
            return new GetToBlockTask(_site);
        }
        // ④ 找门框内底（点火目标 = 黑曜石地板的 UP 面）
        if (!ensureFloorPos(mod)) {
            return null; // giveUp 已在内部落日志
        }
        // ⑤ 材料闸（防下界收集递归，见类注释 4）
        if (!mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)) {
            boolean canCraftInPlace = mod.getItemStorage().hasItem(Items.IRON_INGOT)
                    && mod.getItemStorage().hasItem(Items.FLINT);
            if (!canCraftInPlace && WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
                giveUp(mod, "no flint&steel and no iron+flint to craft with in "
                        + WorldHelper.getCurrentDimension());
                return null;
            }
            // 缺工具但有料（或主世界可采）→ InteractWithBlockTask 内部会先取物品
        }
        // ⑥ 点火（与 ConstructNetherPortalObsidianTask 同一调用形状）
        if (_lightTask == null) {
            _lightTask = new InteractWithBlockTask(
                    new ItemTarget(Items.FLINT_AND_STEEL, 1), Direction.UP, _floorPos, true);
            Debug.logMessage("(PortalRelight) lighting extinguished portal, floor="
                    + _floorPos.toShortString());
            Debug.logToFile("altoclef_diag.log", "[PortalRelight]\tLIGHT start site="
                    + _site.toShortString() + " floor=" + _floorPos.toShortString());
        }
        setDebugState("Relighting nether portal at " + _floorPos.toShortString());
        return _lightTask;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _lightTask = null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _lit || _gaveUp;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof RelightNetherPortalTask task && task._site.equals(_site);
    }

    @Override
    protected String toDebugString() {
        return "RelightNetherPortal(" + _site.toShortString() + ")";
    }

    /**
     * 站点附近（±2xz / -2..+4y）实扫紫块。下界门内腔最多 3 宽 4 高（1.18.2
     * 无大传送门），扫描盒按此定；chunk 未加载的格跳过（远端导航途中不误判）。
     */
    private BlockPos findLitPortalNearSite(AltoClef mod) {
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : WorldHelper.scanRegion(mod, _site.add(-2, -2, -2), _site.add(2, 4, 2))) {
            if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
            if (mod.getWorld().getBlockState(pos).getBlock() != Blocks.NETHER_PORTAL) continue;
            double distSq = pos.getSquaredDistance(_site);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = pos.toImmutable();
            }
        }
        return nearest;
    }

    /**
     * 自站点向下穿空气/紫块找黑曜石框地板（点火目标）。站点若是框外被填实等
     * 异常形态，或地板不是黑曜石（框被炸/被埋）→ 放弃。结果粘滞（_floorPos），
     * 每 tick 校验地板仍是黑曜石，被破坏则重找。
     */
    private boolean ensureFloorPos(AltoClef mod) {
        if (_floorPos != null
                && mod.getChunkTracker().isChunkLoaded(_floorPos)
                && mod.getWorld().getBlockState(_floorPos).getBlock() == Blocks.OBSIDIAN) {
            return true;
        }
        _floorPos = null;
        BlockPos cur = _site;
        for (int i = 0; i <= FLOOR_SCAN_DEPTH && cur.getY() > 0; i++) {
            if (!mod.getChunkTracker().isChunkLoaded(cur)) {
                giveUp(mod, "chunk not loaded while scanning floor at " + cur.toShortString());
                return false;
            }
            Block block = mod.getWorld().getBlockState(cur).getBlock();
            if (block == Blocks.OBSIDIAN) {
                _floorPos = cur.toImmutable();
                return true;
            }
            // 门内腔只允许空气/紫块向下穿；碰到实心非框块 = 框已变形
            if (block != Blocks.AIR && block != Blocks.NETHER_PORTAL) {
                giveUp(mod, "frame floor missing (block=" + block + " at " + cur.toShortString() + ")");
                return false;
            }
            cur = cur.down();
        }
        giveUp(mod, "no obsidian floor within " + FLOOR_SCAN_DEPTH + " below site");
        return false;
    }

    private void giveUp(AltoClef mod, String reason) {
        if (_gaveUp) return;
        _gaveUp = true;
        Debug.logMessage("(PortalRelight) giving up at " + _site.toShortString() + ": " + reason);
        Debug.logToFile("altoclef_diag.log",
                "[PortalRelight]\tGIVE_UP @" + _site.toShortString() + "\treason=" + reason);
    }
}
