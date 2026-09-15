package adris.altoclef.trackers.blacklisting;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
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

    // (2026-09-13 距离改善 RESET 加冷却, 用户"三层都修") 上一版只加"总失败上限",
    // 没解决【失败频率 > 靠近速度】的场景: 20260913-144922 实锤每 2.1s 一次
    // requestBlockUnreachable(DestroyBlockTask 2s 放弃), 而 bot 同时在走 → 每次
    // 都因"距离改善 > 1 格"RESET → 计数永远停在 Try 1/4, 目标无限重选
    // (干草 50 格外, 4 分钟零推进)。改: "距离改善"路径的 RESET 需间隔 ≥ 5s;
    // 工具升级路径不受限(那是真实挖掘能力提升)。
    private static final int RESET_COOLDOWN_TICKS = 100; // 5s

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
        double newDistance = getPos(item).squaredDistanceTo(mod.getPlayer().getPos());
        MiningRequirement newTool = StorageHelper.getCurrentMiningRequirement(mod);
        // (2026-09-15 最严阈值粘滞) 旧实现无条件 entry.allowed = 本次调用值 ——
        // "最后写入者赢", 任何一次宽松调用都会抹掉此前更严的判断。现场
        // 20260915-160447: MineAndCollectTask 挖不动挥手失败 requestBlockUnreachable(pos, 2)
        // → Try 1/2; 2s 后 DestroyBlockTask unreachable 路径默认阈值 4 把 allowed
        // 覆盖成 4 → failures(2) > 4 恒 false → 永远拉不黑 → 上层立刻重选同格
        // → 垫塔 handoff 死循环继续 20s (黑名单形同虚设)。
        // 改: allowed 只取历史最严 (min)。全仓核对过没有调用方靠"再调一次传更大值"
        // 放宽 —— MineAndCollect(2)/StoreInAnyContainer(2) 显式, 其余默认 4。
        if (entry.totalFailures == 1) {
            // 首次登记: 直接记基准。旧代码走下面的 RESET 分支, bestDistanceSq=∞
            // 必然判"更近了" → 每个新目标第一次失败都打一行无意义的 "Blacklist RESET"。
            entry.numberOfFailuresAllowed = numberOfFailuresAllowed;
            entry.bestDistanceSq = newDistance;
            entry.bestTool = newTool;
        } else {
            entry.numberOfFailuresAllowed = Math.min(entry.numberOfFailuresAllowed, numberOfFailuresAllowed);
            // For distance, add a slight threshold so it doesn't reset EVERY time we move a tiny bit closer.
            boolean allowReset = entry.totalFailures < TOTAL_FAILURE_CAP;
            // (2026-09-13) 距离改善路径加 5s 冷却: 失败比靠近更快时 RESET 会变成死循环。
            int nowTick = WorldHelper.getTicks();
            boolean toolUpgraded = newTool.ordinal() > entry.bestTool.ordinal();
            boolean closer = newDistance < entry.bestDistanceSq - 1
                    && nowTick - entry.lastResetTick >= RESET_COOLDOWN_TICKS;
            if (allowReset && (toolUpgraded || closer)) {
                if (toolUpgraded) entry.bestTool = newTool;
                if (newDistance < entry.bestDistanceSq) entry.bestDistanceSq = newDistance;
                entry.numberOfFailures = 0;
                entry.lastResetTick = nowTick;
                Debug.logMessage("Blacklist RESET: " + item.toString());
            }
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
        // (2026-09-13) 上次"距离改善 RESET"的时刻(游戏 tick), 用于 RESET 冷却。
        public int lastResetTick = -RESET_COOLDOWN_TICKS;
    }
}
