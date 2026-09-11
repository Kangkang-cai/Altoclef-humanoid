package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalChunk;
import adris.altoclef.util.helpers.JumpObstructionHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {

    private final boolean _wander;
    private final Task _wanderTask = new TimeoutWanderTask(10);
    protected Goal _cachedGoal = null;
    // (2026-08-27 确定性导航超时) 挖方块开路不在此判定:MovementProgressChecker 挖块时
    // 走单独的"挖掘进度"检查(0.5 秒内挖块进度>0.001, 破块即重置距离计时器), 不会
    // 因 1s 没位移而误判卡住。这里只判"纯行走的净位移":窗口内净位移 <1 块才判卡住。
    // (2026-09-09 单位实锤修正, [NAV-STUCK] 日志两次 49s/52s 现场实锤) 原取 60 并
    // 注释称"60 游戏秒=3 现实秒" — 错。TimerGame 计时源是 ClientConnection.ticks/20,
    // 即【真实秒】, 60=真实 60 秒: bot 水中贴藤蔓被 baritone 假寻路卡住 52 秒
    // (pathing=true 有 path 但 bIn 全 false 一个键不按), 任务层 60s 没到永远装死,
    // 直游/wander 脱困机制全被这段沉默期埋没。修正为设计本意 3 真实秒。
    protected MovementProgressChecker _checker = new MovementProgressChecker(3, 1.0, 0.5, 0.001);
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
    // (2026-09-09 用户: 5s 窗口太长, 调 2s) 直游失速判定窗口。
    private final TimerGame _directSwimStallTimer = new TimerGame(2);
    private static final double DIRECT_SWIM_STOP_DISTANCE = 3;
    // (2026-08-26 用户多次反馈: 游泳时按住疾跑, 一直没修好) 游泳时是否要强制
    // 关 baritone 疾跑 (allowSprint)。上/下水状态变化时切换一次。
    private boolean _wasInWater = false;

    // ===== (2026-09-11) 跳跃卡死: 检测 + 挖障处置 =====
    // 检测口径 (用户报"简单旋转跳怎么都跳不上去"): baritone 强制按住跳跃键 +
    // 水平速度 ≈ 0 连续 1s。会话 20260911-151743 抓到 9 条硬样本 (bIn[fwd=true
    // jump=true] 而 hSpeed≈0.000, 同格内 y 只震荡 0.33 格)。
    // 处置 = 「挖障碍」: 改世界 → baritone 重算时路径自然变。
    // (baritone 没有路径级禁行注入, IPathingBehavior 只暴露 hasPath/isPathing/
    //  getPath/getGoal/forceCancel/cancelEverything; Settings.blacklistClosestOnFailure
    //  与 #blacklist 都挂在 GetToBlockProcess/MineProcess 上, 而 altoclef 走的是
    //  CustomGoalProcess.setGoalAndPath, 拿不到那份黑名单。所以"不重复选失败路线"
    //  只有两个杠杆: ① 挖掉障碍物改世界 ② forceCancel 撤销路径逼它重算。)
    private static final int JUMP_STUCK_TICKS = 20;   // 连续 1s 才判定
    private int _jumpStuckTicks = 0;
    private final TimerGame _jumpStuckLogTimer = new TimerGame(2);
    /** 挖障 / 换路后的冷却。必须是"触发条件层 static" —— 导航任务实例会被频繁
     *  重建, 实例层冷却会被新实例绕过 (20de6b0c 教训)。 */
    private static final int JUMP_FIX_COOLDOWN_TICKS = 20 * 3;
    private static final int JUMP_REPATH_COOLDOWN_TICKS = 20 * 5;
    private static long _jumpFixCooldownUntil = 0;
    /** 同一格反复卡 (挖不出障碍 → 换路 → baritone 又选回来): 达上限拉长冷却防抖动。 */
    private static final int JUMP_SAME_SPOT_LIMIT = 4;
    private static final int JUMP_SAME_SPOT_COOLDOWN_TICKS = 20 * 30;
    private static BlockPos _jumpLastStuckPos = null;
    private static int _jumpSameSpotCount = 0;

    // ===== 水下垂直上浮 (2026-09-04) =====
    // 用户"上方全是海洋, baritone 找不到落脚点但可游泳上去"。GetToYTask 的
    // GoalYLevel 目标无 XZ → goalXZ() 返回 null → 原 direct-swim 不触发 → 水下
    // 回地表(y<58 走 GoToSurfaceTask)卡死 (日志实锤: y=50 水下卡 86 秒无进展)。
    // 垂直上浮: 瞄准正上方 + 前进 + 跳跃 + 疾跑(水下疾跑=游泳加速), 直到出水面
    // 或 y 不再增长(失速 5s 交还 baritone)。与下潜 (DiveToGrabItemTask) 对称。
    private boolean _directSwimUpMode = false;
    private int _directSwimLastY = Integer.MIN_VALUE;
    private static final int DIRECT_SWIM_UP_MIN_Y_GAIN = 1;

    // ===== 直游换气 (2026-09-09, 用户实锤"只游不按空格上浮, 快淹死了") =====
    // GoalXZ 的 target.getY()=0 → 平游分支无条件 release(JUMP) → 埋头游不换气。
    // 修复: 平游时间歇按 JUMP (按 2s / 松 2s 循环, 用户: 不能一直按着空格,
    // 过几秒再按 — 有特殊情况需要间歇处理); 缺氧 <40% 才切持续按, >90% 恢复
    // 间歇 (滞回防抖)。下潜时氧气 <40% 强制上浮换气到 >90% 才继续下潜。
    private boolean _breaching = false;
    private static final double BREACH_TRIGGER_FRACTION = 0.4;
    private static final double BREACH_RELEASE_FRACTION = 0.9;
    // 间歇跳跃节奏: 跳跃相位(按住)与滑行相位(松开)各 2s。
    private final TimerGame _breathPaceTimer = new TimerGame(2);
    private boolean _breathJumpPhase = true;

    // ===== active-stale 假寻路闸门 (2026-09-10) =====
    // 2026-09-10 17:02 场次实锤: GetToChunkTask 走向 650 格外的目标区块, wander
    // 结束后 baritone CustomGoalProcess 自称 active(customGoal=true) 但 pathing=false
    // 且 path[present=false], 四输入全 false, bot 原地冻结 29s 直到用户手动停止。
    // 原逻辑只在 !isActive() 时重发 setGoalAndPath, active-stale 态永远卡在
    // L221 闸门外。修复: 目标未达成时, 连续 4s 既不在算路也无路径 → 强制
    // onLostControl + 重发寻路; 期间 baritone 真在干活 (pathing 或 path 存在)
    // 则重新武装计时。4s 窗口留足远距离分段算路时间, 不误杀正常 path calc。
    private final TimerGame _staleActiveTimer = new TimerGame(4);
    private boolean _staleActiveArmed = false;

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
        Task jumpFix = tickJumpStuck(mod);
        if (jumpFix != null) return jumpFix;

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
                // 水中 + 目标在上方(GoalYLevel 等) → 垂直上浮。不依赖 baritone
                // 无活动路径 (水下无法寻路时 CustomGoalProcess.isActive() 可能恒
                // true 反复重试 → 上浮永不触发, 日志实锤 y=50 卡死)。上浮状态机
                // 自身有 5s 失速 + 出水 release 兜底, 不会误控失控。
                if (inWater && !_directSwimControl) {
                    int ty = goalY();
                    if (ty > 0 && ty > mod.getPlayer().getBlockPos().getY()) {
                        Debug.logToFile("altoclef_diag.log", "[SwimUp] trigger player="
                                + mod.getPlayer().getBlockPos() + " goalY=" + ty
                                + " baritoneActive=" + mod.getClientBaritone().getCustomGoalProcess().isActive());
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
                        _breathJumpPhase = true;
                        _breathPaceTimer.reset();
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
            _staleActiveArmed = false;
        } else if (!_cachedGoal.isInGoal(mod.getPlayer().getBlockPos())) {
            // (2026-09-10 active-stale 假寻路闸门, 见字段注释)
            try {
                boolean pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
                boolean pathPresent = mod.getClientBaritone().getPathingBehavior().getPath().isPresent();
                if (pathing || pathPresent) {
                    _staleActiveArmed = false;
                } else if (!_staleActiveArmed) {
                    _staleActiveArmed = true;
                    _staleActiveTimer.reset();
                } else if (_staleActiveTimer.elapsed()) {
                    _staleActiveArmed = false;
                    _failCount++;
                    Debug.logMessage("[FakePath] baritone active-stale (isActive but not pathing & no path) 4s, forcing re-path to " + _cachedGoal);
                    mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                    mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_cachedGoal);
                }
            } catch (Throwable t) {
                // baritone 状态读取异常时按"在干活"处理, 不强推重寻路
                _staleActiveArmed = false;
            }
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
     * 目标在下方→按潜行下沉, 在上方→按跳跃上浮, 平齐→中性)。
     * (2026-09-09 换气修复: 平齐(GoalXZ Y=0)不再是纯中性——头在水中(air 未满)
     * 就按 JUMP 贴水面游, 防沉底淹死; 下潜分支氧气 <40% 强制换气到 >90%。)
     * 换气期间仍按 W 朝目标推进, 人类换气也是边游边抬头。 */
    private void holdDirectSwim(AltoClef mod, BlockPos target) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        LookHelper.lookAt(mod, new Vec3d(target.getX() + 0.5, mod.getPlayer().getY(), target.getZ() + 0.5));
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        // 目标 Y 有效 (GoalBlock/TwoBlocks, Y>0) 时才做垂直控制; GoalXZ 的 Y=0 忽略。
        double dy = target.getY() - mod.getPlayer().getY();
        // 换气状态机 (滞回防抖): <40% 触发, >90% 解除。
        double airFrac = mod.getPlayer().getMaxAir() > 0
                ? (double) mod.getPlayer().getAir() / mod.getPlayer().getMaxAir() : 1.0;
        if (_breaching) {
            if (airFrac >= BREACH_RELEASE_FRACTION) _breaching = false;
        } else if (airFrac < BREACH_TRIGGER_FRACTION) {
            _breaching = true;
        }
        if (target.getY() > 0 && dy < -1.5 && !_breaching) {
            mod.getInputControls().hold(Input.SNEAK);   // 目标在下方 → 潜行下沉
            mod.getInputControls().release(Input.JUMP);
            // (2026-08-26 用户要求) 下潜时开疾跑 (更快逼近水下目标)。
            mod.getInputControls().hold(Input.SPRINT);
        } else if (target.getY() > 0 && dy > 1.5) {
            mod.getInputControls().hold(Input.JUMP);    // 目标在上方 → 跳跃上浮
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.SPRINT);
        } else {
            // (2026-09-09) 平游/换气: 间歇跳跃 — 按 2s / 松 2s 循环 (用户要求,
            // 不能一直按着空格); 缺氧(_breaching)切持续按直到恢复。松开相头会
            // 慢慢下沉, 2s 内掉不到危险线, <40% 兜底会立即切持续按。
            mod.getInputControls().release(Input.SNEAK);
            if (_breaching) {
                mod.getInputControls().hold(Input.JUMP);
            } else {
                if (_breathPaceTimer.elapsed()) {
                    _breathJumpPhase = !_breathJumpPhase;
                    _breathPaceTimer.reset();
                }
                if (_breathJumpPhase) {
                    mod.getInputControls().hold(Input.JUMP);
                } else {
                    mod.getInputControls().release(Input.JUMP);
                }
            }
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
        _breaching = false;
        _breathJumpPhase = true;
        _breathPaceTimer.reset();
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

    /**
     * (2026-09-11) 跳跃卡死诊断 —— 纯只读, 零行为改动。
     *
     * 用户报"baritone 爬台阶时一个简单的旋转跳怎么都跳不上去, 跳几次就超时变
     * wander, 下次导航又选同一条路"。会话 20260911-151743 已抓到 9 条硬样本:
     * baritone 真的强制按下了前进+跳跃 (bIn[fwd=true jump=true]), 但玩家
     * hSpeed≈0.000, 同一格内 y 只上下震荡 0.33 格 (正常跳跃应上升约 1.25 格),
     * 持续 2~6s 后被超时。
     *
     * 现有日志不足以区分三种成因, 所以先补现场快照再定方案:
     *   ① 跳起撞头 (头顶紧邻有实心) — 处置: 挖掉;
     *   ② baritone 规划了不存在的落点 / 前方是 1 格坎+头顶封顶 — 处置: 挖/抬;
     *   ③ 玩家其实卡在半空 (非跳跃动作) — 处置: 换路。
     *
     * 落盘: logs/altoclef/<会话>/altoclef_diag.log 的 [JumpStuck] 行。
     */
    private Task tickJumpStuck(AltoClef mod) {
        if (!isJumpStuck(mod)) {
            _jumpStuckTicks = 0;
            return null;
        }
        _jumpStuckTicks++;
        if (_jumpStuckTicks < JUMP_STUCK_TICKS) return null;

        if (_jumpStuckLogTimer.elapsed()) {
            _jumpStuckLogTimer.reset();
            logJumpStuck(mod);   // 只读现场快照, 供复盘
        }

        long now = WorldHelper.getTicks();
        if (now < _jumpFixCooldownUntil) return null;
        // 水里强制按 JUMP 是游泳上浮 (baritone 的正常行为), 不是"跳不上去"。
        // 水面场景交给 [SwimUp] / GoToSurfaceTask, 这里不挖。
        if (mod.getPlayer().isTouchingWater()) return null;

        BlockPos obstacle = JumpObstructionHelper.findObstruction(mod);
        if (obstacle != null) {
            // 有明确挡路的方块 → 挖掉它。挖完路径自然重算 (改世界是最根本的杠杆,
            // 也最拟人)。
            _jumpFixCooldownUntil = now + JUMP_FIX_COOLDOWN_TICKS;
            _jumpStuckTicks = 0;
            Debug.logToFile("altoclef_diag.log", "[JumpStuck] clearing obstruction " + obstacle
                    + " player=" + mod.getPlayer().getBlockPos() + " -> DestroyBlockTask");
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            return new DestroyBlockTask(obstacle);
        }

        // 没找到挡路方块 → 不是"挖一格就能过"的卡死 (baritone 规划了做不到的落点 /
        // 人在半空)。只能撤销路径逼它重算另一条路。
        _jumpFixCooldownUntil = now + JUMP_REPATH_COOLDOWN_TICKS;
        _jumpStuckTicks = 0;
        registerSameSpotStuck(mod);
        Debug.logToFile("altoclef_diag.log", "[JumpStuck] no obstruction at "
                + mod.getPlayer().getBlockPos() + " -> forceCancel to re-route (sameSpot="
                + _jumpSameSpotCount + ")");
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return null;
    }

    /** 检测口径: baritone 强制按住跳跃键, 但水平速度 ≈ 0。 */
    private boolean isJumpStuck(AltoClef mod) {
        boolean jumpForced;
        try {
            jumpForced = mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(Input.JUMP);
        } catch (Throwable t) {
            return false;
        }
        if (!jumpForced) return false;
        Vec3d v = mod.getPlayer().getVelocity();
        return Math.hypot(v.x, v.z) < 0.05;
    }

    /** 同一格反复卡 → 计数, 达上限进长冷却 (防"挖不出 → 换路 → 又选回来"抖动)。 */
    private void registerSameSpotStuck(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        if (_jumpLastStuckPos != null
                && Math.abs(p.getX() - _jumpLastStuckPos.getX()) <= 1
                && Math.abs(p.getZ() - _jumpLastStuckPos.getZ()) <= 1) {
            _jumpSameSpotCount++;
        } else {
            _jumpLastStuckPos = p;
            _jumpSameSpotCount = 1;
        }
        if (_jumpSameSpotCount >= JUMP_SAME_SPOT_LIMIT) {
            _jumpFixCooldownUntil = WorldHelper.getTicks() + JUMP_SAME_SPOT_COOLDOWN_TICKS;
            _jumpSameSpotCount = 0;
            Debug.logToFile("altoclef_diag.log", "[JumpStuck] same spot " + p + " stuck "
                    + JUMP_SAME_SPOT_LIMIT + "x -> backing off "
                    + (JUMP_SAME_SPOT_COOLDOWN_TICKS / 20) + "s");
        }
    }

    private void logJumpStuck(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        Vec3d v = mod.getPlayer().getVelocity();
        Direction facing = mod.getPlayer().getHorizontalFacing();
        BlockPos ahead = p.offset(facing);
        String goal;
        try {
            Goal g = mod.getClientBaritone().getPathingBehavior().getGoal();
            goal = g == null ? "null" : g.toString();
        } catch (Throwable t) {
            goal = "?";
        }
        Debug.logToFile("altoclef_diag.log",
                "[JumpStuck] " + String.format("%.1f", _jumpStuckTicks / 20.0) + "s"
                        + " pos=" + p
                        + " xyz=(" + String.format("%.2f", mod.getPlayer().getX())
                        + "," + String.format("%.2f", mod.getPlayer().getY())
                        + "," + String.format("%.2f", mod.getPlayer().getZ()) + ")"
                        + " vel=(" + String.format("%.2f", v.x) + "," + String.format("%.2f", v.y)
                        + "," + String.format("%.2f", v.z) + ")"
                        + " onGround=" + mod.getPlayer().isOnGround()
                        + " inWater=" + mod.getPlayer().isTouchingWater()
                        + " facing=" + facing
                        + " blocks[below=" + bk(mod, p.down())
                        + " feet=" + bk(mod, p)
                        + " up1=" + bk(mod, p.up(1))
                        + " up2=" + bk(mod, p.up(2))
                        + " up3=" + bk(mod, p.up(3))
                        + " ahead=" + bk(mod, ahead)
                        + " aheadUp1=" + bk(mod, ahead.up(1))
                        + " aheadUp2=" + bk(mod, ahead.up(2)) + "]"
                        + " goal=" + goal);
    }

    /** 方块名 (诊断用, 失败不抛)。 */
    private static String bk(AltoClef mod, BlockPos pos) {
        try {
            return Registry.BLOCK.getId(mod.getWorld().getBlockState(pos).getBlock()).getPath();
        } catch (Throwable t) {
            return "?";
        }
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
