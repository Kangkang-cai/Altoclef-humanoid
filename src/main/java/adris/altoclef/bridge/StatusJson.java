package adris.altoclef.bridge;

import adris.altoclef.tasks.speedrun.BeatPhase;
import adris.altoclef.util.Dimension;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * (2026-10-07 bot 间坐标共享) <b>{@code /status} JSON 的单一解析源</b>。
 *
 * <h3>为什么要有这个类</h3>
 * 1006/1007 先后有两处独立解析同一份 {@code /status}：{@link LeaderPhaseWatcher}（阶段 + 坐标）
 * 与新增的 {@link PeerPosWatcher}（玩家名 + 坐标）。同一个 JSON 被两套正则各解析一遍 =
 * 两个判定源，任一处改了键序/格式就会出现"一边看得见一边看不见"。故 **所有跨进程读 {@code /status}
 * 的解析都收敛到本类**，两个 watcher 只负责"什么时候拉、拉到之后怎么用"。
 *
 * <h3>为什么用正则而不是 Jackson</h3>
 * 这三个字段都在后台线程的 2s 热路径上；引入完整 JSON 解析（Jackson 树模型）只为抠 3 个键
 * 不划算，且现有实现已用正则跑通（{@link LeaderPhaseWatcher} 的 PHASE_RE 有"只认全大写"的
 * 约定，见下）。解析失败一律返回 {@code null}，由调用方保留上次值 / 视为不可用。
 */
public final class StatusJson {

    /**
     * {@code "phase":"XXX"}。
     *
     * <p>只认<b>全大写</b>枚举名（≥3 字符）—— 这恰好避开 {@code activeAction.phase} 那个
     * 同名键（它的值是 {@code "running"} / {@code "accepted"} 这类小写状态字），
     * 不需要写完整的 JSON 解析。
     */
    private static final Pattern PHASE_RE = Pattern.compile("\"phase\"\\s*:\\s*\"([A-Z_]{3,})\"");

    /**
     * {@code "pos":{"x":1.0,"y":2.0,"z":3.0,"dim":"overworld"}}。
     *
     * <p>键序与 {@link BridgeHttpServer} 的写出顺序严格一致（x → y → z → dim），
     * 改写出顺序必须同步改这里。{@code BridgeJson.f1} 用 {@code Locale.ROOT} 定死点号小数，
     * 故正则只认 {@code [0-9.-]}。
     */
    private static final Pattern POS_RE = Pattern.compile(
            "\"pos\"\\s*:\\s*\\{\\s*\"x\"\\s*:\\s*(-?[0-9.]+)\\s*,\\s*\"y\"\\s*:\\s*(-?[0-9.]+)"
                    + "\\s*,\\s*\"z\"\\s*:\\s*(-?[0-9.]+)\\s*,\\s*\"dim\"\\s*:\\s*\"([a-z_]+)\"");

    /**
     * (2026-10-07) {@code "player":"alice"} —— 本 bot 的<b>游戏内玩家名</b>。
     *
     * <p>坐标共享的寻址键：{@code @follow}/{@code @give} 都按游戏内名字寻址，
     * 而 bridge 端口只知道 {@code botId}（{@code "bot_1"}），两者不是一回事，
     * 没有这个键就无法把"要跟的人"翻译成"去哪个端口拿坐标"。
     */
    private static final Pattern PLAYER_RE = Pattern.compile("\"player\"\\s*:\\s*\"([^\"]{1,32})\"");

    /**
     * (2026-10-07 follow 关系治理) {@code "following":"alice"} —— 本 bot
     * <b>此刻正在跟随谁</b>；没在跟随为空串（值来源 {@link FollowRegistry}）。
     *
     * <p>用途：对向跟随（A 跟 B 且 B 跟 A）的检测 —— 见 {@link FollowRelation#mutual}。
     * ⚠️ 刻意不用 {@code task} 字段做这件事：那是链名映射的中文短标签，属显示串不是契约。
     */
    private static final Pattern FOLLOWING_RE = Pattern.compile("\"following\"\\s*:\\s*\"([^\"]{0,32})\"");

    /** {@code pos} 解析结果：世界坐标 + 上报维度。 */
    public record Pos(Vec3d pos, Dimension dim) {
    }

    private StatusJson() {
    }

    /**
     * 解析主线大阶段。失败（无键 / 值不是已知枚举）返回 {@code null}。
     *
     * <p>{@code null} 有两种含义，调用方自行区分：对面是旧 jar（还没有 phase 键），
     * 或枚举不认识的未来值。
     */
    public static BeatPhase parsePhase(String json) {
        if (json == null || json.isEmpty()) return null;
        Matcher m = PHASE_RE.matcher(json);
        if (!m.find()) return null;
        try {
            return BeatPhase.valueOf(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 解析 {@code pos} 对象；无键 / 畸形返回 {@code null}。 */
    public static Pos parsePos(String json) {
        if (json == null || json.isEmpty()) return null;
        Matcher m = POS_RE.matcher(json);
        if (!m.find()) return null;
        try {
            double x = Double.parseDouble(m.group(1));
            double y = Double.parseDouble(m.group(2));
            double z = Double.parseDouble(m.group(3));
            Dimension d = dimFromToken(m.group(4));
            if (d == null) return null;
            return new Pos(new Vec3d(x, y, z), d);
        } catch (NumberFormatException e) {
            // 数值畸形（理论上不会）
            return null;
        }
    }

    /** 解析本 bot 的游戏内玩家名；无键 / 空返回 {@code null}。 */
    public static String parsePlayer(String json) {
        if (json == null || json.isEmpty()) return null;
        Matcher m = PLAYER_RE.matcher(json);
        if (!m.find()) return null;
        String name = m.group(1).trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * 解析本 bot 当前跟随的玩家名；<b>没在跟随 / 无键</b>返回 {@code null}。
     *
     * <p>注意与 {@link #parsePlayer} 的差别：本方法在"键存在但值为空串"时返回 {@code null}
     * —— 那是"没在跟随"的正常表达，不是解析失败。调用方只需判空即可。
     */
    public static String parseFollowing(String json) {
        if (json == null || json.isEmpty()) return null;
        Matcher m = FOLLOWING_RE.matcher(json);
        if (!m.find()) return null;
        String name = m.group(1).trim();
        return name.isEmpty() ? null : name;
    }

    /** {@code /status} 的 dim token → 枚举（取值口径与 ActionDispatcher/NamedLandmarks 一致）。 */    public static Dimension dimFromToken(String t) {
        if (t == null) return null;
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "overworld" -> Dimension.OVERWORLD;
            case "nether", "the_nether" -> Dimension.NETHER;
            case "end", "the_end" -> Dimension.END;
            default -> null;
        };
    }
}
