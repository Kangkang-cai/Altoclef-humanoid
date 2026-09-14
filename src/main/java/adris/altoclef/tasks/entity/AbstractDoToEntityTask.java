package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
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
    // = 维持绕位, 战斗等其他任务行为完全不变 (scope 收敛)。
    // =========================================================================
    private static final double OBSTRUCTION_PERSIST_SECONDS = 8;
    private long _losBlockedSinceTick = Long.MIN_VALUE;
    private long _lastObstructionLogTick = Long.MIN_VALUE;

    // (2026-09-14 接近失败诊断, 232015 场次) wander 两个触发点的取证日志节流
    // key (5s)。目的: 区分"目标从感知/候选消失"与"接近无进展", 并拿到触发时
    // 的目标距离 —— 232015 场次猪灵 2.1 格处反复触发, 疑似 enter 边界徘徊
    // (piglin 2.1-2.5 晃, 进不了 enter 2.0) 而非 baritone 无路径, 待日志证实。
    private long _lastNoTargetLogTick = Long.MIN_VALUE;
    private long _lastProgressFailLogTick = Long.MIN_VALUE;

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

        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _progress.reset();
            setDebugState("Failed to get to target, wandering for a bit.");
            return _wanderTask;
        }

        Optional<Entity> checkEntity = getEntityTarget(mod);


        // Oof
        if (checkEntity.isEmpty()) {
            mod.getMobDefenseChain().resetTargetEntity();
            mod.getMobDefenseChain().resetForceField();
            // (2026-09-14 诊断) 目标从感知/候选里消失 (被拉黑/超感知窗口/死亡)。
            // 232015 场次: hoglin 规则拉黑唯一猪灵 → 无目标 wander 循环, 被
            // 随机方向累计带离 30+ 格。节流 5s。
            long nowTicks = WorldHelper.getTicks();
            if (nowTicks - _lastNoTargetLogTick > 100) {
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
                setDebugState("Target blocked, repositioning for line of sight");
                _progress.reset();
                // (2026-09-09 遮挡消除钩子) 绕位 8s 仍被挡 → 交子类决定是否挖除。
                long now = WorldHelper.getTicks();
                if (_losBlockedSinceTick == Long.MIN_VALUE) {
                    _losBlockedSinceTick = now;
                } else if (now - _losBlockedSinceTick > (long) (OBSTRUCTION_PERSIST_SECONDS * 20)) {
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
                return repositionForLineOfSight(mod, entity);
            }
            _progress.reset();
            _losBlockedSinceTick = Long.MIN_VALUE;
            return onEntityInteract(mod, entity);
        } else {
            _withinInteract = false;
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
                long nowTicks = WorldHelper.getTicks();
                if (nowTicks - _lastProgressFailLogTick > 100) {
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
     */
    private Vec3d computeVisibleAimPoint(AltoClef mod, Entity entity) {
        Vec3d from = mod.getPlayer().getCameraPosVec(1.0F);
        net.minecraft.util.math.Box box = entity.getBoundingBox();
        double cx = (box.minX + box.maxX) / 2.0;
        double cz = (box.minZ + box.maxZ) / 2.0;
        double height = box.maxY - box.minY;
        double[] fractions = {0.85, 0.5};
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
        _losBlockedSinceTick = Long.MIN_VALUE; // (2026-09-09) 遮挡计时一并清掉
        _visibleAimPoint = null; // (2026-09-12) 可见瞄准点一并清掉
    }

    protected abstract Optional<Entity> getEntityTarget(AltoClef mod);

}
