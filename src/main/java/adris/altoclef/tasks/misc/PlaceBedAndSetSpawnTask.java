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
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.api.process.ICustomGoalProcess;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SleepingChatScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class PlaceBedAndSetSpawnTask extends Task {

    private boolean _stayInBed;

    private static final Block[] BEDS = CollectBedTask.BEDS;

    private final TimerGame _regionScanTimer = new TimerGame(9);

    // (2026-09-24 用户改革"放床只需要一个干净的两格区域, 不需要清理那么大"):
    // 区域 = 床两格 footprint × (本体 + 上方) 两层 = 4 格; 上方一格有方块才清。
    // 旧版 3×2×1 区域 + Z=-1..1 两侧外扩清 18 格 + 7 邻居 border 检查, 全删。
    private final Vec3i BED_CLEAR_SIZE = new Vec3i(2, 2, 1);
    private final Vec3i[] BED_BOTTOM_PLATFORM = new Vec3i[]{
            new Vec3i(0, -1, 0),
            new Vec3i(1, -1, 0),
    };
    // 玩家站在床 footprint 前一格 (region-1 X), 面朝 footprint 放床:
    // 床沿玩家朝向占 region+(0,0,0) 与 region+(1,0,0), 玩家自己不占床格
    // (用户: "自己不能占着一点, 否则放不下")。
    private final Vec3i BED_PLACE_STAND_POS = new Vec3i(-1, 0, 0);
    private final Vec3i BED_PLACE_POS = new Vec3i(0, 0, 0);
    private final Direction BED_PLACE_DIRECTION = Direction.UP;
    private final TimerGame _bedInteractTimeout = new TimerGame(12);
    private final TimerGame _inBedTimer = new TimerGame(1);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(4, true);
    private TimerGame _placeAttemptTimer; // (2026-09-01 放床 3s 超时换区域重扫, 见 Placing bed 段)
    // (2026-09-16 铺地基死循环) 与 _placeAttemptTimer 同理, 但守护"先搭床底平台"段:
    // 该段 return PlaceStructureBlockTask 后原本没有任何超时出口, 而它又排在
    // 清理周围 / 站位 / 放床之前 → 一旦平台放不上去 (区域悬空、baritone 找不到
    // 可依附面), 整个任务就卡在这一步, 后面的机制一次都不会执行
    // (20260916-195358 实锤: 58s 内 debugstate 只有 place/wander)。
    private TimerGame _structureTimer;
    private static final int STRUCTURE_TIMEOUT_SECONDS = 8;
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(2);
    private BlockPos _currentBedRegion;
    private BlockPos _currentStructure, _currentBreak;
    /**
     * (2026-09-28) 本实例内【放置 / 站位导航 / 地基】失败过的 bed region(foot 格)。
     * 弃区重扫时跳过它们, 防在原地打转 —— 现场 20260928-140502: 7 轮尝试里有 4 轮
     * 是同一批"放不上"的位置被反复选中(bamboo / oak_leaves 格), 每轮白耗 3~10s。
     * onStart 清空(实例复用时跨轮不继承)。
     */
    private final java.util.Set<BlockPos> _failedBedRegions = new java.util.HashSet<>();
    private boolean _spawnSet;
    // (2026-09-21) 睡觉超时放弃后的一次性完成 latch, 防放弃分支每 tick 重入刷屏。
    private boolean _gaveUpSetSpawn;
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
    // (2026-09-24 用户改革"上床就右键对准就好, 不需要特地站侧位") 删除整套
    // 挑侧/换侧机制 (side 候选/3s 换侧计时/尝试计数/推挤 wander): 现场两连败
    // (side0 3s 全耗对齐点击 0 次; side1 yawOff 155°→179.8° 越转越偏)。新逻辑
    // = 走到床边 (≤4.5 格) → lookAt 床头瞄点 → 权威射线命中床即右键, 没睡成的
    // 兜底只剩 12s 大超时 + 触发层 30s 冷却。BedDebug 每 tick 刷屏加 2s 节流
    // (用户: 睡觉期间聊天栏一直刷屏)。
    private final TimerGame _bedDebugLogTimer = new TimerGame(2);
    // (2026-09-02 交互失败左键清障) 放床/上床交互尝试无效(超时)后, 左键清一遍
    // 交互区域的植被(草/花/藤)再重试右键 — 防"站在草里放床失败/床被草包住点不到"
    // (用户: 没破坏掉草, 站在草里很蠢; 建议交互无效就左键几下再右键)。每区域/
    // 每侧只清一次, 避免横跳。
    private boolean _interactClearedVeg = false;
    // (2026-09-06 准心避开村民实体框) 睡床右键前校验 crosshairTarget, 被实体
    // (最典型=床上躺着的村民) 挡住时本 tick 不点右键 — 点到村民会打开交易
    // 界面而不是睡床。此日志 2s 节流, 防止每 tick 刷屏。
    private final TimerGame _crosshairBlockedLogTimer = new TimerGame(2);

    // ==== (2026-09-13 睡前挖穿三格根治) ====
    // 根因 commit = 9dbeb779 (2026-09-08): 它首次把"读 crosshairTarget"接到
    // "派发破坏方块"上 (`else if (crosshairTarget instanceof BlockHitResult)`),
    // 同时把拆障改成实例保活 → 上游两个判据缺陷的代价从"点不出手"升级成"真的挖穿":
    //   ① crosshairTarget 是每帧按【真实当前朝向】raycast 的快照, 而 lookAt 走
    //      baritone LookBehavior.updateTarget = 带最大转速限制的平滑转向, 需多
    //      tick 到位 → 同 tick 读到的必然是转向前的旧朝向 (日志铁证: "switch-side"
    //      与 "blocked" 同毫秒打印);
    //   ② 只判 `instanceof BlockHitResult`, 未排除 getType()==MISS → MC 射线落空
    //      返回的 type=MISS BlockHitResult 被当成"被方块挡住" → 派发挖空气
    //      (日志铁证: 两条 "blocked by block 空气 … destroying it")。
    // 修法: 瞄准与判定解耦(朝向未到位不做任何判定) + 判据换源为权威瞄准射线 +
    // MISS 单列 + 拆障累计上限硬闸门。
    // "等待朝向到位"日志 2s 节流 (同 _crosshairBlockedLogTimer 口径)。
    private final TimerGame _aimWaitLogTimer = new TimerGame(2);
    // (2026-09-28 点床判据同源) "这次到底点没点"原先零日志 —— 现场 20260928-145156
    // 卡 30s 完全无法区分"点了没生效"与"根本没点"。此日志 2s 节流补齐该盲区。
    private final TimerGame _bedClickLogTimer = new TimerGame(2);
    // 朝向到位容差(度): 只需拦住"朝向被劫持压向正下方"(pitch 差常 >30°),
    // 不必苛求精确到位 —— baritone 逼近会留几度残差, 收太紧会永远等 → 一直换侧。
    private static final double AIM_ALIGN_TOLERANCE_DEG = 20.0;
    // (2026-09-13 硬闸门) 单次睡觉累计拆障方块数上限 (硬闸门): 判据再准也保底"绝不出现大规模挖掘"。
    private int _blockersClearedTotal = 0;
    private static final int SLEEP_MAX_BLOCKERS_CLEARED = 10;

    // ==== (2026-09-24 站位导航超时, 用户: 放床完全僵住 45s+ 需人工干预) ====
    // 现场 20260924-181522 18:18:26-18:19:57: 清地完成后去站位 (-635,74,1291),
    // baritone 反复算不出路径 (anomaly: 60s 内 8 次), GetToBlockTask 本身无任何
    // 超时机制 → bot 原地死等 45s+ 直到人工干预。修: 同一站位目标导航超过 10s
    // 仍没到位 → 判"该区域站位不可达", 弃区重扫 (与 place 3s / foundation 8s
    // 超时同款语义)。区域是 10 格内就近选的, 正常几秒内必到, 10s 足够宽松。
    // (审查补充) 刻意用墙钟而非 TimerGame: TimerGame 冻结期切墙钟推进 (2026-09-03
    // 补丁语义相反), 换它解决不了"暂停也计数"; 暂停(Esc)恢复后最坏多一次无害
    // 重扫, 可接受。
    private BlockPos _standNavRegion;
    private long _standNavStartMs;
    private static final long STAND_NAV_TIMEOUT_MS = 10_000;

    // ==== (2026-09-08 睡床卡死三连修, 用户: 瞄准避开了村民却被普通方块挡住,
    // 挡路方块永远没被拆掉, 试了好多次最终卡住) ====
    // (a) 拆障保活: 清障 DestroyBlockTask 缓存保活到拆完。旧版每 tick 重进
    //     "清障分支"但下一 tick 就被换侧分支抢切, 拆障任务只活 1 tick,
    //     挡路方块永远拆不掉 (09:34 日志实锤: 反复 Destroy 同一方块 8 轮)。
    private Task _blockerClearTask;
    private BlockPos _blockerClearPos;
    private long _blockerClearStartMs;
    // 同一挡路方块连续拆不动的次数 (任务结束方块还在/超时), >=2 视为拆不动
    private int _blockerClearFails;
    private boolean _sleepGaveUp;
    // 触发层冷却 (静态: SleepThroughNightTask 每 tick new 实例, 实例字段会随
    // 重建丢失)。放弃后 BeatMinecraft2Task 在冷却内不再派发睡觉任务, 让位主线
    // (机制同收炉侧线整体冷却 6eedb369 — 冷却必须加在触发条件层, 否则重建绕过兜底)。
    private static volatile long s_sleepGiveUpUntilMs = 0;
    private static final long SLEEP_GIVEUP_COOLDOWN_MS = 30_000;
    private static final long BLOCKER_CLEAR_TIMEOUT_MS = 8_000;

    // (2026-09-10 分级睡床, 用户: "人看到 48m 外有床会直接走过去睡, 不是拆了扛
    // 回来") 30 格内无可睡床时, 30~96 格内感知到床 → 直接导航过去睡。static 同上:
    // SleepThroughNightTask 每 tick new 实例, 实例字段会被重建绕过。
    private static final long MID_RANGE_SLEEP_NAV_TIMEOUT_MS = 60_000;
    private static final long MID_RANGE_SLEEP_GIVEUP_COOLDOWN_MS = 30_000;
    private static volatile long s_midRangeSleepGiveUpUntilMs = 0;
    private static BlockPos s_midRangeSleepTarget = null;
    private static long s_midRangeSleepNavStartMs = 0;

    public static boolean isSleepGiveUpCooldown() {
        return System.currentTimeMillis() < s_sleepGiveUpUntilMs;
    }

    /**
     * 开始拆一个挡路方块; 连续 2 次拆不动则返回 null (不再重试, 走换侧/放弃)。
     * (2026-09-13 硬闸门) 单次睡觉累计拆障数上限 —— 判据再准也要保底"绝不出现
     * 大规模挖掘"。超限即认定"这张床从当前站位根本够不到", 直接放弃本次 + 触发层
     * 冷却, 而不是继续挖下去 (用户诉求: 睡前不再把床旁挖穿)。
     */
    private Task startBlockerClear(BlockPos blocker) {
        if (_blockerClearFails >= 2) return null;
        if (_blockersClearedTotal >= SLEEP_MAX_BLOCKERS_CLEARED) {
            s_sleepGiveUpUntilMs = System.currentTimeMillis() + SLEEP_GIVEUP_COOLDOWN_MS;
            _sleepGaveUp = true;
            String m = "[Diag-bed] sleep give-up: blocker-clear budget exhausted ("
                    + _blockersClearedTotal + "/" + SLEEP_MAX_BLOCKERS_CLEARED
                    + "), cooldown " + (SLEEP_GIVEUP_COOLDOWN_MS / 1000) + "s";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return null;
        }
        _blockersClearedTotal++;
        _blockerClearTask = new DestroyBlockTask(blocker);
        _blockerClearPos = blocker;
        _blockerClearStartMs = System.currentTimeMillis();
        return _blockerClearTask;
    }

    /**
     * (2026-09-13 修复B) 权威瞄准射线: 从眼位朝床头瞄点做方块射线
     * (OUTLINE + 不含流体, 与 MC 准心同口径), 长度取"眼位→瞄点距离 + 0.5 格"
     * 余量, 避免瞄点恰好落在方块表面时被浮点边界判成落空。
     *
     * 用它取代 crosshairTarget 作为"该不该破坏方块"的判据。crosshairTarget 是
     * 渲染帧快照: 转向未完成时是旧朝向, 且落空返回 type=MISS 的 BlockHitResult,
     * 与真命中的 BLOCK 同型不可区分 —— 这正是 9dbeb779 引入的误判源。
     * 本射线的方向只由 aimPoint 决定, 与"当前转到哪了"无关, 因此天然不受
     * 转向时序影响, 也天然满足"只有挡在瞄准线上的方块才可拆"的限权要求。
     *
     * @return 命中(可能为 MISS 类型); 眼位与瞄点重合(极罕见)时返回 null。
     */
    private HitResult raycastToAimPoint(AltoClef mod, Vec3d aimPoint) {
        Vec3d eye = mod.getPlayer().getEyePos();
        Vec3d delta = aimPoint.subtract(eye);
        double len = delta.length();
        if (len < 1.0E-4) return null;
        Vec3d end = eye.add(delta.multiply((len + 0.5) / len));
        return mod.getWorld().raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, mod.getPlayer()));
    }

    /**
     * (2026-09-28 根修: 点床判据与 MC 同源) MC 当帧准星是否真的落在【床】方块上。
     *
     * 为什么必须换成这个判据, 而不是自有射线 (raycastToAimPoint):
     * 现场 20260928-145156 —— bot 副手盾, "点床"变成【持续举盾不放】卡满 30s,
     * 用户手动停止。vanilla `MinecraftClient.doItemUse()` 的分支是:
     *   准星命中方块 → interactBlock(MAIN_HAND) → 主手空手时 fallback 到
     *                 blockState.onUse → 床的 onUse → 正常上床
     *                 (副手盾轮不到 —— 这正是"人类拿盾点床也没事"的原因);
     *   准星 MISS   → 掉到 for(Hand) 循环 → interactItem(MAIN) PASS →
     *                 interactItem(OFF_HAND) → 盾牌 use() → **举盾**。
     * 而本任务原先用自有射线判"命中床 → tryPress": 该射线方向只由 aimPoint
     * 决定、与当前朝向无关; 在 AIM_ALIGN_TOLERANCE_DEG=20° 的粗容差下, MC 准星
     * 完全可能落在 MISS —— 于是每一次"点床"实际都在举盾。两套判据不同源 = 根因。
     * 改用同源判据后, 只要点了就必然进 interactBlock 分支, 副手盾永不介入。
     */
    private boolean isCrosshairOnBed(AltoClef mod) {
        HitResult target = MinecraftClient.getInstance().crosshairTarget;
        if (!(target instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return mod.getWorld().getBlockState(bhr.getBlockPos()).getBlock() instanceof BedBlock;
    }

    public PlaceBedAndSetSpawnTask() {

    }

    public PlaceBedAndSetSpawnTask stayInBed() {
        _stayInBed = true;
        return this;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _currentBedRegion = null;
        _interactClearedVeg = false;
        _structureTimer = null;
        // (2026-09-28) 失败位置拉黑表随轮次清空 —— 实例复用(跨夜晚/跨 sleep 请求)
        // 时不继承上一轮"放弃过的位置", 否则新地形下会白跳过一批可用点。
        _failedBedRegions.clear();
        // (2026-09-08) 新一轮睡觉尝试: 重置拆障保活/放弃态
        // (2026-09-24 换侧机制删除, 尝试计数随之移除)
        _blockerClearTask = null;
        _blockerClearPos = null;
        _blockerClearFails = 0;
        _sleepGaveUp = false;
        _blockersClearedTotal = 0;
        // (2026-09-24 站位导航计时随实例归零)
        _standNavRegion = null;
        _standNavStartMs = 0;

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
            // (2026-09-10 根因修复) 删除上游 "Don't ever break beds" — 无条件禁挖
            // 任何床。本任务在没有可睡床时会自己派发"拿床"(Getting a bed first →
            // CollectBedTask → 挖 96 格内现成床), 该谓词把所有床判 avoidBreaking
            // → MineAndCollect 内层 canBreak 恒 false (Diag-blind unbreakable=22
            // 全部 avoidBreaking, 18:04/22:58/23:23 三场次站桩实锤) → valid=0 →
            // "Waiting for calculations (wandering)" 站桩整晚。自放床与身边床
            // 仍有双层保护: 上方 BED_BOTTOM_PLATFORM 平台 + CollectBedTask
            // .markProtectedBedsUnreachable (8 格内床拉黑 + 自放床 3x3)。
            return false;
        });

        mod.getBlockTracker().trackBlock(BEDS);

        _spawnSet = false;
        _gaveUpSetSpawn = false;
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
            // (2026-09-21 白天右键即设点, 用户指定 + 反编译实锤) 1.18.2
            // ServerPlayerEntity.trySleep: setSpawnPoint(sendMessage=true) 在
            // isDay 检查【之前】—— 白天右键床重生点已设置, 随后才提示"只能在
            // 晚上睡觉"。该消息走动作栏 = GameOverlayEvent (ChatMessageEvent
            // 只挂 ChatHudListener, 对动作栏永不触发 —— 旧实现收不到, 只能靠
            // 12s 超时放弃)。收到即视为设点完成, 不需要睡进去。
            if (evt.message.contains("Respawn point set")) {
                _spawnSet = true;
                _inBedTimer.reset();
                return;
            }
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
        _gaveUpSetSpawn = false;
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

        // (2026-09-08) 已放弃本次睡觉 → 不再做任何动作; 触发层依静态冷却到期后
        // 重新派发时 onStart 会重置本标志。
        if (_sleepGaveUp) {
            setDebugState("Sleep give-up cooldown (waiting at trigger layer)");
            return null;
        }

        // We cannot do this anywhere but the overworld.
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to the overworld first.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // (2026-09-08 拆障保活, 睡床/放床两条流程共用) 正在拆挡路方块/植被时,
        // 持续执行到拆完为止。父任务 onTick 每 tick 重跑: 不缓存任务实例的话,
        // 上一 tick 返回的 DestroyBlockTask 下一 tick 就被瞄准/放床/换侧分支
        // 抢切, 只活 1 tick, 挡路方块永远拆不掉 (09:34 日志实锤: 反复 Destroy
        // 同一方块 8 轮, 用户实锤"根本没有拆掉那方块")。
        if (_blockerClearTask != null) {
            boolean timedOut = System.currentTimeMillis() - _blockerClearStartMs > BLOCKER_CLEAR_TIMEOUT_MS;
            if (timedOut || _blockerClearTask.isFinished(mod)) {
                // 任务结束: 方块还在(拆不动/超时)计一次失败, 连续 2 次后不再重试
                if (timedOut) {
                    adris.altoclef.util.helpers.TimeoutLog.hit("PlaceBedAndSetSpawnTask.blockerClear", "8s clearing blocker without success");
                    _blockerClearFails = 2;
                } else if (_blockerClearPos != null && !WorldHelper.isAir(mod, _blockerClearPos)) {
                    _blockerClearFails++;
                }
                _blockerClearTask = null;
                _blockerClearPos = null;
            } else {
                setDebugState("Sleep/place blocked -> destroying blocker " + _blockerClearPos.toShortString());
                return _blockerClearTask;
            }
        }

        Screen screen = MinecraftClient.getInstance().currentScreen;
        // (2026-09-06 意外交易界面, 用户: 右键瞄床时准心落到村民实体框上会打开
        // 交易界面而不是睡床) 出现村民交易界面 = 上一 tick 的右键点到了村民,
        // 立即关闭 (closeHandledScreen 会发 close 包, 不会假死在 GUI 里)。
        if (screen instanceof MerchantScreen) {
            mod.getPlayer().closeHandledScreen();
            String m = "[Diag-bed] accidental trade screen opened (right-click hit villager), closing it";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return null;
        }
        // (2026-09-24 睡成判定修复, 用户实测"bot 其实睡成功了, 但 bot 不知道自己
        // 睡成功了, 睡觉期间聊天栏日志还一直刷屏") isSleeping 或 SleepingChatScreen
        // = 已睡进去。旧版 _stayInBed(游牧)时直接跳过 SleepingChatScreen 分支 →
        // 睡着后 onTick 继续跑睡床流程: BedDebug 每 tick 刷屏 + lookAt/右键 +
        // 3s 超时换侧打日志。现在统一: 睡着 → _wasSleeping/_spawnSet 置位;
        // 游牧模式留在床上等天亮 (醒来后 isFinished 自然收尾 + 拆床分支回收床),
        // 非游牧关屏即走。
        if (mod.getPlayer().isSleeping() || screen instanceof SleepingChatScreen) {
            _wasSleeping = true;
            _spawnSet = true;
            _inBedTimer.reset();
            if (_stayInBed) {
                setDebugState("Sleeping through night...");
                return null;
            }
            _progressChecker.reset();
            screen.close(); // may be chat empty ??
            return null;
        }

        // (2026-08-26 用户修正: 必须右键点一下床才设重生点, 不能跳过) 进入
        // "去睡床"分支时启动超时 (_sleepTimeoutStarted 独立于 overlay 消息):
        // 若 12 秒内既没出 "Respawn point set" 消息也没真正睡进去 (白天不能睡 /
        // 右键没生效 / 站错位), 就收尾继续走 — 不再和床死循环交互。已睡进去
        // (isSleeping) 时不触发, 让夜晚正常睡过。
        // (2026-09-21) 右键命中床已直接置 _spawnSet (用户"右键点了就行") →
        // 超时放弃只兜底"从没点到床"的场景。
        // (2026-09-24 换侧机制删除) 游牧模式原依赖"全侧试完"退出, 现统一走本
        // 大超时: 到点 → 触发层 30s 冷却 + _sleepGaveUp 干净收尾 (不再假装
        // _spawnSet=true, 防拆床分支误拆感知到的别人的床)。
        if (_sleepTimeoutStarted && !_spawnSet && !mod.getPlayer().isSleeping()) {
            if (_bedInteractTimeout.elapsed()) {
                Debug.logMessage("Failed to set spawn via sleeping within timeout, moving on.");
                s_sleepGiveUpUntilMs = System.currentTimeMillis() + SLEEP_GIVEUP_COOLDOWN_MS;
                _sleepGaveUp = true;
                _gaveUpSetSpawn = true;
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
        // (2026-09-11 门控放宽) 旧版只在"世界上任何地方都没床"时才扫自己刚放的
        // 床并注册。但别处 (如 120m 外村庄) 有床 → anyBed=true → 直接跳过 →
        // 脚边刚放的那张永远进不了方块缓存 → nearbyBed 判假 → "Getting a bed
        // first" 死循环 (用户 20260911-192550 复现 3 次: knownBeds=20 全是远处,
        // 脚边那张始终不在 tracker)。追加条件: 自放床已记录但不在缓存里 → 同样
        // 扫一次并注册。容差 3 格: 床占两格, 记忆点与缓存点可能差 1。
        boolean selfBedNotInCache = false;
        BlockPos selfBedRecord = mod.getLastSelfPlacedBed();
        if (selfBedRecord != null) {
            selfBedNotInCache = mod.getBlockTracker()
                    .getKnownLocations(ItemHelper.itemsToBlocks(ItemHelper.BED)).stream()
                    .noneMatch(p -> p.isWithinDistance(selfBedRecord, 3));
        }
        if (!anyBed || selfBedNotInCache) {
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
                // (2026-09-11 同步注册, 同 840 行) 只记 "自己放的床" 不够 ——
                // 358 行算出的 anyBed 以及下面 407 行的 getNearestTracking 都读
                // 方块缓存, 刚放的床不在缓存里 → anyBed=false/查不到, 直接落到
                // "Getting a bed first" 去合第二张床。这里顺手注册并纠正 anyBed,
                // 让本轮判定立刻看到它。
                mod.getBlockTracker().addBlock(mod.getWorld().getBlockState(placed).getBlock(), placed);
                anyBed = true;
                Debug.logMessage("[Diag-bed] sleep target set to just-placed bed at " + placed.toShortString());
                Debug.logToFile("altoclef_diag.log", "[Diag-bed] sleep target set to just-placed bed at " + placed.toShortString());
            }
        }
        BlockPos nearestBed = anyBed ? mod.getBlockTracker().getNearestTracking(BEDS).orElse(null) : null;
        boolean nearbyBed = nearestBed != null
                && nearestBed.isWithinDistance(mod.getPlayer().getPos(), 30);
        // (2026-09-06 就地合床修复, 用户: 夜里背包有 3 羊毛+原木可做床, 却跑去
        // 100 格外村庄床睡) 旧判定 shouldGoToBed = anyBed && (!hasBedItem ||
        // nearbyBed): 只要 BlockTracker 缓存里有任何一张床(无论多远)且背包没床
        // 成品, 就无条件走去睡 —— nearbyBed(30 格) 约束对"没床成品"的情形完全
        // 失效, 材料明明够也不就地合成。改为: 只有 30 格内真有床才去睡; 否则
        // 背包有床就地放一张, 没床走下方 "Getting a bed first" → CollectBedTask
        // (96 格内有可感知床才去拆, 超过则杀羊/羊毛+木板合床)。
        boolean shouldGoToBed = anyBed && nearbyBed;
        // (2026-09-06 v3 根治"隔着方块点床", 用户: bot 会走过去尝试隔方块点床,
        // 撞了 LOS 限制 12s 超时才离开) 出发前就地预判: 30 格内的床若从任何站
        // 侧眼位都无法用方块射线点到 (全部隔着方块), 直接视为没有可睡床 — 不
        // 再走过去撞墙等超时, 走下方就地放床/收集床。结果 1s 缓存, 不每 tick 重算。
        if (shouldGoToBed && findInteractableBedNear(mod, 30) == null) {
            if (_noClickableBedLogTimer.elapsed()) {
                _noClickableBedLogTimer.reset();
                String m = "[Diag-bed] all beds within 30 are block-blocked (no clear click line from any stand side), not walking over";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
            shouldGoToBed = false;
        }
        if (anyBed && !shouldGoToBed && hasBedItem) {
            setDebugState("Has bed item, but nearest bed is far (" + (nearestBed == null ? "?" : nearestBed.getSquaredDistance(mod.getPlayer().getBlockPos())) + ") — placing bed nearby");
        }
        // (2026-09-24 刷屏治理) 每 tick 一条 BedDebug 是"睡觉期间聊天栏一直刷屏"
        // 的主源之一, 加 2s 节流。
        if (anyBed && _bedDebugLogTimer.elapsed()) {
            _bedDebugLogTimer.reset();
            Debug.logMessage("[BedDebug] anyFound(BEDS)=TRUE; knownBeds=" + mod.getBlockTracker().getKnownLocations(BEDS).size() + " player=" + mod.getPlayer().getBlockPos().toShortString());
        }
        if (shouldGoToBed) {
            // (2026-09-24 换侧机制删除) 首次进入分支启动大超时 12s, 不分 _stayInBed
            // 统一启动 —— 游牧模式原依赖"全侧试完"退出, 现在它是唯一兜底。
            if (!_sleepTimeoutStarted) {
                _sleepTimeoutStarted = true;
                _bedInteractTimeout.reset();
            }
            setDebugState("Going to bed (walk over & right-click the bed)...");

            // ① 挑床: 优先刚放的 (避免 BlockTracker 扫描滞后选到远处村庄床), 没有才取最近
            if (_justPlacedBed != null
                    && !(mod.getWorld().getBlockState(_justPlacedBed).getBlock() instanceof BedBlock)) {
                BlockPos found = findPlacedBedNearRegion(mod, _currentBedRegion);
                _justPlacedBed = (found != null) ? found : null;
            }
            BlockPos toSleepIn = _justPlacedBed;
            if (toSleepIn == null || !(mod.getWorld().getBlockState(toSleepIn).getBlock() instanceof BedBlock)) {
                // (2026-09-06 v3) 取最近一张【站侧射线可达】的床, 不选隔着方块的床,
                // 避免走过去才发现点不到。
                toSleepIn = findInteractableBedNear(mod, 30);
            }
            if (toSleepIn == null) {
                // shouldn't happen (shouldGoToBed requires anyBed=true); 退而重扫
                _justPlacedBed = null;
                return null;
            }

            // ② 床头 (瞄点/设点都以床头为准)
            _bedForSpawnPoint = WorldHelper.getBedHead(mod, toSleepIn);
            if (_bedForSpawnPoint == null) _bedForSpawnPoint = toSleepIn;

            // ③ (2026-09-24 用户改革"上床就右键对准就好, 不需要特地站侧位") 不再按
            // 床头 FACING 挑站侧逐个试 (现场铁证: side0 3s 全耗在对齐上点击 0 次;
            // side1 yawOff 155°→179.8° 越转越偏, 天亮被主线切走)。走到床边
            // (距床头中心 ≤ 4.5 格 = 方块手长) 即可, 任何方位都行。
            double distSq = mod.getPlayer().getPos().squaredDistanceTo(
                    _bedForSpawnPoint.getX() + 0.5,
                    _bedForSpawnPoint.getY() + 0.28,
                    _bedForSpawnPoint.getZ() + 0.5);
            if (distSq > 4.5 * 4.5) {
                return new GetToBlockTask(toSleepIn);
            }
            BlockPos pp = mod.getPlayer().getBlockPos();
            // 床头格中心瞄点 (薄床碰撞箱 y=9/16, 取 y+0.28 中点偏下)。不再按站侧
            // 内缩 0.45 —— 玩家从任意方位瞄它, 射线命中床任一半都算数。
            Vec3d aimPoint = new Vec3d(
                    _bedForSpawnPoint.getX() + 0.5,
                    _bedForSpawnPoint.getY() + 0.28,
                    _bedForSpawnPoint.getZ() + 0.5);

            // ④ 已在可点距离: 停掉 Baritone 路径 → lookAt 瞄点 → 解除潜行 + 空手
            // (潜行劫持会把方块右键变"用物品"不放床/不睡, 双通道释放; 空手避免
            // 对着床按右键先吃东西/放方块)。
            ICustomGoalProcess proc = mod.getClientBaritone().getCustomGoalProcess();
            if (proc.isActive()) proc.onLostControl();
            mod.getInputControls().release(Input.SNEAK);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
            mod.getSlotHandler().forceDeequipRightClickableItem();
            // (2026-09-28 点床举盾矛盾) forceDeequipRightClickableItem 只卸主手,
            // 副手(OFFHAND slot 45)的盾从未被碰 → 点床右键走 interactItem(OFF_HAND)
            // 分支时仍会举盾。这里补卸副手盾, 根除"点床=持续举盾"矛盾。
            mod.getSlotHandler().forceDeequipOffhand();
            LookHelper.lookAt(mod, aimPoint);

                // ===== (2026-09-13 修复A: 瞄准与判定解耦) =====
                // LookHelper.lookAt 走 baritone LookBehavior.updateTarget = 带最大转速
                // 限制的平滑转向, 需多 tick 才到位; 而 crosshairTarget 是每帧按玩家
                // 【真实当前朝向】raycast 的快照 → 同 tick 读到的必然是转向前的旧朝向
                // (日志铁证: "switch-side" 与 "blocked" 同毫秒打印)。
                // 朝向未到位 → 本 tick 不点右键、不做任何阻挡判定、绝不派发拆障。
                Rotation aimRot = LookHelper.getLookRotation(mod, aimPoint);
                Rotation curRot = LookHelper.getLookRotation();
                double yawOff = Math.abs(MathHelper.wrapDegrees(curRot.getYaw() - aimRot.getYaw()));
                double pitchOff = Math.abs(curRot.getPitch() - aimRot.getPitch());
                if (yawOff > AIM_ALIGN_TOLERANCE_DEG || pitchOff > AIM_ALIGN_TOLERANCE_DEG) {
                    if (_aimWaitLogTimer.elapsed()) {
                        _aimWaitLogTimer.reset();
                        String m = "[Diag-bed] aim not aligned yet, holding (no click / no blocker judge)"
                                + " yawOff=" + String.format("%.1f", yawOff)
                                + " pitchOff=" + String.format("%.1f", pitchOff)
                                + " (player=" + pp.toShortString() + ")";
                        Debug.logMessage(m);
                        Debug.logToFile("altoclef_diag.log", m);
                    }
                } else if (MinecraftClient.getInstance().crosshairTarget instanceof EntityHitResult) {
                    // (2026-09-06 v2, 用户: 换侧毫无意义, "获取不到实体框吗"—
                    // 能拿到。准心被实体(床上的村民)挡住时直接按实体框重新选
                    // 瞄准点: 在床两半块的顶面/朝玩家侧面网格采样, 找第一个
                    // 【方块射线通畅 + 无实体横插】的点 lookAt 它;
                    // crosshair 下一 tick 更新, 落回床方块即正常点击。
                    Vec3d alt = findUnblockedBedAimPoint(mod, toSleepIn, _bedForSpawnPoint, aimPoint);
                    if (_crosshairBlockedLogTimer.elapsed()) {
                        _crosshairBlockedLogTimer.reset();
                        String m = "[Diag-bed] sleep right-click HELD: crosshair blocked by entity "
                                + ((EntityHitResult) MinecraftClient.getInstance().crosshairTarget).getEntity().getType().getName()
                                + (alt != null ? ", retargeting to " + alt : ", no clear bed point visible")
                                + " (player=" + pp.toShortString() + ")";
                        Debug.logMessage(m);
                        Debug.logToFile("altoclef_diag.log", m);
                    }
                    if (alt != null) {
                        LookHelper.lookAt(mod, alt);
                    }
                } else if (isCrosshairOnBed(mod)) {
                    // ===== (2026-09-28 根修: 点床判据与 MC 同源) =====
                    // 唯一允许点右键的分支 —— 且条件就是"MC 当帧准星真的落在床上"。
                    // 这样 doItemUse() 必然走 interactBlock(MAIN_HAND) 分支(床 onUse
                    // → 上床), 而不是 MISS 分支(interactItem(OFF_HAND) → 副手盾
                    // use() → 举盾)。现场 20260928-145156 铁证: 20° 粗容差下自有
                    // 射线判"命中床"、MC 准星却是 MISS, 于是每 tick 的"点床"都在
                    // 举盾, 卡 30s 后被用户手动停止。
                    // 用 forcePress 而非 tryPress: tryPress 在"键已被别人 hold 按住"
                    // 时产生不了干净的 use 边沿; forcePress 先 release 清残留再短按
                    // (InputControls 里为"不长按反而举盾"写的入口)。
                    //
                    // (2026-09-28 残留风险, 本次未消除) forcePress 仍是**每帧调用**,
                    // 因而仍带 InputControls.tryPress 的"跨帧保持 pressed"特性
                    // (详见该方法注释)。本次止住举盾靠的是"判据与 MC 同源 ⇒ 不再
                    // MISS ⇒ 走 interactBlock 而非 interactItem(OFF_HAND)", 并**没有**
                    // 消除"每帧点击 ⇒ useKey 恒 pressed"。
                    // 残余窗口: 若准星确在床、但 interactBlock 未被 accepted(白天 /
                    // 床被占), vanilla 会 break 到 for(Hand) → interactItem(OFF_HAND)
                    // → 仍可能举盾; 好在 _spawnSet + _inBedTimer(1s) 会收尾, 窗口约 1 秒。
                    // 彻底消除 = 给点床加"点击后 N tick 抑制窗"(让键真正空闲一帧),
                    // 或让 InputControls 提供"保证释放过至少一帧"的点击语义。
                    // (更正: 早期版本此处曾写"EatItemTask.hold 因 SingleTaskChain
                    //  onInterrupt 不 stop 任务而残留" —— 该判断有误, onInterrupt 会调
                    //  _mainTask.interrupt → Task.interrupt 会走 onStop。见 detail/11。)
                    mod.getInputControls().forcePress(Input.CLICK_RIGHT);
                    // (2026-09-28 根修) _inBedTimer 只在【首次设点成功】时 reset 一次,
                    // 不能每帧 reset —— forcePress 每帧调用 ⇒ 旧写法每帧 reset ⇒ 计时器
                    // 永不到 1s elapsed ⇒ isFinished 恒 false ⇒ 白天设完点后卡在"去睡床"
                    // 流程空转几十秒 (现场 latest.log 18:38:35~18:39:25 实锤 50s)。
                    // 用 _spawnSet 从 false→true 的边沿判"首次", 之后不再 reset。
                    if (!_spawnSet) {
                        _inBedTimer.reset();
                    }
                    // (2026-09-21 用户拍板"右键点了就行, 不用等消息") 右键命中床
                    // 即视为设点成功: vanilla 1.18.2 trySleep 里 setSpawnPoint 在
                    // isDay 检查之前, 白天右键床重生点已设置 (动作栏消息只是提示,
                    // mod 侧 GameOverlayEvent 不保证收到 → 旧口径只能 12s 超时
                    // 拉黑, 还让上级把床数目标虚增 +1 → 误判缺床回地表)。
                    // _inBedTimer 1s 后 isFinished 干净收尾; 夜晚真睡进去走
                    // SleepingChatScreen 分支语义一致。
                    _spawnSet = true;
                    if (_bedClickLogTimer.elapsed()) {
                        _bedClickLogTimer.reset();
                        String m = "[Diag-bed] sleep click: crosshair ON BED ("
                                + WorldHelper.describeCrosshair(mod)
                                + ") yawOff=" + String.format("%.1f", yawOff)
                                + " pitchOff=" + String.format("%.1f", pitchOff)
                                + " (player=" + pp.toShortString() + ")";
                        Debug.logMessage(m);
                        Debug.logToFile("altoclef_diag.log", m);
                    }
                } else {
                    // ===== (2026-09-13 修复B+C: 判据换源 + MISS 单列) =====
                    // 不再用 crosshairTarget 决定"该不该破坏方块"。旧版 (9dbeb779:582)
                    // 只判 `instanceof BlockHitResult` 就当"被方块挡住" → MC 射线落空
                    // 返回的 type=MISS BlockHitResult 也被当成挡路 (15:41:03 / 16:03:04
                    // 两条 "blocked by block 空气 … destroying it" 铁证), 且同 tick 读到
                    // 的是转向前的旧朝向 → 误判后 DestroyBlockTask 每 tick lookAt 劫持
                    // 朝向, 竖直挖穿自锁。
                    // 拆障判据(只管拆障)维持"眼位 → 床头瞄点"的方块射线(OUTLINE):
                    //   命中床      → 朝向还差一点, 不点不拆, 继续让 lookAt 逼近
                    //   命中非床方块 → 确实挡在这条射线上, 拆它合法(限权内建, 无需额外校验)
                    //   落空/非方块 → 瞄点几何不可达, 不点不拆
                    // (2026-09-28 注: "点右键"已移交上方 isCrosshairOnBed 同源分支独占 ——
                    //  这里再点一次就会重新引入"自有射线说能点、MC 准星 MISS → 举盾"。)
                    HitResult probe = raycastToAimPoint(mod, aimPoint);
                    if (probe instanceof BlockHitResult pbhr && pbhr.getType() == HitResult.Type.BLOCK) {
                        BlockPos hitPos = pbhr.getBlockPos();
                        boolean hitIsBed = mod.getWorld().getBlockState(hitPos).getBlock() instanceof BedBlock;
                        if (hitIsBed) {
                            // (2026-09-28) 自有射线命中床、MC 准星却没落床 = 朝向还差一点。
                            // 点床已被上方同源分支独占: 这里既不点(点了必是 MISS → 副手盾
                            // 举盾)也不拆(床不能拆), 只让 648 行的 lookAt 继续逼近。
                            if (_bedClickLogTimer.elapsed()) {
                                _bedClickLogTimer.reset();
                                String m = "[Diag-bed] aim ray ON BED but crosshair off it, keep aligning"
                                        + " crosshair=" + WorldHelper.describeCrosshair(mod)
                                        + " yawOff=" + String.format("%.1f", yawOff)
                                        + " pitchOff=" + String.format("%.1f", pitchOff)
                                        + " (player=" + pp.toShortString() + ")";
                                Debug.logMessage(m);
                                Debug.logToFile("altoclef_diag.log", m);
                            }
                        } else if (WorldHelper.canBreak(mod, hitPos)
                                && mod.getWorld().getBlockEntity(hitPos) == null) {
                            // (2026-09-24 容器守卫) 瞄准射线上的容器方块 (熔炉/箱子) 不拆,
                            // 走下方重取瞄点/换侧路径。见 isGoodAsBorder 同款守卫注释。
                            Task clearTask = startBlockerClear(hitPos);
                            if (_crosshairBlockedLogTimer.elapsed()) {
                                _crosshairBlockedLogTimer.reset();
                                String m = "[Diag-bed] sleep right-click HELD: aim ray blocked by block "
                                        + mod.getWorld().getBlockState(hitPos).getBlock().getName().getString()
                                        + " at " + hitPos.toShortString()
                                        + (clearTask != null ? ", destroying it" : ", unbreakable/failed twice, holding click")
                                        + " (player=" + pp.toShortString() + ")";
                                Debug.logMessage(m);
                                Debug.logToFile("altoclef_diag.log", m);
                            }
                            if (clearTask != null) {
                                return clearTask;
                            }
                            // 拆不动 → 重取瞄点碰运气, 否则干等 3s 换侧
                            Vec3d alt = findUnblockedBedAimPoint(mod, toSleepIn, _bedForSpawnPoint, aimPoint);
                            if (alt != null) LookHelper.lookAt(mod, alt);
                        } else {
                            Vec3d alt = findUnblockedBedAimPoint(mod, toSleepIn, _bedForSpawnPoint, aimPoint);
                            if (_crosshairBlockedLogTimer.elapsed()) {
                                _crosshairBlockedLogTimer.reset();
                                String m = "[Diag-bed] sleep right-click HELD: aim ray blocked by container/unbreakable block "
                                        + mod.getWorld().getBlockState(hitPos).getBlock().getName().getString()
                                        + " at " + hitPos.toShortString()
                                        + (alt != null ? ", retargeting to " + alt : ", no clear bed point from this side")
                                        + " (player=" + pp.toShortString() + ")";
                                Debug.logMessage(m);
                                Debug.logToFile("altoclef_diag.log", m);
                            }
                            if (alt != null) LookHelper.lookAt(mod, alt);
                        }
                    } else {
                        // (修复C) 射线落空 / 未命中方块: 不点右键、不拆障。
                        // 旧版走到这里会直接 tryPress(CLICK_RIGHT) = 纯盲点(朝向不对时
                        // 点了也白点), 且把"落空"这一事实掩盖成"可以点"。静默等对齐
                        // (lookAt 逐 tick 逼近, 落空多为瞬态) —— 既不误拆, 也不假装能点。
                        if (_crosshairBlockedLogTimer.elapsed()) {
                            _crosshairBlockedLogTimer.reset();
                            String m = "[Diag-bed] aim ray miss (nothing between eye and bed aim point), holding"
                                    + " (player=" + pp.toShortString() + ")";
                            Debug.logMessage(m);
                            Debug.logToFile("altoclef_diag.log", m);
                        }
                    }
                }
            // (2026-09-24 换侧机制删除) 旧 ⑥ "3s 换侧/全侧试完放弃"整段移除:
            // 没睡成的兜底只剩顶部 12s 大超时 → 触发层 30s 冷却 + _sleepGaveUp。
            return null;
        }

        // (2026-09-10 分级睡床) 30 格内无可睡床(shouldGoToBed=false)且背包没床成品:
        // 30~96 格内感知到床 → 直接导航过去睡。走到 30 格内自然进入上方正常睡床流程
        // (站侧预判/瞄点/超时全套复用); 挖床只留给睡不了的情形 — 挖床同样要走 48m,
        // 直接睡省"挖+背回+放"三步。60s 导航超时 → 触发层 static 冷却 30s(6eedb369:
        // 实例冷却会被任务重建绕过) → 回落下方拿床路径(挖床/做床), 不站桩。
        if (!hasBedItem && nearestBed != null
                && System.currentTimeMillis() >= s_midRangeSleepGiveUpUntilMs) {
            boolean midRangeBed = !nearestBed.isWithinDistance(mod.getPlayer().getPos(), 30)
                    && nearestBed.isWithinDistance(mod.getPlayer().getPos(), 96);
            if (midRangeBed) {
                if (!nearestBed.equals(s_midRangeSleepTarget)) {
                    s_midRangeSleepTarget = nearestBed;
                    s_midRangeSleepNavStartMs = System.currentTimeMillis();
                    String m = "[Diag-bed] mid-range sleep: walking to bed "
                            + nearestBed.toShortString() + " ("
                            + (int) Math.sqrt(nearestBed.getSquaredDistance(mod.getPlayer().getBlockPos()))
                            + "m) — sleep-first, mine only if unreachable";
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                }
                if (System.currentTimeMillis() - s_midRangeSleepNavStartMs > MID_RANGE_SLEEP_NAV_TIMEOUT_MS) {
                    s_midRangeSleepGiveUpUntilMs = System.currentTimeMillis() + MID_RANGE_SLEEP_GIVEUP_COOLDOWN_MS;
                    s_midRangeSleepTarget = null;
                    adris.altoclef.util.helpers.TimeoutLog.hit("PlaceBedAndSetSpawnTask.midRangeSleepNav", "60s sleep nav without arrival, cooling down");
                    String m = "[Diag-bed] mid-range sleep nav timeout " + (MID_RANGE_SLEEP_NAV_TIMEOUT_MS / 1000)
                            + "s, cooldown " + (MID_RANGE_SLEEP_GIVEUP_COOLDOWN_MS / 1000)
                            + "s -> getting a bed instead (mine/craft)";
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                    return null;
                }
                setDebugState("Bed within 96 but >30 — walking over to sleep it (sleep-first)");
                return new GetToBlockTask(nearestBed);
            }
            s_midRangeSleepTarget = null;
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
            // (2026-09-28 根修, 事故 20260928-130354) 旧实现是
            //     return new TimeoutWanderTask();      ← 无参 = Float.POSITIVE_INFINITY
            // 单段 128 格、目标还可高出 20+ 格: 现场 baritone 目标 (-154,96,-51),
            // 即玩家 89 格外 / 高 23 格, bot 顺着该路径起跳爬到 y=74 后回不到站位,
            // GetToBlockTask 死等满 10s → STAND NAV TIMEOUT → 弃区重扫, 整轮报废。
            // 而下游 locateBedRegion 只扫 SCAN_RANGE = 10 格 —— 走得越远越选不中,
            // "无限距离让位"与"10 格内选位"逻辑自相矛盾。改用字段里那把有限游荡
            // (4 格起), 目标稳定且留在扫描半径的量级内。
            // 同时 chat 侧不再每 tick 刷 "diverting to WANDER"(一次失败刷几十条),
            // 只保留 diag 文件记录; 状态仍走 setDebugState 给 HUD 显示。
            String m = "[Diag-bed] diverting to WANDER instead of sleeping. stayInBed=" + _stayInBed
                    + " anyBed=" + anyBed
                    + " hasBedItem=" + mod.getItemStorage().hasItem(ItemHelper.BED)
                    + " player=" + mod.getPlayer().getBlockPos().toShortString();
            Debug.logToFile("altoclef_diag.log", m);
        _placeAttemptTimer = null;
            return _wanderTask;
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
        // (2026-09-24 用户改革"不需要清理那么大") 清理范围从 Z=-1..1 两侧外扩
        // 18 格 收缩到 footprint 两列 (dx=0..1, dy=0..1, dz=0) 内【床占不了】的格 ——
        // 上方有方块才需要清, 床两侧不再清。
        // (2026-09-28 根修) 判定从 WorldHelper.isSolid 改为 bedCellNeedsClear:
        // 竹子这类不可替换的非实心方块同样要清, 否则选区收下它却没人清 = 死结
        // (事故 20260928-114406, 见 bedCellNeedsClear 注释)。
        for (int dx = 0; dx < BED_CLEAR_SIZE.getX(); ++dx) {
            for (int dy = 0; dy < BED_CLEAR_SIZE.getY(); ++dy) {
                BlockPos toClear = _currentBedRegion.add(dx, dy, 0);
                if (bedCellNeedsClear(mod, toClear)) {
                    // (2026-09-24 容器守卫) 运行期落进区域的容器方块 (如合成链放的
                    // 合成台) 不拆 —— 跳过继续清别的格, 拆不动就靠下方 3s place
                    // timeout 换区域。见 isGoodAsBorder 同款守卫注释 (现场铁律)。
                    if (mod.getWorld().getBlockEntity(toClear) != null) continue;
                    // (2026-09-28 根修) 挖不动的格不要占住 _currentBreak 反复重试。
                    if (!WorldHelper.canBreak(mod, toClear)) continue;
                    _currentBreak = toClear;
                    break outer;
                }
            }
        }

        if (_currentStructure != null) {
            if (WorldHelper.isSolid(mod, _currentStructure)) {
                _currentStructure = null;
                _structureTimer = null;
            } else {
                // (2026-09-16 铺地基超时换区域) 见 _structureTimer 声明处注释。
                // 悬空区域(区域下方不实心)的平台 baritone 经常算不出放置方案 →
                // builder 空转、PlaceBlockTask 每 2s 超时一轮。这里独立计时:
                // STRUCTURE_TIMEOUT_SECONDS 内平台没变成实心 → 抛弃当前区域重扫,
                // 让清理/站位/放床有机会在别的位置执行。
                if (_structureTimer == null) {
                    _structureTimer = new TimerGame(STRUCTURE_TIMEOUT_SECONDS);
                    _structureTimer.reset();
                }
                if (_structureTimer.elapsed()) {
                    String m = "[Diag-bed] FOUNDATION TIMEOUT after " + STRUCTURE_TIMEOUT_SECONDS + "s building "
                            + _currentStructure.toShortString() + " (region=" + _currentBedRegion.toShortString()
                            + "), abandoning region and rescanning (platform unplaceable: floating / no attach face).";
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                    adris.altoclef.util.helpers.TimeoutLog.hit("PlaceBedAndSetSpawnTask.foundation",
                            STRUCTURE_TIMEOUT_SECONDS + "s building bed foundation, rescanning region");
                    if (_currentBedRegion != null) _failedBedRegions.add(_currentBedRegion);
                    _currentStructure = null;
                    _structureTimer = null;
                    _currentBedRegion = null;
                    // (2026-09-24 审查补充②) 弃区必须连带清残留: _currentBreak 指向
                    // 旧区格子会在新区无可清格时被误执行 (回拆旧区一格);
                    // _standNavRegion 残留会让新区站位导航继承已超时的旧表。
                    _currentBreak = null;
                    _standNavRegion = null;
                    _regionScanTimer.reset();
                    _placeAttemptTimer = null;
                    _progressChecker.reset();
                    return null;
                }
                setDebugState("Placing structure for bed");
                _placeAttemptTimer = null;
                return new PlaceStructureBlockTask(_currentStructure);
            }
        }
        if (_currentBreak != null) {
            // (2026-09-28 判据统一, 现场 20260928-152357) 这里必须与赋值端
            // bedCellNeedsClear 同口径 —— 旧写法用 isSolid 复检, 而草丛/竹子/树叶
            // 全都【非实心】, 于是刚被选中的格子下一 tick 立刻被判"不用清了":
            //   :858 bedCellNeedsClear(grass)=true → _currentBreak=草丛
            //   :903 !isSolid(grass)=true          → _currentBreak=null  ← 白选
            // 结果草丛永远留在床格里 → 服务端放置被挡 + 射线先命中草(点不到床格
            // 下方的顶面) → 放置任务"走到能点的位置" 与 站位任务每 2s 互相打断,
            // 同一个 region 空转 28s。
            if (!bedCellNeedsClear(mod, _currentBreak)) {
                _currentBreak = null;
            } else {
                setDebugState("Clearing region for bed");
                _placeAttemptTimer = null;
                return new DestroyBlockTask(_currentBreak);
            }
        }

        BlockPos toStand = _currentBedRegion.add(BED_PLACE_STAND_POS);
        // Our bed region is READY TO BE PLACED
        // (2026-09-28 判据回退, 现场 20260928-140502 实锤回归) 上一版把
        // isStandingWellForBed(碰撞箱不压床格) 当成【到位前置闸门】是错的:
        // baritone 的 GoalBlock 只保证"站在这格方块内", 不保证站位偏哪半边,
        // 于是出现 "stand pos -76,74,20" 与 "player=-76,74,20" 方块坐标完全
        // 相同却判【未到位】→ 派 GetToBlockTask → baritone 认为已到位不动 →
        // 死等 10s STAND NAV TIMEOUT → 弃区。判据比导航目标严 = 死锁。
        // 现回到方块级到位判据; "身体别压床格"改用【放置失败后的后退】兜底
        // (见下方 Scoot backwards 段), 不再阻塞导航。
        if (!mod.getPlayer().getBlockPos().equals(toStand)) {
            _placeAttemptTimer = null;
            // (2026-09-24 站位导航超时) 见字段区注释: baritone 算不出路径时
            // GetToBlockTask 会永久死等, 10s 未到位 → 弃区重扫, 不再僵住。
            if (!toStand.equals(_standNavRegion)) {
                _standNavRegion = toStand;
                _standNavStartMs = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - _standNavStartMs > STAND_NAV_TIMEOUT_MS) {
                String m = "[Diag-bed] STAND NAV TIMEOUT after " + (STAND_NAV_TIMEOUT_MS / 1000)
                        + "s reaching stand pos " + toStand.toShortString()
                        + " (player=" + mod.getPlayer().getBlockPos().toShortString()
                        + "), abandoning region and rescanning.";
                adris.altoclef.util.helpers.TimeoutLog.hit("PlaceBedAndSetSpawnTask.standNav",
                        (STAND_NAV_TIMEOUT_MS / 1000) + "s navigating to bed stand pos, rescanning region");
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
                if (_currentBedRegion != null) _failedBedRegions.add(_currentBedRegion);
                _currentBedRegion = null;
                _standNavRegion = null;
                // (2026-09-24 审查补充②) 弃区连带清残留, 防回拆旧区格子。
                _currentBreak = null;
                _regionScanTimer.reset();
                _placeAttemptTimer = null;
                _progressChecker.reset();
                return null;
            }
            return new GetToBlockTask(toStand);
        }
        _standNavRegion = null;

        // (2026-09-24 用户改革) 旧"12 格植被预清理"整段删除: footprint 内的草/花
        // 是 replaceable, 放床时被床直接替换; 实心障碍由上方清段 (footprint 实心
        // → DestroyBlockTask) 与 3s place 超时兜底。放床路径不再预撸一大片植被。

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
            // (2026-09-11 放床后立刻注册进 BlockTracker) 扫描器有节流, 从"床放进
            // 世界"到"进方块缓存"之间有窗口; 该窗口内 anyBed / nearbyBed /
            // findInteractableBedNear 全部判假 → 判定"没床" → "Getting a bed first"
            // → 合床 → 放下 → 再判没床, 整晚死循环 (用户 20260911-192550 复现 3 次:
            // "Getting a bed first" ↔ "Placing bed... progressing" 一晚 292 次,
            // 0 次 Sleeping/Respawn point set)。床刚在此刻确认放进世界, 顺手直接
            // 注册, 不等扫描周期。注意 addBlock 内部走 blockIsValid (世界方块必须
            // 真是床) 与 unreachable 检查 —— 刚放的床在注册前不在缓存, 故不会被
            // markProtectedBedsUnreachable 误标, 必定通过。
            mod.getBlockTracker().addBlock(mod.getWorld().getBlockState(bedBlock).getBlock(), bedBlock);
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
        // (2026-09-28 计时器不再被横跳清零, 现场 20260928-152357) 旧写法"当前子任务
        // 不是 Interact 就重建"埋了雷: 放置段里 InteractWithBlockTask(放床) 与
        // GetToBlockTask(回站位) 每 2s 互相打断时, 每次轮到 GetToBlock 就把计时器
        // 重置 → 3s 超时【永不 elapsed】 → 清障与弃区两条兜底全失效, 同一个 region
        // 白耗 28s(日志 58 次 placed-check 全 false)。现只在"还没有计时器"时创建
        // (进入放置段 / 换区清障后第一次), 横跳不再重置它。
        if (_placeAttemptTimer == null) {
            _placeAttemptTimer = new TimerGame(3);
            _placeAttemptTimer.reset();
        }
        if (_placeAttemptTimer != null && _placeAttemptTimer.elapsed()) {
            BlockPos saw = findPlacedBedNearRegion(mod, _currentBedRegion);
            if (saw == null) {
                // (2026-09-02 加强, 2026-09-08 保活化) 交互无效(3s 没放上床): 先左键清一遍放床区域
                // 植被再右键重试; 已清过仍失败才换区域 — 不再"站在草里傻放"就换地方。
                // 清障走顶部 keep-alive (旧版 new DestroyBlockTask 每 tick 被抢切只活 1 tick)。
                if (!_interactClearedVeg) {
                    _interactClearedVeg = true;
                    BlockPos veg = findBlockingVegetationAround(mod, _currentBedRegion);
                    if (veg != null) {
                        Task clearTask = startBlockerClear(veg);
                        if (clearTask != null) {
                            _placeAttemptTimer = null;
                            _progressChecker.reset();
                            setDebugState("Place bed failed -> brushing vegetation " + veg.toShortString());
                            return clearTask;
                        }
                        // 拆不动 → 走下方超时换区域
                    }
                }
                String m = "[Diag-bed] PLACE BED TIMEOUT after 3s at region " + (_currentBedRegion == null ? "null" : _currentBedRegion.toShortString())
                        + ", rescanning new region (old goal unreachable / blocked).";
                adris.altoclef.util.helpers.TimeoutLog.hit("PlaceBedAndSetSpawnTask.placeBed", "3s place timeout, rescanning region");
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
                if (_currentBedRegion != null) _failedBedRegions.add(_currentBedRegion);
                _currentBedRegion = null;
                _justPlacedBed = null;
                // (2026-09-24 审查补充②) 同 foundation-timeout: 弃区连带清残留。
                _currentBreak = null;
                _standNavRegion = null;
                _regionScanTimer.reset();
                _placeAttemptTimer = null;
                _progressChecker.reset();
                _interactClearedVeg = false; // 换区域重置"已清障"
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
        // (2026-09-28) 点击已尝试却没放上, 多半是身体压在床格上/贴边 —— 这时才后退。
        // isStandingWellForBed 挪到这里当【兜底判据】(而不是前置闸门, 原因见上方判据
        // 回退注释): 方块级到位已过、身体却仍压着床格时多退一步, 直到站回床格之外。
        // 后退方向恰是 -X(远离床格), 与 bot 看向床格的朝向相反。
        if (thisOrChildSatisfies(task -> {
            if (task instanceof InteractWithBlockTask intr)
                return intr.getClickStatus() == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED;
            return false;
        })) {
            mod.getInputControls().tryPress(Input.MOVE_BACK);
            if (!isStandingWellForBed(mod, toStand)) {
                mod.getInputControls().tryPress(Input.MOVE_BACK);
            }
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
        // (2026-09-08) 放弃态 = 本轮尝试结束, 让上层干净收尾
        if (_sleepGaveUp) return true;
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            Debug.logWarning("Can't place spawnpoint/sleep in a bed unless we're in the overworld!");
            return true;
        }
        // (2026-09-28 进门前设点"点一下就走") 永久设点(_stayInBed=false, 进地狱门
        // NetherBedSpawn / 末地 setSpawnNearPortalTask)时, 右键命中床即 _spawnSet=true
        // 就【立即收尾】—— 不需要睡进去、不需要等 _inBedTimer。现场 latest.log
        // 18:38:35 已设置重生点后却卡在"去睡床"流程空转 50s(直到 18:39:25 用户手动
        // 停): 根因是 _inBedTimer 被点床分支每帧 reset(见 onTick 点床处)永不到
        // elapsed, isFinished 恒 false。永久设点本就不要求"睡进去", 设点成功即走。
        // 游牧(_stayInBed=true)不受影响, 仍走下方拆床 + _inBedTimer 路径。
        if (!_stayInBed && _spawnSet) return true;
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
        return (_spawnSet && !mod.getPlayer().isSleeping() && _inBedTimer.elapsed()) || _gaveUpSetSpawn;
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
     * (2026-09-02 交互失败左键清障) 扫 center 周围 3x2x3 小范围, 找"挡交互"的
     * 植被方块 — 草/花/藤/双植物等非实心非空气非流体可破坏方块。返回首个待清
     * 方块, 无则 null。放床/上床交互无效时用它左键清一遍再右键重试, 人类感:
     * "睡不进去/放不下, 先撸两下草再点"。
     */
    private BlockPos findBlockingVegetationAround(AltoClef mod, BlockPos center) {
        if (center == null) return null;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos p = center.add(dx, dy, dz);
                    // (2026-09-21 床豁免, 145404 实锤) 床碰撞箱半格不算完整固体,
                    // 绕过 isSolid 过滤 + canBreak(床)=true → 刚放的床的另一半
                    // 被当"植被"清掉 (9831,18,-10697 foot 半块被拆 → nearest bed
                    // far(3766) → 重放 → 放/拆循环)。任何一半是床都不碰。
                    if (mod.getWorld().getBlockState(p).getBlock() instanceof BedBlock) continue;
                    // (2026-09-24 审查补充③) 非满格容器 (篝火/漏斗等) 碰撞箱不满格,
                    // 会绕过下方 isSolid 过滤被当"植被"拆掉 —— 与"不拆容器"铁律不一致。
                    if (mod.getWorld().getBlockEntity(p) != null) continue;
                    BlockState bs = mod.getWorld().getBlockState(p);
                    if (WorldHelper.isAir(mod, p)) continue;
                    if (WorldHelper.isSolid(mod, p)) continue;
                    if (!bs.getFluidState().isEmpty()) continue;
                    if (!WorldHelper.canBreak(mod, p)) continue;
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * (2026-08-27 睡完拆床 / 2026-09-11 世界直扫兜底) 床位置附近 (水平 6 格 ×
     * 垂直 32 格) 是否躺着床掉落物 —— 刚撸掉的床。
     * (2026-09-11) 与 BeatMinecraft2Task.bedDropNearby 是同一份逻辑的两处拷贝,
     * 同步修复: 旧实现查 EntityTracker.getClosestItemDrop 只能命中"已落地"的
     * 掉落物 (EntityTracker:403 的 grounded 过滤), 方块刚被挖掉那一 tick 生成的
     * 掉落物必然查不到 → 误判"没有掉落物" → 调用方清记忆丢床。改为直扫世界
     * (含下落中), 判定盒纵向放宽覆盖"从高处坠落"。
     */
    private boolean bedDropNearby(AltoClef mod, BlockPos bedPos) {
        if (bedPos == null) return false;
        ItemTarget bedTarget = new ItemTarget(ItemHelper.BED, 1);
        for (Entity e : mod.getWorld().getEntities()) {
            if (!(e instanceof ItemEntity ie) || !ie.isAlive()) continue;
            if (!bedTarget.matches(ie.getStack().getItem())) continue;
            BlockPos dp = ie.getBlockPos();
            if (Math.abs(dp.getX() - bedPos.getX()) <= 6
                    && Math.abs(dp.getZ() - bedPos.getZ()) <= 6
                    && Math.abs(dp.getY() - bedPos.getY()) <= 32) {
                return true;
            }
        }
        return false;
    }

    /**
     * 严格轮: 只接受"床底 3 格本来就实心"的区域 (不需要搭平台)。
     * 严格轮选不到才退回放宽轮 (允许搭平台, 评分加倍惩罚), 保留在原本地形无处
     * 可放时"自己搭个平台"的老能力。
     */
    private BlockPos locateBedRegion(AltoClef mod, BlockPos origin) {
        BlockPos strict = locateBedRegion(mod, origin, true);
        if (strict != null) return strict;
        return locateBedRegion(mod, origin, false);
    }

    private BlockPos locateBedRegion(AltoClef mod, BlockPos origin, boolean requireSolidFoundation) {
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
                    // (2026-09-28) 失败过的位置不再选 —— 见 _failedBedRegions 注释。
                    if (_failedBedRegions.contains(attemptStandPos)) continue outer;
                    double distance = attemptStandPos.getSquaredDistance(mod.getPlayer().getPos());
                    // 候选的最终评分 >= 原始距离: 原始距离都大于当前最优分就跳过。
                    if (distance > closestDist) continue;
                    // Everything from here on out is checking for a BETTER pos.
                    // (2026-09-24 用户改革"放床只需要一个干净的两格区域, 不需要清理
                    // 那么大") 只验 footprint 两格 × (本体+上方) = 4 格, 每格单验
                    // (isGoodAsBorder); 删除旧 7 邻居 border 扩张检查 (清理范围过大的
                    // 根源之一)。
                    for (int checkX = 0; checkX < BED_CLEAR_SIZE.getX(); ++checkX) {
                        for (int checkY = 0; checkY < BED_CLEAR_SIZE.getY(); ++checkY) {
                            BlockPos checkAsGoodArea = attemptStandPos.add(checkX, checkY, 0);
                            if (!isGoodAsBorder(mod, checkAsGoodArea)) {
                                continue outer;
                            }
                        }
                    }
                    // (2026-09-24 用户"自己不能占着一点, 否则放不下") 站位格 =
                    // footprint 前一格 (BED_PLACE_STAND_POS): 必须可站 —— 非实心、
                    // 非流体、脚下实心 (脚下不实心直接拒: 平台段只搭床底两格,
                    // 不搭站位脚下, 选了也站不上去只会白耗 10s 站位超时)。
                    BlockPos standPos = attemptStandPos.add(BED_PLACE_STAND_POS);
                    if (WorldHelper.isSolid(mod, standPos)) continue outer;
                    if (!mod.getWorld().getBlockState(standPos).getFluidState().isEmpty()) continue outer;
                    if (!WorldHelper.isSolid(mod, standPos.down())) continue outer;
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
                        // (2026-09-16 悬空选点死循环) 严格轮直接拒: 床底不实心就必须
                        // 先搭平台, 而"搭平台"段排在清障/站位/放床之前, 平台一旦放不上
                        // (悬空、baritone 找不到可依附面) 整个任务就卡死在那一步,
                        // 后面的清理周围与站位一次都不会执行 (20260916-195358 实锤)。
                        // 10 格内有真平地就绝不选悬空点。
                        if (requireSolidFoundation) continue outer;
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
                    + " strict=" + requireSolidFoundation
                    + " player=" + mod.getPlayer().getBlockPos().toShortString();
            Debug.logMessage(msg);
            Debug.logToFile("altoclef_diag.log", msg);
        }
        return closestGood;
    }

    private boolean isGoodAsBorder(AltoClef mod, BlockPos pos) {
        // 流体(水/岩浆)拒绝, 避免把床放在水里。
        if (!mod.getWorld().getBlockState(pos).getFluidState().isEmpty()) return false;
        // (2026-08-25 草原上搭石头平台修复) 非实心: 空气或植被(草/花/藤等)不
        // 挡床, 接受 — 否则草原上地面区域因周边有草被判不可用, bot 选到空中
        // 区域再往地上搭石头平台放床 (用户实测: 明明平坦草原却先放石头再放床)。
        // (2026-09-28 根修) 判定改走 bedCellNeedsClear 统一口径 —— 旧写法
        // "非实心 + 非流体 = 可用"太宽: 竹子 noCollision 但 Material.BAMBOO
        // 【不可替换】, 收下后清理循环又不动它 ⇒ 选区收下、清不掉、放不上。
        if (!bedCellNeedsClear(mod, pos)) return true;
        // 要清的格: 只要能清掉就算可用。
        // (2026-09-24 容器守卫, 用户: 放床卡死+反复做铁镐) 带 BlockEntity 的方块
        // (熔炉/合成台/箱子…) 不作清地目标 —— 现场 20260924-181522: 选区把自家的
        // 熔炉 (-633,74,1290) 圈进清地区 → 为拆熔炉先做镐 (ToolGate) → 合成链
        // 放置的合成台又落进清地区被拆 → 清地目标高频翻转 → satisfy 链每 ~3s 被
        // 杀重来 (铁镐做 5 次才成 1 次)。区域含容器直接拒选, 让选区另寻他处。
        if (mod.getWorld().getBlockEntity(pos) != null) return false;
        return WorldHelper.canBreak(mod, pos);
    }

    /**
     * (2026-09-28 根修, 事故 20260928-114406) 床要占的一格, 是否需要【先清掉】
     * 才能放。
     *
     * 旧口径散在两处、各漏一半, 竹子正好卡进缝里:
     *   ① isGoodAsBorder 旧写法 "非实心 + 非流体 = 可用" ⇒ 竹子(Material.BAMBOO,
     *      noCollision 但【不可替换】)被当成天然可用格收下;
     *   ② 清理循环只清 WorldHelper.isSolid ⇒ 竹子非实心, 不在清理名单。
     * 结果: 选区收下、清理不动、床放不上 —— 现场 footprint 第二格
     * (-64,73,12)=minecraft:bamboo, `here=air above=air hereIsBed=false` 反复
     * 空转 28s, 用户按键手动停 (快照 11:56:20 / 11:56:21 两次抓拍均见该格 bamboo,
     * 且 placeFaces 缺 up 恰说明该格上空是空气 —— 不是站位问题)。
     *
     * 统一口径: 空气 / 可替换的非流体 ⇒ 天然可放 (草/花/蕨走
     * Material.REPLACEABLE_PLANT, 2026-08-25 草原修复的意图原样保留);
     * 其余(实心本体 或 竹子这类不可替换的非空气) ⇒ 必须先清掉。
     * 竹子是 breakInstantly, 清它几乎零成本, 比"选区另找位置"更就地。
     */
    private boolean bedCellNeedsClear(AltoClef mod, BlockPos pos) {
        if (WorldHelper.isAir(mod, pos)) return false;
        if (!mod.getWorld().getBlockState(pos).getFluidState().isEmpty()) return false;
        // (2026-09-28 二次根修, 现场 20260928-140502) 不再判断"可否被替换"。
        // 靠猜 Material/BlockState.isReplaceable 这条路是漏的: 现场床格为
        // minecraft:bamboo 与 minecraft:oak_leaves 时, 选区收下、放置被服务端
        // canReplaceExisting 反复拒 —— 日志里 124 / 62 次 ">>> about to place
        // bed here" 而 0 次 DestroyBlock, 最后床物品耗尽转去重做床。
        // 改为"床要占的格, 只要不是空气/流体就一律清掉", 用确定性换成本:
        // 草/花是多挖几棵(breakInstantly, 近零成本), 换来绝不再猜错。
        return true;
    }

    /**
     * (2026-09-28 站位判据收紧, 事故 20260928-130354 + 用户手工复现)
     * bot 是否真的站到了"能放床"的位置。
     *
     * 只比方块坐标不够: 玩家碰撞箱宽 0.6, 停在格子的 +X 半边时
     * getBlockPos() 依然等于站位格, 但身体已经压到床格上 → 服务端 canPlace
     * 拒放, 表现成"明明两格都空却放不上, 跳起来朝脚下放反倒一定成"
     * (跳起时不占格、不压边)。
     *
     * 床格恒在 toStand 的 +X 侧 (BED_PLACE_STAND_POS = (-1,0,0)); Z 方向与
     * 站位同格是正常的(床沿 X 展开), 所以只约束 X。
     */
    private boolean isStandingWellForBed(AltoClef mod, BlockPos toStand) {
        if (!mod.getPlayer().getBlockPos().equals(toStand)) return false;
        final double PLAYER_HALF_WIDTH = 0.3;    // MC 玩家实体宽 0.6
        final double EPS = 0.02;                 // 贴边余量
        double playerMaxX = mod.getPlayer().getPos().x + PLAYER_HALF_WIDTH;
        double bedEdgeX = toStand.getX() + 1.0;  // 床格左边界
        return playerMaxX <= bedEdgeX - EPS;
    }

    /**
     * (2026-09-06 睡床瞄准点按实体框重取, 用户: "换侧毫无意义, 调整瞄准方向
     * 就一定能点上, 获取不到实体框吗?") 默认瞄点被实体包围盒挡住时, 在床两
     * 半块的【顶面 + 朝玩家侧面】网格采样候选点, 返回第一个满足:
     *   1. 距眼睛 ≤ 4.5 格 (右键手长);
     *   2. 眼睛→该点方块射线的第一个命中是床方块 (点床任意半块都能睡);
     *   3. 射线上没有任何非旁观实体的包围盒横插在床之前 (实体框由
     *      Box.raycast 求交, 与客户端准星实体判定同源)。
     * 的点; 找不到返回 null (保持不出手, 走既有超时降级)。
     */
    private Vec3d findUnblockedBedAimPoint(AltoClef mod, BlockPos bedMain, BlockPos bedHead, Vec3d defaultAim) {
        if (mod.getPlayer() == null) return null;
        Vec3d eyes = mod.getPlayer().getEyePos();
        List<Vec3d> candidates = collectBedAimCandidates(mod, bedMain, bedHead, eyes);
        // 拟人: 优先离默认瞄点最近的 (视线摆动最小)
        candidates.sort(Comparator.comparingDouble(p -> p.squaredDistanceTo(defaultAim)));
        for (Vec3d p : candidates) {
            double dist = eyes.distanceTo(p);
            if (dist > 4.5 || dist < 0.1) continue;
            if (!blockLineHitsBed(mod, eyes, p)) continue;
            // 实体包围盒: 射线上任何实体在床之前横插 → 弃
            if (entityBlocksRay(mod, eyes, p)) continue;
            return p;
        }
        return null;
    }

    /** 床两半块的候选瞄准点: 顶面 3x3 网格 (y=0.55) + 朝 eye 所在侧的侧面 2 高度 x 3 横向。 */
    private List<Vec3d> collectBedAimCandidates(AltoClef mod, BlockPos bedMain, BlockPos bedHead, Vec3d eye) {
        // 两半块: 床头 + 床头 FACING 反方向(床脚), 再兜底 bedMain, 去重
        List<BlockPos> halves = new ArrayList<>();
        halves.add(bedHead);
        BlockState bs = mod.getWorld().getBlockState(bedHead);
        if (bs.getBlock() instanceof BedBlock) {
            try { halves.add(bedHead.offset(bs.get(BedBlock.FACING).getOpposite())); } catch (Exception ignore) {}
        }
        if (!halves.contains(bedMain)) halves.add(bedMain);
        List<Vec3d> candidates = new ArrayList<>();
        for (BlockPos half : halves) {
            if (!(mod.getWorld().getBlockState(half).getBlock() instanceof BedBlock)) continue;
            // 顶面网格 (床碰撞箱高 9/16≈0.5625, 取 0.55)
            for (double ox : new double[]{0.2, 0.5, 0.8}) {
                for (double oz : new double[]{0.2, 0.5, 0.8}) {
                    candidates.add(new Vec3d(half.getX() + ox, half.getY() + 0.55, half.getZ() + oz));
                }
            }
            // 朝 eye 侧面: eye 相对床的主轴方向, 面上 2 高度 x 3 横向
            double ddx = eye.x - (half.getX() + 0.5);
            double ddz = eye.z - (half.getZ() + 0.5);
            int fx, fz;
            if (Math.abs(ddx) >= Math.abs(ddz)) { fx = ddx >= 0 ? 1 : -1; fz = 0; }
            else { fx = 0; fz = ddz >= 0 ? 1 : -1; }
            for (double ys : new double[]{0.15, 0.35}) {
                for (double lat : new double[]{-0.28, 0.0, 0.28}) {
                    double lx = fx != 0 ? half.getX() + 0.5 + fx * 0.48 : half.getX() + 0.5 + lat;
                    double lz = fz != 0 ? half.getZ() + 0.5 + fz * 0.48 : half.getZ() + 0.5 + lat;
                    candidates.add(new Vec3d(lx, half.getY() + ys, lz));
                }
            }
        }
        return candidates;
    }

    /** 眼睛→p 的方块射线第一个命中必须是床方块 (点床任一半都能睡), 否则 false。 */
    private boolean blockLineHitsBed(AltoClef mod, Vec3d eyes, Vec3d p) {
        double dist = eyes.distanceTo(p);
        if (dist < 0.1) return false;
        Vec3d end = eyes.add(p.subtract(eyes).normalize().multiply(dist + 0.1));
        BlockHitResult hit = MinecraftClient.getInstance().world.raycast(
                new RaycastContext(eyes, end, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, mod.getPlayer()));
        return hit.getType() == HitResult.Type.BLOCK
                && mod.getWorld().getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock;
    }

    /** 眼睛→p 的射线上是否有实体包围盒在 p 之前横插 (挡右键/开交易界面)。 */
    private boolean entityBlocksRay(AltoClef mod, Vec3d eyes, Vec3d p) {
        Box search = new Box(eyes, p).expand(1.0);
        for (Entity e : mod.getWorld().getOtherEntities(mod.getPlayer(), search)) {
            if (!e.isAlive() || e.isSpectator()) continue;
            Optional<Vec3d> isect = e.getBoundingBox().raycast(eyes, p);
            if (isect.isPresent() && isect.get().distanceTo(eyes) < p.distanceTo(eyes) - 0.05) return true;
        }
        return false;
    }

    // ==== (2026-09-06 v3 根治"隔着方块点床") ====
    // 用户: bot 会走过去尝试隔着方块点床, 撞了 LOS 限制(12s 超时)才离开。
    // 根治: 出发前就地预判 — 对每个候选床, 从 4 个站侧的眼位做方块射线采样,
    // 至少有一个点能命中床才算"可睡"; 全部隔方块的床不选、不出发。
    private final HashMap<BlockPos, Boolean> _bedClickableCache = new HashMap<>();
    private final TimerGame _bedClickableCacheTimer = new TimerGame(1);
    private final TimerGame _noClickableBedLogTimer = new TimerGame(5);

    /**
     * range 内最近的一张"至少一个站侧方块射线可达"的床; 全部隔方块返回 null。
     * (按距离排序取第一个可用, 结果按床位缓存 1s。)
     */
    private BlockPos findInteractableBedNear(AltoClef mod, double range) {
        if (mod.getPlayer() == null) return null;
        if (_bedClickableCacheTimer.elapsed()) {
            _bedClickableCacheTimer.reset();
            _bedClickableCache.clear();
        }
        BlockPos playerPos = mod.getPlayer().getBlockPos();
        List<BlockPos> beds = new ArrayList<>(mod.getBlockTracker().getKnownLocations(BEDS));
        beds.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(playerPos)));
        for (BlockPos bed : beds) {
            if (!bed.isWithinDistance(mod.getPlayer().getPos(), range)) continue;
            Boolean cached = _bedClickableCache.get(bed);
            boolean ok;
            if (cached != null) {
                ok = cached;
            } else {
                ok = bedHasClickableLine(mod, bed);
                _bedClickableCache.put(bed, ok);
            }
            if (ok) return bed;
        }
        return null;
    }

    /** 床是否至少存在一个站侧, 从其眼位 (站立方块 +1.62) 的方块射线采样能命中床。 */
    private boolean bedHasClickableLine(AltoClef mod, BlockPos bed) {
        BlockPos head = WorldHelper.getBedHead(mod, bed);
        if (head == null) head = bed;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos stand = head.offset(dir);
            Vec3d eye = new Vec3d(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
            for (Vec3d p : collectBedAimCandidates(mod, bed, head, eye)) {
                if (blockLineHitsBed(mod, eye, p)) return true;
            }
        }
        return false;
    }
}
