package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandFeedback;
import adris.altoclef.tasks.movement.IdleTask;

public class IdleCommand extends Command {
    public IdleCommand() {
        super("idle", "Stand still");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        // (2026-10-04 回执补全) 旧版不回自报 → action_done 退化成通用文案"已执行：idle"，
        // 对端拿不到"这是在待命"的语义（只有 /status.activeAction.detail 里才有）。
        CommandFeedback.ok("待命中，等你下一条指令。");
        mod.runUserTask(new IdleTask(), this::finish);
    }
}
