package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.block.BlockState;
import net.minecraft.block.LightBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * (2026-10-03) 隐形光源照明 —— bot 在方块光照不足的环境 (洞穴/下界) 中,
 * 由服务端通过 RCON 在 bot 脚下放置原版光源方块 ({@code minecraft:light},
 * 亮度 15、完全隐形、无碰撞), 离开超过阈值后自动回收。
 *
 * <h3>背景</h3>
 * 用户关掉永久夜视以保留光影的阴影美学后, 需要"至少身边一直看得见"。
 * 客户端 Glowing 描边 (见 {@code AltoClef#tickPermanentGlowing}) 只能标出 bot
 * 本体, 且被主流光影包吃掉 (outline 是独立后处理 pass, 主流包不渲染);
 * 火把会污染画面且光照只有 14。光源方块是 1.18.2 原版"隐形火把":
 * 真实方块光照, 光影包照常计算明暗 —— 阴影保留, 只是 bot 走过的地方是亮的。
 *
 * <h3>为什么走 RCON 而不是背包放置 (用户拍板)</h3>
 * <ul>
 *   <li>光源方块不可合成、生存拿不到, 只能指令获得;</li>
 *   <li>进背包会撞上丢垃圾档位 (detail/18: 无价值物品会被丢弃) 与腾位穿甲
 *       (detail/31) 两套既有坑 —— 2026-09-30 刚修过"误丢药水";</li>
 *   <li>{@code /setblock} 让背包零参与、任务输入 (装备/瞄准/点击) 完全无感,
 *       任何任务链中途都能安全触发。</li>
 * </ul>
 *
 * <h3>判据与出口 (铁律: hold/refuse 分支必须有出口)</h3>
 * <ul>
 *   <li>放置: 脚下方块光照 &lt; {@code invisibleLightMinBlockLight}(默认 8)
 *       且 距最近已放光源 &gt; {@code invisibleLightSpacingBlocks}(默认 12)
 *       且 脚下是空气。只用方块光照 (LightType.BLOCK): 地表夜晚天空光 15
 *       不触发, 避免地表夜间刷屏; 洞穴/下界才补光。</li>
 *   <li>回收: 同维度且欧氏距离 &gt; {@code invisibleLightRecycleDistance}
 *       (默认 32) → {@code /setblock ... air}。每拍最多回收 2 条防突发。</li>
 *   <li>失败出口: 放置后靠客户端世界方块状态验证 (服务端方块更新包同步回
 *       客户端), 3s 未生效计一次失败, 连续 3 次失败 → 本会话停用 (一次性
 *       gate, 不无限重试刷日志)。典型失败原因: 服务端 rcon 未开启。</li>
 *   <li>账本上限 512 条: 超限强制回收最老的一条, 防长会话膨胀。</li>
 * </ul>
 *
 * <h3>线程与跨世界</h3>
 * <ul>
 *   <li>RCON 网络在单线程后台 executor 执行 (fire-and-forget), 主线程只投递
 *       字符串 —— RCON 不可达时 2s connect timeout 绝不阻塞游戏线程。</li>
 *   <li>账本/待验证状态只增删于客户端 tick 线程。</li>
 *   <li>跨世界残留: 账本含维度名, 回收只对同维度生效 (RCON 控制台默认在主世界
 *       执行, 命令统一包 {@code /execute in <dim> run ...}, 下界坐标不会误改
 *       主世界); 换存档时由 {@code WorldStateGuard} 调 {@link #onWorldBoundary()}
 *       清空。</li>
 * </ul>
 */
public final class InvisibleLightHelper {

    private InvisibleLightHelper() {
    }

    /** 已放置光源账本条目 (维度 + 坐标; BlockPos 统一 immutable)。 */
    private static final class PlacedLight {
        final String dimension;
        final BlockPos pos;

        PlacedLight(String dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }
    }

    /** 账本上限: 长会话防膨胀; 超限强制回收最老一条。 */
    private static final int LEDGER_CAP = 512;

    /** 每拍最多回收几条 (RCON 突发控制)。 */
    private static final int RECYCLE_BUDGET_PER_TICK = 2;

    /** 放置验证窗口 (tick): 超时算一次失败。3s 足够方块更新包往返。 */
    private static final int PLACE_VERIFY_TICKS = 60;

    /** 连续放置失败几次后本会话停用 (fail-fast 出口)。 */
    private static final int MAX_FAIL_STREAK = 3;

    /** 已放置光源账本。只在客户端 tick 线程读写。 */
    private static final ArrayList<PlacedLight> PLACED = new ArrayList<>();

    /** 在途放置 (null = 无); 验证通过前不发新指令。 */
    private static BlockPos _pendingPlace;
    private static String _pendingDimension;
    private static int _pendingTicks;

    /** 连续放置失败计数。 */
    private static int _failStreak = 0;

    /** 本会话停用标记 (连续失败触发; 换存档时复位)。 */
    private static boolean _disabledThisSession = false;

    /** RCON 单线程后台 executor, daemon 线程不挡退出。 */
    private static final ExecutorService RCON_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InvisibleLight-RCON");
        t.setDaemon(true);
        return t;
    });

    /** 换存档清账本 (WorldStateGuard 回调)。停用标记复位: 换服务器给一次新机会。 */
    public static void onWorldBoundary() {
        PLACED.clear();
        _pendingPlace = null;
        _pendingDimension = null;
        _pendingTicks = 0;
        _failStreak = 0;
        _disabledThisSession = false;
    }

    /** 由 {@code AltoClef#onClientTick} 每客户端 tick 调用 (inGame 段内)。 */
    public static void tick(AltoClef mod) {
        if (_disabledThisSession) return;
        adris.altoclef.Settings settings = mod.getModSettings();
        if (settings == null || !settings.isInvisibleLightEnabled()) return;
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null || mod.getWorld() == null || !player.isAlive()) return;

        String currentDim = mod.getWorld().getRegistryKey().getValue().toString();

        // ---- 1) 回收: 同维度、离开超阈值 (每拍限额, 防突发) ----
        double recycleDistSq = settings.getInvisibleLightRecycleDistance()
                * settings.getInvisibleLightRecycleDistance();
        int recycleBudget = RECYCLE_BUDGET_PER_TICK;
        Iterator<PlacedLight> it = PLACED.iterator();
        while (it.hasNext() && recycleBudget > 0) {
            PlacedLight light = it.next();
            if (!light.dimension.equals(currentDim)) continue;
            if (player.squaredDistanceTo(light.pos.getX(), light.pos.getY(), light.pos.getZ())
                    > recycleDistSq) {
                it.remove();
                sendRcon(mod, light.dimension, String.format(
                        "setblock %d %d %d minecraft:air",
                        light.pos.getX(), light.pos.getY(), light.pos.getZ()), "回收");
                recycleBudget--;
            }
        }

        // ---- 2) 在途验证: 服务端方块更新包同步回客户端世界后算成功 ----
        if (_pendingPlace != null) {
            BlockState state = mod.getWorld().getBlockState(_pendingPlace);
            if (state.getBlock() instanceof LightBlock) {
                PLACED.add(new PlacedLight(_pendingDimension, _pendingPlace));
                Debug.logMessage(String.format("[InvisibleLight] 放置光源 %s @ %s (账本 %d 条)",
                        _pendingDimension, _pendingPlace.toShortString(), PLACED.size()));
                _pendingPlace = null;
                _pendingDimension = null;
                _failStreak = 0;
                // 账本超限: 强制回收最老一条
                if (PLACED.size() > LEDGER_CAP) {
                    PlacedLight oldest = PLACED.remove(0);
                    sendRcon(mod, oldest.dimension, String.format(
                            "setblock %d %d %d minecraft:air",
                            oldest.pos.getX(), oldest.pos.getY(), oldest.pos.getZ()), "超限回收");
                }
                return;
            }
            if (++_pendingTicks > PLACE_VERIFY_TICKS) {
                _pendingPlace = null;
                _pendingDimension = null;
                if (++_failStreak >= MAX_FAIL_STREAK) {
                    _disabledThisSession = true;
                    Debug.logWarning("[InvisibleLight] 连续 " + _failStreak
                            + " 次放置未生效 (大概率服务端 rcon 未开启/不可达), 本次运行停用");
                }
            }
            // 在途期间不发新指令
            return;
        }

        // ---- 3) 放置判据 ----
        BlockPos feet = player.getBlockPos();
        if (mod.getWorld().getLightLevel(LightType.BLOCK, feet)
                >= settings.getInvisibleLightMinBlockLight()) return;
        if (!mod.getWorld().getBlockState(feet).isAir()) return;
        int spacingSq = settings.getInvisibleLightSpacingBlocks()
                * settings.getInvisibleLightSpacingBlocks();
        for (PlacedLight light : PLACED) {
            if (light.dimension.equals(currentDim)
                    && light.pos.getSquaredDistance(feet) < spacingSq) return;
        }

        // ---- 4) 发送放置指令, 进入在途验证 ----
        sendRcon(mod, currentDim, String.format(
                "setblock %d %d %d minecraft:light",
                feet.getX(), feet.getY(), feet.getZ()), "放置");
        _pendingPlace = feet.toImmutable();
        _pendingDimension = currentDim;
        _pendingTicks = 0;
    }

    /** 投递一条 {@code /execute in <dim> run setblock ...} 到 RCON 后台线程。 */
    private static void sendRcon(AltoClef mod, String dimension, String setblock, String purpose) {
        String command = String.format("execute in %s run %s", dimension, setblock);
        String host = mod.getModSettings().getRconHost();
        int port = mod.getModSettings().getRconPort();
        String password = mod.getModSettings().getRconPassword();
        RCON_EXECUTOR.execute(() -> {
            try {
                String feedback = RconClient.sendCommand(host, port, password, command);
                // 回收指令的失败只有日志代价 (留下一块隐形光源, 无害);
                // 放置失败由主线程的验证窗口兜底 (fail-fast)。
                if (feedback != null && feedback.contains("Could not set the block")) {
                    Debug.logWarning("[InvisibleLight] RCON " + purpose + "未生效: " + feedback.trim());
                }
            } catch (IOException e) {
                Debug.logWarning("[InvisibleLight] RCON " + purpose + "失败 (" + e + ")");
            }
        });
    }

    /**
     * 极简 RCON 客户端 (Source RCON 协议, 与原版 server.properties 的 rcon 对应)。
     * 每条命令一个短连接: 本功能触发频率极低 (黑暗中每 12 格一条),
     * 短连接最简单且无陈旧连接问题。
     *
     * <p>协议细节: 整数均为【小端】(Java DataInputStream 是大端, 必须手写 LE);
     * 包结构 = len(int32) + requestId(int32) + type(int32) + payload + 2 个 NUL。
     * 认证: type=3, 成功回包 requestId 同值 / 失败为 -1; 命令: type=2。
     */
    private static final class RconClient {
        private static final int TYPE_AUTH = 3;
        private static final int TYPE_COMMAND = 2;

        private RconClient() {
        }

        static String sendCommand(String host, int port, String password, String command)
                throws IOException {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2000);
                socket.setSoTimeout(3000);
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream()));
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(socket.getInputStream()));

                writePacket(out, 7, TYPE_AUTH, password);
                Packet auth = readPacket(in);
                if (auth == null) throw new IOException("认证无响应");
                if (auth.requestId == -1) throw new IOException("认证被拒绝 (密码错误?)");

                writePacket(out, 8, TYPE_COMMAND, command);
                Packet resp = readPacket(in);
                if (resp == null) throw new IOException("命令无响应");
                return resp.payload;
            }
        }

        private static void writePacket(DataOutputStream out, int requestId, int type,
                                        String payload) throws IOException {
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            int len = 4 + 4 + body.length + 2;
            writeLeInt(out, len);
            writeLeInt(out, requestId);
            writeLeInt(out, type);
            out.write(body);
            out.write(0);
            out.write(0);
            out.flush();
        }

        private static Packet readPacket(DataInputStream in) throws IOException {
            int len = readLeInt(in);
            if (len < 10 || len > 4096) throw new IOException("非法包长度 " + len);
            int requestId = readLeInt(in);
            int type = readLeInt(in);
            int payloadLen = len - 10;
            byte[] body = new byte[payloadLen];
            in.readFully(body);
            in.readFully(new byte[2]); // 两个 NUL
            return new Packet(requestId, type, new String(body, StandardCharsets.UTF_8));
        }

        private static void writeLeInt(DataOutputStream out, int v) throws IOException {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 24) & 0xFF);
        }

        private static int readLeInt(DataInputStream in) throws IOException {
            int b0 = in.read(), b1 = in.read(), b2 = in.read(), b3 = in.read();
            if ((b0 | b1 | b2 | b3) < 0) throw new EOFException();
            return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
        }

        private record Packet(int requestId, int type, String payload) {
        }
    }
}
