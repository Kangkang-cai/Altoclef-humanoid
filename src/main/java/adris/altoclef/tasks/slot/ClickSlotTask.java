package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

public class ClickSlotTask extends Task {

    private final Slot _slot;
    private final int _mouseButton;
    private final SlotActionType _type;

    private boolean _clicked = false;

    // (2026-09-09 [GridWar] 诊断, "收金链 ↔ 清 2x2 格" 50 秒横跳案) 现有 [CraftClick]/
    // [CraftGridClick] 只有 cursor 前后 + 单层 caller(ClickSlotTask.onTick), 定位不到
    // 发起点击的任务链。本诊断在【构造时】捕获调用栈里最近的两层业务任务帧(构造点
    // 在父任务 onTick 内, 能拿到真实发起者; 执行栈里只有框架层拿不到), 点击落地时
    // 记录 origin + 目标槽/光标 前后栈, 一次复现即可定案: ①origin 交替出现"清格 vs
    // 配方放料" = 两链诉求冲突; ②有执行但槽栈不变 = 点击哑火; ③落地后立刻被反向
    // 点击 = 每 tick 重建抢切。零行为改动: 只读+写文件。全局 250ms 节流防刷屏。
    private final String _origin;
    private static long _lastDiagMs = 0;

    private static String captureOrigin() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int found = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (!cn.startsWith("adris.altoclef.tasks")) continue;
                if (cn.contains("tasksystem")) continue;
                if (cn.endsWith(".ClickSlotTask")) continue;
                if (found > 0) sb.append(" <- ");
                sb.append(cn.substring(cn.lastIndexOf('.') + 1)).append('.').append(e.getMethodName());
                if (++found >= 2) break;
            }
            return sb.length() == 0 ? "unknown" : sb.toString();
        } catch (Exception e) {
            return "capture-error";
        }
    }

    private static String fmtStack(ItemStack stack) {
        return stack.isEmpty() ? "EMPTY" : stack.getItem().toString() + "x" + stack.getCount();
    }

    public ClickSlotTask(Slot slot, int mouseButton, SlotActionType type) {
        _slot = slot;
        _mouseButton = mouseButton;
        _type = type;
        _origin = captureOrigin();
    }
    public ClickSlotTask(Slot slot, SlotActionType type) {
        this(slot, 0, type);
    }
    public ClickSlotTask(Slot slot, int mouseButton) {
        this(slot, mouseButton, SlotActionType.PICKUP);
    }
    public ClickSlotTask(Slot slot) {
        this(slot, SlotActionType.PICKUP);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _clicked = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getSlotHandler().canDoSlotAction()) {
            // [GridWar] 诊断: 点击前快照目标槽与光标 (只读)。
            ItemStack slotBefore = _slot.getWindowSlot() >= 0
                    ? StorageHelper.getItemStackInSlot(_slot).copy() : ItemStack.EMPTY;
            ItemStack cursorBefore = StorageHelper.getItemStackInCursorSlot().copy();

            mod.getSlotHandler().clickSlot(_slot, _mouseButton, _type);
            mod.getSlotHandler().registerSlotAction();
            _clicked = true;

            // [GridWar] 诊断: 点击后立即回读 (客户端点击本 tick 内生效, 无变化即哑火实锤)。
            long now = System.currentTimeMillis();
            if (now - _lastDiagMs > 250) {
                _lastDiagMs = now;
                ItemStack slotAfter = _slot.getWindowSlot() >= 0
                        ? StorageHelper.getItemStackInSlot(_slot) : ItemStack.EMPTY;
                ItemStack cursorAfter = StorageHelper.getItemStackInCursorSlot();
                Debug.logToFile("altoclef_gridwar.log", String.format(
                        "[SlotClick] origin=%s slot=%d type=%s btn=%d | slot %s -> %s | cursor %s -> %s",
                        _origin, _slot.getWindowSlot(), _type, _mouseButton,
                        fmtStack(slotBefore), fmtStack(slotAfter),
                        fmtStack(cursorBefore), fmtStack(cursorAfter)));
            }
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task obj) {
        if (obj instanceof ClickSlotTask task) {
            return task._mouseButton == _mouseButton && task._type == _type && task._slot.equals(_slot);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Clicking " + _slot.toString();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _clicked;
    }
}
