package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * 状态摘要生成器：把"bot 当前在干嘛 + 坐标 + 血量 + 饥饿"拼成一句中文。
 *
 * <p>设计原则（契约 §3.3 / §0 全局约束）：
 * <ul>
 *   <li><b>模板 + 随机措辞池</b>，不上 LLM（零延迟、零成本）；</li>
 *   <li><b>禁止全量字段直出</b>：不喂 StateSampler 的每帧全量 JSON，只取链名 +
 *       坐标 + 血量 + 饥饿四项粗粒度信号；</li>
 *   <li><b>真实底线</b>：自身状态（坐标/血量/"我在干嘛"）无需视线即可知，如同人
 *       不用看也知道自己在挖矿 —— 这里不涉及对视野外事物的感知。</li>
 * </ul>
 *
 * <p>线程模型：仅在客户端线程（ClientTickEvent 订阅里）被 {@link BotSnapshot}
 * 构造时调用，HTTP 线程只读已构造好的 {@link BotSnapshot}，绝不直接调本类。
 */
final class SummaryGenerator {

    private final Random _rng = new Random();

    /** 三个动作前缀措辞，随机轮换（"措辞池随机轮换起步"）。 */
    private static final String[] VERB_PREFIX = {"正在", "在", ""};
    /** 三个"闲着"措辞。 */
    private static final String[] IDLE_VERB = {"闲着", "发呆中", "暂时没事做"};

    /**
     * 任务链名 → 中文动词短语。key 取 {@link TaskChain#getName()} 的原始英文。
     * 未命中的反射链（Hand Stack Fix / Swing Through 等内部修正链）返回 null，
     * 由调用方决定是否降级为"应急处理"或合并到主链描述。
     */
    private static String chainToVerb(String chainName) {
        if (chainName == null) return null;
        return switch (chainName) {
            case "User Tasks" -> "推进主线任务";
            case "Mob Defense" -> "打架";
            case "Food" -> "找吃的";
            case "Death Recovery / Drop Retrieval" -> "回去捡尸";
            case "Death Menu Respawn Handling" -> "等待重生";
            case "Surface Exit Chain" -> "回地表";
            case "Misc World Survival Chain" -> "应急处理";
            case "MLG Water Bucket Fall Chain" -> "应急处理";
            case "Swim Dive (stuck-in-water reaction)" -> "应急处理";
            case "Swing Through (stuck reaction)" -> "应急处理";
            case "Hand Stack Fix Chain" -> null; // 内部修正链，不单独报
            default -> null;
        };
    }

    /**
     * 把链名映射成 {@code task} 字段的短中文（用于 /status 的 "task" 字段）。
     * 比 verb 更名词化，供对端做简单分类。
     */
    static String chainToTaskLabel(String chainName) {
        if (chainName == null) return "空闲";
        return switch (chainName) {
            case "User Tasks" -> "主线任务";
            case "Mob Defense" -> "战斗";
            case "Food" -> "觅食";
            case "Death Recovery / Drop Retrieval" -> "捡尸";
            case "Death Menu Respawn Handling" -> "重生";
            case "Surface Exit Chain" -> "回地表";
            case "Misc World Survival Chain", "MLG Water Bucket Fall Chain",
                 "Swim Dive (stuck-in-water reaction)", "Swing Through (stuck reaction)" -> "应急";
            case "Hand Stack Fix Chain" -> "空闲";
            default -> "其他";
        };
    }

    /**
     * (1002 16:5x 对端要求) 细分 {@code task} 标签。链名太粗 —— User Tasks 链里跑
     * {@code @idle} 驻留时也会报"主线任务"，害对端把 idle 误判成"主线在跑"（B4/B5 误判源头）。
     *
     * <p>口径：无任务 → {@code 空闲}；{@code @idle} 驻留 → {@code 待命}；
     * {@link BeatMinecraft2Task} 在跑 → {@code 主线任务}；其余用户任务（@goto/@follow/@give 等）
     * → {@code 行动任务}。非 User Tasks 链保持原链名映射。
     */
    private static String refineUserTaskLabel(AltoClef mod, TaskChain chain, String fallback) {
        try {
            if (chain != null && "User Tasks".equals(chain.getName())) {
                var utc = mod.getUserTaskChain();
                if (utc == null) return fallback;
                if (utc.isRunningIdleTask()) return "待命";
                Task cur = utc.getCurrentTask();
                if (cur == null) return "空闲";
                if (cur instanceof IdleTask) return "待命";
                if (cur instanceof BeatMinecraft2Task) return "主线任务";
                return "行动任务";
            }
            if (chain == null || chain.getTasks().isEmpty()) return "空闲";
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 维度枚举 → 契约 {@code pos.dim} 字段的小写字符串。 */
    static String dimToken(Dimension dim) {
        if (dim == null) return "overworld";
        return switch (dim) {
            case NETHER -> "nether";
            case END -> "end";
            default -> "overworld";
        };
    }

    /**
     * 生成一句中文摘要 + 短任务标签。
     *
     * <p>长度契约：≤{@value #SUMMARY_MAX_CHARS} 字，超长按优先级裁剪——
     * 任务+坐标+血量（恒在）&gt; 装备 &gt; 食物 &gt; keyItems。裁剪是确定性的
     * （同状态同句），随机措辞池只影响动词前缀，不影响信息位。
     *
     * @param mod      AltoClef 实例（客户端线程调用，安全）
     * @param botId    本 bot 的身份标识（写入摘要不必要，但调试可见）
     * @param digest   背包摘要（装备/食物/持有子句数据源，与快照字段同口径）
     * @return 同时含 summary 与 task 的结果对象
     */
    Result generate(AltoClef mod, String botId, InventoryDigest digest) {
        ClientPlayerEntity p = mod.getPlayer();
        if (p == null || !AltoClef.inGame()) {
            return new Result("不在游戏里", "离线");
        }

        TaskRunner runner = mod.getTaskRunner();
        TaskChain chain = runner == null ? null : runner.getCurrentTaskChain();
        String chainName = chain == null ? null : chain.getName();
        String verb = chainToVerb(chainName);
        String taskLabel = refineUserTaskLabel(mod, chain, chainToTaskLabel(chainName));

        // 闲着：没有链胜出，或链的任务栈空（TaskRunner 已有 "(no task running)" 口径）
        boolean idle = (chain == null) || chain.getTasks().isEmpty();
        if (idle && verb == null) {
            verb = IDLE_VERB[_rng.nextInt(IDLE_VERB.length)];
        } else if (verb == null) {
            // 命中了内部修正链（Hand Stack Fix）—— 不单独报，降级为闲着
            verb = IDLE_VERB[_rng.nextInt(IDLE_VERB.length)];
            taskLabel = "空闲";
        } else if (!idle) {
            // 有任务在跑：加随机前缀
            String prefix = VERB_PREFIX[_rng.nextInt(VERB_PREFIX.length)];
            verb = prefix + verb;
        }

        BlockPos b = p.getBlockPos();
        int x = b.getX(), y = b.getY(), z = b.getZ();

        // 血量（取整）
        int hp = (int) Math.round(p.getHealth());

        // 饥饿状态：契约示例"有点饿"。分三档。
        int food = p.getHungerManager().getFoodLevel();
        String hungerClause;
        if (food < 8) {
            hungerClause = "，快饿死了";
        } else if (food < 15) {
            hungerClause = "，有点饿";
        } else {
            hungerClause = ""; // 饱腹不报
        }

        // 血量告急（<8）单独标一下，否则只给数值
        String hpClause = hp < 8 ? "，血量告急(" + hp + ")" : "，血量" + hp;

        // 维度后缀：非主世界时附在末尾，便于对端一眼区分
        Dimension dim = null;
        try {
            dim = WorldHelper.getCurrentDimension();
        } catch (Throwable ignored) {
        }
        String dimSuffix = (dim != null && dim != Dimension.OVERWORLD)
                ? "，在" + (dim == Dimension.NETHER ? "下界" : "末地")
                : "";

        // 拼装（确定性裁剪）：
        // 基座=任务+坐标+血量+饥饿档+维度，恒保留；之后按 装备>食物>持有 依次
        // 尝试追加，超 {@value #SUMMARY_MAX_CHARS} 字即停（定点截断，同状态同句）。
        String base = verb + "，坐标(" + x + "," + y + "," + z + ")" + hpClause + hungerClause + dimSuffix;
        StringBuilder summary = new StringBuilder(base);
        String[] extras = {
                digest.equipment.isEmpty() ? null : "，装备" + digest.equipment,
                digest.food.isEmpty() ? null : "，食物" + digest.food,
                digest.keyItems.isEmpty() ? null : "，持有" + digest.keyItems
        };
        for (String extra : extras) {
            if (extra == null) continue;
            if (summary.length() + extra.length() > SUMMARY_MAX_CHARS) break;
            summary.append(extra);
        }

        return new Result(summary.toString(), taskLabel);
    }

    /** summary 长度上限（契约：≤80 字）。 */
    private static final int SUMMARY_MAX_CHARS = 80;

    /** 生成结果：摘要句 + 短任务标签。 */
    static final class Result {
        final String summary;
        final String task;
        Result(String summary, String task) {
            this.summary = summary;
            this.task = task;
        }
    }
}
