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
 * <p>(2026-09-22 死锁保险丝) 上述"等待有界"假设还有一个漏洞：持有方如果
 * 是等待方的【祖先任务】（同一父链, 祖先在 onTick 里先 begin(CRAFT) 再返回
 * 会触发 CLEAR 的子树），租约会每 tick 被祖先永久续 —— 等待方等的是"祖先
 * 完成"，祖先等的是"等待方让路"，死锁 (现场 20260922-094351 实锤 30 分钟
 * stall: CraftInInventoryTask 收集阶段持 CRAFT, 其熔炼子链要 CLEAR)。
 * 保险丝：同一等待方连续等待超过 DEADLOCK_BREAK_MS 强制转移租约并打
 * CRITICAL 日志。正常路径下根修后不会触发；触发即说明出现了新的
 * "祖先续租"形态, 日志可直接定位。强制转移后若双方仍互抢, 退化为
 * DEADLOCK_BREAK_MS 周期的轮换（有界、有日志），优于永久死锁。
 *
 * <p>零行为面变化仅限于时序：清格与配方各自完成整拍，不再交错。
 */
public final class CraftingGridArbiter {

    /** 配方链意图：要把材料写进 2x2 格 / 从输出格收成品。 */
    public static final String INTENT_CRAFT = "CRAFT";
    /** 清格意图：要把 2x2 格取空。 */
    public static final String INTENT_CLEAR = "CLEAR";

    private static final long LEASE_MS = 3000;
    /** (2026-09-22 死锁保险丝) 同一等待方连续等待超此时长 → 强制转移租约。 */
    private static final long DEADLOCK_BREAK_MS = 30000;

    private static String _holder = null;
    private static long _expireAtMs = 0;
    private static long _lastLogMs = 0;
    // 保险丝状态：当前连续等待方 + 等待起点。任一 begin 成功即复位。
    private static String _waiter = null;
    private static long _waitSinceMs = 0;

    private CraftingGridArbiter() {
    }

    /**
     * 请求/续租。持有方每 tick 调用即续租；对方在租约存活期内返回 false
     * （调用方应就地等待，下 tick 再试）。同一等待方连续等待超过
     * DEADLOCK_BREAK_MS 时强制转移租约（死锁保险丝，见类注释）。
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
            _waiter = null;
            _waitSinceMs = 0;
            return true;
        }
        if (_waiter == null || !_waiter.equals(intent)) {
            _waiter = intent;
            _waitSinceMs = now;
        }
        if (now - _waitSinceMs >= DEADLOCK_BREAK_MS) {
            log("DEADLOCK BREAK: " + _holder + " held while " + intent
                    + " waited " + ((now - _waitSinceMs) / 1000) + "s; force transfer");
            _holder = intent;
            _expireAtMs = now + LEASE_MS;
            _waiter = null;
            _waitSinceMs = 0;
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
