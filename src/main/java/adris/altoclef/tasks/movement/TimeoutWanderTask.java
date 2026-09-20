package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Call this when the place you're currently at is bad for some reason and you just wanna get away.
 *
 * <p>(2026-09-20 用户拍板) 游走方向从 baritone ExploreProcess 的【随机方向】改为
 * 【方位角锁定】: 本实例 onStart 时随机选一个方位角, 之后一直沿它走 (resetForReuse
 * 仅 ±5° 微抖, 同远征 BearingExpeditionTask 的方向纪律)。原因: 随机方向净位移≈0,
 * 是"原地打转/来回走"的总根源 (detail/04 偏置探索折返、0920 三次拉回振荡同族)。
 * 到达一段目标点后自动从当前位置续发下一段 —— 无限模式下即"一直朝一个方向走"。
 */
public class TimeoutWanderTask extends Task implements ITaskRequiresGrounded {

    private static final HashSet<Block> ANNOYING_STUCK_BLOCKS;
    static {
        ANNOYING_STUCK_BLOCKS = new HashSet<>();
        ANNOYING_STUCK_BLOCKS.addAll(Arrays.stream(ItemHelper.WOOD_FENCE).map(Block::getBlockFromItem).toList());
        //ANNOYING_STUCK_BLOCKS.addAll(Arrays.stream(ItemHelper.WOOD_DOOR).map(Block::getBlockFromItem).toList());
        ANNOYING_STUCK_BLOCKS.addAll(Arrays.stream(ItemHelper.FLOWER).map(Block::getBlockFromItem).toList());
        ANNOYING_STUCK_BLOCKS.addAll(Arrays.asList(Blocks.VINE,
                Blocks.NETHER_SPROUTS,
                Blocks.CAVE_VINES,
                Blocks.CAVE_VINES_PLANT,
                Blocks.TWISTING_VINES,
                Blocks.TWISTING_VINES_PLANT,
                Blocks.WEEPING_VINES_PLANT,
                Blocks.LADDER,
                Blocks.BIG_DRIPLEAF,
                Blocks.BIG_DRIPLEAF_STEM,
                Blocks.SMALL_DRIPLEAF,
                Blocks.TALL_GRASS,
                Blocks.SNOW,
                Blocks.GRASS
        ));
    }

    private final float _distanceToWander;
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final boolean _increaseRange;
    //private DistanceProgressChecker _distanceProgressChecker = new DistanceProgressChecker(10, 0.1f);

    // (2026-09-20 方向持久化) 本实例的行进方位角 (度, 数学坐标系 x=cos/z=sin)。
    // null = onStart 时随机初始化; resetForReuse 只 ±5° 微抖 —— "尽量持续一个方向"。
    private Double _bearingDeg = null;
    /** 每次复用重开时的方向抖动幅度 (度), 同远征 ±5° 纪律。 */
    private static final double BEARING_JITTER_DEG = 5.0;
    /** 无限游走模式的单段步长: 走完即从当前位置续发同方位下一段 (直线接力)。 */
    private static final double INFINITE_LEG_LENGTH = 128.0;

    private Vec3d _origin;
    private boolean _executingPlanB = false;
    private boolean _forceExplore;
    private Task _unstuckTask = null;
    private int _failCounter;
    private double _wanderDistanceExtension;
    // (2026-09-15 统一超时标签) failCounter 判负的边沿标记, 防 isFinished 重复打 [TIMEOUT]。
    private boolean _giveUpLogged = false;

    public TimeoutWanderTask(float distanceToWander, boolean increaseRange) {
        _distanceToWander = distanceToWander;
        _increaseRange = increaseRange;
        _forceExplore = false;
    }

    public TimeoutWanderTask(float distanceToWander) {
        this(distanceToWander, false);
    }

    public TimeoutWanderTask() {
        this(Float.POSITIVE_INFINITY, false);
    }

    public TimeoutWanderTask(boolean forceExplore) {
        this();
        _forceExplore = forceExplore;
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.add(1, 0, 0),
                pos.add(-1, 0, 0),
                pos.add(0, 0, 1),
                pos.add(0, 0, -1),
        };
    }

    public void resetWander() {
        _executingPlanB = false;
        _wanderDistanceExtension = 0;
    }

    /**
     * (2026-09-17 复用实例重开会话) 本任务是**实例级**的: `onStart` 只在实例第一次
     * 被 setTask 时跑, 之后同实例被反复 `return` (isEqual 命中) 不会再跑, 于是
     * `_origin` 会停留在**上一次使用的位置**。若那次在远处, {@link #isFinished} 的
     * "离原点 > 距离" 立刻成立 → 游荡 0.3s 就返回 "clean 完成" = **空转**。
     *
     * <p>现场 20260917-150146 实锤: 爬升判不可达后走"拉黑 + wander 换活"路径, 5 轮
     * 全是 `Wander for 5.0 blocks` 300ms STOP(clean) —— 换活等于没做, 50ms 后父级
     * 又重建同目标, 3.15s 一轮循环 15s 不脱险。
     *
     * <p>⚠ 这与 `resetWander()` 的语义**不同**: 那个只清"上一次失败造成的 planB/加距"
     * 状态, 不动原点。要真正重开一次游荡会话必须用本方法(等价于补跑一次 onStart 里
     * 会重置的那几项)。
     */
    public void resetForReuse(AltoClef mod) {
        resetWander();
        if (mod.getPlayer() != null && mod.getPlayer().getPos() != null) {
            _origin = mod.getPlayer().getPos();
        }
        // (2026-09-20 方向持久化) 方位角保留, 仅微抖 —— 复用实例不换大方向。
        if (_bearingDeg != null) {
            _bearingDeg += (Math.random() * 2.0 - 1.0) * BEARING_JITTER_DEG;
        }
        _progressChecker.reset();
        _failCounter = 0;
        _giveUpLogged = false;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _origin = mod.getPlayer().getPos();
        if (_bearingDeg == null) {
            _bearingDeg = Math.random() * 360.0;
        }
        _progressChecker.reset();
        _failCounter = 0;
        _giveUpLogged = false;
        StorageHelper.closeScreen();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }

        if (_executingPlanB) {
            setDebugState("Plan B: Bearing-locked direction.");
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(getBearingGoal(mod));
            }
        } else {
            // (2026-09-20 方向持久化) ExploreProcess 方向随机 (净位移≈0, 打转根源),
            // 改为沿锁定方位角直线走; 一段走完 (goal 进程失活) 自动从当前位置续发
            // 下一段 —— 无限模式即一直朝一个方向走。
            setDebugState("Exploring (bearing-locked).");
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(getBearingGoal(mod));
            }
        }

        //_distanceProgressChecker.setProgress(mod.getPlayer().getPos());
        //if (_distanceProgressChecker.failed()) {
        if (!_progressChecker.check(mod)) {
            // We failed at exploring.
            //_distanceProgressChecker.reset();
            _progressChecker.reset();

            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _failCounter++;
                Debug.logMessage("Failed exploring, found stuck block nearby.");
                _unstuckTask = getFenceUnstuckTask(mod, blockStuck);
                return _unstuckTask;
            }

            if (!_forceExplore) {
                _failCounter++;
                Debug.logMessage("Failed exploring.");
                if (_executingPlanB) {
                    // Cancel current plan B
                    mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                }
                _executingPlanB = true;
            }
        }

        return null;
    }

    /** (2026-09-20 方向持久化) 沿锁定方位角的前方目标点: 从【当前位置】起算,
     *  有限模式步长 = 游走距离 (+扩距), 无限模式用固定段长接力直线走。 */
    private Goal getBearingGoal(AltoClef mod) {
        double distance = Float.isInfinite(_distanceToWander)
                ? INFINITE_LEG_LENGTH
                : (_distanceToWander + _wanderDistanceExtension);
        if (_bearingDeg == null) {
            // 防御: 调用方跳过 onStart 直接 onTick 的路径 (理论上没有, #18 同族守卫)。
            _bearingDeg = Math.random() * 360.0;
        }
        Vec3d p = mod.getPlayer().getPos();
        double rad = Math.toRadians(_bearingDeg);
        return new GoalXZ(
                (int) Math.floor(p.x + Math.cos(rad) * distance),
                (int) Math.floor(p.z + Math.sin(rad) * distance));
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getExploreProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        if (isFinished(mod)) {
            if (_increaseRange) {
                _wanderDistanceExtension += _distanceToWander;
                Debug.logMessage("Increased wander range");
            }
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-20 崩溃修复 14.20.15) _origin 只在 onStart 赋值; 调用方在创建当拍
        // 探测 isFinished 时 onStart 还没跑, _origin == null → squaredDistanceTo(null)
        // NPE 崩溃 (CollectEnderPearlsInForestTask.lostChaseBeat 实锤)。未启动 = 未完成。
        // 原作者注释 //if (_origin == null) return true; 语义应为 return false (未启动不算走完)。
        if (_origin == null) return false;

        if (Float.isInfinite(_distanceToWander)) return false;

        // If we fail 10 times or more, we may as well try the previous task again.
        if (_failCounter > 10) {
            return true;
        }

        if (mod.getPlayer() != null && mod.getPlayer().getPos() != null) {
            double sqDist = mod.getPlayer().getPos().squaredDistanceTo(_origin);
            double toWander = _distanceToWander + _wanderDistanceExtension;
            return sqDist > toWander * toWander;
        } else {
            return false;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof TimeoutWanderTask task) {
            if (Float.isInfinite(task._distanceToWander) || Float.isInfinite(_distanceToWander)) {
                return Float.isInfinite(task._distanceToWander) == Float.isInfinite(_distanceToWander);
            }
            return Math.abs(task._distanceToWander - _distanceToWander) < 0.5f;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        String bearing = (_bearingDeg == null) ? "?" : String.valueOf((int) (double) _bearingDeg);
        return "Wander for " + (_distanceToWander + _wanderDistanceExtension)
                + " blocks (bearing=" + bearing + ")";
    }

    // This happens all the time in mineshafts and swamps/jungles
    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.up())) return p.up();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.up());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        return ANNOYING_STUCK_BLOCKS.contains(mod.getWorld().getBlockState(pos).getBlock());
        //mod.getWorld().getBlockState(pos).getBlock() instanceof FenceBlock;
    }

    private Task getFenceUnstuckTask(AltoClef mod, BlockPos fencePos) {
        return new SafeRandomShimmyTask();
    }
}
