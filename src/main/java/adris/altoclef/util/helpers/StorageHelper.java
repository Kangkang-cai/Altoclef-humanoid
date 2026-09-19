package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.mixins.AbstractFurnaceScreenHandlerAccessor;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.util.*;
import adris.altoclef.util.slots.*;
import baritone.utils.ToolSet;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.*;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Helper functions for interpreting containers/slots/windows/inventory
 */
@SuppressWarnings({"ConstantConditions", "rawtypes"})
public class StorageHelper {

    /**
     * (2026-08-31 统一所有权模型) 全系统"我们有没有 X / 材料够不够"的唯一
     * 语义判定入口, 替代散落各处互不一致的计数口径。
     *
     * USE    : 只要能拿来干活就算拥有 —— 方块类物品, 世界里存在有效同类方块
     *          (已放置的台子/熔炉/床) 也算拥有。用于"能不能用这个容器/方块"。
     * POSSESS: 必须真的背在背包里才算拥有(要带走/当下材料/把装备穿在身上)。
     *          已放置的方块不算 —— 床要带走就必须真的背着, 不能因为附近有张床
     *          就算完成。
     *
     * 除特别说明外, 统一的"计数"固定用 {@link #countItemsInInventoryReal}
     * (真实扫 主背包+护甲+副手+光标), 杜绝 track 缓存陈旧导致的误判。
     */
    public enum Ownership {
        USE,
        POSSESS
    }

    public static List<PlayerSlot> INACCESSIBLE_PLAYER_SLOTS = Stream.concat(Stream.of(PlayerSlot.CRAFT_INPUT_SLOTS), Stream.of(PlayerSlot.ARMOR_SLOTS)).toList();

    public static void closeScreen() {
        if (MinecraftClient.getInstance().player == null)
            return;
        Screen screen = MinecraftClient.getInstance().currentScreen;
        if (
                screen != null &&
                !(screen instanceof GameMenuScreen) &&
                !(screen instanceof GameOptionsScreen) &&
                !(screen instanceof ChatScreen)) {
            // Close the screen if we're in-game
            MinecraftClient.getInstance().player.closeHandledScreen();
        }
    }

    public static ItemStack getItemStackInSlot(Slot slot) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null)
            return ItemStack.EMPTY;
        // Cursor slot
        if (Slot.isCursor(slot)) {
            return StorageHelper.getItemStackInCursorSlot();
        }
        // Inventory slot when inventory is NOT open
        PlayerInventory inv = player.getInventory();
        if (inv != null) {
            if (slot.equals(PlayerSlot.OFFHAND_SLOT))
                return inv.offHand.stream().findFirst().orElse(ItemStack.EMPTY).copy();
            if (slot.equals(PlayerSlot.ARMOR_HELMET_SLOT))
                return inv.getArmorStack(3).copy();
            if (slot.equals(PlayerSlot.ARMOR_CHESTPLATE_SLOT))
                return inv.getArmorStack(2).copy();
            if (slot.equals(PlayerSlot.ARMOR_LEGGINGS_SLOT))
                return inv.getArmorStack(1).copy();
            if (slot.equals(PlayerSlot.ARMOR_BOOTS_SLOT))
                return inv.getArmorStack(0).copy();
        }
        try {
            // We might have messed up and opened the wrong slot.
            net.minecraft.screen.slot.Slot mcSlot = player.currentScreenHandler.getSlot(slot.getWindowSlot());
            return (mcSlot != null) ? mcSlot.getStack().copy() : ItemStack.EMPTY;
        } catch (Exception e) {
            Debug.logWarning("Screen Slot Error (ignored)");
            e.printStackTrace();
            return ItemStack.EMPTY;
        }
    }

    public static MiningRequirement getCurrentMiningRequirement(AltoClef mod) {
        MiningRequirement[] order = new MiningRequirement[]{
                MiningRequirement.DIAMOND, MiningRequirement.IRON, MiningRequirement.STONE, MiningRequirement.WOOD
        };
        for (MiningRequirement check : order) {
            if (miningRequirementMet(mod, check)) {
                return check;
            }
        }
        return MiningRequirement.HAND;
    }
    private static boolean h(AltoClef mod, boolean inventoryOnly, Item ...items) {
        if (inventoryOnly) {
            if (mod.getItemStorage().hasItemInventoryOnly(items)) return true;
        } else {
            if (mod.getItemStorage().hasItem(items)) return true;
        }
        // (2026-08-23 兜底直扫) 用户反馈: 背包里有铁镐/钻石镐, 但 bot 仍触发
        // "Satisfy Mining Req: STONE" 去重做石镐 ("它似乎把背包里的东西跳过了")。
        // ItemStorageTracker 缓存万一漏检/未刷新, 直接扫玩家主背包槽位兜底。
        if (MinecraftClient.getInstance().player == null) return false;
        for (ItemStack stack : MinecraftClient.getInstance().player.getInventory().main) {
            if (stack.isEmpty()) continue;
            for (Item item : items) {
                if (stack.getItem() == item) return true;
            }
        }
        return false;
    }
    private static boolean miningRequirementMetInner(AltoClef mod, boolean inventoryOnly, MiningRequirement requirement) {
        switch (requirement) {
            case HAND:
                return true;
            case WOOD:
                return h(mod, inventoryOnly, Items.WOODEN_PICKAXE) || h(mod, inventoryOnly, Items.STONE_PICKAXE) || h(mod, inventoryOnly, Items.IRON_PICKAXE) || h(mod, inventoryOnly, Items.GOLDEN_PICKAXE) || h(mod, inventoryOnly, Items.DIAMOND_PICKAXE) || h(mod, inventoryOnly, Items.NETHERITE_PICKAXE);
            case STONE:
                return h(mod, inventoryOnly, Items.STONE_PICKAXE) || h(mod, inventoryOnly, Items.IRON_PICKAXE) || h(mod, inventoryOnly, Items.GOLDEN_PICKAXE) || h(mod, inventoryOnly, Items.DIAMOND_PICKAXE) || h(mod, inventoryOnly, Items.NETHERITE_PICKAXE);
            case IRON:
                return h(mod, inventoryOnly, Items.IRON_PICKAXE) || h(mod, inventoryOnly, Items.GOLDEN_PICKAXE) || h(mod, inventoryOnly, Items.DIAMOND_PICKAXE) || h(mod, inventoryOnly, Items.NETHERITE_PICKAXE);
            case DIAMOND:
                return h(mod, inventoryOnly, Items.DIAMOND_PICKAXE) || h(mod, inventoryOnly, Items.NETHERITE_PICKAXE);
            default:
                Debug.logError("You missed a spot");
                return false;
        }
    }
    public static boolean miningRequirementMet(AltoClef mod, MiningRequirement requirement) {
        return miningRequirementMetInner(mod, false, requirement);
    }
    public static boolean miningRequirementMetInventory(AltoClef mod, MiningRequirement requirement) {
        return miningRequirementMetInner(mod, true, requirement);
    }

    /**
     * (2026-08-24 真实背包计数兜底) 直接扫玩家真实背包槽位 (主背包+护甲+
     * 副手+光标, 不走 tracker 缓存)。用途: 任务启动瞬间 tracker 缓存可能
     * 陈旧/未初始化, 导致"背包明明有金锭/生金矿却判定为没有" → 一直挖金
     * (用户实测: 启动 gamer 检测不到金锭, 按 esc 刷新才检测到)。与
     * miningRequirementMet 的"镐检测兜底"同一思路: 判定关键资源时以真实
     * 背包为准。
     */
    public static int countItemInInventoryReal(AltoClef mod, Item item) {
        int count = 0;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    /**
     * (2026-08-24 真实背包计数兜底, 多物品版) 同 countItemInInventoryReal,
     * 但接受多个匹配物品 (任一个都计入), 供 SmeltInFurnaceTask 等材料判定
     * 使用 — 材料槽/目标可能匹配多种物品。
     */
    public static int countItemsInInventoryReal(AltoClef mod, Item... items) {
        int count = 0;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            for (Item item : items) {
                if (stack.getItem() == item) {
                    count += stack.getCount();
                    break;
                }
            }
        }
        return count;
    }

    /**
     * (2026-09-01 合成格材料计数, 修复"把所有材料做光") 统计【真实背包 + 2x2 合成格 + 光标】
     * 中匹配物品的数量。与 {@link #countItemsInInventoryReal} 不同: 后者只扫玩家主背包+
     * 护甲+副手+光标, 不含 2x2 合成格 — 当收集材料被搬进合成格等待合成(如做合成台/木斧
     * 时木板放进 2x2 格)后, 合成格里的材料会从"真实背包"里消失 → 判定"缺材料"→ 反复
     * 派发收集 → 又塞进合成格 → 把所有材料喂光(用户实测: planks x4 真实持有恒 0 反复收集)。
     * 这里的语义是"我们到底有没有这么多可用来合成的材料", 合成格里的当然算。
     */
    public static int countItemsInInventoryRealIncludingCraftGrid(AltoClef mod, Item... items) {
        int count = countItemsInInventoryReal(mod, items);
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return count;
        for (PlayerSlot craftSlot : PlayerSlot.CRAFT_INPUT_SLOTS) {
            ItemStack stack = getItemStackInSlot(craftSlot);
            if (stack.isEmpty()) continue;
            for (Item item : items) {
                if (stack.getItem() == item) {
                    count += stack.getCount();
                    break;
                }
            }
        }
        // (2026-09-09 补 3x3 合成台格) 上版只扫 2x2 PlayerSlot.CRAFT_INPUT_SLOTS。
        // 3x3 合成台打开时(如做床) 配方书一键填充 instantFillRecipeViaBook 把材料
        // 搬进 CraftingTableSlot 格, 材料同样从真实背包"消失" → CraftWithMatchingMaterialsTask
        // 判缺料翻面 "Collect 999999 wool" 打断自家合成 (2026-09-09 08:27 tasks log
        // 做床 ping-pong ~15 轮实锤)。与 hasRecipeMaterialsOrTarget 的 3x3 口径对齐。
        if (player.currentScreenHandler instanceof CraftingScreenHandler) {
            for (Slot tableSlot : CraftingTableSlot.INPUT_SLOTS) {
                ItemStack stack = getItemStackInSlot(tableSlot);
                if (stack.isEmpty()) continue;
                for (Item item : items) {
                    if (stack.getItem() == item) {
                        count += stack.getCount();
                        break;
                    }
                }
            }
        }
        return count;
    }

    /**
     * (2026-09-01 同步 countItemInInventoryReal, 含合成格版) 统计单个物品在
     * 真实背包 + 2x2 合成格 + 光标中的数量。见 {@link #countItemsInInventoryRealIncludingCraftGrid}。
     */
    public static int countItemInInventoryRealIncludingCraftGrid(AltoClef mod, Item item) {
        return countItemsInInventoryRealIncludingCraftGrid(mod, item);
    }

    /**
     * (2026-09-10 横跳修复) 计数口径:【真实背包 + 护甲 + 副手 + 光标 + 当前打开
     * 容器的自有槽位(工作台 3x3/熔炉等)】。玩家自带界面时退化为旧口径
     * (含 2x2 合成格)。容器界面打开时【不可】叠用 includingCraftGrid 口径:
     * 其 PlayerSlot.CRAFT_INPUT_SLOTS=window1-4 在台子界面解析到台格前四格,
     * 与容器遍历双计。玩家背包槽(PlayerInventory)跳过防双计。
     *
     * 修复场景: "钻石即做"分支每 tick 用纯背包口径数钻石 — 钻石镐合成把 3 颗钻
     * 借进工作台 3x3 的瞬间, 背包口径骤降 → 分支切下一件防具抢切镐合成 → 格子
     * 打断钻石弹回 → 又切回 → chestplate↔leggings 每 ~250ms 振荡 (18:28 场次
     * [Diag-flow] 实锤), 钻石镐 50 次重选永不完成。含台格口径后阈值判定稳定。
     */
    public static int countItemsAccessibleIncludingOpenContainer(AltoClef mod, Item item) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        ScreenHandler handler = player.currentScreenHandler;
        if (handler == null || handler == player.playerScreenHandler) {
            return countItemInInventoryRealIncludingCraftGrid(mod, item);
        }
        int total = countItemsInInventoryReal(mod, item);
        for (net.minecraft.screen.slot.Slot slot : handler.slots) {
            if (slot == null || slot.inventory instanceof PlayerInventory) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * (2026-09-01) 仅统计 2x2 合成格中匹配物品的数量。配合
     * {@link #countItemsInInventoryReal} 实现"背包 + 合成格"口径。
     */
    public static int countItemsInCraftGrid(AltoClef mod, Item... items) {
        int count = 0;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        for (PlayerSlot craftSlot : PlayerSlot.CRAFT_INPUT_SLOTS) {
            ItemStack stack = getItemStackInSlot(craftSlot);
            if (stack.isEmpty()) continue;
            for (Item item : items) {
                if (stack.getItem() == item) {
                    count += stack.getCount();
                    break;
                }
            }
        }
        return count;
    }

    /**
     * (2026-09-01) 判定是否拥有目标物品, 计数口径为【真实背包 + 2x2 合成格 + 光标】。
     * 语义同 {@link #isItemOwned}, 差别仅在把合成格中的材料也计入"已有" —— 防止
     * 材料搬进合成格等待合成时被判"缺失"而反复收集 (见 countItemsInInventoryRealIncludingCraftGrid)。
     */
    // (2026-09-01 致命BUG修复 — 导致 CollectRecipeResources 卡死 62s 0事件)
    // 之前版本: for each match 只要 count>0 就 return true, 完全忽略
    // target.getTargetCount() 的数量要求。做 crafting_table 需 4 木板但玩家
    // 只有 1 时, 仍判"有木板 = 达标", 提前 finished=true 且不派任何收集子任务。
    // 修复: 对所有匹配物品累计真实计数, 和 target.getTargetCount() 比较。
    public static boolean itemTargetOwnedIncludingCraftGrid(AltoClef mod, Ownership semantics, ItemTarget target) {
        int total = countItemsInInventoryRealIncludingCraftGrid(mod, target.getMatches());
        if (total >= target.getTargetCount()) {
            return true;
        }
        if (semantics == Ownership.USE) {
            // 方块类 USE 语义: 世界里存在有效同类方块也算拥有 (复用 isItemOwned 逻辑)。
            // 例: 附近有已放置的工作台 → 即使背包没工作台物品也判定 USE 达标。
            return isItemOwned(mod, semantics, target.getMatches());
        }
        return false;
    }

    /**
     * (2026-08-31 统一所有权模型) 统一"我们是否拥有任一给定物品"谓词。
     *
     * 计数一律用真实背包 (主背包+护甲+副手+光标), 不走缓存。
     * {@link Ownership#USE}: 方块类物品若世界里存在有效同类方块, 也算拥有
     *                      (已放置的台子/熔炉/床 = 可用), 无需真背在身上。
     * {@link Ownership#POSSESS}: 必须真实背着才作数 (要带走/当下材料/穿身上)。
     */
    public static boolean isItemOwned(AltoClef mod, Ownership semantics, Item... items) {
        if (countItemsInInventoryReal(mod, items) > 0) return true;
        if (semantics == Ownership.USE) {
            for (Item item : items) {
                if (item instanceof BlockItem blockItem) {
                    if (mod.getBlockTracker().anyFound(blockItem.getBlock())) {
                        return true;
                    }
                    // (2026-08-31 容器方块判定兜底) BlockTracker.anyFound 依赖近区扫描
                    // 缓存时序 —— 刚合成/刚放下的台子可能因缓存未刷新而未被感知, 导致
                    // 容器任务误判"没台子"→ 每轮再造一张(用户实测做个位工作台, 并且
                    // 打断采石)。直接扫 bot 近处真实世界方块, 确认眼前/脚下的同类方块
                    // 立即算"已有可用", 不依赖异步缓存。
                    if (nearbyWorldHasBlock(mod, blockItem.getBlock())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * (2026-08-31) 扫 bot 近处(半径 8, 纵向 -4~+4)的真实世界方块, 是否含目标方块。
     * 供 {@link #isItemOwned} 容器方块兜底, 覆盖 BlockTracker 缓存未刷新的窗口期。
     */
    private static boolean nearbyWorldHasBlock(AltoClef mod, net.minecraft.block.Block target) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        net.minecraft.util.math.BlockPos p = mod.getPlayer().getBlockPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    net.minecraft.util.math.BlockPos check = new net.minecraft.util.math.BlockPos(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    if (!mod.getChunkTracker().isChunkLoaded(check)) continue;
                    if (mod.getWorld().getBlockState(check).getBlock() == target) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * (2026-08-31 统一所有权模型) 统一"是否拥有 x 个某物品" (多物品任其计数都算)。
     * 语义同 {@link #isItemOwned}; 计数为真实背包计数, USE 时"已放置方块"视为
     * 额外 +1 可用量。
     */
    public static int countItemOwnedReal(AltoClef mod, Ownership semantics, Item... items) {
        int count = countItemsInInventoryReal(mod, items);
        if (count == 0 && semantics == Ownership.USE) {
            for (Item item : items) {
                if (item instanceof BlockItem blockItem && mod.getBlockTracker().anyFound(blockItem.getBlock())) {
                    return 1;
                }
            }
        }
        return count;
    }

    /**
     * (2026-08-31 统一所有权模型) 从 {@code target} 取出所有匹配物品交给
     * {@link #countItemOwnedReal} 判定是否达标。
     */
    public static boolean itemTargetOwned(AltoClef mod, Ownership semantics, ItemTarget target) {
        return countItemOwnedReal(mod, semantics, target.getMatches()) >= target.getTargetCount();
    }

    /**
     * (2026-08-24 真实背包槽位扫描) 直接扫玩家主背包槽位 (不走 tracker 缓存),
     * 返回含任一所给物品的槽位。供 SlotHandler.forceEquipItem 等"装备/交互
     * 前找物品槽"使用 — tracker 缓存可能陈旧 (金锭刚收进背包就读不到 →
     * forceEquipItem 返回 false → 交易交互不触发, 用户实测 13:52 卡死循环)。
     */
    public static List<Slot> getItemSlotsInInventoryReal(AltoClef mod, Item... items) {
        List<Slot> result = new ArrayList<>();
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return result;
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            ItemStack stack = player.getInventory().main.get(i);
            for (Item item : items) {
                if (stack.getItem() == item) {
                    result.add(Slot.getFromCurrentScreenInventory(i));
                    break;
                }
            }
        }
        // (2026-09-06 22:33 副手不可见修复) 旧扫描只扫 main 0..35: 面包只剩副手
        // 一摞时 forceEquip 永远失败 (实锤 "[FOOD-EAT] equip food FAILED: bread
        // main=iron_pickaxe off=bread" 死循环)。补充副手槽 —— forceEquip 的
        // SWAP(副手格, 快捷栏序号) 可直接把副手物品换到主手。
        ItemStack offhand = player.getInventory().offHand.get(0);
        for (Item item : items) {
            if (offhand.getItem() == item) {
                result.add(PlayerSlot.OFFHAND_SLOT);
                break;
            }
        }
        return result;
    }

    public static Optional<Slot> getBestToolSlot(AltoClef mod, BlockState state) {
        // TODO: mod.configState.silkTouchOverrideMode {
        //      DONT_CARE (Default)
        //      PREFER (Always use silk touch if we have)
        //      AVOID  (Don't use silk touch if we can)
        //  }
        Slot bestToolSlot = null;
        double highestSpeed = Double.NEGATIVE_INFINITY;

        for (Slot slot : Slot.getCurrentScreenSlots()) {
            if (!slot.isSlotInPlayerInventory())
                continue;
            ItemStack stack = getItemStackInSlot(slot);
            if (stack.getItem() instanceof ToolItem) {
                if (stack.getItem().isSuitableFor(state)) {
                    double speed = ToolSet.calculateSpeedVsBlock(stack, state);
                    if (speed > highestSpeed) {
                        highestSpeed = speed;
                        bestToolSlot = slot;
                    }
                }
            }
            if (stack.getItem() == Items.SHEARS) {
                // Shears take priority over leaf blocks.
                if (ItemHelper.areShearsEffective(state.getBlock())) {
                    bestToolSlot = slot;
                    break;
                }
            }
        }
        return Optional.ofNullable(bestToolSlot);
    }

    // "Bulk" 建材/杂物：满背包无垃圾可扔时优先丢这些腾位（人类也是这么
    // 清背包的——丢一把圆石，不丢铁锭）。只丢 >= 16 个的整堆。
    private static final Item[] BULK_DISPOSAL_ITEMS = {
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.GRAVEL,
            Items.SAND, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
            Items.NETHERRACK, Items.TUFF, Items.CALCITE, Items.ROTTEN_FLESH
    };

    public static boolean isBulkDisposalItem(Item item) {
        for (Item bulk : BULK_DISPOSAL_ITEMS) {
            if (bulk == item) return true;
        }
        return false;
    }

    /**
     * 满背包腾位（2026-08-21 床掉落物丢失 bug）：背包全满且没有任何垃圾
     * 可扔时，返回一个"大堆廉价建材"槽位（>= 16 个的圆石/泥土/花岗岩等，
     * 非保护物品），把它扔掉换取一个空位。返回 null = 没有可丢的。
     * 用于捡不起掉落物（如刚撸掉的床掉在脚边）的死循环场景。
     */
    public static Slot getBulkDisposalSlot(AltoClef mod) {
        Slot best = null;
        int bestCount = 15; // 只丢 >= 16 的堆，小堆建材留着用
        for (Slot slot : mod.getItemStorage().getSlotsWithItemPlayerInventory(false, BULK_DISPOSAL_ITEMS)) {
            // (2026-09-03 同 getGarbageSlot 根因) PlayerScreenHandler 下合成格(window0-4)
            // 会被 tracker 当玩家背包槽混进候选 — tuff 本身就是 BULK 清单项, 用户复现
            // tuffx21 卡在合成格。合成格不是存储, 点它是拿起/交换, 腾不出真背包位。
            if (isCraftingGridAreaSlot(slot))
                continue;
            ItemStack stack = getItemStackInSlot(slot);
            if (stack == null || stack.isEmpty()) continue;
            // (2026-09-11 弹回熔断) 8s 内弹回≥2 次的物品 60s 冷却期内不参与大堆腾位。
            if (ItemHelper.isThrowOnCooldown(stack.getItem())) continue;
            if (ItemHelper.isStackProtected(mod, stack)) continue;
            // (2026-09-09 保留闸门补洞, 用户"丢垃圾至少保留一组石头") 旧实现
            // 对 BULK 清单(圆石排第一位!)无任何保留检查: 背包满+无垃圾时, 30 个
            // 圆石也会被整堆扔掉 → 下界捡不回 → 挖不到圆石死循环。圆石家族
            // (圆石/深板岩圆石/黑石)在保留量内绝不参与大堆腾位丢弃。
            if (ItemHelper.isReservedBlockUnderLimit(mod, stack)) continue;
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                best = slot;
            }
        }
        return best;
    }

    // Gets a slot with an item we can throw away
    /**
     * (2026-09-03 合成格区域判定) 当前屏幕为玩家自带 2x2 背包界面(PlayerScreenHandler,
     * 无 GUI 时 MC 默认也是它)时, window 0-4 = 合成输出(0)+2x2合成格(1-4), 会被
     * isSlotInPlayerInventory() 误归入"玩家背包槽"。合成格不是存储: 找垃圾槽/找空位/
     * 找可丢槽等"作用于真背包"的操作必须排除它, 否则会把合成格里的垃圾(tuff/闪长岩)
     * 当目标点击 → 拿起/交换 → 光标↔合成格循环卡死。容器/工作台界面 window 0-4 是
     * 真实容器槽, 不受影响。
     */
    private static boolean isCraftingGridAreaSlot(Slot slot) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        if (!(player.currentScreenHandler instanceof net.minecraft.screen.PlayerScreenHandler)) return false;
        int w = slot.getWindowSlot();
        return w >= 0 && w <= 4;
    }

    public static Optional<Slot> getGarbageSlot(AltoClef mod) {
        // Throwaway items, but keep a few for building.
        final List<Slot> throwawayBlockItems = new ArrayList<>();
        int totalBlockThrowaways = 0;
        for (Slot slot : mod.getItemStorage().getSlotsWithItemPlayerInventory(false, mod.getModSettings().getThrowawayItems(mod))) {
            // Our cursor slot is NOT a garbage slot
            if (Slot.isCursor(slot))
                continue;
            // (2026-09-03 根因修复: 2x2 合成格被当垃圾槽, 用户"bot 往合成格塞满 tuff")
            // PlayerScreenHandler 下 window0-4(合成输出+2x2合成格)被 isSlotInPlayerInventory
            // 归入玩家背包槽 → 这里的候选含合成格里的物品(tuff/闪长岩等垃圾)。返回它
            // 作为"垃圾槽"后, 调用方 clickSlot 点它 = 拿起/交换而非丢弃, tuff 在光标与
            // 合成格间循环 → "Breaking the crafting grid" 卡死。合成格不是存储, 绝不作为
            // 垃圾/丢出候选。
            if (isCraftingGridAreaSlot(slot))
                continue;
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!ItemHelper.canThrowAwayStack(mod, stack))
                continue;
            // (2026-09-11 弹回熔断) 冷却中的物品不作垃圾候选, 打断"扔→弹回→原版
            // 拾取→再扔"死循环 (现场 11:12:53 实锤, 见 ItemHelper 弹回熔断器)。
            if (ItemHelper.isThrowOnCooldown(stack.getItem()))
                continue;
            if (stack.getItem() instanceof BlockItem) {
                // (2026-09-03 垃圾方块有就丢, 用户"安山岩/闪长岩/凝灰岩/树苗/泥土
                // 不丢") 只有圆石/深板岩圆石作为建筑材料保留一组(64, 由
                // canThrowAwayStack 的 isReservedBlockUnderLimit 兜底), 其余
                // throwawayItems 方块(树苗/泥土/安山岩等)都是垃圾, 有就丢 — 旧
                // 逻辑把它们的总量也塞进保留闸门, 合计 <64 时一个都不丢。
                Item blockItem = stack.getItem();
                // (2026-09-09) 黑石=下界的"圆石"(可做石镐/熔炉), 与圆石/深板岩圆石
                // 同进保留闸门合计, 与 isReservedBlockUnderLimit 口径一致。
                if (blockItem == Items.COBBLESTONE || blockItem == Items.COBBLED_DEEPSLATE || blockItem == Items.BLACKSTONE) {
                    totalBlockThrowaways += stack.getCount();
                    throwawayBlockItems.add(slot);
                } else {
                    return Optional.of(slot);
                }
            } else {
                // Throw away this non-block immediately.
                return Optional.of(slot);
            }
        }
        if (!throwawayBlockItems.isEmpty() && totalBlockThrowaways > mod.getModSettings().getReservedBuildingBlockCount()) {
            // (2026-08-23) 丢最大堆而非第一个槽位 — 石头偏多时优先丢大堆。
            Slot largest = null;
            int largestCount = -1;
            for (Slot slot : throwawayBlockItems) {
                int count = StorageHelper.getItemStackInSlot(slot).getCount();
                if (count > largestCount) {
                    largestCount = count;
                    largest = slot;
                }
            }
            return Optional.of(largest);
        }

        // Try throwing away lower tier tools
        final HashMap<Class, Integer> bestMaterials = new HashMap<>();
        final HashMap<Class, Slot> bestTool = new HashMap<>();
        for (Slot slot : PlayerSlot.getCurrentScreenSlots()) {
            // (2026-09-03 同根因) 合成格/输出槽(window0-4)不是背包, 不当工具垃圾候选。
            if (isCraftingGridAreaSlot(slot))
                continue;
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!ItemHelper.canThrowAwayStack(mod, stack))
                continue;
            // (2026-09-11 弹回熔断) 冷却中的低阶工具同样跳过。
            if (ItemHelper.isThrowOnCooldown(stack.getItem()))
                continue;
            Item item = stack.getItem();
            if (item instanceof ToolItem tool) {
                Class c = tool.getClass();
                int level = tool.getMaterial().getMiningLevel();
                int prevBest = bestMaterials.getOrDefault(c, 0);
                if (level > prevBest) {
                    // We had a WORSE tool before.
                    if (bestTool.containsKey(c)) {
                        return Optional.of(bestTool.get(c));
                    }
                    bestMaterials.put(c, level);
                    bestTool.put(c, slot);
                } else if (level < prevBest) {
                    // We found something WORSE!
                    return Optional.of(slot);
                }
            }
        }

        // (2026-08-23 重复工具可扔) 用户设计: 三个铁镐、三个钻石镐这种同材料
        // 重复工具可以扔, 只保留一把。按 (工具类, 材料等级) 分组, 同组 >1 个
        // 就丢一个多余的。低阶工具 (木镐 vs 铁镐) 已由上面逻辑处理, 这里只
        // 处理"同材料多把"的重复。
        final HashMap<String, List<Slot>> toolGroups = new HashMap<>();
        for (Slot slot : PlayerSlot.getCurrentScreenSlots()) {
            // (2026-09-03 同根因) 合成格/输出槽(window0-4)不是背包, 不当工具垃圾候选。
            if (isCraftingGridAreaSlot(slot))
                continue;
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (stack.isEmpty() || !ItemHelper.canThrowAwayStack(mod, stack))
                continue;
            // (2026-09-11 弹回熔断) 冷却中的重复工具同样跳过。
            if (ItemHelper.isThrowOnCooldown(stack.getItem()))
                continue;
            Item item = stack.getItem();
            if (item instanceof ToolItem tool) {
                String key = tool.getClass().getName() + ":" + tool.getMaterial().getMiningLevel();
                toolGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(slot);
            }
        }
        for (List<Slot> slots : toolGroups.values()) {
            if (slots.size() > 1) {
                return Optional.of(slots.get(0));
            }
        }

        // Now we're getting desparate
        if (mod.getModSettings().shouldThrowawayUnusedItems()) {

            // Also uh calculate how much food we have.
            int calcTotalFoodScore = 0;

            // Get all non-important items. For now there is no measure of value.
            final List<Slot> possibleSlots = new ArrayList<>();
            for (Slot slot : PlayerSlot.getCurrentScreenSlots()) {
                // (2026-09-03 同根因) 合成格/输出槽(window0-4)不是背包, 不当垃圾候选。
                if (isCraftingGridAreaSlot(slot))
                    continue;
                ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                // If we're an armor slot, don't count us.
                if (slot instanceof PlayerSlot playerSlot) {
                    if (ArrayUtils.contains(PlayerSlot.ARMOR_SLOTS, playerSlot)) {
                        continue;
                    }
                }
                // (2026-08-23 别把唯一/最好的镐扔了) 满背包腾位时曾把唯一石镐
                // 当"unused"扔掉 → 在地狱里重建要木头 → 死循环收集木头 (日志
                // 实锤)。扔了就没镐可用的槽位不加入候选。
                if (wouldLeaveNoPickaxe(mod, stack)) continue;
                // Throw away-able slots are good!
                if (ItemHelper.canThrowAwayStack(mod, stack)) {
                    possibleSlots.add(slot);
                }
                if (stack.getItem().isFood()) {
                    calcTotalFoodScore += Objects.requireNonNull(stack.getItem().getFoodComponent()).getHunger();
                }
            }

            final int totalFoodScore = calcTotalFoodScore;

            if (!possibleSlots.isEmpty()) {
                // (2026-08-23 丢物优先级) 用户原则: 石头/杂石偏多, 满背包
                // 时优先丢超量 (>64) 的最大石头堆; 原木绝不优先丢 (木头是
                // 燃料/合成刚需), 只有别无可丢才轮到它。原逻辑最后按
                // "最小数量"挑 — 原木通常是小堆, 结果木头比 64 圆石先丢。
                Slot biggestBulk = null;
                int biggestBulkCount = 64;
                List<Slot> nonLogSlots = new ArrayList<>();
                boolean sawLog = false;
                for (Slot slot : possibleSlots) {
                    ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                    if (stack.isEmpty()) continue;
                    if (ArrayUtils.contains(ItemHelper.LOG, stack.getItem())) {
                        sawLog = true;
                        continue;
                    }
                    nonLogSlots.add(slot);
                    if (isBulkDisposalItem(stack.getItem()) && stack.getCount() > biggestBulkCount) {
                        biggestBulkCount = stack.getCount();
                        biggestBulk = slot;
                    }
                }
                if (biggestBulk != null) {
                    return Optional.of(biggestBulk);
                }
                // 原木垫底: 有非原木候选就只在非原木里挑。
                List<Slot> candidates = nonLogSlots.isEmpty() && sawLog ? possibleSlots : nonLogSlots;
                if (candidates.isEmpty()) {
                    return Optional.empty();
                }
                return candidates.stream().min((leftSlot, rightSlot) -> {
                    ItemStack left = StorageHelper.getItemStackInSlot(leftSlot),
                            right = StorageHelper.getItemStackInSlot(rightSlot);
                    boolean leftIsTool = left.getItem() instanceof ToolItem;
                    boolean rightIsTool = right.getItem() instanceof ToolItem;
                    // Prioritize tools over materials.
                    if (rightIsTool && !leftIsTool) {
                        return -1;
                    } else if (leftIsTool && !rightIsTool) {
                        return 1;
                    }
                    if (rightIsTool && leftIsTool) {
                        // Prioritize material type, then durability.
                        ToolItem leftTool = (ToolItem) left.getItem();
                        ToolItem rightTool = (ToolItem) right.getItem();
                        if (leftTool.getMaterial().getMiningLevel() != rightTool.getMaterial().getMiningLevel()) {
                            return leftTool.getMaterial().getMiningLevel() - rightTool.getMaterial().getMiningLevel();
                        }
                        // We want less damage.
                        return left.getDamage() - right.getDamage();
                    }

                    // Prioritize food over other things if we lack food.
                    boolean lacksFood = totalFoodScore < 8;
                    boolean leftIsFood = left.getItem().isFood() && left.getItem() != Items.SPIDER_EYE;
                    boolean rightIsFood = right.getItem().isFood() && right.getItem() != Items.SPIDER_EYE;
                    if (lacksFood) {
                        if (rightIsFood && !leftIsFood) {
                            return -1;
                        } else if (leftIsFood && !rightIsFood) {
                            return 1;
                        }
                    }
                    // If both are food, pick the better cost.
                    if (leftIsFood && rightIsFood) {
                        assert left.getItem().getFoodComponent() != null;
                        assert right.getItem().getFoodComponent() != null;
                        int leftCost = left.getItem().getFoodComponent().getHunger() * left.getCount(),
                                rightCost = right.getItem().getFoodComponent().getHunger() * right.getCount();
                        return -1 * (leftCost - rightCost);
                    }

                    // Just discard the one with the smallest quantity, but this doesn't really matter.
                    return left.getCount() - right.getCount();
                });
            } else {
                Debug.logWarning("No unused items to throw away found. Every item is protected.");
            }
        }
        return Optional.empty();
    }

    /**
     * (2026-08-23 别把唯一/最好的镐扔了) 扔了这把镐之后, 背包里是否一把镐
     * 都不剩 (没有同级或更高级的镐)。是 → 不能扔 (否则无法挖矿, 在地狱里
     * 重建要木头 → 死循环)。非镐物品返回 false (可正常走丢物逻辑)。
     */
    public static boolean wouldLeaveNoPickaxe(AltoClef mod, ItemStack stack) {
        if (!(stack.getItem() instanceof PickaxeItem thisPick)) return false;
        int level = thisPick.getMaterial().getMiningLevel();
        for (Slot slot : PlayerSlot.getCurrentScreenSlots()) {
            ItemStack s = getItemStackInSlot(slot);
            if (s.isEmpty() || s == stack) continue;
            if (s.getItem() instanceof PickaxeItem other && other.getMaterial().getMiningLevel() >= level) {
                return false; // 还有同级或更好的镐, 扔这把没问题
            }
        }
        return true; // 扔了就一把镐都不剩
    }

    /**
     * (2026-09-03 真背包槽枚举) 只枚举玩家主背包 inventory slot 0..35, 并经
     * getFromCurrentScreenInventory 按当前屏幕正确映射。任意界面 (背包/工作台/
     * 熔炉/箱子) 都天然排除合成格/输出槽/护甲/副手/光标 —— 与 EnsureFreeCursor
     * SlotTask.findRealBackpackFit 的口径一致 (见 _fix_report cursor 根因)。凡
     * "腾位/合并/找可丢"这些只应作用于真背包的扫描, 一律用它而非全是 PlayerSlot
     * 的 getCurrentScreenSlots() (后者在 2x2 合成界面会把合成格/输出槽也算进来)。
     */
    public static List<Slot> getRealBackpackSlots() {
        List<Slot> result = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            // (2026-09-03 修复: 不能按子类过滤) 各屏幕下真背包区槽的容器类不同 ——
            // 2x2 界面是 PlayerSlot, 3x3 工作台界面是 CraftingTableSlot, 熔炉界面是
            // FurnaceSlotPlayer, 箱子界面是... 一概不是 PlayerSlot 就漏掉(用户实测同类
            // "满包收合成产物走兜底抛丢" 死锁, 因 getSwapableRealBackpackSlot 借不到槽)。
            // 只要经 getFromCurrentScreenInventory(0..35) 映射到的就是玩家背包装具(天然
            // 排除合成格/输出槽/护甲/副手/光标), 与具体子类无关, 无条件收集。
            if (slot != null) {
                result.add(slot);
            }
        }
        return result;
    }

    /**
     * (2026-08-27 用户要求: 可堆叠物品应放一起省空间, 放在丢垃圾之前)
     * 找一对可合并的槽位: A 是部分堆 (未满), B 是同类且能完全装下 A (装下后
     * 不超上限)。合并后 A 腾空、B 变大 → 省一个格子。只扫玩家主背包 (排除
     * 护甲/副手/光标 — 副手保持不动)。返回 {A, B} 或 null (没有可合并的)。
     * 供 ConsolidateInventoryTask 与 EnsureFreeInventorySlotTask 使用。
     */
    public static Slot[] findMergeableStackPair(AltoClef mod) {
        // (2026-09-03 根因修复: 2x2 合成格被当背包, 用户"默认任务反复横跳")
        // 旧实现用 getCurrentScreenSlots() 过滤 instanceof PlayerSlot —— 玩家自带
        // 的 2x2 合成界面里, 合成格 (window 1-4) 与输出槽 (window 0) 都是
        // PlayerSlot 实例, 会被当成背包可合并堆。后果: ConsolidateInventoryTask
        // 反复"合并"合成格里的木板 (不腾背包空格), 而配方一重摆又填满合成格 →
        // findMergeableStackPair 恒 true → EnsureFreeInventorySlotTask 永远走
        // "做合并"而非"丢垃圾"→ 收不进合成台输出 → Craft(2x2)↔Receiving output
        // ↔EnsureFree↔Consolidate 高频横跳空转 (日志 2026-09-03 10:46-47
        // TaskPingPong, [ConsolidateDiag] emptySlots=0 findMergeablePair=true 持续)。
        // 改用 getRealBackpackSlots(): 只枚举真背包 inventory slot 0..35 并经
        // getFromCurrentScreenInventory 映射, 天然排除合成格/输出槽/护甲/副手/光标。
        List<Slot> playerSlots = getRealBackpackSlots();
        for (Slot a : playerSlots) {
            ItemStack sa = getItemStackInSlot(a);
            if (sa.isEmpty() || sa.getCount() >= sa.getMaxCount()) continue;
            for (Slot b : playerSlots) {
                if (a.equals(b)) continue;
                ItemStack sb = getItemStackInSlot(b);
                if (sb.isEmpty() || sb.getItem() != sa.getItem()) continue;
                if (sb.getCount() + sa.getCount() <= sb.getMaxCount()) {
                    return new Slot[]{a, b};
                }
            }
        }
        return null;
    }

    /**
     * (2026-08-23 保底随机丢) 满背包且 getGarbageSlot / getBulkDisposalSlot 都
     * 找不到可丢时, 随机挑一个非护甲/非副手/非关键任务的非空槽位丢弃 — 用户
     * 要求"加个保底, 随便丢东西出去", 否则捡不起掉落物时永久空转卡死。
     * 排除: 护甲槽/副手(盾)、空槽、关键任务物品 (桶/末影之眼/打火石/火焰弹)、
     * 以及扔了就没镐可用的镐。
     */
    public static Slot getRandomThrowableSlot(AltoClef mod) {
        final Item[] NEVER_DISCARD = new Item[]{
                Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.BUCKET,
                Items.ENDER_EYE, Items.ENDER_PEARL, Items.BLAZE_ROD,
                Items.GOLD_INGOT, Items.IRON_INGOT,
                Items.FLINT_AND_STEEL, Items.FIRE_CHARGE,
                Items.SHIELD,
                // (2026-09-19 18:55 用户"bot 意外把药水丢了") 药水保命物资不参与
                // 兜底随机丢 — 与 ItemHelper.canThrowAwayStack 的 PotionItem 保护
                // 同口径 (183919 会话: 满包腾位把防火药水扔了, 下界裸奔)。
                Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION,
                // (2026-08-31 用户"做完木棍把木棍丢出来/背包很空") 木棍是合成基础
                // 材料(工具/火把/床等都要), 刚合成/待用的木棍绝不能被"满包随机丢"
                // 当垃圾扔掉。加到永不随机丢清单。
                Items.STICK
        };
        List<Slot> unprotected = new ArrayList<>();
        List<Slot> protectedSlots = new ArrayList<>();
        // (2026-09-03 同根因收口) 只遍历真背包槽, 不用 getCurrentScreenSlots ——
        // 2x2 合成界面会把合成格/输出槽当背包候选随机丢, 丢了也不腾背包位
        // (与 findMergeableStackPair 同根因)。getRealBackpackSlots 已排除护甲/副手。
        for (Slot slot : getRealBackpackSlots()) {
            ItemStack stack = getItemStackInSlot(slot);
            if (stack.isEmpty()) continue;
            // (2026-09-11 弹回熔断) 冷却中的物品不参与随机丢 — 否则刚熔断的物品
            // 会被"保底随机丢"立刻捡回来再扔, 冷却形同虚设。全冷却时返回 null
            // 由调用方等待 (宁可短暂等待, 不无限扔/捡循环)。
            if (ItemHelper.isThrowOnCooldown(stack.getItem())) continue;
            if (ArrayUtils.contains(NEVER_DISCARD, stack.getItem())) continue;
            // (2026-08-25 镐永不丢, 用户指定) 用户实测随机丢把镐子全丢掉了。
            // 镐是挖矿刚需, 任何镐都不参与随机丢 (原 wouldLeaveNoPickaxe 只
            // 护"唯一镐", 两把镐时仍会丢 — 不够)。剑/斧/铲等工具同理保守保护。
            if (stack.getItem() instanceof net.minecraft.item.PickaxeItem) continue;
            if (stack.getItem() instanceof net.minecraft.item.SwordItem) continue;
            if (stack.getItem() instanceof net.minecraft.item.AxeItem) continue;
            if (stack.getItem() instanceof net.minecraft.item.ShovelItem) continue;
            if (ArrayUtils.contains(ItemHelper.BED, stack.getItem())) continue;
            if (ArrayUtils.contains(ItemHelper.LOG, stack.getItem())) continue;
            if (ArrayUtils.contains(ItemHelper.PLANKS, stack.getItem())) continue;
            // (2026-09-09 用户拍板"除非被随机丢弃") 保底随机丢是最后手段:
            // 此处【去掉】石头保留豁免 — 垃圾/大堆建材两条常规路径都找不到
            // 可丢时, 石头家族允许参与随机丢腾位 (宁丢一组石头, 不永久空转)。
            if (wouldLeaveNoPickaxe(mod, stack)) continue;
            if (ItemHelper.isStackProtected(mod, stack)) {
                protectedSlots.add(slot);
            } else {
                unprotected.add(slot);
            }
        }
        Collections.shuffle(unprotected);
        if (!unprotected.isEmpty()) return unprotected.get(0);
        // (2026-08-31 修"受保护物品也被丢", 用户"背包空还丢木棍/丢东西") 原实现"全被保护时
        // 也从受保护槽随机丢一个 (protectedSlots.get(0))" —— 直接违反"保护"承诺, 把刚合成
        // 的木棍/工具/床这类被任务标记为保护、不能丢的物品也丢出去; 且与背包是否满无关
        // (空背包同样触发, 用户实测为空背包仍丢东西)。受保护物品绝不该为腾位被随机丢:
        // 宁可返回 null 让调用方等待/换策略(可能短暂 STUCK), 也不动任何受保护物品。
        // 用户优先级: 数据保护 > 万一卡一下。
        return null;
    }

    /**
     * @return whether EVERY item target in {@code targetsToMeet} is met in our inventory or conversion slots.
     */
    public static boolean itemTargetsMet(AltoClef mod, ItemTarget ...targetsToMeet) {
        return Arrays.stream(targetsToMeet).allMatch(target -> mod.getItemStorage().getItemCount(target.getMatches()) >= target.getTargetCount());
    }

    /**
     * AVOID using this unless it's the end goal to keep an item in our inventory.
     * @return whether EVERY item target in {@code targetsToMeet} is strictly in our inventory.
     */
    public static boolean itemTargetsMetInventory(AltoClef mod, ItemTarget ...targetsToMeet) {
        return Arrays.stream(targetsToMeet).allMatch(target -> mod.getItemStorage().getItemCountInventoryOnly(target.getMatches()) >= target.getTargetCount());
    }
    /**
     * (2026-09-01 真实背包统一口径, 修"已入包仍反复捡/杀")
     * 直接扫真实玩家背包 (main 36 + offHand + cursor), 不经过 ItemStorageTracker 缓存。
     * 缓存滞后会把 isFinished / 达标门控永远卡在 false, 导致任务永不完成 (羊肉/羊毛
     * 已在背包, 仍反复拾取/杀羊, 甚至 bed↔food 父任务级横跳)。
     * 语义与 itemTargetsMetInventory 相同: 所有匹配物真实计数 >= target。
     */
    public static boolean itemTargetsMetReal(AltoClef mod, ItemTarget ...targetsToMeet) {
        PlayerInventory inv = (mod.getPlayer() == null) ? null : mod.getPlayer().getInventory();
        if (inv == null) return false;
        for (ItemTarget target : targetsToMeet) {
            int count = 0;
            Item[] matches = target.getMatches();
            for (ItemStack stack : inv.main) {
                if (stack == null || stack.isEmpty()) continue;
                for (Item m : matches) {
                    if (stack.getItem() == m) { count += stack.getCount(); break; }
                }
            }
            for (ItemStack stack : inv.armor) {
                if (stack == null || stack.isEmpty()) continue;
                for (Item m : matches) {
                    if (stack.getItem() == m) { count += stack.getCount(); break; }
                }
            }
            ItemStack off = inv.offHand.get(0);
            if (off != null && !off.isEmpty()) {
                for (Item m : matches) {
                    if (off.getItem() == m) { count += off.getCount(); break; }
                }
            }
            // 光标也计入 (已抓到手里就视为已有, 几秒后就能落袋; 与 countItemsInInventoryReal includeCursor 口径一致)
            ItemStack cur = getItemStackInCursorSlot();
            if (cur != null && !cur.isEmpty()) {
                for (Item m : matches) {
                    if (cur.getItem() == m) { count += cur.getCount(); break; }
                }
            }
            if (count < target.getTargetCount()) return false;
        }
        return true;
    }

    /**
     * Same as {@code itemTargetsMetInventory} but it ignores the cursor slot.
     */
    public static boolean itemTargetsMetInventoryNoCursor(AltoClef mod, ItemTarget ...targetsToMeet) {
        ItemStack cursorStack = getItemStackInCursorSlot();
        return Arrays.stream(targetsToMeet).allMatch(target -> {
            int count = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            if (target.matches(cursorStack.getItem()))
                count -= cursorStack.getCount();
            return count >= target.getTargetCount();
        });
    }

    public static boolean isArmorEquipped(AltoClef mod, Item ...any) {
        for (Item item : any) {
            if (item instanceof ArmorItem armor) {
                ItemStack equippedStack = mod.getPlayer().getInventory().getArmorStack(armor.getSlotType().getEntitySlotId());
                if (equippedStack.getItem().equals(item))
                    return true;
            }
        }
        return false;
    }

    /**
     * (2026-09-08 腾位穿甲) 扫真背包里的可穿戴盔甲, 找出"对应护甲槽为空"的, 返回
     * 其背包槽位 —— 供 EnsureFreeInventorySlotTask 腾位级联"先穿甲再丢东西":
     * 穿上后该物品离开背包、护甲槽不占背包格 → 净空出一个背包格且不掉任何物资
     * (用户 2026-09-08: "背包满了有盔甲他穿上也可以")。无则返回 null。
     *
     * (2026-09-17 只认空槽) 旧实现按"护甲值增益"挑最优, 包含【替换已穿护甲】。
     * 但替换时换下来的那件要回背包 —— 净空格 = 0, 腾位目的完全落空; 满包场景下
     * EnsureFreeInventorySlotTask 就永远卡在这条无效路径上: 每轮拿起铁靴 → 被
     * CLEAR 抢断塞回 → 下轮重试, 真垃圾 (gravel) 一次都没被丢, 合成收不下产物,
     * CRAFT/CLEAR 每 3s 互抢 (20260917-085346 实锤: emptySlots=0、
     * findMergeablePair=false、garbageSlot=gravel, 穿甲循环 6+ 轮)。
     * 装备升级另有显式路径 (EquipArmorTask / gear 阶段指定目标), 腾位路径只做
     * "能立刻净空出格子"的事。
     */
    public static Slot findEquippableArmorUpgrade(AltoClef mod) {
        Slot bestSlot = null;
        int bestProt = -1;
        for (Slot slot : getRealBackpackSlots()) {
            ItemStack stack = getItemStackInSlot(slot);
            if (!(stack.getItem() instanceof ArmorItem armor)) continue;
            ItemStack equipped = mod.getPlayer().getInventory()
                    .getArmorStack(armor.getSlotType().getEntitySlotId());
            // 护甲槽已占用: 换下的那件要回背包, 净空格为 0 → 不算腾位。
            if (!equipped.isEmpty()) continue;
            int newProt = armor.getProtection();
            if (newProt > bestProt) {
                bestProt = newProt;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }


    public static int getBuildingMaterialCount(AltoClef mod) {
        return mod.getItemStorage().getItemCount(Arrays.stream(mod.getModSettings().getThrowawayItems(mod, true)).filter(item -> item instanceof BlockItem).toArray(Item[]::new));
    }

    private static boolean isScreenOpenInner(Predicate<ScreenHandler> pNotNull) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null)
            return pNotNull.test(player.currentScreenHandler);
        return false;
    }

    public static boolean isBigCraftingOpen() {
        return isScreenOpenInner(screen -> screen instanceof CraftingScreenHandler);
    }
    public static boolean isPlayerInventoryOpen() {
        return isScreenOpenInner(screen -> screen instanceof PlayerScreenHandler);
    }
    public static boolean isFurnaceOpen() {
        return isScreenOpenInner(screen -> screen instanceof FurnaceScreenHandler);
    }

    public static boolean isArmorEquippedAll(AltoClef mod, Item ...items) {
        return Arrays.stream(items).allMatch(item -> isArmorEquipped(mod, item));
    }

    public static boolean isEquipped(AltoClef mod, Item ...items) {
        return ArrayUtils.contains(items, StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem());
    }

    public static int calculateInventoryFoodScore(AltoClef mod) {
        int result = 0;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            // Spider eyes are technically food but never a food RESERVE
            // (FoodChain refuses to eat them). Counting them here would make
            // "only spider eyes in backpack" look well-stocked while the bot
            // starves. Keep this in sync with FoodChain.calculateFood.
            if (stack.isFood() && stack.getItem() != Items.SPIDER_EYE)
                result += Objects.requireNonNull(stack.getItem().getFoodComponent()).getHunger() * stack.getCount();
        }
        return result;
    }
    public static double calculateInventoryFuelCount(AltoClef mod) {
        double result = 0;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (mod.getModSettings().isSupportedFuel(stack.getItem())) {
                result += ItemHelper.getFuelAmount(stack.getItem()) * stack.getCount();
            }
        }
        // The 2x2 crafting grid and the cursor slot hold OUR items too and can
        // be pulled back at any moment. Excluding them made this count flip
        // around the "need fuel?" threshold whenever a crafting branch moved
        // planks (a supported fuel!) between the grid and the inventory:
        // SmeltInFurnaceTask then ping-ponged forever between "Getting Fuel"
        // and "Getting container item", each branch killing the other's
        // subtree mid-flight (see task_switch.log [TaskPingPong] entries).
        for (PlayerSlot slot : PlayerSlot.CRAFT_INPUT_SLOTS) {
            ItemStack stack = getItemStackInSlot(slot);
            if (mod.getModSettings().isSupportedFuel(stack.getItem())) {
                result += ItemHelper.getFuelAmount(stack.getItem()) * stack.getCount();
            }
        }
        ItemStack cursor = getItemStackInCursorSlot();
        if (mod.getModSettings().isSupportedFuel(cursor.getItem())) {
            result += ItemHelper.getFuelAmount(cursor.getItem()) * cursor.getCount();
        }
        return result;
    }

    /**
     * Returns whether we have the items in our inventory (or currently crafting)
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean hasRecipeMaterialsOrTarget(AltoClef mod, RecipeTarget... targets) {
        return hasRecipeMaterialsOrTarget(mod, 0, targets);
    }

    /**
     * (2026-09-13 "料尽即收工"判据) 只问"还能做出【至少一份】吗", 与目标份数无关。
     *
     * 存在的理由: {@link #hasRecipeMaterialsOrTarget} 内部算的是
     * {@code need = targetCount - 已有数} → {@code materialsPerSlotNeeded = ceil(need / outputCount)}。
     * 当 targetCount 是哨兵值 99999999(`CollectFoodTask` 用"全转换"语义传的值)时
     * need 恒约 1e8 → 该判据**恒判"缺料"** → 拿它当"原料是否耗尽"的判据会在第一个
     * tick 就成立, 任务完全不做就结束。
     *
     * 本方法把份数固定为 1, 语义 = "配方每个非空槽位都能被真实背包(含合成格)满足
     * 至少一份"。这才是"还能不能继续转"的正确问法。
     *
     * 用途: `collect=false` 的合成任务(调用方声明"料在我手上, 本任务不负责补料")
     * 在原料耗尽后目标(哨兵值)永不达成, 必须靠本判据给出出口。
     */
    public static boolean hasMaterialsForOneCraft(AltoClef mod, RecipeTarget... targets) {
        return hasRecipeMaterialsOrTarget(mod, 1, targets);
    }

    /**
     * 公共实现。{@code forcedCrafts > 0} 时忽略 targetCount, 直接按该份数校验材料。
     */
    private static boolean hasRecipeMaterialsOrTarget(AltoClef mod, int forcedCrafts, RecipeTarget... targets) {
        HashMap<Integer, Integer> slotUsedCounts = new HashMap<>();
        for (RecipeTarget target : targets) {
            CraftingRecipe recipe = target.getRecipe();
            int materialsPerSlotNeeded;
            if (forcedCrafts > 0) {
                materialsPerSlotNeeded = forcedCrafts;
            } else {
                int need = 0;
                if (target.getOutputItem() != null) {
                    need = target.getTargetCount();
                    // (2026-08-25 真实背包兜底) 输出已有数改用真实背包计数 — tracker
                    // 缓存陈旧会误判"缺材料"→ 派发收集 → 挖洞横跳 (用户实测做金靴子:
                    // 背包有足够金粒却 Collecting 36 nuggets 挖洞, 09:14 日志)。
                    need -= countItemInInventoryReal(mod, target.getOutputItem());
                }
                // need holds how many items we need to CRAFT
                // However, a crafting recipe can output more than 1 of an item.
                materialsPerSlotNeeded = (int) Math.ceil((float) need / target.getRecipe().outputCount());
            }
            for (int i = 0; i < materialsPerSlotNeeded; ++i) {
                for (int slot = 0; slot < recipe.getSlotCount(); ++slot) {
                    ItemTarget needs = recipe.getSlot(slot);

                    // Satisfied by default.
                    if (needs == null || needs.isEmpty()) continue;

                    // do NOT include craft or armor slots. This would include the OUTPUT (which we DO NOT want)
                    // (2026-08-25 真实背包兜底) 材料槽改用真实背包扫描 (见上)。
                    List<Slot> slotsWithItem = new ArrayList<>(getItemSlotsInInventoryReal(mod, needs.getMatches()));

                    // Other slots may have our crafting supplies.
                    ScreenHandler screen = mod.getPlayer().currentScreenHandler;
                    if (screen instanceof PlayerScreenHandler || screen instanceof CraftingScreenHandler) {
                        // (2026-09-10 台格属主闸门) 合成格里的物品只计入"属主配方"
                        // 的材料。无属主标记时维持旧口径全计 (2x2 中间合成没有标记,
                        // 行为不变)。否则镐合成放进台格的 3 颗钻会被胸甲缺料判定
                        // 当成自己的料 → 抢切镐合成 → 又缺料 → 死循环 (19:42 场次
                        // tasks.log t=99~523 四轮实锤)。
                        if (adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.isCraftGridOwnedBy(target)) {
                            // Check crafting slots
                            boolean bigCrafting = (screen instanceof CraftingScreenHandler);
                            boolean bigRecipe = recipe.isBig();
                            for (int craftSlotIndex = 0; craftSlotIndex < (bigCrafting ? 9 : 4); ++craftSlotIndex) {
                                Slot craftSlot = bigCrafting ? CraftingTableSlot.getInputSlot(craftSlotIndex, bigRecipe) : PlayerSlot.getCraftInputSlot(craftSlotIndex);
                                ItemStack stack = StorageHelper.getItemStackInSlot(craftSlot);
                                if (needs.matches(stack.getItem())) {
                                    slotsWithItem.add(craftSlot);
                                }
                            }
                        }
                    }

                    // Try to satisfy THIS slot.
                    boolean satisfied = false;
                    for (Slot checkSlot : slotsWithItem) {
                        int windowSlot = checkSlot.getWindowSlot();
                        if (!slotUsedCounts.containsKey(windowSlot)) {
                            slotUsedCounts.put(windowSlot, 0);
                        }
                        int usedFromSlot = slotUsedCounts.get(windowSlot);
                        ItemStack stack = StorageHelper.getItemStackInSlot(checkSlot);

                        if (usedFromSlot < stack.getCount()) {
                            slotUsedCounts.put(windowSlot, slotUsedCounts.get(windowSlot) + 1);
                            //Debug.logMessage("Satisfied " + slot + " with " + checkInvSlot);
                            satisfied = true;
                            break;
                        }
                    }

                    if (!satisfied) {
                        //Debug.logMessage("FAILED TO SATISFY " + slot + " : needs " + needs);
                        // We couldn't satisfy this slot in either the inventory or crafting output.
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static boolean hasCataloguedItem(AltoClef mod, String cataloguedName) {
        return mod.getItemStorage().hasItem(TaskCatalogue.getItemMatches(cataloguedName));
    }

    /**
     * There are slots in our inventory that can't be accessed by containers
     *
     * Mainly the crafting + armor + shield slot.
     *
     * @return A slot of {@code withItem} that is inaccessible to open containers, or {@code Optional.empty} if there
     * are none.
     */
    public static Optional<Slot> getFilledInventorySlotInaccessibleToContainer(AltoClef mod, ItemTarget withItem) {
        // First check if we have anything within our regular inventory.
        if (!StorageHelper.isPlayerInventoryOpen() || withItem.isEmpty() || itemTargetsMetInventory(mod, withItem)) {
            return Optional.empty();
        }
        // 修复 (ping-pong 2026-08-21 12:13 + 12:23 两轮):
        // 活动合成任务的【输出或材料】出现在合成格/光标是正常中间状态。
        // 原版豁免只认合成"输出"且只覆盖光标槽——收集原木任务(目标=原木)
        // 与做斧链里的木板合成(材料=原木)并存时, 斧链刚把原木放进 2x2
        // 合成格(slot 1), 就被 INACCESSIBLE_PLAYER_SLOTS 循环判为"不可
        // 访问"而强行搬回背包, 打断合成 → 斧链重新放原木 → 永久横跳。
        // 豁免必须放在所有槽位检查之前, 同时覆盖合成格与光标。
        if (isItemUsedByActiveCrafting(mod, withItem)) {
            return Optional.empty();
        }
        // Then check our "invalid" slots for our item.
        for (Slot slot : INACCESSIBLE_PLAYER_SLOTS) {
            if (withItem.matches(getItemStackInSlot(slot).getItem())) {
                return Optional.of(slot);
            }
        }
        // Consider Cursor slot only if we have our player inventory open AND we're not crafting it...
        if (StorageHelper.isPlayerInventoryOpen() && withItem.matches(getItemStackInCursorSlot().getItem())) {
            if (!isItemUsedByActiveCrafting(mod, withItem)) {
                return Optional.of(CursorSlot.SLOT);
            }
        }
        return Optional.empty();
    }

    /**
     * 物品是否正被某个活动中的合成任务使用（作为输出或配方材料）。
     * 用于豁免"不可访问槽位"搬运：合成进行期间其材料/输出出现在
     * 合成格或光标槽是正常中间状态，不应被强行搬回背包。
     */
    private static boolean isItemUsedByActiveCrafting(AltoClef mod, ItemTarget withItem) {
        adris.altoclef.tasksystem.Task userTask = mod.getUserTaskChain().getCurrentTask();
        if (userTask == null) return false;
        return userTask.thisOrChildSatisfies(task -> {
            if (task instanceof CraftInInventoryTask invCraft) {
                if (invCraft.getRecipeTarget() == null) return false;
                if (withItem.matches(invCraft.getRecipeTarget().getOutputItem())) {
                    return true;
                }
                CraftingRecipe recipe = invCraft.getRecipeTarget().getRecipe();
                if (recipe != null) {
                    for (ItemTarget slotTarget : recipe.getSlots()) {
                        if (slotTarget == null || slotTarget.isEmpty()) continue;
                        for (Item ingredient : withItem.getMatches()) {
                            if (slotTarget.matches(ingredient)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        });
    }

    public static boolean isItemInaccessibleToContainer(AltoClef mod, ItemTarget item) {
        return getFilledInventorySlotInaccessibleToContainer(mod, item).isPresent();
    }

    public static ItemStack getItemStackInCursorSlot() {
        if (MinecraftClient.getInstance().player != null) {
            if (MinecraftClient.getInstance().player.currentScreenHandler != null) {
                return MinecraftClient.getInstance().player.currentScreenHandler.getCursorStack().copy();
            }
        }
        return ItemStack.EMPTY;
    }

    public static int getBrewingStandFuel() {
        if (MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.currentScreenHandler instanceof BrewingStandScreenHandler stand)
            return getBrewingStandFuel(stand);
        return -1;
    }

    public static int getBrewingStandFuel(BrewingStandScreenHandler handler) {
        return handler.getFuel();
    }

    public static double getFurnaceFuel(AbstractFurnaceScreenHandler handler) {
        PropertyDelegate d = ((AbstractFurnaceScreenHandlerAccessor) handler).getPropertyDelegate();
        return (double) d.get(0) / 200.0;
    }
    public static double getFurnaceFuel() {
        if (MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.currentScreenHandler instanceof AbstractFurnaceScreenHandler furnace)
            return getFurnaceFuel(furnace);
        return -1;
    }
    public static double getFurnaceCookPercent(AbstractFurnaceScreenHandler handler) {
        return (double) handler.getCookProgress() / 24.0;
    }
    public static double getFurnaceCookPercent() {
        if (MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.currentScreenHandler instanceof AbstractFurnaceScreenHandler furnace)
            return getFurnaceCookPercent(furnace);
        return -1;
    }

    public static ItemTarget[] getAllInventoryItemsAsTargets(Predicate<Slot> accept) {
        HashMap<Item, Integer> counts = new HashMap<>();
        for (Slot slot : Slot.getCurrentScreenSlots()) {
            if (slot.isSlotInPlayerInventory() && accept.test(slot)) {
                ItemStack stack = getItemStackInSlot(slot);
                if (!stack.isEmpty()) {
                    counts.put(stack.getItem(), counts.getOrDefault(stack.getItem(), 0) + stack.getCount());
                }
            }
        }
        ItemTarget[] results = new ItemTarget[counts.size()];
        int i = 0;
        for (Item item : counts.keySet()) {
            results[i++] = new ItemTarget(item, counts.get(item));
        }
        return results;
    }

    public static void instantFillRecipeViaBook(AltoClef mod, CraftingRecipe recipe, Item output, boolean craftAll) {
        Optional<Recipe> recipeToSend = JankCraftingRecipeMapping.getMinecraftMappedRecipe(recipe, output);
        if (recipeToSend.isPresent()) {
            mod.getController().clickRecipe(MinecraftClient.getInstance().player.currentScreenHandler.syncId, recipeToSend.get(), craftAll);
        } else {
            Debug.logError("Could not find recipe stored in Minecraft!! Recipe: " + recipe + " with output " + output);
        }
    }
}
