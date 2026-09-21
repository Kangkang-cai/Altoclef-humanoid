package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;

public class DragonBreathTracker {
    private final HashSet<BlockPos> _breathBlocks = new HashSet<>();

    public void updateBreath(AltoClef mod) {
        _breathBlocks.clear();
        for (AreaEffectCloudEntity cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloudEntity.class)) {
            for (BlockPos bad : WorldHelper.getBlocksTouchingBox(mod, cloud.getBoundingBox())) {
                _breathBlocks.add(bad);
            }
        }
    }

    public boolean isTouchingDragonBreath(BlockPos pos) {
        return _breathBlocks.contains(pos);
    }

    /**
     * (2026-09-21 1849 用户"dragon's breath 导航路径也不考虑, 差点被魔法打死")
     * 主动规避判据: 紫云中心进入 range 内就算危险 —— 旧链路只在【踩进】
     * 云格才逃 (getBlocksTouchingPlayer), 走位擦着云边照样持续吃伤害。
     * 按"玩家位置到云实体距离"算, 云本体半径 ~5 格, range=10 覆盖云缘。
     */
    public boolean isNearDragonBreath(AltoClef mod, double range) {
        Vec3d me = mod.getPlayer().getPos();
        for (AreaEffectCloudEntity cloud : mod.getEntityTracker().getTrackedEntities(AreaEffectCloudEntity.class)) {
            if (cloud.squaredDistanceTo(me) < range * range) return true;
        }
        return false;
    }

    public Task getRunAwayTask(double distance) {
        return new RunAwayFromDragonsBreathTask(distance);
    }

    private class RunAwayFromDragonsBreathTask extends CustomBaritoneGoalTask {

        private final double _distance;

        private RunAwayFromDragonsBreathTask(double distance) {
            _distance = distance;
        }

        @Override
        protected void onStart(AltoClef mod) {
            super.onStart(mod);
            mod.getBehaviour().push();
            // Encourage placing of all blocks!
            mod.getBehaviour().setBlockPlacePenalty(0);
            mod.getBehaviour().setBlockBreakAdditionalPenalty(10); // Normally 2

            // do NOT ever wander
            _checker = new MovementProgressChecker(999999);
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            super.onStop(mod, interruptTask);
            mod.getBehaviour().pop();
        }

        @Override
        protected Goal newGoal(AltoClef mod) {
            return new GoalRunAway(_distance, _breathBlocks.toArray(BlockPos[]::new));
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RunAwayFromDragonsBreathTask;
        }

        @Override
        protected String toDebugString() {
            return "ESCAPE Dragons Breath";
        }
    }
}
