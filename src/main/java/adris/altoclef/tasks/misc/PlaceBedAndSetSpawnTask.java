package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.eventbus.events.GameOverlayEvent;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.resources.CollectBedTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import baritone.api.process.ICustomGoalProcess;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SleepingChatScreen;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.Optional;
import java.util.function.Function;

public class PlaceBedAndSetSpawnTask extends Task {

    private boolean _stayInBed;

    private static final Block[] BEDS = CollectBedTask.BEDS;

    private final TimerGame _regionScanTimer = new TimerGame(9);

    private final Vec3i BED_CLEAR_SIZE = new Vec3i(3, 2, 1);
    private final Vec3i[] BED_BOTTOM_PLATFORM = new Vec3i[]{
            new Vec3i(0, -1, 0),
            new Vec3i(1, -1, 0),
            new Vec3i(2, -1, 0),
    };
    // Kinda silly but who knows if we ever want to change it.
    private final Vec3i BED_PLACE_STAND_POS = new Vec3i(0, 0, 0);
    private final Vec3i BED_PLACE_POS = new Vec3i(1, 0, 0);
    private final Direction BED_PLACE_DIRECTION = Direction.UP;
    private final TimerGame _bedInteractTimeout = new TimerGame(12);
    private final TimerGame _inBedTimer = new TimerGame(1);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(4, true);
    private TimerGame _placeAttemptTimer; // (2026-09-01 放床 3s 超时换区域重扫, 见 Placing bed 段)
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(2);
    private BlockPos _currentBedRegion;
    private BlockPos _currentStructure, _currentBreak;
    private boolean _spawnSet;
    private Subscription<ChatMessageEvent> _respawnPointSetMessageCheck;
    private Subscription<GameOverlayEvent> _respawnFailureMessageCheck;
    private boolean _sleepAttemptMade;
    private boolean _wasSleeping;
    // (2026-08-26 用户修正: 必须右键点一下床才设重生点, 不能跳过) 是否已开始
    // 睡床尝试并启动了"设重生点超时": 进入"去睡床"分支时启动, 若 5 秒内既没
    // 出"Respawn point set"消息也没真正睡进去 (白天不能睡/右键没生效) → 超时
    // 收尾继续走, 不再死循环和床交互。独立于 overlay 消息, 更稳。
    private boolean _sleepTimeoutStarted = false;
    private BlockPos _bedForSpawnPoint;
    // (2026-08-27 刚放的床优先睡) 记录【本次刚放到世界的床位置】。放床成功瞬间
    // 只有 bot 自己知道这张床在哪 (BlockTracker 缓存的 chunk 扫描会滞后数秒,
    // 期间 getNearestTracking 可能选到 50 格外的村庄床, 用户实测: 放下床却跑
    // 去村庄床睡觉)。睡眠分支先睡刚放的床, 失效了再回退到最近床搜索。
    private BlockPos _justPlacedBed;
    // ==== [BedDebug] temporary diagnostic (to find why bed isn't slept in) ====
    private boolean _sawBedLastTick;
    // ==== (2026-09-01 人类化上床 H4) 不再绕 DoToClosest+Interact, 自己 onTick 内联 4 步走+瞄+点
    // 挑床侧面 4 选 1 失败时轮换: 0=clockwise 1=counter 2=faceDirOpp+clock 3=faceDirOpp+counter
    private int _humanSleepSide = 0;
    // 『瞄+右键』阶段小超时: 站到床边后 3 秒仍没入睡/没 Respawn 消息 就换一侧
    private final TimerGame _humanSleepTimer = new TimerGame(3);
    // 记录上次侧, 成功换侧时 reset 大超时, 避免一次 wander4s 就耗光 _bedInteractTimeout
    private int _humanSleepSideLast = -1;

    public PlaceBedAndSetSpawnTask() {

    }

    public PlaceBedAndSetSpawnTask stayInBed() {
        _stayInBed = true;
        return this;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _currentBedRegion = null;
        _humanSleepSide = 0;
        _humanSleepSideLast = -1;
        _humanSleepTimer.reset();

        // Don't break our bed thing.
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockPlacing(pos -> {
            if (_currentBedRegion != null) {
                BlockPos start = _currentBedRegion,
                        end = _currentBedRegion.add(BED_CLEAR_SIZE);
                return start.getX() <= pos.getX() && pos.getX() < end.getX()
                        && start.getZ() <= pos.getZ() && pos.getZ() < end.getZ()
                        && start.getY() <= pos.getY() && pos.getY() < end.getY();
            }
            return false;
        });
        mod.getBehaviour().avoidBlockBreaking(pos -> {
            if (_currentBedRegion != null) {
                for (Vec3i baseOffs : BED_BOTTOM_PLATFORM) {
                    BlockPos base = _currentBedRegion.add(baseOffs);
                    if (base.equals(pos)) return true;
                }
            }
            // Don't ever break beds. If one exists, we will sleep in it.
            return mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock;
        });

        mod.getBlockTracker().trackBlock(BEDS);

        _spawnSet = false;
        _sleepAttemptMade = false;
        _wasSleeping = false;
        _sleepTimeoutStarted = false;

        _respawnPointSetMessageCheck = EventBus.subscribe(ChatMessageEvent.class, evt -> {
            String msg = evt.message.asString();
            if (msg.contains("Respawn point set")) {
                _spawnSet = true;
                _inBedTimer.reset();
            }
        });
        _respawnFailureMessageCheck = EventBus.subscribe(GameOverlayEvent.class, evt -> {
            final String[] NEUTRAL_MESSAGES = new String[]{"You can sleep only at night", "You can only sleep at night", "You may not rest now; there are monsters nearby"};
            for (String checkMessage : NEUTRAL_MESSAGES) {
                if (evt.message.contains(checkMessage)) {
                    if (!_sleepAttemptMade) {
                        _bedInteractTimeout.reset();
                    }
                    _sleepAttemptMade = true;
                }
            }
        });
    }

    public void resetSleep() {
        _spawnSet = false;
        _sleepAttemptMade = false;
        _wasSleeping = false;
        _sleepTimeoutStarted = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Summary:
        // If we find a bed nearby, sleep in it.
        // Otherwise, place bed:
        //      Collect bed if we don't have one.
        //      Find a 3x2x1 region and clear it
        //      Stand on the edge of the long (3) side
        //      Place on the middle block, reliably placing the bed.

        // We cannot do this anywhere but the overworld.
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to the overworld first.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        Screen screen = MinecraftClient.getInstance().currentScreen;
        if (!_stayInBed && _inBedTimer.elapsed() && screen instanceof SleepingChatScreen) {
            _progressChecker.reset();
            setDebugState("Sleeping...");
            _wasSleeping = true;
            //Debug.logMessage("Closing sleeping thing");
            _spawnSet = true;
            screen.close(); // may be chat empty ??
            return null;
        }

        // (2026-08-26 用户修正: 必须右键点一下床才设重生点, 不能跳过) 进入
        // "去睡床"分支时启动超时 (_sleepTimeoutStarted 独立于 overlay 消息):
        // 若 5 秒内既没出 "Respawn point set" 消息也没真正睡进去 (白天不能睡 /
        // 右键没生效 / 站错位), 就收尾继续走 — 不再和床死循环交互。已睡进去
        // (isSleeping) 时不触发, 让夜晚正常睡过。
        if (_sleepTimeoutStarted && !_stayInBed && !mod.getPlayer().isSleeping()) {
            if (_bedInteractTimeout.elapsed()) {
                Debug.logMessage("Failed to set spawn via sleeping within timeout, moving on.");
                _spawnSet = true;
                return null;
            }
        }

        // ===== 睡完拆床 (2026-08-27, 用户) =====
        // stayInBed 夜间跳过 = 游牧睡床: 睡醒(白天)后把【自己刚放的床】拆掉收走,
        // 不滞留床、不设永久重生点 (speedrunner 带走床)。只在 stayInBed 且真睡过
        // (spawnSet) 后触发; 永久设重生点(_stayInBed=false)的不受影响。
        // (2026-09-01 睡完带走"睡的床", 用户"睡的不是自己放的床, 背包没床, 理应
        // 挖床") 旧逻辑只拆 _justPlacedBed(自己刚放的那张)。但夜间可能直接睡在
        // 村庄/结构的现成床上(_justPlacedBed 为空), 睡完这张村庄床并不走 —— 背包
        // 又没床, 理应把刚睡的那张挖走带走。条件收窄到: 仅当【背包确实没有床】时
        // 才去挖刚睡的床, 否则尊重"不拆眼前床"原则, 不无故挖走别人/多余的床。
        if (_stayInBed && _spawnSet && !mod.getPlayer().isSleeping()
                && WorldHelper.isBrightDaytime()
                && !mod.getItemStorage().hasItem(ItemHelper.BED)) {
            // 目标床: 优先自己刚放的; 没放床(睡在现成床上)就退而求其次挖刚睡的那张。
            BlockPos targetBed = _justPlacedBed != null ? _justPlacedBed : _bedForSpawnPoint;
            if (targetBed != null) {
                BlockPos bed = selfBedStillThere(mod, targetBed);
                if (bed != null) {
                    if (bed.isWithinDistance(mod.getPlayer().getPos(), 8)) {
                        setDebugState("Picking up my placed bed after sleeping.");
                        return new DestroyBlockTask(bed);
                    }
                    // 睡醒后离床太远(被顶开): 不折返, 放弃这张床。
                    _justPlacedBed = null;
                } else {
                    // 床方块没了(可能已拆): 掉落物在附近先捡走, 没有才清记忆。
                    if (bedDropNearby(mod, targetBed)) {
                        setDebugState("Picking up my bed drop.");
                        return new PickupDroppedItemTask(new ItemTarget("bed", 1), true);
                    }
                    _justPlacedBed = null;
                }
            }
        }

        boolean anyBed = mod.getBlockTracker().anyFound(BEDS);
        if (!anyBed) {
            // ==== [Diag-bed] FIX for "placed bed, then went off to craft
            // another one": after placing, the bed is in the WORLD but not
            // yet in the BlockTracker cache (chunk scans are throttled and
            // can lag seconds behind). During that gap anyBed=false AND
            // hasBedItem=false, so the code below diverted to
            // "get/craft a bed". Instead: check the world directly around
            // the region we just placed in and register any bed we find with
            // the BlockTracker immediately. ====
            BlockPos freshBed = findFreshlyPlacedBed(mod);
            if (freshBed != null) {
                Block bedBlock = mod.getWorld().getBlockState(freshBed).getBlock();
                mod.getBlockTracker().addBlock(bedBlock, freshBed);
                mod.setLastSelfPlacedBed(freshBed); // (2026-08-21) 记录自放床, 睡完收回
                String m = "[Diag-bed] freshly placed bed detected at " + freshBed.toShortString()
                        + ", registered with BlockTracker (scan lag workaround)";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
                anyBed = true;
            }
        }
        if (_sawBedLastTick && !anyBed) {
            Debug.logMessage("[BedDebug] I HAD a bed, now BlockTracker LOST it! region=" + _currentBedRegion + " hasItem=" + mod.getItemStorage().hasItem(ItemHelper.BED));
        }
        _sawBedLastTick = anyBed;
        // (2026-08-23 就近睡/就近放) 用户实测: 走到床边却睡不进去 (日志:
        // 附近已有床, bot 却在放新床 Wander↔Interact dir up 横跳)。旧改动
        // "背包有床就放床"过激 — 附近有床时应直接去睡。规则:
        //  - 附近 30 格内有床 → 去睡最近的床 (无论背包有没有床)
        //  - 附近无近床且背包有床 → 就地放一张睡 (不跑去远处村庄床)
        //  - 附近无近床且背包没床 → 去远处/收集床
        boolean hasBedItem = mod.getItemStorage().hasItem(ItemHelper.BED);
        // (2026-08-27 同床修复) 背包床刚用完(已放到世界) 但还没记下刚放的床 →
        // 直接从放床区域扫出真实床块记作 _justPlacedBed, 让下面睡床分支优先睡它,
        // 而不是跑去缓存里远处的村庄床 (用户实测: 放下床却跑到 ~122 格外睡)。
        if (!hasBedItem && _justPlacedBed == null && _currentBedRegion != null) {
            BlockPos placed = findPlacedBedNearRegion(mod, _currentBedRegion);
            if (placed != null) {
                _justPlacedBed = placed;
                // (2026-08-27 关键: 同步记录到 _lastSelfPlacedBed) 否则顶部
                // BeatMinecraft "白天/任何时候优先收床" 找不到这张床, 放床后一旦
                // 被 gear/挖矿抢走, 床就滞留 (用户实测: 放床→想去睡→被 iron gear
                // 抢走→床没拆)。
                mod.setLastSelfPlacedBed(placed);
                Debug.logMessage("[Diag-bed] sleep target set to just-placed bed at " + placed.toShortString());
                Debug.logToFile("altoclef_diag.log", "[Diag-bed] sleep target set to just-placed bed at " + placed.toShortString());
            }
        }
        BlockPos nearestBed = anyBed ? mod.getBlockTracker().getNearestTracking(BEDS).orElse(null) : null;
        boolean nearbyBed = nearestBed != null
                && nearestBed.isWithinDistance(mod.getPlayer().getPos(), 30);
        boolean shouldGoToBed = anyBed && (!hasBedItem || nearbyBed);
        if (anyBed && !shouldGoToBed && hasBedItem) {
            setDebugState("Has bed item, but nearest bed is far (" + (nearestBed == null ? "?" : nearestBed.getSquaredDistance(mod.getPlayer().getBlockPos())) + ") — placing bed nearby");
        }
        if (anyBed) {
            Debug.logMessage("[BedDebug] anyFound(BEDS)=TRUE; knownBeds=" + mod.getBlockTracker().getKnownLocations(BEDS).size() + " player=" + mod.getPlayer().getBlockPos().toShortString());
        }
        if (shouldGoToBed) {
            // (2026-08-26 / 2026-09-01 H4 人类化上床) 首次进入分支启动大超时 12s
            // (原 5s 太小: InteractWithBlockTask StuckTimer=2s + Wander=4s = 每轮8s >5s, 还没轮完
            // 一次「瞄-点」就被 PlaceBed 自身 _bedInteractTimeout 提前收尾=永远睡不进)
            if (!_stayInBed && !_sleepTimeoutStarted) {
                _sleepTimeoutStarted = true;
                _bedInteractTimeout.reset();
            }
            setDebugState("Going to bed (human-style aim & right-click thin bed)...");

            // ① 挑床: 优先刚放的 (避免 BlockTracker 扫描滞后选到远处村庄床), 没有才取最近
            if (_justPlacedBed != null
                    && !(mod.getWorld().getBlockState(_justPlacedBed).getBlock() instanceof BedBlock)) {
                BlockPos found = findPlacedBedNearRegion(mod, _currentBedRegion);
                _justPlacedBed = (found != null) ? found : null;
            }
            BlockPos toSleepIn = _justPlacedBed;
            if (toSleepIn == null || !(mod.getWorld().getBlockState(toSleepIn).getBlock() instanceof BedBlock)) {
                toSleepIn = mod.getBlockTracker().getNearestTracking(BEDS).orElse(null);
            }
            if (toSleepIn == null) {
                // shouldn't happen (shouldGoToBed requires anyBed=true); 退而重扫
                _justPlacedBed = null;
                return null;
            }

            // ② 床头 + FACING 信息
            _bedForSpawnPoint = WorldHelper.getBedHead(mod, toSleepIn);
            if (_bedForSpawnPoint == null) _bedForSpawnPoint = toSleepIn;
            BlockState bsHead = mod.getWorld().getBlockState(_bedForSpawnPoint);
            Direction face = Direction.NORTH;
            if (bsHead.getBlock() instanceof BedBlock) {
                try { face = bsHead.get(BedBlock.FACING); } catch (Exception ignore) {}
            }

            // ③ 4 个候选站侧: 优先 face.clockwise (原设计正确的侧) 不行再换 3 侧, 每次换
            // 侧就 reset 小超时 _humanSleepTimer(3s) 也 reset 大超时, 不再因 wander 耗光
            Direction[] sideCandidates = new Direction[]{
                    face.rotateYClockwise(),
                    face.rotateYCounterclockwise(),
                    face.rotateYClockwise().getOpposite(),
                    face.rotateYCounterclockwise().getOpposite()
            };
            if (_humanSleepSide < 0 || _humanSleepSide >= sideCandidates.length) _humanSleepSide = 0;
            Direction standDir = sideCandidates[_humanSleepSide];
            // 要右键点的是"床头朝向玩家的那个面"= 玩家站 standDir, 要 hit 的面是 standDir.opposite
            // (原 Interact 传 side=standDir 导致 LookHelper 的直角检查反向报错, reachable 常空)
            Direction clickFace = standDir.getOpposite();
            BlockPos standPos = _bedForSpawnPoint.add(standDir.getVector());
            // 床头的点击瞄准点: 薄床 y=[0,9/16], 瞄 y=0.28 (中点偏下) + 朝玩家面内缩 0.45
            Vec3i cv = clickFace.getVector();
            Vec3d aimPoint = new Vec3d(
                    _bedForSpawnPoint.getX() + 0.5 + cv.getX() * 0.45,
                    _bedForSpawnPoint.getY() + 0.28,  // ≈ 9/32=0.28125, 薄床碰撞箱中心
                    _bedForSpawnPoint.getZ() + 0.5 + cv.getZ() * 0.45
            );

            // ④ 到位检查: 水平曼哈顿 ≤ 2 (≈ 直线距离 ≤ 1.7) 且同 y
            BlockPos pp = mod.getPlayer().getBlockPos();
            boolean atStand = (Math.abs(pp.getX() - standPos.getX()) <= 1
                    && Math.abs(pp.getZ() - standPos.getZ()) <= 1
                    && Math.abs(pp.getY() - standPos.getY()) <= 1);
            if (!atStand) {
                // 未到位 → 走去。途中不启动小超时, 避免走动时消耗 3s
                return new GetToBlockTask(standPos);
            }
            // 第一次进入"已到位" → 启动 3s 小超时 (只启动一次, 直到换侧/完成)
            if (_humanSleepSideLast != _humanSleepSide) {
                _humanSleepSideLast = _humanSleepSide;
                _humanSleepTimer.reset();
                _sleepAttemptMade = true;
                _progressChecker.reset();
                // 换新侧时给大超时补 12s, 防止一次 wander+瞄就结束
                _bedInteractTimeout.reset();
                String m = "[Diag-bed] human-sleep switch-side " + _humanSleepSide + " face=" + face
                        + " standDir=" + standDir + " standPos=" + standPos.toShortString()
                        + " clickFace=" + clickFace + " aim=" + aimPoint;
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }

            // ⑤ 人类化瞄+点: 停掉 Baritone 路径 → 用 LookHelper.lookAt(aimPoint) 瞄薄床
            // → 取消 isLookingAt/reachable 判定, 只要距离 ≤ 4.5 (方块手长 4.5) 就立刻 每 tick
            // 强制 CLICK_RIGHT。人类就这么干: 看一眼, 对准, 连续点几下。
            ICustomGoalProcess proc = mod.getClientBaritone().getCustomGoalProcess();
            if (proc.isActive()) proc.onLostControl();
            double distSq = mod.getPlayer().getPos().squaredDistanceTo(
                    _bedForSpawnPoint.getX() + 0.5,
                    _bedForSpawnPoint.getY() + 0.28,
                    _bedForSpawnPoint.getZ() + 0.5);
            if (distSq <= 4.5 * 4.5) {
                LookHelper.lookAt(mod, aimPoint);
                // 先去潜行 (sneak 劫持会把方块右键变"用物品"不放床/不睡), 双通道释放
                mod.getInputControls().release(Input.SNEAK);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
                // 空手 (避免对着床按右键先吃东西/放方块, 睡不了)
                mod.getSlotHandler().forceDeequipRightClickableItem();
                mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                // 每 tick 点过就 reset _inBedTimer (1s), 若真成功睡进去会走顶部 SleepingChatScreen 分支
                _inBedTimer.reset();
            } else {
                // 距离还是超了 (被挤开/站错) → 重走 GetToBlock, 不耗超时
                return new GetToBlockTask(standPos);
            }

            // ⑥ 小超时换侧: 3 秒内没睡进去/没 Respawn 消息 → 换下一侧 (共 4 侧)
            if (_humanSleepTimer.elapsed()) {
                String m2 = "[Diag-bed] human-sleep 3s no-sleep, bumping side " + _humanSleepSide + "-> " + ((_humanSleepSide + 1) % 4);
                Debug.logMessage(m2);
                Debug.logToFile("altoclef_diag.log", m2);
                _humanSleepSide = (_humanSleepSide + 1) % 4;
                // 推玩家 1 格: 用 4 ticks 的 WanderTask(1) 走开当前站位=换侧前抖一下
                mod.getBehaviour().avoidBlockPlacing(pos -> false);
                return new TimeoutWanderTask(1, true);
            }
            return null;
        }

        // Get a bed if we don't have one.
        if (!mod.getItemStorage().hasItem(ItemHelper.BED)) {
            setDebugState("Getting a bed first");
            if (_stayInBed) {
                // Strictly for SleepThroughNightTask this is a bug: we should already have a bed.
                String m = "[Diag-bed] SleepThroughNight wants to sleep but has NO bed; going to get one. anyFound=" + anyBed;
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
            return TaskCatalogue.getItemTask("bed", 1);
        }

        if (_currentBedRegion == null) {
            if (_regionScanTimer.elapsed()) {
                Debug.logMessage("Rescanning for nearby bed place position...");
                _regionScanTimer.reset();
                _currentBedRegion = this.locateBedRegion(mod, mod.getPlayer().getBlockPos());
            }
        }
        if (_currentBedRegion == null) {
            setDebugState("Searching for spot to place bed, wandering...");
            String m = "[Diag-bed] diverting to WANDER instead of sleeping. stayInBed=" + _stayInBed
                    + " anyBed=" + anyBed
                    + " hasBedItem=" + mod.getItemStorage().hasItem(ItemHelper.BED)
                    + " player=" + mod.getPlayer().getBlockPos().toShortString();
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        _placeAttemptTimer = null;
            return new TimeoutWanderTask();
        }

        // Clear and make bed foundation

        for (Vec3i baseOffs : BED_BOTTOM_PLATFORM) {
            BlockPos toPlace = _currentBedRegion.add(baseOffs);
            if (!WorldHelper.isSolid(mod, toPlace)) {
                _currentStructure = toPlace;
                break;
            }
        }

        outer:
        // (2026-08-27 清床两侧, 用户原则) 原 BED_CLEAR_SIZE 只清床所在那行 (Z=0),
        // 睡床要站的"床头侧"方块不在清理范围内 → 只清到床脚侧, 床头侧有方块时
        // bot 站不过去点不到床 (用户实测)。改成 Z=-1..1 把床两侧都清掉, 无论站
        // 哪一侧都能站。
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = 0; dx < BED_CLEAR_SIZE.getX(); ++dx) {
                for (int dy = 0; dy < BED_CLEAR_SIZE.getY(); ++dy) {
                    BlockPos toClear = _currentBedRegion.add(dx, dy, dz);
                    if (WorldHelper.isSolid(mod, toClear)) {
                        _currentBreak = toClear;
                        break outer;
                    }
                }
            }
        }

        if (_currentStructure != null) {
            if (WorldHelper.isSolid(mod, _currentStructure)) {
                _currentStructure = null;
            } else {
                setDebugState("Placing structure for bed");
                _placeAttemptTimer = null;
                return new PlaceStructureBlockTask(_currentStructure);
            }
        }
        if (_currentBreak != null) {
            if (!WorldHelper.isSolid(mod, _currentBreak)) {
                _currentBreak = null;
            } else {
                setDebugState("Clearing region for bed");
                _placeAttemptTimer = null;
                return new DestroyBlockTask(_currentBreak);
            }
        }

        BlockPos toStand = _currentBedRegion.add(BED_PLACE_STAND_POS);
        // Our bed region is READY TO BE PLACED
        if (!mod.getPlayer().getBlockPos().equals(toStand)) {
            _placeAttemptTimer = null;
            return new GetToBlockTask(toStand);
        }


        // (2026-09-01 人类化花草清理 H1/H3: 人已到 toStand 放床站位,
        // 立刻在放床落点 (床脚=region+BED_PLACE_POS 及其朝右 1 格床头)、
        // 以及上方 y+1 头空, 把 short_grass / tall_grass / flower / vine / double_plant
        // 这类"非实心非空气非流体、但 BedBlock 放下去会被 Minecraft reject"的植被撸掉。
        // 人类玩家就这么干: 左键几下清掉挡方块的草, 直接右键放床, 不等 wander 重扫。
        // 清理范围是床的 footprint (X=1..2 Y=0..1 Z=-1..0), 不挖远处的土方块, 不破坏地形。
        {
            BlockPos foot = _currentBedRegion;
            // 要清理的 8 格: 床 footprint X=1..2 (两格床) Y=0..1 Z=-1..0 (站床侧+床里侧)
            int[][] DEL = new int[][]{
                {1,0,0},{2,0,0},  {1,1,0},{2,1,0},
                {1,0,-1},{2,0,-1},{1,1,-1},{2,1,-1}
            };
            for (int[] d : DEL) {
                BlockPos p = foot.add(d[0], d[1], d[2]);
                BlockState bs = mod.getWorld().getBlockState(p);
                Block b = bs.getBlock();
                // 过滤: 不是空气+不是实心(土方/石头=留给 isSolid 清段处理)+不是流体, 
                // 并且 canBreak, 基本等于"花草藤类植被挡床"。
                boolean isAir = WorldHelper.isAir(mod, p);
                boolean solid = WorldHelper.isSolid(mod, p);
                boolean fluid = !bs.getFluidState().isEmpty();
                if (!isAir && !solid && !fluid && WorldHelper.canBreak(mod, p)) {
                    String m = "[Diag-bed] place-footprint veget blocking bed at " + p.toShortString() + " block=" + b
                            + " -> quick-break (human-style left-click before place-right)";
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                    _placeAttemptTimer = null;
                    setDebugState("Brushing grass/flowers off bed footprint (pre-place)");
                    return new DestroyBlockTask(p);
                }
            }
        }

        BlockPos toPlace = _currentBedRegion.add(BED_PLACE_POS);
        Block placedAbove = mod.getWorld().getBlockState(toPlace.offset(BED_PLACE_DIRECTION)).getBlock();
        Block placedHere = mod.getWorld().getBlockState(toPlace).getBlock();
        Debug.logMessage("[BedDebug] placed-check at " + toPlace.toShortString() + ": here=" + placedHere + " above=" + placedAbove + " hereIsBed=" + (placedHere instanceof BedBlock));
        if (placedAbove instanceof BedBlock || placedHere instanceof BedBlock) {
            // (2026-08-26 修复: 床真正放好才记录自放床) 旧代码在放床【前】就
            // setLastSelfPlacedBed, 父任务 "Picking up our placed bed" 下一 tick
            // 就把刚放的床撸掉 → 设置重生点流程被打断循环 (16:37 日志实锤:
            // Interact using bed → "Picking up our placed bed" → Destroy block
            // 立刻撸)。放床成功 (placedAbove 已是床) 才记录, 收床才有意义。
            // (2026-08-27 同床修复) _justPlacedBed 必须指向【真实床块】。旧代码只
            // 查 placedAbove 且把 _justPlacedBed 设成 toPlace — 若床实际落在
            // placedHere / toPlace.offset(UP), 该坐标不是床 → 睡觉分支下一 tick 就
            // 把 _justPlacedBed 清空 → 又去找远处村庄床 (用户实测: 放下床却跑到
            // ~122 格外村庄床睡)。这里优先从放床区域扫出真实床块。
            BlockPos bedBlock = placedAbove instanceof BedBlock
                    ? toPlace.offset(BED_PLACE_DIRECTION) : toPlace;
            BlockPos actual = findPlacedBedNearRegion(mod, _currentBedRegion);
            if (actual != null) bedBlock = actual;
            mod.setLastSelfPlacedBed(bedBlock);
            _justPlacedBed = bedBlock;
            setDebugState("Waiting to rescan + find bed that we just placed. Should be almost instant.");
            _progressChecker.reset();
            return null;
        }
        setDebugState("Placing bed...");
        Debug.logMessage("[BedDebug] >>> about to place bed here (InteractWithBlockTask) at " + toPlace.toShortString());

        // (2026-08-27 文案纠错) 这行原本误写 "Filling in Portal" 是从传送门任务
        // 抄来的残留调试文案, 让用户误以为新世界在触发"填传送门"(结果只是放床)。
        // (2026-09-01 放床 3s 超时兜底: 上一轮 Interact↔Wander↔GetToBlock 死循环
        // 持续 14s 床根本没放进世界 (tasks.log 14:22:10-24 实锤: _currentBedRegion 卡
        // 在 (-212,65,666), 反复 "Placing bed... progressing" 却 anyFound=bedForSpawn=
        // sleepAttempted=false)。原因: 若创建放置交互目标仍不可达 (站错位/头顶
        // 被挡), 就会被 progressChecker 反复 Wander → 外层 GetToBlock(standPos)
        // 强行拉回 → 再 Wander 无出口。这里独立计时: 进入"正在放床"段后 3 秒
        // 仍看不到真实床方块出现, 强制抛弃当前 _currentBedRegion 并重扫 (换个
        // 位置再试)。比 Wander 更治本 —— 放床目标本身就无效时, Wander 只是兜
        // 圈子, 换区域才是真修复。)
        // (2026-09-01 修2: placeAttemptTimer 生命周期真正独立)
        // 旧 bug: 只在 null 时 new+reset() 一次。进入 PLACING 段后 Wander/GetToBlock
        // /回 Searching 再回 PLACING, timer 不再 reset → 3 秒过 elapsed() 永久 true
        // → 每次进 PLACING 段先触发 "TIMEOUT → 换区域 return null", Interact
        // 子任务永远不会真的 spawn (14:41:47~14:42:22 实锤: 3 次 PLACING 均未
        // 看到 Interact 子任务成功点击 CLICK_ATTEMPTED, 直接回 Searching→Wander)。
        // 修复:
        //  - TIMEOUT 触发换区域、_progressChecker.fail 触发 Wander、以及段外
        //    (回 Searching / 先清方块 / 先搭结构 / 先 GetToStand) 三处都把
        //    _placeAttemptTimer=null, 保证下次真正返回到"要 spawn Interact 子
        //    任务"的前一刻才 reset, 3 秒从真正交互开始时算起。
        //  - 只有当前子任务不是 Interact (这是第一次进段 / 从别的段第一次切回
        //    来) 才 reset, 避免每 tick 都重置=超时永不触发。
        boolean currentlyRunningPlaceInteract = thisOrChildSatisfies(task -> task instanceof InteractWithBlockTask);
        if (!currentlyRunningPlaceInteract) {
            // 本 tick 要第一次派 Interact 子任务 → 重置计时器。
            _placeAttemptTimer = new TimerGame(3);
            _placeAttemptTimer.reset();
        }
        if (_placeAttemptTimer != null && _placeAttemptTimer.elapsed()) {
            BlockPos saw = findPlacedBedNearRegion(mod, _currentBedRegion);
            if (saw == null) {
                String m = "[Diag-bed] PLACE BED TIMEOUT after 3s at region " + (_currentBedRegion == null ? "null" : _currentBedRegion.toShortString())
                        + ", rescanning new region (old goal unreachable / blocked).";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
                _currentBedRegion = null;
                _justPlacedBed = null;
                _regionScanTimer.reset();
                _placeAttemptTimer = null;
                _progressChecker.reset();
                return null;
            }
            _placeAttemptTimer = null;
        }
        setDebugState("Placing bed... progressing");
        if (!_progressChecker.check(mod)) {
            _progressChecker.reset();
            _placeAttemptTimer = null;
            return _wanderTask;
        }

        // Scoot backwards if we're trying to place and fail
        if (thisOrChildSatisfies(task -> {
            if (task instanceof InteractWithBlockTask intr)
                return intr.getClickStatus() == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED;
            return false;
        })) {
            mod.getInputControls().tryPress(Input.MOVE_BACK);
        }

        return new InteractWithBlockTask(new ItemTarget("bed", 1), BED_PLACE_DIRECTION, toPlace.offset(BED_PLACE_DIRECTION.getOpposite()), false);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // ==== [Diag-bed] did we leave WITHOUT sleeping, and is a bed stranded? ====
        String m = "[Diag-bed] PlaceBedAndSetSpawnTask STOP"
                + " interruptedBy=" + (interruptTask != null ? interruptTask.toString() : "null(finished)")
                + " stayInBed=" + _stayInBed
                + " spawnSet=" + _spawnSet
                + " wasSleeping=" + _wasSleeping
                + " sleepAttempted=" + _sleepAttemptMade
                + " bedRegion=" + _currentBedRegion
                + " bedForSpawn=" + _bedForSpawnPoint
                + " hasBedItem=" + mod.getItemStorage().hasItem(ItemHelper.BED)
                + " anyFound=" + mod.getBlockTracker().anyFound(BEDS);
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);

        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(BEDS);
        EventBus.unsubscribe(_respawnPointSetMessageCheck);
        EventBus.unsubscribe(_respawnFailureMessageCheck);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlaceBedAndSetSpawnTask;
    }

    @Override
    protected String toDebugString() {
        return "Placing a bed nearby + resetting spawn point";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            Debug.logWarning("Can't place spawnpoint/sleep in a bed unless we're in the overworld!");
            return true;
        }
        // (2026-08-27 睡完拆床) stayInBed 睡醒但还没拆床 → 不算完成, 等 onTick 的
        // 拆床阶段把 _justPlacedBed 处理掉(拆走/捡起/放弃)再收尾, 否则任务一结束
        // 床就滞留世界 (用户: 睡完不拆床)。
        // (2026-09-01 睡的床也要带走) 背包没床时, 刚睡的床(含现成的村庄床)同样要
        // 拆走 —— 只要还有该拆的床存在就不算完成, 保证拆床阶段跑完。
        if (_stayInBed && _spawnSet && !mod.getPlayer().isSleeping()
                && WorldHelper.isBrightDaytime()
                && !mod.getItemStorage().hasItem(ItemHelper.BED)) {
            BlockPos targetTeardown = _justPlacedBed != null ? _justPlacedBed : _bedForSpawnPoint;
            if (targetTeardown != null && selfBedStillThere(mod, targetTeardown) != null) {
                return false;
            }
        }
        return _spawnSet && !mod.getPlayer().isSleeping() && _inBedTimer.elapsed();
    }

    public BlockPos getBedSleptPos() {
        return _bedForSpawnPoint;
    }

    public boolean isSpawnSet() {
        return _spawnSet;
    }

    /**
     * [Diag-bed] Scan the world directly (NOT the BlockTracker) for a bed
     * block near the player / the region we just placed a bed in. Used to
     * bridge the gap between "bed placed" and "BlockTracker scan noticed it".
     */
    private BlockPos findFreshlyPlacedBed(AltoClef mod) {
        BlockPos player = mod.getPlayer().getBlockPos();
        // Prefer the region we just placed in (cheap, precise)...
        if (_currentBedRegion != null) {
            for (int dx = 0; dx < BED_CLEAR_SIZE.getX(); ++dx) {
                for (int dy = 0; dy < BED_CLEAR_SIZE.getY(); ++dy) {
                    for (int dz = 0; dz < BED_CLEAR_SIZE.getZ(); ++dz) {
                        BlockPos check = _currentBedRegion.add(dx, dy, dz);
                        if (mod.getWorld().getBlockState(check).getBlock() instanceof BedBlock) {
                            return check;
                        }
                    }
                }
            }
        }
        // ...then a small radius around the player (covers the case where we
        // walked a couple blocks after placing).
        final int R = 4;
        for (int dy = -2; dy <= 2; ++dy) {
            for (int dz = -R; dz <= R; ++dz) {
                for (int dx = -R; dx <= R; ++dx) {
                    BlockPos check = player.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(check).getBlock() instanceof BedBlock) {
                        return check;
                    }
                }
            }
        }
        return null;
    }

    /**
     * (2026-08-27 同床修复) 从【放床区域 + 一圈余量】直接扫出真实床方块。
     * 放床检测用 placedAbove 且把坐标记到 toPlace 在部分站位会漏/错位 → 睡眠
     * 分支以此坐标验真时会发现"不是床"而清空记忆, 退回去找远处村庄床。只要床
     * 真的在区域内, 这里一定能定位到它的真实方块, 让睡床分支睡"自己刚放的床"。
     */
    private BlockPos findPlacedBedNearRegion(AltoClef mod, BlockPos region) {
        if (region == null) return null;
        final int MARGIN = 2;
        for (int dx = -MARGIN; dx < BED_CLEAR_SIZE.getX() + MARGIN; ++dx) {
            for (int dy = -1; dy < BED_CLEAR_SIZE.getY() + 1; ++dy) {
                for (int dz = -MARGIN; dz < BED_CLEAR_SIZE.getZ() + MARGIN; ++dz) {
                    BlockPos check = region.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(check).getBlock() instanceof BedBlock) {
                        return check;
                    }
                }
            }
        }
        return null;
    }

    /**
     * (2026-08-27 睡完拆床) 记忆床位置是否仍是床方块 (床占两格, 记忆点可能
     * 是另一半, 四邻也查)。
     */
    private BlockPos selfBedStillThere(AltoClef mod, BlockPos remembered) {
        if (remembered == null) return null;
        if (mod.getWorld().getBlockState(remembered).getBlock() instanceof BedBlock) {
            return remembered;
        }
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos neighbor = remembered.offset(dir);
            if (mod.getWorld().getBlockState(neighbor).getBlock() instanceof BedBlock) {
                return neighbor;
            }
        }
        return null;
    }

    /**
     * (2026-08-27 睡完拆床) 床位置附近 (4 格) 是否躺着床掉落物 — 刚撸掉的床。
     */
    private boolean bedDropNearby(AltoClef mod, BlockPos bedPos) {
        if (bedPos == null) return false;
        ItemTarget bedTarget = new ItemTarget(ItemHelper.BED, 1);
        java.util.Optional<ItemEntity> drop =
                mod.getEntityTracker().getClosestItemDrop(WorldHelper.toVec3d(bedPos), bedTarget);
        return drop.isPresent() && drop.get().isAlive()
                && drop.get().getBlockPos().isWithinDistance(bedPos, 4);
    }

    private BlockPos locateBedRegion(AltoClef mod, BlockPos origin) {
        final int SCAN_RANGE = 10;

        // (2026-08-27 用户修正: 限制在脚部平面附近, 不往地下挖空位) 人两格高,
        // 脚所在方块 Y = origin.getY() (getBlockPos 返回脚部)。放床应贴着玩家
        // 站立的这一层地表, 而不是跑进脚下深处的洞穴空腔去放 — 否则 bot 会
        // "明明平坦却往下挖 3 格给自己挖容身洞" (用户实测)。扫描 Y 只在本层
        // 地表附近 (脚高度 -1 到 +3, 覆盖"站在地上/站高一格"两种情况), 超出
        // 该范围的位置直接跳过; 评分再按与脚平面高度差加倍惩罚, 保证优先选
        // 同一平面的平坦地面。
        final int footY = origin.getY();
        final int SCAN_DY_MIN = -1;
        final int SCAN_DY_MAX = 3;

        BlockPos closestGood = null;
        double closestDist = Double.POSITIVE_INFINITY;
        for (int dx = origin.getX() - SCAN_RANGE; dx < origin.getX() + SCAN_RANGE; ++dx) {
            for (int dz = origin.getZ() - SCAN_RANGE; dz < origin.getZ() + SCAN_RANGE; ++dz) {
                outer:
                for (int dy = footY + SCAN_DY_MIN; dy <= footY + SCAN_DY_MAX; ++dy) {
                    // Test range
                    BlockPos attemptStandPos = new BlockPos(dx, dy, dz);
                    double distance = attemptStandPos.getSquaredDistance(mod.getPlayer().getPos());
                    // 候选的最终评分 >= 原始距离: 原始距离都大于当前最优分就跳过。
                    if (distance > closestDist) continue;
                    // Everything from here on out is checking for a BETTER pos.
                    for (int checkX = 0; checkX < BED_CLEAR_SIZE.getX(); ++checkX) {
                        for (int checkY = 0; checkY < BED_CLEAR_SIZE.getY(); ++checkY) {
                            for (int checkZ = 0; checkZ < BED_CLEAR_SIZE.getZ(); ++checkZ) {
                                BlockPos checkAsGoodArea = attemptStandPos.add(checkX, checkY, checkZ);
                                if (!isGoodToPlaceInsideOrClear(mod, checkAsGoodArea)) {
                                    continue outer;
                                }
                            }
                        }
                    }
                    // (2026-09-01 平原选位卡死修复) 区域要站得上去才算位置, 不能
                    // 是"脚上方1-3格的空气空洞"。此前只查 BED_BOTTOM_PLATFORM
                    // (位置.down() 再加 (0..2,0,0) 偏移的 3 块实心) 不要求空洞格
                    // .down() 也是地面, 结果 footY+1/+2 的空气块:
                    //   attempt=(-213,67,750) BED_BOTTOM at y=66 恰好是 3 格草
                    //   → foundationSolid=true, 评分不加倍 → 因为比 footY 近 (
                    //     SCAN_DY 还允许)  实际赢过真地面候选。
                    // 结果 _currentBedRegion 选进 AIR (14:42:17 实锤: player=
                    // -213,68,750, 而 region 本身是 y=67/68 的空气柱, 人实际脚
                    // 还在 y=66 地面) → GetToBlock 永远走不到 → "明明是平原
                    //  找不到地方放床"。
                    // 修复:
                    //  1) 对 dyOff>=1 (离脚高 1+) 的候选强要求 attemptStandPos
                    //     .down() (即人要踩的那块地面) 实心 + BED_BOTTOM_PLATFORM
                    //     对应的 3 块 ground 全部实心, 否则就是空气空洞, 拒。
                    //  2) 候选本身若为实心且不可破坏 (如基岩), 也拒。
                    int dyOffGround = dy - footY;
                    if (dyOffGround >= 1) {
                        boolean allGroundSolid = true;
                        for (Vec3i baseOffs : BED_BOTTOM_PLATFORM) {
                            // baseOffs 是 (0,-1,0), (1,-1,0), (2,-1,0); 再
                            // 多 .down() 一次 → 真正人脚底踩的地面方块
                            BlockPos ground = attemptStandPos.add(baseOffs).down();
                            if (!WorldHelper.isSolid(mod, ground)) {
                                allGroundSolid = false;
                                break;
                            }
                        }
                        // 同时 attemptStandPos 自己.down() 也要实心 (人站的那一
                        // 格本身脚底下)
                        if (!WorldHelper.isSolid(mod, attemptStandPos.down())) {
                            allGroundSolid = false;
                        }
                        if (!allGroundSolid) continue outer;
                    }
                    // 候选位本身也不能是不可破坏实心
                    if (WorldHelper.isSolid(mod, attemptStandPos)) {
                        if (!WorldHelper.canBreak(mod, attemptStandPos)) continue outer;
                    }
                    // (2026-08-26 用户要求: 有实心地面就优先选, 别搭石头平台) 区域
                    // 正下方 3 个基础块若不全实心 (需要搭平台), 评分加倍 → 优先选
                    // "地面本来就实心"的区域。否则明明旁边有平地, bot 却选到空洞
                    // 上方搭石头再放床, 而且搭完平台站在上面放床交互还容易失败
                    // (用户实测: 站在床的格子上放不了床, 又往地上放石头)。
                    double score = distance;
                    boolean foundationSolid = true;
                    for (Vec3i baseOffs : BED_BOTTOM_PLATFORM) {
                        if (!WorldHelper.isSolid(mod, attemptStandPos.add(baseOffs))) {
                            foundationSolid = false;
                            break;
                        }
                    }
                    if (!foundationSolid) {
                        score *= 2.0;
                    }
                    // (2026-08-27 高度一致优先) 离脚部平面越远, 评分惩罚越重 —
                    // 高出越多(要搭平台站上去)或沉下去越多(钻进地壳空腔)都劣。
                    int dyOff = dy - footY;
                    if (dyOff != 0) {
                        score *= (1 + Math.abs(dyOff) * 4);
                    }
                    if (score < closestDist) {
                        closestGood = attemptStandPos;
                        closestDist = score;
                    }
                }
            }
        }
        // (2026-09-01 选位诊断: footY / closestGood 评分/是否踩实
        {
            String msg = "[Diag-bed] locateBedRegion result=" + (closestGood == null ? "null" : closestGood.toShortString())
                    + " footY=" + footY
                    + " score=" + (closestDist==Double.POSITIVE_INFINITY ? "inf" : String.format("%.1f", closestDist))
                    + " standGroundSolid=" + (closestGood == null ? "n/a" : WorldHelper.isSolid(mod, closestGood.down()))
                    + " player=" + mod.getPlayer().getBlockPos().toShortString();
            Debug.logMessage(msg);
            Debug.logToFile("altoclef_diag.log", msg);
        }
        return closestGood;
    }

    private boolean isGoodToPlaceInsideOrClear(AltoClef mod, BlockPos pos) {
        final Vec3i[] CHECK = new Vec3i[]{
                new Vec3i(0, 0, 0),
                new Vec3i(-1, 0, 0),
                new Vec3i(1, 0, 0),
                new Vec3i(0, 1, 0),
                new Vec3i(0, -1, 0),
                new Vec3i(0, 0, 1),
                new Vec3i(0, 0, -1)
        };
        for (Vec3i offs : CHECK) {
            if (!isGoodAsBorder(mod, pos.add(offs))) return false;
        }
        return true;
    }

    private boolean isGoodAsBorder(AltoClef mod, BlockPos pos) {
        if (WorldHelper.isSolid(mod, pos)) {
            return WorldHelper.canBreak(mod, pos);
        }
        // (2026-08-25 草原上搭石头平台修复) 非实心: 空气或植被(草/花/藤等)不
        // 挡床, 接受 — 否则草原上地面区域因周边有草被判不可用, bot 选到空中
        // 区域再往地上搭石头平台放床 (用户实测: 明明平坦草原却先放石头再放床)。
        // 流体(水/岩浆)仍拒绝, 避免把床放在水里。
        if (WorldHelper.isAir(mod, pos)) return true;
        return mod.getWorld().getBlockState(pos).getFluidState().isEmpty();
    }
}
