package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import net.minecraft.client.MinecraftClient;
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
 * 退化链: 集成服务器不可用(多人服 / 主菜单 / 启动早期) → 目录名取不到,
 * 回退到显示名, 再回退 "?"。多人服下所有世界共享 "?", 与旧行为一致,
 * 不在本次修复范围(当前唯一场景是单人存档)。
 */
public final class WorldIdentity {

    private WorldIdentity() {
    }

    /** 存档目录名 (物理唯一)。取不到时回退显示名, 再回退 "?"。 */
    public static String saveDirName() {
        try {
            var server = MinecraftClient.getInstance().getServer();
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
}
