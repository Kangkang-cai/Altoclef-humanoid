package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SoundEventHappenedEvent;
import adris.altoclef.util.helpers.ClientDamageSensor;
import adris.altoclef.util.helpers.WorldIdentity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.text.Text;
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

    /**
     * (2026-10-07 服务器世界重建隔离) 记录专用服的世界种子标识。
     *
     * <p>专用服下客户端拿不到存档目录名, 只按服务器地址做记忆 key ⇒ 服务器
     * `world/` 删掉重建后 key 不变, 上个世界的地标/传送物/自挖/洞穴/堡垒记忆
     * 整份被继承。`sha256Seed` 由服务端 `BiomeAccess.hashSeed(getSeed())` 生成
     * ⇒ 换世界必变。详见 {@link WorldIdentity} 类注释第 4 条。
     */
    @Inject(
            method = "onGameJoin",
            at = @At("HEAD")
    )
    private void onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        WorldIdentity.onGameJoin(packet.sha256Seed());
    }

    /** 断开时清空种子缓存, 防回主菜单后残留旧服种子被下一次连接早期误用。 */
    @Inject(
            method = "onDisconnected",
            at = @At("HEAD")
    )
    private void onDisconnected(Text reason, CallbackInfo ci) {
        WorldIdentity.onServerDisconnected();
    }
}
