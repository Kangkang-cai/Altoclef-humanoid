package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.DryGroundHelper;
import adris.altoclef.util.helpers.PillarJumpHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WaterStandHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 游泳卡住下潜反应 (SwimDiveChain，2026-09-01)。
 *
 * Baritone 水下寻路不可靠 (见 DiveToGrabItemTask 注释)：游泳/涉水导航时，bot
 * 常被水面顶住或贴着水面/水下岩壁原地划水，XZ 坐标几秒不动却从 baritone 视角
 * 看"正在导航"。此时沿用海底拾取验证过的下潜输入模式 (面向+前进+潜行下沉+疾跑)
 * 短暂下潜几秒，重新咬合水面/绕过顶住的水面，解除"原地划水"。
 *
 * 设计要点 (与 SwingThroughChain 同构，后台反应非任务)：
 *  - 是【后台反应】，不是任务：绝不 setTask()/subTask()，不改任务优先级数值、
 *    不参与任务树。但 (2026-09-24 修订) dive/place 激活期 getPriority 临时返回
 *    DIVE_CONTROL_PRIORITY=60 持权接管仲裁 —— 原先"恒 -Inf 永不抢权 + 原版键
 *    注入"被实锤无效: baritone 寻路期玩家 input 是 PlayerMovementInput, 只读
 *    baritone 强制表 (反编译字节码), 原版 KeyBinding 层全被旁路; 且 alto 跑在
 *    tick HEAD、baritone 写表在玩家 tick 前, 不让主任务让位就无法赢得写时序。
 *    持权期主任务 SingleTaskChain.onInterrupt 只 interrupt 不销毁, 归还后
 *    reset() 原任务继续 (MobDefense/WorldSurvival 抢权同款成熟模式)。
 *  - 触发条件（严格收窄，避免误伤正常游泳）：
 *      (1) 玩家确实在水中 (isTouchingWater())，且【不在船上】：
 *          船上注水潜行会下船，破坏 BoatCrossingTask。
 *      (2) 空气充足 (air/humidity 高)：空气快见底时下潜会加重溺水，应交给
 *          WorldSurvivalChain.handleDrowning 上浮。
 *      (3) 处于导航/挖掘类主任务（复用 movement 包名判定），非战斗。
 *      (4) 屏幕关闭 (非容器/合成/聊天/死亡)。
 *      (5) 3 秒（SWIM_STUCK_WINDOW_TICKS）内玩家水平位移 < 1 格 → 判卡住。
 *      (6) 同一目标方块的破坏进度【未在推进】(2026-09-27 新增, 见字段区
 *          2026-09-27 注释): 挖矿时 XZ 天然零位移, 不加此条则"挖着挖着被判
 *          游泳卡住"→ 抢权 forceCancel 掉 baritone 已规划好的路线。用户口径:
 *          "有持续挖掘进度时不应该下潜游", 且必须是"进度在推进"而非"正在挖"。
 *  - 触发动作 = 兜底阶梯 (2026-09-12 升级):
 *      ① 脚下垫方块止浮 (WaterStandHelper, 优先): 现场 20260912-085838 实锤
 *         bot 在水面 y7↔9 每 0.5s 上下振荡 (bobbing), dive+sprint 10+ 次零
 *         进展, 目标方块距离随 y 振荡在可达边缘闪进闪出, 挖掘无法进行。
 *         往脚下水格垫方块, bot 落上站定后瞄准/可达/挖掘速度同时恢复
 *         (水中悬空挖掘有 5x 水 × 5x 悬空 = 25x 惩罚, 浮着挖不现实)。
 *         【连续垫多块】(09-12 用户要求): 一格垫满 bot 被抬高一格, bob 上浮
 *         腾出下一格继续垫, 自底向上填满水柱; 站上柱顶后 pillar-jump 一路
 *         垫出水面。放置用 force-click (构造 hit+UP 直接交互, 不等准星
 *         收敛 —— 首版等收敛被 baritone 抢旋转, 6 轮全 TIMEOUT 零放置)。
 *         【压住跳跃】(09-12 100413 会话, 用户点破): baritone 水里持续 hold
 *         JUMP = 向上游 = bobbing 的根源。垫方块/下潜全程每 tick release
 *         JUMP 让 bot 沉降稳住; 出水判定必须 onGround, bob 顶点假出水
 *         (PLACE_OUT_OF_WATER placed=0 骗退) 不算数。
 *      ② 下潜向前游 (原有路径): 无建材 / 没得垫 (深水无底、水草挡路、
 *         放置禁区) / 垫方块窗口超时 / 站稳但非本反应所垫 (浅水底,
 *         垫档让位防死结) → 面向当前前进方向 + 按住 FORWARD +
 *         SNEAK (下沉) + SPRINT (加速), 短暂下潜重新咬合水面。
 *      ③ 就近上岸 (2026-10-09 新增第三档): 连续 3 个【零放置】垫档窗口
 *         (垫档/下潜都帮不上的贴底水面) → 环扫最近"真干陆地"
 *         (DryGroundHelper) 强制 前进+跳+疾跑 朝岸游, 出水站稳即收工,
 *         30s 硬上限出口。现场 20261009-223240 卡水面 10 分 40 秒实锤
 *         旧阶梯在"水底贴脚 + 水里永不 onGround"场景下垫档空转无出口。
 *    两档都自带冷却, 避免每 tick 狂触发。
 *  - 只加行为+日志，不改任何既有任务/优先级数值/状态机跳转。
 *  - 绝不调用 getPriority()（避开 setTask→getPriority→setTask 递归铁律）。
 */
public class SwimDiveChain extends TaskChain {

    // 卡住阈值：N 个游戏 tick（60 ≈3s，用户要求"XZ 不动几秒"）
    private static final int SWIM_STUCK_WINDOW_TICKS = 60;
    // 下潜向前游持续 tick 数（60 ≈3s，用户要求"下潜几秒"）
    private static final int DIVE_HOLD_TICKS = 60;
    // 下潜后冷却（tick）。触发一次后即使仍卡，过一段时间才允许再次下潜。
    private static final long RE_DIVE_COOLDOWN_TICKS = 100; // 5s
    // 垫方块尝试窗口（tick, 150 ≈7.5s）：要连垫数块填满水柱 (用户 09-12 要求
    // "多放几个"), 覆盖多个 bob 周期 + 每块之间的上浮间歇。
    private static final long PLACE_WINDOW_TICKS = 150;
    // 垫方块成功站稳后的宽限期（tick, 120 ≈6s）：让导航自己走起来,
    // 期间不再触发垫/潜, 避免把站着的 bot 又按回水里。
    private static final long STAND_GRACE_TICKS = 120;
    // 站稳状态记忆（tick, 300 ≈15s）：刚垫完的"站稳"才值得宽限；
    // 超过这个时间的 SUPPORTED 说明 bot 本来就站在浅水底, 垫方块帮不上。
    private static final long PLACE_STAND_MEM_TICKS = 300;
    // 卡住判定：窗口内玩家水平位移 < 1 格即视为原地。
    private static final double STUCK_MIN_MOVE_SQR = 1.0 * 1.0;
    // (2026-09-30) 浅水站底判据：卡住窗口内 Y 波幅低于此值 = 站稳水底（非 bobbing）。
    // bobbing 实测波幅 1-2 格（0912 现场 y7↔9 每 0.5s 振荡）；站底波幅 ~0
    // （20260930-142545 现场 y=86.0 恒定 44s）。
    private static final double SHALLOW_STAND_Y_SPREAD = 0.5;
    // 空气充足阈值（剩余空气百分比），低于则不触发下潜以免溺水。
    private static final double MIN_AIR_FRACTION = 0.5;
    // 导航（movement）任务包名前缀。
    private static final String MOVEMENT_PACKAGE = "adris.altoclef.tasks.movement.";
    // (2026-09-16 静默修复) 挖掘任务包名前缀。DestroyBlockTask 是挖掘本体，注释
    // 一直宣称"导航/挖掘类主任务"，但旧实现只认 movement 包 → 挖矿泡水时恒被
    // TASK_NOT_MOVEMENT 拦下（20260915-231536 场次整场 0 条 SWIM-DIVE 实锤）。
    private static final String MINING_PACKAGE = "adris.altoclef.tasks.construction.";

    // ==== (2026-09-24 持权仲裁) dive/place 激活期临时接管 ====
    // 根因 (20260924-1856 会话实锤): dive 曾用 mod.getInputControls().hold(...) 走
    // 原版 KeyBinding 层, 但 baritone 寻路期间玩家 input 被换成 PlayerMovementInput,
    // 其 tick() 只读 InputOverrideHandler.isInputForcedDown (反编译字节码实锤,
    // 无原版回退) → hold/release 全被旁路, dive 打了日志零位移, releaseJump 也
    // 压不住 baritone 强制的 JUMP=true (bobbing 持续)。且 alto 全部逻辑跑在
    // MinecraftClient.tick HEAD, baritone TickEvent.IN 在玩家 tick 前才写强制表,
    // 同 tick 必被覆盖 → 唯一活路是持权让主任务让位、baritone 停止写表。
    // 数值: 高于 Food 55 / 普通任务 50 / 睡眠路由 58, 低于死亡恢复 70 /
    // 溺水逃生 95 / 战斗 95 (威胁场景仍可随时抢走我们)。
    private static final float DIVE_CONTROL_PRIORITY = 60f;

    // ==== (2026-10-09 卡水面 10 分 40 秒复盘) 垫档空转升级阶梯 ====
    // 现场 20261009-223240 实锤: bot 卡水面 (106.7,61.6,211.2) 10 分 40 秒零位移。
    // 机制 = 水底离脚 1 格, probe 恒走"脚下已有实心但 onGround=false" → WAIT 永远
    // (水里不可能 onGround), 垫档 150 tick 窗口 placed=0 超时 → endPlace 却【不置
    // _skipPlaceUntilTick】→ 下一窗口仍选垫档 → PLACE_WAIT_PLAYER_BOX 循环,
    // 下潜档/上岸档永远轮不到 (anomaly 停滞 367s/409s 无守卫能破环)。
    // 修复三件套:
    //   ① 零放置窗口收场 ⇒ 让位下潜 (补上 PLACE_NO_SPOT 同款 handoff) + 计数;
    //   ② WAIT 连续 3s 无一块放置 ⇒ 提前判废 (不等 150 tick 拖满);
    //   ③ 连续 {@link #PLACE_FAIL_ESCALATE_WINDOWS} 个空转窗口 ⇒ 就近上岸
    //      (heightmap 环扫 + DryGroundHelper 真干陆地判据, 强制 前进+跳+疾跑),
    //      30s 硬上限出口; 扫不到岸 ⇒ 回落 triggerDive 直游。
    /** 连续几个零放置垫档窗口后升级为"就近上岸"。 */
    private static final int PLACE_FAIL_ESCALATE_WINDOWS = 3;
    /** WAIT(玩家碰撞箱占格/悬空擦底) 连续多久无放置即提前判废窗口 (3s ≈ 3 个 bob 周期)。 */
    private static final long PLACE_WAIT_STALE_TICKS = 60;
    /** 上岸模式硬上限 (30s): 拿着真岸坐标也游不出去 = 有东西挡死, 交回阶梯。 */
    private static final long SHORE_ESCAPE_MAX_TICKS = 600;
    /** 上岸期间重扫岸向间隔 (玩家在游, 最近岸可能换边)。 */
    private static final long SHORE_RESCAN_INTERVAL_TICKS = 40;
    /** 岸点环扫半径档位 (过河场景岸通常 6~20 格)。 */
    private static final int[] SHORE_SCAN_DISTANCES = {4, 8, 12, 16, 24, 32};
    /** 岸点环扫方位数 (16 向 ≈ 22.5° 分辨率, 足够定向)。 */
    private static final int SHORE_SCAN_BEARINGS = 16;

    // 卡住检测窗口起点（游戏 tick + 起点位置）。
    private long _windowStartTick = -1;
    private Vec3d _windowStartPos = null;
    // (2026-09-30) 卡住窗口内的 Y 包络：区分"站稳水底"（波幅~0）与"bobbing"（波幅 1-2 格）。
    private double _windowMinY = Double.MAX_VALUE;
    private double _windowMaxY = -Double.MAX_VALUE;
    // 下潜状态。
    private boolean _diving = false;
    private int _diveTicksLeft = 0;
    private long _lastDiveTick = Long.MIN_VALUE;
    // 下潜起点位置 + 锁定朝向 (直游方向 = 触发瞬间的视线方向)。
    private Vec3d _diveStartPos = null;
    private float _diveYaw = 0f;
    private float _divePitch = 0f;

    // 垫方块状态 (2026-09-12)：进行中标志 + 本轮窗口起点。
    // 注意 _standGraceUntilTick 故意【不】随 resetState 清零 —— 站稳宽限要
    // 跨过"离水→重置窗口"的常规重置路径存续 6s, 到期自然失效。
    private boolean _placing = false;
    private long _placeStartTick = Long.MIN_VALUE;
    private long _standGraceUntilTick = Long.MIN_VALUE;
    // 最近一次垫方块成功的 tick（站稳归属判定）+ 垫档让位截止 tick
    // （SUPPORTED 但非本反应垫的 → 垫档帮不上, 冷却期内直接走下潜档,
    //   防止"垫档空转、下潜档永远轮不到"的阶梯死结）。均不随 resetState 清零。
    private long _lastPlacedTick = Long.MIN_VALUE;
    private long _skipPlaceUntilTick = Long.MIN_VALUE;
    // 本轮垫方块统计 (诊断): 已成功垫几块 + 最近一次交互结果。
    private int _placedCount = 0;
    private String _lastPlaceResult = "none";

    // ==== (2026-10-09) 垫档空转计数 + 就近上岸状态 ====
    // _failedPlaceWindows 故意【不】随 resetState 清零 (同 _standGraceUntilTick):
    // "这片水垫档帮不上"的记忆要跨过接管/归还周期存续;
    // 清零只在两处: 垫档真正放上块 (PLACE_PLACED) / 上岸成功 (SHORE_REACHED)。
    private int _failedPlaceWindows = 0;
    /** 本垫档窗口内连续 WAIT 的起点 tick (MIN_VALUE = 不在 WAIT)。 */
    private long _placeWaitSinceTick = Long.MIN_VALUE;
    /** 上岸模式激活中 (优先级接管同 dive/place)。 */
    private boolean _escaping = false;
    private long _escapeStartTick = Long.MIN_VALUE;
    /** 当前认定的最近岸点 (站立空气格); null = 环扫没找到, 直游兜底。 */
    private BlockPos _escapeShore = null;
    private long _escapeLastRescanTick = Long.MIN_VALUE;

    // ==== [SWIM-DIVE-DIAG] 2026-09-10 零行为改动诊断 ====
    // 现场：水中卡住全靠 NAV-STUCK/超时兜底，整场日志 0 条 [SWIM-DIVE]。
    // 怀疑点：(a) 原地浮动 (water-bob) 时 isTouchingWater 逐 tick 闪烁为
    // false（11:52:02/11:52:04 两帧实证）→ 条件(1) resetState 反复清零
    // 60 tick 连续窗口，永远攒不满；(b) 空气 <50% 闸门误拦。本日志定位。
    private long _lastInWaterTick = Long.MIN_VALUE;
    private long _lastDiagTick = Long.MIN_VALUE;
    private String _lastDiagReason = null;

    // ==== (2026-09-27) 挖掘进度豁免 (用户口径: "必须进度在推进") ====
    // 现场 20260927-171238 实锤 (误触发 → 直接致死):
    //   17:16:53.953 WINDOW_START (pos=-45,37,-99) → 恰好 60 tick 后
    //   17:16:56.953 [SWIM-DIVE] 抢权。窗口内 bot 一直在挖:
    //   OccluderClear -44,40,-99、Stuck-break #2/#3 holding left click on
    //   -44,37,-99(water)/-44,39,-99(stone); 且 NAV-STUCK 显示
    //   baritone[customGoal=true pathing=true] path[present=true segLen=16
    //   dest=(-29,29,-99)] —— "有路线 + 正在挖 + XZ 零位移"被读成"游泳卡住"。
    //   XZ 零位移是挖矿的固有属性 (挖掘不产生位移), 60 tick 窗口必然攒满。
    //   伤害: dive 用 SNEAK 强制下沉, 两次各把 bot 往下扎 5 格多 ——
    //   17:17:00.052 y36.6 → 17:17:03.052 DIVE_END pos={-43,31,-99} air 1.00→0.81;
    //   17:17:12.051 y37.3 → 17:17:15.052 pos={-42,32,-99} air 再掉 20%。
    //   两跳合计烧掉约 40% 空气, 正是后面 17:17:39 溺水死亡的空气预算来源。
    //
    // 判据 (用户定: 不是"正在挖"就算豁免, 而是"进度在推进"才豁免):
    //   同一个目标方块, 本 tick 的 currentBreakingProgress 严格大于上一采样
    //   → 视为"正在有效推进", 清零卡住窗口, 本 tick 绝不触发下潜/垫方块。
    // 为什么不能只看 isBreakingBlock(): 泡水悬空挖掘有 5x 水 × 5x 悬空 = 25x
    //   惩罚, "按住左键但进度恒 0" 恰恰是真卡住, 那时最需要下潜/垫方块兜底
    //   (DestroyBlockTask 的 WATER-EXIT 与本站原始设计意图都依赖它)。
    // 进度来源: ClientBlockBreakMixin → ClientPlayerInteractionManager
    //   .currentBreakingProgress (0..1 累计), 每 tick updateBlockBreakingProgress
    //   发布; 换目标方块 / 取消挖掘 (BlockBreakingCancelEvent) 时归零。
    // 与 JumpStuckResolver 的 [DigGuard] 同性质 (那里是"只要在挖就豁免"),
    //   本站按用户口径收紧为"进度在推进", 不复制其宽判据。
    private BlockPos _lastBreakPos = null;
    private double _lastBreakProgress = -1.0;

    public SwimDiveChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTick(AltoClef mod) {
        // 本反应在 getPriority（20Hz 仲裁）里逐 tick 执行，onTick 空实现。
    }

    @Override
    public float getPriority(AltoClef mod) {
        try {
            tickReaction(mod);
        } catch (Throwable t) {
            // 后台反应绝不影响主逻辑：异常只记日志 + 松开可能按住的键。
            Debug.logWarning("SwimDive chain exception (ignored): " + t);
            t.printStackTrace();
            try {
                releaseAllInjected(mod);
            } catch (Exception ignored) { }
            releaseDive(mod);
            releaseEscape(mod);
            resetState();
        }
        // (2026-09-24 持权仲裁) dive/place/escape 激活期临时接管：主任务让位 → baritone
        // 寻路停写强制表 → 本链 setInputForceState 的输入才不会被同 tick 覆盖。
        // 其余时刻恒负无穷，永不与任何链争权（原设计语义保留）。
        if (_diving || _placing || _escaping) {
            return DIVE_CONTROL_PRIORITY;
        }
        return Float.NEGATIVE_INFINITY;
    }

    private void tickReaction(AltoClef mod) {
        if (!AltoClef.inGame() || mod.getPlayer() == null) {
            resetState();
            return;
        }

        // 屏幕开着（容器/合成/聊天/死亡）不触发。
        if (MinecraftClient.getInstance().currentScreen != null) {
            releaseDive(mod);
            releaseEscape(mod);
            WaterStandHelper.releaseJump(mod); // riseTick 可能 hold 着跳跃键
            resetState();
            return;
        }

        // (2026-10-09) 上岸模式: 每 tick 朝最近岸游 (见 triggerShoreEscape 注释)。
        if (_escaping) {
            tickEscape(mod);
            return;
        }

        // 正在垫方块：每 tick 瞄准+尝试放置（不走下潜检测）。
        if (_placing) {
            tickPlace(mod);
            return;
        }

        // 正在下潜：保持按住，数到下潜时长结束松开并重置窗口。
        if (_diving) {
            // (2026-09-24 持权版) 每 tick 维持强制态 + 取消寻路：持权后主任务
            // 不再 tick, 但 baritone 残留的 path 请求仍会在其 IN 段 dispatch
            // 写输入, 每 tick forceCancel 压死, 我们的强制态才稳赢玩家 tick。
            // 原 InputControls 版 (原版 KeyBinding) 被 PlayerMovementInput 完全
            // 旁路 —— 这正是 dive 打日志零位移的根因。
            try { mod.getClientBaritone().getPathingBehavior().forceCancel(); } catch (Throwable ignored) {}
            var h = mod.getClientBaritone().getInputOverrideHandler();
            h.setInputForceState(Input.MOVE_FORWARD, true);
            h.setInputForceState(Input.SNEAK, true);
            h.setInputForceState(Input.JUMP, false);
            // 锁定触发瞬间的朝向 (直游方向), 不让 baritone LookBehavior 残留目标
            // 把准星甩走 (甩走=前进方向乱, 直游失效)。
            // (2026-10-05) immediate: 直游锁向时间敏感, 不进转向平滑层。
            LookHelper.lookAtImmediate(mod,
                    new baritone.api.utils.Rotation(_diveYaw, _divePitch));
            if (--_diveTicksLeft <= 0) {
                releaseDive(mod);
                resetWindow();
            }
            // 下潜期间无条件持续注入输入（返回，不往下走检测）。
            return;
        }

        long nowTick = WorldHelper.getTicks();
        Vec3d pos = mod.getPlayer().getPos();

        // [SWIM-DIVE-DIAG] 记录最近一次在水中的 tick，用于识别"浮动瞬间脱离水"。
        boolean inWater = mod.getPlayer().isTouchingWater();
        if (inWater) {
            _lastInWaterTick = nowTick;
        }

        // 触发条件 (1)：不在水中 => 无需下潜，重置。
        if (!inWater) {
            // (2026-09-10 浮动闪烁宽限，两档) 原地浮动时 isTouchingWater 闪 false：
            // 小幅水波每 0.5-1s 一次(13:52:29-41 场次实证)，大幅上下浮动脱水可
            // 达 1-2s(20-40t)——单纯时间宽限不够。判据改为【时长+着地状态】：
            // 浮动=脱水但悬空(onGround=false)；真离开水=爬上岸即着地。
            // 脱水 ≤60t 且悬空 → 保留窗口暂停累积；着地或超时 → 真重置。
            if (_windowStartTick >= 0 && _windowStartPos != null
                    && _lastInWaterTick != Long.MIN_VALUE
                    && nowTick - _lastInWaterTick <= 60
                    && !mod.getPlayer().isOnGround()) {
                diagGate(mod, "NOT_IN_WATER_BOB_AIRBORNE");
                return;
            }
            // 只在"刚离开水"(≤60 tick) 时记录——正常陆地行走不刷屏。
            if (_lastInWaterTick != Long.MIN_VALUE && nowTick - _lastInWaterTick <= 60) {
                diagGate(mod, "NOT_IN_WATER");
            }
            resetState();
            return;
        }
        // 触发条件 (1b)：在船上 => 绝不注入（会下船破坏划船），重置。
        if (mod.getPlayer().getVehicle() instanceof BoatEntity) {
            diagGate(mod, "IN_BOAT");
            resetState();
            return;
        }
        // 触发条件 (2)：空气不足 => 交给 handleDrowning 上浮，不下潜，重置。
        if (mod.getPlayer().getMaxAir() > 0
                && (double) mod.getPlayer().getAir() / mod.getPlayer().getMaxAir() < MIN_AIR_FRACTION) {
            diagGate(mod, "AIR_LOW");
            resetState();
            return;
        }
        // 触发条件 (3)：只对导航/挖掘类主任务生效。
        if (!isNavigating(mod)) {
            diagGate(mod, "TASK_NOT_MOVEMENT");
            resetState();
            return;
        }
        // 战斗不触发（别在打斗里贸然注入潜行移动）。
        if (mod.getMobDefenseChain().isInCombat()) {
            diagGate(mod, "IN_COMBAT");
            resetState();
            return;
        }

        // (2026-09-27) 挖掘进度豁免 —— 必须在窗口累积与触发选择【之前】返回。
        // 挖矿时 XZ 天然零位移, 不豁免则 60 tick 窗口必攒满 → 误判游泳卡住。
        if (miningProgressAdvancing(mod)) {
            resetWindow();
            diagGate(mod, "MINING_PROGRESS");
            return;
        }

        // 初始化/重置后的窗口起点。
        if (_windowStartTick < 0 || _windowStartPos == null) {
            _windowStartTick = nowTick;
            _windowStartPos = pos;
            _windowMinY = pos.y;
            _windowMaxY = pos.y;
            diagGate(mod, "WINDOW_START");
            return;
        }
        // (2026-09-30) 每 tick 更新窗口 Y 包络（浅水站底判据的数据源）。
        _windowMinY = Math.min(_windowMinY, pos.y);
        _windowMaxY = Math.max(_windowMaxY, pos.y);

        // 窗口内水平位移 >= 1 格 => 在移动，没卡住，重置窗口。
        double dx = pos.x - _windowStartPos.x;
        double dz = pos.z - _windowStartPos.z;
        if (dx * dx + dz * dz >= STUCK_MIN_MOVE_SQR) {
            diagGate(mod, "WINDOW_RESET_MOVED");
            resetWindow();
            return;
        }

        // 连续卡满阈值 tick => 触发下潜（冷却未到则记录拦截原因）。
        // (2026-09-10 定案) 旧写法 (nowTick - _lastDiveTick) >= COOLDOWN 在
        // _lastDiveTick=Long.MIN_VALUE 哨兵时 long 减法下溢为负 → "冷却中"
        // 恒成立 → 首次下潜永不触发（现场：COOLDOWN_BLOCKING 从窗口攒满起
        // 连刷 34s，全程 0 条 SWIM-DIVE）。哨兵值必须显式豁免。
        long stuckForTicks = nowTick - _windowStartTick;
        if (stuckForTicks < SWIM_STUCK_WINDOW_TICKS) {
            // [SWIM-DIVE-DIAG] 独立 40 tick 节流，观察窗口累积进度。
            if (nowTick - _lastDiagTick >= 40) {
                _lastDiagTick = nowTick;
                writeDiag(mod, "ACCUMULATING streak=" + stuckForTicks
                        + "/" + SWIM_STUCK_WINDOW_TICKS);
            }
        } else if (mod.getPlayer().isOnGround()
                && (_windowMaxY - _windowMinY) < SHALLOW_STAND_Y_SPREAD) {
            // (2026-09-30 B' 浅水站底不接管) onGround+水中+整窗 Y 稳定 = bot 站在
            // 浅水（如农田灌溉渠）底部，不是本反应针对的"bobbing/原地划水"：
            //   垫档必然空转（目标格被 bot 自己占住 → PLACE_WAIT_PLAYER_BOX 循环，
            //   20260930-142545 实锤 3 轮 PLACE_TIMEOUT placed=0 last=JUMPED）；
            //   下潜档=原地走 3s 无增益。而每次接管→归还的代价依旧：BeatMinecraft2
            //   回卷（SESSION START）+ baritone 从零重规划（3s 窗口内路径算不完，
            //   录制帧 pathLen=0），44s 净位移 0，bot 永远走不出 1 格深水渠。
            //   不接管，交给导航与 NAV-STUCK/JumpStuck 等既有看门狗；resetWindow
            //   让本探针每 3s 复查（bot 离水/真 bobbing 时自然恢复接管能力）。
            resetWindow();
            diagGate(mod, "SHALLOW_STANDING");
        } else if (nowTick < _standGraceUntilTick) {
            // (2026-09-12) 刚垫方块站稳：给导航 6s 自己走起来, 期间不注入。
            diagGate(mod, "STAND_GRACE");
        } else if (_lastDiveTick != Long.MIN_VALUE
                && (nowTick - _lastDiveTick) < RE_DIVE_COOLDOWN_TICKS) {
            diagGate(mod, "COOLDOWN_BLOCKING");
        } else if (WaterStandHelper.probe(mod, mod.getPlayer()).status
                == WaterStandHelper.Status.NONE) {
            // (2026-09-30 B 闸门) 深水无立足点 → 两档接管都无收益, 不接管:
            //   垫档 probe 不到 spot 必败 (PLACE_NO_SPOT); 下潜档 3s 强制前游与
            //   baritone 自身导航重复。而每次接管→归还的代价远超收益: 归还时
            //   SingleTaskChain.onTick 无条件 _mainTask.reset() → onStart 重跑 →
            //   BeatMinecraft2Task 整个通关流程回卷到 (start) (20260929-225212
            //   场次 23:24:26/23:24:44 两次实证: [SWIM-DIVE] → 归还 → SESSION
            //   START → portal 子链从零重建, 73s 净位移 5 格; baritone 算出的
            //   下钻路径 dest=(44,29,122) 活了不到 2s 就被下一轮接管掐断)。
            //   空气不足在条件(2)已 reset 走不到这里, 无需重复判空气。
            //   已知取舍: 20260922-224740 型"深水柱贴壁"场景失去 dive 兜底,
            //   交给 NAV-STUCK/JumpStuck 等既有看门狗; resetWindow 让本探针每
            //   3s 复查一次, bob 到有立足点处自然恢复垫档能力。
            resetWindow();
            diagGate(mod, "DEEP_WATER_NO_FOOTING");
        } else if (!PillarJumpHelper.hasMaterials(mod, PillarJumpHelper.allMaterials())) {
            // 无建材 → 旧路径：直接下潜。
            triggerDive(mod, stuckForTicks);
        } else if (nowTick < _skipPlaceUntilTick) {
            // 垫档刚让位（站在浅水底垫不上）→ 冷却期内走下潜。
            triggerDive(mod, stuckForTicks);
        } else {
            // 有建材 → 优先垫方块止浮；窗口内失败则回退下潜（下一窗口）。
            startPlaceUnderfoot(mod, stuckForTicks);
        }
    }

    // ==== [SWIM-DIVE-DIAG] 诊断出口：换原因即记录，同原因最多 60 tick 一条 ====
    private void diagGate(AltoClef mod, String reason) {
        long nowTick = WorldHelper.getTicks();
        if (reason.equals(_lastDiagReason) && nowTick - _lastDiagTick < 60) return;
        _lastDiagTick = nowTick;
        _lastDiagReason = reason;
        writeDiag(mod, reason);
    }

    private void writeDiag(AltoClef mod, String tag) {
        try {
            var p = mod.getPlayer();
            if (p == null) return;
            double airFrac = p.getMaxAir() > 0 ? (double) p.getAir() / p.getMaxAir() : 1.0;
            Task main = mod.getUserTaskChain().getCurrentTask();
            String m = "[SWIM-DIVE-DIAG] gate=" + tag
                    + " inWater=" + p.isTouchingWater()
                    + String.format(" air=%.2f", airFrac)
                    + " pos=" + p.getBlockPos()
                    + " task=" + (main == null ? "null" : main.toString());
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Throwable ignored) {
            // 诊断绝不影响主逻辑
        }
    }

    /**
     * (2026-09-12) 开始垫方块止浮：进入 place 状态, 后续由 tickPlace 每 tick
     * 推进（探测→瞄准→interactBlock）。写 [SWIM-DIVE] 日志, 格式与下潜一致。
     */
    private void startPlaceUnderfoot(AltoClef mod, long stuckForTicks) {
        _placing = true;
        _placeStartTick = WorldHelper.getTicks();
        _placedCount = 0;
        _lastPlaceResult = "none";
        // (2026-09-24 持权版) 清 baritone 残留强制态 (JUMP=true 是 bob 元凶):
        // 清空后五移动键无强制 → inControl()=false → 玩家 input 换回原版
        // KeyboardInput → releaseJump/riseTick 的原版层输入恢复有效。
        try { mod.getClientBaritone().getInputOverrideHandler().clearAllKeys(); } catch (Throwable ignored) {}
        Vec3d pos = mod.getPlayer().getPos();
        Task main = mod.getUserTaskChain().getCurrentTask();
        String m = "[SWIM-DIVE] stuck in water -> place block underfoot (take control prio="
                + (int) DIVE_CONTROL_PRIORITY + "). player=" + pos
                + " task=" + (main == null ? "null" : main.toString())
                + " stuckFor=" + stuckForTicks + " window=" + PLACE_WINDOW_TICKS;
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    /**
     * 垫方块单 tick 推进 (09-12 返工: 连续垫多块填满水柱, 站上柱顶后垫出水面)。
     * 结束路径统一 endPlace + 重开卡住窗口 —— 失败时下潜兜底在下一个窗口
     * 自然接管, 无需在此直接触发。
     */
    private void tickPlace(AltoClef mod) {
        long nowTick = WorldHelper.getTicks();
        if (mod.getPlayer() == null) {
            resetState();
            return;
        }
        // (2026-09-24 持权版) 每 tick 压死 baritone 寻路: 残留 path 请求 dispatch
        // 会重写 JUMP=true (bob 元凶) 且把玩家 input 切走, 原版层 releaseJump/
        // riseTick 全失效。持权后主任务不再请求, forceCancel 保证彻底安静。
        try { mod.getClientBaritone().getPathingBehavior().forceCancel(); } catch (Throwable ignored) {}
        // 出水即收工 (柱子垫满后 bot 已站到水面之上)。
        // (2026-09-12 现场 100413) 必须要求 onGround —— bob 顶点那一瞬
        // inWater 也会闪 false, 旧判据 PLACE_OUT_OF_WATER placed=0 假出水
        // 骗退 (0.15s 内结束, 一块没放), 下一 tick 又落回水里。腾空时恰好
        // 是放置窗口 (目标格刚被腾出), 继续往下走 probe。
        if (!mod.getPlayer().isTouchingWater()) {
            if (mod.getPlayer().isOnGround()) {
                endWithGrace(mod, "PLACE_OUT_OF_WATER");
                return;
            }
            diagGate(mod, "PLACE_APEX_HOLD");
        }
        // (2026-09-12 用户点破) 每 tick 压住跳跃: baritone 导航在水里持续
        // hold JUMP = 向上游 = 水面 bobbing 的根源。不松开, 目标格永远被
        // 身体闪占闪空 (PLACE_WAIT_PLAYER_BOX), bob 永不停止。SUPPORTED 档
        // 例外 —— 下方 riseTick 需要跳跃 pillar-jump。
        WaterStandHelper.releaseJump(mod);
        // 超时: 带上已垫块数与最近交互结果, 方便定位"垫不出"的原因。
        // (2026-10-09) 零放置的超时 = 垫档帮不上 ⇒ 让位下潜 + 计数 (旧实现漏了
        // handoff, 下一窗口仍选垫档 → 1009 卡水面 10 分 40 秒的直接根源)。
        if (nowTick - _placeStartTick >= PLACE_WINDOW_TICKS) {
            if (_placedCount == 0) {
                registerPlaceWindowFailure(mod, "PLACE_TIMEOUT placed=0 last=" + _lastPlaceResult);
            } else {
                writeDiag(mod, "PLACE_TIMEOUT placed=" + _placedCount + " last=" + _lastPlaceResult);
                endPlace(mod);
            }
            return;
        }
        WaterStandHelper.Spot spot = WaterStandHelper.probe(mod, mod.getPlayer());
        if (spot.status == WaterStandHelper.Status.SUPPORTED) {
            // (2026-09-30 哨兵下溢修复) _lastPlacedTick=MIN_VALUE 时 long 减法下溢
            // 为负 → "刚垫过"恒成立 → 永远走 riseTick 而非让位 —— 与 2026-09-10
            // _lastDiveTick 同款 bug（见触发处注释），当时漏改了本处。20260930-
            // 142545 实锤：本会话 0 次 PLACE_PLACED 却 3 轮窗口 last=JUMPED 空转。
            if (_lastPlacedTick == Long.MIN_VALUE
                    || nowTick - _lastPlacedTick > PLACE_STAND_MEM_TICKS) {
                // 没垫过东西也"站稳" = 本来就站在浅水底, 垫方块帮不上
                // → 垫档让位, 冷却期内走下潜档（防阶梯死结）
                _skipPlaceUntilTick = nowTick + RE_DIVE_COOLDOWN_TICKS;
                writeDiag(mod, "PLACE_SUPPORTED_NO_PLACEMENT");
                endPlace(mod);
                return;
            }
            // 站在本反应垫的柱顶但还在水里 → pillar-jump 向上垫出水面
            // (用户要求多放几块)。JUMPED/STALLED 下 tick 继续, 直到出水。
            WaterStandHelper.RiseResult rr = WaterStandHelper.riseTick(mod);
            _lastPlaceResult = rr.toString();
            if (rr == WaterStandHelper.RiseResult.PLACED) {
                _lastPlacedTick = nowTick;
                _placedCount++;
                writeDiag(mod, "PLACE_PLACED #" + _placedCount + " (rise)");
            }
            return;
        }
        if (spot.status == WaterStandHelper.Status.NONE) {
            // 深水够不着 / 水草挡路 / 放置禁区 → 本轮放弃, 回退下潜兜底。
            // (2026-09-22 修下潜档饿死) 现场 20260922-224740: probe 在深水柱对
            // 同一位置无论 bob 相位恒 NONE (apex 与水中两 tick 实锤), 旧代码只
            // endPlace 不写 _skipPlaceUntilTick → 每 3s 新窗口 hasMaterials=true
            // 仍选垫档, 同一 tick 立刻 NONE, dive+sprint 档 45s 零触发。必须像
            // SUPPORTED 让位一样置冷却, 下一窗口才会走 triggerDive (向前下游离
            // 开深水柱); 游到新位置后冷却到期自然会再试垫档。
            _skipPlaceUntilTick = nowTick + RE_DIVE_COOLDOWN_TICKS;
            writeDiag(mod, "PLACE_NO_SPOT placed=" + _placedCount + " -> dive next window");
            endPlace(mod);
            return;
        }
        if (spot.status == WaterStandHelper.Status.WAIT) {
            // bob 下浮半程占住目标格, 等上浮再放（窗口内重试, 节流记录）。
            // (2026-10-09) 门槛降级: 连续 {@link #PLACE_WAIT_STALE_TICKS} 没等来
            // 一次放置机会 (典型 = 水底贴脚、水里永不 onGround 的"假 bob") ⇒
            // 提前判废窗口, 不拖满 150 tick。
            if (_placeWaitSinceTick == Long.MIN_VALUE) _placeWaitSinceTick = nowTick;
            if (_placedCount == 0
                    && nowTick - _placeWaitSinceTick >= PLACE_WAIT_STALE_TICKS) {
                registerPlaceWindowFailure(mod, "PLACE_WAIT_STALE");
                return;
            }
            diagGate(mod, "PLACE_WAIT_PLAYER_BOX");
            return;
        }
        // 非 WAIT: WAIT 计时清零 (放置机会回来了 / 状态翻面)。
        _placeWaitSinceTick = Long.MIN_VALUE;
        // FOUND: 装备 → 准星甩向垫面 → force-click 放置
        ActionResult r = WaterStandHelper.placeTick(mod, spot.floor);
        _lastPlaceResult = r.toString();
        if (r == ActionResult.SUCCESS) {
            _lastPlacedTick = nowTick;
            _placedCount++;
            // (2026-10-09) 垫档真的在产出 ⇒ 空转计数清零。
            _failedPlaceWindows = 0;
            // 不结束: bot 被抬高一格后 bob 上浮会腾出下一格, 继续垫满水柱
            writeDiag(mod, "PLACE_PLACED #" + _placedCount);
        }
    }

    /** 垫升收工 (出水/站稳): 宽限 6s 让导航走起来, 再结束本轮。 */
    private void endWithGrace(AltoClef mod, String tag) {
        _standGraceUntilTick = WorldHelper.getTicks() + STAND_GRACE_TICKS;
        // (2026-10-09) 出水/站稳 = 垫档路线仍然有效, 空转计数清零。
        _failedPlaceWindows = 0;
        writeDiag(mod, tag + " placed=" + _placedCount);
        endPlace(mod);
    }

    /**
     * (2026-10-09) 一次【零放置】垫档窗口的统一收场:
     * 置垫档冷却 (下一窗口走下潜, 补 PLACE_NO_SPOT 同款 handoff) + 空转计数;
     * 连续 {@link #PLACE_FAIL_ESCALATE_WINDOWS} 个空转窗口 ⇒ 升级"就近上岸"。
     */
    private void registerPlaceWindowFailure(AltoClef mod, String tag) {
        long nowTick = WorldHelper.getTicks();
        _failedPlaceWindows++;
        _skipPlaceUntilTick = nowTick + RE_DIVE_COOLDOWN_TICKS;
        writeDiag(mod, tag + " failStreak=" + _failedPlaceWindows
                + "/" + PLACE_FAIL_ESCALATE_WINDOWS);
        if (_failedPlaceWindows >= PLACE_FAIL_ESCALATE_WINDOWS) {
            triggerShoreEscape(mod);
            return; // 内部已置 _escaping, 不再回垫档状态机
        }
        endPlace(mod);
    }

    // ==================================================================== //
    // (2026-10-09) 就近上岸模式
    // ==================================================================== //

    /**
     * 升级"就近上岸": 环扫最近"真干陆地" ({@link DryGroundHelper#isSolidDryGround}),
     * 持权强制 前进+跳+疾跑 朝岸游 (JUMP=水面抬头+爬 1 格岸沿), 每
     * {@link #SHORE_RESCAN_INTERVAL_TICKS} 重扫换向, 出水站稳即收工;
     * 硬上限 {@link #SHORE_ESCAPE_MAX_TICKS}。环扫不到岸 (大湖中心) ⇒ 回落
     * triggerDive 直游 (游着游着岸可能进入扫描半径, 下轮空转再升级)。
     */
    private void triggerShoreEscape(AltoClef mod) {
        _placing = false;
        _placeStartTick = Long.MIN_VALUE;
        _placeWaitSinceTick = Long.MIN_VALUE;
        WaterStandHelper.releaseJump(mod);
        // 清 baritone 残留强制态 (同 triggerDive, JUMP=true 是 bob 元凶)。
        try {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
        } catch (Throwable ignored) {}
        rescanShore(mod);
        _escaping = true;
        _escapeStartTick = WorldHelper.getTicks();
        _escapeLastRescanTick = WorldHelper.getTicks();
        Task main = mod.getUserTaskChain().getCurrentTask();
        String m = "[SWIM-DIVE] stuck in water -> swim to nearest shore (take control prio="
                + (int) DIVE_CONTROL_PRIORITY + "). player=" + mod.getPlayer().getPos()
                + " shore=" + (_escapeShore != null ? _escapeShore.toShortString() : "none(直游兜底)")
                + " failStreak=" + _failedPlaceWindows
                + " task=" + (main == null ? "null" : main.toString());
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
        if (_escapeShore == null) {
            // 扫不到岸: 本模式没意义 (只会原地按跳), 立即回落直游下潜档。
            endEscape(mod, "ESCAPE_NO_SHORE");
            triggerDive(mod, PLACE_FAIL_ESCALATE_WINDOWS);
        }
    }

    /** 上岸单 tick: 压死 baritone → 出水判定 → 超时出口 → 重扫换向 → 注入 前进+跳+疾跑。 */
    private void tickEscape(AltoClef mod) {
        long nowTick = WorldHelper.getTicks();
        if (mod.getPlayer() == null) {
            resetState();
            return;
        }
        try { mod.getClientBaritone().getPathingBehavior().forceCancel(); } catch (Throwable ignored) {}
        // 出水即收工 (口径同垫档: 必须 onGround, bob 顶点假出水不算)。
        if (!mod.getPlayer().isTouchingWater()) {
            if (mod.getPlayer().isOnGround()) {
                endEscape(mod, "SHORE_REACHED");
                return;
            }
            diagGate(mod, "ESCAPE_APEX_HOLD");
        }
        // 硬上限出口: 拿着真岸坐标也游不出去 = 有东西挡死, 交回既有阶梯
        // (failStreak 保留在阈值上, 垫档下次空转立即再升级, 不重走 3 窗口)。
        if (nowTick - _escapeStartTick >= SHORE_ESCAPE_MAX_TICKS) {
            endEscape(mod, "ESCAPE_TIMEOUT");
            return;
        }
        // 定期重扫: 玩家在游, 最近岸可能换边。
        if (nowTick - _escapeLastRescanTick >= SHORE_RESCAN_INTERVAL_TICKS) {
            rescanShore(mod);
            _escapeLastRescanTick = nowTick;
        }
        // 注入: 前进 + 跳 (抬头游/爬岸), 不按潜行; 疾跑走原版层 (同 triggerDive)。
        var h = mod.getClientBaritone().getInputOverrideHandler();
        h.setInputForceState(Input.MOVE_FORWARD, true);
        h.setInputForceState(Input.JUMP, true);
        h.setInputForceState(Input.SNEAK, false);
        mod.getInputControls().hold(Input.SPRINT);
        // 朝岸看 (锁向同 dive, immediate 不进平滑层, 防准星被残留目标甩走)。
        if (_escapeShore != null) {
            LookHelper.lookAtImmediate(mod, Vec3d.ofCenter(_escapeShore));
        }
    }

    /** 环扫最近岸点 (站立空气格过 DryGroundHelper 真干陆地判据); 没找到 ⇒ null。 */
    private void rescanShore(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dist : SHORE_SCAN_DISTANCES) {
            for (int i = 0; i < SHORE_SCAN_BEARINGS; i++) {
                double ang = i * (Math.PI / SHORE_SCAN_BEARINGS);
                int x = feet.getX() + (int) Math.round(Math.cos(ang) * dist);
                int z = feet.getZ() + (int) Math.round(Math.sin(ang) * dist);
                BlockPos stand = new BlockPos(x, surfaceYAt(mod, x, z, feet.getY()), z);
                if (!mod.getChunkTracker().isChunkLoaded(stand)) continue; // 未知 → 不选
                double d = stand.getSquaredDistance(feet);
                if (d >= bestDist) continue;
                if (DryGroundHelper.isSolidDryGround(mod, stand)) {
                    best = stand.toImmutable();
                    bestDist = d;
                }
            }
        }
        _escapeShore = best;
    }

    /** 列顶 Y (MOTION_BLOCKING_NO_LEAVES, 空壳区块回退 MOTION_BLOCKING; 同 BiasedExploreTask 口径)。 */
    private static int surfaceYAt(AltoClef mod, int x, int z, int fallbackY) {
        int bottomPlus1 = mod.getWorld().getBottomY() + 1;
        int y = mod.getWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y > bottomPlus1) return y;
        y = mod.getWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
        return y > bottomPlus1 ? y : fallbackY;
    }

    /** 上岸收场: 成功 ⇒ 站稳宽限 + 空转计数清零; 其余 tag ⇒ 只退模式交回阶梯。 */
    private void endEscape(AltoClef mod, String tag) {
        if (tag.equals("SHORE_REACHED")) {
            _standGraceUntilTick = WorldHelper.getTicks() + STAND_GRACE_TICKS;
            _failedPlaceWindows = 0;
        }
        writeDiag(mod, tag + " shore=" + (_escapeShore != null ? _escapeShore.toShortString() : "none"));
        releaseEscape(mod);
        resetWindow();
    }

    /** 退出上岸模式: 清全部注入 (强制态 + 原版 SPRINT/JUMP) 与状态。幂等。 */
    private void releaseEscape(AltoClef mod) {
        if (!_escaping) return;
        _escaping = false;
        _escapeStartTick = Long.MIN_VALUE;
        _escapeShore = null;
        _escapeLastRescanTick = Long.MIN_VALUE;
        releaseAllInjected(mod);
        WaterStandHelper.releaseJump(mod);
    }

    private void endPlace(AltoClef mod) {
        _placing = false;
        _placeStartTick = Long.MIN_VALUE;
        // riseTick 可能 hold 着跳跃键, 退出路径必须松开。
        WaterStandHelper.releaseJump(mod);
        // (2026-09-24 持权版) 归还控制权前清本反应全部注入 (幂等, place 档
        // 本就没设强制态, 防御 dive/place 交叠残留)。
        releaseAllInjected(mod);
        resetWindow();
    }

    /**
     * 触发下潜：面向当前前进方向，按住 FORWARD + SNEAK(下沉) + SPRINT(加速)
     * DIVE_HOLD_TICKS tick，向前下游，解除水面/岩壁原地划水。写 [SWIM-DIVE]
     * 日志：玩家 pos、当前主任务、卡住时长、下潜持续时长。
     *
     * (2026-09-24 持权版) 输入注入从原版 KeyBinding 层 (InputControls.hold,
     * 被 baritone 寻路期的 PlayerMovementInput 完全旁路) 改为 baritone 强制表
     * setInputForceState + 持权仲裁 (getPriority 返回 60 让主任务让位) +
     * 每 tick forceCancel。原版层只留 SPRINT (PlayerMovementInput 不读 sprint,
     * vanilla ClientPlayerEntity 仍读原版 sprintKey)。
     */
    private void triggerDive(AltoClef mod, long stuckForTicks) {
        Vec3d pos = mod.getPlayer().getPos();
        // 锁定触发瞬间朝向 = 直游方向 (后续每 tick updateTarget 维持)。
        _diveYaw = mod.getPlayer().getYaw();
        _divePitch = mod.getPlayer().getPitch();
        _diveStartPos = pos;
        // 清掉 baritone 残留强制态 (含 JUMP=true —— bobbing 元凶), 再写我们的。
        try {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
        } catch (Throwable ignored) {}
        var h = mod.getClientBaritone().getInputOverrideHandler();
        h.setInputForceState(Input.MOVE_FORWARD, true);
        h.setInputForceState(Input.SNEAK, true);
        h.setInputForceState(Input.JUMP, false);
        // SPRINT 走原版层即可 (PlayerMovementInput 只管六个移动输入)。
        mod.getInputControls().hold(Input.SPRINT);
        _diving = true;
        _diveTicksLeft = DIVE_HOLD_TICKS;
        _lastDiveTick = WorldHelper.getTicks();

        Task main = mod.getUserTaskChain().getCurrentTask();
        String m = "[SWIM-DIVE] stuck in water -> dive+sprint forward (take control prio="
                + (int) DIVE_CONTROL_PRIORITY + "). player=" + pos
                + " task=" + (main == null ? "null" : main.toString())
                + " stuckFor=" + stuckForTicks + " ticks dive=" + DIVE_HOLD_TICKS;
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    /**
     * (2026-09-24) 统一清掉本反应注入的 baritone 强制态。FORWARD/SNEAK 归 false
     * 后 inControl() 失效 → 玩家 input 换回原版 KeyboardInput → InputControls 层
     * 恢复有效。残留 JUMP=false 对 inControl 无影响且会被后续写入覆盖, 也一并清。
     */
    private void releaseAllInjected(AltoClef mod) {
        try {
            var h = mod.getClientBaritone().getInputOverrideHandler();
            h.setInputForceState(Input.MOVE_FORWARD, false);
            h.setInputForceState(Input.SNEAK, false);
            h.setInputForceState(Input.JUMP, false);
        } catch (Throwable ignored) {
        }
        mod.getInputControls().release(Input.SPRINT);
    }

    private void releaseDive(AltoClef mod) {
        // (2026-09-24) 结束时报告本次直游位移 —— 下次日志可直接验证 dive 是否
        // 真的在游 (旧版恒 ~0 位移, 正是输入被旁路的实锤)。
        if (_diveStartPos != null && mod.getPlayer() != null) {
            Vec3d now = mod.getPlayer().getPos();
            double dx = now.x - _diveStartPos.x;
            double dz = now.z - _diveStartPos.z;
            double moved = Math.sqrt(dx * dx + dz * dz);
            writeDiag(mod, String.format("DIVE_END moved=%.2f dx=%.2f dz=%.2f", moved, dx, dz));
        }
        _diveStartPos = null;
        releaseAllInjected(mod);
        _diving = false;
        _diveTicksLeft = 0;
    }

    /**
     * 当前主任务是否为导航类：沿当前执行链向上/下检查，命中 movement/挖掘包内任务
     * 即视为处于导航/挖掘场景（游泳/涉水多半伴随 GetToBlock/探索等 movement 任务，
     * 挖矿伴随 DestroyBlockTask 等 construction 任务）。
     *
     * (2026-09-16 静默修复) 两处口径修正：
     *  ① 判定源优先用 {@link Task#getLastTickedTask()} —— getPriority 在仲裁期被调,
     *     当时 getUserTaskChain().getCurrentTask() 拿到的是链顶层任务（20260915-231536
     *     现场：Destroy 在 tick，diag 里 task 却显示 "Smelting raw food: porkchop"），
     *     而 _lastTicked 在 tick 结束后留下的正是真叶子。
     *  ② 补 construction 包（DestroyBlockTask 所在），与注释"导航/挖掘类"对齐。
     */
    private boolean isNavigating(AltoClef mod) {
        Task leaf = Task.getLastTickedTask();
        if (leaf != null && leaf.thisOrChildSatisfies(SwimDiveChain::isNavOrMiningTask)) {
            return true;
        }
        if (!mod.getUserTaskChain().isActive()) return false;
        Task main = mod.getUserTaskChain().getCurrentTask();
        if (main == null) return false;
        return main.thisOrChildSatisfies(SwimDiveChain::isNavOrMiningTask);
    }

    /**
     * (2026-09-27) "有持续挖掘进度" 判据 (见字段区 2026-09-27 注释)。
     *
     * 返回 true 的三个必要条件（缺一不可）:
     *   ① 正在破坏方块 (isBreakingBlock，来自 ClientBlockBreakMixin 的事件);
     *   ② 破坏目标与上一采样是同一个方块;
     *   ③ 本 tick 的 currentBreakingProgress 严格大于上一采样值。
     *
     * 即"同一目标、进度在推进"。仅"按住左键"不算 —— 泡水悬空 25x 惩罚下进度
     * 恒 0 恰好是真卡住，那种情况必须保留原有的下潜/垫方块兜底。
     * 不碰世界/不回调 baritone（只读控制器的两个字段），无性能与递归风险。
     */
    private boolean miningProgressAdvancing(AltoClef mod) {
        var extras = mod.getControllerExtras();
        if (!extras.isBreakingBlock()) {
            // 没在挖：清采样，下次进入挖掘时重新起算（避免拿旧进度比出新"推进"）。
            _lastBreakPos = null;
            _lastBreakProgress = -1.0;
            return false;
        }
        BlockPos pos = extras.getBreakingBlockPos();
        double progress = extras.getBreakingBlockProgress();
        boolean advancing = _lastBreakPos != null
                && _lastBreakPos.equals(pos)
                && progress > _lastBreakProgress;
        _lastBreakPos = pos;
        _lastBreakProgress = progress;
        return advancing;
    }

    private static boolean isNavOrMiningTask(Task t) {
        String n = t.getClass().getName();
        return n.startsWith(MOVEMENT_PACKAGE) || n.startsWith(MINING_PACKAGE);
    }

    private void resetWindow() {
        _windowStartTick = -1;
        _windowStartPos = null;
    }

    private void resetState() {
        resetWindow();
        _diving = false;
        _diveTicksLeft = 0;
        _placing = false;
        _placeStartTick = Long.MIN_VALUE;
        _placeWaitSinceTick = Long.MIN_VALUE;
        // _escaping 的注入清理走 releaseEscape (需 mod); 此处只对齐状态位,
        // 防止 resetState 后残留 _escaping=true 却不再有人 tick 它。
        _escaping = false;
        _escapeStartTick = Long.MIN_VALUE;
        _escapeShore = null;
        _escapeLastRescanTick = Long.MIN_VALUE;
    }

    @Override
    protected void onStop(AltoClef mod) {
        // 保险：链停止时松开可能正按住的键 (含 baritone 强制态)。
        releaseDive(mod);
        releaseEscape(mod);
        WaterStandHelper.releaseJump(mod);
        resetState();
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        // 让位 (含被战斗/溺水等更高优先级抢走): 必须清强制态, 否则我们写的
        // FORWARD=true 会在 map 里残留, bot 落地后一直往前游。
        releaseDive(mod);
        releaseEscape(mod);
        WaterStandHelper.releaseJump(mod);
        resetState();
    }

    @Override
    public boolean isActive() {
        // 始终保持激活，使 getPriority() 每 tick 被仲裁调用（= 逐 tick 反应）。
        return true;
    }

    @Override
    public String getName() {
        return "Swim Dive (stuck-in-water reaction)";
    }
}