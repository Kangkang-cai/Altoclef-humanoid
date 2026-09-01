package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.MineDownExploreTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 卡住挥击反应 (SwingThrough Chain, 2026-08-31)。
 *
 * 挖掘/导航任务"没进度（卡住）"时，像人类一样长按几下左键，清掉 Baritone
 * 探测不到/微小却挡路的方块（草、藤蔓、草丛、装饰类等）。这些方块不在
 * Baritone 的"可破坏障碍"意识里、任务层也看不到，bot 会原地呆住。
 *
 * 设计要点：
 *  - 是【后台反应】，不是任务：只在本机输入层调用 hold/release 左键，
 *    绝不 setTask()/subTask()，不改任务优先级、不参与任务树，仲裁层面
 *    getPriority() 恒定返回 NEGATIVE_INFINITY，永远不抢占。
 *  - 独立的卡住阈值（不复用 Stuck-breaker/PlayerInteractionFixChain 判定）：
 *    自定义 = 当前主任务属于挖掘类(MineAndCollect/MineDownExploreTask/
 *    Destroy 路线)或导航类(movement)，且连续 N 个游戏 tick（默认 25 ≈1.2s）
 *    内既没有方块被破坏、且玩家水平位移 < 1 格 → 判定卡住。
 *  - 触发动作 = 模拟左键长按 SWING_HOLD_TICKS(4) tick，击打前方挡路方块；
 *    自带冷却，避免每 tick 狂点（触发一次后过 RE_SWING_COOLDOWN 才再挥）。
 *  - 只加行为+日志，不改任何既有任务/优先级数值/状态机跳转。
 *  - 绝不调用 getPriority()（避开 setTask→getPriority→setTask 递归铁律）。
 */
public class SwingThroughChain extends TaskChain {

    // 卡住阈值：N 个游戏 tick（默认 25 ≈1.2s）
    private static final int STUCK_WINDOW_TICKS = 25;
    // 挥击（长按左键）持续 tick 数（3~5，取 4）
    private static final int SWING_HOLD_TICKS = 4;
    // 卡住后重挥冷却（tick）。触发一次后即使仍卡，过一段时间（默认 5s）
    // 才允许再次挥击，避免每 tick 狂点。
    private static final long RE_SWING_COOLDOWN_TICKS = 100;
    // 卡住判定：窗口内玩家水平位移 < 1 格即视为原地（没在导航）。
    private static final double STUCK_MIN_MOVE = 1.0;
    // 导航（movement）任务包名前缀。
    private static final String MOVEMENT_PACKAGE = "adris.altoclef.tasks.movement.";

    // 卡住检测窗口的起点（游戏 tick + 起点位置）。
    private long _windowStartTick = -1;
    private Vec3d _windowStartPos = null;
    // 挥击状态。
    private boolean _swinging = false;
    private int _swingTicksLeft = 0;
    private long _lastSwingTick = Long.MIN_VALUE;

    public SwingThroughChain(TaskRunner runner) {
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
            // 后台反应绝不影响主逻辑：异常只记日志 + 松开可能按住的左键。
            Debug.logWarning("SwingThrough chain exception (ignored): " + t);
            t.printStackTrace();
            try {
                mod.getInputControls().release(Input.CLICK_LEFT);
            } catch (Exception ignored) { }
            _swinging = false;
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
            resetState();
            return;
        }

        // 正在挥击：保持按住，数到挥击时长结束松开并重置窗口，给 bot 恢复时间。
        if (_swinging) {
            if (--_swingTicksLeft <= 0) {
                mod.getInputControls().release(Input.CLICK_LEFT);
                _swinging = false;
                resetWindow();
            }
            return;
        }

        long nowTick = WorldHelper.getTicks();
        Vec3d pos = mod.getPlayer().getPos();

        // 只对挖掘/导航类主任务生效。
        if (!isMiningOrNavigating(mod)) {
            resetState();
            return;
        }
        // 打斗中不触发（别在战斗里乱挥左键）。
        if (mod.getMobDefenseChain().isInCombat()) {
            resetState();
            return;
        }
        // 正在破块 => 没卡住，重置窗口。
        if (mod.getControllerExtras().isBreakingBlock()) {
            resetWindow();
            return;
        }

        // 初始化/重置后的窗口起点。
        if (_windowStartTick < 0 || _windowStartPos == null) {
            _windowStartTick = nowTick;
            _windowStartPos = pos;
            return;
        }

        // 窗口内水平位移 >= 1 格 => 在移动，没卡住，重置窗口。
        double horizontalMove = Math.hypot(pos.x - _windowStartPos.x, pos.z - _windowStartPos.z);
        if (horizontalMove >= STUCK_MIN_MOVE) {
            resetWindow();
            return;
        }

        // 连续卡满阈值 tick，且距上次挥击已过冷却 => 触发挥击。
        long stuckForTicks = nowTick - _windowStartTick;
        if (stuckForTicks >= STUCK_WINDOW_TICKS
                && (nowTick - _lastSwingTick) >= RE_SWING_COOLDOWN_TICKS) {
            triggerSwing(mod, stuckForTicks);
        }
    }

    /**
     * 触发挥击：面向正前方挡路方块的自动可挖块（可选），然后长按左键
     * SWING_HOLD_TICKS tick，清掉草/藤蔓/草丛等探测不到的小方块。写
     * [SWING-BREAK] 日志：判卡住时的玩家 pos、当前主任务、卡住时长。
     */
    private void triggerSwing(AltoClef mod, long stuckForTicks) {
        Vec3d pos = mod.getPlayer().getPos();
        Task main = mod.getUserTaskChain().getCurrentTask();

        BlockPos front = getFrontNonAirBlock(mod);
        if (front != null) {
            LookHelper.lookAt(mod, front);
        }

        mod.getInputControls().hold(Input.CLICK_LEFT);
        _swinging = true;
        _swingTicksLeft = SWING_HOLD_TICKS;
        _lastSwingTick = WorldHelper.getTicks();

        String m = "[SWING-BREAK] stuck detected -> left-click swing. player=" + pos
                + " task=" + (main == null ? "null" : main.toString())
                + " stuckFor=" + stuckForTicks + " ticks hold=" + SWING_HOLD_TICKS;
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    /**
     * 当前主任务是否为挖掘类/导航类：沿当前正在执行的子任务链向下检查，
     * 命中 MineAndCollectTask / MineDownExploreTask / DestroyBlockTask 或
     * movement 包内任务即视为处于挖掘或导航场景。
     */
    private boolean isMiningOrNavigating(AltoClef mod) {
        if (!mod.getUserTaskChain().isActive()) return false;
        Task main = mod.getUserTaskChain().getCurrentTask();
        if (main == null) return false;
        return main.thisOrChildSatisfies(t -> {
            if (t instanceof MineAndCollectTask
                    || t instanceof MineDownExploreTask
                    || t instanceof DestroyBlockTask) {
                return true;
            }
            return t.getClass().getName().startsWith(MOVEMENT_PACKAGE);
        });
    }

    /**
     * 玩家正前方第一个非空气方块（脚部优先，其次眼部）。复制自
     * PlayerInteractionFix 的取前方方法：荷叶在脚部、藤蔓/树干在眼部。
     */
    private BlockPos getFrontNonAirBlock(AltoClef mod) {
        float yaw = mod.getPlayer().getYaw();
        int dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
        int dz = (int) Math.round(Math.cos(Math.toRadians(yaw)));
        if (dx == 0 && dz == 0) dz = 1;
        BlockPos feet = mod.getPlayer().getBlockPos();
        BlockPos frontFeet = feet.add(dx, 0, dz);
        if (!WorldHelper.isAir(mod, frontFeet)) return frontFeet;
        BlockPos frontEye = feet.add(dx, 1, dz);
        if (!WorldHelper.isAir(mod, frontEye)) return frontEye;
        return null;
    }

    private void resetWindow() {
        _windowStartTick = -1;
        _windowStartPos = null;
    }

    private void resetState() {
        resetWindow();
        _swinging = false;
        _swingTicksLeft = 0;
    }

    @Override
    protected void onStop(AltoClef mod) {
        // 保险：链停止时松开可能正按住的左键。
        if (_swinging) {
            mod.getInputControls().release(Input.CLICK_LEFT);
            _swinging = false;
        }
        resetState();
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        // 后台反应没有可被打断的任务；若正处于挥击，安全松开左键。
        if (_swinging) {
            mod.getInputControls().release(Input.CLICK_LEFT);
            _swinging = false;
        }
        resetState();
    }

    @Override
    public boolean isActive() {
        // 始终保持激活，使 getPriority() 每 tick 被仲裁调用（= 逐 tick 反应）。
        return true;
    }

    @Override
    public String getName() {
        return "Swing Through (stuck reaction)";
    }
}