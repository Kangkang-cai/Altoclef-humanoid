package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
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
    // 本拍赢家是否来自 immediate 通道 (lookAtImmediate) —— 是则短暂保留 baritone
    // target 让下一拍头应用 (同拍到位语义), 不被帧尾清空吞掉。
    private static boolean _pendingImmediate = false;
    // immediate 保留窗口 (拍): >0 期间帧尾不清空 baritone target。
    private static int _immKeepTicks = 0;
    // tick 尾仲裁出的帧目标; null = 相机归 baritone/无控制。
    private static Rotation _frameTarget = null;
    private static long _regTick = Long.MIN_VALUE;
    private static long _lastFrameNs = 0L;

    // ==== 诊断埋点 (2026-10-05 22:xx, 排查"视角小幅高频抖动", 零行为改动) ====
    // 探测器原理: 帧层每帧写入后记下该值; 下一帧进来自检 —— 若玩家朝向与上帧
    // 写入值不一致, 说明拍间有外部写入者动过相机 (嫌疑: baritone 拍头应用寻路
    // target 与帧层拉扯)。聚合 + 节流 flush 到 altoclef_diag.log。
    private static float _diagLastYaw = 0f, _diagLastPitch = 0f;
    private static boolean _diagLastValid = false;
    private static int _diagExtN = 0, _diagRetargetN = 0, _diagHoldN = 0;
    private static double _diagExtSum = 0, _diagExtMax = 0;
    private static boolean _diagEverFired = false;
    private static long _diagFlushTick = 0;

    private static void diagFlush(AltoClef mod, double nowDeg, long curTick) {
        try {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            boolean pathing = false;
            try {
                pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
            } catch (Throwable ignored) {}
            String m = String.format(
                    "[SL-DIAG] EXT n=%d retarget=%d hold=%d avgD=%.2f maxD=%.2f nowD=%.2f pathing=%s vel=%.2f" +
                            " frameT=(%.1f,%.1f) cur=(%.1f,%.1f) actTicks=%d",
                    _diagExtN, _diagRetargetN, _diagHoldN,
                    _diagExtN > 0 ? _diagExtSum / _diagExtN : 0, _diagExtMax, nowDeg,
                    pathing,
                    p != null ? (float) p.getVelocity().horizontalLength() : -1f,
                    _frameTarget != null ? _frameTarget.getYaw() : 0f,
                    _frameTarget != null ? _frameTarget.getPitch() : 0f,
                    p != null ? p.getYaw() : 0f, p != null ? p.getPitch() : 0f,
                    _frameTarget != null ? curTick - _regTick : -1);
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Throwable ignored) {}
        _diagExtN = 0;
        _diagRetargetN = 0;
        _diagHoldN = 0;
        _diagExtSum = 0;
        _diagExtMax = 0;
    }

    private static void diagTickState(AltoClef mod, String state, long curTick) {
        try {
            String m = String.format("[SL-DIAG] %s frameT=(%.1f,%.1f) tick=%d",
                    state,
                    _frameTarget != null ? _frameTarget.getYaw() : 0f,
                    _frameTarget != null ? _frameTarget.getPitch() : 0f,
                    curTick);
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Throwable ignored) {}
    }
    // ==== 诊断埋点结束 ====

    private SmoothLookController() {}

    /** 登记本拍转向目标 (普通 lookAt)。同一拍多次调用 = 最后一次生效。 */
    public static void stage(Rotation target) {
        _pending = target;
        _pendingImmediate = false;
    }

    /**
     * 登记本拍转向目标 (immediate 通道)。调用方同时做了 baritone 同拍 snap;
     * tick 尾仲裁时本登记作为赢家定格, 帧层锁定该旋转不再拉走, 并短暂保留
     * baritone target 让下一拍头完成应用 (同拍到位语义)。
     */
    public static void stageImmediate(Rotation target) {
        _pending = target;
        _pendingImmediate = true;
    }

    /**
     * tick 尾唯一仲裁点 (AltoClef.onClientTick 内, applyCombatAim 之后):
     * 把本拍最后登记定格为帧目标。任务层每拍重登记, 战斗瞄准每拍重登记且更晚
     * —— 优先级与旧 updateTarget 逐拍覆盖完全一致。
     */
    public static void tick(AltoClef mod) {
        try {
            long curTick = WorldHelper.getTicks();
            if (_pending != null) {
                if (!mod.getModSettings().isSmoothTurnEnabled()) {
                    // 关闭时回退旧行为: 每拍瞬移下发赢家
                    mod.getClientBaritone().getLookBehavior().updateTarget(_pending, true);
                    _frameTarget = null;
                } else {
                    // (诊断) retarget 计数: 目标跳变 >10° 视为一次换向
                    if (_frameTarget != null && _pending != null) {
                        double dY = Math.abs(MathHelper.wrapDegrees(_pending.getYaw() - _frameTarget.getYaw()));
                        double dP = Math.abs(_pending.getPitch() - _frameTarget.getPitch());
                        if (Math.max(dY, dP) > 10.0) _diagRetargetN++;
                    }
                    boolean wasNull = _frameTarget == null;
                    _frameTarget = _pending;
                    _regTick = curTick;
                    if (_pendingImmediate) {
                        // immediate: 保留 baritone target 2 拍, 让下一拍头应用
                        // (同拍到位语义); 窗口过后帧尾恢复清空。
                        _immKeepTicks = 2;
                        _pendingImmediate = false;
                    }
                    if (wasNull) diagTickState(mod, "take", curTick);
                }
            }
            _pending = null;
            _pendingImmediate = false;
            if (_immKeepTicks > 0) _immKeepTicks--;
            if (_frameTarget != null && curTick - _regTick > TIMEOUT_TICKS) {
                _frameTarget = null; // 归还相机给 baritone 寻路转向
                diagTickState(mod, "release", curTick);
            }
        } catch (Throwable t) {
            _pending = null;
            _pendingImmediate = false;
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
                _diagLastValid = false;
                return;
            }
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player == null) {
                _lastFrameNs = 0L;
                _diagLastValid = false;
                return;
            }

            long now = System.nanoTime();
            double dt = _lastFrameNs == 0L ? 0.016 : Math.min((now - _lastFrameNs) / 1.0E9, MAX_DT_SEC);
            _lastFrameNs = now;

            float curYaw = player.getYaw();
            float curPitch = player.getPitch();

            // (诊断) 外部写入探测: 与上帧我们写入的值比较, 不一致 => 拍间有人动过相机
            if (_diagLastValid) {
                double extY = Math.abs(MathHelper.wrapDegrees(curYaw - _diagLastYaw));
                double extP = Math.abs(curPitch - _diagLastPitch);
                double ext = Math.max(extY, extP);
                if (ext > 0.05) {
                    _diagExtN++;
                    _diagExtSum += ext;
                    _diagExtMax = Math.max(_diagExtMax, ext);
                    long curTick = WorldHelper.getTicks();
                    if (!_diagEverFired || curTick - _diagFlushTick >= 40) {
                        _diagEverFired = true;
                        _diagFlushTick = curTick;
                        diagFlush(mod, ext, curTick);
                    }
                }
            }

            // yaw 环绕归一化到 [-180, 180): 350°→10° 转 20° 而不是倒转 340°
            double dYaw = MathHelper.wrapDegrees(_frameTarget.getYaw() - curYaw);
            double dPitch = _frameTarget.getPitch() - curPitch;
            double maxAbs = Math.max(Math.abs(dYaw), Math.abs(dPitch));

            if (maxAbs <= SNAP_DEG) {
                // 收敛: 精确赋值 (闸门判定需要 <0.01°, 赋精确值一步到位)
                player.setYaw(_frameTarget.getYaw());
                player.setPitch(_frameTarget.getPitch());
                _diagLastYaw = MathHelper.wrapDegrees(_frameTarget.getYaw());
                _diagLastPitch = _frameTarget.getPitch();
                _diagLastValid = true;
                holdBackBaritone(mod, player);
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
            _diagLastYaw = MathHelper.wrapDegrees(newYaw);
            _diagLastPitch = newPitch;
            _diagLastValid = true;
            holdBackBaritone(mod, player);
        } catch (Throwable t) {
            _frameTarget = null;
            _lastFrameNs = 0L;
            _diagLastValid = false;
            if (t instanceof Error) throw t;
        }
    }

    // (2026-10-06 拉扯修复 v2) 帧尾清空 baritone LookBehavior target:
    // 回填方案 (写玩家当前位置) 被 baritone 每拍 onTick 重写自己的 target 击穿
    // (EXT 取证: 修复后仍每拍跳 ~100° 到固定 look 方向, hold=105+ 无效)。
    // 清空是结构性消除: renderFrame 帧尾是下一拍全部代码之前的最后写入者,
    // 拍头 onPlayerUpdate 见 target==null 直接 return (反编译已核), 不再写朝向。
    // ⚠️ 风险标注 (2026-10-06, 本修复新引入): baritone 内部流程 (如寻路中途
    // 打断方块) 自己每拍 updateTarget 的应用也会被清掉 —— 其"已看向方块"判定
    // 读玩家真实朝向, 由任务层 lookAt 前置 + 帧层带到位兜底; 若实跑出现寻路
    // 挖方块卡死, 需细化为仅清非寻路来源。immediate 通道经 _immKeepTicks 保留
    // 窗口豁免 (2 拍), 战斗瞄头/MLG 同拍到位不受影响。
    private static void holdBackBaritone(AltoClef mod, ClientPlayerEntity player) {
        _diagHoldN++;
        if (_immKeepTicks > 0) {
            return; // immediate 保留窗口: 不清, 让下一拍头应用
        }
        mod.getClientBaritone().getLookBehavior().updateTarget(null, true);
    }

    /**
     * (2026-10-07 拉扯修复 v3, 供 {@code LookBehaviorMixin} 调用)
     * 帧尾清空仍不够: baritone 内部流程 (寻路挖方块/放置等) 每拍在自己的
     * tick 事件里**重设** target, 随后拍中 PRE 立即应用写朝向 —— 我们的清空
     * 在帧尾 (PRE 之后), 拦不住"同拍内 重设→应用" (EXT 取证: 仍精确 20Hz,
     * cur 被拉到 baritone 挖掘 look 方向 pitch≈87°, frameT 恒定)。
     * 唯一能拦住的点就是 PRE 本身: 帧层持有相机期间 (无 immediate 保留窗口)
     * 直接 cancel LookBehavior.onPlayerUpdate —— baritone 写朝向路径整体短路,
     * 相机单一主人 = 帧层。immediate 保留窗口 (_immKeepTicks>0) 放行, 保战斗
     * 瞄头/MLG 同拍到位。与 v0「任务 lookAt 每拍覆盖 baritone target」的仲裁
     * 语义一致 (v0 中 baritone 内部 look 同样被覆盖), 仅多出平滑。
     */
    public static boolean shouldSuppressBaritoneLook() {
        return _frameTarget != null && _immKeepTicks <= 0;
    }
}
