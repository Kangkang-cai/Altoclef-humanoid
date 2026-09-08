package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Returns to a remembered death point and picks up any item drops that are
 * still being tracked there.
 *
 * Two-phase flow:
 *   PHASE_GOTO_GRAVE  — navigate the player to the grave block.
 *   PHASE_PICKUP      — grab every tracked ItemEntity within
 *                       PICKUP_RADIUS blocks of the grave. If no drops are
 *                       found for NO_DROPS_GIVE_UP_SECONDS we assume they were
 *                       already picked up / despawned / fell into lava and
 *                       finish early.
 *   PHASE_DONE        — onTick returns no sub-tasks and isFinished returns
 *                       true so the parent chain can clear the grave memory.
 *
 * The task also honours a per-attempt timeout (overallTimer) derived from
 * `Settings.deathRecoveryTimeoutSeconds`. If we exceed the timeout the task
 * gives up regardless of phase so we never spend longer than ~4.5 minutes
 * chasing drops that are about to despawn anyway.
 */
public class DeathRecoveryTask extends Task {

    private static final int PICKUP_RADIUS = 16;
    private static final int GRAVE_REACHED_HORIZONTAL = 3;
    private static final int GRAVE_REACHED_VERTICAL = 4;
    private static final int NO_DROPS_GIVE_UP_SECONDS = 10;

    private enum Phase { GOTO_GRAVE, PICKUP, DONE }

    private final BlockPos _gravePos;
    private final Dimension _graveDim;
    private final int _timeoutSeconds;

    private Phase _phase = Phase.GOTO_GRAVE;
    private Task _currentSub = null;
    private ItemEntity _currentPickupTarget = null;

    private final TimerGame _overallTimer;
    private final TimerGame _noDropsTimer;
    // Track whether the first onTick has logged the header.
    private boolean _started = false;

    public DeathRecoveryTask(BlockPos gravePos, Dimension graveDim, int timeoutSeconds) {
        _gravePos = gravePos;
        _graveDim = graveDim;
        _timeoutSeconds = timeoutSeconds;
        _overallTimer = new TimerGame(timeoutSeconds);
        _noDropsTimer = new TimerGame(NO_DROPS_GIVE_UP_SECONDS);
    }

    // Accessors used by DeathRecoveryChain for match checking + finish cleanup.
    public BlockPos getGravePosition() { return _gravePos; }
    public Dimension getGraveDimension() { return _graveDim; }
    public int getTimeoutSeconds() { return _timeoutSeconds; }

    @Override
    protected void onStart(AltoClef mod) {
        _phase = Phase.GOTO_GRAVE;
        _currentSub = null;
        _overallTimer.reset();
        _noDropsTimer.reset();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        Debug.logToFile("death_recovery.log", String.format(
                "[TASK STOPPED] phase=%s interrupt=%s grave=%s dim=%s",
                _phase, interruptTask == null ? "null" : interruptTask.getClass().getSimpleName(),
                _gravePos, _graveDim));
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!_started) {
            _started = true;
            String msg = String.format(
                    "Starting death recovery: dim=%s pos=(%d,%d,%d) timeout=%ds",
                    _graveDim, _gravePos.getX(), _gravePos.getY(), _gravePos.getZ(),
                    _timeoutSeconds);
            Debug.logMessage(msg);
            Debug.logToFile("death_recovery.log", "[TASK START] " + msg);
        }

        // Hard bail-out: wrong dimension (unexpected portal trip mid-recovery
        // or similar). Don't attempt cross-dimension navigation; just abort.
        if (WorldHelper.getCurrentDimension() != _graveDim) {
            Debug.logWarning("Death recovery aborted: dimension changed from "
                    + _graveDim + " to " + WorldHelper.getCurrentDimension());
            Debug.logToFile("death_recovery.log", String.format(
                    "[TASK ABORT DIM] expected=%s now=%s grave=%s",
                    _graveDim, WorldHelper.getCurrentDimension(), _gravePos));
            _phase = Phase.DONE;
            return null;
        }

        // Overall timeout safety net.
        if (_overallTimer.elapsed()) {
            Debug.logWarning(String.format(
                    "Death recovery timed out after %ds: giving up on grave (%d,%d,%d) dim=%s",
                    _timeoutSeconds, _gravePos.getX(), _gravePos.getY(), _gravePos.getZ(),
                    _graveDim));
            Debug.logToFile("death_recovery.log", String.format(
                    "[TASK TIMEOUT] timeout=%ds grave=%s dim=%s",
                    _timeoutSeconds, _gravePos, _graveDim));
            _phase = Phase.DONE;
            return null;
        }

        switch (_phase) {
            case GOTO_GRAVE:
                return tickGoToGrave(mod);
            case PICKUP:
                return tickPickup(mod);
            case DONE:
            default:
                return null;
        }
    }

    private Task tickGoToGrave(AltoClef mod) {
        if (graveReached(mod)) {
            setDebugState("Reached grave, scanning for dropped items...");
            Debug.logToFile("death_recovery.log", String.format(
                    "[GOTO DONE] arrived at grave=%s dim=%s player=%s",
                    _gravePos, _graveDim, mod.getPlayer().getBlockPos()));
            _phase = Phase.PICKUP;
            _noDropsTimer.reset();
            return null;
        }
        if (_currentSub == null || _currentSub.isFinished(mod) || !(_currentSub instanceof GetToBlockTask)) {
            _currentSub = new GetToBlockTask(_gravePos, _graveDim);
            Debug.logToFile("death_recovery.log", String.format(
                    "[GOTO START] navigating to grave=%s dim=%s currentDist=%.1f",
                    _gravePos, _graveDim, horizontalDistanceXZ(mod)));
        }
        setDebugState(String.format(
                "Going to death point (%.1f blocks away)", horizontalDistanceXZ(mod)));
        return _currentSub;
    }

    private Task tickPickup(AltoClef mod) {
        List<ItemEntity> allDrops = mod.getEntityTracker().getDroppedItems();
        Optional<ItemEntity> closest = findClosestDropNearGrave(mod, allDrops);

        if (closest.isEmpty()) {
            if (_noDropsTimer.elapsed()) {
                Debug.logMessage(String.format(
                        "No item drops found within %d blocks of grave (%d,%d,%d) for %ds — giving up.",
                        PICKUP_RADIUS, _gravePos.getX(), _gravePos.getY(), _gravePos.getZ(),
                        NO_DROPS_GIVE_UP_SECONDS));
                Debug.logToFile("death_recovery.log", String.format(
                        "[PICKUP DONE] no drops for %ds; grave=%s dim=%s",
                        NO_DROPS_GIVE_UP_SECONDS, _gravePos, _graveDim));
                _phase = Phase.DONE;
                return null;
            }
            double waited = Math.min(NO_DROPS_GIVE_UP_SECONDS, _noDropsTimer.getDuration());
            setDebugState(String.format(
                    "Near grave — waiting to see drops (%.0fs / %ds)",
                    waited, NO_DROPS_GIVE_UP_SECONDS));
            // Small wander so the player doesn't stand still and starve /
            // keeps the render distance ticking over.
            if (_currentSub == null || _currentSub.isFinished(mod)) {
                _currentSub = new TimeoutWanderTask(20);
            }
            return _currentSub;
        }

        // We have a target. Restart the "no drops" timer — as long as we
        // keep finding stuff we keep going.
        _noDropsTimer.reset();
        ItemEntity target = closest.get();

        // (2026-09-08 腾位接入, 用户"背包满了为什么不丢东西") 满背包时走近
        // 掉落物也吸不进 (物品留在地上), 只会原地打转。原实现直接 GetToEntity
        // 完全没有容量检查 —— "背包满→先腾位"逻辑只存在于 PickupDroppedItemTask,
        // 死亡捡回链路绕过了它。背包放不下该掉落物 → 先走 EnsureFreeInventorySlot
        // (丢垃圾/穿甲/合并级联), 腾出格子再捡。
        if (mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(target.getStack(), false).isEmpty()) {
            setDebugState("Inventory full — making room before picking up "
                    + target.getStack().getItem().getTranslationKey());
            Debug.logToFile("death_recovery.log", String.format(
                    "[PICKUP FULL] stack=%s x%d; running EnsureFreeInventorySlotTask",
                    target.getStack().getItem().getTranslationKey(), target.getStack().getCount()));
            if (!(_currentSub instanceof EnsureFreeInventorySlotTask)) {
                _currentSub = new EnsureFreeInventorySlotTask();
            }
            return _currentSub;
        }

        // Reuse the current GetToEntityTask if it's already chasing the
        // exact same ItemEntity reference — saves tearing down / rebuilding
        // the baritone goal every tick.
        boolean sameTarget = _currentPickupTarget == target;
        if (!sameTarget || !(_currentSub instanceof GetToEntityTask) || _currentSub.isFinished(mod)) {
            Debug.logInternal("Death recovery: new pickup target "
                    + target.getStack().getItem().getTranslationKey()
                    + " stack=" + target.getStack().getCount());
            _currentPickupTarget = target;
            _currentSub = new GetToEntityTask(target);
        }
        setDebugState(String.format(
                "Picking up: %s x%d",
                target.getStack().getItem().getName().getString(),
                target.getStack().getCount()));
        return _currentSub;
    }

    // ------------------------------------------------------------------ //
    // Completion + helpers
    // ------------------------------------------------------------------ //

    @Override
    public boolean isFinished(AltoClef mod) {
        return _phase == Phase.DONE;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DeathRecoveryTask task) {
            return _gravePos.equals(task._gravePos)
                    && _graveDim == task._graveDim
                    && _timeoutSeconds == task._timeoutSeconds;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "DeathRecoveryTask{phase=" + _phase
                + ", pos=" + _gravePos
                + ", dim=" + _graveDim
                + ", timeout=" + _timeoutSeconds + "s}";
    }

    /** Horizontal (XZ) distance between the player and the grave. */
    private double horizontalDistanceXZ(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        double dx = p.getX() - _gravePos.getX();
        double dz = p.getZ() - _gravePos.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** True once the player is close enough to the grave that nearby drops are in render range. */
    private boolean graveReached(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        int dx = Math.abs(p.getX() - _gravePos.getX());
        int dz = Math.abs(p.getZ() - _gravePos.getZ());
        int dy = Math.abs(p.getY() - _gravePos.getY());
        return dx <= GRAVE_REACHED_HORIZONTAL
                && dz <= GRAVE_REACHED_HORIZONTAL
                && dy <= GRAVE_REACHED_VERTICAL;
    }

    /**
     * Finds the nearest tracked ItemEntity that is within PICKUP_RADIUS blocks
     * of the grave (euclidean XZ distance — close enough that the walk is
     * trivial). Falls back to the player's current position when no drops
     * exist near the grave.
     *
     * (2026-09-08 粘性目标修复) 死亡掉落物是一堆散件, 两个距离几乎相同的掉落物
     * 每 tick 交替成为"最近" → sameTarget 判定失败 → 每 tick 重建 GetToEntityTask
     * → TaskPingPong 5 秒切 6 次 (09:41 日志实锤), bot 永远走不到任何一个。
     * 修复: ① 距离与最近者相近 (1.25x 内) 的都算并列候选, 当前目标仍在候选窗
     * 内就保持不换; ② 真正并列时用实体 id 稳定 tie-break。
     */
    private Optional<ItemEntity> findClosestDropNearGrave(AltoClef mod, List<ItemEntity> drops) {
        Vec3d centre = Vec3d.ofCenter(_gravePos);
        double radiusSq = (double) PICKUP_RADIUS * PICKUP_RADIUS;
        double bestSq = Double.POSITIVE_INFINITY;
        for (ItemEntity drop : drops) {
            if (drop == null || !drop.isAlive()) continue;
            double dsq = drop.squaredDistanceTo(centre);
            if (dsq <= radiusSq && dsq < bestSq) {
                bestSq = dsq;
            }
        }
        if (bestSq == Double.POSITIVE_INFINITY) {
            return Optional.empty();
        }
        List<ItemEntity> candidates = new ArrayList<>();
        for (ItemEntity drop : drops) {
            if (drop == null || !drop.isAlive()) continue;
            if (drop.squaredDistanceTo(centre) <= bestSq * 1.25) {
                candidates.add(drop);
            }
        }
        // 粘性: 当前目标仍有效且还在候选窗内 → 保持, 不换
        if (_currentPickupTarget != null && _currentPickupTarget.isAlive()
                && candidates.contains(_currentPickupTarget)) {
            return Optional.of(_currentPickupTarget);
        }
        // 并列 tie-break 用实体 id, 保证跨 tick 稳定
        return candidates.stream().min(Comparator.comparingInt(Entity::getId));
    }
}
