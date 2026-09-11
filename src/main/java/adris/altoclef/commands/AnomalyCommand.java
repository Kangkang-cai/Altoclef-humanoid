package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.anomaly.AnomalyWatchdog;
import adris.altoclef.anomaly.TaskDurationTracker;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;

/**
 * (2026-09-11 异常看门狗, P2) {@code @anomaly [status|durations|reset]}
 *
 * 不盯着游戏时, 事后排查的入口:
 *   status     看门狗开关 / 本次会话按类型的告警计数 / 任务时长分布前几名 / 日志路径
 *   durations  只看任务时长分布 (p50 / p90 / max)
 *   reset      清空告警冷却表, 让下一 tick 立即重新判定
 *
 * 无参数等价于 status。
 */
public class AnomalyCommand extends Command {

    public AnomalyCommand() throws CommandException {
        super("anomaly", "Inspect the anomaly watchdog: status | durations | reset",
                // 可选参数: 不传时用默认值 "status" (minArgCount=0 表示 0 个实参即取默认值)。
                new Arg(String.class, "mode", "status", 0, false));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String mode = parser.get(String.class);
        if (mode == null) mode = "status";

        switch (mode.toLowerCase()) {
            case "durations":
            case "duration":
                for (String line : TaskDurationTracker.topDurationLines(15)) {
                    mod.log(line);
                }
                mod.log("[Anomaly] 完整分布见 " + Debug.SESSION_DIR + "/task_durations.log");
                break;

            case "reset":
                AnomalyWatchdog.resetCooldowns();
                mod.log("[Anomaly] 告警冷却已清空, 下一 tick 起重新判定。");
                break;

            case "status":
            default:
                for (String line : AnomalyWatchdog.statusReport()) {
                    mod.log(line);
                }
                break;
        }
        finish();
    }
}
