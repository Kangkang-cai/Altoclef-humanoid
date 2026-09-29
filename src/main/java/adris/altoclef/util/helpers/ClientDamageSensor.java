package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

/**
 * (2026-09-29 多人服阶段一 🔴#1) 玩家伤害/死亡信号的【客户端替代通道】。
 *
 * <h3>为什么要另起一条通道</h3>
 * {@link adris.altoclef.mixins.PlayerDamageMixin} 挂在 {@code PlayerEntity#damage} 上 ——
 * 那是【服务端权威】路径：单人存档时内嵌服务端与客户端同 JVM 故能触发；
 * 连【专用服务端】后客户端 JVM 不再跑服务端逻辑，该方法永不执行
 * （javap 取证：damage 只被 ServerPlayerEntity/伤害结算链调用）。
 * 于是三路信号静默归零：死因记录、背包珍贵物快照、猪灵反向激怒记忆。
 * 本类补上前两路 + 死亡瞬间的击杀者归因。
 *
 * <h3>1.18.2 客户端能拿到什么（已逐条 javap 核实）</h3>
 * <ul>
 *   <li>{@code HealthUpdateS2CPacket} → {@code ClientPlayerEntity.updateHealth(F)}：
 *       血量变化，但【不含攻击者与死因】。</li>
 *   <li>{@code DeathMessageS2CPacket(entityId, killerId, message)} →
 *       {@code onDeathMessage}：死亡播报。{@code message} 是
 *       {@link TranslatableText}，key 形如 {@code death.attack.lava}，
 *       与旧路径 {@code DamageSource.getName()} 的后缀同口径
 *       （{@code DamageTracker} 就是用 "death.attack." + name 拼的）；
 *       {@code killerId} 为 -1 表示无攻击者。</li>
 * </ul>
 * 1.18.2 **没有** {@code EntityDamageS2CPacket}（1.19.4 才有），也没有把
 * "谁打了我" 同步给受害者的包，所以【非致命近战的攻击者】在客户端无权威来源 ——
 * 见文末"已知降级"。
 *
 * <h3>背包珍贵物快照：为什么是 1Hz 滚动而不是"受伤瞬间取样"</h3>
 * 死亡时服务端先 {@code dropInventory()} 再同步血量/死亡播报，两者的发包
 * 先后【无法保证】—— 若在"血量归零包"到达时才扫背包，可能扫到已被清空的包
 * ⇒ 误判"没带珍贵物" ⇒ 墓碑丢失（正是 2026-09-23 那次修复要避免的失败方向）。
 * 故改为【存活期间 1Hz 滚动刷新】：死亡前最后一拍的值即"死亡前持有"。
 * 死亡/死亡屏期间【不刷新】（否则会把自己清空后的背包写进去）。
 * 单人存档下 {@code PlayerDamageMixin} 的伤害瞬间取样仍然生效（口径一致，互为冗余）。
 *
 * <h3>已知降级（多人服）</h3>
 * <ul>
 *   <li>{@code HitRegistry.hasRecentlyDamagedPlayer} —— 非致命伤害的攻击者归因
 *       客户端拿不到。死亡瞬间可用 {@code killerId}（已实现），但"被看不见的
 *       骷髅射中却还没死"这类场景无信号 ⇒ {@code MobDefenseChain} 的骷髅
 *       must-kill 证据门在多人服下恒不满足（该门是硬门）。宁缺勿滥：不做
 *       "最近敌对实体"式猜测归因，否则会复活 20260924 修掉的"无缘无故锁敌"。</li>
 *   <li>猪灵反向激怒记忆（{@code onPlayerDamagedBy} 的猪灵分支）同样只在死亡
 *       瞬间补得上；非致命挨打的场景退回依赖 {@code ANGRY_AT} 客户端同步
 *       （20260915 实测该同步成立，是主信号）与金甲兜底。</li>
 * </ul>
 */
public final class ClientDamageSensor {

    private ClientDamageSensor() {
    }

    /** 死亡播报中死因 key 的前缀，与 {@code DamageSource.getName()} 的原貌一致。 */
    private static final String DEATH_ATTACK_PREFIX = "death.attack.";

    /** 背包珍贵物滚动快照周期 (tick)。1s 的陈旧度对"死亡前是否带着贵重物"完全够用。 */
    private static final int CARRY_SCAN_INTERVAL_TICKS = 20;

    /** 距下次滚动快照还有几 tick。纯计时器，无跨世界语义，不必进 WorldStateGuard。 */
    private static int _carryScanCooldown = 0;

    /** 由 {@code AltoClef#onClientTick} 每客户端 tick 调用（inGame 段内）。 */
    public static void tick() {
        if (--_carryScanCooldown > 0) return;
        _carryScanCooldown = CARRY_SCAN_INTERVAL_TICKS;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            PlayerEntity player = mc == null ? null : mc.player;
            // 死亡/死亡屏期间不刷新: 服务端此刻已清空背包，写进去会把真值覆盖成 false。
            if (player == null || !player.isAlive()) return;
            PlayerDamageCauseHolder.recordCarriedHighValue(scanCarriedHighValue(player));
        } catch (Throwable ignored) {
            // 感知器异常绝不影响主循环
        }
    }

    /**
     * 扫描背包是否含珍贵物品。判据与 {@code PlayerDamageMixin} 内联快照完全一致
     * （同一 {@link DropValuationHelper#isHighValue}），口径不得各写一份。
     */
    public static boolean scanCarriedHighValue(PlayerEntity player) {
        if (player == null) return false;
        try {
            var inventory = player.getInventory();
            int size = inventory.size();
            for (int i = 0; i < size; i++) {
                ItemStack stack = inventory.getStack(i);
                if (DropValuationHelper.isHighValue(stack)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 血量变化包到达（{@code ClientPlayNetworkHandler#onHealthUpdate} HEAD，
     * 此刻 {@code player.getHealth()} 仍是【旧值】）。
     *
     * <p>只在"活着且确实掉血"时刷新背包快照：这类死亡（岩浆 / 溺水 / 着火）
     * 都是连续掉血，最后一拍【非致命】伤害距死亡通常 ≤0.5s，比 1Hz 滚动更贴。
     * 血量归零那拍【不刷新】—— 服务端此时可能已清空背包，写进去会覆盖真值。
     */
    public static void onHealthUpdate(float newHealth) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            PlayerEntity player = mc == null ? null : mc.player;
            if (player == null) return;
            float prev = player.getHealth();
            if (newHealth <= 0f || newHealth >= prev) return;
            PlayerDamageCauseHolder.recordCarriedHighValue(scanCarriedHighValue(player));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 死亡播报到达（{@code ClientPlayNetworkHandler#onDeathMessage}，即死亡屏出现的那一刻）。
     *
     * <p>注意【不要】在这里刷新背包快照：此包到达时服务端可能已 dropInventory，
     * 写进去只会把 1Hz 滚动出来的真值覆盖成 false。
     */
    public static void onDeathMessage(int entityId, int killerId, Text message) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || mc.world == null) return;
            // 只关心自己: 死亡播报对所有死亡实体都会下发。
            if (entityId != mc.player.getId()) return;

            // ---- 1) 死因: TranslatableText key 剥前缀 ⇒ "drown"/"lava"/"inFire"...
            // 与旧 DamageSource.getName() 剥前缀的口径一致 (DeathRecoveryChain
            // 只认精确小写 "drown"/"lava", 这两种 vanilla key 后缀正好相同)。
            if (message instanceof TranslatableText) {
                String key = ((TranslatableText) message).getKey();
                if (key != null && key.startsWith(DEATH_ATTACK_PREFIX)) {
                    PlayerDamageCauseHolder.record(key.substring(DEATH_ATTACK_PREFIX.length()));
                }
            }

            // ---- 2) 击杀者归因: 死亡瞬间唯一权威的"谁打死了我"来源, 喂 HitRegistry
            // (猪灵记仇 + _recentAttackerUntil 实锤)。killerId = -1 表示无攻击者。
            if (killerId >= 0) {
                Entity killer = mc.world.getEntityById(killerId);
                if (killer != null) {
                    HitRegistry.onPlayerDamagedBy(killer);
                }
            }
        } catch (Throwable t) {
            Debug.logWarning("[CLIENT-DAMAGE] onDeathMessage 异常: " + t);
        }
    }
}
