package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.CommandFeedback;
import adris.altoclef.util.DemandQuota;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

/**
 * 需求份额（2026-10-03）：AI 角色给本 bot 下达"我负责凑多少"。
 *
 * <p>用法：{@code @quota <item_id> <count>}
 * <ul>
 *   <li>{@code @quota blaze_rod 3} —— 我只负责 3 根烈焰棒（凑够就停，不再为 blaze 出门）；</li>
 *   <li>{@code @quota blaze_rod 0} —— 烈焰棒归别人，我一点不碰；</li>
 *   <li>{@code @quota blaze_rod -1} —— 取消该物品份额，回主线默认口径；</li>
 *   <li>{@code @quota all -1} —— 清空全部份额。</li>
 * </ul>
 *
 * <p>语义细节与为什么只覆盖 blaze_rod / ender_pearl / ender_eye 见 {@link DemandQuota}。
 * 本命令是<b>状态写入</b>，无任务生命期，不打断主线（不像 {@code @goto} 会顶掉手头任务）；
 * 生效点是主线下一拍的需求计算。
 */
public class QuotaCommand extends Command {

    public QuotaCommand() throws CommandException {
        super("quota", "AI: set this bot's collection share (absolute hold target). count<0 clears; item 'all' clears all",
                new Arg(String.class, "item"),
                new Arg(Integer.class, "count"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String token = parser.get(String.class);
        int count = parser.get(Integer.class);

        if (token == null || token.isEmpty()) {
            String m = "缺 item 参数。用法 @quota <item_id> <count>";
            CommandFeedback.fail(m);
            log("quota：" + m);
            finish();
            return;
        }
        if ("all".equalsIgnoreCase(token) || "*".equals(token)) {
            DemandQuota.clearAll();
            String m = "已清空全部份额，回到主线默认口径。";
            CommandFeedback.ok(m);
            log("quota：" + m);
            finish();
            return;
        }
        Item item = resolve(token);
        if (item == null) {
            // (2026-10-22 物品名容错) 失败文案附最接近候选，AI 拿到能自纠重发。
            String sugg = adris.altoclef.commandsystem.ItemNameResolver.suggestions(token, 3);
            String m = "认不出物品 id —— " + token + "（示例：blaze_rod / ender_pearl / ender_eye）"
                    + (sugg.isEmpty() ? "" : "；最接近的可用名字：" + sugg);
            CommandFeedback.fail(m);
            log("quota：" + m);
            finish();
            return;
        }
        Integer prev = DemandQuota.get(item);
        DemandQuota.set(item, count);
        String m = "quota：" + token + " → " + (count < 0
                ? "取消（回主线默认）"
                : count + " 个（本 bot 的绝对持有目标）")
                + "；原值 " + (prev == null ? "未设" : String.valueOf(prev));
        CommandFeedback.ok(m);
        log(m);
        finish();
    }

    /** 物品 id 解析：支持 "blaze_rod" 与 "minecraft:blaze_rod" 两种写法。 */
    private static Item resolve(String token) {
        try {
            // (2026-10-22 物品名容错) 规范化：AI 写 "Blaze Rod" / "blaze-rod" 也能吃下。
            String normalized = adris.altoclef.commandsystem.ItemNameResolver.normalize(token);
            if (normalized.isEmpty()) return null;
            Identifier id = token.contains(":")
                    ? new Identifier(token)
                    : new Identifier("minecraft", normalized);
            Item it = Registry.ITEM.get(id);
            return it == Items.AIR ? null : it;
        } catch (Throwable t) {
            return null;
        }
    }
}
