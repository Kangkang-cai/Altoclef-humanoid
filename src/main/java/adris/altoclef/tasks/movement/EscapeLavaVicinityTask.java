package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LavaVicinityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-10-02) 「离开岩浆邻域」—— 作业侧避岩浆闸门的撤离动作，是 WATER-EXIT
 * ({@code GetToLandTask}) 的岩浆对应物。
 *
 * <p>与 {@link EscapeFromLavaTask} 的分工（**勿合并**）：
 * <ul>
 *   <li>{@code EscapeFromLavaTask} = 已经**泡在岩浆里**之后的求生（prio 100，
 *       由 WorldSurvivalChain 派发，onStart 会开 allowSwimThroughLava）；</li>
 *   <li>本任务 = **还没泡进去**时，被作业侧闸门（DestroyBlockTask）判出
 *       "人站在岩浆邻域"后主动撤出。此时绝不能开 allowSwimThroughLava
 *       （那条不变量见 AltoClef.tickLavaSwimSync：从安全位置规划穿岩浆 =
 *       自杀式寻路）。</li>
 * </ul>
 *
 * <p>为什么不能直接复用 EscapeFromLavaTask：它的 {@code isFinished} 判据是
 * "不在岩浆里 && 不在着火"，人没泡进去时立刻为真 → 撤离任务秒完成 → 作业侧
 * 立刻重试 → 原地死循环。
 *
 * <p>为什么不能直接复用 GetToBlockTask：它 {@code _wander = true}，卡住时
 * 10 格随机 wander —— 在岩浆邻域里随机选点等于朝岩浆送（同 EscapeFromLavaTask
 * "do NOT ever wander" 的教训）。本任务 {@code super(false)} 硬关 wander。
 *
 * <p>完成判据 = 「邻域无岩浆」持续 {@link #CLEAR_CONFIRM_SEC} 秒（滞回防抖：
 * 刚离开一步又被判危险会造成触发↔完成横跳）。
 *
 * <p>出口（铁律：hold 必须有出口）：总超时 {@link #TOTAL_TIMEOUT_TICKS} → 放弃 +
 * static 冷却 {@link #GIVEUP_COOLDOWN_TICKS}，冷却期内调用方不得再派（并把该目标
 * 拉黑，见 DestroyBlockTask 的 LAVA-EXIT 段）。极端地形（被岩浆围死）下不会焊死。
 */
public class EscapeLavaVicinityTask extends CustomBaritoneGoalTask {

    /** 脱离危险后需持续保持这么久才算完成（滞回）。 */
    private static final double CLEAR_CONFIRM_SEC = 1.5;
    /** 总超时：20s 还没撤出去视为链路走不通。 */
    private static final int TOTAL_TIMEOUT_TICKS = 20 * 20;
    /** 放弃后的静默期（static：任务实例会被作业侧频繁重建，实例层冷却无效）。 */
    private static final int GIVEUP_COOLDOWN_TICKS = 20 * 30;

    private static long _giveUpUntilTick = 0;

    private final TimerGame _clearTimer = new TimerGame(CLEAR_CONFIRM_SEC);
    private int _startTick = -1;
    private boolean _started = false;
    private boolean _gaveUp = false;

    public EscapeLavaVicinityTask() {
        // 绝不 wander：岩浆邻域里随机选点可能正对岩浆。
        super(false);
    }

    /** 触发条件层冷却：冷却期内调用方不应再派本任务。 */
    public static boolean isOnCooldown() {
        return WorldHelper.getTicks() < _giveUpUntilTick;
    }

    public boolean hasGivenUp() {
        return _gaveUp;
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        _started = true;
        _gaveUp = false;
        _clearTimer.reset();
        _startTick = WorldHelper.getTicks();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_gaveUp) {
            _giveUpUntilTick = WorldHelper.getTicks() + GIVEUP_COOLDOWN_TICKS;
        }
        super.onStop(mod, interruptTask);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // onStart 前不得判完成（BaseTimer 初始态问题，同 EatItemTask/DestroyBlockTask 先例）。
        if (!_started) return false;
        if (LavaVicinityHelper.playerInLavaVicinity(mod)) {
            _clearTimer.reset();
            return false;
        }
        if (_clearTimer.elapsed()) {
            return true;
        }
        // 已脱险但还在确认窗内时，顺便查总超时（防"撤离途中卡在危险区"永不结束）。
        if (_startTick >= 0 && WorldHelper.getTicks() - _startTick > TOTAL_TIMEOUT_TICKS) {
            _gaveUp = true;
            return true;
        }
        return false;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new LavaVicinityGoal();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EscapeLavaVicinityTask;
    }

    @Override
    protected String toDebugString() {
        return "Leaving lava vicinity";
    }

    /**
     * 目标 = 「不在岩浆邻域」的任意格（baritone 会挑最近的满足格）。
     * 判据与闸门同源（{@link LavaVicinityHelper}），不另立口径。
     */
    private static class LavaVicinityGoal implements Goal {

        @Override
        public boolean isInGoal(int x, int y, int z) {
            var world = MinecraftClient.getInstance().world;
            if (world == null) return true;
            return !LavaVicinityHelper.hasLavaVicinity(world, new BlockPos(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            var world = MinecraftClient.getInstance().world;
            if (world == null) return 0;
            return LavaVicinityHelper.hasLavaVicinity(world, new BlockPos(x, y, z)) ? 100 : 0;
        }
    }
}
