package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/**
 * (2026-08-23 防火药水) 喝下一瓶防火药水 (猪灵交易获得)。
 *
 * 两种药水处理:
 * - 饮用型 (Items.POTION): 装备到主手 + 按住右键 ~1.6s 喝下
 * - 喷溅型 (Items.SPLASH_POTION): 低头 (pitch 90) + 右键扔在脚下爆开
 *
 * 完成 = 玩家获得 FIRE_RESISTANCE 效果 (两种方式生效即完成)。
 * 超时保护: 5 秒内没生效 (药水拿错/被打断) 自动放弃, 不卡死。
 *
 * 装备方式: SWAP 把药水槽位换到当前选中的热栏槽 (主手) — 不能用
 * forceEquipItem(Items.POTION), 它按 Item 匹配会抓到任意药水 (水瓶等)。
 */
public class DrinkFireResPotionTask extends Task {

    private final TimerGame _timeout = new TimerGame(5);
    private boolean _started = false;
    // (2026-09-15 统一超时标签) elapsed() 不自动复位, 放弃后打一次 [TIMEOUT] 即闩住。
    private boolean _giveUpLogged = false;

    @Override
    protected void onStart(AltoClef mod) {
        _timeout.reset();
        _started = false;
        _giveUpLogged = false;
        // 停掉寻路: 原地喝药, 防止 baritone 移动打断使用动作
        mod.getClientBaritone().getPathingBehavior().cancelEverything();
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            // 已生效 (可能上一瓶刚喝完)
            return null;
        }
        if (_timeout.elapsed()) {
            if (!_giveUpLogged) {
                _giveUpLogged = true;
                adris.altoclef.util.helpers.TimeoutLog.hit("DrinkFireResPotionTask.timeout", "5s without fire resistance, giving up this bottle");
            }
            Debug.logMessage("(DrinkFireResPotion) Timed out without getting fire resistance, giving up this bottle.");
            return null;
        }

        ItemStack mainHand = mod.getPlayer().getMainHandStack();
        boolean holdingPotion = ItemHelper.isFireResPotionStack(mainHand);

        if (!holdingPotion) {
            // (2026-09-29 岩浆急救分型, 用户"能喷就喷, 否则喝") 选药优先级:
            //   脚下固体方块 + 有喷溅型 → 喷 (瞬发生效, 不用熬 1.6s 喝药动画);
            //   否则 → 喝饮用型 (在岩浆里脚下是岩浆, 喷溅无效, 只能喝)。
            //   只有喷溅型但脚下没固体 → 喷了打不中自己, 放弃 (链层 30s 冷却兜底)。
            Slot potionSlot;
            Slot splashSlot = ItemHelper.getSplashFireResPotionSlot(mod);
            boolean solidUnderfoot = WorldHelper.isSolid(mod, mod.getPlayer().getBlockPos().down());
            if (solidUnderfoot && splashSlot != null) {
                potionSlot = splashSlot;
            } else {
                potionSlot = ItemHelper.getDrinkableFireResPotionSlot(mod);
            }
            if (potionSlot == null) {
                if (splashSlot != null) {
                    Debug.logMessage("(DrinkFireResPotion) Only splash potion left but no solid block "
                            + "underfoot — unusable, giving up this bottle.");
                } else {
                    // 药水没了 (喝完/丢失), 放弃
                    Debug.logMessage("(DrinkFireResPotion) No fire resistance potion left, giving up.");
                }
                return null;
            }
            int selected = mod.getPlayer().getInventory().selectedSlot;
            mod.getSlotHandler().clickSlot(potionSlot, selected, SlotActionType.SWAP);
            return null;
        }

        // 主手已拿防火药水: 喝或扔
        if (!_started) {
            _started = true;
            _timeout.reset();
            if (mainHand.getItem() == Items.SPLASH_POTION) {
                // 喷溅型: 低头扔脚下 (瞬间生效)
                LookHelper.lookAt(mod, new Rotation(mod.getPlayer().getYaw(), 88));
                mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                setDebugState("Throwing splash fire resistance potion at feet");
            } else {
                // 饮用型: 按住右键喝 (~1.6s)
                mod.getInputControls().hold(Input.CLICK_RIGHT);
                setDebugState("Drinking fire resistance potion");
            }
        } else {
            if (mainHand.getItem() != Items.SPLASH_POTION) {
                // 持续按住右键直到喝完
                mod.getInputControls().hold(Input.CLICK_RIGHT);
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(false);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return mod.getPlayer() != null && mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof DrinkFireResPotionTask;
    }

    @Override
    protected String toDebugString() {
        return "Drinking fire resistance potion";
    }
}
