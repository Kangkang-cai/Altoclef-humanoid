package adris.altoclef.bridge;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.item.Items;
import net.minecraft.util.registry.Registry;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * bridge 包内物品中文名解析（确定性：同物品永远同名，供 food/keyItems/equipment
 * 三个列表字段做"同集合同字符串"，对端变化键才稳定）。
 *
 * <p>三层解析：
 * <ol>
 *   <li>手写表：食物 + 稀有/有用级物品（keyItems 涉及面）；</li>
 *   <li>程序化：工具/护甲 = 材质前缀 + 件型/工具型后缀（石镐/铁胸甲），覆盖全部材质组合；</li>
 *   <li>兜底：注册表 id 路径尾段（如 {@code rabbit_stew}），保证永不返回 null。</li>
 * </ol>
 *
 * <p>刻意不用 I18n.translate：那跟客户端语言设置走，跨机器不稳定，破坏语义稳定性。
 * 线程安全：静态表只读。
 */
final class ItemNames {

    private ItemNames() {}

    /** 手写表：食物 + keyItems 涉及的稀有/有用级物品。 */
    private static final Map<Item, String> ZH = new IdentityHashMap<>();

    static {
        // ---- 食物（food 字段用；collected 白名单也引用这些中文名）----
        put(Items.BREAD, "面包");
        put(Items.APPLE, "苹果");
        put(Items.GOLDEN_APPLE, "金苹果");
        put(Items.ENCHANTED_GOLDEN_APPLE, "附魔金苹果");
        put(Items.GOLDEN_CARROT, "金胡萝卜");
        put(Items.BEEF, "生牛肉");
        put(Items.COOKED_BEEF, "牛排");
        put(Items.PORKCHOP, "生猪排");
        put(Items.COOKED_PORKCHOP, "熟猪排");
        put(Items.CHICKEN, "生鸡肉");
        put(Items.COOKED_CHICKEN, "熟鸡肉");
        put(Items.MUTTON, "生羊肉");
        put(Items.COOKED_MUTTON, "熟羊肉");
        put(Items.COD, "生鳕鱼");
        put(Items.COOKED_COD, "熟鳕鱼");
        put(Items.SALMON, "生鲑鱼");
        put(Items.COOKED_SALMON, "熟鲑鱼");
        put(Items.RABBIT, "生兔肉");
        put(Items.COOKED_RABBIT, "熟兔肉");
        put(Items.RABBIT_STEW, "兔肉煲");
        put(Items.POTATO, "马铃薯");
        put(Items.BAKED_POTATO, "烤马铃薯");
        put(Items.POISONOUS_POTATO, "毒马铃薯");
        put(Items.CARROT, "胡萝卜");
        put(Items.BEETROOT, "甜菜根");
        put(Items.BEETROOT_SOUP, "甜菜汤");
        put(Items.MUSHROOM_STEW, "蘑菇煲");
        put(Items.SUSPICIOUS_STEW, "迷之炖菜");
        put(Items.MELON_SLICE, "西瓜片");
        put(Items.PUMPKIN_PIE, "南瓜派");
        put(Items.COOKIE, "曲奇");
        put(Items.SWEET_BERRIES, "甜浆果");
        put(Items.GLOW_BERRIES, "发光浆果");
        put(Items.ROTTEN_FLESH, "腐肉");
        put(Items.SPIDER_EYE, "蜘蛛眼");
        put(Items.DRIED_KELP, "干海带");
        put(Items.HONEY_BOTTLE, "蜂蜜瓶");
        // ---- 矿物/锭/粗矿 ----
        put(Items.IRON_INGOT, "铁锭");
        put(Items.GOLD_INGOT, "金锭");
        put(Items.COPPER_INGOT, "铜锭");
        put(Items.NETHERITE_INGOT, "下界合金锭");
        put(Items.NETHERITE_SCRAP, "下界合金碎片");
        put(Items.RAW_IRON, "粗铁");
        put(Items.RAW_GOLD, "粗金");
        put(Items.RAW_COPPER, "粗铜");
        put(Items.COAL, "煤炭");
        put(Items.DIAMOND, "钻石");
        put(Items.EMERALD, "绿宝石");
        put(Items.LAPIS_LAZULI, "青金石");
        put(Items.REDSTONE, "红石粉");
        put(Items.QUARTZ, "下界石英");
        put(Items.AMETHYST_SHARD, "紫水晶碎片");
        // ---- 稀有物/下界/末地 ----
        put(Items.ANCIENT_DEBRIS, "远古残骸");
        put(Items.BLAZE_ROD, "烈焰棒");
        put(Items.BLAZE_POWDER, "烈焰粉");
        put(Items.ENDER_PEARL, "末影珍珠");
        put(Items.ENDER_EYE, "末影之眼");
        put(Items.GHAST_TEAR, "恶魂之泪");
        put(Items.WITHER_SKELETON_SKULL, "凋灵骷髅头颅");
        put(Items.NETHER_STAR, "下界之星");
        put(Items.TOTEM_OF_UNDYING, "不死图腾");
        put(Items.ELYTRA, "鞘翅");
        put(Items.EXPERIENCE_BOTTLE, "经验瓶");
        put(Items.SHULKER_BOX, "潜影盒");
        put(Items.ENCHANTING_TABLE, "附魔台");
        put(Items.ANVIL, "铁砧");
        put(Items.SADDLE, "鞍");
        put(Items.NAME_TAG, "命名牌");
        put(Items.OBSIDIAN, "黑曜石");
        put(Items.CRYING_OBSIDIAN, "哭泣的黑曜石");
        put(Items.RESPAWN_ANCHOR, "重生锚");
        // ---- 武器/装备类非工具 ----
        put(Items.BOW, "弓");
        put(Items.CROSSBOW, "弩");
        put(Items.TRIDENT, "三叉戟");
        put(Items.SHIELD, "盾牌");
        put(Items.ARROW, "箭");
        put(Items.SPECTRAL_ARROW, "光灵箭");
        put(Items.TIPPED_ARROW, "药水箭");
        put(Items.POTION, "药水");
        put(Items.SPLASH_POTION, "喷溅药水");
        put(Items.LINGERING_POTION, "滞留药水");
        put(Items.FIREWORK_ROCKET, "烟花火箭");
        put(Items.FLINT_AND_STEEL, "打火石");
        put(Items.FISHING_ROD, "钓鱼竿");
        put(Items.SHEARS, "剪刀");
        put(Items.BUCKET, "铁桶");
        put(Items.WATER_BUCKET, "水桶");
        put(Items.LAVA_BUCKET, "岩浆桶");
        put(Items.POWDER_SNOW_BUCKET, "细雪桶");
        put(Items.MILK_BUCKET, "牛奶桶");
        put(Items.COMPASS, "指南针");
        put(Items.CLOCK, "时钟");
        put(Items.SPYGLASS, "望远镜");
        put(Items.ITEM_FRAME, "物品展示框");
        put(Items.LEAD, "拴绳");
        put(Items.BOOK, "书");
        put(Items.ENCHANTED_BOOK, "附魔书");
        put(Items.WRITABLE_BOOK, "书与笔");
        put(Items.MAP, "地图");
    }

    private static void put(Item item, String zh) {
        ZH.put(item, zh);
    }

    /** 工具型中文名（ToolItem 判型，剑含在 ToolItem 体系内）。 */
    private static String toolTypeZh(Item item) {
        if (item instanceof PickaxeItem) return "镐";
        if (item instanceof AxeItem) return "斧";
        if (item instanceof ShovelItem) return "铲";
        if (item instanceof HoeItem) return "锄";
        if (item instanceof SwordItem) return "剑";
        return null;
    }

    /** 工具材质中文名（yarn ToolMaterials 恒等比对）。 */
    private static String toolMaterialZh(ToolMaterial mat) {
        if (mat == net.minecraft.item.ToolMaterials.WOOD) return "木";
        if (mat == net.minecraft.item.ToolMaterials.STONE) return "石";
        if (mat == net.minecraft.item.ToolMaterials.IRON) return "铁";
        if (mat == net.minecraft.item.ToolMaterials.GOLD) return "金";
        if (mat == net.minecraft.item.ToolMaterials.DIAMOND) return "钻石";
        if (mat == net.minecraft.item.ToolMaterials.NETHERITE) return "下界合金";
        return null;
    }

    /** 护甲件型中文名（按槽位）。 */
    private static String armorPieceZh(ArmorItem a) {
        switch (a.getSlotType()) {
            case HEAD: return "头盔";
            case CHEST: return "胸甲";
            case LEGS: return "护腿";
            case FEET: return "靴子";
            default: return null;
        }
    }

    /** 护甲材质中文名（yarn ArmorMaterials 恒等比对）。 */
    private static String armorMaterialZh(net.minecraft.item.ArmorMaterial mat) {
        if (mat == net.minecraft.item.ArmorMaterials.LEATHER) return "皮革";
        if (mat == net.minecraft.item.ArmorMaterials.CHAIN) return "锁链";
        if (mat == net.minecraft.item.ArmorMaterials.IRON) return "铁";
        if (mat == net.minecraft.item.ArmorMaterials.GOLD) return "金";
        if (mat == net.minecraft.item.ArmorMaterials.DIAMOND) return "钻石";
        if (mat == net.minecraft.item.ArmorMaterials.NETHERITE) return "下界合金";
        if (mat == net.minecraft.item.ArmorMaterials.TURTLE) return "海龟";
        return null;
    }

    /**
     * 解析物品中文名。工具/护甲程序化拼名，其余查手写表，兜底注册表 id 路径尾段。
     * 永不返回 null/空串。
     */
    static String name(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        Item item = stack.getItem();
        // 1) 手写表优先（金苹果等既是食物又是稀有物，只有一份名）
        String zh = ZH.get(item);
        if (zh != null) return zh;
        // 2) 工具程序化：材质+工具型
        if (item instanceof ToolItem) {
            ToolItem t = (ToolItem) item;
            String type = toolTypeZh(item);
            String mat = toolMaterialZh(t.getMaterial());
            if (type != null && mat != null) return mat + type;
        }
        // 3) 护甲程序化：材质+件型
        if (item instanceof ArmorItem) {
            ArmorItem a = (ArmorItem) item;
            String piece = armorPieceZh(a);
            String mat = armorMaterialZh(a.getMaterial());
            if (piece != null && mat != null) return mat + piece;
        }
        // 4) 兜底：注册表 id 路径尾段
        return registryFallback(item);
    }

    private static String registryFallback(Item item) {
        try {
            String id = Registry.ITEM.getId(item).getPath();
            return id == null || id.isEmpty() ? item.toString() : id;
        } catch (Throwable t) {
            return item.toString();
        }
    }
}
