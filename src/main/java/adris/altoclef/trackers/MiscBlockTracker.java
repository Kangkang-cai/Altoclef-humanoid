package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sometimes we want to track specific block related things, like the last nether portal we used.
 * I don't want to pollute other trackers to add these.
 */
public class MiscBlockTracker {

    private final AltoClef _mod;

    private final Map<Dimension, BlockPos> _lastNetherPortalsUsed = new HashMap<>();

    // Make sure we only care about the nether portal we ENTERED through
    private Dimension _lastDimension;
    private boolean _newDimensionTriggered;

    public MiscBlockTracker(AltoClef mod) {
        _mod = mod;
    }

    public void tick() {
        if (WorldHelper.getCurrentDimension() != _lastDimension) {
            _lastDimension = WorldHelper.getCurrentDimension();
            _newDimensionTriggered = true;
        }

        if (AltoClef.inGame() && _newDimensionTriggered) {
            for (BlockPos check : WorldHelper.scanRegion(_mod, _mod.getPlayer().getBlockPos().add(-1, -1, -1), _mod.getPlayer().getBlockPos().add(1, 1, 1))) {
                Block currentBlock = _mod.getWorld().getBlockState(check).getBlock();
                if (currentBlock == Blocks.NETHER_PORTAL) {
                    // Make sure we get the lowest nether portal, as we can only really enter from the bottom.
                    while (check.getY() > 0) {
                        if (_mod.getWorld().getBlockState(check.down()).getBlock() == Blocks.NETHER_PORTAL) {
                            check = check.down();
                        } else {
                            break;
                        }
                    }
                    BlockPos below = check.down();
                    if (WorldHelper.isSolid(_mod, below)) {
                        _lastNetherPortalsUsed.put(WorldHelper.getCurrentDimension(), check);
                        // (2026-09-05 传送门记忆跨会话持久化, 用户"重启游戏后回家的门
                        // 就忘了, 下界再弄黑曜石太贵") 亲手穿过的门落盘, 重启游戏不丢。
                        PortalMemoryStore.recordUsed(_mod, WorldHelper.getCurrentDimension(), check);
                        _newDimensionTriggered = false;
                    }
                    break;
                }
            }
        }
    }

    public void reset() {
        _lastNetherPortalsUsed.clear();
    }

    /**
     * (2026-10-11 熄灭门到场重燃) 与 {@link #getLastUsedNetherPortal} 同一记忆源
     * （含跨会话回填），但"紫块不在"不再判死：熄灭的门通常黑曜石框完好，到场
     * 打火石重燃比重造便宜。只有 chunk 已加载且【连门框都没了】（向下 6 格内无
     * 黑曜石）才真正废弃（清内存+落盘，同旧口径）。供 DefaultGoToDimensionTask
     * 的重燃分支在旧方法的"紫块缺失即清除"之前拦截使用。
     */
    public Optional<BlockPos> getLastUsedNetherPortalSiteIncludingExtinguished(Dimension dimension) {
        if (!_lastNetherPortalsUsed.containsKey(dimension)) {
            PortalMemoryStore.loadLatestUsed(_mod, dimension)
                    .ifPresent(pos -> _lastNetherPortalsUsed.put(dimension, pos));
        }
        BlockPos portalPos = _lastNetherPortalsUsed.get(dimension);
        if (portalPos == null) return Optional.empty();
        if (_mod.getChunkTracker().isChunkLoaded(portalPos) && !netherPortalFrameIntact(portalPos)) {
            _lastNetherPortalsUsed.remove(dimension);
            PortalMemoryStore.removeUsed(_mod, dimension, portalPos);
            adris.altoclef.Debug.logToFile("altoclef_diag.log", "[PortalRelight]\tsite discarded (frame gone) @"
                    + portalPos.toShortString() + " dim=" + dimension);
            return Optional.empty();
        }
        return Optional.ofNullable(portalPos);
    }

    /** 站点向下 6 格内有黑曜石 = 门框地板仍在，值得到场重燃。 */
    private boolean netherPortalFrameIntact(BlockPos portalPos) {
        BlockPos cur = portalPos;
        for (int i = 0; i < 6 && cur.getY() > 0; i++) {
            if (_mod.getWorld().getBlockState(cur).getBlock() == Blocks.OBSIDIAN) return true;
            cur = cur.down();
        }
        return false;
    }

    public Optional<BlockPos> getLastUsedNetherPortal(Dimension dimension) {
        // (2026-09-05 跨会话回填) 游戏重启后内存为空, 从 altoclef_portals.log
        // 回填该维度最近一次亲手穿过的门。人类穿过一扇门就不会忘。
        if (!_lastNetherPortalsUsed.containsKey(dimension)) {
            PortalMemoryStore.loadLatestUsed(_mod, dimension)
                    .ifPresent(pos -> {
                        _lastNetherPortalsUsed.put(dimension, pos);
                        // (2026-09-10) 回填来源显式落日志, 便于排查"导航目标来自旧世界
                        // 脏记忆"类事故 (key 已改存档目录名隔离, 此日志用于核对)。
                        adris.altoclef.Debug.logMessage("[PortalMemory] backfilled used portal for "
                                + dimension + " @ " + pos.toShortString());
                    });
        }
        if (_lastNetherPortalsUsed.containsKey(dimension)) {
            BlockPos portalPos = _lastNetherPortalsUsed.get(dimension);
            // Check whether our nether portal pos is invalid.
            if (_mod.getChunkTracker().isChunkLoaded(portalPos)) {
                if (!_mod.getBlockTracker().blockIsValid(portalPos, Blocks.NETHER_PORTAL)) {
                    _lastNetherPortalsUsed.remove(dimension);
                    // 门已被拆/失效: 同步清掉落盘记录, 防止重启后又回填死位置。
                    PortalMemoryStore.removeUsed(_mod, dimension, portalPos);
                    return Optional.empty();
                }
            }
            return Optional.ofNullable(portalPos);
        }
        return Optional.empty();
    }
}
