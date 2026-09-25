package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-09-25 下界挖金无进展远征, 用户"bot找金子长时间没进展我也希望触发远征模式")
 * 复用基类 {@link BearingExpeditionTask} 的锁定方位角远征机制。与
 * {@link NetherExpeditionTask} (灵魂沙谷, 按群系触发) 互补: 本任务按
 * 【产出无进展】触发 —— 上层 MineAndCollectTask 挖下界金矿时若背包金粒
 * 长时间无增长且已感知不到金矿 (即将开新隧道), 派本任务定向远征换地方,
 * 而不是在贫矿区原地挖隧道零产出。
 *
 * - 触发/结束由上层与 {@link #naturalFinish} 协同:
 *   结束 = 背包金粒数较远征开始时上涨 (找到金了, 上层重新规划即恢复挖矿)
 *   或 硬超时 300s 兜底。超时后上层重新评估, 若仍无进展会再派新实例 ——
 *   方位角 static 跨实例续用, 等效"朝同一方向一直走, 直到找到金"。
 * - 拒绝条件: 目标列脚下附近有【岩浆】一律拒绝; 未加载 chunk 保守拒绝
 *   (与 NetherExpeditionTask 同口径)。
 *
 * 诊断标签: [GOLD-EXPEDITION]。
 */
public class NetherGoldExpeditionTask extends BearingExpeditionTask {

    public NetherGoldExpeditionTask() {
        super(300); // 硬超时兜底
    }

    @Override
    protected String diagTag() {
        return "[GOLD-EXPEDITION]";
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

    /** 结束判定: 金粒数较开局上涨 = 找到金, 交回上层恢复挖矿。 */
    @Override
    protected boolean naturalFinish(AltoClef mod) {
        if (_baselineNuggets < 0) return false;
        int now = countGoldNuggets(mod);
        if (now > _baselineNuggets) {
            Debug.logMessage(String.format(java.util.Locale.ROOT,
                    "[GOLD-EXPEDITION] gold progress (%d > %d nuggets), resuming mining.",
                    now, _baselineNuggets));
            return true;
        }
        return false;
    }

    @Override
    protected String timeoutDetail(AltoClef mod) {
        return "nuggets " + countGoldNuggets(mod) + " (baseline " + _baselineNuggets + ")";
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        _baselineNuggets = countGoldNuggets(mod);
    }

    private int _baselineNuggets = -1;

    /** 金粒进展度量: 真实背包计数 (下界挖金只掉金粒; 不含合成格 — 远征期间不合成)。 */
    public static int countGoldNuggets(AltoClef mod) {
        return StorageHelper.countItemInInventoryReal(mod, Items.GOLD_NUGGET);
    }

    @Override
    protected String toDebugString() {
        return "Nether gold expedition (no mining progress)";
    }
}
