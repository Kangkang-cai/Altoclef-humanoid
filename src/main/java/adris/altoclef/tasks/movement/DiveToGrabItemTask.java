package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-08-26 下潜捡眼) Baritone 不支持可靠的水下下潜寻路 — 它能把 bot 带到
 * 目标 XZ 的水面, 但不会潜到水柱里的目标点 (12:51 日志实锤: GetToBlockTask
 * 追水下掉落物 y 逐 tick 变 62→40→46 → TaskPingPong, bot 在水面等物品自己漂)。
 *
 * 此任务用直接控制下潜/上浮: 面向物品 3D 位置, 按住前进, 目标在下→潜行下沉,
 * 在上→跳跃上浮, 平齐→中性, 直到碰撞自动拾取或物品消失。短距离水下抓取用。
 *
 * (2026-08-26 用户实测) ①从陆地捡水里眼睛时导航模组会搭路到物品正上方, bot
 * 站在自己搭的方块上 (不是水) → 下潜失效 → 打掉脚下方块掉进水里再下潜。
 * ②长按潜行会让 bot 站在方块边缘/平台边走不出去 (潜行防跌落) → 潜行改为
 * 脉冲 (按 N tick / 松 M tick), 松开时能往前跨过坎; 且只在"在水中"时才按潜行
 * 下沉, 不在水中就松开潜行让 bot 能走向水面。
 */
public class DiveToGrabItemTask extends Task {

    private final ItemEntity _item;
    // (2026-08-26 用户实测: 长按潜行走不出去) 潜行脉冲: 按 6 tick / 松 2 tick。
    // 松开的 2 tick 让 bot 能往前走 (跨过方块边缘/平台边落水), 再按下去潜。
    private int _sneakTapTicks = 0;
    private boolean _sneakOn = false;
    private static final int SNEAK_HOLD_TICKS = 6;
    private static final int SNEAK_RELEASE_TICKS = 2;

    public DiveToGrabItemTask(ItemEntity item) {
        _item = item;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // 物品被捡起/消失 → 完成, 交还父任务。
        if (_item == null || !_item.isAlive()) {
            return null;
        }
        Vec3d p = mod.getPlayer().getPos();
        Vec3d ip = _item.getPos();
        double hDist = Math.sqrt((ip.x - p.x) * (ip.x - p.x) + (ip.z - p.z) * (ip.z - p.z));
        // 水平距离被拉开太远 (水流/导航失败) → 完成, 交还父任务重新规划。
        if (hDist > 10) {
            return null;
        }
        // (2026-08-26 用户实测: ①导航搭路到物品正上方, 站在自己搭的方块上不是水
        // → 下潜失效; ②眼睛掉进洞穴/峡谷, bot 站在方块上想下去但下面没有水)
        // 没入水 + 物品在正下方 + 脚下是可破坏实心方块 → 打掉脚下块逐格下降,
        // 直到贴近物品。真实地面也允许 (垂直向下挖到物品), DestroyBlockTask 的
        // 安全判定会挡住危险坠落/岩浆; 水平拉开 (hDist>2) 则交给导航。
        if (!mod.getPlayer().isTouchingWater()) {
            BlockPos feet = mod.getPlayer().getBlockPos();
            BlockPos below = feet.down();
            if (hDist <= 2
                    && mod.getPlayer().getY() > _item.getY() + 1.0
                    && WorldHelper.isSolid(mod, below)
                    && WorldHelper.canBreak(mod, below)) {
                setDebugState("Breaking block below to reach dropped item.");
                return new DestroyBlockTask(below);
            }
        }
        // 直接控制: 停掉 baritone 寻路, 面向物品, 前进 + 垂直控制。
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        LookHelper.lookAt(mod, ip);
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        double dy = ip.y - p.y;
        boolean inWater = mod.getPlayer().isTouchingWater();
        if (dy < -1.5 && inWater) {
            // 目标在下 + 在水中 → 潜行下沉。用脉冲 (按 6/松 2): 松开时 bot 能
            // 往前走跨过方块边缘/平台边, 不至于被潜行防跌落卡住 (2026-08-26 用户
            // 实测: 站在方块上长按潜行走不出去)。
            _sneakTapTicks++;
            if (!_sneakOn && _sneakTapTicks >= SNEAK_RELEASE_TICKS) {
                _sneakOn = true;
                _sneakTapTicks = 0;
            }
            if (_sneakOn && _sneakTapTicks >= SNEAK_HOLD_TICKS) {
                _sneakOn = false;
                _sneakTapTicks = 0;
            }
            if (_sneakOn) {
                mod.getInputControls().hold(Input.SNEAK);
            } else {
                mod.getInputControls().release(Input.SNEAK);
            }
            mod.getInputControls().release(Input.JUMP);
            // (2026-08-26 用户要求) 下潜时开疾跑 (更快逼近水下目标)。
            mod.getInputControls().hold(Input.SPRINT);
        } else if (dy < -1.5) {
            // 目标在下但不在水中 (站在岸边/平台上) → 松开潜行, 让 bot 能往前
            // 走落水/下块; 配合上面的"打掉脚下块"逐格下降。
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.JUMP);
            mod.getInputControls().release(Input.SPRINT);
        } else if (dy > 1.5) {
            mod.getInputControls().hold(Input.JUMP); // 目标在上 → 上浮
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.SPRINT);
        } else {
            mod.getInputControls().release(Input.JUMP);
            mod.getInputControls().release(Input.SNEAK);
            // (2026-08-26 用户要求) 游泳 (水平/水面) 关疾跑。
            mod.getInputControls().release(Input.SPRINT);
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.SPRINT);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _item == null || !_item.isAlive();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof DiveToGrabItemTask d && d._item == _item;
    }

    @Override
    protected String toDebugString() {
        return "Diving to grab item";
    }
}
