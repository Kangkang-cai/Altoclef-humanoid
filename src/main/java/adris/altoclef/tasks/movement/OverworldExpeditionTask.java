package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * (2026-09-15 地表觅食远征, 用户"连续长时间没找到食物就远走, 尽量不要回头,
 * 不要远征走到海里去了") 复用 FortressExploreTask 下界远征的"锁定方位角"机制,
 * 搬到主世界:
 *
 * - 持久锁定方位角 static 跨任务实例存活 (存档:维度 隔离), 每步只在 ±5° 内
 *   微调 —— 净位移 ~N 而非 √N, 不原地打转、不回头。
 * - 目标列 biome 是【海洋】一律拒绝 (不下海); 河/湖放行 (baritone 游河面没问题)。
 *   玩家已在水里时直接朝最近非海洋陆地游出去。
 * - 遇障 (寻路失败 + 长时间无新最远点) 沿用 P2.1 同侧加档试探: +60° → +85° →
 *   ... → ±150° 封顶, 同侧到顶才换边; 试探方向步长 ×2, 走出整步程即转正。
 * - 永不自行 isFinished: 是否结束由上层 (CollectFoodTask 食物达标) 决定。
 *
 * 诊断: [FOOD-EXPEDITION] 每 10s / 半径里程碑 / 群系(海)切换时打一行,
 * 含方位角/距锚点半径/线索, 供"是否真在远征"取证。
 */
public class OverworldExpeditionTask extends Task implements ITaskRequiresGrounded {

    // 一步多远。与 FortressExploreTask 2026-09-14 短步修订一致: 短目标搜索空间
    // 小, 单轮规划秒级算完, bot 连续走; 方向由锁定方位角保证净位移。
    private static final double EXPLORE_STEP = 24;
    // 锁定方位角每步抖动 (°), 小抖动 = 大方向不变。
    private static final double RANDOM_JITTER_DEG = 5;
    // 到达判定: GoalXZ 半径 4.5, 到达判定 5 (判定须 >= 目标半径, 防"差一点无限重设")。
    private static final double GOAL_RADIUS = 4.5;
    private static final double ARRIVE_XZ = 5;
    // 候选方向挑选尝试次数 (全海洋时放弃本轮, 下 tick 再试)。
    private static final int MAX_PICK_ATTEMPTS = 16;

    // ---- 遇障试探 (仿 FortressExploreTask P2/P2.1/大转弯修订) ----
    private static final int PROBE_AFTER_FAILS = 2;
    private static final long STALL_TICKS = 100; // 5s 无新最远点 = 停滞
    private static final double PROBE_STEP_DEG = 60;
    private static final double PROBE_INCREMENT_DEG = 25;
    private static final double MAX_PROBE_DEG = 150;

    private static final int EXPEDITION_DIAG_INTERVAL_TICKS = 200; // 10s
    private static final double RADIUS_MILESTONE = 128;

    // 远征状态 static 跨实例 (存档:维度 隔离, 与 FortressExploreTask 同款)。
    private static final Map<String, Double> _headingByKey = new HashMap<>();
    private static final Map<String, BlockPos> _anchorByKey = new HashMap<>();
    private static final Map<String, Double> _maxRadiusByKey = new HashMap<>();
    private static final Map<String, Double> _probeOffsetByKey = new HashMap<>();
    private static final Map<String, Double> _probeStartMaxByKey = new HashMap<>();
    private static final Map<String, BlockPos> _probeStartPosByKey = new HashMap<>();
    private static final Map<String, Long> _lastProgressTickByKey = new HashMap<>();

    private final MovementProgressChecker _progress = new MovementProgressChecker();

    private BlockPos _currentTarget;
    private String _persistKey;
    private long _tickId = 0;
    private int _failCounter = 0;
    private Vec3d _lastDiagPos;
    private double _lastLoggedRadius = 0;
    private boolean _lastWasOcean = false;

    @Override
    protected void onStart(AltoClef mod) {
        _progress.reset();
        _failCounter = 0;
        _currentTarget = null;
        _tickId = 0;
        _lastLoggedRadius = 0;
        _persistKey = expeditionKey(mod);
        if (mod.getPlayer() != null) {
            _anchorByKey.computeIfAbsent(_persistKey, k -> mod.getPlayer().getBlockPos());
        }
        Double kept = _headingByKey.get(_persistKey);
        if (kept != null) {
            Debug.logMessage("[FOOD-EXPEDITION] resumed bearing " + fmtDeg(kept) + "° for " + _persistKey);
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        _tickId++;

        maybeLogExpedition(mod);

        boolean swimming = isPlayerInWater(mod);

        if (_currentTarget == null || xzDistance(mod.getPlayer().getBlockPos(), _currentTarget) <= ARRIVE_XZ) {
            _currentTarget = pickTarget(mod, swimming);
            if (_currentTarget == null) {
                // 四周全海/全失败: 计失败并升级试探, 下 tick 重选。
                if (++_failCounter > 0) {
                    maybeEscalateProbe(mod);
                }
                setDebugState("Food expedition: no dry direction yet");
                return null;
            }
            _progress.reset();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZWithin(_currentTarget, GOAL_RADIUS));
            return null;
        }

        if (!_progress.check(mod)) {
            // (仿 FortressExploreTask 规划豁免) 难地形全量规划期 bot 0 位移,
            // 寻路器还在算时不算失败。
            if (mod.getClientBaritone().getPathingBehavior().getInProgress().isPresent()) {
                setDebugState("Food expedition: waiting for pathfinder");
                return null;
            }
            _failCounter++;
            _currentTarget = null;
            maybeEscalateProbe(mod);
            Debug.logMessage("[FOOD-EXPEDITION] step failed, picking a new direction.");
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZWithin(_currentTarget, GOAL_RADIUS));
        }
        setDebugState("Food expedition (locked bearing)");
        return null;
    }

    /**
     * 目标选择: 沿锁定方位角 ±5° 抖动走一步 (试探偏置 >=60° 时步长 ×2)。
     * 目标列是【海洋】拒绝; 已在水里时优先朝最近非海洋陆地游。
     */
    private BlockPos pickTarget(AltoClef mod, boolean swimming) {
        Vec3d pos = mod.getPlayer().getPos();
        for (int attempt = 0; attempt < MAX_PICK_ATTEMPTS; ++attempt) {
            double angle;
            if (swimming) {
                BlockPos land = findNearestLand(mod);
                if (land != null) {
                    angle = Math.atan2(land.getZ() - pos.z, land.getX() - pos.x);
                } else {
                    angle = nextRandomHeading();
                }
            } else {
                angle = nextRandomHeading();
            }
            double step = EXPLORE_STEP;
            Double probeDeg = _probeOffsetByKey.get(_persistKey);
            if (probeDeg != null && Math.abs(Math.toDegrees(probeDeg)) >= PROBE_STEP_DEG) {
                step = EXPLORE_STEP * 2; // 试探要走长步, 沿崖/沿岸走不出名堂
            }
            int candX = (int) Math.floor(pos.x + Math.cos(angle) * step);
            int candZ = (int) Math.floor(pos.z + Math.sin(angle) * step);
            BlockPos candidate = new BlockPos(candX, (int) Math.floor(pos.y), candZ);
            // 海洋拒绝: biome 是海洋不下海。河/湖放行 (跨河没问题)。
            if (isTargetOcean(mod, candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    // 锁定方位角 + 遇障试探 (仿 FortressExploreTask, 语义一致)
    // ------------------------------------------------------------------ //

    /** 首次随机, 之后只在 ±RANDOM_JITTER_DEG 内微调, 写回 static 跨实例。 */
    private double nextRandomHeading() {
        Double stored = _headingByKey.get(_persistKey);
        if (stored == null || Double.isNaN(stored)) {
            stored = Math.random() * 2 * Math.PI;
        } else {
            stored += (Math.random() * 2 - 1) * Math.toRadians(RANDOM_JITTER_DEG);
        }
        _headingByKey.put(_persistKey, stored);
        Double probe = _probeOffsetByKey.get(_persistKey);
        return stored + (probe == null ? 0.0 : probe);
    }

    /** 停滞够久 (无新最远点) 且失败够多 → 同侧加档试探 (+60 → ... → ±150, 顶格换边)。 */
    private void maybeEscalateProbe(AltoClef mod) {
        String key = _persistKey;
        BlockPos anchor = _anchorByKey.get(key);
        if (anchor == null || mod.getPlayer() == null) return;
        long lastProgress = _lastProgressTickByKey.getOrDefault(key, _tickId);
        long stalledTicks = _tickId - lastProgress;
        if (stalledTicks < STALL_TICKS) return;
        boolean enoughFails = _failCounter >= PROBE_AFTER_FAILS;
        boolean longStall = _failCounter >= 1 && stalledTicks >= STALL_TICKS * 2;
        if (!enoughFails && !longStall) return;
        double cur = _probeOffsetByKey.getOrDefault(key, 0.0);
        double next;
        if (cur == 0.0) {
            next = Math.toRadians(PROBE_STEP_DEG);
        } else if (Math.abs(Math.toDegrees(cur)) < MAX_PROBE_DEG) {
            double mag = Math.min(Math.abs(cur) + Math.toRadians(PROBE_INCREMENT_DEG), Math.toRadians(MAX_PROBE_DEG));
            next = cur > 0 ? mag : -mag;
        } else {
            next = cur > 0 ? -Math.toRadians(PROBE_STEP_DEG) : Math.toRadians(PROBE_STEP_DEG);
        }
        _probeOffsetByKey.put(key, next);
        _probeStartMaxByKey.put(key, _maxRadiusByKey.getOrDefault(key, 0.0));
        _probeStartPosByKey.put(key, mod.getPlayer().getBlockPos());
        _failCounter = 0;
        Debug.logMessage(String.format(java.util.Locale.ROOT,
                "[FOOD-EXPEDITION] stalled (no new max for %ds), probing %+.0f° (cap ±%.0f°, long step x2)",
                stalledTicks / 20, Math.toDegrees(next), MAX_PROBE_DEG));
    }

    // ------------------------------------------------------------------ //
    // 诊断 + 试探转正 (仿 FortressExploreTask maybeLogExpedition)
    // ------------------------------------------------------------------ //

    private void maybeLogExpedition(AltoClef mod) {
        if (mod.getPlayer() == null) return;
        Vec3d now = mod.getPlayer().getPos();
        BlockPos p = mod.getPlayer().getBlockPos();
        String key = _persistKey;
        BlockPos anchor = _anchorByKey.computeIfAbsent(key, k -> p);
        double radius = Math.hypot(p.getX() - anchor.getX(), p.getZ() - anchor.getZ());
        double prevMax = _maxRadiusByKey.getOrDefault(key, 0.0);
        double maxRadius = Math.max(prevMax, radius);
        _maxRadiusByKey.put(key, maxRadius);
        if (radius > prevMax + 0.5) {
            _lastProgressTickByKey.put(key, _tickId);
            _failCounter = 0;
        }
        // 试探成功 → 转正为新锁定方向: 半径破纪录 或 距试探起点净位移 > 整步程。
        Double probe = _probeOffsetByKey.get(key);
        if (probe != null && probe != 0.0) {
            Double startMax = _probeStartMaxByKey.get(key);
            BlockPos startPos = _probeStartPosByKey.get(key);
            boolean radiusBroken = startMax != null && radius > startMax + 1.0;
            boolean walkedArc = startPos != null
                    && Math.hypot(p.getX() - startPos.getX(), p.getZ() - startPos.getZ()) > EXPLORE_STEP;
            if (radiusBroken || walkedArc) {
                Double base = _headingByKey.get(key);
                if (base != null && !Double.isNaN(base)) {
                    _headingByKey.put(key, base + probe);
                    Debug.logMessage(String.format(java.util.Locale.ROOT,
                            "[FOOD-EXPEDITION] probe %+.0f° committed (%s), new bearing %s",
                            Math.toDegrees(probe), walkedArc ? "walked arc" : "radius broken",
                            fmtDeg(base + probe)));
                }
                _probeOffsetByKey.put(key, 0.0);
                _probeStartMaxByKey.remove(key);
                _probeStartPosByKey.remove(key);
            }
        }
        boolean oceanNow = WorldHelper.isOcean(mod.getWorld().getBiome(p));
        boolean periodic = _tickId % EXPEDITION_DIAG_INTERVAL_TICKS == 0;
        boolean milestone = maxRadius - _lastLoggedRadius >= RADIUS_MILESTONE;
        boolean oceanSwitch = oceanNow != _lastWasOcean;
        if (!periodic && !milestone && !oceanSwitch) return;

        _lastLoggedRadius = maxRadius;
        _lastWasOcean = oceanNow;
        String moved = _lastDiagPos == null ? "-"
                : String.format(java.util.Locale.ROOT, "%.1f", _lastDiagPos.distanceTo(now));
        _lastDiagPos = now;
        Double bearing = _headingByKey.get(key);
        Debug.logMessage(String.format(java.util.Locale.ROOT,
                "[FOOD-EXPEDITION] bearing=%s anchor=(%d,%d) radius=%.0f max=%.0f target=%s moved=%s ocean=%s probe=%+.0f fails=%d",
                bearing == null ? "?" : fmtDeg(bearing),
                anchor.getX(), anchor.getZ(), radius, maxRadius,
                _currentTarget == null ? "-" : _currentTarget.toShortString(),
                moved, oceanNow ? "Y" : "N",
                Math.toDegrees(_probeOffsetByKey.getOrDefault(key, 0.0)), _failCounter));
    }

    // ------------------------------------------------------------------ //
    // 工具
    // ------------------------------------------------------------------ //

    private static double xzDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean isPlayerInWater(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return false;
        if (mod.getPlayer().isTouchingWater()) return true;
        BlockPos feet = mod.getPlayer().getBlockPos();
        return mod.getChunkTracker().isChunkLoaded(feet)
                && mod.getWorld().getBlockState(feet).isOf(net.minecraft.block.Blocks.WATER);
    }

    /** 目标列是否海洋 biome (与 BiasedExploreTask.isTargetOcean 同口径)。 */
    private boolean isTargetOcean(AltoClef mod, BlockPos target) {
        if (mod.getWorld() == null) return false;
        if (!mod.getChunkTracker().isChunkLoaded(target)) return false;
        return WorldHelper.isOcean(mod.getWorld().getBiome(target));
    }

    /** 沿 8 方向扫描最近的非海洋陆地 XZ (仿 BiasedExploreTask.findNearestLand)。 */
    private BlockPos findNearestLand(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int dist = 16; dist <= 128; dist += 16) {
            for (int d = 0; d < 8; d++) {
                double ang = Math.PI / 4.0 * d;
                int x = p.getX() + (int) Math.round(Math.cos(ang) * dist);
                int z = p.getZ() + (int) Math.round(Math.sin(ang) * dist);
                BlockPos cand = new BlockPos(x, p.getY(), z);
                if (mod.getChunkTracker().isChunkLoaded(cand)
                        && !WorldHelper.isOcean(mod.getWorld().getBiome(cand))) {
                    return cand;
                }
            }
        }
        return null;
    }

    private static String expeditionKey(AltoClef mod) {
        String levelName = "?";
        try {
            var server = MinecraftClient.getInstance().getServer();
            if (server != null && server.getSaveProperties() != null && server.getSaveProperties().getLevelName() != null) {
                levelName = server.getSaveProperties().getLevelName();
            }
        } catch (Exception ignored) {
        }
        return levelName + ":" + mod.getWorld().getRegistryKey().getValue().toString();
    }

    private static String fmtDeg(double rad) {
        double deg = Math.toDegrees(rad) % 360;
        if (deg < 0) deg += 360;
        return String.format(java.util.Locale.ROOT, "%.0f", deg);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 永不自行结束: 食物达标由上层 (CollectFoodTask) 判定并收队。
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        // 身份比较: 上层缓存同一实例每 tick 返回, 状态得以保留。
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Overworld food expedition";
    }

    /** 水平半径目标: 站到目标点水平 R 格内任意高度即达成 (仿 FortressExploreTask)。 */
    private class GoalXZWithin implements Goal {
        private final double _cx, _cz, _radiusSq;

        GoalXZWithin(BlockPos target, double radius) {
            _cx = target.getX() + 0.5;
            _cz = target.getZ() + 0.5;
            _radiusSq = radius * radius;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            double dx = x + 0.5 - _cx;
            double dz = z + 0.5 - _cz;
            return dx * dx + dz * dz <= _radiusSq;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double dx = x + 0.5 - _cx;
            double dz = z + 0.5 - _cz;
            return Math.max(0, Math.sqrt(dx * dx + dz * dz) - Math.sqrt(_radiusSq));
        }
    }
}
