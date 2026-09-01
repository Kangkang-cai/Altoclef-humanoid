package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SoundEventHappenedEvent;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures positioned sounds so the bot can "hear" what it cannot see.
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
}
