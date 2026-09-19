package adris.altoclef;

import adris.altoclef.butler.Butler;
import adris.altoclef.chains.*;
import adris.altoclef.commandsystem.CommandExecutor;
import adris.altoclef.control.InputControls;
import adris.altoclef.control.PlayerExtraController;
import adris.altoclef.control.SlotHandler;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientRenderEvent;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.eventbus.events.TitleScreenEntryEvent;
import adris.altoclef.record.RecordManager;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.*;
import adris.altoclef.trackers.storage.ContainerSubTracker;
import adris.altoclef.trackers.storage.ItemStorageTracker;
import adris.altoclef.ui.CommandStatusOverlay;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.ui.MessageSender;
import adris.altoclef.util.helpers.InputHelper;
import adris.altoclef.util.helpers.NavStuckWatchdog;
import adris.altoclef.util.helpers.OffhandHygiene;
import adris.altoclef.util.helpers.TaskStackSnapshot;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ModInitializer;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Central access point for AltoClef
 */
public class AltoClef implements ModInitializer {

    // Static access to altoclef
    private static final Queue<Consumer<AltoClef>> _postInitQueue = new ArrayDeque<>();

    // (2026-09-16 守炉死循环修复) 静态实例访问器: TaskCatalogue 缓存"同资源活实例"
    // 复用需在无 mod 形参处做活性判定 (_liveInstance.isFinished(mod)), 通过本引用
    // 拿 AltoClef, 避免给 CataloguedResource 整条调用链加 mod 形参。在
    // onInitializeLoad 处一次性赋值, 任务运行期恒非 null。
    private static AltoClef _instance;

    public static AltoClef getInstance() {
        return _instance;
    }

    // Central Managers
    private static CommandExecutor _commandExecutor;
    private TaskRunner _taskRunner;
    private TrackerManager _trackerManager;
    private BotBehaviour _botBehaviour;
    private PlayerExtraController _extraController;
    // Task chains
    private UserTaskChain _userTaskChain;
    private FoodChain _foodChain;
    private MobDefenseChain _mobDefenseChain;
    private MLGBucketFallChain _mlgBucketChain;
    // (2026-08-30 没镐别挖石头墙) 当前采矿等级缓存, 每 client tick 刷新。
    // 供 Baritone 寻路的 avoidBlockBreak 谓词用 —— 寻路线程读它不该碰背包,
    // 只在客户线程 (onClientTick) 更新, volatile 保证可见。避免谓词里再走
    // plausibleToBreak (会回调本谓词 → 栈溢出崩溃, 上次实测)。
    private volatile adris.altoclef.util.MiningRequirement _currentMiningReq = adris.altoclef.util.MiningRequirement.HAND;
    // (2026-09-12 v8 封闭空间逃生豁免, 用户拍板"挖一格放工作台做镐子") true 时
    // avoidBlockBreak 谓词放行"没镐避让"(shouldAvoidUnminable) 那一条 —— 空手
    // 挖石头 7.5s/格 慢但能挖, 逃生是最后手段不该被这条规则锁死。硬保护
    // (isPositionExplicitelyProtected: 传送门/黑曜石守卫等) 仍然生效。
    // 只由 GoToSurfaceTask 逃生序列开关, onStop 必须复位。
    private volatile boolean _escapeDigOverride = false;
    // Trackers
    private ItemStorageTracker _storageTracker;
    private ContainerSubTracker _containerSubTracker;
    private EntityTracker _entityTracker;
    private BlockTracker _blockTracker;
    private SimpleChunkTracker _chunkTracker;
    private MiscBlockTracker _miscBlockTracker;
    private SoundTracker _soundTracker;
    private KnowledgeMemory _knowledgeMemory;
    private WorldMemory _worldMemory;
    // Renderers
    private CommandStatusOverlay _commandStatusOverlay;
    // Settings
    private adris.altoclef.Settings _settings;
    // Misc managers/input
    private MessageSender _messageSender;
    private InputControls _inputControls;
    private SlotHandler _slotHandler;
    // Butler
    private Butler _butler;

    // Are we in game (playing in a server/world)
    public static boolean inGame() {
        return MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().getNetworkHandler() != null;
    }

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // As such, nothing will be loaded here but basic initialization.
        EventBus.subscribe(TitleScreenEntryEvent.class, evt -> onInitializeLoad());
    }

    public void onInitializeLoad() {
        // This code should be run after Minecraft loads everything else in.
        // This is the actual start point, controlled by a mixin.

        // (2026-09-16 守炉死循环修复) 登记静态实例, 供 TaskCatalogue 缓存活实例
        // 复用在无 mod 形参处做活性判定。
        _instance = this;

        initializeBaritoneSettings();

        // Central Managers
        _commandExecutor = new CommandExecutor(this);
        _taskRunner = new TaskRunner(this);
        _trackerManager = new TrackerManager(this);
        _botBehaviour = new BotBehaviour(this);
        _extraController = new PlayerExtraController(this);

        // Task chains
        _userTaskChain = new UserTaskChain(_taskRunner);
        _mobDefenseChain = new MobDefenseChain(_taskRunner);
        new DeathMenuChain(_taskRunner);
        new PlayerInteractionFixChain(_taskRunner);
        new SwingThroughChain(_taskRunner);
        new SwimDiveChain(_taskRunner);
        _mlgBucketChain = new MLGBucketFallChain(_taskRunner);
        new WorldSurvivalChain(_taskRunner);
        new DeathRecoveryChain(_taskRunner);
        _foodChain = new FoodChain(_taskRunner);

        // Trackers
        _storageTracker = new ItemStorageTracker(this, _trackerManager, container -> _containerSubTracker = container);
        _entityTracker = new EntityTracker(_trackerManager);
        _blockTracker = new BlockTracker(this, _trackerManager);
        _chunkTracker = new SimpleChunkTracker(this);
        _miscBlockTracker = new MiscBlockTracker(this);
        _soundTracker = new SoundTracker();
        _knowledgeMemory = new KnowledgeMemory(this);
        _worldMemory = new WorldMemory(this);

        // Renderers
        _commandStatusOverlay = new CommandStatusOverlay();

        // Misc managers
        _messageSender = new MessageSender();
        _inputControls = new InputControls();
        _slotHandler = new SlotHandler(this);

        _butler = new Butler(this);

        initializeCommands();

        // Load settings
        adris.altoclef.Settings.load(newSettings -> {
            _settings = newSettings;
            // Baritone's `acceptableThrowawayItems` should match our own.
            // (2026-09-19 修垫脚死循环) ① 只有 BlockItem 才真能被 baritone 放置:
            // nether_brick 是冶炼产物物品 (无方块形态), 混进列表后 baritone 的
            // hasGenericThrowaway 只按物品身份匹配 (反编译 baritone jar 实锤,
            // 无 BlockItem 判断), 会规划垫柱动作并选中一个放不出的物品 → 原地
            // 无限跳 (20260918-231551: hand=nether_brick, jump 循环 36s+)。
            // ② 灵魂沙放行: v30 的 forceWalkOn 谓词 + treatSoulSandAsOrdinaryBlock
            // 已让 baritone 能站上灵魂沙规划/行走, 用户明确灵魂沙可以垫脚;
            // 岩浆块仍排除 (放下去烧自己)。
            List<Item> baritoneCanPlace = Arrays.stream(_settings.getThrowawayItems(this, true))
                    .filter(item -> item instanceof BlockItem)
                    .filter(item -> item != Items.MAGMA_BLOCK)
                    .collect(Collectors.toList());
            getClientBaritoneSettings().acceptableThrowawayItems.value.addAll(baritoneCanPlace);
            // If we should run an idle command...
            if ((!getUserTaskChain().isActive() || getUserTaskChain().isRunningIdleTask()) && getModSettings().shouldRunIdleCommandWhenNotActive()) {
                getUserTaskChain().signalNextTaskToBeIdleTask();
                getCommandExecutor().executeWithPrefix(getModSettings().getIdleCommand());
            }
            // Don't break blocks or place blocks where we are explicitly protected.
            // (2026-08-30 没镐别挖石头墙, 用户) 床在屋里时 bot 不走门, 非要挖穿
            // 石头墙, 但没镐挖不动 → 挖一半放弃又重挖卡死。让寻路在"当前采矿
            // 等级 < 该方块所需等级"时避让 (没镐 → 石头/矿石都避开 → 改走门)。
            // 用缓存的 _currentMiningReq + vanilla getMinimumRequirementForBlock,
            // 绝不用 plausibleToBreak (它会回调本谓词 → 栈溢出崩溃, 上次实测)。
            getExtraBaritoneSettings().avoidBlockBreak(blockPos ->
                    _settings.isPositionExplicitelyProtected(blockPos)
                            || (!_escapeDigOverride && WorldHelper.shouldAvoidUnminable(this, _currentMiningReq, blockPos)));
            getExtraBaritoneSettings().avoidBlockPlace(blockPos -> _settings.isPositionExplicitelyProtected(blockPos));
        });

        // Receive + cancel chat
        EventBus.subscribe(SendChatEvent.class, evt -> {
            String line = evt.message;
            if (getCommandExecutor().isClientCommand(line)) {
                evt.cancel();
                getCommandExecutor().execute(line);
            }
        });

        // Debug jank/hookup
        Debug.jankModInstance = this;

        // Tick with the client
        EventBus.subscribe(ClientTickEvent.class, evt -> onClientTick());
        // Render
        EventBus.subscribe(ClientRenderEvent.class, evt -> onClientRenderOverlay(evt.stack));

        // Playground
        Playground.IDLE_TEST_INIT_FUNCTION(this);

        // External mod initialization
        runEnqueuedPostInits();
    }

    // [JankDebug] 整帧耗时检测 (2026-08-23): 记录本帧起始 nanoTime 与上一帧
    // 间隔, 在帧尾测整体耗时。游戏画面规律性停顿几毫秒 = 主/渲染线程某帧
    // 耗时过长或帧间隔抖动。单帧内某阶段 >500ms 已有 SlowTickDebug; 这里抓
    // 更细的"整帧 + 帧间隔"规律性卡顿 (几 ms~几十 ms 级别)。
    private long _lastFrameStartNano = 0;

    // Client tick
    private void onClientTick() {
        runEnqueuedPostInits();

        // (2026-08-30 没镐别挖石头墙) 客户线程刷新"当前采矿等级"缓存, 供寻路
        // 谓词用。仅 inGame 时刷新; 玩家空手/复活 (HAND) 会让寻路避开硬岩。
        if (inGame()) {
            _currentMiningReq = adris.altoclef.util.helpers.StorageHelper.getCurrentMiningRequirement(this);
            tickInteractionPauseWatchdog();
            // (2026-09-18 下界岩浆邻域导航保险) 旋转跳 (斜向升格) 失败率>50%, 岩浆
            // 邻域动态禁跳 + 压低垫块惩罚, 详见 tickNetherLavaNavGuard 注释。
            tickNetherLavaNavGuard();
            // (2026-09-17 跨世界残留状态) 换存档(不重启游戏)时 static 字段全部存活
            // → 上个世界的记忆/冷却/账本会带进新存档。此守卫比对存档目录名,
            // 变化即清空。清单见 WorldStateGuard.registerAll()。
            adris.altoclef.trackers.WorldStateGuard.tick(this);
            // (2026-09-18 永久夜视) 客户端常挂夜视, 纯视觉: 录屏画面在暗处可读。
            // 用夜视而非 gamma, 是因为 gamma 在光影下会被覆盖 (详见方法注释)。
            tickPermanentNightVision();
        }

        // [JankDebug] 帧开始时间戳 (用于帧尾算整帧耗时与帧间隔)
        long frameStart = System.nanoTime();
        long frameGapMs = (_lastFrameStartNano == 0) ? 0 : (frameStart - _lastFrameStartNano) / 1000_000L;
        _lastFrameStartNano = frameStart;

        _inputControls.onTickPre();

        // Cancel shortcut
        if (InputHelper.isKeyPressed(GLFW.GLFW_KEY_LEFT_CONTROL) && InputHelper.isKeyPressed(GLFW.GLFW_KEY_K)) {
            _userTaskChain.cancel(this);
            if (_taskRunner.getCurrentTaskChain() != null) {
                _taskRunner.getCurrentTaskChain().stop(this);
            }
        }

        // TODO: should this go here?
        _storageTracker.setDirty();
        final long MS = 1000_000L; // nanoseconds per millisecond
        long tMisc = System.nanoTime();
        _containerSubTracker.onServerTick();
        _miscBlockTracker.tick();
        long miscMs = (System.nanoTime() - tMisc) / MS;

        // [SlowTickDebug] measure how long each synchronous phase of the client
        // tick takes. When the bot "stands still forever computing", one of these
        // phases is what it's actually waiting on. We only log when a phase blows
        // past the threshold (ms), so normal ticks stay silent.

        long t0 = System.nanoTime();
        _knowledgeMemory.tick();
        long knowMs = (System.nanoTime() - t0) / MS;
        if (knowMs > 500) {
            String m = "[SlowTickDebug] KnowledgeMemory.tick took " + knowMs + "ms";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_slowtick.log", m);
        }

        t0 = System.nanoTime();
        try {
            _worldMemory.tick();
        } catch (Throwable t) {
            // [BedDebug/diagnostic] if our WorldMemory scan throws mid-client-tick it
            // runs BEFORE _trackerManager/_taskRunner.tick below, silently breaking the
            // task state machine (e.g. sleep task losing its bed). Surfacing it proves
            // or rules this out.
            this.logWarning("WorldMemory.tick threw: " + t);
            t.printStackTrace();
        }
        long worldMs = (System.nanoTime() - t0) / MS;
        if (worldMs > 500) {
            String m = "[SlowTickDebug] WorldMemory.tick took " + worldMs + "ms";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_slowtick.log", m);
        }

        long tTrack = System.nanoTime();
        _trackerManager.tick();
        _blockTracker.preTickTask();
        long trackMs = (System.nanoTime() - tTrack) / MS;
        t0 = System.nanoTime();
        _taskRunner.tick();
        long runMs = (System.nanoTime() - t0) / MS;
        if (runMs > 500) {
            String m = "[SlowTickDebug] TaskRunner.tick took " + runMs + "ms";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_slowtick.log", m);
        }
        // (2026-09-07 准心仲裁) 任务+链层的所有击打诉求跑完后, 唯一出口决定本
        // tick 准心瞄谁: 脸上最近的攻击诉求拿到瞄准权, 避免"任务层瞄远目标 &
        // 力场瞄近怪"每 tick 互相抢 lookAt 覆盖, 准心永远停在远目标身上。
        getControllerExtras().applyCombatAim();

        // (2026-09-17 数据集录制) 六通道快照的唯一采样点: 此时 tracker 已刷新、
        // 任务树已定型 (TaskRunner.tick 内仲裁+任务树都跑完)、动作已下发
        // (applyCombatAim 定了准心) —— 输入/决策/动作三态在本 tick 内一致。
        // 录制器严格只读 + 全包 try/catch, 设置开关关闭时零开销。
        RecordManager.tick(this);

        // [NAV-STUCK] 原地不动/水中浮动看门狗 (2026-09-09, 只读诊断):
        // "泡在水里/站原地不动但挂着导航任务" 此前无任何日志可证。250ms 采样,
        // 4s 窗口净位移判卡, 卡住时连同 baritone 活跃态 + 按键 + 任务链一起落盘
        // altoclef_diag.log。零行为改动。
        if (inGame()) {
            NavStuckWatchdog.tick(this);
            // [SNAPSHOT] 任务栈全栈快照 (2026-09-11, 只读诊断): 常态 5s 一条,
            // NAV-STUCK 冻结期间 1s 一条。补事件驱动日志的盲区 — "父任务每 tick
            // 重派同一子任务 / 子任务每 tick 空转返回 null" 这类无切换死锁在
            // START/STOP 日志里完全不可见 (22:17 冻结 39s 实锤)。内部自节流。
            TaskStackSnapshot.periodicTick(this);
            // [OFFHAND-HYGIENE] 副手卫生检测 (2026-09-12, 低频自愈): 副手被意外
            // 塞进非功能物品 (现场: spruce_planks 挂副手 33s, 材料够但合成链死等
            // — 配方填充从背包取料看不见副手)。白名单外 (盾/食物/药水/图腾) 且
            // 背包放得下 → 搬回背包。5s 冷却, 内部自节流。
            OffhandHygiene.tick(this);
        }
        _blockTracker.postTickTask();

        _butler.tick();
        _messageSender.tick();

        _inputControls.onTickPost();

        // [GamerButton] 每 tick 检查右上角"开始/停止速通"按钮的点击。
        _commandStatusOverlay.onClientTick(this);

        // [JankDebug] 帧尾: 整帧耗时 + 帧间隔 (卡顿检测, 2026-08-23)。
        // 阈值刻意设低: 用户报告"画面停止不动几毫秒", 说明是规律性的小卡顿,
        // 500ms 级别的 SlowTickDebug 抓不到。这里 帧耗时>60ms 或 帧间隔>100ms
        // 都记到 altoclef_jank.log。日志带游戏 tick, 方便对照 tasks.log。
        // 用墙钟而非 tick: 游戏 tick 间隔固定 50ms, 卡顿时 tick 数不变但墙钟
        // 时间拉长 — 帧间隔最能反映"画面停住"。
        // (2026-08-23 崩溃修复) 只在游戏内记录: 主菜单/世界加载时 getTicks 虽已
        // 空安全, 但加载屏的帧耗时波动毫无诊断价值, 且曾导致 NPE 崩溃。
        long frameMs = (System.nanoTime() - frameStart) / MS;
        if (inGame() && (frameMs > 60 || frameGapMs > 100)) {
            // (2026-09-03 分阶段拆解) 附带各 tick 阶段耗时, 便于直接定位 200ms
            // 级帧卡是哪一段吃掉的 (原只记整帧, 阶段>500ms 才单独记 SlowTick,
            // 100~200ms 的分散耗时查不到来源)。
            String m = "[JankDebug] frameMs=" + frameMs + " frameGapMs=" + frameGapMs
                    + " tick=" + WorldHelper.getTicks()
                    + " phases: misc=" + miscMs + "ms knowledge=" + knowMs + "ms world=" + worldMs
                    + "ms track=" + trackMs + "ms task=" + runMs + "ms"
                    + " task=" + (_taskRunner.getCurrentTaskChain() == null ? "null"
                        : _taskRunner.getCurrentTaskChain().getTasks().toString());
            Debug.logToFile("altoclef_jank.log", m);
        }
    }

    private void onClientRenderOverlay(MatrixStack matrixStack) {
        _commandStatusOverlay.render(this, matrixStack);
    }

    /// GETTERS AND SETTERS

    private void initializeBaritoneSettings() {
        // Let baritone move items to hotbar to use them
        getClientBaritoneSettings().allowInventory.value = true;
        // Pretty safe, minor risk EXCEPT in the nether, where it is a huge risk.
        getClientBaritoneSettings().allowDiagonalAscend.value = true;
        // Reduces a bit of far rendering to save FPS
        getClientBaritoneSettings().fadePath.value = true;
        // Don't let baritone scan dropped items, we handle that ourselves.
        getClientBaritoneSettings().mineScanDroppedItems.value = false;
        // Don't let baritone wait for drops, we handle that ourselves.
        getClientBaritoneSettings().mineDropLoiterDurationMSThanksLouca.value = 0L;

        // Human-like movement: sprint when possible, parkour small gaps.
        // NOTE: allowParkourPlace is kept OFF: baritone would place blocks under
        // our feet to cross gaps, which can be dangerous (jumping into hazards).
        getClientBaritoneSettings().allowSprint.value = true;
        getClientBaritoneSettings().allowParkour.value = true;
        getClientBaritoneSettings().allowParkourAscend.value = true;
        getClientBaritoneSettings().sprintAscends.value = true;
        getClientBaritoneSettings().allowParkourPlace.value = false;

        // Let baritone turn our head to face the travel direction while pathing.
        // (freeLook=true made the bot never turn, which also broke sprinting and
        // made the visibility filter unable to see what's ahead.)
        getClientBaritoneSettings().freeLook.value = false;

        // Really avoid mobs if we're in danger.
        getClientBaritoneSettings().mobAvoidanceCoefficient.value = 2.0;
        getClientBaritoneSettings().mobAvoidanceRadius.value = 12;

        // Water bucket placement will be handled by us exclusively
        getExtraBaritoneSettings().configurePlaceBucketButDontFall(true);

        // (2026-09-15 interaction pause 看门狗) `isInteractionPaused` 是 baritone
        // patched 层的**全局单布尔位** (AltoClefSettings 在依赖 jar 里, 本仓改不了),
        // 全仓 12 处 writer 成对 set(true)/set(false), 没有任何 RAII 保护 ——
        // EatItemTask 源码注释自承 "SingleTaskChain 的 isFinished 路径不会调用 onStop";
        // 而这个值直接参与 baritone 的放置总闸
        //     hasThrowaway = !isInteractionPaused && allowPlace && hasGenericThrowaway()
        // 一旦某处 true 之后没能复位, baritone 从此**完全不能放方块**, 症状与
        // "背包里没方块" 完全无法区分: bot 对着该垫的位置只起跳、不放块。
        // 合法占用者最长是进食 / 喝水 / MLG 落桶 / 单次挥击, 都在数秒内 ——
        // 下面这条 >10s 才强制复位的兜底 (tickInteractionPauseWatchdog) 不会误伤任何
        // 正常场景。
        // (注册点在 onTick: tickInteractionPauseWatchdog(); 方法定义在本方法之后。)

    // (2026-09-13 v30 灵魂沙 canWalkOn) —— 修 v29 没打中的真根因
    //
    // 现场: bot 两次进下界都卡死在同一个灵魂沙格 (10,88,-59) (11:28 与 17:00 两次会话),
        // hSpeed=0.000, bIn 全 false, baritone pathing=false pathPresent=false;
        // latest.log 里 `Open set size: 0` + `PathNode map size: 2` +
        // `nodes per second` 溢出 Integer.MAX_VALUE (A* 一步未展开) 共 4499 次,
        // `[Baritone] Failed` 每秒刷 10+ 次, 一路卡到 anomaly 报
        // `原地停滞 225s / TimeoutWanderTask`。主世界无灵魂沙时同样代码完全正常
        // (openset 84380 / 83390 / 59979 ...)。
        //
        // 真根因在**规划层**, 不在执行层 (v29 的 treatSoulSandAsOrdinaryBlock 打在
        // MovementTraverse.updateState, 够不着这里, 所以无效):
        //   MovementHelper.canWalkOn 第一条判据是
        //     isBlockNormalCube(state) = Block.isShapeFullCube(state.getCollisionShape(null,null))
        //   灵魂沙的碰撞盒是 (0,0,0,1,0.875,1) —— 14/16 高, 不是 full cube → false。
        //   后续白名单经 yarn 1.18.2+build.3 映射逐个核对:
        //     LADDER / VINE / FARMLAND / DIRT_PATH / ENDER_CHEST / CHEST / TRAPPED_CHEST
        //     / GLASS / END_PORTAL_FRAME / SlabBlock / StairsBlock / AzaleaBlock
        //     / StainedGlassBlock —— **没有 SoulSandBlock (class_2492)** → 返回 false。
        //   即 baritone 认为**灵魂沙不能站**。
        // 连锁: 玩家站在灵魂沙上 → IPlayerContext.playerFeet() 的 `floor(y+0.1251)`
        // 把脚算到灵魂沙上方那格 (88.875→89.0001→89) → 起点下方 (10,88,-59) 正是
        // 灵魂沙 → canWalkOn=false → A* 的 44 种移动全部 COST_INF (每种 walk 移动都
        // 要求"起点有支撑") → openset=0 → Failed → bot 一个输入都不发, 完全站桩。
        // 下界传送门出口常落在灵魂沙谷, 所以每次进下界必现。
        //
        // 修: canWalkOn 的第一个分支就是
        //   `if (AltoClefSettings.canWalkOnForce(x,y,z)) return true;`
        // 注册一个 force-can-walk-on 谓词把灵魂沙放行即可 (与规划层同源, 执行层
        // 的 +0.1251 hack 本来就为灵魂沙准备好了落脚点)。全 jar 只有一个消费点
        // (MovementHelper), 改动面收敛。谓词跑在 baritone 计算线程, 与既有
        // avoidBlockBreak 谓词同样直接读世界, 只做 O(1) 的 getBlockState, 不回调
        // baritone (避免上次那种"谓词回调自己"的栈溢出)。
        getExtraBaritoneSettings().getForceWalkOnPredicates().add(pos -> {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world == null) return false;
            int y = pos.getY();
            if (y < world.getBottomY() || y >= world.getTopY()) return false;
            return world.getBlockState(pos).getBlock() == Blocks.SOUL_SAND;
        });

        // 配套 (v29): 执行层也当普通方块走。规划层放行后 baritone 会真的把路径
        // 规划到灵魂沙上, 此时再走 MovementTraverse 的"灵魂沙特判分支"(接近目标
        // 中心 <0.85 格就 MOVE_BACK 刹车) 只会在灵魂沙上原地蹭, 关掉它保持一致。
        getExtraBaritoneSettings().treatSoulSandAsOrdinaryBlock(true);

        // Give baritone more time to calculate paths. Sometimes they can be really far away.
        // Was: 2000L
        getClientBaritoneSettings().failureTimeoutMS.value = 6000L;
        // Was: 5000L
        getClientBaritoneSettings().planAheadFailureTimeoutMS.value = 10000L;
        // Was 100
        getClientBaritoneSettings().movementTimeoutTicks.value = 200;
    }

    // ==== (2026-09-15 interaction pause 看门狗) ====
    // 看门狗状态: 上一次观察到的暂停值, 与本次暂停的起始时刻。
    private static final long INTERACTION_PAUSE_STALE_MS = 10_000;
    private boolean _lastInteractionPaused = false;
    private long _interactionPausedSinceMs = 0;

    /**
     * (2026-09-15) interaction pause 泄漏兜底。详见 initializeBaritoneSettings 里的注释。
     */
    private void tickInteractionPauseWatchdog() {
        boolean paused;
        try {
            paused = getExtraBaritoneSettings().isInteractionPaused();
        } catch (Throwable ignored) {
            return; // 世界/玩家尚未就绪, 不参与
        }
        long now = System.currentTimeMillis();
        if (!paused) {
            _lastInteractionPaused = false;
            _interactionPausedSinceMs = 0;
            return;
        }
        if (!_lastInteractionPaused || _interactionPausedSinceMs == 0) {
            _lastInteractionPaused = true;
            _interactionPausedSinceMs = now;
            return;
        }
        long heldMs = now - _interactionPausedSinceMs;
        if (heldMs > INTERACTION_PAUSE_STALE_MS) {
            getExtraBaritoneSettings().setInteractionPaused(false);
            _lastInteractionPaused = false;
            _interactionPausedSinceMs = 0;
            String m = "[InteractionPauseWatchdog] 自动复位泄漏的 interaction pause (已持续 "
                    + heldMs + "ms > " + INTERACTION_PAUSE_STALE_MS
                    + "ms) — 它掐着 baritone 的放置总闸 hasThrowaway, 泄漏期间 bot 只能跳不能放方块";
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
    }

    // ==== (2026-09-18 下界岩浆邻域导航保险) ====
    // 背景: baritone 的斜向升格 (allowDiagonalAscend, 俗称"旋转跳") 执行失败率
    // 实测 >50% —— 从 (1,0,0) 斜跳 (0,1,1) 且头顶 (1,2,1) 有顶棚时需要空中转向,
    // baritone 的执行模型做不好这一跳, 掉下去落岩浆直接死 (123145 场次 12:37 /
    // 12:48 / 13:10 三次, 死前均为纯赶路)。initializeBaritoneSettings 的原注释
    // 早就写了 "minor risk EXCEPT in the nether, where it is a huge risk"。
    // 处理: 下界且玩家周围有岩浆时, 动态 ① 关斜向升格 ② 把垫块惩罚压到上限 2
    // (A* 改选"垫一格绕行", 执行成功率≈100%)。没有 baritone 放得下去的垫块时
    // 保持原行为: 硬禁跳可能把 bot 冻在原地, 跳总比冻死强 (PillarJumpHelper.
    // countBaritonePlaceable 判定, 与 baritone 的 hasThrowaway 同口径)。
    // 扫描每秒一次 (TimerGame 节流, 25x17x25 块立方体); 每 tick 强制写设置,
    // 防任务栈 applyState 把旧值写回去。惩罚值用 Math.min 收紧、解除时不回写 —
    // 残留的低惩罚只会"更爱垫路", 无害, 下一次任务栈 applyState 自动恢复。
    private static final double LAVA_NAV_SCAN_RADIUS = 12;
    private static final double LAVA_NAV_PLACE_PENALTY_CAP = 2.0;
    private final adris.altoclef.util.time.TimerGame _lavaNavScanTimer = new adris.altoclef.util.time.TimerGame(1.0);
    private boolean _lavaNavSuppressActive = false;
    private boolean _lavaNavNoBlockWarned = false;

    private void tickNetherLavaNavGuard() {
        if (inGame() && getPlayer() != null && getWorld() != null && _lavaNavScanTimer.elapsed()) {
            _lavaNavScanTimer.reset();
            boolean lavaNearby = false;
            boolean suppress = false;
            if (adris.altoclef.util.helpers.WorldHelper.getCurrentDimension() == adris.altoclef.util.Dimension.NETHER) {
                BlockPos center = getPlayer().getBlockPos();
                int r = (int) LAVA_NAV_SCAN_RADIUS;
                scan:
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= 4; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (getWorld().getBlockState(center.add(dx, dy, dz)).getBlock() == Blocks.LAVA) {
                                lavaNearby = true;
                                break scan;
                            }
                        }
                    }
                }
                if (lavaNearby) {
                    suppress = adris.altoclef.util.helpers.PillarJumpHelper.countBaritonePlaceable(this) > 0;
                    if (!suppress && !_lavaNavNoBlockWarned) {
                        Debug.logToFile("altoclef_diag.log",
                                "[LavaNav] lava nearby but no baritone-placeable blocks — keeping diagonal ascend ON");
                        _lavaNavNoBlockWarned = true;
                    }
                }
            }
            if (!lavaNearby) {
                _lavaNavNoBlockWarned = false;
            }
            if (suppress != _lavaNavSuppressActive) {
                _lavaNavSuppressActive = suppress;
                String m = suppress
                        ? "[LavaNav] 下界岩浆邻域: 关斜向升格(旋转跳), 垫块惩罚压到 " + LAVA_NAV_PLACE_PENALTY_CAP
                        : "[LavaNav] 岩浆邻域解除: 恢复斜向升格";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
        }
        // 每 tick 强制写 (applyState 可能随时把任务栈里的旧值写回 baritone settings)。
        // 未抑制时写回基线 true, 与 initializeBaritoneSettings 的原值一致。
        getClientBaritoneSettings().allowDiagonalAscend.value = !_lavaNavSuppressActive;
        if (_lavaNavSuppressActive) {
            getClientBaritoneSettings().blockPlacementPenalty.value =
                    Math.min(getClientBaritoneSettings().blockPlacementPenalty.value, LAVA_NAV_PLACE_PENALTY_CAP);
        }
    }

    // ==== 永久夜视 (2026-09-18) ====
    // 挂多久: 30 分钟一份。不用 Integer.MAX_VALUE 是怕极端时长在 HUD 上显示成
    // "**:**" 之类的怪东西, 反正下面会按剩余时间自动续。
    private static final int NIGHT_VISION_DURATION_TICKS = 20 * 60 * 30;

    // 只在我们自己挂过之后才知道"关开关时要撤掉谁": 如果是服务器给的夜视(喝了药水),
    // 不该由这个开关来撤 —— 否则关一次开关就把 bot 的药水效果也清了。
    private boolean _nightVisionAppliedByUs = false;

    /**
     * 客户端侧常挂夜视, 保证录屏/采集画面在夜晚/矿洞/下界能看清。
     *
     * <p>【为什么用夜视而不是 gamma】光影 (Iris/OptiFine) 接管画面亮暗后, 原版 gamma
     * 会被忽略; 而 1.18.2 的夜视是融进 lightmap 纹理的, 光影普遍要采样 lightmap,
     * 所以夜视在光影下依然生效。这是"装了光影也适用"的关键。
     *
     * <p>纯视觉改动: 不碰任何任务/寻路判据 (bot 读世界靠方块与实体数据, 不看亮度)。
     * 效果只在客户端添加, 服务器不知情, 无服务端副作用。
     */
    private void tickPermanentNightVision() {
        ClientPlayerEntity player = getPlayer();
        if (player == null || _settings == null) return;

        if (!_settings.isForceNightVisionEnabled()) {
            if (_nightVisionAppliedByUs) {
                player.removeStatusEffect(StatusEffects.NIGHT_VISION);
                _nightVisionAppliedByUs = false;
                Debug.logMessage("[NightVision] 开关关闭, 已移除客户端夜视");
            }
            return;
        }

        StatusEffectInstance current = player.getStatusEffect(StatusEffects.NIGHT_VISION);
        int refreshTicks = (int) (_settings.getNightVisionRefreshBelowSeconds() * 20);
        if (current == null || current.getDuration() < refreshTicks) {
            // 无粒子 + 无 HUD 图标: 录屏画面不该出现效果图标和粒子。
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION,
                    NIGHT_VISION_DURATION_TICKS, 0, false, false, false));
            _nightVisionAppliedByUs = true;
        }
    }

    // List all command sources here.
    private void initializeCommands() {
        try {
            // This creates the commands. If you want any more commands feel free to initialize new command lists.
            new AltoClefCommands();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Executes commands (ex. `@get`/`@gamer`)
     */
    public static CommandExecutor getCommandExecutor() {
        return _commandExecutor;
    }

    /**
     * Runs the highest priority task chain
     * (task chains run the task tree)
     */
    public TaskRunner getTaskRunner() {
        return _taskRunner;
    }

    // ==== Self-placed bed memory (2026-08-21 睡完收床) ====
    // Bot 自己放下的床的位置 (由 PlaceBedAndSetSpawnTask 记录)。
    // 用途: 睡觉结束/中断后白天把床收回带走 (下个晚上继续用),
    // 且严格只收自己的床 — 绝不据此去拆别人的床 (用户原则)。
    private BlockPos _lastSelfPlacedBed = null;

    public BlockPos getLastSelfPlacedBed() {
        return _lastSelfPlacedBed;
    }

    public void setLastSelfPlacedBed(BlockPos pos) {
        _lastSelfPlacedBed = pos;
    }

    /**
     * The user task chain (runs your command. Ex. Get Diamonds, Beat the Game)
     */
    public UserTaskChain getUserTaskChain() {
        return _userTaskChain;
    }

    /**
     * Controls bot behaviours, like whether to temporarily "protect" certain blocks or items
     */
    public BotBehaviour getBehaviour() {
        return _botBehaviour;
    }

    /**
     * Tracks items in your inventory and in storage containers.
     */
    public ItemStorageTracker getItemStorage() {
        return _storageTracker;
    }

    /**
     * Tracks loaded entities
     */
    public EntityTracker getEntityTracker() {
        return _entityTracker;
    }

    /**
     * Tracks blocks and their positions
     */
    public BlockTracker getBlockTracker() {
        return _blockTracker;
    }

    /**
     * Remembers recently heard sounds (human-like hearing).
     */
    public SoundTracker getSoundTracker() {
        return _soundTracker;
    }

    public KnowledgeMemory getKnowledgeMemory() {
        return _knowledgeMemory;
    }

    public WorldMemory getWorldMemory() {
        return _worldMemory;
    }

    ContainerSubTracker getContainerSubTracker() {
        return _containerSubTracker;
    }

    /**
     * Tracks of whether a chunk is loaded/visible or not
     */
    public SimpleChunkTracker getChunkTracker() {
        return _chunkTracker;
    }

    /**
     * Tracks random block things, like the last nether portal we used
     */
    public MiscBlockTracker getMiscBlockTracker() {
        return _miscBlockTracker;
    }

    /**
     * Baritone access (could just be static honestly)
     */
    public Baritone getClientBaritone() {
        if (getPlayer() == null) {
            return (Baritone) BaritoneAPI.getProvider().getPrimaryBaritone();
        }
        return (Baritone) BaritoneAPI.getProvider().getBaritoneForPlayer(getPlayer());
    }

    /**
     * Baritone settings access (could just be static honestly)
     */
    public Settings getClientBaritoneSettings() {
        return Baritone.settings();
    }

    // (2026-09-12 v8) 逃生挖掘豁免开关: 见 _escapeDigOverride 注释。
    public void setEscapeDigOverride(boolean value) {
        _escapeDigOverride = value;
    }

    public boolean isEscapeDigOverride() {
        return _escapeDigOverride;
    }

    /**
     * Baritone settings special to AltoClef (could just be static honestly)
     */
    public AltoClefSettings getExtraBaritoneSettings() {
        return AltoClefSettings.getInstance();
    }

    /**
     * AltoClef Settings
     */
    public adris.altoclef.Settings getModSettings() {
        return _settings;
    }

    /**
     * Butler controller. Keeps track of users and lets you receive user messages
     */
    public Butler getButler() {
        return _butler;
    }

    /**
     * Sends chat messages (avoids auto-kicking)
     */
    public MessageSender getMessageSender() {
        return _messageSender;
    }

    /**
     * Does Inventory/container slot actions
     */
    public SlotHandler getSlotHandler() {
        return _slotHandler;
    }

    /**
     * Minecraft player client access (could just be static honestly)
     */
    public ClientPlayerEntity getPlayer() {
        return MinecraftClient.getInstance().player;
    }

    /**
     * Minecraft world access (could just be static honestly)
     */
    public ClientWorld getWorld() {
        return MinecraftClient.getInstance().world;
    }

    /**
     * Minecraft client interaction controller access (could just be static honestly)
     */
    public ClientPlayerInteractionManager getController() {
        return MinecraftClient.getInstance().interactionManager;
    }

    /**
     * Extra controls not present in ClientPlayerInteractionManager. This REALLY should be made static or combined with something else.
     */
    public PlayerExtraController getControllerExtras() {
        return _extraController;
    }

    /**
     * Manual control over input actions (ex. jumping, attacking)
     */
    public InputControls getInputControls() {
        return _inputControls;
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task) {
        runUserTask(task, () -> { });
    }

    /**
     * Run a user task
     */
    public void runUserTask(Task task, Runnable onFinish) {
        _userTaskChain.runTask(this, task, onFinish);
    }

    /**
     * Cancel currently running user task
     */
    public void cancelUserTask() {
        _userTaskChain.cancel(this);
    }

    /**
     * Takes control away to eat food
     */
    public FoodChain getFoodChain() {
        return _foodChain;
    }

    /**
     * Takes control away to defend against mobs
     */
    public MobDefenseChain getMobDefenseChain() {
        return _mobDefenseChain;
    }

    /**
     * Takes control away to perform bucket saves
     */
    public MLGBucketFallChain getMLGBucketChain() {
        return _mlgBucketChain;
    }

    public void log(String message) {
        log(message, MessagePriority.TIMELY);
    }

    /**
     * Logs to the console and also messages any player using the bot as a butler.
     */
    public void log(String message, MessagePriority priority) {
        Debug.logMessage(message);
        _butler.onLog(message, priority);
    }

    public void logWarning(String message) {
        logWarning(message, MessagePriority.TIMELY);
    }

    /**
     * Logs a warning to the console and also alerts any player using the bot as a butler.
     */
    public void logWarning(String message, MessagePriority priority) {
        Debug.logWarning(message);
        _butler.onLogWarning(message, priority);
    }

    private void runEnqueuedPostInits() {
        synchronized (_postInitQueue) {
            while (!_postInitQueue.isEmpty()) {
                _postInitQueue.poll().accept(this);
            }
        }
    }

    /**
     * Use this to access AltoClef as an external library.
     */
    public static void subscribeToPostInit(Consumer<AltoClef> onPostInit) {
        synchronized (_postInitQueue) {
            _postInitQueue.add(onPostInit);
        }
    }
}
