package adris.altoclef.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.path.IPathExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Baritone 1.18.2 崩溃防护（2026-08-21 18:19 崩溃实录）：
 * BuilderProcess.updateMovement 直接取 movements().get(position)，
 * 当路径的 movement 列表为空（路径竞态：刚被清除/重算）时抛
 * IndexOutOfBoundsException 崩掉整个客户端。
 * 这里在方法入口预检同一条件，命中则跳过本 tick 的 movement 刷新
 * （它只是进度刷新，跳过一个 tick 无害，下 tick 路径状态自会恢复）。
 */
@Mixin(targets = "baritone.process.BuilderProcess", remap = false)
public class BuilderProcessMixin {

    @Inject(method = "updateMovement", at = @At("HEAD"), cancellable = true, remap = false)
    private void altoclef$guardEmptyMovementList(CallbackInfo ci) {
        try {
            IPathExecutor exec = BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getPathingBehavior().getCurrent();
            if (exec == null) return;
            IPath path = exec.getPath();
            if (path == null || path.movements().size() <= exec.getPosition()) {
                ci.cancel();
            }
        } catch (Exception ignored) {
            // 防护逻辑本身绝不抛异常
        }
    }
}
