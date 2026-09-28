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
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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

    // ==================== (2026-09-28 深水宝箱忽略 + 打不开超时放弃) ====================
    // 用户口径 (2026-09-28):
    //   ① 贪婪开箱要忽略"浸在很深的水里"的宝箱 —— bot 不会/不该游泳下潜, 走过去只会
    //      泡在水里打不开。判定: 容器上方连续水体 ≥ DEEP_WATER_MIN_DEPTH(3) 格, 即容器
    //      位于水平面以下 ≥3 格。
    //   ② 到过箱子旁 OPEN_NEAR_TIMEOUT_MS(10s) 仍打不开 → 放弃; 另挂
    //      TASK_TOTAL_TIMEOUT_MS(60s) 总活跃兜底, 覆盖"根本走不到"(baritone 无路径)
    //      导致任务无限占用主线的情形。
    // 两条都实现【在本任务内】→ 所有贪婪开箱入口 (就近搜刮 / 废墟传送门 / 沙漠神殿)
    // 自动统一生效, 调用方零改动: 置 _weDoneHere 后 isFinished 成立, 调用方 shouldForce
    // 自然放手; 拉黑由调用方既有名单 (BeatMinecraft2Task._sweptContainers 派发即拉黑 /
    // _notRuinedPortalChests) 承担。
    private static final int DEEP_WATER_MIN_DEPTH = 3;
    private static final long OPEN_NEAR_TIMEOUT_MS = 10_000;
    private static final long TASK_TOTAL_TIMEOUT_MS = 60_000;
    private static final double NEAR_CONTAINER_RANGE = 5.0;
    /** 两 tick 间隔超过此值视为"被抢占后重入", 该间隔不并入活跃时长 (防把中断时长算成卡住时长)。 */
    private static final long ACTIVE_TICK_GAP_CAP_MS = 250;

    private long _activeMs = 0;
    private long _nearMs = 0;
    private long _lastTickMs = 0;
    private long _lastNearMs = 0;
    private boolean _timedOut = false;
    private boolean _refused = false;

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
        // (2026-09-28) 两道闸都置 _weDoneHere (isFinished 成立) 让调用方放手:
        // ① 深水容器 → 直接跳过; ② 卡住 (打不开 / 走不到) → 超时放弃。
        if (rejectDeepWaterContainer(mod)) return null;
        if (checkLootTimeout(mod)) return null;
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

    /** 容器目标方块位 (矿车箱子取实体所在格)。 */
    private BlockPos targetPos() {
        return _entity != null ? _entity.getBlockPos() : chest;
    }

    /**
     * (2026-09-28 深水宝箱忽略) 容器浸在 ≥{@link #DEEP_WATER_MIN_DEPTH} 格连续水体里
     * → 判为"深水里的宝箱", 直接放弃 (bot 不下潜)。
     * 放在 onTick 而非 onStart: 首次进入时目标区块可能尚未加载, 水柱判不出来。
     * @return true = 已拒绝 (调用方不应再执行任何开箱动作)
     */
    private boolean rejectDeepWaterContainer(AltoClef mod) {
        if (_refused || _weDoneHere) return true;
        // 无实体且坐标为 ORIGIN 属异常构造, 不做水体判定以免误杀 (0,0,0 附近的水)。
        if (_entity == null && chest.equals(BlockPos.ORIGIN)) return false;
        BlockPos pos = targetPos();
        if (!isSubmergedInDeepWater(mod, pos)) return false;
        _refused = true;
        _weDoneHere = true;
        Debug.logMessage("(LootContainer) skip container submerged in deep water (>="
                + DEEP_WATER_MIN_DEPTH + " blocks) at " + pos.toShortString());
        Debug.logToFile("altoclef_diag.log", "[LOOT-DEEPWATER]\tskip\t" + pos.toShortString()
                + "\tdepth>=" + DEEP_WATER_MIN_DEPTH + "\tminecart=" + (_entity != null));
        return true;
    }

    /**
     * 容器上方连续水体格数是否达到"深水"阈值 (只数净水, 岩浆/空气/实心都终止计数)。
     * 未加载区块 → 视为不足, 宁可尝试也不误杀。
     */
    private static boolean isSubmergedInDeepWater(AltoClef mod, BlockPos pos) {
        if (mod.getWorld() == null || pos == null) return false;
        int depth = 0;
        for (BlockPos p = pos.up(); depth < DEEP_WATER_MIN_DEPTH; p = p.up()) {
            if (!mod.getChunkTracker().isChunkLoaded(p)) break;
            FluidState fs = mod.getWorld().getBlockState(p).getFluidState();
            if (fs.isEmpty()) break;
            Fluid f = fs.getFluid();
            if (f != Fluids.WATER && f != Fluids.FLOWING_WATER) break;
            depth++;
        }
        return depth >= DEEP_WATER_MIN_DEPTH;
    }

    /**
     * (2026-09-28 打不开超时) 累计【任务活跃时长】与【已在容器旁但屏幕仍未打开】的时长:
     * 到达后 10s 打不开 → 放弃; 总活跃 60s (含走不到的情形) → 也放弃。
     * 两 tick 间隔 > {@link #ACTIVE_TICK_GAP_CAP_MS} 视为被抢占, 不计入 (避免把
     * 高优先级任务占用的时间算成"卡住")。
     * @return true = 已超时并结束本任务
     */
    private boolean checkLootTimeout(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (_lastTickMs != 0) {
            long dt = now - _lastTickMs;
            if (dt > 0 && dt <= ACTIVE_TICK_GAP_CAP_MS) _activeMs += dt;
        }
        _lastTickMs = now;
        if (isNearContainer(mod)) {
            // 已进交互范围 → 开始计"打不开"的时长 (首次置锚, 不一次性设满)。
            if (_lastNearMs != 0) {
                long dt = now - _lastNearMs;
                if (dt > 0 && dt <= ACTIVE_TICK_GAP_CAP_MS) _nearMs += dt;
            }
            _lastNearMs = now;
        } else {
            _lastNearMs = 0;
        }
        boolean nearTimeout = _nearMs >= OPEN_NEAR_TIMEOUT_MS;
        boolean totalTimeout = _activeMs >= TASK_TOTAL_TIMEOUT_MS;
        if (!nearTimeout && !totalTimeout) return false;
        _timedOut = true;
        _weDoneHere = true;
        String why = nearTimeout
                ? ("stuck at container " + (OPEN_NEAR_TIMEOUT_MS / 1000) + "s without opening")
                : ("total " + (TASK_TOTAL_TIMEOUT_MS / 1000) + "s without reaching/opening");
        Debug.logMessage("(LootContainer) give up looting " + targetPos().toShortString() + " — " + why);
        Debug.logToFile("altoclef_diag.log", "[LOOT-TIMEOUT]\tgiveUp\t" + targetPos().toShortString()
                + "\t" + why + "\tactiveMs=" + _activeMs + "\tnearMs=" + _nearMs);
        return true;
    }

    /** 玩家是否已在容器的交互邻近范围内 (含一点余量, 覆盖各类 reach 口径)。 */
    private boolean isNearContainer(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        Vec3d c = (_entity != null) ? _entity.getPos() : Vec3d.ofCenter(chest);
        return mod.getPlayer().getPos().squaredDistanceTo(c) <= NEAR_CONTAINER_RANGE * NEAR_CONTAINER_RANGE;
    }

    /**
     * (2026-09-28) 本任务是否因"深水跳过"或"超时放弃"而结束 —— 供调用方把该容器
     * 加入自己的黑名单 (否则"未开箱"的目标会被 locate 再次选中 → 每 tick 重建死循环)。
     */
    public boolean wasRefusedOrTimedOut() {
        return _refused || _timedOut;
    }

    /** 该任务对应的容器方块位 (废墟传送门等按 BlockPos 定位的调用方用于拉黑)。 */
    public BlockPos getChestPos() {
        return chest;
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
