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
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    // ===================== 现场执行状态（1002 C 块：activeAction / botStatus / 终态细分）=====================

    /** 进度计数器 N 秒不变 → phase=blocked（客户端 tick 评估，比外部坐标猜测准）。 */
    private static final long BLOCKED_SEC = 15;
    /** goto 持续算不出路径多久 → failed（保守；判错宁可只报 blocked）。 */
    private static final long FAIL_GOTO_SEC = 120;
    /** follow 目标从未出现过多久 → failed（"目标始终不在线"）。 */
    private static final long FAIL_FOLLOW_SEC = 180;

    /** 当前活跃行动（volatile：客户端线程写，HTTP 线程只读）。null = 无活跃行动。 */
    private volatile ActiveAction _active;
    private volatile long _activeSinceMs = 0;

    /** goto 目标（dispatch 时从 cmd 文本解析）。 */
    private volatile GoTarget _activeGotoTarget;
    /** follow/give 的目标玩家名。 */
    private volatile String _activePlayerName;
    /** give 的目标物品 token 与数量。 */
    private volatile String _activeItemToken;
    private volatile int _activeItemCount = 0;

    // ---- 进度判定状态（仅客户端线程读写）----
    private String _progressKey = null;
    private long _progressAtMs = 0;

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
        return detailJson(actionId, token, null, null);
    }

    /**
     * 带终态分类的 detail：{@code status ∈ {completed,cancelled,interrupted,failed}}，
     * {@code reason} 仅在需要说明时出现（详见 DESIGN_action_api.md 的终态契约）。
     */
    static String detailJson(String actionId, String token, String status, String reason) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"actionId\":");
        BridgeJson.str(sb, actionId);
        sb.append(",\"cmd\":");
        BridgeJson.str(sb, token);
        if (status != null && !status.isEmpty()) {
            sb.append(",\"status\":");
            BridgeJson.str(sb, status);
        }
        if (reason != null && !reason.isEmpty()) {
            sb.append(",\"reason\":");
            BridgeJson.str(sb, reason);
        }
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
                // 回主线 → 没有活跃行动（activeAction 键省略）
                _active = null;
                _activeSinceMs = 0;
                resetProgress();
                emitDone(actionId, token, did ? "已回到主线" : "主线已在推进");
                return;
            }
            CommandExecutor executor = AltoClef.getCommandExecutor();
            if (executor == null) {
                emitDone(actionId, token, "命令执行器未就绪");
                return;
            }
            // 会替换手头用户任务的命令（动作三类 + idle）→ 先给旧活跃行动发 cancelled
            boolean replacesUserTask = TASK_ACTIONS.contains(token) || "idle".equals(token);
            if (replacesUserTask) {
                // 新行动接管 = 不再是"被 @pause_main 按住"态。必须清，否则 isPaused() 永久为真，
                // 会让 LivenessDigest 把后续真卡死当成"合法静止"而漏报。
                _paused = false;
                supersedeActive(token);
            }
            executor.execute(cmd);
            // 瞬时命令没有任务生命期，没有 TaskFinishedEvent 可等 —— 受理即完成
            if (INSTANT.contains(token)) {
                if (replacesUserTask) {
                    // idle 是"按住"态：受理即 done，但 bot 会一直站着等你下一条指令
                    setActiveHold(actionId, token, "待命中，等你下一条指令");
                }
                emitDone(actionId, token, "已执行：" + token);
                return;
            }
            // 其余（goto/follow/give）等顶层任务结束时报 action_done（见 onTaskFinished），
            // 未结束就结束的（被顶替/被 resume 抢占）由对端超时兜底。
            setActiveTaskAction(actionId, token, cmd);
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
        // 被暂停的活跃行动判 interrupted（终态：pause 语义 = "停手头一切"，不会自动恢复）。
        // 先记下来，cancel 之后再发 —— 时机上避开 _interrupting 窗口内的伪关联。
        ActiveAction interrupted = _active;
        // cancel 会同步 publish TaskFinishedEvent（UserTaskChain.cancel → onTaskFinish），
        // 窗口内抑制伪 task_done。
        _interrupting = true;
        try {
            _mod.cancelUserTask();
        } finally {
            _interrupting = false;
        }
        if (interrupted != null && TASK_ACTIONS.contains(interrupted.cmd)) {
            _pending.removeIf(e -> e.actionId.equals(interrupted.actionId));
            emitDone(interrupted.actionId, interrupted.cmd, "被 @pause_main 暂停",
                    "interrupted", "被 @pause_main 暂停");
        }
        _active = null;
        _activeSinceMs = 0;
        resetProgress();
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
        String status = "completed";
        String reason = null;
        // follow 唯一的自然结束路径 = 走到最后已知位置但目标已不在加载范围（FollowPlayerTask 内 stop），
        // 归 failed 而非 completed（"追不到"是终态失败，不是干完了）。
        if ("follow".equals(p.token) && !isPlayerLoaded(_activePlayerName)) {
            status = "failed";
            reason = "目标不在加载范围/不在线";
            text = "做不到：目标不在加载范围";
        }
        // B'：goto/follow/give 完成 → 自动回主线（idle/pause_main 不登记 pending，不在此列）。
        if (resumeMainline()) {
            text += "，已自动回主线";
        }
        emitDone(p.actionId, p.token, text, status, reason);
        ActiveAction a = _active;
        if (a != null && a.actionId.equals(p.actionId)) {
            _active = null;
            _activeSinceMs = 0;
            resetProgress();
        }
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
        emitDone(actionId, token, text, null, null);
    }

    private void emitDone(String actionId, String token, String text, String status, String reason) {
        _buffer.emitImmediate("action_done", "[" + actionId + "] " + text, false,
                detailJson(actionId, token, status, reason));
    }

    // ===================== 现场执行状态（1002 C 块：activeAction / botStatus） =====================

    /** 当前活跃行动（HTTP 线程只读；null = 无活跃行动 → /status 省略该键）。 */
    ActiveAction activeAction() {
        return _active;
    }

    /** 是否处于"按住"态（idle / pause_main）——此时"不动"是设计意图，不算卡死。 */
    boolean isHolding() {
        ActiveAction a = _active;
        return a != null && !TASK_ACTIONS.contains(a.cmd);
    }

    /** 登记一个"有任务生命期"的活跃行动（解析命令目标供进度探针用）。 */
    private void setActiveTaskAction(String actionId, String token, String cmd) {
        _activeGotoTarget = "goto".equals(token) ? parseGoto(cmd) : null;
        _activePlayerName = ("follow".equals(token) || "give".equals(token)) ? parseNameArg(cmd) : null;
        String[] gi = "give".equals(token) ? parseGiveItem(cmd) : null;
        _activeItemToken = gi == null ? null : gi[0];
        _activeItemCount = gi == null ? 1 : parseIntSafe(gi[1], 1);
        long now = System.currentTimeMillis();
        _activeSinceMs = now;
        _progressKey = null;
        _progressAtMs = now;
        _active = new ActiveAction(actionId, token, "running", "已受理，正在启动", 0);
    }

    /** 登记一个"按住"态行动（idle / pause_main）：无终点，detail 固定，不判进度。 */
    private void setActiveHold(String actionId, String token, String detail) {
        _activeGotoTarget = null;
        _activePlayerName = null;
        _activeItemToken = null;
        _activeSinceMs = System.currentTimeMillis();
        _active = new ActiveAction(actionId, token, "running", detail, 0);
    }

    /**
     * 新命令会替换手头用户任务：旧活跃行动已被 setTask 顶替，其 done 永不会来
     * → 发 {@code action_done(status=cancelled, reason="被 @X 顶替")} 并清掉。
     * "按住"态（idle/pause）被顶替时静默清除（它不是有终点的行动，无需回执）。
     */
    private void supersedeActive(String newToken) {
        ActiveAction old = _active;
        if (old == null) return;
        if (TASK_ACTIONS.contains(old.cmd)) {
            _pending.removeIf(e -> e.actionId.equals(old.actionId));
            emitDone(old.actionId, old.cmd, "被 @" + newToken + " 顶替",
                    "cancelled", "被 @" + newToken + " 顶替");
        }
        _active = null;
        _activeSinceMs = 0;
        resetProgress();
    }

    /** 客户端线程每 tick 调用（BridgeHttpServer 的 ClientTickEvent）。 */
    void onClientTick() {
        try {
            ClientPlayerEntity p = AltoClef.inGame() ? _mod.getPlayer() : null;
            ActiveAction a = _active;
            if (a == null || p == null) return;              // 离线/切换中：不判，离线快照本就不出该键
            if (!TASK_ACTIONS.contains(a.cmd)) return;        // idle/pause 按住态，detail 固定
            evaluateProgress(p, a);
        } catch (Throwable ignored) {
        }
    }

    /** 进度探针：算进度计数器 + 中文现场，并据此定 phase（running/blocked）与 failed。 */
    private void evaluateProgress(ClientPlayerEntity p, ActiveAction a) {
        Probe probe = probe(a, p);
        if (probe == null || probe.key == null) return;
        long now = System.currentTimeMillis();
        if (_progressKey == null || !_progressKey.equals(probe.key)) {
            _progressKey = probe.key;
            _progressAtMs = now;
        }
        long stalledSec = (now - _progressAtMs) / 1000;

        // failed：只在"确定的做不到"上发（goto 长时间算不出路 / follow 目标从未出现）
        long failAfter = "goto".equals(a.cmd) ? FAIL_GOTO_SEC
                : "follow".equals(a.cmd) ? FAIL_FOLLOW_SEC : Long.MAX_VALUE;
        if (probe.hardFailReason != null && stalledSec >= failAfter) {
            _pending.removeIf(e -> e.actionId.equals(a.actionId));
            emitDone(a.actionId, a.cmd, "做不到，放弃：" + probe.detail, "failed", probe.hardFailReason);
            _active = null;
            _activeSinceMs = 0;
            resetProgress();
            return;
        }

        String phase = stalledSec >= BLOCKED_SEC ? "blocked" : "running";
        String detail = probe.detail;
        if ("blocked".equals(phase) && (detail == null || detail.isEmpty())) {
            detail = "卡住 " + stalledSec + "s 无进展";
        }
        int sinceSec = (int) ((now - _activeSinceMs) / 1000);
        // 无变化就不重建（_active 每 tick 被读，避免 20Hz 分配）
        if (phase.equals(a.phase) && detail.equals(a.detail) && sinceSec == a.sinceSec) {
            return;
        }
        _active = new ActiveAction(a.actionId, a.cmd, phase, detail, sinceSec);
    }

    /** 一次进度采样：进度 key（变了=在推进）、中文现场、可选的"确定失败"原因。 */
    private static final class Probe {
        final String key;
        final String detail;
        final String hardFailReason; // 非 null 表示"若持续无进展即可判 failed"

        Probe(String key, String detail, String hardFailReason) {
            this.key = key;
            this.detail = detail;
            this.hardFailReason = hardFailReason;
        }
    }

    private Probe probe(ActiveAction a, ClientPlayerEntity p) {
        // 被更高优先级内部任务抢占（战斗/吃食/逃生/死亡恢复）→ 说明原因，但不当终态
        String interruption = currentInterruption();
        String prefix = interruption == null ? "" : "被打断（" + interruption + "），稍后继续；";
        try {
            return switch (a.cmd) {
                case "goto" -> probeGoto(p, prefix);
                case "follow" -> probeFollow(p, prefix);
                case "give" -> probeGive(prefix);
                default -> null;
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** 若活跃行动的用户任务正被更高优先级链挂起（战斗/吃食/逃生…），返回该链名；否则 null。 */
    private String currentInterruption() {
        try {
            var runner = _mod.getTaskRunner();
            if (runner == null) return null;
            TaskChain winner = runner.getCurrentTaskChain();
            var user = _mod.getUserTaskChain();
            if (winner == null || user == null || winner == user) return null;
            if (user.getCurrentTask() == null) return null; // 用户任务已没了 = 不是"暂时挂起"
            return winner.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    private Probe probeGoto(ClientPlayerEntity p, String prefix) {
        boolean available = baritoneAvailable();
        boolean pathing = available && baritonePathing();
        boolean pathPresent = available && baritonePathPresent();
        String barKey = pathing + "/" + pathPresent;
        GoTarget g = _activeGotoTarget;
        if (g == null) {
            return new Probe("goto|unknown|" + barKey, prefix + "导航中", null);
        }
        if (g.kind == GoTarget.DIM_ONLY) {
            return new Probe("goto|dim|" + g.dimZh + "|" + barKey, prefix + "前往" + g.dimZh, null);
        }
        Dimension cur = currentDim();
        if (g.dim != null && cur != null && cur != g.dim) {
            return new Probe("goto|todim|" + g.dimZh + "|" + barKey,
                    prefix + "前往" + g.dimZh + "中", null);
        }
        if (g.kind == GoTarget.Y_ONLY) {
            double dy = Math.abs(p.getY() - g.y);
            if (dy <= 1) return new Probe("goto|y|done|" + barKey, prefix + "已到位，等待确认", null);
            return new Probe("goto|y|" + (int) (dy / 2) + "|" + barKey,
                    prefix + "上下移动中（目标 y=" + (int) g.y + "）", null);
        }
        double dist = g.horizontalDistanceTo(p.getPos());
        // (1002 15:4x 对端实测) 严格到位判据：baritone GoalBlock 无容差，feet 所在 BlockPos
        // 必须**正好**等于目标格。旧逻辑只看水平 ≤2 就报"已到位"，目标格站不进
        // （雪层/非完整方块/被占）时会误导 LLM 对外报"我到了"。
        BlockPos goalCell = new BlockPos((int) Math.floor(g.x), (int) Math.floor(g.y), (int) Math.floor(g.z));
        boolean arrived = p.getBlockPos().equals(goalCell);
        boolean noPath = available && !pathing && !pathPresent;
        String bucketKey;
        String detail;
        String hardFail = null;
        if (arrived) {
            bucketKey = "done";
            detail = prefix + "已到位，等待确认";
        } else if (dist <= 2) {
            // 水平已到但 feet 格进不去：key 固定，让"长时间站不进"能累积到 hardFail 阈值
            // （否则每拍 bucket 抖动会不断重置 stalledSec，永远发不出 failed）。
            bucketKey = "unenterable";
            detail = prefix + "目标格站不进去（雪层/遮挡？），正在重找路径";
            hardFail = "目标格无法站立（水平已到但 feet 格进不去）";
        } else {
            bucketKey = String.valueOf((int) (dist / 2));
            if (noPath) {
                detail = prefix + "受阻：算不出路径";
                hardFail = "坐标不可达（长时间算不出路径）";
            } else {
                detail = prefix + "导航中，剩余" + Math.max(1, (int) Math.ceil(dist)) + "格";
            }
        }
        return new Probe("goto|xz|" + bucketKey + "|" + barKey, detail, hardFail);
    }

    private Probe probeFollow(ClientPlayerEntity p, String prefix) {
        String name = _activePlayerName;
        var et = _mod.getEntityTracker();
        if (et == null || name == null) {
            return new Probe("follow|na", prefix + "跟随中", null);
        }
        if (et.isPlayerLoaded(name)) {
            double d = et.getPlayerEntity(name)
                    .map(e -> e.getPos().distanceTo(p.getPos())).orElse(0.0);
            return new Probe("follow|vis|" + (int) (d / 2) + "|" + name,
                    prefix + "跟随中，目标可见距离" + Math.round(d) + "格", null);
        }
        Optional<Vec3d> recent = et.getPlayerMostRecentPosition(name);
        if (recent.isPresent()) {
            double d = recent.get().distanceTo(p.getPos());
            return new Probe("follow|lost|" + (int) (d / 2) + "|" + name,
                    prefix + "受阻：目标不在加载范围（正赶往最后位置，距" + Math.round(d) + "格）", null);
        }
        // 从未见过该玩家 → 持续无进展即判 failed（"目标始终不在线"）
        return new Probe("follow|offline|" + name, prefix + "受阻：目标不在线", "目标始终不在线");
    }

    private Probe probeGive(String prefix) {
        Task top = null;
        try {
            var chain = _mod.getUserTaskChain();
            top = chain == null ? null : chain.getCurrentTask();
        } catch (Throwable ignored) {
        }
        String state = top == null ? "" : safeDebugState(top);
        String key = "give";
        String detail;
        if (state.contains("Throw")) {
            key += "|throw";
            detail = prefix + "已抛出，等待对方拾取";
        } else if (state.contains("Collecting")) {
            Integer have = countItem(_activeItemToken);
            String zh = itemZh(_activeItemToken);
            if (have != null) {
                key += "|collect|" + have;
                detail = prefix + "背包没有，正在收集" + zh + have + "/" + _activeItemCount;
            } else {
                key += "|collect";
                detail = prefix + "背包没有，正在收集" + zh;
            }
        } else if (state.contains("Going to player")) {
            key += "|approach";
            detail = prefix + "走近中";
        } else if (state.contains("No player found")) {
            key += "|lost|" + _activePlayerName;
            detail = prefix + "受阻：目标不在加载范围";
        } else {
            key += "|run";
            detail = prefix + "进行中";
        }
        return new Probe(key, detail, null);
    }

    // ---- 进度探针用的小工具（全部 try/catch，绝不把异常抛进 tick）----

    private static String safeDebugState(Task t) {
        try {
            String s = t.debugState();
            return s == null ? "" : s;
        } catch (Throwable e) {
            return "";
        }
    }

    private boolean baritoneAvailable() {
        try {
            return _mod.getClientBaritone() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean baritonePathing() {
        try {
            return _mod.getClientBaritone().getPathingBehavior().isPathing();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean baritonePathPresent() {
        try {
            return _mod.getClientBaritone().getPathingBehavior().getPath().isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    private Dimension currentDim() {
        try {
            return WorldHelper.getCurrentDimension();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isPlayerLoaded(String name) {
        try {
            var et = _mod.getEntityTracker();
            return name != null && et != null && et.isPlayerLoaded(name);
        } catch (Throwable t) {
            return false;
        }
    }

    private Integer countItem(String token) {
        try {
            Item it = resolveItem(token);
            if (it == null) return null;
            var st = _mod.getItemStorage();
            return st == null ? null : st.getItemCount(it);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Item resolveItem(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            Identifier id = token.contains(":")
                    ? new Identifier(token) : new Identifier("minecraft", token);
            Item it = Registry.ITEM.get(id);
            return it == Items.AIR ? null : it;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String itemZh(String token) {
        try {
            Item it = resolveItem(token);
            if (it == null) return "材料";
            String zh = ItemNames.name(new ItemStack(it));
            return zh == null || zh.isEmpty() ? "材料" : zh;
        } catch (Throwable t) {
            return "材料";
        }
    }

    // ---- 命令文本解析（dispatch 时一次，供后续每 tick 探针复用）----

    /** 解析 follow/give 的目标玩家名（第一个参数）。 */
    private static String parseNameArg(String cmd) {
        String[] t = argsOf(cmd);
        return t.length > 0 ? t[0] : null;
    }

    /** 解析 give 的 (item, count)。 */
    private static String[] parseGiveItem(String cmd) {
        String[] t = argsOf(cmd);
        if (t.length < 2) return null;
        String count = t.length >= 3 ? t[2] : "1";
        return new String[]{t[1], count};
    }

    /** 去掉命令前缀与命令名，取参数数组（并剥掉 ';' 多命令分隔）。 */
    private static String[] argsOf(String cmd) {
        if (cmd == null) return new String[0];
        String s = cmd.trim();
        int sp = s.indexOf(' ');
        if (sp < 0) return new String[0];
        String rest = s.substring(sp + 1).trim();
        int semi = rest.indexOf(';');
        if (semi >= 0) rest = rest.substring(0, semi).trim();
        if (rest.isEmpty()) return new String[0];
        return rest.split("\\s+");
    }

    /** 解析 goto 的坐标/维度参数（形态见 GotoCommand：x y z / x z / y / 维度）。 */
    private static GoTarget parseGoto(String cmd) {
        try {
            String[] parts = argsOf(cmd);
            List<Double> nums = new ArrayList<>();
            Dimension dim = null;
            for (String t : parts) {
                Dimension d = dimFromToken(t);
                if (d != null) {
                    dim = d;
                    continue;
                }
                try {
                    nums.add(Double.parseDouble(t));
                } catch (NumberFormatException ignored) {
                }
            }
            String dz = dimZh(dim);
            if (nums.size() >= 3) return new GoTarget(GoTarget.XYZ, nums.get(0), nums.get(1), nums.get(2), dim, dz);
            if (nums.size() == 2) return new GoTarget(GoTarget.XZ, nums.get(0), 0, nums.get(1), dim, dz);
            if (nums.size() == 1) return new GoTarget(GoTarget.Y_ONLY, 0, nums.get(0), 0, dim, dz);
            if (dim != null) return new GoTarget(GoTarget.DIM_ONLY, 0, 0, 0, dim, dz);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Dimension dimFromToken(String t) {
        if (t == null) return null;
        return switch (t.toLowerCase()) {
            case "overworld", "主世界" -> Dimension.OVERWORLD;
            case "nether", "the_nether", "下界" -> Dimension.NETHER;
            case "end", "the_end", "末地" -> Dimension.END;
            default -> null;
        };
    }

    private static String dimZh(Dimension d) {
        if (d == null) return null;
        return switch (d) {
            case NETHER -> "下界";
            case END -> "末地";
            default -> "主世界";
        };
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private void resetProgress() {
        _progressKey = null;
        _progressAtMs = 0;
    }

    /** goto 目标（从命令文本解析；kind 见常量）。 */
    private static final class GoTarget {
        static final int DIM_ONLY = 0, Y_ONLY = 1, XZ = 2, XYZ = 3;
        final int kind;
        final double x, y, z;
        final Dimension dim;   // 可 null
        final String dimZh;    // 可 null

        GoTarget(int kind, double x, double y, double z, Dimension dim, String dimZh) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.dimZh = dimZh;
        }

        double horizontalDistanceTo(Vec3d p) {
            if (kind == XYZ || kind == XZ) {
                double dx = p.x - x, dz = p.z - z;
                return Math.sqrt(dx * dx + dz * dz);
            }
            return 0;
        }
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
