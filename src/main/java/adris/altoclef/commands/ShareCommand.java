package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.NamedLandmarks;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.CommandFeedback;

/**
 * (2026-10-05 被动式记忆分享·分享侧否决口) 控制新记忆是否自动推送 memory_shared
 * 事件给对端。
 *
 * <p>用法：{@code @share <on|off> <type|all>}
 * <ul>
 *   <li>{@code @share off lava_lake} —— 岩浆湖记忆不再推送（其余照常）；</li>
 *   <li>{@code @share off all} —— 全部不推送；</li>
 *   <li>{@code @share on lava_lake} / {@code @share on all} —— 恢复推送。</li>
 * </ul>
 *
 * <p>口径 = "默认做, AI 只否决"：不发本命令 = 全部类型照常推送。只作用于<b>新
 * 入账</b>的自动结构记忆（KnowledgeMemory.remember 钩子），不回收已推送的。
 * 本命令是<b>状态写入</b>：无任务生命期、不打断主线（INSTANT, 不 replaceUserTask）。
 */
public class ShareCommand extends Command {

    public ShareCommand() throws CommandException {
        super("share", "AI: toggle pushing new memories to peers (@share off <type|all>)",
                new Arg(String.class, "开关"),
                new Arg(String.class, "类型"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String action = parser.get(String.class);
        String typeToken = parser.get(String.class);
        boolean on;
        if ("on".equalsIgnoreCase(action)) {
            on = true;
        } else if ("off".equalsIgnoreCase(action)) {
            on = false;
        } else {
            String m = "第一个参数只认 on/off —— " + action + "。用法 @share <on|off> <type|all>";
            CommandFeedback.fail(m);
            log("share：" + m);
            finish();
            return;
        }
        if (typeToken == null || typeToken.isEmpty()) {
            String m = "缺 type 参数。用法 @share <on|off> <" + NamedLandmarks.allTypeTokens() + "|all>";
            CommandFeedback.fail(m);
            log("share：" + m);
            finish();
            return;
        }
        String summary = NamedLandmarks.setShareDenied(typeToken, !on);
        if (summary == null) {
            String m = "认不出类型 —— " + typeToken + "（可选：" + NamedLandmarks.allTypeTokens() + " / all）";
            CommandFeedback.fail(m);
            log("share：" + m);
            finish();
            return;
        }
        CommandFeedback.ok(summary);
        log("share：" + summary);
        finish();
    }
}
