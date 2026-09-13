package adris.altoclef.tasksystem;

import adris.altoclef.Debug;

/**
 * (2026-09-13 CursorArbiter) 光标（鼠标拖着的物品）"意图租约"仲裁 —— 与
 * {@link CraftingGridArbiter} 对称：那个管 2x2/3x3 合成格，这个管光标。
 *
 * <p>现场（会话 20260913-135927，14:05:45–14:06:12，用户"站着不动"，手动停止）：
 * 收合成产物时背包满，链路是
 * <pre>
 *   CraftInInventoryTask:125  "Returning stranded cursor item" → EnsureFreeCursorSlotTask
 *   ReceiveCraftingOutputSlotTask:107 "Making room for output" → EnsureFreeInventorySlotTask
 *     → ConsolidateInventoryTask（用光标把部分堆搬到同类大堆上，省一个格子）
 * </pre>
 * 两条意图对光标的要求正好相反：Consolidate 需要"光标可被临时占用"做两拍中转
 * （拿起 A → 并进 B），EnsureFreeCursor 要求"光标必须为空"。而
 * {@code Task.tickInternal} 每 tick 重跑父任务 onTick，父任务看到"光标非空"就翻面
 * 派 EnsureFreeCursorSlotTask —— 把正在搬堆叠的子树整棵 STOP。
 * 于是每 2 秒一轮：Consolidate 拿起圆石 → EnsureFreeCursor 点另一个槽把它换成闪长岩
 * → Consolidate 的目标丢失 → 重来。{@code altoclef_gridwar.log} 实录：
 * <pre>
 *   [SlotClick] origin=ConsolidateInventoryTask.onTick  slot=42 | slot cobblestonex17 -> EMPTY        | cursor EMPTY       -> cobblestonex17
 *   [SlotClick] origin=EnsureFreeCursorSlotTask.onTick  slot=43 | slot dioritex34    -> cobblestonex17 | cursor cobblestonex17 -> dioritex34
 * </pre>
 * EnsureFreeCursorSlotTask 自带的两道兜底（MAX_PLACE_ATTEMPTS=3 /
 * CURSOR_FREE_LIMIT_TICKS=40）都救不回来：闪长岩是建材 → {@code canThrowAwayStack}
 * 返回 false → 落进 "Picking up garbage" 分支 → 又点一个槽 → 把别的东西换回光标。
 *
 * <p>仲裁规则：谁先 begin 成功谁持有租约（LEASE_MS），持有方每 tick 刷新；
 * 对方在租约存活期内拿不到。等待方【不要】在租约期内翻面抢光标，而是维持当前
 * 子树（实例缓存 keep-alive）—— 见工作记忆"父任务 onTick 每 tick 重跑 → 侧线/
 * 清障子任务必须实例缓存；'返回任务' ≠ '任务会跑完'"。
 *
 * <p>必须用【静态】层状态：任务实例每 tick 被父链重建，实例层标志会被绕过
 * （见工作记忆"冷却表一律放触发条件层 static"条目）。
 *
 * <p>零行为面变化仅限于时序：搬堆叠与清光标各自完成整拍，不再交错。
 */
public final class CursorArbiter {

    /** 腾位/搬堆叠意图：允许把物品临时放在光标上做两拍中转（Consolidate 链）。 */
    public static final String INTENT_RELOCATE = "RELOCATE";
    /** 清空光标意图：要求光标为空（EnsureFreeCursorSlotTask）。 */
    public static final String INTENT_EMPTY = "EMPTY";

    /**
     * 租约时长。持租者每 tick `begin()` 续租，所以只要它还活着租约就不会过期；
     * 过期只发生在"持租者被 STOP / 不再被调度"之后，用来把等待方从死等里放出来。
     * 取值与 {@link CraftingGridArbiter} 一致（3s），覆盖"拿起→并入"两拍点击。
     */
    private static final long LEASE_MS = 3000;

    private static String _holder = null;
    private static long _expireAtMs = 0;
    private static long _lastLogMs = 0;

    private CursorArbiter() {
    }

    /**
     * 请求/续租。持有方每 tick 调用即续租；对方在租约存活期内返回 false
     * （调用方应维持当前子树、下 tick 再试，不要翻面抢光标）。
     */
    public static synchronized boolean begin(String intent) {
        long now = System.currentTimeMillis();
        if (_holder == null || _holder.equals(intent) || now >= _expireAtMs) {
            if (_holder == null) {
                log("cursor lease idle -> " + intent);
            } else if (!_holder.equals(intent)) {
                log("cursor lease " + _holder + " expired/stopped -> " + intent);
            }
            _holder = intent;
            _expireAtMs = now + LEASE_MS;
            return true;
        }
        log("cursor wait: held by " + _holder + ", requester=" + intent);
        return false;
    }

    /**
     * 主动释放（腾位链判定"已腾出空位/彻底放弃"时调用，让清光标链立即接手，
     * 不等租约过期）。只允许持有者本人释放。
     */
    public static synchronized void release(String intent) {
        if (_holder != null && _holder.equals(intent)) {
            log("cursor released by " + intent);
            _holder = null;
            _expireAtMs = 0;
        }
    }

    /** 租约是否正被指定意图持有（供派发方在"翻面抢光标"前查询）。 */
    public static synchronized boolean isHeldBy(String intent) {
        return _holder != null && _holder.equals(intent)
                && System.currentTimeMillis() < _expireAtMs;
    }

    /** 当前持有者意图标签，无则 null（诊断用）。 */
    public static synchronized String holder() {
        return (_holder != null && System.currentTimeMillis() < _expireAtMs) ? _holder : null;
    }

    private static void log(String msg) {
        long now = System.currentTimeMillis();
        if (now - _lastLogMs > 400) {
            _lastLogMs = now;
            Debug.logToFile("altoclef_gridwar.log", "[CursorArbiter] " + msg);
        }
    }
}
