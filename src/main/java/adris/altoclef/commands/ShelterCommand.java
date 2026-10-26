package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.movement.ShelterAndRecoverTask;

/**
 * (2026-10-26) 就地封墙避难：{@link ShelterAndRecoverTask} 的聊天/bridge 入口。
 * 无参数 —— BUILD（封墙）→ HEAL（吃饭回血）→ OPEN（开洞）三阶段都有硬超时，
 * 任务必定结束，不会再无限期跑。
 */
public class ShelterCommand extends Command {

    public ShelterCommand() {
        super("shelter", "Wall yourself in, recover health, then open back up");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.runUserTask(new ShelterAndRecoverTask(), this::finish);
    }
}
