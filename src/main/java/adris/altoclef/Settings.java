package adris.altoclef;

import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.util.BlockRange;
import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.serialization.IFailableConfigFile;
import adris.altoclef.util.serialization.ItemDeserializer;
import adris.altoclef.util.serialization.ItemSerializer;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.Streams;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The settings file, loaded and used across the codebase.
 *
 * Each setting is documented.
 */
@SuppressWarnings("ALL")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class Settings implements IFailableConfigFile {

    public static final String SETTINGS_PATH = "altoclef_settings.json";

    // Internal only.
    // If settings failed to load, this will be set to warn the user.
    @JsonIgnore
    private transient boolean _failedToLoad = false;

    //////////////////////////////////////////////////////////////////////////////////////////
    ////////** BEGIN SETTINGS w/ COMMENTS **//////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////

    /**
     * If true, text will appear on the top left showing the current
     * task chain.
     */
    private boolean showTaskChains = true;

    /**
     * If true, all warning logs will be disabled.
     *
     * NOT RECOMMENDED, as it will make debugging more difficult.
     * But if you know what you're doing, go nuts.
     */
    private boolean hideAllWarningLogs = false;

    /**
     * The prefix for commands (ex. @gamer )
     */
    private String commandPrefix = "@";

    /**
     * When logging to chat, will prepend this to each log.
     */
    private String chatLogPrefix = "[Alto Clef] ";

    /**
     * If true, will show a timer.
     */
    private boolean showTimer = false;

    /**
     * The delay between moving items for crafting/furnace/any kind of inventory movement.
     */
    private float containerItemMoveDelay = 0.2f;

    /**
     * If true, use Minecraft's crafting recipe book to place items into
     * the crafting table (should be much faster as it's almost instant)
     *
     * If false, will place items in each slot manually (the original way)
     */
    private boolean useCraftingBookToCraft = true;

    /**
     * If a dropped resource item is further than this from the player, don't pick it up.
     * <p>
     * -1 (or less than 0) to disable.
     */
    private float resourcePickupDropRange = -1;


    /**
     * minumum amount of food to have in the inventory.
     * if we have less food than this value the bot will go pickup some more.
     */
    private int minimumFoodAllowed = 0;


    /**
     * amount of food to collect when the food in inventory
     * is lower than the value of minimumFoodAllowed
     */
    private int foodUnitsToCollect = 0;


    /**
     * Chests are cached for their contents.
     * <p>
     * If the bot is collecting a resource and finds a chest within this range,
     * it will grab the resource from the chest.
     * <p>
     * Set this to 0 to disable chest pickups.
     * <p>
     * Don't set this too high, as the bot will prioritize chests even if the resource
     * is easily accesible now.
     */
    private float resourceChestLocateRange = 500;

    /**
     * Some block resources are by default obtained through non-mining means.
     * Crafting tables for example, are normally crafted using planks.
     * <p>
     * However, if the block resource is found within this range it may be mined first.
     * <p>
     * Set this to 0 to disable this feature
     * (keep in mind, this will not affect blocks like "dirt" and "cobblestone"
     * that can only be obtained through mining)
     * <p>
     * Set this to -1 to ALWAYS mine a block if it's catalogued.
     * This is not recommended. For example, if the bot happens to track a
     * crafting table 10000 blocks away, and it then tries obtaining one
     * it will travel 10000 blocks to mine that table, even if it finds
     * itself in a forest where the wood is abundant.
     */
    private float resourceMineRange = 100;

    /**
     * When going to the nearest chest to store items, the bot may normally
     * dig up dungeons constantly. If this is set to true, the bot will
     * search around each chest to make sure it's not in a dungeon.
     */
    private boolean avoidSearchingDungeonChests = true;

    /**
     * Will ignore mining/interacting with blocks that are BELOW an ocean (in an ocean biome and below y = 64)
     *
     * This is mainly here because alto-clef does NOT know how to deal with oceans
     */
    private boolean avoidOceanBlocks = true;

    /**
     * How close we must be to attack/interact with an entity.
     * 6 works well for singleplayer
     * 4 works better on more restrictive multiplayer servers
     * (2026-08-30 4→3.2 近战收紧, 用户"靠近距离不够/经常空打"。4 格比 MC 近战
     * 真实命中(~3格)远, 导致 3~4 格挥剑空挥。3.2 贴近真实射程, 空挥带几乎消失,
     * 靠近距离(≈reach-1≈2.2)也更近。)
     */
    private float entityReachRange = 3.2f;

    /**
     * Before grabbing ANYTHING, get a pickaxe.
     * <p>
     * Will help with navigation as sometimes dropped items will be underground,
     * but this behaviour only makes sense in regular minecraft worlds.
     */
    private boolean collectPickaxeFirst = true;

    /**
     * If set to true, crops broken when collecting food will be replanted.
     */
    private boolean replantCrops = true;

    /**
     * Uses killaura to move mobs away and performs survival moves including:
     * - Running away from hostile mobs when your health is low
     * - Run away from creepers about to blow up
     * - Avoid wither skeletons and other really dangerous mobs
     * - Attempting to dodge arrows and other projectiles
     */
    private boolean mobDefense = true;

    /**
     * Defines how force field behaves when "mobDefense" is set to true.
     * Note that the force field is not here to KILL mobs, but PUSH THEM AWAY.
     * <p>
     * <p>
     * Strategies:
     * <p>
     * FASTEST: All hostiles are attacked at every possible moment, every frame.
     * DELAY: Closest hostile is attacked with a sword when your attack is charged up
     * SMART: Closest hostile is attacked at max every 0.2 seconds.
     * OFF: Off
     */
    private KillAura.Strategy forceFieldStrategy = KillAura.Strategy.SMART;

    /**
     * Only applies if mobDefense is on.
     * <p>
     * If enabled, will attempt to dodge all incoming projectiles
     */
    private boolean dodgeProjectiles = true;

    /**
     * (2026-09-07 塔式逃生, 用户设计) 逃跑失败升级时, 若追兵为纯近战
     * (无远程怪/无苦力怕/有可放置方块), 优先尝试"逃到空旷 → 搭高 3 块
     * 悬空方块 → 塔上进食 → 塔上打怪"的确定性逃生 (TowerEscapeTask),
     * 替代"继续挖通道" (被追杀时挖洞来不及且导航会钻墙)。
     * 设为 false 关闭, 行为回到旧升级链 (shelter → dig → long retreat)。
     */
    private boolean towerEscapeEnabled = true;

    /**
     * If any hostile mob is "close" to our bot for this long,
     * consider it a nuissance and defeat it if we have enough gear.
     *
     * Skeletons + Witches get a much larger range.
     *
     * Set to zero to make the bot always kill/run away from any nearby hostiles.
     */
    private double killAnnoyingHostileWhenCloseForSeconds = 12;

    /**
     * Skeletons and large groups of mobs are a huge pain.
     * <p>
     * With this set to true, the bot may either
     * kill or run away from mobs that stay too close for too long.
     */
    private boolean killOrAvoidAnnoyingHostiles = true;

    /**
     * If enabled, the bot will avoid going underwater if baritone
     * isn't giving the bot movement instructions.
     * <p>
     * Baritone doesn't know how to move underwater so this should cause
     * no problems, but disable it if you want the bot to be able to sink.
     */
    private boolean avoidDrowning = true;

    /**
     * If enabled, the bot will close the open screen (furnace/crafting/chest/whatever) when the bot detects
     * that its look direction has changed OR that it is mining something.
     *
     * This is here to stop the bot from getting stuck in a screen container.
     */
    private boolean autoCloseScreenWhenLookingOrMining = true;

    /**
     * If enabled, will attempt to extinguish ourselves when on fire (and not immune to fire)
     */
    private boolean extinguishSelfWithWater = true;

    /**
     * If true, eat when we're hungry or in danger.
     */
    private boolean autoEat = true;

    /**
     * If true, the bot uses privileged world access: it reads block/entity positions
     * directly from the world regardless of whether a human could actually see them.
     * <p>
     * If false (default, human-like), the bot can only perceive what a human could
     * see (line of sight + field of view + distance) or hear (nearby sounds).
     */
    private boolean cheatScan = false;

    /**
     * (2026-09-04 人类式找要塞) 下界找烈焰人要塞/刷怪笼走人类式探索
     * (FortressExploreTask: 循声 + 看见下界砖 + 要塞地面走廊巡逻)。
     * 人类式探索持续超过该秒数仍未找到时, 降级回旧的 chunk 全方块扫描
     * (SearchChunkForBlockTask) 兜底, 保通关效率下限。
     * <p>
     * 设为 0 表示永不降级 (严格人类式, 极端情况下可能长时间找不到)。
     */
    private int fortressSearchFallbackSeconds = 480;

    /**
     * (2026-09-07 贪婪↔去下界链乒乓, 用户"允许找flint途中贪婪挖矿, 但不允许频繁
     * 交换任务") "放弃钻石准备进下界"阶段 (_diamondCollectGivenUp, 主线=金靴/
     * 造门链/找燧石挖砂砾) 顶层贪婪挖矿的触发冷却 (游戏秒)。
     * <p>
     * 实测 (2026-09-07 11:47-11:48 latest.log): 深板岩铁富集区 8 格内永远有生铁,
     * 贪婪批次目标=1 挖完一处即释放, 8s 通用冷却挡不住 "每 ~8s 整链拆一次去下界
     * 任务"的乒乓 (61s 内互抢 14 次), 燧石链每次重开重选砂砾, 净进度≈0。
     * 此阶段把冷却放大 (默认 45) = 保留低频顺手挖矿, 给燧石链连续挖掘窗口。
     * 普通阶段 (铁套/钻石收集等) 仍用 8s, 不受本项影响。
     */
    private int preNetherGreedyCooldownSeconds = 45;

    /**
     * (2026-09-07 回地表分流, 用户"他还非常深就触发了之前用于头顶是海洋的情况,
     * 这不对, 头顶不是海洋就要正常上去地面") GoToSurfaceTask 分流: 头顶列扫到水
     * (海洋/含水层) 才用"逐格向上凿+游泳上浮"; 头顶无水时默认交 Baritone 正常
     * 寻路上去 (洞穴穿行/楼梯)。
     * <p>
     * 本项 = Baritone 模式的失速兜底秒数: 回地表期间玩家 Y 净高度持续该秒数无
     * 增长 (2026-09-04 日志实锤: 干燥低顶洞穴 y=50, Baritone GoalYLevel(58)
     * 寻路失败原地卡 43s) 才降级为逐格向上凿。
     * <p>
     * 设为 0 表示永不降级 (严格 Baritone, 低顶洞穴可能长时间卡死)。
     */
    private int goToSurfaceBaritoneFallbackSeconds = 60;

    /**
     * If true, MLG/No Fall Bucket if we're knocked off course and falling.
     */
    private boolean autoMLGBucket = true;

    /**
     * If true, will automatically reconnect to the last open server if you get disconnected.
     * <p>
     * If disabled, the bot will stop running when you disconnect from a server.
     */
    private boolean autoReconnect = true;

    /**
     * If true, will automatically respawn instantly if you die.
     * <p>
     * If disabled, the bot will stop running when you die.
     */
    private boolean autoRespawn = true;

    /**
     * If true (and autoRespawn is on), the bot will try to return to the
     * remembered death point and pick up the dropped items after respawn.
     *
     * The feature is deliberately low priority:
     *   - It will NOT run before the bot has at least a stone pickaxe
     *     (see deathRecoveryRequireStonePickaxe).
     *   - MobDefenseChain / WorldSurvivalChain always take precedence so
     *     recovery won't run into lava / into a crowd of mobs.
     *   - If the grave is further than deathRecoveryMaxDistance blocks away
     *     or the 5-minute despawn window is nearly up, it is skipped.
     */
    private boolean recoverDropsAfterDeath = true;

    /**
     * Only attempt drop recovery if the death point is within this (Euclidean)
     * horizontal distance from the current player position.
     *
     * Walking 100-150 blocks typically takes ~40-70s and comfortably fits
     * inside the 5-minute despawn window; beyond that the drops are likely
     * to be gone before we arrive or the detour isn't worth the risk.
     *
     * Set to 0 to disable the distance check entirely (not recommended).
     */
    private int deathRecoveryMaxDistance = 200; // (2026-09-03 1000→200: 用户实测远距离(424格)回收太久/风险大, 缩短到200格内才回收)

    /**
     * If true, refuse to start recovery until the bot has at least a stone
     * pickaxe in its inventory. Recovery without even a stone pickaxe is
     * almost always a mistake: the bot has nothing to defend itself with,
     * can barely break blocks if it needs to dig out, and a wood-only
     * "naked" run is usually better spent gathering tools first.
     */
    private boolean deathRecoveryRequireStonePickaxe = true;

    /**
     * Maximum time (in real-game seconds) we will spend on a single recovery
     * attempt (navigation + picking up drops). If we haven't finished by
     * then the grave is abandoned and the memory is cleared.
     *
     * Defaults to 270 s = 4.5 min, leaving a 30 s safety margin before the
     * 5-minute item despawn kicks in.
     */
    private int deathRecoveryTimeoutSeconds = 270;

    /**
     * Once a death grave record is older than this many seconds (counted in
     * game ticks) it is considered unrecoverable (the 5-minute despawn
     * window has already closed) and will be silently discarded.
     *
     * Defaults to 360 s = 6 min to allow a small grace period beyond the
     * strict 5 min window.
     */
    private int deathRecoveryForgetAfterSeconds = 360;

    /**
     * If the bot dies this many times in a row (without a long surviving gap
     * in between), death drop recovery is abandoned: repeatedly walking back
     * to the grave keeps getting it killed, so the drops are simply left
     * behind. The streak resets after ~10 minutes of survival.
     *
     * Set to 0 to disable the check and always attempt recovery.
     */
    private int deathRecoveryMaxConsecutiveDeaths = 3;

    /**
     * 决策链（用户任务/食物/死亡恢复）的任务树每 N 个游戏 tick 执行一次。
     * 默认 1 = 20Hz（每 tick 执行，原版速度）。之前降为 4 = 5Hz 想减少
     * 切换噪音，但副作用是容器交互（MoveItemToSlotTask 拿起/放下）决策
     * 间隔拉长，叠加光标同步延迟导致"拿起放下反复 4-5 次"（用户实测，
     * 2026-08-24 已改回 1）。
     *
     * 安全不受影响：链仲裁（优先级抢占）和反射链（MLG 落水桶、岩浆/
     * 火焰/溺水逃生、怪物防御、交互修复）始终以 20Hz 运行。
     */
    private int taskTickInterval = 1;

    /**
     * This setting lets you configure what the bot should do if it needs to go to the nether
     * but can't find a nether portal immediately.
     * <p>
     * Options:
     * BUILD_PORTAL_VANILLA: Builds a nether portal, either with obsidian or with a water bucket and lava pool.
     * GO_TO_HOME_BASE: Travel to the set home coordinates and assume there's a portal there.
     */
    private DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR overworldToNetherBehaviour = DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR.BUILD_PORTAL_VANILLA;

    /**
     * When fast traveling via the nether, walk to our destination if we somehow end up closer than this range in the overworld.
     * We will normally travel well within this range (to within 100 blocks if not within a few), so keep this value ~decently~ large.
     */
    private int netherFastTravelWalkingRange = 600;

    /**
     * If set, will run this command by default when no other commands are running.
     *
     * For example, try setting this to "idle" to make the bot continue surviving/eating/escaping mobs.
     * Or "follow <Your Username>" to follow you when not doing anything.
     * Or "goto <Home base coords>" to return to home base when the bot finishes its work.
     */
    private String idleCommand = "";

    /**
     * If we need to throw away something, throw away these items first.
     */
    @JsonSerialize(using = ItemSerializer.class)
    @JsonDeserialize(using = ItemDeserializer.class)
    private List<Item> throwawayItems = Arrays.asList(
            // Overworld junk
            Items.DIORITE,
            Items.ANDESITE,
            Items.GRANITE,
            Items.TUFF,
            // (2026-08-25 用户指定) 圆石(原石)不再可丢 — 合成熔炉/石镐/石剑常用
            //Items.COBBLESTONE,
            Items.DIRT,
            Items.GRAVEL,
            Items.COBBLED_DEEPSLATE,
            Items.ACACIA_LEAVES, Items.BIRCH_LEAVES, Items.DARK_OAK_LEAVES, Items.OAK_LEAVES, Items.JUNGLE_LEAVES, Items.SPRUCE_LEAVES,
            // Nether junk, to be fair it's mostly tuned for the "beat game" task
            Items.NETHERRACK,
            Items.MAGMA_BLOCK,
            Items.SOUL_SOIL,
            Items.SOUL_SAND,
            Items.NETHER_BRICKS,
            Items.NETHER_BRICK,
            Items.BASALT,
            Items.BLACKSTONE,
            Items.END_STONE,
            // (2026-08-25 杂物优先丢, 用户指定) 草/种子/线/石英/皮革/烈焰弹及
            // 常见掉落物杂物 — 背包满腾位时优先丢这些, 不要丢镐/木头/重要物资
            Items.GRASS,
            Items.WHEAT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.BEETROOT_SEEDS,
            Items.STRING,
            Items.QUARTZ,
            Items.LEATHER,
            Items.FIRE_CHARGE,
            Items.BONE, Items.BONE_MEAL, Items.FEATHER, Items.ROTTEN_FLESH,
            Items.SPIDER_EYE, Items.GUNPOWDER, Items.EGG, Items.ARROW,
            // (2026-09-03 用户指定直接丢名单) 树苗/生铜/铁轨/多余铁盔甲/木石工具
            // — 背包满腾位时这些都是可丢垃圾, 不应占着空位。
            Items.OAK_SAPLING, Items.SPRUCE_SAPLING, Items.BIRCH_SAPLING,
            Items.JUNGLE_SAPLING, Items.ACACIA_SAPLING, Items.DARK_OAK_SAPLING,
            Items.RAW_COPPER,
            Items.RAIL,
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.WOODEN_SWORD, Items.WOODEN_HOE,
            Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_SWORD, Items.STONE_HOE
    );

    /**
     * How many throwaway blocks to keep as building blocks.
     */
    private int reservedBuildingBlockCount = 64;

    /**
     * If true, items with custom names will be protected/marked as "important"
     * so they won't be thrown away.
     */
    private boolean dontThrowAwayCustomNameItems = true;

    /**
     * If we need to throw away something but we don't have any "throwaway Items",
     * throw away any unimportant item that's not currently needed in our task chain.
     * <p>
     * Careful with this! If true, any item not in "importantItems" is liable to be thrown away.
     */
    private boolean throwAwayUnusedItems = true;

    /**
     * We will NEVER throw away these items.
     * Even if "throwAwayUnusedItems" is true and one of these items is not used in a task.
     */
    @JsonSerialize(using = ItemSerializer.class)
    @JsonDeserialize(using = ItemDeserializer.class)
    private List<Item> importantItems = Streams.concat(
            Stream.of(
                    Items.ENCHANTED_GOLDEN_APPLE,
                    Items.ENDER_EYE,
                    Items.TRIDENT,
                    Items.DIAMOND,
                    Items.DIAMOND_BLOCK,
                    Items.NETHERITE_SCRAP,
                    Items.NETHERITE_INGOT,
                    Items.NETHERITE_BLOCK
            ),
            Stream.of(ItemHelper.DIAMOND_ARMORS),
            Stream.of(ItemHelper.NETHERITE_ARMORS),
            Stream.of(ItemHelper.DIAMOND_TOOLS),
            Stream.of(ItemHelper.NETHERITE_TOOLS),
            // Don't throw away shulker boxes that would be pretty bad lol
            Stream.of(ItemHelper.SHULKER_BOXES),
            // (2026-08-25 重要物资保护, 用户指定) 末影珍珠/烈焰棒(末影之眼材料)、
            // 金锭(猪灵交易)/铁锭、床(末地打龙)、原木/木板(木头) — 这些都不参与
            // 丢物, 背包满腾位只丢杂物/垃圾。
            Stream.of(Items.ENDER_PEARL, Items.BLAZE_ROD, Items.GOLD_INGOT, Items.IRON_INGOT),
            // (2026-08-25 用户指定) 打火石(点火刚需) 不可丢; 圆石/深板岩圆石
            // 走"至少保留一组(64)"逻辑 (见 ItemHelper.canThrowAwayStack), 超出的
            // 可丢, 不放在这里完全保护。
            Stream.of(Items.FLINT_AND_STEEL),
            Stream.of(ItemHelper.BED),
            Stream.of(ItemHelper.LOG),
            Stream.of(ItemHelper.PLANKS)
    ).toList();

    /**
     * If true, will only accept items found in `supportedFuels` as fuel when smelting.
     *
     * Be careful when setting this to false, as ALL burnable items are liable to be burned
     * if they're not protected (blaze rods, beds, wooden tools, crafting tables etc.)
     */
    private boolean limitFuelsToSupportedFuels = true;

    /**
     * If `limitFuelsToSupportedFuels` is true, will use these items and ONLY these items as smelting fuel.
     * (2026-08-24) 原木【不在】燃料列表里 — 用户设计: 先做木板再烧, 别直接用
     * 原木烧 (原木还能做工具/合成台)。缺燃料时 CollectFuelTask 会用
     * CollectPlanksTask 把需要的原木合成木板 (2x2 格, 有界批量) 再烧。
     */
    @JsonSerialize(using = ItemSerializer.class)
    @JsonDeserialize(using = ItemDeserializer.class)
    private List<Item> supportedFuels = Streams.concat(
        Stream.of(
            Items.COAL,
            Items.CHARCOAL
        ),
        Stream.of(ItemHelper.PLANKS)
    ).toList();

    /**
     * Where "home base" is for the bot.
     * Some tasks use this value if you tell them to,
     * but don't worry about changing this unless you NEED it.
     */
    private BlockPos homeBasePosition = new BlockPos(0, 64, 0);

    /**
     * These areas will not be mined.
     * Used to prevent griefing, or to define a "spawn protection" zone so
     * the bot doesn't keep trying to break spawn protected blocks.
     *
     * Example: protects two areas. A "spawn" area from (x=-10 z=-10) to (x=10 z=10) and a home base at around (x = 1100, y = 2050)
     *
     * areasToProtect : [
     *      {
     *          "start": "-10, 0, -10",
     *          "end": "10, 255, 10",
     *          "dimension" : "OVERWORLD"
     *      },
     *      {
     *          "start": "1000, 50, 2000",
     *          "end": "1200, 255, 2100",
     *          "dimension" : "OVERWORLD"
     *      },
     * ],
     */
    private List<BlockRange> areasToProtect = Collections.emptyList();


    //////////////////////////////////////////////////////////////////////////////////////////
    ////////** END SETTINGS w/ COMMENTS **////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////



    private static boolean idArrayContainsItem(Item item, int[] ids) {
        int id = Item.getRawId(item);
        for (int check : ids) {
            if (check == id) return true;
        }
        return false;
    }

    public boolean shouldShowTaskChain() {
        return showTaskChains;
    }

    public boolean shouldHideAllWarningLogs() {
        return hideAllWarningLogs;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }
    public String getChatLogPrefix() {
        return chatLogPrefix;
    }

    public boolean shouldShowTimer() {
        return showTimer;
    }

    public float getResourcePickupRange() {
        return resourcePickupDropRange;
    }

    public float getResourceChestLocateRange() {
        return resourceChestLocateRange;
    }

    public float getResourceMineRange() {
        return resourceMineRange;
    }

    public float getContainerItemMoveDelay() {
        return containerItemMoveDelay;
    }

    public boolean shouldUseCraftingBookToCraft() {
        return useCraftingBookToCraft;
    }

    public int getFoodUnitsToCollect() {
        return foodUnitsToCollect;
    }

    public int getMinimumFoodAllowed() {
        return minimumFoodAllowed;
    }

    public boolean isMobDefense() {
        return mobDefense;
    }

    public boolean isDodgeProjectiles() {
        return dodgeProjectiles;
    }

    public boolean isTowerEscapeEnabled() {
        return towerEscapeEnabled;
    }

    public double getKillHostileWhenCloseForSeconds() {
        return killAnnoyingHostileWhenCloseForSeconds;
    }

    public boolean isAutoEat() {
        return autoEat;
    }

    public boolean isCheatScan() {
        return cheatScan;
    }

    public int getFortressSearchFallbackSeconds() {
        return fortressSearchFallbackSeconds;
    }

    public int getPreNetherGreedyCooldownSeconds() {
        return preNetherGreedyCooldownSeconds;
    }

    public int getGoToSurfaceBaritoneFallbackSeconds() {
        return goToSurfaceBaritoneFallbackSeconds;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public boolean isAutoRespawn() {
        return autoRespawn;
    }

    public boolean shouldRecoverDropsAfterDeath() {
        return recoverDropsAfterDeath;
    }

    public int getDeathRecoveryMaxDistance() {
        return deathRecoveryMaxDistance;
    }

    public boolean shouldDeathRecoveryRequireStonePickaxe() {
        return deathRecoveryRequireStonePickaxe;
    }

    public int getDeathRecoveryTimeoutSeconds() {
        return deathRecoveryTimeoutSeconds;
    }

    public int getDeathRecoveryForgetAfterSeconds() {
        return deathRecoveryForgetAfterSeconds;
    }

    public int getDeathRecoveryMaxConsecutiveDeaths() {
        return deathRecoveryMaxConsecutiveDeaths;
    }

    public int getTaskTickInterval() {
        return taskTickInterval;
    }

    public boolean shouldReplantCrops() {
        return replantCrops;
    }

    public boolean shouldDealWithAnnoyingHostiles() {
        return killOrAvoidAnnoyingHostiles;
    }

    public KillAura.Strategy getForceFieldStrategy() {
        return forceFieldStrategy;
    }

    public String getIdleCommand() {
        return idleCommand;
    }
    public boolean shouldRunIdleCommandWhenNotActive() {
        return idleCommand != null && !idleCommand.isBlank();
    }

    public boolean shouldAutoMLGBucket() {
        return autoMLGBucket;
    }

    public boolean shouldCollectPickaxeFirst() {
        return collectPickaxeFirst;
    }

    public boolean shouldAvoidDrowning() {
        return avoidDrowning;
    }

    public boolean shouldCloseScreenWhenLookingOrMining() {
        return autoCloseScreenWhenLookingOrMining;
    }

    public boolean shouldExtinguishSelfWithWater() {
        return extinguishSelfWithWater;
    }

    public boolean shouldAvoidSearchingForDungeonChests() {
        return avoidSearchingDungeonChests;
    }

    public boolean shouldAvoidOcean() {
        return avoidOceanBlocks;
    }

    public boolean isThrowaway(Item item) {
        return throwawayItems.contains(item);
    }

    public boolean isImportant(Item item) {
        return importantItems.contains(item);
    }

    public boolean shouldThrowawayUnusedItems() {
        return this.throwAwayUnusedItems;
    }

    public int getReservedBuildingBlockCount() {
        return this.reservedBuildingBlockCount;
    }

    public boolean getDontThrowAwayCustomNameItems() {
        return this.dontThrowAwayCustomNameItems;
    }

    public float getEntityReachRange() {
        return entityReachRange;
    }

    public Item[] getThrowawayItems(AltoClef mod, boolean includeProtected) {
        return throwawayItems.stream().filter(item -> includeProtected || !mod.getBehaviour().isProtected(item)).toArray(Item[]::new);
    }
    public Item[] getThrowawayItems(AltoClef mod) {
        return getThrowawayItems(mod, false);
    }

    public boolean shouldLimitFuelsToSupportedFuels() {
        return limitFuelsToSupportedFuels;
    }

    public boolean isSupportedFuel(Item item) {
        return !limitFuelsToSupportedFuels || supportedFuels.contains(item);
    }
    @JsonIgnore
    public Item[] getSupportedFuelItems() {
        return supportedFuels.toArray(Item[]::new);
    }

    public boolean isPositionExplicitelyProtected(BlockPos pos) {
        for (BlockRange protection : areasToProtect) {
            if (protection.contains(pos)) return true;
        }
        return false;
    }

    public DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR getOverworldToNetherBehaviour() {
        return overworldToNetherBehaviour;
    }

    public int getNetherFastTravelWalkingRange() {
        return netherFastTravelWalkingRange;
    }

    public BlockPos getHomeBasePosition() {
        return homeBasePosition;
    }

    @Override
    public void onFailLoad() {
        _failedToLoad = true;
    }

    @Override
    public boolean failedToLoad() {
        return _failedToLoad;
    }

    public static void load(Consumer<Settings> onReload) {
        ConfigHelper.loadConfig(SETTINGS_PATH, Settings::new, Settings.class, onReload);
    }
}
