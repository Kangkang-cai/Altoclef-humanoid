package adris.altoclef.util.helpers;

import adris.altoclef.bridge.BridgeConfig;

/**
 * (2026-10-06 多 bot 随行, 用户拍板) bot 角色（主 bot / 次 bot）与其派生语义的
 * <b>单一来源</b>。
 *
 * <h3>角色从哪来</h3>
 * {@code altoclef/configs/bridge.json} 的 {@code "role": "main"|"sub"}（见
 * {@link BridgeConfig#role}）。缺省 / 未知值 = {@code "main"}。
 *
 * <h3>为什么要有它</h3>
 * 多 bot 同时开工时，次 bot 的定位是"跟着主 bot 走、缺料向主 bot 要"，因此它
 * <b>不需要像主 bot 那样囤货</b>（用户 2026-10-06 原话："要求不要像主 bot 那么高"）。
 * 本类提供把"主 bot 的目标量"折算成本实例目标量的唯一入口，避免各处各写一个系数。
 *
 * <h3>作用范围（刻意收窄）</h3>
 * 只降<b>囤积目标</b>：
 * <ul>
 *   <li>木头：{@code minWoodLogs} 与 {@code woodRestockThreshold}；</li>
 *   <li>食物：囤积峰值目标（{@code foodPeakUnits} / {@code preNetherFoodUnits}）。</li>
 * </ul>
 * <b>不降</b>"危险线"类阈值（如 {@code minFoodUnits} 断粮线）—— 那是"别饿死"的地板，
 * 与角色无关，降它只会让次 bot 更容易饿死。
 *
 * <h3>不缓存的理由</h3>
 * 每次直读 {@link BridgeConfig#getInstance()}。配置可在运行时重载
 * （{@code ConfigHelper.reloadAllConfigs}），缓存就必须配一套失效通知 —— 而本方法
 * 调用频率是"每 tick 数次字段读"，不构成性能问题。
 */
public final class BotRole {

    private BotRole() {
    }

    /**
     * 次 bot 的目标量相对主 bot 的比例。
     *
     * <p>取 1/3：木头 25→8、食物峰值 120→40 / 硬闸 150→50。次 bot 只需撑住自己一段
     * 行程，缺口由"向主 bot 要"补齐（{@code @give} 通道）。
     */
    public static final double SUB_TARGET_SCALE = 1.0 / 3.0;

    /** 本实例是否为次 bot（{@code bridge.json} 的 {@code role} 为 {@code "sub"}，大小写不敏感）。 */
    public static boolean isSub() {
        BridgeConfig cfg = BridgeConfig.getInstance();
        String role = (cfg == null) ? null : cfg.role;
        return role != null && "sub".equalsIgnoreCase(role.trim());
    }

    /**
     * 把"主 bot 的目标量"折算成本实例的目标量。
     *
     * <p>主 bot 原样返回；次 bot 按 {@link #SUB_TARGET_SCALE} 下调，且<b>下界为 1</b> ——
     * 返回 0 会被调用方读成"目标为 0"（无需收集 / 已完成），语义正好反过来。
     * 入参 {@code <= 0} 表示"该阈值已被禁用"，原样返回，不做任何折算。
     */
    public static int scaleTarget(int mainValue) {
        if (mainValue <= 0 || !isSub()) return mainValue;
        return Math.max(1, (int) Math.round(mainValue * SUB_TARGET_SCALE));
    }

    /** 诊断 / 日志用 token：{@code "main"} 或 {@code "sub"}。 */
    public static String token() {
        return isSub() ? "sub" : "main";
    }
}
