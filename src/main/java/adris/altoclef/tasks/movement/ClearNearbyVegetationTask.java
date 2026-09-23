package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.JumpObstructionHelper;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.input.Input;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * (2026-09-23) 近场植物清场任务。
 *
 * 背景实锤 (会话 20260923-081029, 08:18:36): bot 被双格高草丛卡住 (feet/up1
 * 均为 tall_grass), {@link InstantBreakTask} 只赌一块、出手当拍 baritone 平滑
 * 转头还没跟上 (眼睛本身就在目标方块内), 准星实际没对准草丛 → 打空, 租约 50ms
 * 即 done 释放不重试 → 拖到 28.9s 任务超时。
 *
 * 用户拍板策略: 不赌"挖哪块、这 tick 转没转过去" —— 把近场
 * (x/z ±{@link JumpObstructionHelper#VEGETATION_RADIUS}, y=脚下-1..头+2)
 * 所有白名单植物【逐块清干净】, 每块以"方块真的变空气"为推进证据, 集合每 tick
 * 从当前位置重算, 清空才结束。单块超时则跳过 (本任务实例内不重试), 由调用方
 * 总租约兜底。
 *
 * 实心方块不在本任务职责 (仍走 DestroyBlockTask); 白名单与闸门见
 * {@link JumpObstructionHelper#isClearableVegetation}。
 */
public class ClearNearbyVegetationTask extends Task {

    /** 单块最长处置时间 (1s): 瞄不上/打不掉就跳过, 不被一块卡死。 */
    private static final int PER_BLOCK_TIMEOUT_TICKS = 20;

    private BlockPos _target = null;
    private int _targetTicks = 0;
    /** 当前块准星实际命中 tick 数 (诊断用: 0 = 一直没瞄上)。 */
    private int _aimTicks = 0;
    /** 本次清场已清除块数 (诊断用)。 */
    private int _clearedCount = 0;
    /** 单块超时跳过的位置: 本实例内不再选, 避免反复选同一块。 */
    private final HashSet<BlockPos> _skipped = new HashSet<>();
    /** 清场起点 (诊断用, 不随移动更新)。 */
    private BlockPos _anchor = null;

    @Override
    protected void onStart(AltoClef mod) {
        _target = null;
        _targetTicks = 0;
        _aimTicks = 0;
        _clearedCount = 0;
        _skipped.clear();
        _anchor = mod.getPlayer().getBlockPos();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        List<BlockPos> vegetation = JumpObstructionHelper.collectVegetation(mod, feet);
        vegetation.removeAll(_skipped);
        if (vegetation.isEmpty()) {
            releaseClick(mod);
            setDebugState("Vegetation cleared.");
            Debug.logToFile("altoclef_diag.log", "[VegSweep] done cleared=" + _clearedCount
                    + " skipped=" + _skipped.size() + " anchor=" + _anchor
                    + " end=" + feet);
            return null;
        }

        // 当前目标已消失/不在近场 → 选下一块。
        if (_target == null || !vegetation.contains(_target)) {
            _target = pickNext(vegetation, feet);
            _targetTicks = 0;
            _aimTicks = 0;
        }

        _targetTicks++;
        // 视角对准方块中心 (baritone 平滑转头, 多 tick 渐进)。
        LookHelper.lookAt(mod, new Vec3d(_target.getX() + 0.5, _target.getY() + 0.5, _target.getZ() + 0.5));
        // 人类式左键纪律 (同 InstantBreakTask): 准星射线真正命中目标才出手,
        // 未命中必须显式松开, 防止打到草丛背后/侧面的方块。
        if (LookHelper.isLookingAt(mod, _target)) {
            _aimTicks++;
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        } else {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        }

        // 推进证据: 目标真的变空气 → 下一块 (下一拍重选)。
        if (mod.getChunkTracker().isChunkLoaded(_target)
                && mod.getWorld().getBlockState(_target).isAir()) {
            _clearedCount++;
            releaseClick(mod);
            _target = null;
            return null;
        }

        if (_targetTicks >= PER_BLOCK_TIMEOUT_TICKS) {
            Debug.logToFile("altoclef_diag.log", "[VegSweep] skip " + _target
                    + " block=" + blockName(mod) + " after " + _targetTicks + "t (aim "
                    + _aimTicks + "t): 1s 没清掉");
            _skipped.add(_target.toImmutable());
            releaseClick(mod);
            _target = null;
        }

        setDebugState("Clearing vegetation " + (_target == null ? "?" : _target.toShortString())
                + " (cleared " + _clearedCount + ")");
        return null;
    }

    /**
     * 选块: 离玩家最近优先 (同格的脚/头先清 —— 真正卡人的块); 距离相同取更低的
     * 一格 (双格植物先打下半截, 半截破了整株消失); 再以坐标兜底保证确定性。
     */
    private BlockPos pickNext(List<BlockPos> vegetation, BlockPos feet) {
        return vegetation.stream().min(Comparator
                .comparingInt((BlockPos p) -> manhattan(p, feet))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ))
                .orElse(null);
    }

    private static void releaseClick(AltoClef mod) {
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        releaseClick(mod);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        List<BlockPos> vegetation = JumpObstructionHelper.collectVegetation(mod, feet);
        vegetation.removeAll(_skipped);
        return vegetation.isEmpty();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ClearNearbyVegetationTask;
    }

    @Override
    protected String toDebugString() {
        return "Clear nearby vegetation (cleared " + _clearedCount + ")";
    }

    /** 方块名 (诊断用, 失败不抛)。 */
    private String blockName(AltoClef mod) {
        try {
            return Registry.BLOCK.getId(mod.getWorld().getBlockState(_target).getBlock()).getPath();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }
}
