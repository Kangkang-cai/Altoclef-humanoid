package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.WoodType;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PotionItem;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.TagKey;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import java.util.*;

/**
 * Helper functions and definitions for useful groupings of items
 */
public class ItemHelper {

    /**
     * (2026-08-23 防火药水) 背包里是否有防火药水 (饮用型或喷溅型)。
     * 药水的种类在 NBT 里 (Items.POTION 是同一个物品, 靠 PotionUtil 区分),
     * 普通 ItemTarget 无法区分 — 必须逐 stack 检查药水 NBT。
     */
    public static boolean isFireResPotionStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.POTION && stack.getItem() != Items.SPLASH_POTION) return false;
        return PotionUtil.getPotion(stack) == Potions.FIRE_RESISTANCE;
    }

    /**
     * (2026-08-23 防火药水) 玩家背包 (main + offhand) 中防火药水总数。
     * 直接扫描背包, 不依赖 ItemStorageTracker 缓存 (缓存统计 bug 已在
     * 原木/结构方块上踩过两次坑)。
     */
    public static int countFireResPotions(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getPlayer().getInventory() == null) return 0;
        int count = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (isFireResPotionStack(stack)) count += stack.getCount();
        }
        ItemStack offhand = mod.getPlayer().getInventory().offHand.get(0);
        if (isFireResPotionStack(offhand)) count += offhand.getCount();
        return count;
    }

    /**
     * (2026-08-23 防火药水) 第一个防火药水所在的背包槽位 (无则 null)。
     * PlayerInventory.main 的 index 映射: 0-8 热栏 (window 36-44),
     * 9-35 背包 (window 9-35)。
     */
    public static Slot getFireResPotionSlot(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getPlayer().getInventory() == null) return null;
        for (int i = 0; i < mod.getPlayer().getInventory().main.size(); ++i) {
            ItemStack stack = mod.getPlayer().getInventory().main.get(i);
            if (isFireResPotionStack(stack)) {
                return Slot.getFromCurrentScreenInventory(i);
            }
        }
        ItemStack offhand = mod.getPlayer().getInventory().offHand.get(0);
        if (isFireResPotionStack(offhand)) return adris.altoclef.util.slots.PlayerSlot.OFFHAND_SLOT;
        return null;
    }

    public static String stripItemName(Item item) {
        String[] possibilities = new String[]{"item.minecraft.", "block.minecraft."};
        for (String possible : possibilities) {
            if (item.getTranslationKey().startsWith(possible)) {
                return item.getTranslationKey().substring(possible.length());
            }
        }
        return item.getTranslationKey();
    }

    public static Item[] blocksToItems(Block[] blocks) {
        Item[] result = new Item[blocks.length];
        for (int i = 0; i < blocks.length; ++i) {
            result[i] = blocks[i].asItem();
        }
        return result;
    }

    public static Block[] itemsToBlocks(Item[] items) {
        ArrayList<Block> result = new ArrayList<>();
        for (Item item : items) {
            if (item instanceof BlockItem) {
                Block b = Block.getBlockFromItem(item);
                if (b != null && b != Blocks.AIR) {
                    result.add(b);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    public static final Item[] PLANKS = new Item[]{Items.ACACIA_PLANKS, Items.BIRCH_PLANKS, Items.CRIMSON_PLANKS, Items.DARK_OAK_PLANKS, Items.OAK_PLANKS, Items.JUNGLE_PLANKS, Items.SPRUCE_PLANKS, Items.WARPED_PLANKS};
    public static final Item[] LEAVES = new Item[]{Items.ACACIA_LEAVES, Items.BIRCH_LEAVES, Items.DARK_OAK_LEAVES, Items.OAK_LEAVES, Items.JUNGLE_LEAVES, Items.SPRUCE_LEAVES};
    public static final Item[] WOOD = new Item[]{Items.ACACIA_WOOD, Items.BIRCH_WOOD, Items.CRIMSON_HYPHAE, Items.DARK_OAK_WOOD, Items.OAK_WOOD, Items.JUNGLE_WOOD, Items.SPRUCE_WOOD, Items.WARPED_HYPHAE};
    public static final Item[] WOOD_BUTTON = new Item[]{Items.ACACIA_BUTTON, Items.BIRCH_BUTTON, Items.CRIMSON_BUTTON, Items.DARK_OAK_BUTTON, Items.OAK_BUTTON, Items.JUNGLE_BUTTON, Items.SPRUCE_BUTTON, Items.WARPED_BUTTON};
    public static final Item[] WOOD_SIGN = new Item[]{Items.ACACIA_SIGN, Items.BIRCH_SIGN, Items.CRIMSON_SIGN, Items.DARK_OAK_SIGN, Items.OAK_SIGN, Items.JUNGLE_SIGN, Items.SPRUCE_SIGN, Items.WARPED_SIGN};
    public static final Item[] WOOD_PRESSURE_PLATE = new Item[]{Items.ACACIA_PRESSURE_PLATE, Items.BIRCH_PRESSURE_PLATE, Items.CRIMSON_PRESSURE_PLATE, Items.DARK_OAK_PRESSURE_PLATE, Items.OAK_PRESSURE_PLATE, Items.JUNGLE_PRESSURE_PLATE, Items.SPRUCE_PRESSURE_PLATE, Items.WARPED_PRESSURE_PLATE};
    public static final Item[] WOOD_FENCE = new Item[]{Items.ACACIA_FENCE, Items.BIRCH_FENCE, Items.DARK_OAK_FENCE, Items.OAK_FENCE, Items.JUNGLE_FENCE, Items.SPRUCE_FENCE, Items.CRIMSON_FENCE, Items.WARPED_FENCE};
    public static final Item[] WOOD_FENCE_GATE = new Item[]{Items.ACACIA_FENCE_GATE, Items.BIRCH_FENCE_GATE, Items.DARK_OAK_FENCE_GATE, Items.OAK_FENCE_GATE, Items.JUNGLE_FENCE_GATE, Items.SPRUCE_FENCE_GATE, Items.CRIMSON_FENCE_GATE, Items.WARPED_FENCE_GATE};
    public static final Item[] WOOD_BOAT = new Item[]{Items.ACACIA_BOAT, Items.BIRCH_BOAT, Items.DARK_OAK_BOAT, Items.OAK_BOAT, Items.JUNGLE_BOAT, Items.SPRUCE_BOAT};
    public static final Item[] WOOD_DOOR = new Item[]{Items.ACACIA_DOOR, Items.BIRCH_DOOR, Items.CRIMSON_DOOR, Items.DARK_OAK_DOOR, Items.OAK_DOOR, Items.JUNGLE_DOOR, Items.SPRUCE_DOOR, Items.WARPED_DOOR};
    public static final Item[] WOOD_SLAB = new Item[]{Items.ACACIA_SLAB, Items.BIRCH_SLAB, Items.CRIMSON_SLAB, Items.DARK_OAK_SLAB, Items.OAK_SLAB, Items.JUNGLE_SLAB, Items.SPRUCE_SLAB, Items.WARPED_SLAB};
    public static final Item[] WOOD_STAIRS = new Item[]{Items.ACACIA_STAIRS, Items.BIRCH_STAIRS, Items.CRIMSON_STAIRS, Items.DARK_OAK_STAIRS, Items.OAK_STAIRS, Items.JUNGLE_STAIRS, Items.SPRUCE_STAIRS, Items.WARPED_STAIRS};
    public static final Item[] WOOD_TRAPDOOR = new Item[]{Items.ACACIA_TRAPDOOR, Items.BIRCH_TRAPDOOR, Items.CRIMSON_TRAPDOOR, Items.DARK_OAK_TRAPDOOR, Items.OAK_TRAPDOOR, Items.JUNGLE_TRAPDOOR, Items.SPRUCE_TRAPDOOR, Items.WARPED_TRAPDOOR};
    public static final Item[] LOG = new Item[]{Items.ACACIA_LOG, Items.BIRCH_LOG, Items.DARK_OAK_LOG, Items.OAK_LOG, Items.JUNGLE_LOG, Items.SPRUCE_LOG,
            Items.ACACIA_WOOD, Items.BIRCH_WOOD, Items.DARK_OAK_WOOD, Items.OAK_WOOD, Items.JUNGLE_WOOD, Items.SPRUCE_WOOD,
            Items.STRIPPED_ACACIA_LOG, Items.STRIPPED_BIRCH_LOG, Items.STRIPPED_DARK_OAK_LOG, Items.STRIPPED_OAK_LOG, Items.STRIPPED_JUNGLE_LOG, Items.STRIPPED_SPRUCE_LOG,
            Items.STRIPPED_ACACIA_WOOD, Items.STRIPPED_BIRCH_WOOD, Items.STRIPPED_DARK_OAK_WOOD, Items.STRIPPED_OAK_WOOD, Items.STRIPPED_JUNGLE_WOOD, Items.STRIPPED_SPRUCE_WOOD,
            Items.CRIMSON_STEM, Items.WARPED_STEM, Items.CRIMSON_HYPHAE, Items.WARPED_HYPHAE, Items.STRIPPED_CRIMSON_STEM, Items.STRIPPED_WARPED_STEM, Items.STRIPPED_CRIMSON_HYPHAE, Items.STRIPPED_WARPED_HYPHAE};

    public static final Item[] DYE = new Item[]{Items.WHITE_DYE, Items.BLACK_DYE, Items.BLUE_DYE, Items.BROWN_DYE, Items.CYAN_DYE, Items.GRAY_DYE, Items.GREEN_DYE, Items.LIGHT_BLUE_DYE, Items.LIGHT_GRAY_DYE, Items.LIME_DYE, Items.MAGENTA_DYE, Items.ORANGE_DYE, Items.PINK_DYE, Items.PURPLE_DYE, Items.RED_DYE, Items.YELLOW_DYE};
    public static final Item[] WOOL = new Item[]{Items.WHITE_WOOL, Items.BLACK_WOOL, Items.BLUE_WOOL, Items.BROWN_WOOL, Items.CYAN_WOOL, Items.GRAY_WOOL, Items.GREEN_WOOL, Items.LIGHT_BLUE_WOOL, Items.LIGHT_GRAY_WOOL, Items.LIME_WOOL, Items.MAGENTA_WOOL, Items.ORANGE_WOOL, Items.PINK_WOOL, Items.PURPLE_WOOL, Items.RED_WOOL, Items.YELLOW_WOOL};
    public static final Item[] BED = new Item[]{Items.WHITE_BED, Items.BLACK_BED, Items.BLUE_BED, Items.BROWN_BED, Items.CYAN_BED, Items.GRAY_BED, Items.GREEN_BED, Items.LIGHT_BLUE_BED, Items.LIGHT_GRAY_BED, Items.LIME_BED, Items.MAGENTA_BED, Items.ORANGE_BED, Items.PINK_BED, Items.PURPLE_BED, Items.RED_BED, Items.YELLOW_BED};

    /**
     * 贵重掉落物（2026-08-21 用户原则：重要物品要持续感知）：这些物品的
     * 掉落物实体即使捡拾暂时失败（满背包/够不着）也不允许永久拉黑——
     * 满背包会随腾位恢复，永久拉黑 = 主动丢弃。只有距离过远（实体自然
     * 卸载出渲染距离）才真正放弃。Settings.importantItems（钻石/下界合金
     * 等）和行为保护物品在调用处另行叠加。
     */
    private static final Item[] VALUABLE_DROP_ITEMS = {
            Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT,
            Items.ENDER_PEARL, Items.BLAZE_ROD, Items.GHAST_TEAR,
            Items.IRON_SWORD, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL,
            Items.STONE_SWORD, Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL,
            Items.BUCKET, Items.FLINT_AND_STEEL, Items.SHIELD
    };

    public static boolean isValuableDropItem(Item item) {
        for (Item check : VALUABLE_DROP_ITEMS) {
            if (check == item) return true;
        }
        for (Item bed : BED) {
            if (bed == item) return true;
        }
        return false;
    }

    // =====================================================================
    // (2026-09-11 丢弃弹回熔断器) "扔→弹回→原版自动拾取→再扔"死循环拦截。
    // 现场 2026-09-11 11:12:53-11:13:27 实锤: 丢泥土方向"开阔"(首个实心块 4 格外),
    // 物品撞墙反弹落点 1-2 格内, 被原版自动拾取塞回刚腾出的槽位, 2s 一轮循环
    // 17 次直到用户干预。原版拾取代码层拦不住, 只能在丢弃层熔断:
    //   - 每次真正扔出时 recordCursorThrown(item, dropPos)。
    //   - 同一物品 8s 内重新出现在光标上 = 弹回被重拾实锤 →
    //     notifyGarbageReturned: 第 1 次弹回→扔前先离开上次落点 ≥4 格;
    //     第 2 次弹回→进入 60s 触发层冷却, 丢弃候选选择器跳过该物品
    //     (getGarbageSlot/getBulkDisposalSlot/getRandomThrowableSlot),
    //     打断循环; 冷却期过后自然恢复丢弃能力。
    // 状态放 ItemHelper(util 层): StorageHelper(选择器) 查询, ThrowCursorTask
    // (tasks 层) 写入 — 避免 util 反向依赖 tasks。
    // =====================================================================
    private static final long THROW_BOUNCE_WINDOW_MS = 8000;
    private static final long THROW_COOLDOWN_MS = 60000;
    // 弹回次数达到该值 → 触发冷却 (即第 3 次自动重扔是最后一次)
    private static final int THROW_MAX_BOUNCES = 2;
    // item -> long[3]: {lastThrowMs, bounceCount, cooldownUntilMs}
    private static final Map<Item, long[]> _cursorThrowMemory = new HashMap<>();
    // item -> 上次扔出时玩家坐标 (弹回后"先离落点"用)
    private static final Map<Item, BlockPos> _lastThrowDropPos = new HashMap<>();

    /** ThrowCursorTask 真正执行丢出时调用 (每次扔都记录)。 */
    public static void recordCursorThrown(Item item, BlockPos dropPos) {
        if (item == null) return;
        long now = System.currentTimeMillis();
        synchronized (_cursorThrowMemory) {
            long[] mem = _cursorThrowMemory.computeIfAbsent(item, k -> new long[3]);
            mem[0] = now;
            _lastThrowDropPos.put(item, dropPos == null ? null : dropPos.toImmutable());
        }
    }

    /**
     * 光标物品在 8s 内刚被扔过 = 弹回被重拾。ThrowCursorTask.onStart 检测到时调用:
     * 递增弹回计数 (超窗则重新计数), 达到上限时设置 60s 冷却。
     */
    public static void notifyGarbageReturned(Item item) {
        if (item == null) return;
        long now = System.currentTimeMillis();
        synchronized (_cursorThrowMemory) {
            long[] mem = _cursorThrowMemory.computeIfAbsent(item, k -> new long[3]);
            mem[1] = (now - mem[0] <= THROW_BOUNCE_WINDOW_MS) ? mem[1] + 1 : 1;
            if (mem[1] >= THROW_MAX_BOUNCES) {
                mem[2] = now + THROW_COOLDOWN_MS;
            }
        }
    }

    /** 该物品是否在丢弃冷却中 (冷却期内丢弃候选选择器跳过它)。 */
    public static boolean isThrowOnCooldown(Item item) {
        if (item == null) return false;
        synchronized (_cursorThrowMemory) {
            long[] mem = _cursorThrowMemory.get(item);
            return mem != null && System.currentTimeMillis() < mem[2];
        }
    }

    /** 上次扔该物品时的玩家坐标 (无记录返回 null)。 */
    public static BlockPos getLastThrowDropPos(Item item) {
        if (item == null) return null;
        synchronized (_cursorThrowMemory) {
            return _lastThrowDropPos.get(item);
        }
    }

    /** 上次扔该物品的时间戳 (无记录返回 0)。 */
    public static long getLastCursorThrowMs(Item item) {
        if (item == null) return 0;
        synchronized (_cursorThrowMemory) {
            long[] mem = _cursorThrowMemory.get(item);
            return mem == null ? 0 : mem[0];
        }
    }

    public static long getThrowBounceWindowMs() {
        return THROW_BOUNCE_WINDOW_MS;
    }

    // =====================================================================
    // (2026-09-21 地牢阶段垃圾升级, 用户指定) 进入地牢任务后 (BeatMinecraft2Task
    // 地牢锁定 _ranStrongholdLocator 置位起), 金粒/金锭/生金 (猪灵交易货币,
    // 猪灵阶段已结束)、岩浆桶(搭门液体, 门已完成)、打火石(点门刚需, 门已点燃)
    // 全部降级为垃圾 —— 这批物品此前被 importantItems(金锭/打火石)、桶保护
    // (岩浆桶)、行为保护(搭门任务 addProtectedItems) 三重覆盖, 永远进不了
    // 垃圾候选, 常年占背包。
    // 旗子由 BeatMinecraft2Task.onTick 每拍按实例状态刷新、onStop 清零,
    // 不跨世界残留 (新任务实例 _ranStrongholdLocator=false, 首拍即回落)。
    // =====================================================================
    private static volatile boolean s_dungeonPhaseActive = false;

    /** 地牢(要塞定位/末地)阶段是否已激活。 */
    public static boolean isDungeonPhaseActive() {
        return s_dungeonPhaseActive;
    }

    /** BeatMinecraft2Task 每 tick 按 _ranStrongholdLocator 刷新; onStop 清零。 */
    public static void setDungeonPhaseActive(boolean active) {
        s_dungeonPhaseActive = active;
    }

    /**
     * 该物品在当前是否属于"地牢阶段垃圾" (金粒/金锭/生金/岩浆桶/打火石)。
     * 仅在地牢阶段激活时为 true; 非地牢阶段一律 false, 保护名单原样生效。
     */
    public static boolean isDungeonJunkItem(Item item) {
        if (!s_dungeonPhaseActive || item == null) return false;
        // (2026-09-29 用户拍板加烈焰棒) 要塞定位器跑过后末影之眼材料已凑够,
        // 烈焰棒与金粒/金锭同样失去用途, 一并解锁。
        return item == Items.GOLD_NUGGET || item == Items.GOLD_INGOT
                || item == Items.RAW_GOLD || item == Items.LAVA_BUCKET
                || item == Items.FLINT_AND_STEEL
                || item == Items.BLAZE_ROD;
    }

    public static final Item[] CARPET = new Item[]{Items.WHITE_CARPET, Items.BLACK_CARPET, Items.BLUE_CARPET, Items.BROWN_CARPET, Items.CYAN_CARPET, Items.GRAY_CARPET, Items.GREEN_CARPET, Items.LIGHT_BLUE_CARPET, Items.LIGHT_GRAY_CARPET, Items.LIME_CARPET, Items.MAGENTA_CARPET, Items.ORANGE_CARPET, Items.PINK_CARPET, Items.PURPLE_CARPET, Items.RED_CARPET, Items.YELLOW_CARPET};

    public static final Item[] SHULKER_BOXES = new Item[]{Items.WHITE_SHULKER_BOX, Items.BLACK_SHULKER_BOX, Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.GRAY_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.LIGHT_BLUE_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.PINK_SHULKER_BOX, Items.PURPLE_SHULKER_BOX, Items.RED_SHULKER_BOX, Items.YELLOW_SHULKER_BOX};

    public static final Item[] FLOWER = new Item[]{Items.ALLIUM, Items.AZURE_BLUET, Items.BLUE_ORCHID, Items.CORNFLOWER, Items.DANDELION, Items.LILAC, Items.LILY_OF_THE_VALLEY, Items.ORANGE_TULIP, Items.OXEYE_DAISY, Items.PINK_TULIP, Items.POPPY, Items.PEONY, Items.RED_TULIP, Items.ROSE_BUSH, Items.SUNFLOWER, Items.WHITE_TULIP};

    public static final Item[] LEATHER_ARMORS = new Item[]{Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_HELMET, Items.LEATHER_BOOTS};
    public static final Item[] GOLDEN_ARMORS = new Item[]{Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_HELMET, Items.GOLDEN_BOOTS};
    public static final Item[] IRON_ARMORS = new Item[]{Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_HELMET, Items.IRON_BOOTS};
    public static final Item[] DIAMOND_ARMORS = new Item[]{Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS};
    public static final Item[] NETHERITE_ARMORS = new Item[]{Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_HELMET, Items.NETHERITE_BOOTS};

    public static final Item[] WOODEN_TOOLS = new Item[]{Items.WOODEN_PICKAXE, Items.WOODEN_SHOVEL, Items.WOODEN_SWORD, Items.WOODEN_AXE, Items.WOODEN_HOE};
    public static final Item[] STONE_TOOLS = new Item[]{Items.STONE_PICKAXE, Items.STONE_SHOVEL, Items.STONE_SWORD, Items.STONE_AXE, Items.STONE_HOE};
    public static final Item[] IRON_TOOLS = new Item[]{Items.IRON_PICKAXE, Items.IRON_SHOVEL, Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_HOE};
    public static final Item[] GOLDEN_TOOLS = new Item[]{Items.GOLDEN_PICKAXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_SWORD, Items.GOLDEN_AXE, Items.GOLDEN_HOE};
    public static final Item[] DIAMOND_TOOLS = new Item[]{Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_HOE};
    public static final Item[] NETHERITE_TOOLS = new Item[]{Items.NETHERITE_PICKAXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_SWORD, Items.NETHERITE_AXE, Items.NETHERITE_HOE};
    
    public static final Block[] WOOD_SIGNS_ALL = new Block[]{Blocks.ACACIA_SIGN, Blocks.BIRCH_SIGN, Blocks.DARK_OAK_SIGN, Blocks.OAK_SIGN, Blocks.JUNGLE_SIGN, Blocks.SPRUCE_SIGN, Blocks.ACACIA_WALL_SIGN, Blocks.BIRCH_WALL_SIGN, Blocks.DARK_OAK_WALL_SIGN, Blocks.OAK_WALL_SIGN, Blocks.JUNGLE_WALL_SIGN, Blocks.SPRUCE_WALL_SIGN};

    private static final Map<Item, Item> _logToPlanks = new HashMap<>() {
        {
            put(Items.ACACIA_LOG, Items.ACACIA_PLANKS);
            put(Items.BIRCH_LOG, Items.BIRCH_PLANKS);
            put(Items.CRIMSON_STEM, Items.CRIMSON_PLANKS);
            put(Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS);
            put(Items.OAK_LOG, Items.OAK_PLANKS);
            put(Items.JUNGLE_LOG, Items.JUNGLE_PLANKS);
            put(Items.SPRUCE_LOG, Items.SPRUCE_PLANKS);
            put(Items.WARPED_STEM, Items.WARPED_PLANKS);
            put(Items.STRIPPED_ACACIA_LOG, Items.ACACIA_PLANKS);
            put(Items.STRIPPED_BIRCH_LOG, Items.BIRCH_PLANKS);
            put(Items.STRIPPED_CRIMSON_STEM, Items.CRIMSON_PLANKS);
            put(Items.STRIPPED_DARK_OAK_LOG, Items.DARK_OAK_PLANKS);
            put(Items.STRIPPED_OAK_LOG, Items.OAK_PLANKS);
            put(Items.STRIPPED_JUNGLE_LOG, Items.JUNGLE_PLANKS);
            put(Items.STRIPPED_SPRUCE_LOG, Items.SPRUCE_PLANKS);
            put(Items.STRIPPED_WARPED_STEM, Items.WARPED_PLANKS);
            put(Items.ACACIA_WOOD, Items.ACACIA_PLANKS);
            put(Items.BIRCH_WOOD, Items.BIRCH_PLANKS);
            put(Items.CRIMSON_HYPHAE, Items.CRIMSON_PLANKS);
            put(Items.DARK_OAK_WOOD, Items.DARK_OAK_PLANKS);
            put(Items.OAK_WOOD, Items.OAK_PLANKS);
            put(Items.JUNGLE_WOOD, Items.JUNGLE_PLANKS);
            put(Items.SPRUCE_WOOD, Items.SPRUCE_PLANKS);
            put(Items.WARPED_HYPHAE, Items.WARPED_PLANKS);
            put(Items.STRIPPED_ACACIA_WOOD, Items.ACACIA_PLANKS);
            put(Items.STRIPPED_BIRCH_WOOD, Items.BIRCH_PLANKS);
            put(Items.STRIPPED_CRIMSON_HYPHAE, Items.CRIMSON_PLANKS);
            put(Items.STRIPPED_DARK_OAK_WOOD, Items.DARK_OAK_PLANKS);
            put(Items.STRIPPED_OAK_WOOD, Items.OAK_PLANKS);
            put(Items.STRIPPED_JUNGLE_WOOD, Items.JUNGLE_PLANKS);
            put(Items.STRIPPED_SPRUCE_WOOD, Items.SPRUCE_PLANKS);
            put(Items.STRIPPED_WARPED_HYPHAE, Items.WARPED_PLANKS);
        }
    };
    private static final Map<Item, Item> _planksToLogs = new HashMap<>() {
        {
            put(Items.ACACIA_PLANKS, Items.ACACIA_LOG);
            put(Items.BIRCH_PLANKS, Items.BIRCH_LOG);
            put(Items.CRIMSON_PLANKS, Items.CRIMSON_STEM);
            put(Items.DARK_OAK_PLANKS, Items.DARK_OAK_LOG);
            put(Items.OAK_PLANKS, Items.OAK_LOG);
            put(Items.JUNGLE_PLANKS, Items.JUNGLE_LOG);
            put(Items.SPRUCE_PLANKS, Items.SPRUCE_LOG);
            put(Items.WARPED_PLANKS, Items.WARPED_STEM);
        }
    };
    // This is kinda jank ngl
    private static final Map<MapColor, ColorfulItems> _colorMap = new HashMap<MapColor, ColorfulItems>() {
        {
            p(DyeColor.RED, "red", Items.RED_DYE, Items.RED_WOOL, Items.RED_BED, Items.RED_CARPET, Items.RED_STAINED_GLASS, Items.RED_STAINED_GLASS_PANE, Items.RED_TERRACOTTA, Items.RED_GLAZED_TERRACOTTA, Items.RED_CONCRETE, Items.RED_CONCRETE_POWDER, Items.RED_BANNER, Items.RED_SHULKER_BOX, Blocks.RED_WALL_BANNER);
            p(DyeColor.WHITE, "white", Items.WHITE_DYE, Items.WHITE_WOOL, Items.WHITE_BED, Items.WHITE_CARPET, Items.WHITE_STAINED_GLASS, Items.WHITE_STAINED_GLASS_PANE, Items.WHITE_TERRACOTTA, Items.WHITE_GLAZED_TERRACOTTA, Items.WHITE_CONCRETE, Items.WHITE_CONCRETE_POWDER, Items.WHITE_BANNER, Items.WHITE_SHULKER_BOX, Blocks.WHITE_WALL_BANNER);
            p(DyeColor.BLACK, "black", Items.BLACK_DYE, Items.BLACK_WOOL, Items.BLACK_BED, Items.BLACK_CARPET, Items.BLACK_STAINED_GLASS, Items.BLACK_STAINED_GLASS_PANE, Items.BLACK_TERRACOTTA, Items.BLACK_GLAZED_TERRACOTTA, Items.BLACK_CONCRETE, Items.BLACK_CONCRETE_POWDER, Items.BLACK_BANNER, Items.BLACK_SHULKER_BOX, Blocks.BLACK_WALL_BANNER);
            p(DyeColor.BLUE, "blue", Items.BLUE_DYE, Items.BLUE_WOOL, Items.BLUE_BED, Items.BLUE_CARPET, Items.BLUE_STAINED_GLASS, Items.BLUE_STAINED_GLASS_PANE, Items.BLUE_TERRACOTTA, Items.BLUE_GLAZED_TERRACOTTA, Items.BLUE_CONCRETE, Items.BLUE_CONCRETE_POWDER, Items.BLUE_BANNER, Items.BLUE_SHULKER_BOX, Blocks.BLUE_WALL_BANNER);
            p(DyeColor.BROWN, "brown", Items.BROWN_DYE, Items.BROWN_WOOL, Items.BROWN_BED, Items.BROWN_CARPET, Items.BROWN_STAINED_GLASS, Items.BROWN_STAINED_GLASS_PANE, Items.BROWN_TERRACOTTA, Items.BROWN_GLAZED_TERRACOTTA, Items.BROWN_CONCRETE, Items.BROWN_CONCRETE_POWDER, Items.BROWN_BANNER, Items.BROWN_SHULKER_BOX, Blocks.BROWN_WALL_BANNER);
            p(DyeColor.CYAN, "cyan", Items.CYAN_DYE, Items.CYAN_WOOL, Items.CYAN_BED, Items.CYAN_CARPET, Items.CYAN_STAINED_GLASS, Items.CYAN_STAINED_GLASS_PANE, Items.CYAN_TERRACOTTA, Items.CYAN_GLAZED_TERRACOTTA, Items.CYAN_CONCRETE, Items.CYAN_CONCRETE_POWDER, Items.CYAN_BANNER, Items.CYAN_SHULKER_BOX, Blocks.CYAN_WALL_BANNER);
            p(DyeColor.GRAY, "gray", Items.GRAY_DYE, Items.GRAY_WOOL, Items.GRAY_BED, Items.GRAY_CARPET, Items.GRAY_STAINED_GLASS, Items.GRAY_STAINED_GLASS_PANE, Items.GRAY_TERRACOTTA, Items.GRAY_GLAZED_TERRACOTTA, Items.GRAY_CONCRETE, Items.GRAY_CONCRETE_POWDER, Items.GRAY_BANNER, Items.GRAY_SHULKER_BOX, Blocks.GRAY_WALL_BANNER);
            p(DyeColor.GREEN, "green", Items.GREEN_DYE, Items.GREEN_WOOL, Items.GREEN_BED, Items.GREEN_CARPET, Items.GREEN_STAINED_GLASS, Items.GREEN_STAINED_GLASS_PANE, Items.GREEN_TERRACOTTA, Items.GREEN_GLAZED_TERRACOTTA, Items.GREEN_CONCRETE, Items.GREEN_CONCRETE_POWDER, Items.GREEN_BANNER, Items.GREEN_SHULKER_BOX, Blocks.GREEN_WALL_BANNER);
            p(DyeColor.LIGHT_BLUE, "light_blue", Items.LIGHT_BLUE_DYE, Items.LIGHT_BLUE_WOOL, Items.LIGHT_BLUE_BED, Items.LIGHT_BLUE_CARPET, Items.LIGHT_BLUE_STAINED_GLASS, Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.LIGHT_BLUE_TERRACOTTA, Items.LIGHT_BLUE_GLAZED_TERRACOTTA, Items.LIGHT_BLUE_CONCRETE, Items.LIGHT_BLUE_CONCRETE_POWDER, Items.LIGHT_BLUE_BANNER, Items.LIGHT_BLUE_SHULKER_BOX, Blocks.LIGHT_BLUE_WALL_BANNER);
            p(DyeColor.LIGHT_GRAY, "light_gray", Items.LIGHT_GRAY_DYE, Items.LIGHT_GRAY_WOOL, Items.LIGHT_GRAY_BED, Items.LIGHT_GRAY_CARPET, Items.LIGHT_GRAY_STAINED_GLASS, Items.LIGHT_GRAY_STAINED_GLASS_PANE, Items.LIGHT_GRAY_TERRACOTTA, Items.LIGHT_GRAY_GLAZED_TERRACOTTA, Items.LIGHT_GRAY_CONCRETE, Items.LIGHT_GRAY_CONCRETE_POWDER, Items.LIGHT_GRAY_BANNER, Items.LIGHT_GRAY_SHULKER_BOX, Blocks.LIGHT_GRAY_WALL_BANNER);
            p(DyeColor.LIME, "lime", Items.LIME_DYE, Items.LIME_WOOL, Items.LIME_BED, Items.LIME_CARPET, Items.LIME_STAINED_GLASS, Items.LIME_STAINED_GLASS_PANE, Items.LIME_TERRACOTTA, Items.LIME_GLAZED_TERRACOTTA, Items.LIME_CONCRETE, Items.LIME_CONCRETE_POWDER, Items.LIME_BANNER, Items.LIME_SHULKER_BOX, Blocks.LIME_WALL_BANNER);
            p(DyeColor.MAGENTA, "magenta", Items.MAGENTA_DYE, Items.MAGENTA_WOOL, Items.MAGENTA_BED, Items.MAGENTA_CARPET, Items.MAGENTA_STAINED_GLASS, Items.MAGENTA_STAINED_GLASS_PANE, Items.MAGENTA_TERRACOTTA, Items.MAGENTA_GLAZED_TERRACOTTA, Items.MAGENTA_CONCRETE, Items.MAGENTA_CONCRETE_POWDER, Items.MAGENTA_BANNER, Items.MAGENTA_SHULKER_BOX, Blocks.MAGENTA_WALL_BANNER);
            p(DyeColor.ORANGE, "orange", Items.ORANGE_DYE, Items.ORANGE_WOOL, Items.ORANGE_BED, Items.ORANGE_CARPET, Items.ORANGE_STAINED_GLASS, Items.ORANGE_STAINED_GLASS_PANE, Items.ORANGE_TERRACOTTA, Items.ORANGE_GLAZED_TERRACOTTA, Items.ORANGE_CONCRETE, Items.ORANGE_CONCRETE_POWDER, Items.ORANGE_BANNER, Items.ORANGE_SHULKER_BOX, Blocks.ORANGE_WALL_BANNER);
            p(DyeColor.PINK, "pink", Items.PINK_DYE, Items.PINK_WOOL, Items.PINK_BED, Items.PINK_CARPET, Items.PINK_STAINED_GLASS, Items.PINK_STAINED_GLASS_PANE, Items.PINK_TERRACOTTA, Items.PINK_GLAZED_TERRACOTTA, Items.PINK_CONCRETE, Items.PINK_CONCRETE_POWDER, Items.PINK_BANNER, Items.PINK_SHULKER_BOX, Blocks.PINK_WALL_BANNER);
            p(DyeColor.PURPLE, "purple", Items.PURPLE_DYE, Items.PURPLE_WOOL, Items.PURPLE_BED, Items.PURPLE_CARPET, Items.PURPLE_STAINED_GLASS, Items.PURPLE_STAINED_GLASS_PANE, Items.PURPLE_TERRACOTTA, Items.PURPLE_GLAZED_TERRACOTTA, Items.PURPLE_CONCRETE, Items.PURPLE_CONCRETE_POWDER, Items.PURPLE_BANNER, Items.PURPLE_SHULKER_BOX, Blocks.PURPLE_WALL_BANNER);
            p(DyeColor.RED, "red", Items.RED_DYE, Items.RED_WOOL, Items.RED_BED, Items.RED_CARPET, Items.RED_STAINED_GLASS, Items.RED_STAINED_GLASS_PANE, Items.RED_TERRACOTTA, Items.RED_GLAZED_TERRACOTTA, Items.RED_CONCRETE, Items.RED_CONCRETE_POWDER, Items.RED_BANNER, Items.RED_SHULKER_BOX, Blocks.RED_WALL_BANNER);
            p(DyeColor.YELLOW, "yellow", Items.YELLOW_DYE, Items.YELLOW_WOOL, Items.YELLOW_BED, Items.YELLOW_CARPET, Items.YELLOW_STAINED_GLASS, Items.YELLOW_STAINED_GLASS_PANE, Items.YELLOW_TERRACOTTA, Items.YELLOW_GLAZED_TERRACOTTA, Items.YELLOW_CONCRETE, Items.YELLOW_CONCRETE_POWDER, Items.YELLOW_BANNER, Items.YELLOW_SHULKER_BOX, Blocks.YELLOW_WALL_BANNER);
        }

        void p(DyeColor color, String colorName, Item dye, Item wool, Item bed, Item carpet, Item stainedGlass, Item stainedGlassPane, Item terracotta, Item glazedTerracotta, Item concrete, Item concretePowder, Item banner, Item shulker, Block wallBanner) {
            put(color.getMapColor(), new ColorfulItems(color, colorName, dye, wool, bed, carpet, stainedGlass, stainedGlassPane, terracotta, glazedTerracotta, concrete, concretePowder, banner, shulker, wallBanner));
        }
    };
    private static final Map<WoodType, WoodItems> _woodMap = new HashMap<WoodType, WoodItems>() {
        {
            p(WoodType.ACACIA, "acacia", Items.ACACIA_PLANKS, Items.ACACIA_LOG, Items.STRIPPED_ACACIA_LOG, Items.STRIPPED_ACACIA_WOOD, Items.ACACIA_WOOD, Items.ACACIA_SIGN, Items.ACACIA_DOOR, Items.ACACIA_BUTTON, Items.ACACIA_STAIRS, Items.ACACIA_SLAB, Items.ACACIA_FENCE, Items.ACACIA_FENCE_GATE, Items.ACACIA_BOAT, Items.ACACIA_SAPLING, Items.ACACIA_LEAVES, Items.ACACIA_PRESSURE_PLATE, Items.ACACIA_TRAPDOOR);
            p(WoodType.BIRCH, "birch", Items.BIRCH_PLANKS, Items.BIRCH_LOG, Items.STRIPPED_BIRCH_LOG, Items.STRIPPED_BIRCH_WOOD, Items.BIRCH_WOOD, Items.BIRCH_SIGN, Items.BIRCH_DOOR, Items.BIRCH_BUTTON, Items.BIRCH_STAIRS, Items.BIRCH_SLAB, Items.BIRCH_FENCE, Items.BIRCH_FENCE_GATE, Items.BIRCH_BOAT, Items.BIRCH_SAPLING, Items.BIRCH_LEAVES, Items.BIRCH_PRESSURE_PLATE, Items.BIRCH_TRAPDOOR);
            p(WoodType.CRIMSON, "crimson", Items.CRIMSON_PLANKS, Items.CRIMSON_STEM, Items.STRIPPED_CRIMSON_STEM, Items.STRIPPED_CRIMSON_HYPHAE, Items.CRIMSON_HYPHAE, Items.CRIMSON_SIGN, Items.CRIMSON_DOOR, Items.CRIMSON_BUTTON, Items.CRIMSON_STAIRS, Items.CRIMSON_SLAB, Items.CRIMSON_FENCE, Items.CRIMSON_FENCE_GATE, null, Items.CRIMSON_FUNGUS, null, Items.CRIMSON_PRESSURE_PLATE, Items.CRIMSON_TRAPDOOR);
            p(WoodType.DARK_OAK, "dark_oak", Items.DARK_OAK_PLANKS, Items.DARK_OAK_LOG, Items.STRIPPED_DARK_OAK_LOG, Items.STRIPPED_DARK_OAK_WOOD, Items.DARK_OAK_WOOD, Items.DARK_OAK_SIGN, Items.DARK_OAK_DOOR, Items.DARK_OAK_BUTTON, Items.DARK_OAK_STAIRS, Items.DARK_OAK_SLAB, Items.DARK_OAK_FENCE, Items.DARK_OAK_FENCE_GATE, Items.DARK_OAK_BOAT, Items.DARK_OAK_SAPLING, Items.DARK_OAK_LEAVES, Items.DARK_OAK_PRESSURE_PLATE, Items.DARK_OAK_TRAPDOOR);
            p(WoodType.OAK, "oak", Items.OAK_PLANKS, Items.OAK_LOG, Items.STRIPPED_OAK_LOG, Items.STRIPPED_OAK_WOOD, Items.OAK_WOOD, Items.OAK_SIGN, Items.OAK_DOOR, Items.OAK_BUTTON, Items.OAK_STAIRS, Items.OAK_SLAB, Items.OAK_FENCE, Items.OAK_FENCE_GATE, Items.OAK_BOAT, Items.OAK_SAPLING, Items.OAK_LEAVES, Items.OAK_PRESSURE_PLATE, Items.OAK_TRAPDOOR);
            p(WoodType.JUNGLE, "jungle", Items.JUNGLE_PLANKS, Items.JUNGLE_LOG, Items.STRIPPED_JUNGLE_LOG, Items.STRIPPED_JUNGLE_WOOD, Items.JUNGLE_WOOD, Items.JUNGLE_SIGN, Items.JUNGLE_DOOR, Items.JUNGLE_BUTTON, Items.JUNGLE_STAIRS, Items.JUNGLE_SLAB, Items.JUNGLE_FENCE, Items.JUNGLE_FENCE_GATE, Items.JUNGLE_BOAT, Items.JUNGLE_SAPLING, Items.JUNGLE_LEAVES, Items.JUNGLE_PRESSURE_PLATE, Items.JUNGLE_TRAPDOOR);
            p(WoodType.SPRUCE, "spruce", Items.SPRUCE_PLANKS, Items.SPRUCE_LOG, Items.STRIPPED_SPRUCE_LOG, Items.STRIPPED_SPRUCE_WOOD, Items.SPRUCE_WOOD, Items.SPRUCE_SIGN, Items.SPRUCE_DOOR, Items.SPRUCE_BUTTON, Items.SPRUCE_STAIRS, Items.SPRUCE_SLAB, Items.SPRUCE_FENCE, Items.SPRUCE_FENCE_GATE, Items.SPRUCE_BOAT, Items.SPRUCE_SAPLING, Items.SPRUCE_LEAVES, Items.SPRUCE_PRESSURE_PLATE, Items.SPRUCE_TRAPDOOR);
            p(WoodType.WARPED, "warped", Items.WARPED_PLANKS, Items.WARPED_STEM, Items.STRIPPED_WARPED_STEM, Items.STRIPPED_WARPED_HYPHAE, Items.WARPED_HYPHAE, Items.WARPED_SIGN, Items.WARPED_DOOR, Items.WARPED_BUTTON, Items.WARPED_STAIRS, Items.WARPED_SLAB, Items.WARPED_FENCE, Items.WARPED_FENCE_GATE, null, Items.WARPED_FUNGUS, null, Items.WARPED_PRESSURE_PLATE, Items.WARPED_TRAPDOOR);
        }

        void p(WoodType type, String prefix, Item planks, Item log, Item strippedLog, Item strippedWood, Item wood, Item sign, Item door, Item button, Item stairs, Item slab, Item fence, Item fenceGate, Item boat, Item sapling, Item leaves, Item pressurePlate, Item trapdoor) {
            put(type, new WoodItems(prefix, planks, log, strippedLog, strippedWood, wood, sign, door, button, stairs, slab, fence, fenceGate, boat, sapling, leaves, pressurePlate, trapdoor));
        }
    };

    private static final HashMap<Item, Item> _cookableFoodMap = new HashMap<>() {
        {
            put(Items.PORKCHOP, Items.COOKED_PORKCHOP);
            put(Items.BEEF, Items.COOKED_BEEF);
            put(Items.CHICKEN, Items.COOKED_CHICKEN); // chicken is best meat, fight me
            put(Items.MUTTON, Items.COOKED_MUTTON);
            put(Items.RABBIT, Items.COOKED_RABBIT);
            put(Items.SALMON, Items.COOKED_SALMON);
            put(Items.COD, Items.COOKED_COD);
        }
    };
    public static final Item[] RAW_FOODS = _cookableFoodMap.keySet().toArray(Item[]::new);

    /* Logs:
        ACACIA
        BIRCH
        CRIMSON
        DARK_OAK
        OAK
        JUNGLE
        SPRUCE
        WARPED
     */

    /* Colors:
        WHITE
        BLACK
        BLUE
        BROWN
        CYAN
        GRAY
        GREEN
        LIGHT_BLUE
        LIGHT_GRAY
        LIME
        MAGENTA
        ORANGE
        PINK
        PURPLE
        RED
        YELLOW
     */

    public static Item logToPlanks(Item logItem) {
        return _logToPlanks.getOrDefault(logItem, null);
    }

    public static Item planksToLog(Item plankItem) {
        return _planksToLogs.getOrDefault(plankItem, null);
    }

    public static ColorfulItems getColorfulItems(MapColor color) {
        return _colorMap.get(color);
    }

    public static ColorfulItems getColorfulItems(DyeColor color) {
        return getColorfulItems(color.getMapColor());
    }

    public static Collection<ColorfulItems> getColorfulItems() {
        return _colorMap.values();
    }

    public static WoodItems getWoodItems(WoodType type) {
        return _woodMap.get(type);
    }

    public static Collection<WoodItems> getWoodItems() {
        return _woodMap.values();
    }

    public static Optional<Item> getCookedFood(Item rawFood) {
        return Optional.ofNullable(_cookableFoodMap.getOrDefault(rawFood, null));
    }

    public boolean isRawFood(Item item) {
        return _cookableFoodMap.containsKey(item);
    }

    public static String trimItemName(String name) {
        if (name.startsWith("block.minecraft.")) {
            name = name.substring("block.minecraft.".length());
        } else if (name.startsWith("item.minecraft.")) {
            name = name.substring("item.minecraft.".length());
        }
        return name;
    }

    public static boolean areShearsEffective(Block b) {
        return
                //b.getRegistryEntry().streamTags().anyMatch(t -> t ==
                // BlockTags.LEAVES); should also work... but is slower
                b instanceof LeavesBlock
                        || b == Blocks.COBWEB
                        || b == Blocks.GRASS
                        || b == Blocks.TALL_GRASS
                        || b == Blocks.LILY_PAD
                        || b == Blocks.FERN
                        || b == Blocks.DEAD_BUSH
                        || b ==Blocks.VINE
                        || b == Blocks.TRIPWIRE
                        || isOfBlockType(b, BlockTags.WOOL)
                        || b == Blocks.NETHER_SPROUTS;
    }

    public static boolean isOfBlockType(Block b, TagKey<Block> tag) {
        return Registry.BLOCK.getKey(b).map(e -> Registry.BLOCK.entryOf(e).streamTags().anyMatch(t -> t == tag)).orElse(false);
    }

    public static class ColorfulItems {
        public DyeColor color;
        public String colorName;
        public Item dye;
        public Item wool;
        public Item bed;
        public Item carpet;
        public Item stainedGlass;
        public Item stainedGlassPane;
        public Item terracotta;
        public Item glazedTerracotta;
        public Item concrete;
        public Item concretePowder;
        public Item banner;
        public Item shulker;
        public Block wallBanner;

        public ColorfulItems(DyeColor color, String colorName, Item dye, Item wool, Item bed, Item carpet, Item stainedGlass, Item stainedGlassPane, Item terracotta, Item glazedTerracotta, Item concrete, Item concretePowder, Item banner, Item shulker, Block wallBanner) {
            this.color = color;
            this.colorName = colorName;
            this.dye = dye;
            this.wool = wool;
            this.bed = bed;
            this.carpet = carpet;
            this.stainedGlass = stainedGlass;
            this.stainedGlassPane = stainedGlassPane;
            this.terracotta = terracotta;
            this.glazedTerracotta = glazedTerracotta;
            this.concrete = concrete;
            this.concretePowder = concretePowder;
            this.banner = banner;
            this.shulker = shulker;
            this.wallBanner = wallBanner;
        }
    }

    public static class WoodItems {
        public String prefix;
        public Item planks;
        public Item log;
        public Item strippedLog;
        public Item strippedWood;
        public Item wood;
        public Item sign;
        public Item door;
        public Item button;
        public Item stairs;
        public Item slab;
        public Item fence;
        public Item fenceGate;
        public Item boat;
        public Item sapling;
        public Item leaves;
        public Item pressurePlate;
        public Item trapdoor;

        public WoodItems(String prefix, Item planks, Item log, Item strippedLog, Item strippedWood, Item wood, Item sign, Item door, Item button, Item stairs, Item slab, Item fence, Item fenceGate, Item boat, Item sapling, Item leaves, Item pressurePlate, Item trapdoor) {
            this.prefix = prefix;
            this.planks = planks;
            this.log = log;
            this.strippedLog = strippedLog;
            this.strippedWood = strippedWood;
            this.wood = wood;
            this.sign = sign;
            this.door = door;
            this.button = button;
            this.stairs = stairs;
            this.slab = slab;
            this.fence = fence;
            this.fenceGate = fenceGate;
            this.boat = boat;
            this.sapling = sapling;
            this.leaves = leaves;
            this.pressurePlate = pressurePlate;
            this.trapdoor = trapdoor;
        }

        public boolean isNetherWood() {
            return planks == Items.CRIMSON_PLANKS || planks == Items.WARPED_PLANKS;
        }
    }

    public static boolean isStackProtected(AltoClef mod, ItemStack stack) {
        if (stack.hasCustomName() && mod.getModSettings().getDontThrowAwayCustomNameItems())
            return true;
        return mod.getBehaviour().isProtected(stack.getItem()) || mod.getModSettings().isImportant(stack.getItem());
    }

    public static boolean canThrowAwayStack(AltoClef mod, ItemStack stack) {
        // Can't throw away empty stacks!
        if (stack.isEmpty())
            return false;
        Item item = stack.getItem();
        // (2026-09-21 地牢阶段垃圾, 用户"进地牢后金粒/金锭/生金/岩浆桶/打火石
        // 都可以丢, 不然太占背包") 这批物品在金锭/打火石(importantItems)、
        // 岩浆桶(桶保护)名单里, 主垃圾路径永远锁不到; 地牢阶段在此显式放行,
        // 优先级高于一切保护 (自定义名物品仍不丢)。
        if (isDungeonJunkItem(item)
                && !(stack.hasCustomName() && mod.getModSettings().getDontThrowAwayCustomNameItems())) {
            return true;
        }
        if (isStackProtected(mod, stack))
            return false;
        // (2026-08-27 用户要求: 水桶/盾牌绝不能丢) 打末影龙/保命关键物品,
        // 与 StorageHelper.getRandomThrowableSlot 的 NEVER_DISCARD 清单一致。
        // 旧代码这条丢垃圾路径 (getGarbageSlot) 只查 isStackProtected (按任务
        // 保护), 非相关任务激活时水桶/盾牌会被当垃圾丢掉 (用户实测)。
        if (item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET
                || item == Items.BUCKET || item == Items.SHIELD) {
            return false;
        }
        // (2026-09-19 18:55 用户"bot 意外把药水丢了") 药水(饮用/喷溅/滞留)绝不能丢:
        // 183919 会话实锤 —— 背包满(393 食物)时 EnsureFreeInventorySlotTask 腾位,
        // 药水进了光标被 ThrowCursorTask 点窗口外(slot=-999)扔掉, 下界打烈焰人
        // 直接裸奔。药水=保命物资, 与水桶/盾牌同级, 主垃圾路径在此统一堵上。
        if (item instanceof PotionItem) {
            return false;
        }
        // (2026-09-02 木棍主路径保护, 用户"做铁镐前把木棍当垃圾丢了→没木棍卡住")
        // 木棍是 2x2 合成基础材料(工具/火把/床都要)。旧代码只在兜底随机丢
        // StorageHelper.getRandomThrowableSlot 的 NEVER_DISCARD 里保护了 STICK,
        // 但这 【canThrowAwayStack 是主丢垃圾路径 getGarbageSlot / 合成抛光标
        // (EnsureFreeCursorSlotTask→ThrowCursorTask) 共用的单一收口】 却漏了它 —
        // throwAwayUnusedItems=true 时木棍被当普通垃圾/光标垃圾丢光, 想做铁镐
        // 时没木棍直接卡死 (用户实测)。在此统一保护, 与 getRandomThrowableSlot
        // 清单口径一致, 两路径都堵上。
        // (2026-08-25 保留方块, 用户指定) 圆石/深板岩圆石至少保留一组 (64):
        // 当前背包数量 ≤ 保留量时不丢 (合成熔炉/石镐/石剑常用, 丢光了会卡死)。
        if (item == Items.STICK)
            return false;
        // (2026-09-24 最后镐保护) 丢掉它之后背包再无同级/更好镐 → 不可丢。
        // 现场 20260924-202519: 满包腾位 (446 圆石) 把唯一木镐选为垃圾 (09-03
        // "直接丢名单"含木/石工具, 但它是当时唯一镐), 光标滞留 8s 被
        // PlayerInteractionFixChain 抛出 → pick-escape 捡回后热栏装备又被豁免
        // 挡住 → 爬升通道手挖石头 3.5 分钟 (hand=water_bucket 铁证)。本方法是
        // getGarbageSlot / 光标抛丢 / 合成抛光标共用的单一收口, 在此统一堵上;
        // 与 getRandomThrowableSlot 的"镐永不丢"(08-25 用户) 同族 — 有同级或
        // 更好镐时仍可丢 (尊重 09-03 名单释放背包位的本意)。
        if (adris.altoclef.util.helpers.StorageHelper.wouldLeaveNoPickaxe(mod, stack))
            return false;
        if (isReservedBlockUnderLimit(mod, stack))
            return false;
        return mod.getModSettings().isThrowaway(stack.getItem()) || mod.getModSettings().shouldThrowawayUnusedItems();
    }

    /**
     * (2026-08-25 保留方块) 圆石(cobblestone)/深板岩圆石(cobbled_deepslate)
     * 若背包数量 ≤ Settings.reservedBuildingBlockCount (默认 64, 一组) 则视为
     * 不可丢 — 保证 bot 始终留一组可合成熔炉/石工具。超过保留量才允许丢弃。
     * 供 canThrowAwayStack 与 StorageHelper.getRandomThrowableSlot 共用。
     * (2026-09-09 修订) ① 石头家族【合并计数】而非按物品单算 — 各留 30 个
     * 也凑不出一组 64。② 纳入黑石(blackstone): 下界没有圆石, 黑石就是下界的
     * "圆石"(可合成石镐/熔炉), 用户实测下界石头丢光后挖不到圆石死循环
     * (14:25-14:27 tasks log: Collect Cobblestone → 挖竖井 no ores found ×3 轮)。
     */
    public static boolean isReservedBlockUnderLimit(AltoClef mod, ItemStack stack) {
        Item item = stack.getItem();
        if (item != Items.COBBLESTONE && item != Items.COBBLED_DEEPSLATE && item != Items.BLACKSTONE) return false;
        int have = StorageHelper.countItemsInInventoryReal(mod, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
        return have <= mod.getModSettings().getReservedBuildingBlockCount();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean canStackTogether(ItemStack from, ItemStack to) {
        if (to.isEmpty() && from.getCount() <= from.getMaxCount())
            return true;
        return to.getItem().equals(from.getItem()) && (from.getCount() + to.getCount() < to.getMaxCount());
    }

    private static Map<Item, Integer> _fuelTimeMap = null;
    private static Map<Item, Integer> getFuelTimeMap() {
        if (_fuelTimeMap == null) {
            _fuelTimeMap = AbstractFurnaceBlockEntity.createFuelTimeMap();
        }
        return _fuelTimeMap;
    }
    public static double getFuelAmount(Item... items) {
        double total = 0;
        for (Item item : items) {
            if (getFuelTimeMap().containsKey(item)) {
                int timeTicks = getFuelTimeMap().get(item);
                // 300 ticks of wood -> 1.5 operations
                // 200 ticks -> 1 operation
                total += (double) timeTicks / 200.0;
            }
        }
        return total;
    }
    public static double getFuelAmount(ItemStack stack) {
        return getFuelAmount(stack.getItem()) * stack.getCount();
    }

    public static boolean isFuel(Item item) {
        return getFuelTimeMap().containsKey(item);
    }

}
