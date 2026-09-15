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
 *  (20260915 更正: ANGRY_AT 客户端同步实测成立, 20260915-123005 场次
 *   angryAtMem=true 实锤 —— Phase 0 的"恒 false"是当时的测量场景没有猪灵
 *   处于激怒态所致。现在 ANGRY_AT 与事件记忆双保险并用, 见 EntityHelper
 *   isGenerallyHostileToPlayer / onPlayerDamagedBy。)
 *
 * 写入点 1: AttackEntityMixin (ClientPlayerInteractionManager#attackEntity HEAD),
 * 覆盖 bot 的攻击与玩家手动攻击;
 * 写入点 2 (20260915 反向记忆): PlayerDamageMixin (PlayerEntity#damage HEAD),
 * bot 被猪灵系实体打到 → 该实体记敌对, 覆盖"挖金/开箱激怒、bot 没打过它"的场景。
 * 只读方: EntityHelper.isGenerallyHostileToPlayer。
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
            recordPiglinAnger((PiglinEntity) target, now);
        } else {
            // 僵尸猪灵: 群体仇恨窗口, 记住命中点 (32 格内全体激怒)。
            _zombifiedHordeUntil = now + ZOMBIFIED_HORDE_MS;
            _zombifiedHordeX = target.getX();
            _zombifiedHordeY = target.getY();
            _zombifiedHordeZ = target.getZ();
        }
        pruneExpired(now);
    }

    /**
     * (20260915 挖金激怒盲区修复) 反向事件记忆: bot 被某只猪灵系实体打到 → 该实体
     * (含 16 格内同伴, 猪灵激怒是群体行为) 记敌对, 窗口与正向记忆一致。
     * 覆盖"bot 没打过猪灵、猪灵因挖金/开箱激怒正在打 bot"的场景 —— 该场景下
     * ANGRY_AT 与"金甲兜底"之外曾无任何信号 (20260915-123005 场次实锤:
     * 穿金甲 + hitReg=false + 贴脸输出, 4 秒掉 16 血全程不还手)。
     * 调用方: PlayerDamageMixin (PlayerEntity#damage HEAD, 体内置服务端与客户端同 JVM)。
     */
    public static void onPlayerDamagedBy(Entity attacker) {
        if (attacker == null || !attacker.isAlive()) return;
        boolean isPiglin = attacker instanceof PiglinEntity;
        boolean isZombified = attacker instanceof ZombifiedPiglinEntity;
        if (!isPiglin && !isZombified) return;

        long now = System.currentTimeMillis();
        RegistryKey<World> dim = attacker.getWorld().getRegistryKey();
        if (!dim.equals(_dimension)) {
            reset();
            _dimension = dim;
        }

        if (isPiglin) {
            recordPiglinAnger((PiglinEntity) attacker, now);
        } else {
            // 被僵尸猪灵打到: 直接刷新群体仇恨窗口, 圆心=攻击者位置。
            _zombifiedHordeUntil = now + ZOMBIFIED_HORDE_MS;
            _zombifiedHordeX = attacker.getX();
            _zombifiedHordeY = attacker.getY();
            _zombifiedHordeZ = attacker.getZ();
        }
        pruneExpired(now);
    }

    /** 记仇写入 (正向攻击/反向被打共用): 目标本体 + 16 格内同伴。 */
    private static void recordPiglinAnger(PiglinEntity pig, long now) {
        long until = now + PIGLIN_ANGER_MS;
        _piglinAngeredUntil.put(pig.getUuid(), until);
        // 群体传播: 16 格内的同伴一起记仇 (vanilla 猪灵会喊帮手)。
        List<PiglinEntity> nearby = pig.getWorld().getEntitiesByClass(
                PiglinEntity.class,
                pig.getBoundingBox().expand(PIGLIN_GROUP_RADIUS),
                e -> e != null && e.isAlive());
        for (PiglinEntity other : nearby) {
            _piglinAngeredUntil.put(other.getUuid(), until);
        }
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
