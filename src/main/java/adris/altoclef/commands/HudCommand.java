package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;

import java.util.Locale;

/**
 * (2026-10-07 HUD 运行时开关) {@code @hud [status|on|off|toggle]}
 *
 * <p>控制左上角任务链 overlay (渲染入口 {@code CommandStatusOverlay.render}) 的
 * 临时隐藏 —— 录屏/看画面时不想被那堆任务文本挡住用这个。
 *
 * <ul>
 *   <li>{@code off} / {@code hide} —— 隐藏;</li>
 *   <li>{@code on} / {@code show} —— 恢复显示;</li>
 *   <li>{@code toggle} —— 取反 (等同 Alt+H 快捷键);</li>
 *   <li>{@code status} (默认) —— 打印配置值与运行时覆盖值两层状态。</li>
 * </ul>
 *
 * <p><b>与配置文件的关系</b>: 本命令只改内存覆盖位, 【不】回写
 * {@code altoclef_settings.json}, 重启客户端即恢复显示 (与 {@code @nightvision}
 * 同一约定)。想<b>永久</b>关掉请直接改配置文件里的 {@code showTaskChains}。
 *
 * <p>无任务生命期、不打断主线 (纯 UI 状态写入)。
 */
public class HudCommand extends Command {

    public HudCommand() throws CommandException {
        super("hud", "Toggle the top-left task chain overlay: on | off | toggle | status",
                new Arg(String.class, "mode", "status", 0, false));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String mode = parser.get(String.class);
        if (mode == null || mode.isEmpty()) mode = "status";
        boolean hidden = mod.getCommandStatusOverlay().isHudHidden();
        boolean configOn = mod.getModSettings() != null && mod.getModSettings().shouldShowTaskChain();

        switch (mode.toLowerCase(Locale.ROOT)) {
            case "off":
            case "hide":
            case "hidden":
            case "false":
            case "disable":
                mod.getCommandStatusOverlay().setHudHidden(true);
                log("[HUD] 已隐藏左上角任务链显示 (仅本次运行有效, 不写回配置文件; 重启即恢复)。");
                break;

            case "on":
            case "show":
            case "visible":
            case "true":
            case "enable":
                mod.getCommandStatusOverlay().setHudHidden(false);
                log("[HUD] 已恢复左上角任务链显示。");
                break;

            case "toggle":
                mod.getCommandStatusOverlay().setHudHidden(!hidden);
                log("[HUD] 切换为 " + (!hidden ? "已隐藏" : "已恢复") + "。");
                break;

            case "status":
            default:
                log("[HUD] 当前" + (hidden || !configOn ? "不显示" : "显示")
                        + " —— 配置项 showTaskChains=" + configOn
                        + ", 运行时覆盖=" + (hidden ? "隐藏" : "无"));
                break;
        }
        finish();
    }
}
