package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasks.entity.GiveItemToPlayerTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasksystem.Task;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 阶段三·行动派发器：AI 行动白名单 / 限流 / 回执 / 主线干预的唯一落点。
 *
 * <p>设计契约见 {@code DESIGN_action_api.md}。核心取舍：
 * <ul>
 *   <li><b>不给 LLM 调度权</b>：所有干预收敛为两个伪命令
 *       {@code @pause_main}（停下手头任何用户任务）与 {@code @resume_main}（回主线），
 *       主线令牌只是一个 boolean，不暴露任意任务名 / 链优先级。</li>
 *   <li><b>恢复用重建不复用</b>：被 cancel 的任务实例已 stop，二次 setTask 会走
 *       onStart 二次进入雷区（记忆 detail/01）；重建 {@link BeatMinecraft2Task} 有
 *       死亡恢复/@gamer 重开先例，进度由 KnowledgeMemory 承接。</li>
 *   <li><b>回执两级</b>：同步 action_accepted（HTTP 线程受理即发）+ 异步 action_done
 *       （顶层任务结束 / 瞬时命令立即发）。失败不做模组侧判定（信号不可靠），
 *       由对端按 actionId 超时兜底。</li>
 *   <li><b>完成即回主线（B'，1001 对端拍板）</b>：goto/follow/give 这类有终点的行动，
 *       完成时自动重建主线（action_done 文本附"已自动回主线"）；`@idle` 与 `@pause_main`
 *       归"按住"侧——无终点或语义就是"立正等到下一条指令"，只由显式 `@resume_main` 解除。</li>
 *   <li><b>停顿标记</b>：pause 的 cancel 会同步触发 TaskFinishedEvent
 *       （UserTaskChain.cancel → onTaskFinish → publish，1001 实测）。若不处理，
 *       LLM 会看到"完成：BeatMinecraft2Task"这种误导性完成信号。故 pause 期间置
 *       {@link #isInterrupting()} 同步窗口标记，EventCollector 与
 *       {@link #onTaskFinished} 都借此跳过。</li>
 * </ul>
 *
 * <p>线程模型：HTTP 线程做校验/限流/记账（只读配置 + 并发队列）；
 * {@link #runOnClientThread} 与 {@link #onTaskFinished} 都在客户端线程，
 * 触碰 mod/taskChain 安全。
 */
final class ActionDispatcher {

    /** 只读命令：不占限流额度（不改变世界状态）。 */
    private static final Set<String> READ_ONLY = Set.of("coords", "status", "inventory", "help", "list");

    /** 瞬时命令：无任务生命期，受理即视为完成（立即回 action_done，**不进 pending 队列**）。 */
    private static final Set<String> INSTANT = Set.of("coords", "status", "inventory", "help", "list", "idle");

    /**
     * 有任务生命期的行动：只有这些命令才登记 pending、才可能在顶层任务结束时回 action_done
     * （B' 口径同集：完成后自动回主线）。
     *
     * <p>⚠️ 1002 冒烟实锤缺陷：早前实现用"FIFO 取最老 pending"，且 pause/只读类也压了 pending
     * 条目（它们的 done 是同步发的、从不 pop）⇒ 队列残留老条目被后续 TaskFinishedEvent 拿走，
     * done 记到错误 actionId（pause 的 done 出现第二次、goto 的 done 迟到 2.5 分钟）。
     * 现在两道闸：①非 TASK_ACTIONS 一律不登记 pending；②关联时必须**语义匹配**
     * （结束的顶层任务类型 == 该 token 期望的任务类型），匹配不上宁可丢弃，交对端超时兜底。
     */
    private static final Set<String> TASK_ACTIONS = Set.of("goto", "follow", "give");

    /** 主线干预伪命令 token（不在 CommandExecutor 注册，仅 bridge 识别）。 */
    static final String PAUSE = "pause_main";
    static final String RESUME = "resume_main";

    /** pending action 关联时间窗：超过则丢弃（对端自行超时）。 */
    private static final long PENDING_TTL_MS = 5 * 60_000;
    private static final int MAX_PENDING = 8;
    private static final long RATE_WINDOW_MS = 60_000;

    private final AltoClef _mod;
    private final EventBuffer _buffer;

    /** 限流滑动窗口（HTTP 线程独占）。 */
    private final ConcurrentLinkedDeque<Long> _actionTimesMs = new ConcurrentLinkedDeque<>();
    /** 待回执行动（HTTP 线程写，客户端线程读/消费）。 */
    private final ConcurrentLinkedDeque<PendingAction> _pending = new ConcurrentLinkedDeque<>();
    private final AtomicLong _seq = new AtomicLong(0);

    /** pause 令牌：语义= "我们主动暂停了手头任务"，仅用于语义记录与幂等。 */
    private volatile boolean _paused = false;
    /** pause 的同步 cancel 窗口标记（EventCollector / onTaskFinished 据此跳过伪完成）。 */
    private volatile boolean _interrupting = false;

    private Subscription<TaskFinishedEvent> _subTask;

    ActionDispatcher(AltoClef mod, EventBuffer buffer) {
        this._mod = mod;
        this._buffer = buffer;
    }

    // ===================== HTTP 线程侧 =====================

    /** 该 token 是否在 AI 行动白名单内（含两个主线干预伪命令）。 */
    boolean isAllowedToken(String token) {
        if (token == null) return false;
        if (PAUSE.equals(token) || RESUME.equals(token)) {
            return BridgeConfig.getInstance().enablePauseResume;
        }
        List<String> allowed = BridgeConfig.getInstance().allowedCommands;
        return allowed != null && allowed.contains(token);
    }

    /** 只读命令不占限流额度。 */
    boolean isReadOnlyToken(String token) {
        return token != null && READ_ONLY.contains(token);
    }

    /**
     * 限流检查并记账。
     *
     * @return 0 = 允许（已记一次）；&gt;0 = 拒绝，返回建议退避秒数
     */
    int rateCheckAndRecord() {
        int limit = BridgeConfig.getInstance().actionRatePerMinute;
        if (limit <= 0) return 0; // 不限流
        long now = System.currentTimeMillis();
        pruneRateWindow(now);
        if (_actionTimesMs.size() >= limit) {
            Long oldest = _actionTimesMs.peekFirst();
            long waitMs = oldest == null ? RATE_WINDOW_MS : RATE_WINDOW_MS - (now - oldest);
            return (int) Math.max(1, (waitMs + 999) / 1000);
        }
        _actionTimesMs.addLast(now);
        return 0;
    }

    private void pruneRateWindow(long now) {
        while (true) {
            Long head = _actionTimesMs.peekFirst();
            if (head == null || now - head < RATE_WINDOW_MS) return;
            _actionTimesMs.pollFirst();
        }
    }

    /** 生成 actionId：对端给了就透传，否则 bridge 生成。 */
    String allocateActionId(String given) {
        if (given != null && !given.isEmpty()) return given;
        return "a-" + System.currentTimeMillis() + "-" + _seq.incrementAndGet();
    }

    /** 登记待回执行动 + 发同步受理事件（HTTP 线程）。 */
    void accept(String actionId, String token, String cmd) {
        // 只有有任务生命期的行动才进 pending —— pause/resume/只读类的 done 是同步发的，
        // 登记了也没人 pop，只会在队列里残留并污染后续关联（1002 实锤缺陷）。
        if (TASK_ACTIONS.contains(token)) {
            prunePending();
            // 同 token 的新派发顶掉旧条目：前一次行动的任务已被 setTask 顶替，其 done 永不会来，
            // 留着只会让后续事件错配（对端对旧 actionId 走超时兜底）。
            _pending.removeIf(p -> p.token.equals(token));
            _pending.addLast(new PendingAction(actionId, token, System.currentTimeMillis()));
            while (_pending.size() > MAX_PENDING) {
                _pending.pollFirst();
            }
        }
        _buffer.emitImmediate("action_accepted",
                "[" + actionId + "] 已受理：" + describe(token, cmd), false,
                detailJson(actionId, token));
    }

    /** 构建 detail JSON 片段（值全部走 BridgeJson 转义，防 LLM 注入破坏 JSON）。 */
    static String detailJson(String actionId, String token) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"actionId\":");
        BridgeJson.str(sb, actionId);
        sb.append(",\"cmd\":");
        BridgeJson.str(sb, token);
        sb.append('}');
        return sb.toString();
    }

    private static String describe(String token, String cmd) {
        String rest = cmd == null ? "" : cmd;
        return rest.length() > 60 ? rest.substring(0, 60) + "…" : rest;
    }

    // ===================== 客户端线程侧 =====================

    /** 订阅顶层任务结束（action_done 关联）；由 BridgeHttpServer 启动时调用。 */
    void subscribe() {
        _subTask = EventBus.subscribe(TaskFinishedEvent.class, this::onTaskFinished);
    }

    void dispose() {
        EventBus.unsubscribe(_subTask);
        _subTask = null;
    }

    /** 执行派发（客户端线程，由 BridgeHttpServer 经 client.execute 调度）。 */
    void runOnClientThread(String cmd, String token, String actionId) {
        try {
            if (PAUSE.equals(token)) {
                boolean did = pauseMainline();
                emitDone(actionId, token, did ? "已暂停手头任务" : "手头没有任务，无需暂停");
                return;
            }
            if (RESUME.equals(token)) {
                boolean did = resumeMainline();
                emitDone(actionId, token, did ? "已回到主线" : "主线已在推进");
                return;
            }
            CommandExecutor executor = AltoClef.getCommandExecutor();
            if (executor == null) {
                emitDone(actionId, token, "命令执行器未就绪");
                return;
            }
            executor.execute(cmd);
            // 瞬时命令没有任务生命期，没有 TaskFinishedEvent 可等 —— 受理即完成
            if (INSTANT.contains(token)) {
                emitDone(actionId, token, "已执行：" + token);
                return;
            }
            // 其余（goto/follow/give）等顶层任务结束时报 action_done（见 onTaskFinished），
            // 未结束就结束的（被顶替/被 resume 抢占）由对端超时兜底。
        } catch (Throwable t) {
            Debug.logWarning("[Bridge] action dispatch threw: " + t);
            emitDone(actionId, token, "执行失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 停下手头任何用户任务（不限于主线），语义定稿见 DESIGN_action_api.md §2.3。
     * 幂等：无任务在跑返回 false。
     */
    private boolean pauseMainline() {
        var chain = _mod.getUserTaskChain();
        if (chain == null) return false;
        if (chain.getCurrentTask() == null || chain.isRunningIdleTask()) return false;
        _paused = true;
        // cancel 会同步 publish TaskFinishedEvent（UserTaskChain.cancel → onTaskFinish），
        // 窗口内抑制伪 task_done。
        _interrupting = true;
        try {
            _mod.cancelUserTask();
        } finally {
            _interrupting = false;
        }
        return true;
    }

    /**
     * 回到主线：已在跑主线则幂等返回 false（顺带覆盖"人类手动 @gamer 接管"场景），
     * 否则重建 BeatMinecraft2Task。
     */
    private boolean resumeMainline() {
        var chain = _mod.getUserTaskChain();
        Task cur = chain == null ? null : chain.getCurrentTask();
        if (cur instanceof BeatMinecraft2Task) {
            _paused = false;
            return false;
        }
        _paused = false;
        _mod.runUserTask(new BeatMinecraft2Task());
        return true;
    }

    /** pause 的同步 cancel 窗口：true = 本次任务结束是 bridge 主动中断，不是"完成"。 */
    boolean isInterrupting() {
        return _interrupting;
    }

    /** 是否处于"我们主动暂停过"状态（诊断 / 后续 /status 扩展用）。 */
    boolean isPaused() {
        return _paused;
    }

    /**
     * 顶层任务结束 → 语义匹配 pending action，命中才回 action_done；命中不了直接丢弃
     * （对端按 actionId 超时兜底，宁可无信号也不发错配信号）。
     */
    private void onTaskFinished(TaskFinishedEvent evt) {
        if (_interrupting) return; // pause 的 cancel，不当作行动完成
        Task finished = evt == null ? null : evt.lastTaskRan;
        if (finished == null) return;
        PendingAction p = pollMatching(finished);
        if (p == null) return; // 无匹配（含 pending 为空 / 类型不符）：丢弃，等对端超时
        String label = finished.toString();
        if (label.length() > 40) label = label.substring(0, 40) + "…";
        String text = label.isEmpty() ? "完成" : "完成：" + label;
        // B'：goto/follow/give 完成 → 自动回主线（idle/pause_main 不登记 pending，不在此列）。
        // 此刻 UserTaskChain.onTaskFinish 已把 _mainTask 置 null 且 EventBus.publish 同步派发到
        // 本回调，故这里 runUserTask 是安全的重入（setTask 走 _mainTask==null 分支，不会误停旧任务）。
        if (resumeMainline()) {
            text += "，已自动回主线";
        }
        emitDone(p.actionId, p.token, text);
    }

    /** 从待回执队列里取**语义匹配**本次结束任务的最老条目；无匹配返回 null。 */
    private PendingAction pollMatching(Task finished) {
        prunePending();
        for (PendingAction p : _pending) {
            if (matchesToken(p.token, finished)) {
                _pending.remove(p);
                return p;
            }
        }
        return null;
    }

    /** token → 期望的顶层任务类型（语义相等才允许关联）。 */
    private static boolean matchesToken(String token, Task finished) {
        return switch (token) {
            case "goto" -> finished instanceof GetToBlockTask
                    || finished instanceof GetToXZTask
                    || finished instanceof GetToYTask
                    || finished instanceof DefaultGoToDimensionTask;
            case "follow" -> finished instanceof FollowPlayerTask;
            case "give" -> finished instanceof GiveItemToPlayerTask;
            default -> false;
        };
    }

    private void prunePending() {
        long now = System.currentTimeMillis();
        while (true) {
            PendingAction head = _pending.peekFirst();
            if (head == null || now - head.tsMs <= PENDING_TTL_MS) return;
            _pending.pollFirst();
        }
    }

    private void emitDone(String actionId, String token, String text) {
        _buffer.emitImmediate("action_done", "[" + actionId + "] " + text, false,
                detailJson(actionId, token));
    }

    /** 待回执行动条目。 */
    private static final class PendingAction {
        final String actionId;
        final String token;
        final long tsMs;

        PendingAction(String actionId, String token, long tsMs) {
            this.actionId = actionId;
            this.token = token;
            this.tsMs = tsMs;
        }
    }
}
