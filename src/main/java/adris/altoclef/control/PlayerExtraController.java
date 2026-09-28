package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

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
            return;
        }
        // (2026-09-25 火弹提前转头) reach 外的火弹也登记攻击瞄准诉求:
        // 火弹速度 ~1.6 格/tick, 穿过 3.2 格 reach 圈只剩 ~2 tick, "进圈才转头
        // 再出刀"根本来不及 (20260925 岩浆湖搭路被火弹炸落事故)。reach 外先
        // 把准心转过去, 进圈即可出刀。仅火弹豁免, 其他实体出 reach 不转头。
        if (entity instanceof FireballEntity) {
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
     * 能真正打到"的点。采样: 每根轴取 [min, mid, max] 三档 → 最多 27 个点
     * (含 8 角/6 面心/棱中点), 按离包围盒中心由近到远排序 —— 中心能打到时
     * 仍然优先瞄中段, 观感和旧版一致; 中心打不到时自动改瞄露出来的头/侧身。
     * 全部打不到 (实体完全藏住) 返回 empty, 调用方退回瞄中心。
     *
     * (2026-09-28 用户拍板 B, 现场 20260928-114406) 判据由"相机→点无方块遮挡"
     * (自写的 COLLIDER 形状 world.raycast) 换成 **该方向真能命中目标实体** ——
     * 复用 LookHelper.raycast(from, to, aimPoint, reach), 与出刀判定 (crosshairTarget)
     * 走同一处 ProjectileUtil 调用、同一 OUTLINE 形状口径 (项目铁律: 同一判据只留一份)。
     *
     * 旧口径的致命缝: 末影人排序基准 = (center.x, box.minY + 0.3, center.z), 而候选 y
     * 里含 box.minY ⇒ **脚底点距基准仅 0.3, 恒排第一**; 只要它"没被方块挡"就被选中。
     * 脚底点贴在 AABB 最低边缘 (容错 0) 且带来 ~46° 大俯角 —— bot 站在比目标高 1 格的
     * 台阶上时射线贴着地面/台沿走, 被地形或软植物 (草/藤蔓/菌根: 有轮廓无碰撞, 旧
     * COLLIDER 判据整类穿透) 吃掉 ⇒ crosshairTarget 落不到实体 ⇒ isCrosshairOn 恒
     * false ⇒ attack() 每拍只登记不挥刀; 而任务栈仍显示 "Approach entity"
     * (onEntityInteract 每 tick return move 的并行设计) ⇒ 用户看到"距离早就进了击打
     * 范围, 却一直在 approach", hp 11.96→7.76→3.56→0 被打死。
     *
     * 实测取证 (t=11504): 实际 pitch 45.9° = 瞄脚底几何值 45.98°, 而瞄身体中心只需
     * 24.8°, 且 Δyaw≈0 ⇒ 不是转向滞后, 就是瞄准点被选到了脚底。
     */
    private Optional<Vec3d> findVisibleAimPoint(Entity entity) {
        if (MinecraftClient.getInstance().player == null) return Optional.empty();
        Box box = entity.getBoundingBox();
        Vec3d center = box.getCenter();
        // (2026-09-21 末影人瞄脚, 用户"盯脚打防吸旁人") 排序基准从身体中心换成
        // 脚部中心 (minY+0.3): 原版盯头激怒只查头部包围盒与视线相交, 瞄脚让
        // 准心离开头区, 走位时不至于把旁边末影人吸过来。攻击判定扫整个包围盒,
        // 打脚照常掉血。
        // (2026-09-28 B) 排序只决定"先试哪个候选", 能不能打到由下面的真实命中
        // 判定把关 —— 脚底打不到时自然落到下一个候选, 不再死锁在脚底。
        Vec3d sortBase = entity instanceof net.minecraft.entity.mob.EndermanEntity
                ? new Vec3d(center.x, box.minY + 0.3, center.z)
                : center;

        List<Vec3d> candidates = new ArrayList<>();
        for (double x : new double[]{box.minX, center.x, box.maxX}) {
            for (double y : new double[]{box.minY, center.y, box.maxY}) {
                for (double z : new double[]{box.minZ, center.z, box.maxZ}) {
                    candidates.add(new Vec3d(x, y, z));
                }
            }
        }
        // 拟人优先级: 越接近身体中段的点越优先瞄 (末影人 = 越接近脚部)。
        candidates.sort(Comparator.comparingDouble(p -> p.squaredDistanceTo(sortBase)));

        // 射线长度取"至少覆盖到目标": 目标在射程内 = 验证"这一刀真打得到"; 目标在
        // 射程外时按真实距离延伸 —— attack() 对 reach 外的火弹也会登记诉求 (2026-09-25
        // 火弹提前转头, 火弹 1.6 格/tick 进圈只剩 2 tick, 来不及转), 那种场合若把射线
        // 卡在 reach 上, 全部候选都判死、退化成"瞄中心", 把提前转头的精度吃掉。
        // 语义仍是"这个方向上准星压得住目标", 与"此刻能不能出刀"(inReach, 另判) 分开。
        Entity player = MinecraftClient.getInstance().player;
        double reach = Math.max(_mod.getModSettings().getEntityReachRange(),
                Math.sqrt(entity.squaredDistanceTo(player)) + 1.0);
        // 由低到高取第一个"沿该方向真能命中该实体"的点:
        // 既保住"尽量瞄脚"(末影人防吸旁人), 又保证选出来的点一定打得中。
        for (Vec3d p : candidates) {
            if (LookHelper.raycast(player, entity, p, reach) != null) return Optional.of(p);
        }
        return Optional.empty();
    }
}
