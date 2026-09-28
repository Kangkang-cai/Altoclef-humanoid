package adris.altoclef.util.helpers;

/**
 * (2026-09-28 L1) 异常恢复（绕行 / 挖除）的**参数与语义口径集中点**。
 *
 * <p>背景: "目标够不着"在这套代码里由四条链路各自处理, 参数散落在两个大文件里,
 * 同类量取值不同却互不可见 (改一处不知道还有第二处)。本类只做"集中 + 显式化",
 * <b>不含任何控制流</b> —— 各链路的触发条件、动作选择、退出策略保持原样。
 *
 * <p>四条链路 (详见 memory `detail/14-recovery-unification.md`):
 * <ol>
 *   <li>实体交互 {@code AbstractDoToEntityTask}: LOS 被挡 → 先绕位, 2s 无果 → 交子类挖除</li>
 *   <li>击杀子类 {@code AbstractKillEntityTask}: 覆写挖除实现 (树叶清障等);
 *       2026-09-28 (b) 定案 = "被挡直接挖 / 站桩交战", 不再走绕位</li>
 *   <li>方块交互 {@code InteractWithBlockTask.tickDetour}: baritone 无路径 + 近场 3s 无位移
 *       → 直接挖走廊。<b>没有独立"绕行"环节</b> —— "绕"的载体是 baritone 本身,
 *       gate 里的 {@code !isPathing && path.isEmpty} 即"baritone 已经试过并放弃"</li>
 *   <li>猪灵交易 {@code TradeWithPiglinsTask}: 复用第 1 条的钩子</li>
 * </ol>
 *
 * <p><b>两个时长为什么不同 (调参时请一起看)</b>:
 * <ul>
 *   <li>{@code ENTITY_OBSTRUCTION_PERSIST_SECONDS = 2} —— 触发源是"视线被挡", 几何上
 *       通常是贴着方块沿/目标擦身, 绕位多半无效, 所以快挖。</li>
 *   <li>{@code BLOCK_DETOUR_TRIGGER_SECONDS = 3} —— 触发源是"baritone 算不出路径",
 *       需要给它足够的重算时间 (现场 20260923-085300: A* 每 6s 超时重启 57 万节点),
 *       过早介入反而打断正常寻路。</li>
 * </ul>
 * 若将来要把两者对齐, 改本类即可同时影响两处 (各文件保留本地别名指向此处)。
 */
public final class RecoveryTuning {

    private RecoveryTuning() {
    }

    // ==================== 共用口径 ====================

    /**
     * "有没有真实移动"的判定口径: 位移**平方**阈值, 对应 {@code 0.2} 格。
     *
     * <p>使用方必须挂<b>墙钟锚点 + 真实位移</b>, 不能用 {@code moveOk} / 瞬时位移这类
     * 会闪断的值 (项目铁律: 兜底计时器互为清零源 ⇒ 永不 elapsed)。
     */
    public static final double STUCK_MOVE_EPSILON_SQ = 0.04;

    // ==================== 实体交互侧 (AbstractDoToEntityTask) ====================

    /** 视线被挡持续此秒数仍无可见瞄准点 → 交子类 {@code onObstructionPersistent} 清障 (挖)。 */
    public static final double ENTITY_OBSTRUCTION_PERSIST_SECONDS = 2;

    /** 绕位目标粘滞时间 (秒): 选定一个绕位点后不再每 tick 换, 消除相邻候选横跳。 */
    public static final double ENTITY_REPOSITION_STICKY_SECONDS = 3;

    /** 连续可见满此秒数才清零"持续被挡"计时 (防瞬移/探头的单拍可见打断计时)。 */
    public static final double ENTITY_LOS_CLEAR_GRACE_SECONDS = 2;

    /**
     * "持续被挡"计时的**陈旧残留**过期口径 (tick)。该计时按目标 UUID 存活于 static map,
     * 不随任务实例重建清零; 正常路径下 40 tick 内必被"触发挖除"或"可见清零"消化,
     * 超过本值说明是上一场交战遗留 → 重置起点 (不立刻触发挖掘)。
     */
    public static final long LOS_TIMER_STALE_TICKS = 600;

    /** 实体侧计时 map 条目上限 (超过则清陈旧条目), 与 {@code _meleeEngageAnchor} 同模式。 */
    public static final int LOS_TIMER_MAP_CAPACITY = 32;

    // ==================== 方块交互侧 (InteractWithBlockTask.tickDetour) ====================

    /** 近场 (≤3 格) 且 baritone 无路径时, 真实无位移持续此秒数 → 触发走廊计划。 */
    public static final double BLOCK_DETOUR_TRIGGER_SECONDS = 3;

    /** 单轮走廊最多挖几块实心方块 (BFS 空气 cost=1 / 可挖 cost=100, 优先少挖)。 */
    public static final int BLOCK_DETOUR_MAX_BLOCKS = 4;

    /** 同一目标最多触发几轮走廊, 耗尽 → {@code startEscape} + wander。 */
    public static final int BLOCK_DETOUR_MAX_ROUNDS = 2;

    /** 单块走廊方块挖掘超时 (ms), 超过视为挖不动 → 走 detourBlockTimeout 分支。 */
    public static final long BLOCK_DETOUR_BLOCK_TIMEOUT_MS = 10_000;

    /**
     * (2026-09-28 (i) 先绕后挖) 挖走廊**之前**先把交互站位半径放宽到此值, 再给一次
     * 重新寻路的机会。
     *
     * <p><b>为什么有效</b>: {@code createGoalForInteract} 实际用的半径恒为 1
     * (字段 {@code reachDistance} 恒 0 ⇒ {@code Math.max(1, 0) = 1}), 因此 in-goal
     * 只有目标的 **6 个面邻格**。目标贴脸被实心环封堵时, 那 6 格全站不进去 ⇒ baritone
     * 无解, 唯一出路是挖 (现场 20260923-085300: GoalNear 站位环全实心, A* 每 6s 超时
     * 重启 57 万节点, 站桩 146s —— 这正是 {@code tickDetour} 被引入的原因)。
     * 放宽到 2 后 in-goal 扩到"距离 ≤2"的斜位/稍远位, 靠真实方块 reach (约 4.5) 仍能
     * 点到目标 —— 即人类"退半步、斜着站"的做法。
     *
     * <p><b>顺序</b>: 半径 1 无路径 + 3s 无位移 → 放宽到本值 (再给一次 3s) → 仍不行才挖走廊。
     */
    public static final int WIDENED_STAND_RADIUS = 2;

    /** "已放宽过站位半径"的目标集合容量上限 (static, 跨实例存活)。 */
    public static final int WIDENED_STAND_MAP_CAPACITY = 256;
}
