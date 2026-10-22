package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.bridge.BridgeConfig;
import adris.altoclef.bridge.LeaderPhaseWatcher;
import adris.altoclef.bridge.PeerPosWatcher;
import adris.altoclef.bridge.SubBotDuty;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.SleepThroughNightTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.speedrun.BeatMinecraftConfig;
import adris.altoclef.tasks.speedrun.BeatPhase;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.SleepOverride;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.SleepHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * (2026-10-06 次 bot 自动引导, 用户拍板"一次性前置 → 自动转跟随") <b>次 bot 的常驻宿主</b>:
 * 空闲就自动把木头/食物补到"次 bot 档", 达标后自动进入随行 —— <b>完全不需要对端发命令</b>。
 *
 * <h3>三阶段</h3>
 * <ol>
 *   <li><b>前置</b>: 木头 &lt; {@code woodLogsTarget(8)} 或 食物 &lt; {@code foodTarget(40)}
 *       → 跑 {@link BeatMinecraft2Task} <b>本体</b>（完全复用主线的砍树/造斧/回地表/收食物/
 *       烹饪/夜间路由 —— 用户 20:1x 拍板"还是得用原本主线的智能程度"）。达标 → 置 static 闩,
 *       进随行。<b>每个 JVM 会话只做一次</b>（与 BeatMinecraft2Task 的 {@code _fireResPhaseDone}
 *       同款 static 闩）—— 之后即使背包丢了也不重做, 缺口按设计由主 bot 用 {@code @give} 补。</li>
 *   <li><b>睡眠镜像</b>: 多人服「全员入睡才跳夜」⇒ 主 bot 躺下时次 bot 也必须躺。详见
 *       {@link #tickMirrorSleep}。</li>
 *   <li><b>下界金靴前置（2026-10-10；1011 增强）</b>: 主 bot 处于下界向阶段（PORTAL/
 *       BLAZE_RODS/ENDER_PEARLS/BARTER）或次 bot 自己已在下界, 而未穿金靴 → 先补靴再跟随
 *       （猪灵不攻击的通行证）。普通期 10 分钟超时放弃；主 bot 交易（BARTER）时
 *       <b>强制、无超时</b> —— 必须先完成靴子任务。详见 {@link #tickGoldenBoots}。</li>
 *   <li><b>随行</b>: 返回 {@link FollowAndWorkTask}(leader) —— 贴 3 格 + 顺手搜刮/挖矿。</li>
 * </ol>
 *
 * <h3>睡眠镜像（2026-10-06 20:5x 用户拍板）</h3>
 * 语义 = <b>"主 bot 躺下了咱就睡，没床就触发收集；30 秒没睡成就今晚彻底放弃"</b>。
 * <ul>
 *   <li><b>进入</b>: 夜晚 且 主 bot 持续在睡 1s（{@code isSleeping} 去抖）。</li>
 *   <li><b>执行</b>: {@link SleepThroughNightTask} —— 有床就地睡; 没床它会<b>自己派发
 *       {@code CollectBedTask}</b>（96 格内找床 / 羊毛+木板造床）。</li>
 *   <li><b>放弃</b>（两条, 阈值与主线同源 30s）: ① 未上床时"就近无机会"（{@link
 *       SleepHelper#hasSleepChanceTonight}); ② 已上床后 30s 仍未天亮（有人没睡）。
 *       两条都走 {@link SleepOverride#requestSkipThisNight()} = 本夜不睡, 白天自动恢复。</li>
 *   <li><b>结束</b>: 天亮。</li>
 * </ul>
 * ⚠️ 主 bot 侧有对称的出口（{@code BeatMinecraft2Task} 内 {@code _inBedNoDawnTimer},
 * 打出 {@code [SleepPeerWait]}）—— 两边阈值一致, <b>无需 bot 间通信</b>即可同步放弃。
 *
 * <h3>为什么它是"常驻不结束"的</h3>
 * 本类只换内部子任务、自身几乎不结束。这点是<b>硬要求</b>: 一旦它结束, 链会走
 * {@code UserTaskChain.onTaskFinish} 的"空闲→自动重新拉起"路径（自动引导入口见
 * {@code AltoClef.onClientTick} 内的 maybeStartSubBotBootstrap）, 若它瞬间就结束会形成
 * 每 tick 一次的拉起循环。唯一结束出口是"连续跟丢超过 {@link #FOLLOW_LOST_GIVEUP_MS}（5 分钟）"。
 *
 * <h3>⚠️ 出口写在调用侧</h3>
 * {@code Task.tickInternal} 不消费子任务的 {@code isFinished}, 替换权在父链。所以前置子任务
 * 是否跑完、跟随子任务是否终止, 都由这里每拍主动轮询。
 *
 * <h3>与 @follow 的关系</h3>
 * 本任务是 {@code role=sub} 且 {@code bridge.json} 配了 {@code leader} 时自动拉起的等价物;
 * 对端手动 {@code @follow <名> work} 走的是 {@link FollowAndWorkTask} 本身（无前置、无睡眠镜像）。
 */
public class SubBotBootstrapTask extends Task {

    /**
     * 跟丢后的重试间隔。用户 2026-10-06 22:0x 拍板「<b>bot 位置变了就再靠近一次</b>」⇒
     * 该值必须<b>远小于</b>主 bot 恢复可见所需的时间, 否则位置早变了、这边还在傻等
     * （原值 60s: 主 bot 一回到加载范围, 次 bot 最长要 60 秒后才开始靠近）。
     */
    private static final long RETRY_COOLDOWN_MS = 3_000;

    /** 稳定跟随该时长 = "已跟上" → 清零跟丢计时（否则计时只增不减）。 */
    private static final long FOLLOW_STABLE_MS = 20_000;

    /**
     * 连续跟丢超过该时长 → 结束本次引导（上层会在冷却后重新拉起）。
     *
     * <p>取代旧的"失败 5 次"口径: 那是<b>次数</b>语义, 在 3s 重试下 15 秒就耗尽 —— 太紧,
     * 主 bot 稍微跑远一点引导就自我了断。改用<b>时长</b>, 与重试间隔解耦。
     */
    private static final long FOLLOW_LOST_GIVEUP_MS = 5 * 60_000;

    /** 跟丢日志节流: 3s 重试 = 每分钟 20 条, 不节流会把聊天刷爆（其余落 diag）。 */
    private static final long LOST_LOG_INTERVAL_MS = 60_000;

    // (2026-10-07 13:4x) 远程接近的鲜度上限 / 重建距离 / 信任本地阈值三个常量已上收到
    // PeerPosWatcher（MAX_AGE_MS / RETARGET_DIST / TRUST_LOCAL_DIST）—— 与
    // FollowPlayerTask 共用同一份判定源，禁止在调用侧另立阈值。

    /**
     * (2026-10-07 步骤2) 远程接近硬上限。超时仍未到达 = 视为跟丢, 结束本次引导（与
     * {@link #FOLLOW_LOST_GIVEUP_MS} 同款出口语义, 上层冷却后重新拉起）—— 保证这条路
     * 有明确出口, 不会变成无限导航。
     */
    private static final long REMOTE_APPROACH_MAX_MS = 5 * 60_000;

    /**
     * (2026-10-10 fix C) 数据断流宽限。peer 上报瞬时失效（超过 {@code MAX_AGE_MS} 未刷新 /
     * 轮询抖动 / 判定条件单拍翻转）时, 旧实现立刻 {@link #clearRemoteApproach} + 降级
     * follow —— 而 follow 在"目标不可见且无坐标"的现实里几乎秒死（跨维度守卫 one-shot
     * stop / 空记录等待）, 于是形成 "导航跑 7~10s → 拆 → follow 秒死 → 3s 冷却 → 重建
     * 导航重算路径" 的乒乓（1010 14:14 实录: 40s 里 637→495 格, 约 1/3 时间浪费在停顿
     * +重算, 用户口径"每走一段距离就停下来重新算一遍"）。宽限期内沿用最后目标继续走;
     * 超过宽限才降级。15s = {@code PeerPosWatcher.MAX_AGE_MS}(10s) + 轮询间隔(2s)×2 余量。
     */
    private static final long REMOTE_DATA_GRACE_MS = 15_000;

    /**
     * (2026-10-06 22:5x 用户点名) <b>只在这些大阶段跟随主 bot，其余阶段自己跑主线</b> ——
     * 取代此前"前置完成后全程跟随"。编号对应用户拍板时我列的主线大任务清单。
     */
    private static final Set<BeatPhase> FOLLOW_PHASES = EnumSet.of(
            BeatPhase.FOOD,          //  4 收食物
            BeatPhase.PORTAL,        //  5 建传送门 / 进下界
            BeatPhase.BLAZE_RODS,    //  7 找堡垒 / 收烈焰棒
            BeatPhase.ENDER_PEARLS,  //  8 收末影珍珠
            BeatPhase.BARTER,        // 8.5 猪灵交易（1010 补：交易时次 bot 金靴前置后跟随）
            BeatPhase.STRONGHOLD,    // 10+11 丢眼定位要塞 + 要塞探索
            BeatPhase.OPEN_PORTAL);  // 12 插眼开末地门

    /** 主 bot 阶段超过该时长没刷新 = 陈旧（主 bot 关了/卡了）→ 退回保守策略"跟随"。 */
    private static final long LEADER_PHASE_STALE_MS = 10_000;

    /** 睡眠放弃阈值（秒）: 与主线 {@code _inBedNoDawnTimer} / {@code _nightBedFindTimer} 同源。 */
    private static final double SLEEP_GIVEUP_SEC = 30;

    /** leader "持续在睡"去抖时长: 避免瞬时 isSleeping 抖动误触发整个睡眠镜像。 */
    private static final long LEADER_SLEEP_DEBOUNCE_MS = 1_000;

    /** 前置是否已完成。static = 跨实例/跨重连存活, 同一 JVM 会话只做一次。 */
    private static boolean _prepDone = false;

    private final String _leader;

    /** 前置子任务缓存与它的种类标记 —— 每 tick new 会打断任务自己的进度。 */
    private Task _prep = null;
    private String _prepKind = null;

    /** 跟随子任务缓存。 */
    private FollowAndWorkTask _follow = null;

    private long _retryAfterMs = 0;
    private boolean _finished = false;

    /** 本次「连续跟丢」的起点（0 = 当前不在跟丢；稳定跟随后清零）。 */
    private long _followLostSinceMs = 0;
    /** 当前 follow 子任务的创建时刻 —— 用于判「已稳定跟随」。 */
    private long _followStartedMs = 0;
    /** 跟丢日志上次落聊天的时间（其余落 diag，防刷屏）。 */
    private long _lastLostLogMs = 0;

    // ------------------------------------------------------------------ //
    // (2026-10-07 步骤2) 远程接近状态
    // ------------------------------------------------------------------ //

    /** 本地看不到主 bot 时的"远程接近"导航任务；null = 当前不走这条路。 */
    private Task _remoteApproach = null;
    /** 远程接近当前导航目标格（用于"移动超阈值才重建"判断）。 */
    private BlockPos _remoteApproachTarget = null;
    /** 远程接近起始时刻（0 = 当前不在远程接近）。 */
    private long _remoteApproachSinceMs = 0;
    /** (2026-10-10 fix C) 最近一次拿到新鲜远程坐标的时刻（宽限计时基准）。 */
    private long _remoteApproachDataMs = 0;

    /** "自主跑主线"时的子任务缓存 —— 同 {@link #_follow}，每拍 new 会打断它自己的进度。 */
    private Task _autoTask = null;

    // ------------------------------------------------------------------ //
    // (2026-10-10) 下界金靴前置
    // ------------------------------------------------------------------ //

    /**
     * 视为"主 bot 正在往/在下界"的大阶段。金靴（猪灵不主动攻击的唯一通行证）必须
     * 在进下界前穿好；ENDER_PEARLS 也算 —— 主 bot 在下界以物易物收珍珠时次 bot 跟着,
     * 没金靴一样会被猪灵围殴。
     */
    private static final Set<BeatPhase> NETHER_BOUND_PHASES = EnumSet.of(
            BeatPhase.PORTAL,
            BeatPhase.BLAZE_RODS,
            BeatPhase.ENDER_PEARLS,
            BeatPhase.BARTER);       // (2026-10-11) 猪灵交易——金靴是交易的前置条件

    /**
     * 补靴总预算（1010 实测 3 分钟太紧, 用户点名"放弃时间需要增长" → 10 分钟）:
     * 背包有=秒级, 没有=挖金+合成 4 金锭。到点放弃并闩死, 等触发线关闭（回主世界阶段）
     * 后重新武装。⚠️ 例外：主 bot 处于 {@link BeatPhase#BARTER}（猪灵交易）时
     * <b>不受本超时约束</b> —— 交易必须金靴, 见 {@link #tickGoldenBoots} 强制分支。
     */
    private static final long GOLDEN_BOOTS_GIVEUP_MS = 10 * 60_000L;

    /** 金靴子任务缓存（ EquipArmorTask / 收集链每 tick new 会打断自身进度, 同 {@link #_prep}）。 */
    private Task _bootsTask = null;
    /** 补靴开始时刻（0 = 当前没在补）。目标达成或放弃时清零。 */
    private long _bootsSinceMs = 0;
    /** 本次触发期内已放弃闩（one-shot: 到点只放弃一次并落一条日志, 不反复刷）。 */
    private boolean _bootsGivenUp = false;

    // ------------------------------------------------------------------ //
    // 睡眠镜像状态
    // ------------------------------------------------------------------ //

    /** 睡觉子任务缓存（SleepThroughNightTask 每 tick new 会让下层无法维持进度）。 */
    private SleepThroughNightTask _sleepTask = null;
    /** 本夜是否已进入睡眠镜像。 */
    private boolean _mirrorSleep = false;
    /** 本夜是否已放弃睡觉（白天自动清除, 避免反复进入镜像空转）。 */
    private boolean _mirrorSleepGivenUpTonight = false;
    /** "未上床"阶段的 30s 计时（有就近机会则重置继续找床）。 */
    private final TimerGame _mirrorBedFindTimer = new TimerGame(SLEEP_GIVEUP_SEC);
    /** "已上床"阶段的 30s 计时（到点仍未天亮 = 有人没睡）。 */
    private final TimerGame _mirrorInBedTimer = new TimerGame(SLEEP_GIVEUP_SEC);
    /** leader 连续在睡的起点（0 = 当前不在睡）。 */
    private long _leaderSleepSeenMs = 0;

    public SubBotBootstrapTask(String leader) {
        _leader = leader;
    }

    /** (2026-10-22) 对端文案用（SummaryGenerator 赶路带目的）：只读领队名。 */
    public String getLeaderName() {
        return _leader;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_finished) return null;
        if (!_prepDone) return tickPrep(mod);
        Task sleep = tickMirrorSleep(mod);
        if (sleep != null) return sleep;
        // (2026-10-10) 下界金靴前置: 主 bot 要进/在下界而次 bot 光脚时, 先补靴再跟随。
        Task boots = tickGoldenBoots(mod);
        if (boots != null) return boots;
        return tickFollow(mod);
    }

    // ------------------------------------------------------------------ //
    // 阶段一: 前置（跑主线本体, 达标即切）
    // ------------------------------------------------------------------ //

    /**
     * 前置: 木头/食物任一不足 → 返回 {@link BeatMinecraft2Task} <b>本体</b>。
     *
     * <p>为什么不自己写精简版（旧实现）: 精简版没有造石斧、没有回地表、没有食物的峰值锁存/
     * 熔炼/远征、没有夜间路由 —— 用户实测"特别不智能"。跑主线本体则<b>一行不复刻</b>地拿到
     * 全部智能; 次 bot 是 {@code role=sub}, 目标已被 {@code BotRole} 降到 8/40, 达标即被切走,
     * 不会一路跑到下界。
     *
     * <p><b>刻意不设总超时</b>（用户 20:2x "不用"）: 主线本体不会卡死（它一直在推进）,
     * 没有"永久卡在前置"的风险。
     */
    private Task tickPrep(AltoClef mod) {
        BeatMinecraftConfig cfg = BeatMinecraft2Task.getConfig();
        int woodTarget = (cfg == null) ? 0 : cfg.woodLogsTarget();
        int foodTarget = (cfg == null) ? 0 : cfg.foodTarget();

        int wood = StorageHelper.countItemsInInventoryReal(mod, ItemHelper.LOG);
        int food = (int) StorageHelper.calculateInventoryFoodScore(mod);

        if (wood >= woodTarget && food >= foodTarget) {
            _prepDone = true;
            String m = "[SUB-BOOT] prep done (wood " + wood + "/" + woodTarget
                    + ", food " + food + "/" + foodTarget + "), entering follow of " + _leader;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return null;
        }
        SubBotDuty.set(SubBotDuty.Mode.PREP, _leader, "为 " + _leader + " 备货（补木头/食物）");
        setDebugState("[次bot引导] 为 " + _leader + " 备货 · 木头 " + wood + "/" + woodTarget
                + " 食物 " + food + "/" + foodTarget);
        return cachedPrep(mod, "mainline", BeatMinecraft2Task::new);
    }

    /**
     * 前置子任务缓存。每 tick `new` 会打断任务自己的进度（囤木会不停地从零重来）,
     * 所以同种类复用同一实例; 只有"换种类"或"已跑完"才重建。
     * （"跑完要重建"这条来自框架铁律: tickInternal 不消费子任务 isFinished, 得由这里收。）
     */
    private Task cachedPrep(AltoClef mod, String kind, Supplier<Task> maker) {
        if (!kind.equals(_prepKind) || _prep == null || _prep.isFinished(mod)) {
            _prepKind = kind;
            _prep = maker.get();
        }
        return _prep;
    }

    // ------------------------------------------------------------------ //
    // 阶段二: 睡眠镜像（主 bot 躺下 → 次 bot 跟着睡）
    // ------------------------------------------------------------------ //

    /**
     * 镜像主 bot 的睡眠。<b>优先级最高</b>——盖过随行与顺手干活: 多人服「全员入睡才跳夜」,
     * 次 bot 不睡主 bot 就一直躺着干等。
     *
     * @return 要执行的任务（睡觉），或 null（本拍不睡）
     */
    private Task tickMirrorSleep(AltoClef mod) {
        // 天亮 = 本夜结束: 解除全部本夜闩锁（含"已放弃"，让下一夜可以重新尝试）。
        if (WorldHelper.isBrightDaytime()) {
            if (_mirrorSleep) {
                Debug.logToFile("altoclef_diag.log", "[SUB-BOOT] mirror sleep done (dawn), back to follow");
            }
            _mirrorSleep = false;
            _mirrorSleepGivenUpTonight = false;
            _leaderSleepSeenMs = 0;
            return null;
        }
        // 本夜已放弃（自身放弃 或 对端 @sleep skip）→ 不再进入。
        if (_mirrorSleepGivenUpTonight || SleepOverride.isSkipping()) return null;

        if (!_mirrorSleep) {
            if (!leaderSleepingDebounced(mod)) return null;
            _mirrorSleep = true;
            _mirrorBedFindTimer.reset();
            _mirrorInBedTimer.reset();
            Debug.logToFile("altoclef_diag.log",
                    "[SUB-BOOT] mirror sleep start: leader '" + _leader + "' is sleeping");
        }

        if (_sleepTask == null) _sleepTask = new SleepThroughNightTask();

        boolean inBed = mod.getPlayer() != null && mod.getPlayer().isSleeping();
        if (inBed) {
            // 已上床: 找床计时无意义, 改等跳夜。
            _mirrorBedFindTimer.reset();
            if (_mirrorInBedTimer.elapsed()) {
                // 躺下 30s 仍未天亮 = 有人没睡（例如第三个 bot）→ 本夜放弃（与主线同源）。
                SleepHelper.wakeUpIfSleeping(mod, "SubBotPeerWait");
                _mirrorSleep = false;
                _mirrorSleepGivenUpTonight = true;
                SleepOverride.requestSkipThisNight();
                Debug.logToFile("altoclef_diag.log",
                        "[SUB-BOOT] mirror sleep give up tonight (in bed 30s without dawn)");
                return null;
            }
        } else {
            // 未上床: 等跳夜计时无意义, 改找床计时。
            _mirrorInBedTimer.reset();
            if (_mirrorBedFindTimer.elapsed()) {
                if (SleepHelper.hasSleepChanceTonight(mod)) {
                    _mirrorBedFindTimer.reset();   // 就近还有机会, 继续找/做床
                } else {
                    _mirrorSleep = false;
                    _mirrorSleepGivenUpTonight = true;
                    SleepOverride.requestSkipThisNight();
                    Debug.logToFile("altoclef_diag.log",
                            "[SUB-BOOT] mirror sleep give up tonight (no bed chance in 30s)");
                    return null;
                }
            }
        }
        SubBotDuty.set(SubBotDuty.Mode.SLEEP, _leader,
                "陪 " + _leader + " 睡觉（多人服需全员入睡才跳夜）");
        setDebugState("[次bot引导] 陪 " + _leader + " 睡觉");
        return _sleepTask;
    }

    /** leader 是否"持续在睡"≥{@link #LEADER_SLEEP_DEBOUNCE_MS}。 */
    private boolean leaderSleepingDebounced(AltoClef mod) {
        if (mod.getEntityTracker() == null) return false;
        boolean sleeping = mod.getEntityTracker().getPlayerEntity(_leader)
                .map(p -> p.isSleeping()).orElse(false);
        long now = System.currentTimeMillis();
        if (!sleeping) {
            _leaderSleepSeenMs = 0;
            return false;
        }
        if (_leaderSleepSeenMs == 0) {
            _leaderSleepSeenMs = now;
            return false;
        }
        return now - _leaderSleepSeenMs >= LEADER_SLEEP_DEBOUNCE_MS;
    }

    // ------------------------------------------------------------------ //
    // 下界金靴前置（2026-10-10, 用户点名"次 bot 没有穿金靴的意识"）
    // ------------------------------------------------------------------ //

    /**
     * 主 bot 要进/在下界（{@link #NETHER_BOUND_PHASES}）而次 bot 未穿金靴 → 先补靴再跟随;
     * 次 bot 自己已在下界（跟随中掉队/传送到半路）同样适用 —— 猪灵敌意不分主次 bot。
     *
     * <p><b>三态与主线同款</b>（复用 {@code BeatMinecraft2Task.getPreNetherGoldenBootsTask}
     * 已验证口径, 不另立语义）: 已穿 → null 放行; 背包有 → {@link EquipArmorTask} 穿上;
     * 没有 → {@link TaskCatalogue#getItemTask} 收集合成（金靴只耗 4 金锭, 门槛极低）。
     *
     * <p><b>出口</b>（铁律: 一切等待必须有出口）: {@link #GOLDEN_BOOTS_GIVEUP_MS} 到点 →
     * 置 {@link #_bootsGivenUp} 闩 + 放行跟随, 绝不永久阻塞。闩在触发线关闭
     * （主 bot 回主世界阶段且次 bot 不在下界）时解除 —— 下次进下界重新尝试。
     *
     * <p>优先级位于睡眠镜像之后、随行之前: 睡觉不能被换靴打断, 而换靴值得短暂打断跟随
     * （穿靴秒级, 收集有超时兜底）。
     *
     * @return 要执行的金靴子任务, 或 null（已穿/已放弃/触发线未命中 → 交回随行）
     */
    private Task tickGoldenBoots(AltoClef mod) {
        boolean selfInNether = WorldHelper.getCurrentDimension() == Dimension.NETHER;
        BeatPhase lp = LeaderPhaseWatcher.phase();
        boolean phaseFresh = LeaderPhaseWatcher.ageMs() <= LEADER_PHASE_STALE_MS;
        boolean leaderNetherBound = lp != null && phaseFresh && NETHER_BOUND_PHASES.contains(lp);
        // (2026-10-11 用户点名) 主 bot 在猪灵交易（BARTER）→ 金靴前置是硬义务：
        // 必须先把靴子任务做完（含收集合成），无超时出口 —— 光脚凑到交易现场，
        // 猪灵围殴既坑自己也搅黄交易。未穿也要先补完再跟。
        boolean leaderTrading = lp == BeatPhase.BARTER && phaseFresh;
        boolean mandatory = leaderTrading && shouldFollowLeaderNow();
        // 触发线: 自己在下界, 或 主 bot 处于下界向阶段且此刻本就轮到次 bot 跟随。
        // （自主模式下 leaderNetherBound 不触发 —— 那时次 bot 跑的是主线本体, 装备自管;
        //   selfInNether 仍然触发: 掉在下界里, 生存优先于自主任务。）
        boolean trigger = selfInNether || (leaderNetherBound && shouldFollowLeaderNow());
        if (!trigger) {
            // 触发线关闭 → 解除放弃闩并清缓存, 下次进下界重新武装（否则一次放弃闩死终身）。
            _bootsTask = null;
            _bootsSinceMs = 0;
            _bootsGivenUp = false;
            return null;
        }
        if (StorageHelper.isArmorEquipped(mod, Items.GOLDEN_BOOTS)) {
            _bootsTask = null;   // 目标达成, 丢缓存（句柄已终态）
            _bootsSinceMs = 0;
            return null;
        }
        if (mandatory && _bootsGivenUp) {
            // 交易期强制 → 解除此前(非强制期)的放弃闩, 重新起算（强制期无超时）。
            _bootsGivenUp = false;
            _bootsSinceMs = 0;
            Debug.logMessage("[SUB-BOOT] 主 bot 交易中, 金靴前置转为强制（解除放弃闩）");
        }
        if (_bootsGivenUp) return null;

        if (_bootsSinceMs == 0) {
            _bootsSinceMs = System.currentTimeMillis();
            String m = "[SUB-BOOT] 下界金靴前置: 未穿金靴（leader=" + _leader
                    + ", phase=" + lp + ", selfInNether=" + selfInNether
                    + (mandatory ? ", 交易强制" : "") + "）, 先补靴";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
        if (!mandatory && System.currentTimeMillis() - _bootsSinceMs > GOLDEN_BOOTS_GIVEUP_MS) {
            _bootsGivenUp = true;
            String m = "[SUB-BOOT] 金靴 " + (GOLDEN_BOOTS_GIVEUP_MS / 60_000)
                    + " 分钟未凑齐 — 本次放弃, 直接跟随（触发线关闭后自动重试；交易期不设超时）";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return null;
        }

        SubBotDuty.set(SubBotDuty.Mode.PREP, _leader,
                mandatory ? "补金靴（主bot 交易中, 强制前置）" : "补金靴（下界前置）");
        // 背包已有 → 优先直接穿（收集中途凑齐时从"挖金"切到"穿上", 不等收集链走完）。
        if (mod.getItemStorage().hasItemInventoryOnly(Items.GOLDEN_BOOTS)) {
            if (!(_bootsTask instanceof EquipArmorTask)) {
                _bootsTask = new EquipArmorTask(Items.GOLDEN_BOOTS);
            }
            setDebugState("[次bot引导] 穿金靴（下界前置）");
            return _bootsTask;
        }
        // 没有 → 收集/合成（粘性实例, 同 cachedPrep 理由）。
        if (_bootsTask == null || _bootsTask.isFinished(mod)) {
            _bootsTask = TaskCatalogue.getItemTask(Items.GOLDEN_BOOTS, 1);
        }
        setDebugState("[次bot引导] 补金靴（下界前置, 4 金锭）");
        return _bootsTask;
    }

    // ------------------------------------------------------------------ //
    // 阶段三: 随行
    // ------------------------------------------------------------------ //

    private Task tickFollow(AltoClef mod) {
        // (2026-10-06 22:5x 用户要求) 跟随与否由【主 bot 当前大阶段】决定：
        // 命中 FOLLOW_PHASES 才跟，否则次 bot 自己跑主线 —— 不再"后面全程跟随"。
        if (!shouldFollowLeaderNow()) {
            return tickAutonomous(mod);
        }
        _autoTask = null;   // 已回到跟随 → 自主任务句柄作废（其实例已被顶掉）

        long now = System.currentTimeMillis();
        if (now < _retryAfterMs) {
            SubBotDuty.set(SubBotDuty.Mode.FOLLOW, _leader,
                    "跟随保护 " + _leader + "（跟丢，等主bot 靠近）");
            setDebugState("[次bot引导] 跟丢 " + _leader + "，等主bot 靠近");
            return null;
        }

        // (2026-10-07 步骤2) 远程坐标补位：本地看不到主 bot 时, 用主 bot 上报的坐标先把
        // "过去"这一步补上（详见 tryRemoteApproach）。
        Task remote = tryRemoteApproach(mod, now);
        if (remote != null) return remote;

        if (_follow == null) {
            _follow = new FollowAndWorkTask(_leader, new FollowAndWorkTask.GreedySideWorkPicker());
            _followStartedMs = now;
        }
        if (_follow.isFinished(mod)) {
            // 跟丢（主 bot 超出加载范围 / 走到旧坐标时人已走开 / 跨维度）。用户口径:
            // "bot 位置变了就再靠近一次" ⇒ 只短暂冷却就重建 follow, 不做长等待 ——
            // 主 bot 一回到加载范围, 下一轮重建时 getPlayerMostRecentPosition 就是新坐标。
            _follow = null;
            if (_followLostSinceMs == 0) _followLostSinceMs = now;
            logFollowLost(now);
            if (now - _followLostSinceMs >= FOLLOW_LOST_GIVEUP_MS) {
                Debug.logMessage("[SUB-BOOT] 连续跟丢 " + (FOLLOW_LOST_GIVEUP_MS / 60_000)
                        + " 分钟，本次引导结束（上层会在冷却后重新拉起）");
                _finished = true;
                return null;
            }
            _retryAfterMs = now + RETRY_COOLDOWN_MS;
            return null;
        }
        // 稳定跟随该时长 = 已跟上 → 清零跟丢计时（否则计时只增不减, 久了会误判"连续跟丢"）。
        if (_followLostSinceMs != 0 && now - _followStartedMs >= FOLLOW_STABLE_MS) {
            _followLostSinceMs = 0;
        }
        SubBotDuty.set(SubBotDuty.Mode.FOLLOW, _leader, "跟随保护 " + _leader);
        setDebugState("[次bot引导] 跟随保护 " + _leader);
        return _follow;
    }

    /**
     * (2026-10-07 步骤2 / 同日 13:4x 升级为通用坐标共享) 本地不可见时的远程接近。
     *
     * <p><b>为什么需要</b>: 客户端只下发 ~48 格内的其他玩家实体。主 bot 走远后（1007 实测
     * 相距 277 格）次 bot 既看不到人、本地也没有坐标, {@code FollowPlayerTask} 只能停在
     * "online but too far: no coordinates yet" 原地干等 —— 永远过不去。
     *
     * <p><b>坐标来源已改为通用的 {@link PeerPosWatcher}</b>（按游戏内玩家名查本机同伴的
     * {@code /status.pos}），不再用 {@code LeaderPhaseWatcher.pos()} 那种"只有 leaderPort
     * 那一台"的口径 —— 后者既要依赖 {@code leaderPort} 配置、又存在"配置里的名字与端口
     * 指向不一致时会朝错的人走"的隐患。
     *
     * <p><b>"要不要走远程、走哪儿"的判定只在 {@link PeerPosWatcher#remoteNav} 一处</b>
     * （与 {@code FollowPlayerTask} 共用）；本方法只负责导航任务的重建节流、超时出口与文案。
     *
     * @return {@code null} = 走常规跟随（无鲜坐标 / 跨维度 / 本地已感知 / 本地坐标够用）
     */
    private Task tryRemoteApproach(AltoClef mod, long now) {
        // 本地已"感知"到主 bot（当前可见/可听, _playerMap 每拍重建）→ 坐标新鲜, 交常规跟随。
        if (mod.getEntityTracker().isPlayerLoaded(_leader)) {
            clearRemoteApproach();
            return null;
        }
        Optional<PeerPosWatcher.Nav> nav = PeerPosWatcher.remoteNav(_leader,
                mod.getEntityTracker().getPlayerMostRecentPosition(_leader),
                WorldHelper.getCurrentDimension());
        if (nav.isEmpty()) {
            // (2026-10-10 fix C) 数据断流宽限：瞬时失效不拆正在走的导航, 沿用最后目标
            // 继续走; 到达/超宽限才降级 follow。_remoteApproachSinceMs 不清零 ——
            // REMOTE_APPROACH_MAX_MS 硬上限不再被乒乓重置。
            if (_remoteApproach != null && _remoteApproachTarget != null
                    && now - _remoteApproachDataMs <= REMOTE_DATA_GRACE_MS
                    && !_remoteApproach.isFinished(mod)) {
                SubBotDuty.set(SubBotDuty.Mode.FOLLOW, _leader,
                        "远程接近 " + _leader + "（坐标断流，保持导航）");
                setDebugState("[次bot引导] 远程接近 " + _leader + "（坐标断流，保持导航）");
                return _remoteApproach;
            }
            clearRemoteApproach();
            return null;
        }
        _remoteApproachDataMs = now;

        if (_remoteApproachSinceMs == 0) _remoteApproachSinceMs = now;
        if (now - _remoteApproachSinceMs >= REMOTE_APPROACH_MAX_MS) {
            // 出口: 长时间走不到 = 跟丢, 结束本次引导（上层冷却后重新拉起）。
            Debug.logMessage("[SUB-BOOT] 远程接近 " + _leader + " 超 "
                    + (REMOTE_APPROACH_MAX_MS / 60_000) + " 分钟未到达，本次引导结束");
            Debug.logToFile("altoclef_diag.log", "[SUB-BOOT] remote approach give up: " + _leader);
            clearRemoteApproach();
            _finished = true;
            return null;
        }

        BlockPos target = nav.get().blockPos();
        if (_remoteApproach == null || _remoteApproachTarget == null
                || _remoteApproachTarget.getSquaredDistance(target)
                        > PeerPosWatcher.RETARGET_DIST * PeerPosWatcher.RETARGET_DIST) {
            _remoteApproach = new GetToBlockTask(target, false, nav.get().dim());
            _remoteApproachTarget = target;
        }
        // 交出去的 follow 会被框架 stop, 句柄留着会被误判成"终态"。
        _follow = null;
        double dist = mod.getPlayer() == null ? -1 : nav.get().pos().distanceTo(mod.getPlayer().getPos());
        SubBotDuty.set(SubBotDuty.Mode.FOLLOW, _leader,
                "远程接近 " + _leader + "（超出客户端跟踪范围）");
        setDebugState("[次bot引导] 远程接近 " + _leader
                + (dist >= 0 ? "（" + (int) dist + " 格外）" : ""));
        return _remoteApproach;
    }

    /** 退出远程接近: 丢导航句柄与计时, 回到常规跟随/自主。 */
    private void clearRemoteApproach() {
        _remoteApproach = null;
        _remoteApproachTarget = null;
        _remoteApproachSinceMs = 0;
        _remoteApproachDataMs = 0;   // (2026-10-10 fix C) 宽限计时一并清零
    }

    /**
     * 此刻该跟随还是自主 = 主 bot 当前大阶段是否落在 {@link #FOLLOW_PHASES} 内。
     *
     * <p>三种"退回跟随"的保守情形（都返回 true = 跟随）：① 未配 {@code leaderPort}
     * （功能未启用 ⇒ 旧语义）；② 从未拉到（主 bot 没开 / 对面还是旧 jar）；③ 阶段陈旧
     * （主 bot 关了或卡住）。一律跟随而非自主 —— 跟随最坏只是贴着他站着，自主会让次 bot
     * 朝未知方向跑，代价大得多。
     */
    private boolean shouldFollowLeaderNow() {
        int port = BridgeConfig.getInstance().leaderPort;
        if (port <= 0) return true;
        LeaderPhaseWatcher.ensureStarted(port);
        BeatPhase p = LeaderPhaseWatcher.phase();
        // (2026-10-07 步骤1) UNKNOWN = 主 bot 未跑主线 / 空闲待命 —— 属"不知道他在干嘛"的
        // 保守情形, 必须跟随（与本方法注释的哲学一致）。此前 UNKNOWN 落在白名单外被当成
        // "明确不跟", 于是主 bot 一待命次 bot 就抛弃跟随去自主（1007 实测; 用户口径:
        // "主bot 是我停的/待命, 但次 bot 也要过去呀"）。
        if (p == null || p == BeatPhase.UNKNOWN || LeaderPhaseWatcher.ageMs() > LEADER_PHASE_STALE_MS) {
            return true;
        }
        return FOLLOW_PHASES.contains(p);
    }

    /**
     * 自主阶段：次 bot 跑自己的主线（{@code role=sub} ⇒ 木头/食物目标已由 {@code BotRole}
     * 降到 1/3，不会像主 bot 那样为整局囤货）。
     */
    private Task tickAutonomous(AltoClef mod) {
        _follow = null;   // 从跟随切到自主 → 丢掉 follow 句柄（其实例已被顶掉）
        clearRemoteApproach();   // 同理丢掉远程接近句柄
        if (_autoTask == null || _autoTask.isFinished(mod)) {
            Debug.logToFile("altoclef_diag.log", "[SUB-BOOT] switch to AUTONOMOUS mainline"
                    + " (leader phase=" + LeaderPhaseWatcher.phase() + ")");
            _autoTask = new BeatMinecraft2Task();
        }
        BeatPhase lp = LeaderPhaseWatcher.phase();
        SubBotDuty.set(SubBotDuty.Mode.COLLECT, _leader,
                "替 " + _leader + " 备货（主bot 正在" + (lp == null ? "未知阶段" : lp.zh()) + "）");
        setDebugState("[次bot引导] 替 " + _leader + " 备货（主bot "
                + (lp == null ? "?" : lp.zh()) + "）");
        return _autoTask;
    }

    /**
     * 跟丢打点: 首次 / 每 {@link #LOST_LOG_INTERVAL_MS} 落聊天, 其余落 diag。
     * （3s 重试 = 每分钟 20 条, 不节流会把聊天刷爆。）
     */
    private void logFollowLost(long now) {
        String who = "目标 " + _leader + " 不可达（超出加载范围/已走开）";
        if (now - _lastLostLogMs >= LOST_LOG_INTERVAL_MS) {
            _lastLostLogMs = now;
            Debug.logMessage("[SUB-BOOT] 跟丢：" + who + "，3s 后重试");
        } else {
            Debug.logToFile("altoclef_diag.log", "[SUB-BOOT] follow lost: " + who + " (retry in 3s)");
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 被顶掉时只丢句柄, 不动 static 闩 —— 前置"只做一次"是跨实例语义。
        // duty 要清: 被对端 @goto 顶掉后, 次 bot 干的是"对端的事", 不该再对外声称"在帮主 bot"。
        SubBotDuty.clear();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SubBotBootstrapTask t && t._leader.equals(_leader);
    }

    @Override
    protected String toDebugString() {
        return "Sub-bot bootstrap (leader: " + _leader + ")";
    }
}
