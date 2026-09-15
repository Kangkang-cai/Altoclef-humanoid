package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.Items;

import java.util.Optional;

/**
 * (2026-08-23 防火药水保命阶段) 用户设计的大任务:
 *   ① 下界挖金 (下界金矿出金粒, 9 粒 = 1 锭, 比主世界挖矿+熔炼快)
 *   ② 与猪灵交易 (丢金锭换物品), 直到拿到至少 2 瓶防火药水
 *      (2026-09-15 用户拍板: 维持 2 瓶; BeatMinecraft2Task.FIRE_RES_POTION_TARGET=2
 *      是唯一权威, 本类不持有目标常量, 目标数由外层传入)
 *   ③ 之后打烈焰人时喝药水免疫火焰伤害 (CollectBlazeRodsTask 集成)
 *
 * 周全考虑的细节:
 * - 猪灵敌对: bot 不穿金装时猪灵会攻击而不交易 → 先穿金靴子 (与项目
 *   现有 getEnderPearlTask 猪灵交易前置一致, 统一金靴不搞两套)。
 * - 交易概率: 防火药水 ~3.5%/次, 32 锭大概率不够 2 瓶 → 金锭耗尽时
 *   TradeWithPiglinsTask 内部自动续挖, 循环直到达标。
 * - 超时兜底: 交易是随机的, 防极端运气差死循环阻塞主线 — 12 分钟后
 *   放弃 (有几瓶用几瓶, 0 瓶也放行回到原流程)。
 */
public class CollectFireResPotionsTask extends Task {

    private final int _targetCount;
    // 每轮交易的金锭数 (用户设计 32): TradeWithPiglinsTask 金锭耗尽自动续挖
    private static final int GOLD_BUFFER = 32;
    // 整阶段超时 (秒): 兜底防死循环。挖 32 金 + 交易 32 次 ≈ 3-5 分钟/轮,
    // 期望 2-3 轮达标, 12 分钟足够宽裕。
    private static final double PHASE_TIMEOUT_SECONDS = 12 * 60;
    private final TimerGame _phaseTimeout = new TimerGame(PHASE_TIMEOUT_SECONDS);
    private boolean _gaveUp = false;
    // 交易子任务 (永不满足的假目标 DRAGON_EGG×999999: 让它一直交易直到外层
    // 检测药水达标。1.18.2 没有统一的 Items.BED — 床是 16 个颜色变体)
    private final Task _tradeTask = new TradeWithPiglinsTask(GOLD_BUFFER, Items.DRAGON_EGG, 999999);

    public CollectFireResPotionsTask(int targetCount) {
        _targetCount = targetCount;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _phaseTimeout.reset();
        // (2026-08-24 保命) 阶段内保护合成台 (金粒→金锭必须用, 别被丢物逻辑
        // 扔了) 和防火药水 (POTION/SPLASH_POTION, 阶段内背包里不会想要水瓶,
        // 保护比丢了好)。
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.CRAFTING_TABLE);
        mod.getBehaviour().addProtectedItems(Items.POTION, Items.SPLASH_POTION);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // ⓪ (2026-08-24 捡地上的防火药水) 猪灵丢的防火药水是 ItemEntity。之前
        // 只放在交易子任务 (PerformTradeWithPiglin.onEntityInteract) 里检查,
        // 而 bot 大部分时间在挖金/游荡 — 用户实测: 药水就在眼前却不捡 ("该
        // 父任务下没有子任务搜寻该药水")。整个阶段每 tick 都优先检查: 玩家
        // 16 格内有防火药水掉落物 → 先捡 (NBT 感知, 排除水瓶), 比挖金/交易
        // 都优先。
        Optional<Entity> potionDrop = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                e -> e instanceof ItemEntity ie && ie.isAlive() && ie.isInRange(mod.getPlayer(), 16)
                        && ItemHelper.isFireResPotionStack(ie.getStack()),
                ItemEntity.class);
        if (potionDrop.isPresent()) {
            setDebugState("Picking up fire resistance potion drop");
            return new PickupDroppedItemTask(new ItemTarget[]{
                    new ItemTarget(Items.POTION, 1),
                    new ItemTarget(Items.SPLASH_POTION, 1)
            }, true);
        }

        // ① (2026-08-24 修正: 不强制回主世界搞合成台) 用户确认工作台不是
        // 问题 ("背包里明明有足够木头"), 下界需要合成台时 CraftInTableTask
        // 会用背包木头就地做。只在主世界时顺手确保带一张 (进地狱前前置,
        // 不强行跑腿)。
        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD
                && !mod.getItemStorage().hasItemInventoryOnly(Items.CRAFTING_TABLE)) {
            setDebugState("Getting a crafting table before entering nether");
            return TaskCatalogue.getItemTask(Items.CRAFTING_TABLE, 1);
        }

        // ① 必须在下界 (下界金矿 + 猪灵都在下界)
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether for fire resistance potions");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }

        // ①.5 (2026-08-23 贪婪打末影人) 防火药水阶段 (挖金/交易) 里,
        // 8 格内末影人优先击杀攒珍珠 — 用户设计: 末影人靠近到不可能无视
        // 我们的距离就直接打, 高优先级不切换 (否则挖金/交易时看到末影人
        // 只躲不打, 它反复骚扰)。
        if (mod.getItemStorage().getItemCount(Items.ENDER_PEARL) < 16) {
            Optional<Entity> enderman = mod.getEntityTracker().getClosestEntity(EndermanEntity.class);
            if (enderman.isPresent() && enderman.get().isInRange(mod.getPlayer(), 8)) {
                setDebugState("Greedy enderman kill (within 8 blocks, during fire-res phase)");
                return new KillAndLootTask(EndermanEntity.class, new ItemTarget(Items.ENDER_PEARL, 16));
            }
        }

        // ② 超时兜底: 放弃本阶段 (不阻塞主线, 打烈焰人回到原流程)
        if (_phaseTimeout.elapsed() && !_gaveUp) {
            _gaveUp = true;
            Debug.logMessage("(FireRes) Phase timed out after " + (int) PHASE_TIMEOUT_SECONDS + "s with "
                    + ItemHelper.countFireResPotions(mod) + "/" + _targetCount
                    + " potions. Giving up, continuing without full supply.");
            Debug.logToFile("altoclef_diag.log", "[FireRes] phase TIMEOUT, potions="
                    + ItemHelper.countFireResPotions(mod) + "/" + _targetCount);
            stop(mod);
            return null;
        }

        // ③ 穿金靴 (猪灵对无金装玩家敌对, 愤怒时不交易)。与项目现有
        // getEnderPearlTask 的猪灵交易逻辑保持一致: 统一用金靴子 (4 锭,
        // 最便宜), 不用金头盔 (用户要求: 项目原本就有穿金靴逻辑, 别搞两套)。
        // 背包有金靴 → EquipArmorTask 穿上; 没有 → 合成 (自动挖所需金)。
        if (!StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS)) {
            if (mod.getItemStorage().hasItemInventoryOnly(Items.GOLDEN_BOOTS)) {
                setDebugState("Equipping golden boots for piglin trading");
                return new EquipArmorTask(Items.GOLDEN_BOOTS);
            }
            setDebugState("Crafting golden boots for piglin trading");
            return TaskCatalogue.getItemTask(Items.GOLDEN_BOOTS, 1);
        }

        // ④ 交易循环 (金锭耗尽自动续挖, 直到外层检测药水达标)
        setDebugState("Trading with piglins for fire resistance potions ("
                + ItemHelper.countFireResPotions(mod) + "/" + _targetCount + ")");
        return _tradeTask;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 达标 或 主动放弃
        return ItemHelper.countFireResPotions(mod) >= _targetCount || _gaveUp;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CollectFireResPotionsTask
                && ((CollectFireResPotionsTask) other)._targetCount == _targetCount;
    }

    @Override
    protected String toDebugString() {
        return "Collecting " + _targetCount + " fire resistance potions";
    }
}
