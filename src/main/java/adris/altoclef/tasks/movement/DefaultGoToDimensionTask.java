package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Some generic tasks require us to go to the nether/overworld/end.
 * <p>
 * The user should be able to specify how this should be done in settings
 * (ex, craft a new portal from scratch or check particular portal areas first or highway or whatever)
 */
public class DefaultGoToDimensionTask extends Task {

    private final Dimension _target;
    // Cached to keep build properties alive if this task pauses/resumes.
    private final Task _cachedNetherBucketConstructionTask = new ConstructNetherPortalBucketTask();

    // (2026-09-20 搭了两块就跑去翻旧门) 下界回家"直接造门"的粘滞闩。
    // canBuildPortalHome 是**瞬时**判据 (背包黑曜石 ≥ 10), 而搭门本身会消耗黑曜石;
    // 内层 ConstructNetherPortalObsidianTask 用的是另一个口径 (还需要 10 - 已就位),
    // 两者在搭门过程中必然分叉。实测 20260920-205755:
    //   21:02:09.489 背包 11 块过闸 "building portal home directly"
    //   21:02:09.539 放第 1 块 (剩 10)
    //   21:02:11.585 放第 2 块 (剩 9)
    //   21:02:13.291 外层重判 canBuildPortalHome=false → 撤回构造任务转
    //                "Going to last nether portal pos" (跑去 -18,72,32 的旧门)
    // 门只搭了两块就永远搭不完。修 = 一旦过闸决定直造就锁住该决策, 仅超时兜底
    // 释放 (门已建好时由维度切换自然结束本任务)。
    private boolean _buildingPortalHome = false;
    private long _portalHomeBuildStartMs = 0L;
    private static final long PORTAL_HOME_BUILD_TIMEOUT_MS = 180_000;

    public DefaultGoToDimensionTask(Dimension target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() == _target) return null;

        switch (_target) {
            case OVERWORLD:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        return goToOverworldFromNetherTask(mod);
                    case END:
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case NETHER:
                switch (WorldHelper.getCurrentDimension()) {
                    case OVERWORLD:
                        return goToNetherFromOverworldTask(mod);
                    case END:
                        // First go to the overworld
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case END:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        // First go to the overworld
                        return goToOverworldFromNetherTask(mod);
                    case OVERWORLD:
                        return goToEndTask(mod);
                }
                break;
        }

        setDebugState(WorldHelper.getCurrentDimension() + " -> " + _target + " is NOT IMPLEMENTED YET!");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DefaultGoToDimensionTask task) {
            return task._target == _target;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Going to dimension: " + _target + " (default version)";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == _target;
    }

    private Task goToOverworldFromNetherTask(AltoClef mod) {
        // (2026-09-13 用户"黑曜石够且有打火石/材料就直接造门, 别走原来的门")
        // 有料直接原地造门回家, 不再长途跋涉去翻 last-used / 记忆旧门 (旧门
        // 记忆可能过期/死门, 徒步也可能几百上千格)。唯一例外: 眼前 64 格内就
        // 有已点亮的门, 走进去比烧 10 块黑曜石划算, 人类也会直接进。
        // 材料闸收死: 黑曜石 ≥10, 且有打火石或"铁锭+燧石"能现场合成 ——
        // 绝不让 ConstructNetherPortalObsidianTask 在下界触发黑曜石收集
        // (那条路绕猪灵交易, 是另一个阶段的事)。
        // 粘滞闩 (见字段 _buildingPortalHome 注释): 过闸后不再因背包数量下降撤回。
        // 超时兜底: 锁了这么久还没进主世界, 放弃直造回到旧的找门链。
        if (_buildingPortalHome && System.currentTimeMillis() - _portalHomeBuildStartMs > PORTAL_HOME_BUILD_TIMEOUT_MS) {
            Debug.logMessage("(PortalHome) Direct build latched for " + (PORTAL_HOME_BUILD_TIMEOUT_MS / 1000)
                    + "s without finishing; falling back to portal search chain.");
            Debug.logToFile("altoclef_diag.log", "[PortalHome]\tlatch TIMEOUT " + PORTAL_HOME_BUILD_TIMEOUT_MS
                    + "ms, release to portal search chain");
            _buildingPortalHome = false;
        }

        if (_buildingPortalHome || canBuildPortalHome(mod)) {
            Optional<BlockPos> litNearby = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), Blocks.NETHER_PORTAL);
            if (litNearby.isEmpty() || !litNearby.get().isWithinDistance(mod.getPlayer().getPos(), 64)) {
                if (!_buildingPortalHome) {
                    _buildingPortalHome = true;
                    _portalHomeBuildStartMs = System.currentTimeMillis();
                    int obsidian = mod.getItemStorage().getItemCount(Items.OBSIDIAN);
                    Debug.logMessage("(PortalHome) Materials available (obsidian=" + obsidian
                            + ") — latching direct build until portal is finished.");
                    Debug.logToFile("altoclef_diag.log", "[PortalHome]\tlatch ON, obsidian=" + obsidian
                            + " — sticky until built (timeout " + (PORTAL_HOME_BUILD_TIMEOUT_MS / 1000) + "s)");
                }
                setDebugState("Has obsidian + flint&steel (or materials), building portal home directly");
                return new ConstructNetherPortalObsidianTask();
            }
            setDebugState("Lit nether portal within 64 blocks, entering instead of building");
            _buildingPortalHome = false;
        }

        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }

        Optional<BlockPos> closest = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
        if (closest.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(closest.get());
        }

        Optional<BlockPos> remembered = mod.getWorldMemory().getNearestPortal(mod.getPlayer().getPos());
        if (remembered.isPresent()) {
            setDebugState("Going to remembered nether portal pos");
            return new GetToBlockTask(remembered.get());
        }

        setDebugState("Constructing nether portal with obsidian");
        return new ConstructNetherPortalObsidianTask();
    }

    private Task goToOverworldFromEndTask(AltoClef mod) {
        setDebugState("TODO: Go to center portal (at 0,0). If it doesn't exist, kill ender dragon lol");
        return null;
    }

    private Task goToNetherFromOverworldTask(AltoClef mod) {
        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }

        Optional<BlockPos> lastUsed = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.OVERWORLD);
        if (lastUsed.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(lastUsed.get());
        }

        Optional<BlockPos> remembered = mod.getWorldMemory().getNearestPortal(mod.getPlayer().getPos());
        if (remembered.isPresent()) {
            setDebugState("Going to remembered nether portal pos");
            return new GetToBlockTask(remembered.get());
        }

        return switch (mod.getModSettings().getOverworldToNetherBehaviour()) {
            case BUILD_PORTAL_VANILLA -> _cachedNetherBucketConstructionTask;
            case GO_TO_HOME_BASE -> new GetToBlockTask(mod.getModSettings().getHomeBasePosition());
        };
    }

    private Task goToEndTask(AltoClef mod) {
        // Keep in mind that getting to the end requires going to the nether first.
        setDebugState("TODO: Get to End, Same as BeatMinecraft");
        return null;
    }

    private boolean netherPortalIsClose(AltoClef mod) {
        if (mod.getBlockTracker().anyFound(Blocks.NETHER_PORTAL)) {
            Optional<BlockPos> closest = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), Blocks.NETHER_PORTAL);
            return closest.isPresent() && closest.get().isWithinDistance(mod.getPlayer().getPos(), 2000);
        }
        return false;
    }

    /**
     * (2026-09-13) 下界回家"直接造门"材料闸: 黑曜石 ≥10 (门框最低 10 块无四角),
     * 且有打火石, 或有铁锭+燧石可现场合成打火石。缺任一都走旧的找门链。
     */
    private boolean canBuildPortalHome(AltoClef mod) {
        if (mod.getItemStorage().getItemCount(Items.OBSIDIAN) < 10) return false;
        if (mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)) return true;
        return mod.getItemStorage().hasItem(Items.IRON_INGOT) && mod.getItemStorage().hasItem(Items.FLINT);
    }

    public enum OVERWORLD_TO_NETHER_BEHAVIOUR {
        BUILD_PORTAL_VANILLA,
        GO_TO_HOME_BASE
    }
}
