package adris.altoclef.bridge;

/**
 * (2026-10-07 follow 关系治理) <b>本实例"当前正在跟随谁"的单一来源</b>。
 *
 * <h3>为什么需要</h3>
 * 1007 13:15 实测出现过<b>对向跟随</b>：bot_2(jingjing) 被命令 follow kangkang，
 * 而 kangkang 同时在 follow jingjing —— 两边都朝对方走，谁也到不了"目的地"。
 * 要打破这种局面，每台 bot 必须能知道<b>别人在跟谁</b>；而 {@code /status.task}
 * 是按链名映射的<b>中文短标签</b>（{@code SummaryGenerator} 口径，随时会改），
 * 拿它做判据等于把显示串当契约。所以新增 {@code /status.following} 键，值取自本类。
 *
 * <h3>为什么用 token 而不是名字</h3>
 * {@link adris.altoclef.tasks.movement.FollowPlayerTask} 会被 interrupt 后二次 onStart
 * （框架行为），也存在"旧实例 onStop 晚于新实例 onStart"的窗口。用<b>实例自身作 token</b>，
 * {@link #unregister} 只在 token 仍是当前登记方时才清 —— 旧实例不会误清掉新实例的登记。
 */
public final class FollowRegistry {

    /** 登记方（任务实例）。volatile：写于客户端线程，读于客户端线程/HTTP 快照线程。 */
    private static volatile Object _token = null;
    /** 当前跟随目标玩家名；null = 没在跟随。 */
    private static volatile String _target = null;

    private FollowRegistry() {
    }

    /**
     * 登记"我正在跟随 target"（幂等）。任务 onStart 时调用。
     *
     * @param token 登记方实例（清登记时校验）
     */
    public static synchronized void register(Object token, String target) {
        if (token == null || target == null || target.isBlank()) return;
        _token = token;
        _target = target.trim();
    }

    /** 注销登记；只有 token 仍是当前登记方才会清（防旧实例误清新实例）。 */
    public static synchronized void unregister(Object token) {
        if (token == null || _token != token) return;
        _token = null;
        _target = null;
    }

    /** 当前跟随目标玩家名；{@code null} = 没在跟随。供 {@code /status.following} 输出。 */
    public static String target() {
        return _target;
    }
}
