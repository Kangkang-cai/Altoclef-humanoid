package adris.altoclef.util.helpers;

import net.minecraft.entity.Entity;

import java.util.UUID;

/**
 * (2026-09-25 苦力怕诱爆风筝) 全局单槽信号: 当前正在被诱爆风筝的苦力怕。
 *
 * 用途: 诱爆任务运行期间, 防御链/KillAura 的既有苦力怕反射 (逃跑 / 挥刀)
 * 会与"故意留在爆圈边引它自爆"直接打架 (172433 会话实锤: Killing creeper
 * ↔ Run away from creepers 横跳 6s+, 徒手没打死反被炸了熔炉)。各反射点
 * 读本信号做定点豁免, 与 MobDefenseChain._activeGuardedPos 的静态信号先例
 * 同款。
 *
 * 单槽语义: 同一时间只诱爆一只苦力怕; 第二只点燃的苦力怕照常触发既有逃跑
 * (安全兜底优先)。UUID 比对不持有实体引用, 无内存滞留; 换存档后旧 UUID
 * 永不匹配, 无需额外清理。
 */
public final class CreeperBaitSignal {

    private static volatile UUID _baitingId = null;

    private CreeperBaitSignal() {
    }

    public static void set(UUID creeperId) {
        _baitingId = creeperId;
    }

    /** 仅当 id 与当前诱爆目标一致时清除 (防旧任务 onStop 误清新任务的信号)。 */
    public static void clear(UUID creeperId) {
        if (creeperId == null || creeperId.equals(_baitingId)) {
            _baitingId = null;
        }
    }

    /** 当前是否有诱爆在进行 (不看具体目标)。 */
    public static boolean isActive() {
        return _baitingId != null;
    }

    public static boolean isBaiting(Entity entity) {
        UUID id = _baitingId;
        return entity != null && id != null && id.equals(entity.getUuid());
    }
}
