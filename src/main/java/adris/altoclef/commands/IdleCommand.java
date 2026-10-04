package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.CommandFeedback;
import adris.altoclef.tasks.movement.IdleTask;

/**
 * 原地站立待命。(2026-10-04) 支持可选时长参数：{@code idle 30} = 待命 30 秒后自动回主线；
 * 不带参数 = 无限期待命（旧语义，等你下一条指令）。非正整数当场拒绝，不进任务。
 */
public class IdleCommand extends Command {
    public IdleCommand() throws CommandException {
        super("idle", "Stand still [seconds]（可选秒数，到时自动回主线；不传=一直待命）",
                new Arg(Integer.class, "seconds", null, 0));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        Integer sec = parser.get(Integer.class);
        if (sec != null && sec <= 0) {
            // 一次性出口：参数非法当场拒绝，不派任务（对端收 failed 回执）。
            String m = "idle 参数必须是正整数秒（不传=一直待命），收到：" + sec + "。";
            CommandFeedback.fail(m);
            log(m);
            finish();
            return;
        }
        // (2026-10-04 回执补全) 旧版不回自报 → action_done 退化成通用文案"已执行：idle"，
        // 对端拿不到"这是在待命"的语义（只有 /status.activeAction.detail 里才有）。
        String m = sec == null
                ? "原地站立待命（不动），等你下一条指令。"
                : "原地站立待命（不动）" + sec + " 秒，到时自动回主线。";
        CommandFeedback.ok(m);
        log(m);
        mod.runUserTask(sec == null ? new IdleTask() : new IdleTask(sec), this::finish);
    }
}
