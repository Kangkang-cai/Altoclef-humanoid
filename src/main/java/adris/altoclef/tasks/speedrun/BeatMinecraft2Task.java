package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.DoStuffInContainerTask;
import adris.altoclef.tasks.container.EmptyFurnaceTask;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.LootDesertTempleTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.resources.*;
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
import net.minecraft.entity.mob.SilverfishEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
    }, ItemHelper.itemsToBlocks(ItemHelper.BED));
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
            toItemTargets(Items.DIAMOND_PICKAXE, 3),
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

    /**
     * (2026-08-30 铁套保护, 用户 "获得工具后再做一个铁套保护自己, 不然太容易死")
     * 铁甲四件是否全部【穿在身上】。只有穿上才算完成, 保证收集到的铁甲会被
     * EquipArmorTask 实际装备, 而不只是躺在背包里。
     */
    private static boolean ironArmorMet(AltoClef mod) {
        return StorageHelper.isArmorEquippedAll(mod, ItemHelper.IRON_ARMORS);
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
    private LootContainerTask _nearbySweepTask = null;
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
    // (2026-08-31 收熔炉取空) 收熔炉前的 EmptyFurnaceTask 粘性实例: 一旦派发就
    // 粘住直到完成(取尽炉内物品+拆炉带走), 防止每 tick 新建被其他分支切走,
    // 也确保 onTick 顶部延续块能持续返回同一实例。
    private EmptyFurnaceTask _emptyFurnaceTask = null;
    // (2026-08-27 床保险一次性完成) 地表白天"必须持床"只执行一轮: 拿到并持有
    // 一张床(或睡过一张床已设重生点) 就置 true 放行, 之后不再因"背包没床"打断
    // 木头/食物/挖矿主线 (否则每次回地表都被拽去拿床)。与 _woodStockpiled 同构。
    private boolean _bedInsuranceDone = false;
    // (2026-08-27 收床来源距离) 感知到的现成床在 96 格内才算"可挖去拿"——
    // 超过则走杀羊/羊毛→做床合成分支, 不为一张床长途折返。
    private static final double BED_GRAB_RANGE = 96.0;
    private final Task _getOneBedTask = TaskCatalogue.getItemTask("bed", 1);
    private final Task _sleepThroughNightTask = new SleepThroughNightTask();
    private final Task _killDragonBedStratsTask = new KillEnderDragonWithBedsTask(new WaitForDragonAndPearlTask());

    // (2026-08-22) isDeepUnderground 去抖: 洞口/峡谷边缘头顶方块判定每
    // 几 tick 翻转 (y<58 硬阈值 + 头顶 24 格扫描), bed↔GoToSurface 分支
    // 横跳 (tasks.log 11:22:32 t=1724-1796 每 2 tick 切换)。双稳态: 状态
    // 翻转需连续 2 秒新值才生效。
    private final TimerGame _surfaceFlipTimer = new TimerGame(2);
    private boolean _stableUnderground = false;
    // (2026-08-22 贪心收集) 顺手挖眼前可见矿石的粘性任务实例 + 触发
    // 节流 (无活可干时每 3 秒才重扫一次, 防 onTick 每 tick 全量扫描)。
    private CollectVisibleOresTask _greedyOreTask = null;
    // (2026-08-28 感知延迟 3→1, 用户) 贪心矿检查冷却降为 1 游戏秒, 见矿→开始
    // 响应从 ~1.5s 平均(最坏3s) 缩短到 ~0.5s 平均(最坏1s)。
    private final TimerGame _greedyOreCheckCooldown = new TimerGame(1);
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
    // 超时), 不再重试。外层超时 (13 分钟) 比内层 (12 分钟) 长, 内层先放弃
    // 外层兜底。
    private boolean _fireResPhaseActive = false;
    private boolean _fireResPhaseDone = false;
    private final TimerGame _fireResPhaseTimer = new TimerGame(13 * 60);

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
        _lastDispatchedChildDesc = state;
        long nowMs = System.currentTimeMillis();
        if (nowMs - _lastFlowLogMs < 200) return; // 节流
        _lastFlowLogMs = nowMs;
        if (_modRef == null || !AltoClef.inGame()) return;
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
            Debug.logMessage(snap);
            Debug.logToFile("altoclef_diag.log", snap);
        } catch (Exception ignored) {
            // 诊断日志绝不影响主逻辑
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
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.ENDER_EYE, Items.BLAZE_ROD, Items.ENDER_PEARL, Items.CRAFTING_TABLE);
        mod.getBehaviour().addProtectedItems(ItemHelper.BED);
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

        // (2026-08-27 白天优先收床) 错过了夜(没睡成)→ 我方自放床会滞留世界没人拆,
        // 随后被"收合成台/装备/下矿"等分支抢走, 床永久留在原地 (用户实测: 放床后
        // 早晨到, canSleep=false, going to bed 被 iron gear 抢走, 睡完/错过夜床
        // 都没拆)。这里在一切侧线任务之前回收: 自放床还在→拆掉; 只剩掉落物→捡起;
        // 都没了→清记忆。是否睡过都照收 (speedrunner 带床走)。
        {
            BlockPos strBed = mod.getLastSelfPlacedBed();
            if (strBed != null && !_ranStrongholdLocator
                    && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
                BlockPos still = selfBedStillThere(mod, strBed);
                if (still != null) {
                    if (still.isWithinDistance(mod.getPlayer().getPos(), 8)) {
                        setDebugState("Picking up my placed bed (recover).");
                        return new DestroyBlockTask(still);
                    }
                } else if (bedDropNearby(mod, strBed)) {
                    setDebugState("Picking up my bed drop (recover).");
                    return new PickupDroppedItemTask(new ItemTarget[]{new ItemTarget("bed", 1)}, true);
                } else {
                    mod.setLastSelfPlacedBed(null);
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
                _emptyFurnaceTask = null;
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
                // (2026-08-27 睡/床优先) 主世界夜间可睡时推迟收合成台 — 否则
                // 收合成台分支优先于睡眠分支, 反复把睡/弄床流程打断 (用户实测:
                // 摘床动作被 "Pick up crafting table" 打断没做完)。夜里先睡/弄床。
                && !isNightSleepPriority(mod)
                // (2026-08-30 木头优先, 用户"挖木头反复横跳") 收合成台只是"顺路",
                // 不盖过进行中的原木收集。若 _woodStockpileTask 还在跑就放行木头,
                // 否则 tableNearby/dropped 一闪烁就和木头任务互踩 (日志: 每2s
                // Stockpiling wood <-> Pick up crafting table 互跳)。
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
                return new MineAndCollectTask(Items.CRAFTING_TABLE, 1, new Block[]{Blocks.CRAFTING_TABLE}, MiningRequirement.HAND);
            }
        }

        // (2026-08-27 熔炉即收) 烧完东西后把自放/近处的熔炉带走 — 与合成台同款
        // 站桩分支。条件: 主世界 / 门未开 / 配置开 / 背包无熔炉 / 当前不在用熔炉 /
        // (2026-08-31) 主链忙时让位 / 非夜间睡/床优先时段。检测 16 格内是否有熔炉
        // ("顺手拿"), 有则先取尽炉内内容物再拆走。
        if (!_endPortalOpened && WorldHelper.getCurrentDimension() != Dimension.END && _config.rePickupFurnace
                && !mod.getItemStorage().hasItem(Items.FURNACE)
                // (2026-08-31 顺路收容不让位打断主线) 主链(铁装合成/下矿/食物/熔炼)
                // 忙时让位 — 否则熔炼铁锭到一半会被收熔炉拦腰打断 (用户 12:42 日志)。
                && !isMainLineBusy(mod)
                && !isNightSleepPriority(mod)) {
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
                return new MineAndCollectTask(Items.FURNACE, 1, new Block[]{Blocks.FURNACE}, MiningRequirement.HAND);
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
                && !_ranStrongholdLocator) {
            BlockPos bedAt = selfBedStillThere(mod, selfBed);
            if (bedAt == null) {
                // 床方块没了 (刚撸掉/被毁/放置失败)。若掉落物还躺在附近,
                // 先捡起来再清记忆 — 否则床永远丢失, 下晚被迫重新合床。
                if (bedDropNearby(mod, selfBed)) {
                    setDebugState("Picking up our bed drop.");
                    return new PickupDroppedItemTask(new ItemTarget[]{new ItemTarget("bed", 1)}, true);
                }
                mod.setLastSelfPlacedBed(null);
            } else if (bedAt.isWithinDistance(mod.getPlayer().getPos(), 8)) {
                setDebugState("Picking up our placed bed.");
                return new DestroyBlockTask(bedAt);
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
                    Item[] diamondOrder = {Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET};
                    int[] diamondCosts = {8, 7, 5};
                    for (int i = 0; i < diamondOrder.length; i++) {
                        Item piece = diamondOrder[i];
                        boolean owned = mod.getItemStorage().hasItem(piece) || StorageHelper.isArmorEquipped(mod, piece);
                        if (!owned && mod.getItemStorage().getItemCount(Items.DIAMOND) >= diamondCosts[i]) {
                            setDebugState("Crafting diamond gear immediately: " + ItemHelper.stripItemName(piece));
                            return TaskCatalogue.getItemTask(piece, 1);
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
                            _greedyOreTask = new CollectVisibleOresTask();
                            setDebugState("Greedy mining visible ores");
                            _greedyOreCheckCooldown.reset();
                            return _greedyOreTask;
                        } else {
                            _greedyOreTask = null;
                            _greedyOreCheckCooldown.reset();
                        }
                    }
                }
                if (shouldForce(mod, _gearTask) && !StorageHelper.isArmorEquippedAll(mod, COLLECT_EYE_ARMOR)) {
                    setDebugState("Getting gear for Ender Eye journey");
                    return _gearTask;
                }
                if (shouldForce(mod, _foodTask)) {
                    setDebugState("Getting Food for Ender Eye journey");
                    return _foodTask;
                }
                // Smelt remaining raw food
                if (_config.alwaysCookRawFood) {
                    for (Item raw : ItemHelper.RAW_FOODS) {
                        if (mod.getItemStorage().hasItem(raw)) {
                            Optional<Item> cooked = ItemHelper.getCookedFood(raw);
                            if (cooked.isPresent()) {
                                int targetCount = mod.getItemStorage().getItemCount(cooked.get()) + mod.getItemStorage().getItemCount(raw);
                                setDebugState("Smelting raw food: " + ItemHelper.stripItemName(raw));
                                return new SmeltInFurnaceTask(new SmeltTarget(new ItemTarget(cooked.get(), targetCount), new ItemTarget(raw, targetCount)));
                            }
                        }
                    }
                }

                // NEARBY LOOT SWEEP (2026-08-21 就近快速收集):
                // 附近有未搜刮的结构箱子(村庄/矿道遗迹/神殿)时优先拿空,
                // 再去跑远路收集(木头等)。派发即标记 _sweptContainers,
                // 即使该箱子开不出来也不会死循环。
                // 2026-08-21 rev2: 候选必须 isBlockPerceivable — getKnownLocations
                // 是裸缓存(区块扫描透视岩层), 不过滤会把地底刷怪笼遗迹的
                // 箱子也算"就近可拿", bot 丢下正事去挖 40 格岩层。感知规则
                // (VisibilityHelper): 视线可达, 或在"已见过的结构"推断半径内
                // (村庄屋内箱子), 地下未见的箱子不再触发。
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

                // 床持床保险已上移到 onTick 睡觉分支的三路互斥逻辑 (2026-08-27):
                // 地下睡觉与收床都跳过; 地表白天只在 _bedInsuranceDone==false 且背包无床
                // 时才 acquireBedTask(96格内现床/合成分支); 地表夜间优先去睡。此处不再重复
                // 拦截 — 深地下挖矿时绝不因"背包没床"派床任务 (原逻辑曾导致挖矿时拆床横跳)。

                // WOOD STOCKPILE: wood is the backbone resource (tools, fuel, torches,
                // crafting). 木头强前置逻辑已抽到 getWoodPerfusionTask() 并在本 case
                // 最前面门控调用 — 木头未攒够时烧肉/挖矿/穿护甲/箱子全部让位, 避免
                // "烧生肉→做木板燃料→消耗原木→返回来砍木"反复循环 (2026-09-01 用户
                // 实测 t=414 挖石被 Stockpiling wood 打断, 圆石需 8 个永远集不齐)。

                // FOOD FIRST (先于挖矿) — 强门槛。 (2026-08-31 rev, 用户再次收紧)
                // 进入下矿(挖生铁)/熔炼铁锭之前, 食物必须攒够 settings.foodUnitsToCollect
                // (用户实配 ~220) 才允许放行。旧版一度降为 minFoodUnits(=20, "一点点应急")
                // 就放行, 实测食物完全没收集就一头钻进铁装/熔炉链, 熔炉里还烧着没取走的
                // 肉就被连炉拖走 (用户 12:42 日志)。食物链仍排在 mining 之前。
                int strongFoodUnits = mod.getModSettings().getFoodUnitsToCollect();
                if (strongFoodUnits <= 0) strongFoodUnits = _config.foodUnits; // settings 未配时兜底强门槛
                if (StorageHelper.calculateInventoryFoodScore(mod) < strongFoodUnits) {
                    if (isUndergroundDebounced(mod) || isInPit(mod)) {
                        setDebugState("Returning to surface to collect food.");
                        return new GoToSurfaceTask();
                    }
                    _foodTask = new CollectFoodTask(strongFoodUnits);
                    diagIronPhase(mod, "collect food (strong gate)",
                            "foodScore=" + StorageHelper.calculateInventoryFoodScore(mod)
                                    + " strongFoodUnits=" + strongFoodUnits
                                    + " underground=" + isUndergroundDebounced(mod) + " inPit=" + isInPit(mod));
                    Debug.logToFile("altoclef_diag.log",
                            "[MAIN-FIX]\t[FoodGate]\tstrong-gate food=" + StorageHelper.calculateInventoryFoodScore(mod)
                                    + "/" + strongFoodUnits + " -> CollectFoodTask before mining/smelting");
                    return _foodTask;
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
                    return _gearTask;
                }

                // (2026-08-30 铁套保护, 用户) 工具 (铁剑/镐/盾) 就绪后、进钻石前,
                // 先做一套铁甲穿上, 避免太容易死。先穿已持有未装备的铁甲, 缺件再
                // 收集; 只有四件全穿身上 (ironArmorMet) 才结束本阶段。
                if (!ironArmorMet(mod) && !eyeGearSatisfied) {
                    diagIronPhase(mod, "make iron armor set",
                            "ironArmorMet=" + ironArmorMet(mod)
                                    + " eyeGearSatisfied=" + eyeGearSatisfied);
                    Item[] ownedNotEquipped = Arrays.stream(ItemHelper.IRON_ARMORS)
                            .filter(a -> mod.getItemStorage().hasItem(a) && !StorageHelper.isArmorEquipped(mod, a))
                            .toArray(Item[]::new);
                    if (ownedNotEquipped.length > 0) {
                        setDebugState("Equipping owned iron armor");
                        return new EquipArmorTask(ownedNotEquipped);
                    }
                    ItemTarget[] missingIronArmor = Arrays.stream(ItemHelper.IRON_ARMORS)
                            .filter(a -> !mod.getItemStorage().hasItem(a) && !StorageHelper.isArmorEquipped(mod, a))
                            .map(a -> new ItemTarget(a, 1))
                            .toArray(ItemTarget[]::new);
                    setDebugState("Making iron armor set before mining");
                    return TaskCatalogue.getSquashedItemTask(missingIronArmor);
                }

                // Then get diamond
                if (!eyeGearSatisfied) {
                    _gearTask = TaskCatalogue.getSquashedItemTask(Stream.concat(Arrays.stream(COLLECT_EYE_ARMOR).filter(item -> !mod.getItemStorage().hasItem(item) && !StorageHelper.isArmorEquipped(mod, item)).map(item -> new ItemTarget(item, 1)), Arrays.stream(COLLECT_EYE_GEAR)).toArray(ItemTarget[]::new));
                    return _gearTask;
                }
                // Then go to the nether.
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
                if (needsBlazeRods) {
                    // (2026-08-23 防火药水保命) 打烈焰人前先搞 ≥1 瓶防火药水
                    // (用户设计: 下界挖金 → 猪灵交易 → 打烈焰人时喝)。只尝试
                    // 一轮: 达标或超时 (12 分钟) 后不再重试, 不阻塞主线。
                    // (2026-08-23 rev2 用户降低要求: 只需 1 瓶; 已检测到有 1 瓶
                    // 防火药水就直接去打烈焰人, 不再进交易阶段。)
                    if (!_fireResPhaseDone) {
                        if (!_fireResPhaseActive) {
                            if (ItemHelper.countFireResPotions(mod) < 1) {
                                _fireResPhaseActive = true;
                                _fireResPhaseTimer.reset();
                            } else {
                                _fireResPhaseDone = true;
                            }
                        }
                        if (_fireResPhaseActive) {
                            if (ItemHelper.countFireResPotions(mod) >= 1) {
                                Debug.logMessage("(FireRes) Target reached: 1 fire resistance potion. Proceeding to blaze rods.");
                                _fireResPhaseActive = false;
                                _fireResPhaseDone = true;
                            } else if (_fireResPhaseTimer.elapsed()) {
                                Debug.logMessage("(FireRes) Phase timed out with " + ItemHelper.countFireResPotions(mod)
                                        + " potions. Proceeding to blaze rods anyway.");
                                Debug.logToFile("altoclef_diag.log", "[FireRes] outer phase TIMEOUT, potions="
                                        + ItemHelper.countFireResPotions(mod));
                                _fireResPhaseActive = false;
                                _fireResPhaseDone = true;
                            } else {
                                setDebugState("Getting fire resistance potions (gold + piglin trading)");
                                return new CollectFireResPotionsTask(1);
                            }
                        }
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
        return thisOrChildSatisfies(task ->
                task instanceof SmeltInFurnaceTask                      // 熔炼
                        || task instanceof CollectFoodTask               // 找食物
                        || task instanceof MineAndCollectTask            // 挖矿/收矿
                        || task instanceof CollectVisibleOresTask        // 贪心收矿
                        || task instanceof CraftInInventoryTask          // 铁装等 2x2 合成
                        || task instanceof CraftInTableTask              // 铁装等 3x3 合成
                        || (task instanceof DoStuffInContainerTask cont
                            && (cont.getContainerTarget().matches(Items.FURNACE)
                                || cont.getContainerTarget().matches(Items.CRAFTING_TABLE))) // 用熔炉/工作台
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

    // (2026-08-27 熔炉即收) 与 findNearbyCraftingTable 同款: 16 格内是否有可用熔炉。
    private boolean findNearbyFurnace(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos pos : mod.getBlockTracker().getKnownLocations(Blocks.FURNACE)) {
            if (pos.isWithinDistance(player, FURNACE_GRAB_RANGE)
                    && mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE) {
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
                            && WorldHelper.canBreak(mod, pos)) {
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
        // (2026-08-22) 地表资源前置: 树只长在地表。深处矿洞/水下时先挖回
        // 地表再收集 — Baritone 垂直挖穿几十格岩层耗时数分钟, 若直接派收集
        // 任务会被进度检查误判"不可达"而"挖一半就放弃换目标"。
        if ((!_woodStockpiled && logCount < _config.minWoodLogs)
                || (_woodStockpiled && logCount < _config.woodRestockThreshold)) {
            // (2026-08-27 原木↔回表面横跳修复) 已有进行中的原木收集任务时
            // 别再用"回表面"反复打断 (导航路线若中途低于表面会触发横跳)。
            boolean woodTaskActive = _woodStockpileTask != null && !_woodStockpileTask.isFinished(mod);
            if (!woodTaskActive && (isUndergroundDebounced(mod) || isInPit(mod))) {
                setDebugState("Returning to surface to collect wood.");
                return new GoToSurfaceTask();
            }
        }
        if (!_woodStockpiled) {
            if (logCount >= _config.minWoodLogs) {
                _woodStockpiled = true;
                _woodStockpileTask = null;
            } else {
                setDebugState("Stockpiling wood (logs): " + logCount + "/" + _config.minWoodLogs);
                diagIronPhase(mod, "stockpile wood",
                        "stockpiled=" + _woodStockpiled
                                + " logCount=" + logCount
                                + " minWoodLogs=" + _config.minWoodLogs
                                + " woodTaskActive=" + (_woodStockpileTask != null && !_woodStockpileTask.isFinished(mod))
                                + " underground=" + isUndergroundDebounced(mod) + " inPit=" + isInPit(mod));
                // (2026-08-27 一次性粘性) 复用同一个任务实例, 不再每 tick new。
                if (_woodStockpileTask == null || _woodStockpileTask.isFinished(mod)) {
                    _woodStockpileTask = TaskCatalogue.getItemTask("log", _config.minWoodLogs);
                }
                return _woodStockpileTask;
            }
        } else if (logCount < _config.woodRestockThreshold) {
            setDebugState("Restocking wood (logs): " + logCount + "/" + _config.woodRestockThreshold);
            diagIronPhase(mod, "restock wood",
                    "logCount=" + logCount
                            + " restockThresh=" + _config.woodRestockThreshold
                            + " woodTaskActive=" + (_woodStockpileTask != null && !_woodStockpileTask.isFinished(mod)));
            if (_woodStockpileTask == null || _woodStockpileTask.isFinished(mod)) {
                _woodStockpileTask = TaskCatalogue.getItemTask("log", _config.minWoodLogs);
            }
            return _woodStockpileTask;
        }
        return null;
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
     * (2026-08-22) isDeepUnderground 的双稳态去抖版: 洞口边缘 (挖到
     * y=58+ 且头顶露天的瞬间) 判定会逐 tick 翻转, 曾导致 bed↔
     * GoToSurface 每 2 tick 横跳 (tasks.log t=1724-1796)。翻转需连续
     * 2 秒才生效。峡谷搭高 (getPitEscapeY) 不经过去抖 — 它只在
     * wood/food 前置分支用, 且壁顶判定本身稳定 (16 格采样)。
     */
    private boolean isUndergroundDebounced(AltoClef mod) {
        boolean raw = WorldHelper.isDeepUnderground(mod);
        if (raw == _stableUnderground) {
            _surfaceFlipTimer.reset();
            return _stableUnderground;
        }
        if (_surfaceFlipTimer.elapsed()) {
            _stableUnderground = raw;
            _surfaceFlipTimer.reset();
        }
        return _stableUnderground;
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
        boolean belowSeaLevel = updateBelowSeaLevelDebounce(mod);
        boolean inWater = mod.getPlayer() != null && mod.getPlayer().isTouchingWater();
        boolean ridingVehicle = mod.getPlayer() != null && mod.getPlayer().getVehicle() != null;
        boolean effectivelyDeep = WorldHelper.isDeepUnderground(mod) && !inWater;

        // 路1: 地下或水上 → 睡觉与收床都跳过。
        if (effectivelyDeep || belowSeaLevel || inWater || ridingVehicle) {
            setDebugState(inWater || ridingVehicle
                    ? "On water/boat: not sleeping."
                    : "Underground: working through the night.");
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
            }
        }
        boolean effectiveCanSleep = rawCanSleep || (_wasSleepableOnce && !_notSleepableTimer.elapsed());

        boolean hasBedRegard = mod.getItemStorage().hasItem(ItemHelper.BED)
                || isPlacedBedNearby(mod)
                || isBedPerceived(mod);

        if (effectiveCanSleep) {
            // 路2: 地表夜间 → 优先睡觉
            if (hasBedRegard) {
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
            if (_nightBedGivenUp) {
                if (nightBedOpportunityNearby(mod)) {
                    _nightBedGivenUp = false;
                    _nightBedFindInProgress = true;
                    _nightBedFindTimer.reset();
                    setDebugState("Night bed opportunity nearby (sheep/wool/bed), grabbing");
                    return acquireBedTask(mod);
                }
                setDebugState("Giving up on night bed for tonight, working through.");
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
                } else {
                    // 区块内确无床/羊/近羊毛: 真放弃, 去挖矿/收食物熬过夜。
                    _nightBedFindInProgress = false;
                    _nightBedGivenUp = true;
                    setDebugState("No bed/sheep after 30s — giving up night bed for tonight.");
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
        if (inWater) return false;
        boolean effectivelyDeep = WorldHelper.isDeepUnderground(mod);
        boolean belowSeaLevel = updateBelowSeaLevelDebounce(mod);
        if (effectivelyDeep || belowSeaLevel) return false;
        return WorldHelper.canSleep();
    }

    /**
     * (2026-08-23 原木统计修正) 直接扫描玩家背包 (main 36 + offhand) 统计原木
     * 数量, 不依赖 ItemStorageTracker 缓存 (用户实测缓存统计为 0 导致
     * Restocking 误触发)。覆盖 ItemHelper.LOG 全部原木种类。
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
     * [2026-08-22 收床防丢] 记忆床位置附近 (4 格) 是否躺着床掉落物 —
     * 刚撸掉/被炸毁的床。有则先捡 (PickupDroppedItemTask), 没有才清记忆。
     * 否则床永久丢失, 下晚被迫重新合床。
     */
    private boolean bedDropNearby(AltoClef mod, BlockPos bedPos) {
        ItemTarget bedTarget = new ItemTarget(ItemHelper.BED, 1);
        java.util.Optional<net.minecraft.entity.ItemEntity> drop =
                mod.getEntityTracker().getClosestItemDrop(WorldHelper.toVec3d(bedPos), bedTarget);
        return drop.isPresent() && drop.get().isAlive()
                && drop.get().getBlockPos().isWithinDistance(bedPos, 4);
    }

    private BlockPos doSimpleSearchForEndPortal(AltoClef mod) {
        List<BlockPos> frames = mod.getBlockTracker().getKnownLocations(Blocks.END_PORTAL_FRAME);
        if (frames.size() >= END_PORTAL_FRAME_COUNT) {
            // Get the center of the frames.
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