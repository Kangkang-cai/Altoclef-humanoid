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
    private long _lastShieldStuckLogMs = 0;
    private long _lastEquipFailLogMs = 0;
    // (2026-09-06 开局即超时修复) SingleTaskChain.onTick 在任务【首次 tick 之前】
    // 就先查 isFinished (SingleTaskChain.java:59), onStart 来不及跑。而 BaseTimer
    // 初始 _prevTime=0, elapsed() = 连接ticks/20 - 0 > interval —— 连接时长一旦
    // 超过 eatTaskTimeoutSeconds(20s), 新建的 EatItemTask 在 onStart 前就被判
    // 超时结束, 每 tick 被 FoodChain 重建→即死 (日志实锤 21:10:01 起
    // "Food main task ending: isFinished=true" 刷屏, Food prio 62 常驻压死
    // 用户任务 50 → "一启动任务就结束")。修复: onStart 没跑过就绝不判结束。
    private boolean _started = false;

    public EatItemTask(double timeoutSeconds) {
        _timeout = new TimerGame(timeoutSeconds);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _timeout.reset();
        _eating = false;
        _started = true;
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
        net.minecraft.item.ItemStack main = mod.getPlayer().getMainHandStack();
        net.minecraft.item.ItemStack off = mod.getPlayer().getOffHandStack();
        boolean mainIsShield = main.getItem() == net.minecraft.item.Items.SHIELD;

        // 自愈旧 bug 遗留态 (21:20 实锤: 主手盾+副手面包): 主手盾捡上光标,
        // 下一 tick 主手空 + 副手食物 → 直接从副手吃。光标被占时不抢 (PICKUP
        // 会和光标上现有物品交换, 搅乱状态)。
        if (mainIsShield && off.isFood()
                && adris.altoclef.util.helpers.StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            mod.getSlotHandler().clickSlotForced(
                    adris.altoclef.util.slots.PlayerSlot.getEquipSlot(), 0,
                    net.minecraft.screen.slot.SlotActionType.PICKUP);
            setDebugState("Recovering: main-hand shield -> cursor, eat from offhand");
            return null;
        }

        // (2026-09-06 21:38 重构) 副手盾 → 光标 (一把 forced PICKUP, 光标不是
        // 手不挡右键; 不再依赖多步 SWAP 序列 —— 那会被槽位节流静默丢弃出中间态)。
        if (off.getItem() == net.minecraft.item.Items.SHIELD && !FoodChain.parkShieldOffHands(mod)) {
            // 停盾失败 (光标被占): 本 tick 绝不 hold 右键 (右键 = 举盾 → 卡死)。
            releaseEat(mod);
            _lastShieldStuckLogMs = logThrottled(mod, _lastShieldStuckLogMs,
                    "[FOOD-EAT] shield PARK FAILED (cursor busy), waiting");
            setDebugState("Shield park failed (cursor busy), waiting");
            return null;
        }

        // 装备食物到主手 (unInterruptable=true: 本任务拥有 bot)。
        // (2026-09-06 22:33) getItemSlotsInInventoryReal 已含副手, 面包只剩副手
        // 一摞时也能 SWAP 换到主手; 装备仍失败时, 主手为"无右键用途"物品
        // (空手/工具) 的话直接从副手吃 —— vanilla 主手 use 失败才轮到副手,
        // 工具/空手 use 恒失败, 副手食物正常开吃; 盾/方块/桶等会抢右键, 不行。
        if (!main.isFood()) {
            mod.getSlotHandler().forceEquipItem(new Item[]{food}, true);
            main = mod.getPlayer().getMainHandStack();
            if (!main.isFood()) {
                boolean mainNotBlocking = main.isEmpty() || main.getItem() instanceof net.minecraft.item.ToolItem;
                if (!(off.isFood() && mainNotBlocking)) {
                    releaseEat(mod);
                    _lastEquipFailLogMs = logThrottled(mod, _lastEquipFailLogMs,
                            "[FOOD-EAT] equip food FAILED: " + food
                                    + " main=" + main.getItem() + " off=" + off.getItem());
                    setDebugState("Failed to equip " + food.toString() + ", waiting");
                    return null;
                }
                // fallthrough 到下方开吃, 吃副手。
            }
        }

        mod.getInputControls().hold(Input.CLICK_RIGHT);
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
        _eating = true;
        setDebugState("Eating: " + (main.isFood() ? main.getItem().toString() : off.getItem().toString()));
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 注意: SingleTaskChain 的 isFinished 路径不会调用 onStop, 收尾
        // (释放右键/恢复交互/归还光标上的盾) 必须在这里先做完再返回 true。
        // onStart 尚未执行 (刚 setTask、还没被 tick 过): 绝不判结束,
        // 否则计时器未重置 → 开局即超时死循环 (见 _started 注释)。
        if (!_started) {
            return false;
        }
        if (_timeout.elapsed()) {
            finishUp(mod);
            return true;
        }
        if (!mod.getFoodChain().needsToEat(mod)) {
            finishUp(mod);
            return true;
        }
        return false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        finishUp(mod);
    }

    private void finishUp(AltoClef mod) {
        releaseEat(mod);
        // 盾在进食期间停在光标上, 结束时归还 (副手/背包空格)。
        FoodChain.restoreShieldFromCursor(mod);
    }

    private void releaseEat(AltoClef mod) {
        if (_eating) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _eating = false;
        }
    }

    // 诊断日志 2s 节流: 卡在等待分支时让 altoclef_diag.log 有迹可循。
    private long logThrottled(AltoClef mod, long lastMs, String message) {
        long now = System.currentTimeMillis();
        if (now - lastMs < 2000) return lastMs;
        adris.altoclef.Debug.logToFile("altoclef_diag.log", message);
        return now;
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
