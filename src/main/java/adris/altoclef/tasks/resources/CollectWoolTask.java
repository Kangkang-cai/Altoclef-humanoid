package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.ShearSheepTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;

public class CollectWoolTask extends ResourceTask {

    private final int _count;

    private final HashSet<DyeColor> _colors;
    private final Item[] _wools;

    // Cached surface Y for the "return to surface" subtask (recomputed only
    // when the bot moves far from where it was last calculated).
    private Integer _cachedSurfaceY = null;
    private BlockPos _cachedSurfaceAt = null;

    // (2026-08-31 捡羊毛掉落优先, 同 CollectFoodTask) 地面有我们要的羊毛掉落时
    // "粘性"捡取任务: 派发后持续返回直到捡完, 不再被 Biased exploration 找下一只羊
    // 抢占 (用户 13:01 实测: "Pickup Dropped [white_wool] 刚启动就被 Searching for
    // mob 抢占, 羊毛没捡走")。
    private Task _woolDropPickupTask = null;
    private static final double WOOL_DROP_PICKUP_RANGE = 24;

    public CollectWoolTask(DyeColor[] colors, int count) {
        super(new ItemTarget(ItemHelper.WOOL, count));
        _colors = new HashSet<>(Arrays.asList(colors));
        _count = count;
        _wools = getWoolColorItems(colors);
    }

    public CollectWoolTask(DyeColor color, int count) {
        this(new DyeColor[]{color}, count);
    }

    public CollectWoolTask(int count) {
        this(DyeColor.values(), count);
    }

    private static Item[] getWoolColorItems(DyeColor[] colors) {
        Item[] result = new Item[colors.length];
        for (int i = 0; i < result.length; ++i) {
            result[i] = ItemHelper.getColorfulItems(colors[i]).wool;
        }
        return result;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(ItemHelper.itemsToBlocks(_wools));
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // (2026-09-01 根因修复: 羊毛已入包仍反复拾取/杀羊 + bed<->food 父任务横跳)
        // 999999 wool 是 CraftWithMatchingMaterialsTask.getAllSameResourcesTask 抛
        // 出的"无限目标", 任何现实背包都不可能装满 -> isFinished 恒 false ->
        // CollectWoolTask 永不结束, 父 CraftBed 也永不完成 -> BeatMinecraft2Task
        // 在 food<->bed 之间反复抢断 (tasks.log 14:03:57 / 14:04:00 两轮实锤)。
        // 解决方案: 对 _count 做语义化上限 — 一张床合成最多需要 3 块同色羊毛,
        // 64 块(一整组)足够应付任何即时合成需求; 当 _count>=64 视为 64 判达标,
        // 真有 64 块同色羊毛在背包就认为本收集阶段完成, 回到 CraftWithMatching-
        // MaterialsTask 去执行真正的床合成(不再无意义地收集)。
        // 达标时直接 return null 给父类, isFinished 自然判断(若 _count<=64 则
        // 父 ResourceTask.isFinished(itemTargetOwned POSSESS 真实背包)也会同步 true,
        // 任务退出; 若 _count=999999 则 ResourceTask.isFinished 仍 false 但
        // CraftWithMatchingMaterialsTask isFinished 自己按 canCraft>=needed 独立判)
        int effLimit = Math.min(_count, 64);
        boolean woolMet = StorageHelper.itemTargetsMetReal(mod, new ItemTarget(_wools, effLimit));
        if (woolMet) return null;
        // (2026-08-31 捡羊毛掉落优先) 未达标时: 地面有我们需要的羊毛掉落就
        // 先粘性捡掉, 再考虑杀/剪羊或探索 — 避免杀羊掉的羊毛落地后被
        // Biased exploration(找下一只羊) 抢占, 羊毛没捡走。
        Task woolDrop = tryPickupDroppedWool(mod);
        if (woolDrop != null) {
            return woolDrop;
        }

        // (2026-08-25 修复羊毛横跳, 用户实测 15:51: Collecting beds → Collect
        // 999999 wool → 反复在"杀羊"和"挖羊毛方块→挖通道(Digging down to
        // find ores)"之间横跳)。根因: 原逻辑只要 BlockTracker 感知到任何羊毛
        // 方块(哪怕很远/不可达)就派 MineAndCollectTask 挖羊毛, 挖不到可达
        // 目标就退化"挖矿挖通道" — 羊毛在地表, 挖通道找矿完全不对。
        // 调整: 杀羊优先 (羊是感知目标, 无时限原则), 挖羊毛方块只限近距离。

        // TODO: If we don't find good color wool blocks
        // and we DONT find good color sheep:
        // USE DYES + REGULAR WOOL TO CRAFT THE WOOL COLOR!!

        Block[] woolBlocks = ItemHelper.itemsToBlocks(_wools);

        // 1. 附近有羊 → 优先剪/杀羊收集羊毛 (感知目标, 无时限)
        if (mod.getEntityTracker().entityFound(SheepEntity.class)) {
            if (mod.getItemStorage().hasItem(Items.SHEARS)) {
                return new ShearSheepTask();
            }
            return new KillAndLootTask(SheepEntity.class, entity -> {
                if (entity instanceof SheepEntity sheep) {
                    // Hunt sheep of the same color.
                    return _colors.contains(sheep.getColor()) && !sheep.isSheared();
                }
                return false;
            }, new ItemTarget(_wools, _count));
        }

        // 2. 维度不对且无羊 → 去正确维度
        if (isInWrongDimension(mod)) {
            return getToCorrectDimensionTask(mod);
        }

        // 3. 无羊且深地下 → 回地表 (羊毛/羊都不会出现在深地下)
        if (WorldHelper.isDeepUnderground(mod)) {
            Task surfaceTask = getReturnToSurfaceTask(mod);
            if (surfaceTask != null) {
                setDebugState("No sheep underground: returning to surface");
                return surfaceTask;
            }
        }

        // 4. 近距离 (24 格内) 有羊毛方块 → 挖 (快速备选; 远距离羊毛不可达时
        //     MineAndCollectTask 会退化挖通道, 所以只挖近处的)
        Optional<BlockPos> nearbyWool = mod.getBlockTracker().getNearestTracking(woolBlocks);
        if (nearbyWool.isPresent() && nearbyWool.get().isWithinDistance(mod.getPlayer().getPos(), 24)) {
            return new MineAndCollectTask(new ItemTarget(_wools), woolBlocks, MiningRequirement.HAND);
        }

        // 5. 兜底: 没有羊也没有近处羊毛 → 游荡找羊 (感知目标, 无时限)
        setDebugState("No sheep or nearby wool: hunting sheep");
        return new KillAndLootTask(SheepEntity.class, entity -> {
            if (entity instanceof SheepEntity sheep) {
                return _colors.contains(sheep.getColor()) && !sheep.isSheared();
            }
            return false;
        }, new ItemTarget(_wools, _count));
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(ItemHelper.itemsToBlocks(_wools));
    }

    /**
     * (2026-08-31 捡羊毛掉落优先, 同 CollectFoodTask) 地面有我们需要的羊毛掉落
     * (在 _wools 内且在范围内) 时返回"粘性"捡取任务; 派发后持续返回直到捡完,
     * 不再被 Biased exploration(找下一只羊) 抢占。
     */
    private Task tryPickupDroppedWool(AltoClef mod) {
        if (_woolDropPickupTask != null) {
            if (_woolDropPickupTask.isActive() && !_woolDropPickupTask.isFinished(mod)) {
                return _woolDropPickupTask;
            }
            _woolDropPickupTask = null;
        }
        Vec3d playerPos = mod.getPlayer().getPos();
        ItemEntity best = null;
        double bestSq = WOOL_DROP_PICKUP_RANGE * WOOL_DROP_PICKUP_RANGE;
        for (ItemEntity drop : mod.getEntityTracker().getDroppedItems()) {
            if (drop == null || !drop.isAlive()) continue;
            Item it = drop.getStack().getItem();
            boolean wanted = false;
            for (Item w : _wools) {
                if (w == it) { wanted = true; break; }
            }
            if (!wanted) continue;
            double sq = drop.squaredDistanceTo(playerPos);
            if (sq < bestSq) {
                bestSq = sq;
                best = drop;
            }
        }
        if (best != null) {
            _woolDropPickupTask = new PickupDroppedItemTask(new ItemTarget(best.getStack().getItem()), true);
            setDebugState("Picking up wool drop (priority over hunting sheep)");
            Debug.logToFile("altoclef_diag.log",
                    "[FOOD-FIX]\t[WoolPickupFirst]\tground wool " + best.getStack().getItem()
                            + " within " + WOOL_DROP_PICKUP_RANGE + " -> sticky pickup before searching next sheep");
            return _woolDropPickupTask;
        }
        return null;
    }

    /**
     * Subtask that climbs back to the surface so sheep can actually be found.
     * Uses the cached land-surface Y and only recomputes it when we walked far
     * away from where it was calculated.
     */
    private Task getReturnToSurfaceTask(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        if (_cachedSurfaceY == null || _cachedSurfaceAt == null
                || player.getSquaredDistance(_cachedSurfaceAt) > 24 * 24) {
            _cachedSurfaceY = WorldHelper.getLandSurfaceY(mod, player.getX(), player.getZ(), 16);
            _cachedSurfaceAt = player.toImmutable();
        }
        if (_cachedSurfaceY == null || _cachedSurfaceY < 0) {
            // No land column nearby (e.g. under an ocean): let normal
            // exploration handle it.
            return null;
        }
        return new GetToYTask(_cachedSurfaceY);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectWoolTask && ((CollectWoolTask) other)._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " wool.";
    }

}
