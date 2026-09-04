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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
    // 扫描会顺路发现液体源。到地表后用持久 heading 步进探索 (见 getSurfaceExploreTask,
    // 2026-09-04 替代原无限/有界游荡, 坚持方向不原地打转)。
    private static final int BELOW_SURFACE_Y = 58;

    // (2026-09-04 用户"找lava随机方向原地打转, 应坚持大致方向前进, 左右转不回头")
    // 地表探索不再用 TimeoutWanderTask (每段重估可能换方向 → 原地打转)。改用
    // 持久 heading: 每段沿当前方向走 EXPLORE_STEP_DIST 格 (GetToXZTask, 到达后
    // 父任务重估, 有液体源就近收), 每段仅 ±EXPLORE_JITTER_DEG 小角度抖动 (左右
    // 微调, 绝不回头/大角度转向), 持续朝大致方向推进不原地打转。
    private double _exploreHeading = Double.NaN;
    private static final int EXPLORE_STEP_DIST = 48;
    private static final double EXPLORE_JITTER_DEG = 25;
    // (2026-09-04 v3 遇海丝滑转向) 前方水域探测: 正前方这个距离是水/海则偏航绕开。
    private static final int EXPLORE_WATER_PROBE = 24;
    // 每档偏航角度 (度): 小幅转向, 丝滑不突兀。
    private static final double EXPLORE_TURN_STEP_DEG = 30;
    // 最大连续偏航档数: 6×30°=180°, 足以绕开半岛/海湾, 不会无限转圈。
    private static final int MAX_TURNS = 6;
    // (2026-09-04 v2 粘性修复, 用户"不对劲看最新日志") 持久的当前探索任务实例:
    // ResourceTask.onTick 每 tick 调 onResourceTick, 子任务粘性靠 isEqual —— 若每次
    // new GetToXZTask(不同 x,z) 则 isEqual false → 每 tick 重启换目标 (日志实锤
    // heading 758→778→763→748deg 每 tick 变, 目标 222,493→210,504→220,496 每 tick
    // 换)。缓存实例, active 且未完成时返回同一实例, 到达后才重算下一步。
    private GetToXZTask _exploreTask = null;

    // 找水诊断日志节流 (原每 tick 一条刷屏聊天栏, 影响性能也难读)。
    private final TimerGame _liquidDiagTimer = new TimerGame(2);

    // (2026-09-04 溯源任务实例, 用户"看到水流应定位水源") 持有当前溯源任务引用以读取
    // 失败状态; 溯源失败(gaveUp)时把起始流动水加入 _blacklist, 避免重试同一处。
    private TraceFlowingWaterToSourceTask _traceTask = null;

    // (2026-09-04 海洋中转头找陆地, 用户"扫描时配合视角转动, 使得陆地满足fov")
    // bot 上浮到海面后不能"透视"biome 直接知道陆地方向 — 像人类一样转头环顾,
    // 让陆地进入视野(FOV) 再朝它导航。ScanLookBehavior: 静止时全景环顾、移动时
    // 沿行进方向横扫。
    private final ScanLookBehavior _scanLook = new ScanLookBehavior();

    // (2026-09-04 v3 缓存陆地目标, 用户"一次性导航找不到dry point卡在原地")
    // 日志实锤: findNearestLand 每 tick 从玩家位置重算 → 玩家在海上缓慢移动 →
    // 返回的陆地 XZ 每 tick 变 (357→358) → GetToXZTask 每 tick 换目标 → baritone
    // 永远重算路径到不了岸。修复: 缓存目标, 到达(<3格)才重算下一段, 逐段向陆地
    // 推进; 不在海洋(已上岸)清除。目标稳定, 不会每 tick 抖动。
    private BlockPos _landTarget = null;

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
        // Track the liquid we actually collect (water OR lava), not just water.
        // (2026-09-04 修: 原无条件 trackBlock(WATER) — 找 lava 时没追踪 lava →
        // lava 缓存恒空 → 永远感知不到岩浆 → 只能地表游荡/定位到水面)。
        mod.getBehaviour().push();
        mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.ANY);
        mod.getBlockTracker().trackBlock(_toCollect);

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
        // (2026-09-04 用户"看到水流应定位水源": 地下看到流动水也应就地溯源到源再装桶,
        // 而不是直接回地表) 放开原"深地下不溯源"闸门。防打架: 溯源失败(gaveUp)时把
        // 该起点流动水加入黑名单, 下次不再溯源同一处; 全部失败后自然落到回地表。
        // 流动水谓词已带 LOS 门控(只溯看得见的近处水流), BlockTracker 缓存已上限100。
        if (!mod.getBlockTracker().anyFound(isSourceLiquid, _toCollect) && _toCollect == Blocks.WATER) {
            // 处理上次溯源任务的结果: 失败则黑名单其起点, 成功/失败都清理实例。
            if (_traceTask != null && _traceTask.isFinished(mod)) {
                if (_traceTask.gaveUp() && _traceTask.getStartPos() != null) {
                    _blacklist.add(_traceTask.getStartPos());
                    Debug.logMessage("[Diag-water] trace failed, blacklisting start "
                            + _traceTask.getStartPos().toShortString());
                }
                _traceTask = null;
            }
            Predicate<BlockPos> isFlowingWater = blockPos -> {
                if (_blacklist.contains(blockPos)) return false;
                // (2026-09-03 用户原则) 不满足 FOV/LOS 的不评估可达性。
                if (!VisibilityHelper.isBlockPerceivable(mod, blockPos)) return false;
                if (!WorldHelper.canReach(mod, blockPos)) return false;
                assert MinecraftClient.getInstance().world != null;
                var state = MinecraftClient.getInstance().world.getBlockState(blockPos);
                return state.getBlock() == Blocks.WATER && !state.getFluidState().isStill();
            };

            if (_traceTask == null && mod.getBlockTracker().anyFound(isFlowingWater, Blocks.WATER)) {
                // 找到流动水，追踪到源头
                setDebugState("Found flowing water, tracing to source...");
                _traceTask = new TraceFlowingWaterToSourceTask(isFlowingWater);
            }
            if (_traceTask != null) {
                return _traceTask;
            }
            Debug.logMessage("[Diag-water] No flowing water found in BlockTracker either");
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
        // (2026-09-04 海洋中朝陆地, 用户"上浮到海面后继续往海里导航, 转头能看到陆地")
        // 地表找液体时若玩家在海洋 biome 中, 随机/有界游荡只会越游越深进海 (海洋里
        // 既无岩浆也无水源, 游过去浪费时间)。改: 先朝最近陆地 XZ 导航 (GoToXZTask),
        // 到陆地后再有界游荡。
        // (2026-09-04 v2 用户"扫描时配合视角转动, 使得陆地满足fov") 扫描前先转头
        // 环顾 (ScanLookBehavior 全景/横扫), 让陆地进入视野再导航 — 像人类转头确认
        // "那边是陆地" 才游过去, 而非透视 biome 数据。
        // (2026-09-04 v3 用户"一次性导航找不到dry point卡在原地") 缓存陆地目标防
        // 每 tick 抖动 (见 _landTarget 注释), 到达后重算下一段。
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD && isPlayerInOcean(mod)) {
            _scanLook.step(mod);
            // 已到缓存目标附近 → 清除重新找 (目标逐段向陆地推进)
            if (_landTarget != null
                    && mod.getPlayer().getBlockPos().getSquaredDistance(_landTarget) < 9) {
                _landTarget = null;
            }
            if (_landTarget == null) {
                _landTarget = findNearestLand(mod);
            }
            if (_landTarget != null) {
                setDebugState("On ocean, heading to land to search " + _liquidName
                        + " -> " + _landTarget.getX() + "," + _landTarget.getZ());
                return new GetToXZTask(_landTarget.getX(), _landTarget.getZ());
            }
            // 全海无陆地: 有界探索兜底 (持久方向, 不原地打转)
            return getSurfaceExploreTask(mod);
        }
        // 不在海洋 (已上岸/内陆): 清除陆地缓存, 恢复有界探索
        _landTarget = null;
        // (2026-09-04 用户"找lava随机方向原地打转") 地表探索用持久 heading 步进
        // 探索替代 TimeoutWanderTask: 每段沿当前方向走 48 格 (GetToXZTask, 到达后
        // 父任务重估, 有液体源就近收 / 无则下一步), 每段仅 ±25° 小抖动不回头,
        // 持续朝大致方向推进, 不再每段重估随机换方向原地打转。
        return getSurfaceExploreTask(mod);
    }

    /**
     * (2026-09-04 用户"坚持大致方向前进, 左右转可以, 不能回头") 计算下一步探索
     * 目标: 沿持久 heading 走 EXPLORE_STEP_DIST 格, 每步 ±EXPLORE_JITTER_DEG 小
     * 角度抖动 (绝不回头)。GetToXZTask 到达后父任务重估 (有液体源就近收 / 无则
     * 下一步继续)。heading 首次随机, 之后只微调。
     * (2026-09-04 v3 用户"遇海洋转向丝滑一点") 计算目标前先探测正前方一段距离
     * 是否大片水域(海/湖面), 是则往一个方向【逐档平滑偏航】(每次向同侧转
     * EXPLORE_TURN_STEP_DEG, 最多 MAX_TURNS 次) 直到前方不再是水域 — 提前绕开
     * 海洋, 不再走到海边/海里才急停折返 (旧行为: 冲进海 → On ocean → findNearest
     * Land 僵硬转向, heading 大跳 + 反复寻路停滞)。
     */
    private Task getSurfaceExploreTask(AltoClef mod) {
        // 粘性: 当前探索任务 active 且未完成 → 返回同一实例, 不每 tick 重算换目标
        // (v2 修复, 见 _exploreTask 注释)。
        if (_exploreTask != null && _exploreTask.isActive() && !_exploreTask.isFinished(mod)) {
            return _exploreTask;
        }
        Vec3d pos = mod.getPlayer().getPos();
        if (Double.isNaN(_exploreHeading)) {
            _exploreHeading = Math.random() * 2 * Math.PI;
        } else {
            // 左右小角度抖动 (保持大致方向, 不回头/不 180° 转向)
            _exploreHeading += (Math.random() * 2 - 1) * Math.toRadians(EXPLORE_JITTER_DEG);
        }
        // 前方水域探测: 正前方 PROBE 处是水/海则向一侧平滑偏航, 直到前方干燥。
        // 转向方向固定一侧(顺时针), 连续转最多 MAX_TURNS 次; 每次仅小幅, 丝滑绕行。
        for (int t = 0; t < MAX_TURNS; t++) {
            int fx = (int) Math.floor(pos.x + Math.cos(_exploreHeading) * EXPLORE_WATER_PROBE);
            int fz = (int) Math.floor(pos.z + Math.sin(_exploreHeading) * EXPLORE_WATER_PROBE);
            BlockPos fp = new BlockPos(fx, pos.getY(), fz);
            if (!isWaterAt(mod, fp)) break; // 前方干燥/未加载 → 该方向可行
            _exploreHeading += Math.toRadians(EXPLORE_TURN_STEP_DEG);
        }
        int x = (int) Math.floor(pos.x + Math.cos(_exploreHeading) * EXPLORE_STEP_DIST);
        int z = (int) Math.floor(pos.z + Math.sin(_exploreHeading) * EXPLORE_STEP_DIST);
        _exploreTask = new GetToXZTask(x, z);
        setDebugState("Exploring for " + _liquidName + " (heading " + (int) Math.toDegrees(_exploreHeading) + "deg) -> " + x + "," + z);
        return _exploreTask;
    }

    /**
     * (2026-09-04 v3) 该 XZ 列顶部是否为水(海面/湖面)。用于前方水域探测: 避免
     * bot 朝大片水域直冲后急停折返。列未加载视为非水(不知道, 信任当前 heading)。
     */
    private boolean isWaterAt(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        for (int y = mod.getWorld().getHeight() - 1; y >= 0; --y) {
            BlockPos check = new BlockPos(target.getX(), y, target.getZ());
            if (!mod.getChunkTracker().isChunkLoaded(check)) return false;
            BlockState state = mod.getWorld().getBlockState(check);
            if (state.isAir()) continue;
            return state.getBlock() == Blocks.WATER;
        }
        return false;
    }

    /**
     * (2026-09-04) 玩家是否"在海洋里需要回岸": 海洋 biome 且【真泡在水里】。
     * (2026-09-04 v2 用户"遇到海洋转向不丝滑思考很久") 原只看 biome — bot 走
     * 在海边陆地/沙滩但 biome 采样为 ocean 时误判"在海洋" → 反复 On ocean →
     * findNearestLand(近处陆地) 与探索分支打架, heading 每段大跳 + 反复寻路停滞
     * (日志 15:16:49-15:17:09 On ocean→land ↔ Exploring 每 1-7s 切, heading
     * 262→299→353)。收紧为真泡水: 岸上(biome ocean 但没泡水)走正常探索, 由
     * getSurfaceExploreTask 前方探测平滑绕海, 不再僵硬折返。
     */
    private boolean isPlayerInOcean(AltoClef mod) {
        if (mod.getPlayer() == null || !WorldHelper.isOcean(mod.getWorld().getBiome(mod.getPlayer().getBlockPos()))) {
            return false;
        }
        if (mod.getPlayer().isTouchingWater()) return true;
        BlockPos feet = mod.getPlayer().getBlockPos();
        return mod.getChunkTracker().isChunkLoaded(feet)
                && mod.getWorld().getBlockState(feet).isOf(Blocks.WATER);
    }

    /** (2026-09-04) 沿 8 方向扫描, 找最近的"非海洋 biome 且列顶部非水"的干燥陆地
     * XZ; 全海/全湿返回 null。
     * (2026-09-04 v2 用户"寻找lava老是定位到水面上") 原只查 biome 非 ocean — biome
     * 是陆地但该 XZ 实际是海面/浅水时, bot GetToXZTask 游到水面停住不上岸 (日志
     * 14:19:28-38 On ocean heading to land -> (216,285)/(228,279) 反复到达水面)。
     * 加 isColumnWater: 只有真正干燥 (列顶部非水) 的陆地才作为上岸目标。 */
    private BlockPos findNearestLand(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int dist = 16; dist <= 128; dist += 16) {
            for (int d = 0; d < 8; d++) {
                double ang = Math.PI / 4.0 * d;
                int x = p.getX() + (int) Math.round(Math.cos(ang) * dist);
                int z = p.getZ() + (int) Math.round(Math.sin(ang) * dist);
                BlockPos cand = new BlockPos(x, p.getY(), z);
                if (mod.getChunkTracker().isChunkLoaded(cand)
                        && !WorldHelper.isOcean(mod.getWorld().getBiome(cand))
                        && !isColumnWater(mod, cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    /** (2026-09-04) 该 XZ 列的顶部 (最高非空气方块) 是否为水 (海面/浅水)。
     * 列未加载视为非水 (不知道, 先信任 biome)。 */
    private boolean isColumnWater(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        for (int y = mod.getWorld().getHeight() - 1; y >= 0; --y) {
            BlockPos check = new BlockPos(target.getX(), y, target.getZ());
            if (!mod.getChunkTracker().isChunkLoaded(check)) return false;
            BlockState state = mod.getWorld().getBlockState(check);
            if (state.isAir()) continue;
            return state.getBlock() == Blocks.WATER;
        }
        return false;
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
