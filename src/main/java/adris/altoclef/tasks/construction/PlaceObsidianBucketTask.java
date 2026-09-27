package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

/**
 * Places obsidian at a position using buckets and a cast.
 */
public class PlaceObsidianBucketTask extends Task {

    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5, true); // This can get stuck forever, so we increase the range.
    // (2026-09-04 误触诊断) 倒岩浆阶段诊断日志节流 (1s)。
    private final adris.altoclef.util.time.TimerGame _pourDiagTimer = new adris.altoclef.util.time.TimerGame(1);

    public static final Vec3i[] CAST_FRAME = new Vec3i[]{
            new Vec3i(0, -1, 0),
            new Vec3i(0, 0, -1),
            new Vec3i(0, 0, 1),
            new Vec3i(-1, 0, 0),
            new Vec3i(1, 0, 0),
            new Vec3i(1, 1, 0)
    };

    private final BlockPos _pos;

    private BlockPos _currentCastTarget;
    private BlockPos _currentDestroyTarget;

    // (2026-09-27 清场死锁修复) 模具位保护开关 —— 每拍由 onTick 刷新, 谓词只读不写。
    // 口径 = "清场已完成" (_pos / _pos.up() / _pos.up(2) 都无需清除)。清场期间一律放行。
    // 详见 onStart 里 avoidBlockBreaking 谓词的注释。
    private boolean _protectCastFrame = false;

    public PlaceObsidianBucketTask(BlockPos pos) {
        _pos = pos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // Don't break cast
        //
        // (2026-09-27 清场死锁修复) 原实现**无条件**保护 CAST_FRAME 六格 —— 而这六格
        // 是 _pos 的【正下 + 四个水平邻 + 右上出水口】, 也就是从任何站位瞄准 _pos 的
        // 射线**必经**的格子 (只有从正上方俯视才不经过任何一格; 五个水平/下方方向全部
        // 落在模具位上)。于是当 _pos 自身需要挖掉时 (废墟传送门自带门框是
        // `crying_obsidian`, 哭黑不能被点燃, 必须换成 obsidian), DestroyBlockTask 的
        // 挡块判定查到该格 → shouldAvoidBreaking 恒 true → neverOccluder 拒拆
        // → 目标永远清不掉。
        //
        // 现场 20260927-085834 (`_pos = -419, 70, 244`, 该格 = `crying_obsidian`):
        //   09:22:36~09:24:16 每 2s 一条(节流上限)
        //     `[OccluderClear] refused protected -419, 69, 244 (cobblestone)
        //      blocking target=-419, 70, 244`
        //   —— `-419,69,244` 正是 CAST_FRAME 的 `(0,-1,0)`, 且那格圆石还是 **bot 自己
        //   垫上去的** (用户现场确认) ⇒ **自己垫的方块被自己的谓词锁死**。
        //   另两条 refused 位置 `-420,70,244`=`(-1,0,0)`、`-419,69,243` 同样是模具位。
        //   末 `Blacklist: -419,70,244: Try 4/4`, `<Construct Nether Portal>` 无休止。
        //
        // 保护的真实用途只有一个: **岩浆/水浇铸期间别把模具挖塌**。所以判据从"无条件"
        // 收紧为"清场已完成" (`_protectCastFrame`, 由 onTick 每拍刷新) —— 清场阶段模具位
        // 一律放行, 浇铸阶段照旧全保护。
        //
        // 配套改动见 onTick 开头: 清场判定已**前移**到 "Build the cast frame" 之前 ——
        // 否则时序仍是"先砌满模具 → 再挖 _pos", 目标会被自己刚砌的模具**物理**围住,
        // 即便谓词放行也会变成"挖模具 → 再砌模具"的反复 (churn)。
        mod.getBehaviour().avoidBlockBreaking(block -> {
            if (!_protectCastFrame) {
                // 清场阶段 (或 _pos 尚未就绪): 模具位此刻是瞄准通道, 不是模具。
                return false;
            }
            for (Vec3i castPosRelativeToLava : PlaceObsidianBucketTask.CAST_FRAME) {
                BlockPos castPos = _pos.add(castPosRelativeToLava);
                if (block.equals(castPos)) {
                    return true;
                }
            }
            return false;
        });
        // Don't place blocks inside our cast water/lava
        mod.getBehaviour().avoidBlockPlacing(block -> {
            BlockPos waterTarget = _pos.up();
            return block.equals(_pos) || block.equals(waterTarget);
        });

        // (0927) 首拍即定保护开关, 不留 "onStart → onTick" 之间的空窗。
        refreshCastFrameProtection(mod);
        _progressChecker.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (0927 清场死锁修复) 模具位保护开关每拍刷新 —— 见 onStart 的
        // avoidBlockBreaking 谓词注释。放在最前, 保证本拍 spawn 的任何子任务
        // (含 DestroyBlockTask 的挡块判定) 看到的都是刷新后的口径。
        refreshCastFrameProtection(mod);

        // Clear leftover water
        if (mod.getBlockTracker().blockIsValid(_pos, Blocks.OBSIDIAN) && mod.getBlockTracker().blockIsValid(_pos.up(), Blocks.WATER)) {
            return new ClearLiquidTask(_pos.up());
        }

        if (!mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
            // The only excuse is that we have lava at our position.
            if (!mod.getBlockTracker().blockIsValid(_pos, Blocks.LAVA)) {
                _progressChecker.reset();
                return TaskCatalogue.getItemTask(Items.LAVA_BUCKET, 1);
            }
        }

        // Manual wander to try and make this godforesaken task work
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering.");
            _progressChecker.reset();
            return _wanderTask;
        }
        if (!_progressChecker.check(mod)) {
            _progressChecker.reset();
            return _wanderTask;
        }

        if (_currentCastTarget != null) {
            if (WorldHelper.isSolid(mod, _currentCastTarget)) {
                _currentCastTarget = null;
            } else {
                return new PlaceStructureBlockTask(_currentCastTarget);
            }
        }
        if (_currentDestroyTarget != null) {
            if (!needsClearing(mod, _currentDestroyTarget)) {
                _currentDestroyTarget = null;
            } else {
                return new DestroyBlockTask(_currentDestroyTarget);
            }
        }

        // (0927 清场死锁修复) 清场判定**前移到砌模之前**。
        // 原顺序是"先砌满 CAST_FRAME 模具 → 再清 _pos", 但模具六格正是 _pos 的正下 +
        // 四个水平邻 (见 onStart 注释) ⇒ 砌满之后目标被自己的模具**物理**四面包住;
        // 而那六格又同时被 avoidBlockBreaking 判为禁破坏 —— 等于亲手把自己锁进盒子。
        // 现场 20260927-085834 `_pos = -419,70,244` (废墟传送门自带的 crying_obsidian):
        //   机器人反复 `<Destroy block at -419,70,244> Getting to block...`,
        //   `[Ascent] start/done` 每 50ms 翻面 (全场 391:391), 一个字也挖不动。
        // 清场放在砌模之前 ⇒ 挖 _pos 时周围还没有我们自己砌的模具。
        if (mod.getWorld().getBlockState(_pos).getBlock() != Blocks.LAVA) {

            // (2026-09-25 树叶挡射线修复) 清场判定从 isSolid 扩为 needsClearing:
            // 树叶 isSolidBlock=false 会漏检, 但有碰撞箱挡瞄准射线且不可被液体替换。
            if (needsClearing(mod, _pos)) {
                setDebugState("Clearing space around lava");
                _currentDestroyTarget = _pos;
                return null;
                //return new DestroyBlockTask(framePos);
            }
            // Clear the upper two as well, to make placing more reliable.
            // (2026-09-25) 这两格正是倒岩浆/倒水瞄准射线的必经路径 (bot 站
            // _pos.add(-1,1,0) 眼高 y+2.62 斜射到 (x+1,y+0.5)/(x+1,y+1.5) 面心,
            // 射线穿过 (x,y+1,z) 与 (x,y+2,z)), 树叶在此会永久挡射线 → 卡死。
            if (needsClearing(mod, _pos.up())) {
                setDebugState("Clearing space around lava");
                _currentDestroyTarget = _pos.up();
                return null;
            }
            if (needsClearing(mod, _pos.up(2))) {
                setDebugState("Clearing space around lava");
                _currentDestroyTarget = _pos.up(2);
                return null;
            }
        }

        // Build the cast frame
        if (_currentCastTarget != null && WorldHelper.isSolid(mod, _currentCastTarget)) {
            // Current cast frame already built.
            _currentCastTarget = null;
        }
        for (Vec3i castPosRelative : CAST_FRAME) {
            BlockPos castPos = _pos.add(castPosRelative);
            if (!WorldHelper.isSolid(mod, castPos)) {
                _currentCastTarget = castPos;
                return null;
            }
        }

        // Cast frame built. Now, place lava.
        if (mod.getWorld().getBlockState(_pos).getBlock() != Blocks.LAVA) {

            // Make sure we have water, juuust in case we have another creeper appear run end here
            if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                _progressChecker.reset();
                return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            }

            // Don't place lava at our position!
            // Would lead to an embarrassing death.
            BlockPos targetPos = _pos.add(-1, 1, 0);
            if (!mod.getPlayer().getBlockPos().equals(targetPos) && mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
                setDebugState("Positioning player before lava");
                return new GetToBlockTask(targetPos, false);
            }

            setDebugState("Placing lava for cast");

            // (2026-09-04 误触诊断) 每秒一次: 目标块状态 + 周围 ±2 已漏岩浆位置 +
            // 玩家位置。用于事后核对"岩浆到底倒哪了/流到哪了"。
            if (_pourDiagTimer.elapsed()) {
                _pourDiagTimer.reset();
                StringBuilder lavaNear = new StringBuilder();
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            BlockPos p = _pos.add(dx, dy, dz);
                            if (mod.getWorld().getBlockState(p).getBlock() == Blocks.LAVA) {
                                if (lavaNear.length() > 0) lavaNear.append(',');
                                lavaNear.append(p.getX() - _pos.getX()).append(',').append(p.getY() - _pos.getY()).append(',').append(p.getZ() - _pos.getZ());
                            }
                        }
                    }
                }
                Debug.logInternal("[PourDiag] pos=" + _pos.toShortString()
                        + " blockAtPos=" + mod.getWorld().getBlockState(_pos).getBlock()
                        + " player=" + mod.getPlayer().getBlockPos().toShortString()
                        + " lavaNearby(rel)=" + lavaNear);
            }

            return new InteractWithBlockTask(new ItemTarget(Items.LAVA_BUCKET, 1), Direction.WEST, _pos.add(1, 0, 0), false);
        }
        // Lava placed, Now, place water.
        BlockPos waterCheck = _pos.up();
        if (mod.getWorld().getBlockState(waterCheck).getBlock() != Blocks.WATER) {
            setDebugState("Placing water for cast");

            // (2026-09-25 树叶挡射线修复) 用户实测: 水位上方一格是树叶时, 瞄准射线
            // 穿过该格被判 CANT_REACH, 放不了水卡死, 用户手动打掉树叶才恢复。
            // 树叶 isSolidBlock=false 漏检 → 改用 needsClearing 兜住。
            if (needsClearing(mod, waterCheck)) {
                _currentDestroyTarget = waterCheck;
                return null;
                //return new DestroyBlockTask(waterCheck);

            }
            if (needsClearing(mod, waterCheck.up())) {
                _currentDestroyTarget = waterCheck.up();
                return null;
                //return new DestroyBlockTask(waterCheck.up());
            }

            // Get to position to avoid weird stuck scenario
            BlockPos targetPos = _pos.add(-1, 1, 0);
            if (!mod.getPlayer().getBlockPos().equals(targetPos) && mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                setDebugState("Positioning player before water");
                return new GetToBlockTask(targetPos, false);
            }

            return new InteractWithBlockTask(new ItemTarget(Items.WATER_BUCKET, 1), Direction.WEST, _pos.add(1, 1, 0), true);
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return mod.getBlockTracker().blockIsValid(_pos, Blocks.OBSIDIAN) && !mod.getBlockTracker().blockIsValid(_pos.up(), Blocks.WATER);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceObsidianBucketTask task) {
            return task._pos.equals(_pos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Placing obsidian at " + _pos + " with a cast";
    }

    public BlockPos getPos() {
        return _pos;
    }

    /**
     * (2026-09-27 清场死锁修复) 刷新 {@code _protectCastFrame} —— CAST_FRAME 模具位保护开关。
     *
     * 口径 = "清场已完成" = `_pos` / `_pos.up()` / `_pos.up(2)` 三格都无需清除。
     * 这三格正是 onTick 里会派给 DestroyBlockTask 的全部目标; 只要其中任一格还没清完,
     * CAST_FRAME 六格此刻就是"瞄准通道"而不是"模具", 谓词必须放行 —— 否则
     * DestroyBlockTask 的挡块判定 (`neverOccluder` → `shouldAvoidBreaking`) 会把它们
     * 全判为受保护, 目标永远清不掉 (现场 20260927-085834, 见 onStart 谓词注释)。
     *
     * 反之清场一结束 (倒岩浆之前) 立刻回到"全保护" —— 保护的本职 (浇铸期间别把模具
     * 挖塌) 没有削弱半分。
     *
     * 每拍调用一次 (onStart + onTick 开头), 谓词侧只读布尔, 不做任何世界查询。
     */
    private void refreshCastFrameProtection(AltoClef mod) {
        _protectCastFrame = !needsClearing(mod, _pos)
                && !needsClearing(mod, _pos.up())
                && !needsClearing(mod, _pos.up(2));
    }

    /**
     * (2026-09-25 树叶挡射线修复) 倒岩浆/倒水前"该格是否必须清掉"的判定。
     *
     * 旧口径只有 {@link WorldHelper#isSolid} (= isSolidBlock, 实心整块), 而
     * 树叶 Material.LEAVES allowsMovement/notSolid → isSolidBlock=false 漏检;
     * 但树叶有完整碰撞箱 —— baritone/游戏瞄准射线 (ShapeType.COLLIDER) 会被它
     * 挡住, 且树叶不可被液体替换 (水/岩浆浇不进去)。结果: 水位上方一格是树叶
     * 时, 射线永远命中树叶 ≠ 目标面 → CANT_REACH 卡死, 流体桶又不许盲点强按
     * (2026-09-04 误触教训), 用户手动打掉树叶才恢复 (20260925 实锤)。
     *
     * 判定 (严格为旧口径的超集, 不改实心块既有行为):
     *   空气/流体 → 不清 (射线可穿, 液体格本就是浇铸目标);
     *   可替换 (草/花/雪片) → 不清 (射线可穿, 倒水时被 MC 自动替换);
     *   isSolidBlock → 清 (原口径);
     *   其余有碰撞箱且可挖 (树叶/栅栏等) → 清 (本次修复的盲区)。
     */
    private boolean needsClearing(AltoClef mod, BlockPos pos) {
        BlockState s = mod.getWorld().getBlockState(pos);
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.getMaterial().isReplaceable()) return false;
        if (WorldHelper.isSolid(mod, pos)) return true;
        boolean collides = !s.getCollisionShape(mod.getWorld(), pos).isEmpty();
        if (collides && WorldHelper.canBreak(mod, pos)) {
            if (_clearDiagTimer.elapsed()) {
                _clearDiagTimer.reset();
                // (审查 Low-2) logInternal 只进 stdout 不落盘, 验证点必须可从日志文件核对。
                Debug.logToFile("altoclef_diag.log", "[PourClear]\tclearing ray-blocking non-solid block "
                        + s.getBlock() + " at " + pos.toShortString());
            }
            return true;
        }
        return false;
    }
    // [PourClear] 诊断日志节流 (2s), 防逐 tick 刷屏。
    private final adris.altoclef.util.time.TimerGame _clearDiagTimer = new adris.altoclef.util.time.TimerGame(2);
}
