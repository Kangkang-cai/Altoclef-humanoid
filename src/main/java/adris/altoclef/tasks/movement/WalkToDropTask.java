package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.input.Input;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-08 干草块现场兜底) 掉落物就在几格内、baritone 却因为"整格导航目标"与
 * MC 的 1 格吸附判定不一致而认为已到达、站着不动时, 直接朝掉落物走两步捡起来。
 * <p>
 * 现场 (08:07-08:08 日志): 干草块掉落物实体 x=305.18 y=64.94 z=178.19, 取整后的
 * 导航目标 BlockPos(305,64,178) 是【物品脚下的实心地面】, baritone 无法把玩家送进
 * 实心方块 -> 静止 36 秒, 3 格外的掉落物永远吸不到。人类看到脚边的掉落物就是走
 * 两步捡起来, 不会启动全局寻路。
 * <p>
 * 只在水平 <= ~4.5 格、垂直 <= 2 格时启用 (短距离直走风险可控), 4 秒没捡到就结束,
 * 交还上层由它决定换目标/放弃。
 */
public class WalkToDropTask extends Task {

    private final ItemEntity _item;
    private final TimerGame _timeout = new TimerGame(4);
    private boolean _done = false;

    /** 已经够近, 交给 MC 自身的吸附逻辑 (吸附判定是 3D 距离 < 1 格)。 */
    private static final double GRAB_DISTANCE = 0.85;

    public WalkToDropTask(ItemEntity item) {
        _item = item;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _done = false;
        _timeout.reset();
        // 抢走 baritone 的控制权, 否则它会跟我们抢按键。
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_done || !_item.isAlive()) {
            _done = true;
            return null;
        }
        Vec3d me = mod.getPlayer().getPos();
        Vec3d p = _item.getPos();
        double dist = me.distanceTo(p);
        if (dist < GRAB_DISTANCE || _timeout.elapsed()) {
            // 够近了 (或走不过去 — 被地形挡住) → 结束, 交还上层。
            _done = true;
            return null;
        }
        // 视线高度对齐目标 (不低头, 避免视角抖动), 按住前进走过去。
        LookHelper.lookAt(mod, new Vec3d(p.x, mod.getPlayer().getEyeY(), p.z));
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        // 物品比脚高一层 (台阶/坎上) 时补一个跳跃, 免得贴着坎走不上去。
        if (p.y - me.y > 0.8 && mod.getPlayer().isOnGround()) {
            mod.getInputControls().hold(Input.JUMP);
        } else {
            mod.getInputControls().release(Input.JUMP);
        }
        setDebugState("Walking to drop (" + String.format("%.1f", dist) + "m away)");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.JUMP);
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        _done = true;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_done || !_item.isAlive()) {
            return true;
        }
        return mod.getPlayer() != null && mod.getPlayer().getPos().distanceTo(_item.getPos()) < GRAB_DISTANCE;
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof WalkToDropTask task && task._item == _item;
    }

    @Override
    protected String toDebugString() {
        return "Walking to nearby drop";
    }
}
