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
 * v2 (2026-09-11, 现场 20260911-184733 「游一会停一会」):
 *   ① 完成判据从"连续 0.5s 不沾水"改为"连续 0.5s 脚下有实体"。
 *      旧判据被水面上下浮动骗: 玩家在水面 bobbing 时腾空相 isTouchingWater=false
 *      (框架自己记作 NOT_IN_WATER_BOB_AIRBORNE), 连续 10 tick 腾空就误判"已上岸"。
 *      深水里脚下永远是水, 新判据永不误判。
 *   ② 新增卡住检测: 连续 4s XZ 位移 < 0.5 格 → 换方位重找陆地 + forceCancel
 *      撤销 baritone 旧路径, 逼它按新目标重算; 连续重规划 4 次仍无进展 → 放弃。
 *
 * 退出: 连续 0.5s 脚下有实体 (站得住, 含浅水底/沙滩) → 已可正常放置。
 * 兜底: 3 分钟总超时 → 放弃; 放弃后走【触发条件层 static 冷却 60s】, 防止
 *       父任务 (PlaceBlockNearbyTask 每 tick 重估) 立刻重建实例再来一轮。
 */
public class GetToLandTask extends Task {

    /** 连续这么多 tick 脚下有实体 (站得住) 才算上岸。 */
    private static final int GROUNDED_TICKS_TO_FINISH = 10;        // 0.5s
    /** 抵达当前陆地锚点 3 格内 → 这一段走完, 重算下一段 (逐段向陆地推进)。 */
    private static final double ARRIVE_DIST_SQ = 9;
    /** 卡住判定: 连续这么久 XZ 位移 < STALL_MIN_MOVE → 重新规划路线。 */
    private static final int STALL_TICKS_TO_REPLAN = 20 * 4;        // 4s
    private static final double STALL_MIN_MOVE = 0.5;
    /** 连续重规划这么多次仍无进展 → 放弃 (交给冷却, 让父任务改走别的分支)。 */
    private static final int MAX_REPLANS = 4;
    private static final int TOTAL_TIMEOUT_TICKS = 20 * 180;        // 3 分钟
    /** 放弃冷却 —— 触发条件层 static: 任务实例会被父任务频繁重建, 实例层冷却无效。 */
    private static final int GIVEUP_COOLDOWN_TICKS = 20 * 60;
    /** 128 格内找不到陆地时的兜底推进步长。 */
    private static final int EXPLORE_STEP_DIST = 48;

    private static long _giveUpUntilTick = 0;

    private BlockPos _landTarget = null;
    private double _exploreHeading = Double.NaN;
    private int _startTick = -1;
    private int _groundedTicks = 0;
    private int _stallTicks = 0;
    private int _replanCount = 0;
    private Vec3d _stallAnchor = null;
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
        _groundedTicks = 0;
        _stallTicks = 0;
        _replanCount = 0;
        _stallAnchor = null;
        _exploreHeading = Double.NaN;
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

        // 上岸判定: 脚下有实体才算站得住。
        // (v2) 不能看 isTouchingWater —— 水面 bobbing 的腾空相不沾水, 会被误判成上岸。
        BlockPos feet = mod.getPlayer().getBlockPos();
        if (WorldHelper.isSolid(mod, feet.down())) {
            if (++_groundedTicks >= GROUNDED_TICKS_TO_FINISH) {
                setDebugState("On land (solid ground under feet), done");
                return null;
            }
        } else {
            _groundedTicks = 0;
        }

        if (_startTick >= 0 && WorldHelper.getTicks() - _startTick > TOTAL_TIMEOUT_TICKS) {
            if (!_gaveUp) {
                _gaveUp = true;
                // (2026-09-11 v2) 冷却必须在此刻立刻写入, 不能只放 onStop ——
                // Task.tickInternal 从不检查子任务 isFinished, 父任务只要还返回本
                // 任务, _sub 就一直是同一实例; 而 GetToLandTask.isEqual 恒 true,
                // 父任务"重建实例"的写法也会被 isEqual 挡下, 于是 onStart 不会重跑、
                // onStop 永不触发 → 冷却永远写不进去 → 实例带着 _gaveUp 被无限 tick。
                _giveUpUntilTick = WorldHelper.getTicks() + GIVEUP_COOLDOWN_TICKS;
            }
            setDebugState("Give up: no land reached in " + (TOTAL_TIMEOUT_TICKS / 20) + "s");
            return null;
        }

        // (v2) 卡住检测 —— 用户要求: "游到岸再说, 但卡住要重新规划路线"。
        // 4s 内 XZ 几乎没动 → 换方位重找陆地 + 撤销 baritone 旧路径。
        Vec3d pos = mod.getPlayer().getPos();
        if (_stallAnchor == null || pos.squaredDistanceTo(_stallAnchor) >= STALL_MIN_MOVE * STALL_MIN_MOVE) {
            _stallAnchor = pos;
            _stallTicks = 0;
        } else if (++_stallTicks >= STALL_TICKS_TO_REPLAN) {
            _stallTicks = 0;
            _stallAnchor = pos;
            if (++_replanCount > MAX_REPLANS) {
                _gaveUp = true;
                _giveUpUntilTick = WorldHelper.getTicks() + GIVEUP_COOLDOWN_TICKS;
                setDebugState("Give up: still stuck after " + MAX_REPLANS + " replans");
                return null;
            }
            // 换一个方位重找 (findNearestLand(mod, _replanCount) 用 offset 换方向),
            // 并撤销 baritone 当前路径, 逼它按新目标重算 (baritone 没有路径级黑名单,
            // forceCancel 是"撤销路径"这一个杠杆)。
            _landTarget = null;
            _exploreHeading = Double.NaN;
            try {
                mod.getClientBaritone().getPathingBehavior().forceCancel();
            } catch (Throwable ignored) {
                // baritone 未就绪时忽略, 下一 tick 仍会重算目标
            }
            setDebugState("Stuck in water, replanning land route #" + _replanCount);
            return null;
        }

        // 逐段推进: 到达当前锚点附近就清掉, 重算下一段 (目标可能仍在海上/在岛上)。
        BlockPos player = mod.getPlayer().getBlockPos();
        if (_landTarget != null && player.getSquaredDistance(_landTarget) < ARRIVE_DIST_SQ) {
            _landTarget = null;
            _replanCount = 0;   // 真实推进了一段 → 重规划配额重置
        }
        if (_landTarget == null) {
            _landTarget = LandHelper.findNearestLand(mod, _replanCount);
        }
        if (_landTarget != null) {
            setDebugState("Heading to land at " + _landTarget.getX() + "," + _landTarget.getZ());
            return new GetToXZTask(_landTarget.getX(), _landTarget.getZ());
        }

        // 128 格内全是海 (或未加载): 沿持久 heading 有界推进, 不原地打转。
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
        return _gaveUp || _groundedTicks >= GROUNDED_TICKS_TO_FINISH;
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
