package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

import java.util.Locale;

/**
 * (2026-09-18 永久夜视) {@code @nightvision [status|on|off]}
 *
 * <ul>
 *   <li>{@code on}   下一 tick 起常挂夜视 (无粒子、无 HUD 图标)。</li>
 *   <li>{@code off}  下一 tick 撤掉由本开关挂上的夜视。服务器给的夜视 (喝药水) 不动。</li>
 *   <li>{@code status} 默认: 开关状态 + 当前是否真的生效 + 剩余秒数。</li>
 * </ul>
 *
 * 跟 {@code @record} 一样只改内存字段, 不回写 altoclef_settings.json; 要改默认值
 * 请直接改配置文件里的 {@code forceNightVision}。
 */
public class NightVisionCommand extends Command {

    public NightVisionCommand() throws CommandException {
        super("nightvision", "Permanent client-side night vision: status | on | off",
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
                    mod.log("[NightVision] 设置尚未加载完成, 稍后再试。");
                    break;
                }
                mod.getModSettings().setForceNightVisionEnabled(true);
                mod.log("[NightVision] 已开启, 下一 tick 生效 (仅本次运行有效, 不写回配置文件)。");
                break;

            case "off":
            case "stop":
            case "false":
            case "disable":
                if (mod.getModSettings() == null) {
                    mod.log("[NightVision] 设置尚未加载完成, 稍后再试。");
                    break;
                }
                mod.getModSettings().setForceNightVisionEnabled(false);
                mod.log("[NightVision] 已关闭, 下一 tick 撤销 (服务器给的夜视不受影响)。");
                break;

            case "status":
            default:
                boolean enabled = mod.getModSettings() != null && mod.getModSettings().isForceNightVisionEnabled();
                StatusEffectInstance eff = (mod.getPlayer() == null)
                        ? null
                        : mod.getPlayer().getStatusEffect(StatusEffects.NIGHT_VISION);
                if (eff == null) {
                    mod.log("[NightVision] 开关=" + (enabled ? "开" : "关") + ", 当前未生效"
                            + (enabled ? " (下一 tick 补挂)" : ""));
                } else {
                    mod.log("[NightVision] 开关=" + (enabled ? "开" : "关")
                            + ", 生效中, 剩余 " + String.format(Locale.ROOT, "%.1f", eff.getDuration() / 20.0) + "s");
                }
                break;
        }
        finish();
    }
}
