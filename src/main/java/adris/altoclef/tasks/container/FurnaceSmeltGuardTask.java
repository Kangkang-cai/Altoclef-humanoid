package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatMemory;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * (2026-09-07 熔炉守卫, 用户"烧东西时不能一直在烧制界面, 要站旁边旋转观察四周")
 * 烧制等待期的守卫子任务, 替换 containerSubTask 末尾的 "Waiting... return null"
 * (旧实现整个烧制期间熔炉界面常开 —— bot 站炉前不动, 所有点击进 UI, 遇袭无法战斗)。
 *
 * 职责:
 *  ① 首 tick 关掉熔炉界面 (交还鼠标/准心, MobDefenseChain 得以正常工作);
 *  ② 守在炉边, 每 ~2s 随机转向扫视四周 (拟人观察, EntityTracker 靠视觉感知威胁);
 *  ③ 感知到 ≤16 格敌对生物 → 派发 KillEntityTask 子任务 (用户拍板 16 格内派杀);
 *  ④ 打斗期间驻留计时暂停, 威胁解除后重新计时;
 *  ⑤ 无威胁驻留 ~6s 后 isFinished → 父任务 (DoStuffInContainerTask) 自然走
 *     "walk & reopen" 分支重开熔炉 → 现有 containerSubTask 收输出/补燃料,
 *     循环往复直至 isFurnaceDrained 排空结束。
 *
 * 低风险要点: 不改任何槽位点击/燃料计算/缓存逻辑; 击杀是本任务的子任务
 * (返回子任务不触发父任务 onStart, _furnaceCache 等状态存活); 界面关闭由
 * 父任务每 tick 重判 isContainerOpen 自然衔接。
 */
public class FurnaceSmeltGuardTask extends Task {

    // (2026-09-07 用户拍板) 16 格内的敌对生物主动派杀, 更远的交给并行的 MobDefenseChain。
    private static final double THREAT_DISPATCH_RANGE = 16;
    // 无威胁驻留时长: 到点重开熔炉收货/补料。产物最多在炉里多躺一个周期,
    // 这是"不盯界面"的固有代价。
    private static final double DWELL_SECONDS = 6;
    // 扫视间隔: 每 2s 随机转向一次, 模拟真人警戒环视。
    private static final double SCAN_INTERVAL_SECONDS = 2;
    // 离炉超过这个距离就先走回来 (追击杀完怪后可能站得远)。
    private static final double RETURN_TO_FURNACE_DISTANCE = 4;
    // (2026-09-07 击杀承诺粘性) 承诺的杀目标从感知列表消失超过该 tick 数
    // (3s) 才撤销承诺 —— 感知列表会因视角/遮挡抖动, 没有粘性会
    // "Dispatching kill ↔ Returning to furnace" 高频横跳 (18:04 日志实锤)。
    private static final long COMMIT_LOST_TICKS = 60;

    private final BlockPos _furnacePos;

    // (2026-09-11) 当前正在守卫的工作炉位置 — 静态跨层信号 (同 MineDownExploreTask
    // getLastFireAnchor 先例)。用途: BeatMinecraft2Task 进下界排空闸门豁免冶炼链
    // 自家的炉。125529 场次实锤: nether 链为做岩浆桶放炉投料 → 排空闸门看到非空炉
    // 抢占拆炉 → 链重建再放再投 → 2.1s 周期互抢 12 分钟零进度。守卫存活期间闸门
    // 跳过该炉, 烧完收集后守卫停止 → 静态清除 → 闸门恢复正常职责。
    private static BlockPos _activeGuardedPos = null;

    /** 当前有守卫在值的工作炉位置; 无守卫返回 null。 */
    public static BlockPos getActiveGuardedFurnacePos() {
        return _activeGuardedPos;
    }
    private final TimerGame _dwellTimer = new TimerGame(DWELL_SECONDS);
    private final TimerGame _scanTimer = new TimerGame(SCAN_INTERVAL_SECONDS);
    private boolean _screenClosed = false;
    // 当前承诺击杀的目标 (killGiveUpSeconds 超时放弃后由 CombatMemory 冷却解除)。
    private Entity _committedKill = null;
    private long _committedLastSeenTick = Long.MIN_VALUE;

    public FurnaceSmeltGuardTask(BlockPos furnacePos) {
        _furnacePos = furnacePos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _screenClosed = false;
        _dwellTimer.reset();
        _scanTimer.reset();
        _committedKill = null;
        _committedLastSeenTick = Long.MIN_VALUE;
        _activeGuardedPos = _furnacePos;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // ① 首 tick: 关熔炉界面 (若开着)。先消耗一个 tick 让 closeScreen 生效,
        // 之后 isContainerOpen=false; 父任务的重判由守卫接管分支压住
        // (见 DoSmeltInFurnaceTask.onTick 注释)。
        if (!_screenClosed) {
            if (mod.getPlayer() != null && mod.getPlayer().currentScreenHandler != null) {
                StorageHelper.closeScreen();
            }
            _screenClosed = true;
            _dwellTimer.reset();
            setDebugState("Closing furnace screen to guard");
            return null;
        }

        // ② 击杀承诺维持: 一旦承诺杀某目标就粘住 —— 只要它还在感知列表
        // (3s 内见过) 且未进入超时冷却, 就继续杀它, 不因感知抖动退回守卫。
        long now = WorldHelper.getTicks();
        if (_committedKill != null) {
            if (CombatMemory.isUnreachable(_committedKill.getUuid())) {
                // 击杀任务超时放弃了它 (KillTimeout) → 解除承诺, 冷却期内不再选它。
                _committedKill = null;
            } else if (isTrackedAsHostile(mod, _committedKill)) {
                _committedLastSeenTick = now;
            } else if (!_committedKill.isAlive() || now - _committedLastSeenTick > COMMIT_LOST_TICKS) {
                _committedKill = null;
            }
        }
        if (_committedKill != null) {
            _dwellTimer.reset();
            setDebugState("Killing committed threat: " + _committedKill.getType().getTranslationKey());
            return new KillEntityTask(_committedKill);
        }

        // ③ 威胁派杀: ≤16 格内有感知到的敌对生物 → 承诺并打它 (KillEntityTask
        // 按目标实体 isEqual 复用同实例, 不会每 tick 重启)。打斗期间驻留计时
        // 重置 —— 威胁解除后重新数满一个驻留周期再回炉。
        Optional<Entity> threat = findNearestThreat(mod);
        if (threat.isPresent()) {
            _committedKill = threat.get();
            _committedLastSeenTick = now;
            _dwellTimer.reset();
            setDebugState("Dispatching kill: " + threat.get().getType().getTranslationKey());
            return new KillEntityTask(threat.get());
        }

        // ④ 追击杀完可能站远了, 先走回炉边再守。
        if (_furnacePos != null
                && _furnacePos.getSquaredDistance(mod.getPlayer().getBlockPos()) > RETURN_TO_FURNACE_DISTANCE * RETURN_TO_FURNACE_DISTANCE) {
            setDebugState("Returning to furnace");
            return new GetToBlockTask(_furnacePos, true);
        }

        // ⑤ 扫视: 周期性随机转向, 让视觉感知覆盖四周。
        if (_scanTimer.elapsed()) {
            LookHelper.randomOrientation(mod);
            _scanTimer.reset();
        }

        setDebugState("Guarding furnace, scanning surroundings");
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 驻留期满且当前没有在打怪 (打斗期 _dwellTimer 被持续重置) → 交给
        // 父任务重开熔炉收货。
        return _screenClosed && _dwellTimer.elapsed();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 界面已在本任务里被关掉; 若被外部打断, 不强行重开 —— 父任务
        // (DoStuffInContainerTask) 的 walk&open 分支会自然重开。
        if (_activeGuardedPos != null && _activeGuardedPos.equals(_furnacePos)) {
            _activeGuardedPos = null;
        }
    }

    /**
     * 最近的可派杀威胁: 感知到的敌对生物且 ≤16 格, 且不在击杀超时冷却中
     * (CombatMemory)。
     * (EntityTracker 的感知即"看过/听过", 符合拟人感知原则, 不用透视。)
     */
    private Optional<Entity> findNearestThreat(AltoClef mod) {
        Entity best = null;
        double bestDistSq = THREAT_DISPATCH_RANGE * THREAT_DISPATCH_RANGE;
        for (Entity hostile : mod.getEntityTracker().getHostiles()) {
            if (hostile == null || !hostile.isAlive()) continue;
            if (CombatMemory.isUnreachable(hostile.getUuid())) continue;
            double distSq = hostile.squaredDistanceTo(mod.getPlayer());
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = hostile;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * (2026-09-07 承诺粘性) 目标是否仍在感知列表里 (按 UUID 比对, 不依赖
     * Entity 实例引用)。
     */
    private boolean isTrackedAsHostile(AltoClef mod, Entity entity) {
        if (entity == null) return false;
        for (Entity hostile : mod.getEntityTracker().getHostiles()) {
            if (hostile != null && hostile.getUuid().equals(entity.getUuid())) return true;
        }
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof FurnaceSmeltGuardTask task) {
            return task._furnacePos.equals(_furnacePos);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Guarding furnace @" + (_furnacePos == null ? "?" : _furnacePos.toShortString());
    }
}
