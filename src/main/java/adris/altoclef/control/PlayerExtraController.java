package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class PlayerExtraController {

    private final AltoClef _mod;
    private BlockPos _blockBreakPos;
    private double _blockBreakProgress;

    // (2026-09-07 准心仲裁) 本 tick 的攻击瞄准诉求: 一根准心每 tick 只能瞄一个
    // 实体, 多个击打诉求 (任务层主目标 + KillAura 力场近怪) 争夺时, 离 bot 最近
    // 的拿到瞄准权 (用户"优先击杀脸上最近的怪")。attack() 只登记诉求不直接转头,
    // 统一由 AltoClef tick 尾的 applyCombatAim() 唯一出口 lookAt, 避免各调用点
    // 每 tick 互相抢覆盖 (修"两只僵尸在脸上却一直瞄远处那只")。
    private long _combatAimTick = Long.MIN_VALUE;
    private Entity _combatAimEntity = null;
    private double _combatAimDistSq = Double.MAX_VALUE;
    private Vec3d _combatAimPoint = null;

    // (2026-09-07 防御朝向优先, 用户"苦力怕格挡优先") 举盾格挡的面朝方向
    // (盾牌核弹面朝 fuse 苦力怕 / 面朝来袭弹射物) 比"瞄最近攻击目标"更优先:
    // 盾的角度错了格挡就白举。单槽不参与距离竞争 —— 防御朝向由威胁选择逻辑
    // (MobDefenseChain) 自己决定。applyCombatAim 里防御诉求存在时压过攻击诉求。
    private long _defenseAimTick = Long.MIN_VALUE;
    private Vec3d _defenseAimPoint = null;

    // (2026-09-07 击杀超时兜底) 最近一次真实出刀 (准心落上并挥击) 的目标与
    // tick, 供 AbstractKillEntityTask 判定"多久没砍中目标了"。
    private long _lastSwingTick = Long.MIN_VALUE;
    private java.util.UUID _lastSwingTargetId = null;

    public PlayerExtraController(AltoClef mod) {
        _mod = mod;

        EventBus.subscribe(BlockBreakingEvent.class, evt -> onBlockBreak(evt.blockPos, evt.progress));
        EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> onBlockStopBreaking());
    }

    private void onBlockBreak(BlockPos pos, double progress) {
        _blockBreakPos = pos;
        _blockBreakProgress = progress;
    }

    private void onBlockStopBreaking() {
        _blockBreakPos = null;
        _blockBreakProgress = 0;
    }

    public BlockPos getBreakingBlockPos() {
        return _blockBreakPos;
    }

    public boolean isBreakingBlock() {
        return _blockBreakPos != null;
    }

    public double getBreakingBlockProgress() {
        return _blockBreakProgress;
    }

    public boolean inRange(Entity entity) {
        return _mod.getPlayer().isInRange(entity, _mod.getModSettings().getEntityReachRange());
    }

    public void attack(Entity entity) {
        if (inRange(entity)) {
            // (2026-09-06 拟人化击打, 用户"不希望开挂式的击打") 旧实现直接
            // attackEntity 发包, 完全不看准心 —— MC 的 attackEntity 只要实体在
            // reach 内就结算伤害, bot 背对怪也能砍中 (外挂式)。KillAura 力场
            // (MobDefenseChain) 更是 360° 无朝向攻击。现改为: 只有准心
            // (crosshairTarget, 客户端每 tick 按真实视角 raycast 得出, 与玩家
            // 看到的准心完全一致) 真的命中目标实体才出手。
            // (2026-09-07 准心仲裁) 出刀还需本实体是本 tick 攻击诉求里最近的
            // (准心空闲或已被自己认领视为满足) —— 否则上一 tick 遗留的准心停在
            // 远目标上时, 远目标会趁近目标还没瞄上就抢刀, "优先杀脸上最近"
            // 失效。没抢到准心的诉求只登记, tick 尾统一转头。
            if (isCrosshairOn(entity) && isCombatAimOwner(entity)) {
                _mod.getController().attackEntity(_mod.getPlayer(), entity);
                _mod.getPlayer().swingHand(Hand.MAIN_HAND);
                _lastSwingTick = WorldHelper.getTicks();
                _lastSwingTargetId = entity.getUuid();
                return;
            }
            requestCombatAim(entity);
        }
    }

    /**
     * (2026-09-07 击杀超时兜底) 指定目标最近一次被真实出刀 (准心落上并挥击)
     * 的游戏 tick; 从未砍中过返回 Long.MIN_VALUE。
     */
    public long getLastSwingTickFor(java.util.UUID targetId) {
        return targetId != null && targetId.equals(_lastSwingTargetId) ? _lastSwingTick : Long.MIN_VALUE;
    }

    /**
     * (2026-09-07 准心仲裁) 本实体是否拥有本 tick 的出刀权:
     * 准心停在它身上, 且它就是本 tick 距离最近的攻击诉求 (或本 tick 还没人登记)。
     */
    private boolean isCombatAimOwner(Entity entity) {
        if (_combatAimTick != WorldHelper.getTicks() || _combatAimEntity == null) return true;
        if (_combatAimEntity == entity) return true;
        return entity.squaredDistanceTo(_mod.getPlayer()) < _combatAimDistSq;
    }

    /**
     * (2026-09-07 准心仲裁) 登记本 tick 的攻击瞄准诉求, 离 bot 最近者胜出。
     * 胜出者的自适应瞄准点 (findVisibleAimPoint) 在登记时算好, tick 尾由
     * applyCombatAim() 统一 lookAt。
     */
    private void requestCombatAim(Entity entity) {
        long now = WorldHelper.getTicks();
        if (_combatAimTick != now) {
            _combatAimTick = now;
            _combatAimEntity = null;
            _combatAimDistSq = Double.MAX_VALUE;
            _combatAimPoint = null;
        }
        double distSq = entity.squaredDistanceTo(_mod.getPlayer());
        if (_combatAimEntity != null && distSq >= _combatAimDistSq) return;
        _combatAimEntity = entity;
        _combatAimDistSq = distSq;
        // (2026-09-06 自适应瞄准点) 中心被方块挡住时改瞄露出的头/侧身。
        _combatAimPoint = findVisibleAimPoint(entity)
                .orElseGet(() -> entity.getBoundingBox().getCenter());
    }

    /**
     * (2026-09-07 防御朝向优先) 登记本 tick 的防御朝向诉求 (面朝 fuse 苦力怕/
     * 来袭弹射物举盾)。防御 > 攻击: 格挡的角度错了伤害一点挡不住, 攻击瞄准
     * 晚几 tick 无伤大雅。由 applyCombatAim() 在 tick 尾统一 lookAt。
     */
    public void requestDefenseAim(Vec3d point) {
        _defenseAimTick = WorldHelper.getTicks();
        _defenseAimPoint = point;
    }

    /**
     * (2026-09-07 准心仲裁) tick 尾唯一出口: 优先应用防御朝向 (格挡),
     * 其次把准心转向本 tick 胜出的攻击诉求的瞄准点, 然后清空仲裁状态
     * (下一 tick 重新裁决)。由 AltoClef.onTick 在 taskRunner.tick() 之后
     * 调用 —— 此时任务层和链层的所有 lookAt/attack 都已跑完, 这里的转向
     * 不会被同一 tick 内的任何战斗代码再覆盖。
     */
    public void applyCombatAim() {
        long now = WorldHelper.getTicks();
        if (_defenseAimTick == now && _defenseAimPoint != null) {
            LookHelper.lookAt(_mod, _defenseAimPoint);
            _defenseAimPoint = null;
            _combatAimEntity = null;
            _combatAimDistSq = Double.MAX_VALUE;
            _combatAimPoint = null;
            return;
        }
        if (_combatAimTick != now || _combatAimEntity == null) return;
        if (_combatAimPoint != null) {
            LookHelper.lookAt(_mod, _combatAimPoint);
        }
        _combatAimEntity = null;
        _combatAimDistSq = Double.MAX_VALUE;
        _combatAimPoint = null;
    }

    /**
     * (2026-09-06 拟人化击打) 准心是否真的停在这个实体上。
     * 用客户端现成的 crosshairTarget 而不是自己再 raycast: 它就是游戏画面
     * 里十字准星的判定结果 (实体/方块/MISS), bot 出刀条件因此和真人玩家
     * 点鼠标的条件完全一致。
     */
    // (2026-09-09 public) 猪灵交易也复用同一套准心校验 (用户"必须准心指向猪灵
    // 无遮挡才允许交易, 使用通用的自动瞄准") — 与战斗出刀同一判定来源。
    public boolean isCrosshairOn(Entity entity) {
        HitResult result = MinecraftClient.getInstance().crosshairTarget;
        return result instanceof EntityHitResult && ((EntityHitResult) result).getEntity() == entity;
    }

    /**
     * (2026-09-06 自适应瞄准点) 从包围盒表面采样点里挑一个"从我们相机看过去
     * 不被方块挡住"的点。采样: 每根轴取 [min, mid, max] 三档 → 最多 27 个点
     * (含 8 角/6 面心/棱中点), 按离包围盒中心由近到远排序 —— 中心没被挡时
     * 仍然优先瞄中段, 观感和旧版一致; 中心被挡时自动改瞄露出来的头/侧身。
     * 全部被挡 (实体完全藏住) 返回 empty, 调用方退回瞄中心。
     */
    private Optional<Vec3d> findVisibleAimPoint(Entity entity) {
        if (MinecraftClient.getInstance().player == null) return Optional.empty();
        Vec3d cam = MinecraftClient.getInstance().player.getCameraPosVec(1.0F);
        Box box = entity.getBoundingBox();
        Vec3d center = box.getCenter();

        List<Vec3d> candidates = new ArrayList<>();
        for (double x : new double[]{box.minX, center.x, box.maxX}) {
            for (double y : new double[]{box.minY, center.y, box.maxY}) {
                for (double z : new double[]{box.minZ, center.z, box.maxZ}) {
                    candidates.add(new Vec3d(x, y, z));
                }
            }
        }
        // 拟人优先级: 越接近身体中段的点越优先瞄。
        candidates.sort(Comparator.comparingDouble(p -> p.squaredDistanceTo(center)));

        for (Vec3d p : candidates) {
            if (isPointVisible(cam, p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /**
     * 相机到点 p 的视线上, 是否没有方块在 p 之前挡住。
     * 射线略微延伸过 p (0.1), 方块命中点比 p 更远即视为通畅。
     */
    private boolean isPointVisible(Vec3d cam, Vec3d p) {
        double dist = p.distanceTo(cam);
        if (dist < 0.1) return true; // 相机几乎贴在点上
        Vec3d end = cam.add(p.subtract(cam).normalize().multiply(dist + 0.1));
        BlockHitResult hit = MinecraftClient.getInstance().world.raycast(
                new RaycastContext(cam, end, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, MinecraftClient.getInstance().player));
        return hit.getType() == HitResult.Type.MISS
                || hit.getPos().distanceTo(cam) >= dist - 0.05;
    }
}
