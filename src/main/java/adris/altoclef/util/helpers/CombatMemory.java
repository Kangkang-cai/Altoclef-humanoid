package adris.altoclef.util.helpers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * (2026-09-07 击杀超时兜底, 用户"蜘蛛躲在暗处 bot 傻傻对准") 战斗目标记忆:
 * 击杀任务超时放弃的目标进入"不可达冷却", 冷却期内所有击杀目标选择
 * (熔炉守卫派杀 / KillEntitiesTask / DoToClosestEntityTask) 跳过该实体,
 * 防止"放弃 → 立刻重选同一只 → 再放弃"的死循环。按 UUID 记忆, 与实体
 * 实例生命周期解耦。
 */
public final class CombatMemory {

    private static final Map<UUID, Long> UNREACHABLE_UNTIL = new HashMap<>();

    private CombatMemory() {
    }

    /** 标记目标不可达, durationTicks 内跳过它 (游戏 tick)。 */
    public static void markUnreachable(UUID id, int durationTicks) {
        UNREACHABLE_UNTIL.put(id, WorldHelper.getTicks() + (long) durationTicks);
    }

    public static boolean isUnreachable(UUID id) {
        if (id == null) return false;
        Long until = UNREACHABLE_UNTIL.get(id);
        if (until == null) return false;
        if (until <= WorldHelper.getTicks()) {
            UNREACHABLE_UNTIL.remove(id);
            return false;
        }
        return true;
    }

    public static void clear() {
        UNREACHABLE_UNTIL.clear();
    }
}
