package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SoundEventHappenedEvent;
import adris.altoclef.util.helpers.ClientDamageSensor;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures positioned sounds so the bot can "hear" what it cannot see.
 *
 * <p>(2026-09-29 多人服阶段一 🔴#1) 另挂 {@code onDeathMessage}: 专用服务端下
 * {@code PlayerEntity#damage}（PlayerDamageMixin）不再触发，死亡播报包成为
 * 客户端唯一的"我死了 + 谁杀的 + 什么死因"权威来源。见
 * {@link ClientDamageSensor} 类注释。
 */
@Mixin(ClientPlayNetworkHandler.class)
public final class ClientPlayNetworkHandlerMixin {

    @Inject(
            method = "onPlaySound",
            at = @At("HEAD")
    )
    private void onPlaySound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        EventBus.publish(new SoundEventHappenedEvent(
                packet.getSound(),
                packet.getX(),
                packet.getY(),
                packet.getZ(),
                packet.getVolume(),
                packet.getPitch()
        ));
    }

    @Inject(
            method = "onHealthUpdate",
            at = @At("HEAD")
    )
    private void onHealthUpdate(HealthUpdateS2CPacket packet, CallbackInfo ci) {
        ClientDamageSensor.onHealthUpdate(packet.getHealth());
    }

    @Inject(
            method = "onDeathMessage",
            at = @At("HEAD")
    )
    private void onDeathMessage(DeathMessageS2CPacket packet, CallbackInfo ci) {
        ClientDamageSensor.onDeathMessage(packet.getEntityId(), packet.getKillerId(), packet.getMessage());
    }

    @Inject(
            method = "onPlayerRespawn",
            at = @At("HEAD")
    )
    private void onPlayerRespawn(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        ClientDamageSensor.onPlayerRespawn();
    }
}
