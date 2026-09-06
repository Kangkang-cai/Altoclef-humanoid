package adris.altoclef.util.helpers;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * (2026-09-06 Phase 1) 本地事件记忆: 记录"我们(或玩家)亲手打过哪些猪灵系实体"。
 *
 * 背景 (Phase 0 实测, altoclef_diag.log 11:56 事件):
 *  - PiglinEntity 的 brain ANGRY_AT 记忆客户端不同步 (angryAtMem 恒 false);
 *  - ZombifiedPiglinEntity 的 Angerable API 客户端恒 false (hasAngerTime);
 *  → 客户端只能靠本地事件记忆推断激怒状态: 谁被我们打过, 谁就在记仇。
 *
 * 写入点: AttackEntityMixin (ClientPlayerInteractionManager#attackEntity HEAD),
 * 同时覆盖 bot 的攻击与玩家手动攻击。只读方: EntityHelper.isGenerallyHostileToPlayer。
 *
 * 规则 (保守取值, 宁可多防不漏防):
 *  - 成年猪灵: 被打过 → 记仇 30s; 攻击会向 16 格内同伴传播仇恨 (vanilla 群体激怒);
 *  - 僵尸猪灵: 打过任意一只 → 以命中点为圆心 32 格内全体激怒 25s (vanilla 群体仇恨);
 *  - 换维度清空, 不持久化 (愤怒是短时状态, 跨会话无意义)。
 */
public final class HitRegistry {

    private HitRegistry() {}

    /** 成年猪灵记仇窗口 (vanilla anger 600 ticks=30s)。 */
    private static final long PIGLIN_ANGER_MS = 30_000;
    /** 攻击一只猪灵后, 同伴仇恨传播半径。 */
    private static final double PIGLIN_GROUP_RADIUS = 16.0;
    /** 僵尸猪灵群体仇恨窗口 (vanilla 400~800 ticks, 取保守下限偏上)。 */
    private static final long ZOMBIFIED_HORDE_MS = 25_000;
    /** 僵尸猪灵群体仇恨半径 (vanilla 32)。 */
    private static final double ZOMBIFIED_HORDE_RADIUS = 32.0;

    private static final Map<UUID, Long> _piglinAngeredUntil = new HashMap<>();
    private static long _zombifiedHordeUntil = 0;
    private static double _zombifiedHordeX, _zombifiedHordeY, _zombifiedHordeZ;
    private static RegistryKey<World> _dimension = null;

    /**
     * 记录一次客户端攻击 (bot 或玩家手动)。只关心猪灵系目标, 其余直接忽略。
     * 注意: 攻击发起≠必然命中 (服务器有距离校验), 但对"是否激怒"的判断,
     * 保守记录发起即可 —— 挥空不该激怒, 但概率低且代价只是多防一会。
     */
    public static void onClientAttack(Entity target) {
        if (target == null || !target.isAlive()) return;
        boolean isPiglin = target instanceof PiglinEntity;
        boolean isZombified = target instanceof ZombifiedPiglinEntity;
        if (!isPiglin && !isZombified) return;

        long now = System.currentTimeMillis();
        RegistryKey<World> dim = target.getWorld().getRegistryKey();
        if (!dim.equals(_dimension)) {
            reset();
            _dimension = dim;
        }

        if (isPiglin) {
            long until = now + PIGLIN_ANGER_MS;
            _piglinAngeredUntil.put(target.getUuid(), until);
            // 群体传播: 16 格内的同伴一起记仇 (vanilla 猪灵会喊帮手)。
            List<PiglinEntity> nearby = target.getWorld().getEntitiesByClass(
                    PiglinEntity.class,
                    target.getBoundingBox().expand(PIGLIN_GROUP_RADIUS),
                    e -> e != null && e.isAlive());
            for (PiglinEntity pig : nearby) {
                _piglinAngeredUntil.put(pig.getUuid(), until);
            }
        } else {
            // 僵尸猪灵: 群体仇恨窗口, 记住命中点 (32 格内全体激怒)。
            _zombifiedHordeUntil = now + ZOMBIFIED_HORDE_MS;
            _zombifiedHordeX = target.getX();
            _zombifiedHordeY = target.getY();
            _zombifiedHordeZ = target.getZ();
        }
        pruneExpired(now);
    }

    /** 成年猪灵是否处于"被我们打过、正在记仇"状态。 */
    public static boolean isPiglinAngered(Entity pig) {
        return isPiglinAngered(pig, System.currentTimeMillis());
    }

    public static boolean isPiglinAngered(Entity pig, long now) {
        if (pig == null) return false;
        ensureDimension(pig.getWorld().getRegistryKey());
        Long until = _piglinAngeredUntil.get(pig.getUuid());
        return until != null && now < until;
    }

    /** 僵尸猪灵是否处于群体仇恨窗口内 (以最近一次命中点为圆心)。 */
    public static boolean isZombifiedAngered(Entity zombie) {
        return isZombifiedAngered(zombie, System.currentTimeMillis());
    }

    public static boolean isZombifiedAngered(Entity zombie, long now) {
        if (zombie == null) return false;
        ensureDimension(zombie.getWorld().getRegistryKey());
        if (now >= _zombifiedHordeUntil) return false;
        return zombie.squaredDistanceTo(_zombifiedHordeX, _zombifiedHordeY, _zombifiedHordeZ)
                <= ZOMBIFIED_HORDE_RADIUS * ZOMBIFIED_HORDE_RADIUS;
    }

    private static void ensureDimension(RegistryKey<World> dim) {
        if (_dimension == null) {
            _dimension = dim;
        } else if (!_dimension.equals(dim)) {
            reset();
            _dimension = dim;
        }
    }

    private static void pruneExpired(long now) {
        Iterator<Map.Entry<UUID, Long>> it = _piglinAngeredUntil.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue()) it.remove();
        }
        if (now >= _zombifiedHordeUntil) {
            _zombifiedHordeUntil = 0;
        }
    }

    /** 换维度/换存档清空。 */
    public static void reset() {
        _piglinAngeredUntil.clear();
        _zombifiedHordeUntil = 0;
    }
}
