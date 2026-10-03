package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.NamedLandmarks;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;

/**
 * (2026-10-03 重要位置记忆共享, 桥接接口定稿 mod-bridge-landmark.md §4.2)
 * 删除一条【命名地标】。作用域定稿 §7.2：只删 NamedLandmarks，
 * KnowledgeMemory（自动感知的结构记忆）永不可经命令删除 ——
 * AI 起名撞自动中心名（如 remember "下界堡垒"）时，forget 同名只删命名那条。
 */
public class ForgetCommand extends Command {

    public ForgetCommand() throws CommandException {
        super("forget", "删除一条命名地标（不影响自动感知的结构记忆）",
                new Arg(String.class, "名称"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String name = parser.get(String.class);
        NamedLandmarks.forget(name);
        finish();
    }
}
