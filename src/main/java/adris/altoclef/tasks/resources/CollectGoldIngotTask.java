package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.*;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.Slot;

public class CollectGoldIngotTask extends ResourceTask {

    private final int _count;
    // (2026-08-24 熔炼锁定修复) 已派出的熔炼任务实例。一旦进入熔炼 raw_gold
    // 流程就锁定同一个 SmeltInFurnaceTask, 直到它烧完。原因: raw_gold 放进
    // 熔炉后背包 getItemCount(RAW_GOLD) 减少 → 下面的 ingots+rawGold>=_count
    // 判定失效 → 每 tick 重新评估会改派 MineAndCollectTask(挖金粒) → 打断
    // 熔炼 → Get Fuel↔挖矿反复横跳 (日志 TaskPingPong, 熔炼永远烧不完)。
    private SmeltInFurnaceTask _smeltTask = null;
    // (2026-08-24 熔炉回收) 熔炼完成后要拆掉带走自己用的熔炉 (下界熔炉宝贵)。
    // 熔炉被 DoStuffInContainerTask 放置出去后背包无熔炉物品 → 熔炼完拆掉
    // 捡回; 非 null 表示正在回收流程中 (拆 → 捡)。
    private BlockPos _recoveringFurnacePos = null;

    public CollectGoldIngotTask(int count) {
        super(Items.GOLD_INGOT, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        // (2026-08-23 诊断) 排查"背包有金却不用来当金锭" (用户反馈:
        // "bot 还是不使用背包里面的金矿当金锭, 会不会是因为它在 hotbar")。
        // 记录下界挖金时当前金锭/金粒/工作台状态, 对照 altoclef_diag.log
        // 判断是数量判定问题还是工作台缺失问题 (getItemCount 覆盖热栏+背包,
        // 理论上 hotbar 不影响)。
        if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            // (2026-08-24 真实背包兜底) 用 countItemInInventoryReal 直接扫真实
            // 背包, 不依赖 tracker 缓存 — 任务启动瞬间缓存可能陈旧 (用户实测:
            // 启动检测不到金锭, 按 esc 才刷新), 导致判定误走挖矿。
            int ingots = StorageHelper.countItemInInventoryReal(mod, Items.GOLD_INGOT);
            int rawGold = StorageHelper.countItemInInventoryReal(mod, Items.RAW_GOLD);
            int nuggs = StorageHelper.countItemInInventoryReal(mod, Items.GOLD_NUGGET);
            boolean hasTable = mod.getItemStorage().hasItemInventoryOnly(Items.CRAFTING_TABLE);
            String m = "(GoldDiag) nether: ingots=" + ingots + " rawGold=" + rawGold
                    + " nuggets=" + nuggs + " target=" + _count
                    + " (ingots+rawGold=" + (ingots + rawGold) + ") craftingTableInInv=" + hasTable;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // (2026-08-24 熔炼锁定修复) 熔炼未完成时持续返回同一个 _smeltTask,
        // 不因背包 raw_gold 数量变化而改派其他任务打断熔炼。
        boolean justSmelted = false;
        if (_smeltTask != null) {
            if (!_smeltTask.isFinished(mod)) {
                return _smeltTask;
            }
            justSmelted = true;
        }
        _smeltTask = null;

        // (2026-08-24 熔炉回收) 熔炼刚完成时, 若熔炉被放置出去了 (背包无熔炉
        // 物品), 找到最近的真实熔炉, 拆掉并捡回 — 下界熔炉宝贵, 别丢在原地
        // (用户实测: 烧完 32 锭直接走, 熔炉留在地上)。
        if (justSmelted && StorageHelper.countItemInInventoryReal(mod, Items.FURNACE) == 0) {
            _recoveringFurnacePos = findFurnaceNearby(mod);
        }
        if (_recoveringFurnacePos != null) {
            if (mod.getWorld().getBlockState(_recoveringFurnacePos).getBlock() == Blocks.FURNACE) {
                setDebugState("Taking back our furnace (break)");
                return new DestroyBlockTask(_recoveringFurnacePos);
            }
            // 熔炉已拆掉 → 捡回掉落物 (熔炉物品回背包才算完成)
            if (StorageHelper.countItemInInventoryReal(mod, Items.FURNACE) > 0) {
                _recoveringFurnacePos = null;
            } else {
                setDebugState("Taking back our furnace (pickup)");
                return new PickupDroppedItemTask(new ItemTarget(Items.FURNACE, 1), true);
            }
        }

        if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
            _smeltTask = new SmeltInFurnaceTask(new SmeltTarget(new ItemTarget(Items.GOLD_INGOT, _count), new ItemTarget(Items.RAW_GOLD, _count)));
            return _smeltTask;
        } else if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            // (2026-08-23 卡死修复) 完成判定必须基于【实际金锭数】而非金粒折算:
            // 旧逻辑用 totalNuggets≥需求 就 return null (背包有 288 金粒当量但
            // 0 金锭就"完成") → 金粒没合成金锭 → 下游交易任务 (TradeWithPiglinsTask)
            // 检查 hasItem(GOLD_INGOT)=false → 又触发 CollectGoldIngotTask → 死循环
            // 卡住。正确顺序: 金锭够才算完; 不够则先挖下界金矿出金粒 → 再合成金锭。
            //
            // (2026-08-23 修复: 下界熔炼 raw_gold 有坑) raw_gold 是主世界资源。
            // 下界若背包带了 raw_gold, 旧逻辑"熔炼 raw_gold" → SmeltInFurnaceTask
            // 缺料时派发 MineAndCollectTask(raw_gold) (强制 OVERWORLD) → 下界
            // 找不到 → DefaultGoToDimensionTask(OVERWORLD) → 造地狱门回主世界
            // (用户实测: Collecting 32 gold 却跑去造门)。
            //
            // (2026-08-24 修复: 背包【已有】的 raw_gold 要算进金源) 用户实测:
            // 23 锭 + 16 生金矿 = 39 ≥ 32, 却一直挖金矿 — 旧逻辑只数
            // GOLD_INGOT + GOLD_NUGGET, 完全漏掉 RAW_GOLD。只要 ingots+rawGold
            // 足够补满 _count, 就熔炼背包现成的 raw_gold。SmeltTarget 用满额
            // _count 当输出目标 → materialsNeeded = _count - ingots ≤ rawGold,
            // SmeltInFurnaceTask 材料足够, 不会派发收集任务 → 不会触发上面
            // 的跨维度老问题。
            int ingots = StorageHelper.countItemInInventoryReal(mod, Items.GOLD_INGOT);
            int rawGold = StorageHelper.countItemInInventoryReal(mod, Items.RAW_GOLD);
            if (ingots >= _count) {
                return null; // 真·金锭已够
            }
            // ① 背包已有 raw_gold → 熔炼 (1:1, 最划算)。
            // (2026-08-24 动态目标修复) 熔炼目标不能固定满额 _count: 否则
            // raw_gold 放进炉子后背包计数减少, DoSmeltInFurnaceTask 会误判
            // "缺材料" → getMaterialTask 派发挖 raw_gold (主世界资源) → 下界
            // 挖不到 → 触发回主世界造地狱门 (日志 11:31 Getting Materials →
            // Going to OVERWORLD → Building nether portal, TaskPingPong 卡死)。
            // 目标按"实际能补满的锭数"动态设置: 输出 = 现有锭 + 能熔炼的,
            // 材料 = 能熔炼的, 二者严格匹配, 熔炼期间不会再缺材料。
            // (2026-08-24 烧完所有生金矿) canSmelt 取全部 rawGold (不只补满
            // 缺口): 用户实测"炉里还有几个金子没烧完, 它检测到 32 锭就走"。
            // 生金矿就该全烧完, 多出的金锭留着 (交易/后续都用得上), 不浪费。
            if (rawGold > 0) {
                int canSmelt = rawGold;
                _smeltTask = new SmeltInFurnaceTask(new SmeltTarget(
                        new ItemTarget(Items.GOLD_INGOT, ingots + canSmelt),
                        new ItemTarget(Items.RAW_GOLD, canSmelt)));
                return _smeltTask;
            }
            // 还差多少锭
            int missingIngots = _count - ingots;
            // ② 金粒够合成 → 合成金锭 (9 粒 = 1 锭)
            // (2026-09-05 合成乒乓修复) 金粒计数必须含【合成格里的金粒】: 摆进
            // 3x3/2x2 的材料是已投入合成的, 只数背包会在合成期间看到"金粒骤减"
            // → 误判不足切去挖矿 → 打断合成 → 下 tick 又切回 → TaskPingPong
            // (2026-09-05 20:12 实锤 6x/5s, 用户观感"制作失败")。
            int nuggsTotal = countNuggetsIncludingCraftGrids(mod);
            if (nuggsTotal >= missingIngots * 9) {
                ItemTarget n = new ItemTarget(Items.GOLD_NUGGET);
                CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("gold_ingot", new ItemTarget[]{
                        n, n, n, n, n, n, n, n, n
                }, 1);
                // (2026-09-05 站死修复) RecipeTarget 数量必须用【绝对持有目标】_count,
                // 不能传差值 missingIngots: DoCraftInTableTask.containerSubTask 把
                // target 数量当"背包已有多少"判定 (realHave >= targetCount → 全部
                // 完成)。传差值时 bot 已有的锭数就 ≥ 差值 → 合成任务开局即
                // "DONE? Shouldn't be here" 空转, 父任务 isEqual 又不换子任务
                // → 工作台前永久呆住 (2026-09-05 20:12:16 实锤)。
                return new CraftInTableTask(new RecipeTarget(Items.GOLD_INGOT, _count, recipe));
            }
            // ③ 否则挖下界金矿出金粒 (石镐: 耐久 131 vs 木镐 59, 减少损坏
            // 中断; 下界金矿只掉金粒, 用什么镐都掉)
            int neededNuggets = _count * 9;
            return new MineAndCollectTask(new ItemTarget(Items.GOLD_NUGGET, neededNuggets), new Block[]{Blocks.NETHER_GOLD_ORE}, MiningRequirement.STONE);
        } else {
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-08-24 熔炉回收优先级) 熔炉回收未完成时不算结束 — 否则
        // TradeWithPiglinsTask 看到金锭 ≥ 32 就切去交易, 熔炉被丢在原地
        // (用户实测: 烧完 32 锭直接走, 熔炉没带走, 优先级被交易抢走)。
        if (_recoveringFurnacePos != null) {
            return false;
        }
        return super.isFinished(mod);
    }

    /**
     * (2026-09-05 合成乒乓修复) 金粒计数 = 真实背包(含光标) + 2x2 随身合成格
     * + 3x3 工作台合成格(界面开着才有效)。摆进合成格的材料是"已投入合成"的,
     * 不计入会让父任务在合成进行中误判"金粒不足"而切去挖矿、打断合成。
     */
    private static int countNuggetsIncludingCraftGrids(AltoClef mod) {
        int count = StorageHelper.countItemInInventoryReal(mod, Items.GOLD_NUGGET);
        count += StorageHelper.countItemsInCraftGrid(mod, Items.GOLD_NUGGET);
        if (StorageHelper.isBigCraftingOpen()) {
            for (Slot slot : CraftingTableSlot.INPUT_SLOTS) {
                ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                if (!stack.isEmpty() && stack.getItem() == Items.GOLD_NUGGET) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectGoldIngotTask && ((CollectGoldIngotTask) other)._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " gold.";
    }

    /**
     * (2026-08-24 熔炉回收) 找玩家附近最近的真实熔炉方块 (直接扫世界, 不依赖
     * BlockTracker)。范围 4 格, 覆盖熔炼时 DoStuffInContainerTask 放置熔炉的
     * 常见位置。返回 null 表示附近没有熔炉。
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
