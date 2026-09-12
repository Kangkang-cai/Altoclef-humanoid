package adris.altoclef.tasks.slot;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;

public class MoveItemToSlotFromInventoryTask extends MoveItemToSlotTask {
    // (2026-09-12 燃料搬运死循环根修) 源槽解析从 tracker 缓存改为【真实背包扫描】。
    // 现场 (20260912-075750 会话): "Filling fuel" 选料用真实背包扫到 spruce_planks
    // (fuelUnits=7.5), 本任务却按 tracker 找源槽 → tracker 陈旧 (planks=0, 槽位映射
    // 过期, 甚至 PICKUP 到实际装着 spruce_logx21 的格子) → logError + return null
    // → 父任务 2s 重派, latest.log 1964 条 "not enough item is available" 循环,
    // 木板永远进不了炉。与 8/24 两次定案同源 ("缓存可能陈旧, 必须以真实背包为准",
    // SlotHandler.forceEquipItem / SmeltInFurnaceTask 材料判定先例)。
    // 真实扫描只覆盖主背包 (与旧 includeCraftArmorOffhand=false 口径一致);
    // 光标上的物料由 MoveItemToSlotTask 的 currentHeld 分支自行处理, 不依赖源槽列表。
    public MoveItemToSlotFromInventoryTask(ItemTarget toMove, Slot destination) {
        super(toMove, destination, mod -> StorageHelper.getItemSlotsInInventoryReal(mod, toMove.getMatches()));
    }
}
