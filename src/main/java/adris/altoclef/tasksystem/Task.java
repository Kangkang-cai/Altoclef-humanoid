package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.TimeoutWanderTask;

import java.util.ArrayDeque;
import java.util.function.Predicate;

public abstract class Task {

    private String _oldDebugState = "";
    private String _debugState = "";

    private Task _sub = null;

    private boolean _first = true;

    private boolean _stopped = false;

    private boolean _active = false;

    // (2026-09-17 守炉死循环根治) 进入"停泊"(_stopped=true) 的墙钟时刻。
    // 动机: _stopped 只是"此刻没在跑", 不是终态 —— stop() 会置 _first=true,
    // 同一实例被任何父任务重新 tick 时会复位 _stopped=false 并重跑 onStart
    // (见 stop() 注释 "Next time it's run it will run onStart")。因此
    // "停泊了多久" 才是区分"暂时让位"与"被彻底遗弃"的唯一可靠依据。
    // 消费者: TaskCatalogue.CataloguedResource.getResource 的复用/重建判据。
    private long _parkedAtMs = 0;

    // (2026-08-22 横跳去抖) 任务 onTick 每 tick 全量重估子任务, 当多个候选
    // 子任务条件同时满足 (如合成/熔炉链: 缺材料↔缺容器↔清光标) 时, onTick
    // 返回值会在候选间高频横跳 (用户实测 17:12: DoStuffInFurnaceTask ↔
    // MineAndCollect、CraftInTable ↔ Craft 2x2 crafting_table 等 6x/5s)。
    // 记录"上一次被 stop 的子任务": 若新子任务与之相同 (来回横跳 A→B→A→B)
    // 且距上次切换 < 2 秒, 拒绝切换 — 保持当前 _sub 让任务稳定推进。全新
    // 目标 (单向切换) 不受影响。仅抑制"来回横跳", 不掩盖正常切换。
    private Task _lastStoppedSub = null;
    private long _lastSwitchMs = 0;
    private static final long SWITCH_DEBOUNCE_MS = 2000;

    // 递归保险丝：正常任务链深度 ~10。任务环（A 要 B、B 又要 A 的新实例）
    // 会在单个 tick 内无限递归直至 StackOverflowError 崩游戏
    // （2026-08-21 11:46 崩溃实例：做斧→合成台→木板→收集原木→又要做斧）。
    // 超过深度上限时记错误日志并放弃本 tick，游戏不再崩溃。
    private static final int MAX_TICK_DEPTH = 128;
    private static int _tickDepth = 0;

    // (2026-09-15 统一超时标签) 当前正在 onTick 的任务, 供 TimeoutLog 在
    // 进度检查器/任务超时分支里归属"是哪个任务被超时逼跳"。tick() 进出时
    // 保存/恢复, 子任务 tick 期间自然指向子任务, 零行为影响。
    private static final ThreadLocal<Task> _currentTicking = new ThreadLocal<>();

    public static Task getCurrentTickingTask() {
        return _currentTicking.get();
    }

    // (2026-09-16 防环守卫) 当前线程的 tick 祖先栈。背景: TaskCatalogue 活实例
    // 合并后, "同需求复用同一实例"与"任务不能成为自己的祖先"冲突 —— bot 拾取
    // 原木时 Mine(logs) 的斧子 COMMIT 链要合成台/木板 → 链内 getItemTask("log")
    // 命中同一个活实例 → M 出现在自己的子树里 (19:15:20 实锤: depth-128
    // OVERFLOW 后渲染线程挂死 —— 环一旦成形, thisOrChildSatisfies/stop 等
    // 无深度保护的链遍历直接死循环, 保险丝只能防崩溃防不了挂死)。
    // tick() 进出维护祖先栈, 供 TaskCatalogue 复用前判定"缓存实例是否在栈上":
    // 在栈上 = 复用会成环 → 退回新建实例。
    private static final ThreadLocal<ArrayDeque<Task>> _tickStack = ThreadLocal.withInitial(ArrayDeque::new);

    /** 当前线程 tick 祖先栈上是否已有 t (即再复用 t 会构成任务环)。 */
    public static boolean isInTickStack(Task t) {
        return t != null && _tickStack.get().contains(t);
    }

    // (2026-09-16 泡水挖矿判定) 最近一次开始 tick 的任务。链仲裁发生在任务 tick
    // 之外（此时 ThreadLocal 已恢复为 null，拿不到叶子），但仲裁紧跟在叶子 tick
    // 之后 —— 嵌套 tick 时子任务后进先覆盖，tick 结束后这里留下的正是真叶子
    // （如 DestroyBlockTask）。供 SwimDiveChain 等仲裁期反应判定"实际在干什么"。
    private static volatile Task _lastTicked;

    public static Task getLastTickedTask() {
        return _lastTicked;
    }

    public void tick(AltoClef mod, TaskChain parentChain) {
        if (_tickDepth >= MAX_TICK_DEPTH) {
            Debug.logInternal("(task) TASK CHAIN RECURSION OVERFLOW (depth " + _tickDepth
                    + ")! Task cycle detected, skipping tick. Top: " + this
                    + " sub: " + _sub);
            return;
        }
        _tickDepth++;
        Task prevTicking = _currentTicking.get();
        _currentTicking.set(this);
        ArrayDeque<Task> tickStack = _tickStack.get();
        tickStack.push(this);
        _lastTicked = this; // 嵌套时子任务随后覆盖 → tick 完留下的就是真叶子
        try {
            tickInternal(mod, parentChain);
        } finally {
            tickStack.pop();
            _currentTicking.set(prevTicking);
            _tickDepth--;
        }
    }

    private void tickInternal(AltoClef mod, TaskChain parentChain) {
        parentChain.addTaskToChain(this);
        if (_first) {
            Debug.logInternal("Task START: " + this);
            TaskSwitchLogger.taskStarted(this, parentChain);
            _active = true;
            onStart(mod);
            _first = false;
            _stopped = false;
            _parkedAtMs = 0;
        }
        if (_stopped) return;

        Task newSub = onTick(mod);
        // Debug state print
        if (!_oldDebugState.equals(_debugState)) {
            Debug.logInternal(toString());
            // (2026-09-03 日志系统完善, 用户"应有一文件保存左上角 debugState 变化历史",
            // 便于复现卡死时精确定位状态机走到哪一步) debugState 变化时同步持久化到
            // altoclef_debugstate.log (位于游戏目录)。toString() 已含 toDebugString +
            // 当前 debugState, 与左上角显示一致。变化才写, 不刷屏。
            Debug.logToFile("altoclef_debugstate.log", toString());
            _oldDebugState = _debugState;
        }
        // We have a sub task
        if (newSub != null) {
            if (!newSub.isEqual(_sub)) {
                if (canBeInterrupted(mod, _sub, newSub)) {
                    // (2026-08-22 横跳去抖) 若新子任务 == 上一次被 stop 的
                    // 子任务 (来回横跳 A→B→A→B) 且距上次切换 < 2 秒 → 拒绝
                    // 切换, 保持当前 _sub。全新目标不受影响。
                    // (2026-09-14 豁免, 212757 场次) 当前 _sub 已 isFinished
                    // (僵尸兜底任务: 掉物已消失还在 wander) 时不再占位 ——
                    // 父层边沿回切被去抖吞掉 1.55s, 期间零 interact tick,
                    // 2s barterTimeout 照样到期 → 刚成功的猪灵被误拉黑。
                    long nowMs = System.currentTimeMillis();
                    boolean subZombie = _sub != null && _sub.isFinished(mod);
                    boolean bounceBack = !subZombie
                            && newSub.isEqual(_lastStoppedSub)
                            && (nowMs - _lastSwitchMs) < SWITCH_DEBOUNCE_MS;
                    if (bounceBack && subZombie) {
                        Debug.logInternal("(Task.tickInternal: bounce debounce bypassed, current sub isFinished zombie)");
                    }
                    if (!bounceBack) {
                        // Our sub task is new
                        if (_sub != null) {
                            // Our previous sub must be interrupted.
                            _sub.stop(mod, newSub);
                        }
                        // [Diag-chain] include owning chain's priority so the
                        // log shows which chain/priority this branch runs under.
                        TaskSwitchLogger.subTask(parentChain, 0f, this, _sub, newSub);
                        _lastStoppedSub = _sub;
                        _lastSwitchMs = nowMs;
                        _sub = newSub;
                    }
                }
            }

            // (2026-08-23 NPE 防御) 极端情况下 (isEqual(null) 误判 / 竞态 /
            // canBeInterrupted 异常) newSub 非空但 _sub 仍为 null — 直接
            // _sub.tick 会 NPE 崩游戏 (2026-08-23 15:58 进下界崩溃实例:
            // Task.tickInternal:98 _sub null)。newSub 是真实子任务, 强制
            // 赋上再 tick; 若 _sub 非空 (正在执行), 保持原值不受影响。
            if (_sub == null) {
                _sub = newSub;
            }

            // Run our child
            _sub.tick(mod, parentChain);
        } else {
            // We are null
            if (_sub != null && canBeInterrupted(mod, _sub, null)) {
                // Our previous sub must be interrupted.
                _sub.stop(mod);
                TaskSwitchLogger.subTask(parentChain, 0f, this, _sub, null);
                _sub = null;
            }
        }
    }

    public void reset() {
        _first = true;
        _active = false;
        _stopped = false;
        _parkedAtMs = 0;
    }

    /**
     * (2026-09-23 死亡后失效保留子树, 事故 20260923-115851) 强制清空当前
     * 子任务并清掉 bounce 去抖记忆。供子类在"世界状态整体失效"(如玩家死亡)
     * 后调用: 旧 _sub 的导航/挖块目标已无意义, 下一拍 onTick 须全新派发。
     * 与普通 stop 的区别 —— 树可能正处于链挂起状态 (interrupt 后 _first=true
     * 且不 active), stop() 此时直接 return 不落盘, 摘除即可; 仅当它仍 active
     * 时才走完整 stop。
     */
    protected void clearRetainedSub(AltoClef mod) {
        if (_sub != null) {
            if (_sub._active) {
                _sub.stop(mod);
            }
            _sub = null;
        }
        _lastStoppedSub = null;
        _lastSwitchMs = 0;
    }

    public void stop(AltoClef mod) {
        stop(mod, null);
    }

    /**
     * Stops the task. Next time it's run it will run `onStart`
     */
    public void stop(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        TaskSwitchLogger.taskStopped(this, interruptTask);
        Debug.logInternal("Task STOP: " + this + ", interrupted by " + interruptTask);
        if (!_first) {
            onStop(mod, interruptTask);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.stop(mod, interruptTask);
        }

        _first = true;
        _active = false;
        _stopped = true;
        _parkedAtMs = System.currentTimeMillis();
    }

    /**
     * Lets the task know it's execution has been "suspended"
     *
     * STILL RUNS `onStop`
     *
     * Doesn't stop it all-together (meaning `isActive` still returns true)
     *
     * (2026-09-28 取证补充, 勿与 `stop()` 混淆)
     *  - **会走 onStop**: 让位(链被抢占)时任务的收尾/资源释放照常发生。
     *    `SingleTaskChain.onInterrupt` 正是调本方法 (`_mainTask.interrupt(mod, null)`)。
     *  - **与 `stop()` 的关键差别**: `stop()` 会写 `TaskSwitchLogger.taskStopped` +
     *    `Debug.logInternal("Task STOP: …, interrupted by …")`; 本方法**一条日志都不打**。
     *    ⇒ 让位"到底有没有触发 onStop / 释放资源"在日志里**完全不可观测**
     *    (2026-09-28 那次 30s 黑盒排查即因此失去线索, 并一度被误判成"不 stop 任务")。
     *  - **只递归 `_sub`**: 覆盖范围是"任务树"; **不负责"链自身持有的全局资源"**
     *    (如 FoodChain 在 getPriority 里 startEat 施加的 hold / interactionPaused,
     *    释放全押在 stopEat 上)。
     *  - 两个前置门: `!_active` 直接 return; `_first == true`(尚未 tick 过)会跳过 onStop。
     *  细节: detail/03-consistency.md 第 4 节 + detail/11「放床第四轮」。
     */
    public void interrupt(AltoClef mod, Task interruptTask) {
        if (!_active) return;
        if (!_first) {
            onStop(mod, interruptTask);
        }

        if (_sub != null && !_sub.stopped()) {
            _sub.interrupt(mod, interruptTask);
        }

        _first = true;
    }

    protected void setDebugState(String state) {
        if (state == null) {
            state = "";
        }
        _debugState = state;
    }

    // Virtual
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    public boolean isActive() {
        return _active;
    }

    public boolean stopped() {
        return _stopped;
    }

    /**
     * (2026-09-17 守炉死循环根治) 本实例已"停泊"的持续毫秒数; 当前未停泊返回 -1。
     * 用途: 区分"暂时让位"与"被彻底遗弃"。调用方 (TaskCatalogue 活实例槽) 只有在本实例
     * 停泊超过宽限期仍未被重新 tick 时, 才认为它已死并放行重建 —— 避免把 4 秒的瞬时
     * 让位误判成死亡而造出冷实例 (20260917-155653 实锤: 4s 内连建两个 iron_ingot 实例,
     * 冷的那一个只被 isFinished 轮询、从不 tick → drained 恒假 → 守炉空转环)。
     * 说明: 停泊期间被重新 tick 会走 tickInternal 复位 (_parkedAtMs=0), 故本值不会回绕。
     */
    public long parkedMillis() {
        if (!_stopped || _parkedAtMs <= 0) return -1;
        return System.currentTimeMillis() - _parkedAtMs;
    }

    protected abstract void onStart(AltoClef mod);

    protected abstract Task onTick(AltoClef mod);

    // interruptTask = null if the task stopped cleanly
    protected abstract void onStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqual(Task other);

    protected abstract String toDebugString();

    /**
     * (2026-09-12 诊断) 本任务当前操作的"目标方块"坐标, 供 TaskStackSnapshot
     * 打印目标位及邻块现场 (放置不下/交互失败时唯一缺失的证据: 目标格是什么
     * 方块、有没有可放置面、是否已被 BlockTracker 拉黑)。
     *
     * 默认 null = 该任务无单一目标位。纯只读, 不参与任何决策, 不改变行为;
     * 只在需要现场证据的任务里覆写 (放置/交互)。
     */
    public net.minecraft.util.math.BlockPos getDiagTargetBlock() {
        return null;
    }

    /**
     * (2026-09-17 数据集录制) 任务标签只读访问器 —— toDebugString()/toDebugState()
     * 在框架里分别是 protected/private, 录制器需要把两者【分开】导出:
     *  - debugString() = 任务类自描述的结构文案 (含参数, 如 "Collect 3 logs")
     *  - debugState()  = setDebugState(...) 写入的当前语义状态 (如 "Collecting logs")
     * 日后离线收割 L1/L2/L3 标签词表时, 两条来源口径不同, 不能混在一个字段里。
     * 纯只读, 不改变任何状态 (toString() 原本就是二者拼接)。
     */
    public String debugString() {
        return toDebugString();
    }

    public String debugState() {
        return _debugState;
    }

    @Override
    public String toString() {
        return "<" + toDebugString() + "> " + _debugState;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Task task) {
            return isEqual(task);
        }
        return false;
    }

    public boolean thisOrChildSatisfies(Predicate<Task> pred) {
        Task t = this;
        while (t != null) {
            if (pred.test(t)) return true;
            t = t._sub;
        }
        return false;
    }

    public boolean thisOrChildAreTimedOut() {
        return thisOrChildSatisfies(task -> task instanceof TimeoutWanderTask);
    }

    /**
     * Sometimes a task just can NOT be bothered to be interrupted right now.
     * For instance, if we're in mid air and MUST complete the parkour movement.
     */
    // [ForceProtect] 诊断日志节流（1s 一条，防空中连发刷屏）
    private static long _lastForceLogTs = 0;

    private boolean canBeInterrupted(AltoClef mod, Task subTask, Task toInterruptWith) {
        if (subTask == null) return true;
        // (2026-09-16 语义修复) 旧实现走 thisOrChildSatisfies(anyMatch): 子链上
        // 任何一个非-force 任务都让谓词命中返回 true → ITaskCanForce 的
        // shouldForce=true 声明几乎永远被旁路, 只有 _sub 恰为叶子单任务时才
        // 生效 (ITaskRequiresGrounded 的跳跃/放置保护、ResourceTask 的光标
        // 保护实际上从未在深层链上生效过)。
        // 改为沿子链扫描: 任一 ITaskCanForce 声明 force → 整棵子树本 tick
        // 不可被父级翻面打断。
        // (20260916-1104 场次实锤: 腾位穿铁靴 Moving 的 force 被链上 5 个
        // 非-force 祖先旁路, 仍被 Collect Recipe Resources 每 8s 打断,
        // 收输出腾位死循环未解。)
        Task t = subTask;
        while (t != null) {
            if (t instanceof ITaskCanForce canForce && canForce.shouldForce(mod, toInterruptWith)) {
                long now = System.currentTimeMillis();
                if (now - _lastForceLogTs > 1000) {
                    _lastForceLogTs = now;
                    Debug.logMessage("[ForceProtect] " + t + " 抵挡打断 (来自 " + toInterruptWith + ")");
                }
                return false;
            }
            t = t._sub;
        }
        return true;
    }
}
