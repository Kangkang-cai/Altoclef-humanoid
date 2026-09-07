package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatMemory;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
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
        if (!mod.getEntityTracker().entityFound(_targetEntities)) return Optional.empty();
        // (2026-09-07 击杀超时兜底) 跳过 CombatMemory 不可达冷却中的目标,
        // 防止"放弃 → 立刻重选同一只 → 再放弃"死循环 (蜘蛛躲暗处实测)。
        return mod.getEntityTracker().getClosestEntity(pos,
                _shouldInteractWith.and(e -> !CombatMemory.isUnreachable(e.getUuid())), _targetEntities);
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