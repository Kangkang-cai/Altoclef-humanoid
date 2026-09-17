package adris.altoclef.record;

import adris.altoclef.AltoClef;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.BeaconScreenHandler;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.HorseScreenHandler;
import net.minecraft.screen.LecternScreenHandler;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;

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

        // ---- (2026-09-17) 容器槽位快照: GUI 打开时逐帧导出整个 handler 的槽位内容 ----
        // 玩家侧背包 self.inv 已有, 但容器侧 (熔炉燃料/输入/输出、箱子内容、合成格
        // 里放了什么) 之前数据里没有 —— 那是训练熔炼/合成任务的直接输入。
        // 只在容器类界面打开时导出, 空槽跳过; 槽位下标 i 就是 ClickSlot 协议的下标。
        sb.append(",\"slots\":[");
        if (p != null && p.currentScreenHandler != null
                && !(p.currentScreenHandler instanceof PlayerScreenHandler)) {
            boolean sFirst = true;
            try {
                for (net.minecraft.screen.slot.Slot slot : p.currentScreenHandler.slots) {
                    net.minecraft.item.ItemStack stack = slot.getStack();
                    if (stack == null || stack.isEmpty()) continue;
                    if (!sFirst) sb.append(',');
                    sFirst = false;
                    sb.append("{\"i\":").append(slot.id);
                    sb.append(",\"id\":");
                    Json.str(sb, StateSampler.itemId(stack.getItem()));
                    sb.append(",\"n\":").append(stack.getCount());
                }
            } catch (Throwable ignored) {
            }
        }
        sb.append(']');
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
            // (2026-09-17 混淆名修复) 绝不能用 getClass().getSimpleName() ——
            // 发布 jar 里 Minecraft 类被 remap 成 intermediary, 拿到的是
            // "class_1723"(熔炉界面)/"class_3858"(玩家背包) 这种不可读的名字
            // (20260917-100000 场次实测, act.screen 全段都是 class_xxxx)。
            // instanceof 判定写在源码里是 named, 编译期 remap → 运行期一致, 可靠。
            ScreenHandler h = p.currentScreenHandler;
            if (h instanceof PlayerScreenHandler) return "player";
            if (h instanceof CraftingScreenHandler) return "crafting_table";
            if (h instanceof FurnaceScreenHandler) return "furnace";
            if (h instanceof BlastFurnaceScreenHandler) return "blast_furnace";
            if (h instanceof SmokerScreenHandler) return "smoker";
            if (h instanceof Generic3x3ContainerScreenHandler) return "dispenser_like";
            if (h instanceof ShulkerBoxScreenHandler) return "shulker_box";
            if (h instanceof HopperScreenHandler) return "hopper";
            if (h instanceof GenericContainerScreenHandler container) {
                // 箱子/木桶: 行数就是容量 (1..6), 训练时能区分 27 格箱与 54 格大箱
                return "container_" + container.getRows() + "x9";
            }
            if (h instanceof AnvilScreenHandler) return "anvil";
            if (h instanceof EnchantmentScreenHandler) return "enchanting_table";
            if (h instanceof BrewingStandScreenHandler) return "brewing_stand";
            if (h instanceof BeaconScreenHandler) return "beacon";
            if (h instanceof GrindstoneScreenHandler) return "grindstone";
            if (h instanceof LoomScreenHandler) return "loom";
            if (h instanceof StonecutterScreenHandler) return "stonecutter";
            if (h instanceof CartographyTableScreenHandler) return "cartography_table";
            if (h instanceof SmithingScreenHandler) return "smithing_table";
            if (h instanceof LecternScreenHandler) return "lectern";
            if (h instanceof MerchantScreenHandler) return "merchant";
            if (h instanceof HorseScreenHandler) return "horse";
            return "other";
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供 meta.json 记录一次: 本 tick 是否真的"在游戏内"(客户端对象齐备)。 */
    static boolean clientReady(AltoClef mod) {
        return mod.getPlayer() != null && mod.getWorld() != null && MinecraftClient.getInstance() != null;
    }
}