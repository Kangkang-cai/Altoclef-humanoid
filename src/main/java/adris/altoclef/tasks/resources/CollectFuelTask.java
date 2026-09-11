package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public class CollectFuelTask extends Task {

    private final double _targetFuel;

    // 燃料单位 = 可烧炼物品数（1 煤/木炭 = 8，1 木板/原木 = 1.5）。
    private static final double PLANK_FUEL = 1.5;
    // 只有附近这么远内有煤矿才值得去挖煤；否则直接降级砍树。
    private static final double COAL_SEARCH_RANGE = 64;

    public CollectFuelTask(double targetFuel) {
        _targetFuel = targetFuel;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // Nothing
    }

    @Override
    protected Task onTick(AltoClef mod) {

        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                // (2026-08-27 满额→缺口修复) 先扫真实背包算"已有多少燃料",
                // 只收集还差的部分, 而不是满额请求整批 — 旧版 "collect 6 planks"
                // 不减掉已有木板/原木/煤, 背包明明有木头也照样去砍一整批 (用户实测)。
                double currentFuel = StorageHelper.calculateInventoryFuelCount(mod);
                double deficit = _targetFuel - currentFuel;
                if (deficit <= 0) return null; // 防边界, 实际 by isFinished 不会到这
                // 优先煤炭，但只有附近（64 格内）有可见煤矿时才去挖——
                // 之前的实现强制挖煤，煤在几十格外/地下时 bot 会大老远
                // 跑去挖甚至卡死（2026-08-21 用户问题：无煤炭时降级用木头）。
                mod.getBlockTracker().trackBlock(Blocks.COAL_ORE);
                Optional<BlockPos> coal = mod.getBlockTracker().getNearestTracking(Blocks.COAL_ORE);
                if (coal.isPresent() && coal.get().isWithinDistance(mod.getPlayer().getPos(), COAL_SEARCH_RANGE)) {
                    setDebugState("Collecting coal.");
                    return TaskCatalogue.getItemTask(Items.COAL, (int) Math.ceil(deficit / 8));
                }
                // 降级：附近无煤时收集木板当燃料。用【通用 "planks" 资源】——
                // 不是 getItemTask(Items.OAK_PLANKS,..)（那只收橡木板，背包里有
                // 云杉/桦等其它木头也不利用，偏要去砍橡树）。通用任务木种无关：
                // 会先把已有任意原木转木板当燃料，真不够才补砍 (用户: 明明有其他
                // 木头却去收橡木)。
                // (2026-09-11 冻结 39s 死锁根修) 必须传【绝对目标数】而非缺口数:
                // CollectPlanksTask.isFinished 是"背包木板数 >= target"语义。旧版
                // 传 ceil(deficit/1.5)=3, 而背包已有 3 木板 → 子任务秒达标 → 永远
                // 只走清格分支不再合成; 本任务 deficit 恒 4.5>0 → 每 tick 重派同一
                // 实例 (isEqual 挡下) → 父要木板/子不合成, 框架不查中间层
                // isFinished 且无超时 → 冻结至手动停止 (22:17:34-22:18:13 实锤)。
                // 改传 ceil(_targetFuel/1.5) 绝对值: 已有 3 → 子任务真去补差额。
                setDebugState("No coal nearby, collecting wood as fuel.");
                return TaskCatalogue.getItemTask("planks",
                        (int) Math.ceil(_targetFuel / PLANK_FUEL));
            }
            case END -> {
                setDebugState("Going to overworld, since, well, no more fuel can be found here.");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
            case NETHER -> {
                // (2026-08-24 用户设计: 原木先做成一组木板再烧, 别直接用原木烧,
                // 也别一下全做) 下界有原木/木板 → CollectPlanksTask 只把【需要的】
                // 原木合成木板 (2x2 合成格, 1 原木→4 木板, 有界批量) 当燃料;
                // 原木本身不在 supportedFuels 里, 熔炉不会直接烧原木。背包既无
                // 原木也无木板才回主世界 (旧逻辑无脑"去主世界"是 bug)。
                if (mod.getItemStorage().getItemCount(ItemHelper.PLANKS)
                        + mod.getItemStorage().getItemCount(ItemHelper.LOG) * 4 > 0) {
                    setDebugState("Crafting planks from logs as fuel");
                    return new CollectPlanksTask(Math.max(16, (int) Math.ceil(_targetFuel / PLANK_FUEL)));
                }
                setDebugState("Going to overworld for fuel (no wood here)");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
        }
        setDebugState("INVALID DIMENSION: " + WorldHelper.getCurrentDimension());
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Nothing
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectFuelTask task) {
            return Math.abs(task._targetFuel - _targetFuel) < 0.01;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 燃料够就行，形式不限：煤/木炭/木板都计入（旧实现只数煤炭，
        // 而且拿煤炭"个数"和燃料"单位"直接比较，语义就是错的）。
        return StorageHelper.calculateInventoryFuelCount(mod) >= _targetFuel;
    }

    @Override
    protected String toDebugString() {
        return "Collect Fuel: x" + _targetFuel;
    }
}
