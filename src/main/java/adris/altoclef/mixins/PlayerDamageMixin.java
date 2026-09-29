package adris.altoclef.mixins;

import adris.altoclef.util.helpers.ClientDamageSensor;
import adris.altoclef.util.helpers.HitRegistry;
import adris.altoclef.util.helpers.PlayerDamageCauseHolder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

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
 * (2026-09-29 多人服阶段一 🔴#1 现状说明) 本条挂点【只在单人存档生效】——
 * 专用服务端下本方法所在的类不在客户端 JVM 执行, 注入永不触发。客户端替代
 * 通道见 {@link adris.altoclef.util.helpers.ClientDamageSensor}: 死因 + 死亡
 * 击杀者 + 背包珍贵物 1Hz 滚动快照。本 Mixin 保留, 单人路径口径不变;
 * 已知多人降级(非致命伤害的攻击者归因)见该类注释。
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
        // (2026-09-29 死因取证升级) source.getName() 只到 "mob"/"drown" 这种粗类, 现场真正
        // 需要的是"谁打的、贴多近、这一下掉多少血"。把这三样串成一条留档 (环形 12 条),
        // 死亡瞬间由 DeathMenuChain 一次性导出到日志。
        // 现场依据 20260929-222238: grave 记录只有 `cause=mob`, 而录制里有
        // [MOB-DIAG] nearestThreat=class_1642@hz=1.3 (僵尸贴脸) + 连续 8 次 -2.5 ⇒
        // 日志粒度不足以定位, 只能回头挖录制。补这一条后日志自身即可自证。
        PlayerEntity self = (PlayerEntity) (Object) this;
        Entity attacker = source.getAttacker();
        String attackerDesc = "none";
        if (attacker != null) {
            String id;
            try {
                id = Registries.ENTITY_TYPE.getId(attacker.getType()).toString();
            } catch (Throwable t) {
                // 兜底: 拿不到注册名就用运行时类名 (生产环境为 intermediary, 如 class_1642)
                id = attacker.getClass().getSimpleName();
            }
            attackerDesc = String.format(Locale.ROOT, "%s@%.1f", id, attacker.distanceTo(self));
        }
        PlayerDamageCauseHolder.recordDamageTrail(String.format(Locale.ROOT,
                "hp%.1f -%.1f %s by %s", self.getHealth(), amount, name, attackerDesc));
        // (2026-09-23) 死亡屏上客户端实体/区块被卸载, 无法扫描掉落物
        // (MC-169113/MC-254610)。改为在伤害 HEAD (背包尚未掉落) 快照:
        // 当前背包任一槽位是否含珍贵物品。每次受伤都刷新, 致命伤时的快照
        // 即死亡掉落内容。环境伤害 (drown/lava/饥饿...) 同样走到这里。
        // (2026-09-29) 扫描实现移到 ClientDamageSensor, 与多人通道共用同一判据。
        PlayerDamageCauseHolder.recordCarriedHighValue(ClientDamageSensor.scanCarriedHighValue(self));
        if (attacker == null) return;
        HitRegistry.onPlayerDamagedBy(attacker);
    }
}
