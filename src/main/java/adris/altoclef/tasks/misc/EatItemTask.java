package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.FoodChain;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.item.Item;

import java.util.Optional;

/**
 * 前台进食任务 (2026-09-06 战后不进食修复)。
 *
 * 背景: FoodChain 原设计为"异步后台进食"——getPriority() 里副作用式
 * startEat() (装备食物 + hold 右键 + interactionPaused) 后返回 -INF,
 * 让用户任务继续跑、吃东西在后台完成。该机制极其脆弱: 获胜任务链 tick
 * 里的装备 (MineAndCollectTask 单物品重载无 isTryingToEat 闸门)、举盾
 * release、MLG setInputForceState(CLICK_RIGHT,false) 都会静默掐断进食,
 * 且 FoodChain 零诊断日志 (用户实测: 血量 2、饥饿 11~17、背包 129 食物,
 * 战斗结束后没有任何进食动作, 直接继续挖铁)。
 *
 * 修复: 真饿/低血 (FoodChain.needsToEat == true) 且不在战斗时, FoodChain
 * 改为前台接管 (prio 62 > 用户任务 50): bot 站定吃完再回去干活。本任务
 * 拥有 bot 所有权期间, 没有并发任务能抢走主手/右键; 有威胁时
 * (逃跑 70 / 加急战斗 95 / MLG 100) 自然被抢占, onStop 释放右键。
 */
public class EatItemTask extends Task {

    private final TimerGame _timeout;
    private boolean _eating = false;

    public EatItemTask(double timeoutSeconds) {
        _timeout = new TimerGame(timeoutSeconds);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _timeout.reset();
        _eating = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // 岩浆/坠落等致命险情: 松键等待 (MLG/岩浆链优先级更高, 会抢占本任务,
        // 这里只是防御性释放, 避免被抢占前的窗口期还挂着右键)。
        if (mod.getPlayer().isInLava()) {
            releaseEat(mod);
            setDebugState("Not eating (in lava)");
            return null;
        }

        Optional<Item> bestFood = mod.getFoodChain().getBestFoodToEat(mod);
        if (bestFood.isEmpty()) {
            // 没有可选食物 (全被保护/没有食物): 释放右键干等, 超时兜底结束,
            // 让 CollectFoodTask 之类的补粮逻辑接管。
            releaseEat(mod);
            setDebugState("No edible food, waiting");
            return null;
        }

        Item food = bestFood.get();

        // (2026-08-23 吃不上饭修复, 复用 FoodChain 逻辑) 副手持盾时右键被盾抢走
        // (MC 机制: 副手盾优先于主手使用 → 举盾不吃食物)。先换盾到背包空槽。
        if (!FoodChain.tryClearShieldFromOffhand(mod)) {
            // 换盾失败 (背包满无空槽 / SWAP 未生效): 本 tick 绝不 hold 右键
            // (右键 = 举盾 → interactionPaused + 原地举盾卡死), 等下个 tick。
            releaseEat(mod);
            setDebugState("Offhand shield stuck, waiting for slot");
            return null;
        }

        // 装备食物 (unInterruptable=true: 本任务拥有 bot, 不许别的逻辑换手)。
        boolean equipped = mod.getSlotHandler().forceEquipItem(new Item[]{food}, true);
        if (!equipped) {
            releaseEat(mod);
            setDebugState("Failed to equip " + food.toString() + ", waiting");
            return null;
        }

        mod.getInputControls().hold(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
        _eating = true;
        setDebugState("Eating: " + food.toString());
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 注意: SingleTaskChain 的 isFinished 路径不会调用 onStop, 收尾
        // (释放右键/恢复交互) 必须在这里先做完再返回 true。
        if (_timeout.elapsed()) {
            releaseEat(mod);
            return true;
        }
        if (!mod.getFoodChain().needsToEat(mod)) {
            releaseEat(mod);
            return true;
        }
        return false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        releaseEat(mod);
    }

    private void releaseEat(AltoClef mod) {
        if (_eating) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _eating = false;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EatItemTask;
    }

    @Override
    protected String toDebugString() {
        return "Eating food (foreground)";
    }
}
