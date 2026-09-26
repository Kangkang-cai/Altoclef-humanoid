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
    // (2026-09-26 乒乓活锁修复) 开吃前"准星连续干净"确认计数。
    private int _clearViewTicks = 0;
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
        _clearViewTicks = 0;
        _started = true;
        // (2026-09-14 空中禁食配套, 用户"跳跃中途吃东西减速掉下去") 设计意图是
        // "站定吃完", 但被抢占的导航任务若不是 CustomBaritoneGoalTask 系, baritone
        // 路径可能残留 → 吃着吃着 baritone 又起跳/走到坎边。前台接管时明确取消
        // 寻路, 保证站定。吃完后导航任务 onTick 会自动重新 setGoalAndPath。
        try {
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        } catch (Throwable ignored) {
        }
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

        // (2026-09-14 空中禁食配套) 吃到一半 baritone/惯性起跳: 立刻松右键,
        // vanilla 停止使用物品 → 速度恢复满值跳完这一跳, 落地后下一 tick 继续吃。
        // 口径与 FoodChain 空中闸门 / ITaskRequiresGrounded 一致。
        if (!(mod.getPlayer().isOnGround() || mod.getPlayer().isSwimming()
                || mod.getPlayer().isTouchingWater() || mod.getPlayer().isClimbing())) {
            releaseEat(mod);
            setDebugState("Not eating (airborne)");
            return null;
        }

        // (2026-09-22 熔炉合成卡死修复) 面向容器 / 容器界面开着时右键 = 打开界面
        // 而不是吃。旧设计靠 FoodChain 每 tick 重评闸门让位, 造成 Food↔User Tasks
        // ownership 乒乓 (121524 会话 6.2s 内 50 次, 一口都吃不成, 用户工作台的
        // 合成也被 closeScreen 副作用掐断)。现在接管后视角自己负责: 面向容器先
        // 低头 (低头永不指向容器), 视角稳定后再开吃。
        if (adris.altoclef.util.helpers.LookHelper.isFacingInteractable(mod)) {
            releaseEat(mod);
            // 俯仰角 90 = 垂直向下; 保持当前 yaw, 避免无谓的水平甩头
            adris.altoclef.util.helpers.LookHelper.lookAt(mod,
                    new baritone.api.utils.Rotation(mod.getPlayer().getYaw(), 90f));
            // (2026-09-26) 准星命中容器 = 视角不稳定, 确认计数清零。
            _clearViewTicks = 0;
            setDebugState("Facing interactable, looking down before eating");
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
            // (2026-09-20 10:26 用户"拿着盾发愣, 不吃东西") 先自愈"光标被占"的中间态,
            // 再谈放弃。20260920-102209 实锤: 10:23:44~10:23:53 光标上卡着一把钻石剑
            // (换武器序列丢步留下的中间态), 副手的盾因此停不下来 → 这 9 秒里只有
            // "Shield park failed (cursor busy), waiting" 反复刷, 没有任何出路,
            // 用户看到的就是"拿着盾发愣不吃东西"。自愈成功 → 同 tick 直接继续进食。
            if (FoodChain.restoreCursorItemToInventory(mod) && FoodChain.parkShieldOffHands(mod)) {
                off = mod.getPlayer().getOffHandStack();
                setDebugState("Recovering: cursor item -> inventory, shield parked, eating");
            } else {
                // 停盾失败 (光标被占): 本 tick 绝不 hold 右键 (右键 = 举盾 → 卡死)。
                releaseEat(mod);
                _lastShieldStuckLogMs = logThrottled(mod, _lastShieldStuckLogMs,
                        "[FOOD-EAT] shield PARK FAILED (cursor busy), waiting");
                setDebugState("Shield park failed (cursor busy), waiting");
                return null;
            }
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

        // (2026-09-26 乒乓活锁修复, 现场 1726 会话 17:56:01~24) 被中断导航的 look
        // 残留让相机仍在朝容器平滑转动: 当拍 crosshairTarget 还没指到炉子 → 本任务
        // 当拍 hold 右键 → 右键实际应用时准星已转进炉子 → 误开熔炉界面 → FoodChain
        // 容器闸门让位 (prio62→50) → 炉卫 "Closing furnace screen to guard" 关界面
        // → Food 再抢 → 每 tick 互换 344 次、23 秒吃不成一口。修法: 开吃要求准星
        // 连续 2 拍干净; 相机若正转向容器, 第 2 拍必进上方低头分支 —— 结构上永不
        // 误开界面。代价: 正常进食晚 1 tick。
        if (_clearViewTicks < 1) {
            _clearViewTicks++;
            setDebugState("Waiting for stable view before eating");
            return null;
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
            adris.altoclef.util.helpers.TimeoutLog.hit("EatItemTask.timeout", "eating did not complete in time, force-finished");
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
