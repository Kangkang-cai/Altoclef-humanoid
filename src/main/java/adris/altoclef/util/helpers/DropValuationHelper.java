package adris.altoclef.util.helpers;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * (2026-09-17 空墓过滤) 物品"是否珍贵"的判定工具, 供死亡墓碑评估使用。
 *
 * (2026-09-23 方案变更) 旧实现由 DeathMenuChain 在死亡屏上扫描掉落物实体
 * (scanNear/assess) + 普通物资数量阈值。该方案从根上不成立: 死亡屏上服务端
 * 停止向客户端发送更新, 死亡动画结束后约 1s 客户端侧区块/实体被卸载
 * (Mojang MC-169113/MC-254610, Works As Intended), 掉落物永远扫不到 ——
 * 实测跨 3 个会话 6 次死亡扫描结果全为 0。现在由 PlayerDamageMixin 在每次
 * 伤害 HEAD (背包尚未掉落) 时用本判定扫描背包, 死亡瞬间取样。用户拍板:
 * 只看珍贵物品, 不再统计普通物资数量。
 *
 * 判据: 出现【装备/稀有物】任意一件 → 值得回 (见 {@link #isHighValue})。
 *
 * 为什么枚举"珍贵"而不是枚举"垃圾": 无法预判的白名单会漏判, 把未知物品
 * 默认当普通 → 最坏"少跑一趟"; 反之若默认当贵重 → "多跑一趟"。当前方向与
 * 用户诉求一致(别为垃圾回头)。
 */
public final class DropValuationHelper {

    private DropValuationHelper() {
    }

    /**
     * 【珍贵】物品集合 —— 出现任意一件就值得跑一趟。
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
     * 单个物品是否"珍贵"(任一即值得回去捡)。
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
}
