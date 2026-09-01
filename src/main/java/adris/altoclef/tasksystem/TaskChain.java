package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;

import java.util.ArrayList;
import java.util.List;

public abstract class TaskChain {

    private final List<Task> _cachedTaskChain = new ArrayList<>();

    public TaskChain(TaskRunner runner) {
        runner.addTaskChain(this);
    }

    public void tick(AltoClef mod) {
        _cachedTaskChain.clear();
        onTick(mod);
    }

    public void stop(AltoClef mod) {
        _cachedTaskChain.clear();
        onStop(mod);
    }

    protected abstract void onStop(AltoClef mod);

    public abstract void onInterrupt(AltoClef mod, TaskChain other);

    protected abstract void onTick(AltoClef mod);

    public abstract float getPriority(AltoClef mod);

    public abstract boolean isActive();

    public abstract String getName();

    /**
     * 反射链（安全关键，需要每个游戏 tick 都执行任务树）：MLG 落水桶、
     * 岩浆/火焰/溺水逃生、怪物防御等。其余"决策链"（用户任务、食物、
     * 死亡恢复）由 TaskRunner 按 Settings.taskTickInterval 降频执行，
     * 使任务决策频率更接近人类（默认 4 tick = 5Hz）。
     * 注意：链仲裁（getPriority）始终 20Hz 运行，反射类优先级抢占不受降频影响。
     */
    public boolean shouldTickEveryTick() {
        return false;
    }

    public List<Task> getTasks() {
        return _cachedTaskChain;
    }

    void addTaskToChain(Task task) {
        _cachedTaskChain.add(task);
    }

    public String toString() {
        return getName();
    }

}
