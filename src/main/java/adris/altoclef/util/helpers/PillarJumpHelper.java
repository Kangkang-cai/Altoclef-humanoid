package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-11) 原地垫塔上升 (pillar-jump) 共用实现。
 *
 * 经典"跳起来往脚下放方块": 站在实体上 → hold 跳; 腾空后脚下出现
 * "空气 + 其下实体" 的放置窗口 → 瞄准该格的顶面 interactBlock 放置。
 * 循环一次净上升 1 格, 不依赖任何地形/水流, 是最稳的垂直移动手段。
 *
 * 来源: TowerEscapeTask (2026-09-07) 的 pillarStep/tryPlaceBelow 抽取,
 * 行为逐行等价; TowerEscapeTask 改为委托本类, 消除两份实现。
 *
 * 设计约束 (踩坑记录):
 *  - 无状态: 每 tick 只按"当前世界状态"决定按什么键, 被抢占重入天然安全
 *    (吸取 64290c94 教训: 状态尽量无状态化)。
 *  - 调用方必须在退出路径上调用 {@link #release} 松开跳跃键, 否则残留长按。
 *  - 材料清单由调用方传入: 水平悬空搭路不能用重力方块 (会掉), 垂直垫塔
 *    (下方永远有支撑) 可以用沙/沙砾 —— 这决定了"挖沙砾场景"是否有材料。
 */
public final class PillarJumpHelper {

    private PillarJumpHelper() {
    }

    /** 常规建材 (不受重力, 任意场景可放)。 */
    public static final Item[] SAFE_MATERIALS = new Item[]{
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK,
            Items.STONE, Items.DEEPSLATE, Items.TUFF,
            Items.GRANITE, Items.DIORITE, Items.ANDESITE
    };

    /**
     * 重力方块。水平搭路会掉, 但**垂直垫塔下方必有支撑不会掉** ——
     * 挖穿沙砾/沙层时这是唯一现成的材料来源, 必须允许。
     */
    public static final Item[] GRAVITY_MATERIALS = new Item[]{
            Items.SAND, Items.RED_SAND, Items.GRAVEL
    };

    /** 垫塔可用材料全集 (常规 + 重力)。 */
    public static Item[] allMaterials() {
        Item[] safe = SAFE_MATERIALS;
        Item[] gravity = GRAVITY_MATERIALS;
        Item[] all = new Item[safe.length + gravity.length];
        System.arraycopy(safe, 0, all, 0, safe.length);
        System.arraycopy(gravity, 0, all, safe.length, gravity.length);
        return all;
    }

    /**
     * 背包 (主背包 + 副手) 里可用垫塔方块的总数。
     * (2026-08-23 统计修正同款) 直接扫背包, 不走 ItemStorageTracker 缓存。
     */
    public static int countMaterials(AltoClef mod, Item[] materials) {
        if (mod.getPlayer() == null || mod.getPlayer().getInventory() == null) return 0;
        int count = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack == null || stack.isEmpty()) continue;
            for (Item item : materials) {
                if (stack.getItem() == item) {
                    count += stack.getCount();
                    break;
                }
            }
        }
        ItemStack offhand = mod.getPlayer().getInventory().offHand.get(0);
        if (offhand != null && !offhand.isEmpty()) {
            for (Item item : materials) {
                if (offhand.getItem() == item) {
                    count += offhand.getCount();
                    break;
                }
            }
        }
        return count;
    }

    public static boolean hasMaterials(AltoClef mod, Item[] materials) {
        return countMaterials(mod, materials) > 0;
    }

    /** 松开垫塔期间 hold 的跳跃键 (退出路径必调)。 */
    public static void release(AltoClef mod) {
        mod.getInputControls().release(Input.JUMP);
        // (2026-09-12 v21) 居中转向可能残留移动输入, 退出时一并松开。
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.MOVE_BACK);
        mod.getInputControls().release(Input.MOVE_LEFT);
        mod.getInputControls().release(Input.MOVE_RIGHT);
    }

    // (2026-09-12 v21 居中再跳) 1x1 竖井垫跳: 头顶开口只有 1 格宽, 玩家碰撞箱
    // 0.6 —— 站偏超过 (1.0-0.6)/2 = 0.2 格, 起跳瞬间头顶就蹭到开口边沿 → 垂直
    // 速度被顶掉, 跳不起来 (20260912-203009 20:43:55-20:44:12 y=-16 同格 17s+
    // 实锤, 用户: "站的偏了一点, 老是撞到旁边的方块导致跳不起来")。人类做法 =
    // 先把脚挪到方块正中再跳。跳跃放行阈值 0.2 (几何硬上限, 不留余量就蹭),
    // 转向松键阈值 0.25 (松键后动量滑行 ~0.1, 自然落进 0.2 内, 防冲过头滑出
    // 支撑块)。
    private static final double CENTER_JUMP_MAX = 0.2;
    private static final double CENTER_STEER_THRESHOLD = 0.25;

    // (2026-09-13 诊断) 20260913-084406 实锤: pillaring 分支每 tick 在跑 (18 条
    // 日志) 但 inputs 全 false + hSpeed=0 + 用户确认完全静止 —— 代码逻辑上该分支
    // 必然 hold 某键, 矛盾。加分支级诊断定案: step 每个出口 + steer 分解值 +
    // 实际按键状态, 500ms 节流。
    private static long _lastDiagMs = 0;

    private static void diag(String msg) {
        long now = System.currentTimeMillis();
        if (now - _lastDiagMs < 500) return;
        _lastDiagMs = now;
        adris.altoclef.Debug.logToFile("altoclef_diag.log", "[PillarDiag] " + msg);
    }

    private static String heldInputs(adris.altoclef.control.InputControls in) {
        return "fwd=" + in.isHeldDown(baritone.api.utils.input.Input.MOVE_FORWARD)
                + " back=" + in.isHeldDown(baritone.api.utils.input.Input.MOVE_BACK)
                + " left=" + in.isHeldDown(baritone.api.utils.input.Input.MOVE_LEFT)
                + " right=" + in.isHeldDown(baritone.api.utils.input.Input.MOVE_RIGHT)
                + " jump=" + in.isHeldDown(baritone.api.utils.input.Input.JUMP);
    }

    private static double offAxisSq(AltoClef mod, BlockPos support) {
        Vec3d c = Vec3d.ofCenter(support);
        double dx = c.x - mod.getPlayer().getX();
        double dz = c.z - mod.getPlayer().getZ();
        return Math.max(Math.abs(dx), Math.abs(dz));
    }

    /**
     * 把前后左右输入换算成"走向支撑块中心"的移动力。视线此时钉在脚下
     * (pitch≈90°), 不影响水平移动输入 —— 输入按当前 yaw 分解, 而 lookAt
     * (支撑块顶面中心) 的 yaw 本来就指向中心偏移方向, 二者自洽。
     */
    private static void steerToCenter(AltoClef mod, BlockPos support) {
        Vec3d c = Vec3d.ofCenter(support);
        double dx = c.x - mod.getPlayer().getX();
        double dz = c.z - mod.getPlayer().getZ();
        double yaw = Math.toRadians(mod.getPlayer().getYaw());
        double fwdX = -Math.sin(yaw), fwdZ = Math.cos(yaw);
        double rightX = -fwdZ, rightZ = fwdX;
        double fwd = dx * fwdX + dz * fwdZ;
        double right = dx * rightX + dz * rightZ;
        adris.altoclef.control.InputControls in = mod.getInputControls();
        if (fwd > CENTER_STEER_THRESHOLD) in.hold(Input.MOVE_FORWARD);
        else in.release(Input.MOVE_FORWARD);
        if (fwd < -CENTER_STEER_THRESHOLD) in.hold(Input.MOVE_BACK);
        else in.release(Input.MOVE_BACK);
        if (right > CENTER_STEER_THRESHOLD) in.hold(Input.MOVE_RIGHT);
        else in.release(Input.MOVE_RIGHT);
        if (right < -CENTER_STEER_THRESHOLD) in.hold(Input.MOVE_LEFT);
        else in.release(Input.MOVE_LEFT);
        // (2026-09-13 诊断) 记录分解值与实际按键落地状态。
        diag("steer dx=" + String.format("%.2f", dx) + " dz=" + String.format("%.2f", dz)
                + " yaw=" + String.format("%.0f", mod.getPlayer().getYaw())
                + " fwd=" + String.format("%.2f", fwd) + " right=" + String.format("%.2f", right)
                + " [" + heldInputs(in) + "]");
    }

    /**
     * 垫塔单步 (每 tick 调用一次)。
     *
     * @return true = 本 tick 做出了"向上"动作 (起跳或放置), false = 没法垫
     *         (缺材料 / 放置窗口不合法)。
     */
    public static boolean step(AltoClef mod, Item[] materials) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        BlockPos below = feet.down();
        // (2026-09-13 诊断) 入口现场: 玩家脚块/支撑块/两格判定/分支走向。
        diag("step feet=" + feet.toShortString() + " below=" + below.toShortString()
                + " solid=" + WorldHelper.isSolid(mod, below)
                + " solidDD=" + WorldHelper.isSolid(mod, below.down())
                + " offAxis=" + String.format("%.2f", offAxisSq(mod, below)));
        // (2026-09-12 v19 人类垫塔时机) 站立/上升途中就把视线钉在脚下支撑面
        // —— 窗口一开准星已命中, 右键立刻能点; 旧代码只在窗口开时才 lookAt,
        // 腾空窗口仅几 tick, 转头来不及, 白白错过 (20260912-195050 hand=dirt
        // 已装备、yRange1.25 正常跳、放置 0 次)。
        Vec3d supportTop = Vec3d.ofCenter(below).add(0, 0.5, 0);
        if (WorldHelper.isSolid(mod, below)) {
            // 站在实体上: 起跳 (视线已对准脚下块顶面, 跳起即入放置窗口)。
            LookHelper.lookAt(mod, supportTop);
            // (2026-09-12 v21) 居中再跳: 站偏时先走向方块中心, 不起跳 ——
            // 站偏起跳头蹭开口边沿, 垂直速度被顶掉 = 永远跳不起来。
            if (offAxisSq(mod, below) > CENTER_JUMP_MAX) {
                steerToCenter(mod, below);
                diag("GROUND steer (offAxis=" + String.format("%.2f", offAxisSq(mod, below)) + ")");
                return true;
            }
            mod.getInputControls().hold(Input.JUMP);
            diag("GROUND jump [" + heldInputs(mod.getInputControls()) + "]");
            return true;
        }
        // 腾空放置窗口: 脚下是空气、其下是实体 (跳过 1 格后的经典窗口)。
        // (2026-09-12 v18) 放置合法性检查不再走 WorldHelper.canPlace —— 它是
        // "寻路可达性"语义 (unreachable 黑名单 + avoidOcean biome 检查), 而垫塔
        // 窗口 feet.down() 贴脸物理可达, 不该过这道闸。20260912-192542 实锤:
        // pick-escape DIG_FOOT 挖穿脚下后 DestroyBlockTask 早退分支
        // requestBlockUnreachable 拉黑该格 → 垫塔 below 恰为该格 → canPlace 恒
        // false → 永远不进放置分支 → 只 hold JUMP = 原地狂跳 (y62↔63 交替 12+
        // 次零放置, 24s+)。硬保护谓词 (传送门/黑曜石) 保留。
        if (WorldHelper.isSolid(mod, below.down())
                && !WorldHelper.isInsidePlayer(mod, below)
                && !mod.getExtraBaritoneSettings().shouldAvoidPlacingAt(below)) {
            // 放置瞬间松跳, 避免连跳把放置窗口抢掉。
            mod.getInputControls().release(Input.JUMP);
            boolean ok = place(mod, below, materials);
            diag("PLACE try below=" + below.toShortString() + " ok=" + ok + " [" + heldInputs(mod.getInputControls()) + "]");
            return ok;
        }
        // 腾空但窗口还没到 (上升途中): 继续按住跳, 视线保持钉在支撑面。
        // (2026-09-12 v21) 上升途中同步向中心收 (空中控制微弱但聊胜于无),
        // 防止起跳后漂移在开口边沿蹭头。
        LookHelper.lookAt(mod, supportTop);
        if (offAxisSq(mod, below) > CENTER_JUMP_MAX) {
            steerToCenter(mod, below);
        }
        mod.getInputControls().hold(Input.JUMP);
        diag("AIRBORNE hold-jump [" + heldInputs(mod.getInputControls()) + "]");
        return true;
    }

    /**
     * 瞄准目标块顶面 → 准心落上后 interactBlock 放置。
     * (与 PlaceBlockNearbyTask.place 同款路径; 不用 baritone builder, 因为
     * builder 依赖"脚下接地"的放置语义, 在腾空窗口里不成立。)
     *
     * (2026-09-12 v19 命中条件修正 — "疯狂原地跳不放置"的直接死因):
     * 旧条件 `bhit.getBlockPos().equals(pos)` 恒 false —— below 是【空气格】,
     * MC 射线检测永远穿过空气, crosshairTarget 命中的是它下面的支撑块顶面
     * (pos.down()) 或更深处。右键因此一次都没执行过。
     * 人类垫塔的真实操作 = 低头看脚下支撑块顶面, 跳起瞬间右键 —— MC 自动把
     * 方块放到该面邻接的空气格 (= below)。故命中 pos 或 pos.down() 都算有效,
     * 直接对该 hit 交互 (face=UP 时放置位置自动 = pos)。
     */
    private static boolean place(AltoClef mod, BlockPos pos, Item[] materials) {
        if (!StorageHelper.isEquipped(mod, materials)) {
            if (!mod.getSlotHandler().forceEquipItem(materials)) {
                return false; // 没方块了 (调用方入口已检查库存, 这里是保险)
            }
        }
        Vec3d supportTop = Vec3d.ofCenter(pos.down()).add(0, 0.5, 0);
        LookHelper.lookAt(mod, supportTop);
        HitResult hit = MinecraftClient.getInstance().crosshairTarget;
        if (hit instanceof BlockHitResult bhit
                && (bhit.getBlockPos().equals(pos) || bhit.getBlockPos().equals(pos.down()))
                && MinecraftClient.getInstance().interactionManager != null) {
            ActionResult r = MinecraftClient.getInstance().interactionManager.interactBlock(
                    mod.getPlayer(), mod.getWorld(), Hand.MAIN_HAND, bhit);
            if (r == ActionResult.SUCCESS) {
                mod.getPlayer().swingHand(Hand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }
}
