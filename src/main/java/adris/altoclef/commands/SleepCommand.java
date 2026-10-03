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
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.util.math.BlockPos;

/**
 * 睡觉三态（2026-10-03）：<b>不是一个命令，是三个决策</b>。
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
 * </ul>
 *
 * <p>起床/中止不需要新命令：任何新行动（{@code @idle} / {@code @goto} …）会顶掉当前任务，
 * 这是 UserTaskChain 单任务位的既有语义。
 */
public class SleepCommand extends Command {

    /** bed-only 判据半径：24 格内的已知床算"现成"。 */
    private static final double BED_ONLY_RADIUS = 24;

    public SleepCommand() throws CommandException {
        super("sleep", "Go to bed / wait for dawn. mode: auto(default) | bed-only | wait-dawn",
                new Arg(String.class, "mode", "auto", 0));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String mode = parser.get(String.class);
        if (mode == null || mode.isEmpty()) mode = "auto";
        mode = mode.toLowerCase().trim();

        switch (mode) {
            case "wait-dawn", "wait", "dawn" -> {
                String m = "不睡觉，熬到天亮（多人凑不齐入睡时的替代）。任何新指令即可中止。";
                CommandFeedback.ok(m);
                log(m);
                mod.runUserTask(new WaitForDawnTask(), this::finish);
            }
            case "bed-only", "bed" -> {
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
                String m = "睡觉（有床用床，没床就地找/造）。";
                CommandFeedback.ok(m);
                log(m);
                mod.runUserTask(new SleepThroughNightTask(), this::finish);
            }
            default -> {
                // 未知模式必须显式失败 —— 旧实现把它静默当 auto，AI 以为下的是 bed-only。
                String m = "未知模式 '" + mode + "'；可用 auto | bed-only | wait-dawn。";
                CommandFeedback.fail(m);
                log("sleep：" + m + " 已放弃。");
                finish();
            }
        }
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
