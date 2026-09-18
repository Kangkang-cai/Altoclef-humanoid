package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import net.minecraft.entity.Entity;

import java.util.function.Predicate;

/**
 * Kill all entities of a type
 */
public class KillEntitiesTask extends DoToClosestEntityTask {

    public KillEntitiesTask(Predicate<Entity> shouldKill, Class... entities) {
        super(KillEntityTask::new, shouldKill, entities);
    }

    public KillEntitiesTask(Class... entities) {
        super(KillEntityTask::new, entities);
    }

    @Override
    protected boolean closerTargetMayBreakSticky(AltoClef mod) {
        // (2026-09-18 战斗近距优先, 用户"击打目标优先靠的近的") 杀怪场景允许
        // "显著更近"的新目标打破粘滞 — 僵尸贴脸时不再死追远处骷髅 31.5 秒。
        // 0.85 系数在基类里防横跳。
        return true;
    }

    @Override
    protected String toDebugString() {
        return "Killing hostiles";
    }
}
