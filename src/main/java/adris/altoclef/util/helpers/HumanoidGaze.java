package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.utils.Rotation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-10-23 拟人头部观察) 待机 / 守卫类场景的共用"视线调度器"。
 *
 * <p>要解决的两个呆滞来源:
 * ① {@code IdleTask} 什么都不登记 lookAt, 相机滞留在上一任务的朝向 (常是低头
 * 挖矿姿态) → "老是看向脚";
 * ② 守炉旧逻辑 {@link LookHelper#randomOrientation} 每 2s 在 yaw∈[0,360)、
 * pitch∈[-90,90] 全域均匀随机瞬转 → 随机盯脚/盯天 + 大角度甩头。
 *
 * <p>行为模型 (模拟真人驻留时的头部动作):
 * <ul>
 *   <li>大部分时间平视附近环境扫视, 换目标时 yaw 限制在当前朝向 ±160° 内、
 *       pitch 压在 [-45°, +40°] 之间且以 [-15°, +20°] 为主 —— 不盯脚不盯天;</li>
 *   <li>时不转向附近的玩家/bot (优先) 或近距离生物 (降权), 注视 2.5~6s,
 *       实体移动时视线平滑跟随;</li>
 *   <li>可选锚点 (如守炉时的熔炉): 小概率瞄一眼锚点确认, 0.8~2s。</li>
 * </ul>
 *
 * <p>转向本身不在这里做: 每 tick 调 {@link LookHelper#lookAt(AltoClef, Rotation)}
 * 重登记目标, 由 {@link SmoothLookController} 帧级指数平滑驱动相机 —— 停留期间
 * 持续登记可保持相机持有权 (SmoothLookController 10 拍超时不触发), 任务停止后
 * 自然归还相机。战斗瞄准 (applyCombatAim / immediate 通道) 晚于任务层登记,
 * 优先级天然高于本类, 被打时视线让位战斗不受影响。
 */
public final class HumanoidGaze {

    // 选中"看实体"的基础概率 (没有合适实体则顺延为环境扫视)。
    private static final double ENTITY_GAZE_PROBABILITY = 0.45;
    // 有锚点 (熔炉等) 时瞄一眼锚点的概率。
    private static final double ANCHOR_GLANCE_PROBABILITY = 0.25;
    // 没有玩家可看时, 退而看近距离生物的概率 (降权, 人不会一直盯着动物)。
    private static final double LIVING_FALLBACK_PROBABILITY = 0.4;
    // 视线目标实体的最大距离 (格)。
    private static final double ENTITY_GAZE_MAX_RANGE = 24;

    // 各停留时长 (毫秒): 看实体 > 环境扫视 > 瞄锚点。
    private static final long DWELL_ENTITY_MS = 2500;
    private static final long DWELL_ENTITY_JITTER_MS = 3500;
    private static final long DWELL_AMBIENT_MS = 1200;
    private static final long DWELL_AMBIENT_JITTER_MS = 2300;
    private static final long DWELL_ANCHOR_MS = 800;
    private static final long DWELL_ANCHOR_JITTER_MS = 1200;

    /** 可选锚点 (如熔炉中心); null = 无锚点模式 (纯待机)。 */
    private final Vec3d _anchor;

    // 当前注视状态: 实体优先, 其次固定点 (锚点), 再次环境扫视旋转量。
    private Entity _focusEntity = null;
    private Vec3d _focusPoint = null;
    private Rotation _ambientRotation = null;
    /** 下次换注视目标的墙钟时刻; 0 = 立即重选。 */
    private long _switchAtMs = 0;

    public HumanoidGaze() {
        this(null);
    }

    /** @param anchor 可选锚点位置 (方块/世界坐标, 如熔炉中心)。 */
    public HumanoidGaze(Vec3d anchor) {
        _anchor = anchor;
    }

    /** 重置注视状态 (onStart 调用; 复用实例重进任务时强制立即重选目标)。 */
    public void reset() {
        _focusEntity = null;
        _focusPoint = null;
        _ambientRotation = null;
        _switchAtMs = 0;
    }

    /**
     * 每 tick 调用一次 (任务 onTick 内): 到点换目标, 然后把当前注视目标
     * 登记进平滑转向层。焦点实体死亡/走远时本拍不登记, 下拍自动重选。
     */
    public void tick(AltoClef mod) {
        if (mod.getPlayer() == null) return;
        long now = System.currentTimeMillis();
        if (now >= _switchAtMs) {
            pickNext(mod, now);
        }
        Rotation target = currentTarget(mod);
        if (target == null) {
            // 焦点失效 (死了/走远了): 本拍不动, 置 0 强制下拍重选。
            _switchAtMs = 0;
            return;
        }
        LookHelper.lookAt(mod, target);
    }

    /** 当前注视状态的一句话描述 (供 setDebugState / 诊断)。 */
    public String describe() {
        if (_focusEntity instanceof PlayerEntity player) {
            return "looking at player " + player.getName().getString();
        }
        if (_focusEntity != null) {
            return "looking at " + _focusEntity.getType().getName().getString();
        }
        if (_focusPoint != null) {
            return "glancing at anchor";
        }
        if (_ambientRotation != null) {
            return "glancing around";
        }
        return "gaze idle";
    }

    // ==== 内部: 选目标 ====

    private void pickNext(AltoClef mod, long now) {
        double roll = Math.random();
        // ① 看实体: 优先玩家 (别的 bot / 队友), 其次近距离生物。
        if (roll < ENTITY_GAZE_PROBABILITY) {
            Entity e = pickGazeEntity(mod);
            if (e != null) {
                _focusEntity = e;
                _focusPoint = null;
                _ambientRotation = null;
                _switchAtMs = now + DWELL_ENTITY_MS + (long) (Math.random() * DWELL_ENTITY_JITTER_MS);
                return;
            }
        }
        // ② 瞄一眼锚点 (守炉确认火候之类), 带小抖动避免机械重复。
        if (_anchor != null && Math.random() < ANCHOR_GLANCE_PROBABILITY) {
            _focusEntity = null;
            _focusPoint = _anchor.add(
                    (Math.random() - 0.5) * 0.6,
                    (Math.random() - 0.3) * 0.5,
                    (Math.random() - 0.5) * 0.6);
            _ambientRotation = null;
            _switchAtMs = now + DWELL_ANCHOR_MS + (long) (Math.random() * DWELL_ANCHOR_JITTER_MS);
            return;
        }
        // ③ 环境扫视: yaw 限幅 (±160°), pitch 压在自然区间, 不盯脚不盯天。
        _focusEntity = null;
        _focusPoint = null;
        _ambientRotation = pickAmbientRotation(mod);
        _switchAtMs = now + DWELL_AMBIENT_MS + (long) (Math.random() * DWELL_AMBIENT_JITTER_MS);
    }

    /**
     * 选一个值得看的实体: 24 格内最近的玩家优先 (bot/队友), 没有玩家时
     * 有 40% 概率看最近的生物 (动物/怪, 只要没在派杀冷却里也不构成开打)。
     * 只读, 纯感知消费 (EntityTracker 感知集), 不做任何透视扩展。
     */
    private Entity pickGazeEntity(AltoClef mod) {
        if (mod.getPlayer() == null) return null;
        Entity nearestPlayer = null;
        Entity nearestLiving = null;
        double bestPlayerSq = Double.MAX_VALUE;
        double bestLivingSq = Double.MAX_VALUE;
        double maxRangeSq = ENTITY_GAZE_MAX_RANGE * ENTITY_GAZE_MAX_RANGE;
        for (Entity e : mod.getEntityTracker().getCloseEntities()) {
            if (e == null || !e.isAlive() || e == mod.getPlayer()) continue;
            if (!(e instanceof LivingEntity)) continue;
            double distSq = e.squaredDistanceTo(mod.getPlayer());
            if (distSq > maxRangeSq) continue;
            if (e instanceof PlayerEntity) {
                if (distSq < bestPlayerSq) {
                    bestPlayerSq = distSq;
                    nearestPlayer = e;
                }
            } else if (distSq < bestLivingSq) {
                bestLivingSq = distSq;
                nearestLiving = e;
            }
        }
        if (nearestPlayer != null) return nearestPlayer;
        if (nearestLiving != null && Math.random() < LIVING_FALLBACK_PROBABILITY) return nearestLiving;
        return null;
    }

    /**
     * 环境扫视旋转量: yaw 相对当前朝向 ±160° (wrapDegrees 后仍取短弧方向),
     * pitch 分档 —— 75% 平视带自然浮动 [-15°, +20°]、15% 抬头 [-45°, -15°)、
     * 10% 低头 (+20°, +40°]。旧 randomOrientation 的盯脚/盯天 (pitch ±90°) 从
     * 根上排除。
     */
    private Rotation pickAmbientRotation(AltoClef mod) {
        float curYaw = LookHelper.getLookRotation().getYaw();
        float dYaw = (float) ((Math.random() * 2.0 - 1.0) * 160.0);
        // 太小的转头看不出"在看什么", 保底 10°。
        if (Math.abs(dYaw) < 10.0f) dYaw = dYaw < 0 ? -10.0f : 10.0f;
        float pitch;
        double p = Math.random();
        if (p < 0.75) {
            pitch = -15.0f + (float) (Math.random() * 35.0);   // [-15, +20] 平视
        } else if (p < 0.90) {
            pitch = -45.0f + (float) (Math.random() * 30.0);   // [-45, -15) 抬头
        } else {
            pitch = 20.0f + (float) (Math.random() * 20.0);    // (+20, +40] 低头
        }
        return new Rotation(MathHelper.wrapDegrees(curYaw + dYaw), pitch);
    }

    // ==== 内部: 取当前注视目标 ====

    private Rotation currentTarget(AltoClef mod) {
        if (_focusEntity != null) {
            // 实时跟随: 实体移动时视线平滑追过去 (SmoothLookController 负责平滑)。
            if (!_focusEntity.isAlive()) return null;
            if (mod.getPlayer() != null
                    && _focusEntity.squaredDistanceTo(mod.getPlayer())
                    > (ENTITY_GAZE_MAX_RANGE + 8) * (ENTITY_GAZE_MAX_RANGE + 8)) {
                return null; // 走远了, 下拍重选
            }
            return LookHelper.getLookRotation(mod, _focusEntity.getEyePos());
        }
        if (_focusPoint != null) {
            return LookHelper.getLookRotation(mod, _focusPoint);
        }
        return _ambientRotation;
    }
}
