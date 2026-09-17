package adris.altoclef.util.helpers;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * (2026-09-17 空墓过滤) 死亡掉落物"值不值得回去捡"的估值工具。
 *
 * 背景(用户 2026-09-17 现场): 死亡时背包本就空空(死因 outOfWorld), 墓碑附近
 * 一个掉落物都没有, 但链层照常把它当"值得回收的墓" —— 先为埋在地下的墓空耗
 * 74 秒造石镐, 再跑到墓碑处找不到东西, 然后在墓碑旁原地打转 wander。用户定案:
 * "对于没有大量物资的情况就不要拾取了, 比如食物, 矿物"。
 *
 * 判据(用户 2026-09-17 拍板: 装备优先 + 普通物量阈值):
 *   1. 掉落物里出现【装备/稀有物】任意一件 → 值得回(见 {@link #isHighValue});
 *   2. 否则按【普通物资总件数】比较阈值, 默认 16(配置 deathRecoveryMinOrdinaryDropCount)。
 *
 * 为什么这里不枚举"普通物资"白名单而是枚举"高价值": 无法预判的白名单会漏判,
 * 把未知物品默认当普通 → 走数量阈值, 最坏情况是"少跑一趟"; 反之若把未知物品
 * 默认当贵重 → "多跑一趟"。当前方向与用户诉求一致(别为垃圾回头)。
 *
 * 用途: 只在【死亡瞬间, 玩家仍停在死亡点且区块已加载】时取样
 * (DeathMenuChain 记墓碑之前), 复活后墓地区块可能未加载, 扫不到实体, 那时
 * 取样会把"真掉落物"误判成"没有"。绝不用于通用透视/搜寻。
 */
public final class DropValuationHelper {

    private DropValuationHelper() {
    }

    /**
     * 【高价值】物品集合 —— 出现任意一件就值得跑一趟。
     * 木/石质工具与皮革甲【不在】其中(随手可造, 不值一趟回头), 见
     * {@link #isHighValue} 的 LOW_TIER_EQUIPMENT 短路。
     */
    private static final Set<Item> HIGH_VALUE_ITEMS = new HashSet<>(Arrays.asList(
            // 钻石与下界合金
            Items.DIAMOND,
            Items.DIAMOND_BLOCK,
            Items.DIAMOND_ORE,
            Items.DEEPSLATE_DIAMOND_ORE,
            Items.DIAMOND_HORSE_ARMOR,
            Items.NETHERITE_INGOT,
            Items.NETHERITE_SCRAP,
            Items.NETHERITE_BLOCK,
            Items.ANCIENT_DEBRIS,
            // 绿宝石 / 青金石
            Items.EMERALD,
            Items.EMERALD_BLOCK,
            Items.EMERALD_ORE,
            Items.DEEPSLATE_EMERALD_ORE,
            Items.LAPIS_LAZULI,
            Items.LAPIS_BLOCK,
            Items.LAPIS_ORE,
            Items.DEEPSLATE_LAPIS_ORE,
            // 下界/末地素材
            Items.BLAZE_ROD,
            Items.BLAZE_POWDER,
            Items.ENDER_PEARL,
            Items.ENDER_EYE,
            Items.ENDER_CHEST,
            Items.END_CRYSTAL,
            // 金(猪灵交易货币 / 附魔金苹果前置)
            Items.GOLD_INGOT,
            Items.GOLD_BLOCK,
            Items.RAW_GOLD,
            Items.GOLD_ORE,
            Items.DEEPSLATE_GOLD_ORE,
            Items.NETHER_GOLD_ORE,
            Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE,
            // 稀有消耗品 / 工具类非装备物
            Items.EXPERIENCE_BOTTLE,
            Items.BUCKET,
            Items.WATER_BUCKET,
            Items.LAVA_BUCKET,
            Items.MILK_BUCKET,
            Items.POWDER_SNOW_BUCKET,
            Items.BOW,
            Items.CROSSBOW,
            Items.TRIDENT,
            Items.SHIELD,
            Items.FISHING_ROD,
            Items.SHEARS,
            Items.POTION,
            Items.SPLASH_POTION,
            Items.LINGERING_POTION,
            // 不可再生 / 稀有结构物
            Items.ELYTRA,
            Items.TOTEM_OF_UNDYING,
            Items.SADDLE,
            Items.NAME_TAG,
            Items.OBSIDIAN,
            Items.CRYING_OBSIDIAN,
            Items.RESPAWN_ANCHOR,
            Items.ANVIL,
            Items.CHIPPED_ANVIL,
            Items.DAMAGED_ANVIL,
            Items.ENCHANTING_TABLE,
            Items.SHULKER_BOX
    ));

    /**
     * 单个物品是否"高价值"(任一即值得回去捡)。
     * 顺序要点: 先判附魔, 再短路掉低档装备(木/石工具、皮革甲), 最后判类型/白名单。
     */
    public static boolean isHighValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        // 附魔过的任何东西都值得捡(铲子附魔也花经验)。
        if (stack.hasEnchantments()) return true;
        Item item = stack.getItem();
        // 低档装备短路: 木/石质工具与皮革甲随手可造, 不值得为它回头。
        if (isOneOf(item, ItemHelper.WOODEN_TOOLS)
                || isOneOf(item, ItemHelper.STONE_TOOLS)
                || isOneOf(item, ItemHelper.LEATHER_ARMORS)) {
            return false;
        }
        // 装备类: 工具(含剑, SwordItem 继承 ToolItem)、护甲。
        if (item instanceof ToolItem || item instanceof SwordItem || item instanceof ArmorItem) {
            return true;
        }
        if (HIGH_VALUE_ITEMS.contains(item)) return true;
        // 任意潜影盒(含染色款)。
        return isOneOf(item, ItemHelper.SHULKER_BOXES);
    }

    private static boolean isOneOf(Item item, Item[] pool) {
        if (item == null || pool == null) return false;
        for (Item candidate : pool) {
            if (candidate == item) return true;
        }
        return false;
    }

    /**
     * 扫描已加载实体中, 以 {@code centre} 为中心 {@code radius} 格球内的掉落物。
     * 只取 ItemEntity —— 不含其它实体, 也不做任何方块透视。
     */
    public static List<ItemEntity> scanNear(BlockPos centre, int radius) {
        List<ItemEntity> result = new ArrayList<>();
        if (centre == null || MinecraftClient.getInstance().world == null) return result;
        Vec3d c = Vec3d.ofCenter(centre);
        double rSq = (double) radius * radius;
        for (Entity entity : MinecraftClient.getInstance().world.getEntities()) {
            if (entity instanceof ItemEntity item && item.isAlive()
                    && item.squaredDistanceTo(c) <= rSq) {
                result.add(item);
            }
        }
        return result;
    }

    /** 对一组掉落物做估值: 堆数 / 总件数 / 是否含高价值物。 */
    public static Assessment assess(Collection<ItemEntity> drops) {
        int stacks = 0;
        int items = 0;
        boolean highValue = false;
        String highValueSample = null;
        if (drops != null) {
            for (ItemEntity drop : drops) {
                if (drop == null || !drop.isAlive()) continue;
                ItemStack stack = drop.getStack();
                if (stack == null || stack.isEmpty()) continue;
                stacks++;
                items += stack.getCount();
                if (!highValue && isHighValue(stack)) {
                    highValue = true;
                    highValueSample = stack.getItem().getTranslationKey() + " x" + stack.getCount();
                }
            }
        }
        return new Assessment(stacks, items, highValue, highValueSample);
    }

    /** 掉落物估值结果。 */
    public static final class Assessment {
        /** 掉落物堆数(ItemEntity 个数)。 */
        public final int stackCount;
        /** 掉落物总件数(所有堆的 count 之和)。 */
        public final int itemCount;
        /** 是否含任一高价值物品。 */
        public final boolean hasHighValue;
        /** 命中的高价值物品样本, 供日志取证; 无则 null。 */
        public final String highValueSample;

        Assessment(int stackCount, int itemCount, boolean hasHighValue, String highValueSample) {
            this.stackCount = stackCount;
            this.itemCount = itemCount;
            this.hasHighValue = hasHighValue;
            this.highValueSample = highValueSample;
        }

        /**
         * 是否值得回去捡。
         *
         * @param minOrdinaryItemCount 不含高价值物时, 普通物资需要达到的总件数;
         *                             &le;0 表示阈值关闭(任何掉落物都回)。
         */
        public boolean worthRecovery(int minOrdinaryItemCount) {
            if (stackCount <= 0) return false;
            if (hasHighValue) return true;
            if (minOrdinaryItemCount <= 0) return true;
            return itemCount >= minOrdinaryItemCount;
        }

        /** 一行日志摘要: 堆数/件数/高价值命中。 */
        public String describe() {
            return "stacks=" + stackCount + " items=" + itemCount
                    + " highValue=" + (hasHighValue ? "Y" + (highValueSample != null ? "(" + highValueSample + ")" : "") : "N");
        }
    }
}
