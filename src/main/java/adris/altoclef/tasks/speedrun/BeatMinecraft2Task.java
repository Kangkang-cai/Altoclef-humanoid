package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.chains.FoodChain;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.DoStuffInContainerTask;
import adris.altoclef.tasks.container.EmptyFurnaceTask;
import adris.altoclef.tasks.container.FurnaceSmeltGuardTask;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.container.PickupFromContainerTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.LootDesertTempleTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.resources.*;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.mob.SilverfishEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

@SuppressWarnings("ALL")
public class BeatMinecraft2Task extends Task {

    private static BeatMinecraftConfig _config;
    static {
        ConfigHelper.loadConfig("configs/beat_minecraft.json", BeatMinecraftConfig::new, BeatMinecraftConfig.class, newConfig -> _config = newConfig);
    }
    public static BeatMinecraftConfig getConfig() {
        return _config;
    }
    // 床也全程追踪 (2026-08-21 床↔木头横跳修复): hasBed / 床保险 /
    // grab-bed 三个分支原来只认 6 格内的 isPlacedBedNearby, bot 走向
    // 村庄床时反复进出 6 格边界 → 分支高频翻转(挖床↔收集木头)。
    // 现在"感知到床"(anyFound, 见 VisibilityHelper 完全感知)就算
    // "有床可用"——睡觉时跑去睡, 绝不为了手里拿一张床去拆它。
    private static final Block[] TRACK_BLOCKS = concat(new Block[] {
            Blocks.END_PORTAL_FRAME,
            Blocks.END_PORTAL,
            Blocks.CRAFTING_TABLE, // For pearl trading + gold crafting
            Blocks.CHEST, // For ruined portals
            Blocks.SPAWNER, // For silverfish,
            Blocks.STONE_PRESSURE_PLATE // For desert temples
    }, concat(ItemHelper.itemsToBlocks(ItemHelper.BED),
            // 羊毛也全程追踪 (2026-09-15 刷屏修复): nightBedOpportunityNearby 每 tick
            // 查 WOOL, 但羊毛只有 CollectWoolTask 激活期间才被 track —— 平时
            // getNearestTracking 每次查询都报 "Not tracking block white_wool" 刷屏,
            // 且"顺路捡羊毛做床"判断恒 false 功能死掉。与床同等待遇全程追踪。
            ItemHelper.itemsToBlocks(ItemHelper.WOOL)));
    private static Block[] concat(Block[] a, Block[] b) {
        Block[] result = new Block[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static final Item[] COLLECT_EYE_ARMOR = new Item[] {
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS,
            Items.GOLDEN_BOOTS
    };
    private static final Item[] COLLECT_EYE_ARMOR_END = ItemHelper.DIAMOND_ARMORS;
    private static final ItemTarget[] COLLECT_EYE_GEAR = combine(
            toItemTargets(Items.DIAMOND_SWORD),
            // (2026-09-10 用户确认 3 是改错的) 1 把钻石镐足够, 与 COLLECT_EYE_GEAR_MIN 对齐;
            // 3 把=9 颗钻, 是"挖 26 颗钻"需求虚高的主要来源。
            toItemTargets(Items.DIAMOND_PICKAXE, 1),
            toItemTargets(Items.CRAFTING_TABLE)
    );
    private static final ItemTarget[] COLLECT_EYE_GEAR_MIN = combine(
            toItemTargets(Items.DIAMOND_SWORD),
            toItemTargets(Items.DIAMOND_PICKAXE, 1)
    );
    private static final ItemTarget[] IRON_GEAR = combine(
            toItemTargets(Items.IRON_SWORD),
            toItemTargets(Items.IRON_PICKAXE, 2),
            // Shield: makes the nether/end fights much safer, get one early.
            toItemTargets(Items.SHIELD)
    );
    private static final ItemTarget[] IRON_GEAR_MIN = combine(
            toItemTargets(Items.IRON_SWORD),
            // (2026-08-22 盾牌被吞修复) 盾牌是 nether/末地战斗刚需。原 MIN
            // 只含铁剑 — 铁剑一到手就退出 iron gear 阶段, squashed IRON_GEAR
            // 里排在后面的盾牌没做完就被跳过 (用户实测"盾牌任务被吞掉")。
            // 加入最低要求, 铁剑+盾牌齐了才进 eyeGear。
            toItemTargets(Items.SHIELD)
    );

    /**
     * (2026-08-23) 铁装备阶段 (铁剑+盾牌) 是否已完成。
     * 供 CollectVisibleOresTask 贪心挖矿判断金矿是否让位:
     * 未完成时金矿不参与贪心 — 铁装备制作/穿上优先于挖金 (用户原则)。
     */
    public static boolean isIronGearPhaseDone(AltoClef mod) {
        boolean met = ironGearMinMet(mod);
        logShieldDiagIfNotMet(mod, met);
        return met;
    }

    /**
     * (2026-08-27 副手/主手的盾也算"已有盾", 用户方向) 盾常拿在副手, 不在背包
     * 里; 只看背包/容器计数会误判"没有盾" → 反复想做一张 (用户实测: 主副手各持
     * 一盾仍跳去合盾)。手上/副手举着盾就算满足盾需求。
     */
    private static boolean shieldMet(AltoClef mod) {
        if (StorageHelper.itemTargetsMet(mod, toItemTargets(Items.SHIELD))) return true;
        return mod.getPlayer() != null
                && (mod.getPlayer().getMainHandStack().getItem() == Items.SHIELD
                    || mod.getPlayer().getOffHandStack().getItem() == Items.SHIELD);
    }

    private static boolean ironGearMinMet(AltoClef mod) {
        return StorageHelper.itemTargetsMet(mod, toItemTargets(Items.IRON_SWORD)) && shieldMet(mod);
    }

    // (2026-09-12 跨批次对撞闸门) 判据在 MineAndCollectTask.mainlineMiningEngagedOn —
    // 由【主线挖矿批次主动登记心跳】(矿种 + 时间戳, TTL 1.5s), 本任务只读。
    // 不能在这里遍历 mod.getUserTaskChain().getTasks() 找子任务: Task.tickInternal
    // 是自上而下"先 addTaskToChain 再 onTick"(Task.java L56/L67), 本任务 onTick 跑的
    // 时候链上只有它自己和祖先, 看不到 gear 链里的 MineAndCollectTask。

    /**
     * (2026-09-09 进下界前必须持盾, 用户"bot 进下界前没有先做一个盾牌, 这不对")
     * 漏洞: OVERWORLD case 里铁装门槛是 `!gearMinMet && !eyeGearSatisfied` —
     * 钻石装达标后整段铁装阶段被跳过, IRON_GEAR_MIN 的盾牌要求失效, 两处钻石
     * 放弃分支 + 主路径直接 return _goToNetherTask 都没有再查盾 → 裸盾进下界。
     * 补一道进下界前的最后一道闸: 缺盾就补做 (铁锭/木板链由合成任务自动续)。
     */
    private static Task getPreNetherShieldTask(AltoClef mod) {
        if (shieldMet(mod)) return null;
        return TaskCatalogue.getItemTask(Items.SHIELD, 1);
    }

    /**
     * (2026-08-30 铁套保护, 用户 "获得工具后再做一个铁套保护自己, 不然太容易死")
     * 铁甲四件是否全部【穿在身上】。只有穿上才算完成, 保证收集到的铁甲会被
     * EquipArmorTask 实际装备, 而不只是躺在背包里。
     */
    private static boolean ironArmorMet(AltoClef mod) {
        return StorageHelper.isArmorEquippedAll(mod, ItemHelper.IRON_ARMORS);
    }

    /**
     * (2026-09-01 铁套↔钻石套打架) 是否已有钻石装具穿在身上 (头盔/胸甲/护腿任一)。
     * Path A "Equipping owned armor" 每 tick 先跑, 一旦穿上钻石件, 铁套阶段就
     * 应让位 (钻石>铁), 否则铁件覆盖钻石件形成横跳。
     */
    private static boolean hasDiamondArmorEquipped(AltoClef mod) {
        return StorageHelper.isArmorEquipped(mod, Items.DIAMOND_HELMET)
                || StorageHelper.isArmorEquipped(mod, Items.DIAMOND_CHESTPLATE)
                || StorageHelper.isArmorEquipped(mod, Items.DIAMOND_LEGGINGS);
    }

    /**
     * (2026-08-28 已有盾就不再做盾) IRON_GEAR 里带 SHIELD。即便 shieldMet
     * 判定"已有盾"(副手/主手) 都返回 true, 只要铁剑还没做出来 (gate
     * !gearMinMet = 缺铁剑), 就会拿整份 IRON_GEAR 去 getSquashedItemTask,
     * 该任务的按物品满足扫描拿不到稳定识别副手盾 → 重复做盾、缺材料卡死
     * (用户实测: 副手有盾还触发做盾)。这里在发给 squashed 任务前, 若已
     * 持有盾就把 SHIELD 目标剔除, 只补铁剑/镐。
     */
    private static boolean ironSwordHeld(AltoClef mod) {
        return mod.getPlayer() != null
                && (mod.getPlayer().getMainHandStack().getItem() == Items.IRON_SWORD
                    || mod.getPlayer().getOffHandStack().getItem() == Items.IRON_SWORD);
    }

    // (2026-08-30 手上铁剑也剔除, 用户"有剑还去重做铁剑→8锭级联") 铁剑常拿在主手
    // (战斗中), 背包计数=0 → gearMinMet 以为"没剑" → 重进铁装链把整把剑重做 →
    // 目标变成剑+镐+8锭 → 级联熔炉/圆石/木镐 (日志 19:41 实锤)。与盾牌同型 bug。
    // 手上/副手已拿剑就把可达 IRON_SWORD 的目标筛掉, 只补真正缺的镐等, 不再重做
    // 已持有的剑。
    private static ItemTarget[] ironGearTargets(AltoClef mod) {
        if (shieldMet(mod) && ironSwordHeld(mod)) {
            return Arrays.stream(IRON_GEAR)
                    .filter(t -> !t.matches(Items.SHIELD) && !t.matches(Items.IRON_SWORD))
                    .toArray(ItemTarget[]::new);
        }
        if (!shieldMet(mod)) return IRON_GEAR;
        return Arrays.stream(IRON_GEAR)
                .filter(t -> !t.matches(Items.SHIELD))
                .toArray(ItemTarget[]::new);
    }

    // (2026-08-27 盾牌诊断日志) 每 2 秒一条写到独立文件, 方便测试后检索。
    private static long _lastShieldDiagLog = 0;
    private static void logShieldDiag(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastShieldDiagLog < 2000) return;
        _lastShieldDiagLog = now;
        int swordCount = StorageHelper.countItemInInventoryReal(mod, Items.IRON_SWORD);
        int shieldCount = StorageHelper.countItemInInventoryReal(mod, Items.SHIELD);
        String main = "none", off = "none";
        if (mod.getPlayer() != null) {
            if (mod.getPlayer().getMainHandStack() != null) main = mod.getPlayer().getMainHandStack().getItem().getTranslationKey();
            if (mod.getPlayer().getOffHandStack() != null) off = mod.getPlayer().getOffHandStack().getItem().getTranslationKey();
        }
        Debug.logToFile("altoclef_fix_diag.log",
                "[ShieldDiag] gear-min check | swordCount=" + swordCount
                        + " shieldInventory=" + shieldCount
                        + " mainHand=" + main + " offHand=" + off);
    }
    private static void logShieldDiagIfNotMet(AltoClef mod, boolean met) {
        if (!met) logShieldDiag(mod);
    }

    /**
     * (2026-08-31) [IronDiag] 阶段判定诊断: 每个关键分支决策点打一份
     * "当前阶段 + 关键材料量 + 为何选此分支"的键值快照到 altoclef_diag.log。
     * 纯旁路日志, 2 秒节流, 绝不影响主逻辑。核心排查目标: "铁锭/铁装备
     * 材料已经够做/过剩, bot 却仍去拿工作台/收熔炉/囤木" —— 看各分支
     * why 与 ironIngots / rawIron / gearMinMet 的键值对照即可定位是谁
     * 的错误判定在把主线拽偏。
     */
    private void diagIronPhase(AltoClef mod, String branch, String why) {
        if (!_ironDiagTimer.elapsed()) return;
        _ironDiagTimer.reset();
        try {
            boolean gearMinMet = ironGearMinMet(mod);
            boolean eyeGearSatisfied = StorageHelper.itemTargetsMet(mod, COLLECT_EYE_GEAR_MIN)
                    && StorageHelper.isArmorEquippedAll(mod, COLLECT_EYE_ARMOR);
            String msg = "[IronDiag] stage=" + branch
                    + " | why=" + why
                    + " | ironIngots=" + StorageHelper.countItemInInventoryReal(mod, Items.IRON_INGOT)
                    + " rawIron=" + StorageHelper.countItemInInventoryReal(mod, Items.RAW_IRON)
                    + " ironSword=" + StorageHelper.countItemInInventoryReal(mod, Items.IRON_SWORD)
                    + " shield=" + StorageHelper.countItemInInventoryReal(mod, Items.SHIELD)
                    + " wood=" + countLogsInPlayerInventory(mod)
                    + " food=" + StorageHelper.calculateInventoryFoodScore(mod)
                    + " | keys gearMinMet=" + gearMinMet
                    + " shieldMet=" + shieldMet(mod)
                    + " eyeGearSatisfied=" + eyeGearSatisfied;
            Debug.logToFile("altoclef_diag.log", msg);
        } catch (Exception ignored) {
            // 诊断日志绝不影响主逻辑
        }
    }

    private static final int END_PORTAL_FRAME_COUNT = 12;
    private static final double END_PORTAL_BED_SPAWN_RANGE = 8;


    private BlockPos _endPortalCenterLocation;
    private boolean _ranStrongholdLocator;
    private boolean _endPortalOpened;
    private BlockPos _bedSpawnLocation;

    private List<BlockPos> _notRuinedPortalChests = new ArrayList<>();

    // ==== 就近快速搜刮 (2026-08-21 用户原则: 路过的结构物资先拿上) ====
    // 机器人附近(48格)存在未搜刮过的箱子/木桶时, 先把它们搜刮干净再去
    // 干"要跑远路的正事"(如远处收集木头)。典型场景: 身在村庄但没木头、
    // 森林很远 → 先把村庄箱子里的面包/铁/煤/床全拿走。矿洞遗迹(废弃
    // 矿道/要塞)的箱子同样是 CHEST, 自动被同一机制覆盖。
    // _sweptContainers 记录派发过的箱子, 永不重复开(即使当时开失败)。
    private final Set<BlockPos> _sweptContainers = new HashSet<>();
    // (2026-09-01 矿车箱子) 记录派发过的矿车箱子实体, 不重复开。
    private final Set<ChestMinecartEntity> _sweptMinecarts = new HashSet<>();
    private LootContainerTask _nearbySweepTask = null;
    // (2026-09-02 刷怪笼抑制开箱) 近场扫描 SPAWNER 的节流缓存 (ms + 结果),
    // 避免每 tick 全扫 17^3 方块造成卡顿。
    private long _lastSpawnerBlockScanMs = 0;
    private boolean _spawnerBlockedCached = false;
    private static final double NEARBY_LOOT_RANGE = 48;

    // ==== Craft table pickup 限额 (2026-08-21 修复: "while we're at it" 死循环) ====
    // 日志实锤 (22:42-22:46): 缓存里唯一的工作台在几十格外, 任务反复派发
    // "Pick up crafting table while we're at it", 导航到不了也不放弃, 五分钟
    // 内刷了上百次。修复: (1) 只认 16 格内的近处工作台 — "顺手拿" 的语义;
    // (2) 单次尝试 90 秒拿不到 = 失败; (3) 失败 2 次后冷却 3 分钟再允许。
    private static final double CRAFTING_TABLE_GRAB_RANGE = 16;
    private static final double FURNACE_GRAB_RANGE = 16;

    private int _cachedFilledPortalFrames = 0;

    private final HashMap<Item, Integer> _cachedEndItemDrops = new HashMap<>();

    // Controls whether we CAN walk on the end portal.
    private boolean _enterindEndPortal = false;

    // For some reason, after death there's a frame where the game thinks there are NO items in the end.
    private final TimerGame _cachedEndItemNothingWaitTime = new TimerGame(2);

    // We don't want curse of binding
    private static final Predicate<ItemStack> _noCurseOfBinding = stack -> {
        boolean hasBinding = false;
        for (NbtElement elm : stack.getEnchantments()) {
            NbtCompound comp = (NbtCompound) elm;
            if (comp.getString("id").equals("minecraft:binding_curse")) {
                return false;
            }
        }
        return true;
    };

    private Task _foodTask;
    private Task _gearTask;
    private Task _lootTask;
    // (2026-09-17 钻石件合成粘性) "钻石即做"分支每 tick 重入必须返回同一实例直到
    // 它结束。旧代码每 tick TaskCatalogue.getItemTask 新开链, 而 shouldForce(_gearTask)
    // 会在 craftBusy=true 的瞬间抢断它 (填格→抢断→排格→craftBusy 清零→再填格),
    // 2s 死循环 —— 213247/214954 两场实锤: 钻石甲从未做成, MineDown 每轮爬回
    // y=-54 都被掐断, 用户看到的就是"一直爬高但啥也不干"。
    private Task _diamondPieceTask = null;
    private Item _diamondPieceItem = null;
    // (2026-09-03 钻石收集时间上限, 用户确认以 5 分钟为准; 早期注释写 15 分钟与常量不符已修正)
    // 钻石探索/收集(挖钻石矿换剑/镐)耗尽太久时, 累积活跃 5 分钟(DIAMOND_COLLECT_LIMIT_MS)后
    // 永久放弃钻石收集,
    // 不再卡在"差几颗钻石"的死循环, 直接推进去下界。累计的是"钻石收集任务活跃运行"的
    // 时间(被打断去食物/木柴等时不累加), 用墙上时钟。达标后 marked give up, 后续
    // 两处钻石收集判定 (shouldForce(_gearTask) 与 !eyeGearSatisfied 分支) 都跳过。
    private long _diamondCollectCumulativeMs = 0;
    private long _diamondCollectSegmentStartMs = -1; // -1 = 当前未在连续活跃段
    private boolean _diamondCollectGivenUp = false;
    private static final long DIAMOND_COLLECT_LIMIT_MS = 5 * 60 * 1000L;
    private long _lastDiamondGiveUpLogMs = 0;
    // (2026-09-03 钻石时间限制辅助) _gearTask 被铁装/钻石复用, 只有钻石收集阶段
    // 才受时间上限约束。赋值处置位: 铁装 getSquashedItemTask → false; 钻石
    // COLLECT_EYE gear → true。计时只对钻石收集 (此类下 _gearTask) 生效。
    private boolean _gearTaskIsDiamond = false;
    // (2026-09-04 钻石放弃后进下界前补金靴) 金靴和钻石件同在 COLLECT_EYE_ARMOR,
    // 钻石超时放弃时金靴被一并放弃 → 裸脚下界, 到防火药水阶段前有猪灵敌对窗口。
    // give up 后去下界前只补金靴 (4 金锭, 不碰钻石件): 没穿没持有 → 收集合成
    // (粘性实例防每tick重启); 背包有 → 穿上; 已穿 → 放行 _goToNetherTask。
    private Task _preNetherGoldenBootsTask = null;
    // (2026-09-12 金靴独立超时 + 下界补链, 用户"寻找金子做靴子需要有(时间限制),
    // 可以在地狱里找到足够金子然后再做") 原实现的两处无界: ① 金靴与钻石件同在
    // COLLECT_EYE_ARMOR, 共用 DIAMOND_COLLECT_LIMIT_MS (5min) 预算, 金靴收集本身
    // 在吃钻石的时间; ② 钻石超时后 getPreNetherGoldenBootsTask 单独补金靴, 但那个
    // 方法自身没有超时 → 主世界凑不到金矿就永久停留, 永远进不了下界。
    // 现在两道独立窗口: 主世界最多 PRE_NETHER_BOOTS_TIMEOUT_MS; 用尽则置
    // _preNetherBootsDeferredToNether 并放行 _goToNetherTask, 进下界后由 NETHER
    // case 的 getNetherGoldenBootsTask 再给 NETHER_BOOTS_TIMEOUT_MS — 下界金矿
    // (y=10~117 均匀分布, 掉金粒) 极易凑够 4 金锭, 二次窗也用尽才彻底放弃。
    private long _preNetherBootsStartMs = -1;
    private boolean _preNetherBootsDeferredToNether = false;
    private static final long PRE_NETHER_BOOTS_TIMEOUT_MS = 3 * 60_000L;
    private long _netherBootsStartMs = -1;
    private boolean _netherBootsGivenUp = false;
    private static final long NETHER_BOOTS_TIMEOUT_MS = 3 * 60_000L;
    private final Task _buildMaterialsTask;
    private final PlaceBedAndSetSpawnTask _setBedSpawnTask = new PlaceBedAndSetSpawnTask();
    private final GoToStrongholdPortalTask _locateStrongholdTask;
    private final Task _goToNetherTask = new DefaultGoToDimensionTask(Dimension.NETHER); // To keep the portal build cache.
    private boolean _collectingEyes;
    // 木头是否已囤满过一次（minWoodLogs）。囤满后只有原木跌破
    // woodRestockThreshold（低水位）才重新去囤，避免 64 根用掉 1 根
    // 就被"收集 64 原木"任务拽回去挖树的优先级过高问题。
    private boolean _woodStockpiled = false;
    // (2026-08-27 48原木一次性粘性实例, 用户原则) 木头囤货作为"一次性任务"：
    // 一旦派发就把实例粘住，跑完（isFinished）才释放，绝不每 tick 新建一个
    // 48 原木任务把其他任务链压没（用户实测: 需工具→需木棍→需木板→需原木,
    // 挖木头被自动要求挖到48, 是旧逻辑每 tick new 48 原木任务抢占所致）。
    // 其他子任务需要少量木头时用各自独立的 target（几根/木板），不继承 48。
    private Task _woodStockpileTask = null;
    // (2026-09-16 囤木前做石斧任务粘性缓存) 旧逻辑 prependAxeForWood 每 tick 都
    // 新建 getItemTask(STONE_AXE,1) → TaskRunner 的 isEqual 判定为新实例 → 每 tick
    // 停旧起新, 石斧链 (挖圆石→合台→合棍→合斧) 每次从头跑、被自己重建打断, 形成
    // "挖 1 根原木→被合成抢占→重来" 死循环 (Restocking wood 0/4 出现 4467 次实锤)。
    // 粘滞复用同一实例, 直到石斧到手 (isFinished) 才清缓存, 自然回落原木收集。
    private Task _stoneAxeTask = null;
    // (2026-09-16 迟滞修横跳) "无斧"判定的迟滞起点: 0 = 上次判定有斧。
    // 背景: isItemOwned(USE) 是真实背包直读, 开屏/光标移动/合成格收发窗口期
    // 会瞬时数不到斧子 → 主分支 "Restocking wood (logs)" ↔ "crafting stone axe
    // first" 每 ~3.4s 翻转 (20260916-195358 场 8 分钟各 70 次实锤), 每次翻转都
    // 触发 baritone 重规划 = 原地反复跳。现有 2s 去抖只压子任务切换, 压不住主
    // 分支横跳。修法: 无斧需连续持续 3s 才回切斧链, 期间维持补货分支; 真丢斧只损失 3s。
    private long _axeAbsentSinceMs = 0L;
    private static final long AXE_ABSENT_HYSTERESIS_MS = 3000L;
    // (2026-09-17) [AXE-HYST] 诊断的边沿闩锁: true = 本轮"无斧"已打过点, 直到
    // 重新拿到斧 (axeOwned 分支) 才复位。防迟滞放行后的长驻状态每 tick 刷屏。
    private boolean _axeHystLogged = false;
    // (2026-08-31 收熔炉取空) 收熔炉前的 EmptyFurnaceTask 粘性实例: 一旦派发就
    // 粘住直到完成(取尽炉内物品+拆炉带走), 防止每 tick 新建被其他分支切走,
    // 也确保 onTick 顶部延续块能持续返回同一实例。
    private EmptyFurnaceTask _emptyFurnaceTask = null;
    // (2026-09-08 仅掉落物收炉粘性) 旧实现每 tick `return new MineAndCollectTask(...)`
    // 全新实例 — 配合 MineOrCollectTask 的"无目标空转放弃"兜底会变成
    // 空转→放弃→重建 的循环, 且每次重建丢失探索/捡取记忆。改粘性实例;
    // 放弃后的重试节流由收炉侧线整体冷却 _furnaceSidelineGiveUpUntilMs (30s) 统一接管。
    private MineAndCollectTask _pickupFurnaceDropTask = null;
    // (2026-09-08) 收炉侧线整体冷却截止时间: EmptyFurnaceTask/掉落物收集因无目标
    // 空转放弃后置 now+30s, 冷却期内收炉分支整体让位主线。
    private long _furnaceSidelineGiveUpUntilMs = 0;
    // (2026-09-08 收工作台粘性+超时兜底) 旧实现每 tick `return new MineAndCollectTask(...)`
    // 全新实例且无任何兜底 — 与找岩浆主线每 2s 互抢 ping-pong (日志 22:56 实锤:
    // isMainLineBusy 把收工作台自身的 MineAndCollectTask 判"忙", 分支刚跑就把自己
    // 判掉, 岩浆主线抢回; 掉落物 itemDropped 又无距离闸门), 每次都差几格没吸到。
    // 改粘性实例 + 顶部延续块保活; 15s 仍没拿到或内部空转放弃 → 触发层冷却 30s。
    private MineAndCollectTask _pickupCraftingTableTask = null;
    // (2026-09-08) 收工作台侧线整体冷却截止时间: 放弃后置 now+30s, 冷却期内分支让位。
    private long _craftingTableSidelineGiveUpUntilMs = 0;
    // (2026-09-16 铁装"先做齐再穿") 铁装合成阶段进入时间: 缺件>0 时开始计时,
    // 超 90s 材料断供则回退为"先穿已持有的"。缺件清零即复位。
    private long _ironArmorCraftSinceMs = 0;
    // (2026-09-08) 当前收工作台粘性实例的启动时间, 用于 15s 外部超时判定。
    private long _pickupCraftingTableStartMs = 0;
    // (2026-08-27 床保险一次性完成) 地表白天"必须持床"只执行一轮: 拿到并持有
    // 一张床(或睡过一张床已设重生点) 就置 true 放行, 之后不再因"背包没床"打断
    // 木头/食物/挖矿主线 (否则每次回地表都被拽去拿床)。与 _woodStockpiled 同构。
    private boolean _bedInsuranceDone = false;
    // (2026-08-27 收床来源距离) 感知到的现成床在 96 格内才算"可挖去拿"——
    // 超过则走杀羊/羊毛→做床合成分支, 不为一张床长途折返。
    private static final double BED_GRAB_RANGE = 96.0;

    // (2026-09-11 收床粘性延续 + latch) 事故 20260911-185745 实锤: 18:59:17.697
    // 进入 "Picking up my placed bed (recover)" → DestroyBlockTask(-174,77,276)
    // START, 17.898 床方块变空气后闸门 else 分支清掉记忆并掉进 "Stockpiling wood
    // (logs): 20/25" → 收床 took=201ms, 此后 hasBed=false 持续 92s 到日志结束
    // (床掉落物无人认领, 5 分钟后 despawn)。根因是收床闸门每 tick 重估实时世界
    // 状态、无 latch —— "分支闸门必须 latch" 铁律的又一例。修法同
    // _pickupCraftingTableTask: 粘性实例 + onTick 顶部延续块; 15s 仍未完成 →
    // 放弃 + 30s 冷却 (防止"放弃→立即重建同一个→再空转"的循环)。
    private Task _bedRecoverTask = null;
    private long _bedRecoverStartMs = 0;
    private long _bedRecoverGiveUpUntilMs = 0;
    private static final long BED_RECOVER_TIMEOUT_MS = 15_000;
    private static final long BED_RECOVER_GIVEUP_COOLDOWN_MS = 30_000;
    // (2026-09-11 清记忆宽限) 床方块消失 ≠ 床彻底丢了: 掉落物可能还在下落, 甚至
    // 还没同步进客户端实体表 (方块更新包与实体生成包不是同一 tick 保证送达)。
    // 旧实现见到 still==null && !bedDropNearby 就立刻 setLastSelfPlacedBed(null)
    // 永久清除记忆 → 掉落物再也无人认领。改为宽限 N 秒内保留记忆继续重试。
    private long _bedMissingSinceMs = 0;
    private static final long BED_MEMORY_GRACE_MS = 5_000;
    private final Task _getOneBedTask = TaskCatalogue.getItemTask("bed", 1);
    private final Task _sleepThroughNightTask = new SleepThroughNightTask();
    private final Task _killDragonBedStratsTask = new KillEnderDragonWithBedsTask(new WaitForDragonAndPearlTask());

    // (2026-09-17 判据统一) 原 _surfaceFlipTimer/_stableUnderground 双稳态去抖已删:
    // 它包装的 WorldHelper.isDeepUnderground 现在本身就走 SurfaceHelper 的
    // 5 点多数投票 + 5s 驻留, 再叠一层 2s 去抖只会让结论多滞后 2s。见 isUndergroundDebounced。
    // (2026-08-22 贪心收集) 顺手挖眼前可见矿石的粘性任务实例 + 触发
    // 节流 (无活可干时每 3 秒才重扫一次, 防 onTick 每 tick 全量扫描)。
    private CollectVisibleOresTask _greedyOreTask = null;
    // (2026-09-01 防横跳, 用户) 贪心矿检查冷却 1→8 游戏秒: 贪心批次释放后给主线
    // (铁套/挖铁等)长窗口推进, 不再每 1s 就因范围可见矿再次打断 → 铁套↔贪心横跳。
    // 触发本身仍快(冷却到点立即判), 只是两次贪心之间留 8s 缓冲。
    // (2026-09-07) 改为动态 interval: 普通阶段 GREEDY_ORE_CHECK_COOLDOWN_NORMAL,
    // 放弃钻石→进下界阶段由 settings.preNetherGreedyCooldownSeconds 放大 (见
    // onTick 贪婪检查块注释, 防贪婪↔燧石链乒乓)。
    // (2026-09-11 8s→3s, 用户"看到金子总是没有第一时间触发贪婪挖矿, 总是过一会儿才
    // 触发") 检查节拍即触发延迟上界。8s 是 2026-09-07 防频繁换任务定的; 进下界前
    // 阶段已有 45s 保护窗(preNetherGreedyCooldownSeconds), 普通阶段 3s 乒乓风险可控。
    private static final double GREEDY_ORE_CHECK_COOLDOWN_NORMAL = 3;
    private final TimerGame _greedyOreCheckCooldown = new TimerGame(GREEDY_ORE_CHECK_COOLDOWN_NORMAL);
    // (2026-09-08 烧肉 ping-pong 修复) 烧炼任务实例缓存保活。本分支原来每 tick
    // new SmeltInFurnaceTask, 且选肉按 RAW_FOODS 顺序 + getItemStorage().hasItem
    // (不含光标) —— 搬运子任务把生肉拿起在光标上的那一 tick, hasItem(生肉)=false
    // → 父任务立刻换下一块肉的子任务抢切 → 被打断的任务 Dumping cursor 把肉放回
    // 背包 → 下一 tick hasItem 又变 true 切回。beef/mutton/porkchop 2s 周期互抢
    // 39 分钟 (11:33~12:12, TaskPingPong 449 次), 净进度≈0 (用户旁观+日志实锤)。
    // 修法: 与 _greedyOreTask 同模式缓存保活, 守卫计数用含光标的
    // countItemsInInventoryReal; 生肉彻底消失且炉已排空才弃缓存换肉。
    private SmeltInFurnaceTask _cachedSmeltTask = null;
    // (2026-09-08 二次修复) "生肉消失+炉排空"弃缓存需连续成立 20 tick (~1s):
    // 单 tick 的缓存陈旧/界面同步抖动不再触发换肉抢切 (15:17 实测牛肉刚进炉
    // 1 tick 后就被换肉任务抢切, 疑似 isFurnaceDrained 单 tick 误判, 待诊断日志实锤)。
    private int _smeltDiscardStreak = 0;
    private static final int SMELT_DISCARD_STREAK_TICKS = 20;
    // "生肉没了但炉未排空"状态翻转记录 (诊断用, 只记翻转不刷屏)
    private boolean _lastSmeltGoneNotDrained = false;
    // (2026-08-22 工作台即收) findNearbyCraftingTable 的兜底暴力扫描
    // 节流 (16³=36k 次 getBlockState, 1 秒一次足够)。
    private final TimerGame _pickupTableScanTimer = new TimerGame(1);
    private final TimerGame _pickupFurnaceScanTimer = new TimerGame(1);
    // [PickupTable] 诊断日志节流 (L341 每 tick 判定, 2 秒打一条)。
    private final TimerGame _pickupTableLogTimer = new TimerGame(2);
    // [BedPickup] 诊断日志节流 (收床判定, 2 秒打一条)。
    private final TimerGame _bedPickupLogTimer = new TimerGame(2);
    // [IronDiag] 关键材料量/阶段判定诊断节流 (木/食/容器/铁装/铁甲各分支复用,
    // 2 秒一条)。核心目标: 实锤"已有≥6铁锭却仍去拿工作台/收熔炉/囤木而不推进
    // 铁装备"这类症状 —— 每个分支打一份当前阶段+关键材料量+为何选此分支的键值。
    private final TimerGame _ironDiagTimer = new TimerGame(2);
    // (2026-09-12 跨批次对撞) "贪心让位给主线"诊断日志节流 (5s 一条) — 长时间挖矿时
    // 贪心检查每 3s 一次, 不节流会持续刷屏。
    private final TimerGame _greedyYieldLogTimer = new TimerGame(5);
    // (2026-08-22) getPitEscapeY 每 tick 扫 960 个方块 (4 方向×4 距离×
    // 60 高度), 不能每 tick 重算。1 秒缓存 + food/wood 分支共用。
    private final TimerGame _pitEscapeCacheTimer = new TimerGame(1);
    private int _pitEscapeYCache = -1;

    // ==== [Diag-flow] 子任务切换诊断 (2026-08-21 床↔木头横跳排查) ====
    // BeatMinecraft2Task 的分支决策每 tick 全量重估, 任何一个条件翻转
    // (canSleep 雷暴抖动 / isPlacedBedNearby 6 格半径进出 / 库存数量变化)
    // 都会让子任务整棵树被 stop+restart。这里记录"上一次返回的子任务",
    // 变化时打出全部关键条件的快照, 让横跳根因一眼可见。
    private String _lastDispatchedChildDesc = null;
    private AltoClef _modRef; // onStart 缓存, 仅诊断用
    private long _lastFlowLogMs = 0;
    // canSleep 去抖: canSleep()=false 持续该时长后才真正退出睡眠模式。
    // 雷暴在阈值边缘抖动(isThundering 秒级翻转)曾把 SleepThroughNight
    // 和收集任务之间高频切换(2026-08-21 20:49 日志: PlaceBedAndSetSpawn
    // 被 Mine And Collect logs 打断, 当时床 anyFound=true 就在旁边)。
    // _wasSleepableOnce (2026-08-21 晚修复): TimerGame 未 reset 时新连接
    // 的前 30 秒 elapsed()=false, 曾导致 effectiveCanSleep 恒真 → 大白天
    // 也进睡觉分支 (23:35 日志实锤: canSleep=false time=1658 却睡觉)。
    // 守卫: 只有"本任务周期内真的可睡过"才允许 false 后的 30 秒宽限。
    // (2026-08-31 夜间找床/羊超时, 用户) 夜里没有可用床/找不到羊时, 若 30 秒内
    // 仍拿不到床就放弃找床, 转挖矿/收食物(熬夜推进), 不无限找羊。超时后冷却
    // SKIP_NIGHT_BED_FOR_MS 毫秒, 避免下 tick 又回头找床打断挖矿。
    private final TimerGame _nightBedFindTimer = new TimerGame(30);
    private boolean _nightBedFindInProgress = false;
    // (2026-08-31 夜间找床放弃一次就够, 用户) 本夜已放弃找床 → 直到白天不再重启。
    private boolean _nightBedGivenUp = false;
    // (2026-09-17) "背包已有 ≥3 同色羊毛 → 不放弃找床"这一分支的边沿闩锁, 只用于
    // 诊断打点去重 (长驻状态分支必须边沿触发, 见 TimeoutLog 口径)。白天重置时归零。
    private boolean _bedWoolHoldLogged = false;
    private long _skipNightBedUntilMs = 0;
    private static final long SKIP_NIGHT_BED_FOR_MS = 45_000;
    private final TimerGame _notSleepableTimer = new TimerGame(30);
    private boolean _wasSleepableOnce = false;
    // rawCanSleep 持续时长计时 (2026-08-22): 须连续 5 秒为真才武装 30s 宽限,
    // 防止世界加载瞬间的 canSleep 幻影 (时间未同步的头几 tick) 触发白天睡觉。
    private final TimerGame _rawSleepableStreakTimer = new TimerGame(5);
    // belowSeaLevel 去抖 (2026-08-23): 游泳时玩家 Y 坐标在 63 (海平面) 边缘
    // 上下波动 (水面 63 / 潜入 62), 旧逻辑 y<63 直接判"水下", 导致睡眠分支
    // 与 Going-to-Nether 分支在 tick 间反复抢占 (实测 13:09 每 2 秒横跳一次)。
    // 只有 y<63 持续超过此 tick 数才真正视为"水下熬夜", 游泳起伏不再翻转。
    private static final int BELOW_SEA_LEVEL_DEBOUNCE_TICKS = 20; // 1s @ 20tps
    private int _belowSeaLevelTicks = 0;
    // (2026-08-23 防火药水保命阶段) 打烈焰人前的金锭+猪灵交易阶段状态。
    // _fireResPhaseActive = 正在进行; _fireResPhaseDone = 已尝试过 (达标或
    // 超时), 不再重试。外层超时 (18 分钟) 比内层 (12 分钟) 长, 内层先放弃
    // 外层兜底。
    // (2026-09-06 用户) 目标从 1 瓶提到 2 瓶 (落单烈焰人走盾战不喝药,
    // 2 瓶留给刷怪笼房多只场景)。防任务打架: _fireResPhaseDone 是一次性
    // 闩 — 本实例内一旦结束 (达标或超时) 就不再进本阶段, 之后喝掉 1~2 瓶
    // (库存跌回 < 2) 也不会重新触发药水任务, 由喝药门槛 (CollectBlazeRodsTask)
    // 决定要不要花。
    private static final int FIRE_RES_POTION_TARGET = 2;
    private boolean _fireResPhaseActive = false;
    private boolean _fireResPhaseDone = false;
    private final TimerGame _fireResPhaseTimer = new TimerGame(18 * 60);

    // (2026-09-14 用户"两瓶都喝完就不再重收"要改成允许重收, 但加冷却时间, 不要马上触发)
    // 旧行为: _fireResPhaseDone 一次闩 → 2 瓶喝完后下界后半程全程裸奔, 再也不会去收。
    // 新行为: 阶段关闭时起算冷却; 冷却是 static —— BeatMinecraft2Task 实例会因死亡重开/
    // 任务重建而重建, 放实例字段会被洗掉, 直接退化成"立刻重收"(用户明确不要)。
    // 180s: 给 bot 一个"先用现有条件活下去"的窗口; 猪灵交易一轮约 12 分钟,
    // 反复进本阶段会阻塞主线, 所以宁可长一点。
    private static final long FIRE_RES_REOPEN_COOLDOWN_MS = 180_000;
    private static long _fireResReopenAtMs = 0;

    // (2026-09-09 进下界前排空熔炉, 用户"把烧金子挪到进传送门之前") 进下界前若
    // 16 格内有非空熔炉 (input/fuel/output 任一有物), 先 EmptyFurnaceTask 取尽
    // 并连炉带走: ①炉里的金锭/物资不丢 — 11:11 场次实测, 门旁炉内 8 金锭被
    // FireRes 阶段接管后 18 分钟无人取, 随烈焰棒抢切永远留在下界; ②炉+剩燃料
    // 随身进下界, 缓解下界 "no wood here" 燃料死结。放弃兜底 (凡"停下等"必须
    // 有放弃上限): 外部 90s 超时或子任务 gaveUpWandering() → 30s 触发层冷却后
    // 放行进下界 (冷却加在本闸门入口, 任务实例重建绕不过)。
    private EmptyFurnaceTask _preNetherFurnaceDrainTask = null;
    private long _preNetherFurnaceDrainStartMs = 0L;
    private long _preNetherFurnaceDrainGiveUpUntilMs = 0L;
    private final TimerGame _preNetherDrainScanTimer = new TimerGame(1.0);
    private BlockPos _preNetherDrainScanPos = null;

    // (2026-09-09 进下界前清仓生金, 用户"有多少生金矿就烧多少") 进下界前若背包
    // 持有 RAW_GOLD, 先全量熔炼成金锭再进门 (不额外挖矿凑数 — 输出目标=现有锭+
    // 生金数, 材料目标=生金数, 严格匹配绝不缺料, SmeltInFurnaceTask 不会派挖矿)。
    // 这样下界 FireRes 阶段的猪灵交易直接有锭可用, 避免重演"下界挖金粒 18 分钟"。
    // 实例粘性: 生金放进炉后背包计数归零, 必须靠缓存实例 keep-alive 到烧完,
    // 否则下 tick 就被排空闸门把炉里的金取出来 (与排空闸门打架)。
    // 放弃兜底: 外部 5 分钟超时 → 30s 冷却重试; 累计 2 次放弃后本次会话永久
    // 放行 (生金留着生金, 不无限阻塞进门)。
    private SmeltInFurnaceTask _preNetherGoldSmeltTask = null;
    private long _preNetherGoldSmeltStartMs = 0L;
    private long _preNetherGoldSmeltGiveUpUntilMs = 0L;
    private int _preNetherGoldSmeltGiveUpCount = 0;
    private static final long PRE_NETHER_GOLD_SMELT_TIMEOUT_MS = 5 * 60_000L;

    private Task getPreNetherGoldSmeltTask(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (_preNetherGoldSmeltGiveUpCount >= 2 || now < _preNetherGoldSmeltGiveUpUntilMs) {
            return null;
        }
        // 实例粘性: 烧炼进行中持续返回同一实例 (此时背包 rawGold 已进炉归零,
        // 不能按背包重新判定, 否则排空闸门会把炉里的金取出来)。
        if (_preNetherGoldSmeltTask != null && !_preNetherGoldSmeltTask.isFinished(mod)) {
            if (now - _preNetherGoldSmeltStartMs > PRE_NETHER_GOLD_SMELT_TIMEOUT_MS) {
                Debug.logMessage("(PreNetherGold) Gold smelt timed out, 30s cooldown then retry.");
                Debug.logToFile("altoclef_diag.log", "[MAIN-FIX]\t[PreNetherGold]\ttimeout, cooldown 30s, giveUpCount=" + _preNetherGoldSmeltGiveUpCount);
                _preNetherGoldSmeltGiveUpCount++;
                _preNetherGoldSmeltGiveUpUntilMs = now + 30_000;
                _preNetherGoldSmeltTask = null;
                return null;
            }
            setDebugState("Smelting held raw gold into ingots before Nether");
            return _preNetherGoldSmeltTask;
        }
        _preNetherGoldSmeltTask = null;
        // 口径三件套规则: 材料够不够判定用含合成格口径 (344bd043), 防
        // 配方书把生金搬进格后背包计数"骤减"导致误判/打断。
        int rawGold = StorageHelper.countItemInInventoryRealIncludingCraftGrid(mod, net.minecraft.item.Items.RAW_GOLD);
        if (rawGold <= 0) {
            return null; // 没有生金, 直接放行
        }
        int ingots = StorageHelper.countItemInInventoryRealIncludingCraftGrid(mod, net.minecraft.item.Items.GOLD_INGOT);
        _preNetherGoldSmeltTask = new SmeltInFurnaceTask(new SmeltTarget(
                new ItemTarget(net.minecraft.item.Items.GOLD_INGOT, ingots + rawGold),
                new ItemTarget(net.minecraft.item.Items.RAW_GOLD, rawGold)));
        _preNetherGoldSmeltStartMs = now;
        Debug.logMessage("(PreNetherGold) Holding " + rawGold + " raw gold — smelting all into ingots before entering Nether.");
        Debug.logToFile("altoclef_diag.log", "[MAIN-FIX]\t[PreNetherGold]\tsmelt rawGold=" + rawGold + " ingots=" + ingots);
        setDebugState("Smelting held raw gold into ingots before Nether");
        return _preNetherGoldSmeltTask;
    }

    /**
     * [Diag-flow] 分支切换诊断: debug 状态变化 ≈ 子任务切换。每次变化
     * 打出全部关键条件快照, 下次测试直接从日志看横跳是哪个条件翻转
     * 引起的。200ms 节流防止高频横跳刷爆日志。
     */
    @Override
    protected void setDebugState(String state) {
        super.setDebugState(state);
        if (state.equals(_lastDispatchedChildDesc)) return;
        String prev = (_lastDispatchedChildDesc == null ? "(start)" : "\"" + _lastDispatchedChildDesc + "\"");
        long nowMs = System.currentTimeMillis();
        // (2026-09-16 日志卫生) 旧实现把 _lastDispatchedChildDesc 的更新放在 200ms 节流之前:
        // 高频横跳时窗口内每次新状态都覆盖去重基准, 窗口一开立刻重发, 每 200ms 必刷一条
        // 聊天 (实锤 5123 条占 16%)。现改为: ①聊天节流 200ms→1000ms; ②落盘渠道每次状态
        // 变化都写 (文件不刷屏); ③仅当聊天真正发出才把去重基准推进到当前 state, 横跳回归
        // 已发状态时被上方去重吞掉, 消除"窗口内新状态被吞、窗口一开立刻重发"。
        if (_modRef != null && AltoClef.inGame()) {
            try {
                net.minecraft.client.world.ClientWorld world = MinecraftClient.getInstance().world;
                String snap = "[Diag-flow] branch: " + prev + " -> \"" + state + "\""
                        + " | canSleep=" + WorldHelper.canSleep()
                        + " time=" + (world != null ? (int) (world.getTimeOfDay() % 24000) : -1)
                        + " thunder=" + (world != null && world.isThundering() && world.isRaining())
                        + " hasBedItem=" + _modRef.getItemStorage().hasItem(ItemHelper.BED)
                        + " bedNearby6=" + isPlacedBedNearby(_modRef)
                        + " bedPerceived=" + isBedPerceived(_modRef)
                        + " logs=" + _modRef.getItemStorage().getItemCount(ItemHelper.LOG)
                        + " woodStockpiled=" + _woodStockpiled
                        + " deep=" + WorldHelper.isDeepUnderground(_modRef);
                // 落盘: 每次状态变化都写, 不受聊天节流影响。
                Debug.logToFile("altoclef_diag.log", snap);
                // 聊天: 1s 节流, 仅真正发出才更新去重基准。
                if (nowMs - _lastFlowLogMs >= 1000) {
                    Debug.logMessage(snap);
                    _lastFlowLogMs = nowMs;
                    _lastDispatchedChildDesc = state;
                }
            } catch (Exception ignored) {
                // 诊断日志绝不影响主逻辑
            }
        }
    }

    // End specific dragon breath avoidance
    private final DragonBreathTracker _dragonBreathTracker = new DragonBreathTracker();
    private boolean _escapingDragonsBreath;

    public BeatMinecraft2Task() {
        _locateStrongholdTask = new GoToStrongholdPortalTask(_config.targetEyes);
        _buildMaterialsTask = new GetBuildingMaterialsTask(_config.buildMaterialCount);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _modRef = mod; // [Diag-flow] 诊断日志用
        _lastDispatchedChildDesc = null;
        // [Diag] 会话标记: 每轮测试分隔, 避免新旧日志混淆 (用户 2026-08-22
        // 反馈"看到旧日志"找不到本次关键信息)。日志文件累积多会话时,
        // 直接搜索 "SESSION START" 即可定位本轮起点。
        Debug.logMessage("===== BeatMinecraft2Task SESSION START =====");
        Debug.logToFile("altoclef_diag.log", "===== SESSION START (BeatMinecraft2Task.onStart) =====");

        // Add a warning to make sure the user at least knows to change the settings.
        String settingsWarningTail = "in \".minecraft/altoclef_settings.json\". @gamer may break if you don't add this! (sorry!)";
        if (!ArrayUtils.contains(mod.getModSettings().getThrowawayItems(mod), Items.END_STONE)) {
            Debug.logWarning("\"end_stone\" is not part of your \"throwawayItems\" list " + settingsWarningTail);
        }
        if (!mod.getModSettings().shouldThrowawayUnusedItems()) {
            Debug.logWarning("\"throwawayUnusedItems\" is not set to true " + settingsWarningTail);
        }

        mod.getBlockTracker().trackBlock(TRACK_BLOCKS);
        mod.getBlockTracker().trackBlock(ItemHelper.itemsToBlocks(ItemHelper.BED));
        // (2026-09-05 睡觉跳过判定用) 门建好后感知到 NETHER_PORTAL → 跳过夜里睡觉
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.ENDER_EYE, Items.BLAZE_ROD, Items.ENDER_PEARL, Items.CRAFTING_TABLE);
        mod.getBehaviour().addProtectedItems(ItemHelper.BED);
        // (2026-09-17) 钻石甲永不进丢弃级联: 203154 场实锤刚做好的钻石甲被腾位链
        // 当垃圾丢进坑又捡回 (装备从未穿戴成功的一部分根因)。保护后满包腾位会
        // 找别的垃圾, 不会动盔甲。
        mod.getBehaviour().addProtectedItems(ItemHelper.DIAMOND_ARMORS);
        // Allow walking on end portal
        mod.getBehaviour().allowWalkingOn(blockPos -> _enterindEndPortal && mod.getChunkTracker().isChunkLoaded(blockPos) && mod.getWorld().getBlockState(blockPos).getBlock() == Blocks.END_PORTAL);

        // Avoid dragon breath
        mod.getBehaviour().avoidWalkingThrough(blockPos -> {
            return WorldHelper.getCurrentDimension() == Dimension.END && !_escapingDragonsBreath && _dragonBreathTracker.isTouchingDragonBreath(blockPos);
        });

        // Don't break the bed we placed near the end portal
        mod.getBehaviour().avoidBlockBreaking(blockPos -> {
            if (_bedSpawnLocation != null) {
                return blockPos.equals(WorldHelper.getBedHead(mod, _bedSpawnLocation)) || blockPos.equals(WorldHelper.getBedFoot(mod, _bedSpawnLocation));
            }
            return false;
        });
    }

    @Override
    protected Task onTick(AltoClef mod) {
        /*
        if in the overworld:
          if end portal found:
            if end portal opened:
              @make sure we have iron gear and enough beds to kill the dragon first, considering whether that gear was dropped in the end
              @enter end portal
            else if we have enough eyes of ender:
              @fill in the end portal
          else if we have enough eyes of ender:
            @locate the end portal
          else:
            if we don't have diamond gear:
              if we have no food:
                @get a little bit of food
              @get diamond gear
            @go to the nether
        if in the nether:
          if we don't have enough blaze rods:
            @kill blazes till we do
          else if we don't have enough pearls:
            @kill enderman till we do
          else:
            @leave the nether
        if in the end:
          if we have a bed:
            @do bed strats
          else:
            @just hit the dragon normally
         */

        // By default, don't walk over end portals.
        _enterindEndPortal = false;

        // (2026-08-27 烧炼期间不打断) 正在烧炼 (SmeltInFurnaceTask) 时, 无论它
        // 在烧料/接燃料/做木板燃料 (2x2 合成) 哪个子阶段, 都不要用"顺手拾取合成台 /
        // 熔炉"去打断 —— 否则刚放下的熔炉被重放/掉落, 烧↔回收横跳 (用户实测
        // 01:01:52~59: 边烧土豆边被 "Pick up crafting table" 打断, 熔炉连续
        // "Pickup Dropped Items: [[furnace]x1]")。
        Predicate<Task> isSmeltingTask = task -> task instanceof SmeltInFurnaceTask;

        Predicate<Task> isCraftingTableTask = task -> {
            if (task instanceof DoStuffInContainerTask cont) {
                return cont.getContainerTarget().matches(Items.CRAFTING_TABLE);
            }
            return false;
        };

        // (2026-08-27 熔炉即收) 正在用熔炉就别抢。注意烧炼链路头部是
        // SmeltInFurnaceTask (ResourceTask), 未直接是 DoStuffInContainerTask —
        // 只判容器任务会漏掉"正在收燃料/材料"时误触发回收 → 用户实测: 刚放下熔炉
        // 烧东西就被回收分支挖掉, 烧↔回收反复横跳。故两个都判: 只要链上任一节点
        // 是烧炼或用熔炉的容器任务, 就禁止回收。
        Predicate<Task> isFurnaceTask = task -> {
            if (task instanceof SmeltInFurnaceTask) return true;
            if (task instanceof DoStuffInContainerTask cont) {
                return cont.getContainerTarget().matches(Items.FURNACE);
            }
            return false;
        };

        // (2026-08-27 睡/床优先) onTick 最顶部先跑睡眠/持床路由 — 抢在收合成台、
        // 装备、烤食、下矿等一切侧线任务之前。夜间先睡/弄床, 避免睡完拆床被
        // "Get catalogued iron gear" 等分支抢断 (用户实测: 放床后 going to bed
        // 被抢去下矿, 睡完拆床永远做不完)。
        {
            Task nightSleepFirst = getNightSleepRouting(mod);
            if (nightSleepFirst != null) {
                return nightSleepFirst;
            }
        }

        // (2026-09-11 收床粘性延续) 一旦进入收床(撸自放床 / 捡床掉落物), 就持续
        // 返回同一实例直到完成 —— 睡眠路由(最高优先)之后、一切侧线任务之前,
        // 绝不被屯木头/装备/下矿半路抢断。见 _bedRecoverTask 字段注释 (事故
        // 20260911-185745: 收床 201ms 就被 "Stockpiling wood" 顶掉, 床永久丢失)。
        // 放在这里而不是最顶部: 夜间睡/弄床仍可抢占收床 (睡眠优先级更高)。
        if (_bedRecoverTask != null) {
            boolean timedOut = System.currentTimeMillis() - _bedRecoverStartMs > BED_RECOVER_TIMEOUT_MS;
            if (WorldHelper.getCurrentDimension() == Dimension.END
                    || timedOut || _bedRecoverTask.isFinished(mod)) {
                if (timedOut) {
                    Debug.logMessage("[BedPickup] recover timeout ("
                            + (BED_RECOVER_TIMEOUT_MS / 1000) + "s), giving up + "
                            + (BED_RECOVER_GIVEUP_COOLDOWN_MS / 1000) + "s cooldown");
                    _bedRecoverGiveUpUntilMs = System.currentTimeMillis() + BED_RECOVER_GIVEUP_COOLDOWN_MS;
                }
                _bedRecoverTask = null;
            } else {
                return _bedRecoverTask;
            }
        }

        // (2026-08-27 白天优先收床) 错过了夜(没睡成)→ 我方自放床会滞留世界没人拆,
        // 随后被"收合成台/装备/下矿"等分支抢走, 床永久留在原地 (用户实测: 放床后
        // 早晨到, canSleep=false, going to bed 被 iron gear 抢走, 睡完/错过夜床
        // 都没拆)。这里在一切侧线任务之前回收: 自放床还在→拆掉; 只剩掉落物→捡起;
        // 都没了→清记忆。是否睡过都照收 (speedrunner 带床走)。
        {
            BlockPos strBed = mod.getLastSelfPlacedBed();
            if (strBed == null) {
                _bedMissingSinceMs = 0;
            } else if (!_ranStrongholdLocator
                    && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD
                    && System.currentTimeMillis() >= _bedRecoverGiveUpUntilMs) {
                // (2026-09-11) 区块未加载时 getBlockState 恒返回空气 → 会误判"床没了"
                // 并把记忆清掉 (走远再回来 = 丢床)。未加载一律不判定、宽限计时归零,
                // 且必须【放行后续所有分支】(不能 return null 把整棵树卡死)。
                if (mod.getChunkTracker().isChunkLoaded(strBed)) {
                    BlockPos still = selfBedStillThere(mod, strBed);
                    if (still != null) {
                        _bedMissingSinceMs = 0; // 床还在, 宽限计时作废
                        if (still.isWithinDistance(mod.getPlayer().getPos(), 8)) {
                            setDebugState("Picking up my placed bed (recover).");
                            _bedRecoverTask = new DestroyBlockTask(still);
                            _bedRecoverStartMs = System.currentTimeMillis();
                            return _bedRecoverTask;
                        }
                    } else if (bedDropNearby(mod, strBed)) {
                        setDebugState("Picking up my bed drop (recover).");
                        _bedRecoverTask = new PickupDroppedItemTask(new ItemTarget[]{new ItemTarget("bed", 1)}, true);
                        _bedRecoverStartMs = System.currentTimeMillis();
                        return _bedRecoverTask;
                    } else {
                        // (2026-09-11 清记忆宽限) 不立刻清 —— 掉落物可能还在下落/未同步。
                        scheduleForgetSelfBed(mod, strBed);
                    }
                } else {
                    _bedMissingSinceMs = 0;
                }
            }
        }

        // (2026-08-31 收熔炉取空粘性延续) 一旦进入"先取尽炉内物品再拆炉"
        // (EmptyFurnaceTask), 就持续返回同一实例直到完成 — 绝不被收工作台等
        // 侧线或主链分支半路抢断 (否则取空到一半被切走, 炉内剩物又被连炉挖掉)。
        if (_emptyFurnaceTask != null) {
            if (WorldHelper.getCurrentDimension() != Dimension.END
                    && _emptyFurnaceTask.isActive() && !_emptyFurnaceTask.isFinished(mod)) {
                return _emptyFurnaceTask;
            }
            if (_emptyFurnaceTask.isFinished(mod)) {
                // (2026-09-08) 拆炉收集因无目标空转放弃(而非正常完成) → 收炉侧线
                // 整体冷却 30 秒。冷却期内本分支不进入, 防止"放弃→立即重建同一个
                // 还是无目标的任务→再空转"的循环; 冷却结束后条件若仍成立自然重试
                // (那时掉落物多半已被吸走/消失, 或 bot 移动后 FOV 恢复)。
                if (_emptyFurnaceTask.gaveUpWandering()) {
                    _furnaceSidelineGiveUpUntilMs = System.currentTimeMillis() + 30_000;
                }
                _emptyFurnaceTask = null;
            }
        }

        // (2026-09-08 收工作台粘性延续) 一旦进入收工作台侧线, 就持续返回同一实例
        // 直到完成 — 绝不被找岩浆等主线半路抢断。放在分支闸门之前, 绕过
        // isMainLineBusy (该判定把收工作台自身的 MineAndCollectTask 也算"忙",
        // 是 22:56 收工作台↔找岩浆每 2s 互抢 ping-pong 的根因之一)。
        // 超时兜底: 启动 15s 仍没拿到 → 主动放弃 + 触发层冷却 30s, 杜绝
        // "永远差几格"式无限横跳; 内部无目标空转放弃同样进冷却。
        if (_pickupCraftingTableTask != null) {
            if (WorldHelper.getCurrentDimension() != Dimension.END
                    && _pickupCraftingTableTask.isActive() && !_pickupCraftingTableTask.isFinished(mod)) {
                if (System.currentTimeMillis() - _pickupCraftingTableStartMs > 15_000) {
                    Debug.logMessage("[PickupTable] pickup timeout (15s), giving up + 30s cooldown");
                    _craftingTableSidelineGiveUpUntilMs = System.currentTimeMillis() + 30_000;
                    _pickupCraftingTableTask = null;
                } else {
                    return _pickupCraftingTableTask;
                }
            }
            if (_pickupCraftingTableTask != null && _pickupCraftingTableTask.isFinished(mod)) {
                if (_pickupCraftingTableTask.gaveUpWandering()) {
                    _craftingTableSidelineGiveUpUntilMs = System.currentTimeMillis() + 30_000;
                }
                _pickupCraftingTableTask = null;
            }
        }

        // Portable crafting table.
        // If we're NOT using our crafting table right now and there's one nearby, grab it.
        // 2026-08-21: "nearby" is now enforced (CRAFTING_TABLE_GRAB_RANGE).
        // 2026-08-22: 移除 fail/cooldown 惩罚机制 — 16 格内的可见工作台是
        // 感知目标, 按"感知目标无时限"原则直接去拿。旧机制 (90s 窗口
        // 2 次失败 → 3 分钟冷却) 在任务横跳期间极易累积失败数, 表现为
        // "用完工作台不带走, 优先级被挖矿占去" (用户实测 11:28-11:40)。
        // 横跳根源已由 CataloguedResourceTask 粘性修复解决; 范围限制
        // (16 格) 本身就防住了旧版"追几十格外工作台"的问题。
        if (!_endPortalOpened && WorldHelper.getCurrentDimension() != Dimension.END && _config.rePickupCraftingTable && !mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)
                // (2026-08-31 顺路收容不让位打断主线) 收工作台只是"顺路", 主链
                // (铁装合成/下矿/食物/熔炼) 忙时一律让位, 绝不打断 (isMainLineBusy
                // 顺着 _sub 子链深查, 覆盖铁装 squash 任务内部展开的挖矿/熔炼树枝)。
                && !isMainLineBusy(mod)
                // (2026-09-12 v16 逃生优先, 20260912-190543 实锤) GoToSurfaceTask
                // 在链上时收台必须让位: 封闭空间做镐侧线刚把台放到地上, "包里
                // 没台 + 台在近旁"瞬间成立, 本分支每 2s 抢占挖垫爬升 (Mine And
                // Collect ↔ Returning to surface 乒乓, 10.7s 才爬 2 格)。台又
                // 跑不掉 (方块/掉落物就在井里), 爬出见天后本分支自然恢复。
                && !thisOrChildSatisfies(t -> t instanceof GoToSurfaceTask)
                // (2026-08-27 睡/床优先) 主世界夜间可睡时推迟收合成台 — 否则
                // 收合成台分支优先于睡眠分支, 反复把睡/弄床流程打断 (用户实测:
                // 摘床动作被 "Pick up crafting table" 打断没做完)。夜里先睡/弄床。
                && !isNightSleepPriority(mod)
                // (2026-08-30 木头优先, 用户"挖木头反复横跳") 收合成台只是"顺路",
                // 不盖过进行中的原木收集。若 _woodStockpileTask 还在跑就放行木头,
                // 否则 tableNearby/dropped 一闪烁就和木头任务互踩 (日志: 每2s
                // Stockpiling wood <-> Pick up crafting table 互跳)。
                // (2026-09-08) 收工作台侧线放弃冷却期: 上次超时/空转放弃后 30 秒内
                // 分支整体让位, 防止重建同一个捡不到的任务再横跳一轮。
                && System.currentTimeMillis() >= _craftingTableSidelineGiveUpUntilMs
                && !(_woodStockpileTask != null && !_woodStockpileTask.isFinished(mod))) {
            // (2026-08-22 修复) 不依赖 isBlockPerceivable: 刚放置/紧邻的工作台
            // LOS 射线判定不稳定, 用户实测 14:22 会话合成石镐后工作台留下不捡
            // (11:32/11:34 却捡过 → 判定抖动)。改为: BlockTracker 缓存位置 +
            // 世界真实存在 + 16 格内 (缓存未登记时兜底暴力扫描)。
            boolean tableNearby = findNearbyCraftingTable(mod);
            // [PickupTable] 诊断: 2 秒节流, 记录判定分解
            if (_pickupTableLogTimer.elapsed()) {
                _pickupTableLogTimer.reset();
                Debug.logToFile("altoclef_diag.log", "[PickupTable] check: near=" + tableNearby
                        + " cache=" + mod.getBlockTracker().getKnownLocations(Blocks.CRAFTING_TABLE).size()
                        + " dropped=" + mod.getEntityTracker().itemDropped(Items.CRAFTING_TABLE)
                        + " player=" + mod.getPlayer().getBlockPos());
            }
            if (tableNearby || mod.getEntityTracker().itemDropped(Items.CRAFTING_TABLE)) {
                setDebugState("Pick up crafting table while we're at it");
                diagIronPhase(mod, "pickup crafting table",
                        "rePickup=" + _config.rePickupCraftingTable
                                + " hasTable=" + mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)
                                + " tableNearby=" + tableNearby
                                + " dropped=" + mod.getEntityTracker().itemDropped(Items.CRAFTING_TABLE)
                                + " woodActive=" + (_woodStockpileTask != null && !_woodStockpileTask.isFinished(mod))
                                + " nightPrio=" + isNightSleepPriority(mod));
                // (2026-09-08) 粘性实例: 顶部延续块负责持续返回直到完成/超时,
                // 不再每 tick new (每 tick new 是与找岩浆互抢的根因之一)。
                if (_pickupCraftingTableTask == null) {
                    _pickupCraftingTableTask = new MineAndCollectTask(Items.CRAFTING_TABLE, 1, new Block[]{Blocks.CRAFTING_TABLE}, MiningRequirement.HAND);
                    _pickupCraftingTableStartMs = System.currentTimeMillis();
                }
                return _pickupCraftingTableTask;
            }
        }

        // (2026-08-27 熔炉即收) 烧完东西后把自放/近处的熔炉带走 — 与合成台同款
        // 站桩分支。条件: 主世界 / 门未开 / 配置开 / 背包无熔炉 / 当前不在用熔炉 /
        // (2026-08-31) 主链忙时让位 / 非夜间睡/床优先时段。检测 16 格内是否有
        // 【非空】熔炉, 有则先取尽炉内内容物再拆走。
        // (2026-09-09 只收非空熔炉, 用户指定) 空熔炉完全不触发本分支 — 路过村庄
        // 铁匠铺的空炉不再被绊; 自放且已彻底掏空的炉子留在原地, 下次熔炼重新合成。
        if (!_endPortalOpened && WorldHelper.getCurrentDimension() != Dimension.END && _config.rePickupFurnace
                && !mod.getItemStorage().hasItem(Items.FURNACE)
                // (2026-08-31 顺路收容不让位打断主线) 主链(铁装合成/下矿/食物/熔炼)
                // 忙时让位 — 否则熔炼铁锭到一半会被收熔炉拦腰打断 (用户 12:42 日志)。
                && !isMainLineBusy(mod)
                && !isNightSleepPriority(mod)
                // (2026-09-08) 收炉侧线放弃冷却期: 上次因无目标空转放弃后 30 秒内
                // 整个分支让位, 避免重建同一个仍无目标的任务再卡一轮。
                && System.currentTimeMillis() >= _furnaceSidelineGiveUpUntilMs) {
            boolean furnaceBlockNearby = findNearbyFurnace(mod);
            if (furnaceBlockNearby || mod.getEntityTracker().itemDropped(Items.FURNACE)) {
                setDebugState(furnaceBlockNearby ? "Emptying then picking up furnace" : "Pick up furnace while we're at it");
                diagIronPhase(mod, "pickup furnace",
                        "hasFurnace=" + mod.getItemStorage().hasItem(Items.FURNACE)
                                + " furnaceNearby=" + furnaceBlockNearby
                                + " dropped=" + mod.getEntityTracker().itemDropped(Items.FURNACE));
                Debug.logToFile("altoclef_diag.log",
                        "[MAIN-FIX]\t[PickupFurnace]\tmainline idle -> furnaceBlock=" + furnaceBlockNearby
                                + " dropped=" + mod.getEntityTracker().itemDropped(Items.FURNACE));
                // (2026-08-31 收熔炉前必先取尽炉内物品) 熔炉是【方块】在场时必须先取尽
                // input/output/fuel 三槽再拆, 杜绝"炉内还烧着/没取走的肉/铁被连炉挖掉丢失"
                // (EmptyFurnaceTask 取尽后自行拆炉)。用粘性实例, 顶部延续块负责持续返回
                // 直到完成。仅掉落物(无方块可拆)时直接捡。
                if (furnaceBlockNearby) {
                    if (_emptyFurnaceTask == null || _emptyFurnaceTask.isFinished(mod)) {
                        _emptyFurnaceTask = new EmptyFurnaceTask();
                    }
                    return _emptyFurnaceTask;
                }
                // (2026-09-08) 仅掉落物场景: 粘性实例。放弃(无目标空转 2s)后由
                // 分支顶部的 30 秒侧线冷却统一接管, 不再单独建 10 秒小冷却。
                if (_pickupFurnaceDropTask != null && _pickupFurnaceDropTask.isFinished(mod)) {
                    if (_pickupFurnaceDropTask.gaveUpWandering()) {
                        _furnaceSidelineGiveUpUntilMs = System.currentTimeMillis() + 30_000;
                    }
                    _pickupFurnaceDropTask = null;
                }
                if (_pickupFurnaceDropTask == null) {
                    // (2026-09-07) 熔炉需镐才掉落, 用 HAND 会挖而不掉 → 永远收不到。
                    _pickupFurnaceDropTask = new MineAndCollectTask(Items.FURNACE, 1, new Block[]{Blocks.FURNACE}, MiningRequirement.WOOD);
                }
                return _pickupFurnaceDropTask;
            }
        }

        // End stuff.
        if (WorldHelper.getCurrentDimension() == Dimension.END) {

            // Dragons breath avoidance
            _dragonBreathTracker.updateBreath(mod);
            for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer(mod)) {
                if (_dragonBreathTracker.isTouchingDragonBreath(playerIn)) {
                    setDebugState("ESCAPE dragons breath");
                    _escapingDragonsBreath = true;
                    return _dragonBreathTracker.getRunAwayTask();
                }
            }
            _escapingDragonsBreath = false;

            // If we find an ender portal, just GO to it!!!
            if (mod.getBlockTracker().anyFound(Blocks.END_PORTAL)) {
                setDebugState("WOOHOO");
                _enterindEndPortal = true;
                return new DoToClosestBlockTask(
                        blockPos -> new GetToBlockTask(blockPos.up()),
                        Blocks.END_PORTAL
                );
            }

            // If we have bed, do bed strats, otherwise punk normally.
            updateCachedEndItems(mod);
            // Grab beds
            if (mod.getEntityTracker().itemDropped(ItemHelper.BED) && mod.getItemStorage().getItemCount(ItemHelper.BED) < _config.requiredBeds)
                return new PickupDroppedItemTask(new ItemTarget(ItemHelper.BED), true);
            // Grab tools
            if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE)) {
                if (mod.getEntityTracker().itemDropped(Items.IRON_PICKAXE))
                    return new PickupDroppedItemTask(Items.IRON_PICKAXE, 1);
                if (mod.getEntityTracker().itemDropped(Items.DIAMOND_PICKAXE))
                    return new PickupDroppedItemTask(Items.DIAMOND_PICKAXE, 1);
            }
            if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET) && mod.getEntityTracker().itemDropped(Items.WATER_BUCKET))
                return new PickupDroppedItemTask(Items.WATER_BUCKET, 1);
            // Grab armor
            for (Item armorCheck : COLLECT_EYE_ARMOR_END) {
                if (!StorageHelper.isArmorEquipped(mod, armorCheck)) {
                    if (mod.getItemStorage().hasItem(armorCheck)) {
                        return new EquipArmorTask(armorCheck);
                    }
                    if (mod.getEntityTracker().itemDropped(armorCheck)) {
                        return new PickupDroppedItemTask(armorCheck, 1);
                    }
                }
            }
            if (mod.getItemStorage().hasItem(ItemHelper.BED) || (_killDragonBedStratsTask.isActive() && !_killDragonBedStratsTask.isFinished(mod))) {
                setDebugState("Bed strats");
                return _killDragonBedStratsTask;
            }
            setDebugState("No beds, regular strats.");
            return new KillEnderDragonTask();
        } else {
            // We're not in the end so reset our "end cache" timer
            _cachedEndItemNothingWaitTime.reset();
        }

        // Check for end portals. Always.
        if (!endPortalOpened(mod, _endPortalCenterLocation) && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
            Optional<BlockPos> endPortal = mod.getBlockTracker().getNearestTracking(Blocks.END_PORTAL);
            if (endPortal.isPresent()) {
                _endPortalCenterLocation = endPortal.get();
                _endPortalOpened = true;
            } else {
                // TODO: Test that this works, for some reason the bot gets stuck near the stronghold and it keeps "Searching" for the portal
                _endPortalCenterLocation = doSimpleSearchForEndPortal(mod);
            }
        }

        // (2026-08-27 睡眠/持床路由已上移为 getNightSleepRouting(), 在 onTick
        // 最顶部最先调用 — 保证"睡/床优先"于收合成台/装备/下矿等侧线任务。)

        // PICK UP OUR OWN BED (2026-08-21 睡完收床): 白天 (不可睡, sleep 分支
        // 不再 return) 时把自己放的床收回带走 — 床是稀缺过夜物资, 下一晚
        // 继续用 (speedrunner 行为)。严格只收自己放的 (_lastSelfPlacedBed
        // 由 PlaceBedAndSetSpawnTask 记录), 绝不拆别人的床; 8 格内才收
        // (睡醒必然贴脸), 跑远了的场景放弃, 避免为一张床长途折返。
        // (2026-08-22) 精确位置直撸: 旧的 MineAndCollectTask 按方块类型选
        // 目标, 床头/床尾两半在"破坏中断→黑名单→重选"间循环, 破坏进度被
        // 反复重置 (11:21-11:31 日志: 十分钟撸不掉一张床, 用户手动帮忙)。
        // DestroyBlockTask(记录的位置) 无目标选择环节; 掉落物由下一阶段
        // 的 drop 捡拾收走。
        BlockPos selfBed = mod.getLastSelfPlacedBed();
        // [BedPickup] 诊断 (2 秒节流): 睡完不收床时直接看 selfBed 记录是否
        // 存在、床是否还在原地、距离多少 — 定位是"没记录"还是"被抢占"。
        if (_bedPickupLogTimer.elapsed()) {
            _bedPickupLogTimer.reset();
            BlockPos bedAt0 = (selfBed == null) ? null : selfBedStillThere(mod, selfBed);
            Debug.logToFile("altoclef_diag.log", "[BedPickup] selfBed="
                    + (selfBed == null ? "null" : selfBed.toShortString())
                    + " stillThere=" + (bedAt0 == null ? "null" : bedAt0.toShortString())
                    + " hasBed=" + mod.getItemStorage().hasItem(ItemHelper.BED)
                    + " player=" + mod.getPlayer().getBlockPos());
        }
        // (2026-08-23 收床放宽) 去掉 !hasItem(BED) 条件: 旧逻辑背包有床就不收,
        // 但"去远处感知床睡"场景里 bot 自放的床一直滞留世界 (背包床也没放出去),
        // 收床分支永远不触发。现在只要自放床在 8 格内就撸掉收走 — 严格只收
        // _lastSelfPlacedBed 记录的床 (自己放的), 不碰别人的床。
        // (2026-08-26 修复: 强地牢阶段不撸自放床) 设置重生点流程 (Setting spawn
        // near end portal → PlaceBedAndSetSpawnTask) 放床设重生点, 若此时收床
        // 分支触发会把刚放的床立刻撸掉 → 重生点永远设不上, 任务链循环
        // (16:37 日志实锤: Interact using bed → "Picking up our placed bed" →
        // Destroy block 立刻撸)。强地牢阶段 (_ranStronglordLocator) 跳过收床。
        if (selfBed != null
                && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD
                && !_ranStrongholdLocator
                && System.currentTimeMillis() >= _bedRecoverGiveUpUntilMs
                // (2026-09-11) 区块未加载 → getBlockState 恒空气, 会误判"床没了"并
                // 在宽限后清记忆 (走远再回来 = 丢床)。未加载一律不判定。
                && mod.getChunkTracker().isChunkLoaded(selfBed)) {
            BlockPos bedAt = selfBedStillThere(mod, selfBed);
            if (bedAt == null) {
                // 床方块没了 (刚撸掉/被毁/放置失败)。若掉落物还躺在附近,
                // 先捡起来再清记忆 — 否则床永远丢失, 下晚被迫重新合床。
                // (2026-09-11 收床粘性) 与上方闸门同款: 粘性实例 + 宽限清记忆。
                if (bedDropNearby(mod, selfBed)) {
                    setDebugState("Picking up our bed drop.");
                    _bedRecoverTask = new PickupDroppedItemTask(new ItemTarget[]{new ItemTarget("bed", 1)}, true);
                    _bedRecoverStartMs = System.currentTimeMillis();
                    return _bedRecoverTask;
                }
                scheduleForgetSelfBed(mod, selfBed);
            } else if (bedAt.isWithinDistance(mod.getPlayer().getPos(), 8)) {
                setDebugState("Picking up our placed bed.");
                _bedRecoverTask = new DestroyBlockTask(bedAt);
                _bedRecoverStartMs = System.currentTimeMillis();
                return _bedRecoverTask;
            }
        }

        // (2026-08-25 地牢锁定, 用户指定) 一旦开始定位地牢 (丢出末影之眼,
        // _ranStrongholdLocator 置 true), 专注地牢流程, 不再被眼睛收集/床/
        // 食物/木头/装备等前置任务打扰 — 否则 tracker 缓存陈旧会误判"眼睛
        // 不够/装备不够"反复打断定位 (用户实测 14:27: Locating Stronghold
        // 被 Get catalogued iron_gear 打断 → 熔铁 → 合成熔炉卡死)。
        if (_ranStrongholdLocator) {
            return getStrongholdPhase(mod);
        }

        // Do we need more eyes?
        boolean noEyesPlease = (endPortalOpened(mod, _endPortalCenterLocation) || WorldHelper.getCurrentDimension() == Dimension.END);
        int filledPortalFrames = getFilledPortalFrames(mod, _endPortalCenterLocation);
        int eyesNeededMin = noEyesPlease ? 0 : _config.minimumEyes - filledPortalFrames;
        int eyesNeeded    = noEyesPlease ? 0 : _config.targetEyes  - filledPortalFrames;
        // (2026-08-25 真实背包兜底) 眼睛数用真实背包扫描, 避免缓存陈旧误判
        // "眼睛不够"→ 走收集 → 前置装备检查反复打断地牢。
        int eyes = StorageHelper.countItemInInventoryReal(mod, Items.ENDER_EYE);
        if (eyes < eyesNeededMin || (!_ranStrongholdLocator && _collectingEyes && eyes < eyesNeeded)) {
            _collectingEyes = true;
            return getEyesOfEnderTask(mod, eyesNeeded);
        } else {
            _collectingEyes = false;
        }

        // We have eyes. Locate our portal + enter.
        return getStrongholdPhase(mod);
    }

    /**
     * (2026-08-25 从 onTick 抽出) 有足够末影之眼后的地牢定位/进末地流程。
     */
    private Task getStrongholdPhase(AltoClef mod) {
        // (2026-08-26 全局修复) 地牢定位中锁定主流程: Locate 任务运行中未完成时,
        // 不切换到床/出生点等强地牢阶段任务, 直到真正找到传送门。否则
        // endPortalFound (结构感知, 地牢区块一加载就 true) 会让 bot 在海上/未下挖
        // 时就被引去收集床/重置出生点, 打断"定位→下挖→探索→找到传送门"主流程
        // (用户 10:22 实测: 船上被打断去重置出生点 → 水上放床失败)。
        if (_ranStrongholdLocator && !_locateStrongholdTask.isFinished(mod)) {
            setDebugState("Locating End Portal...");
            return _locateStrongholdTask;
        }
        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                // If we found our end portal...
                if (endPortalFound(mod, _endPortalCenterLocation)) {
                    // (2026-08-27 修复: 处理传送门阶段锁定) 找到门后整段(拆刷怪笼/
                    // 收床/设重生点/开门/进门)都算"已定位地牢"。否则重启任务时若
                    // 门已开(跳过定位流程) _ranStrongholdLocator 还是 false, 主流程
                    // "Picking up our placed bed" 会把刚放的出生点床撸掉 (用户实测:
                    // 睡觉后起来触发打床)。
                    _ranStrongholdLocator = true;

                    // Destroy silverfish spawner
                    if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD)) {
                        Optional<BlockPos> silverfish = mod.getBlockTracker().getNearestTracking(blockPos -> {
                            return WorldHelper.getSpawnerEntity(mod, blockPos) instanceof SilverfishEntity;
                        }, Blocks.SPAWNER);
                        if (silverfish.isPresent()) {
                            return new DestroyBlockTask(silverfish.get());
                        }
                    }

                    // Get remaining beds.
                    if (needsBeds(mod)) {
                        setDebugState("Collecting beds.");
                        return getBedTask(mod);
                    }
                    if (_config.placeSpawnNearEndPortal) {
                        if (!spawnSetNearPortal(mod, _endPortalCenterLocation)) {
                            // (2026-08-26 修复: 结构感知 ≠ 真正到达传送门) endPortalFound
                            // 用 BlockTracker 结构感知 (加载区块内 END_PORTAL_FRAME 完全
                            // 感知无视距离), 全新任务/中途重启时地牢区块一加载就 true →
                            // 跳过"定位→下挖→探索→确认传送门"直接设重生点 (用户 17:xx
                            // 实测: 重启后立刻 "Approaching portal (to set spawnpoint)")。
                            // 只有定位任务真正探索确认了传送门才直接设重生点; 否则先走
                            // 定位流程 (GoToStrongholdPortalTask 会走到估计坐标、下挖到
                            // y=45、格栅探索、LOS 看到传送门才完成)。
                            if (!_locateStrongholdTask.isFinished(mod)) {
                                _ranStrongholdLocator = true;
                                setDebugState("Locating End Portal...");
                                return _locateStrongholdTask;
                            }
                            setDebugState("Setting spawn near end portal");
                            return setSpawnNearPortalTask(mod);
                        }
                    }
                    if (endPortalOpened(mod, _endPortalCenterLocation)) {
                        // Does our (current inventory) + (end dropped items inventory) satisfy (base requirements)?
                        //      If not, obtain (base requirements) - (end dropped items).
                        setDebugState("Getting equipment for End");
                        if (!hasItemOrDroppedInEnd(mod, Items.IRON_SWORD) && !hasItemOrDroppedInEnd(mod, Items.DIAMOND_SWORD)) {
                            return TaskCatalogue.getItemTask(Items.IRON_SWORD, 1);
                        }
                        // (2026-08-25 地牢锁定补盾) 中途开始可能没盾, 进末地打
                        // 末影龙需要盾挡吐息/末影人, 这里补上 (原逻辑漏查盾)。
                        if (!hasItemOrDroppedInEnd(mod, Items.SHIELD)) {
                            return TaskCatalogue.getItemTask(Items.SHIELD, 1);
                        }
                        if (!hasItemOrDroppedInEnd(mod, Items.WATER_BUCKET)) {
                            return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
                        }
                        if (!hasItemOrDroppedInEnd(mod, Items.IRON_PICKAXE) && !hasItemOrDroppedInEnd(mod, Items.DIAMOND_PICKAXE)) {
                            return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                        }
                        if (needsBuildingMaterials(mod)) {
                            return _buildMaterialsTask;
                        }

                        // We're as ready as we'll ever be, hop into the portal!
                        setDebugState("Entering End");
                        _enterindEndPortal = true;
                        // (2026-08-27 修复: 不再靠 BlockTracker 缓存找 END_PORTAL 块)
                        // 旧代码 DoToClosestBlockTask(END_PORTAL) 依赖缓存, 缓存丢失时
                        // getClosestTo 返回空 → "Waiting for calculations (wandering)" →
                        // 被抢占探索 (日志实锤 01:40/01:43 反复 wandering+exploring)。
                        // 门中心坐标已知 (框架平均/追踪缓存), 直接导航过去进门即可。
                        // (2026-08-27 用户实测: 直接导航到门中心(END_PORTAL 非实心块)
                        // baritone 找不到可站目标疯狂计算路线) → 目标改为门中心【上方
                        // 一格】的空气格, 走到那一步就踏进门传走。
                        return new GetToBlockTask(_endPortalCenterLocation.up());
                    } else {

                        // Open the portal! (we have enough eyes, do it)
                        setDebugState("Opening End Portal");
                        // (2026-08-27 修复: 世界扫描兜底) BlockTracker 缓存可能丢失
                        // 框架块 (任务切换/缓存刷新), DoToClosestBlockTask 找不到目标
                        // → "Waiting for calculations (wandering)" → 被抢占探索。
                        // 中心坐标已知时, 从 getFrameBlocks 直接推算 12 个框架位置,
                        // 把世界里真实存在的 END_PORTAL_FRAME 块登记回追踪缓存。
                        if (_endPortalCenterLocation != null) {
                            for (BlockPos frame : getFrameBlocks(_endPortalCenterLocation)) {
                                if (mod.getChunkTracker().isChunkLoaded(frame)
                                        && mod.getWorld().getBlockState(frame).getBlock() == Blocks.END_PORTAL_FRAME) {
                                    mod.getBlockTracker().addBlock(Blocks.END_PORTAL_FRAME, frame);
                                }
                            }
                        }
                        return new DoToClosestBlockTask(
                                blockPos -> new InteractWithBlockTask(Items.ENDER_EYE, blockPos),
                                blockPos -> !isEndPortalFrameFilled(mod, blockPos),
                                Blocks.END_PORTAL_FRAME
                        );
                    }
                } else {
                    // Get beds before starting our portal location.
                    if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD && needsBeds(mod)) {
                        setDebugState("Getting beds before stronghold search.");
                        return getBedTask(mod);
                    }
                    // Portal Location
                    setDebugState("Locating End Portal...");
                    _ranStrongholdLocator = true;
                    return _locateStrongholdTask;
                }
            }
            case NETHER -> {
                // Portal Location
                setDebugState("Locating End Portal...");
                if (needsBuildingMaterials(mod)) {
                    return _buildMaterialsTask;
                }
                // (2026-08-25 地牢锁定) 下界定位地牢同样锁定, 不被前置任务打断
                _ranStrongholdLocator = true;
                return _locateStrongholdTask;
            }
        }

        return null;
    }

    private boolean needsBuildingMaterials(AltoClef mod) {
        return StorageHelper.getBuildingMaterialCount(mod) < _config.minBuildMaterialCount || shouldForce(mod, _buildMaterialsTask);
    }

    private void updateCachedEndItems(AltoClef mod) {
        List<ItemEntity> droppedItems = mod.getEntityTracker().getDroppedItems();
        // If we have no items, it COULD be because we're dead. Wait a little.
        if (droppedItems.isEmpty()) {
            if (!_cachedEndItemNothingWaitTime.elapsed()) {
                return;
            }
        } else {
            _cachedEndItemNothingWaitTime.reset();
        }
        _cachedEndItemDrops.clear();
        for (ItemEntity entity : droppedItems) {
            Item item = entity.getStack().getItem();
            int count = entity.getStack().getCount();
            _cachedEndItemDrops.put(item, _cachedEndItemDrops.getOrDefault(item, 0) + count);
        }
    }
    private int getEndCachedCount(Item item) {
        return _cachedEndItemDrops.getOrDefault(item, 0);
    }
    private boolean droppedInEnd(Item item) {
        return getEndCachedCount(item) > 0;
    }
    private boolean hasItemOrDroppedInEnd(AltoClef mod, Item item) {
        // (2026-08-27 修复: 副手物品也算"拥有") 旧代码只查 getItemStorage().hasItem
        // (缓存), 角色拿在副手上的盾牌等不被认为拥有 → 模组重复做第二个 (用户
        // 实测: 副手有盾还去做盾)。加真实背包扫描兜底 (含 main/armor/offhand,
        // 与"关键资源判定以真实背包为准"一致)。
        return mod.getItemStorage().hasItem(item)
                || droppedInEnd(item)
                || StorageHelper.countItemInInventoryReal(mod, item) > 0;
    }

    private List<Item> lootableItems(AltoClef mod) {
        List<Item> lootable = new ArrayList<>();
        lootable.add(Items.GOLDEN_APPLE);
        lootable.add(Items.ENCHANTED_GOLDEN_APPLE);
        lootable.add(Items.GLISTERING_MELON_SLICE);
        lootable.add(Items.GOLDEN_CARROT);
        lootable.add(Items.OBSIDIAN);
        if (!StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS) && !mod.getItemStorage().hasItemInventoryOnly(Items.GOLDEN_BOOTS)) {
            lootable.add(Items.GOLDEN_BOOTS);
        }
        if ((mod.getItemStorage().getItemCountInventoryOnly(Items.GOLD_INGOT) < 4 && !StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS) && !mod.getItemStorage().hasItemInventoryOnly(Items.GOLDEN_BOOTS)) || _config.barterPearlsInsteadOfEndermanHunt) {
            lootable.add(Items.GOLD_INGOT);
        }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.FLINT_AND_STEEL)) {
            lootable.add(Items.FLINT_AND_STEEL);
            if (!mod.getItemStorage().hasItemInventoryOnly(Items.FIRE_CHARGE)) {
                lootable.add(Items.FIRE_CHARGE);
            }
        }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.BUCKET) && !mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            lootable.add(Items.IRON_INGOT);
        }
        if (!StorageHelper.itemTargetsMetInventory(mod, COLLECT_EYE_GEAR_MIN)) {
            lootable.add(Items.DIAMOND);
        }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.FLINT)) {
            lootable.add(Items.FLINT);
        }
        return lootable;
    }

    /**
     * (2026-09-02 刷怪笼抑制开箱) 玩家近场 4 格内是否有 SPAWNER (500ms 节流缓存)。
     * 有刷怪笼时禁止就近搜刮 — 刷怪笼地牢先由 MineDownExploreTask 清源(先清怪再挖
     * 掉刷怪笼), 箱子等刷完再开, 避免搜刮抢走挖刷怪笼任务(用户"挖/打/开别打架")。
     */
    private boolean nearbySpawnerBlocked(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastSpawnerBlockScanMs < 500) {
            return _spawnerBlockedCached;
        }
        _lastSpawnerBlockScanMs = now;
        boolean blocked = false;
        if (mod.getWorld() != null && mod.getPlayer() != null) {
            BlockPos player = mod.getPlayer().getBlockPos();
            final int R = 4;
            outer:
            for (int dx = -R; dx <= R; dx++) {
                for (int dy = -R; dy <= R; dy++) {
                    for (int dz = -R; dz <= R; dz++) {
                        BlockPos p = player.add(dx, dy, dz);
                        if (mod.getChunkTracker().isChunkLoaded(p)
                                && mod.getWorld().getBlockState(p).getBlock() == Blocks.SPAWNER) {
                            blocked = true;
                            break outer;
                        }
                    }
                }
            }
        }
        _spawnerBlockedCached = blocked;
        return blocked;
    }

    /**
     * 就近搜刮的目标物品清单 (2026-08-21): 村庄/矿道遗迹箱子里值得拿的
     * 东西。与 lootableItems()(下界传送门专用: 金/黑曜石/打火石)不同,
     * 这里覆盖主循环通用刚需: 食物、铁、煤、钻石、床(出生点保险)、工具
     * 护甲; 木头存量低时连原木/木板也要(村庄箱子里常有木板)。
     */
    private List<Item> nearbySweepLootableItems(AltoClef mod) {
        List<Item> lootable = new ArrayList<>();
        // 床: 出生点保险, 有就白拿
        lootable.addAll(Arrays.asList(ItemHelper.BED));
        // 食物(村庄面包/矿道补给)
        lootable.add(Items.BREAD);
        lootable.add(Items.COOKED_BEEF);
        lootable.add(Items.COOKED_PORKCHOP);
        lootable.add(Items.COOKED_CHICKEN);
        lootable.add(Items.COOKED_MUTTON);
        lootable.add(Items.COOKED_RABBIT);
        lootable.add(Items.BAKED_POTATO);
        lootable.add(Items.CARROT);
        lootable.add(Items.POTATO);
        lootable.add(Items.APPLE);
        lootable.add(Items.GOLDEN_APPLE);
        lootable.add(Items.ENCHANTED_GOLDEN_APPLE);
        lootable.add(Items.GOLDEN_CARROT);
        // 材料/矿物
        lootable.add(Items.IRON_INGOT);
        lootable.add(Items.GOLD_INGOT);
        lootable.add(Items.DIAMOND);
        lootable.add(Items.COAL);
        lootable.add(Items.OBSIDIAN);
        lootable.add(Items.EMERALD);
        lootable.add(Items.REDSTONE);
        lootable.add(Items.LAPIS_LAZULI);
        // 工具/实用
        lootable.add(Items.BUCKET);
        lootable.add(Items.WATER_BUCKET);
        lootable.add(Items.FLINT_AND_STEEL);
        lootable.add(Items.SHEARS);
        lootable.add(Items.STRING);
        lootable.add(Items.ARROW);
        lootable.add(Items.IRON_SWORD);
        lootable.add(Items.IRON_PICKAXE);
        lootable.add(Items.IRON_AXE);
        lootable.add(Items.IRON_SHOVEL);
        lootable.add(Items.DIAMOND_SWORD);
        lootable.add(Items.DIAMOND_PICKAXE);
        lootable.addAll(Arrays.asList(ItemHelper.IRON_ARMORS));
        // 木头存量低: 木板/原木也是"眼前可白拿的木头"
        if (mod.getItemStorage().getItemCount(ItemHelper.LOG) < 16) {
            lootable.addAll(Arrays.asList(ItemHelper.LOG));
            lootable.addAll(Arrays.asList(ItemHelper.PLANKS));
        }
        return lootable;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(TRACK_BLOCKS);
        mod.getBlockTracker().stopTracking(ItemHelper.itemsToBlocks(ItemHelper.BED));
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BeatMinecraft2Task;
    }

    @Override
    protected String toDebugString() {
        return "Beating the Game.";
    }

    private boolean endPortalFound(AltoClef mod, BlockPos endPortalCenter) {
        if (endPortalCenter == null) {
            return false;
        }
        if (endPortalOpened(mod, endPortalCenter)) {
            return true;
        }
        return getFrameBlocks(endPortalCenter).stream().allMatch(frame -> mod.getBlockTracker().blockIsValid(frame, Blocks.END_PORTAL_FRAME));
    }
    private boolean endPortalOpened(AltoClef mod, BlockPos endPortalCenter) {
        if (endPortalCenter == null) return false;
        // (2026-08-27 修复) 世界直接扫描兜底: BlockTracker 缓存可能丢失
        // END_PORTAL 块 (用户人工开门/缓存刷新延迟, 日志实锤: 门已开 bot
        // 仍 "Opening End Portal"→"calculating then exploring")。直接看
        // 中心块是不是 END_PORTAL 门块, 是则视为门已开。
        if (mod.getChunkTracker().isChunkLoaded(endPortalCenter)) {
            Block block = mod.getWorld().getBlockState(endPortalCenter).getBlock();
            if (block == Blocks.END_PORTAL) {
                _endPortalOpened = true;
                return true;
            }
        }
        return _endPortalOpened && mod.getBlockTracker().blockIsValid(endPortalCenter, Blocks.END_PORTAL);
    }
    private boolean spawnSetNearPortal(AltoClef mod, BlockPos endPortalCenter) {
        if (_bedSpawnLocation == null) return false;
        // (2026-08-27 修复: 不再依赖 BlockTracker 缓存) 旧代码还要
        // blockIsValid(_bedSpawnLocation, BED) 校验床还在 — 缓存陈旧时返回
        // false → needsToSetSpawn 一直 true → 床数目标 = requiredBeds+1 →
        // 刚放完重生点床 (11→10) 就转去收集床, 挡住开门/进门 (用户实测)。
        // _bedSpawnLocation 只要被记录 (= PlaceBedAndSetSpawnTask isSpawnSet
        // 真正设置成功) 就视为已设; 只有区块已加载且明确看到床被拆才重设。
        if (mod.getChunkTracker().isChunkLoaded(_bedSpawnLocation)) {
            return mod.getWorld().getBlockState(_bedSpawnLocation).getBlock() instanceof BedBlock;
        }
        return true;
    }
    private int getFilledPortalFrames(AltoClef mod, BlockPos endPortalCenter) {
        // If we have our end portal, this doesn't matter.
        if (endPortalFound(mod, endPortalCenter)) {
            return END_PORTAL_FRAME_COUNT;
        }
        if (endPortalFound(mod, endPortalCenter)) {
            List<BlockPos> frameBlocks = getFrameBlocks(endPortalCenter);
            // If EVERY portal frame is loaded, consider updating our cached filled portal count.
            if (frameBlocks.stream().allMatch(blockPos -> mod.getChunkTracker().isChunkLoaded(blockPos))) {
                _cachedFilledPortalFrames = frameBlocks.stream().reduce(0, (count, blockPos) ->
                        count + (isEndPortalFrameFilled(mod, blockPos) ? 1 : 0),
                        Integer::sum);
            }
            return _cachedFilledPortalFrames;
        }
        return 0;
    }

    private boolean canBeLootablePortalChest(AltoClef mod, BlockPos blockPos) {
        if (mod.getWorld().getBlockState(blockPos.up(1)).getBlock() == Blocks.WATER || blockPos.getY() < 50) {
            return false;
        }
        for (BlockPos check : WorldHelper.scanRegion(mod, blockPos.add(-4, -2, -4), blockPos.add(4, 2, 4))) {
            if (mod.getWorld().getBlockState(check).getBlock() == Blocks.NETHERRACK) {
                return true;
            }
        }
        _notRuinedPortalChests.add(blockPos);
        return false;
    }

    private Optional<BlockPos> locateClosestUnopenedRuinedPortalChest(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            return Optional.empty();
        }
        return mod.getBlockTracker().getNearestTracking(blockPos -> !_notRuinedPortalChests.contains(blockPos) && WorldHelper.isUnopenedChest(mod, blockPos) && mod.getPlayer().getBlockPos().isWithinDistance(blockPos, 150) && canBeLootablePortalChest(mod, blockPos), Blocks.CHEST);
    }

    private static List<BlockPos> getFrameBlocks(BlockPos endPortalCenter) {
        Vec3i[] frameOffsets = new Vec3i[] {
                new Vec3i(2, 0, 1),
                new Vec3i(2, 0, 0),
                new Vec3i(2, 0, -1),
                new Vec3i(-2, 0, 1),
                new Vec3i(-2, 0, 0),
                new Vec3i(-2, 0, -1),
                new Vec3i(1, 0, 2),
                new Vec3i(0, 0, 2),
                new Vec3i(-1, 0, 2),
                new Vec3i(1, 0, -2),
                new Vec3i(0, 0, -2),
                new Vec3i(-1, 0, -2)
        };
        return Arrays.stream(frameOffsets).map(endPortalCenter::add).toList();
    }

    private Task getEyesOfEnderTask(AltoClef mod, int targetEyes) {
        if (mod.getEntityTracker().itemDropped(Items.ENDER_EYE)) {
            setDebugState("Picking up Dropped Eyes");
            return new PickupDroppedItemTask(Items.ENDER_EYE, targetEyes);
        }

        int eyeCount = mod.getItemStorage().getItemCount(Items.ENDER_EYE);

        int blazePowderCount = mod.getItemStorage().getItemCount(Items.BLAZE_POWDER);
        int blazeRodCount = mod.getItemStorage().getItemCount(Items.BLAZE_ROD);
        int blazeRodTarget = (int)Math.ceil(((double)targetEyes - eyeCount - blazePowderCount) / 2.0);
        int enderPearlTarget = targetEyes - eyeCount;
        boolean needsBlazeRods = blazeRodCount < blazeRodTarget;
        boolean needsBlazePowder = eyeCount + blazePowderCount < targetEyes;
        boolean needsEnderPearls = mod.getItemStorage().getItemCount(Items.ENDER_PEARL) < enderPearlTarget;

        if (needsBlazePowder && !needsBlazeRods) {
            // We have enough blaze rods.
            setDebugState("Crafting blaze powder");
            return TaskCatalogue.getItemTask(Items.BLAZE_POWDER, targetEyes - eyeCount);
        }

        if (!needsBlazePowder && !needsEnderPearls) {
            // Craft ender eyes
            setDebugState("Crafting Ender Eyes");
            return TaskCatalogue.getItemTask(Items.ENDER_EYE, targetEyes);
        }

        // Get blaze rods + pearls...
        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                // (2026-09-01 木头强前置, 用户"先把木头攒够再干别的") 木头是工具/
                // 燃料/合成的地基资源。木头未攒够(首次 minWoodLogs / 后续跌破
                // restockThreshold)时, 任何其它 OVERWORLD 分支(穿护甲/烧肉/挖矿/
                // 箱子)都必须让位 — 否则"烧生肉→做木板燃料→消耗原木"会反过来把
                // 主链拽去砍木, 造成 砍木↔烧肉↔挖石 反复循环(日志 t=414 挖石被
                // Stockpiling wood 打断, 圆石需 8 个永远集不齐)。
                Task woodGate = getWoodPerfusionTask(mod);
                if (woodGate != null) return woodGate;

                // NEARBY LOOT SWEEP (2026-08-21 就近快速收集; 2026-09-01 优先级上移 +
                // 矿车箱子): 附近有未搜刮的箱子/木桶/矿车箱子时优先拿空, 再去干正事。
                // 2026-09-01 用户"矿洞里看见箱子/矿车箱子竟然不开, 被其他任务抢了":
                // 原位置在 _gearTask/贪心挖矿之后, 铁装备阶段 shouldForce(_gearTask)
                // 恒真把搜刮抢先跳过。移到 woodGate(木头硬前置)之后、装备/挖矿之前 —
                // 视线内的箱子一出现就先开, 不再被正事抢。派发即标记 _swept*,
                // 即使开不出来也不死循环。
                // 候选必须可感知: 方块箱子 isBlockPerceivable (LOS, 防透视挖 40 格
                // 岩层去开看不见的箱子), 矿车箱子 isEntityVisible (LOS)。
                // (2026-09-02 刷怪笼抑制开箱, 用户"先清怪再挖刷怪笼, 挖/打/开别打架")
                // 附近 8 格内有刷怪笼时不触发就近搜刮: 刷怪笼地牢先由挖矿任务的
                // MineDownExploreTask 清源(先清怪再挖掉刷怪笼), 箱子等刷完再开 —
                // 否则搜刮会抢走挖刷怪笼任务, 刷怪源永远不清。
                if (!nearbySpawnerBlocked(mod)) {
                if (shouldForce(mod, _nearbySweepTask)) {
                    return _nearbySweepTask;
                }
                _nearbySweepTask = null;
                {
                    mod.getBlockTracker().trackBlock(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
                    BlockPos nearestContainer = null;
                    double bestSq = NEARBY_LOOT_RANGE * NEARBY_LOOT_RANGE;
                    BlockPos playerPos = mod.getPlayer().getBlockPos();
                    for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL)) {
                        if (_sweptContainers.contains(pos)) continue;
                        if (!VisibilityHelper.isBlockPerceivable(mod, pos)) continue;
                        double sq = pos.getSquaredDistance(playerPos);
                        if (sq < bestSq) {
                            bestSq = sq;
                            nearestContainer = pos;
                        }
                    }
                    // (2026-09-01 矿车箱子) 矿道遗迹常见 ChestMinecartEntity, 是实体
                    // 非方块, BlockTracker 不覆盖 — 单独经 EntityTracker 检测 (LOS 感知)。
                    ChestMinecartEntity nearestMinecart = null;
                    double bestMinecartSq = NEARBY_LOOT_RANGE * NEARBY_LOOT_RANGE;
                    for (ChestMinecartEntity mc : mod.getEntityTracker().getTrackedEntities(ChestMinecartEntity.class)) {
                        if (mc == null || !mc.isAlive() || _sweptMinecarts.contains(mc)) continue;
                        if (!VisibilityHelper.isEntityVisible(mod, mc)) continue;
                        double sq = mc.squaredDistanceTo(mod.getPlayer());
                        if (sq < bestMinecartSq) {
                            bestMinecartSq = sq;
                            nearestMinecart = mc;
                        }
                    }
                    if (nearestMinecart != null && (nearestContainer == null
                            || nearestMinecart.squaredDistanceTo(mod.getPlayer()) < bestSq)) {
                        _sweptMinecarts.add(nearestMinecart);
                        setDebugState("Sweeping nearby minecart loot");
                        diagIronPhase(mod, "nearby loot sweep",
                                "distSq=" + (int) nearestMinecart.squaredDistanceTo(mod.getPlayer())
                                        + " minecart=" + nearestMinecart.getBlockPos().toShortString());
                        _nearbySweepTask = new LootContainerTask(nearestMinecart, nearbySweepLootableItems(mod), _noCurseOfBinding);
                        return _nearbySweepTask;
                    }
                    if (nearestContainer != null) {
                        _sweptContainers.add(nearestContainer);
                        setDebugState("Sweeping nearby structure loot: " + nearestContainer.toShortString());
                        diagIronPhase(mod, "nearby loot sweep",
                                "distSq=" + (int) nearestContainer.getSquaredDistance(playerPos)
                                        + " chest=" + nearestContainer.toShortString());
                        _nearbySweepTask = new LootContainerTask(nearestContainer, nearbySweepLootableItems(mod), _noCurseOfBinding);
                        return _nearbySweepTask;
                    }
                }
                }

                // Make sure we have gear, then food.
                // (2026-08-23 穿戴不收集) 原分支传全套 COLLECT_EYE_ARMOR →
                // EquipArmorTask 发现缺件会先 CataloguedResourceTask 收集
                // 全套 (日志 00:01:24: 做完胸甲 → Equipping armor → Get
                // catalogued → 挖 20 钻石 + 4 金), 胸甲反而不穿、护腿也不
                // 做。只传"背包已有且未穿"的件 → 立即穿戴; 缺的件由下方
                // "钻石即做"分支够材料逐件做, 不足时 gearTask 阶段兜底。
                {
                    Item[] ownedNotEquipped = Arrays.stream(COLLECT_EYE_ARMOR)
                            .filter(item -> mod.getItemStorage().hasItem(item) && !StorageHelper.isArmorEquipped(mod, item))
                            .toArray(Item[]::new);
                    if (ownedNotEquipped.length > 0) {
                        setDebugState("Equipping owned armor");
                        return new EquipArmorTask(ownedNotEquipped);
                    }
                }
                // (2026-08-23 钻石即做) 背包钻石够某件钻石防具的材料时立刻
                // 制作 — 放在搜刮/神殿/贪心挖矿/食物木头等所有分支之前
                // (用户实测: 材料够了迟迟不做装备)。顺序胸甲(8)>护腿(7)>
                // 头盔(5), 金靴不耗钻石不在列; 做完由上方 EquipArmorTask 穿上。
                {
                    // (2026-09-03 时间上限语义) 钻石收集已 give up → 钻石件保持
                    // 放弃态, 不再开合成 (否则与金靴分支互抢工作台, 203154 场实锤:
                    // chestplate↔golden_boots 每 2s 互抢 8 轮)。
                    if (!_diamondCollectGivenUp) {
                    // (2026-09-17 合成粘性) 当前件的合成链未结束前每 tick 返回同一
                    // 实例, 杜绝 shouldForce(_gearTask) 趁 craftBusy 间隙抢断
                    // (214954 场: 合成链 50ms 就被 Get catalogued 掐断, have=0)。
                    if (_diamondPieceTask != null) {
                        if (_diamondPieceTask.isFinished(mod)) {
                            Debug.logMessage("(DiamondPiece) sticky craft task finished: "
                                    + ItemHelper.stripItemName(_diamondPieceItem));
                            _diamondPieceTask = null;
                            _diamondPieceItem = null;
                        } else {
                            setDebugState("Crafting diamond gear immediately (sticky): "
                                    + ItemHelper.stripItemName(_diamondPieceItem));
                            return _diamondPieceTask;
                        }
                    }
                    // (2026-09-10 合成在飞不开火, 19:52 场次 t=311 实锤) 有合成占据
                    // 台格 (如镐正在做) 时本分支不开火: accessible 口径含台格槽位,
                    // 镐的 3 颗钻在格子里会被算成 5+3=8"够做胸甲" → 开出胸甲任务
                    // 抢切镐合成。等在飞合成收尾 (格空) 后再按真实材料判。
                    boolean craftBusy = adris.altoclef.tasks.CraftGenericWithRecipeBooksTask.isCraftInFlight();
                    Item[] diamondOrder = {Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET};
                    int[] diamondCosts = {8, 7, 5};
                    for (int i = 0; !craftBusy && i < diamondOrder.length; i++) {
                        Item piece = diamondOrder[i];
                        boolean owned = mod.getItemStorage().hasItem(piece) || StorageHelper.isArmorEquipped(mod, piece);
                        // (2026-09-10 横跳修复) 钻石计数含打开容器自有槽位: 钻石镐合成
                        // 把 3 颗钻借进工作台 3x3 的瞬间, 旧口径(仅背包)骤降 → 本分支
                        // 每 tick 重扫切换到下一件 → 抢切镐合成 → 格子打断钻石弹回 →
                        // 又切回 → chestplate↔leggings 每 ~250ms 振荡, 镐子永不完成
                        // (18:28 场次 [Diag-flow] 实锤)。含台格口径后阈值判定稳定,
                        // 当前件做完(或钻石真被消耗)才轮到下一件。
                        if (!owned && StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.DIAMOND) >= diamondCosts[i]) {
                            _diamondPieceItem = piece;
                            _diamondPieceTask = TaskCatalogue.getItemTask(piece, 1);
                            setDebugState("Crafting diamond gear immediately: " + ItemHelper.stripItemName(piece));
                            return _diamondPieceTask;
                        }
                    }
                    }
                }
                if(shouldForce(mod, _lootTask)) {
                    return _lootTask;
                }
                if (_config.searchRuinedPortals) {
                    // Check for ruined portals
                    Optional<BlockPos> chest = locateClosestUnopenedRuinedPortalChest(mod);
                    if (chest.isPresent()) {
                        setDebugState("Looting ruined portal chest for goodies");
                        _lootTask = new LootContainerTask(chest.get(), lootableItems(mod), _noCurseOfBinding);
                        return _lootTask;
                    }
                }
                if (_config.searchDesertTemples && StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD)) {
                    // Check for desert temples
                    BlockPos temple = WorldHelper.getADesertTemple(mod);
                    if (temple != null) {
                        setDebugState("Looting desert temple for goodies");
                        _lootTask = new LootDesertTempleTask(temple, lootableItems(mod));
                        return _lootTask;
                    }
                }
                // (2026-08-22 贪心收集) 顺手挖眼前可见矿石 (煤/铁/金/钻石)。
                // 位置必须在 shouldForce(_gearTask) 之前 — 否则 iron gear
                // 收集期间 (下方 "Getting gear" 强制返回 _gearTask) 贪心检查
                // 被抢先跳过, bot 挖铁时碰到煤也不顺手挖 (用户实测 15:45-48:
                // 进 gear 分支后 GreedyOre 检查再无日志 → 碰到煤不挖)。
                // (2026-08-23 扩展+让位) 覆盖范围从"仅铁阶段"扩展到
                // eyeGear 完成前 — 钻石阶段路过可见钻石/金也顺手挖;
                // 但铁装备(铁剑+盾)未完成时金矿让位 (铁装备制作优先于
                // 挖金, 让位判断在 CollectVisibleOresTask.planExecutable 内
                // 动态做)。只认视野内矿 (FOV+LOS, 24 格, 上限防饿死主线);
                // 容器界面中不触发; 粘性: 选中种类后执行到"达上限 且
                // 眼前无同类"才换。
                boolean eyeGearSatisfied = StorageHelper.itemTargetsMet(mod, COLLECT_EYE_GEAR_MIN) && StorageHelper.isArmorEquippedAll(mod, COLLECT_EYE_ARMOR);
                if (!eyeGearSatisfied) {
                    // (2026-09-07 主线保护窗口, 用户"允许找flint途中贪婪挖矿, 但不允许
                    // 频繁交换任务") 放弃钻石准备进下界阶段 (_diamondCollectGivenUp,
                    // 主线=金靴/造门链/找燧石挖砂砾) 时, 贪婪触发冷却放大到
                    // settings.preNetherGreedyCooldownSeconds (默认 45s)。实测根因
                    // (2026-09-07 11:47-11:48 latest.log): 深板岩铁富集区 8 格内永远
                    // 有生铁, 贪婪批次目标=1 挖完一处 2-4s 即释放, 8s 通用冷却挡不住
                    // "每 ~8s 整链拆一次去下界任务"的乒乓 (61s 内互抢 14 次), 燧石链
                    // 每次重开重选砂砾、部分挖掘进度全丢, 净进度≈0 永远凑不齐燧石。
                    // 此阶段冷却放大 = 保留低频顺手挖矿 (用户允许), 给燧石链连续
                    // 挖掘窗口 (用户禁止的是频繁交换, 不是贪婪本身)。普通阶段仍 8s。
                    boolean preNetherPhase = _diamondCollectGivenUp;
                    _greedyOreCheckCooldown.setInterval(preNetherPhase
                            ? mod.getModSettings().getPreNetherGreedyCooldownSeconds()
                            : GREEDY_ORE_CHECK_COOLDOWN_NORMAL);
                    if (_greedyOreTask != null) {
                        if (_greedyOreTask.isFinished(mod)) {
                            _greedyOreTask = null;
                        } else {
                            setDebugState("Greedy mining visible ores");
                            return _greedyOreTask;
                        }
                    }
                    if (_greedyOreCheckCooldown.elapsed() && MinecraftClient.getInstance().currentScreen == null) {
                        // 纯函数静态版 hasAnyVisibleOreWork (不实例化探针,
                        // 避免 onStart trackBlock 引用计数泄漏)。
                        if (CollectVisibleOresTask.hasAnyVisibleOreWork(mod)) {
                            // (2026-09-12 跨批次对撞闸门) 主线链里已有"已接触矿石 +
                            // 同矿种"的挖矿在推进 → 贪心让位, 不拆主线已选定的矿点。
                            // 见 mainlineMiningEngagedOn 注释 (21:51:29 实锤)。
                            if (MineAndCollectTask.mainlineMiningEngagedOn(CollectVisibleOresTask.bestVisibleOreBlocks(mod))) {
                                if (_greedyYieldLogTimer.elapsed()) {
                                    _greedyYieldLogTimer.reset();
                                    Debug.logToFile("altoclef_diag.log",
                                            "[GreedyOre] yield — mainline mining already engaged on the same ore");
                                }
                                _greedyOreCheckCooldown.reset();
                            } else {
                                _greedyOreTask = new CollectVisibleOresTask();
                                setDebugState("Greedy mining visible ores");
                                _greedyOreCheckCooldown.reset();
                                if (preNetherPhase) {
                                    Debug.logToFile("altoclef_diag.log",
                                            "[PreNetherGreedy]\tgreedy fires during pre-nether phase (protection window="
                                                    + mod.getModSettings().getPreNetherGreedyCooldownSeconds() + "s)");
                                }
                                return _greedyOreTask;
                            }
                        } else {
                            _greedyOreTask = null;
                            _greedyOreCheckCooldown.reset();
                        }
                    }
                }
                if (shouldForce(mod, _gearTask) && !StorageHelper.isArmorEquippedAll(mod, COLLECT_EYE_ARMOR)) {
                    // (2026-09-03 钻石时间上限) 钻石收集进行中: 累积活跃时间; 若已
                    // 累计超限则永久放弃, 不再返回钻石收集任务 (落空往下走去下界)。
                    if (_gearTaskIsDiamond) {
                        recordDiamondCollectActive(mod);
                    if (_diamondCollectGivenUp) {
                        // (2026-09-04 补金靴) 放弃钻石后进下界前只补金靴, 不碰钻石件
                        Task bootsTask = getPreNetherGoldenBootsTask(mod);
                        if (bootsTask != null) {
                            return bootsTask;
                        }
                        // (2026-09-09 进下界前必须持盾) 缺盾先补, 见 getPreNetherShieldTask。
                        Task preNetherShield = getPreNetherShieldTask(mod);
                        if (preNetherShield != null) {
                            return preNetherShield;
                        }
                        setDebugState("Giving up diamond gear, proceeding forward (limit reached)");
                        return _goToNetherTask;
                    }
                    }
                    setDebugState("Getting gear for Ender Eye journey");
                    return _gearTask;
                }
                if (shouldForce(mod, _foodTask)) {
                    setDebugState("Getting Food for Ender Eye journey");
                    return _foodTask;
                }
                // Smelt remaining raw food
                if (_config.alwaysCookRawFood) {
                    // (2026-09-08 烧肉 ping-pong 修复) 先保活缓存任务: 未 finished 就
                    // 继续 return 它, 不给"每 tick 换肉抢切"任何机会。弃缓存仅两条路:
                    // ① 任务自身 finished (达标且炉排空); ② 原料彻底消失(死亡掉落/
                    // 被扔) 且 炉已排空 —— 后者防"肉已塞进炉里、背包为 0"被误判,
                    // 炉里还有料时保活让任务自己收完产物。中途被更高优先级抢切的
                    // 任务同样留在缓存里, 恢复后继续跑 (stop-loss 在 onStop 已兜底)。
                    if (_cachedSmeltTask != null) {
                        if (_cachedSmeltTask.isFinished(mod)) {
                            Debug.logToFile("altoclef_diag.log", "[SmeltKeepAlive]\tdiscard: isFinished raw="
                                    + ItemHelper.stripItemName(_cachedSmeltTask.getTargets()[0].getMaterial().getMatches()[0])
                                    + " cache=" + _cachedSmeltTask.describeFurnaceCache());
                            _cachedSmeltTask = null;
                            _smeltDiscardStreak = 0;
                        } else {
                            Item[] cachedRaw = _cachedSmeltTask.getTargets()[0].getMaterial().getMatches();
                            int rawCount = StorageHelper.countItemsInInventoryReal(mod, cachedRaw);
                            boolean drained = _cachedSmeltTask.isFurnaceDrained();
                            boolean goneNotDrained = rawCount == 0 && !drained;
                            // 诊断: 状态翻转才记 —— 肉在炉里烧时 rawCount==0 属正常
                            if (goneNotDrained != _lastSmeltGoneNotDrained) {
                                Debug.logToFile("altoclef_diag.log", "[SmeltKeepAlive]\traw gone but "
                                        + (drained ? "drained" : "NOT drained")
                                        + " raw=" + ItemHelper.stripItemName(cachedRaw[0])
                                        + " cache=" + _cachedSmeltTask.describeFurnaceCache());
                                _lastSmeltGoneNotDrained = goneNotDrained;
                            }
                            if (rawCount == 0 && drained) {
                                _smeltDiscardStreak++;
                                if (_smeltDiscardStreak >= SMELT_DISCARD_STREAK_TICKS) {
                                    Debug.logToFile("altoclef_diag.log", "[SmeltKeepAlive]\tdiscard: raw gone+drained raw="
                                            + ItemHelper.stripItemName(cachedRaw[0]) + " streak=" + _smeltDiscardStreak
                                            + " cache=" + _cachedSmeltTask.describeFurnaceCache());
                                    _cachedSmeltTask = null;
                                    _smeltDiscardStreak = 0;
                                } else {
                                    // 未达连续阈值, 本 tick 仍保活
                                    setDebugState("Smelting raw food: "
                                            + ItemHelper.stripItemName(cachedRaw[0]));
                                    return _cachedSmeltTask;
                                }
                            } else {
                                _smeltDiscardStreak = 0;
                                setDebugState("Smelting raw food: "
                                        + ItemHelper.stripItemName(cachedRaw[0]));
                                return _cachedSmeltTask;
                            }
                        }
                    }
                    for (Item raw : ItemHelper.RAW_FOODS) {
                        if (mod.getItemStorage().hasItem(raw)) {
                            Optional<Item> cooked = ItemHelper.getCookedFood(raw);
                            if (cooked.isPresent()) {
                                int targetCount = mod.getItemStorage().getItemCount(cooked.get()) + mod.getItemStorage().getItemCount(raw);
                                setDebugState("Smelting raw food: " + ItemHelper.stripItemName(raw));
                                _cachedSmeltTask = new SmeltInFurnaceTask(new SmeltTarget(new ItemTarget(cooked.get(), targetCount), new ItemTarget(raw, targetCount)));
                                Debug.logToFile("altoclef_diag.log", "[SmeltKeepAlive]\tcreated raw="
                                        + ItemHelper.stripItemName(raw) + " target=" + targetCount);
                                return _cachedSmeltTask;
                            }
                        }
                    }
                }

                // 床持床保险已上移到 onTick 睡觉分支的三路互斥逻辑 (2026-08-27):
                // 地下睡觉与收床都跳过; 地表白天只在 _bedInsuranceDone==false 且背包无床
                // 时才 acquireBedTask(96格内现床/合成分支); 地表夜间优先去睡。此处不再重复
                // 拦截 — 深地下挖矿时绝不因"背包没床"派床任务 (原逻辑曾导致挖矿时拆床横跳)。

                // WOOD STOCKPILE: wood is the backbone resource (tools, fuel, torches,
                // crafting). 木头强前置逻辑已抽到 getWoodPerfusionTask() 并在本 case
                // 最前面门控调用 — 木头未攒够时烧肉/挖矿/穿护甲/箱子全部让位, 避免
                // "烧生肉→做木板燃料→消耗原木→返回来砍木"反复循环 (2026-09-01 用户
                // 实测 t=414 挖石被 Stockpiling wood 打断, 圆石需 8 个永远集不齐)。

                // FOOD FIRST (先于挖矿) — 强门槛。 (2026-09-08 贪婪食物峰值 rev, 用户)
                // 旧版: 地表食物 < foodUnits(120) 就收食物, 吃几口跌回 120 以下又重复
                // 触发收集 (用户实测: 干草收到 120 就停, 吃一块又回去收, 循环往复)。
                // 新版: 峰值锁存 — 峰值(settings.foodPeakUnits, 默认 240, 与 120 取大)
                // 未达成前, 地表贪婪收食物直达峰值(干草/作物/猎杀全开); 库存峰值一旦
                // 达到过 240 即永久锁存 (FoodChain.s_foodPeakReached), 之后吃东西回落
                // 不再触发收集, 只有跌破危险线(minFoodUnits=10)真断粮才重新收集(冲峰值)。
                int strongFoodUnits = mod.getModSettings().getFoodUnitsToCollect();
                if (strongFoodUnits <= 0) strongFoodUnits = _config.foodUnits; // settings 未配时兜底强门槛
                int foodPeakUnits = Math.max(strongFoodUnits, mod.getModSettings().getFoodPeakUnits());
                double foodScore = StorageHelper.calculateInventoryFoodScore(mod);
                if (foodPeakUnits > 0 && !FoodChain.s_foodPeakReached && foodScore >= foodPeakUnits) {
                    FoodChain.s_foodPeakReached = true;
                    Debug.logToFile("altoclef_diag.log",
                            "[MAIN-FIX]\t[FoodPeak]\tpeak reached food=" + foodScore
                                    + "/" + foodPeakUnits + " -> LATCHED; no more re-collection on eating");
                }
                // 锁存前: 食物 < 峰值即收集 (地表强门槛改为贪婪冲峰); 锁存后: 只有
                // 跌破危险线才再收集。地下分支保持原样: 未到危险线就地继续挖矿不回地表。
                // (2026-09-15 觅食远征) 超时远征锁存期间, 本轮收集目标用降低值。
                int collectTargetUnits = foodPeakUnits;
                if (FoodChain.s_foodExpeditionActive) {
                    int reduced = mod.getModSettings().getFoodExpeditionReducedUnits();
                    if (reduced > 0 && reduced < collectTargetUnits) {
                        collectTargetUnits = reduced;
                    }
                }
                boolean foodGateNeedCollect = FoodChain.s_foodPeakReached
                        ? foodScore <= _config.minFoodUnits
                        : foodScore < collectTargetUnits;
                if (foodGateNeedCollect) {
                    if (isUndergroundDebounced(mod) || isInPit(mod)) {
                        // (2026-09-01 用户"食物没到 10 units 不该回表面") 地下时只有
                        // 食物跌破危险线(minFoodUnits=10)才回地表收集; 其余情况就地
                        // 继续挖矿/熔炼, 不回表面。
                        if (foodScore <= _config.minFoodUnits) {
                            setDebugState("Returning to surface to collect food (critical low).");
                            return new GoToSurfaceTask();
                        }
                        // 食物尚可(>minFoodUnits): 跳过食物门, 继续下矿/熔炼等后续分支。
                    } else {
                        // 地表: 贪婪收集到达峰 (锁存后真断粮也冲峰值重建库存)。
                        // (2026-09-15 觅食远征) 远征锁存期间目标用降低值。
                        _foodTask = new CollectFoodTask(collectTargetUnits);
                        diagIronPhase(mod, "collect food (greedy peak gate)",
                                "foodScore=" + foodScore
                                        + " targetUnits=" + collectTargetUnits
                                        + " expedition=" + FoodChain.s_foodExpeditionActive
                                        + " peakLatched=" + FoodChain.s_foodPeakReached
                                        + " underground=" + isUndergroundDebounced(mod) + " inPit=" + isInPit(mod));
                        Debug.logToFile("altoclef_diag.log",
                                "[MAIN-FIX]\t[FoodGate]\tgreedy-peak food=" + foodScore
                                        + "/" + foodPeakUnits + " latched=" + FoodChain.s_foodPeakReached
                                        + " -> CollectFoodTask before mining/smelting");
                        return _foodTask;
                    }
                }

                // THEN start with iron
                boolean gearMinMet = ironGearMinMet(mod);
                logShieldDiagIfNotMet(mod, gearMinMet);
                if (!gearMinMet && !eyeGearSatisfied) {
                    // (2026-08-28 已有盾则剔除 SHIELD 目标) 避免副手/主手已有
                    // 盾却还在铁装备阶段重复做盾、缺材料卡死。
                    diagIronPhase(mod, "advance iron gear",
                            "gearMinMet=" + gearMinMet
                                    + " (ironSwordHeld=" + ironSwordHeld(mod) + ")" );
                    _gearTask = TaskCatalogue.getSquashedItemTask(ironGearTargets(mod));
                    _gearTaskIsDiamond = false; // 铁装阶段不受钻石时间上限约束
                    return _gearTask;
                }

                // (2026-08-30 铁套保护, 用户) 工具 (铁剑/镐/盾) 就绪后、进钻石前,
                // 先做一套铁甲穿上, 避免太容易死。先穿已持有未装备的铁甲, 缺件再
                // 收集; 只有四件全穿身上 (ironArmorMet) 才结束本阶段。
                // (2026-09-01 铁套↔钻石套打架, 用户"装备铁套和钻石套打架了") 上方
                // "Equipping owned armor" 已穿上钻石件(Path A 每 tick 先跑), 这里若
                // 仍因 !ironArmorMet 强推铁件 → 铁件覆盖钻石件 → 下 tick Path A 见
                // 钻石没了又装回 → 反复横跳 (日志 TaskPingPong 实锤)。已有钻石装具
                // 时跳过铁套阶段 — 钻石>铁, 走钻石路径后不再强推铁套。
                // (2026-09-12 金靴↔铁靴抢脚槽, 用户"任务打架") Path A 的
                // COLLECT_EYE_ARMOR 含金靴, Path B 的铁套含铁靴, 两者都打
                // 脚部槽位: Path A 穿金靴 → Path B 见铁靴未穿换上铁靴(金靴掉回
                // 背包) → Path A 又见金靴未穿 → 每 2s 振荡 (tasks.log 10:49
                // 实锤)。09-01 的钻石守卫盖不住金靴。定案(用户): 金靴优先,
                // 脚上已有金靴则铁套让位 — 进下界猪灵中立价值高于铁靴 1 点护甲。
                if (!ironArmorMet(mod) && !eyeGearSatisfied && !hasDiamondArmorEquipped(mod)
                        && !StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS)) {
                    diagIronPhase(mod, "make iron armor set",
                            "ironArmorMet=" + ironArmorMet(mod)
                                    + " eyeGearSatisfied=" + eyeGearSatisfied);
                    Item[] ownedNotEquipped = Arrays.stream(ItemHelper.IRON_ARMORS)
                            .filter(a -> mod.getItemStorage().hasItem(a) && !StorageHelper.isArmorEquipped(mod, a))
                            .toArray(Item[]::new);
                    ItemTarget[] missingIronArmor = Arrays.stream(ItemHelper.IRON_ARMORS)
                            .filter(a -> !mod.getItemStorage().hasItem(a) && !StorageHelper.isArmorEquipped(mod, a))
                            .map(a -> new ItemTarget(a, 1))
                            .toArray(ItemTarget[]::new);
                    // (2026-09-16 用户"做一件穿一件, 还多次打掉工作台, 浪费时间")
                    // 旧行为: ownedNotEquipped 优先 → 每做出一件, squash 合成链在收
                    // 下一件产出时被 EquipArmorTask 拦腰打断 (tasks.log 09:57:04
                    // "Get catalogued ... by <Equipping armor {iron_leggings}> took=850ms",
                    // 正在 Receiving output), 穿完回来重建合成任务又要重新放台。
                    // 改为: 还有缺件就继续做 (含补材料), 缺件清零后一次性穿齐。
                    // 兜底: 合成阶段超 90s (材料断供卡住) 回退旧行为先穿已持有的,
                    // 防止"包里躺着一件事不穿却永远在补缺件"。
                    boolean craftStalledTooLong = _ironArmorCraftSinceMs != 0
                            && System.currentTimeMillis() - _ironArmorCraftSinceMs > 90_000;
                    if (missingIronArmor.length > 0 && !craftStalledTooLong) {
                        if (_ironArmorCraftSinceMs == 0) {
                            _ironArmorCraftSinceMs = System.currentTimeMillis();
                        }
                        setDebugState("Making iron armor set before mining");
                        return TaskCatalogue.getSquashedItemTask(missingIronArmor);
                    }
                    _ironArmorCraftSinceMs = 0;
                    if (ownedNotEquipped.length > 0) {
                        setDebugState("Equipping owned iron armor");
                        return new EquipArmorTask(ownedNotEquipped);
                    }
                    setDebugState("Making iron armor set before mining");
                    return TaskCatalogue.getSquashedItemTask(missingIronArmor);
                }

                // Then get diamond
                if (!eyeGearSatisfied) {
                    // (2026-09-03 钻石时间上限) 累积钻石收集活跃 ≥5 分钟则永久放弃,
                    // 不再卡死在"差几颗钻石", 直接去下界。
                    if (_diamondCollectGivenUp) {
                        // 已放弃钻石收集, 跳过往下去下界 (2026-09-04 途中先补金靴)
                        Task bootsTask = getPreNetherGoldenBootsTask(mod);
                        if (bootsTask != null) {
                            return bootsTask;
                        }
                        // (2026-09-09 进下界前排空熔炉) 金靴做完 → 清仓生金 → 排炉 → 进门。
                        Task preNetherGold = getPreNetherGoldSmeltTask(mod);
                        if (preNetherGold != null) {
                            return preNetherGold;
                        }
                        Task preNetherDrain = getPreNetherFurnaceDrainTask(mod);
                        if (preNetherDrain != null) {
                            return preNetherDrain;
                        }
                        // (2026-09-09 进下界前必须持盾) 缺盾先补, 见 getPreNetherShieldTask。
                        Task preNetherShield = getPreNetherShieldTask(mod);
                        if (preNetherShield != null) {
                            return preNetherShield;
                        }
                        // (2026-09-15 进下界前食物硬闸 150)
                        Task preNetherFood = getPreNetherFoodTask(mod);
                        if (preNetherFood != null) {
                            return preNetherFood;
                        }
                        setDebugState("Giving up diamond gear, proceeding to Nether (limit reached)");
                        return _goToNetherTask;
                    }
                    recordDiamondCollectActive(mod); // 钻石收集进行中, 累积活跃时间
                    _gearTask = TaskCatalogue.getSquashedItemTask(Stream.concat(Arrays.stream(COLLECT_EYE_ARMOR).filter(item -> !mod.getItemStorage().hasItem(item) && !StorageHelper.isArmorEquipped(mod, item)).map(item -> new ItemTarget(item, 1)), Arrays.stream(COLLECT_EYE_GEAR)).toArray(ItemTarget[]::new));
                    _gearTaskIsDiamond = true;
                    return _gearTask;
                }
                // Then go to the nether.
                // (2026-09-09 进下界前) 主路径同样过 清仓生金→排炉 两道闸门。
                Task preNetherGold = getPreNetherGoldSmeltTask(mod);
                if (preNetherGold != null) {
                    return preNetherGold;
                }
                Task preNetherDrain = getPreNetherFurnaceDrainTask(mod);
                if (preNetherDrain != null) {
                    return preNetherDrain;
                }
                // (2026-09-09 进下界前必须持盾) 缺盾先补, 见 getPreNetherShieldTask。
                Task preNetherShield = getPreNetherShieldTask(mod);
                if (preNetherShield != null) {
                    return preNetherShield;
                }
                setDebugState("Going to Nether");
                return _goToNetherTask;
            }
            case NETHER -> {
                // (2026-08-23 贪婪打末影人 rev2) 用户设计: 珍珠 <16 且感知到
                // 8 格内末影人 → 直接视为击杀目标 (这个距离末影人已经不可能
                // 无视我们, 一旦对准它就会瞬移来打)。放在下界 case 最顶部,
                // 任何阶段 (防火药水/挖金/交易/烈焰棒) 都优先触发, 子树内
                // 专注清末影人攒珍珠不切换。8 格触发而非"看见就打":
                // 远处末影人走过去打反而浪费, 等它靠近再接战最稳。
                Task greedyEnderman = getGreedyEndermanKillTask(mod);
                if (greedyEnderman != null) {
                    return greedyEnderman;
                }
                // (2026-09-05 下界就近搜刮, 用户"要塞上近处有箱子不顺便开, 矿洞时是实现的")
                // 主世界 NEARBY LOOT SWEEP 的下界版: 要塞走廊里的箱子/木桶/陷阱箱
                // 可感知 (LOS, 防透视) 且未开过时优先拿空。8 格内刷怪笼仍抑制
                // (先清怪再挖刷怪笼的老规矩, 烈焰人刷怪笼旁的箱子等清完再开)。
                // 物品清单复用 lootableItems (金锭/黑曜石/打火石/钻石等下界刚需)。
                // (2026-09-06 搜刮让位捡烈焰棒, 用户"没把烈焰棒捡起来就继续探索了")
                // 地上有烈焰棒掉落物 (已感知) 时不开箱: 棒是本阶段目标, 开箱抢跑
                // 只会把棒晾在地上 (日志 11:40:59: Killing blaze 被搜刮打断, 此后
                // Mob Defense 连环打断, 棒一直没人捡)。满背包捡棒时 PickupDroppedItemTask
                // 靠近后会自动触发 EnsureFreeInventorySlotTask 丢垃圾腾位。
                {
                    mod.getBlockTracker().trackBlock(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
                    if (!mod.getEntityTracker().itemDropped(Items.BLAZE_ROD) && !nearbySpawnerBlocked(mod)) {
                        if (shouldForce(mod, _nearbySweepTask)) {
                            return _nearbySweepTask;
                        }
                        _nearbySweepTask = null;
                        BlockPos nearestContainer = null;
                        double bestSq = NEARBY_LOOT_RANGE * NEARBY_LOOT_RANGE;
                        BlockPos playerPos = mod.getPlayer().getBlockPos();
                        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL)) {
                            if (_sweptContainers.contains(pos)) continue;
                            // (2026-09-18 堡垒箱子让位, 用户"猪灵城堡的宝箱不该开, 回报有限")
                            // 猪灵堡垒的箱子/木桶旁必有守卫, 开箱瞬间围殴: 20260918-115412
                            // 场次实锤, 12:02:36 Sweeping nether loot (-339,35,20) → 12:02:44
                            // CONFIDENT FIGHT → 12:02:56 被蛮兵打进岩浆海 (death #2)。守卫
                            // 撤走前把该容器拉黑 (_sweptContainers), 避免 retreat 结束后
                            // 立刻又跑回去开 → 再被打 → 死循环。可见口径与 GOLD-GATE 同源
                            // (模仿人类, 隔墙看不见的不算); 蛮兵不受金甲安抚, 普通猪灵
                            // 开箱也会被激怒, 两者都算守卫。
                            boolean piglinGuarded = false;
                            Entity guard = mod.getEntityTracker()
                                    .getClosestEntity(Vec3d.ofCenter(pos), PiglinBruteEntity.class, PiglinEntity.class)
                                    .orElse(null);
                            if (guard != null && guard.isAlive()
                                    && guard.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 12 * 12) {
                                piglinGuarded = true;
                                _sweptContainers.add(pos);
                            }
                            if (piglinGuarded) continue;
                            if (!VisibilityHelper.isBlockPerceivable(mod, pos)) continue;
                            double sq = pos.getSquaredDistance(playerPos);
                            if (sq < bestSq) {
                                bestSq = sq;
                                nearestContainer = pos;
                            }
                        }
                        if (nearestContainer != null) {
                            _sweptContainers.add(nearestContainer);
                            setDebugState("Sweeping nearby nether loot: " + nearestContainer.toShortString());
                            _nearbySweepTask = new LootContainerTask(nearestContainer, lootableItems(mod), _noCurseOfBinding);
                            return _nearbySweepTask;
                        }
                    }
                }
                // (2026-09-12 金靴下界补链, 用户"可以在地狱里找到足够金子然后再做")
                // 主世界补靴超时被顺延下来时在这里补 — 放在就近搜刮之后 (箱子里的
                // 金锭可能直接把 4 锭凑齐, 见 lootableItems 的 GOLD_INGOT 条件),
                // 防火药水/烈焰棒之前 (猪灵交易要穿金靴才不被敌对)。
                Task netherBoots = getNetherGoldenBootsTask(mod);
                if (netherBoots != null) {
                    return netherBoots;
                }
                if (needsBlazeRods) {
                    // (2026-08-23 防火药水保命) 打烈焰人前先搞防火药水
                    // (用户设计: 下界挖金 → 猪灵交易 → 打烈焰人时喝)。
                    // (2026-09-06 用户) 目标提为 2 瓶 (FIRE_RES_POTION_TARGET); 花不花由
                    // CollectBlazeRodsTask 的喝药门槛管。
                    // (2026-09-14 用户"要重收, 但加冷却, 不要马上触发") 旧注释写的
                    // "_fireResPhaseDone 闩保证之后喝掉也不会重新进本阶段"已废除 ——
                    // 那等于两瓶喝完后下界全程裸奔 (本次事故: 09:56 药效到期后 7 秒烧死,
                    // 背包 0 瓶, 且此后再也不会去收)。现在阶段可重开, 但必须过冷却。
                    if (!_fireResPhaseDone) {
                        if (!_fireResPhaseActive) {
                            if (ItemHelper.countFireResPotions(mod) < FIRE_RES_POTION_TARGET) {
                                _fireResPhaseActive = true;
                                _fireResPhaseTimer.reset();
                            } else {
                                closeFireResPotionPhase();
                            }
                        }
                        if (_fireResPhaseActive) {
                            if (ItemHelper.countFireResPotions(mod) >= FIRE_RES_POTION_TARGET) {
                                Debug.logMessage("(FireRes) Target reached: " + FIRE_RES_POTION_TARGET
                                        + " fire resistance potions. Proceeding to blaze rods.");
                                closeFireResPotionPhase();
                            } else if (_fireResPhaseTimer.elapsed()) {
                                Debug.logMessage("(FireRes) Phase timed out with " + ItemHelper.countFireResPotions(mod)
                                        + " potions. Proceeding to blaze rods anyway.");
                                Debug.logToFile("altoclef_diag.log", "[FireRes] outer phase TIMEOUT, potions="
                                        + ItemHelper.countFireResPotions(mod));
                                closeFireResPotionPhase();
                            } else {
                                setDebugState("Getting fire resistance potions (gold + piglin trading)");
                                return new CollectFireResPotionsTask(FIRE_RES_POTION_TARGET);
                            }
                        }
                    } else if (shouldReopenFireResPotionPhase(mod)) {
                        // (2026-09-14 用户) 药水耗尽 + 冷却已过 → 重开收药阶段。
                        // 不马上触发: 冷却由 closeFireResPotionPhase() 在阶段关闭那一刻起算。
                        Debug.logMessage("(FireRes) No potions left, reopening the potion phase.");
                        Debug.logToFile("altoclef_diag.log", "[FireRes] phase REOPENED (potions exhausted, cooldown "
                                + (FIRE_RES_REOPEN_COOLDOWN_MS / 1000) + "s elapsed)");
                        _fireResPhaseActive = true;
                        _fireResPhaseTimer.reset();
                        _fireResPhaseDone = false;
                    }
                    setDebugState("Getting Blaze Rods");
                    return getBlazeRodsTask(mod, blazeRodTarget);
                }
                setDebugState("Getting Ender Pearls");
                return getEnderPearlTask(mod, enderPearlTarget);
            }
            case END -> throw new UnsupportedOperationException("You're in the end. Don't collect eyes here.");
        }
        return null;
    }

    /**
     * (2026-08-31 顺路收容不让位打断主线) 主链是否"忙" — 正在 合成铁装 / 熔炼 /
     * 挖矿(收矿) / 找食物 等正事时返回 true, 顺路收工作台/熔炉的侧线必须让位,
     * 绝不打断主线 (用户实测 12:42: 熔炼 9 铁锭到一半被 "Pick up furnace while
     * we're at it" 拦腰打断)。顺着 _sub 子链深查, 能覆盖铁装 squash 任务
     * (CataloguedResourceTask) 内部展开的挖矿/熔炼/收集树枝。
     */
    private boolean isMainLineBusy(AltoClef mod) {
        // (2026-09-03 钻石 give up 后主线去下界) 钻石收集已永久放弃、已转入推进
        // 下界 → 一律视为主链忙, 收工作台/熔炉等"顺路侧线"必须让位, 否则
        // "Going to NETHER" 与 "Pick up crafting table" 每几秒互踩 (日志
        // 15:19 t=25454~25819: give up→去下界→被打断收工作台→挖完又 give up→
        // 循环 8 秒没进展)。
        if (_diamondCollectGivenUp) return true;
        return thisOrChildSatisfies(task ->
                task instanceof SmeltInFurnaceTask                      // 熔炼
                        || task instanceof PickupFromContainerTask      // (2026-09-05) 从容器收产物/物资也是正事
                        || task instanceof CollectFoodTask               // 找食物
                        || task instanceof MineAndCollectTask            // 挖矿/收矿
                        || task instanceof CollectVisibleOresTask        // 贪心收矿
                        || task instanceof CraftInInventoryTask          // 铁装等 2x2 合成
                        || task instanceof CraftInTableTask              // 铁装等 3x3 合成
                        || (task instanceof DoStuffInContainerTask cont
                            && (cont.getContainerTarget().matches(Items.FURNACE)
                                || cont.getContainerTarget().matches(Items.CRAFTING_TABLE))) // 用熔炉/工作台
                        // (2026-09-02 整个装备合成链都算忙, 用户"做铁装做一件就收一下
                        // 工作台再放下来做下一件") 铁装/铁套/钻石装 squash(合并)
                        // CataloguedResourceTask 逐件做护甲。每件做完的瞬间其 activeTask
                        // 归零重选, 那 1 tick 上方顺序子任务不是 Craft* → isMainLineBusy
                        // 误判 false → 顶部"顺路收工作台/熔炉"分支把台子挖走, 下一件又
                        // 得重放台子。整条 squash 链视为忙, 台/炉在整个装备周期内让位,
                        // 铁齐全后直接用同一张台子把装备全部做完。台子不在近旁时
                        // findNearby=false 本就不触发, 故只抑制"正要接着用台"的场景。
                        || task instanceof CataloguedResourceTask
                        // (2026-09-16 用户"做一件穿一件, 还多次打掉工作台") 穿甲窗口
                        // 也要算忙: tasks.log 09:57:05 "Equipping armor {iron_leggings}"
                        // 被 "Mine And Collect: [[crafting_table]]" 抢断 → 刚放好的台
                        // 被挖走, 下一件又得重放。09-02 的 CataloguedResourceTask 白名单
                        // 只盖住合成链, 盖不住件间穿插的 EquipArmorTask。
                        || task instanceof EquipArmorTask
        );
    }

    /**
     * (2026-08-22) 16 格内是否有可拾取的工作台。
     *
     * 先查 BlockTracker 缓存 (刚放置时 BlockPlaceEvent 已登记, 但
     * stopTracking 后缓存不清理, 需过滤"世界真实存在 + 距离")。
     * 缓存未命中时兜底暴力扫描 (1 秒节流, 16³=36k 次 getBlockState)。
     * 刻意不调用 isBlockPerceivable: 紧邻工作台的 LOS 判定不稳, 曾导致
     * "刚合成完的工作台留下不捡" (用户实测 14:22)。
     */
    private boolean findNearbyCraftingTable(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.CRAFTING_TABLE)) {
            if (pos.isWithinDistance(player, CRAFTING_TABLE_GRAB_RANGE)
                    && mod.getWorld().getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE) {
                return true;
            }
        }
        if (!_pickupTableScanTimer.elapsed()) return false;
        _pickupTableScanTimer.reset();
        int r = (int) Math.ceil(CRAFTING_TABLE_GRAB_RANGE);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = player.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.CRAFTING_TABLE
                            && WorldHelper.canBreak(mod, pos)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * (2026-09-15 进下界前食物硬闸, 用户"去地狱之前必须达到150units") 进下界
     * (_goToNetherTask) 前的最后闸之一: 背包食物分 < preNetherFoodUnits → 返回
     * CollectFoodTask 收满; 够了 → null 放行。ignoreExpeditionReduction=true:
     * 硬闸不受觅食远征"超时降低"影响 (降低量只作用于日常囤粮, 地狱前必须 150)。
     */
    private Task getPreNetherFoodTask(AltoClef mod) {
        int need = _config.preNetherFoodUnits;
        if (need <= 0) return null;
        double score = StorageHelper.calculateInventoryFoodScore(mod);
        if (score >= need) return null;
        CollectFoodTask wanted = new CollectFoodTask(need, true);
        if (!(_foodTask instanceof CollectFoodTask)
                || !_foodTask.equals(wanted)
                || !_foodTask.isActive() || _foodTask.isFinished(mod)) {
            Debug.logMessage("[PreNetherFood] food=" + (int) score + " < " + need
                    + " — collecting food before entering Nether.");
            Debug.logToFile("altoclef_diag.log",
                    "[MAIN-FIX]\t[PreNetherFood]\tfood=" + (int) score + " < " + need
                            + " -> CollectFoodTask(" + need + ", ignoreReduction) before Nether");
            _foodTask = wanted;
        }
        setDebugState("Collecting food before Nether (" + (int) score + "/" + need + " units)");
        return _foodTask;
    }

    /**
     * (2026-09-09 进下界前排空熔炉) 进下界 (_goToNetherTask) 前的最后一道闸:
     * 16 格内存在非空熔炉 (input/fuel/output 任一有物) → 返回 EmptyFurnaceTask
     * (取尽三槽并连炉拆走); 没有 → null 放行。判定口径与 findNearbyFurnace 一致
     * (BlockTracker + 16 格暴力兜底, 仅非空), 扫描 1s 节流。实例粘性: 一旦派发
     * 就持续返回同一实例直到完成/放弃, 不被其他分支抢成 1-tick 任务。
     * 放弃上限: 外部 90s 超时或 gaveUpWandering() → 置 30s 触发层冷却后放行,
     * 防止进下界主线被一颗够不着的炉子卡死 (凡"停下等"必须有放弃上限)。
     */
    private Task getPreNetherFurnaceDrainTask(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now < _preNetherFurnaceDrainGiveUpUntilMs) {
            return null;
        }
        if (_preNetherDrainScanTimer.elapsed()) {
            _preNetherDrainScanTimer.reset();
            _preNetherDrainScanPos = scanNearestNonEmptyFurnace(mod);
        }
        if (_preNetherDrainScanPos == null) {
            // 没有非空炉 → 缓存实例作废, 直接放行。
            _preNetherFurnaceDrainTask = null;
            return null;
        }
        if (_preNetherFurnaceDrainTask == null) {
            _preNetherFurnaceDrainTask = new EmptyFurnaceTask();
            _preNetherFurnaceDrainStartMs = now;
            Debug.logMessage("(PreNetherDrain) Non-empty furnace @ "
                    + _preNetherDrainScanPos.toShortString()
                    + " — draining (input/fuel/output) and taking the furnace before entering Nether.");
            Debug.logToFile("altoclef_diag.log",
                    "[MAIN-FIX]\t[PreNetherDrain]\tdrain furnace @"
                            + _preNetherDrainScanPos.toShortString());
        }
        if (now - _preNetherFurnaceDrainStartMs > 90_000 || _preNetherFurnaceDrainTask.gaveUpWandering()) {
            Debug.logMessage("(PreNetherDrain) Furnace drain gave up (timeout/unreachable), proceeding to Nether. 30s cooldown.");
            Debug.logToFile("altoclef_diag.log",
                    "[MAIN-FIX]\t[PreNetherDrain]\tgive up, cooldown 30s");
            _preNetherFurnaceDrainGiveUpUntilMs = now + 30_000;
            _preNetherFurnaceDrainTask = null;
            _preNetherDrainScanPos = null; // 下次扫描重新找炉
            return null;
        }
        setDebugState("Draining nearby furnace before Nether");
        return _preNetherFurnaceDrainTask;
    }

    /**
     * (2026-09-09) 16 格内最近的非空熔炉坐标; 没有则 null。BlockTracker 命中优先
     * (零扫描成本), miss 才走 1s 节流暴力扫 (同 findNearbyFurnace 成本口径)。
     */
    private BlockPos scanNearestNonEmptyFurnace(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        // (2026-09-11 互抢豁免) 守卫在值的工作炉 = 冶炼链自家正在用的炉,
        // 跳过不排 —— 否则闸门拆炉→链重建再放再投→闸门再拆, 2.1s 周期
        // 互抢死循环 (125529 场次 12 分钟零进度)。守卫 onStop 后静态清除,
        // 炉若仍非空, 下一轮扫描照常排掉 — 闸门职责不受损。
        // (2026-09-12 v20 填料期豁免) 守卫静态口只盖交接后; 20260912-195954
        // 20:07:08-21 实锤填料阶段 (Moving Materials, 尚未 Handing over) 被闸门
        // 1s 扫描撞上 → 拆炉 → 链捡回重放再填 → 再拆, 2-4s 周期三轮零进度。
        // 补 SmeltInFurnaceTask.getActiveSmeltingFurnacePos (链锁定炉位即登记,
        // onStop 注销) 覆盖全程。
        BlockPos guarded = FurnaceSmeltGuardTask.getActiveGuardedFurnacePos();
        BlockPos smelting = SmeltInFurnaceTask.getActiveSmeltingFurnacePos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.FURNACE)) {
            if (guarded != null && pos.equals(guarded)) continue;
            if (smelting != null && pos.equals(smelting)) continue;
            if (pos.isWithinDistance(player, FURNACE_GRAB_RANGE)
                    && mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE
                    && WorldHelper.isFurnaceNonEmpty(mod, pos)) {
                double sq = pos.getSquaredDistance(player);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = pos;
                }
            }
        }
        if (best != null) return best;
        int r = (int) Math.ceil(FURNACE_GRAB_RANGE);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = player.add(dx, dy, dz);
                    // (2026-09-12 v20) 兜底循环同样豁免守卫/冶炼链在用炉
                    // (09-11 的守卫豁免只盖了上面 tracker 循环, 这里一直漏)。
                    if (guarded != null && pos.equals(guarded)) continue;
                    if (smelting != null && pos.equals(smelting)) continue;
                    if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE
                            && WorldHelper.isFurnaceNonEmpty(mod, pos)) {
                        double sq = pos.getSquaredDistance(player);
                        if (sq < bestSq) {
                            bestSq = sq;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }

    // (2026-08-27 熔炉即收) 与 findNearbyCraftingTable 同款: 16 格内是否有可用熔炉。
    private boolean findNearbyFurnace(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.FURNACE)) {
            if (pos.isWithinDistance(player, FURNACE_GRAB_RANGE)
                    && mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE
                    // (2026-09-09 只收非空熔炉) 空炉不触发取尽+拆走侧线 —
                    // 路过村庄铁匠铺的空炉不再把 bot 绊进收炉分支
                    // (9月8日 23:26 日志: 铁匠铺空炉触发 EmptyFurnaceTask → 无限 wander)。
                    && WorldHelper.isFurnaceNonEmpty(mod, pos)) {
                return true;
            }
        }
        if (!_pickupFurnaceScanTimer.elapsed()) return false;
        _pickupFurnaceScanTimer.reset();
        int r = (int) Math.ceil(FURNACE_GRAB_RANGE);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = player.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE
                            && WorldHelper.canBreak(mod, pos)
                            // (2026-09-09 只收非空熔炉) 同上, 空炉跳过继续扫。
                            && WorldHelper.isFurnaceNonEmpty(mod, pos)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Task setSpawnNearPortalTask(AltoClef mod) {
        if (_setBedSpawnTask.isSpawnSet()) {
            _bedSpawnLocation = _setBedSpawnTask.getBedSleptPos();
        } else {
            _bedSpawnLocation = null;
        }
        if (shouldForce(mod, _setBedSpawnTask)) {
            // Set spawnpoint and set our bed spawn when it happens.
            setDebugState("Setting spawnpoint now.");
            return _setBedSpawnTask;
        }
        // Get close to portal. If we're close enough, set our bed spawn somewhere nearby.
        if (WorldHelper.inRangeXZ(mod.getPlayer(), WorldHelper.toVec3d(_endPortalCenterLocation), END_PORTAL_BED_SPAWN_RANGE)) {
            return _setBedSpawnTask;
        } else {
            setDebugState("Approaching portal (to set spawnpoint)");
            // (2026-08-26 修复: 用户实测"被导航任务打断后在水面上无法转向被困")
            // 普通 GetToXZ 在水上不转船会卡住。骑船中/路径横跨大水面(≥30 格)
            // 时改用 BoatCrossingTask (onStart 已处理骑船续驾 S_DRIVE)。
            boolean ridingBoat = mod.getPlayer() != null
                    && mod.getPlayer().getVehicle() instanceof BoatEntity;
            BlockPos targetXZ = new BlockPos(_endPortalCenterLocation.getX(), 0, _endPortalCenterLocation.getZ());
            if (ridingBoat || WorldHelper.isLargeWaterCrossing(mod, mod.getPlayer().getBlockPos(), targetXZ, 30)) {
                return new BoatCrossingTask(targetXZ, false);
            }
            return new GetToXZTask(_endPortalCenterLocation.getX(), _endPortalCenterLocation.getZ());
        }
    }

    /**
     * (2026-09-01 从 getEyesOfEnderTask 抽出) 木头强前置门控: 木头未攒够就返回
     * 收集任务, 否则返回 null 放行后续分支。本身含"回地表"与"粘性复用同一任务
     * 实例"逻辑, 见原实现注释。烧肉/挖矿/箱子等分支都让位给它。
     */
    private Task getWoodPerfusionTask(AltoClef mod) {
        // (2026-08-23 原木统计修正) 旧逻辑 getItemCount(ItemHelper.LOG)
        // 依赖 ItemStorageTracker 缓存, 用户实测背包 30+ 原木却统计为 0,
        // 导致 Restocking 反复误触发。改用直接扫描玩家背包 (main+offhand)
        // 统计原木, 不依赖 tracker 缓存刷新时机。
        int logCount = countLogsInPlayerInventory(mod);
        // (2026-09-17 "链上已有木任务"口径, 事故 20260917-143638) 见 woodMiningActiveInChain。
        // 本方法全程复用同一判定, 诊断串也用它, 避免"门控用的口径"与"日志写的口径"分家。
        boolean woodTaskActive = (_woodStockpileTask != null && !_woodStockpileTask.isFinished(mod))
                || woodMiningActiveInChain(mod);
        // (2026-09-01 用户"地下不触发收集木头, 除非原木<4") 地下时原木 ≥
        // woodRestockThreshold(4) 就地继续挖矿, 不回地表/不就地砍树 — 否则
        // minWoodLogs(25) 未攒够就让 bot 在原木还很多时频繁回地表打断下矿
        // (日志 10:35 "Returning to surface to collect wood" 实锤)。只有跌破
        // 4 才算真缺木头, 才回地表。地表逻辑仍按原 minWoodLogs 攒满。
        if ((isUndergroundDebounced(mod) || isInPit(mod)) && logCount >= _config.woodRestockThreshold) {
            return null;
        }
        // (2026-08-22) 地表资源前置: 树只长在地表。深处矿洞/水下时先挖回
        // 地表再收集 — Baritone 垂直挖穿几十格岩层耗时数分钟, 若直接派收集
        // 任务会被进度检查误判"不可达"而"挖一半就放弃换目标"。
        if ((!_woodStockpiled && logCount < _config.minWoodLogs)
                || (_woodStockpiled && logCount < _config.woodRestockThreshold)) {
            // (2026-08-27 原木↔回表面横跳修复) 已有进行中的原木收集任务时
            // 别再用"回表面"反复打断 (导航路线若中途低于表面会触发横跳)。
            // (2026-09-17 口径扩展, 事故 20260917-143638) 旧口径只认私有实例
            // `_woodStockpileTask` —— 床链内部的 MineAndCollectTask(木头) 不是它, 于是
            // 门控认为"没人砍木", 把正在为自己供料的床链整个顶掉: 3 羊毛已到手、
            // 只差 3 木板, 14:38:02 被 GoToSurfaceTask 抢走, 整夜再没做床 (次日
            // t=1691 背包 3 羊毛+3 木板已满足配方却无人发起)。
            // 现在 woodTaskActive 同时涵盖"链上确有一个木头目标的采集任务"。
            if (!woodTaskActive && (isUndergroundDebounced(mod) || isInPit(mod))) {
                setDebugState("Returning to surface to collect wood.");
                return new GoToSurfaceTask();
            }
        }
        if (!_woodStockpiled) {
            if (logCount >= _config.minWoodLogs) {
                _woodStockpiled = true;
                _woodStockpileTask = null;
                _stoneAxeTask = null;
            } else {
                setDebugState("Stockpiling wood (logs): " + logCount + "/" + _config.minWoodLogs);
                diagIronPhase(mod, "stockpile wood",
                        "stockpiled=" + _woodStockpiled
                                + " logCount=" + logCount
                                + " minWoodLogs=" + _config.minWoodLogs
                                + " woodTaskActive=" + woodTaskActive
                                + " underground=" + isUndergroundDebounced(mod) + " inPit=" + isInPit(mod));
                // (2026-08-27 一次性粘性) 复用同一个任务实例, 不再每 tick new。
                if (_woodStockpileTask == null || _woodStockpileTask.isFinished(mod)) {
                    _woodStockpileTask = TaskCatalogue.getItemTask("log", _config.minWoodLogs);
                }
                return prependAxeForWood(mod, _woodStockpileTask);
            }
        } else if (logCount < _config.woodRestockThreshold) {
            // (2026-09-17 显示口径修正) 旧串 "Restocking wood (logs): 3/4" 里的 4 是
            // 【触发水位】, 收集目标其实是 minWoodLogs(25) —— 用户据此误读为"只补 4 个,
            // 用掉 1 个就又补"。这里把水位与目标同时写出来; 前缀保持原样以兼容既有判据。
            setDebugState("Restocking wood (logs): " + logCount + "/" + _config.woodRestockThreshold
                    + " (waterline, settle target " + _config.minWoodLogs + ")");
            diagIronPhase(mod, "restock wood",
                    "logCount=" + logCount
                            + " restockThresh=" + _config.woodRestockThreshold
                            + " settleTarget=" + _config.minWoodLogs
                            + " woodTaskActive=" + woodTaskActive);
            if (_woodStockpileTask == null || _woodStockpileTask.isFinished(mod)) {
                _woodStockpileTask = TaskCatalogue.getItemTask("log", _config.minWoodLogs);
            }
            return prependAxeForWood(mod, _woodStockpileTask);
        }
        return null;
    }

    /**
     * (2026-09-17 新增, 事故 20260917-143638) 当前【运行中的任务链】上是否已有木头类采集任务。
     *
     * <p>为什么需要它: {@code getWoodPerfusionTask} 的"是否已在砍木"原先只看私有字段
     * {@code _woodStockpileTask} —— 那只能认出门控自己派出去的囤木任务。别人派发的木类
     * 采集任务(典型: 床链 Crafting bed → CollectRecipeResources → Crafting planks →
     * MineAndCollectTask)完全不可见, 于是门控在"地下 + 原木 < 4"时把它整条顶成
     * GoToSurfaceTask。实测事故 20260917-143638: 3 羊毛已到手、只差 3 块木板, 14:38:02
     * 床链被"回地表囤木"掐断; 5 秒后夜间找床计时器到期判定"附近无机会"→
     * {@code _nightBedGivenUp} 锁一整夜, 背包里的羊毛白白躺着。
     *
     * <p>实现: 从链顶层任务沿当前激活子链({@code Task.thisOrChildSatisfies} 走 {@code _sub})
     * 自省, 命中任一"木头目标"的 {@link MineAndCollectTask} 即算"已在砍木"。
     * 只读、无副作用, 不改变任何任务的调度。
     */
    private boolean woodMiningActiveInChain(AltoClef mod) {
        Task top = mod.getUserTaskChain().getCurrentTask();
        if (top == null) return false;
        return top.thisOrChildSatisfies(t -> t instanceof MineAndCollectTask mac && mac.isWoodTarget());
    }

    /**
     * (2026-09-03 囤木前先确保斧子; 用户"他还是不做石斧——他需要先挖石头凑材料才能触发做斧")
     * 囤一大把原木前若没有任何可用斧子, 就【无条件】先派 getItemTask(STONE_AXE,1), 让该
     * 任务自己去挖 3 圆石、合台、合木棍、合成石斧 —— 而不是只在我们已摸到材料时才做, 否则
     * 没圆石/没台就一直手撸树。石斧是 3x3 配方, 子任务内部会先挖石/做台/合棍, 不会递归回
     * 本囤木任务(那是不同目标任务)。斧子一到位即放行真正的囤木任务。若已有石斧/铁/钻/下界
     * 合金斧则直接用原任务。不做木斧: 木斧提升有限, 要就石斧(用户预期)。
     */
    private Task prependAxeForWood(AltoClef mod, Task woodTask) {
        boolean axeOwned = false;
        for (Item axe : new Item[]{Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE}) {
            if (StorageHelper.isItemOwned(mod, StorageHelper.Ownership.USE, axe)) {
                axeOwned = true;
                break;
            }
        }
        if (axeOwned) {
            _axeAbsentSinceMs = 0L;
            _axeHystLogged = false;
            return woodTask;
        }
        // (2026-09-16 迟滞修横跳) 无斧需连续持续 3s 才允许回切斧链。
        long nowMs = System.currentTimeMillis();
        if (_axeAbsentSinceMs == 0L) {
            _axeAbsentSinceMs = nowMs;
        }
        if (nowMs - _axeAbsentSinceMs < AXE_ABSENT_HYSTERESIS_MS) {
            return woodTask;
        }
        setDebugState("Stockpiling wood: crafting stone axe first (gather cobble/table/sticks)");
        // (2026-09-16 验证日志, 2026-09-17 补边沿闩锁) 迟滞放行后的真回切, 只落盘不进聊天。
        // 本仓约定"长驻状态的分支打点必须边沿触发"(见 TimeoutLog 口径) —— 原实现无
        // 闩锁, 3s 迟滞放行后【每 tick】都打一条: 20260917-135722 实测 56 秒 1068 条
        // (≈每 50ms 一条, 20Hz × 56s), 把 diag.log 冲成噪音且掩盖真实事件。改为只在
        // "持斧/迟滞中 → 放行"这一边沿打一条; 拿到斧时复位闩锁 (见 axeOwned 分支),
        // 于是"真丢斧"仍是每次一条 —— 原验证判据 (低频、真丢斧才一条) 不变。
        // 失败判据不变: 翻转依旧且无此日志 = 翻转发生在本方法之外的其他分支。
        if (!_axeHystLogged) {
            _axeHystLogged = true;
            Debug.logToFile("altoclef_diag.log", "[AXE-HYST] axe absent confirmed, switching to axe chain");
        }
        // (2026-09-16 修复每 tick 重建) 见 _stoneAxeTask 字段注释。石斧到手后上方
        // isItemOwned 命中 → 直接返回 woodTask, 自然回落原木收集; 此间粘滞复用同一
        // 实例, 不再每 tick new 导致石斧链从头跑被自己打断。
        if (_stoneAxeTask == null || _stoneAxeTask.isFinished(mod)) {
            // (2026-09-16 验证日志) 新建即打点, 只落盘不进聊天。石斧到手是低频事件,
            // 无需节流。验证判据 —— 生效: [AXE-CACHE] new 低频 (每次真造斧才一条),
            // 且相邻两条间隔内 WOOD-BATCH #1→#3 连续推进; 失败: 每秒多条 new =
            // 粘滞失效, "挖一根→重来"死循环回归。
            Debug.logToFile("altoclef_diag.log", "[AXE-CACHE] new stone-axe task (prev "
                    + (_stoneAxeTask == null ? "none" : "finished") + ")");
            _stoneAxeTask = TaskCatalogue.getItemTask(Items.STONE_AXE, 1);
        }
        return _stoneAxeTask;
    }

    /** (2026-09-14) 关闭收药阶段, 并起算"允许重开"的冷却。见 FIRE_RES_REOPEN_COOLDOWN_MS 注释。 */
    private void closeFireResPotionPhase() {
        _fireResPhaseActive = false;
        _fireResPhaseDone = true;
        _fireResReopenAtMs = System.currentTimeMillis() + FIRE_RES_REOPEN_COOLDOWN_MS;
    }

    /**
     * (2026-09-14 用户"要重收, 但是加冷却时间, 不要马上触发") 是否该重开收药阶段。
     * 只在"一瓶都没有"时重开 —— 还剩 1 瓶说明上一轮收的药还没用完, 不必重收。
     */
    private boolean shouldReopenFireResPotionPhase(AltoClef mod) {
        if (ItemHelper.countFireResPotions(mod) > 0) return false;
        return System.currentTimeMillis() >= _fireResReopenAtMs;
    }

    private Task getBlazeRodsTask(AltoClef mod, int count) {
        if (mod.getEntityTracker().itemDropped(Items.BLAZE_POWDER)) {
            return new PickupDroppedItemTask(Items.BLAZE_POWDER, 1);
        }
        return new CollectBlazeRodsTask(count);
    }
    /**
     * (2026-08-23 贪婪打末影人 rev2) 珍珠 <16 且感知到 8 格内末影人时,
     * 返回"清末影人攒珍珠"任务 (KillAndLootTask), 否则 null。
     *
     * 8 格阈值: 用户设计 "末影人距离自己非常近比如 8 格时就把它视为击杀
     * 目标 (触发贪婪任务), 这时候末影人就不可能放过我了"。一旦触发,
     * KillAndLootTask 子树内专注击杀 + 拾取珍珠, 不切换 (优先级高于
     * 防火药水/挖金/烈焰棒等主线)。
     */
    private Task getGreedyEndermanKillTask(AltoClef mod) {
        if (mod.getItemStorage().getItemCount(Items.ENDER_PEARL) >= 16) return null;
        Optional<Entity> enderman = mod.getEntityTracker().getClosestEntity(EndermanEntity.class);
        if (enderman.isEmpty() || !enderman.get().isInRange(mod.getPlayer(), 8)) return null;
        setDebugState("Greedy enderman kill (within 8 blocks, pearls < 16)");
        return new KillAndLootTask(EndermanEntity.class, new ItemTarget(Items.ENDER_PEARL, 16));
    }

    private Task getEnderPearlTask(AltoClef mod, int count) {
        if (_config.barterPearlsInsteadOfEndermanHunt) {
            // Equip golden boots before trading...
            if (!StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS)) {
                return new EquipArmorTask(Items.GOLDEN_BOOTS);
            }
            int goldBuffer = 32;
            return new TradeWithPiglinsTask(32, Items.ENDER_PEARL, count);
        } else {
            if (mod.getEntityTracker().entityFound(EndermanEntity.class) || mod.getEntityTracker().itemDropped(Items.ENDER_PEARL)) {
                return new KillAndLootTask(EndermanEntity.class, new ItemTarget(Items.ENDER_PEARL, count));
            }
            // Search for warped forests this way...
            return new SearchChunkForBlockTask(Blocks.WARPED_NYLIUM);
        }
    }

    private int getTargetBeds(AltoClef mod) {
        boolean needsToSetSpawn = _config.placeSpawnNearEndPortal &&
                (
                        !spawnSetNearPortal(mod, _endPortalCenterLocation)
                                && !shouldForce(mod, _setBedSpawnTask)
                );
        int bedsInEnd = 0;
        for (Item bed : ItemHelper.BED) {
            bedsInEnd += _cachedEndItemDrops.getOrDefault(bed, 0);
        }

        return _config.requiredBeds + (needsToSetSpawn ? 1 : 0) - bedsInEnd;
    }
    private boolean needsBeds(AltoClef mod) {
        int inEnd = 0;
        for (Item item : ItemHelper.BED) {
            inEnd += _cachedEndItemDrops.getOrDefault(item, 0);
        }
        // (2026-08-27 修复: 已放的自放床算进床数) 设重生点放床瞬间背包床数
        // 11→10, 若不把已放的床算上, needsBeds (目标仍含 +1 重生点床, 因重生点
        // 还没确认) 会立即抢占设床任务, MineAndCollect 把刚放的床挖回去
        // (日志实锤 11:45: Collecting 11 beds → Destroy block 765,2,-1615)。
        // 放床后 _lastSelfPlacedBed 已记录, +1 后 10+1=11 ≥ 目标 11 → 不再抢。
        int placedBed = mod.getLastSelfPlacedBed() != null ? 1 : 0;
        return (mod.getItemStorage().getItemCount(ItemHelper.BED) + placedBed + inEnd) < getTargetBeds(mod);
    }
    private Task getBedTask(AltoClef mod) {
        return getBedTask(mod, getTargetBeds(mod));
    }

    /**
     * (2026-08-22 → 2026-09-17 判据统一) "是否在地下"的稳定版: 直接转
     * {@link WorldHelper#isDeepUnderground}, 后者已指向全项目唯一判据
     * {@code SurfaceHelper.isBelowSurfaceDebounced} (5 点多数投票 + 5s 驻留)。
     *
     * <p>保留本方法名而非删除: 本类有 5 个调用点 (:1973/:1991/:2514/:2525/:2542),
     * 删了要改 5 处而无收益。
     *
     * <p>历史: 本方法原是"洞口边缘头顶方块判定逐 tick 翻转"的 2s 双稳态补丁
     * (tasks.log 11:22:32 t=1724-1796 bed↔GoToSurface 每 2 tick 横跳)。判据统一后
     * 抖动已由 5 点空间投票抹平、过渡期由 5s 驻留吸收, 补丁本身成为多余的额外
     * 滞后 → 移除 (否则真实翻面要 5s+2s 才生效)。
     *
     * <p>峡谷搭高 (getPitEscapeY) 仍不经此方法 — 它只在 wood/food 前置分支用,
     * 且壁顶判定 (16 格采样 + 对向双墙) 本身稳定。
     */
    private boolean isUndergroundDebounced(AltoClef mod) {
        return WorldHelper.isDeepUnderground(mod);
    }

    /**
     * (2026-08-22) getPitEscapeY 节流封装: 结果缓存 1 秒。food/wood 前置
     * 分支每 tick 调用, 直接调会每 tick 扫 960 个方块 (4 方向×4 距离×
     * 60 高度的 getBlockState), 卡顿且纯属浪费。
     */
    private boolean isInPit(AltoClef mod) {
        if (_pitEscapeCacheTimer.elapsed()) {
            _pitEscapeYCache = WorldHelper.getPitEscapeY(mod);
            _pitEscapeCacheTimer.reset();
        }
        return _pitEscapeYCache >= 0;
    }

    /**
     * @param targetBeds how many beds to collect. The pre-mining phase only
     *                   needs 1 (spawn safety); the full bed-strats count is
     *                   collected later, closer to the End fight.
     */
    private Task getBedTask(AltoClef mod, int targetBeds) {
        // Collect beds. If we want to set our spawn, collect 1 more.
        setDebugState("Collecting " + targetBeds + " beds");
        // (2026-08-24 做床卡死修复) 删掉"多床先做剪刀"分支。做剪刀需要
        // 铁锭→熔炉→圆石/黑石, 主世界资源不足时触发
        // "Getting container item→合成熔炉→缺材料"死循环 (用户实测
        // 15:32-16:10 卡在做床 40 分钟: Crafting bed→Collect 999999 wool→
        // Craft shears→熔铁→Getting container item→合成熔炉→循环)。
        // 注释本意"杀羊拿 3 羊毛比先搞铁做剪刀更快"。直接走 CollectBedTask:
        // 优先挖村庄床 (BlockTracker 感知), 不够时合成分支 CollectWoolTask
        // 自行处理 (有剪刀剪、没剪刀杀羊), 不陷入剪刀合成链。
        return TaskCatalogue.getItemTask("bed", targetBeds);
    }

    /**
     * (2026-08-27) 地表获取一张床 (持床保险 / 夜间无床睡觉前置)。
     * 语义: 优先挖 96 格内【感知到】的现成床 (CollectBedTask 内部对玩家 8 格内/
     * 自放床做不可达保护, 绝不拆眼前已放置的床也绝不拆重生点床); 96 格内没有
     * 现床时走杀羊/羊毛→合成 (CraftWithMatchingWoolTask) 分支持床。
     * 前提: 只在"地表白天/地表夜间无床"分支调用, 深地下绝不调用 (用户原则: 地下
     * 睡觉与收床都跳过)。
     */
    private Task acquireBedTask(AltoClef mod) {
        return TaskCatalogue.getItemTask("bed", 1);
    }
    private boolean anyBedsFound(AltoClef mod) {
        return mod.getBlockTracker().anyFound(ItemHelper.itemsToBlocks(ItemHelper.BED));
    }

    /**
     * (2026-08-27 睡/床优先, 从 onTick 抽出) 地表夜间/白天持床保险路由。
     * 返回要执行的睡眠/持床任务; 无则返回 null (让 onTick 继续侧线任务)。
     * 在 onTick 最顶部最先调用 — 否则 "收合成台/装备/下矿/烤食" 等分支会在
     * 睡前反复把它抢断 (用户实测: 放床后 "Going to bed to sleep" 被
     * "Get catalogued iron gear" 抢走去下矿, 睡完拆床永远做不完)。
     */
    private Task getNightSleepRouting(AltoClef mod) {
        boolean locatingStronghold = _ranStrongholdLocator && !_locateStrongholdTask.isFinished(mod);
        if (!(_config.sleepThroughNight && !_endPortalOpened && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD && !locatingStronghold)) {
            return null;
        }
        // (2026-09-05 低风险改善, 用户) 传送门已建成(感知到下界门方块) 且 装备达标
        // (eyeGear 齐 或 钻石已超时放弃) → 马上就要进下界, 夜里不再睡觉/找床:
        // 下界没有昼夜循环, 睡觉对主线纯属延误。
        if (shouldSkipSleepForNether(mod)) {
            return null;
        }
        boolean belowSeaLevel = updateBelowSeaLevelDebounce(mod);
        boolean inWater = mod.getPlayer() != null && mod.getPlayer().isTouchingWater();
        boolean ridingVehicle = mod.getPlayer() != null && mod.getPlayer().getVehicle() != null;
        boolean effectivelyDeep = WorldHelper.isDeepUnderground(mod) && !inWater;

        // 路1: 地下或水上 → 睡觉与收床都跳过。
        // (2026-09-01 修"Underground ↔ Getting gear" 0.2s 横跳噪音) 本分支返回 null,
        // onTick 随后必然继续派发真实子任务(装备/挖矿/地牢)并设置自己的 debug 状态 —
        // 这里 setDebugState 同一 tick 内就被覆盖, 只剩 diag-flow 两条变化行刷屏,
        // 掩盖真实任务横跳。返回 null 的分支一律不设状态, 地下信息由 diag 快照的
        // deep= 字段保留。
        // (2026-09-03 过河去睡在床被 inWater 否决导致横跳, 用户) inWater 否决本意
        // 是"水中航行(迁移/逃离)时别半路被叫回去睡觉"。但过河去【已锁定床】的
        // 进行中睡觉任务(_sleepThroughNightTask.isActive())也被它掐断 → 在河道反复
        // flip Sleeping↔收熟牛肉(日志 10:12 44s 不停)。故仅当无进行中睡觉任务时
        // 才因水否决: 保过河抵达, 同时保留非睡觉时的水中航行保护。isNightSleepPriority
        // 判定(L2090)须同步一致。
        // (2026-09-04 水陆交界横跳, 用户"反复横跳") 刚游上岸在水陆交界 (y≈海平面,
        // inWater 每 2 秒翻转), 即便 _nightBedFindInProgress 已 true (夜间找床流程)
        // 也会被 inWater 否决切回收木头 → wood↔bed 每 2s 横跳 (13:59:49-55 日志实锤)。
        // 补 _nightBedFindInProgress: 已进入夜间找床流程就稳定找床, 不被浅水翻转打断。
        if (effectivelyDeep || belowSeaLevel
                || (inWater && !_sleepThroughNightTask.isActive() && !_nightBedFindInProgress)
                || ridingVehicle) {
            return null;
        }

        // 路2/3: 地表
        boolean rawCanSleep = WorldHelper.canSleep();
        if (rawCanSleep) {
            _notSleepableTimer.reset();
            if (_rawSleepableStreakTimer.elapsed()) {
                _wasSleepableOnce = true;
            }
        } else {
            _rawSleepableStreakTimer.reset();
            if (WorldHelper.isBrightDaytime()) {
                _wasSleepableOnce = false;
                // (2026-08-31 白天重置"本夜放弃找床") 让新的夜晚可以再次尝试找床。
                _nightBedFindInProgress = false;
                _nightBedGivenUp = false;
                _bedWoolHoldLogged = false;
            }
        }
        boolean effectiveCanSleep = rawCanSleep || (_wasSleepableOnce && !_notSleepableTimer.elapsed());

        boolean hasBedRegard = mod.getItemStorage().hasItem(ItemHelper.BED)
                || isPlacedBedNearby(mod)
                || isBedPerceived(mod);

        if (effectiveCanSleep) {
            // 路2: 地表夜间 → 优先睡觉
            if (hasBedRegard) {
                // (2026-09-08 睡觉放弃冷却, 用户"尝试次数两次就够了, 兜底不够智能") 
                // PlaceBedAndSetSpawnTask 的真实站侧(去重后通常 2 个)全试过仍没睡
                // 进去 → 静态冷却 30s 内不再派发睡觉任务, 让位主线推进。冷却必须
                // 加在触发条件层(同收炉侧线冷却 6eedb369), 否则任务放弃后条件仍
                // 成立会立即重建同一个任务, 绕过一切兜底。
                if (PlaceBedAndSetSpawnTask.isSleepGiveUpCooldown()) {
                    return null;
                }
                _nightBedFindInProgress = false;
                _skipNightBedUntilMs = 0;
                _nightBedGivenUp = false; // 找到床了, 解除"本夜放弃找床"
                setDebugState("Sleeping through night");
                boolean securedBed = mod.getItemStorage().hasItem(ItemHelper.BED) || isPlacedBedNearby(mod);
                if (securedBed) _bedInsuranceDone = true;
                return _sleepThroughNightTask;
            }
            // (2026-08-31 夜间找床/羊超时, 用户) 找不到床/羊: 先着手挖矿/收食物
            // (熬夜推进), 不无限找羊。超时(约30秒)后放弃 → return null 让 onTick
            // 继续。
            // (2026-08-31 放弃但顺路仍取, 用户"路过劫掠者羊毛堆不取不够智能")
            // 上一版"放弃一次就够, 白天前不再找床"回归: 一旦 `_nightBedGivenUp`,
            // `return null` 让整个夜晚的找床/羊毛/结构探索全部短路 —— bot 路过
            // 劫掠者羊毛堆/羊, 明明能就地做床睡觉, 却视而不见 (用户实测 23:22
            // 路过羊毛堆不取)。改为"放弃主动反复找, 但顺路机会仍取":
            //   - 刚主动找过头一轮仍照旧放弃(避免做床↔砍木横跳);
            //   - 放弃后按 _nightBedFindTimer 周期重新检查附近是否有【顺路机会】
            //     (近距离羊毛方块/已感知的床), 有则解除放弃回去拿, 既顺手又
            //     不会 30 秒一循环地反复重启找床打断砍木。
            // (2026-09-01 区块内有床/有羊就不超时, 用户"感知到床就走着却被倒计时掐断")
            // 旧逻辑 _nightBedFindTimer 到点(30s)就无条件 _nightBedGivenUp=true 放弃,
            // 即使 bot 正在走向已感知的床/羊/羊毛 (用户实测: 感知到小房子里的床/附近
            // 有羊, 却在走过去的路上被 30s 倒计时硬掐断, 去挖木头)。改为: 到点时先查
            // 区块内【有没有床/羊/就近羊毛机会】—— 有就重设计时器继续找, 不放弃;
            // 只有区块内确无床/羊/近羊毛才放弃 (熬夜推进挖矿收食物)。
            // (2026-09-17 "背包羊毛也算机会", 事故 20260917-143638) 上面所有"机会"口径
            // (nightBedOpportunityNearby) 都只看【世界】(32 格内床/羊毛方块/羊), 不看
            // 自己的背包 —— 3 羊毛已在手、只差 3 块木板时仍被判"无机会" → 放弃整夜。
            // 实测: 14:38:02 床链被囤木门控掐断, 14:38:07 计时器到期判"无机会"→给定放弃,
            // 而 t=1691 (14:38:15) 背包已是 3 羊毛 + 3 木板 (配方可满足) 却无人发起。
            // 现在: 背包里已有【同色 ≥3 羊毛】= 做床只差木头, 属于"手上的机会", 与
            // 世界里的机会同权 —— 不放弃、直接续做床链。
            if (_nightBedGivenUp) {
                if (nightBedOpportunityNearby(mod) || hasBedWoolInBag(mod)) {
                    _nightBedGivenUp = false;
                    _nightBedFindInProgress = true;
                    _nightBedFindTimer.reset();
                    setDebugState("Night bed opportunity nearby (sheep/wool/bed), grabbing");
                    return acquireBedTask(mod);
                }
                // (2026-09-01 同类噪音修复) 返回 null 的分支不设状态 — onTick 会继续
                // 派发真实任务并覆盖, 放弃窗口内每 tick 都刷屏, 掩盖真实横跳。
                return null;
            }
            if (!_nightBedFindInProgress) {
                _nightBedFindInProgress = true;
                _nightBedFindTimer.reset();
            } else if (_nightBedFindTimer.elapsed()) {
                if (nightBedOpportunityNearby(mod)) {
                    // 区块内确实还有床/羊/就近羊毛: 不能放弃, 重置计时器继续找/走过去。
                    _nightBedFindTimer.reset();
                    setDebugState("Still night bed opportunity (bed/sheep/wool nearby), continuing");
                } else if (hasBedWoolInBag(mod)) {
                    // (2026-09-17) 世界里的机会没了, 但羊毛已在背包: 不算"放弃找床",
                    // 而是"不必再找"—— 重设计时器, 继续推进做床链 (缺木头由床链自己挖)。
                    // 边沿打点(只落盘不进聊天, 遵循"长驻状态分支必须边沿触发"约定):
                    // 生效判据 = 出现 [BedWool]; 失败判据 = 背包 ≥3 同色羊毛仍出现
                    // "Returning to surface"/整夜无床链。
                    _nightBedFindTimer.reset();
                    if (!_bedWoolHoldLogged) {
                        _bedWoolHoldLogged = true;
                        Debug.logToFile("altoclef_diag.log",
                                "[BedWool] holding bed chain: >=3 same-colour wool in bag, skip night-give-up");
                    }
                    setDebugState("Still night bed opportunity (bed/sheep/wool nearby), continuing");
                } else {
                    // 区块内确无床/羊/近羊毛, 背包里也没有: 真放弃, 去挖矿/收食物熬过夜。
                    // (2026-09-01 同类噪音修复) 返回 null 的分支不设状态, 理由同上。
                    _nightBedFindInProgress = false;
                    _nightBedGivenUp = true;
                    return null;
                }
            }
            setDebugState("Getting a bed to sleep through the night.");
            return acquireBedTask(mod);
        } else {
            // 路3: 地表白天 → (2026-08-31 去掉"必须持床"强制阶段, 用户"任务切换不合理")
            // 不再为了"提前持有床"而在白天打断砍木/侧线任务去弄床 —— 实测该分支与
            // "Stockpiling wood" 反复横跳(砍木→做床→又砍木→又做床), 卡在图 Biased
            // exploration 找羊毛。床只在夜里需要睡觉时由路2 acquireBedTask 自然获取
            // (保留原来的床任务 = CollectBedTask 杀羊做床/拆床), 白天不强制先弄床。
            return null;
        }
    }

    /**
     * (2026-08-27 睡/床优先, 用户) 是否处于"主世界·夜间·可睡"优先时段。
     * 该时段应优先睡/弄床, 推迟收合成台等侧线任务, 避免把睡床流程打断
     * (用户实测: 睡觉↔收合成台横跳, 摘床动作被 "Pick up crafting table"
     * 打断没做完)。与睡眠路由 (onTick 内) 的判定保持一致。
     */
    private boolean isNightSleepPriority(AltoClef mod) {
        if (!_config.sleepThroughNight || _endPortalOpened) return false;
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) return false;
        if (_ranStrongholdLocator && !_locateStrongholdTask.isFinished(mod)) return false;
        if (mod.getPlayer() == null) return false;
        boolean inWater = mod.getPlayer().isTouchingWater();
        if (mod.getPlayer().getVehicle() != null) return false;
        // (2026-09-03 与 getNightSleepRouting L1982 口径一致, 用户"过河去床被 inWater
        // 否决横跳") 仅当无进行中睡觉任务时才因水让位; 睡觉途中过河不被掐断。
        // (2026-09-04 与 getNightSleepRouting 同步) 已进入夜间找床流程(_nightBedFindInProgress)
        // 时 inWater 不让位, 避免水陆交界 wood↔bed 横跳。
        if (inWater && !_sleepThroughNightTask.isActive() && !_nightBedFindInProgress) return false;
        boolean effectivelyDeep = WorldHelper.isDeepUnderground(mod);
        boolean belowSeaLevel = updateBelowSeaLevelDebounce(mod);
        if (effectivelyDeep || belowSeaLevel) return false;
        return WorldHelper.canSleep();
    }

    /**
     * (2026-09-05 低风险改善, 用户"有传送门且装备齐全要进地狱时跳过睡觉")
     * 传送门已建成(感知到下界门方块——门是 bot 自己建的, 就立在旁边, 扫描
     * 可见非透视) 且 装备达标(eyeGear 齐或钻石已超时放弃) → 马上要进下界,
     * 夜里不再睡觉/找床: 下界没有昼夜循环, 睡觉对主线纯属延误。
     */
    private boolean shouldSkipSleepForNether(AltoClef mod) {
        if (mod.getBlockTracker().getKnownLocations(Blocks.NETHER_PORTAL).isEmpty()) return false;
        boolean eyeGearSatisfied = StorageHelper.itemTargetsMet(mod, COLLECT_EYE_GEAR_MIN)
                && StorageHelper.isArmorEquippedAll(mod, COLLECT_EYE_ARMOR);
        return eyeGearSatisfied || _diamondCollectGivenUp;
    }

    /**
     * (2026-08-23 原木统计修正) 直接扫描玩家背包 (main 36 + offhand) 统计原木
     * 数量, 不依赖 ItemStorageTracker 缓存 (用户实测缓存统计为 0 导致
     * Restocking 误触发)。覆盖 ItemHelper.LOG 全部原木种类。
     *
     * (2026-09-01 光标/合成格口径补全, 根因: t=1491 合成木板任务把整组 29 根
     * 原木搬上光标/2x2 合成格, 本函数主背包读到 0 → 木头门控误判缺木 →
     * "Returning to surface to collect wood" 掐断合成链。与"材料进合成格不算
     * 缺失"的统一真实计数口径对齐: 光标 + 合成格(2x2/工作台3x3)里的原木同样
     * 是我们持有的原木, 不因搬运瞬间消失。)
     */
    private int countLogsInPlayerInventory(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getPlayer().getInventory() == null) return 0;
        int count = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack != null && !stack.isEmpty() && ArrayUtils.contains(ItemHelper.LOG, stack.getItem())) {
                count += stack.getCount();
            }
        }
        ItemStack offhand = mod.getPlayer().getInventory().offHand.get(0);
        if (offhand != null && !offhand.isEmpty() && ArrayUtils.contains(ItemHelper.LOG, offhand.getItem())) {
            count += offhand.getCount();
        }
        // 光标: 合成任务搬材料的一瞬间整组原木挂在光标上, 主背包瞬时读 0。
        ScreenHandler handler = mod.getPlayer().currentScreenHandler;
        if (handler != null) {
            ItemStack cursor = handler.getCursorStack();
            if (cursor != null && !cursor.isEmpty() && ArrayUtils.contains(ItemHelper.LOG, cursor.getItem())) {
                count += cursor.getCount();
            }
            // 合成格: 已放进 2x2(背包界面/无界面) 或 3x3(工作台界面) 等待合成的原木也算持有。
            for (Slot slot : handler.slots) {
                if (slot.inventory instanceof CraftingInventory) {
                    ItemStack stack = slot.getStack();
                    if (stack != null && !stack.isEmpty() && ArrayUtils.contains(ItemHelper.LOG, stack.getItem())) {
                        count += stack.getCount();
                    }
                }
            }
        }
        return count;
    }

    /**
     * Direct world scan for a bed block within a small radius of the player.
     * Unlike {@link #anyBedsFound}, this does NOT depend on the BlockTracker
     * cache (whose chunk scans lag behind reality) — used right after we
     * placed a bed, so the sleep logic doesn't think we're bedless and divert
     * to crafting a replacement.
     */
    private boolean isPlacedBedNearby(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        BlockPos pos = mod.getPlayer().getBlockPos();
        final int R = 6;
        for (int dy = -2; dy <= 2; ++dy) {
            for (int dz = -R; dz <= R; ++dz) {
                for (int dx = -R; dx <= R; ++dx) {
                    if (mod.getWorld().getBlockState(pos.add(dx, dy, dz)).getBlock() instanceof BedBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * (2026-08-23 belowSeaLevel 判定) 水下阈值 55 (用户设计): 只有潜到深度
     * <55 才视为"水下熬夜继续挖"。海平面 y=63 附近游泳 (62~64 波动) 不会
     * 触发, 从根上消除"睡觉/去地狱"横跳。去抖: y<55 须持续
     * BELOW_SEA_LEVEL_DEBOUNCE_TICKS tick 才算, 作阈值边缘的额外保险。
     */
    private static final int BELOW_SEA_LEVEL_Y = 55;

    private boolean updateBelowSeaLevelDebounce(AltoClef mod) {
        boolean currentlyBelow = mod.getPlayer() != null
                && mod.getPlayer().getBlockPos().getY() < BELOW_SEA_LEVEL_Y;
        if (currentlyBelow) {
            _belowSeaLevelTicks = Math.min(_belowSeaLevelTicks + 1, BELOW_SEA_LEVEL_DEBOUNCE_TICKS);
        } else {
            _belowSeaLevelTicks = 0;
        }
        return _belowSeaLevelTicks >= BELOW_SEA_LEVEL_DEBOUNCE_TICKS;
    }

    /**
     * 是否感知到床 (2026-08-21): 床已加入 TRACK_BLOCKS 全程追踪, 且
     * VisibilityHelper 对结构方块完全感知(无 FOV/距离限制)。感知到床
     * (如一墙之隔/远处村庄的床) = 睡觉时有床可睡 = 床保险成立。
     * 关键: 绝不因此去拆床(用户原则), 睡觉时 PlaceBedAndSetSpawnTask
     * 自己会走到床边睡。
     */
    private boolean isBedPerceived(AltoClef mod) {
        return mod.getBlockTracker().anyFound(ItemHelper.itemsToBlocks(ItemHelper.BED));
    }

    /**
     * (2026-08-31 夜间找床"顺路仍取", 用户 2026-09-01 收窄为近距离, 原"整区块/40格"
     * 太大, 会从远处追着去拿床打断砍木) 夜间是否【就近】就有做床/睡床的机会:
     * bot 附近 32 格内存在床方块、羊、或羊毛方块 —— 有就值得继续找床/走过去睡觉,
     * 避免 30 秒倒计时在 bot 正走向近处床/羊时硬掐断去挖木头 (用户 08:27 实测)。
     * 用"就近"而非"区块": 遥远(>32格)的床/羊不会反复把 bot 从砍木/挖矿拉走。
     */
    private boolean nightBedOpportunityNearby(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        final int NEAR = 32;
        Vec3d pos = mod.getPlayer().getPos();
        // 1) 附近 32 格内已感知/追踪到床方块
        Optional<BlockPos> nearestBed = mod.getBlockTracker().getNearestTracking(ItemHelper.itemsToBlocks(ItemHelper.BED));
        if (nearestBed.isPresent() && nearestBed.get().isWithinDistance(pos, NEAR)) return true;
        // 2) 附近 32 格内羊毛方块 (劫掠者塔/村庄羊毛堆, 就地能炼成床)
        Optional<BlockPos> nearestWool = mod.getBlockTracker().getNearestTracking(ItemHelper.itemsToBlocks(ItemHelper.WOOL));
        if (nearestWool.isPresent() && nearestWool.get().isWithinDistance(pos, NEAR)) return true;
        // 3) 附近 32 格内有羊 (有羊 = 现成的羊毛来源, 能原地杀羊做床)
        for (SheepEntity sheep : mod.getEntityTracker().getTrackedEntities(SheepEntity.class)) {
            if (sheep != null && sheep.isAlive() && sheep.getBlockPos().isWithinDistance(pos, NEAR)) return true;
        }
        return false;
    }

    /**
     * (2026-09-17 新增, 事故 20260917-143638) 背包里是否已有【同色 ≥3 羊毛】=
     * 做一张床只差木板。
     *
     * <p>{@link #nightBedOpportunityNearby} 判的是"世界里的机会"(附近床/羊毛方块/羊),
     * 全套都看不见【自己背包】。于是一旦因为任意一次打断(本次是囤木门控抢了正在供料的
     * 挖原木任务)触发 {@code _nightBedGivenUp}, 就算 3 羊毛捏在手里也会被锁一整夜 ——
     * 实测 14:38:07 判定"无机会"放弃, 14:38:15 背包已是 3 羊毛 + 3 木板(配方满足)
     * 却无人发起, 天亮前再无任何做床尝试。
     *
     * <p>用 {@code ItemHelper.WOOL} 16 色逐一计数, 要求【同色 ≥3】(床配方是 3 块同色羊毛,
     * 混色凑数做不出来, 不能放宽成"总羊毛数 ≥3")。计数走
     * {@link StorageHelper#countItemInInventoryRealIncludingCraftGrid} —— 与全项目"材料进
     * 合成格/光标不算缺失"的统一口径一致 (床配方任务可能正把羊毛摆在 3x3 格上)。
     */
    private boolean hasBedWoolInBag(AltoClef mod) {
        for (Item wool : ItemHelper.WOOL) {
            if (StorageHelper.countItemInInventoryRealIncludingCraftGrid(mod, wool) >= 3) return true;
        }
        return false;
    }

    /**
     * [2026-08-21 睡完收床] 记忆的自放床位置 (可能是 head 或 foot 半格)
     * 处是否仍有床。返回实际床位置, 没有则 null (记忆作废, 由调用方清空)。
     */
    private BlockPos selfBedStillThere(AltoClef mod, BlockPos remembered) {
        if (mod.getWorld().getBlockState(remembered).getBlock() instanceof BedBlock) {
            return remembered;
        }
        // 床占两格: 记录点可能是另一半
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos neighbor = remembered.offset(dir);
            if (mod.getWorld().getBlockState(neighbor).getBlock() instanceof BedBlock) {
                return neighbor;
            }
        }
        return null;
    }

    /**
     * [2026-08-22 收床防丢 / 2026-09-11 世界直扫兜底] 记忆床位置附近 (水平 6 格 ×
     * 垂直 32 格) 是否躺着床掉落物 —— 刚撸掉/被炸毁的床。有则先捡
     * (PickupDroppedItemTask), 没有才进清记忆宽限 (scheduleForgetSelfBed)。
     *
     * (2026-09-11) 旧实现查 EntityTracker.getClosestItemDrop, 而 _itemDropLocations
     * 只收"已落地"掉落物 (EntityTracker:403: isOnGround || isTouchingWater || 下方
     * 2–3 格实心) —— 方块被挖掉的同一 tick 才生成的掉落物必然不在缓存 → 恒返回
     * false → 调用方清掉床记忆 (事故 20260911-185745 实锤, 参见 _bedRecoverTask
     * 字段注释)。改为直扫世界 (含下落中/未落地), 与
     * PickupDroppedItemTask.findClosestDropByScan (2026-08-26) 同思路。
     * 判定盒用"水平 6 × 垂直 32"而非球半径: 床被挖后掉落物主要沿竖直方向坠落
     * (尤其从树上/高处掉下来的场景), 水平几乎不漂移; 纵向放宽才不会漏掉落差大的。
     */
    private boolean bedDropNearby(AltoClef mod, BlockPos bedPos) {
        if (bedPos == null) return false;
        ItemTarget bedTarget = new ItemTarget(ItemHelper.BED, 1);
        for (Entity e : mod.getWorld().getEntities()) {
            if (!(e instanceof ItemEntity ie) || !ie.isAlive()) continue;
            if (!bedTarget.matches(ie.getStack().getItem())) continue;
            BlockPos dp = ie.getBlockPos();
            if (Math.abs(dp.getX() - bedPos.getX()) <= 6
                    && Math.abs(dp.getZ() - bedPos.getZ()) <= 6
                    && Math.abs(dp.getY() - bedPos.getY()) <= 32) {
                return true;
            }
        }
        return false;
    }

    /**
     * (2026-09-11 清记忆宽限) 床方块已不在、且附近也扫不到床掉落物时调用 ——
     * 不立刻 setLastSelfPlacedBed(null)。掉落物可能还在下落, 甚至还没同步进
     * 客户端实体表 (方块更新包与实体生成包不保证同 tick 送达), 旧实现会在方块
     * 变空气的同一 tick 永久清掉记忆 (事故 20260911-185745)。保留记忆直到宽限
     * 窗口 BED_MEMORY_GRACE_MS 走完才判"真丢了"。
     * 幂等: 同一 tick 内多处调用只会起一次计时, 不会提前触发清除。
     */
    private void scheduleForgetSelfBed(AltoClef mod, BlockPos bedPos) {
        long now = System.currentTimeMillis();
        if (_bedMissingSinceMs == 0) {
            _bedMissingSinceMs = now;
            return;
        }
        if (now - _bedMissingSinceMs > BED_MEMORY_GRACE_MS) {
            Debug.logMessage("[BedPickup] bed at " + bedPos.toShortString()
                    + " gone + no bed drop for " + (BED_MEMORY_GRACE_MS / 1000)
                    + "s, forgetting self placed bed");
            mod.setLastSelfPlacedBed(null);
            _bedMissingSinceMs = 0;
        }
    }

    private BlockPos doSimpleSearchForEndPortal(AltoClef mod) {
        // (2026-09-04 人类式感知) 原实现直接数 BlockTracker 缓存里的门框
        // (getKnownLocations, 隔墙也知道) —— 典型透视。改为 getPerceivedLocations:
        // 只数"亲眼看见"(LOS+FOV, 近距豁免 6 格) 的门框。
        // ≥12 个 → 按原逻辑取平均得门中心;
        // ≥3 个但还没看全 → 先返回已见门框的重心, 人类行为: 看到几个门框就
        // 知道门厅就在那, 走过去自然看全 (近距豁免生效后凑满 12, 中心收敛)。
        List<BlockPos> frames = mod.getBlockTracker().getPerceivedLocations(Blocks.END_PORTAL_FRAME);
        if (frames.size() >= 3) {
            // Get the center of the (seen) frames.
            Vec3d average = frames.stream()
                    .reduce(Vec3d.ZERO, (accum, bpos) -> accum.add(bpos.getX() + 0.5, bpos.getY() + 0.5, bpos.getZ() + 0.5), Vec3d::add)
                    .multiply(1.0f / frames.size());
            return new BlockPos(average.x, average.y, average.z);
        }
        return null;
    }

    private static boolean isEndPortalFrameFilled(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos))
            return false;
        BlockState state = mod.getWorld().getBlockState(pos);
        if (state.getBlock() != Blocks.END_PORTAL_FRAME) {
            Debug.logWarning("BLOCK POS " + pos + " DOES NOT CONTAIN END PORTAL FRAME! This is probably due to a bug/incorrect assumption.");
            return false;
        }
        return state.get(EndPortalFrameBlock.EYE);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return MinecraftClient.getInstance().currentScreen instanceof CreditsScreen;
    }

    // Just a helpful utility to reduce reuse recycle.
    private static boolean shouldForce(AltoClef mod, Task task) {
        return task != null && task.isActive() && !task.isFinished(mod);
    }

    /**
     * (2026-09-03 钻石时间上限, 用户确认以 5 分钟为准, 常量 DIAMOND_COLLECT_LIMIT_MS=5min)
     * 每 tick 钻石收集活跃时调用: 用墙上时钟累加两次调用间的真实时长 (被打断去
     * 食物/木柴等时不调用, 天然暂停), 达到 DIAMOND_COLLECT_LIMIT_MS 后永久
     * give up 并打印累计时间。打印节流: 放弃时打一次, 平时每 30s 打一次累计进度。
     */
    private void recordDiamondCollectActive(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (_diamondCollectSegmentStartMs == -1) {
            _diamondCollectSegmentStartMs = now;
            return;
        }
        _diamondCollectCumulativeMs += now - _diamondCollectSegmentStartMs;
        _diamondCollectSegmentStartMs = now;
        if (_diamondCollectCumulativeMs >= DIAMOND_COLLECT_LIMIT_MS && !_diamondCollectGivenUp) {
            _diamondCollectGivenUp = true;
            Debug.logMessage("(DiamondCollect) cumulative " + (_diamondCollectCumulativeMs / 1000)
                    + "s >= 5min: giving up diamond gear, proceeding forward.");
            Debug.logToFile("altoclef_diag.log", "[DiamondCollect] GAVE UP, cumulative="
                    + _diamondCollectCumulativeMs + "ms");
        } else if (now - _lastDiamondGiveUpLogMs > 30000) {
            _lastDiamondGiveUpLogMs = now;
            Debug.logToFile("altoclef_diag.log", "[DiamondCollect] cumulative active="
                    + (_diamondCollectCumulativeMs / 1000) + "s so far (limit 300s)");
        }
    }

    /**
     * (2026-09-04 钻石放弃后进下界前补金靴) 只在钻石 give up 分支调用:
     * 已穿金靴 → null (放行 _goToNetherTask); 背包有 → EquipArmorTask 穿上;
     * 没有 → 收集合成 (粘性实例: CataloguedResourceTask 每 tick 新建会重启
     * 收集链, 用户实测教训)。金靴只耗 4 金锭, 与钻石件解耦, 钻石保持放弃态。
     */
    private Task getPreNetherGoldenBootsTask(AltoClef mod) {
        if (StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS)) {
            _preNetherGoldenBootsTask = null;
            return null;
        }
        if (mod.getItemStorage().hasItemInventoryOnly(Items.GOLDEN_BOOTS)) {
            setDebugState("Equipping golden boots before Nether (diamond gear given up)");
            return new EquipArmorTask(Items.GOLDEN_BOOTS);
        }
        // (2026-09-12 金靴独立超时) 已顺延到下游 → 不再阻塞, 放行 _goToNetherTask。
        if (_preNetherBootsDeferredToNether) {
            return null;
        }
        if (_preNetherBootsStartMs < 0) {
            _preNetherBootsStartMs = System.currentTimeMillis();
        }
        if (System.currentTimeMillis() - _preNetherBootsStartMs > PRE_NETHER_BOOTS_TIMEOUT_MS) {
            _preNetherBootsDeferredToNether = true;
            Debug.logMessage("(DiamondGiveUp) Golden boots unobtainable in overworld within "
                    + (PRE_NETHER_BOOTS_TIMEOUT_MS / 60_000) + "min — deferring to Nether (mine nether gold ore).");
            Debug.logToFile("altoclef_diag.log", "[PreNetherBoots] OVERWORLD TIMEOUT "
                    + PRE_NETHER_BOOTS_TIMEOUT_MS + "ms, deferred to Nether.");
            return null;
        }
        if (_preNetherGoldenBootsTask == null || _preNetherGoldenBootsTask.isFinished(mod)) {
            Debug.logMessage("(DiamondGiveUp) Golden boots missing before Nether — crafting (4 gold ingots), diamond gear stays abandoned.");
            _preNetherGoldenBootsTask = TaskCatalogue.getItemTask(Items.GOLDEN_BOOTS, 1);
        }
        setDebugState("Crafting golden boots before Nether (diamond gear given up)");
        return _preNetherGoldenBootsTask;
    }

    /**
     * (2026-09-12 金靴下界补链) 主世界补金靴超时被顺延到下界时, 在 NETHER case 调用。
     * 下界金矿 NETHER_GOLD_ORE 分布 y=10~117 均匀 (10 脉/区块, 玄武岩三角洲 ×2),
     * 掉 2~6 金粒/块, 4 金锭 = 36 金粒, 远好凑过主世界 y=-64~32 的金矿隧道。
     * CollectGoldIngotTask 的 NETHER 分支会先烧背包 raw_gold, 再金粒合成金锭, 最后
     * 挖下界金矿 — 不会像"熔炼缺料"那样回主世界造地狱门。
     * 二次窗口 NETHER_BOOTS_TIMEOUT_MS 用尽后彻底放弃, 不再阻塞烈焰棒主线。
     * 只补金靴不碰钻石件 (钻石保持 _diamondCollectGivenUp 放弃态)。
     */
    private Task getNetherGoldenBootsTask(AltoClef mod) {
        if (!_preNetherBootsDeferredToNether || _netherBootsGivenUp) {
            return null;
        }
        if (StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS)) {
            _netherBootsGivenUp = true;
            return null;
        }
        if (mod.getItemStorage().hasItemInventoryOnly(Items.GOLDEN_BOOTS)) {
            setDebugState("Equipping golden boots (deferred, Nether)");
            return new EquipArmorTask(Items.GOLDEN_BOOTS);
        }
        if (_netherBootsStartMs < 0) {
            _netherBootsStartMs = System.currentTimeMillis();
            // 丢弃主世界残留的收集实例, 让链在下界重建 (走 NETHER 分支)。
            _preNetherGoldenBootsTask = null;
        }
        if (System.currentTimeMillis() - _netherBootsStartMs > NETHER_BOOTS_TIMEOUT_MS) {
            _netherBootsGivenUp = true;
            Debug.logMessage("(NetherBoots) Still no golden boots after "
                    + (NETHER_BOOTS_TIMEOUT_MS / 60_000) + "min in Nether — giving up, proceeding with blaze rods.");
            Debug.logToFile("altoclef_diag.log", "[NetherBoots] NETHER TIMEOUT "
                    + NETHER_BOOTS_TIMEOUT_MS + "ms, giving up.");
            return null;
        }
        if (_preNetherGoldenBootsTask == null || _preNetherGoldenBootsTask.isFinished(mod)) {
            Debug.logMessage("(NetherBoots) Golden boots deferred from overworld — collecting from nether gold ore.");
            _preNetherGoldenBootsTask = TaskCatalogue.getItemTask(Items.GOLDEN_BOOTS, 1);
        }
        setDebugState("Crafting golden boots in Nether (deferred from overworld)");
        return _preNetherGoldenBootsTask;
    }

    private static ItemTarget[] toItemTargets(Item ...items) {
        return Arrays.stream(items).map(item -> new ItemTarget(item, 1)).toArray(ItemTarget[]::new);
    }
    private static ItemTarget[] toItemTargets(Item item, int count) {
        return new ItemTarget[] {new ItemTarget(item, count)};
    }
    private static ItemTarget[] combine(ItemTarget[] ...targets) {
        List<ItemTarget> result = new ArrayList<>();
        for (ItemTarget[] ts : targets) {
            result.addAll(Arrays.asList(ts));
        }
        return result.toArray(ItemTarget[]::new);
    }
}