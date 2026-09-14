package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.StoreGarbageInContainerTask;
import adris.altoclef.tasksystem.CursorArbiter;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;

import java.util.Optional;

public class EnsureFreeInventorySlotTask extends Task {

    // (2026-08-27 合并诊断日志) 每 2 秒一条写独立文件, 便于检索合并为何不触发。
    private static long _lastConsolidateDiag = 0;

    // (2026-09-14 穿甲两拍搬运粘滞) 持有本任务派发的穿甲搬运子任务与目标物品:
    // 两拍中转的第一拍 PICKUP 把靴子拿上光标后, 下一 tick 重入时 cursorBusy=true
    // 会跳过穿甲分支落进丢弃级联, 自己把中间态拆了 (20260914-221044 实锤:
    // fix_diag cursor air<->iron_boots 每 2s 交替, TaskPingPong 20x/5s, 穿甲
    // 永远走不到第二步)。搬运未完成且光标上正是目标物(或光标空=第一拍未发)
    // 时保持返回同一实例, 让两拍走完。
    private MoveItemToSlotFromInventoryTask _armorMoveTask = null;
    private Item _armorMoveItem = null;

    // (2026-09-07 容器腾位) 站在箱子前时优先把垃圾存进容器。跨 tick 持有该子任务
    // 实例以便查询它是否已判定"容器存不下"; 判定失败后本轮不再重试, 直接走丢弃
    // 级联 (否则每 tick 重建一个已结束的任务 = 空转)。
    private StoreGarbageInContainerTask _storeTask = null;
    private boolean _containerStoreBlocked = false;

    @Override
    protected void onStart(AltoClef mod) {
        _storeTask = null;
        _containerStoreBlocked = false;
        _armorMoveTask = null;
        _armorMoveItem = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-09-13 CursorArbiter) 本任务是"腾位"意图的 owner：整条腾位链
        // （StoreGarbage → Consolidate → 穿甲 → 丢弃级联）期间持光标租约，让上层
        // 合成链知道"光标的非空是合法的两拍中转"，不要翻面派 EnsureFreeCursorSlotTask
        // 把子树抢断（现场与机制见 CursorArbiter 类注释）。每 tick 调用即续租。
        CursorArbiter.begin(CursorArbiter.INTENT_RELOCATE);
        // (2026-08-31 空转修复) 背包有真实空位且光标为空时无需腾位 — 直接返回,
        // 不进"合并/拟抛丢"级联, 消除每 tick 刷屏空转 (日志 13:00 实测:
        // emptySlot=2 cursorEmpty=true 每秒 10+ 条 [DIAG-DROP])。
        if (mod.getPlayer() != null && mod.getPlayer().getInventory() != null) {
            if (mod.getPlayer().getInventory().getEmptySlot() >= 0
                    && StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                Debug.logToFile("altoclef_diag.log",
                        "[SYNC-FIX]\t[EnsureFree]\temptySlot>=0 & cursorEmpty -> no-op (skip discard cascade)");
                setDebugState("Inventory already has free space");
                CursorArbiter.release(CursorArbiter.INTENT_RELOCATE);
                return null;
            }
        }
        // (2026-09-14 穿甲两拍搬运粘滞) 见字段注释。必须在 cursorBusy 判定之前:
        // 中间态(物品已上光标)恰好会让 cursorBusy=true 而跳过穿甲分支。
        if (_armorMoveTask != null) {
            if (_armorMoveTask.isFinished(mod)) {
                _armorMoveTask = null;
                _armorMoveItem = null;
            } else {
                ItemStack cursorHeld = StorageHelper.getItemStackInCursorSlot();
                if (cursorHeld.isEmpty() || cursorHeld.getItem() == _armorMoveItem) {
                    setDebugState("Armor move in flight, holding move task...");
                    return _armorMoveTask;
                }
                // 光标被别的意图占用(非本搬运物): 放弃粘滞, 走常规级联。
                _armorMoveTask = null;
                _armorMoveItem = null;
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
                // (2026-09-14 穿甲两拍搬运粘滞) 同一搬运复用实例, 供上方粘滞块认领。
                if (_armorMoveTask == null || _armorMoveItem != armorStack.getItem()) {
                    _armorMoveItem = armorStack.getItem();
                    _armorMoveTask = new MoveItemToSlotFromInventoryTask(
                            new ItemTarget(armorStack.getItem(), 1),
                            PlayerSlot.getEquipSlot(armor.getSlotType()));
                }
                return _armorMoveTask;
            }
        }
        // 我们为腾位主动拿起的 bulk 建材（可能在 throwaway 列表之外，
        // 如花岗岩）：直接扔，别卡在光标里。
        // (2026-09-09 保留闸门补洞) 圆石家族(圆石/深板岩圆石/黑石)在保留量
        // (一组)内不从光标抛丢 — 与 getBulkDisposalSlot / getGarbageSlot 口径
        // 一致。此路径此前无保留检查, 圆石进光标即被整堆扔掉。
        if (!cursorStack.isEmpty() && StorageHelper.isBulkDisposalItem(cursorStack.getItem())
                && !ItemHelper.isReservedBlockUnderLimit(mod, cursorStack)
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
                // 腾位彻底无路可走（无可丢/可存/可穿）→ 交还光标租约，不再占着。
                CursorArbiter.release(CursorArbiter.INTENT_RELOCATE);
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

    // (2026-09-12 语义修复) onTick 的 no-op 分支（有真实空位+光标空 → 直接返回）
    // 之前没同步到 isFinished, 基类默认恒 false → 任何以 isFinished 判断本任务的
    // 调用方都会把"无事可做"误读成"未完成"并永久等待（16:10 收产物互锁的
    // 次生风险）。与 no-op 分支保持同一判定口径。
    @Override
    public boolean isFinished(AltoClef mod) {
        if (mod.getPlayer() != null && mod.getPlayer().getInventory() != null) {
            return mod.getPlayer().getInventory().getEmptySlot() >= 0
                    && StorageHelper.getItemStackInCursorSlot().isEmpty();
        }
        return false;
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
