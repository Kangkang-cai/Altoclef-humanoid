package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.MineDownExploreTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

/**
 * 卡住挥击反应 (SwingThrough Chain, 2026-08-31)。
 *
 * 挖掘/导航任务"没进度（卡住）"时，像人类一样长按几下左键，清掉 Baritone
 * 探测不到/微小却挡路的方块（草、藤蔓、草丛、装饰类等）。这些方块不在
 * Baritone 的"可破坏障碍"意识里、任务层也看不到，bot 会原地呆住。
 *
 * 设计要点：
 *  - 是【后台反应】，不是任务：只在本机输入层调用 hold/release 左键，
 *    绝不 setTask()/subTask()，不改任务优先级、不参与任务树，仲裁层面
 *    getPriority() 恒定返回 NEGATIVE_INFINITY，永远不抢占。
 *  - 独立的卡住阈值（不复用 Stuck-breaker/PlayerInteractionFixChain 判定）：
 *    自定义 = 当前主任务属于挖掘类(MineAndCollect/MineDownExploreTask/
 *    Destroy 路线)或导航类(movement)，且连续 N 个游戏 tick（默认 25 ≈1.2s）
 *    内既没有方块被破坏、且玩家水平位移 < 1 格 → 判定卡住。
 *  - 触发动作 = 模拟左键长按 SWING_HOLD_TICKS(4) tick，击打前方挡路方块；
 *    自带冷却，避免每 tick 狂点（触发一次后过 RE_SWING_COOLDOWN 才再挥）。
 *  - 只加行为+日志，不改任何既有任务/优先级数值/状态机跳转。
 *  - 绝不调用 getPriority()（避开 setTask→getPriority→setTask 递归铁律）。
 *
 * (2026-09-19 栅栏楔住扩展) 现场 20260919-195218 seq 9943 实锤：bot 脚所在格
 * = jungle_fence（栅栏是细的一根，人挤在格子边缘，栅栏中心距 0.656 格），baritone
 * 按着 MOVE_FORWARD 整整 942 tick(47s) 零位移。旧实现救不到它，两条原因：
 *   ① 触发前置是"主任务是挖掘/导航类"，而该场主任务是建造类
 *      <Place structure{} at 777,78,126> → 直接 reset，反应永不触发；
 *   ② 挥击固定 4 tick（为草/藤蔓这类瞬破方块设计），对硬度 2.0 的栅栏无效。
 * 扩展（纯增，不改旧路径）：
 *   - 触发加一条 **"baritone 在强制移动输入 且 25 tick 内水平位移 < 1 格"** 的分支
 *     —— "在推但推不动"才是楔住的签名；合法静止不会强制移动键。
 *   - 挥击前先用 findThinBlocker 找"非满立方且有碰撞箱"的楔住物（栅栏/墙/栏杆），
 *     找到就盯住它**持续挖**（上限 60 tick）而不是盲挥 4 tick；找不到退回旧行为。
 *   - 目标合法性走 WorldHelper.canBreak（已含 shouldAvoidBreaking 全部保护谓词），
 *     围场 9x9 保护盒内的栅栏不会被本反应拆掉。
 *
 * (2026-09-27 两处致命修复) 现场 20260927-145243 record t=10557..10855:
 *   bot **站在杜鹃花丛顶面** (脚下一格 = azalea) 卡 14s —— 位置钉死在格子边界
 *   (-46.5,-28.5)±0.1、hSpeed 恒定 0.064、yaw 累计转 22 圈，任务链
 *   <ConstructNetherPortalBucketTask → PlaceObsidianBucketTask → DestroyBlockTask>
 *   最后超时切 TimeoutWanderTask。本链全程 **0 次触发**，两条原因都在这文件里:
 *   ① **首次触发整型溢出**（致命）: `_lastSwingTick` 初值 Long.MIN_VALUE，
 *      `nowTick - Long.MIN_VALUE` 溢出为负数 ⇒ 冷却判据恒 false ⇒ 首次永远
 *      进不来，而首次进不来 _lastSwingTick 就永远是 MIN_VALUE —— **自 2026-08-31
 *      建链至今从未触发过一次**（全历史 [SWING-BREAK] 计数 = 0，09-19 那次为它
 *      专门做的"栅栏楔住"扩展也因此从未生效）。修 = 显式判等初值。
 *   ② **阻挡判据漏掉花丛**: `findThinBlocker` 原本排除"满立方"
 *      (`Block.isShapeFullCube`)，而杜鹃花丛正是"满格碰撞 + nonOpaque" ⇒ 被
 *      跳过 ⇒ 只剩盲挥 4 tick，对花丛无效。修 = 改用与 baritone
 *      `MovementHelper.canWalkThrough` **同源**的 `isOpaqueFullCube` 排除真墙
 *      (判据同源铁律见 03-consistency.md)。
 */
public class SwingThroughChain extends TaskChain {

    // 卡住阈值：N 个游戏 tick（默认 25 ≈1.2s）
    private static final int STUCK_WINDOW_TICKS = 25;
    // 挥击（长按左键）持续 tick 数（3~5，取 4）
    private static final int SWING_HOLD_TICKS = 4;
    // 卡住后重挥冷却（tick）。触发一次后即使仍卡，过一段时间（默认 5s）
    // 才允许再次挥击，避免每 tick 狂点。
    private static final long RE_SWING_COOLDOWN_TICKS = 100;
    // 卡住判定：窗口内玩家水平位移 < 1 格即视为原地（没在导航）。
    private static final double STUCK_MIN_MOVE = 1.0;
    // 导航（movement）任务包名前缀。
    private static final String MOVEMENT_PACKAGE = "adris.altoclef.tasks.movement.";

    // 卡住检测窗口的起点（游戏 tick + 起点位置）。
    private long _windowStartTick = -1;
    private Vec3d _windowStartPos = null;
    // 挥击状态。
    private boolean _swinging = false;
    private int _swingTicksLeft = 0;
    private long _lastSwingTick = Long.MIN_VALUE;

    // (2026-09-19 栅栏楔住) 针对性持续挖掘状态：非 null 表示正在盯住某个细方块挖，
    // 它会一直按住左键直到该方块消失 / 超过 _breakDeadlineTick / 变成不可挖。
    private BlockPos _breakTarget = null;
    private long _breakDeadlineTick = Long.MIN_VALUE;
    // 持续挖掘上限 (tick)。栅栏硬度 2.0：空手 ~3s、石斧 ~0.5s；3s 兜底防死握。
    private static final int BREAK_TARGET_MAX_TICKS = 60;

    public SwingThroughChain(TaskRunner runner) {
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
            // 后台反应绝不影响主逻辑：异常只记日志 + 松开可能按住的左键。
            Debug.logWarning("SwingThrough chain exception (ignored): " + t);
            t.printStackTrace();
            try {
                mod.getInputControls().release(Input.CLICK_LEFT);
            } catch (Exception ignored) { }
            _swinging = false;
            _windowStartTick = -1;
            _windowStartPos = null;
        }
        // 恒定负无穷：永不抢占任何任务链。
        return Float.NEGATIVE_INFINITY;
    }

    private void tickReaction(AltoClef mod) {
        if (!AltoClef.inGame() || mod.getPlayer() == null) {
            resetState();
            return;
        }

        // 屏幕开着（容器/合成/聊天/死亡）不触发。
        if (MinecraftClient.getInstance().currentScreen != null) {
            resetState();
            return;
        }

        // 正在挥击：保持按住，数到挥击时长结束松开并重置窗口，给 bot 恢复时间。
        if (_swinging) {
            // (2026-09-19 栅栏楔住) 针对性持续挖掘：盯住楔住自己的细方块，直到它消失 /
            // 超时 / 不可挖。栅栏硬度 2.0，旧逻辑的固定 4 tick 盲挥对它完全无效，会
            // 无限"挥一下没挖掉→冷却 5s→再挥"空转。
            if (_breakTarget != null) {
                if (WorldHelper.isAir(mod, _breakTarget)
                        || WorldHelper.getTicks() >= _breakDeadlineTick
                        || !WorldHelper.canBreak(mod, _breakTarget)) {
                    releaseSwing(mod);
                    resetWindow();
                    return;
                }
                // 每 tick 重新对准：baritone 的 LookBehavior 也会写朝向，会被它抢走。
                LookHelper.lookAt(mod, _breakTarget);
                return;
            }
            if (--_swingTicksLeft <= 0) {
                releaseSwing(mod);
                resetWindow();
            }
            return;
        }

        long nowTick = WorldHelper.getTicks();
        Vec3d pos = mod.getPlayer().getPos();

        // 只对挖掘/导航类主任务生效 —— 或 (2026-09-19 栅栏楔住) baritone 正在强制
        // 某个移动输入。后者才是"被楔住"最直接的签名：bot 被自己那一格里的细方块
        // (栅栏/墙/栏杆) 顶在格子边缘，baritone 仍按着 MOVE_FORWARD 往前推 47 秒
        // 零位移 (20260919-195218 seq 9943 实锤：脚所在格 = jungle_fence，栅栏中心
        // 距 0.656 格)。当时主任务是建造类 <Place structure{} at 777,78,126>，
        // 旧判据 isMiningOrNavigating 直接 reset，反应永不触发。
        // 反向风险：合法静止 (站桩挖矿 / 开熔炉 / 吃东西) 不会强制移动键，故不会误触发。
        if (!isMiningOrNavigating(mod) && !isBaritonePushingMovement(mod)) {
            resetState();
            return;
        }
        // 打斗中不触发（别在战斗里乱挥左键）。
        if (mod.getMobDefenseChain().isInCombat()) {
            resetState();
            return;
        }
        // 正在破块 => 没卡住，重置窗口。
        if (mod.getControllerExtras().isBreakingBlock()) {
            resetWindow();
            return;
        }

        // 初始化/重置后的窗口起点。
        if (_windowStartTick < 0 || _windowStartPos == null) {
            _windowStartTick = nowTick;
            _windowStartPos = pos;
            return;
        }

        // 窗口内水平位移 >= 1 格 => 在移动，没卡住，重置窗口。
        double horizontalMove = Math.hypot(pos.x - _windowStartPos.x, pos.z - _windowStartPos.z);
        if (horizontalMove >= STUCK_MIN_MOVE) {
            resetWindow();
            return;
        }

        // 连续卡满阈值 tick，且距上次挥击已过冷却 => 触发挥击。
        long stuckForTicks = nowTick - _windowStartTick;
        // (2026-09-27 首次触发整型溢出, 致命) 原写法是直接
        //     (nowTick - _lastSwingTick) >= RE_SWING_COOLDOWN_TICKS
        // 而 _lastSwingTick 初值 = Long.MIN_VALUE ⇒ `nowTick - Long.MIN_VALUE` 在
        // long 上**溢出为负数** ⇒ 恒 < 100 ⇒ **首次永远进不来**; 而既然首次进不来,
        // _lastSwingTick 永远是 MIN_VALUE ⇒ 本链自 2026-08-31 建链至今**一次都没
        // 触发过** (全历史日志 [SWING-BREAK] 计数 = 0, 含 09-19 那次为它专门做的
        // "栅栏楔住"扩展也从未生效)。修 = 显式判等初值, 不让哨兵参与减法。
        boolean cooldownOk = _lastSwingTick == Long.MIN_VALUE
                || (nowTick - _lastSwingTick) >= RE_SWING_COOLDOWN_TICKS;
        if (stuckForTicks >= STUCK_WINDOW_TICKS && cooldownOk) {
            triggerSwing(mod, stuckForTicks);
        }
    }

    /**
     * 触发挥击：面向正前方挡路方块的自动可挖块（可选），然后长按左键
     * SWING_HOLD_TICKS tick，清掉草/藤蔓/草丛等探测不到的小方块。写
     * [SWING-BREAK] 日志：判卡住时的玩家 pos、当前主任务、卡住时长。
     */
    private void triggerSwing(AltoClef mod, long stuckForTicks) {
        Vec3d pos = mod.getPlayer().getPos();
        Task main = mod.getUserTaskChain().getCurrentTask();

        // (2026-09-19 栅栏楔住) 优先: 楔住 bot 的细方块 → 盯住它持续挖到消失。
        BlockPos blocker = findThinBlocker(mod);
        if (blocker != null) {
            LookHelper.lookAt(mod, blocker);
            _breakTarget = blocker;
            _breakDeadlineTick = WorldHelper.getTicks() + BREAK_TARGET_MAX_TICKS;
        } else {
            _breakTarget = null;
            BlockPos front = getFrontNonAirBlock(mod);
            if (front != null) {
                LookHelper.lookAt(mod, front);
            }
        }

        mod.getInputControls().hold(Input.CLICK_LEFT);
        _swinging = true;
        _swingTicksLeft = SWING_HOLD_TICKS;
        _lastSwingTick = WorldHelper.getTicks();

        String m = "[SWING-BREAK] stuck detected -> left-click swing. player=" + pos
                + " task=" + (main == null ? "null" : main.toString())
                + " stuckFor=" + stuckForTicks + " ticks hold=" + SWING_HOLD_TICKS
                + (blocker != null
                        ? " target=" + blocker.toShortString() + " (细方块 → 持续挖, 上限 " + BREAK_TARGET_MAX_TICKS + " tick)"
                        : " (盲挥, 未找到细方块)");
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    /**
     * (2026-09-19 栅栏楔住) baritone 是否正在强制某个移动输入 —— "在推"。
     * 与"25 tick 内水平位移 < 1 格"合起来才是"推不动"的完整签名。绝不调用
     * getPriority，只读 baritone 输入覆盖状态。
     */
    private boolean isBaritonePushingMovement(AltoClef mod) {
        try {
            var h = mod.getClientBaritone().getInputOverrideHandler();
            return h.isInputForcedDown(Input.MOVE_FORWARD)
                    || h.isInputForcedDown(Input.MOVE_BACK)
                    || h.isInputForcedDown(Input.MOVE_LEFT)
                    || h.isInputForcedDown(Input.MOVE_RIGHT)
                    || h.isInputForcedDown(Input.JUMP);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * (2026-09-19 栅栏楔住) 找出把 bot 楔在原地的"细方块"。
     *
     * 只看三个候选位 (bot 自己脚所在格 → 正前方同层 → 正前方上一层，与
     * getFrontNonAirBlock 同口径)，条件：
     *   非空气 ∧ 碰撞箱非空 ∧ **baritone 判"可穿过"** ∧ WorldHelper.canBreak。
     *
     * <b>(2026-09-27 判据改同源)</b> 原判据是"非满立方"(`Block.isShapeFullCube`)，
     * 它把**杜鹃花丛**这类"满格碰撞 + nonOpaque"的隐形体一起排除掉了 —— 而花丛
     * 恰恰是本链最常遇到、也最需要处置的那一类。现场 record 20260927-145243
     * t=10557..10855：bot 站在 azalea 顶面卡 14s (hSpeed 恒 0.064, yaw 转 22 圈)，
     * 本链全程 0 次触发，只能靠任务层超时切 wander。
     *
     * 新判据 = 排除 `isOpaqueFullCube` (= baritone 自己会挖的真墙)，与
     * `MovementHelper.canWalkThrough` **同源** (它就是用 `isOpaqueFullCube` 判"可穿")。
     * 留下的正好是 "baritone 认为可穿过、物理上却挡人"的隐性阻挡集合：
     *   杜鹃花丛 / 栅栏 / 墙 / 栏杆玻璃板 / 树叶 …
     * 石头/泥土等 opaque 满立方仍交给 baritone 自己挖 (它们的眼睛射线也打不进去)。
     * 判据同源的理由见 03-consistency.md 铁律: 造第二份口径必然与第一份漂移。
     *
     * 为什么用 WorldHelper.canBreak 当闸门：它内部已消费 shouldAvoidBreaking，即
     * 全局保护谓词 (围场 9x9 保护盒 / 门体 / 任务注册的临时保护) 全部生效 ——
     * 本反应绝不会去拆自己的围场，也不会新增一套判据口径。
     */
    private BlockPos findThinBlocker(AltoClef mod) {
        ClientPlayerEntity p = mod.getPlayer();
        if (p == null) return null;
        BlockPos feet = p.getBlockPos();
        float yaw = p.getYaw();
        int dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
        int dz = (int) Math.round(Math.cos(Math.toRadians(yaw)));
        if (dx == 0 && dz == 0) dz = 1;
        BlockPos[] candidates = { feet, feet.add(dx, 0, dz), feet.add(dx, 1, dz) };
        for (BlockPos c : candidates) {
            BlockState s = mod.getWorld().getBlockState(c);
            if (s.isAir()) continue;
            VoxelShape shape = s.getCollisionShape(mod.getWorld(), c);
            if (shape.isEmpty()) continue;
            if (s.isOpaqueFullCube(mod.getWorld(), c)) continue;
            if (!WorldHelper.canBreak(mod, c)) continue;
            return c.toImmutable();
        }
        return null;
    }

    /** 松开左键并清掉持续挖掘状态 (所有退出路径共用, 防死握)。 */
    private void releaseSwing(AltoClef mod) {
        // 只在确实由本反应按住时才松 —— 与旧行为一致, 避免误松别的链正按的左键。
        if (_swinging) {
            mod.getInputControls().release(Input.CLICK_LEFT);
        }
        _swinging = false;
        _swingTicksLeft = 0;
        _breakTarget = null;
        _breakDeadlineTick = Long.MIN_VALUE;
    }

    /**
     * 当前主任务是否为挖掘类/导航类：沿当前正在执行的子任务链向下检查，
     * 命中 MineAndCollectTask / MineDownExploreTask / DestroyBlockTask 或
     * movement 包内任务即视为处于挖掘或导航场景。
     */
    private boolean isMiningOrNavigating(AltoClef mod) {
        if (!mod.getUserTaskChain().isActive()) return false;
        Task main = mod.getUserTaskChain().getCurrentTask();
        if (main == null) return false;
        return main.thisOrChildSatisfies(t -> {
            if (t instanceof MineAndCollectTask
                    || t instanceof MineDownExploreTask
                    || t instanceof DestroyBlockTask) {
                return true;
            }
            return t.getClass().getName().startsWith(MOVEMENT_PACKAGE);
        });
    }

    /**
     * 玩家正前方第一个非空气方块（脚部优先，其次眼部）。复制自
     * PlayerInteractionFix 的取前方方法：荷叶在脚部、藤蔓/树干在眼部。
     */
    private BlockPos getFrontNonAirBlock(AltoClef mod) {
        float yaw = mod.getPlayer().getYaw();
        int dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
        int dz = (int) Math.round(Math.cos(Math.toRadians(yaw)));
        if (dx == 0 && dz == 0) dz = 1;
        BlockPos feet = mod.getPlayer().getBlockPos();
        BlockPos frontFeet = feet.add(dx, 0, dz);
        if (!WorldHelper.isAir(mod, frontFeet)) return frontFeet;
        BlockPos frontEye = feet.add(dx, 1, dz);
        if (!WorldHelper.isAir(mod, frontEye)) return frontEye;
        return null;
    }

    private void resetWindow() {
        _windowStartTick = -1;
        _windowStartPos = null;
    }

    private void resetState() {
        resetWindow();
        _swinging = false;
        _swingTicksLeft = 0;
        // (2026-09-19) 细方块目标必须一起清：只清 _swinging 会让下一轮触发继承
        // 上一个已挖掉/已超时的目标，造成"对着空气持续挖"。
        _breakTarget = null;
        _breakDeadlineTick = Long.MIN_VALUE;
    }

    @Override
    protected void onStop(AltoClef mod) {
        // 保险：链停止时松开可能正按住的左键。
        releaseSwing(mod);
        resetState();
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        // 后台反应没有可被打断的任务；若正处于挥击，安全松开左键。
        releaseSwing(mod);
        resetState();
    }

    @Override
    public boolean isActive() {
        // 始终保持激活，使 getPriority() 每 tick 被仲裁调用（= 逐 tick 反应）。
        return true;
    }

    @Override
    public String getName() {
        return "Swing Through (stuck reaction)";
    }
}