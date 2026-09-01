package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * (2026-08-31) 收熔炉前的"取尽内容物"任务。
 *
 * 用途: BeatMinecraft2Task 里"顺路收熔炉"分支在拆炉之前先派本任务, 把炉内
 * input / fuel / output 三个槽里的物品【全部】取出放进背包, 确认取尽后才拆炉
 * 带走。用户实测: 熔炼 9 铁锭到一半被 "Pick up furnace while we're at it"
 * 拦腰打断, 炉内还烧着/还没取走的肉被连炉一起挖掉丢失 (2026-08-31 12:42 日志)。
 *
 * 流程: 打开最近真实熔炉 → 逐槽 PICKUP 取物 → 光标物品放回背包 → 三槽全空即
 * _emptied → 关屏 → 用 MineAndCollectTask 把已清空的熔炉拆走。全程复用
 * DoStuffInContainerTask 的"走到/打开容器"逻辑与 ClickSlotTask 槽位点击, 不新造轮子。
 */
public class EmptyFurnaceTask extends DoStuffInContainerTask {

    private boolean _emptied = false;
    private MineAndCollectTask _mineTask = null;

    public EmptyFurnaceTask() {
        super(Blocks.FURNACE, new ItemTarget(Items.FURNACE));
    }

    @Override
    protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
        return other instanceof EmptyFurnaceTask;
    }

    @Override
    protected boolean isContainerOpen(AltoClef mod) {
        return mod.getPlayer() != null && mod.getPlayer().currentScreenHandler instanceof FurnaceScreenHandler;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-08-31) 内容已取尽 → 锁定"拆炉"阶段, 不再回容器分支反复打开。
        if (_emptied) {
            if (_mineTask == null) {
                _mineTask = new MineAndCollectTask(Items.FURNACE, 1, new Block[]{Blocks.FURNACE}, MiningRequirement.HAND);
            }
            setDebugState("Mining the emptied furnace away");
            return _mineTask;
        }
        return super.onTick(mod);
    }

    @Override
    protected Task containerSubTask(AltoClef mod) {
        // 光标先清回背包 (PICKUP 拿起的东西要落袋才算取走)。
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false)
                    .or(() -> StorageHelper.getGarbageSlot(mod));
            if (toFit.isPresent()) {
                setDebugState("Storing furnace content");
                return new ClickSlotTask(toFit.get());
            }
            setDebugState("Closing screen to free cursor");
            StorageHelper.closeScreen();
            return null;
        }

        // 逐个槽取尽: input / fuel / output。燃料槽(FUEL)同样必须取走 — 用户实测
        // "收熔炉时只拿产出入包、燃料留在炉里", 收炉必须连燃料一起带走。
        for (Slot slot : new Slot[]{FurnaceSlot.INPUT_SLOT_MATERIALS, FurnaceSlot.INPUT_SLOT_FUEL, FurnaceSlot.OUTPUT_SLOT}) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!stack.isEmpty()) {
                setDebugState("Taking " + stack.getItem() + " out of furnace");
                String slotName = (slot == FurnaceSlot.INPUT_SLOT_FUEL) ? "FUEL"
                        : (slot == FurnaceSlot.INPUT_SLOT_MATERIALS) ? "INPUT" : "OUTPUT";
                Debug.logToFile("altoclef_diag.log",
                        "[MAIN-FIX]\t[EmptyFurnace]\tTaking " + slotName + " " + stack.getItem() + " x" + stack.getCount());
                return new ClickSlotTask(slot, SlotActionType.PICKUP);
            }
        }

        // 三槽(input/fuel/output)全空 → 确认取尽(含燃料), 关屏去拆炉。
        _emptied = true;
        setDebugState("Furnace emptied");
        Debug.logToFile("altoclef_diag.log",
                "[MAIN-FIX]\t[EmptyFurnace]\tFurnace emptied (input/fuel/output all taken), mining it away");
        StorageHelper.closeScreen();
        return null;
    }

    @Override
    protected double getCostToMakeNew(AltoClef mod) {
        // 我们是去"取空并拆走眼前真实熔炉", 绝不合成新的。
        return 9999999;
    }

    @Override
    protected BlockPos overrideContainerPosition(AltoClef mod) {
        // 优先锁定玩家近场真实存在的熔炉 (与 DoSmeltInFurnaceTask 同款兜底), 不依赖
        // 异步缓存; 正在收熔炉的场景熔炉必在近场。
        if (mod.getPlayer() == null) return getTargetContainerPosition();
        BlockPos player = mod.getPlayer().getBlockPos();
        int r = 4;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = player.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE) {
                        double sq = pos.getSquaredDistance(player);
                        if (sq < bestSq) {
                            bestSq = sq;
                            best = pos;
                        }
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        return getTargetContainerPosition();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 取尽内容后, 只有拆下来的熔炉真正落袋/任务收尾才算完成。
        if (_emptied && _mineTask != null) {
            return _mineTask.isFinished(mod);
        }
        return _emptied;
    }

    @Override
    protected String toDebugString() {
        return "Emptying furnace before removing it";
    }
}