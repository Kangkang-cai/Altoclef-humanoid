package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.PlaceBlockSchematic;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Destroy a block at a position.
 */
public class DestroyBlockTask extends Task implements ITaskRequiresGrounded {

    private final BlockPos _pos;
    private final MovementProgressChecker _moveChecker = new MovementProgressChecker(6, 0.1, 4, 0.01);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5, true);

    private final TimerGame _tryToMineTimer = new TimerGame(5);

    // (2026-09-03 够不着直接放弃, 用户"没用的随机探索, 再够不到直接放弃") 对悬浮/太高
    // 的目标(reach 持续缺失), 旧逻辑 _tryToMineTimer 超时 → "Getting to block…"设
    // baritone 目标 → 够不着 → wander → 重试同目标 → 无限空转(09:10 撸树顶 y=68 原木
    // 空转 30s, 既不搭方块垫脚也不放弃)。requestBlockUnreachable 原只在 _moveChecker
    // 失败触发, 但 bot 能走动(moveChecker 通过)就不触发 → 永远不判不可达。这里补:
    // reach 持续缺失超时仍够不着 → 判不可达黑名单并【直接放弃】(返回 null 交上层
    // MineAndCollectTask 换下一个目标), 不再随机探索空转。不做搭方块垫脚(用户判定放弃)。
    private int _gettingToBlockTick = 0;
    private static final int GETTING_TO_BLOCK_GIVE_UP_TICKS = 40; // 2s, 够不着即放弃

    // For vines and stuff
    private boolean _wasClose = false;

    // (2026-09-01 自挖中央记录) 任务开始时目标是否实心: 只有"开始时实心、结束时
    // 变空气"才算我们真实挖开的一格(开始时已是空气的不记, 防误标天然洞空气)。
    // 记录进 MineDownExploreTask 的持久化自挖记忆, 供矿洞检测区分天然空气。
    private boolean _wasSolidAtStart = false;
    private boolean _selfDugRecorded = false;

    public DestroyBlockTask(BlockPos pos) {
        _pos = pos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _tryToMineTimer.forceElapse();
        _wanderTask.resetWander();
        StorageHelper.closeScreen();
        _wasSolidAtStart = WorldHelper.isSolid(mod, _pos);
        _selfDugRecorded = false;
        _gettingToBlockTick = 0;

        mod.getBehaviour().push();
        // Avoid placing on top, to prevent our annoying "run away" bug
        mod.getBehaviour().avoidBlockPlacing(pos -> _pos.up().equals(pos));
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-05 挖完仍挖真因, 用户"挖掉一块金矿不到一秒, 但任务还挖它卡几秒"):
        // 任务框架 (Task.tickInternal) 从不调用子任务 isFinished —— 子任务只在父任务
        // 换目标时才被替换。而 BlockTracker 缓存的重扫清理有周期, 已挖掉的空气位在
        // 清理前仍可能被上层选中 (pickNearestCacheOre 的方块状态重校验还有 500ms 节流),
        // 于是本任务对着空气 builder 重建/左键空挥, 卡到 MovementProgressChecker
        // 超时 (~6s) 才脱身 —— 正是"挖完的方块任务还在挖、卡几秒"的来源。
        // 修复: onTick 最顶上直查真实世界, 已是空气 → 立即收工 (记自挖、停输入、
        // 不再喂 builder)。下一 tick 父任务 isValid(空气位)=false 自然换目标。
        if (WorldHelper.isAir(mod, _pos)) {
            if (_wasSolidAtStart && !_selfDugRecorded) {
                _selfDugRecorded = true;
                MineDownExploreTask.recordBrokenBlock(mod, _pos);
            }
            setDebugState("Target already air, nothing to break");
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            return null;
        }

        // Wander and check
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _moveChecker.reset();
            return _wanderTask;
        }
        if (!_moveChecker.check(mod)) {
            _moveChecker.reset();
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            _wanderTask.resetWander();
            return _wanderTask;
        }

        // do NOT break if we're standing above it and it's dangerous below...
        if (!WorldHelper.isSolid(mod, _pos.up()) && mod.getPlayer().getPos().y > _pos.getY() && _pos.isWithinDistance(mod.getPlayer().isOnGround()? mod.getPlayer().getPos() : mod.getPlayer().getPos().add(0, -1, 0), 0.89)) {
            if (WorldHelper.dangerousToBreakIfRightAbove(mod, _pos)) {
                setDebugState("It's dangerous to break as we're right above it, moving away and trying again.");
                return new RunAwayFromPositionTask(3, _pos.getY(), _pos);
            }
        }

        // We're trying to mine
        Optional<Rotation> reach = LookHelper.getReach(_pos);
        if (reach.isPresent()) {
            _tryToMineTimer.reset();
            _gettingToBlockTick = 0;
        }
        if (!_tryToMineTimer.elapsed()) {
            if (reach.isPresent() && (mod.getPlayer().isTouchingWater() || mod.getPlayer().isOnGround())) {
                setDebugState("Block in range, mining...");
                // Break the block, force it.
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                if (!LookHelper.isLookingAt(mod, _pos)) {
                    LookHelper.lookAt(mod, reach.get());
                }
                if (LookHelper.isLookingAt(mod, _pos)) {
                    // Tool equip is handled in `PlayerInteractionFixChain`. Oof.
                    mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
            } else {
                setDebugState("Breaking the normal way.");
                if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
                    // Try breaking normally.
                    mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                    Debug.logMessage("Break Block: Restarting builder process");
                    mod.getClientBaritone().getBuilderProcess().build("destroy block", new PlaceBlockSchematic(Blocks.AIR), _pos);
                }
            }
        } else {
            setDebugState("Getting to block...");
            // (2026-09-03 够不着即放弃) reach 持续缺失且累计足够长仍够不着 → 判不可达,
            // 黑名单交给上层换目标, 不再无限 "Getting to block → wander → 重试同目标"。
            if (_gettingToBlockTick++ > GETTING_TO_BLOCK_GIVE_UP_TICKS) {
                Debug.logMessage("(DestroyBlockTask) unreachable after " + _gettingToBlockTick
                        + " ticks, abandoning " + _pos.toShortString());
                mod.getBlockTracker().requestBlockUnreachable(_pos);
                _gettingToBlockTick = 0;
                return null; // 直接放弃, 交上层换下一个目标, 不随机探索
            }
            boolean isClose = _pos.isWithinDistance(mod.getPlayer().getPos(), 1);
            if (isClose != _wasClose) {
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                _wasClose = isClose;
            }
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                // If we're close, go TO the block (potentially disrupts vines and stuff)
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(isClose? new GoalBlock(_pos) : new GoalNear(_pos, 1));
            }
        }

        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (!AltoClef.inGame())
            return;
        // (2026-09-02 自挖记录修复: onStop 双通道补记)
        // 根因: Task.tickInternal 换子任务走 _sub.stop() 中断旧实例, 从不调子任务
        // isFinished。MineDownExploreTask 挖隧道每 tick new DestroyBlockTask、挖开
        // 后被框架 stop 替换 → isFinished 永不执行 → 自挖记录(isFinished 里唯一入口)
        // 从未触发, altoclef_selfdug.log 从未生成(实锤) → 自挖隧道空气全被矿洞检测
        // 当"天然空气" → 探索矿洞导航进自己挖的隧道。
        // onStop 是父任务换任务必走路径, 在此补记: 开始时实心、停止时已变空气 =
        // 我们真实挖开的一格(幂等: isFinished 先记过就不重复)。
        if (WorldHelper.isAir(mod, _pos) && _wasSolidAtStart && !_selfDugRecorded) {
            _selfDugRecorded = true;
            adris.altoclef.tasks.movement.MineDownExploreTask.recordBrokenBlock(mod, _pos);
        }
        mod.getBehaviour().pop();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        // Do not keep breaking.
        // Can lead to trouble, for example, if lava is right above the NEXT block.
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        boolean air = WorldHelper.isAir(mod, _pos);
        // (2026-09-01 自挖中央记录) 开始时实心、现在变空气 ⇒ 这格空气是我们挖出来的,
        // 记入持久化自挖记忆(幂等)。矿洞检测据此排除自挖隧道, 不再误判"自己的洞"。
        if (air && _wasSolidAtStart && !_selfDugRecorded) {
            _selfDugRecorded = true;
            adris.altoclef.tasks.movement.MineDownExploreTask.recordBrokenBlock(mod, _pos);
        }
        return air;//;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DestroyBlockTask task) {
            return task._pos.equals(_pos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Destroy block at " + _pos.toShortString();
    }
}
