package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;

public class DragonBreathTracker {
    private final HashSet<BlockPos> _breathBlocks = new HashSet<>();

    // ===== (2026-09-21 1955 会话) 逃跑期间云格寻路避让的放行开关 =====
    //
    // 云格在 baritone 寻路里是【硬墙】(avoidWalkingThrough 谓词 → baritone
    // MovementHelper.canWalkThrough/fullyPassable 消费 shouldAvoidWalkThroughForce,
    // 命中即返回 false —— 不是高惩罚, 是不可通行)。
    //
    // 195335 会话实锤 (用户现场: 洞里没掉血): 旧版逃跑任务启动即
    // _escapingDragonsBreath=true 整体关闭这面墙 + setBlockPlacePenalty(0)
    // 鼓励垫方块 —— bot 在洞底时"竖直垫柱穿云"成了代价最小的逃跑路径
    // (挖墙要吃 breakAdditionalPenalty 10), 直接穿云 -11 血。
    //
    // 现在: 逃跑期间硬墙保持, baritone 自动选不穿云的路线 (洞底=横向挖出去,
    // 即用户指定的"贴地横向挖出去")。只有绕云真无路可走 (逃跑中净位移停滞
    // 3s, 见 RunAwayFromDragonsBreathTask 的 stall 保底) 才放行穿云 + 重算。
    // volatile: 谓词在 baritone 算路线程读, 渲染线程写。
    private volatile boolean _escapeAllowThroughBreath = false;

    public void updateBreath(AltoClef mod) {
        _breathBlocks.clear();
        for (AreaEffectCloudEntity cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloudEntity.class)) {
            for (BlockPos bad : WorldHelper.getBlocksTouchingBox(mod, cloud.getBoundingBox())) {
                _breathBlocks.add(bad);
            }
        }
    }

    public boolean isTouchingDragonBreath(BlockPos pos) {
        return _breathBlocks.contains(pos);
    }

    /**
     * (2026-09-21) 实体级触碰判定, 对齐原版伤害语义。
     *
     * <p>原版依据 (1.18.2 反编译 AreaEffectCloudEntity.tick): 候选 =
     * {@code world.getNonSpectatingEntities(LivingEntity.class, this.getBoundingBox())}
     * (实体包围盒与云包围盒相交), 再过滤 {@code (dx² + dz²) <= radius²}
     * (XZ 圆柱距离, 实体脚底中心到云中心)。两条件同时成立才上伤害。
     *
     * <p>旧判定 {@code getBlocksTouchingPlayer ∩ _breathBlocks} 是格级 AABB
     * (BlockPos(min)..BlockPos(max) 直接 floor 取格): 云悬在洞口、bot 在洞底
     * 安全时, 玩家包围盒与云盒【同占一个方块格但实际不相交】→ 误报"踩进云"
     * → 触发逃跑把 bot 送进云里 (195335 会话 -11 血, 用户确认洞里没掉血)。
     */
    public boolean isTouchingDragonBreathEntity(AltoClef mod) {
        Box playerBox = mod.getPlayer().getBoundingBox();
        Vec3d me = mod.getPlayer().getPos();
        for (AreaEffectCloudEntity cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloudEntity.class)) {
            if (!cloud.getBoundingBox().intersects(playerBox)) continue;
            double dx = me.x - cloud.getX();
            double dz = me.z - cloud.getZ();
            double radius = cloud.getRadius();
            if (dx * dx + dz * dz <= radius * radius) return true;
        }
        return false;
    }

    /**
     * (2026-09-21 1849 用户"dragon's breath 导航路径也不考虑, 差点被魔法打死")
     * 主动规避判据: 紫云中心进入 range 内就算危险 —— 旧链路只在【踩进】
     * 云格才逃 (getBlocksTouchingPlayer), 走位擦着云边照样持续吃伤害。
     * 按"玩家位置到云实体距离"算, 云本体半径 ~5 格, range=10 覆盖云缘。
     */
    public boolean isNearDragonBreath(AltoClef mod, double range) {
        Vec3d me = mod.getPlayer().getPos();
        for (AreaEffectCloudEntity cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloudEntity.class)) {
            if (cloud.squaredDistanceTo(me) < range * range) return true;
        }
        return false;
    }

    /** (2026-09-21) 逃跑期间是否放行穿云格寻路 (默认 false = 绕云; 卡死保底才置 true)。 */
    public void setEscapeAllowThroughBreath(boolean allow) {
        _escapeAllowThroughBreath = allow;
    }

    /** 供 BeatMinecraft2Task 的 avoidWalkingThrough 谓词读取 (baritone 算路线程)。 */
    public boolean isEscapeAllowThroughBreath() {
        return _escapeAllowThroughBreath;
    }

    public Task getRunAwayTask(double distance) {
        return new RunAwayFromDragonsBreathTask(distance);
    }

    private class RunAwayFromDragonsBreathTask extends CustomBaritoneGoalTask {

        private final double _distance;

        // (2026-09-21) 卡死放行保底: 逃跑中净位移 <0.5 格持续这么久 = 绕云
        // 寻路无解 (云格硬墙堵死所有出口, 如 bot 被云整个罩住)。放行穿云 +
        // 撤路径重算 —— 穿云吃伤害总比原地冻死强。正常逃跑 (横挖/绕行) 会
        // 持续刷新参考点, 不会误触发。
        private final TimerGame _stallTimer = new TimerGame(3);
        private Vec3d _stallRefPos = null;

        private RunAwayFromDragonsBreathTask(double distance) {
            _distance = distance;
        }

        @Override
        protected void onStart(AltoClef mod) {
            super.onStart(mod);
            mod.getBehaviour().push();
            // Encourage placing of all blocks!
            // (2026-09-21 注: 垫方块本身无害 —— 穿云格已被寻路硬墙挡住,
            // 垫柱只可能发生在无云方向。旧事故的根因是"硬墙被整体关闭 +
            // 零惩罚垫柱"二者叠加, 不是垫柱本身。)
            mod.getBehaviour().setBlockPlacePenalty(0);
            mod.getBehaviour().setBlockBreakAdditionalPenalty(10); // Normally 2

            // do NOT ever wander
            _checker = new MovementProgressChecker(999999);

            // 逃跑从"绕云"开始, 只有 stall 保底才放行穿云。
            setEscapeAllowThroughBreath(false);
            _stallRefPos = mod.getPlayer().getPos();
            // TimerGame 新建即到期, 显式复位。
            _stallTimer.reset();
        }

        @Override
        protected Task onTick(AltoClef mod) {
            // stall 保底: 净位移持续停滞 → 绕云无解 → 放行穿云重算。
            Vec3d pos = mod.getPlayer().getPos();
            if (_stallRefPos == null || pos.squaredDistanceTo(_stallRefPos) >= 0.25) {
                _stallRefPos = pos;
                _stallTimer.reset();
            } else if (_stallTimer.elapsed() && !isEscapeAllowThroughBreath()) {
                setEscapeAllowThroughBreath(true);
                Debug.logToFile("altoclef_diag.log", "[BreathEscape] stalled 3s while escaping"
                        + " (net位移<0.5), 放行穿云格寻路 + 重算, pos=" + mod.getPlayer().getBlockPos());
                mod.getClientBaritone().getPathingBehavior().forceCancel();
            }
            return super.onTick(mod);
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            super.onStop(mod, interruptTask);
            mod.getBehaviour().pop();
            // 复位放行开关 (下次逃跑重新从"绕云"开始)。
            setEscapeAllowThroughBreath(false);
        }

        @Override
        protected Goal newGoal(AltoClef mod) {
            return new GoalRunAway(_distance, _breathBlocks.toArray(BlockPos[]::new));
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RunAwayFromDragonsBreathTask;
        }

        @Override
        protected String toDebugString() {
            return "ESCAPE Dragons Breath";
        }
    }
}
