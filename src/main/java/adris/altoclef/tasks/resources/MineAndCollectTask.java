package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.BiasedExploreTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GoToSurfaceTask;
import adris.altoclef.tasks.movement.MineDownExploreTask;
import adris.altoclef.tasks.movement.NetherExpeditionTask;
import adris.altoclef.tasks.movement.NetherGoldExpeditionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ScaffoldBlocks;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.SurfaceHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.CursorSlot;
import baritone.api.utils.input.Input;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;

public class MineAndCollectTask extends ResourceTask {

    private final Block[] _blocksToMine;

    // (2026-09-07 探索断点交接, 用户"刚转去挖洞没一会又被同一个洞吸引去探索")
    // 最近一个仍在探索矿洞(_inCave)的 MineDownExploreTask。BeatMinecraft 重规划时
    // MineOrCollectTask 会按 isEqual 被重建, 其 _explorationTask 字段随对象丢失 →
    // 进行中的探索被换成全新实例, 探索进度作废、同洞反复吸引(16:0x 会话 11 次
    // FIRE/0 mark 的根因之一)。静态引用跨对象存活, createNextExplorationTask 认领。
    private static MineDownExploreTask _lastCaveExploration;

    // (2026-09-16 灵魂沙谷远征) 谷内挖金时缓存的远征实例: isEqual 是 identity,
    // 每 tick new 会无限拆树; static 跨链重建存活, isFinished(出谷) 后由下一轮
    // createNextExplorationTask 新建 (正常挖矿探索)。
    private static NetherExpeditionTask _soulSandExpedition;

    // (2026-09-25 挖金无进展远征, 用户"bot找金子长时间没进展我也希望触发远征模式")
    // 缓存的远征实例 (与 _soulSandExpedition 同款: isEqual 是 identity, static 跨
    // 链重建存活, isFinished 后由下次评估新建) + 金粒进展基线。基线只在闸门评估
    // 时采样 (感知不到矿才会走到闸门; 挖矿/捡取期间不评估, 不会误判):
    // 金粒数较上次评估有变化 (上涨=挖到金, 下降=被合成消耗) → 有进展, 计时重置;
    // 超过 GOLD_NO_PROGRESS_MILLIS 无变化 → 派 NetherGoldExpeditionTask 定向远征。
    private static NetherGoldExpeditionTask _goldNoProgressExpedition;
    private static int _goldNuggetBaseline = -1;
    private static long _lastGoldProgressMs = 0L;
    /** 背包金粒数多久无增长算"长时间没进展"。 */
    private static final long GOLD_NO_PROGRESS_MILLIS = 180_000L;

    private final MiningRequirement _requirement;

    private final TimerGame _cursorStackTimer = new TimerGame(3);
    // (2026-09-01 手挖修复) 镐装备核对用独立更快的节流 (0.5s), 不依赖光标/
    // PlayerInteractionFixChain 的 isBreakingBlock() 事件 — 有时不触发导致手挖。
    private final TimerGame _toolEquipTimer = new TimerGame(0.5);

    private final MineOrCollectTask _subtask;

    // (2026-08-28 贪心=纯贴身, 用户) 贪婪模式只靠近场(3x3x3)扫描挖紧贴的矿,
    // 不走任何 24 格缓存/世界扫描/仅FOV 的目标选择 — 那会让 bot"透视"到被
    // 岩石包住的远矿 (用户实测: 密不透风 7 格外的铁矿也能被锁定去挖)。
    private final boolean _greedy;

    // (2026-09-22 批次开局判死保护, 183317 场实锤) 贪婪批次在【第一 tick 之前】
    // 被父层/自身 isFinished 询问时: _miningPos=null(未选目标)、破坏记账未跑
    // (brokenMs=0, 记账在 getClosestTo :1285)、掉落未回册 → 保活/宽限/链挖
    // 三条挂住路径全部失效 → super(POSSESS 已达标)恒真 → isFinished 误判完成
    // → 每 50ms 拆批重建 (diag: [GreedyBatch] release 20 连发, have 却在涨 —
    // 碎片式捡漏)。修: 创建后 GREEDY_BATCH_GRACE_MS 内贪婪批次一律不算完成,
    // 保证至少活到第一 tick (getClosestTo 必然选目标/记账/捡掉落三选一),
    // 之后 isFinished 恢复正常语义 (在场即活/宽限/链挖接管)。
    private static final long GREEDY_BATCH_GRACE_MS = 100;
    private final long _createdMs = System.currentTimeMillis();

    // (2026-09-16 工具打架修复, 082639 场次) 本类 [ToolEquip] 分支与
    // PlayerInteractionFixChain 的 better-tool 分支是两个独立的 0.5s 装备源:
    // 本分支按任务 miningPos(石头)换镐, chain 按"实际在撸的方块"(如卡住时
    // 长按的工作台=木头)换斧 — 每 0.5s 互相覆盖对方的选择, 手在斧/镐间横跳,
    // 挖掘进度反复清零 (diag.log: [ToolEquip] ok=true now=wooden_pickaxe,
    // 0.55s 后又 had stone_axe, x50)。这里记录每次装备决策的时间戳,
    // chain 在窗口内让位 → 任务层装备唯一说话。
    private static volatile long _lastToolEquipDecisionMs = 0;
    private static final long TOOL_EQUIP_DEFER_WINDOW_MS = 2000;

    /** 任务层最近 2s 内做过工具装备决策 (chain 应让位)。 */
    public static boolean taskToolEquipRecently() {
        return System.currentTimeMillis() - _lastToolEquipDecisionMs < TOOL_EQUIP_DEFER_WINDOW_MS;
    }

    // 砍树前做斧子的 COMMIT 状态：一旦决定做斧子就执行到底，不每 tick
    // 重估条件（修复 ping-pong，见 onResourceTick 注释）。
    private boolean _axeCommitted = false;
    private Task _axeTask = null;

    // (2026-09-17 斧链重入护栏 —— 同毫秒 21 轮死循环实锤)
    // 现场 20260917-103520 11:09:24: Restocking 门控派 MineAndCollectTask(log) → 无斧
    // 即 COMMIT 斧链 → 派木斧 → 配方链要 3 木板 → 要原木 → 又派【全新】的
    // MineAndCollectTask(log), 它的 _axeCommitted 是初始 false → 又 COMMIT → 闭环,
    // 同一毫秒 chat/tasks 各刷出 21 轮, 原木恒 0~3。上面那个实例字段 latch 挡不住
    // "新实例重置状态"这条路径, 所以护栏必须放在实例之外。
    // 语义: 斧链由某实例发起后, 【其它】实例在窗口内不得再 COMMIT(发起者自己照常推进);
    // 窗口到期或发起者解除 COMMIT 时自动释放, 避免发起者被丢弃导致永久封死。
    private static Task _axeChainOwner = null;
    private static long _axeChainGuardUntilMs = 0L;
    private static final long AXE_CHAIN_GUARD_MS = 45_000L;
    private static long _lastAxeGuardLogMs = 0L;

    // (2026-09-16 满足采矿工具需求任务粘性缓存) 旧逻辑每 tick new
    // SatisfyMiningRequirementTask → 与内部 getItemTask→CollectCobblestoneTask→
    // new MineAndCollectTask 形成递归嵌套链 (日志实锤 stack 嵌套 3 层)。粘滞复用
    // 同一实例, 直到任务完成/需求满足才释放, 避免每 tick 重建导致子任务反复 stop/restart。
    private Task _satisfyTask = null;

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
        this(itemTargets, blocksToMine, requirement, false);
    }

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement, boolean greedy) {
        super(itemTargets);
        _requirement = requirement;
        _blocksToMine = blocksToMine;
        _greedy = greedy;
        _subtask = new MineOrCollectTask(_blocksToMine, _itemTargets, greedy);
    }

    public MineAndCollectTask(ItemTarget[] blocksToMine, MiningRequirement requirement) {
        this(blocksToMine, itemTargetToBlockList(blocksToMine), requirement);
    }

    public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget[]{target}, blocksToMine, requirement);
    }

    public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement, boolean greedy) {
        this(new ItemTarget[]{target}, blocksToMine, requirement, greedy);
    }

    public MineAndCollectTask(Item item, int count, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget(item, count), blocksToMine, requirement);
    }

    /**
     * (2026-09-17 口径外露, 事故 20260917-143638) 本采集任务的目标方块是不是【木头类】。
     *
     * <p>由来: 上层"囤木门控"要回答"链上是否已经有人在砍木头"。旧口径只看囤木门控自己的
     * 私有实例 {@code _woodStockpileTask}, 认不出别人派发的采集任务 —— 床链
     * (Crafting bed → CollectRecipeResources → Crafting planks → MineAndCollectTask)
     * 正在为自己供料时被门控判成"没在砍木", 于是整条床链被 GoToSurfaceTask 顶掉
     * (3 羊毛已到手、只差 3 木板, 却整夜再没做床)。此处把 {@code _subtask} 的私有
     * 判定公开出来, 让门控能沿任务链自省。
     */
    public boolean isWoodTarget() {
        return _subtask != null && _subtask.isTargetingWood();
    }

    public static Block[] itemTargetToBlockList(ItemTarget[] targets) {        List<Block> result = new ArrayList<>(targets.length);
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                Block block = Block.getBlockFromItem(item);
                if (block != null && !WorldHelper.isAir(block)) {
                    result.add(block);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(_blocksToMine);

        // We're mining, so don't throw away pickaxes.
        mod.getBehaviour().addProtectedItems(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

        // 砍树任务里斧子也是保护物品，别当垃圾扔了。
        if (_subtask.isTargetingWood()) {
            mod.getBehaviour().addProtectedItems(AXES);
        }

        _subtask.resetSearch();
        _axeCommitted = false;
        _axeTask = null;
        _satisfyTask = null;
        // (2026-09-17) 本实例若曾是斧链发起者, 重开时释放护栏(只释放自己持有的)。
        releaseAxeChainGuard();
        // (20261113 层1) 重开 = 新机会 (维度可能已换), 放弃标记复位。
        _abandoned = false;
    }

    // (20261113 铁律②出口·层1) 放弃语义: 本实例已确认"全目标本维度不可得",
    // 对外表现为已完成 (Task.hasAbandoned 语义, 熔炉 giveUp 先例), 供框架的
    // 弃用子任务强制替换与上游 previousGaveUp 判定消费。isFinished 的合并入口
    // 见下方既有覆写。
    private boolean _abandoned = false;

    @Override
    public boolean hasAbandoned() {
        return _abandoned;
    }

    /** (20261113 层1) 所有非空目标的全部变体在当前维度都不可获得。空目标集不放弃。 */
    private boolean _allTargetsUnobtainableInDimension(AltoClef mod) {
        boolean anyTarget = false;
        for (ItemTarget t : _itemTargets) {
            if (t == null || t.isEmpty()) continue;
            anyTarget = true;
            boolean anyObtainable = false;
            for (Item i : t.getMatches()) {
                if (ItemHelper.isItemObtainableInCurrentDimension(mod, i)) {
                    anyObtainable = true;
                    break;
                }
            }
            if (anyObtainable) return false;
        }
        return anyTarget;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Picking up is controlled by a separate task here.
        return true;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        // (20261113 铁律②出口·层1) 全目标物品在当前维度均不可获得 → fail-fast
        // abandon (放弃且 isFinished=true, 上游 CollectRecipeCataloguedResourcesTask
        // 的 previousGaveUp/detached 重试链与 CraftInTableTask 层3 出口据此换道)。
        // 旧版零出口: 目标维度不存在时 BiasedExplore 无限游走静默磨死
        // (1113-113440 实锤: MineAndCollect(blackstone x3) 在主世界挂 13m36s)。
        // 口径复用 ItemHelper 维度曲线表 (表外物品放行), 与家族槽维度过滤同一判据源;
        // 只拦"全部目标都不可得", 混合目标 (任一可得) 照常。
        if (!_abandoned && _allTargetsUnobtainableInDimension(mod)) {
            _abandoned = true;
            Debug.logWarning("[MineCollect] 全目标在本维度均不可获得 (dim=" + ItemHelper.currentDimensionId(mod)
                    + ", targets=" + java.util.Arrays.toString(_itemTargets) + ") -> abandon, 上层换道重规划");
            return null;
        }

        // (20261113 通用回地表前置) 木头 = 地表资源 (GoToSurfaceTask 头注释的用户
        // 原则), 但该前置此前只在食物 (CollectFoodTask:780) / 羊毛
        // (CollectWoolTask:229) 任务里实现, 木头链一直缺失 → 重生在地下基地
        // (y≈-18) 时 BiasedExplore 在洞穴里随机游走永远遇不到树
        // (1113-113440 实锤: Stockpiling wood 0/25 挂 50 分钟, bot 停在 y=-16)。
        // 口径与食物/羊毛任务同款: WorldHelper.isDeepUnderground (5 点投票+5s
        // 驻留去抖) → GoToSurfaceTask (挖一垫一+水爬升+放弃冷却自带, 每 tick new
        // 因 isEqual 不重启)。限主世界: WOOD_LOG_BLOCKS 全是主世界原木。
        if (_subtask.isTargetingWood()
                && mod.getWorld() != null
                && mod.getWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD
                && WorldHelper.isDeepUnderground(mod)) {
            setDebugState("Underground: returning to surface before chopping wood");
            Debug.logToFile("altoclef_diag.log", "[WOOD-SURFACE]\tdeep underground while collecting logs (y="
                    + (mod.getPlayer() != null ? mod.getPlayer().getBlockPos().getY() : "?") + ") -> GoToSurfaceTask first");
            return new GoToSurfaceTask();
        }

        if (!StorageHelper.miningRequirementMet(mod, _requirement)) {
            // (2026-09-16 修复递归嵌套/每tick重建) 旧代码每 tick new SatisfyMiningRequirementTask
            // → 与内部 getItemTask→CollectCobblestoneTask→new MineAndCollectTask 形成
            // 递归嵌套链 (日志实锤 stack 嵌套 3 层), 且每 tick 全新实例导致子链反复
            // stop/restart。现在粘滞复用同一实例 (仿 _axeTask 写法), 直到任务完成或
            // 需求满足才释放。
            // 为什么不做"祖先链守卫": Task.thisOrChildSatisfies 只沿 _sub 向下遍历
            // (this+子孙), 查不到重入场景里的 SMR 祖先; 而 onResourceTick 返回 null
            // 的真实语义是"停掉当前子任务并置空"(Task.tickInternal else 分支), 会让
            // CollectCobblestoneTask 派出的本任务空转 → 死锁。实际上嵌套深度有界:
            // 内层 Mine 的 requirement 是 WOOD (徒手可满足) 正常即终止, 真正的病灶
            // 是"每 tick 重建"而非深度本身, 粘滞缓存 + TaskCatalogue 同资源活实例
            // 合并 (见 TaskCatalogue.CataloguedResource._liveInstance) 已根治。
            if (_satisfyTask == null || _satisfyTask.isFinished(mod)) {
                // (2026-09-16 验证日志) 新建即打点, 只落盘不进聊天。验证判据 ——
                // 生效: [SMR-CACHE] new 低频且相邻间隔内子链 (CollectCobblestone→Craft)
                // 能跑完; 失败: 每秒多条 new = 粘滞失效, 嵌套重建回归。
                Debug.logToFile("altoclef_diag.log", "[SMR-CACHE] new SatisfyMiningRequirementTask ("
                        + _requirement + ", prev " + (_satisfyTask == null ? "none" : "finished") + ")");
                _satisfyTask = new SatisfyMiningRequirementTask(_requirement, "mine-and-collect");
            }
            return _satisfyTask;
        }
        // 需求已满足 → 释放缓存, 下次若再不满足会重新派发 (不递归)。
        _satisfyTask = null;

        // 砍树必须用斧子：没有斧子且【立即能做出斧子】（整条合成链不缺
        // 任何材料）时，先去合成石斧/木斧再继续收集。
        //
        // 修复 1 (StackOverflow 崩溃 2026-08-21 11:46): 旧判断只查
        // "圆石3+木棍2"，但石斧是 3x3 配方需要合成台，合成台需要木板，
        // 木板需要原木 → 做斧任务内部又生成"收集原木"任务，新实例再次
        // 要求先做斧子 → 任务链自我嵌套，单 tick 内无限递归直至
        // StackOverflowError。现在只有当斧子合成链（含合成台）不缺料时
        // 才触发；否则空手先砍几根原木攒材料，攒够后本条件自然满足。
        //
        // 修复 2 (ping-pong 2026-08-21 11:54): 条件每 tick 重估会被自己
        // 的子任务扰动——做斧链在合成木板时把原木从背包搬进 2x2 合成格/
        // 光标槽，背包计数跌破阈值 → 条件翻转回收集分支 → 收集分支把
        // 原木搬回背包 → 条件又成立 → 循环横跳。现在一旦决定做斧子就
        // COMMIT 到底（不再重估），直到斧子到手才解除；任务实例也缓存，
        // 避免每 tick 重建导致子任务反复 stop/restart。
        if (_subtask.isTargetingWood() && !hasAnyAxe(mod)) {
            // (2026-09-01 睡床后做木斧卡死 35s 根因修复)
            // 阶段推进: 当前斧子子任务 isFinished 后清缓存, 下一 tick 自动
            // 重算下一阶段 (如: 拿到 crafting_table 物品 → 切 wooden_axe)。
            if (_axeTask != null && _axeTask.isFinished(mod)) {
                _axeTask = null;
            }
            if (!_axeCommitted && shouldCommitToAxeChain(mod)) {
                _axeCommitted = true;
                _axeTask = null;
                // (2026-09-17 护栏) 登记自己是发起者: 斧链跑完前, 别的
                // MineAndCollectTask 实例(多为配方链回派出来的挖原木任务)不得再 COMMIT。
                markAxeChainOwned();
                Debug.logMessage("[Axe] Committing to axe chain before chopping wood.");
            }
            if (_axeCommitted) {
                setDebugState("Crafting an axe before chopping wood");
                if (_axeTask == null) {
                    _axeTask = computeNextAxeTask(mod);
                    // 算不出下一步 → 理论不该发生, 解除 COMMIT 继续砍树
                    if (_axeTask == null) {
                        _axeCommitted = false;
                        releaseAxeChainGuard();
                        return _subtask;
                    }
                }
                return _axeTask;
            }
        } else {
            // 有斧子了（或不是砍树任务）：解除提交状态。
            _axeCommitted = false;
            _axeTask = null;
            releaseAxeChainGuard();
        }

        // (2026-09-01 手挖修复) 追矿石(isMining)与探索挖隧道(isBreakingBlock, 手挖
        // 高发场景)都触发工具核对 — makeSureToolIsEquipped 内部再用控制器破坏方块兜底。
        if (_subtask.isMining() || mod.getControllerExtras().isBreakingBlock()) {
            makeSureToolIsEquipped(mod);
        }

        // Wrong dimension check.
        if (_subtask.wasWandering() && isInWrongDimension(mod)) {
            return getToCorrectDimensionTask(mod);
        }

        // (2026-09-12 跨批次对撞闸门) 心跳登记"主线挖矿已接触矿石 + 当前矿种",
        // 供 BeatMinecraft2Task 的贪心检查让位 (祖先查不到后代, 只能后代主动登记)。
        heartbeatMainlineEngaged(mod);
        // (2026-09-26 配额期) "正在推进矿脉"心跳, 口径见 heartbeatMainlineActive 注释。
        heartbeatMainlineActive(mod);

        return _subtask;
    }

    private static final Item[] AXES = {
            Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
            Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE
    };

    private boolean hasAnyAxe(AltoClef mod) {
        for (Item axe : AXES) {
            if (mod.getItemStorage().hasItem(axe)) return true;
        }
        return false;
    }

    // ================================================================
    // (2026-09-01 斧子合成分阶段调度 重写)
    // 原 canCraftAxeRightNow 门槛过松: effectivePlanks≥8 就立即 COMMIT 派
    // wooden_axe (3x3) 任务, 但既无工作台物品/方块(USE) 又无真木板 (只
    // 有 1 木板 + 大量原木待合成) → DoCraftInTableTask 材料检查"缺木板
    // →CollectRecipeResources(wooden_axe)" 与 DoStuffInContainerTask "没
    // 有工作台→Getting container item → Craft 2x2 crafting_table" 两分支
    // 1tick 内互 STOP, 最终 CollectRecipeResources(crafting_table) 停在半
    // 空 → 35s 原地不动 (用户实测 15:20:26 → 15:21:56)。
    //
    // 新模型 分阶段:
    //   A. shouldCommitToAxeChain → 仅用真实背包(含2x2合成格) 计数,
    //      effectivePlanks≥8(全自给) 或 有台+直接材料 才允许 COMMIT。
    //   B. computeNextAxeTask → 按优先级分派:
    //      (1) 石斧立即 (有台USE + 3cobble+2sticks)
    //      (2) 无工作台 → 先派 crafting_table 物品 (2x2 合木板4→合台)
    //          不够木板就撸 2 根 log
    //      (3) 有台 → 木斧 (3木板+2木棍)
    //      (4) fallback → 再撸 2 根 log
    //   C. onResourceTick: 当前斧子子任务 isFinished 就清缓存, 下 tick 重算
    // ================================================================

    /**
     * 斧子合成链【是否值得 COMMIT 开工】:
     *  - 石斧/木斧: 有台 USE + 直接材料 立即够
     *  - 全自给: 无台, 但木板当量(planks + logs*4)≥8 = 4做台 + 3做斧 + 1做棍
     * 一律用真实背包(含 2x2 合成格 + 光标) 计数, 不使用 tracker 缓存。
     */
    private boolean shouldCommitToAxeChain(AltoClef mod) {
        // (2026-09-17 重入护栏) 已有别的实例在跑斧链 → 本实例直接放弃 COMMIT, 老实挖树。
        // 递归场景里, 配方链回派出来的 MineAndCollectTask(log) 从这条路径被挡住,
        // 于是它只能去做本该做的事(挖木头), 闭环就断了。
        if (isAxeCommitBlockedByGuard()) {
            long now = System.currentTimeMillis();
            if (now - _lastAxeGuardLogMs > 5000L) {
                _lastAxeGuardLogMs = now;
                Debug.logToFile("altoclef_diag.log", "[AXE-GUARD] reentrant axe commit blocked (owner alive, "
                        + (_axeChainGuardUntilMs - now) + "ms left)");
            }
            return false;
        }
        int planksReal = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, ItemHelper.PLANKS);
        int logsReal   = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, ItemHelper.LOG);
        int sticksReal = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.STICK);
        int cobbleReal = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.COBBLESTONE);
        boolean hasTableUse = StorageHelper.isItemOwned(mod, StorageHelper.Ownership.USE, Items.CRAFTING_TABLE);
        int effectivePlanks = planksReal + logsReal * 4;
        // 石斧立即: 有台 + 3圆石 + 2木棍
        if (hasTableUse && cobbleReal >= 3 && sticksReal >= 2) return true;
        // 木斧立即: 有台 + 3木板 + 2木棍
        if (hasTableUse && planksReal >= 3 && sticksReal >= 2) return true;
        // (2026-09-17 口径收窄 —— 删掉原"有台 + effectivePlanks>=3 + 木棍够"这条)
        // 旧条目允许"只有原木、没有真木板"时 COMMIT: 判定侧按 logsReal*4 折算说"能做",
        // 而配方链只认真木板 → 判缺料 → 要原木 → 回派挖原木任务 → 同毫秒 21 轮闭环
        // (20260917-103520 实锤)。真木板没到位就不该在这里开工, 交给主收集任务砍够料,
        // 与第 2/4 步 "abort axe chain, back to chopping" 的处理同构。
        // 全自给(最常见): 没台, 但有效木板≥8 = 4做台 + 3做斧 + 2木棍需1木板 = 8
        // (2026-09-02 递归修复) 仅无台时允许全自给 COMMIT: 有台但缺木棍时做不了
        // 木斧, computeNextAxeTask 会因缺棍落到撸原木 fallback → 形成 wood→做斧→
        // 撸原木→wood 自我递归 (tasks.log t=784 实锤卡死)。有台+缺棍返回 false,
        // 回主收集任务砍树攒料, 木棍由后续合成阶段处理, 不卡死也不递归。
        // (2026-09-17) 这条路径仍可能被"原木折算"触发, 但即便派出的合成链回头要原木,
        // 也会被上面的重入护栏挡住 → 最坏只是慢一拍, 不会再自递归。
        return !hasTableUse && effectivePlanks >= 8;
    }

    // ================================================================
    // (2026-09-17 斧链重入护栏的实现) 见字段区注释。
    // ================================================================

    /** 是否有【别的】实例正持有斧链护栏(且未超时)。超时即自动释放, 防永久封死。 */
    private boolean isAxeCommitBlockedByGuard() {
        if (_axeChainOwner == null) return false;
        if (_axeChainOwner == this) return false;
        if (System.currentTimeMillis() >= _axeChainGuardUntilMs) {
            _axeChainOwner = null;
            _axeChainGuardUntilMs = 0L;
            return false;
        }
        return true;
    }

    /** 发起斧链 COMMIT: 记下发起者与本轮护栏窗口。 */
    private void markAxeChainOwned() {
        _axeChainOwner = this;
        _axeChainGuardUntilMs = System.currentTimeMillis() + AXE_CHAIN_GUARD_MS;
    }

    /** 解除 COMMIT: 只有发起者能释放护栏(其它实例调用是空操作)。 */
    private void releaseAxeChainGuard() {
        if (_axeChainOwner == this) {
            _axeChainOwner = null;
            _axeChainGuardUntilMs = 0L;
        }
    }

    /**
     * 计算斧子链"当前阶段应该执行的子任务"。
     * 先台后斧、先石后木, 每个阶段完成后 onResourceTick 会清缓存调用本函数推进。
     * 返回 null 表示放弃当前斧子 COMMIT(不应发生在正常流程)。
     */
    private Task computeNextAxeTask(AltoClef mod) {
        int planksReal = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, ItemHelper.PLANKS);
        int logsReal   = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, ItemHelper.LOG);
        int sticksReal = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.STICK);
        int cobbleReal = StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, Items.COBBLESTONE);
        boolean hasTableUse = StorageHelper.isItemOwned(mod, StorageHelper.Ownership.USE, Items.CRAFTING_TABLE);
        int effectivePlanks = planksReal + logsReal * 4;

        // 1) 石斧: 有台 + 3圆石 + 2木棍 → 直接合成
        if (hasTableUse && cobbleReal >= 3 && sticksReal >= 2) {
            Debug.logMessage("[Axe] phase -> stone_axe (table " + hasTableUse + " cobble=" + cobbleReal + " sticks=" + sticksReal + ")");
            return TaskCatalogue.getItemTask(Items.STONE_AXE, 1);
        }

        // 2) 没有工作台 (USE) → 先在 2x2 合木板 → 合一个 crafting_table 物品
        //    这步完成后, hasTableUse=TRUE(背包里有 crafting_table 物品或已放下的方块)
        if (!hasTableUse) {
            if (effectivePlanks >= 4) {
                Debug.logMessage("[Axe] phase -> crafting_table (effPlanks=" + effectivePlanks + " >=4, 2x2 planks→table)");
                return TaskCatalogue.getItemTask(Items.CRAFTING_TABLE, 1);
            }
            // (2026-09-02 递归修复) 有效木板不够 4(做台) 时不能再"撸 2 根原木":
            // getItemTask("log", N) 返回 MineAndCollectTask(wood), 与当前任务同构,
            // 材料在合成格/光标间波动一旦让这里可触达, 就形成 wood→做斧→撸原木→wood
            // 自我递归, 任务系统疯狂 STOP/START 同一任务 (tasks.log t=784 实锤, 木头
            // 停在 2 不再增长)。COMMIT 已保证 effPlanks>=8 理论上走不到, 但计数会波动。
            // 返回 null 让 onResourceTick 解除 COMMIT 回主收集任务砍树 — 主任务本身就
            // 是收木头, 攒够材料后下 tick 自然重新 COMMIT。
            Debug.logMessage("[Axe] phase -> abort axe chain, back to chopping (effPlanks=" + effectivePlanks + " <4 for table)");
            return null;
        }

        // 3) 有工作台了 → 做木斧 (3x3: 3木板 + 2木棍)
        // (2026-09-17 口径修正) 旧条件用 effectivePlanks(=planksReal + logsReal*4) 折算,
        // 把"原木还能合木板"当成"木板已就位": 判定侧说"能做", 而配方链只认真木板,
        // 于是判"缺 3 木板" → 要原木 → 回派 MineAndCollectTask(log) → 同毫秒 21 轮
        // 闭环 (20260917-103520 实锤, chat.log 里 [Axe] phase -> wooden_axe
        // effPlanks=14 刷屏)。现在只认真材料: 3 真木板 + 2 真木棍;
        // 木板够(≥5)但缺木棍也可以 —— 合木棍只吃木板, 全程不涉及原木, 不会再回头挖树。
        // 料不到位就 return null 回主收集任务砍树 (与第 2/4 步同构)。
        if (planksReal >= 3 && (sticksReal >= 2 || planksReal >= 5)) {
            Debug.logMessage("[Axe] phase -> wooden_axe (table=" + hasTableUse + " planks=" + planksReal
                    + " sticks=" + sticksReal + ")");
            return TaskCatalogue.getItemTask(Items.WOODEN_AXE, 1);
        }

        // 4) 兜底: 材料还是不够(如缺木棍/木板当量不足) → 返回 null 放弃本次 COMMIT,
        //    回主收集任务砍树攒料。绝不能返回 getItemTask("log", N): 那是
        //    MineAndCollectTask(wood), 与当前任务同构, 会形成 wood→做斧→撸原木→wood
        //    自我递归 (tasks.log t=784 卡死实锤, 木头停在 2 不再增长)。
        Debug.logMessage("[Axe] phase fallback -> abort axe chain, back to chopping (effPlanks=" + effectivePlanks + " sticks=" + sticksReal + ")");
        return null;
    }

    /** (2026-09-08 无目标空转兜底) 子任务是否已因连续无动作放弃本次收集。 */
    public boolean gaveUpWandering() {
        return _subtask.gaveUpWandering();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (20261113 层1) 放弃语义优先: 短路放行, 上层据此换道。
        if (_abandoned) return true;
        // (2026-09-22 批次开局判死保护) 见 GREEDY_BATCH_GRACE_MS 注释: 第一 tick
        // 前三条挂住路径全失效, 100ms 内一律不算完成。
        if (_greedy && System.currentTimeMillis() - _createdMs < GREEDY_BATCH_GRACE_MS) {
            return false;
        }
        if (!super.isFinished(mod)) {
            // (2026-08-31 防挖床卡死, 用户"又卡住不动") 仅【床】目标: 当 MineAndCollect(bed)
            // 在 96 格内找不到可感知可挖的床时, 不能一直"无目标 null 停"卡死 (getWanderTask
            // 已去偏置=null; 背包又没拿到床所以 super.isFinished 恒 false)。找不到可挖床就
            // 放弃本次挖床, 让父任务 CollectBedTask 回到杀羊取毛做床正路。木头/工作台不受影响。
            if (_subtask.isTargetingBed() && !hasNearbyMineableTarget(mod)) {
                return true;
            }
            // (2026-09-08 无目标空转兜底) 子任务连续 5 秒无任何动作(掉落物/近场矿/
            // 探索全无, surface 目标探索刻意为 null) → 宣告本次收集放弃。旧实现返回
            // false 会让上层(BeatMinecraft 收炉分支等)永远等待, bot 站桩卡死 37 秒
            // (08:27 日志实锤)。放弃后触发条件(如 findNearbyFurnace)若仍成立自然重试,
            // 否则分支收敛, 不会死循环。
            if (_subtask.gaveUpWandering()) {
                return true;
            }
            return false;
        }
        // (2026-08-27 木头不过度砍伐) 下面的扫尾"数量达标再清完眼前同类"是
        // 给矿石的 (矿脉连片, 挖一块还有下一块)。木头只缺 N 根时一旦也扫尾,
        // 会把整棵树甚至旁边树全砍掉 — 即使背包已够也是 (用户实测: 背包明明
        // 有木头还去砍一整批)。木头数量到了就停。
        // (2026-09-15 批采扫堆, 用户定案) 数量达标但脚边 ≤4 格还有本目标掉落物
        // → 不结束, 顺手把这一堆捡完再走。只捡不砍: 背包达标后 getClosestTo 的
        // 批采/链挖分支全部关闭, 不会多砍一棵树, 不违背"不过度砍伐"。
        // 卡死风险: 掉落物捡不走时由 PickupDroppedItemTask 的 15s 被困拉黑/
        // despawn 兜底, 扫堆窗口自然收敛。
        if (_subtask.isTargetingWood()) {
            if (_subtask.hasNearbyWoodDropsToSweep(mod)) {
                return false;
            }
            return true;
        }
        // (2026-09-10 贪婪掉落宽限, 用户"挖掉一个还没拾取就被钻石任务抢去") POSSESS
        // 已达标(背包已有同类)时, 本任务能挂住全靠扫尾语义 hasNearbyMineableTarget;
        // 方块刚破坏的 1-2 tick 内掉落物未落地/未过 EntityTracker 感知过滤 →
        // itemDropped=false → 扫尾两条件(近场矿/掉落物)同时为 false → 立即判完成,
        // 整链退出, 掉落物留在地上 (tasks.log 2026-09-10 20:14:57 实锤: raw_iron
        // have=3, 破坏后 50ms (clean) 退出, 钻石链接管走人)。贪婪模式下: 刚挖掉目标
        // 方块后的宽限窗口内挂住, 等 EntityTracker 回册掉落物 → getClosestTo 去捡;
        // 掉落物出现后又消失(已捡/已毁)或窗口超时(掉岩浆/despawn)则照常结束。
        // 仅 _greedy 生效: 非贪婪收集的目标数通常未达标 (super.isFinished=false),
        // 走不到这条路径, 行为不变。
        // (2026-09-10 23:14 布尔反转修复) 上一版写成 `!hasNearby || hold` — 宽限挂住
        // 时 (!hasNearby=true, hold=true) 恰好返回 true 判完成, 与挂住意图完全相反,
        // 且比修复前更糟: 23:05:07 实锤破坏后 50ms 整链被父层抢占 (diag 07.913
        // [GreedyPickup] hold 日志刚打, 07.914 整链 STOP by 钻石链)。正确语义:
        // 完成 = 没有近场活 且 不在宽限挂住期。
        if (_greedy) {
            // latch 必须在 hasNearbyMineableTarget 之前更新: 掉落物回册时后者为 true,
            // holdGreedyForDrop 因 && 短路不会被调用, latch 就没机会记录"见过掉落物"。
            updateGreedyDropLatch(mod);
            return !hasNearbyMineableTarget(mod) && !holdGreedyForDrop(mod);
        }
        return !hasNearbyMineableTarget(mod);
    }

    // (2026-09-10 贪婪掉落宽限) 掉落物落地+感知回册的宽限窗口。落地约 0.5s,
    // 回册再 1 tick, 2.5s 足够; 上限防"掉落物永远感知不到"(掉岩浆/滚远)时无限挂住。
    // 本方法只在 !hasNearbyMineableTarget 时被调 (itemDropped 已为 false, 掉落物
    // 尚未回册), 所以只需按破坏时刻计时; 掉落物一旦回册, hasNearbyMineableTarget
    // 的 itemDropped 条款自动接管挂住/捡取。
    private static final long GREEDY_DROP_GRACE_MS = 2500;
    // (2026-09-17 贪心收尾提前释放, 用户"挖掉最后一块之后都要顿一下卡几秒") 上面的宽限
    // 是【无条件硬等 2.5s】: 掉落物通常 0.3~1.0s 就落地回册并被吸入, 两个挂住判据早已
    // 为 false, 但 holdGreedyForDrop 只按破坏时刻计时 → 每次挖空都白等满 2.5s 才走人,
    // 现场表现就是"顿一下再走开"。
    // 修复: 【破坏后掉落物曾被感知回册】作 latch; 回册过之后再消失 = 已捡走/已消失,
    // 无需继续挂住。防抖: 消失需持续 DROP_LOST_CONFIRM_MS 才确认 —— 掉落物合并/吸入
    // 瞬间 1~2 tick isValid=false 会横跳 (574295d8 教训), 否则会把还没捡走的掉落物放掉,
    // 退回 2026-09-10 "挖掉还没拾取就被抢走"的老问题。
    // 从未回册的 (掉岩浆/落在 FOV 外) latch 不置位, 照旧等满 2.5s + scoop 兜底。
    private static final long GREEDY_DROP_LOST_CONFIRM_MS = 400;
    private long _greedyDropEpisodeMs = 0;
    private boolean _greedyDropSeen = false;
    private long _greedyDropLostSinceMs = 0;
    private long _greedyEarlyReleaseEpisodeMs = 0;

    /** 每 tick 维护"破坏后掉落物是否回册过"的 latch (见 GREEDY_DROP_LOST_CONFIRM_MS 注释)。 */
    private void updateGreedyDropLatch(AltoClef mod) {
        long brokenMs = _subtask.miningTargetBrokenMs();
        if (brokenMs == 0) {
            _greedyDropEpisodeMs = 0;
            _greedyDropSeen = false;
            _greedyDropLostSinceMs = 0;
            return;
        }
        if (_greedyDropEpisodeMs != brokenMs) {
            // 新的一次破坏 = 新 episode, latch 全部重置。
            _greedyDropEpisodeMs = brokenMs;
            _greedyDropSeen = false;
            _greedyDropLostSinceMs = 0;
        }
        boolean dropSeen = mod.getEntityTracker().itemDropped(_itemTargets);
        if (dropSeen) {
            _greedyDropSeen = true;
            _greedyDropLostSinceMs = 0;
        } else if (_greedyDropSeen && _greedyDropLostSinceMs == 0) {
            _greedyDropLostSinceMs = System.currentTimeMillis();
        }
    }

    /**
     * (2026-09-22 掉落宽限对外查询, 183317 场实锤) 本批次是否处于"挖掉目标块后
     * 等掉落物落地回册"的宽限挂住期。供 CollectVisibleOresTask 的批次释放检查
     * 使用: isFinished 可能因掉落物 tracker 1~2 tick 回册抖动瞬时翻真, 父层若
     * 直接拆批会把宽限语义整个绕掉 (每 50ms 拆批重建循环的成因之一)。
     * 调用前先刷 latch, 与 isFinished 内部顺序一致。
     */
    public boolean holdingForGreedyDrop(AltoClef mod) {
        if (!_greedy) return false;
        updateGreedyDropLatch(mod);
        return holdGreedyForDrop(mod);
    }

    // (2026-10-16 存在性挂住保底) 掉落物持续被感知时的挂住硬上限 — 防不可达掉落物
    // 无限挂住 (hold 分支必须有出口)。到头后走既有释放路径 (isFinished 翻真)。
    private static final long GREEDY_DROP_HOLD_CAP_MS = 30000;
    // (2026-09-11 背包满顺延) scoop/腾格子进行中的宽限顺延上限(总窗口 = GRACE + 此值)。
    private static final long GREEDY_DROP_SCOOP_EXTEND_MS = 5000;
    private long _greedyGraceLogEpisodeMs = 0;
    // (2026-10-16 存在性挂住) 每事件一条的日志标记 (独立于 scoop 顺延标记)。
    private long _greedyExistLogEpisodeMs = 0;

    private boolean holdGreedyForDrop(AltoClef mod) {
        long brokenMs = _subtask.miningTargetBrokenMs();
        if (brokenMs == 0) return false;
        long now = System.currentTimeMillis();
        // (2026-09-17 提前释放) 掉落物回册过且已连续消失 CONFIRM_MS → 已捡走/已消失,
        // 不必再挂住 (见 GREEDY_DROP_LOST_CONFIRM_MS 注释)。scoop 顺延期间同样生效:
        // 此时掉落物既已消失, 走过去也没得捡。
        if (_greedyDropSeen && _greedyDropLostSinceMs != 0
                && now - _greedyDropLostSinceMs >= GREEDY_DROP_LOST_CONFIRM_MS) {
            if (_greedyEarlyReleaseEpisodeMs != brokenMs) {
                _greedyEarlyReleaseEpisodeMs = brokenMs;
                Debug.logToFile("altoclef_diag.log", "[GreedyPickup]\tearly release — drop perceived then gone "
                        + "(picked up/lost) after " + (now - brokenMs) + "ms, grace was " + GREEDY_DROP_GRACE_MS + "ms");
            }
            return false;
        }
        long sinceBroken = now - brokenMs;
        // (2026-10-16 存在性挂住, 用户拍板"按推荐修") 掉落物【此刻仍在 tracker 里】
        // (= 还没捡走也没消失确认) 时, 不再受 2.5s 固定宽限约束, 持续挂住到
        // 捡走/消失确认或 30s 保底。
        // 实锤 (20261016-162404 会话 16:27:33): 掉落物 3~6 格外导航 >2.5s, 固定宽限
        // 到期整批被铁套任务打断, 捡拾中途弃捡 → 金掉落物遗落; 且"挖一块→弃捡→
        // 铁套顶上→3s 冷却后贪婪再触发"形成乒乓 (贪婪↔铁套互抢 22/8 行)。
        // 语义 = 掉落物存在性 (单一判定源, updateGreedyDropLatch 维护); 旧 2.5s 窗
        // 仅剩"掉落物尚未落地回册"的短暂窗口职责。30s 保底满足"hold 分支必须有出口"
        // (掉落物不可达等异常到头放行)。
        if (_greedyDropSeen && _greedyDropLostSinceMs == 0 && sinceBroken < GREEDY_DROP_HOLD_CAP_MS) {
            if (_greedyExistLogEpisodeMs != brokenMs) {
                _greedyExistLogEpisodeMs = brokenMs; // 独立标记, 与 scoop 顺延日志互不踩
                Debug.logToFile("altoclef_diag.log",
                        "[GreedyPickup]\tholding — drop still perceived (existence-based, cap "
                                + (GREEDY_DROP_HOLD_CAP_MS / 1000) + "s)");
            }
            return true;
        }
        boolean withinGrace = sinceBroken < GREEDY_DROP_GRACE_MS;
        // (2026-09-11 背包满顺延, 09:58:56 金矿实锤) scoop 走到破坏点后捡不起来:
        // 背包满, "Ensuring inventory is free" 腾格子耗时超过剩余宽限 → 58.27 宽限
        // 到期整链释放, 金掉落物留在原地。修复: scoop 两段任务(转头看/走到位)仍在
        // 进行时顺延宽限; 硬上限 GRACE+5s 防不可达目标无限挂住, 到头照常释放。
        if (!withinGrace && sinceBroken < GREEDY_DROP_GRACE_MS + GREEDY_DROP_SCOOP_EXTEND_MS
                && _subtask.scoopInProgress()) {
            if (_greedyGraceLogEpisodeMs != -brokenMs) {
                _greedyGraceLogEpisodeMs = -brokenMs; // 负值区分顺延日志, 每事件一条
                Debug.logToFile("altoclef_diag.log",
                        "[GreedyPickup]\textending grace — scoop/inventory-freeing still in progress");
            }
            return true;
        }
        if (!withinGrace) return false;
        if (_greedyGraceLogEpisodeMs >= 0 && _greedyGraceLogEpisodeMs != brokenMs) {
            _greedyGraceLogEpisodeMs = brokenMs;
            Debug.logToFile("altoclef_diag.log",
                    "[GreedyPickup]\thold after ore break (grace " + GREEDY_DROP_GRACE_MS
                            + "ms) — waiting for drop landing/perception");
        }
        return true;
    }

    /**
     * (2026-09-12 批次原子性, 用户"挖完一处的矿然后全部拾取完才换到下一处") 本批次是否
     * 已经"接触"过矿石 —— 破坏过目标方块, 或感知范围内已有目标掉落物待拾取。
     *
     * 供 CollectVisibleOresTask 判断能否中途换矿种: 已接触 → 锁死到本任务 isFinished
     * (该处挖空 + 掉落物拾完); 未接触 (纯导航阶段) → 仍允许转向更富矿脉, 保留
     * 2026-08-22 "被导航导向某矿的途中, 发现可贪心矿应立马转向" 的设计意图。
     *
     * 实锤 (2026-09-12 21:51:05 会话 20260912-212737): 贪心批次把 163/-183 那片铁矿
     * 挖空后正走去捡掉落物 (掉落物 (175,20,-172) 距 bot 仅 3.68 格), 动态刷新见隔壁
     * 1 块金矿 (vis 1 vs 0) 判"更丰富"整批切走 → 铁掉落物留原地、金矿 1.9s 后放弃
     * 探索, 净损 ~4s 两处皆空。
     */
    public boolean hasEngagedOre(AltoClef mod) {
        return _subtask.miningTargetBrokenMs() != 0
                || mod.getEntityTracker().itemDropped(_itemTargets);
    }

    // (2026-09-12 跨批次对撞闸门) 非贪婪(主线)挖矿批次"已接触矿石"时的心跳登记:
    // 当前矿种方块 + 时间戳。供 BeatMinecraft2Task 的贪心检查让位 —— 祖先任务在
    // 自己的 onTick 里【查不到后代任务】(Task.tickInternal 是自上而下"先
    // addTaskToChain 再 onTick", 见 Task.java L56/L67: BeatMinecraft2Task.onTick
    // 跑的时候链上只有它自己和祖先), 所以只能由后代主动登记 (与
    // SmeltInFurnaceTask.getActiveSmeltingFurnacePos 同模式)。
    private static final Set<Block> _MAINLINE_ENGAGED_BLOCKS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static long _mainlineEngagedMs = 0;
    // 心跳 TTL: 主线批次每 tick 刷新, 1500ms 覆盖"挖完一块到下一块"的间隔; 超时
    // 即视为"主线没在推进挖矿"(切去合成/走路/被抢占), 贪心恢复正常触发。
    private static final long MAINLINE_ENGAGED_TTL_MS = 1500;

    // (2026-09-26 配额期让位口径收紧) "active" = 主线【此刻】真正在推进矿脉, 与上面
    // engaged(挖到过一颗即一次性锁存)分开登记: 心跳条件 = 贴身有【当前真感知】的
    // 目标矿 (8 格内 + LOS, 防透视) 或目标掉落物落地待捡; 走离矿脉/掉落捡完即过期。
    // 供 BeatMinecraft2Task 配额期区分"挖矿脉中"(让位) 与"正在找"(金/铁放行)。
    private static final Set<Block> _MAINLINE_ACTIVE_BLOCKS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static long _mainlineActiveMs = 0;

    // (2026-09-26 二次收紧, 145441 场 14:57:03~17 实锤) 只判"known 非空"不够:
    // BlockTracker 对"见过未挖"的钻石位置是持久记忆, 找矿/探洞途中恒非空 →
    // active 恒真 → 路过金矿连续 4 拍 TRIGGER 仍全被让位 (等价于换壳的一次性
    // 锁存)。收紧为"贴身": 已知目标矿在该半径内才算"正在挖完这个矿脉"。半径
    // 取 8 = 矿脉作业区 (贴身挖 + 绕脉走位) 覆盖; 赶路/探洞中的远记忆 (通常
    // >12 格) 不算, 金/铁贪婪放行, bot 走回矿脉 8 格内时 active 自然恢复。
    private static final int MAINLINE_ACTIVE_NEAR_RADIUS_SQ = 8 * 8;

    /** "正在推进矿脉"心跳: 口径见 _MAINLINE_ACTIVE_BLOCKS 注释与 {@link #mainlineMiningActiveOn}。 */
    private void heartbeatMainlineActive(AltoClef mod) {
        if (_greedy) return;
        if (!nearKnownTarget(mod)
                && !mod.getEntityTracker().itemDropped(_itemTargets)) return;
        _mainlineActiveMs = System.currentTimeMillis();
        _MAINLINE_ACTIVE_BLOCKS.clear();
        for (Block b : _blocksToMine) _MAINLINE_ACTIVE_BLOCKS.add(b);
    }

    /** 已知目标矿是否有任意一块在 {@link #MAINLINE_ACTIVE_NEAR_RADIUS_SQ} 贴身半径内。
     *  (2026-09-26 防透视, 用户"心跳的是完全看不见的钻石也是透视") known 位置来自
     *  chunk 扫描(未看见也登记), 让位判定必须只认【当前真感知】的矿 —— 看不见的
     *  不算"正在挖矿脉", 否则路过得让位也是透视决策。 */
    private boolean nearKnownTarget(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        for (BlockPos known : mod.getBlockTracker().getKnownLocations(_blocksToMine)) {
            int dx = known.getX() - player.getX();
            int dy = known.getY() - player.getY();
            int dz = known.getZ() - player.getZ();
            if (dx * dx + dy * dy + dz * dz > MAINLINE_ACTIVE_NEAR_RADIUS_SQ) continue;
            if (!VisibilityHelper.isBlockPerceivable(mod, known)) continue;
            return true;
        }
        return false;
    }

    /**
     * (2026-09-12 跨批次对撞闸门) 供 BeatMinecraft2Task 调用: 主线挖矿是否正在推进
     * 【给定矿种之一】。成立时贪心让位 —— 主线已锁定这处矿并在挖/拾, 贪心插进来只会
     * 把主线的矿物目标选择拆掉重新找矿, 两边交替重建 = 用户看到的"被多个地点的矿
     * 反复抢来抢去"。
     *
     * 只拦【同矿种】: 主线挖铁时贪心照样能顺手挖煤/金 (保留 08-22 "进 gear 分支后
     * 碰到煤也顺手挖"的设计)。
     *
     * 实锤 (会话 20260912-212737): 21:51:28.194 gear 链 `Mine And Collect raw_iron x24`
     * 派 `Destroy block at 163,1,-183` 正在挖, 21:51:29.544 被贪心整链抢走 → 贪心自己
     * 派批却选中同一块 (已被 gear 挖成空气) → "Target already air" → 空转 2.6s 后释放,
     * 期间 gear 的掉落物 (163,2,-182) 躺在地上没人捡。
     * 反例 (不拦, 那次抢是合理的): 21:50:38 gear 链在 `Digging a tunnel to find ores`
     * 探矿、尚未锁定矿点 → hasEngagedOre=false → 让贪心去挖眼前的可见铁矿。
     */
    public static boolean mainlineMiningEngagedOn(Block[] blocks) {
        if (System.currentTimeMillis() - _mainlineEngagedMs > MAINLINE_ENGAGED_TTL_MS) return false;
        for (Block b : blocks) {
            if (_MAINLINE_ENGAGED_BLOCKS.contains(b)) return true;
        }
        return false;
    }

    private void heartbeatMainlineEngaged(AltoClef mod) {
        if (_greedy || !hasEngagedOre(mod)) return;
        _mainlineEngagedMs = System.currentTimeMillis();
        _MAINLINE_ENGAGED_BLOCKS.clear();
        for (Block b : _blocksToMine) _MAINLINE_ENGAGED_BLOCKS.add(b);
    }

    /**
     * (2026-09-26 配额期让位口径收紧, 用户定案"正在挖完这个矿脉才让位; 没发现钻石/
     * 正在找时看见金铁就贪") 主线是否【此刻】在推进某矿种 —— 该矿种已知位置落在
     * {@link #MAINLINE_ACTIVE_NEAR_RADIUS_SQ} 贴身半径内, 或其掉落物落地待捡
     * (TTL 1500ms 内有心跳)。
     *
     * <p>与 {@link #mainlineMiningEngagedOn} 的区别: engaged 是"接触过"一次性锁存
     * (挖到过一颗就整段成立), active 随走离矿脉+掉落捡完自动过期。
     * 142310 场实锤: engaged 口径下配额期路过金/铁 3 次 TRIGGER 全被拦、零批次;
     * 145441 场二次实锤: 退一步只判"known 非空"仍不够 (持久记忆恒非空), 必须贴身。
     */
    public static boolean mainlineMiningActiveOn(Block[] blocks) {
        if (System.currentTimeMillis() - _mainlineActiveMs > MAINLINE_ENGAGED_TTL_MS) return false;
        for (Block b : blocks) {
            if (_MAINLINE_ACTIVE_BLOCKS.contains(b)) return true;
        }
        return false;
    }

    private static final double MINE_EVERYTHING_RANGE = 16;

    // (2026-09-22 邻接口径收编, 用户定案"豁免=面相邻6块, 都改, 口径一致") 原此处
    // GREEDY_NEAR_FIELD_RADIUS=2 (贪婪近场 5x5x5=125 块, 09-13 为"矿脉收尾"引入;
    // 更早 3x3x3)。立方体豁免里棱邻/角邻的矿可以整块埋在石头里也被当候选, 与
    // "不透视"冲突。现近场豁免统一收编为【面相邻 6 块】(getNearFieldOreAt):
    // 挖掉/正在挖锚块时, 贴面 6 块必然暴露或即将暴露, 豁免感知不引入透视;
    // 棱/角邻与更远的矿交给 BlockTracker 真感知 (LOS/角点射线) 接力。
    // 木头批采的 ±3 格扫描 (用户 09-15 定案"3 格内还有原木就继续砍") 属批采
    // 行为非感知豁免, 保留立方体口径 → getNearFieldOreInCube。

    private boolean hasNearbyMineableTarget(AltoClef mod) {
        // (2026-08-28 贪心=纯贴身) 贪心只看紧贴矿, 不把缓存里远处/被包住的矿
        // 当成"还有活"——否则会把贪心任务一直挂住又不让 getClosestTo 去挖。
        // (2026-09-01 贪心掉落物, 用户"贪心挖矿后不把所有掉落物捡起来") 原实现
        // 只看近场矿: 挖完紧贴矿后 hasNearFieldTarget=false → isFinished=true →
        // 上层直接把 MineAndCollectTask 切走, 地上掉落物没捡完。补上"tracker
        // 感知范围内还有目标掉落物" → 挖完一处必须捡完掉落物才算完成 (getClosestTo
        // 里无紧贴矿时正是返回 closestDrop 去捡, 二者一致)。tracker 感知范围有限
        // (可见 32 格 + 5s 记忆), 不会拖住任务去超远捡。
        if (_greedy) {
            return _subtask.hasNearFieldTarget(mod)
                    || mod.getEntityTracker().itemDropped(_itemTargets);
        }
        List<BlockPos> known = mod.getBlockTracker().getKnownLocations(_blocksToMine);
        if (known.isEmpty()) return false;
        BlockPos player = mod.getPlayer().getBlockPos();
        // (2026-08-31 白天感知到床就应收进背包) 仅【床】目标放宽判距到 96,
        // 与 getClosestTo 的搜索范围一致 —— 否则床在 50~90 格时 hasNearbyMineableTarget
        // 判"没近床" → isFinished 提前放弃挖床回杀羊, 那张感知到的床永远不会被收进背包。
        // 木头/工作台维持原 16 格(不受影响)。
        double range = _subtask.isTargetingBed() ? 96.0 : MINE_EVERYTHING_RANGE;
        for (BlockPos pos : known) {
            if (!pos.isWithinDistance(player, range)) continue;
            if (!WorldHelper.canBreak(mod, pos)) continue;
            // 远离液体策略：贴着水/岩浆的矿不去挖（挖了会放液体进来把
            // bot 困在水里/烧到），也不算"眼前还有可挖目标"——任务正常
            // 结束，不会因它挂住。
            if (WorldHelper.isAdjacentToLiquid(mod, pos)) continue;
            if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
            Block actual = mod.getWorld().getBlockState(pos).getBlock();
            for (Block target : _blocksToMine) {
                if (actual == target) return true;
            }
        }
        // (2026-08-28 近场即时, 用户) cache 里没有, 但正在挖那块周围 3x3x3 还有
        // 紧贴的连位矿 → 任务不该结束 (否则去挖隧道)。近场扫描 0 延迟, getClosestTo
        // 每 tick 也会走到它并续挖。
        return _subtask.hasNearFieldTarget(mod);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_blocksToMine);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof MineAndCollectTask task) {
            return Arrays.equals(task._blocksToMine, _blocksToMine);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Mine And Collect";
    }

    private void makeSureToolIsEquipped(AltoClef mod) {
        if (_cursorStackTimer.elapsed() && !mod.getFoodChain().isTryingToEat()) {
            assert MinecraftClient.getInstance().player != null;
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (cursorStack != null && !cursorStack.isEmpty()) {
                // We have something in our cursor stack
                Item item = cursorStack.getItem();
                // (2026-09-02 崩溃修复) 光标分支误用可能为 null 的 miningPos 查
                // getBlockState → NPE (crash: Cannot invoke BlockPos.method_10264
                // because "$$0" is null; 场景: 找到刷怪笼/开箱切任务瞬间没有正在挖
                // 的方块)。无当前挖掘方块时无法判断光标工具是否适用, 直接跳过换工具。
                BlockPos cursorMiningPos = _subtask.miningPos();
                if (cursorMiningPos != null && item.isSuitableFor(mod.getWorld().getBlockState(cursorMiningPos))) {
                    // Our cursor stack would help us mine our current block
                    Item currentlyEquipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
                    if (item instanceof MiningToolItem) {
                        if (currentlyEquipped instanceof MiningToolItem currentPick) {
                            MiningToolItem swapPick = (MiningToolItem) item;
                            if (swapPick.getMaterial().getMiningLevel() > currentPick.getMaterial().getMiningLevel()) {
                                // We can equip a better pickaxe.
                                mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                            }
                        } else {
                            // We're not equipped with a pickaxe...
                            mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                        }
                    }
                }
            }
            _cursorStackTimer.reset();
        }
        // (2026-09-01 手挖修复 简化版, 用户"检测到 better tool 却不换工具+代码臃肿")
        // 根因: 旧版用 forceEquipSlot(点槽 SWAP) 装备, 在此挖矿上下文点击装备从不生效
        // ([ToolEquip] 每 0.5s 检测到铁镐却恒 "had cobblestone")。改用代码库在无界面
        // 场景广泛验证的 forceEquipItem(item) (战斗/坐船都用它, 稳定)。0.5s 节流;
        // 位置兜底控制器正在破坏的方块; 方块已挖掉(air)跳过; 已装备合适工具不重复换。
        // (2026-09-02 吃东西↔换镐拉锯修复) 换镐分支必须让位 autoEat: 旧代码这里没有
        // isTryingToEat 检查 — 挖矿时 autoEat 把食物放主手右键吃, 换镐 0.5s 又
        // forceEquipItem 换回镐, FoodChain 下一 tick 又换回食物 → 无限拉锯
        // ([ToolEquip] equip iron_pickaxe (had cooked_porkchop) 0.5s 一次实锤,
        // 镐子永远站不稳)。正在吃时直接 return, 吃完 isTryingToEat=false 自然恢复。
        if (_toolEquipTimer.elapsed() && !mod.getFoodChain().isTryingToEat()) {
            _toolEquipTimer.reset();
            // (2026-09-17 工具按真实破坏对象选, 212108 场实锤) 优先取控制器【正在
            // 破坏】的方块: 目标是合成台(木→斧)而挡块是石头时, 旧顺序用目标方块
            // 选型 → 拿石斧撸石头 ([ToolEquip] equip stone_axe for crafting_table,
            // 实际在挖 occluder 石头, 6s 只啃一点点)。正在破坏的方块才是本次
            // 装备真正要加速的对象; 没在破坏时才看子任务目标位。
            BlockPos miningPos = mod.getControllerExtras().isBreakingBlock()
                    ? mod.getControllerExtras().getBreakingBlockPos()
                    : _subtask.miningPos();
            if (miningPos == null) return;
            BlockState state = mod.getWorld().getBlockState(miningPos);
            if (state.isAir()) return; // 位置陈旧/方块已挖掉, 不换工具
            // (2026-09-16 工具打架修复) 决策生效点: miningPos 非空且非 air,
            // 本分支 0.5s 一次在此表态 → 记时间戳, chain 2s 内让位。
            _lastToolEquipDecisionMs = System.currentTimeMillis();
            Item equippedItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
            if (equippedItem instanceof MiningToolItem mt && mt.isSuitableFor(state)) return;
            Optional<Slot> bestTool = StorageHelper.getBestToolSlot(mod, state);
            if (bestTool.isPresent()) {
                Item bestItem = StorageHelper.getItemStackInSlot(bestTool.get()).getItem();
                if (bestItem != equippedItem) {
                    boolean ok = mod.getSlotHandler().forceEquipItem(bestItem);
                    Debug.logToFile("altoclef_diag.log",
                            "[ToolEquip] equip " + bestItem + " for " + state.getBlock()
                                    + " (had " + equippedItem + ") ok=" + ok
                                    + " now=" + StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem());
                }
            }
        }
    }

    private static class MineOrCollectTask extends AbstractDoToClosestObjectTask<Object> {

        private final Block[] _blocks;
        private final ItemTarget[] _targets;
        private final Set<BlockPos> _blacklist = new HashSet<>();
        // (2026-08-28 贪心=纯贴身) 目标选择只认近场(3x3x3)紧贴的矿, 不走
        // 24 格缓存/世界/仅FOV 发现 — 那会透视被岩石包住的远矿。
        private final boolean _greedy;
        // 进度检查器耐心放宽 (2026-08-21 挖床/收集任务抖动修复): 原 (6s, 1次)
        // 在长距离导航(100+ 格, 如远处村庄的床)时会误判失败 — Baritone 分段
        // 寻路, 段间重算时 bot 站桩可超过 6 秒 → "unreachable"黑名单 →
        // BlockTracker 重扫时把目标从缓存移除 → 父任务(CollectBedTask 等)
        // 翻转到合成分支(去收集木头) → 黑名单过期目标回缓存 → 又翻回来,
        // 形成"挖床↔收集木头"横跳。3 次重试容忍 3 次站桩间歇。
        // (2026-08-22) 3→5: 挖岸边/垂直上岸(如水里挖向陆地圆石)耗时更长,
        // 3 次(18s)仍会"挖一半放弃换目标", 放宽到 5 次(30s)。
        // (2026-09-04 用户"6秒窗口太长×5太多, 1秒窗口×3") 卡住/挖不动判定收紧:
        // 移动窗口 1s、挖掘窗口 1s、各容忍 3 次连续失败 → 约 3s 无进展即换目标。
        // 卡在草/小方块时由失败处理先左键挥击清草(见 onTick), 不再直接放弃。
        private final MovementProgressChecker _progressChecker = new MovementProgressChecker(1, 0.1, 1, 0.001, 3);
        private final Task _pickupTask;
        // (2026-08-22 修补) 挖矿掉落物横跳: 挖掉一个方块掉落物立即出现在脚下,
        // getClosestTo 里 dropSq 极小 → 每挖一块就切去捡 → "挖一个→捡一个"
        // (UserTask 里 ResourceTask 的 shouldAvoidPickingUp=true 已绕过父类冷却)。
        private long _lastDropPickupMs = 0;
        private static final long DROP_PICKUP_COOLDOWN_MS = 2000;
        // (2026-09-10 空中不重建) 空中跳过重建的节流日志锚点。
        private long _lastAirborneSkipLogTick = -100;
        // (2026-09-06 重启启用, 原为死代码) 捡取粘性窗口: 追过掉落物后 2 秒内
        // 不开新隧道/环顾 (见 getWanderTask)。作用方向与旧注释相反 — 不是让
        // 掉落物不参与目标竞争, 而是刚追过掉落物时【等一等】, 别让探索分支
        // 抢断捡取 (用户实测 18:32 铁掉落物两次被 Digging a tunnel 顶停)。
        private Task _explorationTask;
        private BlockPos _miningPos;
        // (2026-09-10 贪婪掉落宽限, 用户"挖掉一个还没拾取就被钻石任务抢去") 目标方块
        // 被挖掉的墙钟时刻。EntityTracker 只收录 GROUNDED(onGround) 的掉落物, 方块刚
        // 破坏后的 1-2 tick 内掉落物还在空中/未过人类感知过滤 → itemDropped=false →
        // 外层 isFinished (super.isFinished 恒真 + 扫尾两条件同时 false) 立即判完成,
        // 掉落物被扔在地上 (tasks.log 20:14:57 实锤: raw_iron have=3 超目标 x1, 破坏
        // 后 50ms 整链退出)。记录破坏时刻供外层挂住等掉落物落地回册。
        private long _miningTargetBrokenMs = 0;
        // (2026-09-15 批采+扫堆, 用户定案"每挖3个拾取一次; 挖了第1个扫它 3 格内
        // 还有别的原木就继续砍, 没有就直接触发拾取") 木头批采状态:
        //   _woodBatchBroken  本批已砍根数 (批 = 从第一根到切拾取为止)
        //   _woodBatchAnchor  批锚点 = 最后一根被砍原木的位置 (邻位扫描中心)
        // 规则: 背包未达标且未砍满 WOOD_BATCH_MAX 时, 锚点 WOOD_BATCH_RADIUS 格内
        // 还有可挖原木 → 继续砍 (掉落物先放着, 跑不掉); 砍满或无邻位 → 切拾取。
        // 数量达标后脚边 ≤4 格还有掉落物 → 扫堆捡完再走 (只捡不砍, 不违背
        // "木头不过度砍伐"—— 那条卡的是"继续砍新树", 这里只捡已砍出来的)。
        private int _woodBatchBroken = 0;
        private BlockPos _woodBatchAnchor = null;
        private BlockPos _woodBatchLastDiagPos = null;
        private static final int WOOD_BATCH_MAX = 3;
        private static final int WOOD_BATCH_RADIUS = 3;
        private static final double WOOD_SWEEP_RANGE_SQ = 4 * 4;
        // (2026-09-16) 批满后等掉落物回册的宽限窗口 (ms)。EntityTracker 只在掉落物
        // GROUNDED 后收录, 破块到入册有 1-2 tick 空窗; 窗口内挂起开挖, 超时放行。
        private static final long BATCH_BROKEN_GRACE_MS = 1000;
        private AltoClef _mod;
        // (2026-09-04 用户"卡住时左键挥几tick清草, 会被草卡住") 挖不动(ProgressChecker
        // 失败)先长按左键 SWING_TICKS 清掉挡路小草/藤蔓, 挥完仍失败才 blacklist 换目标。
        private int _swingTicksLeft = 0;
        private static final int SWING_TICKS = 4;
        // (2026-08-31) 掉落物导航诊断节流时间戳。
        private long _lastMinePickupDiagMs = 0;
        // (2026-08-23 近矿记忆, 替代被用户否决的 8 格透视) 最近【真正感知到】
        // 的目标方块: pos → 最后感知 tick。矿嵌岩体只露一个面时, 中心点 LOS
        // 会随视角/站位抖动 — 上一 tick 可感知, 下一 tick 被周围岩石挡住 →
        // bot 以为矿没了, 跑去探索 (用户实测: 5 格内金矿没挖完, 转头就忘)。
        // 人类"记住刚看到的矿": 记忆期内即使当前中心点 LOS 被挡也继续当目标
        // (世界验证仍要过, 不透视 — 矿必须曾经真实感知过, 不会隔墙看到)。
        private final java.util.Map<BlockPos, Long> _recentlySeen = new java.util.HashMap<>();
        private static final long RECENTLY_SEEN_TICKS = 100; // 5s 记忆期
        private static final int RECENTLY_SEEN_MAX_SIZE = 200;
        // (2026-09-02 记忆兜底回溯, 用户"杀完怪该回去挖记忆中的钻石") 独立的"最近真实
        // 感知(LOS)过的矿"记录, 供眼前无矿时的记忆兜底导航。与 _recentlySeen(5s, 供
        // 近场链挖)分开——战斗可能打断挖掘数十秒, 5s 太短, 这里给更长的回溯窗口,
        // 让 bot 杀完怪后能回到它【真看过】的矿继续挖, 而不是扭头去挖隧道/空转。
        // 判据: 必须是 LOS 真感知(不透视), 且在本窗口内(40s)、直线距离 ≤64 格(0926
        // 用户拍板, 原 48); 只要眼前有任何可感知矿, 仍优先眼前(既有"舍近求远不可取"
        // 原则不受影响)。
        private final java.util.Map<BlockPos, Long> _seenOreTick = new java.util.HashMap<>();
        private static final long MEMORY_NAV_LIFETIME_TICKS = 800; // 40s 记忆回溯窗口
        // (2026-09-26 48→64, 用户拍板"16 格太短改 64") 记忆兜底导航距离上限。
        // 仅在眼前无任何可感知矿时兜底使用, 不与近目标竞争, 提半径不破"舍近求远"。
        private static final double MEMORY_NAV_RANGE = 64;
        // (2026-09-01 贪心触发放宽后修复, 用户"贪心挖目标矿的3x3x3") 贪心触发能看见
        // GREEDY 范围内的可见矿(CollectVisibleOresTask 8格), 但本任务原先只认玩家
        // 贴身 3x3x3 → 远处可见矿无起始目标 → "Waiting for calculations" 空转卡住。
        // 补: 节流地找 GREEDY_TARGET_RANGE 内最近的可感知目标矿作起始目标。
        private static final int GREEDY_TARGET_RANGE = 8;
        private final adris.altoclef.util.time.TimerGame _greedyTargetScanTimer = new adris.altoclef.util.time.TimerGame(0.5);
        private BlockPos _greedyTargetCache = null;
        // (2026-09-22 透视取证) [GreedyTarget] 日志节流: 同目标不重打, 换目标立打。
        private BlockPos _lastGreedyTargetLogged = null;
        private long _lastGreedyTargetLogMs = 0;
        // Throttle for [Diag-blind] logging (counts wander ticks).
        private int _blindDiagTick = 0;
        // 上次创建 wander 探索任务的游戏 tick（重建冷却用）。
        private long _lastExploreCreateTick = Long.MIN_VALUE;
        // 环顾任务实例与节流（开新隧道前转头看近处 FOV 外的矿）。
        private LookAroundTask _lookAroundTask = null;
        private final TimerGame _lookAroundCooldown = new TimerGame(15);
        // (2026-08-22) 世界扫描 fallback 节流: getNearestTracking 依赖
        // BlockTracker 异步缓存, 启动瞬间可能未就绪 (贪心检查扫世界已看到
        // 矿, 但 cache 空 → 返回 empty → 退化为挖隧道, 用户实测触发贪心后
        // bot 挖 tunnel 不去挖煤)。缓存找不到时扫世界 24 格, 3 秒一次。
        // 用 Wall-clock 而非 TimerGame: 新会话/重连时游戏 tick 从 0 开始,
        // TimerGame 首次 elapsed()=false 会让首次 fallback 直接失败
        // (用户实测 16:01 新会话 t=4 → WorldScan 未执行 → 挖 tunnel)。
        private long _lastWorldScanMs = 0;
        private static final long WORLD_SCAN_INTERVAL_MS = 3000;

        // (2026-08-28 近场即时扫描, 用户) 挖一个发现背后还有一个要"马上挖"。
        // 原理: 眼中/近处的东西是免费、零延迟的, 我们不复用昂贵的缓存/节流。
        // 而是在【正在挖的那块周围 3x3x3】每 tick 直接采样世界 —— 挖穿一块，
        // 它邻位的连位矿下一 tick 就被看到并续挖，不依赖异步 BlockTracker 缓存。
        // 锚定挖矿位置而非玩家，这样即使挖的是远处的矿也不会扫错地方。

        // (2026-09-16 无限介质批采推广, 用户定案"每挖3个拾取一次")
        // 石头家族与木头同属【批采目标】: 介质连续分布, 挖穿一块邻位立刻补上,
        // "挖完本处再统一捡"的 16 格 gate (MINE_EVERYTHING_RANGE) 对它们恒真 →
        // 不切拾取就永远挖下去。20260916-192700 实锤: Collect Cobblestone x3 挖了
        // 53 个不同坐标、0 次拾取、背包圆石恒 0, 直到上层横跳强拆整链。
        // 与 2026-09-15 泥土无限挖坑同构 (当时只补了 DIRT, 石头类漏了)。
        // 口径与 VisibilityHelper.STONE_MEDIUM_BLOCKS 一致 (本项目各处私有集合惯例)。
        private static final Set<Block> STONE_MEDIUM_BLOCKS = Set.of(
                Blocks.STONE, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
                Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE,
                Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF,
                Blocks.BLACKSTONE, Blocks.BASALT, Blocks.NETHERRACK
        );

        // =========================================================================
        // (2026-09-21 21:56 用户定案"先向下挖4格深(人一定跟着下去), 再水平获取最近的方块")
        // 收集材料族 = 地面介质方块 (石头家族/泥土/沙砾/末地石等), 不含矿/木/人造物。
        // 与下界堡垒前的地狱岩收集是同族任务 (用户 2026-09-21 "完全是一样的任务"),
        // 统一走两段式获取策略。修复 20260921-214102 实锤: 末地收 52 建材时每挖掉
        // 一块, 正下方那块就成了新的 "significantly closer target", 以约 1.5s/格
        // 打出 60,54→59,54→60,53→59,53→60,52→… 无尽阶梯坑。
        // =========================================================================
        private static final Set<Block> GROUND_MATERIAL_BLOCKS;
        static {
            java.util.HashSet<Block> ground = new java.util.HashSet<>(STONE_MEDIUM_BLOCKS);
            ground.add(Blocks.END_STONE);
            ground.add(Blocks.DIRT);
            ground.add(Blocks.GRASS_BLOCK);
            ground.add(Blocks.COARSE_DIRT);
            ground.add(Blocks.PODZOL);
            ground.add(Blocks.GRAVEL);
            ground.add(Blocks.SAND);
            ground.add(Blocks.RED_SAND);
            ground.add(Blocks.SANDSTONE);
            ground.add(Blocks.CLAY);
            ground.add(Blocks.SNOW_BLOCK);
            GROUND_MATERIAL_BLOCKS = java.util.Collections.unmodifiableSet(ground);
        }

        /** 目标全是地面介质(末地收建材/下界收地狱岩等收集材料族) → 适用竖井两段式策略。 */
        private boolean isMaterialCollectionTarget() {
            if (_blocks.length == 0) return false;
            for (Block b : _blocks) {
                if (!GROUND_MATERIAL_BLOCKS.contains(b)) return false;
            }
            return true;
        }

        // 竖井状态: _shaftBaseY = 触发时的脚Y (MIN_VALUE=未触发); 到底后进 _horizontalMode。
        private int _shaftBaseY = Integer.MIN_VALUE;
        private boolean _horizontalMode = false;
        private static final int SHAFT_DEPTH = 4;

        /**
         * 竖井安全闸: 脚下 1..{@link #SHAFT_DEPTH} 格连续、可挖、不邻液体,
         * 且第 SHAFT_DEPTH+1 格是实体底 —— 防打穿悬空柱(自己垫的塔)/岩浆上空/浮空
         * 团块 (0919 悬空下界岩教训)。
         * (2026-09-22 0925 用户"和地狱岩策略一样处理") 不再要求柱内方块 ∈ 目标块:
         * 竖井是【下降手段】不是材料来源 —— 0922-091750 实锤: 泥土任务站在草地上,
         * 柱内草方块/石头直接判 unsafe → skip 闩死水平模式 → 阶梯坑复发。
         * 返回 null = 安全; 否则返回原因串 (调用方记日志)。
         */
        private String isSafeShaftColumn(AltoClef mod, BlockPos feet) {
            for (int dy = 1; dy <= SHAFT_DEPTH; dy++) {
                BlockPos p = feet.down(dy);
                if (!mod.getChunkTracker().isChunkLoaded(p)) return "chunk unloaded @" + p.toShortString();
                if (!WorldHelper.canBreak(mod, p)) return "unbreakable @" + p.toShortString();
                if (WorldHelper.isAdjacentToLiquid(mod, p)) return "liquid adjacent @" + p.toShortString();
            }
            BlockPos floor = feet.down(SHAFT_DEPTH + 1);
            if (!mod.getChunkTracker().isChunkLoaded(floor)) return "chunk unloaded @" + floor.toShortString();
            if (!WorldHelper.isSolid(mod, floor)) return "no solid floor @" + floor.toShortString();
            return null;
        }

        // Log/stem/wood blocks only generate on the surface (or nether ceiling).
        // If we're hunting these, NEVER dig a shaft down — a human knows to climb
        // out of a cave first, then walk toward trees.
        private static final Set<Block> WOOD_LOG_BLOCKS = Set.of(
                Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG,
                Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
                Blocks.CRIMSON_STEM, Blocks.WARPED_STEM,
                Blocks.MUSHROOM_STEM,
                Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_SPRUCE_LOG,
                Blocks.STRIPPED_JUNGLE_LOG, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_DARK_OAK_LOG,
                Blocks.STRIPPED_CRIMSON_STEM, Blocks.STRIPPED_WARPED_STEM,
                Blocks.OAK_WOOD, Blocks.BIRCH_WOOD, Blocks.SPRUCE_WOOD,
                Blocks.JUNGLE_WOOD, Blocks.ACACIA_WOOD, Blocks.DARK_OAK_WOOD,
                Blocks.CRIMSON_HYPHAE, Blocks.WARPED_HYPHAE
        );

        // Overworld sea level; surface biomes (forests / plains / jungles) nearly
        // always sit at y>=62. We use this as a soft target for "get back to the
        // surface" before exploring for wood.
        // (2026-09-17) 仅作【锚点高度】用, 不再兼任"是否在地下"的判据 —— 判据走统一
        // 的 SurfaceHelper (见下方 createNextExplorationTask)。本常量取值 63 是
        // "主世界地表典型高度"的经验值, 与 SurfaceHelper 的采样口径互不耦合。
        private static final int SURFACE_SEA_LEVEL_Y = 63;

        // 近处矿绕过 FOV 的距离上限（格）。地下矿洞/隧道场景矿都在这个
        // 范围内（Diag-blind 实测被拒矿 4-15 米）；太远的缓存矿仍可能埋在
        // 岩石深处，不值得专程走。
        private static final double NEAR_ORE_NO_FOV_RANGE = 24;

        // (2026-09-17 木头选择半径 64→128, 用户"能看到几十格外的树却没去获取") 三套
        // 范围口径原本互不相同, 木头顶着最窄的那个:
        //   isBlockPerceivable (Diag-blind 统计"看得见") = 256 格
        //   pickNearestCacheOre (主选择器, 旧值)        = 64 格
        //   pickNearestWorldOre (世界直扫兜底)          = min(maxRange,16) 格
        //   pickNearestNearbySurface (地表资源直扫)     = 8 格
        //   pickNearestMemoryOre (记忆回溯)             = 显式排除 surface 目标, 木头进不去
        // ⇒ 缓存里有 64~128 格的树 (BlockTracker 远区扫描半径本就是 128)、感知判定也
        // "看得见", 但选择器 64 格门槛全拒、记忆导航又不管木头 → 返回空 → wander →
        // BiasedExplore。20260917-135722 实锤: [Diag-blind] perceivable=16/30/29 而
        // nearest rejected 全在 78~99 格 (occluded), 用户现场确认"能看到的树在 64 格
        // 以上"。取 128 = 与 BlockTracker.FAR_SCAN_RADIUS 对齐 (扫得到的就能被选中),
        // 不再自造第三个数字。仅木头放宽: 矿石/石头仍留 24 (挖矿靠隧道推进, 不值得
        // 横穿百格; 且深部矿 128 格内海量, 放宽只会让射线开销爆掉)。
        private static final double WOOD_SEARCH_RANGE = 128;

        // wander 探索任务重建冷却（tick，100=5 秒）。
        private static final long EXPLORE_RECREATE_COOLDOWN_TICKS = 100;

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets) {
            this(blocks, targets, false);
        }

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets, boolean greedy) {
            _blocks = blocks;
            _targets = targets;
            _greedy = greedy;
            // (2026-09-19 15:45) 垫脚补料 (纯下界岩) 的内部拾取任务带上 8 格
            // 追距上限 —— getClosestTo 的掉落上限只管选目标, 这里派发出去的
            // PickupDroppedItemTask 自己有完整的选目标+粘滞, 不带上限就照样
            // 追几十格外的陈旧掉落 (151808/154xxx 实锤两次)。其它目标不限。
            _pickupTask = new PickupDroppedItemTask(_targets, true,
                    isScaffoldGroundTarget() ? SCAFFOLD_DROP_PICKUP_RANGE_SQ : 0);
        }

        private boolean isTargetingWood() {
            for (Block b : _blocks) {
                if (WOOD_LOG_BLOCKS.contains(b)) return true;
            }
            return false;
        }

        /**
         * (2026-09-16) 目标方块集是否全部由【石头类无限介质】组成: 地层里连续
         * 分布、挖穿一块邻位立刻补上。判定取"全部"而非"任一" — 混了稀有矿的
         * 目标集 (如 {STONE, DIAMOND_ORE}) 不该被当成无限介质走批采, 否则会
         * 在钻石露头的矿脉上凑数挖石头。
         */
        private boolean isTargetingStoneMedium() {
            if (_blocks.length == 0) return false;
            for (Block b : _blocks) {
                if (!STONE_MEDIUM_BLOCKS.contains(b)) return false;
            }
            return true;
        }

        /**
         * (2026-09-16) 批采目标: 木头 (2026-09-15) + 石头类无限介质。这类目标
         * 共用 WOOD_BATCH_* 状态机 (每挖 BATCH_MAX 个切一次拾取, 期间在锚点
         * BATCH_RADIUS 格内续挖), 否则"还有矿就继续挖"的 gate 永不放行拾取。
         */
        private boolean isBatchTarget() {
            return isTargetingWood() || isTargetingStoneMedium();
        }

        /**
         * (2026-08-30 找床/羊毛/熔炉不挖洞, 用户 "获取床子任务是挖通道"/"熔炉在眼前还挖洞")
         * 木头、床、羊毛、熔炉、工作台都是【非自然矿】的地表/人造资源。找它们时不该用
         * 挖洞找矿的 MineDownExplore (尤其熔炉: 多为人工放置, 用矿石探洞逻辑会因 FOV/LOS
         * 拒绝眼前遗留熔炉而掉进挖隧道——用户实测 21:35 收熔炉去手挖地道)。
         */
        private boolean isTargetingBed() {
            for (Block b : _blocks) {
                if (b instanceof BedBlock) return true;
            }
            return false;
        }

        private boolean isTargetingSurfaceResource() {
            if (isTargetingWood()) return true;
            for (Block b : _blocks) {
                if (b instanceof net.minecraft.block.BedBlock) return true;
                // (2026-08-30 合成台也是木制地表资源, 找它不挖洞)
                if (b == net.minecraft.block.Blocks.CRAFTING_TABLE) return true;
                // (2026-08-30 熔炉同: 人工放置的可拆容器, 找它不挖洞)
                if (b == net.minecraft.block.Blocks.FURNACE) return true;
                if (b.getTranslationKey().contains("wool")) return true;
            }
            return false;
        }

        /**
         * (2026-08-27 床边挖边捡, 用户) "拆完立刻捡掉落物再继续"的目标:
         * 木头(树下掉落散一地, 边挖边捡不漏) + 床(拆一张床只掉一个,
         * 直奔下一张会把掉落的床扔在原地 — 用户实测拆掉村里的床不捡就
         * 跑去拆下一张)。这些目标的掉落物优先级最高, 破块脚下掉了就
         * 立刻捡起再继续。
         */
        private boolean isCollectDropsImmediately() {
            for (Block b : _blocks) {
                if (WOOD_LOG_BLOCKS.contains(b)) return true;
                if (b instanceof BedBlock) return true;
                // (2026-09-15 泥土立刻捡, 用户"挖泥土没完了") 现场 20260915-165523:
                // 垫塔缺料挖 3 块泥土, 泥土不在立即捡列表 → 掉落优先级低于链挖续挖
                // → bot 站在自己挖的坑里, 泥墙永远供得出下一块链挖目标 → 永不进入
                // 拾取阶段; 而 pumpAcquireMaterials 早停只数背包(have=0 恒成立)。
                // 两半相乘 = 无限挖坑, 24s 切 20+ 目标, [Anomaly:duration] 30s 超限。
                // 矿石链挖不受影响(矿脉有限, 挖完自然转拾取); 泥土是无限介质,
                // 必须"挖一块捡一块"才收敛。
                if (b == Blocks.DIRT) return true;
                // (2026-09-16 石头类, 20260916-192700 实锤) 石头/圆石/深板岩/黑石
                // 同属无限介质: 山体/地层里 16 格内永远有下一块 → 16 格 gate 恒真
                // → 拾取分支永远排不到 (挖 53 块 0 拾取)。这里让它进入"掉落优先"
                // 路径, 配合批采状态机 (挖满 BATCH_MAX 个才切拾取) 收敛。
                if (STONE_MEDIUM_BLOCKS.contains(b)) return true;
            }
            return false;
        }

        /**
         * (2026-09-21 床/木挖完必捡掉落, 用户"第一张打完不去拾取") 掉落最优先
         * 判定写在 getClosestTo (isCollectDropsImmediately 分支), 但父类 pursuit
         * 粘滞在更上游覆盖它 —— 20260921-154115 实锤: 挖掉门口床 (9833,18,-10693)
         * 同帧锁定村庄床 (9829,69,-10662); 掉落物注册后 getClosestTo 已返回脚下
         * 掉落, 但 bot 走向村庄床距离在缩 → "Closing in on target, keeping
         * pursuit" / "Sticking with current pursuit" (debugstate 15:42:00.316/.365
         * 两行) 把掉落永远否决, 掉落躺在原地等 despawn。
         * 修复: 仅当本目标属"挖完立刻捡"族且【场上确实存在掉落物】时开放粘滞
         * 击穿 (与 2026-09-18 战斗近距优先同一钩子) —— 此时 getClosestTo 必然
         * 返回该掉落 (掉落最优先), isSignificantlyCloser 的 0.85 系数天然满足
         * (刚挖完掉落在脚下)。无掉落时防横跳语义原样保留。
         */
        @Override
        protected boolean closerTargetMayBreakSticky(AltoClef mod) {
            if (!isCollectDropsImmediately()) return false;
            return mod.getEntityTracker().itemDropped(_targets);
        }

        // =========================================================================
        // (2026-09-19 垫脚补货贴地选择) 20260919-102254 实锤: 下界垫脚补货挖到
        // 的下界岩大量【悬空】生成 (下 8 格全空/岩浆湖上空的漂浮团块) —— ① 掉落
        // 坠 8 格根本捡不到 (用户"挖几个就不挖了"); ② 悬空块常走不到 (DestroyBlock
        // 42 ticks unreachable + 拉黑循环 Try 5/4)。规则: 目标为下界岩时优先选
        // 【2 格内有实体地面】的贴地块 (掉落落在目标位附近, 站邻位即捡), 悬空块
        // 罚 1e9 选块距离 (全贴地候选耗尽后仍可兜底, 不硬拒绝)。
        // =========================================================================
        private boolean isScaffoldGroundTarget() {
            // (2026-09-19 21:20 统一口径) 原来只认地狱岩 — 挖黑石/玄武岩/深板岩
            // 圆石时近场扩展、8 格掉落物追踪、悬空罚等垫脚优化全部失效。现在认
            // ScaffoldBlocks 全清单 (单方块目标且属搭路材料)。
            return _blocks.length == 1 && ScaffoldBlocks.isScaffoldBlock(_blocks[0].asItem());
        }

        // (2026-09-19 15:20 用户"贼远处的地狱岩掉落物太远了"; 15:28 用户"24还是大")
        // 垫脚补料只追这个半径内的掉落物, 更远的直接无视。挖矿掉落本来就落在
        // 脚下 1~3 格, 8 格已经 covers 掉落弹跳/滚坡, 再远的都是陈旧遗留。
        private static final double SCAFFOLD_DROP_PICKUP_RANGE_SQ = 8 * 8;

        private static boolean scaffoldDropLandsNearby(AltoClef mod, BlockPos p) {
            for (int dy = 1; dy <= 2; dy++) {
                BlockPos below = p.down(dy);
                BlockState s = mod.getWorld().getBlockState(below);
                // 下方 2 格内遇岩浆 = 掉落必丢, 比悬空更糟。
                if (s.getBlock() == Blocks.LAVA) return false;
                if (WorldHelper.isSolid(mod, below)) return true;
            }
            return false;
        }

        /** 悬空垫脚目标的选块罚距: 贴地块正常排序, 悬空块排到最后 (兜底才选)。 */
        private double scaffoldSelectionPenalty(AltoClef mod, BlockPos p) {
            boolean scaffold = isScaffoldGroundTarget();
            // (2026-09-21 2156 竖井策略) 水平阶段的材料采集目标: 脚下层(地板)重罚,
            // 让"水平取最近方块"不被脚下/下方候选抢走。竖井阶段选择器不会被用到
            // (getClosestTo 直接返回脚下方块), 不受影响。
            boolean horizontalCollect = _horizontalMode && isMaterialCollectionTarget()
                    && !isTargetingWood() && !isTargetingBed() && !isTargetingSurfaceResource();
            if (!scaffold && !horizontalCollect) return 0.0;
            // (2026-09-19 16:05 问题一, 用户"拿方块容易持续向下挖") 站立层以下
            // 的下界岩重罚 —— 向下挖出坑, 爬回来又要费方块。平层/以上正常排序,
            // 以下按深度递增罚 (全场上都 Below 时近的下面仍优先兜底)。
            // (2026-09-21 1849, 用户"末地收集材料往下挖的很深") 旧阈值
            // p.getY() < feetY 把【站立层本身】也罚了 —— 平地上整个可挖面
            // (脚下那层) 全是同罚, 罚距等于没罚, bot 照样挖空脚底一层层往下
            // (183333 实锤 y=42→41→40→39 阶梯坑)。阈值下移一格: 站立层
            // (feetY-1, 真正的地表) 不罚, 再往下才罚 —— 贴着地表收料, 不掘地。
            int feetY = (mod.getPlayer() != null) ? mod.getPlayer().getBlockPos().getY() : Integer.MIN_VALUE;
            double penalty;
            if (scaffold) {
                penalty = (p.getY() < feetY - 1) ? 1.0e6 * (double)(feetY - 1 - p.getY()) : 0.0;
                if (!scaffoldDropLandsNearby(mod, p)) penalty += 1.0e9; // 悬空照旧最重罚
            } else {
                // 水平模式: 脚下层(含更深)一律 1e9 罚距 —— 只有水平方向一个候选都
                // 没有时才兜底选中(等价旧"就近"行为), 宁可挖脚下也不站桩。
                penalty = (p.getY() < feetY) ? 1.0e9 : 0.0;
            }
            return penalty;
        }

        @Override
        protected Vec3d getPos(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return WorldHelper.toVec3d(b);
            }
            if (obj instanceof ItemEntity item) {
                // (2026-08-31 掉水里/飞空掉落物导航诊断) 旧实现 item.getPos()。这里打印
                // 实体真实状态, 一次性确认 y=78 到底来自哪、实体是否入水/落地。每 5s 一次。
                {
                    long now = System.currentTimeMillis();
                    if (now - _lastMinePickupDiagMs > 5000) {
                        _lastMinePickupDiagMs = now;
                        Vec3d p = item.getPos();
                        double py = _mod != null && _mod.getPlayer() != null ? _mod.getPlayer().getY() : -1;
                        int gy = WorldHelper.getLandSurfaceY(mod, item.getBlockPos().getX(), item.getBlockPos().getZ(), 0);
                        Debug.logMessage("[MineCollect.PickupTarget] item=(" + (int)p.x + "," + (int)p.y + "," + (int)p.z
                                + ") isTouchingWater=" + item.isTouchingWater()
                                + " isOnGround=" + item.isOnGround()
                                + " playerY=" + (int)py + " landSurfaceY=" + gy);
                    }
                }
                return item.getPos();
            }
            throw new UnsupportedOperationException("Shouldn't try to get the position of object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected Optional<Object> getClosestTo(AltoClef mod, Vec3d pos) {
            Optional<ItemEntity> closestDrop = Optional.empty();
            if (mod.getEntityTracker().itemDropped(_targets)) {
                closestDrop = mod.getEntityTracker().getClosestItemDrop(pos, _targets);
            }
            // (2026-09-19 15:20 用户) 垫脚补料目标只追 24 格内的掉落物, 更远的
            // 无视 —— 现场 151808 实锤: 旧围场留下的陈旧掉落物 (777,77,122) 把
            // bot 在 38~54 格间来回拽 (区块卸载冻结了 5 分钟 despawn 计时, 重载
            // 后"复活")。挖身边的矿自己会掉新的, 远掉落不值得跑一趟; 补料挖满
            // 时它早就 despawn 了。
            if (closestDrop.isPresent() && isScaffoldGroundTarget()
                    && closestDrop.get().squaredDistanceTo(pos) > SCAFFOLD_DROP_PICKUP_RANGE_SQ) {
                closestDrop = Optional.empty();
            }
            // (2026-09-15 批采记账前置) 本任务正在挖的目标已变空气 → 先记账再选目标。
            // 必须放在一切 return 之前: 旧实现这笔账记在下面粘性块里, 木头目标的
            // "掉落最优先"分支会抢先返回, 破坏计数永远漏记。
            if (_miningPos != null && !_blacklist.contains(_miningPos)
                    && mod.getChunkTracker().isChunkLoaded(_miningPos)
                    && mod.getWorld().getBlockState(_miningPos).isAir()) {
                // (2026-09-10 贪婪掉落宽限) 方块刚被挖掉 → 记录时刻, 供外层
                // isFinished 在贪婪模式下挂住等掉落物落地回册。
                _miningTargetBrokenMs = System.currentTimeMillis();
                // (2026-09-11 自挖因果记忆) 记住破坏位置 — "我自己挖掉的矿,
                // 我知道掉落物在哪"属结构推断线索, 供宽限窗口内 scoop 拾取。
                _lastSelfBrokenPos = _miningPos;
                onWoodBatchBroken(_miningPos);
                _miningPos = null;
            }
            // (2026-09-15 批采状态) woodBatchActive = 本批未砍满且背包未达标。
            // 砍满且场上已无本目标掉落物 → 计数清零, 下一批从头计 (掉落物还剩时
            // 不清零, 防掉落物登记 1-2 tick 空窗把计数误清)。
            boolean batchTargetMet = isBatchTarget()
                    && StorageHelper.itemTargetsMetInventoryNoCursor(mod, _targets);
            // (2026-09-16 石头类延迟清零) 原实现批满 + 场上无掉落就立刻清零。刚挖掉
            // 第 BATCH_MAX 块时掉落物还没被 EntityTracker 登记 (1-2 tick 空窗), 立刻
            // 清零 → 挂起判定失效 → 下一 tick 又开挖新一批。给 BATCH_BROKEN_GRACE_MS
            // 等掉落物回册; 超时仍无掉落 (掉进岩浆/被吞) 才清零开新批, 不会卡死。
            if (isBatchTarget() && _woodBatchBroken >= WOOD_BATCH_MAX && closestDrop.isEmpty()
                    && System.currentTimeMillis() - _miningTargetBrokenMs > BATCH_BROKEN_GRACE_MS) {
                _woodBatchBroken = 0;
            }
            boolean woodBatchActive = isBatchTarget() && !batchTargetMet && _woodBatchBroken < WOOD_BATCH_MAX;
            // 批采续砍目标: 围绕批锚点(刚砍的原木)扫 ±3 格 (用户:"扫一下这个原木的
            // 位置的 3 格范围内还有别的原木吗")。首根未砍(_woodBatchBroken==0)时
            // 不用这口径, 走正常最近目标选择。
            // (2026-09-22) 立方体扫描收编为 getNearFieldOreInCube: 批采是行为约定
            // 非感知豁免, 矿石链挖已换面相邻口径, 木头批采口径不变。
            Optional<BlockPos> woodBatchNext = (woodBatchActive && _woodBatchBroken > 0)
                    ? getNearFieldOreInCube(mod, getChainAnchor(), WOOD_BATCH_RADIUS)
                    : Optional.empty();
            // (2026-08-30 木头立即拾取强制, 用户) 木头/床挖完必须立刻捡掉落, 不能
            // 等整片挖完再统一捡。原逻辑掉落优先级排在 _miningPos 粘性之后 → 挖完
            // 一根原木, getClosestTo 被"下一个还没挖完的原木"粘住, 这根越滚越不捡。
            // 木头/床: 掉落最优先, 脚下掉了马上捡再继续挖。
            // (2026-09-15 批采) 批采续砍期间掉落物让位: 砍满或 3 格内无下一根才轮到捡。
            if (isCollectDropsImmediately() && closestDrop.isPresent() && woodBatchNext.isEmpty()) {
                return closestDrop.map(Object.class::cast);
            }
            // [2026-08-22 挖矿目标粘性] 正在挖的方块还没挖完时绝不换目标:
            // 铁矿脉多块距离相近, 挖掉一块(或掉落物刷新)后"最近目标"切换
            // → DestroyBlockTask 重启 → 破坏进度清零, 每 1-2 秒换一个目标
            // "挖了几个还没挖完又转头" (tasks.log 11:36:57 t=16304-16748
            // "Trying out NEW pursuit"/"Retrying old heuristic" 交替 7 个
            // 不同目标)。粘住 _miningPos 直到它失效(挖掉/不可挖/黑名单)。
            // (2026-09-15 批采) "已变空气就清 _miningPos + 记破坏时刻/自挖记忆"的
            // 记账已前置到本方法开头 (先于掉落优先返回, 否则批采计数漏记),
            // 这里只保留粘性返回; 空气/黑名单/不可挖都由 isValid 兜底放行。
            if (_miningPos != null && !_blacklist.contains(_miningPos) && isValid(mod, _miningPos)) {
                return Optional.of(_miningPos);
            }
            // =========================================================================
            // (2026-09-21 21:56 用户定案"先向下挖4格深, 人一定跟着下去, 再水平获取最近的方块")
            // 收集材料族两段式获取 (矿/木/床/人造物/贪婪模式不适用):
            //   ① 竖井段: 本任务实例首次采石时先垂直挖 4 格 —— 每步目标 = 脚下方块,
            //      挖掉后 bot 随之落下(人跟着下去), 4 步到底;
            //   ② 水平段: 到底后恢复正常"最近方块"选择, 脚下层候选被 1e9 罚距
            //      (见 scaffoldSelectionPenalty / getNearFieldOreAt), 平层取料。
            // 安全闸(不过 → 不打井直接水平): 未落地不决策; 竖井 4 格不连续/不可挖/
            // 邻液体/落点无底(悬空柱、岩浆上空、浮空团块 —— 0919 悬空下界岩教训)。
            // =========================================================================
            if (!_greedy && !_horizontalMode && isMaterialCollectionTarget()
                    && !isTargetingWood() && !isTargetingBed() && !isTargetingSurfaceResource()) {
                int feetY = mod.getPlayer().getBlockPos().getY();
                if (_shaftBaseY == Integer.MIN_VALUE) {
                    // (2026-09-22 修复激活被掉落物饿死) 旧条件 closestDrop.isEmpty():
                    // 泥土任务是"挖一块→捡一块"交替, 掉落物几乎恒在场 → 整个收集期
                    // 没有干净 tick, 竖井从未评估 (0922-091750 实锤阶梯坑复发)。
                    // 掉落物优先级由更早的 early-return 保证, 这里不再看掉落物。
                    if (!mod.getPlayer().isOnGround()) {
                        // 落地前不决策不打井, 让既有流程接住这几 tick, 落地后再评估。
                    } else {
                        String shaftReason = isSafeShaftColumn(mod, mod.getPlayer().getBlockPos());
                        if (shaftReason == null) {
                            _shaftBaseY = feetY;
                            Debug.logMessage("[MINE-SHAFT] activate at " + mod.getPlayer().getBlockPos().toShortString()
                                    + ": dig down " + SHAFT_DEPTH + ", then horizontal nearest");
                        } else {
                            _horizontalMode = true;
                            Debug.logMessage("[MINE-SHAFT] skip (unsafe column at "
                                    + mod.getPlayer().getBlockPos().toShortString() + ": " + shaftReason
                                    + "), horizontal nearest only");
                        }
                    }
                }
                if (_shaftBaseY != Integer.MIN_VALUE) {
                    if (_shaftBaseY - feetY >= SHAFT_DEPTH) {
                        _horizontalMode = true;
                        Debug.logMessage("[MINE-SHAFT] bottom reached (y=" + feetY + "), switch to horizontal nearest");
                    } else {
                        BlockPos below = mod.getPlayer().getBlockPos().down(1);
                        // 每步再验: 漂位/邻液体/不可挖当场放弃竖井转水平, 不硬挖。
                        // (2026-09-22) 不再要求 ∈ 目标块 —— 竖井是下降手段, 草/石照挖
                        // (isValid 已为竖井步放行, 见 isValid 注释)。
                        if (mod.getChunkTracker().isChunkLoaded(below) && WorldHelper.canBreak(mod, below)
                                && !WorldHelper.isAdjacentToLiquid(mod, below)) {
                            return Optional.of((Object) below);
                        }
                        Debug.logMessage("[MINE-SHAFT] abort at " + below.toShortString() + " (unsafe step), switch to horizontal");
                        _horizontalMode = true;
                    }
                }
            }
            // (2026-08-28 近场即时, 用户) 刚挖完这块, 立刻扫它紧贴的邻位:
            // 紧贴刚挖穿的连位矿下一 tick 就被看到并续挖 — 完全不需要缓存/节流,
            // 也不受挖矿位置远近影响 (锚定的是矿, 不是玩家)。
            // (2026-09-22 邻接口径收编, 用户定案"豁免=面相邻6块, 都改, 口径一致")
            // 原 (2026-09-13) 半径立方体 (贪婪 5x5x5 / 非贪婪 3x3x3) 撤销 —— 立方体里
            // 棱/角邻的矿可整块埋在石头里也被当候选 (透视嫌疑)。现统一为面相邻 6 块
            // (getNearFieldOreAt), 与 hasNearFieldTarget 同口径。
            // (2026-09-15 链挖锚点修复) 原: getNearFieldOreAt(mod, _miningPos)
            // —— _miningPos 刚被清空恒为 null, 链挖断链。改用 getChainAnchor()。
            // (2026-09-15 批采) 木头批采转入拾取阶段 (砍满/背包达标) 时跳过链挖,
            // 防止掉落物登记 1-2 tick 空窗内链挖续砍第 4 根 (违背"每挖3个拾取一次")。
            Optional<BlockPos> nearField = (isBatchTarget() && _woodBatchBroken > 0 && !woodBatchActive)
                    ? Optional.empty()
                    : getNearFieldOreAt(mod, getChainAnchor());
            if (nearField.isPresent()) {
                return nearField.map(Object.class::cast);
            }
            if (_greedy) {
                // (2026-08-28 贪心=纯贴身) 先认玩家近身紧贴的矿 (起始/链尾)。
                Optional<BlockPos> nearMine = getNearFieldOreAt(mod, mod.getPlayer().getBlockPos());
                if (nearMine.isPresent()) {
                    return nearMine.map(Object.class::cast);
                }
                // (2026-09-01 触发放宽后修复, 用户"贪心挖目标矿的3x3x3") 贪心触发能看见
                // GREEDY_TARGET_RANGE 内(8格)的可见矿, 但上面只认贴身 3x3x3 → 远处可见矿
                // 无起始目标 → "Waiting for calculations" 空转卡住 (日志 09:13 实锤)。
                // 补: 找范围内最近的可感知目标矿作起始目标, 导航过去后由 _miningPos 的
                // 3x3x3 链挖处理 (第 632 行, 正确设计)。FOV+LOS 门控, 不透视。
                Optional<BlockPos> visibleTarget = findVisibleGreedyTarget(mod);
                if (visibleTarget.isPresent()) {
                    return visibleTarget.map(Object.class::cast);
                }
                // (2026-08-31 贪心挖完必捡掉落, 用户实测"挖完没拾取") 原 greedy 分支没矿就
                // 直接返回空 → 近场没矿且 shouldAvoidPickingUp=true(父 ResourceTask 不兜底捡),
                // 掉落物一直不捡。挖完一处必须先捡走掉落物, 再找下一处。
                if (closestDrop.isPresent()) {
                    return closestDrop.map(Object.class::cast);
                }
                return Optional.empty();
            }
            // (2026-08-30 遗留容器近场直扫, 用户"熔炉在眼前还挖洞") 熔炉/工作台/
            // 床/羊毛这类【人造/地表】目标, 用下面矿石的 FOV+LOS 感知(requireFov/
            // requireLos)会因"没看向它/被遮挡/缓存空"丢掉眼前遗留熔炉 → 掉进
            // MineDownExplore 挖隧道探矿、手挖 (用户实测 21:35 收熔炉去挖地道、
            // 不做镐)。人类看到眼前可拆方块就直接收。这里在【玩家近场】直扫世界,
            // 只要 chunk 已加载且世界验证为目标块即可, 不看 FOV/LOS——距玩家限 8 格,
            // 目标又为人工方块, 不算透视远处自然矿。
            if (isTargetingSurfaceResource()) {
                Optional<BlockPos> nearbySurface = pickNearestNearbySurface(mod, pos);
                if (nearbySurface.isPresent()) {
                    return nearbySurface.map(Object.class::cast);
                }
            }
            boolean satisfied = StorageHelper.itemTargetsMetInventoryNoCursor(mod, _targets);
            // (2026-08-31 床/羊毛等 surface 目标无需面向, 用户"又卡住不动") 床在
            // VisibilityHelper 里是【无条件可感知】(穿墙, 床保险)。但这里 requireFov
            // 仅因"背包还没达标"就要求面向床 → bot 站在村庄里背对/侧对的床永远不
            // 满足 FOV → getClosestTo 无目标, 而 hasNearbyMineableTarget 却判"16 格内
            // 有床" → 一个要面向、一个不面向互斥 → 卡死在 "Mining or Collecting"
            // (用户连续 3 轮实测)。surface 资源本就是"看到就有", 不该因未达标再要求
            // FOV。矿石仍保持 FOV(防透视), 不受影响。
            boolean bedTarget = isTargetingBed();
            boolean requireFov = !satisfied && !bedTarget;
            // (2026-08-31 白天感知到床就应收进背包, 用户"感知到床却白天不去收, 晚上才去床")
            // CollectBedTask 用 96 格判定"有床就切挖床", 但这里之前 cache/world 只搜
            // 24 格 → 床在 24~96 格之间(村庄床常 50~90 格)时"切了挖床却 lock 不到" →
            // 卡在 "Mining or Collecting" 空转, 直到晚上被 Sleeping 链抢占才去碰床。
            // 仅对【床】目标放宽搜索到 96 + 免 FOV, 与 CollectBed 门控一致: 白天感知到的
            // 床直接锁到并走过去拆进背包(真正"持有床")。
            // (2026-09-09 木头感知半径 24→64, 用户"我都看到树了它还没看到木头"实测:
            // 21:05-21:10 开阔地 30-45m 外的树在视野里, bot 瞎逛 4 分钟不砍) 24 格
            // 感知半径意味着 30m 外的树对 bot 不存在; 配合 VisibilityHelper 树叶透明
            // LOS(2026-09-09) 才能真正锁到远树。仍要求 FOV+LOS, 不透视。
            // (2026-09-17) 木头 64 → WOOD_SEARCH_RANGE(128): 64 就是同类问题撞到的
            // 新天花板 (见常量注释), 不再是"看不到"而是"看得见但不要"。
            int cacheRange = bedTarget ? 96 : (isTargetingWood() ? (int) WOOD_SEARCH_RANGE : 24);
            // (2026-09-01 防透视回归修复) 主线远场恢复 LOS 门控 (用户原则: 主线
            // 发现矿需 FOV+LOS)。上一轮性能修复在这里传了 requireLos=false —
            // BlockTracker 缓存是全 chunk 扫描数据(天然透视), LOS 一关, 被岩石
            // 完全包住的矿也被锁定直挖 (用户实测挖铁矿透视)。性能由
            // pickNearestCacheOre 内部三重护栏保障, 不再靠关 LOS。
            Optional<BlockPos> closestBlock = pickNearestCacheOre(mod, pos, cacheRange, requireFov, false);
            if (closestBlock.isEmpty()) {
                closestBlock = pickNearestWorldOre(mod, pos, bedTarget ? 48 : 24, requireFov);
            }

            // NOTE: 不再调取 WorldMemory 记忆中的矿位置做目标（2026-08-21
            // 用户原则：优先选用眼前更近的目标材料，舍近求远不可取）。
            // 记忆位置既不验证矿是否还在（bot 自己挖掉的旧位置会造成空跑），
            // 也会在视野内没有任何可见矿时把 bot 拉去几十格外。目标选择只
            // 信当前追踪缓存（BlockTracker，带 FOV 感知 + 实时校验）。

            // We can't mine right now.
            if (mod.getExtraBaritoneSettings().isInteractionPaused()) {
                return closestDrop.map(Object.class::cast);
            }

            // (2026-08-23 掉落物收集策略) 用户设计: "把短范围内可见的矿全部
            // 挖完, 挖完后再把掉落物都捡完, 然后才去下一处矿"。旧逻辑
            // dropSq(+5) vs blockSq 竞争 → 挖完一块(掉落物在脚下 dropSq≈5)若
            // 附近有另一块更近的矿(blockSq<5)就去挖另一块 → "不把这一处捡完
            // 就跑" (用户实测: 感知两处矿, 挖完这处不捡掉落就去挖另一处)。
            // 新优先级: ① 短范围内(MINE_EVERYTHING_RANGE=16)还有可挖矿 →
            //    优先挖完本处(不捡掉落, 掉落留给挖完后统一捡);
            //   ② 短范围内矿挖完了 → 捡掉落物;
            //   ③ 掉落也捡完 → 才去下一处(更远)矿。
            Optional<BlockPos> nearbyMine = pickNearestCacheOre(mod, pos, MINE_EVERYTHING_RANGE, false, true);
            // (2026-09-15 判据统一, 104100 实锤) 16 格 gate 走 BlockTracker 感知缓存:
            // 下界隧道壁里的连位矿从未 LOS 入册 → 缓存恒空 → "还有矿"误判 false →
            // 矿脉挖到一半被 Pickup 抢占弃坑 (矿脉 35,80/-79/-78,-36/-37 挖到第二格
            // 被抢)。与链挖同口径兜底: 围绕链挖锚点面相邻 6 块直扫 (便宜)。
            // 扫完真没矿才算"本处挖完", 才轮到掉落物。
            if (nearbyMine.isEmpty()) {
                // (2026-09-15 批采) 拾取阶段同样跳过锚点兜底扫, 理由同上。
                if (!(isBatchTarget() && _woodBatchBroken > 0 && !woodBatchActive)) {
                    nearbyMine = getNearFieldOreAt(mod, getChainAnchor());
                }
            }
            if (isCollectDropsImmediately()) {
                // (2026-09-15 批采续砍优先) 批采进行中先砍完邻位再捡 (与开头 early
                // gate 同源的 woodBatchNext; 走到这里说明半径 1 的链挖没接上,
                // 由批采口径 ±3 格补位 — 用户"3 格范围内还有别的原木就继续砍")。
                if (woodBatchNext.isPresent()) {
                    if (!woodBatchNext.get().equals(_woodBatchLastDiagPos)) {
                        _woodBatchLastDiagPos = woodBatchNext.get();
                        Debug.logMessage("[WOOD-BATCH] mine-first #" + (_woodBatchBroken + 1)
                                + "/" + WOOD_BATCH_MAX + " -> " + _woodBatchLastDiagPos.toShortString());
                    }
                    return woodBatchNext.map(Object.class::cast);
                }
                // (2026-08-27 床边挖边捡, 用户) 上面"挖完本处再统一捡"的
                // 优先级是给挖矿的 (矿脉落地物先放着, 挖完一块还有下一块)。
                // 木头/床不一样: 木树高, 一次砍倒掉落物散一地; 床拆一张只掉
                // 一个。所以对这两类目标, 掉落物优先级最高 — 脚下掉了就
                // 立刻捡起再继续, 不直奔下一个方方块。
                if (closestDrop.isPresent()) {
                    return closestDrop.map(Object.class::cast);
                }
                // (2026-09-16 批满挂起) 批采目标挖满 BATCH_MAX 个、掉落物尚未回册的
                // 空窗内, 16 格 gate (nearbyMine) 对无限介质恒真, 会立刻送上下一批
                // 挖掘 → "挖满 3 个 → 掉落还没登记 → 还有矿 → 继续挖"死循环。这里
                // 挂起该 gate, 让下一 tick 掉落物回册后由上面的 closestDrop 接手。
                if (!batchFullPendingPickup() && nearbyMine.isPresent()) {
                    return nearbyMine.map(Object.class::cast);
                }
                return closestBlock.map(Object.class::cast);
            }
            if (nearbyMine.isPresent()) {
                return nearbyMine.map(Object.class::cast);
            }
            if (closestDrop.isPresent()) {
                return closestDrop.map(Object.class::cast);
            }
            // (2026-09-02 记忆兜底导航, 用户"杀完怪该回去挖记忆中的钻石") 眼前既无
            // 可感知矿、又无掉落、材料仍未达标, 且不是地表人造目标(床/木/羊毛等
            // 有各自的近场直达)时, 若世界记忆里有【本窗口内真看过(LOS)**的该资源矿**,
            // 就把它当导航目标走回去, 而不是立刻去挖隧道/空转。防透视不破: 目标
            // 必须是 LOS 真感知过才入选; 走过去途中若是被岩石包住的矿, 依旧挖不开
            // (到点真感知到才由正常挖掘接手), 只是"人类式回到记得的位置", 不隔墙。
            // 只要眼前有任何可感知矿, 上面 closestBlock/nearbyMine 已优先返回, 这里
            // 仅兜底, 不抢近目标 (保留"舍近求远不可取", 2026-08-21 原则)。
            if (closestBlock.isEmpty() && !isTargetingSurfaceResource()) {
                Optional<BlockPos> memoryNav = pickNearestMemoryOre(mod, pos);
                if (memoryNav.isPresent()) {
                    Debug.logMessage("[MineMem] no perceivable ore -> navigating to recently-seen ore "
                            + memoryNav.get().toShortString() + " (dist=" + (int) Math.sqrt(memoryNav.get().getSquaredDistance(pos)) + ")");
                    return memoryNav.map(Object.class::cast);
                }
            }
            // (2026-09-01 trace) 目标选择结果: 没挖的紧贴矿/掉落/远处矿, 记录
            // bot 最终将去处理的方块——区分木头(wood)/石头(cobble)高发地。
            if (closestBlock.isEmpty() && closestDrop.isEmpty() && !satisfied) {
                String tag = isTargetingSurfaceResource() ? "wood" : "cobble";
                Debug.logTrace(tag, "getClosestTo 无可见目标但材料未满足 (satisfied=" + satisfied
                        + "), surfaceTarget=" + isTargetingSurfaceResource()
                        + ", bed=" + isTargetingBed() + ", 将走 wander/空转");
            }
            return closestBlock.map(Object.class::cast);
        }

        @Override
        protected Vec3d getOriginPos(AltoClef mod) {
            return mod.getPlayer().getPos();
        }

        /**
         * (2026-09-02 记忆兜底) 从"最近真看过(LOS)的矿"里挑一个作为导航回溯目标。
         * 仅当眼前无任何可感知矿时才调用 (见 getClosestTo)。约束: 必须在本窗口内
         * 被 LOS 真感知过 (记录在 _seenOreTick), 且直线距离 ≤ MEMORY_NAV_RANGE,
         * 未进黑名单、可破坏。返回位置后由上层 GetToBlock 导航过去, 到点真感知到矿
         * 才由正常挖掘接手; 若矿已被挖掉/不可达, 会走 _blacklist 自我淘汰, 不会死循环
         * 扑向同一空位。顺带修剪过期记忆, 防 _seenOreTick 无限膨胀。
         */
        private Optional<BlockPos> pickNearestMemoryOre(AltoClef mod, Vec3d fromPos) {
            long nowTick = WorldHelper.getTicks();
            if (_seenOreTick.isEmpty()) return Optional.empty();
            double maxSq = MEMORY_NAV_RANGE * MEMORY_NAV_RANGE;
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            // (2026-09-05 崩溃修复, 19:29 ConcurrentModificationException 实锤) 上一版
            // 在本循环体内直接 _seenOreTick.remove(p) —— 边遍历 HashMap 边改结构必崩。
            // 改用显式 iterator, 删除走 it.remove(), 遍历/删改同源安全。
            for (java.util.Iterator<java.util.Map.Entry<BlockPos, Long>> it =
                 _seenOreTick.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<BlockPos, Long> e = it.next();
                BlockPos p = e.getKey();
                long seen = e.getValue();
                if (nowTick - seen >= MEMORY_NAV_LIFETIME_TICKS) {
                    it.remove(); // 过期记忆当场清, 不再攒 pruned 标记延迟删
                    continue;
                }
                if (_blacklist.contains(p)) continue;
                // (2026-09-30 导航口径≠开挖口径) 原来这里用 canBreak 整体把关,
                // 但 canBreak 内含 canReach/plausibleToBreak (baritone 此刻可达性
                // 快照) —— 目标在岩石后面时该快照瞬态翻 false ⇒ chooser 某一拍
                // 突然为空 → wander 分支的 Look-around/探索把【在途的记忆位导航】
                // 枪毙 (20260930 18:38 现场: Destroy block@-241,32,252 走到 24s
                // 被 Look around 顶掉, 之后再没回去)。记忆回溯的语义本来就是
                // "人类式走回去再看", 到点才由正常感知/挖掘接手, 硬岩遮挡不影响
                // 导航合法性。这里只挡: 不可破坏(基岩) + 保护方块 + 液体邻接;
                // 真到不了时既有失败路径 (requestBlockUnreachable + _blacklist,
                // 见 onTick "Still failed to mine") 仍会淘汰, 不会死循环扑空。
                if (!canNavigateToMemoryOre(mod, p)) continue;
                // (2026-09-05 冻结真因) 记忆导航此前不校验方块还在不在 —— canBreak
                // 对空气(硬度0)照样通过, 已挖掉的空气位被当"记忆中的矿"反复选中;
                // 以前靠 DestroyBlockTask 挖 6 秒超时进黑名单才能逃出, 自空气早退
                // (e3087744)后不再超时 → 父任务 isValid 否决 → 重选 → 还是它 → 永久
                // 站死 (19:17 实测)。补上与其他选择器一致的方块状态校验: 区块已加载
                // 时必须仍是目标方块; 未加载才允许凭记忆导航过去。
                if (mod.getChunkTracker().isChunkLoaded(p)) {
                    Block actual = mod.getWorld().getBlockState(p).getBlock();
                    boolean matches = false;
                    for (Block target : _blocks) {
                        if (actual == target) { matches = true; break; }
                    }
                    if (!matches) {
                        // 已挖掉: 连记忆一起清掉 (iterator.remove, 遍历中安全), 不再回头。
                        it.remove();
                        continue;
                    }
                }
                double sq = p.getSquaredDistance(fromPos);
                if (sq >= maxSq) continue;
                if (sq < bestSq) { bestSq = sq; best = p; }
            }
            if (_seenOreTick.size() > 128) {
                // (2026-09-05) 过期项已在上面 iterator 遍历时清掉; 这里仅保底防膨胀。
                _seenOreTick.entrySet().removeIf(x -> nowTick - x.getValue() >= MEMORY_NAV_LIFETIME_TICKS);
            }
            if (best != null) return Optional.of(best);

            // (2026-09-22 跨实例记忆兜底, 20260922-114142 实锤"钻石贪婪挖被抢占后矿脉丢失",
            // 用户"我不会放过的") 根因: _seenOreTick 是【实例字段】, 钻石收集链上同时存在
            // 多个 MineAndCollectTask 实例 (CollectVisibleOresTask 持有的贪婪 x1 /
            // Collect Recipe Resources 持有的 x10)。11:44:16 贪婪 x1 实例真感知(FOV+LOS)
            // 锁定 47,-41,-34 矿脉正垫柱扑矿; 11:44:19 Get catalogued 抢占杀掉 x1, 接手
            // 的 x10 实例自己从未感知过那颗矿 (边界 LOS 站位), _seenOreTick 为空 → 实例
            // 记忆兜底无记忆可回 → 落到挖隧道分支弃矿脉而去, 到用户手动停止再没回头。
            // 2026-09-02 那次修复解决的是"同实例内战斗打断" (注释原文"杀完怪该回去挖
            // 记忆中的钻石"), 没覆盖跨实例交接 — 本兜底补上这一层。
            // 修法: 实例记忆落空时退接 WorldMemory._oreMemory — 全局按维度记忆的
            // "真看见过(isBlockVisible)的矿", 天然跨实例存活。不违反两条既有原则:
            //   不透视 —— WorldMemory 只记 isBlockVisible 通过的矿, 没看过的不会出现;
            //   舍近求远不可取 (2026-08-21 用户原则) —— 这里仅在实例记忆为空、挖隧道
            //   之前的最后兜底 (原记忆兜底语义不变), 不参与主目标竞争。
            // 护栏与实例记忆路径同口径: 距离 ≤MEMORY_NAV_RANGE / 黑名单 /
            // 导航口径过滤 (canNavigateToMemoryOre, 不含瞬态 canReach —
            // 2026-09-30 同上述"导航口径≠开挖口径") / chunk 已加载时必须仍是
            // 目标块 (已挖掉的空位直接跳过, 不扑空位)。
            // 只读不改 WorldMemory (结构不归本任务管, 过期位由其自身机制维护)。
            {
                BlockPos gBest = null;
                double gBestSq = Double.POSITIVE_INFINITY;
                for (BlockPos p : mod.getWorldMemory().getOreLocations(_blocks)) {
                    if (_blacklist.contains(p)) continue;
                    if (!canNavigateToMemoryOre(mod, p)) continue;
                    if (mod.getChunkTracker().isChunkLoaded(p)) {
                        Block actual = mod.getWorld().getBlockState(p).getBlock();
                        boolean matches = false;
                        for (Block target : _blocks) {
                            if (actual == target) { matches = true; break; }
                        }
                        if (!matches) continue;
                    }
                    double sq = p.getSquaredDistance(fromPos);
                    if (sq >= maxSq) continue;
                    if (sq < gBestSq) { gBestSq = sq; gBest = p; }
                }
                if (gBest != null) {
                    Debug.logMessage("[MineMem-Global] instance memory empty -> navigating to globally-seen ore "
                            + gBest.toShortString() + " (dist=" + (int) Math.sqrt(gBestSq) + ")");
                    return Optional.of(gBest);
                }
            }
            // (2026-09-26 15:26 用户否决第三层 BlockTracker 缓存导航) 曾把 chunk 扫描
            // 登记的已知矿位当导航目标 —— 但那些位置 bot 从未看见过, 导航=用透视信息
            // 做决策, 违反防透视原则, 已撤销。记忆导航只认【真看见过】的两层
            // (_seenOreTick / WorldMemory); 重启后记忆为空就诚实 wander, 看见什么记什么。
            return Optional.empty();
        }

        /**
         * (2026-09-30) 记忆导航目标的过滤口径 (与 canBreak 的"现在就开挖"口径分离)。
         *
         * <p>canBreak 回答的是"此刻够不够得着、能不能挖" —— 内含
         * {@code canReach}/{@code plausibleToBreak} 两个 baritone 可达性【瞬时快照】,
         * 目标隔岩石/寻路未收敛时随时翻 false。拿它当记忆导航的选目标硬门, 后果是
         * chooser 某一拍突然为空 → wander 分支 (Look-around/挖隧道探索) 把在途的
         * 记忆位导航顶掉 (20260930 18:38 实锤)。记忆回溯的语义本来就是"人类式走回去
         * 再看" (2026-09-02 注释原文), 到点才由正常感知/挖掘接手, 岩石遮挡不影响导航
         * 合法性 —— 与防透视不冲突: 能进这里的位都经 LOS 真看见过。
         *
         * <p>这里只挡三类 (与 canBreak 的静态部分一致 + 液体护栏):
         * ① 硬度 <0 (基岩等不可破坏 — 走过去也挖不开, 不值得专程跑);
         * ② shouldAvoidBreaking (黑曜石门框等受保护方块);
         * ③ 邻接液体 (岩浆隔断位: 导航会反复撞 CANT_REACH 且有安全风险,
         *    与 {@code findFovRejectedOre} 的液体过滤同口径)。
         *
         * <p>真到不了的目标仍有淘汰出口: 挖掘失败路径 requestBlockUnreachable +
         * {@code _blacklist} (见 onTick "Still failed to mine"), 不会死循环扑空。
         */
        private boolean canNavigateToMemoryOre(AltoClef mod, BlockPos p) {
            return mod.getWorld().getBlockState(p).getHardness(mod.getWorld(), p) >= 0
                    && !mod.getExtraBaritoneSettings().shouldAvoidBreaking(p)
                    && !WorldHelper.isAdjacentToLiquid(mod, p);
        }

        /**
         * (2026-08-28 统一选择器·缓存) 从 BlockTracker 缓存取 maxRange 内最近的可挖
         * 感知矿。收敛取代旧 getNearestKnownOre / getNearestTracking / getNearestMineInRange。
         *
         * (2026-09-01 防透视回归修复 + 性能护栏) 用户实测挖铁矿透视: 上一轮性能修复
         * 把主线远场 requireLos 传了 false — BlockTracker 缓存本身是全 chunk 扫描
         * 数据 (天然透视), LOS 是唯一防透视闸门, 一关就被岩石完全包住的矿也会被
         * 锁定直挖。恢复主线 FOV+LOS 双门控 (用户原则: 主线发现矿需 FOV 和 LOS),
         * 同时用三重护栏防上一轮的 500ms/tick 卡顿回归:
         *   ① 廉价过滤先行 (距离/FOV/canBreak/黑名单/液体/500ms 重校验), 只对幸存
         *      候选跑 LOS, 且按距离升序 — 命中首个成功即返回 (最近可感知矿);
         *   ② LOS 失败结果缓存 500ms (LOS_FAIL_CACHE_MS), 短期内不重射同一块;
         *   ③ 每次调用 LOS 射线上限 MAX_LOS_RAYCASTS_PER_CALL 条, 超限本 tick
         *      返回空, 失败缓存让下一 tick 从断点续查。
         * 被岩石包裹的矿由 isBlockPerceivable 内部 isBuriedOre 拒绝 — 不再"直挖
         * 看不见的矿", 而是走 MineDownExplore 挖隧道, 挖开岩层自然暴露后续挖
         * (3x3x3 近场链挖只需一处暴露就能吃掉整条矿脉, 不会因 LOS 卡住主线)。
         *
         * useMemory=true (近场 16 格扫尾): 带"近矿记忆"(5s 内真正感知过, 中心点
         * LOS 抖动的只露一面矿), 感知逻辑与主线一致 (LOS 必过), 仅多 5s 记忆宽限。
         */
        // (2026-09-05 移除 CACHE_BLOCK_REVALIDATE_MS 500ms 节流) 方块状态重校验
        // 改为每 tick 必做 (见 pickNearestCacheOre 两处注释): 节流窗口内已挖掉的
        // 空气位会绕过校验被选中, 是"挖完仍挖卡几秒"的源头之一。getBlockState
        // 廉价, 性能护栏保留在 LOS 射线侧不变。
        // (2026-09-01 LOS 护栏) 见上方法注释: 失败缓存 TTL / 每次射线上限 / 缓存体积上限。
        private static final long LOS_FAIL_CACHE_MS = 500;
        private static final int MAX_LOS_RAYCASTS_PER_CALL = 16;
        private static final int LOS_FAIL_CACHE_MAX_SIZE = 256;
        private final java.util.Map<BlockPos, Long> _losFailCache = new java.util.HashMap<>();

        // ---- (20260915 挖金猪灵闸, 用户"挖金附近猪灵超过1只就不挖, 3个拿剑的打不过") ----
        // 挖下界金矿/镶金黑石会激怒 16 格内猪灵 (vanilla 挖金激怒半径)。激怒 2+ 只
        // (尤其持剑) 是必输局 (20260915-123005 实锤: 3 只围殴 4 秒掉 16 血)。闸门:
        // 目标金块 16 格内猪灵(成年) > 1 只 → 该块本轮不可选。全部候选被闸 →
        // getClosestTo 落空 → 框架自然走 wander 探索 → bot 离开猪灵密集区。
        // 计数口径"知道它在那" = 现在感知到 OR 5s 内刚看见 OR 已激怒指向 bot
        // (2026-09-15 用户: 模仿人类, 不拿上帝视角全知计数; 20261005 用户拍板修
        // 盲区: 纯"当前 FOV"口径在仰望挖矿时把贴脸猪灵漏光 → 挖断金矿激怒全场,
        // 20261005-133105 实锤, 且激怒猪灵本身不可能"没看见", 见 isPiglinKnown)。
        private static final double PIGLIN_ANGER_RADIUS = 16.0;
        private static final int PIGLIN_MINE_MAX_COUNT = 1;
        // (20261005) "刚看见过"记忆窗口 (100t = 5s): 视角转向矿面期间, 近期瞥见的
        // 猪灵仍计入闸门, 防"仰望挖矿 → 猪灵出 FOV → 闸门放行"盲区 (见 isPiglinKnown)。
        private static final int PIGLIN_PERCEPTION_MEMORY_TICKS = 100;
        private static final Set<Block> PIGLIN_ANGERING_BLOCKS = Set.of(
                Blocks.NETHER_GOLD_ORE, Blocks.GILDED_BLACKSTONE);
        // [GOLD-GATE] 诊断节流。
        private long _lastPiglinGateLogMs = 0;

        // 感知判定按 tick 缓存: isEntityVisible 只依赖玩家视角与猪灵自身位置,
        // 与"哪块矿"无关 —— 同一 tick 内对同一只猪灵结果恒定。不缓存的话
        // "每候选矿块 × 每猪灵"重复射线, 密集金矿+多猪灵时最坏上百条/tick。
        private long _piglinVisCacheTick = -1;
        private final Map<UUID, Boolean> _piglinVisCache = new HashMap<>();
        // (20261005 感知记忆) "近期看见过"的猪灵记忆 (UUID -> 最后可见 tick)。
        private final Map<UUID, Long> _piglinLastSeenTick = new HashMap<>();

        private boolean isPiglinPerceived(AltoClef mod, LivingEntity pig) {
            long tick = WorldHelper.getTicks();
            if (tick != _piglinVisCacheTick) {
                _piglinVisCacheTick = tick;
                _piglinVisCache.clear();
                // 记忆表顺手修剪 (可见过的猪灵数量少, 开销可忽略)
                _piglinLastSeenTick.values()
                        .removeIf(t -> tick - t > PIGLIN_PERCEPTION_MEMORY_TICKS * 2);
            }
            Boolean cached = _piglinVisCache.get(pig.getUuid());
            if (cached == null) {
                cached = VisibilityHelper.isEntityVisible(mod, pig);
                _piglinVisCache.put(pig.getUuid(), cached);
            }
            if (cached) {
                _piglinLastSeenTick.put(pig.getUuid(), tick);
            }
            return cached;
        }

        /**
         * (20261005 闸门盲区修复, 20261005-133105 场次) 挖金猪灵闸的计数口径:
         * 「现在看得见 OR 5 秒内刚看见过 OR 已激怒指向 bot」三选一即算"知道它
         * 在"。原口径只数【当前 FOV 内】的猪灵 —— bot 仰望头顶金矿 (Ascent/垫柱)
         * 时, 脚下 1~8 格的猪灵全在 FOV 外 → 闸门计 0 → 放行 → 矿被挖断 → 16 格
         * 内猪灵全体激怒 (13:31:02 挖 -154,98,218 实锤: GOLD-GATE 每秒打 skip
         * 却照挖, ToolEquip×3/Ascent×2 = 视角来回摆导致放行/拒绝横跳)。
         * "刚看过"记忆模仿人类周边视觉: 2 秒前瞥见的贴脸猪灵不会因为低头挖矿
         * 就从认知里消失。激怒猪灵无视 FOV (正在打你的东西不存在"没看见")。
         */
        private boolean isPiglinKnown(AltoClef mod, LivingEntity pig) {
            if (isPiglinPerceived(mod, pig)) return true;
            if (EntityHelper.isAngryAtPlayer(mod, pig)) return true;
            Long lastSeen = _piglinLastSeenTick.get(pig.getUuid());
            return lastSeen != null
                    && WorldHelper.getTicks() - lastSeen <= PIGLIN_PERCEPTION_MEMORY_TICKS;
        }

        /**
         * 挖金猪灵闸: 该方块是挖了会激怒猪灵的金矿, 且其 {@link #PIGLIN_ANGER_RADIUS}
         * (原版挖金激怒半径) 内【被 bot 感知到 (可见)】的成年猪灵+猪灵蛮兵数量 >
         * {@link #PIGLIN_MINE_MAX_COUNT} → 返回 true (本轮不可挖)。僵尸猪灵不激怒
         * 于挖金, 不计。开销: 仅对通过既有过滤的候选矿块执行, 一次实体表遍历
         * (仅对近距候选跑 LOS 射线)。
         */
        private boolean piglinDensityGated(AltoClef mod, BlockPos p) {
            Block block = mod.getWorld().getBlockState(p).getBlock();
            if (!PIGLIN_ANGERING_BLOCKS.contains(block)) return false;
            int count = 0;
            double radiusSq = PIGLIN_ANGER_RADIUS * PIGLIN_ANGER_RADIUS;
            Vec3d center = Vec3d.ofCenter(p);
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            for (PiglinEntity pig : mod.getEntityTracker().getTrackedEntities(PiglinEntity.class)) {
                if (pig.isAlive() && !pig.isBaby() && pig.squaredDistanceTo(center) <= radiusSq
                        && isPiglinKnown(mod, pig)) count++;
            }
            for (PiglinBruteEntity brute : mod.getEntityTracker().getTrackedEntities(PiglinBruteEntity.class)) {
                if (brute.isAlive() && brute.squaredDistanceTo(center) <= radiusSq
                        && isPiglinKnown(mod, brute)) count++;
            }
            }
            if (count <= PIGLIN_MINE_MAX_COUNT) return false;
            long now = System.currentTimeMillis();
            if (now - _lastPiglinGateLogMs > 1000) {
                _lastPiglinGateLogMs = now;
                Debug.logMessage("[GOLD-GATE] skip mining " + block.getName().getString()
                        + " at " + p.toShortString() + ": " + count + " piglins within 16 (max " + PIGLIN_MINE_MAX_COUNT + ")");
                Debug.logToFile("altoclef_diag.log", "[GOLD-GATE] skip " + p.toShortString() + " piglins=" + count);
            }
            return true;
        }


        private Optional<BlockPos> pickNearestCacheOre(AltoClef mod, Vec3d fromPos, double maxRange, boolean requireFov, boolean useMemory) {
            List<BlockPos> known = mod.getBlockTracker().getKnownLocations(_blocks);
            if (known.isEmpty()) return Optional.empty();
            double maxSq = maxRange * maxRange;
            long nowTick = WorldHelper.getTicks();
            long nowMs = System.currentTimeMillis();

            // ---- 近场记忆分支: 感知(LOS) + 5s 记忆宽限, 判定语义与原实现一致 ----
            if (useMemory) {
                BlockPos best = null;
                double bestDistSq = Double.POSITIVE_INFINITY;
                for (BlockPos p : known) {
                    // 范围外的块不可能被本次调用选中, 先过滤再跑 LOS 射线 (纯性能
                    // 优化, 不改判定: 幸存块的全部检查与原实现相同)。
                    if (p.getSquaredDistance(fromPos) >= maxSq) continue;
                    boolean perceivable = VisibilityHelper.isBlockPerceivable(mod, p);
                    if (perceivable) {
                        _recentlySeen.put(p, nowTick);
                        _seenOreTick.put(p, nowTick); // (2026-09-02) 记录"真看过"供记忆兜底
                    }
                    boolean remembered = !perceivable && _recentlySeen.containsKey(p)
                            && (nowTick - _recentlySeen.get(p)) < RECENTLY_SEEN_TICKS;
                    if (!perceivable && !remembered) continue;
                    if (requireFov && !VisibilityHelper.isBlockInView(mod, p)) continue;
                    if (!WorldHelper.canBreak(mod, p)) continue;
                    if (WorldHelper.isAdjacentToLiquid(mod, p)) continue;
                    // (20260915 挖金猪灵闸) 猪灵密集的金块本轮不可选。
                    if (piglinDensityGated(mod, p)) continue;
                    // (2026-09-05 真因②) 方块状态重校验不再 500ms 节流: 原节流让
                    // "缓存里已挖掉(空气)的矿位"在节流窗口内绕过真实世界校验被选中
                    // → 上层派 DestroyBlockTask 对着空气卡几秒。getBlockState 是
                    // 廉价查询 (昂贵的 LOS 射线仍走三重护栏), 每 tick 校验无回归风险。
                    if (mod.getChunkTracker().isChunkLoaded(p)) {
                        Block actual = mod.getWorld().getBlockState(p).getBlock();
                        boolean matches = false;
                        for (Block target : _blocks) {
                            if (actual == target) { matches = true; break; }
                        }
                        if (!matches) continue;
                    }
                    double dSq = p.getSquaredDistance(fromPos)
                            + scaffoldSelectionPenalty(mod, p); // (2026-09-19) 悬空下界岩排后
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = p;
                    }
                }
                if (_recentlySeen.size() > RECENTLY_SEEN_MAX_SIZE) {
                    _recentlySeen.entrySet().removeIf(e -> (nowTick - e.getValue()) >= RECENTLY_SEEN_TICKS);
                }
                return Optional.ofNullable(best);
            }

            // ---- 主线远场分支: FOV + LOS 双门控 (防透视), 三重性能护栏 ----
            List<BlockPos> candidates = new ArrayList<>();
            for (BlockPos p : known) {
                if (_blacklist.contains(p)) continue;
                if (p.getSquaredDistance(fromPos) >= maxSq) continue;
                if (requireFov && !VisibilityHelper.isBlockInView(mod, p)) continue;
                if (!WorldHelper.canBreak(mod, p)) continue;
                if (WorldHelper.isAdjacentToLiquid(mod, p)) continue;
                // (20260915 挖金猪灵闸) 猪灵密集的金块本轮不可选。
                if (piglinDensityGated(mod, p)) continue;
                // (2026-09-05 真因②) 同上: 方块状态每 tick 必校验, 不再 500ms 节流,
                // 已挖掉的空气位当场淘汰, 不再派 DestroyBlockTask 对着空气卡秒。
                if (mod.getChunkTracker().isChunkLoaded(p)) {
                    Block actual = mod.getWorld().getBlockState(p).getBlock();
                    boolean matches = false;
                    for (Block target : _blocks) {
                        if (actual == target) { matches = true; break; }
                    }
                    if (!matches) continue;
                }
                candidates.add(p);
            }
            if (candidates.isEmpty()) return Optional.empty();
            // 按距离升序逐个过 LOS (含 isBuriedOre 包裹判定), 首个成功即最近可感知矿。
            // (2026-09-19) 悬空下界岩罚距排后 —— 贴地块优先出队过 LOS。
            candidates.sort(Comparator.comparingDouble(p ->
                    p.getSquaredDistance(fromPos) + scaffoldSelectionPenalty(mod, p)));
            int raycasts = 0;
            for (BlockPos p : candidates) {
                Long failMs = _losFailCache.get(p);
                if (failMs != null && nowMs - failMs < LOS_FAIL_CACHE_MS) continue;
                if (++raycasts > MAX_LOS_RAYCASTS_PER_CALL) break;
                if (VisibilityHelper.isBlockPerceivable(mod, p)) {
                    _seenOreTick.put(p, (long) WorldHelper.getTicks()); // (2026-09-02) 记录"真看过"供记忆兜底
                    return Optional.of(p);
                }
                _losFailCache.put(p, nowMs);
            }
            if (_losFailCache.size() > LOS_FAIL_CACHE_MAX_SIZE) {
                _losFailCache.entrySet().removeIf(e -> nowMs - e.getValue() >= LOS_FAIL_CACHE_MS);
            }
            return Optional.empty();
        }
        /**
         * (2026-08-28 统一选择器·世界兜底) 缓存未就绪/找不到时扫世界定位, 覆盖缓存
         * 异步滞后的冷启动。与缓存选择器共用同一谓词 (LOS 防透视, requireFov 按模式)。
         * 3 秒 Wall-clock 节流 (新会话 tick 重置不干扰首次执行)。
         */
        private Optional<BlockPos> pickNearestWorldOre(AltoClef mod, Vec3d fromPos, double maxRange, boolean requireFov) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - _lastWorldScanMs < WORLD_SCAN_INTERVAL_MS) return Optional.empty();
            _lastWorldScanMs = nowMs;
            // (2026-09-01 探视力, 用户"挺近有钻石没看到") 上一轮性能修复把世界
            // 扫描硬缩到 8 格 (17^3≈4913 次) — 但那是 cache miss 兜底, 真卡顿
            // 在 LOS 射线(已用三重护栏解决), 缩半径只把感知弄瞎、卡顿没解决。
            // 恢复覆盖到 16 格 (33^3≈35,937 次), 3 秒节流下开销可控, 眼前可见的
            // 矿不再漏。主选路仍是 BlockTracker cache(24格), 世界扫描兜底 16。
            double limitedRange = Math.min(maxRange, 16);
            int r = (int) Math.ceil(limitedRange);
            BlockPos player = mod.getPlayer().getBlockPos();
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos p = player.add(dx, dy, dz);
                        Block actual = mod.getWorld().getBlockState(p).getBlock();
                        boolean match = false;
                        for (Block target : _blocks) {
                            if (actual == target) { match = true; break; }
                        }
                        if (!match) continue;
                        // (2026-09-01 防透视回归) 匹配块必须过 LOS+防包裹感知 — 上一轮
                        // 性能修复跳过 LOS 后, 隧道正前方 8 格内埋在墙里的矿也被直接
                        // 锁定 (透视)。LOS 只对匹配块执行 (非匹配块上面已 continue),
                        // 匹配块数量极少, 代价可忽略。
                        if (!VisibilityHelper.isBlockPerceivable(mod, p)) continue;
                        if (requireFov && !VisibilityHelper.isBlockInView(mod, p)) continue;
                        if (!WorldHelper.canBreak(mod, p)) continue;
                        // (20260915 挖金猪灵闸) 猪灵密集的金块本轮不可选。
                        if (piglinDensityGated(mod, p)) continue;
                        // (2026-09-19) 悬空下界岩罚距排后 (cache miss 兜底同口径)。
                        double dSq = p.getSquaredDistance(fromPos)
                                + scaffoldSelectionPenalty(mod, p);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                            best = p;
                        }
                    }
                }
            }
            if (best != null) {
                Debug.logMessage("[WorldScan] located " + _blocks[0] + " at " + best.toShortString() + " (cache miss fallback)");
            }
            return Optional.ofNullable(best);
        }

        /**
         * (2026-08-28 近场即时, 用户) 围绕【正在挖/刚挖完的那块】(center) 找紧贴它的
         * 可挖目标矿。center 为空 (还没开始挖) 时返回空, 由冷启动的缓存/世界扫描
         * 负责首个目标。
         *
         * (2026-09-22 邻接口径收编, 用户定案"豁免=面相邻6块, 都改, 口径一致") 原实现
         * 扫立方体 (贪婪 5x5x5 / 非贪婪 3x3x3, 更早历史见 GREEDY_NEAR_FIELD_RADIUS
         * 删除处注释), 棱邻/角邻的矿可以整块埋在石头里也被当候选 —— 与"不透视"冲突。
         * 现只认【面相邻】的 6 块: 锚=正在挖的块时, 挖掉它贴面 6 块必然暴露 (即将
         * 暴露); 锚=刚挖掉的空位时, 贴面 6 块已经暴露。豁免感知不引入任何透视。
         * 代价: 纯斜向 (棱/角) 连接的矿脉段链挖会断, 交给 BlockTracker 真感知
         * (暴露后 LOS/角点射线入册) 接力 —— 宁可慢一步, 不透视。
         */
        private Optional<BlockPos> getNearFieldOreAt(AltoClef mod, BlockPos center) {
            if (center == null) return Optional.empty();
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (Direction dir : Direction.values()) {
                BlockPos p = center.offset(dir);
                if (_blacklist.contains(p)) continue;
                Block actual = mod.getWorld().getBlockState(p).getBlock();
                boolean matches = false;
                for (Block target : _blocks) {
                    if (actual == target) { matches = true; break; }
                }
                if (!matches) continue;
                if (!WorldHelper.canBreak(mod, p)) continue;
                if (WorldHelper.isAdjacentToLiquid(mod, p)) continue;
                // (20260915 挖金猪灵闸) 猪灵密集的金块本轮不可选。
                if (piglinDensityGated(mod, p)) continue;
                double dSq = mod.getPlayer().getBlockPos().getSquaredDistance(p);
                // (2026-09-19 16:05 问题一) 垫脚目标: 站立层以下的块重罚
                // (与 scaffoldSelectionPenalty 同口径), 防近场链挖一路
                // 向下挖出坑。(2026-09-21 1849) 阈值下移一格: 站立层
                // (feetY-1) 不罚 —— 平地整层地表被旧阈值同罚=无差别,
                // bot 照样掘地三层 (183333 实锤)。
                if (isScaffoldGroundTarget() && p.getY() < mod.getPlayer().getBlockPos().getY() - 1) {
                    dSq += 1.0e6 * (double)(mod.getPlayer().getBlockPos().getY() - 1 - p.getY());
                } else if (_horizontalMode && isMaterialCollectionTarget()
                        && !isTargetingWood() && !isTargetingBed() && !isTargetingSurfaceResource()
                        && p.getY() < mod.getPlayer().getBlockPos().getY()) {
                    // (2026-09-21 2156 竖井策略) 水平阶段: 脚下层候选最后兜底,
                    // 与 scaffoldSelectionPenalty 的 horizontalCollect 分支同口径。
                    dSq += 1.0e9;
                }
                if (dSq < bestSq) {
                    bestSq = dSq;
                    best = p;
                }
            }
            return Optional.ofNullable(best);
        }

        /**
         * (2026-09-22 从原 getNearFieldOreAt 立方体实现收编改名, 仅木头批采保留)
         * 围绕批锚点扫半径 radius 的立方体, 找可挖目标。木头批采是用户 09-15 定案的
         * 【批采行为】("扫一下这个原木的位置的 3 格范围内还有别的原木吗"), 不是感知
         * 豁免 —— 树冠遮挡的木头另有 canopy_inference 感知口径, 且树叶透明本就不构成
         * 矿石那种"隔岩透视"。矿石/材料链挖一律走 {@link #getNearFieldOreAt} 面相邻口径。
         */
        private Optional<BlockPos> getNearFieldOreInCube(AltoClef mod, BlockPos center, int radius) {
            if (center == null) return Optional.empty();
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos p = center.add(dx, dy, dz);
                        if (p.equals(center)) continue;
                        if (_blacklist.contains(p)) continue;
                        Block actual = mod.getWorld().getBlockState(p).getBlock();
                        boolean matches = false;
                        for (Block target : _blocks) {
                            if (actual == target) { matches = true; break; }
                        }
                        if (!matches) continue;
                        if (!WorldHelper.canBreak(mod, p)) continue;
                        if (WorldHelper.isAdjacentToLiquid(mod, p)) continue;
                        if (piglinDensityGated(mod, p)) continue;
                        double dSq = mod.getPlayer().getBlockPos().getSquaredDistance(p);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                            best = p;
                        }
                    }
                }
            }
            return Optional.ofNullable(best);
        }

        // (2026-09-15 链挖锚点修复, 104100 场次实锤) 挖掉一块矿后 _miningPos 立即
        // 清空 (:859), 而链挖 getNearFieldOreAt 的锚点正是 _miningPos —— 锚点恰在
        // "刚挖完"这一刻丢失, 3x3x3 链挖实际从未接续过。主世界靠 BlockTracker
        // 缓存碰巧兜住 (矿暴露在隧道面能被 LOS 感知入册); 下界岩浆岩致密, 矿脉
        // 嵌在壁里永远 occluded, 缓存 perceivable=0 → 链断 → "挖完本处再捡" gate
        // 误判"没矿了" → 拾取抢占, 矿脉弃坑。修复: 挖掉方块时 _lastSelfBrokenPos
        // 已记录破坏位置 (2026-09-11 scoop 复用), 宽限窗口内把它当链挖锚点回退。
        private static final long CHAIN_ANCHOR_MS = 4000;

        /** 链挖/收尾 gate 共用的锚点: 正在挖的块; 刚挖完 4s 内用破坏位置兜底。 */
        private BlockPos getChainAnchor() {
            if (_miningPos != null) return _miningPos;
            if (_lastSelfBrokenPos == null || _miningTargetBrokenMs == 0) return null;
            if (System.currentTimeMillis() - _miningTargetBrokenMs >= CHAIN_ANCHOR_MS) return null;
            return _lastSelfBrokenPos;
        }

        /**
         * (2026-09-15 批采记账, 用户"每挖3个拾取一次") 木头目标: 本任务砍掉一根
         * 原木时更新批计数与批锚点。新砍位置仍在上一根 ±{@link #WOOD_BATCH_RADIUS}
         * 格内且未砍满 → 计数 +1 (同一批); 否则开新批 (计数 =1)。
         * 只统计任务显式砍掉的 (DestroyBlockTask 流程); baritone 寻路顺刀砍的不计,
         * 那是白捡的额外掉落, 由扫堆兜住。
         */
        private void onWoodBatchBroken(BlockPos broken) {
            if (!isBatchTarget()) return;
            boolean adjacentToBatch = _woodBatchAnchor != null
                    && _woodBatchBroken > 0
                    && _woodBatchBroken < WOOD_BATCH_MAX
                    && Math.max(Math.max(
                            Math.abs(broken.getX() - _woodBatchAnchor.getX()),
                            Math.abs(broken.getY() - _woodBatchAnchor.getY())),
                            Math.abs(broken.getZ() - _woodBatchAnchor.getZ())) <= WOOD_BATCH_RADIUS;
            if (adjacentToBatch) {
                _woodBatchBroken++;
            } else {
                _woodBatchBroken = 1;
                Debug.logMessage("[WOOD-BATCH] new batch at " + broken.toShortString());
            }
            _woodBatchAnchor = broken;
            _woodBatchLastDiagPos = null;
            Debug.logMessage("[WOOD-BATCH] broken #" + _woodBatchBroken + "/" + WOOD_BATCH_MAX
                    + " at " + broken.toShortString());
        }

        /**
         * (2026-09-16 批满挂起) 批采目标刚挖满 WOOD_BATCH_MAX 个、掉落物尚未回册的
         * 短暂窗口 (BATCH_BROKEN_GRACE_MS)。窗口内在 getClosestTo 里挡住 16 格 gate
         * (见 nearbyMine 处调用) —— 该 gate 对无限介质恒真, 不挡就是"挖满 3 个 →
         * 掉落没登记 → 还有矿 → 继续挖"的死循环。窗口由墙钟封顶, 掉落物真没生成
         * (掉进岩浆/被吞) 也会超时放行, 不会永久挂起。
         */
        private boolean batchFullPendingPickup() {
            return isBatchTarget() && _woodBatchBroken >= WOOD_BATCH_MAX
                    && System.currentTimeMillis() - _miningTargetBrokenMs < BATCH_BROKEN_GRACE_MS;
        }

        /**
         * (2026-09-15 扫堆 gate, 用户"捡完这一堆再走") 脚边 ≤{@code 4} 格内是否还有
         * 本目标掉落物没捡。供外层 isFinished 在木头数量达标后挂住任务继续捡,
         * 把批采砍出来的一堆一次收齐, 不再"捡 1 根→回合成→再跑一趟"。
         */
        boolean hasNearbyWoodDropsToSweep(AltoClef mod) {
            if (!isTargetingWood()) return false;
            if (!mod.getEntityTracker().itemDropped(_targets)) return false;
            Vec3d me = mod.getPlayer().getPos();
            Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(me, _targets);
            return closest.isPresent() && closest.get().squaredDistanceTo(me) <= WOOD_SWEEP_RANGE_SQ;
        }

        /**
         * (2026-09-01 贪心起始目标, 用户"贪心挖目标矿的3x3x3") 找玩家
         * GREEDY_TARGET_RANGE 内最近的可感知目标矿 (FOV+LOS, 不透视), 作为贪心的
         * 起始目标 — 导航过去后由 _miningPos 的 3x3x3 链挖接手。节流 0.5s, 且仅在
         * 无当前挖掘目标/无贴身矿时调用 (见 getClosestTo greedy 分支)。
         */
        private Optional<BlockPos> findVisibleGreedyTarget(AltoClef mod) {
            if (!_greedyTargetScanTimer.elapsed()) {
                return Optional.ofNullable(_greedyTargetCache);
            }
            _greedyTargetScanTimer.reset();
            BlockPos player = mod.getPlayer().getBlockPos();
            int r = GREEDY_TARGET_RANGE;
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos p = player.add(dx, dy, dz);
                        if (_blacklist.contains(p)) continue;
                        Block actual = mod.getWorld().getBlockState(p).getBlock();
                        boolean matches = false;
                        for (Block target : _blocks) {
                            if (actual == target) { matches = true; break; }
                        }
                        if (!matches) continue;
                        // 必须可感知 (LOS+isBuriedOre): 被岩石包住的矿不可见, 不锁 (不透视)。
                        if (!VisibilityHelper.isBlockPerceivable(mod, p)) continue;
                        if (!WorldHelper.canBreak(mod, p)) continue;
                        if (WorldHelper.isAdjacentToLiquid(mod, p)) continue;
                        // (20260915 挖金猪灵闸) 猪灵密集的金块本轮不可选。
                        if (piglinDensityGated(mod, p)) continue;
                        double dSq = player.getSquaredDistance(p);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                            best = p;
                        }
                    }
                }
            }
            _greedyTargetCache = best;
            // (2026-09-22 透视取证, 用户"挖隔墙完全看不到的铁") 选中目标时打出感知来源
            // 标签 (perceiveSource: los=真视线 / stone_medium / canopy_inference /
            // experience / fluid_*) + 距离。下次"看到挖了看不见的矿"时, 该行直接定位
            // 是哪条感知分支放行的 —— 与 09-01 [GreedyDiag] 钻石排查同一套路。
            // 2s 节流: 同一目标反复选中不刷屏。
            if (best != null) {
                long nowMs = System.currentTimeMillis();
                if (!best.equals(_lastGreedyTargetLogged) || nowMs - _lastGreedyTargetLogMs >= 2000) {
                    _lastGreedyTargetLogged = best;
                    _lastGreedyTargetLogMs = nowMs;
                    double dist = Math.sqrt(player.getSquaredDistance(best));
                    Debug.logToFile("altoclef_diag.log",
                            String.format("[GreedyTarget] selected %s dist=%.1f perceive=%s",
                                    best.toShortString(), dist, VisibilityHelper.perceiveSource(mod, best)));
                }
            }
            return Optional.ofNullable(best);
        }

        /**
         * (2026-08-30 遗留容器近场直扫) 扫【玩家近场】(水平±8/纵向-4~+4)世界里
         * 仍存在的目标方块, 不看 FOV/不看 LOS、不用 BlockTracker 缓存的异步延迟。
         * 用于熔炉/工作台/床/羊毛这类人造/地表方块: 人工放置的遗留容器(上个任务
         * 留的)就在眼前, 人类直接收; 矿石探洞的 FOV/LOS 感知反而会"看不见眼前
         * 熔炉"而掉进挖隧道(用户实测 21:35 收熔炉去手挖地道)。范围限 8 格且块
         * 必须 chunk 已加载 + 世界验证 == 目标块, 不锁远处被岩石包住的自然矿,
         * 不破坏"不透视矿石"约束。
         */
        private Optional<BlockPos> pickNearestNearbySurface(AltoClef mod, Vec3d fromPos) {
            if (mod.getPlayer() == null) return Optional.empty();
            BlockPos player = mod.getPlayer().getBlockPos();
            int r = 8;
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos p = player.add(dx, dy, dz);
                        if (_blacklist.contains(p)) continue;
                        if (!mod.getChunkTracker().isChunkLoaded(p)) continue;
                        Block actual = mod.getWorld().getBlockState(p).getBlock();
                        boolean matches = false;
                        for (Block target : _blocks) {
                            if (actual == target) { matches = true; break; }
                        }
                        if (!matches) continue;
                        if (!WorldHelper.canBreak(mod, p)) continue;
                        if (WorldHelper.isAdjacentToLiquid(mod, p)) continue;
                        double dSq = p.getSquaredDistance(fromPos);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                            best = p;
                        }
                    }
                }
            }
            return Optional.ofNullable(best);
        }

        /**
         * (2026-08-28) 近场是否有紧贴正在挖那块的可挖连位矿 (供 isFinished 判断)。
         * (2026-09-22) 与 getClosestTo 同口径收编为面相邻 6 块 (原贪婪 5x5x5 /
         * 非贪婪 3x3x3 / 垫脚 7x7x7 立方体撤销), 半径不一致会"判还有活但找不到
         * 目标"空转的老问题由"两边都调 getNearFieldOreAt"根治。
         */
        boolean hasNearFieldTarget(AltoClef mod) {
            // (2026-09-22 批次在场即活, 用户"路过很多铁不挖" 165449 场实锤)
            // 批次已从真感知锁定目标块 (_miningPos 由 getClosestTo 从 BlockTracker/
            // 可见扫描选出) 且该块世界校验仍有效 → 本身就是活。原保活口径只有
            // "锚块面相邻6"+"玩家面相邻6": 批次开局 bot 距目标 2~8 格、洞壁散铁
            // 彼此不贴面 → 两个口径全 false → isFinished 在批次创建后 1~2 tick 即
            // 真, 上层(BeatMinecraft2Task)立即拆批, 表现为 TRIGGER 每 3s 闪一次、
            // 生铁一颗没挖 (16:58:07~27 五连闪实锤; 对照 16:58:30 金批次恰贴脸
            // 才活)。这不是感知豁免: 目标块本就是真感知选的, 保活不引入透视;
            // 面相邻口径只用于链挖延续 (锚=刚挖掉的空位)。防陈旧: 黑名单/块已
            // 非目标/不可挖/邻液/chunk 卸载 → 不认活, 防"判还有活但找不到目标"空转。
            if (_greedy && _miningPos != null && isMiningPosStillWorkable(mod)) {
                return true;
            }
            // (2026-09-15) 锚点与 getClosestTo 同口径 (getChainAnchor 含刚挖
            // 完 4s 内的破坏位置兜底), 否则"判没活但链挖有目标"反向不一致。
            Optional<BlockPos> m = getNearFieldOreAt(mod, getChainAnchor());
            if (m.isPresent()) return true;
            // 贪心: 玩家近身也有紧贴矿也算"还有活", 任务不结束 (等 getClosestTo 派发)。
            return _greedy && getNearFieldOreAt(mod, mod.getPlayer().getBlockPos()).isPresent();
        }

        /** (2026-09-22 在场即活) 目标块世界校验: 仍是目标方块且可挖不邻液未拉黑。 */
        private boolean isMiningPosStillWorkable(AltoClef mod) {
            if (_blacklist.contains(_miningPos)) return false;
            if (!mod.getChunkTracker().isChunkLoaded(_miningPos)) return false;
            Block actual = mod.getWorld().getBlockState(_miningPos).getBlock();
            boolean matches = false;
            for (Block target : _blocks) {
                if (actual == target) { matches = true; break; }
            }
            if (!matches) return false;
            if (!WorldHelper.canBreak(mod, _miningPos)) return false;
            return !WorldHelper.isAdjacentToLiquid(mod, _miningPos);
        }

        // (2026-09-08 无目标空转兜底) surface 目标(熔炉/工作台等)在全无目标时
        // createNextExplorationTask 刻意返回 null "停下等"(注释见该方法: 床/羊毛
        // 不做无脑探索), 但"停"没有上限, 且本类未覆写 isFinished → Task 默认
        // false → 上层永远等不到结束, bot 在 "Waiting for calculations
        // (wandering)" 站桩卡死 (08:27 日志实锤: 拆完熔炉后掉落物未及时进包,
        // 空转 734 ticks = 36.7 秒, debugstate 状态一字未变, 直到用户手动停)。
        // 修复: onTick 返回 null(本 tick 完全无动作)连续超过阈值 → 宣告放弃,
        // 交还上层决策; 上层(BeatMinecraft 收炉分支)对放弃冷却 30 秒, 期间让位
        // 主线 — 用户"5s 太长, 观感上就是卡住"定 2 秒。捡取粘性冷却(等 tracker
        // 回册的 2s)不计入空转。
        private static final int BLIND_GIVE_UP_TICKS = 40; // 2 秒
        private int _blindNullTicks = 0;

        /** 连续无动作空转是否已超过放弃阈值。 */
        public boolean gaveUpWandering() {
            return _blindNullTicks >= BLIND_GIVE_UP_TICKS;
        }

        /** 统一出口: 记录本 tick 是否完全无动作 (供无目标空转兜底计数)。 */
        private Task trackSuperTick(Task sub) {
            if (sub != null) {
                // 有任何动作(捡取/挖掘/探索/环顾)都不算空转。
                _blindNullTicks = 0;
            } else if (System.currentTimeMillis() - _lastDropPickupMs >= DROP_PICKUP_COOLDOWN_MS) {
                // 掉落物合并/吸入的 1~2 tick tracker 滞后属于正常等待, 不计。
                _blindNullTicks++;
            }
            return sub;
        }

        // (2026-09-11 自挖因果记忆) 最近一次自己破坏的目标方块位置 + scoop 状态。
        // 实例缓存 keep-alive(任务重选教训): 只在破坏位置变化时重建, 防每 tick 重建。
        private BlockPos _lastSelfBrokenPos = null;

        /**
         * (2026-09-17 零行为诊断, 只落盘 diag.log 不打聊天) scoop 激活期间打印
         * "掉落物为什么没回册"的逐项分解。本仓 EntityTracker 的掉落物登记要过
         * "已落地" + 距离≤64 + (FOV 豁免≤6格 或 视锥内) + 物品可见距离≤32 +
         * LOS 无遮挡 多道门, 现有日志无法区分卡在哪一道。1s 节流。
         * 判读: drop 行存在且 onGround=true 而 visible=false → LOS/距离门
         * (树叶/树干遮挡); onGround=false 且 solidBelow 全 false → 落地门
         * (悬在树叶/空中, 永不入册); itemEntitiesNearby=0 → 实体已被吸入或
         * 从未出现在客户端侧。
         */
        private long _lastSelfDropDiagMs = 0;

        private void logSelfDropDiag(AltoClef mod) {
            long now = System.currentTimeMillis();
            if (now - _lastSelfDropDiagMs < 1000) return;
            _lastSelfDropDiagMs = now;
            if (_lastSelfBrokenPos == null || mod.getPlayer() == null || mod.getWorld() == null) return;
            Vec3d eye = mod.getPlayer().getCameraPosVec(1.0f);
            StringBuilder sb = new StringBuilder();
            sb.append("[DropDiag] broken=").append(_lastSelfBrokenPos.toShortString())
                    .append(" age=").append(now - _miningTargetBrokenMs).append("ms")
                    .append(" itemDropped=").append(mod.getEntityTracker().itemDropped(_targets));
            int found = 0;
            for (net.minecraft.entity.Entity e : mod.getWorld().getEntities()) {
                if (!(e instanceof ItemEntity ie)) continue;
                if (ie.squaredDistanceTo(eye) > 32 * 32) continue;
                found++;
                Item item = ie.getStack().getItem();
                boolean matches = false;
                for (ItemTarget t : _targets) {
                    if (t.matches(item)) {
                        matches = true;
                        break;
                    }
                }
                sb.append("\n  drop ").append(item)
                        .append(" at ").append(ie.getBlockPos().toShortString())
                        .append(" matchTarget=").append(matches)
                        .append(" onGround=").append(ie.isOnGround())
                        .append(" inWater=").append(ie.isTouchingWater())
                        .append(" solidBelow2=").append(WorldHelper.isSolid(mod, ie.getBlockPos().down(2)))
                        .append(" solidBelow3=").append(WorldHelper.isSolid(mod, ie.getBlockPos().down(3)))
                        .append(" dEye=").append(String.format("%.1f", Math.sqrt(ie.squaredDistanceTo(eye))))
                        .append(" visible=").append(VisibilityHelper.isEntityVisible(mod, ie));
            }
            sb.append("\n  itemEntitiesNearby=").append(found);
            Debug.logToFile("altoclef_diag.log", sb.toString());
        }
        private BlockPos _scoopTaskPos = null;
        private boolean _scoopStage2 = false;
        private Task _scoopTask = null;

        /** (2026-09-11 背包满顺延) scoop 两段任务是否仍在进行(未走到/未看完)。 */
        public boolean scoopInProgress() {
            return _scoopTask != null && _scoopTaskPos != null && _mod != null && !_scoopTask.isFinished(_mod);
        }

        // (2026-09-17) 无限介质目标 (木头/石头类) 的自挖掉落物 scoop 宽限, 比贪婪
        // 挖矿的 2.5s 长。理由: 这类目标常踩"挖掉后附近没有下一块、也没进拾取阶段"
        // 的空档, 且掉落物可能落在树叶层里/贴在壁里, 要走到跟前才被吸入
        // (20260917-135722: 树顶那格掉落后 bot 就在 4 格外, 36 秒没拾取)。
        private static final long SELF_DROP_SCOOP_MS = 5000;

        /** scoop 宽限 (ms): 无限介质目标 5s, 其余沿用贪婪挖矿的 2.5s (行为不变)。 */
        private long selfDropGraceMs() {
            return isBatchTarget() ? SELF_DROP_SCOOP_MS : GREEDY_DROP_GRACE_MS;
        }

        /** 宽限窗口内的自挖掉落物 scoop 任务; 窗口外/无因果位置返回 null。 */
        private Task getScoopSelfDropTask(AltoClef mod) {            if (_miningTargetBrokenMs == 0 || _lastSelfBrokenPos == null) return null;
            if (System.currentTimeMillis() - _miningTargetBrokenMs >= selfDropGraceMs()) return null;
            if (_scoopTaskPos == null || !_scoopTaskPos.equals(_lastSelfBrokenPos)) {
                // 新破坏事件: 重置两段拾取, 先转头看。
                _scoopTaskPos = _lastSelfBrokenPos;
                _scoopStage2 = false;
                _scoopTask = new LookAroundTask(_scoopTaskPos, 1);
            }
            if (_scoopTask.isFinished(mod)) {
                if (_scoopStage2) {
                    // 看完也走到了仍无回册: 本次 scoop 到头, 交给宽限到期释放。
                    return null;
                }
                // 第一段(转头 1s)结束仍被调到这里 = 掉落物仍未回册 → 第二段走过去。
                _scoopStage2 = true;
                _scoopTask = new GetToBlockTask(_scoopTaskPos);
            }
            return _scoopTask;
        }

        @Override
        protected Task getWanderTask(AltoClef mod) {
            // (2026-09-11 自挖掉落物因果记忆, 用户"他压根没去拾取") 机理: 掉落物
            // 弹出时 bot 正脸挖下一块 → 掉落物落在视野外 → EntityTracker 人类
            // 感知过滤(FOV+LOS)永不回册 → itemDropped 恒 false → getClosestTo
            // 认为"没活"落到这里 → 原实现返回 null 原地空转, 宽限 2.5s 到期整链
            // 释放, 掉落物永远留在地上 (09:07 场次实锤: 47.3 唯一一次 Pickup 派发后
            // 49.7-52.2 全 null, 52.2 宽限到期放人, 3 个掉落物用户人工捡)。
            // "我自己挖掉的矿, 我知道掉落物在哪"属结构推断线索 (线索优先级
            // 视觉>听觉>结构推断), 非透视。宽限窗口内两段 scoop:
            // ①转头看向破坏位置 → 掉落物进视锥 → 正常感知回册 → closestDrop 接管;
            // ②看完仍无回册 → 走到破坏位置, 拾取半径自动吸入。
            // 宽限到期仍未捡到 → 返回 null 照常释放(掉岩浆/被炸飞等)。
            //
            // (2026-09-17 提到两模式共用, 用户"掉落物滞空那 1 tick 里附近没原木,
            // 所以整个父任务都换了, 没等到它落地") 用户对"没等落地"的判断正确,
            // 但经 tasks.log 核对父任务并未被换 (单实例、clean 结束、无抢占者)。
            // 真正缺的是这段等待本身 —— 原先它只挂在贪婪分支, 非贪婪的木头/石头
            // 目标踩到"批采未满 (1/3) + 附近没有下一块 + 掉落物仍在滞空/未过感知
            // 门控"时, 四个等待机制全部落空:
            //   holdGreedyForDrop      : 仅 _greedy (MineAndCollectTask.isFinished)
            //   batchFullPendingPickup : 仅 _woodBatchBroken >= WOOD_BATCH_MAX
            //   getScoopSelfDropTask   : 原先仅贪婪分支 (本处)
            //   _lastDropPickupMs 冷却 : 需先有过一次拾取派发才起算
            // ⇒ getClosestTo 返回空 → 立刻 wander/BiasedExplore, 之后再没有任何人
            // 回头等那件掉落物。20260917-135722 实锤: 树顶挖掉 12,74,-39 一格, 掉落物
            // 躺在 4 格宽 36 秒无人捡, MineAndCollectTask 全程 (clean) 运行 53.3s 到
            // 用户手动停, 每 tick getClosestTo 都是空, [Diag-blind] 每 5s 刷到底。
            Task scoop = getScoopSelfDropTask(mod);
            if (scoop != null) {
                logSelfDropDiag(mod);
                return scoop;
            }
            // (2026-08-28 贪心=纯贴身) 贪心没有紧贴矿就收手, 绝不挖隧道/探索
            // (否则会"看不到矿就挖通道", 还可能在远处发现透视的矿)。
            if (_greedy) {
                logBlindnessDiag(mod);
                return null;
            }
            logBlindnessDiag(mod);
            // (2026-09-06 捡取粘性移植 574295d8, 用户"贪婪挖铁任务也被打断") 机制:
            // 掉落物实体合并/被吸入瞬间 isValid=false → pursuit 清空, entity tracker
            // 异步滞后 1-2 tick 内 getClosestTo 返回空 → 本方法立刻开新隧道
            // (Digging a tunnel) → 掉落物重新入册又切回捡取 → 每 ~1s 乒乓
            // (日志实锤 18:32:44/49 pickup 两次被 tunnel 顶停, TaskPingPong 6x/5s,
            // raw_iron 9 块只收到 6)。修复: 刚追过掉落物 (DROP_PICKUP_COOLDOWN_MS
            // 窗口内) 不开新隧道也不环顾, 返回 null 站等 1-2 tick, tracker 回册后
            // getClosestTo 自然重新锁掉落物续捡。只拦"进入探索", 不拦捡取本身 —
            // 与 574295d8 "冷却只管进入决策" 同语义。窗口过完掉落真没了才继续探索。
            if (System.currentTimeMillis() - _lastDropPickupMs < DROP_PICKUP_COOLDOWN_MS) {
                return null;
            }
            // 环顾行为（2026-08-21，替代已回滚的 FOV bypass——用户反馈
            // "绕过 FOV 太作弊"）：即将开新通道前，朝近处(≤24格)被 FOV
            // 拒绝的已知矿转头看 2 秒。人类行为：开新隧道前先环顾四周
            // 确认没漏矿。转头后矿进入视锥，getClosestTo 走正常感知
            // 路径返回它——感知检查本身不被绕过。
            if (_lookAroundTask != null && !_lookAroundTask.isFinished(mod)) {
                return _lookAroundTask;
            }
            _lookAroundTask = null;
            long nowTick = WorldHelper.getTicks();
            boolean finished = _explorationTask != null && _explorationTask.isFinished(mod);
            boolean cooledDown = nowTick - _lastExploreCreateTick >= EXPLORE_RECREATE_COOLDOWN_TICKS;
            if (_explorationTask == null || (finished && cooledDown)) {
                // (2026-09-10 空中不重建, 22:23:52 场次实锤) 挖穿坠入矿坑的瞬间玩家
                // Y 穿越 targetY → 探索实例 isFinished 在 CaveScan FIRE 前瞬时翻 true,
                // 若此刻在空中重建, 全新实例会顶掉原实例 — 落地换入解除后父层返回的
                // 已是新实例, 原实例手里待启动的洞探索连同 FIRE 标记一起被丢弃, 整洞
                // 被判"已探索"永不重试。空中时框架本来就拒绝一切换入 (ITaskRequires
                // Grounded), 重建只会白造实例; 返回 null 站等 <1s 落地, 落地后若原实例
                // 已 FIRE(_inCave=true, isFinished=false) 自然续跑探索链。
                if (!MineDownExploreTask.isPlayerGrounded(mod)) {
                    if (WorldHelper.getTicks() - _lastAirborneSkipLogTick >= 20) {
                        _lastAirborneSkipLogTick = WorldHelper.getTicks();
                        Debug.logToFile("altoclef_diag.log",
                                "[MineDown-Wait]\tskip exploration rebuild while airborne (prev="
                                        + (_explorationTask == null ? "null" : "held")
                                        + ", prevFinished=" + finished + ") — waiting for landing");
                    }
                    return null;
                }
                if (_lookAroundCooldown.elapsed()) {
                    Optional<BlockPos> rejected = findFovRejectedOre(mod, NEAR_ORE_NO_FOV_RANGE);
                    if (rejected.isPresent()) {
                        _lookAroundCooldown.reset();
                        _lookAroundTask = new LookAroundTask(rejected.get(), 2);
                        Debug.logMessage("[Look-around] checking nearby ore at " + rejected.get().toShortString()
                                + " before digging a new tunnel.");
                        return _lookAroundTask;
                    }
                }
                // (2026-09-10 探索断裂诊断, 用户"挖到地下矿坑却不触发矿洞探索")
                // 重建决策打点: 重点抓"上一实例 isFinished=true 且 bot 空中"的瞬间 —
                // 坠入矿坑时 isFinished 在 FIRE 前瞬时翻转, 此处若在空中重建, 全新
                // 实例会顶掉正在坠落实例手里还没来得及启动的洞探索。
                String prevDesc = _explorationTask == null ? "null"
                        : _explorationTask + "@" + Integer.toHexString(System.identityHashCode(_explorationTask));
                boolean prevFinished = finished;
                _explorationTask = createNextExplorationTask(mod);
                _lastExploreCreateTick = nowTick;
                Debug.logToFile("altoclef_diag.log",
                        "[MineDown-WaitDiag]\texploration recreated: prev=" + prevDesc
                                + " prevFinished=" + prevFinished
                                + " grounded=" + MineDownExploreTask.isPlayerGrounded(mod)
                                + " new=" + _explorationTask
                                + "@" + Integer.toHexString(System.identityHashCode(_explorationTask)));
            }
            return _explorationTask;
        }

        /**
         * 找近处仅因 FOV 被拒的已知矿（遮挡/太远的转头也没用）。
         * (2026-09-22 修复死过滤, 20260922-121524 实锤) 原判据是
         * `perceivableFailReason(...).startsWith("outside FOV")` — 但
         * perceivableFailReason 自 2026-08-21 改版后【只可能】返回
         * "occluded (ray to center blocked...)"（FOV 拒绝已从感知判定里拆出,
         * 原因串不复存在), 全工程无任何产出点 → 本方法每个候选都被 continue,
         * LookAroundTask(环顾) 自那时起从未触发过 = 死代码。12:21:40 现场:
         * [Diag-blind] perceivable=1(唯一可感知钻在视锥外被 requireFov 丢弃)
         * 却无人救回, 直接挖隧道。改用 isBlockInView 直接判视锥内外, 语义
         * 与方法名一致: 已在视锥内的转头没用, 视锥外的转头看一眼即可被
         * 正常选中。
         */
        private Optional<BlockPos> findFovRejectedOre(AltoClef mod, double maxRange) {
            if (mod.getPlayer() == null) return Optional.empty();
            List<BlockPos> known = mod.getBlockTracker().getKnownLocations(_blocks);
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            double maxSq = maxRange * maxRange;
            for (BlockPos pos : known) {
                if (_blacklist.contains(pos)) continue;
                if (!WorldHelper.canBreak(mod, pos)) continue;
                if (WorldHelper.isAdjacentToLiquid(mod, pos)) continue;
                double dSq = pos.getSquaredDistance(mod.getPlayer().getBlockPos());
                if (dSq >= maxSq) continue;
                if (VisibilityHelper.isBlockInView(mod, pos)) continue; // 已在视锥内，无需转头
                if (dSq < bestSq) {
                    bestSq = dSq;
                    best = pos;
                }
            }
            return Optional.ofNullable(best);
        }

        /**
         * 转头看一个位置 2 秒（人类"开新隧道前环顾"）。期间父任务每 tick
         * 调 getClosestTo，目标一旦进入视锥即被正常选中，本任务随之被替换。
         */
        private static class LookAroundTask extends Task {
            private final BlockPos _target;
            private final TimerGame _timer;

            LookAroundTask(BlockPos target, double seconds) {
                _target = target;
                _timer = new TimerGame(seconds);
            }

            @Override
            protected void onStart(AltoClef mod) {
                setDebugState("Looking around at nearby ore");
            }

            @Override
            protected Task onTick(AltoClef mod) {
                LookHelper.lookAt(mod, _target);
                return null;
            }

            @Override
            protected void onStop(AltoClef mod, Task interruptTask) {
            }

            @Override
            public boolean isFinished(AltoClef mod) {
                return _timer.elapsed();
            }

            @Override
            protected boolean isEqual(Task other) {
                return other instanceof LookAroundTask && ((LookAroundTask) other)._target.equals(_target);
            }

            @Override
            protected String toDebugString() {
                return "Look around at " + _target.toShortString();
            }
        }

        /**
         * [Diag-blind] While wandering for targets, report WHY each cached target
         * is being rejected. If the bot walks past a visible ore and starts a shaft,
         * this line tells us whether it was: never scanned (cache=0), filtered as
         * invalid (unreachable/blacklisted), or rejected by the perceivability
         * check (too far / outside FOV / occluded ray).
         */
        private void logBlindnessDiag(AltoClef mod) {
            if (++_blindDiagTick % 100 != 0) return; // every ~5 seconds of wandering
            if (mod.getPlayer() == null) return;
            List<BlockPos> known = mod.getBlockTracker().getKnownLocations(_blocks);
            int valid = 0;
            int perceivable = 0;
            int blacklisted = 0;
            int unbreakable = 0;
            BlockPos nearestRejected = null;
            double nearestRejectedDistSq = Double.POSITIVE_INFINITY;
            BlockPos nearestHardFail = null;
            double nearestHardFailDistSq = Double.POSITIVE_INFINITY;
            String nearestHardFailReason = null;
            for (BlockPos pos : known) {
                double dSq = pos.getSquaredDistance(mod.getPlayer().getBlockPos());
                if (_blacklist.contains(pos)) {
                    blacklisted++;
                    if (dSq < nearestHardFailDistSq) {
                        nearestHardFailDistSq = dSq;
                        nearestHardFail = pos;
                        nearestHardFailReason = "blacklisted(task-level)";
                    }
                    continue;
                }
                if (!WorldHelper.canBreak(mod, pos)) {
                    unbreakable++;
                    if (dSq < nearestHardFailDistSq) {
                        nearestHardFailDistSq = dSq;
                        nearestHardFail = pos;
                        nearestHardFailReason = WorldHelper.canBreakFailReason(mod, pos);
                    }
                    continue;
                }
                valid++;
                if (VisibilityHelper.isBlockPerceivable(mod, pos)) {
                    perceivable++;
                    continue;
                }
                if (dSq < nearestRejectedDistSq) {
                    nearestRejectedDistSq = dSq;
                    nearestRejected = pos;
                }
            }
            StringBuilder sb = new StringBuilder();
            int[] air = countAirAround(mod);
            sb.append("[Diag-blind] wandering for ").append(Arrays.toString(_blocks))
                    .append(": cached=").append(known.size())
                    .append(" valid=").append(valid)
                    .append(" perceivable=").append(perceivable)
                    .append(" blacklisted=").append(blacklisted)
                    .append(" unbreakable=").append(unbreakable)
                    .append(" oreMemory=").append(mod.getWorldMemory().getOreLocations(_blocks).size())
                    .append(" caveAir=").append(air[0])
                    .append(" air=").append(air[1]);
            if (nearestHardFail != null) {
                sb.append(" | nearest hard-fail ").append(nearestHardFail.toShortString())
                        .append(" (").append((int) Math.sqrt(nearestHardFailDistSq)).append("m): ")
                        .append(nearestHardFailReason);
            }
            if (nearestRejected != null) {
                sb.append(" | nearest rejected ").append(nearestRejected.toShortString())
                        .append(" (").append((int) Math.sqrt(nearestRejectedDistSq)).append("m): ")
                        .append(VisibilityHelper.perceivableFailReason(mod, nearestRejected));
            }
            Debug.logMessage(sb.toString());
            Debug.logToFile("altoclef_diag.log", sb.toString());
        }

        private Task createNextExplorationTask(AltoClef mod) {
            if (isTargetingSurfaceResource()) {
                // (2026-08-31 去掉偏置探索, 用户"错误找床/没杀羊/撸床不捡") 床/羊毛
                // 目标的 BiasedExplore 在无偏置源头时就是 "Exploring randomly" 瞎逛:
                // 实测会 ① 丢下正在杀的羊去 <Biased exploration> 找床 ② 刚撸掉床
                // 不捡掉落物就跑去探索 —— 把明确的子任务(杀羊取毛/拆眼前床/捡脚下
                // 掉落)打断。床/羊毛这种需要就地解决的东西, 不做无脑偏置探索: 只
                // 专注当前明确目标, 全无目标时就停下等(绝不随机逛)。木头仍保留
                // BiasedExplore 在地表找树。
                if (!isTargetingWood()) {
                    return null;
                }
                // SURFACE RESOURCES (wood ONLY) EXIST ON THE SURFACE.
                // Digging a shaft down is the opposite of what a human would do.
                List<Supplier<Collection<BlockPos>>> biasSources = new ArrayList<>();
                // If we're underground, push the first bias toward "climb
                // back up to sea level" first — otherwise we'll wander a cave
                // forever looking for trees that don't exist there.
                //
                // (2026-09-17 判据统一) 原判据是内联硬编码 y < SURFACE_SEA_LEVEL_Y - 2
                // (= y < 61)。这是全项目第 5 份互不相同的"是否在地下"口径, 且在
                // 20260917-120830 场次实锤翻车: bot 恰在 y=62 的 1 格宽阶梯地道里,
                // y<61 不成立 → 爬升锚点不注入 → BiasedExplore 拿洞外 y=79 的原木
                // 当目标, 在 3 格厚的洞顶下面耗了 35 分钟 (见 SurfaceHelper 类注释)。
                // 改走统一判据 (5 点多数投票 + 5s 迟滞): 1 格宽地道里四个水平邻柱
                // 同高全是实心 → covered 5/5 → 稳定判地下, 锚点正常注入。
                if (SurfaceHelper.isBelowSurfaceDebounced(mod)) {
                    BlockPos player = mod.getPlayer().getBlockPos();
                    BlockPos upAnchor = new BlockPos(player.getX(), SURFACE_SEA_LEVEL_Y, player.getZ());
                    biasSources.add(() -> Collections.singletonList(upAnchor));
                }
                biasSources.add(() -> mod.getBlockTracker().getKnownLocations(_blocks));
                biasSources.add(() -> mod.getWorldMemory().getOreLocations(_blocks));
                biasSources.add(() -> mod.getKnowledgeMemory().getCandidateAreas(mod.getPlayer().getPos()));
                @SuppressWarnings("unchecked")
                Supplier<Collection<BlockPos>>[] arr = biasSources.toArray(Supplier[]::new);
                return new BiasedExploreTask(arr);
            }

            // ORE (2026-08-28 探索=挖隧道, 用户): 没有可感知的矿石时, 直接挖通道找矿。
            // 统一用 MineDownExploreTask (挖梯/隧道, 内部会自动探测并进入洞穴)。
            // 移除原来的"矿洞内随机走"BiasedExplore 分支: 它只走不挖, 困在封闭
            // 空腔里永远走不出、只能原地反复寻路 → 站死 (用户实测站定不动)。
            // (2026-09-05 目标带) 传 [min, max] 生成带, 每实例带内随机取目标高度。
            // (2026-09-16 灵魂沙谷远征, 用户"灵魂沙地貌金子少, 应该远征") 下界挖
            // 金/石英/镶金黑石时若身处灵魂沙谷 → 定向远征走出山谷再继续挖。谷内
            // 灵魂沙/灰烬覆盖, 金矿暴露少, 原地挖隧道零产出 (20260916-114603 实锤:
            // 12:00~12:26 在谷内反复挖隧道+被爬升打断, 零金产出)。残骸目标不触发
            // (残骸全下界均匀分布, 无需避谷)。实例缓存: isEqual 是 identity, 每 tick
            // new 会无限拆树。
            if (WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER
                    && targetsNetherSurfaceOres()
                    && isInSoulSandValley(mod)) {
                if (_soulSandExpedition == null || _soulSandExpedition.isFinished(mod)) {
                    _soulSandExpedition = new NetherExpeditionTask();
                    Debug.logMessage("[NETHER-EXPEDITION] soul sand valley detected while mining"
                            + " nether ores -> expedition to leave the valley.");
                }
                return _soulSandExpedition;
            }
            // (2026-09-25 挖金无进展远征, 用户"bot找金子长时间没进展我也希望触发
            // 远征模式") 与灵魂沙谷远征 (按群系) 互补: 按【产出无进展】触发 —— 只在
            // 已感知不到矿、即将开新隧道时评估 (挖矿/捡取/探洞进行中都不打断)。
            // 进展度量 = 背包金粒数在两次评估间有无变化 (上涨=挖到金, 下降=被合成
            // 消耗, 都算"有产出")。无变化超 3 分钟 → 换地方, 别在贫矿区原地挖隧道。
            // 仅金任务 (下界金矿目标) 触发; 石英/镶金黑石暂不纳入, 需要时再扩。
            if (WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER
                    && targetsNetherGoldOre()) {
                int nuggets = NetherGoldExpeditionTask.countGoldNuggets(mod);
                long nowMs = System.currentTimeMillis();
                if (_goldNuggetBaseline < 0) {
                    // 首次评估: 建基线, 给足宽限。
                    _goldNuggetBaseline = nuggets;
                    _lastGoldProgressMs = nowMs;
                } else if (nuggets != _goldNuggetBaseline) {
                    _goldNuggetBaseline = nuggets;
                    _lastGoldProgressMs = nowMs;
                    _goldNoProgressExpedition = null;
                } else if (nowMs - _lastGoldProgressMs >= GOLD_NO_PROGRESS_MILLIS) {
                    if (_goldNoProgressExpedition == null || _goldNoProgressExpedition.isFinished(mod)) {
                        _goldNoProgressExpedition = new NetherGoldExpeditionTask();
                        Debug.logMessage(String.format(java.util.Locale.ROOT,
                                "[GOLD-EXPEDITION] no gold progress for %.0fs while mining nether"
                                        + " gold (nuggets=%d, ore not perceivable) -> expedition.",
                                (nowMs - _lastGoldProgressMs) / 1000.0, nuggets));
                        Debug.logToFile("altoclef_diag.log",
                                "[GOLD-EXPEDITION]\tno progress " + ((nowMs - _lastGoldProgressMs) / 1000.0)
                                        + "s, nuggets=" + nuggets + " -> expedition");
                    }
                    return _goldNoProgressExpedition;
                }
            }
            int[] band = targetShaftBand();
            // (2026-09-07 探索断点交接) 链重建后认领仍在探索中的上一个探索任务:
            // 同维度且玩家仍在最近 FIRE 锚点 64 格内 → 原样复用(MineDownExploreTask
            // 的 isEqual 是 identity, 同一对象不会被框架重启重置), 探索无缝继续;
            // 玩家已远离/维度已变/探索已结束 → 正常新建。
            // (2026-09-13 遇水改道纳入交接) "探洞中"与"遇水改道中"都是必须保护的中途
            // 状态 —— 实例重建会重置 _step/_waterSkipped 并把方向强制回常量
            // STAIR_DIR(1,0) → 沿原轨迹重挖撞同一片水 (09-13 16:38 实锤: 4 轮改道
            // 全锁在 x=71~91/y=54~64/z=-470~-480 这 20 格内)。
            // (2026-09-10 探索断裂诊断) 闸门结果与失败原因打点 — 定位"坠落窗口
            // 内 prev 尚未 FIRE(isExploringCave=false) → 交接失败 → 全新实例"。
            boolean dimNowNether = WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER;
            String handoffFail = null;
            if (_lastCaveExploration == null) {
                handoffFail = "no-prev";
            } else if (!_lastCaveExploration.isExploringCave()
                    && !_lastCaveExploration.isInWaterDetour()) {
                handoffFail = "prev-not-exploring(inCave=false,detour=false)";
            } else if (_lastCaveExploration.isNetherTask() != dimNowNether) {
                handoffFail = "dim-changed";
            } else {
                BlockPos fireAnchor = MineDownExploreTask.getLastFireAnchor();
                if (fireAnchor != null && mod.getPlayer() != null
                        && fireAnchor.getSquaredDistance(mod.getPlayer().getBlockPos()) >= 64 * 64) {
                    handoffFail = "anchor-far";
                }
            }
            if (handoffFail == null) {
                Debug.logToFile("altoclef_diag.log",
                        "[MineDown-WaitDiag]\thandoff: reusing exploring instance @"
                                + Integer.toHexString(System.identityHashCode(_lastCaveExploration)));
                return _lastCaveExploration;
            }
            Debug.logToFile("altoclef_diag.log",
                    "[MineDown-WaitDiag]\thandoff gate FAIL: " + handoffFail
                            + " -> NEW instance (prev @"
                            + (_lastCaveExploration == null ? "null" : Integer.toHexString(System.identityHashCode(_lastCaveExploration)))
                            + ")");
            MineDownExploreTask exploration = new MineDownExploreTask(band[0], band[1]);
            // (2026-09-22 铁任务矿洞窗口, 用户"把铁任务的矿洞范围设为40以下, y≤40")
            // 默认窗口 带上限30+8=38, 会把正常矿洞 y39+ 的上层空气全剔成 aboveMaxY,
            // 剩单层过不了 Y 跨度闸门 (20260922-214354: natural=92/96 spans Y=1)。
            if (isIronOverworldTask()) {
                exploration.withCaveMaxY(IRON_CAVE_MAX_Y);
            }
            // (2026-09-22 钻石任务矿洞窗口, 用户"钻石矿洞范围 y=-1以下就好")
            // 默认窗口 -53+8=-45; 覆盖为 -1 (y≤-1), 下沉到 -1 起即可扫洞进洞。
            else if (isDiamondOverworldTask()) {
                exploration.withCaveMaxY(DIAMOND_CAVE_MAX_Y);
            }
            _lastCaveExploration = exploration;
            return exploration;
        }

        /**
         * (2026-09-25 挖金无进展远征) 目标是否含下界金矿: 仅挖金任务触发无进展
         * 远征 (石英/镶金黑石不纳入, 需要时再扩)。
         */
        private boolean targetsNetherGoldOre() {
            for (Block block : _blocks) {
                if (block == Blocks.NETHER_GOLD_ORE) return true;
            }
            return false;
        }

        /**
         * (2026-09-16 灵魂沙谷远征) 目标是否"表层下界矿" (金/石英/镶金黑石):
         * 这三样全下界分布但灵魂沙谷暴露少, 值得为它们远征避谷。
         */
        private boolean targetsNetherSurfaceOres() {
            for (Block block : _blocks) {
                if (block == Blocks.NETHER_GOLD_ORE || block == Blocks.GILDED_BLACKSTONE
                        || block == Blocks.NETHER_QUARTZ_ORE) {
                    return true;
                }
            }
            return false;
        }

        /** (2026-09-16 灵魂沙谷远征) 当前是否身处灵魂沙谷。 */
        private static boolean isInSoulSandValley(AltoClef mod) {
            try {
                return mod.getWorld().getBiome(mod.getPlayer().getBlockPos())
                        .matchesKey(net.minecraft.world.biome.BiomeKeys.SOUL_SAND_VALLEY);
            } catch (Exception e) {
                return false;
            }
        }

        // (2026-09-22 铁任务矿洞窗口专属上限, 用户"把铁任务的矿洞范围设为40以下",
        // y≤40 含40)。只放宽铁任务: 默认公式 30+8=38 导致 y39+ 洞空气全被
        // aboveMaxY 剔除。不动 CAVE_ABOVE_TARGET_BUFFER 共享常量 (钻石/金窗口不受影响)。
        private static final int IRON_CAVE_MAX_Y = 40;

        /** 主世界挖铁任务 (含铁矿石目标): 矿洞认定/导航窗口上限放宽到 40。 */
        private boolean isIronOverworldTask() {
            if (WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER) return false;
            for (Block block : _blocks) {
                if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
                    return true;
                }
            }
            return false;
        }

        // (2026-09-22 钻石任务矿洞窗口专属上限, 用户"钻石矿洞范围 y=-1以下就好",
        // y≤-1 含-1, 与铁任务"含40"口径一致)。只放宽钻石任务的窗口上限,
        // 采矿目标带 [-57,-53] 不变; 窗口下限仍是 -57-8=-65。
        private static final int DIAMOND_CAVE_MAX_Y = -1;

        /** 主世界挖钻石任务 (含钻石矿石目标): 矿洞认定/导航窗口上限放宽到 -1。 */
        private boolean isDiamondOverworldTask() {
            if (WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER) return false;
            for (Block block : _blocks) {
                if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 周围空气计数: [0]=CAVE_AIR(天然洞穴), [1]=普通 AIR(自挖)。
         * 扫描范围 ±8 水平, y-6~+2。供诊断日志使用。
         */
        private int[] countAirAround(AltoClef mod) {
            BlockPos center = mod.getPlayer().getBlockPos();
            int caveAir = 0, air = 0;
            for (int y = center.getY() - 6; y <= center.getY() + 2; y++) {
                for (int dz = -8; dz <= 8; dz++) {
                    for (int dx = -8; dx <= 8; dx++) {
                        BlockPos check = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        Block block = mod.getWorld().getBlockState(check).getBlock();
                        if (block == Blocks.CAVE_AIR) {
                            caveAir++;
                        } else if (WorldHelper.isAir(block)) {
                            air++;
                        }
                    }
                }
            }
            return new int[]{caveAir, air};
        }

        /**
         * (2026-09-05 目标带, 用户"高度无法确定, 只要在一个合适范围内, 适用于不同
         * 下界任务") 单点目标深度改为【生成带】[min, max]: 每个探索实例在带内随机
         * 取目标高度, 人类不会每次卡同一个 y。下界带见 MineDownExploreTask
         * NETHER_MINING_MIN/MAX_Y (32~96, 熔岩海之上/基岩顶盖之下)。
         */
        private int[] targetShaftBand() {
            for (Block block : _blocks) {
                // 下界金矿/镶金黑石/下界石英: 全下界分布 → 通用安全带 (用户原定
                // 70 换成带内随机, "合适范围即可")。
                if (block == Blocks.NETHER_GOLD_ORE || block == Blocks.GILDED_BLACKSTONE
                        || block == Blocks.NETHER_QUARTZ_ORE) {
                    return new int[]{MineDownExploreTask.NETHER_MINING_MIN_Y, MineDownExploreTask.NETHER_MINING_MAX_Y};
                }
                // 古代残骸生成带 y≈8~22: 取 [12, 22] — 整体在紧急上行危险线
                // (NETHER_ESCAPE_Y=10) 之上, 又贴近残骸富集层。
                if (block == Blocks.ANCIENT_DEBRIS) {
                    return new int[]{12, 22};
                }
            }
            // 兜底: 若当前就在下界但矿石没被上面分支覆盖 (新增下界任务/模组矿),
            // 也给通用下界带, 不再落回主世界深度。
            if (WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER) {
                return new int[]{MineDownExploreTask.NETHER_MINING_MIN_Y, MineDownExploreTask.NETHER_MINING_MAX_Y};
            }
            for (Block block : _blocks) {
                if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
                    // (2026-09-10, 用户"挖铁不应始终呆在 y=负值, 应往上走回 y=5~30,
                    // 非严格值") 铁带取 [5, 30]: 探索隧道被带下限兜住不再沉到负值
                    // (旧 {50,50} + 当时地板会一路缓沉到地板平挖), 深陷
                    // 负值时上行楼梯爬回带内 (MineDownExploreTask 主世界上行)。
                    return new int[]{5, 30};
                }
            }
            for (Block block : _blocks) {
                if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) {
                    // (2026-09-12 主世界金矿高度带, 用户"金子的高度区域理想在哪")
                    // 1.18 主世界金矿分布在 y=-64~32, 峰值 y=-16 (y<-48 另有额外
                    // 批次; 恶地 biome 额外批次上限提到 256, 地表/崖壁即可见)。
                    // 旧实现没有金矿分支 → 落到最后兜底 {50,50} → 在 y=50 平挖,
                    // 而金矿生成上限只到 y=32, y=50 处金矿近乎为 0 → "找金子做
                    // 金靴"永远挖不到矿, 叠加金靴链本身无超时 → 永久停在主世界。
                    // 带取 [-24, 0]: 整段落在富集层, floorY=max(TUNNEL_MIN_Y,-24)
                    // =-24 由【带下限】主导 (TUNNEL_MIN_Y 09-13 放开、09-14 收到 -58,
                    // 不影响本带); 上限 0
                    // 使带内随机高度仍全部在石/深板岩金矿区间, 避免随机到高处后
                    // 还要长距离下挖。带下限为负 → ascendTriggerY() 返回
                    // MIN_VALUE, 不触发上行爬升, 保持"下楼梯到带内再平挖"语义。
                    // (边界: 未做 biome 检测, 恶地内该带偏保守但仍有矿。)
                    return new int[]{-24, 0};
                }
            }
            for (Block block : _blocks) {
                if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
                    // (2026-09-14 深度带再修正, 用户"bot 挖钻石挖到基岩层困难重重, 改一下允许的
                    //  通道深度, 选范围内最适合钻石的, 也要考虑最容易挖到钻石层矿洞的深度")
                    // 事实前提 (1.18.2 worldgen):
                    //  1) 钻石共 4 批: diamond 7/chunk(size4) + medium 2/chunk(size8, 均布
                    //     -64~-4) + buried 4/chunk(size8) + large 稀有(size12)。y=-64~16 上呈
                    //     【向下加权】三角分布, 越接近基岩越密, 到基岩之前不会回落。
                    //  2) 但【空气暴露惩罚】方向相反: diamond/medium 丢弃 50%, large
                    //     丢弃 70%, buried 丢弃 100% (永不在露天露头)。→ 单位石头产钻率上
                    //     【自挖隧道 > 探洞】; 探洞的优势是"不用挖、单位时间覆盖格数多", 而
                    //     不是"同样体积里更富"。两个数要加权, 不能只按探洞挑层。
                    //  3) 基岩带 y=-64~-60 (20260914-132420 实锤: (-214,-60,279) 撞基岩傻挖 17s);
                    //     深层含水层约 y=-54 以下由水变岩浆。
                    // 为何放弃 {-59,-53} 的下沿 -59:
                    //  - 楼梯下行要把脚放进 targetY, 镐子实际碰的行是 targetY-1 ⇒ targetY=-59
                    //    必须去挖 -60 行, 而 -60 就是基岩 (这是 20260914-132420 卡死的直接成因) —
                    //    现在有不可破坏拦截保底不会傻挖, 但每次命中仍要改道, 还可能留下
                    //    1 格高不可通行断面。⇒ targetY ≥ -58 是不可破的硬底。
                    //  - 密度梯度其实很缓 (约每行 1%: 按 (16-y)/80 估, -59→-53 只丢 ~8%), 而
                    //    -59 以下全是基岩 ⇒ "-59 峰值"的收益远抵不上撞基岩/岩浆的代价。
                    //  - 越深越遇不到可触发的【天然大洞】(洞口判据要 X/Z 跨度 ≥4×4、天然空气
                    //    ≥24; 深层只剩 1~2 格宽的面条洞, 永远不满足) → 越深越只能硬挖深板岩。
                    // 结论: 取 [-57,-53]。上沿 -53 = 常见"避开 -54 起岩浆"安全线, 也是最容易
                    // 遇到深层大洞的高度; 下沿 -57 仍在三角分布高密段, 最深镐子行 -58 距基岩
                    // (-60) 留 2 行余量。带内随机 ⇒ 每轮落在不同高度, 自挖与探洞两侧都覆盖。
                    return new int[]{-57, -53};
                }
            }
            return new int[]{50, 50}; // Coal/stone fallback (铁已专属 [5,30] 带, 见上).
        }

        @Override
        protected Task onTick(AltoClef mod) {
            _mod = mod;
            if (_miningPos != null && !_progressChecker.check(mod)) {
                // (2026-09-15 够得着才挥, 现场 20260915-172416) 挥击清草只对"已在
                // 挖掘距离内但挖不动"有效。导航途中 baritone 绕坑寻路停顿 >1s 也会
                // 让进度判负 —— 该场 bot 站坑边对 7 格外的原木连挥 33 次/7s, 判负
                // 计数被微动不断重置, 永远到不了拉黑出口 (无 "Still failed to mine"),
                // 纯空转+刷屏, 用户观感即"卡住"。够不着时: 不挥、不计负、进度判负
                // 作废, 交给导航 (导航卡死由 DestroyBlockTask 的 _moveChecker/放弃
                // 闸门兜底)。判据与 DestroyBlockTask 同源 (LookHelper.getReach =
                // 真实 reach + LOS, 本仓"同一概念只留一个判据源")。
                if (LookHelper.getReach(_miningPos).isEmpty()) {
                    if (_swingTicksLeft > 0) {
                        _swingTicksLeft = 0;
                        mod.getInputControls().release(Input.CLICK_LEFT);
                    }
                    _progressChecker.reset();
                    return trackSuperTick(super.onTick(mod));
                }
                // (2026-09-04 用户"卡住先左键挥几tick清草") 挖不动先挥击清草, 不
                // 立即 blacklist: 草/藤蔓/草丛等小方块挡路时 bot 挖不动目标, 先
                // 长按左键 SWING_TICKS 清掉, 挥完仍失败才 requestBlockUnreachable
                // + 换目标。避免"卡在草里 → 直接放弃目标"。
                if (_swingTicksLeft > 0) {
                    _swingTicksLeft--;
                    if (_swingTicksLeft == 0) {
                        mod.getInputControls().release(Input.CLICK_LEFT);
                        Debug.logMessage("Still failed to mine after swing, blacklisting target.");
                        mod.getBlockTracker().requestBlockUnreachable(_miningPos, 2);
                        _blacklist.add(_miningPos);
                        _miningPos = null;
                        _progressChecker.reset();
                    } else {
                        mod.getInputControls().hold(Input.CLICK_LEFT);
                    }
                    return trackSuperTick(super.onTick(mod));
                }
                // 首次失败: 开始挥击 (长按左键清挡路小草)
                _swingTicksLeft = SWING_TICKS;
                Debug.logMessage("Failed to mine block, swinging to clear grass/foliage...");
                mod.getInputControls().hold(Input.CLICK_LEFT);
                return trackSuperTick(super.onTick(mod));
            }
            // 有进展/正常: 保险松开可能按住的左键
            if (_swingTicksLeft > 0) {
                _swingTicksLeft = 0;
                mod.getInputControls().release(Input.CLICK_LEFT);
            }
            return trackSuperTick(super.onTick(mod));
        }

        @Override
        protected Task getGoalTask(Object obj) {
            if (obj instanceof BlockPos newPos) {
                if (_miningPos == null || !_miningPos.equals(newPos)) {
                    _progressChecker.reset();
                }
                _miningPos = newPos;
                // (2026-09-01 用户"挖到矿就重置矿洞探索超时") 在追/挖矿石
                // (包括矿洞探索途中) 时重置探索看门狗: 有产出的探索不被超时掐断。
                if (_explorationTask instanceof MineDownExploreTask explore) {
                    explore.markOreMined();
                }
                return new DestroyBlockTask(_miningPos);
            }
            if (obj instanceof ItemEntity itemEntity) {
                _miningPos = null;
                // (2026-08-22 修补) 本次去捡掉落物 → 记下时间启动冷却, 防止
                // 捡完立刻又切去挖再切回捡 (横跳)。
                _lastDropPickupMs = System.currentTimeMillis();

                if (_mod.getItemStorage().getSlotThatCanFitInPlayerInventory(itemEntity.getStack(), false).or(() -> StorageHelper.getGarbageSlot(_mod)).isEmpty()) {
                    return new EnsureFreeInventorySlotTask();
                }

                return _pickupTask;
            }
            throw new UnsupportedOperationException("Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected boolean isValid(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                // (2026-09-22 竖井步放行) 竖井进行中, 脚下方块是当前竖井步 —— 竖井是
                // 【下降手段】不是材料来源, 可以不是目标块(草/石照挖), 只要可挖不邻液体。
                // 不放行的话框架 isValid 否决 → 重选 → 竖井目标永远立不住 (0922-091750
                // 泥土任务站在草地上, 脚下第一格就是草方块)。
                if (_shaftBaseY != Integer.MIN_VALUE && !_horizontalMode
                        && mod.getPlayer() != null
                        && b.equals(mod.getPlayer().getBlockPos().down(1))) {
                    return WorldHelper.canBreak(mod, b) && !WorldHelper.isAdjacentToLiquid(mod, b);
                }
                // Don't use BlockTracker.blockIsValid here — it checks the unreachable cache
                // which doesn't know about WorldMemory positions we remember from afar.
                if (!WorldHelper.canBreak(mod, b)) return false;
                if (!mod.getChunkTracker().isChunkLoaded(b)) {
                    // Chunk isn't loaded — trust the remembered/tracked position until we can verify.
                    return true;
                }
                Block actual = mod.getWorld().getBlockState(b).getBlock();
                for (Block target : _blocks) {
                    if (actual == target) {
                        // (20260915 挖金猪灵闸) 正在挖/赶路途中猪灵聚到 16 格内
                        // (>1) → 当前目标立即失效, 上层重选 (全被闸 → wander 离开)。
                        return !piglinDensityGated(mod, b);
                    }
                }
                return false;
            }
            if (obj instanceof ItemEntity drop) {
                // (2026-09-02 isAlive: 已消失(被捡/despawn)的掉落物立即失效, 防 pursuit
                // 粘住陈旧实体 → PickupDroppedItemTask 卡 "Waiting for dropped item to land")。
                if (!drop.isAlive()) return false;
                Item item = drop.getStack().getItem();
                for (ItemTarget target : _targets) {
                    if (target.matches(item)) return true;
                }
                return false;
            }
            return false;
        }

        @Override
        protected void onStart(AltoClef mod) {
            _progressChecker.reset();
            _miningPos = null;
            _blindNullTicks = 0;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {

        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof MineOrCollectTask task) {
                return Arrays.equals(task._blocks, _blocks) && Arrays.equals(task._targets, _targets);
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Mining or Collecting";
        }

        public boolean isMining() {
            return _miningPos != null;
        }

        public BlockPos miningPos() {
            return _miningPos;
        }

        /** (2026-09-10 贪婪掉落宽限) 最近一次目标方块被挖掉的墙钟时刻 (0=无)。 */
        public long miningTargetBrokenMs() {
            return _miningTargetBrokenMs;
        }
    }

}
