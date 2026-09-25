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

    /**
     * (2026-09-25 191451 拍板) 诱爆派发半径²: 苦力怕未点燃但 ≤8 格逼近即接管控距
     * (原口径点燃才派, 1.5s 引信从 2.9 格往外撤太贴边)。防御链与重定向共用。
     */
    public static final double DISPATCH_RANGE_SQ = 8.0 * 8.0;

    /**
     * (2026-09-25 224856 实锤) 诱爆持有半径²: 信号目标存活且 ≤10 格即持续持有,
     * 覆盖"KITE 撤离成功→引信熄灭"窗口。派发端距离门也用它 (≤10 才可派),
     * 防"派在 11 格、持有只到 10"的夹缝横跳。
     */
    public static final double HOLD_RANGE_SQ = 10.0 * 10.0;

    private static volatile UUID _baitingId = null;
    // (2026-09-25 224856) 信号是否由守卫 (熔炉保护, keepClear 语义) 持有:
    // 守卫持有的诱爆防御链不收编 (收编会丢 keepClear, 引爆点可能贴近熔炉);
    // 批次重定向创建的 (无守卫标记) 才由防御链收编为链级持有, 消灭
    // "批次重派→重定向→新建"每 0.5s Task STOP/START 横跳。
    private static volatile boolean _guarded = false;

    private CreeperBaitSignal() {
    }

    public static void set(UUID creeperId) {
        set(creeperId, false);
    }

    public static void set(UUID creeperId, boolean guarded) {
        _baitingId = creeperId;
        _guarded = guarded;
    }

    /** 当前诱爆是否由守卫 (熔炉保护) 持有 —— 防御链不收编这种。 */
    public static boolean isGuarded() {
        return _guarded;
    }

    /** 当前诱爆目标的 UUID (无则 null) —— 持有判据的单一事实源。 */
    public static UUID getTargetId() {
        return _baitingId;
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
