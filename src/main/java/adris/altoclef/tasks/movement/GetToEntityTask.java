package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalFollowEntity;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.entity.Entity;

public class GetToEntityTask extends Task implements ITaskRequiresGrounded {

    private final Entity _entity;

    private final double _closeEnoughDistance;

    private final MovementProgressChecker _progress = new MovementProgressChecker(5, 0.1, 5, 0.001, 2);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(10);

    // [2026-08-22 水中贴角落修复] 水中直游触发距离 (8 格): 掉落物拾取本质
    // 是碰撞接触 (半径 1-2 格), 追踪目标大多是沉在附近水域的掉落物。
    // Baritone 水中寻路慢, 且 GoalFollowEntity 的到达判定 (球面距离) 与
    // 碰撞拾取不一致, 贴方块角落会"游进 1 格→goal 达成→没吸到→重设
    // goal→转头换路径"原地打转; 水流还会推动掉落物, goal 漂移导致反复
    // 重算。8 格内改人类式直游: 对准目标按住前进(+视情况跳跃), 靠碰撞
    // 自然拾取。直游一旦开始就持续到离水/失速放弃, 不被 8 格边界来回
    // 切断 (否则边界附近会"直游→释放→寻路→直游"高频抖动)。
    private static final double DIRECT_SWIM_RANGE = 8;

    private boolean _directSwimControl = false;
    private double _directSwimBestDist = Double.MAX_VALUE;
    private final TimerGame _directSwimStallTimer = new TimerGame(5);

    public GetToEntityTask(Entity entity, double closeEnoughDistance) {
        _entity = entity;
        _closeEnoughDistance = closeEnoughDistance;
    }

    public GetToEntityTask(Entity entity) {
        this(entity, 1);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        _wanderTask.resetWander();
        _directSwimControl = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _progress.reset();
            setDebugState("Failed to get to target, wandering for a bit.");
            return _wanderTask;
        }

        // [2026-08-22 水中贴角落修复] 水中直游状态机:
        //   进入: 在水中且目标 8 格内 (近距主场景, 详见 DIRECT_SWIM_RANGE 注释)。
        //   持续: 直游一旦开始就不被 8 格边界切断, 目标游远就一路追。
        //   退出: (a) 离水 → 交还 Baritone 收尾;
        //         (b) 超出 8 格且 5 秒无逼近 (顶到岛壁/悬崖死顶) → 放弃
        //             直游让 Baritone 绕路。近距失速不退出, 由上层任务的
        //             被困检测 (PickupDroppedItemTask 15s 位移<2格) 兜底。
        boolean inWater = mod.getPlayer().isTouchingWater();
        double distToTarget = mod.getPlayer().getPos().distanceTo(_entity.getPos());

        if (_directSwimControl) {
            if (!inWater) {
                releaseDirectSwim(mod);
            } else {
                if (distToTarget < _directSwimBestDist - 0.5) {
                    _directSwimBestDist = distToTarget;
                    _directSwimStallTimer.reset();
                }
                if (distToTarget > DIRECT_SWIM_RANGE && _directSwimStallTimer.elapsed()) {
                    releaseDirectSwim(mod);
                } else {
                    holdDirectSwim(mod);
                    setDebugState("Swimming directly to target");
                    return null;
                }
            }
        } else if (inWater && distToTarget <= DIRECT_SWIM_RANGE) {
            _directSwimBestDist = distToTarget;
            _directSwimStallTimer.reset();
            holdDirectSwim(mod);
            setDebugState("Swimming directly to target");
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalFollowEntity(_entity, _closeEnoughDistance));
        }

        if (mod.getPlayer().isInRange(_entity, _closeEnoughDistance)) {
            _progress.reset();
        }

        if (!_progress.check(mod)) {
            return _wanderTask;
        }

        setDebugState("Going to entity");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_directSwimControl) {
            releaseDirectSwim(mod);
        }
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    /** 直游: 视角对准目标实体 + 前进; 跳跃仅目标不在下方时按住 (水中
     *  跳跃=上浮, 会抵消下潜; 目标在脚下深处时松开才能潜下去)。 */
    private void holdDirectSwim(AltoClef mod) {
        // 停掉 Baritone 的 goal 寻路, 避免两套控制打架。
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        LookHelper.lookAt(mod, _entity.getPos());
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        boolean targetBelow = _entity.getPos().y < mod.getPlayer().getY() - 0.5;
        if (targetBelow) {
            mod.getInputControls().release(Input.JUMP);
        } else {
            mod.getInputControls().hold(Input.JUMP);
        }
        _progress.reset();
        _directSwimControl = true;
    }

    private void releaseDirectSwim(AltoClef mod) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.JUMP);
        _directSwimControl = false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToEntityTask task) {
            return task._entity.equals(_entity) && Math.abs(task._closeEnoughDistance - _closeEnoughDistance) < 0.1;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Approach entity " + _entity.getType().getTranslationKey();
    }
}
