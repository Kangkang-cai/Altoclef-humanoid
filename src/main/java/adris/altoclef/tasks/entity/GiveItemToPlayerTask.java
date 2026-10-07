package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.ThrowCursorTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class GiveItemToPlayerTask extends Task {

    private final String _playerName;
    private final ItemTarget[] _targets;

    private final CataloguedResourceTask _resourceTask;
    private final List<ItemTarget> _throwTarget = new ArrayList<>();
    private boolean _droppingItems;

    private Task _throwTask;

    public GiveItemToPlayerTask(String player, ItemTarget... targets) {
        _playerName = player;
        _targets = targets;
        _resourceTask = TaskCatalogue.getSquashedItemTask(targets);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _droppingItems = false;
        _throwTarget.clear();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_throwTask != null && _throwTask.isActive() && !_throwTask.isFinished(mod)) {
            setDebugState("Throwing items");
            return _throwTask;
        }

        Optional<Vec3d> lastPos = mod.getEntityTracker().getPlayerMostRecentPosition(_playerName);

        if (lastPos.isEmpty()) {
            // (2026-10-02) 与 FollowPlayerTask 同口径: 区分"名字打错 / 服务器没这人 / 在线但太远"。
            // debug state 保留 "No player found" 前缀, bridge probeGive 依赖它做状态映射。
            var et = mod.getEntityTracker();
            List<String> online = et.getServerPlayerNames();
            Optional<String> closest = et.findClosestServerPlayerName(_playerName);
            if (!online.isEmpty() && closest.isEmpty()) {
                mod.logWarning("Player \"" + _playerName + "\" is not on this server. Online players: " + String.join(", ", online));
                stop(mod);
                return null;
            }
            if (closest.isPresent() && !closest.get().equalsIgnoreCase(_playerName)) {
                setDebugState("No player found/detected. Did you mean \"" + closest.get() + "\"?");
            } else {
                setDebugState("No player found/detected. Target is online but outside client tracking range; no coordinates to navigate to.");
            }
            return null;
        }
        Vec3d targetPos = lastPos.get().add(0, 0.2f, 0);

        if (_droppingItems) {
            // THROW ITEMS
            setDebugState("Throwing items");
            LookHelper.lookAt(mod, targetPos);
            for (int i = 0; i < _throwTarget.size(); ++i) {
                ItemTarget target = _throwTarget.get(i);
                if (target.getTargetCount() > 0) {
                    Optional<Slot> has = mod.getItemStorage().getSlotsWithItemPlayerInventory(false, target.getMatches()).stream().findFirst();
                    if (has.isPresent()) {
                        Slot currentlyPresent = has.get();
                        if (Slot.isCursor(currentlyPresent)) {
                            ItemStack stack = StorageHelper.getItemStackInSlot(currentlyPresent);
                            // Update target
                            target = new ItemTarget(target, target.getTargetCount() - stack.getCount());
                            _throwTarget.set(i, target);
                            Debug.logMessage("THROWING: " + has.get());
                            // (2026-09-17) 扔给玩家是故意交付, 豁免"搬运在途"拦截。
                            _throwTask = new ThrowCursorTask(true);
                            return _throwTask;
                        } else {
                            return new ClickSlotTask(currentlyPresent);
                        }
                    }
                }
            }

            if (!targetPos.isInRange(mod.getPlayer().getPos(), 4)) {
                mod.log("Finished giving items.");
                stop(mod);
                return null;
            }
            return new RunAwayFromPositionTask(6, WorldHelper.toBlockPos(targetPos));
        }

        if (!StorageHelper.itemTargetsMet(mod, _targets)) {
            setDebugState("Collecting resources...");
            return _resourceTask;
        }

        if (targetPos.isInRange(mod.getPlayer().getPos(), 1.5)) {
            if (!mod.getEntityTracker().isPlayerLoaded(_playerName)) {
                mod.logWarning("Failed to get to player \"" + _playerName + "\". We moved to where we last saw them but now have no idea where they are.");
                stop(mod);
                return null;
            }
            _droppingItems = true;
            _throwTarget.addAll(Arrays.asList(_targets));
        }

        setDebugState("Going to player...");
        // (2026-10-05) FollowPlayerTask 默认跟随距离改为 3 格; 交付物品必须在
        // 1.5 格内才能触发扔掷 (见上方 isInRange 1.5 判定), 这里显式传近距离。
        // (2026-10-07 follow 关系治理) 后两个参数 = (不限时, 不登记 /status.following)：
        // 这只是"走过去交货"的一次性位移, 不是跟随关系 —— 若登记会与"对方正跟着我"
        // 构成假互指, 导致双方互相退让而交货中断。
        return new FollowPlayerTask(_playerName, 1, 0, false);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GiveItemToPlayerTask task) {
            if (!task._playerName.equals(_playerName)) return false;
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Giving items to " + _playerName;
    }
}
