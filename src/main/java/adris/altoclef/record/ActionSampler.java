package adris.altoclef.record;

import adris.altoclef.AltoClef;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Optional;

/**
 * (2026-09-17 数据集录制) 通道 G —— 动作信号 (每帧全量)。
 *
 * <p>关键设计: 动作是 <b>API 级</b> 而不是键鼠级。聚合点只在 tick 末尾一次性读
 * 各子系统状态, 不在几十个调用点插桩 —— 插桩会污染行为, 而聚合读取天然只读。
 *
 * <p>两个按键来源必须分开记, 否则完全读不到真实移动意图:
 * <ul>
 *   <li>{@code keys} = AltoClef 自己 {@code InputControls} 按下的键 (直游/进食/举盾等)</li>
 *   <li>{@code bkeys} = baritone 的 input override (寻路走出的移动) —— 它走
 *       {@code InputOverrideHandler} 覆盖, <b>不</b> setPressed, 所以 vanilla
 *       KeyBinding 和 {@code isHeldDown} 都读不到 (此坑见 NavStuckWatchdog 注释)</li>
 * </ul>
 */
final class ActionSampler {

    private ActionSampler() {}

    private static final Input[] INPUTS = Input.values();

    /** 返回 "act" 通道的 JSON 对象字面量。 */
    static String sample(AltoClef mod) {
        StringBuilder sb = new StringBuilder(384);
        sb.append('{');

        // ---- AltoClef 自己的按键 ----
        sb.append("\"keys\":[");
        boolean first = true;
        for (Input in : INPUTS) {
            boolean down;
            try {
                down = mod.getInputControls().isHeldDown(in);
            } catch (Throwable t) {
                continue;
            }
            if (!down) continue;
            if (!first) sb.append(',');
            first = false;
            Json.str(sb, in.name());
        }
        sb.append(']');

        // ---- baritone 覆盖的按键 (真实寻路移动) ----
        sb.append(",\"bkeys\":[");
        first = true;
        boolean pathing = false;
        boolean customGoal = false;
        boolean builder = false;
        int pathLen = 0;
        int[] pathDest = null;
        try {
            var baritone = mod.getClientBaritone();
            if (baritone != null) {
                for (Input in : INPUTS) {
                    boolean down = baritone.getInputOverrideHandler().isInputForcedDown(in);
                    if (!down) continue;
                    if (!first) sb.append(',');
                    first = false;
                    Json.str(sb, in.name());
                }
                pathing = baritone.getPathingBehavior().isPathing();
                customGoal = baritone.getCustomGoalProcess().isActive();
                builder = baritone.getBuilderProcess().isActive();
                Optional<IPath> p = baritone.getPathingBehavior().getPath();
                if (p.isPresent()) {
                    pathLen = p.get().length();
                    var dest = p.get().getDest();
                    if (dest != null) pathDest = new int[] {dest.getX(), dest.getY(), dest.getZ()};
                }
            }
        } catch (Throwable ignored) {
            // baritone 未就绪时当成"无寻路", 不打断整帧
        }
        sb.append(']');

        // ---- baritone 导航状态 ----
        sb.append(",\"pathing\":").append(pathing);
        sb.append(",\"customGoal\":").append(customGoal);
        sb.append(",\"builder\":").append(builder);
        sb.append(",\"pathLen\":").append(pathLen);
        sb.append(",\"pathDest\":");
        if (pathDest == null) sb.append("null");
        else Json.ivec(sb, pathDest);

        // ---- 采矿进度 (AltoClef 自己的破坏跟踪) ----
        sb.append(",\"breaking\":");
        try {
            if (mod.getControllerExtras().isBreakingBlock()) {
                var bp = mod.getControllerExtras().getBreakingBlockPos();
                sb.append("{\"pos\":");
                Json.ivec(sb, bp.getX(), bp.getY(), bp.getZ());
                sb.append(",\"prog\":").append(Json.f(mod.getControllerExtras().getBreakingBlockProgress()));
                sb.append('}');
            } else {
                sb.append("null");
            }
        } catch (Throwable t) {
            sb.append("null");
        }

        // ---- 容器/界面 ----
        ClientPlayerEntity p = mod.getPlayer();
        sb.append(",\"screen\":");
        Json.str(sb, screenName(p));
        sb.append(",\"syncId\":").append(p == null ? -1 : p.currentScreenHandler.syncId);
        // ---- 攻击冷却 (是否处于可出刀窗口, 近战节奏的关键信号) ----
        sb.append(",\"atkCd\":");
        sb.append(p == null ? "null" : Json.f(p.getAttackCooldownProgress(0f)));
        sb.append(",\"swing\":");
        sb.append(p == null ? "null" : String.valueOf(p.handSwinging));

        sb.append('}');
        return sb.toString();
    }

    private static String screenName(ClientPlayerEntity p) {
        if (p == null || p.currentScreenHandler == null) return null;
        try {
            return p.currentScreenHandler.getClass().getSimpleName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供 meta.json 记录一次: 本 tick 是否真的"在游戏内"(客户端对象齐备)。 */
    static boolean clientReady(AltoClef mod) {
        return mod.getPlayer() != null && mod.getWorld() != null && MinecraftClient.getInstance() != null;
    }
}