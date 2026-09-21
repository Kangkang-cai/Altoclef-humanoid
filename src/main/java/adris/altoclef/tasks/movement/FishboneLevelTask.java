package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * (2026-09-21 用户设计: 鱼骨下挖法找要塞, 窗口 [25,-55])
 *
 * 单层鱼骨: 从当前高度直降 5 格到层底, 然后四向各挖 5 格水平支道,
 * 全部完成后结束 (父任务接着创建下一层)。只负责移动/挖掘 —— 砖块感知
 * (视野+LOS / 近距扫描) 全部留在父任务 GoToStrongholdPortalTask 的
 * 节流扫描里, 本任务不做任何判定, 判据单一来源。
 *
 * 摔落/岩浆防护: 直降用 GetToYTask (与直挖阶段同款口径), 坠落由
 * MLGBucketFallChain 兜底 (硬前置: 父任务保证背包有水桶才进下挖);
 * 支道推进前检查沿线岩浆/水, 命中直接跳过该方向, 不赌 MLG。
 *
 * sticky 纪律: 内部 _navTask 持久实例, 同一支道/回程反复返回直到真正
 * 走完 (父任务每 tick 全量重估, 一次性 return 会被 null 抢停 — 0918 实锤)。
 */
public class FishboneLevelTask extends Task {

    // 支道长度 (用户规格: 四向各 5 格)。
    private static final int BRANCH_LENGTH = 5;

    private enum Step { DOWN, BRANCH, RETURN, DONE }

    // 层底 Y (父任务按"当前 Y - 5"传入, 最深不超过 -55)。
    private final int _targetY;
    // 直降完成后实测的井位 (baritone 直降可能有横向漂移, 以落地为准)。
    private int _shaftX = Integer.MAX_VALUE;
    private int _shaftZ = Integer.MAX_VALUE;
    private int _dirIndex = 0;
    private Step _step = Step.DOWN;
    // sticky 导航 (同一支道/回程反复返回直到走完)。
    private Task _navTask = null;
    private static final Direction[] DIRS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    public FishboneLevelTask(int targetY) {
        _targetY = targetY;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_navTask != null && !_navTask.isFinished(mod)) {
            return _navTask;
        }
        _navTask = null;
        switch (_step) {
            case DOWN: {
                BlockPos player = mod.getPlayer().getBlockPos();
                if (player.getY() > _targetY) {
                    setDebugState("Fishbone: shaft down to y=" + _targetY);
                    _navTask = new GetToYTask(_targetY);
                    return _navTask;
                }
                _shaftX = player.getX();
                _shaftZ = player.getZ();
                Debug.logMessage("[Fishbone] level floor y=" + _targetY
                        + ", shaft (" + _shaftX + "," + _shaftZ + "), branching 4x" + BRANCH_LENGTH);
                _step = Step.BRANCH;
                return null;
            }
            case BRANCH: {
                if (_dirIndex >= DIRS.length) {
                    _step = Step.DONE;
                    return null;
                }
                Direction dir = DIRS[_dirIndex];
                if (hasFluidAhead(mod, dir)) {
                    setDebugState("Fishbone y=" + _targetY + ": skip " + dir.asString() + " (fluid ahead).");
                    _dirIndex++;
                    return null;
                }
                BlockPos far = new BlockPos(
                        _shaftX + dir.getOffsetX() * BRANCH_LENGTH,
                        _targetY,
                        _shaftZ + dir.getOffsetZ() * BRANCH_LENGTH);
                setDebugState("Fishbone y=" + _targetY + ": branch " + dir.asString() + " x" + BRANCH_LENGTH);
                _navTask = new GetToBlockTask(far);
                _step = Step.RETURN;
                return _navTask;
            }
            case RETURN: {
                _dirIndex++;
                _step = Step.BRANCH;
                BlockPos player = mod.getPlayer().getBlockPos();
                if (player.getX() != _shaftX || player.getZ() != _shaftZ) {
                    // 回井底再挖下一向 (baritone 可挖穿回程)。
                    _navTask = new GetToBlockTask(new BlockPos(_shaftX, _targetY, _shaftZ));
                    return _navTask;
                }
                return null;
            }
            case DONE:
            default:
                return null;
        }
    }

    /** 支道沿线 (脚部/头部高度) 是否有岩浆/水/岩浆块 — 命中跳过该方向。 */
    private boolean hasFluidAhead(AltoClef mod, Direction dir) {
        for (int i = 1; i <= BRANCH_LENGTH; i++) {
            BlockPos foot = new BlockPos(
                    _shaftX + dir.getOffsetX() * i,
                    _targetY,
                    _shaftZ + dir.getOffsetZ() * i);
            if (isFluidOrMagma(mod, foot) || isFluidOrMagma(mod, foot.up())) {
                return true;
            }
        }
        return false;
    }

    private boolean isFluidOrMagma(AltoClef mod, BlockPos pos) {
        Block b = mod.getWorld().getBlockState(pos).getBlock();
        return b == Blocks.LAVA || b == Blocks.WATER || b == Blocks.MAGMA_BLOCK;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _step == Step.DONE;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FishboneLevelTask
                && ((FishboneLevelTask) other)._targetY == _targetY;
    }

    @Override
    protected String toDebugString() {
        return "FishboneLevel(y=" + _targetY + ")";
    }
}
