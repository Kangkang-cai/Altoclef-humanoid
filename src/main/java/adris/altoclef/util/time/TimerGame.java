package adris.altoclef.util.time;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.mixins.ClientConnectionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;

// Simple timer
public class TimerGame extends BaseTimer {

    private ClientConnection _lastConnection;

    // (2026-09-03 单机暂停冻结根因修复, 2x2 合成格卡死现场)
    //
    // 根因: 1.18.2 客户端 play 连接的 ticks 只在 ClientPlayerInteractionManager.tick()
    // 里递增, 而该方法仅当 !MinecraftClient.paused 时被 MinecraftClient.tick() 调用
    // (字节码确认)。单机下打开 Esc 菜单 / 窗口失焦自动暂停 (Screen.shouldPause 默认
    // true, pauseOnLostFocus) 会让 paused=true -> ticks 完全冻结 -> 本计时器 currentTime
    // 恒定 -> elapsed() 恒 false -> SlotHandler.canDoSlotAction() 等速率闸门永久卡死。
    // 表现为 "Clicking PlayerSlot{...}" 数百 tick 零进展且零日志:
    //   - 客户端预测 (ClientPlayerInteractionManager.clickSlot 会同步改客户端槽位)
    //     证明点击从未发出 (铁证A: 客户端槽位纹丝不动);
    //   - 游戏日志无 "Slot Click Error" (排除异常路径);
    //   - 任务系统 equal-swap 去重让重试完全静默 (Task.tickInternal)。
    //
    // 修复: ticks 冻结超过宽限期后, 用墙钟推进虚拟时间; ticks 恢复后虚拟偏移保留,
    // 总时间单调不减。正常运行 (20TPS) 时 ticks 每 50ms 递增, 不触发墙钟路径,
    // 原有"按游戏 tick 计时"语义完全不变。多实例共享一条冻结日志 (60s 节流)。
    private double _frozenOffsetSeconds = 0;
    private double _lastTicksTime = -1;
    private long _ticksAdvancedWallMs = 0;
    private long _lastWallMs = 0;
    private boolean _loggedFrozenEpisode = false;
    private static long _lastFreezeLogMs = 0;
    // ticks 停滞超过该墙钟时长才视为冻结: 正常 20TPS 下每 50ms 递增, 卡顿掉 tick
    // 不误判; 只有暂停级冻结 (>300ms 无递增) 才切换墙钟推进。
    private static final long FROZEN_GRACE_MS = 300;

    public TimerGame(double intervalSeconds) {
        super(intervalSeconds);
    }

    private static double getTime(ClientConnection connection) {
        if (connection == null) return 0;
        return (double) ((ClientConnectionAccessor) connection).getTicks() / 20.0;
    }

    @Override
    protected double currentTime() {
        if (!AltoClef.inGame()) {
            Debug.logError("Running game timer while not in game.");
            return 0;
        }
        // If we change connections, our game time will also be reset. In that case, offset our time to reflect that change.
        ClientConnection currentConnection = null;
        if (MinecraftClient.getInstance().getNetworkHandler() != null) {
            currentConnection = MinecraftClient.getInstance().getNetworkHandler().getConnection();
        }
        if (currentConnection != _lastConnection) {
            if (_lastConnection != null) {
                double prevTimeTotal = getTime(_lastConnection);
                Debug.logInternal("(TimerGame: New connection detected, offsetting by " + prevTimeTotal + " seconds)");
                setPrevTimeForce(getPrevTime() - prevTimeTotal);
            }
            _lastConnection = currentConnection;
            // 新连接: ticks 从 0 重新开始, 重建冻结检测基线。
            _lastTicksTime = -1;
        }
        // Use ticks for timing. 20TPS is normal, if we go slower that's fine.
        // Adding a "mod" argument here would be hell across the board. Not happening.
        double ticksTime = getTime(currentConnection);
        trackFrozenTime(ticksTime);
        return ticksTime + _frozenOffsetSeconds;
    }

    /**
     * (2026-09-03 冻结免疫) 检测 ClientConnection.ticks 是否冻结 (单机暂停), 冻结
     * 超过宽限期后用墙钟推进虚拟偏移 _frozenOffsetSeconds。只兑付"冻结期间超出
     * 宽限部分"的墙钟时间, 恢复后偏移保留, 保证 currentTime 单调不减。
     */
    private void trackFrozenTime(double ticksTime) {
        long nowMs = System.currentTimeMillis();
        if (_lastTicksTime < 0 || ticksTime > _lastTicksTime) {
            // ticks 在走 (或刚建立基线): 记录推进时刻, 清除本回合冻结标记。
            _ticksAdvancedWallMs = nowMs;
            _loggedFrozenEpisode = false;
        } else if (nowMs - _ticksAdvancedWallMs > FROZEN_GRACE_MS) {
            // 冻结: 只兑付超过宽限期的墙钟时间 (宽限段可能是暂时卡顿)。
            long creditFrom = Math.max(_lastWallMs, _ticksAdvancedWallMs + FROZEN_GRACE_MS);
            if (nowMs > creditFrom) {
                _frozenOffsetSeconds += (nowMs - creditFrom) / 1000.0;
            }
            if (!_loggedFrozenEpisode && nowMs - _lastFreezeLogMs > 60_000) {
                _loggedFrozenEpisode = true;
                _lastFreezeLogMs = nowMs;
                Debug.logInternal("(TimerGame: ClientConnection.ticks 冻结超 " + FROZEN_GRACE_MS
                        + "ms (单机暂停/Esc 菜单/失焦), 计时器已切换墙钟推进 — 槽位点击不再冻结)");
            }
        }
        _lastWallMs = nowMs;
        _lastTicksTime = ticksTime;
    }
}