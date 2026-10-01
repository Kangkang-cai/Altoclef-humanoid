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
    // ---- /status 契约扩展字段（对端按结构化字段算变化键，语义必须稳定）----
    /** 饥饿值 0~20 原始数值（档位对端算）。 */
    final int hunger;
    /** 经验等级原始数值。 */
    final int level;
    /** 护甲+主手工具签名（"铁套+石镐"），裸装空串。 */
    final String equipment;
    /** 背包食物种类概述（"面包x5、生牛肉x3"），无食物空串。 */
    final String food;
    /** 背包食物总数。 */
    final int foodCount;
    /** 稀有+有用级持有（"钻石x2、铁锭x5"），无则空串。 */
    final String keyItems;

    private BotSnapshot(String botId, String ts, boolean online, String summary, String task,
                        double x, double y, double z, String dim, double health,
                        int hunger, int level, String equipment,
                        String food, int foodCount, String keyItems) {
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
        this.hunger = hunger;
        this.level = level;
        this.equipment = equipment;
        this.food = food;
        this.foodCount = foodCount;
        this.keyItems = keyItems;
    }

    /** 离线时的占位快照（player 为 null / 未进世界）。 */
    static BotSnapshot offline(String botId) {
        return new BotSnapshot(botId, nowIso(), false, "不在游戏里", "离线",
                0, 0, 0, "overworld", 0, 0, 0, "", "", 0, "");
    }

    /**
     * 在客户端线程采样当前状态构造快照。仅由 {@link BridgeHttpServer} 的
     * ClientTickEvent 订阅调用（保证客户端线程）。
     *
     * @param guard 背包读失败防抖（可 null；null 时防抖旁路，摘要直出）
     */
    static BotSnapshot snapshot(AltoClef mod, SummaryGenerator gen, String botId, InventoryGuard guard) {
        if (mod == null || !AltoClef.inGame()) {
            return offline(botId);
        }
        ClientPlayerEntity p = mod.getPlayer();
        if (p == null) {
            return offline(botId);
        }
        // 背包摘要先算（带读失败防抖：冻结窗口内沿用上一个有效的背包派生字段），
        // summary 的装备/食物/持有子句从 digest 取，保证口径一致
        InventoryDigest digest = InventoryDigest.sample(p, guard);
        SummaryGenerator.Result r = gen.generate(mod, botId, digest);

        Vec3d pos = p.getPos();
        double x = pos.x, y = pos.y, z = pos.z;
        double hp = p.getHealth();

        Dimension dim = null;
        try {
            dim = WorldHelper.getCurrentDimension();
        } catch (Throwable ignored) {
        }
        String dimTok = SummaryGenerator.dimToken(dim);

        return new BotSnapshot(botId, nowIso(), true, r.summary, r.task, x, y, z, dimTok, hp,
                digest.hunger, digest.level, digest.equipment,
                digest.food, digest.foodCount, digest.keyItems);
    }

    /** 当前时刻的 ISO8601 UTC 字符串。 */
    static String nowIso() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
