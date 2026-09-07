package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
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

    private final BlockPos _furnacePos;
    private final TimerGame _dwellTimer = new TimerGame(DWELL_SECONDS);
    private final TimerGame _scanTimer = new TimerGame(SCAN_INTERVAL_SECONDS);
    private boolean _screenClosed = false;

    public FurnaceSmeltGuardTask(BlockPos furnacePos) {
        _furnacePos = furnacePos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _screenClosed = false;
        _dwellTimer.reset();
        _scanTimer.reset();
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

        // ② 威胁派杀: ≤16 格内有感知到的敌对生物 → 打它 (KillEntityTask 按
        // 目标实体 isEqual 复用同实例, 不会每 tick 重启)。打斗期间驻留计时
        // 重置 —— 威胁解除后重新数满一个驻留周期再回炉。
        Optional<Entity> threat = findNearestThreat(mod);
        if (threat.isPresent()) {
            _dwellTimer.reset();
            setDebugState("Dispatching kill: " + threat.get().getType().getTranslationKey());
            return new KillEntityTask(threat.get());
        }

        // ③ 追击杀完可能站远了, 先走回炉边再守。
        if (_furnacePos != null
                && _furnacePos.getSquaredDistance(mod.getPlayer().getBlockPos()) > RETURN_TO_FURNACE_DISTANCE * RETURN_TO_FURNACE_DISTANCE) {
            setDebugState("Returning to furnace");
            return new GetToBlockTask(_furnacePos, true);
        }

        // ④ 扫视: 周期性随机转向, 让视觉感知覆盖四周。
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
    }

    /**
     * 最近的可派杀威胁: 感知到的敌对生物且 ≤16 格。
     * (EntityTracker 的感知即"看过/听过", 符合拟人感知原则, 不用透视。)
     */
    private Optional<Entity> findNearestThreat(AltoClef mod) {
        Entity best = null;
        double bestDistSq = THREAT_DISPATCH_RANGE * THREAT_DISPATCH_RANGE;
        for (Entity hostile : mod.getEntityTracker().getHostiles()) {
            if (hostile == null || !hostile.isAlive()) continue;
            double distSq = hostile.squaredDistanceTo(mod.getPlayer());
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = hostile;
            }
        }
        return Optional.ofNullable(best);
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
