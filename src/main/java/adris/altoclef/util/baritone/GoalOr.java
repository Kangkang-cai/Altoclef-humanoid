package adris.altoclef.util.baritone;

import baritone.api.pathing.goals.Goal;

import java.util.Arrays;

/**
 * (2026-09-27 y 差 1 缝) 多目标取**任一**满足即可 —— {@link GoalAnd} 的对偶。
 *
 * 为什么需要它: 交互目标恰比 bot 高 1 格时, "站位面"有两个合法层 —— 目标自己那一层
 * (爬上一格台阶站到水平邻格) 和 bot 自己那一层 (原地站到水平邻格, 眼到目标侧面
 * ~0.8 格, 同样能右键)。只要用其中**任意一个**单独的 GoalNear 表达, 就会丢掉另一条
 * 路: 记 `GoalNear(target,1)` 会在"台阶被岩浆/整面地形封住"时永久无解(现场
 * 20260927-222854 站死 102.7s); 只记 `GoalNear(target.down(),1)` 又会在"台阶其实走得
 * 上去"的常见几何下舍近求远、甚至绕去挖走廊。OR 让 A* 自己按代价挑。
 *
 * 启发式取各子目标的 **min**: 对"任一到达"的真实代价是最小值, 故 min(可采纳) 仍可
 * 采纳, A\* 不会因此漏解。
 */
public class GoalOr implements Goal {
    private final Goal[] goals;

    public GoalOr(Goal... goals) {
        this.goals = goals;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Goal goal : this.goals) {
            if (goal.isInGoal(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.POSITIVE_INFINITY;
        for (Goal goal : this.goals) {
            min = Math.min(min, goal.heuristic(x, y, z));
        }
        return min;
    }

    @Override
    public String toString() {
        return "GoalOr" + Arrays.toString(this.goals);
    }

    public Goal[] goals() {
        return this.goals;
    }
}
