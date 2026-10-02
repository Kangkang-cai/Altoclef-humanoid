package adris.altoclef.bridge;

import adris.altoclef.util.helpers.DropValuationHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /status 扩展字段的背包摘要：hunger/level/equipment/food/foodCount/keyItems。
 *
 * <p>语义稳定性铁律（对端 L1 注入节流靠这些字段算变化键，同状态必须同字符串）：
 * <ul>
 *   <li><b>固定排序</b>：所有"集合→字符串"一律按注册表 id 字典序排 —— 背包槽位序
 *       会随整理/腾位变动（同集换序=伪变化=节流失效），绝不按槽位序拼。</li>
 *   <li><b>equipment 只计护甲四件</b>（1002 15:4x 对端要求，字段语义 = 护甲签名）：
 *       主手工具/剑一律**不进**本字段，裸装返回空串。bot 日常手持圆石垫脚/拿方块/
 *       拿食物随时在变，混入会污染对端按"护甲段"算的变化键。</li>
 *   <li><b>keyItems 独立口径</b>：{@link DropValuationHelper#isHighValue} 是
 *       "死亡值不值得回头"的判据（服务墓碑评估），语义不同不污染；这里
 *       isHighValue + 补充集（铁锭/粗铁/煤炭——铁系是日常进度核心但不在
 *       HIGH_VALUE_ITEMS 里），种类上限 {@value #KEY_MAX_KINDS}。</li>
 *   <li>food/foodCount 含全部 {@code Item.isFood()} 项（含腐肉——快照是如实
 *       摘要）；collected 事件白名单是另一套更窄的口径（EventCollector 管）。</li>
 * </ul>
 *
 * <p>线程模型：仅在客户端线程由 {@link BotSnapshot#snapshot} 构造时调用。
 */
final class InventoryDigest {

    /** keyItems 种类上限，防爆发性长字符串。 */
    private static final int KEY_MAX_KINDS = 8;

    /** isHighValue 之外的 keyItems 补充集：日常进度核心矿物/锭。 */
    private static final Set<Item> KEY_EXTRA = new HashSet<>(List.of(
            Items.IRON_INGOT, Items.RAW_IRON, Items.COAL
    ));

    final int hunger;
    final int level;
    /** 纯护甲签名，如 "铁套"；裸装给空串（1002 对端要求：不含主手）。 */
    final String equipment;
    /**
     * 主手此刻拿着的物品中文名（1002 16:5x 对端新增）。语义与 {@link #keyItems} 不同 ——
     * "镐在背包" 与 "手里举着镐" 是两种状态；空手 → 空串。**任意物品**都算（含方块/食物，
     * 这里是"如实摘要"不是变化键，抖动由对端渲染层承担）。
     */
    final String holding;
    /** 背包食物种类概述，如 "面包x5、生牛肉x3"；无食物空串。 */
    final String food;
    /** 背包食物总数（所有 isFood 项计数和）。 */
    final int foodCount;
    /** 稀有+有用级持有，如 "钻石x2、铁锭x5"；无则空串。 */
    final String keyItems;

    private InventoryDigest(int hunger, int level, String equipment, String holding,
                            String food, int foodCount, String keyItems) {
        this.hunger = hunger;
        this.level = level;
        this.equipment = equipment;
        this.holding = holding;
        this.food = food;
        this.foodCount = foodCount;
        this.keyItems = keyItems;
    }

    /** 空摘要（离线占位用）。 */
    static InventoryDigest empty() {
        return new InventoryDigest(0, 0, "", "", "", 0, "");
    }

    /**
     * 采样入口（带读失败防抖）：冻结窗口内沿用上一个有效摘要的背包派生字段，
     * hunger/level 是玩家状态照常实时。仅客户端线程调用（静态缓存单线程读写）。
     */
    static InventoryDigest sample(ClientPlayerEntity p, InventoryGuard guard) {
        long now = System.currentTimeMillis();
        InventoryDigest d = of(p);
        if (guard != null && guard.isFrozen(now)) {
            if (_lastGood != null) {
                return withPrevInventory(d.hunger, d.level, _lastGood);
            }
            return d; // 首次尚无有效摘要，暂时直出（瞬时态，5s 内自愈）
        }
        _lastGood = d;
        return d;
    }

    /** hunger/level 用新值，背包派生四字段沿用上一个有效摘要。 */
    static InventoryDigest withPrevInventory(int hunger, int level, InventoryDigest prev) {
        return new InventoryDigest(hunger, level,
                prev.equipment, prev.holding, prev.food, prev.foodCount, prev.keyItems);
    }

    /** 上一个有效摘要（冻结窗口时快照沿用）。仅客户端线程读写。 */
    private static InventoryDigest _lastGood = null;

    /** 在客户端线程采样。仅由 BotSnapshot.snapshot 调用。 */
    static InventoryDigest of(ClientPlayerEntity p) {
        int hunger = p.getHungerManager().getFoodLevel();
        int level = p.experienceLevel;
        String equipment = equipmentSignature(p);
        String holding = holdingName(p);
        FoodAgg foodAgg = foodAgg(p);
        String keyItems = keyItemsAgg(p);
        return new InventoryDigest(hunger, level, equipment, holding,
                foodAgg.text, foodAgg.total, keyItems);
    }

    // ===================== equipment =====================

    /** 护甲四件签名（head→feet 固定序；1002 对端要求：不含主手）。无护甲 → 空串。 */
    private static String equipmentSignature(ClientPlayerEntity p) {
        // 护甲：head→chest→legs→feet 固定顺序展示
        List<String> pieces = new ArrayList<>(4);
        List<ArmorItem> armorItems = new ArrayList<>(4);
        for (ItemStack s : p.getArmorItems()) {
            if (s == null || s.isEmpty()) continue;
            if (s.getItem() instanceof ArmorItem) {
                armorItems.add((ArmorItem) s.getItem());
            }
        }
        // getArmorItems 是 feet→head 序，反转为 head→feet
        java.util.Collections.reverse(armorItems);
        String armorMat = null;
        boolean allSameMat = armorItems.size() == 4;
        for (ArmorItem a : armorItems) {
            String zh = ItemNames.name(a.getDefaultStack());
            pieces.add(zh);
            String mat = matPrefix(zh, pieceZh(a));
            if (mat == null) {
                allSameMat = false;
            } else if (armorMat == null) {
                armorMat = mat;
            } else if (!armorMat.equals(mat)) {
                allSameMat = false;
            }
        }
        String armorStr;
        if (armorItems.isEmpty()) {
            armorStr = "";
        } else if (allSameMat && armorMat != null) {
            armorStr = armorMat + "套";
        } else {
            armorStr = String.join("、", pieces);
        }

        // (1002 15:4x 对端要求) equipment 语义收窄为**纯护甲签名** —— 主手工具不再进本字段。
        // 旧行为：裸装手持木镐会返回 "木镐"，对端按"护甲段"算变化键会被工具切换污染。
        // 若要保留"手持什么工具"，应另开独立字段（如 holding），不混进 equipment。
        return armorStr;
    }

    /**
     * 主手物品中文名（1002 16:5x 对端新增字段 {@code holding}）。空手 → 空串。
     * 与 {@code equipment}（护甲）无关；任意物品都算 —— 对端要的就是"此刻手里举着什么"。
     */
    private static String holdingName(ClientPlayerEntity p) {
        try {
            ItemStack s = p.getMainHandStack();
            return (s == null || s.isEmpty()) ? "" : ItemNames.name(s);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从"铁胸甲"这类拼名里剥出材质前缀（剥掉件型尾巴）。 */
    private static String matPrefix(String fullName, String pieceSuffix) {
        if (pieceSuffix == null || fullName == null) return null;
        if (fullName.length() > pieceSuffix.length()
                && fullName.endsWith(pieceSuffix)) {
            return fullName.substring(0, fullName.length() - pieceSuffix.length());
        }
        return null;
    }

    private static String pieceZh(ArmorItem a) {
        switch (a.getSlotType()) {
            case HEAD: return "头盔";
            case CHEST: return "胸甲";
            case LEGS: return "护腿";
            case FEET: return "靴子";
            default: return "";
        }
    }

    // ===================== food / foodCount =====================

    private static final class FoodAgg {
        String text = "";
        int total = 0;
    }

    /** 主背包 + 副手的全部 isFood 项，按注册表 id 序拼 "名x数"。 */
    private static FoodAgg foodAgg(ClientPlayerEntity p) {
        Map<Item, Integer> counts = new IdentityHashMap<>();
        collectIf(p.getInventory().main, counts, s -> s.getItem().isFood());
        collectIf(p.getInventory().offHand, counts, s -> s.getItem().isFood());
        FoodAgg agg = new FoodAgg();
        if (counts.isEmpty()) return agg;
        List<Map.Entry<Item, Integer>> sorted = sortById(counts);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Integer> e : sorted) {
            agg.total += e.getValue();
            if (sb.length() > 0) sb.append('、');
            sb.append(ItemNames.name(e.getKey().getDefaultStack())).append('x').append(e.getValue());
        }
        agg.text = sb.toString();
        return agg;
    }

    // ===================== keyItems =====================

    /** 稀有+有用级持有：isHighValue ∪ KEY_EXTRA，注册表 id 序，种类上限 8。 */
    private static String keyItemsAgg(ClientPlayerEntity p) {
        Map<Item, Integer> counts = new IdentityHashMap<>();
        collectIf(p.getInventory().main, counts, InventoryDigest::isKeyItem);
        collectIf(p.getInventory().armor, counts, InventoryDigest::isKeyItem);
        collectIf(p.getInventory().offHand, counts, InventoryDigest::isKeyItem);
        if (counts.isEmpty()) return "";
        List<Map.Entry<Item, Integer>> sorted = sortById(counts);
        StringBuilder sb = new StringBuilder();
        int kinds = 0;
        for (Map.Entry<Item, Integer> e : sorted) {
            if (kinds >= KEY_MAX_KINDS) break;
            if (sb.length() > 0) sb.append('、');
            sb.append(ItemNames.name(e.getKey().getDefaultStack())).append('x').append(e.getValue());
            kinds++;
        }
        return sb.toString();
    }

    private static boolean isKeyItem(ItemStack s) {
        return KEY_EXTRA.contains(s.getItem()) || DropValuationHelper.isHighValue(s);
    }

    // ===================== 通用工具 =====================

    private interface StackPredicate {
        boolean test(ItemStack s);
    }

    private static void collectIf(List<ItemStack> stacks, Map<Item, Integer> into, StackPredicate pred) {
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty() || !pred.test(s)) continue;
            into.merge(s.getItem(), s.getCount(), Integer::sum);
        }
    }

    /** 按注册表 id 字典序排（确定性核心：同集永远同序）。 */
    private static List<Map.Entry<Item, Integer>> sortById(Map<Item, Integer> counts) {
        List<Map.Entry<Item, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> registryId(a.getKey()).compareTo(registryId(b.getKey())));
        return list;
    }

    private static String registryId(Item item) {
        try {
            return Registry.ITEM.getId(item).toString();
        } catch (Throwable t) {
            return item.toString();
        }
    }
}
