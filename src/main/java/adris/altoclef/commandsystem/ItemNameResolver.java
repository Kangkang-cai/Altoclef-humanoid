package adris.altoclef.commandsystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.item.Item;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 物品名容错解析（2026-10-22，用户"AI 角色写错物品名需要全面多层次兜底"）。
 *
 * <p>动机：对端 AI 角色会发出 {@code @get blade_rod 3} 这类命令 —— 拼错一个字母就
 * {@code Item not catalogued} 硬失败，AI 只能等超时或盲猜重试。本类把"名字写错"从
 * 一票否决变成分层消化：
 * <ol>
 *   <li><b>规范化</b>：大小写、空格/连字符、{@code minecraft:} 前缀、多余下划线、
 *       引号与杂字符统一归一 —— "Blaze Rod" / "blaze-rod" / " minecraft:blaze_rod "
 *       全部等价；</li>
 *   <li><b>别名表</b>：编辑距离够不着的语义变体（{@code pearl → ender_pearl} 等），
 *       仅精确未命中时启用，不进 {@code @list} 目录避免污染清单；</li>
 *   <li><b>模糊匹配自动修正</b>：编辑距离 ≤ 阈值（短名 1 / 长名 2）且<b>唯一最优</b>
 *       才自动改写（{@code blade_rod → blaze_rod} 距离 1）；有歧义（两个候选同距离）
 *       不改，只给候选。每次自动修正都会 log 到聊天，人不看提示也能发现；</li>
 *   <li><b>失败带候选</b>：真认不出时抛 {@link CommandException}，文案附最接近的
 *       3 个候选 + {@code @list} 指引 —— 经 bridge 的 errorKind=usage 回执传给对端
 *       AI，让它下一轮自纠重发而不是瞎猜；</li>
 *   <li><b>上游预防</b>（本仓库外）：对端命令契约文档同步"名字拿不准先 @list"。</li>
 * </ol>
 *
 * <p>收口原则（判定源单一化）：所有吃物品名的命令入口（{@code ItemList.parseRemainder}
 * → @get/@equip/@stash/@deposit、@give、@pickup、@quota）统一经本类规范化/纠错/提示，
 * 不再各自为战。
 */
public final class ItemNameResolver {

    /**
     * 常见误写别名 → 目录名。只收"模糊匹配够不着"的（距离太远或歧义大）；
     * 能被模糊匹配吃掉的（blazerod、enderpearl、sticks 这类）不必登记。
     * 目标名必须真实存在于目录（resolve 时会再验 taskExists，防止目录改名后悬空）。
     */
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        ALIASES.put("pearl", "ender_pearl");
        ALIASES.put("pearls", "ender_pearl");
        ALIASES.put("blazerod", "blaze_rod");
        ALIASES.put("blazerods", "blaze_rod");
        ALIASES.put("eyeofender", "ender_eye");
        ALIASES.put("enderpearls", "ender_pearl");
    }

    private ItemNameResolver() {}

    /**
     * 解析为目录注册名：规范化 → 精确 → 别名 → 模糊自动修正。
     *
     * @throws CommandException 全部层未命中（文案附最接近候选）。
     */
    public static String resolve(String raw) throws CommandException {
        String name = normalize(raw);
        if (name.isEmpty()) {
            throw new CommandException("物品名为空，用法：@get <物品名> <数量>");
        }
        // 第 2 层：规范化后精确命中
        if (TaskCatalogue.taskExists(name)) {
            return name;
        }
        // 第 3 层：别名表（仅精确未命中时）
        String aliased = ALIASES.get(name);
        if (aliased != null && TaskCatalogue.taskExists(aliased)) {
            logCorrection(raw, aliased, "别名表");
            return aliased;
        }
        // 第 4 层：模糊匹配
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        int secondDist = Integer.MAX_VALUE;
        for (String candidate : TaskCatalogue.resourceNames()) {
            int d = levenshtein(name, candidate);
            if (d < bestDist) {
                secondDist = bestDist;
                bestDist = d;
                best = candidate;
            } else if (d < secondDist) {
                secondDist = d;
            }
        }
        int threshold = name.length() >= 8 ? 2 : 1;
        if (best != null && bestDist <= threshold && bestDist < secondDist) {
            // 唯一最优且够近 → 自动修正（歧义时 secondDist == bestDist，走失败提示）
            logCorrection(raw, best, "模糊匹配(编辑距离" + bestDist + ")");
            return best;
        }
        // 第 5 层：失败反馈带候选（经 errorKind=usage 回执给对端 AI 自纠）
        StringBuilder msg = new StringBuilder("Item not catalogued: '" + raw + "'（未收录物品名）。");
        String sugg = suggestions(raw, 3);
        if (!sugg.isEmpty()) {
            msg.append(" 最接近的可用资源：").append(sugg).append("。");
        }
        msg.append("用 @list 查看全部可用资源名。");
        throw new CommandException(msg.toString());
    }

    /**
     * 失败提示用的候选清单（供 @give/@pickup/@quota 等解析到注册表层的命令拼错误文案）。
     * 在 目录资源名 ∪ 全物品注册表短名 上做模糊匹配，取距离最近的 {@code topN} 个；
     * 距离超过 3 的视为完全不沾边，不出现（免得误导）。
     */
    public static String suggestions(String raw, int topN) {
        String name = normalize(raw);
        if (name.isEmpty()) return "";
        // 候选 = 目录资源名 ∪ 全物品注册表短名；距离 ≤3 才算"沾边"，超了不给（免得误导）。
        Map<String, Integer> scored = new HashMap<>();
        for (String candidate : TaskCatalogue.resourceNames()) {
            scored.merge(candidate, levenshtein(name, candidate), Math::min);
        }
        for (Item item : Registry.ITEM) {
            String shortName = ItemHelper.stripItemName(item);
            if (shortName == null || shortName.isBlank()) continue;
            String lower = shortName.toLowerCase(Locale.ROOT);
            scored.merge(lower, levenshtein(name, lower), Math::min);
        }
        List<Map.Entry<String, Integer>> hits = new ArrayList<>();
        for (Map.Entry<String, Integer> e : scored.entrySet()) {
            if (e.getValue() <= 3) hits.add(e);
        }
        hits.sort(Comparator
                .comparingInt(Map.Entry<String, Integer>::getValue)
                .thenComparing(Map.Entry::getKey));
        List<String> top = new ArrayList<>();
        for (Map.Entry<String, Integer> e : hits) {
            if (top.size() >= topN) break;
            top.add(e.getKey());
        }
        return String.join(", ", top);
    }

    /**
     * 规范化：去引号 → 小写 → 去 {@code minecraft:} 前缀 → 杂字符归并为下划线 →
     * 压缩连续下划线 → 去首尾下划线。
     * "Blaze Rod" / "blaze-rod" / " minecraft:blaze_rod " → "blaze_rod"。
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        s = s.replaceAll("[^a-z0-9_]", "_");
        s = s.replaceAll("_+", "_");
        if (s.startsWith("_")) s = s.substring(1);
        if (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 自动修正必须可见：log 到聊天（无实例时退日志），人不用看回执也能发现改写。 */
    private static void logCorrection(String raw, String canonical, String via) {
        String msg = "[物品名自动修正] '" + raw + "' → '" + canonical + "'（" + via + "）";
        try {
            AltoClef mod = AltoClef.getInstance();
            if (mod != null) {
                mod.log(msg);
                return;
            }
        } catch (Throwable ignored) {
        }
        Debug.logMessage(msg);
    }

    /** 经典 Levenshtein 编辑距离（滚动数组，目录规模几百条无压力）。 */
    private static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
