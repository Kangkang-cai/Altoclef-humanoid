package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
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
import net.minecraft.item.Items;
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
    //
    // ⚠ (2026-09-14 用户约束) **不含原木** —— 原木是挖树任务结算的目标物, 拿它
    // 垫 1 格就等于木料 -1。用户原话"别用原木, 木板可以"。没木板/土石时由
    // needScaffoldMaterials / startAcquireMaterials 去挖几格 (见下), 绝不动原木。
    private static final Item[] _pillarMaterials = PillarJumpHelper.withWoodNoLogs();
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
    /**
     * (2026-09-14 垫塔判据滞后) 垫塔期间【锁定】的落脚基准, 净空闸门只按它算。
     *
     * 为什么不能每 tick 取 getBlockPos(): PillarJumpHelper 靠 hold(JUMP) 起跳,
     * 起跳那一瞬间玩家的 BlockPos y 会 +1 —— 而 headroomBlockedForPillar 看的是
     * feet.up(2)。玩家踩在自己的隧道底 (y=-60) 时 aboveHead=-58 (空气, 放行);
     * 一跳起来 feet 变 -59, aboveHead 立刻变成 -57 —— **那正是要挖的目标方块本身
     * (实心)** → 判"净空被挡" → endPillar → 下一 tick 判据又翻转回来重开。
     *
     * 现场 20260914-162715 实锤: [PillarApproach] start 重入 13 次、PillarDiag 里
     * PLACE 分支 0 次 (从没进入过放置窗口), debugstate 每 300ms 在
     * "Pillaring up to reach" 与 "Getting to block..." 之间横跳 10s。
     * 而且 start 会顺带 reset _pillarLastY/_pillarNoProgressTimer/_pillarTotalTimer
     * ⇒ 20s 总超时闸门永远被重置, 只能靠上层的 DigStuck 超时拽出来。
     *
     * 滞后语义: 只在【落地且高度真的提升了】时用新的落脚点重锁基准。
     */
    private BlockPos _pillarBaseFeet = null;

    // ---- (2026-09-14 缺垫块 → 去挖几格) 用户: "没方块为什么不触发挖几个方块" ----
    // 旧行为: tryPillarUpToReach 一发现没垫块就 return false, 落回导航 → 对"站立面
    // 之上 N 格"的目标导航永远不可满足 → 冻结或拉黑换目标。现场 20260914-115322
    // 里 bot 只有原木(按新约束不算垫块)时走的正是这条死路。
    // 新行为: 目标是头顶的 + 水平已在身边 + 无可用垫块 → 派采集任务挖 3 格泥土
    // (草方块/土/土径, 手挖即可), 拿到后下一轮自然进垫塔分支。
    /** 一次采集几格垫块。净升 1 格即可把"眼→方块下表面"缩进 reach (本现场 5.38→4.38)。 */
    private static final int ACQUIRE_MATERIALS_COUNT = 3;
    /**
     * 采集子任务: **实例缓存 keep-alive** —— 父 onTick 每 tick 重跑, 每 tick new 一个
     * 会被框架的 isEqual/防横跳逻辑挡下或反复重启 (本仓"侧线子任务必须实例缓存"铁律)。
     */
    private Task _acquireMaterialsTask = null;
    /** 每个 DestroyBlockTask 实例只采一次; 采完仍不够就落回原有放弃路径, 不无限循环。 */
    private boolean _acquireStarted = false;
    /** 单次采集总时长上限。超了收工, 交回原有放弃路径换目标。 */
    private final TimerGame _acquireTotalTimer = new TimerGame(30);
    private boolean _acquireDoneLogged = false;

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
        _unbreakableRefusedLogged = false;
        // (2026-09-14 垫塔接近) 新实例归零。timer 新建即到期, 进垫塔分支时才 reset。
        _pillarActive = false;
        _pillarLastY = Integer.MIN_VALUE;
        // (2026-09-14 垫塔判据滞后) 垫塔基准脚块同样归零: 残留会让新实例沿用上一次
        // 落脚层位的 aboveHead 判净空, 与当前玩家实际位置错位。
        _pillarBaseFeet = null;
        // (2026-09-14 缺垫块采集) 子任务引用必须清 —— 残留会让新实例 keep-alive 一个
        // 已经不该跑的采集任务。标记归零: 实例重建即新一轮, 允许再采一次。
        _acquireMaterialsTask = null;
        _acquireStarted = false;
        _acquireDoneLogged = false;
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

    // (2026-09-14 不可破坏方块硬守卫) 目标是基岩类 (hardness<0) → 物理上永远
    // 挖不掉, 旧逻辑会 "Block in range, mining..." 无限左键直到 45s 兜底。
    // 现场 20260914-132420 实锤: 隧道挖 -60 行撞基岩 (1.18.2 基岩带最高到 -60),
    // 对 (-214,-60,279) 傻挖 17s 到用户手动退出, snapshot.log reason=stuck 每秒
    // 一条、dug 卡 3/80, 全程无任何报错 —— 用户视角就是"对着基岩挥镐不停止"。
    //
    // 守卫语义 (对齐 refuseIfProtected 范式): 命中 → 记一次日志 + requestBlock-
    // Unreachable 拉黑 (候选选择器当场换目标) + 视作完成 (isFinished 放行, 防上层
    // 卡死)。MineDownExploreTask 隧道选块侧另有一道独立拦截 (skipAirAndWater,
    // 主路径), 判据同源: getHardness(world, pos) < 0 (与 WorldHelper.canBreak
    // / JumpObstructionHelper 同款判据)。
    private boolean _unbreakableRefusedLogged = false;

    private boolean refuseIfUnbreakable(AltoClef mod) {
        if (!mod.getChunkTracker().isChunkLoaded(_pos)) return false; // 未加载读作空气, 不判负
        if (mod.getWorld().getBlockState(_pos).getHardness(mod.getWorld(), _pos) >= 0) return false;
        if (!_unbreakableRefusedLogged) {
            _unbreakableRefusedLogged = true;
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            String m = "[UnbreakableGuard] 拒绝挖掘不可破坏方块: " + _pos.toShortString()
                    + " (hardness<0 基岩类), 放弃该目标";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
        return true;
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

        // (2026-09-14 不可破坏方块硬守卫) 第二道闸门: 目标是基岩类 → 立即拉黑
        // 放弃, 不喂任何 lookAt / CLICK_LEFT / builder。详见 refuseIfUnbreakable。
        if (refuseIfUnbreakable(mod)) {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            setDebugState("Unbreakable block (bedrock-like), refusing to break");
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

            // (2026-09-14 缺垫块 → 先去挖几格) 用户: "没方块为什么不触发挖几个方块"。
            // 目标在头顶 + 水平已在身边 + 完全没有可用垫块 (原木不算, 见 _pillarMaterials)
            // → 先派采集任务挖 3 格泥土, 采到后下一轮自然进下面的垫塔分支。
            // 必须排在 tryPillarUpToReach 之前 —— 后者见无材料会直接 return false。
            // 子任务实例缓存 keep-alive, 完成/超时才收工 (见 pumpAcquireMaterials)。
            if (_acquireMaterialsTask != null) {
                Task acquire = pumpAcquireMaterials(mod);
                if (acquire != null) {
                    _gettingToBlockTick = 0;
                    return acquire;
                }
            } else if (needScaffoldMaterials(mod)) {
                Task acquire = startAcquireMaterials(mod);
                if (acquire != null) {
                    _gettingToBlockTick = 0;
                    return acquire;
                }
            }

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
        // (2026-09-14 v2 判据滞后) 用户现场"(bot) 一跳起来任务立马就跳了, 反复横跳"。
        // 旧实现【每 tick 重算】下面四个前置, 而垫塔本身靠 hold(JUMP) 起跳 —— 起跳
        // 那一瞬间玩家的 BlockPos y 会 +1, 判据随之自激翻转 (证据与完整机理见
        // _pillarBaseFeet 字段注释)。新语义分两段:
        //   · 未垫塔 (_pillarActive=false): 照旧用【即时】位置准入;
        //   · 垫塔中: 前置判据不再重算, 只在"已经垫到目标所在层及以上"时收工,
        //     其余一切失败由各自的超时闸门处理 (无进展 4s / 总时长 20s / 没料)。
        if (_pillarActive) {
            // ①' 垫到目标层及以上 → 目标已在脚边或以下, 垫塔使命完成, 交回常规挖掘。
            if (mod.getPlayer().getBlockPos().getY() >= _pos.getY()) {
                endPillar(mod);
                return false;
            }
        } else {
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
            // ③ 没垫块 → 交回原逻辑。缺料采集由 needScaffoldMaterials /
            //    startAcquireMaterials 在调用方(更早)处理, 走到这里通常意味着
            //    "已经采过一轮还是不够" → 正常落回放弃闸门, 不永久冻结。
            if (!PillarJumpHelper.hasMaterials(mod, _pillarMaterials)) {
                endPillar(mod);
                return false;
            }
            // ③b (2026-09-14 低净空闸门, 2026-09-14 v2 基准改为垫塔基准脚块)
            //    即时位置版本见 headroomBlockedForPillar(mod, feet) 的注释。
            //    首次准入仍用即时位置: 开跑前玩家应站稳在地面, 不存在"跳到一半"。
            if (headroomBlockedForPillar(mod, mod.getPlayer().getBlockPos())) {
                endPillar(mod);
                return false;
            }
        }
        if (!_pillarActive) {
            _pillarActive = true;
            _pillarLastY = Integer.MIN_VALUE;
            _pillarBaseFeet = mod.getPlayer().getBlockPos();
            _pillarNoProgressTimer.reset();
            _pillarTotalTimer.reset();
            Debug.logToFile("altoclef_diag.log", "[PillarApproach] start target="
                    + _pos.toShortString()
                    + " eyeY=" + String.format("%.2f", mod.getPlayer().getEyeY())
                    + " player=" + mod.getPlayer().getBlockPos().toShortString()
                    + " headroom=" + (_pillarBaseFeet.up(2).toShortString() + " solid="
                    + WorldHelper.isSolid(mod, _pillarBaseFeet.up(2))));
        }
        // ④ 只在【落地时】采样高度: 跳跃上升途中 getBlockPos().getY() 会瞬时 +1,
        //    拿它当"上升了"会让无进展计时被反复重置 → 原地跳永不超时。
        if (mod.getPlayer().isOnGround()) {
            int y = mod.getPlayer().getBlockPos().getY();
            if (y > _pillarLastY) {
                _pillarLastY = y;
                _pillarNoProgressTimer.reset();
                // (2026-09-14 v2) 真的站稳在更高一层了 → 重锁净空基准。此时才允许
                // 重新判定"还能不能再往上垫一格"。不这样做的话, 垫塔会把整座塔垫到
                // 天花板里 (净空闸门被起始基准永久豁免)。
                _pillarBaseFeet = mod.getPlayer().getBlockPos();
            }
        }
        // ④b 净空闸门 (垫塔中用锁定基准, 见 ③b / _pillarBaseFeet 注释): 垫到某一层
        //     后发现净空已经不够再上一格, 而目标还在上面 ⇒ 垫不到了, 收工判不可达。
        if (_pillarBaseFeet != null && headroomBlockedForPillar(mod, _pillarBaseFeet)) {
            Debug.logToFile("altoclef_diag.log", "[PillarApproach] headroom blocked at "
                    + _pillarBaseFeet.toShortString() + " (aboveHead="
                    + _pillarBaseFeet.up(2).toShortString() + ") target=" + _pos.toShortString());
            endPillar(mod);
            mod.getBlockTracker().requestBlockUnreachable(_pos);
            return true;
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
        // ⑦ (2026-09-14 实锤补漏) 只放 customGoal **不够** —— 上一轮 reach 短暂出现时
        //    "Breaking the normal way." 分支已启动 baritone builder (见上 L312), 它不会
        //    自己停: builder 持续占着输入通道, 垫塔的 hold(JUMP) / interactBlock 全被压掉。
        //    现场 20260914-115322 的 [NAV-STUCK] 快照全是 builder=true + inputs 全 false,
        //    bot 站在原地 7~18s 一格上不去 —— 这正是"材料在手上却垫不起来"的现场机制。
        //    与挖掘分支 (L295-296) 同款处理: 两个进程都要放。
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        PillarJumpHelper.step(mod, _pillarMaterials);
        return true;
    }

    /**
     * (2026-09-14 缺垫块 → 先去挖几格) 是否该派采集任务。
     *
     * 三条前置与 tryPillarUpToReach 完全同源 —— 采垫块就是为了垫塔, 前置不满足时
     * 采了也是白采 (目标在几十格外时那是导航问题, 不该先去挖土)。
     */
    private boolean needScaffoldMaterials(AltoClef mod) {
        if (_acquireStarted) return false; // 每实例只采一次, 采完仍不够就落回放弃路径
        if (!targetAboveEye(mod)) return false;
        if (horizontalDistanceSqToPlayer(mod) > 4.0) return false;
        // (2026-09-14 低净空闸门) 净空被挡时垫塔不可能发生, 别去采垫块。
        // 与 tryPillarUpToReach 的 ③b 同源 —— "同一概念只留一个判据源"。
        if (headroomBlockedForPillar(mod, mod.getPlayer().getBlockPos())) return false;
        return !PillarJumpHelper.hasMaterials(mod, _pillarMaterials);
    }

    /**
     * (2026-09-14 低净空闸门) 起跳必需净空是否被挡: 脚块上方第二格
     * (feet.up(2), 即头块再上一格) 实心 → 起跳头蹭天花板, 垂直速度被顶掉,
     * 脚下永远实心 → 永远进不了放置窗口 = 原地跳死循环 (PillarJumpHelper
     * 只看脚下/腾空窗口, 不查净空 —— 净空是调用方的前置责任)。
     *
     * 放行一整格净空的原因: 放置窗口在脚块变更 (净升 1.0) 时即开, 需要的
     * bbox 最高点只到头块上方一格; 1.18.2 基岩带最高到 -60 而钻石隧道头行
     * 在 -59, 常见"净空被挡"场景就是 -57 行天花板。区块未加载时不判负
     * (与本仓 isAir 同款语义, "读不到"不等于"被挡")。
     */
    /**
     * (2026-09-14 v2 落脚基准入参) 净空必须按【指定的落脚位置】算, 不能默认读即时
     * BlockPos —— 起跳那一 tick 脚位会瞬时 +1, aboveHead 随之撞上目标方块本身,
     * 判据自激翻转。垫塔中用 _pillarBaseFeet (见该字段注释), 首次准入/派采集前
     * 用即时位置 (那时玩家应当站稳)。
     */
    private boolean headroomBlockedForPillar(AltoClef mod, BlockPos feet) {
        BlockPos aboveHead = feet.up(2);
        if (!mod.getChunkTracker().isChunkLoaded(aboveHead)) return false;
        return WorldHelper.isSolid(mod, aboveHead);
    }

    /**
     * 派采集垫块子任务 (实例缓存, 只建一次)。
     *
     * @return 子任务实例 (keep-alive); null = 建不出来 (目录里没有 / 已达尝试上限)。
     */
    private Task startAcquireMaterials(AltoClef mod) {
        Task t = TaskCatalogue.getItemTask(Items.DIRT, ACQUIRE_MATERIALS_COUNT);
        _acquireStarted = true;
        _acquireDoneLogged = false;
        if (t == null) {
            Debug.logToFile("altoclef_diag.log", "[PillarAcquire] no task for " + Items.DIRT
                    + " target=" + _pos.toShortString() + " → 落回放弃路径");
            return null;
        }
        _acquireMaterialsTask = t;
        _acquireTotalTimer.reset();
        // 采集期间不能留残余长按 (垫塔可能上一 tick 还在 hold(JUMP))。
        endPillar(mod);
        Debug.logToFile("altoclef_diag.log", "[PillarAcquire] start target=" + _pos.toShortString()
                + " want=" + ACQUIRE_MATERIALS_COUNT + " X " + Items.DIRT
                + " eyeY=" + String.format("%.2f", mod.getPlayer().getEyeY())
                + " have=" + PillarJumpHelper.countMaterials(mod, _pillarMaterials));
        return t;
    }

    /**
     * 采集子任务 keep-alive。
     *
     * @return 仍在跑 → 返回同一实例 (框架 isEqual 判等, 不会换实例); 已完成/超时 →
     *         null (调用方不再返回它, 框架会 stop 掉)。
     */
    private Task pumpAcquireMaterials(AltoClef mod) {
        // (2026-09-14 实时计数早停, 现场 20260914-125213 实锤) 不能只信子任务自己的
        // isFinished —— 它走 ItemTarget/POSSESS 判量(本仓"哨兵 targetCount 陷阱"),
        // 收够了也判不完: 该现场要 3 份泥土, 实际收了 17 份、自挖 6 格深坑 + 3x3 环
        // (20+ 方块), 最后靠 30s 硬超时掐掉(还被游戏 5.2s 卡顿吃掉 3.7s)。这里每
        // tick 直读【真实背包】(getItemCountReal, 不走 500ms 缓存层 —— 判"够没够"
        // 属于判定关键资源, 按本仓"背包权威计数"惯例一律走 Real)。
        int haveDirt = mod.getItemStorage().getItemCountReal(Items.DIRT);
        if (haveDirt >= ACQUIRE_MATERIALS_COUNT) {
            logAcquireDone(mod, "enough(dirt=" + haveDirt + ")");
            _acquireMaterialsTask = null;
            return null;
        }
        if (_acquireTotalTimer.elapsed()) {
            logAcquireDone(mod, "timeout");
            _acquireMaterialsTask = null;
            return null;
        }
        boolean finished;
        try {
            finished = _acquireMaterialsTask.isFinished(mod);
        } catch (Throwable t) {
            // 没 start 过就查 isFinished 可能炸 → 当作完成, 不再重派 (本仓"读不到与 0 同码"
            // 教训的同款防御: 宁可少采一轮, 不要卡在这个判断上)。
            finished = true;
        }
        if (finished) {
            logAcquireDone(mod, "finished");
            _acquireMaterialsTask = null;
            return null;
        }
        return _acquireMaterialsTask;
    }

    private void logAcquireDone(AltoClef mod, String why) {
        if (_acquireDoneLogged) return;
        _acquireDoneLogged = true;
        Debug.logToFile("altoclef_diag.log", "[PillarAcquire] done(" + why + ") target="
                + _pos.toShortString()
                + " have=" + PillarJumpHelper.countMaterials(mod, _pillarMaterials));
    }

    /** 收尾垫塔: 幂等松开跳跃/移动键 (所有退出路径必调, 见 PillarJumpHelper 设计约束)。 */
    private void endPillar(AltoClef mod) {
        // (2026-09-14 v2) 基准伴生于"正在垫塔", 收工即清空 —— 残留会让下一次
        // 垫塔准入错位到上一次的落脚层。
        _pillarBaseFeet = null;
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
        // (2026-09-14 缺垫块采集) 子任务引用同样在两个清除点 (onStart/onStop) 都要清 ——
        // 否则被抢占后残留引用会让下一实例 keep-alive 一个不该跑的采集任务。
        _acquireMaterialsTask = null;
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
        // (2026-09-14 不可破坏方块硬守卫) 同款: 不可破坏视作"完成", 让上层另选目标。
        if (refuseIfUnbreakable(mod)) {
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
