package adris.altoclef.util.helpers;

/**
 * (2026-09-16 死因记录) 最近一次玩家伤害来源名的静态 holder。
 *
 * 为什么不放 Mixin 类里: Mixin 会把所有非 private 方法合并进目标类
 * (PlayerEntity), public static 辅助方法会被判 InvalidMixin 拒绝应用,
 * 直接导致游戏启动崩溃 (2026-09-16 19:06 实锤: "contains non-private static
 * method consumeLastDeathCause")。项目先例 HitRegistry 也是"Mixin 调用普通类",
 * 故同样放到普通类。
 *
 * 为什么用静态 volatile: damage 在网络/实体线程触发, 死亡轮询 (DeathMenuChain)
 * 在客户端线程, 跨线程读写必须保证可见性; volatile 足以覆盖"最后一次写、
 * 死亡时读一次"的场景。
 *
 * 语义: 仅作为"死因"弱信号 —— 玩家死亡前的最后一击来源名 (已剥掉
 * "death.attack." 前缀), 如 "drown" / "lava" / "inFire" / "hotFloor" ...;
 * 若死亡前无受伤事件则为 null。
 *
 * (2026-09-23) 同时承载"死亡前是否持有珍贵物品"的快照。根因: 死亡屏上
 * 服务端停止向客户端发送更新, 死亡动画结束后约 1s 客户端侧区块/实体被
 * 卸载 (Mojang MC-169113/MC-254610, Works As Intended) —— 掉落物实体
 * 永远扫不到。改为在每次 damage HEAD 时 (背包尚未掉落) 直接评估背包,
 * 死亡时取样。用户拍板: 只看珍贵物品, 不再统计普通物资数量。
 */
public final class PlayerDamageCauseHolder {

    private static volatile String lastDamageCauseName = null;
    private static volatile boolean lastCarriedHighValue = false;

    // ---------- (2026-10-04 bridge 死因事件) 死亡播报原文快照 ----------
    // 为什么需要: bridge 的 death 事件目前只有字面 "死了"(EventCollector 的 isDead 边沿),
    // 对端拿不到死因。而死因的权威来源就是死亡播报包(DeathMessageS2CPacket)的原文
    // ("kangkang被北极熊杀死了"), onDeathMessage 时刻顺手记一份, 消费方读走即清。
    // 时序: 边沿检测(HealthUpdate 血量归零)可能先于死亡播报包到达, 消费方(bridge)须
    // 短暂重试; 保险起见读走即清 + 每次死亡必定覆盖, 不会串场。
    private static volatile String lastDeathDescription = null;

    // ---------- (2026-09-29 死因取证升级) 近期伤害流水 ----------
    // 为什么需要: 现场 20260929-222238 的死亡在日志里只留下一行 `cause=mob` —— 没有
    // 怪物种类、没有掉血序列、没有"死前想吃吃不了"。而录制数据里这些信息全都有
    // ([MOB-DIAG] nearbyHostiles=class_1642@hz=1.3 贴身 / 8 次 -2.5 / 1Hz 的
    // [FOOD-DIAG] needEat=true blocked=airborne) —— 说明采集到了却没进日志, 只能靠
    // 录制反推。这里把最近 N 次伤害留档, 死亡瞬间一次性导出, 让日志自身就能说明死因。
    // 纯新增字段 + 只读/一次性导出方法, 不参与任何既有判据 (与 hasGivenUp 撞名不同的
    // 是: 方法名带 DamageTrail 后缀, 不会与既有成员混淆)。
    private static final int MAX_DAMAGE_TRAIL = 12;
    private static final String[] damageTrail = new String[MAX_DAMAGE_TRAIL];
    private static int damageTrailCount = 0;
    private static int damageTrailHead = 0;

    private PlayerDamageCauseHolder() {
    }

    /** 由 PlayerDamageMixin 在每次 damage HEAD 时调用 (覆盖旧值)。 */
    public static void record(String causeName) {
        lastDamageCauseName = causeName;
    }

    /** 每次受伤追加一条 "hp前值 -扣量 来源名 by 攻击者@距离"; 满 12 条环形覆盖最旧的。 */
    public static synchronized void recordDamageTrail(String entry) {
        damageTrail[damageTrailHead] = entry;
        damageTrailHead = (damageTrailHead + 1) % MAX_DAMAGE_TRAIL;
        if (damageTrailCount < MAX_DAMAGE_TRAIL) {
            damageTrailCount++;
        }
    }

    /**
     * 只读导出 (不清空): 供"死亡播报到达"时先写一条 [DEATH] 汇总行,
     * 随后仍由 {@link #consumeRecentDamageTrail()} 在墓碑记录里正式取样。
     */
    public static synchronized String peekRecentDamageTrail() {
        if (damageTrailCount == 0) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        int start = (damageTrailHead - damageTrailCount + MAX_DAMAGE_TRAIL) % MAX_DAMAGE_TRAIL;
        for (int i = 0; i < damageTrailCount; i++) {
            String entry = damageTrail[(start + i) % MAX_DAMAGE_TRAIL];
            if (entry == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(entry);
        }
        return sb.toString();
    }

    /**
     * 死亡瞬间取样一次: 按时间顺序导出最近伤害流水并清空。
     * 清空是为了"每个墓只导出一次", 避免下一次死亡的记录里混进上一场的旧伤害。
     */
    public static synchronized String consumeRecentDamageTrail() {
        if (damageTrailCount == 0) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        int start = (damageTrailHead - damageTrailCount + MAX_DAMAGE_TRAIL) % MAX_DAMAGE_TRAIL;
        for (int i = 0; i < damageTrailCount; i++) {
            String entry = damageTrail[(start + i) % MAX_DAMAGE_TRAIL];
            if (entry == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(entry);
        }
        damageTrailCount = 0;
        damageTrailHead = 0;
        for (int i = 0; i < MAX_DAMAGE_TRAIL; i++) {
            damageTrail[i] = null;
        }
        return sb.toString();
    }

    /**
     * 由 PlayerDamageMixin 在每次 damage HEAD 时调用: 记录"当前背包是否含
     * 珍贵物品"。最后一次 (致命) 伤害时的背包内容即死亡掉落物。
     */
    public static void recordCarriedHighValue(boolean highValue) {
        lastCarriedHighValue = highValue;
    }

    /** 由 ClientDamageSensor.onDeathMessage 在死亡播报到达时调用(覆盖旧值)。 */
    public static void recordDeathDescription(String description) {
        lastDeathDescription = description;
    }

    /** 死因描述读走即清: 保证"每个墓只被消费一次", 不被下一次死亡继承。 */
    public static synchronized String consumeDeathDescription() {
        String desc = lastDeathDescription;
        lastDeathDescription = null;
        return desc;
    }

    /**
     * 死亡瞬间取样一次: 返回并清空最近一次伤害来源名。
     * 读后清空是为了"每个墓只取样一次" —— 避免无关旧伤害来源名被后续死亡继承。
     */
    public static String consumeLastDeathCause() {
        String cause = lastDamageCauseName;
        lastDamageCauseName = null;
        return cause;
    }

    /** 死亡瞬间取样一次: 返回并清空"死亡前是否持有珍贵物品"。 */
    public static boolean consumeLastDeathCarriedHighValue() {
        boolean highValue = lastCarriedHighValue;
        lastCarriedHighValue = false;
        return highValue;
    }
}
