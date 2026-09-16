package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.GetToLandTask;
import adris.altoclef.tasks.movement.MineDownExploreTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.PlaceBlockSchematic;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.LandHelper;
import adris.altoclef.util.helpers.PillarJumpHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WaterExitHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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

    // (2026-09-16 泡水漂浮停挖离水) 上岸子任务实例缓存。GetToLandTask.isEqual 恒
    // true —— 完成后必须 reset() 同一实例而非 new（新实例会被 isEqual 挡下不重跑
    // onStart，见 PlaceBlockNearbyTask 同款处理）。
    private GetToLandTask _toLand = null;

    // (2026-09-16 v2 防横跳闩锁) 现场 20260916-101226 10:24:14-37: isFloatingInWater
    // 是瞬时判据, 水面 bobbing 时 isTouchingWater 以 ~0.4s 周期翻转 (腾空相翻 false)
    // → 闸门每 0.4s 松开一次, 父任务返回 null 把刚启动的 GetToLandTask 拆掉
    // (STOP took=198ms) 再重建, 16 格外的岸永远走不到。
    // 修: 触发即闩住 (_waterExitLatched), 中途不再重评瞬时判据, 直到子任务真正
    // 结束 (上岸站住 0.5s / 放弃进 60s static 冷却) 才松闩。
    private boolean _waterExitLatched = false;
    // 收敛出口: 上岸成功后 30s 内同目标再次触发 = 这块只能泡水挖 (上岸也得再下水)
    // → 拉黑换目标, 封顶"上岸-下水"的大周期震荡。
    private int _waterExitGroundedTick = -1;
    private BlockPos _waterExitGroundedPos = null;
    private static final int WATER_EXIT_REPEAT_BLACKLIST_TICKS = 20 * 30;

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
    // 垫 1 格就等于木料 -1。用户原话"别用原木, 木板可以"。完全没料时由
    // tickBaritoneHandoff 的 stall 检测反应式补一次 (见下), 绝不动原木。
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
    // (2026-09-14 v4 交还 baritone) 头顶被【可挖】方块挡住 (树叶/土/木/半砖…) 时,
    // 自写 PillarJumpHelper 只会 hold(JUMP) 空跳 —— 它不挖头顶, 而"脚块变空气"的
    // 放置窗口永远开不了 ⇒ 空跳到超时。但这类挡头不该判"垫不到": baritone 的
    // MovementPillar 构造就是 positionsToBreak={start.above(2)} + positionToPlace=start
    // (反编译实锤), **它本来就是"挖头顶 + 放脚下"**。用户原话: "baritone 会处理悬空方块"。
    // 所以: 让位给 baritone, 用 GoalYLevel(目标层) 驱动; 只有挖不动的 (基岩) 才拉黑。
    private boolean _pillarHandoff = false;
    /** 交还后的观察窗口 20s; 期间 y 有上升就重置 (baritone 在爬只是慢)。 */
    private final TimerGame _pillarHandoffTimer = new TimerGame(HANDOFF_TOTAL_SECONDS);
    private final TimerGame _pillarHandoffStallTimer = new TimerGame(HANDOFF_STALL_SECONDS);
    private int _pillarHandoffLastY = Integer.MIN_VALUE;
    // (2026-09-15 GoalNear 进场) 上次 tick 的水平位置: handoff 目标改成
    // GoalNear(目标, 2) 后 baritone 可能先【走】到目标附近再爬 —— 进场走路阶段
    // y 不涨, 若 stall 只看 y 会把正常进场误判成"上不去"。XZ 位移也算进度;
    // 原地跳(唯一要抓的死锁)XZ 不变、y 不创新高, 两条判据都抓得住它。
    private int _pillarHandoffLastX = Integer.MIN_VALUE;
    private int _pillarHandoffLastZ = Integer.MIN_VALUE;
    /** 本实例是否已交过一次 —— 交过还失败才拉黑, 避免"交还↔自写"来回抖。 */
    private boolean _pillarHandoffTried = false;
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
    private static final int ACQUIRE_MATERIALS_COUNT = 5;

    // (2026-09-15 反应式补料) baritone 交还期间多久没往上爬过一格算"真上不去"。
    // MovementPillar 每格约 1.5s, 4s 无上升 = 要么没料, 要么路径断了, 不必再等。
    private static final double HANDOFF_STALL_SECONDS = 4;
    // 交还总窗口: 09-14 ~ 09-15 是 20s, 期间"有上升就续命"导致 bot 能反复跳十几秒
    // (用户观感"为啥不像以前 2s 就放弃")。补一次料 + 再爬一轮用不到 20s, 收紧到 12s。
    private static final double HANDOFF_TOTAL_SECONDS = 12;
    /**
     * 采集子任务: **实例缓存 keep-alive** —— 父 onTick 每 tick 重跑, 每 tick new 一个
     * 会被框架的 isEqual/防横跳逻辑挡下或反复重启 (本仓"侧线子任务必须实例缓存"铁律)。
     */
    private Task _acquireMaterialsTask = null;

    // (2026-09-15 口径改"够爬") 本次采集要凑的垫块量 = 保底 3 / 目标层差(封顶 10)。
    private int _acquireWant = ACQUIRE_MATERIALS_COUNT;
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
        // (2026-09-14 交还 baritone) 同上, 交还状态也必须随实例归零 —— 残留会让
        // 新目标一上来就被送去爬上一次的 GoalYLevel。
        _pillarHandoff = false;
        _pillarHandoffLastY = Integer.MIN_VALUE;
        _pillarHandoffTried = false;
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
        // (2026-09-15 删自写垫塔) handoff 期间同样豁免: baritone 挖挡块/走位阶段
        // 可能 >6s 零位移, 卡死由 handoff 自己的 20s 超时兜底, 不劳 moveChecker。
        if (!_pillarActive && !_pillarHandoff && !_moveChecker.check(mod) && _tryToMineTimer.elapsed()) {
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
                adris.altoclef.util.helpers.TimeoutLog.hit("DestroyBlockTask.miningTotal", "45s reachable but never broke " + _pos.toShortString());
                setDebugState("Mining stalled, giving up");
                mod.getBlockTracker().requestBlockUnreachable(_pos);
                return null;
            }

            // (2026-09-16 泡水漂浮停挖离水) 现场 20260915-231536 23:22:41-23:23:23:
            // bot 水面 water-bob 30s+ 照常挖石头 (本类旧判据把 isTouchingWater 与
            // isOnGround 同权)。水中悬空挖掘 5x 水 × 5x 悬空 = 25x 惩罚。用户定规矩:
            // 泡水漂浮挖掘时停止挖掘并离开水区; 例外 = 水洞 (附近没有直接连通且可达
            // 的两格空气, WaterExitHelper.hasReachableAirExit=false) 时继续挖。
            // 站浅水底 (脚下有实体) 不算泡水漂浮, 涉水过河不受影响。
            //
            // (2026-09-16 v2 防横跳) 触发后【闩住】, 不再逐 tick 重评瞬时判据 ——
            // bob 腾空相 isTouchingWater 翻 false 曾把子任务 0.2s 一拆 (现场实锤)。
            // 松闩只看 _toLand 真正结束; 结束后按情况记"上岸成功"锚点供重复触发拉黑。
            if (reach.isPresent()) {
                if (_waterExitLatched) {
                    if (_toLand == null) {
                        _waterExitLatched = false;
                    } else if (_toLand.isFinished(mod)) {
                        if (!_toLand.hasGivenUp()) {
                            // 真上岸了 (脚下实体连续 0.5s), 记锚点。
                            _waterExitGroundedTick = WorldHelper.getTicks();
                            _waterExitGroundedPos = _pos;
                            Debug.logToFile("altoclef_diag.log",
                                    "[WATER-EXIT] grounded next to target " + _pos.toShortString());
                        }
                        // 放弃时任务内部已写 60s static 冷却 (isOnCooldown 兜底)。
                        _toLand.reset();
                        _toLand = null;
                        _waterExitLatched = false;
                    }
                }
                // 上岸后短时间内同目标又漂回来 = 只能泡水挖 → 拉黑换目标。
                if (!_waterExitLatched
                        && _waterExitGroundedTick >= 0
                        && _pos.equals(_waterExitGroundedPos)
                        && WorldHelper.getTicks() - _waterExitGroundedTick < WATER_EXIT_REPEAT_BLACKLIST_TICKS
                        && LandHelper.isFloatingInWater(mod)
                        && WaterExitHelper.hasReachableAirExit(mod)) {
                    String m = "[WATER-EXIT] target " + _pos.toShortString()
                            + " needs water approach again within 30s after landing -> blacklist (water-only block)";
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                    mod.getBlockTracker().requestBlockUnreachable(_pos);
                    _waterExitGroundedTick = -1;
                    _waterExitGroundedPos = null;
                    return null;
                }
                if (!_waterExitLatched
                        && !GetToLandTask.isOnCooldown()
                        && LandHelper.isFloatingInWater(mod)
                        && WaterExitHelper.hasReachableAirExit(mod)) {
                    _waterExitLatched = true;
                    if (_toLand != null) {
                        _toLand.reset();
                    } else {
                        _toLand = new GetToLandTask();
                    }
                    String m = "[WATER-EXIT] floating in water while mining -> pause & head to land"
                            + " (latched until grounded/give-up). target=" + _pos.toShortString()
                            + " player=" + mod.getPlayer().getBlockPos().toShortString();
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                }
                if (_waterExitLatched && _toLand != null) {
                    setDebugState("Floating in water (mining 25x slow) -> heading to land first (latched)");
                    return _toLand;
                }
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

            // (2026-09-15 叶包裹不垫塔) 目标本体已在挖掘距离内、只是视线被中间方块
            // (树叶等可挖方块)挡住 → 先挖掉挡块, 不进垫塔。现场 20260915-174702:
            // 原木 (9,79,183) 被橡树叶包裹, bot 站 2 格外 eyeY=79.74, targetAboveEye
            // (80>=79.74) 以 0.26 的边距成立 → 每 0.6s 重进 PillarApproach 原地跳
            // 18s 一格不升 (起跳瞬间 eyeY 越过 80 → reach 短暂出现 → endPillar →
            // 落地重入)。人对"手边被树叶包住的原木"不会先垫塔, 会先把叶子撸掉。
            // 只有目标超出挖掘距离 (真够不着) 或挡块挖不动时才落回垫塔/放弃路径。
            if (tryClearOccluder(mod)) {
                _gettingToBlockTick = 0;
                return null;
            }

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
            }
            // (2026-09-15 反应式补料) 派采集的决策点从"预判不够"改到 stall 侧
            // (tickBaritoneHandoff), 这里只负责 keep-alive 已派出的采集任务。

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
    // (2026-09-15 叶包裹不垫塔) 挡块日志节流: 同一块只打一次, 避免每 tick 刷屏。
    private BlockPos _lastOccluderLogged;

    // (2026-09-15 撸叶 latch) 锁定一片挡叶挖到变成空气才换下一片。现场
    // 20260915-180535: 两片叶子 (-15,84,147 / -15,85,146) 每 tick 交替被选中,
    // 每次切换挖掘进度清零, 3 秒换了 9 轮一片都没断。
    private BlockPos _occluderTarget;

    // (2026-09-16 藤蔓裹木) 当前挡块是否为无碰撞方块 (藤蔓/花/雪片一类 climbable
    // 或装饰块)。这类方块 COLLIDER 射线必穿透 → LookHelper.getReach (同样 COLLIDER
    // 口径) 必 miss → 不能走常规 rotation 瞄准, 改瞄准星口径 (VISUAL) 命中点。
    private boolean _occNoCollision = false;
    /** 无碰撞挡块的本 tick 瞄准点 (VISUAL 射线命中位置), 每 tick 刷新。 */
    private Vec3d _occAim = null;

    /**
     * (2026-09-15 叶包裹不垫塔) 目标在挖掘距离内但视线被可挖方块挡住 → 挖掉挡块。
     * baritone 的爬升只挖挡它【路】的方块, 包住目标的叶子不在它的导航目标里,
     * 这一步是它不覆盖的唯一缺口 —— 分工: 靠近/爬升交 baritone, 目标周边叶子我们撸。
     *
     * @return true = 本 tick 已转为挖挡块; false = 目标真够不着 / 挡块挖不动 /
     *         挡块自己也够不着, 交回 handoff 与放弃路径。
     */
    private boolean tryClearOccluder(AltoClef mod) {
        // (20260916 1330 场次 死循环修复) handoff 期间【完全让位】: 台阶地形 bot 随
        // baritone 起跳/落步上下漂移, 眼-目标瞬时距离会在腾空瞬间假达标 (≤4.5) ——
        // 本分支随即拆 handoff 抢输入 (20260915 互斥方向 = 挡块优先); 下一 tick bot
        // 落回低台阶, 4.5 门又关上, 本分支自己也退出 → "handoff 起跳 → 拆 → 退出 →
        // 重挂" 1.1s 死循环 25+ 轮零产出 (diag.log [PillarHandoff] start x25, 无一
        // done/give up/timeout/STALL)。互斥方向反转: handoff 优先。baritone 的
        // MovementPillar 本来就挖一垫一, 路径挡块它自己处理; handoff 按自身体系退出
        // (done/STALL 补料/12s 超时/立即拉黑) 后本分支自然恢复, 收尾半格撸叶不变。
        if (_pillarHandoff) {
            return false;
        }
        Vec3d eye = mod.getPlayer().getEyePos();
        Vec3d center = Vec3d.ofCenter(_pos);
        // 只处理"目标本体已在挖掘距离内"的情形 —— 超出 reach 的目标是导航/爬升问题,
        // 隔空够不着的挡块挖了也是白挖 (射线终点到不了目标附近)。
        if (eye.distanceTo(center) > 4.5) {
            _occluderTarget = null;
            return false;
        }
        // latch: 锁定的挡块仍实心可挖 → 继续挖它, 不重选; 变空气/挖不动才重新找。
        BlockPos occ = _occluderTarget;
        if (occ != null) {
            BlockState latched = mod.getWorld().getBlockState(occ);
            if (latched.isAir() || latched.getHardness(mod.getWorld(), occ) < 0) {
                _occluderTarget = null;
                _occNoCollision = false;
                _occAim = null;
                occ = null;
            }
        }
        if (occ == null) {
            BlockHitResult hit = mod.getWorld().raycast(new RaycastContext(eye, center,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos found = hit.getBlockPos();
                if (!found.equals(_pos)) {
                    BlockState state = mod.getWorld().getBlockState(found);
                    if (!state.isAir() && state.getHardness(mod.getWorld(), found) >= 0) {
                        occ = found;
                        _occluderTarget = occ;
                        _occNoCollision = false;
                        _occAim = null;
                    }
                }
            }
            // (2026-09-16 藤蔓裹木) COLLIDER 射线对藤蔓穿透 (藤蔓无碰撞箱) → 判
            // "无遮挡", 撸藤分支永不触发。现场 20260916-140459: 目标 (-55,73,28)
            // 被 vine 包裹, 整场零 [OccluderClear], 左键一次没按 (准星被藤挡,
            // isLookingAt 恒 false), 1 分钟打不满黑名单 4 次。补一条准星口径
            // (VISUAL=outline 形状, 与游戏准星同源) 的射线: COLLIDER 说"畅通"而
            // 准星实际被挡的 climbable/装饰可挖方块 (藤/花/雪片) 按挡块处理。
            // 严格增量: COLLIDER 已命中的实心挡块 (树叶等) 走原路径, 行为不变。
            if (occ == null) {
                BlockHitResult vhit = mod.getWorld().raycast(new RaycastContext(eye, center,
                        RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
                if (vhit.getType() == HitResult.Type.BLOCK) {
                    BlockPos vfound = vhit.getBlockPos();
                    if (!vfound.equals(_pos)) {
                        BlockState vstate = mod.getWorld().getBlockState(vfound);
                        if (!vstate.isAir() && vstate.getHardness(mod.getWorld(), vfound) >= 0) {
                            occ = vfound;
                            _occluderTarget = occ;
                            _occNoCollision = true;
                            _occAim = vhit.getPos();
                        }
                    }
                }
            }
            if (occ == null) return false;
        }
        // 无碰撞挡块 (藤蔓类): COLLIDER 口径的 getReach 必 miss (射线穿它而过),
        // 不能因 rotation 缺失放弃; 瞄准点每 tick 用 VISUAL 射线刷新 (藤蔓断面
        // 随附着面变化, 固定旧点可能脱靶)。VISUAL 现在命中的已不是 latched 挡块
        // (挡块还在但被更近的东西挡住等罕见情形) → 退回挡块中心, latch 仍锁原块。
        if (_occNoCollision) {
            BlockHitResult vhit = mod.getWorld().raycast(new RaycastContext(eye, center,
                    RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
            _occAim = (vhit.getType() == HitResult.Type.BLOCK && vhit.getBlockPos().equals(occ))
                    ? vhit.getPos()
                    : Vec3d.ofCenter(occ);
        }
        Optional<Rotation> occReach = LookHelper.getReach(occ);
        if (occReach.isEmpty() && !_occNoCollision) {
            _occluderTarget = null;
            _occNoCollision = false;
            _occAim = null;
            return false; // 挡块自己也够不着 → 交回 handoff
        }
        // (20260916) 撸叶与 handoff 的互斥已上移到方法开头: handoff 期间本分支整体
        // 让位, 不再走到这里。原"挡块优先拆 handoff"方向在台阶地形产生 1.1s 死循环,
        // 见方法开头注释。
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        if (!LookHelper.isLookingAt(mod, occ)) {
            if (occReach.isPresent()) {
                LookHelper.lookAt(mod, occReach.get());
            } else {
                LookHelper.lookAt(mod, _occAim);
            }
        }
        if (LookHelper.isLookingAt(mod, occ)) {
            // 与常规挖掘分支同款: 左键由 PlayerInteractionFixChain 配工具。
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        }
        if (!occ.equals(_lastOccluderLogged)) {
            _lastOccluderLogged = occ;
            Debug.logToFile("altoclef_diag.log", "[OccluderClear] clearing " + occ.toShortString()
                    + " (" + mod.getWorld().getBlockState(occ).getBlock()
                    + (_occNoCollision ? ", visual/no-collision" : "") + ") blocking target="
                    + _pos.toShortString());
        }
        setDebugState("Clearing occluder " + occ.toShortString() + " in front of " + _pos.toShortString());
        return true;
    }

    private boolean tryPillarUpToReach(AltoClef mod) {
        // (2026-09-14 交还 baritone) 已经让位给 baritone 的垂直搭高: 期间不要抢
        // 输入通道, 只盯着"够到了没 / 超时没"。抢回去 = 把它的 pillar 腰斩。
        if (_pillarHandoff) {
            return tickBaritoneHandoff(mod);
        }
        // (2026-09-15 删自写垫塔, 用户"baritone 设定挖的目标后应该自己会解决靠近;
        // 垫塔当初是为头顶海洋写的, 为什么变成通用搭高") 自写 PillarJumpHelper 垫塔
        // 09-14 (f697d790) 引入后连打 6+ 补丁仍与 baritone 打架 (现场 20260915-180535:
        // 撸叶/自写垫塔/handoff 三套机制互相抢输入, 18s 零进展)。baritone 的
        // MovementPillar 本来就会挖挡路块+放脚下爬升, 靠近与爬升全交它; PillarJumpHelper
        // 只保留在 GoToSurfaceTask 的海洋逃生场景 (它的诞生地)。
        // ① 只处理"目标方块整体在眼睛及上方"。水平远处的目标是导航问题 ——
        //    垫高只会让水平距离更远, 人也不会为了够 20 格外的方块先垫个塔。
        if (!targetAboveEye(mod)) {
            return false;
        }
        // ② 水平已在身边 (<=2 格) 才交爬升 —— 与 closeEnoughToJudge 的 horizontallyInPlace
        //    同一判据源。否则"头顶上方 + 水平 50 格"这类目标会误触发。
        if (horizontalDistanceSqToPlayer(mod) > 4.0) {
            return false;
        }
        // ③ (2026-09-15 反应式补料, 用户"方块需求量不好求, 容易错误触发") 这里
        //    不再预判需要几块 —— 层差猜多了白挖一堆泥、猜少了照样料尽。改为让
        //    baritone 先上: 有多少块就先垫多少, 真上不去时由 tickBaritoneHandoff
        //    的 stall 检测反应式补一次料, 再不行就拉黑放弃 (09-03 地基)。
        // ③b 头顶被【挖不动】的方块 (基岩类) 挡死 → baritone 爬不上去, 直接判不可达,
        //    省掉整轮 handoff。可挖挡头 (树叶/泥土) 不拦 —— baritone 自己会挖。
        if (headroomBlockedForPillar(mod, mod.getPlayer().getBlockPos())
                && !isBlockerDiggable(mod, mod.getPlayer().getBlockPos().up(2))) {
            Debug.logToFile("altoclef_diag.log", "[PillarHandoff] skip: headroom UNBREAKABLE at "
                    + mod.getPlayer().getBlockPos().up(2).toShortString()
                    + " target=" + _pos.toShortString() + " -> unreachable");
            // (2026-09-15 立即拉黑) baritone 爬不上去是判死刑的强信号, 默认阈值 4
            // 要 5 轮才拉黑, 每轮 10-15s 纯浪费 (现场 224849 同目标重试 4 轮)。
            mod.getBlockTracker().requestBlockUnreachable(_pos, 1);
            return true;
        }
        startBaritoneHandoff(mod, mod.getPlayer().getBlockPos(), "target above eye, out of reach");
        return true;
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
        BlockState st = mod.getWorld().getBlockState(aboveHead);
        if (st.isAir()) return false;
        if (WorldHelper.isSolid(mod, aboveHead)) return true;
        // (2026-09-14 v3 碰撞≠实心) 树叶/半砖/灯笼/台阶一类: isSolidBlock() 返回
        // false 但【有碰撞箱】, 起跳照样被顶掉。现场 20260914-193231 实锤: 目标是
        // 树上的 acacia_log(1,114,4), 头顶 (1,112,4) 判定 solid=false 放行 → 跳起来
        // 撞树叶 → 跳跃幅度只有 0.95 格 (NAV-STUCK yRange) → 脚块 BlockPos 永不变 →
        // PillarJumpHelper 的放置窗口 (需要 below 变空气) 永不打开 → 只 hold JUMP
        // 空跳 5s, noProgress 放弃 → 重入, 连着三轮。
        // 判据必须落到"起跳路径上有没有碰撞", 不能停在"是不是实心方块"
        // (本仓"轮廓≠碰撞"铁律, 见 detail/03-consistency.md)。
        return !st.getCollisionShape(mod.getWorld(), aboveHead).isEmpty();
    }

    /**
     * (2026-09-14 v4 交还 baritone) 头顶挡头能不能被挖掉。
     *
     * 只有挖不动的 (hardness < 0: 基岩/末地传送门框架/屏障) 才是真·"垫不到"。
     * 树叶、木头、土、半砖、灯笼全都可挖 —— 而 baritone 的 MovementPillar 会把
     * 头顶那格 ({@code start.above(2)}) 列进 positionsToBreak, 挖完再放脚下,
     * 本来就是"挖一垫一"。判成不可达会把整棵树的目标逐个拉黑。
     */
    private boolean isBlockerDiggable(AltoClef mod, BlockPos pos) {
        BlockState st = mod.getWorld().getBlockState(pos);
        if (st.isAir()) return true;
        return st.getHardness(mod.getWorld(), pos) >= 0;
    }

    /**
     * (2026-09-14 v4 交还 baritone 垂直搭高) 自写 PillarJumpHelper 只放不挖,
     * 遇到挡头只会空跳到超时; 而 baritone 会挖。让位: 挂 GoalYLevel(目标层),
     * 由它的 A* 自己选 MovementPillar (挖 up(2) + 放脚下) 爬上去。
     *
     * (2026-09-15 GoalYLevel → GoalNear(pos,2), 用户"这不是回表面任务, GoalYLevel
     * 纯 Y 目标也是错的") GoalYLevel 是 GoToSurfaceTask 的遗产 —— 那里只关心
     * "到某一层", 在哪根柱子爬都行。挖目标的 handoff 关心的是爬到【目标旁边】。
     * 现场 20260915-224849: baritone 挑了 8 格外的柱子爬 (树冠下净空差, 它选了
     * 好爬的), 两次爬到 y=79 都是假 done —— 目标不在手边, 挖掘进度 -12, wander
     * 挪走重来, 全场零产出。GoalNear(pos,2) 允许脚站在目标邻格/下一层 (含斜角),
     * 对树干旁的可站立格可满足; 即便个别几何不可满足, 现在 stall 4s + 总窗口
     * 12s + 立即拉黑已把最坏情况封顶, 不再是 09-14 那个 26s 冻结的无底洞。
     */
    private void startBaritoneHandoff(AltoClef mod, BlockPos feet, String reason) {
        endPillar(mod);
        if (!_pillarHandoff) {
            _pillarHandoff = true;
            _pillarHandoffTried = true;
            _pillarHandoffLastY = mod.getPlayer().getBlockPos().getY();
            _pillarHandoffLastX = mod.getPlayer().getBlockPos().getX();
            _pillarHandoffLastZ = mod.getPlayer().getBlockPos().getZ();
            _pillarHandoffTimer.reset();
            _pillarHandoffStallTimer.reset();
            Debug.logToFile("altoclef_diag.log", "[PillarHandoff] start target="
                    + _pos.toShortString()
                    + " from y=" + _pillarHandoffLastY
                    + " blockedBy=" + mod.getWorld().getBlockState(feet.up(2)).getBlock()
                    + " (" + reason + ") -> GoalNear(" + _pos.toShortString() + ", 2)");
        }
        // 与自写垫塔同款: builder 不会自己停, 占着输入通道会让 baritone 的
        // pillar 动不了 (现场 20260914-115322)。
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_pos, 2));
    }

    /**
     * (2026-09-14 v4) 交还期间每 tick 只做三件事, **绝不动输入** —— 抢回去等于
     * 把 baritone 正在执行的 pillar 腰斩 (与 GoToSurfaceTask 踩过的同一个坑):
     *   ① 够到了 (目标不在眼睛上方) 或已到目标层 → 收工, 交回常规挖掘;
     *   ② y 有上升 → 续命 (baritone 搭高每格 1.5s 上下, 别用固定窗口砍它);
     *   ③ goal 掉线 → 重挂。
     */
    private boolean tickBaritoneHandoff(AltoClef mod) {
        int y = mod.getPlayer().getBlockPos().getY();
        // (2026-09-15 落地门控) done 判定只在【落地时】做。现场 20260915-160447:
        // bot 站 y=69 头顶橡树叶 → 交还 baritone GoalYLevel(70), baritone 起跳
        // 那一瞬 blockPos 还是 69 但真实 Y 已过 69.38 → eyeY>71 →
        // targetAboveEye 瞬间翻 false → 这里误判 "done y=69" 腰斩它的起跳 →
        // 落地重入 → 1.4s 一轮 start/done 死循环 8 轮 (diag.log 16:06:31-52)。
        // 与垫塔 v2 "判据滞后"同病: 腾空期间的即时位置一律不可信, 视为"进行中"。
        // (2026-09-15 GoalNear) 爬到目标 2 格内可能还差半层 (y=78 贴着 79 的目标),
        // 此时 y<目标层 但挖掘距离已到 —— reach 判定补上这个口, 否则 baritone 停在
        // 已满足的 goal 上, y 不再涨 → 4s 后被 STALL 误杀拉黑。
        if (mod.getPlayer().isOnGround()
                && (!targetAboveEye(mod) || y >= _pos.getY() || withinMiningReach(mod))) {
            Debug.logToFile("altoclef_diag.log", "[PillarHandoff] done y=" + y
                    + " target=" + _pos.toShortString() + " -> back to normal mining");
            _pillarHandoff = false;
            endPillar(mod);
            return false;
        }
        if (y > _pillarHandoffLastY) {
            _pillarHandoffLastY = y;
            _pillarHandoffTimer.reset();
            _pillarHandoffStallTimer.reset();
        }
        // (2026-09-15 GoalNear 进场) 走向目标的水平位移也算进度 (见字段注释);
        // 原地跳 XZ 不变、y 不创新高, 两条都拦不住它 —— 该抓的照抓。
        BlockPos _cur = mod.getPlayer().getBlockPos();
        if (_cur.getX() != _pillarHandoffLastX || _cur.getZ() != _pillarHandoffLastZ) {
            _pillarHandoffLastX = _cur.getX();
            _pillarHandoffLastZ = _cur.getZ();
            _pillarHandoffStallTimer.reset();
        }
        // (2026-09-15 反应式补料 + 恢复 09-03 "放弃"地基)
        // baritone 交还期间高度不再上涨 = 真上不去, 分两种情况:
        //   · 背包一块垫料都没有 且 本实例还没补过 → 补一次 (固定 5 块), 再爬一轮;
        //   · 已经补过 / 其实有料却爬不上去 → 按 09-03 的地基直接拉黑换目标。
        // 旧逻辑此时只能干等 20s 总超时, 期间表现为"反复跳跃止不住"。
        if (_pillarHandoffStallTimer.elapsed()) {
            int have = PillarJumpHelper.countMaterials(mod, _pillarMaterials);
            // baritone 的放置总闸 hasThrowaway 依赖它; pause 泄漏时症状 = 只跳不放。
            boolean paused = mod.getExtraBaritoneSettings().isInteractionPaused();
            Debug.logToFile("altoclef_diag.log", "[PillarHandoff] STALL y=" + y
                    + " lastY=" + _pillarHandoffLastY
                    + " have=" + have + " paused=" + paused
                    + " acquired=" + _acquireStarted
                    + " target=" + _pos.toShortString());
            if (!_acquireStarted && have == 0) {
                Task acquire = startAcquireMaterials(mod);
                if (acquire != null) {
                    Debug.logToFile("altoclef_diag.log", "[PillarHandoff] -> acquire "
                            + _acquireWant + " dirt then retry");
                    return true; // 下一 tick 由 pumpAcquireMaterials keep-alive
                }
            }
            // (2026-09-15 GoalNear) baritone 已把 bot 带进目标挖掘距离 (爬到邻格),
            // 只是视线被树叶挡/差半格 —— 收工回常规路径 (撸叶/正常挖), 不拉黑。
            // 拉黑了反而把一个本来够得着的目标判死。
            if (withinMiningReach(mod)) {
                Debug.logToFile("altoclef_diag.log", "[PillarHandoff] climb delivered reach, "
                        + "back to normal mining target=" + _pos.toShortString());
                _pillarHandoff = false;
                endPillar(mod);
                return true;
            }
            Debug.logToFile("altoclef_diag.log", "[PillarHandoff] give up: "
                    + (_acquireStarted ? "already acquired once" : "has materials but cannot climb")
                    + " target=" + _pos.toShortString() + " -> unreachable");
            _pillarHandoff = false;
            endPillar(mod);
            // (2026-09-15 立即拉黑) 同上: baritone 爬不上去一次即判死刑, 不给
            // 默认阈值 4 留 4 轮每轮 10-15s 的重试浪费。
            mod.getBlockTracker().requestBlockUnreachable(_pos, 1);
            return true;
        }
        if (_pillarHandoffTimer.elapsed()) {
            // (2026-09-15 GoalNear) 超时同理: 已进挖掘距离就不拉黑, 交回常规挖掘。
            if (withinMiningReach(mod)) {
                Debug.logToFile("altoclef_diag.log", "[PillarHandoff] timeout at y=" + y
                        + " but within reach target=" + _pos.toShortString()
                        + " -> back to normal mining");
                _pillarHandoff = false;
                endPillar(mod);
                return true;
            }
            Debug.logToFile("altoclef_diag.log", "[PillarHandoff] timeout 20s at y=" + y
                    + " lastY=" + _pillarHandoffLastY
                    + " target=" + _pos.toShortString() + " -> unreachable");
            _pillarHandoff = false;
            endPillar(mod);
            mod.getBlockTracker().requestBlockUnreachable(_pos, 1);
            return true;
        }
        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(_pos, 2));
        }
        setDebugState("Baritone pillar to " + _pos.toShortString());
        return true;
    }

    /**
     * 派采集垫块子任务 (实例缓存, 只建一次)。
     *
     * @return 子任务实例 (keep-alive); null = 建不出来 (目录里没有 / 已达尝试上限)。
     */
    private Task startAcquireMaterials(AltoClef mod) {
        // (2026-09-15 反应式补料) 不再按层差猜需求量 —— 固定补一小撮够再试一轮。
        // 真不够时会再次 stall, 那时 _acquireStarted 已 true → 走放弃。
        _acquireWant = ACQUIRE_MATERIALS_COUNT;
        Task t = TaskCatalogue.getItemTask(Items.DIRT, _acquireWant);
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
                + " want=" + _acquireWant + " X " + Items.DIRT
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
        // (2026-09-15 口径改"够爬") 早停量 = _acquireWant (保底 3, 目标层差封顶 10);
        // PillarJumpHelper.countMaterials 直读 inventory.main/offHand, 同为真实计数,
        // 圆石等其他垫块也计入 (bot 有 5 块圆石时别再逼它挖第 6 块泥)。
        int haveMaterials = PillarJumpHelper.countMaterials(mod, _pillarMaterials);
        if (haveMaterials >= _acquireWant
                || mod.getItemStorage().getItemCountReal(Items.DIRT) >= _acquireWant) {
            logAcquireDone(mod, "enough(materials=" + haveMaterials + ")");
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
        // (2026-09-15 handoff 残留目标) handoff 退出(done/超时/give up)必须连
        // baritone 的 customGoal 一起放掉。现场 20260915-224849 末尾: give up 后
        // 只清了 _pillarHandoff 标志, GoalYLevel(79) 还挂在 customGoalProcess 上
        // → baritone 原地跳 19s (bIn jump=true); 更糟的是导航分支见 "isActive()"
        // 就不设新目标, bot 永远走不到目标正下方 → 水平 8 格不满足"到位"前提 →
        // 2s 放弃计数每 tick 清零, 闸门永不触发, 死锁闭环。
        // startBaritoneHandoff 首行也调本方法, 但其后紧跟 setGoalAndPath, 无空窗。
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        // (2026-09-14 v2) 基准伴生于"正在垫塔", 收工即清空 —— 残留会让下一次
        // 垫塔准入错位到上一次的落脚层。
        _pillarBaseFeet = null;
        if (!_pillarActive) return;
        _pillarActive = false;
        PillarJumpHelper.release(mod);
    }

    /** 目标方块中心到玩家的水平 (XZ) 距离平方。 */
    /** (2026-09-15 GoalNear 配套) 眼睛到目标中心是否已在挖掘距离内 (与
     * tryClearOccluder 的 4.5 门控同源 —— 同一概念只留一个判据源)。 */
    private boolean withinMiningReach(AltoClef mod) {
        return mod.getPlayer().getEyePos().distanceTo(Vec3d.ofCenter(_pos)) <= 4.5;
    }

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
