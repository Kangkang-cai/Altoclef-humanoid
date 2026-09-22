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

    // (2026-09-22 unreachable 类失败禁用距离 RESET) "距离改善 RESET" 的语义前提是
    // "走近一点就可能够得着"。对【寻路/够不着】类失败 (requestBlockUnreachable /
    // requestEntityUnreachable) 该前提不成立: 目标在覆盖水体/对岸/封闭腔体内,
    // 走近 1 格不改变可达性, RESET 只是清零计数 → 4 次上限永远打不满 → 同一目标
    // 无限重选 (20260922 另机日志实锤: latest.log 00:11:24-00:12:06 同一目标
    // 24,10,27 连续 5 次 giving up, 每次都因 wander 靠近被 RESET; raw_iron 掉落物
    // 在覆盖水体内 00:02:55-00:08:00 停滞 5 分钟同理)。改: 此类失败走
    // allowDistanceReset=false 重载, 逐 entry 粘滞 (最严赢, 与 09-15 allowed 取 min
    // 同款口径); 工具升级 RESET 不受影响 (那是真实挖掘能力提升)。
    public void blackListItem(AltoClef mod, T item, int numberOfFailuresAllowed) {
        blackListItem(mod, item, numberOfFailuresAllowed, true);
    }

    public void blackListItem(AltoClef mod, T item, int numberOfFailuresAllowed, boolean allowDistanceReset) {
        if (!_entries.containsKey(item)) {
            BlacklistEntry entry = new BlacklistEntry();
            entry.numberOfFailuresAllowed = numberOfFailuresAllowed;
            entry.numberOfFailures = 0;
            entry.bestDistanceSq = Double.POSITIVE_INFINITY;
            entry.bestTool = MiningRequirement.HAND;
            _entries.put(item, entry);
        }
        BlacklistEntry entry = _entries.get(item);
        // (2026-09-22) 距离 RESET 许可逐 entry 粘滞: 一旦按 unreachable 语义登记
        // (false), 后续任何调用都不再放宽 —— 最严赢, 防止"最后写入者赢"回退。
        if (!allowDistanceReset) entry.distanceResetAllowed = false;
        entry.totalFailures++;
        int totalFailuresBefore = entry.totalFailures - 1; // (2026-09-17 录制) 0 = 首次登记
        boolean resetHappened = false;                     // (2026-09-17 录制) 本次是否走了"距离改善/工具升级 RESET"
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
            boolean closer = entry.distanceResetAllowed
                    && newDistance < entry.bestDistanceSq - 1
                    && nowTick - entry.lastResetTick >= RESET_COOLDOWN_TICKS;
            if (allowReset && (toolUpgraded || closer)) {
                if (toolUpgraded) entry.bestTool = newTool;
                if (newDistance < entry.bestDistanceSq) entry.bestDistanceSq = newDistance;
                entry.numberOfFailures = 0;
                entry.lastResetTick = nowTick;
                resetHappened = true;
                Debug.logMessage("Blacklist RESET: " + item.toString());
            }
        }
        entry.numberOfFailures++;
        Debug.logMessage("Blacklist: " + item.toString() + ": Try " + entry.numberOfFailures + " / " + entry.numberOfFailuresAllowed
                + (entry.totalFailures >= TOTAL_FAILURE_CAP ? " (total " + entry.totalFailures + ", reset disabled)" : ""));
        emitRecordEvent(item, entry, totalFailuresBefore, resetHappened);
    }

    /**
     * (2026-09-17 决策知识入流) 把本次黑名单判定作为一条稀疏事件交给录制器。
     *
     * <p>这是方块黑名单 ({@link WorldLocateBlacklist}) 与实体黑名单
     * ({@link EntityLocateBlacklist}) 的<b>唯一</b>写入汇聚点 —— 插一处, 两类
     * "目标为什么被放弃"的决策知识全进数据流。训练时模型看到 bot 突然绕路/换目标,
     * 就能归因到"这个格子失败次数打满被拉黑", 而不是瞎猜。
     *
     * <p>纪律: 只读 + 全包 try/catch —— 任何异常绝不影响黑名单本身的判定流程;
     * 未录制时 {@code RecordManager.event} 内部零成本早退。
     */
    private void emitRecordEvent(T item, BlacklistEntry entry, int totalFailuresBefore, boolean resetHappened) {
        try {
            Vec3d pos = getPos(item);
            StringBuilder sb = new StringBuilder(192);
            sb.append("{\"kind\":");
            adris.altoclef.record.RecordManager.quote(sb,
                    this instanceof WorldLocateBlacklist ? "block"
                            : this instanceof EntityLocateBlacklist ? "entity" : "other");
            sb.append(",\"pos\":[").append((int) Math.floor(pos.x)).append(',')
                    .append((int) Math.floor(pos.y)).append(',')
                    .append((int) Math.floor(pos.z)).append(']');
            sb.append(",\"fail\":").append(entry.numberOfFailures);
            sb.append(",\"allowed\":").append(entry.numberOfFailuresAllowed);
            sb.append(",\"total\":").append(entry.totalFailures);
            sb.append(",\"tool\":");
            adris.altoclef.record.RecordManager.quote(sb, entry.bestTool.name());
            sb.append(",\"reset\":").append(resetHappened);
            sb.append(",\"banned\":").append(entry.numberOfFailures > entry.numberOfFailuresAllowed);
            sb.append(",\"first\":").append(totalFailuresBefore == 0);
            sb.append('}');
            adris.altoclef.record.RecordManager.event("blacklist", sb.toString());
        } catch (Throwable ignored) {
        }
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
        // (2026-09-22) 是否允许"距离改善 RESET"。unreachable 类失败 (寻路够不着)
        // 登记 false 后粘滞; 默认 true 保持旧行为。
        public boolean distanceResetAllowed = true;
        // (2026-09-13) 上次"距离改善 RESET"的时刻(游戏 tick), 用于 RESET 冷却。
        public int lastResetTick = -RESET_COOLDOWN_TICKS;
    }
}
