package adris.altoclef.util;

import adris.altoclef.Debug;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public enum MiningRequirement implements Comparable<MiningRequirement> {
    HAND(Items.AIR), WOOD(Items.WOODEN_PICKAXE), STONE(Items.STONE_PICKAXE), IRON(Items.IRON_PICKAXE), DIAMOND(Items.DIAMOND_PICKAXE);

    private final Item _minPickaxe;

    MiningRequirement(Item minPickaxe) {
        _minPickaxe = minPickaxe;
    }

    // (2026-09-14 刷屏修复) 本方法在 baritone A* 热路径上 —— AltoClef:180 注册的
    // avoidBlockBreak 谓词 → WorldHelper.shouldAvoidUnminable 会对 A* 展开到的
    // 【每个候选方块】调用一次。现场 20260914-113531: 一趟搜索 8514176 movements
    // considered / Open set size 44941 (latest.log:44886), 警告被调用数万次,
    // 在 pool-2-thread-3 (baritone 路径线程) 刷屏 23s+, 每次 STDOUT + CHAT 双份,
    // 把真正有用的日志全部冲掉。
    //
    // 修法: ① 结果缓存 (方块属性静态不变, 返回值与逐次计算完全一致, 语义零变更)
    //       ② 警告按方块去重 (同一方块只喊一次)
    // 线程安全: A* 跑在 baritone 路径线程池, 必须用并发容器 —— 用 HashMap 会
    // 在并发 put 时结构损坏 (顺带把 A* 弄崩)。
    private static final Map<Block, MiningRequirement> _requirementCache = new ConcurrentHashMap<>();
    private static final Set<Block> _warnedBlocks = ConcurrentHashMap.newKeySet();

    public static MiningRequirement getMinimumRequirementForBlock(Block block) {
        MiningRequirement cached = _requirementCache.get(block);
        if (cached != null) {
            return cached;
        }
        MiningRequirement result = computeMinimumRequirement(block);
        _requirementCache.put(block, result);
        return result;
    }

    private static MiningRequirement computeMinimumRequirement(Block block) {
        if (block.getDefaultState().isToolRequired()) {
            for (MiningRequirement req : MiningRequirement.values()) {
                if (req == MiningRequirement.HAND) continue;
                Item pick = req.getMinimumPickaxe();
                if (pick.isSuitableFor(block.getDefaultState())) {
                    return req;
                }
            }
            // (2026-09-14) 走到这里 = 方块"需要工具"但本枚举只有镐子一个维度 ——
            // 铲类方块 (snow_block 是现场常客) 必然命中, 不是"哪里出错了", 是本
            // 枚举的已知覆盖盲区。保留警告供真异常排查, 但只打一次/方块。
            if (_warnedBlocks.add(block)) {
                Debug.logWarning("Failed to find ANY effective tool against: " + block + ". I assume netherite is not required anywhere, so something else probably went wrong.");
            }
            return MiningRequirement.DIAMOND;
        }
        return MiningRequirement.HAND;
    }

    public Item getMinimumPickaxe() {
        return _minPickaxe;
    }

}
