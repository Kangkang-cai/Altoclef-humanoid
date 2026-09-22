package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

/**
 * Helper functions to interpret and change our player's look direction
 */
public interface LookHelper {

    /**
     * 【右键交互语义】side==null 时用 3.0 的收紧距离 —— bot 走到容器旁再点。
     *
     * (2026-09-02 持盾右键变举盾, 用户"开箱/熔炉走太远右键变举盾") MC 方块交互距
     * 离是 4.5, 但 bot 在 4.5 格边缘点击时射线常差一点没点中方块 → 副手持盾右键被
     * MC 当成举盾 (不开容器)。点击判定距离收紧到 3 格内: 导航 GoalNear(target,1)
     * 本就停在 1 格, 收紧只改变"何时开始点击", 不影响已走近的交互。
     *
     * ⚠ 本方法只服务【右键交互】(InteractWithBlockTask.getCurrentReach / 水桶链)。
     * 挖掘与舀液体请走单参 {@link #getReach(BlockPos)} —— 那里要的是真实游戏 reach。
     */
    static final double INTERACT_REACH_NO_SIDE = 3.0;

    static Optional<Rotation> getReach(BlockPos target, Direction side) {
        Optional<Rotation> reachable;
        IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
        if (side == null) {
            assert MinecraftClient.getInstance().player != null;
            reachable = RotationUtils.reachable(ctx.player(), target, INTERACT_REACH_NO_SIDE);
        } else {
            Vec3i sideVector = side.getVector();
            Vec3d centerOffset = new Vec3d(0.5 + sideVector.getX() * 0.5, 0.5 + sideVector.getY() * 0.5, 0.5 + sideVector.getZ() * 0.5);

            Vec3d sidePoint = centerOffset.add(target.getX(), target.getY(), target.getZ());

            //reachable(this.ctx.player(), _target, this.ctx.playerController().getBlockReachDistance());
            reachable = RotationUtils.reachableOffset(ctx.player(), target, sidePoint, ctx.playerController().getBlockReachDistance(), false);

            // Check for right angle
            if (reachable.isPresent()) {
                // Note: If sneak, use RotationUtils.inferSneakingEyePosition
                Vec3d camPos = ctx.player().getCameraPosVec(1.0F);
                Vec3d vecToPlayerPos = camPos.subtract(sidePoint);

                double dot = vecToPlayerPos.normalize().dotProduct(new Vec3d(sideVector.getX(), sideVector.getY(), sideVector.getZ()));
                if (dot < 0) {
                    // We're perpendicular and cannot face.
                    return Optional.empty();
                }
            }
        }
        return reachable;
    }

    /**
     * 【挖掘 / 舀液体语义】side==null 时用**真实游戏 reach**(生存 4.5 / 创造 5.0)。
     *
     * (2026-09-14 挖掘 reach 回归修复) 2026-09-03 `ee06bbe9` 为"持盾右键变举盾"把
     * side==null 分支改成了硬编码 3.0, 但那条分支同时被挖掘/舀液体消费 ——
     * `DestroyBlockTask:195`(挖掘) 与 `CollectBucketLiquidTask:334`(舀液体) 的 reach
     * 被连带从 4.5 削到 3.0, **副作用从未被察觉**。
     *
     * 20260914-110653 实锤: bot feet y=65 / 眼 66.62, 目标悬空原木 (92,70,2) 下表面
     * y=70.0 → 眼到方块 **3.38 格**, 恰好卡在 3.0 之外 → `getReach` 返空 →
     * `DestroyBlockTask` 落 "Getting to block…" 分支(该分支整段无 lookAt) → 给 baritone
     * 派 `GoalNear(_pos,1)`(对"站立面之上 5 格"不可满足, A* 烧掉 10.5M 元素无路径) →
     * 原地冻结 16.3s, 表现为"不瞄准、不撸、站死"; 木料停在 11/25 后任务终止。
     * (同场 `RotationUtils.reachable` 首行有 `ctx.isLookingAt(pos)` 短路 —— 准心恰好压在
     *  该方块上就无条件返回 present, 所以同一格时好时坏, 取决于头当时指哪。)
     *
     * 这里拆回两条语义: 挖掘/舀液体 = 真实 reach; 右键交互 = 上面那条 3.0 方法。
     * 举盾问题不会被重新引入 —— 真正挡住它的是 `InteractWithBlockTask.isPreciselyAiming`
     * (要求 crosshair 是 BLOCK 命中且 blockPos==_target) + 0.3s 点击节流, 3.0 只是叠加保险。
     */
    static Optional<Rotation> getReach(BlockPos target) {
        IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
        assert MinecraftClient.getInstance().player != null;
        return RotationUtils.reachable(ctx.player(), target, ctx.playerController().getBlockReachDistance());
    }

    static EntityHitResult raycast(Entity from, Entity to, double reachDistance) {
        Vec3d fromPos = getCameraPos(from),
                toPos = getCameraPos(to);
        Vec3d direction = (toPos.subtract(fromPos).normalize().multiply(reachDistance));
        Box box = to.getBoundingBox();
        return ProjectileUtil.raycast(from, fromPos, fromPos.add(direction), box, entity -> entity.equals(to), 0);
    }

    static boolean seesPlayer(Entity entity, Entity player, double maxRange, Vec3d entityOffs, Vec3d playerOffs) {
        return seesPlayerOffset(entity, player, maxRange, entityOffs, playerOffs) || seesPlayerOffset(entity, player, maxRange, entityOffs, new Vec3d(0, -1, 0).add(playerOffs));
    }

    static boolean seesPlayer(Entity entity, Entity player, double maxRange) {
        return seesPlayer(entity, player, maxRange, Vec3d.ZERO, Vec3d.ZERO);
    }

    static boolean cleanLineOfSight(Entity entity, Vec3d start, Vec3d end, double maxRange) {
        return raycast(entity, start, end, maxRange).getType() == HitResult.Type.MISS;
    }

    static boolean cleanLineOfSight(Entity entity, Vec3d end, double maxRange) {
        Vec3d start = getCameraPos(entity);
        return cleanLineOfSight(entity, start, end, maxRange);
    }
    static boolean cleanLineOfSight(Vec3d end, double maxRange) {
        return cleanLineOfSight(MinecraftClient.getInstance().player, end, maxRange);
    }

    static boolean cleanLineOfSight(Entity entity, BlockPos block, double maxRange) {
        Vec3d center = WorldHelper.toVec3d(block);
        // (2026-08-23 流体 LOS 修复) 目标方块是流体 (水/岩浆) 时, 若仍用
        // FluidHandling.NONE, 射线会直接穿过流体命中背后的实心方块 (岩浆湖
        // 底/岩壁), hit 方块 ≠ 目标 → 永远判"不可见" (用户实测: 左前方一条
        // 缝看到岩浆湖却感知不到, 一直 wander 找岩浆)。流体用 SOURCE_ONLY,
        // 射线会命中流体源方块本身, LOS 判定正确。
        boolean isFluid = !entity.world.getBlockState(block).getFluidState().isEmpty();
        RaycastContext.FluidHandling handling = isFluid ? RaycastContext.FluidHandling.SOURCE_ONLY : RaycastContext.FluidHandling.NONE;

        // (2026-08-23 6 面 LOS 判定) 用户实测: 平坦的岩浆湖只能看到很扁平的
        // 表面, 以方块中心为目标的射线会被湖边缘/岩壁挡住 → 判"不可见";
        // 强行搭高正对湖中心才看到。方块的中心被挡, 不代表它的表面不可见。
        // 中心判定失败时, 再对 6 个面的中心点各射一条射线, 任一命中目标方块
        // 本身即视为可见。中心通过 (绝大多数正常视野) 时快速短路返回。
        Vec3d start = getCameraPos(entity);

        // ① 中心点判定
        BlockHitResult centerHit = raycastSkippingWebs(entity, start, center, maxRange, handling);
        if (centerHit != null && centerHit.getType() == HitResult.Type.BLOCK && centerHit.getBlockPos().equals(block)) {
            return true;
        }
        // MISS (射线到中心没碰到任何东西) 保持原语义: 视为可见
        if (centerHit == null || centerHit.getType() == HitResult.Type.MISS) {
            return true;
        }

        // ② 6 个面判定 (2026-08-23): 中心被其他方块挡住 (BLOCK hit ≠ 目标) 时,
        // 任一可见面即可见 — 解决平坦岩浆湖/扁平方块侧面可见但中心被挡的问题。
        for (Direction dir : Direction.values()) {
            Vec3d faceCenter = center.add(
                    dir.getOffsetX() * 0.5,
                    dir.getOffsetY() * 0.5,
                    dir.getOffsetZ() * 0.5);
            BlockHitResult faceHit = raycastSkippingWebs(entity, start, faceCenter, maxRange, handling);
            if (faceHit != null && faceHit.getType() == HitResult.Type.BLOCK && faceHit.getBlockPos().equals(block)) {
                return true;
            }
        }

        // ③ 8 角点判定 (2026-09-21): 中心+6 面射线全被挡、但方块以边角露出时,
        // 人眼在屏幕上仍能看到它的一角 — 几何事实是角点仍在方块自身体积内,
        // 对 8 个角点各补一条射线, 任一命中目标方块本身即可见, 不引入透视。
        // (20260921-134247 场实锤引入: END_PORTAL_FRAME 嵌在传送门房间内部,
        // 从门口看到的是门框方块的边角, 12 个门框点全在 12r 内, 其中 3 个
        // FOV=true 但 LOS=false, bot 贴着门格栅探索就是不停。)
        // (2026-09-22 放开到全部方块, 20260922-121524 实锤同类事故: 钻石矿
        // 48,-41,-35 在 7m 外以边角露出, 用户屏幕上"钻石就在眼前", 中心+6 面
        // 射线全被挡 → perceivable=0 → bot 弃矿挖隧道。判据一致性原则: 同一
        // 几何事实(角点在方块体积内、命中须为目标方块本身)不应按方块种类
        // 分裂成两套口径 — 09-21 那次只放开门框是打补丁, 这次收编为统一判定。)
        for (double sx : new double[]{-0.5, 0.5}) {
            for (double sy : new double[]{-0.5, 0.5}) {
                for (double sz : new double[]{-0.5, 0.5}) {
                    Vec3d corner = center.add(sx, sy, sz);
                    BlockHitResult cornerHit = raycastSkippingWebs(entity, start, corner, maxRange, handling);
                    if (cornerHit != null && cornerHit.getType() == HitResult.Type.BLOCK
                            && cornerHit.getBlockPos().equals(block)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // (2026-09-11 蜘蛛网透明 LOS) 蜘蛛网碰撞箱是整格 (ShapeType.COLLIDER 下
    // 射线必命中), 但视觉上它是稀疏的网 — 人隔着蜘蛛网能看见后面的钻石。
    // 现场 2026-09-11 11:05-11:08 实锤: [Diag-blind] cached=200 钻石矿
    // perceivable=0, 最近 3 格仍 occluded, bot 路过多次不去挖。本射线遇到
    // 蜘蛛网命中时从网块内继续向前 re-cast (最多穿 MAX_WEB_PASSTHROUGH 层),
    // 只对网透明 — 网后的实心方块 (岩壁等) 仍正常遮挡, 不引入透视。
    // 与 2026-09-09 树叶透明 LOS (isWoodVisibleThroughLeaves) 同一反透视层级:
    // 网只存在于可见结构处, 穿网 ≠ 穿岩。
    // interface 字段隐式 public — 不加 private 修饰符
    static final int MAX_WEB_PASSTHROUGH = 4;

    private static BlockHitResult raycastSkippingWebs(Entity entity, Vec3d start, Vec3d end, double maxRange, RaycastContext.FluidHandling fluidHandling) {
        Vec3d cur = start;
        BlockHitResult hit = null;
        for (int pass = 0; pass <= MAX_WEB_PASSTHROUGH; pass++) {
            hit = raycast(entity, cur, end, maxRange, fluidHandling);
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
                return hit; // MISS 或非方块命中, 原样返回
            }
            if (hit.getBlockPos().equals(new BlockPos(end.x, end.y, end.z))) {
                return hit; // 命中的就是目标方块本身 → 可见
            }
            BlockState hitState = entity.world.getBlockState(hit.getBlockPos());
            if (hitState.getBlock() != Blocks.COBWEB) {
                return hit; // 非网方块 → 正常遮挡判定, 原样返回
            }
            // 命中蜘蛛网 → 推进起点越过该网块中心, 继续 re-cast
            Vec3d dir = end.subtract(cur);
            if (dir.lengthSquared() < 1.0E-4) {
                return hit; // 射线已走到头
            }
            cur = Vec3d.ofCenter(hit.getBlockPos()).add(dir.normalize().multiply(0.6));
        }
        return hit; // 穿透层数用尽, 按最后一次命中处理 (保守判遮挡)
    }

    /**
     * (2026-08-23) 带流体处理方式的 raycast, 供 LOS 面判定复用。
     */
    static BlockHitResult raycast(Entity entity, Vec3d start, Vec3d end, double maxRange, RaycastContext.FluidHandling fluidHandling) {
        Vec3d delta = end.subtract(start);
        if (delta.lengthSquared() > maxRange * maxRange) {
            end = start.add(delta.normalize().multiply(maxRange));
        }
        return entity.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, fluidHandling, entity));
    }

    static Vec3d toVec3d(Rotation rotation) {
        return RotationUtils.calcVector3dFromRotation(rotation);
    }

    static BlockHitResult raycast(Entity entity, Vec3d start, Vec3d end, double maxRange) {
        Vec3d delta = end.subtract(start);
        if (delta.lengthSquared() > maxRange * maxRange) {
            end = start.add(delta.normalize().multiply(maxRange));
        }
        return entity.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity));
    }

    static BlockHitResult raycast(Entity entity, Vec3d end, double maxRange) {
        Vec3d start = getCameraPos(entity);
        return raycast(entity, start, end, maxRange);
    }

    static Rotation getLookRotation(Entity entity) {
        float pitch = entity.getPitch();
        float yaw = entity.getYaw();
        return new Rotation(yaw, pitch);
    }
    static Rotation getLookRotation() {
        if (MinecraftClient.getInstance().player == null) {
            return new Rotation(0,0);
        }
        return getLookRotation(MinecraftClient.getInstance().player);
    }

    static Vec3d getCameraPos(Entity entity) {
        boolean isSneaking = false;
        if (entity instanceof PlayerEntity player) {
            isSneaking = player.isSneaking();
        }
        return isSneaking ? RayTraceUtils.inferSneakingEyePosition(entity) : entity.getCameraPosVec(1.0F);
    }
    static Vec3d getCameraPos(AltoClef mod) {
        IPlayerContext ctx = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext();
        return ctx.player().getCameraPosVec(1);
    }

    //  1: Looking straight at pos
    //  0: pos is 90 degrees to the side
    // -1: pos is 180 degrees away (looking away completely)
    static double getLookCloseness(Entity entity, Vec3d pos) {
        Vec3d rotDirection = entity.getRotationVecClient();
        Vec3d lookStart = getCameraPos(entity);
        Vec3d deltaToPos = pos.subtract(lookStart);
        Vec3d deltaDirection = deltaToPos.normalize();
        return rotDirection.dotProduct(deltaDirection);
    }

    static boolean tryAvoidingInteractable(AltoClef mod) {
        if (isCollidingInteractable(mod)) {
            randomOrientation(mod);
            return false;
        }
        return true;
    }

    private static boolean seesPlayerOffset(Entity entity, Entity player, double maxRange, Vec3d offsetEntity, Vec3d offsetPlayer) {
        Vec3d start = getCameraPos(entity).add(offsetEntity);
        Vec3d end = getCameraPos(player).add(offsetPlayer);
        return cleanLineOfSight(entity, start, end, maxRange);
    }

    private static boolean isCollidingInteractable(AltoClef mod) {

        if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) {
            StorageHelper.closeScreen();
            return true;
        }

        HitResult result = MinecraftClient.getInstance().crosshairTarget;
        if (result == null) return false;
        if (result.getType() == HitResult.Type.BLOCK) {
            return WorldHelper.isInteractableBlock(mod, new BlockPos(result.getPos()));
        } else if (result.getType() == HitResult.Type.ENTITY) {
            if (result instanceof EntityHitResult) {
                Entity entity = ((EntityHitResult) result).getEntity();
                return entity instanceof MerchantEntity;
            }
        }
        return false;
    }

    static void randomOrientation(AltoClef mod) {
        Rotation r = new Rotation((float) Math.random() * 360f, -90 + (float) Math.random() * 180f);
        lookAt(mod, r);
    }

    static boolean isLookingAt(AltoClef mod, Rotation rotation) {
        return rotation.isReallyCloseTo(getLookRotation());
    }
    static boolean isLookingAt(AltoClef mod, BlockPos blockPos) {
        return mod.getClientBaritone().getPlayerContext().isLookingAt(blockPos);
    }

    static void lookAt(AltoClef mod, Rotation rotation) {
        // Let baritone handle the rotation smoothly (with max speed limits) instead of
        // instantly snapping the camera, which looks/behaves unnaturally.
        mod.getClientBaritone().getLookBehavior().updateTarget(rotation, true);
    }
    static void lookAt(AltoClef mod, Vec3d toLook) {
        Rotation targetRotation = getLookRotation(mod, toLook);
        lookAt(mod, targetRotation);
    }
    static void lookAt(AltoClef mod, BlockPos toLook, Direction side) {
        Vec3d target = new Vec3d(toLook.getX() + 0.5, toLook.getY() + 0.5, toLook.getZ() + 0.5);
        if (side != null) {
            target.add(side.getVector().getX() * 0.5, side.getVector().getY() * 0.5, side.getVector().getZ() * 0.5);
        }
        lookAt(mod, target);
    }
    static void lookAt(AltoClef mod, BlockPos toLook) {
        lookAt(mod, toLook, null);
    }

    static Rotation getLookRotation(AltoClef mod, Vec3d toLook) {
        return RotationUtils.calcRotationFromVec3d(mod.getClientBaritone().getPlayerContext().playerHead(), toLook, mod.getClientBaritone().getPlayerContext().playerRotations());
    }
    static Rotation getLookRotation(AltoClef mod, BlockPos toLook) {
        return getLookRotation(mod, WorldHelper.toVec3d(toLook));
    }

}
