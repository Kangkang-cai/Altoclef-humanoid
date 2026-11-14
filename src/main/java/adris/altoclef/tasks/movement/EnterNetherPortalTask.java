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

        // (20261114 三段导航) 重入复位: 同一实例 stop/start 后相位与缓存任务
        // 可能残留 (如停在 TUNNEL 而 bot 已被拉回地表), 一律回到 SURFACE 重新
        // 推进; 深度快速升相位保证: bot 若已在门高度层, 首个 tick 直接升
        // DESCENT/TUNNEL, 不会被派回地表列顶重新下挖。
        invalidateApproachTasks();
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
        // (20261114) 深挖让位标志随任务停止清除, 防残留挡死 SurfaceExitChain。
        _deepApproachActive = false;
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

    /**
     * (20261114) 深水门三段导航 DESCENT/TUNNEL 进行中标志 (静态, 供跨链查询)。
     *
     * <p>存在的理由: DESCENT 是【主动下挖竖井】, 而 SurfaceExitChain 会把
     * "bot 站在 1 格宽露天竖井底部"当成需要引导出洞的场景反复抢占 —— 竖井
     * 露天直通天空, 高度图把井底当地表, isBelowSurfaceDebounced=false 不触发
     * "在地下"豁免; 且每次被拉出井口翻面都会重新武装配额, 死循环自续
     * (20261114-131021 实锤: Surface Exit Chain 20 次抢占, User Tasks ↔
     * Surface Exit Chain 每 ~5s 互换, y=48↔58 弹跳零净位移)。
     * 本标志置位期间, SurfaceExitChain 必须 {让位}。
     */
    private static boolean _deepApproachActive = false;

    public static boolean isDeepApproachActive() {
        return _deepApproachActive;
    }

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

    // =========================================================================
    // (20261114 三段导航·用户设计) 深水门改道的交接协议:
    //   SURFACE  先到干燥陆地列顶 (地表, baritone 正常走位);
    //   DESCENT  导航到【陆地列正下方、与门同高】的地下点 —— baritone 自由挖掘
    //            下潜到门的高度层 (竖直下挖 ~40 格, 干燥地层, 无水柱问题);
    //   TUNNEL   再导航到门 —— 同一高度层横向隧道, baritone 自由挖过去。
    // 中间多一次"目标修正"的意义: 直扑门的路径会横跨海面+水柱 (water-bob),
    // 把目标先降到门的高度层, TUNNEL 段就是纯干燥同层隧道。
    // 派发异常三防:
    //   ① 相位前向单向 (只升不降, 到达判据只用于升相位) — 治 11:02 实锤的
    //      ±1 驻留条件被位置抖动打穿 → 两个 GetToBlockTask 每 2s 互换洗进度;
    //   ② 任务实例缓存复用 (1112 HoldClose 教训: 每 tick new → onStart 重启
    //      baritone 路径) — 目标不变不重建, 门换位/列重算才整体失效;
    //   ③ Mob Defense 抢占 SUSPEND/RESUME 不重建实例, 相位保留; 整链被
    //      watchdog 重建时相位回 SURFACE, bot 已在列附近秒级重推进。
    // =========================================================================

    private enum ApproachPhase { SURFACE, DESCENT, TUNNEL }

    private ApproachPhase _approachPhase = ApproachPhase.SURFACE;
    private GetToBlockTask _cachedSurfaceTask = null;
    private GetToBlockTask _cachedDescentTask = null;
    private GetToBlockTask _cachedTunnelTask = null;

    private void invalidateApproachTasks() {
        _approachPhase = ApproachPhase.SURFACE;
        _cachedSurfaceTask = null;
        _cachedDescentTask = null;
        _cachedTunnelTask = null;
    }

    private Task deepWaterDigDownApproach(AltoClef mod, BlockPos portal, Predicate<BlockPos> standablePortal) {
        if (_rerouteColumn == null || !portal.equals(_reroutePortal)) {
            _reroutePortal = portal.toImmutable();
            _rerouteColumn = findDigDownColumn(mod, portal);
            invalidateApproachTasks();
            if (_rerouteColumn != null) {
                Debug.logMessage("[PortalApproach] 门在深水柱正下方 (y=" + portal.getY()
                        + ", 海面是水) -> 三段导航: 陆地列 " + _rerouteColumn.toShortString()
                        + " → 列下方同门高层 → 横向隧道至门 (water-bob 卡死规避)");
            }
        }
        if (_rerouteColumn == null) {
            // 64 格内无可用陆地列 → 回退原直扑路径 (交给既有看门狗)。
            // 回退必须留痕: 静默回退 = 触发了改道但表现与没改一样, 无法诊断。
            Debug.logMessage("[PortalApproach] 改道激活但门 64 格内无海面以上陆地列, 回退直扑 "
                    + portal.toShortString());
            _deepApproachActive = false; // 直扑路径不压制 SurfaceExitChain。
            return new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos, false),
                    standablePortal, Blocks.NETHER_PORTAL);
        }

        BlockPos bot = mod.getPlayer().getBlockPos();
        BlockPos descentTarget = new BlockPos(_rerouteColumn.getX(), portal.getY(), _rerouteColumn.getZ());

        // (20261114 二修·用户点名) 已在地层深处 (y≤门+6) 时绝不派回地表:
        // 实例被抢占重派/onStart 复位后相位回 SURFACE, 若不按深度快速升相位,
        // 深挖中的 bot 会被派回列顶 (y=62) 再重新下挖, 无限往返
        // (20261114-120225 实锤: 列顶快照 47 次 vs 地下 26 次, y=42→52 爬回中)。
        // 距门水平≤10 直接 TUNNEL; 否则 DESCENT (地下自由导航到列下方同高点)。
        if (_approachPhase == ApproachPhase.SURFACE && bot.getY() <= portal.getY() + 6) {
            boolean nearPortal = Math.abs(bot.getX() - portal.getX()) <= 10
                    && Math.abs(bot.getZ() - portal.getZ()) <= 10;
            _approachPhase = nearPortal ? ApproachPhase.TUNNEL : ApproachPhase.DESCENT;
            Debug.logMessage("[PortalApproach] [PHASE] SURFACE -> " + _approachPhase
                    + " (已在门高度层, 不派回地表)");
        }

        // 相位只前进不回退: 升相位 = 到达判据成立 (一次性事件), 之后不再复核。
        if (_approachPhase == ApproachPhase.SURFACE
                && Math.abs(bot.getX() - _rerouteColumn.getX()) <= 2
                && Math.abs(bot.getZ() - _rerouteColumn.getZ()) <= 2
                && Math.abs(bot.getY() - _rerouteColumn.getY()) <= 3) {
            _approachPhase = ApproachPhase.DESCENT;
            Debug.logMessage("[PortalApproach] [PHASE] SURFACE -> DESCENT: 已到列顶, 下挖到门同高层 "
                    + descentTarget.toShortString());
        } else if (_approachPhase == ApproachPhase.DESCENT
                && bot.isWithinDistance(descentTarget, 3)) {
            _approachPhase = ApproachPhase.TUNNEL;
            Debug.logMessage("[PortalApproach] [PHASE] DESCENT -> TUNNEL: 已到门同高层, 横向隧道至门 "
                    + portal.toShortString());
        } else if (_approachPhase == ApproachPhase.DESCENT
                && bot.getY() <= portal.getY() + 6
                && Math.abs(bot.getX() - portal.getX()) <= 10
                && Math.abs(bot.getZ() - portal.getZ()) <= 10) {
            // 下挖途中已到门同高层且水平邻近门: 直接隧道, 不再绕回列下方中间点。
            _approachPhase = ApproachPhase.TUNNEL;
            Debug.logMessage("[PortalApproach] [PHASE] DESCENT -> TUNNEL (邻近门): 横向隧道至门 "
                    + portal.toShortString());
        }

        switch (_approachPhase) {
            case DESCENT:
                if (_cachedDescentTask == null) _cachedDescentTask = new GetToBlockTask(descentTarget, false);
                setDebugState("Digging down to portal-height point " + descentTarget.toShortString());
                _deepApproachActive = true;
                return _cachedDescentTask;
            case TUNNEL:
                if (_cachedTunnelTask == null) _cachedTunnelTask = new GetToBlockTask(portal, false);
                setDebugState("Tunneling to underwater portal " + portal.toShortString());
                _deepApproachActive = true;
                return _cachedTunnelTask;
            case SURFACE:
            default:
                if (_cachedSurfaceTask == null) _cachedSurfaceTask = new GetToBlockTask(_rerouteColumn.up(), false);
                setDebugState("Heading to land column " + _rerouteColumn.toShortString());
                _deepApproachActive = false; // 地表段不压制 SurfaceExitChain (bot 在地表属正常出洞场景)。
                return _cachedSurfaceTask;
        }
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
