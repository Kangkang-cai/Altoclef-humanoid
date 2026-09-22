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
import adris.altoclef.util.helpers.VisibilityHelper;
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
    // (2026-09-13 导航步长与视野解耦) 视野必须够大(EXPLORE_SCAN_RADIUS)才能判断
    // "这条洞还有多长", 但【单次导航的目标】不能选在视野边缘 —— 探索是低优先级
    // 支线, 父任务(挖隧道主线)每 5~10 秒就用 DestroyBlock 抢一次, 长距离目标永远
    // 走不完。09-13 实锤: 视野修好(coverR=16/16)后首个目标 124,-8,-248 距玩家
    // 140,-25,-264 有 22.6 格水平 + 17 格上升, 启动 3 秒就触发 JumpStuckResolver
    // 挖障, 51 秒内 visited=0(零到达, 每次中断只回传"玩家当前位置"一个中心);
    // 而修复前(视野被 512 上限砍成 8 格)目标只有 4~6 格, 54 秒走了 20 个点。
    // 折中: 候选集仍按 EXPLORE_SCAN_RADIUS 收集(洞的延伸范围可见), 选目标只在
    // NAV_STEP_MAX_DIST 格以内挑"离起点最远"的点 —— 保持"一致向外"语义, 同时保证
    // 单次导航能在抢占窗口内完成。想调推进速度只需改这一个常量。
    static final int NAV_STEP_MAX_DIST = 10;
    // (2026-09-13 垂直可达性, 修 NAV_STEP_MAX_DIST 的副作用) NAV_STEP_MAX_DIST 用的是
    // 3D 距离(isWithinDistance), 垂直分量能吃满全部预算 —— 实锤 09-13 13:08:15
    // best=247,-39,28 c=246,-48,27 navDist=9.1 = (dx1,dy9,dz1), 目标在【正上方 9 格】。
    // 地下洞穴里垂直上升要垫方块/找斜坡; 纯空腔(四周 9 格全 air)里做不到 → 水平速度
    // 恒 0 → JumpStuckResolver 判"卡住", 又因无阻挡可挖 → "no obstruction -> forceCancel"
    // 反复打断爬升(09-13 会话 129 次 ORDER 里 66 次 no obstruction, onGround=false 占 50)。
    // 上下不对称: 向上靠跳跃/垫方块(严格), 向下重力帮忙(宽松)。两个上限分别建模。
    // 13:08 段实测 dy 分布: 向下段(玩家从 y-24 沉到 y-54)全在 -1~-6, 属正常沿洞下坡;
    // 向上段(玩家从 y-54 爬回 y-37)出现 +6/+9, 与 JumpStuck 触发点一一对应。
    static final int NAV_STEP_MAX_DY_UP = 4;
    static final int NAV_STEP_MAX_DY_DOWN = 8;
    // =========================================================================
    // (2026-09-16 下界探索差异化, 用户"下界探洞范围更大, 别把空旷地当矿洞")
    // 下界的"洞"尺度与主世界完全不同: 巨型空腔/开阔荒地随处延伸到 16 格视野之外。
    // 0916 131126 场次实锤三件事:
    //   1) 巨腔里 10 格一小步 = 原地挪动 (CavePick 连续 navDist≈9.9, 每 5s 挪 10 格);
    //   2) naturalMaxY=45 是主世界"防自挖楼梯连地表"的遗产, 下界矿带 [32,96] 大半
    //      被它挡掉 (REJECT aboveMaxY=280);
    //   3) 主世界的"深度闸门 y≤45"在下界只偶发放行, 且放行的多是空旷地 (coverR=16/16
    //      CAP HIT = 空气延伸出视野, 是巨腔/荒地不是需要"探索"的洞)。
    // 三个对应差异点 (主世界路径零改动):
    //   视野/步长放大 16→32 / 10→24; 高度硬顶 45→120; 空旷地判据从"深度闸门"
    //   换成"头顶 48 格内必须有岩顶"(开阔荒地/灵魂沙谷地表无顶 → 拒; 巨腔有腔顶 → 放行)。
    // =========================================================================
    static final int NETHER_EXPLORE_SCAN_RADIUS = 32;
    static final int NETHER_NAV_STEP_MAX_DIST = 24;
    // 下界空旷地判据: 玩家头顶该范围内无实心岩顶 → 不算矿洞(开阔地表)。
    // 48 覆盖常见巨腔腔顶高度(腔顶多在玩家上方 10~40 格); 菌柄/菌伞不算顶(见 hasOverheadCover)。
    private static final int NETHER_COVER_SCAN_UP = 48;
    // 下界天然空气高度硬顶(主世界 CAVE_NATURAL_AIR_MAX_Y=45 的下界对应物;
    // 基岩顶盖 y=127 之下留裕量)。下界无"自挖楼梯连地表"概念, 防连顶由
    // 自挖记忆排除承担。
    private static final int NETHER_NATURAL_AIR_MAX_Y = 120;
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
    // 矿洞认定 + 探索导航都只认【目标矿带附近】的天然空气。
    // (2026-09-13 双边窗口, 用户"挖钻石时只有高度范围内的矿洞才会触发探索探索")
    // 原实现是【单边上限】: naturalMaxY = targetY + 16, 没有下限。两个问题:
    //   1) 16 太宽 → 钻石层 -57 时会认到 y=-43 的浅层洞, bot 被上方洞导航走;
    //   2) 无下限 → 深部目标不会拒绝"掉到更深处"的洞。
    // 改为围绕【目标矿带】(不是单点 _targetY) 的双边窗口:
    //   上限 = 带上限 + CAVE_ABOVE_TARGET_BUFFER
    //   下限 = 带下限 - CAVE_BELOW_TARGET_BUFFER
    // 各带实际窗口 (ABOVE/BELOW 均取 8):
    //   钻石 [-57,-53] → [-65, -45] → 受世界底/基岩约束为 [-59,-45]  (含 -53 以上的探洞层)
    //   金   [-24, 0]  → [-32,   8]   (金峰值 -16, 窗口内)
    //   铁   [  5,30]  → [ -3,  38]   (铁带隧道 5~30, 窗口内)
    //   兜底 [ 50,50]  → [ 42,  45]   (注: CAVE_EXPLORE_MIN_DEPTH_Y=45 已先拦下)
    // 语义: 隧道挖到目标层后, 只有【与本层高度相当】的洞才值得进去; 向上蔓延到
    // 浅层、或落在更深处的洞都不再吸引 bot。
    static final int CAVE_ABOVE_TARGET_BUFFER = 8;
    static final int CAVE_BELOW_TARGET_BUFFER = 8;
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
    // 已探索洞穴区域的禁入/禁探索形状(扁圆柱): 水平半径 EXPLORED_CAVE_RADIUS +
    // 垂直半高 EXPLORED_CAVE_Y_BAND, 区域中心为探索结束时玩家所在/洞区中心。
    // (2026-09-03 用户选定 32): 一个普通矿洞 1 次就能整体剔除; 超大洞网络靠"每个探索
    // 结束标一个中心"逐步覆盖, 即使 90s 超时也能保证同一片不再被反复进。
    // (2026-09-11 用户"半径32太大"改扁圆柱 16/±8): 3D 球 y 方向 ±32, 锚点上下的
    // 没探索过的深层/浅层全被挡(0911 09:11 场次: 昨日 y=-17 的标记把今日刚挖到的
    // y=-24~-27 同洞系新段整体判 explored=463 → CaveScan 拒绝)。水平改 16 收窄
    // 覆盖面, y 压 ±8 使上层洞标记不再误挡深层新洞。
    private static final int EXPLORED_CAVE_RADIUS = 16;
    // (2026-09-11) 扁圆柱垂直半高: |Δy| 超过此值即使水平重合也不算同片洞区。
    private static final int EXPLORED_CAVE_Y_BAND = 8;
    // (2026-09-03 同心丢弃) 与已有中心过近时不再重复记(防密集记录同片洞区)。
    private static final int EXPLORED_CAVE_MIN_SEP = 24;
    // (2026-09-12 点集回传) ExploreCaveTask 结束/被打断时把【实际到达过的目标点】
    // 批量交回本类登记, 用 8 作去重间距而非 24: 到达点在洞内是一串连续轨迹, 彼此
    // 常近于 24, 用 24 会把整条轨迹削掉只剩几个点、洞两端仍可能落在半径外。8 仍能
    // 滤掉同一目标的重记, 又保留轨迹形状 —— 相邻登记中心距 ≥8 < 2×半径16, 圆柱
    // 必然互相重叠 → 覆盖连续无缝隙。
    // 起因(09-12 实锤): 09-11 把半径 32 收窄到 16 后, 洞体跨度 24 格的场景漏了远端 ——
    // 锚点 129,41,-231 + 半径16 只覆盖到 z=-247, 洞实际延伸到 z=-255, 22 秒后
    // 139,41,-255 再次 FIRE, 同一洞二次进洞。
    private static final int EXPLORED_CAVE_POINT_MIN_SEP = 8;
    private static final java.util.Map<String, java.util.LinkedHashSet<BlockPos>> _exploredCaveByKey
            = new java.util.HashMap<>();
    private static boolean _exploredCaveLoaded = false;

    // =========================================================================
    // (2026-09-17 FIRE 标记降级为"临时租约", 用户"感觉有忽略没探索过的矿洞的嫌疑")
    //
    // 症状: FIRE 那一刻就 markExploredCaveRegion 写盘(r16/y±8, 跨会话永久生效)。
    // 审计实锤(会话 20260917-173950 + 20260917-155653):
    //   - 16:08:24.471 FIRE(-61,38,-27) -> 16:08:28.868 被 Destroy block 抢占,
    //     整场探索只活了 4.4 秒, 却留下 (-61,38,-27) y[30,46] + 到达点(-56,20,-28)
    //     y[12,28] 两层永久封禁;
    //   - 93 分钟后同一洞区再扫: natural 从 176 掉到 24 —— 恰好等于阈值
    //     MIN_NATURAL_CAVE_AIR=24 (前两次 22/23 被拒, 差 1 就永远发现不了);
    //   - 该次 FIRE 行 explored=217/313, 玩家周围 BFS±8 立方体 75% 落在封禁内。
    // 用户现象: "洞口就在旁边, bot 却不进去"(不是没扫到, 是扫到了被判已探索)。
    //
    // 语义修正: FIRE 锚点只代表"本洞【正在】被探索", 这是【租约】不是【结论】。
    //   - 租约期内(默认 180s)照旧排除 -> 保住 2026-09-07 防"同洞反复吸引"的原始诉求
    //     (那次是因为链重建导致结束标记难产, 同洞 19 秒内二次 FIRE);
    //   - 租约到期自动失效 -> 因为超时/被抢占【恰恰说明本洞没探索完】, 不该永久封禁。
    //   - 持久记忆只由两类【事实】写入: 真实到达点(markExploredCavePoints, 2026-09-12)
    //     与探索结束时玩家所在位置(_inCave 退出分支)。
    // 形状判据与持久记忆同源(r = EXPLORED_CAVE_RADIUS / y ± EXPLORED_CAVE_Y_BAND),
    // 不引入第二套口径 —— 唯一区别是"会过期"。
    //
    // 用墙钟而非 TimerGame: 租约要的是"防抖窗口", 暂停时宁可少租一会儿(过期 = 放开,
    // 比永久封禁安全); TimerGame 走 ClientConnection.ticks, 单机暂停会冻结、Esc 挂机
    // 反而延长租约(2026-09-03 那条冻结修复只保证单调不冻结, 语义上不适用于租约)。
    // =========================================================================
    private static final long FIRE_LEASE_MS = 180_000L;
    private static final java.util.Map<String, java.util.Map<BlockPos, Long>> _recentCaveAnchors
            = new java.util.HashMap<>();
    // (2026-09-06 闸门级诊断, 用户"22:31 在大矿洞里没触发探洞") findNearbyCave 有
    // 多个【静默】返回点(露天闸门/跨度/邻居), 日志上完全看不出被哪道闸门拦下,
    // 也无法区分"没扫"和"扫了被拒"。每次评估返回时打一行 [CaveScan] 原因:
    // 每个实例首次评估必打(防 2 秒节流错过短窗口), 之后拒绝类 2 秒节流,
    // 触发类(FIRE)必打。静态时间戳跨实例共享节流。
    private static long _caveScanDiagMs = 0;
    // Check for caves every N steps.
    private static final int CAVE_SCAN_EVERY_STEPS = 3;
    // (2026-09-22 用户定案"下沉过程中 y<-10 时遇到矿洞就直接探索") 下沉模式探洞
    // 解禁线: y<-10 的深层下挖途中也扫描遇洞即探; y≥-10 的浅层仍不扫 —— 保留
    // 09-02 "浅层大洞抢占下挖"防护(当年实锤 17:01 at y=33/51 反复触发)。
    // 动机: 121524 实锤读档点在洞内(y=-42)却一路穿洞下挖到 -57, 洞留在头顶
    // 不连通, 到 -57 后探洞判据永久够不着(only 9 natural air)。
    private static final int DESCENT_CAVE_SCAN_MIN_Y = -10;
    // Horizontal direction of the staircase (dig east as we descend).
    private static final int STAIR_DIR_X = 1;
    private static final int STAIR_DIR_Z = 0;
    // Horizontal directions a tunnel may pick from (cardinal only).
    private static final int[][] TUNNEL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // Tunnel mode descends 1 block every this many steps (a walkable 2:1 slope).
    private static final int TUNNEL_DESCEND_EVERY = 2;
    // Tunnels never descend below this Y — at the floor they run flat, which is
    // exactly how humans branch-mine for diamonds.
    // (2026-09-13 深度修正) 旧值 -24 源于"钻石带 = -24"的错误前提。
    // (2026-09-14 再修正: -59 → -58 —— 【镐子碰到的行】比【目标脚位】再低一格)
    // 楼梯下行为了把玩家的脚放进 targetY, 必须把脚下的那一格(ground 行)也挖掉 ⇒
    // 实际开挖的最深一行 = targetY - 1。1.18.2 基岩带 y=-64~-60 (20260914-132420 实锤
    // <Destroy block at -214,-60,279> 傻挖 17s), 所以任何带的目标脚位都必须 ≥ -58,
    // 最深的镐子行(-59)才不会落在基岩上。本硬地板取 -58 作为最后一道防线: 今后谁把一个
    // 更深的带传进来, 也会被抬回 -58 (虽已有不可破坏拦截兜底, 但每次命中都要改道, 成本高)。
    // 对其它带无副作用: 金 [-24,0]→max(-58,-24)=-24, 铁 [5,30]→max(-58,5)=5,
    // 兜底 [50,50]→50, 均与原值一致 (带下限主导, 地板不参与); 钻石 [-57,-53]→
    // max(-58,-57)=-57, 同样由带下限主导。
    // (2026-09-05 下界高度概念) 此下限只适用于主世界: 下界基岩在 y=0~4, 照搬会把
    // bot 一路缓降到 y≈5 被基岩挡死 (实测)。下界隧道不再缓降 (floor=目标高度, 平挖)。
    private static final int TUNNEL_MIN_Y = -58;
    // (2026-09-05 下界基岩层逃生, 用户"挖到基岩挡住应向上挖矿洞回到Y=70再随便探索")
    // 下界玩家 y 低于此值 → 紧急进入上行模式: 45° 楼梯向上挖回目标高度。
    // 基岩顶面 y=4, 取 10 留出安全缓冲。
    private static final int NETHER_ESCAPE_Y = 10;
    // 上行模式的最大楼梯步数 (下界从 y≈5 爬回 y=70 需约 66 步; 2026-09-10 主世界
    // 浅带上行: 从 y≈-40 爬回 y=30 需约 70 步, 深洞 -60 爬回需约 90 步 — 提到 100
    // 留余量。普通下挖 MAX_DIG_STEPS=40 不变)。
    private static final int MAX_ASCEND_DIG_STEPS = 100;
    // (上行触发缓冲) 下界任务开始时, 玩家低于 目标高度-16 才进入上行模式:
    // 避免 y=69 这种贴着目标高度的位置反复触发"上爬1格"的抖动。
    private static final int NETHER_ASCEND_BUFFER = 16;
    // (2026-09-14 深带上行缓冲) 主世界【负矿带】(钻石 [-57,-53]、金 [-24,0] 等)
    // 的上行触发线 = 允许行事线 floorY 再低 2 格。旧实现对这些带返回 MIN_VALUE
    // (永不上行), 而 enqueueNextStep 的 drop 公式 (#footnote1) 在起点已低于 floorY
    // 时恒为 0 ⇒ bot 永远平挖在起点那一层, 回不到目标带。
    //   20260914-152654 实锤: 玩家开局就在 y=-61(上一局遗产隧道), 目标 -57,
    //   chat.log 打 "Already at depth -60 (target -57), digging a slowly-descending
    //   tunnel instead." 但整局 y 分布 = -60(170)/-59(73)/-61(14), 一次没到 -57~-53;
    //   -61 那一行是基岩带, [MineDown-Unbreakable] 累计 10+ 次。
    //   取 2 格缓冲: 玩家脚位 -57 (行车线内) 不触发, -58 及以下才爬, 避免贴线抖动。
    //   footnote1: drop = max(0, min(digStart.y - floorY, k/2)) —— floorY 只拦"继续下降",
    //   没有任何上升分量, 起点在 floor 之下时 min() 取负值 → max(0,负) = 0 → 平挖。
    private static final int DEEP_ASCEND_BUFFER = 2;
    // (2026-09-16 开阔豁免) 判"头顶空旷"的连续可通行格数: 从脚上方起向上扫这么
    // 多格全是 air/cave_air → 站在开阔地表/大空腔, 不是被困在地下, 无需爬回目标高度。
    // 10 格足够区分"洞里"(洞顶通常 1~6 格内封顶) 与"露天/大空腔"。
    private static final int OPEN_CEILING_SCAN = 10;
    // (2026-09-15 baritone-first 上行) 触发上行后先让位 baritone GoalYLevel 爬回目标
    // 高度 (baritone 的 A* 可能借现成矿洞/地形省时间, 用户拍板), 自写 45° 楼梯降级为
    // 看门狗兜底。看门狗: 脚下最高 Y 连续这么久没有净涨 ≥ MIN_GAIN → 判 baritone 爬
    // 不动 (典型: 水淹岩层, baritone 垂直爬升在水体无解, 09-14 取证) → 回退楼梯。
    private static final int BARITONE_ASCENT_STALL_SECONDS = 20;
    private static final int BARITONE_ASCENT_STALL_MIN_GAIN = 2;
    // (2026-09-17 头顶净空步上) 低行事线恰好 1 格时挖头顶净空块的超时 (超时交还
    // baritone 正常流程, 防不可挖方块挂死本状态)。
    private static final int CEILING_STEP_UP_TIMEOUT = 8;
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
    // (2026-09-14 挖掘停滞放弃) 队首同一格派发 DestroyBlockTask 超过这么久仍是实体
    // ⇒ 放弃它, 队列往前走。实锤 20260914-152654: <Destroy block at -237,-60,233>
    // 从 15:28:16 卡到 15:28:45 —— 29 秒里 12 次 "unreachable after 42 ticks"
    // (Blacklist Try 1/4 一路报到 10/4, 上限形同虚设)、1 次
    // "INVALID SET: Block{minecraft:gravel}" (重力方块让 baritone builder 反复重启),
    // 期间 bot 完全静止, 最后靠用户手动挖开幕才脱离 —— 挖开幕的是用户, 不是 bot。
    // 正常一格深板岩/矿石用铁镐 <1 秒 (该场次 39 次派发里 36 次 ≤2 秒), 10 秒留足
    // 走位、够不着重选路线、换工具的余量, 又不至于让一次误判吃掉半分钟。
    private static final long DIG_TARGET_STALL_TIMEOUT_MS = 10_000;

    /**
     * (2026-09-14 v2 目标跑到头顶 → 重锚) 队首目标比玩家【脚位】高出这么多格以上,
     * 就不再派发 DestroyBlockTask, 先把挖掘锚点拉回玩家当前位置。
     *
     * 合法上限为什么是 2: 45° 上行时 ground(k) 是玩家要踩的下一格台阶 (= 脚位 +1),
     * ground.up() 是它的净空 (= 脚位 +2) —— 两者都必须能派发。到 3 格就已经是"头顶
     * 之上的方块", 人手 sanction 根本不会去挖它, bot 派出去只能触发垫塔。
     * 现场 20260914-162715: 脚位 (-220,-60,230)、队首 (-220,-57,229) = 高出 3 格。
     */
    private static final int MAX_TARGET_ABOVE_FEET = 2;

    // How deep we're willing to dig for this run (differs by target ore).
    // (2026-09-05 目标带) 单点改带 [_targetYMin, _targetYMax]: onStart 随机取
    // 一点作本轮目标。_targetY 不再是 final (每实例 onStart 赋值一次)。
    private final int _targetYMin;
    private final int _targetYMax;
    private int _targetY;

    // (2026-09-15 实例重建继承 targetY, 104100 场次实锤) 探索实例因 handoff/
    // AIRBORNE 频繁重建, 每次重建 onStart 重掷带内随机 targetY → 爬升线
    // (targetY-16) 跟着跳 → 挖到一半被 "Going to y=NN" 反复抢走 (diag:
    // 55→75→87→89→84→73 连续跳变)。带内继承上一轮值; 竖井正常收工
    // ("Shaft done") 时清空重掷, 下一口井换高度。不同矿种带不重叠, 越界自动重掷。
    private static int _lastRolledTargetY = Integer.MIN_VALUE;

    // (2026-09-22 防回头·跨实例方向粘滞) 用户"水平方向容易回头, 导致 bot 长期
    // 在一片高危区探索": 隧道方向原来是每次 onStart 纯随机 —— 父层重建实例后
    // 25% 概率正好掉头、50% 概率折回刚挖过的象限, 净位移不增长。方向记忆放
    // static (跨实例存续, 同 _lastRolledTargetY 口径):
    // - 正常开新隧道 (onStart 隧道分支): 有记忆就延续上一方向 (粘滞), 无记忆随机;
    // - 上行楼梯/爬升续挖/弃道换向: 随机但【排除逆方向】(回头 = 挖回自己刚走过
    //   的区域), 20260922 另机日志实锤 z=26 一面墙 9 分钟逐格烧 10s。
    private static int _lastTunnelDirX = 0;
    private static int _lastTunnelDirZ = 0;

    // (2026-09-22 挖不动名单) 10s 超时被放弃的队首格 (FIFO 有界)。几何路径重新
    // 铺到它时直接跳过, 不再派发 DestroyBlockTask 重试 —— 重试只会重演 10s 停滞
    // (20260922 实锤: 24,10,27 连续 5 次 giving up, 纯烧 50s)。
    private static final java.util.ArrayDeque<BlockPos> _recentGiveUps = new java.util.ArrayDeque<>();
    private static final int RECENT_GIVEUP_MAX = 64;

    private final Deque<BlockPos> _digQueue = new ArrayDeque<>();

    private BlockPos _digStart;
    private int _step = 0;
    // (2026-09-14 v2 目标跑到头顶 → 重锚) 上次重锚时刻, 用于 1 秒节流 (见重锚调用处)。
    private long _lastReanchorMs = 0;
    // (2026-09-10 空跳修复) 本实例【真实挖掉】的方块数 = 本轮挖掘预算的消耗量。
    // 与几何路径索引 _step 严格分离: _step 会被 FAST-FORWARD 空跳刷高, 不代表
    // 任何真实挖掘; reachedBottom 的步数上限只看 _realDigs — 空跳不能再替
    // "一镐没挖"的实例宣告 "Shaft done" (10:47 场次死循环根因)。
    private int _realDigs = 0;
    // 上一 tick 派发 DestroyBlockTask 的目标格。下个 tick 确认它已变空气
    // ⇒ 真的被挖掉了, 记 1 格预算 (判定必须先于 skipAirAndWater 弹队首)。
    private BlockPos _miningTarget = null;
    // (2026-09-14 挖掘停滞放弃) 当前队首目标格 + 它第一次被派发的时刻。
    // 同一格挖超过 DIG_TARGET_STALL_TIMEOUT_MS 还是实体 ⇒ 有东西挡着 (重力方块、
    // 边缘悬挂、目标被后来的方块替换), 而不是"再等等就能挖掉"。
    private BlockPos _stallTarget = null;
    private long _stallTargetSinceMs = 0;
    // 连续空跳步数计数 (跨 tick 累计, 找到实体方块后清零), 用于诊断日志与
    // 单 tick 空跳上限。
    private int _skipStreak = 0;
    private boolean _inCave = false;
    private Task _subTask;
    // (2026-09-10 探索断裂诊断, 用户"挖到地下矿坑却不触发矿洞探索") isFinished
    // 翻转诊断: 只在 true/false 翻转沿打一行。怀疑链: 坠入矿坑瞬间玩家 Y 穿越
    // targetY → isFinished(!inCave && reachedBottom) 在 FIRE 之前瞬时为 true →
    // 父任务 getWanderTask 判"挖完一轮"重建全新实例, 正在坠落的本实例(空中
    // ITaskRequiresGrounded 换入被挡)手里攥着的洞探索作废。
    private boolean _finishedDiagLastState = false;
    // (2026-09-06 闸门级诊断) 本实例是否已打过首次 [CaveScan] 评估行。
    private boolean _caveScanDiagLogged = false;
    // Tunnel mode: we're already at the target depth, dig horizontally instead.
    private boolean _tunnelMode = false;
    // (2026-09-05 下界维度感知) onStart 时缓存是否在下界, 供隧道下限/上行模式判断。
    private boolean _nether = false;
    // (2026-09-05 下界基岩层逃生) true = 45° 楼梯【向上】挖回目标高度。
    private boolean _ascendMode = false;
    // (2026-09-15 baritone-first 上行) true = 上行交给 baritone GoalYLevel 爬,
    // 楼梯 (_ascendMode) 只在看门狗超时 / 水中场景启用。
    private boolean _baritoneAscent = false;
    private GetToYTask _baritoneAscentTask = null;
    private int _baritoneAscentBestY = Integer.MIN_VALUE;
    private final TimerGame _baritoneAscentStall = new TimerGame(BARITONE_ASCENT_STALL_SECONDS);
    // (2026-09-17 头顶净空步上) 非 null = 正在挖这个头顶块, 挖开后交 baritone
    // 垫一步上行事线。2 格高隧道里跳高不足 1 格、pillar 放不进方块, baritone
    // 无解; 挖掉头顶净空块变 3 格高后 pillar 可行 —— 人类做法。
    private BlockPos _ceilingStepUp = null;
    private final TimerGame _ceilingStepUpTimer = new TimerGame(CEILING_STEP_UP_TIMEOUT);
    private int _dirX = STAIR_DIR_X;
    private int _dirZ = STAIR_DIR_Z;
    // [Diag-water] how many water blocks we've skipped so far this run.
    private int _waterSkipped = 0;
    // (2026-09-13 传送门保护) 跳过的门体方块数, 用于日志节流。
    private int _protectedDigSkipped = 0;
    // (2026-09-14 不可破坏拦截) 跳过的基岩类方块数, 用于日志节流。
    private int _unbreakableSkipped = 0;

    // ==== (2026-09-10 遇水改道) 挖掘路径真的出现水 → 不再涉水直穿 ====
    // 13:15/13:52 场次实证: 涉水直穿 → bot 在水下方块前卡死 → 下潜 → 溺水
    // 连锁。改为: 路径真实水块累计达标 (挖开才见, 反透视合规) → 有界改道
    // (RunAwayFromPositionTask 远离水体, 不严格避水, 任意方向) → 超时/完成后
    // retreatAndRedig 换向回到正常挖通道。
    private static final int WATER_DETOUR_TRIGGER_SKIPS = 2;
    private static final double WATER_DETOUR_RUN_DISTANCE = 10.0;
    private static final double WATER_DETOUR_TIMEOUT = 20.0;
    private boolean _inWaterDetour = false;
    private final TimerGame _waterDetourTimer = new TimerGame(WATER_DETOUR_TIMEOUT);
    private int _lastDetourWaterMark = 0;
    // (2026-09-22 方向选水) 最近一次在挖掘路径上撞到的水 (改道/换向时背离它用)。
    private BlockPos _lastWaterPos = null;
    /** 选向时沿走行线向前看这么多格统计水块 (方向选水评分)。 */
    private static final int DIR_WATER_LOOKAHEAD = 8;
    /** 记忆方向走行线水块数超过该值 → "方向被水封死", 放弃粘滞改选最干方向。 */
    private static final int DIR_WATER_STICKY_MAX = 2;
    // (2026-09-22 沾水粘滞) 水面浮沉让 isTouchingWater 逐 tick 翻面; 沾水后 3s 内
    // 一律按"水中"处置 (见 isWatery), 防止水中场景在 baritone 爬升/楼梯之间抖动。
    private final TimerGame _waterRecent = new TimerGame(3);

    // ==== (2026-09-14 遇岩浆弃道) 岩浆不能按水处置, 见一次立刻换通道 ====
    // 背景 (代码考古): 本类此前完全没有 lava 概念, skipAirAndWater 里的 isWater()
    // 判据是 `getBlock() instanceof FluidBlock` —— LavaBlock 是 FluidBlock 子类,
    // 于是【岩浆被当作水】: 从队列 poll 掉当空气走过去, 且要累计到
    // WATER_DETOUR_TRIGGER_SKIPS(2 次) 才后撤一次, 撤完回原地继续挖同一条通道。
    // 即"挖穿岩浆 → 走进去", 掉进去只能靠 WorldSurvivalChain 的 EscapeFromLavaTask
    // 救, 救完又挖回原处。人类绝不这样挖。
    // 与水的处置对照 (两者物理性质相反, 不能共用参数):
    //   水  : 能走过去 → 累计 2 次才撤, 撤完回原地继续挖, 撤 10 格。
    //   岩浆: 走过去=死 → 第【一】次遇到就立刻弃道(清队列+后撤+换方向), 不再回原道。
    // 后撤距离 (2026-09-17 重定): 原按"躲开岩浆流动范围"设计 (主世界 6 / 下界 12),
    // 那是"只判队首"时代的数 —— 撤 6 格就看不见那一格了。09-17 判据换成 12 格视觉
    // 感知之后, 这个数直接变成 churn 源头: 撤到 6 格外即判"撤离完成", 而 6 < 12 仍
    // 看得见同一处岩浆 → 立刻再弃道 → 再判"完成"(0 位移, baritone GoalRunAway 说
    // 已经达标) → 100ms 一轮, 计数一路涨到 22/4 → GiveUp → 父层重建 → 再来一轮。
    // 20260917-191217 实锤 (19:15:43~52 十秒内 20+ 轮), 用户观感"任务快速跳跃"。
    // 现在二者解耦但必须【匹配】: 撤离距离 > LAVA_SIGHT_RADIUS_XZ 才能保证"撤完就看
    // 不见它"。取 16/20 (>12, 留 4~8 格余量吃岩浆流体继续扩散)。
    private static final int LAVA_RETREAT_NETHER = 20;
    private static final int LAVA_RETREAT_OVERWORLD = 16;
    // 撤 16~20 格要实跑一段, 水的 20s 不够。
    private static final double LAVA_DETOUR_TIMEOUT = 30.0;
    // 一轮内弃道次数上限 (水平方向总共只有 TUNNEL_DIRS.length=4 个): 4 个方向全撞上
    // 岩浆 = 这片就是岩浆区, 再换向只会原地打转 → 结束本轮交回父任务重新选址。
    private static final int LAVA_ABANDON_LIMIT = 4;
    private boolean _inLavaDetour = false;
    private final TimerGame _lavaDetourTimer = new TimerGame(LAVA_DETOUR_TIMEOUT);
    private int _lavaEncounters = 0;
    private int _lavaAbandonCount = 0;
    private BlockPos _lastLavaPos = null;
    private boolean _lavaGiveUp = false;
    // 本轮亲眼见过的岩浆点 (不是预测)。用于给 RunAwayFromPositionTask 多个逃跑
    // 目标 —— 第二次撤离时能同时避开先前那一处, 不至于在两处岩浆之间来回跑。
    private final Set<BlockPos> _lavaHazards = new HashSet<>();

    // ==== (2026-09-17 视觉岩浆感知) 看得见的岩浆 = 弃道, 不再"装作看不见" ====
    // 09-14 的弃道判据只认 "待挖队首那一格" (skipAirAndWater 里的 isLava), 覆盖不到
    // "通道挖通之后岩浆从侧壁/脚下渗进已挖空间"。20260917-101352 场次实锤: 隧道
    // dug 41/80 正常推进, 岩浆从侧壁 + 脚下流入 bot 已经站进去的格子, 全场零条
    // [MineDown-Lava] / [MineDown-LavaAbandon], _inLavaDetour 从未进入 → bot 站在
    // 自己挖的通道里被烧, health 20 → 0 只用 6 秒 (cause=onFire, death (408,-57,-647))。
    // 口径 (用户 2026-09-17 拍板): "只判前一格是为了不透视; 岩浆在前面几格也装作
    // 看不见是不对的 —— 只要视觉范围内扫到的岩浆, 那就叫看见了, 看见了就该弃坑"。
    // 因此主判据直接走仓内【人类式视觉】统一入口 VisibilityHelper.isBlockPerceivable
    // (与找矿/找水/找床同一套): 岩浆必须过"表面暴露(正上方是空气/洞穴空气)"前置 +
    // LOS 射线无遮挡。隔着岩壁 / 被岩石包裹的岩浆照旧判不可见 → 反透视边界不变,
    // 不存在"隔着石头读到源头"的路径。三档触发口径由窄到宽:
    //   dig-queue-head = 待挖队首那格就是岩浆 (09-14 原有, 挖穿瞬间就在眼前);
    //   seen           = 视觉范围内 (12/6 椭球) 确实看得见的岩浆 (09-17 新增, 主路径);
    //   adjacent       = 贴身 11 格兜底 (09-17 新增, 次路径)。
    // 贴身兜底为什么留着: ①LOS 射线在脚下 / 紧贴方块处几何不稳 (同 VisibilityHelper
    // 里 CRAFTING_TABLE 16 格豁免的教训); ②紧邻格已是岩浆 = 危险已经贴上身体, 任何
    // 判据都得兜住, 不能因为"射线没打准"就放过。
    private static final BlockPos[] NEAR_LAVA_OFFSETS = {
            new BlockPos(0, 0, 0),
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(0, -1, 0), new BlockPos(0, 1, 0),
            new BlockPos(1, -1, 0), new BlockPos(-1, -1, 0),
            new BlockPos(0, -1, 1), new BlockPos(0, -1, -1),
    };
    // 视觉感知扫描半径 (椭球): 水平 12 / 垂直 6。取 12 而不是全渲染距离 —— 只关心
    // "会挡到自己这条通道"的近场; 同一个岩浆湖远处的湖面属正常地形, 不该触发弃道。
    // 垂直压到 6: 下界岩浆海常在头顶正上方一层, 再远的头顶岩浆不构成"挖出来的危险"。
    private static final int LAVA_SIGHT_RADIUS_XZ = 12;
    private static final int LAVA_SIGHT_RADIUS_Y = 6;
    // 扫描节流: 半径 12/6 椭球约 8k 格 getBlockState, 200ms 一次 (与 BlockTracker 的
    // 近场采样同量级, 远低于其 16 格全量扫描)。命中才打 LOS 射线, 而 LOS 自带 400ms
    // 短缓存 + 玩家位移失效率 (VisibilityHelper.isBlockVisible)。
    private static final long LAVA_SIGHT_SCAN_INTERVAL_MS = 200;
    private long _lastLavaSightScanMs = 0;
    // 贴身兜底节流: 11 次 getBlockState 极便宜; 口径同 SPAWNER_SCAN_INTERVAL_MS。
    private static final long NEAR_LAVA_SCAN_INTERVAL_MS = 100;
    private long _lastNearLavaScanMs = 0;
    // (2026-09-17 churn 修复) 撤离刚结束的宽限期: baritone 把 bot 放到"距该 hazard
    // ≥ LAVA_RETREAT_* 格"的位置后会立刻判撤离完成, 而这条落点可能只是贴着视觉半径
    // 边缘 (被墙挤住时更差) → 同一秒又"看见"同一处岩浆。给 2s 让撤退动作真正做完再
    // 重新评估。只压【视觉】路径: 贴身 11 格兜底 (findAdjacentLava) 不受宽限期影响 —
    // 危险已经贴到身上时, 不能因为"刚撤过"就不救。
    private static final long LAVA_SIGHT_GRACE_MS = 2000;
    private long _lavaSightGraceUntilMs = 0;
    // (2026-09-17 churn 修复) 跨实例播种: GiveUp 后父层重建的是【新实例】, 本地 hazards
    // 归零 → 换向时没有背离依据, 又冲同一处岩浆。onStart 时从仓内"见过即记忆"表
    // (VisibilityHelper.getRememberedValuable, 只收真感知过的岩浆, 按维度分表, 非透视)
    // 把附近的点播进 hazards, 让重建后的 retreatAndRedig 背离判据能生效。
    private static final double LAVA_MEMORY_SEED_RANGE = 16.0;
    private static final int LAVA_MEMORY_SEED_LIMIT = 8;

    // Cave exploration watchdog.
    // (2026-09-22 甲案: 看门狗改"在场 tick 计数", 挂起/停机不计时) 原 TimerGame 走
    // ClientConnection.ticks, 只要游戏在跑就照走 —— 而被战斗抢占/手动停机/链抖动期间
    // 本任务 onTick 根本没被调, 计时却在走。09-22 实锤: 13:30:20 FIRE → 13:30:27 被
    // 僵尸战斗抢占 → 13:31:09 手动停 → 13:33:09 重启瞬间, 冻结的看门狗立即报
    // "exploration timed out"(2m49s 里实际一秒都没探), 还把"已探索区域"错标在重启
    // 站位。改为只在探索支线在场跑的 tick 计数(onTick 经 AltoClef.onClientTick 每
    // game tick 恰好一次), 挂起/停机期间预算冻结, 假超时从根上消失。
    private int _caveExploreActiveTicks = 0;
    private static final int CAVE_EXPLORE_TIMEOUT_TICKS = (int) (CAVE_EXPLORE_TIMEOUT * 20);
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
        _caveExploreActiveTicks = 0;
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
        // (2026-09-15 继承) 见 _lastRolledTargetY 注释: 重建继承, 收工重掷。
        if (_lastRolledTargetY >= _targetYMin && _lastRolledTargetY <= _targetYMax) {
            _targetY = _lastRolledTargetY;
        } else {
            _targetY = _targetYMin + (int) (Math.random() * (_targetYMax - _targetYMin + 1));
        }
        _lastRolledTargetY = _targetY;
        _digStart = mod.getPlayer().getBlockPos().down();
        _step = 0;
        _realDigs = 0;
        _miningTarget = null;
        _skipStreak = 0;
        _inCave = false;
        _waterSkipped = 0;
        _protectedDigSkipped = 0;
        _unbreakableSkipped = 0;
        _digQueue.clear();
        // (2026-09-14 岩浆弃道) 执行态归零, 但【记忆不丢】: 见过的岩浆是事实, 不会
        // 因为父层重派/被打断就消失。典型打断链 = 挖穿岩浆 → 掉进去 → 生存链
        // EscapeFromLavaTask(prio 100) 抢占 → 救回来 onStart 重入 → 若无记忆, bot
        // 会立刻挖回刚把自己烧过的那条通道。保留 _lastLavaPos/_lavaHazards 后, 换向
        // 会自动避开那个方向。计数按"新一轮"归零 (同 waterSkipped 口径)。
        _inLavaDetour = false;
        _lavaEncounters = 0;
        _lavaAbandonCount = 0;
        _lavaGiveUp = false;
        _lavaSightGraceUntilMs = 0;
        // (2026-09-17 churn 修复) 实例可能是 GiveUp 后父层重建的【新实例】, 本地
        // hazards 已归零 —— 从仓内"见过即记忆"表把附近的岩浆点补回来, 否则换向判据
        // 无依据, 会再次朝着同一处岩浆挖 (20260917-191217 实锤: GiveUp 后 300ms 又
        // "累计 1" 重来一轮)。见字段区 LAVA_MEMORY_SEED_* 注释。
        seedLavaHazardsFromMemory(mod);
        // (2026-09-05 下界维度感知) 缓存维度, 决定隧道下限与上行模式。
        _nether = WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER;
        // (2026-09-05 下界基岩层逃生) 任务开始时已被压到目标高度之下很深 (如上一轮
        // 挖到 y≈5 被基岩挡) → 直接上行模式, 45° 楼梯挖回目标高度, 不再向下。
        // (2026-09-10 主世界浅带上行, 用户"挖铁不应始终呆在 y=负值, 应往回 y=5~30")
        // 目标带下限为正 (铁 [5,30] 等浅带) 且玩家深陷带下 → 同样上行爬回带内;
        // 钻石 {-24,-24} 等负带任务不触发, 保持原隧道平挖语义不变。
        // (2026-09-15 baritone-first 上行) 干地触发 → 先 baritone 爬升 (可能借矿洞
        // 省时间, 用户拍板); 只有水中 (baritone 垂直爬升在水体无解, 09-14 取证) 或
        // baritone 看门狗超时才走自写楼梯。
        boolean ascendTriggered = _digStart.getY() <= ascendTriggerY();
        // (2026-09-16 开阔豁免, 用户"高度上升只在有落脚点/被困前提下适用, 不是非要到
        // 那个高度"): 干地 + 头顶连续 OPEN_CEILING_SCAN 格可通行 = 站在开阔地表或
        // 大空腔, 没有被困, 强行爬回 targetY 没有意义。
        // 20260916-114603 实锤: 灵魂沙谷地表 y=44~59 挖隧道找金, targetY 掷到 64 →
        // 触发线=48, 每次实例 onStart 都 engage 爬升 → 爬上去 wander 又下来再 engage,
        // 4 轮循环零产出。开阔时直接平挖 (隧道缓降语义不变)。
        if (ascendTriggered && !mod.getPlayer().isTouchingWater() && isCeilingOpen(mod)) {
            Debug.logToFile("altoclef_diag.log", "[MineDown-OpenSkySkip] below ascend line (y="
                    + _digStart.getY() + ", line " + ascendTriggerY() + ") but ceiling open"
                    + " -> no ascent, dig tunnel instead.");
            ascendTriggered = false;
        }
        if (ascendTriggered && !isWatery(mod)) {
            enterBaritoneAscent(mod, "start below ascend line (y=" + _digStart.getY() + ")");
        } else {
            _ascendMode = ascendTriggered;
        }
        // Already at mining depth? Don't dig a pointless 1-block "shaft" that
        // finishes instantly and leaves the bot wandering — dig a tunnel instead.
        _tunnelMode = !_ascendMode && !_baritoneAscent && _digStart.getY() <= _targetY;
        if (_tunnelMode) {
            // (2026-09-17 行事线对齐, 20260917-183338 实锤) 隧道走行线 = _digStart.y
            // (首步 k=1 在前方挖 [ground, ground.up()] 两格, bot 落进去走 ground.y 层)。
            // 旧锚 feet.down() 让走行线 = 脚 y − 1: 钻石带掷到下限 -57 时挖降恰好停
            // 在 -57, 隧道平挖在 -58, 与 floor 门控 (y < -57 即 engage 爬升) 正面相撞
            // —— "bot 爬到边界、通道刚好低边界一格"死循环的结构性根源。锚改用脚所在
            // 格: 走行线 = 脚 y, 永不低于行事线; 缓降 drop 公式继续把 ground.y 压到
            // floorY 为止, floorY 以上的缓降行为不变。
            _digStart = mod.getPlayer().getBlockPos();
        }
        if (_ascendMode) {
            // (2026-09-22 防回头 + 方向选水) 随机选向但排除上一隧道方向的逆方向
            // (回头挖回刚走过的区域); 2026-09-22 二改: 改为按走行线水块数选最干
            // 方向 (平手随机), 水下/湖底起楼梯不再一头扎进水柱。选出后更新方向记忆。
            int[] dir = pickDriestDir(mod, mod.getPlayer().getBlockPos(), null,
                    hasLastTunnelDir() ? new int[]{-_lastTunnelDirX, -_lastTunnelDirZ} : null,
                    null);
            if (dir == null) {
                dir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
            }
            rememberTunnelDir(dir[0], dir[1]);
            _dirX = dir[0];
            _dirZ = dir[1];
            Debug.logMessage("[MineDown] below target depth (y=" + _digStart.getY()
                    + ", target " + _targetY + ") in water, climbing back up with an ascending staircase.");
            Debug.logToFile("altoclef_diag.log", "[MineDown] ascend (staircase, water) engaged at "
                    + _digStart.toShortString() + " (target y=" + _targetY + ").");
        } else if (_tunnelMode) {
            // Pick a random cardinal direction so consecutive tunnels don't
            // forever dig the same way through the same rock.
            // (2026-09-22 防回头) 改为跨实例粘滞: 有方向记忆就延续上一方向 ——
            // 父层重建实例后继续往前推 (净位移增长), 不再 25% 概率正好掉头挖回
            // 刚走过的区域; 完全没有记忆才随机。弃道换向仍会更新记忆 (见
            // retreatAndRedig), 粘滞不等于死锁在坏方向上。
            int[] dir;
            if (hasLastTunnelDir()) {
                dir = new int[]{_lastTunnelDirX, _lastTunnelDirZ};
                // (2026-09-22 方向选水) 记忆方向走行线被水封死 (前看 8 格水块 > 2)
                // → 放弃粘滞, 在其余方向 (排除回头) 里选最干的。三面环水时新实例
                // 不再一头续挖进水里, 挖 2 格又触发改道。
                int w = countWaterAhead(mod, mod.getPlayer().getBlockPos(), dir);
                if (w > DIR_WATER_STICKY_MAX) {
                    int[] drier = pickDriestDir(mod, mod.getPlayer().getBlockPos(), null,
                            new int[]{-dir[0], -dir[1]}, null);
                    String m = "[MineDown-DirWater] 记忆方向 (" + dir[0] + "," + dir[1]
                            + ") 走行线水块 " + w + "/" + DIR_WATER_LOOKAHEAD
                            + " > " + DIR_WATER_STICKY_MAX + " -> 弃粘滞换最干方向 ("
                            + (drier == null ? "无合法方向, 保底随机" : drier[0] + "," + drier[1]) + ")";
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                    if (drier != null) dir = drier;
                }
            } else {
                dir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
                rememberTunnelDir(dir[0], dir[1]);
            }
            _dirX = dir[0];
            _dirZ = dir[1];
            Debug.logMessage("Already at depth " + _digStart.getY() + " (target " + _targetY
                    + "), digging a " + (_nether ? "flat" : "slowly-descending") + " tunnel instead.");
        } else {
            _dirX = STAIR_DIR_X;
            _dirZ = STAIR_DIR_Z;
        }
    }

    /**
     * (2026-09-16 开阔豁免) 头顶是否开阔: 从脚上方起向上连续 OPEN_CEILING_SCAN 格
     * 可通行 (air/cave_air) → 不在被困的地下空间, 上行爬升不适用。实体方块即封顶。
     */
    private boolean isCeilingOpen(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos().up();
        for (int i = 0; i < OPEN_CEILING_SCAN; i++) {
            if (!mod.getWorld().getBlockState(p).isAir()) return false;
            p = p.up();
        }
        return true;
    }

    /**
     * (2026-09-22 沾水粘滞) 当前在水中, 或 3s 内曾沾水。水体表面浮沉会让
     * isTouchingWater 逐 tick 翻面 —— 上行模式选择的两个入口 (开/baritone vs 楼梯)
     * 都用本判定, 防止"出水一瞬选 baritone → 落水重挂 thrash"。计时器未 reset 过时
     * elapsed()=true → 非水中, 与旧语义兼容。
     */
    private boolean isWatery(AltoClef mod) {
        return mod.getPlayer().isTouchingWater() || !_waterRecent.elapsed();
    }

    /**
     * (2026-09-10 主世界浅带上行) 上行模式触发线:
     * - 下界: 玩家低于 本轮目标高度-16 (原语义不变);
     * - 主世界浅带 (带下限 > 0, 如铁 [5,30]): 玩家低于 带下限-16 触发上行爬回带内;
     * - 主世界深带 (带下限 ≤ 0, 如钻石 [-57,-53]) (2026-09-14 新增): 以【允许行事线】
     *   floorY=max(TUNNEL_MIN_Y, 带下限) 再低 DEEP_ASCEND_BUFFER 格为触发线。
     *   旧实现这里返回 MIN_VALUE 永不触发 ⇒ 一旦起点已在行事线以下 (上一局遗产隧道 /
     *   坠入深洞 / 探洞时沉下去), drop 公式退化成 0, bot 永久平挖在那一层 ——
     *   20260914-152654 实锤: 目标 -57 却在 -60/-61 挖了 258 个 diag 采样, 还踩着
     *   基岩带 (详见 DEEP_ASCEND_BUFFER 注释)。负带同样应该遵守这条行事线: floorY
     *   是"允许的最低层", 低于它就是不该在那儿, 爬回带内才是正解 (人类发现自己
     *   挖过头了会往回填/往上爬, 不会顺着基岩顶一路平推)。
     */
    private int ascendTriggerY() {
        if (_nether) return _targetY - NETHER_ASCEND_BUFFER;
        if (_targetYMin > 0) return _targetYMin - NETHER_ASCEND_BUFFER;
        return tunnelFloorY() - DEEP_ASCEND_BUFFER;
    }

    /**
     * (2026-09-14 深带上行) 隧道允许的最低行事线: 带下限, 但不深于 TUNNEL_MIN_Y。
     * 与 enqueueNextStep 里的 floorY 严格同一表达式 —— 两处分头写死过一次就够呛了。
     */
    private int tunnelFloorY() {
        return Math.max(TUNNEL_MIN_Y, _targetYMin);
    }

    /**
     * (2026-09-15 baritone-first 上行) 进入"baritone 爬升"阶段: 交给 GetToYTask
     * (GoalYLevel) 往上爬, 本任务的楼梯队列停用。楼梯只在两种情况启用: ① 触发时
     * 玩家在水里 (baritone 垂直爬升在水体无解, 09-14 取证); ② 看门狗超时
     * (BARITONE_ASCENT_STALL_SECONDS 内最高 Y 净涨不足 BARITONE_ASCENT_STALL_MIN_GAIN)。
     */
    private void enterBaritoneAscent(AltoClef mod, String reason) {
        _baritoneAscent = true;
        _ascendMode = false;
        _tunnelMode = false;
        _inCave = false;
        _subTask = null;
        _miningTarget = null;
        _digQueue.clear();
        _baritoneAscentTask = new GetToYTask(_targetY);
        _baritoneAscentBestY = mod.getPlayer().getBlockPos().getY();
        _baritoneAscentStall.reset();
        Debug.logMessage("[MineDown] " + reason + ": climbing back to y=" + _targetY
                + " via baritone first (staircase = watchdog fallback).");
        Debug.logToFile("altoclef_diag.log", "[MineDown-BaritoneAscent] engage (" + reason + ") at "
                + mod.getPlayer().getBlockPos().toShortString() + " targetY=" + _targetY + ".");
    }

    /**
     * (2026-09-16 爬完续挖) baritone 爬升到顶后原地切回隧道模式, 不收工、不劳父层
     * 重建。要点:
     * - _realDigs【不清零】—— 预算是整轮探索的真实挖掘额度, 爬升只是中途状态;
     * - 隧道几何是 _digStart + k*dir 的纯递推, 爬升后原锚点早已无意义 → 必须重锚
     *   到当前位置 (2026-09-17 行事线对齐: 锚点 = 脚所在格, 走行线 = 脚 y);
     * - 方向重掷: 爬升可能位移了十几格, 原方向继续大概率一头撞回刚爬出来的那片洞;
     * - _tunnelMode = true 使 reachedBottom 走"只看步数上限"分支, isFinished 恒
     *   false, 父层不再重建。
     */
    private void resumeTunnelAfterAscent(AltoClef mod) {
        _baritoneAscent = false;
        _baritoneAscentTask = null;
        _ascendMode = false;
        _tunnelMode = true;
        _inCave = false;
        _subTask = null;
        _miningTarget = null;
        _caveExploreCooldownSteps = 0;
        // 方向重掷: 爬升可能位移了十几格, 原方向继续大概率一头撞回刚爬出来的那片洞;
        // (2026-09-22 防回头 + 方向选水) 重掷排除上一方向的逆方向, 按走行线水块数
        // 选最干方向 (与 onStart/retreatAndRedig 同口径), 选出后更新记忆。
        int[] dir = pickDriestDir(mod, mod.getPlayer().getBlockPos(), null,
                hasLastTunnelDir() ? new int[]{-_lastTunnelDirX, -_lastTunnelDirZ} : null,
                null);
        if (dir == null) {
            dir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
        }
        rememberTunnelDir(dir[0], dir[1]);
        _dirX = dir[0];
        _dirZ = dir[1];
        _digStart = mod.getPlayer().getBlockPos();
        // (2026-09-17 行事线对齐) 旧 _digStart = feet.down(): 走行线 = 锚点 y, 即
        // 比 resume 时的脚位低 1 格。配合"半空到线即 resume"曾把续挖隧道钉死在
        // floor−1 (20260917-183338: [MineDown-AscentResume] at 20,-58,-81, 行事线
        // -57)。resume 现在只在落地后执行 (见 reachedBottom/onTick), 锚用脚所在格,
        // 走行线 = 脚 y 恰在行事线上; 缓降 drop 公式不变。
        _step = 0;
        _skipStreak = 0;
        _digQueue.clear();
        enqueueNextStep();
        String m = "[MineDown-AscentResume] 爬回到 y=" + _targetY
                + ", 原地切回隧道续挖 (realDigs=" + _realDigs + ", dir=(" + _dirX + "," + _dirZ
                + ")) at " + _digStart.toShortString() + ".";
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-05 下界基岩层逃生, 用户"挖下界金矿一路往下挖被基岩挡在 y≈5") 任务
        // 进行中若玩家已压到基岩层附近 (下界基岩顶面 y=4), 立即切上行模式: 清空当前
        // 向下队列, 换随机水平方向, 45° 楼梯向上挖回目标高度, 然后任务自然结束,
        // 由父任务以隧道平挖模式继续在目标高度探索。
        if (_nether && !_ascendMode && !_baritoneAscent
                && mod.getPlayer().getBlockPos().getY() <= NETHER_ESCAPE_Y) {
            _tunnelMode = false;
            _inCave = false;
            _subTask = null;
            _caveExploreCooldownSteps = 0;
            if (isWatery(mod)) {
                // (2026-09-15 baritone-first) 水中 baritone 垂直爬升无解 → 直接楼梯。
                // (2026-09-22) 判定换 isWatery (3s 粘滞): 水面浮沉出水一瞬不再误选 baritone。
                _ascendMode = true;
                retreatAndRedig(mod);
                Debug.logMessage("[MineDown] nether: hit bedrock floor zone (y="
                        + mod.getPlayer().getBlockPos().getY() + ") in water, ascending staircase to y=" + _targetY + ".");
                Debug.logToFile("altoclef_diag.log", "[MineDown] nether bedrock escape (water): ascending from "
                        + mod.getPlayer().getBlockPos().toShortString() + " to target y=" + _targetY + ".");
            } else {
                enterBaritoneAscent(mod, "nether bedrock escape (y="
                        + mod.getPlayer().getBlockPos().getY() + ")");
            }
        }

        // (2026-09-17 头顶净空步上) 低行事线恰好 1 格时的人类做法: 2 格高隧道里
        // 跳高不足 1 格、pillar 放不进方块, baritone 无解 (20260917-183338: 11 轮
        // engage 无一爬上)。先挖掉头顶净空块变 3 格高, 再交 baritone 垫一步上来。
        if (_ceilingStepUp != null) {
            if (_ceilingStepUpTimer.elapsed()) {
                Debug.logToFile("altoclef_diag.log", "[MineDown-CeilingStep] timeout at "
                        + _ceilingStepUp.toShortString() + " (unbreakable/protected?), hand to baritone.");
                _ceilingStepUp = null;
                _subTask = null;
            } else if (mod.getWorld().getBlockState(_ceilingStepUp).isAir()) {
                Debug.logToFile("altoclef_diag.log", "[MineDown-CeilingStep] ceiling open at "
                        + _ceilingStepUp.toShortString() + ", pillar up via baritone from y="
                        + mod.getPlayer().getBlockPos().getY() + ".");
                _ceilingStepUp = null;
                _subTask = null;
                // 落到下方 floor 门控: 头顶已净空 → 直接 engage baritone (3 格高 pillar 可行)。
            } else {
                setDebugState("Digging headroom overhead to step up to the mining floor");
                if (_subTask == null || _subTask.isFinished(mod)) {
                    _subTask = new DestroyBlockTask(_ceilingStepUp);
                }
                return _subTask;
            }
        }

        // (2026-09-14 主世界深陷兜底) 上一处只管下界。主世界 bot 在挖掘【过程中】也会
        // 掉出行事线: 探洞时顺着洞往下走、坠入岩浆湖底、 Tunnel 缓降踩空。旧实现里一旦
        // 到了行事线以下就再也回不去 (drop 公式退化为 0, 见 DEEP_ASCEND_BUFFER 注释),
        // 整轮任务就耗在基岩层周边 —— 20260914-152654 就是这么耗掉 2 分钟的。
        // 与下界同款处置: 立刻切上行模式挖回目标带。用 tunnelFloorY() 而不是
        // ascendTriggerY(): 开局判定需要缓冲防抖动, 这里是已经跑偏的事实纠正, 不必
        // 再等 2 格 —— 玩家在 TUNNEL_MIN_Y 以下就该马上开始爬。
        if (!_nether && !_ascendMode && !_baritoneAscent && _ceilingStepUp == null
                && mod.getPlayer().getBlockPos().getY() < tunnelFloorY()) {
            _tunnelMode = false;
            _inCave = false;
            _subTask = null;
            _caveExploreCooldownSteps = 0;
            if (mod.getPlayer().isTouchingWater()) {
                // (2026-09-15 baritone-first) 水中 baritone 垂直爬升无解 → 直接楼梯。
                _ascendMode = true;
                retreatAndRedig(mod);
                Debug.logMessage("[MineDown] below mining floor (y="
                        + mod.getPlayer().getBlockPos().getY() + ", floor " + tunnelFloorY()
                        + ") in water, climbing back to y=" + _targetY + " with a staircase.");
                Debug.logToFile("altoclef_diag.log", "[MineDown-FloorEscape] 低于行事线 "
                        + tunnelFloorY() + " at " + mod.getPlayer().getBlockPos().toShortString()
                        + " targetY=" + _targetY + " (water) -> ascendMode (staircase).");
            } else if (mod.getPlayer().getBlockPos().getY() == tunnelFloorY() - 1 && isPlayerGrounded(mod)) {
                // (2026-09-17 头顶净空步上) 恰好低 1 格: 能清头顶就先清 (3 格高后
                // baritone pillar 可行), 清不了/已净空按普通路径 engage。
                BlockPos headroom = mod.getPlayer().getBlockPos().up(2);
                if (mod.getWorld().getBlockState(headroom).isAir()) {
                    enterBaritoneAscent(mod, "below mining floor by 1 with open headroom (y="
                            + mod.getPlayer().getBlockPos().getY() + ", floor " + tunnelFloorY() + ")");
                } else if (isClearableHeadroom(mod, headroom)) {
                    _ceilingStepUp = headroom;
                    _ceilingStepUpTimer.reset();
                    _subTask = null;
                    Debug.logToFile("altoclef_diag.log", "[MineDown-CeilingStep] 1 below floor (y="
                            + mod.getPlayer().getBlockPos().getY() + ", floor " + tunnelFloorY()
                            + "), digging headroom " + headroom.toShortString() + " for a pillar step-up.");
                    return null;
                } else {
                    enterBaritoneAscent(mod, "below mining floor (y="
                            + mod.getPlayer().getBlockPos().getY() + ", floor " + tunnelFloorY() + ")");
                }
            } else {
                enterBaritoneAscent(mod, "below mining floor (y="
                        + mod.getPlayer().getBlockPos().getY() + ", floor " + tunnelFloorY() + ")");
            }
        }

        // (2026-09-15 baritone-first 上行) 爬升阶段: 交给 baritone, 本任务只做看门狗。
        if (_baritoneAscent) {
            int y = mod.getPlayer().getBlockPos().getY();
            boolean grounded = isPlayerGrounded(mod);
            // (2026-09-17 落地判据) 半空 y==targetY 不算到顶: 2 格高隧道里 bot 只能
            // 靠跳瞬时够线, 空中 resume 曾把续挖隧道重锚到 floor−1 再触门控 (死循环
            // 链第 2 环)。空中只等落地, 不 resume、不积累看门狗。
            if (y < _targetY || !grounded) {
                if (grounded) {
                    if (y > _baritoneAscentBestY) {
                        if (y - _baritoneAscentBestY >= BARITONE_ASCENT_STALL_MIN_GAIN) {
                            _baritoneAscentStall.reset();
                        }
                        _baritoneAscentBestY = y;
                    }
                    if (_baritoneAscentStall.elapsed()) {
                        String m = "[MineDown-AscentFallback] baritone 爬升 " + BARITONE_ASCENT_STALL_SECONDS
                                + "s 净进展不足 " + BARITONE_ASCENT_STALL_MIN_GAIN + " 格 (bestY=" + _baritoneAscentBestY
                                + ") at " + mod.getPlayer().getBlockPos().toShortString() + " -> 回退自写 45° 楼梯";
                        Debug.logMessage(m);
                        Debug.logToFile("altoclef_diag.log", m);
                        _baritoneAscent = false;
                        _baritoneAscentTask = null;
                        _ascendMode = true;
                        retreatAndRedig(mod);
                    } else {
                        // (2026-09-17) baritone 可能因半空瞬时到线而"完成"过目标; 落地后
                        // 仍未到线 → 重挂导航。看门狗【不】重置, 20s 预算跨重挂累计 ——
                        // 旧实现每轮重建都 reset, 看门狗永远攒不满, 兜底楼梯永不触发
                        // (死循环链第 3 环)。
                        if (_baritoneAscentTask == null || _baritoneAscentTask.isFinished(mod)) {
                            // (2026-09-22 水中立即降级楼梯) baritone 自认"完成"但落地后
                            // 仍湿着且低于目标 = "游上去→瞬时到线→落回水里"的重挂循环:
                            // 每次 recreate 都是一次全量重算 (20260922 另机日志
                            // 23:59:50-57 七秒内重发 16 次, 每次一条 baritone 路径)。
                            // 水中不再重挂 baritone, 立即走与看门狗超时同款的楼梯回退。
                            if (isWatery(mod)) {
                                String m = "[MineDown-AscentFallback] baritone 爬升自认完成但落回水中 (y="
                                        + y + ", target " + _targetY + ") at "
                                        + mod.getPlayer().getBlockPos().toShortString()
                                        + " -> 回退自写 45° 楼梯 (水中 baritone 重挂即 thrash)";
                                Debug.logMessage(m);
                                Debug.logToFile("altoclef_diag.log", m);
                                _baritoneAscent = false;
                                _baritoneAscentTask = null;
                                _ascendMode = true;
                                retreatAndRedig(mod);
                                return null;
                            }
                            _baritoneAscentTask = new GetToYTask(_targetY);
                        }
                        setDebugState("Climbing back to y=" + _targetY + " via baritone (stall watchdog "
                                + BARITONE_ASCENT_STALL_SECONDS + "s)");
                        return _baritoneAscentTask;
                    }
                } else {
                    setDebugState("Climbing to y=" + _targetY + " (airborne, waiting to land)");
                    if (_baritoneAscentTask != null && !_baritoneAscentTask.isFinished(mod)) {
                        return _baritoneAscentTask;
                    }
                    return null;
                }
            } else {
            // (2026-09-16 爬完续挖) y >= _targetY: 到顶【不再收工】。原语义是保持
            // _baritoneAscent 让 reachedBottom 判完工 → "Shaft done" → 父层重建全新
            // 实例, _realDigs 进度/隧道方向全丢 → 隧道每次从 0/80 重来, 与"缓降触线
            // → 逃生爬升"凑成横跳死循环 (20260916-094427 五轮实锤: dug 52/80 被扔,
            // engage(y=4,floor=5) → 爬回 11 → 0/80 重挖, 3 分钟 5 轮)。改为原地切回
            // 隧道模式续挖, 真实挖掘预算保留; 预算耗尽/岩浆弃道仍照常收工。
            // (2026-09-17) 本分支现在只在【落地】后进入, resume 重锚不再空中取样。
            resumeTunnelAfterAscent(mod);
            }
        }

        // (2026-09-12 冷却语义修正, 用户"同一段矿洞放弃后又重复去探索") 原实现在此
        // 每 tick 递减 —— _caveExploreCooldownSteps 名为 STEPS 却按 tick 消耗,
        // 12 tick ≈ 0.6 秒, 注释声称的"冷却内不探测(防同一洞反复)"从未生效。
        // 09-12 日志实锤: 探索结束 22:51:52.4 之后第一个扫描点 22:51:54.1(step=3)
        // 就已经在冷却外。递减移到【真实挖掉方块】处 (见 _realDigs++ 分支), 恢复
        // "再挖 12 格通道才允许重新探洞"的原意。

        // (2026-09-14 岩浆弃道) 岩浆改道优先于水改道: 水只是麻烦, 岩浆是致命的。
        // 同样放在洞穴探索之前。
        if (_inLavaDetour) {
            // (2026-09-17 churn 修复) 本轮已判"这片挖不动"(GiveUp): 立刻收工。否则实例被
            // 多 tick 几次时, 这里会继续"撤完→计数+1→再 GiveUp", 计数一路涨到 23/4
            // (20260917-191217 实锤), 任务链每 100ms 抖一次 —— 用户观感"快速跳跃"。
            if (_lavaGiveUp) return null;
            // (2026-09-22 三修: 同 tick 竞态) 132249 实锤: staircase 弃道(cause=seen,
            // 岩浆贴身 1 格)与 CaveScan FIRE 同拍 —— abandonTunnelForLava 先设
            // _inLavaDetour=true + _subTask=RunAway, 同一 tick 洞穴扫描随后把 _subTask
            // 换成 ExploreCaveTask(探洞接管移动), 但 _inLavaDetour 残留; 30s 后本分支
            // 走"后撤超时"把 _subTask 置 null 杀掉正在跑的探索(visited=2793/4356+ 未完),
            // _inCave 分支见 null 判 "exploration done" → point-set marked (interrupted)
            // 封洞。探索接管移动后撤退已无意义(探索态岩浆守卫整条豁免, baritone 绕行
            // 岩浆), 直接清掉残留状态, 落回下方 _inCave 正常分支续跑探索。
            // 注: 撤退期间 onTick 在此提前 return, 洞穴扫描不会跑 —— _inCave 只可能
            // 在弃道同拍被置位, 本分支清残留即可, 无需处理"撤到一半被接管"的跨 tick 版本。
            if (_inCave) {
                _inLavaDetour = false;
            } else if (_subTask != null && !_subTask.isFinished(mod) && !_lavaDetourTimer.elapsed()) {
                setDebugState("Lava retreat: backing away from lava in dig path");
                return _subTask;
            } else {
            // 撤完(或超时): 换向重挖。注意与上面的 ascend 分支的交互 —— 玩家若已掉到
            // 行事线以下, ascend 分支会先把 _subTask 置空并换向, 这里紧接着再按"背离
            // 岩浆"重选一次方向, 两者不冲突 (都以玩家当前位置为新锚点)。
            boolean timedOut = _lavaDetourTimer.elapsed();
            BlockPos lava = _lastLavaPos;
            _inLavaDetour = false;
            _subTask = null;
            _lavaAbandonCount++;
            // (2026-09-17 churn 修复) 撤离收尾 (完成或超时) 后给视觉判据 2s 宽限, 见字段区
            // LAVA_SIGHT_GRACE_MS 注释。超时收尾尤其需要 —— 那是"根本没撤到位"的情况。
            _lavaSightGraceUntilMs = System.currentTimeMillis() + LAVA_SIGHT_GRACE_MS;
            String m = "[MineDown-LavaRetreat] 后撤" + (timedOut ? "超时" : "完成") + " at "
                    + mod.getPlayer().getBlockPos().toShortString()
                    + " (本轮弃道 " + _lavaAbandonCount + "/" + LAVA_ABANDON_LIMIT + ")"
                    + " lava=" + (lava != null ? lava.toShortString() : "?")
                    + " nether=" + _nether;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            if (_lavaAbandonCount >= LAVA_ABANDON_LIMIT) {
                // 4 个水平方向都试过了还撞岩浆 = 这一片就是岩浆区 (下界尤其常见),
                // 继续换向只会原地打转 → 结束本轮, 由父任务重新选址/重派。
                _lavaGiveUp = true;
                String g = "[MineDown-LavaGiveUp] 本轮弃道达上限 " + _lavaAbandonCount
                        + ", 结束本轮交回父任务 at "
                        + mod.getPlayer().getBlockPos().toShortString()
                        + " hazards=" + _lavaHazards.size();
                Debug.logMessage(g);
                Debug.logToFile("altoclef_diag.log", g);
                return null;
            }
            retreatAndRedig(mod, lava);
            }
        }

        // (2026-09-17 视觉岩浆感知) 看得见的岩浆 (含侧壁/脚下渗进已挖空间的那种) → 立刻
        // 弃道, 不再"装作看不见"。放在 _inLavaDetour 分支【之后】: 撤离中由上面接管,
        // 不重复触发; 又放在水改道与所有挖掘推进【之前】: 危险源优先, 绝不让 bot 先往
        // 前迈一步才发现脚下已经变岩浆。判据 / 合规边界 / 三档 cause 见字段区注释。
        // (2026-09-22 探索态整条豁免, 二修; 用户"碰到岩浆后还是回退挖通道了")
        // 一修(6950d5ff)只豁免 seen, 131406 实锤探索途中贴身 1 格岩浆(37,-48,-47,
        // player=38,-48,-47)走 adjacent 口径照样 abandonTunnelForLava → 探索 8.7s 被
        // RunAway 顶掉 + 撤完判 done + point-set marked 把洞封禁。
        // 语义: 本守卫保护的是【挖掘作业】(队首是岩浆/自挖空间渗入, bot 盲挖会踩);
        // 探索态没有挖掘队列, 洞内移动是 baritone 导航(岩浆不可通行, 自动绕行),
        // 无"弃道"可言 → 探索态(_inCave)整条跳过。非探索态 seen+adjacent 双口径不变。
        if (!_inCave && !_inLavaDetour && !_lavaGiveUp && !_ascendMode && !_baritoneAscent) {
            // 主判据: 仓内人类式视觉 (表面暴露 + LOS 无遮挡), 危险能在 3~12 格外被抓出来。
            BlockPos seenLava = findSeenLava(mod);
            // 兜底: 贴身 (LOS 射线在脚下/紧贴方块处几何不稳; 且贴身 = 危险已上身)。
            BlockPos hazard = seenLava != null ? seenLava : findAdjacentLava(mod);
            if (hazard != null) {
                abandonTunnelForLava(mod, hazard, seenLava != null ? "seen" : "adjacent");
            }
        }

        // (2026-09-10 遇水改道) 有界改道探索: 远离水体一轮, 结束后换向重挖。
        // 放在洞穴探索之前——水是当前挖掘路径的直接危险源, 优先于洞穴分心。
        if (_inWaterDetour) {
            if (_subTask != null && !_subTask.isFinished(mod) && !_waterDetourTimer.elapsed()) {
                setDebugState("Water detour: moving away from flooded dig path");
                return _subTask;
            }
            Debug.logMessage("[MineDown-WaterDetour] detour "
                    + (_waterDetourTimer.elapsed() ? "timed out" : "finished")
                    + ", retreating to a fresh direction.");
            Debug.logToFile("altoclef_diag.log", "[MineDown-WaterDetour] detour done at "
                    + mod.getPlayer().getBlockPos().toShortString() + ", retreatAndRedig.");
            _inWaterDetour = false;
            _subTask = null;
            // (2026-09-22 方向选水) 带上水源位置: 换向不仅排除原方向/逆方向,
            // 还排除朝向水源的方向 + 按"走行线水块数"选最干方向。旧行为只保证
            // "换个随机方向" —— 三面环水时新方向大概率还是扎进水里, 挖 2 格
            // 又触发改道, 循环烧时间。
            retreatAndRedig(mod, _lastWaterPos);
        }

        if (_inCave) {
            // We found a cave while digging: prefer exploring inside it.
            // A watchdog bounds the detour so a huge cave network can't trap
            // us forever — after CAVE_EXPLORE_TIMEOUT seconds we resume digging.
            // (2026-09-22 甲案) 预算只按"探索支线在场跑"的 tick 计数, 挂起期间冻结。
            boolean exploringNow = _subTask != null && !_subTask.isFinished(mod);
            if (exploringNow && _caveExploreActiveTicks < CAVE_EXPLORE_TIMEOUT_TICKS) {
                _caveExploreActiveTicks++;
                setDebugState("Exploring cave found while digging");
                return _subTask;
            }
            boolean caveTimedOut = exploringNow; // 还在场但预算耗尽 = 真超时; 否则 = 自然完成
            if (caveTimedOut) {
                Debug.logMessage("Cave exploration timed out after " + CAVE_EXPLORE_TIMEOUT + "s, resuming digging.");
                Debug.logToFile("altoclef_diag.log", "[MineDown-Cave] exploration timed out, resuming digging.");
            } else {
                Debug.logMessage("Cave exploration done, resuming digging.");
                Debug.logToFile("altoclef_diag.log", "[MineDown-Cave] exploration done (BiasedExplore finished), resuming digging.");
            }
            _inCave = false;
            _subTask = null;
            // (2026-09-03 区域记忆) 探索结束(超时/完成): 标记这个洞穴区域为"已探索",
            // 扁圆柱(水平16/±8, 2026-09-11 收窄)内不再触发探索 →
            // 同一大洞网络不会被打转第二次(用户"同一个矿洞打转")。
            // (2026-09-22 甲案: 超时收尾改标真洞口) 超时的语义对象是"这个洞", 中心改用
            // _lastFireAnchor(FIRE 洞锚点), 不再标 bot 当前站位 —— 09-22 实锤: 挂起 2m49s
            // 的假超时把标记落在重启站位(54,-38,-46), 距真洞口 anchor(13,-45,-34) 差 40+
            // 格, 错位封禁一块无关区域(没盖住真洞纯属侥幸)。真超时时 bot 也可能在洞深处
            // /已被逐出洞口, 站位同样不代表洞区; 洞内真走过的路径另有 markExploredCavePoints
            // 点集覆盖(ExploreCaveTask.onStop), 深处不漏标。完成收尾玩家位置是真走过的,
            // 照旧标玩家位置。锚点缺失(跨世界 onWorldBoundary 已清)退回玩家位置。
            markExploredCaveRegion(mod, caveTimedOut && _lastFireAnchor != null ? _lastFireAnchor
                    : (mod.getPlayer() != null ? mod.getPlayer().getBlockPos() : null));
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
                    // mark 难产 → 同洞反复吸引。
                    // (2026-09-17 降级为临时租约) 原实现直接 markExploredCaveRegion 写盘,
                    // 审计发现"FIRE 了但没真探索"会把洞永久封禁(实锤: 一次 4.4 秒就被打断的
                    // 探索留下两层 r16/y±8 封禁, 93 分钟后同洞 natural 从 176 掉到阈值 24),
                    // 用户现象"洞口在旁边却不进"。改取租约: 180s 内仍防同洞反复吸引, 到期
                    // 自动放开。本次探索不受影响(ExploreCaveTask 选点豁免区域记忆), 探索
                    // 结束路径的持久化标记保留(那里记的玩家位置是真走过的)。
                    markRecentCaveAnchor(mod, anchor);
                    _lastFireAnchor = anchor.toImmutable();
                    Debug.logMessage("Found cave at shaft bottom at " + anchor.toShortString() + ", exploring it.");
                    _caveExploreActiveTicks = 0;
                    // (2026-09-01 真正进洞) 专用洞内导航任务, 不再用 BiasedExploreTask
                    // (其落点被强制抬到 48 格外地表, 从未真正走进洞里)。
                    _subTask = new ExploreCaveTask(_targetY, caveWindowMinY(), caveWindowMaxY());
                    return _subTask;
                }
            }
            setDebugState("Shaft done, no ores found.");
            // (2026-09-15) 竖井正常收工 → 清 targetY 继承, 下一口井重新随机换高度。
            _lastRolledTargetY = Integer.MIN_VALUE;
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
        // (2026-09-22 用户定案"下沉过程中 y<-10 遇到矿洞就直接探索") 下沉解禁:
        // 深层(y<DESCENT_CAVE_SCAN_MIN_Y)下沉途中同款扫描, y≥-10 浅层仍不扫。
        // 冷却内不探测(防同一洞反复), 步进门控与隧道模式共用。
        boolean descentDeepScan = !_tunnelMode && !_ascendMode && !_baritoneAscent
                && mod.getPlayer().getBlockPos().getY() < DESCENT_CAVE_SCAN_MIN_Y;
        if ((_tunnelMode || descentDeepScan)
                && _caveExploreCooldownSteps <= 0 && _step % CAVE_SCAN_EVERY_STEPS == 0) {
            Optional<BlockPos> cave = findNearbyCave(mod);
            if (cave.isPresent()) {
                _inCave = true;
                BlockPos anchor = cave.get();
                // (2026-09-07 FIRE 时即标记, 同 reachedBottom 分支注释)。
                // (2026-09-17 降级为临时租约, 同上)。
                markRecentCaveAnchor(mod, anchor);
                _lastFireAnchor = anchor.toImmutable();
                Debug.logMessage("Found cave while digging at " + anchor.toShortString() + ", exploring it.");
                Debug.logToFile("altoclef_diag.log",
                        "[MineDown-Cave] found cave while digging at " + anchor.toShortString() + ", exploring it first.");
                _caveExploreActiveTicks = 0;
                // (2026-09-01 真正进洞) 专用洞内导航任务: 直奔最远天然洞空气,
                // 到达后重扫跟深(见 ExploreCaveTask), 25s 看门狗兜底不影响挖矿主线。
                // (2026-09-02 传 targetY) 导航目标限目标深度附近, 不导航到上方浅层。
                _subTask = new ExploreCaveTask(_targetY, caveWindowMinY(), caveWindowMaxY());
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
                // (2026-09-12 冷却语义修正) 洞穴探索冷却按【真实挖掉的方块数】消耗,
                // 不再是每 tick 递减(见 onTick 顶部的说明)。
                if (_caveExploreCooldownSteps > 0) {
                    _caveExploreCooldownSteps--;
                }
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
            return new SatisfyMiningRequirementTask(MiningRequirement.WOOD, "mine-down-explore/cave-dig");
        }

        // (2026-09-10 空跳修复) 进度显示改为真实挖掘预算消耗 (dug X/Y),
        // _step 已是几何路径索引, 空跳后会远超步数上限, 显示出来有误导性。
        int budget = (_ascendMode ? MAX_ASCEND_DIG_STEPS : MAX_DIG_STEPS) * BLOCKS_PER_STEP;
        setDebugState((_ascendMode ? "Climbing back up to target Y (dug "
                : _tunnelMode ? "Digging a tunnel to find ores (dug "
                : "Digging down to find ores (dug ") + _realDigs + "/" + budget + ")");
        BlockPos head = _digQueue.peek();
        // (2026-09-14 v2 目标跑到头顶 → 重锚) 队首已经高出玩家 MAX_TARGET_ABOVE_FEET
        // 格以上 ⇒ 它是旧锚点推出的"空气几何", 派下去 bot 只会垫塔横跳 (洗点见
        // MAX_TARGET_ABOVE_FEET 注释)。先把锚点拉回玩家当前位置, 下一 tick 从新
        // 队列派发。1 秒节流: 万一玩家脚下整段都是旧隧道空气 (advanceToSolid 会连续
        // 快进), 也不至于每 tick 重锚。
        if (head != null
                && head.getY() - mod.getPlayer().getBlockPos().getY() > MAX_TARGET_ABOVE_FEET
                && System.currentTimeMillis() - _lastReanchorMs > 1000) {
            reanchorToPlayer(mod);
            return null;
        }
        // (2026-09-14 挖掘停滞放弃) 队首没换 = 上一格还没挖掉。超时 ⇒ 它有某种我们
        // 不知道的障碍, 继续派发 DestroyBlockTask 只会让它左右键到天荒地老。
        // 与"水/门体/不可破坏"同款处置: 从队列弹掉, 几何路径继续往前。[MineDown-DigStuck]
        // 会带上那个方块的 id, 便于事后分清是深板岩太硬还是重力方块在掉。
        if (head != null) {
            long now = System.currentTimeMillis();
            if (head.equals(_stallTarget)) {
                long elapsed = now - _stallTargetSinceMs;
                if (elapsed > DIG_TARGET_STALL_TIMEOUT_MS) {
                    _digQueue.poll();
                    // (2026-09-22 挖不动名单) 该格 10s 超时已证明"挖不动/够不着",
                    // 记入名单后 reanchor/实例重建重新铺几何路径时直接跳过, 不再
                    // 重演"同一格反复 giving up" (20260922 日志实锤: 24,10,27
                    // 连续 5 次, 每次烧 10s)。
                    rememberGiveUp(head);
                    Debug.logMessage("[MineDown] giving up on stuck target " + head.toShortString()
                            + " after " + elapsed + "ms, advancing past it.");
                    Debug.logToFile("altoclef_diag.log", "[MineDown-DigStuck] 队首长时间挖不动 -> 放弃 "
                            + head.toShortString() + " elapsed=" + elapsed + "ms player="
                            + mod.getPlayer().getBlockPos().toShortString()
                            + " mode=" + (_tunnelMode ? "tunnel" : _ascendMode ? "ascend" : "staircase")
                            + " block=" + mod.getWorld().getBlockState(head).getBlock().getTranslationKey());
                    _stallTarget = null;
                    return null;
                }
            } else {
                _stallTarget = head;
                _stallTargetSinceMs = now;
            }
        } else {
            _stallTarget = null;
        }
        _miningTarget = head;
        return new DestroyBlockTask(_miningTarget);
    }

    /**
     * 放弃当前挖掘轨迹，从玩家当前位置随机换一个水平方向
     * （与旧方向不同）重新开始楼梯/隧道。
     */
    private void retreatAndRedig(AltoClef mod) {
        retreatAndRedig(mod, null);
    }

    /**
     * @param awayFrom 需要背离的危险点 (岩浆/水), 传 null 时只保证换一个方向。
     *
     * (2026-09-14 岩浆弃道) 原实现只排除"与旧方向相同", 换出来的新方向有 1/3 概率
     * 正好朝着刚撤出来的那处岩浆 —— 撤了等于白撤, 挖两格又撞回去, 直接把
     * LAVA_ABANDON_LIMIT 烧穿。这里加一条: 新方向与 (危险点 - 玩家) 的水平分量同向
     * 则重选。试 12 次仍选不出 (四面都是岩浆的极端情况) 就接受最后一个, 由上限兜底。
     *
     * (2026-09-22 方向选水) 随机重试改为【评分选最干】: 对每个合法方向沿走行线
     * 前看 DIR_WATER_LOOKAHEAD 格统计水块数, 取最少者 (平手随机)。三面环水时
     * 至少选中水最少的那一面, 不再 50% 概率继续扎进水里。
     */
    private void retreatAndRedig(AltoClef mod, BlockPos awayFrom) {
        _digQueue.clear();
        _step = 0;
        _skipStreak = 0;
        _miningTarget = null;
        BlockPos feet = mod.getPlayer().getBlockPos();
        // (2026-09-22 行事线对齐) 锚点规则与 reanchorToPlayer 同源: 上行/隧道锚
        // 脚所在格 (走行线 = 脚 y), 下行楼梯锚脚下地板。旧实现无条件 feet.down(),
        // 水改道后隧道走行线比脚低 1 格 → 立刻再撞 floor 门控触发爬升 (高度调整
        // 不灵活的结构性来源之一, 20260917-183338 同款)。
        _digStart = (_ascendMode || _tunnelMode) ? feet : feet.down();
        int[] newDir = pickDriestDir(mod, feet,
                new int[]{_dirX, _dirZ}, new int[]{-_dirX, -_dirZ}, awayFrom);
        if (newDir == null) {
            // 合法方向全被排除 (四面都是危险源的极端情况): 兜底随机, 由弃道上限兜底。
            newDir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
        }
        _dirX = newDir[0];
        _dirZ = newDir[1];
        rememberTunnelDir(_dirX, _dirZ);
    }

    /**
     * (2026-09-22 方向选水) 在 4 个 cardinal 方向里选"走行线最干"的方向。
     * 排除: sameDir (原方向)、reverseDir (回头)、朝向 awayFrom 的方向。
     * 每方向沿走行线前看 DIR_WATER_LOOKAHEAD 格数水块 (区块未加载按干处理)。
     *
     * @return 最干方向; 全部被排除时返回 null (调用方兜底)。
     */
    private int[] pickDriestDir(AltoClef mod, BlockPos from,
                                int[] sameDir, int[] reverseDir, BlockPos awayFrom) {
        int[] best = null;
        int bestWater = Integer.MAX_VALUE;
        for (int[] cand : TUNNEL_DIRS) {
            if (sameDir != null && cand[0] == sameDir[0] && cand[1] == sameDir[1]) continue;
            if (reverseDir != null && cand[0] == reverseDir[0] && cand[1] == reverseDir[1]) continue;
            if (pointsToward(from, cand, awayFrom)) continue;
            int w = countWaterAhead(mod, from, cand);
            if (w < bestWater || (w == bestWater && Math.random() < 0.5)) {
                bestWater = w;
                best = cand;
            }
        }
        return best;
    }

    /**
     * (2026-09-22 方向选水) 统计 dir 方向前 DIR_WATER_LOOKAHEAD 格走行线上的水块数。
     * 走行线取 from 沿 dir 的水平推进 (与隧道 ground 同源: ground.y = from.y;
     * 上行楼梯用脚 y 评也够 —— 满区水时低层水就是洪泛区的一致代理)。
     * 区块未加载视为干 (无法预知, 与 isUnbreakable 同口径)。
     */
    private int countWaterAhead(AltoClef mod, BlockPos from, int[] dir) {
        int water = 0;
        BlockPos p = from;
        for (int i = 0; i < DIR_WATER_LOOKAHEAD; i++) {
            p = p.add(dir[0], 0, dir[1]);
            if (!mod.getChunkTracker().isChunkLoaded(p)) break;
            if (isWater(mod, p)) water++;
        }
        return water;
    }

    /** 方向 dir 是否朝向 hazard (只看水平分量; 同格/无法判定时不算朝向)。 */
    private boolean pointsToward(BlockPos from, int[] dir, BlockPos hazard) {
        if (hazard == null) return false;
        int dx = hazard.getX() - from.getX();
        int dz = hazard.getZ() - from.getZ();
        return dx * dir[0] + dz * dir[1] > 0;
    }

    // ==== (2026-09-22 防回头 / 挖不动名单) 辅助 ====

    /** 上一条隧道方向是否有记忆 (static, 跨实例存续)。 */
    private static boolean hasLastTunnelDir() {
        return _lastTunnelDirX != 0 || _lastTunnelDirZ != 0;
    }

    private static void rememberTunnelDir(int x, int z) {
        _lastTunnelDirX = x;
        _lastTunnelDirZ = z;
    }

    /** 记录一个"挖不动被放弃"的队首格 (FIFO 有界, 跨实例存续)。 */
    private static void rememberGiveUp(BlockPos pos) {
        BlockPos p = pos.toImmutable();
        _recentGiveUps.remove(p);
        _recentGiveUps.addLast(p);
        while (_recentGiveUps.size() > RECENT_GIVEUP_MAX) {
            _recentGiveUps.pollFirst();
        }
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
        // (2026-09-14 岩浆弃道) _inLavaDetour 时【不得再沿几何路径延伸】: 弃道已经把
        // 队列清空了, 若这里继续 enqueueNextStep, 弃道等于没弃 —— 队列会被立刻重新
        // 填满并再次撞上同一处岩浆。
        while (_digQueue.isEmpty() && !_inLavaDetour && !reachedBottom(mod) && _skipStreak < SKIP_STEPS_PER_TICK) {
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

    /**
     * 跳过"挖不了 / 不该挖"的队首方块: 空气、水、门体保护方块。
     *
     * (2026-09-13 传送门保护) 原只跳空气与水。下界挖金矿时隧道几何路径恰好穿过
     * 返回门框, 队首是黑曜石门框 → 无过滤地派发 DestroyBlockTask → 打穿门框,
     * 门熄灭 (20260913-185026 实锤: dim=the_nether pos=(8,90,-57), 栈
     * CollectGoldIngotTask > MineAndCollectTask > MineOrCollectTask >
     * MineDownExploreTask > DestroyBlockTask)。
     *
     * 门体按与"水"相同的方式处置: 从队列弹掉, 隧道继续几何推进, 绝不试图挖它。
     * 这是本事故的**主路径修复**; DestroyBlockTask 侧另有一道独立硬守卫兜底
     * (覆盖全库其他 40+ 个派发点), 两者判据同源 (BotBehaviour.isPortalBodyBlock)。
     */
    private void skipAirAndWater(AltoClef mod) {
        while (!_digQueue.isEmpty()) {
            BlockPos head = _digQueue.peek();
            if (isLava(mod, head)) {
                // (2026-09-14 岩浆弃道) 必须排在【水之前】: 旧判据 isWater() 用
                // `instanceof FluidBlock`, 岩浆会被它先吃掉, 然后按水的方式"跳过
                // 继续走" —— 直接走进岩浆。这里命中后立刻弃道并 return, 不 poll、
                // 不继续几何推进。判据只取队首 (即将挖的那一格), 不扫周边: 队首是
                // 岩浆意味着它前方那格已经挖开、岩浆就在眼前, 属于合规的"亲眼看到"。
                abandonTunnelForLava(mod, head);
                return;
            }
            // (2026-09-22 挖不动名单) 曾经 10s 超时被放弃的格子: 几何路径重新铺到
            // 它时直接弹掉继续推进, 不再派发 DestroyBlockTask 重试。排在岩浆之后
            // (安全优先)、水之前 (名单内格子是实体方块, 两者不会重叠, 顺序无实义)。
            if (_recentGiveUps.contains(head)) {
                _digQueue.poll();
                continue;
            }
            if (isWater(mod, head)) {
                logWaterEncounter(mod, head);
            } else if (mod.getBehaviour().isPortalBodyBlock(head)) {
                logProtectedDigSkip(mod, head);
            } else if (isUnbreakable(mod, head)) {
                // (2026-09-14 不可破坏拦截, 主路径) 隧道几何路径撞上基岩类方块
                // (hardness<0, 1.18.2 基岩带最高到 y=-60; 09-14 前钻石带下沿 -59, 下行要挖
                // -60 行, 恰好贴着基岩顶 —— 该带已于同日上抬到 [-57,-53], 正常情况不再
                // 碰到这一行, 本拦截改为兜底而非常态路径)。旧逻辑无过滤派发
                // DestroyBlockTask → "Block in range, mining..." 无限左键, 20260914-132420
                // 实锤: 对 (-214,-60,279) 傻挖 17s 到用户手动退出 (snapshot.log reason=stuck
                // 每秒一条, dug 卡 3/80), 唯一逃逸是 45s "Mining stalled" 兜底。与水/门体
                // 同款处置: 从队列弹掉, 几何继续推进 —— 脚行被弹掉等于跨过去的一格台阶,
                // 头行在基岩带之上不会出现基岩(1.18.2 基岩带 -64~-60), 不会造成 1 格高
                // 不可通行断面。DestroyBlockTask 侧另有独立硬守卫兜底(覆盖其他
                // 40+ 派发点), 判据同源 (getHardness < 0)。
                logUnbreakableSkip(mod, head);
            } else if (!WorldHelper.isAir(mod, head)) {
                break;
            }
            _digQueue.poll();
        }
    }

    /**
     * (2026-09-14 不可破坏拦截) 目标方块 hardness<0 (基岩/末地门框/强化深板岩)
     * → 物理上永远挖不掉。区块未加载时不判负 (未加载读作空气, 与本仓 isAir 同款语义)。
     */
    private boolean isUnbreakable(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
        return mod.getWorld().getBlockState(pos).getHardness(mod.getWorld(), pos) < 0;
    }

    /**
     * (2026-09-14 不可破坏拦截) 记录一次"隧道跳过不可破坏方块"。节流口径同
     * [Diag-water] / [PortalGuard] (前 3 次 + 每 10 次)。
     */
    private void logUnbreakableSkip(AltoClef mod, BlockPos pos) {
        _unbreakableSkipped++;
        if (_unbreakableSkipped <= 3 || _unbreakableSkipped % 10 == 0) {
            String m = "[MineDown-Unbreakable] 隧道跳过不可破坏方块 " + pos.toShortString()
                    + " (hardness<0 基岩类, 累计 " + _unbreakableSkipped + ")"
                    + " player=" + mod.getPlayer().getBlockPos().toShortString()
                    + " mode=" + (_tunnelMode ? "tunnel" : "staircase");
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
    }

    /**
     * (2026-09-13 传送门保护) 记录一次"隧道跳过门体方块"。节流口径同 [Diag-water]
     * (前 3 次 + 每 10 次), 防门框密集区刷屏。
     */
    private void logProtectedDigSkip(AltoClef mod, BlockPos pos) {
        _protectedDigSkipped++;
        if (_protectedDigSkipped <= 3 || _protectedDigSkipped % 10 == 0) {
            String m = "[PortalGuard] 隧道跳过受保护方块 " + pos.toShortString()
                    + " (门体/门框, 累计 " + _protectedDigSkipped + ")"
                    + " player=" + mod.getPlayer().getBlockPos().toShortString()
                    + " mode=" + (_tunnelMode ? "tunnel" : "staircase");
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
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
            // 平挖 — 主世界 TUNNEL_MIN_Y(-58) 是最后安全行事线, 下界照搬会一路挖到基岩。
            // (2026-09-10 带感知下限, 用户"挖铁不应始终呆在 y=负值") 主世界 floor =
            // max(TUNNEL_MIN_Y, 带下限): 铁带 [5,30] 隧道缓降到 y=5 为止, 不再一路
            // 沉到地板平挖; 钻石带 [-57,-53] → max(-58,-57)=-57 (带下限主导; 见
            // MineAndCollectTask.targetShaftBand 09-14 注释: 再往下就要挖到 -60 基岩行);
            // {50,50} 兜底带回到"平挖在 50"的原意 (旧实现会意外缓沉到 -24)。
            int floorY = _nether ? _targetY : tunnelFloorY();
            int drop = Math.max(0, Math.min(_digStart.getY() - floorY, k / TUNNEL_DESCEND_EVERY));
            ground = _digStart.add(k * _dirX, -drop, k * _dirZ);
        } else {
            ground = _digStart.add(k * _dirX, -k, k * _dirZ);
        }
        // (2026-09-14 v2 上行顺序修正) 旧实现三种模式统一"先头行后脚行" (up 先入队
        // = FIFO 先挖)。下行/平挖没问题 —— up 是前进方向的墙, 挖开才走得过去; 但
        // 【上行】反了: ground 是玩家要踩上去的那一格台阶, 它还实心时玩家永远留在
        // 下一层的坑里, 队首的 up 却已经抬到头顶 ⇒ 只能靠垫塔去够。现场 20260914-
        // 162715: 玩家 (-220,-60,230), 队首却是 (-220,-57,229) —— 头顶 3 格。
        // 上行必须先挖要踩的那一格, 再挖它的净空。
        if (_ascendMode) {
            _digQueue.add(ground);
            _digQueue.add(ground.up());
        } else {
            _digQueue.add(ground.up());
            _digQueue.add(ground);
        }
    }

    /**
     * (2026-09-14 v2 目标跑到头顶 → 重锚) _digStart 只在开工时锚定【一次】, 之后
     * 玩家移动了它不跟着走 —— 45° 上行的 ground(k) = digStart + k*(dir, +1, dir)
     * 是纯几何递推, k 每 +1 目标就抬升 1 格。旧通道里的空气还会被 advanceToSolid
     * 快速跳过, 进一步拉大 k ⇒ 目标一路飞到玩家头顶 3 格以上, 而 DestroyBlockTask
     * 拿到这种目标只能垫塔去够 → 横跳 (见 DestroyBlockTask._pillarBaseFeet 注释)。
     *
     * 人类做法是不存在的 —— 人不会对着自己头顶 3 格的方块开挖。正解: 把自己当前
     * 位置重新当作锚点, 从脚下重新铺第一段台阶。
     */
    private void reanchorToPlayer(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        _lastReanchorMs = System.currentTimeMillis();
        // 上行: 锚点取【脚所在的空气格】, 下一轮 ground(k=1) 才是"前进一步 + 上一格";
        // 取 .down() (实心地板) 会先把脚下的支撑挖掉, 玩家反而掉下去。
        // (2026-09-17 行事线对齐) 隧道平挖同上行取脚所在格: 走行线 = _digStart.y,
        // 取 .down() 会让走行线比脚低 1 格, 撞 floor 门控 (20260917-183338 实锤)。
        // 下行楼梯: 保持原意 —— 锚点是脚下的地板, 挖开它就是"往下挖"。
        _digStart = (_ascendMode || _tunnelMode) ? feet : feet.down();
        _step = 0;
        _digQueue.clear();
        enqueueNextStep();
        String m = "[MineDown-Reanchor] 队首高于玩家 " + MAX_TARGET_ABOVE_FEET
                + " 格以上, 以当前位置重锚 -> " + _digStart.toShortString()
                + " mode=" + (_tunnelMode ? "tunnel" : _ascendMode ? "ascend" : "staircase")
                + " player=" + feet.toShortString();
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    /**
     * 注意 (2026-09-14): FluidBlock 同时是 WaterBlock 和 LavaBlock 的父类, 单看这条
     * 判据【岩浆也会被算作水】。调用方 skipAirAndWater 已把 isLava 排在前面拦截,
     * 所以走到这里的一定不是岩浆。别在别处单独复用本方法当"能安全走过去的液体"用。
     */
    private boolean isWater(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).getBlock() instanceof net.minecraft.block.FluidBlock;
    }

    /**
     * (2026-09-14 岩浆弃道) 岩浆判定。用 Blocks.LAVA 而不是 FluidBlock 的流体状态:
     * 源岩浆和流动岩浆都是同一个 Block 实例 (只是 state.level 不同), 一律算危险。
     * 区块未加载时不判危险 (未加载读作空气, 与本仓 isAir 同款语义, 见 isUnbreakable)。
     */
    private boolean isLava(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
        return mod.getWorld().getBlockState(pos).getBlock() == Blocks.LAVA;
    }

    /**
     * (2026-09-17 视觉岩浆感知, 主判据) 在玩家周围 (水平 {@value #LAVA_SIGHT_RADIUS_XZ} /
     * 垂直 {@value #LAVA_SIGHT_RADIUS_Y} 的椭球) 找【看得见】的岩浆, 命中返回坐标。
     *
     * "看得见"走仓内统一视觉入口 {@link VisibilityHelper#isBlockPerceivable}: 岩浆必须先过
     * "表面暴露(正上方是空气/洞穴空气)"前置, 再过 LOS 射线。所以:
     *   - 侧壁/脚下渗进已挖空间的岩浆, 上方就是通道空气 → 暴露 → 看得见 → 弃道;
     *   - 隔着岩壁、或被岩石包裹的源头岩浆, 表面被封 → 判不可见 → 不触发 (反透视边界不变);
     *   - 前提仍是区块已加载 (isLava 里的 isChunkLoaded)。
     *
     * 成本: 椭球约 8k 格 getBlockState / 200ms, 只在"是岩浆"的少数格上打 LOS 射线, 而
     * LOS 自带 400ms 短缓存 (VisibilityHelper.isBlockVisible), 不会退化成每帧射线风暴。
     */
    private BlockPos findSeenLava(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        long now = System.currentTimeMillis();
        // (2026-09-17 churn 修复) 刚撤完的宽限期, 见字段区 LAVA_SIGHT_GRACE_MS 注释。
        if (now < _lavaSightGraceUntilMs) return null;
        if (now - _lastLavaSightScanMs < LAVA_SIGHT_SCAN_INTERVAL_MS) return null;
        _lastLavaSightScanMs = now;
        BlockPos feet = mod.getPlayer().getBlockPos();
        int xzSq = LAVA_SIGHT_RADIUS_XZ * LAVA_SIGHT_RADIUS_XZ;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dy = -LAVA_SIGHT_RADIUS_Y; dy <= LAVA_SIGHT_RADIUS_Y; dy++) {
            for (int dx = -LAVA_SIGHT_RADIUS_XZ; dx <= LAVA_SIGHT_RADIUS_XZ; dx++) {
                for (int dz = -LAVA_SIGHT_RADIUS_XZ; dz <= LAVA_SIGHT_RADIUS_XZ; dz++) {
                    int hSq = dx * dx + dz * dz;
                    if (hSq > xzSq) continue; // 椭球水平截断
                    BlockPos p = feet.add(dx, dy, dz);
                    if (!isLava(mod, p)) continue; // 便宜的血缘复核 (区块未加载/已非岩浆直接跳过)
                    if (!VisibilityHelper.isBlockPerceivable(mod, p)) continue; // 贵的那步: 表面暴露 + LOS
                    double d = hSq + (double) dy * dy;
                    if (d < bestSq) {
                        bestSq = d;
                        best = p; // 取最近的一处: 撤离目标/后撤方向都更准
                    }
                }
            }
        }
        return best;
    }

    /**
     * (2026-09-17 视觉岩浆感知, 贴身兜底) 扫【与玩家紧邻的 11 格】(见 NEAR_LAVA_OFFSETS),
     * 命中返回该岩浆格坐标, 否则 null。
     *
     * 为什么在视觉判据之外还要留这一层: ① cleanLineOfSight 对脚下/紧贴方块的几何本就
     * 不稳 (同仓 CRAFTING_TABLE_PERCEIVE_DISTANCE 16 格豁免的教训); ② 紧邻格已是岩浆
     * 意味着危险已经贴上身体, 不能因为射线没打准就放过。
     *
     * 顺序刻意从"最贴近玩家"开始 (玩家所在格 → 水平四邻 → 脚下 → 斜下 → 头顶);
     * 命中哪一格只影响日志与 hazards 记录, 弃道动作 (MineDown-LavaAbandon) 完全一致。
     */
    private BlockPos findAdjacentLava(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        long now = System.currentTimeMillis();
        if (now - _lastNearLavaScanMs < NEAR_LAVA_SCAN_INTERVAL_MS) return null;
        _lastNearLavaScanMs = now;
        BlockPos feet = mod.getPlayer().getBlockPos();
        for (BlockPos off : NEAR_LAVA_OFFSETS) {
            BlockPos p = feet.add(off);
            if (isLava(mod, p)) return p;
        }
        return null;
    }

    /**
     * (2026-09-17 churn 修复) 从"见过即记忆"表把附近的岩浆播进本轮 hazards。
     *
     * 只读仓内既有的感知记忆 {@link VisibilityHelper#getRememberedValuable} (按维度分表,
     * 只收【真感知过】的岩浆, 不是扫描/透视), 半径 {@value #LAVA_MEMORY_SEED_RANGE} 外
     * 的点自然被距离过滤挡掉, 最多播 {@value #LAVA_MEMORY_SEED_LIMIT} 处, 防止历史记忆
     * 把 RunAwayFromPositionTask 的"远离所有目标"变成无解。
     *
     * 用途单一: 让 GiveUp 后重建的新实例在 retreatAndRedig 换向时能背离这些点, 而不是
     * 一头再撞回同一处岩浆。
     */
    private void seedLavaHazardsFromMemory(AltoClef mod) {
        if (mod.getPlayer() == null) return;
        BlockPos feet = mod.getPlayer().getBlockPos();
        int added = 0;
        for (BlockPos p : VisibilityHelper.getRememberedValuable(mod, Blocks.LAVA)) {
            if (added >= LAVA_MEMORY_SEED_LIMIT) break;
            if (!feet.isWithinDistance(p, LAVA_MEMORY_SEED_RANGE)) continue;
            if (_lavaHazards.add(p.toImmutable())) added++;
        }
        if (added > 0) {
            String m = "[MineDown-LavaSeed] 从记忆播种 " + added + " 处岩浆 hazard (range "
                    + (int) LAVA_MEMORY_SEED_RANGE + ") at " + feet.toShortString()
                    + " -> 后续换向会背离这些点";
            Debug.logToFile("altoclef_diag.log", m);
        }
    }

    /**
     * (2026-09-14 岩浆弃道) 立即放弃当前这条通道: 清空挖掘队列、切到后撤子任务,
     * 等后撤结束后由 onTick 的 _inLavaDetour 分支换向重挖。
     *
     * 与水的三点本质差异 (共用一套代码会出人命):
     *   1. 触发阈值 1 次 (水要攒 2 次);
     *   2. 不 poll 掉这一格继续走 (水能走过去, 岩浆不能);
     *   3. 后撤完【换方向】, 不回原通道继续挖。
     */
    private void abandonTunnelForLava(AltoClef mod, BlockPos lava) {
        abandonTunnelForLava(mod, lava, "dig-queue-head");
    }

    /**
     * (2026-09-17 近场岩浆感知) cause 用于现场区分触发口径, 两条路径共用同一套弃道动作:
     *   dig-queue-head = 待挖队首那格就是岩浆 (09-14 原有, "挖穿后岩浆就在眼前");
     *   near-player    = 与玩家紧邻的已挖开空间出现岩浆 (侧壁/脚下渗入, 09-17 新增)。
     */
    private void abandonTunnelForLava(AltoClef mod, BlockPos lava, String cause) {
        // (2026-09-17 churn 修复) 本轮已判"这片挖不动"时不再累积/派发: 防 GiveUp 之后
        // 实例被多 tick 几次又转起来 (20260917-191217 实锤: GiveUp 后计数仍从 5 涨到 23)。
        if (_lavaGiveUp) return;
        _lavaEncounters++;
        BlockPos lavaFixed = lava.toImmutable();
        _lavaHazards.add(lavaFixed);
        if (_lavaEncounters <= 3 || _lavaEncounters % 10 == 0) {
            String m = "[MineDown-Lava] 挖通道撞上岩浆 " + lavaFixed.toShortString()
                    + " (y=" + lavaFixed.getY() + ", 累计 " + _lavaEncounters
                    + ", cause=" + cause + ")"
                    + " player=" + mod.getPlayer().getBlockPos().toShortString()
                    + " mode=" + (_tunnelMode ? "tunnel" : _ascendMode ? "ascend" : "staircase")
                    + " step=" + _step + " nether=" + _nether;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
        if (_inLavaDetour) return; // 已经在撤了, 别把计时器和撤离目标重置掉
        _digQueue.clear();
        _step = 0;
        _skipStreak = 0;
        _miningTarget = null;
        _stallTarget = null;
        _inLavaDetour = true;
        _lavaDetourTimer.reset();
        _lastLavaPos = lavaFixed;
        double dist = _nether ? LAVA_RETREAT_NETHER : LAVA_RETREAT_OVERWORLD;
        // maintainY = 当前高度: 后撤只许水平跑, 不许上下乱窜 —— 下界往上跑可能一头
        // 扎进头顶的岩浆海, 往下跑可能踩空。逃跑目标带上本轮所有见过的岩浆点, 避免
        // 在两处岩浆之间来回。
        _subTask = new RunAwayFromPositionTask(dist, mod.getPlayer().getBlockPos().getY(),
                _lavaHazards.toArray(new BlockPos[0]));
        String m = "[MineDown-LavaAbandon] 弃道 -> 后撤 " + (int) dist + " 格, 撤完换方向"
                + " (撤离目标 " + _lavaHazards.size() + " 处, 上限 "
                + (int) LAVA_DETOUR_TIMEOUT + "s) from " + lavaFixed.toShortString()
                + " player=" + mod.getPlayer().getBlockPos().toShortString();
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    /**
     * [Diag-water] log every water encounter (throttled to the first few +
     * every 10th, so a flooded shaft doesn't spam the log).
     */
    private void logWaterEncounter(AltoClef mod, BlockPos pos) {
        _waterSkipped++;
        // (2026-09-22 方向选水) 记下水源, 水改道结束时 retreatAndRedig 背离它。
        _lastWaterPos = pos.toImmutable();
        if (_waterSkipped <= 3 || _waterSkipped % 10 == 0) {
            String m = "[Diag-water] shaft/tunnel hit water at " + pos.toShortString()
                    + " (y=" + pos.getY() + ", skipped so far: " + _waterSkipped + ")"
                    + " player=" + mod.getPlayer().getBlockPos().toShortString()
                    + " mode=" + (_tunnelMode ? "tunnel" : "staircase")
                    + " step=" + _step + "/" + MAX_DIG_STEPS;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
        // (2026-09-10 遇水改道) 路径真实水块累计达标 → 有界改道一轮。
        if (!_inWaterDetour && _waterSkipped - _lastDetourWaterMark >= WATER_DETOUR_TRIGGER_SKIPS) {
            _lastDetourWaterMark = _waterSkipped;
            _inWaterDetour = true;
            _waterDetourTimer.reset();
            _subTask = new RunAwayFromPositionTask(WATER_DETOUR_RUN_DISTANCE, pos.toImmutable());
            String m = "[MineDown-WaterDetour] dig path hit water (total " + _waterSkipped
                    + ") -> detour away from " + pos.toShortString()
                    + " (run " + (int) WATER_DETOUR_RUN_DISTANCE + " blocks, max "
                    + (int) WATER_DETOUR_TIMEOUT + "s)";
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
        // (2026-09-15 baritone-first 上行) baritone 爬升: 回到目标高度即完成
        // (与 ascend 楼梯同语义; bot 可能超过目标高度一点, 一样算回带内)。
        // (2026-09-17 落地判据, 20260917-183338 实锤) 半空瞬时到线曾被判"到顶" →
        // isFinished TRUE → 父层重建/resume 空中重锚 → floor 门控再 engage 死循环。
        // 到线必须落地才算 (与 ITaskRequiresGrounded 同口径)。
        if (_baritoneAscent) return mod.getPlayer().getBlockPos().getY() >= _targetY && isPlayerGrounded(mod);
        // (2026-09-05 下界基岩层逃生) 上行模式: 爬回目标高度即完成 (同上, 落地才算)。
        if (_ascendMode) return mod.getPlayer().getBlockPos().getY() >= _targetY && isPlayerGrounded(mod);
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
        // (2026-09-17 跨世界污染) 旧口径只按【维度】→ 上个存档的自挖隧道在新存档
        // 里被当"自己挖过的空气"(那些位置在新存档很可能是实心天然岩石), 直接
        // 错判矿洞/空气判据。改成 存档:维度, 与洞区记忆同口径; 旧格式记录自然
        // 查不到(自动作废)。
        return adris.altoclef.util.helpers.WorldIdentity.key(mod);
    }

    private static java.nio.file.Path selfDugFile() {
        // (2026-09-10) 迁入 logs/altoclef/memory/, 旧根目录文件自动迁移。
        return adris.altoclef.Debug.memoryFile(SELF_DUG_FILE, "selfdug.log");
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
    /**
     * (2026-09-13 遇水改道交接) 交接闸门用 —— "正在遇水改道" 与 "正在探洞" 一样
     * 是必须被保护的中途状态。旧闸门只看 isExploringCave()，而改道期间 _inCave=false
     * → 闸门判"上一个探索已结束" → 丢弃实例重建 → onStart 把方向强制回常量
     * STAIR_DIR_X(1)/STAIR_DIR_Z(0)、水计数归零 → 沿原轨迹重挖撞同一片水。
     * 09-13 16:38 实锤: 4 轮改道全锁在 x=71~91 / y=54~64 / z=-470~-480 这 20 格内。
     */
    public boolean isInWaterDetour() { return _inWaterDetour; }
    public boolean isNetherTask() { return _nether; }
    public static BlockPos getLastFireAnchor() { return _lastFireAnchor; }

    /**
     * (2026-09-13 探洞双边窗口) 本次任务认可为"本层矿洞"的天然空气高度【上限】。
     * 围绕目标【矿带上限】而非随机出来的单点 _targetY —— 单点会让同一条带里
     * 随机到高值的实例把整条带的上沿都算进来 (钻石 -53 时 -53+8=-45 与
     * -57+8=-49 只差 4 格, 但语义上应当统一用带的上沿)。
     * 仍受 CAVE_NATURAL_AIR_MAX_Y 全局硬顶约束 (防自挖楼梯连到地表)。
     */
    int caveWindowMaxY() {
        // (2026-09-16 下界差异化) 主世界硬顶 45 在下界会把矿带 [32,96] 的上半截
        // 全部拒掉(0916 实锤 aboveMaxY=280) — 下界用自己的硬顶, 矿带上沿 + 8 照常生效。
        if (_nether) return Math.min(NETHER_NATURAL_AIR_MAX_Y, _targetYMax + CAVE_ABOVE_TARGET_BUFFER);
        return Math.min(CAVE_NATURAL_AIR_MAX_Y, _targetYMax + CAVE_ABOVE_TARGET_BUFFER);
    }

    /**
     * (2026-09-13 探洞双边窗口) 本次任务认可为"本层矿洞"的天然空气高度【下限】。
     * 围绕目标【矿带下限】—— 隧道会缓降到 floorY=max(TUNNEL_MIN_Y, 带下限),
     * 所以带下限就是 bot 实际活动的最低层, 再往下 BELOW 格内的洞仍值得看一眼。
     */
    int caveWindowMinY() {
        return _targetYMin - CAVE_BELOW_TARGET_BUFFER;
    }

    private static String exploredCaveKey(AltoClef mod) {
        // (2026-09-17 跨世界污染) 显示名 → 存档目录名, 统一走 WorldIdentity。
        // 旧 key 的存量"已探索洞区"记录自然作废(它们本就有同名世界互灌风险)。
        // (2026-09-17 FIRE 标记降级) 版本后缀 #v2: 本版起 FIRE 锚点不再落盘, 而存量
        // 记录里混着大量"只跑了几秒就被打断"的冤标记(实锤 16:08 那次 4.4s 留下两层
        // 封禁, 93 分钟后让同洞的 natural 从 176 掉到 24) —— 与新版语义不兼容, 一律
        // 作废从零重建。旧行仍留在 explored.log 里(append-only, 不删文件), 回滚只需
        // 去掉后缀。到期租约(_recentCaveAnchors)在内存里, 不受本后缀影响。
        return adris.altoclef.util.helpers.WorldIdentity.key(mod) + "#v2";
    }

    /**
     * (2026-09-17 世界边界丢弃) 换存档时清空矿洞相关 static:
     * 已探索洞区 / 自挖方块记录 / 探洞交接锚点。
     * 其中最危险的一点: 上个存档的"自挖隧道"位置在新存档里可能是实心
     * 天然岩石, 留着会把它们当"自己挖过的空气"而错判矿洞判据。
     */
    public static void onWorldBoundary() {
        _exploredCaveByKey.clear();
        // (2026-09-17 FIRE 标记降级) 租约是"本世界本维度"的, 换存档必须一起清。
        _recentCaveAnchors.clear();
        _selfDugByDim.clear();
        _lastFireAnchor = null;
    }

    private static java.nio.file.Path exploredCaveFile() {
        // (2026-09-10) 迁入 logs/altoclef/memory/, 旧根目录文件自动迁移。
        return adris.altoclef.Debug.memoryFile(EXPLORED_CAVE_FILE, "explored.log");
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
     * (2026-09-17 FIRE 标记降级) FIRE 时刻取一份【临时租约】: 锚点在 FIRE_LEASE_MS 内
     * 参与"已探索"排除(防链重建导致同洞反复吸引), 到期自动失效 —— 因为超时/被抢占
     * 恰恰说明本洞没探索完, 不该永久封禁。**不写盘**。
     *
     * 与 markExploredCaveRegion 的关系: 那个管"事实"(故事后保留), 这个管"意图"(租约)。
     * 形状判据同源(见 isInExploredCaveRegion)。
     */
    static void markRecentCaveAnchor(AltoClef mod, BlockPos anchor) {
        if (mod == null || mod.getWorld() == null || anchor == null) return;
        String key = exploredCaveKey(mod);
        java.util.Map<BlockPos, Long> leases = _recentCaveAnchors
                .computeIfAbsent(key, k -> new java.util.HashMap<>());
        long now = System.currentTimeMillis();
        leases.entrySet().removeIf(e -> now - e.getValue() > FIRE_LEASE_MS);
        leases.put(anchor.toImmutable(), now);
        Debug.logToFile("altoclef_diag.log",
                "[ExploredCave] fire lease taken center=" + anchor.toShortString()
                        + " ttl=" + (FIRE_LEASE_MS / 1000) + "s activeLeases=" + leases.size()
                        + " key=" + key);
    }

    /**
     * (2026-09-03 记录一个已探索洞穴区域) 探索结束(超时/完成)时以玩家位置为圆心,
     * 记录扁圆柱区域(水平 EXPLORED_CAVE_RADIUS / 垂直 ±EXPLORED_CAVE_Y_BAND)内的
     * 洞穴为"不再触发探索"。与已有中心水平距离不足 EXPLORED_CAVE_MIN_SEP 且 y 带
     * 重叠则视为重复(同一片洞区)丢弃, 防密集堆点。
     * (2026-09-11 同心丢弃改扁形匹配: 旧 3D 距离会把"同一水平位置但 y 带不相交"
     * 的两个独立区域误判为重复而丢弃。)
     * (2026-09-17 FIRE 标记降级) 本方法**不再由 FIRE 调用** —— FIRE 改走
     * markRecentCaveAnchor 取临时租约。此处只剩一个调用方: _inCave 退出分支
     * (超时/完成), 玩家位置是【真走过】的位置, 值得持久化。
     */
    private static void markExploredCaveRegion(AltoClef mod, BlockPos center) {
        if (mod == null || mod.getWorld() == null || center == null) return;
        ensureExploredCaveLoaded();
        String key = exploredCaveKey(mod);
        java.util.LinkedHashSet<BlockPos> set = _exploredCaveByKey.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>());
        // 与已有中心过近 → 同片洞区已覆盖, 丢弃(防每 90s 超时在同一点重复追加)。
        if (hasNearbyCenter(set, center, EXPLORED_CAVE_MIN_SEP)) return;
        set.add(center.toImmutable());
        appendExploredCaveLine(key, center);
        Debug.logToFile("altoclef_diag.log",
                "[ExploredCave] marked region center=" + center.toShortString()
                        + " hRadius=" + EXPLORED_CAVE_RADIUS + " yBand=" + EXPLORED_CAVE_Y_BAND
                        + " key=" + key
                        + " totalCenters=" + set.size());
    }

    /**
     * (2026-09-12 实际探索点回传, 用户"同一段矿洞放弃后又重复去探索") ExploreCaveTask
     * 把本次【实际到达过的目标点】批量交回, 逐点写入已探索洞穴区域记忆。
     *
     * 起因: 旧实现只在 (a) FIRE 时刻 anchor、(b) 探索结束玩家位置 各记【一个】中心,
     * 覆盖 = 半径 16 的扁圆柱。洞体跨度 24 格的场景 (09-12 场次) 下:
     *   - FIRE 锚点 129,41,-231 → 覆盖 z∈[-247,-215];
     *   - 洞实际延伸到 z=-255 (22:52:14 FIRE 目标 139,41,-255) → 远端 8 格永在标记外;
     *   - (b) 那次还被 MIN_SEP=24 去重吞掉 (结束位置 132,42,-232 距锚点仅 3 格) →
     *     等于只有 1 个中心。
     * 结果: 冷却过后, 玩家挖隧道走到 z=-247 (标记圆柱边界外) 重扫, 缺口里的洞空气
     * 重新满足 naturalAir≥24 → 同一洞二次 FIRE (日志两次 FIRE 相隔 77 秒)。
     *
     * 点集 + 既有扁圆柱判定天然贴合连通洞穴的形状: ExploreCaveTask 的到达点是沿洞
     * 单调向外的轨迹, 逐点登记后整条洞都被覆盖, 不再依赖"洞是否恰好落在某个圆柱里"。
     *
     * @param why 诊断标签(完成/打断), 只进日志不改行为。
     */
    static void markExploredCavePoints(AltoClef mod, java.util.List<BlockPos> points, String why) {
        if (mod == null || mod.getWorld() == null || points == null || points.isEmpty()) return;
        ensureExploredCaveLoaded();
        String key = exploredCaveKey(mod);
        java.util.LinkedHashSet<BlockPos> set = _exploredCaveByKey
                .computeIfAbsent(key, k -> new java.util.LinkedHashSet<>());
        int added = 0;
        for (BlockPos center : points) {
            if (center == null) continue;
            if (hasNearbyCenter(set, center, EXPLORED_CAVE_POINT_MIN_SEP)) continue;
            BlockPos immutable = center.toImmutable();
            set.add(immutable);
            appendExploredCaveLine(key, immutable);
            added++;
        }
        if (added > 0) {
            Debug.logToFile("altoclef_diag.log",
                    "[ExploredCave] point-set marked (" + why + "): +" + added
                            + " centers, totalCenters=" + set.size()
                            + " hRadius=" + EXPLORED_CAVE_RADIUS + " yBand=" + EXPLORED_CAVE_Y_BAND
                            + " key=" + key);
        }
    }

    /**
     * (2026-09-12 抽取自 markExploredCaveRegion) 扁形同片判定: |Δy| ≤ Y_BAND 且水平
     * 距离 < minSep 视为同一片洞区。minSep 按调用场景区分 —— 单点标记用 24(一次探索
     * 一个中心), 点集回传用 8(保留轨迹形状)。
     */
    private static boolean hasNearbyCenter(java.util.Set<BlockPos> set, BlockPos center, int minSep) {
        if (set == null || set.isEmpty()) return false;
        for (BlockPos existing : set) {
            if (Math.abs(center.getY() - existing.getY()) > EXPLORED_CAVE_Y_BAND) continue;
            int dx = center.getX() - existing.getX();
            int dz = center.getZ() - existing.getZ();
            if (dx * dx + dz * dz < minSep * minSep) return true;
        }
        return false;
    }

    /**
     * (2026-09-03 判定 pos 是否落在任一已探索洞穴区域内) 供探测/目标选择排除用。
     * (2026-09-11 3D 球改扁圆柱: 水平距离 ≤ EXPLORED_CAVE_RADIUS 且 |Δy| ≤
     * EXPLORED_CAVE_Y_BAND 才算命中, 上层洞标记不再挡深层/浅层新洞。)
     * (2026-09-17 FIRE 标记降级) 判据 = 持久记忆 ∪ 未过期的 FIRE 租约。两者形状
     * 完全同源, 唯一区别是租约会到期 —— 不引入第二套半径口径。
     */
    private static boolean isInExploredCaveRegion(AltoClef mod, BlockPos pos) {
        if (mod == null || mod.getWorld() == null || pos == null) return false;
        String key = exploredCaveKey(mod);
        int rSq = EXPLORED_CAVE_RADIUS * EXPLORED_CAVE_RADIUS;
        java.util.Map<BlockPos, Long> leases = _recentCaveAnchors.get(key);
        if (leases != null && !leases.isEmpty()) {
            long now = System.currentTimeMillis();
            for (java.util.Map.Entry<BlockPos, Long> e : leases.entrySet()) {
                if (now - e.getValue() > FIRE_LEASE_MS) continue;
                BlockPos center = e.getKey();
                if (Math.abs(pos.getY() - center.getY()) > EXPLORED_CAVE_Y_BAND) continue;
                int dx = pos.getX() - center.getX();
                int dz = pos.getZ() - center.getZ();
                if (dx * dx + dz * dz <= rSq) return true;
            }
        }
        ensureExploredCaveLoaded();
        java.util.Set<BlockPos> set = _exploredCaveByKey.get(key);
        if (set == null || set.isEmpty()) return false;
        for (BlockPos center : set) {
            if (Math.abs(pos.getY() - center.getY()) > EXPLORED_CAVE_Y_BAND) continue;
            int dx = pos.getX() - center.getX();
            int dz = pos.getZ() - center.getZ();
            if (dx * dx + dz * dz <= rSq) return true;
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
    static BlockPos pickFarthestNaturalCaveAir(AltoClef mod, BlockPos center, BlockPos fromRef, Set<BlockPos> exclude, int minY, int maxY) {
        return pickFarthestNaturalCaveAir(mod, center, fromRef, exclude, minY, maxY, false);
    }

    /**
     * (2026-09-07 FIRE 时标记配套) ignoreExploredRegions=true 供 ExploreCaveTask 使用:
     * 探索目标选择豁免区域记忆 — FIRE 时已把本洞标记为已探索, 若选点仍排除该区域,
     * 本次探索自己的目标会被剔空而立即结束。探索任务天然只由 FIRE 创建, 恒豁免。
     */
    static BlockPos pickFarthestNaturalCaveAir(AltoClef mod, BlockPos center, BlockPos fromRef, Set<BlockPos> exclude, int minY, int maxY, boolean ignoreExploredRegions) {
        // (2026-09-16 下界差异化) 视野 16→32、单步导航 10→24、高度硬顶 45→120:
        // 下界巨腔里 10 格碎步=原地挪动(0916 实锤 navDist 恒≈9.9), 高度硬顶 45
        // 把矿带上半截的巨腔全拒掉。主世界参数不变。
        boolean netherDim = WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER;
        int scanRadius = netherDim ? NETHER_EXPLORE_SCAN_RADIUS : EXPLORE_SCAN_RADIUS;
        int navMaxDist = netherDim ? NETHER_NAV_STEP_MAX_DIST : NAV_STEP_MAX_DIST;
        int hardCapY = netherDim ? NETHER_NATURAL_AIR_MAX_Y : CAVE_NATURAL_AIR_MAX_Y;
        // 探索目标选择用更远的 EXPLORE_SCAN_RADIUS, 探索段更长(用户"得调远一点")。
        Set<BlockPos> reachable = collectConnectedAir(mod, center, scanRadius);
        // (2026-09-02 目标深度上界) 导航目标也只选目标深度附近的天然空气, 防止
        // 钻石任务在洞内向外推时把目标选到上方浅层, 离开钻石层。
        int naturalMaxY = Math.min(hardCapY, maxY);
        int naturalMinY = minY;
        BlockPos best = null;
        double bestDistSq = Double.NEGATIVE_INFINITY;
        for (BlockPos p : reachable) {
            if (p.getY() > naturalMaxY) continue;
            // (2026-09-13 双边窗口) 导航目标也不落到窗口下方 — 与 findNearbyCave
            // 的认定窗口同源, 否则会出现"认定时会拒、选点时会选"的判据撕裂。
            if (p.getY() < naturalMinY) continue;
            // (2026-09-13 导航步长上限) 视野里看得见、但不作为本次导航目标 ——
            // 超出抢占窗口能走完的距离(见 NAV_STEP_MAX_DIST 注释)。
            // (2026-09-16 下界差异化) 下界巨腔无障碍、baritone 直线可达, 步长放大
            // 到 24(主世界仍 10, 见 NETHER_NAV_STEP_MAX_DIST 注释)。
            if (!p.isWithinDistance(center, navMaxDist)) continue;
            // (2026-09-13 垂直可达性) 3D 距离不约束垂直分量 → 目标可能落在正上方
            // 9 格(纯空腔里无落脚点, 上不去 → JumpStuck 反复 forceCancel)。见常量注释。
            int dy = p.getY() - center.getY();
            if (dy > NAV_STEP_MAX_DY_UP || dy < -NAV_STEP_MAX_DY_DOWN) continue;
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
        // (2026-09-13 视野可见) 探索选点此前【没有任何日志】→ "候选集被容量上限砍到
        // 多少格"完全不可观测, 09-13 的误判(以为视野真有 16 格)就是这样来的。
        // 这里记录候选集规模、是否触顶、以及候选集【实际覆盖的水平半径】coverR
        // (对照名义 EXPLORE_SCAN_RADIUS): coverR 长期小于 EXPLORE_SCAN_RADIUS =
        // 视野仍被截断, 应继续上调 bfsCapacityFor。
        // 只对 ignoreExploredRegions=true(ExploreCaveTask) 打点, 避免 findNearbyCave
        // 的每步扫描把日志刷满。
        if (ignoreExploredRegions) {
            int cap = bfsCapacityFor(EXPLORE_SCAN_RADIUS);
            int coverR = 0;
            for (BlockPos p : reachable) {
                int d = Math.max(Math.abs(p.getX() - center.getX()), Math.abs(p.getZ() - center.getZ()));
                if (d > coverR) coverR = d;
            }
            Debug.logToFile("altoclef_diag.log",
                    "[CavePick] reachable=" + reachable.size()
                            + (reachable.size() >= cap ? "(CAP HIT)" : "")
                            + " coverR=" + coverR + "/" + EXPLORE_SCAN_RADIUS
                            + " naturalMaxY=" + naturalMaxY
                            + " exclude=" + (exclude == null ? 0 : exclude.size())
                            + " best=" + (best == null ? "null(→done)" : best.toShortString())
                            + " navDist=" + (best == null ? "-"
                                    : String.format("%.1f", Math.sqrt(best.getSquaredDistance(center))))
                            + " dy=" + (best == null ? "-" : (best.getY() - center.getY()))
                            + " c=" + center.toShortString());
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
            int cap = bfsCapacityFor(radius);
            while (!queue.isEmpty() && visited.size() < cap) {
                BlockPos cur = queue.poll();
                if (cur == null || !visited.add(cur)) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos n = cur.add(dx, dy, dz);
                            if (Math.abs(n.getX() - start.getX()) > radius
                                    || Math.abs(n.getZ() - start.getZ()) > radius) continue;
                            // (2026-09-16 下界视野 32) 半径 32 跨 2 个区块, 必须防未加载
                            // 区块(未加载按空气算会污染连通集); 半径 8 时恒已加载, 无副作用。
                            if (!mod.getChunkTracker().isChunkLoaded(n)) continue;
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

        /**
         * (2026-09-13 容量上限参数化) BFS 收集的容量上限。
         *
         * 旧实现写死 512: 在空气密度高的洞穴里【半径 8 就能收满】(09-13 实测
         * radius 8 → 464~512 块, 日志 reachable=512(CAP HIT) ×4 + 400-499 ×5),
         * 于是 radius 16 的扫描在约 8.3 格处被 512 掐死 —— EXPLORE_SCAN_RADIUS=16
         * 从未生效, 探索视野退化成 8 格。后果: 目标点间隔只有 4~6 格、在近场往复
         * 横跳, 约 20 个到达点(每个 ±3 膨胀 =343 格)就把视野内空气全部标为已访问
         * → pickNext 返回 null → 误判"洞已探索完"(09-13 实锤: done 时玩家
         * 132,42,-232, 而洞延伸到 z=-258; 名义 16 格的视野本该覆盖到 z=-248)。
         *
         * 改为随水平半径增长: (2r+1)^2 是横截面格子数, ×4 给垂直方向留约 4 层。
         * radius=16 → 33*33*4 = 4356, 足以扫完整个 33x33 水平面。
         *
         * 边界: radius <= CAVE_SCAN_RADIUS(8) 维持 512 不变 —— findNearbyCave 的
         * 判据(/FIRE 频率)依赖它, 放大上限会改变行为, 属于本次改动之外的 scope。
         */
        private static int bfsCapacityFor(int radius) {
            if (radius <= CAVE_SCAN_RADIUS) return 512;
            int span = 2 * radius + 1;
            return Math.max(512, span * span * 4);
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
        // 需求: 地表/浅层即使头顶有方块(挖开的楼梯)也不算"发现矿洞"。
        // (2026-09-16 下界差异化) 主世界用"深度闸门 y≤45"承担这层防护(主世界
        // 地表 y≥60, 深度即是否 underground)。下界没有这个概念 —— 矿带 [32,96]
        // 大半在 45 之上, 深度闸门只会偶发放行(实锤 REJECT depth y=49>45),
        // 且放行的恰是空旷地。下界改用"头顶 48 格内必须有岩顶": 开阔荒地/
        // 灵魂沙谷地表无顶 → 拒; 巨腔有腔顶 → 放行。
        if (_nether) {
            if (!hasOverheadCover(mod, center, NETHER_COVER_SCAN_UP)) {
                logCaveScanDiag(mod, center, "REJECT nether open-ground (头顶" + NETHER_COVER_SCAN_UP + "格内无岩顶)");
                return Optional.empty();
            }
        } else if (center.getY() > CAVE_EXPLORE_MIN_DEPTH_Y) {
            logCaveScanDiag(mod, center, "REJECT depth y=" + center.getY() + ">" + CAVE_EXPLORE_MIN_DEPTH_Y);
            return Optional.empty();
        }
        // (2026-09-01 露天不检测洞, 二次保险) 玩家头顶无实心覆盖(露天) → 关闭检测。
        // 深度闸门放行后(已在地下)本判据几乎恒真, 但保留防"挖穿到地表天窗"等
        // 罕见场景。树冠不算顶(树下仍算露天)。
        // (2026-09-07 高 cavern 修复, 用户"洞边缘不触发") 24 格仰扫在大奶酪洞里
        // 会全程空气 → 深地大洞被误判"露天"而整局关闸(10:24 复现实锤: REJECT
        // no-cover c=251,-43,201)。主世界 y<0 物理上不可能露天(地表 y≥60) →
        // 跳过本闸门, 地表误判防护由深度闸门(y≤45)承担。
        // (2026-09-16) 下界分支已上移为专属空旷地判据(48 格仰扫), 不再走这里。
        boolean openSkyImpossible = center.getY() < 0;
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
        // (2026-09-13 双边窗口) 只认与目标矿带高度相当的天然空气: 上限滤掉向上
        // 蔓延的浅层洞(防"下挖到合法深度却被上方矿洞导航走"), 下限滤掉更深处的
        // 洞(防"被下方溶洞拖离本层")。窗口推导见 caveWindowMaxY/MinY。
        int naturalMaxY = caveWindowMaxY();
        int naturalMinY = caveWindowMinY();
        // (2026-09-06 闸门级诊断) 逐原因计数: 天然空气不足时能直接看出是被
        // 高度上界/下界/自挖记忆/已探索区域哪一方吃掉的, 以及 BFS 512 上限是否饱和。
        int aboveMaxY = 0, belowMinY = 0, selfDugCount = 0, exploredCount = 0;
        for (BlockPos p : reachableAir) {
            if (p.getY() > naturalMaxY) { aboveMaxY++; continue; }
            if (p.getY() < naturalMinY) { belowMinY++; continue; }
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
                    + " aboveMaxY=" + aboveMaxY + " belowMinY=" + belowMinY + " selfDug=" + selfDugCount + " explored=" + exploredCount
                    + " targetY=" + _targetY + " naturalMaxY=" + naturalMaxY + " naturalMinY=" + naturalMinY);
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
        // (2026-09-11 FIRE 行补计数, 用户"自己挖的通道却触发了探索") REJECT 行有
        // aboveMaxY/selfDug/explored 三计数而 FIRE 行没有 → 误触发时无法看出 488
        // 个"natural"里自挖/高度/区域记忆各吃掉多少, 定位分类漏洞缺证据。补齐。
        logCaveScanDiag(mod, center, (best != null ? "FIRE best=" + best.toShortString() : "REJECT no-neighbor x" + noNeighbor)
                + " natural=" + naturalAir.size() + " reachable=" + reachableAir.size() + (reachableAir.size() >= 512 ? "(CAP HIT)" : "")
                + " aboveMaxY=" + aboveMaxY + " belowMinY=" + belowMinY + " selfDug=" + selfDugCount + " explored=" + exploredCount
                + " targetY=" + _targetY + " naturalMaxY=" + naturalMaxY + " naturalMinY=" + naturalMinY + " c=" + center.toShortString());
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
                + " c=" + center.toShortString() + " step=" + _step + " tun=" + _tunnelMode
                + (_nether ? " nether=1" : ""));
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
        return hasOverheadCover(mod, pos, CAVE_COVER_SCAN_UP);
    }

    /**
     * (2026-09-16 仰扫范围参数化, 服务下界 48 格空旷地判据) 判定一块空气上方
     * scanUp 格内是否有"岩顶"覆盖。下界巨腔腔顶普遍比主世界洞穴顶高, 24 格不够;
     * 开阔荒地/灵魂沙谷地表到基岩顶盖(127)通常远超 48 → 正确判"露天"。
     * 下界菌类(绯红/诡异森林): 菌柄(PillarBlock)本就不算顶; 菌伞(wart block)
     * 是普通实心方块, 必须显式排除, 否则森林地表头顶有菌伞会被误判"有岩顶"。
     */
    private boolean hasOverheadCover(AltoClef mod, BlockPos pos, int scanUp) {
        BlockPos up = pos;
        for (int i = 0; i < scanUp; i++) {
            up = up.up();
            if (!mod.getChunkTracker().isChunkLoaded(up)) return true;
            net.minecraft.block.Block b = mod.getWorld().getBlockState(up).getBlock();
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR) continue;
            if (b instanceof net.minecraft.block.LeavesBlock
                    || b instanceof net.minecraft.block.PillarBlock
                    || b instanceof net.minecraft.block.AzaleaBlock) continue; // 树冠/树干不算岩顶
            if (b == Blocks.NETHER_WART_BLOCK || b == Blocks.WARPED_WART_BLOCK) continue; // 下界菌伞不算岩顶
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
        // (2026-09-14 岩浆弃道) _lavaGiveUp: 本轮换向 4 次都撞上岩浆 (见
        // LAVA_ABANDON_LIMIT), 这片区域挖不下去了 —— 收工, 由父任务重新选址重派,
        // 比在岩浆里反复打转强。短路在前, 避免再算一次 reachedBottom。
        boolean result = _lavaGiveUp || (!_inCave && reachedBottom(mod));
        // (2026-09-10 探索断裂诊断) 翻转沿打点, 重点抓"空中宣告到底"瞬间。
        if (result != _finishedDiagLastState) {
            _finishedDiagLastState = result;
            if (result) {
                var p = mod.getPlayer();
                boolean grounded = isPlayerGrounded(mod);
                Debug.logToFile("altoclef_diag.log",
                        "[MineDown-WaitDiag]\tisFinished -> TRUE at "
                                + (p != null ? p.getBlockPos().toShortString() : "?")
                                + " grounded=" + grounded + " inCave=" + _inCave
                                + " targetY=" + _targetY
                                + (grounded ? "" : "  << AIRBORNE: parent may recreate now & strand pending cave scan"));
            }
        }
        return result;
    }

    /** (2026-09-10 诊断) 与 ITaskRequiresGrounded 同口径的落地判定。 */
    public static boolean isPlayerGrounded(AltoClef mod) {
        var p = mod.getPlayer();
        return p != null && (p.isOnGround() || p.isSwimming() || p.isTouchingWater() || p.isClimbing());
    }

    /**
     * (2026-09-17 头顶净空步上) 头顶净空候选块是否值得派 DestroyBlockTask:
     * 可挖实体方块 → true; 液体与不可挖(基岩等) → false, 走普通 baritone 爬升。
     */
    private boolean isClearableHeadroom(AltoClef mod, BlockPos pos) {
        var state = mod.getWorld().getBlockState(pos);
        if (state.isAir()) return true;
        if (state.getMaterial().isLiquid()) return false;
        return state.getHardness(mod.getWorld(), pos) >= 0;
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
        return _baritoneAscent ? "Climbing back up (via baritone)"
                : _ascendMode ? "Climbing back up (staircase up)"
                : _tunnelMode ? "Digging a tunnel to find ores" : "Digging down to find ores";
    }
}
