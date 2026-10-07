package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;

/**
 * (2026-09-17 跨世界污染统一口径) 全局唯一的"世界标识"来源。
 *
 * 背景: 跨会话记忆此前五处各写各的 key, 其中四处用存档【显示名】
 * (`SaveProperties.getLevelName()`) —— 新建世界默认全叫"新的世界", 两个
 * 存档 key 完全相同, 上个世界的坐标被当成本世界的导航目标。
 * 2026-09-10 已实锤过一次: 650 格外假寻路卡死 29s(见 PortalMemoryStore 注释),
 * 当时只把传送门一处改成目录名, 其余四处沿用至今。
 *
 * 唯一可靠的区分是存档【目录名】—— `WorldSavePath.ROOT` 的文件名, 每个新世界
 * 自动递增 (w1/w2/...), 物理唯一。本类把它抽成单一实现, 凡需"按存档隔离"
 * 的状态一律调这里, 不再各写一份。
 *
 * "w2:" 前缀 = 【目录名口径】的版本标记(沿用 PortalMemoryStore 既有约定),
 * 作用是让旧的 LevelName 格式 key 永不匹配 → 存量脏记录自动作废。
 *
 * 解析顺序 (2026-09-29 多人服纳入范围):
 *  1. 单人 / 局域网主机: 集成服务器可用 → 存档【目录名】, 取不到再退显示名;
 *     此时 `getCurrentServerEntry()` 为 null, 不会误判。
 *  2. 专用服务端(多人): 集成服务器恒 null → 用 `ServerInfo.address`(或退化到
 *     `name`), 前缀 `srv:` 标记来源。多人下所有服务器此前共享 "?", 换服不隔离,
 *     且 `WorldStateGuard` 的切世界清档检测永不触发 —— 2026-09-29 修复。
 *  3. 都取不到(主菜单 / 启动早期) → "?"。
 *  4. (2026-10-07 服务器世界重建) 专用服分支再拼【世界种子标识】:
 *     `srv:<地址>@<sha256Seed>`。起因: 2) 只认服务器地址, 把服务器存档
 *     (`world/`) 删掉重建后地址不变 ⇒ key 不变 ⇒ 上个世界的地标/传送门/自挖/
 *     洞穴/堡垒记忆整份被当成本世界的坐标用 (用户现场实锤: 跑向/挖向旧世界坐标)。
 *     种子来源 = `GameJoinS2CPacket.sha256Seed`, 服务端由
 *     `BiomeAccess.hashSeed(ServerWorld.getSeed())` 生成 ⇒ 同种子同值、
 *     换世界必变, 是客户端唯一拿得到的"世界级"标识 (专用服下存档目录名不可得)。
 *     注意: 若服务端 `level-seed` 被固定, 重建后种子不变 ⇒ 本手段失效
 *     (用户当前未设, 每次新建随机)。
 *
 * `srv:` 前缀与单人目录名天然互斥: Windows 存档目录名不含 ':'。
 */
public final class WorldIdentity {

    /** 多人服条目标识前缀 (见类注释第 2 条)。 */
    private static final String SERVER_TOKEN = "srv:";

    /** 世界种子标识分隔符 (见类注释第 4 条)。与 `MineDownExploreTask` 的 "#v2" 后缀区分开。 */
    private static final String SEED_TOKEN = "@";

    /**
     * 最近一次 `GameJoinS2CPacket.sha256Seed`。null = 未知(尚未收到该包 / 已断开)。
     * 只对专用服务端记录 —— 单人走存档目录名分支, 不需要种子。
     */
    private static volatile Long _serverSeed = null;

    private WorldIdentity() {
    }

    /**
     * (2026-10-07) 客户端收到 `GameJoinS2CPacket` 时调用, 记录本服务器世界种子标识。
     * 由 `ClientPlayNetworkHandlerMixin#onGameJoin` 注入调用。
     *
     * <p>包在每次连接(含重连)时到达; 单人世界同样会收到, 但那时
     * `getCurrentServerEntry()` 为 null ⇒ 不记录(分支 1 也不消费它)。
     */
    public static void onGameJoin(long sha256Seed) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getCurrentServerEntry() == null) return;
        _serverSeed = sha256Seed;
    }

    /**
     * (2026-10-07) 断开连接时清空种子缓存, 避免"回主菜单后残留旧服务器种子"
     * 被下一次连接早期的 `saveDirName()` 误用。由
     * `ClientPlayNetworkHandlerMixin#onDisconnected` 注入调用。
     */
    public static void onServerDisconnected() {
        _serverSeed = null;
    }

    /** 世界标识串: 单人=存档目录名, 多人=`srv:<服务器地址>`。取不到时 "?"。 */
    public static String saveDirName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return "?";
        // ---- 1) 单人 / 局域网主机: 存档目录名 ----
        try {
            var server = mc.getServer();
            if (server != null) {
                Path root = server.getSavePath(WorldSavePath.ROOT);
                if (root != null) {
                    // (2026-09-23 根因修复) 1.18.2 的 getSavePath(ROOT) 实际返回
                    // `saves/<目录>/.` —— Session.getDirectory 对 ROOT 没有特判,
                    // 而是 resolve(ROOT.getRelativePath()), ROOT 的相对路径字面量是
                    // ".", 不 normalize 时 getFileName() 恒为 ".", 导致所有存档
                    // 标识相同 (knowledge.log 全是 w2:., WorldStateGuard 永不触发)。
                    Path norm = root.normalize();
                    if (norm.getFileName() != null) {
                        return norm.getFileName().toString();
                    }
                }
                if (server.getSaveProperties() != null
                        && server.getSaveProperties().getLevelName() != null) {
                    return server.getSaveProperties().getLevelName();
                }
            }
        } catch (Exception ignored) {
        }
        // ---- 2) 专用服务端: 服务器地址 (+ 世界种子) ----
        try {
            ServerInfo entry = mc.getCurrentServerEntry();
            if (entry != null) {
                String host = null;
                if (entry.address != null && !entry.address.isEmpty()) {
                    host = entry.address;
                } else if (entry.name != null && !entry.name.isEmpty()) {
                    host = entry.name;
                }
                if (host != null) {
                    // (2026-10-07) 拼上世界种子标识: 服务器存档删除重建后种子必变 ⇒ key 变。
                    // 种子未知(连接早期)时退回旧口径, 行为与修复前一致。
                    Long seed = _serverSeed;
                    return seed == null
                            ? SERVER_TOKEN + host
                            : SERVER_TOKEN + host + SEED_TOKEN + Long.toHexString(seed);
                }
            }
        } catch (Exception ignored) {
        }
        return "?";
    }

    /**
     * 世界标识 (存档:维度), 维度取 Dimension 枚举名。
     * 与 PortalMemoryStore 既有 key 口径完全一致 → 存量传送门记录不作废。
     */
    public static String key(Dimension dim) {
        return "w2:" + saveDirName() + ":" + (dim == null ? "?" : dim.name());
    }

    /**
     * 世界标识 (存档:维度), 维度取 RegistryKey 字符串(如 "minecraft:overworld")。
     * 供地标 / 堡垒走廊 / 远征 / 矿洞四处使用 —— 它们的维度 token 一直是这个格式,
     * 只把【存档部分】从显示名换成目录名, 维度部分不动。
     */
    public static String key(AltoClef mod) {
        String dim = "?";
        try {
            if (mod != null && mod.getWorld() != null) {
                dim = mod.getWorld().getRegistryKey().getValue().toString();
            }
        } catch (Exception ignored) {
        }
        return "w2:" + saveDirName() + ":" + dim;
    }

    // =========================================================================
    // (2026-09-27 空格 key 修复) 记忆文件行的统一编解码 —— 唯一口径。
    //
    // 背景: 五处跨会话记忆 (knowledge / fortress_visited / selfdug / explored /
    // portals) 的落盘格式都是 `key + " " + 字段...`, 而 key 自 2026-09-17 起改用
    // 存档【目录名】(如 `新的世界 (5)`, 含空格) —— 加载端 `line.trim().split(" ")`
    // 后段数变多, 被 `parts.length != N → continue` 整行【静默丢弃】(无日志)。
    // 实测: knowledge.log 487 行丢 423, selfdug.log 6620 行丢 3488,
    // explored.log 174 行丢 128, portals.log 26 行丢 12 —— 重启进程后地标/堡垒
    // 走廊/自挖/洞穴/传送门五类记忆一起归零。根因是分隔符与 key 的字符集冲突,
    // 故改用 TAB 分隔 (存档名不会含 TAB)。
    //
    // decodeLine 对【旧空格行】按"尾部定长字段"右锚定回退 —— 存量文件无需迁移
    // 即可正确读回 (只挪不删)。旧格式里 key 字面为 `w2:.` 的历史脏行仍会因 key
    // 失配读不到, 属预期 (2026-09-23 normalize 修复前写入, 本就作废)。
    // =========================================================================
    private static final String FIELD_SEP = "\t";

    /** 落盘行: key 与各字段以 TAB 分隔, 末尾带换行 (直接写文件用)。 */
    public static String encodeLine(String key, Object... fields) {
        StringBuilder sb = new StringBuilder(key);
        for (Object f : fields) {
            sb.append(FIELD_SEP).append(f);
        }
        return sb.append('\n').toString();
    }

    /**
     * 解析记忆文件的一行。
     *
     * @param fieldCount key 之后的字段个数 (不含 key)
     * @return {@code [key, field0, field1, ...]}, 长度 {@code fieldCount + 1};
     *         行不可解析 (空行 / 段数不符) 时返回 {@code null} —— 调用方跳过即可,
     *         与旧实现 `parts.length != N → continue` 行为等价。
     */
    public static String[] decodeLine(String line, int fieldCount) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        // 新格式: TAB 分隔 (key 含空格也安全)。
        if (trimmed.indexOf('\t') >= 0) {
            String[] parts = trimmed.split(FIELD_SEP);
            return parts.length == fieldCount + 1 ? parts : null;
        }
        // 旧格式回退: 尾部 fieldCount 段是定长字段, 其余全部归 key。
        String[] parts = trimmed.split(" ");
        if (parts.length < fieldCount + 1) return null;
        int keyParts = parts.length - fieldCount;
        String[] out = new String[fieldCount + 1];
        out[0] = String.join(" ", java.util.Arrays.copyOf(parts, keyParts));
        System.arraycopy(parts, keyParts, out, 1, fieldCount);
        return out;
    }
}
