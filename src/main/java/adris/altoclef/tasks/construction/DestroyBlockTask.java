package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.MineDownExploreTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.PlaceBlockSchematic;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.PillarJumpHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Destroy a block at a position.
 */
public class DestroyBlockTask extends Task implements ITaskRequiresGrounded {

    private final BlockPos _pos;
    private final MovementProgressChecker _moveChecker = new MovementProgressChecker(6, 0.1, 4, 0.01);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5, true);

    private final TimerGame _tryToMineTimer = new TimerGame(5);

    // (2026-09-12 v17) 挖掘总时长兜底: reach 持续在但 45s 没挖穿 = 挖掘无效
    // (力点击被吃掉/方块不可破坏判定错误), 放弃防死挖。空手最慢石头 ~7.5s。
    private final TimerGame _miningTotalTimer = new TimerGame(45);

    // (2026-09-03 够不着直接放弃, 用户"没用的随机探索, 再够不到直接放弃") 对悬浮/太高
    // 的目标(reach 持续缺失), 旧逻辑 _tryToMineTimer 超时 → "Getting to block…"设
    // baritone 目标 → 够不着 → wander → 重试同目标 → 无限空转(09:10 撸树顶 y=68 原木
    // 空转 30s, 既不搭方块垫脚也不放弃)。requestBlockUnreachable 原只在 _moveChecker
    // 失败触发, 但 bot 能走动(moveChecker 通过)就不触发 → 永远不判不可达。这里补:
    // reach 持续缺失超时仍够不着 → 判不可达黑名单并【直接放弃】(返回 null 交上层
    // MineAndCollectTask 换下一个目标), 不再随机探索空转。
    //
    // ⚠ (2026-09-14 需求反转) 本段原结尾"不做搭方块垫脚(用户判定放弃)"已作废 ——
    // 用户明确改要求"搭方块垫脚上去够"。见 tryPillarUpToReach: 够不着时先垫塔升
    // 上去, 垫不动(无材料/被头顶挡住/超时)才落回这里的放弃路径。
    private int _gettingToBlockTick = 0;
    private static final int GETTING_TO_BLOCK_GIVE_UP_TICKS = 40; // 2s, 够不着即放弃

    // (2026-09-13 距离前提, 用户"三层都修") "2s 拿不到 reach" 只对【已经在身边】的
    // 目标等于"够不着"; 对几十格外的目标它只是"还没走到" —— baritone 长途跋涉几十秒
    // 是常态。20260913-144922 实锤: 干草在 50+ 格外(玩家 37,84,-478 vs 目标
    // 87,89,-524), 本判据每 2.1s 触发一次 requestBlockUnreachable(chat.log
    // "Blacklist: (87,89,-524): Try 1 / 4" 连发), 黑名单又被"靠近 1 格"RESET →
    // 4 分钟零推进、上层在 hay 与 explore 之间横跳。
    // 改为: 只有目标已进入身边范围才累计"够不着"; 远处导航中由 _moveChecker
    // (6s 无进展 + 5s 无 reach) 兜底, 不会无限走。
    private static final double GIVE_UP_JUDGE_RANGE = 6;
    private boolean _farGiveUpSuppressed = false;

    // For vines and stuff
    private boolean _wasClose = false;

    // (2026-09-01 自挖中央记录) 任务开始时目标是否实心: 只有"开始时实心、结束时
    // 变空气"才算我们真实挖开的一格(开始时已是空气的不记, 防误标天然洞空气)。
    // 记录进 MineDownExploreTask 的持久化自挖记忆, 供矿洞检测区分天然空气。
    private boolean _wasSolidAtStart = false;
    private boolean _selfDugRecorded = false;

    // (2026-09-13 传送门保护硬守卫) 命中保护只记一次 [PortalGuard] 日志,
    // 详见 refuseIfProtected。
    private boolean _protectedRefusalLogged = false;

    // ==================== (2026-09-14 垫塔接近) ====================
    // 用户推翻 2026-09-03 的"够不到直接放弃, 不做搭方块垫脚", 明确要求
    // "搭方块垫脚上去够" (原话: "怎么没触发搭过去靠近")。
    //
    // 现场 20260914-113531: bot 站在 (88.50, 63.00, 0.79), feet y=63 → 眼 y=64.62;
    // 目标悬空原木 (89/90,70,2~3) 下表面 y=70 → 眼到方块 5.38 格 (> 4.5 真实
    // blockReachDistance), 用户现场确认"那个方块确实够不到"; 3D 距离 7.55。
    // 用户确认现场形态: 树干是 bot 自己挖空的 —— memory/selfdug.log 有据:
    //   overworld 90 63 2 / 90 64 2 / … / 90 69 2 / 91 70 2
    // 即 x=90, z=2 的树干被从 y=63 一路挖到 y=69, 只剩 (90,70,2) 悬空一格。
    //
    // 旧行为(两步都坏): reach 缺失 → 落 "Getting to block…" → 只
    // setGoalAndPath(GoalNear(_pos,1)) → 对"站立面之上 7 格"不可满足 → baritone
    // 每 6s 一轮全展开失败 (latest.log 11:35:59 = 7562368 movements / 11:36:05 =
    // 8514176 movements, 均无 "Path goes for") → 原地冻结 26s 到用户手动停。
    // diag.log 的 [NAV-STUCK] 快照全为 customGoal=true pathing=false path=absent、
    // bIn/inputs 全 false、hand=stone_axe。
    //
    // 新行为: reach 持续缺失 + 目标在眼睛及上方 + 背包有垫块 → 复用
    // PillarJumpHelper 竖直垫塔 (每步净上升 1 格), 直到 LookHelper.getReach 变
    // present, 下一 tick 自然走正常挖掘分支。
    private static final Item[] _pillarMaterials = PillarJumpHelper.withWood();
    /** 垫塔总时长上限: 20s 净升 ~15 格有余, 超了就是垫不动。 */
    private final TimerGame _pillarTotalTimer = new TimerGame(20);
    /** 连续 4s 净高度没上升 (原地跳 / 头顶被挡) = 垫不动。 */
    private final TimerGame _pillarNoProgressTimer = new TimerGame(4);
    /**
     * 上次【落地时】采到的脚块 y。
     * ⚠ 必须用落地采样: 跳跃上升途中 getBlockPos().getY() 会瞬时 +1, 拿它当
     * "高度上升了"会让无进展计时恒被重置 → 反复原地跳永不超时 (这正是本仓
     * "卡死检测必须排除合法零速状态"教训的镜像: 这里要排除的是【合法瞬时升高】)。
     */
    private int _pillarLastY = Integer.MIN_VALUE;
    private boolean _pillarActive = false;

    public DestroyBlockTask(BlockPos pos) {
        _pos = pos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _tryToMineTimer.forceElapse();
        _wanderTask.resetWander();
        StorageHelper.closeScreen();
        _wasSolidAtStart = WorldHelper.isSolid(mod, _pos);
        _selfDugRecorded = false;
        _gettingToBlockTick = 0;
        _farGiveUpSuppressed = false;
        _protectedRefusalLogged = false;
        // (2026-09-14 垫塔接近) 新实例归零。timer 新建即到期, 进垫塔分支时才 reset。
        _pillarActive = false;
        _pillarLastY = Integer.MIN_VALUE;
        // 保险: 若上一实例被抢占, 其 onStop 已 release; 这里再松一次防残漏长按。
        PillarJumpHelper.release(mod);

        mod.getBehaviour().push();
        // Avoid placing on top, to prevent our annoying "run away" bug
        mod.getBehaviour().avoidBlockPlacing(pos -> _pos.up().equals(pos));
    }

    /**
     * (2026-09-13 传送门保护硬守卫) 见 黑曜石门保护机制报告.md「已知边界 1」。
     *
     * 保护谓词只挂在 baritone 挖掘层 (`avoidBlockBreaking` → `AltoClefSettings.
     * shouldAvoidBreaking`, 由 `WorldHelper.canBreakFailReason` / `JumpObstructionHelper`
     * 消费), 而本任务走的是两条**手动**破坏路径 —— force `CLICK_LEFT` 长按 (L172)
     * 与 builder process (L180), 两者都不查该谓词。于是任何显式派发 DestroyBlockTask
     * 的上层都能直接挖掉门体 (全库 40+ 处 `new DestroyBlockTask`, 无一处过滤)。
     *
     * 20260913-185026 实锤: 下界挖金矿时 `MineDownExploreTask` 的隧道队列打穿返回
     * 门框 → 门熄灭 (dim=the_nether pos=(8,90,-57) / (81,52,-81), 栈
     * CollectGoldIngotTask > MineAndCollectTask > MineOrCollectTask >
     * MineDownExploreTask > DestroyBlockTask)。
     *
     * 守卫语义 (对齐 PlaceBlockTask.refuseIfProtected 范式): 命中 → 记一次日志 +
     * 拉黑该位 (让候选选择器类上层当场换目标) + 视作完成 (isFinished 放行, 防上层
     * 卡死在"永远未完成")。注意本守卫只挡"门体"这一类 (isPortalBodyBlock), 不拦
     * 其他任务注册的 avoidBlockBreaking 谓词 —— 那些是"临时避让"语义, 上层显式
     * 要求挖时必须放行 (否则会误伤要塞砖/清场/逃生挖障等合法挖掘)。
     */
    private boolean refuseIfProtected(AltoClef mod) {
        if (mod.getBehaviour().isPortalBodyBlock(_pos)) {
            if (!_protectedRefusalLogged) {
                _protectedRefusalLogged = true;
                mod.getBlockTracker().requestBlockUnreachable(_pos);
                String m = "[PortalGuard] 拒绝破坏受保护位置: " + _pos.toShortString()
                        + " (门体/门框), 放弃该目标";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
            return true;
        }
        return false;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-13 传送门保护硬守卫) 第一道闸门: 目标是门体 → 立即放弃,
        // 不做任何 lookAt / CLICK_LEFT / builder 喂料。详见 refuseIfProtected。
        if (refuseIfProtected(mod)) {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            setDebugState("Protected position (portal guard), refusing to break");
            return null;
        }

        // (2026-09-05 挖完仍挖真因, 用户"挖掉一块金矿不到一秒, 但任务还挖它卡几秒"):
        // 任务框架 (Task.tickInternal) 从不调用子任务 isFinished —— 子任务只在父任务
        // 换目标时才被替换。而 BlockTracker 缓存的重扫清理有周期, 已挖掉的空气位在
        // 清理前仍可能被上层选中 (pickNearestCacheOre 的方块状态重校验还有 500ms 节流),
        // 于是本任务对着空气 builder 重建/左键空挥, 卡到 MovementProgressChecker
        // 超时 (~6s) 才脱身 —— 正是"挖完的方块任务还在挖、卡几秒"的来源。
        // 修复: onTick 最顶上直查真实世界, 已是空气 → 立即收工 (记自挖、停输入、
        // 不再喂 builder)。下一 tick 父任务 isValid(空气位)=false 自然换目标。
        // (2026-09-05 rev2 冻结修复) 两处收紧:
        // ① 区块未加载时 isAir 语义为 true (isAir 对未加载返回 true), 而"凭记忆
        //    导航回未加载区"是合法场景, 不能早退 —— 只对【已加载】的空气位生效。
        // ② 同时把该位置 requestBlockUnreachable 拉黑, 所有候选选择器当场跳过
        //    (空气位拉黑无副作用; 之前不拉黑导致记忆导航反复重选同一位 → 站死)。
        if (mod.getChunkTracker().isChunkLoaded(_pos) && WorldHelper.isAir(mod, _pos)) {
            if (_wasSolidAtStart && !_selfDugRecorded) {
                _selfDugRecorded = true;
                MineDownExploreTask.recordBrokenBlock(mod, _pos);
            }
            setDebugState("Target already air, nothing to break");
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            return null;
        }

        // Wander and check
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _moveChecker.reset();
            return _wanderTask;
        }
        // (2026-09-12 v17) 正在有效挖掘时【跳过移动检查】—— 挖掘期间原地不动是合法的。
        // 20260912-191401 实锤: pick-escape DIG_FOOT 挖脚下石头, 空手 7.5s/块 >
        // moveChecker 6s 窗口 → 每次挖到 6s 被 moveChecker 误判"卡住" →
        // requestBlockUnreachable 拉黑 + 强制 Wander → 回来重挖 → 循环永不穿。
        // (上次成功是脚下泥土 0.75s < 6s, 掩盖了该 bug。)
        // 只在"最近 5s reach 都缺失"(真走不到) 时 moveChecker 失败才拉黑。
        // (2026-09-14 垫塔接近) 垫塔期间同样豁免: 竖直垫塔 XZ 天然零位移, 不豁免
        // 就会 6s 一到被 moveChecker 判"卡住" → 强制 Wander 把垫塔打断。
        if (!_pillarActive && !_moveChecker.check(mod) && _tryToMineTimer.elapsed()) {
            _moveChecker.reset();
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            _wanderTask.resetWander();
            return _wanderTask;
        }

        // do NOT break if we're standing above it and it's dangerous below...
        if (!WorldHelper.isSolid(mod, _pos.up()) && mod.getPlayer().getPos().y > _pos.getY() && _pos.isWithinDistance(mod.getPlayer().isOnGround()? mod.getPlayer().getPos() : mod.getPlayer().getPos().add(0, -1, 0), 0.89)) {
            if (WorldHelper.dangerousToBreakIfRightAbove(mod, _pos)) {
                setDebugState("It's dangerous to break as we're right above it, moving away and trying again.");
                return new RunAwayFromPositionTask(3, _pos.getY(), _pos);
            }
        }

        // We're trying to mine
        Optional<Rotation> reach = LookHelper.getReach(_pos);
        if (reach.isPresent()) {
            // (2026-09-14 垫塔接近) 垫塔期间够到了 → 收工并松开跳跃键。垫塔的退出
            // 路径必须 release, 否则 residual hold(JUMP) 会让 bot 持续起跳
            // (PillarJumpHelper 的设计约束)。
            endPillar(mod);
            // (2026-09-12 v17) 转换沿: tryToMine 刚从"缺 reach 超时"回到可达 = 开始
            // 一轮新挖掘, 重置总挖掘计时 (TimerGame 新建即到期, 不能 onStart reset)。
            if (_tryToMineTimer.elapsed()) {
                _miningTotalTimer.reset();
            }
            _tryToMineTimer.reset();
            _gettingToBlockTick = 0;
        }
        if (!_tryToMineTimer.elapsed()) {
            if (_miningTotalTimer.elapsed()) {
                // 持续可达 45s 仍没挖穿 (空手最慢石头 ~7.5s×富余) → 挖掘无效, 放弃。
                setDebugState("Mining stalled, giving up");
                mod.getBlockTracker().requestBlockUnreachable(_pos);
                return null;
            }
            if (reach.isPresent() && (mod.getPlayer().isTouchingWater() || mod.getPlayer().isOnGround())) {
                setDebugState("Block in range, mining...");
                // Break the block, force it.
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                if (!LookHelper.isLookingAt(mod, _pos)) {
                    LookHelper.lookAt(mod, reach.get());
                }
                if (LookHelper.isLookingAt(mod, _pos)) {
                    // Tool equip is handled in `PlayerInteractionFixChain`. Oof.
                    mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
            } else {
                setDebugState("Breaking the normal way.");
                if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
                    // Try breaking normally.
                    mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                    Debug.logMessage("Break Block: Restarting builder process");
                    mod.getClientBaritone().getBuilderProcess().build("destroy block", new PlaceBlockSchematic(Blocks.AIR), _pos);
                }
            }
        } else {
            setDebugState("Getting to block...");

            // (2026-09-14 垫塔接近) 目标在眼睛及上方 + reach 够不到 + 背包有垫块
            // → 垫方块升上去够 (用户"搭方块垫脚上去够", 推翻 09-03 的放弃决定)。
            // 垫塔接管时本 tick 直接返回, 且不累计下面的"够不着"计数 —— 垫塔本身
            // 要跑好几秒, 累计会让它在半路被放弃闸门打断。
            if (tryPillarUpToReach(mod)) {
                _gettingToBlockTick = 0;
                return null;
            }

            // (2026-09-03 够不着即放弃) reach 持续缺失且累计足够长仍够不着 → 判不可达,
            // 黑名单交给上层换目标, 不再无限 "Getting to block → wander → 重试同目标"。
            // (2026-09-13 距离前提) 见 GIVE_UP_JUDGE_RANGE 注释: 只有目标已在身边
            // 范围才累计"够不着"; 远处导航中不判负, 继续走。
            // (2026-09-14 水平前提) 单靠 3D 距离把"正上方 5 格"(3D 5.4) 与"正上方
            // 7 格"(3D 7.55, 现场 20260914-113531) 推到了 6 的两侧, 两侧都错:
            //   <6 → 判"在身边、够不着" → 拉黑 → 上层重选同格 → churn (Try 5/4);
            //   >6 → 判"还在路上" → 计数清零 → 永不放弃 → 冻结 26s。
            // 修正: 水平距离已在身边 (<=2 格) 同样算"到位" —— 剩下的垂直差距是
            // "够不够得着"的问题, 不是"还没走到"的问题。
            boolean horizontallyInPlace = horizontalDistanceSqToPlayer(mod) <= 4.0;
            boolean closeEnoughToJudge = horizontallyInPlace
                    || _pos.isWithinDistance(mod.getPlayer().getPos(), GIVE_UP_JUDGE_RANGE);
            if (!closeEnoughToJudge) {
                // 还在路上: 清零"够不着"计数, 让导航继续。
                if (_gettingToBlockTick != 0) _gettingToBlockTick = 0;
                if (!_farGiveUpSuppressed) {
                    _farGiveUpSuppressed = true;
                    Debug.logToFile("altoclef_diag.log",
                            "[DigGiveUp] target " + _pos.toShortString() + " still far (3D>"
                                    + GIVE_UP_JUDGE_RANGE + " and horizontal>2): not counting as unreachable, keep navigating");
                }
            } else if (_gettingToBlockTick++ > GETTING_TO_BLOCK_GIVE_UP_TICKS) {
                Debug.logMessage("(DestroyBlockTask) unreachable after " + _gettingToBlockTick
                        + " ticks, abandoning " + _pos.toShortString());
                mod.getBlockTracker().requestBlockUnreachable(_pos);
                _gettingToBlockTick = 0;
                return null; // 直接放弃, 交上层换下一个目标, 不随机探索
            }
            boolean isClose = _pos.isWithinDistance(mod.getPlayer().getPos(), 1);
            if (isClose != _wasClose) {
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                _wasClose = isClose;
            }
            if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                // (2026-09-14 垫塔接近) 目标在头顶时不能再用 3D GoalNear/GoalBlock ——
                // 它们要求把脚放到目标那一格, 对"站立面之上 7 格"不可满足: baritone
                // 全展开后直接放弃 (现场 20260914-113531: 11:35:59 = 7562368 /
                // 11:36:05 = 8514176 movements considered, 均无 "Path goes for",
                // [NAV-STUCK] pathing=false path=absent) → 原地冻结 26s。
                // 改用 GoalXZ 只要求水平走到它【正下方】—— 可满足, 也正是垫塔前的
                // 必要站位 (人也是先站到方块底下再垫)。
                Goal goal = targetAboveEye(mod)
                        ? new GoalXZ(_pos.getX(), _pos.getZ())
                        : (isClose ? new GoalBlock(_pos) : new GoalNear(_pos, 1));
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(goal);
            }
        }

        return null;
    }

    /**
     * (2026-09-14 垫塔接近) reach 够不到时竖直垫塔升上去够 —— 用户"搭方块垫脚上去
     * 够", 推翻 2026-09-03 的"够不到直接放弃"。见字段区注释的现场证据链。
     *
     * @return true = 本 tick 已由垫塔接管 (含"垫不动 → 判不可达"); false = 垫塔
     *         不适用 (目标不在上方 / 水平还没到位 / 没材料), 交回原导航+放弃逻辑。
     */
    private boolean tryPillarUpToReach(AltoClef mod) {
        // ① 只处理"目标方块整体在眼睛及上方"。水平远处的目标是导航问题 ——
        //    垫高只会让水平距离更远, 人也不会为了够 20 格外的方块先垫个塔。
        if (!targetAboveEye(mod)) {
            endPillar(mod);
            return false;
        }
        // ② 水平已在身边 (<=2 格) 才垫 —— 与 closeEnoughToJudge 的 horizontallyInPlace
        //    同一判据源。否则"头顶上方 + 水平 50 格"这类目标会误触发垫塔。
        if (horizontalDistanceSqToPlayer(mod) > 4.0) {
            endPillar(mod);
            return false;
        }
        // ③ 没垫块 → 交回原逻辑。09-14 已给放弃闸门补水平前提, 这里会正常判
        //    不可达并换目标, 不会像修复前那样永久冻结。
        if (!PillarJumpHelper.hasMaterials(mod, _pillarMaterials)) {
            endPillar(mod);
            return false;
        }
        if (!_pillarActive) {
            _pillarActive = true;
            _pillarLastY = Integer.MIN_VALUE;
            _pillarNoProgressTimer.reset();
            _pillarTotalTimer.reset();
            Debug.logToFile("altoclef_diag.log", "[PillarApproach] start target="
                    + _pos.toShortString()
                    + " eyeY=" + String.format("%.2f", mod.getPlayer().getEyeY())
                    + " player=" + mod.getPlayer().getBlockPos().toShortString());
        }
        // ④ 只在【落地时】采样高度: 跳跃上升途中 getBlockPos().getY() 会瞬时 +1,
        //    拿它当"上升了"会让无进展计时被反复重置 → 原地跳永不超时。
        if (mod.getPlayer().isOnGround()) {
            int y = mod.getPlayer().getBlockPos().getY();
            if (y > _pillarLastY) {
                _pillarLastY = y;
                _pillarNoProgressTimer.reset();
            }
        }
        if (_pillarNoProgressTimer.elapsed() || _pillarTotalTimer.elapsed()) {
            // ⑤ 垫不动 (原地跳 / 头顶被挡 / 总时长超限) → 判不可达换目标。
            Debug.logToFile("altoclef_diag.log", "[PillarApproach] give up target="
                    + _pos.toShortString()
                    + " y=" + mod.getPlayer().getBlockPos().getY()
                    + " lastY=" + _pillarLastY
                    + " noProgress=" + _pillarNoProgressTimer.elapsed()
                    + " total=" + _pillarTotalTimer.elapsed());
            endPillar(mod);
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            return true;
        }
        setDebugState("Pillaring up to reach " + _pos.toShortString());
        // ⑥ 垫塔靠自己按输入 + interactBlock 放置, 必须让 baritone 松开自定义目标 ——
        //    否则 GoalNear 会同时抢移动输入 (与挖掘分支同一处理)。
        if (mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        }
        PillarJumpHelper.step(mod, _pillarMaterials);
        return true;
    }

    /** 收尾垫塔: 幂等松开跳跃/移动键 (所有退出路径必调, 见 PillarJumpHelper 设计约束)。 */
    private void endPillar(AltoClef mod) {
        if (!_pillarActive) return;
        _pillarActive = false;
        PillarJumpHelper.release(mod);
    }

    /** 目标方块中心到玩家的水平 (XZ) 距离平方。 */
    private double horizontalDistanceSqToPlayer(AltoClef mod) {
        double dx = (_pos.getX() + 0.5) - mod.getPlayer().getX();
        double dz = (_pos.getZ() + 0.5) - mod.getPlayer().getZ();
        return dx * dx + dz * dz;
    }

    /**
     * 目标方块整体在眼睛及上方。
     * "垫塔接近"与"水平接近 goal"共用这一个判据源 —— 同一概念只留一个判据,
     * 避免两处各写一份后在边界上打架 (本仓元规矩)。
     */
    private boolean targetAboveEye(AltoClef mod) {
        return _pos.getY() + 1.0 >= mod.getPlayer().getEyeY();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (!AltoClef.inGame())
            return;
        // (2026-09-02 自挖记录修复: onStop 双通道补记)
        // 根因: Task.tickInternal 换子任务走 _sub.stop() 中断旧实例, 从不调子任务
        // isFinished。MineDownExploreTask 挖隧道每 tick new DestroyBlockTask、挖开
        // 后被框架 stop 替换 → isFinished 永不执行 → 自挖记录(isFinished 里唯一入口)
        // 从未触发, altoclef_selfdug.log 从未生成(实锤) → 自挖隧道空气全被矿洞检测
        // 当"天然空气" → 探索矿洞导航进自己挖的隧道。
        // onStop 是父任务换任务必走路径, 在此补记: 开始时实心、停止时已变空气 =
        // 我们真实挖开的一格(幂等: isFinished 先记过就不重复)。
        if (WorldHelper.isAir(mod, _pos) && _wasSolidAtStart && !_selfDugRecorded) {
            _selfDugRecorded = true;
            adris.altoclef.tasks.movement.MineDownExploreTask.recordBrokenBlock(mod, _pos);
        }
        mod.getBehaviour().pop();
        // (2026-09-14 垫塔接近) 垫塔期间 hold 的跳跃/移动键必须在【所有】退出路径上
        // 松开 (被抢占 / 上层换目标 / 链停止都走这里), 否则残漏长按会让 bot 持续起跳。
        endPillar(mod);
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        // Do not keep breaking.
        // Can lead to trouble, for example, if lava is right above the NEXT block.
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-13 传送门保护硬守卫) 命中保护视作"完成", 让上层另选目标;
        // 否则上层 (每 tick 重派同实例) 会因本任务永远未完成而卡死。
        if (refuseIfProtected(mod)) {
            return true;
        }
        boolean air = WorldHelper.isAir(mod, _pos);
        // (2026-09-01 自挖中央记录) 开始时实心、现在变空气 ⇒ 这格空气是我们挖出来的,
        // 记入持久化自挖记忆(幂等)。矿洞检测据此排除自挖隧道, 不再误判"自己的洞"。
        if (air && _wasSolidAtStart && !_selfDugRecorded) {
            _selfDugRecorded = true;
            adris.altoclef.tasks.movement.MineDownExploreTask.recordBrokenBlock(mod, _pos);
        }
        return air;//;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DestroyBlockTask task) {
            return task._pos.equals(_pos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Destroy block at " + _pos.toShortString();
    }
}
