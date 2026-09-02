package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * (2026-08-22 贪心收集) 顺手挖掉眼前所有可见矿石。
 *
 * 用户设计: 即使当前主线是"获得铁", 看到煤炭也该顺手挖一下, 看到
 * 铁矿该顺手全挖完 — 而不是只锁定目标种类。每个种类带数量上限
 * (煤 32 / 铁 32 / 金 12 / 钻石 8), 达上限就跳过该种类, 防止无限
 * 挖煤把主线饿死。
 *
 * 触发条件 (hasWork): 矿石【真·可见】(同洞穴: 12 格内空气 BFS 能从 bot 连通到
 * 其侧面) 且 canBreak 且不贴液体; 镐等级满足; 背包数量未达上限。只认同洞穴
 * 可摸到的矿, 隔层岩壁的一律不算 (不透视), 也不追缓存里的远矿。
 *
 * 防横跳: 内部粘性 — 一旦选中某种类 (优先可见块数最多的), 派发的
 * MineAndCollectTask 会执行到【数量达上限 且 眼前 16 格无同类可挖】
 * (MineAndCollectTask.isFinished 的扫尾语义) 才轮换下一种类。绝不
 * 每 tick 重选。
 *
 * 打断安全: 由调用方 (BeatMinecraft2Task) 保证只在【无容器界面打开】
 * 时派发 — 合成/熔炉操作中被打断会把材料丢在合成格里。
 */
public class CollectVisibleOresTask extends Task {

    /**
     * 一个矿石种类: 方块变体 + 产出物品 + 数量上限 + 镐需求 + 计入上限
     * 的物品集合 (生矿 + 已烧制的锭 — 用户 2026-08-23: 烧好的铁也算
     * 进 32, 达标就不再触发挖矿)。
     */
    private record OrePlan(Block[] blocks, Item item, int cap, MiningRequirement req, Item[] countItems) {}

    private static final OrePlan[] PLANS = {
            new OrePlan(new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE}, Items.COAL, 32, MiningRequirement.WOOD, new Item[]{Items.COAL}),
            new OrePlan(new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE}, Items.RAW_IRON, 32, MiningRequirement.STONE, new Item[]{Items.RAW_IRON, Items.IRON_INGOT}),
            new OrePlan(new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE}, Items.RAW_GOLD, 12, MiningRequirement.IRON, new Item[]{Items.RAW_GOLD, Items.GOLD_INGOT}),
            new OrePlan(new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE}, Items.DIAMOND, 8, MiningRequirement.IRON, new Item[]{Items.DIAMOND}),
    };

    // (2026-09-01 贪心触发"看见"放宽, 用户"看到钻石都不触发贪心挖") 旧 3x3x3 贴身
    // 只算"触碰到"的矿, 眼前几格可见的钻石 vis=0 永不触发。改为扫 GREEDY_VISIBLE_RANGE
    // 且必须可感知(见 isOreVisibleToGreedy, LOS+isBuriedOre 防透视, 但放宽到"暴露面
    // 可见"而非死板中心 LOS) — 眼前可见的矿才触发, 被岩石包住的矿仍不可感知,
    // 不破坏防透视。
    // (2026-09-01 v2 用户"看见钻石依旧不挖") 8 格仍太近(钻石常在 8~16 格墙上),
    // 且部分嵌岩的矿中心 LOS 被周边挡 → vis=0。扩到 16 格 + 宽松暴露面判定。
    private static final int GREEDY_VISIBLE_RANGE = 16;

    // hasAnyVisibleOreWork / onTick 的静态节流缓存: 两者都可能每 tick 被
    // 调用, 而 countAllVisible 是 12 格暴力扫描 (1 秒一次可接受, 每 tick 不行)。
    private static long _lastCheckMs = 0;
    private static boolean _lastHasWork = false;
    private static Map<OrePlan, OreStat> _lastCounts = null;
    // (2026-08-28 感知延迟 3→1, 用户) 见矿→触发响应缩短, 见 onTick 注释。
    private static final long CHECK_INTERVAL_MS = 1000;

    /** block → 所属矿石种类 (快速查找)。 */
    private static final Map<Block, OrePlan> BLOCK_TO_PLAN = new HashMap<>();
    static {
        for (OrePlan plan : PLANS) {
            for (Block b : plan.blocks()) {
                BLOCK_TO_PLAN.put(b, plan);
            }
        }
    }

    /**
     * 一次扫描里某类矿的判定计数 (用于 [GreedyOre] 诊断)。
     * 只需 count: 现在是纯 3x3x3 贴身扫描, 没有 bfs/canBreak 中间环节。
     */
    private static class OreStat {
        int count = 0;
    }

    private MineAndCollectTask _active = null;
    // 当前 _active 对应的矿种 (动态刷新对比用)。
    private OrePlan _activePlan = null;
    // (2026-08-22 动态刷新) 挖矿/导航途中重新评估目标的节流 (1 秒一次,
    // 2026-08-28 由 3s 缩短, 与感知延迟一致)。
    private long _lastRefreshMs = 0;
    private static final long REFRESH_INTERVAL_MS = 1000;

    @Override
    protected Task onTick(AltoClef mod) {
        if (_active != null && _activePlan != null) {
            // (2026-08-23) 达标判定按"生矿+锭"动态计数 (含光标) — 原只数
            // 生矿: 烧好后生铁跌破 32 会重新触发挖矿 (用户: 烧制产物也
            // 算进 32)。中途被 gear 任务拿去烧一部分同样成立。
            if (countedItems(mod, _activePlan) >= _activePlan.cap() || _active.isFinished(mod)) {
                _active = null;
                _activePlan = null;
            } else {
                // (2026-08-22 动态刷新) 导航/挖矿途中每 3 秒重新扫描一次:
                // 若视野内出现"明显更丰富"的其他矿种, 立即切换目标 —
                // 让模型路过更近的物资 (用户设计: 被导航导向某矿的途中,
                // 发现可贪心矿应立马转向)。切换条件: 新矿种可见块数严格
                // 多于当前目标 (防止轻微波动导致横跳; 3 秒节流兜底)。
                long nowMs = System.currentTimeMillis();
                if (nowMs - _lastRefreshMs >= REFRESH_INTERVAL_MS) {
                    _lastRefreshMs = nowMs;
                    Map<OrePlan, OreStat> counts = countAllVisible(mod);
                    OrePlan best = null;
                    int bestCount = 0;
                    for (OrePlan plan : PLANS) {
                        if (planExecutable(mod, plan) <= 0) continue;
                        OreStat st = counts.get(plan);
                        int v = st == null ? 0 : st.count;
                        if (v > bestCount) {
                            bestCount = v;
                            best = plan;
                        }
                    }
                    if (best != null && best != _activePlan) {
                        OreStat cur = counts.get(_activePlan);
                        int curV = cur == null ? 0 : cur.count;
                        if (bestCount > curV) {
                            Debug.logMessage("[GreedyOre] switching " + _activePlan.item()
                                    + " -> " + best.item() + " (richer/nearer: " + bestCount + " vs " + curV + ")");
                            // (2026-08-27 目标数=1, 用户修复) 不要追着凑满 cap(32):
                            // MineAndCollectTask 的 isFinished 有"16 格内扫尾挖完"语义,
                            // 目标=1 时会挖完眼前这处矿脉即停, 返回主流程, 不会因数量
                            // 不够去"Digging down to find ores"硬挖到底。
                            _active = new MineAndCollectTask(new ItemTarget(best.item(), 1), best.blocks(), best.req(), true);
                            _activePlan = best;
                            setDebugState("Greedy mining " + best.item().toString());
                        }
                    }
                }
                return _active;
            }
        }
        // 选可见块数最多的种类 (大概率是脚下正对着的大矿脉)。
        Map<OrePlan, OreStat> counts = countAllVisible(mod);
        OrePlan best = null;
        int bestCount = 0;
        for (OrePlan plan : PLANS) {
            if (planExecutable(mod, plan) <= 0) continue;
            OreStat st = counts.get(plan);
            int visible = st == null ? 0 : st.count;
            if (visible > bestCount) {
                bestCount = visible;
                best = plan;
            }
        }
        if (best == null) return null;
        // (2026-08-27 目标数=1, 用户修复) 见上方切换分支注释: cap(32) 是"已有≥32
        // 就不触发贪心"的上限抑制, 不是挖矿目标。目标传 1, 靠 MineAndCollectTask
        // 的"16 格内扫尾挖完"把眼前这处矿清光即停, 不再追着凑 32 硬挖到底。
        _active = new MineAndCollectTask(new ItemTarget(best.item(), 1), best.blocks(), best.req(), true);
        _activePlan = best;
        setDebugState("Greedy mining " + best.item().toString());
        return _active;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-01 防横跳, 用户"贪心引发任务横跳") 旧语义"范围内还有可见矿就永不
        // 结束"(return !hasAnyVisibleOreWork): 触发放宽到 8 格可见后, 矿洞里可见矿
        // 几乎一直存在 → 贪心任务永不释放 → 反复打断主线铁套任务 → 铁套↔贪心每
        // ~2s 互抢 (日志 09:21 实锤)。改为【当前批次挖完即释放】(有界贪心):
        // _active(本批 MineAndCollectTask)挖完 → 完成交还主线; 由 BeatMinecraft2Task
        // 的冷却(已加大到 8s)控制下次再触发, 主线获得长窗口推进。
        if (_active == null) {
            // 还没选目标(或当前无可见矿): 有可见活就继续等 onTick 建目标, 否则完成。
            return !hasAnyVisibleOreWork(mod);
        }
        return _active.isFinished(mod);
    }

    /**
     * (2026-08-22 bug 修复) 纯函数版"是否该顺手挖矿"检查。
     * 原 BeatMinecraft2Task 用 `new CollectVisibleOresTask().isFinished(mod)`
     * 做探针: 构造实例时 onStart 会订阅 BlockTracker，但探针对象从不被
     * 任务链条纳入 tick，因此永远不会走 onStop 取消订阅——每 3 秒一次的
     * 检查会让 8 种矿石的 trackBlock 引用计数累积泄漏（只加不减）。
     * 改用本静态方法，不碰 onStart/onStop，不依赖实例状态。
     *
     * (2026-08-22 二次修复) 可见性统计不再依赖 BlockTracker 缓存——因为
     * 改成纯函数后煤/铜/金等从未被 trackBlock，getKnownLocations 恒为空，
     * 顺手挖矿对它们永不触发（用户实测 14:18-14:23 会话: 全程只查铁矿，
     * 煤炭路过不挖）。改直接扫世界真实方块。每 3 秒一次 (调用方冷却)。
     */
    public static boolean hasAnyVisibleOreWork(AltoClef mod) {
        Map<OrePlan, OreStat> counts = countAllVisible(mod);
        StringBuilder diag = new StringBuilder("[GreedyOre] check |");
        boolean hasWork = false;
        for (OrePlan plan : PLANS) {
            int remain = planExecutable(mod, plan);
            OreStat st = counts.get(plan);
            int visible = st == null ? 0 : st.count;
            int have = countedItems(mod, plan);
            diag.append(" ").append(plan.item().toString())
                    .append("(have=").append(have)
                    .append(",rem=").append(remain)
                    .append(",vis=").append(visible)
                    .append(")");
            if (remain > 0 && visible > 0) hasWork = true;
        }
        diag.append(" -> ").append(hasWork ? "TRIGGER" : "skip");
        Debug.logToFile("altoclef_diag.log", diag.toString());
        _lastHasWork = hasWork;
        return _lastHasWork;
    }

    /**
     * 已计入上限的数量: 生矿 + 已烧制的锭 (用户 2026-08-23: 烧制产物也
     * 算进 32, 达标不再触发)。getItemCountInventoryOnly 含光标 (捡掉落
     * 物时光标拿着矿不会误判未达标)。
     */
    private static int countedItems(AltoClef mod, OrePlan plan) {
        int total = 0;
        for (Item item : plan.countItems()) {
            total += mod.getItemStorage().getItemCountInventoryOnly(item);
        }
        return total;
    }

    /** 剩余可收数量 (cap - 已计入, 含烧制产物), 镐不满足返回 0。 */
    private static int planExecutable(AltoClef mod, OrePlan plan) {
        // (2026-08-23 金矿让位) 铁装备(铁剑+盾牌)未完成时金矿不参与贪心 —
        // 铁装备制作/穿上优先于挖金 (用户原则), 否则贪心挖金抢占 gear
        // 任务, 铁装备迟迟不齐。铁阶段完成后金矿恢复参与 (阈值 12)。
        if (plan.item() == Items.RAW_GOLD && !BeatMinecraft2Task.isIronGearPhaseDone(mod)) return 0;
        if (!StorageHelper.miningRequirementMet(mod, plan.req())) return 0;
        return Math.max(0, plan.cap() - countedItems(mod, plan));
    }

    /**
     * (2026-08-22 修复) 统计玩家 VISIBLE_RANGE 内"可挖可见"的矿石数量 (按种类)。
     *
     * 原实现依赖 BlockTracker 缓存 (getKnownLocations + isBlockPerceivable)。
     * 但贪心检查改成纯静态函数后不再 trackBlock, 煤/铜/金等从未被
     * BlockTracker track → 缓存永远为空 → 顺手挖矿永不触发。改为直接
     * 扫描世界真实方块, 不依赖任何跟踪状态。调用方用 3 秒冷却兜底性能;
     * canBreak/isBlockPerceivable 只对命中的少数矿石方块调用。
     */
    public static Map<OrePlan, OreStat> countAllVisible(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastCheckMs < CHECK_INTERVAL_MS && _lastCounts != null) {
            return _lastCounts;
        }
        _lastCheckMs = now;
        BlockPos player = mod.getPlayer().getBlockPos();
        int r = GREEDY_VISIBLE_RANGE;
        // (2026-09-01 触发"看见"放宽 + 防透视) 扫玩家近身 r 格, 但每个候选矿必须
        // 过 isOreVisibleToGreedy (LOS 视线 + isBuriedOre 包裹判定, 且暴露面可见即算)
        // — 眼前可见的矿才计入 vis, 被岩石包住的矿(即使 r 格内)不算, 不透视。
        Map<OrePlan, OreStat> counts = new IdentityHashMap<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = player.add(dx, dy, dz);
                    OrePlan plan = BLOCK_TO_PLAN.get(mod.getWorld().getBlockState(pos).getBlock());
                    if (plan == null) continue;
                    if (!isOreVisibleToGreedy(mod, pos)) continue;
                    OreStat stat = counts.computeIfAbsent(plan, p -> new OreStat());
                    if (!WorldHelper.canBreak(mod, pos)) continue;
                    if (WorldHelper.isAdjacentToLiquid(mod, pos)) continue;
                    stat.count++;
                }
            }
        }
        _lastCounts = counts;
        return counts;
    }

    /**
     * (2026-09-01 v2 用户"看见钻石依旧不挖") 贪心可见判定:
     *   - 严格版: isBlockPerceivable (LOS 到方块中心 + isBuriedOre 包裹判定, 主线防透视同款);
     *   - 宽松版: 任一个邻位是空气(暴露面), 且玩家 LOS 能看到该暴露面 — 部分嵌岩的矿
     *     中心 LOS 会被周边挡, 但暴露面肉眼可见 (人类"看到就挖")。暴露面必须真的
     *     LOS 可见, 不会隔墙看到其它 pocket 的矿, 不透视。
     */
    private static boolean isOreVisibleToGreedy(AltoClef mod, BlockPos pos) {
        if (VisibilityHelper.isBlockPerceivable(mod, pos)) return true;
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            BlockPos n = pos.offset(dir);
            net.minecraft.block.BlockState ns = mod.getWorld().getBlockState(n);
            if (ns.isAir() || ns.getBlock() == Blocks.CAVE_AIR) {
                if (VisibilityHelper.isBlockVisible(mod, n)) return true;
            }
        }
        return false;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // 让 BlockTracker 开始缓存矿石位置 (chunk 扫描)。
        for (OrePlan plan : PLANS) {
            mod.getBlockTracker().trackBlock(plan.blocks());
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        for (OrePlan plan : PLANS) {
            mod.getBlockTracker().stopTracking(plan.blocks());
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CollectVisibleOresTask;
    }

    @Override
    protected String toDebugString() {
        return "Collect visible ores";
    }
}
