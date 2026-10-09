package adris.altoclef.mixins;

import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SoundEventHappenedEvent;
import adris.altoclef.util.helpers.ClientDamageSensor;
import adris.altoclef.util.helpers.WorldIdentity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
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

    // [2026-10-07 界面换代取证] 见下方 onOpenScreen 注释。1s 节流 + 压制计数。
    private static long _lastOpenScreenLogMs = 0;
    private static int _openScreenSuppressed = 0;

    /**
     * (2026-10-07 合成界面反复换代取证) 现场 20261007-171943: 合成死循环期间
     * `StorageHelper.closeScreen()` 零调用 (diag.log `[ScreenClose]` 末条 19:17:31),
     * 但 craftclick.log `[CraftFill]` 的 syncId 从 47 一路涨到 100 再卷回 1..6
     * (稳定 4 次/秒) —— 说明 `player.currentScreenHandler` 在被反复替换。
     * handler 替换在客户端只有一条入口: 服务端下发 `OpenScreenS2CPacket`
     * (`ClientPlayNetworkHandler.onOpenScreen`)。本注入把换代时刻与 syncId 直接记进
     * diag.log, 用于判定"谁在反复重开容器"; 配上 tasks.log 的时间戳即可对齐到拍。
     *
     * <p>刻意不走 Debug.logMessage (会刷到聊天栏), 只落文件; 1s 节流并用 sup 计数
     * 保留被压制的实际次数, 既能看出换代频率又不刷爆日志。
     */
    @Inject(
            method = "onOpenScreen",
            at = @At("HEAD")
    )
    private void onOpenScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        try {
            long now = System.currentTimeMillis();
            if (now - _lastOpenScreenLogMs < 1000) {
                _openScreenSuppressed++;
                return;
            }
            String cur = "null";
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.player.currentScreenHandler != null) {
                cur = client.player.currentScreenHandler.getClass().getSimpleName()
                        + "#" + client.player.currentScreenHandler.syncId;
            }
            Debug.logToFile("altoclef_diag.log",
                    "[OpenScreen] syncId=" + packet.getSyncId()
                            + " sup=" + _openScreenSuppressed
                            + " type=" + packet.getScreenHandlerType()
                            + " name=" + packet.getName().getString()
                            + " prevHandler=" + cur);
            _openScreenSuppressed = 0;
            _lastOpenScreenLogMs = now;
        } catch (Throwable ignored) {
            // 诊断自身抛错不得影响网络线程
        }
    }

    // [2026-10-09 界面换代取证·服务端侧] 服务端主动关屏 (CloseScreenS2CPacket,
    // 1.18.2 yarn 方法名 onCloseScreen, 经映射 jar javap 核实) —— 客户端静默把
    // currentScreenHandler 换回 playerScreenHandler, 不经任何 mod 代码。1009 金锭
    // 现场 [OpenScreen] prevHandler 每次换代前都是 class_1723#0, 若换代由服务端
    // 关屏驱动则只有这里能看到。与 [OpenScreen]/[CPE-Close] 三路配对, 同款 1s 节流。
    private static long _lastServerCloseLogMs = 0;
    private static int _serverCloseSuppressed = 0;

    @Inject(
            method = "onCloseScreen",
            at = @At("HEAD")
    )
    private void onServerCloseScreen(CloseScreenS2CPacket packet, CallbackInfo ci) {
        try {
            long now = System.currentTimeMillis();
            if (now - _lastServerCloseLogMs < 1000) {
                _serverCloseSuppressed++;
                return;
            }
            String cur = "null";
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.player.currentScreenHandler != null) {
                cur = client.player.currentScreenHandler.getClass().getSimpleName()
                        + "#" + client.player.currentScreenHandler.syncId;
            }
            Debug.logToFile("altoclef_diag.log",
                    "[ServerClose] windowId=" + packet.getSyncId()
                            + " sup=" + _serverCloseSuppressed
                            + " prevHandler=" + cur);
            _serverCloseSuppressed = 0;
            _lastServerCloseLogMs = now;
        } catch (Throwable ignored) {
            // 诊断自身抛错不得影响网络线程
        }
    }
}
