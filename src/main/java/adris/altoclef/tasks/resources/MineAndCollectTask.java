package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.BiasedExploreTask;
import adris.altoclef.tasks.movement.MineDownExploreTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.CursorSlot;
import baritone.api.utils.input.Input;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
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

    // 砍树前做斧子的 COMMIT 状态：一旦决定做斧子就执行到底，不每 tick
    // 重估条件（修复 ping-pong，见 onResourceTick 注释）。
    private boolean _axeCommitted = false;
    private Task _axeTask = null;

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
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Picking up is controlled by a separate task here.
        return true;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        if (!StorageHelper.miningRequirementMet(mod, _requirement)) {
            return new SatisfyMiningRequirementTask(_requirement);
        }

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
                Debug.logMessage("[Axe] Committing to axe chain before chopping wood.");
            }
            if (_axeCommitted) {
                setDebugState("Crafting an axe before chopping wood");
                if (_axeTask == null) {
                    _axeTask = computeNextAxeTask(mod);
                    // 算不出下一步 → 理论不该发生, 解除 COMMIT 继续砍树
                    if (_axeTask == null) {
                        _axeCommitted = false;
                        return _subtask;
                    }
                }
                return _axeTask;
            }
        } else {
            // 有斧子了（或不是砍树任务）：解除提交状态。
            _axeCommitted = false;
            _axeTask = null;
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
        // 有台 + 木板不足但原木够合成木板(有效≥3) + 木棍够 → 直接派木斧(内部Collect会合木板)
        if (hasTableUse && effectivePlanks >= 3 && sticksReal >= 2) return true;
        // 全自给(最常见): 没台, 但有效木板≥8 = 4做台 + 3做斧 + 2木棍需1木板 = 8
        // (2026-09-02 递归修复) 仅无台时允许全自给 COMMIT: 有台但缺木棍时做不了
        // 木斧, computeNextAxeTask 会因缺棍落到撸原木 fallback → 形成 wood→做斧→
        // 撸原木→wood 自我递归 (tasks.log t=784 实锤卡死)。有台+缺棍返回 false,
        // 回主收集任务砍树攒料, 木棍由后续合成阶段处理, 不卡死也不递归。
        return !hasTableUse && effectivePlanks >= 8;
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
        if (sticksReal >= 2 && effectivePlanks >= 3) {
            Debug.logMessage("[Axe] phase -> wooden_axe (table=" + hasTableUse + " effPlanks=" + effectivePlanks + " sticks=" + sticksReal + ")");
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
        if (_subtask.isTargetingWood()) return true;
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
        if (_greedy) {
            return !hasNearbyMineableTarget(mod) || holdGreedyForDrop(mod);
        }
        return !hasNearbyMineableTarget(mod);
    }

    // (2026-09-10 贪婪掉落宽限) 掉落物落地+感知回册的宽限窗口。落地约 0.5s,
    // 回册再 1 tick, 2.5s 足够; 上限防"掉落物永远感知不到"(掉岩浆/滚远)时无限挂住。
    // 本方法只在 !hasNearbyMineableTarget 时被调 (itemDropped 已为 false, 掉落物
    // 尚未回册), 所以只需按破坏时刻计时; 掉落物一旦回册, hasNearbyMineableTarget
    // 的 itemDropped 条款自动接管挂住/捡取。
    private static final long GREEDY_DROP_GRACE_MS = 2500;
    private long _greedyGraceBrokenMs = 0;
    private long _greedyGraceLogEpisodeMs = 0;

    private boolean holdGreedyForDrop(AltoClef mod) {
        long brokenMs = _subtask.miningTargetBrokenMs();
        if (brokenMs == 0) return false;
        _greedyGraceBrokenMs = brokenMs;
        boolean holding = System.currentTimeMillis() - brokenMs < GREEDY_DROP_GRACE_MS;
        if (holding && _greedyGraceLogEpisodeMs != brokenMs) {
            _greedyGraceLogEpisodeMs = brokenMs;
            Debug.logToFile("altoclef_diag.log",
                    "[GreedyPickup]\thold after ore break (grace " + GREEDY_DROP_GRACE_MS
                            + "ms) — waiting for drop landing/perception");
        }
        return holding;
    }

    private static final double MINE_EVERYTHING_RANGE = 16;

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
            BlockPos miningPos = _subtask.miningPos();
            if (miningPos == null && mod.getControllerExtras().isBreakingBlock()) {
                miningPos = mod.getControllerExtras().getBreakingBlockPos();
            }
            if (miningPos == null) return;
            BlockState state = mod.getWorld().getBlockState(miningPos);
            if (state.isAir()) return; // 位置陈旧/方块已挖掉, 不换工具
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
        // 判据: 必须是 LOS 真感知(不透视), 且在本窗口内(40s)、直线距离 ≤48 格; 只要
        // 眼前有任何可感知矿, 仍优先眼前(既有"舍近求远不可取"原则不受影响)。
        private final java.util.Map<BlockPos, Long> _seenOreTick = new java.util.HashMap<>();
        private static final long MEMORY_NAV_LIFETIME_TICKS = 800; // 40s 记忆回溯窗口
        private static final double MEMORY_NAV_RANGE = 48; // 记忆兜底导航距离上限
        // (2026-09-01 贪心触发放宽后修复, 用户"贪心挖目标矿的3x3x3") 贪心触发能看见
        // GREEDY 范围内的可见矿(CollectVisibleOresTask 8格), 但本任务原先只认玩家
        // 贴身 3x3x3 → 远处可见矿无起始目标 → "Waiting for calculations" 空转卡住。
        // 补: 节流地找 GREEDY_TARGET_RANGE 内最近的可感知目标矿作起始目标。
        private static final int GREEDY_TARGET_RANGE = 8;
        private final adris.altoclef.util.time.TimerGame _greedyTargetScanTimer = new adris.altoclef.util.time.TimerGame(0.5);
        private BlockPos _greedyTargetCache = null;
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
        private static final int SURFACE_SEA_LEVEL_Y = 63;

        // 近处矿绕过 FOV 的距离上限（格）。地下矿洞/隧道场景矿都在这个
        // 范围内（Diag-blind 实测被拒矿 4-15 米）；太远的缓存矿仍可能埋在
        // 岩石深处，不值得专程走。
        private static final double NEAR_ORE_NO_FOV_RANGE = 24;

        // wander 探索任务重建冷却（tick，100=5 秒）。
        private static final long EXPLORE_RECREATE_COOLDOWN_TICKS = 100;

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets) {
            this(blocks, targets, false);
        }

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets, boolean greedy) {
            _blocks = blocks;
            _targets = targets;
            _greedy = greedy;
            _pickupTask = new PickupDroppedItemTask(_targets, true);
        }

        private boolean isTargetingWood() {
            for (Block b : _blocks) {
                if (WOOD_LOG_BLOCKS.contains(b)) return true;
            }
            return false;
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
            }
            return false;
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
            // (2026-08-30 木头立即拾取强制, 用户) 木头/床挖完必须立刻捡掉落, 不能
            // 等整片挖完再统一捡。原逻辑掉落优先级排在 _miningPos 粘性之后 → 挖完
            // 一根原木, getClosestTo 被"下一个还没挖完的原木"粘住, 这根越滚越不捡。
            // 木头/床: 掉落最优先, 脚下掉了马上捡再继续挖。
            if (isCollectDropsImmediately() && closestDrop.isPresent()) {
                return closestDrop.map(Object.class::cast);
            }
            // [2026-08-22 挖矿目标粘性] 正在挖的方块还没挖完时绝不换目标:
            // 铁矿脉多块距离相近, 挖掉一块(或掉落物刷新)后"最近目标"切换
            // → DestroyBlockTask 重启 → 破坏进度清零, 每 1-2 秒换一个目标
            // "挖了几个还没挖完又转头" (tasks.log 11:36:57 t=16304-16748
            // "Trying out NEW pursuit"/"Retrying old heuristic" 交替 7 个
            // 不同目标)。粘住 _miningPos 直到它失效(挖掉/不可挖/黑名单)。
            if (_miningPos != null && !_blacklist.contains(_miningPos)) {
                // (2026-09-03 兜底刷新, 用户"挖掉一个矿以为还在, 站原地傻等") 目标方块已
                // 消失(世界验证为空气)时立即清空 _miningPos 刷新任务 — 不粘已挖掉的位置,
                // 让后续逻辑重新锁最近目标/掉落物/探索。仅对已加载 chunk 判消失(未加载
                // 的 getBlockState 恒 air, 直接判会误清远处合法目标)。
                if (mod.getChunkTracker().isChunkLoaded(_miningPos)
                        && mod.getWorld().getBlockState(_miningPos).isAir()) {
                    // (2026-09-10 贪婪掉落宽限) 方块刚被挖掉 → 记录时刻, 供外层
                    // isFinished 在贪婪模式下挂住等掉落物落地回册。
                    _miningTargetBrokenMs = System.currentTimeMillis();
                    _miningPos = null;
                } else if (isValid(mod, _miningPos)) {
                    return Optional.of(_miningPos);
                }
            }
            // (2026-08-28 近场即时, 用户) 刚挖完这块, 立刻扫它周围的 3x3x3:
            // 紧贴刚挖穿的连位矿下一 tick 就被看到并续挖 — 完全不需要缓存/节流,
            // 也不受挖矿位置远近影响 (锚定的是矿, 不是玩家)。
            Optional<BlockPos> nearField = getNearFieldOreAt(mod, _miningPos);
            if (nearField.isPresent()) {
                return nearField.map(Object.class::cast);
            }
            if (_greedy) {
                // (2026-08-28 贪心=纯贴身) 先认玩家近身 3x3x3 紧贴的矿 (起始/链尾)。
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
            int cacheRange = bedTarget ? 96 : (isTargetingWood() ? 64 : 24);
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
            if (isCollectDropsImmediately()) {
                // (2026-08-27 床边挖边捡, 用户) 上面"挖完本处再统一捡"的
                // 优先级是给挖矿的 (矿脉落地物先放着, 挖完一块还有下一块)。
                // 木头/床不一样: 木树高, 一次砍倒掉落物散一地; 床拆一张只掉
                // 一个。所以对这两类目标, 掉落物优先级最高 — 脚下掉了就
                // 立刻捡起再继续, 不直奔下一个方方块。
                if (closestDrop.isPresent()) {
                    return closestDrop.map(Object.class::cast);
                }
                if (nearbyMine.isPresent()) {
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
                if (!WorldHelper.canBreak(mod, p)) continue;
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
            return Optional.ofNullable(best);
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
                    double dSq = p.getSquaredDistance(fromPos);
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
            candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(fromPos)));
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
                        double dSq = p.getSquaredDistance(fromPos);
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
         * (2026-08-28 近场即时, 用户) 围绕【正在挖/刚挖完的那块】(center) 扫 3x3x3,
         * 找紧贴它的可挖目标矿。范围极小 (27 块), 每 tick 直接采样世界, 不缓存
         * 不限流 —— 挖穿一块, 邻位暴露的连位矿下一 tick 就被看见并续挖, 彻底摆脱
         * 对异步 BlockTracker 缓存和节流扫描的依赖 (人挖一个马上挖下一个)。
         * center 为空 (还没开始挖) 时返回空, 由冷启动的缓存/世界扫描负责首个目标。
         */
        private Optional<BlockPos> getNearFieldOreAt(AltoClef mod, BlockPos center) {
            if (center == null) return Optional.empty();
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
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
                        double dSq = player.getSquaredDistance(p);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                            best = p;
                        }
                    }
                }
            }
            _greedyTargetCache = best;
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
         * (2026-08-28) 近场是否有紧贴正在挖那块的可挖连位矿 (供 isFinished 判断)。 */
        boolean hasNearFieldTarget(AltoClef mod) {
            Optional<BlockPos> m = getNearFieldOreAt(mod, _miningPos);
            if (m.isPresent()) return true;
            // 贪心: 玩家近身也有紧贴矿也算"还有活", 任务不结束 (等 getClosestTo 派发)。
            return _greedy && getNearFieldOreAt(mod, mod.getPlayer().getBlockPos()).isPresent();
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

        @Override
        protected Task getWanderTask(AltoClef mod) {
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
                        : _explorationTask.toDebugString() + "@" + Integer.toHexString(System.identityHashCode(_explorationTask));
                boolean prevFinished = finished;
                _explorationTask = createNextExplorationTask(mod);
                _lastExploreCreateTick = nowTick;
                Debug.logToFile("altoclef_diag.log",
                        "[MineDown-WaitDiag]\texploration recreated: prev=" + prevDesc
                                + " prevFinished=" + prevFinished
                                + " grounded=" + MineDownExploreTask.isPlayerGrounded(mod)
                                + " new=" + _explorationTask.toDebugString()
                                + "@" + Integer.toHexString(System.identityHashCode(_explorationTask)));
            }
            return _explorationTask;
        }

        /**
         * 找近处仅因 FOV 被拒的已知矿（遮挡/太远的转头也没用）。
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
                if (VisibilityHelper.isBlockPerceivable(mod, pos)) continue; // 已可见，无需转头
                if (!VisibilityHelper.perceivableFailReason(mod, pos).startsWith("outside FOV")) continue;
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
                // If we're deep underground, push the first bias toward "climb
                // back up to sea level" first — otherwise we'll wander a cave
                // forever looking for trees that don't exist there.
                int y = mod.getPlayer().getBlockPos().getY();
                if (y < SURFACE_SEA_LEVEL_Y - 2) {
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
            int[] band = targetShaftBand();
            // (2026-09-07 探索断点交接) 链重建后认领仍在探索中的上一个探索任务:
            // 同维度且玩家仍在最近 FIRE 锚点 64 格内 → 原样复用(MineDownExploreTask
            // 的 isEqual 是 identity, 同一对象不会被框架重启重置), 探索无缝继续;
            // 玩家已远离/维度已变/探索已结束 → 正常新建。
            // (2026-09-10 探索断裂诊断) 闸门结果与失败原因打点 — 定位"坠落窗口
            // 内 prev 尚未 FIRE(isExploringCave=false) → 交接失败 → 全新实例"。
            boolean dimNowNether = WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER;
            String handoffFail = null;
            if (_lastCaveExploration == null) {
                handoffFail = "no-prev";
            } else if (!_lastCaveExploration.isExploringCave()) {
                handoffFail = "prev-not-exploring(inCave=false)";
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
            _lastCaveExploration = exploration;
            return exploration;
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
                        } else if (block == Blocks.AIR) {
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
                    // (旧 {50,50} + TUNNEL_MIN_Y=-24 会一路缓沉到 -24 平挖), 深陷
                    // 负值时上行楼梯爬回带内 (MineDownExploreTask 主世界上行)。
                    return new int[]{5, 30};
                }
            }
            for (Block block : _blocks) {
                if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
                    // 1.18 diamond band: dig down to y=-24, then MineDownExploreTask
                    // switches to horizontal branch-mining at that depth
                    // (TUNNEL_MIN_Y matches). Starting higher wastes the run
                    // above the diamond band.
                    return new int[]{-24, -24};
                }
            }
            return new int[]{50, 50}; // Coal/stone fallback (铁已专属 [5,30] 带, 见上).
        }

        @Override
        protected Task onTick(AltoClef mod) {
            _mod = mod;
            if (_miningPos != null && !_progressChecker.check(mod)) {
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
                // Don't use BlockTracker.blockIsValid here — it checks the unreachable cache
                // which doesn't know about WorldMemory positions we remember from afar.
                if (!WorldHelper.canBreak(mod, b)) return false;
                if (!mod.getChunkTracker().isChunkLoaded(b)) {
                    // Chunk isn't loaded — trust the remembered/tracked position until we can verify.
                    return true;
                }
                Block actual = mod.getWorld().getBlockState(b).getBlock();
                for (Block target : _blocks) {
                    if (actual == target) return true;
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
