package adris.altoclef.bridge;

/**
 * 一条已落定（聚合完毕、分配了单调 id）的事件。
 *
 * <p>不可变。由 {@link EventBuffer} 在聚合窗口到期时构造，进 {@code ConcurrentLinkedDeque}；
 * /events handler 只读。字段口径对齐契约 §3.2 的 /events 响应：
 * <ul>
 *   <li>{@link #id} —— 单调游标，对端用作下次轮询的 cursor；</li>
 *   <li>{@link #botId} —— 本 bot 身份；</li>
 *   <li>{@link #ts} —— ISO8601 字符串；</li>
 *   <li>{@link #type} —— {@code task_done|collected|rare_item|death|chat|help|...}；</li>
 *   <li>{@link #text} —— 中文短句；</li>
 *   <li>{@link #strong} —— 是否强制开轮。仅死亡、对方给物/求助类为 true。</li>
 * </ul>
 */
final class BridgeEvent {

    final long id;
    final String botId;
    final String ts;
    final String type;
    final String text;
    final boolean strong;

    BridgeEvent(long id, String botId, String ts, String type, String text, boolean strong) {
        this.id = id;
        this.botId = botId;
        this.ts = ts;
        this.type = type;
        this.text = text;
        this.strong = strong;
    }
}
