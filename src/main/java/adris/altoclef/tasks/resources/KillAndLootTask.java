package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.BiasedExploreTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import net.minecraft.entity.Entity;

import java.util.function.Predicate;

public class KillAndLootTask extends ResourceTask {

    private final Class _toKill;

    private final Task _killTask;

    // Cached exploration instance: returned every tick while the mob is missing.
    // (BiasedExploreTask uses identity isEqual, so callers MUST reuse the same
    // instance instead of constructing a new one each tick.)
    private BiasedExploreTask _exploreTask;

    public KillAndLootTask(Class toKill, Predicate<Entity> shouldKill, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _killTask = new KillEntitiesTask(shouldKill, _toKill);
    }

    public KillAndLootTask(Class toKill, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _killTask = new KillEntitiesTask(_toKill);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (!mod.getEntityTracker().entityFound(_toKill)) {
            if (isInWrongDimension(mod)) {
                setDebugState("Going to correct dimension.");
                return getToCorrectDimensionTask(mod);
            }
            setDebugState("Searching for mob...");
            // Steer toward places we remember seeing this mob before.
            // Reuse the cached instance; only swap once it finished.
            if (_exploreTask == null || _exploreTask.isFinished(mod)) {
                // [FOOD-BIOME] (2026-08-31 用户需求, 2026-09-23 森林分档)
                // CollectFoodTask → killTaskOrNull 触发的找动物/找食物探索:
                // foodExplore=true 让探索机制按档级偏置走向食物富集群系 —— 平原
                // 最优先, 其次本动物的记忆点, 再按橡木/花森林→白桦→深色橡林逐级
                // 兜底, 提高动物/食物收获概率。挖矿/砍木等 non-food 探索不经过
                // 这里, 不受影响。
                _exploreTask = new BiasedExploreTask(true, () -> mod.getWorldMemory().getAnimalLocations((Class<?>) _toKill));
            }
            return _exploreTask;
        }
        // We found the mob!
        return _killTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof KillAndLootTask task) {
            return task._toKill.equals(_toKill);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect items from " + _toKill.toGenericString();
    }
}
