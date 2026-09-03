package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.TurnToOpenAreaTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.util.math.BlockPos;

/**
 * 丢弃光标上的物品。
 *
 * (2026-09-01 用户纠正: 丢出是朝向视角的, 不是随机初速度 — 之前我误判成随机,
 * Q键/快捷栏重设计已废弃回退) 点窗外(-999)丢光标物品的方向由玩家朝向决定,
 * 所以: 先交给独立 TurnToOpenAreaTask 转向"前方最开阔"的方向, 视角稳定后再
 * 点窗外丢出 — 物品带朝向初速度飞向开阔处, 不再落回脚下被满背包立刻捡回。
 *
 * (2026-09-03 主动挖开堆头, 用户"四周封闭无开阔空间→丢出落回脚下被重拾→卡死"):
 * 转向后若 TurnToOpenAreaTask 判定四周仍封闭 (最佳方向前方紧贴实心块), 直接丢
 * 会把物品甩到贴脸处被满背包立刻捡回。此时先主动挖开该方向前方可挖的实心块,
 * 打通空间后再重新转向、丢弃。每轮最多挖 OPEN_SCORE_THRESHOLD 格 (DigSlot上限),
 * 防无限挖。
 */
public class ThrowCursorTask extends Task {

    private final Task _throwTask = new ClickSlotTask(Slot.UNDEFINED);
    // 独立转向任务: 先转向开阔处, 视角稳定后才丢。
    private TurnToOpenAreaTask _turnTask;
    // 主动挖通关卡的任务 (封闭时清理该方向前方实心块)。
    private DestroyBlockTask _digTask;
    // (2026-09-03) 挖开处计数, 上限 = 开阔判定所需格数, 防无限挖。
    private int _dugCount = 0;
    // 至多挖这么多格就把通路完全打通 (前后两层都数, 单层够深才算开阔)。
    private static final int MAX_DIG = 4;

    @Override
    protected void onStart(AltoClef mod) {
        _turnTask = new TurnToOpenAreaTask();
        _digTask = null;
        _dugCount = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // 1) 正在挖: 挖完前一直派该挖格任务。
        if (_digTask != null && !_digTask.isFinished(mod)) {
            return _digTask;
        }
        if (_digTask != null) {
            // 挖格与挖掘标记重置 — 挖完一处就重新转向评估。
            _digTask = null;
            _dugCount++;
            _turnTask = new TurnToOpenAreaTask();
            return _turnTask;
        }
        // 2) 转向中: 完成前持续转向。
        if (_turnTask != null && !_turnTask.isFinished(mod)) {
            return _turnTask;
        }
        // 3) 转向完成。判定是否封闭 → 是则主动挖开堆头。
        if (_turnTask != null) {
            boolean enclosed = _turnTask.isEnclosed();
            float bestYaw = _turnTask.getBestYaw();
            _turnTask = null;
            if (enclosed && _dugCount < MAX_DIG) {
                // 挖开最佳方向前方第一块实心 (最贴脸处)。
                BlockPos dig = findFirstWallInBestDir(mod, bestYaw);
                // 挖不到或不该挖(如基岩/液体/关键方块)时不能无限重试 → 回退直接丢。
                if (dig != null) {
                    _digTask = new DestroyBlockTask(dig);
                    return _digTask;
                }
            }
            _digTask = null;
            // 挖无可挖/已达上限 → 直接丢 (兜底, 不再空转卡死)。
            return _throwTask;
        }
        // 4) 转向完成且判定开阔 → 直接丢。
        return _throwTask;
    }

    // 沿最佳方向前方 (1..MAX_DIG) 找"脚下层(dy=0)或头顶层(dy=1)"第一格实心且可挖的
    // 块, 返回其坐标; 无则 null。
    // (2026-09-03 用户"丢出撞到上面/面前: 不是挖头顶, 是挖面前两块"):
    // 丢弃物品是沿玩家丢出高度(y=+1, 身体/胸前层)飞出的 — 挡路的正是这层前方
    // d=1..2 的方块, 而不是更高处的"头顶"。旧实现先挖脚下层(dy=0), 物品飞行高度
    // (dy=1) 的前方方块没被挖, 挖一次重转仍封闭 → 达上限被迫直接丢 → 撞到面前上层
    // 方块。故: 沿面前距离 d=1..2, 【优先挖丢出高度层 dy=1】(这才是挡飞行的), 同
    // 距离脚下层 dy=0 也挖 — 即面前两格都挖两层。
    private BlockPos findFirstWallInBestDir(AltoClef mod, float yaw) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(rad));
        int dz = (int) Math.round(Math.cos(rad));
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int d = 1; d <= MAX_DIG; d++) {
            BlockPos cFoot = p.add(dx * d, 0, dz * d);
            BlockPos cHead = p.add(dx * d, 1, dz * d);
            // 优先挖丢出高度层(dy=1)= 物品实际飞行层; 该层可挖就挖它。
            if (WorldHelper.isSolid(mod, cHead) && WorldHelper.canBreak(mod, cHead)) {
                return cHead;
            }
            // 同距离脚下层(dy=0)也要挖 — 面前两格都挖两层。
            if (WorldHelper.isSolid(mod, cFoot) && WorldHelper.canBreak(mod, cFoot)) {
                return cFoot;
            }
            // 该距离两层都不可挖的实心 → 这个方向挖不开, 放弃换方向重转为丢。
            if (WorldHelper.isSolid(mod, cFoot) || WorldHelper.isSolid(mod, cHead)) {
                return null;
            }
        }
        return null; // 前方已是通路, 无需挖 (理论封闭判定不该到这)。
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof ThrowCursorTask;
    }

    @Override
    protected String toDebugString() {
        return "Throwing Cursor";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _throwTask.isFinished(mod);
    }
}