package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
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
    /** 纯护甲签名（"铁套"），裸装空串（1002 对端要求：不含主手）。 */
    final String equipment;
    /** 主手此刻拿着的物品中文名（1002 16:5x 对端新增）；空手 → 空串。 */
    final String holding;
    /** 背包食物种类概述（"面包x5、生牛肉x3"），无食物空串。 */
    final String food;
    /** 背包食物总数。 */
    final int foodCount;
    /** 稀有+有用级持有（"钻石x2、铁锭x5"），无则空串。 */
    final String keyItems;
    // ---- 阶段三感知补全（1002）：任务细化 + 环境感知。只随快照拉取，不进 L0 事件 ----
    /** 当前实际在干的子任务中文短名（如"挖钻石"）。允许高频变化。 */
    final String taskDetail;
    /** 主线阶段+进度（如"下界·烈焰棒2/8"）；主线没在跑为空串（对端据此省略字段）。 */
    final String mainlineProgress;
    /** day | night（黄昏/黎明归入相邻档）。 */
    final String timeOfDay;
    /** clear | rain | thunder。 */
    final String weather;
    /** 地表 | 洞穴 | 水下 | 下界 | 末地。 */
    final String environment;
    /** 当前生物群系中文短名。 */
    final String biome;
    /** 16 格内敌对生物数（无则 0）。 */
    final int nearbyHostiles;
    // ---- 阶段三感知补全（1002 C 块）：现场执行状态。只随快照拉取，不进 L0 事件 ----
    /** 当前 AI 行动的实时状态；无活跃行动为 null（/status 省略该键）。 */
    final ActiveAction activeAction;
    /** ok | stuck | recovering；离线为空串（对端省略）。 */
    final String botStatus;
    /** 仅 stuck/recovering 时非空，说明卡在哪。 */
    final String stuckReason;
    // ---- (2026-10-03 重要位置记忆共享, 定稿 mod-bridge-landmark.md §4.1) ----
    /**
     * landmarks JSON 数组片段（命名地标 + KnowledgeMemory 自动中心, 合计 ≤20, 见
     * {@link NamedLandmarks#snapshotJsonOrNull}）；无任何内容为 null（/status 整键省略）。
     * 预序列化片段（同 BridgeEvent.detail 的信任模型：客户端线程用 BridgeJson 转义构建）。
     */
    final String landmarks;

    private BotSnapshot(String botId, String ts, boolean online, String summary, String task,
                        double x, double y, double z, String dim, double health,
                        int hunger, int level, String equipment, String holding,
                        String food, int foodCount, String keyItems,
                        String taskDetail, String mainlineProgress, String timeOfDay,
                        String weather, String environment, String biome, int nearbyHostiles,
                        ActiveAction activeAction, String botStatus, String stuckReason,
                        String landmarks) {
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
        this.holding = holding == null ? "" : holding;
        this.food = food;
        this.foodCount = foodCount;
        this.keyItems = keyItems;
        this.taskDetail = taskDetail;
        this.mainlineProgress = mainlineProgress;
        this.timeOfDay = timeOfDay;
        this.weather = weather;
        this.environment = environment;
        this.biome = biome;
        this.nearbyHostiles = nearbyHostiles;
        this.activeAction = activeAction;
        this.botStatus = botStatus == null ? "" : botStatus;
        this.stuckReason = stuckReason == null ? "" : stuckReason;
        this.landmarks = landmarks;
    }

    /** 离线时的占位快照（player 为 null / 未进世界）。环境与现场字段降级为空/省略。 */
    static BotSnapshot offline(String botId) {
        return new BotSnapshot(botId, nowIso(), false, "不在游戏里", "离线",
                0, 0, 0, "overworld", 0, 0, 0, "", "", "", 0, "",
                "", "", "", "", "", "", 0,
                null, "", "", null);
    }

    /**
     * 在客户端线程采样当前状态构造快照。仅由 {@link BridgeHttpServer} 的
     * ClientTickEvent 订阅调用（保证客户端线程）。
     *
     * @param guard 背包读失败防抖（可 null；null 时防抖旁路，摘要直出）
     * @param activeAction 当前 AI 行动现场状态（可 null = 无活跃行动）
     * @param botStatus    ok | stuck | recovering（来自 {@link LivenessDigest}）
     * @param stuckReason  卡住原因（仅 stuck/recovering 非空）
     */
    static BotSnapshot snapshot(AltoClef mod, SummaryGenerator gen, String botId, InventoryGuard guard,
                               ActiveAction activeAction, String botStatus, String stuckReason) {
        if (mod == null || !AltoClef.inGame()) {
            return offline(botId);
        }
        ClientPlayerEntity p = mod.getPlayer();
        if (p == null) {
            return offline(botId);
        }
        ClientWorld world = p.clientWorld;
        if (world == null) {
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
                digest.hunger, digest.level, digest.equipment, digest.holding,
                digest.food, digest.foodCount, digest.keyItems,
                SceneDigest.taskDetail(mod), SceneDigest.mainlineProgress(mod),
                SceneDigest.timeOfDay(world), SceneDigest.weather(world),
                SceneDigest.environment(mod, p), SceneDigest.biome(world, p),
                SceneDigest.nearbyHostiles(world, p),
                activeAction, botStatus, stuckReason,
                NamedLandmarks.snapshotJsonOrNull(pos, mod));
    }

    /** 当前时刻的 ISO8601 UTC 字符串。 */
    static String nowIso() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
