package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ConcurrentModificationException;
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

    /**
     * (2026-09-21 211823 会话) 龙火球来袭检测: 返回预测撞击点方块; 无威胁返回 null。
     *
     * <p>背景: 全系统的弹射物防御【明确跳过龙火球】(MobDefenseChain
     * getNearestProjectile / isProjectileClose 双处 "Ignore dragon fireballs"),
     * 云侧链路全部是【落地后】的被动反应 —— 211823 实锤: 龙 strafe 火球,
     * 7 秒 4 发直接命中 (20→14→9.7→4→0, 全 indirectMagic), bot 活靶子。
     * 火球直线飞行无重力无追踪 (spawn 包携带速度, 客户端 getVelocity 可信),
     * 可以提前预测撞击点先手躲。
     *
     * <p>判据 (全部满足才算来袭): 距离 ≤24 格; 速度方向与"火球→bot"夹角
     * <45°(归一 dot>0.7); 预测撞击点 (当前 pos + vel*(dist/|vel|)) 距 bot
     * ≤4.5 格 (直击包线内, 云半径不算 —— 落地云由云侧链路管)。贴脸 (<6 格)
     * 不再细算朝向, 直接按当前飞行方向前推 2 格当撞击点。
     */
    public BlockPos findIncomingFireballImpact(AltoClef mod) {
        Vec3d me = mod.getPlayer().getPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        try {
            for (CachedProjectile p : mod.getEntityTracker().getProjectiles()) {
                if (p == null || p.position == null || p.velocity == null) continue;
                if (p.projectileType != DragonFireballEntity.class) continue;
                double vlen = p.velocity.length();
                if (vlen < 0.05) continue;
                double dist = p.position.distanceTo(me);
                if (dist > 24) continue;
                BlockPos impact;
                if (dist < 6) {
                    // 已贴脸: 不算包线了, 朝它前进方向前推 2 格就是危险区
                    impact = new BlockPos(
                            (int) Math.floor(p.position.x + p.velocity.x / vlen * 2),
                            (int) Math.floor(p.position.y + p.velocity.y / vlen * 2),
                            (int) Math.floor(p.position.z + p.velocity.z / vlen * 2));
                } else {
                    double heading = (p.velocity.x / vlen) * ((me.x - p.position.x) / dist)
                            + (p.velocity.y / vlen) * ((me.y - p.position.y) / dist)
                            + (p.velocity.z / vlen) * ((me.z - p.position.z) / dist);
                    if (heading < 0.7) continue;
                    Vec3d hit = p.position.add(p.velocity.multiply(dist / vlen));
                    if (hit.distanceTo(me) > 4.5) continue;
                    impact = new BlockPos(
                            (int) Math.floor(hit.x), (int) Math.floor(hit.y), (int) Math.floor(hit.z));
                }
                if (dist < bestDist) {
                    bestDist = dist;
                    best = impact;
                }
            }
        } catch (ConcurrentModificationException | NullPointerException ignored) {
            // 实体列表并发修改: 本 tick 视为无威胁, 下 tick 重扫 (同 MobDefenseChain 先例)
        }
        return best;
    }

    /** 有龙火球朝 bot 直飞时返回躲避任务, 否则 null (供顶层守卫每 tick 询问)。 */
    public Task getFireballEvadeTaskIfIncoming(AltoClef mod) {
        return findIncomingFireballImpact(mod) == null ? null : new FireballEvadeTask();
    }

    /**
     * (2026-09-21 211823 会话) 火球躲避: 对预测撞击点 GoalRunAway(9) 侧闪/后撤。
     * 撞击点随火球前移每 tick 可能变化 → 变化时 forceRepath 重快照 (见
     * CustomBaritoneGoalTask.forceRepath); 威胁解除 (无来袭火球) 即完成,
     * 上层守卫自然接管 (云侧/任务链)。
     */
    private class FireballEvadeTask extends CustomBaritoneGoalTask {

        private BlockPos _impactBlock;
        private boolean _loggedStart = false;

        @Override
        protected Goal newGoal(AltoClef mod) {
            BlockPos impact = findIncomingFireballImpact(mod);
            _impactBlock = impact != null ? impact : mod.getPlayer().getBlockPos();
            return new GoalRunAway(9, _impactBlock);
        }

        @Override
        protected Task onTick(AltoClef mod) {
            BlockPos impact = findIncomingFireballImpact(mod);
            if (impact != null) {
                if (!_loggedStart) {
                    _loggedStart = true;
                    Debug.logToFile("altoclef_diag.log", "[FireballEvade] incoming dragon fireball, impact~"
                            + impact.toShortString() + " bot=" + mod.getPlayer().getBlockPos().toShortString());
                }
                if (!impact.equals(_impactBlock)) {
                    _impactBlock = impact;
                    forceRepath(mod);
                }
            }
            return super.onTick(mod);
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            if (findIncomingFireballImpact(mod) == null) return true; // 威胁解除
            return super.isFinished(mod);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof FireballEvadeTask;
        }

        @Override
        protected String toDebugString() {
            return "EVADE Dragon Fireball";
        }
    }

    private class RunAwayFromDragonsBreathTask extends CustomBaritoneGoalTask {

        private final double _distance;

        // (2026-09-21) 卡死放行保底: 逃跑中净位移 <0.5 格持续这么久 = 绕云
        // 寻路无解 (云格硬墙堵死所有出口, 如 bot 被云整个罩住)。放行穿云 +
        // 撤路径重算 —— 穿云吃伤害总比原地冻死强。正常逃跑 (横挖/绕行) 会
        // 持续刷新参考点, 不会误触发。
        private final TimerGame _stallTimer = new TimerGame(3);
        private Vec3d _stallRefPos = null;

        // (2026-09-21 211823) 近距云实体集合 (目标新鲜度检测用)。
        private HashSet<BlockPos> _nearCloudBlocks = null;

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
            // (2026-09-21 211823) 目标新鲜度: 逃跑期间新火球落地 → 新云出现 →
            // 危险集变化。老机制 newGoal 只在任务启动时快照一次, isEqual 保活的
            // 实例永远对【旧云集】寻路 —— 211823 实锤: touch 反复触发但 bot 仍
            // 站在/走向新云 (旧目标里根本没有新云)。近距 (16 格) 云实体集合
            // 一变就 forceRepath, newGoal 重快照当 tick 的 _breathBlocks。
            Vec3d meNow = mod.getPlayer().getPos();
            HashSet<BlockPos> near = new HashSet<>();
            for (AreaEffectCloudEntity cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloudEntity.class)) {
                if (cloud.squaredDistanceTo(meNow) < 16 * 16) near.add(cloud.getBlockPos());
            }
            if (_nearCloudBlocks == null) {
                _nearCloudBlocks = near; // 首 tick: 目标快照本来就是新的, 只记录
            } else if (!near.equals(_nearCloudBlocks)) {
                _nearCloudBlocks = near;
                forceRepath(mod);
                Debug.logToFile("altoclef_diag.log", "[BreathEscape] goal-refresh (nearby cloud set changed, n="
                        + near.size() + ") pos=" + mod.getPlayer().getBlockPos());
            }

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
            // (2026-09-21 210114) 半径 +1 防"边界带站桩死锁": baritone isInGoal 用
            // BlockPos 整数口径 (>=range² 达标站桩), 上层派发判据 (isNearDragonBreath/
            // touch 的 Vec3d 中心口径 <range) 会差最多 ~0.7 格 → bot 站进边界带时
            // 上层每 tick 重派、baritone 每次都认为达标不动 → 站桩被火球白打
            // (210114 实锤: ESCAPE(12) 站桩 5s, -6 血后死)。+1 推出边界带。
            return new GoalRunAway(_distance + 1, _breathBlocks.toArray(BlockPos[]::new));
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
