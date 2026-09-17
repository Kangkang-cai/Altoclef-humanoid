package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TurnToOpenAreaTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.Rotation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 丢弃光标上的物品。
 *
 * (2026-09-01 用户纠正: 丢出是朝向视角的, 不是随机初速度 — 之前我误判成随机,
 * Q键/快捷栏重设计已废弃回退) 点窗外(-999)丢光标物品的方向由玩家朝向决定,
 * 所以: 先交给独立 TurnToOpenAreaTask 转向"前方最开阔"的方向, 视角稳定后再
 * 点窗外丢出 — 物品带朝向初速度飞向开阔处, 不再落回脚下被满背包立刻捡回。
 *
 * (2026-09-03 主动挖开堆头, 用户"四周封闭无开阔空间→丢出落回脚下被重拾→卡死"):
 * 转向后若 TurnToOpenAreaTask 判定四周仍封闭 (最佳方向前方紧贴实心块), 直接丢
 * 会把物品甩到贴脸处被满背包立刻捡回。
 *
 * (2026-09-12 用户钦定隧道丢弃流程, 取代旧"挖一格碰运气"):
 *   封闭时挖掉【和自己头一样高的、面前两个方块】(d=1 与 d=2 的头高层),
 *   然后【对准这个洞的中心】(远格中心), 准星命中后丢出 —
 *   物品沿视线飞进隧道落在 ≥2 格外, 满背包拾取半径够不着, 不再反复吸回。
 *   近→远顺序挖 (同实例续租), 25s 总超时/2s 瞄准超时兜底照丢。
 *
 * (2026-09-11 弹回熔断器, 现场 11:12:53-11:13:27 实锤: 泥土"开阔"方向丢出撞
 * 3-4 格外墙壁反弹落点 1-2 格内, 原版自动拾取塞回刚腾出的槽位, 2s 一轮循环
 * 17 次直到用户干预。原版拾取拦不住, 只能丢出侧熔断):
 *   - onStart 检测光标物品 8s 内刚被扔过 (ItemHelper 记忆) = 弹回被重拾实锤;
 *   - 第 1 次弹回: 扔前先 RunAwayFromPositionTask 离开上次落点 ≥4 格再丢;
 *   - 第 2 次弹回: 该物品进入 60s 丢弃冷却 (选择器跳过, 见 ItemHelper),
 *     本轮仍照常丢出 (光标必须清空), 之后 60s 内不再主动重扔该物品。
 *
 * (2026-09-15 丢坑优先三档, 用户钦定: 现成坑→丢坑里 > 现挖一格埋掉 > 都没有
 * 才挖隧道。现场 08:48 实锤: 深板岩层四周封闭, 空手 ~7.5s/格 vs 光标滞留
 * 8s 超时 → 挖远格永远差半格被收回重捡重挖, 死循环 54s 直到手动停。):
 *   1. 现成坑: 脚下(-1层)半径 6 格扫一次, 开口/上方无盖/非液体即合格;
 *      优先 ≥2 格深的坑 (路过不会踩进去重拾), 对准坑底中心丢, 不挖任何方块。
 *   2. 现挖一格: 封闭且无现成坑 → 挖脚前一格地板成洞 (正下方非液体, 防挖开
 *      岩浆地板), 对准洞中心丢; 30s 挖不动兜底照丢。
 *   3. 隧道兜底: 连地板都没得挖 (基岩/岩浆) 才走 09-12 两格隧道流程。
 *   挖坑/隧道进行中向 PlayerInteractionFixChain 暴露 isDiggingForThrow(),
 *   豁免 8s 光标滞留超时 (防重捡死循环); 本任务自带 25s/30s 超时兜底照丢。
 *   防重拾低风险实现: 洞口目标 ≥2 格远 + 洞底比脚低 1 格 (原版拾取够不着),
 *   万一仍被重拾, 既有弹回熔断 (走开 ≥4 格 / 60s 冷却) 接管。
 *
 * (2026-09-17 用户钦定【丢坑三档废除】, 213247 场实锤: 三连 pit 丢弃全部低头
 * 下砸且 7s 内弹回 —— 坑方案与"最远距离不被拾取"根本矛盾: ① 向下丢距离短;
 * ② 物品落洞口在拾取半径内; ③ bot 事后走回坑边照样重拾。唯一丢弃流程改为:
 *   方向选定 (弹回场景=落点→当前位置的离开方向, 其余=最开阔方向)
 *   → ensureFrontHeadTunnel: 面前与头同高的两格 (d=1,d=2) 实心就挖穿
 *   → pitch=0 严格【水平】平抛, 禁止任何仰角/俯角 (用户: "肯定要水平扔")。
 *   走开完成后的强制朝向 pitch 也从 -15° 改 0。)
 *
 * (2026-09-15 方向纪律, 用户现场观察: "bot 走开后丢东西, 理应面朝离开方向丢,
 * 却总是突然转过头来, 结果又丢在要去的路上"。根因: 走开后方向选择交回
 * TurnToOpenAreaTask (挑"最开阔"方向, 可以正好回头), 坑选择只看距离不看方向,
 * 坑还可能就在工作台/必经之路旁边 (112,10,-323 距合成台 111,11,-322 仅 1.4 格)):
 *   - 弹回场景记录离开方向 _preferredDir (上次落点→当前位置, 水平单位向量);
 *   - 走开完成后不再交给 TurnToOpenAreaTask, 强制面朝离开方向直接丢 ——
 *     刚走过来的方向必然开阔;
 *   - (2026-09-17 坑选择两条随丢坑三档一并废除, 走开完成时用落点→当前位置
 *     重算 yaw, 不再维护 _preferredDir 向量。)
 *
 * (2026-09-15 丢弃会话 static 继承, 用户"又是我介入才进行下去", 095835 会话实锤:
 *   ThrowCursor 平均每 2s 被抢断/整树重建一次, 09:58:59-10:00:05 几十轮零丢出。
 *   三类抢断者: ① EnsureFreeCursorSlotTask 放置↔抛丢自我翻面 ② 容器链
 *   freeing↔walk 翻面 ③ 上层任务目标变化整树重建 (isEqual 不匹配)。共同要害 =
 *   任务实例一换, 瞄准/挖掘进度清零, 永远走不到丢出。修法: 阶段状态 (坑/挖格/
 *   隧道/强制朝向 + 各自起始 tick) 全部改 static —— 进度跟着光标走, 实例怎么
 *   重建都继承; 会话边界 = 光标非空且距上次活动 <2.5s, 丢完/换垃圾/长时间中断
 *   才重开新会话。继承时瞄准起点只留 5 tick 余量, 既不"继承即丢看都不看",
 *   也不给抢断任何拖延收益。)
 */
public class ThrowCursorTask extends Task {

    private final Task _throwTask = new ClickSlotTask(Slot.UNDEFINED);
    // 独立转向任务: 先转向开阔处, 视角稳定后才丢。
    private TurnToOpenAreaTask _turnTask;
    // 主动挖通关卡的任务 (封闭时清理该方向前方实心块)。
    private DestroyBlockTask _digTask;
    // (_runAwayTask/_runAwayStartTick 已改 static, 见下方丢弃会话块。)
    // (2026-09-11) 挖开处计数, 上限防无限挖。 → (2026-09-12 废弃: 改用户钦定隧道流程)
    // (2026-09-15 丢弃会话 static 继承) 以下阶段状态全部 static: 实例被抢断/重建
    // 时进度跟着光标走, 不再清零 (现场与机制见类注释)。同一时间只有一个
    // ThrowCursorTask 活动 (_activeInstance), static 无并发问题。
    private static BlockPos _tunnelNear;
    private static BlockPos _aimFar;
    private static int _tunnelStartTick = -1;
    private static int _aimStartTick = -1;
    // 空手 ~7.5s/格 × 2 格 + 富余; 超时照丢 (光标必须清空)。
    private static final int TUNNEL_TIMEOUT_TICKS = 20 * 25;
    // 瞄准就位等待上限 (lookAt 是平滑转头)。
    private static final int AIM_TIMEOUT_TICKS = 20 * 2;
    // (2026-09-12 v10) 弹回熔断"走开"步骤超时: 封闭空间 baritone 无路,
    // RunAwayFromPositionTask 永不 finished → ThrowCursor 卡死在走开这步。
    // 走不动就放弃走开, 照常丢 (死点兜底)。
    private static final int RUNAWAY_TIMEOUT_TICKS = 20 * 5;
    // (2026-09-11) 丢出记录只做一次 (转向/挖格期间会多次走到 _throwTask 分支)。
    // static: 被抢断重建后不重复记录 (recordCursorThrown 时间戳必须是真实丢出)。
    // 会话按物品划分 (_sessionItem), 新物品 onStart 自然重置 _throwRecorded。
    private static boolean _throwRecorded = false;
    // (2026-09-17 用户钦定, 废除 09-15 丢坑三档) 现成坑/脚前洞全部移除:
    // 坑是【向下】丢 (低头), 物品落洞口在拾取半径内, bot 事后走回坑边照样
    // 重拾 (213247 场实锤: 三连 pit 丢出全部弹回, oak_sapling 7s 内回笼)。
    // 唯一丢弃方式 = 【水平】丢: 方向选定 → 面前与头同高两格 (d=1,d=2) 实心
    // 就挖穿 → 平抛出隧道, 最远距离。
    // (2026-09-15) 豁免标记: 挖隧道进行中, PlayerInteractionFixChain 不收回光标。
    private static ThrowCursorTask _activeInstance;
    // (2026-09-15 方向纪律) 弹回场景: 上次落点、强制朝向 (背对落点, pitch 0
    // 【水平】—— 2026-09-17 用户纠正: 禁止任何仰角/俯角, 仰角会砸近处)。
    private static BlockPos _lastDropPos = null;
    private static Rotation _forcedYaw = null;
    private static int _forceYawStartTick = -1;
    // 面朝离开方向的转向兜底 (平滑转头 1s 足够, 超时照丢, 光标必须清空)。
    private static final int FORCE_YAW_TIMEOUT_TICKS = 20;
    // (2026-09-15 丢弃会话 static 继承) 弹回走开子任务也 static (走开中途被抢断
    // 不重走)。RunAwayFromPositionTask 实例无实例态依赖, 可跨实例复用。
    private static RunAwayFromPositionTask _runAwayTask;
    private static int _runAwayStartTick = -1;
    // 会话活性: 每 tick 刷新; onStart 以 "同物品 + 距上次活动 <2.5s" 判定继承。
    private static Item _sessionItem = null;
    private static long _lastActiveMs = 0;
    private static final long SESSION_GAP_MS = 2500;
    // 继承时瞄准起点留的余量 (tick): 既不"继承即丢", 也不给抢断拖延收益。
    private static final int INHERIT_AIM_GRACE_TICKS = 5;

    // ── (2026-09-17 丢垃圾升级阶梯, 用户钦定) ─────────────────────────────
    // 同一件垃圾被扔出后短时间内又回到光标 (被原版拾取吸回) = "又触发丢垃圾"。
    // 每级给 2 次尝试, 之后再触发就升级:
    //   L0: 原地正常丢 (方向选定 → 面前头高两格挖穿 → 水平平抛)
    //   L1: 究极丢垃圾 — 走开 ≥5 格 + 强制面朝"落点→当前位置"的离开方向水平丢
    //   L2: 大招 — 走开 ≥10 格, 其余同 L1
    //   L3: 终极大招 — 不扔了, 就近放箱子, 垃圾全进箱 (EnsureFreeInventorySlotTask
    //       路由到 GarbageChestDisposalTask; 木料不足落回 L2)
    // 计数器 static (实例被抢断重建不清零, 与会话状态同口径); 按物品回弹窗口
    // 判定"又触发", 连续丢不同物品不会误升级 (不同物品 lastThrow 为空)。
    private static final long RETRIGGER_WINDOW_MS = 45_000;
    private static int _retriggerStreak = 0;
    private static long _lastStreakMs = 0;
    // 真实丢出时刻 (throwNow 记录), 供下一轮判定"短时间内又触发"。
    private static long _lastThrowDoneMs = 0;

    /** 当前升级等级 0..3 (见类内阶梯注释)。超时窗口外自动衰减回 0。 */
    public static int getEscalationLevel() {
        if (System.currentTimeMillis() - _lastStreakMs > RETRIGGER_WINDOW_MS * 2) {
            return 0;
        }
        return Math.min(3, _retriggerStreak / 2);
    }

    /** 该等级的走开距离 (用户钦定: L1 ≥5 格, L2 ≥10 格, L0 弹回熔断沿用 4 格)。 */
    private static double runAwayDistanceForLevel(int level) {
        return level >= 2 ? 10 : level == 1 ? 5 : 4;
    }

    /** 挖隧道进行中 → 8s 光标滞留超时豁免 (7.5s/格 vs 8s 超时死循环, 见类注释)。
     *  (2026-09-15) 阶段状态已 static 化, 不依赖实例存活 —— 实例被抢断重建的
     *  间隙里豁免同样成立 (否则 8s 超时会在间隙误触)。 */
    public static boolean isDiggingForThrow() {
        return _tunnelNear != null;
    }

    /**
     * (2026-09-15) 丢弃流程是否正持有光标 (从垃圾上光标到丢出完成的全程)。
     * 其它链在"把光标内容放回背包/洗槽"之前必须先查这个 —— 现场实锤
     * (20260915-091355 gridwar.log 09:25:31-09:25:51): CraftGenericWithRecipeBooksTask
     * drain 分支每 2s 把腾位链刚拿上光标、正瞄准坑准备丢出的花岗岩 x14 放回 17 号槽,
     * ThrowCursorTask 八轮全部死在瞄准阶段, 一次都没丢出去。丢弃有界 (2s 瞄准 +
     * 25s/30s 挖掘超时兜底照丢), 等待方 return null 等它丢完即可。
     * (命名避开基类 Task.isActive() 实例方法。)
     */
    public static boolean isDiscardInProgress() {
        return _activeInstance != null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _activeInstance = this;
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        // (2026-09-15 丢弃会话 static 继承) 同一件垃圾的丢弃流程被抢断/重建 →
        // 继承 static 阶段状态, 进度不清零。不同物品 = 新会话 (重扫坑)。
        boolean inherit = !cursor.isEmpty() && cursor.getItem() == _sessionItem
                && _lastActiveMs > 0
                && System.currentTimeMillis() - _lastActiveMs < SESSION_GAP_MS;
        if (!inherit) {
            // 新会话: 清阶段状态 + 弹回检测。
            _sessionItem = cursor.isEmpty() ? null : cursor.getItem();
            _turnTask = new TurnToOpenAreaTask();
            _digTask = null;
            _runAwayTask = null;
            _runAwayStartTick = -1;
            _throwRecorded = false;
            _tunnelNear = null;
            _aimFar = null;
            _tunnelStartTick = -1;
            _aimStartTick = -1;
            _lastDropPos = null;
            _forcedYaw = null;
            _forceYawStartTick = -1;
            // 弹回检测: 光标物品 8s 内刚被扔过 → 计入弹回, 视弹回次数升级处理。
            // (2026-09-17 升级阶梯) 计数窗口放宽到 45s: 8s 弹回窗口外但在 45s 内
            // 又拿上光标同样算"又触发丢垃圾", 推进阶梯; 窗口外的新会话衰减计数。
            int escLevel = getEscalationLevel();
            boolean retrigger = false;
            if (!cursor.isEmpty() && mod.getPlayer() != null) {
                Item item = cursor.getItem();
                long lastThrow = ItemHelper.getLastCursorThrowMs(item);
                long now = System.currentTimeMillis();
                if (lastThrow > 0 && now - lastThrow <= RETRIGGER_WINDOW_MS) {
                    retrigger = true;
                }
                if (lastThrow > 0 && now - lastThrow <= ItemHelper.getThrowBounceWindowMs()) {
                    ItemHelper.notifyGarbageReturned(item);
                    BlockPos lastDrop = ItemHelper.getLastThrowDropPos(item);
                    if (lastDrop != null
                            && lastDrop.isWithinDistance(mod.getPlayer().getBlockPos(), 6)) {
                        // 先离开上次落点 (距离按升级等级: L0=4, L1=5, L2+=10), 再丢弃。
                        _runAwayTask = new RunAwayFromPositionTask(
                                runAwayDistanceForLevel(escLevel), lastDrop);
                        _lastDropPos = lastDrop.toImmutable();
                        // (2026-09-15 方向纪律) 记录离开方向 (落点→当前位置), 走开后
                        // 强制面朝这个方向丢, 不交给 TurnToOpenAreaTask 乱挑方向。
                        // (2026-09-17 朝向加固) 走开路径可能拐弯, 真正的离开方向在
                        // 走开【完成时】用落点→当前位置重算 (见 onTick 走开完成分支),
                        // 这里只记录落点锚。
                        _turnTask = null; // 方向待走开完成后定, 不挑"最开阔"。
                        Debug.logMessage("(ThrowCursorTask) [ThrowBreaker] " + item
                                + " bounced back within " + (now - lastThrow) / 1000 + "s — level="
                                + escLevel + " walkaway=" + (int) runAwayDistanceForLevel(escLevel)
                                + " blocks before rethrow");
                    }
                }
            }
            if (retrigger) {
                _retriggerStreak++;
                _lastStreakMs = System.currentTimeMillis();
                if (getEscalationLevel() > escLevel) {
                    Debug.logMessage("(ThrowCursorTask) [ThrowLadder] garbage re-triggered, escalation level -> "
                            + getEscalationLevel()
                            + (getEscalationLevel() >= 3 ? " (chest mode, routed by EnsureFree)" : ""));
                }
            } else if (System.currentTimeMillis() - _lastStreakMs > RETRIGGER_WINDOW_MS) {
                _retriggerStreak = 0;
            }
            // (2026-09-17 阶梯 L1+) 即使超出 8s 弹回窗口, 等级 ≥1 时也强制走开再丢,
            // 不再原地反复丢同一个位置 (用户: 走开至少 5/10 格, 朝远离方向丢)。
            if (_runAwayTask == null && getEscalationLevel() >= 1 && !cursor.isEmpty()
                    && mod.getPlayer() != null) {
                BlockPos lastDrop = ItemHelper.getLastThrowDropPos(cursor.getItem());
                if (lastDrop != null
                        && lastDrop.isWithinDistance(mod.getPlayer().getBlockPos(), 12)) {
                    _runAwayTask = new RunAwayFromPositionTask(
                            runAwayDistanceForLevel(getEscalationLevel()), lastDrop);
                    _lastDropPos = lastDrop.toImmutable();
                    _turnTask = null;
                    Debug.logMessage("(ThrowCursorTask) [ThrowLadder] level="
                            + getEscalationLevel() + " walkaway before throw from "
                            + lastDrop.toShortString());
                }
            }
        } else {
            // 继承: 瞄准起点若已在计时, 只留 5 tick 余量 (不"继承即丢", 也不给
            // 抢断拖延收益); 挖掘/走开进度原样保留。
            _digTask = null;
            if (_aimStartTick >= 0) {
                _aimStartTick = WorldHelper.getTicks() - AIM_TIMEOUT_TICKS + INHERIT_AIM_GRACE_TICKS;
            }
            if (_forceYawStartTick >= 0) {
                _forceYawStartTick = WorldHelper.getTicks() - FORCE_YAW_TIMEOUT_TICKS + INHERIT_AIM_GRACE_TICKS;
            }
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-09-15 会话活性) 每 tick 刷新, 供下个实例的 onStart 判定继承。
        _lastActiveMs = System.currentTimeMillis();
        // 0) (2026-09-11) 弹回后先离开上次落点, 走开后再转向丢弃。
        //    (2026-09-12 v10) 封闭空间兜底: baritone 无路时 RunAway 永不完成 →
        //    卡死死点。5s 走不动就放弃, 直接进入转向+丢弃流程。
        if (_runAwayTask != null) {
            if (_runAwayStartTick < 0) _runAwayStartTick = WorldHelper.getTicks();
            if (!_runAwayTask.isFinished(mod)
                    && WorldHelper.getTicks() - _runAwayStartTick < RUNAWAY_TIMEOUT_TICKS) {
                return _runAwayTask;
            }
            _runAwayTask = null;
            // (2026-09-17 朝向加固, 用户实测"bot 很容易自己选择一个相反错误的方向")
            // 走开完成时用【落点→当前位置】现算离开方向并强制面朝它, 拒绝
            // TurnToOpenAreaTask 乱挑"最开阔"(可能正好回头朝工作台)。
            // (2026-09-17 用户钦定) pitch=0 严格水平, 禁止任何仰角/俯角。
            if (_lastDropPos != null && mod.getPlayer() != null) {
                double dx = mod.getPlayer().getX() - (_lastDropPos.getX() + 0.5);
                double dz = mod.getPlayer().getZ() - (_lastDropPos.getZ() + 0.5);
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.5) {
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx / len, dz / len));
                    _forcedYaw = new Rotation(yaw, 0f);
                    _turnTask = null;
                } else {
                    _turnTask = new TurnToOpenAreaTask();
                }
            } else {
                _turnTask = (_forcedYaw != null) ? null : new TurnToOpenAreaTask();
            }
        }
        // 0.8) (2026-09-17) 隧道挖掘进行中 → 优先继续 (挖近格→远格→水平瞄准→丢)。
        if (_aimFar != null) {
            // 总超时兜底: 空手 7.5s/格 × 2 + 瞄准富余。超时就照丢 (光标必须清空)。
            if (WorldHelper.getTicks() - _tunnelStartTick > TUNNEL_TIMEOUT_TICKS) {
                Debug.logMessage("(ThrowCursorTask) [ThrowTunnel] timeout, throwing anyway");
                resetTunnel();
                return throwNow(mod);
            }
            // 近格还实心 → 挖近格。
            if (WorldHelper.isSolid(mod, _tunnelNear)) {
                if (!WorldHelper.canBreak(mod, _tunnelNear)) {
                    // 不可挖 (基岩/保护方块) → 隧道放弃, 直接丢兜底。
                    resetTunnel();
                    return throwNow(mod);
                }
                _digTask = new DestroyBlockTask(_tunnelNear);
                setDebugState("Throw: digging tunnel near " + _tunnelNear);
                return _digTask;
            }
            // 远格还实心 → 挖远格。
            if (WorldHelper.isSolid(mod, _aimFar)) {
                if (!WorldHelper.canBreak(mod, _aimFar)) {
                    resetTunnel();
                    return throwNow(mod);
                }
                _digTask = new DestroyBlockTask(_aimFar);
                setDebugState("Throw: digging tunnel far " + _aimFar);
                return _digTask;
            }
            // 洞已打穿 (两格都空) → 每 tick 对准远格中心 (与头同高 ≈ 水平), 命中即丢。
            if (_aimStartTick < 0) _aimStartTick = WorldHelper.getTicks();
            LookHelper.lookAt(mod, new Vec3d(_aimFar.getX() + 0.5, _aimFar.getY() + 0.5,
                    _aimFar.getZ() + 0.5));
            if (LookHelper.isLookingAt(mod, _aimFar)
                    || WorldHelper.getTicks() - _aimStartTick > AIM_TIMEOUT_TICKS) {
                resetTunnel();
                return throwNow(mod);
            }
            setDebugState("Throw: aiming at tunnel center");
            return null;
        }
        // 1) (2026-09-15 方向纪律 / 2026-09-17 用户钦定水平丢) 弹回场景: 面朝离开
        //    方向 (背对上次落点, pitch=0 严格水平) → 确认面前头高两格清空 → 平抛。
        if (_forcedYaw != null) {
            if (_forceYawStartTick < 0) _forceYawStartTick = WorldHelper.getTicks();
            LookHelper.lookAt(mod, _forcedYaw);
            if (LookHelper.isLookingAt(mod, _forcedYaw)
                    || WorldHelper.getTicks() - _forceYawStartTick > FORCE_YAW_TIMEOUT_TICKS) {
                float yaw = _forcedYaw.getYaw();
                _forcedYaw = null;
                // (2026-09-17 用户钦定) 丢前必须保证面前与头同高两格清空,
                // 物品沿头高平抛出最远距离, 不被拾取半径够到。
                if (ensureFrontHeadTunnel(mod, yaw, "escape direction")) {
                    return null; // 隧道已立项, 下一 tick 进入 0.8) 挖近格。
                }
                return throwNow(mod);
            }
            setDebugState("Throw: facing away from last drop (horizontal)");
            return null;
        }
        // 2) 转向中: 完成前持续转向。
        if (_turnTask != null && !_turnTask.isFinished(mod)) {
            return _turnTask;
        }
        // 3) 转向完成 → 同一口径: 面前与头同高两格实心就挖穿 (完全封闭空间走到这里
        //    必然两格全实心 → 隧道), 开阔则通常两格本就空 → 直接水平丢。
        if (_turnTask != null) {
            float bestYaw = _turnTask.getBestYaw();
            _turnTask = null;
            if (ensureFrontHeadTunnel(mod, bestYaw, "open direction")) {
                return null;
            }
            return throwNow(mod);
        }
        // 4) 兜底: 无转向任务 → 直接丢。
        return throwNow(mod);
    }

    /**
     * (2026-09-17 用户钦定) 丢弃前检查所选方向面前与头同高的两格 (d=1,d=2):
     * 有实心 → 立项隧道 (挖穿后水平平抛), 返回 true; 两格本就清空 / 不可挖
     * (基岩/保护方块, 兜底照丢) → 返回 false。
     */
    private boolean ensureFrontHeadTunnel(AltoClef mod, float yaw, String scene) {
        if (mod.getPlayer() == null) {
            return false;
        }
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(rad));
        int dz = (int) Math.round(Math.cos(rad));
        BlockPos p = mod.getPlayer().getBlockPos();
        BlockPos near = p.add(dx, 1, dz);
        BlockPos far = p.add(dx * 2, 1, dz * 2);
        boolean nearSolid = WorldHelper.isSolid(mod, near);
        boolean farSolid = WorldHelper.isSolid(mod, far);
        if (!nearSolid && !farSolid) {
            return false; // 头高两格已清空 → 直接水平丢。
        }
        BlockPos blocking = nearSolid ? near : far;
        if (!WorldHelper.canBreak(mod, blocking)) {
            Debug.logMessage("(ThrowCursorTask) [ThrowTunnel] front head block unbreakable at "
                    + blocking.toShortString() + ", throwing anyway (" + scene + ")");
            return false; // 兜底照丢 (光标必须清空), 弹回熔断接管。
        }
        _tunnelNear = near;
        _aimFar = far;
        _tunnelStartTick = WorldHelper.getTicks();
        _aimStartTick = -1;
        Debug.logMessage("(ThrowCursorTask) [ThrowTunnel] digging head-height front two blocks ("
                + scene + "): near=" + near.toShortString() + " far=" + far.toShortString());
        return true;
    }

    /** 清掉隧道状态 (超时/不可挖/已丢弃), 回到普通直接丢路径。 */
    private void resetTunnel() {
        _tunnelNear = null;
        _aimFar = null;
        _digTask = null;
        _aimStartTick = -1;
    }

    // (2026-09-11) 真正进入丢出前的唯一记录点: 记录物品+落点 (供弹回熔断用)。
    private Task throwNow(AltoClef mod) {
        if (!_throwRecorded && mod.getPlayer() != null) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (!cursor.isEmpty()) {
                ItemHelper.recordCursorThrown(cursor.getItem(), mod.getPlayer().getBlockPos());
            }
            _throwRecorded = true;
        }
        // (2026-09-17 升级阶梯) 记录真实丢出时刻, 供下一轮判定"短时间内又触发"。
        _lastThrowDoneMs = System.currentTimeMillis();
        return _throwTask;
    }

    // (2026-09-17 用户钦定) 丢坑三档整体废除: isDirectionOpenEnough / isFullyEnclosed
    // / findNearbyPit / scanPits / getPitDepth / findFloorPitCandidate 随之删除。
    // 唯一丢弃流程 = 方向选定 → ensureFrontHeadTunnel (面前头高两格挖穿) → 水平平抛。

    // (2026-09-12 挖墙选点逻辑废弃: findFirstWallInBestDir 删除, 改为用户钦定的
    //  "挖面前两个头高方块 → 对准洞中心 → 丢" 隧道流程, 见 onTick 分支 1/3。)


    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // (2026-09-15) 实例结束必须摘掉豁免标记, 否则 8s 光标超时永久失效。
        if (_activeInstance == this) {
            _activeInstance = null;
        }
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof ThrowCursorTask;
    }

    @Override
    protected String toDebugString() {
        return "Throwing Cursor";
    }

    // (2026-09-17 统一拦截) 光标物品正被某次两拍搬运持有 (MoveItemToSlotTask
    // 在途表, 1s 滑动过期) → 本任务拒绝执行丢弃, 立即视为完成让父任务重新
    // 决策。放本体的原因: ThrowCursorTask 全库共 8 个派发点 (EnsureFree×2、
    // DoStuffInContainer、SmeltInFurnace、Craft×2、EnsureFreeCursorSlot、
    // GiveItemToPlayer), 逐派发点加保护已两次漏堵 (0917 清晨 090520/092555
    // 两场连发), 统一收口到唯一执行体。例外: 构造传 ignoreInFlightGuard=true
    // (GiveItemToPlayerTask 的"扔给玩家"是故意交付, 物品不经 MoveItemToSlot
    // 拿起故通常不会命中在途表, 但显式豁免防边缘竞态)。
    private final boolean _ignoreInFlightGuard;
    private static long _lastInFlightLogMs = 0;

    public ThrowCursorTask() {
        this(false);
    }

    public ThrowCursorTask(boolean ignoreInFlightGuard) {
        _ignoreInFlightGuard = ignoreInFlightGuard;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (!_ignoreInFlightGuard) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (!cursor.isEmpty() && MoveItemToSlotTask.isCursorItemInFlight(cursor.getItem())) {
                long now = System.currentTimeMillis();
                if (now - _lastInFlightLogMs > 2000) {
                    _lastInFlightLogMs = now;
                    Debug.logToFile("altoclef_diag.log", "[MOVE-GUARD-TC] refused to throw in-flight "
                            + cursor.getItem().getTranslationKey());
                }
                // 拒绝丢弃: 立即"完成"交还父任务重新决策 (父会重派或让路,
                // 在途表 1s 过期后搬运卡死场景自动解除保护)。
                return true;
            }
        }
        return _throwTask.isFinished(mod);
    }
}
