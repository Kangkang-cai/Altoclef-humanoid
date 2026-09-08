package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.StoreGarbageInContainerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;

import java.util.Optional;

public class EnsureFreeInventorySlotTask extends Task {

    // (2026-08-27 合并诊断日志) 每 2 秒一条写独立文件, 便于检索合并为何不触发。
    private static long _lastConsolidateDiag = 0;

    // (2026-09-07 容器腾位) 站在箱子前时优先把垃圾存进容器。跨 tick 持有该子任务
    // 实例以便查询它是否已判定"容器存不下"; 判定失败后本轮不再重试, 直接走丢弃
    // 级联 (否则每 tick 重建一个已结束的任务 = 空转)。
    private StoreGarbageInContainerTask _storeTask = null;
    private boolean _containerStoreBlocked = false;

    @Override
    protected void onStart(AltoClef mod) {
        _storeTask = null;
        _containerStoreBlocked = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-08-31 空转修复) 背包有真实空位且光标为空时无需腾位 — 直接返回,
        // 不进"合并/拟抛丢"级联, 消除每 tick 刷屏空转 (日志 13:00 实测:
        // emptySlot=2 cursorEmpty=true 每秒 10+ 条 [DIAG-DROP])。
        if (mod.getPlayer() != null && mod.getPlayer().getInventory() != null) {
            if (mod.getPlayer().getInventory().getEmptySlot() >= 0
                    && StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                Debug.logToFile("altoclef_diag.log",
                        "[SYNC-FIX]\t[EnsureFree]\temptySlot>=0 & cursorEmpty -> no-op (skip discard cascade)");
                setDebugState("Inventory already has free space");
                return null;
            }
        }
        // (2026-08-27 诊断) 满包腾位入口: 记录鼠标/可合并对/空位/垃圾判定, 写到
        // altoclef_fix_diag.log, 好定位"没叠"是找不到可合并对, 还是根本没走合并。
        long now = System.currentTimeMillis();
        if (now - _lastConsolidateDiag > 2000) {
            _lastConsolidateDiag = now;
            Slot[] pair = StorageHelper.findMergeableStackPair(mod);
            String cursor = StorageHelper.getItemStackInCursorSlot().getItem().getTranslationKey();
            int empty = 0;
            if (mod.getPlayer() != null && mod.getPlayer().getInventory() != null) {
                for (ItemStack s : mod.getPlayer().getInventory().main) {
                    if (s.isEmpty()) empty++;
                }
            }
            String garbage = StorageHelper.getGarbageSlot(mod).map(s -> s.toString()).orElse("none");
            Debug.logToFile("altoclef_fix_diag.log",
                    "[ConsolidateDiag] EnsureFreeInventorySlot | findMergeablePair=" + (pair != null)
                            + " cursor=" + cursor + " emptySlots=" + empty
                            + " garbageSlot=" + garbage);
        }
        // (2026-09-07 卡死根因修复, 现场: 光标拿着面包 + 背包 36 格全满)
        // 光标非空时【绝不能】走合并: 此时点任何槽都是"放置/交换光标物品", 合并
        // 根本无从执行; 而 ConsolidateInventoryTask 又要求"光标为空"才算完成 →
        // 两者互锁 = 永久空转, 真正的腾位/丢弃链路一次都执行不到 (23:15:37-53
        // 日志: [ConsolidateDiag] cursor=bread emptySlots=0 findMergeablePair=true
        // 持续 8 次采样, 直到用户手动停止)。光标非空一律直接走下面的腾位/丢弃级联。
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        boolean cursorBusy = !cursorStack.isEmpty();

        if (!cursorBusy) {
            // (2026-09-07 用户拍板) 站在箱子前腾位: 先把垃圾【存进这个箱子】,
            // 能不扔地上就不扔 — 都开箱了还把东西丢一地不合理。存不下再由
            // StoreGarbageInContainerTask 自己结束, 落到下面的丢弃级联。
            if (!_containerStoreBlocked && StoreGarbageInContainerTask.isStorageContainerOpen()) {
                if (_storeTask == null) {
                    _storeTask = new StoreGarbageInContainerTask();
                }
                if (_storeTask.isFinished(mod)) {
                    _containerStoreBlocked = true;
                } else {
                    setDebugState("Making room by storing junk in container");
                    return _storeTask;
                }
            }
            // (2026-08-27 用户要求: 可堆叠物品合并放一起省空间, 放在丢垃圾之前)
            // 背包满需要腾位时, 先尝试把同类部分堆合并 (省格子, 不丢任何东西);
            // 合并完仍满才走丢垃圾/随机丢逻辑。有可合并的对才启动合并任务,
            // 否则空跑会立刻结束导致死循环。
            if (StorageHelper.findMergeableStackPair(mod) != null) {
                setDebugState("Consolidating stacks before freeing space");
                return new ConsolidateInventoryTask();
            }
            // (2026-09-08 腾位穿甲, 用户"背包满有盔甲他穿上也可以") 光标为空时,
            // 背包里有比当前护甲槽更好(或空槽)的盔甲 → 先穿甲: 空出一个背包格
            // 且不掉任何物资。放在丢弃级联之前, 合并之后 (同样无损)。
            // 若当前开着别的界面, 先关界面等下一 tick (穿甲要用 PlayerScreenHandler
            // 槽位映射, 见 EquipArmorTask 同款守卫)。
            Slot armorSlot = StorageHelper.findEquippableArmorUpgrade(mod);
            if (armorSlot != null) {
                ItemStack armorStack = StorageHelper.getItemStackInSlot(armorSlot);
                if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) {
                    StorageHelper.closeScreen();
                    setDebugState("Closing screen to equip armor");
                    return null;
                }
                ArmorItem armor = (ArmorItem) armorStack.getItem();
                setDebugState("Equipping " + armorStack.getItem().getTranslationKey()
                        + " from backpack to free a slot");
                Debug.logMessage("(EnsureFreeInventorySlotTask) Equipping backpack armor "
                        + armorStack.getItem().getTranslationKey() + " to free a slot instead of discarding.");
                return new MoveItemToSlotFromInventoryTask(
                        new ItemTarget(armorStack.getItem(), 1),
                        PlayerSlot.getEquipSlot(armor.getSlotType()));
            }
        }
        // 我们为腾位主动拿起的 bulk 建材（可能在 throwaway 列表之外，
        // 如花岗岩）：直接扔，别卡在光标里。
        if (!cursorStack.isEmpty() && StorageHelper.isBulkDisposalItem(cursorStack.getItem())
                && !ItemHelper.isStackProtected(mod, cursorStack)) {
            setDebugState("Throwing bulk stack from cursor");
            return new ThrowCursorTask();
        }
        // (2026-08-31 丢东西诊断, 用户"背包空着却丢浆果/木棍")
        // 这三条丢弃路径本应只在"背包全满/无空位/无垃圾"时触发。
        // 记录当前空位: 若 emptySlot>=0(还有空位)却仍走到抛丢分支 → 即为"不该丢却丢"的 bug 证据。
        int _emptyInv = mod.getPlayer().getInventory().getEmptySlot();
        Debug.logToFile("altoclef_diag.log",
                "[DIAG-DROP] EnsureFree 拟抛丢: emptySlot=" + (_emptyInv >= 0 ? (_emptyInv + " (还有空位却抛丢=异常!)") : "FULL(满, 腾位合理)")
                        + " cursorEmpty=" + cursorStack.isEmpty());
        if (cursorStack.isEmpty() || !ItemHelper.canThrowAwayStack(mod, cursorStack)) {
            Optional<Slot> toThrow = StorageHelper.getGarbageSlot(mod);
            if (toThrow.isPresent()) {
                setDebugState("Finding garbage");
                return new ClickSlotTask(toThrow.get());
            } else {
                // 背包全满且无垃圾可扔（2026-08-21 床掉落物丢失 bug）：
                // 原逻辑在这里永久空转（"NO THROWAWAYABLE!"），捡拾任务
                // 无进展 → 掉落物被拉黑为 unreachable → 永久丢失（表现
                // 为"感知不到自己刚撸掉的床"）。人类做法：丢一把大堆廉价
                // 建材（圆石/泥土等 >= 16 个）腾出一个格子。
                Slot bulk = StorageHelper.getBulkDisposalSlot(mod);
                if (bulk != null) {
                    ItemStack bulkStack = StorageHelper.getItemStackInSlot(bulk);
                    setDebugState("No garbage: tossing bulk stack to make room");
                    Debug.logMessage("(EnsureFreeInventorySlotTask) Inventory full with no garbage — tossing "
                            + bulkStack.getCount() + "x " + bulkStack.getItem().getTranslationKey()
                            + " to make room for pickup.");
                    return new ClickSlotTask(bulk);
                }
                // (2026-08-23 保底随机丢) 背包全满且无垃圾/无大堆建材可扔时,
                // 用户要求"加个保底, 随机丢东西出去" — 否则这里永久空转
                // ("NO THROWAWAYABLE!" 反复, 14:33-14:59 日志实锤)。随机挑一个
                // 非护甲/非副手/非空的槽位扔。优先扔未被保护的, 全被保护时
                // 也从这些槽位里随机扔 (保底)。排除关键任务物品 (桶/床/末影之眼)。
                Slot randomThrow = StorageHelper.getRandomThrowableSlot(mod);
                if (randomThrow != null) {
                    ItemStack rs = StorageHelper.getItemStackInSlot(randomThrow);
                    setDebugState("No garbage: random fallback toss to make room");
                    Debug.logMessage("(EnsureFreeInventorySlotTask) No garbage/bulk — random fallback tossing "
                            + rs.getCount() + "x " + rs.getItem().getTranslationKey()
                            + " to make room for pickup.");
                    return new ClickSlotTask(randomThrow);
                }
                setDebugState("NO THROWAWAYABLE!");
                return null;
            }
        } else {
            setDebugState("Throwing cursor");
            return new ThrowCursorTask();
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof EnsureFreeInventorySlotTask;
    }

    @Override
    protected String toDebugString() {
        return "Ensuring inventory is free";
    }
}
