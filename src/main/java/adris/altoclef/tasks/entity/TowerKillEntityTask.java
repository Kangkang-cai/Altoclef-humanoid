package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CaveSpiderEntity;
import net.minecraft.entity.mob.SpiderEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * (2026-09-07 塔式逃生) 站桩版击杀任务: bot 已搭高 (脚下 3 块悬空方块), 怪
 * 上不来, 只需要原地瞄准+出手, 绝不能走位。
 *
 * 为什么不复用 KillEntityTask / AbstractKillEntityTask: 它们每 tick 返回
 * GetToEntityTask 贴脸走位 (AbstractDoToEntityTask.onTick L143 / L206) ——
 * 在塔上时怪在塔脚, "贴脸走位"会把 bot 带下塔, 塔逃直接作废。本任务
 * 覆盖该行为: 不返回任何移动子任务, 只 lookAt + 冷却到点 attack()。
 *
 * 出手拟人化不受影响: PlayerExtraController.attack() 仍做准心校验 +
 * 战斗瞄准仲裁 (7ebc4913 最近诉求胜出 / 9a0c0539 自适应瞄准点), 俯视打
 * 塔脚怪的自适应瞄准点采样已覆盖。
 *
 * 蜘蛛优先 (2026-09-07 用户拍板): 蜘蛛会爬墙, 是 3 格塔唯一的登塔威胁,
 * 爬塔途中正好进入近战范围, 必须最优先击杀 (12 格内的蜘蛛优先于更近的
 * 其他怪)。
 */
public class TowerKillEntityTask extends Task {

    // 目标选取半径: 塔下 16 格内的怪都算威胁清单。
    private static final double TARGET_RANGE = 16;
    // 蜘蛛在塔边爬 → 12 格内出现蜘蛛就优先转火。
    private static final double SPIDER_PRIORITY_RANGE = 12;

    @Override
    protected void onStart(AltoClef mod) {
        // 站桩: 确保没有遗留的 Baritone 寻路在偷偷移动 bot。
        try {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        Entity target = pickTarget(mod);
        if (target == null) {
            setDebugState("Tower kill: no target in range");
            return null;
        }
        setDebugState("Tower kill: " + target.getType().getTranslationKey()
                + " @ " + String.format("%.1f", Math.sqrt(target.squaredDistanceTo(mod.getPlayer()))));

        // 站桩瞄准: 无 Baritone 寻路竞争, 直接 lookAt 目标包围盒中心,
        // 让准心尽快落到目标上 (attack() 的准心校验依赖 crosshairTarget)。
        LookHelper.lookAt(mod, target.getBoundingBox().getCenter());

        AbstractKillEntityTask.equipWeapon(mod);
        if (mod.getPlayer().getAttackCooldownProgress(0) >= 0.75f) {
            // attack() 内部: 准心对准 + 仲裁胜出才出刀, 否则登记瞄准诉求。
            mod.getControllerExtras().attack(target);
        }
        return null;
    }

    /**
     * 目标选取: 12 格内蜘蛛最优先 (唯一能爬塔的怪), 其余取最近。
     */
    private Entity pickTarget(AltoClef mod) {
        List<Entity> hostiles;
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            hostiles = new ArrayList<>(mod.getEntityTracker().getHostiles());
        }
        Entity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        Entity spider = null;
        double spiderDistSq = Double.MAX_VALUE;
        for (Entity e : hostiles) {
            if (e == null || !e.isAlive()) continue;
            if (!e.isInRange(mod.getPlayer(), TARGET_RANGE)) continue;
            double d = e.squaredDistanceTo(mod.getPlayer());
            if (e instanceof SpiderEntity || e instanceof CaveSpiderEntity) {
                if (d < spiderDistSq) {
                    spiderDistSq = d;
                    spider = e;
                }
            } else if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = e;
            }
        }
        if (spider != null && spiderDistSq <= SPIDER_PRIORITY_RANGE * SPIDER_PRIORITY_RANGE) {
            return spider;
        }
        return nearest;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 16 格内没有存活敌对怪 → 塔上打怪阶段结束。
        for (Entity e : mod.getEntityTracker().getHostiles()) {
            if (e != null && e.isAlive() && e.isInRange(mod.getPlayer(), TARGET_RANGE)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 保险起见松开跳跃 (BUILD 阶段遗留的 hold 不应带到下个任务)。
        mod.getInputControls().release(Input.JUMP);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TowerKillEntityTask;
    }

    @Override
    protected String toDebugString() {
        return "Tower kill (stationary)";
    }
}
