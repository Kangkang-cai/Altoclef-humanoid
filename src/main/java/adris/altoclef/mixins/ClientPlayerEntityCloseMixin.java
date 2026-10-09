package adris.altoclef.mixins;

import adris.altoclef.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [2026-10-09 界面换代取证·客户端主动关屏侧] 与 ClientPlayNetworkHandlerMixin 的
 * [OpenScreen] / [ServerClose] 三路配对。
 *
 * <p>现场 20261009-132123 (金锭 31/32 合成收货 38s 零点击): StorageHelper.closeScreen
 * 的 [ScreenClose] 全程零调用, 但 [OpenScreen] ~5次/秒换代 (prevHandler 合成台
 * class_1714 ↔ 背包 class_1723#0 交替) —— 关屏走了不走 StorageHelper 的另一条路:
 * 直接 {@code player.closeHandledScreen()} (mod 内如 PlaceBedAndSetSpawnTask:562、
 * 或 vanilla 自身开新界面/骑乘等)。本注入钉死"客户端主动关屏"的真实调用方。
 *
 * <p>服务端下发侧见 ClientPlayNetworkHandlerMixin 的 [ServerClose] (CloseScreenS2CPacket)。
 * 只落 altoclef_diag.log 文件, 1s 节流 + sup 计数, 与 [OpenScreen] 同款口径。
 */
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityCloseMixin {

    private static long _lastCpeCloseLogMs = 0;
    private static int _cpeCloseSuppressed = 0;

    @Inject(method = "closeHandledScreen", at = @At("HEAD"))
    private void onClientCloseHandledScreen(CallbackInfo ci) {
        try {
            long now = System.currentTimeMillis();
            if (now - _lastCpeCloseLogMs < 1000) {
                _cpeCloseSuppressed++;
                return;
            }
            StackTraceElement caller = new Throwable().getStackTrace()[1];
            var handler = MinecraftClient.getInstance().player != null
                    ? MinecraftClient.getInstance().player.currentScreenHandler : null;
            Debug.logToFile("altoclef_diag.log",
                    "[CPE-Close] syncId=" + (handler != null ? handler.syncId : -1)
                            + " handler=" + (handler != null ? handler.getClass().getSimpleName() : "null")
                            + " caller=" + caller.getClassName() + "." + caller.getMethodName()
                            + ":" + caller.getLineNumber()
                            + " sup=" + _cpeCloseSuppressed);
            _cpeCloseSuppressed = 0;
            _lastCpeCloseLogMs = now;
        } catch (Throwable ignored) {
            // 诊断自身抛错不得影响网络线程
        }
    }
}
