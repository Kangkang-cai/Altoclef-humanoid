package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.CursorArbiter;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.tag.ItemTags;
import net.minecraft.tag.TagKey;

import java.util.Optional;

public class ReceiveCraftingOutputSlotTask extends Task implements ITaskUsesCraftingGrid {

    private final int _toTake;
    private final boolean _all;
    private final Slot _slot;

    // (2026-08-27 输出拾取防抖) 点过输出格后, 若输出格状态没变(数量相同)且光标
    // 仍空, 说明客户端同步还没到位 — 别每 tick 再点(旧代码反复
    // "Receiving output→Picking up output→Clicking 输出格" 永不收, 用户实测做
    // 石镐时卡死横跳)。宽限期内等同步。
    private long _lastOutputClickMs = 0;
    private int _lastClickedOutputCount = -1;
    // (2026-09-18 合并拾取) 防抖签名加入光标数量: 合并拾取时输出格恒为 1 份,
    // 只有光标数量在变, 光标不变才是"点击没生效"。
    private int _lastClickedCursorCount = -1;
    private static final long OUTPUT_CLICK_GRACE_MS = 500;

    // (2026-09-13 CursorArbiter 根修) 跨 tick 持住腾位子树实例。见下方光标守卫:
    // 光标被腾位链租用时不能翻面抢, 复用同一实例 = 树不换, 让搬堆叠跑完整拍。
    private Task _freeTask = null;

    public ReceiveCraftingOutputSlotTask(Slot slot, int toTake) {
        _slot = slot;
        _toTake = toTake;
        _all = toTake == Integer.MAX_VALUE;
    }
    public ReceiveCraftingOutputSlotTask(Slot slot, boolean all) {
        this(slot, all? Integer.MAX_VALUE : 1);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _freeTask = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        ItemStack inOutput = StorageHelper.getItemStackInSlot(_slot);
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();

        boolean cursorSlotFree = cursor.isEmpty();

        if (inOutput.isEmpty()) {
            // (2026-08-31 空转修复...) 输出槽空时不该再点 QUICK_MOVE(空操作), 交还上层。
            setDebugState("Output slot empty");
            return null;
        }
        if (!cursorSlotFree && !ItemHelper.canStackTogether(inOutput, cursor)) {
            // (2026-09-13 CursorArbiter 根修) 光标被腾位链租用 = 上面这件东西正是下级
            // ConsolidateInventoryTask 搬的"拿起→并入"两拍中转态, 不是外来的滞留残留。
            // 此时派 EnsureFreeCursorSlotTask 会把整条腾位链 STOP (gridwar.log 实锤:
            // consolidate 45.030 拿起 → ensurefree 45.531 换走, 每 2s 一轮)。维持子树。
            if (CursorArbiter.isHeldBy(CursorArbiter.INTENT_RELOCATE)) {
                if (_freeTask == null) {
                    _freeTask = new EnsureFreeInventorySlotTask();
                }
                setDebugState("Cursor leased by free-space chain, holding");
                return _freeTask;
            }
            return new EnsureFreeCursorSlotTask();
        }

        // [2026-08-22 铁剑卡死修复] 光标已拿着可堆叠的输出物品时...
        // (2026-09-18 收货提速: 光标合并拾取) 背景: 09-13 起配方填充已是批量
        // (craftAll=true), 但下方 takeAll 判定 (weWant ≥ 格内可做总量) 在"格内容量
        // 超过还差数量"时恒假 — 金锭这类 9 格配方一填就是几十批, 而目标往往只有
        // 十几个, 于是退化成"拿起 1 个 → 放回背包 → 再拿 1 个", 每件成品 2 次槽位
        // 操作 (0.8s/件, 20260918-134502 craftclick.log 13:50:31-38 八连 PICKUP 实锤)。
        // 本分支: 光标已持同种成品且叠上去不会超堆叠上限时, 继续点输出格把成品
        // 【叠加到光标】(vanilla 原生行为), 每件只花 1 次操作, 最后一次性放回背包。
        // 上限用真实背包计数 (getItemCountReal, 含光标) 而非 500ms 缓存 —— 快速
        // 连点下缓存必然滞后, 按缓存会超出目标数量多耗材料 (老"铁剑超量合成"
        // 事故的新形态, 严禁)。防抖语义保留: 点击后输出格与光标双双纹丝不动
        // 才算"同步未到", 光标数量变了就是真有进展。
        if (!cursorSlotFree) {
            if (ItemHelper.canStackTogether(inOutput, cursor)
                    && cursor.getCount() + inOutput.getCount() <= cursor.getMaxCount()) {
                int realHave = mod.getItemStorage().getItemCountReal(inOutput.getItem());
                if (_toTake - realHave >= inOutput.getCount()) {
                    long nowMerge = System.currentTimeMillis();
                    if (nowMerge - _lastOutputClickMs < OUTPUT_CLICK_GRACE_MS
                            && inOutput.getCount() == _lastClickedOutputCount
                            && cursor.getCount() == _lastClickedCursorCount) {
                        return null;
                    }
                    _lastOutputClickMs = nowMerge;
                    _lastClickedOutputCount = inOutput.getCount();
                    _lastClickedCursorCount = cursor.getCount();
                    setDebugState("Picking up output (merge onto cursor)");
                    return new ClickSlotTask(_slot);
                }
            }
            _lastOutputClickMs = 0;
            _lastClickedOutputCount = -1;
            _lastClickedCursorCount = -1;
            Optional<Slot> fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (fit.isPresent()) {
                setDebugState("Placing held output into inventory");
                return new ClickSlotTask(fit.get());
            }
            // 背包放不下光标物品: 丢弃腾位兜底 (EnsureFreeInventorySlotTask 内部
            // 会清理可丢弃槽位; 全保护时返回自身不动, 上层重置计时器兜底)。
            setDebugState("No room for held output, freeing inventory");
            if (_freeTask == null) {
                _freeTask = new EnsureFreeInventorySlotTask();
            }
            return _freeTask;
        }

        // (2026-09-18 防超量兜底) 光标空、缓存计数滞后 (刚整批放入一批成品, 缓存
        // 层最长 500ms 才刷新) 时, 旧逻辑会按滞后的缓存计数再点一次输出格 →
        // 每轮填充最多白多合成一批。真实主背包计数已达目标就不再点 (输出点击
        // 每次都真实消耗一批材料, 超目标即浪费 — 老"铁剑超量合成"事故口径)。
        // 注意只扫主背包, 不含副手/护甲: 盾牌/剑这类常驻装备槽的物品不应阻塞
        // 正常收货; 输出格整份产出 (如木板 x4/次) 大于还差数量时仍照拿 — 拿取
        // 以整份为原子, 与旧行为一致, 这里只拦"已有数已达标"的情况。
        if (!_all) {
            int mainInvHave = countInMainInventory(mod, inOutput.getItem());
            if (_toTake - mainInvHave <= 0) {
                setDebugState("Real inventory already at target, not picking more");
                return null;
            }
        }

        int craftCount = inOutput.getCount() * getCraftMultipleCount(mod);
        int weWantToAddToInventory = _toTake - mod.getItemStorage().getItemCountInventoryOnly(inOutput.getItem());
        // (2026-09-18 有界超量一键全收) 上面 takeAll 严格要求"格内可做总量 ≤ 还差
        // 数量"——批量填充后格内容量 K 通常远超 W（原料富余），于是永远走不到
        // QUICK_MOVE，退化成逐个拾取（20260918-154845 实锤：4 锭 4 次 PICKUP，
        // 用户复现"还是一个一个拿"）。对【金粒→金锭/铁粒→铁锭/小麦→面包】这类
        // 纯转化配方（原料的唯一去向就是成品，多做的成品仍是等值资源：金锭直接
        // 喂猪灵交易、面包是食物储备），超量在 Settings.maxBulkCraftOvershoot
        // 批内时放开一键全收。剑/工具/盔甲等"原料另有他用"的配方不在白名单，
        // 维持严格不超量（老铁剑超量事故口径）。超量上限 0 = 完全恢复旧行为。
        boolean takeAll = weWantToAddToInventory >= craftCount
                || (mod.getModSettings().getMaxBulkCraftOvershoot() > 0
                && _toTake != Integer.MAX_VALUE
                && weWantToAddToInventory > 0
                && isPureConversionWhitelisted(inOutput.getItem(), mod)
                // 上限按【批】计: (craftCount - weWant) 是成品件数, 除以每批产出
                // 得超量批数 (木板 4 件/批、金锭 1 件/批, 同一上限对两类配方
                // 语义一致——都是"多做几批"而不是"多拿几件")。
                && craftCount - weWantToAddToInventory
                        <= (long) mod.getModSettings().getMaxBulkCraftOvershoot() * inOutput.getCount());
        // (2026-08-27 输出拾取防抖) ...
        long now = System.currentTimeMillis();
        if (takeAll) {
            // [2026-08-22] 背包放不下输出时不能落到 PICKUP (拿起后没有后续
            // 放置步骤, 见上)。
            if (!mod.getItemStorage().getSlotThatCanFitInPlayerInventory(inOutput, true).isPresent()) {
            // (2026-09-03 回退) 曾用"交换进合成格"腾位(借垃圾槽→QUICK_MOVE产物
            // →垃圾暂存合成格)。实测 3x3 工作台合成钻石头盔时该状态机制造
            // 借位↔放回死循环(12s 内 borrow/move/park 各 ~70 次 ping-pong,
            // 且被 Throwing Cursor 打断真丢物品)。根因: 交换借出的位马上又被
            // 产物占据, 净空格没增加, getSlotThatCanFit 恒 false → 反复重启交换。
            // 回退为直接走 EnsureFreeInventorySlotTask 腾位。
            // (2026-09-12 收产物互锁实修) fit 判定与 MC 权威背包不一致时以现实为准:
            // craftgrid_diag 16:11:49 实锤 — 2x2 界面下产物 crafting_table x1 已在
            // 输出槽、合成格 4 木板就位, MC 权威背包尚有 hotbar 空位(window 36),
            // 但 getSlotThatCanFit 恒 false → "Making room for output"→EnsureFree
            // no-op 分支互锁, 30s+ 空转直到被上层抢占(16:09:49/16:10:19 两次)。
            // 光标为空且背包真有空位时, 直接 QUICK_MOVE shift 进空位, 一步到位。
            boolean realEmptyMain = mod.getPlayer() != null
                    && mod.getPlayer().getInventory() != null
                    && mod.getPlayer().getInventory().getEmptySlot() >= 0;
            if (realEmptyMain && cursorSlotFree) {
                Debug.logToFile("altoclef_diag.log",
                        "[CraftFitMismatch] fit=false but realEmptyMain=true -> QUICK_MOVE output anyway"
                                + " | output=" + inOutput.getItem().getTranslationKey() + "x" + inOutput.getCount()
                                + " handler=" + (mod.getPlayer().currentScreenHandler != null
                                        ? mod.getPlayer().currentScreenHandler.getClass().getSimpleName() : "null"));
                setDebugState("Quick moving output (fit mismatch fallback)");
                return new ClickSlotTask(_slot, SlotActionType.QUICK_MOVE);
            }
            setDebugState("Making room for output");
            if (_freeTask == null) {
                _freeTask = new EnsureFreeInventorySlotTask();
            }
            return _freeTask;
            }
            if (now - _lastOutputClickMs < OUTPUT_CLICK_GRACE_MS
                    && inOutput.getCount() == _lastClickedOutputCount) {
                return null;
            }
            _lastOutputClickMs = now;
            _lastClickedOutputCount = inOutput.getCount();
            setDebugState("Quick moving output");
            return new ClickSlotTask(_slot, SlotActionType.QUICK_MOVE);
        }
        if (now - _lastOutputClickMs < OUTPUT_CLICK_GRACE_MS
                && inOutput.getCount() == _lastClickedOutputCount) {
            return null;
        }
        _lastOutputClickMs = now;
        _lastClickedOutputCount = inOutput.getCount();
        setDebugState("Picking up output");
        return new ClickSlotTask(_slot);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ReceiveCraftingOutputSlotTask task) {
            return task._slot.equals(_slot) && task._toTake == _toTake;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Receiving output";
    }

    /**
     * (2026-09-18) 入口：合成格所有非空格是【同一种原料】才放行（两种以上原料
     * 如金胡萝卜/工具一律 false），再查 {@link #isWhitelistedConversion}。
     */
    private static boolean isPureConversionWhitelisted(Item outputItem, AltoClef mod) {
        Item gridItem = null;
        for (Slot check : (StorageHelper.isBigCraftingOpen() ? CraftingTableSlot.INPUT_SLOTS : PlayerSlot.CRAFT_INPUT_SLOTS)) {
            ItemStack stack = StorageHelper.getItemStackInSlot(check);
            if (stack.isEmpty()) {
                continue;
            }
            Item inGrid = stack.getItem();
            if (gridItem == null) {
                gridItem = inGrid;
            } else if (gridItem != inGrid) {
                return false;
            }
        }
        return gridItem != null && isWhitelistedConversion(outputItem, gridItem);
    }

    /**
     * (2026-09-18) "纯转化配方"白名单：输出是原料的等值下游产物 —— 多做的成品
     * 仍是等值资源、会被 bot 自己的管线消费掉：
     *   金粒→金锭 / 铁粒→铁锭（锭直接喂猪灵交易/工具）
     *   小麦→面包（食物储备）
     *   干草块→小麦（食物链上游，9 小麦/块，可逆）
     *   原木→木板（万能中间品：木棍/工作台/船[要塞船降]/燃料）
     *   木板→木棍（万能中间品：火把/工具）
     * 剑/工具/盔甲等"原料另有他用"的配方不在白名单（老铁剑超量事故口径）。
     */
    private static boolean isWhitelistedConversion(Item outputItem, Item gridItem) {
        if (gridItem == Items.GOLD_NUGGET) {
            return outputItem == Items.GOLD_INGOT;
        }
        if (gridItem == Items.IRON_NUGGET) {
            return outputItem == Items.IRON_INGOT;
        }
        if (gridItem == Items.WHEAT) {
            return outputItem == Items.BREAD;
        }
        if (gridItem == Items.HAY_BLOCK) {
            return outputItem == Items.WHEAT;
        }
        if (isInItemTag(gridItem, ItemTags.LOGS)) {
            return isInItemTag(outputItem, ItemTags.PLANKS);
        }
        if (isInItemTag(gridItem, ItemTags.PLANKS)) {
            return outputItem == Items.STICK;
        }
        return false;
    }

    /** (2026-09-18) 1.18.2 的 TagKey 归属判定走 ItemStack.isIn。 */
    private static boolean isInItemTag(Item item, TagKey<Item> tag) {
        return new ItemStack(item).isIn(tag);
    }

    // How many multiples of the current crafting recipe can we craft?
    private static int getCraftMultipleCount(AltoClef mod) {
        int minNonZero = Integer.MAX_VALUE;
        boolean found = false;
        for (Slot check : (StorageHelper.isBigCraftingOpen()? CraftingTableSlot.INPUT_SLOTS :PlayerSlot.CRAFT_INPUT_SLOTS)) {
            ItemStack stack = StorageHelper.getItemStackInSlot(check);
            if (!stack.isEmpty()) {
                minNonZero = Math.min(stack.getCount(), minNonZero);
                found = true;
            }
        }
        if (!found)
            return 0;
        return minNonZero;
    }

    /**
     * (2026-09-18 防超量兜底用) 只数玩家主背包 (main) 里的某物品数量, 不含
     * 副手/护甲/光标/容器 — 副手装备 (盾牌/剑) 不应阻塞收货判定。直扫真实
     * 背包, 不走 500ms 缓存层, 快速连点收货时以它为准。
     */
    private static int countInMainInventory(AltoClef mod, Item item) {
        if (mod.getPlayer() == null || mod.getPlayer().getInventory() == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
