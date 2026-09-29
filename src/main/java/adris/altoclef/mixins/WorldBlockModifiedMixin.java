package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBrokenEvent;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 方块变化事件源（唯一）。{@code World#onBlockChanged} 是【客户端可达】的收敛点：
 * {@code ClientPlayNetworkHandler.onBlockUpdate} → {@code World.setBlockState(...)}
 * 尾部无条件调用本方法（javap 取证），且 {@code ClientWorld} 未覆写
 * {@code setBlockState}（只覆写 {@code setBlockStateWithoutNeighborUpdates}）。
 *
 * <p>(2026-09-29 多人服阶段一 🔴#5) 方块【消失】事件从
 * {@code BlockModifiedByPlayerMixin}（注入 {@code Block#onBreak}）迁到本类：
 * 那个挂点只在服务端生效 —— {@code Block#onBreak} 的唯一调用方是
 * {@code ServerPlayerInteractionManager.tryBreakBlock}，专用服务端下客户端 JVM
 * 不执行，事件静默归零（阶段二 /events 依赖它）。迁移后单人/多人同一条路径。
 *
 * <p>事件语义按用户 2026-09-29 拍板收窄为"**方块消失**"，不区分原因
 * （活塞推动 / 床爆 / 流水淹没 / 沙砾下落 / 被挖掉 一律算）。归因（谁弄没的）
 * 客户端拿不到 ⇒ {@code BlockBrokenEvent.player} 恒为 {@code null}，消费方不得
 * 当作"本地玩家挖的"。采集类事件（"挖到铁矿"）不走本事件，改以背包增量为源
 * （需求文档 §3.4：方块消失 ≠ 物品到手）。
 */
@Mixin(World.class)
public class WorldBlockModifiedMixin {

    private static boolean hasBlock(BlockState state, BlockPos pos) {
        return !state.isAir() && state.isSolidBlock(MinecraftClient.getInstance().world, pos);
    }

    /**
     * "此处现在有东西"——非空气且非流体。比 {@link #hasBlock} 宽：植物 / 火把 /
     * 栅栏 / 告示牌等非实心方块也算，故"功能方块被破坏"不会漏。
     */
    private static boolean isPresent(BlockState state) {
        return state != null && !state.isAir() && state.getFluidState().isEmpty();
    }

    @Inject(
            method = "onBlockChanged",
            at = @At("HEAD")
    )
    public void onBlockWasChanged(BlockPos pos, BlockState oldBlock, BlockState newBlock, CallbackInfo ci) {
        // 放置事件: 口径保持原样（未改动，避免波及既有消费方）。
        if (!hasBlock(oldBlock, pos) && hasBlock(newBlock, pos)) {
            BlockPlaceEvent evt = new BlockPlaceEvent(pos, newBlock);
            EventBus.publish(evt);
        }
        // ---- 消失事件: 只在【客户端世界】发 ----
        // 单人时内嵌服务端世界也会走 setBlockState → onBlockChanged，不加这道闸
        // 同一格会发两次（服务端世界先、客户端世界随后）。
        if (!((World) (Object) this).isClient) return;
        if (!isPresent(oldBlock) || isPresent(newBlock)) return;
        BlockBrokenEvent evt = new BlockBrokenEvent();
        evt.blockPos = pos;
        // 消失前的状态 = 被破坏的那个方块，与旧服务端通道口径一致。
        evt.blockState = oldBlock;
        // 归因不可得（客户端无"谁破坏的"信号）—— 保持 null，勿用本地玩家冒充。
        evt.player = null;
        EventBus.publish(evt);
    }
}
