package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.record.RecordManager;

import java.util.Locale;

/**
 * (2026-09-17 数据集录制) {@code @record [status|on|off]}
 *
 * <ul>
 *   <li>{@code on}   开始录制 (下一 tick 起建会话目录并写第一帧)。会顺带清掉
 *       "出错自禁用"状态, 让上一次因异常停掉的录制有机会重来。</li>
 *   <li>{@code off}  停止录制。会话收尾是【必须】的 —— 必须让写盘队列排空、
 *       gzip 尾部写全, 否则文件是半个 gzip 读不出来。收尾由 RecordManager.tick
 *       检测到开关变 false 时执行, 所以这里是"下一 tick 生效"而不是立即。</li>
 *   <li>{@code status} 默认: 开关/活跃会话/会话目录/各通道已落盘行数/丢弃行数。</li>
 * </ul>
 *
 * 注意: 只改内存里的设置字段, 不回写 altoclef_settings.json。想永久开启请直接改
 * settings 文件里的 {@code recordDatasetEnabled}。
 */
public class RecordCommand extends Command {

    public RecordCommand() throws CommandException {
        super("record", "Training dataset recorder: status | on | off",
                // 可选参数: 不传时默认 status (minArgCountToUseDefault=0)
                new Arg(String.class, "mode", "status", 0, false));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String mode = parser.get(String.class);
        if (mode == null) mode = "status";

        switch (mode.toLowerCase(Locale.ROOT)) {
            case "on":
            case "start":
            case "true":
            case "enable":
                if (mod.getModSettings() == null) {
                    mod.log("[Record] 设置尚未加载完成, 稍后再试。");
                    break;
                }
                RecordManager.clearDisabled();
                mod.getModSettings().setRecordDatasetEnabled(true);
                mod.log("[Record] 已开启, 下一 tick 起开始录制 (仅本次运行有效, 不会写回配置文件)。");
                break;

            case "off":
            case "stop":
            case "false":
            case "disable":
                if (mod.getModSettings() == null) {
                    mod.log("[Record] 设置尚未加载完成, 稍后再试。");
                    break;
                }
                String dir = RecordManager.getSessionDirString();
                mod.getModSettings().setRecordDatasetEnabled(false);
                if (dir != null) {
                    mod.log("[Record] 已停止, 下一 tick 收尾刷盘 (排空队列 + 补全 gzip + 写 meta)。目录: " + dir);
                } else {
                    mod.log("[Record] 已停止 (当时没有活跃会话)。");
                }
                break;

            case "status":
            default:
                for (String line : RecordManager.statusReport(mod)) {
                    mod.log(line);
                }
                break;
        }
        finish();
    }
}