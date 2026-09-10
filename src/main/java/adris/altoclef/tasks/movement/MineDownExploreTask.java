package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Dig a staircase shaft down to find ores. Humans don't find ores by staring
 * at the surface; they dig down.
 *
 * Rules:
 *  - Dig a 45-degree staircase (never a straight drop) so we can walk back up.
 *  - While digging, watch for caves: if an open cave space is detected, switch
 *    to biased exploration INSIDE the cave (prefer exploring caves over blind
 *    digging — caves expose far more ore-bearing walls than fresh tunnels).
 *  - Stop when we reach the target Y depth or after {@value MAX_DIG_STEPS}
 *    steps with nothing found; the parent task then resumes surface exploration.
 *
 * If the player is ALREADY at/below the target depth, the task runs in TUNNEL
 * mode: dig a 2-block-high tunnel that slowly DESCENDS (1 block every
 * {@value TUNNEL_DESCEND_EVERY} steps, floored at y={@value TUNNEL_MIN_Y}).
 * Repeated tunnel runs sink toward diamond depth and then branch-mine flat —
 * this is what a human does at mining depth: ores don't come to you, you dig
 * corridors and watch the walls.
 *
 * Cave detection (2026-09-01 最终方案): 深度闸门(y≤45) + 露天闸门(头顶有岩顶)
 * + 连通空气 BFS(防透视) + 【天然空气统计】(排除自挖块、y≤55, 数量≥24 且
 * X/Z/Y 三维跨度达标 — 多条自挖隧道缝合也绝不会误判) → ExploreCaveTask 进洞。
 */
public class MineDownExploreTask extends Task implements ITaskRequiresGrounded {

    // Default target Y depth if the caller doesn't specify one.
    private static final int DEFAULT_TARGET_Y = 10;
    // Maximum number of staircase steps before giving up.
    private static final int MAX_DIG_STEPS = 40;
    // (2026-09-10 空跳修复) 每步入队 2 格 (ground.up() + ground), 预算按
    // 【真实挖掉方块数】计, 步数预算 = MAX_*_DIG_STEPS * BLOCKS_PER_STEP。
    private static final int BLOCKS_PER_STEP = 2;
    // (2026-09-10 空跳修复) 单 tick 空跳步数上限: 防巨型空腔(沿路径成百上千格
    // 全空气)下 fast-forward while 真死循环。跨 tick 可继续跳, 不影响正确性。
    private static final int SKIP_STEPS_PER_TICK = 512;
    // How far around the player we look for a cave while digging (blocks).
    // 8 (was 5): diag logs showed the bot mining straight past caves whose
    // mouths were 6-7 blocks away — a human would notice and walk in.
    private static final int CAVE_SCAN_RADIUS = 8;
    // (2026-09-01 用户"探索机制还是8格, 得调远") 矿洞【检测】保持 8 格
    // (找洞口, 防误判), 但【进洞后的探索目标选择】扫描更远 — collectConnectedAir
    // 以 EXPLORE_SCAN_RADIUS 为界, 探索段更长、推进更连贯, 不再 8 格一小段。
    private static final int EXPLORE_SCAN_RADIUS = 16;
    // (2026-09-01 三维开阔度判定, 取代旧 CAVE_AREA_THRESHOLD=40 空气计数)
    // 天然洞在 X/Z/Y 三维都开阔, 自挖楼梯/隧道是 1-2 格宽、2 格高的细长通道。
    // 连通空气集合的 X/Z/Y 跨度分别达到下面阈值才算"开阔天然洞", 触发矿洞探索。
    private static final int CAVE_OPEN_SPAN_X = 4;
    private static final int CAVE_OPEN_SPAN_Z = 4;
    private static final int CAVE_OPEN_SPAN_Y = 3;
    // (2026-09-01 自挖块排除, 用户"又错误探测到矿洞", 现场确认=全是自挖通道)
    // 三维跨度只挡得住【单条】自挖隧道; 多条隧道(多次任务实例/换向重挖)交叉缝合后
    // 连通空气 X/Z/Y 跨度全部达标(实测 20:42: X=7,Z=9,Y=7) → 仍误判。
    // 本修复: 开阔度只统计【天然空气】= 连通空气中排除自挖记录、
    // 且 y 不高于 CAVE_NATURAL_AIR_MAX_Y(用户: 只认 y≤45 的空气, 与深度闸门对齐,
    // 防自挖楼梯上半段/浅层洞口被计入)。
    // 自挖网络里天然空气≈0 → 永不误判; 挖穿进真洞时洞内天然空气连通 → 正常触发。
    private static final int CAVE_NATURAL_AIR_MAX_Y = 45;
    // (2026-09-02 钻石任务矿洞深度上界, 用户"下挖到合法深度却导航到上方矿洞")
    // 矿洞认定 + 探索导航都只认【目标深度附近】的天然空气: y 不能高于
    // targetY + 本缓冲 (钻石 targetY=-24 → 只认 -8 以下)。否则 bot 在钻石层
    // 挖洞时, 连通空气向上蔓延到的浅层洞会被当成"当前深度该探索的洞" → 导航
    // 离开钻石层。package-private 供 ExploreCaveTask 复用。
    static final int CAVE_ABOVE_TARGET_BUFFER = 16;
    // 天然空气的最少数量: 配合三维跨度, 过滤"恰好一条天然窄缝穿过扫描区"的小概率情形。
    private static final int MIN_NATURAL_CAVE_AIR = 24;
    // 自挖位置记录上限(每维度, 容量封顶, 超限逐出最旧并重写文件), 防内存/文件无限增长。
    private static final int MAX_SELF_DUG_MEMORY = 8192;
    // (2026-09-01 跨会话持久化, 用户"一到y=45就触发") 静态集合随 JVM 重启清零 →
    // 上一局挖的旧隧道在新会话里全被当"天然空气"。自挖记忆持久化到游戏目录
    // altoclef_selfdug.log(每行 "维度 x y z"), 启动后首次访问时加载。记录入口在
    // DestroyBlockTask(中央: 所有任务真实挖开的方块都记, 含主线 MineAndCollect 挖矿)。
    private static final String SELF_DUG_FILE = "altoclef_selfdug.log";
    private static final java.util.Map<String, java.util.LinkedHashSet<BlockPos>> _selfDugByDim
            = new java.util.HashMap<>();
    private static boolean _selfDugLoaded = false;
    // =========================================================================
    // (2026-09-03 已探索洞穴区域记忆, 用户"bot 同一个矿洞打转10分钟, 要区域记忆":
    // 该坐标大范围内不再触发矿洞探索) 跨实例、跨会话记录【已探索完成的洞穴区域】,
    // 探测(findNearbyCave)与目标选择(pickFarthestNaturalCaveAir)都排除这些区域 → 同一
    // 大洞网络不会被反复进洞打转(日志 17:0x 同一洞反复触发实锤)。
    //
    // 粒度: 用户"不同世界不要共享" → key = 存档名 + ':' + 维度, 不同存档/维度独立维护
    // (自挖隧道 selfDug 旧版只按维度, 此处更严)。持久化到游戏目录 altoclef_explored.log
    // (每行 "存档:维度 x y z"), 启动后首次访问时加载。
    // =========================================================================
    private static final String EXPLORED_CAVE_FILE = "altoclef_explored.log";
    // 已探索洞穴区域的禁入/禁探索半径(格)——区域圆心为探索结束时玩家所在/洞区中心。
    // (2026-09-03 用户选定 32): 一个普通矿洞 1 次就能整体剔除; 超大洞网络靠"每个探索
    // 结束标一个中心"逐步覆盖, 即使 90s 超时也能保证同一片不再被反复进。
    private static final int EXPLORED_CAVE_RADIUS = 32;
    // (2026-09-03 同心丢弃) 半径内已有中心时不再重复记(防密集记录同片洞区)。
    private static final int EXPLORED_CAVE_MIN_SEP = 24;
    private static final java.util.Map<String, java.util.LinkedHashSet<BlockPos>> _exploredCaveByKey
            = new java.util.HashMap<>();
    private static boolean _exploredCaveLoaded = false;
    // (2026-09-06 闸门级诊断, 用户"22:31 在大矿洞里没触发探洞") findNearbyCave 有
    // 多个【静默】返回点(露天闸门/跨度/邻居), 日志上完全看不出被哪道闸门拦下,
    // 也无法区分"没扫"和"扫了被拒"。每次评估返回时打一行 [CaveScan] 原因:
    // 每个实例首次评估必打(防 2 秒节流错过短窗口), 之后拒绝类 2 秒节流,
    // 触发类(FIRE)必打。静态时间戳跨实例共享节流。
    private static long _caveScanDiagMs = 0;
    // Check for caves every N steps.
    private static final int CAVE_SCAN_EVERY_STEPS = 3;
    // Horizontal direction of the staircase (dig east as we descend).
    private static final int STAIR_DIR_X = 1;
    private static final int STAIR_DIR_Z = 0;
    // Horizontal directions a tunnel may pick from (cardinal only).
    private static final int[][] TUNNEL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // Tunnel mode descends 1 block every this many steps (a walkable 2:1 slope).
    private static final int TUNNEL_DESCEND_EVERY = 2;
    // Tunnels never descend below this Y — at the floor they run flat, which is
    // exactly how humans branch-mine for diamonds. 1.18 diamond band: stop
    // sinking around y=-24 and branch-mine flat from there.
    // (2026-09-05 下界高度概念) 此下限只适用于主世界: 下界基岩在 y=0~4, 照搬会把
    // bot 一路缓降到 y≈5 被基岩挡死 (实测)。下界隧道不再缓降 (floor=目标高度, 平挖)。
    private static final int TUNNEL_MIN_Y = -24;
    // (2026-09-05 下界基岩层逃生, 用户"挖到基岩挡住应向上挖矿洞回到Y=70再随便探索")
    // 下界玩家 y 低于此值 → 紧急进入上行模式: 45° 楼梯向上挖回目标高度。
    // 基岩顶面 y=4, 取 10 留出安全缓冲。
    private static final int NETHER_ESCAPE_Y = 10;
    // 上行模式的最大楼梯步数 (从 y≈5 爬回 y=70 需约 66 步, 80 留余量;
    // 普通下挖 MAX_DIG_STEPS=40 不变)。
    private static final int MAX_ASCEND_DIG_STEPS = 80;
    // (上行触发缓冲) 下界任务开始时, 玩家低于 目标高度-16 才进入上行模式:
    // 避免 y=69 这种贴着目标高度的位置反复触发"上爬1格"的抖动。
    private static final int NETHER_ASCEND_BUFFER = 16;
    // =========================================================================
    // (2026-09-05 下界目标带, 用户"高度无法确定, 只要在一个合适范围内, 适用于
    //  不同下界任务") 单点目标高度 (70) 改为【生成带】: 调用方给 [min,max], 每个
    //  MineDownExploreTask 实例 onStart 在带内随机取目标高度 —— 人类不会每次卡
    //  同一个 y, 也天然避免所有隧道挖成同一条水平线。
    //
    //  下界通用开采带 [32, 96]:
    //  - 下限 32 = 熔岩海面 (y=31) 之上: 更深的隧道到处开岩浆口, 且逼近基岩;
    //  - 上限 96 = 远离下界基岩顶盖 (y=127), 大部分下界实心地形都在此带内。
    //  特殊矿石 (如古代残骸生成带 y≈8~22) 由调用方给专属带, 但必须整体位于
    //  NETHER_ESCAPE_Y=10 危险线之上, 否则会被紧急上行逻辑反复拽回。
    // =========================================================================
    public static final int NETHER_MINING_MIN_Y = 32;
    public static final int NETHER_MINING_MAX_Y = 96;
    // How long we may spend exploring a cave before resuming the dig.
    // (2026-09-01 用户"矿洞探索超时太短") 25s 对大洞穴偏短: 纯走路就能
    // 掐断一次探索。提到 90s; 且挖到矿会重置计时 (markOreMined), 有产出的
    // 探索不会被超时打断, 只有"长时间没挖到矿的干逛"才会被限时。
    private static final double CAVE_EXPLORE_TIMEOUT = 90;

    // How deep we're willing to dig for this run (differs by target ore).
    // (2026-09-05 目标带) 单点改带 [_targetYMin, _targetYMax]: onStart 随机取
    // 一点作本轮目标。_targetY 不再是 final (每实例 onStart 赋值一次)。
    private final int _targetYMin;
    private final int _targetYMax;
    private int _targetY;

    private final Deque<BlockPos> _digQueue = new ArrayDeque<>();

    private BlockPos _digStart;
    private int _step = 0;
    // (2026-09-10 空跳修复) 本实例【真实挖掉】的方块数 = 本轮挖掘预算的消耗量。
    // 与几何路径索引 _step 严格分离: _step 会被 FAST-FORWARD 空跳刷高, 不代表
    // 任何真实挖掘; reachedBottom 的步数上限只看 _realDigs — 空跳不能再替
    // "一镐没挖"的实例宣告 "Shaft done" (10:47 场次死循环根因)。
    private int _realDigs = 0;
    // 上一 tick 派发 DestroyBlockTask 的目标格。下个 tick 确认它已变空气
    // ⇒ 真的被挖掉了, 记 1 格预算 (判定必须先于 skipAirAndWater 弹队首)。
    private BlockPos _miningTarget = null;
    // 连续空跳步数计数 (跨 tick 累计, 找到实体方块后清零), 用于诊断日志与
    // 单 tick 空跳上限。
    private int _skipStreak = 0;
    private boolean _inCave = false;
    private Task _subTask;
    // (2026-09-06 闸门级诊断) 本实例是否已打过首次 [CaveScan] 评估行。
    private boolean _caveScanDiagLogged = false;
    // Tunnel mode: we're already at the target depth, dig horizontally instead.
    private boolean _tunnelMode = false;
    // (2026-09-05 下界维度感知) onStart 时缓存是否在下界, 供隧道下限/上行模式判断。
    private boolean _nether = false;
    // (2026-09-05 下界基岩层逃生) true = 45° 楼梯【向上】挖回目标高度。
    private boolean _ascendMode = false;
    private int _dirX = STAIR_DIR_X;
    private int _dirZ = STAIR_DIR_Z;
    // [Diag-water] how many water blocks we've skipped so far this run.
    private int _waterSkipped = 0;
    // Cave exploration watchdog.
    private final TimerGame _caveExploreTimer = new TimerGame(CAVE_EXPLORE_TIMEOUT);
    // (2026-09-02 防"同一洞反复探索", 用户"永远不会离开这个矿洞") 探索结束(超时/
    // 完成)后的冷却步数: 冷却内不再探测矿洞, 让 bot 挖离洞区/换向, 避免
    // 探索→挖几步→又探测到同一洞→又探索 的死循环 (日志 17:01 at 63,33,428 /
    // 51,33,406 反复实锤)。12 步 ≈ 挖 12 格隧道远离洞区边缘。
    private static final int CAVE_EXPLORE_COOLDOWN_STEPS = 12;
    private int _caveExploreCooldownSteps = 0;
    // (2026-09-02 刷怪笼危险处理, 用户"先清怪再挖刷怪笼") 挖通道时【感知到】的
    // 刷怪笼是持续刷怪源, 不清掉会一直被怪打 (用户"bot 找到刷怪笼后被怪物一直
    // 打")。流程: 感知(4 格, 看到就感知, 不需要 FOV/LOS — 危险源主动避让, 类似
    // 床的无条件感知) → 有镐: 先清 16 格内敌对怪再挖掉刷怪笼 (粘性, 状态机驱动);
    // 无镐: 换向远离 + 记黑名单 (徒手挖不动 SPAWNER 硬度5, 硬挖会被刷怪打死)。
    // 挖掉的/避让的刷怪笼位置都记入黑名单, 防隧道反复挖回同一地牢。
    private static final int SPAWNER_SCAN_RANGE = 4;          // 感知范围(格)
    private static final int HOSTILE_CLEAN_RANGE = 16;        // 清怪范围=刷怪笼激活半径
    private static final long SPAWNER_SCAN_INTERVAL_MS = 500; // 近场扫描节流, 防每 tick 卡顿
    private int _spawnerPhase = 0;                            // 0=无/完成, 1=清怪, 2=挖刷怪笼
    private BlockPos _spawnerTarget = null;
    private long _lastSpawnerScanMs = 0;
    private final Set<BlockPos> _avoidedSpawners = new HashSet<>();

    public MineDownExploreTask() {
        this(DEFAULT_TARGET_Y);
    }

    public MineDownExploreTask(int targetY) {
        this(targetY, targetY);
    }

    /**
     * (2026-09-05 目标带, 用户"高度无法确定, 合适范围即可") 给 [min, max] 生成带,
     * onStart 在带内随机取本轮目标高度; min==max 时等价单点。
     */
    public MineDownExploreTask(int targetYMin, int targetYMax) {
        _targetYMin = Math.min(targetYMin, targetYMax);
        _targetYMax = Math.max(targetYMin, targetYMax);
        _targetY = _targetYMin;
    }

    /**
     * (2026-09-01 用户"一旦挖到矿就应该重置矿洞探索超时") 矿洞探索期间挖到矿
     * = 有产出, 看门狗不应把活跃的探索掐断。由 MineOrCollectTask 每次开始追
     * 矿石时调用, 重置探索计时 — 边挖矿边探索的会话不会被超时打断, 只有
     * "长时间没挖到矿的干逛"才被限时。
     */
    public void markOreMined() {
        _caveExploreTimer.reset();
    }

    // (2026-09-07 断点续探) 是否已完成过一次 onStart; 区分"首次启动"与"被打断后恢复"。
    private boolean _startedOnce = false;

    @Override
    protected void onStart(AltoClef mod) {
        // (2026-09-07 断点续探, 用户"挖完一个矿就继续挖通道, 矿洞记忆没生效") 任务
        // 被打断(典型: 探洞途中感知到矿石, 父任务切去挖矿/捡取)后恢复时, 框架会重新
        // 调 onStart。原实现无条件重置 _inCave/_step/_digQueue → 探索状态静默丢失,
        // _inCave 分支里的 markExploredCaveRegion 永远执行不到 → 同一片洞区刚探索完
        // 就再次被探测(10:26 复现实锤: FIRE 后 19 秒同洞二次 FIRE, 全程无 marked
        // region 行)。改为: 仅当上次中断发生在探索中(_inCave)且维度未变时, 保留
        // 探索状态(_inCave/_subTask/探索计时/冷却)原地续探; 挖掘中断(_inCave=false)
        // 仍走完整重置, 保留死亡/传送后的自愈能力。
        boolean dimChanged = _startedOnce
                && _nether != (WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER);
        if (_startedOnce && _inCave && !dimChanged) {
            return;
        }
        _startedOnce = true;
        // (2026-09-05 目标带) 本轮目标高度: 生成带内随机取一点 (含两端)。
        _targetY = _targetYMin + (int) (Math.random() * (_targetYMax - _targetYMin + 1));
        _digStart = mod.getPlayer().getBlockPos().down();
        _step = 0;
        _realDigs = 0;
        _miningTarget = null;
        _skipStreak = 0;
        _inCave = false;
        _waterSkipped = 0;
        _digQueue.clear();
        // (2026-09-05 下界维度感知) 缓存维度, 决定隧道下限与上行模式。
        _nether = WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER;
        // (2026-09-05 下界基岩层逃生) 任务开始时已被压到目标高度之下很深 (如上一轮
        // 挖到 y≈5 被基岩挡) → 直接上行模式, 45° 楼梯挖回目标高度, 不再向下。
        _ascendMode = _nether && _digStart.getY() <= _targetY - NETHER_ASCEND_BUFFER;
        // Already at mining depth? Don't dig a pointless 1-block "shaft" that
        // finishes instantly and leaves the bot wandering — dig a tunnel instead.
        _tunnelMode = !_ascendMode && _digStart.getY() <= _targetY;
        if (_ascendMode) {
            int[] dir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
            _dirX = dir[0];
            _dirZ = dir[1];
            Debug.logMessage("[MineDown] nether: below target depth (y=" + _digStart.getY()
                    + ", target " + _targetY + "), climbing back up with an ascending staircase.");
            Debug.logToFile("altoclef_diag.log", "[MineDown] nether ascend mode engaged at "
                    + _digStart.toShortString() + " (target y=" + _targetY + ").");
        } else if (_tunnelMode) {
            // Pick a random cardinal direction so consecutive tunnels don't
            // forever dig the same way through the same rock.
            int[] dir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
            _dirX = dir[0];
            _dirZ = dir[1];
            Debug.logMessage("Already at depth " + _digStart.getY() + " (target " + _targetY
                    + "), digging a " + (_nether ? "flat" : "slowly-descending") + " tunnel instead.");
        } else {
            _dirX = STAIR_DIR_X;
            _dirZ = STAIR_DIR_Z;
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-05 下界基岩层逃生, 用户"挖下界金矿一路往下挖被基岩挡在 y≈5") 任务
        // 进行中若玩家已压到基岩层附近 (下界基岩顶面 y=4), 立即切上行模式: 清空当前
        // 向下队列, 换随机水平方向, 45° 楼梯向上挖回目标高度, 然后任务自然结束,
        // 由父任务以隧道平挖模式继续在目标高度探索。
        if (_nether && !_ascendMode && mod.getPlayer().getBlockPos().getY() <= NETHER_ESCAPE_Y) {
            _ascendMode = true;
            _tunnelMode = false;
            _inCave = false;
            _subTask = null;
            _caveExploreCooldownSteps = 0;
            retreatAndRedig(mod);
            Debug.logMessage("[MineDown] nether: hit bedrock floor zone (y="
                    + mod.getPlayer().getBlockPos().getY() + "), switching to ascending staircase back to y=" + _targetY + ".");
            Debug.logToFile("altoclef_diag.log", "[MineDown] nether bedrock escape: ascending from "
                    + mod.getPlayer().getBlockPos().toShortString() + " to target y=" + _targetY + ".");
        }

        if (_caveExploreCooldownSteps > 0) {
            _caveExploreCooldownSteps--;
        }

        if (_inCave) {
            // We found a cave while digging: prefer exploring inside it.
            // A watchdog bounds the detour so a huge cave network can't trap
            // us forever — after CAVE_EXPLORE_TIMEOUT seconds we resume digging.
            if (_subTask != null && !_subTask.isFinished(mod) && !_caveExploreTimer.elapsed()) {
                setDebugState("Exploring cave found while digging");
                return _subTask;
            }
            if (_caveExploreTimer.elapsed()) {
                Debug.logMessage("Cave exploration timed out after " + CAVE_EXPLORE_TIMEOUT + "s, resuming digging.");
                Debug.logToFile("altoclef_diag.log", "[MineDown-Cave] exploration timed out, resuming digging.");
            } else {
                Debug.logMessage("Cave exploration done, resuming digging.");
                Debug.logToFile("altoclef_diag.log", "[MineDown-Cave] exploration done (BiasedExplore finished), resuming digging.");
            }
            _inCave = false;
            _subTask = null;
            // (2026-09-03 区域记忆) 探索结束(超时/完成): 以当前玩家位置标记这个洞穴
            // 区域为"已探索", 半径 EXPLORED_CAVE_RADIUS 内不再触发探索 → 同一大洞网络
            // 不会被打转第二次(用户"同一个矿洞打转10分钟")。
            markExploredCaveRegion(mod, mod.getPlayer() != null ? mod.getPlayer().getBlockPos() : null);
            // (2026-09-02) 探索结束(超时/完成): 启动冷却防立刻重新探测同一洞;
            // 隧道模式下换一个水平方向继续挖, 避免原方向挖回刚探索过的洞区再次触发。
            _caveExploreCooldownSteps = CAVE_EXPLORE_COOLDOWN_STEPS;
            if (_tunnelMode) {
                retreatAndRedig(mod);
            }
        }

        // (2026-09-02 刷怪笼危险处理, 用户"先清怪再挖刷怪笼") 挖通道时感知到刷怪笼:
        // 有镐 → 先清 16 格内敌对怪再挖掉刷怪笼(粘性); 无镐 → 换向远离+记黑名单。
        // 放在探测洞/挖隧道之前, 危险源优先于一切探索。
        if (_spawnerPhase > 0) {
            if (_spawnerTarget == null || !WorldHelper.canBreak(mod, _spawnerTarget)) {
                // 目标已挖掉/不可挖: 处理完成, 记黑名单后恢复挖通道。
                if (_spawnerTarget != null) {
                    _avoidedSpawners.add(_spawnerTarget);
                }
                _spawnerPhase = 0;
                _spawnerTarget = null;
            } else if (_spawnerPhase == 1) {
                // 先清怪: 16 格内还有敌对怪 → 继续清; 清完切挖刷怪笼。
                if (hasHostileWithin(mod, HOSTILE_CLEAN_RANGE)) {
                    setDebugState("Clearing hostiles before mining spawner");
                    return new KillEntitiesTask(
                            e -> e instanceof HostileEntity
                                    && e.squaredDistanceTo(mod.getPlayer()) < HOSTILE_CLEAN_RANGE * HOSTILE_CLEAN_RANGE,
                            HostileEntity.class);
                }
                _spawnerPhase = 2;
            } else {
                // 挖刷怪笼: 破坏进度在 MobDefense 高威胁打断后由本状态机续挖 (粘性)。
                setDebugState("Mining spawner at " + _spawnerTarget.toShortString());
                return new DestroyBlockTask(_spawnerTarget);
            }
        } else {
            long now = System.currentTimeMillis();
            if (now - _lastSpawnerScanMs >= SPAWNER_SCAN_INTERVAL_MS) {
                _lastSpawnerScanMs = now;
                BlockPos spawner = findNearbySpawner(mod);
                if (spawner != null) {
                    // (2026-09-02 烈焰人刷怪笼保护, 用户"破坏刷怪笼会不会把烈焰人的
                    // 也破坏了") 下界堡垒的烈焰人刷怪笼是 CollectBlazeRodsTask 收集
                    // 烈焰棒的关键设施 — 破坏它会卡死主线(没烈焰棒做不了末影之眼,
                    // 也进不了末地)。检测到烈焰人刷怪笼 → 记入已处理集(本任务不再
                    // 碰), 绝不破坏/远离。
                    if (isProtectedSpawner(mod, spawner)) {
                        _avoidedSpawners.add(spawner);
                        Debug.logToFile("altoclef_diag.log",
                                "[Spawner] blaze spawner at " + spawner.toShortString()
                                        + " protected (needed for blaze rods), NOT breaking it.");
                    } else if (StorageHelper.miningRequirementMet(mod, MiningRequirement.WOOD)) {
                        _spawnerTarget = spawner;
                        _spawnerPhase = 1;
                        Debug.logMessage("[Spawner] found spawner at " + spawner.toShortString()
                                + ", clearing hostiles then mining it.");
                        Debug.logToFile("altoclef_diag.log",
                                "[Spawner] found spawner at " + spawner.toShortString()
                                        + ", clearing hostiles then mining it.");
                    } else {
                        // 无镐: 换向远离 + 记黑名单 (徒手挖不动 SPAWNER, 硬挖会被刷怪打死)。
                        _avoidedSpawners.add(spawner);
                        retreatAndRedig(mod);
                        Debug.logMessage("[Spawner] no pickaxe, retreating from spawner at "
                                + spawner.toShortString());
                        Debug.logToFile("altoclef_diag.log",
                                "[Spawner] no pickaxe, retreating from spawner at "
                                        + spawner.toShortString());
                    }
                }
            }
        }

        // Check if we should keep digging.
        if (reachedBottom(mod)) {
            // (2026-08-31 进矿洞却卡 shaft done, 用户重即"进入矿洞不动") 到井底/挖穿后,
            // 洞穴检测原在 reachedBottom 之后、且受 _step%3 门控 → 井底 _step 不推进,
            // findNearbyCave 永不走 → 挖穿进洞也不触发探索, 反复 "Shaft done" 空转卡死。
            // 这里在判定"结束"前先扫一次: 井底已有连通洞空气(从洞口可达)→ 进洞探索,
            // 否则才真正 shaft done。
            if (!_inCave && _caveExploreCooldownSteps <= 0) {
                Optional<BlockPos> cave = findNearbyCave(mod);
                if (cave.isPresent()) {
                    _inCave = true;
                    BlockPos anchor = cave.get();
                    // (2026-09-07 FIRE 时即标记, 用户"刚转去挖洞没一会又被同一个洞吸引去探索")
                    // 原设计只在探索结束(超时/完成)时标记, 但链重建会中途丢弃探索任务导致
                    // mark 难产 → 同洞反复吸引。改为 FIRE 承诺探索时立即标记洞锚点;
                    // 本次探索不受影响(ExploreCaveTask 选点豁免区域记忆), 探索结束路径的
                    // 标记保留(玩家最终位置与锚点相距>24 时多记一个中心, 覆盖更全)。
                    markExploredCaveRegion(mod, anchor);
                    _lastFireAnchor = anchor.toImmutable();
                    Debug.logMessage("Found cave at shaft bottom at " + anchor.toShortString() + ", exploring it.");
                    _caveExploreTimer.reset();
                    // (2026-09-01 真正进洞) 专用洞内导航任务, 不再用 BiasedExploreTask
                    // (其落点被强制抬到 48 格外地表, 从未真正走进洞里)。
                    _subTask = new ExploreCaveTask(_targetY);
                    return _subTask;
                }
            }
            setDebugState("Shaft done, no ores found.");
            // FIX (stuck bug): if a cave exploration interrupted the FINAL step,
            // the queue may still hold undug blocks. isFinished used to require an
            // empty queue, so the task could never finish while onTick returned
            // null here — the bot stood still forever with "Shaft done" on screen.
            _digQueue.clear();
            return null;
        }

        // Watch for caves periodically.
        // (2026-09-02 用户"钻石任务应到 -24 却被矿洞探索接替") 仅 TUNNEL 模式
        // (已到目标深度, 水平隧道挖矿) 才中途探测洞; 楼梯下沉模式(起始高于目标
        // 深度)先沉到目标深度再探测, 否则浅层大洞网络会一直抢占探索, 永不沉到
        // 钻石层 y=-24 (日志 17:01 at 63,33,428 / 51,33,406 浅层反复触发实锤)。
        // 冷却内不探测(防同一洞反复)。
        if (_tunnelMode && _caveExploreCooldownSteps <= 0 && _step % CAVE_SCAN_EVERY_STEPS == 0) {
            Optional<BlockPos> cave = findNearbyCave(mod);
            if (cave.isPresent()) {
                _inCave = true;
                BlockPos anchor = cave.get();
                // (2026-09-07 FIRE 时即标记, 同 reachedBottom 分支注释)。
                markExploredCaveRegion(mod, anchor);
                _lastFireAnchor = anchor.toImmutable();
                Debug.logMessage("Found cave while digging at " + anchor.toShortString() + ", exploring it.");
                Debug.logToFile("altoclef_diag.log",
                        "[MineDown-Cave] found cave while digging at " + anchor.toShortString() + ", exploring it first.");
                _caveExploreTimer.reset();
                // (2026-09-01 真正进洞) 专用洞内导航任务: 直奔最远天然洞空气,
                // 到达后重扫跟深(见 ExploreCaveTask), 25s 看门狗兜底不影响挖矿主线。
                // (2026-09-02 传 targetY) 导航目标限目标深度附近, 不导航到上方浅层。
                _subTask = new ExploreCaveTask(_targetY);
                return _subTask;
            }
        }

        // Advance the staircase when the current step is fully dug out.
        // WATER FIX: DestroyBlockTask can never "break" a water block (attacking
        // water does nothing), so if the queue head was water the bot used to
        // freeze forever trying to dig it. Water/air blocks at the head are
        // simply skipped — we dig the solid blocks BEHIND the water instead
        // (slow but works underwater).
        // (2026-09-10 空跳修复) 先记账再弹队列: 上一 tick 派发的 DestroyBlockTask
        // 目标格此刻已变空气 ⇒ 真实挖掉, 消耗 1 格预算。必须放在 skipAirAndWater
        // 之前 — 被挖掉的格马上会从队首弹走, 错过这次判定预算就永远不消耗。
        if (_miningTarget != null) {
            if (WorldHelper.isAir(mod, _miningTarget)) {
                _realDigs++;
            }
            _miningTarget = null;
        }
        skipAirAndWater(mod);
        // FAST-FORWARD: if the whole step was already mined (re-entering our
        // own tunnel), advance immediately instead of one step per tick.
        // (2026-09-10 空跳修复) 空跳只推进几何路径 _step, 不消耗真实挖掘预算;
        // 单 tick 空跳有上限, 巨型空腔分多个 tick 跳, 不挂死单 tick。
        advanceToSolid(mod);
        // (2026-09-10 液体预判闸门删除, 用户"就算有水也要直直挖, 真的看到水了才躲开")
        // 旧闸门用 isAdjacentToLiquid 检查挖掘目标 6 个相邻格的流体 —— 这是隔墙
        // 透视: 水藏在未挖的石头后面 bot 也"知道", 违反反透视原则。实况
        // (altoclef_chat-20260910-114004): bot 重入被水灌满的旧隧道网, 队首跳过水
        // 块后落到的实体石头周围全是水 → 四方向全命中 → 一 tick 内 8 次退避打满
        // → "too many liquid encounters" 放弃 → 父层 5s 重建 → 站桩死循环。
        // 新语义: 直直挖。水出现在挖掘路径上由 skipAirAndWater 处理 (走过去 +
        // [Diag-water] 记录) —— 这才是"真的看到水"。隐藏在水后石头里的液体照
        // 人类认知处理: 不知道, 挖穿了再说。
        if (_digQueue.isEmpty()) {
            return null;
        }

        // (2026-08-30 探洞镐门槛, 用户"探洞不检查镐子不合理") 探洞就是挖石头找矿。
        // 直接 DestroyBlockTask 手挖石头: 石头硬度高, 主手无镐/拿方块极慢, 探洞形同
        // 废了 (用户实测: 背包有铁/木头却手挖)。与主线挖石头(MineAndCollectTask 的
        // onResourceTick)保持一致: 挖前先保证有镐, 没有就去 SatisfyMiningRequirement
        // 合成。WOOD=木镐及以上即可挖石头, 保底最低成本、立即能解锁, 主线挖矿时再升级。
        if (!StorageHelper.miningRequirementMet(mod, MiningRequirement.WOOD)) {
            return new SatisfyMiningRequirementTask(MiningRequirement.WOOD);
        }

        // (2026-09-10 空跳修复) 进度显示改为真实挖掘预算消耗 (dug X/Y),
        // _step 已是几何路径索引, 空跳后会远超步数上限, 显示出来有误导性。
        int budget = (_ascendMode ? MAX_ASCEND_DIG_STEPS : MAX_DIG_STEPS) * BLOCKS_PER_STEP;
        setDebugState((_ascendMode ? "Climbing back up to target Y (dug "
                : _tunnelMode ? "Digging a tunnel to find ores (dug "
                : "Digging down to find ores (dug ") + _realDigs + "/" + budget + ")");
        _miningTarget = _digQueue.peek();
        return new DestroyBlockTask(_miningTarget);
    }

    /**
     * 放弃当前挖掘轨迹，从玩家当前位置随机换一个水平方向
     * （与旧方向不同）重新开始楼梯/隧道。
     */
    private void retreatAndRedig(AltoClef mod) {
        _digQueue.clear();
        _step = 0;
        _skipStreak = 0;
        _miningTarget = null;
        _digStart = mod.getPlayer().getBlockPos().down();
        int[] newDir;
        do {
            newDir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
        } while (newDir[0] == _dirX && newDir[1] == _dirZ);
        _dirX = newDir[0];
        _dirZ = newDir[1];
    }

    /**
     * (2026-09-10 空跳修复) 沿几何路径推进, 直到队首是实体方块 (或预算耗尽 /
     * 单 tick 空跳上限)。核心不变量: 空跳只 _step++ (几何路径索引), 绝不动
     * _realDigs (真实挖掘预算) — 旧实现 "重入旧自挖隧道 → 空 40 步一 tick
     * 刷完 → reachedBottom 判到底 → 'Shaft done' 收工 → 父层 5s 重建 →
     * 无限空转" 的死循环由此根除: 预算未耗尽时路径会一直延伸, 挖到路径上
     * 第一块实体方块为止。
     */
    private void advanceToSolid(AltoClef mod) {
        while (_digQueue.isEmpty() && !reachedBottom(mod) && _skipStreak < SKIP_STEPS_PER_TICK) {
            _step++;
            _skipStreak++;
            enqueueNextStep();
            skipAirAndWater(mod);
        }
        if (!_digQueue.isEmpty()) {
            // 跳了很远才摸到实体方块: 打一条诊断, 便于现场确认空跳修复生效。
            if (_skipStreak >= MAX_DIG_STEPS) {
                Debug.logToFile("altoclef_diag.log", "[MineDown-FF] skipped " + _skipStreak
                        + " air steps, first solid at " + _digQueue.peek().toShortString()
                        + " (realDigs=" + _realDigs + ", step=" + _step + ")");
            }
            _skipStreak = 0;
        } else if (_skipStreak >= SKIP_STEPS_PER_TICK) {
            // 单 tick 上限打满仍未摸到实体: 清零, 下个 tick 继续跳。
            _skipStreak = 0;
        }
    }

    private void skipAirAndWater(AltoClef mod) {
        while (!_digQueue.isEmpty()
                && (WorldHelper.isAir(mod, _digQueue.peek()) || isWater(mod, _digQueue.peek()))) {
            if (isWater(mod, _digQueue.peek())) {
                logWaterEncounter(mod, _digQueue.peek());
            }
            _digQueue.poll();
        }
    }

    private void enqueueNextStep() {
        // Each step: the block forward(-up for ascend stairs) + the block above it (headroom).
        // (自挖记录不在此做: 入队≠挖开, 废弃队列会误标真洞空气。真实挖开由
        //  DestroyBlockTask.isFinished 中央记录, 见 recordBrokenBlock。)
        int k = _step;
        BlockPos ground;
        if (_ascendMode) {
            // (2026-09-05 下界基岩层逃生) 45° 楼梯【向上】挖回目标高度, 可步行走下。
            ground = _digStart.add(k * _dirX, k, k * _dirZ);
        } else if (_tunnelMode) {
            // Descend 1 block every TUNNEL_DESCEND_EVERY steps (walkable slope),
            // clamped at the floor so we sink toward the target band and then
            // branch-mine flat. (2026-09-05 下界高度概念) 下界 floor=目标高度 → 不缓降,
            // 平挖 — 主世界 TUNNEL_MIN_Y=-24 是钻石层逻辑, 下界照搬会一路挖到基岩。
            int floorY = _nether ? _targetY : TUNNEL_MIN_Y;
            int drop = Math.max(0, Math.min(_digStart.getY() - floorY, k / TUNNEL_DESCEND_EVERY));
            ground = _digStart.add(k * _dirX, -drop, k * _dirZ);
        } else {
            ground = _digStart.add(k * _dirX, -k, k * _dirZ);
        }
        _digQueue.add(ground.up());
        _digQueue.add(ground);
    }

    private boolean isWater(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).getBlock() instanceof net.minecraft.block.FluidBlock;
    }

    /**
     * [Diag-water] log every water encounter (throttled to the first few +
     * every 10th, so a flooded shaft doesn't spam the log).
     */
    private void logWaterEncounter(AltoClef mod, BlockPos pos) {
        _waterSkipped++;
        if (_waterSkipped <= 3 || _waterSkipped % 10 == 0) {
            String m = "[Diag-water] shaft/tunnel hit water at " + pos.toShortString()
                    + " (y=" + pos.getY() + ", skipped so far: " + _waterSkipped + ")"
                    + " player=" + mod.getPlayer().getBlockPos().toShortString()
                    + " mode=" + (_tunnelMode ? "tunnel" : "staircase")
                    + " step=" + _step + "/" + MAX_DIG_STEPS;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
    }

    /**
     * (2026-09-02 刷怪笼危险处理) 近场(SPAWNER_SCAN_RANGE=4)扫 SPAWNER 方块
     * (chunk 已加载即可, "看到就感知", 无需 FOV/LOS — 危险源主动识别, 类似床)。
     * 已避让/已挖掉的刷怪笼在黑名单中, 不再重复处理。
     */
    private BlockPos findNearbySpawner(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return null;
        BlockPos player = mod.getPlayer().getBlockPos();
        final int R = SPAWNER_SCAN_RANGE;
        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (int dx = -R; dx <= R; dx++) {
            for (int dy = -R; dy <= R; dy++) {
                for (int dz = -R; dz <= R; dz++) {
                    BlockPos p = player.add(dx, dy, dz);
                    if (_avoidedSpawners.contains(p)) continue;
                    if (!mod.getChunkTracker().isChunkLoaded(p)) continue;
                    if (mod.getWorld().getBlockState(p).getBlock() == Blocks.SPAWNER) {
                        double dSq = p.getSquaredDistance(player);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** (2026-09-02 刷怪笼危险处理) 玩家 range 内是否有存活的敌对实体。 */
    private boolean hasHostileWithin(AltoClef mod, double range) {
        if (mod.getPlayer() == null) return false;
        double rangeSq = range * range;
        for (Entity e : mod.getEntityTracker().getHostiles()) {
            if (e.isAlive() && e.squaredDistanceTo(mod.getPlayer()) < rangeSq) return true;
        }
        return false;
    }

    /**
     * (2026-09-02 烈焰人刷怪笼保护) 下界烈焰棒任务依赖烈焰人刷怪笼持续刷烈焰人,
     * 破坏它会卡死主线(没烈焰棒 → 做不了末影之眼 → 进不了末地)。是烈焰人刷怪笼
     * → 本任务绝不处理(保护)。非烈焰人(僵尸/骷髅/蜘蛛等)地牢刷怪笼正常清源。
     */
    private boolean isProtectedSpawner(AltoClef mod, BlockPos pos) {
        return WorldHelper.getSpawnerEntity(mod, pos) instanceof BlazeEntity;
    }

    private boolean reachedBottom(AltoClef mod) {
        // Pre-start safety: isFinished() may be called before onStart() (the task
        // framework evaluates candidate subtasks before swapping them in — if the
        // swap is blocked by canBeInterrupted, the task sits unstarted). An
        // unstarted shaft is by definition not at the bottom yet.
        if (_digStart == null) return false;
        // (2026-09-05 上行模式步数上限更宽: 从 y≈5 爬回 y=70 需约 66 步)
        // (2026-09-10 空跳修复) 预算按【真实挖掉方块数】计 (每步 2 格):
        // 几何索引 _step 会被 FAST-FORWARD 空跳刷高, 不代表任何真实挖掘 —
        // 10:47 场次: 重入旧自挖隧道, 空 40 步一 tick 刷完 → 旧逻辑判"到底"
        // → "Shaft done" → 父层 5s 重建 → 无限空转。空跳不再能宣告完工。
        if (_realDigs >= (_ascendMode ? MAX_ASCEND_DIG_STEPS : MAX_DIG_STEPS) * BLOCKS_PER_STEP) return true;
        // Tunnel mode: only the step limit applies (we're already at depth).
        if (_tunnelMode) return false;
        // (2026-09-05 下界基岩层逃生) 上行模式: 爬回目标高度即完成。
        if (_ascendMode) return mod.getPlayer().getBlockPos().getY() >= _targetY;
        // FIX (task ping-pong): use the player's ACTUAL Y, not the old
        // `_digStart.getY() - _step` formula. The step counter increments even
        // when the step's blocks were ALREADY air (re-entering a previously dug
        // tunnel), so the formula reported a "ghost depth" the player never
        // reached. The task then finished at the ghost depth, the parent created
        // a NEW MineDownExploreTask at nearly the same spot, whose step 1/2
        // blocks were the already-mined tunnel blocks — the bot ping-ponged
        // between "dig 2 already-gone blocks" task instances forever
        // (see task_switch.log 10:04:54-10:04:56).
        return mod.getPlayer().getBlockPos().getY() <= _targetY;
    }

    /**
     * (中央记录入口, 由 DestroyBlockTask 调用) 记录一个本 mod【真实挖开】的方块位置:
     * 任务开始时是实心、结束时变空气 ⇒ 这格空气是我们造的, 不是天然洞空气。
     * 同时追加持久化文件, 跨游戏重启(新会话)后旧隧道仍被认得, 不再被当天然洞。
     */
    public static void recordBrokenBlock(AltoClef mod, BlockPos pos) {
        if (mod.getWorld() == null || pos == null) return;
        ensureSelfDugLoaded();
        String dim = selfDugDimKey(mod);
        java.util.LinkedHashSet<BlockPos> set = _selfDugByDim.computeIfAbsent(dim, k -> new java.util.LinkedHashSet<>());
        if (!set.add(pos.toImmutable())) return; // 已记录
        appendSelfDugLine(dim, pos);
        if (set.size() > MAX_SELF_DUG_MEMORY) {
            // 容量封顶: 逐出最旧并整体重写文件(罕见操作)。
            java.util.Iterator<BlockPos> it = set.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
            rewriteSelfDugFile();
        }
    }

    /**
     * 该位置是否为本 mod 自己挖出来的空气(查当前维度的持久化记忆)。
     */
    static boolean isSelfDugAir(AltoClef mod, BlockPos pos) {
        if (mod.getWorld() == null) return false;
        ensureSelfDugLoaded();
        java.util.Set<BlockPos> set = _selfDugByDim.get(selfDugDimKey(mod));
        return set != null && set.contains(pos);
    }

    private static String selfDugDimKey(AltoClef mod) {
        return mod.getWorld().getRegistryKey().getValue().toString();
    }

    private static java.nio.file.Path selfDugFile() {
        // 必须用 runDirectory(游戏目录 D:\minecraft\.minecraft), 相对 CWD 会找不到文件。
        return MinecraftClient.getInstance().runDirectory.toPath().resolve(SELF_DUG_FILE);
    }

    private static void ensureSelfDugLoaded() {
        if (_selfDugLoaded) return;
        _selfDugLoaded = true;
        try {
            java.nio.file.Path f = selfDugFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 4) continue;
                try {
                    BlockPos p = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    _selfDugByDim.computeIfAbsent(parts[0], k -> new java.util.LinkedHashSet<>()).add(p);
                } catch (NumberFormatException ignored) {
                }
            }
            Debug.logMessage("[SelfDug] loaded " + _selfDugByDim.values().stream().mapToInt(Set::size).sum()
                    + " self-dug positions from " + SELF_DUG_FILE);
        } catch (Exception e) {
            Debug.logMessage("[SelfDug] failed to load " + SELF_DUG_FILE + ": " + e.getMessage());
        }
    }

    private static void appendSelfDugLine(String dim, BlockPos pos) {
        try {
            java.nio.file.Files.write(selfDugFile(),
                    (dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 记录失败不阻断挖掘, 只是跨会话记忆缺一格。
            Debug.logMessage("[SelfDug] failed to append: " + e.getMessage());
        }
    }

    private static void rewriteSelfDugFile() {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, java.util.LinkedHashSet<BlockPos>> e : _selfDugByDim.entrySet()) {
                for (BlockPos p : e.getValue()) {
                    sb.append(e.getKey()).append(' ').append(p.getX()).append(' ')
                            .append(p.getY()).append(' ').append(p.getZ()).append('\n');
                }
            }
            java.nio.file.Files.write(selfDugFile(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Debug.logMessage("[SelfDug] failed to rewrite: " + e.getMessage());
        }
    }

    // =========================================================================
    // 已探索洞穴区域: key = "存档名:维度", 跨会话持久化到 altoclef_explored.log。
    // =========================================================================

    /**
     * (2026-09-03 存档+维度隔离, 用户"不同世界不要共享") 区域 key = 存档名 + ':' + 维度。
     * 存档名取集成服务器 LevelName("新的世界"), 与维度(overworld/nether/the_end)拼接,
     * 不同存档/维度各自维护已探索区域。集成服务器不可用时(启动早期/主菜单)退化为维度。
     */
    // (2026-09-07 FIRE 时标记配套) 最近一次 FIRE 的洞锚点与"探索中"查询:
    // 供 MineAndCollectTask.createNextExplorationTask 在链重建(父任务对象被
    // isEqual 判异重建)后认领仍在探索中的本任务(断点交接), 防止 _explorationTask
    // 随父对象丢失导致探索进度作废、同洞反复吸引。
    private static BlockPos _lastFireAnchor;
    public boolean isExploringCave() { return _inCave; }
    public boolean isNetherTask() { return _nether; }
    public static BlockPos getLastFireAnchor() { return _lastFireAnchor; }

    private static String exploredCaveKey(AltoClef mod) {
        String levelName = "?";
        try {
            var server = MinecraftClient.getInstance().getServer();
            if (server != null && server.getSaveProperties() != null && server.getSaveProperties().getLevelName() != null) {
                levelName = server.getSaveProperties().getLevelName();
            }
        } catch (Exception ignored) {
        }
        return levelName + ":" + mod.getWorld().getRegistryKey().getValue().toString();
    }

    private static java.nio.file.Path exploredCaveFile() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve(EXPLORED_CAVE_FILE);
    }

    private static void ensureExploredCaveLoaded() {
        if (_exploredCaveLoaded) return;
        _exploredCaveLoaded = true;
        try {
            java.nio.file.Path f = exploredCaveFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 4) continue;
                try {
                    BlockPos p = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    _exploredCaveByKey.computeIfAbsent(parts[0], k -> new java.util.LinkedHashSet<>()).add(p);
                } catch (NumberFormatException ignored) {
                }
            }
            Debug.logMessage("[ExploredCave] loaded "
                    + _exploredCaveByKey.values().stream().mapToInt(Set::size).sum()
                    + " explored-cave regions from " + EXPLORED_CAVE_FILE);
        } catch (Exception e) {
            Debug.logMessage("[ExploredCave] failed to load " + EXPLORED_CAVE_FILE + ": " + e.getMessage());
        }
    }

    private static void appendExploredCaveLine(String key, BlockPos pos) {
        try {
            java.nio.file.Files.write(exploredCaveFile(),
                    (key + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Debug.logMessage("[ExploredCave] failed to append: " + e.getMessage());
        }
    }

    /**
     * (2026-09-03 记录一个已探索洞穴区域) 探索结束(超时/完成)时以玩家位置为圆心,
     * 记录 EXPLORED_CAVE_RADIUS 半径内的洞穴为"不再触发探索"。与已有中心距离不足
     * EXPLORED_CAVE_MIN_SEP 则视为重复(同一片洞区)丢弃, 防密集堆点。
     */
    private static void markExploredCaveRegion(AltoClef mod, BlockPos center) {
        if (mod == null || mod.getWorld() == null || center == null) return;
        ensureExploredCaveLoaded();
        String key = exploredCaveKey(mod);
        java.util.LinkedHashSet<BlockPos> set = _exploredCaveByKey.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>());
        // 与已有中心过近 → 同片洞区已覆盖, 丢弃(防每 90s 超时在同一点重复追加)。
        for (BlockPos existing : set) {
            if (center.getSquaredDistance(existing) < EXPLORED_CAVE_MIN_SEP * EXPLORED_CAVE_MIN_SEP) {
                return;
            }
        }
        set.add(center.toImmutable());
        appendExploredCaveLine(key, center);
        Debug.logToFile("altoclef_diag.log",
                "[ExploredCave] marked region center=" + center.toShortString()
                        + " radius=" + EXPLORED_CAVE_RADIUS + " key=" + key
                        + " totalCenters=" + set.size());
    }

    /**
     * (2026-09-03 判定 pos 是否落在任一已探索洞穴区域内) 供探测/目标选择排除用。
     */
    private static boolean isInExploredCaveRegion(AltoClef mod, BlockPos pos) {
        if (mod == null || mod.getWorld() == null || pos == null) return false;
        ensureExploredCaveLoaded();
        java.util.Set<BlockPos> set = _exploredCaveByKey.get(exploredCaveKey(mod));
        if (set == null || set.isEmpty()) return false;
        int rSq = EXPLORED_CAVE_RADIUS * EXPLORED_CAVE_RADIUS;
        for (BlockPos center : set) {
            if (pos.getSquaredDistance(center) <= rSq) return true;
        }
        return false;
    }

    /**
     * 玩家当前连通空间里【离参考点 fromRef 最远的天然空气】(排除自挖位置、
     * y≤CAVE_NATURAL_AIR_MAX_Y、排除调用方已访问/已失败的点)。
     *
     * (2026-09-01 策略修正, 用户"随机探索原地打转") 旧版从【玩家当前位置】取最远:
     * collectConnectedAir 只扫 ±8 格, 目标总在近旁, 且"最远"随玩家移动左右翻转
     * → 在小空间里来回绕圈。改为:
     *   - BFS 仍以玩家当前位置为中心(只认连通可达的空气, 防透视);
     *   - 距离参考点 fromRef 固定为【探索起点】 — 每次选点都朝"离入口更远"的方向
     *     推, 目标一致向外, 不再左右横跳;
     *   - exclude 由调用方传入 visited ∪ failed — 到过的点绝不回头。
     * 无天然空气 → null(洞已探索完/根本不是洞)。
     */
    static BlockPos pickFarthestNaturalCaveAir(AltoClef mod, BlockPos center, BlockPos fromRef, Set<BlockPos> exclude, int maxY) {
        return pickFarthestNaturalCaveAir(mod, center, fromRef, exclude, maxY, false);
    }

    /**
     * (2026-09-07 FIRE 时标记配套) ignoreExploredRegions=true 供 ExploreCaveTask 使用:
     * 探索目标选择豁免区域记忆 — FIRE 时已把本洞标记为已探索, 若选点仍排除该区域,
     * 本次探索自己的目标会被剔空而立即结束。探索任务天然只由 FIRE 创建, 恒豁免。
     */
    static BlockPos pickFarthestNaturalCaveAir(AltoClef mod, BlockPos center, BlockPos fromRef, Set<BlockPos> exclude, int maxY, boolean ignoreExploredRegions) {
        // 探索目标选择用更远的 EXPLORE_SCAN_RADIUS, 探索段更长(用户"得调远一点")。
        Set<BlockPos> reachable = collectConnectedAir(mod, center, EXPLORE_SCAN_RADIUS);
        // (2026-09-02 目标深度上界) 导航目标也只选目标深度附近的天然空气, 防止
        // 钻石任务在洞内向外推时把目标选到上方浅层, 离开钻石层。
        int naturalMaxY = Math.min(CAVE_NATURAL_AIR_MAX_Y, maxY);
        BlockPos best = null;
        double bestDistSq = Double.NEGATIVE_INFINITY;
        for (BlockPos p : reachable) {
            if (p.getY() > naturalMaxY) continue;
            if (isSelfDugAir(mod, p)) continue;
            if (exclude != null && exclude.contains(p)) continue;
            // (2026-09-03 区域记忆) 探索目标也不落到已探索洞穴区域 → 不会导航回刚
            // 探索完的洞区(用户"同一个矿洞打转", 双向排除: 探测 + 目标选择)。
            if (!ignoreExploredRegions && isInExploredCaveRegion(mod, p)) continue;
            double d = p.getSquaredDistance(fromRef);
            if (d > bestDistSq) {
                bestDistSq = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Look around the player for an open cave space (CAVE_AIR), preferring
     * positions below/at eye level since that's where we're digging toward.
     * Returns the FARTHEST qualifying cave air from the player so the
     * exploration bias pulls the bot INTO the cave, not just to its mouth.
     */
    /**
         * (2026-08-31) BFS 收集玩家当前所在开放空间的全部空气块(普通 air + cave_air),
         * 半径 ±CAVE_SCAN_RADIUS、容量封顶, 供 findNearbyCave 判断洞空气是否「已挖开/可达」。
         */
        static Set<BlockPos> collectConnectedAir(AltoClef mod, BlockPos start) {
            return collectConnectedAir(mod, start, CAVE_SCAN_RADIUS);
        }

        /**
         * (2026-09-01 探索半径分离) 以 radius 为水平界限 BFS 收集与 start 连通的空气。
         * 检测(findNearbyCave)用 CAVE_SCAN_RADIUS(8, 防误判), 探索目标选择
         * (pickFarthestNaturalCaveAir)用 EXPLORE_SCAN_RADIUS(16, 探索段更长)。
         */
        static Set<BlockPos> collectConnectedAir(AltoClef mod, BlockPos start, int radius) {
            Set<BlockPos> visited = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty() && visited.size() < 512) {
                BlockPos cur = queue.poll();
                if (cur == null || !visited.add(cur)) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos n = cur.add(dx, dy, dz);
                            if (Math.abs(n.getX() - start.getX()) > radius
                                    || Math.abs(n.getZ() - start.getZ()) > radius) continue;
                            net.minecraft.block.Block nb = mod.getWorld().getBlockState(n).getBlock();
                            if (nb == Blocks.AIR || nb == Blocks.CAVE_AIR) {
                                queue.add(n);
                            }
                        }
                    }
                }
            }
            return visited;
        }

        // (2026-09-01 三维开阔度) 连通空气集合的 X/Z/Y 跨度是否都达到阈值。
        // 自挖楼梯(1-2格宽、2格高)至少一个方向跨度不足 → false, 不会把自己
        // 挖的通道当成天然洞。天然洞/繁茂洞穴 X/Z/Y 都开阔 → true。
        private boolean isOpenCaveSpace(Set<BlockPos> airs) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : airs) {
                minX = Math.min(minX, p.getX());
                maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxZ = Math.max(maxZ, p.getZ());
            }
            return maxX - minX >= CAVE_OPEN_SPAN_X
                    && maxZ - minZ >= CAVE_OPEN_SPAN_Z
                    && maxY - minY >= CAVE_OPEN_SPAN_Y;
        }

        private Optional<BlockPos> findNearbyCave(AltoClef mod) {
        BlockPos center = mod.getPlayer().getBlockPos();
        // (2026-09-01 矿洞深度感知, 用户"刚挖进去头上有方块就又检测到外面是矿洞")
        // 露天闸门不够: 玩家刚往下挖几格头顶就有方块(不露天), 但玩家仍在浅层,
        // 旁边/上方就是地表空气 → 仍误判"发现矿洞"。人类刚挖进地下时知道自己还
        // 在浅层, 不会"发现矿洞" — 洞检测必须要求玩家已挖到真正的地下矿洞层:
        // 玩家 y 低于 CAVE_EXPLORE_MIN_DEPTH_Y 才允许检测。地表/浅层(y 高)即使
        // 头顶有方块(挖开的楼梯)也不检测。
        if (center.getY() > CAVE_EXPLORE_MIN_DEPTH_Y) {
            logCaveScanDiag(mod, center, "REJECT depth y=" + center.getY() + ">" + CAVE_EXPLORE_MIN_DEPTH_Y);
            return Optional.empty();
        }
        // (2026-09-01 露天不检测洞, 二次保险) 玩家头顶无实心覆盖(露天) → 关闭检测。
        // 深度闸门放行后(已在地下)本判据几乎恒真, 但保留防"挖穿到地表天窗"等
        // 罕见场景。树冠不算顶(树下仍算露天)。
        // (2026-09-07 高 cavern 修复, 用户"洞边缘不触发") 24 格仰扫在大奶酪洞里
        // 会全程空气 → 深地大洞被误判"露天"而整局关闸(10:24 复现实锤: REJECT
        // no-cover c=251,-43,201)。主世界 y<0 物理上不可能露天(地表 y≥60), 下界
        // 探测带 y≤45 也在顶盖(y=127)与大空腔(y≈90+)之下 → 这两种场景跳过本闸门,
        // 地表误判防护仍由深度闸门(y≤45)承担。
        boolean openSkyImpossible = _nether || center.getY() < 0;
        if (!openSkyImpossible && !hasOverheadCover(mod, center)) {
            logCaveScanDiag(mod, center, "REJECT no-cover (24格内无实心顶)");
            return Optional.empty();
        }
        // (2026-08-31 挖穿才进洞探索, 用户"挖一半墙后探测到洞就触发探索, 不让挖方块还绕远路")
        // 只认「与玩家开放空间连通」的洞空气: 墙后隔着实心、还没挖开的洞空气不连通 →
        // 不触发探索, 继续挖穿; 一旦挖开/从洞口进入, 洞空气进入连通集 → 才触发 BiasedExplore
        // 进洞。杜绝"挖一半被墙后预知触发的探索抢走挖矿、绕远路"。
        Set<BlockPos> reachableAir = collectConnectedAir(mod, center);
        // (2026-09-01 自挖块排除, 核心闸门) 连通空气里只认【天然空气】:
        // 排除本 mod 静态记录的自挖位置(多条自挖隧道缝合的跨度再大也不算),
        // 排除 y>CAVE_NATURAL_AIR_MAX_Y 的高处空气(防自挖楼梯连到地表被计入)。
        // 自挖网络天然空气≈0 → 数量闸门直接拦下; 挖穿进真洞 → 洞内天然空气
        // 经自挖口连通进集合, 数量+三维跨度均达标 → 正常触发。
        Set<BlockPos> naturalAir = new HashSet<>();
        // (2026-09-02 目标深度上界) 只认目标深度附近的天然空气: 钻石任务在 -24 时,
        // -8 以上的浅层洞空气不计入 — 防止"下挖到合法深度却被上方矿洞导航走"。
        int naturalMaxY = Math.min(CAVE_NATURAL_AIR_MAX_Y, _targetY + CAVE_ABOVE_TARGET_BUFFER);
        // (2026-09-06 闸门级诊断) 逐原因计数: 天然空气不足时能直接看出是被
        // 高度上界/自挖记忆/已探索区域哪一方吃掉的, 以及 BFS 512 上限是否饱和。
        int aboveMaxY = 0, selfDugCount = 0, exploredCount = 0;
        for (BlockPos p : reachableAir) {
            if (p.getY() > naturalMaxY) { aboveMaxY++; continue; }
            if (isSelfDugAir(mod, p)) { selfDugCount++; continue; }
            // (2026-09-03 区域记忆) 排除已探索洞穴区域内的空气: 整片洞区上次已探索完,
            // 本次连同 team 一起剔除 → 不再触发进洞 (用户"同一个矿洞打转", 防反复进)。
            if (isInExploredCaveRegion(mod, p)) { exploredCount++; continue; }
            naturalAir.add(p);
        }
        if (naturalAir.size() < MIN_NATURAL_CAVE_AIR) {
            // (2026-09-06 闸门级诊断) 取代原 10 秒节流的 suppression 单行: 补充
            // reachable 规模(BFS 512 上限是否打满)、逐原因剔除计数与目标深度上界。
            logCaveScanDiag(mod, center, "REJECT naturalAir=" + naturalAir.size() + "<" + MIN_NATURAL_CAVE_AIR
                    + " reachable=" + reachableAir.size() + (reachableAir.size() >= 512 ? "(CAP HIT)" : "")
                    + " aboveMaxY=" + aboveMaxY + " selfDug=" + selfDugCount + " explored=" + exploredCount
                    + " targetY=" + _targetY + " naturalMaxY=" + naturalMaxY);
            return Optional.empty();
        }
        // (2026-09-01 三维开阔度判定, 用户"又把自己的洞当成矿洞") 天然洞与
        // 通道的本质区别是【空间形状】: 天然洞在 X/Z/Y 三维都开阔。跨度判定
        // 现在只统计天然空气 — 自挖楼梯/隧道即使连通集合庞大, 天然空气侧
        // 约束为零。不依赖空气类型(cave_air vs 普通 air), 繁茂洞穴自然覆盖。
        if (!isOpenCaveSpace(naturalAir)) {
            // (2026-09-06 闸门级诊断) 静默拒绝点: 打出三轴实际跨度, 直接看差多少。
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : naturalAir) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            logCaveScanDiag(mod, center, "REJECT spans X=" + (maxX - minX) + " Z=" + (maxZ - minZ)
                    + " Y=" + (maxY - minY) + " natural=" + naturalAir.size());
            return Optional.empty();
        }
        BlockPos best = null;
        double bestDistSq = Double.NEGATIVE_INFINITY;
        int noNeighbor = 0;
        for (BlockPos check : naturalAir) {
            // 只认与玩家连通的空间(reachableAir 已保证, 防透视: 墙后未挖开的洞空气不连通, 不触发)。
            // Require a couple of adjacent air blocks so a single hole doesn't count as a cave.
            if (!hasCaveNeighbors(mod, check, 2)) { noNeighbor++; continue; }
            double distSq = check.getSquaredDistance(center);
            if (distSq > bestDistSq) {
                bestDistSq = distSq;
                best = check;
            }
        }
        // (2026-09-06 闸门级诊断) 取代原 200 tick 概率采样行: 每次评估都有确定结论,
        // 触发(FIRE)必打, 拒绝(邻居不足)走 2 秒节流。
        logCaveScanDiag(mod, center, (best != null ? "FIRE best=" + best.toShortString() : "REJECT no-neighbor x" + noNeighbor)
                + " natural=" + naturalAir.size() + " reachable=" + reachableAir.size());
        return Optional.ofNullable(best);
    }

    /**
     * (2026-09-06 闸门级诊断) findNearbyCave 每次评估的结论行, 写 altoclef_diag.log。
     * 每个实例首次评估必打(防节流错过隧道模式 ~2 秒的扫描窗口), 拒绝类之后 2 秒
     * 节流, FIRE 必打。行为完全不变, 纯诊断。
     */
    private void logCaveScanDiag(AltoClef mod, BlockPos center, String result) {
        boolean isFire = result.startsWith("FIRE");
        long nowMs = System.currentTimeMillis();
        if (!isFire && _caveScanDiagLogged && nowMs - _caveScanDiagMs < 2000) return;
        _caveScanDiagLogged = true;
        _caveScanDiagMs = nowMs;
        Debug.logToFile("altoclef_diag.log", "[CaveScan] " + result
                + " c=" + center.toShortString() + " step=" + _step + " tun=" + _tunnelMode);
    }

    // (2026-09-01 矿洞深度感知) 只有玩家 y 低于此值(已挖到地下矿洞层)才允许
    // 洞检测。地表/浅层(y 高)即使头顶有方块(挖开的楼梯)也不算"发现矿洞"。
    // 海平面 63, 地表 70-80; 主流矿洞/洞穴系统在 y<45 以下较丰富, 取 45。
    private static final int CAVE_EXPLORE_MIN_DEPTH_Y = 45;

    // (2026-09-01 地表误判矿洞修复) 判定一块空气上方是否有"岩顶"覆盖。
    // 向上扫 CAVE_COVER_SCAN_UP 格: 遇到实心方块 → 有顶(true); 全是空气/
    // 流体/树冠(树叶/原木) → 露天(false)。树冠不算岩顶, 避免砍树区域误判。
    // 未加载列保守视为有顶(不冒险把已见区域当洞外的天)。繁茂洞穴顶通常在
    // 玩家上方几格到几十格内, 24 格上限足够覆盖, 也避免地表向上扫 60 格。
    private static final int CAVE_COVER_SCAN_UP = 24;

    private boolean hasOverheadCover(AltoClef mod, BlockPos pos) {
        BlockPos up = pos;
        for (int i = 0; i < CAVE_COVER_SCAN_UP; i++) {
            up = up.up();
            if (!mod.getChunkTracker().isChunkLoaded(up)) return true;
            net.minecraft.block.Block b = mod.getWorld().getBlockState(up).getBlock();
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR) continue;
            if (b instanceof net.minecraft.block.LeavesBlock
                    || b instanceof net.minecraft.block.PillarBlock
                    || b instanceof net.minecraft.block.AzaleaBlock) continue; // 树冠/树干不算岩顶
            if (!b.getDefaultState().getFluidState().isEmpty()) continue; // 流体不算
            return true; // 实心覆盖
        }
        return false;
    }

    private boolean hasCaveNeighbors(AltoClef mod, BlockPos pos, int required) {
        int count = 0;
        for (int dy = -1; dy <= 1 && count < required; dy++) {
            for (int dz = -1; dz <= 1 && count < required; dz++) {
                for (int dx = -1; dx <= 1 && count < required; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (WorldHelper.isAir(mod, pos.add(dx, dy, dz))) count++;
                }
            }
        }
        return count >= required;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // (2026-09-07 二次修复, 用户"记忆还是没生效") 原实现在此清空 _inCave/_subTask:
        // 每次被打断(挖矿/合成/进食 overlay 抢占)都会把探索状态抹掉, 恢复时 onStart
        // 的断点续探分支(_inCave 才续)永远走不到 → markExploredCaveRegion 仍不执行
        // (11:36-11:46 会话 12 次 FIRE / 0 条 marked region)。探索状态的生命周期归
        // onTick 的完成/超时分支管理(完成时先 mark 再清), onStop 不再插手; 任务对象
        // 被父任务丢弃时状态随对象消亡, 无泄漏。
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // FIX (stuck bug): once we've hit the bottom the shaft's job is done.
        // Requiring an empty queue here could deadlock the task (and the bot)
        // forever when leftover blocks remained from an interrupted final step.
        return !_inCave && reachedBottom(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        // Identity comparison. The parent task creates a NEW instance when a
        // dig run finishes (isFinished). With plain "instanceof" equality the
        // Task framework would treat the fresh task as equal to the finished
        // one, never swap it in, and keep ticking the DEAD task forever —
        // the bot freezes on "Shaft done". Each dig run is a distinct task.
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return _ascendMode ? "Climbing back up (staircase up)"
                : _tunnelMode ? "Digging a tunnel to find ores" : "Digging down to find ores";
    }
}
