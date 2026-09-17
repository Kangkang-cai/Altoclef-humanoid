package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.function.Predicate;

/**
 * 2026-08-23: 从流动水追踪到源头
 * 策略：从流动水位置，找相邻的 level 更高的水方块，逐步追踪到 level 8 的源
 */
public class TraceFlowingWaterToSourceTask extends Task {

    private final Predicate<BlockPos> _isFlowingWater;
    private BlockPos _currentTracePos;
    // (2026-09-04 溯源失败黑名单, 用户"看到水流应定位水源") 记录本次溯源的起始流动水
    // 位置与是否放弃。父任务(CollectBucketLiquidTask)在溯源失败后把该起点加入黑名单,
    // 避免"溯源失败→重试同一处→溯源↔回地表打架"。
    private BlockPos _startPos;
    private boolean _gaveUp = false;
    private int _traceAttempts = 0;
    private static final int MAX_TRACE_ATTEMPTS = 50;
    private final Set<BlockPos> _visited = new HashSet<>();
    // (2026-09-13 v25 锁定追踪修复, 20260913-091045 实锤: 931 条 "tracing to"
    // 每 50ms 换一格, <Approaching> 任务 46-53ms 即被顶掉 = 人根本不动) 旧 onTick
    // 每 tick 找下一个更高 level 邻居并 new GetCloseToBlockTask —— 框架铁律
    // "返回任务≠任务会跑完": 下一 tick 返回新实例, 旧的立刻 STOP, baritone 一步
    // 都没走。修: 人类溯源行为 = 走到当前格【之后】再评估下一个上游格 ——
    // 在途子任务未完成时每 tick 原样续租同一实例。
    private Task _approachTask = null;
    private BlockPos _approachTarget = null;
    // (2026-09-17 接近点口径修正) 实际走近的是"水格旁【干燥可站格】"(见 onTick),
    // 与本次接近对应的水格分开记 —— 续租判定要看"水格还是不是当前追踪格"。
    private BlockPos _approachWaterPos = null;
    // (2026-09-13 v26 无进展超时 + 可达性门控, 20260913-093823 实锤: attempt 1
    // 目标 122,7,-269 而玩家在 y58+, 垂直差 51 格 → GetCloseToBlockTask 无路 →
    // 每 3-4s 一轮 Wander 空转 16s 后被上层放弃) 溯源只应跟随同一层的近邻水流,
    // 跨层水流走不到也没意义。
    private int _lastProgressTick = 0;
    private static final int TRACE_STALL_TIMEOUT_TICKS = 20 * 15;
    private static final int TRACE_MAX_VERTICAL_DELTA = 12;
    // (2026-09-13 v27 真实接近进度, 20260913-094812 实锤: 溯源 09:48:37.318 起
    // 目标 122,7,-269, 到 09:48:47.813 被父任务直接分支顶掉 —— 10.5s 只有 attempt 1
    // 一条日志 = 一步没推进; 期间 JumpStuck 卡墙 + 42.9-44.5 原地一进一退
    // (x122→123→123→122)。旧 _lastProgressTick 只在【换目标】时刷新, "朝一个到不了
    // 的格硬走 10 秒"完全漏检, 15s 总超时也够不着。改为按【与目标距离是否变小】
    // 判定: 4s 内没有实质接近 → 这条水流链走不通, 立刻放弃让父任务直取分支接手。
    private double _bestApproachSq = Double.MAX_VALUE;
    private int _lastApproachImproveTick = 0;
    private static final int TRACE_APPROACH_STALL_TICKS = 20 * 4;

    public TraceFlowingWaterToSourceTask(Predicate<BlockPos> isFlowingWater) {
        _isFlowingWater = isFlowingWater;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // 找一个流动水位置作为起点
        _startPos = findFlowingWater(mod);
        _currentTracePos = _startPos;
        _traceAttempts = 0;
        _gaveUp = false;
        _visited.clear();
        _approachTask = null;
        _approachTarget = null;
        _approachWaterPos = null;
        _lastProgressTick = WorldHelper.getTicks();
        _bestApproachSq = Double.MAX_VALUE;
        _lastApproachImproveTick = WorldHelper.getTicks();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_currentTracePos == null || _traceAttempts >= MAX_TRACE_ATTEMPTS) {
            Debug.logMessage("TraceFlowingWater: giving up after " + _traceAttempts
                    + " attempts (startPos=" + (_startPos == null ? "null" : _startPos.toShortString())
                    + ") | " + diagnose(mod));
            _gaveUp = true;
            return new TimeoutWanderTask(10f);
        }

        // (2026-09-13 v26) 无进展超时: 15s 没推进一格 → 放弃 (旧代码会无限
        // wander 空转到上层任务放弃, 20260913-093823 空转 16s)。
        if (WorldHelper.getTicks() - _lastProgressTick > TRACE_STALL_TIMEOUT_TICKS) {
            Debug.logMessage("TraceFlowingWater: no progress for "
                    + (TRACE_STALL_TIMEOUT_TICKS / 20) + "s, giving up at " + _currentTracePos
                    + " | " + diagnose(mod));
            _gaveUp = true;
            return new TimeoutWanderTask(5f);
        }

        // 检查当前位置是否已经是源
        if (isSource(mod, _currentTracePos)) {
            Debug.logMessage("TraceFlowingWater: found source at " + _currentTracePos);
            return null; // 找到了，任务完成
        }

        // (2026-09-13 v25) 在途子任务未完成 → 续租同一实例, 不评估下一个目标。
        // 到达 (GetCloseToBlockTask.isFinished) 后才落到底部的"推进下一格"。
        if (_approachTask != null && _approachWaterPos != null
                && _approachWaterPos.equals(_currentTracePos)
                && !_approachTask.isFinished(mod)) {
            // (2026-09-13 v27) 真实接近进度判定 —— 见字段注释。到达前每 tick 只做
            // 这一件事: 距离实质变小就刷新计时, 长时间没接近就放弃这条水流链。
            // (2026-09-17 判据口径) 度量对象从"接近任务的目标格"改为【水格本身的
            // 水平距离】: 接近点是水格旁的干燥可站格, 垂直方向本来就允许有差
            // (站在岸边/台阶上), 3D 距离会把"已经站到位"误判成"没接近"。
            // 4s 阈值(TRACE_APPROACH_STALL_TICKS)保持用户钦定不变。
            double sq = horizontalSqTo(mod, _currentTracePos);
            if (sq < _bestApproachSq - 0.25) {
                _bestApproachSq = sq;
                _lastApproachImproveTick = WorldHelper.getTicks();
            } else if (WorldHelper.getTicks() - _lastApproachImproveTick > TRACE_APPROACH_STALL_TICKS) {
                Debug.logMessage("TraceFlowingWater: no approach progress for "
                        + (TRACE_APPROACH_STALL_TICKS / 20) + "s toward water "
                        + _currentTracePos.toShortString() + " → giving up | " + diagnose(mod));
                _gaveUp = true;
                return new TimeoutWanderTask(5f);
            }
            return _approachTask;
        }

        // 找相邻的 level 更高的水方块
        BlockPos higherLevel = findHigherLevelNeighbor(mod, _currentTracePos);
        if (higherLevel == null) {
            Debug.logMessage("TraceFlowingWater: no higher level neighbor found at "
                    + _currentTracePos.toShortString() + " (" + describeLiquid(mod, _currentTracePos)
                    + ") → giving up | " + diagnose(mod));
            _traceAttempts++;
            _currentTracePos = findFlowingWater(mod);
            _gaveUp = true;
            return new TimeoutWanderTask(5f);
        }

        // (2026-09-17 接近点口径修正 —— 本轮"看到水源却没找"的真正病根)
        // 旧代码走近 higherLevel.up(): 水格上方那格【脚下就是水】, 没有固体支撑,
        // 玩家站不进去 (下水后脚部 BlockPos 降到水格那一层) → GoalNear(该格, 0)
        // 在物理上永不满足 → baritone 全展开后放弃, pathing=false、无路径、零输入
        // 原地冻结 (20260917-220709 实锤: 玩家 (-44.51,-52,-121.77) 距目标 1 格,
        // 4s 零位移 → 本任务 4s 判据把整条水流链判死)。
        // 改为水格水平邻域里的【干燥可站格】—— 目标是可满足的, 玩家既能真站上去,
        // 也就站在了能看见/够得着源的位置。
        BlockPos standable = WorldHelper.findStandableNearLiquid(mod, higherLevel);
        if (standable == null) {
            // (2026-09-17 v2 水柱过渡格) 水格四周【没有干燥落脚点】≠ 走不通 —— 现场
            // 20260917-231306 的起点全部是 `lvl=8 still=false aboveLiquid=true
            // standableNear=false`, 即水柱/多层水体【内部】格: 它上一格是水、左右也是水,
            // 当然没有落脚点。旧行为在这里直接 gaveUp, 于是每一处水柱都在 50ms 内判死。
            //     改为把它当【过渡格】: 不派接近任务 (本来也没地方站), 直接推进到它的
            // 上游, 沿水柱一路判到源头; 最终能不能舀交给父任务的可达性 + LOS 判据
            // (源头若够不着, 父任务自然转去别处, 不会卡死)。_visited + MAX_TRACE_ATTEMPTS
            // 保证不会无限向上。
            _currentTracePos = higherLevel;
            _traceAttempts++;
            _lastProgressTick = WorldHelper.getTicks();
            _visited.add(higherLevel);
            Debug.logMessage("TraceFlowingWater: no standable spot beside water "
                    + higherLevel.toShortString() + " (" + describeLiquid(mod, higherLevel)
                    + ") → water-column transit, continue upstream (attempt " + _traceAttempts + ")");
            return null; // 本 tick 不派子任务, 下一 tick 继续沿链向上
        }

        // (2026-09-13 v26) 目标不可达 / 跨层太远 → 这条水流链到不了源头, 放弃
        // (父任务黑名单起点 + 15s 全局冷却), 不要派 GetCloseToBlockTask 空转。
        // v27: 两格都判可达 —— 与父任务直接分支 isSourceLiquid 的
        // canReach(pos) && canReach(pos.up()) 同口径。
        int dy = Math.abs(standable.getY() - mod.getPlayer().getBlockPos().getY());
        if (dy > TRACE_MAX_VERTICAL_DELTA
                || !WorldHelper.canReach(mod, standable)
                || !WorldHelper.canReach(mod, standable.up())) {
            Debug.logMessage("TraceFlowingWater: target " + standable.toShortString()
                    + " unreachable (dy=" + dy + ", water=" + higherLevel.toShortString()
                    + " " + describeLiquid(mod, higherLevel) + ") → giving up | " + diagnose(mod));
            _gaveUp = true;
            return new TimeoutWanderTask(5f);
        }

        // 移动到更高 level 的位置 (只在真正推进时计数/记日志 —— 每 tick 一次
        // 的旧口径让 attempts 2.5s 刷爆 50 上限, 931 条日志全是噪音)。
        _currentTracePos = higherLevel;
        _traceAttempts++;
        _lastProgressTick = WorldHelper.getTicks();
        _visited.add(higherLevel);

        Debug.logMessage("TraceFlowingWater: tracing to " + higherLevel + " (attempt " + _traceAttempts
                + ") via standable " + standable.toShortString());
        _approachTarget = standable;
        _approachWaterPos = higherLevel;
        // (2026-09-17) 必须用 GetWithinRangeOfBlockTask, 不能用 GetCloseToBlockTask:
        // 后者没覆写 isFinished (Task 默认恒 false) → 上面"续租分支"的
        // !_approachTask.isFinished(mod) 永远成立 → 只会永远续租同一个接近任务,
        // **从不推进到下一格** (20260917-220709 实锤: 全程只有 attempt 1 一条
        // "tracing to", 之后直接 4s 放弃)。range=1 也刻意不用 0: 目标是"站到水流
        // 旁边", 不需要精确站进某一格, 且 range=0 的 GoalNear 对可站格也过严。
        _approachTask = new GetWithinRangeOfBlockTask(standable, 1);
        _bestApproachSq = Double.MAX_VALUE;
        _lastApproachImproveTick = WorldHelper.getTicks();
        return _approachTask;
    }

    /** (2026-09-04) 溯源是否已失败放弃(找不到源)。供父任务黑名单起点用。 */
    public boolean gaveUp() {
        return _gaveUp;
    }

    /** (2026-09-04) 本次溯源的起始流动水位置(失败时父任务黑名单它)。 */
    public BlockPos getStartPos() {
        return _startPos;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 失败放弃或已定位到源都算完成, 父任务据此清理 _traceTask。
        if (_gaveUp) return true;
        return _currentTracePos != null && isSource(mod, _currentTracePos);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TraceFlowingWaterToSourceTask;
    }

    @Override
    protected String toDebugString() {
        return "Tracing flowing water to source";
    }

    private BlockPos findFlowingWater(AltoClef mod) {
        // 从 BlockTracker 找一个流动水位置
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        BlockPos playerPos = mod.getPlayer().getBlockPos();

        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.WATER)) {
            if (_isFlowingWater.test(pos) && !_visited.contains(pos)) {
                double dist = pos.getSquaredDistance(playerPos);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = pos;
                }
            }
        }
        return closest;
    }

    private boolean isSource(AltoClef mod, BlockPos pos) {
        var state = MinecraftClient.getInstance().world.getBlockState(pos);
        return state.getBlock() == Blocks.WATER && state.getFluidState().isStill();
    }

    private BlockPos findHigherLevelNeighbor(AltoClef mod, BlockPos pos) {
        var world = MinecraftClient.getInstance().world;
        var currentState = world.getBlockState(pos);
        int currentLevel = currentState.getFluidState().getLevel();

        // (2026-09-17 v2 水柱/下落水上游, 20260917-231306 实锤)
        // MC 里【下落水】(FallingFluid) 的 level 也是 8, 与源方块同级 —— 于是
        // "只选 level 更高的邻居"这条判据在水柱/多层水体里永远找不到上游:
        // 起点 lvl=8 still=false aboveLiquid=true, 上方那格水同样是 8 → 不满足
        // `neighborLevel > currentLevel` → 返回 null → 本任务 3ms 内 gaveUp,
        // tasks.log 实测 `START <Tracing...>` → 50ms 后 STOP, attempt 恒 0,
        // 每 15s 冷却后重来一次, 永远走不出探索分支 (用户: "压根没往水那边去")。
        //     垂直上游优先: 上方是水 → 水就是从上面来的 (瀑布/水柱/多层水体),
        // 顺着它往上就是朝源头走。人类找源也是先抬头看水从哪下来的。
        BlockPos above = pos.up();
        if (!_visited.contains(above)) {
            var aboveState = world.getBlockState(above);
            if (aboveState.getBlock() == Blocks.WATER) {
                return above;
            }
        }

        BlockPos best = null;
        int bestLevel = currentLevel;

        // 检查所有相邻位置（包括上方）
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (_visited.contains(neighbor)) continue;

            var neighborState = world.getBlockState(neighbor);
            if (neighborState.getBlock() != Blocks.WATER) continue;

            int neighborLevel = neighborState.getFluidState().getLevel();
            // 找 level 更高的（更接近源）
            if (neighborLevel > bestLevel) {
                bestLevel = neighborLevel;
                best = neighbor;
            }
        }

        return best;
    }

    // ===== (2026-09-17 用户要求: 超时/放弃时必须把现场打出来) 只读诊断 =====

    /** 玩家与水格中心的水平距离平方 (接近判据用, 忽略垂直差)。 */
    private double horizontalSqTo(AltoClef mod, BlockPos pos) {
        if (pos == null) return Double.MAX_VALUE;
        double dx = (pos.getX() + 0.5) - mod.getPlayer().getX();
        double dz = (pos.getZ() + 0.5) - mod.getPlayer().getZ();
        return dx * dx + dz * dz;
    }

    /** 液体格现场描述: 方块类型 / 流体等级 / 是否静止 / 上方是否还是液体 / 旁边有无干燥落脚点。 */
    private String describeLiquid(AltoClef mod, BlockPos pos) {
        if (pos == null) return "null";
        var world = MinecraftClient.getInstance().world;
        if (world == null) return "world=null";
        var state = world.getBlockState(pos);
        var fluid = state.getFluidState();
        boolean aboveLiquid = world.getBlockState(pos.up()).getBlock() instanceof FluidBlock;
        return "block=" + state.getBlock() + " lvl=" + fluid.getLevel() + " still=" + fluid.isStill()
                + " aboveLiquid=" + aboveLiquid
                + " standableNear=" + (WorldHelper.findStandableNearLiquid(mod, pos) != null);
    }

    /** baritone 实时状态 (active / 正在寻路 / 有路径) —— 冻结现场的关键证据。 */
    private String baritoneState(AltoClef mod) {
        try {
            var pathing = mod.getClientBaritone().getPathingBehavior();
            var goal = mod.getClientBaritone().getCustomGoalProcess();
            return "baritone[active=" + goal.isActive() + " pathing=" + pathing.isPathing()
                    + " path=" + pathing.getPath().isPresent() + "]";
        } catch (Throwable t) {
            return "baritone[unavailable]";
        }
    }

    /**
     * (2026-09-17) 放弃/超时现场快照 —— 一行打包"为什么这一步走不到":
     * 玩家坐标(整数+精确) / 是否触水 / 已尝试格数 / 当前水格状态 /
     * 与水格的水平距离 / 接近任务与其完成态 / baritone 状态。
     * 上一轮 (20260917-220709) 只有 "no approach progress for 4s" 一行,
     * 无法区分"目标不可站"与"寻路失败", 这次必须自带证据。
     */
    private String diagnose(AltoClef mod) {
        return "player=" + mod.getPlayer().getBlockPos().toShortString()
                + " xyz=(" + String.format("%.2f,%.2f,%.2f",
                mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ()) + ")"
                + " inWater=" + mod.getPlayer().isTouchingWater()
                + " attempts=" + _traceAttempts
                + " water=" + (_currentTracePos == null ? "null"
                : _currentTracePos.toShortString() + "(" + describeLiquid(mod, _currentTracePos) + ")")
                + " horizSqToWater=" + String.format("%.2f", horizontalSqTo(mod, _currentTracePos))
                + " approach=" + (_approachTarget == null ? "null"
                : _approachTarget.toShortString() + "[finished="
                + (_approachTask != null && _approachTask.isFinished(mod)) + "]")
                + " " + baritoneState(mod);
    }
}
