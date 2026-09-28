package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.baritone.GoalAnd;
import adris.altoclef.util.baritone.GoalBlockSide;
import adris.altoclef.util.baritone.GoalOr;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.AscentHandoffHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.PillarJumpHelper;
import adris.altoclef.util.helpers.RecoveryTuning;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Left or Right click on a block on a particular (or any) side of the block.
 */
public class InteractWithBlockTask extends Task {

    private final ItemTarget _toUse;
    private final Direction _direction;
    private final BlockPos _target;
    private final boolean _walkInto;
    private final Vec3i _interactOffset;
    private final Input _interactInput;
    private final boolean _shiftClick;
    private final TimerGame _clickTimer = new TimerGame(5);
    // 右键卡死检测：可达且持续 WAIT_FOR_CLICK 但目标界面没打开（典型
    // 原因：床/箱子上方有实体方块，MC 拒绝使用/打开）。原代码的 5 秒
    // 超时 (_clickTimer) 只挂在永远不返回的 CLICK_ATTEMPTED 分支上，
    // WAIT_FOR_CLICK 每 tick reset 计时器 → 永久卡在"等待右键"。
    // 现在 2 秒无进展就检查目标上方：有可破坏方块 → 撸掉再试。
    private final TimerGame _clickStuckTimer = new TimerGame(2);
    private boolean _clickStuckTiming = false;
    private final MovementProgressChecker _moveChecker = new MovementProgressChecker(4, 0.1, 4, 0.01);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5);
    private final int reachDistance = 0;

    // (2026-09-11 熔炉误判事故) 失败时是否允许"拉黑目标 + wander 挪位"。
    // 容器交互(开炉/开台)场景必须传 false: bot 因 NAV-STUCK 冻结时 moveChecker
    // 立刻判负, 旧逻辑会把 1 格外的熔炉 requestBlockUnreachable 拉黑 → 而
    // WorldHelper.canReach 实际是"黑名单取反"(非几何距离) → 父任务三路容器
    // 查找全被过滤 → 误判"无可用容器"→ 重做熔炉 (22:51 日志实锤, 炉内 6 生羊
    // 肉成为孤儿)。false 时: 站住原地持续重试点击, 15s 仍无进展才走原放弃路径。
    private final boolean _wanderOnFailure;
    // 上述 false 模式下的总兜底: 自上次真实进展起 15s 仍失败, 回落原路径。
    private final TimerGame _giveUpTimer = new TimerGame(15);
    // (2026-09-15 统一超时标签) 15s 兜底放弃的边沿标记, 防多条放弃路径重复刷 [TIMEOUT]。
    private boolean _giveUpLogged = false;

    // ==================== (2026-09-27 兜底自锁修复) ====================
    // 现场 20260927-222854: 熔炉 (185,73,39) 被岩浆隔断, bot 冻在 (185.48,72.00,35.46)
    // 站死 102.7s —— hSpeed=0.000, inputs 全 false, baritone calc 永远 inProgress,
    // snapshot 里 blacklisted=false 贯穿全场, 而 "Failed, blacklisting and wandering."
    // 一次都没打。
    //
    // 根因是**两个计时器互相清零**: `_moveChecker` 判负那一拍会走本任务自己的
    // `_moveChecker.reset()`(继续重试), 这一重置把 DistanceProgressChecker 的 4s
    // 窗口从头开始 ⇒ 随后约 4s 里 `moveOk` 每拍都判正 ⇒ CANT_REACH 分支末尾那句
    // `if (moveOk) _giveUpTimer.reset();` 把 15s 兜底每拍清零 ⇒ `_giveUpTimer.elapsed()`
    // 永不成立 ⇒ `requestBlockUnreachable` 永不执行。日志佐证: [TIMEOUT]
    // LinearProgressChecker timeout=4.0s 每 4s 准点刷(说明距离表确实在判负), 但放弃
    // 分支一次没进。
    //
    // 修: 15s 兜底改挂**墙钟锚点 + 真实位移**(口径同 InteractWithBlockTask.tickDetour —
    // "同概念只留一个判据源"), 与 moveChecker 的瞬时闪断彻底解耦。
    private Vec3d _noProgressAnchor = null;

    /** 记一次"有进展": 重置 15s 无进展窗口 (真实位移 / 派发了实际干活的子任务)。 */
    private void noteProgress(AltoClef mod) {
        _noProgressAnchor = mod.getPlayer().getPos();
        _giveUpTimer.reset();
    }

    /**
     * (2026-09-17 统一爬升协调器) "目标在头顶、够不着"的垂直爬升全权交给它:
     * 1.5s 无进展 → 重挂 baritone 导航(零成本, 能挖的挡块它自己会处理);
     * 再 1.5s → 补一次垫料; 再不行 → 判不可达交本任务拉黑。
     *
     * 现场 20260917-143636 实锤缺的正是这一层: bot 在自挖竖井底 y=60, 要上到
     * y=66 的工作台右键, `[NAV-STUCK] netXZ=0.00 jump=true pathing=true` 且全场
     * 零 Place(没垫料, baritone 的 MovementPillar 进不了 A*)—— 原地跳 33.2s 直到
     * 用户手动停。当时补料逻辑只长在 `DestroyBlockTask` 里, 且本任务自己的 15s
     * 兜底被"4s 瞬时判负一 tick、其余 tick 全判正"淹没(见 01-task-framework #14)。
     */
    private final AscentHandoffHelper _ascent =
            new AscentHandoffHelper("interact", PillarJumpHelper.withWoodNoLogs());

    private ClickResponse _cachedClickStatus = ClickResponse.CANT_REACH;

    // ==================== (2026-09-17 脱险: 判不可达后必须真的换活) ====================
    // 现场 20260917-150146 第二段: 协调器 1.5s 重挂 + 1.5s 判不可达 → `requestBlockUnreachable`
    // + `return _wanderTask` → 但本实例是**容器交互**(wanderOnFailure=false), 下面 L274 的
    // wander 续跑守卫不成立 → 游荡子任务只被返回**一个 tick** 就被本任务的下一 tick 丢掉
    // (tasks.log: `Wander for 5.0 blocks` 300ms STOP, 紧接 `<Wander> -> null`) → 换活等于没做;
    // 50ms 后父级又重建同目标 → 3.15s 一轮 × 5 轮 = 15s 不脱险。
    //
    // 修: ① 拉黑后**抱持**休整(锁住本任务最多 ESCAPE_SECONDS, 期间只返回 wander, 不重评
    // CANT_REACH 把子任务丢掉); ② 游荡实例必须 `resetForReuse` —— 复用实例 `_origin` 是上一次
    // 使用的位置, 不重置的话 isFinished 立刻成立, 抱持也是空转; ③ 同一目标爬升失败按
    // **static** 计数封顶(实例级计数会被父级重建绕过 —— 本仓 _clearAboveAttempts 同款教训)。
    private static final Map<BlockPos, Integer> _ascentAttempts = new HashMap<>();
    private static final int ASCENT_MAX_ATTEMPTS = 2;
    private static final double ESCAPE_SECONDS = 5;
    private boolean _escaping = false;
    private final TimerGame _escapeTimer = new TimerGame(ESCAPE_SECONDS);

    // (2026-09-10 清上方方块止损+持久化) 15:13 场次实锤: 清上方方块的
    // DestroyBlockTask 每次只活 1 tick — 下一 tick reach 状态翻转后 onTick
    // 走了别的分支 return null, 子任务被抛弃, 方块永远挖不掉, "点击 2s 超时
    // → 清上方"每 6s 一轮死循环 70s+。两层修复:
    // ① 实例层持久化: 清理子任务在飞期间持续返回它, 不被分支切换抛弃, 10s
    //   超时兜底 (防保护块上无限挖)。
    // ② 触发层 static 冷却 (老规矩: 实例层计数会被父级每轮重建绕过): 同一
    //   目标上方方块清理尝试超 3 次后不再清, 直接走 requestBlockUnreachable
    //   + wander 放弃路径。附带记上方方块身份 (是否门框/保护块, 定案用)。
    private static final Map<BlockPos, Integer> _clearAboveAttempts = new HashMap<>();
    private static final Set<BlockPos> _clearAboveCapLogged = new HashSet<>();
    private static final int CLEAR_ABOVE_MAX_ATTEMPTS = 3;
    private static final long CLEAR_ABOVE_PERSIST_MS = 10_000;
    private DestroyBlockTask _clearAboveTask = null;
    private BlockPos _clearAbovePos = null;
    private long _clearAboveSinceMs = 0;

    // ==================== (2026-09-23 水平绕行: 熔炉贴脸封门死锁) ====================
    // 现场 20260923-085300: bot(220,65,80) → 熔炉(220,65,81) → 工作台(220,65,82),
    // 目标站位环全实心。baritone A* 每 6s 超时重启(57万节点), 而 moveChecker 的
    // "3.9s true + 1tick false" 闪断把 _giveUpTimer 每 tick reset → 全部兜底永不触发,
    // 站桩 146s。修法同构 _clearAbove(垂直清方块): 同层近场 + 持续 CANT_REACH 3s
    // (期间真实移动 >0.2 格即重新计时) + baritone 无路径 → BFS 找"挖块最少"的
    // 侧面走廊, 逐块 DestroyBlockTask 挖通(抱持, 单块 10s 超时)。只挖近场、非容器/
    // 功能方块、canBreak、不邻岩浆、头/脚空间合格的方块。同目标 2 轮封顶 →
    // startEscape 拉黑脱险。绝不挖 _target 本体。
    // (2026-09-28 L1) 参数统一收口到 RecoveryTuning —— 本地别名保留, 调用点零改动;
    // 与实体侧 AbstractDoToEntityTask 的"被挡 → 清障"时长现在同一处可见 (调参只改一处)。
    // 两者为何不同 (2s vs 3s), 见 RecoveryTuning 类注释。
    private static final double DETOUR_TRIGGER_SECONDS = RecoveryTuning.BLOCK_DETOUR_TRIGGER_SECONDS;
    private static final int DETOUR_MAX_BLOCKS = RecoveryTuning.BLOCK_DETOUR_MAX_BLOCKS;
    private static final int DETOUR_MAX_ROUNDS = RecoveryTuning.BLOCK_DETOUR_MAX_ROUNDS;
    private static final long DETOUR_BLOCK_TIMEOUT_MS = RecoveryTuning.BLOCK_DETOUR_BLOCK_TIMEOUT_MS;
    private static final Map<BlockPos, Integer> _detourRounds = new HashMap<>();
    // (2026-09-28 (i) 先绕后挖) 已放宽过"交互站位半径"的目标。static: 跨实例存活,
    // 与 _detourRounds 同口径 (上层重建任务实例不重置"这个目标试过几步")。
    private static final Set<BlockPos> _widenedStand = new HashSet<>();
    private final TimerGame _detourTimer = new TimerGame(DETOUR_TRIGGER_SECONDS);
    private boolean _detourTiming = false;
    private Vec3d _detourAnchor = null;
    private List<BlockPos> _detourBlocks = null;
    private int _detourIndex = 0;
    private DestroyBlockTask _detourTask = null;
    private long _detourBlockSinceMs = 0;
    // 绕行绝不开挖的容器/功能方块(显式清单; Shulker/Bed 走 instanceof)。
    private static final Set<net.minecraft.block.Block> NEVER_DETOUR_BREAK = Set.of(
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.CRAFTING_TABLE, Blocks.CHEST, Blocks.TRAPPED_CHEST,
            Blocks.ENDER_CHEST, Blocks.BARREL, Blocks.HOPPER, Blocks.DROPPER,
            Blocks.DISPENSER, Blocks.BREWING_STAND, Blocks.BEACON, Blocks.CONDUIT,
            Blocks.BELL, Blocks.NOTE_BLOCK, Blocks.JUKEBOX, Blocks.ENCHANTING_TABLE,
            Blocks.CAKE, Blocks.DRAGON_EGG);

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick) {
        this(toUse, direction, target, interactInput, walkInto, interactOffset, shiftClick, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick, boolean wanderOnFailure) {
        _toUse = toUse;
        _direction = direction;
        _target = target;
        _interactInput = interactInput;
        _walkInto = walkInto;
        _interactOffset = interactOffset;
        _shiftClick = shiftClick;
        _wanderOnFailure = wanderOnFailure;
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, boolean shiftClick) {
        this(toUse, direction, target, interactInput, walkInto, Vec3i.ZERO, shiftClick);
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, boolean walkInto) {
        this(toUse, direction, target, Input.CLICK_RIGHT, walkInto, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target, boolean walkInto, Vec3i interactOffset) {
        // null means any side is OK
        this(toUse, null, target, Input.CLICK_RIGHT, walkInto, interactOffset, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target, boolean walkInto) {
        this(toUse, target, walkInto, Vec3i.ZERO);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target) {
        this(toUse, target, false);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick) {
        this(new ItemTarget(toUse, 1), direction, target, interactInput, walkInto, interactOffset, shiftClick);
    }
    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, boolean shiftClick) {
        this(new ItemTarget(toUse, 1), direction, target, interactInput, walkInto, shiftClick);
    }
    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, boolean walkInto) {
        this(new ItemTarget(toUse, 1), direction, target, walkInto);
    }
    public InteractWithBlockTask(Item toUse, BlockPos target, boolean walkInto, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), target, walkInto, interactOffset);
    }
    public InteractWithBlockTask(Item toUse, BlockPos target, boolean walkInto) {
        this(new ItemTarget(toUse, 1), target, walkInto);
    }
    public InteractWithBlockTask(Item toUse, BlockPos target) {
        this(new ItemTarget(toUse, 1), target);
    }


    public InteractWithBlockTask(BlockPos target) {
        this(ItemTarget.EMPTY, null, target, Input.CLICK_RIGHT, false, false);
    }

    /** 容器交互专用: 失败不拉黑不游荡, 15s 兜底 (2026-09-11 熔炉误判事故)。 */
    public InteractWithBlockTask(BlockPos target, boolean wanderOnFailure) {
        this(ItemTarget.EMPTY, null, target, Input.CLICK_RIGHT, false, Vec3i.ZERO, false, wanderOnFailure);
    }

    private static Goal createGoalForInteract(BlockPos target, int reachDistance, Direction interactSide, Vec3i interactOffset, boolean walkInto, int standY) {

        boolean sideMatters = interactSide != null;
        if (sideMatters) {
            Vec3i offs = interactSide.getVector();
            if (offs.getY() == -1) {
                // If we're below, place ourselves two blocks below.
                offs = offs.down();
            }
            target = target.add(offs);
        }

        if (walkInto) {
            return new GoalTwoBlocks(target);
        } else {
            if (sideMatters) {
                // (2026-09-01 修复放床 dir=UP 错位死循环: UP 面 GoalBlockSide(buffer=1)
                // 要求 y站 - y目 > 1(站在目标上方 2 格以上); 但贴地面放床 click TOP
                // 时 bot 就站在目标同层+1 的地面 → 永远判不"正确侧" → Baritone
                // 规划去地下两格 → 4 tick 走不动被 Wander 踢走, 又被 PlaceBed 外层
                // GetToBlockTask 拉回 → Interact↔Wander↔GetToBlock 乒乓(14:22 14 秒
                // 2 整轮, 床根本没放进世界)。UP/DOWN 水平点击放宽为近旁 1 格。
                if (interactSide == Direction.UP || interactSide == Direction.DOWN) {
                    BlockPos anchor = (interactSide == Direction.UP) ? target.up() : target.down();
                    return new GoalNear(anchor.add(interactOffset), Math.max(1, reachDistance));
                }
                // Make sure we're on the right side of the block.
                Goal sideGoal = new GoalBlockSide(target, interactSide, 1);
                return new GoalAnd(sideGoal, new GoalNear(target.add(interactOffset), reachDistance));
            } else {
                // (2026-09-27 y 差 1 缝: 目标恰比 bot 高 1 格) 交互"站位面"就是 bot
                // 自己这一层 —— 眼在 standY+1.62, 目标块占 targetY..targetY+1, 站到
                // 目标的水平邻格时眼到目标侧面仅 ~0.8 格, 与站在目标同层等效。
                //
                // 但 GoalNear(target,1) 的 in-goal 集合是 distSq≤1 的 6 个面邻格:
                // 在 standY = targetY-1 这一层里, 只有"目标正下方那格"命中 —— 而它
                // 正是目标的**支撑方块**(恒实心), 站不进去 ⇒ **这一层天然无解**,
                // baritone 只能想办法把 bot 抬进目标层。目标层被岩浆/整面地形隔断时
                // ⇒ 永久 CANT_REACH (现场 20260927-222854: 熔炉 (185,73,39) 比 bot
                // 高 1 格、中间隔岩浆, 站死 102.7s; 而用户在同位置**手动右键能开炉**)。
                // 也与两条兜底的缝同源: 爬升协调器只认 `feetY < targetY-1`、水平绕行
                // 只认 `feetY == targetY`, Δy==1 落在中间。
                //
                // 修: 这种几何下再补一个"bot 自己这一层"的目标, 与原来那个**取任一**
                // (GoalOr) ⇒ in-goal = 目标层的 4 个水平邻格 **或** bot 这一层的 4 个
                // 水平邻格, 后者正是人类"走到炉子旁边"的位置。
                // 为什么不能只换成下移一格的目标: 台阶其实走得上去时那样会舍近求远
                // (甚至绕去挖走廊); 单一 GoalNear 必然丢掉两条路中的一条, 见 GoalOr。
                // standY 与 targetY 相等或差 ≥2 时**逐字保持旧行为**。
                // (2026-09-01 用户"熔炉距离太远右键失败, 用人类交互逻辑") 旧
                // GoalTwoBlocks(target.up()) 让 bot 停在目标上方/过远位置, 开容器
                // (熔炉/箱子/工作台) 时右键够不着 → CANT_REACH↔WAIT_FOR_CLICK 反复
                // 打转。改为 GoalNear(target, 1): 走到容器旁 1 格内再右键, 人类开
                // 箱就是走到跟前再点。
                int reach = Math.max(1, reachDistance);
                if (target.getY() == standY + 1) {
                    return new GoalOr(
                            new GoalNear(target.add(interactOffset), reach),
                            new GoalNear(target.down().add(interactOffset), reach));
                }
                return new GoalNear(target.add(interactOffset), reach);
            }
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        _moveChecker.reset();
        _wanderTask.resetWander();
        // (2026-09-04 首tick盲点修复) BaseTimer._prevTime 初始 0 → 新任务实例首 tick
        // _forceClickTimer.elapsed()=true → 准星还没转到目标就盲点右键。日志实锤
        // (2026-09-04 21:44): 6 次 FORCE-CLICK 全部 START 同秒, crosshair 与 target
        // 差 1-3 格 (舀岩浆点在自己搭的路/旁边地形上)。onStart 重置后, "1.5s 兜底"
        // 才是真正"等了 1.5s 仍瞄不准"才触发。
        _forceClickTimer.reset();
        // (2026-09-11) 无游荡模式的 15s 总兜底计时。
        _giveUpTimer.reset();
        _giveUpLogged = false;
        // (2026-09-27) 无进展墙钟锚点不跨次进入: 新一次交互应当重新起表。
        _noProgressAnchor = null;
        // (2026-09-17) 协调器状态不跨实例存活: 被抢占恢复后按新现场重新判定高度差。
        _ascent.stop(mod);
        // (2026-09-17) 脱险抱持不跨次进入 —— 新一次交互应当重新尝试 (static 计数仍生效)。
        _escaping = false;
        // (2026-09-23) 绕行计时/路径不跨实例; static 轮次仍生效。
        // (2026-09-28 (i)) static 的 _widenedStand 同理不在 onStart 清 —— "这个目标需要
        // 放宽站位半径"是客观几何事实, 重建任务实例不该让它退回半径 1 再撞一次墙。
        _detourTiming = false;
        _detourAnchor = null;
        _detourBlocks = null;
        _detourIndex = 0;
        _detourTask = null;
        // 任务一开始就清掉可能卡住的潜行(双通道), 否则 bot 会一路潜行着
        // 走到目标前 — 既难看, 也让用户误以为交互总是带潜行。
        if (!_shiftClick) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-10) 清上方方块子任务在飞: 持续返回同一子任务, 不被 reach
        // 翻转/分支切换一 tick 抛弃 (15:13 场次死循环根因)。完成或 10s 超时
        // 后放行, 回到正常交互流程。
        if (_clearAboveTask != null) {
            if (_clearAboveTask.isFinished(mod)) {
                _clearAboveTask = null;
                _clearAbovePos = null;
            } else if (System.currentTimeMillis() - _clearAboveSinceMs > CLEAR_ABOVE_PERSIST_MS) {
                Debug.logMessage("(InteractWithBlockTask) clear-above "
                        + aboveShortString() + " not finished in "
                        + (CLEAR_ABOVE_PERSIST_MS / 1000) + "s, dropping (may be protected/unreachable)");
                _clearAboveTask = null;
                _clearAbovePos = null;
            } else {
                setDebugState("Clearing block above interaction target");
                return _clearAboveTask;
            }
        }

        // (2026-09-23) 水平绕行挖掘在飞: 抱持当前 DestroyBlockTask, 单块挖成
        // (变空气)后放行, 让 CANT_REACH 分支挂下一块; finished 但没变空气或
        // 单块 10s 超时 → 本轮失败, gate 重新计时(轮次 static 累加)。
        if (_detourTask != null) {
            BlockPos cur = (_detourBlocks != null && _detourIndex < _detourBlocks.size())
                    ? _detourBlocks.get(_detourIndex) : null;
            if (cur == null) {
                _detourTask = null;
            } else if (blockIsAir(mod, cur)) {
                _detourTask = null;
                _detourIndex++;
            } else if (_detourTask.isFinished(mod)) {
                detourRoundFail(mod, "block " + cur.toShortString() + " finished but not air");
            } else if (System.currentTimeMillis() - _detourBlockSinceMs > DETOUR_BLOCK_TIMEOUT_MS) {
                detourRoundFail(mod, "block " + cur.toShortString() + " timeout "
                        + (DETOUR_BLOCK_TIMEOUT_MS / 1000) + "s");
            } else {
                setDebugState("Detouring around " + _target.toShortString() + ", breaking "
                        + cur.toShortString() + " (" + (_detourIndex + 1) + "/"
                        + _detourBlocks.size() + ")");
                return _detourTask;
            }
        }

        _cachedClickStatus = ClickResponse.CANT_REACH;

        // (2026-09-17 脱险抱持) 已判不可达 → 锁住本任务, 只把游荡子任务挂到底, 不重评
        // 交互分支(否则下一 tick 就把 wander 丢掉 = 空转)。见字段区注释。
        if (_escaping) {
            if (_wanderTask.isFinished(mod) || _escapeTimer.elapsed()) {
                _escaping = false;
            } else {
                setDebugState("Escaping, wandering away from " + _target.toShortString());
                return _wanderTask;
            }
        }

        // Get our use item first
        if (!ItemTarget.nullOrEmpty(_toUse) && !StorageHelper.itemTargetsMet(mod, _toUse)) {
            _moveChecker.reset();
            _clickTimer.reset();
            return TaskCatalogue.getItemTask(_toUse);
        }

        // Wander and check
        // (2026-09-11) 无游荡模式绝不续跑 wander 子任务 —— 实例被 isEqual 复用时
        // onStart 不重跑, 上一次交互失败留下的 active wander 会让任务恢复后第一
        // tick 就接着游荡 (22:51:46 事故: guard 6.1s 后回到交互, 立刻 Wander 5.0)。
        if (_wanderOnFailure && _wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _moveChecker.reset();
            _clickTimer.reset();
            return _wanderTask;
        }
        // (2026-09-11) moveChecker 判负不再无条件"拉黑目标+wander": 容器交互
        // (_wanderOnFailure=false) 时 bot 冻结/站桩会让本检查器瞬间判负, 把眼前
        // 熔炉拉黑 → canReach(=黑名单取反)全链失效 → 父任务误判"无容器"重做熔炉。
        // (2026-09-27 兜底自锁修复) 墙钟无进展: 只有真实位移 >0.2 格才清零 15s 兜底。
        // 旧实现是 CANT_REACH 分支末尾的 `if (moveOk) _giveUpTimer.reset();`, 与
        // 下方判负时的 `_moveChecker.reset()` 互为清零源 → `_giveUpTimer` 永不 elapsed
        // (详见字段区注释)。
        if (_noProgressAnchor == null
                || _noProgressAnchor.squaredDistanceTo(mod.getPlayer().getPos()) > RecoveryTuning.STUCK_MOVE_EPSILON_SQ) {
            noteProgress(mod);
        }

        boolean moveOk = _moveChecker.check(mod);
        if (!moveOk) {
            // 失败=无真实进展, 无游荡模式下兜底计时持续走表。
            // (2026-09-02 Q2: 目标正上方被实心方块堵住 → 到不了 → 先清掉暴露目标再试)
            // 用户实测: 开熔炉时 bot 误把圆石放到炉顶(69,-21,394), 之后目标炉(69,-22,394)
            // 一直 CANT_REACH (不是 WAIT_FOR_CLICK)。旧的"清上方"逻辑只挂在
            // WAIT_FOR_CLICK 分支, 所以到这里就直接 requestBlockUnreachable + 随机探索,
            // 石头永远不拆、炉子永远开不开 (日志: Stuck-break 在砸 69,-21 但主任务泄气走)。
            // 此处在【放弃前】、仅当是右键交互且目标正上方有可破坏实心方块时, 先
            // DestroyBlockTask 清掉它"暴露"容器, 回来再开炉取物 —— 人类也会清碍事方块
            // 而不是扭头游走。判定口径与下方 WAIT_FOR_CLICK 分支的 isSolid+canBreak 一致,
            // 只碰 _target.up()(绝不清目标本体), 且只有真走不到(卡住)才触发, 不误伤。
            if (_wanderOnFailure || _giveUpTimer.elapsed()) {
                if (_interactInput == Input.CLICK_RIGHT) {
                    Task clear = clearAboveOrGiveUp(mod, "CANT_REACH stuck, clearing solid above");
                    if (clear != null) return clear;
                }
                Debug.logMessage("Failed, blacklisting and wandering.");
                mod.getBlockTracker().requestBlockUnreachable(_target);
                return _wanderTask;
            }
            // 无游荡模式未到 15s 兜底: 重置检查器, 继续走下方点击/寻路流程。
            _moveChecker.reset();
        }

        // (2026-09-28 (i) 先绕后挖) 被实心环封堵过的目标: 交互站位半径放宽
        // (1 → RecoveryTuning.WIDENED_STAND_RADIUS), 给 baritone 一次"退半步 / 斜着站"
        // 的机会 —— 这是挖走廊之前的"绕"。未放宽过的目标逐字保持旧行为 (半径 1)。
        int standRadius = _widenedStand.contains(_target)
                ? RecoveryTuning.WIDENED_STAND_RADIUS : reachDistance;
        Goal moveGoal = createGoalForInteract(_target, standRadius, _direction, _interactOffset, _walkInto,
                mod.getPlayer().getBlockPos().getY());
        ICustomGoalProcess proc = mod.getClientBaritone().getCustomGoalProcess();

        _cachedClickStatus = rightClick(mod);
        // (2026-09-17 让位期间不评瞬时门) 起跳瞬间眼睛抬高 ~1.25 格 → `getCurrentReach()`
        // 会短暂成立 → 落到下面的 WAIT_FOR_CLICK 分支 → 那里 `_ascent.stop(mod)` +
        // `proc.onLostControl()` 会把**正在爬升**的 baritone 腰斩 → 落地重入 = 横跳
        // (用户现场描述: "bot 跳起来的一瞬间判定可以撸到方块, 所以没有垫方块…落地时
        // 又要搭方块")。让位期间腾空时把状态压回 CANT_REACH, 交给协调器继续推进;
        // 落地后重新评定 —— 落地成立才说明真的站到够得着的位置。
        if (_ascent.isActive() && !mod.getPlayer().isOnGround()) {
            _cachedClickStatus = ClickResponse.CANT_REACH;
        }
        switch (_cachedClickStatus) {
            case CANT_REACH -> {
                setDebugState("Getting to our goal");

                // (2026-09-17 统一爬升协调器) 目标明显比我们高一截时, 把垂直爬升
                // 整体交给协调器 —— 它自己按"1.5s 无进展 → 重挂导航 → 补垫料 →
                // 判不可达"推进, 且有明确的 6s 级决策点, 不再出现"baritone 原地跳
                // 半小时没人管"。仅在还差 ≥2 格时才启动, 免得已到位却点不动时
                // 反复 start/done 空转。
                // (2026-09-27) Δy==1 **刻意不并入本闸门** —— 目标只高一格时交互站位
                // 面可以是 bot 自己这一层 (见 createGoalForInteract 的 GoalOr), 靠
                // "站位面取任一 + 同层绕行"解决; 若塞进爬升协调器, 会在"站旁边其实
                // 就能点"的场景里反复搭塔。
                int feetY = mod.getPlayer().getBlockPos().getY();
                int attempts = _ascentAttempts.getOrDefault(_target, 0);
                boolean needsClimb = feetY < _target.getY() - 1;
                if (needsClimb && attempts > ASCENT_MAX_ATTEMPTS) {
                    // (2026-09-17 封顶) 同一目标爬升已失败超限 → 不再重挂, 直接走脱险路径。
                    startEscape(mod, "ascent attempts " + attempts + " > " + ASCENT_MAX_ATTEMPTS);
                    return _wanderTask;
                }
                if (needsClimb) {
                    if (!_ascent.isActive()) {
                        _ascent.start(mod, _target, _target.getY(),
                                () -> createGoalForInteract(_target, reachDistance, _direction,
                                        _interactOffset, _walkInto,
                                        mod.getPlayer().getBlockPos().getY()),
                                "interact target above, feetY=" + feetY
                                        + " targetY=" + _target.getY());
                    }
                    AscentHandoffHelper.TickResult climb = _ascent.tick(mod);
                    if (climb.handled) {
                        // 协调器期间不走本任务自己的 15s 兜底: 它才是唯一的决策者,
                        // 否则两套判据互相清零 (本仓"同一概念只留一个判据源")。
                        _moveChecker.reset();
                        _giveUpTimer.reset();
                        if (climb.done) {
                            // 已到目标层, 下一 tick 走正常点击流程。
                            _ascentAttempts.remove(_target);
                            return null;
                        }
                        if (climb.unreachable) {
                            adris.altoclef.util.helpers.TimeoutLog.hit("InteractWithBlockTask.ascent",
                                    "re-navigate + acquire exhausted, blacklisting target");
                            mod.getBlockTracker().requestBlockUnreachable(_target);
                            // (2026-09-17 脱险) 拉黑 + **真**休整抱持: 只 return 一个 tick 的
                            // wander 会被下一 tick 丢掉 = 空转, 见字段区注释。
                            startEscape(mod, "ascent unreachable, blacklisting target");
                            return _wanderTask;
                        }
                        if (climb.child != null) {
                            setDebugState("Acquiring pillar materials for " + _target.toShortString());
                            return climb.child;
                        }
                        setDebugState("Climbing to " + _target.toShortString() + " (baritone)");
                        return null;
                    }
                }

                // (2026-09-23 水平绕行) 同层近场目标被实心环封堵 → 计时到就
                // 挖侧面走廊。needsClimb 的垂直场景仍只归爬升协调器。
                if (!needsClimb) {
                    Task detour = tickDetour(mod);
                    if (detour != null) return detour;
                }

                // Get to our goal then
                if (!proc.isActive()) {
                    proc.setGoalAndPath(moveGoal);
                }
                _clickTimer.reset();
                _clickStuckTiming = false;
                // (2026-09-27 兜底自锁修复) 这里原有 `if (moveOk) _giveUpTimer.reset();`
                // —— moveOk 会在本分支上一拍 `_moveChecker.reset()` 之后连续 ~4s 判正,
                // 把 15s 兜底每拍清零。已改由 tick 顶部的墙钟锚点统一负责"有进展才清零",
                // 本处不再有自己的判据源。
            }
            case WAIT_FOR_CLICK -> {
                setDebugState("Waiting for click");
                // 够到了 → 交回普通点击流程, 协调器收工。
                _ascent.stop(mod);
                if (proc.isActive()) {
                    proc.onLostControl();
                }
                _clickTimer.reset();
                // 卡死检测：可达、正对目标、右键按下却迟迟没有结果。
                if (!_clickStuckTiming) {
                    _clickStuckTiming = true;
                    _clickStuckTimer.reset();
                } else if (_clickStuckTimer.elapsed()) {
                    _clickStuckTiming = false;
                    // 床/箱子等上方有实体方块会被 MC 拒绝使用 → 撸掉它再试。
                    if (_interactInput == Input.CLICK_RIGHT) {
                        Task clear = clearAboveOrGiveUp(mod, "Click stuck for 2s");
                        if (clear != null) return clear;
                    }
                    // (2026-09-01 人类化 H2: 右键 2s 卡住, 上方又没大实心方块, 还可能是
                    // 草/花/藤/双植物这种"非实心非空气非流体"但 MC 仍 reject 放方块/点床的植被
                    // 挡在 _target (地面) 或 _target.up() (放置落点/床的本格)。人类这时会
                    // 直接左键点掉眼前这株小草, 不会 wander 重选。此处就清 _target / up / up(2) 三格,
                    // 绝不挖远处土方石 (否则跟 isSolid 清段重叠, 越界破坏地形)。
                    // (2026-09-02 修: 去掉 _target 本身) 日志实锤 18:45 开箱(45,-12,360)卡
                    // 住 → "Brushing veget off right-click target -> Destroy block at 45,-12,360"
                    // 把【箱子】当植被去挖! 开容器时 _target 是箱子(isSolid=false 被判成植被)。
                    // 目标本身是用户要交互的方块, 绝不清; 只清目标上方挡住视线/放置的植被。
                    if (_interactInput == Input.CLICK_RIGHT) {
                        BlockPos[] probe = new BlockPos[]{_target.up(), _target.up(2)};
                        for (BlockPos p : probe) {
                            BlockState bs = mod.getWorld().getBlockState(p);
                            boolean air = WorldHelper.isAir(mod, p);
                            boolean solid = WorldHelper.isSolid(mod, p);
                            boolean fluid = !bs.getFluidState().isEmpty();
                            if (!air && !solid && !fluid && WorldHelper.canBreak(mod, p)) {
                                Debug.logMessage("(InteractWithBlockTask) Click stuck for 2s, clearing veget " + p.toShortString() + " block=" + bs.getBlock());
                                setDebugState("Brushing veget off right-click target");
                                return new DestroyBlockTask(p);
                            }
                        }
                    }
                    // 上方没东西可撸：按原有超时逻辑放弃并 wander。
                    // (2026-09-11) 无游荡模式: 15s 兜底未到前不拉黑不游荡, 原地继续点。
                    if (!_wanderOnFailure && !_giveUpTimer.elapsed()) {
                        break;
                    }
                    mod.getBlockTracker().requestBlockUnreachable(_target);
                    return _wanderTask;
                }
            }
            case CLICK_ATTEMPTED -> {
                Debug.logInternal("(InteractWithBlockTask: Click attempted!)");
                _ascent.stop(mod);
                if (proc.isActive()) {
                    proc.onLostControl();
                }
                if (_clickTimer.elapsed()) {
                    // We tried clicking but failed.
                    _clickTimer.reset();
                    _clickStuckTiming = false;
                    // (2026-09-11) 无游荡模式: 15s 兜底未到前不拉黑不游荡。
                    if (!_wanderOnFailure && !_giveUpTimer.elapsed()) {
                        break;
                    }
                    if (!_wanderOnFailure && _giveUpTimer.elapsed() && !_giveUpLogged) {
                        _giveUpLogged = true;
                        adris.altoclef.util.helpers.TimeoutLog.hit("InteractWithBlockTask.giveUpTimer", "15s click attempted without success, blacklisting target");
                    }
                    mod.getBlockTracker().requestBlockUnreachable(_target);
                    return _wanderTask;
                }
            }
        }

        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.SNEAK);
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        // (2026-09-17) 协调器可能正握着 customGoal + 按键, 所有退出路径都要收工。
        _ascent.stop(mod);
        // (2026-09-23) 子任务由 task runner 收停; 引用清掉, static 轮次保留。
        _detourTask = null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
        //return _trying && !proc(mod).isActive();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof InteractWithBlockTask task) {
            if ((task._direction == null) != (_direction == null)) return false;
            if (task._direction != null && !task._direction.equals(_direction)) return false;
            if ((task._toUse == null) != (_toUse == null)) return false;
            if (task._toUse != null && !task._toUse.equals(_toUse)) return false;
            if (!task._target.equals(_target)) return false;
            if (!task._interactInput.equals(_interactInput)) return false;
            return task._walkInto == _walkInto;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Interact using " + _toUse + " at " + _target + " dir " + _direction;
    }

    /** (2026-09-12 诊断) 交互目标位, 供 snapshot 打印目标格方块/邻块现场。 */
    @Override
    public BlockPos getDiagTargetBlock() {
        return _target;
    }

    public ClickResponse getClickStatus() {
        return _cachedClickStatus;
    }

    /**
     * (2026-09-17) 启动一次**真实**的脱险休整: 累加同目标爬升失败次数(static, 防父级
     * 重建绕过)、重置游荡实例(复用实例的 `_origin` 是上一次使用的位置, 不重置则
     * `isFinished` 立刻成立 → 0.3s 就"游荡完成" = 空转)、锁住本任务最多
     * {@link #ESCAPE_SECONDS} 秒只挂游荡子任务。
     */
    private void startEscape(AltoClef mod, String reason) {
        if (_ascentAttempts.size() > 256) {
            _ascentAttempts.clear();
        }
        int n = _ascentAttempts.merge(_target, 1, Integer::sum);
        Debug.logToFile("altoclef_diag.log", "[AscentEscape] target=" + _target.toShortString()
                + " attempts=" + n + "/" + ASCENT_MAX_ATTEMPTS + " reason=" + reason);
        _wanderTask.resetForReuse(mod);
        _escaping = true;
        _escapeTimer.reset();
    }

    /**
     * (2026-09-10) 发起"清目标上方方块"。返回要执行的子任务; null = 放弃清理
     * (超限/不可破坏), 调用方走原有 requestBlockUnreachable + wander 放弃路径。
     * 日志携带上方方块身份 (block=), 用于定案"清不掉"是不是门框/保护方块。
     */
    private Task clearAboveOrGiveUp(AltoClef mod, String reason) {
        BlockPos above = _target.up();
        BlockState bs = mod.getWorld().getBlockState(above);
        if (!WorldHelper.isSolid(mod, above) || !WorldHelper.canBreak(mod, above)) {
            return null;
        }
        if (_clearAboveAttempts.size() > 256) {
            // 粗粒度防泄漏: 跨场次累计过大直接清零重新计数。
            _clearAboveAttempts.clear();
            _clearAboveCapLogged.clear();
        }
        int attempts = _clearAboveAttempts.merge(above, 1, Integer::sum);
        if (attempts > CLEAR_ABOVE_MAX_ATTEMPTS) {
            if (_clearAboveCapLogged.add(above)) {
                Debug.logMessage("(InteractWithBlockTask) clear-above cap " + CLEAR_ABOVE_MAX_ATTEMPTS
                        + " reached @" + above.toShortString() + " block=" + bs.getBlock()
                        + " — 反复清不掉(疑保护块/够不着), 之后直接走放弃路径");
                Debug.logToFile("altoclef_diag.log", "[ClearAboveCap]\t" + above.toShortString()
                        + " block=" + bs.getBlock() + " interactTarget=" + _target.toShortString());
            }
            return null;
        }
        Debug.logMessage("(InteractWithBlockTask) " + reason + ", clearing block above "
                + _target.toShortString() + " -> " + above.toShortString()
                + " block=" + bs.getBlock() + " attempt=" + attempts + "/" + CLEAR_ABOVE_MAX_ATTEMPTS);
        _clearAboveTask = new DestroyBlockTask(above);
        _clearAbovePos = above;
        _clearAboveSinceMs = System.currentTimeMillis();
        setDebugState("Clearing block above interaction target");
        return _clearAboveTask;
    }

    private String aboveShortString() {
        return _clearAbovePos != null ? _clearAbovePos.toShortString() : "?";
    }

    // ==================== (2026-09-23 水平绕行实现) ====================

    /**
     * 同层近场封堵 gate + 3s 计时 + 走廊执行。
     * @return 非 null = 调用方应返回该任务; null = 无干预, 继续原 baritone 流程
     */
    private Task tickDetour(AltoClef mod) {
        Vec3d pos = mod.getPlayer().getPos();
        BlockPos feet = mod.getPlayer().getBlockPos();
        // (2026-09-27 y 差 1 缝) 闸门由"严格同层"放宽到"同层 **或** 目标恰高一格"。
        // 旧闸门 `feet.getY() == _target.getY()` 与爬升协调器的 `feetY < targetY-1`
        // 恰好错开, Δy==+1 落在两条兜底之间的缝里 (现场 20260927-222854 站死 102.7s)。
        // 目标高一格时交互站位面就是 bot 自己这一层 (见 createGoalForInteract), 所以
        // 走廊也必须在这一层挖 —— 这也是人类做法: 沿着自己这层挖到炉子旁边。
        // Δy==-1 不放进来: 那种几何下 GoalNear 自带"站在目标正上方"的 in-goal 格,
        // 不缺兜底, 不扩大行为面。
        int dyToTarget = _target.getY() - feet.getY();
        boolean gate = _direction == null
                && (dyToTarget == 0 || dyToTarget == 1)
                && Math.abs(feet.getX() - _target.getX()) <= 3
                && Math.abs(feet.getZ() - _target.getZ()) <= 3
                && !mod.getClientBaritone().getPathingBehavior().isPathing()
                && mod.getClientBaritone().getPathingBehavior().getPath().isEmpty();
        if (!gate) {
            _detourTiming = false;
            return null;
        }

        // 已有走廊: 派下一块挖
        if (_detourBlocks != null) {
            if (_detourIndex >= _detourBlocks.size()) {
                detourCompleted(mod);
                return null;
            }
            BlockPos next = _detourBlocks.get(_detourIndex);
            if (blockIsAir(mod, next)) {
                _detourIndex++;
                return null;
            }
            if (_detourTask == null) {
                if (_detourRounds.getOrDefault(_target, 0) >= DETOUR_MAX_ROUNDS) {
                    resetDetourState();
                    _detourTiming = false;
                    startEscape(mod, "detour rounds exhausted");
                    return _wanderTask;
                }
                _detourTask = new DestroyBlockTask(next);
                _detourBlockSinceMs = System.currentTimeMillis();
                Debug.logMessage("(InteractWithBlockTask) [Detour] breaking " + next.toShortString()
                        + " (" + (_detourIndex + 1) + "/" + _detourBlocks.size()
                        + ") to reach " + _target.toShortString());
                return _detourTask;
            }
            return _detourTask;
        }

        // 计时: 3s 内真实移动 >0.2 格 → 重新计时
        if (!_detourTiming) {
            _detourTiming = true;
            _detourAnchor = pos;
            _detourTimer.reset();
            return null;
        }
        if (_detourAnchor != null && _detourAnchor.squaredDistanceTo(pos) > RecoveryTuning.STUCK_MOVE_EPSILON_SQ) {
            _detourAnchor = pos;
            _detourTimer.reset();
            return null;
        }
        if (!_detourTimer.elapsed()) return null;

        // (2026-09-28 (i) 先绕后挖) 3s 无位移 + baritone 无路径 —— 但先别挖:
        // 先把交互站位半径放宽一次 (1 → WIDENED_STAND_RADIUS), 给 baritone 一次
        // "退半步 / 斜着站" 的重新寻路机会; 仍然无解才挖走廊。
        // 依据: 半径 1 的 in-goal 只有目标的 6 个面邻格, 站位环全实心时天然无解
        // (现场 20260923-085300 就是本机制被引入的那次)。这一步即用户要的"先绕"。
        if (!_widenedStand.contains(_target)) {
            if (_widenedStand.size() > RecoveryTuning.WIDENED_STAND_MAP_CAPACITY) {
                _widenedStand.clear();
            }
            _widenedStand.add(_target);
            _detourTiming = false;
            // 旧 goal 的 A* 可能仍 inProgress —— 不清就永远用不上新 goal 重算。
            try {
                mod.getClientBaritone().getPathingBehavior().cancelEverything();
            } catch (Throwable ignored) {
            }
            Debug.logMessage("(InteractWithBlockTask) [Detour] widen stand radius to "
                    + RecoveryTuning.WIDENED_STAND_RADIUS + " for " + _target.toShortString()
                    + " (try walking around before digging)");
            Debug.logToFile("altoclef_diag.log", "[RECOVERY]\t[WidenStand]\tradius 1 -> "
                    + RecoveryTuning.WIDENED_STAND_RADIUS + " at " + _target.toShortString()
                    + " (no path + no move 3s; reposition attempt before corridor)");
            return null;
        }

        // 触发一轮
        if (_detourRounds.size() > 256) _detourRounds.clear();
        if (_detourRounds.getOrDefault(_target, 0) >= DETOUR_MAX_ROUNDS) {
            _detourTiming = false;
            startEscape(mod, "detour rounds exhausted");
            return _wanderTask;
        }
        int round = _detourRounds.merge(_target, 1, Integer::sum);
        List<BlockPos> blocks = planDetourPath(mod, feet);
        _detourTiming = false;
        if (blocks == null || blocks.isEmpty()) {
            Debug.logMessage("(InteractWithBlockTask) [Detour] no valid side corridor for "
                    + _target.toShortString() + " (round " + round + "/" + DETOUR_MAX_ROUNDS + ")");
            return null;
        }
        _detourBlocks = blocks;
        _detourIndex = 0;
        Debug.logMessage("(InteractWithBlockTask) [Detour] corridor for " + _target.toShortString()
                + ": " + blocks.size() + " block(s) " + blocks);
        return null;
    }

    /**
     * BFS(4 方向, 固定层 = **bot 自己脚下的层**): 起点 feet → 目标的水平 4 邻位之一,
     * 空气格 cost=1、可挖实心格 cost=100 (优先少挖)。返回需挖实心块序列
     * (bot 侧 → 目标侧); 无通路或挖块超过 {@link #DETOUR_MAX_BLOCKS} 返回 null。
     *
     * (2026-09-27 y 差 1 缝) 旧实现把层写死成 `_target.getY()` —— 在 Δy==1 的几何下
     * 等于"在目标层找站位格", 而 bot 根本抬不上那一层 (这正是无解的那一层) ⇒ 走廊
     * 计划出来也没用。改为挂在 bot 自己的层: `feetY == targetY` 时 `planeY ==
     * _target.getY()`、horizManhattan 与 manhattan 等价, **与旧行为逐字等价**;
     * `feetY == targetY-1` 时才有新行为 (在自己这层挖到炉子旁边)。
     */
    private List<BlockPos> planDetourPath(AltoClef mod, BlockPos start) {
        final int planeY = start.getY();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Integer.compare(a.cost, b.cost));
        Map<BlockPos, Node> best = new HashMap<>();
        Node root = new Node(start, 0, null);
        open.add(root);
        best.put(start, root);
        Node goal = null;
        while (!open.isEmpty() && goal == null) {
            Node cur = open.poll();
            if (best.get(cur.pos) != cur) continue;
            if (!cur.pos.equals(start) && cur.pos.getY() == planeY
                    && horizManhattan(cur.pos, _target) == 1) {
                goal = cur;
                break;
            }
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos np = cur.pos.offset(d);
                if (np.getY() != planeY
                        || Math.abs(np.getX() - _target.getX()) > 3
                        || Math.abs(np.getZ() - _target.getZ()) > 3) {
                    continue;
                }
                int cell = detourCellCost(mod, np);
                if (cell < 0) continue;
                int nc = cur.cost + cell;
                Node ex = best.get(np);
                if (ex == null || nc < ex.cost) {
                    Node nn = new Node(np, nc, cur);
                    best.put(np, nn);
                    open.add(nn);
                }
            }
        }
        if (goal == null) return null;
        List<BlockPos> seq = new ArrayList<>();
        for (Node w = goal; w != null && !w.pos.equals(start); w = w.parent) {
            if (detourCellCost(mod, w.pos) >= 100) seq.add(w.pos);
        }
        Collections.reverse(seq);
        if (seq.isEmpty() || seq.size() > DETOUR_MAX_BLOCKS) return null;
        return seq;
    }

    /**
     * 格子通行判定。
     * @return 1=空气可站; 100=实心可挖(挖空后可站); -1=不可通行
     */
    private int detourCellCost(AltoClef mod, BlockPos p) {
        if (p.equals(_target)) return -1;
        BlockState state = mod.getWorld().getBlockState(p);
        if (!state.getFluidState().isEmpty()) return -1;
        boolean headAir = blockIsAir(mod, p.up())
                && mod.getWorld().getBlockState(p.up()).getFluidState().isEmpty();
        BlockState floor = mod.getWorld().getBlockState(p.down());
        boolean floorOk = floor.getFluidState().isEmpty() && WorldHelper.isSolid(mod, p.down());
        if (state.isAir()) {
            return (headAir && floorOk) ? 1 : -1;
        }
        if (!WorldHelper.isSolid(mod, p)) return -1; // 非实心非流体(门/栅栏)绕行不处理
        Block block = state.getBlock();
        if (block instanceof ShulkerBoxBlock || block instanceof BedBlock
                || NEVER_DETOUR_BREAK.contains(block)) {
            return -1;
        }
        if (!WorldHelper.canBreak(mod, p) || hasAdjacentLava(mod, p)) return -1;
        // 挖空后玩家要站进此格: 头部必须已空、脚下有支撑
        return (headAir && floorOk) ? 100 : -1;
    }

    private void detourCompleted(AltoClef mod) {
        Debug.logMessage("(InteractWithBlockTask) [Detour] corridor open, re-pathing to "
                + _target.toShortString());
        _detourRounds.remove(_target);
        resetDetourState();
        // 让 baritone 按新几何立即重算, 不等 6s backoff。
        ICustomGoalProcess proc = mod.getClientBaritone().getCustomGoalProcess();
        if (proc.isActive()) proc.onLostControl();
    }

    private void detourRoundFail(AltoClef mod, String reason) {
        Debug.logMessage("(InteractWithBlockTask) [Detour] round fail: " + reason
                + " (rounds " + _detourRounds.getOrDefault(_target, 0)
                + "/" + DETOUR_MAX_ROUNDS + ")");
        resetDetourState();
        _detourTiming = false;
    }

    private void resetDetourState() {
        _detourBlocks = null;
        _detourIndex = 0;
        _detourTask = null;
    }

    /**
     * (2026-09-27 y 差 1 缝) 只看水平轴的曼哈顿距离。旧实现用
     * {@link BlockPos#getManhattanDistance} —— Δy==1 时它会把 |dy|=1 也算进去, 于是
     * "水平邻格"永远凑不满 1, 走廊目标不可达。走廊层固定 = bot 自己的层, 层差由闸门
     * 管, 这里只判水平。
     */
    private static int horizManhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    /** 直查真实世界(绕过 tracker 黑名单): 保证刚挖开的格能被认成空气。 */
    private static boolean blockIsAir(AltoClef mod, BlockPos pos) {
        ClientWorld world = mod.getWorld();
        if (world == null) return true;
        if (!mod.getChunkTracker().isChunkLoaded(pos)) return true;
        return world.getBlockState(pos).isAir();
    }

    private static boolean hasAdjacentLava(AltoClef mod, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (mod.getWorld().getBlockState(p.offset(d)).getBlock() == Blocks.LAVA) return true;
        }
        return false;
    }

    private static final class Node {
        final BlockPos pos;
        final int cost;
        final Node parent;
        Node(BlockPos pos, int cost, Node parent) {
            this.pos = pos;
            this.cost = cost;
            this.parent = parent;
        }
    }

    private ClickResponse rightClick(AltoClef mod) {

        // Don't interact if baritone can't interact.
        if (mod.getExtraBaritoneSettings().isInteractionPaused()) return ClickResponse.WAIT_FOR_CLICK;

        // We can't interact while a screen is open.
        if (!StorageHelper.isPlayerInventoryOpen()) {
            StorageHelper.closeScreen();
        }

        Optional<Rotation> reachable = getCurrentReach();
        if (reachable.isPresent()) {
            LookHelper.lookAt(mod, reachable.get());
            // (2026-09-02 人类操作: 精确瞄准再点击, 用户"还是举盾, 提前右键")
            // 旧代码用 baritone isLookingAt(宽松朝向判定, 不保证射线命中) + 每 tick
            // forcePress 右键: 副手持盾 + 视角稍偏(射线没命中箱子) → 每 tick 右键 =
            // 每 tick 举盾, 箱子永远打不开 (日志 Waiting for click 反复 + 把箱子当
            // 植被挖)。改为 MC 准星射线确认【真正命中目标方块】才点击, 命中期间按
            // 节流间隔重复短按, 未命中(瞄准中)绝不多点 — 从根上不举盾。
            if (isPreciselyAiming(mod)) {
                if (_toUse != null) {
                    mod.getSlotHandler().forceEquipItem(_toUse, false);
                } else {
                    mod.getSlotHandler().forceDeequipRightClickableItem();
                }
                // 潜行劫持修复 (2026-08-21): MC 原版机制是 sneak + 手持任意
                // 物品右键 → 跳过方块交互改为使用物品(手持方块=贴着箱子放
                // 方块, GUI 打不开)。sneak 有两个泄漏通道: 卡住的按键
                // (PlayerInteractionFixChain 的 10s 兜底太慢) 和 baritone
                // InputOverrideHandler 的强制状态(每 tick 重按, 仅 release
                // 键位压不住)。本点击不需要 shift → 点击瞬间双通道强制松开。
                if (!_shiftClick) {
                    mod.getInputControls().release(Input.SNEAK);
                    mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
                }
                // (2026-09-02 节流点击) 命中后按 _clickPressTimer(0.3s) 间隔重复
                // 短按, 不再每 tick 狂点 — 每 tick 点 = 持盾时每 tick 举盾。
                if (_clickPressTimer.elapsed()) {
                    mod.getInputControls().forcePress(_interactInput);
                    _clickPressTimer.reset();
                }
                if (_shiftClick) {
                    mod.getInputControls().hold(Input.SNEAK);
                }
            } else if (_forceClickTimer.elapsed()) {
                // (2026-09-01 WAIT_FOR_CLICK 兜底): reachable 已非空且每 tick lookAt() 过
                // 但 isLookingAt 因方块面/角度/半块(床头床脚) 判错 → 1.5s 后强制一次交互;
                // 若仍失败由 CLICK_ATTEMPTED 的 5s _clickTimer / 清上方卡死检测接手。
                // (2026-09-04 误触诊断+窄修) 新任务 _forceClickTimer 继承 BaseTimer
                // _prevTime=0 → 首 tick elapsed()=true → 准星还没转到目标就盲点右键。
                // 对倒岩浆/水是灾难: 液体倒错位置会流动毁掉 cast (实测 21:25:00 任务
                // START 与 Click attempted 同秒 → Escaping lava)。流体桶【严禁盲点】,
                // 落回 WAIT_FOR_CLICK 等准星跟上 (1-2 tick); 其余交互保留兜底但打
                // 诊断日志 (准星实际指向 vs 目标)。
                boolean pouringFluid = false;
                if (_toUse != null && _toUse.getMatches() != null) {
                    for (Item it : _toUse.getMatches()) {
                        if (it == Items.LAVA_BUCKET || it == Items.WATER_BUCKET) {
                            pouringFluid = true;
                            break;
                        }
                    }
                }
                HitResult diagHit = MinecraftClient.getInstance().crosshairTarget;
                String crosshairDesc = (diagHit instanceof BlockHitResult diagB)
                        ? diagB.getBlockPos().toShortString() + "/" + diagB.getSide()
                        : diagHit.getType().toString();
                if (pouringFluid || !isWithinStrictInteractReach(mod)) {
                    Debug.logInternal("(InteractWithBlockTask) SKIP force-click: "
                            + (pouringFluid ? "pouring fluid bucket" : "beyond strict interact reach")
                            + ", crosshair=" + crosshairDesc + " != target "
                            + _target.toShortString() + ", waiting for precise aim");
                } else {
                    Debug.logInternal("(InteractWithBlockTask) FORCE-CLICK crosshair=" + crosshairDesc
                            + ", target=" + _target.toShortString() + ", dir=" + _direction);
                    if (_toUse != null) {
                        mod.getSlotHandler().forceEquipItem(_toUse, false);
                    } else {
                        mod.getSlotHandler().forceDeequipRightClickableItem();
                    }
                    if (!_shiftClick) {
                        mod.getInputControls().release(Input.SNEAK);
                        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
                    }
                    // (2026-09-01 短按, 同上方分支) 见右侧分支注释。
                    mod.getInputControls().forcePress(_interactInput);
                    if (_shiftClick) {
                        mod.getInputControls().hold(Input.SNEAK);
                    }
                    return ClickResponse.CLICK_ATTEMPTED;
                }
            }
            _forceClickTimer.reset();
            return ClickResponse.WAIT_FOR_CLICK;
        }
        if (_shiftClick) {
            mod.getInputControls().release(Input.SNEAK);
        }
        return ClickResponse.CANT_REACH;
    }

    public Optional<Rotation> getCurrentReach() {
        return LookHelper.getReach(_target, _direction);
    }

    /**
     * (2026-09-27 盲点闸门) 目标是否在**严格交互距离**(LookHelper.INTERACT_REACH_NO_SIDE
     * = 3.0)之内 —— 允许"盲点"(准星还没压上目标就 forcePress)的唯一前提。
     *
     * 为什么必须同时收紧: LookHelper 已把"够不着"的否决闸门从 3.0 放宽到 MC 真实
     * reach(4.5)。若盲点兜底照旧在 4.5 格边缘触发, 就退回 2026-09-02 的持盾事故
     * (准星偏差 → forcePress 右键 → MC 判成举盾, 容器永远不开)。规则:
     * ≤3.0 允许盲点(射程宽裕, 角度差一点也能命中); 3.0~4.5 一律等
     * isPreciselyAiming(准星射线真命中 _target)成立才点。
     */
    private boolean isWithinStrictInteractReach(AltoClef mod) {
        double d = LookHelper.INTERACT_REACH_NO_SIDE;
        Vec3d eye = mod.getPlayer().getCameraPosVec(1.0F);
        return eye.squaredDistanceTo(Vec3d.ofCenter(_target)) <= d * d;
    }

    /**
     * (2026-09-02 人类操作: 精确瞄准) 用 MC 准星射线确认【真正命中】目标方块。
     * baritone isLookingAt 只判朝向(宽松), 副手持盾时视角稍偏 → 每 tick 右键 =
     * 举盾, 箱子打不开。这里要求 crosshairTarget 是 BLOCK 命中且 blockPos ==
     * _target — 真命中才可右键。
     */
    private boolean isPreciselyAiming(AltoClef mod) {
        // (2026-09-04 流体目标特殊处理) MC crosshairTarget 射线默认忽略流体 →
        // 舀岩浆/水时 _target 是液体方块, 准星永远"命中"不了它 (只会命中后面的
        // 固体/上方的搭路方块) → isPreciselyAiming 恒 false → 永远落到盲点兜底。
        // 流体目标改用 baritone isLookingAt (朝向判定): 空桶的 use() 自己按
        // SOURCE_ONLY 射线找源, 朝向对即可舀到, 不需要准星命中流体方块。
        BlockState targetState = mod.getWorld().getBlockState(_target);
        if (targetState.getBlock() == Blocks.LAVA || targetState.getBlock() == Blocks.WATER) {
            return mod.getClientBaritone().getPlayerContext().isLookingAt(_target);
        }
        HitResult hr = MinecraftClient.getInstance().crosshairTarget;
        return hr instanceof BlockHitResult bhr
                && bhr.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(_target);
    }

    public enum ClickResponse {
        CANT_REACH,
        WAIT_FOR_CLICK,
        CLICK_ATTEMPTED
    }
    // (2026-09-01 WAIT_FOR_CLICK 兜底: reachable 成立且 lookAt() 调过, 但 isLookingAt
    // 因方块面/角度/半块(床头床脚) 持续判 FALSE 时, 1.5s 后强制一次点击 (CLICK_ATTEMPTED)。
    // 否则只写 WAIT_FOR_CLICK 但 5s _clickTimer 仅挂 CLICK_ATTEMPTED 分支 = 死循环。
    private final TimerGame _forceClickTimer = new TimerGame(1.5);
    // (2026-09-02 人类操作: 精确瞄准+节流点击) 命中目标方块后按此间隔重复右键,
    // 避免每 tick 狂点(持盾时每 tick 点 = 每 tick 举盾, 箱子打不开)。
    private final TimerGame _clickPressTimer = new TimerGame(0.3);
}
