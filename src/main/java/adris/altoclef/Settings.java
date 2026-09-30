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
     * (2026-09-18 批量收货允许的有界超量) 批量填充后合成格可做批次 K 超过还差
     * 数量 W 时，一键 shift 全收会多做 (K-W) 批。对"金粒→金锭/铁粒→铁锭"这类
     * 纯转化配方（原料唯一去向就是成品，多做的成品仍是等值资源），超量不超过
     * 此批数时允许一次 shift 全收（否则退化成逐个拾取，0.6s/件）。
     * 0 = 严格不超量（退回逐个拾取）。仅对金粒/铁粒→锭生效，剑/工具等
     * 原料另有他用的配方永远精确。
     */
    private int maxBulkCraftOvershoot = 8;

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
     * (2026-09-08 贪婪食物峰值, 用户) 食物收集的贪婪目标: 峰值未达成前, 地表
     * 食物门槛按该值(与 foodUnitsToCollect 取大)贪婪收集; 库存峰值一旦达到过
     * 该值即永久锁存 (FoodChain.s_foodPeakReached), 之后吃东西回落不再触发
     * 收集, 只有跌破危险线才重新收集。<=0 时视为禁用峰值逻辑 (退回旧 120 门槛)。
     */
    private int foodPeakUnits = 240;

    /**
     * (2026-09-15 觅食远征, 用户"连续长时间没找到食物就远走") 本轮觅食周期内
     * 食物潜力【无任何增长】持续超过该秒数 → 触发食物远征 (锁定方位角一路向,
     * 不回头、不下海), 且收集目标降为 foodExpeditionReducedUnits。
     * <=0 时禁用远征逻辑。
     */
    private double foodSearchExpeditionTimeoutSeconds = 40;

    /**
     * (2026-09-15 觅食远征, 用户"食物要求量现在有点高, 若超时间应该降低")
     * 觅食超时触发后, 本轮收集目标从峰值降为该值; 按该量收官后锁存"峰值已达"
     * 语义 (之后只在真断粮才重新收集)。进下界前的硬性食物闸 (150) 不受此影响。
     */
    private int foodExpeditionReducedUnits = 120;

    /**
     * (2026-09-26 远征收队, 用户"这个远征应该在再次收集到一定量食物时关闭, 比如
     * 20 units") 远征期间背包食物分数相对【进入远征那一刻】的增量达到该值 →
     * 关闭远征态 (s_foodExpeditionActive=false), 达标线恢复常规、探索不再锁方位角。
     * <p>
     * 语义: 远征是"长时间找不到食物"的应急手段, 增量达标 = 已走出食物真空区、
     * 重新开始有产出, 此时继续锁着方位角行军反而把 bot 带离已找到的食源地。
     * <=0 时禁用该收队判据 (远征只能靠食物达标结束)。
     */
    private int foodExpeditionExitUnits = 20;


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
     * 此阶段把冷却放大 = 保留低频顺手挖矿, 给燧石链连续挖掘窗口。
     * 普通阶段 (铁套/钻石收集等) 仍用 3s, 不受本项影响。
     * <p>
     * (2026-09-25 用户拍板 45→5) 实例 20260925-112204: preNether 阶段 45s 一查,
     * 8 格暴露矿感知窗口频繁整段错过 (diag: [GreedyOre] check 全是 vis=0->skip,
     * 唯一 11:54:58 vis=2 即 TRIGGER 挖到 +7)。当年乒乓根因是"批次目标=1 挖完
     * 2-4s 即释放", 现 CollectVisibleOresTask 已有 engaged 锁死+掉落宽限保护,
     * 10s 乒乓风险可控。
     */
    private int preNetherGreedyCooldownSeconds = 5;

    /**
     * (2026-09-07 击杀超时兜底, 用户"蜘蛛躲在暗处 bot 傻傻对准, 必须绕路才能
     * 打到, 也没有击杀超时兜底") 单个击杀目标连续该秒数既没命中一刀、也没在
     * 接近它 (距离无净缩短) 时放弃: 标记 CombatMemory 不可达冷却, 由上层
     * (守卫/KillEntitiesTask) 重选目标或回归主线。准心校验拟人化后, 打不到
     * 就是真打不到, 必须有止损。
     * <p>
     * 设为 0 表示永不放弃 (可能对躲在暗处的怪无限耗)。
     */
    private int killGiveUpSeconds = 15;

    /**
     * 击杀超时放弃的目标的冷却秒数: 冷却期内所有击杀目标选择跳过该实体,
     * 防止"放弃 → 立刻重选同一只 → 再放弃"死循环。冷却过后会再尝试一次
     * (怪可能自己走出来了, 玩家也会时不时回头看一眼)。
     */
    private int unreachableCooldownSeconds = 25;

    /**
     * (2026-09-07 v4) GoToSurfaceTask 头顶水况的扫描范围 (格)。仅用于判定
     * "凿穿后能否安全进水": 水面距脚 ≤5 (凿击窗口, "挖出必有水流下")、水柱深
     * ≥goToSurfaceMinWaterDepth、水柱上方有空气。水体本身【不再】触发凿击
     * (用户: 能不进水就不进水, 由 baritone 自己导航上去)。
     * <p>
     * 设为 0 表示永不判有水 (禁用逐格凿, 纯 Baritone)。
     */
    private int goToSurfaceWaterDetectRange = 16;

    /**
     * (2026-09-07 v4, 用户"检测海洋智能点; 3 格深的水还有必要非要去游吗, 还可
     * 能是洞穴水洞一点空气没有, 上去就淹死") 判定"凿穿后能安全进水"的最小
     * 连续水柱深度 (格)。滴水/小水洼不值得凿。另需满足水面 dy≤5 且水柱上方
     * 有空气 (封闭水洞不进)。
     */
    private int goToSurfaceMinWaterDepth = 3;

    /**
     * (2026-09-07 v4, 用户"当 baritone 找不到导航路线时, 希望它缩小导航长度,
     * 几格几格逐步导航到头, 而不是最后到海洋前十几格傻等; 实在找不到落脚点时
     * 才 dig_up") 判定"baritone 找不到路线"的持续时间 (秒): 连续该秒数没有可
     * 执行路径 (hasPath()==false) 才认无路, 随后把目标段长 16→8→4→2 逐级缩短
     * 重试; 最短段仍无路 = 确实找不到落脚点, 才允许凿穿进水。
     */
    private int goToSurfaceNoPathSeconds = 5;

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
    private int deathRecoveryMaxDistance = 250; // (2026-09-03 1000→200: 用户实测远距离(424格)回收太久/风险大; 2026-09-30 200→250: 用户定, 配合 Guard-2 滞回防压线抖动)

    /**
     * If true, refuse to start recovery until the bot has at least a stone
     * pickaxe in its inventory. Recovery without even a stone pickaxe is
     * almost always a mistake: the bot has nothing to defend itself with,
     * can barely break blocks if it needs to dig out, and a wood-only
     * "naked" run is usually better spent gathering tools first.
     */
    private boolean deathRecoveryRequireStonePickaxe = true;

    /**
     * (2026-09-10) 水下/洞内死亡时, 拟人感知闸门 (视线射线+FOV 或 声音) 可能
     * 始终建立不起对掉落物的感知 → DeathRecoveryTask 站在墓碑旁也"看不见"
     * 自己的掉落物 (13:15 场次: 水底墓碑旁 5s 零感知 → 误判无掉落 → 清记忆)。
     * 开启后, 从未感知到掉落物且短窗口到期时, 降级为直接扫描墓碑
     * PICKUP_RADIUS 内的已加载 ItemEntity —— "我知道我死在这, 东西就在附近"
     * 属于人类合法知识 (WorldMemory), 符合"超时可降级透视兜底"约定。
     * 范围锁死墓碑半径, 每次启用/命中都写 [DEGRADE SCAN] 日志。
     */
    private boolean deathRecoveryDegradeScan = true;

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
            // (2026-09-30 用户拍板) STONE_AXE 移出名单 — 白名单工具绕过档5 工具淘汰
            // 判据("有更好同类才丢"), 0930 19:34 现场: 背包有 wheat_seeds x64/砂土等
            // 杂物, 唯一的石斧因在热栏(档1 按背包下标顺序取第一个命中)被先丢。移出后
            // 石斧走档5: 无更好斧子/重复石斧不丢, 杂物(种子等)优先。
            Items.STONE_PICKAXE, Items.STONE_SHOVEL, Items.STONE_SWORD, Items.STONE_HOE,
            // (2026-09-29 用户拍板) 金质装备/工具进白名单: 猪灵谈判用完即丢,
            // 不占背包。金锭本身在 importantItems 里, 不受影响。
            Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS,
            Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_SWORD, Items.GOLDEN_HOE
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

    /**
     * (2026-09-11 异常看门狗) 是否启用异常自动检测。
     *
     * 启用后每 tick 只读扫描 bot 状态, 检出以下异常并主动告警, 免去人工盯屏:
     *  - 反复横跳: 同一处任务在 5s 窗口内来回切换 >= 6 次
     *  - 原地停滞: 有任务在跑但位置长期不动 (45s WARN / 120s CRITICAL)
     *  - 不可达  : 期望寻路但 baritone 持续算不出路径
     *
     * 检测层严格只读, 绝不修改任务状态 —— 关掉它只是停止告警, 不影响 bot 行为。
     * 告警落盘于 logs/altoclef/&lt;会话&gt;/anomaly.log。
     */
    private boolean anomalyWatchdogEnabled = true;

    //////////////////////////////////////////////////////////////////////////////////////////
    ////////** 要塞船降 (2026-09-18) **////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////

    /**
     * (2026-09-18 船降) 要塞走廊模式"下不去"兜底: bot 站在要塞上部但可行走
     * 地面都在下方大落差处 (baritone 拒绝跳), 卡死持续超阈值后用"船免摔落"
     * 方案下降。机制依据: Java 版船完全抵消自身与骑乘者的摔落伤害。
     */
    private boolean fortressBoatDescentEnabled = true;

    /** 走廊模式"找不到可走格子"持续多少秒后才触发船降 (用户拍板: ≤15s)。 */
    private int fortressBoatDescentStuckSeconds = 15;

    /** 只对落差 ≥ 此值的落点触发船降 (更浅的落差交给正常寻路)。 */
    private int fortressBoatDescentMinDropBlocks = 8;

    /** 连续失败这么多次后, 本任务实例内停用船降 (防反复折腾)。 */
    private int fortressBoatDescentMaxFailures = 3;

    //////////////////////////////////////////////////////////////////////////////////////////
    ////////** 顺手收珍珠 (2026-09-18) **////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////
    // 下界三大任务里, 末影珍珠【不需要任何前置】—— 它不像进要塞打烈焰人那样必须
    // 等防火药水齐备。所以执行"7 根烈焰棒 / 2 瓶防火药水"两件主线事的过程中, 只要
    // 亲眼看见诡异森林, 就驻留下来把珍珠刷够 (用户拍板)。烈焰棒的优先级始终比珍珠
    // 高一点, 由 opportunisticPearlBlazePriorityRange 管。

    /** 总开关: 主线 (烈焰棒/防火药水) 进行中是否允许顺手进林子刷珍珠。 */
    private boolean opportunisticWarpedForestPearls = true;

    /**
     * "看见森林"的最远距离。与 {@code VisibilityHelper.MAX_VIEW_DISTANCE} (人类视距 64)
     * 同口径 —— 不另起一套常量。候选还必须过 FOV 锥 + LOS 射线才算真看见。
     */
    private double opportunisticPearlSightRange = 64;

    /** 烈焰棒优先半径: 感知到这个距离内有烈焰人 / 地上有烈焰棒(粉) → 不打珍珠, 让位主线。 */
    private double opportunisticPearlBlazePriorityRange = 32;

    /** 单次蹲守总时长上限 (秒)。到点无条件交还主线, 防蹲守吃掉整局。 */
    private double opportunisticPearlCampMaxSeconds = 300;

    /** 蹲守中这么久一只末影人都没感知到 → 判这片林子没货, 放弃并进冷却。 */
    private double opportunisticPearlDrySeconds = 60;

    /** 放弃蹲守后的冷却 (秒), 防止"放弃→立刻又看见林子→再放弃"来回横跳。 */
    private double opportunisticPearlRetryCooldownSeconds = 120;

    //////////////////////////////////////////////////////////////////////////////////////////
    ////////** 数据集录制 (2026-09-17) **//////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////
    // 为层次化 MBPN 训练做逐帧全量快照导出 (不录像素画面, 只录结构化世界 + 结构化记忆)。
    // 决策频率 = 20Hz (taskTickInterval=1), 因此标签每 tick 都可能变化, 不做稀疏化假设。
    //
    // 录制器纪律: 严格只读、全包 try/catch、绝不影响 bot 行为; 关掉开关时零开销。
    // 输出目录: <游戏运行目录>/record/<会话时间戳>/ —— 刻意【不】放在 logs/altoclef/ 下,
    // 因为 Debug.pruneOldSessionsOnce (KEEP_SESSIONS=10) 会自动删旧会话目录。

    /**
     * (2026-09-17) 是否开启训练数据集录制 (逐帧全量快照, 约 20 行/秒 × 4 通道)。
     * <p>
     * 默认关闭 —— 开启后每局会产生可观的数据量 (gzip JSONL), 只在需要采集语料时打开。
     * 关闭时录制器在 tick 里只做一次布尔判断, 无其它开销。
     */
    private boolean recordDatasetEnabled = true;

    /**
     * (2026-09-18) 客户端侧永久夜视: 常挂一份无图标/无粒子的夜视效果, 让录屏画面
     * 在夜晚/矿洞/下界不至于全黑。
     *
     * <p>【为什么不是 gamma】: 项目里的 {@code @gamma} 命令改的是 {@code options.gamma},
     * 那是原版渲染管线的亮度; 装了 Iris/OptiFine 光影后, 画面亮暗由光影自己的曝光与
     * 色调映射决定, gamma 会被直接忽略。而 1.18.2 的夜视是写进 lightmap 纹理的
     * ({@code LightmapTextureManager}), 绝大多数光影都要采样 lightmap, 所以夜视在
     * 光影下仍然生效 —— 这是本开关选夜视而非拉 gamma 的唯一原因。
     *
     * <p>纯视觉改动: bot 读世界状态走的是方块/实体数据, 不依赖画面亮度, 因此不影响
     * 任何任务逻辑。效果只加在客户端, 服务器不知道, 不产生任何服务端副作用。
     * 默认开启 (用途是录制可读画面)。{@code @nightvision off} 可运行时关掉。
     */
    private boolean forceNightVision = true;

    /**
     * (2026-09-18) 夜视剩余时间低于该秒数就补挂一次。用来兜住"牛奶/死亡/服务器同步"
     * 把客户端效果清掉的情况 —— 补挂最快下一 tick 生效, 录屏最多丢一帧两帧。
     */
    private double nightVisionRefreshBelowSeconds = 300;

    /**
     * (2026-09-17) 每帧导出的可见方块上限 (按距离由近到远截断)。
     * 防"海洋群系 24 万格水"这类极端场景撑爆单帧 (见 VisibilityHelper 的水体过滤约定)。
     * 设为 0 表示完全不导出方块通道 (D)。
     */
    private int recordPerceptionMaxBlocks = 512;

    /**
     * (2026-09-17) 每帧导出的可见实体上限 (按距离截断)；0 表示不导出实体。
     */
    private int recordPerceptionMaxEntities = 64;

    /**
     * (2026-09-17) 感知导出的最大距离 (格)。超过该距离的方块/实体不进感知通道。
     * 与拟人感知的视线判定叠加 (两者取交集), 纯粹是体积控制手段。
     */
    private double recordPerceptionRange = 48;

    /**
     * (2026-09-17) 视觉记忆的近场扫描半径 (格)。录制器按"近场全量扫 + 远场稀疏扫"
     * 重建 bot 的可见世界: 近场半径内 step=1 逐格看, 超出部分按
     * {@link #recordFarScanStep} 稀疏看。体积与开销正比于半径³, 别开太大。
     */
    private int recordNearScanRadius = 12;

    /**
     * (2026-09-17) 远场稀疏扫描的步长 (格)。远场只看"隔几步一格", 用于开阔地形
     * 与远处结构 (近处已被近场覆盖, 不重复扫)。
     */
    private int recordFarScanStep = 4;

    /**
     * (2026-09-17) 视觉记忆窗口 (秒)。某个方块最后一次被判定"看得见"之后, 在窗口
     * 内即使当前不可感知也仍按 {@code remembered} 导出 —— 对应人类的"刚看过的
     * 东西还记得", 与 EntityTracker 的 20s 实体感知记忆同口径。
     */
    private double recordSightMemorySeconds = 20;

    /**
     * (2026-09-17) L1 操作域 blockstate 导出半径 (格)。
     * 感知通道里该半径内、且本扫描圈确认过 (fresh) 的方块, 额外导出方块状态属性
     * (门开没开/熔炉燃没燃/作物长到第几段/楼梯朝向...) —— 近距操作 (开门/取燃料/
     * 收作物) 的直接输入, 只靠方块 id 训练时只能瞎猜。0 = 关闭。
     * 只在导出时对半径内方块多读一次 getBlockState (L1 域方块最多百来个), 开销可忽略。
     */
    private double recordPerceptionStateRadius = 5;

    /**
     * (2026-09-17) 是否每帧导出背包全量物品计数。
     * 背包是 L2 操作层 ("检查背包是否有所需物品/拿出物品") 的直接输入, 逐帧全量最完整;
     * 关掉可显著减小 frames.jsonl.gz (背包每帧重复度极高)。
     */
    private boolean recordInventoryPerTick = true;

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

    public boolean isAnomalyWatchdogEnabled() {
        return anomalyWatchdogEnabled;
    }

    // ==== 要塞船降 (2026-09-18) ====

    public boolean isFortressBoatDescentEnabled() {
        return fortressBoatDescentEnabled;
    }

    public int getFortressBoatDescentStuckSeconds() {
        return fortressBoatDescentStuckSeconds;
    }

    public int getFortressBoatDescentMinDropBlocks() {
        return fortressBoatDescentMinDropBlocks;
    }

    public int getFortressBoatDescentMaxFailures() {
        return fortressBoatDescentMaxFailures;
    }

    // ==== 顺手收珍珠 (2026-09-18) ====

    public boolean isOpportunisticWarpedForestPearlsEnabled() {
        return opportunisticWarpedForestPearls;
    }

    public double getOpportunisticPearlSightRange() {
        return opportunisticPearlSightRange;
    }

    public double getOpportunisticPearlBlazePriorityRange() {
        return opportunisticPearlBlazePriorityRange;
    }

    public double getOpportunisticPearlCampMaxSeconds() {
        return opportunisticPearlCampMaxSeconds;
    }

    public double getOpportunisticPearlDrySeconds() {
        return opportunisticPearlDrySeconds;
    }

    public double getOpportunisticPearlRetryCooldownSeconds() {
        return opportunisticPearlRetryCooldownSeconds;
    }

    // ==== 数据集录制 (2026-09-17) ====

    public boolean isRecordDatasetEnabled() {
        return recordDatasetEnabled;
    }

    /**
     * (2026-09-17) 运行时开关 ({@code @record on/off})。只改内存字段, 【不】回写
     * altoclef_settings.json —— 临时采集不该悄悄改掉用户的持久配置; 想永久开启
     * 请直接改 settings 文件里的 recordDatasetEnabled。
     */
    public void setRecordDatasetEnabled(boolean value) {
        recordDatasetEnabled = value;
    }

    // ==== 永久夜视 (2026-09-18) ====

    public boolean isForceNightVisionEnabled() {
        return forceNightVision;
    }

    /**
     * (2026-09-18) 运行时开关 ({@code @nightvision on/off})。跟录制开关一样, 只改内存
     * 字段, 【不】回写 altoclef_settings.json; 想默认关掉请直接改配置文件的
     * {@code forceNightVision}。
     */
    public void setForceNightVisionEnabled(boolean value) {
        forceNightVision = value;
    }

    public double getNightVisionRefreshBelowSeconds() {
        return nightVisionRefreshBelowSeconds;
    }

    public int getRecordPerceptionMaxBlocks() {
        return recordPerceptionMaxBlocks;
    }

    public int getRecordPerceptionMaxEntities() {
        return recordPerceptionMaxEntities;
    }

    public double getRecordPerceptionRange() {
        return recordPerceptionRange;
    }

    public int getRecordNearScanRadius() {
        return recordNearScanRadius;
    }

    public int getRecordFarScanStep() {
        return recordFarScanStep;
    }

    public double getRecordSightMemorySeconds() {
        return recordSightMemorySeconds;
    }

    public double getRecordPerceptionStateRadius() {
        return recordPerceptionStateRadius;
    }

    public boolean shouldRecordInventoryPerTick() {
        return recordInventoryPerTick;
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

    public int getMaxBulkCraftOvershoot() {
        return maxBulkCraftOvershoot;
    }

    public int getFoodUnitsToCollect() {
        return foodUnitsToCollect;
    }

    public int getFoodPeakUnits() {
        return foodPeakUnits;
    }

    public int getMinimumFoodAllowed() {
        return minimumFoodAllowed;
    }

    public double getFoodSearchExpeditionTimeoutSeconds() {
        return foodSearchExpeditionTimeoutSeconds;
    }

    public int getFoodExpeditionReducedUnits() {
        return foodExpeditionReducedUnits;
    }

    public int getFoodExpeditionExitUnits() {
        return foodExpeditionExitUnits;
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

    public int getKillGiveUpSeconds() {
        return killGiveUpSeconds;
    }

    public int getUnreachableCooldownSeconds() {
        return unreachableCooldownSeconds;
    }

    public int getGoToSurfaceWaterDetectRange() {
        return goToSurfaceWaterDetectRange;
    }

    public int getGoToSurfaceMinWaterDepth() {
        return goToSurfaceMinWaterDepth;
    }

    public int getGoToSurfaceNoPathSeconds() {
        return goToSurfaceNoPathSeconds;
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

    public boolean shouldDeathRecoveryDegradeScan() {
        return deathRecoveryDegradeScan;
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
        Item[] base = throwawayItems.stream().filter(item -> includeProtected || !mod.getBehaviour().isProtected(item)).toArray(Item[]::new);
        // (2026-09-21 地牢阶段垃圾, 用户"进地牢后金/岩浆桶/打火石都可以丢")
        // 必须在行为保护过滤【之后】追加: 搭门任务会 addProtectedItems(打火石/
        // 岩浆桶) 且不回收, 若在过滤前追加会被这一步重新滤掉, getGarbageSlot
        // 的候选槽列表直接查不到它们, canThrowAwayStack 的地牢放行没机会生效。
        if (!ItemHelper.isDungeonPhaseActive()) {
            return base;
        }
        Item[] dungeonJunk = {Items.GOLD_NUGGET, Items.GOLD_INGOT, Items.RAW_GOLD, Items.LAVA_BUCKET, Items.FLINT_AND_STEEL};
        Item[] result = Arrays.copyOf(base, base.length + dungeonJunk.length);
        System.arraycopy(dungeonJunk, 0, result, base.length, dungeonJunk.length);
        return result;
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
