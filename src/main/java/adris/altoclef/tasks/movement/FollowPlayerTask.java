package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.bridge.FollowRegistry;
import adris.altoclef.bridge.FollowRelation;
import adris.altoclef.bridge.PeerPosWatcher;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

public class FollowPlayerTask extends Task {

    private final String _playerName;

    // (2026-10-05) 跟随保持距离。原实现把该值直接传给 GetToEntityTask 的 goal
    // 半径, 但 GoalFollowEntity 的 goal 区域是"半径内任意格都算到达", A* 终点格
    // 被启发值拉向实体一侧(≈1格), 名义半径 2 实际停点只有 ~1 格 —— 用户反馈
    // "贴太近影响任务执行"。现在由本任务自己做环形判定: ≤ 距离就停(停泊子任务,
    // 由框架走子任务 onStop 释放 baritone goal), > 距离+滞回带才重新跟随。
    private final double _followDistance;

    // 滞回带: 停止跟随后再恢复的额外距离, 防止恰好在边界时每拍重算路径抖动。
    private static final double RESUME_HYSTERESIS = 0.5;

    private boolean _holdingDistance;

    // 空记录提示的节流时间戳 (毫秒)。原先是每 tick 静默刷同一句 debug state。
    private long _lastNoRecordWarnMs;

    // ------------------------------------------------------------------ //
    // (2026-10-07 follow 关系治理) 时长上限 + 互指退让
    // ------------------------------------------------------------------ //

    /**
     * 本次跟随的总时长上限（毫秒）；{@code 0} = 不限时。
     *
     * <p>命令级 follow（{@code @follow} / {@code @follow ... work}）传
     * {@link FollowRelation#COMMAND_FOLLOW_MAX_MS}（5 分钟）：主 bot 跟次 bot 不能太久、
     * 次 bot 跟别的次 bot 也不能太久，到期即结束、由上层回到常驻任务（次 bot → bootstrap，
     * 主 bot → 主线）。
     *
     * <p>{@code 0}（不限时）只给两类：① bootstrap 宿主内部的常驻跟随（它的生命周期由宿主
     * 的跟丢/阶段判定管）；② {@code GiveItemToPlayerTask} 的"走到目标身边"接近段（见
     * {@code _announce} 注释）。
     */
    private final long _maxDurationMs;

    /**
     * 是否登记进 {@link FollowRegistry}（{@code /status.following}），供同伴检测对向跟随。
     *
     * <p>{@code false} 只给 {@code GiveItemToPlayerTask} 的接近段：那只是"走过去交货"的一次性
     * 位移，不是跟随关系 —— 若也登记，会出现"我去给人送货、对方正跟着我 ⇒ 被判定互指而互相退让"
     * 的误伤。
     */
    private final boolean _announce;

    /** 本次跟随起点（onStart 首次记，interrupt 二次进入不重置）。 */
    private long _startedMs = 0;

    // ------------------------------------------------------------------ //
    // (2026-10-07 bot 间坐标共享) 远程接近状态
    // ------------------------------------------------------------------ //

    /**
     * 只在<b>本地拿不到目标坐标</b>（超出客户端 ~48 格实体跟踪范围）时启用的兜底：
     * 用 {@link PeerPosWatcher} 那本"同伴自报坐标"表走过去。典型场景 = 对端
     * {@code @follow <远距本机 bot>}（1007 13:15 实测卡死 6 分钟）。
     */
    private Task _remoteApproach = null;
    /** 远程导航当前目标格（移动超过 {@link PeerPosWatcher#RETARGET_DIST} 才重建）。 */
    private BlockPos _remoteTarget = null;
    /** 本次远程接近起点（0 = 当前不在远程接近）。 */
    private long _remoteSinceMs = 0;

    /**
     * 远程接近硬上限 —— 必须有出口，否则"同伴一直在动/一直够不到"会变成无限导航
     * （与 {@link SubBotBootstrapTask#REMOTE_APPROACH_MAX_MS} 同款语义）。
     *
     * <p>与 {@link #_maxDurationMs} 的分工：本值管<b>"这段远程走法"</b>的出口（给不限时的
     * 常驻跟随用），{@code _maxDurationMs} 管<b>"整条跟随"</b>的总时限。命令级 follow 两者
     * 同为 5 分钟 —— 无论哪条先到，都会结束任务，语义一致。
     */
    private static final long REMOTE_APPROACH_MAX_MS = 5 * 60_000;

    public FollowPlayerTask(String playerName) {
        this(playerName, 3, 0, true);
    }

    public FollowPlayerTask(String playerName, double followDistance) {
        this(playerName, followDistance, 0, true);
    }

    /**
     * @param maxDurationMs 跟随总时长上限；{@code 0} = 不限时（见字段注释）
     * @param announce      是否登记 {@code /status.following}（见字段注释）
     */
    public FollowPlayerTask(String playerName, double followDistance, long maxDurationMs, boolean announce) {
        _playerName = playerName;
        _followDistance = followDistance;
        _maxDurationMs = maxDurationMs;
        _announce = announce;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _holdingDistance = false;
        resetRemoteApproach();
        // interrupt 后会二次 onStart（框架行为）⇒ 起点只记一次，否则时限被无限续期。
        if (_startedMs == 0) _startedMs = System.currentTimeMillis();
        if (_announce) FollowRegistry.register(this, _playerName);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = System.currentTimeMillis();

        // (1) (2026-10-07 follow 关系治理) 互指退让：我在跟 X，而 X 也在跟我 ——
        //     除非 X 就是我的 leader（设计关系），否则结束本侧跟随（唯一判定源 FollowRelation）。
        if (shouldYieldMutualFollow(mod)) {
            mod.logWarning("Mutual follow with \"" + _playerName + "\" detected; yielding on this side ("
                    + FollowRelation.describe(_playerName) + "). Returning to the standing task.");
            stop(mod);
            return null;
        }

        // (2) 时长上限：命令级 follow 一律有时限，到期结束（回归常驻任务由上层完成）。
        if (_maxDurationMs > 0 && now - _startedMs >= _maxDurationMs) {
            mod.logWarning("Follow \"" + _playerName + "\" hit the " + (_maxDurationMs / 60_000)
                    + "-min limit; ending (standing task resumes).");
            stop(mod);
            return null;
        }

        Optional<Vec3d> lastPos = mod.getEntityTracker().getPlayerMostRecentPosition(_playerName);

        // (2026-10-07 坐标共享) 跨进程坐标补位。判定源单一：PeerPosWatcher.remoteNav。
        Task remote = tickRemoteApproach(mod, lastPos);
        if (remote != null) return remote;

        if (lastPos.isEmpty()) {
            return onNoPlayerRecord(mod);
        }

        // (2026-10-06) 跨维度守卫：_playerLastCoordinates 故意跨维度不清空，若目标"最后现身"
        // 在另一个维度，那份坐标在本维度里是废坐标 —— 旧实现会照着它走完整趟废路，到地方才
        // 发现"no idea where they are"。这里提前拦下：目标不在线（未加载）且记录维度 ≠ 当前
        // 维度 → 立即结束（one-shot 出口），由 bridge 报 failed 让派发方决策（换维度/放弃）。
        if (!mod.getEntityTracker().isPlayerLoaded(_playerName)) {
            Dimension lastSeenDim = mod.getEntityTracker().getDimensionOfPlayerLastCoordinates(_playerName).orElse(null);
            Dimension currentDim = WorldHelper.getCurrentDimension();
            if (lastSeenDim != null && lastSeenDim != currentDim) {
                mod.logWarning("Player \"" + _playerName + "\" was last seen in a different dimension ("
                        + lastSeenDim + ", we are in " + currentDim + "). Not chasing stale coordinates.");
                stop(mod);
                return null;
            }
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

        // (2026-10-05) 环形跟随: 到距离就站定, 不再继续逼近 (旧实现 goal 半径不生效,
        // A* 终点被启发值拉到 ~1 格)。滞回带防边界抖动; 返回 null 停泊子任务时,
        // 框架会走 GetToEntityTask.onStop 释放 baritone goal 与按键输入。
        double dist = mod.getPlayer().getPos().distanceTo(player.get().getPos());
        if (dist <= _followDistance) {
            _holdingDistance = true;
        } else if (dist > _followDistance + RESUME_HYSTERESIS) {
            _holdingDistance = false;
        }
        if (_holdingDistance) {
            setDebugState("Following \"" + _playerName + "\" (holding " + (int) Math.round(_followDistance) + " blocks away)");
            return null;
        }
        return new GetToEntityTask(player.get(), _followDistance);
    }

    /**
     * (2026-10-07 bot 间坐标共享) 本地看不到目标时，用同伴自报坐标走过去。
     *
     * <p><b>为什么需要</b>：客户端只下发 ~48 格内其他玩家实体，超出后本地<b>连坐标都没有</b>，
     * 旧实现只能停在 "online but too far: no coordinates yet" 原地干等（1007 13:15
     * 实测对端 {@code @follow kangkang} 卡死 6 分钟）。而本机每个 bot 的
     * {@code /status.pos} 就是现成的跨进程坐标源（同机 localhost）。
     *
     * <p><b>"要不要走远程、走哪儿"的判断只在 {@link PeerPosWatcher#remoteNav} 一处</b>
     * （本方法只负责导航任务的重建节流与超时出口），{@link SubBotBootstrapTask} 共用同一判定源。
     *
     * @return 远程导航任务；{@code null} = 走常规跟随（本地坐标够用 / 非本机 bot / 跨维度 /
     * 对面不再是新鲜上报 —— 此时 {@link #onNoPlayerRecord} 的既有语义原样保留）
     */
    private Task tickRemoteApproach(AltoClef mod, Optional<Vec3d> localPos) {
        // 本地已"感知"到目标（可见/可听 ⇒ 本地坐标每拍刷新）→ 交常规跟随。
        if (mod.getEntityTracker().isPlayerLoaded(_playerName)) {
            resetRemoteApproach();
            return null;
        }
        Optional<PeerPosWatcher.Nav> nav = PeerPosWatcher.remoteNav(
                _playerName, localPos, WorldHelper.getCurrentDimension());
        if (nav.isEmpty()) {
            resetRemoteApproach();
            return null;
        }

        long now = System.currentTimeMillis();
        if (_remoteSinceMs == 0) _remoteSinceMs = now;
        if (now - _remoteSinceMs >= REMOTE_APPROACH_MAX_MS) {
            // 出口：长时间走不到 = 跟丢（同伴一直在动/地形不可达），结束本任务让派发方决策。
            mod.logWarning("Gave up remote-approaching \"" + _playerName + "\" after "
                    + (REMOTE_APPROACH_MAX_MS / 60_000) + " min (peer coordinates only).");
            resetRemoteApproach();
            stop(mod);
            return null;
        }

        BlockPos target = nav.get().blockPos();
        if (_remoteApproach == null || _remoteTarget == null
                || _remoteTarget.getSquaredDistance(target)
                        > PeerPosWatcher.RETARGET_DIST * PeerPosWatcher.RETARGET_DIST) {
            _remoteApproach = new GetToBlockTask(target, false, nav.get().dim());
            _remoteTarget = target;
        }
        double dist = mod.getPlayer() == null ? -1 : nav.get().pos().distanceTo(mod.getPlayer().getPos());
        setDebugState("Approaching \"" + _playerName + "\" via peer coordinates"
                + (dist >= 0 ? " (" + (int) dist + " blocks)" : ""));
        return _remoteApproach;
    }

    /** 退出远程接近：丢导航句柄与计时（回到常规跟随路径）。 */
    private void resetRemoteApproach() {
        _remoteApproach = null;
        _remoteTarget = null;
        _remoteSinceMs = 0;
    }

    /**
     * 客户端没有任何该玩家的坐标记录，且<b>同伴也没有</b>（{@link PeerPosWatcher} 无新鲜上报）。
     * 原先这里无条件静默空转, 把三种完全不同的情况混成一句 "No player found", 用户无从判断该
     * 怎么办。现在明确区分:
     * <p>
     * 1) 服务器有相似名 (名字打错/大小写/服务器昵称) -&gt; 提示 "你是不是想找 X"
     * 2) tab 列表里根本没这人 -&gt; 报警并结束任务 (不再无限空转)
     * 3) 在线但超出客户端追踪范围 -&gt; 明确告知, 继续等待其进入范围
     * <p>
     * (2026-10-02 用户实锤) 第三方服务器上, 追踪范围外(约等于服务器 view-distance,
     * 默认 160 格)的玩家实体不下发坐标, 客户端拿不到位置就无法导航 —— 这是原版
     * 协议限制, 不是策略问题。唯一可用来源是"曾经亲眼看到过"的位置
     * (_playerLastCoordinates, 不持久化, 本次上线没见过就是空) 或
     * {@link PeerPosWatcher} 的同伴自报坐标（<b>仅限本机跑本模组的 bot</b>,
     * 真人玩家不暴露 bridge, 永远走不到第三分支之外的补救）。
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

    /**
     * (2026-10-07 follow 关系治理) 互指退让判定。判定源 <b>只在</b>
     * {@link FollowRelation#shouldYield} 一处（本方法只负责取"我是谁"）。
     * 未启用坐标共享 / 玩家还没加载 / 对面不是本版 jar ⇒ 一律不退让（保守）。
     */
    private boolean shouldYieldMutualFollow(AltoClef mod) {
        if (!PeerPosWatcher.enabled()) return false;
        var p = mod.getPlayer();
        if (p == null) return false;
        return FollowRelation.shouldYield(p.getName().getString(), _playerName);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 注销 /status.following 登记（token 校验，旧实例不会误清新实例）。
        if (_announce) FollowRegistry.unregister(this);
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
