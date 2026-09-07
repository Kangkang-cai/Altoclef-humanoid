package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.*;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;


public class SlotHandler {

    private final AltoClef _mod;

    private final TimerGame _slotActionTimer = new TimerGame(0);
    private boolean _overrideTimerOnce = false;
    // (2026-09-03 诊断) 点击合成格放东西诊断日志节流时间戳(ms).
    private long _lastCraftGridClickDiagMs = 0;
    // (2026-09-07 [CraftClick] 诊断) 槽位点击哑火案: 每次真实发出的点击记录
    // handler/syncId/revision/光标前后, 定案"客户端预测是否应用/是否被回滚"。
    private long _lastCraftClickDiagMs = 0;

    public SlotHandler(AltoClef mod) {
        _mod = mod;
    }

    private void forceAllowNextSlotAction() {
        _overrideTimerOnce = true;
    }

    public boolean canDoSlotAction() {
        if (_overrideTimerOnce) {
            _overrideTimerOnce = false;
            return true;
        }
        _slotActionTimer.setInterval(_mod.getModSettings().getContainerItemMoveDelay());
        return _slotActionTimer.elapsed();
    }
    public void registerSlotAction() {
        _mod.getItemStorage().registerSlotAction();
        _slotActionTimer.reset();
    }

    public void clickSlot(Slot slot, int mouseButton, SlotActionType type) {
        if (!canDoSlotAction()) return;

        if (slot.getWindowSlot() == -1) {
            Debug.logWarning("Tried to click the cursor slot. Shouldn't do this!");
            return;
        }

        // (2026-09-03 诊断: 抓"垃圾被塞进 2x2 合成格"根因, 用户复现 tuff x21 在
        // window1 + itemt diag 已确凿 inPlayerInv=true). 只要光标非空且目标是
        // 玩家自带 2x2 合成格/输出槽 (window≤4), 记录谁在点击往合成格放东西。
        // 节流 500ms; 含调用方类名, 帮助区分是配方放置(正常)还是丢垃圾/腾位误放(bug)。
        try {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p != null && p.currentScreenHandler instanceof net.minecraft.screen.PlayerScreenHandler
                    && slot.getWindowSlot() >= 0 && slot.getWindowSlot() <= 4
                    && !StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                long now = System.currentTimeMillis();
                if (now - _lastCraftGridClickDiagMs > 500) {
                    _lastCraftGridClickDiagMs = now;
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    StackTraceElement caller = null;
                    for (int i = 2; i < st.length; i++) {
                        if (!st[i].getClassName().equals(SlotHandler.class.getName())) {
                            caller = st[i];
                            break;
                        }
                    }
                    if (caller == null && st.length > 2) caller = st[2];
                    Debug.logToFile("altoclef_craftgrid_click.log",
                            "[CraftGridClick] put=" + StorageHelper.getItemStackInCursorSlot()
                                    + " -> window=" + slot.getWindowSlot()
                                    + " type=" + type + " btn=" + mouseButton
                                    + " caller=" + caller);
                }
            }
        } catch (Exception ignored) {
        }

        clickWindowSlot(slot.getWindowSlot(), mouseButton, type);
    }
    private void clickSlotForce(Slot slot, int mouseButton, SlotActionType type) {
        forceAllowNextSlotAction();
        clickSlot(slot, mouseButton, type);
    }

    /**
     * (2026-09-06 进食修复) 强制槽位点击 (跳过 containerItemMoveDelay 节流)。
     * 仅供"必须在本 tick 确定完成"的少数场景 (进食的盾/食物换位) 使用:
     * 节流丢弃是静默的, 多步槽位序列混用普通点击会出现中间态永久卡死
     * (实锤 21:20: 主手盾+副手面包, 换盾点击被节流丢弃后状态翻转)。
     */
    public void clickSlotForced(Slot slot, int mouseButton, SlotActionType type) {
        clickSlotForce(slot, mouseButton, type);
    }

    private void clickWindowSlot(int windowSlot, int mouseButton, SlotActionType type) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        registerSlotAction();
        int syncId = player.currentScreenHandler.syncId;

        // (2026-09-07 [CraftClick] 专项诊断, "台前合成熔炉卡死"案) 现象: 配方书填充
        // (CraftRequestC2SPacket, 无客户端预测无 revision) 生效, 而槽位点击
        // (ClickSlotC2SPacket, 带客户端预测带 revision) 对同一 handler 全部哑光,
        // 77 次输出格点击零产出且光标始终为空。本日志逐次记录点击环境, 一次复现
        // 即可三分支定案: ①predicted=false → 点击根本没执行到预测层; ②predicted=true
        // 且背包计数不变 → 客户端应用了但服务端未应用(revision/syncId 被静默丢弃);
        // ③predicted=true 且服务端也应用 → 光标应持续持有成品(与用户观察矛盾, 若出现
        // 则指向"光标物品被某个未记录的路径回滚")。300ms 节流防刷屏。
        net.minecraft.item.ItemStack cursorBefore = player.currentScreenHandler.getCursorStack().copy();
        int revisionBefore = player.currentScreenHandler.getRevision();
        try {
            _mod.getController().clickSlot(syncId, windowSlot, mouseButton, type, player);
        } catch (Exception e) {
            Debug.logWarning("Slot Click Error (ignored)");
            e.printStackTrace();
        }
        long now = System.currentTimeMillis();
        if (now - _lastCraftClickDiagMs > 300) {
            _lastCraftClickDiagMs = now;
            net.minecraft.item.ItemStack cursorAfter = player.currentScreenHandler.getCursorStack();
            boolean predicted = !net.minecraft.item.ItemStack.areEqual(cursorBefore, cursorAfter);
            Debug.logToFile("altoclef_craftclick.log",
                    String.format("[CraftClick] handler=%s syncId=%d slot=%d btn=%d type=%s rev=%d->%d | cursor %s -> %s | predicted=%s",
                            player.currentScreenHandler.getClass().getSimpleName(),
                            syncId, windowSlot, mouseButton, type,
                            revisionBefore, player.currentScreenHandler.getRevision(),
                            cursorBefore.isEmpty() ? "EMPTY" : cursorBefore.getItem() + "x" + cursorBefore.getCount(),
                            cursorAfter.isEmpty() ? "EMPTY" : cursorAfter.getItem() + "x" + cursorAfter.getCount(),
                            predicted));
        }
    }

    public boolean forceEquipItem(Item toEquip) {

        // Already equipped
        if (StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem() == toEquip) return true;

        // Always equip to the second slot. First + last is occupied by baritone.
        _mod.getPlayer().getInventory().selectedSlot = 1;

        // If our item is in our cursor, simply move it to the hotbar.
        boolean inCursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT).getItem() == toEquip;

        // (2026-08-24 真实背包扫描) 改用直接扫玩家背包槽位找物品, 不走 tracker
        // 缓存 — 缓存可能陈旧 (金锭刚收进背包就读不到 → 返回 false → 交易
        // 交互不触发, 用户实测 13:52 卡死: Approach 猪灵反复)。
        List<Slot> itemSlots = StorageHelper.getItemSlotsInInventoryReal(_mod, toEquip);
        if (itemSlots.size() != 0) {
            Slot slot = itemSlots.get(0);
            int hotbar = 1;
            //_mod.getPlayer().getInventory().swapSlotWithHotbar();
            clickSlotForce(Objects.requireNonNull(slot), inCursor? 0 : hotbar, inCursor? SlotActionType.PICKUP : SlotActionType.SWAP);
            //registerSlotAction();
            return true;
        }

        return false;
    }

    public boolean forceDeequipHitTool() {
        return forceDeequip(stack -> stack.getItem() instanceof ToolItem);
    }
    public void forceDeequipRightClickableItem() {
        forceDeequip(stack -> {
            Item item = stack.getItem();
                    return item instanceof BlockItem // any placeable block (2026-08-21: 手持圆石/木板右键, 视线稍偏或潜行残留就"贴着箱子放方块", 交互失效; 纯交互点击前换下方块, 空手右键无副作用)
                            || item instanceof BucketItem // water,lava,milk,fishes
                            || item instanceof EnderEyeItem
                            || item == Items.BOW
                            || item == Items.CROSSBOW
                            || item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE
                            || item == Items.ENDER_PEARL
                            || item instanceof FireworkRocketItem
                            || item instanceof SpawnEggItem
                            || item == Items.END_CRYSTAL
                            || item == Items.EXPERIENCE_BOTTLE
                            || item instanceof PotionItem // also includes splash/lingering
                            || item == Items.TRIDENT
                            || item == Items.WRITABLE_BOOK
                            || item == Items.WRITTEN_BOOK
                            || item instanceof FishingRodItem
                            || item instanceof OnAStickItem
                            || item == Items.COMPASS
                            || item instanceof EmptyMapItem
                            || item instanceof Wearable
                            || item == Items.SHIELD
                            || item == Items.LEAD;
                }
        );
    }

    /**
     * Tries to de-equip any item that we don't want equipped.
     *
     * @param isBad: Whether an item is bad/shouldn't be equipped
     * @return Whether we successfully de-equipped, or if we didn't have the item equipped at all.
     */
    public boolean forceDeequip(Predicate<ItemStack> isBad) {
        ItemStack equip = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
        ItemStack cursor = StorageHelper.getItemStackInSlot(CursorSlot.SLOT);
        if (isBad.test(cursor)) {
            // Throw away cursor slot OR move
            Optional<Slot> fittableSlots = _mod.getItemStorage().getSlotThatCanFitInPlayerInventory(equip, false);
            if (fittableSlots.isEmpty()) {
                // Try to swap items with the first non-bad slot.
                for (Slot slot : Slot.getCurrentScreenSlots()) {
                    if (!isBad.test(StorageHelper.getItemStackInSlot(slot))) {
                        clickSlotForce(slot, 0, SlotActionType.PICKUP);
                        return false;
                    }
                }
                if (ItemHelper.canThrowAwayStack(_mod, cursor)) {
                    clickSlotForce(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return true;
                }
                // Can't throw :(
                return false;
            } else {
                // Put in the empty/available slot.
                clickSlotForce(fittableSlots.get(), 0, SlotActionType.PICKUP);
                return true;
            }
        } else if (isBad.test(equip)) {
            // Pick up the item
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, SlotActionType.PICKUP);
            return false;
        } else if (equip.isEmpty() && !cursor.isEmpty()) {
            // cursor is good and equip is empty, so finish filling it in.
            clickSlotForce(PlayerSlot.getEquipSlot(), 0, SlotActionType.PICKUP);
            return true;
        }
        // We're already de-equipped
        return true;
    }
    public void forceEquipSlot(Slot slot) {
        Slot target = PlayerSlot.getEquipSlot();
        clickSlotForce(slot, target.getInventorySlot(), SlotActionType.SWAP);
    }

    public boolean forceEquipItem(Item[] matches, boolean unInterruptable) {
        return forceEquipItem(new ItemTarget(matches, 1), unInterruptable);
    }
    public boolean forceEquipItem(ItemTarget toEquip, boolean unInterruptable) {
        if (toEquip == null) return false;

        //If the bot try to eat
        if (_mod.getFoodChain().isTryingToEat() && !unInterruptable) { //unless we really need to force equip the item
            return false; //don't equip the item for now
        }

        Slot target = PlayerSlot.getEquipSlot();
        // Already equipped
        if (toEquip.matches(StorageHelper.getItemStackInSlot(target).getItem())) return true;

        for (Item item : toEquip.getMatches()) {
            // (2026-08-26 修复: ItemStorage 缓存陈旧导致坐不了船) 旧代码用
            // _mod.getItemStorage().hasItem(item) 当闸门 — 缓存可能陈旧 (船刚
            // 造好/捡回背包 cache 没刷新 → hasItem false → forceEquipItem 直接
            // 失败 → "Could not equip boat, swimming across instead", 18:00 日志
            // 实锤)。forceEquipItem(item) 内部用 getItemSlotsInInventoryReal 真实
            // 扫描背包, 直接以真实扫描为准, 不被陈旧缓存卡住。
            if (forceEquipItem(item)) return true;
        }
        return false;
    }

    // By default, don't force equip if the bot is eating.
    public boolean forceEquipItem(Item... toEquip) {
        return forceEquipItem(toEquip, false);
    }

    public void refreshInventory() {
        if (MinecraftClient.getInstance().player == null)
            return;
        for (int i = 0; i < MinecraftClient.getInstance().player.getInventory().main.size(); ++i) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            clickSlotForce(slot, 0, SlotActionType.PICKUP);
            clickSlotForce(slot, 0, SlotActionType.PICKUP);
        }
    }
}
