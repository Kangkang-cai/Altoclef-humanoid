package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import adris.altoclef.util.helpers.CombatMemory;
import adris.altoclef.util.helpers.CreeperBaitSignal;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.List;

/**
 * (2026-09-25 苦力怕诱爆风筝, 用户定案)
 * "把徒手打苦力怕改为, 保持距离然后使其引爆, 在一定距离既不会受到伤害,
 * 也不会使苦力怕停止引爆。"
 *
 * ============ 机制依据 (1.18.2 反编译字节码取证, 2026-09-25) ============
 * CreeperIgniteGoal (net.minecraft.entity.ai.goal.CreeperIgniteGoal):
 *  · canStart: fuseSpeed<=0 || 目标距离² < 9.0 —— 即引信只能在自己距目标
 *    【3 格内】被首次点燃 (或已被点燃时续期);
 *  · tick: 引燃后, 缓存目标距离² ≤ 49.0 (≤7 格) 且 canSee(目标) → fuseSpeed=+1;
 *    距离 >7 格或丢失视线 → fuseSpeed=-1 (熄引信, 逆转不致命)。
 * CreeperEntity.tick: currentFuseTime += fuseSpeed 每 tick, 满 30 (1.5s) 爆炸。
 * 引燃 Goal 持 MOVE 控制权 (start 里 navigation.stop()) → 点燃后苦力怕定身,
 * 距离变化只由 bot 控制。
 *
 * ============ 由此推出的诱爆窗口 ============
 * 爆炸 (power=3) 在开阔地 ~5 格外伤害趋近 0, 而 ≤7 格引信不熄。
 * ⇒ 环带 [RING_MIN=5.0, 6.8] (留 0.2 防判据抖动越过 7.0):
 *    既不受伤 (≥5), 也不熄引信 (≤7 + 保持视线)。
 * ⇒ 但点燃必须先让苦力怕逼近到 3 格内 —— LURE 阶段允许它贴近, 引燃瞬间
 *    立即后撤进环带。1.5s 引信内走开 2~3 格绰绰有余 (步速 4.3 格/s)。
 *
 * ============ 保护目标 (熔炉等) ============
 * keepClearPos 非空 (熔炉守卫派发) 时, 引爆点必须离炉 ≥10 格: 苦力怕是追着
 * bot 走的, 先把 bot 拉到炉外 10 格再站定引诱, 引燃点 (bot 附近) 自然远离
 * 炉子 —— 172433 会话熔炉在炉边被炸的复现路径即"bot 站炉边 → 苦力怕炉边
 * 点燃 → 爆炸波及熔炉"。 kite 方向同样背向保护点。
 *
 * 与既有系统的协调 (CreeperBaitSignal):
 *  · KillAura 不挥诱爆目标 (挥击击退会把苦力怕打出环带);
 *  · MobDefenseChain 三处苦力怕反射 (evaluate/fight 的逃跑、危险批次的
 *    shouldRetreatMelee) 对诱爆目标豁免 —— 有盾时盾牌核弹优先级更高,
 *    照旧接管 (本任务会被打断, 属正常);
 *  · 第二只点燃的苦力怕照常触发逃跑 (单槽信号, 安全兜底)。
 *
 * 失败兜底: 总超时 / 目标跑丢 (>24 格) → CombatMemory 冷却拉黑, 上层重选。
 */
public class BaitCreeperDetonationTask extends Task {

    // ---- 机制常量 (见类注释的字节码依据) ----
    /** 原版熄引信阈值: 距离 >7.0 格 fuseSpeed=-1。环带上沿留 0.2 余量。 */
    private static final double DEFUSE_DISTANCE = 7.0;
    /** 环带下沿: 开阔地 power=3 爆炸 ≥5 格伤害趋近 0。 */
    private static final double RING_MIN = 5.0;
    /** 后撤目标距离 (环带中点偏上, 惯性越界 <0.8 格仍安全)。 */
    private static final double RING_KITE = 6.0;
    /** 引燃后意外越出上沿 (惯性/被击退) → 走近补位到这个距离。 */
    private static final double RING_RECOVER = 5.0;
    /** 引诱阶段与苦力怕的目标间距: 停在 3.5, 苦力怕自己走完最后 0.5 格点燃。 */
    private static final double LURE_MAINTAIN = 3.5;
    /** 保护点 (熔炉) 的最小安全引爆距离: 爆炸破坏半径 power3 ~5 格, 再留 5 格余量。 */
    private static final double KEEP_CLEAR_RADIUS = 10.0;
    /** 目标跑丢 (感知不到且距离超此值) 放弃。 */
    private static final double LOST_DISTANCE = 24.0;
    /** 总超时: 苦力怕迟迟不点燃 (未索敌/地形卡住) → 放弃冷却, 防无限循环。 */
    private static final double OVERALL_TIMEOUT_SECONDS = 25;

    private final CreeperEntity _creeper;
    private final java.util.UUID _creeperId;
    /** 保护点 (熔炉位置), null = 无保护目标场景。 */
    private final BlockPos _keepClearPos;
    private final TimerGame _overallTimer = new TimerGame(OVERALL_TIMEOUT_SECONDS);
    private final TimerGame _diagTimer = new TimerGame(1);

    /** 当前 baritone 目标类别 (变更才重寻路, 防 setGoalAndPath 每 tick 刷)。 */
    private String _goalKind = null;
    private boolean _gaveUp = false;

    public BaitCreeperDetonationTask(CreeperEntity creeper, BlockPos keepClearPos) {
        _creeper = creeper;
        _creeperId = creeper == null ? null : creeper.getUuid();
        _keepClearPos = keepClearPos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _overallTimer.reset();
        _gaveUp = false;
        _goalKind = null;
        CreeperBaitSignal.set(_creeperId);
        double dist = _creeper != null ? Math.sqrt(_creeper.squaredDistanceTo(mod.getPlayer())) : -1;
        Debug.logMessage("[BAIT] start creeper dist=" + String.format("%.1f", dist)
                + " keepClear=" + (_keepClearPos == null ? "none" : _keepClearPos.toShortString()));
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_creeper == null || !_creeper.isAlive()) {
            // 爆炸/被打死 → 完成。信号在 onStop 清。
            return null;
        }

        // ---- 放弃兜底 ----
        double distSq = _creeper.squaredDistanceTo(mod.getPlayer());
        double dist = Math.sqrt(distSq);
        if (!_gaveUp && (_overallTimer.elapsed() || dist > LOST_DISTANCE)) {
            _gaveUp = true;
            int cooldown = mod.getModSettings().getUnreachableCooldownSeconds();
            CombatMemory.markUnreachable(_creeperId, cooldown * 20);
            Debug.logMessage("[BAIT-TIMEOUT] creeper " + (_overallTimer.elapsed() ? "not ignited in " + (int) OVERALL_TIMEOUT_SECONDS + "s" : "lost (> " + (int) LOST_DISTANCE + " blocks)")
                    + ", cooldown " + cooldown + "s");
            stopBaritone(mod);
            return null;
        }

        // 信号每 tick 续 (KillAura/防御链豁免依据) + 认领防御链目标槽
        // (applyForceField 跳过 _targetEntity, 与 AbstractDoToEntityTask 同口径)。
        CreeperBaitSignal.set(_creeperId);
        mod.getMobDefenseChain().setTargetEntity(_creeper);

        float fuse = _creeper.getClientFuseTime(1);

        if (_diagTimer.elapsed()) {
            _diagTimer.reset();
            Debug.logToFile("altoclef_diag.log", String.format(
                    "[BAIT] fuse=%.2f dist=%.1f phase=%s keepClear=%s",
                    fuse, dist, _goalKind == null ? "HOLD" : _goalKind,
                    _keepClearPos == null ? "none" : _keepClearPos.toShortString()));
        }

        if (fuse > 0.001f) {
            // ============ 已点燃: 维持环带, 等它爆 ============
            if (dist > DEFUSE_DISTANCE - 0.2) {
                // 惯性/被击退越出熄引信边缘 → 立刻补位走近 (目标 RING_RECOVER)。
                setGoal(mod, "RECOVER", () -> new adris.altoclef.util.baritone.GoalFollowEntity(_creeper, RING_RECOVER));
            } else if (dist < RING_MIN) {
                // 太近会吃伤害 → 先断当前寻路急停, 再后撤到环带上沿。
                if (!"KITE".equals(_goalKind)) {
                    mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                }
                setGoal(mod, "KITE", () -> new GoalRunAwayFromEntities(mod, RING_KITE, false, 10) {
                    @Override
                    protected List<Entity> getEntities(AltoClef m) {
                        return Collections.singletonList(_creeper);
                    }
                });
            } else {
                // 环带内: 定身, 面向苦力怕 (保持感知/视线, 引信不熄)。
                holdPosition(mod);
                LookHelper.lookAt(mod, _creeper.getPos());
            }
        } else {
            // ============ 未点燃: 诱导它贴近 ============
            if (_keepClearPos != null
                    && mod.getPlayer().getBlockPos().isWithinDistance(_keepClearPos, KEEP_CLEAR_RADIUS - 1)) {
                // 先把 bot 拉出保护点 10 格 (苦力怕追着走, 引燃点自然远离熔炉)。
                setGoal(mod, "PULL_OUT", () -> new GoalRunAway(KEEP_CLEAR_RADIUS + 1, (Integer) null, _keepClearPos));
            } else if (dist > LURE_MAINTAIN) {
                // 走近到 3.5 格内站定; 剩下的 0.5 格苦力怕自己走完并点燃。
                setGoal(mod, "LURE", () -> new adris.altoclef.util.baritone.GoalFollowEntity(_creeper, LURE_MAINTAIN));
            } else {
                // 站定邀请: 不再逼近, 等苦力怕进 3 格点燃 (点燃瞬间走 KITE 分支)。
                holdPosition(mod);
                LookHelper.lookAt(mod, _creeper.getPos());
            }
        }
        return null;
    }

    private void setGoal(AltoClef mod, String kind, GoalSupplier goal) {
        if (!kind.equals(_goalKind)) {
            _goalKind = kind;
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(goal.get());
        } else if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(goal.get());
        }
    }

    private void holdPosition(AltoClef mod) {
        if (_goalKind != null) {
            _goalKind = null;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }
    }

    private void stopBaritone(AltoClef mod) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        _goalKind = null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _gaveUp || _creeper == null || !_creeper.isAlive();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        stopBaritone(mod);
        CreeperBaitSignal.clear(_creeperId);
        mod.getMobDefenseChain().resetTargetEntity();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof BaitCreeperDetonationTask task) {
            if (task._creeperId == null || _creeperId == null) return task._creeperId == _creeperId;
            if (!task._creeperId.equals(_creeperId)) return false;
            return java.util.Objects.equals(task._keepClearPos, _keepClearPos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Bait creeper detonation (ring " + (int) RING_MIN + "-" + (int) DEFUSE_DISTANCE + ")";
    }

    /** 延迟构造 goal (每 tick 可能不同实例, 由 kind 变更门控避免刷寻路)。 */
    @FunctionalInterface
    private interface GoalSupplier {
        Goal get();
    }
}
