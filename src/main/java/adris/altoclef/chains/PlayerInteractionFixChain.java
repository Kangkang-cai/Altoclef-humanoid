package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasks.slot.ThrowCursorTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public class PlayerInteractionFixChain extends TaskChain {

    private final TimerGame _stackHeldTimeout = new TimerGame(8);
    private final TimerGame _generalDuctTapeSwapTimeout = new TimerGame(30);
    private final TimerGame _shiftDepressTimeout = new TimerGame(10);
    private final TimerGame _betterToolTimer = new TimerGame(0.5);
    private final TimerGame _mouseMovingButScreenOpenTimeout = new TimerGame(0.2);
    private ItemStack _lastHandStack = null;

    private Screen _lastScreen;
    private Rotation _lastLookRotation;

    // ==== Stuck-breaker（卡住撸方块，2026-08-21）====
    // 荷叶/藤蔓/树干等挡路时 bot 会呆住——这些方块不在 Baritone 的
    // "可破坏障碍"意识里，任务层也看不到它们。行为级修复（模拟人类）：
    // bot 想移动但水平位置 ~1.5 秒不变 → 朝面前方块（脚部，其次眼部）
    // 看过去，长按左键 2 秒把它撸掉。和人类"瞄准方向然后长按"一致，
    // 不依赖任务层的精确方块定位。
    private final TimerGame _stuckDetectTimer = new TimerGame(1.5);
    /**
     * (2026-09-27 判据修正) 卡住窗口锚点 —— "是否在动"改看**窗口内水平净位移**，
     * 不再看瞬时位移。详见 tick 内注释。
     */
    private Vec3d _stuckAnchorPos = null;
    /**
     * (2026-09-27) 窗口内水平净位移 ≥ 此值即认定"真的在走"（格）。与
     * SwingThroughChain 的 `STUCK_MIN_MOVE` 同口径 —— 两条卡住反应链必须用
     * 同一把尺子，否则一条判"卡住"、另一条判"在动"，行为互相矛盾。
     */
    private static final double STUCK_MIN_MOVE = 1.0;
    private boolean _breakingForward = false;
    private int _breakForwardTicks = 0;
    private BlockPos _breakForwardTarget = null;
    private static final int BREAK_FORWARD_HOLD_TICKS = 40; // 2 秒 @ 20tps
    // (2026-08-27 开门) 卡住时前方是【木门】→ 长按的是右键开门, 不是左键砸。
    // 门的类型多(云杉/深色云杉/橡木/丛林/金合欢/红树/绯红/诡异…)但都能右键
    // 开, 区别对待: true=右键开门, false=左键破坏。
    private static final int DOOR_OPEN_HOLD_TICKS = 2; // 0.1 秒, 一下点开即可 (2026-08-27 由 6 缩短, 开门更利索)
    private boolean _breakingForwardIsDoor = false;
    private int _stuckBreakCount = 0;

    public PlayerInteractionFixChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onStop(AltoClef mod) {
        // 保险：链停止时松开我们可能按住的左键/右键。
        if (_breakingForward) {
            mod.getInputControls().release(_breakingForwardIsDoor ? Input.CLICK_RIGHT : Input.CLICK_LEFT);
            _breakingForward = false;
            _breakingForwardIsDoor = false;
        }
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {

    }

    @Override
    protected void onTick(AltoClef mod) {
    }

    @Override
    public float getPriority(AltoClef mod) {

        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        if (mod.getUserTaskChain().isActive() && _betterToolTimer.elapsed()) {
            _betterToolTimer.reset();
            // (2026-09-16 工具打架修复, 082639 场次) 任务层 (MineAndCollectTask
            // [ToolEquip]) 最近 2s 内做过装备决策时本分支让位: 两个 0.5s 装备源
            // 按不同方块(实际在撸的工作台=木头 vs 任务 miningPos=石头)互相覆盖,
            // 手在斧/镐间每 0.5s横跳、挖掘进度清零。任务层装备唯一说话。
            boolean taskToolEquipDefers = MineAndCollectTask.taskToolEquipRecently();
            if (!taskToolEquipDefers && mod.getControllerExtras().isBreakingBlock()) {
                BlockState state = mod.getWorld().getBlockState(mod.getControllerExtras().getBreakingBlockPos());
                Optional<Slot> bestToolSlot = StorageHelper.getBestToolSlot(mod, state);
                Slot currentEquipped = PlayerSlot.getEquipSlot();

                // (2026-09-16 同口径补充) 手上工具已适合当前方块就不换 — 与
                // MineAndCollectTask L616 一致, 速度微优不值得付出换手代价。
                if (bestToolSlot.isPresent()
                        && StorageHelper.getItemStackInSlot(currentEquipped).getItem().isSuitableFor(state)) {
                    bestToolSlot = Optional.empty();
                }

                // if baritone is running, only accept tools OUTSIDE OF HOTBAR!
                // Baritone will take care of tools inside the hotbar.
                // (2026-09-24 热栏豁免移除) 上述假设实测不成立: 20260924-202519
                // 镐在热栏, baritone 寻路挖掘 (GoToSurfaceTask 爬升) 3.5 分钟
                // hand=water_bucket 不换工具 → 手速挖石头。且本分支只在"当前手持
                // 不能采收正在挖的方块"时触发 (上方已过滤 current suitable), 装备
                // 后 current 变 suitable 自动停手, 不与 baritone 的放块选槽打架;
                // 任务层装备优先权由 taskToolEquipDefers 保持 (2026-09-16)。
                if (bestToolSlot.isPresent() && !bestToolSlot.get().equals(currentEquipped)) {
                    // ONLY equip if the item class is STRICTLY different (otherwise we swap around a lot)
                    if (StorageHelper.getItemStackInSlot(currentEquipped).getItem() != StorageHelper.getItemStackInSlot(bestToolSlot.get()).getItem()) {
                        Debug.logMessage("Found better tool in inventory, equipping.");
                        mod.getSlotHandler().forceEquipSlot(bestToolSlot.get());
                    }
                }
            }
        }

        // Unpress shift (it gets stuck for some reason???)
        if (mod.getInputControls().isHeldDown(Input.SNEAK)) {
            if (_shiftDepressTimeout.elapsed()) {
                mod.getInputControls().release(Input.SNEAK);
            }
        } else {
            _shiftDepressTimeout.reset();
        }

        // ==== Sprint-jump (bunny hop) REMOVED (2026-08-21) ====
        // 自研边跑边跳实测效果差（该跳不跳/不该跳乱跳），按用户要求直接
        // 删除。Baritone 原生寻路自带的跳跃不受影响。

        // ==== Stuck-breaker：卡住时瞄准前方长按左键撸掉挡路方块 ====
        boolean baritonePathing = mod.getClientBaritone().getPathingBehavior().isPathing();
        boolean tryingToMove = baritonePathing
                || (mod.getPlayer().input != null
                    && Math.abs(mod.getPlayer().input.movementForward) + Math.abs(mod.getPlayer().input.movementSideways) > 0.1f);
        boolean screenClosed = MinecraftClient.getInstance().currentScreen == null;
        if (_breakingForward) {
            // 长按（右键开门 或 左键撸前方）中：保持瞄准，倒计时结束（或
            // 界面弹出/目标没了）松键。
            boolean targetGone = _breakForwardTarget == null
                    || WorldHelper.isAir(mod, _breakForwardTarget);
            if (--_breakForwardTicks <= 0 || !screenClosed || targetGone) {
                mod.getInputControls().release(_breakingForwardIsDoor ? Input.CLICK_RIGHT : Input.CLICK_LEFT);
                _breakingForward = false;
                _breakingForwardIsDoor = false;
                _stuckDetectTimer.reset(); // 给 bot 时间恢复移动再重新计卡
            } else {
                LookHelper.lookAt(mod, _breakForwardTarget);
            }
        } else if (screenClosed && tryingToMove && !mod.getControllerExtras().isBreakingBlock()) {
            // (2026-09-27 判据修正: "瞬时位移" → "窗口净位移")
            //
            // 旧判据 `_lastPos.squaredDistanceTo(pos) < 0.0025`（≈0.05 格 / **tick**）
            // 要求每一 tick 都几乎纹丝不动。而"被方块顶在格子边缘"时玩家恰恰会以
            // **±0.19 格幅度来回抖动** —— record 20260927-145243 t=10557..10855
            // 实锤: bot 站在杜鹃花丛顶面, 位置在 -46.40 ↔ -46.59 之间周期摆动,
            // `hSpeed` 恒定 0.064 而整段 15s 的水平净位移只有 0.08~0.12 格。
            // ⇒ 旧判据每 tick 都判"在动" ⇒ `_stuckDetectTimer` 每 tick 被 reset
            // ⇒ **永远凑不满 1.5s** ⇒ 本反应在最该生效的场景（花丛/栅栏楔住）下
            // 从不触发 —— 8 条历史 [Stuck-break] 全部来自真正静止的门/围栏场景。
            // 同族教训见 detail/03 "瞬时判据横跳 / latch 缺失"。
            //
            // 新判据: 锚点固定, 只看**水平净位移**（与 SwingThroughChain 同口径）——
            // 来回抖动会互相抵消, 真移动不会。y 刻意不计: 原地垫塔/上浮不归本反应。
            Vec3d pos = mod.getPlayer().getPos();
            if (_stuckAnchorPos == null) {
                _stuckAnchorPos = pos;
                // TimerGame 新建即到期, 新窗口必须显式 reset 才能重新计满 1.5s。
                _stuckDetectTimer.reset();
            } else if (Math.hypot(pos.x - _stuckAnchorPos.x, pos.z - _stuckAnchorPos.z)
                    >= STUCK_MIN_MOVE) {
                // 真的在走 → 重锚 + 重新计时。
                _stuckAnchorPos = pos;
                _stuckDetectTimer.reset();
            }

            // (2026-09-27 避让) 左键已被别的反应按住时让位。
            // `InputControls.hold/release` 直接 `KeyBinding.setPressed`, **没有引用
            // 计数** —— 而 SwingThroughChain（25 tick 窗口, 触发得比本链的 1.5s 更早）
            // 会 hold 左键持续挖 60 tick。两条反应链若同时上, 会各自 lookAt 不同
            // 目标, 且一方 release 会把**另一方正按着的键松开**（挖掘进度归零）
            // ⇒ 修好判据反而互相抵消。只读查询, 不改任何既有状态。
            if (!mod.getInputControls().isHeldDown(Input.CLICK_LEFT)
                    && _stuckDetectTimer.elapsed()) {
                BlockPos front = getFrontBlockingBlock(mod);
                if (front != null) {
                    Block fblock = mod.getWorld().getBlockState(front).getBlock();
                    // (2026-08-27 开门, 用户)
                    // 挡路的是【木门】(云杉/深色云杉/橡木等全部类型) → 右键
                    // 打开, 不是左键硬砸。木门也属于"可挖", 但硬砸要好几下、
                    // 还会一直卡门反复横跳 — 人类是直接开门走进去。
                    // 用硬度区分: 1.18 木门 hardness=3.0, 铁门(徒手开不了)=5.0。
                    boolean openableDoor = fblock instanceof DoorBlock
                            && fblock.getDefaultState().getHardness(mod.getWorld(), front) < 5.0f;
                    if (openableDoor) {
                        _stuckBreakCount++;
                        String m = "[Stuck-door] #" + _stuckBreakCount + " opening "
                                + front.toShortString() + " (" + fblock.getTranslationKey() + ")";
                        Debug.logMessage(m);
                        Debug.logToFile("altoclef_diag.log", m);
                        LookHelper.lookAt(mod, front);
                        _breakingForwardIsDoor = true;
                        mod.getInputControls().hold(Input.CLICK_RIGHT);
                        _breakingForward = true;
                        _breakForwardTicks = DOOR_OPEN_HOLD_TICKS;
                        _breakForwardTarget = front;
                    } else if (WorldHelper.canBreak(mod, front)) {
                        _stuckBreakCount++;
                        String m = "[Stuck-break] #" + _stuckBreakCount + " holding left click on "
                                + front.toShortString() + " ("
                                + fblock.getTranslationKey() + ")";
                        Debug.logMessage(m);
                        Debug.logToFile("altoclef_diag.log", m);
                        LookHelper.lookAt(mod, front);
                        _breakingForwardIsDoor = false;
                        mod.getInputControls().hold(Input.CLICK_LEFT);
                        _breakingForward = true;
                        _breakForwardTicks = BREAK_FORWARD_HOLD_TICKS;
                        _breakForwardTarget = front;
                    } else {
                        // 前方没有可撸的实体方块：不触发，等下一轮卡住计时。
                        _stuckDetectTimer.reset();
                    }
                } else {
                    _stuckDetectTimer.reset();
                }
            }
        } else {
            _stuckAnchorPos = null;
            _stuckDetectTimer.reset();
        }

        // Refresh inventory
        if (_generalDuctTapeSwapTimeout.elapsed()) {
            // [2026-08-22] 屏幕开着(容器/合成操作进行中)时禁止全背包双击刷新:
            // refreshInventory 对每个槽 PICKUP 两次, 会打乱刚放进 3x3 合成格的
            // 配方和光标上的物品, 与合成任务的点击序列互相打架 (合成卡死诱因)。
            // [2026-09-15] 丢弃流程(ThrowCursorTask)持有光标期间同样禁止: 双击刷新
            // 会把瞄准坑准备丢出的垃圾洗回背包槽 (09:25:36/:47 实锤), 丢弃永完不成。
            if (!mod.getControllerExtras().isBreakingBlock() && MinecraftClient.getInstance().currentScreen == null
                    && !ThrowCursorTask.isDiscardInProgress()) {
                Debug.logMessage("Refreshed inventory...");
                mod.getSlotHandler().refreshInventory();
                _generalDuctTapeSwapTimeout.reset();
                return Float.NEGATIVE_INFINITY;
            }
        }

        ItemStack currentStack = StorageHelper.getItemStackInCursorSlot();

        if (currentStack != null && !currentStack.isEmpty()) {
            //noinspection PointlessNullCheck
            if (_lastHandStack == null || !ItemStack.areEqual(currentStack, _lastHandStack)) {
                // We're holding a new item in our stack!
                _stackHeldTimeout.reset();
                _lastHandStack = currentStack.copy();
            }
        } else {
            _stackHeldTimeout.reset();
            _lastHandStack = null;
        }

        // If we have something in our hand for a period of time...
        if (_lastHandStack != null && _stackHeldTimeout.elapsed()) {
            // (2026-09-15) 丢垃圾挖坑/隧道期间豁免: 空手深板岩 ~7.5s/格 vs 8s 超时 →
            // 挖到一半光标被收回 → 重捡 → 重挖进度清零, 每 8s 一轮死循环
            // (2026-09-15 08:48-08:49 latest.log 实锤, 54s 循环到手动停)。
            // 挖掘进行中只重置计时; ThrowCursorTask 自带 25s(隧道)/30s(挖一格)
            // 超时兜底照丢, 不会因此永久占住光标。
            if (ThrowCursorTask.isDiggingForThrow()) {
                _stackHeldTimeout.reset();
            } else {
            Debug.logMessage("Cursor stack is held for too long, will move back to inventory.");
            // (2026-09-01 同 LootContainerTask 根因) 删 `.or(getGarbageSlot)`: 背包满时
            // 点垃圾槽只交换不扔 → 光标杂物腾不掉 → 走下方"扔光标/保底随机丢"真正腾位。
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(_lastHandStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
            } else {
                // Try throwing away cursor slot if it's garbage
                if (ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                } else {
                    // (2026-08-23 满背包卡死修复) 光标物品不能扔 (是要留的) 且
                    // 背包满无垃圾 → 旧逻辑只打日志 "We're stuck" 永久卡死 (用户:
                    // "东西捡不上又不会丢东西所以卡住不动")。用保底: 随机丢一个
                    // 非关键物品腾位, 让光标物品能放回背包。
                    Slot toDrop = StorageHelper.getRandomThrowableSlot(mod);
                    if (toDrop != null) {
                        ItemStack rs = StorageHelper.getItemStackInSlot(toDrop);
                        Debug.logMessage("(PlayerInteractionFix) Cursor stuck with full inventory — random tossing "
                                + rs.getCount() + "x " + rs.getItem().getTranslationKey() + " to make room.");
                        mod.getSlotHandler().clickSlot(toDrop, 0, SlotActionType.PICKUP);
                    } else {
                        Debug.logMessage("Cursor stack edge case: Full inventory AND NO GARBAGE! We're stuck.");
                    }
                }
            }
            return Float.NEGATIVE_INFINITY;
            } // (2026-09-15) else: 挖坑豁免分支只重置计时, 不收回光标。
        }

        if (shouldCloseOpenScreen(mod)) {
            Debug.logMessage("Closed screen since we changed our look.");
            StorageHelper.closeScreen();
            return Float.NEGATIVE_INFINITY;
        }

        return Float.NEGATIVE_INFINITY;
    }

    /**
     * 玩家面前挡路的方块：按当前朝向（baritone 移动时会把视角对准移动
     * 方向）取脚部前方，若是空气再取眼部前方。荷叶在脚部高度、藤蔓/
     * 树干在眼部高度。都为空返回 null。
     */
    private BlockPos getFrontBlockingBlock(AltoClef mod) {
        float yaw = mod.getPlayer().getYaw();
        // MC yaw: 0=南(+Z), 90=西(-X)；前向向量 = (-sin(yaw), 0, cos(yaw))
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

    private boolean shouldCloseOpenScreen(AltoClef mod) {
        if (!mod.getModSettings().shouldCloseScreenWhenLookingOrMining())
            return false;
        // Only check look if we've had the same screen open for a while
        Screen openScreen = MinecraftClient.getInstance().currentScreen;
        if (openScreen != _lastScreen) {
            _mouseMovingButScreenOpenTimeout.reset();
        }
        // We're in the player screen/a screen we DON'T want to cancel out of
        if (openScreen == null || openScreen instanceof ChatScreen || openScreen instanceof GameMenuScreen || openScreen instanceof DeathScreen) {
            _mouseMovingButScreenOpenTimeout.reset();
            return false;
        }
        // [2026-08-22 合成界面被误关修复] 原逻辑: 界面打开 0.2 秒后视角只要
        // 转 0.1° 就关屏。合成任务操作工作台/背包期间 LookHelper 完全可能
        // 转头(对准容器/移动残留视角), 界面被反复关闭重开, 合成永远完不成。
        // 收紧触发条件: 只在 Baritone 正在寻路(真的要移动)时才认为界面是
        // 移动前忘关的残留物, 值得自动关闭。
        if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _lastScreen = openScreen;
            return false;
        }
        // Check for rotation change
        Rotation look = LookHelper.getLookRotation();
        if (_lastLookRotation != null && _mouseMovingButScreenOpenTimeout.elapsed()) {
            Rotation delta = look.subtract(_lastLookRotation);
            if (Math.abs(delta.getYaw()) > 0.1f || Math.abs(delta.getPitch()) > 0.1f) {
                _lastLookRotation = look;
                return true;
            }
            // do NOT update our last look rotation, just because we want to measure long term rotation.
        } else {
            _lastLookRotation = look;
        }
        _lastScreen = openScreen;
        return false;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getName() {
        return "Hand Stack Fix Chain";
    }
}
