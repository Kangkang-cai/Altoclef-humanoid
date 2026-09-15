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
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
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

    private ClickResponse _cachedClickStatus = ClickResponse.CANT_REACH;

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

    private static Goal createGoalForInteract(BlockPos target, int reachDistance, Direction interactSide, Vec3i interactOffset, boolean walkInto) {

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
                // (2026-09-01 用户"熔炉距离太远右键失败, 用人类交互逻辑") 旧
                // GoalTwoBlocks(target.up()) 让 bot 停在目标上方/过远位置, 开容器
                // (熔炉/箱子/工作台) 时右键够不着 → CANT_REACH↔WAIT_FOR_CLICK 反复
                // 打转。改为 GoalNear(target, 1): 走到容器旁 1 格内再右键, 人类开
                // 箱就是走到跟前再点。
                return new GoalNear(target.add(interactOffset), Math.max(1, reachDistance));
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

        _cachedClickStatus = ClickResponse.CANT_REACH;

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

        Goal moveGoal = createGoalForInteract(_target, reachDistance, _direction, _interactOffset, _walkInto);
        ICustomGoalProcess proc = mod.getClientBaritone().getCustomGoalProcess();

        _cachedClickStatus = rightClick(mod);
        switch (_cachedClickStatus) {
            case CANT_REACH -> {
                setDebugState("Getting to our goal");
                // Get to our goal then
                if (!proc.isActive()) {
                    proc.setGoalAndPath(moveGoal);
                }
                _clickTimer.reset();
                _clickStuckTiming = false;
                // (2026-09-11) 有真实移动进展才重置无游荡兜底; 冻结站桩时走表,
                // 15s 后由上方 moveChecker 分支/下方卡死分支回落放弃路径。
                if (moveOk) {
                    _giveUpTimer.reset();
                }
            }
            case WAIT_FOR_CLICK -> {
                setDebugState("Waiting for click");
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
                if (pouringFluid) {
                    Debug.logInternal("(InteractWithBlockTask) SKIP force-click: pouring fluid bucket, crosshair="
                            + crosshairDesc + " != target " + _target.toShortString() + ", waiting for precise aim");
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
