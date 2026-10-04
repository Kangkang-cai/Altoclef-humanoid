package adris.altoclef.util;

import adris.altoclef.Debug;
import adris.altoclef.util.helpers.WorldHelper;

/**
 * 「跳过睡觉」的外部覆盖（2026-10-04，配套 {@code @sleep skip}）。
 *
 * <p><b>它到底是什么</b>：主线 {@code BeatMinecraft2Task.getNightSleepRouting()} 每 tick 在最顶部
 * 跑一次"夜间睡/弄床路由"，返回非 null 就派发睡/找床任务、返回 null 就继续派发真实子任务
 * （收床/装备/下矿/食物—— 也就是"跳过睡觉后启动的分支"）。本类就是一个<b>外部可写的第 5 个
 * 返回 null 的理由</b>：AI 说"这夜别睡了"，路由立刻返回 null，bot 马上回到主线干活。
 *
 * <p><b>为什么必须静态</b>：{@code BeatMinecraft2Task} 会被反复 {@code new} 重建
 * （bridge 的 resume_main、WorldStateGuard 换存档、GamerCommand、GetToOuterEndIslandsTask），
 * 实例字段会随重建丢失。判定源必须与实例生命周期解耦（同 {@code DemandQuota} 的口径）。
 *
 * <p><b>为什么只本夜</b>：与既有 {@code _nightBedGivenUp}（找床超时 ⇒ 本夜放弃）同口径。
 * 白天自动解除，下一个夜晚照常尝试睡觉 —— 否则一次 skip 会让 bot 永久拒绝睡觉。
 * <b>解除点唯一</b>：{@link #autoClearIfDaytime()}，由 {@code AltoClef.onClientTick} 每 tick 调用
 * （那个位置每 tick 必跑，<b>与主线在不在运行无关</b>；若挂在主线的夜间睡眠路由里，一次白天下的
 * skip 会因为"没有主线在跑"而残留到下一个夜晚，变成凭空跳一夜）。
 *
 * <p>⚠️ 语义提醒（写进对端文档）：<b>跳过睡觉 ≠ 天亮</b>。多人服里只要有玩家醒着，夜晚就不会被
 * 跳过；本命令的真实效果是"别浪费时间上床，去干活，等真正的天亮（约 7 分钟真实时间）"。
 */
public final class SleepOverride {

    private static volatile boolean _skipThisNight = false;

    private SleepOverride() {
    }

    /** 命令层调用：本夜不睡、不找床，立刻让睡眠路由让位。 */
    public static void requestSkipThisNight() {
        _skipThisNight = true;
    }

    public static boolean isSkipping() {
        return _skipThisNight;
    }

    /** 手动解除（留给未来的 {@code @sleep auto} 之类的显式恢复；当前无人调用）。 */
    public static void clear() {
        _skipThisNight = false;
    }

    /**
     * 白天自动解除 —— <b>本状态的唯一解除点</b>。
     * 只写 diag 文件、不进聊天栏（放置/心跳类长驻状态不刷屏，1004 用户要求）。
     */
    public static void autoClearIfDaytime() {
        if (!_skipThisNight) return;
        try {
            if (WorldHelper.isBrightDaytime()) {
                _skipThisNight = false;
                Debug.logToFile("altoclef_diag.log",
                        "[SleepSkip] cleared by daytime; night sleep routing re-enabled");
            }
        } catch (Throwable ignored) {
            // 世界未就绪/维度切换瞬间读时间失败: 保持原状, 下 tick 再判。
        }
    }
}
