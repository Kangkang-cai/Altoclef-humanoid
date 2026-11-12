package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.RecoveryTuning;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

/**
 * Interacts with an entity while maintaining distance.
 *
 * The interaction is abstract.
 */
public abstract class AbstractDoToEntityTask extends Task implements ITaskRequiresGrounded {
    protected final MovementProgressChecker _progress = new MovementProgressChecker(5, 0.1, 5, 0.001, 2);
    private final double _maintainDistance;
    private final double _combatGuardLowerRange;
    private final double _combatGuardLowerFieldRadius;
    // (2026-08-30 迟滞) 一旦进入射程就保持"交战"状态, 直到明显远离才退回"靠近",
    // 消除位置卡在 reach 边界时每 tick 在靠近↔盾战间横跳 (日志 TaskPingPong)。
    private boolean _withinInteract = false;
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(10);
    // (2026-08-31 绕位贴稳, 用户) LOS 被挡要绕位找视线时, 选定一个绕位目标就
    // "粘住"一小段时间 (3s 或到达), 不再每 tick 在两个相邻候选块间翻来翻去
    // (日志: Getting to block <-> Getting to block 6x thrash, 近战不持续贴近)。
    private BlockPos _repositionTarget = null;
    private final TimerGame _repositionSwitchTimer = new TimerGame(3);

    // =========================================================================
    // (2026-09-09 遮挡消除钩子, 用户"猪灵交易被挡时先走动, 绕不开就挖掉眼前方块")
    // LOS 被挡先走既有绕位逻辑; 持续 OBSTRUCTION_PERSIST_SECONDS 仍没绕开 → 调
    // onObstructionPersistent 钩子, 子类可接管消除遮挡 (如挖方块)。默认返回 null
    // (2026-09-20 22:08 用户拍板"被挡两秒就应该挖了"): 8 → 2 秒。绕位先行意义
    // 不大 (绕位常绕不开墙), 快挖快打; 同拍 2s 宽限可见重置 (LOS_CLEAR_GRACE_SECONDS)
    // 与 persist 相同量级, 探头一瞬仍不清零。
    // = 维持绕位, 战斗等其他任务行为完全不变 (scope 收敛)。
    // =========================================================================
    // (2026-09-28 L1) 阈值统一收口到 RecoveryTuning —— 本地别名保留, 调用点零改动;
    // 与方块侧 InteractWithBlockTask.tickDetour 的对应量现在同一处可见 (调参只改一处)。
    private static final double OBSTRUCTION_PERSIST_SECONDS = RecoveryTuning.ENTITY_OBSTRUCTION_PERSIST_SECONDS;
    // (2026-09-20 计时器加固) 连续可见满这个时长才重置"持续被挡"计时 —— 末影人
    // 瞬移/探头制造的单拍可见不再把计时器打回零 (现场 20260920-213856 实锤:
    // 3 次 KillTimeout 全程 LOS-Obstruction 0 条 = 8s 持续条件永远凑不满,
    // 遮挡消除钩子死路, 用户设计的对策从未生效)。
    private static final double LOS_CLEAR_GRACE_SECONDS = RecoveryTuning.ENTITY_LOS_CLEAR_GRACE_SECONDS;
    private long _lastObstructionLogTick = Long.MIN_VALUE;
    // (2026-09-27 用户拍板 C) "迟滞圈内被挡改交战 (不再绕位)" 的取证日志节流 (1s)。
    private long _lastHoldCloseLogTick = Long.MIN_VALUE;

    // =========================================================================
    // (2026-09-28 L2) "持续被挡 / 连续可见" 这对计时改为**按目标 UUID 存活于 static map**。
    //
    // 为什么必须 static: 上层 (MobDefenseChain 每秒重建 <Killing entity.X>) 会把实例
    // 字段洗回初值, 计时从零开始 —— 2s 阈值永远凑不满。现场 20260927-222854 实证:
    // 击杀实例存活 2.1s / 3.4s / 7.8s / 9.1s / 11.7s / 14.6s (阈值恰 2s), 全场
    // [LOS-Obstruction] 0 条 = 清障从未触发过。而 0927 的 A+C 之后它是击杀链路
    // "被挡"场景**唯一**的自救计时源 (绕位已不可达, 另两条路只剩"挖除成功"或
    // "站桩到 15s kill timeout 拉黑换目标")。
    //
    // 索引: 0 = blockedSinceTick, 1 = clearSinceTick; 不存 = Long.MIN_VALUE。
    // 生命周期与 AbstractKillEntityTask._meleeEngageAnchor 同模式: **不按实例清**
    // (按实例清会误伤其它目标的计时), 靠容量上限自净; 陈旧残留由 LOS_TIMER_STALE_TICKS 兜。
    // =========================================================================
    private static final java.util.Map<java.util.UUID, long[]> _losTimersByTarget = new java.util.HashMap<>();

    private static long losTimerGet(java.util.UUID id, int idx) {
        long[] a = _losTimersByTarget.get(id);
        return a == null ? Long.MIN_VALUE : a[idx];
    }

    private static void losTimerSet(java.util.UUID id, int idx, long value) {
        if (_losTimersByTarget.size() > RecoveryTuning.LOS_TIMER_MAP_CAPACITY) {
            long now = WorldHelper.getTicks();
            _losTimersByTarget.entrySet().removeIf(en -> {
                long[] v = en.getValue();
                // (2026-11-12 扩 4 槽) hold 耐心/粘滞计时 (idx 2/3) 也纳入自净。
                long newest = Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3]));
                return newest != Long.MIN_VALUE && now - newest > RecoveryTuning.LOS_TIMER_STALE_TICKS;
            });
        }
        long[] a = _losTimersByTarget.computeIfAbsent(id, k -> new long[]{Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE});
        a[idx] = value;
        if (a[0] == Long.MIN_VALUE && a[1] == Long.MIN_VALUE
                && a[2] == Long.MIN_VALUE && a[3] == Long.MIN_VALUE) {
            _losTimersByTarget.remove(id);
        }
    }

    // (2026-09-14 接近失败诊断, 232015 场次) wander 两个触发点的取证日志节流
    // key (5s)。目的: 区分"目标从感知/候选消失"与"接近无进展", 并拿到触发时
    // 的目标距离 —— 232015 场次猪灵 2.1 格处反复触发, 疑似 enter 边界徘徊
    // (piglin 2.1-2.5 晃, 进不了 enter 2.0) 而非 baritone 无路径, 待日志证实。
    private long _lastNoTargetLogTick = Long.MIN_VALUE;
    private long _lastProgressFailLogTick = Long.MIN_VALUE;

    // (2026-09-18 追最后已知位置) 上一次追击的目标实体。目标从活跃感知消失
    // (20s 记忆过期) 但仍在最后已知宽限期 (30s) 内时, 继续走向冻结的最后
    // 位置, 到了还没重新感知到才真放弃。下挖/绕障途中视线断流丢目标的主修。
    private Entity _lastChaseTarget = null;

    public AbstractDoToEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        _maintainDistance = maintainDistance;
        _combatGuardLowerRange = combatGuardLowerRange;
        _combatGuardLowerFieldRadius = combatGuardLowerFieldRadius;
    }

    public AbstractDoToEntityTask(double maintainDistance) {
        this(maintainDistance, 0, Double.POSITIVE_INFINITY);
    }

    public AbstractDoToEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        this(-1, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }
    public AbstractDoToEntityTask() {
        this(-1);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _wanderTask.resetWander();
        _progress.reset();
        StorageHelper.closeScreen(); // Kinda duct tape but it should be future proof ish
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-18 wander 中重评目标) 原先本分支先于目标重评——随机走完
        // 10 格之前即使出现新的可用猪灵也不追 (171546 会话用户实测 "wander
        // 中遇到别的猪灵还是 wander")。改为: 先重评目标, 有目标立即中断
        // wander 进入正常流程, 没有才继续随机走。
        Optional<Entity> checkEntity = getEntityTarget(mod);

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            if (checkEntity.isEmpty()) {
                _progress.reset();
                setDebugState("Failed to get to target, wandering for a bit.");
                return _wanderTask;
            }
            setDebugState("New target spotted during wander, aborting it.");
        }


        // Oof
        if (checkEntity.isEmpty()) {
            mod.getMobDefenseChain().resetTargetEntity();
            mod.getMobDefenseChain().resetForceField();
            // (2026-09-18 追最后已知位置) 目标从感知消失但处于最后已知宽限期
            // → 走向冻结的最后位置 (XZ 导航, 允许下挖接近); 到达 2 格内还没
            // 重新感知到才真放弃进 wander。
            if (_lastChaseTarget != null) {
                Vec3d lastKnown = mod.getEntityTracker().getLastKnownPos(_lastChaseTarget);
                if (lastKnown != null) {
                    double dxz = Math.hypot(mod.getPlayer().getX() - lastKnown.x,
                            mod.getPlayer().getZ() - lastKnown.z);
                    if (dxz > 2.0) {
                        long nowTk = WorldHelper.getTicks();
                        if (nowTk - _lastNoTargetLogTick > 100) {
                            _lastNoTargetLogTick = nowTk;
                            Debug.logToFile("altoclef_diag.log", "[LAST-KNOWN-CHASE]\tchasing last known pos of "
                                    + _lastChaseTarget.getType().toString()
                                    + " at (" + String.format("%.1f,%.1f,%.1f", lastKnown.x, lastKnown.y, lastKnown.z)
                                    + ") dxz=" + String.format("%.1f", dxz));
                        }
                        setDebugState("Chasing last known position of target");
                        return new GetToXZTask((int) Math.floor(lastKnown.x), (int) Math.floor(lastKnown.z));
                    }
                }
            }
            _lastChaseTarget = null;
            // (2026-09-14 诊断) 目标从感知/候选里消失 (被拉黑/超感知窗口/死亡)。
            // 232015 场次: hoglin 规则拉黑唯一猪灵 → 无目标 wander 循环, 被
            // 随机方向累计带离 30+ 格。节流 5s。
            // (2026-09-18 修) 初值 Long.MIN_VALUE 时 nowTicks - MIN_VALUE 溢出
            // 为负 → 首条日志被抑制 (165136 会话实锤: 无任何 APPROACH-FAIL 落盘)。
            long nowTicks = WorldHelper.getTicks();
            if (_lastNoTargetLogTick == Long.MIN_VALUE || nowTicks - _lastNoTargetLogTick > 100) {
                _lastNoTargetLogTick = nowTicks;
                Debug.logToFile("altoclef_diag.log", "[APPROACH-FAIL]\tno target (lost/blacklisted), player="
                        + mod.getPlayer().getBlockPos().toShortString()
                        + " dim=" + mod.getWorld().getRegistryKey().getValue());
            }
            return _wanderTask;
        } else {
            mod.getMobDefenseChain().setTargetEntity(checkEntity.get());
        }
        Entity entity = checkEntity.get();
        _lastChaseTarget = entity;

        double playerReach = mod.getModSettings().getEntityReachRange();

        // TODO: This is basically useless.
        EntityHitResult result = LookHelper.raycast(mod.getPlayer(), entity, playerReach);

        double sqDist = entity.squaredDistanceTo(mod.getPlayer());

        if (sqDist < _combatGuardLowerRange * _combatGuardLowerRange) {
            mod.getMobDefenseChain().setForceFieldRange(_combatGuardLowerFieldRadius);
        } else {
            mod.getMobDefenseChain().resetForceField();
        }

        // If we don't specify a maintain distance, default to within 1 block of our reach.
        double maintainDistance = _maintainDistance >= 0? _maintainDistance : playerReach - 1;        // (2026-08-23 退后风筝移除) 用户: "打烈焰人时有退后机制, 把退后风筝机制
        // 直接去掉, 没啥用"。旧逻辑: 敌人进入 maintainDistance (≈ reach-1 ≈ 4.5 格)
        // 就 GoalRunAway 后退 → 打烈焰人打一下退一下, 反复横跳 (日志 15:41 打
        // 烈焰人反复 Escaping/Dodge, 靠近就打不到)。直接去掉退后, bot 贴脸站撸
        // 任务目标, 不再风筝。
        // (2026-08-30 迟滞, 用户) 原条件 `sqDist < reach²` 在边界会每 tick 横跳。
        // 进入射程就锁"交战", 直到明显远离 (> reach+1.5) 才退回靠近。
        // (2026-09-13 用户"交易距离没生效") enter/exit 阈值原先硬编码 = 射程
        // 3.2 / +1.5 —— approach 分支还没走到 maintainDistance, 交互分支就在
        // 3.2 格抢先触发, maintainDistance 形同虚设 (交易 3→2 格肉眼无变化)。
        // 抽成可覆写钩子, 默认值 = 原行为 (战斗等全部不变), 要真正贴身的任务
        // (猪灵交易) 自行收紧。
        double interactEnter = getInteractEnterDistance(mod);
        double interactExit = getInteractExitDistance(mod);
        double enterSq = interactEnter * interactEnter;
        double exitSq = interactExit * interactExit;
        if (sqDist < enterSq || (_withinInteract && sqDist < exitSq)) {
            // (2026-09-26 交战接管清残留路径, 用户定案"嫌疑1") 从接近切进交战的边沿,
            // 接近阶段留下的 baritone 旧路径还挂在 PathingBehavior 上 —— 任务切换的
            // CustomGoalProcess.onLostControl 不清它 (20260926-115959 实锤: 蜘蛛从上方
            // 掉进坑底, 接近期算的爬升路径 dest=(191,-39,265) 在贴脸交战期间冻结
            // pathing=true 整整 36s)。目标已贴脸 in-goal 时 baritone 既不重算也不取消
            // 旧路径, 其 movement look 每 tick 覆盖战斗 lookAt —— 准心被钉在坑沿
            // deepslate 上, [MELEE-Crosshair] swing DECLINED 每秒一条, 一刀未出站桩
            // 挨打。交战接管时 cancelEverything 清场: 交战分支的贴身 follow 下一 tick
            // 按实时目标重新表态 (in-goal = 完全静默), 战斗朝向独占相机。
            // 只在边沿触发 (_withinInteract false→true), 交战期内不反复清 —— 蜘蛛
            // 出了 4.0 圈再回来的合法重寻路不受影响。非战斗交互 (猪灵交易/剪羊毛)
            // 共用本入口, 清场同样无害 (follow 会按需重算)。
            if (!_withinInteract) {
                try {
                    if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                        mod.getClientBaritone().getPathingBehavior().cancelEverything();
                        Debug.logToFile("altoclef_diag.log",
                                "[COMBAT-FIX]\t[CombatTakeover]\tcanceled stale baritone path on entering interact branch");
                    }
                } catch (Throwable ignored) {
                }
            }
            _withinInteract = true;
            // (2026-08-23 被方块挡修复) 已够近 (射程内) 但射线到实体不是 ENTITY
            // (被墙/地形挡住, result 是 BLOCK) → bot 原地打不到, 却一直
            // "Approaching target" → GetToEntityTask 已达成 → 循环卡死 (日志:
            // Killing blaze "Shield down, preparing to attack" 反复几十秒)。
            // 绕到能看到目标的位置 (实体上方或水平邻位) 获得视线再打。
            // (2026-09-11 树叶遮挡补检) 上面的 ENTITY 射线走 ProjectileUtil,
            // 只测实体盒、不测方块 —— 目标躲在树叶/墙后时照样 "命中", 被挡分支
            // 永不触发, bot 站在射程内反复干瞄准直到 kill timeout (2026-09-11
            // 19:45 杀树下的猪现场)。补方块遮挡射线 (COLLIDER), 命中 = 被挡,
            // 走同一套绕位+遮挡钩子恢复。
            // (2026-09-12 多点视线/瞄头, 用户"小白的头明明露出来了却打不到")
            // 旧补检射线终点只取包围盒【中心】(小白≈腰高) —— 小白贴在方块后
            // 只露头时, 中心被挡 → 判"被挡" → 绕位 ping-pong, 一次刀都不出
            // (2026-09-12 11:40 现场绕位 10+ 次后 58s 被射死)。改为自上而下
            // 探测候选瞄准点 (头 0.85 身高 → 中心), 第一个视线通畅的点即为
            // "可见" —— 本 tick 判定不被挡, 且把该点缓存给子类瞄准 (看到头
            // 就瞄头, 与人类一致)。全部被挡才算真被挡。
            _visibleAimPoint = null;
            BlockPos blockObstruction = null;
            if (result != null && result.getType() == HitResult.Type.ENTITY) {
                _visibleAimPoint = computeVisibleAimPoint(mod, entity);
                if (_visibleAimPoint == null) {
                    blockObstruction = findObstructingBlock(mod, entity);
                }
            }
            if (result == null || result.getType() != HitResult.Type.ENTITY || blockObstruction != null) {
                // (2026-09-22 贴脸让位, 用户"小白就在脸上, 已经不用再靠近了, 得让出来")
                // 目标已在交战射程内 (贴脸) 时, "被挡→绕位" 判据近距下大量误报
                // (相机射线贴着方块沿/怪身擦过), 绕位与交战每 tick 互抢控制 —
                // 现场 20260922-132249: Approach entity <-> Getting to block 39,-42,6
                // ForceProtect 打架 (13:30:47~49), 判定逐 tick 翻面, bot 站桩挨射
                // 11s 掉到 0.45 血。近战子类声明贴脸时绕位让位, 交战分支接管。
                boolean pointBlank = isPointBlankCombat(mod, entity);
                // (2026-09-27 用户拍板 C, 现场 20260927-222854) 已在交战迟滞圈内
                // (上一拍判定进入过交战, 只是这一拍射线被吃) 且子类认这是战斗 →
                // 同样交给交战分支, 不用绕位导航把 bot 带走。迟滞圈的语义就是
                // "已在交战"。与 A 互补: A 覆盖"停靠距离以内", C 覆盖 A 与 exit 圈
                // 之间的过渡带 + 末影人瞬移造成的一拍距离跳变。默认 false, 非战斗
                // 交互 (交易/剪羊毛) 被墙挡住时仍需走出去看目标, 保持原绕位行为。
                boolean holdClose = !pointBlank && _withinInteract
                        && holdPositionWhenBlockedInInteract(mod, entity);
                // (2026-11-12 统一可达判据收口, 用户拍板"必中判据对, 走位/重建判据都对",
                // 现场 20261112-150714: 高台打崖下小白 dist=4.7 踩在迟滞圈上沿,
                // HoldClose "no reposition" 钉死 28s 站桩挨射 —— hold 的"不再走位"
                // 必须以必中判据的距离分量 (sqDist <= reach²) 成立为前提:
                //   · 距离分量成立 (真贴脸, 只差视线) → hold 无限合法 (0922 语义不变);
                //   · 距离分量不成立 (打不着, 如高低差假贴脸) → hold 只许维持
                //     OBSTRUCTION_PERSIST_SECONDS (2s) 耐心窗口 (覆盖 0927 C 的
                //     "末影人瞬移一拍跳变"语义), 超时解除走绕位, 解除粘滞到
                //     "距离回到 reach 内或退出交互圈"才复位 (防逐 tick 翻面乒乓)。
                if (holdClose) {
                    java.util.UUID holdId = entity.getUuid();
                    long holdNow = WorldHelper.getTicks();
                    if (sqDist <= playerReach * playerReach) {
                        losTimerSet(holdId, 2, Long.MIN_VALUE);
                        losTimerSet(holdId, 3, Long.MIN_VALUE);
                    } else if (losTimerGet(holdId, 3) != Long.MIN_VALUE) {
                        // 已因超时解除且粘滞中: 保持解除 (否则下一拍又 hold 回来)。
                        holdClose = false;
                    } else {
                        long heldSince = losTimerGet(holdId, 2);
                        if (heldSince == Long.MIN_VALUE
                                || holdNow - heldSince > RecoveryTuning.LOS_TIMER_STALE_TICKS) {
                            losTimerSet(holdId, 2, holdNow);
                        } else if (holdNow - heldSince
                                > (long) (OBSTRUCTION_PERSIST_SECONDS * 20)) {
                            holdClose = false;
                            losTimerSet(holdId, 3, holdNow);
                            losTimerSet(holdId, 2, Long.MIN_VALUE);
                            Debug.logToFile("altoclef_diag.log", "[COMBAT-FIX]\t[HoldLift]\tmandatory-strike"
                                    + " distance unmet for " + (int) OBSTRUCTION_PERSIST_SECONDS
                                    + "s (dist=" + String.format("%.1f", Math.sqrt(sqDist))
                                    + " > reach " + playerReach + "), lifting hold -> reposition");
                        }
                    }
                }
                if (holdClose) {
                    long holdNow = WorldHelper.getTicks();
                    if (_lastHoldCloseLogTick == Long.MIN_VALUE
                            || holdNow - _lastHoldCloseLogTick > 20) {
                        _lastHoldCloseLogTick = holdNow;
                        Debug.logToFile("altoclef_diag.log", "[COMBAT-FIX]\t[HoldClose]\tblocked inside"
                                + " interact ring (dist=" + String.format("%.1f", Math.sqrt(sqDist))
                                + ") -> fighting, no reposition");
                    }
                }
                if (pointBlank || holdClose) {
                    _visibleAimPoint = computeVisibleAimPoint(mod, entity);
                    // (2026-09-25 贴脸墙挡清障, 用户"瞄准怪物后要清干净挡着的障碍,
                    // 障碍可能有两格") 瞄准点全部被挡 = 真有墙隔着 (不是相机擦沿
                    // 误报) —— 旧实现无条件把 _losBlockedSinceTick 清零, 遮挡持久
                    // 判据永远凑不满, bot 站桩干瞄准到 kill timeout (215310 会话
                    // 22:01:35~22:02:24 对墙后蜘蛛干瞄准 49s 实锤)。现: 被挡持续
                    // 计时器继续累积, 满 2s (OBSTRUCTION_PERSIST_SECONDS) 交
                    // onObstructionPersistent 挖除 —— 挖掉一格后射线落到下一格,
                    // 计时器早已超限, 下一格【立即】继续挖, 不再等第二个 2s
                    // ("两格障碍"整条清通)。持续窗口内仍照常交战 (0922 贴脸让位
                    // 行为不变, 交战分支优先, 不与绕位互抢)。
                    java.util.UUID losId = entity.getUuid();
                    if (_visibleAimPoint == null) {
                        long blankNow = WorldHelper.getTicks();
                        long blockedSince = losTimerGet(losId, 0);
                        // (L2) 陈旧残留 (上一场交战遗留) → 重置起点, 不立刻触发挖掘。
                        if (blockedSince == Long.MIN_VALUE
                                || blankNow - blockedSince > RecoveryTuning.LOS_TIMER_STALE_TICKS) {
                            losTimerSet(losId, 0, blankNow);
                        } else if (blankNow - blockedSince
                                > (long) (OBSTRUCTION_PERSIST_SECONDS * 20)) {
                            BlockPos blankObstruction = findObstructingBlock(mod, entity);
                            if (blankObstruction != null) {
                                Task clearTask = onObstructionPersistent(mod, entity, blankObstruction);
                                if (clearTask != null) {
                                    _progress.reset();
                                    return clearTask;
                                }
                            }
                        }
                    } else {
                        // 真能看到目标 (探头/露头) → 恢复 0922 原语义: 清零计时, 直接交战。
                        losTimerSet(losId, 0, Long.MIN_VALUE);
                    }
                    _progress.reset();
                    setDebugState("Target in face, fighting instead of repositioning");
                    return onEntityInteract(mod, entity);
                }
                setDebugState("Target blocked, repositioning for line of sight");
                _progress.reset();
                java.util.UUID repLosId = entity.getUuid();
                losTimerSet(repLosId, 1, Long.MIN_VALUE);
                // (2026-09-09 遮挡消除钩子) 绕位持续被挡 (2026-09-20 起 2s) → 交子类决定是否挖除。
                long now = WorldHelper.getTicks();
                long repBlockedSince = losTimerGet(repLosId, 0);
                if (repBlockedSince == Long.MIN_VALUE
                        || now - repBlockedSince > RecoveryTuning.LOS_TIMER_STALE_TICKS) {
                    losTimerSet(repLosId, 0, now);
                } else if (now - repBlockedSince > (long) (OBSTRUCTION_PERSIST_SECONDS * 20)) {
                    BlockPos obstruction = blockObstruction != null ? blockObstruction : findObstructingBlock(mod, entity);
                    if (obstruction != null) {
                        if (now - _lastObstructionLogTick > 100) {
                            _lastObstructionLogTick = now;
                            Debug.logToFile("altoclef_diag.log", "[LOS-Obstruction]\tblocked >"
                                    + (int) OBSTRUCTION_PERSIST_SECONDS + "s, obstruction="
                                    + obstruction.toShortString() + ", asking task to clear");
                        }
                        Task clearTask = onObstructionPersistent(mod, entity, obstruction);
                        if (clearTask != null) return clearTask;
                    }
                }
                // (2026-09-28 (b) 定案) 击杀子类走不到这里: holdPositionWhenBlockedInInteract=true
                // 且进入交互分支时 _withinInteract 已置 true ⇒ 上面 `pointBlank || holdClose`
                // 恒真, 必走让位分支。击杀链路的"被挡"出路 = 清障挖除 / 站桩到 kill timeout 拉黑。
                // 本分支 (绕位) 仅服务非战斗交互 (剪羊毛 / 挤奶 / 猪灵交易)。
                return repositionForLineOfSight(mod, entity);
            }
            _progress.reset();
            // (2026-09-20 计时器加固) 可见需连续保持 LOS_CLEAR_GRACE_SECONDS 才重置
            // "持续被挡"计时; 单拍可见 (瞬移/探头掠过) 不清零。
            long clearNow = WorldHelper.getTicks();
            java.util.UUID clearLosId = entity.getUuid();
            long clearSince = losTimerGet(clearLosId, 1);
            if (clearSince == Long.MIN_VALUE
                    || clearNow - clearSince > RecoveryTuning.LOS_TIMER_STALE_TICKS) {
                losTimerSet(clearLosId, 1, clearNow);
            } else if (clearNow - clearSince > (long) (LOS_CLEAR_GRACE_SECONDS * 20)) {
                losTimerSet(clearLosId, 0, Long.MIN_VALUE);
            }
            return onEntityInteract(mod, entity);
        } else {
            _withinInteract = false;
            // (2026-11-12 统一可达判据收口) 退出交互圈 = 重新接近 = hold 耐心/
            // 粘滞计时整体复位 (粘滞标记不清理会让下一场交战的 hold 被残留压制)。
            losTimerSet(entity.getUuid(), 2, Long.MIN_VALUE);
            losTimerSet(entity.getUuid(), 3, Long.MIN_VALUE);
            setDebugState("Approaching target");

            // [EndermanProvoke] 接近途中每 tick 回调 (2026-08-23): 子类可
            // 在走近目标时做"提前行为" — 例如打末影人时边接近边把准心对准
            // 它, 激怒它主动瞬移过来接战 (末影人机制: 不看它它不攻击)。
            onEntityApproach(mod, entity);

            if (!_progress.check(mod)) {
                // (2026-09-14 诊断) 接近无进展触发 wander。232015 场次: 猪灵在
                // enter(2.0) 边界 2.1-2.5 晃, 5s 进不去 → wander 10 格随机方向,
                // 反复累积带离目标 30+ 格。节流 5s。若日志显示 dist 恒 ~2.1,
                // 即"enter 边界徘徊"而非寻路失败, 修法另议 (放宽 enter 容差)。
                // (2026-09-18 修) 同 _lastNoTargetLogTick 的溢出抑制问题。
                long nowTicks = WorldHelper.getTicks();
                if (_lastProgressFailLogTick == Long.MIN_VALUE || nowTicks - _lastProgressFailLogTick > 100) {
                    _lastProgressFailLogTick = nowTicks;
                    Debug.logToFile("altoclef_diag.log", "[APPROACH-FAIL]\tprogress stall, target="
                            + entity.getType().toString()
                            + " dist=" + String.format("%.1f", Math.sqrt(sqDist))
                            + " player=" + mod.getPlayer().getBlockPos().toShortString()
                            + " dim=" + mod.getWorld().getRegistryKey().getValue());
                }
                Debug.logMessage("Failed to get to target, wandering.");
                return _wanderTask;
            }

            // Move to target

            return new GetToEntityTask(entity, maintainDistance);
        }
    }

    // Called every tick while we're still approaching the target (outside interact range).
    protected void onEntityApproach(AltoClef mod, Entity entity) {
    }

    /**
     * (2026-09-13) 进入交互分支的距离阈值 (迟滞下沿)。默认 = 实体射程
     * (entityReachRange, 3.2), 与旧行为一致; 需要真正贴身交互的任务覆写收紧。
     */
    protected double getInteractEnterDistance(AltoClef mod) {
        return mod.getModSettings().getEntityReachRange();
    }

    /**
     * (2026-09-13) 退出交互分支的距离阈值 (迟滞上沿)。默认 = 射程 +1.5, 与
     * 旧行为一致; 覆写时须 > getInteractEnterDistance, 否则边界横跳。
     */
    protected double getInteractExitDistance(AltoClef mod) {
        return mod.getModSettings().getEntityReachRange() + 1.5;
    }

    /**
     * (2026-09-09 遮挡消除钩子) 视线被方块挡住且绕位持续无效 (≥8s) 时调用。
     * 返回一个任务接管消除遮挡 (如 DestroyBlockTask 挖掉遮挡方块); 返回 null
     * = 默认行为, 继续绕位。挡住的具体方块由 findObstructingBlock 给出。
     */
    protected Task onObstructionPersistent(AltoClef mod, Entity entity, BlockPos obstruction) {
        return null;
    }

    /**
     * (2026-09-22 贴脸让位) 目标已在交战射程内 (贴脸) 时是否放弃绕位、直接
     * 走交战分支。默认 false —— 交易/剪羊毛等非战斗交互维持原绕位行为;
     * 近战击杀子类覆写为 true: 贴脸时"被挡"多为相机射线擦沿误报, 绕位只会
     * 与交战互抢控制 (0922 站桩挨射事故)。
     */
    protected boolean isPointBlankCombat(AltoClef mod, Entity entity) {
        return false;
    }

    /**
     * (2026-09-27 用户拍板 C) 已在交战迟滞圈内 (上一拍判定进入过交战, 这一拍
     * 射线被吃) 时, 是否禁止"绕位"重获视线。默认 false —— 猪灵交易/剪羊毛等
     * 非战斗交互保持绕位 (被墙挡住时必须走出去才能看到目标, 站桩只会让交互
     * 超时)。近战击杀子类覆写为 true: 迟滞圈内绕位 = 把 bot 从交战距离带出去,
     * 现场 20260927-222854 下界末影人 3 秒内 6 次换绕位点 / 位移 11 格。
     */
    protected boolean holdPositionWhenBlockedInInteract(AltoClef mod, Entity entity) {
        return false;
    }

    /**
     * (2026-09-09) 相机到目标包围盒中心的射线上, 第一个挡住视线的方块;
     * 视线通畅返回 null。
     */
    private BlockPos findObstructingBlock(AltoClef mod, Entity entity) {
        Vec3d from = mod.getPlayer().getCameraPosVec(1.0F);
        Vec3d to = entity.getBoundingBox().getCenter();
        BlockHitResult hit = mod.getWorld().raycast(new RaycastContext(from, to,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
        if (hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos();
        return null;
    }

    // (2026-09-12 多点视线) 本 tick 判定出的可见瞄准点 (自上而下第一个视线
    // 通畅的候选); 目标射线命中实体但全点被挡时为 null。仅在交战判定 tick
    // 内有效, 交战分支的子类 (瞄准) 与盾战分支 (朝向) 直接取用。
    protected Vec3d _visibleAimPoint = null;

    /** (2026-09-12) 取本 tick 的可见瞄准点; 不可用 (被挡/未判定) 返回 null。 */
    protected Vec3d getVisibleAimPoint() {
        return _visibleAimPoint;
    }

    /**
     * (2026-09-12 多点视线/瞄头) 自上而下探测目标身上的候选瞄准点: 头部
     * (0.85 身高, 小白/僵尸探头过方块沿时最先露出的就是这一段) → 包围盒
     * 中心。返回第一个从玩家相机无方块遮挡的点; 全被挡返回 null。
     * 这是"用自己的眼睛看" (相机射线), 不违反反透视。
     * (2026-09-21 末影人例外, 用户"盯脚打防吸旁人") 末影人候选压到脚/小腿
     * (0.10/0.25 身高) — 原版盯头激怒只查头部包围盒与视线相交, 瞄头 =
     * 每次走位都可能在激怒新末影人。
     */
    private Vec3d computeVisibleAimPoint(AltoClef mod, Entity entity) {
        Vec3d from = mod.getPlayer().getCameraPosVec(1.0F);
        net.minecraft.util.math.Box box = entity.getBoundingBox();
        double cx = (box.minX + box.maxX) / 2.0;
        double cz = (box.minZ + box.maxZ) / 2.0;
        double height = box.maxY - box.minY;
        double[] fractions = entity instanceof net.minecraft.entity.mob.EndermanEntity
                ? new double[]{0.10, 0.25}
                : new double[]{0.85, 0.5};
        for (double frac : fractions) {
            Vec3d p = new Vec3d(cx, box.minY + height * frac, cz);
            BlockHitResult hit = mod.getWorld().raycast(new RaycastContext(from, p,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
            if (hit.getType() != HitResult.Type.BLOCK) return p;
        }
        return null;
    }

    /**
     * (2026-08-23) 目标在射程内但被方块挡住 (射线 BLOCK), 返回一个绕行任务,
     * 走到能看到目标的位置。候选: 实体上方 2 格 (俯视) + 4 个水平邻位,
     * 优先水平 (避免站到岩浆/深渊上方), 选离玩家最近的可达候选。目标可动,
     * 每次被挡时重新选。
     */
    private Task repositionForLineOfSight(AltoClef mod, Entity entity) {
        BlockPos ep = entity.getBlockPos();
        // (2026-08-31 绕位贴稳) 已有进行中的绕位目标且未到附近/未超时 → 继续走它,
        // 不重新选 (消除相邻候选块间每 tick 横跳, 近战持续贴近)。
        if (_repositionTarget != null) {
            if (!_repositionSwitchTimer.elapsed()
                    && !mod.getPlayer().getBlockPos().isWithinDistance(_repositionTarget, 1.5)) {
                setDebugState("Staying committed to reposition target (LOS)");
                return new GetToBlockTask(_repositionTarget, false);
            }
            // 到达附近或超时 → 放掉, 允许本轮重新评估。
            _repositionTarget = null;
        }
        // (2026-08-30 后退绕位, 用户) 视线被墙挡时常需【后退】拉开看墙角; 原列表只有
        // 实体紧邻点, 需要后退时没有可选点 → bot 原地干等。补 4 个水平方向往外 2 格
        // 的拉远点 + 实体上方 2 格; 并只选"能站进去"的点 (目标格及头顶都是空气,
        // 2 格高), 避免走进墙/水/岩浆。优先就近可达的点, 让 bot 能退开清视野。
        BlockPos[] cardinals = { ep.north(), ep.south(), ep.east(), ep.west() };
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        for (BlockPos p : cardinals) candidates.add(p);
        candidates.add(ep.up(2));
        for (BlockPos p : cardinals) {
            int dx = p.getX() - ep.getX(), dz = p.getZ() - ep.getZ();
            candidates.add(ep.add(dx * 2, 0, dz * 2));
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos c : candidates) {
            double d = c.getSquaredDistance(mod.getPlayer().getBlockPos());
            if (d >= bestDist) continue;
            if (!mod.getWorld().getBlockState(c).isAir() || !mod.getWorld().getBlockState(c.up()).isAir()) continue;
            bestDist = d;
            best = c;
        }
        if (best == null) best = ep.up(2);
        // 一旦选定就粘住一小段时间, 避免相邻候选翻来翻去。
        _repositionTarget = best;
        _repositionSwitchTimer.reset();
        Debug.logToFile("altoclef_diag.log",
                "[COMBAT-FIX]\t[LOS-Reposition]\tcommitting to " + best.toShortString() + " for up to 3s (no flip-flop)");
        return new GetToBlockTask(best, false);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof AbstractDoToEntityTask task) {
            if (!doubleCheck(task._maintainDistance, _maintainDistance)) return false;
            if (!doubleCheck(task._combatGuardLowerFieldRadius, _combatGuardLowerFieldRadius)) return false;
            if (!doubleCheck(task._combatGuardLowerRange, _combatGuardLowerRange)) return false;
            return isSubEqual(task);
        }
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean doubleCheck(double a, double b) {
        if (Double.isInfinite(a) == Double.isInfinite(b)) return true;
        return Math.abs(a - b) < 0.1;
    }

    protected abstract boolean isSubEqual(AbstractDoToEntityTask other);

    protected abstract Task onEntityInteract(AltoClef mod, Entity entity);

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getMobDefenseChain().setTargetEntity(null);
        mod.getMobDefenseChain().resetForceField();
        _repositionTarget = null; // (2026-08-31) 长任务停止时清掉绕位记忆, 避免遗留下次误用
        // (2026-09-28 L2) 遮挡/可见计时已改为 static map (按目标 UUID), **不在这里清**:
        // 按实例清会误伤其它目标的计时, 且上层每秒重建实例会把它清成"永远从头计时"
        // —— 那正是 20260927-222854 全场 [LOS-Obstruction] 0 条的成因。
        // 过期由 RecoveryTuning.LOS_TIMER_STALE_TICKS + 容量上限自净。
        _visibleAimPoint = null; // (2026-09-12) 可见瞄准点一并清掉
        // (2026-09-18) _lastChaseTarget 故意不清: 任务实例长期复用 (如 _tradeTask),
        // 进食抢占等打断恢复后还能续上最后已知追击; 残留无风险 —— 追击仅在
        // tracker 判定该实体仍处 30s 宽限期时触发, 且只在无活跃目标时走到。
    }

    protected abstract Optional<Entity> getEntityTarget(AltoClef mod);

}
