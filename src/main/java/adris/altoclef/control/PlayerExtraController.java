package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBreakingCancelEvent;
import adris.altoclef.eventbus.events.BlockBreakingEvent;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

public class PlayerExtraController {

    private final AltoClef _mod;
    private BlockPos _blockBreakPos;
    private double _blockBreakProgress;

    public PlayerExtraController(AltoClef mod) {
        _mod = mod;

        EventBus.subscribe(BlockBreakingEvent.class, evt -> onBlockBreak(evt.blockPos, evt.progress));
        EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> onBlockStopBreaking());
    }

    private void onBlockBreak(BlockPos pos, double progress) {
        _blockBreakPos = pos;
        _blockBreakProgress = progress;
    }

    private void onBlockStopBreaking() {
        _blockBreakPos = null;
        _blockBreakProgress = 0;
    }

    public BlockPos getBreakingBlockPos() {
        return _blockBreakPos;
    }

    public boolean isBreakingBlock() {
        return _blockBreakPos != null;
    }

    public double getBreakingBlockProgress() {
        return _blockBreakProgress;
    }

    public boolean inRange(Entity entity) {
        return _mod.getPlayer().isInRange(entity, _mod.getModSettings().getEntityReachRange());
    }

    public void attack(Entity entity) {
        if (inRange(entity)) {
            // (2026-09-06 拟人化击打, 用户"不希望开挂式的击打") 旧实现直接
            // attackEntity 发包, 完全不看准心 —— MC 的 attackEntity 只要实体在
            // reach 内就结算伤害, bot 背对怪也能砍中 (外挂式)。KillAura 力场
            // (MobDefenseChain) 更是 360° 无朝向攻击。现改为: 只有准心
            // (crosshairTarget, 客户端每 tick 按真实视角 raycast 得出, 与玩家
            // 看到的准心完全一致) 真的命中目标实体才出手; 没对准就先把视角
            // 转向目标, 本 tick 不挥击。附带效果: 中间隔墙时准心是方块 →
            // 打不到, 与真人一致。所有调用点 (KillAura/任务层近战/杀龙/船)
            // 走这里, 自动拟人化。
            if (!isCrosshairOn(entity)) {
                LookHelper.lookAt(_mod, entity.getBoundingBox().getCenter());
                return;
            }
            _mod.getController().attackEntity(_mod.getPlayer(), entity);
            _mod.getPlayer().swingHand(Hand.MAIN_HAND);
        }
    }

    /**
     * (2026-09-06 拟人化击打) 准心是否真的停在这个实体上。
     * 用客户端现成的 crosshairTarget 而不是自己再 raycast: 它就是游戏画面
     * 里十字准星的判定结果 (实体/方块/MISS), bot 出刀条件因此和真人玩家
     * 点鼠标的条件完全一致。
     */
    private boolean isCrosshairOn(Entity entity) {
        HitResult result = MinecraftClient.getInstance().crosshairTarget;
        return result instanceof EntityHitResult && ((EntityHitResult) result).getEntity() == entity;
    }
}
