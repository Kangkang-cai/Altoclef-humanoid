package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.input.Input;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-12) 非实心障碍 (azalea/草等植物) 的直挖任务。
 *
 * 背景: 会话 20260912-111853 实锤 — JumpStuckResolver 正确选中脚下
 * flowering_azalea, 但 DestroyBlockTask 走 baritone builder 流程, 而 baritone
 * 把非实心植物当"本来就可通过", builder 拒绝对它执行破坏 → 对着空转到 3s
 * 冷却, 连挖 8 次方块纹丝不动 (用户"bot 根本没有挖他")。
 *
 * 实心方块仍走 DestroyBlockTask (baritone 破坏流程是可靠的); 植物类硬度 0,
 * 直接视角对准 + 强制按住左键, 一 tick 内碎, 不经过 baritone。
 * 超时 1.5s (用户观感要求: 卡住处置节奏不得超过 1.5s)。
 */
public class InstantBreakTask extends Task {

    /** 1.5s 上限 (用户: 超时时长不要超过 1.5s, 影响观感)。 */
    private static final int TIMEOUT_TICKS = 30;

    private final BlockPos _pos;
    private int _ticks = 0;

    public InstantBreakTask(BlockPos pos) {
        _pos = pos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _ticks = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getChunkTracker().isChunkLoaded(_pos) && mod.getWorld().getBlockState(_pos).isAir()) {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            setDebugState("Instant break done");
            return null;
        }
        if (_ticks >= TIMEOUT_TICKS) {
            setDebugState("Instant break timeout");
            return null;
        }
        _ticks++;
        // 视角对准方块中心 + 强制左键 (硬度 0 的植物一 tick 即碎)。
        LookHelper.lookAt(mod, new Vec3d(_pos.getX() + 0.5, _pos.getY() + 0.5, _pos.getZ() + 0.5));
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        setDebugState("Instant breaking " + _pos.toShortString());
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (!mod.getChunkTracker().isChunkLoaded(_pos)) return true;
        return mod.getWorld().getBlockState(_pos).isAir() || _ticks >= TIMEOUT_TICKS;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof InstantBreakTask task && task._pos.equals(_pos);
    }

    @Override
    protected String toDebugString() {
        return "Instant break at " + _pos.toShortString();
    }
}
