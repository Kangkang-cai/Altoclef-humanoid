package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import net.minecraft.client.MinecraftClient;

public abstract class Tracker {

    protected AltoClef _mod;
    // Needs to update
    private boolean _dirty = true;

    public Tracker(TrackerManager manager) {
        manager.addTracker(this);
    }

    public void setDirty() {
        _dirty = true;
    }

    // Virtual
    protected boolean isDirty() {
        return _dirty;
    }

    protected void ensureUpdated() {
        if (!isDirty()) return;
        // (2026-09-26 死锁修复, watchdog dump #45 实锤三方锁环)
        // 主线程锁序 = updateState 实例锁 -> BaritoneHelper.MINECRAFT_LOCK;
        // 而 baritone 寻路线程在 GoalRunAwayFromEntities.heuristic /
        // ProgressiveRunAwayTask.collectThreats 里持 MINECRAFT_LOCK 回调
        // getHostiles -> ensureUpdated -> updateState, 锁序相反。
        // 寻路线程在 tracker dirty 时执行全量重建 = 与主线程互等 = 死锁,
        // 主线程还握着 EventBus.class, 事件发布全线冻结 => 整个客户端定格。
        // ⇒ 非主线程只读当前快照, 把重建留给主线程下一 tick (dirty 位保持)。
        if (!MinecraftClient.getInstance().isOnThread()) return;
        updateState();
        _dirty = false;
    }

    protected abstract void updateState();

    protected abstract void reset();
}
