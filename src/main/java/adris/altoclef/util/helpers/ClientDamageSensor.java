package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.registry.Registry;

import java.util.Locale;

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
 *       {@link TranslatableText}，key 形如 {@code death.attack.lava}
 *       （摔死系是 {@code death.fell.*}，见 {@link #causeFromKey}），
 *       {@code killerId} 为 -1 表示无攻击者。</li>
 *   <li>{@code PlayerRespawnS2CPacket} → {@code onPlayerRespawn}：复活落地。
 *       捡尸链靠它判断"客户端坐标已从尸体位置换成重生点"（死亡当帧的
 *       客户端玩家坐标仍是尸体位置，据此判"已到墓碑"会假到达）。</li>
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

    /** 摔死系 key 前缀（{@code death.fell.accident.* / assist(.item) / finish(.item) / killer}）。 */
    private static final String DEATH_FELL_PREFIX = "death.fell.";

    /** 背包珍贵物滚动快照周期 (tick)。1s 的陈旧度对"死亡前是否带着贵重物"完全够用。 */
    private static final int CARRY_SCAN_INTERVAL_TICKS = 20;

    /** 距下次滚动快照还有几 tick。纯计时器，无跨世界语义，不必进 WorldStateGuard。 */
    private static int _carryScanCooldown = 0;

    // =========================================================================
    // (2026-09-29 多人首测 复活时序) 重生序列号 —— 供捡尸链判断"重生传送已生效"。
    //
    // 现场 20260929-181904: 死亡当帧(复活包未应用)客户端玩家仍在尸体坐标,
    // DeathRecoveryTask 的 GOTO 算出 dist=0.0 判"已到达" → 抢跑扫描 EMPTY →
    // 当场 FINISH CLEAR 清碑, 掉落物永久丢失(整链 4ms 跑完)。修法见
    // DeathRecoveryChain 的"复活落地前不激活"闸门, 数据源就是这里。
    // =========================================================================

    /** 客户端处理过 {@code PlayerRespawnS2CPacket} 的次数。 */
    private static volatile int _respawnSeq = 0;
    /** 最近一次死亡播报到达时的 {@link #_respawnSeq}（-1 = 本会话还没死过）。 */
    private static volatile int _respawnSeqAtLastDeath = -1;
    /** 最近一次重生落地时的世界 tick（-1 = 未知）。 */
    private static volatile long _lastRespawnGameTick = -1;

    /** 重生包到达（{@code ClientPlayNetworkHandler#onPlayerRespawn}）。 */
    public static void onPlayerRespawn() {
        try {
            _respawnSeq++;
            MinecraftClient mc = MinecraftClient.getInstance();
            _lastRespawnGameTick = (mc != null && mc.world != null) ? mc.world.getTime() : -1;
        } catch (Throwable ignored) {
        }
    }

    /** 自最近一次死亡以来，客户端是否已处理过复活包。 */
    public static boolean respawnSeenSinceLastDeath() {
        return _respawnSeqAtLastDeath >= 0 && _respawnSeq > _respawnSeqAtLastDeath;
    }

    /** 距最近一次复活经过了多少游戏 tick；未知返回 -1（调用方按"已稳定"处理，避免硬锁）。 */
    public static long ticksSinceLastRespawn() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || _lastRespawnGameTick < 0) return -1L;
        return mc.world.getTime() - _lastRespawnGameTick;
    }

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
            // (2026-09-29 死因取证升级) 多人服下 PlayerDamageMixin 永不触发, 掉血节奏
            // 只能从血量变化包还原 (1.18.2 没有 EntityDamageS2CPacket, 攻击者无权威
            // 来源 ⇒ 只记血量, 不猜归因)。当前拍 player.getHealth() 仍是旧值, 故差值可信。
            // 现场 20260929-222238: 20 血被僵尸连击 8 次 ×2.5, 日志里只留 `cause=mob`
            // 一行, 掉血序列只能靠录制反推 —— 补这一条后 diag 自身即可自证。
            PlayerDamageCauseHolder.recordDamageTrail(String.format(Locale.ROOT,
                    "hp%.1f -%.1f (client, attacker-unattributed)", prev, prev - newHealth));
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

            // 记下"本次死亡发生在第几次重生之前" —— 捡尸链据此判断复活包是否已到达。
            _respawnSeqAtLastDeath = _respawnSeq;

            // ---- 1) 死因 ----
            // (2026-09-29 多人首测 cause=null 根因) 死因 key 不止 death.attack.* 一族:
            // 摔死是 death.fell.*(accident/assist/finish/killer), 旧口径只认
            // death.attack. 前缀 → 摔死不写入 → 消费端读到 null; 更糟的是
            // 【残留】上一次的死因(实测: 18:20 虚空死写入 outOfWorld, 18:22:11
            // 摔死未覆盖 → 18:22:13 墓碑记下 cause=outOfWorld)。
            // 现在: 认得的族归一化, 不认得的也【必定覆盖】(解析不出就写 null),
            // 保证"每个墓的死因只属于本次死亡"。
            String cause = null;
            if (message instanceof TranslatableText) {
                cause = causeFromKey(((TranslatableText) message).getKey());
            }
            PlayerDamageCauseHolder.record(cause);

            // ---- 1.5) (2026-10-04 bridge 死因事件) 死亡播报原文快照, 供 bridge death
            // 事件带上死因。就在这里记: 此处同时握有播报原文 + 维度 + 坐标, 不用第二次解析。
            try {
                String deathDesc = String.format(Locale.ROOT, "%s（%s %d,%d,%d）",
                        message.getString(),
                        mc.world.getRegistryKey().getValue(),
                        mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
                PlayerDamageCauseHolder.recordDeathDescription(deathDesc);
            } catch (Throwable ignored) {
            }

            // ---- 2) 击杀者归因: 死亡瞬间唯一权威的"谁打死了我"来源, 喂 HitRegistry
            // (猪灵记仇 + _recentAttackerUntil 实锤)。killerId = -1 表示无攻击者。
            if (killerId >= 0) {
                Entity killer = mc.world.getEntityById(killerId);
                if (killer != null) {
                    HitRegistry.onPlayerDamagedBy(killer);
                }
            }

            // ---- 3) (2026-09-29 死因取证升级) 一行汇总, 落到主分析面 diag ----
            // 为什么在这里: 本类是全环境唯一同时握有【死因 key + killerId + 死亡播报原文 +
            // 掉血流水】的地方; 而多人服下 PlayerDamageMixin 永不触发, 且现有诊断分散在
            // death_recovery.log(只有 `cause=mob` 的粗类) 与 diag.log(1Hz 的 MOB-DIAG /
            // FOOD-DIAG) 两处 —— 20260929-222238 首轮分析就因为"死因不在 diag"而漏读,
            // 最后只能回头挖录制才确认是僵尸连击致死。补这一行后日志自身即可自证。
            // 只写诊断, 不改任何判据。
            try {
                StringBuilder msgArgs = new StringBuilder();
                if (message instanceof TranslatableText) {
                    for (Object arg : ((TranslatableText) message).getArgs()) {
                        if (arg instanceof Text) {
                            if (msgArgs.length() > 0) {
                                msgArgs.append(",");
                            }
                            msgArgs.append(((Text) arg).getString());
                        }
                    }
                }
                String killerDesc = "none";
                if (killerId >= 0) {
                    Entity killer = mc.world.getEntityById(killerId);
                    if (killer != null) {
                        killerDesc = String.format(Locale.ROOT, "%s@%.1f name=%s",
                                Registry.ENTITY_TYPE.getId(killer.getType()),
                                killer.distanceTo(mc.player),
                                killer.getName().getString());
                    } else {
                        killerDesc = "id=" + killerId + "(notLoaded)";
                    }
                }
                Debug.logToFile("altoclef_diag.log", String.format(Locale.ROOT,
                        "[DEATH] dim=%s pos=%d,%d,%d cause=%s killer=%s killerId=%d msg=\"%s\" args=[%s] recentDamages=[%s]",
                        mc.world.getRegistryKey().getValue(),
                        mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(),
                        cause, killerDesc, killerId, message.getString(), msgArgs,
                        PlayerDamageCauseHolder.peekRecentDamageTrail()));
            } catch (Throwable t) {
                Debug.logWarning("[CLIENT-DAMAGE] [DEATH] 汇总写出失败: " + t);
            }
        } catch (Throwable t) {
            Debug.logWarning("[CLIENT-DAMAGE] onDeathMessage 异常: " + t);
        }
    }

    /**
     * 死因翻译 key → 与旧 {@code DamageSource.getName()} 剥前缀同口径的短名。
     *
     * <ul>
     *   <li>{@code death.attack.lava} → {@code lava}（DeathRecoveryChain 只认精确
     *       {@code drown}/{@code lava}，这两个 vanilla key 后缀正好相同）；</li>
     *   <li>{@code death.fell.accident.generic} / {@code death.fell.assist...} 等
     *       摔死系一律归一为 {@code fall}（"从高处摔了下来" 就是 fell.accident）；</li>
     *   <li>其余 → 剥掉 {@code death.} 前缀的原样返回；完全给不出 key（例如服务端
     *       发的是字面文本）→ {@code null}，交给调用方覆盖写入。</li>
     * </ul>
     */
    private static String causeFromKey(String key) {
        if (key == null || key.isEmpty()) return null;
        if (key.startsWith(DEATH_ATTACK_PREFIX)) {
            return key.substring(DEATH_ATTACK_PREFIX.length());
        }
        if (key.startsWith(DEATH_FELL_PREFIX)) {
            return "fall";
        }
        if (key.startsWith("death.")) {
            return key.substring("death.".length());
        }
        return key;
    }
}
