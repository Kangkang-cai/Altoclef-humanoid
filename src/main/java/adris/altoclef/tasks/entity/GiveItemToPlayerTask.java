package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.bridge.PeerCommandSender;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class GiveItemToPlayerTask extends Task {

    private final String _playerName;
    private final ItemTarget[] _targets;

    private final CataloguedResourceTask _resourceTask;
    private final List<ItemTarget> _throwTarget = new ArrayList<>();
    private boolean _droppingItems;

    /** 本次交付的丢弃动作 (丢光标物品 = 在玩家窗口外点 slot -999)。 */
    private Task _dropTask;
    /** 交付瞄准起点 (0 = 尚未开始瞄准)；视线稳定 {@link #AIM_SETTLE_MS} 后才丢。 */
    private long _aimStartMs;
    /** (2026-10-07) onStart 只初始化一次：让位恢复会重跑 onStart，交付状态必须存活。 */
    private boolean _sessionStarted;

    /** 交付前瞄准对方脚边的视线稳定等待 (毫秒)。平滑转头未稳定就丢，物品会沿旧朝向飞出。 */
    private static final long AIM_SETTLE_MS = 400;

    /** (2026-10-07 恰好数量) 拆堆受阻重试起点 (0 = 未受阻)；超过 {@link #PREP_STUCK_GIVEUP_MS} 放弃本次交付。 */
    private long _prepStuckSinceMs;

    /** 拆堆受阻放弃阈值 —— 一切 hold/wait 分支必须有出口 (铁律②)。 */
    private static final long PREP_STUCK_GIVEUP_MS = 3000;

    public GiveItemToPlayerTask(String player, ItemTarget... targets) {
        _playerName = player;
        _targets = targets;
        _resourceTask = TaskCatalogue.getSquashedItemTask(targets);
    }

    @Override
    protected void onStart(AltoClef mod) {
        // (2026-10-07 现场修正) 这里原本无条件清零 _droppingItems/_throwTarget。但框架在
        // "让位后恢复"时会重跑 onStart (Task.interrupt 置 _first=true ⇒ tickInternal 重进
        // onStart)，清零等于抹掉"物品已经丢出去了"这个事实 —— 恢复后落到下面的
        // !itemTargetsMet 分支，判定"我还没收集齐要给的物品"，转去冶炼/收集
        // (1007 14:11:26 僵尸抢占 → 14:11:29.965 恢复 → <Get catalogued: cooked_porkchop x3>)。
        // 交付状态属于"一次交货会话"，必须跨 interrupt 存活；新的一次 @give = 新实例，
        // 字段本来就是初值。
        if (_sessionStarted) return;
        _sessionStarted = true;
        _droppingItems = false;
        _throwTarget.clear();
        _aimStartMs = 0;
        _prepStuckSinceMs = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // 上一拍的丢弃动作还没跑完 → 继续跑 (完成后清空，下一件重新瞄准)。
        if (_dropTask != null) {
            if (_dropTask.isActive() && !_dropTask.isFinished(mod)) {
                setDebugState("Throwing items");
                return _dropTask;
            }
            _dropTask = null;
            _aimStartMs = 0;
        }

        Optional<Vec3d> lastPos = mod.getEntityTracker().getPlayerMostRecentPosition(_playerName);

        if (lastPos.isEmpty()) {
            // (2026-10-02) 与 FollowPlayerTask 同口径: 区分"名字打错 / 服务器没这人 / 在线但太远"。
            // debug state 保留 "No player found" 前缀, bridge probeGive 依赖它做状态映射。
            var et = mod.getEntityTracker();
            List<String> online = et.getServerPlayerNames();
            Optional<String> closest = et.findClosestServerPlayerName(_playerName);
            if (!online.isEmpty() && closest.isEmpty()) {
                mod.logWarning("Player \"" + _playerName + "\" is not on this server. Online players: " + String.join(", ", online));
                stop(mod);
                return null;
            }
            if (closest.isPresent() && !closest.get().equalsIgnoreCase(_playerName)) {
                setDebugState("No player found/detected. Did you mean \"" + closest.get() + "\"?");
            } else {
                setDebugState("No player found/detected. Target is online but outside client tracking range; no coordinates to navigate to.");
            }
            return null;
        }
        Vec3d targetPos = lastPos.get().add(0, 0.2f, 0);

        if (_droppingItems) {
            // THROW ITEMS
            setDebugState("Throwing items");

            // (2026-10-07 现场修正) 交付 = 【近距离低抛到对方脚边】。
            // 旧实现复用 ThrowCursorTask —— 那是"丢垃圾"专用：转向开阔处 → 挖穿面前两格
            // 实心方块 → 水平平抛到最远距离。用它交货，物品落到远处地上，接收方 ~1 格自拾
            // 半径够不着，现场表现为"丢了但对方几乎捡不到"(1007 14:10/14:11)。
            // 现在：平滑转头瞄对方脚边 → 视线稳定后丢出光标整堆 → 物品落在两人脚边。
            Optional<PlayerEntity> receiver = mod.getEntityTracker().getPlayerEntity(_playerName);
            if (receiver.isPresent()) {
                LookHelper.lookAt(mod, receiver.get().getPos().add(0, -0.4, 0));
                long now = System.currentTimeMillis();
                if (_aimStartMs == 0) _aimStartMs = now;
                if (now - _aimStartMs < AIM_SETTLE_MS) {
                    return null;
                }
            } else {
                // 只剩"最近位置"、没有实体（对方在跟踪范围外）→ 不阻塞，按记录坐标继续
                _aimStartMs = 0;
            }

            // (2026-10-07 恰好数量) 交付语义 = "恰好给 N 个"。MC 的丢弃只有整堆一条路
            // (点 slot -999 = 丢光标整堆)，所以必须先【拆堆】把光标收敛到恰好 N 个：
            //   光标空      → 挑一个堆拿到光标 (优先"≥需求的最小堆"，其次最大堆)
            //   光标超量    → 右键源槽放回 1 个 (光标非空时右键 = 放 1 个)，逐拍收敛
            //   光标恰好/不足 → 丢出光标整堆
            // 全部判定直接读【光标现状】，不存中间态 ⇒ 被抢占恢复后天然幂等 (对照 onStart 注释那类坑)。
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();

            for (int i = 0; i < _throwTarget.size(); ++i) {
                ItemTarget target = _throwTarget.get(i);
                if (target.getTargetCount() <= 0) continue;

                // (1) 光标上就是本次要给的物品
                if (!cursor.isEmpty() && target.matches(cursor.getItem())) {
                    if (cursor.getCount() <= target.getTargetCount()) {
                        // 恰好 (或该堆本来就不够需求 = 按实际给) → 丢出光标整堆
                        int give = cursor.getCount();
                        _throwTarget.set(i, new ItemTarget(target, target.getTargetCount() - give));
                        _prepStuckSinceMs = 0;
                        Debug.logMessage("THROWING " + give + "x " + cursor.getItem().getTranslationKey() + " (exact)");
                        // 丢光标物品 = 在玩家窗口外点 slot(-999)。物品沿当前视线方向抛出，
                        // 上面已瞄向对方脚边 ⇒ 落在脚边，接收方走过去即可自动拾取。
                        _dropTask = new ClickSlotTask(Slot.UNDEFINED);
                        return _dropTask;
                    }
                    // 超量 → 放回 1 个到同类未满堆 (通常是刚被拿空的源槽)
                    Optional<Slot> back = findSlotToPutBack(mod, cursor);
                    if (back.isPresent()) {
                        _prepStuckSinceMs = 0;
                        return new ClickSlotTask(back.get(), 1);
                    }
                    // 理论上"源槽被左键拿空后必有位置放回"，走到这里 = 背包异常。
                    // 有出口优先: 【不偷偷多给】(用户钦定"恰好")，先记录重试，超过 3s 放弃本次交付并告警。
                    long stuckNow = System.currentTimeMillis();
                    if (_prepStuckSinceMs == 0) {
                        _prepStuckSinceMs = stuckNow;
                        mod.logWarning("Cannot split stack for \"" + _playerName + "\": no slot to put the "
                                + cursor.getCount() + "x surplus back. Retrying...");
                    } else if (stuckNow - _prepStuckSinceMs > PREP_STUCK_GIVEUP_MS) {
                        mod.logWarning("Giving up exact delivery of "
                                + cursor.getItem().getTranslationKey() + " x" + cursor.getCount()
                                + " (inventory full, surplus has nowhere to go).");
                        stop(mod);
                        return null;
                    }
                    return null;
                }

                // (2) 光标空 → 挑一个堆拿到光标 (左键 = 拿整堆；MC 没有"拿 N 个")
                if (cursor.isEmpty()) {
                    Optional<Slot> src = pickSourceSlotFor(mod, target);
                    if (src.isPresent()) {
                        return new ClickSlotTask(src.get());
                    }
                }
            }

            if (!targetPos.isInRange(mod.getPlayer().getPos(), 4)) {
                mod.log("Finished giving items.");
                notifyReceiverToPickUp();
                stop(mod);
                return null;
            }
            return new RunAwayFromPositionTask(6, WorldHelper.toBlockPos(targetPos));
        }

        if (!StorageHelper.itemTargetsMet(mod, _targets)) {
            setDebugState("Collecting resources...");
            return _resourceTask;
        }

        if (targetPos.isInRange(mod.getPlayer().getPos(), 1.5)) {
            if (!mod.getEntityTracker().isPlayerLoaded(_playerName)) {
                mod.logWarning("Failed to get to player \"" + _playerName + "\". We moved to where we last saw them but now have no idea where they are.");
                stop(mod);
                return null;
            }
            _droppingItems = true;
            _throwTarget.addAll(Arrays.asList(_targets));
        }

        setDebugState("Going to player...");
        // (2026-10-05) FollowPlayerTask 默认跟随距离改为 3 格; 交付物品必须在
        // 1.5 格内才能触发扔掷 (见上方 isInRange 1.5 判定), 这里显式传近距离。
        // (2026-10-07 follow 关系治理) 后两个参数 = (不限时, 不登记 /status.following)：
        // 这只是"走过去交货"的一次性位移, 不是跟随关系 —— 若登记会与"对方正跟着我"
        // 构成假互指, 导致双方互相退让而交货中断。
        return new FollowPlayerTask(_playerName, 1, 0, false);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GiveItemToPlayerTask task) {
            if (!task._playerName.equals(_playerName)) return false;
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    /**
     * (2026-10-07 交付闭环) 交付完成后，让<b>接收方 bot</b>主动去捡。
     *
     * <p>交付只能把物品丢在地上（MC 没有"塞进对方背包"的原语）：真人会自己捡，
     * 另一个 bot 不会 —— 1007 14:10 现场即"丢了但对方不捡、自动拾取概率极低"。
     * 这里给对方发一条 {@code @pickup <物品短名>}，由它的 {@code PickupCommand}
     * 派 {@code PickupDroppedItemTask} 走过去捡。
     *
     * <p>物品名用 {@link ItemHelper#stripItemName}（= {@code cooked_porkchop} 这类注册短名），
     * 与对面 {@code PickupCommand} 的三级解析口径一致。
     *
     * <p>对面是真人玩家 / 未开 bridge ⇒ {@link PeerCommandSender} 静默跳过，无副作用；
     * 多个目标物品时逐个发（实测场景都是单一物品）。
     */
    private void notifyReceiverToPickUp() {
        for (ItemTarget target : _targets) {
            if (target.getMatches().length == 0) continue;
            PeerCommandSender.send(_playerName, "@pickup " + ItemHelper.stripItemName(target.getMatches()[0]));
        }
    }

    /**
     * (2026-10-07 恰好数量) 找一个能把光标上这撮物品放回去的槽：优先"同类且未满"的堆
     * （左键拿空的那个源槽最典型），否则任意能容纳它的槽。找不到 ⇒ 调用方降级直接丢（有出口优先）。
     */
    private Optional<Slot> findSlotToPutBack(AltoClef mod, ItemStack cursorStack) {
        for (Slot slot : mod.getItemStorage().getSlotsWithItemPlayerInventory(false, cursorStack.getItem())) {
            if (Slot.isCursor(slot)) continue;
            ItemStack at = StorageHelper.getItemStackInSlot(slot);
            if (!at.isEmpty() && at.getCount() < at.getMaxCount()) {
                return Optional.of(slot);
            }
        }
        return mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
    }

    /**
     * (2026-10-07 恰好数量) 挑一个堆拿到光标：优先"数量 ≥ 需求的最小堆"（拆完剩余最少）；
     * 没有这样的堆时挑<b>最大堆</b> —— 此时该堆本就 &lt; 需求，整堆给出去也不会超过需求，
     * "恰好"语义天然满足（且绕开了 MC 无法跨堆精确拆分的限制）。
     */
    private Optional<Slot> pickSourceSlotFor(AltoClef mod, ItemTarget target) {
        Slot best = null;
        int bestCount = -1;
        boolean bestOver = false;
        for (Slot slot : mod.getItemStorage().getSlotsWithItemPlayerInventory(false, target.getMatches())) {
            if (Slot.isCursor(slot)) continue;
            int count = StorageHelper.getItemStackInSlot(slot).getCount();
            if (count <= 0) continue;
            boolean over = count >= target.getTargetCount();
            if (best == null) {
                best = slot;
                bestCount = count;
                bestOver = over;
            } else if (over) {
                if (!bestOver || count < bestCount) {
                    best = slot;
                    bestCount = count;
                    bestOver = true;
                }
            } else if (!bestOver && count > bestCount) {
                best = slot;
                bestCount = count;
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    protected String toDebugString() {
        return "Giving items to " + _playerName;
    }
}
