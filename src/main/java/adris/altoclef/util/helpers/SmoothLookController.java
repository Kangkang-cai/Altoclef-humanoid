package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.utils.Rotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * (2026-10-05 拟人转向 v2) 把 {@link LookHelper#lookAt} 的转向目标从「一拍瞬移」
 * 改成「每渲染帧直接驱动相机 + 指数平滑」。
 *
 * v1 教训: 每 tick(20Hz) 限速一步 + ≤3° 每拍直跳 —— 渲染端只做拍间插值, 行进
 * 跟踪时每拍小跳变, 观感"卡、不丝滑"。真人鼠标是**每帧**更新朝向的, 本版对齐
 * 该模型: ClientRenderEvent(每渲染帧, 60~144Hz)直接 setYaw/setPitch, 指数平滑
 * (时间常数 tau) + 最大角速度上限, 大角度=快速甩头带 ease-out 收尾, 小角度=
 * 连续滑移, 无任何逐拍跳变。
 *
 * 仲裁语义与旧瞬移逐字节对齐:
 * ① 同一拍多次 stage = last-wins; tick 尾 {@link #tick} (applyCombatAim 之后)
 *    把本拍赢家定格为帧目标 —— applyCombatAim/immediate 在拍末登记, 天然压过
 *    任务层, 战斗瞄准每拍重登记 ⇒ 帧级持续占优, 无中途拉扯;
 * ② immediate 路径保留 baritone 直调 snap (同拍生效), 帧层随后锁定该旋转;
 * ③ 最近一次登记超过 {@link #TIMEOUT_TICKS} 拍 → 自动失活归还相机 (baritone
 *    寻路转向不受干扰), 与旧「apply 即清 target」的持有窗口同量级;
 * ④ 收敛 (<1°) 直接赋精确值 —— baritone isReallyCloseTo 阈值 0.01°, 所有
 *    isLookingAt / isCrosshairOn 闸门最终必然通过, 只是晚 2~6 帧 (~0.1s)。
 *
 * 关闭 smoothTurnEnabled 时 tick() 内回退为旧瞬移 (updateTarget per tick),
 * renderFrame 不触碰相机 —— 行为与改动前逐字节一致。
 */
public final class SmoothLookController {

    // 收敛判定: 剩余角差小于该值直接赋精确目标 (人眼不可辨, 保证闸门判定)。
    private static final double SNAP_DEG = 1.0;
    // 最近一次登记后保持帧控制的拍数上限, 超时归还相机 (sweep 类单次 lookAt
    // 需要的收敛窗口 45°/tau≈10 帧 < 10 拍, 足够)。
    private static final int TIMEOUT_TICKS = 10;
    // 单帧 dt 上限 (秒), 防卡顿帧后一步跳飞。
    private static final double MAX_DT_SEC = 0.1;

    // 本拍任务/战斗层登记的转向目标 (lookAt 写入, tick 尾消费)。
    private static Rotation _pending = null;
    // tick 尾仲裁出的帧目标; null = 相机归 baritone/无控制。
    private static Rotation _frameTarget = null;
    private static long _regTick = Long.MIN_VALUE;
    private static long _lastFrameNs = 0L;

    private SmoothLookController() {}

    /** 登记本拍转向目标 (普通 lookAt)。同一拍多次调用 = 最后一次生效。 */
    public static void stage(Rotation target) {
        _pending = target;
    }

    /**
     * 登记本拍转向目标 (immediate 通道)。调用方同时做了 baritone 同拍 snap;
     * tick 尾仲裁时本登记作为赢家定格, 帧层锁定该旋转不再拉走。
     */
    public static void stageImmediate(Rotation target) {
        _pending = target;
    }

    /**
     * tick 尾唯一仲裁点 (AltoClef.onClientTick 内, applyCombatAim 之后):
     * 把本拍最后登记定格为帧目标。任务层每拍重登记, 战斗瞄准每拍重登记且更晚
     * —— 优先级与旧 updateTarget 逐拍覆盖完全一致。
     */
    public static void tick(AltoClef mod) {
        try {
            if (_pending != null) {
                if (!mod.getModSettings().isSmoothTurnEnabled()) {
                    // 关闭时回退旧行为: 每拍瞬移下发赢家
                    mod.getClientBaritone().getLookBehavior().updateTarget(_pending, true);
                    _frameTarget = null;
                } else {
                    _frameTarget = _pending;
                    _regTick = WorldHelper.getTicks();
                }
            }
            _pending = null;
            if (_frameTarget != null && WorldHelper.getTicks() - _regTick > TIMEOUT_TICKS) {
                _frameTarget = null; // 归还相机给 baritone 寻路转向
            }
        } catch (Throwable t) {
            _pending = null;
            _frameTarget = null;
            if (t instanceof Error) throw t;
        }
    }

    /**
     * 每渲染帧驱动 (ClientRenderEvent, InGameHud.render TAIL): 读当前真实朝向,
     * 指数平滑逼近帧目标, 直接 setYaw/setPitch —— 与真人鼠标同一条更新通路
     * (帧率级), 渲染端原生插值, 丝滑无跳变。
     */
    public static void renderFrame(AltoClef mod) {
        try {
            if (_frameTarget == null || !mod.getModSettings().isSmoothTurnEnabled()) {
                _lastFrameNs = 0L;
                return;
            }
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player == null) {
                _lastFrameNs = 0L;
                return;
            }

            long now = System.nanoTime();
            double dt = _lastFrameNs == 0L ? 0.016 : Math.min((now - _lastFrameNs) / 1.0E9, MAX_DT_SEC);
            _lastFrameNs = now;

            float curYaw = player.getYaw();
            float curPitch = player.getPitch();
            // yaw 环绕归一化到 [-180, 180): 350°→10° 转 20° 而不是倒转 340°
            double dYaw = MathHelper.wrapDegrees(_frameTarget.getYaw() - curYaw);
            double dPitch = _frameTarget.getPitch() - curPitch;
            double maxAbs = Math.max(Math.abs(dYaw), Math.abs(dPitch));

            if (maxAbs <= SNAP_DEG) {
                // 收敛: 精确赋值 (闸门判定需要 <0.01°, 赋精确值一步到位)
                player.setYaw(_frameTarget.getYaw());
                player.setPitch(_frameTarget.getPitch());
                return;
            }

            // 指数平滑: alpha = 1 - e^(-dt/tau), 帧率无关; 快起慢收 ease-out
            double tau = Math.max(1.0, mod.getModSettings().getSmoothTurnTauMs()) / 1000.0;
            double alpha = 1.0 - Math.exp(-dt / tau);
            // 角速度上限: 保证"快", 大角度不会因纯指数尾而拖沓
            double maxStep = mod.getModSettings().getSmoothTurnMaxDegPerSec() * dt;
            double wantStep = maxAbs * alpha;
            // 两轴按同一比例缩放, 保持直线旋转路径 (yaw/pitch 同步到位)
            double s = wantStep > maxStep ? maxStep / wantStep : 1.0;

            float newYaw = curYaw + (float) (dYaw * alpha * s);
            float newPitch = MathHelper.clamp(
                    curPitch + (float) (dPitch * alpha * s), -90.0f, 90.0f);
            player.setYaw(MathHelper.wrapDegrees(newYaw));
            player.setPitch(newPitch);
        } catch (Throwable t) {
            _frameTarget = null;
            _lastFrameNs = 0L;
            if (t instanceof Error) throw t;
        }
    }
}
