package adris.altoclef.mixins;

import adris.altoclef.util.helpers.SmoothLookController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * (2026-10-07 转向拉扯修复 v3) 帧层持有相机期间短路 baritone 的朝向应用。
 *
 * 时间线 (反编译 + EXT 取证链): baritone 的 tick 事件在 client tick 开头,
 * 内部流程 (寻路挖方块/放置/跟随等) 每拍 updateTarget 重设 target → 玩家
 * tick 中 PRE 无条件应用 (直接 setYaw/setPitch) → 帧尾我们的清空发生在
 * PRE 之后, 拦不住"同拍内 重设→应用" ⇒ 与帧层 20Hz 拉扯 (EXT n=40/40tick,
 * cur 被拉到挖掘 look 方向 pitch≈87°, frameT 恒定)。
 * 能拦住的唯一位置就是 PRE 本身: cancel 整个 onPlayerUpdate (PRE+POST 一并
 * 跳过; POST 在 force 模式下本就无事可做), baritone 写朝向路径整体短路。
 * immediate 保留窗口 (SmoothLookController._immKeepTicks>0) 放行, 保战斗
 * 瞄头/MLG 同拍到位。仲裁语义 = v0「任务 lookAt 每拍覆盖 baritone target」,
 * 仅多出帧级平滑。
 */
@Mixin(targets = "baritone.behavior.LookBehavior", remap = false)
public class LookBehaviorMixin {

    @Inject(method = "onPlayerUpdate", at = @At("HEAD"), cancellable = true, remap = false)
    private void altoclef$suppressLookApplicationWhileFrameLayerOwnsCamera(CallbackInfo ci) {
        try {
            if (SmoothLookController.shouldSuppressBaritoneLook()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 守卫逻辑绝不抛异常
        }
    }
}
