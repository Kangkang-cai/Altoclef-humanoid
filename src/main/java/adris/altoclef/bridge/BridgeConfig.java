package adris.altoclef.bridge;

import adris.altoclef.util.helpers.ConfigHelper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
@JsonIgnoreProperties(ignoreUnknown = true)
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
     * (2026-10-06 多 bot 随行, 用户拍板) 本实例的 bot 角色: {@code "main"}（主 bot）
     * 或 {@code "sub"}（次 bot）。
     *
     * <p>本字段是角色的<b>唯一来源</b>, 两个消费方向:
     * <ul>
     *   <li><b>对端</b>: 经 {@code GET /status} 的 {@code role} 键暴露, chat_and_play
     *       直接读即可区分主次, 不需要靠 botId 命名或端口去猜。</li>
     *   <li><b>模组内部</b>: {@link adris.altoclef.util.helpers.BotRole} 读取, 用于把
     *       <b>木头/食物</b>的囤积目标按比例下调 —— 次 bot 跟着主 bot 走, 只要够自己用,
     *       不必像主 bot 那样为整局囤货（用户: "要求不要像主 bot 那么高"）。</li>
     * </ul>
     *
     * <p>取值大小写不敏感; 缺失或未知值一律按 {@code "main"} 处理（保守: 不引入
     * 未预期的降档）。改动后需重连/重载配置生效（与其他配置字段一致）。
     */
    public String role = "main";

    /**
     * (2026-10-06 次 bot 自动引导, 用户拍板"leader 从 bridge.json 读") 主 bot 的
     * <b>MC 游戏内玩家名</b>（如 {@code "qiaoqiao"}），<b>不是</b> bridge 的 {@link #botId}
     * （{@code "bot_1"}）—— {@code @follow} 按游戏内玩家名寻址，两者不是一回事。
     *
     * <p>只有 {@link #role} 为 {@code "sub"} 且本字段非空时，次 bot 才会启用自动引导：
     * 空闲即自动执行"先补木头/食物 → 再随行"的常驻流程，<b>完全不需要对端发命令</b>。
     * 留空 = 关闭自动引导（次 bot 退回"等命令"的旧行为）。
     */
    public String leader = "";

    /**
     * (2026-10-06 阶段跟随, 用户拍板"第二条路") 主 bot 的 <b>bridge 端口</b>（如 {@code 8761}）。
     * 次 bot 会定期拉 {@code http://127.0.0.1:<leaderPort>/status} 读对方的 {@code phase}
     * （主线大阶段枚举 {@link adris.altoclef.tasks.speedrun.BeatPhase}），据此决定此刻
     * <b>"跟随主 bot" 还是 "自己跑主线"</b>。
     *
     * <p><b>为什么需要它</b>：客户端只下发 ~48 格内玩家的<b>位置</b>，看不到对方"在干什么"，
     * 所以"按大任务决定跟不跟"必须有一个跨进程的信息源 —— 本字段就是它（同机 localhost，
     * 不对外暴露，与 bridge 服务本身同为 127.0.0.1 绑定）。
     *
     * <p>{@code <=0}（默认）= 不启用 ⇒ 次 bot <b>恒跟随</b>（即旧语义，零回归）。
     * 改动后需重连/重载配置生效。
     */
    public int leaderPort = 0;

    /**
     * (2026-10-07 用户拍板"关键还是让每个 bot 知道别的 bot 的坐标") <b>坐标共享的同伴端口表</b>：
     * 本机其他 bot 实例的 bridge 端口（如 {@code [8760, 8761, 8762, 8763]}）。
     *
     * <p>本 bot 的后台线程会每 2s 轮询这些端口的 {@code /status}，把对方的
     * {@code player}（游戏内名）+ {@code pos} + {@code dim} 记进
     * {@link PeerPosWatcher} 的坐标表；此后 {@code @follow <远距玩家>} 与本 bot 的
     * 自动引导都能借这份表走到 48 格客户端跟踪范围之外的本机 bot 身边。
     *
     * <p><b>留空</b>（默认）= 用内置默认窗口 {@code 8760~8769}（与既有四实例端口定档一致），
     * 不需要为此改配置。只有端口不落在该窗口内才需要显式写。
     *
     * <p>注意本字段<b>不含自己</b>（本实例端口会被自动排除）；地址恒为 {@code 127.0.0.1}。
     * 只对本机跑本模组的 bot 生效 —— 真人玩家不暴露 bridge，仍受 48 格物理限制。
     * 改动后需重连/重载配置生效。
     */
    public java.util.List<Integer> peerPorts = new java.util.ArrayList<>();

    /**
     * AI 行动白名单（阶段三）：只有列在这里的命令允许经 {@code POST /command} 派发，
     * 其余一律 403。默认 17 条（6 行动 + 2 命名地标读写 + 1 记忆作废 + 2 协调原语
     * + 2 记忆分享开关 + 3 只读 + 1009 开放的 get/equip 2 条），
     * 见 DESIGN_action_api.md §1.2 A 档 + mod-bridge-landmark.md §4.2 + 1003 协调/记忆原语
     * （sleep / quota / forgetmem，其中 forgetmem 推翻定稿 §7.2 的"记忆永不可经命令删除"，
     * 理由见 ForgetMemoryCommand 类注释）+ 1005 被动式记忆分享（share / autorecord）
     * + 1007 交付闭环（pickup：接收方 bot 走过去捡起给方丢下的物品）
     * + 1009 get/equip 开放（get=按 TaskCatalogue 收集指定物品；equip=穿戴护甲）。
     * 显式置空数组 = 不允许任何行动命令
     * （关闭行动通道）；字段缺省 = 用默认 20 条。
     * ⚠ 已落盘的旧 bridge.json 若含旧条数数组会盖掉此默认 —— 部署时需同步补三处配置
     * （定稿 §5）。1007 14:5x 实测四处 bridge.json <b>都没有</b> allowedCommands 字段，
     * 故本次加条无需改配置。
     */
    public java.util.List<String> allowedCommands = new java.util.ArrayList<>(java.util.Arrays.asList(
            "goto", "follow", "give", "pickup", "get", "equip", "food", "shelter", "locate_structure",
            "idle", "coords", "status", "inventory", "remember", "forget", "sleep", "quota", "forgetmem",
            "share", "autorecord"));

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
