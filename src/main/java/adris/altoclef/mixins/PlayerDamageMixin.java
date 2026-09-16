package adris.altoclef.mixins;

import adris.altoclef.util.helpers.HitRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * (20260915 挖金激怒盲区修复) 玩家受伤事件监听: 玩家被猪灵系实体打 → 喂给
 * HitRegistry.onPlayerDamagedBy 做反向激怒记忆。
 *
 * 挂点选 PlayerEntity#damage (而非 ClientPlayerEntity): 单体内置服务端与客户端
 * 共享同一 JVM/类, 服务端权威伤害必然走 ServerPlayerEntity.damage → super
 * (PlayerEntity.damage) 链, HEAD 注入稳定触发; 且 DamageSource.getAttacker()
 * 在服务端拿到的攻击者实体信息最全 (UUID 与客户端实例一致, HitRegistry 按
 * UUID 记忆, 跨侧通用)。
 *
 * 背景: 20260915-123005 场次, bot 挖金惹怒猪灵 (挖金激怒与穿金甲无关),
 * bot 全程没打过猪灵 → 正向事件记忆空; 穿着金甲 → 金甲兜底判"友好";
 * ANGRY_AT 记忆当时未被读取。三路信号全灭, 4 秒掉 16 血不还手不逃跑。
 *
 * (2026-09-16 死因记录) 另在每次受伤时把"最近一次伤害来源名"存进静态 holder,
 * 供死亡瞬间 (DeathMenuChain) 取样一次, 判断 grave 死因是否为 drown/lava。
 * 为什么用静态 volatile: damage 在网络/实体线程触发, 死亡轮询在客户端线程,
 * 跨线程读写必须保证可见性; volatile 足以覆盖"最后一次写、死亡时读一次"的场景。
 */
@Mixin(PlayerEntity.class)
public class PlayerDamageMixin {

    // (2026-09-16) 最近一次伤害来源名(已剥掉 "death.attack." 前缀), 如
    // "drown" / "lava" / "inFire" / "onFire" / "hotFloor" / "cactus" ...
    // 死亡时刻由 DeathMenuChain.consumeLastDeathCause() 取样一次(读后清空)。
    // 仅作为"死因"弱信号: 玩家死亡前的最后一击来源; 若死亡前无受伤事件则为 null。
    private static volatile String lastDamageCauseName = null;

    @Inject(method = "damage", at = @At("HEAD"))
    private void altoclef$onPlayerDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (source == null) return;
        // (2026-09-16) 先记录死因来源名(无论有无攻击者), 再走原有的攻击者记忆逻辑。
        // 1.18.2 DamageSource.getName() 形如 "death.attack.drown", 剥前缀得 "drown"。
        // 注意: 必须把记录放在 attacker 判空之前 —— 淹死/岩浆死常常没有 attacker
        // (环境伤害), 原逻辑到 "attacker == null return" 就结束了, 必须在此前取样。
        String name = source.getName();
        if (name != null && name.startsWith("death.attack.")) {
            name = name.substring("death.attack.".length());
        }
        lastDamageCauseName = name;
        Entity attacker = source.getAttacker();
        if (attacker == null) return;
        HitRegistry.onPlayerDamagedBy(attacker);
    }

    /**
     * (2026-09-16) 死亡瞬间取样一次: 返回并清空最近一次伤害来源名。
     * 读后清空是为了"每个墓只取样一次" —— 避免一次无关旧伤害来源名被后续死亡继承。
     */
    public static String consumeLastDeathCause() {
        String cause = lastDamageCauseName;
        lastDamageCauseName = null;
        return cause;
    }
}
