package adris.altoclef.mixins;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.LightBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * (2026-10-03 隐形光源零关联) 客户端把 {@code minecraft:light} 光源方块当空气。
 *
 * <h3>背景</h3>
 * {@code InvisibleLightHelper} 经 RCON 让服务端在 bot 脚下放置隐形光源方块
 * (亮度 15、无碰撞、渲染隐形), 用于关掉夜视后保留光影阴影美学的"身边照明"。
 * 用户要求: 该方块与 bot 的【任何】逻辑零关联 —— 不进感知导出、不参与寻路、
 * 不被挖掘/放置判据、不影响任何 isAir 语义的扫描 (跳跃障碍/撤离落点/窒息阻挡/
 * 可站判据/空腔统计…)。
 *
 * <h3>为什么改 isAir 而不是逐调用点补丁</h3>
 * 全仓 {@code BlockState.isAir()} 判定 ~50 处 (grep 全量核对), 散布在任务链/
 * 助手/录制器里; 逐点补丁必然漏改且未来新代码会漂移。isAir 是"这格算不算空气"
 * 的单一判定源 (与"判定源单一化"原则一致), 在方法级 HEAD 接管一次即可全量生效。
 * 1.18.2 字节码核实: {@code AbstractBlock$AbstractBlockState.isAir()} 为
 * public 非 final 方法 (内部缓存字段 {@code isAir}), HEAD 注入可完全接管。
 *
 * <h3>边界与风险</h3>
 * <ul>
 *   <li>仅客户端 mixin (注册在 client 段): bot 连的专用服务端无 mod, 服务端保持
 *       原版语义 (isAir=false) —— 方块光照引擎、怪物生成、寻路材质判定
 *       (baritone 走 Material.AIR/无碰撞路径, 不依赖 isAir) 均不受影响。</li>
 *   <li>高度图: 服务端 WORLD_SURFACE 会把光源方块算进高度 (isAir=false),
 *       客户端 {@code SurfaceHelper} 的"数据洞"检测对此做了显式豁免
 *       (见 overheadTerrain 的 light 排除分支), 不会误触发保守口径。</li>
 *   <li>vanilla 客户端行为: 光源方块渲染类型本就是 INVISIBLE、无碰撞,
 *       isAir=true 不改变任何可见结果。</li>
 * </ul>
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public final class LightBlockAirMixin {

    @Inject(method = "isAir", at = @At("HEAD"), cancellable = true)
    private void altoclef$treatLightBlockAsAir(CallbackInfoReturnable<Boolean> cir) {
        if (((Object) this) instanceof BlockState
                && ((BlockState) (Object) this).getBlock() instanceof LightBlock) {
            cir.setReturnValue(true);
        }
    }
}
