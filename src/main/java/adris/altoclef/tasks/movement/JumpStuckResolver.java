package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.util.helpers.JumpObstructionHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.entity.effect.StatusEffects;
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

    // ===== 处置任务独占 (2026-09-12 会话 20260912-134716 实锤) =====
    /**
     * 【脑残硬编码 ①：处置任务只活 1 tick 的元凶】
     *
     * 本文件 tick() 每 tick 被父任务调用一次, 返回的处置任务会成为父任务的子任务。
     * 但任务框架 (TaskSystem.Task.tickInternal L79-123) 的规则是: **父任务下一 tick
     * 若返回别的任务或 null, 刚派出的子任务立刻被 stop**。而旧版这里在返回处置任务
     * 之前就把 _cooldownUntil 设成 now+20 → 下一 tick 本函数直接 return null →
     * 父任务 (CustomBaritoneGoalTask) 继续往下走, 落到 [SwimUp] 分支 return null →
     * 框架把 InstantBreakTask / 挖障任务 / wander 全部 stop 掉。
     *
     * 实锤 (tasks.log): `<Instant break at 140,64,44>` START→STOP 全是 took=49~51ms
     * = **1 tick**。也就是说:
     *   - 挖树叶需要的连续左键窗口 = 1 tick (50ms), 方块永远差几 tick;
     *   - InstantBreakTask 自己 1.5s 的超时 (TIMEOUT_TICKS=30) 从来没走到过;
     *   - TimeoutWanderTask(5) 需要在 5 格内持续探索, 也只拿到 1 tick;
     *   - 每秒换一次挖障目标 ((140,64,43) ↔ (140,64,44)), 破坏进度反复清零。
     */
    private static Task _activeOrder = null;
    private static int _orderStartTick = 0;
    /** 独占硬顶 (处置任务自己的 isFinished 不可靠/永不成立时兜底收权)。 */
    private static final int ORDER_LEASE_TICKS = 200;

    // ===== 处置冷却 (static: 实例频繁重建会绕过实例层冷却) =====
    /** 【脑残硬编码 ②】挖障与换路共用一个冷却。(2026-09-12 用户"超时时长不要超过
     *  1.5s, 影响观感": 检测 1s + 冷却 1s, 卡住后每个节奏点都 ≤1.5s。)
     *  注意: 冷却现在只在【处置任务结束后】起算 (见 tick() 第 0 步), 不再在派发
     *  那一刻就起算 —— 否则就是上面说的"派出即被判 null 打断"。 */
    private static final int COOLDOWN_TICKS = 20;
    private static int _cooldownUntil = 0;
    /** 同一格反复处置: 达上限不再原地纠缠, 强制 wander 脱离 (2026-09-12 用户
     *  "兜底机制没有强行 w 吗" — 旧 30s 被动冷却期内谁也不动, 观感极差)。 */
    private static final int JUMP_SAME_SPOT_LIMIT = 4;
    private static BlockPos _lastStuckPos = null;
    private static int _sameSpotCount = 0;
    /** 【脑残硬编码 ④】同一格重复挖障计数: 挖了 2 次还在原地 (baritone builder
     *  拒挖/挖不掉) → 立即强制 wander, 不再第三次挖。
     *  旧口径的错: 它数的是【派发次数】而不是【失败的挖掘】—— 在"每次只活 1 tick"
     *  的年代, 2 次 = 2 tick 的点击, 树叶连破坏进度都没攒起来就被判"挖不掉"。
     *  现在 1 次 = 一个完整租约 (最长 1.5s, InstantBreakTask 自己结束), 口径才成立。 */
    private static final int SAME_POS_DIG_LIMIT = 2;
    private static BlockPos _lastDigPos = null;
    private static int _digCount = 0;

    // ===== (2026-09-13) 挖掘豁免: 正在破坏方块时不判"跳跃卡死" =====
    // 用户实锤 (会话 20260913-122323, 前往传送门长途导航): 目标 (167,87,-453) 距
    // bot 300+ 格且垂直差 130 格, 现场实心岩层只能挖过去。baritone 挖障途中
    // **水平速度天然为 0** (人在挖方块), 且持续按住 MOVE_FORWARD → 三选一证据
    // (强制前进) 恒成立 → 每 (检测 20 tick + 挖掘 ~20 tick + 冷却 20 tick) = 3 秒
    // 就判一次卡死 → forceCancel() 把 baritone 刚算好的长途路径作废、派一格挖障
    // → 重算 A*。tasks.log 实锤: 55 分钟 874 次 <Destroy block>, z 只推进 138 格,
    // 每次挖格伴随一次完整重算 = 用户说的"频繁重新导航"。
    //
    // 对齐既有语义: MovementProgressChecker.check() (util/progresscheck:40) 对
    // "正在破坏方块"本就有专门分支 — 走 _mineChecker 判挖掘进度, **不计距离失败**。
    // 本处是同一件事在 JumpStuckResolver 里的缺失, 不是新增策略。
    //
    // 只做"不累计 streak"这一件事: 真卡死 (顶墙/弹跳) 时 bot 并未在挖方块,
    // isBreakingBlock()=false, 检测与处置链路原样保留。
    private static int _digGuardSinceTick = 0;
    /** 豁免连续生效多久后开始打诊断 (5s): 观察"豁免期间 baritone 是否真在推进"。 */
    private static final int DIG_GUARD_LOG_TICKS = 100;
    private static final TimerGame _digGuardLogTimer = new TimerGame(5);

    // ===== (2026-09-14) 岩浆豁免: 在岩浆里不判"水平卡死" =====
    // 第 4 个"合法零速状态" (前三个: 挖方块中 / 下落中 / 垂直上不去)。
    //
    // 现场实锤 (会话 20260914-095600, 下界岩浆池 -223,22,-307, 用户"差一秒成功上岸,
    // 药水效果停了"):
    //   09:56:39.020 [LavaEscape] pos=-223,30,-307 pathing=true  ← baritone 首次算出
    //                                                              上岸路径 (新 jar 的
    //                                                              allowSwimThroughLava
    //                                                              生效, 方向是对的)
    //   09:56:41.051 [LavaEscape] fireRes=false                  ← 防火药水到期
    //   09:56:41.902 [JumpStuck] 1.0s pos=-223,31,-305
    //                blocks[below=lava feet=lava up1=air up2=air up3=air
    //                       ahead=cobblestone aheadUp1=air]      ← 已浮到岩浆表面,
    //                                                              前方一格就是岸
    //   09:56:41.903 [JumpStuck] clearing obstruction -223,31,-304 solid=true
    //                -> DestroyBlockTask                          ← forceCancel 掉刚算好
    //                                                              的上岸路径, 改成挖岸石
    //   09:56:43.101 [LavaEscape] pathing=false
    //   09:56:41→48 血 13.0→8.16→4.25→1.64→死; 挖那格花了 6.7s 没挖完
    //
    // 成因: 在岩浆里按 JUMP 上浮时水平速度天然为 0 (垂直位移不算水平移动), 而
    // EscapeFromLavaTask.onTick 全程强制 hold(JUMP) → "证据三选一"里的强制跳跃恒成立
    // → 检测 1s + 挖障/冷却各 1s 就是一个死循环节奏点。
    //
    // 为什么旧版一格都没覆盖: 水面豁免 (:248) 的判据是 isTouchingWater(), 而
    // **isTouchingWater() 对岩浆返回 false** —— 灵魂沙那次已吃过同一个亏。故岩浆
    // 场景对"岸边浅水"那套兜底是完全裸奔的。
    //
    // 保留 ascentCapped 例外: 头顶正上方被封 = 上浮通道死了, 此时"不干预"等于永久
    // 静止, 必须放行给挖障兜底 (敞口岩浆池如本次现场不会走到这里)。
    private static int _lavaGuardSinceTick = 0;
    private static final int LAVA_GUARD_LOG_TICKS = 100;
    private static final TimerGame _lavaGuardLogTimer = new TimerGame(5);

    // ===== (2026-09-21) 垂直垫柱豁免: 垫柱跳跃是又一个"合法零水平速度"状态 =====
    //
    // 现场实锤 (会话 20260921-185215, 末地 Pillaring up, goal=GoalYLevel{y=107},
    // diag.log [JumpStuck] 行): MovementPillar 全程水平速度≈0 (vel=(0.01,0.00,0.00),
    // onGround=false), baritone 强制 JUMP → 1s 检测 + "强制跳跃"证据恒成立 →
    // 每 1s 判一次卡死 → forceCancel 作废路径 + 全量 A* 重算 (~103 万 movements,
    // open set 1.6 万, 每次约 1s) = 用户看到的"搭一格停一下"; 同格计数到 4 还会
    // 强制 wander 把 bot 拽离垫柱 (18:53:27-30, 约 2.3s)。18:35 会话 GoalXZ 垫柱
    // 过 end stone 地形时同样在误判挖方块, 同族问题一并覆盖。
    //
    // 与挖掘豁免 (:isBreakingBlock)/岩浆豁免 (:isInLava) 同一性质: 垂直推进中
    // 水平速度天然为 0, 不是卡死。判据 (两层, 缺一不可):
    //   ① 最近 CLIMB_GAIN_WINDOW_TICKS 内脚下 Y 增加过 (垫柱在真推进; 真撞头顶棚
    //     /原地弹跳无 Y 增益, 窗口过后检测自然恢复);
    //   ② 当前处于垂直姿态: 离地 (空中) 或 baritone 正强制跳跃 —— 排除
    //     "刚爬上坡就撞墙" 的水平卡死 (那种姿态在地面且强制的是前进不是跳)。
    // 长程爬升真卡死另有 CustomBaritoneGoalTask 的 20s 看门狗 (2026-09-15)
    // 兜底, 不在本件职责内。static 口径同上: 任务实例频繁重建。
    //
    // (2026-09-21 2043 二修) 首版用"全局历史最高 Y"当增益基准, 被会话内更高的
    // 位置污染 (203750 会话实锤: 20:40:39 wander 到 y=74 后 _climbBestY=74,
    // 之后 20:40:34-37 垫柱 y 67→69 明明在涨却永不算"创新高", 窗口恒过期,
    // 豁免整个失效 = 修复失败)。正确语义是"最近窗口内 Y 是否增加过", 与绝对
    // 高度无关 —— 改为只记"上次 Y 增加的 tick"。垫柱一格约 1.2s(24 tick) < 40
    // tick 窗口, 豁免恒有效; 同格弹跳/撞头 Y 不增加, 2s 后检测恢复。
    private static int _lastPlayerY = Integer.MIN_VALUE;
    /** 上次脚下 Y【增加】的 tick (同格不动/下降不刷新 —— 增益才是推进证据)。 */
    private static int _lastYGainTick = Integer.MIN_VALUE + 1;
    /** Y 增益新鲜窗口: 垫柱一个跳跃周期约 1.2s (+1 格), 2s 容忍一个空拍周期。 */
    private static final int CLIMB_GAIN_WINDOW_TICKS = 40;

    /** 共享 wander 任务 (强制脱离用)。 */
    private static final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5);

    /**
     * 每 tick 调用 (放在任务 onTick 最前面)。
     *
     * 第 0 步是「处置任务独占续租」: 只要上一次派发的处置任务还没结束 (或未超
     * ORDER_LEASE_TICKS), 就原样把它返回给父任务, 保证它能连续拿到 tick。调用方
     * (CustomBaritoneGoalTask / ExploreCaveTask) 只需把返回值直接 return 出去。
     *
     * @return 处置任务 (挖障时为 InstantBreakTask / DestroyBlockTask, 脱离时为
     *         TimeoutWanderTask); null = 无事发生 / 只做了 forceCancel 换路 / 冷却中。
     */
    public static Task tick(AltoClef mod) {
        // (2026-09-21) Y 增益追踪必须放在所有提前 return 之前 —— 挖掘/岩浆豁免
        // 分支会先 return null, 若追踪放在它们之后, 豁免期间 (正是跳跃/上浮推进中)
        // 的 Y 增益会丢失, 回到主流程时 bestY 已过期。
        int nowTick0 = WorldHelper.getTicks();
        int playerY0 = mod.getPlayer().getBlockPos().getY();
        if (playerY0 != _lastPlayerY) {
            if (playerY0 > _lastPlayerY) {
                _lastYGainTick = nowTick0;
            }
            _lastPlayerY = playerY0;
        }
        // 0) (2026-09-12) 进行中的处置任务优先续租。处置任务必须"独占"到它自己
        //    报告结束为止 —— 否则父任务下一 tick 的 SwimUp/本体 return null 会
        //    立刻把它换掉 (实锤: 每次只活 1 tick)。期间不重估、不 forceCancel,
        //    撤销路径会把正在进行的挖掘/探索打断。
        if (_activeOrder != null) {
            // (2026-09-12 14:00:53 假死现场, 这条必须保住) 防"处置任务成为自己的后代"。
            // 处置任务内部可能再跑出一个 CustomBaritoneGoalTask 子类: DestroyBlockTask
            // 发现"要挖的方块就在自己脚下且它下面危险"时返回 RunAwayFromPositionTask
            // —— 而 RunAwayFromPositionTask 正是 CustomBaritoneGoalTask, onTick 第一件
            // 事就是回来调本函数。若无条件续租, 同一个 DestroyBlockTask 会被发回给它
            // 自己的后代 → 单 tick 内 128 层嵌套 (每层一次 baritone setGoalAndPath),
            // 日志只留一条 TASK CHAIN RECURSION OVERFLOW 然后渲染线程再无输出, 世界
            // 保存线程却照常工作 = 假死 (用户"崩溃了")。
            // 判据: 本 tick 的链里已经出现这个处置任务实例 = 它就在当前递归栈上。
            // TaskChain._cachedTaskChain 每 tick 开头 clear, 每个被 tick 的任务都会
            // addTaskToChain, 所以这一条对"深层嵌套"是精确的。用 == 而非 equals:
            // Task.equals 走 isEqual (同类同参数即相等), 同坐标的另一个任务会误判。
            if (isOrderOnStack(mod)) return null;
            int leaseNow = WorldHelper.getTicks();
            boolean leaseOver = leaseNow - _orderStartTick >= ORDER_LEASE_TICKS;
            boolean done;
            try {
                done = _activeOrder.isFinished(mod);
            } catch (Throwable t) {
                // TimeoutWanderTask 在 onStart 之前被问 isFinished 会 NPE
                // (_origin 为 null)。视为"未完成", 下一 tick 它 tick 到 onStart
                // 后即可正常判定。
                done = false;
            }
            if (!done && !leaseOver) return _activeOrder;
            _activeOrder = null;
            _cooldownUntil = leaseNow + COOLDOWN_TICKS;
            Debug.logToFile("altoclef_diag.log", "[JumpStuck] order released (done=" + done
                    + " leaseOver=" + leaseOver + ") at " + mod.getPlayer().getBlockPos());
        }

        // (2026-09-13) 挖掘豁免 —— 见字段区注释。必须在读 velocity 之前返回:
        // 挖掘中 hSpeed 恒 0, 走下面的 streak 累积只会把正常挖掘读成卡死。
        if (mod.getControllerExtras().isBreakingBlock()) {
            int digNow = WorldHelper.getTicks();
            if (_digGuardSinceTick == 0) {
                _digGuardSinceTick = digNow;
                // TimerGame 新建即到期, 首次进入必须显式 reset。
                _digGuardLogTimer.reset();
            } else if (digNow - _digGuardSinceTick >= DIG_GUARD_LOG_TICKS
                    && _digGuardLogTimer.elapsed()) {
                _digGuardLogTimer.reset();
                Debug.logToFile("altoclef_diag.log", "[DigGuard] mining exemption held "
                        + (digNow - _digGuardSinceTick) + " ticks, pos="
                        + mod.getPlayer().getBlockPos()
                        + " baritonePathing=" + mod.getClientBaritone().getPathingBehavior().isPathing()
                        + " breaking=" + mod.getControllerExtras().getBreakingBlockPos());
            }
            resetStreak();
            return null;
        }
        _digGuardSinceTick = 0;

        // (2026-09-14) 岩浆豁免 —— 见字段区注释。必须同样在累积 streak 之前返回:
        // 在岩浆里上浮的水平零速会一路攒满 20 tick, 等离开岩浆那一刻立刻触发处置。
        if (mod.getPlayer().isInLava() && !ascentCapped(mod)) {
            int lavaNow = WorldHelper.getTicks();
            if (_lavaGuardSinceTick == 0) {
                _lavaGuardSinceTick = lavaNow;
                // TimerGame 新建即到期, 首次进入必须显式 reset。
                _lavaGuardLogTimer.reset();
            } else if (lavaNow - _lavaGuardSinceTick >= LAVA_GUARD_LOG_TICKS
                    && _lavaGuardLogTimer.elapsed()) {
                _lavaGuardLogTimer.reset();
                Debug.logToFile("altoclef_diag.log", "[LavaGuard] lava exemption held "
                        + (lavaNow - _lavaGuardSinceTick) + " ticks, pos="
                        + mod.getPlayer().getBlockPos()
                        + " y=" + String.format("%.2f", mod.getPlayer().getY())
                        + " baritonePathing="
                        + mod.getClientBaritone().getPathingBehavior().isPathing()
                        + " fireRes="
                        + mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE));
            }
            resetStreak();
            return null;
        }
        _lavaGuardSinceTick = 0;

        // (2026-09-21) 垂直垫柱豁免 —— 见字段区注释。必须与挖掘/岩浆豁免同层:
        // 在累积 streak 之前放行, 否则垫柱跳跃的水平零速会一路攒满 20 tick,
        // 等落到柱顶那一刻立刻触发 forceCancel (185215 会话每秒一次的实锤节奏)。
        // 两层判据缺一不可: 只看 Y 增益会放过"爬上坡后撞墙"的水平卡死
        // (那种情况离地=false 且强制的是前进), 只看姿态会放过"原地悬停算路"。
        if (nowTick0 - _lastYGainTick < CLIMB_GAIN_WINDOW_TICKS
                && (!mod.getPlayer().isOnGround() || isInputForced(mod, Input.JUMP))) {
            resetStreak();
            return null;
        }

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
            // 冷却 / streak 由 issue() 在真正派发处置那一刻设置 (见 _activeOrder 注释:
            // 提前设冷却 = 派出的任务下一 tick 就被判 null 打断)。
            if (_digCount >= SAME_POS_DIG_LIMIT) {
                _digCount = 0;
                _lastDigPos = null;
                Debug.logToFile("altoclef_diag.log", "[JumpStuck] dig x" + SAME_POS_DIG_LIMIT
                        + " no escape at " + obstacle + " -> forcing wander");
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                _wanderTask.resetWander();
                return issue(mod, _wanderTask);
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
                return issue(mod, new InstantBreakTask(obstacle));
            }
            return issue(mod, new DestroyBlockTask(obstacle));
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
            return issue(mod, _wanderTask);
        }
        Debug.logToFile("altoclef_diag.log", "[JumpStuck] no obstruction at "
                + mod.getPlayer().getBlockPos() + " -> forceCancel to re-route (sameSpot="
                + _sameSpotCount + ")");
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return null;
    }

    /**
     * (2026-09-12) 当前 tick 的任务链里是否已经出现 {@link #_activeOrder}。
     * true = 处置任务已经在当前递归栈上 (它自己或它的后代又调回了本函数) →
     * 绝不能再把它发一遍, 否则同一实例成为自己的子任务 = 无限嵌套。
     */
    private static boolean isOrderOnStack(AltoClef mod) {
        try {
            TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
            if (chain == null) return false;
            for (Task t : chain.getTasks()) {
                if (t == _activeOrder) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * (2026-09-12) 派发一个处置任务并登记"独占租约"。
     *
     * 派发即登记: 之后每 tick 由 tick() 第 0 步把它原样返回给父任务, 直到
     * {@code isFinished} 或 ORDER_LEASE_TICKS 到期。**冷却从任务结束后才起算**,
     * 不再从派发那一刻起算 (那是"派出即打断"的根因)。
     */
    private static Task issue(AltoClef mod, Task task) {
        _activeOrder = task;
        _orderStartTick = WorldHelper.getTicks();
        _cooldownUntil = _orderStartTick + COOLDOWN_TICKS;
        resetStreak();
        Debug.logToFile("altoclef_diag.log", "[JumpStuck] ORDER " + task
                + " at " + mod.getPlayer().getBlockPos()
                + " (lease " + ORDER_LEASE_TICKS + "t)");
        return task;
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
