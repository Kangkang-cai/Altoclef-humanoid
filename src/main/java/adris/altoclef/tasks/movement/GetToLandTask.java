package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LandHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-11) 「先回岸上」—— 把泡在水里 (踩水, 脚下无实体) 的 bot 送到最近的干燥陆地。
 *
 * 为什么必须有这个任务 (现场 20260911-174220):
 *   Settings.avoidOceanBlocks 默认 true → WorldHelper.canReach 在
 *   【海洋 biome + 玩家 y>47 + 候选点 y<64 + 该列水线高于候选点】时恒 false。
 *   bot 从封闭水体里游上来停在 (-102,61,212) 仍泡在水里, 放置工作台时
 *   locateClosePlacePos 在 ±7 格内找不到任何"可达"点位 → 无限 Wander,
 *   15.5s 失败到用户手动停。唯一出路是上岸 —— 岸上 canReach 不再被 ocean 规则拦。
 *
 * 退出: 连续 0.5s 不再"踩水" (脚下出现实体, 含浅水底/沙滩) → 已可正常放置。
 * 兜底: 3 分钟总超时 → 放弃; 放弃后走【触发条件层 static 冷却 60s】, 防止
 *       父任务 (PlaceBlockNearbyTask 每 tick 重估) 立刻重建实例再来一轮。
 */
public class GetToLandTask extends Task {

    /** 连续这么多 tick 不踩水才算上岸 (过滤掉跳跃/浪涌造成的瞬时 inWater=false)。 */
    private static final int DRY_TICKS_TO_FINISH = 10;             // 0.5s
    /** 抵达当前陆地锚点 3 格内 → 视为这一段走完, 重算下一段 (逐段向陆地推进)。 */
    private static final double ARRIVE_DIST_SQ = 9;
    private static final int TOTAL_TIMEOUT_TICKS = 20 * 180;       // 3 分钟
    /** 放弃冷却 —— 触发条件层 static: 任务实例会被父任务频繁重建, 实例层冷却无效。 */
    private static final int GIVEUP_COOLDOWN_TICKS = 20 * 60;
    /** 128 格内找不到陆地时的兜底推进步长。 */
    private static final int EXPLORE_STEP_DIST = 48;

    private static long _giveUpUntilTick = 0;

    private BlockPos _landTarget = null;
    private double _exploreHeading = Double.NaN;
    private int _startTick = -1;
    private int _dryTicks = 0;
    private boolean _gaveUp = false;

    /** 触发条件层冷却: 冷却期内父任务不应再派本任务。 */
    public static boolean isOnCooldown() {
        return WorldHelper.getTicks() < _giveUpUntilTick;
    }

    public boolean hasGivenUp() {
        return _gaveUp;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _startTick = WorldHelper.getTicks();
        _dryTicks = 0;
        _gaveUp = false;
        _landTarget = null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_gaveUp) {
            _giveUpUntilTick = WorldHelper.getTicks() + GIVEUP_COOLDOWN_TICKS;
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getPlayer() == null) return null;

        // 上岸判定: 不再踩水 → 连续 0.5s 后收工。
        if (LandHelper.isFloatingInWater(mod)) {
            _dryTicks = 0;
        } else if (++_dryTicks >= DRY_TICKS_TO_FINISH) {
            setDebugState("On land (not in deep water), done");
            return null;
        }

        if (_startTick >= 0 && WorldHelper.getTicks() - _startTick > TOTAL_TIMEOUT_TICKS) {
            _gaveUp = true;
            setDebugState("Give up: no land reached in " + (TOTAL_TIMEOUT_TICKS / 20) + "s");
            return null;
        }

        // 逐段推进: 到达当前锚点附近就清掉, 重算下一段 (目标可能仍在海上/在岛上)。
        BlockPos player = mod.getPlayer().getBlockPos();
        if (_landTarget != null && player.getSquaredDistance(_landTarget) < ARRIVE_DIST_SQ) {
            _landTarget = null;
        }
        if (_landTarget == null) {
            _landTarget = LandHelper.findNearestLand(mod);
        }
        if (_landTarget != null) {
            setDebugState("Heading to land at " + _landTarget.getX() + "," + _landTarget.getZ());
            return new GetToXZTask(_landTarget.getX(), _landTarget.getZ());
        }

        // 128 格内全是海 (或未加载): 沿持久 heading 有界推进, 不原地打转。
        Vec3d pos = mod.getPlayer().getPos();
        if (Double.isNaN(_exploreHeading)) {
            _exploreHeading = Math.random() * 2 * Math.PI;
        }
        int x = (int) Math.floor(pos.x + Math.cos(_exploreHeading) * EXPLORE_STEP_DIST);
        int z = (int) Math.floor(pos.z + Math.sin(_exploreHeading) * EXPLORE_STEP_DIST);
        setDebugState("No land within 128 blocks, pushing to " + x + "," + z);
        return new GetToXZTask(x, z);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _gaveUp || _dryTicks >= DRY_TICKS_TO_FINISH;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetToLandTask;
    }

    @Override
    protected String toDebugString() {
        return "Getting to land (out of water)";
    }
}
