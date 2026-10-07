package adris.altoclef.bridge;

import adris.altoclef.util.helpers.BotRole;

/**
 * (2026-10-07 用户拍板) <b>follow 关系的三条硬约束</b>：
 * <ol>
 *   <li><b>命令级 follow 一律有时限</b>（{@link #COMMAND_FOLLOW_MAX_MS}）—— 主 bot 跟次 bot
 *       不能太久、次 bot 跟别的次 bot 也不能太久；到期即结束，由上层流程回到常驻任务
 *       （次 bot → {@code SubBotBootstrapTask} 跟自己的 leader；主 bot → 主线）。</li>
 *   <li><b>不允许互相 follow 卡死</b>：我在跟 X、而 X 也在跟我 ⇒ 除非 X 就是我的 leader
 *       （设计关系，见 {@link #isDesignated}），否则<b>我退让</b>。</li>
 *   <li><b>终局回到"次 bot 跟主 bot"</b>：退让/到期只是结束当前任务，回归由既有机制完成
 *       （{@code UserTaskChain.maybeStartSubBotBootstrap} / {@code ActionDispatcher.resumeMainline}）。
 *       注意回归目的地是 <b>bootstrap（带阶段跟随）</b>，不是"永久贴住主 bot" —— 用户
 *       1007 13:3x 点名："只有主 bot 的部分任务时期才需要被 follow"。</li>
 * </ol>
 *
 * <h3>为什么要"退让"而不是"双方都停"</h3>
 * 对向跟随 = 两边都在朝对方走，没有一方在做正事。让<b>关系更"正式"的一侧保持</b>
 * （次 bot 跟配置里的 leader 属设计关系）比"两边都放弃"更稳：</p>
 * <ul>
 *   <li>主 bot {@code @follow} 次 bot，而次 bot 正处 bootstrap 跟随主 bot ⇒ 主 bot 退
 *       （它的跟随是临时命令；次 bot 的跟随是设计关系）⇒ 终局 = 次 bot 跟主 bot ✓</li>
 *   <li>两个次 bot 互相 {@code @follow}（都非设计关系）⇒ <b>双方都退</b> ⇒ 各自回 bootstrap ✓
 *       （刻意不做"只退一方"的仲裁：那需要两边对名字序达成一致，收益为零而判定源变复杂）</li>
 * </ul>
 *
 * <h3>数据依赖</h3>
 * "X 是否在跟人/跟谁"来自 {@link PeerPosWatcher} 的 {@code /status.following}（对面必须是本版 jar）。
 * 对面是旧 jar / 上报陈旧 / 自己没在跟人 ⇒ 一律<b>不判互指、不退让</b>（保守，宁可不退也不误退）。
 */
public final class FollowRelation {

    /**
     * 命令级 follow 的最长时长（{@code @follow} / {@code @follow ... work}）。
     *
     * <p>取值与 {@link PeerPosWatcher#MAX_AGE_MS} 无关，按"够走完一次会合"定：
     * baritone 常规地表速度 ≈ 3~5 格/秒 ⇒ 5 分钟 ≈ 900~1500 格，覆盖 1007 实测的
     * 180 / 277 格会合场景两倍以上余量。到点即结束 —— 这是"不允许跟太久"的落点。
     */
    public static final long COMMAND_FOLLOW_MAX_MS = 5 * 60_000;

    private FollowRelation() {
    }

    /**
     * {@code targetName} 是不是本实例的<b>设计跟随对象</b>（= {@code bridge.json.leader}，
     * 且本实例 {@code role=sub}）。bootstrap 内部那条跟随恒属设计关系 ⇒ 永不主动退让。
     */
    public static boolean isDesignated(String targetName) {
        if (targetName == null) return false;
        if (!BotRole.isSub()) return false;
        String leader = BridgeConfig.getInstance().leader;
        return leader != null && !leader.isBlank()
                && leader.trim().equalsIgnoreCase(targetName.trim());
    }

    /**
     * 是否构成"对向跟随"：<b>我在跟 targetName，且 targetName 也在跟人</b>
     * （对端 {@code /status.following} 新鲜且等于我）。
     *
     * @param myName 本 bot 游戏内玩家名
     */
    public static boolean mutual(String myName, String targetName) {
        if (myName == null || myName.isBlank() || targetName == null) return false;
        String peerFollows = PeerPosWatcher.followingOf(targetName);
        return peerFollows != null && peerFollows.trim().equalsIgnoreCase(myName.trim());
    }

    /**
     * <b>唯一判定源</b>：当前这条"我在跟 targetName"的跟随是否应当立刻退让（结束自身）。
     *
     * <p>退让条件 = 对向（{@link #mutual}）且<b>我不是在设计关系里</b>（{@link #isDesignated}）。
     * 与角色无关、与名字序无关、与时长无关 —— 三端算法一致，无需任何协商。
     */
    public static boolean shouldYield(String myName, String targetName) {
        if (!mutual(myName, targetName)) return false;
        return !isDesignated(targetName);
    }

    /** 日志用：把关系描述成人话（"设计关系" / "临时跟随"）。 */
    public static String describe(String targetName) {
        return isDesignated(targetName) ? "设计关系(我的 leader)" : "临时跟随";
    }
}
