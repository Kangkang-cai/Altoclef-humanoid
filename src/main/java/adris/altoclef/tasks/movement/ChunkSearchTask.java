package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Use to walk through and search interconnected structures or biomes.
 * <p>
 * Example use cases:
 * - Search a dark forest for a woodland mansion and avoid going to different biomes
 * - Search a nether fortress for blaze spawners
 * - Search a stronghold for the portal
 */
abstract class ChunkSearchTask extends Task {

    private final BlockPos _startPoint;
    private final Object _searchMutex = new Object();
    // We're either searched or will be searched later.
    private final Set<ChunkPos> _consideredAlready = new HashSet<>();
    // We definitely were searched before.
    private final Set<ChunkPos> _searchedAlready = new HashSet<>();
    private final ArrayList<ChunkPos> _searchLater = new ArrayList<>();
    private final ArrayList<ChunkPos> _justLoaded = new ArrayList<>();
    private boolean _first = true;
    private boolean _finished = false;

    private Subscription<ChunkLoadEvent> _onChunkLoad;
    // (2026-09-20 sticky target, 预存行为修正) 上一拍选中的目标区块。见 onTick。
    private ChunkPos _currentTarget = null;

    public ChunkSearchTask(BlockPos startPoint) {
        _startPoint = startPoint;
    }

    public ChunkSearchTask(ChunkPos chunkPos) {
        this(chunkPos.getStartPos().add(1, 1, 1));
    }

    public Set<ChunkPos> getSearchedChunks() {
        return _searchedAlready;
    }

    public boolean finished() {
        return _finished;
    }

    @Override
    protected void onStart(AltoClef mod) {

        //Debug.logMessage("(deleteme) start. Finished: " + _finished);
        if (_first) {
            _finished = false;
            _first = false;
            _currentTarget = null;
            ChunkPos startPos = mod.getWorld().getChunk(_startPoint).getPos();
            synchronized (_searchMutex) {
                searchChunkOrQueueSearch(mod, startPos);
            }
        }

        _onChunkLoad = EventBus.subscribe(ChunkLoadEvent.class, evt -> {
            WorldChunk chunk = evt.chunk;
            if (chunk == null) return;
            synchronized (_searchMutex) {
                if (!_searchedAlready.contains(chunk.getPos())) {
                    _justLoaded.add(chunk.getPos());
                }
            }
        });
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // WTF This is a horrible idea.
        // Backup in case if chunk search fails?
        //onChunkLoad((WorldChunk) mod.getWorld().getChunk(mod.getPlayer().getBlockPos()));

        synchronized (_searchMutex) {
            // Search all items from _justLoaded that we ought to search.
            for (ChunkPos justLoaded : _justLoaded) {
                if (_searchLater.contains(justLoaded)) {
                    // Search this one. If we succeed, we no longer need to search.
                    if (trySearchChunk(mod, justLoaded)) {
                        _searchLater.remove(justLoaded);
                    }
                }
            }
            _justLoaded.clear();
        }

        // Now that we have an updated map, go to the nearest
        // (2026-09-20 sticky target, 预存行为修正) 原实现每 tick 重算 getBestChunk:
        // 新邻块入队 / 玩家位置一变, best 就可能换成别的区块 → GetToChunkTask 每次换
        // 目标都被重建 (onStart → onLostControl) → baritone 作废当前路径整段重算。
        // 现场 20260920-112716 tasks.log: 目标 3~10s 一换 ([69,-10]→[70,-10]→[71,-10]
        // →[71,-11]→[72,-11], 全程没走到任何一个), 用户观感 = "远距离目标走十几格就
        // 停下来重算路径"。修法: 目标粘滞 —— 当前目标只要还在 _searchLater (未被
        // 搜过/未失效) 就不换, 真正走进并搜完该块后才重选最近的下一块。途经新加载
        // 的区块不受影响: 它们照常经 _justLoaded 被扫描入队。
        ChunkPos closest;
        if (_currentTarget != null && _searchLater.contains(_currentTarget)) {
            closest = _currentTarget;
        } else {
            closest = getBestChunk(mod, _searchLater);
            _currentTarget = closest;
        }

        if (closest == null) {
            _finished = true;
            _currentTarget = null;
            Debug.logWarning("Failed to find any chunks to go to. If we finish, that means we scanned all possible chunks.");
            //Debug.logMessage("wtf??????: " + _finished);
            return null;
        }

        return new GetToChunkTask(closest);
    }

    // Virtual
    protected ChunkPos getBestChunk(AltoClef mod, List<ChunkPos> chunks) {
        double lowestScore = Double.POSITIVE_INFINITY;
        ChunkPos bestChunk = null;
        for (ChunkPos toSearch : chunks) {
            double cx = (toSearch.getStartX() + toSearch.getEndX() + 1) / 2.0, cz = (toSearch.getStartZ() + toSearch.getEndZ() + 1) / 2.0;
            double px = mod.getPlayer().getX(), pz = mod.getPlayer().getZ();
            double distanceSq = (cx - px) * (cx - px) + (cz - pz) * (cz - pz);
            double distanceToCenterSq = new Vec3d(_startPoint.getX() - cx, 0, _startPoint.getZ() - cz).lengthSquared();
            double score = distanceSq + distanceToCenterSq * 0.8;
            if (score < lowestScore) {
                lowestScore = score;
                bestChunk = toSearch;
            }
        }
        return bestChunk;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        EventBus.unsubscribe(_onChunkLoad);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _searchLater.size() == 0;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ChunkSearchTask task) {
            if (!task._startPoint.equals(_startPoint)) return false;
            return isChunkSearchEqual(task);
        }
        return false;
    }

    private void searchChunkOrQueueSearch(AltoClef mod, ChunkPos pos) {
        // Don't search/consider this chunk again.
        if (_consideredAlready.contains(pos)) {
            return;
        }
        _consideredAlready.add(pos);

        if (!trySearchChunk(mod, pos)) {
            // We'll check it later if we haven't searched it.
            if (!_searchedAlready.contains(pos)) {
                _searchLater.add(pos);
            }
        }
    }

    /**
     * Try to search the chunk.
     *
     * @param pos chunk to search
     * @return true if we're DONE searching this chunk
     * false if we need to SEARCH IT IN PERSON
     */
    private boolean trySearchChunk(AltoClef mod, ChunkPos pos) {
        // Do NOT search later.
        if (_searchedAlready.contains(pos)) {
            return true;
        }
        if (mod.getChunkTracker().isChunkLoaded(pos)) {
            _searchedAlready.add(pos);
            if (isChunkPartOfSearchSpace(mod, pos)) {
                // This chunk may lead to more, so either search or enqueue its neighbors.
                searchChunkOrQueueSearch(mod, new ChunkPos(pos.x + 1, pos.z));
                searchChunkOrQueueSearch(mod, new ChunkPos(pos.x - 1, pos.z));
                searchChunkOrQueueSearch(mod, new ChunkPos(pos.x, pos.z + 1));
                searchChunkOrQueueSearch(mod, new ChunkPos(pos.x, pos.z - 1));
            }
            return true;
        }
        return false;
    }

    protected abstract boolean isChunkPartOfSearchSpace(AltoClef mod, ChunkPos pos);

    protected abstract boolean isChunkSearchEqual(ChunkSearchTask other);
}
