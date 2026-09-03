package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.function.Predicate;

public class CollectBucketLiquidTask extends ResourceTask {

    private final HashSet<BlockPos> _blacklist = new HashSet<>();
    private final TimerGame _tryImmediatePickupTimer = new TimerGame(3);
    private final TimerGame _pickedUpTimer = new TimerGame(0.5);
    private final int _count;

    //private IProgressChecker<Double> _checker = new LinearProgressChecker(5, 0.1);
    private final Item _target;
    private final Block _toCollect;
    private final String _liquidName;
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(15f);
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();

    // (2026-09-03 用户: 找水/岩浆先回地表) 地下(y<58, 与 isDeepUnderground 同口径)
    // 随机游荡找水几乎无果, 且持续探入未加载区块 → 地形生成 + 路径搜索把帧率拖到
    // 100ms+ (altoclef_jank.log 实锤 "Wander for Infinity blocks" 期间 frameMs=102~146)。
    // 地表河流/湖/海找水概率高, 地表也常有裸露岩浆池; 回地表路上 BlockTracker 近区
    // 扫描会顺路发现液体源。到地表后以【有界游荡】(每段 SURFACE_SEARCH_WANDER_RANGE
    // 自动返回重估, 顺路感知) 替代原先的无限游荡 (TimeoutWanderTask(∞) 永不结束)。
    private static final int SURFACE_SEARCH_WANDER_RANGE = 50;
    private static final int BELOW_SURFACE_Y = 58;
    // 找水诊断日志节流 (原每 tick 一条刷屏聊天栏, 影响性能也难读)。
    private final TimerGame _liquidDiagTimer = new TimerGame(2);

    private boolean wasWandering = false;

    public CollectBucketLiquidTask(String liquidName, Item filledBucket, int targetCount, Block toCollect) {
        super(filledBucket, targetCount);
        _liquidName = liquidName;
        _target = filledBucket;
        _count = targetCount;
        _toCollect = toCollect;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    protected void onResourceStart(AltoClef mod) {
        // Track all water (source + flowing), not just sources
        mod.getBehaviour().push();
        mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.ANY);
        mod.getBlockTracker().trackBlock(Blocks.WATER);

        // Avoid breaking / placing blocks at our liquid
        mod.getBehaviour().avoidBlockBreaking((pos) -> MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == _toCollect);
        mod.getBehaviour().avoidBlockPlacing((pos) -> MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == _toCollect);

        //_blacklist.clear();

        _wanderTask.resetWander();

        _progressChecker.reset();
    }


    @Override
    protected Task onTick(AltoClef mod) {
        Task result = super.onTick(mod);
        // Reset our "first time" timeout/wander flag.
        if (!thisOrChildAreTimedOut()) {
            wasWandering = false;
        }
        return result;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        // Run one update to prevent the false fail bug?
        _progressChecker.check(mod);

        // If we're standing inside a liquid, go pick it up.
        if (_tryImmediatePickupTimer.elapsed()) {
            Block standingInside = mod.getWorld().getBlockState(mod.getPlayer().getBlockPos()).getBlock();
            if (standingInside == _toCollect) {
                setDebugState("Trying to collect (we are in it)");
                LookHelper.lookAt(mod, new Rotation(0, 90));
                //mod.getClientBaritone().getLookBehavior().updateTarget(new Rotation(0, 90), true);
                //Debug.logMessage("Looking at " + _toCollect + ", picking up right away.");
                _tryImmediatePickupTimer.reset();
                if (mod.getSlotHandler().forceEquipItem(Items.BUCKET)) {
                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                    mod.getExtraBaritoneSettings().setInteractionPaused(true);
                    _pickedUpTimer.reset();
                    _progressChecker.reset();
                }
                return null;
            }
        }

        if (!_pickedUpTimer.elapsed()) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _progressChecker.reset();
            // Wait for force pickup
            return null;
        }

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Failed to receive: Wandering.");
            _progressChecker.reset();
            return _wanderTask;
        }

        // Get buckets if we need em
        int bucketsNeeded = _count - mod.getItemStorage().getItemCount(Items.BUCKET) - mod.getItemStorage().getItemCount(_target);
        if (bucketsNeeded > 0) {
            setDebugState("Getting bucket...");
            return TaskCatalogue.getItemTask(Items.BUCKET, bucketsNeeded);
        }

        Predicate<BlockPos> isSourceLiquid = blockPos -> {
            if (_blacklist.contains(blockPos)) return false;
            // (2026-09-03 用户原则: 不满足 FOV/LOS 的不评估可达性, 且不限制距离)
            // 看不到的水(墙后/未暴露表面)直接排除, 不进入后续 canReach。
            // LOS 射线已有 400ms 短缓存 (VisibilityHelper), 不会每 tick 重打。
            if (!VisibilityHelper.isBlockPerceivable(mod, blockPos)) return false;
            if (!WorldHelper.canReach(mod, blockPos)) return false;
            if (!WorldHelper.canReach(mod, blockPos.up())) return false; // We may try reaching the block above.
            assert MinecraftClient.getInstance().world != null;

            // Lava, we break the block above. If it's bedrock, ignore.
            if (_toCollect == Blocks.LAVA && !WorldHelper.canBreak(mod, blockPos.up())) {
                return false;
            }

            // 2026-08-23: 对水，接受水源方块；对熔岩，只接受源
            return WorldHelper.isSourceBlock(mod, blockPos, false);
        };

        // 2026-08-23: 诊断 - 检查 BlockTracker 是否感知到任何水
        // (2026-09-03 节流: 原每 tick 打印一条, 无水源时也刷屏, 影响帧率/难读日志)
        if (_liquidDiagTimer.elapsed()) {
            _liquidDiagTimer.reset();
            int totalWaterKnown = 0;
            int sourceWaterKnown = 0;
            int flowingWaterKnown = 0;
            for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.WATER)) {
                totalWaterKnown++;
                var state = MinecraftClient.getInstance().world.getBlockState(pos);
                if (state.getFluidState().isStill()) {
                    sourceWaterKnown++;
                } else {
                    flowingWaterKnown++;
                }
            }
            Debug.logMessage("[Diag-water] totalWater=%d, source=%d, flowing=%d, canReach=%s",
                    totalWaterKnown, sourceWaterKnown, flowingWaterKnown,
                    totalWaterKnown > 0 ? "checking..." : "NONE");
        }

        // 2026-08-23: 如果没找到水源，尝试从流动水追踪到源头
        // (2026-09-03 防溯源↔回地表打架, 用户实锤) 深地下(y<58)不回溯源: 回地表路上
        // 一看到流动水就被溯源任务劫持横向走, 溯源无果又回到回地表 → 反复横跳。
        // 溯源只在已到地表且无源时进行 (地表溪流沿上游找源是合理行为)。
        if (!isBelowSurface(mod)
                && !mod.getBlockTracker().anyFound(isSourceLiquid, _toCollect) && _toCollect == Blocks.WATER) {
            setDebugState("No source found, tracing flowing water to source...");
            // 找所有流动水位置
            Predicate<BlockPos> isFlowingWater = blockPos -> {
                if (_blacklist.contains(blockPos)) return false;
                // (2026-09-03 用户原则) 不满足 FOV/LOS 的不评估可达性。
                if (!VisibilityHelper.isBlockPerceivable(mod, blockPos)) return false;
                if (!WorldHelper.canReach(mod, blockPos)) return false;
                assert MinecraftClient.getInstance().world != null;
                var state = MinecraftClient.getInstance().world.getBlockState(blockPos);
                return state.getBlock() == Blocks.WATER && !state.getFluidState().isStill();
            };

            if (mod.getBlockTracker().anyFound(isFlowingWater, Blocks.WATER)) {
                // 找到流动水，追踪到源头
                setDebugState("Found flowing water, tracing to source...");
                return new TraceFlowingWaterToSourceTask(isFlowingWater);
            } else {
                Debug.logMessage("[Diag-water] No flowing water found in BlockTracker either");
            }
        }

        // Find nearest water and right click it
        if (mod.getBlockTracker().anyFound(isSourceLiquid, _toCollect)) {
            // We want to MINIMIZE this distance to liquid.
            setDebugState("Trying to collect...");
            //Debug.logMessage("TEST: " + RayTraceUtils.fluidHandling);

            return new DoToClosestBlockTask(blockPos -> {
                // Clear above if lava because we can't enter.
                // but NOT if we're standing right above.
                if (WorldHelper.isSolid(mod, blockPos.up())) {

                    if (!_progressChecker.check(mod)) {
                        Debug.logMessage("Failed to break, blacklisting & wandering");
                        mod.getBlockTracker().requestBlockUnreachable(blockPos);
                        _blacklist.add(blockPos);
                        return _wanderTask;
                    }
                    return new DestroyBlockTask(blockPos.up());
                }

                // We can reach the block.
                if (LookHelper.getReach(blockPos).isPresent()) {
                    return new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), blockPos, _toCollect != Blocks.LAVA, new Vec3i(0, 1, 0));
                }
                // Get close enough.
                // up because if we go below we'll try to move next to the liquid (for lava, not a good move)
                if (this.thisOrChildAreTimedOut() && !wasWandering) {
                    mod.getBlockTracker().requestBlockUnreachable(blockPos.up());
                    wasWandering = true;
                }
                return new GetCloseToBlockTask(blockPos.up());
            }, isSourceLiquid, _toCollect);
        }

        // Dimension
        if (_toCollect == Blocks.WATER && WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // Oof, no liquid found.
        // (2026-09-03 用户: 找水/岩浆先回地表) 地下(主世界 y<58, 与 isDeepUnderground
        // 同口径)几乎没有地表型水源, 随机游荡只会越钻越深 + 拖慢帧率; 先显式回地表
        // (路上近区扫描顺路发现液体源即收), 地表找水(河/湖/海)与裸露岩浆池概率高。
        // 只对主世界生效: 下界/末地 y<58 不代表"地下", 回地表逻辑无意义(且可能挖向
        // 下界基岩天花板)。
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD
                && mod.getPlayer() != null && mod.getPlayer().getY() < BELOW_SURFACE_Y) {
            setDebugState("No " + _liquidName + " underground, returning to surface to search");
            return new GoToSurfaceTask();
        }

        setDebugState("Searching for liquid by wandering around aimlessly");
        // (2026-09-03 无限游荡 → 有界游荡) 地表探索用有界游荡: 每走完一段自动回到
        // 本分支重估 (有液体源就近收 / 无则换方向再走一段), 不再 TimeoutWanderTask(∞)
        // 一条路走到黑永不评估。游荡是探索手段, 不是目标。
        return new TimeoutWanderTask(SURFACE_SEARCH_WANDER_RANGE);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_toCollect);
        mod.getBehaviour().pop();
        //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        mod.getExtraBaritoneSettings().setInteractionPaused(false);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBucketLiquidTask task) {
            if (task._count != _count) return false;
            return task._toCollect == _toCollect;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " " + _liquidName + " buckets";
    }

    /**
     * (2026-09-03) 是否属于"深地下应回地表找液体"的场景: 主世界 y<58
     * (与 WorldHelper.isDeepUnderground 同口径)。下界/末地不算 — 回地表语义
     * 只对主世界有意义 (下界 y<58 也满是岩浆, 无需回任何"地表")。
     */
    private boolean isBelowSurface(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == Dimension.OVERWORLD
                && mod.getPlayer() != null && mod.getPlayer().getY() < BELOW_SURFACE_Y;
    }

    public static class CollectWaterBucketTask extends CollectBucketLiquidTask {
        public CollectWaterBucketTask(int targetCount) {
            super("water", Items.WATER_BUCKET, targetCount, Blocks.WATER);
        }
    }

    public static class CollectLavaBucketTask extends CollectBucketLiquidTask {
        public CollectLavaBucketTask(int targetCount) {
            super("lava", Items.LAVA_BUCKET, targetCount, Blocks.LAVA);
        }
    }

}
