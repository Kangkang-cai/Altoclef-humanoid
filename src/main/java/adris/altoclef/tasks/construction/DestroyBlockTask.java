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
import adris.altoclef.util.helpers.AscentHandoffHelper;
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

    // ==================== (2026-09-14 垫塔接近 → 2026-09-17 统一) ====================
    // 用户推翻 2026-09-03 的"够不到直接放弃, 不做搭方块垫脚", 明确要求
    // "搭方块垫脚上去够" (原话: "怎么没触发搭过去靠近")。
    //
    // 现场 20260914-113531: bot 站在 (88.50, 63.00, 0.79), feet y=63 → 眼 y=64.62;
    // 目标悬空原木 (89/90,70,2~3) 下表面 y=70 → 眼到方块 5.38 格 (> 4.5 真实
    // blockReachDistance), 用户现场确认"那个方块确实够不到"; 3D 距离 7.55。
    // 用户确认现场形态: 树干是 bot 自己挖空的 —— memory/selfdug.log 有据。
    //
    // 三轮演进(别再重走):
    //   09-14 f697d790  自写 PillarJumpHelper 垫塔。连打 6+ 补丁仍与 baritone 打架
    //                   (20260915-180535: 撸叶/自写垫塔/handoff 三套抢输入, 18s 零进展)。
    //   09-15           删自写垫塔。用户原话: "baritone 设定挖的目标后应该自己会解决
    //                   靠近; 垫塔当初是为头顶海洋写的, 为什么变成通用搭高"。baritone
    //                   的 MovementPillar 本来就是"挖头顶 up(2) + 放脚下"(反编译实锤),
    //                   干竖井/干口袋它完全能上; PillarJumpHelper 只保留在 GoToSurfaceTask
    //                   的海洋逃生场景(它的诞生地)。
    //   09-17           爬升状态机整段抽出为 AscentHandoffHelper 与 InteractWithBlockTask
    //                   共用 —— 现场 20260917-143636 实锤: "去工作台右键"要走同一段竖直
    //                   爬升, 却因为补料逻辑只长在本类里, 在自挖竖井底原地跳 33.2s 零产出。
    //                   本类此后只负责"准入判据"与"挖掘特有的完成条件", 爬不上去的
    //                   观察/补救/判负全部交协调器。
    //
    // ⚠ (2026-09-14 用户约束) 垫料清单 **不含原木** —— 原木是挖树任务结算的目标物,
    // 拿它垫 1 格就等于木料 -1。用户原话"别用原木, 木板可以"。缺料时由协调器
    // 反应式补一次 (挖 5 格泥土), 绝不动原木。
    private static final Item[] _pillarMaterials = PillarJumpHelper.withWoodNoLogs();

    /**
     * (2026-09-17 统一) 垂直爬升协调器。三档: 1.5s 无进展 → 重挂 baritone 导航
     * (零成本, 能挖的挡块它自己会处理) → 再 1.5s 无垫料则补一次 → 再不行判不可达。
     * 本类不再自己管 stall 计时/补料子任务/交还基准采样 —— 那些都在协调器里,
     * 与其他消费方同一份口径。
     */
    private final AscentHandoffHelper _ascent =
            new AscentHandoffHelper("destroy", _pillarMaterials);

    /**
     * (2026-09-17) 协调器本 tick 派出的补料子任务出口。
     *
     * `tryPillarUpToReach` 用 boolean 表达"已接管", 子任务只能靠这个字段带出来 ——
     * 调用方(主循环)必须原样返回它, 否则采集任务每 tick 被重建 (本仓"侧线子任务
     * 实例缓存 keep-alive"铁律)。
     */
    private Task _ascentChild = null;
    // (2026-09-14 v4 交还 baritone → 2026-09-17 交协调器) 让位期间本类【绝不动输入】,
    // 只做两件事: ① 挖掘特有的完成判定 (够了到了 / reach 到位) ② 把协调器的判负
    // 结果转成"拉黑换目标"。stall 计时、重挂导航、补料、采样基准全在
    // AscentHandoffHelper —— 本类不再持有任何 handoff 状态字段。
    //
    // 为什么当初要交还: 头顶被【可挖】方块挡住 (树叶/土/木/半砖…) 时, 自写
    // PillarJumpHelper 只会 hold(JUMP) 空跳 —— 它不挖头顶, 而"脚块变空气"的放置
    // 窗口永远开不了 ⇒ 空跳到超时。baritone 的 MovementPillar 构造是
    // positionsToBreak={start.above(2)} + positionToPlace=start(反编译实锤),
    // 本来就是"挖头顶 + 放脚下"。用户原话: "baritone 会处理悬空方块"。

    // ---- (2026-09-14 缺垫块 → 去挖几格 → 2026-09-17 上移协调器) ----
    // 历史: 09-14 在 tryPillarUpToReach 里"预判没垫块就派采集(挖 3 格泥土)", 死路一条
    // (现场 20260914-115322: bot 只有原木, 按约束不算垫块 → 落回对"站立面之上 N 格"
    // 的不可满足导航 → 冻结)。09-15 改成"让 baritone 先上, 真上不去时 stall 反应式
    // 补一次"—— 用户拍板理由: "方块需求量不好求, 容易错误触发"。
    // 09-17 这套状态机(含采集子任务 keep-alive、早停口径、超时)整体移入
    // AscentHandoffHelper, "反应式补一次"的语义不变, 只是不再由本类持有。

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
        // (2026-09-17 统一) 爬升状态随实例归零: 新目标不允许沿用上一次的 GoalNear /
        // 采样基准 (残留会让新目标一上来就被送去爬上一次的位置)。协调器 stop 内部
        // 已含 onLostControl + 松键, 这里不用再重复那些动作。
        _ascent.stop(mod);
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
        // (2026-09-15 删自写垫塔 / 2026-09-17 交协调器) 让位期间豁免卡死检测:
        // baritone 挖挡块/走位阶段可能 >6s 零位移, 卡死由协调器自己的 1.5s 档兜底,
        // 不劳 moveChecker 在这里插一脚。
        if (!_ascent.isActive() && !_moveChecker.check(mod) && _tryToMineTimer.elapsed()) {
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
        // (2026-09-17 让位期间不评瞬时门) 起跳瞬间眼睛抬高 ~1.25 格, reach/眼距会短暂
        // 达标 → 下面 L349 的 `_ascent.stop()` 立刻拆掉正在爬升的 baritone → 落地重入
        // = "起跳 → 拆 → 退出 → 重挂" 横跳(本仓 09-15 叶包裹原木注释 + 今天 150146
        // 两段横跳同款)。让位期间一律不采信**腾空瞬间**的空间判据, 只在落地时才认
        // (与 tickAscentHandoff 的完成判定同口径: 腾空期间的即时位置一律不可信)。
        if (_ascent.isActive() && !mod.getPlayer().isOnGround()) {
            reach = Optional.empty();
        }
        if (reach.isPresent()) {
            // (2026-09-14 垫塔接近 / 2026-09-17 统一) 够到了 → 收工并释放 baritone
            // 目标 + 松开可能残留的按键。协调器 stop 是幂等的, 未启动时无副作用。
            _ascent.stop(mod);
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
            // 18s 一格不升 (起跳瞬间 eyeY 越过 80 → reach 短暂出现 → 让位收工 →
            // 落地重入)。人对"手边被树叶包住的原木"不会先垫塔, 会先把叶子撸掉。
            // 只有目标超出挖掘距离 (真够不着) 或挡块挖不动时才落回垫塔/放弃路径。
            if (tryClearOccluder(mod)) {
                _gettingToBlockTick = 0;
                return null;
            }

            // (2026-09-17 统一) 补料子任务的 keep-alive 已并入协调器 —— 它会在下面
            // tryPillarUpToReach 的 tick 里被驱动并原样返回, 这里不再单独接一手。

            // (2026-09-14 垫塔接近) 目标在眼睛及上方 + reach 够不到 + 背包有垫块
            // → 垫方块升上去够 (用户"搭方块垫脚上去够", 推翻 09-03 的放弃决定)。
            // 垫塔接管时本 tick 直接返回, 且不累计下面的"够不着"计数 —— 垫塔本身
            // 要跑好几秒, 累计会让它在半路被放弃闸门打断。
            // (2026-09-14 垫塔接近 / 2026-09-17 统一协调器) 目标在眼睛及上方 + reach
            // 够不到 → 交协调器让 baritone 爬升。接管时本 tick 直接返回, 且不累计
            // 下面的"够不着"计数 —— 爬升本身要跑好几秒, 累计会让它半路被放弃闸门打断。
            if (tryPillarUpToReach(mod)) {
                _gettingToBlockTick = 0;
                Task child = _ascentChild;
                _ascentChild = null;
                // 非 null = 协调器派的补料子任务, 必须原样交回框架 (keep-alive)。
                return child;
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

    // (2026-09-17) "目标在挖掘距离内"的双阈值滞回闩锁 (进 4.5 / 出 6.0)。
    // 详见 tryClearOccluder 里 20260917-150146 的现场注释: 原判据是瞬时的, 跳跃让
    // 眼距在 4.88 ↔ 3.96 之间横跳 → 每 400ms 一次"选中挡块 ↔ 清 latch", 挖掘进度
    // 反复归零。铁律: 卡死/放弃类判据不得被腾空瞬间的采样翻转。
    private boolean _occInRange = false;

    // (2026-09-16 藤蔓裹木) 当前挡块是否为无碰撞方块 (藤蔓/花/雪片一类 climbable
    // 或装饰块)。这类方块 COLLIDER 射线必穿透 → LookHelper.getReach 必 miss (该
    // 方法走 baritone `RotationUtils.reachable` → `RayTraceUtils.rayTraceTowards`,
    // 20260917 反编译实锤用的是 **OUTLINE**, 不是 COLLIDER) → 不能走常规 rotation
    // 瞄准, 改瞄准星口径 (OUTLINE) 命中点。
    private boolean _occNoCollision = false;
    /** 无碰撞挡块的本 tick 瞄准点 (OUTLINE 射线命中位置), 每 tick 刷新。 */
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
        // (20260916 1330 场次 死循环修复 / 2026-09-17 交协调器) 让位期间【完全让位】:
        // 台阶地形 bot 随 baritone 起跳/落步上下漂移, 眼-目标瞬时距离会在腾空瞬间
        // 假达标(≤4.5) → 本分支拆掉让位抢输入; 下一 tick 落回低台阶又退出 →
        // "起跳 → 拆 → 退出 → 重挂" 1.1s 死循环 25+ 轮零产出。互斥方向: 让位优先。
        // baritone 的 MovementPillar 本来就挖一垫一, 路径挡块它自己处理; 协调器按
        // 自身体系退出(1.5s 重挂 / 补料 / 判不可达)后本分支自然恢复。
        if (_ascent.isActive()) {
            return false;
        }
        Vec3d eye = mod.getPlayer().getEyePos();
        Vec3d center = Vec3d.ofCenter(_pos);
        // (2026-09-17 眼距门横跳 = 双阈值滞回) 原判据 `eye.distanceTo(center) > 4.5 → 放弃`.
        // 只处理"目标本体已在挖掘距离内"的情形 —— 超出 reach 的目标是导航/爬升问题,
        // 隔空够不着的挡块挖了也是白挖 (射线终点到不了目标附近)。
        //
        // 现场 20260917-150146 实锤它是个**瞬时门**: bot 站 (-85.61, ·, -81.46), 目标中心
        // (-85.5, 66.5, -78.5) —— 站地 feet=61.0/eye=62.62 → **4.88 > 4.5**(不挖, 且清 latch);
        // 起跳近顶点 feet≈62.25/eye=63.87 → **3.96 < 4.5**(开挖)。跳跃周期 ~0.4s, 于是
        // `Getting to block...` ↔ `Clearing occluder` 每 400ms 交替 15 秒, 每次落地
        // L596 还把 `_occluderTarget` 清空 → **挖掘进度反复归零, 一格土都挖不掉**。
        //
        // 修: 进 4.5 / 出 6.0 的双阈值滞回 —— 一旦开始挖挡块就锁住, 直到挡块真的变空气
        // (原有 latch 语义 L590-599 负责换下一片) 或目标真的走远(>6.0)。跳跃 1.25 格的
        // 越界幅度落在滞回带内, 不再能翻转判据; 而 30 格外的目标依旧进不来(站地就 >6.0)。
        boolean inEnterRange = eye.distanceTo(center) <= 4.5;
        boolean inExitRange = eye.distanceTo(center) <= 6.0;
        if (_occInRange) {
            if (!inExitRange) _occInRange = false;
        } else if (inEnterRange) {
            _occInRange = true;
        }
        if (!_occInRange) {
            _occluderTarget = null;
            return false;
        }
        // latch: 锁定的挡块仍实心可挖 → 继续挖它, 不重选; 变空气/挖不动才重新找。
        BlockPos occ = _occluderTarget;
        if (occ != null) {
            BlockState latched = mod.getWorld().getBlockState(occ);
            if (latched.isAir() || latched.getHardness(mod.getWorld(), occ) < 0
                    || neverOccluder(mod, occ)) {
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
                    if (!state.isAir() && state.getHardness(mod.getWorld(), found) >= 0
                            && !neverOccluder(mod, found)) {
                        occ = found;
                        _occluderTarget = occ;
                        _occNoCollision = false;
                        _occAim = null;
                    } else if (neverOccluder(mod, found)) {
                        logRefusedContainer(mod, found);
                    }
                }
            }
            // (2026-09-16 藤蔓裹木 / 2026-09-17 重写) COLLIDER 射线对藤蔓穿透 (藤蔓无
            // 碰撞箱) → 判 "无遮挡", 撸藤分支永不触发。现场 20260916-140459: 目标
            // (-55,73,28) 被 vine 包裹, 整场零 [OccluderClear], 左键一次没按 (准星被
            // 藤挡, isLookingAt 恒 false), 1 分钟打不满黑名单 4 次。补一条准星口径的
            // 射线: COLLIDER 说"畅通"而准星实际被挡的 climbable/装饰可挖方块 (藤/花/
            // 雪片) 按挡块处理。
            //
            // ⚠ (2026-09-17 修正 — 09-16 那版挑错了枚举, 补丁是死代码) 原版写
            // `ShapeType.VISUAL` 并注释 "VISUAL=outline 形状, 与游戏准星同源" ——
            // **语义正好写反了**。反编译实锤 (MC 1.18.2):
            //   COLLIDER → AbstractBlockState.getCollisionShape (= collidable ? outline : empty)
            //   OUTLINE  → AbstractBlockState.getOutlineShape   ← 准星口径
            //   VISUAL   → AbstractBlockState.getCameraCollisionShape, 而 AbstractBlock
            //              的该方法是 `return getCollisionShape(...)` —— 直接转发碰撞箱
            // 即: 对任何 collidable=false 的软方块 (藤/草/花/作物/雪片), **VISUAL 与
            // COLLIDER 完全等价, 都是 VoxelShapes.empty(), 射线照旧穿透**。证据: 修复
            // 上线后 `visual/no-collision` 这个唯一命中标记在全部历史日志中出现 0 次 =
            // 该分支从未选中过任何方块。藤蔓有 outline、无碰撞箱, 只有 OUTLINE 看得见。
            // 现场 20260917-101125 复核: 藤蔓挡在 bot 与目标原木之间, 两条射线双双穿透
            // → occ 恒 null → 本方法恒 false → 左键永不按下, 而外层 reach(OUTLINE)
            // 恒空 → 每 4s 白交接一次 baritone 顺藤爬 ("抱着藤蔓上下爬")。
            // 严格增量: COLLIDER 已命中的实心挡块 (树叶等) 走原路径, 行为不变。
            if (occ == null) {
                BlockHitResult vhit = mod.getWorld().raycast(new RaycastContext(eye, center,
                        RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
                if (vhit.getType() == HitResult.Type.BLOCK) {
                    BlockPos vfound = vhit.getBlockPos();
                    if (!vfound.equals(_pos)) {
                        BlockState vstate = mod.getWorld().getBlockState(vfound);
                        if (!vstate.isAir() && vstate.getHardness(mod.getWorld(), vfound) >= 0
                                && !neverOccluder(mod, vfound)) {
                            occ = vfound;
                            _occluderTarget = occ;
                            _occNoCollision = true;
                            _occAim = vhit.getPos();
                        } else if (neverOccluder(mod, vfound)) {
                            logRefusedContainer(mod, vfound);
                        }
                    }
                }
            }
            if (occ == null) return false;
        }
        // 无碰撞挡块 (藤蔓类): COLLIDER 口径的 getReach 必 miss (射线穿它而过),
        // 不能因 rotation 缺失放弃; 瞄准点每 tick 用 OUTLINE 射线刷新 (藤蔓断面
        // 随附着面变化, 固定旧点可能脱靶)。OUTLINE 现在命中的已不是 latched 挡块
        // (挡块还在但被更近的东西挡住等罕见情形) → 退回挡块中心, latch 仍锁原块。
        // ⚠ 这里同样必须是 OUTLINE —— 与准星 (crosshairTarget) 同口径, 也是
        // `isLookingAt(occ)` 用来判"能不能出刀"的那条; 09-16 的 VISUAL 版对软方块
        // 等于碰撞箱口径 → 恒 miss, 目标点只能退化到方块中心 (见上方 2026-09-17 修正)。
        if (_occNoCollision) {
            BlockHitResult vhit = mod.getWorld().raycast(new RaycastContext(eye, center,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
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
                    + (_occNoCollision ? ", outline/no-collision" : "") + ") blocking target="
                    + _pos.toShortString());
        }
        setDebugState("Clearing occluder " + occ.toShortString() + " in front of " + _pos.toShortString());
        return true;
    }

    // (2026-09-17 拆自己熔炉修复) "refused container" 验证日志节流戳。
    private long _lastRefuseContainerLogMs = 0;

    /**
     * (2026-09-17 拆自己熔炉修复) 方块"永不作清遮挡挡块"的判据 —— 带 BlockEntity 的方块
     * (熔炉/箱子/漏斗/告示牌/箱子类…) 一律跳过:
     *  ① 里面可能装着我们的资产, 撸掉就地撒落 —— 掉进岩浆 / 滚下山 / 被别的生物捡走即净损失;
     *  ② 这类方块本来就是"要交互"的对象, 不是"要挖"的障碍。
     * 口径与 {@link adris.altoclef.util.helpers.JumpObstructionHelper} 的挖障跳过同一先例
     * (`mod.getWorld().getBlockEntity(pos) != null` → 箱子/熔炉等跳过), 不再各写一套。
     *
     * 现场 20260917-155653 16:09 实锤(自己拆自己的炉子):
     *   bot 往 `-59,25,-31` 的熔炉里投了 `raw_iron x9`(16:09:32), 17s 后为开炉
     *   (InteractWithBlockTask 的 "Clearing block above interaction target") 去挖它上方的
     *   `-59,26,-31`, 本类把**这座熔炉自己**选成了挡块并真撸掉:
     *   `[OccluderClear] clearing -59, 25, -31 (Block{minecraft:furnace}) blocking target=-59, 26, -31`
     *   → 2.1s 后 `[SmeltFurnaceGone] -59,25,-31 vanished with mat=raw_iron x9 -> recovering drops`。
     *   这一炉的 9 块生铁是恰好被捡回来了(用户这次看到的就是它); 上一次同类事故的代价
     *   见 `[SmeltOrphan]` 家族 —— 炉内资产直接丢失。
     */
    private boolean neverOccluder(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockEntity(pos) != null;
    }

    /** (2026-09-17) 拒拆容器挡块的打点, 2s 节流, 只落盘不进聊天。 */
    private void logRefusedContainer(AltoClef mod, BlockPos pos) {
        long now = System.currentTimeMillis();
        if (now - _lastRefuseContainerLogMs <= 2000) return;
        _lastRefuseContainerLogMs = now;
        Debug.logToFile("altoclef_diag.log", "[OccluderClear] refused container "
                + pos.toShortString() + " (" + mod.getWorld().getBlockState(pos).getBlock()
                + ") blocking target=" + _pos.toShortString() + " — 不拆容器, 交回常规瞄准/放弃路径");
    }

    /**
     * (2026-09-14 垫塔接近 → 2026-09-17 统一协调器) reach 够不到时把垂直爬升交给
     * {@link AscentHandoffHelper}。
     *
     * 本方法只负责**准入判据** + **挖掘特有的完成条件**; 爬不上去之后怎么办
     * (1.5s 无进展重挂导航 / 补料 5 格 / 判不可达) 全在协调器里 —— 与
     * `InteractWithBlockTask` 共用同一份口径, 不再各写一套 (现场 20260917-143636
     * 就是"补料只长在本类里"导致的: 去工作台要走同一段竖直爬升却原地跳 33.2s)。
     *
     * @return true = 本 tick 已由让位/协调器接管 (含"挡头挖不动 → 判不可达")。
     *         子任务(补料)通过 {@link #_ascentChild} 传出。false = 不适用
     *         (目标不在上方 / 水平还没到位), 交回原导航+放弃逻辑。
     */
    private boolean tryPillarUpToReach(AltoClef mod) {
        _ascentChild = null;
        // ① 让位中 → 本类只做"挖掘特有的完成判定", 其余全交给协调器。
        if (_ascent.isActive()) {
            return tickAscentHandoff(mod);
        }
        // ② 只处理"目标方块整体在眼睛及上方"。水平远处的目标是导航问题 ——
        //    垫高只会让水平距离更远, 人也不会为了够 20 格外的方块先垫个塔。
        if (!targetAboveEye(mod)) {
            return false;
        }
        // ③ 水平已在身边 (<=2 格) 才交爬升 —— 与 closeEnoughToJudge 的
        //    horizontallyInPlace 同一判据源。否则"头顶上方 + 水平 50 格"会误触发。
        if (horizontalDistanceSqToPlayer(mod) > 4.0) {
            return false;
        }
        // ④ 头顶被【挖不动】的方块 (基岩类) 挡死 → baritone 爬不上去, 直接判不可达,
        //    省掉整轮让位。可挖挡头 (树叶/泥土) 不拦 —— baritone 自己会挖。
        //    ③ (2026-09-15) 不再预判"需要几块垫料": 用户"方块需求量不好求, 容易错误
        //    触发"。改为让 baritone 先上, 真上不去时由协调器反应式补一次。
        if (headroomBlockedForPillar(mod, mod.getPlayer().getBlockPos())
                && !isBlockerDiggable(mod, mod.getPlayer().getBlockPos().up(2))) {
            Debug.logToFile("altoclef_diag.log", "[Ascent] skip: headroom UNBREAKABLE at "
                    + mod.getPlayer().getBlockPos().up(2).toShortString()
                    + " target=" + _pos.toShortString() + " -> unreachable");
            // (2026-09-15 立即拉黑) baritone 爬不上去是判死刑的强信号, 默认阈值 4
            // 要 5 轮才拉黑, 每轮 10-15s 纯浪费 (现场 224849 同目标重试 4 轮)。
            mod.getBlockTracker().requestBlockUnreachable(_pos, 1);
            return true;
        }
        startAscentHandoff(mod);
        return true;
    }

    /** (2026-09-17) 让位中每 tick 的唯一入口: 完成判定 → 协调器裁决。 */
    private boolean tickAscentHandoff(AltoClef mod) {
        int y = mod.getPlayer().getBlockPos().getY();
        // (2026-09-15 落地门控) 完成判定只在【落地时】做。现场 20260915-160447:
        // bot 站 y=69 头顶橡树叶 → 让位 baritone, baritone 起跳那一瞬 blockPos 还是
        // 69 但真实 Y 已过 69.38 → eyeY>71 → targetAboveEye 瞬间翻 false → 这里
        // 误判 "done" 腰斩它的起跳 → 落地重入 → 1.4s 一轮 start/done 死循环 8 轮。
        // 腾空期间的即时位置一律不可信。
        // (2026-09-15 GoalNear) 爬到目标 2 格内可能还差半层 (y=78 贴着 79 的目标),
        // 此时 y<目标层 但挖掘距离已到 —— reach 判定补上这个口。
        if (mod.getPlayer().isOnGround()
                && (!targetAboveEye(mod) || y >= _pos.getY() || withinMiningReach(mod))) {
            Debug.logToFile("altoclef_diag.log", "[Ascent] done tag=destroy y=" + y
                    + " target=" + _pos.toShortString() + " -> back to normal mining");
            _ascent.stop(mod);
            return false;
        }
        AscentHandoffHelper.TickResult r = _ascent.tick(mod);
        if (!r.handled) {
            return false;
        }
        if (r.unreachable) {
            // (2026-09-15 立即拉黑) baritone 爬不上去一次即判死刑, 不给默认阈值 4
            // 留 4 轮每轮 10-15s 的重试浪费。
            mod.getBlockTracker().requestBlockUnreachable(_pos, 1);
            return true;
        }
        if (r.child != null) {
            setDebugState("Acquiring pillar materials for " + _pos.toShortString());
            _ascentChild = r.child;
            return true;
        }
        setDebugState("Baritone pillar to " + _pos.toShortString());
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
     * 判据自激翻转。本类只在"准入前"调用它, 那时玩家应当站稳, 故传即时位置;
     * 让位期间的采样基准由 AscentHandoffHelper 自己管(它也只在落地时更新)。
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
     * (2026-09-14 v4 让位 baritone → 2026-09-17 交协调器) 把垂直爬升交给 baritone。
     *
     * 为什么是 baritone 而不是自写垫塔: 自写 PillarJumpHelper 只放不挖, 遇到挡头
     * (树叶/土/木/半砖) 只会空跳到超时; 而 baritone 的 MovementPillar 构造就是
     * positionsToBreak={start.above(2)} + positionToPlace=start (反编译实锤),
     * 天生"挖一垫一", 干竖井/干口袋完全能上。用户原话: "baritone 会处理悬空方块"。
     *
     * 目标用 GoalNear(_pos, 2) 而不是 GoalYLevel: 后者只约束高度(GoToSurfaceTask
     * 的遗产, "到某一层"在哪根柱子爬都行), 而挖方块关心的是爬到【目标旁边】。
     * 现场 20260915-224849: baritone 挑了 8 格外的柱子爬, 两次爬到 y=79 都是
     * 假 done —— 目标不在手边, 挖掘进度 -12, wander 挪走重来, 全场零产出。
     * GoalNear(pos,2) 允许脚站在目标邻格/下一层 (含斜角), 对树干旁的可站立格可满足。
     *
     * 让位期间本类**绝不动输入**; 爬升观察 / 重挂导航 / 补料 / 判负全在
     * AscentHandoffHelper (1.5s 无进展一档, 不再是 09-14 那个 26s 冻结的无底洞)。
     */
    private void startAscentHandoff(AltoClef mod) {
        // 与自写垫塔同款: builder 不会自己停, 占着输入通道会让 baritone 的
        // pillar 动不了 (现场 20260914-115322)。
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        _ascent.start(mod, _pos, _pos.getY(),
                () -> new GoalNear(_pos, 2),
                "target above eye, out of reach");
    }
    /**
     * 收尾: 幂等释放 baritone 目标 + 松开可能残留的按键 (所有退出路径必调)。
     *
     * (2026-09-15 handoff 残留目标) 现场 20260915-224849 末尾实锤: give up 后只清了
     * 让位标志, GoalYLevel(79) 还挂在 customGoalProcess 上 → baritone 原地跳 19s
     * (bIn jump=true); 更糟的是导航分支见 "isActive()" 就不设新目标, bot 永远走不到
     * 目标正下方 → 水平 8 格不满足"到位"前提 → 放弃计数每 tick 清零, 闸门永不触发,
     * 死锁闭环。故凡退出让位, 必须连 customGoal 一起放掉 —— 现在这一步在协调器
     * stop 内部, 本方法是"退出即放手"的统一出口。
     */
    private void releaseAscent(AltoClef mod) {
        _ascent.stop(mod);
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
        // (2026-09-14 垫塔接近 / 2026-09-17 统一) 让位期间 baritone 握着 customGoal,
        // 爬上期间也可能残留按键 —— 被抢占 / 上层换目标 / 链停止都走这里, 必须放手,
        // 否则残漏会让 bot 持续起跳 (现场 20260915-224849 的 19s 原地跳)。
        releaseAscent(mod);
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
