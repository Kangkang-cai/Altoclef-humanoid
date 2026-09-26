package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.SurfaceExitGuide;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * (2026-09-16 远征机制抽基类) "锁定方位角定向远征"原本有三份拷贝:
 * FortressExploreTask (找堡垒/猪灵, 原型) → OverworldExpeditionTask (主世界觅食)
 * → 下界挖金远征。前两者各自为政导致第三份只能继续抄, 本类把 Overworld 版的
 * 机制原样上提为公共基类, 参数化三处差异:
 *
 * - {@link #rejectTarget}: 目标列拒绝条件 (主世界=海洋不下海, 下界=岩浆不入)。
 * - {@link #swimSteering}: 已落水时是否朝最近安全地游出 (主世界 true, 下界 false)。
 * - 结束语义: {@link #naturalFinish} (下界=走出灵魂沙谷; 主世界永不自行结束,
 *   由上层 CollectFoodTask 判定) + {@link #timeoutSeconds} 硬超时兜底 (可关)。
 *
 * 机制 (三份拷贝一直保持一致):
 * - 持久锁定方位角 static 跨实例存活 (存档:维度 隔离), 每步只在 ±5° 内微调 ——
 *   净位移 ~N 而非 √N, 不原地打转、不回头。
 * - 遇障 (寻路失败 + 长时间无新最远点) 同侧加档试探: +60° → +85° → ... → ±150°
 *   封顶, 同侧到顶才换边; 试探方向步长 ×2, 走出整步程即转正。
 * - 诊断: diagTag() 每 10s / 半径里程碑 / 开关量(群系)切换时打一行, 含方位角/
 *   距锚点半径, 供"是否真在远征"取证。
 *
 * 注意: static 状态按 expeditionKey (存档名:维度) 隔离, 主世界与下界任务共享
 * 这几张 map 不会串键。FortressExploreTask 的远征与堡垒线索逻辑耦合深, 本轮
 * 不动它 (重构它会波及近期多次修复, 行为面不可控)。
 */
public abstract class BearingExpeditionTask extends Task implements ITaskRequiresGrounded {

    // 一步多远。短目标搜索空间小, 单轮规划秒级算完, bot 连续走; 方向由锁定方位角保证净位移。
    protected static final double EXPLORE_STEP = 24;
    // 锁定方位角每步抖动 (°), 小抖动 = 大方向不变。
    protected static final double RANDOM_JITTER_DEG = 5;
    // 到达判定: GoalXZ 半径 4.5, 到达判定 5 (判定须 >= 目标半径, 防"差一点无限重设")。
    protected static final double GOAL_RADIUS = 4.5;
    protected static final double ARRIVE_XZ = 5;
    // 候选方向挑选尝试次数 (全拒绝时放弃本轮, 下 tick 再试)。
    protected static final int MAX_PICK_ATTEMPTS = 16;

    // ---- 遇障试探 (仿 FortressExploreTask P2/P2.1/大转弯修订) ----
    protected static final int PROBE_AFTER_FAILS = 2;
    protected static final long STALL_TICKS = 100; // 5s 无新最远点 = 停滞
    protected static final double PROBE_STEP_DEG = 60;
    protected static final double PROBE_INCREMENT_DEG = 25;
    protected static final double MAX_PROBE_DEG = 150;

    protected static final int EXPEDITION_DIAG_INTERVAL_TICKS = 200; // 10s
    protected static final double RADIUS_MILESTONE = 128;

    // 远征状态 static 跨实例 (存档:维度 隔离, 与 FortressExploreTask 同款)。
    private static final Map<String, Double> _headingByKey = new HashMap<>();
    private static final Map<String, BlockPos> _anchorByKey = new HashMap<>();
    private static final Map<String, Double> _maxRadiusByKey = new HashMap<>();
    private static final Map<String, Double> _probeOffsetByKey = new HashMap<>();
    private static final Map<String, Double> _probeStartMaxByKey = new HashMap<>();
    private static final Map<String, BlockPos> _probeStartPosByKey = new HashMap<>();
    private static final Map<String, Long> _lastProgressTickByKey = new HashMap<>();

    private final MovementProgressChecker _progress = new MovementProgressChecker();
    // 硬超时 (秒); <0 = 无超时 (主世界觅食远征由上层判定收队)。
    private final double _timeoutSeconds;
    private final TimerGame _timeout;

    protected boolean _escaped = false;

    private BlockPos _currentTarget;
    /** 出洞安全引导武装期内正在逼近的 3D 安全落点 (null = 未占用引导分支)。 */
    private BlockPos _safeExitTarget = null;
    protected String _persistKey;
    private long _tickId = 0;
    private int _failCounter = 0;
    private Vec3d _lastDiagPos;
    private double _lastLoggedRadius = 0;
    private boolean _lastSwitchState = false;

    /** @param timeoutSeconds 硬超时秒数; <0 = 不设超时。 */
    protected BearingExpeditionTask(double timeoutSeconds) {
        _timeoutSeconds = timeoutSeconds;
        _timeout = timeoutSeconds > 0 ? new TimerGame(timeoutSeconds) : null;
    }

    // ------------------------------------------------------------------ //
    // 子类钩子
    // ------------------------------------------------------------------ //

    /** 诊断标签, 如 "[FOOD-EXPEDITION]" / "[NETHER-EXPEDITION]"。 */
    protected abstract String diagTag();

    /** 目标列拒绝条件 (主世界=海洋, 下界=岩浆)。未加载 chunk 由实现自定口径。 */
    protected abstract boolean rejectTarget(AltoClef mod, BlockPos candidate);

    /** 已落水时是否朝最近安全地游出 (主世界 true, 下界 false)。 */
    protected boolean swimSteering() {
        return false;
    }

    /** 诊断开关量: 跟踪切换打点的状态 (主世界=是否海洋, 下界=是否在谷内)。 */
    protected boolean diagSwitchState(AltoClef mod) {
        return false;
    }

    /** 诊断开关量字段名 (ocean / inValley)。 */
    protected String diagSwitchLabel() {
        return "switch";
    }

    /** 自然结束条件 (下界=走出山谷; 主世界永不 → false, 由上层判定)。 */
    protected boolean naturalFinish(AltoClef mod) {
        return false;
    }

    /**
     * 是否启用出洞安全引导 (2026-09-24): 仅主世界觅食远征开启 —— 出地表后前 3 个
     * 导航必落草方块等强签名露天点, 防洞口边缘横跳。下界/诡异森林远征不开。
     */
    protected boolean useSurfaceExitGuide() {
        return false;
    }

    /**
     * (2026-09-26) 沿本步直线的【恶劣地形】拒绝钩子 (默认不启用, 下界/诡异森林
     * 远征不受影响)。返回 true 时这一步换向重采 —— 注意这只是"拒绝候选步",
     * 方向的最终变更仍由既有遇障试探机制完成: 连续拒绝累计 _failCounter →
     * maybeEscalateProbe 同侧加档试探 (+60°…±150°) → 试探走出整步程转正为新
     * 锁定方位角。已落水 (swimming) 时不调用: 游出去优先, 不做地形挑剔。
     */
    protected boolean rejectTerrainAlong(AltoClef mod, Vec3d from, double angle, double step) {
        return false;
    }

    /** 超时放弃时的补充说明 (可选)。 */
    protected String timeoutDetail(AltoClef mod) {
        return "";
    }

    // ------------------------------------------------------------------ //
    // 生命周期
    // ------------------------------------------------------------------ //

    @Override
    protected void onStart(AltoClef mod) {
        if (_timeout != null) _timeout.reset();
        _progress.reset();
        _failCounter = 0;
        _currentTarget = null;
        _safeExitTarget = null;
        _tickId = 0;
        _lastLoggedRadius = 0;
        _escaped = false;
        _persistKey = expeditionKey(mod);
        if (mod.getPlayer() != null) {
            _lastSwitchState = diagSwitchState(mod);
            _anchorByKey.computeIfAbsent(_persistKey, k -> mod.getPlayer().getBlockPos());
        }
        Double kept = _headingByKey.get(_persistKey);
        if (kept != null) {
            Debug.logMessage(diagTag() + " resumed bearing " + fmtDeg(kept) + "° for " + _persistKey);
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

        boolean swimming = swimSteering() && isPlayerInWater(mod);

        // (2026-09-24 出洞安全引导) 武装期内所有导航目标改走 SurfaceExitGuide:
        // 必须是草方块/沙/雪等强签名的露天落点 (带真实站立 y, 3D GoalNear),
        // 且距出洞锚点 ≥24 格。绝不能落回纯 XZ 目标 —— GoalXZWithin 任意高度
        // 达成, baritone 会选下坡穿洞的便宜路把 bot 领回深处 (洞口横跳根因)。
        // 已在水里时交游泳引导, 不走本分支。
        if (useSurfaceExitGuide() && !swimming && SurfaceExitGuide.isArmed(mod)) {
            // 接管期作废可能残留的旧纯 XZ 目标, 防解除引导后旧 GoalXZWithin 复活。
            _currentTarget = null;
            return tickSurfaceExit(mod);
        }
        _safeExitTarget = null;

        if (_currentTarget == null || xzDistance(mod.getPlayer().getBlockPos(), _currentTarget) <= ARRIVE_XZ) {
            _currentTarget = pickTarget(mod, swimming);
            if (_currentTarget == null) {
                // 四周全拒绝/全失败: 计失败并升级试探, 下 tick 重选。
                if (++_failCounter > 0) {
                    maybeEscalateProbe(mod);
                }
                setDebugState(diagTag() + " no safe direction yet");
                return null;
            }
            _progress.reset();
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZWithin(_currentTarget, GOAL_RADIUS));
            return null;
        }

        if (!_progress.check(mod)) {
            // 难地形全量规划期 bot 0 位移, 寻路器还在算时不算失败。
            if (mod.getClientBaritone().getPathingBehavior().getInProgress().isPresent()) {
                setDebugState(diagTag() + " waiting for pathfinder");
                return null;
            }
            _failCounter++;
            _currentTarget = null;
            maybeEscalateProbe(mod);
            Debug.logMessage(diagTag() + " step failed, picking a new direction.");
            return null;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZWithin(_currentTarget, GOAL_RADIUS));
        }
        setDebugState(diagTag() + " (locked bearing)");
        return null;
    }

    // ------------------------------------------------------------------ //
    // 出洞安全引导 (2026-09-24)
    // ------------------------------------------------------------------ //

    /**
     * 武装期 onTick 分流: 扫描并逼近 SurfaceExitGuide 的 3D 安全落点, 到达后
     * 按成功计数 (guide 内部要求当前确在地表, 穿洞路径走到的列点不计数)。
     * 找不到点的 tick 不发任何导航 (绝不退回穿洞 XZ 目标)。
     */
    private Task tickSurfaceExit(AltoClef mod) {
        // (审查 Medium-3) 远征普通分支不接 JumpStuckResolver, 武装期在洞口卡岩沿时
        // 会干站到 90s 超时; 引导分支补接 (与 BiasedExploreTask 同款, 处置期重置进度)。
        Task jumpFix = JumpStuckResolver.tick(mod);
        if (jumpFix != null) {
            _progress.reset();
            return jumpFix;
        }
        // XZ 到达且 guide 确认计数 (脚高也对、确在地表) 才换下一点; 未计数 (岩台
        // XZ 到达但还没爬上落点 y / 地表确认窗未满) 时保持同一目标继续逼近。
        if (_safeExitTarget != null
                && xzDistance(mod.getPlayer().getBlockPos(), _safeExitTarget) <= ARRIVE_XZ
                && SurfaceExitGuide.recordArrived(mod, _safeExitTarget)) {
            _safeExitTarget = null;
        }
        if (_safeExitTarget == null) {
            _safeExitTarget = SurfaceExitGuide.findSafeLanding(mod, currentBearing());
            if (_safeExitTarget == null) {
                // (审查 Medium-1) 本拍没有安全落点: 必须取消 baritone 仍持有武装前的
                // 旧 GoalXZWithin (任意高度达成, 正是穿洞目标), 不能让它在空档继续
                // 把 bot 领回洞里。
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                setDebugState(diagTag() + " surface-exit armed, no safe landing this tick");
                return null;
            }
            _progress.reset();
            mod.getClientBaritone().getCustomGoalProcess()
                    .setGoalAndPath(new GoalNear(_safeExitTarget, 2));
            return null;
        }
        if (!_progress.check(mod)) {
            // 全量规划期 0 位移不算失败, 与普通步同一口径。
            if (mod.getClientBaritone().getPathingBehavior().getInProgress().isPresent()) {
                setDebugState(diagTag() + " surface-exit waiting for pathfinder");
                return null;
            }
            Debug.logMessage(diagTag() + " surface-exit step failed, rescanning a safe landing.");
            SurfaceExitGuide.reject(_safeExitTarget);
            _safeExitTarget = null;
            return null;
        }
        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess()
                    .setGoalAndPath(new GoalNear(_safeExitTarget, 2));
        }
        setDebugState(diagTag() + " surface-exit -> " + _safeExitTarget.toShortString()
                + " (" + SurfaceExitGuide.remaining() + " safe nav left)");
        return null;
    }

    /** 当前锁定方位角 + 试探偏置 (不抖动、不写回); 尚未有方位时随机一个。 */
    private double currentBearing() {
        Double stored = _headingByKey.get(_persistKey);
        if (stored == null || Double.isNaN(stored)) {
            stored = Math.random() * 2 * Math.PI;
        }
        Double probe = _probeOffsetByKey.get(_persistKey);
        return stored + (probe == null ? 0.0 : probe);
    }

    // ------------------------------------------------------------------ //
    // 目标选择
    // ------------------------------------------------------------------ //

    /**
     * 目标选择: 沿锁定方位角 ±5° 抖动走一步 (试探偏置 >=60° 时步长 ×2)。
     * 目标列被 rejectTarget 拒绝则换向; 游泳模式优先朝最近安全地游。
     */
    private BlockPos pickTarget(AltoClef mod, boolean swimming) {
        Vec3d pos = mod.getPlayer().getPos();
        for (int attempt = 0; attempt < MAX_PICK_ATTEMPTS; ++attempt) {
            double angle;
            if (swimming) {
                BlockPos land = findNearestSafeColumn(mod);
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
            if (rejectTarget(mod, candidate)) {
                continue;
            }
            // (2026-09-26 恶劣地形拒绝) 岸上时沿本步直线判地形, 极端恶劣 (主世界
            // = 宽水) 换向重采; 连续全灭由试探机制升级换方向。
            if (!swimming && rejectTerrainAlong(mod, pos, angle, step)) {
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
                "%s stalled (no new max for %ds), probing %+.0f° (cap ±%.0f°, long step x2)",
                diagTag(), stalledTicks / 20, Math.toDegrees(next), MAX_PROBE_DEG));
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
                            "%s probe %+.0f° committed (%s), new bearing %s",
                            diagTag(), Math.toDegrees(probe), walkedArc ? "walked arc" : "radius broken",
                            fmtDeg(base + probe)));
                }
                _probeOffsetByKey.put(key, 0.0);
                _probeStartMaxByKey.remove(key);
                _probeStartPosByKey.remove(key);
            }
        }
        boolean switchNow = diagSwitchState(mod);
        boolean periodic = _tickId % EXPEDITION_DIAG_INTERVAL_TICKS == 0;
        boolean milestone = maxRadius - _lastLoggedRadius >= RADIUS_MILESTONE;
        boolean stateSwitch = switchNow != _lastSwitchState;
        if (!periodic && !milestone && !stateSwitch) return;

        _lastLoggedRadius = maxRadius;
        _lastSwitchState = switchNow;
        String moved = _lastDiagPos == null ? "-"
                : String.format(java.util.Locale.ROOT, "%.1f", _lastDiagPos.distanceTo(now));
        _lastDiagPos = now;
        Double bearing = _headingByKey.get(key);
        Debug.logMessage(String.format(java.util.Locale.ROOT,
                "%s bearing=%s anchor=(%d,%d) radius=%.0f max=%.0f target=%s moved=%s %s=%s probe=%+.0f fails=%d",
                diagTag(),
                bearing == null ? "?" : fmtDeg(bearing),
                anchor.getX(), anchor.getZ(), radius, maxRadius,
                _currentTarget == null ? "-" : _currentTarget.toShortString(),
                moved, diagSwitchLabel(), switchNow ? "Y" : "N",
                Math.toDegrees(_probeOffsetByKey.getOrDefault(key, 0.0)), _failCounter));
    }

    /** 当前远征锚点 (onStart 记录; 未开跑返回 null), 供子类结束判定用。 */
    protected BlockPos getAnchor() {
        return _persistKey == null ? null : _anchorByKey.get(_persistKey);
    }

    // ------------------------------------------------------------------ //
    // 工具
    // ------------------------------------------------------------------ //

    protected static double xzDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 已落水判定 (主世界游泳引导用; 下界 swimSteering=false 不会被调用)。 */
    protected boolean isPlayerInWater(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return false;
        if (mod.getPlayer().isTouchingWater()) return true;
        BlockPos feet = mod.getPlayer().getBlockPos();
        return mod.getChunkTracker().isChunkLoaded(feet)
                && mod.getWorld().getBlockState(feet).isOf(net.minecraft.block.Blocks.WATER);
    }

    /** 沿 8 方向扫描最近的未被拒绝的安全 XZ (仿 BiasedExploreTask.findNearestLand)。 */
    private BlockPos findNearestSafeColumn(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int dist = 16; dist <= 128; dist += 16) {
            for (int d = 0; d < 8; d++) {
                double ang = Math.PI / 4.0 * d;
                int x = p.getX() + (int) Math.round(Math.cos(ang) * dist);
                int z = p.getZ() + (int) Math.round(Math.sin(ang) * dist);
                BlockPos cand = new BlockPos(x, p.getY(), z);
                if (mod.getChunkTracker().isChunkLoaded(cand) && !rejectTarget(mod, cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    protected static String expeditionKey(AltoClef mod) {
        // (2026-09-17 跨世界污染) 显示名 → 存档目录名, 统一走 WorldIdentity。
        return adris.altoclef.util.helpers.WorldIdentity.key(mod);
    }

    /**
     * (2026-09-17 世界边界丢弃) 清空方位 / 锚点 / 试探进度 static。
     * `_anchorByKey`(远征锚点) 与 `_probeStartPosByKey`(试探起点) 都是坐标 ——
     * 不清就是"上个世界的锚点被当本世界的导航目标"(2026-09-10 传送门同款事故)。
     */
    public static void onWorldBoundary() {
        _headingByKey.clear();
        _anchorByKey.clear();
        _maxRadiusByKey.clear();
        _probeOffsetByKey.clear();
        _probeStartMaxByKey.clear();
        _probeStartPosByKey.clear();
        _lastProgressTickByKey.clear();
    }

    protected static String fmtDeg(double rad) {
        double deg = Math.toDegrees(rad) % 360;
        if (deg < 0) deg += 360;
        return String.format(java.util.Locale.ROOT, "%.0f", deg);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_escaped) return true;
        if (mod.getPlayer() == null || mod.getWorld() == null) return false;
        if (_timeout != null && _timeout.elapsed()) {
            String detail = timeoutDetail(mod);
            Debug.logMessage(diagTag() + " timeout " + (int) _timeoutSeconds + "s, giving up"
                    + (detail.isEmpty() ? "" : " (" + detail + ")"));
            _escaped = true;
            return true;
        }
        if (naturalFinish(mod)) {
            _escaped = true;
            return true;
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        // 身份比较: 上层缓存同一实例每 tick 返回, 状态得以保留。
        return other == this;
    }

    /** 水平半径目标: 站到目标点水平 R 格内任意高度即达成 (仿 FortressExploreTask)。 */
    protected static class GoalXZWithin implements Goal {
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
