package adris.altoclef.bridge;

/**
 * (2026-10-06 23:1x 用户点名) <b>次 bot 的"当前目的"</b> —— 经 {@code GET /status} 的
 * {@code duty} 键暴露给对端（chat_and_play），让 AI 角色能看出它<b>在为主 bot 做什么</b>。
 *
 * <h3>用户的原话与动机</h3>
 * "次 bot 的很多任务的名称都要相应改变，这样子 ai 角色那边才能知道自己正在做这件事的目的，
 * 比如跟着是为了保护，不进去是帮主 bot 收集……这样子回头就可以直接给主 bot"。
 *
 * <h3>为什么不用 {@code taskDetail}</h3>
 * {@code /status.taskDetail} 是<b>任务链最深层动作</b>的显示串。次 bot 自主跑主线时，那里显示的是
 * 主线的阶段名（{@code Stockpiling wood} / {@code Greedy mining visible ores}）—— 宿主层改的
 * 文案会被深层整个盖掉。所以"目的"必须放在一个<b>与 taskDetail 并列、不被覆盖</b>的字段里。
 *
 * <h3>为什么 main 不输出</h3>
 * 只有 {@code role=sub} 会拉起到引导任务、进而写入本类 ⇒ {@code mode == NONE} ⇒ {@code /status}
 * 整键省略。对端据此一眼分清"这台是不是跟班"。
 *
 * <h3>与 {@code @give} 的衔接</h3>
 * {@link Mode#COLLECT} 就是那个钩子：对端读到它 = "次 bot 现在是流动仓库"，主 bot 缺什么就发
 * {@code @give <次bot> <物品> <数量>}。**不需要**新增 bot 间通道。
 */
public final class SubBotDuty {

    /** 次 bot 当前扮演的角色。取值稳定，对端可硬编码判断。 */
    public enum Mode {
        /** 未设置（非次 bot，或引导任务没在跑）。 */
        NONE,
        /** 开局前置：为主 bot 补木头/食物。 */
        PREP,
        /** 主 bot 正处于"该跟"的阶段 → 贴上去保护。 */
        FOLLOW,
        /** 主 bot 处于其余阶段 → 次 bot 自己在外备货，回头给主 bot。 */
        COLLECT,
        /** 睡眠镜像：陪主 bot 一起躺（多人服全员入睡才跳夜）。 */
        SLEEP
    }

    private static volatile Mode _mode = Mode.NONE;
    private static volatile String _forLeader = "";
    private static volatile String _label = "";

    private SubBotDuty() {
    }

    /**
     * 更新当前目的。每拍调用（volatile 写，开销可忽略）。
     *
     * @param mode     角色枚举
     * @param forLeader 服务对象 = 主 bot 的 MC 玩家名
     * @param label    给人/LLM 看的自然语言说明（如 "替 alice 备货（主bot 正在收烈焰棒）"）
     */
    public static void set(Mode mode, String forLeader, String label) {
        _mode = (mode == null) ? Mode.NONE : mode;
        _forLeader = (forLeader == null) ? "" : forLeader;
        _label = (label == null) ? "" : label;
    }

    /** 清空（引导任务被顶掉/结束时调用）。 */
    public static void clear() {
        set(Mode.NONE, "", "");
    }

    public static Mode mode() {
        return _mode;
    }

    public static String forLeader() {
        return _forLeader;
    }

    public static String label() {
        return _label;
    }
}
