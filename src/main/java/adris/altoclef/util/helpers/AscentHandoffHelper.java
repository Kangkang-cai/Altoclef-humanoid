package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.function.Supplier;

/**
 * (2026-09-17) 垂直爬升交还 baritone 的**统一协调器**。
 *
 * <p>为什么要有这个类:20260917-143636 现场,`InteractWithBlockTask`(去工作台右键,
 * 目标在头顶 6 格)在自挖竖井底被 baritone 原地跳了 33.2s 直到用户手停 ——
 * `[NAV-STUCK] netXZ=0.00 yrange=1.13~4.25 jump=true pathing=true` 且**全场零 Place**。
 * 根因不是"补料逻辑写错",而是补料与让位机制**只长在 `DestroyBlockTask` 里**,
 * 走"去某个容器/位置"的导航任务连"缺垫块"这个概念都不存在;而它自带的 15s 兜底
 * 又被"4s 瞬时判负一 tick + 其余 tick 全判正"淹没(`InteractWithBlockTask`
 * `if (moveOk) _giveUpTimer.reset()`),永远攒不满。
 *
 * <p>本类把"挂 goal → 观察进展 → 反应式补救 → 判不可达"收敛成一个状态机,
 * 供所有"要站到某个高处才能干活"的任务共用。所有消费方共享同一份判据口径,
 * 不再各自实现一套(合并前有 4 套半垂直爬升机制并存)。
 *
 * <h2>三档补救(2026-09-17 用户定档)</h2>
 * <ol>
 *   <li><b>1.5s 无进展 → 重挂导航</b>。这是首选:baritone 有路径却原地跳,常常是
 *       手上那份 path 已经作废(起跳腰斩/目标瞬时不可达),重新规划一次它可能就
 *       自己挖挡块爬上去了 —— 用户原话"可以让 baritone 重新导航一次,他自己在
 *       能挖的情况下选择自己挖上去的,不一定非要补料挖"。零成本,所以排第一。</li>
 *   <li><b>再 1.5s 无进展 → 补一次垫料</b>(固定 5 格,只补一次)。垫块总量是
 *       baritone 放置的总闸(`hasThrowaway` → `costOfPlacingAt`),一块都没有时
 *       MovementPillar 会在 A* 里被直接排除,它只会原地跳,永远不会自己去找方块。</li>
 *   <li><b>补料后仍无进展 → 判不可达</b>,交调用方拉黑换目标(恢复 09-03 地基)。
 *       不再有"反复跳跃止不住"的窗口 —— 1.5s × 2 段就做出决策。</li>
 * </ol>
 *
 * <h2>为什么必须"落地采样"</h2>
 * 起跳瞬间 `getBlockPos().getY()` 会瞬时 +1。若拿它当"高度上升了",原地跳的 bot
 * 每跳一次就刷新一次进展计时 → 判据恒不触发(与 `DestroyBlockTask._pillarLastY`
 * 注释里那条"合法瞬时升高"是同一个坑的第二形态)。故只在 `isOnGround()` 时采样,
 * 且要求**创新高**。水平位移只认**跨整格**(BlockPos 整数变化),防止原地抖动骗过。
 *
 * <h2>消费方契约</h2>
 * <ul>
 *   <li>调用方负责"到了没"的业务判据(挖掘距离够不够、右键能不能点到)——
 *       一旦业务达成,调用方自己 `stop()`,不要把完成判定塞进本类。</li>
 *   <li>`tick()` 返回的 {@link TickResult#child} 必须由调用方**原样返回**给框架
 *       (补料子任务实例 keep-alive,本仓铁律)。</li>
 *   <li>`unreachable=true` 时本类已自行 `stop()`,调用方只需走拉黑路径。</li>
 * </ul>
 */
public final class AscentHandoffHelper {

    /**
     * 无进展判定窗口。2026-09-17 用户定档 1.5s(此前 DestroyBlockTask 是 4s)。
     * baritone 的 MovementPillar 每格约 1.5s,1.5s 一格不动就该换招了;
     * 真在爬的场景 1.5s 内必有 y 上升或跨格位移,不会误判。
     */
    public static final double STALL_SECONDS = 1.5;

    /** 一次补几块垫料。净升 1 格就能把"眼→目标"缩进 reach,5 块够垫一段。 */
    private static final int ACQUIRE_WANT = 5;

    /** 补料子任务总时长上限(自挖的哨兵 targetCount 陷阱:收够了也可能判不完)。 */
    private static final double ACQUIRE_TOTAL_SECONDS = 30;

    /**
     * 总窗口硬兜底。正常情况下 1.5s×2 段就该有结论,但若 bot "来回挪一格"
     * 每轮都算进展(真的在绕路),需要一条绝对上限防爆。取 90s:每格 1.5s
     * 足够爬 60 格,远超任何真实竖直差距。
     */
    private static final double TOTAL_SECONDS = 90;

    /** 内层完成判据的放宽量:站在目标【下一层】通常已能交互/挖掘,不必强求同层。 */
    private static final int ARRIVED_SLACK = 1;

    // ==================== tick 返回 ====================

    /**
     * 本 tick 的处置结果。四选一,互斥。
     */
    public static final class TickResult {
        /** 本 tick 是否由协调器接管。false = 调用方走自己的原逻辑。 */
        public final boolean handled;
        /** 需要调用方原样返回给框架的子任务(补料采集)。null = 无需子任务。 */
        public final Task child;
        /** 已到达目标层,本类已 stop(),调用方收工回常规路径。 */
        public final boolean done;
        /** 判不可达,本类已 stop(),调用方应拉黑目标换活。 */
        public final boolean unreachable;

        private TickResult(boolean handled, Task child, boolean done, boolean unreachable) {
            this.handled = handled;
            this.child = child;
            this.done = done;
            this.unreachable = unreachable;
        }

        private static final TickResult NOT_HANDLED = new TickResult(false, null, false, false);

        private static TickResult running(Task child) {
            return new TickResult(true, child, false, false);
        }

        private static TickResult done() {
            return new TickResult(true, null, true, false);
        }

        private static TickResult unreachable() {
            return new TickResult(true, null, false, true);
        }

        public static TickResult notHandled() {
            return NOT_HANDLED;
        }
    }

    // ==================== 状态 ====================

    /** 日志标签, 用于在 diag.log 里分辨是哪个消费方(单一实例不能串场)。 */
    private final String _tag;
    /** 垫料口径由调用方传入 —— 挖树任务不含原木, 逃生含 (见 PillarJumpHelper)。 */
    private final Item[] _materials;

    private boolean _active = false;
    private BlockPos _target = null;
    private int _targetY = Integer.MIN_VALUE;
    private Supplier<Goal> _goalFactory = null;
    private String _reason = "";

    /** 上次采样的脚块 y(**只在落地时更新**, 见类注释)。 */
    private int _lastY = Integer.MIN_VALUE;
    private int _lastX = Integer.MIN_VALUE;
    private int _lastZ = Integer.MIN_VALUE;
    /** 是否已采过基准位置。首 tick 只建立基准, 不算进展也不算 stall。 */
    private boolean _sampled = false;

    private final TimerGame _stallTimer = new TimerGame(STALL_SECONDS);
    private final TimerGame _totalTimer = new TimerGame(TOTAL_SECONDS);
    /** 本段是否已重挂过导航。"重挂"只做一次, 之后再 stall 就进补料档。 */
    private boolean _renavigated = false;
    /** 补料后重置 _renavigated, 让 bot 再获得一次"重挂 + 爬"的机会。 */
    private boolean _acquireStarted = false;
    private Task _acquireTask = null;
    private final TimerGame _acquireTimer = new TimerGame(ACQUIRE_TOTAL_SECONDS);
    private boolean _acquireDoneLogged = false;
    /** 已经历的 stall 轮数, 只用于日志。 */
    private int _stallRounds = 0;

    public AscentHandoffHelper(String tag, Item[] materials) {
        _tag = tag;
        _materials = materials;
    }

    public boolean isActive() {
        return _active;
    }

    public boolean isAcquiring() {
        return _acquireTask != null;
    }

    public String getTag() {
        return _tag;
    }

    // ==================== 生命周期 ====================

    /**
     * 开始让位。调用方需自行保证"这个目标确实需要往上爬"(在眼睛上方 / 够不着),
     * 并提供一个能**每次新建** goal 的工厂 —— baritone 的 `setGoal` 对相同引用
     * 会早退, 复用同一实例可能导致"重挂导航"其实是空操作。
     *
     * @param targetY 期望到达的脚块层。到达 `targetY - ARRIVED_SLACK` 且落地即判 done。
     */
    public void start(AltoClef mod, BlockPos target, int targetY,
                      Supplier<Goal> goalFactory, String reason) {
        _active = true;
        _target = target;
        _targetY = targetY;
        _goalFactory = goalFactory;
        _reason = reason;
        _lastY = Integer.MIN_VALUE;
        _lastX = Integer.MIN_VALUE;
        _lastZ = Integer.MIN_VALUE;
        _sampled = false;
        _renavigated = false;
        _acquireStarted = false;
        _acquireTask = null;
        _acquireDoneLogged = false;
        _stallRounds = 0;
        _stallTimer.reset();
        _totalTimer.reset();
        applyGoal(mod);
        Debug.logToFile("altoclef_diag.log", "[Ascent] start tag=" + _tag
                + " target=" + shortPos(target) + " targetY=" + targetY
                + " fromY=" + mod.getPlayer().getBlockPos().getY()
                + " materials=" + PillarJumpHelper.countMaterials(mod, _materials)
                + " (" + reason + ")");
    }

    /**
     * 每 tick 驱动。
     *
     * @return 见 {@link TickResult}。`handled=false` 时调用方应走自己的原逻辑。
     */
    public TickResult tick(AltoClef mod) {
        if (!_active || mod.getPlayer() == null || mod.getWorld() == null) {
            return TickResult.notHandled();
        }

        // ① 补料子任务在飞 → 只观察它, 不抢输入 (采集任务自己会管导航)。
        Task acquire = pumpAcquire(mod);
        if (acquire != null) {
            return TickResult.running(acquire);
        }

        int y = mod.getPlayer().getBlockPos().getY();
        boolean onGround = mod.getPlayer().isOnGround();

        // ② 到达判定: 只在落地时认, 避免起跳瞬时 y+1 假达标 (20260915-160447 死循环的坑)。
        if (onGround && y >= _targetY - ARRIVED_SLACK) {
            Debug.logToFile("altoclef_diag.log", "[Ascent] done tag=" + _tag
                    + " y=" + y + " target=" + shortPos(_target));
            stop(mod);
            return TickResult.done();
        }

        // ③ 合法零速豁免: baritone 正在挖挡块时, 挖一格可能**远慢于 1.5s**
        //    (空手挖石头 7.5s)。这段 XZ/y 都不动, 但它是在干活 —— 若算作 stall 就会
        //    反复 onLostControl 打断它的挖掘(进度清零), 越修越爬不上去。
        //    本仓"卡死检测必须排除合法零速状态"铁律的同款应用; 同族豁免见
        //    detail/02-diagnosis.md(挖方块/下落中/竖直垫塔/岩浆上浮)。
        if (mod.getControllerExtras().isBreakingBlock()) {
            _stallTimer.reset();
            return TickResult.running(null);
        }

        // ④ 有进展就续命。
        if (progressed(mod)) {
            _stallTimer.reset();
            return TickResult.running(null);
        }

        // ⑤ 无进展 → 三档补救。总窗口只作防爆兜底("来回挪一格"每轮都算进展时)。
        boolean totalExpired = _totalTimer.elapsed();
        if (!_stallTimer.elapsed() && !totalExpired) {
            return TickResult.running(null);
        }
        _stallTimer.reset();
        _stallRounds++;

        if (!totalExpired && !_renavigated) {
            // 第一档: 重挂导航。让 baritone 丢掉可能已作废的 path 重算一次 ——
            // 它是纯 pathfinder, 能挖的挡块本来就归它处理, 重算可能直接解决。
            _renavigated = true;
            applyGoal(mod);
            Debug.logToFile("altoclef_diag.log", "[Ascent] renavigate tag=" + _tag
                    + " round=" + _stallRounds + " y=" + y + " lastY=" + _lastY
                    + " have=" + PillarJumpHelper.countMaterials(mod, _materials)
                    + " target=" + shortPos(_target));
            return TickResult.running(null);
        }

        int have = PillarJumpHelper.countMaterials(mod, _materials);
        if (!totalExpired && have == 0 && !_acquireStarted) {
            // 第二档: 一块垫料都没有 → 补一次。baritone 的 MovementPillar 需要
            // throwaway 方块才能进 A* (`hasThrowaway` false 时 costOfPlacingAt 恒
            // 1_000_000), 没料它只会原地跳到天荒地老, 永远不会自己去找方块。
            Task t = startAcquire(mod);
            if (t != null) {
                return TickResult.running(t);
            }
        }

        // 第三档: 有料也爬不上去 / 已补过一次 / 建不出采集任务 → 判不可达。
        Debug.logToFile("altoclef_diag.log", "[Ascent] give up tag=" + _tag
                + " y=" + y + " lastY=" + _lastY
                + " have=" + have + " acquired=" + _acquireStarted
                + " paused=" + mod.getExtraBaritoneSettings().isInteractionPaused()
                + (_totalTimer.elapsed() ? " (total window elapsed)" : "")
                + " target=" + shortPos(_target) + " -> unreachable");
        stop(mod);
        return TickResult.unreachable();
    }

    /** 幂等收工: 释放 baritone 目标 + 松开可能残留的按键。所有退出路径必调。 */
    public void stop(AltoClef mod) {
        if (!_active) {
            return;
        }
        _active = false;
        _acquireTask = null;
        _target = null;
        _goalFactory = null;
        if (!AltoClef.inGame()) {
            return;
        }
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        // 让位期间上层不按任何键, 但保险起见统一松一次 (PillarJumpHelper 的调用方契约)。
        PillarJumpHelper.release(mod);
    }

    // ==================== 内部 ====================

    /** 挂/重挂目标。先 onLostControl 再 setGoalAndPath, 保证同 goal 也能真重算。 */
    private void applyGoal(AltoClef mod) {
        if (_goalFactory == null) {
            return;
        }
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        var proc = mod.getClientBaritone().getCustomGoalProcess();
        proc.onLostControl();
        proc.setGoalAndPath(_goalFactory.get());
    }

    /**
     * 本 tick 是否有真实进展。
     *
     * <p>高度:**只在落地时**采且必须创新高(起跳瞬时 +1 不算, 见类注释)。
     * 水平:BlockPos 整数格变化即算(跨整格才可能变化), 用来覆盖 `GoalNear`
     * 的"先进场走路再爬"阶段 —— 那段 y 不涨但 bot 确实在干活。
     */
    private boolean progressed(AltoClef mod) {
        BlockPos cur = mod.getPlayer().getBlockPos();
        if (!_sampled) {
            _sampled = true;
            _lastY = cur.getY();
            _lastX = cur.getX();
            _lastZ = cur.getZ();
            return true;
        }
        if (mod.getPlayer().isOnGround() && cur.getY() > _lastY) {
            _lastY = cur.getY();
            _lastX = cur.getX();
            _lastZ = cur.getZ();
            return true;
        }
        if (cur.getX() != _lastX || cur.getZ() != _lastZ) {
            _lastX = cur.getX();
            _lastZ = cur.getZ();
            return true;
        }
        return false;
    }

    /** 补料子任务 keep-alive。非 null = 调用方原样返回它。 */
    private Task pumpAcquire(AltoClef mod) {
        if (_acquireTask == null) {
            return null;
        }
        // 实时计数早停: 直读真实背包, 不信子任务自己的 isFinished
        // (哨兵 targetCount 陷阱: 收够了也可能判不完)。
        if (PillarJumpHelper.countMaterials(mod, _materials) >= ACQUIRE_WANT
                || mod.getItemStorage().getItemCountReal(Items.DIRT) >= ACQUIRE_WANT) {
            finishAcquire(mod, "enough");
            return null;
        }
        if (_acquireTimer.elapsed()) {
            finishAcquire(mod, "timeout");
            return null;
        }
        boolean finished;
        try {
            finished = _acquireTask.isFinished(mod);
        } catch (Throwable t) {
            // 没 start 过就查 isFinished 可能炸 → 当作完成, 不再重派
            // ("读不到与 0 同码"教训的同款防御: 宁可少采一轮, 不要卡在这里)。
            finished = true;
        }
        if (finished) {
            finishAcquire(mod, "finished");
            return null;
        }
        return _acquireTask;
    }

    /**
     * 收工补料并**重新开放一轮补救**: 补料之后 bot 应该再获得一次
     * "重挂导航 → 爬"的机会, 否则补完料立刻撞上"已补过一次"判负, 白采。
     */
    private void finishAcquire(AltoClef mod, String why) {
        logAcquireDone(mod, why);
        _acquireTask = null;
        _renavigated = false;
        _stallRounds = 0;
        _stallTimer.reset();
        // 采集任务期间可能把 baritone 目标留成别的东西, 重挂一次干净的。
        applyGoal(mod);
    }

    /** 派垫料采集子任务。null = 目录里建不出来 (调用方落回判不可达)。 */
    private Task startAcquire(AltoClef mod) {
        Task t = TaskCatalogue.getItemTask(Items.DIRT, ACQUIRE_WANT);
        _acquireStarted = true;
        _acquireDoneLogged = false;
        if (t == null) {
            Debug.logToFile("altoclef_diag.log", "[Ascent] acquire unavailable tag=" + _tag
                    + " item=" + Items.DIRT + " -> fall through to give up");
            return null;
        }
        _acquireTask = t;
        _acquireTimer.reset();
        Debug.logToFile("altoclef_diag.log", "[Ascent] acquire start tag=" + _tag
                + " want=" + ACQUIRE_WANT + " X " + Items.DIRT
                + " y=" + mod.getPlayer().getBlockPos().getY()
                + " have=" + PillarJumpHelper.countMaterials(mod, _materials)
                + " target=" + shortPos(_target));
        return t;
    }

    private void logAcquireDone(AltoClef mod, String why) {
        if (_acquireDoneLogged) {
            return;
        }
        _acquireDoneLogged = true;
        Debug.logToFile("altoclef_diag.log", "[Ascent] acquire done(" + why + ") tag=" + _tag
                + " have=" + PillarJumpHelper.countMaterials(mod, _materials)
                + " target=" + shortPos(_target));
    }

    private static String shortPos(BlockPos pos) {
        return pos == null ? "null" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
