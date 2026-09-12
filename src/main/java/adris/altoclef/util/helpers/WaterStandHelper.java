package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-12) 水面原地上下浮动 (bobbing) 的"脚下垫方块止浮"共用实现。
 *
 * 现场 (日志会话 20260912-085838): bot 卡在水面 y7↔9 每 0.5s 上下振荡
 * ([SWIM-DIVE-DIAG] gate=NOT_IN_WATER_BOB_AIRBORNE), dive+sprint 兜底 10+ 次
 * 零进展; 目标方块距离在可达半径边缘随 y 振荡闪进闪出, 挖掘无法进行。
 * 站定 (脚下实心) 后瞄准/可达/挖掘速度三者同时恢复 —— 与其加"持续瞄准"层
 * (会和 baritone/任务层抢 rotation), 不如先消除浮动根源。
 *
 * 放置语义与 PlaceBlockNearbyTask 一致: 瞄准承托方块 (floor) 的顶面,
 * 交互命中 floor + UP 面 → 放置目标 = floor.up() = 待垫水格。水是可替换
 * 方块, 可直接垫进水里; 垫完 bot 落上方块站定, 浮动消失。
 *
 * 设计约束 (与 PillarJumpHelper 同款):
 *  - 无状态: 每 tick 只按"当前世界状态"决定做什么, 被抢占重入天然安全。
 *  - 调用方 (SwimDiveChain) 负责状态机/窗口/冷却, 本类只做单 tick 探测与放置。
 *  - 材料复用 {@link PillarJumpHelper#allMaterials()} (常规建材 + 垂直垫塔
 *    可用的重力方块; 水下垫底下方永远有承托, 沙砾不会掉)。
 *  - 放置禁区走 {@code ExtraBaritoneSettings.shouldAvoidPlacingAt} (黑曜石
 *    传送门保护等全局谓词), 绝不在禁区垫方块。
 */
public final class WaterStandHelper {

    private WaterStandHelper() {
    }

    /** 从脚往下最多探几格找承托面 (bob 振幅通常 1-3 格, 再深就是深水该下潜了)。 */
    private static final int MAX_SCAN_DEPTH = 3;
    /** 垫面触及距离 (客户端 4.5, 收紧到 4.3 防边缘点击差一点)。 */
    private static final double MAX_PLACE_REACH_SQR = 4.3 * 4.3;

    /** 探测结论。 */
    public enum Status {
        /** 脚下已是实心且着地 —— 已站稳, 无需垫。 */
        SUPPORTED,
        /** 找到了可垫的水格 (cell) 与承托面 (floor)。 */
        FOUND,
        /** 目标格正被玩家碰撞箱占用 (bob 下浮半程), 等下一 tick 上浮再放。 */
        WAIT,
        /** 没得垫: 深水够不着 / 有水草睡莲等挡着 / 放置禁区。 */
        NONE
    }

    /** 探测结果 (FOUND 时 cell/floor 非空; 其余情况复用单例)。 */
    public static final class Spot {
        public final Status status;
        public final BlockPos cell;
        public final BlockPos floor;

        private Spot(Status status, BlockPos cell, BlockPos floor) {
            this.status = status;
            this.cell = cell;
            this.floor = floor;
        }
    }

    private static final Spot SUPPORTED = new Spot(Status.SUPPORTED, null, null);
    private static final Spot WAIT = new Spot(Status.WAIT, null, null);
    private static final Spot NONE = new Spot(Status.NONE, null, null);

    /**
     * 探测脚下能否垫方块止浮 (每 tick 调用, 无副作用)。
     *
     * 从脚下一格向下扫: 逐格要求"空气或水" (有水草/睡莲等实体方块挡路则放弃,
     * 交给下潜兜底), 直到第一个"其下是实心"的格子。命中后校验: 目标格不在
     * 玩家碰撞箱内、承托面顶面在触及距离内、不在全局放置禁区。
     */
    public static Spot probe(AltoClef mod, ClientPlayerEntity p) {
        BlockPos feet = p.getBlockPos();
        Vec3d eye = p.getEyePos();
        for (int dy = 1; dy <= MAX_SCAN_DEPTH; dy++) {
            BlockPos cell = feet.down(dy);
            BlockState st = mod.getWorld().getBlockState(cell);
            if (WorldHelper.isSolid(mod, cell)) {
                // 脚下已有实心支撑: 站稳才算 SUPPORTED, bob 下浮擦底 (悬空)
                // 返回 WAIT 等 next tick, 避免把"擦底半程"误判成已站稳。
                return p.isOnGround() ? SUPPORTED : WAIT;
            }
            if (!(st.isAir() || st.getBlock() == Blocks.WATER)) {
                // 水草/海带/睡莲等占格, 不垫 (保持与 WorldSurvivalChain 同款判据)
                return NONE;
            }
            BlockPos floor = cell.down();
            if (!WorldHelper.isSolid(mod, floor)) {
                continue; // 这格下面还是水, 再深一格找底
            }
            if (WorldHelper.isInsidePlayer(mod, cell)) {
                return WAIT; // bob 下浮半程, 玩家身体还占着这格
            }
            Vec3d face = Vec3d.ofCenter(floor).add(0, 0.5, 0);
            if (eye.squaredDistanceTo(face) > MAX_PLACE_REACH_SQR) {
                return NONE; // 承托面超出触及 (深水), 垫不了
            }
            if (mod.getExtraBaritoneSettings().shouldAvoidPlacingAt(cell)) {
                return NONE; // 全局放置禁区 (传送门保护等)
            }
            return new Spot(Status.FOUND, cell, floor);
        }
        return NONE; // MAX_SCAN_DEPTH 内无底 = 深水
    }

    /**
     * 垫方块单 tick (每 tick 调用一次, 仅在 probe=FOUND 时)。
     * 装备建材 → 平滑瞄准承托面顶面 → 准星命中 floor 顶面 (hit + UP 正是
     * 待垫水格) 时 interactBlock 放置。
     *
     * @return true = 放置成功 (服务端已接受); false = 本 tick 未完成
     *         (还没装备好 / 还没瞄到 / 点击被拒), 调用方下 tick 重试即可。
     */
    public static boolean placeTick(AltoClef mod, BlockPos floor) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return false;
        if (!StorageHelper.isEquipped(mod, PillarJumpHelper.allMaterials())) {
            if (!mod.getSlotHandler().forceEquipItem(PillarJumpHelper.allMaterials())) {
                return false; // 没建材了 (调用方入口已查过库存, 这里是保险)
            }
        }
        Vec3d face = Vec3d.ofCenter(floor).add(0, 0.5, 0);
        LookHelper.lookAt(mod, face);
        HitResult hit = MinecraftClient.getInstance().crosshairTarget;
        if (hit instanceof BlockHitResult bhit
                && bhit.getType() == HitResult.Type.BLOCK
                && bhit.getBlockPos().equals(floor)
                && bhit.getSide() == Direction.UP
                && MinecraftClient.getInstance().interactionManager != null) {
            ActionResult r = MinecraftClient.getInstance().interactionManager.interactBlock(
                    p, mod.getWorld(), Hand.MAIN_HAND, bhit);
            if (r == ActionResult.SUCCESS) {
                p.swingHand(Hand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }
}
