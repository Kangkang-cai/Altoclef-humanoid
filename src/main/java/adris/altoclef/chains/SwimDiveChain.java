package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.Vec3d;

/**
 * 游泳卡住下潜反应 (SwimDiveChain，2026-09-01)。
 *
 * Baritone 水下寻路不可靠 (见 DiveToGrabItemTask 注释)：游泳/涉水导航时，bot
 * 常被水面顶住或贴着水面/水下岩壁原地划水，XZ 坐标几秒不动却从 baritone 视角
 * 看"正在导航"。此时沿用海底拾取验证过的下潜输入模式 (面向+前进+潜行下沉+疾跑)
 * 短暂下潜几秒，重新咬合水面/绕过顶住的水面，解除"原地划水"。
 *
 * 设计要点 (与 SwingThroughChain 完全同构，后台反应非任务)：
 *  - 是【后台反应】，不是任务：只在本机输入层 hold/release，绝不 setTask()/
 *    subTask()，不改任务优先级、不参与任务树，getPriority() 恒返回
 *    NEGATIVE_INFINITY，仲裁层面永不抢占，只在水下卡住时短暂注入输入。
 *  - 触发条件（严格收窄，避免误伤正常游泳）：
 *      (1) 玩家确实在水中 (isTouchingWater())，且【不在船上】：
 *          船上注水潜行会下船，破坏 BoatCrossingTask。
 *      (2) 空气充足 (air/humidity 高)：空气快见底时下潜会加重溺水，应交给
 *          WorldSurvivalChain.handleDrowning 上浮。
 *      (3) 处于导航/挖掘类主任务（复用 movement 包名判定），非战斗。
 *      (4) 屏幕关闭 (非容器/合成/聊天/死亡)。
 *      (5) 3 秒（SWIM_STUCK_WINDOW_TICKS）内玩家水平位移 < 1 格 → 判卡住。
 *  - 触发动作 = 下潜向前游 DIVE_HOLD_TICKS 格：面向当前前进方向 + 按住
 *    FORWARD + SNEAK (下沉) + SPRINT (加速)，持续后释放；自带冷却，避免
 *    每 tick 狂潜。释放后给 bot 一段时间恢复 (RE_DIVE_COOLDOWN_TICKS)。
 *  - 只加行为+日志，不改任何既有任务/优先级数值/状态机跳转。
 *  - 绝不调用 getPriority()（避开 setTask→getPriority→setTask 递归铁律）。
 */
public class SwimDiveChain extends TaskChain {

    // 卡住阈值：N 个游戏 tick（60 ≈3s，用户要求"XZ 不动几秒"）
    private static final int SWIM_STUCK_WINDOW_TICKS = 60;
    // 下潜向前游持续 tick 数（60 ≈3s，用户要求"下潜几秒"）
    private static final int DIVE_HOLD_TICKS = 60;
    // 下潜后冷却（tick）。触发一次后即使仍卡，过一段时间才允许再次下潜。
    private static final long RE_DIVE_COOLDOWN_TICKS = 100; // 5s
    // 卡住判定：窗口内玩家水平位移 < 1 格即视为原地。
    private static final double STUCK_MIN_MOVE_SQR = 1.0 * 1.0;
    // 空气充足阈值（剩余空气百分比），低于则不触发下潜以免溺水。
    private static final double MIN_AIR_FRACTION = 0.5;
    // 导航（movement）任务包名前缀。
    private static final String MOVEMENT_PACKAGE = "adris.altoclef.tasks.movement.";

    // 卡住检测窗口起点（游戏 tick + 起点位置）。
    private long _windowStartTick = -1;
    private Vec3d _windowStartPos = null;
    // 下潜状态。
    private boolean _diving = false;
    private int _diveTicksLeft = 0;
    private long _lastDiveTick = Long.MIN_VALUE;

    // ==== [SWIM-DIVE-DIAG] 2026-09-10 零行为改动诊断 ====
    // 现场：水中卡住全靠 NAV-STUCK/超时兜底，整场日志 0 条 [SWIM-DIVE]。
    // 怀疑点：(a) 原地浮动 (water-bob) 时 isTouchingWater 逐 tick 闪烁为
    // false（11:52:02/11:52:04 两帧实证）→ 条件(1) resetState 反复清零
    // 60 tick 连续窗口，永远攒不满；(b) 空气 <50% 闸门误拦。本日志定位。
    private long _lastInWaterTick = Long.MIN_VALUE;
    private long _lastDiagTick = Long.MIN_VALUE;
    private String _lastDiagReason = null;

    public SwimDiveChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTick(AltoClef mod) {
        // 本反应在 getPriority（20Hz 仲裁）里逐 tick 执行，onTick 空实现。
    }

    @Override
    public float getPriority(AltoClef mod) {
        try {
            tickReaction(mod);
        } catch (Throwable t) {
            // 后台反应绝不影响主逻辑：异常只记日志 + 松开可能按住的键。
            Debug.logWarning("SwimDive chain exception (ignored): " + t);
            t.printStackTrace();
            try {
                mod.getInputControls().release(Input.MOVE_FORWARD);
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.SPRINT);
            } catch (Exception ignored) { }
            releaseDive(mod);
            _windowStartTick = -1;
            _windowStartPos = null;
        }
        // 恒定负无穷：永不抢占任何任务链。
        return Float.NEGATIVE_INFINITY;
    }

    private void tickReaction(AltoClef mod) {
        if (!AltoClef.inGame() || mod.getPlayer() == null) {
            resetState();
            return;
        }

        // 屏幕开着（容器/合成/聊天/死亡）不触发。
        if (MinecraftClient.getInstance().currentScreen != null) {
            releaseDive(mod);
            resetState();
            return;
        }

        // 正在下潜：保持按住，数到下潜时长结束松开并重置窗口。
        if (_diving) {
            if (--_diveTicksLeft <= 0) {
                releaseDive(mod);
                resetWindow();
            }
            // 下潜期间无条件持续注入输入（返回，不往下走检测）。
            return;
        }

        long nowTick = WorldHelper.getTicks();
        Vec3d pos = mod.getPlayer().getPos();

        // [SWIM-DIVE-DIAG] 记录最近一次在水中的 tick，用于识别"浮动瞬间脱离水"。
        boolean inWater = mod.getPlayer().isTouchingWater();
        if (inWater) {
            _lastInWaterTick = nowTick;
        }

        // 触发条件 (1)：不在水中 => 无需下潜，重置。
        if (!inWater) {
            // (2026-09-10 浮动闪烁宽限，两档) 原地浮动时 isTouchingWater 闪 false：
            // 小幅水波每 0.5-1s 一次(13:52:29-41 场次实证)，大幅上下浮动脱水可
            // 达 1-2s(20-40t)——单纯时间宽限不够。判据改为【时长+着地状态】：
            // 浮动=脱水但悬空(onGround=false)；真离开水=爬上岸即着地。
            // 脱水 ≤60t 且悬空 → 保留窗口暂停累积；着地或超时 → 真重置。
            if (_windowStartTick >= 0 && _windowStartPos != null
                    && _lastInWaterTick != Long.MIN_VALUE
                    && nowTick - _lastInWaterTick <= 60
                    && !mod.getPlayer().isOnGround()) {
                diagGate(mod, "NOT_IN_WATER_BOB_AIRBORNE");
                return;
            }
            // 只在"刚离开水"(≤60 tick) 时记录——正常陆地行走不刷屏。
            if (_lastInWaterTick != Long.MIN_VALUE && nowTick - _lastInWaterTick <= 60) {
                diagGate(mod, "NOT_IN_WATER");
            }
            resetState();
            return;
        }
        // 触发条件 (1b)：在船上 => 绝不注入（会下船破坏划船），重置。
        if (mod.getPlayer().getVehicle() instanceof BoatEntity) {
            diagGate(mod, "IN_BOAT");
            resetState();
            return;
        }
        // 触发条件 (2)：空气不足 => 交给 handleDrowning 上浮，不下潜，重置。
        if (mod.getPlayer().getMaxAir() > 0
                && (double) mod.getPlayer().getAir() / mod.getPlayer().getMaxAir() < MIN_AIR_FRACTION) {
            diagGate(mod, "AIR_LOW");
            resetState();
            return;
        }
        // 触发条件 (3)：只对导航/挖掘类主任务生效。
        if (!isNavigating(mod)) {
            diagGate(mod, "TASK_NOT_MOVEMENT");
            resetState();
            return;
        }
        // 战斗不触发（别在打斗里贸然注入潜行移动）。
        if (mod.getMobDefenseChain().isInCombat()) {
            diagGate(mod, "IN_COMBAT");
            resetState();
            return;
        }

        // 初始化/重置后的窗口起点。
        if (_windowStartTick < 0 || _windowStartPos == null) {
            _windowStartTick = nowTick;
            _windowStartPos = pos;
            diagGate(mod, "WINDOW_START");
            return;
        }

        // 窗口内水平位移 >= 1 格 => 在移动，没卡住，重置窗口。
        double dx = pos.x - _windowStartPos.x;
        double dz = pos.z - _windowStartPos.z;
        if (dx * dx + dz * dz >= STUCK_MIN_MOVE_SQR) {
            diagGate(mod, "WINDOW_RESET_MOVED");
            resetWindow();
            return;
        }

        // 连续卡满阈值 tick => 触发下潜（冷却未到则记录拦截原因）。
        // (2026-09-10 定案) 旧写法 (nowTick - _lastDiveTick) >= COOLDOWN 在
        // _lastDiveTick=Long.MIN_VALUE 哨兵时 long 减法下溢为负 → "冷却中"
        // 恒成立 → 首次下潜永不触发（现场：COOLDOWN_BLOCKING 从窗口攒满起
        // 连刷 34s，全程 0 条 SWIM-DIVE）。哨兵值必须显式豁免。
        long stuckForTicks = nowTick - _windowStartTick;
        if (stuckForTicks < SWIM_STUCK_WINDOW_TICKS) {
            // [SWIM-DIVE-DIAG] 独立 40 tick 节流，观察窗口累积进度。
            if (nowTick - _lastDiagTick >= 40) {
                _lastDiagTick = nowTick;
                writeDiag(mod, "ACCUMULATING streak=" + stuckForTicks
                        + "/" + SWIM_STUCK_WINDOW_TICKS);
            }
        } else if (_lastDiveTick != Long.MIN_VALUE
                && (nowTick - _lastDiveTick) < RE_DIVE_COOLDOWN_TICKS) {
            diagGate(mod, "COOLDOWN_BLOCKING");
        } else {
            triggerDive(mod, stuckForTicks);
        }
    }

    // ==== [SWIM-DIVE-DIAG] 诊断出口：换原因即记录，同原因最多 60 tick 一条 ====
    private void diagGate(AltoClef mod, String reason) {
        long nowTick = WorldHelper.getTicks();
        if (reason.equals(_lastDiagReason) && nowTick - _lastDiagTick < 60) return;
        _lastDiagTick = nowTick;
        _lastDiagReason = reason;
        writeDiag(mod, reason);
    }

    private void writeDiag(AltoClef mod, String tag) {
        try {
            var p = mod.getPlayer();
            if (p == null) return;
            double airFrac = p.getMaxAir() > 0 ? (double) p.getAir() / p.getMaxAir() : 1.0;
            Task main = mod.getUserTaskChain().getCurrentTask();
            String m = "[SWIM-DIVE-DIAG] gate=" + tag
                    + " inWater=" + p.isTouchingWater()
                    + String.format(" air=%.2f", airFrac)
                    + " pos=" + p.getBlockPos()
                    + " task=" + (main == null ? "null" : main.toString());
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Throwable ignored) {
            // 诊断绝不影响主逻辑
        }
    }

    /**
     * 触发下潜：面向当前前进方向，按住 FORWARD + SNEAK(下沉) + SPRINT(加速)
     * DIVE_HOLD_TICKS tick，向前下游，解除水面/岩壁原地划水。写 [SWIM-DIVE]
     * 日志：玩家 pos、当前主任务、卡住时长、下潜持续时长。
     */
    private void triggerDive(AltoClef mod, long stuckForTicks) {
        Vec3d pos = mod.getPlayer().getPos();
        // 保持当前朝向前进（yaw/pitch 不动，避免覆盖 baritone/其它链的视角控制），
        // 只注入前向+潜行下沉+疾跑，让 bot 向前下游。
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        mod.getInputControls().hold(Input.SNEAK);
        mod.getInputControls().hold(Input.SPRINT);
        _diving = true;
        _diveTicksLeft = DIVE_HOLD_TICKS;
        _lastDiveTick = WorldHelper.getTicks();

        Task main = mod.getUserTaskChain().getCurrentTask();
        String m = "[SWIM-DIVE] stuck in water -> dive+sprint forward. player=" + pos
                + " task=" + (main == null ? "null" : main.toString())
                + " stuckFor=" + stuckForTicks + " ticks dive=" + DIVE_HOLD_TICKS;
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    private void releaseDive(AltoClef mod) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.SPRINT);
        _diving = false;
        _diveTicksLeft = 0;
    }

    /**
     * 当前主任务是否为导航类：沿当前执行链向上/下检查，命中 movement 包内任务
     * 即视为处于导航场景（游泳/涉水多半伴随 GetToBlock/探索等 movement 任务）。
     */
    private boolean isNavigating(AltoClef mod) {
        if (!mod.getUserTaskChain().isActive()) return false;
        Task main = mod.getUserTaskChain().getCurrentTask();
        if (main == null) return false;
        return main.thisOrChildSatisfies(t ->
                t.getClass().getName().startsWith(MOVEMENT_PACKAGE));
    }

    private void resetWindow() {
        _windowStartTick = -1;
        _windowStartPos = null;
    }

    private void resetState() {
        resetWindow();
        _diving = false;
        _diveTicksLeft = 0;
    }

    @Override
    protected void onStop(AltoClef mod) {
        // 保险：链停止时松开可能正按住的键。
        releaseDive(mod);
        resetState();
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        releaseDive(mod);
        resetState();
    }

    @Override
    public boolean isActive() {
        // 始终保持激活，使 getPriority() 每 tick 被仲裁调用（= 逐 tick 反应）。
        return true;
    }

    @Override
    public String getName() {
        return "Swim Dive (stuck-in-water reaction)";
    }
}