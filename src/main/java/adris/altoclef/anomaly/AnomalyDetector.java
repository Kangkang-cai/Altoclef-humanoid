package adris.altoclef.anomaly;

/**
 * (2026-09-11 异常看门狗) 检测器接口。
 *
 * 三条硬约束, 违反即视为设计缺陷:
 *
 * 1. {@link #check} 必须**严格只读**。禁止调用任何会改变任务状态的方法
 *    (Task.stop / TaskChain 换主任务 / BotBehaviour push|pop / baritone 重发寻路)。
 *    理由: 看门狗一旦产生行为副作用, 它自己就成了新的异常源 —— 这个项目里
 *    "修一个问题引入新振荡"的记录不止一次。
 *
 * 2. {@link #check} 每 tick 都会被调用, 必须廉价。禁止全量扫描方块 / 实体 / 容器。
 *    需要历史样本请用滑动窗口 + 环形缓冲, 不要每 tick 重算。
 *
 * 3. 跨 tick 状态必须能通过 {@link #reset()} 清空。换维度 / 换世界时坐标不可比,
 *    watchdog 会调用 reset() 防止拿旧世界的样本判断新世界。
 *
 * 新增检测器: 实现本接口, 在 AnomalyWatchdog.ensureRegistered() 里注册即可,
 * 主循环(TaskRunner)无需改动。
 */
public interface AnomalyDetector {

    /** 检测器短名, 用于日志与去重键前缀。 */
    String name();

    /** 返回 null 表示当前无异常。 */
    Anomaly check(AnomalyContext ctx);

    /** 清空所有跨 tick 状态。换世界 / 关开看门狗时由 watchdog 调用。 */
    default void reset() {
    }
}
