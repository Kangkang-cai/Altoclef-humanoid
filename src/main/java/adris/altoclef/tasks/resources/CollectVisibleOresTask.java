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
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * (2026-08-22 贪心收集) 顺手挖掉眼前所有可见矿石。
 *
 * 用户设计: 即使当前主线是"获得铁", 看到煤炭也该顺手挖一下, 看到
 * 铁矿该顺手全挖完 — 而不是只锁定目标种类。每个种类带数量上限
 * (煤 32 / 铁 32 / 金 64 / 钻石 8), 达上限就跳过该种类, 防止无限
 * 挖煤把主线饿死。金 64 (2026-09-11 用户拍板): 下界猪灵交易需大量金锭。
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
            new OrePlan(new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE}, Items.RAW_GOLD, 64, MiningRequirement.IRON, new Item[]{Items.RAW_GOLD, Items.GOLD_INGOT}),
            new OrePlan(new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE}, Items.DIAMOND, 8, MiningRequirement.IRON, new Item[]{Items.DIAMOND}),
    };

    // (2026-09-01 贪心触发"看见"放宽, 用户"看到钻石都不触发贪心挖") 旧 3x3x3 贴身
    // 只算"触碰到"的矿, 眼前几格可见的钻石 vis=0 永不触发。改为扫 GREEDY_VISIBLE_RANGE
    // 且必须 isBlockPerceivable(FOV+LOS+isBuriedOre, 防透视) — 眼前可见的矿才触发,
    // 被岩石包住的矿仍不可感知, 不破坏防透视。
    // (2026-09-01 v2 回退, 用户"钻石修改原因错误, 留下排查日志") 曾扩到 16 格+宽松
    // 暴露面判定, 用户判定原因不对。回退到 8 格 + 严格 isBlockPerceivable; 具体为何
    // 钻石 vis=0 用 [GreedyDiag] 日志排查 (范围内不可感知的钻石记位置/距离/失败原因)。
    // (2026-10-16 射线化重写, 用户拍板 A) 8 格立方扫描 (4913 次 getBlockState/次)
    // 两大问题: ① 8~16 格的可见矿永远漏 (20261016-105749 会话实锤: 用户看到钻石
    // 而 perception 全场零记录 = 钻石在 12 格外, 远场 step=4 稀疏 + 8 格贪婪闸都
    // 够不着); ② 成本随 r³ 膨胀, 逐格问"你是不是矿"大部分浪费在实心岩石上。
    // 改为射线扇: 从眼睛发 GREEDY_RAY_COUNT 条斐波那契球面均匀射线 (原生
    // RaycastContext, 与玩家视线同构), 只查首个命中方块; 命中矿直接计入 (构造上
    // 已有视线), 命中非矿时对其 6 邻接的矿石补 isBlockPerceivable 闸 (矿脉露头
    // 常与相邻岩壁共面, 贴着命中点的那块矿其实同样可见; 最终闸门不变, 反透视
    // 口径一寸不让)。成本 ≈ 400 次 raycast/次 (1s 缓存), 半径扩到 16 不涨价。
    // 取舍: ≤8 格近乎确定性必中 (射线间距 ≈1.4 格); 8~16 格单块矿变概率事件,
    // 2~3 块以上矿脉大概率中 — 贪婪本就是机会主义顺手挖, 故意的找矿走主线
    // BlockTracker 路径, 语义不冲突。
    private static final int GREEDY_RAY_COUNT = 400;
    private static final int GREEDY_RAY_RANGE = 16;

    // hasAnyVisibleOreWork / onTick 的静态节流缓存: 两者都可能每 tick 被
    // 调用, 而 countAllVisible 是 400 条射线扫描 (1 秒一次可接受, 每 tick 不行)。
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
     * 只需 count: (2026-10-16 射线化后) 每块矿过 canBreak/贴液体两道闸再计数。
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
    // (2026-09-12 批次原子性) "本批已锁死"诊断日志每批只打一条, 避免 1s 节流下刷屏。
    private boolean _engagedLockLogged = false;

    // (2026-09-26 钻石配额期贪婪化, 用户"挖钻石跟挖铁一样完全贪心, 主线只管'没挖到
    // 25 个钻石就别停下'") _onlyItem 非 null 时本实例只挖该矿种 (选种/刷新/触发判定
    // 全过滤); _capOverride > 0 时将该矿种的上限提升到覆盖值 (钻石 8→配额 25, 否则
    // 攒到 8 颗贪婪就停工, 凑不齐配额)。null/0 = 旧行为 (全部矿种)。
    private final Item _onlyItem;
    private final int _capOverride;

    public CollectVisibleOresTask() {
        this(null, 0);
    }

    public CollectVisibleOresTask(Item onlyItem, int capOverride) {
        _onlyItem = onlyItem;
        _capOverride = capOverride;
    }

    /** 本实例是否允许挖该矿种 (配额期锁矿种过滤)。 */
    private boolean planAllowed(OrePlan plan) {
        return _onlyItem == null || plan.item() == _onlyItem;
    }

    /** 该矿种在本实例下的有效上限 (配额期钻石 8→25, 其余矿种/普通实例不受影响)。 */
    private int effectiveCap(OrePlan plan) {
        if (_onlyItem != null && plan.item() == _onlyItem && _capOverride > plan.cap()) {
            return _capOverride;
        }
        return plan.cap();
    }

    /**
     * (2026-10-16 钻石选种优先, 用户拍板 A) 钻石 rem>0 且 vis≥1 → 返回钻石计划,
     * 金/铁让路。语义 = "看得见钻石就先挖钻石"。
     *
     * <p>实锤 (20261016-162404 会话 16:28:16): 金 vis=2 &gt; 钻石 vis=1 → 旧数量规则
     * 选金, 用户目击"看到钻石却去挖金"。钻石此前在"可见块数最多"比较里没有任何
     * 权重优势, 单块裸露钻石几乎永远输给矿脉。</p>
     *
     * <p>闸门不放松: 配额 (rem≤0)、镐位 (MiningRequirement.IRON)、配额期锁矿种
     * (planAllowed) 任一不满足 → 返回 null, 回落既有"可见块数最多"规则。</p>
     */
    private OrePlan diamondPriorityPlan(AltoClef mod, Map<OrePlan, OreStat> counts) {
        for (OrePlan plan : PLANS) {
            if (plan.item() != Items.DIAMOND) continue;
            if (!planAllowed(plan)) return null;
            if (planExecutable(mod, plan, effectiveCap(plan)) <= 0) return null;
            OreStat st = counts.get(plan);
            return (st != null && st.count > 0) ? plan : null;
        }
        return null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_active != null && _activePlan != null) {
            // (2026-08-23) 达标判定按"生矿+锭"动态计数 (含光标) — 原只数
            // 生矿: 烧好后生铁跌破 32 会重新触发挖矿 (用户: 烧制产物也
            // 算进 32)。中途被 gear 任务拿去烧一部分同样成立。
            // (2026-09-22 宽限保护, 183317 场实锤 50ms 拆批重建循环) isFinished
            // 会因掉落物 tracker 1~2 tick 回册抖动瞬时翻真; 挖掉块后的掉落宽限
            // 挂住期 (holdingForGreedyDrop) 不得释放批次, 否则宽限语义被父层
            // 整个绕掉 → 每 tick 拆批重建, 挖矿退化成碎片式捡漏。
            boolean capHit = countedItems(mod, _activePlan) >= effectiveCap(_activePlan);
            boolean fin = _active.isFinished(mod);
            boolean holding = fin && _active.holdingForGreedyDrop(mod);
            if (capHit || (fin && !holding)) {
                // (2026-09-13 批次收尾诊断) 用户"矿脉没挖完就回主线"现场取证:
                // 分清是①达上限正常收工, 还是②近场判无活提前释放 (后者看 vis 与
                // 近场豁免口径能否覆盖矿脉延伸)。每批只打一条, 天然节流。
                // (2026-09-22) 近场豁免收编为面相邻6块 (原 GREEDY_NEAR_FIELD_RADIUS
                // 删除), 诊断字段改为固定口径标签。
                int have = countedItems(mod, _activePlan);
                OreStat endSt = countAllVisible(mod).get(_activePlan);
                // (2026-09-22) release 原因分解: capHit=达上限收工; holding=true 不可能
                // 出现在此(宽限期已保护); 其余为 isFinished 瞬时真 — 看 have/vis 区分
                // 正常收尾与抖动拆批。(2026-10-16) 标签 vis8→vis: 扫描已射线化, 半径 16。
                Debug.logToFile("altoclef_diag.log", "[GreedyBatch] release item="
                        + _activePlan.item() + " capHit=" + capHit
                        + " have=" + have + "/" + _activePlan.cap()
                        + " vis=" + (endSt == null ? 0 : endSt.count)
                        + " nearField=face-adjacent-6"
                        + " player=" + mod.getPlayer().getBlockPos());
                _active = null;
                _activePlan = null;
                _engagedLockLogged = false;
            } else {
                // (2026-08-22 动态刷新) 导航/挖矿途中每 3 秒重新扫描一次:
                // 若视野内出现"明显更丰富"的其他矿种, 立即切换目标 —
                // 让模型路过更近的物资 (用户设计: 被导航导向某矿的途中,
                // 发现可贪心矿应立马转向)。切换条件: 新矿种可见块数严格
                // 多于当前目标 (防止轻微波动导致横跳; 3 秒节流兜底)。
                long nowMs = System.currentTimeMillis();
                if (nowMs - _lastRefreshMs >= REFRESH_INTERVAL_MS) {
                    _lastRefreshMs = nowMs;
                    // (2026-09-12 批次原子性, 用户"挖完一处的矿然后全部拾取完才换到下一处")
                    // 本批次一旦已经"接触"矿石 (破坏过目标方块, 或感知范围内已有目标掉落物
                    // 待拾取), 就锁死到本批结束 — 动态刷新退化为"仅纯导航阶段允许转向更富
                    // 矿脉", 保留 2026-08-22 "被导航导向某矿途中发现可贪心矿应立马转向" 的
                    // 设计意图, 同时杜绝挖到一半/正在拾取时被切走。
                    // 实锤 (21:51:05 会话 20260912-212737): 铁矿批次挖空 163/-183 后正走去
                    // 捡掉落物 (掉落物 (175,20,-172) 距 bot 仅 3.68 格), 刷新见隔壁 1 块金矿
                    // (vis 1 vs 0) 判"更丰富"整批切走 → 铁掉落物留原地、金矿 1.9s 后放弃
                    // 探索, 净损 ~4s 两处皆空。切换判据里的 curV=0 本就是"刚被自己挖空"的
                    // 表现, 不等于"这处矿不值得挖"。
                    if (_active.hasEngagedOre(mod)) {
                        if (!_engagedLockLogged) {
                            _engagedLockLogged = true;
                            Debug.logToFile("altoclef_diag.log",
                                    "[GreedyOre] batch engaged — switching locked until this batch finishes");
                        }
                        return _active;
                    }
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
                    // (2026-10-16 钻石选种优先, 用户拍板 A) 刷新路径同样生效: 钻石可见
                    // → 无视"更丰富"比较直接切钻石 (仍在批间/未接触阶段, engaged 锁上
                    // 面已拦, 不违反批次原子性)。
                    OrePlan diamondFirst = diamondPriorityPlan(mod, counts);
                    if (diamondFirst != null && diamondFirst != _activePlan) {
                        int dV = counts.get(diamondFirst) == null ? 0 : counts.get(diamondFirst).count;
                        Debug.logMessage("[GreedyOre] switching " + _activePlan.item()
                                + " -> " + diamondFirst.item() + " (diamond priority: " + dV + " visible)");
                        _active = new MineAndCollectTask(new ItemTarget(diamondFirst.item(), 1), diamondFirst.blocks(), diamondFirst.req(), true);
                        _activePlan = diamondFirst;
                        setDebugState("Greedy mining " + diamondFirst.item().toString());
                        return _active;
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
        // (2026-10-16 钻石选种优先, 用户拍板 A) 钻石 rem>0 且 vis≥1 → 直接选钻石,
        // 无视"可见块数最多"。实锤 (20261016-162404 会话 16:28:16): 金 vis=2 >
        // 钻石 vis=1 → 批次选金, 用户目击"看到钻石却去挖金"。配额/镐位闸门
        // (planExecutable) 照常生效, 钻石不可见或不可执行时回落数量规则。
        best = diamondPriorityPlan(mod, counts);
        if (best != null) {
            bestCount = counts.get(best) == null ? 0 : counts.get(best).count;
            setDebugState("Greedy mining " + best.item().toString() + " (diamond priority)");
        }
        if (best == null) for (OrePlan plan : PLANS) {
            if (!planAllowed(plan)) continue;
            if (planExecutable(mod, plan, effectiveCap(plan)) <= 0) continue;
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
        _engagedLockLogged = false; // 新批次开始, 锁死日志重新计数
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
        // 的检查节拍(普通阶段 3s = GREEDY_ORE_CHECK_COOLDOWN_NORMAL / 进下界前阶段
        // preNetherGreedyCooldownSeconds)控制下次再触发, 主线获得长窗口推进。
        // (2026-09-12 更正) 此处原文写"冷却已加大到 8s"是 09-01 的旧值, 09-11 已按
        // 用户"看到金子总是没有第一时间触发"改回 3s, 且检查失败的分支也会 reset —
        // 节拍的实际语义是"两次检查间隔 >= 3s", 与冷却起点无关。
        if (_active == null) {
            // 还没选目标(或当前无可见矿): 有可见活就继续等 onTick 建目标, 否则完成。
            return !hasAnyVisibleOreWork(mod);
        }
        return _active.isFinished(mod);
    }

    /**
     * (2026-09-22 宽限对外查询, 183317 场实锤) 当前批次是否处于"挖掉目标块后
     * 等掉落物落地回册"的宽限挂住期。供 BeatMinecraft2Task 的顶层释放检查使用:
     * isFinished 因掉落物 tracker 1~2 tick 回册抖动瞬时翻真时, 顶层不应拆掉
     * 整个贪婪任务 (拆掉=批次状态丢失+3s 冷却重建, 50ms 拆批重建循环的顶层成因)。
     */
    public boolean holdingForGreedyDrop(AltoClef mod) {
        return _active != null && _active.holdingForGreedyDrop(mod);
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
     * (2026-09-26 钻石配额期贪婪化) 带矿种过滤 + 上限覆盖的"有活可干"判定。
     *
     * <p>onlyItem=null 时等价于 {@link #hasAnyVisibleOreWork} 的纯判定 (无诊断日志,
     * 不写 _lastHasWork); onlyItem 非 null 时只判该矿种, 且其上限取
     * max(plan.cap, capOverride) —— 配额期钻石 8→25, 否则攒到 8 颗贪婪就停工。
     *
     * <p>不写 _lastHasWork/_lastCounts 之外的共享状态, 与 hasAnyVisibleOreWork
     * 共用 countAllVisible 的 1s 扫描缓存, 无额外世界扫描开销。
     */
    public static boolean hasVisibleOreWork(AltoClef mod, Item onlyItem, int capOverride) {
        Map<OrePlan, OreStat> counts = countAllVisible(mod);
        for (OrePlan plan : PLANS) {
            if (onlyItem != null && plan.item() != onlyItem) continue;
            int cap = (onlyItem != null && capOverride > plan.cap()) ? capOverride : plan.cap();
            int remain = planExecutable(mod, plan, cap);
            OreStat st = counts.get(plan);
            int visible = st == null ? 0 : st.count;
            if (remain > 0 && visible > 0) return true;
        }
        return false;
    }

    /**
     * (2026-09-26 钻石配额期) 指定矿种的方块数组 (供 BeatMinecraft2Task 配额期
     * 查询"主线是否正在挖钻石"做非钻石贪婪让位判定)。未知矿种返回空数组。
     */
    public static Block[] oreBlocksFor(Item item) {
        for (OrePlan plan : PLANS) {
            if (plan.item() == item) return plan.blocks();
        }
        return new Block[0];
    }

    /**
     * (2026-09-12 跨批次对撞闸门) 当前【贪心会选的矿种】的方块集 — 可见块数最多且
     * 可执行 (镐够 / 未达上限)。无可见活时返回空数组。
     *
     * 供 BeatMinecraft2Task 与"主线正在推进的挖矿"做同矿种对撞判定: 撞上则贪心让位 —
     * 主线已锁定这处矿并在挖/拾, 贪心插进来只会把主线的目标选择拆掉重新找矿。
     * 复用 countAllVisible 的 1s 缓存, 不额外扫世界; 与 onTick 里选 best 的口径一致。
     */
    public static Block[] bestVisibleOreBlocks(AltoClef mod) {
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
        return best == null ? new Block[0] : best.blocks();
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
        return planExecutable(mod, plan, plan.cap());
    }

    /** 同上, 但上限用调用方给的覆盖值 (2026-09-26 配额期钻石 8→25)。 */
    private static int planExecutable(AltoClef mod, OrePlan plan, int cap) {
        // (2026-08-23 金矿让位) 铁装备(铁剑+盾牌)未完成时金矿不参与贪心 —
        // 铁装备制作/穿上优先于挖金 (用户原则), 否则贪心挖金抢占 gear
        // 任务, 铁装备迟迟不齐。铁阶段完成后金矿恢复参与 (阈值 64, 2026-09-11 用户改)。
        if (plan.item() == Items.RAW_GOLD && !BeatMinecraft2Task.isIronGearPhaseDone(mod)) return 0;
        if (!StorageHelper.miningRequirementMet(mod, plan.req())) return 0;
        return Math.max(0, cap - countedItems(mod, plan));
    }

    /**
     * (2026-08-22 修复) 统计玩家"可挖可见"的矿石数量 (按种类)。
     *
     * 原实现依赖 BlockTracker 缓存 (getKnownLocations + isBlockPerceivable)。
     * 但贪心检查改成纯静态函数后不再 trackBlock, 煤/铜/金等从未被
     * BlockTracker track → 缓存永远为空 → 顺手挖矿永不触发。改为直接
     * 扫描世界, 不依赖任何跟踪状态。调用方用 3 秒冷却兜底性能。
     *
     * (2026-10-16 射线化重写, 用户"主动发射射线, 只查被射中的第一个方块")
     * 旧实现扫玩家周围 (2*8+1)³ = 4913 格立方逐格 getBlockState, 成本随 r³
     * 膨胀, 且 8~16 格的可见矿永远漏 (20261016-105749 会话实锤: 用户看到钻石
     * 而录制 perception 全场零钻石记录 = 钻石在近场穷举半径外)。现从眼睛发
     * {@link #GREEDY_RAY_COUNT} 条斐波那契球面均匀射线 (原生 RaycastContext,
     * ShapeType.COLLIDER + FluidHandling.NONE — 流体无碰撞形状, 射线穿透,
     * 与既有"矿贴液体不计"的 {@link WorldHelper#isAdjacentToLiquid} 闸配合),
     * 只处理首个命中方块:
     * <ul>
     *   <li>命中矿 → 直接计入 (射线到达其表面 = 构造上已有视线, 天然防透视);</li>
     *   <li>命中非矿 → 对命中方块 6 邻接的矿石补 {@link VisibilityHelper#isBlockPerceivable}
     *       闸后计入 (矿脉露头常与相邻岩壁共面, 贴着命中点的那块矿同样可见;
     *       最终闸门与旧口径同源, 反透视不放松)。</li>
     * </ul>
     * 同一块矿被多条射线/邻域重复命中时按 {@code counted} 去重, 每块只计一次
     * (与旧立方扫描计数口径一致)。
     */
    public static Map<OrePlan, OreStat> countAllVisible(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastCheckMs < CHECK_INTERVAL_MS && _lastCounts != null) {
            return _lastCounts;
        }
        _lastCheckMs = now;
        Map<OrePlan, OreStat> counts = new IdentityHashMap<>();
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null || mod.getWorld() == null) {
            _lastCounts = counts;
            return counts;
        }
        Vec3d eye = player.getCameraPosVec(1.0f);
        Set<BlockPos> counted = new HashSet<>();
        for (Vec3d dir : rayDirections()) {
            Vec3d end = eye.add(dir.x * GREEDY_RAY_RANGE, dir.y * GREEDY_RAY_RANGE, dir.z * GREEDY_RAY_RANGE);
            BlockHitResult hit = mod.getWorld().raycast(new RaycastContext(eye, end,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            BlockPos hitPos = hit.getBlockPos();
            // 首个命中方块: 是矿直接计 (射线已到达其表面, 构造上已有视线)。
            tryCountOreAt(mod, hitPos, counts, counted);
            // (2026-10-16 命中面邻域补查) 射线打在矿脉旁边的岩壁上时, 贴着命中点
            // 的那块露头矿同样可见 — 6 邻接做候选, isBlockPerceivable 最终闸兜底;
            // 不可感知的候选走 [GreedyDiag] 排查日志。
            for (Direction d : Direction.values()) {
                BlockPos n = hitPos.offset(d);
                if (counted.contains(n)) continue;
                if (!BLOCK_TO_PLAN.containsKey(mod.getWorld().getBlockState(n).getBlock())) continue;
                if (VisibilityHelper.isBlockPerceivable(mod, n)) {
                    tryCountOreAt(mod, n, counts, counted);
                } else {
                    logUnperceivableOre(mod, n, player.getBlockPos());
                }
            }
        }
        _lastCounts = counts;
        return counts;
    }

    /**
     * 单个候选矿位的计数入口: 去重 → 确认已知矿种 → canBreak → 不贴液体
     * (与旧立方扫描完全同源的接受条件, 判定源单一)。
     */
    private static void tryCountOreAt(AltoClef mod, BlockPos pos, Map<OrePlan, OreStat> counts, Set<BlockPos> counted) {
        if (!counted.add(pos)) return;
        OrePlan plan = BLOCK_TO_PLAN.get(mod.getWorld().getBlockState(pos).getBlock());
        if (plan == null) return;
        if (!WorldHelper.canBreak(mod, pos)) return;
        if (WorldHelper.isAdjacentToLiquid(mod, pos)) return;
        counts.computeIfAbsent(plan, p -> new OreStat()).count++;
    }

    /**
     * 斐波那契球面均匀方向 (惰性初始化, 全向覆盖 — 与旧口径"无 FOV 限制,
     * 只认视线可达"一致)。相邻射线角间距 ≈ sqrt(4π/N): N=400 时 16 格处
     * 采样间距 ≈2.8 格, 8 格处 ≈1.4 格。
     */
    private static Vec3d[] rayDirections() {
        if (_rayDirections == null) {
            Vec3d[] dirs = new Vec3d[GREEDY_RAY_COUNT];
            double golden = Math.PI * (3.0 - Math.sqrt(5.0));
            for (int i = 0; i < GREEDY_RAY_COUNT; i++) {
                double y = 1.0 - (2.0 * (i + 0.5)) / GREEDY_RAY_COUNT;
                double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
                double theta = golden * i;
                dirs[i] = new Vec3d(Math.cos(theta) * r, y, Math.sin(theta) * r);
            }
            _rayDirections = dirs;
        }
        return _rayDirections;
    }

    private static Vec3d[] _rayDirections = null;

    // [GreedyDiag] 范围内不可感知的目标矿: 只对【钻石】(用户最关心的)记录, 节流
    // 到最多每 5 秒一条, 避免刷屏。记录距离与 VisibilityHelper.perceivableFailReason
    // (occluded=视线被挡 / 其它), 直接定位"看到却不触发"的原因。
    // (2026-10-16 盲区修复) 原实现开头 `if (pos.getY() < 0) return;` — bot 常驻
    // 负 Y 深板岩层, 深板岩钻石不可感知时从不留日志, 本次"看到却不触发"排障
    // 被迫动用录制数据自证。删除该行 (5s 节流仍在, 不会刷爆)。
    private static long _lastGreedyDiagMs = 0;
    private static final long GREEDY_DIAG_INTERVAL_MS = 5000;

    private static void logUnperceivableOre(AltoClef mod, BlockPos pos, BlockPos player) {
        net.minecraft.block.Block b = mod.getWorld().getBlockState(pos).getBlock();
        if (b != Blocks.DIAMOND_ORE && b != Blocks.DEEPSLATE_DIAMOND_ORE) return;
        long now = System.currentTimeMillis();
        if (now - _lastGreedyDiagMs < GREEDY_DIAG_INTERVAL_MS) return;
        _lastGreedyDiagMs = now;
        double dist = Math.sqrt(player.getSquaredDistance(pos));
        String reason = VisibilityHelper.perceivableFailReason(mod, pos);
        Debug.logToFile("altoclef_diag.log",
                String.format("[GreedyDiag] diamond at %s dist=%.1f NOT perceivable: %s",
                        pos.toShortString(), dist, reason));
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
        // (2026-09-26) 区分配额期钻石专属实例与普通实例 — 钻石专属批次与普通批次
        // 互换时视为不同任务, 让任务框架正常走打断/重建而非误判相等。
        return other instanceof CollectVisibleOresTask
                && ((CollectVisibleOresTask) other)._onlyItem == _onlyItem;
    }

    @Override
    protected String toDebugString() {
        // (2026-09-26) 配额期钻石专属批次带标题标识, 用户在画面上可直接认出模式。
        return _onlyItem == null ? "Collect visible ores"
                : "Collect visible ores [quota:" + _onlyItem.toString() + "]";
    }
}
