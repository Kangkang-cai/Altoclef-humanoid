package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.PlayerDamageCauseHolder;
import adris.altoclef.tasks.movement.DeathRecoveryTask;
import adris.altoclef.util.helpers.DropValuationHelper;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

public class DeathMenuChain extends TaskChain {

    // Sometimes we fuck up, so we might want to retry considering the death screen.
    private final TimerReal _deathRetryTimer = new TimerReal(8);
    private ServerInfo _prevServerEntry = null;
    private boolean _reconnecting = false;
    private final TimerGame _reconnectTimer = new TimerGame(1);
    private int _deathCount = 0;
    private Class _prevScreen = null;

    private final TimerGame _waitOnDeathScreenBeforeRespawnTimer = new TimerGame(2);

    public DeathMenuChain(TaskRunner runner) {
        super(runner);
    }

    private boolean shouldAutoRespawn(AltoClef mod) {
        return mod.getModSettings().isAutoRespawn();
    }

    private boolean shouldAutoReconnect(AltoClef mod) {
        return mod.getModSettings().isAutoReconnect();
    }

    @Override
    protected void onStop(AltoClef mod) {

    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {

    }

    @Override
    protected void onTick(AltoClef mod) {

    }

    @Override
    public float getPriority(AltoClef mod) {
        //MinecraftClient.getInstance().getCurrentServerEntry().address;
//        MinecraftClient.getInstance().
        Screen screen = MinecraftClient.getInstance().currentScreen;

        // This might fix Weird fail to respawn that happened only once
        if (_prevScreen == DeathScreen.class) {
            if (_deathRetryTimer.elapsed()) {
                Debug.logMessage("(RESPAWN RETRY WEIRD FIX...)");
                _deathRetryTimer.reset();
                _prevScreen = null;
            }
        } else {
            _deathRetryTimer.reset();
        }
        // Keep track of the last server we were on so we can re-connect.
        if (AltoClef.inGame()) {
            _prevServerEntry = MinecraftClient.getInstance().getCurrentServerEntry();
        }

        if (screen instanceof DeathScreen) {
            if (_waitOnDeathScreenBeforeRespawnTimer.elapsed()) {
                _waitOnDeathScreenBeforeRespawnTimer.reset();
                // Record the death grave BEFORE respawning — the instant
                // requestRespawn() runs, the player entity is moved to the
                // world spawn and we lose the original death coordinates.
                try {
                    PlayerEntity player = MinecraftClient.getInstance().player;
                    if (player != null && MinecraftClient.getInstance().world != null) {
                        BlockPos deathPos = player.getBlockPos();
                        Dimension deathDim = WorldHelper.getCurrentDimension();
                        long deathTick = MinecraftClient.getInstance().world.getTime();
                        // (2026-09-16) 死亡瞬间取样一次死因: 读 PlayerDamageCauseHolder
                        // 最近一次伤害来源名(剥前缀后的 "drown"/"lava"/...), 传进 grave 记忆。
                        // 读后该 holder 被清空, 保证每个墓只取样这一次。
                        // (holder 放普通类而非 Mixin 类: Mixin 会把非 private 方法合并进
                        // 目标类, public static 辅助方法被拒应用 → 19:06 启动崩溃实锤。)
                        String deathCause = PlayerDamageCauseHolder.consumeLastDeathCause();

                        // (2026-09-17 空墓过滤, 用户"没有大量物资的情况就不要拾取了, 比如食物, 矿物")
                        // 取样时机是唯一可靠的: 此刻玩家仍停在死亡点、该区块仍在加载,
                        // 死亡掉落物实体已生成并同步到客户端 (死亡屏出现后再等 2s ≈ 40 tick,
                        // 本地集成服务器足够)。复活之后墓地区块可能已被卸载, 那时扫描只会
                        // 得到空 —— 把"真掉落物"误判成"没有", 所以判据必须在此刻取样。
                        // 判据: 高价值物(装备/工具/护甲/附魔/稀有素材)任一 → 必捡;
                        //       否则普通物资总件数需 ≥ deathRecoveryMinOrdinaryDropCount。
                        DropValuationHelper.Assessment assessment = DropValuationHelper.assess(
                                DropValuationHelper.scanNear(deathPos, DeathRecoveryTask.getPickupRadius()));
                        if (assessment.worthRecovery(mod.getModSettings().getDeathRecoveryMinOrdinaryDropCount())) {
                            mod.getWorldMemory().rememberDeathGrave(deathDim, deathPos, deathTick, deathCause);
                            String msg = String.format(
                                    "DEATH GRAVE remembered: dim=%s pos=(%d,%d,%d) tick=%d (death #%d) cause=%s drops[%s]",
                                    deathDim, deathPos.getX(), deathPos.getY(), deathPos.getZ(),
                                    deathTick, _deathCount + 1, deathCause, assessment.describe());
                            Debug.logMessage(msg);
                            Debug.logToFile("death_recovery.log", "[RECORD] " + msg);
                        } else {
                            // 不值得回去捡 → 只推进连续死亡 streak, 不写墓碑。
                            // 墓碑表保持原样(可能仍是上一次值得捡的墓, 或空), 链层
                            // getDeathGrave() 因此取到空 → 完全不介入: 不搞镐、不白跑、
                            // 不打断主任务。
                            mod.getWorldMemory().noteDeathWithoutGrave(deathTick);
                            String msg = String.format(
                                    "DEATH GRAVE skipped (drops not worth recovering): dim=%s pos=(%d,%d,%d) tick=%d (death #%d) cause=%s drops[%s] ordinaryThreshold=%d",
                                    deathDim, deathPos.getX(), deathPos.getY(), deathPos.getZ(),
                                    deathTick, _deathCount + 1, deathCause, assessment.describe(),
                                    mod.getModSettings().getDeathRecoveryMinOrdinaryDropCount());
                            Debug.logMessage(msg);
                            Debug.logToFile("death_recovery.log", "[SKIP GRAVE] " + msg);
                        }
                    } else {
                        Debug.logWarning("Failed to record death grave: player/world is null.");
                        Debug.logToFile("death_recovery.log",
                                "[RECORD FAIL] player=" + (MinecraftClient.getInstance().player == null)
                                        + " world=" + (MinecraftClient.getInstance().world == null));
                    }
                } catch (Throwable t) {
                    Debug.logWarning("Exception while recording death grave: " + t.getMessage());
                    Debug.logToFile("death_recovery.log", "[RECORD ERROR] " + t);
                }

                if (shouldAutoRespawn(mod)) {
                    _deathCount++;
                    Debug.logMessage("RESPAWNING... (this is death #" + _deathCount + ")");
                    Debug.logToFile("death_recovery.log",
                            "[RESPAWN] death #" + _deathCount + " autoRespawn=true");
                    assert MinecraftClient.getInstance().player != null;
                    MinecraftClient.getInstance().player.requestRespawn();
                    MinecraftClient.getInstance().setScreen(null);
                } else {
                    // Cancel if we die and are not auto-respawning.
                    Debug.logToFile("death_recovery.log",
                            "[RESPAWN SKIP] autoRespawn=false; canceling user task.");
                    mod.cancelUserTask();
                }
            }
        } else {
            _waitOnDeathScreenBeforeRespawnTimer.reset();
            if (screen instanceof DisconnectedScreen) {
                if (shouldAutoReconnect(mod)) {
                    Debug.logMessage("RECONNECTING: Going to Multiplayer Screen");
                    _reconnecting = true;
                    MinecraftClient.getInstance().setScreen(new MultiplayerScreen(new TitleScreen()));
                } else {
                    // Cancel if we disconnect and are not auto-reconnecting.
                    mod.cancelUserTask();
                }
            } else if (screen instanceof MultiplayerScreen && _reconnecting && _reconnectTimer.elapsed()) {
                _reconnectTimer.reset();
                Debug.logMessage("RECONNECTING: Going ");
                _reconnecting = false;

                if (_prevServerEntry == null) {
                    Debug.logWarning("Failed to re-connect to server, no server entry cached.");
                } else {
                    MinecraftClient client = MinecraftClient.getInstance();
                    ConnectScreen.connect(screen, client, ServerAddress.parse(_prevServerEntry.address), _prevServerEntry);
                    //client.setScreen(new ConnectScreen(screen, client, _prevServerEntry));
                }
            }
        }
        if (screen != null)
            _prevScreen = screen.getClass();
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Death Menu Respawn Handling";
    }
}
