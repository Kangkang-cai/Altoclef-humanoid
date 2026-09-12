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
        if (WorldHelper.isSolid(mod, below)) {
            // 站在实体上: 起跳 (对着脚下的块顶面看, 跳起来那一刻就进入放置窗口)。
            mod.getInputControls().hold(Input.JUMP);
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
            return place(mod, below, materials);
        }
        // 腾空但窗口还没到 (上升途中): 继续按住跳。
        mod.getInputControls().hold(Input.JUMP);
        return true;
    }

    /**
     * 瞄准目标块顶面 → 准心落上后 interactBlock 放置。
     * (与 PlaceBlockNearbyTask.place 同款路径; 不用 baritone builder, 因为
     * builder 依赖"脚下接地"的放置语义, 在腾空窗口里不成立。)
     */
    private static boolean place(AltoClef mod, BlockPos pos, Item[] materials) {
        if (!StorageHelper.isEquipped(mod, materials)) {
            if (!mod.getSlotHandler().forceEquipItem(materials)) {
                return false; // 没方块了 (调用方入口已检查库存, 这里是保险)
            }
        }
        Vec3d topCenter = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        LookHelper.lookAt(mod, topCenter);
        HitResult hit = MinecraftClient.getInstance().crosshairTarget;
        if (hit instanceof BlockHitResult bhit
                && bhit.getBlockPos().equals(pos)
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
