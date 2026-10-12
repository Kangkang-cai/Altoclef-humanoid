package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * /status 的 {@code botStatus} 采集（1002 B 块）：让 LLM 明白"不是我的指令没生效，
 * 是身体本身打结了"。
 *
 * <p>判据（契约：位置 + 背包 + 任务态，三者全不变持续 N 秒 → stuck）：
 * <ul>
 *   <li><b>位置</b>：锚点位移 &gt;0.5 格（沿用 {@code AnomalyWatchdog.StallDetector}
 *       口径 —— 卡在方块里时 bot 常在 1 格内微抖，精确比较抓不到）；</li>
 *   <li><b>背包</b>：全量物品计数和（挖到/消耗/搬运都会变）；</li>
 *   <li><b>任务态</b>：胜出链名 + 最深任务类名 + 该任务 {@code debugState()}。</li>
 * </ul>
 * 三者任一变化即视为"在推进"，重置计时。签名取得比纯位置判据丰富，
 * 是为了压低误报（宁可漏报：误报一条 strong 事件会误导 LLM 认为 bot 坏了）。
 *
 * <p>刻意排除的正常静止（这些"不动"是设计意图，直接静默重置、不发事件）：
 * 打开 GUI（翻箱子/合成/看背包）、睡觉、游戏暂停、不在游戏中、无任务在跑（等指令）、
 * 以及动作侧的"按住"态（{@code @idle} / {@code @pause_main}，语义就是立正等指令）。
 *
 * <p>三个状态：{@code ok} / {@code stuck}（有任务但长时间无进展）/ {@code recovering}
 * （刚从 stuck 里出来，可能正在重新寻路/重启子任务）。进入 stuck 发一条 strong
 * {@code bot_stuck}；恢复发一条 {@code bot_recovered}（不强开轮）。
 *
 * <p>线程模型：仅客户端线程（BridgeHttpServer 的 ClientTickEvent）调用；
 * status/reason 为 volatile，供 HTTP 线程只读。
 */
final class LivenessDigest {

    /** 三者全不变多久 → stuck（契约 30s）。 */
    private static final long STUCK_MS = 30_000;
    /** 恢复判定：5s 内有进展即认为已脱困。 */
    private static final long RESUME_EPS_MS = 5_000;
    /** 恢复后显示 recovering 的时长，之后回 ok。 */
    private static final long RECOVER_MS = 10_000;
    /** 位置"动过"阈值（格）。 */
    private static final double MOVE_EPS = 0.5;
    private static final double MOVE_EPS_SQ = MOVE_EPS * MOVE_EPS;

    private final AltoClef _mod;
    private final EventBuffer _buffer;
    /** 用于识别"按住"态（idle/pause）——那时静止是设计意图。 */
    private final ActionDispatcher _dispatcher;

    // ---- 跨 tick 状态（仅客户端线程）----
    private boolean _armed = false;
    private Vec3d _anchor;
    private String _lastSig = "";
    private int _lastInvTotal = -1;
    private long _progressAtMs = 0;
    private boolean _stuck = false;
    private long _recoveredUntilMs = 0;

    // ---- HTTP 线程只读 ----
    private volatile String _status = "ok";
    private volatile String _reason = "";

    LivenessDigest(AltoClef mod, EventBuffer buffer, ActionDispatcher dispatcher) {
        this._mod = mod;
        this._buffer = buffer;
        this._dispatcher = dispatcher;
    }

    String status() {
        return _status;
    }

    /** 仅 stuck / recovering 时非空。 */
    String reason() {
        return _reason;
    }

    /**
     * {@code bot_stuck} 的 detail：附带当前活跃行动，供对端回执"你那条 @goto 的任务卡住了"。
     * 无活跃行动（主线/内部任务卡住）时两个字段为空串 ⇒ 对端可区分"是不是它刚下的指令卡的"。
     * 任何异常返回 null（detail 省略，事件本体照发）。
     */
    private String stuckDetail() {
        try {
            ActiveAction a = _dispatcher == null ? null : _dispatcher.activeAction();
            StringBuilder sb = new StringBuilder(48);
            sb.append("{\"actionId\":");
            BridgeJson.str(sb, a == null ? "" : a.actionId);
            sb.append(",\"cmd\":");
            BridgeJson.str(sb, a == null ? "" : a.cmd);
            sb.append('}');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 客户端线程每 tick 调用。任何异常都不能影响主循环。 */
    void tick() {
        try {
            ClientPlayerEntity p = AltoClef.inGame() ? _mod.getPlayer() : null;
            MinecraftClient mc = MinecraftClient.getInstance();
            TaskChain chain = null;
            try {
                var runner = _mod.getTaskRunner();
                chain = runner == null ? null : runner.getCurrentTaskChain();
            } catch (Throwable ignored) {
            }
            List<Task> stack = chain == null ? null : chain.getTasks();
            boolean hasTask = stack != null && !stack.isEmpty();

            boolean legitStill = p == null
                    || (mc != null && mc.currentScreen != null)
                    || (mc != null && mc.isPaused())
                    || p.isSleeping()
                    || !hasTask
                    || isFollowHolding(stack)
                    || (_dispatcher != null && (_dispatcher.isHolding() || _dispatcher.isPaused()));
            if (legitStill) {
                // 这些场景下的"不动"合法：静默重置，不发任何事件
                _armed = false;
                _anchor = null;
                _lastSig = "";
                _lastInvTotal = -1;
                _stuck = false;
                _recoveredUntilMs = 0;
                _status = "ok";
                _reason = "";
                return;
            }

            long now = System.currentTimeMillis();
            Vec3d pos = p.getPos();
            int invTotal = inventoryTotal(p);
            String sig = signature(chain, stack);
            boolean moved = _anchor == null || pos.squaredDistanceTo(_anchor) > MOVE_EPS_SQ;
            boolean changed = !_armed || moved || !sig.equals(_lastSig) || invTotal != _lastInvTotal;
            _armed = true;
            if (moved) _anchor = pos;
            _lastSig = sig;
            _lastInvTotal = invTotal;
            if (changed) _progressAtMs = now;

            long stillMs = now - _progressAtMs;
            if (_stuck) {
                if (stillMs < RESUME_EPS_MS) {
                    _stuck = false;
                    _recoveredUntilMs = now + RECOVER_MS;
                    _buffer.emitImmediate("bot_recovered", "已从卡住中恢复，继续原任务", false);
                }
            } else if (stillMs >= STUCK_MS) {
                _stuck = true;
                _recoveredUntilMs = 0;
                String taskName = deepestClass(stack);
                _buffer.emitImmediate("bot_stuck",
                        "我在执行的任务卡住了，可能需要重新下指令（" + taskName + " 已 "
                                + (stillMs / 1000) + "s 无进展）", true, stuckDetail());
            }

            if (_stuck) {
                _status = "stuck";
                _reason = "任务 " + deepestClass(stack) + " 卡住 " + (stillMs / 1000) + "s 无进度";
            } else if (now < _recoveredUntilMs) {
                _status = "recovering";
                _reason = "正在重新寻路/重启子任务";
            } else {
                _status = "ok";
                _reason = "";
            }
        } catch (Throwable ignored) {
            _status = "ok";
            _reason = "";
        }
    }

    /**
     * (2026-10-12 修 bot_stuck 误报) follow 贴身站定 = 设计意图的静止：最深任务是
     * {@code FollowPlayerTask} 且已到保持距离（目标实体在视野内）。这种"不动"是跟随的
     * 正常形态，不该报 stuck。
     *
     * <p>1011 22:45 实测：铁头贴 kangkang 2.7 格站定 60s（三元组全不变）→ 误报 bot_stuck，
     * 对端 LLM 又自造"区块没加载"的解释。注意只豁免"贴身站定"这一种 —— FollowPlayerTask
     * 的其它 null 返回路径（空记录等目标进范围/远程接近超时前）仍照常判卡，那是要上报的真停滞。
     */
    private static boolean isFollowHolding(List<Task> stack) {
        if (stack == null || stack.isEmpty()) return false;
        Task deep = stack.get(stack.size() - 1);
        return deep instanceof adris.altoclef.tasks.movement.FollowPlayerTask t && t.isStationaryByDesign();
    }

    /** 任务态签名：链名 + 最深任务类名 + 其 debugState。 */
    private static String signature(TaskChain chain, List<Task> stack) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(chain == null ? "none" : chain.getName()).append('|');
        if (stack != null && !stack.isEmpty()) {
            Task deep = stack.get(stack.size() - 1);
            sb.append(deep.getClass().getSimpleName());
            try {
                String st = deep.debugState();
                if (st != null) sb.append('|').append(st);
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private static String deepestClass(List<Task> stack) {
        if (stack == null || stack.isEmpty()) return "未知任务";
        return stack.get(stack.size() - 1).getClass().getSimpleName();
    }

    private static int inventoryTotal(ClientPlayerEntity p) {
        try {
            var inv = p.getInventory();
            if (inv == null) return 0;
            int t = 0;
            t += countStacks(inv.main);
            t += countStacks(inv.armor);
            t += countStacks(inv.offHand);
            return t;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static int countStacks(List<ItemStack> stacks) {
        int n = 0;
        for (ItemStack s : stacks) {
            if (s != null && !s.isEmpty()) n += s.getCount();
        }
        return n;
    }
}
