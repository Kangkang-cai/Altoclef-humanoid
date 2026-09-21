package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * (2026-09-19 21:20 用户"128 方块和搭路和造墙等方块需求不统一, 且有缺失:
 * 黑石/深板岩/玄武岩/深处的圆石/哭泣黑曜石等") 【搭路/垫脚/造墙消耗方块】
 * 的统一口径中心。此前散落 6+ 处硬编码清单互不一致:
 *  - FortressExploreTask.countPlaceableScaffoldBlocks: throwaway 里所有 BlockItem (最宽)
 *  - FortressExploreTask / CollectBlazeRodsTask 补货: 只挖 NETHERRACK
 *  - MineAndCollectTask.isScaffoldGroundTarget: 只认 Blocks.NETHERRACK
 *    (近场扩展/掉落物半径/悬空罚等优化对黑石玄武岩全失效)
 *  - PillarJumpHelper.SAFE_MATERIALS: 缺 BLACKSTONE/BASALT/CRYING_OBSIDIAN
 *  - ShelterAndRecoverTask / TowerEscapeTask / PlaceBlockTask: 各自一份小清单
 * 统一后: 计数/放置用 {@link #SAFE_MATERIALS} (宽, 背包有什么用什么),
 * 补货挖掘用 {@link #preferredRestockItem} (窄, 按维度定点挖最常见的)。
 * 注意: 重力方块(沙/沙砾)一律不进 —— 水平搭路悬空会掉 (PlaceBlockTask 0826 结论);
 * 镀金黑石挖了掉金粒不留方块, 只在放置口径外, 挖掘目标也不选。
 */
public final class ScaffoldBlocks {

    /**
     * 全部可作为搭路/垫脚/造墙消耗的非重力方块物品。
     * 下界: 地狱岩/黑石/玄武岩/灵魂沙/灵魂土; 主世界: 圆石/深板岩圆石/深板岩/
     * 凝灰岩/石头系/泥土; 两界通用: 哭泣黑曜石 (用户点名, 废弃传送门拆的);
     * 末地: 末地石 (2026-09-21 1849, 用户"他有可使用的方块: 深板岩, 统一清单
     * 没接上"后一并收编 —— 末地打龙垫柱/搭桥唯一可挖的大量方块就是末地石,
     * 旧注释"末地石不在清单里"是已知缺口, 末地场景 (GetToOuterEndIslandsTask /
     * WaitForDragonAndPearlTask) 现已存在)。
     */
    public static final Item[] SAFE_MATERIALS = new Item[]{
            Items.NETHERRACK, Items.BLACKSTONE, Items.BASALT,
            Items.SOUL_SAND, Items.SOUL_SOIL,
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DEEPSLATE, Items.TUFF,
            Items.STONE, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
            Items.DIRT,
            Items.CRYING_OBSIDIAN,
            Items.END_STONE
    };

    private static final Set<Item> _set = new HashSet<>(Arrays.asList(SAFE_MATERIALS));

    private ScaffoldBlocks() {
    }

    public static boolean isScaffoldBlock(Item item) {
        return item != null && _set.contains(item);
    }

    /** 背包(真实口径: 含副手/光标/护甲)里的搭路方块总数。 */
    public static int countInInventory(AltoClef mod) {
        int n = 0;
        for (Item item : SAFE_MATERIALS) {
            n += StorageHelper.countItemInInventoryReal(mod, item);
        }
        return n;
    }

    /**
     * 补货定点挖掘目标: 只选"环境中大量存在 + 木镐可挖 + 挖了直接掉方块(无需
     * 熔炼) + 不毁有价值东西"的类型, 按当前维度取第一优先。哭泣黑曜石/深板岩
     * 本体/石头本体/泥土只在放置口径, 绝不作为挖掘目标。
     */
    public static Item preferredRestockItem(AltoClef mod) {
        // 下界判定: 用世界 registry key, 1.18.2 yarn 映射下最稳。
        boolean nether = mod.getPlayer().world.getRegistryKey() == World.NETHER;
        boolean theEnd = mod.getPlayer().world.getRegistryKey() == World.END;
        if (theEnd) {
            // 末地: 末地石遍地, 木镐可挖直掉本体 —— 唯一的合理补货目标
            // (2026-09-21 1849 收编, 旧"圆石兜底"在末地无来源, 纯死分支)。
            return Items.END_STONE;
        }
        if (nether) {
            // 下界最常见优先: 地狱岩(堡垒区遍地) → 黑石(堡垒内部大量) → 玄武岩(三角洲)。
            return firstWithStock(mod, Items.NETHERRACK, Items.BLACKSTONE, Items.BASALT);
        }
        // 主世界: 圆石 → 深板岩圆石 (深处 Y<0 挖深板岩掉的就是它)。
        return firstWithStock(mod, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
    }

    /** 偏好序列里选"背包里已有一些"的类型优先续用 (避免反复横跳换材料); 都没有就取第一个。 */
    private static Item firstWithStock(AltoClef mod, Item... prefs) {
        for (Item item : prefs) {
            if (StorageHelper.countItemInInventoryReal(mod, item) > 0) return item;
        }
        return prefs[0];
    }
}
