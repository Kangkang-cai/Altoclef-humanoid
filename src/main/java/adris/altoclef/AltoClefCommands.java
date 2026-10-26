package adris.altoclef;

import adris.altoclef.commands.*;
import adris.altoclef.commandsystem.CommandException;

/**
 * Initializes altoclef's built in commands.
 */
public class AltoClefCommands {

    public AltoClefCommands() throws CommandException {
        // List commands here
        AltoClef.getCommandExecutor().registerNewCommand(
                new HelpCommand(),
                new GetCommand(),
                new FollowCommand(),
                new GiveCommand(),
                new EquipCommand(),
                new DepositCommand(),
                new StashCommand(),
                new GotoCommand(),
                new IdleCommand(),
                new CoordsCommand(),
                new StatusCommand(),
                new InventoryCommand(),
                new LocateStructureCommand(),
                new StopCommand(),
                new TestCommand(),
                new FoodCommand(),
                new ReloadSettingsCommand(),
                new GamerCommand(),
                new PunkCommand(),
                new SetGammaCommand(),
                new ListCommand(),
                new CustomCommand(),
                // (2026-09-11 P2) 异常看门狗状态查询。
                new AnomalyCommand(),
                // (2026-09-17) 训练数据集录制开关/状态。
                new RecordCommand(),
                // (2026-09-18) 永久夜视开关 (录屏画面在暗处可读, 光影下也适用)。
                new NightVisionCommand(),
                // (2026-10-03) 命名地标读写 (重要位置记忆共享, bridge /command 白名单)。
                new RememberCommand(),
                new ForgetCommand(),
                // (2026-10-03) AI 角色协调: 睡觉三态 + 需求份额 (bridge 白名单)。
                new SleepCommand(),
                new QuotaCommand(),
                // (2026-10-03) 作废自动结构记忆 (推翻定稿 §7.2: 见类注释)。
                new ForgetMemoryCommand(),
                // (2026-10-05 被动式记忆分享) 分享/记录否决口 (bridge 白名单)。
                new ShareCommand(),
                new AutoRecordCommand(),
                // (2026-10-07 交付闭环) 走过去捡起附近地上的指定物品 —— 接收方 bot 侧入口，
                // 由给方交付完成后经 PeerCommandSender 自动调用 (bridge 白名单)。
                new PickupCommand(),
                // (2026-10-26) 就地封墙避难 + 回血再开洞 (ShelterAndRecoverTask, bridge 白名单)。
                new ShelterCommand(),
                // (2026-10-07 HUD 运行时开关) 临时隐藏左上角任务链 overlay (Alt+H 同效)。
                new HudCommand(),
                // (2026-10-10) 主线干预伪命令的聊天栏入口：转发 BridgeHttpServer.dispatchPseudoFromChat,
                // 与 AI 的 HTTP /command 入口走 ActionDispatcher 同一条流水线(人工测试用, 原本聊天栏输入静默失败)。
                new PauseMainCommand(),
                new ResumeMainCommand()
                //new TestMoveInventoryCommand(),
                //    new TestSwapInventoryCommand()
        );
    }
}
