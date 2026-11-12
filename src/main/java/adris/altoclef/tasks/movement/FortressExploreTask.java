package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Settings;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.bridge.NamedLandmarks;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.helpers.ScaffoldBlocks;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.trackers.KnowledgeMemory;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.BiomeKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * (2026-09-04 人类式找要塞) 替代 SearchChunkForBlockTask(NETHER_BRICKS) 的
 * "chunk 全方块透视扫描", 模拟人类在下界找烈焰人要塞的过程。
 *
 * 两种模式:
 *
 * 1. 外围探索 (没找到要塞): 带"方向感"的游走 —— 优先朝最近 N 秒内听到的
 *    烈焰人音效方向 (SoundTracker, 人类听觉), 其次朝"亲眼看见"(LOS+FOV,
 *    getPerceivedLocations)的下界砖方向, 再次朝 KnowledgeMemory 里"看见过
 *    的要塞地标"推断的候选区。什么线索都没有就沿一个【持久锁定的方位角】
 *    持续前进 (跨任务实例存活, 只在 ±5° 内微调) —— 即"远征": 不原地打转、
 *    不回头, 靠持续位移穿过下界生物群系去撞结构 (不传送、不直奔目标, 保持人类式)。
 *
 * 2. 走廊探索 (踏上要塞地面, 用户核心需求): 只在要塞的可行走地面上探索
 *    —— 地面是下界砖(含楼梯/台阶)、上方两格空气的"走廊/桥面/房间"格子。
 *    像人一样沿着城堡的路走: 先走没走过的格子 (前沿), 全走过后按"最久
 *    没走"轮换巡逻。绝不因为"格子走完了"就跑回野外乱逛。
 *
 * 刷怪笼本身不在这里找: 上层 CollectBlazeRodsTask 用 getPerceivedLocations
 * (SPAWNER) 只认"亲眼看见"的刷怪笼; 走廊巡逻自然会把视野扫过要塞各处。
 */
public class FortressExploreTask extends Task implements ITaskRequiresGrounded {

    // 烈焰人音效 id 前缀 (ambient/burn/hurt/death/shoot 全系)。
    private static final String BLAZE_SOUND_PREFIX = "entity.blaze.";
    // 人类听觉放宽: MC 音效衰减约 16 格, 稍放宽到 24 用于定位方向。
    private static final double BLAZE_HEARING_RANGE = 24;
    // (2026-09-05 通用化: 找猪灵) 猪灵音效前缀 (ambient/jealous/admiring 等,
    // 不含 piglin_brute — 猪灵蛮兵不交易, 循它的声会带偏)。前缀带 '.' 不会
    // 误匹配 "entity.piglin_brute.*"。
    private static final String PIGLIN_SOUND_PREFIX = "entity.piglin.";
    private static final double PIGLIN_HEARING_RANGE = 32;

    // 要塞地面格子扫描: 半径 / 步长 / 玩家 Y 上下扩展 / 周期 (tick)。
    private static final int FLOOR_SCAN_RADIUS = 48;
    private static final int FLOOR_SCAN_STEP = 2;
    private static final int FLOOR_SCAN_Y_RANGE = 10;
    private static final int FLOOR_SCAN_INTERVAL_TICKS = 40;
    // 玩家到最近下界砖可见位置小于该距离 → 认为已"踏上要塞附近", 进走廊模式。
    private static final double FORTRESS_NEARBY_RANGE = 32;
    // 走廊格子到达判定距离。
    private static final double CORRIDOR_ARRIVED_DISTANCE = 3;

    // =========================================================================
    // (2026-09-05 走廊记忆稳定性 + 跨会话持久化, 用户"探索 fortress 没有矿洞
    // 类似的记忆机制, 一直小区域打转") 两个问题一起修:
    //
    // 1. 【打转根因】扫描网格以玩家坐标为原点 (x = player.getX() + dx, 步长 2),
    //    玩家移动后采样点奇偶随之漂移 —— 同一块物理地板会以相邻的 BlockPos 重新
    //    出现在"没走过"集合里, frontier 永远有"新"目标, 实际是在已走过的走廊
    //    来回打转。修法: 归一到【世界原点锚定】的 2 格网格 (floorDiv), 与玩家
    //    位置彻底无关。
    // 2. 【跨会话记忆】走过的走廊格子持久化到游戏目录 altoclef_fortress_visited.log
    //    (每行 "存档:维度 x y z"), key 与矿洞 altoclef_explored.log 同款
    //    (存档名+维度, 不同世界不共享)。任务重启/游戏重开都不再重走已探索走廊。
    // =========================================================================
    private static final String FORTRESS_VISITED_FILE = "altoclef_fortress_visited.log";
    // 单 key (存档:维度) 的走廊格子记忆上限, 防文件/内存无限增长。
    private static final int MAX_PERSISTED_CELLS = 8192;
    private static final java.util.Map<String, java.util.LinkedHashSet<BlockPos>> _visitedCellsByKey
            = new java.util.HashMap<>();
    private static boolean _visitedCellsLoaded = false;

    // 外围探索一步多远, 偏置锥角 (仿 BiasedExploreTask)。
    // (2026-09-14 短步修订, 用户"目标太远, 模仿人类先短距离走起来"):
    // 48 → 24。实测 (20260914-204117): 48 格目标横穿岩浆碎块地形, baritone
    // 4s primaryTimeout 预算内算不完, 只回 cost>3 的 ~15 格 best-so-far 段,
    // bot 走两步又停。短目标 = 搜索空间小, 单轮规划大概率秒级算完, bot 连续
    // 走; 到达即换下一小步, 方向仍由锁定方位角保证净位移 (抖动 5° 不变)。
    // 试探长步 ×2 = 48 (沿崖根长步保留)。
    private static final double EXPLORE_STEP = 24;
    private static final double BIAS_ANGLE_DEG = 12;
    // (2026-09-13 远征) 25° → 5°: 原 ±25° 抖动 + 48 格步长 = 布朗运动,
    // 净位移 ~√N 而非 N —— 即使实例不重建也是小区域打转。降到 5° 后是
    // "锁定方向一路向" (用户选定), 净位移 ~N。
    private static final double RANDOM_JITTER_DEG = 5;
    // 外围探索连续失败的容忍次数 (超过后本任务结束, 交给上层重决策/兜底)。
    private static final int MAX_FAIL_COUNT = 10;
    // (2026-09-13 导航目标循环) 远征目标: 走到目标点水平 R 格内【任意高度】即到,
    // 不要求站上目标柱。GoalXZ 精确柱语义在高地/峡谷地形会逼 baritone 出
    // "sneak-bridging 数十格" 畸形路径 (cost>3 yolo), 段间重规划 7M 节点 4s 一轮,
    // 执行两步一停 (走两步停一下循环, 20260913-211736 实锤)。到达判定半径须
    // >= 目标半径, 否则 baritone 停在 4.4 格而到达判 4 格 → 永远差一点, 无限重设。
    private static final double EXPEDITION_GOAL_RADIUS = 4.5;
    private static final double EXPEDITION_ARRIVE_XZ = 5;
    // (2026-09-13 P2 遇障换向) 锁定方向在悬崖/岩浆海前会死顶 (20260913-214110:
    // radius 卡 326 半分钟, fails 连涨, moved 三连 0 —— 用户"方向是不是定的太死")。
    // (2026-09-13 P2.1 大悬崖修订, 用户"总可以绕一绕, 没有大局观"): 旧版三大死穴
    // ① 转正判据 = 距锚点半径破纪录, 但绕崖走是绕锚点的圆弧, 半径不变 → 永远
    //   无法转正; ② 换边乒乓 (+60 → -75 → +90 → -90), 大障碍前左右横跳;
    // ③ 试探步长 48 格, 沿崖根走不够绕过。修订:
    //   触发 = 停滞 STALL_MS 且 (失败 ≥PROBE_AFTER_FAILS 或 停滞 ≥2×STALL_MS);
    //   (20261112 A) 停滞 ≥2×STALL_MS 不再要求"失败≥1" —— 沿岩浆边乒乓时
    //   progress 检查器从不判失败, 带失败前提 = probe 永久锁死; 且调用方从
    //   "两条失败路径"扩到"每 tick 周期调用" (升级间隔 2×STALL_MS 节流);
    //   升级 = 同侧加档 +60 → +75 → +90, 同侧到顶才换边; 试探方向
    //   步长 ×2 (沿崖要走长步); 转正 = 半径破纪录 或 距试探起点净位移 > EXPLORE_STEP
    //   (绕崖横向走出一个整步程 = 真的绕出去了)。
    // (2026-09-14 大转弯修订, 用户"策略太严, 大范围内可以允许大转弯"): 大片岩浆
    // 悬崖/岩浆海 (20260914-183710: radius 卡 406 两分多钟, probe 在 ±90° 两侧
    // 乒乓, moved 全程 0~1.5) 前 ±90° 封顶太严 —— 大岩浆海的绕行方向往往在
    // 90°~150° 之间, 90° 封顶等于逼 bot 沿崖根平推撞死。封顶放宽到 ±150°
    // (大转弯, 允许接近侧向), 同侧加档步距 15° → 25° (60/85/110/135/150 共
    // 五档, 一侧约 1 分钟见分晓再换边)。
    private static final int PROBE_AFTER_FAILS = 2;
    /**
     * (2026-09-26 试探解冻) 停滞判据由「实例内 tick 计数」改为【墙钟毫秒】。
     *
     * <p>旧值 {@code STALL_TICKS=100} 与实例字段 {@code _tickId} 配对使用, 而
     * {@code _tickId} 是不归零的实例计数器、停滞基线却是 static 跨实例存活 ——
     * 上层 (TradeWithPiglinsTask / CollectBlazeRodsTask) 每 4~12 分钟重建本任务
     * 一次时, 新实例 {@code _tickId} 从 0 起而基线是旧实例的大值 →
     * {@code stalledTicks = 小 - 大 = 负数}, 恒 {@code < 100} → 遇障 (寻路失败)
     * 时试探升级全程零触发。毫秒基准跨实例天然连续, 与食物远征基类
     * {@code BearingExpeditionTask} 同一修法。
     */
    private static final long STALL_MS = 5000; // 5s 无新最远点 = 停滞
    private static final double PROBE_STEP_DEG = 60;
    private static final double PROBE_INCREMENT_DEG = 25;
    private static final double MAX_PROBE_DEG = 150;
    // (2026-09-13 远征取证) 诊断周期 200 tick = 10s; 距锚点半径每增长 128 格
    // 额外打一行 —— 此前日志里没有任何坐标/群系遥测, 无法证明"是否真在远征"。
    private static final int EXPEDITION_DIAG_INTERVAL_TICKS = 200;
    private static final double EXPEDITION_RADIUS_MILESTONE = 128;

    // =========================================================================
    // (2026-09-09 探深后再巡逻, 用户"还没探索完整怎么就触发巡逻了, 连刷怪笼
    // 都没找到") 根因: 走廊格子扫描半径只有 FLOOR_SCAN_RADIUS=48, bot 站在
    // 堡垒入口时"没走过的格子"只统计 48 格内的 — 前沿很快走空就被误判"探索
    // 完了"转入最久没走巡逻, 深处走廊/刷怪笼根本没进过扫描视野。
    // 修法: 前沿走空时先"探深" — 朝最远已走格再往外探 EXPLORE_EXTEND_BLOCKS,
    // 把更深的走廊拉进扫描半径; 连续 EXPAND_MAX_FAIL 次探深都没发现新格子,
    // 才承认真的走完了, 转入轮换巡逻。
    // =========================================================================
    private static final int EXPLORE_EXTEND_BLOCKS = 16;
    private static final int EXPAND_MAX_FAIL = 3;
    private int _expandFailStreak = 0;
    // 当前目标是否为"探深"目标 (不是真实走廊格, 到达/失败都不记 visited)。
    private boolean _currentIsProbe = false;

    private final MovementProgressChecker _progress = new MovementProgressChecker();

    // 走廊格子状态: 已走集合 + 最近一次走的时间戳 (用于"最久没走"巡逻轮换)。
    private final Set<BlockPos> _visitedCells = new HashSet<>();
    private final Map<BlockPos, Long> _lastVisitTicks = new HashMap<>();
    // 最近一次扫描到的走廊格子。
    private List<BlockPos> _floorTargets = new ArrayList<>();
    // 缓存的"看见的下界砖"位置 (每 FLOOR_SCAN_INTERVAL_TICKS 刷新一次, 控射线开销)。
    private Optional<BlockPos> _perceivedBrickCache = Optional.empty();

    private BlockPos _currentTarget;
    private boolean _corridorMode;
    private int _scanTicker;
    private int _failCounter;
    // 持久朝向 (外围随机游走不原地打转)。
    private double _heading = Double.NaN;
    // 本次到达目标是否走过 (用于日志)。
    private long _tickId = 0;
    // 上次诊断采样点 / 群系标签 / 上次打点时的最大半径 / 本次目标线索来源。
    private Vec3d _lastDiagPos;
    private String _lastBiomeLabel;
    private double _lastLoggedRadius = 0;
    private String _lastClue = "none";

    // =========================================================================
    // (2026-09-13 远征: 方向记忆跨任务实例) 用户明确"bot 在下界打转"。
    // 根因: _heading / _currentTarget 是实例字段, onStart 复位; 而上层
    // (TradeWithPiglinsTask / CollectBlazeRodsTask) 每 4-12 分钟被更上层销毁
    // 重建一次 (日志实锤: TradeWithPiglinsTask START 3 次, "-> null" 后立刻
    // 重新 SUB) → 每段路指向无关的随机方向, 正负抵消 → 净位移趋近 0。
    // 修法: 朝向 / 远征锚点 / 已达最大半径一律放 static, 按【存档:维度】隔离
    // (与走廊记忆 _visitedCellsByKey 同款 key), 实例重建不再失忆。
    // 注: 仅内存不落盘 —— 要修的是"单次会话内的任务重建"; 跨会话续走意义
    // 不大且锚点语义会变质, 属刻意收敛的范围。
    // =========================================================================
    private static final java.util.Map<String, Double> _headingByKey = new java.util.HashMap<>();
    private static final java.util.Map<String, BlockPos> _anchorByKey = new java.util.HashMap<>();
    private static final java.util.Map<String, Double> _maxRadiusByKey = new java.util.HashMap<>();
    // (2026-09-13 P2 遇障换向) 试探偏置 (相对锁定基向, 弧度) / 试探起点 max /
    // 最近一次刷新最远半径的 tick —— 三个都按 存档:维度 隔离。
    private static final java.util.Map<String, Double> _probeOffsetByKey = new java.util.HashMap<>();
    private static final java.util.Map<String, Double> _probeStartMaxByKey = new java.util.HashMap<>();
    // (2026-09-13 P2.1) 试探起点位置: 转正判据之二 —— 距起点净位移 > EXPLORE_STEP
    // (绕崖是圆弧, 半径不变但位移实打实, 旧判据 radius 永远量不到这种进展)。
    private static final java.util.Map<String, BlockPos> _probeStartPosByKey = new java.util.HashMap<>();
    // (2026-09-26 试探解冻) 停滞基线改存【墙钟毫秒】—— 旧版存实例内 _tickId,
    // 跨实例比对必然为负 (见 STALL_MS 注释)。毫秒基准跨实例连续。
    private static final java.util.Map<String, Long> _lastProgressMsByKey = new java.util.HashMap<>();
    // (20261112 A·试探周期化) 上次 probe 升级时刻: probe 的调用方从"两条失败路径"
    // 扩到"每 tick 周期调用"后, 必须自带升级节流, 否则停滞期每 tick 加一档,
    // ±150° 五档一秒扫完变成方向乱转。节流 = 两次升级至少隔 2×STALL_MS,
    // 保住"同侧加档 10s 一档"的原节奏。
    private static final java.util.Map<String, Long> _lastProbeEscalateMsByKey = new java.util.HashMap<>();

    // (2026-09-16 记忆中心直航) "亲眼看见过的要塞中心"直接导航的最大距离。
    // 下界 8:1, 1024 下界格 ≈ 8192 主世界格, 覆盖一次远征的合理范围 ——
    // 人类"记得那边有座堡垒"就该直接去, 不该被候选推断的 192 格限制挡掉。
    private static final double REMEMBERED_CENTER_MAX_DISTANCE = 1024;

    // =========================================================================
    // (2026-09-29 冻结自愈) 隐形冻结死循环: tickOutside 的"到达分支"
    // (:915 xzDistance<=ARRIVE) 每 tick 重选目标 + _progress.reset() ⇒ 6s 距离
    // 检查器永远新鲜、fail 计数永不增长、无任何日志。baritone 僵尸态
    // (customGoalProcess active 但 path[present=false] calc=false exec=null,
    // 零输入) 时整条链零日志冻结 —— 现场 20260929-134354 14:35:12→14:42:02
    // 冻结 408s, NAV-STUCK 全程 only 盲挥, "outside step failed" 全场 0 条。
    // 修法: tickOutside 顶部挂墙钟净位移看门狗 —— 停滞超阈值且 baritone 此刻
    // 不在规划 (规划期最长 ~4s 属正常等待, 不惩罚) ⇒ 走一次失败路径
    // (fail 计数 + maybeEscalateProbe 换向 + 重选目标), 打破死循环。
    // =========================================================================
    private static final long EXPLORE_STALL_GIVEUP_MS = 8000;
    private BlockPos _outsideStallLastPos = null;
    private long _outsideStallSinceMs = 0;

    // =========================================================================
    // (2026-09-13 记忆分支"查讫"机制) [Expedition] 日志实锤: clue 全是 memory,
    // radius 11~48 震荡、max 卡 49 —— bot 在记忆推断区之间往返, 走不到锁定
    // 方向分支。根因: KnowledgeMemory.getCandidateAreas 每次调用都【随机重新
    // 生成】候选点, 永远消耗不完; 且旧版把 bot 自建门的黑曜石记成
    // RUINED_PORTAL 地标, 候选全锚在门附近。
    // 修法: ① 只认 NETHER_FORTRESS 类型候选 ( RUINED_PORTAL 黑名单语义放
    // KnowledgeMemory 过载里); ② 记忆候选【到达扑空 / 寻路失败】即登记"查讫",
    // 32 格内的候选不再重复前往, 全部查讫 → 自然落空到锁定方向分支。
    // =========================================================================
    private static final java.util.Map<String, java.util.Set<BlockPos>> _checkedMemoryByKey = new java.util.HashMap<>();
    private static final double MEMORY_CHECK_RADIUS = 32;
    private static final int MAX_CHECKED_MEMORY = 64;
    // 当前目标是否为记忆推断目标 (到达/失败时登记"查讫"用)。
    private BlockPos _currentMemoryTarget;

    // =========================================================================
    // (2026-09-19 记忆目标失败分级处理) 实锤 20260918-231551: clue=memory-center
    // 的目标失败后 `_currentTarget.equals(_currentMemoryTarget)` 永假 (stepTowards
    // 恒返回 EXPLORE_STEP 距离的中间点, 永远不等于中心), "查讫"是死代码 → 同一个
    // 记忆中心每 6s 超时重选, 原地循环 36s+ 且探测/随机分支永远轮不到 (memory-center
    // 分支优先级最高)。修法: ① 走到中心 32 格内失败 → 查讫 (原语义); ② 远处反复
    // 失败 (≥3 次) → 真中心冷却 3 分钟 / 推断候选按 32 格登记淘汰 —— 让探测、
    // 更近的其他中心、随机分支接管 (更近优选不受冷却影响)。
    // =========================================================================
    private static final int MEMORY_TARGET_GIVEUP_FAILS = 3;
    private static final long CENTER_COOLDOWN_MS = 180_000;
    private static final java.util.Map<BlockPos, Long> _centerCooldownUntil = new java.util.HashMap<>();
    private int _memoryFailStreak = 0;
    private boolean _currentMemoryIsCenter = false;

    // =========================================================================
    // (20261112 B·记忆追逐停滞冷却) 20261112-204350 实锤: 记忆中心 (220,60,-649)
    // 20:57:24 已查讫, 但 getCandidateAreas 围绕它 ±48 的候选云每次【随机重生成】,
    // 存活者 (查讫圈外) 恒在岩浆海对岸 —— bot 沿岸乒乓 7 分钟 (radius 663~699
    // 往复, max=707 冻结), clue=memory 全程绑架选点。既有出口全部失效:
    // ① progress 失败通道 —— baritone 沿岸永远给得出可行路径, "outside step
    //    failed" 全场 0 条, _memoryFailStreak 永远凑不齐 3 次;
    // ② 查讫/冷却 —— 要求真走到目标 32 格内, 被岩浆拦住永远到不了。
    // 修法: 非 failure 通道 —— 同一"追逐锚中心"连续 MEMORY_CHASE_COOLDOWN_MS
    // 且期间无新最远半径、无一次查讫 → 该中心冷却 3 分钟 (复用 _centerCooldownUntil:
    // 中心分支跳过冷却中心 + 候选分支剔除冷却中心 64 格内候选), probe/锁定方向接管。
    // 追逐锚 = bot 最近的记忆中心 (候选逐点记账会被随机重生成洗掉, 按中心聚合)。
    // 计时重置: 线索切走 / 锚中心换人 / radius 破纪录 / markMemoryChecked(真进度)。
    // =========================================================================
    private static final long MEMORY_CHASE_COOLDOWN_MS = 90_000;
    private static final java.util.Map<String, BlockPos> _chaseAnchorByKey = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> _chaseSinceMsByKey = new java.util.HashMap<>();
    private static final java.util.Map<String, Double> _chaseStartMaxByKey = new java.util.HashMap<>();

    // =========================================================================
    // (2026-09-19 垫脚方块断供兜底) 20260918-231551 实锤: 背包没有可放置方块
    // (手里是 nether_brick 冶炼物品放不出), 对着需要垫柱的地形原地跳 36s+。
    // 外围步失败且可放置方块数为 0 → 先补一组地狱岩再继续探索 (灵魂沙算垫脚,
    // baritone 放置列表已放行, 见 AltoClef.java 同日注释)。
    // =========================================================================
    private static final int SCAFFOLD_RESTOCK_COUNT = 64;
    // (2026-09-19) 放行阈值: 攒到 32 块才放行回探索 —— 102254 场实锤阈值 >0
    // 时挖到 2 块就放行, baritone 马上垫掉 2 块又归零, 无限循环"补货→垫掉"。
    private static final int SCAFFOLD_RESTOCK_RELEASE_MIN = 32;
    private static final long SCAFFOLD_RESTOCK_COOLDOWN_TICKS = 600; // 补货失败 30s 后才可重试
    private static long _scaffoldRestockNextTick = 0;
    // (2026-09-19 sticky 补货) 本仓任务模型: 父任务 onTick 每 tick 重估子任务,
    // 一次性 return 的子任务会在下一 tick 因父任务返回 null 而被抢停
    // (20260919-095231 实锤: 补货链 49ms 即 "interrupted by null")。
    // 补货期间粘住同一实例, 背包有垫脚方块才释放。
    private Task _restockTask;
    // (2026-09-19) 中心被冷却后 30s 内跳过 memory-center 分支 —— 记忆中心常
    // 集中在同一象限 (knowledge.log 实锤 x644-788 一带 15+ 个), 逐个冷却每
    // 个要 18s+, 期间 probe/随机分支永远轮不到, bot 表现为"原地不动找不到目标"。
    private static final long MEMORY_CENTER_SUPPRESS_TICKS = 600;
    private static long _memoryCenterSuppressUntilTick = 0;

    // =========================================================================
    // (2026-09-05 通用化) 同一套人类式探索复用于不同下界目标: 音效线索前缀 /
    // 听觉范围 / 视觉线索方块 / 是否启用走廊地面模式。默认构造 = 找要塞
    // (行为与 2026-09-04 版本完全一致); forPiglins() = 找猪灵。
    // =========================================================================
    private final String _soundPrefix;
    private final double _hearingRange;
    private final Block[] _clueBlocks;
    private final boolean _corridorEnabled;

    /** 默认: 人类式找烈焰人要塞 (循声 + 可见下界砖 + 走廊地面巡逻)。 */
    public FortressExploreTask() {
        this(BLAZE_SOUND_PREFIX, BLAZE_HEARING_RANGE,
                new Block[]{Blocks.NETHER_BRICKS}, true);
    }

    /**
     * (2026-09-05 找猪灵真探索) 循猪灵音效 + 亲眼看见的堡垒黑石砖/绯红森林
     * 地形 (猪灵生成地), 无走廊模式 — 看见猪灵本体由上层 (TradeWithPiglinsTask)
     * 切交易任务。绯红菌岩是大面积地表方块, "看到就往那走"符合人类直觉。
     */
    public static FortressExploreTask forPiglins() {
        return new FortressExploreTask(PIGLIN_SOUND_PREFIX, PIGLIN_HEARING_RANGE,
                new Block[]{Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
                        Blocks.CRIMSON_NYLIUM},
                false);
    }

    private FortressExploreTask(String soundPrefix, double hearingRange,
                                Block[] clueBlocks, boolean corridorEnabled) {
        _soundPrefix = soundPrefix;
        _hearingRange = hearingRange;
        _clueBlocks = clueBlocks;
        _corridorEnabled = corridorEnabled;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // 跟踪线索方块, 供"亲眼看见"的视觉线索 (getPerceivedLocations 用)。
        for (Block clue : _clueBlocks) {
            mod.getBlockTracker().trackBlock(clue);
        }
        _progress.reset();
        _scanTicker = 0;
        _failCounter = 0;
        _currentTarget = null;
        // (2026-09-19) 记忆目标失败计数随实例复位 (冷却表 _centerCooldownUntil 是
        // static, 刻意跨实例保留 —— 任务被上层 4-12 分钟重建一次不失忆)。
        _currentMemoryTarget = null;
        _memoryFailStreak = 0;
        _currentMemoryIsCenter = false;
        _corridorMode = false;
        // (2026-09-09) 探深状态随任务重开复位 (走廊记忆 _visitedCells 仍保留)。
        _expandFailStreak = 0;
        _currentIsProbe = false;
        // (2026-09-13 远征) 实例重建后恢复朝向/锚点 —— 新实例不重新随机方向,
        // 接着上一段的方向继续走。key 全程可用 (猪灵模式也走外围)。
        _persistKey = fortressVisitedKey(mod);
        // (2026-09-26 试探解冻) 停滞基线若已过期 (被上层 4~12 分钟的重建期 / 战斗
        // 长时打断), 从恢复时刻重新起算 —— 打断期间不该算成"无新最远点"; 未过期
        // 则保留, 让停滞判据跨实例连续累计 (旧版基线存 tickId 正是试探失效的根因)。
        long stallNowMs = System.currentTimeMillis();
        Long stallLastMs = _lastProgressMsByKey.get(_persistKey);
        if (stallLastMs == null || stallNowMs - stallLastMs > STALL_MS) {
            _lastProgressMsByKey.put(_persistKey, stallNowMs);
        }
        Double keptHeading = _headingByKey.get(_persistKey);
        if (keptHeading != null) {
            _heading = keptHeading;
            Debug.logMessage("[Expedition] resumed heading " + fmtDeg(keptHeading)
                    + "° for " + _persistKey);
        } else {
            _heading = Double.NaN;
        }
        if (mod.getPlayer() != null) {
            _anchorByKey.computeIfAbsent(_persistKey, k -> mod.getPlayer().getBlockPos());
        }
        // 注意: _visitedCells / _lastVisitTicks 故意不清空 —— 上层在超时兜底
        // 与人类式探索间切换重跑本任务时, "走过的路"是人类式记忆, 不该失忆。
        // (2026-09-05 跨会话) 走廊模式再把上次会话走过的格子从文件里读回来
        // —— 重启任务/重开游戏也不重走老走廊。
        if (_corridorEnabled) {
            seedVisitedCellsFromDisk(_persistKey);
        }
        // 船降触发状态复位 (2026-09-18): 任务实例重建即重新武装。
        _noFloorSinceTick = 0;
        _boatScanNextTick = 0;
        _boatDescentCell = null;
        _boatDescentCooldownUntilTick = 0;
        _boatDescentFails = 0;
        _boatDescentDisabledLogged = false;
        _boatScanMissCount = 0;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        for (Block clue : _clueBlocks) {
            mod.getBlockTracker().stopTracking(clue);
        }
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        _tickId++;

        // 周期扫描: 要塞地面格子 + 可见下界砖。
        if (++_scanTicker >= FLOOR_SCAN_INTERVAL_TICKS) {
            _scanTicker = 0;
            _floorTargets = scanFortressFloorCells(mod);
            _perceivedBrickCache = findNearestPerceivedBrick(mod);
        }

        // 模式判定: 有走廊格子 / 看见了下界砖 → 上路。一旦进走廊模式,
        // 只在要塞地面轮换, 不回野外 (用户: 在城堡的路探索最像人、概率最大)。
        boolean corridorAvailable = !_floorTargets.isEmpty();
        if (_corridorMode && !corridorAvailable) {
            // 已在走廊模式: 扫描失败/没格子时保持上一个目标走, 别掉回野外。
            corridorAvailable = _currentTarget != null;
        }
        // (2026-09-28 修) 走廊模式进入前置必须是 _corridorEnabled —— forPiglins()
        // 明确传 false, 但本判定只看"看见线索方块"(CRIMSON_NYLIUM 站在绯红森林时
        // 最近 1.2 格恒命中) ⇒ 被顶进走廊模式, 而 scanFortressFloorCells 因
        // !_corridorEnabled 恒返回空 ⇒ pickCorridorTarget 恒 null ⇒ 从不
        // setGoalAndPath ⇒ 原地冻结 85s (现场 20260928-173600, 坐标小数 15 位不变)。
        // 且本判定只在 !_corridorMode 时生效, 进了就出不去。
        if (_corridorEnabled && !_corridorMode && (corridorAvailable || _perceivedBrickCache.isPresent())) {
            _corridorMode = corridorAvailable || nearSeenBrick(mod);
        }

        // (2026-09-13 远征取证) 周期 / 里程碑 / 群系切换三种触发, 拿"是否真在
        // 远征"的硬数据 (此前日志无坐标/群系遥测)。
        maybeLogExpedition(mod);

        if (_corridorMode) {
            return tickCorridor(mod);
        }
        return tickOutside(mod);
    }

    // ------------------------------------------------------------------ //
    // 走廊模式: 只踩要塞地面
    // ------------------------------------------------------------------ //

    private Task tickCorridor(AltoClef mod) {
        // (2026-09-28 修) 双保险: 非走廊任务 (找猪灵 forPiglins) 即使被顶进走廊
        // 模式也直接走野外分支, 不进"无目标可派发"的死锁 (现场 20260928-173600)。
        if (!_corridorEnabled) {
            return tickOutside(mod);
        }
        BlockPos player = mod.getPlayer().getBlockPos();

        // (2026-09-18 修) 有目标 = 没卡死, 复位船降卡死计时 —— 否则首次卡死
        // 后计时戳残留, 之后每次卡死都跳过 15s 等待直接触发。
        if (_currentTarget != null) {
            _noFloorSinceTick = 0;
        }

        if (_currentTarget == null) {
            _currentTarget = pickCorridorTarget(mod, player);
            if (_currentTarget == null) {
                setDebugState("On fortress but no walkable floor found yet");
                // (2026-09-18 船降) 卡死持续 ≥ 阈值后尝试"船免摔落"下降到下方走廊。
                return maybeBoatDescent(mod, player);
            }
            _progress.reset();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
            Debug.logMessage("[FortressExplore] corridor target " + _currentTarget.toShortString()
                    + " (floorTargets=" + _floorTargets.size() + ")");
            return null;
        }

        // 到达: 记账, 换下一个格子。
        // (2026-09-09) 探深目标不是走廊格: 到达不记 visited (避免垃圾格子落盘),
        // 等 ≤2s 后的周期扫描把更深走廊拉进 _floorTargets 再选目标。
        if (player.isWithinDistance(_currentTarget, CORRIDOR_ARRIVED_DISTANCE)) {
            if (_currentIsProbe) {
                _currentIsProbe = false;
            } else {
                markVisited(_currentTarget);
            }
            _currentTarget = null;
            return null;
        }

        // 卡住: 记账跳过, 换方向 (不重走同一条死路)。
        // (2026-09-09) 探深目标卡住只放弃该目标, 不记 visited。
        if (!_progress.check(mod)) {
            Debug.logMessage("[FortressExplore] corridor step failed, marking visited and re-picking.");
            if (_currentIsProbe) {
                _currentIsProbe = false;
            } else {
                markVisited(_currentTarget);
            }
            _currentTarget = null;
            _failCounter++;
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_currentTarget, 1));
        }
        setDebugState("Patrolling fortress corridors");
        return null;
    }

    /**
     * 走廊目标选择 (人类式): 先走"没走过的" (前沿, 优先远的——把走廊走穿),
     * 全走过后按"最久没走"轮换巡逻 —— 像人在城堡里反复巡查各条路等刷怪。
     */
    private BlockPos pickCorridorTarget(AltoClef mod, BlockPos player) {
        BlockPos frontier = null;
        double bestFrontierSq = -1;
        BlockPos oldest = null;
        long oldestTick = Long.MAX_VALUE;

        for (BlockPos cell : _floorTargets) {
            // (2026-09-06 修复"2:1 坐标压缩走飞", 用户实测"转几圈就离开要塞
            // 导航到非城堡区域") 归一 cell 只做【记忆 key】(visited 判重/巡逻
            // 时间戳/落盘), 导航目标必须是【真实 cell】—— 旧版把 (x/2,z/2) 的
            // 归一坐标直接当 GoalNear 目标, bot 朝压缩后的世界坐标走, 一路
            // 走出要塞 (日志实锤: 要塞在 z~565, 目标却是 (64,63,254))。
            BlockPos key = canonCell(cell);
            double sq = cell.getSquaredDistance(player);
            if (!_visitedCells.contains(key)) {
                if (sq > bestFrontierSq) {
                    bestFrontierSq = sq;
                    frontier = cell;
                }
            } else {
                Long t = _lastVisitTicks.get(key);
                long tt = t == null ? 0 : t;
                if (tt < oldestTick) {
                    oldestTick = tt;
                    oldest = cell;
                }
            }
        }
        if (frontier != null) {
            // 有前沿 = 扫描半径内还有没走过的走廊, 探深失败计数清零。
            _expandFailStreak = 0;
            _currentIsProbe = false;
            return frontier;
        }
        // (2026-09-09 探深后再巡逻) 前沿走空 ≠ 城堡走完 — 48 格扫描半径外的
        // 深处走廊还看不见。先朝"最远已走格"再往外探一段, 把新走廊拉进扫描
        // 视野; 连续 EXPAND_MAX_FAIL 次都没探出新格子才承认走完, 转轮换巡逻。
        if (_expandFailStreak < EXPAND_MAX_FAIL) {
            BlockPos farthest = null;
            double bestSq = -1;
            for (BlockPos cell : _floorTargets) {
                double sq = cell.getSquaredDistance(player);
                if (sq > bestSq) {
                    bestSq = sq;
                    farthest = cell;
                }
            }
            if (farthest != null) {
                double dx = farthest.getX() - player.getX();
                double dz = farthest.getZ() - player.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                BlockPos probe;
                if (len < 1.0) {
                    probe = farthest;
                } else {
                    probe = new BlockPos(
                            farthest.getX() + (int) Math.round(dx / len * EXPLORE_EXTEND_BLOCKS),
                            farthest.getY(),
                            farthest.getZ() + (int) Math.round(dz / len * EXPLORE_EXTEND_BLOCKS));
                }
                _expandFailStreak++;
                _currentIsProbe = true;
                Debug.logMessage("[FortressExplore] corridor frontier empty within scan radius, probing deeper to "
                        + probe.toShortString() + " (attempt " + _expandFailStreak + "/" + EXPAND_MAX_FAIL + ")");
                return probe;
            }
        }
        if (_expandFailStreak >= EXPAND_MAX_FAIL) {
            Debug.logMessage("[FortressExplore] no new corridors after " + EXPAND_MAX_FAIL
                    + " deeper probes, switching to oldest-visit patrol.");
            _expandFailStreak = 0;
        }
        _currentIsProbe = false;
        return oldest;
    }

    private void markVisited(BlockPos cell) {
        BlockPos canonical = canonCell(cell);
        _visitedCells.add(canonical);
        _lastVisitTicks.put(canonical, _tickId);
        persistVisitedCell(_persistKey, canonical);
    }

    // 当前存档:维度 的持久化 key (onStart 缓存; 维度内本任务不换世界)。
    private String _persistKey;

    /**
     * (2026-09-05 走廊格子归一) 归一到世界原点锚定的 2 格网格: 旧版扫描网格
     * 以玩家坐标为原点, 玩家移动后同一块地板会以相邻 BlockPos 重新出现在
     * "没走过"集合 → 在已走过的走廊来回打转。floorDiv 后与玩家位置无关。
     */
    private static BlockPos canonCell(BlockPos p) {
        return new BlockPos(Math.floorDiv(p.getX(), 2), p.getY(), Math.floorDiv(p.getZ(), 2));
    }

    // ------------------------------------------------------------------ //
    // 走廊记忆跨会话持久化 (altoclef_fortress_visited.log, 每行 "存档:维度 x y z")
    // ------------------------------------------------------------------ //

    private static String fortressVisitedKey(AltoClef mod) {
        // (2026-09-17 跨世界污染) 显示名 → 存档目录名, 统一走 WorldIdentity。
        // 旧 key 的存量走廊记忆自然作废。
        return adris.altoclef.util.helpers.WorldIdentity.key(mod);
    }

    /**
     * (2026-09-17 世界边界丢弃) 换存档时清空本任务的全部 static 状态:
     * 走廊格子 / 方位 / 远征锚点 / 试探进度 / 记忆查讫。
     * 这些里 `_anchorByKey` / `_probeStartPosByKey` 存的是【坐标】, 不清就是
     * "上个世界的锚点被当本世界的导航目标"。
     * 刻意不动 `_visitedCellsLoaded`: 保持 true = 不重载磁盘(丢弃语义)。
     */
    public static void onWorldBoundary() {
        _visitedCellsByKey.clear();
        _headingByKey.clear();
        _anchorByKey.clear();
        _maxRadiusByKey.clear();
        _probeOffsetByKey.clear();
        _probeStartMaxByKey.clear();
        _probeStartPosByKey.clear();
        _lastProgressMsByKey.clear();
        _checkedMemoryByKey.clear();
        // (20261112) A·probe 升级节流 + B·记忆追逐停滞: 与上方同批 static 同生命周期。
        _lastProbeEscalateMsByKey.clear();
        _chaseAnchorByKey.clear();
        _chaseSinceMsByKey.clear();
        _chaseStartMaxByKey.clear();
    }

    private static java.nio.file.Path fortressVisitedFile() {
        // (2026-09-10) 迁入 logs/altoclef/memory/, 旧根目录文件自动迁移。
        return adris.altoclef.Debug.memoryFile(FORTRESS_VISITED_FILE, "fortress_visited.log");
    }

    private static void ensureVisitedCellsLoaded() {
        if (_visitedCellsLoaded) return;
        _visitedCellsLoaded = true;
        try {
            java.nio.file.Path f = fortressVisitedFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                // (2026-09-27 空格 key 修复) 统一走 WorldIdentity.decodeLine —— key 取存档
                // 目录名 (可含空格), 旧空格行右锚定兼容; 旧 `split(" ") + length != 4` 会把
                // 含空格 key 的行整行静默丢弃。
                String[] parts = adris.altoclef.util.helpers.WorldIdentity.decodeLine(line, 3);
                if (parts == null) continue;
                try {
                    BlockPos p = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    java.util.LinkedHashSet<BlockPos> set = _visitedCellsByKey.computeIfAbsent(parts[0], k -> new java.util.LinkedHashSet<>());
                    if (set.size() < MAX_PERSISTED_CELLS) set.add(p);
                } catch (NumberFormatException ignored) {
                }
            }
            Debug.logMessage("[FortressExplore] loaded "
                    + _visitedCellsByKey.values().stream().mapToInt(Set::size).sum()
                    + " visited corridor cells from " + FORTRESS_VISITED_FILE);
        } catch (Exception e) {
            Debug.logMessage("[FortressExplore] failed to load " + FORTRESS_VISITED_FILE + ": " + e.getMessage());
        }
    }

    private static void appendVisitedCellLine(String key, BlockPos pos) {
        try {
            java.nio.file.Files.write(fortressVisitedFile(),
                    adris.altoclef.util.helpers.WorldIdentity
                            .encodeLine(key, pos.getX(), pos.getY(), pos.getZ())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Debug.logMessage("[FortressExplore] failed to append visited cell: " + e.getMessage());
        }
    }

    /** onStart 时把上次会话走过的格子读回内存 (只针对当前 存档:维度)。 */
    private void seedVisitedCellsFromDisk(String key) {
        ensureVisitedCellsLoaded();
        java.util.Set<BlockPos> persisted = _visitedCellsByKey.get(key);
        if (persisted == null || persisted.isEmpty()) return;
        _visitedCells.addAll(persisted);
        Debug.logMessage("[FortressExplore] seeded " + persisted.size()
                + " previously visited corridor cells (key=" + key + ")");
    }

    /** 到达/跳过一个走廊格子时持久化 (追加一行, 与矿洞 altoclef_explored.log 同款)。 */
    private void persistVisitedCell(String key, BlockPos canonical) {
        if (key == null || canonical == null) return;
        ensureVisitedCellsLoaded();
        java.util.LinkedHashSet<BlockPos> set = _visitedCellsByKey.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>());
        if (set.contains(canonical)) return; // 已记过, 不重复追加
        if (set.size() >= MAX_PERSISTED_CELLS) return;
        set.add(canonical);
        appendVisitedCellLine(key, canonical);
    }

    /**
     * 扫描玩家周围已加载区块里的"要塞可行走地面"格子:
     * 地面是下界砖 (砖/楼梯/台阶), 格子本身与上两格为空气 —— 即走廊/桥面/
     * 房间里人类真正会走的地方。
     * (2026-09-05 通用化) 非走廊模式 (找猪灵等) 直接跳过这个 48x48x20 的
     * 周期扫描 — 猪灵不绑定走廊地面, 省掉无谓开销。
     */
    private List<BlockPos> scanFortressFloorCells(AltoClef mod) {
        List<BlockPos> out = new ArrayList<>();
        if (!_corridorEnabled) return out;
        BlockPos player = mod.getPlayer().getBlockPos();
        int yMin = player.getY() - FLOOR_SCAN_Y_RANGE;
        int yMax = player.getY() + FLOOR_SCAN_Y_RANGE;
        for (int dx = -FLOOR_SCAN_RADIUS; dx <= FLOOR_SCAN_RADIUS; dx += FLOOR_SCAN_STEP) {
            for (int dz = -FLOOR_SCAN_RADIUS; dz <= FLOOR_SCAN_RADIUS; dz += FLOOR_SCAN_STEP) {
                int x = player.getX() + dx;
                int z = player.getZ() + dz;
                for (int y = yMin; y <= yMax; ++y) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
                    if (isFortressFloorCell(mod, pos)) {
                        // (2026-09-06) 存【真实坐标】: 记忆 key 由 pickCorridorTarget/
                        // markVisited 现场归一。旧版在这里就存归一坐标, 导航目标跟着
                        // 被压缩成半比例世界坐标, bot 直接走出要塞 (2:1 压缩 bug)。
                        out.add(pos);
                    }
                }
            }
        }
        return out;
    }

    private boolean isFortressFloorCell(AltoClef mod, BlockPos pos) {
        Block ground = mod.getWorld().getBlockState(pos.down()).getBlock();
        return (ground == Blocks.NETHER_BRICKS
                || ground == Blocks.NETHER_BRICK_STAIRS
                || ground == Blocks.NETHER_BRICK_SLAB)
                && mod.getWorld().getBlockState(pos).isAir()
                && mod.getWorld().getBlockState(pos.up()).isAir();
    }

    // ------------------------------------------------------------------ //
    // 船降触发 (2026-09-18): 走廊模式"下不去"兜底
    // ------------------------------------------------------------------ //

    /** 走廊模式找不到可走格子的起始 tick (0 = 未在卡死状态)。 */
    private long _noFloorSinceTick = 0;
    /** 下次落点深扫描的 tick (每 5s 一次)。 */
    private long _boatScanNextTick = 0;
    /** 非 null = 船降子任务已启动 (进行中)。 */
    private BlockPos _boatDescentCell = null;
    /** 船降结算后的冷却截止 tick。 */
    private long _boatDescentCooldownUntilTick = 0;
    /** 船降连续失败计数 (≥ Settings 上限 → 本实例停用)。 */
    private int _boatDescentFails = 0;
    private boolean _boatDescentDisabledLogged = false;
    /** 落点扫描无果计数 (诊断日志用)。 */
    private int _boatScanMissCount = 0;

    /**
     * 卡死判定: _currentTarget == null 持续 ≥ fortressBoatDescentStuckSeconds
     * (用户拍板 15s)。此时 bot 在走廊模式完全 idle (无寻路无按键), 无需再查
     * "是否在忙正当动作"。触发后深扫描下方要塞地面找落点, 几何可行才启动
     * FortressBoatDescentTask; 结算 (按玩家 Y)/冷却/失败计数都在这里收口。
     */
    private Task maybeBoatDescent(AltoClef mod, BlockPos player) {
        Settings s = mod.getModSettings();
        if (!s.isFortressBoatDescentEnabled()) return null;
        if (_boatDescentFails >= s.getFortressBoatDescentMaxFailures()) {
            if (!_boatDescentDisabledLogged) {
                _boatDescentDisabledLogged = true;
                Debug.logToFile("altoclef_diag.log", "[BOAT-DROP] 连续失败 " + _boatDescentFails
                        + " 次, 本探索任务实例内停用船降");
            }
            return null;
        }
        if (_tickId < _boatDescentCooldownUntilTick) return null;

        // 子任务进行中: 任务栈里还有它 → 继续喂同一落点 (isEqual 幂等去重);
        // 栈里没了 → 结算: 玩家是否真的降到了落点附近。
        if (_boatDescentCell != null) {
            boolean stillActive = false;
            TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
            if (chain != null) {
                for (Task t : chain.getTasks()) {
                    if (t instanceof FortressBoatDescentTask) {
                        stillActive = true;
                        break;
                    }
                }
            }
            if (stillActive) {
                return new FortressBoatDescentTask(_boatDescentCell);
            }
            boolean success = player.getY() <= _boatDescentCell.getY() + 2;
            if (success) {
                Debug.logToFile("altoclef_diag.log", "[BOAT-DROP] 船降成功, bot 现在位于 "
                        + player.toShortString());
                _boatDescentCooldownUntilTick = _tickId + 15 * 20;
            } else {
                _boatDescentFails++;
                Debug.logToFile("altoclef_diag.log", "[BOAT-DROP] 船降失败 (" + _boatDescentFails
                        + "/" + s.getFortressBoatDescentMaxFailures() + "), 冷却 60s 后再试");
                _boatDescentCooldownUntilTick = _tickId + 60 * 20;
            }
            _boatDescentCell = null;
            _currentTarget = null;
            return null;
        }

        // 卡死计时 (首次进入该分支置起点)。
        if (_noFloorSinceTick == 0) {
            _noFloorSinceTick = _tickId;
            return null;
        }
        if (_tickId - _noFloorSinceTick < (long) s.getFortressBoatDescentStuckSeconds() * 20) {
            return null;
        }
        // 每 5s 扫一次落点 (深扫描有开销, 节流)。
        if (_tickId < _boatScanNextTick) return null;
        _boatScanNextTick = _tickId + 100;
        BlockPos landing = findBoatDescentLanding(mod, player);
        if (landing == null) {
            // (2026-09-18 补) 扫描无果此前零日志 —— 无法区分"没触发"和"触发了
            // 但找不到落点"。首次 + 之后每 30s 一条。
            _boatScanMissCount++;
            if (_boatScanMissCount == 1 || _boatScanMissCount % 6 == 0) {
                Debug.logToFile("altoclef_diag.log", "[BOAT-DROP] 卡死已过阈值, 落点扫描第 " + _boatScanMissCount
                        + " 次无果 (落差≥" + s.getFortressBoatDescentMinDropBlocks()
                        + " 且水平 2~5 格内无几何可行的走廊格)");
            }
            return null;
        }
        Debug.logToFile("altoclef_diag.log", "[BOAT-DROP] 触发船降: 玩家 y=" + player.getY()
                + ", 落点 " + landing.toShortString()
                + " (落差 " + (player.getY() - landing.getY()) + ")");
        _boatDescentCell = landing;
        return new FortressBoatDescentTask(landing);
    }

    /**
     * 深扫描下方要塞地面走廊格: 落差 ≥ minDrop、水平距 2~5 格 (船从边缘滑落
     * 是直落的, 落点列离得太远船到不了; 贴脚下列放船会和 bot 碰撞), 再过
     * FortressBoatDescentTask.isLandingViable 的几何校验。浅 + 近优先。
     */
    private BlockPos findBoatDescentLanding(AltoClef mod, BlockPos player) {
        final int RADIUS = 8;
        final int MAX_DEPTH = 48;
        int minDrop = mod.getModSettings().getFortressBoatDescentMinDropBlocks();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < 2 || dist > 5) continue;
                for (int y = player.getY() - minDrop; y >= player.getY() - MAX_DEPTH; y--) {
                    BlockPos pos = new BlockPos(player.getX() + dx, y, player.getZ() + dz);
                    if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
                    if (!isFortressFloorCell(mod, pos)) continue;
                    if (!FortressBoatDescentTask.isLandingViable(mod, player, pos)) continue;
                    double score = (player.getY() - y) + dist;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ //
    // 外围模式: 带方向感的游走 (音效 > 看见的下界砖 > 结构推断 > 随机)
    // ------------------------------------------------------------------ //

    /** 水平面 (xz) 欧氏距离。外围目标用 GoalXZWithin, 到达判定必须同为 2D。 */
    private static double xzDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * (2026-09-13 导航目标循环) 水平半径目标: 站到目标点水平 radius 格内任意
     * 高度即达成。baritone API 没有"xz within"目标类型 —— GoalXZ 是精确柱语义,
     * 目标柱表面在高地/悬崖顶 (砖块线索 y=96, bot y=64) 时 baritone 只能给出
     * cost>3 的 sneak-bridge 畸形路径, 执行两步即失效, 4s 一轮全量重规划
     * (700 万节点, 规划期不按键) → "走两步停一下"死循环。
     * 远征语义只需要水平净位移, 不需要站上某根柱子, 半径语义正好。
     */
    private class GoalXZWithin implements Goal {
        private final double _cx, _cz, _radiusSq;

        GoalXZWithin(BlockPos target, double radius) {
            _cx = target.getX() + 0.5;
            _cz = target.getZ() + 0.5;
            _radiusSq = radius * radius;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            double dx = x + 0.5 - _cx;
            double dz = z + 0.5 - _cz;
            return dx * dx + dz * dz <= _radiusSq;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double dx = x + 0.5 - _cx;
            double dz = z + 0.5 - _cz;
            // 估计剩余代价 = 水平距离 - 半径, 已在圈内则为 0。
            return Math.max(0, Math.sqrt(dx * dx + dz * dz) - Math.sqrt(_radiusSq));
        }
    }

    private Task tickOutside(AltoClef mod) {
        Vec3d pos = mod.getPlayer().getPos();

        // (2026-09-29 冻结自愈) 墙钟净位移看门狗: 停滞超阈值 + baritone 此刻
        // 不在规划 (排除 ~4s 正常规划等待) ⇒ 强制走一次失败路径换向/换目标。
        // 详细机制见字段区 EXPLORE_STALL_GIVEUP_MS 注释 (20260929-134354 冻结 408s)。
        {
            BlockPos nowPos = mod.getPlayer().getBlockPos();
            if (_outsideStallLastPos == null
                    || _outsideStallLastPos.getSquaredDistance(nowPos) > 1.0) {
                // 有位移 (或首轮): 刷新停滞基线
                _outsideStallLastPos = nowPos;
                _outsideStallSinceMs = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - _outsideStallSinceMs > EXPLORE_STALL_GIVEUP_MS
                    && !mod.getClientBaritone().getPathingBehavior().getInProgress().isPresent()) {
                Debug.logMessage("[FortressExplore] frozen " + EXPLORE_STALL_GIVEUP_MS / 1000
                        + "s+ with no movement and no path calc -> forced re-target (pos="
                        + nowPos.toShortString() + ")");
                _outsideStallSinceMs = System.currentTimeMillis(); // 本轮已处置, 重新计时
                _failCounter++;
                maybeEscalateProbe(mod);
                _currentTarget = null;
                _progress.reset();
                return null;
            }
        }

        // (2026-09-19 sticky 补货) 每 tick 重派同一补货实例, 完成才释放 ——
        // 一次性 return 会在下一 tick 被 null 抢停 (见字段注释)。
        // 放行阈值 32 块: >0 就放行会被 baritone 立刻垫掉又归零 (102254 实锤)。
        if (_restockTask != null) {
            int scaffoldCount = countPlaceableScaffoldBlocks(mod);
            if (scaffoldCount >= SCAFFOLD_RESTOCK_RELEASE_MIN || _restockTask.isFinished(mod)) {
                Debug.logMessage("[FortressExplore] scaffolding restock finished (" + scaffoldCount + " blocks).");
                _scaffoldRestockNextTick = 0; // 垫完归零后立刻可再补, 不吃 30s 冷却
                _restockTask = null;
            } else {
                setDebugState("Out of scaffolding blocks, collecting netherrack");
                return _restockTask;
            }
        }

        // (2026-09-13 浮空目标) 到达判定改 2D: GoalXZWithin 只约束水平距离, 站到
        // 目标点水平 R 格内任意高度都算到。旧 3D isWithinDistance(3) 会因 y 差
        // >3 判"没到", 卡到 progress 超时才换目标, 白走。
        if (_currentTarget == null || xzDistance(mod.getPlayer().getBlockPos(), _currentTarget) <= EXPEDITION_ARRIVE_XZ) {
            // (2026-09-13) 到达即"查讫": 记忆推断目标扑空一次就登记, 不再去
            // 同一片记忆区反复撞 (getCandidateAreas 每次随机重生成候选点)。
            // (2026-09-19 修) 旧判据 `_currentTarget.equals(_currentMemoryTarget)`
            // 恒假 (stepTowards 恒返回中间点), 查讫是死代码 —— 改按"到过中心
            // 32 格内"判定; 中间点到达而中心还远时照常继续逐段推进。
            if (_currentMemoryTarget != null
                    && _currentMemoryTarget.isWithinDistance(mod.getPlayer().getBlockPos(), MEMORY_CHECK_RADIUS)) {
                markMemoryChecked(_currentMemoryTarget);
                _currentMemoryTarget = null;
                _memoryFailStreak = 0;
            }
            _currentTarget = pickOutsideTarget(mod, pos);
            if (_currentTarget == null) {
                if (++_failCounter > MAX_FAIL_COUNT) {
                    Debug.logMessage("[FortressExplore] outside explore exhausted, handing back to parent.");
                    return null;
                }
                setDebugState("Wandering the nether (no clues yet)");
                return null;
            }
            _progress.reset();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZWithin(_currentTarget, EXPEDITION_GOAL_RADIUS));
            return null;
        }

        if (!_progress.check(mod)) {
            // (2026-09-14 规划豁免) 难地形 (岩浆悬崖/岩浆海) 一次全量规划 ~4s
            // (baritone primaryTimeoutMS) 且规划期 bot 0 位移, 6s 距离超时会在
            // 规划算完前就判失败 → 反复打断重启, moved=0 死循环 (20260914-183710
            // 实锤: 73s 内 12+ 次重选方向, 每轮 10M movements considered 白算,
            // 仅 19:10:02 一轮赶上窗口算完, 走了 1.5 格)。
            // 寻路器还在算 (getInProgress 非空) 时不算失败, 给规划留完整窗口;
            // 本轮算完 (超时返回部分路径/无路径) 仍不动才判失败换目标。
            if (mod.getClientBaritone().getPathingBehavior().getInProgress().isPresent()) {
                setDebugState("Waiting for pathfinder (planning in progress)");
                return null;
            }
            _failCounter++;
            // (2026-09-19 记忆目标失败分级) 旧判据 `_currentTarget.equals(
            // _currentMemoryTarget)` 恒假 (stepTowards 恒返回中间点), 查讫是死
            // 代码 → 同一记忆中心 6s 一轮无限重选 (20260918-231551 实锤 36s+)。
            if (_currentMemoryTarget != null) {
                if (_currentMemoryTarget.isWithinDistance(mod.getPlayer().getPos(), MEMORY_CHECK_RADIUS)) {
                    // 真的走到中心附近才"查讫" (到达扑空语义)。
                    markMemoryChecked(_currentMemoryTarget);
                    _memoryFailStreak = 0;
                } else if (++_memoryFailStreak >= MEMORY_TARGET_GIVEUP_FAILS) {
                    if (_currentMemoryIsCenter) {
                        // 远处中心反复走不到 → 冷却 3 分钟, 让探测/更近中心/随机分支接管。
                        _centerCooldownUntil.put(_currentMemoryTarget.toImmutable(),
                                System.currentTimeMillis() + CENTER_COOLDOWN_MS);
                        // 同时跳过 memory-center 分支 30s, 让试探/随机方向真正接管
                        // (否则剩余中心仍锚在同一象限, 换向等于没换)。
                        _memoryCenterSuppressUntilTick = _tickId + MEMORY_CENTER_SUPPRESS_TICKS;
                        Debug.logMessage("[Expedition] memory center " + _currentMemoryTarget.toShortString()
                                + " unreachable after " + MEMORY_TARGET_GIVEUP_FAILS + " fails, cooling down 3min");
                    } else {
                        // 推断候选按 32 格登记, 淘汰整片候选区。
                        markMemoryChecked(_currentMemoryTarget);
                    }
                    _memoryFailStreak = 0;
                }
                _currentMemoryTarget = null;
                _currentMemoryIsCenter = false;
            }
            // (2026-09-19 垫脚断供兜底) 可放置方块数为 0 → 先补一组地狱岩再继续
            // 探索 (baritone 没有可放置方块时会对需要垫柱的地形原地无限跳)。
            // 补货实例粘性重派 (sticky), 见 _restockTask 字段注释。
            if (countPlaceableScaffoldBlocks(mod) == 0 && _tickId >= _scaffoldRestockNextTick) {
                _scaffoldRestockNextTick = _tickId + SCAFFOLD_RESTOCK_COOLDOWN_TICKS;
                // (2026-09-19 21:20 统一口径) 原来固定补地狱岩; 现按维度/存量定点:
                // 下界=地狱岩→黑石→玄武岩, 主世界=圆石→深板岩圆石 (ScaffoldBlocks)。
                Item restockItem = ScaffoldBlocks.preferredRestockItem(mod);
                _restockTask = TaskCatalogue.getItemTask(restockItem, SCAFFOLD_RESTOCK_COUNT);
                _progress.reset();
                _currentTarget = null;
                Debug.logMessage("[FortressExplore] no placeable blocks in inventory, restocking "
                        + SCAFFOLD_RESTOCK_COUNT + " " + restockItem.toString() + " first");
                setDebugState("Out of scaffolding blocks, collecting " + restockItem.toString());
                return _restockTask;
            }
            _currentTarget = null;
            // (2026-09-13 P2 遇障换向) 停滞够久才升级试探, 普通寻路失败不换向。
            maybeEscalateProbe(mod);
            Debug.logMessage("[FortressExplore] outside step failed, picking a new direction.");
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZWithin(_currentTarget, EXPEDITION_GOAL_RADIUS));
        }
        setDebugState("Exploring the nether (human-like)");
        return null;
    }

    /**
     * 外围目标: 循声 (烈焰人音效) > 看见的下界砖 > 记忆推断 > 持久朝向随机。
     */
    private BlockPos pickOutsideTarget(AltoClef mod, Vec3d pos) {
        // 1. 听觉: 目标生物音效 (烈焰人/猪灵), 人类能听到并大致辨向。
        Optional<Vec3d> heard = mod.getSoundTracker()
                .getNearestRecentSoundPos(_soundPrefix, pos, _hearingRange);
        if (heard.isPresent()) {
            _lastClue = "sound";
            setDebugState("Heading towards heard sounds (" + _soundPrefix + ")");
            return stepTowards(pos, heard.get().x, heard.get().z, heard.get().y);
        }

        // 2. 视觉: 亲眼看见的下界砖 (感知过滤后的缓存)。
        if (_perceivedBrickCache.isPresent()) {
            _lastClue = "brick";
            BlockPos brick = _perceivedBrickCache.get();
            setDebugState("Heading towards seen fortress bricks");
            return stepTowards(pos, brick.getX(), brick.getZ(), brick.getY());
        }

        // 3. 结构推断: 记忆地标生成的候选区 (明确是 inference 不是 observation,
        //    当方向感用)。只认 NETHER_FORTRESS —— RUINED_PORTAL 的 landmark 是
        //    黑曜石, 会把 bot 自建的下界门记成地标, 候选全锚在门附近导致绕门
        //    打转 (20260913-202230 [Expedition] clue=memory 实锤)。
        //    "查讫"过的区域 (32 格内已扑空过) 直接跳过, 全查讫 → 落到锁定方向。
        //    (2026-09-16 记忆中心直航) 先取"亲眼见过的要塞中心"直接导航 ——
        //    挖金/交易时路过看到的堡垒记在 KnowledgeMemory (跨会话落盘),
        //    切回找堡垒任务要能想起来, 不再只靠中心周围随机撒点 + 192 格
        //    距离过滤。查讫机制同样适用: 中心扑空过就跳过, 不死磕假地标。
        _currentMemoryTarget = null;
        _currentMemoryIsCenter = false;
        // (2026-09-19) 中心刚被冷却 → 30s 内跳过 memory-center 分支, 让试探/
        // 随机方向接管 (剩余中心仍锚在同一象限时, 换向等于没换)。
        boolean suppressMemoryCenter = _tickId < _memoryCenterSuppressUntilTick;
        List<BlockPos> centers = suppressMemoryCenter
                ? new ArrayList<>()
                // (2026-10-05 双源线索召回) 自动记忆中心 ∪ NamedLandmarks 带类型的
                // 共享地标。假坐标最坏 = 走一趟, 下方 isCheckedMemory 查讫 + 冷却兜底。
                : NamedLandmarks.recallClueCenters(mod,
                        KnowledgeMemory.StructureType.NETHER_FORTRESS, pos, REMEMBERED_CENTER_MAX_DISTANCE);
        centers.removeIf(this::isCheckedMemory);
        // (2026-09-19) 冷却中的中心 (远处反复走不到) 跳过 —— 最近优选在剩余
        // 中心里照常生效, 更近的新堡垒不会被冷却的老中心挡住。
        centers.removeIf(FortressExploreTask::isCenterOnCooldown);
        if (!centers.isEmpty()) {
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (BlockPos c : centers) {
                double sq = c.getSquaredDistance(pos);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = c;
                }
            }
            if (best != null) {
                _lastClue = "memory-center";
                _currentMemoryTarget = best;
                _currentMemoryIsCenter = true;
                setDebugState("Heading towards remembered fortress location");
                return stepTowards(pos, best.getX(), best.getZ(), best.getY());
            }
        }
        List<BlockPos> candidates = mod.getKnowledgeMemory()
                .getCandidateAreas(pos, KnowledgeMemory.StructureType.NETHER_FORTRESS);
        candidates.removeIf(this::isCheckedMemory);
        // (2026-09-19) 冷却中心周边 64 格的候选一并跳过 (候选锚在中心 ±48 生成)。
        for (BlockPos cooled : _centerCooldownUntil.keySet()) {
            if (isCenterOnCooldown(cooled)) {
                candidates.removeIf(cand -> cand.isWithinDistance(cooled, 64));
            }
        }
        if (!candidates.isEmpty()) {
            _lastClue = "memory";
            BlockPos best = null;
            double bestSq = Double.POSITIVE_INFINITY;
            for (BlockPos c : candidates) {
                double sq = c.getSquaredDistance(pos);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = c;
                }
            }
            if (best != null) {
                _currentMemoryTarget = best;
                setDebugState("Heading towards remembered fortress area (inference)");
                return stepTowards(pos, best.getX(), best.getZ(), best.getY());
            }
        }

        // 4. 随机: 沿持久锁定方位角 (远征) ±5° 微调, 不原地打转。
        //    (P2.1) 试探偏置 ≥60° 时步长 ×2: 绕崖要走长步, 48 格沿崖根走不出名堂。
        _lastClue = "heading";
        double angle = nextRandomHeading();
        double step = EXPLORE_STEP;
        Double probeDeg = _probeOffsetByKey.get(expeditionKey());
        if (probeDeg != null && Math.abs(Math.toDegrees(probeDeg)) >= PROBE_STEP_DEG) {
            step = EXPLORE_STEP * 2;
        }
        int candX = (int) Math.floor(pos.x + Math.cos(angle) * step);
        int candZ = (int) Math.floor(pos.z + Math.sin(angle) * step);
        // y 只作日志参考 (导航用 GoalXZ 不约束高度, 见 stepTowards 注释)。
        return new BlockPos(candX, (int) Math.floor(pos.y), candZ);
    }

    /** 从当前位置朝 (tx, tz) 走一步 EXPLORE_STEP。y 只作日志参考 —— 导航用 GoalXZ, 不约束高度。 */
    private BlockPos stepTowards(Vec3d from, double tx, double tz, double targetY) {
        double toTarget = Math.atan2(tz - from.z, tx - from.x);
        double spread = Math.toRadians(BIAS_ANGLE_DEG);
        double angle = toTarget + (Math.random() * 2 - 1) * spread;
        // 注意: 这里只更新实例级 _heading (日志用), 故意【不】写回 _headingByKey
        // —— 声音/下界砖等瞬态线索不该永久扭转远征方位角, 线索消失后下一轮
        // nextRandomHeading 会回到锁定的远征方向 (否则瞬态线索反复把方向来回
        // 掰, 又变成小区域打转)。
        _heading = angle;
        int candX = (int) Math.floor(from.x + Math.cos(angle) * EXPLORE_STEP);
        int candZ = (int) Math.floor(from.z + Math.sin(angle) * EXPLORE_STEP);
        // (2026-09-13 浮空目标) 旧版 y 复制出发脚高: 48 格外地形起伏 ±20 格时
        // 目标悬空, GoalNear(三维) 逼 baritone 搭柱子上天 (用户"空旷环境总搭到
        // 空中去")。改 GoalXZ 后 y 不参与寻路, 这里只留出发脚高供日志观察。
        return new BlockPos(candX, (int) Math.floor(targetY), candZ);
    }

    /**
     * (2026-09-13 远征) 锁定方向: 首次随机, 之后只在 ±RANDOM_JITTER_DEG 内微调,
     * 并写回 static —— 跨任务实例存活。旧版每次实例重建重新随机 = 小区域打转。
     * (2026-09-13 P2) 有效方向 = 锁定基向 + 遇障试探偏置 (见 maybeEscalateProbe)。
     */
    private double nextRandomHeading() {
        Double stored = _headingByKey.get(expeditionKey());
        if (stored == null || Double.isNaN(stored)) {
            stored = Math.random() * 2 * Math.PI;
        } else {
            stored += (Math.random() * 2 - 1) * Math.toRadians(RANDOM_JITTER_DEG);
        }
        _headingByKey.put(expeditionKey(), stored);
        Double probe = _probeOffsetByKey.get(expeditionKey());
        double effective = stored + (probe == null ? 0.0 : probe);
        _heading = effective;
        return effective;
    }

    /**
     * (2026-09-13 P2 遇障换向) 外围步失败时调用: 停滞够久 (无新最远点) → 升级
     * 试探偏置, 绕障试探。探测方向走到更远 (radius 破纪录) 或 距试探起点净位移
     * 超过一个步程 (绕崖走通) → 转正为新锁定方向 (转正逻辑在 maybeLogExpedition);
     * 否则【同侧加档】+60° → +85° → +110° → +135° → +150° 沿崖根走到底,
     * 同侧到顶仍卡死才换边。
     * (P2.1: 旧版换边乒乓 + radius 判据, 大悬崖前左右横跳永远绕不出去 —— 用户
     * "总可以绕一绕, 太不智能没有大局观"。)
     * (2026-09-14 大转弯, 用户"策略太严, 大范围内可以允许大转弯"): 封顶
     * ±90° → ±150°, 加档步距 15° → 25° —— 大岩浆海的绕行方向常在 90°~150°。
     */
    private void maybeEscalateProbe(AltoClef mod) {
        String key = expeditionKey();
        BlockPos anchor = _anchorByKey.get(key);
        if (anchor == null || mod.getPlayer() == null) return;
        long stallNowMs = System.currentTimeMillis();
        long stallLastMs = _lastProgressMsByKey.getOrDefault(key, stallNowMs);
        long stalledMs = stallNowMs - stallLastMs;
        if (stalledMs < STALL_MS) return; // 停滞不够久, 可能只是普通寻路失败
        // (20261112 A) 升级节流: 调用方扩到"每 tick 周期调用"后, 两次升级至少隔
        // 2×STALL_MS —— 保住"同侧加档 10s 一档"的原节奏, 不因周期调用变成连扫。
        Long lastEscalateMs = _lastProbeEscalateMsByKey.get(key);
        if (lastEscalateMs != null && stallNowMs - lastEscalateMs < STALL_MS * 2) return;
        // (P2.1) 触发提速: 失败够 2 次即升级; 或停滞 ≥2×STALL_MS。
        // (20261112 A) 去掉 longStall 的"失败≥1"前提: 沿岩浆边"走得到但绕不出"的
        // 乒乓里 baritone 永远给得出可行路径, progress 检查器不判失败,
        // "outside step failed"/冻结看门狗全场 0 条 → fails 恒 0 → probe 被锁死
        // (20261112-204350 实锤: max=707 冻结 7 分钟+, 全程 probe=+0 fails=0)。
        // 停滞本身 (10s 无新最远点) 就是换向信号。
        boolean enoughFails = _failCounter >= PROBE_AFTER_FAILS;
        boolean longStall = stalledMs >= STALL_MS * 2;
        if (!enoughFails && !longStall) return;
        double cur = _probeOffsetByKey.getOrDefault(key, 0.0);
        double next;
        if (cur == 0.0) {
            next = Math.toRadians(PROBE_STEP_DEG); // 先认准一侧, 之后同侧加档
        } else if (Math.abs(Math.toDegrees(cur)) < MAX_PROBE_DEG) {
            // 同侧加一档: 大障碍要沿崖根走到底, 不换边乒乓。
            double mag = Math.min(Math.abs(cur) + Math.toRadians(PROBE_INCREMENT_DEG), Math.toRadians(MAX_PROBE_DEG));
            next = cur > 0 ? mag : -mag;
        } else {
            // 同侧 150° 仍卡死 → 换另一侧从 60° 重新爬档。
            next = cur > 0 ? -Math.toRadians(PROBE_STEP_DEG) : Math.toRadians(PROBE_STEP_DEG);
        }
        _probeOffsetByKey.put(key, next);
        _probeStartMaxByKey.put(key, _maxRadiusByKey.getOrDefault(key, 0.0));
        _probeStartPosByKey.put(key, mod.getPlayer().getBlockPos());
        _lastProbeEscalateMsByKey.put(key, stallNowMs);
        _failCounter = 0; // 给试探方向重新计失败
        Debug.logMessage(String.format(java.util.Locale.ROOT,
                "[Expedition] stalled (no new max for %ds), probing %+.0f° (cap ±%.0f°, long step x2)",
                stalledMs / 1000, Math.toDegrees(next), MAX_PROBE_DEG));
    }

    /**
     * (20261112 B·记忆追逐停滞冷却) 非 failure 通道: progress 失败路径在"沿岸
     * 乒乓"里永远不触发 (baritone 一直有路), 查讫/冷却又要求真走到目标 32 格内
     * (被岩浆拦住到不了) —— 假记忆中心的候选云就能无限期绑架选点。本方法按
     * "追逐锚中心"聚合追逐时长: 连续 {@link #MEMORY_CHASE_COOLDOWN_MS} 锚未换、
     * radius 无新最远、无一次查讫 → 冷却该中心 3 分钟 (复用 _centerCooldownUntil:
     * 中心分支跳过 + 候选分支剔除其 64 格内候选), probe/锁定方向接管。
     * 每 tick 由 {@link #maybeLogExpedition} 调用 (仅野外模式)。
     */
    private void maybeChaseStallCooldown(AltoClef mod, Vec3d pos, String key) {
        boolean chasing = "memory".equals(_lastClue) || "memory-center".equals(_lastClue);
        BlockPos nearest = null;
        if (chasing) {
            // 追逐锚 = 最近记忆中心。候选区围绕中心 ±48 随机重生成, 逐候选记账
            // 会被重生成洗掉, 按中心聚合才能跨重选累积。
            List<BlockPos> centers = NamedLandmarks.recallClueCenters(mod,
                    KnowledgeMemory.StructureType.NETHER_FORTRESS, pos, REMEMBERED_CENTER_MAX_DISTANCE);
            double bestSq = Double.POSITIVE_INFINITY;
            for (BlockPos c : centers) {
                double sq = c.getSquaredDistance(pos);
                if (sq < bestSq) {
                    bestSq = sq;
                    nearest = c;
                }
            }
        }
        if (!chasing || nearest == null) {
            // 线索切走 (声音/砖/随机方向) 或没有中心: 记忆没在拽, 清钟重来。
            if (_chaseAnchorByKey.remove(key) != null) {
                _chaseSinceMsByKey.remove(key);
                _chaseStartMaxByKey.remove(key);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!nearest.equals(_chaseAnchorByKey.get(key))) {
            // 锚中心换人 (或首拍): 重启计时。
            _chaseAnchorByKey.put(key, nearest.toImmutable());
            _chaseSinceMsByKey.put(key, now);
            _chaseStartMaxByKey.put(key, _maxRadiusByKey.getOrDefault(key, 0.0));
            return;
        }
        // 追逐期间有真进度 (radius 破纪录) → 方向没错, 重启计时。
        double maxNow = _maxRadiusByKey.getOrDefault(key, 0.0);
        Double startMax = _chaseStartMaxByKey.get(key);
        if (startMax != null && maxNow > startMax + 1.0) {
            _chaseSinceMsByKey.put(key, now);
            _chaseStartMaxByKey.put(key, maxNow);
            return;
        }
        Long since = _chaseSinceMsByKey.get(key);
        if (since == null || now - since < MEMORY_CHASE_COOLDOWN_MS) return;
        _chaseAnchorByKey.remove(key);
        _chaseSinceMsByKey.remove(key);
        _chaseStartMaxByKey.remove(key);
        _centerCooldownUntil.put(nearest.toImmutable(), now + CENTER_COOLDOWN_MS);
        // 同步压制 memory-center 分支 30s: 剩余中心若仍锚在同一象限, 换向等于没换
        // (与远处中心 3 次失败冷却的同款处置, 见 tickOutside 失败分级)。
        _memoryCenterSuppressUntilTick = _tickId + MEMORY_CENTER_SUPPRESS_TICKS;
        Debug.logMessage(String.format(java.util.Locale.ROOT,
                "[Expedition] memory chase stalled %.0fs without new max or checked area, "
                        + "cooling center %s for 3min (its candidates within 64 blocks dropped)",
                (now - since) / 1000.0, nearest.toShortString()));
    }

    // ------------------------------------------------------------------ //
    // (2026-09-13 远征取证) 诊断: 模式 / 群系 / 方位角 / 距锚点半径 / 线索来源
    // ------------------------------------------------------------------ //

    private String expeditionKey() {
        return _persistKey != null ? _persistKey : "?:unknown";
    }

    /** 该候选是否落在已"查讫"(扑空过) 的记忆区 32 格内。 */
    private boolean isCheckedMemory(BlockPos candidate) {
        java.util.Set<BlockPos> checked = _checkedMemoryByKey.get(expeditionKey());
        if (checked == null || checked.isEmpty()) return false;
        for (BlockPos p : checked) {
            if (p.isWithinDistance(candidate, MEMORY_CHECK_RADIUS)) return true;
        }
        return false;
    }

    private void markMemoryChecked(BlockPos target) {
        if (target == null) return;
        // (20261112 B) 查讫 = 记忆系统真进度 (走到过、扑空登记), 追逐停滞钟重启
        // —— 否则"合法清候选云但 radius 不涨"的场景会被误判成追逐停滞。
        String key = expeditionKey();
        _chaseSinceMsByKey.put(key, System.currentTimeMillis());
        _chaseStartMaxByKey.put(key, _maxRadiusByKey.getOrDefault(key, 0.0));
        java.util.Set<BlockPos> checked = _checkedMemoryByKey
                .computeIfAbsent(key, k -> new java.util.HashSet<>());
        if (checked.size() >= MAX_CHECKED_MEMORY) return;
        if (checked.add(target.toImmutable())) {
            Debug.logMessage("[Expedition] memory area checked-empty at " + target.toShortString()
                    + " (" + checked.size() + "/" + MAX_CHECKED_MEMORY + " total)");
        }
    }

    /**
     * (2026-09-19) 背包里 baritone 真能放置的方块数 (BlockItem 且非岩浆块,
     * 与 baritone 放置列表同口径, 灵魂沙算 —— 见 AltoClef.java 同日注释)。
     */
    private static int countPlaceableScaffoldBlocks(AltoClef mod) {
        int n = 0;
        for (Item item : mod.getModSettings().getThrowawayItems(mod, true)) {
            if (item instanceof BlockItem && item != Items.MAGMA_BLOCK) {
                n += mod.getItemStorage().getItemCount(item);
            }
        }
        return n;
    }

    /** (2026-09-19) 中心是否在"走不到"冷却期内。 */
    private static boolean isCenterOnCooldown(BlockPos center) {
        Long until = _centerCooldownUntil.get(center);
        return until != null && until > System.currentTimeMillis();
    }

    private int checkedMemoryCount() {
        java.util.Set<BlockPos> checked = _checkedMemoryByKey.get(expeditionKey());
        return checked == null ? 0 : checked.size();
    }

    private void maybeLogExpedition(AltoClef mod) {
        if (mod.getPlayer() == null) return;
        Vec3d now = mod.getPlayer().getPos();
        BlockPos p = mod.getPlayer().getBlockPos();
        String key = expeditionKey();
        // (20261112 A+B) 周期自检只在野外模式: 走廊模式没有"远征停滞"语义。
        // ① A·probe 周期调用 —— 旧版 probe 只在 progress 失败 (:1043) 与冻结
        //    看门狗 (:934) 两条路径被调, "沿岸乒乓"里 bot 一直在动、baritone
        //    一直有路, 两条路全都不来 → probe 死锁。每 tick 进来, 方法内部自带
        //    STALL_MS 停滞闸 + 2×STALL_MS 升级节流, 不改原节奏。
        // ② B·记忆追逐停滞 —— clue=memory/-center 连续绑架选点且无任何真进度
        //    时, 把追逐锚中心冷却 3 分钟 (见 MEMORY_CHASE_COOLDOWN_MS 注释)。
        if (!_corridorMode) {
            maybeEscalateProbe(mod);
            maybeChaseStallCooldown(mod, now, key);
        }
        BlockPos anchor = _anchorByKey.computeIfAbsent(key, k -> p);
        double radius = Math.hypot(p.getX() - anchor.getX(), p.getZ() - anchor.getZ());
        double prevMax = _maxRadiusByKey.getOrDefault(key, 0.0);
        double maxRadius = Math.max(prevMax, radius);
        _maxRadiusByKey.put(key, maxRadius);
        // (2026-09-13 P2 遇障换向) 有真实进展 (刷新最远半径): 记进度时刻 +
        // 失败归零 —— 只有"长时间无进展的失败"才升级换向。
        if (radius > prevMax + 0.5) {
            _lastProgressMsByKey.put(key, System.currentTimeMillis());
            _failCounter = 0;
        }
        // 试探成功 → 转正为新锁定基向。两条判据 (P2.1):
        // ① radius 破纪录 (走比卡死点更远, 原判据);
        // ② 距试探起点净位移 > EXPLORE_STEP (绕崖是绕锚点的圆弧, 半径不变,
        //    但横向位移实打实 —— 走出一个整步程 = 试探方向真的可走 = 绕出去了)。
        Double probe = _probeOffsetByKey.get(key);
        if (probe != null && probe != 0.0) {
            Double startMax = _probeStartMaxByKey.get(key);
            BlockPos startPos = _probeStartPosByKey.get(key);
            boolean radiusBroken = startMax != null && radius > startMax + 1.0;
            boolean walkedArc = startPos != null
                    && Math.hypot(p.getX() - startPos.getX(), p.getZ() - startPos.getZ()) > EXPLORE_STEP;
            if (radiusBroken || walkedArc) {
                Double base = _headingByKey.get(key);
                if (base != null && !Double.isNaN(base)) {
                    _headingByKey.put(key, base + probe);
                    _heading = base + probe;
                    Debug.logMessage(String.format(java.util.Locale.ROOT,
                            "[Expedition] probe %+.0f° committed (%s), new bearing %s",
                            Math.toDegrees(probe), walkedArc ? "walked arc" : "radius broken",
                            fmtDeg(base + probe)));
                }
                _probeOffsetByKey.put(key, 0.0);
                _probeStartMaxByKey.remove(key);
                _probeStartPosByKey.remove(key);
            }
        }
        String biome = netherBiomeLabel(mod);
        String prevBiome = _lastBiomeLabel;
        boolean biomeChanged = prevBiome != null && !prevBiome.equals(biome);
        boolean periodic = _tickId % EXPEDITION_DIAG_INTERVAL_TICKS == 0;
        boolean milestone = maxRadius - _lastLoggedRadius >= EXPEDITION_RADIUS_MILESTONE;
        if (!periodic && !milestone && !biomeChanged) return;

        _lastLoggedRadius = maxRadius;
        String moved = _lastDiagPos == null ? "-"
                : String.format(java.util.Locale.ROOT, "%.1f", _lastDiagPos.distanceTo(now));
        _lastDiagPos = now;
        _lastBiomeLabel = biome;
        Debug.logMessage(String.format(java.util.Locale.ROOT,
                "[Expedition] mode=%s biome=%s bearing=%s anchor=(%d,%d) radius=%.0f max=%.0f"
                        + " target=%s moved=%s clue=%s fails=%d memCk=%d probe=%s",
                _corridorMode ? "corridor" : "outside", biome,
                Double.isNaN(_heading) ? "?" : fmtDeg(_heading),
                anchor.getX(), anchor.getZ(), radius, maxRadius,
                _currentTarget == null ? "-" : _currentTarget.toShortString(),
                moved, _lastClue, _failCounter, checkedMemoryCount(),
                String.format(java.util.Locale.ROOT, "%+.0f",
                        Math.toDegrees(_probeOffsetByKey.getOrDefault(key, 0.0)))));
        if (biomeChanged) {
            Debug.logMessage("[Expedition] biome switch " + prevBiome + " -> " + biome);
        }
    }

    /** 当前群系标签 (只认下界五群系, 其余归 "other"; 异常返 "?")。 */
    private static String netherBiomeLabel(AltoClef mod) {
        try {
            var biome = mod.getWorld().getBiome(mod.getPlayer().getBlockPos());
            if (biome.matchesKey(BiomeKeys.BASALT_DELTAS)) return "basalt_deltas";
            if (biome.matchesKey(BiomeKeys.SOUL_SAND_VALLEY)) return "soul_sand_valley";
            if (biome.matchesKey(BiomeKeys.CRIMSON_FOREST)) return "crimson_forest";
            if (biome.matchesKey(BiomeKeys.WARPED_FOREST)) return "warped_forest";
            if (biome.matchesKey(BiomeKeys.NETHER_WASTES)) return "nether_wastes";
            return "other";
        } catch (Exception e) {
            return "?";
        }
    }

    private static String fmtDeg(double rad) {
        double deg = Math.toDegrees(rad) % 360;
        if (deg < 0) deg += 360;
        return String.format(java.util.Locale.ROOT, "%.0f", deg);
    }

    /**
     * 是否"靠近看见过的下界砖" (走得到才算, 缓存位置已在感知半径内)。
     */
    private boolean nearSeenBrick(AltoClef mod) {
        return _perceivedBrickCache.isPresent()
                && _perceivedBrickCache.get().isWithinDistance(mod.getPlayer().getPos(), FORTRESS_NEARBY_RANGE + 16);
    }

    /**
     * 找"亲眼看见"的最近线索方块 (要塞=下界砖, 猪灵=堡垒黑石砖/绯红菌岩):
     * 缓存候选按距离排序, 只逐个做感知判定到第一个命中为止 (控制 LOS 射线
     * 开销, 参见 KnowledgeMemory 卡顿教训)。
     */
    private Optional<BlockPos> findNearestPerceivedBrick(AltoClef mod) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Block clue : _clueBlocks) {
            candidates.addAll(mod.getBlockTracker().getKnownLocations(clue));
        }
        if (candidates.isEmpty()) return Optional.empty();
        Vec3d from = mod.getPlayer().getPos();
        candidates.sort((a, b) -> Double.compare(a.getSquaredDistance(from), b.getSquaredDistance(from)));
        int checks = 0;
        for (BlockPos pos : candidates) {
            // 只做有限次射线: 最近的几个都看不见 (背对着) 就等下一轮。
            if (++checks > 12) break;
            if (VisibilityHelper.isBlockPerceivable(mod, pos)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 永不自行结束: 找到刷怪笼由上层 (CollectBlazeRodsTask) 切任务,
        // 长期找不到由上层超时兜底开关接管。
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        // 身份比较: 上层缓存同一个实例每 tick 返回, 状态 (已走格子) 得以保留。
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return _corridorEnabled ? "Fortress explore (human-like)" : "Nether explore (human-like)";
    }

    // =========================================================================
    // (2026-10-04 场景摘要) SceneDigest 消费的只读状态 —— "找堡垒"(外围远征,
    // 还没踏上堡垒) 与 "巡堡垒走廊"(走廊模式, 已在堡垒里) 是两种完全不同的
    // 现场动作, 此前统一译成"探堡垒"让对端把赶路找路理解成了在里面探索。
    // =========================================================================

    /** true = 走廊模式（已踏上堡垒地面在巡逻）；false = 外围远征（还在找）。 */
    public boolean isCorridorMode() {
        return _corridorMode;
    }

    /** true = forPiglins() 变体（找猪灵/堡垒，非找要塞）；语义与主变体不同，摘要侧需区分。 */
    public boolean isPiglinVariant() {
        return PIGLIN_SOUND_PREFIX.equals(_soundPrefix);
    }
}
