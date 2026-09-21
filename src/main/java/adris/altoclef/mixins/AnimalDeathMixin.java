package adris.altoclef.mixins;

import adris.altoclef.util.helpers.AnimalMemoryJanitor;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * (2026-09-21 杀死动物清记忆) 动物死亡事件监听: 任何 LivingEntity 死亡
 * (bot 杀的 / 狼咬的 / 摔死的, 服务端权威) → {@link AnimalMemoryJanitor}
 * → WorldMemory 清击杀点附近的同类型动物记忆。
 *
 * <p>挂点选 LivingEntity#onDeath (而非各类实体的客户端移除信号): 单体内置
 * 服务端与客户端共享同一 JVM, 服务端死亡必然走 onDeath, HEAD 注入稳定触发,
 * 且此刻实体坐标仍有效 (death 事件里 pos 未被清除)。
 *
 * <p>背景: 找羊链 (CollectWoolTask → KillAndLootTask → BiasedExploreTask)
 * 的探索方向由 WorldMemory 动物记忆决定, 而 _animalMemory 只有 remember
 * 没有 forget —— 羊被杀光后"羊坟场"坐标永久留档, bot 被记忆点拽回村里
 * 逐点爬, 净位移≈0 (20260921-112922 场 BiasStep 日志实锤)。
 */
@Mixin(LivingEntity.class)
public class AnimalDeathMixin {

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void altoclef$onLivingDeath(DamageSource source, CallbackInfo ci) {
        AnimalMemoryJanitor.onEntityDeath((LivingEntity) (Object) this);
    }
}
