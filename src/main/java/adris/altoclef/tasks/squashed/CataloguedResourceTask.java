package adris.altoclef.tasks.squashed;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.UpgradeInSmithingTableTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask.TaskSquasher;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;

public class CataloguedResourceTask extends ResourceTask {


    private final TaskSquasher _squasher;
    private final ItemTarget[] _targets;
    private final List<ResourceTask> _tasksToComplete;

    // [2026-08-22 ping-pong 修复] 当前粘住的子任务: 一旦选中就执行到完成。
    private ResourceTask _activeTask = null;

    public CataloguedResourceTask(boolean squash, ItemTarget... targets) {
        super(targets);
        _squasher = new TaskSquasher();
        _targets = targets;
        _tasksToComplete = new ArrayList<>(targets.length);

        for (ItemTarget target : targets) {
            if (target != null) {
                // (2026-09-29 崩溃修复) getItemTask 在目标未注册时返回 null —— 旧代码
                // 把 null 直接塞进 _tasksToComplete, 随后 TaskSquasher.addTask 第
                // 129 行 t.getClass() NPE 崩客户端 (现场 20260929-18:48: PlaceBlockTask
                // → crying_obsidian/end_stone 未注册)。这里就地跳过并点名目标, 让日志
                // 直接给出"该注册什么", 而不是崩在两层之外。
                ResourceTask task = TaskCatalogue.getItemTask(target);
                if (task != null) {
                    _tasksToComplete.add(task);
                } else {
                    Debug.logWarning("Skipping unregistered item target in CataloguedResourceTask: "
                            + target + " (no TaskCatalogue entry; nothing to run for it)");
                }
            }
        }

        if (squash) {
            squashTasks(_tasksToComplete);
        }
    }

    public CataloguedResourceTask(ItemTarget... targets) {
        this(true, targets);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _activeTask = null;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // [2026-08-22 ping-pong 修复] 旧逻辑每 tick 重选"第一个目标未满足"的
        // 子任务: iron_sword 工作台合成 ↔ 2x2 木板/工作台/熔炉合成轮流上场,
        // 互相清对方的光标/合成格、关对方的界面, 形成大横跳 (tasks.log
        // 11:37:23-11:39:45: TaskPingPong 6x/5s 多组, EnsureFreeCursorSlotTask
        // 逐槽换位找不到落点)。改为粘性选择: 一旦选中子任务, 坚持到它所有
        // 目标满足 (isFinished, 不含光标的背包判定) 才轮换到下一个。
        if (_activeTask != null && _activeTask.isFinished(mod)) {
            _activeTask = null;
        }
        if (_activeTask == null) {
            for (ResourceTask task : _tasksToComplete) {
                if (!task.isFinished(mod)) {
                    _activeTask = task;
                    break;
                }
            }
        }
        return _activeTask;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        for (ResourceTask task : _tasksToComplete) {
            for (ItemTarget target : task.getItemTargets()) {
                if (!StorageHelper.itemTargetsMetInventory(mod, target)) return false;
            }
        }
        // All targets are met.
        return true;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Useless
        return false;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CataloguedResourceTask task) {
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Get catalogued: " + ArrayUtils.toString(_targets);
    }

    private void squashTasks(List<ResourceTask> tasks) {
        _squasher.addTasks(tasks);
        tasks.clear();
        tasks.addAll(_squasher.getSquashed());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static class TaskSquasher {

        private final Map<Class, adris.altoclef.tasks.squashed.TypeSquasher> _squashMap = new HashMap<>();

        private final List<ResourceTask> _unSquashableTasks = new ArrayList<>();

        public TaskSquasher() {
            _squashMap.put(CraftInTableTask.class, new CraftSquasher());
            _squashMap.put(UpgradeInSmithingTableTask.class, new SmithingSquasher());
            //_squashMap.put(MineAndCollectTask.class)
        }

        public void addTask(ResourceTask t) {
            // (2026-09-29 崩溃修复 防御层 ①) 空任务直接跳过: 未注册目标曾让 null
            // 一路飘到这里, t.getClass() 直接崩客户端 (现场 20260929-18:48)。
            // 现在源头 (构造器) 与这里各拦一道, 任何调用方都不会再把 null 变崩溃。
            if (t == null) {
                Debug.logWarning("TaskSquasher: skipping null task (unregistered catalog target?)");
                return;
            }
            Class type = t.getClass();
            if (_squashMap.containsKey(type)) {
                _squashMap.get(type).add(t);
            } else {
                //Debug.logMessage("Unsquashable: " + type + ": " + t);
                _unSquashableTasks.add(t);
            }
        }

        public void addTasks(List<ResourceTask> tasks) {
            for (ResourceTask task : tasks) {
                addTask(task);
            }
        }

        public List<ResourceTask> getSquashed() {
            List<ResourceTask> result = new ArrayList<>();

            for (Class type : _squashMap.keySet()) {
                result.addAll(_squashMap.get(type).getSquashed());
            }
            result.addAll(_unSquashableTasks);

            return result;
        }
    }


}
