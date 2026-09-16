package adris.altoclef.mixins;

import adris.altoclef.util.helpers.HitRegistry;
import adris.altoclef.util.helpers.PlayerDamageCauseHolder;
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
 * (2026-09-16 死因记录) 每次受伤时把"最近一次伤害来源名"写进普通类
 * PlayerDamageCauseHolder (不放本 Mixin 类 —— Mixin 会把非 private 方法合并进
 * 目标类, public static 辅助方法会被拒应用导致启动崩溃, 19:06 实锤),
 * 供死亡瞬间 DeathMenuChain 取样一次, 判断 grave 死因是否为 drown/lava。
 */
@Mixin(PlayerEntity.class)
public class PlayerDamageMixin {

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
        PlayerDamageCauseHolder.record(name);
        Entity attacker = source.getAttacker();
        if (attacker == null) return;
        HitRegistry.onPlayerDamagedBy(attacker);
    }
}
