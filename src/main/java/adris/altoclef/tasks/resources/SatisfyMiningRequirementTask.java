package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Make sure we have a tool at or above a mining level.
 */
public class SatisfyMiningRequirementTask extends Task {

    private final MiningRequirement _requirement;

    public SatisfyMiningRequirementTask(MiningRequirement requirement) {
        _requirement = requirement;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // (2026-08-23 诊断) 需要镐却触发本任务 → 记录当前背包镐状态, 排查
        // "背包有铁/钻石镐却还要重做镐" (用户反馈: 它似乎把背包里的东西跳过)。
        int wooden = mod.getItemStorage().getItemCountInventoryOnly(Items.WOODEN_PICKAXE);
        int stone = mod.getItemStorage().getItemCountInventoryOnly(Items.STONE_PICKAXE);
        int iron = mod.getItemStorage().getItemCountInventoryOnly(Items.IRON_PICKAXE);
        int golden = mod.getItemStorage().getItemCountInventoryOnly(Items.GOLDEN_PICKAXE);
        int diamond = mod.getItemStorage().getItemCountInventoryOnly(Items.DIAMOND_PICKAXE);
        int netherite = mod.getItemStorage().getItemCountInventoryOnly(Items.NETHERITE_PICKAXE);
        String m = "(SatisfyMiningReq) " + _requirement + " not met. Pickaxes: wood=" + wooden
                + " stone=" + stone + " iron=" + iron + " golden=" + golden + " diamond=" + diamond + " netherite=" + netherite;
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_requirement) {
            case HAND:
                // Will never happen if you program this right
                break;
            case WOOD:
            case STONE:
                // (2026-09-10 钻石镐优先, 用户"有钻石应做钻石镐而非石镐/铁镐")
                // 钻石 ≥3 + 棒料够 → 直接钻石镐: 全档位天花板(耐久 1562), 且主线
                // 后期挖黑曜石本就需要钻石镐, 早做不亏。
                if (shouldPreferDiamondPickaxe(mod)) {
                    setDebugState("Preferring DIAMOND pickaxe (diamonds+wood at hand, skip lower tiers)");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                }
                // (2026-09-09 铁镐优先, 用户"背包里有生铁和木头, 有能力制作铁镐
                // 但没有触发") 下界没有圆石, STONE 档默认做石镐 → 挖竖井找圆石
                // → "Shaft done, no ores found" 死循环 (14:25-14:27 tasks log ×3 轮)。
                // 背包有 ≥3 铁锭(或 ≥3 生铁, iron_ingot 任务链自带熔炼)且木棍/
                // 木板/原木够做 2 根棒子时, 直接走铁镐任务链 — 铁镐同样满足
                // WOOD/STONE 档位 (miningRequirementMetInner 认高阶镐)。
                if (shouldPreferIronPickaxe(mod)) {
                    setDebugState("Preferring IRON pickaxe (iron+wood at hand, skip stone tier)");
                    return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
                }
                if (_requirement == MiningRequirement.WOOD) {
                    return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
            case IRON:
                if (shouldPreferDiamondPickaxe(mod)) {
                    setDebugState("Preferring DIAMOND pickaxe (diamonds+wood at hand, skip iron tier)");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
            case DIAMOND:
                return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
        }
        return null;
    }

    /**
     * (2026-09-09) 是否手头材料足以直接做铁镐, 应跳过石/木镐档位。
     * 铁源: 铁锭 ≥3 或 生铁 ≥3 (TaskCatalogue.smelt("iron_ingot", ...) 链
     * 自带"生铁→熔炉→铁锭", 燃料由 SmeltInFurnaceTask 自取)。
     * 棒源: 木棍 ≥2, 或 木板 ≥2, 或 原木 ≥1 (可合成木棍/木板)。
     *
     * (2026-09-09 口径修正, 用户目击"原木放进合成格→任务翻转收集金子") 计数
     * 必须用含 2x2 合成格口径: 配方链把原木/木棍/木板放进 2x2 格做中间合成时,
     * 真背包口径计数变 0 → 本判定翻 false → SatisfyMiningReq 重选石镐 → 石镐链
     * "Breaking the crafting grid" 清格打断中间合成 → 原木回背包 → 判定又 true
     * → 无限翻转 (16:36 场次实锤, ~6.3s/圈)。与项目约定"配方判定用含格口径"
     * 对齐 (StorageHelper.countItemInInventoryRealIncludingCraftGrid)。
     */
    private boolean shouldPreferIronPickaxe(AltoClef mod) {
        // (2026-09-10 口径升级) 计数含打开容器自有槽位: 材料被搬进工作台 3x3 的
        // 瞬间不再被判"缺失" → 档位选择不翻转 (横跳根因之一, 18:28 场次)。
        int ingots = StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.IRON_INGOT);
        int rawIron = StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.RAW_IRON);
        if (ingots < 3 && rawIron < 3) return false;
        return hasStickMaterial(mod);
    }

    /**
     * (2026-09-10, 用户"有钻石应做钻石镐而非石镐/铁镐") 钻石 ≥3 且棒料够 →
     * 直接走钻石镐任务链 (WOOD/STONE/IRON 档统一适用)。钻石镐是全档位天花板
     * (挖速最快/耐久 1562), 主线后期挖黑曜石本来就必须钻石镐 — 有钻早做不亏,
     * 避免铁/石镐损耗后反复重做。棒料判定与铁镐优先共用, 含 2x2 合成格口径
     * (防配方中间态计数翻假 → 档位选择抖动, 同 shouldPreferIronPickaxe 教训)。
     */
    private boolean shouldPreferDiamondPickaxe(AltoClef mod) {
        // (2026-09-10 口径升级) 含打开容器自有槽位: 钻石被搬进工作台 3x3 等待合成
        // 的瞬间, 阈值判定不再骤降 → 不翻回铁镐档 (横跳根因之一)。
        int diamonds = StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.DIAMOND);
        return diamonds >= 3 && hasStickMaterial(mod);
    }

    /** 棒料: 木棍 ≥2, 或 木板 ≥2, 或 任一原木 ≥1 (均可合成出 2 根棒子)。 */
    private boolean hasStickMaterial(AltoClef mod) {
        if (StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.STICK) >= 2) return true;
        if (StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, ItemHelper.PLANKS) >= 2) return true;
        for (Item log : ItemHelper.LOG) {
            if (StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, log) >= 1) return true;
        }
        return false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SatisfyMiningRequirementTask task) {
            return task._requirement == _requirement;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Satisfy Mining Req: " + _requirement;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.miningRequirementMetInventory(mod, _requirement);
    }
}
