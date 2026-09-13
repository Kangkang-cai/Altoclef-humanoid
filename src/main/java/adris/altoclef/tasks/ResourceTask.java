package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.PickupFromContainerTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.slot.*;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Block;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The parent for all "collect an item" tasks.
 *
 * If the target item is on the ground or in a chest, will grab from those sources first.
 */
public abstract class ResourceTask extends Task implements ITaskCanForce {

    protected final ItemTarget[] _itemTargets;

    private final PickupDroppedItemTask _pickupTask;
    private ContainerCache _currentContainer;
    private final EnsureFreePlayerCraftingGridTask _ensureFreeCraftingGridTask = new EnsureFreePlayerCraftingGridTask();
    // (2026-08-22 掉落物横跳修复) 捡掉落冷却: 挖矿/收集中挖掉一个方块,
    // 掉落物立即出现 → 若马上切去捡, 形成"挖一个→捡一个→挖一个"横跳
    // (用户实测: 挖瓜/矿/木头时一撸掉就切到捡掉落物子任务)。捡过掉落物
    // 后 0.5 秒内不因掉落物切换任务 — 4 格内掉落物会自然吸/短距离即捡,
    // 无需切换; 冷却结束后统一捡远处累积的掉落物。
    // (2026-08-22 rev2) 原 2 秒冷却 + 2 格豁免过严: 挖铁时铁掉落物喷出
    // 豁免范围, 2 秒内不捡 → bot 挖隧道移动后掉落物丢失 → 背包 raw_iron
    // 永远到不了 32, 贪心"挖不完" (用户实测 19:19)。放宽到 0.5 秒 + 4 格。
    private long _lastDropPickupMs = 0;
    // (2026-08-31 挖矿/捡掉落横跳缓解) 原 500ms + 4格豁免仍挡不住"每挖一块立刻切去捡":
    // 采石/砍木挖掉一块掉落物就在脚边, 冷却过期后就又触发切换, 采集节奏被打断
    // (日志 18:20:50-21:20 挖圆石被 Pickup Dropped 反复打断 20 余次)。拉长到 1.5s,
    // 相邻掉落物会在冷却内被玩家自然吸走/就近拾取, 采集进程更连贯。
    private static final long DROP_PICKUP_COOLDOWN_MS = 1500;
    // Extra resource parameters
    private Block[] _mineIfPresent = null;
    private boolean _forceDimension = false;
    private Dimension _targetDimension;
    private BlockPos _mineLastClosest = null;

    public ResourceTask(ItemTarget[] itemTargets) {
        _itemTargets = itemTargets;
        _pickupTask = new PickupDroppedItemTask(_itemTargets, true);
    }

    public ResourceTask(ItemTarget target) {
        this(new ItemTarget[]{target});
    }

    public ResourceTask(Item item, int targetCount) {
        this(new ItemTarget(item, targetCount));
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-08-31 终局判定统一真实持有) 原 itemTargetsMetInventoryNoCursor 走
        // 缓存 getItemCountInventoryOnly, 已收集进背包的物品(刚拾取的鸡肉/木棍)被
        // 缓存滞后判成"不足" → 任务永不退出一直重复找/捡 (用户实测: 鸡已死、鸡肉
        // 在主背包, 仍卡在 Collect items from chicken)。改用统一真实持有判定, 一个
        // 都别漏, 避免"已持有却判不足"。POSSESS 语义 = 必须真背在背包里才算拥有。
        return Arrays.stream(_itemTargets).allMatch(target -> StorageHelper.itemTargetOwned(mod, StorageHelper.Ownership.POSSESS, target));
    }

    @Override
    public boolean shouldForce(AltoClef mod, Task interruptingCandidate) {
        // We have an important item target in our cursor.
        return StorageHelper.itemTargetsMetInventory(mod, _itemTargets) && !isFinished(mod)
                // This _should_ be redundant, but it'll be a guard just to make 100% sure.
                && Arrays.stream(_itemTargets).anyMatch(target -> target.matches(StorageHelper.getItemStackInCursorSlot().getItem()));
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(ItemTarget.getMatches(_itemTargets));//removeThrowawayItems(_itemTargets);
        if (_mineIfPresent != null) {
            mod.getBlockTracker().trackBlock(_mineIfPresent);
        }
        onResourceStart(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // If we have an item in an INACCESSIBLE inventory slot

        for (ItemTarget target : _itemTargets) {
            if (StorageHelper.isItemInaccessibleToContainer(mod, target)) {
                setDebugState("Moving from SPECIAL inventory slot");
                return new MoveInaccessibleItemToInventoryTask(target);
            }
        }

        // We have enough items COUNTING the cursor slot, we just need to move an item from our cursor.
        if (StorageHelper.itemTargetsMetInventory(mod, _itemTargets) && Arrays.stream(_itemTargets).anyMatch(target -> target.matches(StorageHelper.getItemStackInCursorSlot().getItem()))) {
            Optional<Slot> toMove = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), true).or(() -> StorageHelper.getGarbageSlot(mod));
            if (toMove.isEmpty()) {
                // (2026-08-22 收铁卡死修复) 光标目标物品放不回背包（背包满/
                // 无可用堆叠位）时，原实现 return null 永久挂死（用户实测：
                // 烧完铁铁锭滞留光标 + 背包快满，卡熔炉界面 3 分 44 秒）。
                // 若界面打开，关屏让 MC 自动把光标物品弹回物品栏（放不下则
                // 掉落），解除光标占位死锁；否则只能等后续腾出空间。
                setDebugState("STUCK! No slot can fit our item.");
                if (StorageHelper.isFurnaceOpen() || StorageHelper.isBigCraftingOpen() || StorageHelper.isPlayerInventoryOpen()) {
                    StorageHelper.closeScreen();
                }
                return null;
            }
            setDebugState("Moving from cursor");
            return new ClickSlotTask(toMove.get());
        }

        // (2026-08-31 合成拾取收敛) 正在用合成格(如 Craft 2x2 planks)时不切捡掉落:
        // 合成子任务自身(CraftInInventoryTask)已 shouldAvoidPickingUp=true, 这里再
        // 补上父类帧 — 否则父 ResourceTask 在合成进行中仍每 tick 因地面有同类掉落
        // 就切 PickupDropped, 形成 <Pickup Dropped> -> <Craft 2x2 planks> -> <Pickup>
        // 反复抢占 (用户 13:00 日志实锤挖木料时合成打断)。
        boolean craftingGridActive = thisOrChildSatisfies(task -> task instanceof ITaskUsesCraftingGrid);
        // (2026-09-01 挖石边挖边捡横跳修复, 用户"撸完再拾取") 当子任务是挖矿
        // 收集(MineAndCollectTask)且仍在挖未达成目标时, 父类不再因地面有同类掉落
        // 就切去捡 — 挖满目标后由 MineAndCollect 自身的掉落兜底统一收, 避免
        // <Mine And Collect cobblestone> <-> <Pickup Dropped Items cobblestone>
        // 每 1~2 秒互抢、挖一块就去捡(用户实测挖石头需 8 个被反复打断,
        // altoclef_tasks log t=246/286/395/435 实锤)。木头/床是立即捡(自身就优先
        // 捡), 不受此抑制; 圆石/石头等非"紧贴必捡"目标挖满后一次还原。
        boolean mineAndCollectActive = thisOrChildSatisfies(task -> task instanceof MineAndCollectTask);
        // (2026-09-06 捡烈焰棒乒乓修复, 用户"想拾取掉落的烈焰棒但一直捡不到,
        // 没精确导航到烈焰棒位置") pickup 任务一旦激活且未完成必须【粘住】直到
        // 自然结束 (捡到/拉黑/despawn), 冷却只该管"要不要进入捡取", 不该把
        // 正在进行的捡取拦腰打断。旧逻辑: 进 pickup 设了 1.5s 冷却 → 冷却期
        // shouldPickupDrops=false → 父任务切回要塞探索 → pickup 被停 → 冷却
        // 过期再进 → 再被停… 每 ~1.5s 乒乓 (日志实锤 11:19:12-29 七连乒乓),
        // 远处 (40+ 格) 掉落物永远走不到。
        boolean pickupTaskActive = _pickupTask.isActive() && !_pickupTask.isFinished(mod);
        if (!shouldAvoidPickingUp(mod) && !craftingGridActive && !mineAndCollectActive
                && (pickupTaskActive || shouldPickupDrops(mod))) {
            // Check if items are on the floor. If so, pick em up.
            // pickupTaskActive 时不强求 itemDropped: tracker 缓存单 tick 抖动
            // 不该打断一次正常进行的捡取。
            if (pickupTaskActive || mod.getEntityTracker().itemDropped(_itemTargets)) {

                // If we're picking up a pickaxe (we can't go far underground or mine much)
                if (PickupDroppedItemTask.isIsGettingPickaxeFirst(mod)) {
                    if (_pickupTask.isCollectingPickaxeForThis()) {
                        setDebugState("Picking up (pickaxe first!)");
                        // Our pickup task is the one collecting the pickaxe, keep it going.
                        _lastDropPickupMs = System.currentTimeMillis();
                        return _pickupTask;
                    }
                    // Only get items that are CLOSE to us.
                    Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), _itemTargets);
                    if (closest.isPresent() && !closest.get().isInRange(mod.getPlayer(), 10)) {
                        return onResourceTick(mod);
                    }
                }

                double range = mod.getModSettings().getResourcePickupRange();
                if (pickupTaskActive) {
                    // 粘住: 任务在进行中就继续, 不看距离/冷却。
                    setDebugState("Picking up");
                    _lastDropPickupMs = System.currentTimeMillis();
                    return _pickupTask;
                }
                Optional<ItemEntity> closest = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), _itemTargets);
                if (range < 0 || (closest.isPresent() && closest.get().isInRange(mod.getPlayer(), range))) {
                    setDebugState("Picking up");
                    _lastDropPickupMs = System.currentTimeMillis();
                    return _pickupTask;
                }
            }
        }

        // Check for chests and grab resources from them.
        if (_currentContainer == null) {
            List<ContainerCache> containersWithItem = mod.getItemStorage().getContainersWithItem(Arrays.stream(_itemTargets).reduce(new Item[0], (items, target) -> ArrayUtils.addAll(items, target.getMatches()), ArrayUtils::addAll));
            // (2026-09-05 熔炉不是资源箱子, 用户"烧金子到一半被打断拆炉") 熔炉一烧出
            // 1 个产物, 这里的"附近容器有货就去拿"就会把正在 Waiting 等烧完的
            // SmeltInFurnaceTask 子任务拦腰打断 (日志 15:20:28: <Collecting 32 gold>
            // 从 <Doing stuff in [furnace]> Waiting 切到 <Picking up from container>,
            // 第一块金锭烧好正好 10 秒)。链上熔炼任务一断, isMainLineBusy 误判空闲 →
            // 顶部"熔炉即收"侧线点火 → EmptyFurnaceTask 把刚放好正在烧的炉子取空
            // 拆掉, 生金/煤丢一地 → 主线再造炉重熔, 死循环。炉内物品归
            // SmeltInFurnaceTask(烧炼收产物)/EmptyFurnaceTask(取空拆炉) 专属管理,
            // ResourceTask 的容器拾取只认纯存储容器, 熔炉类 (熔炉/高炉/烟熏炉) 排除。
            containersWithItem.removeIf(cache -> cache.getContainerType() == ContainerType.FURNACE);
            if (!containersWithItem.isEmpty()) {
                ContainerCache closest = containersWithItem.stream().min(StlHelper.compareValues(container -> container.getBlockPos().getSquaredDistance(mod.getPlayer().getPos()))).get();
                if (closest.getBlockPos().isWithinDistance(mod.getPlayer().getPos(), mod.getModSettings().getResourceChestLocateRange())) {
                    _currentContainer = closest;
                }
            }
        }
        if (_currentContainer != null) {
            Optional<ContainerCache> container = mod.getItemStorage().getContainerAtPosition(_currentContainer.getBlockPos());
            if (container.isPresent()) {
                if (Arrays.stream(_itemTargets).noneMatch(target -> container.get().hasItem(target.getMatches()))) {
                    _currentContainer = null;
                } else {
                    // We have a current chest, grab from it.
                    setDebugState("Picking up from container");
                    return new PickupFromContainerTask(_currentContainer.getBlockPos(), _itemTargets);
                }
            } else {
                _currentContainer = null;
            }
        }

        // We may just mine if a block is found.
        if (_mineIfPresent != null) {
            ArrayList<Block> satisfiedReqs = new ArrayList<>(Arrays.asList(_mineIfPresent));
            satisfiedReqs.removeIf(block -> !StorageHelper.miningRequirementMet(mod, MiningRequirement.getMinimumRequirementForBlock(block)));
            if (!satisfiedReqs.isEmpty()) {
                if (mod.getBlockTracker().anyFound(satisfiedReqs.toArray(Block[]::new))) {
                    Optional<BlockPos> closest = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), _mineIfPresent);
                    if (closest.isPresent() && closest.get().isWithinDistance(mod.getPlayer().getPos(), mod.getModSettings().getResourceMineRange())) {
                        _mineLastClosest = closest.get();
                    }
                    if (_mineLastClosest != null) {
                        if (_mineLastClosest.isWithinDistance(mod.getPlayer().getPos(), mod.getModSettings().getResourceMineRange() * 1.5 + 20)) {
                            return new MineAndCollectTask(_itemTargets, _mineIfPresent, MiningRequirement.HAND);
                        }
                    }
                }
            }
        }

        // Make sure that items don't get stuck in the player crafting grid. May be an issue if a future task isn't a resource task.
        if(StorageHelper.isPlayerInventoryOpen()){
            boolean clearInProgress = _ensureFreeCraftingGridTask.isActive();
            // (2026-09-13 交易期暂缓清格, 用户"交易途中为什么会被清理合成格打断")
            // 正在进行的清格照常跑完; 尚未开始的清格允许子任务 (猪灵交易) 申请
            // 暂缓 — 清格要开背包屏+挪光标, 是一段显眼的插入动作, 插在丢金锭
            // 节奏里很扎眼 (日志 23:05:59 实锤 Trading with Piglins 被插 9s
            // "Breaking the crafting grid")。
            if (!shouldDeferCraftingGridClear(mod) || clearInProgress) {
                if (!(thisOrChildSatisfies(task -> task instanceof ITaskUsesCraftingGrid)) || clearInProgress){
                    for(Slot slot : PlayerSlot.CRAFT_INPUT_SLOTS){
                        if(!StorageHelper.getItemStackInSlot(slot).isEmpty()) {
                            return _ensureFreeCraftingGridTask;
                        }
                    }
                }
            }
        }

        return onResourceTick(mod);
    }

    /**
     * (2026-09-13) 子任务可申请暂缓"清空 2x2 合成格"兜底。仅当清格尚未开始
     * 时生效 (正在清的一律跑完, 不留半格状态)。默认 false = 永不让位 (原行为)。
     */
    protected boolean shouldDeferCraftingGridClear(AltoClef mod) {
        return false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        if (_mineIfPresent != null) {
            mod.getBlockTracker().stopTracking(_mineIfPresent);
        }
        onResourceStop(mod, interruptTask);
    }

    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof ResourceTask t) {
            if (!isEqualResource(t)) return false;
            return Arrays.equals(t._itemTargets, _itemTargets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append(toDebugStringName()).append(": [");
        int c = 0;
        for (ItemTarget target : _itemTargets) {
            result.append(target != null? target.toString() : "(null)");
            if (++c != _itemTargets.length) {
                result.append(", ");
            }
        }
        result.append("]");
        return result.toString();
    }

    protected boolean isInWrongDimension(AltoClef mod) {
        if (_forceDimension) {
            return WorldHelper.getCurrentDimension() != _targetDimension;
        }
        return false;
    }

    /**
     * (2026-08-22 掉落物横跳修复) 是否应切到"捡掉落物"子任务。
     * 冷却期 (刚捡过掉落物 2 秒内) 不切换 — 近处 (2 格内) 掉落物会自然吸,
     * 无需切换任务; 否则挖矿时"挖一个→捡一个→挖一个"横跳。
     */
    private boolean shouldPickupDrops(AltoClef mod) {
        if (System.currentTimeMillis() - _lastDropPickupMs >= DROP_PICKUP_COOLDOWN_MS) return true;
        Optional<ItemEntity> close = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), _itemTargets);
        return close.isPresent() && close.get().isInRange(mod.getPlayer(), 2);
    }

    protected Task getToCorrectDimensionTask(AltoClef mod) {
        return new DefaultGoToDimensionTask(_targetDimension);
    }

    public ResourceTask mineIfPresent(Block[] toMine) {
        _mineIfPresent = toMine;
        return this;
    }

    public ResourceTask forceDimension(Dimension dimension) {
        _forceDimension = true;
        _targetDimension = dimension;
        return this;
    }

    protected abstract boolean shouldAvoidPickingUp(AltoClef mod);

    protected abstract void onResourceStart(AltoClef mod);

    protected abstract Task onResourceTick(AltoClef mod);

    protected abstract void onResourceStop(AltoClef mod, Task interruptTask);

    protected abstract boolean isEqualResource(ResourceTask other);

    protected abstract String toDebugStringName();

    public ItemTarget[] getItemTargets() {
        return _itemTargets;
    }
}
