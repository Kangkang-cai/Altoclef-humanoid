package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

public class FollowPlayerTask extends Task {

    private final String _playerName;

    // 空记录提示的节流时间戳 (毫秒)。原先是每 tick 静默刷同一句 debug state。
    private long _lastNoRecordWarnMs;

    public FollowPlayerTask(String playerName) {
        _playerName = playerName;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {

        Optional<Vec3d> lastPos = mod.getEntityTracker().getPlayerMostRecentPosition(_playerName);

        if (lastPos.isEmpty()) {
            return onNoPlayerRecord(mod);
        }
        Vec3d target = lastPos.get();

        if (target.isInRange(mod.getPlayer().getPos(), 1) && !mod.getEntityTracker().isPlayerLoaded(_playerName)) {
            mod.logWarning("Failed to get to player \"" + _playerName + "\". We moved to where we last saw them but now have no idea where they are.");
            stop(mod);
            return null;
        }

        Optional<PlayerEntity> player = mod.getEntityTracker().getPlayerEntity(_playerName);
        if (player.isEmpty()) {
            // Go to last location
            return new GetToBlockTask(new BlockPos((int) target.x, (int) target.y, (int) target.z), false);
        }
        return new GetToEntityTask(player.get(), 2);
    }

    /**
     * 客户端没有任何该玩家的坐标记录。原先这里无条件静默空转, 把三种完全不同的
     * 情况混成一句 "No player found", 用户无从判断该怎么办。现在明确区分:
     * <p>
     * 1) 服务器有相似名 (名字打错/大小写/服务器昵称) -&gt; 提示 "你是不是想找 X"
     * 2) tab 列表里根本没这人 -&gt; 报警并结束任务 (不再无限空转)
     * 3) 在线但超出客户端追踪范围 -&gt; 明确告知, 继续等待其进入范围
     * <p>
     * (2026-10-02 用户实锤) 第三方服务器上, 追踪范围外(约等于服务器 view-distance,
     * 默认 160 格)的玩家实体不下发坐标, 客户端拿不到位置就无法导航 —— 这是原版
     * 协议限制, 不是策略问题。唯一可用来源是"曾经亲眼看到过"的位置
     * (_playerLastCoordinates), 它不持久化, 本次上线没见过就是空。
     */
    private Task onNoPlayerRecord(AltoClef mod) {
        var et = mod.getEntityTracker();
        List<String> online = et.getServerPlayerNames();
        Optional<String> closest = et.findClosestServerPlayerName(_playerName);

        if (!online.isEmpty() && closest.isEmpty()) {
            mod.logWarning("Player \"" + _playerName + "\" is not on this server. Online players: " + String.join(", ", online));
            stop(mod);
            return null;
        }
        if (closest.isPresent() && !closest.get().equalsIgnoreCase(_playerName)) {
            warnThrottled(mod, "No record of player \"" + _playerName + "\". Did you mean \"" + closest.get() + "\"?");
            setDebugState("Player \"" + _playerName + "\" not found. Did you mean \"" + closest.get() + "\"?");
            return null;
        }
        warnThrottled(mod, "Player \"" + _playerName + "\" is online but outside the client tracking range; "
                + "the client has no coordinates for them yet. Waiting for them to come within reach.");
        setDebugState("Player \"" + _playerName + "\" is online but too far: no coordinates yet. "
                + "Waiting for them to come within tracking range.");
        return null;
    }

    private void warnThrottled(AltoClef mod, String message) {
        long now = System.currentTimeMillis();
        if (now - _lastNoRecordWarnMs < 5000) return;
        _lastNoRecordWarnMs = now;
        mod.logWarning(message);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof FollowPlayerTask task) {
            return task._playerName.equals(_playerName);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Going to player " + _playerName;
    }
}
