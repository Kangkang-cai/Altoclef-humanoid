package adris.altoclef.record;

import adris.altoclef.AltoClef;
import adris.altoclef.trackers.KnowledgeMemory;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * (2026-09-17 数据集录制) 通道 E —— 记忆快照 (只在**变化时**写)。
 *
 * <p>三个记忆子系统 (世界记忆 / 地标知识 / 见过即记忆的贵重方块) 各自维护一个
 * "写版本号", 本类每 tick 只比版本号; 没变就一个字节都不写。记忆写入是低频事件
 * (数秒一次), 所以 memory.jsonl.gz 的体量远小于逐帧通道, 同时保证"任何时刻都能
 * 从最近一条快照还原 bot 的完整记忆内容" —— 不需要重放历史事件。
 *
 * <p>配套的稀疏事件 (memory_write / memory_read) 走 events.jsonl.gz, 由写入点与
 * 决策入口各自调用 {@link RecordManager#event} / {@link RecordManager#eventThrottled}。
 */
final class MemoryRecorder {

    private MemoryRecorder() {}

    private static long _lastWorldRev = -1;
    private static long _lastKnowledgeRev = -1;
    private static long _lastValuableRev = -1;
    // (2026-09-21 录制同步) BlockTracker 缓存 (决策候选集) 的版本门控。缓存变化频率
    // 远高于记忆写入 (近区 5Hz 扫描持续喂入), 单独加 3s 最小间隔, 避免 memory 通道
    // 被"位置只挪了一格"的快照刷屏。
    private static long _lastBlockCacheRev = -1;
    private static long _lastBlockCacheWriteMs = 0;
    private static final long BLOCKCACHE_MIN_INTERVAL_MS = 3000;

    /** 返回 null 表示"记忆没变, 本帧不写"。 */
    static String sample(AltoClef mod) {
        long w;
        long k;
        long v;
        long bc;
        try {
            w = mod.getWorldMemory() == null ? 0 : mod.getWorldMemory().getMemoryRevision();
            k = KnowledgeMemory.getMemoryRevision();
            v = VisibilityHelper.getValuableMemoryRevision();
            bc = mod.getBlockTracker().getCacheRevision();
        } catch (Throwable t) {
            return null;
        }
        long nowMs = System.currentTimeMillis();
        boolean bcDue = bc != _lastBlockCacheRev && nowMs - _lastBlockCacheWriteMs >= BLOCKCACHE_MIN_INTERVAL_MS;
        if (w == _lastWorldRev && k == _lastKnowledgeRev && v == _lastValuableRev && !bcDue) return null;

        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        sb.append("\"seq\":").append(RecordManager.currentSeq());
        sb.append(",\"t\":").append(WorldHelper.getTicks());
        sb.append(",\"rev\":{\"world\":").append(w)
                .append(",\"knowledge\":").append(k)
                .append(",\"valuable\":").append(v)
                .append(",\"blockcache\":").append(bc).append('}');

        sb.append(",\"world\":");
        appendBuckets(sb, safeDump(() -> mod.getWorldMemory().dumpMemoryForRecord()));
        sb.append(",\"knowledge\":");
        appendBuckets(sb, safeDump(() -> mod.getKnowledgeMemory().dumpMemoryForRecord()));
        sb.append(",\"valuable\":");
        appendBuckets(sb, safeDump(VisibilityHelper::dumpValuableMemory));

        // (2026-09-21) BlockTracker 缓存快照: 任务决策候选集 (巡逻目标/失联远走目标/
        // 收集材料取最近的来源)。感知通道只录视线口径, 没有这里离线无法解释
        // "为什么朝林子/那块矿走"。只在 bcDue 时写 (其余情况 null 占位), 单类型
        // ≤100 上限天然有界。
        sb.append(",\"blockcache\":");
        if (bcDue) {
            try {
                sb.append("{\"dim\":");
                Json.str(sb, mod.getBlockTracker() == null ? null
                        : adris.altoclef.util.helpers.WorldHelper.getCurrentDimension().name());
                sb.append(",\"types\":");
                appendBuckets(sb, safeDump(mod.getBlockTracker()::dumpCacheForRecord));
                sb.append('}');
            } catch (Throwable t) {
                sb.append("null");
            }
            _lastBlockCacheRev = bc;
            _lastBlockCacheWriteMs = nowMs;
        } else {
            sb.append("null");
        }

        sb.append('}');

        _lastWorldRev = w;
        _lastKnowledgeRev = k;
        _lastValuableRev = v;
        return sb.toString();
    }

    /** 会话重新开始: 忘掉"上次写到哪个版本", 保证新会话第一条就是完整快照。 */
    static void reset() {
        _lastWorldRev = -1;
        _lastKnowledgeRev = -1;
        _lastValuableRev = -1;
        _lastBlockCacheRev = -1;
        _lastBlockCacheWriteMs = 0;
    }

    private interface Dump {
        Map<String, List<BlockPos>> get();
    }

    private static Map<String, List<BlockPos>> safeDump(Dump dump) {
        try {
            Map<String, List<BlockPos>> m = dump.get();
            return m == null ? Map.of() : m;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    private static void appendBuckets(StringBuilder sb, Map<String, List<BlockPos>> buckets) {
        sb.append('{');
        boolean firstBucket = true;
        for (Map.Entry<String, List<BlockPos>> e : buckets.entrySet()) {
            if (!firstBucket) sb.append(',');
            firstBucket = false;
            Json.str(sb, e.getKey());
            sb.append(":[");
            List<BlockPos> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                BlockPos p = list.get(i);
                Json.ivec(sb, p.getX(), p.getY(), p.getZ());
            }
            sb.append(']');
        }
        sb.append('}');
    }
}