package adris.altoclef.commandsystem;

/**
 * 命令自报结果（2026-10-03）：让 bridge 的 {@code action_done} 能带上"到底成没成、为什么"。
 *
 * <p>动机（用户 1003）：{@code runOnClientThread} 原本只回 {@code "已执行：<token>"}，
 * 命令内部的失败只打在聊天/日志里，AI 拿不到 —— 典型三例：
 * <ul>
 *   <li>{@code @sleep bed-only} 附近没床 → 命令放弃，回执却说"已执行"；</li>
 *   <li>{@code @give 小影 铁锭} 背包没有铁锭 → {@code GiveCommand} 直接 finish、**不起任务**
 *       ⇒ 没有 TaskFinishedEvent ⇒ bridge 的 action_done **永远不会来**，AI 只能等超时；</li>
 *   <li>{@code @quota blaze_rod}（少参数）→ 参数解析抛 CommandException，被
 *       {@code CommandExecutor.execute(cmd)} 吞进 {@code Debug.logWarning}。</li>
 * </ul>
 *
 * <p>用法：命令在 {@code call()} 里显式 {@link #ok}/{@link #fail}；bridge 在派发前
 * {@link #clear()}、派发后读。**没上报 → 退回原通用文案**（不强制所有命令都报）。
 * 解析类异常由 bridge 侧从 {@code CommandExecutor} 的异常回调里补 —— 命令本体无需处理。
 *
 * <p>线程：命令与 bridge 派发都在<b>客户端线程</b>，{@code clear → execute → 读} 同线程
 * 一气呵成，故用普通字段即可（无跨线程访问）。
 */
public final class CommandFeedback {

    private static boolean _failed;
    private static String _msg;

    private CommandFeedback() {}

    /** 派发前清空。 */
    public static void clear() {
        _failed = false;
        _msg = null;
    }

    /** 报告成功（msg = 给 AI 看的一句中文现场，会进 action_done 文本与 detail）。 */
    public static void ok(String msg) {
        _failed = false;
        _msg = msg;
    }

    /** 报告失败（msg = 做不到的原因，会进 action_done 的 text + detail.reason）。 */
    public static void fail(String msg) {
        _failed = true;
        _msg = msg;
    }

    /** 是否上报过。 */
    public static boolean has() {
        return _msg != null;
    }

    /** 上报的是否为失败。 */
    public static boolean failed() {
        return _failed;
    }

    /** 上报的文案（可能为 null = 未上报）。 */
    public static String message() {
        return _msg;
    }
}
