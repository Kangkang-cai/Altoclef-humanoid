package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.chains.FoodChain;
import adris.altoclef.record.PerceptionSampler;
import adris.altoclef.tasks.CraftGenericWithRecipeBooksTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.movement.BearingExpeditionTask;
import adris.altoclef.tasks.movement.BiasedExploreTask;
import adris.altoclef.tasks.movement.FortressExploreTask;
import adris.altoclef.tasks.movement.MineDownExploreTask;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.util.helpers.HitRegistry;
import adris.altoclef.util.helpers.SurfaceHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldIdentity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * (2026-09-17 跨世界残留状态) 世界边界守卫 —— 换存档时的一次性全量刷新入口。
 *
 * 为什么需要它: 换存档(不重启游戏)时, `AltoClef` 实例与全部 tracker / chain
 * 都不重建(`onInitializeLoad` 只由 TitleScreenEntryEvent 触发一次), 而 Java
 * static 字段在整个 JVM 生命周期内存活。于是上个存档的记忆 / 冷却 / 账本被
 * 原样带进新存档。其中最危险的一类是【坐标】: 上个世界的门 / 堡垒 / 远征锚点
 * 被当成新世界的导航目标(2026-09-10 实锤 650 格外假寻路卡死 29s)。
 *
 * 与 `TrackerManager.tick()` 既有"离开世界即 reset"分支的分工:
 *  - TrackerManager 清【实例级】tracker(只在 inGame 由 true→false 时触发一次);
 *  - 本类清【static】状态, 判据是世界标识本身变化, 与 inGame 翻转解耦 ——
 *    即使"退出后立刻进另一个存档"这种窄窗口也不会漏。
 *
 * 刷新语义(用户 2026-09-17 拍板 = "丢弃"):
 *  - 清空内存中的记忆, **不**从磁盘重载 —— 新存档从零开始积累(亲眼看见才记);
 *  - 各记忆类的 `_loaded` 标志保持 true, 正是"不重载"的实现手段;
 *  - 进程重启(新 JVM)不受影响, 跨会话持久化的原设计保留。
 *
 * 注册表是【集中清单】而非各类自注册: 一眼能看到"哪些状态会跨世界重置",
 * 新增状态只需在这里加一行 + 目标类加一个 static 清理方法。
 */
public final class WorldStateGuard {

    /** 上一次见到的存档目录名。null = 本进程还没进过任何世界(首次进入不触发清理)。 */
    private static String _currentSave = null;
    private static boolean _registered = false;
    private static final Map<String, Runnable> _resetters = new LinkedHashMap<>();

    private WorldStateGuard() {
    }

    /** 每 tick 调用(AltoClef.onClientTick)。开销 = 一次目录名字符串比较。 */
    public static void tick(AltoClef mod) {
        if (!AltoClef.inGame()) return;
        if (!_registered) {
            _registered = true;
            registerAll();
        }
        String now = WorldIdentity.saveDirName();
        if (_currentSave == null) {
            _currentSave = now;
            return;
        }
        if (now.equals(_currentSave)) return;

        String from = _currentSave;
        _currentSave = now;
        int ok = 0, failed = 0;
        for (Map.Entry<String, Runnable> e : _resetters.entrySet()) {
            try {
                e.getValue().run();
                ++ok;
            } catch (Throwable t) {
                ++failed;
                Debug.logWarning("[WorldGuard] reset failed: " + e.getKey() + " (" + t + ")");
            }
        }
        Debug.logMessage("[WorldGuard] world changed " + from + " -> " + now
                + ", cleared " + ok + " states"
                + (failed > 0 ? " (" + failed + " failed)" : ""));
    }

    private static void register(String name, Runnable reset) {
        _resetters.put(name, reset);
    }

    /**
     * 跨世界需要清空的 static 状态清单。分三类:
     *  - 记忆类: 坐标/区域, 清了最安全(丢弃语义);
     *  - 冷却 / 哨兵: 时间戳与布尔, 不清会误压制新世界的行为;
     *  - 账本 / 缓存: 会认领已不存在的容器或方块。
     * 故意不清的: 玩家配置(Settings)、白名单(UserAuth)、配方表(ItemHelper 静态
     * 映射)、MiningRequirement 缓存 —— 与存档无关, 重建纯浪费。
     */
    private static void registerAll() {
        // ---- 记忆类(丢弃: 清内存, 保持 _loaded=true 以阻止磁盘重载) ----
        register("KnowledgeMemory(地标中心)", KnowledgeMemory::onWorldBoundary);
        register("CollectBlazeRodsTask(烈焰刷怪笼确认/排除)", CollectBlazeRodsTask::onWorldBoundary);
        register("FortressExploreTask(走廊/远征锚点)", FortressExploreTask::onWorldBoundary);
        register("MineDownExploreTask(矿洞/自挖)", MineDownExploreTask::onWorldBoundary);
        register("BearingExpeditionTask(方位/锚点)", BearingExpeditionTask::onWorldBoundary);
        register("PortalMemoryStore(传送门)", PortalMemoryStore::onWorldBoundary);

        // ---- 冷却 / 哨兵 / 账本(危害: 误压制新世界的行为) ----
        register("CraftGenericWithRecipeBooksTask(合成放弃/格归属)",
                CraftGenericWithRecipeBooksTask::onWorldBoundary);
        register("SmeltInFurnaceTask(熔炉冷却/在途账本)", SmeltInFurnaceTask::onWorldBoundary);
        register("FoodChain(进食哨兵)", FoodChain::onWorldBoundary);
        register("HitRegistry(猪灵愤怒)", HitRegistry::reset);

        // ---- 缓存(危害: 陈旧坐标命中) ----
        register("VisibilityHelper(LOS 缓存)", VisibilityHelper::onWorldBoundary);
        register("PerceptionSampler(感知快照)", PerceptionSampler::onWorldBoundary);
        register("SurfaceHelper(地表判据迟滞)", SurfaceHelper::onWorldBoundary);
        register("BiasedExploreTask(够不着的目标拉黑表)", BiasedExploreTask::onWorldBoundary);
    }
}
