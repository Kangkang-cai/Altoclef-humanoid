package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.NamedLandmarks;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;

/**
 * (2026-10-03 重要位置记忆共享, 桥接接口定稿 mod-bridge-landmark.md §4.2)
 * 把一条位置写进【本 bot 自己的】命名地标存储（NamedLandmarks, 与 KnowledgeMemory
 * 平级、独立落盘跨会话持久化）。主要入口是聊天侧 bridge /command 白名单；
 * 游戏内手动敲也可用（bridge 未启动时只少事件回执）。
 *
 * <p>语法：{@code @remember <名称> <x y z> [dim] [来源]}
 * <ul>
 *   <li>名称 ≤16 字符、不允许空格；重名覆盖更新；上限 20 条满则淘汰最旧；</li>
 *   <li>dim 只认 overworld/the_nether/the_end（聊天侧已把中文归一化），缺省 = 当前维度；</li>
 *   <li>来源：分享者名字（AI 说"这是 TA 告诉我的"），缺省 self。定稿 §4.2 语法上
 *       没有此参，实施时补的可选尾参 —— shared:小影 这类 source 只有带上它才成立。</li>
 * </ul>
 * 成功/失败都发 landmark_saved / landmark_failed 事件回执（NamedLandmarks 内发），
 * 命令本体瞬时完成（ActionDispatcher INSTANT, 受理即 action_done）。
 */
public class RememberCommand extends Command {

    public RememberCommand() throws CommandException {
        super("remember", "记录一条重要位置到命名地标（跨会话持久化）",
                new Arg(String.class, "名称"),
                new Arg(Double.class, "x"),
                new Arg(Double.class, "y"),
                new Arg(Double.class, "z"),
                // givenArgs ≤4（只给 名称 x y z）时用缺省 = 当前维度
                new Arg(String.class, "维度", "", 4),
                // givenArgs ≤5（给到 dim）时用缺省 = self
                new Arg(String.class, "来源", "", 5));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String name = parser.get(String.class);
        double x = parser.get(Double.class);
        double y = parser.get(Double.class);
        double z = parser.get(Double.class);
        String dim = parser.get(String.class);
        String source = parser.get(String.class);
        NamedLandmarks.remember(mod, name, x, y, z, dim, source);
        finish();
    }
}
