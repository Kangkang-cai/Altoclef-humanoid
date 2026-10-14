package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.tasks.movement.GetToLandTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LandHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Place a type of block nearby, anywhere.
 *
 * Also known as the "bear strats" task.
 */
public class PlaceBlockNearbyTask extends Task {

    private final Block[] _toPlace;

    // (2026-09-12 观感) 站住不动容忍 6s→2s。现场 20260912-084704: 这条才是日志里
    // 实际触发换位的 checker (快照 L3 "Wandering, will try to place again later."
    // + L4 Wander for 2.0 blocks, 08:48:20.6), 与 PlaceBlockTask 的 checker 同源
    // 6s 叠加 → 放置失败要等 6s 才动。移动中 distance 有进展不触发。
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(2, 0.1, 0.5, 0.001, 1);

    // (2026-09-28 让位超时硬上限) 现场 20260928-091258 实锤: 判负后返回 _wander 进
    // 「Wandering, will try to place again later.」分支 (下方 L182), 该分支唯一的
    // 出口是 `_wander.isFinished()` = 走满 2 格, 而 baritone 在那个窄通道
    // `pathing=false pathPresent=false` (anomaly.log 原文) ⇒ bot 89 秒零位移
    // (SWING-BREAK 三次采样坐标 15 位小数完全一致), 同时该分支每 tick
    // `_progressChecker.reset()` 保证自己永不再判负 ⇒ 僵尸态永不自愈。
    // _wander 自带的 `_failCounter > 10` 逃生门也没打开 —— 它全程只判负过一次
    // (diag.log 09:21:20.907), 此后 check() 转入挖掘分支, 距离表每 tick reset。
    // 这里补墙钟硬上限: 让位超过 WANDER_GIVEUP_MS 仍无位移 ⇒ 丢弃本轮 wander
    // (换新实例 → isActive() 归假), 本拍落到下方重新选点重试, 并留下日志。
    private static final long WANDER_GIVEUP_MS = 8000;
    private TimeoutWanderTask _wander = new TimeoutWanderTask(2);
    /** 本轮让位的起始墙钟 (ms); 0 = 未开始计时。 */
    private long _wanderSinceMs = 0;

    // (2026-09-28 快路径假成功根修) 上一拍"眼前直接放"(getCurrentlyLookingBlockPlace +
    // place) 的右键落点, 需下一拍核实 —— 客户端世界更新滞后一拍。null = 无待核实。
    private BlockPos _fastAttempt = null;
    // (2026-09-28 快路径假成功根修) 已证明"右键只会开容器界面、放不出方块"的落点。
    // 必须持久记住而不是只跳过一拍: 界面一被 closeScreen 关掉, 隔一拍准星就恢复,
    // 若不清空/不记住就会再点一次 ⇒ 界面照样反复开合(现场 syncId 4→16 每 300ms 一轮)。
    // 该落点交给下面的 baritone 路径(它能对着容器侧面/顶面正常放置)。
    private BlockPos _fastBlockedCell = null;

    // (2026-09-29 陈旧选点距离闸) locateClosePlacePos 的搜索半径是 7 格(对角
    // ~12 格), 本任务实例又被 DoStuffInContainerTask 长期缓存复用 —— 上次放置
    // 会话留下的 _tryPlace 在 bot 远走数百格后仍存活, 而 WorldHelper.canReach
    // 是"黑名单取反"口径, 远位置没被拉黑就恒"可达" ⇒ 陈旧点位永不失效。
    // 现场 20260929-134354: bot 在 864,107,-104 冻结, 放置任务却对着 386 格外
    // 旧炉位 (478,118,-133) 反复 "Trying to place"。超过本闸的点位一律视为陈旧。
    private static final double STALE_PLACE_RANGE = 16.0;

    // (2026-09-11) 「放不下就先上岸」用的导航任务 (缓存实例, 避免每 tick 重建丢进度)。
    private GetToLandTask _toLand = null;
    // (v3) 回岸模式的锁存位: 一旦进入就不再每 tick 重估 isTouchingWater (水面浮动会闪烁)。
    private boolean _landActive = false;

    private final TimerGame _randomlookTimer = new TimerGame(0.25);
    private final Predicate<BlockPos> _canPlaceHere;
    private BlockPos _justPlaced; // Where we JUST placed a block.
    private BlockPos _tryPlace;   // Where we should TRY placing a block.
    // Oof, necesarry for the onBlockPlaced action.
    private AltoClef _mod;
    private Subscription<BlockPlaceEvent> _onBlockPlaced;

    public PlaceBlockNearbyTask(Predicate<BlockPos> canPlaceHere, Block... toPlace) {
        _toPlace = toPlace;
        _canPlaceHere = canPlaceHere;
    }

    public PlaceBlockNearbyTask(Block... toPlace) {
        this(blockPos -> true, toPlace);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _mod = mod;

        // (2026-09-28 time-base 修复 —— 本次卡死的触发源) 本任务是
        // DoStuffInContainerTask 构造时创建、并长期复用的缓存实例
        // (DoStuffInContainerTask:120 `_placeTask = new PlaceBlockNearbyTask(...)`,
        //  :161/:302 反复 return 同一实例), 而 `_progressChecker` 的计时器是从
        // **实例构造那一刻**起算的。onStart 原先不重置它, 于是:
        //   实例构造 > timeout(2s) 之后才第一次走到 checker 时,
        //   LinearProgressChecker.setProgress 的 `_first` 分支先把 _lastProgress
        //   赋成当前值 (LinearProgressChecker:34-37) ⇒ improvement ≡ 0
        //   < minProgress(0.1) ⇒ **必然判负**, 与 bot 有没有移动无关。
        //
        // 现场 20260928-091258 diag.log 铁证:
        //   09:21:20.907 [TIMEOUT] site=LinearProgressChecker task=PlaceBlockNearbyTask
        //                timeout=2.0s minProgress=0.1 actual=0.0
        //   09:21:20.907 [TIMEOUT] site=ProgressCheckerRetry task=PlaceBlockNearbyTask
        //                attempts=1 exhausted
        // 判负发生在 Place structure{crafting_table} 启动后仅 49ms (1 tick) —— baritone
        // 连一次放置机会都没拿到就进了让位分支。同一处判负在本会话出现 5 次
        // (09:14:54.249 / 09:15:55.450 / 09:15:56.402 / 09:20:47.256 / 09:21:20.907),
        // 前几次没暴露只因恰好走了 L124「眼前能放就直接放」的快路径, 压根没碰 checker。
        // 对齐 PlaceBlockTask.onStart (其 L115 已有 _progressChecker.reset())。
        _progressChecker.reset();
        _wanderSinceMs = 0;
        _fastAttempt = null;
        _fastBlockedCell = null;
        // (2026-09-29 陈旧选点) onStart = 新一轮放置开始, 上次会话的选点/已放位
        // 对本轮是陈旧状态 (bot 可能已远走数百格), 全部作废重选。
        _tryPlace = null;
        _justPlaced = null;

        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);

        // Check for blocks being placed
        _onBlockPlaced = EventBus.subscribe(BlockPlaceEvent.class, evt -> {
            if (ArrayUtils.contains(_toPlace, evt.blockState.getBlock())) {
                // (2026-09-28 快路径假成功根修) 真值来源: 方块【确实落地】才认完成。
                // isFinished 读 _justPlaced, 旧实现只由 place() 的"右键 SUCCESS"设置,
                // 而那个 SUCCESS 可能只是打开了容器界面(见 place() 注释) ⇒ 会撒谎。
                _justPlaced = evt.blockPos;
                stopPlacing(_mod);
            }
        });
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-09-05 进门先出门, 用户"到地狱后必须先走出传送门才能放置")
        // 脚下还踩在门内方块(刚传送落地)时不做任何放置: ①站门里放方块,
        // Baritone 觉得"原地就够得着", bot 会长时间站在门内不动 (实测卡十几秒,
        // 还有 4 秒后被再次传送回程的风险); ②门内/门框现在是放置禁区, 眼前
        // 最优点位全被拦, 任务原地空转。先主动走出传送门再回来放。
        // (rev2, 用户"根本没有跑离四格, 卡住我手推才走开") GoalRunAway 实测
        // 不动 (17:35:27/31 两次 Running away 都没挪窝, 疑似刚切维度 Baritone
        // 寻路没就绪/远目标不吸引)。改为直接找可站立邻位, 用 CustomGoalProcess
        // 走一步出门; 找不到邻位才兜底 RunAway。
        BlockPos feet = mod.getPlayer().getBlockPos();
        if (mod.getWorld().getBlockState(feet).getBlock() == Blocks.NETHER_PORTAL) {
            setDebugState("Stepping out of nether portal before placing");
            BlockPos exit = findPortalExitNeighbor(mod, feet);
            if (exit != null) {
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(exit));
                return null;
            }
            return new RunAwayFromPositionTask(4, feet.getY(), feet);
        }

        // Method:
        // - If looking at placable block
        //      Place immediately
        // Find a spot to place
        // - Prefer flat areas (open space, block below) closest to player
        // -

        // Close screen first
        StorageHelper.closeScreen();

        // (2026-09-28 快路径假成功根修) 核实上一拍"眼前直接放"的结果。客户端世界更新
        // 滞后一拍, 所以在本拍核实: 那一拍的右键很可能只是打开了容器界面 —— 工作台 /
        // 熔炉 / 箱子这类"有 onUse 的方块"被右键时, interactBlock 照样返回 SUCCESS,
        // 但一个方块也没放出去。旧实现据此就认成功 ⇒ 上层每 tick 重投一次右键。
        // 现场 20260928-111156: diag.log [ScreenClose] handler=class_1714(工作台界面)
        // syncId 4→16 两秒开了又关 7 轮, 15 次 PRESSED, 而炉子【物品】计数全程不变。
        if (_fastAttempt != null) {
            BlockPos attempted = _fastAttempt;
            _fastAttempt = null;
            if (ArrayUtils.contains(_toPlace, mod.getWorld().getBlockState(attempted).getBlock())) {
                _justPlaced = attempted;      // 真的放出去了
                return null;
            }
            // 没放成 ⇒ 该落点右键只会开界面, 拉黑它(本任务内不再重投), 交给 baritone。
            _fastBlockedCell = attempted;
        }

        // Try placing where we're looking right now.
        BlockPos current = getCurrentlyLookingBlockPlace(mod);
        if (current != null && current.equals(_fastBlockedCell)) {
            current = null;   // 该落点已证明"右键只开界面", 本拍不走快路径
        }
        if (current != null && _canPlaceHere.test(current)) {
            setDebugState("Placing since we can...");
            if (mod.getSlotHandler().forceEquipItem(ItemHelper.blocksToItems(_toPlace))) {
                if (place(mod)) {
                    // 不在这里认成功 —— 下一拍核实(顶部), 真值也由 BlockPlaceEvent 兜底。
                    _fastAttempt = current;
                    return null;
                }
            }
        }

        // (2026-09-11 v2) 通用「踩水就先上岸」闸门 —— **无条件生效**, 不再要求
        // "选不到点位"。v1 的条件 `_tryPlace == null` 形同虚设:
        //
        // 现场 20260911-182618: bot 停在 (-101.50,61.50,214.57) 泡在深水里
        // (inWater=true onGround=false hSpeed=0.000), 12.2 秒零输入
        // (bIn[fwd/jump/sprint 全 false], baritone[pathing=false builder=true]),
        // 会话结束前一直在 "Place structure{crafting_table} at -102,62,214"。
        // 而 (-102,62,214) **就是 bot 自己的头部格** —— 方块放不进自己身体里,
        // baritone builder 只能死等。
        //
        // 为什么 v1 闸门没拦到: avoidOceanBlocks 让 canReach 在大洋里只剩
        // "水面高度那一层"(y=62, 判据 `pos.y < 64 && 水线62 > pos.y` 恰好为假)
        // 能通过, 于是 locateClosePlacePos **总会返回一个假点位**(就是头顶那格),
        // `_tryPlace == null` 永远不成立 → 闸门一行都没执行。
        //
        // 判据用 LandHelper.isFloatingInWater (沾水 + 脚下无实体): 站在浅水底/
        // 沙滩上有支撑, 不算踩水, 所以涉水过河、岸边浅水放置不受影响。
        // 放在"试放眼前点位"之后 —— 眼前确实能放就立刻放掉, 不白跑一趟。
        //
        // (v3, 现场 20260911-184733 「游一会停一会」) 闸门必须**上锁**, 不能每 tick
        // 重估 isTouchingWater: 水面上下浮动时 inWater 在 y62/y63 之间闪烁 (框架自己
        // 记作 NOT_IN_WATER_BOB_AIRBORNE), 腾空那一 tick 闸门变 false → 掉进下面的
        // 放置分支 → 回岸任务被抢切。实测每 2s 抢切一次, 时间戳吻合到毫秒:
        //   42.834 SWIM-DIVE gate=NOT_IN_WATER_BOB_AIRBORNE inWater=false y=63
        //   42.836 STOP <Getting to land> by <Place structure{crafting_table} ...>
        // 用户明确要求: 一旦判定要上岸就一路游到岸, 中途不打断 (卡住由 GetToLandTask
        // 自己的 4s 卡住检测重规划路线解决)。所以这里用一个 latch: 进入回岸模式后,
        // 只要任务没报告完成就一直返回同一个实例, 不再看 isTouchingWater。
        //
        // 完成时用 reset() 而不是 new: GetToLandTask.isEqual 恒 true, 直接换新实例
        // 会被框架的 isEqual 挡下 (不会 onStart), 而 reset() 让下次进入时 onStart 重跑。
        if (!GetToLandTask.isOnCooldown()) {
            if (_toLand != null && _toLand.isFinished(mod)) {
                _toLand.reset();
                _landActive = false;
            }
            if (!_landActive && LandHelper.isFloatingInWater(mod)) {
                if (_toLand == null) {
                    _toLand = new GetToLandTask();
                }
                _landActive = true;
            }
            if (_landActive) {
                _progressChecker.reset();
                setDebugState("Heading to land first (swimming, not interrupting)");
                return _toLand;
            }
        }

        // Wander while we can.
        // (2026-09-28 让位硬上限) 本分支原先只有 `_wander.isFinished()` 一个出口,
        // baritone 走不动时就永久停在这里 (见字段区 WANDER_GIVEUP_MS 注释的现场)。
        // 超时 ⇒ 换新 wander 实例 (isActive() 归假 ⇒ 本拍不再 return 它), 落到下方
        // 重新选点, 至少每 8s 一轮推进而不是彻底静止。
        if (_wander.isActive() && !_wander.isFinished(mod)) {
            if (_wanderSinceMs == 0) {
                _wanderSinceMs = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - _wanderSinceMs > WANDER_GIVEUP_MS) {
                Debug.logMessage("[PlaceNearby] 让位 wander 超时 " + (WANDER_GIVEUP_MS / 1000)
                        + "s 未产生位移 (baritone 疑似无路径), 丢弃本轮让位并重新选点");
                _wander = new TimeoutWanderTask(2);
                _wanderSinceMs = 0;
                // (2026-10-14 治点位复现) 旧代码只置 _tryPlace = null, 于是下一拍
                // locateClosePlacePos 按 distSq 打分重新选 —— bot 刚让位走了 2 格, 原来
                // 那个点**仍是全场最优解**, 于是被原样选回。现场 20261014-140704:
                // 273,65,-766 一个点位被重复选取 40 次 (165 个放置快照里共 48 个不同
                // 点位), 表现为"一直卡在原地"。让位超时本就等价于"此处站不下去",
                // 必须把点位一起拉黑, 否则重选只是重置记忆。
                // 注意: 判负分支 (_progressChecker.check) 早已调 requestBlockUnreachable,
                // 只有这条"让位也走不动"的路径漏了 —— 口径不一致才是复现的根因。
                if (_tryPlace != null) {
                    mod.getBlockTracker().requestBlockUnreachable(_tryPlace);
                }
                _tryPlace = null;              // 强制重新选点, 不复用已被拉黑的旧目标
                _progressChecker.reset();
            } else {
                setDebugState("Wandering, will try to place again later.");
                _progressChecker.reset();
                return _wander;
            }
        }
        // Fail check
        if (!_progressChecker.check(mod)) {
            Debug.logMessage("Failed placing, wandering and trying again.");
            LookHelper.randomOrientation(mod);
            if (_tryPlace != null) {
                mod.getBlockTracker().requestBlockUnreachable(_tryPlace);
                _tryPlace = null;
            }
            return _wander;
        }

        // Try to place at a particular spot.
        // (2026-09-05 传送门放置硬守卫) 复用缓存选点时补 canPlace 复查: 点位可能
        // 在选中后变成受保护位置 (如新发现门体/任务栈放置避让生效), 不复查会拿着
        // 过期点位反复重建被 [PortalGuard] 拒绝的 PlaceBlockTask 死循环。
        // (2026-09-29 陈旧选点距离闸) 超出 locate 半径的缓存点位必为陈旧
        // (bot 已远走), 直接作废重选, 不再信任 canReach 黑名单口径。
        if (_tryPlace == null
                || _tryPlace.getSquaredDistance(mod.getPlayer().getPos()) > STALE_PLACE_RANGE * STALE_PLACE_RANGE
                || !WorldHelper.canReach(mod, _tryPlace) || !WorldHelper.canPlace(mod, _tryPlace)
                || WorldHelper.isUnderwaterAt(mod, _tryPlace)) {
            _tryPlace = locateClosePlacePos(mod);
        }

        if (_tryPlace != null) {
            setDebugState("Trying to place at " + _tryPlace);
            _justPlaced = _tryPlace;
            // (2026-08-31 放单方块不垫基) 禁止 PlaceBlockTask 自动收集垫基方块。
            // 旧逻辑: 用默认构造(autoCollectStructureBlocks=true)放台子/熔炉等单体
            // 方块, 手上没有泥土/圆石时就被拖去挖 32 块垫基方块才回来放, 放台子
            // 卡死→回退挖泥土。放单方块理应直接落地, 不需要垫基。
            return new PlaceBlockTask(_tryPlace, _toPlace, false, false);
        }

        // Look in random places to maybe get a random hit
        if (_randomlookTimer.elapsed()) {
            _randomlookTimer.reset();
            LookHelper.randomOrientation(mod);
        }

        setDebugState("Wandering until we randomly place or find a good place spot.");
        return new TimeoutWanderTask();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        stopPlacing(mod);
        EventBus.unsubscribe(_onBlockPlaced);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceBlockNearbyTask task) {
            return Arrays.equals(task._toPlace, _toPlace);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Place " + Arrays.toString(_toPlace) + " nearby";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _justPlaced != null && ArrayUtils.contains(_toPlace, mod.getWorld().getBlockState(_justPlaced).getBlock());
    }

    public BlockPos getPlaced() {
        return _justPlaced;
    }

    /**
     * (2026-09-05 出门邻位搜索) 从门内脚位出发, 找一个"能直接迈过去站住"的水平
     * 邻位: 目标格可通行(非实心/非门/非门框), 头顶有空间, 脚下有支撑。
     * 返回 null 表示四面都被堵(罕见)。
     */
    private BlockPos findPortalExitNeighbor(AltoClef mod, BlockPos feet) {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos n = feet.offset(dir);
            Block nBlock = mod.getWorld().getBlockState(n).getBlock();
            if (nBlock == Blocks.NETHER_PORTAL || nBlock == Blocks.OBSIDIAN || nBlock == Blocks.CRYING_OBSIDIAN) {
                continue;
            }
            if (WorldHelper.isSolid(mod, n) || WorldHelper.isSolid(mod, n.up())) {
                continue;
            }
            if (!WorldHelper.isSolid(mod, n.down())) {
                continue;
            }
            return n;
        }
        return null;
    }

    private BlockPos getCurrentlyLookingBlockPlace(AltoClef mod) {
        HitResult hit = MinecraftClient.getInstance().crosshairTarget;
        if (hit instanceof BlockHitResult bhit) {
            BlockPos bpos = bhit.getBlockPos();//.subtract(bhit.getSide().getVector());
            //Debug.logMessage("TEMP: A: " + bpos);
            IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
            if (MovementHelper.canPlaceAgainst(ctx, bpos)) {
                BlockPos placePos = bhit.getBlockPos().add(bhit.getSide().getVector());
                // Don't place inside the player.
                if (WorldHelper.isInsidePlayer(mod, placePos)) {
                    return null;
                }
                // (2026-10-04) 被水淹没的位置不放(快路径同口径, 与选点过滤单一判据源)。
                if (WorldHelper.isUnderwaterAt(mod, placePos)) {
                    return null;
                }
                //Debug.logMessage("TEMP: B (actual): " + placePos);
                if (WorldHelper.canPlace(mod, placePos)) {
                    return placePos;
                }
            }
        }
        return null;
    }

    private boolean blockEquipped(AltoClef mod) {
        return StorageHelper.isEquipped(mod, ItemHelper.blocksToItems(_toPlace));
    }

    private boolean place(AltoClef mod) {
        if (!mod.getExtraBaritoneSettings().isInteractionPaused() && blockEquipped(mod)) {
            // Shift click just for 100% container security.
            mod.getInputControls().hold(Input.SNEAK);

            //mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            // This appears to work on servers...
            // TODO: Helper lol
            HitResult mouseOver = MinecraftClient.getInstance().crosshairTarget;
            if (mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
                return false;
            }
            Hand hand = Hand.MAIN_HAND;
            assert MinecraftClient.getInstance().interactionManager != null;
            if (MinecraftClient.getInstance().interactionManager.interactBlock(mod.getPlayer(), mod.getWorld(), hand, (BlockHitResult) mouseOver)  == ActionResult.SUCCESS) {
                mod.getPlayer().swingHand(hand);
                // (2026-09-28 快路径假成功根修) 这里【不能】把 SUCCESS 认成"方块放出去了":
                // 右键工作台 / 熔炉 / 箱子这类"有 onUse 的方块"会打开界面, interactBlock
                // 同样返回 SUCCESS —— 旧实现据此设 _justPlaced, 上层就每 tick 重投一次右键
                // (现场 20260928-111156: 两秒 15 次 PRESSED + 工作台界面开合 7 轮)。
                // 真值改由两条独立来源提供: onTick 顶部的"下一拍世界核实", 以及
                // BlockPlaceEvent(_onBlockPlaced, 方块真正落地的事件)。
                Debug.logMessage("PRESSED");
                return true;
            }

            //mod.getControllerExtras().mouseClickOverride(1, true);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            return true;
        }
        return false;
    }

    private void stopPlacing(AltoClef mod) {
        mod.getInputControls().release(Input.SNEAK);
        //mod.getControllerExtras().mouseClickOverride(1, false);
        // Oof, these sometimes cause issues so this is a bit of a duct tape fix.
        mod.getClientBaritone().getBuilderProcess().onLostControl();
    }

    private BlockPos locateClosePlacePos(AltoClef mod) {
        int range = 7;
        BlockPos best = null;
        double smallestScore = Double.POSITIVE_INFINITY;
        BlockPos start = mod.getPlayer().getBlockPos().add(-range, -range, -range);
        BlockPos end = mod.getPlayer().getBlockPos().add(range, range, range);

        for (BlockPos blockPos : WorldHelper.scanRegion(mod, start, end)) {
            boolean solid = WorldHelper.isSolid(mod, blockPos);
            // (2026-09-11 v2) 方块放不进自己身体里 —— 原实现把 isInsidePlayer 只当
            // 评分项 (+3 分) 而不排除, 于是 bot 泡在大洋里时 (合法点位全被
            // avoidOceanBlocks 的 canReach 规则滤掉) 唯一"幸存"的候选恰好是它自己
            // 的头部格 (-102,62,214), 被选中 → baritone builder 死等 12.2s。
            // 现场 20260911-182618。直接排除。
            if (WorldHelper.isInsidePlayer(mod, blockPos)) {
                continue;
            }
            // We can't break this block.
            if (solid && !WorldHelper.canBreak(mod, blockPos)) {
                continue;
            }
            // We can't place here as defined by user.
            if (!_canPlaceHere.test(blockPos)) {
                continue;
            }
            // We can't place here.
            if (!WorldHelper.canReach(mod, blockPos) || !WorldHelper.canPlace(mod, blockPos)) {
                continue;
            }
            // (2026-10-04) 被水淹没的位置不放 —— 单源判据只接放置路径(勿并入 canReach:
            // 舀水/追水源/找容器共用 canReach)。现场 20261004-1214 站水里 79s 反复。
            if (WorldHelper.isUnderwaterAt(mod, blockPos)) {
                continue;
            }
            boolean hasBelow = WorldHelper.isSolid(mod, blockPos.down());
            double distSq = blockPos.getSquaredDistance(mod.getPlayer().getPos());

            double score = distSq + (solid ? 4 : 0) + (hasBelow ? 0 : 10);

            if (score < smallestScore) {
                best = blockPos;
                smallestScore = score;
            }
        }

        return best;
    }
}
