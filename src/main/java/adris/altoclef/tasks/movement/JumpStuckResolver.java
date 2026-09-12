package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.JumpObstructionHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;

/**
 * (2026-09-12) 跳跃卡死「检测 + 挖障处置」共享件。
 *
 * 背景: 原实现只挂在 CustomBaritoneGoalTask.tickJumpStuck, 且检测要求 baritone
 * 【连续】20 tick 强制按住跳跃键 (isInputForcedDown(JUMP))。会话 20260912-101305
 * 10:19:00-10:19:19 实锤两个缺口:
 *   ① ExploreCaveTask 直接继承 Task, 根本不走这段代码 — bot 在 (117,-13,102)
 *      原地弹跳 19s (yRange=1.25, hSpeed=0.000), 兜底只是每 6s 换一个同样
 *      不可达的洞内空气目标 ([CaveExplore] no progress ×3), 用户左键挖掉
 *      挡路方块才脱困;
 *   ② bIn[jump] 只在起跳瞬间为 true, 空中即释放 → "连续 20 tick 强制跳跃"
 *      的 streak 永远凑不满, 检测死代码化。
 *
 * 新检测口径: 水平速度 ≈ 0 的【连续】streak 内, 出现过以下任一证据即视为
 * "卡死"而非"站立等待":
 *   - baritone 强制过跳跃键 (原地弹跳);
 *   - 垂直速度先上冲 (>+BONK_VY) 后急坠 (<-BONK_VY) — 撞头弹回的弹跳特征;
 *   - baritone 强制按着前进 (贴地顶墙: 会话 20260912-111130 实锤 bIn[fwd=true]
 *     而 hSpeed 0.019~0.064 抖动 22s+, 全程不跳不撞)。
 * 纯站立等待 (算路中 vel.y≈-0.078, bIn 全 false) 不满足证据, 不处置。
 *
 * 处置 = 「挖障碍」: 改世界 → baritone 重算时路径自然变 (最根本的杠杆, 也最
 * 拟人 — 等价于用户手动左键)。baritone 没有路径级禁行注入 (IPathingBehavior
 * 只暴露 hasPath/isPathing/getPath/getGoal/forceCancel/cancelEverything;
 * Settings.blacklistClosestOnFailure 与 #blacklist 都挂在
 * GetToBlockProcess/MineProcess 上, 而 altoclef 走 CustomGoalProcess 拿不到
 * 那份黑名单), 所以"不重复选失败路线"只有两个杠杆:
 *   ① 挖掉障碍物改世界  ② forceCancel 撤销路径逼它重算。
 *
 * 所有状态 (streak / 冷却 / 同格计数) 必须放【static】—— 导航任务实例会被
 * 频繁重建, 实例层冷却会被新实例绕过 (20de6b0c 教训); 且两个调用方
 * (CustomBaritoneGoalTask / ExploreCaveTask) 同一时间只有一个是主任务,
 * static 状态跨任务交换保持连续, 卡点记忆不丢。
 */
public final class JumpStuckResolver {

    private JumpStuckResolver() {}

    // ===== 检测 =====
    /** 连续 1s (20 tick) 水平近零速 + 卡死证据才判定。 */
    private static final int STUCK_TICKS = 20;
    /** 水平速度低于此值视为原地 (walk 约 0.2/tick; 0.1 容纳顶墙抖动 0.019~0.064,
     *  会话 20260912-111130 实锤)。 */
    private static final double HSPEED_STUCK = 0.1;
    /** 弹跳证据阈值: 上冲/急坠的垂直速度分界 (跳跃起跳约 +0.42, 撞头反弹 -0.3~)。 */
    private static final double BONK_VY = 0.15;

    private static int _streakTicks = 0;
    private static boolean _sawJumpForced = false;
    private static boolean _sawFwdForced = false;
    private static boolean _risingSeen = false;
    private static boolean _bonkSeen = false;

    /** 现场快照日志节流 (2s 一条)。 */
    private static final TimerGame _logTimer = new TimerGame(2);

    // ===== 处置冷却 (static: 实例频繁重建会绕过实例层冷却) =====
    /** 挖障与换路共用一个冷却。(2026-09-12 用户"超时时长不要超过 1.5s, 影响
     *  观感": 检测 1s + 冷却 1s, 卡住后每个节奏点都 ≤1.5s。) */
    private static final int COOLDOWN_TICKS = 20;
    private static int _cooldownUntil = 0;
    /** 同一格反复处置: 达上限不再原地纠缠, 强制 wander 脱离 (2026-09-12 用户
     *  "兜底机制没有强行 w 吗" — 旧 30s 被动冷却期内谁也不动, 观感极差)。 */
    private static final int JUMP_SAME_SPOT_LIMIT = 4;
    private static BlockPos _lastStuckPos = null;
    private static int _sameSpotCount = 0;
    /** 同一格重复挖障计数: 挖了 2 次还在原地 (baritone builder 拒挖/挖不掉)
     *  → 立即强制 wander, 不再第三次挖。 */
    private static final int SAME_POS_DIG_LIMIT = 2;
    private static BlockPos _lastDigPos = null;
    private static int _digCount = 0;
    /** 共享 wander 任务 (强制脱离用)。 */
    private static final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5);

    /**
     * 每 tick 调用 (放在任务 onTick 最前面)。
     *
     * @return 处置任务 (挖障时为 DestroyBlockTask, 调用方应直接 return 它);
     *         null = 无事发生 / 只做了 forceCancel 换路 / 冷却中。
     */
    public static Task tick(AltoClef mod) {
        Vec3d v = mod.getPlayer().getVelocity();
        boolean hSlow = Math.hypot(v.x, v.z) < HSPEED_STUCK;
        if (!hSlow) {
            resetStreak();
            return null;
        }
        _streakTicks++;
        if (isInputForced(mod, Input.JUMP)) _sawJumpForced = true;
        if (isInputForced(mod, Input.MOVE_FORWARD)) _sawFwdForced = true;
        if (v.y > BONK_VY) {
            _risingSeen = true;
        } else if (_risingSeen && v.y < -BONK_VY) {
            _bonkSeen = true;
        }
        if (_streakTicks < STUCK_TICKS) return null;
        // 证据三选一: 强制跳跃 (原地弹跳) / 撞头弹回 / 强制前进 (贴地顶墙,
        // 20260912-111130 实锤: bIn[fwd=true] 而 hSpeed 0.019~0.064 抖动 22s+)。
        // 三者皆无 = 纯站立等待 (算路等), 不处置。
        if (!(_sawJumpForced || _bonkSeen || _sawFwdForced)) return null;

        if (_logTimer.elapsed()) {
            _logTimer.reset();
            logSnapshot(mod);
        }

        int now = WorldHelper.getTicks();
        if (now < _cooldownUntil) return null;

        // (2026-09-12 会话 20260912-132300) 水里强制按 JUMP 通常是游泳上浮
        // (baritone 的正常行为), 旧版这里一律 return null —— 但"岸边浅水"是
        // 所有兜底一起失效的死角: 实锤现场为岸边 1 格水, 头顶 up2=spruce_leaves
        // 顶住上浮、上岸方向还有树叶挡前进, hSpeed 与 Δy 恒 0 持续 40s;
        // [SwimUp] 每 2s "触发→失速释放→立刻重触发" 把 baritone 与进度检查器
        // 全挡在门外, 而这一行水中豁免又把唯一的挖障兜底短路 → 三个兜底同时失效。
        // 例外两条 (其余开阔水面照旧不挖, 交 [SwimUp] / GoToSurfaceTask):
        //   ① 上浮通道被封: 头顶正上方 (up2) 有碰撞方块;
        //   ② 挡路物是"baritone 认为可穿过"的植物类 (树叶/azalea/草):
        //      builder 拒挖、物理上却挡人, 只能直挖。
        BlockPos obstacle = JumpObstructionHelper.findObstruction(mod);
        if (mod.getPlayer().isTouchingWater()
                && !ascentCapped(mod)
                && !(obstacle != null && isBaritonePassable(mod, obstacle))) {
            return null;
        }

        if (obstacle != null) {
            // 有明确挡路的方块 → 挖掉它。挖完路径自然重算 (改世界是最根本的杠杆,
            // 也最拟人 — 等价于用户手动左键)。
            // 同格挖了 2 次还在原地 (baritone builder 拒挖非实心植物等) → 强制 wander。
            if (obstacle.equals(_lastDigPos)) {
                _digCount++;
            } else {
                _lastDigPos = obstacle;
                _digCount = 1;
            }
            _cooldownUntil = now + COOLDOWN_TICKS;
            resetStreak();
            if (_digCount >= SAME_POS_DIG_LIMIT) {
                _digCount = 0;
                _lastDigPos = null;
                Debug.logToFile("altoclef_diag.log", "[JumpStuck] dig x" + SAME_POS_DIG_LIMIT
                        + " no escape at " + obstacle + " -> forcing wander");
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                _wanderTask.resetWander();
                return _wanderTask;
            }
            // (2026-09-12) 分流判据: baritone builder 只对自己视为实心的方块执行
            // 破坏, "可穿过"的 (树叶/azalea/草) 一律拒挖 (111853 实锤连挖 8 次
            // 方块纹丝不动) → 这些走 InstantBreakTask 直挖。
            boolean passable = isBaritonePassable(mod, obstacle);
            Debug.logToFile("altoclef_diag.log", "[JumpStuck] clearing obstruction " + obstacle
                    + " passable=" + passable + " solid=" + WorldHelper.isSolid(mod, obstacle)
                    + " player=" + mod.getPlayer().getBlockPos()
                    + " -> " + (passable ? "InstantBreakTask" : "DestroyBlockTask"));
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            if (passable) {
                return new InstantBreakTask(obstacle);
            }
            return new DestroyBlockTask(obstacle);
        }

        // 没找到挡路方块 → 不是"挖一格就能过"的卡死 (baritone 规划了做不到的落点 /
        // 人在半空)。只能撤销路径逼它重算另一条路; 同格反复换路也没用 → 强制 wander
        // (旧的 30s 被动冷却期内谁也不动, 已废除)。
        _cooldownUntil = now + COOLDOWN_TICKS;
        resetStreak();
        if (registerSameSpotStuck(mod)) {
            Debug.logToFile("altoclef_diag.log", "[JumpStuck] same spot re-route x"
                    + JUMP_SAME_SPOT_LIMIT + " -> forcing wander");
            _wanderTask.resetWander();
            return _wanderTask;
        }
        Debug.logToFile("altoclef_diag.log", "[JumpStuck] no obstruction at "
                + mod.getPlayer().getBlockPos() + " -> forceCancel to re-route (sameSpot="
                + _sameSpotCount + ")");
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return null;
    }

    private static boolean isInputForced(AltoClef mod, Input input) {
        try {
            return mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(input);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * (2026-09-12) 头顶正上方 (up2 = 头部上一格) 是否被有碰撞的方块封住。
     * 玩家高 1.8: 脚在 p, 头占 p.up(1), 上浮需要 p.up(2) 有空间。树叶/石头
     * 都有碰撞形状; 水/空气/草 (无碰撞) 不算封住。
     */
    private static boolean ascentCapped(AltoClef mod) {
        try {
            BlockPos above = mod.getPlayer().getBlockPos().up(2);
            return !mod.getWorld().getBlockState(above)
                    .getCollisionShape(mod.getWorld(), above).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * (2026-09-12) 该方块是否属于"baritone 认为可穿过"的一类 (非不透明满方块:
     * 树叶/azalea/草/藤等)。baritone builder 对这类方块拒绝执行破坏
     * (111853 实锤: 连挖 8 次方块纹丝不动), 必须用 InstantBreakTask 直挖。
     */
    private static boolean isBaritonePassable(AltoClef mod, BlockPos pos) {
        try {
            return !mod.getWorld().getBlockState(pos).isOpaqueFullCube(mod.getWorld(), pos);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void resetStreak() {
        _streakTicks = 0;
        _sawJumpForced = false;
        _sawFwdForced = false;
        _risingSeen = false;
        _bonkSeen = false;
    }

    /**
     * 同一格反复卡 → 计数。
     *
     * @return true = 达上限 (调用方应强制 wander 脱离, 不再原地纠缠)。
     */
    private static boolean registerSameSpotStuck(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        if (_lastStuckPos != null
                && Math.abs(p.getX() - _lastStuckPos.getX()) <= 1
                && Math.abs(p.getZ() - _lastStuckPos.getZ()) <= 1) {
            _sameSpotCount++;
        } else {
            _lastStuckPos = p;
            _sameSpotCount = 1;
        }
        if (_sameSpotCount >= JUMP_SAME_SPOT_LIMIT) {
            _sameSpotCount = 0;
            _lastStuckPos = null;
            return true;
        }
        return false;
    }

    private static void logSnapshot(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        Vec3d v = mod.getPlayer().getVelocity();
        Direction facing = mod.getPlayer().getHorizontalFacing();
        BlockPos ahead = p.offset(facing);
        String goal;
        try {
            baritone.api.pathing.goals.Goal g = mod.getClientBaritone().getPathingBehavior().getGoal();
            goal = g == null ? "null" : g.toString();
        } catch (Throwable t) {
            goal = "?";
        }
        Debug.logToFile("altoclef_diag.log",
                "[JumpStuck] " + String.format("%.1f", _streakTicks / 20.0) + "s"
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
}
