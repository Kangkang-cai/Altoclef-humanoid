package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;

import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;


public class LootContainerTask extends Task {
    private boolean _weDoneHere = false;
    private final Predicate<ItemStack> _check;
    public final BlockPos chest;
    // (2026-09-01 矿车箱子, 用户"矿洞里看见矿车箱子不开") 非 null 时表示容器是
    // 一个矿车箱子实体 (ChestMinecartEntity), 开箱需右键实体而非方块。屏幕打开后
    // 复用同一套槽位拾取逻辑 (矿车箱子屏幕是 GenericContainerScreenHandler,
    // ContainerType.CHEST 匹配)。
    private final Entity _entity;
    public final List<Item> targets = new ArrayList<>();

    // (2026-08-27 拿了又放反复修复) 点箱子槽拿起物品后, 客户端光标有约 4-5 tick
    // 的同步延迟。旧逻辑每 tick 重扫: 光标仍读为空 + 箱子槽还没被清 → 又去点
    // 同一个箱子槽 — 而此时光标其实已拿着该物品 → 等于把物品放回箱子, 再拿起,
    // 反复几次光标才真正同步、才点到背包 (用户实测"拿了又放四次才放进背包")。
    // 这里记录刚发起的 PICKUP 槽位: 宽限期内光标未出现目标物品就不再重复点
    // 该槽(会拿起又放下), 等光标到位。
    private Slot _pendingPickup = null;
    private long _pendingPickupSinceMs = 0;
    private static final long PICKUP_SYNC_GRACE_MS = 500;

    public LootContainerTask(BlockPos chestPos, List<Item> items) {
        this(chestPos, null, items, x -> true);
    }

    public LootContainerTask(BlockPos chestPos, List<Item> items, Predicate<ItemStack> pred) {
        this(chestPos, null, items, pred);
    }

    /**
     * (2026-09-01 矿车箱子) 容器是一个实体 (ChestMinecartEntity), 开箱用
     * 右键实体而非 InteractWithBlockTask。
     */
    public LootContainerTask(Entity entity, List<Item> items, Predicate<ItemStack> pred) {
        this(BlockPos.ORIGIN, entity, items, pred);
    }

    private LootContainerTask(BlockPos chestPos, Entity entity, List<Item> items, Predicate<ItemStack> pred) {
        chest = chestPos;
        _entity = entity;
        targets.addAll(items);
        _check = pred;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        for (Item item : targets) {
            if (!mod.getBehaviour().isProtected(item)) {
                mod.getBehaviour().addProtectedItems(item);
            }
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if(!ContainerType.screenHandlerMatches(ContainerType.CHEST)) {
            if (_entity != null) {
                return openMinecart(mod);
            }
            setDebugState("Interact with container");
            return new InteractWithBlockTask(chest);
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            // 光标已拿到物品 → 放回背包, 并清空"待确认拿起"(拿起已完成)。
            _pendingPickup = null;
            // (2026-09-11 存箱抢断修复, 现场 10:35:14-17: 深板岩圆石拿起/放回 5 次
            // 后整堆扔地) 旧链路: StoreGarbageInContainerTask 阶段1 把垃圾拿上光标后,
            // 本任务下一 tick 重跑至此, 光标非空 → 走"放回背包" — 而全背包唯一空位
            // 恰是刚腾出的那个垃圾槽, 等于原样放回 → 阶段2(存入箱子)一个 tick 都没
            // 轮到 → 拿起/放回循环, 最终走 ThrowCursor 扔地。
            // 闸门: 开着储物箱且光标是垃圾/大堆建材(与存箱流程同口径)且容器装得下
            // → 直接存入容器。拾取目标(床/食物/矿石)都是 protected 物品, 永不命中
            // 此闸, 正常捡箱不受影响; 容器满的边界由 Store 阶段1 的存前预检兜住。
            if (StoreGarbageInContainerTask.isStorageContainerOpen()
                    && (StorageHelper.isBulkDisposalItem(cursor.getItem())
                        || ItemHelper.canThrowAwayStack(mod, cursor))) {
                List<Slot> fits = mod.getItemStorage().getSlotsThatCanFitInOpenContainer(cursor, true);
                if (!fits.isEmpty()) {
                    setDebugState("Storing cursor junk into container");
                    return new ClickSlotTask(fits.get(0));
                }
            }
            Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (toFit.isPresent()) {
                setDebugState("Putting cursor in inventory");
                return new ClickSlotTask(toFit.get());
            } else {
                setDebugState("Ensuring space");
                return new EnsureFreeInventorySlotTask();
            }
        }
        Optional<Slot> optimal = getAMatchingSlot(mod);
        if (optimal.isEmpty()) {
            _weDoneHere = true;
            return null;
        }
        // (2026-09-07 用户现场: 想拿箱子里的面包, 背包全满 → 举着面包发呆卡死)
        // 拿之前先看背包放不放得下这件东西 (含并进已有的部分堆); 放不下就先腾位。
        // 顺序很关键: 先腾位时光标是空的, EnsureFreeInventorySlotTask 能走"把垃圾
        // 存进这个箱子"这条无损路径; 若先拿起来再发现没地方放, 光标被占住后所有
        // 槽位操作都退化成"交换", 腾位链路施展不开 (23:15:37 光标=bread 卡死)。
        ItemStack want = StorageHelper.getItemStackInSlot(optimal.get());
        if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(want, true).isEmpty()) {
            setDebugState("Making room before looting");
            return new EnsureFreeInventorySlotTask();
        }
        // (2026-08-27 拿了又放反复修复) 光标仍空但刚点过的拿起槽还在同步宽限期:
        // 物品已拿起(光标稍后到位)却还没被此槽清掉 → 别再重复点它, 否则会把
        // 光标物品放回箱子(拿起又放下)。等光标真正到位。
        if (_pendingPickup != null
                && System.currentTimeMillis() - _pendingPickupSinceMs < PICKUP_SYNC_GRACE_MS
                && optimal.get().equals(_pendingPickup)) {
            Debug.logToFile("altoclef_diag.log",
                    "[ContainerSync] waiting for pickup of " + targets
                            + " from " + optimal.get() + " (cursor sync pending)");
            return null;
        }
        _pendingPickup = optimal.get();
        _pendingPickupSinceMs = System.currentTimeMillis();
        setDebugState("Looting items: " + targets);
        return new ClickSlotTask(optimal.get());
    }

    /**
     * (2026-09-01 矿车箱子) 走近矿车箱子实体并在交互距离内右键打开其容器。
     * 右键用 interactionManager.interactEntity 触发 (与 block 交互同机制)。
     */
    private Task openMinecart(AltoClef mod) {
        Entity e = _entity;
        if (e == null || !e.isAlive()) {
            _weDoneHere = true; // 矿车没了, 结束
            return null;
        }
        double reach = mod.getModSettings().getEntityReachRange();
        if (e.squaredDistanceTo(mod.getPlayer()) > reach * reach) {
            setDebugState("Approaching minecart chest");
            return new adris.altoclef.tasks.movement.GetToEntityTask(e, 2);
        }
        // 在交互距离内: 面朝实体 + 右键打开。
        adris.altoclef.util.helpers.LookHelper.lookAt(mod, e.getPos());
        net.minecraft.client.MinecraftClient.getInstance().interactionManager
                .interactEntity(mod.getPlayer(), e, Hand.MAIN_HAND);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task task) {
        StorageHelper.closeScreen();
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LootContainerTask && targets == ((LootContainerTask) other).targets;
    }

    private Optional<Slot> getAMatchingSlot(AltoClef mod) {
        for (Item item : targets) {
            List<Slot> slots = mod.getItemStorage().getSlotsWithItemContainer(item);
            if (!slots.isEmpty()) for (Slot slot : slots) {
                if (_check.test(StorageHelper.getItemStackInSlot(slot))) return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _weDoneHere || (ContainerType.screenHandlerMatchesAny() &&
                getAMatchingSlot(mod).isEmpty());
    }

    @Override
    protected String toDebugString() {
        return "Looting a container";
    }
}
