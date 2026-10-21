package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.PeerPosWatcher;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.RconPlayerOracle;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * (2026-10-21, 用户点名"模仿 1.21.6 定位栏") <b>玩家定位栏</b>：在经验条位置
 * 显示服务器里其他玩家的方向指示器（彩色方块 + 距离分档大小 + 上/下高度箭头）。
 *
 * <h3>与原版 1.21.6 Locator Bar 的对齐与差异</h3>
 * <ul>
 *   <li>对齐：经验条槽位渲染（有经验时让位到经验条上方一行）；120° 扇区映射；
 *       上下箭头；距离分档图标；按名字稳定的颜色。</li>
 *   <li>差异：超出 ±60° 的目标钳制在栏两端显示（原版直接不显示）—— 对 bot
 *       会合导航更有用；指示器上方标注玩家名（bot 多、颜色随机时肉眼可辨）。</li>
 *   <li>跨维度目标不显示（原版同口径：waypoint 是维度本地的）。</li>
 * </ul>
 *
 * <h3>数据源（三路合并，按优先级取最新）</h3>
 * <ol>
 *   <li><b>本地实体</b>（≤48 格实体跟踪范围）：每帧精确坐标；</li>
 *   <li><b>{@link PeerPosWatcher}</b>（本机全部 bot 实例，任意距离，2s 上报）：
 *       远距离显示的关键，用户点名"远距离也要显示"由此满足；</li>
 *   <li><b>{@link RconPlayerOracle}</b>（服务端 RCON，覆盖不跑本模组的真人）：
 *       名单来自 TAB 列表（服务端全量下发，不限距离），逐名按 {@code request()}
 *       的既有节流/退避查询。</li>
 * </ol>
 *
 * <h3>光影安全（用户点名"不要被光影抢占没了"）</h3>
 * 全部绘制走 {@link DrawableHelper#fill} 与 {@code TextRenderer.draw} ——
 * 与原版 HUD 同一条渲染通路（InGameHud 渲染阶段、原版 position_color 着色器）。
 * 不创建自定义 shader program、不改 RenderSystem 全局状态、不碰深度缓冲。
 * Iris / OptiFine 光影只接管<b>世界渲染</b>阶段，HUD 阶段原样执行 ⇒ 本覆盖层
 * 不会被任何光影包吞掉（与左上角任务链 HUD 同机制，已在光影环境下验证可行）。
 *
 * <h3>性能与风险边界</h3>
 * <ul>
 *   <li>纯渲染叠加层：不写任何游戏状态、不消费/不提供任何判定源，画错最坏是
 *       显示难看，不会影响任务链与寻路（符合"修复不引出新问题"守则第①条）。</li>
 *   <li>每帧只做：读并发表（PeerPosWatcher/RCON 缓存）+ 若干次 fill，微秒级。</li>
 *   <li>RCON 触发在渲染线程调用 {@code request()} —— 其内部已有"恒不阻塞 +
 *       单后台线程 + 节流/退避"设计（见该类线程模型章节），安全。</li>
 * </ul>
 */
public final class PlayerLocatorBarOverlay {

    // ==== 几何常量（对齐原版经验条槽位） ====
    /** 原版经验条宽 182（hotbar 同宽），中心偏移 91。 */
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HALF = BAR_WIDTH / 2;
    /** 定位栏高度（比经验条 5px 略高，好辨认）。 */
    private static final int BAR_HEIGHT = 7;
    /** 原版可见扇区半角 60°（总 120°）。超出钳制到栏两端。 */
    private static final float SECTOR_HALF_DEG = 60.0f;
    /** 高度箭头阈值：目标高于/低于视点超过 8 格才画箭头（原版按视锥，这里用固定阈值）。 */
    private static final double VERTICAL_ARROW_DIST = 8.0;

    /** 一次渲染的目标快照。 */
    private record Target(String name, double x, double y, double z) {
    }

    public void render(AltoClef mod, MatrixStack matrices) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity self = mod.getPlayer();
        if (!mod.inGame() || self == null || client.world == null || client.getNetworkHandler() == null) {
            return;
        }

        Dimension selfDim = WorldHelper.getCurrentDimension();
        String selfName = self.getGameProfile().getName();

        // ---- 三路合并 ----
        // key 用小写名去重；本地实体最准，优先登记。
        List<Target> targets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(selfName.toLowerCase(Locale.ROOT));

        // ① 本地实体（≤48 格，每帧精确）
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p == null || p == self) continue;
            String n = p.getGameProfile().getName();
            if (!seen.add(n.toLowerCase(Locale.ROOT))) continue;
            targets.add(new Target(n, p.getX(), p.getY(), p.getZ()));
        }

        // ② PeerPosWatcher（本机 bot，任意距离，2s 上报）
        for (PeerPosWatcher.PeerSnapshot ps : PeerPosWatcher.snapshotAll()) {
            if (!seen.add(ps.name().toLowerCase(Locale.ROOT))) continue;
            if (ps.dim() != selfDim) continue;   // 跨维度不显示（原版同口径）
            targets.add(new Target(ps.name(), ps.pos().x, ps.pos().y, ps.pos().z));
        }

        // ③ TAB 列表 × RCON（覆盖真人玩家；RCON 不可达时静默退避）
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            if (entry == null || entry.getProfile() == null) continue;
            String n = entry.getProfile().getName();
            if (n == null || !seen.add(n.toLowerCase(Locale.ROOT))) continue;
            RconPlayerOracle.request(mod, n);
            var snap = RconPlayerOracle.snapshotOf(n);
            if (snap.isEmpty() || snap.get().dim() != selfDim) continue;
            Vec3d pos = snap.get().pos();
            targets.add(new Target(n, pos.x, pos.y, pos.z));
        }

        if (targets.isEmpty()) return;

        // ---- 几何：经验条槽位，有经验时让位到上一行 ----
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        boolean xpVisible = self.experienceLevel > 0 || self.experienceProgress > 0;
        int barLeft = sw / 2 - BAR_HALF;
        int barY = xpVisible ? (sh - 32 - 16) : (sh - 32 + 2);
        int centerX = sw / 2;

        // 背景（半透明黑）+ 中心朝向刻度（白，= 正前方）
        DrawableHelper.fill(matrices, barLeft - 1, barY - 1, barLeft + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, 0x88000000);
        DrawableHelper.fill(matrices, centerX, barY, centerX + 1, barY + BAR_HEIGHT, 0x90FFFFFF);

        float selfYaw = self.getYaw();
        double eyeY = self.getY() + 1.62;

        // 远的先画（近的后画覆盖在上层）
        targets.sort((a, b) -> Double.compare(dist2(b, self), dist2(a, self)));

        TextRenderer tr = client.textRenderer;
        boolean showLabels = targets.size() <= 6;

        for (Target t : targets) {
            double dx = t.x() - self.getX();
            double dz = t.z() - self.getZ();
            double dy = t.y() - eyeY;

            // 世界方位角 → MC yaw 口径（yaw=0 朝 +Z，90 朝 -X）
            float yawToTarget = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float rel = MathHelper.wrapDegrees(yawToTarget - selfYaw);

            // ±60° 扇区映射到栏宽；超界钳制到两端
            float clamped = MathHelper.clamp(rel, -SECTOR_HALF_DEG, SECTOR_HALF_DEG);
            float frac = clamped / SECTOR_HALF_DEG;
            int mx = Math.round(centerX + frac * (BAR_HALF - 4));
            int my = barY + BAR_HEIGHT / 2;

            int color = colorFor(t.name());

            // 距离分档大小（对齐原版 179/230/281 分界）
            double hdist = Math.sqrt(dx * dx + dz * dz);
            int half;
            if (hdist < 179) half = 3;       // 近：7px 大
            else if (hdist < 230) half = 2;  // 中：5px
            else if (hdist < 281) half = 2;  // 远：5px
            else half = 1;                   // 极远：3px

            // 描边 + 本体
            DrawableHelper.fill(matrices, mx - half - 1, my - half - 1, mx + half + 2, my + half + 2, 0xFF000000 | darken(color));
            DrawableHelper.fill(matrices, mx - half, my - half, mx + half + 1, my + half + 1, 0xFF000000 | color);

            // 上/下高度箭头（目标在视点上方/下方 > 8 格）
            if (dy > VERTICAL_ARROW_DIST) {
                drawArrow(matrices, mx, my - half - 6, true, color);
            } else if (dy < -VERTICAL_ARROW_DIST) {
                drawArrow(matrices, mx, my + half + 3, false, color);
            }

            // 名字标签（bot 多时肉眼可辨；超过 6 人不画防糊）
            if (showLabels) {
                String label = t.name();
                int tw = tr.getWidth(label);
                int lx = mx - tw / 2;
                tr.draw(matrices, label, lx, my - half - 15, 0xFFE8E8E8);
            }
        }
    }

    private static double dist2(Target t, PlayerEntity self) {
        double dx = t.x() - self.getX();
        double dz = t.z() - self.getZ();
        return dx * dx + dz * dz;
    }

    /** 上下小箭头（fill 近似三角形，指向 up=true 表示目标在上方）。ay = 箭头顶端 y。 */
    private static void drawArrow(MatrixStack matrices, int mx, int ay, boolean up, int color) {
        int c = 0xFF000000 | color;
        if (up) {
            // 尖朝上：顶部 2px 宽，向下逐行加宽，共 3 行
            DrawableHelper.fill(matrices, mx, ay, mx + 2, ay + 1, c);
            DrawableHelper.fill(matrices, mx - 1, ay + 1, mx + 3, ay + 2, c);
            DrawableHelper.fill(matrices, mx - 2, ay + 2, mx + 4, ay + 3, c);
        } else {
            // 尖朝下：顶部宽，向下收窄
            DrawableHelper.fill(matrices, mx - 2, ay, mx + 4, ay + 1, c);
            DrawableHelper.fill(matrices, mx - 1, ay + 1, mx + 3, ay + 2, c);
            DrawableHelper.fill(matrices, mx, ay + 2, mx + 2, ay + 3, c);
        }
    }

    /**
     * 按名字稳定的颜色（对齐原版"按 UUID 随机、每玩家固定"的观感：
     * 用名字 hash 定 hue，饱和度/亮度固定，同名恒同色）。
     */
    private static int colorFor(String name) {
        float hue = (name.toLowerCase(Locale.ROOT).hashCode() & 0xFFFFL) / 65536.0f;
        return hsvToRgb(hue, 0.72f, 1.0f);
    }

    /** 小型 HSV→RGB（不引 java.awt，避免模块依赖）。返回 0xRRGGBB。 */
    private static int hsvToRgb(float h, float s, float v) {
        h = (h % 1.0f + 1.0f) % 1.0f;
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        int ri = (int) (r * 255), gi = (int) (g * 255), bi = (int) (b * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** 颜色变暗（描边用）。 */
    private static int darken(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return ((r * 2) / 3 << 16) | ((g * 2) / 3 << 8) | ((b * 2) / 3);
    }
}
