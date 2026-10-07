package adris.altoclef.bridge;

import adris.altoclef.Debug;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * (2026-10-07 交付闭环) 向<b>本机另一个 bot 实例</b>的 bridge 发一条命令。
 *
 * <h3>为什么需要</h3>
 * {@code @give} 只能把物品丢在地上（MC 没有"塞进对方背包"的原语）。真人会自己捡，
 * 而另一个 bot 不会 —— 需要有人通知它"该去捡了"。本类就是给方那一侧的出口：
 * 交付完成后由 {@code GiveItemToPlayerTask} 调 {@link #send}，对方收到 {@code @pickup}。
 *
 * <h3>定位对端</h3>
 * 复用 {@link PeerPosWatcher#portOf(String)}（那张"同伴自报坐标"表本来就带端口）。
 * 拿不到端口（对面是真人玩家 / 未开 bridge / 不在本机 peerPorts 窗口内）⇒ <b>静默跳过</b>，
 * 不是错误 —— 真人自己会捡。
 *
 * <h3>线程</h3>
 * 网络 IO 一律在<b>单线程后台执行器</b>上做，绝不在游戏线程里阻塞
 * （对照 1004 那次"后台线程直接 sendMessage 写坏 font 表"的教训 —— 跨线程的活要么全异步、
 * 要么全走安全封装）。发送失败只记一行日志，不影响交付本身。
 */
public final class PeerCommandSender {

    private static final int TIMEOUT_MS = 1_500;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "peer-command-sender");
        t.setDaemon(true);
        return t;
    });

    private PeerCommandSender() {
    }

    /**
     * 异步向 {@code playerName} 对应的本机 bot 实例发一条 bridge 命令（形如 {@code "@pickup cooked_porkchop"}）。
     * 对方不是本机 bot ⇒ 静默跳过。
     */
    public static void send(String playerName, String command) {
        if (playerName == null || playerName.isBlank() || command == null || command.isBlank()) return;
        int port = PeerPosWatcher.portOf(playerName);
        if (port <= 0) return;
        EXEC.submit(() -> post(port, command));
    }

    private static void post(int port, String command) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/command").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = ("{\"cmd\":\"" + escape(command) + "\"}").getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            if (code == 200) {
                Debug.logMessage("[PeerCmd] 已发送 -> port=" + port + " cmd=" + command);
            } else {
                // 403 = 对面白名单没开这条命令（对端 jar 旧 / 配置显式覆盖过 allowedCommands）
                Debug.logMessage("[PeerCmd] 发送被拒 code=" + code + " port=" + port + " cmd=" + command);
            }
        } catch (Throwable t) {
            Debug.logMessage("[PeerCmd] 发送异常 port=" + port + " cmd=" + command + " : " + t);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 最小 JSON 转义（命令只含物品名/数字，够用）。 */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
