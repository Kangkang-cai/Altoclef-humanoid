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
 * 视角对准 + 准星命中后强制左键 (人类式左键: 平滑转头途中不出手, 见 onTick
 * 注释), 不经过 baritone。超时 1.5s (用户观感要求: 卡住处置节奏不得超过 1.5s)。
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
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            setDebugState("Instant break timeout");
            return null;
        }
        _ticks++;
        // 视角对准方块中心 (baritone 平滑转头, 多 tick 渐进)。
        LookHelper.lookAt(mod, new Vec3d(_pos.getX() + 0.5, _pos.getY() + 0.5, _pos.getZ() + 0.5));
        // (2026-09-12 会话 20260912-120658 实锤) 人类式左键纪律: 准星射线真正
        // 命中目标方块才出手。旧版同 tick 强制左键 → 攻击发生在转头途中, 射线
        // 打穿花丛命中背后的方块 (用户目击"瞄准花草左键, 打到花后面的东西")。
        // 未瞄准时必须显式松开, 防上一 tick 的强制状态误击别的目标。
        if (LookHelper.isLookingAt(mod, _pos)) {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        } else {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        }
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
