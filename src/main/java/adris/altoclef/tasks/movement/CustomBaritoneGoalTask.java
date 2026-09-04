package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalChunk;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {

    private final boolean _wander;
    private final Task _wanderTask = new TimeoutWanderTask(10);
    protected Goal _cachedGoal = null;
    // (2026-08-27 确定性导航超时) 挖方块开路不在此判定:MovementProgressChecker 挖块时
    // 走单独的"挖掘进度"检查(0.5 游戏秒内挖块进度>0.001, 破块即重置距离计时器), 不会
    // 因 1s 没位移而误判卡住。这里只判"纯行走的净位移":窗口内净位移 <1 块才判卡住。
    // 取值 60 游戏秒(3 现实秒) — 步伐稍慢/绕路折返(净位移一时 <1 块)也不误报,
    // 真卡住 3s 也能脱身。曾取 6(太急)→120(放置/挖块失手卡 6s)→20(用户担心净位移
    // <1 块易误报), 现取 60。
    protected MovementProgressChecker _checker = new MovementProgressChecker(60, 1.0, 0.5, 0.001);
    // How many times we failed to make progress on this goal (e.g. stuck/no path).
    // Used by the mob defense chain to decide when retreating is hopeless and we
    // should switch to sheltering instead.
    private int _failCount = 0;

    // ===== 水中直游 (2026-08-25) =====
    // baritone 在水里贴墙/近岸时寻路会反复重算 (转头前后摆动, 用户实测 7~15 次
    // 才游走): 根源是 DistanceProgressChecker 判"无进展"→ 触发 wander → 回
    // baritone → 又无进展 → 再 wander 的循环。修复: 水中无进展时不再 wander,
    // 直接朝目标 XZ 直游 (仿 GetToEntityTask 的 2026-08-22 水中贴角落修复),
    // 出水/接近目标/5 秒失速才交还 baritone。
    private boolean _directSwimControl = false;
    private double _directSwimBestDist = Double.MAX_VALUE;
    private final TimerGame _directSwimStallTimer = new TimerGame(5);
    private static final double DIRECT_SWIM_STOP_DISTANCE = 3;
    // (2026-08-26 用户多次反馈: 游泳时按住疾跑, 一直没修好) 游泳时是否要强制
    // 关 baritone 疾跑 (allowSprint)。上/下水状态变化时切换一次。
    private boolean _wasInWater = false;

    // ===== 水下垂直上浮 (2026-09-04) =====
    // 用户"上方全是海洋, baritone 找不到落脚点但可游泳上去"。GetToYTask 的
    // GoalYLevel 目标无 XZ → goalXZ() 返回 null → 原 direct-swim 不触发 → 水下
    // 回地表(y<58 走 GoToSurfaceTask)卡死 (日志实锤: y=50 水下卡 86 秒无进展)。
    // 垂直上浮: 瞄准正上方 + 前进 + 跳跃 + 疾跑(水下疾跑=游泳加速), 直到出水面
    // 或 y 不再增长(失速 5s 交还 baritone)。与下潜 (DiveToGrabItemTask) 对称。
    private boolean _directSwimUpMode = false;
    private int _directSwimLastY = Integer.MIN_VALUE;
    private static final int DIRECT_SWIM_UP_MIN_Y_GAIN = 1;

    public CustomBaritoneGoalTask(boolean wander) {
        _wander = wander;
    }

    public CustomBaritoneGoalTask() {
        this(true);
    }

    public int getFailCount() {
        return _failCount;
    }

    public void resetFailCount() {
        _failCount = 0;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        _checker.reset();
        _failCount = 0;
        _directSwimControl = false;
        _directSwimUpMode = false;
        _wasInWater = mod.getPlayer().isTouchingWater();
        // (2026-08-26 游泳疾跑) 上/下水状态统一由 onTick 切换 allowSprint。
        mod.getClientBaritoneSettings().allowSprint.value = !_wasInWater;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-08-26 用户多次反馈: 游泳时按住疾跑, 一直没修好) baritone 在
        // allowSprint=true 下, 水中寻路会 sprint-swim (之前只在 direct-swim /
        // 下潜里释放疾跑键, 平时 baritone 寻路游泳时管不到)。这里每 tick:
        // 在水中 → 关掉 baritone 疾跑 + 松开疾跑键 (双保险); 出水 → 恢复。
        // 明确"下潜"的任务 (DiveToGrabItemTask / direct-swim 目标在下方) 会自己
        // 直接按疾跑键, 不受 allowSprint 影响, 下潜加速保留。
        boolean inWater = mod.getPlayer().isTouchingWater();
        if (inWater != _wasInWater) {
            _wasInWater = inWater;
            mod.getClientBaritoneSettings().allowSprint.value = !inWater;
        }
        if (inWater) {
            mod.getInputControls().release(Input.SPRINT);
        }

        if (_cachedGoal == null) {
            _cachedGoal = newGoal(mod);
        }

        if (_wander) {
            if (isFinished(mod)) {
                // Don't wander if we've reached our goal.
                _checker.reset();
                _failCount = 0;
                if (_directSwimControl) releaseDirectSwim(mod);
            } else {
                if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
                    setDebugState("Wandering...");
                    _checker.reset();
                    if (_directSwimControl) releaseDirectSwim(mod);
                    return _wanderTask;
                }
                // 水中直游状态机: 一旦进入就持续朝目标游, 直到出水/接近/失速。
                if (_directSwimControl) {
                    if (!mod.getPlayer().isTouchingWater()) {
                        releaseDirectSwim(mod);
                    } else if (_directSwimUpMode) {
                        // 垂直上浮: 以 y 是否增长判断推进 (XZ 不变, 不能用水平距离)。
                        int y = mod.getPlayer().getBlockPos().getY();
                        if (y - _directSwimLastY >= DIRECT_SWIM_UP_MIN_Y_GAIN) {
                            _directSwimLastY = y;
                            _directSwimStallTimer.reset();
                        }
                        if (_directSwimStallTimer.elapsed()) {
                            Debug.logMessage("Direct swim-up stalled (no Y gain), handing back to baritone.");
                            releaseDirectSwim(mod);
                        } else {
                            holdDirectSwimUp(mod);
                            setDebugState("Swimming straight up");
                            return null;
                        }
                    } else {
                        BlockPos target = goalXZ();
                        double dist = target == null ? Double.MAX_VALUE : horizontalDist(mod, target);
                        if (dist < _directSwimBestDist - 0.5) {
                            _directSwimBestDist = dist;
                            _directSwimStallTimer.reset();
                        }
                        if (dist <= DIRECT_SWIM_STOP_DISTANCE) {
                            releaseDirectSwim(mod);
                        } else if (_directSwimStallTimer.elapsed()) {
                            Debug.logMessage("Direct swim stalled, handing back to baritone.");
                            releaseDirectSwim(mod);
                        } else {
                            holdDirectSwim(mod, target);
                            setDebugState("Swimming directly to goal");
                            return null;
                        }
                    }
                }
                // (2026-09-04 水下回地表, 用户"上方全是海洋, 但可以游泳上去")
                // 水中 + 目标在上方(GoalYLevel 等) + baritone 无活动路径(水下无法
                // 寻路) → 垂直上浮, 不依赖 _checker (水下卡住时 checker 可能一直
                // 通过, 永不判卡 → 86s 卡死)。出水面后交还 baritone 正常导航。
                if (inWater && !_directSwimControl) {
                    int ty = goalY();
                    if (ty > 0 && ty > mod.getPlayer().getBlockPos().getY()
                            && !mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                        _directSwimUpMode = true;
                        _directSwimLastY = mod.getPlayer().getBlockPos().getY();
                        _directSwimStallTimer.reset();
                        holdDirectSwimUp(mod);
                        setDebugState("Swimming straight up to y=" + ty);
                        return null;
                    }
                }
                if (!_checker.check(mod)) {
                    // 水中无进展 → 直游绕过 baritone 贴墙寻路循环 (不再 wander)。
                    if (mod.getPlayer().isTouchingWater() && goalXZ() != null) {
                        BlockPos target = goalXZ();
                        _directSwimBestDist = horizontalDist(mod, target);
                        _directSwimStallTimer.reset();
                        holdDirectSwim(mod, target);
                        setDebugState("Swimming directly to goal");
                        return null;
                    }
                    _failCount++;
                    Debug.logMessage("Failed to make progress on goal, wandering.");
                    onWander(mod);
                    return _wanderTask;
                }
            }
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_cachedGoal);
        }
        setDebugState("Completing goal.");
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_cachedGoal == null) {
            _cachedGoal = newGoal(mod);
        }
        return _cachedGoal != null && _cachedGoal.isInGoal(mod.getPlayer().getBlockPos());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_directSwimControl) releaseDirectSwim(mod);
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        // (2026-08-26 游泳疾跑) 恢复 baritone 疾跑 (防停在水中时 allowSprint
        // 停留在 false, 上岸后不能疾跑)。
        mod.getClientBaritoneSettings().allowSprint.value = true;
        _wasInWater = false;
    }

    /** 直游: 停掉 baritone 寻路, 视角对准目标 XZ, 按住前进 + 按目标 Y 垂直控制
     * (2026-08-26 修复: 之前一直按 JUMP 上浮, 目标在水下(沉水眼睛)时游不下去;
     * 目标在下方→按潜行下沉, 在上方→按跳跃上浮, 平齐→中性)。 */
    private void holdDirectSwim(AltoClef mod, BlockPos target) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        LookHelper.lookAt(mod, new Vec3d(target.getX() + 0.5, mod.getPlayer().getY(), target.getZ() + 0.5));
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        // 目标 Y 有效 (GoalBlock/TwoBlocks, Y>0) 时才做垂直控制; GoalXZ 的 Y=0 忽略。
        double dy = target.getY() - mod.getPlayer().getY();
        if (target.getY() > 0 && dy < -1.5) {
            mod.getInputControls().hold(Input.SNEAK);   // 目标在下方 → 潜行下沉
            mod.getInputControls().release(Input.JUMP);
            // (2026-08-26 用户要求) 下潜时开疾跑 (更快逼近水下目标)。
            mod.getInputControls().hold(Input.SPRINT);
        } else if (target.getY() > 0 && dy > 1.5) {
            mod.getInputControls().hold(Input.JUMP);    // 目标在上方 → 跳跃上浮
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.SPRINT);
        } else {
            mod.getInputControls().release(Input.JUMP);
            mod.getInputControls().release(Input.SNEAK);
            // (2026-08-26 用户要求) 游泳 (水平/水面) 关疾跑 — 开疾跑游泳会带来问题。
            mod.getInputControls().release(Input.SPRINT);
        }
        _directSwimControl = true;
    }

    private void releaseDirectSwim(AltoClef mod) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.SPRINT);
        _directSwimControl = false;
        _directSwimUpMode = false;
        _checker.reset();
    }

    /**
     * (2026-09-04 水下垂直上浮) 瞄准正上方 + 前进 + 跳跃 + 疾跑 (水下疾跑 =
     * 游泳加速, 用户要求 sprint+瞄准控制)。用于水下回地表 (GetToYTask /
     * GoalYLevel 目标在上方, baritone 无法寻路时)。
     */
    private void holdDirectSwimUp(AltoClef mod) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        LookHelper.lookAt(mod, new Rotation(0, -90)); // 正上方
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        mod.getInputControls().hold(Input.JUMP);
        mod.getInputControls().hold(Input.SPRINT);
        mod.getInputControls().release(Input.SNEAK);
        _directSwimControl = true;
    }

    /** 从 baritone goal 提取目标 XZ, 不支持的类型返回 null (不启用直游)。 */
    private BlockPos goalXZ() {
        Goal g = _cachedGoal;
        if (g == null) return null;
        if (g instanceof GoalXZ gxz) return new BlockPos(gxz.getX(), 0, gxz.getZ());
        if (g instanceof GoalBlock gb) return gb.getGoalPos();
        if (g instanceof GoalTwoBlocks gtb) return gtb.getGoalPos();
        if (g instanceof GoalChunk gc) {
            ChunkPos cp = gc.getChunkPos();
            return new BlockPos(cp.getStartX(), 0, cp.getStartZ());
        }
        return null;
    }

    /**
     * (2026-09-04) 提取 baritone goal 的目标 Y, 不支持的类型返回 -1。
     * 供水下垂直上浮用 (GoalYLevel 无 XZ, goalXZ() 返回 null)。
     */
    private int goalY() {
        Goal g = _cachedGoal;
        if (g == null) return -1;
        if (g instanceof GoalYLevel gyl) return gyl.level;
        if (g instanceof GoalBlock gb) return gb.getGoalPos().getY();
        if (g instanceof GoalTwoBlocks gtb) return gtb.getGoalPos().getY();
        return -1;
    }

    private double horizontalDist(AltoClef mod, BlockPos target) {
        Vec3d p = mod.getPlayer().getPos();
        double dx = (target.getX() + 0.5) - p.x;
        double dz = (target.getZ() + 0.5) - p.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    protected abstract Goal newGoal(AltoClef mod);

    protected void onWander(AltoClef mod) {}
}
