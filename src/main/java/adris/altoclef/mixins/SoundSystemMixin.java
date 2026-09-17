package adris.altoclef.mixins;

import adris.altoclef.record.RecordManager;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * (2026-09-17 录制: 听觉通道补全) 在声音引擎的总入口截获所有真正发声的声音。
 *
 * <h3>为什么在这里, 而不是沿用 ClientPlayNetworkHandlerMixin</h3>
 * 旧路径只截 {@code PlaySoundS2CPacket} (服务端定位广播), 实测整段会话 0 条覆盖不了:
 * <ul>
 *   <li>bot 自己挖/走/吃/放方块的客户端本地音</li>
 *   <li>randomDisplayTick 的环境音 (熔炉噼啪等)</li>
 *   <li>{@code PlaySoundFromEntityS2CPacket} 的实体语音 (怪叫 —— 屏幕外威胁归因的关键)</li>
 * </ul>
 * {@code SoundSystem.play(SoundInstance)} 是以上全部的必经漏斗, 在这里截一次全齐。
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li><b>只喂录制器</b> ({@code RecordManager.onSoundPlayed}), 绝不进 SoundTracker /
 *       SoundEventHappenedEvent —— "听到怪"是 bot 的决策输入, 改了会影响行为 (RECORDING.md §7)。</li>
 *   <li>跳过 {@code isRelative} 的非空间音 (音乐/UI 音) —— 没有空间信息价值。</li>
 *   <li>全包 try/catch: 录制器任何异常都不能炸掉声音引擎。</li>
 * </ul>
 */
@Mixin(SoundSystem.class)
public final class SoundSystemMixin {

    @Inject(
            method = "play(Lnet/minecraft/client/sound/SoundInstance;)V",
            at = @At("HEAD")
    )
    private void onPlaySound(SoundInstance sound, CallbackInfo ci) {
        try {
            if (sound instanceof AbstractSoundInstance && ((AbstractSoundInstance) sound).isRelative()) {
                return; // 音乐/UI 音: 非空间, 无感知价值
            }
            RecordManager.onSoundPlayed(
                    sound.getId().getPath(),
                    sound.getCategory().getName(),
                    sound.getX(), sound.getY(), sound.getZ(),
                    sound.getVolume(), sound.getPitch());
        } catch (Throwable ignored) {
        }
    }
}
