package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * (2026-09-21 杀死动物清记忆) 动物死亡事件桥: {@code AnimalDeathMixin} 在
 * 服务端线程 (单体内置服务端与客户端共享同一 JVM) 触发, 而
 * {@code WorldMemory} 的 HashMap 全部只在客户端线程读写 —— 直接跨线程改
 * 会撞上客户端 tick 正在进行的迭代 (CME)。本类负责把事件 marshaling 回
 * 客户端线程再调用 {@link WorldMemory#forgetAnimalNear}。
 *
 * <p>为什么不把这段放在 Mixin 类里: Mixin 会把非 private 方法合并进目标类,
 * public static 辅助方法被拒应用导致启动崩溃 (20260916 PlayerDamageMixin
 * 19:06 实锤, 同款教训写在 PlayerDamageMixin javadoc)。
 *
 * <p>死因不限: bot 杀的 / 狼咬的 / 摔死的都清 —— "这里没有活羊了"与谁杀的
 * 无关。非动物类型 (敌对怪/玩家) 在 WorldMemory.matchAnimalType 早退。
 */
public final class AnimalMemoryJanitor {

    private AnimalMemoryJanitor() {
    }

    /** 动物死亡入口 (任意线程安全; 实际清理 marshaling 到客户端线程)。 */
    public static void onEntityDeath(Entity dead) {
        if (dead == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        // 捕获死亡实体引用 (服务端实体, 坐标只读, 与 PlayerDamageMixin 读
        // attacker 实体同一先例), 在客户端线程取 BlockPos 并清记忆。
        client.execute(() -> {
            try {
                AltoClef mod = AltoClef.getInstance();
                if (mod == null) return;
                mod.getWorldMemory().forgetAnimalNear(dead);
            } catch (Throwable ignored) {
                // 死亡清理失败绝不打断游戏 tick
            }
        });
    }
}
