package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.slot.EnsureFreePlayerCraftingGridTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.*;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.Arrays;

public class CollectPlanksTask extends ResourceTask {

    private final Item[] _planks;
    private final Item[] _logs;
    private final int _targetCount;
    private boolean _logsInNether;
    // (2026-08-31 采集粘滞) 一旦进入"砍原木"子任务就保持到本轮采集完成(或工具需求),
    // 不再因为"已有原木够转木板"就中途切回合成 —— 否则砍一两根原木就被合木板打断,
    // 采集不连贯(日志 18:20:51 砍 logs 被 Craft 2x2 planks 打断,/镐/斧类工具需求仍允许打断)。
    private boolean _miningCommitted = false;
    // 持久化的砍木子任务实例: 提交后持续复用同一实例, 避免每 tick 重建
    // (任务系统频繁打断重开是横跳常见根因)。
    private ResourceTask _mineTaskCache = null;
    // 记录并核对 _mineTaskCache 的目标 Item (isEqual 复用判断)。
    private Item[] _mineTaskLogs = null;

    public CollectPlanksTask(Item[] planks, Item[] logs, int count, boolean logsInNether) {
        super(new ItemTarget(planks, count));
        _planks = planks;
        _logs = logs;
        _targetCount = count;
        _logsInNether = logsInNether;
    }

    public CollectPlanksTask(int count) {
        this(ItemHelper.PLANKS, ItemHelper.LOG, count, false);
    }

    public CollectPlanksTask(Item plank, Item log, int count) {
        this(new Item[]{plank}, new Item[]{log}, count, false);
    }

    public CollectPlanksTask(Item plank, int count) {
        this(plank, ItemHelper.planksToLog(plank), count);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _miningCommitted = false;
        _mineTaskCache = null;
        _mineTaskLogs = null;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // (2026-09-09 收尾防残留) 木板目标已达成但 2x2 格还有残留(craftAll 批量
        // 合成收第一批即达标: 输入格滞留原木、输出格滞留木板, 17:29:53 实锤
        // 原木x30 + oak_planksx4) → 先收尾清格再结束, 不继续合成。否则残留死格
        // 阻塞下一个配方填充(背包满时 crafting_table 填充死等 26s)。
        if (super.isFinished(mod)) {
            setDebugState("Planks target met, cleaning 2x2 grid leftovers");
            return new EnsureFreePlayerCraftingGridTask();
        }

        // (2026-08-31 采集粘滞) 正在砍原木子任务进行中: 保持采集, 不因
        // "已有原木够转木板"切回合成 (不合而打断采集节奏)。
        if (_miningCommitted) {
            if (_mineTaskCache != null) {
                if (!_mineTaskCache.isFinished(mod)) {
                    return _mineTaskCache;
                }
                // 本轮砍伐目标已达成 → 解除提交, 允许切去合成。
                _miningCommitted = false;
                _mineTaskCache = null;
                _mineTaskLogs = null;
            }
        }

        // Craft when we can
        int totalInventoryPlankCount = mod.getItemStorage().getItemCount(_planks);
        int potentialPlanks = totalInventoryPlankCount + mod.getItemStorage().getItemCount(_logs) * 4;
        if (potentialPlanks >= _targetCount) {
            for (Item logCheck : _logs) {
                int count = mod.getItemStorage().getItemCount(logCheck);
                if (count > 0) {
                    Item plankCheck = ItemHelper.logToPlanks(logCheck);
                    if (plankCheck == null) {
                        Debug.logError("Invalid/Un-convertable log: " + logCheck + " (failed to find corresponding plank)");
                    }
                    int plankCount = mod.getItemStorage().getItemCount(plankCheck);
                    int otherPlankCount = totalInventoryPlankCount - plankCount;
                    // (2026-09-01 过度合成修复, 用户"把所有木头一次做成木板") 这里只做
                    // “还缺的木板数”= 目标 - 已有其它木板, 并硬性夹在 [1, 目标] 内:
                    // ① 下限 ≥1, 防止 otherPlankCount 恰好等于/超过目标时算成 0 或负数
                    //   (0 → CraftGeneric 把“需求”当 0, 判定材料全齐 → 把全部原木塞进
                    //   配方一次转光, 日志实锤 Craft 2x2 _item=birch_planks x 0)。
                    // ② 上限受 _targetCount 封顶, 单种原木即使一大把(如 8+ 根)也只转
                    //   需要的当量, 不贪多一次做光所有木头。
                    int deficitPlanks = _targetCount - otherPlankCount;
                    int targetTotalPlanks = Math.max(1, Math.min(_targetCount, Math.min(count*4 + plankCount, deficitPlanks)));
                    Debug.logToFile("altoclef_trace.log",
                            "[CRAFT-LIMIT]\tplanks target=" + _targetCount
                                    + " have(plank=" + plankCount + " other=" + otherPlankCount
                                    + " log=" + count + ")" +
                                    " -> craft " + targetTotalPlanks
                                    + " (禁止滑坡: 只做缺口, 一次转 1 原木=4 木板当量)");
                    setDebugState("We have " + logCheck + ", crafting " + targetTotalPlanks + " planks.");
                    return new CraftInInventoryTask(new RecipeTarget(plankCheck, targetTotalPlanks, generatePlankRecipe(_logs)));
                }
            }
        }

        // Collect planks and logs
        ArrayList<ItemTarget> blocksTomine = new ArrayList<>(2);
        blocksTomine.add(new ItemTarget(_logs));
        // Ignore planks if we're told to.
        if (!mod.getBehaviour().exclusivelyMineLogs()) {
            // TODO: Add planks back in, but with a heuristic check (so we don't go for abandoned mineshafts)
            //blocksTomine.add(new ItemTarget(ItemUtil.PLANKS));
        }

        // 复用同一砍木子任务实例, 避免每 tick 重建.
        if (_mineTaskCache == null || _mineTaskLogs == null
                || !Arrays.equals(_mineTaskLogs, _logs)) {
            _mineTaskCache = new MineAndCollectTask(blocksTomine.toArray(ItemTarget[]::new), MiningRequirement.HAND);
            _mineTaskLogs = _logs;
            if (_logsInNether) {
                _mineTaskCache.forceDimension(Dimension.NETHER);
            }
        }
        // 一旦派发砍木子任务 → 提交粘滞: 本 tick 下一轮起持续采集, 不中途切合成。
        _miningCommitted = true;
        return _mineTaskCache;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (!super.isFinished(mod)) return false;
        // (2026-09-09 收尾防残留) 木板达标不算完: 2x2 格残留(craftAll 批量合成
        // 收第一批即达标: 输入格原木 + 输出格木板)必须先清, 否则阻塞下一个
        // 配方填充 (17:29:53-17:30:20 crafting_table 死等实锤)。
        if (!StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT).isEmpty()) return false;
        for (Slot s : PlayerSlot.CRAFT_INPUT_SLOTS) {
            if (!StorageHelper.getItemStackInSlot(s).isEmpty()) return false;
        }
        return true;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectPlanksTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting " + _targetCount + " planks " + Arrays.toString(_planks);
    }

    public CollectPlanksTask logsInNether() {
        _logsInNether = true;
        return this;
    }

    private static CraftingRecipe generatePlankRecipe(Item[] logs) {
        return CraftingRecipe.newShapedRecipe(
                "planks",
                new Item[][]{
                        logs, null,
                        null, null
                },
                4
        );
    }
}
