package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.BridgeHttpServer;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;

/**
 * 聊天栏入口：{@code @resume_main} —— 回到主线（重建 {@code BeatMinecraft2Task}）。
 *
 * <p>背景（2026-10-10）：该伪命令按阶段三设计只在 bridge HTTP {@code POST /command} 识别
 * （"不在 CommandExecutor 注册，仅 bridge 识别"），聊天栏输入原本静默失败
 * （CommandException 只进日志，聊天框无回显），人工测试只能 curl。
 * 现在转发到 {@link BridgeHttpServer#dispatchPseudoFromChat}，与 AI 入口走
 * {@code ActionDispatcher} 同一条流水线（限流/受理事件/终态回执/activeAction 簿记全部一致），
 * 聊天与 AI 两种来源无行为差异。
 */
public class ResumeMainCommand extends Command {

    public ResumeMainCommand() {
        super("resume_main", "回到主线任务（bridge 伪命令的聊天入口，与 AI 入口同流水线）");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        log(BridgeHttpServer.dispatchPseudoFromChat("resume_main"));
        finish();
    }
}
