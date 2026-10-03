package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.movement.FollowPlayerTask;

public class FollowCommand extends Command {
    public FollowCommand() throws CommandException {
        super("follow", "Follows you or someone else", new Arg(String.class, "username", null, 0));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String username = parser.get(String.class);
        if (username == null) {
            if (mod.getButler().hasCurrentUser()) {
                username = mod.getButler().getCurrentUser();
            } else {
                mod.logWarning("No butler user currently present. Running this command with no user argument can ONLY be done via butler.");
                // (2026-10-03 回执) 未起任务 → 必须自报失败, 否则 bridge 的 action_done 永不会来。
                adris.altoclef.commandsystem.CommandFeedback.fail(
                        "做不到：没有指定玩家名，而当前没有 butler 用户可代指。用法 @follow <玩家名>");
                finish();
                return;
            }
        }
        mod.runUserTask(new FollowPlayerTask(username), this::finish);
    }
}