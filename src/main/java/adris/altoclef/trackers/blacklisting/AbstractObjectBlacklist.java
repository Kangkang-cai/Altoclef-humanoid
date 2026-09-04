package adris.altoclef.trackers.blacklisting;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;

/**
 * Sometimes we will try to access something and fail TOO many times.
 * <p>
 * This lets us know that a block is unreachable, and will ignore it from the search intelligently.
 */
public abstract class AbstractObjectBlacklist<T> {

    private final HashMap<T, BlacklistEntry> _entries = new HashMap<>();

    // (2026-09-04 绝对上限, 用户拍板) 距离每改善 1 格就 RESET 计数的设计, 在
    // "慢慢逼近够不着的目标" 场景会 RESET 死循环, 4 次上限永远打不满
    // (实测 2026-09-04: 深洞 DestroyBlock Try2→RESET→Try1 循环到手动停止)。
    // 累计总失败达上限后不再因距离改善 RESET (工具升级 RESET 保留), 计数
    // 只增不减, unreachable 必然到达。
    private static final int TOTAL_FAILURE_CAP = 12;

    public void blackListItem(AltoClef mod, T item, int numberOfFailuresAllowed) {
        if (!_entries.containsKey(item)) {
            BlacklistEntry entry = new BlacklistEntry();
            entry.numberOfFailuresAllowed = numberOfFailuresAllowed;
            entry.numberOfFailures = 0;
            entry.bestDistanceSq = Double.POSITIVE_INFINITY;
            entry.bestTool = MiningRequirement.HAND;
            _entries.put(item, entry);
        }
        BlacklistEntry entry = _entries.get(item);
        entry.totalFailures++;
        entry.numberOfFailuresAllowed = numberOfFailuresAllowed;
        double newDistance = getPos(item).squaredDistanceTo(mod.getPlayer().getPos());
        MiningRequirement newTool = StorageHelper.getCurrentMiningRequirement(mod);
        // For distance, add a slight threshold so it doesn't reset EVERY time we move a tiny bit closer.
        boolean allowReset = entry.totalFailures < TOTAL_FAILURE_CAP;
        if (allowReset && (newTool.ordinal() > entry.bestTool.ordinal() || (newDistance < entry.bestDistanceSq - 1))) {
            if (newTool.ordinal() > entry.bestTool.ordinal()) entry.bestTool = newTool;
            if (newDistance < entry.bestDistanceSq) entry.bestDistanceSq = newDistance;
            entry.numberOfFailures = 0;
            Debug.logMessage("Blacklist RESET: " + item.toString());
        }
        entry.numberOfFailures++;
        Debug.logMessage("Blacklist: " + item.toString() + ": Try " + entry.numberOfFailures + " / " + entry.numberOfFailuresAllowed
                + (entry.totalFailures >= TOTAL_FAILURE_CAP ? " (total " + entry.totalFailures + ", reset disabled)" : ""));
    }

    protected abstract Vec3d getPos(T item);

    public boolean unreachable(T item) {
        if (_entries.containsKey(item)) {
            BlacklistEntry entry = _entries.get(item);
            return entry.numberOfFailures > entry.numberOfFailuresAllowed;
        }
        return false;
    }

    public void clear() {
        _entries.clear();
    }

    // Key: BlockPos
    private static class BlacklistEntry {
        public int numberOfFailuresAllowed;
        public int numberOfFailures;
        public int totalFailures;
        public double bestDistanceSq;
        public MiningRequirement bestTool;
    }
}
