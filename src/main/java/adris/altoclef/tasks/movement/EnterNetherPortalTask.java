package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class EnterNetherPortalTask extends Task {

    private final Task _getPortalTask;
    private final Dimension _targetDimension;

    private final TimerGame _portalTimeout = new TimerGame(10);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(2);

    private final Predicate<BlockPos> _goodPortal;

    private boolean _leftPortal;

    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        if (targetDimension == Dimension.END)
            throw new IllegalArgumentException("Can't build a nether portal to the end.");
        _getPortalTask = getPortalTask;
        _targetDimension = targetDimension;
        _goodPortal = goodPortal;
    }

    public EnterNetherPortalTask(Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        this(null, targetDimension, goodPortal);
    }
    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension) {
        this(getPortalTask, targetDimension, blockPos -> true);
    }
    public EnterNetherPortalTask(Dimension targetDimension) {
        this(null, targetDimension);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
        _leftPortal = false;
        _portalTimeout.reset();

        _wanderTask.resetWander();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Exiting portal for a bit.");
            _portalTimeout.reset();
            _leftPortal = true;
            return _wanderTask;
        }

        if (mod.getWorld().getBlockState(mod.getPlayer().getBlockPos()).getBlock() == Blocks.NETHER_PORTAL) {

            if (_portalTimeout.elapsed() && !_leftPortal) {
                adris.altoclef.util.helpers.TimeoutLog.hit("EnterNetherPortalTask.portalTimeout", "10s inside portal without dimension change, wandering");
                return _wanderTask;
            }
            setDebugState("Waiting inside portal");
            return null;
        } else {
            _portalTimeout.reset();
        }

        Predicate<BlockPos> standablePortal = blockPos -> {
            // REQUIRE that there be solid ground beneath us, not more portal.
            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                // Eh just assume it's good for now
                return true;
            }
            BlockPos below = blockPos.down();
            boolean canStand = WorldHelper.isSolid(mod, below) && !mod.getBlockTracker().blockIsValid(below, Blocks.NETHER_PORTAL);
            return canStand && _goodPortal.test(blockPos);
        };

        if (mod.getBlockTracker().anyFound(standablePortal, Blocks.NETHER_PORTAL)) {
            setDebugState("Going to found portal");
            // (20261113 深水门改道) 门在深水柱正下方 → 不潜水直扑: baritone 水中寻路
            // 打不穿 43 格水柱 → water-bob 原地浮动 78 分钟 (20261113-190755 实锤,
            // SwimDiveChain 的 DEEP_WATER_NO_FOOTING 是 0927/0930 刻意不接管, 此场景
            // 无任何看门狗能救)。改道: 找门附近"陆地列顶高于门"的列 → 先走到列顶 →
            // 再 GetToBlockTask(门) 让 baritone 从陆地直挖下去 (用户现场给的策略)。
            Optional<BlockPos> portalOpt = mod.getBlockTracker()
                    .getNearestTracking(standablePortal, Blocks.NETHER_PORTAL);
            if (portalOpt.isPresent()) {
                BlockPos portal = portalOpt.get();
                if (isDeepUnderwaterPortal(mod, portal)) {
                    return deepWaterDigDownApproach(mod, portal, standablePortal);
                }
            }
            return new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos, false), standablePortal, Blocks.NETHER_PORTAL);
        }
        setDebugState("Getting our portal");
        return _getPortalTask;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == _targetDimension;
    }

    // =========================================================================
    // (20261113 深水门改道) 用户现场: "传送门上面是很大一片海, 只有几十格外有
    // 陆地可以直挖下去"。直扑水柱下方的门 = water-bob 卡死, 78 分钟零位移
    // (20261113-190755, NAV-STUCK mode=water-bob dur=4685s)。
    // =========================================================================

    // 改道落点 (陆地列顶) 与其对应的门; 门换了就重算。跨 onStart 保留 (sticky)。
    private BlockPos _rerouteColumn = null;
    private BlockPos _reroutePortal = null;

    /**
     * 门深藏海平面以下 + 正上方海面是水 = 水柱下方的门。
     * (2213 修正) 不再数"从门顶向上的连续水柱"——追踪到的门块常是门的底部块,
     * 向上扫一格就撞上门顶黑曜石框 → break → water=0 → 永远漏判 (20261113-214004
     * 实锤: 门 (-3337,18,56) 上方 43 格海, bot 水漂 5.4 分钟判定仍 false)。
     * 改判据: 门够深 (y≤48) + 同列海面层 (y=62) 与浅层 (y=55) 均为水,
     * 同时覆盖开放水柱与浅埋海床 (门框/埋藏石不再影响判定)。
     */
    private boolean isDeepUnderwaterPortal(AltoClef mod, BlockPos portal) {
        if (portal.getY() > 48) return false;
        BlockState surface = mod.getWorld().getBlockState(new BlockPos(portal.getX(), 62, portal.getZ()));
        if (surface.getBlock() != Blocks.WATER) return false;
        BlockState shallow = mod.getWorld().getBlockState(new BlockPos(portal.getX(), 55, portal.getZ()));
        return shallow.getBlock() == Blocks.WATER;
    }

    /**
     * 门附近 (水平 64 格内) 找"陆地列顶高于门"的最近列: 从海平面上方向下扫,
     * 第一个非空气/非水/非树叶方块即陆地表面 (高于门深 = 直挖可达)。
     * 只在改道首次触发时算一次并缓存 (一次全量扫描 ~数千次方块读, 不能每 tick 来)。
     */
    private BlockPos findDigDownColumn(AltoClef mod, BlockPos portal) {
        final int RADIUS = 64;
        final int SCAN_TOP = 72;
        final int STRIDE = 2;
        Vec3d center = Vec3d.ofCenter(portal);
        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (int dx = -RADIUS; dx <= RADIUS; dx += STRIDE) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += STRIDE) {
                int x = portal.getX() + dx;
                int z = portal.getZ() + dz;
                BlockPos chunkProbe = new BlockPos(x, 40, z);
                if (!mod.getChunkTracker().isChunkLoaded(chunkProbe)) continue;
                int top = -1;
                for (int y = SCAN_TOP; y > portal.getY(); y--) {
                    BlockState s = mod.getWorld().getBlockState(new BlockPos(x, y, z));
                    if (s.isAir() || s.getBlock() == Blocks.WATER || s.getBlock() instanceof LeavesBlock) continue;
                    top = y;
                    break;
                }
                // (2213 修正) 只认海面以上的陆地列 (top≥61): 海床砂砾顶 (y≈19) 也是
                // "非水实心", 原实现会把海底列当陆地列 → GetToBlockTask 又去潜水,
                // 改道形同虚设。海底列一律跳过。
                if (top < 61) continue;
                BlockPos ground = new BlockPos(x, top, z);
                if (!WorldHelper.isSolid(mod, ground)) continue;
                double sq = center.squaredDistanceTo(Vec3d.ofCenter(ground));
                if (sq < bestSq) {
                    bestSq = sq;
                    best = ground;
                }
            }
        }
        return best;
    }

    private Task deepWaterDigDownApproach(AltoClef mod, BlockPos portal, Predicate<BlockPos> standablePortal) {
        if (_rerouteColumn == null || !portal.equals(_reroutePortal)) {
            _reroutePortal = portal.toImmutable();
            _rerouteColumn = findDigDownColumn(mod, portal);
            if (_rerouteColumn != null) {
                Debug.logMessage("[PortalApproach] 门在深水柱正下方 (y=" + portal.getY()
                        + ", 上方连续水柱≥12) -> 改道: 先到陆地列 " + _rerouteColumn.toShortString()
                        + " 再直挖下潜 (water-bob 卡死规避)");
            }
        }
        if (_rerouteColumn == null) {
            // 64 格内无可用陆地列 → 回退原直扑路径 (交给既有看门狗)。
            // (2213 补) 回退必须留痕: 静默回退 = 触发了改道但表现与没改一样, 无法诊断。
            Debug.logMessage("[PortalApproach] 改道激活但门 64 格内无海面以上陆地列, 回退直扑 "
                    + portal.toShortString());
            return new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos, false),
                    standablePortal, Blocks.NETHER_PORTAL);
        }
        BlockPos bot = mod.getPlayer().getBlockPos();
        boolean atColumn = Math.abs(bot.getX() - _rerouteColumn.getX()) <= 1
                && Math.abs(bot.getZ() - _rerouteColumn.getZ()) <= 1;
        if (atColumn) {
            setDebugState("On land column, digging down to underwater portal");
            return new GetToBlockTask(portal, false);
        }
        setDebugState("Deep-water portal: heading to land column " + _rerouteColumn.toShortString());
        return new GetToBlockTask(_rerouteColumn.up(), false);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EnterNetherPortalTask task) {
            return (Objects.equals(task._getPortalTask, _getPortalTask) && Objects.equals(task._targetDimension, _targetDimension));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Entering nether portal";
    }
}
