package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.bridge.NamedLandmarks;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.LandHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.SurfaceHelper;
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
import net.minecraft.util.registry.Registry;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    // (2026-09-03 用户: 找水/岩浆先回地表) 地下(旧口径 y<58, 20260916 改为"未见天",
    // 见 isBelowSurface) 随机游荡找水几乎无果, 且持续探入未加载区块 → 地形生成 +
    // 路径搜索把帧率拖到 100ms+ (altoclef_jank.log 实锤 "Wander for Infinity blocks"
    // 期间 frameMs=102~146)。地表河流/湖/海找水概率高, 地表也常有裸露岩浆池; 回地表
    // 路上 BlockTracker 近区扫描会顺路发现液体源。到地表后用持久 heading 步进探索
    // (见 getSurfaceExploreTask, 2026-09-04 替代原无限/有界游荡, 坚持方向不原地打转)。

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
    // (2026-09-05 记忆回舀日志节流, 原 [LavaMemory] 每 tick 一条 CHAT 一秒 16 条)
    private final TimerGame _memoryLogTimer = new TimerGame(5);

    // (2026-09-04 溯源任务实例, 用户"看到水流应定位水源") 持有当前溯源任务引用以读取
    // 失败状态; 溯源失败(gaveUp)时把起始流动水加入溯源黑名单, 避免重试同一处。
    private TraceFlowingWaterToSourceTask _traceTask = null;
    // (2026-09-13 v25) 溯源失败后的重试冷却 (static: 实例频繁重建会被绕过)。
    private static long _traceRetryCooldownUntilMs = 0;

    // (2026-09-17 黑名单口径拆分 —— "看到水源却没找"的现场病根之一)
    // 原来 _blacklist 一份两用: ①舀/破坏失败的液体格 ②溯源失败的起点流动水。
    // 两者语义不同却共用一个集合, 而 isSourceLiquid 会查它 → 溯源失败把起点拉黑后,
    // 【眼前那格真的是源的水】在后继判定里被永久忽略。现场 20260917-220709:
    // 22:15:52 溯源放弃并拉黑起点, 此后 if 分支每 tick 打 "No flowing water
    // found in BlockTracker either", 而水就在玩家正前方 1 格。
    // 拆法: _blacklist 保留"操作不了"(仍参与 isSourceLiquid);
    //       _traceBlacklist 只装"这条链走过了", 只参与 isFlowingWater, 且带 TTL。
    // static: 本任务实例被框架频繁重建, 实例层集合会被绕过 (铁律, 同 v25 冷却)。
    private static final Map<BlockPos, Long> _traceBlacklist = new HashMap<>();
    private static final long TRACE_BLACKLIST_TTL_MS = 60_000;

    // (2026-09-17) "No flowing water found" 诊断行节流 —— 原来该行在 2s 节流块之外,
    // 每 tick 一条 (现场 22:15:53-56 打了 200+ 条), 既刷屏又拖帧。
    private final TimerGame _noFlowDiagTimer = new TimerGame(5);

    // (2026-09-17) 判据拒绝原因台账 (原因 -> 2s 窗口内次数)。回答"眼前的液体为什么
    // 不被认": 黑名单/不可见/不可达/非源, 四选一。static + 2s 清零: 实例重建不丢中期数据。
    private static final Map<String, Integer> _rejectStats = new HashMap<>();

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

    // (2026-09-23 岩浆湖记忆导航) 地表/下界找不到可直接舀的源时, 优先导航到
    // KnowledgeMemory 里亲眼见过并落盘的岩浆湖锚点, 替代无方向 wander ("以后
    // 需要岩浆时直接用记住的湖")。粘性子任务 (同 _exploreTask 铁律: 每 tick
    // 新建实例会导致目标每 tick 换、baritone 永远在重算)。
    private GetToXZTask _lakeNavTask = null;
    private BlockPos _lakeNavTarget = null;
    // 到达/导航结束的湖锚点短期拉黑, 防"到达→附近找不到源→再选同一个"死循环。
    // static + TTL: 本任务实例会被框架频繁重建, 实例层冷却会被绕过 (铁律,
    // 同 _traceBlacklist)。
    private static final Map<BlockPos, Long> _lakeBlacklist = new HashMap<>();
    private static final long LAKE_BLACKLIST_TTL_MS = 120_000;
    // 到达判定: XZ 6 格内视为已到湖边 —— trackBlock(LAVA) 近区扫描自会接管找
    // 源, 不需要踩到锚点头上。
    private static final double LAKE_NAV_ARRIVE_XZ = 6.0;
    // (2026-10-01 用户拍板) 选锚点的 |dy| 上限。导航是 GetToXZTask 只走 XZ,
    // 深井锚点 (洞穴岩浆湖, 231949 场 y=-55) 地表走到头顶也够不着, 每个白烧
    // 30s 且 TTL 过期后重选 —— 地表舀岩浆只认可达锚点, 深井湖留给未来
    // "挖矿取岩浆"类任务。
    private static final int LAKE_MAX_DY = 24;

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
        // (2026-09-17 判据统一) 原来这里重置本类私有的静态模式锁存 (_modeInit/_flapRejected)。
        // 判据搬到 SurfaceHelper 后【故意不再重置】: 那份迟滞是全局事实(所有调用方共看
        // 一个答案), 而本任务实例每 tick 都可能重建 —— 在 onStart 里清它等于每 tick 把
        // 全局迟滞清零, 迟滞直接失效。跨存档复位由 WorldStateGuard 统一负责。

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

        // (2026-10-15 兜底自守 — 岩浆桶×8 事故) 数量已满足就不再舀。本任务被父链
        // 反复派发时 (框架不消费子任务 isFinished, detail/01), 旧版舀取肢每 tick 照跑:
        // 20261015-153843 实证 99 次舀取 vs 2 次浇铸, 背包攒 8 个岩浆桶。
        // 判据与 isFinished (ResourceTask POSSESS 真实扫描) 同一份 —— 判定源单一化;
        // 实时无缓存, 不存在"判断过期" (父链缓存层 hasItem 的 500ms 窗口期间本任务
        // 最多空转 1-2 tick)。出口: 桶到手后各调用方闸门自然翻转替换本任务
        // (ConstructNetherPortalBucketTask 兜底分支改用真实持有闸 / PlaceObsidianBucketTask
        // 的 hasItem 门 ≤500ms 翻转); 期间显式释放拾取闩 (interactionPaused), 防按住悬空 (铁律②)。
        if (Arrays.stream(_itemTargets).allMatch(target ->
                StorageHelper.itemTargetOwned(mod, StorageHelper.Ownership.POSSESS, target))) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _progressChecker.reset();
            setDebugState("Already have enough " + _liquidName + " bucket, idling (no more scooping)");
            return null;
        }

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
        // (2026-10-07 口径注释) 本口径 = "本液体任务自需的桶容": 空桶 + 本液体已装桶,
        // 【故意】不算另一形态已装桶 (水桶舀不了岩浆) —— 与 ConstructNetherPortalBucketTask
        // 的三形态总预算 (WorldHelper.countTotalBuckets) 是两层不同的判据, 不合并。
        // ⚠️ 已知残留: 若包里堆了超额的另一形态已装桶且无空桶, 本任务会再合成新桶
        // (现场 20261007-171943 实锤攒到 4 桶) —— 真修复需要"倒掉超额水桶"机制, 待立项。
        int bucketsNeeded = _count - mod.getItemStorage().getItemCount(Items.BUCKET) - mod.getItemStorage().getItemCount(_target);
        if (bucketsNeeded > 0) {
            setDebugState("Getting bucket...");
            return TaskCatalogue.getItemTask(Items.BUCKET, bucketsNeeded);
        }

        // (2026-09-17 判据与诊断同源) 谓词本体搬到 sourceRejectReason(): 返回拒绝原因
        // 或 null(=接受)。谓词与 [Diag-water] 诊断复用同一份判据 —— 判据只有一份,
        // 杜绝"诊断口径与决策口径不一致"这条本项目最高频根因 (detail/03-consistency.md)。
        Predicate<BlockPos> isSourceLiquid = blockPos -> sourceRejectReason(mod, blockPos) == null;

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
            Debug.logMessage("[Diag-water] totalWater=%d, source=%d, flowing=%d, canReach=%s reject=%s",
                    totalWaterKnown, sourceWaterKnown, flowingWaterKnown,
                    totalWaterKnown > 0 ? "checking..." : "NONE", drainRejectSummary());
            // (2026-09-17 v2) 逐格报告: 距玩家最近 3 个已知液体坐标 + 各自的拒绝原因 ——
            // 直接回答"眼前这格水/岩浆为什么不算数"(黑名单/不可见/不可达/非源)。
            Debug.logMessage("[Diag-water] nearest: %s", nearestWaterReport(mod));
            // (2026-09-17 回地表闸门明细, 用户"找水不是应该触发回地表吗")
            // 现场 20260917-231306: 玩家 y=-45 在洞里, 却落到 "Searching for liquid
            // by wandering around aimlessly" + [Diag-flow] deep=false → 回地表分支
            // (isBelowSurface) 判了 false, 而 [Surface] 翻转日志一条都没有 (结论从未变过)。
            // 这里把判据的中间量全部打出来, 定位是"埋深算错"还是"迟滞锁死":
            //   below/raw = 迟滞结论 / 原始结论(两者不同 = 卡在 5s 驻留窗口或静态锁死)
            //   buried    = 5 点采样的中位数 = getTopY(MOTION_BLOCKING_NO_LEAVES) - (y+1)
            //   oh[]      = 5 点原始值 (下标 0=自身列, 其余 东南西北)
            //   topY      = 自身列高度图读数 (返回世界底部 = 该 chunk 高度图缺失, 即根因)
            var pb = mod.getPlayer().getBlockPos();
            Debug.logMessage("[Diag-water] surfaceGate: below=%s raw=%s buried=%d oh=%s topY=%d y=%d dim=%s",
                    SurfaceHelper.isBelowSurfaceDebounced(mod), SurfaceHelper.isBelowSurface(mod),
                    SurfaceHelper.buriedDepth(mod),
                    java.util.Arrays.toString(SurfaceHelper.overheadSamples(mod)),
                    mod.getWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                            pb.getX(), pb.getZ()),
                    pb.getY(), WorldHelper.getCurrentDimension());
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
                    // (2026-09-17 黑名单拆口径) 只进溯源黑名单 (60s TTL), 不再写
                    // isSourceLiquid 的 _blacklist —— 否则眼前"真的是源"的那格水
                    // 会被永久忽略 (现场 20260917-220709: 拉黑后每 tick 打
                    // "No flowing water found", 而水就在玩家正前方 1 格)。
                    _traceBlacklist.put(_traceTask.getStartPos(),
                            System.currentTimeMillis() + TRACE_BLACKLIST_TTL_MS);
                    Debug.logMessage("[Diag-water] trace failed, blacklisting start for tracing only (60s) "
                            + _traceTask.getStartPos().toShortString());
                }
                // (2026-09-13 v25) 失败即给全局溯源冷却, 防"new→秒放弃→再new"刷屏。
                if (_traceTask.gaveUp()) {
                    _traceRetryCooldownUntilMs = System.currentTimeMillis() + 15_000;
                }
                _traceTask = null;
            }
            // (2026-09-17) 同 sourceRejectReason: 判据本体搬进 flowingRejectReason,
            // 谓词与诊断共用一份; 黑名单改用 _traceBlacklist (见字段区注释)。
            Predicate<BlockPos> isFlowingWater = blockPos -> flowingRejectReason(mod, blockPos) == null;

            if (_traceTask == null && mod.getBlockTracker().anyFound(isFlowingWater, Blocks.WATER)
                    // (2026-09-13 v25 防重建刷屏, 20260913-091045 实锤: "giving up
                    // after 0 attempts" 每 50ms 连刷) 溯源放弃后 BlockTracker 里
                    // 水流记忆仍在 → 下一 tick 立即 new 新实例 → 起点=null → 0
                    // attempts 放弃 → 再 new, 无冷却死循环。冷却放 static (铁律:
                    // 实例频繁重建, 实例层冷却会被绕过), 放弃后 15s 内不再发起。
                    && System.currentTimeMillis() >= _traceRetryCooldownUntilMs) {
                // 找到流动水，追踪到源头
                setDebugState("Found flowing water, tracing to source...");
                _traceTask = new TraceFlowingWaterToSourceTask(isFlowingWater);
            }
            if (_traceTask != null) {
                return _traceTask;
            }
            // (2026-09-17 节流) 原每 tick 一条 (现场 22:15:53-56 打了 200+ 条)。
            if (_noFlowDiagTimer.elapsed()) {
                _noFlowDiagTimer.reset();
                Debug.logMessage("[Diag-water] No flowing water found in BlockTracker either | "
                        + drainRejectSummary() + " | nearest: " + nearestWaterReport(mod));
            }
        }

        // Find nearest water and right click it
        // (2026-09-05 记忆候选合并, "造门到一半走人"修复) 感知列表 (getKnownLocations)
        // 有重扫周期/上限: 实测 11:28:38 重扫瞬间池子被挤出列表 → anyFound 突然 false
        // → "Exploring for lava" 越走越远, 即便"见过即记忆"表里有池子 —— 因为原逻辑
        // 只对【感知列表里的坐标】查记忆, 列表空了记忆根本没机会被查询。
        // 现在: 门外层合并评估; DoToClosestBlockTask 候选也合并记忆坐标。
        boolean knownFound = mod.getBlockTracker().anyFound(isSourceLiquid, _toCollect);
        BlockPos rememberedBest = null;
        double rememberedBestSq = Double.POSITIVE_INFINITY;
        if (!knownFound) {
            Vec3d playerPos = mod.getPlayer().getPos();
            for (BlockPos remembered : VisibilityHelper.getRememberedValuable(mod, _toCollect)) {
                if (!isSourceLiquid.test(remembered)) continue;
                double sq = remembered.getSquaredDistance(playerPos);
                if (sq < rememberedBestSq) {
                    rememberedBestSq = sq;
                    rememberedBest = remembered;
                }
            }
        }
        if (knownFound || rememberedBest != null) {
            // We want to MINIMIZE this distance to liquid.
            setDebugState("Trying to collect...");
            //Debug.logMessage("TEST: " + RayTraceUtils.fluidHandling);
            // (2026-09-05 节流) 记忆回舀激活期间每 tick 一条 CHAT 会刷屏掉帧
            // (实测 11:38:41 一秒 16 条), 节流到 5s 一条。
            if (rememberedBest != null && _memoryLogTimer.elapsed()) {
                _memoryLogTimer.reset();
                Debug.logMessage("[LavaMemory] 感知列表空, 凭记忆回舀 " + rememberedBest.toShortString());
            }

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
            }, (Vec3d closestPos) -> {
                // (2026-09-05) 候选合并: 感知列表与"见过即记忆"表【统一取最近】,
                // 消除"感知列表空→记忆源 / 重扫恢复→感知源"来回切目标造成的
                // 走开又折返 (用户实测"走开一下马上回来, 任务不稳定")。
                BlockPos best = null;
                double bestSq = Double.POSITIVE_INFINITY;
                Optional<BlockPos> known = mod.getBlockTracker().getNearestTracking(closestPos, isSourceLiquid, _toCollect);
                if (known.isPresent()) {
                    best = known.get();
                    bestSq = best.getSquaredDistance(closestPos);
                }
                for (BlockPos remembered : VisibilityHelper.getRememberedValuable(mod, _toCollect)) {
                    if (!isSourceLiquid.test(remembered)) continue;
                    double sq = remembered.getSquaredDistance(closestPos);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = remembered;
                    }
                }
                return Optional.ofNullable(best);
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
        if (isBelowSurface(mod)) {
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
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD && LandHelper.isPlayerInOcean(mod)) {
            _scanLook.step(mod);
            // 已到缓存目标附近 → 清除重新找 (目标逐段向陆地推进)
            if (_landTarget != null
                    && mod.getPlayer().getBlockPos().getSquaredDistance(_landTarget) < 9) {
                _landTarget = null;
            }
            if (_landTarget == null) {
                _landTarget = LandHelper.findNearestLand(mod);
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
        // (2026-09-23 岩浆湖记忆) 到了地表/在下界仍无可直接舀的源: 若记忆里有
        // 亲眼见过并跨会话记住的岩浆湖, 直接导航过去, 不再无方向 wander。仅岩浆
        // 走此路 (水没有"湖记忆")。地下场景已被上面的 GoToSurfaceTask 分支接走。
        if (_toCollect == Blocks.LAVA) {
            Task lakeTask = getRememberedLakeTask(mod);
            if (lakeTask != null) return lakeTask;
        }
        // (2026-09-04 用户"找lava随机方向原地打转") 地表探索用持久 heading 步进
        // 探索替代 TimeoutWanderTask: 每段沿当前方向走 48 格 (GetToXZTask, 到达后
        // 父任务重估, 有液体源就近收 / 无则下一步), 每段仅 ±25° 小抖动不回头,
        // 持续朝大致方向推进, 不再每段重估随机换方向原地打转。
        return getSurfaceExploreTask(mod);
    }

    /**
     * (2026-09-23 岩浆湖记忆) 取 KnowledgeMemory 中最近的 LAVA_LAKE 锚点并粘性
     * 导航过去 (跨会话持久化, 无距离上限 —— 与找堡垒同款"记得就奔过去"语义)。
     * (2026-10-01 用户拍板) |dy| > {@value #LAKE_MAX_DY} 的深井锚点跳过:
     * 导航是 XZ 口径, 洞穴湖地表走到头顶也够不着 (231949 场 y=-55 湖白烧)。
     * 返回 null = 没有可用记忆, 交回外层探索。到达 XZ {@value #LAKE_NAV_ARRIVE_XZ}
     * 格 (近区 trackBlock 扫描会接管找源), 或导航子任务自行结束, 就把锚点拉黑
     * {@value #LAKE_BLACKLIST_TTL_MS}ms 并重选, 防同一湖死循环。
     * d= 打印为水平距离 (与 GetToXZTask 口径一致)。
     */
    private Task getRememberedLakeTask(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        if (_lakeNavTask != null) {
            boolean close = _lakeNavTarget != null
                    && horizontalSq(feet, _lakeNavTarget) <= LAKE_NAV_ARRIVE_XZ * LAKE_NAV_ARRIVE_XZ;
            if (!close && _lakeNavTask.isActive() && !_lakeNavTask.isFinished(mod)) {
                setDebugState("Navigating to remembered lava lake -> "
                        + _lakeNavTarget.getX() + "," + _lakeNavTarget.getZ());
                return _lakeNavTask;
            }
            // 到达 或 导航结束: 近区若有源, 外层前面的收集分支下一 tick 会接走;
            // 没有则拉黑本锚点重选, 不卡在同一处。
            if (_lakeNavTarget != null) {
                _lakeBlacklist.put(_lakeNavTarget.toImmutable(),
                        System.currentTimeMillis() + LAKE_BLACKLIST_TTL_MS);
                Debug.logMessage("[LavaMemory] 湖锚点导航结束, 短期拉黑重估 " + _lakeNavTarget.toShortString());
            }
            _lakeNavTask = null;
            _lakeNavTarget = null;
        }
        long now = System.currentTimeMillis();
        _lakeBlacklist.entrySet().removeIf(e -> now > e.getValue());
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        int deepSkipped = 0;
        // (2026-10-01 用户拍板) |dy| > LAKE_MAX_DY 的深井锚点跳过 —— XZ 导航
        // 到不了 (见 LAKE_MAX_DY 注释)。d= 同步改为水平距离 (旧值含无效 Y 分量,
        // y=-55 的锚点打出 d=180 假近)。
        // (2026-10-05 双源线索召回) 自动记忆中心 ∪ NamedLandmarks 带类型的共享
        // 地标（含对端转来的 shared: 坐标）。假坐标最坏 = 导航白跑一趟, 下方锚点
        // 拉黑 + |dy| 闸兜底; 到场亲眼看见后由感知管道自动晋升进 KnowledgeMemory。
        for (BlockPos center : NamedLandmarks.recallClueCenters(mod,
                adris.altoclef.trackers.KnowledgeMemory.StructureType.LAVA_LAKE,
                mod.getPlayer().getPos(), 1.0e7)) {
            if (_lakeBlacklist.containsKey(center)) continue;
            if (Math.abs(center.getY() - feet.getY()) > LAKE_MAX_DY) {
                deepSkipped++;
                continue;
            }
            double sq = horizontalSq(center, feet);
            if (sq < bestSq) {
                bestSq = sq;
                best = center;
            }
        }
        if (best == null) {
            if (deepSkipped > 0) {
                Debug.logMessage("[LavaMemory] " + deepSkipped + " 个记忆锚点 |dy|>"
                        + LAKE_MAX_DY + " 跳过 (XZ 地表导航到不了深井湖)");
            }
            return null;
        }
        _lakeNavTarget = best.toImmutable();
        _lakeNavTask = new GetToXZTask(best.getX(), best.getZ());
        setDebugState("Heading to remembered lava lake -> " + best.getX() + "," + best.getZ()
                + " (d=" + (int) Math.sqrt(bestSq) + ")");
        Debug.logMessage("[LavaMemory] 凭记忆前往岩浆湖 " + best.toShortString()
                + " (d=" + (int) Math.sqrt(bestSq) + ")");
        return _lakeNavTask;
    }

    /** XZ 水平距离平方 (忽略高度, GetToXZTask/GoalXZ 只认 XZ)。 */
    private static double horizontalSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
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
            if (!LandHelper.isColumnWater(mod, fp)) break; // 前方干燥/未加载 → 该方向可行
            _exploreHeading += Math.toRadians(EXPLORE_TURN_STEP_DEG);
        }
        int x = (int) Math.floor(pos.x + Math.cos(_exploreHeading) * EXPLORE_STEP_DIST);
        int z = (int) Math.floor(pos.z + Math.sin(_exploreHeading) * EXPLORE_STEP_DIST);
        _exploreTask = new GetToXZTask(x, z);
        setDebugState("Exploring for " + _liquidName + " (heading " + (int) Math.toDegrees(_exploreHeading) + "deg) -> " + x + "," + z);
        return _exploreTask;
    }

    // (2026-09-11) isWaterAt / isColumnWater / isPlayerInOcean / findNearestLand
    // 四个私有方法已抽出到 LandHelper (两者逐行等价), 供放置类任务共用。

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
     * (2026-09-03) 是否属于"深地下应回地表找液体"的场景。下界/末地不算 —
     * 回地表语义只对主世界有意义 (下界 y<58 也满是岩浆, 无需回任何"地表")。
     * <p>
     * (2026-09-13 v26 判据对齐, 20260913-093823 实锤) 旧口径只有 y<58, 与
     * GoToSurfaceTask 的完工判据 (y<58【或】头顶无天空视线) 不一致 → 玩家在
     * y58~真实地表之间 (矿洞里) 时: 本任务判"不深了, 去 explore 找水" ↔ 回地表
     * 任务判"还没见天, 继续爬" → 两任务 1-2s 互抢 (09:39:56/58/09:40:00 三秒内
     * 三次 SUB 交替, 用户"卡死在 y=58")。对齐为同口径: 未见天就当仍需回地表。
     * <p>
     * (2026-09-16 修翻面振荡) 废除 y<58 短路, 只以"未见天"为准 —— 露天山谷
     * y=57 在地表却被 y<58 误判地下, 加上 1 格宽阈值 + 跳节点 y 震荡, 分支
     * 1-10s/轮互翻 (20260916-1128 场次 11:27:59 单秒三翻面) → 原地不动。
     */
    // (20260916 v2 → 20260917 判据统一) 本处曾是全项目"最智能"的一份地表判据
    // (5 点多数投票 + 5s 迟滞)，现已抽到 SurfaceHelper 作为全项目唯一实现。
    // 演进史 (别再往回改):
    //  ① 纯 y<58 短路: 露天山谷 y=57 在地表却被误判地下 → 反复爬向山顶;
    //    且 1 格宽阈值 + baritone 跳节点 y 在 57↔58 震荡 → 分支每 1-10s 互翻
    //    (20260916-1128 场次 11:27:59 单秒三翻面), 每翻一次子树 STOP 重建、
    //    48 格探索腿报废 (实测相邻目标仅差 5-14 格) → bot 原地不动。
    //  ② v2 单点 isSkyVisible(pos.up()) + 5s 迟滞: 仍抖, rejectedFlaps 高达
    //    90+/5s ≈ 每 tick 抖一次 (水面漂浮 bobbing 时 pos.up() 交替水/空气,
    //    高度图把水算遮挡)。
    //  ③ v3 (现行) 5 点采样 + ≥3 多数投票 + 5s 驻留, 见 SurfaceHelper。
    // 日志标签 [LiquidSearch] mode -> 并入统一的 [Surface] mode ->
    // (字段更全: y / covered=n/5 / dim / rejectedFlaps)。
    private boolean isBelowSurface(AltoClef mod) {
        return SurfaceHelper.isBelowSurfaceDebounced(mod);
    }

    // ===== (2026-09-17 判据本体 + 诊断: 一份判据两处用) =====

    /**
     * 返回"不接受这个液体格"的原因; null = 接受。判据语义与原内联谓词逐条一致
     * (只把 return false 换成原因字符串):
     * ①黑名单: 舀/破坏失败过的位置不再试;
     * ②不可感知 (2026-09-03 用户原则): 不满足 FOV/LOS 的不评估可达性, 且不限制距离
     *   —— 看不到的液体(墙后/未暴露表面)直接排除; 但 (2026-09-04 见过即记忆) 真感知
     *   过一次的源进长期记忆表, 此刻看不见也能凭记忆走回去舀 (人记得池子在哪);
     * ③不可达: BlockTracker 标记 unreachable —— 水格与其上方一格都要可达 (得站上去舀);
     * ④岩浆额外要求上方可破坏 (要挖开顶盖);
     * ⑤必须真的是源 (level==8 且上方不是液体)。
     * <p>
     * 谓词与诊断共用这一份: 本项目最高频根因就是"两个口径不一致"
     * (detail/03-consistency.md), 诊断再写一遍判据只会制造第 N+1 份口径。
     */
    private String sourceRejectReason(AltoClef mod, BlockPos pos) {
        return sourceRejectReason(mod, pos, true);
    }

    /** @param count 是否计入拒绝台账 (诊断自身的探查性调用传 false, 避免污染统计)。 */
    private String sourceRejectReason(AltoClef mod, BlockPos pos, boolean count) {
        if (_blacklist.contains(pos)) return reject("blacklist", count);
        if (!VisibilityHelper.isBlockPerceivable(mod, pos)
                && !VisibilityHelper.isValuableRemembered(mod, _toCollect, pos)) return reject("blind", count);
        if (!WorldHelper.canReach(mod, pos)) return reject("unreachable", count);
        if (!WorldHelper.canReach(mod, pos.up())) return reject("unreachableUp", count);
        if (_toCollect == Blocks.LAVA && !WorldHelper.canBreak(mod, pos.up()))
            return reject("cannotBreakAbove", count);
        if (!WorldHelper.isSourceBlock(mod, pos, false)) return reject("notSource", count);
        return null;
    }

    /**
     * 溯源专用: 返回"不接受这个流动水格"的原因; null = 接受。
     * 黑名单用 _traceBlacklist (只挡重复溯源), 不查 _blacklist 的"操作不了"语义。
     */
    private String flowingRejectReason(AltoClef mod, BlockPos pos) {
        return flowingRejectReason(mod, pos, true);
    }

    private String flowingRejectReason(AltoClef mod, BlockPos pos, boolean count) {
        if (traceBlacklisted(pos)) return reject("traceBlacklist", count);
        // (2026-09-03 用户原则) 不满足 FOV/LOS 的不评估可达性。
        if (!VisibilityHelper.isBlockPerceivable(mod, pos)) return reject("blind", count);
        if (!WorldHelper.canReach(mod, pos)) return reject("unreachable", count);
        var world = MinecraftClient.getInstance().world;
        if (world == null) return reject("noWorld", count);
        var state = world.getBlockState(pos);
        if (state.getBlock() != _toCollect || state.getFluidState().isStill())
            return reject("notFlowing", count);
        return null;
    }

    private static String reject(String reason, boolean count) {
        if (count) {
            synchronized (_rejectStats) {
                _rejectStats.merge(reason, 1, Integer::sum);
            }
        }
        return reason;
    }

    /** (2026-09-17) 溯源黑名单查询 —— 带 60s TTL, 过期即忘 (不让一次失败永久闭嘴)。 */
    private static boolean traceBlacklisted(BlockPos pos) {
        Long until = _traceBlacklist.get(pos);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            _traceBlacklist.remove(pos);
            return false;
        }
        return true;
    }

    /** 打出并清空判据拒绝台账 (按次数降序)。 */
    private static String drainRejectSummary() {
        synchronized (_rejectStats) {
            if (_rejectStats.isEmpty()) return "(no calls)";
            StringBuilder sb = new StringBuilder();
            _rejectStats.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(sb.length() == 0 ? "" : ",")
                            .append(e.getKey()).append('=').append(e.getValue()));
            _rejectStats.clear();
            return sb.toString();
        }
    }

    /**
     * (2026-09-17 诊断) 距玩家最近的 3 个已知液体格 + 逐格拒绝原因 —— 直接回答
     * "眼前那格水/岩浆为什么不算数"。探查用 count=false, 不污染台账。
     */
    private String nearestWaterReport(AltoClef mod) {
        Vec3d playerPos = mod.getPlayer().getPos();
        List<BlockPos> known = new ArrayList<>(mod.getBlockTracker().getKnownLocations(_toCollect));
        known.sort(Comparator.comparingDouble((BlockPos p) -> p.getSquaredDistance(playerPos)));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (BlockPos pos : known) {
            if (shown >= 3) break;
            var world = MinecraftClient.getInstance().world;
            if (world == null) break;
            var fluid = world.getBlockState(pos).getFluidState();
            String src = sourceRejectReason(mod, pos, false);
            String flow = flowingRejectReason(mod, pos, false);
            // (2026-09-17 v2, 20260917-225345 场: 最近的水 3.2 格却 src=blind)
            // blind 只是"不可感知"的总称, 定位不到是哪一条子判据; 这里补上直接证据:
            //   above = 水格正上方是什么方块 (perceiveSourceInner 的流体分支要求它必须是
            //           空气/洞穴空气, 否则直接判不可感知 —— 防隔岩壁透视的前置闸门);
            //   perc  = VisibilityHelper.perceiveSource 的感知来源标签
            //           (los/fluid_los/fluid_surface → 真视线; null → 不可感知)。
            // 两者合起来一眼区分"上方被岩石压着"和"LOS 被挡"。
            sb.append(shown == 0 ? "" : " | ")
                    .append(pos.toShortString())
                    .append(" d=").append(String.format("%.1f", Math.sqrt(pos.getSquaredDistance(playerPos))))
                    .append(" lvl=").append(fluid.getLevel())
                    .append(" still=").append(fluid.isStill())
                    .append(" above=").append(describeAbove(mod, pos))
                    .append(" perc=").append(String.valueOf(VisibilityHelper.perceiveSource(mod, pos)))
                    .append(" src=").append(src == null ? "OK" : src)
                    .append(" flow=").append(flow == null ? "OK" : flow);
            shown++;
        }
        return shown == 0 ? "(no known " + _toCollect + ")" : sb.toString();
    }

    /** 水格正上方那个方块的简名 (流体分支的第一道硬闸门)。 */
    private static String describeAbove(AltoClef mod, BlockPos pos) {
        var world = MinecraftClient.getInstance().world;
        if (world == null) return "?";
        var above = world.getBlockState(pos.up());
        String name = String.valueOf(Registry.BLOCK.getId(above.getBlock()));
        return above.isAir() ? name + "(air)" : name;
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
