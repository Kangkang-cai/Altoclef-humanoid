package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.NamedLandmarks;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.CommandFeedback;

/**
 * (2026-10-05 被动式记忆分享·记录侧否决口) 控制共享来源（source=shared:名）的
 * 坐标是否自动落库到命名地标。
 *
 * <p>用法：{@code @autorecord <on|off>}
 * <ul>
 *   <li>默认 on —— 对端编排层把别的 bot 的 {@code memory_shared} 自动转成
 *       本 bot 的 {@code @remember ... source=shared:名}，直接落库；</li>
 *   <li>{@code @autorecord off} —— shared 来源的写入被拒（landmark_failed 回执），
 *       self 来源（AI 主动记的）不受影响；</li>
 *   <li>事后否决仍可用 {@code @forget <名>} 删除已记下的。</li>
 * </ul>
 *
 * <p>本命令是<b>状态写入</b>：无任务生命期、不打断主线（INSTANT, 不 replaceUserTask）。
 */
public class AutoRecordCommand extends Command {

    public AutoRecordCommand() throws CommandException {
        super("autorecord", "AI: toggle auto-recording coordinates shared by peers (@autorecord on|off)",
                new Arg(String.class, "开关"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String action = parser.get(String.class);
        boolean enabled;
        if ("on".equalsIgnoreCase(action)) {
            enabled = true;
        } else if ("off".equalsIgnoreCase(action)) {
            enabled = false;
        } else {
            String m = "参数只认 on/off —— " + action + "。用法 @autorecord <on|off>";
            CommandFeedback.fail(m);
            log("autorecord：" + m);
            finish();
            return;
        }
        String m = NamedLandmarks.setAutoRecordShared(enabled);
        CommandFeedback.ok(m);
        log("autorecord：" + m);
        finish();
    }
}
