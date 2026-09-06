package adris.altoclef.mixins;

import adris.altoclef.util.helpers.HitRegistry;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * (2026-09-06 Phase 1) 客户端攻击事件监听: 所有"发起对实体攻击"的入口
 * (bot 的 KillAura/任务层、玩家手动点击) 都走 ClientPlayerInteractionManager#attackEntity,
 * 在此把猪灵系目标喂给 HitRegistry 做激怒事件记忆。
 */
@Mixin(ClientPlayerInteractionManager.class)
public class AttackEntityMixin {

    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void altoclef$onAttackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
        HitRegistry.onClientAttack(target);
    }
}
