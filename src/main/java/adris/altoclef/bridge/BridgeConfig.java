package adris.altoclef.bridge;

import adris.altoclef.util.helpers.ConfigHelper;

/**
 * bridge 层（HTTP 服务 + 状态摘要 + 事件缓冲 + 命令入口）的实例级配置。
 *
 * <p>每个 Minecraft 客户端实例（Prism 多开时各自一个 .minecraft 目录）通过
 * {@code altoclef/configs/bridge.json} 独立配置端口与 botId，与 chat_and_play 侧
 * {@code GAME_BOT_PORTS} 一一对应。加载范式与 {@code ButlerConfig} 完全一致：
 * 静态块首次加载 + reload 回调，文件不存在时按 ConfigHelper 省略式保存落盘。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #port} —— HTTP 服务端口，{@code 0} = 不启动 bridge（默认启动 8761）；
 *       绑定地址恒为 {@code 127.0.0.1}，绝不对外暴露。</li>
 *   <li>{@link #botId} —— 本 bot 在聊天室里的身份标识，默认 {@code "bot_a"}，
 *       对齐 chat_and_play 的 {@code GAME_BOT_PORTS} key。</li>
 * </ul>
 */
public class BridgeConfig {

    private static BridgeConfig _instance = new BridgeConfig();
    static {
        // 与 ButlerConfig 同构：静态块加载 + reload 回调更新 _instance。
        ConfigHelper.loadConfig("configs/bridge.json", BridgeConfig::new, BridgeConfig.class,
                newConfig -> _instance = newConfig);
    }

    public static BridgeConfig getInstance() {
        return _instance;
    }

    /**
     * HTTP 服务端口。{@code 0} = 不启动 bridge。默认 8761 与 chat_and_play 的
     * {@code GAME_BOT_PORTS={"bot_a":8761,...}} 首项对齐。第二实例改 8762 等。
     * 绑定地址恒为 127.0.0.1（见 BridgeHttpServer），端口是唯一可调项。
     */
    public int port = 8761;

    /**
     * 本 bot 在聊天室里的身份标识。默认 {@code "bot_a"}，多实例并行时每实例
     * 必须改成不同的值（bot_a / bot_b / ...），与 chat_and_play 侧代理路由对齐。
     */
    public String botId = "bot_a";

    /**
     * AI 行动白名单（阶段三）：只有列在这里的命令允许经 {@code POST /command} 派发，
     * 其余一律 403。默认 9 条（4 行动 + 2 命名地标读写 + 3 只读），见 DESIGN_action_api.md
     * §1.2 A 档 + mod-bridge-landmark.md §4.2。显式置空数组 = 不允许任何行动命令
     * （关闭行动通道）；字段缺省 = 用默认 9 条。
     * ⚠ 已落盘的旧 bridge.json 若含旧 7 条数组会盖掉此默认 —— 部署时需同步补三处配置
     * （定稿 §5）。
     */
    public java.util.List<String> allowedCommands = new java.util.ArrayList<>(java.util.Arrays.asList(
            "goto", "follow", "give", "idle", "coords", "status", "inventory",
            "remember", "forget"));

    /**
     * 行动类命令限流（条/分钟，滑动窗口）。只读命令（coords/status/inventory）不占额度。
     * {@code <=0} = 不限流。
     */
    public int actionRatePerMinute = 6;

    /**
     * 是否允许主线干预伪命令 {@code @pause_main} / {@code @resume_main}（阶段三 ActionDispatcher）。
     */
    public boolean enablePauseResume = true;
}
