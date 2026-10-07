package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.FollowRelation;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.movement.FollowAndWorkTask;
import adris.altoclef.tasks.movement.FollowPlayerTask;

/**
 * {@code @follow <玩家名> [mode]}
 *
 * <ul>
 *   <li>{@code @follow <名字>} —— <b>纯跟随</b>, 行为与 2026-10-06 之前逐字一致（默认值）。</li>
 *   <li>{@code @follow <名字> work} —— 跟随 + 顺手干活: 贴 3 格跟着, 顺手时短暂偏离去
 *       搜刮就近容器 / 贪心挖可见矿, 干完回跟随。语义见 {@link FollowAndWorkTask}。</li>
 * </ul>
 *
 * <p>默认取"纯跟随"是刻意保守: 新形态先做成可开关, 验证没问题再由使用方决定要不要翻默认。
 */
public class FollowCommand extends Command {
    public FollowCommand() throws CommandException {
        super("follow", "Follows you or someone else",
                new Arg(String.class, "username", null, 0),
                // minArgCountToUseDefault=1: 只给 1 个参数（即只写玩家名）时取默认 "follow"。
                new Arg(String.class, "mode", "follow", 1));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String username = parser.get(String.class);
        String mode = parser.get(String.class);
        if (username == null) {
            if (mod.getButler().hasCurrentUser()) {
                username = mod.getButler().getCurrentUser();
            } else {
                mod.logWarning("No butler user currently present. Running this command with no user argument can ONLY be done via butler.");
                // (2026-10-03 回执) 未起任务 → 必须自报失败, 否则 bridge 的 action_done 永不会来。
                adris.altoclef.commandsystem.CommandFeedback.fail(
                        "做不到：没有指定玩家名，而当前没有 butler 用户可代指。用法 @follow <玩家名> [work]");
                finish();
                return;
            }
        }
        if ("work".equalsIgnoreCase(mode)) {
            mod.runUserTask(new FollowAndWorkTask(username, new FollowAndWorkTask.GreedySideWorkPicker(),
                    FollowRelation.COMMAND_FOLLOW_MAX_MS), this::finish);
        } else {
            // (2026-10-07 follow 关系治理) 命令级 follow 一律带 5 分钟上限：到期结束，
            // 次 bot 由 maybeStartSubBotBootstrap 回到 bootstrap（带阶段跟随），主 bot 经
            // ActionDispatcher.resumeMainline 回主线 —— 即"最终回归两个次 bot 跟主 bot"。
            mod.runUserTask(new FollowPlayerTask(username, 3, FollowRelation.COMMAND_FOLLOW_MAX_MS, true),
                    this::finish);
        }
    }
}
