package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import adris.altoclef.util.helpers.LookHelper;
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
            // 看到的准心完全一致) 真的命中目标实体才出手; 没对准就先把视角
            // 转向目标, 本 tick 不挥击。附带效果: 中间隔墙时准心是方块 →
            // 打不到, 与真人一致。所有调用点 (KillAura/任务层近战/杀龙/船)
            // 走这里, 自动拟人化。
            if (!isCrosshairOn(entity)) {
                // (2026-09-06 自适应瞄准点, 用户"中心被方块挡住就打不到") 旧版
                // 永远瞄包围盒中心 —— 实体贴墙/躲半格后时中心被方块挡住, 准心
                // 永远落不到实体上, bot 原地盯着墙不出手。改为在包围盒表面采样,
                // 从中挑一个视线真正通(不被方块挡)的点瞄; 找不到就退回中心。
                Optional<Vec3d> aim = findVisibleAimPoint(entity);
                LookHelper.lookAt(_mod, aim.orElseGet(() -> entity.getBoundingBox().getCenter()));
                return;
            }
            _mod.getController().attackEntity(_mod.getPlayer(), entity);
            _mod.getPlayer().swingHand(Hand.MAIN_HAND);
        }
    }

    /**
     * (2026-09-06 拟人化击打) 准心是否真的停在这个实体上。
     * 用客户端现成的 crosshairTarget 而不是自己再 raycast: 它就是游戏画面
     * 里十字准星的判定结果 (实体/方块/MISS), bot 出刀条件因此和真人玩家
     * 点鼠标的条件完全一致。
     */
    private boolean isCrosshairOn(Entity entity) {
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
