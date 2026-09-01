package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class CollectBedTask extends CraftWithMatchingWoolTask {

    public static final Block[] BEDS = ItemHelper.itemsToBlocks(ItemHelper.BED);

    // (2026-08-27 收床来源距离) 感知到的现成床只有在 96 格内才"挖去拿";
    // 超过则走合成分支(杀羊/羊毛做床), 不为一张床长途折返。
    private static final double BED_GRAB_RANGE = 96.0;

    // Visual-only: used to keep isEqualResource stable across craft/mine swings.
    private final ItemTarget _visualBedTarget;

    public CollectBedTask(Item[] beds, ItemTarget wool, int count) {
        // Top 3 are wool, must be the same.
        super(new ItemTarget(beds, count), colorfulItems -> colorfulItems.wool, colorfulItems -> colorfulItems.bed, createBedRecipe(wool), new boolean[]{true, true, true, false, false, false, false, false, false});
        _visualBedTarget = new ItemTarget(beds, count);
    }

    public CollectBedTask(Item bed, String woolCatalogueName, int count) {
        this(new Item[]{bed}, new ItemTarget(woolCatalogueName, 1), count);
    }

    public CollectBedTask(int count) {
        this(ItemHelper.BED, TaskCatalogue.getItemTarget("wool", 1), count);
    }

    private static CraftingRecipe createBedRecipe(ItemTarget wool) {
        ItemTarget w = wool;
        ItemTarget p = TaskCatalogue.getItemTarget("planks", 1);
        return CraftingRecipe.newShapedRecipe(new ItemTarget[]{w, w, w, p, p, p, null, null, null}, 1);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(BEDS);
        super.onResourceStart(mod);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(BEDS);
        super.onResourceStop(mod, interruptTask);
    }

    // 挖床分支决策滞回 (2026-08-21 挖床↔收集木头横跳修复):
    // anyFound(BEDS) 会随 BlockTracker 缓存闪烁 — 导航中途一次进度
    // 检查失败 → 床被黑名单 2 秒 → 下次重扫(约 7 秒间隔)时从缓存移除
    // → anyFound 翻 false → 本任务切到合成分支(收集羊毛/木板=砍树) →
    // bot 放弃走到一半的导航; 黑名单过期后重扫又把床加回来 → anyFound
    // 翻 true → 又切回挖床... 12 秒窗口完全覆盖"黑名单 2s + 重扫 7s"的
    // 闪烁周期: 只要床在 12 秒内还被看见过, 就保持挖床分支不翻转。
    private final TimerGame _bedsSeenRecently = new TimerGame(12);

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // (2026-08-31 修正"可感知床"判定, 用户"96格挖床逻辑对, 刚才是不可感知床触发了")
        // 96 格内挖现成床的分支是正确的, 但之前的判定用 getKnownLocations(缓存里
        // 有位置就算"有床"), 没有做"可感知(LOS/视野)"过滤 —— 于是"不可感知"的床
        // (被岩石挡住/在身后)也会触发 → MineAndCollectTask(bed) 因那些床 getClosestTo
        // 的 LOS 感知拒绝而挖不到 → 无目标 → 偏置探索乱逛/卡死不动。
        // 现在: 仅当 96 格内存在【真正可感知 + 可挖】的床才切挖床; 否则一律走
        // super 的杀羊做床正路。
        if (mod.getBlockTracker().anyFound(BEDS)) {
            _bedsSeenRecently.reset();
        }
        if (!_bedsSeenRecently.elapsed()) {
            List<BlockPos> knownBeds = mod.getBlockTracker().getKnownLocations(ItemHelper.itemsToBlocks(ItemHelper.BED));
            BlockPos playerPos = mod.getPlayer() != null ? mod.getPlayer().getBlockPos() : null;
            boolean perceivableBedInRange = playerPos != null && knownBeds.stream().anyMatch(bed ->
                    bed.isWithinDistance(playerPos, BED_GRAB_RANGE)
                            && VisibilityHelper.isBlockPerceivable(mod, bed)
                            && WorldHelper.canBreak(mod, bed));
            if (!perceivableBedInRange) {
                // 96 格内没有可感知/可挖的床 → 回到作者正路: 杀羊取毛做床。
                markProtectedBedsUnreachable(mod);
                return super.onResourceTick(mod);
            }
            // 真实可感知的床就在附近 → 拆它拿床。
            markProtectedBedsUnreachable(mod);
            return new MineAndCollectTask(new ItemTarget(ItemHelper.BED, 1), BEDS, MiningRequirement.HAND);
        }
        markProtectedBedsUnreachable(mod);
        return super.onResourceTick(mod);
    }

    /**
     * (2026-08-31) 把"自己放的重生点床 + 玩家身旁 8 格内床"标为不可达,
     * 防止做床/杀羊过程中把身边刚睡的/出生点床撸掉。
     */
    private void markProtectedBedsUnreachable(AltoClef mod) {
        BlockPos selfBed = mod.getLastSelfPlacedBed();
        if (selfBed != null) {
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dz = -1; dz <= 1; ++dz) {
                    BlockPos c = selfBed.add(dx, 0, dz);
                    if (mod.getWorld().getBlockState(c).getBlock() instanceof BedBlock) {
                        mod.getBlockTracker().requestBlockUnreachable(c);
                    }
                }
            }
        }
        BlockPos playerPos = mod.getPlayer() != null ? mod.getPlayer().getBlockPos() : null;
        if (playerPos == null) return;
        for (BlockPos bed : mod.getBlockTracker().getKnownLocations(ItemHelper.itemsToBlocks(ItemHelper.BED))) {
            if (bed.isWithinDistance(playerPos, 8.0)
                    && mod.getWorld().getBlockState(bed).getBlock() instanceof BedBlock) {
                mod.getBlockTracker().requestBlockUnreachable(bed);
            }
        }
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBedTask task) {
            return task._visualBedTarget.equals(_visualBedTarget);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting bed: " + _visualBedTarget;
    }
}
