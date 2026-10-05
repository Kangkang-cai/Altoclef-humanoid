package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.utils.Rotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * (2026-10-05 拟人转向) 把 {@link LookHelper#lookAt} 的转向目标从「一拍瞬移」
 * 改成「每拍限速逼近」。
 *
 * 背景: baritone {@code LookBehavior} 的 force 分支每拍直接 {@code setYaw/setPitch}
 * (baritone-unoptimized-fabric-1.18.2 反编译实锤, 该 build 无 smoothLook 设置),
 * 90° 转身一帧到位, 观感生硬。本控制器在 tick 尾把「目标旋转」按自适应步长
 * (ease-out: 快起慢收) 分摊到 2~7 拍, 大角度转身不再是瞬移。
 *
 * 语义保持与旧瞬移一致的三点:
 * ① 本拍没有 lookAt 调用 → 不下发任何目标 (baritone 本就 apply 一次即清 target,
 *    调用方每拍重叫 lookAt 才生效; 这里完全沿用该契约, 不与寻路转向打架);
 * ② 每拍读玩家【真实】yaw/pitch 作为起点, 任何直调 snap (lookAtImmediate /
 *    baritone 移动转向) 改变了朝向, 下一拍平滑层自动从新位置跟随, 无状态漂移;
 * ③ 收敛时下发达【精确】目标 (baritone isReallyCloseTo 阈值 0.01°),
 *    所有 isLookingAt 闸门最终必然通过, 只是晚 2~4 拍。
 *
 * 逃生通道: {@link #suppressThisTick()} + {@link LookHelper#lookAtImmediate} —
 * 战斗瞄头/格挡朝向 (applyCombatAim)、MLG/喝药/龙战链等时间敏感路径不进平滑,
 * 一拍到位, 与旧行为逐字节一致。
 *
 * 开关: Settings.smoothTurnEnabled (默认开), smoothTurnMaxStepDeg (每拍最大角速度)。
 */
public final class SmoothLookController {

    // 收敛判定: 剩余角差小于该值时一步到位 (含 0.01° isReallyCloseTo 余量)。
    private static final double CONVERGE_DEG = 0.02;
    // 最小步长: 大角度收敛末段仍保持肉眼可见的连续运动, 不至于长尾拖沓。
    private static final double MIN_STEP_DEG = 2.0;
    // ease-out 系数: step = |Δ| × EASE, 差距越大转得越快。
    private static final double EASE = 0.45;

    // 本 tick 登记的目标 (LookHelper.lookAt 写入, tick 尾消费)。
    private static Rotation _target = null;
    private static long _targetTick = Long.MIN_VALUE;
    // immediate 通道压制标记: 本 tick 直调已 snap, 平滑层不得再覆盖。
    private static long _suppressTick = Long.MIN_VALUE;

    private SmoothLookController() {}

    /**
     * 登记本 tick 的转向目标。同一 tick 多次调用 = 最后一次生效 (与旧
     * updateTarget 直接覆盖的语义一致)。
     */
    public static void setTarget(Rotation target) {
        _target = target;
        _targetTick = WorldHelper.getTicks();
    }

    /**
     * 压制本 tick 的平滑下发 (immediate 通道已直接 snap 到位)。
     */
    public static void suppressThisTick() {
        _suppressTick = WorldHelper.getTicks();
        _target = null;
    }

    /**
     * tick 尾唯一执行点 (AltoClef.onClientTick 内, applyCombatAim 之后、
     * RecordManager 之前 —— 保持"动作已下发"的录制采样语义)。
     */
    public static void tick(AltoClef mod) {
        try {
            long now = WorldHelper.getTicks();
            if (_target == null || _targetTick != now || _suppressTick == now) {
                _target = null;
                return;
            }
            Rotation target = _target;
            _target = null; // 一次性消费: 下一拍任务若仍需要会重新 lookAt

            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player == null) return;

            if (!mod.getModSettings().isSmoothTurnEnabled()) {
                // 关闭时保持旧行为: 一拍瞬移到位
                mod.getClientBaritone().getLookBehavior().updateTarget(target, true);
                return;
            }

            float curYaw = player.getYaw();
            float curPitch = player.getPitch();
            // yaw 环绕归一化到 [-180, 180): 350°→10° 应转 20° 而不是倒转 340°
            double yawDelta = MathHelper.wrapDegrees(target.getYaw() - curYaw);
            double pitchDelta = target.getPitch() - curPitch;
            double maxAbs = Math.max(Math.abs(yawDelta), Math.abs(pitchDelta));

            if (maxAbs <= mod.getModSettings().getSmoothTurnMinAngleDeg()
                    || maxAbs <= CONVERGE_DEG) {
                // 微调 (≤3°) 直接到位: 交互延迟优先, 3° 内瞬移肉眼不可辨
                mod.getClientBaritone().getLookBehavior().updateTarget(target, true);
                return;
            }

            double maxStep = Math.max(1.0, mod.getModSettings().getSmoothTurnMaxStepDeg());
            double step = Math.min(maxAbs * EASE, maxStep);
            if (step < MIN_STEP_DEG) step = MIN_STEP_DEG;
            if (maxAbs - step < MIN_STEP_DEG) {
                // 一步到位, 避免长尾 (剩余角差已不足一个最小步长)
                mod.getClientBaritone().getLookBehavior().updateTarget(target, true);
                return;
            }

            double k = step / maxAbs;
            float newYaw = curYaw + (float) (yawDelta * k);
            float newPitch = MathHelper.clamp(curPitch + (float) (pitchDelta * k), -90.0f, 90.0f);
            mod.getClientBaritone().getLookBehavior()
                    .updateTarget(new Rotation(MathHelper.wrapDegrees(newYaw), newPitch), true);
        } catch (Throwable t) {
            // 平滑层故障绝不拖死任务: 本拍不下发, 目标已清空, 下一拍调用方重叫即恢复
            _target = null;
            if (t instanceof Error) throw t;
        }
    }
}
