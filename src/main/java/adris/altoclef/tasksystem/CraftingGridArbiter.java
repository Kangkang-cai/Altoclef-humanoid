package adris.altoclef.tasksystem;

import adris.altoclef.Debug;

/**
 * (2026-09-09 [GridWar] 根修) 2x2 玩家合成格"意图租约"仲裁。
 *
 * <p>现场（14:56 / 15:32 两场次实锤）：清格链（EnsureFreePlayerCraftingGridTask
 * 要 2x2 格空）与配方链（CraftGeneric* / CraftInInventoryTask 要把材料放进
 * 2x2 格）分属同一父链下每 tick 翻转重建的兄弟分支，双方每 tick 互抢半截
 * 动作 —— 清格刚把 log 拿出，配方又放回；配方刚放好，清格又拿走，50 秒
 * 净进度为零（altoclef_gridwar.log / altoclef_craftclick.log 实锤）。
 *
 * <p>仲裁规则：谁先 begin 成功谁持有租约（LEASE_MS），持有方每 tick 刷新；
 * 对方 begin 失败就地等待。等待必有界 —— 持有方要么完成（停止续租，租约
 * 过期），要么任务被 STOP（同样停止续租），最迟 LEASE_MS 后等待方拿到的
 * 是完整的一拍，而不是再被打断的半拍。注意必须用【静态】层状态：任务实例
 * 每 tick 被父链重建，实例层标志会被绕过（见工作记忆"触发条件层 static
 * 冷却"条目）。
 *
 * <p>零行为面变化仅限于时序：清格与配方各自完成整拍，不再交错。
 */
public final class CraftingGridArbiter {

    /** 配方链意图：要把材料写进 2x2 格 / 从输出格收成品。 */
    public static final String INTENT_CRAFT = "CRAFT";
    /** 清格意图：要把 2x2 格取空。 */
    public static final String INTENT_CLEAR = "CLEAR";

    private static final long LEASE_MS = 3000;

    private static String _holder = null;
    private static long _expireAtMs = 0;
    private static long _lastLogMs = 0;

    private CraftingGridArbiter() {
    }

    /**
     * 请求/续租。持有方每 tick 调用即续租；对方在租约存活期内返回 false
     * （调用方应就地等待，下 tick 再试）。
     */
    public static synchronized boolean begin(String intent) {
        long now = System.currentTimeMillis();
        if (_holder == null || _holder.equals(intent) || now >= _expireAtMs) {
            if (_holder == null) {
                log("lease idle -> " + intent);
            } else if (!_holder.equals(intent)) {
                log("lease " + _holder + " expired/stopped -> " + intent);
            }
            _holder = intent;
            _expireAtMs = now + LEASE_MS;
            return true;
        }
        log("wait: held by " + _holder + ", requester=" + intent);
        return false;
    }

    /**
     * 主动释放（清格发现格子已空时调用，让配方链立即接手，不等租约过期）。
     * 只允许持有者本人释放。
     */
    public static synchronized void release(String intent) {
        if (_holder != null && _holder.equals(intent)) {
            log("released by " + intent);
            _holder = null;
            _expireAtMs = 0;
        }
    }

    private static void log(String msg) {
        long now = System.currentTimeMillis();
        if (now - _lastLogMs > 400) {
            _lastLogMs = now;
            Debug.logToFile("altoclef_gridwar.log", "[Arbiter] " + msg);
        }
    }
}
