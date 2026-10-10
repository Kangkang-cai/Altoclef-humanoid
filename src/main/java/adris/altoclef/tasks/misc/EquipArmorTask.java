package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Blocks;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class EquipArmorTask extends Task {

    private final ItemTarget[] _toEquip;

    // (2026-10-10) inventoryOnly 模式：只用背包已有材料「直接穿 / 现做」，绝不外出挖矿/砍树收集。
    // 命令入口（@equip / bridge equip）走 true；主线 gear-up 等老调用方默认 false，行为不变。
    // 现场（20261010-150627）：@equip diamond 触发 Pickup raw_iron（去挖矿），用户点名禁止。
    private final boolean _inventoryOnly;

    // ---- inventoryOnly 回执状态（逐件 one-shot 记录）----
    private final Set<Item> _skipped = new LinkedHashSet<>();
    private final Map<Item, String> _skipReasons = new LinkedHashMap<>();
    // 来源: "原本已穿" / "背包原有" / "现做"（putIfAbsent, 先记的优先）
    private final Map<Item, String> _sources = new LinkedHashMap<>();
    // 每件现做任务缓存（同件复用同实例, 避免 isEqual 不命中被框架反复 STOP）
    private final Map<Item, CraftInTableTask> _craftTasks = new HashMap<>();
    private boolean _receiptEmitted = false;

    public EquipArmorTask(ItemTarget... toEquip) {
        this(false, toEquip);
    }

    public EquipArmorTask(Item... toEquip) {
        this(false, Arrays.stream(toEquip).map(ItemTarget::new).toArray(ItemTarget[]::new));
    }

    public EquipArmorTask(boolean inventoryOnly, ItemTarget... toEquip) {
        _inventoryOnly = inventoryOnly;
        _toEquip = toEquip;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _skipped.clear();
        _skipReasons.clear();
        _sources.clear();
        _craftTasks.clear();
        _receiptEmitted = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!_inventoryOnly) {
            // ===== 原逻辑（主线 gear-up 等老调用方）：缺什么收集什么 =====
            ItemTarget[] armorsNotEquipped = Arrays.stream(_toEquip).filter(target -> !StorageHelper.itemTargetsMetInventory(mod, target) && !StorageHelper.isArmorEquipped(mod, target.getMatches())).toArray(ItemTarget[]::new);
            if (armorsNotEquipped.length > 0) {
                setDebugState("Obtaining armor");
                return new CataloguedResourceTask(armorsNotEquipped);
            }
        } else {
            // ===== inventoryOnly：逐件「直接穿 → 现做 → 跳过」，绝不外出收集 =====
            for (ItemTarget targetArmor : _toEquip) {
                Item match = targetArmor.getMatches()[0];
                if (!(match instanceof ArmorItem armor)) continue; // 命令入口已过滤, 双保险
                if (StorageHelper.isArmorEquipped(mod, match)) {
                    _sources.putIfAbsent(match, "原本已穿");
                    continue;
                }
                if (StorageHelper.countItemInInventoryReal(mod, match) >= 1) {
                    _sources.putIfAbsent(match, "背包原有");
                    setDebugState("Equipping " + armorZhName(match) + " (from inventory)");
                    return makeEquipMove(mod, targetArmor, armor);
                }
                if (_skipped.contains(match)) continue;
                String blocker = craftBlocker(mod, armor);
                if (blocker == null) {
                    _sources.putIfAbsent(match, "现做");
                    setDebugState("Crafting " + armorZhName(match) + " from inventory materials (no collecting)");
                    return craftTaskFor(armor);
                }
                // 跳过（one-shot 闩死, 本任务生命期内不再重试 —— 无出口的等待是被禁止的）
                _skipped.add(match);
                _skipReasons.put(match, blocker);
                Debug.logMessage("[equip] 跳过 " + armorZhName(match) + "：" + blocker);
            }
            // 全部件已处置（穿上 / 跳过）→ 发回执收工
            emitReceiptOnce(mod);
            return null;
        }

        setDebugState("Equipping armor");

        // Now equip (collect 模式共用原穿甲循环)
        for (ItemTarget targetArmor : _toEquip) {
            ArmorItem item = (ArmorItem) Objects.requireNonNull(targetArmor.getMatches())[0];
            if (item == null) {
                Debug.logWarning("Item " + targetArmor + " is not armor! Will not equip.");
            } else {
                if (!StorageHelper.isArmorEquipped(mod, item)) {
                    if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) {
                        StorageHelper.closeScreen();
                    }
                    Slot toMove = PlayerSlot.getEquipSlot(item.getSlotType());
                    if (toMove == null) {
                        Debug.logWarning("Invalid armor equip slot for item " + item.getTranslationKey() + ": " + item.getSlotType());
                    }
                    return new MoveItemToSlotFromInventoryTask(targetArmor, toMove);
                }
            }
        }

        return null;
    }

    /** inventoryOnly: 关界面守卫 + 搬运子任务（与原穿甲循环同款守卫）。 */
    private Task makeEquipMove(AltoClef mod, ItemTarget target, ArmorItem item) {
        if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) {
            StorageHelper.closeScreen();
            return null; // 下一 tick 再搬
        }
        Slot toMove = PlayerSlot.getEquipSlot(item.getSlotType());
        if (toMove == null) {
            Debug.logWarning("Invalid armor equip slot for item " + item.getTranslationKey() + ": " + item.getSlotType());
            return null;
        }
        return new MoveItemToSlotFromInventoryTask(target, toMove);
    }

    /**
     * inventoryOnly 专用: 返回 null = 可用背包已有材料现做; 否则返回跳过原因。
     * 判据单一化（本方法是"能不能现做"的唯一判定源, 全部消费方走这里）。
     */
    private String craftBlocker(AltoClef mod, ArmorItem armor) {
        Item material = craftMaterialOf(armor);
        if (material == null) return "该材质不可用工作台合成（仅支持皮革/铁/金/钻石）";
        int need = recipeMaterialCount(armor.getSlotType());
        // 含合成格在途材料: 防另一件合成摆料期间把本件误判缺料（09-06 镐子"差1木板"死锁同源教训）
        int have = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, material);
        if (have < need) return "缺" + materialZhName(material) + ": 需" + need + "实有" + have;
        if (!tableAvailable(mod)) return "附近无工作台, 背包也无台子/木料现做";
        return null;
    }

    /**
     * 工作台可用性（inventoryOnly 绝不外出采集的边界内）:
     * ① 背包里有台子; ② tracker 记忆 32 格内有已放置台子（与 CraftInTableTask.getMaxWalkDistance 的 32 上限对齐）;
     * ③ 半径 8 真实世界扫描兜底（覆盖 tracker 缓存时序/刚放下的台子, 同 isItemOwned 0831 兜底口径）;
     * ④ 都没有 → 只允许背包木料现做一张（随身 2x2 合成, 木料在背包 = 合成链不外出）。
     */
    private boolean tableAvailable(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        if (StorageHelper.countItemsInInventoryReal(mod, Items.CRAFTING_TABLE) > 0) return true;
        BlockPos near = mod.getBlockTracker().getNearestTracking(Blocks.CRAFTING_TABLE).orElse(null);
        if (near != null && near.isWithinDistance(mod.getPlayer().getBlockPos(), 32)) return true;
        if (nearbyWorldHasTable(mod)) return true;
        return StorageHelper.countItemsInInventoryReal(mod, ItemHelper.PLANKS) >= 4
                || StorageHelper.countItemsInInventoryReal(mod, ItemHelper.LOG) >= 1;
    }

    /** 半径 8 / 纵向 -4~+4 真实台子扫描（复刻 StorageHelper.nearbyWorldHasBlock 口径, 其为 private）。 */
    private boolean nearbyWorldHasTable(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int dx = -8; dx <= 8; dx++)
            for (int dy = -4; dy <= 4; dy++)
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos check = p.add(dx, dy, dz);
                    if (!mod.getChunkTracker().isChunkLoaded(check)) continue;
                    if (mod.getWorld().getBlockState(check).getBlock() == Blocks.CRAFTING_TABLE) return true;
                }
        return false;
    }

    private Task craftTaskFor(ArmorItem armor) {
        return _craftTasks.computeIfAbsent(armor, a -> {
            ArmorItem item = (ArmorItem) a;
            Item material = craftMaterialOf(item);
            CraftingRecipe recipe = CraftingRecipe.newShapedRecipe(
                    Registry.ITEM.getId(a).getPath(), armorRecipeSlots(material, item.getSlotType()), 1);
            // collect=false: 材料在调用方（本任务）手上, CraftInTableTask 不负责补料 ——
            // 缺料即收工（其 isFinished 有"料尽即收工"出口）, 绝不外出收集。
            return new CraftInTableTask(new RecipeTarget(a, 1, recipe), false, false);
        });
    }

    /** 材料物; null = 该材质不可用工作台合成（锁链/下界合金需锻造台/海龟壳等）。 */
    private static Item craftMaterialOf(ArmorItem armor) {
        ArmorMaterial mat = armor.getMaterial();
        if (mat == ArmorMaterials.LEATHER) return Items.LEATHER;
        if (mat == ArmorMaterials.IRON) return Items.IRON_INGOT;
        if (mat == ArmorMaterials.GOLD) return Items.GOLD_INGOT;
        if (mat == ArmorMaterials.DIAMOND) return Items.DIAMOND;
        return null;
    }

    private static int recipeMaterialCount(net.minecraft.entity.EquipmentSlot type) {
        return switch (type) {
            case HEAD -> 5;
            case CHEST -> 8;
            case LEGS -> 7;
            case FEET -> 4;
            default -> 0;
        };
    }

    private static ItemTarget[] armorRecipeSlots(Item material, net.minecraft.entity.EquipmentSlot type) {
        ItemTarget m = new ItemTarget(material);
        return switch (type) {
            case HEAD -> new ItemTarget[]{m, m, m, m, null, m, null, null, null};
            case CHEST -> new ItemTarget[]{m, null, m, m, m, m, m, m, m};
            case LEGS -> new ItemTarget[]{m, m, m, m, null, m, m, null, m};
            case FEET -> new ItemTarget[]{m, null, m, m, null, m, null, null, null};
            default -> new ItemTarget[]{null, null, null, null, null, null, null, null, null};
        };
    }

    private static String armorZhName(Item item) {
        String path = Registry.ITEM.getId(item).getPath(); // e.g. iron_chestplate
        int sep = path.indexOf('_');
        String material = sep > 0 ? path.substring(0, sep) : path;
        String piece = sep > 0 ? path.substring(sep + 1) : "";
        String m = switch (material) {
            case "leather" -> "皮革";
            case "chainmail" -> "锁链";
            case "iron" -> "铁";
            case "golden", "gold" -> "金";
            case "diamond" -> "钻石";
            case "netherite" -> "下界合金";
            default -> material;
        };
        String p = switch (piece) {
            case "helmet" -> "头盔";
            case "chestplate" -> "胸甲";
            case "leggings" -> "护腿";
            case "boots" -> "靴子";
            default -> piece;
        };
        return m + p;
    }

    private static String materialZhName(Item material) {
        return switch (Registry.ITEM.getId(material).getPath()) {
            case "leather" -> "皮革";
            case "iron_ingot" -> "铁锭";
            case "gold_ingot" -> "金锭";
            case "diamond" -> "钻石";
            default -> Registry.ITEM.getId(material).getPath();
        };
    }

    private void emitReceiptOnce(AltoClef mod) {
        if (_receiptEmitted) return;
        _receiptEmitted = true;
        Debug.logMessage("[equip] " + getReceiptText());
    }

    public boolean isInventoryOnly() {
        return _inventoryOnly;
    }

    /** 回执正文: "穿甲回执: 已穿 x/n: 件(来源)...; 跳过 y: 件(原因)..." （bridge action_done 与游戏内 chat 共用）。 */
    public String getReceiptText() {
        List<String> worn = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ItemTarget target : _toEquip) {
            Item match = target.getMatches()[0];
            if (_skipped.contains(match)) {
                skipped.add(armorZhName(match) + "(" + _skipReasons.getOrDefault(match, "未知") + ")");
            } else {
                worn.add(armorZhName(match) + "(" + _sources.getOrDefault(match, "已穿") + ")");
            }
        }
        StringBuilder sb = new StringBuilder("穿甲回执: 已穿 ").append(worn.size()).append("/").append(_toEquip.length);
        if (!worn.isEmpty()) sb.append(": ").append(String.join(", ", worn));
        if (!skipped.isEmpty()) sb.append("; 跳过 ").append(skipped.size()).append(": ").append(String.join(", ", skipped));
        return sb.toString();
    }

    /** 已穿上/原本已穿的件数（= 总数 - 跳过; 结束时非跳过件必已穿上）。 */
    public int getWornCount() {
        return _toEquip.length - _skipped.size();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_inventoryOnly) {
            // 全部件 = 已穿上 或 已跳过（跳过视为处置完毕, 否则缺料会永久等待）
            for (ItemTarget target : _toEquip) {
                Item match = target.getMatches()[0];
                if (!StorageHelper.isArmorEquipped(mod, match) && !_skipped.contains(match)) return false;
            }
            return true;
        }
        return armorEquipped(mod);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EquipArmorTask task) {
            return task._inventoryOnly == _inventoryOnly && Arrays.equals(task._toEquip, _toEquip);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Equipping armor " + ArrayUtils.toString(_toEquip) + (_inventoryOnly ? " [只用已有材料]" : "");
    }

    private boolean armorTestAll(java.util.function.Predicate<Item> armorSatisfies) {
        // If ALL item target has any match that is equipped...
        return Arrays.stream(_toEquip).allMatch(
                target -> Arrays.stream(target.getMatches()).anyMatch(armorSatisfies)
        );
    }

    public boolean armorEquipped(AltoClef mod) {
        return armorTestAll(item -> StorageHelper.isArmorEquipped(mod, item));
    }

}
