package adris.altoclef.bridge;

import adris.altoclef.Debug;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件缓冲：单调游标分页 + 3s 同类聚合 + 容量上限。
 *
 * <p>线程模型（契约 §3.5 实现约束）：
 * <ul>
 *   <li><b>生产者</b>：{@link EventCollector}（在客户端线程 / EventBus 发布线程，
 *       两者都是客户端线程）调 {@link #collectOrAccumulate} 与 {@link #emitImmediate}。</li>
 *   <li><b>刷出</b>：{@link #flushPending} 由客户端 tick 与 /events poll 时机调用，
 *       把 3s 窗口到期的累积项转成 {@link BridgeEvent} 进 deque。</li>
 *   <li><b>消费者</b>：/events handler 调 {@link #poll}，只读 deque，线程安全。</li>
 * </ul>
 *
 * <p>游标语义（契约 §3.2 /events）：
 * <ul>
 *   <li>空 cursor = 对齐到当前时刻、不返回历史（首次轮询用空串）；</li>
 *   <li>非空 cursor = 返回该游标之后（id &gt; cursor）的事件。</li>
 * </ul>
 * 实现为单调递增的 long id：空 cursor → cursor = 当前最大 id（= 对齐到 now），
 * 返回空；下次带这个 cursor 来轮询即可拿到之后到达的事件。
 *
 * <p>游标跨会话单调（1001 缺陷②）：id 以 epoch millis 为基底
 * （{@code id = max(上次id+1, 当前墙钟ms)}），bot 重启后新 id 必然大于上一会话
 * 全部 id，旧游标不会吞掉新会话事件。对"游标大于本会话最大值"的异常请求，
 * poll 返回对齐后的 nextCursor（= 当前最大 id），不会返回越界游标。
 */
final class EventBuffer {

    /** 同类聚合窗口（契约 §3.4 "3s 窗口内同类事件合并"）。 */
    private static final long AGGREGATE_WINDOW_MS = 3000;
    /** 同 (type,itemKey) 事件冷却窗：期内再次到期不落定，计数合并延后（1001 缺陷③）。 */
    private static final long SAME_KEY_COOLDOWN_MS = 60_000;
    /** deque 容量上限，防无界增长；超过则从最旧端裁剪。 */
    private static final int MAX_BUFFERED = 500;

    private final ConcurrentLinkedDeque<BridgeEvent> _events = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, Pending> _pending = new ConcurrentHashMap<>();
    /** 最近一次落定的墙钟（按 aggKey），冷却窗判定用。 */
    private final ConcurrentHashMap<String, Long> _lastEmitMs = new ConcurrentHashMap<>();
    /** 全局单调 id 计数器：基底 epoch millis（跨重启单调）。 */
    private final AtomicLong _idCounter = new AtomicLong(0);
    private final String _botId;

    EventBuffer(String botId) {
        this._botId = botId;
    }

    /**
     * 累积一条采集类事件（背包增量）。同类（同 type+itemKey）在 3s 窗口内合并计数，
     * 到期由 {@link #flushPending} 转成一条 {@link BridgeEvent}。
     *
     * @param type        collected / rare_item
     * @param itemKey     聚合 key（物品注册名，如 "iron_ore"）
     * @param chineseName 中文名（如 "铁矿石"）
     * @param verb        动词（"挖到" / "捡到"）
     * @param delta       本次增量
     * @param strong      是否强制开轮（采集类应为 false）
     */
    void collectOrAccumulate(String type, String itemKey, String chineseName, String verb,
                             int delta, boolean strong) {
        String aggKey = type + ":" + itemKey;
        long now = System.currentTimeMillis();
        _pending.compute(aggKey, (k, existing) -> {
            if (existing == null) {
                return new Pending(type, itemKey, chineseName, verb, delta,
                        now, now + AGGREGATE_WINDOW_MS, strong);
            }
            // 同 key 累加，但刷新窗口会无限延后聚合 —— 故窗口不刷新，到点即刷。
            existing.count += delta;
            existing.strong = existing.strong || strong;
            return existing;
        });
    }

    /**
     * 立即发一条事件（不聚合）：死亡、task_done、chat、help 等离散事件。
     * 同 type 在极短时间内可能重复，这里不做去重 —— 死亡/task_done 本就低频，
     * chat/help 由 EventCollector 自身做请求识别后才调用。
     */
    void emitImmediate(String type, String text, boolean strong) {
        emitImmediate(type, text, strong, null);
    }

    /** 带结构化 detail（已是 JSON 片段）的立即事件，用于行动回执。 */
    void emitImmediate(String type, String text, boolean strong, String detail) {
        long id = allocId();
        BridgeEvent e = new BridgeEvent(id, _botId, BotSnapshot.nowIso(), type, text, strong, detail);
        _events.addLast(e);
        trim();
    }

    /** 把 3s 窗口到期的累积项刷出。客户端 tick 与 /events poll 时调用。 */
    void flushPending() {
        long now = System.currentTimeMillis();
        // 不能在 forEach 里直接改 map，先收集到期项再逐个移除。
        List<Pending> expired = new ArrayList<>();
        for (Pending p : _pending.values()) {
            if (p.deadlineMs <= now) {
                expired.add(p);
            }
        }
        for (Pending p : expired) {
            // removeIf 等价：用 compute 保证只移除我们这把（万一期间又被更新）
            _pending.computeIfPresent(p.aggKey, (k, cur) -> cur == p ? null : cur);
            if (p.count <= 0) continue;
            // 同类冷却窗（缺陷③）：60s 内同 (type,itemKey) 不落定，计数合并、
            // 延后到冷却期满再发 —— 盾牌"造→掉→捡"循环不再刷 35 条。
            Long last = _lastEmitMs.get(p.aggKey);
            if (last != null && now - last < SAME_KEY_COOLDOWN_MS) {
                _pending.put(p.aggKey, new Pending(p.type, p.itemKey, p.chineseName, p.verb,
                        p.count, now, last + SAME_KEY_COOLDOWN_MS, p.strong));
                continue;
            }
            _lastEmitMs.put(p.aggKey, now);
            String text = (p.count == 1)
                    ? p.verb + "了" + p.chineseName
                    : p.verb + "了 " + p.count + " 个" + p.chineseName;
            long id = allocId();
            _events.addLast(new BridgeEvent(id, _botId, BotSnapshot.nowIso(), p.type, text, p.strong));
        }
        if (!expired.isEmpty()) trim();
    }

    /**
     * 分配严格单调的下一事件 id：max(上次+1, 当前墙钟ms)。
     * 同毫秒多次调用也严格递增（+1 保底）；跨重启以墙钟为基底，新会话 id
     * 必然大于旧会话全部 id。
     */
    private long allocId() {
        return _idCounter.updateAndGet(cur -> Math.max(cur + 1, System.currentTimeMillis()));
    }

    /**
     * 按游标拉取已落定的事件。
     *
     * @param cursorStr 游标字符串；空/null = 对齐到当前时刻（返回空、nextCursor=当前最大id）
     * @return [事件列表, nextCursor 字符串]
     */
    PollResult poll(String cursorStr) {
        // 先刷一次 pending，让刚到期的也能被本次轮询取到。
        flushPending();

        long cursor = parseCursor(cursorStr);
        long maxId = currentMaxId();
        if (cursor < 0 || cursor >= maxId) {
            // 空 cursor 或游标已到当前最大：对齐到 now，返回空。
            // 下次用 maxId 当 cursor 来，即可拿到之后到达的事件。
            return new PollResult(new ArrayList<>(), Long.toString(maxId));
        }

        // 取 id > cursor 的事件。deque 从 head(最旧) → tail(最新)，id 单调递增，
        // 找到第一个 id>cursor 后其余连续都满足。
        List<BridgeEvent> out = new ArrayList<>();
        for (BridgeEvent e : _events) {
            if (e.id > cursor) {
                out.add(e);
            }
            // 不 break：deque 可能因 trim 中段不连续，逐个判断最稳。
        }
        long nextCursor = out.isEmpty() ? maxId : out.get(out.size() - 1).id;
        return new PollResult(out, Long.toString(nextCursor));
    }

    private long parseCursor(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty()) return -1;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return -1; // 非法游标按"对齐到 now"处理
        }
    }

    private long currentMaxId() {
        return _idCounter.get();
    }

    private void trim() {
        while (_events.size() > MAX_BUFFERED) {
            if (_events.pollFirst() == null) break;
        }
    }

    /** 调试用：当前已落定事件数 + 待聚合项数。 */
    String debugCounts() {
        return "flushed=" + _events.size() + " pending=" + _pending.size();
    }

    /** 一条待聚合的累积项。 */
    private static final class Pending {
        final String aggKey;
        final String type;
        final String itemKey;
        final String chineseName;
        final String verb;
        int count;
        final long firstTsMs;
        final long deadlineMs;
        boolean strong;

        Pending(String type, String itemKey, String chineseName, String verb, int count,
                long firstTsMs, long deadlineMs, boolean strong) {
            this.type = type;
            this.itemKey = itemKey;
            this.chineseName = chineseName;
            this.verb = verb;
            this.count = count;
            this.firstTsMs = firstTsMs;
            this.deadlineMs = deadlineMs;
            this.strong = strong;
            this.aggKey = type + ":" + itemKey;
        }
    }

    /** poll 结果：事件列表 + 下一个游标字符串。 */
    static final class PollResult {
        final List<BridgeEvent> events;
        final String nextCursor;
        PollResult(List<BridgeEvent> events, String nextCursor) {
            this.events = events;
            this.nextCursor = nextCursor;
        }
    }
}
