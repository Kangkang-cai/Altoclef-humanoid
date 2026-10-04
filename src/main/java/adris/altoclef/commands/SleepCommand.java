package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.CommandFeedback;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasks.misc.WaitForDawnTask;
import adris.altoclef.tasks.resources.CollectBedTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.SleepOverride;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.util.math.BlockPos;

/**
 * 睡觉四态（2026-10-03 三态，2026-10-04 补第四态 skip）：<b>不是一个命令，是四个决策</b>。
 *
 * <p>动机（用户 1003 反问："想 sleep 就 sleep 吗，没床怎么办，不想睡觉想跳过睡觉怎么办？"）：
 * 睡觉在多人服里是"同步型动作"——要求全员入睡才跳夜，单方面躺下只是干等。
 * 而主线用的 {@link SleepThroughNightTask} 在没床时会自己派发 {@code CollectBedTask}
 * （96 格内找床 / 羊毛+木板造床），这是一次<b>时长不可预估的远征</b>，与当初禁掉
 * {@code @get} 的理由同型。所以把"要不要为睡一觉出门"这个决策交还给 AI：
 *
 * <ul>
 *   <li>{@code auto}（默认）——有床用床，没床就地找/造。等价原主线行为。</li>
 *   <li>{@code bed-only}——<b>只用现成的床</b>。开任务前先确认"手里有床 或 24 格内
 *       有已知的床方块"，都没有就<b>当场放弃并回话</b>（一次性出口，绝不进远征路径）。</li>
 *   <li>{@code wait-dawn}——<b>不睡</b>，站桩熬到天亮（{@link WaitForDawnTask}）。
 *       多人凑不齐全员入睡时的正解。</li>
 *   <li>{@code skip}——<b>本夜不睡、也不找床</b>，立刻回到主线干活（{@link SleepOverride}）。
 *       与 wait-dawn 的区别：wait-dawn 是"站着干等天亮"，skip 是"当作今夜不该睡觉，
 *       照常挖矿/收集/推进主线"——即主线夜里"跳过睡觉后启动的那条分支"。
 *       <b>下一个白天自动恢复</b>，不会让 bot 永久拒绝睡觉。</li>
 * </ul>
 *
 * <p>⚠️ <b>跳过睡觉 ≠ 天亮</b>：多人服里只要有玩家醒着，夜晚就不会被跳过。skip 的真实效果是
 * "别浪费时间上床，去干活，等真正的天亮（约 7 分钟真实时间）"。
 *
 * <p>起床/中止不需要新命令：任何新行动（{@code @idle} / {@code @goto} …）会顶掉当前任务，
 * 这是 UserTaskChain 单任务位的既有语义（skip 亦不例外：它不派任务，只让夜间睡眠路由让位）。
 */
public class SleepCommand extends Command {

    /** bed-only 判据半径：24 格内的已知床算"现成"。 */
    private static final double BED_ONLY_RADIUS = 24;

    public SleepCommand() throws CommandException {
        super("sleep", "Go to bed / wait for dawn / skip this night. mode: auto(default) | bed-only | wait-dawn | skip",
                new Arg(String.class, "mode", "auto", 0));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String mode = parser.get(String.class);
        if (mode == null || mode.isEmpty()) mode = "auto";
        mode = mode.toLowerCase().trim();

        switch (mode) {
            case "skip", "nosleep", "no-sleep" -> {
                SleepOverride.requestSkipThisNight();
                // "跳过后该走的分支" = 主线的真实子任务。顶层若还停在 sleep 家族的站桩/睡觉任务上,
                // 它已经没有意义 → 换成主线, 让那条分支立刻接管(等价 @resume_main)。
                boolean resumed = resumeIfSittingInSleepTask(mod);
                String m = "skip：本夜不睡、也不去找床，立刻回主线干活（下一个白天自动恢复）。"
                        + (resumed ? "已中止「站桩等天亮」并回主线。" : "");
                CommandFeedback.ok(m);
                log(m);
                finish();
            }
            case "wait-dawn", "wait", "dawn" -> {
                // 显式睡/不睡指令 = 对上一次 skip 的显式撤销(否则 skip 会一直压到白天)。
                SleepOverride.clear();
                String m = "不睡觉，熬到天亮（多人凑不齐入睡时的替代）。任何新指令即可中止。";
                CommandFeedback.ok(m);
                log(m);
                mod.runUserTask(new WaitForDawnTask(), this::finish);
            }
            case "bed-only", "bed" -> {
                SleepOverride.clear();
                if (!hasImmediateBed(mod)) {
                    // 一次性出口：明确拒绝，不进 CollectBedTask 远征路径。
                    // 回执走 failed，让 AI 知道"不是没受理，是做不到"。
                    String m = "bed-only 做不到：手里没有床、附近 24 格也没有现成的床；按约定不去远行找/造。";
                    CommandFeedback.fail(m);
                    log(m);
                    finish();
                    return;
                }
                String m = "bed-only：用现成的床睡。";
                CommandFeedback.ok(m);
                log(m);
                mod.runUserTask(new SleepThroughNightTask(), this::finish);
            }
            case "auto" -> {
                SleepOverride.clear();
                String m = "睡觉（有床用床，没床就地找/造）。";
                CommandFeedback.ok(m);
                log(m);
                mod.runUserTask(new SleepThroughNightTask(), this::finish);
            }
            default -> {
                // 未知模式必须显式失败 —— 旧实现把它静默当 auto，AI 以为下的是 bed-only。
                String m = "未知模式 '" + mode + "'；可用 auto | bed-only | wait-dawn | skip。";
                CommandFeedback.fail(m);
                log("sleep：" + m + " 已放弃。");
                finish();
            }
        }
    }

    /**
     * 顶层任务还停在 sleep 家族的「站桩/睡觉」任务上 → 换回主线。
     *
     * <p>只在那种情况下动手：主线在跑时，当前顶层任务是 {@link BeatMinecraft2Task}，sleep 只是它的
     * 子任务 —— 睡眠路由让位后子任务会被 onTick 自然顶掉，不需要（也不该）在这里重建主线。
     * 反之若眼下这个 sleep 任务就是顶层（由 {@code @sleep auto|bed-only|wait-dawn} 直接派发），
     * 不让位就没人接"跳过后该走的分支"。
     */
    private static boolean resumeIfSittingInSleepTask(AltoClef mod) {
        try {
            if (mod.getUserTaskChain() == null) return false;
            Task cur = mod.getUserTaskChain().getCurrentTask();
            if (cur instanceof WaitForDawnTask || cur instanceof SleepThroughNightTask) {
                mod.runUserTask(new BeatMinecraft2Task(), () -> {
                });
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 手里有床，或 24 格内有已知的床方块 → true。全部读取包 try/catch，绝不把异常抛进命令链。 */
    private static boolean hasImmediateBed(AltoClef mod) {
        try {
            if (mod.getItemStorage() != null && mod.getItemStorage().hasItem(ItemHelper.BED)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mod.getPlayer() == null || mod.getBlockTracker() == null) return false;
            mod.getBlockTracker().trackBlock(CollectBedTask.BEDS);
            BlockPos me = mod.getPlayer().getBlockPos();
            double maxSq = BED_ONLY_RADIUS * BED_ONLY_RADIUS;
            for (BlockPos bed : mod.getBlockTracker().getKnownLocations(CollectBedTask.BEDS)) {
                if (bed != null && bed.getSquaredDistance(me) <= maxSq) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
