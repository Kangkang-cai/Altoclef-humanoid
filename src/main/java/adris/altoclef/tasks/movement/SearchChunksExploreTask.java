package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Searches/explores a continuous "blob" of chunks, attempting to load in ALL nearby chunks that are part of this "blob"
 *
 * You must define a function that determines whether a chunk is to be included within this "blob".
 *
 * For instance, if you wish to explore an entire desert, this function will return whether a chunk is a desert chunk.
 */
public abstract class SearchChunksExploreTask extends Task {

    private final Object _searcherMutex = new Object();
    private final Set<ChunkPos> _alreadyExplored = new HashSet<>();
    private ChunkSearchTask _searcher;
    private AltoClef _mod;
    private Subscription<ChunkLoadEvent> _chunkLoadedSubscription;

    // Virtual
    protected ChunkPos getBestChunkOverride(AltoClef mod, List<ChunkPos> chunks) {
        return null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _mod = mod;

        // Listen for chunk loading
        _chunkLoadedSubscription = EventBus.subscribe(ChunkLoadEvent.class, evt -> onChunkLoad(evt.chunk.getPos()));

        resetSearch(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        synchronized (_searcherMutex) {
            if (_searcher == null) {
                setDebugState("Exploring/Searching for valid chunk");
                // Explore
                return getWanderTask(mod);
            }

            if (_searcher.isActive() && _searcher.isFinished(mod)) {
                Debug.logWarning("Target object search failed.");
                _alreadyExplored.addAll(_searcher.getSearchedChunks());
                _searcher = null;
            } else if (_searcher.finished()) {
                setDebugState("Searching for target object...");
                Debug.logMessage("Search finished.");
                _alreadyExplored.addAll(_searcher.getSearchedChunks());
                _searcher = null;
            }
            //Debug.logMessage("wtf: " + (_searcher == null? "(null)" :_searcher.finished()));
            setDebugState("Searching within chunks...");
            return _searcher;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        EventBus.unsubscribe(_chunkLoadedSubscription);
    }

    // When we find a valid chunk, start our search there.
    private void onChunkLoad(ChunkPos pos) {
        if (_searcher != null) return;
        if (!this.isActive()) return;
        if (isChunkWithinSearchSpace(_mod, pos)) {
            synchronized (_searcherMutex) {
                if (!_alreadyExplored.contains(pos)) {
                    Debug.logMessage("New searcher: " + pos);
                    _searcher = new SearchSubTask(pos);
                }
            }
        }
    }

    protected Task getWanderTask(AltoClef mod) {
        return new TimeoutWanderTask(true);
    }

    protected abstract boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos);

    public boolean failedSearch() {
        return _searcher == null;
    }

    public void resetSearch(AltoClef mod) {
        //Debug.logMessage("Search reset");
        _searcher = null;
        _alreadyExplored.clear();
        // (2026-09-20 就近选起点, 预存行为修正) 原实现按 getLoadedChunks() 的
        // HashSet 迭代顺序取【第一个】匹配区块当搜索起点 —— 顺序等于随机, 会把
        // bot 派到渲染区对角的零星匹配块, 而放着眼前的目标区域不管。现场
        // 20260920-112716: bot 站在诡异森林边缘 (玩家区块 [57,1]), searcher 却从
        // [72,-11] (对角 ~300 格外的角落块) 起步, blob 秒耗尽 → 无限 wander 到
        // 别的区块。改为扫描全部已加载区块取离玩家最近的匹配块。
        // 代价: resetSearch 只在任务启动时跑一次, 原"无匹配"分支本来就要全扫;
        // 每块扫描命中即短路, 全量实测 <100ms (20260920-112716 79ms 跑完)。
        ChunkPos best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        int loadedCount = 0, matchedCount = 0;
        for (ChunkPos start : mod.getChunkTracker().getLoadedChunks()) {
            loadedCount++;
            if (!isChunkWithinSearchSpace(mod, start)) continue;
            matchedCount++;
            double cx = start.getStartX() + 8, cz = start.getStartZ() + 8;
            double dx = cx - mod.getPlayer().getX(), dz = cz - mod.getPlayer().getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = start;
            }
        }
        // 零行为诊断: 起点选了谁、离玩家多远、玩家在哪 —— 下次"往别的区块跑"
        // 一眼可判 (若 best 恒为 none 而眼前明明有目标, 说明扫描判据本身漏了)。
        Debug.logMessage("[ChunkSearch] start selection: loaded=" + loadedCount
                + " matched=" + matchedCount
                + " best=" + (best == null ? "none" : best.toString())
                + " playerChunk=[" + (mod.getPlayer().getBlockPos().getX() >> 4) + ", "
                + (mod.getPlayer().getBlockPos().getZ() >> 4) + "]"
                + (best == null ? "" : " dist=" + (int) Math.sqrt(bestDistSq) + " blocks"));
        if (best != null) {
            onChunkLoad(best);
        }
    }

    class SearchSubTask extends ChunkSearchTask {

        public SearchSubTask(ChunkPos start) {
            super(start);
        }

        @Override
        protected boolean isChunkPartOfSearchSpace(AltoClef mod, ChunkPos pos) {
            return isChunkWithinSearchSpace(mod, pos);
        }

        @Override
        public ChunkPos getBestChunk(AltoClef mod, List<ChunkPos> chunks) {
            ChunkPos override = getBestChunkOverride(mod, chunks);
            if (override != null) return override;
            return super.getBestChunk(mod, chunks);
        }

        @Override
        protected boolean isChunkSearchEqual(ChunkSearchTask other) {
            // Since we're keeping track of "_searcher", we expect the subchild routine to ALWAYS be consistent!
            return other == this;//return other instanceof SearchSubTask;
        }

        @Override
        protected String toDebugString() {
            return "Searching chunks...";
        }
    }

}
