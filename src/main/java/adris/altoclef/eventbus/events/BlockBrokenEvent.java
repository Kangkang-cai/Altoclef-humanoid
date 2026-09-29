package adris.altoclef.eventbus.events;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 方块【消失】事件（2026-09-29 语义收窄）。
 *
 * <p>发布点唯一：{@code WorldBlockModifiedMixin}（{@code World#onBlockChanged}，
 * 仅客户端世界）。语义 = "非空气非流体 → 空气或流体"的跃迁，**不区分原因**
 * （被挖掉 / 活塞推走 / 床爆 / 流水淹没 / 沙砾下落一律算）—— 用户 2026-09-29
 * 拍板：噪声源不必区分，"方块消失"这个粒度对上层（含 LLM）够用。
 *
 * <p>历史：原发布点是 {@code BlockModifiedByPlayerMixin} 注入 {@code Block#onBreak}，
 * 那是服务端路径（唯一调用方 {@code ServerPlayerInteractionManager.tryBreakBlock}），
 * 专用服务端下客户端永不触发 ⇒ 已迁移。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@link #blockPos} —— 变化位置；</li>
 *   <li>{@link #blockState} —— **消失前**的状态（即被破坏的那个方块）；</li>
 *   <li>{@link #player} —— **恒为 null**。客户端拿不到"谁弄没的"（1.18.2 无
 *       {@code EntityDamageS2CPacket} 那类归因包；方块更新包不带行为主体）。
 *       消费方不得 null-check 后拿本地玩家冒充归因；需要"是否自己挖的"就用
 *       背包增量或挖掘进度信号。</li>
 * </ul>
 */
public class BlockBrokenEvent {
    public BlockPos blockPos;
    public BlockState blockState;
    public PlayerEntity player;
}
