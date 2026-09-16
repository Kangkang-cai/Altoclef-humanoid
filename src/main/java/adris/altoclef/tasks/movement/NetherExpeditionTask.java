package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;

/**
 * (2026-09-16 灵魂沙谷远征, 用户"灵魂沙地貌金子少, 应该远征") 复用基类
 * {@link BearingExpeditionTask} 的锁定方位角远征机制, 用在下界挖金场景:
 *
 * - 上层 (MineAndCollectTask) 挖下界金/石英/镶金黑石时若身处灵魂沙谷 → 派本任务
 *   定向远征, 直到走出山谷 (谷内灵魂沙/灰烬覆盖, 金矿暴露少, 原地挖隧道零产出)。
 * - 拒绝条件: 目标列脚下附近有【岩浆】一律拒绝; 未加载 chunk 保守拒绝。
 * - 结束判定: 当前群系不再是灵魂沙谷 且 距锚点水平 ≥ MIN_ESCAPE_RADIUS (防群系
 *   边界抖动来回横跳) → isFinished, 上层重新规划即恢复正常挖矿探索。
 *   硬超时 300s 兜底: 山谷过大/被岩浆围死时放弃交回上层重新规划。
 *
 * 诊断标签: [NETHER-EXPEDITION]。
 */
public class NetherExpeditionTask extends BearingExpeditionTask {

    // 出谷后还要再走这么远才算站稳, 防群系边界抖动来回横跳。
    private static final double MIN_ESCAPE_RADIUS = 48;

    public NetherExpeditionTask() {
        super(300); // 硬超时兜底
    }

    @Override
    protected String diagTag() {
        return "[NETHER-EXPEDITION]";
    }

    /** 目标列脚下 (y-3 ~ y+2) 是否岩浆 (未加载 chunk 保守视为有)。 */
    @Override
    protected boolean rejectTarget(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return true;
        if (!mod.getChunkTracker().isChunkLoaded(target)) return true;
        for (int dy = -3; dy <= 2; dy++) {
            if (mod.getWorld().getBlockState(target.add(0, dy, 0)).isOf(Blocks.LAVA)) return true;
        }
        return false;
    }

    @Override
    protected boolean diagSwitchState(AltoClef mod) {
        return isInSoulSandValley(mod);
    }

    @Override
    protected String diagSwitchLabel() {
        return "inValley";
    }

    @Override
    protected boolean naturalFinish(AltoClef mod) {
        boolean inValley = isInSoulSandValley(mod);
        BlockPos anchor = getAnchor();
        double radius = anchor == null ? 0
                : Math.hypot(mod.getPlayer().getBlockPos().getX() - anchor.getX(),
                        mod.getPlayer().getBlockPos().getZ() - anchor.getZ());
        if (!inValley && radius >= MIN_ESCAPE_RADIUS) {
            Debug.logMessage(String.format(java.util.Locale.ROOT,
                    "[NETHER-EXPEDITION] escaped soul sand valley (radius=%.0f >= %.0f), resuming mining.",
                    radius, MIN_ESCAPE_RADIUS));
            return true;
        }
        return false;
    }

    @Override
    protected String timeoutDetail(AltoClef mod) {
        return "still in valley: " + isInSoulSandValley(mod);
    }

    private static boolean isInSoulSandValley(AltoClef mod) {
        try {
            return mod.getWorld().getBiome(mod.getPlayer().getBlockPos())
                    .matchesKey(BiomeKeys.SOUL_SAND_VALLEY);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected String toDebugString() {
        return "Nether soul sand valley expedition";
    }
}
