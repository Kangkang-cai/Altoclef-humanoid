package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.Items;

import java.util.Optional;

/**
 * (2026-09-05 下界黑曜石改猪灵交易, 用户"文件里没有传送门坐标时, 触发和猪灵
 * 交易获得黑曜石; 交易路线参考获取防火药水的路径")
 *
 * 背景: 传送门记忆跨会话持久化后, "记忆里一扇门都没有"的兜底链走到
 * ConstructNetherPortalObsidianTask → TaskCatalogue.getItemTask(OBSIDIAN) →
 * CollectObsidianTask。但旧版 CollectObsidianTask 在下界是死路: 下界没有天然
 * 黑曜石, 水流铸造分支被 isUltrawarm 挡住, 永远 TimeoutWander 打转。
 *
 * 方案: 完全照抄 CollectFireResPotionsTask 的人类式交易路线 —
 *   ① 下界挖金 (金粒 9→1 锭, CollectGoldIngotTask 自动续)
 *   ② 穿金靴 (猪灵对无金装玩家敌对, 项目统一金靴不搞两套)
 *   ③ TradeWithPiglinsTask 交易直到持有 ≥ 目标黑曜石数
 *      (猪灵交易黑曜石掉率 ~8.7%/锭, 10 块门框期望 ~115 锭, 8 格内末影人
 *      优先击杀的老规矩一并沿用, 长时间挖金难免被骚扰)。
 *
 * 搭门本身不在这里 — ConstructNetherPortalObsidianTask 是现成的 (10 块门框
 * + 打火石点火), 本任务只负责把黑曜石凑够。
 */
public class CollectObsidianByBarterTask extends Task {

    private final int _count;
    // 每轮交易的金锭预算 (与防火药水阶段一致, TradeWithPiglinsTask 金锭耗尽自动续挖)
    private static final int GOLD_BUFFER = 32;
    // 整阶段超时兜底: 交易随机 + 挖金量大 (期望 ~115 锭), 40 分钟宽裕; 超时
    // 放弃后外层兜底链还能尝试别的路 (比如游荡撞见有真实传送门的区块)。
    private static final double PHASE_TIMEOUT_SECONDS = 40 * 60;
    private final TimerGame _phaseTimeout = new TimerGame(PHASE_TIMEOUT_SECONDS);
    private boolean _gaveUp = false;
    private final Task _tradeTask;

    public CollectObsidianByBarterTask(int count) {
        _count = count;
        _tradeTask = new TradeWithPiglinsTask(GOLD_BUFFER, Items.OBSIDIAN, count);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _phaseTimeout.reset();
        _gaveUp = false;
        // 挖金+交易期间保护合成台 (金粒→金锭要用) 和黑曜石 (交易来的别被丢物逻辑扔了)
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.CRAFTING_TABLE);
        mod.getBehaviour().addProtectedItems(Items.OBSIDIAN);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // 已达标直接结束 (外层 ResourceTask 也会查, 这里提前收口)
        if (mod.getItemStorage().getItemCount(Items.OBSIDIAN) >= _count) {
            return null;
        }

        // ⓪ 捡眼前的黑曜石掉落物 (猪灵丢的/战斗掉落, 别视而不见)
        Optional<Entity> obsidianDrop = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                e -> e instanceof ItemEntity ie && ie.isAlive() && ie.isInRange(mod.getPlayer(), 16)
                        && ie.getStack().getItem() == Items.OBSIDIAN,
                ItemEntity.class);
        if (obsidianDrop.isPresent()) {
            setDebugState("Picking up obsidian drop");
            return new PickupDroppedItemTask(new ItemTarget(Items.OBSIDIAN, 1), true);
        }

        // ① 超时兜底 (不卡死主线)
        if (_phaseTimeout.elapsed() && !_gaveUp) {
            _gaveUp = true;
            adris.altoclef.util.helpers.TimeoutLog.hit("CollectObsidianByBarterTask.phaseTimeout",
                    "phase timeout, giving up this phase");
            Debug.logMessage("(ObsidianBarter) Phase timed out with "
                    + mod.getItemStorage().getItemCount(Items.OBSIDIAN) + "/" + _count
                    + " obsidian. Giving up this round.");
            Debug.logToFile("altoclef_diag.log", "[ObsidianBarter] phase TIMEOUT, obsidian="
                    + mod.getItemStorage().getItemCount(Items.OBSIDIAN) + "/" + _count);
        }
        if (_gaveUp) {
            setDebugState("Giving up bartering for obsidian (timeout)");
            return null;
        }

        // ② 必须在下界 (下界金矿 + 猪灵都在下界)
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether for obsidian bartering");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }

        // ③ (贪婪打末影人, 与防火药水阶段同款) 珍珠 <16 且 8 格内末影人 → 优先击杀。
        //    长时间挖金难免被末影人骚扰, 只躲不打会反复蹭血。
        if (mod.getItemStorage().getItemCount(Items.ENDER_PEARL) < 16) {
            Optional<Entity> enderman = mod.getEntityTracker().getClosestEntity(EndermanEntity.class);
            if (enderman.isPresent() && enderman.get().isInRange(mod.getPlayer(), 8)) {
                setDebugState("Greedy enderman kill (within 8 blocks, during obsidian barter)");
                return new KillAndLootTask(EndermanEntity.class, new ItemTarget(Items.ENDER_PEARL, 16));
            }
        }

        // ④ 穿金靴 (猪灵敌对判定, 项目统一金靴)
        if (!StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS)) {
            if (mod.getItemStorage().hasItemInventoryOnly(Items.GOLDEN_BOOTS)) {
                setDebugState("Equipping golden boots for piglin trading");
                return new EquipArmorTask(Items.GOLDEN_BOOTS);
            }
            setDebugState("Crafting golden boots for piglin trading");
            return TaskCatalogue.getItemTask(Items.GOLDEN_BOOTS, 1);
        }

        // ⑤ 交易循环 (金锭耗尽自动续挖, 直到持有 ≥ 目标黑曜石)
        setDebugState("Bartering piglins for obsidian ("
                + mod.getItemStorage().getItemCount(Items.OBSIDIAN) + "/" + _count + ")");
        return _tradeTask;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return mod.getItemStorage().getItemCount(Items.OBSIDIAN) >= _count || _gaveUp;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CollectObsidianByBarterTask task
                && task._count == _count;
    }

    @Override
    protected String toDebugString() {
        return "Bartering for " + _count + " obsidian";
    }
}
