package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

/**
 * 铁锭复合收集任务 (2026-10-01, 用户"铁粒也不要丢, 保证 bot 知道把所有铁粒
 * 做成铁锭") — 金锭 CollectGoldIngotTask 的同款结构:
 * <ol>
 *   <li>铁粒折算 (全维度, 优先): 背包铁粒 (含合成格) ≥ 9 即合成
 *       floor(粒/9) 个铁锭 — "把所有(可折算的)铁粒做成锭"; 下界有铁粒时
 *       不再为主世界挖矿专门开门。</li>
 *   <li>生铁熔炼: 主世界整额熔炼 (与旧 smelt(iron_ingot <- raw_iron) 口径
 *       一致, 可选铁矿石直接烧); 下界只熔"缺口所需"的生铁 (1008 收紧:
 *       恰好原则, 不再整包全塞), 烧完仍不足回主世界。</li>
 * </ol>
 * 修复背景: importantItems 被磁盘配置覆盖导致 raw_iron 被丢 (见
 * Settings.CODE_PROTECTED_BASELINE), 铁粒此前无任何保护且目录里没有
 * 粒→锭配方, bot 拿着铁粒只会去挖矿。
 */
public class CollectIronIngotTask extends ResourceTask {

    private final int _count;
    // (0824 熔炼锁定修复, 金锭同款) 已派出的熔炼任务实例, 烧完前不换任务,
    // 防"生铁入炉→背包计数下降→重评估改派挖矿"横跳。
    private SmeltInFurnaceTask _smeltTask = null;
    // (0824 熔炉回收, 金锭同款) 熔炼完成后拆掉带走自己放置的熔炉。
    private BlockPos _recoveringFurnacePos = null;

    public CollectIronIngotTask(int count) {
        super(Items.IRON_INGOT, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        int ingots = StorageHelper.countItemInInventoryReal(mod, Items.IRON_INGOT);
        int nuggets = countNuggetsIncludingCraftGrids(mod);
        int rawIron = StorageHelper.countItemInInventoryReal(mod, Items.RAW_IRON);
        String m = "(CollectIronIngot) start: target=" + _count + " ingots=" + ingots
                + " nuggets=" + nuggets + " rawIron=" + rawIron
                + " dim=" + WorldHelper.getCurrentDimension();
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // ---- 熔炼锁定: 烧完前持续返回同一实例 ----
        if (_smeltTask != null) {
            if (!_smeltTask.isFinished(mod)) {
                return _smeltTask;
            }
        }
        boolean justSmelted = _smeltTask != null;
        _smeltTask = null;

        // ---- 熔炉回收 (金锭同款): 烧完且背包无熔炉 → 拆回自己放的炉子 ----
        if (justSmelted && StorageHelper.countItemInInventoryReal(mod, Items.FURNACE) == 0) {
            _recoveringFurnacePos = findFurnaceNearby(mod);
        }
        if (_recoveringFurnacePos != null) {
            if (mod.getWorld().getBlockState(_recoveringFurnacePos).getBlock() == Blocks.FURNACE) {
                setDebugState("Taking back our furnace (break)");
                return new DestroyBlockTask(_recoveringFurnacePos);
            }
            if (StorageHelper.countItemInInventoryReal(mod, Items.FURNACE) > 0) {
                _recoveringFurnacePos = null;
            } else {
                setDebugState("Taking back our furnace (pickup)");
                return new PickupDroppedItemTask(new ItemTarget(Items.FURNACE, 1), true);
            }
        }

        int ingots = StorageHelper.countItemInInventoryReal(mod, Items.IRON_INGOT);
        if (ingots >= _count) {
            return null; // 真·铁锭已够
        }

        // ---- ① 铁粒折算 (全维度, 优先): ≥9 粒即合成, 一次折算 floor(粒/9) 锭 ----
        // RecipeTarget 用绝对持有目标 (现有锭 + 可折算锭): 0924"站死修复"口径 —
        // 传差值会被 DoCraftInTableTask 当"背包已有数量"判定, 开局即 DONE 空转。
        // 材料收集按 (目标 - 现有) 精确折算 → 需要的粒数恰好是可折算粒数, 不会
        // 触发多余的铁粒收集 (铁粒目录条目是"锭→粒"降级合成, 多收集会往返空转)。
        int nuggets = countNuggetsIncludingCraftGrids(mod);
        if (nuggets >= 9) {
            int craftable = nuggets / 9;
            ItemTarget n = new ItemTarget(Items.IRON_NUGGET);
            CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("iron_ingot",
                    new ItemTarget[]{n, n, n, n, n, n, n, n, n}, 1);
            setDebugState("Crafting " + craftable + " iron ingot(s) from " + nuggets + " nuggets");
            return new CraftInTableTask(new RecipeTarget(Items.IRON_INGOT, ingots + craftable, recipe));
        }

        // ---- ② 生铁熔炼 ----
        if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            // 下界: 只熔"缺口所需"的生铁 (1008 修复, 用户现场确认"只该放3个却塞
            // 了15个进炉": 旧动态目标 = 背包全部现成生铁, 只需 3 锭时整包 15 个
            // 全塞进炉, 多出的 12 个赶上 bot 死亡全部滞留下界炉子 — 违反"恰好"
            // 原则。0824 的防乒乓由 _smeltTask 熔炼锁兜住, 材料入炉导致的背包
            // 计数下降不会中途改派, 动态整包目标不再必要)。
            // 生铁是主世界资源, 下界挖不到; 现成生铁少于缺口时烧完仍不足回主世界。
            int rawIron = StorageHelper.countItemInInventoryReal(mod, Items.RAW_IRON);
            if (rawIron > 0) {
                int smeltCount = Math.min(rawIron, _count - ingots);
                _smeltTask = new SmeltInFurnaceTask(new SmeltTarget(
                        new ItemTarget(Items.IRON_INGOT, ingots + smeltCount),
                        new ItemTarget(Items.RAW_IRON, smeltCount)));
                return _smeltTask;
            }
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }
        // 主世界: 与旧目录条目 smelt(iron_ingot <- raw_iron, 可选铁矿石) 完全
        // 同口径 — 整额目标, 缺料由 SmeltInFurnaceTask 内部派挖矿收集。
        _smeltTask = new SmeltInFurnaceTask(new SmeltTarget(
                new ItemTarget(Items.IRON_INGOT, _count),
                new ItemTarget(Items.RAW_IRON, _count),
                Items.IRON_ORE));
        return _smeltTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (0824 熔炉回收优先级, 金锭同款) 回收未完成不算结束。
        if (_recoveringFurnacePos != null) {
            return false;
        }
        return super.isFinished(mod);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectIronIngotTask && ((CollectIronIngotTask) other)._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " iron ingots.";
    }

    /**
     * (0905 合成乒乓修复, 金锭同款) 铁粒计数 = 真实背包(含光标) + 2x2 随身
     * 合成格 + 3x3 工作台合成格(界面开着才有效)。摆进合成格的材料是"已投入
     * 合成"的, 不计入会让父任务在合成进行中误判"铁粒不足"而切去挖矿、打断合成。
     */
    private static int countNuggetsIncludingCraftGrids(AltoClef mod) {
        int count = StorageHelper.countItemInInventoryReal(mod, Items.IRON_NUGGET);
        count += StorageHelper.countItemsInCraftGrid(mod, Items.IRON_NUGGET);
        if (StorageHelper.isBigCraftingOpen()) {
            for (Slot slot : CraftingTableSlot.INPUT_SLOTS) {
                ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                if (!stack.isEmpty() && stack.getItem() == Items.IRON_NUGGET) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    /**
     * (0824 熔炉回收, 金锭同款) 找玩家附近最近的真实熔炉方块, 范围 4 格。
     */
    private BlockPos findFurnaceNearby(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pos = player.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE) {
                        double d = pos.getSquaredDistance(player);
                        if (d < bestDist) {
                            bestDist = d;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }
}
