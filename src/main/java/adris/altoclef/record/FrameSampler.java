package adris.altoclef.record;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.WorldHelper;

import java.util.List;
import java.util.Map;

/**
 * (2026-09-17 数据集录制) frames.jsonl.gz 的整帧装配 —— 通道 A + B + C + G。
 *
 * <pre>
 * A 时间溯源: seq / 游戏 tick / 墙钟 / 维度
 * B 任务标签: 胜出链 + 全链仲裁优先级 + 完整任务栈 (类名/结构文案/语义状态/是否完成)
 * C 自身状态: 见 StateSampler
 * G 动作信号: 见 ActionSampler
 * </pre>
 *
 * <p>关于 L1/L2/L3: 录制端<b>不</b>做层级判定。层级来自日后离线收割的标签词表
 * (按任务类名 + 结构文案聚类), 所以这里只如实导出原始任务栈 —— 手写词表会锁死
 * 后续迭代空间, 也会把"用户新加的任务类"漏掉。
 */
final class FrameSampler {

    private FrameSampler() {}

    static String sample(AltoClef mod, long seq) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('{');

        // ---- A 通道: 时间溯源 ----
        sb.append("\"seq\":").append(seq);
        sb.append(",\"t\":").append(WorldHelper.getTicks());
        sb.append(",\"wt\":").append(System.currentTimeMillis());
        sb.append(",\"dim\":");
        Json.str(sb, dimension(mod));

        // ---- B 通道: 任务标签 ----
        appendLabels(sb, mod);

        // ---- C 通道: 自身状态 ----
        sb.append(",\"self\":").append(StateSampler.sample(mod));

        // ---- G 通道: 动作信号 ----
        sb.append(",\"act\":").append(ActionSampler.sample(mod));

        sb.append('}');
        return sb.toString();
    }

    private static String dimension(AltoClef mod) {
        try {
            if (mod.getWorld() == null) return null;
            return mod.getWorld().getRegistryKey().getValue().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void appendLabels(StringBuilder sb, AltoClef mod) {
        TaskRunner runner = mod.getTaskRunner();
        if (runner == null) {
            sb.append(",\"labels\":null");
            return;
        }
        sb.append(",\"labels\":{");

        TaskChain winner = runner.getCurrentTaskChain();
        sb.append("\"chain\":");
        Json.str(sb, winner == null ? null : winner.getName());
        sb.append(",\"chainPrio\":").append(Json.f(runner.getCachedWinnerPriority()));

        // 全链仲裁现场: 谁是赢家、输了多少 —— 反应式链抢占目标式链的证据
        sb.append(",\"chains\":[");
        boolean firstChain = true;
        try {
            List<TaskChain> chains = runner.getChains();
            Map<TaskChain, Float> prios = runner.getCachedPriorities();
            for (TaskChain c : chains) {
                if (!firstChain) sb.append(',');
                firstChain = false;
                sb.append("{\"name\":");
                Json.str(sb, c.getName());
                Float prio = prios.get(c);
                sb.append(",\"prio\":").append(prio == null ? "null" : Json.f(prio));
                sb.append(",\"active\":").append(c.isActive());
                sb.append('}');
            }
        } catch (Throwable ignored) {
        }
        sb.append(']');

        // 本 tick 的完整任务栈 (父→子顺序, 由 TaskChain.addTaskToChain 累积)
        sb.append(",\"stack\":[");
        boolean firstTask = true;
        try {
            if (winner != null) {
                List<Task> tasks = winner.getTasks();
                for (int i = 0; i < tasks.size(); i++) {
                    Task t = tasks.get(i);
                    if (!firstTask) sb.append(',');
                    firstTask = false;
                    appendTask(sb, mod, i, t);
                }
            }
        } catch (Throwable ignored) {
        }
        sb.append("]}");
    }

    private static void appendTask(StringBuilder sb, AltoClef mod, int index, Task t) {
        sb.append("{\"i\":").append(index);
        sb.append(",\"c\":");
        Json.str(sb, className(t));
        String dbg;
        try {
            dbg = t.debugString();
        } catch (Throwable e) {
            dbg = null;
        }
        sb.append(",\"d\":");
        Json.str(sb, dbg);
        String state;
        try {
            state = t.debugState();
        } catch (Throwable e) {
            state = null;
        }
        sb.append(",\"s\":");
        Json.str(sb, state);
        String fin;
        try {
            fin = String.valueOf(t.isFinished(mod));
        } catch (Throwable e) {
            fin = "null";
        }
        sb.append(",\"f\":").append(fin);
        sb.append('}');
    }

    private static String className(Task t) {
        try {
            return t.getClass().getSimpleName();
        } catch (Throwable e) {
            return "?";
        }
    }
}