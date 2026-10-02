package adris.altoclef.bridge;

/**
 * /status 的 {@code activeAction} 快照（不可变），描述"当前 AI 行动跑到哪了"。
 *
 * <p>动机（1002 感知缺口）：行动通道此前只有 action_accepted + action_done，中间是黑盒 ——
 * LLM 不知道它派的任务到底在推进还是卡住，{@code @follow} 尤其瞎（追不到就永久沉默）。
 * 本对象让对端**轮询**（不发事件）就能知道现场：
 * <ul>
 *   <li>{@link #phase} —— {@code running}（在推进）/ {@code blocked}（卡住/够不着）；</li>
 *   <li>{@link #detail} —— 中文一句话现场（进度 / 受阻原因）；</li>
 *   <li>{@link #sinceSec} —— 该行动已跑了多少秒。</li>
 * </ul>
 *
 * <p>没有活跃行动时整个 {@code activeAction} 键省略（对端据此省略）。
 * 线程模型：客户端线程构造，HTTP 线程只读（字段全 final）。
 */
final class ActiveAction {

    /** 对端透传（或 bridge 生成）的行动 id，回填，便于对端与自己的行动记录对齐。 */
    final String actionId;
    /** 当前在跑的行动命令名（goto / follow / give / idle / pause_main）。 */
    final String cmd;
    /** running = 在推进；blocked = 卡住/够不着。 */
    final String phase;
    /** 中文一句话现场（进度 / 受阻原因），空串表示暂无描述。 */
    final String detail;
    /** 这个行动已跑的秒数（墙钟）。 */
    final int sinceSec;

    ActiveAction(String actionId, String cmd, String phase, String detail, int sinceSec) {
        this.actionId = actionId;
        this.cmd = cmd;
        this.phase = phase == null || phase.isEmpty() ? "running" : phase;
        this.detail = detail == null ? "" : detail;
        this.sinceSec = Math.max(0, sinceSec);
    }
}
