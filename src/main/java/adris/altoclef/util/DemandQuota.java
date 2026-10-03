package adris.altoclef.util;

import net.minecraft.item.Item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 需求覆盖层（2026-10-03 `@quota`）：AI 角色给<b>本 bot</b> 下达的"收集份额"。
 *
 * <p>为什么需要（用户 1003 现场）：主线的每个需求都是<b>私有且闭合</b>的 ——
 * {@code BeatMinecraft2Task.getEyesOfEnderTask} 只读自己的背包，把
 * {@code targetEyes}(=16) 折算成 {@code ceil(16/2)=8} 根烈焰棒，于是 3 个 bot
 * 各自冲 8 根（最多 24 根），而团队实际只需 ≈8 根。对端此前只能从
 * {@code /status.mainlineProgress} <b>看</b> {@code N/8}，改不了、也分摊不了。
 *
 * <p>语义：某物品的<b>绝对持有目标</b>。
 * <ul>
 *   <li>设了 = 本 bot 只负责凑够这个数；数量 <b>0</b> = "这块归别人，我不管"；</li>
 *   <li>没设 = 走主线原有口径（{@code configs/beat_minecraft.json}），逐字节不变。</li>
 * </ul>
 *
 * <p>只覆盖三条<b>独立</b>需求线：{@code blaze_rod}（烈焰棒）、{@code ender_pearl}
 * （末影珍珠）、{@code ender_eye}（末影之眼 = 本 bot 的总份额）。派生物（烈焰粉）
 * 不单独设，由棒折算 —— 与主线固有的"1 棒 = 2 粉"口径一致。
 *
 * <p>消费点单一：{@code BeatMinecraft2Task.getEyesOfEnderTask}（全仓唯一的需求计算处）。
 * 之所以敢动主线这一处，正因为它是单点；未设配额时不受任何影响。
 *
 * <p>线程：HTTP 线程写（命令派发）、客户端线程读（主线 tick），用 ConcurrentHashMap。
 * 静态 = 每 JVM 实例独立（Prism 多开各一份），正是"每个 bot 各自份额"所需。
 *
 * <p>⚠ 生命周期：本表<b>跨世界/跨会话保留</b>（AI 的显式分派，不随存档切换清空）。
 * 需要复位用 {@code @quota all -1}。
 */
public final class DemandQuota {

    private static final Map<Item, Integer> QUOTA = new ConcurrentHashMap<>();

    private DemandQuota() {}

    /** 设份额（绝对持有目标，>=0）；count &lt; 0 = 取消该物品的份额（回主线默认）。 */
    public static void set(Item item, int count) {
        if (item == null) return;
        if (count < 0) QUOTA.remove(item);
        else QUOTA.put(item, count);
    }

    /** 取消某物品份额。 */
    public static void clear(Item item) {
        if (item != null) QUOTA.remove(item);
    }

    /** 清空全部份额（回到主线默认口径）。 */
    public static void clearAll() {
        QUOTA.clear();
    }

    /** 读份额；{@code null} = 未设（消费方走原逻辑）。 */
    public static Integer get(Item item) {
        return item == null ? null : QUOTA.get(item);
    }

    /** 是否设过该物品的份额。 */
    public static boolean has(Item item) {
        return item != null && QUOTA.containsKey(item);
    }
}
