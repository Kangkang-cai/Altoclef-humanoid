package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.FortressExploreTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.misc.DrinkFireResPotionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.KnowledgeMemory;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.ScaffoldBlocks;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.ThreatEvaluator;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.WorldIdentity;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class CollectBlazeRodsTask extends ResourceTask {

    private static final double SPAWNER_BLAZE_RADIUS = 32;

    private static final int TOO_MANY_BLAZES = 5;
    private static final double TOO_LITTLE_HEALTH_BLAZE = 5;

    // =========================================================================
    // (2026-09-18 烈焰刷怪笼记忆, 用户"刷怪笼是像堡垒那样的关键位置, 记住后
    // 直接去那边刷, 不用再找") 原实现只把刷怪笼存【实例字段】(第 54 行附近),
    // 任务实例一重建就清零, 于是每次都得靠走廊巡逻重新"亲眼看见"才能确立目标。
    // 两层记忆补上:
    //   ① 会话内: 按 存档:维度 隔离的 static map —— 覆盖"任务实例重建"这一类
    //      最高频丢失 (_liveInstance 缓存只在 isFinished / 停泊超 8s / 重开游戏
    //      时才重建, 但下界战斗频繁让位, 8s 宽限很容易被击穿)。
    //   ② 跨会话: KnowledgeMemory.StructureType.BLAZE_SPAWNER (亲眼看见过就
    //      记、落盘 knowledge.log), 下次直接从记忆点导航过去。
    // ⚠ 安全底线: 记忆点只是【候选】, 必须走到现场用 getSpawnerEntity 验明是
    //   烈焰实体才确立。蜘蛛笼/僵尸笼方块完全相同, 按坐标远程认亲会骗到主世界去。
    // =========================================================================
    // 记忆点召回的最大距离 (下界格)。1024 = 与 FortressExploreTask 的
    // REMEMBERED_CENTER_MAX_DISTANCE 同口径: 人类"记得那边有座堡垒"就该直接去。
    private static final double SPAWNER_RECALL_RANGE = 1024;
    // 走到候选点多近才够验明 (需 chunk 已加载才能读实体)。
    private static final double SPAWNER_RECALL_ARRIVE = 12;
    // 前往候选点的超时 (秒)。超时 = 够不着, 登记排除, 换下一个候选。
    private static final double SPAWNER_RECALL_TIMEOUT = 90;
    // 排除表的容量/判定半径 (与 FortressExploreTask 记忆查讫同款思路)。
    private static final double REJECT_RADIUS = 8;
    private static final int MAX_REJECTED_SPAWNERS = 64;

    private static final Map<String, BlockPos> _confirmedSpawnerByKey = new HashMap<>();
    private static final Map<String, Set<BlockPos>> _rejectedSpawnerByKey = new HashMap<>();

    // (2026-09-06 药水门槛, 用户) 喝防火药水的最低烈焰人数: 落单烈焰人走盾战, 不喝药。
    private static final int POTION_MIN_BLAZES = 2;
    // 血量低 (半血) 时即便落单也允许喝 — 盾战容错太差, 保命优先。
    private static final float POTION_LOW_HEALTH = 10.0f;

    // =========================================================================
    // (2026-09-19 刷怪笼包围策略, 用户; 17:20 终版规格) 结构 = 三件套:
    //   地板: 刷怪笼低一格 (y-1) 5x5 补实
    //   围墙: y+1 一圈 7x7 外圈 (单圈)
    //   顶盖: y+3 5x5 补实 ("封顶")
    //   内圈 5x5 在 y..y+2 敞开 (3 格高), bot 与烈焰人同场。
    // 搭建顺序自上向下: 顶盖 → 围墙 → 地板 (用户)。
    // =========================================================================
    private static final int ENCLOSURE_NETHERRACK_PREP = 128;
    private static final int ENCLOSURE_RESTOCK_TRIGGER = 32;
    private static final int ENCLOSURE_RESTOCK_TOPUP = 32;
    // 脚底地板 5x5 (低一格) / 顶盖 5x5 (高 3 格) / 围墙 7x7 外圈半径。
    private static final int SPAWNER_FLOOR_HALF = 2;
    private static final int SPAWNER_ROOF_HEIGHT = 3;
    private static final int SPAWNER_WALL_HEIGHT = 1;
    private static final int SPAWNER_WALL_HALF = 3;
    // 破洞重扫间隔 / 回场检查间隔 (秒, 用户 13:05)。
    private static final int HOLE_CHECK_INTERVAL_S = 15;
    private static final int RETURN_CHECK_INTERVAL_S = 10;
    // 围场内烈焰人才打 (刷怪笼中心 4.5 格 ≈ 7x7 围场对角半径)。
    private static final double ENCLOSURE_KILL_RANGE = 4.5;
    /**
     * (2026-09-19 20:20 用户"贴脸的烈焰人还是要打, 打远了就不管了继续造墙")
     * 施工中(spawnerWorkPending)不开农场杀, 但贴脸烈焰人必须紧急处理 ——
     * MobDefense 施工让位后没人管, bot 会被贴脸烧着还继续放方块。
     *
     * (2026-09-19 22:44 用户"不行, 还是不要收紧") 22:31 那次 3 → 2 格已回退,
     * 维持 **3 格** (火球这距离躲不开)。同一个会话里另有真问题 ——
     * "明明有烈焰人在区域内任务还是等待生成", 见 {@link #blazesInEnclosure}
     * (自己砌的墙挡住自己的视线 → 感知不到 → 不打 → 等)。所以不靠收紧阈值解决。
     */
    private static final double BLAZE_MELEE_EMERGENCY_RANGE = 3.0;
    /** (2026-09-19 22:47) 感知盲区诊断日志节流 (30s 一条, 供实跑核对根因)。 */
    private long _lastPerceptDiagMs = 0;

    private final int _count;
    // (2026-09-04 人类式改造) 默认走 FortressExploreTask: 循声/循可见下界砖
    // 偏置游走 + 踏上要塞后只踩走廊地面, 不再 chunk 透视。
    private final Task _fortressSearcher = new FortressExploreTask();
    // 超时兜底: 人类式探索太久没找到时, 降级回旧的 chunk 扫描 (Settings 开关)。
    private final Task _searcher = new SearchChunkForBlockTask(Blocks.NETHER_BRICKS);
    private final TimerGame _searchTimer = new TimerGame(Integer.MAX_VALUE);
    private boolean _fallbackEngaged = false;

    // Why was this here???
    //private Entity _toKill;
    private BlockPos _foundBlazeSpawner = null;

    // (2026-09-18 刷怪笼记忆召回) 当前 存档:维度 key / 正在前往的候选点 / 前往计时。
    private String _worldKey = null;
    private BlockPos _recallTarget = null;
    private final TimerGame _recallTimer = new TimerGame(SPAWNER_RECALL_TIMEOUT);

    // (2026-09-19 16:22) 自上向下搭建时顶圈可能暂无贴面放不了: 同一目标卡
    // 超 10s 就轮到队尾 (优先级让位, 不死等)。
    private static final long BUILD_TARGET_STALL_MS = 10_000;
    private BlockPos _currentBuildTarget = null;
    private long _currentBuildStartMs = 0;

    // (2026-09-19 13:05) 破洞检查缓存 (每 15s 全扫一次, 期间只对缓存清单做
    // 廉价的 isSolid 刷新) 与回场检查计时 (每 10s 一次)。
    private final TimerGame _holeCheckTimer = new TimerGame(HOLE_CHECK_INTERVAL_S);
    private List<BlockPos> _cachedMissingEnclosure = null;
    /** (2026-09-19 19:30) 上次扫描里"非满立方但已封堵"的格数, 变化才打日志。 */
    private static int _lastLooseBlocked = -1;
    private final TimerGame _returnCheckTimer = new TimerGame(RETURN_CHECK_INTERVAL_S);

    // =========================================================================
    // (2026-09-19 21:50 用户"bot 搭完路反复触发清理, 又触发补墙; 补墙和清理这些
    // 都应该有时间间隔触发, 而不是时刻检查") 围场维护【统一节拍】。
    //
    // 事故现场 20260919-214013 (60s 内清障与灭火互抢 17 轮, ≈1.7s/轮):
    //   ① 清障 (原每 tick 全扫 findInteriorObstructor) 与灭火 (原每 tick 查
    //      5 格内 FIRE) 都在【每 tick 路径】且零节流, 而补墙 (15s 缓存) 也用
    //      同一 tick 抢任务点 → 三者秒级横跳。anomaly.log 已自行报出周期 3 的
    //      任务环: null ↔ PutOutFire ↔ InteractWithBlockTask。
    //   ② 灭火还自锁: 单次灭火要先走位 1s+, 而清障/补墙每 0.5~1.5s 抢一次 →
    //      灭火永远跑不完 → 火永远在 (目标恒为 775,79,121) → 下一 tick 又触发。
    //
    // 修法: 决策与执行分离 —— 每 ENCLOSURE_BEAT_SECONDS 秒才重新决策一次
    // "这一拍做哪件事", 节拍内只认锁定的动作 (补墙 > 清障 > 灭火), 不重估其他
    // 分支; 同类工作可在节拍内连续做 (清障可连挖多格)。
    // (2026-09-19 22:09 用户"8s 吧, 3s 太频繁") 定 8s: 单次灭火要走位 1s+ 再点,
    // 一拍 8s 足够把动作跑完整; 清障一拍内可连挖 8~16 格, 不浪费。
    // 与既有节奏的关系: BUILD_TARGET_STALL_MS=10s 略大于一拍 → stall 轮转最早
    // 落在第二拍 (不会一拍就轮转); 灭火 FIRE_GIVEUP_ATTEMPTS=2 拍 = 16s 才冷却。
    // =========================================================================
    private static final int ENCLOSURE_BEAT_SECONDS = 8;
    private final TimerGame _beatTimer = new TimerGame(ENCLOSURE_BEAT_SECONDS);

    // (2026-09-19 22:25 用户"你这也得考虑建墙完成百分之多少才开始节流呀, 不然建
    // 一会就换任务了") 建墙优先期: 缺块还多时本拍只做补墙, 不轮入清障/灭火 ——
    // 否则"建几块就被换去清障/灭火", 墙永远立不起来。完成度 ≥ 90% 才恢复轮转。
    /** 围场结构总格数 = 顶盖 5x5 + 围墙 7x7 外圈 + 地板 5x5 = 25 + 24 + 25 = 74。 */
    private static final int ENCLOSURE_TOTAL_BLOCKS =
            (2 * SPAWNER_FLOOR_HALF + 1) * (2 * SPAWNER_FLOOR_HALF + 1) * 2
                    + (2 * SPAWNER_WALL_HALF + 1) * (2 * SPAWNER_WALL_HALF + 1)
                    - (2 * SPAWNER_FLOOR_HALF + 1) * (2 * SPAWNER_FLOOR_HALF + 1);
    private static final double ENCLOSURE_BUILD_FOCUS_COMPLETION = 0.90;

    private enum EnclosureAction { NONE, BUILD, CLEAR, FIRE }

    private EnclosureAction _beatAction = EnclosureAction.NONE;
    /** 本节拍跳过补墙: 同一目标 stall 轮转后不再死等, 让清障/灭火有机会跑。 */
    private boolean _skipBuildThisBeat = false;
    /** 上一拍锁定的灭火坐标 (供节拍结算"火还在不在")。 */
    private BlockPos _beatFireTarget = null;

    // (2026-09-19 21:50 用户"没注意, 不确定"火源 → 保守处理) 同一火位连续
    // FIRE_GIVEUP_ATTEMPTS 拍仍未扑灭 → 冷却 FIRE_GIVEUP_COOLDOWN_MS, 期间不再
    // 选它, 免得在一个灭不掉的火上原地打转 (烈焰人火球会持续重新点燃)。
    private static final int FIRE_GIVEUP_ATTEMPTS = 2;
    private static final long FIRE_GIVEUP_COOLDOWN_MS = 30_000;
    private final Map<BlockPos, Integer> _fireAttempts = new HashMap<>();
    private final Map<BlockPos, Long> _fireGiveUpUntilMs = new HashMap<>();

    public CollectBlazeRodsTask(int count) {
        super(Items.BLAZE_ROD, count);
        _count = count;
    }

    private static boolean isHoveringAboveLavaOrTooHigh(AltoClef mod, Entity entity) {
        int MAX_HEIGHT = 23;
        for (BlockPos check = entity.getBlockPos(); entity.getBlockPos().getY() - check.getY() < MAX_HEIGHT; check = check.down()) {
            if (mod.getWorld().getBlockState(check).getBlock() == Blocks.LAVA) return true;
            if (WorldHelper.isSolid(mod, check)) return false;
        }
        return true;
    }

    /**
     * (2026-09-19 22:44 用户"怎么有的时候, 明明有烈焰人在区域内任务还是等待生成")
     * 围场盒子内的烈焰人 —— 绕过【人类式感知】直接查世界实体。
     *
     * 为什么必须绕: {@code entityTracker} 只收录"看得见/听得见, 或最近记忆窗口内
     * 感知过"的实体 (EntityTracker.updateState → VisibilityHelper.isEntityVisible),
     * 而 bot 站到围墙上/顶盖上补块时, **它自己刚砌的那圈墙正好挡住自己的视线** →
     * 感知不到 → 击杀分支根本不进 → 一路走到最后
     * "Waiting inside our enclosure for blazes to spawn"。
     * 223931 实锤: 两次 Waiting (22:41:41.317 / 22:41:50.668) 都紧跟在
     * Place structure 之后 1.5~2s, bot 一动视线恢复就立刻转
     * "Killing CLOSE-RANGE blaze" —— 不是没烈焰人, 是看不见。
     *
     * 这不是透视: 范围严格限制在自己造的围场盒子内 (7x7 × y-1..y+3), 语义等于
     * "我记得我把它关在里面了"—— 封闭围场里的实体跑不掉, 且每次只查这一小块。
     */
    private List<BlazeEntity> blazesInEnclosure(AltoClef mod) {
        BlockPos s = _foundBlazeSpawner;
        if (s == null || mod.getWorld() == null) return Collections.emptyList();
        Box box = new Box(
                s.getX() - SPAWNER_WALL_HALF, s.getY() - 1, s.getZ() - SPAWNER_WALL_HALF,
                s.getX() + SPAWNER_WALL_HALF + 1, s.getY() + SPAWNER_ROOF_HEIGHT + 1, s.getZ() + SPAWNER_WALL_HALF + 1);
        return mod.getWorld().getEntitiesByClass(BlazeEntity.class, box, Entity::isAlive);
    }

    /**
     * 候选里距 ref 最近、且在 range 内的烈焰人; 无则 empty。
     * {@code requireGrounded=true} 时额外排除"悬在岩浆上方/下方 23 格无实体地面"的
     * (贴脸紧急杀的老口径: 打不到还追出去会摔)。
     */
    private Optional<Entity> closestBlazeWithin(AltoClef mod, List<BlazeEntity> candidates,
                                                Vec3d ref, double range, boolean requireGrounded) {
        BlazeEntity best = null;
        double bestSq = range * range;
        for (BlazeEntity b : candidates) {
            if (b == null || !b.isAlive()) continue;
            double sq = b.squaredDistanceTo(ref);
            if (sq > bestSq) continue;
            if (requireGrounded && isHoveringAboveLavaOrTooHigh(mod, b)) continue;
            bestSq = sq;
            best = b;
        }
        return Optional.ofNullable(best);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.SPAWNER);
        // (2026-09-04) 人类式找要塞: 计时器从进下界找要塞开始算, 触发超时兜底。
        _searchTimer.reset();
        _fallbackEngaged = false;
        // (2026-09-18 刷怪笼记忆) 实例即便被重建也要把上一段确认过的刷怪笼接回来,
        // key 与 FortressExploreTask / KnowledgeMemory 同口径 (WorldIdentity)。
        _worldKey = WorldIdentity.key(mod);
        _recallTarget = null;
        _recallTimer.reset();
        // (2026-09-19 13:05) 实例重建 → 围场缓存清零, 到场后立即重扫一次。
        _cachedMissingEnclosure = null;
        // (2026-09-19 18:55) 完工标志同步作废, 到场重扫后再确立 (断药坚守判定依据)。
        _enclosureCompleteFlag = false;
        _holeCheckTimer.reset();
        _returnCheckTimer.reset();
        // (2026-09-19 21:50) 节拍照同步清零 —— 实例/分支二次进入时不许沿用上一拍的
        // 锁定动作 (否则会带着旧的 BUILD/CLEAR/FIRE 直接返回旧目标)。
        _beatTimer.reset();
        _beatAction = EnclosureAction.NONE;
        _skipBuildThisBeat = false;
        _beatFireTarget = null;
        _currentBuildTarget = null;
        BlockPos remembered = _confirmedSpawnerByKey.get(_worldKey);
        if (remembered != null) {
            // (2026-09-19 18:05 用户) 沿用前先验岩浆 — 别沿用一个岩浆笼再走
            // "喝药→备料→盖墙→换笼"的冤枉路。
            if (isSpawnerLavaTroubled(mod, remembered)) {
                Debug.logMessage("[BLAZE-SPAWNER] 已确认笼 " + remembered.toShortString()
                        + " 打怪区域有岩浆, 选笼阶段直接跳过 (不喝药不备料)");
                rejectSpawner(remembered, "打怪区域有岩浆, 选笼时直接跳过");
                _confirmedSpawnerByKey.remove(_worldKey, remembered);
            } else {
                _foundBlazeSpawner = remembered;
                Debug.logMessage("[BLAZE-SPAWNER] 沿用已确认刷怪笼 " + remembered.toShortString()
                        + " (实例重建, key=" + _worldKey + ")");
                registerEnclosureProtection(mod);
            }
        } else {
            // Session-static 里也没有 → 交给每层 tick 走"亲眼看见 / 记忆召回"两条路。
            _foundBlazeSpawner = null;
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }

        // (2026-09-19 包围策略④) 围场未完工 (没喝药 / 缺块未补) 时不开杀 —
        // 半路去追打飘在空中的烈焰人正是 0919 摔死事故的根源, 先把围场立起来。
        boolean spawnerWorkPending = isSpawnerWorkPending(mod);

        // (2026-09-19 18:55 用户三条特殊规则) 无防火效果且背包无药水时:
        //  ① 墙未完工 → 停止造墙, 转去获得药水 (不裸奔硬撑);
        //  ② 墙已完工且 8 分钟收药冷却未到 → 留在墙里继续打 (不逃);
        //  ③ 冷却到点 → 出墙去收药。
        // 判定放在最顶上 (战斗/逃跑逻辑之前), 统一接管两个场景。
        if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                && ItemHelper.countFireResPotions(mod) == 0
                && shouldAbandonForPotions()) {
            _enclosureBuildActive = false;
            setDebugState("No fire res effect & no potions -> getting potions");
            return new CollectFireResPotionsTask(BeatMinecraft2Task.FIRE_RES_POTION_TARGET);
        }

        Optional<Entity> toKill = Optional.empty();
        // (2026-09-19 20:20) true = 本 tick 选的是【贴脸紧急杀】口径 (距 bot ≤3),
        // 击杀谓词要跟着用 bot 距离而不是笼心距离, 否则 KillEntitiesTask 追丢。
        boolean emergencyBlaze = false;
        // (2026-09-19 22:44 用户"明明有烈焰人在区域内任务还是等待生成") 候选 =
        // 【感知到的】∪【围场盒子里的】—— 后者见 blazesInEnclosure 注释 (自己砌的
        // 墙挡住自己视线 → 感知不到 → 明明有烈焰人却报"等待生成")。
        List<BlazeEntity> blazeCandidates = new ArrayList<>(
                mod.getEntityTracker().getTrackedEntities(BlazeEntity.class));
        List<BlazeEntity> enclosureBlazes = blazesInEnclosure(mod);
        // (2026-09-19 22:47 诊断) 感知盲区实锤点: tracker 一只都没收录、围场盒里却有 ——
        // 那就是"bot 被自己砌的墙挡住视线"。30s 节流一条, 供实跑核对这个根因。
        if (blazeCandidates.isEmpty() && !enclosureBlazes.isEmpty()
                && System.currentTimeMillis() - _lastPerceptDiagMs > 30_000) {
            _lastPerceptDiagMs = System.currentTimeMillis();
            Debug.logToFile("altoclef_diag.log", "[BLAZE-PERCEPT] tracker 收录 0 只, 但围场盒内有 "
                    + enclosureBlazes.size() + " 只烈焰人 — 视线被自建围墙/顶盖挡住, 已由额外候选兜住");
        }
        for (BlazeEntity b : enclosureBlazes) {
            if (!blazeCandidates.contains(b)) blazeCandidates.add(b);
        }
        if (!blazeCandidates.isEmpty()) {

            // (2026-09-19 用户) 有防火效果时不逃跑 — 火球的火焰伤害被完全免疫,
            // 剩下的只有轻微碰撞伤害, 贴身近战收割才是正解。
            // (2026-09-19 18:55) 补两个豁免: ①包里有药 → 先喝药不逃 (跑是浪费药);
            // ②完工围场内(安全屋) → 坚守到收药冷却到点, 不逃。
            if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                    && ItemHelper.countFireResPotions(mod) == 0
                    && !isEnclosureSafeHaven(mod)
                    && blazeCandidates.size() >= TOO_MANY_BLAZES
                    && mod.getPlayer().getHealth() <= TOO_LITTLE_HEALTH_BLAZE) {
                setDebugState("Running away as there are too many blazes nearby.");
                return new TimeoutWanderTask();
            }

            // (2026-09-19 13:05 用户) 只打【我们自己围起来的区域】里的烈焰人;
            // 走廊/别的房间里的暂定直接不打 — 追出去打正是摔死事故的根源。
            // (2026-09-19 20:20 用户) 施工中(spawnerWorkPending)不开农场杀,
            // 但【贴脸】烈焰人(距 bot ≤3)必须紧急杀掉, 打远了就不管继续造墙
            // —— 施工让位后 MobDefense 不插手, 这里不处理就没人处理。
            if (_foundBlazeSpawner != null) {
                if (!spawnerWorkPending) {
                    Vec3d spawnerCenter = Vec3d.ofCenter(_foundBlazeSpawner);
                    toKill = closestBlazeWithin(mod, blazeCandidates, spawnerCenter, ENCLOSURE_KILL_RANGE, false);
                } else {
                    emergencyBlaze = true;
                    toKill = closestBlazeWithin(mod, blazeCandidates, mod.getPlayer().getPos(),
                            BLAZE_MELEE_EMERGENCY_RANGE, true);
                }
            }
        }

        if (toKill.isPresent() && toKill.get().isAlive()) {
            // (2026-09-06 药水门槛, 用户) 落单烈焰人不再浪费药水 — 改走盾牌
            // 波次格挡 (AbstractKillEntityTask)。只有以下情况才喝:
            //   1) 感知到 ≥ POTION_MIN_BLAZES 只烈焰人 (刷怪笼房, 火球密度太高);
            //   2) 盾战不可行: 没盾 (ThreatEvaluator.hasShield 含背包) 或血量低。
            // 有 FIRE_RESISTANCE 效果时不喝, 效果消失后才按上面门槛续杯。
            if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                    && ItemHelper.countFireResPotions(mod) > 0) {
                int blazeCount = mod.getEntityTracker().getTrackedEntities(BlazeEntity.class).size();
                boolean manyBlazes = blazeCount >= POTION_MIN_BLAZES;
                boolean shieldImpractical = !ThreatEvaluator.hasShield(mod)
                        || mod.getPlayer().getHealth() <= POTION_LOW_HEALTH;
                if (manyBlazes || shieldImpractical) {
                    setDebugState("Drinking fire resistance potion before fighting blaze");
                    return new DrinkFireResPotionTask();
                }
            }
            setDebugState(emergencyBlaze
                    ? "Killing CLOSE-RANGE blaze (emergency, then back to building)"
                    : "Killing blaze inside our enclosure");
            // 击杀谓词与选目标口径一致: 农场杀=距笼心 4.5 (限定围场内, 否则会追
            // 到走廊里打外面的 — 用户 13:05); 贴脸紧急杀=距 bot 3 (bot 站在墙外
            // 侧时目标可能距笼心 >4.5, 用笼心谓词会追丢)。
            if (emergencyBlaze) {
                Vec3d botPos = mod.getPlayer().getPos();
                // (2026-09-19 22:44) 补上"围场盒内的烈焰人"作为额外候选 —— 否则 bot 被
                // 自己砌的墙挡住视线时, 击杀任务拿不到目标 (见 blazesInEnclosure)。
                return new KillEntitiesTask(() -> new ArrayList<Entity>(blazesInEnclosure(mod)),
                        entity ->
                                entity.squaredDistanceTo(botPos) <= BLAZE_MELEE_EMERGENCY_RANGE * BLAZE_MELEE_EMERGENCY_RANGE
                                        && !isHoveringAboveLavaOrTooHigh(mod, entity),
                        BlazeEntity.class);
            }
            Vec3d spawnerCenter = Vec3d.ofCenter(_foundBlazeSpawner);
            return new KillEntitiesTask(() -> new ArrayList<Entity>(blazesInEnclosure(mod)),
                    entity ->
                            entity.squaredDistanceTo(spawnerCenter) <= ENCLOSURE_KILL_RANGE * ENCLOSURE_KILL_RANGE
                                    && !isHoveringAboveLavaOrTooHigh(mod, entity),
                    BlazeEntity.class);
        }


        // If the blaze spawner somehow isn't valid
        if (_foundBlazeSpawner != null && mod.getChunkTracker().isChunkLoaded(_foundBlazeSpawner) && !isValidBlazeSpawner(mod, _foundBlazeSpawner)) {
            Debug.logMessage("Blaze spawner at " + _foundBlazeSpawner + " too far away or invalid. Re-searching.");
            // (2026-09-18) 失效要连记忆一起废掉: 否则下次实例重建/记忆召回又把
            // bot 送回这个已经不是烈焰笼的地方, 形成来回拉扯。
            invalidateSpawner(_foundBlazeSpawner, "invalidated (not a blaze spawner / despawned)");
            _foundBlazeSpawner = null;
        }

        // If we have a blaze spawner, go near it.
        if (_foundBlazeSpawner != null) {
            // (2026-09-19 21:20 统一口径) 不再只数地狱岩 — 黑石/玄武岩/深板岩
            // 圆石/哭泣黑曜石等搭路方块一并计入 (ScaffoldBlocks 中心清单),
            // 否则包里 60 块黑石还跑去补地狱岩。
            int netherrackHave = ScaffoldBlocks.countInInventory(mod);

            // (2026-09-19 13:05 用户⑥) 围场阶段搭路方块 <32 → 随时补 32 个
            // (2 组备料用完后的续补口径)。粘性实例: 补货中途 have 渐涨但目标
            // 已固定, 只在目标上调时重建, 防 MineAndCollect 反复重启 (铁律 #3)。
            if (netherrackHave < ENCLOSURE_RESTOCK_TRIGGER) {
                setDebugState("Restocking scaffold blocks (have " + netherrackHave + ", topup "
                        + ENCLOSURE_RESTOCK_TOPUP + ")");
                return getEnclosureRestockTask(mod, netherrackHave + ENCLOSURE_RESTOCK_TOPUP);
            }
            // (2026-09-19 17:20) 不再无条件 clear —— 之前这里每 tick 清空, 而
            // !arrived 分支每 tick 重建 128 任务, "Preparing 128" 一秒刷 20 条 +
            // MineAndCollect 实例反复重建 (171228 实锤, 补货后期卡顿源之一)。
            // 复用/重建策略统一收敛到 getEnclosureRestockTask (isFinished 才重建)。

            boolean arrived = _foundBlazeSpawner.isWithinDistance(mod.getPlayer().getPos(), 4);
            if (!arrived) {
                // (2026-09-19 包围策略①, 用户) 出发前置: 准备 2 组 (128) 搭路
                // 方块。一次性闩: 补满一次即闩上, 不再反复检查 —— 否则路上
                // baritone 每垫一块脚手架就跌破阈值又弹回补货, 2s 一轮
                // Restock↔Going 死循环 (123515 实锤)。路上的消耗是正常开销,
                // 由上面的 <32 续补口径兜底。
                String stockKey = enclosureStockKey();
                boolean stockedForThisSpawner = stockKey != null && stockKey.equals(_enclosureStockedFor);
                if (!stockedForThisSpawner && netherrackHave < ENCLOSURE_NETHERRACK_PREP) {
                    setDebugState("Preparing " + ENCLOSURE_NETHERRACK_PREP + " netherrack (2 stacks) for spawner enclosure");
                    return getEnclosureRestockTask(mod, ENCLOSURE_NETHERRACK_PREP);
                }
                if (!stockedForThisSpawner) {
                    _enclosureStockedFor = stockKey;
                    clearEnclosureRestockTask();
                    Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] stock latch set (netherrack="
                            + netherrackHave + ", spawner=" + _foundBlazeSpawner.toShortString() + ")");
                }
                setDebugState("Going to blaze spawner");
                return new GetToBlockTask(_foundBlazeSpawner.up(), false);
            }

            // (2026-09-19 包围策略②, 用户) 到达刷怪笼先喝药水。包里没药不卡
            // 在这一步 (防火效果本身不是围场的硬前置, 硬等会死循环)。
            if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                    && ItemHelper.countFireResPotions(mod) > 0) {
                setDebugState("Drinking fire resistance at spawner (before enclosure)");
                return new DrinkFireResPotionTask();
            }

            // (2026-09-19 13:05 包围策略③⑤) 围场: 同层 5x5 地板 + 高一格/高两
            // 层各一圈 7x7 外圈围墙。破洞全扫每 15s 一次 (不每 tick 扫), 期间只
            // 对缓存清单做廉价的 isSolid 刷新 (防已补位置被当缺口循环)。
            if (_holeCheckTimer.elapsed() || _cachedMissingEnclosure == null) {
                _holeCheckTimer.reset();
                _cachedMissingEnclosure = findMissingEnclosureBlocks(mod, _foundBlazeSpawner);
                // (2026-09-19 18:55) 完工标志同步刷新 (断药坚守/收药切换的判定依据)。
                _enclosureCompleteFlag = _cachedMissingEnclosure.isEmpty();
                if (_cachedMissingEnclosure.isEmpty()) {
                    Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] enclosure complete (scan at tick "
                            + WorldHelper.getTicks() + ")");
                }
            }
            // (2026-09-19 16:05 问题三, 用户) 高处岩浆流进围场区域。
            // (2026-09-19 18:05 用户"20s没有必要") 见岩浆立即换笼, 不攒时长;
            // 必须放在 15s 洞检门控【外】, 否则 5s 节流形同虚设 (175617 实锤)。
            // 无候选可换时留下继续盖墙 (trySwitchAway 内兜底), 5s 后再查。
            long lavaNowMs = System.currentTimeMillis();
            if (lavaNowMs - _lavaLastCheckMs >= LAVA_CHECK_INTERVAL_MS) {
                _lavaLastCheckMs = lavaNowMs;
                if (isLavaInEnclosureRegion(mod, _foundBlazeSpawner)) {
                    trySwitchAwayFromLavaSpawner(mod);
                    if (_foundBlazeSpawner == null) {
                        setDebugState("Switching away from lava-flooded spawner");
                        return _fortressSearcher;
                    }
                }
            }
            List<BlockPos> missing = _cachedMissingEnclosure;
            // (2026-09-19 16:05) 围墙施工窗口标志: 有缺块就处于施工中。
            _enclosureBuildActive = missing != null && !missing.isEmpty();
            if (missing != null) {
                // 廉价刷新: 已补(或被别人补)的格从缓存清单剔除, 防"已补位置被当缺口"循环。
                missing.removeIf(p -> WorldHelper.isSolid(mod, p));
            }

            // (2026-09-19 21:50 用户) 统一节拍: 补墙 / 清障 / 灭火 三选一, 每拍只做
            // 一件事, 节拍内不重估 —— 取代原先三个分支同 tick 抢任务点的写法。
            Task beatTask = enclosureBeat(mod, missing);
            if (beatTask != null) return beatTask;

            // (2026-09-19 13:05 用户) bot 留在自己围起来的区域里打最方便最安全:
            // 每 10s 检查一次, 离开围场就回去 (允许短暂离开)。
            if (_returnCheckTimer.elapsed()) {
                _returnCheckTimer.reset();
                if (!isInsideEnclosure(mod.getPlayer().getBlockPos())) {
                    setDebugState("Returning to our enclosure");
                    return new GetToBlockTask(_foundBlazeSpawner.up(), false);
                }
            }

            setDebugState("Waiting inside our enclosure for blazes to spawn");
            return null;
        } else {
            // Search for blaze
            // (2026-09-04 人类式感知) 原来 getKnownLocations(SPAWNER) 绕过了
            // 感知过滤 —— 岩石后面的刷怪笼也会被"知道"。改为 getPerceivedLocations:
            // 只有 LOS+FOV 亲眼看见的刷怪笼才能确立目标; 走廊巡逻自然会看见它。
            for (BlockPos pos : mod.getBlockTracker().getPerceivedLocations(Blocks.SPAWNER)) {
                // (2026-09-19 17:20) 排除表同样拦感知重确认 —— 否则刚被换掉的
                // 岩浆笼就在眼前、每 tick 被"亲眼看见"重新确立, 无限回拉 (171228
                // 实锤: 换笼后 0.3s 内又确认回同一个岩浆笼)。
                if (isRejectedSpawner(pos)) continue;
                if (isValidBlazeSpawner(mod, pos)) {
                    // (2026-09-19 18:05 用户) 选笼阶段直接跳过岩浆笼: 打怪区域
                    // 有岩浆 = 到场就是喝药+备料+换笼的冤枉路, 当场排除。
                    if (isSpawnerLavaTroubled(mod, pos)) {
                        rejectSpawner(pos, "打怪区域有岩浆, 选笼时直接跳过");
                        continue;
                    }
                    _foundBlazeSpawner = pos;
                    // (2026-09-18) 亲眼确认 → 写 static, 后续实例重建能直接接回。
                    if (_worldKey != null) {
                        if (_confirmedSpawnerByKey.put(_worldKey, pos.toImmutable()) == null) {
                            Debug.logMessage("[BLAZE-SPAWNER] 亲眼确认刷怪笼并记住 " + pos.toShortString());
                        }
                    }
                    registerEnclosureProtection(mod);
                    break;
                }
            }
            // (2026-09-18) 亲眼也没看见 → 从跨会话记忆里召回一个候选点直接过去。
            if (_foundBlazeSpawner == null) {
                Task recall = recallSpawnerFromMemory(mod);
                if (recall != null) return recall;
            }
        }

        // We need to find our fortress.
        // (2026-09-04 人类式探索) 默认 FortressExploreTask (循声+走廊地面);
        // 超过 fortressSearchFallbackSeconds 仍未找到时降级回旧 chunk 扫描兜底。
        int fallbackSeconds = mod.getModSettings().getFortressSearchFallbackSeconds();
        if (fallbackSeconds > 0 && _searchTimer.getDuration() > fallbackSeconds) {
            if (!_fallbackEngaged) {
                _fallbackEngaged = true;
                Debug.logMessage("[FortressSearch] human-like explore timed out (" + fallbackSeconds
                        + "s), falling back to privileged chunk scan.");
            }
            setDebugState("Searching for fortress (fallback chunk scan)");
            return _searcher;
        }
        setDebugState("Searching for fortress (human-like explore)");
        return _fortressSearcher;
    }

    // ------------------------------------------------------------------ //
    // (2026-09-18 刷怪笼记忆) 召回 / 排除 / 失效
    // ------------------------------------------------------------------ //

    /**
     * 从跨会话记忆 ({@link KnowledgeMemory.StructureType#BLAZE_SPAWNER}) 取最近
     * 的、未被排除过的刷怪笼候选点, 直接导航过去。返回 null = 没有候选, 交由
     * 下面的走廊探索。
     *
     * 三段确认串起来才是"人记得并直接去": ① 记忆里有坐标 (跨会话落盘); ② 走到
     * 现场 (成功导航, 不是远程猜); ③ 现场验明确实是烈焰笼。
     */
    private Task recallSpawnerFromMemory(AltoClef mod) {
        if (_worldKey == null || mod.getPlayer() == null) return null;
        Vec3d pos = mod.getPlayer().getPos();
        List<BlockPos> candidates = new ArrayList<>(mod.getKnowledgeMemory()
                .getRememberedCenters(KnowledgeMemory.StructureType.BLAZE_SPAWNER, pos, SPAWNER_RECALL_RANGE));
        candidates.removeIf(this::isRejectedSpawner);
        if (candidates.isEmpty()) return null;

        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (BlockPos c : candidates) {
            double sq = c.getSquaredDistance(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = c;
            }
        }
        if (best == null) return null;

        if (_recallTarget == null || !_recallTarget.equals(best)) {
            _recallTarget = best;
            _recallTimer.reset();
            Debug.logMessage("[BLAZE-SPAWNER] 记忆召回候选 " + best.toShortString()
                    + " (距离 " + String.format(java.util.Locale.ROOT, "%.0f", Math.sqrt(bestSq)) + ", 候选数 " + candidates.size() + ")");
        }

        // 走到现场且区块可读 → 验明正身。
        if (mod.getChunkTracker().isChunkLoaded(best) && best.isWithinDistance(pos, SPAWNER_RECALL_ARRIVE)) {
            if (isValidBlazeSpawner(mod, best)) {
                // (2026-09-19 18:05 用户) 走到现场验明是烈焰笼, 但打怪区域有岩浆
                // → 同样当场排除, 继续找下一个候选。
                if (isSpawnerLavaTroubled(mod, best)) {
                    rejectSpawner(best, "记忆点烈焰笼但打怪区域有岩浆, 跳过");
                } else {
                    _foundBlazeSpawner = best;
                    _confirmedSpawnerByKey.put(_worldKey, best.toImmutable());
                    Debug.logMessage("[BLAZE-SPAWNER] 现场验明通过, 确立刷怪笼 " + best.toShortString());
                    registerEnclosureProtection(mod);
                }
            } else {
                // 记得这里有刷怪笼, 但它不是烈焰人的 (主世界蜘蛛笼之类)。
                rejectSpawner(best, "记忆点不是烈焰刷怪笼");
            }
            _recallTarget = null;
            return null;
        }

        // 太久没走到 = 够不着/被地形挡死, 排除后换下一个候选。
        if (_recallTimer.getDuration() > SPAWNER_RECALL_TIMEOUT) {
            Debug.logToFile("altoclef_diag.log", "[BLAZE-SPAWNER] 召回前往超时 " + SPAWNER_RECALL_TIMEOUT
                    + "s (停在 " + mod.getPlayer().getBlockPos().toShortString() + ", 目标 " + best.toShortString() + ")");
            rejectSpawner(best, "前往超时");
            _recallTarget = null;
            return null;
        }

        setDebugState("Going to remembered blaze spawner");
        return new GetToBlockTask(best.up(), false);
    }

    /** 该候选是否已被标记排除 (记忆点与实际不符 / 够不着)。 */
    private boolean isRejectedSpawner(BlockPos candidate) {
        Set<BlockPos> rejected = _rejectedSpawnerByKey.get(_worldKey);
        if (rejected == null || rejected.isEmpty()) return false;
        for (BlockPos p : rejected) {
            if (p.isWithinDistance(candidate, REJECT_RADIUS)) return true;
        }
        return false;
    }

    private void rejectSpawner(BlockPos pos, String reason) {
        if (_worldKey == null || pos == null) return;
        Set<BlockPos> rejected = _rejectedSpawnerByKey.computeIfAbsent(_worldKey, k -> new HashSet<>());
        if (rejected.size() >= MAX_REJECTED_SPAWNERS) {
            // 容量兜底: 整体作废重来, 宁可偶尔重试一次旧的错误点, 也不要无限增长。
            rejected.clear();
        }
        rejected.add(pos.toImmutable());
        Debug.logToFile("altoclef_diag.log", "[BLAZE-SPAWNER] 登记排除 " + pos.toShortString() + " — " + reason);
    }

    /** 已确认的刷怪笼失效: 清 static 记忆并登记排除, 免得下次又被召回。 */
    private void invalidateSpawner(BlockPos pos, String reason) {
        if (_worldKey != null) {
            _confirmedSpawnerByKey.remove(_worldKey, pos);
        }
        // (2026-09-19 18:55) 笼失效 = 安全屋/完工标志同步作废。
        _enclosureCompleteFlag = false;
        // (2026-09-19 17:20) 保护【不】随失效清除 —— 换笼后 bot 还在旧笼边上
        // 补货, 保护若立刻解除, 近场链挖会把刚搭好的墙当最近下界岩挖掉 (171228
        // 实锤: 换笼后 restock 挖掉自己围场的整圈墙)。保护一直持续到新刷怪笼
        // 确立 (registerEnclosureProtection 覆写) 或换存档。
        rejectSpawner(pos, reason);
    }

    /**
     * (2026-09-18) 换存档时清空本任务的全部 static 状态 —— 这类【坐标】残留 =
     * 上个世界的刷怪笼被当成这个世界的导航目标, 与 FortressExploreTask 锚点同例。
     */
    public static void onWorldBoundary() {
        _confirmedSpawnerByKey.clear();
        _rejectedSpawnerByKey.clear();
        _enclosureStockedFor = null;
        _protectedSpawnerPos = null;
        _lavaTroubleSpawnerKeys.clear();
    }

    /**
     * (2026-09-19 16:05 问题二b) 围场结构位置判定 (静态供行为谓词用)。
     * 材质无关: 围墙"只要求有东西堵着"—— 栅栏等挡得住的方块都算 (isEnclosureBlocking
     * 口径; 2026-09-19 19:30 前误用 isSolid, 栅栏被判缺口遭 builder 清场换成地狱岩),
     * 不要求非得是地狱岩。
     */
    private static BlockPos _protectedSpawnerPos = null;
    private static boolean _protectionRegistered = false;

    /**
     * (2026-09-19 16:22 修订; 2026-09-19 19:45 收窄) 围场保护盒: 刷怪笼周围
     * 9x9 × y-2..+4 整盒禁拆。材质无关: 围墙"只要求有东西堵着"。
     *
     * 【已删: 附近 16 格内栅栏/墙类方块禁拆】(2026-09-19 19:45, 用户"实测无效")
     * 三条理由:
     *   ① **管不到真凶**: avoidBlockBreaking → shouldAvoidBreaking 只有 baritone
     *      侧 (canBreak / MovementHelper.avoidBreaking 的寻路成本) 会查; 而拆掉
     *      栅栏的是 **builder process 的放置清场** 与 DestroyBlockTask 的手动长按,
     *      两者从不消费这个谓词 (同 BotBehaviour.isPortalBodyBlock 的已知边界,
     *      09-13 下界挖金把门框打穿实锤)。所以"栅栏被换成地狱岩"它拦不住。
     *   ② **却误伤合法挖掘**: 它是 COST_INF **硬禁**, 会把范围内 baritone 的采掘
     *      / 绕行合法路径一起废掉 (AltoClef.java 黑曜石段注释同款警告); 堡垒里
     *      16 格几乎全是栅栏结构, 寻路代价凭空上升。
     *   ③ **目的已被判据修复覆盖**: isEnclosureBlocking 已把栅栏算"已封堵", 不再
     *      进补块队列 → 没有放置任务 → 没有清场 → 不会拆。要保护的是"别把它当
     *      缺口", 而不是"禁止挖它"。
     * 真要"少挖穿堡垒栅栏"应走软避让 (blocksToAvoidBreaking, 只加成本不禁死),
     * 不是 shouldAvoidBreaking 这条硬禁。
     */
    private static final int SPAWNER_PROTECT_HALF = 4;

    private static boolean isEnclosureStructurePos(BlockPos pos) {
        BlockPos s = _protectedSpawnerPos;
        if (s == null) return false;
        int dx = Math.abs(pos.getX() - s.getX());
        int dz = Math.abs(pos.getZ() - s.getZ());
        int dy = pos.getY() - s.getY();
        // (2026-09-19 17:33 用户"最后要清理刷怪空间") 内圈 5x5 × y..y+2 (刷怪笼
        // 本体除外) 是要清空的刷怪空间, 不在保护集里 —— 否则清障拆不动。
        if (dy >= 0 && dy <= 2 && dx <= SPAWNER_FLOOR_HALF && dz <= SPAWNER_FLOOR_HALF) {
            if (!(dx == 0 && dz == 0 && dy == 0)) return false;
        }
        // 整盒保护: 9x9 水平 × 低两格到高四格。
        return dx <= SPAWNER_PROTECT_HALF && dz <= SPAWNER_PROTECT_HALF && dy >= -2 && dy <= 4;
    }

    /** 注册围场防拆谓词 (整个会话一次; 谓词读静态刷怪笼位, 换存档自动失效)。 */
    private void registerEnclosureProtection(AltoClef mod) {
        _protectedSpawnerPos = _foundBlazeSpawner.toImmutable();
        if (_protectionRegistered) return;
        _protectionRegistered = true;
        mod.getBehaviour().avoidBlockBreaking(CollectBlazeRodsTask::isEnclosureStructurePos);
        Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] structure break-protection registered (9x9 box)");
    }

    /** (2026-09-19 16:05 问题三) 岩浆 Trouble 登记表 (存档key@刷怪笼坐标)。 */
    private static final Set<String> _lavaTroubleSpawnerKeys = new HashSet<>();
    // (2026-09-19 18:05 用户"20s没有必要") 岩浆笼语义改为【当场跳过】: 选笼阶段
    // 就排除打怪区域有岩浆的笼 (不走路不喝药不备料), 施工中见岩浆立即换笼。
    // 20s 攒时长的唯一意义是给"盖墙挡岩浆"一个机会, 但 175617 实证高处浇下来
    // 的岩浆墙挡不住, 只是白喝一瓶药 → 计时器整体裁撤, 只留 5s 检查节流防刷屏。
    // (2026-09-19 17:50) 岩浆检查独立节奏, 每 5s 一次 (7x7x5 扫描很廉价)。
    private static final long LAVA_CHECK_INTERVAL_MS = 5_000;
    private long _lavaLastCheckMs = 0;

    // (2026-09-19 16:05 用户补充: 免逃岩浆只针对建围墙时的特殊宽限, 其他情况不变)
    // 围场施工进行中 (已到笼、缺块未补) 的全局标志 —— WorldSurvivalChain 只在
    // [防火效果 + 本标志] 同时成立时才豁免逃岩浆/灭火, 其余场景一律照旧。
    private static volatile boolean _enclosureBuildActive = false;

    /** 围场墙施工是否进行中 (供 WorldSurvivalChain 的岩浆/火焰豁免判定)。 */
    public static boolean isEnclosureBuildWorkActive() {
        return _enclosureBuildActive;
    }

    private String lavaTroubleKey() {
        return (_worldKey == null || _foundBlazeSpawner == null)
                ? null : _worldKey + "@" + _foundBlazeSpawner.toShortString();
    }

    /** 围场区域 (7x7 水平 × y-1..y+3) 内是否有岩浆。 */
    private boolean isLavaInEnclosureRegion(AltoClef mod, BlockPos spawner) {
        for (int dy = -1; dy <= 3; dy++) {
            for (int dx = -SPAWNER_WALL_HALF; dx <= SPAWNER_WALL_HALF; dx++) {
                for (int dz = -SPAWNER_WALL_HALF; dz <= SPAWNER_WALL_HALF; dz++) {
                    if (mod.getWorld().getBlockState(spawner.add(dx, dy, dz)).getBlock() == Blocks.LAVA) return true;
                }
            }
        }
        return false;
    }

    /**
     * (2026-09-19 18:05 用户) 该刷怪笼打怪区域 (围场 7x7 × y-1..y+3) 有岩浆 →
     * 选笼阶段【当场跳过】: 不走路过去、不喝防火药、不备 128 块料 — 到场才
     * 发现岩浆就是白喝一瓶药 + 白跑一趟。区块未加载时无法判定, 交给到场检查。
     */
    private boolean isSpawnerLavaTroubled(AltoClef mod, BlockPos spawner) {
        if (!mod.getChunkTracker().isChunkLoaded(spawner)) return false;
        return isLavaInEnclosureRegion(mod, spawner);
    }

    /**
     * (2026-09-19 18:05 用户"20s没有必要") 施工中一旦发现岩浆进打怪区域, 立即
     * 换笼 — 若记忆里有别的 (非排除) 刷怪笼候选就切换过去; 没有才留下来继续盖。
     */
    private void trySwitchAwayFromLavaSpawner(AltoClef mod) {
        if (_worldKey == null || _foundBlazeSpawner == null || mod.getPlayer() == null) return;
        Vec3d pos = mod.getPlayer().getPos();
        List<BlockPos> candidates = new ArrayList<>(mod.getKnowledgeMemory()
                .getRememberedCenters(KnowledgeMemory.StructureType.BLAZE_SPAWNER, pos, SPAWNER_RECALL_RANGE));
        candidates.removeIf(c -> isRejectedSpawner(c)
                || _lavaTroubleSpawnerKeys.contains(_worldKey + "@" + c.toShortString())
                || c.getSquaredDistance(_foundBlazeSpawner) < REJECT_RADIUS * REJECT_RADIUS);
        if (candidates.isEmpty()) {
            Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] lava in region but no alternative spawner known, keep building (recheck in 5s)");
            return;
        }
        String key = lavaTroubleKey();
        if (key != null) _lavaTroubleSpawnerKeys.add(key);
        Debug.logMessage("[BLAZE-ENCLOSURE] spawner lava-troubled, switching to another known spawner ("
                + candidates.size() + " candidates)");
        Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] switch away from lava spawner " + _foundBlazeSpawner.toShortString());
        invalidateSpawner(_foundBlazeSpawner, "lava pouring into region, switching away");
        _foundBlazeSpawner = null;
        _recallTarget = null;
        _recallTimer.reset();
        _cachedMissingEnclosure = null;
        _lavaLastCheckMs = 0;
        clearEnclosureRestockTask();
    }

    /**
     * (2026-09-19 17:33 用户"最后清理刷怪空间") 内圈 5x5 × y..y+2 (不含刷怪笼
     * 本体) 里第一个可拆的障碍块; 全空返回 null。岩浆/基岩等拆不动的跳过。
     */
    private BlockPos findInteriorObstructor(AltoClef mod, BlockPos spawner) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -SPAWNER_FLOOR_HALF; dx <= SPAWNER_FLOOR_HALF; dx++) {
                for (int dz = -SPAWNER_FLOOR_HALF; dz <= SPAWNER_FLOOR_HALF; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue; // 刷怪笼本体
                    BlockPos p = spawner.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(p).isAir()) continue;
                    if (!WorldHelper.canBreak(mod, p)) continue;
                    return p.toImmutable();
                }
            }
        }
        return null;
    }

    private boolean isValidBlazeSpawner(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            // If unloaded, go to it. Unless it's super far away.
            return false;
            //return pos.isWithinDistance(mod.getPlayer().getPos(),3000);
        }
        return WorldHelper.getSpawnerEntity(mod, pos) instanceof BlazeEntity;
    }

    /**
     * (2026-09-19 包围策略④) 已到达确认刷怪笼旁、且围场作业还没做完 (该喝的药
     * 没喝 / 围场缺块未补或还没扫过) → 期间不开杀, 由刷怪笼分支执行喝药与建围
     * 场。不在场或已确认完工则返回 false, 围场内击杀照常。
     */
    private boolean isSpawnerWorkPending(AltoClef mod) {
        if (_foundBlazeSpawner == null) return false;
        if (!_foundBlazeSpawner.isWithinDistance(mod.getPlayer().getPos(), 4)) return false;
        // 该喝药没喝 (包里有药且没效果) — 没药时不算 pending, 不死等。
        if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                && ItemHelper.countFireResPotions(mod) > 0) {
            return true;
        }
        // 还没扫过 (缓存 null) 或扫出缺口 → 围场未确认完工, 不开杀。
        List<BlockPos> missing = _cachedMissingEnclosure;
        if (missing == null || !missing.isEmpty()) return true;
        // (2026-09-19 17:33) 刷怪空间还没清完也算 pending — 先清场再开杀。
        return findInteriorObstructor(mod, _foundBlazeSpawner) != null;
    }

    // ------------------------------------------------------------------ //
    // (2026-09-19 21:50 用户) 围场维护统一节拍
    // ------------------------------------------------------------------ //

    /**
     * 围场维护统一节拍。每 {@link #ENCLOSURE_BEAT_SECONDS} 秒重新决策一次"这一拍
     * 做哪件事", 节拍内只认锁定动作, 同类工作可连续做 (清障可连挖多格)。
     * 返回 null = 本拍无事可做 (由调用方继续走回场/等待)。
     *
     * 为什么必须"决策与执行分离": 原先补墙/清障/灭火三个分支写在同一个 tick 路径
     * 上, 前一个分支 return 的子任务一旦被执行器判完/抢走, 下一 tick 立刻重估,
     * 于是三者以秒级频率互相打断 (214013: 60s 内 17 轮)。灭火这种"必须先走位
     * 1s+ 才能生效"的动作永远跑不完, 形成自锁环。
     */
    private Task enclosureBeat(AltoClef mod, List<BlockPos> missing) {
        if (_beatTimer.elapsed()) {
            settleBeatOutcome(mod);
            _beatTimer.reset();
            _skipBuildThisBeat = false;
            _beatAction = pickBeatAction(mod, missing);
        }
        switch (_beatAction) {
            case BUILD: {
                if (missing == null || missing.isEmpty()) {
                    _beatAction = EnclosureAction.NONE;
                    _currentBuildTarget = null;
                    return null;
                }
                BlockPos next = missing.get(0);

                // (2026-09-19 16:22) 自上向下优先, 但暂无贴面放不了时不死等:
                // 同一目标超 BUILD_TARGET_STALL_MS 就轮到队尾。必须先于下面的顶盖
                // 站位前置 —— 否则"下不去"时前置会每 tick 重发同一个 GetToBlockTask,
                // 永远走不到 stall 兜底 (死等)。
                long nowMs = System.currentTimeMillis();
                if (!next.equals(_currentBuildTarget)) {
                    _currentBuildTarget = next;
                    _currentBuildStartMs = nowMs;
                } else if (nowMs - _currentBuildStartMs > BUILD_TARGET_STALL_MS) {
                    missing.remove(0);
                    missing.add(next);
                    _currentBuildTarget = null;
                    Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] build target stalled, rotating "
                            + next.toShortString() + " to back of queue");
                    // (2026-09-19 22:25 用户"建墙完成百分之多少才开始节流") 建墙优先期
                    // (完成度 < 90%) 不降级: 轮转后继续建其他块, 不把本拍让给清障/灭火。
                    if (isBuildFocusPhase(missing.size())) {
                        return null;
                    }
                    // 收尾期才降级 (否则补墙优先级最高会把另外两个分支无限饿死)。
                    _skipBuildThisBeat = true;
                    _beatAction = pickBeatAction(mod, missing);
                    return null;
                }

                // (2026-09-19 21:50 顶盖死结, 用户现场确认"他站在顶的那个位置上,
                // 头挡住那个位置, 没法封顶; 其实站在底下就可以放方块")
                // 214013 实锤: <Place structure{} at 779,80,123> 报 "Standing inside
                // target cell, no walkable neighbor, wandering" —— bot 把自己站进了
                // 顶盖最后一个缺口格, 而 PlaceBlockTask.pickStepOutPos 只往【水平】
                // 四向找落脚点, 那四格全是它刚放好的顶盖 (isSolid) → 返回 null →
                // 只能原地 wander (走不出去) → 10s stall 轮转队尾 → 15s 重扫又报
                // 同一格, "1 missing" 永远补不上。
                // (2026-09-19 22:25 回归修复) 触发条件必须收紧到【真的被困住】:
                // 221114 实锤 —— 只判"bot 在顶盖层"就抢, 会和 PlaceBlockTask 自己的
                // "Standing inside target cell, stepping out first"(水平走到邻格再放)
                // 每 2~3 秒互抢一次 (22:15:52~22:16:14 六轮), 而抢完后返回的
                // GetToBlockTask(spawner.up()) 的"到达"并不等于真下到围场内
                // (301ms 就 Completing goal) → 下一 tick 又触发, 形成横跳。
                // 现在只在水平四邻全堵 (pickStepOutPos 会返回 null 的那种) 才介入。
                if (next.getY() >= _foundBlazeSpawner.getY() + SPAWNER_ROOF_HEIGHT
                        && isTrappedInTargetCell(mod, next)) {
                    setDebugState("Dropping down to build roof from below");
                    return new GetToBlockTask(_foundBlazeSpawner.up(), false);
                }

                setDebugState("Building enclosure: " + missing.size() + " missing, next " + next.toShortString());
                return new PlaceStructureBlockTask(next);
            }
            case CLEAR: {
                BlockPos interior = findInteriorObstructor(mod, _foundBlazeSpawner);
                if (interior == null) {
                    _beatAction = pickBeatAction(mod, missing);
                    return null;
                }
                setDebugState("Clearing spawn space inside enclosure: " + interior.toShortString());
                return new DestroyBlockTask(interior);
            }
            case FIRE: {
                BlockPos fire = pickFireToExtinguish(mod);
                if (fire == null) {
                    _beatAction = EnclosureAction.NONE;
                    _beatFireTarget = null;
                    return null;
                }
                _beatFireTarget = fire.toImmutable();
                setDebugState("Clearing fire around spawner to prevent loss of blaze rods.");
                return new PutOutFireTask(fire);
            }
            default:
                return null;
        }
    }

    /**
     * (2026-09-19 22:25 回归修复) bot 是否【真的】被困在目标格里走不出去 ——
     * 与 {@code PlaceBlockTask.pickStepOutPos} 同口径: 脚正好在目标格, 且水平四向
     * 的候选 (非固体 + 头顶非固体) 一个都没有。
     *
     * 为什么必须复算, 而不是"看 bot 在顶盖层就抢": 221114 实锤 —— bot 在顶盖层时
     * PlaceBlockTask 会走 "Standing inside target cell, stepping out first"
     * (自己水平走到 777,80,121 再放), 父任务若一律抢走返回
     * GetToBlockTask(spawner.up()), 两者每 2~3 秒互抢一次 (22:15:52~22:16:14
     * 六轮); 且 GetToBlockTask 的"到达"不等于真下到围场内 (301ms 就 Completing
     * goal), 于是下一 tick 又触发, 形成横跳。只有水平四邻全堵 (214013 那种
     * "no walkable neighbor" 真死结) 才需要父任务介入把它降下来。
     */
    private static boolean isTrappedInTargetCell(AltoClef mod, BlockPos target) {
        if (!mod.getPlayer().getBlockPos().equals(target)) return false;
        for (Vec3i offset : STEP_OUT_OFFSETS) {
            BlockPos p = target.add(offset);
            if (WorldHelper.isSolid(mod, p)) continue;      // 被堵
            if (WorldHelper.isSolid(mod, p.up())) continue; // 头顶被堵
            return false;
        }
        return true;
    }

    private static final Vec3i[] STEP_OUT_OFFSETS = new Vec3i[]{
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)
    };

    /** 本拍该做什么: 补墙 (可被 stall 降级跳过) > 清障 > 灭火 > 无。 */
    private EnclosureAction pickBeatAction(AltoClef mod, List<BlockPos> missing) {
        int missingCount = (missing == null) ? 0 : missing.size();
        // (2026-09-19 22:25 用户) 建墙优先期 (完成度 < 90%): 只看补墙, 不轮入清障/
        // 灭火 —— 连 stall 降级都不给让位, 免得"建一会就换任务"。
        boolean buildFocus = missingCount > 0 && isBuildFocusPhase(missingCount);
        if (missingCount > 0 && (buildFocus || !_skipBuildThisBeat)) return EnclosureAction.BUILD;
        if (findInteriorObstructor(mod, _foundBlazeSpawner) != null) return EnclosureAction.CLEAR;
        if (pickFireToExtinguish(mod) != null) return EnclosureAction.FIRE;
        return EnclosureAction.NONE;
    }

    /** 缺块占比是否还在"专心建墙"区间内 (完成度 < {@link #ENCLOSURE_BUILD_FOCUS_COMPLETION})。 */
    private static boolean isBuildFocusPhase(int missingCount) {
        double completion = 1.0 - (double) missingCount / ENCLOSURE_TOTAL_BLOCKS;
        return completion < ENCLOSURE_BUILD_FOCUS_COMPLETION;
    }

    /**
     * 节拍结算: 上一拍锁定的灭火动作若结束时火还在 → 记一次失败; 连续
     * {@link #FIRE_GIVEUP_ATTEMPTS} 拍都没扑灭 → 该火位进 {@link #FIRE_GIVEUP_COOLDOWN_MS}
     * 冷却, 期间不再选它 (烈焰人火球会持续重新点燃, 无限重试没有任何收益)。
     */
    private void settleBeatOutcome(AltoClef mod) {
        BlockPos target = _beatFireTarget;
        _beatFireTarget = null;
        if (_beatAction != EnclosureAction.FIRE || target == null) return;
        BlockState s = mod.getWorld().getBlockState(target);
        if (s.getBlock() != Blocks.FIRE && s.getBlock() != Blocks.SOUL_FIRE) {
            _fireAttempts.remove(target);
            return;
        }
        int attempts = _fireAttempts.merge(target, 1, Integer::sum);
        if (attempts >= FIRE_GIVEUP_ATTEMPTS) {
            _fireAttempts.remove(target);
            _fireGiveUpUntilMs.put(target, System.currentTimeMillis() + FIRE_GIVEUP_COOLDOWN_MS);
            Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] fire at " + target.toShortString()
                    + " still burning after " + attempts + " beats, cooling down "
                    + (FIRE_GIVEUP_COOLDOWN_MS / 1000) + "s");
        }
    }

    /** 刷怪笼 5 格内最近的、未在冷却中的火; 无火或全在冷却 → null。 */
    private BlockPos pickFireToExtinguish(AltoClef mod) {
        long now = System.currentTimeMillis();
        _fireGiveUpUntilMs.entrySet().removeIf(e -> e.getValue() <= now);
        BlockPos best = null;
        double bestSq = FIRE_SEARCH_RANGE * FIRE_SEARCH_RANGE;
        for (BlockPos p : mod.getBlockTracker().getKnownLocations(Blocks.FIRE)) {
            if (_fireGiveUpUntilMs.getOrDefault(p, 0L) > now) continue;
            double sq = p.getSquaredDistance(_foundBlazeSpawner);
            if (sq <= bestSq) {
                bestSq = sq;
                best = p;
            }
        }
        return best;
    }

    /** (2026-09-19 21:50) 灭火搜索半径, 与旧内联常量 5 同值。 */
    private static final double FIRE_SEARCH_RANGE = 5;

    /**
     * (2026-09-19 17:20 终版规格) 围场缺口全扫, 顺序自上向下:
     * 顶盖 (y+3, 5x5) → 围墙 (y+1, 7x7 外圈单圈) → 地板 (y-1, 5x5)。
     * (2026-09-19 19:30) 缺口判据改为 {@link #isEnclosureBlocking} (见其注释)。
     */
    private List<BlockPos> findMissingEnclosureBlocks(AltoClef mod, BlockPos spawner) {
        List<BlockPos> missing = new ArrayList<>();
        int looseBlocked = 0;
        // ① 顶盖 y+3, 5x5。
        for (int dx = -SPAWNER_FLOOR_HALF; dx <= SPAWNER_FLOOR_HALF; dx++) {
            for (int dz = -SPAWNER_FLOOR_HALF; dz <= SPAWNER_FLOOR_HALF; dz++) {
                BlockPos p = spawner.add(dx, SPAWNER_ROOF_HEIGHT, dz);
                looseBlocked += noteEnclosureGap(mod, p, missing);
            }
        }
        // ② 围墙 y+1, 7x7 外圈。
        for (int dx = -SPAWNER_WALL_HALF; dx <= SPAWNER_WALL_HALF; dx++) {
            for (int dz = -SPAWNER_WALL_HALF; dz <= SPAWNER_WALL_HALF; dz++) {
                if (Math.abs(dx) != SPAWNER_WALL_HALF && Math.abs(dz) != SPAWNER_WALL_HALF) continue;
                BlockPos p = spawner.add(dx, SPAWNER_WALL_HEIGHT, dz);
                looseBlocked += noteEnclosureGap(mod, p, missing);
            }
        }
        // ③ 脚底地板 y-1, 5x5。
        for (int dx = -SPAWNER_FLOOR_HALF; dx <= SPAWNER_FLOOR_HALF; dx++) {
            for (int dz = -SPAWNER_FLOOR_HALF; dz <= SPAWNER_FLOOR_HALF; dz++) {
                BlockPos p = spawner.add(dx, -1, dz);
                looseBlocked += noteEnclosureGap(mod, p, missing);
            }
        }
        if (looseBlocked != _lastLooseBlocked) {
            _lastLooseBlocked = looseBlocked;
            Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] scan: missing=" + missing.size()
                    + ", blocked-by-non-full-cube (fence/wall/glass...)=" + looseBlocked
                    + ", spawner=" + spawner.toShortString());
        }
        return missing;
    }

    /** 单格登记: 真缺口入 missing 返回 0; 非满立方但已封堵返回 1 (仅供计数/日志)。 */
    private static int noteEnclosureGap(AltoClef mod, BlockPos p, List<BlockPos> missing) {
        boolean solid = WorldHelper.isSolid(mod, p);
        if (solid || isEnclosureBlocking(mod, p)) {
            return solid ? 0 : 1;
        }
        missing.add(p.toImmutable());
        return 0;
    }

    /**
     * (2026-09-19 19:30 用户"下界砖栅栏也是遮挡方块, 没必要换成地狱岩") 围场
     * 缺口判据 = 【挡得住就算数】, 不要求整格满立方:
     *   isSolid (满立方: 地狱岩 / 下界砖 …) 算;
     *   非满立方但有碰撞箱 —— 栅栏 / 墙 / 玻璃 / 铁栏杆 / 台阶 —— 也算。
     *
     * 为什么不能只用 isSolid: isSolidBlock 要求整格满立方, 下界砖栅栏 (堡垒自带)
     * 恒为 false → 被当成缺口 → 交给 baritone builder 补块, 而 builder 放置前会
     * "清场" (挖掉目标位原有方块), 于是好端端的栅栏被拆掉换成地狱岩 (用户现场)。
     * 更糟的一步: 当时 isEnclosureStructurePos 还把 16 格内栅栏列为禁拆, 拆不动 →
     * 建造目标永久 stall (每 10s 轮转一次队尾), 围场永远"没完工"。那条禁拆支已于
     * 19:45 删除 (见其注释: 管不到 builder 清场、却误伤合法挖掘), 本判据才是根治项。
     *
     * 空气 / 流体 / 火把等无碰撞箱 → 仍算缺口, 该补照补。
     * 地板位同样用宽松判据: 栅栏顶面站得住, 挖开反而可能踩空掉进洞或岩浆。
     */
    private static boolean isEnclosureBlocking(AltoClef mod, BlockPos pos) {
        if (WorldHelper.isSolid(mod, pos)) return true;
        BlockState s = mod.getWorld().getBlockState(pos);
        if (s.isAir()) return false;
        return !s.getCollisionShape(mod.getWorld(), pos).isEmpty();
    }

    /**
     * (2026-09-19 13:05) bot 是否还在自己围起来的 7x7 区域内 (只看水平投影,
     * 允许任何高度 — 被顶一下/跳一下不算离开)。
     */
    private boolean isInsideEnclosure(BlockPos pos) {
        return isInsideEnclosureOf(_foundBlazeSpawner, pos);
    }

    // ------------------------------------------------------------------ //
    // (2026-09-19 18:55 用户三条特殊规则) 断药场景: 收药 / 墙内坚守
    // ------------------------------------------------------------------ //

    /** 当前确认笼的围场是否已完工 (每次 15s 洞检刷新; 换笼/失效即 false)。 */
    private static volatile boolean _enclosureCompleteFlag = false;

    /**
     * (2026-09-19 18:55 用户) 断药 (无效果且无药水) 时是否该放弃当前动作去收药:
     * ① 没有确立的笼 / 围场未完工 → true (规则①, 停止造墙去收药);
     * ② 围场完工 → 收药冷却 (8 分钟) 到点才 true (规则③), 未到点留在墙里打 (规则②)。
     */
    private boolean shouldAbandonForPotions() {
        if (_protectedSpawnerPos == null || !_enclosureCompleteFlag) return true;
        return System.currentTimeMillis() >= BeatMinecraft2Task.getFireResReopenAtMs();
    }

    /** 静态口径的"人在围场内" (供安全屋判定, 不依赖实例的 _foundBlazeSpawner)。 */
    private static boolean isInsideProtectedEnclosure(BlockPos pos) {
        return isInsideEnclosureOf(_protectedSpawnerPos, pos);
    }

    /**
     * (2026-09-19 19:45 合并) "围场内"的唯一判据: 以 center 为中心的 7x7 水平
     * 投影 (只看水平, 允许任何高度 — 被顶一下/跳一下不算离开)。原先实例口径与
     * 静态口径各抄了一份同样的阈值表达式, 属同概念两套判据, 收成一处。
     */
    private static boolean isInsideEnclosureOf(BlockPos center, BlockPos pos) {
        if (center == null || pos == null) return false;
        return Math.abs(pos.getX() - center.getX()) <= SPAWNER_WALL_HALF
                && Math.abs(pos.getZ() - center.getZ()) <= SPAWNER_WALL_HALF;
    }

    /**
     * (2026-09-19 18:55 用户"有药水效果还逃跑应该避免") 完工围场 = 安全屋:
     * 人在自己盖好的 7x7 围场里, 且 (有防火效果 或 8 分钟收药冷却未到) →
     * MobDefenseChain 对纯烈焰人威胁不撤退, 留在墙里打到冷却到点。
     * 换笼/失效/离开围场自动失效。
     */
    public static boolean isEnclosureSafeHaven(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return false;
        if (_protectedSpawnerPos == null || !_enclosureCompleteFlag) return false;
        if (!isInsideProtectedEnclosure(mod.getPlayer().getBlockPos())) return false;
        return mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                || System.currentTimeMillis() < BeatMinecraft2Task.getFireResReopenAtMs();
    }

    // ------------------------------------------------------------------ //
    // (2026-09-19 卡死修复 120616) 包围补货任务粘性。父任务每 tick 重估子任务,
    // 每 tick new 一个 MineAndCollect 会反复重启寻路/追踪 (铁律 #3 同款);
    // 这里复用实例, 只在目标上调时重建, 缺料解决后由调用方 clear。
    // ------------------------------------------------------------------ //
    private Task _enclosureRestockTask = null;
    private int _enclosureRestockTarget = -1;

    // (2026-09-19 123515 追加) "出发前置 32 已备足"的一次性闩, 记到 存档key@刷怪笼坐标。
    // 不闩的话路上 baritone 每垫一块脚手架 31<32 就弹回补货 (恰好 32 零余量),
    // 2s 一轮 Restock↔Going 死循环。换存档 (onWorldBoundary) 自动失效。
    private static String _enclosureStockedFor = null;

    /** 当前 (存档key @ 已确认刷怪笼) 的备料闩 key; 无刷怪笼返回 null。 */
    private String enclosureStockKey() {
        return (_worldKey == null || _foundBlazeSpawner == null)
                ? null : _worldKey + "@" + _foundBlazeSpawner.toShortString();
    }

    private Task getEnclosureRestockTask(AltoClef mod, int target) {
        // (2026-09-19 17:20) 重建条件 = 未创建 / 目标上调 / 旧任务已凑满。同目标
        // 重建不刷日志 (避免 一秒 20 条 CHAT 刷屏, 171228 实锤)。
        boolean recreate = _enclosureRestockTask == null
                || target > _enclosureRestockTarget
                || _enclosureRestockTask.isFinished(mod);
        boolean logIt = recreate && target != _enclosureRestockTarget;
        if (recreate) {
            _enclosureRestockTarget = target;
            // (2026-09-19 21:20 统一口径) 挖什么按维度/存量定: 下界优先地狱岩,
            // 背包已有黑石/玄武岩就续用同款 (ScaffoldBlocks.preferredRestockItem)。
            Item restockItem = ScaffoldBlocks.preferredRestockItem(mod);
            _enclosureRestockTask = TaskCatalogue.getItemTask(restockItem, target);
            if (logIt) {
                Debug.logMessage("[BLAZE-ENCLOSURE] restock task target=" + target
                        + " (" + restockItem.toString() + " now " + ScaffoldBlocks.countInInventory(mod) + ")");
            }
        }
        return _enclosureRestockTask;
    }

    private void clearEnclosureRestockTask() {
        _enclosureRestockTask = null;
        _enclosureRestockTarget = -1;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.SPAWNER);
        clearEnclosureRestockTask();
        _enclosureBuildActive = false;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectBlazeRodsTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " blaze rods";
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }
}
