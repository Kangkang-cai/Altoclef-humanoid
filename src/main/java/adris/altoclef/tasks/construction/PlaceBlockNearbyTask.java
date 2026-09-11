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

    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask _wander = new TimeoutWanderTask(2);

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
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);

        // Check for blocks being placed
        _onBlockPlaced = EventBus.subscribe(BlockPlaceEvent.class, evt -> {
            if (ArrayUtils.contains(_toPlace, evt.blockState.getBlock())) {
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

        // Try placing where we're looking right now.
        BlockPos current = getCurrentlyLookingBlockPlace(mod);
        if (current != null && _canPlaceHere.test(current)) {
            setDebugState("Placing since we can...");
            if (mod.getSlotHandler().forceEquipItem(ItemHelper.blocksToItems(_toPlace))) {
                if (place(mod, current)) {
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
        if (_wander.isActive() && !_wander.isFinished(mod)) {
            setDebugState("Wandering, will try to place again later.");
            _progressChecker.reset();
            return _wander;
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
        if (_tryPlace == null || !WorldHelper.canReach(mod, _tryPlace) || !WorldHelper.canPlace(mod, _tryPlace)) {
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

    private boolean place(AltoClef mod, BlockPos targetPlace) {
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
                _justPlaced = targetPlace;
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
