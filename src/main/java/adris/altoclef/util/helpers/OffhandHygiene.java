package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PotionItem;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Objects;

/**
 * (2026-09-12 副手卫生检测) 把被意外塞进副手的非功能物品搬回主背包。
 *
 * 现场实锤 (20260912-081734 会话): 重启后 offHand=spruce_planks 从 08:18:23
 * 挂到 08:18:57 (用户手动移回才恢复)。期间 bot 卡在 sticks 合成链 33s:
 * 配方书填充要服务端从【背包】取 2 木板 → 木板在副手、背包 planks=0 →
 * 填充失败 → "Waiting for recipe book click..." 死等。副手木板同时对
 * getItemSlotsInInventoryReal(false) 不可见 (不含副手), 材料判定也看不见。
 *
 * 用户原则: 材料够但无法合成, 就该有机制把副手杂物放回去。
 *
 * 设计: 副手只允许放功能性物品 (盾/食物/药水/不死图腾 — FoodChain 副手食物、
 * MobDefenseChain 副手盾、ItemHelper 副手药水的既有先例)。全局搜证确认 bot
 * 从不【故意】往副手放材料 (PillarJumpHelper/PlaceBlockTask/BeatMinecraft2Task
 * 的 offHand 引用全部是只读计数) → 白名单外的即杂物。
 *
 * 动作: 两步 forced PICKUP (副手→光标→背包格), 背包满则不搬不丢 (保持原状,
 * 交还主任务流处理)。落格失败 (哑光) 立刻原路退回副手, 保证检测本身不污染
 * 光标。5s static 冷却 (项目规矩: 冷却放触发条件层)。
 */
public final class OffhandHygiene {

    private static long _lastCheckMs = 0;
    private static final long CHECK_INTERVAL_MS = 5000;

    private OffhandHygiene() {}

    public static void tick(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastCheckMs < CHECK_INTERVAL_MS) return;
        _lastCheckMs = now;
        try {
            if (mod.getPlayer() == null || mod.getPlayer().getInventory() == null) return;
            // OFFHAND_SLOT(45) 只在玩家自带界面里才是副手; 容器界面开着时 45 是
            // 别的槽位, 乱点会把杂物塞进容器/背包错格。
            if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) return;
            // 进食中不碰副手 (FoodChain 的食物搬运序列在进行)
            if (mod.getFoodChain().isTryingToEat()) return;
            ItemStack offhand = mod.getPlayer().getOffHandStack();
            if (offhand == null || offhand.isEmpty()) return;
            if (isAllowedOffhandItem(offhand.getItem())) return;
            // 光标被占不动: 此时任何 PICKUP 都会打乱其它槽位逻辑的在途状态
            if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) return;

            // 真实主背包找落点: 优先可合并堆, 否则空格。都没有 (背包满) 就不搬
            // 不丢 — 丢弃是破坏性动作, 不该由卫生检测自作主张。
            Slot_ target = findLandingSlot(mod, offhand.getItem());
            if (target == null) return;

            int offhandCountBefore = offhand.getCount();
            // 两步 forced PICKUP: 副手→光标→落点 (forced 跳过节流, 同 tick 完成,
            // 避免中间态被其它槽位操作撞上 — FoodChain parkShield 同款先例)
            mod.getSlotHandler().clickSlotForced(
                    adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT, 0, SlotActionType.PICKUP);
            mod.getSlotHandler().clickSlotForced(
                    target.slot, 0, SlotActionType.PICKUP);

            // 校验: 光标应已清空。第二步哑光 (服务端拒绝) 时原路退回副手,
            // 保证"检测本身不改变总量、不弄脏光标"。
            ItemStack cursorAfter = StorageHelper.getItemStackInCursorSlot();
            if (!cursorAfter.isEmpty()) {
                mod.getSlotHandler().clickSlotForced(
                        adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT, 0, SlotActionType.PICKUP);
                Debug.logMessage("[OFFHAND-HYGIENE] 落格失败, 已退回副手: " + cursorAfter.getItem());
                return;
            }
            Debug.logMessage("[OFFHAND-HYGIENE] 副手杂物已搬回背包: "
                    + offhand.getItem() + " x" + offhandCountBefore);
        } catch (Exception ignored) {
            // 卫生检测绝不能抛异常打断主 tick
        }
    }

    /** 副手合法持有者: 盾 (MobDefenseChain)、食物 (FoodChain)、药水 (火抗)、图腾。 */
    private static boolean isAllowedOffhandItem(Item item) {
        if (item == Items.SHIELD) return true;
        if (item == Items.TOTEM_OF_UNDYING) return true;
        if (item instanceof PotionItem) return true;
        // vanilla 食物判定 (FoodChain 副手放食物进食的既有设计)
        return item.isFood();
    }

    /**
     * 真实背包扫描找落点 (2026-08-24 先例: 缓存可能陈旧必须以真实背包为准)。
     * 注意 getFromCurrentScreenInventory 收【背包索引】(0-8 快捷栏 / 9-35 主背包),
     * 内部自行映射 PlayerScreenHandler windowSlot — refreshInventory 同口径。
     */
    private static Slot_ findLandingSlot(AltoClef mod, Item item) {
        PlayerInventory inv = mod.getPlayer().getInventory();
        Slot_ mergeTarget = null;
        Slot_ emptyTarget = null;
        for (int i = 0; i < inv.main.size(); i++) {
            ItemStack stack = inv.main.get(i);
            adris.altoclef.util.slots.Slot slot =
                    adris.altoclef.util.slots.Slot.getFromCurrentScreenInventory(i);
            if (stack == null || stack.isEmpty()) {
                if (emptyTarget == null) emptyTarget = new Slot_(slot);
                continue;
            }
            if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                mergeTarget = new Slot_(slot);
                break; // 第一个可合并堆最优
            }
        }
        return mergeTarget != null ? mergeTarget : emptyTarget;
    }

    /** 小包装, 免去整段泛型/导出名混写。 */
    private static final class Slot_ {
        final adris.altoclef.util.slots.Slot slot;
        Slot_(adris.altoclef.util.slots.Slot slot) {
            this.slot = Objects.requireNonNull(slot);
        }
    }
}
