package adris.altoclef.bridge;

/**
 * 背包瞬时读失败防抖：过传送门/维度切换瞬间，客户端侧背包读出全零或骤降，
 * 产生两类污染（1001 19:02 实证）——
 * <ul>
 *   <li><b>事件伪报</b>：清零后重新读回，被差量算法当成新拾取（同秒 8 条
 *       collected/rare_item）；</li>
 *   <li><b>快照字段瞬零</b>：连续采样 food=0/coal=0，若对端恰在轮首采样，
 *       会把"没有食物"的假状态注入 LLM。</li>
 * </ul>
 *
 * <p>修法在采样源头统一防抖：背包总量骤降（&gt;80% 或归零）即判读失败，进入
 * 恢复窗口（{@value #RECOVERY_WINDOW_MS}ms）。窗口内三个消费方一致动作：
 * <ol>
 *   <li>{@link EventCollector} 冻结 collected/rare_item 事件生成，且不更新差量基线
 *       （窗口结束后静默重建基线，跳过窗口内增量）；</li>
 *   <li>{@link InventoryDigest#sample} 沿用上一个有效摘要的背包派生字段
 *       （equipment/food/foodCount/keyItems；hunger/level 是玩家状态，照常实时）；</li>
 *   <li>差量基线跳过该窗口（同 1）。</li>
 * </ol>
 *
 * <p>线程模型：{@link #onSample} 由 EventCollector 在客户端线程 0.5s 一次调用；
 * {@link #isFrozen} 由快照构造（同在客户端线程）读取。全部单线程访问，无需锁。
 *
 * <p>误触发边界：整包丢弃/死亡掉落也会触发 &gt;80% 骤降 → 误冻结 5s，代价仅是
 * 掩掉窗口内事件与背包字段（丢弃本就不该报、死亡有 death strong 事件兜底），
 * 可接受；总量低于 {@value #MIN_FLOOR} 时不判骤降（近空背包的合法波动不算）。
 */
final class InventoryGuard {

    /** 恢复窗口时长。 */
    private static final long RECOVERY_WINDOW_MS = 5000;
    /** 总量地板：低于此值不判"骤降"（防近空背包波动误触发）。 */
    private static final int MIN_FLOOR = 16;

    private volatile long _frozenUntilMs = 0;
    private volatile boolean _frozen = false;
    /** 上次有效采样的背包总量；-1 = 尚无基线（首采样不判骤降）。 */
    private volatile int _lastTotal = -1;

    /**
     * 每次背包全量采样调用。
     *
     * @param total 背包全量物品计数和（main+armor+offHand 所有非空栈）
     * @param nowMs 当前墙钟
     * @return false = 读数不可信（冻结窗口内），调用方必须跳过增量判定与基线更新
     */
    boolean onSample(int total, long nowMs) {
        if (_lastTotal >= 0 && _lastTotal > MIN_FLOOR
                && (total == 0 || total * 5 < _lastTotal)) {
            _frozen = true;
            _frozenUntilMs = nowMs + RECOVERY_WINDOW_MS;
            return false;
        }
        if (_frozen && nowMs < _frozenUntilMs) {
            return false;
        }
        _frozen = false;
        _lastTotal = total;
        return true;
    }

    /** 快照侧查询：当前是否处于冻结窗口（true = 背包派生字段应沿用上一个有效值）。 */
    boolean isFrozen(long nowMs) {
        return _frozen && nowMs < _frozenUntilMs;
    }
}
