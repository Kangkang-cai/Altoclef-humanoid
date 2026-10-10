package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.BridgeHttpServer;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;

/**
 * 聊天栏入口：{@code @pause_main} —— 暂停手头任何用户任务（含主线），等待 {@code @resume_main} 回主线。
 *
 * <p>背景（2026-10-10）：该伪命令按阶段三设计只在 bridge HTTP {@code POST /command} 识别
 * （"不在 CommandExecutor 注册，仅 bridge 识别"），聊天栏输入原本静默失败
 * （CommandException 只进日志，聊天框无回显），人工测试只能 curl。
 * 现在转发到 {@link BridgeHttpServer#dispatchPseudoFromChat}，与 AI 入口走
 * {@code ActionDispatcher} 同一条流水线（限流/受理事件/终态回执/activeAction 簿记全部一致），
 * 聊天与 AI 两种来源无行为差异。
 */
public class PauseMainCommand extends Command {

    public PauseMainCommand() {
        super("pause_main", "暂停手头任何用户任务（含主线），等待 @resume_main 回主线（bridge 伪命令的聊天入口）");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        log(BridgeHttpServer.dispatchPseudoFromChat("pause_main"));
        finish();
    }
}
