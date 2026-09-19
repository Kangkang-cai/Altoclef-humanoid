package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatMemory;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Finds the closest entity and runs a task on that entity
 */
@SuppressWarnings("ALL")
public class DoToClosestEntityTask extends AbstractDoToClosestObjectTask<Entity> {

    private final Class[] _targetEntities;

    private final Supplier<Vec3d> _getOriginPos;

    private final Function<Entity, Task> _getTargetTask;

    private final Predicate<Entity> _shouldInteractWith;

    public DoToClosestEntityTask(Supplier<Vec3d> getOriginSupplier, Function<Entity, Task> getTargetTask, Predicate<Entity> shouldInteractWith, Class... entities) {
        _getOriginPos = getOriginSupplier;
        _getTargetTask = getTargetTask;
        _shouldInteractWith = shouldInteractWith;
        _targetEntities = entities;
    }

    public DoToClosestEntityTask(Supplier<Vec3d> getOriginSupplier, Function<Entity, Task> getTargetTask, Class... entities) {
        this(getOriginSupplier, getTargetTask, entity -> true, entities);
    }

    public DoToClosestEntityTask(Function<Entity, Task> getTargetTask, Predicate<Entity> shouldInteractWith, Class... entities) {
        this(null, getTargetTask, shouldInteractWith, entities);
    }

    public DoToClosestEntityTask(Function<Entity, Task> getTargetTask, Class... entities) {
        this(null, getTargetTask, entity -> true, entities);
    }

    @Override
    protected Vec3d getPos(AltoClef mod, Entity obj) {
        return obj.getPos();
    }

    @Override
    protected Optional<Entity> getClosestTo(AltoClef mod, Vec3d pos) {
        // (2026-09-19 22:44 用户"怎么有的时候, 明明有烈焰人在区域内任务还是等待生成")
        // 候选 = 追踪器 (感知路径, 原语义不变) ∪ additionalCandidates (绕过感知的补充)。
        // 为什么需要补充来源: entityTracker 只收录"看得见/听得见, 或记忆窗口内感知过"
        // 的实体, 而 bot 站上自己刚砌的墙/顶盖补块时, 那圈墙正好挡住自己的视线 →
        // 目标明明在围场里却"看不见" → 本方法返回 empty → 击杀任务空转、父任务每 tick
        // 重选 → 界面上就是"有烈焰人却一直等待生成"。
        Predicate<Entity> accept = _shouldInteractWith.and(e -> !CombatMemory.isUnreachable(e.getUuid()));
        Entity best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        if (mod.getEntityTracker().entityFound(_targetEntities)) {
            Optional<Entity> tracked = mod.getEntityTracker().getClosestEntity(pos, accept, _targetEntities);
            if (tracked.isPresent()) {
                best = tracked.get();
                bestSq = best.getPos().squaredDistanceTo(pos);
            }
        }
        for (Entity extra : additionalCandidates(mod)) {
            if (extra == null || !accept.test(extra)) continue;
            double sq = extra.getPos().squaredDistanceTo(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = extra;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * (2026-09-19 22:44) 感知之外的补充候选。**默认空 —— 所有现有使用者的行为一字不变。**
     * 只用于"实体肯定还在, 只是自己被自己挡住看不见"的场景 (目前唯一使用者是
     * {@link KillEntitiesTask} 的围场候选, 见 CollectBlazeRodsTask.blazesInEnclosure)。
     */
    protected List<Entity> additionalCandidates(AltoClef mod) {
        return Collections.emptyList();
    }

    @Override
    protected Vec3d getOriginPos(AltoClef mod) {
        if (_getOriginPos != null) {
            return _getOriginPos.get();
        }
        return mod.getPlayer().getPos();
    }

    @Override
    protected Task getGoalTask(Entity obj) {
        return _getTargetTask.apply(obj);
    }

    @Override
    protected boolean isValid(AltoClef mod, Entity obj) {
        // (2026-09-07 击杀超时兜底) 当前目标进入不可达冷却 → 视为无效, 触发重选。
        return obj.isAlive() && mod.getEntityTracker().isEntityReachable(obj)
                && !CombatMemory.isUnreachable(obj.getUuid());
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoToClosestEntityTask task) {
            return Arrays.equals(task._targetEntities, _targetEntities);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing something to closest entity...";
    }
}