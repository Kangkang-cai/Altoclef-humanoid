package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * bot 状态的不可变快照：客户端线程定期构造，HTTP 线程只读 volatile 引用。
 *
 * <p>线程模型（契约 §3.5 实现约束）：
 * <ul>
 *   <li><b>构造</b>：仅在客户端线程（ClientTickEvent 订阅里，每 ~5s）调用
 *       {@link #snapshot(AltoClef, SummaryGenerator, String)}，读 player/taskRunner
 *       等游戏状态 —— 这些读取只允许在客户端线程做。</li>
 *   <li><b>读取</b>：{@code BridgeHttpServer} 持有 {@code volatile BotSnapshot} 字段，
 *       /status handler 只读该引用的字段，绝不直接触碰 MinecraftClient/player。
 *       所有字段都是不可变 String/基本类型，volatile 引用读到的快照自洽。</li>
 * </ul>
 *
 * <p>字段口径对齐契约 §3.2 / §3.3：{@code ts} 为 ISO8601 字符串；{@code online}
 * = 是否在游戏内；{@code summary} 是一句中文模板摘要；{@code task} 是短任务标签；
 * {@code pos}/{@code health} 为可选补充字段（对端只消费 summary/ts/online）。
 */
final class BotSnapshot {

    final String botId;
    /** ISO8601 UTC 字符串，如 {@code "2026-09-30T04:23:22Z"}。 */
    final String ts;
    final boolean online;
    final String summary;
    final String task;
    final double x, y, z;
    final String dim;
    final double health;

    private BotSnapshot(String botId, String ts, boolean online, String summary, String task,
                        double x, double y, double z, String dim, double health) {
        this.botId = botId;
        this.ts = ts;
        this.online = online;
        this.summary = summary;
        this.task = task;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dim = dim;
        this.health = health;
    }

    /** 离线时的占位快照（player 为 null / 未进世界）。 */
    static BotSnapshot offline(String botId) {
        return new BotSnapshot(botId, nowIso(), false, "不在游戏里", "离线",
                0, 0, 0, "overworld", 0);
    }

    /**
     * 在客户端线程采样当前状态构造快照。仅由 {@link BridgeHttpServer} 的
     * ClientTickEvent 订阅调用（保证客户端线程）。
     */
    static BotSnapshot snapshot(AltoClef mod, SummaryGenerator gen, String botId) {
        if (mod == null || !AltoClef.inGame()) {
            return offline(botId);
        }
        ClientPlayerEntity p = mod.getPlayer();
        if (p == null) {
            return offline(botId);
        }
        SummaryGenerator.Result r = gen.generate(mod, botId);

        Vec3d pos = p.getPos();
        double x = pos.x, y = pos.y, z = pos.z;
        double hp = p.getHealth();

        Dimension dim = null;
        try {
            dim = WorldHelper.getCurrentDimension();
        } catch (Throwable ignored) {
        }
        String dimTok = SummaryGenerator.dimToken(dim);

        return new BotSnapshot(botId, nowIso(), true, r.summary, r.task, x, y, z, dimTok, hp);
    }

    /** 当前时刻的 ISO8601 UTC 字符串。 */
    static String nowIso() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
