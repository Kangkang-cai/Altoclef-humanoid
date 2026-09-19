package adris.altoclef.util.helpers;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.path.IPathExecutor;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementPillar;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

public class BaritoneHelper {

    /**
     * Use whenever accessing Minecraft data (from ClientWorld or ClientPlayerEntity) in baritone
     */
    public static final Object MINECRAFT_LOCK = new Object();

    /**
     * (2026-09-19 搭路禁举盾, 用户) baritone 当前是否正在【搭方块/悬空寻路】——
     * builder 流程激活, 或当前 movement 是会放方块的类型 (搭柱 MovementPillar /
     * 上行 MovementAscend / 斜行 MovementDiagonal), 或寻路中双脚悬空
     * (空中搭路, 本 fork 无独立 MovementBridge, 横向搭桥落在悬空分支里)。
     *
     * 消费方: 弹射物举盾格挡与战斗盾战。原因: 举盾 + 防御瞄准(requestDefenseAim)
     * 会抢 baritone 放方块需要的转头, 格挡还会 cancelEverything —— 搭路中断,
     * 半空就是坠落 (0919 搭路靠近烈焰人摔死实锤)。搭路期间弹射物防御整体让位。
     *
     * 平地直行 (MovementTraverse 平走) 不算搭路, 不误伤正常走位中的盾战。
     * 任何异常都吞掉并返回 false (按"没在搭路"处理, 不阻塞正常防御)。
     */
    public static boolean isBaritoneBuilding() {
        try {
            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone.getBuilderProcess().isActive()) return true;
            if (!baritone.getPathingBehavior().isPathing()) return false;
            Object m = null;
            IPathExecutor exec = baritone.getPathingBehavior().getCurrent();
            if (exec != null) {
                IPath path = exec.getPath();
                if (path != null && exec.getPosition() < path.movements().size()) {
                    m = path.movements().get(exec.getPosition());
                }
            }
            if (m instanceof MovementPillar || m instanceof MovementAscend || m instanceof MovementDiagonal) {
                return true;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            return client == null || client.player == null || !client.player.isOnGround();
        } catch (Throwable t) {
            return false;
        }
    }

    public static double calculateGenericHeuristic(Vec3d start, Vec3d target) {
        return calculateGenericHeuristic(start.x, start.y, start.z, target.x, target.y, target.z);
    }

    /**
     * Returns baritone's approximation heuristic from start to target
     */
    public static double calculateGenericHeuristic(double xStart, double yStart, double zStart, double xTarget, double yTarget, double zTarget) {
        double xDiff = xTarget - xStart;
        int yDiff = (int) yTarget - (int) yStart;
        double zDiff = zTarget - zStart;
        return GoalBlock.calculate(xDiff, yDiff < 0 ? yDiff + 1 : yDiff, zDiff);
    }
}
