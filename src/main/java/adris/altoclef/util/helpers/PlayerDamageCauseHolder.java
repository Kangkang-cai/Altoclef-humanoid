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

    private PlayerDamageCauseHolder() {
    }

    /** 由 PlayerDamageMixin 在每次 damage HEAD 时调用 (覆盖旧值)。 */
    public static void record(String causeName) {
        lastDamageCauseName = causeName;
    }

    /**
     * 由 PlayerDamageMixin 在每次 damage HEAD 时调用: 记录"当前背包是否含
     * 珍贵物品"。最后一次 (致命) 伤害时的背包内容即死亡掉落物。
     */
    public static void recordCarriedHighValue(boolean highValue) {
        lastCarriedHighValue = highValue;
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
