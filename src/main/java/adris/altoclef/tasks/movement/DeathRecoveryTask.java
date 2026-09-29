package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.EmptyChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Returns to a remembered death point and picks up any item drops that are
 * still being tracked there.
 *
 * Two-phase flow:
 *   PHASE_GOTO_GRAVE  — navigate the player to the grave block.
 *   PHASE_PICKUP      — grab every tracked ItemEntity within
 *                       PICKUP_RADIUS blocks of the grave. If no drops are
 *                       found for NO_DROPS_GIVE_UP_SECONDS we assume they were
 *                       already picked up / despawned / fell into lava and
 *                       finish early. (2026-09-29) 到达墓碑那刻若是空手,
 *                       先走 ARRIVAL_EMPTY_RETRY_SECONDS 的有上限重试窗口
 *                       (站立复查, 不漫游), 过期才收工 —— 专用服下掉落实体
 *                       同步/区块回载可能晚于到达, 旧口径当场清碑会丢光掉落物。
 *   PHASE_DONE        — onTick returns no sub-tasks and isFinished returns
 *                       true so the parent chain can clear the grave memory.
 *
 * The task also honours a per-attempt timeout (overallTimer) derived from
 * `Settings.deathRecoveryTimeoutSeconds`. If we exceed the timeout the task
 * gives up regardless of phase so we never spend longer than ~4.5 minutes
 * chasing drops that are about to despawn anyway.
 */
public class DeathRecoveryTask extends Task {

    private static final int PICKUP_RADIUS = 16;

    /**
     * (2026-09-16) 暴露拾取扫描半径, 供 DeathRecoveryChain 在"弃墓切主任务"时
     * 复用同量级半径判断墓碑附近掉落物(避免两套魔数漂移)。
     */
    public static int getPickupRadius() {
        return PICKUP_RADIUS;
    }
    private static final int GRAVE_REACHED_HORIZONTAL = 3;
    private static final int GRAVE_REACHED_VERTICAL = 4;
    // (2026-09-09 10→5, 用户"捡完了任务还不更新") 最后一件捡完后的空窗等待,
    // 5s 足够确认没有新掉落, 不用陪掉落物消失窗口耗满 10s。
    private static final int NO_DROPS_GIVE_UP_SECONDS = 5;
    // (2026-09-10 降级扫描) 从未感知到掉落物时, 降级扫描也连续扫不到这么久
    // 才承认掉落物真没了 (岩浆烧毁/被别的实体捡走)。
    private static final int DEGRADE_SCAN_GIVE_UP_SECONDS = 30;
    // (2026-09-10 陈旧坐标复核) PICKUP 阶段发现玩家离墓碑超过拾取半径+余量,
    // 说明当前坐标不是真到了墓碑 (重生瞬间的陈旧坐标), 退回 GOTO 重新导航。
    private static final int PICKUP_POSITION_MARGIN = 4;

    /**
     * (2026-09-29 多人首测) "到达墓碑却看不到掉落物" 的重试窗口秒数。
     * 专用服下掉落实体同步 / 区块回载可能晚于到达时刻几 tick, 而旧口径
     * (2026-09-17 到达即判定) 当场收工并清碑 ⇒ 掉落物永久丢失。窗口内站立
     * 复查(不漫游), 过期才收工 —— 本值即上限, keepInventory 服上"永远空"
     * 也只是多等这么久。
     */
    private static final int ARRIVAL_EMPTY_RETRY_SECONDS = 3;

    private enum Phase { GOTO_GRAVE, PICKUP, DONE }

    private final BlockPos _gravePos;
    private final Dimension _graveDim;
    private final int _timeoutSeconds;

    private Phase _phase = Phase.GOTO_GRAVE;
    private Task _currentSub = null;
    private ItemEntity _currentPickupTarget = null;

    private final TimerGame _overallTimer;
    private final TimerGame _noDropsTimer;
    // (2026-09-17 去 wander) 空窗期不再漫游 —— 见 tickPickup 的注释。
    // (2026-09-10 水下感知盲区) 拟人感知 (EntityTracker 视线+FOV/声音) 从未
    // 发现过任何掉落物时为 false; 一旦发现过 (含降级扫描命中) 为 true。
    private boolean _everSawDropNearGrave = false;
    // (2026-09-10 降级扫描) 感知闸门始终建立不起来时的直接扫描模式。
    private boolean _degradeScanActive = false;
    private final TimerGame _degradeScanTimer = new TimerGame(DEGRADE_SCAN_GIVE_UP_SECONDS);
    // (2026-09-29 多人首测) 到达墓碑时空手的重试窗口(见 ARRIVAL_EMPTY_RETRY_SECONDS
    // 与 tickGoToGrave/tickPickup 的注释): true 时 giveUp 判据改用本计时器。
    private boolean _arrivalEmpty = false;
    private final TimerGame _arrivalEmptyTimer = new TimerGame(ARRIVAL_EMPTY_RETRY_SECONDS);
    // Track whether the first onTick has logged the header.
    private boolean _started = false;
    // (2026-09-17 进度保留) onStart 只在实例首次启动时做初始化, 恢复(抢占后 reset
    // 再次进入 onStart)时保留进度。详见 onStart 注释。
    private boolean _initialized = false;

    // (2026-09-12 GOTO 中途升级镐子) 空手导航到深埋墓地时 baritone 成本模型
    // 爆炸算不出路径 (20260912-114028 实锤: 空手复活地表 y=63 → 墓 y=-26,
    // path[present=false] → wander 循环 3 分钟零进展)。激活时链层闸门已按
    // 墓地深度动态要求石镐, 这里是兜底: 区块未加载导致 heightmap 误判/开关
    // 关闭等漏网场景下, GOTO 失速 (每 5s 采样, 20s 窗口净位移 <3 格) 且
    // 墓地确实埋在地下且无石镐档 → 挂起导航先搞镐, 到手后重新导航。
    private static final int STALL_SAMPLE_SECONDS = 5;
    private static final int STALL_WINDOW_SAMPLES = 4;
    private static final double STALL_NET_MOVEMENT = 3.0;
    private static final int BURIED_DEPTH_THRESHOLD = 8;
    private final TimerGame _stallSampleTimer = new TimerGame(STALL_SAMPLE_SECONDS);
    private final ArrayDeque<BlockPos> _stallSamples = new ArrayDeque<>();
    private boolean _pickaxeUpgradeActive = false;
    private Task _pickaxeSub = null;

    public DeathRecoveryTask(BlockPos gravePos, Dimension graveDim, int timeoutSeconds) {
        _gravePos = gravePos;
        _graveDim = graveDim;
        _timeoutSeconds = timeoutSeconds;
        _overallTimer = new TimerGame(timeoutSeconds);
        _noDropsTimer = new TimerGame(NO_DROPS_GIVE_UP_SECONDS);
    }

    // Accessors used by DeathRecoveryChain for match checking + finish cleanup.
    public BlockPos getGravePosition() { return _gravePos; }
    public Dimension getGraveDimension() { return _graveDim; }
    public int getTimeoutSeconds() { return _timeoutSeconds; }

    /** GOTO 中途镐子升级是否进行中 (链层闸门据此让路, 避免重复派搞镐任务)。 */
    public boolean isPickaxeUpgradeActive() { return _pickaxeUpgradeActive; }

    /**
     * (2026-09-12 动态镐门槛) 墓地是否深埋在地下: heightmap 地表 y 与墓 y
     * 之差超过 {@link #BURIED_DEPTH_THRESHOLD}。区块未加载时 heightmap 不可信,
     * 返回 false — 链层跳过镐门槛直接导航, 由 GOTO 中途失速升级兜底。
     */
    public static boolean isGraveBuried(AltoClef mod, BlockPos grave) {
        if (mod.getWorld().getChunk(grave.getX() >> 4, grave.getZ() >> 4) instanceof EmptyChunk) {
            return false;
        }
        int surfaceY = mod.getWorld().getTopPosition(Heightmap.Type.MOTION_BLOCKING, grave).getY();
        return surfaceY - grave.getY() > BURIED_DEPTH_THRESHOLD;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // (2026-09-17 进度保留 / 空窗永不收工根因修复)
        // onStart 会被调用两次: ①实例首次启动; ②被高优先级链(Mob Defense 等)
        // 抢占后恢复 —— SingleTaskChain 恢复时调 Task.reset() → _first=true →
        // 下一个 tick 再次进入 onStart (Task.java L99-105)。
        //
        // 原实现无条件把 _phase 打回 GOTO_GRAVE 并清零全部计时器, 实测
        // 20260917-134215 造成死循环: 到达墓碑 → 扫描不到掉落物(本就无掉落) →
        // 空窗 5s 还没攒满就被骷髅弓箭抢占 → 恢复后 _noDropsTimer 归零且 phase
        // 回 GOTO → 重新导航 → 再次空窗 → ... 5s 永远攒不满, bot 在墓碑旁反复
        // 打转直到被射死。总体超时(270s)同样被无限刷新而失效。
        //
        // 修法: 首次启动才做初始化; 恢复时保留进度(phase / 各计时器 / 失速采样 /
        // 镐子升级标志), 只丢弃子任务引用, 让当前 phase 按需重建子任务。
        if (_initialized) {
            Debug.logToFile("death_recovery.log", String.format(
                    "[TASK RESUME] phase=%s grave=%s dim=%s (progress kept across interruption)",
                    _phase, _gravePos, _graveDim));
            _currentSub = null;
            _pickaxeSub = null;
            return;
        }
        _initialized = true;
        _phase = Phase.GOTO_GRAVE;
        _currentSub = null;
        _overallTimer.reset();
        _noDropsTimer.reset();
        _everSawDropNearGrave = false;
        _degradeScanActive = false;
        _degradeScanTimer.reset();
        _arrivalEmpty = false;
        _arrivalEmptyTimer.reset();
        _pickaxeUpgradeActive = false;
        _pickaxeSub = null;
        _stallSamples.clear();
        _stallSampleTimer.reset();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        Debug.logToFile("death_recovery.log", String.format(
                "[TASK STOPPED] phase=%s interrupt=%s grave=%s dim=%s",
                _phase, interruptTask == null ? "null" : interruptTask.getClass().getSimpleName(),
                _gravePos, _graveDim));
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!_started) {
            _started = true;
            String msg = String.format(
                    "Starting death recovery: dim=%s pos=(%d,%d,%d) timeout=%ds",
                    _graveDim, _gravePos.getX(), _gravePos.getY(), _gravePos.getZ(),
                    _timeoutSeconds);
            Debug.logMessage(msg);
            Debug.logToFile("death_recovery.log", "[TASK START] " + msg);
        }

        // Hard bail-out: wrong dimension (unexpected portal trip mid-recovery
        // or similar). Don't attempt cross-dimension navigation; just abort.
        if (WorldHelper.getCurrentDimension() != _graveDim) {
            Debug.logWarning("Death recovery aborted: dimension changed from "
                    + _graveDim + " to " + WorldHelper.getCurrentDimension());
            Debug.logToFile("death_recovery.log", String.format(
                    "[TASK ABORT DIM] expected=%s now=%s grave=%s",
                    _graveDim, WorldHelper.getCurrentDimension(), _gravePos));
            _phase = Phase.DONE;
            return null;
        }

        // (2026-09-10 陈旧坐标修复, 用户"死亡地点是重生点这绝对是错的")
        // requestRespawn() 后服务端重生包未应用时, 客户端玩家坐标仍是死亡点
        // (DeathMenuChain L82-84 注释自证), graveReached 误判"已到墓碑"直接
        // 进 PICKUP, 实际 bot 在重生点瞎逛 (13:15 场次: ACTIVATE dist=0.0 →
        // PICKUP → 5s 无掉落 → 清墓碑记忆, 从此不再恢复; 22:27 场次
        // [ACTIVATE] dist=0.0 后 51ms 真实距离 289.1 为陈旧坐标铁证)。
        // PICKUP 阶段复核: 玩家离墓碑超出拾取半径+余量 → 退回 GOTO 重新导航。
        // 余量 4 格防止追边界掉落物时正常抖动触发回退。
        if (_phase == Phase.PICKUP
                && horizontalDistanceXZ(mod) > PICKUP_RADIUS + PICKUP_POSITION_MARGIN) {
            double dist = horizontalDistanceXZ(mod);
            Debug.logWarning(String.format(
                    "Death recovery: PICKUP phase but player is %.1f blocks from grave — stale respawn position, re-navigating.", dist));
            Debug.logToFile("death_recovery.log", String.format(
                    "[STALE POS] PICKUP but player %.1f from grave=%s; back to GOTO",
                    dist, _gravePos));
            _phase = Phase.GOTO_GRAVE;
            _noDropsTimer.reset();
            _degradeScanTimer.reset();
            _degradeScanActive = false;
            _arrivalEmpty = false;
            _currentPickupTarget = null;
            _stallSamples.clear();
            _stallSampleTimer.reset();
        }

        // Overall timeout safety net.
        if (_overallTimer.elapsed()) {
            adris.altoclef.util.helpers.TimeoutLog.hit("DeathRecoveryTask.overallTimeout",
                    "timeout=" + _timeoutSeconds + "s grave=" + _gravePos.toShortString() + " dim=" + _graveDim);
            Debug.logWarning(String.format(
                    "Death recovery timed out after %ds: giving up on grave (%d,%d,%d) dim=%s",
                    _timeoutSeconds, _gravePos.getX(), _gravePos.getY(), _gravePos.getZ(),
                    _graveDim));
            Debug.logToFile("death_recovery.log", String.format(
                    "[TASK TIMEOUT] timeout=%ds grave=%s dim=%s",
                    _timeoutSeconds, _gravePos, _graveDim));
            _phase = Phase.DONE;
            return null;
        }

        switch (_phase) {
            case GOTO_GRAVE:
                return tickGoToGrave(mod);
            case PICKUP:
                return tickPickup(mod);
            case DONE:
            default:
                return null;
        }
    }

    private Task tickGoToGrave(AltoClef mod) {
        if (graveReached(mod)) {
            setDebugState("Reached grave, scanning for dropped items...");
            Debug.logToFile("death_recovery.log", String.format(
                    "[GOTO DONE] arrived at grave=%s dim=%s player=%s",
                    _gravePos, _graveDim, mod.getPlayer().getBlockPos()));
            _phase = Phase.PICKUP;
            _noDropsTimer.reset();

            // (2026-09-17 到达即判定, 用户"到了死亡地点找不到掉落物, 于是原地打转")
            // 到达墓碑这一刻, 该区块已在加载中、实体已同步, 此时"感知(EntityTracker
            // 视线+FOV/声音)"与"直扫已加载实体"双双为空的话, 继续空窗等待或漫游都
            // 不会带来新信息 —— 直接收工。直扫沿用降级扫描的开关与 PICKUP_RADIUS
            // 同口径(范围锁死墓碑半径, 不是通用透视)。
            Optional<ItemEntity> immediate = findClosestDropNearGrave(mod, mod.getEntityTracker().getDroppedItems());
            boolean directScanUsed = false;
            if (immediate.isEmpty() && mod.getModSettings().shouldDeathRecoveryDegradeScan()) {
                directScanUsed = true;
                immediate = findClosestDropNearGrave(mod, scanLoadedDropsNearGrave(mod));
            }
            if (immediate.isEmpty()) {
                // (2026-09-29 多人首测) 旧口径是"到达即空 → 当场收工"(2026-09-17
                // 去 wander 的配套改动), 专用服下会在掉落物实体尚未同步 / 区块刚
                // 回载时抢跑: 现场 20260929-181904 整链 4ms 跑完 [PICKUP EMPTY] →
                // [FINISH CLEAR] 清碑, 掉落物永久丢失、且无任何重试。
                // 改为进入 PICKUP 并给一个有上限的重试窗口(站立复查, 不漫游),
                // 窗口内每 tick 复查, 过期才收工(见 tickPickup 的 giveUp 分支)。
                _phase = Phase.PICKUP;
                _arrivalEmpty = true;
                _arrivalEmptyTimer.reset();
                Debug.logToFile("death_recovery.log", String.format(
                        "[PICKUP EMPTY-RETRY] grave=%s dim=%s directScan=%s radius=%d — nothing on arrival; retrying %ds before giving up",
                        _gravePos, _graveDim, directScanUsed ? "Y" : "N", PICKUP_RADIUS,
                        ARRIVAL_EMPTY_RETRY_SECONDS));
                return null;
            }
            // 有目标: 交给 PICKUP 阶段的粘性目标逻辑继续追。
            if (!_everSawDropNearGrave) {
                _everSawDropNearGrave = true;
            }
            _arrivalEmpty = false;
            _currentPickupTarget = immediate.get();
            return null;
        }

        // (2026-09-12 GOTO 中途升级镐子) 升级激活期间: 镐到位 → 重新导航;
        // 未到位 → 继续搞镐 (实例缓存, isEqual 挡下重复重建)。
        if (_pickaxeUpgradeActive) {
            if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                Debug.logToFile("death_recovery.log", String.format(
                        "[PICKAXE UPGRADE DONE] stone-tier tool acquired; re-navigating to grave=%s",
                        _gravePos));
                _pickaxeUpgradeActive = false;
                _pickaxeSub = null;
                _stallSamples.clear();
                _stallSampleTimer.reset();
                _currentSub = new GetToBlockTask(_gravePos, _graveDim);
                return _currentSub;
            }
            if (_pickaxeSub == null || _pickaxeSub.isFinished(mod) || _pickaxeSub.stopped()) {
                _pickaxeSub = new SatisfyMiningRequirementTask(MiningRequirement.STONE,
                        "death-recovery-task/buried-grave");
            }
            setDebugState("Buried grave & stalled: getting a pickaxe first");
            return _pickaxeSub;
        }

        // 失速采样: 每 5s 记录一次位置, 窗口 20s; 净位移 <3 格视为原地打转。
        // (实例跨 GetToBlockTask 重建存活 — wander 循环里 GetToBlockTask 反复
        // 重建, 锚点必须留在本层才能看到"4 分钟零进展"。)
        if (_stallSampleTimer.elapsed()) {
            _stallSampleTimer.reset();
            _stallSamples.addLast(mod.getPlayer().getBlockPos());
            while (_stallSamples.size() > STALL_WINDOW_SAMPLES) {
                _stallSamples.removeFirst();
            }
            if (_stallSamples.size() == STALL_WINDOW_SAMPLES
                    && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)
                    && isGraveBuried(mod, _gravePos)) {
                BlockPos oldest = _stallSamples.peekFirst();
                BlockPos now = mod.getPlayer().getBlockPos();
                double net = Math.hypot(now.getX() - oldest.getX(), now.getZ() - oldest.getZ());
                if (net < STALL_NET_MOVEMENT) {
                    _pickaxeUpgradeActive = true;
                    _pickaxeSub = new SatisfyMiningRequirementTask(MiningRequirement.STONE,
                            "death-recovery-task/goto-stall");
                    _stallSamples.clear();
                    Debug.logWarning(String.format(
                            "Death recovery GOTO stalled (%.1f blocks net in %ds) with no pickaxe and buried grave — acquiring stone pickaxe first.",
                            net, STALL_SAMPLE_SECONDS * STALL_WINDOW_SAMPLES));
                    Debug.logToFile("death_recovery.log", String.format(
                            "[PICKAXE UPGRADE START] stalled net=%.1f over %ds grave=%s; running SatisfyMiningRequirementTask(STONE)",
                            net, STALL_SAMPLE_SECONDS * STALL_WINDOW_SAMPLES, _gravePos));
                    setDebugState("Buried grave & stalled: getting a pickaxe first");
                    return _pickaxeSub;
                }
            }
        }

        if (_currentSub == null || _currentSub.isFinished(mod) || !(_currentSub instanceof GetToBlockTask)) {
            _currentSub = new GetToBlockTask(_gravePos, _graveDim);
            Debug.logToFile("death_recovery.log", String.format(
                    "[GOTO START] navigating to grave=%s dim=%s currentDist=%.1f",
                    _gravePos, _graveDim, horizontalDistanceXZ(mod)));
        }
        setDebugState(String.format(
                "Going to death point (%.1f blocks away)", horizontalDistanceXZ(mod)));
        return _currentSub;
    }

    private Task tickPickup(AltoClef mod) {
        List<ItemEntity> allDrops = mod.getEntityTracker().getDroppedItems();
        Optional<ItemEntity> closest = findClosestDropNearGrave(mod, allDrops);

        // (2026-09-10 水下掉落物感知盲区, 用户"死亡地点在水里") 拟人感知闸门
        // (EntityTracker: 视线射线+FOV 或 声音) 在水下洞穴对掉落物全线失效:
        // 掉落物无声, FOV 随重生朝向随机, LOS 被洞壁挡 → getDroppedItems() 恒空
        // (13:15 场次: 墓碑旁 5s 零感知)。降级扫描: bot 站在自己刚死的墓碑旁,
        // "我的东西就在这附近"是人类合法知识 (WorldMemory 记忆), 不依赖感知,
        // 直接扫已加载实体。范围锁死 PICKUP_RADIUS, 开关
        // deathRecoveryDegradeScan (默认 true), 全程带 [DEGRADE SCAN] 日志。
        // 激活后每 tick 都扫 (只受感知为空门控), 否则水底逐件拾取会每件卡 5s。
        if (closest.isEmpty() && mod.getModSettings().shouldDeathRecoveryDegradeScan()) {
            boolean activateNow = !_everSawDropNearGrave && _noDropsTimer.elapsed();
            if (_degradeScanActive || activateNow) {
                if (!_degradeScanActive) {
                    _degradeScanActive = true;
                    _degradeScanTimer.reset();
                    Debug.logToFile("death_recovery.log", String.format(
                            "[DEGRADE SCAN START] perception found nothing for %ds near grave=%s; scanning loaded entities directly",
                            NO_DROPS_GIVE_UP_SECONDS, _gravePos));
                }
                closest = findClosestDropNearGrave(mod, scanLoadedDropsNearGrave(mod));
                if (closest.isPresent()) {
                    ItemEntity hit = closest.get();
                    Debug.logToFile("death_recovery.log", String.format(
                            "[DEGRADE SCAN HIT] %s x%d (perception was blind)",
                            hit.getStack().getItem().getTranslationKey(), hit.getStack().getCount()));
                }
            }
        }

        if (closest.isEmpty()) {
            boolean giveUp;
            if (_arrivalEmpty) {
                // (2026-09-29 多人首测) "到达时空手" 的有上限重试窗口 —— 见
                // ARRIVAL_EMPTY_RETRY_SECONDS 与 tickGoToGrave 到达分支。
                giveUp = _arrivalEmptyTimer.elapsed();
            } else if (_everSawDropNearGrave) {
                // 见过掉落物 (感知曾正常工作) → 现在没了 = 捡完/消失, 5s 收工。
                giveUp = _noDropsTimer.elapsed();
            } else if (_degradeScanActive) {
                // 降级扫描也连续扫不到 → 掉落物真没了 (岩浆烧毁/被捡走)。
                giveUp = _degradeScanTimer.elapsed();
            } else {
                // 降级开关关闭: 维持旧的 5s 收工行为。
                giveUp = _noDropsTimer.elapsed();
            }
            if (giveUp) {
                if (_arrivalEmpty) {
                    Debug.logToFile("death_recovery.log", String.format(
                            "[PICKUP EMPTY-GIVEUP] grave=%s dim=%s retried=%ss directScan=%s — still nothing, finishing",
                            _gravePos, _graveDim, ARRIVAL_EMPTY_RETRY_SECONDS,
                            _degradeScanActive ? "Y" : "N"));
                }
                Debug.logMessage(String.format(
                        "No item drops found within %d blocks of grave (%d,%d,%d) — giving up.%s",
                        PICKUP_RADIUS, _gravePos.getX(), _gravePos.getY(), _gravePos.getZ(),
                        _everSawDropNearGrave ? " (drops were seen & handled)"
                                : (_degradeScanActive ? " (degrade scan also exhausted)"
                                      : " (perception never saw any; degrade scan off)")));
                Debug.logToFile("death_recovery.log", String.format(
                        "[PICKUP DONE] grave=%s dim=%s everSaw=%s degradeScan=%s",
                        _gravePos, _graveDim, _everSawDropNearGrave, _degradeScanActive));
                _phase = Phase.DONE;
                return null;
            }
            double waited = Math.min(NO_DROPS_GIVE_UP_SECONDS, _noDropsTimer.getDuration());
            if (_arrivalEmpty) {
                setDebugState("At grave but no drops — retrying up to "
                        + ARRIVAL_EMPTY_RETRY_SECONDS + "s (standing still)");
            } else {
                setDebugState(String.format(
                        "Near grave — waiting to see drops (%.0fs / %ds)",
                        waited, NO_DROPS_GIVE_UP_SECONDS));
            }
            // (2026-09-17 去 wander, 用户"原地打转") 旧实现在这段空窗里跑缓存的
            // TimeoutWanderTask(20) 漫游 —— 用户看到的就是"在墓碑旁来回打转"。
            // 空窗的作用只是给掉落物一个进入感知/区块的确认时间, 站在墓碑旁等
            // 就已经足够(人类也是站定环顾), 漫游既不加速确认, 一旦被高优先级链
            // 抢占还要从头再来。这里改为原地待机, 返回 null 不派任何子任务。
            return null;
        }

        // We have a target. Restart the "no drops" timers — as long as we
        // keep finding stuff we keep going.
        _noDropsTimer.reset();
        _degradeScanTimer.reset();
        _arrivalEmpty = false;
        if (!_everSawDropNearGrave) {
            _everSawDropNearGrave = true;
        }
        ItemEntity target = closest.get();

        // (2026-09-08 腾位接入, 用户"背包满了为什么不丢东西") 满背包时走近
        // 掉落物也吸不进 (物品留在地上), 只会原地打转。原实现直接 GetToEntity
        // 完全没有容量检查 —— "背包满→先腾位"逻辑只存在于 PickupDroppedItemTask,
        // 死亡捡回链路绕过了它。背包放不下该掉落物 → 先走 EnsureFreeInventorySlot
        // (丢垃圾/穿甲/合并级联), 腾出格子再捡。
        if (mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(target.getStack(), false).isEmpty()) {
            setDebugState("Inventory full — making room before picking up "
                    + target.getStack().getItem().getTranslationKey());
            Debug.logToFile("death_recovery.log", String.format(
                    "[PICKUP FULL] stack=%s x%d; running EnsureFreeInventorySlotTask",
                    target.getStack().getItem().getTranslationKey(), target.getStack().getCount()));
            if (!(_currentSub instanceof EnsureFreeInventorySlotTask)) {
                _currentSub = new EnsureFreeInventorySlotTask();
            }
            return _currentSub;
        }

        // Reuse the current GetToEntityTask if it's already chasing the
        // exact same ItemEntity reference — saves tearing down / rebuilding
        // the baritone goal every tick.
        boolean sameTarget = _currentPickupTarget == target;
        if (!sameTarget || !(_currentSub instanceof GetToEntityTask) || _currentSub.isFinished(mod)) {
            Debug.logInternal("Death recovery: new pickup target "
                    + target.getStack().getItem().getTranslationKey()
                    + " stack=" + target.getStack().getCount());
            _currentPickupTarget = target;
            _currentSub = new GetToEntityTask(target);
        }
        setDebugState(String.format(
                "Picking up: %s x%d",
                target.getStack().getItem().getName().getString(),
                target.getStack().getCount()));
        return _currentSub;
    }

    // ------------------------------------------------------------------ //
    // Completion + helpers
    // ------------------------------------------------------------------ //

    @Override
    public boolean isFinished(AltoClef mod) {
        return _phase == Phase.DONE;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DeathRecoveryTask task) {
            return _gravePos.equals(task._gravePos)
                    && _graveDim == task._graveDim
                    && _timeoutSeconds == task._timeoutSeconds;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "DeathRecoveryTask{phase=" + _phase
                + ", pos=" + _gravePos
                + ", dim=" + _graveDim
                + ", timeout=" + _timeoutSeconds + "s}";
    }

    /** Horizontal (XZ) distance between the player and the grave. */
    private double horizontalDistanceXZ(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        double dx = p.getX() - _gravePos.getX();
        double dz = p.getZ() - _gravePos.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * (2026-09-10 降级扫描) 绕过 EntityTracker 的拟人感知闸门, 直接扫描
     * 已加载实体中墓碑 PICKUP_RADIUS 范围内的掉落物。仅用于"感知从未建立"
     * 的墓碑场景 ("我死在这, 东西就在附近" = WorldMemory 人类合法知识),
     * 不是通用 X-ray: 范围锁死半径, 只在 deathRecoveryDegradeScan 开启且
     * 感知短窗口到期后才启用, 每次启用/命中都落盘 [DEGRADE SCAN] 日志。
     */
    private List<ItemEntity> scanLoadedDropsNearGrave(AltoClef mod) {
        List<ItemEntity> result = new ArrayList<>();
        if (MinecraftClient.getInstance().world == null) return result;
        Vec3d centre = Vec3d.ofCenter(_gravePos);
        double radiusSq = (double) PICKUP_RADIUS * PICKUP_RADIUS;
        for (Entity entity : MinecraftClient.getInstance().world.getEntities()) {
            if (entity instanceof ItemEntity item && item.isAlive()
                    && item.squaredDistanceTo(centre) <= radiusSq) {
                result.add(item);
            }
        }
        return result;
    }

    /** True once the player is close enough to the grave that nearby drops are in render range. */
    private boolean graveReached(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        int dx = Math.abs(p.getX() - _gravePos.getX());
        int dz = Math.abs(p.getZ() - _gravePos.getZ());
        int dy = Math.abs(p.getY() - _gravePos.getY());
        return dx <= GRAVE_REACHED_HORIZONTAL
                && dz <= GRAVE_REACHED_HORIZONTAL
                && dy <= GRAVE_REACHED_VERTICAL;
    }

    /**
     * Finds the nearest tracked ItemEntity that is within PICKUP_RADIUS blocks
     * of the grave (euclidean XZ distance — close enough that the walk is
     * trivial). Falls back to the player's current position when no drops
     * exist near the grave.
     *
     * (2026-09-08 粘性目标修复) 死亡掉落物是一堆散件, 两个距离几乎相同的掉落物
     * 每 tick 交替成为"最近" → sameTarget 判定失败 → 每 tick 重建 GetToEntityTask
     * → TaskPingPong 5 秒切 6 次 (09:41 日志实锤), bot 永远走不到任何一个。
     * 修复: ① 距离与最近者相近 (1.25x 内) 的都算并列候选, 当前目标仍在候选窗
     * 内就保持不换; ② 真正并列时用实体 id 稳定 tie-break。
     */
    private Optional<ItemEntity> findClosestDropNearGrave(AltoClef mod, List<ItemEntity> drops) {
        Vec3d centre = Vec3d.ofCenter(_gravePos);
        double radiusSq = (double) PICKUP_RADIUS * PICKUP_RADIUS;
        double bestSq = Double.POSITIVE_INFINITY;
        for (ItemEntity drop : drops) {
            if (drop == null || !drop.isAlive()) continue;
            double dsq = drop.squaredDistanceTo(centre);
            if (dsq <= radiusSq && dsq < bestSq) {
                bestSq = dsq;
            }
        }
        if (bestSq == Double.POSITIVE_INFINITY) {
            return Optional.empty();
        }
        List<ItemEntity> candidates = new ArrayList<>();
        for (ItemEntity drop : drops) {
            if (drop == null || !drop.isAlive()) continue;
            if (drop.squaredDistanceTo(centre) <= bestSq * 1.25) {
                candidates.add(drop);
            }
        }
        // 粘性: 当前目标仍有效且还在候选窗内 → 保持, 不换
        if (_currentPickupTarget != null && _currentPickupTarget.isAlive()
                && candidates.contains(_currentPickupTarget)) {
            return Optional.of(_currentPickupTarget);
        }
        // 并列 tie-break 用实体 id, 保证跨 tick 稳定
        return candidates.stream().min(Comparator.comparingInt(Entity::getId));
    }
}
