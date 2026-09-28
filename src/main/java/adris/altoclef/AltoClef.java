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
import adris.altoclef.util.RenderThreadWatchdog;
import adris.altoclef.util.helpers.NavStuckWatchdog;
import adris.altoclef.util.helpers.OffhandHygiene;
import adris.altoclef.util.helpers.ScaffoldBlocks;
import adris.altoclef.util.helpers.TaskStackSnapshot;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ModInitializer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.WallBlock;
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
        // (2026-09-25 渲染线程冻结取证看门狗) 守护线程监控 client tick 心跳,
        // 主线程冻结 >5s 时转储全线程栈 + 任务链到 watchdog.log。172433 会话
        // 渲染完全卡住零取证的事故对策。与加载状态无关, 游戏起来即监控。
        RenderThreadWatchdog.start(this);
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
        // (2026-09-27 出洞收尾) 出地表后"走离洞口 ≥24 格 ×3"的唯一持有者。
        // 放在 User Tasks 之上一点点 (52), 用来替代"指望各任务自己消费引导配额"
        // 的隐式契约 —— 详见 chains/SurfaceExitChain 类注释。
        new SurfaceExitChain(_taskRunner);

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
            // (2026-09-21 深板岩不用于搭路, 76c89e8d 的"计数修复"只是半截) baritone
            // InventoryBehavior.selectThrowawayForLocation 放置选材按
            // acceptableThrowawayItems 【清单顺序】逐项找背包 (反编译实锤: iterator
            // 循环 + lambda$3 逐项 item.equals(stack.getItem())), 而灌入顺序 =
            // Settings.throwawayItems 的"丢垃圾"顺序 —— 闪长岩/安山岩/花岗岩/凝灰岩/
            // 泥土/沙砾全排在深板岩圆石前面, 主世界背包常备杂石 → 深板岩永远轮不到
            // (用户"有深板岩却不用搭路"); 且 DEEPSLATE/STONE 本体 (BlockItem) 根本
            // 不在 throwawayItems, 有也不用。改按 ScaffoldBlocks.SAFE_MATERIALS 的
            // 搭路优先序重排, 清单外可放置项 (树叶等) 保持原相对顺序排在后面。
            List<Item> ordered = new java.util.ArrayList<>(baritoneCanPlace.size());
            for (Item scaffold : ScaffoldBlocks.SAFE_MATERIALS) {
                if (baritoneCanPlace.remove(scaffold)) {
                    ordered.add(scaffold);
                } else if (scaffold instanceof BlockItem && !ordered.contains(scaffold)) {
                    // SAFE_MATERIALS 里的放置型方块若不在 throwawayItems (深板岩/
                    // 石头本体/哭泣黑曜石) 也补进 baritone 放置清单 —— 有就用,
                    // 不影响 altoclef 自己"丢垃圾"清单的语义。
                    ordered.add(scaffold);
                }
            }
            ordered.addAll(baritoneCanPlace);
            getClientBaritoneSettings().acceptableThrowawayItems.value.addAll(ordered);
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
        // (2026-09-25 看门狗心跳) 本方法跑在 Render thread 上, 每 tick 一次
        // volatile 写。主线程在 tick 前/tick 中/帧渲染任一处冻结, 心跳即停,
        // 守护线程 5s 后转储现场 (RenderThreadWatchdog 类注释)。
        RenderThreadWatchdog.heartbeat();
        runEnqueuedPostInits();

        // (2026-08-30 没镐别挖石头墙) 客户线程刷新"当前采矿等级"缓存, 供寻路
        // 谓词用。仅 inGame 时刷新; 玩家空手/复活 (HAND) 会让寻路避开硬岩。
        if (inGame()) {
            _currentMiningReq = adris.altoclef.util.helpers.StorageHelper.getCurrentMiningRequirement(this);
            tickInteractionPauseWatchdog();
            // (2026-09-28 斜跳反复失败保险) 反应式缺口: 普通地形上同一斜跳落点连续
            // 跳不上去 (JumpStuckResolver 的零速 streak 口径接不住跳跃循环) → 短暂
            // 关跳 + 重算让 A* 改选垫块路线, 窗口到期自动恢复。必须先于
            // tickLavaNavGuard 执行 —— 后者每 tick 写回 allowDiagonalAscend (合并
            // 两个抑制源), 详见 DiagonalJumpFailDetector 类注释。
            adris.altoclef.util.helpers.DiagonalJumpFailDetector.tick(this);
            // (2026-09-18 岩浆邻域导航保险) 旋转跳 (斜向升格) 失败率>50%, 落地邻域
            // 有岩浆时动态禁跳 (下界全程 + 主世界深层), 详见 tickLavaNavGuard 注释。
            tickLavaNavGuard();
            // (2026-09-19 修 17:58 围场施工站桩) baritone 的"岩浆可游"开关与现场
            // 状态解耦同步, 详见 tickLavaSwimSync 注释。
            tickLavaSwimSync();
            // (2026-09-22 木板搭路闸门) 木板按背包存量动态进/出 baritone 可放置
            // 清单, 详见 tickPlankScaffoldSync 注释。
            tickPlankScaffoldSync();
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
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() == Blocks.SOUL_SAND) {
                // (2026-09-20 灵魂沙边缘浸岩浆) 09-13 的**无条件**放行有个副作用: baritone
                // 会心安理得地把路径规划到"紧贴岩浆的灵魂沙"上 —— 判定链是 09-13 自己
                // 留下的 (灵魂沙 canWalkOn=false ⇒ 全局放行), 但放行时没人问过
                // "站上去脚下会不会被浇到"。
                //   灵魂沙顶面 = 0.875 (14/16), 站上去脚比正常方块低 0.125; 相邻同层
                //   岩浆的液面高度 ≥0.875 ⇒ 身体 AABB 与岩浆相交 ⇒ 持续着火, 并触发
                //   EscapeFromLavaTask 的原地弹跳 (20260920-155006 现场: 站 (17,31,71)
                //   12.5s, netXZ=0.00 / yRange=1.67, 血 20→0, cause=lava)。
                // 处置: 邻域有岩浆的灵魂沙不再作为**导航落点** —— 从上游不让 bot 站上去。
                // 例外: bot 自己当前站位仍放行。否则"已经站在上面"会退化成
                //   canWalkOn=false → A* 起点判据全灭 → Open set size: 0 → 站桩
                //   (与 09-13 那次事故同构), 连走开的机会都没有。
                //   语义与 09-19 栅栏"只解冻当前站位"完全同构: 放行"我要走开"这个事实,
                //   不放行"我要走过去"这个意图。
                if (isOwnCurrentSupport(pos)) return true;
                return !hasAdjacentLava(world, pos);
            }
            // (2026-09-19 栅栏/墙/栏杆"解冻站位") MovementHelper.canWalkOn 是硬编码
            // 白名单 —— isBlockNormalCube(整格满立方) 之后只放行
            // LADDER/VINE/FARMLAND/DIRT_PATH/CHEST/ENDER_CHEST/TRAPPED_CHEST/GLASS/
            // END_PORTAL_FRAME/SlabBlock/StairsBlock/AzaleaBlock/StainedGlassBlock,
            // **没有 FenceBlock / WallBlock / PaneBlock**(javap 逐个核对)。栅栏/墙/
            // 栏杆的碰撞盒顶面(1.5 / 1.5 / 1.0)是真实可站面, 于是 bot 一旦站上去,
            // "脚下不可站" → 与该 class 的 COST_INF 判据全灭, 与灵魂沙同构。
            //
            // ⚠ 刻意【不】无条件放行这三类: canWalkOn=true 隐含不变量"该方块顶面
            // ≤1 格、1.25 跳跃够得着"(白名单里全是 ≤1 高)。若全局放行 1.5 高的栅栏,
            // MovementAscend 会规划出"跳上栅栏"的假路径 —— 物理上跳不上去 → 制造
            // 新的站桩。故只解冻【bot 自己当前脚所在格 / 脚下方格】这一格:
            // 描述的是"我正站在这里、我要走开", 事实成立、不可能引入假路径。
            return isThinStandableBlock(state) && isOwnCurrentSupport(pos);
        });

        // 配套 (v29): 执行层也当普通方块走。规划层放行后 baritone 会真的把路径
        // 规划到灵魂沙上, 此时再走 MovementTraverse 的"灵魂沙特判分支"(接近目标
        // 中心 <0.85 格就 MOVE_BACK 刹车) 只会在灵魂沙上原地蹭, 关掉它保持一致。
        getExtraBaritoneSettings().treatSoulSandAsOrdinaryBlock(true);

        // ---- (2026-09-27) 杜鹃花丛"隐形体": baritone 两套判据打架 ----
        //
        // 现场 (会话 20260927-145243, record t=10557..10855 实锤):
        //   bot **站在杜鹃花丛顶面** (脚所在格 (-47,-29,-29), 其下一格 = azalea,
        //   再下 moss_block; 脚底 y 精确 -29.000 = 花丛碰撞盒顶面), 位置钉死在
        //   格子边界 (-46.5,-28.5)±0.1, hSpeed 恒定 0.063~0.064 (贴边推挤),
        //   15s 内 yaw 累计转 7800°+ (约 22 圈 = 用户"疯狂转向不动")。
        //   任务链 = ConstructNetherPortalBucketTask -> PlaceObsidianBucketTask ->
        //   DestroyBlockTask, 三者全 extends Task ⇒ 卡死处置链一处未接 ⇒
        //   6s 进度判负 -> TimeoutWanderTask (wander 同样穿不过, 原地卡到会话结束)。
        //   同会话 (-48,-25,-44) 还有约 330 帧同类现场。
        //
        // 根因在方块注册本身 (minecraft-project jar 字节码实锤):
        //   AZALEA = register("azalea", new AzaleaBlock(Settings.of(Material.PLANT)
        //              .breakInstantly().sounds(AZALEA).nonOpaque()));
        //   **只调 nonOpaque(), 没有 noCollision()** ⇒
        //     碰撞盒 = Settings 默认满立方 (物理实心阻挡)
        //     isOpaque() = false
        //   于是 baritone 对**同一个方块**的两套判据结论相反:
        //     canWalkThrough 用 isOpaqueFullCube -> false ⇒ **判"可穿过"**(错)
        //     canWalkOn      用 isShapeFullCube   -> true  ⇒ 判"可站上去"(对)
        //   baritone 照前者规划出一条**穿过花丛的路径**, 物理上花丛实心 ⇒ 撞死。
        //   这也顺带解释了"bot 为什么会站在花丛上": canWalkOn 一侧本来就放行。
        //   → 症状家族 = 03-consistency.md"两侧口径不一致", 与 09-19 栅栏
        //     (canWalkThrough 特批放行 vs canWalkOn 覆盖不到) 同族, 方向相反。
        //
        // 为什么用 avoidWalkingThrough, 而不是 canWalkOnForce 的反向:
        //   本仓 baritone 只有 force-* 两个通行钩子 ——
        //     getForceWalkOnPredicates()           (canWalkOn 放行)
        //     getForceAvoidWalkThroughPredicates() (canWalkThrough / canWalkOn 拒绝)
        //   后者在 MovementHelper.canWalkThrough (offset 124~128) 与 canWalkOn
        //   (offset 186~192) 各有一个消费点, 语义 = "这格不许身体进去" —— 正是
        //   "别把路径规划到花丛里"需要的窄语义 (既有唯一消费点 = 龙息)。
        //
        // ⚠ 必修的例外: 谓词命中会让 canWalkOn 那边把**下方那格**判成不可站 ——
        //   若不排除 bot 自己当前站位, 一旦 bot 已经站在花丛上 (本现场正是如此),
        //   A* 起点判据全灭 = Open set size: 0 = 比卡住更死的站桩。
        //   与 09-19 栅栏 / 09-20 灵魂沙"只解冻当前站位"同构: 放行"我正站在这里",
        //   不放行"我要走过去"。isOwnCurrentSupport 已含脚格与脚下方格。
        //
        // 范围: 只收 azalea / flowering_azalea (用户本场所报)。同族的
        //   azalea_leaves / flowering_azalea_leaves (同样 nonOpaque + 满格碰撞)
        //   刻意**不放**: 树冠通行是另一个高频场景, 改动面必须单独评估。
        // 开销: 谓词跑在 baritone A* 热路径, 但先做 O(1) 方块类型早退 (非杜鹃即
        //   返回 false), 与上面的 forceWalkOn 谓词同款。只读世界、不回调 baritone。
        getExtraBaritoneSettings().getForceAvoidWalkThroughPredicates().add(pos -> {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world == null) return false;
            int y = pos.getY();
            if (y < world.getBottomY() || y >= world.getTopY()) return false;
            Block b = world.getBlockState(pos).getBlock();
            if (b != Blocks.AZALEA && b != Blocks.FLOWERING_AZALEA) return false;
            return !isOwnCurrentSupport(pos);
        });

        // Give baritone more time to calculate paths. Sometimes they can be really far away.
        // Was: 2000L
        getClientBaritoneSettings().failureTimeoutMS.value = 6000L;
        // Was: 5000L
        getClientBaritoneSettings().planAheadFailureTimeoutMS.value = 10000L;
        // Was 100
        getClientBaritoneSettings().movementTimeoutTicks.value = 200;

        // (2026-09-19 黑曜石不参与"寻路顺手挖")
        //
        // 现场: 下界堡垒附近赶路, bot 把一块完全不必挖的黑曜石挖穿 (钻石镐无效率
        // 附魔, 散块, 不属于任何门结构)。不是任务在采集 —— 是 baritone 寻路自己
        // 选的, 因此 tasks.log 里连一条 DestroyBlockTask 都没有 (盲区)。
        //
        // 根因是**成本账本里没有"黑曜石很贵"这一项**:
        //   MovementHelper.getMiningDurationTicks = 1/ToolSet.getStrVsBlock(state)
        //                                          + blockBreakAdditionalCost
        //   ToolSet: 镐速 ÷ 硬度 ÷ 30 → 钻石镐挖黑曜石 = 8/50/30 = 0.00533/tick
        //   ⇒ 187.5 ticks ≈ 9.4s; 而 blockBreakAdditionalCost 默认只有 2 ticks
        //   (0.1 秒), 全库只有岩浆逃生(20)/龙息(10)调高过 —— 对 187 这个量级无感。
        //   步行一格成本 4.63 ⇒ 挖一块黑曜石 ≈ 绕 41 格路; 堡垒里上下坡绕行轻松超过
        //   这个值, 于是"挖穿"成了 baritone 眼里的理性选择。它只算时间, 不知道
        //   黑曜石是不可再生的战略物资。
        //
        // 为什么用 blocksToAvoidBreaking(软) 而不是 blocksToDisallowBreaking(硬禁):
        //   软: ToolSet.avoidanceMultiplier 在方块命中该列表时返回
        //       avoidBreakingMultiplier(默认 0.1), 乘在**挖掘速度**上 ⇒ 成本 ×10
        //       → 189.5 → 1895 ticks ≈ 等价绕 409 格, 寻路基本不会再选它。
        //       且 MineProcess.plausibleToBreak 判据是 `成本 < 1e6`, 1895 仍通过,
        //       ⇒ WorldHelper.canBreak 不受影响, CollectObsidianTask 的显式采集照常可用。
        //   硬禁: MovementHelper.avoidBreaking 直接返回 COST_INF ⇒ plausibleToBreak
        //       = false ⇒ CollectObsidianTask 的 goodObsidian 谓词再也找不到可挖目标,
        //       **采集合法路径一起废掉**。同理 AltoClef 自己的 avoidBlockBreaking
        //       谓词(shouldAvoidBreaking)也是 COST_INF, 一样不能用。
        // 门框保护另走 BotBehaviour.isPortalBodyBlock(栈底全局谓词), 与本项不冲突;
        // 本项把散块/废弃传送门结构/哭泣黑曜石一起拔到"寻路不值得挖"的量级。
        getClientBaritoneSettings().blocksToAvoidBreaking.value.add(Blocks.OBSIDIAN);
        getClientBaritoneSettings().blocksToAvoidBreaking.value.add(Blocks.CRYING_OBSIDIAN);
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

    // ==== (2026-09-19 栅栏/墙/栏杆 细方块可站) ====

    /**
     * "细方块": 碰撞盒非满立方但顶面是真实可站面 —— 栅栏(1.5) / 墙(1.5) / 栏杆玻璃板(1.0)。
     * baritone 的 canWalkOn 白名单不含这三类 (见 forceWalkOnPredicates 注册点注释)。
     */
    private static boolean isThinStandableBlock(BlockState state) {
        Block b = state.getBlock();
        return b instanceof FenceBlock || b instanceof WallBlock || b instanceof PaneBlock;
    }

    /**
     * pos 是否就是 bot 自己当前所处的格 / 脚下方格 —— 用于把"细方块解冻"限制在
     * "解冻当前站位"这一格, 绝不放行任意位置的栅栏 (否则 MovementAscend 会规划
     * 跳上 1.5 高栅栏的假路径)。谓词跑在 baritone 计算线程, 只读 player 位置。
     */
    private static boolean isOwnCurrentSupport(BlockPos pos) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return false;
        BlockPos feet = p.getBlockPos();
        return pos.equals(feet) || pos.equals(feet.down());
    }

    /**
     * (2026-09-20 灵魂沙边缘浸岩浆) pos 的"同层 8 邻域(含对角)"或"上方一层"是否存在
     * 岩浆方块。用于把灵魂沙的 canWalkOnForce 放行收窄到"不会把脚浇到岩浆里"的位置。
     *
     * 为什么要查上一层的对角: 玩家 AABB 半宽 0.3, 站在灵魂沙边缘时身体会横向探出到
     * 相邻列; 相邻列 y+1 的岩浆液面(0.888~1.0)同样能浸到脚(灵魂沙顶面仅 0.875)。
     * 下方一层刻意不查 —— 脚底就在站立方块顶面, 物理上不可能浸到下一层。
     *
     * 开销: 本谓词被 baritone 的 A* 在热路径上调用, 但只有"该方块确实是灵魂沙"时
     * 才会进到这里(前面已早退), 且 16 次 getBlockState 全部命中即返回, 可忽略。
     * 只读世界、不回调 baritone(避免谓词自递归)。
     */
    private static boolean hasAdjacentLava(ClientWorld world, BlockPos pos) {
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (world.getBlockState(pos.add(dx, dy, dz)).getBlock() == Blocks.LAVA) return true;
                }
            }
        }
        return false;
    }

    // ==== (2026-09-19 岩浆里 baritone 起点判据同步) ====
    private boolean _lavaSwimSynced = false;

    /**
     * (2026-09-19 修"围场施工时站在岩浆里站桩", 现场 20260919-175617 / 17:58:40)
     *
     * 现场 (snapshot.log 实锤):
     *   player=(763,80,140), target=763,80,140 = minecraft:lava (fluid=true),
     *   below=763,79,140 = netherrack (solid=true) —— 支撑正常, 问题在脚所在格本身。
     *   baritone: `22 movements considered / Open set size: 0 / PathNode map size: 1
     *   / nodes per second: 2147483647`(int 溢出) 刷屏 → PlaceStructureBlockTask
     *   2s 超时轮转 → 整条围场建造链空转 (17:58:28 `build target stalled, rotating` 同刻)。
     *
     * 字节码链 (patched baritone 1.18.2, javap 取证):
     *   MovementTraverse.cost() 先算 getMiningDurationTicks(ctx, srcX, srcY, srcZ, srcState),
     *   ≥1e6 直接 return COST_INF (offset 168~194); 而 getMiningDurationTicks 是
     *     `if (canWalkThrough(state)) return 0;`  ← 岩浆唯二被放行的条件是
     *        canSwimThroughLava() 为真 ∧ 方块==LAVA ∧ 上方无流体 (canWalkThrough offset 139~175)
     *     `if (!state.getFluidState().isEmpty()) return 1e6;`
     *   ⇒ 开关关闭时岩浆格恒 1e6 ⇒ 该起点出发的 22 种移动全部 COST_INF。
     *   (同款指纹: 灵魂沙 09-13、岩浆 09-14 —— "A* 一步不展开先怀疑起点判据"。)
     *
     * 根因 = 开关与现场脱钩: `allowSwimThroughLava(true)` 只写在
     *   EscapeFromLavaTask.onStart。该洞 09-14 已修过一次 (WorldSurvivalChain.isInLavaOhShit
     *   改成"只要身体浸在岩浆里就派 Lava Escape"); 09-19 16:05 为"岩浆从高处浇下来时
     *   专心盖墙"加的施工豁免 (防火 ∧ isEnclosureBuildWorkActive ⇒ 不派 Lava Escape)
     *   **把同一个洞重新打开了** —— 豁免放行了"留在岩浆里", 却没配套放行 baritone 的开关。
     *
     * 本方法把开关直接对齐现场, 不经任何任务:
     *   on  = 身体浸在岩浆里, 或脚所在格 / 脚下方格是岩浆;
     *   off = 其余一切情况。
     * 只在"已经泡在岩浆里"才开 ⇒ 不会从安全位置规划穿岩浆; 脱离即关 ⇒ 暴露窗口有界。
     * 这正是 09-14 那条修复的语义, 此处补上 09-19 的围场豁免分支。
     */
    private void tickLavaSwimSync() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return;
        }
        boolean inLava = player.isInLava();
        if (!inLava) {
            // baritone 起点判据看的是"脚所在格": 薄岩浆层可能只淹到脚、isInLava() 仍为
            // false, 而 getMiningDurationTicks(脚所在格) 已经 1e6。故补一层方块口径
            // (与 CollectBlazeRodsTask.isLavaInEnclosureRegion 同口径: block == LAVA)。
            BlockPos feet = player.getBlockPos();
            inLava = world.getBlockState(feet).getBlock() == Blocks.LAVA
                    || world.getBlockState(feet.down()).getBlock() == Blocks.LAVA;
        }
        if (inLava == _lavaSwimSynced) {
            return;
        }
        _lavaSwimSynced = inLava;
        getBehaviour().allowSwimThroughLava(inLava);
        String m = "[LavaSwim] baritone 岩浆可游=" + (inLava ? "on" : "off")
                + " pos=" + player.getBlockPos().toShortString()
                + " inLava=" + player.isInLava()
                + " fireRes=" + player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                + " (开关只跟现场走; 旧的只挂在 EscapeFromLavaTask.onStart 会让"
                + " 施工豁免期间起点判据全废 → Open set size: 0)";
        Debug.logToFile("altoclef_diag.log", m);
    }

    /**
     * (2026-09-22 木板搭路闸门) 木板按背包存量动态进/出 baritone 的
     * {@code acceptableThrowawayItems}。见字段区注释与 ScaffoldBlocks.PLANK_ITEMS。
     *
     * <p>设计要点:
     * <ul>
     * <li>1s 节流 (baritone 每次放置选材才读清单, 不需要逐 tick 改);</li>
     * <li>双阈值迟滞: ≥8 开、≤4 关, 4..7 保持现状 —— 背包临界值不横跳;</li>
     * <li>进清单时挂在【末尾】: 泥土/圆石/深板岩圆石等杂石先消耗, 木板是最后
     *     手段 (Settings 加载处的 SAFE_MATERIALS 重排不感知木板, 这里 add 在尾部
     *     恰好就是最低优先);</li>
     * <li>状态变化只在实际翻转时打日志, [ScaffoldPlanks] 可现场核查。</li>
     * </ul>
     */
    private void tickPlankScaffoldSync() {
        if (!_plankScaffoldTimer.elapsed()) {
            return;
        }
        _plankScaffoldTimer.reset();
        int planks = adris.altoclef.util.helpers.ScaffoldBlocks.countPlanksInInventory(this);
        boolean want = _plankScaffoldEnabled
                ? planks > PLANK_SCAFFOLD_DISABLE_MAX
                : planks >= PLANK_SCAFFOLD_ENABLE_MIN;
        if (want == _plankScaffoldEnabled) {
            return;
        }
        _plankScaffoldEnabled = want;
        List<Item> placeable = getClientBaritoneSettings().acceptableThrowawayItems.value;
        if (want) {
            for (Item plank : adris.altoclef.util.helpers.ScaffoldBlocks.PLANK_ITEMS) {
                if (!placeable.contains(plank)) {
                    placeable.add(plank);
                }
            }
        } else {
            // 木板族 8 项一次摘净。
            placeable.removeIf(adris.altoclef.util.helpers.ScaffoldBlocks::isPlankItem);
        }
        String m = "[ScaffoldPlanks] " + (want ? "允许" : "停用") + "木板垫路/搭桥 planks=" + planks
                + " (启用阈值 ≥" + PLANK_SCAFFOLD_ENABLE_MIN
                + ", 停用阈值 ≤" + PLANK_SCAFFOLD_DISABLE_MAX
                + ", 木板排在可放置清单末尾=最低优先)";
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    // ==== (2026-09-18 岩浆邻域导航保险) ====
    // 背景: baritone 的斜向升格 (allowDiagonalAscend, 俗称"旋转跳") 执行失败率
    // 实测 >50% —— 从 (1,0,0) 斜跳 (0,1,1) 且头顶 (1,2,1) 有顶棚时需要空中转向,
    // baritone 的执行模型做不好这一跳, 掉下去落岩浆直接死 (123145 场次 12:37 /
    // 12:48 / 13:10 三次, 死前均为纯赶路)。initializeBaritoneSettings 的原注释
    // 早就写了 "minor risk EXCEPT in the nether, where it is a huge risk"。
    //
    // 处理: 玩家落地邻域有岩浆时, 动态关掉斜向升格 —— **只做这一件事**。
    //   【不碰 blockPlacementPenalty】(2026-09-19 19:25 用户拍板): 早期版本顺手
    //   把放置惩罚压到 2.0, 意图是让 A* 改选"垫一格绕行"; 但那是**降低**放置成本
    //   (鼓励垫块), 不是"抑制垫块", 既非用户意图、解除时又会残留到主世界。已整体
    //   移除, 本保险只管 allowDiagonalAscend 这一个开关。
    //   垫块不足时保持原行为: 硬禁跳可能把 bot 冻在原地, 跳总比冻死强
    //   (PillarJumpHelper.countBaritonePlaceable 判定, 与 baritone 的 hasThrowaway
    //   同口径; 门槛 LAVA_NAV_MIN_PLACEABLE, 2026-09-19 从 >0 提到 ≥8 —— 1 块撑不
    //   起替代路线, 却把跳禁了)。
    // 适用维度 (2026-09-19 19:25 用户补选): 下界全程 + **主世界深层 y ≤ 20**
    //   (钻石层岩浆湖、深板岩层岩浆海同样致命)。末地无岩浆, 不参与。
    // ===== (2026-09-19 收紧判据, 用户"触发太频繁") =====
    // 旧判据 = "玩家周围 25x17x25 (水平 ±12、垂直 -12..+4) 立方体内存在任意一格
    // LAVA"的布尔存在量, 三个结构性缺陷:
    //   (a) 无距离权重 — 12 格外的整片岩浆海与脚边一格岩浆判据相同, 而下界到处
    //       是岩浆 ⇒ 抑制几乎恒真, 旋转跳长期关闭, 路径无谓变长;
    //   (b) 扫描盒与"会不会掉进去"无关 — 脚下 12 格深的岩浆、头顶 +4 处渗流的
    //       岩浆滴 (下界极常见) 都算数;
    //   (c) 未查区块加载 — 未加载区块 getBlockState 返回空气, 走到加载边界时判据
    //       突变 (放大下面的横跳)。
    // 新判据 = 触发收紧 + 解除迟滞:
    //   ① 触发只看**落地邻域** (水平切比雪夫 ≤3、下 2、上 1), 这是"斜向升格失败
    //      会掉进去"的真实范围, 与旋转跳的风险直接对应。
    //   ② 解除走**双半径迟滞**: 激活后用更大的外围 (水平 ≤6、下 3、上 2) 判断
    //      "是否已离开这片岩浆区域", 外围连续 LAVA_NAV_RELEASE_CONFIRM_SEC 秒无
    //      岩浆才解除。旧判据 1s 一次布尔翻转、无迟滞, 在湖边来回走就反复开关
    //      旋转跳 —— 与 09-14 岩浆横跳、SurfaceHelper 判据4 是同一根因。
    //   ③ 扫描补 isChunkLoaded 判定。
    // 扫描节流 0.25s (体积 10625 → 1014, 开销更小、灵敏度更高); 每 tick 强制写
    // allowDiagonalAscend, 防任务栈 applyState 把旧值写回去。
    // 脚下邻域 (兜底): 半径 3 → 2 (2026-09-19 用户"洞里一直触发"/"应该减小触发半径"),
    // 前方危险改由下面的**路径预检**接管 —— 它比"人已经站在危险区"更早、更准。
    private static final int LAVA_NAV_TRIGGER_RADIUS = 2;
    private static final int LAVA_NAV_TRIGGER_DOWN = 2;
    private static final int LAVA_NAV_TRIGGER_UP = 1;
    private static final int LAVA_NAV_HOLD_RADIUS = 6;
    private static final int LAVA_NAV_HOLD_DOWN = 3;
    private static final int LAVA_NAV_HOLD_UP = 2;
    private static final double LAVA_NAV_RELEASE_CONFIRM_SEC = 3.0;
    // ==== 路径预检 (2026-09-19 用户: "导航是一次性的, 安全区算好的路径会穿过危险区") ====
    // baritone 只在**算路时**读 allowDiagonalAscend, 已算好的路径里那个斜跳动作会照
    // 执行 —— 所以"人走进危险区才关跳"永远晚一步。改为查**剩余路线**:
    //   IPathExecutor exec = getPathingBehavior().getCurrent();
    //   exec.getPath().movements() 从 exec.getPosition() 起 → 每个动作的落脚点 getDest()
    // 只查其中的**斜向升格** (MovementDiagonal): 关跳只影响它, 路径里没斜跳就不触发
    // (顺带治好"洞里一直触发"); 落脚点邻域的危险判据见 destDangerous()。
    // 命中后 forceCancel() 请求重算 (节流), 让新路径在开关已关的状态下重新生成。
    private static final int LAVA_NAV_PATH_LOOKAHEAD = 24;
    /** 落脚点邻格下方连续非实心 ≥ 此深度 = 悬崖/深坑 (MC 摔落伤害自第 4 格起)。 */
    private static final int LAVA_NAV_FALL_RISK_DEPTH = 4;
    private static final double LAVA_NAV_REPLAN_THROTTLE_SEC = 3.0;
    /** 连续重算仍命中的上限: 到了就停止重算 (地形必经之路换了也没用, 只白烧 A*)。 */
    private static final int LAVA_NAV_REPLAN_MAX_STREAK = 3;
    /** 触发后至少保持这么久才允许解除 (防"触发→解除→触发"的 4~5s 周期横跳)。 */
    private static final double LAVA_NAV_MIN_HOLD_SEC = 8.0;
    /** 主世界参与判据的最大 Y (钻石层/深板岩层岩浆湖; 地表无岩浆, 不参与)。 */
    private static final int LAVA_NAV_OVERWORLD_MAX_Y = 20;
    /** 关跳所需的 baritone 可放置垫块数: 太少时垫不出替代路线, 禁跳反把 bot 冻住。 */
    private static final int LAVA_NAV_MIN_PLACEABLE = 8;
    private final adris.altoclef.util.time.TimerGame _lavaNavScanTimer = new adris.altoclef.util.time.TimerGame(0.25);
    /** 外围连续无岩浆计时 (解除确认窗): 外围任一格有岩浆就重置。 */
    private final adris.altoclef.util.time.TimerGame _lavaNavClearTimer = new adris.altoclef.util.time.TimerGame(LAVA_NAV_RELEASE_CONFIRM_SEC);
    /** 命中危险后请求重算路径的节流 (防 forceCancel 反复重算同一条路)。 */
    private final adris.altoclef.util.time.TimerGame _lavaNavReplanTimer = new adris.altoclef.util.time.TimerGame(LAVA_NAV_REPLAN_THROTTLE_SEC);

    // ==== (2026-09-22 木板搭路闸门, 用户"木板也作为搭路方块之一, 木板过少时不用,
    // 注意原木和木板的转化") ====
    // 背景 (20260922 0921 本机现场实锤): 砍树捡掉落时目标悬空 (掉在树冠上),
    // bot 背包 25 根原木全是保护物资不可放置 → baritone 无任何可搭方块 →
    // MovementPillar 无解 → A* 对 4 格外的目标洪泛全场 (单次 ~90 万 movements,
    // ~81k nodes/s ≈ 11s 满核 CPU), 反复重算 = "卡顿十几秒"。有可垫方块时
    // A* 会把垫柱当可行动作直接算出路, 洪泛不会发生。
    // 实现: 木板动态进/出 baritone acceptableThrowawayItems (真放置清单),
    // 阈值 + 迟滞防横跳; 不动 ScaffoldBlocks.SAFE_MATERIALS (那是补货目标语义,
    // 见 ScaffoldBlocks.PLANK_ITEMS 注释)。
    /** 木板 ≥ 该值 → 允许木板垫路/搭桥。8 = 合成台4 + 木棍2 + 富余2。 */
    private static final int PLANK_SCAFFOLD_ENABLE_MIN = 8;
    /** 已启用后木板 ≤ 该值 → 收回 (迟滞带 4..7 保持现状, 防临界横跳)。 */
    private static final int PLANK_SCAFFOLD_DISABLE_MAX = 4;
    private boolean _plankScaffoldEnabled = false;
    private final adris.altoclef.util.time.TimerGame _plankScaffoldTimer = new adris.altoclef.util.time.TimerGame(1.0);
    /** 触发后的最小保持计时: 解除需同时满足"外围无岩浆 + 路径无危险斜跳 + 已保持 ≥8s"。 */
    private final adris.altoclef.util.time.TimerGame _lavaNavHoldTimer = new adris.altoclef.util.time.TimerGame(LAVA_NAV_MIN_HOLD_SEC);
    /** 连续"重算后仍命中"计数; 路径一旦干净立即清零。 */
    private int _lavaNavReplanStreak = 0;
    private boolean _lavaNavSuppressActive = false;
    private boolean _lavaNavNoBlockWarned = false;

    /** 本保险是否适用: 下界全程 + 主世界深层 (y ≤ LAVA_NAV_OVERWORLD_MAX_Y); 末地无岩浆。 */
    private boolean lavaNavApplies() {
        adris.altoclef.util.Dimension dim = adris.altoclef.util.helpers.WorldHelper.getCurrentDimension();
        if (dim == adris.altoclef.util.Dimension.NETHER) return true;
        return dim == adris.altoclef.util.Dimension.OVERWORLD
                && getPlayer().getY() <= LAVA_NAV_OVERWORLD_MAX_Y;
    }

    private void tickLavaNavGuard() {
        if (inGame() && getPlayer() != null && getWorld() != null && _lavaNavScanTimer.elapsed()) {
            _lavaNavScanTimer.reset();
            boolean triggerHit = false; // 脚下邻域 (内圈) 有岩浆
            boolean holdHit = false;    // 外围警戒环有岩浆
            if (lavaNavApplies()) {
                BlockPos center = getPlayer().getBlockPos();
                int r = LAVA_NAV_HOLD_RADIUS;
                scan:
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dy = -LAVA_NAV_HOLD_DOWN; dy <= LAVA_NAV_HOLD_UP; dy++) {
                            BlockPos p = center.add(dx, dy, dz);
                            // 未加载区块 getBlockState 返回空气, 走到加载边界会造成
                            // 判据突变 (解除迟滞的放大器), 直接跳过。
                            if (!getChunkTracker().isChunkLoaded(p)) continue;
                            if (getWorld().getBlockState(p).getBlock() != Blocks.LAVA) continue;
                            holdHit = true;
                            int horiz = Math.max(Math.abs(dx), Math.abs(dz));
                            if (horiz <= LAVA_NAV_TRIGGER_RADIUS
                                    && dy >= -LAVA_NAV_TRIGGER_DOWN && dy <= LAVA_NAV_TRIGGER_UP) {
                                triggerHit = true;
                                break scan; // 内圈命中已足够, 不必扫完外围
                            }
                        }
                    }
                }
            }
            if (!holdHit) {
                _lavaNavNoBlockWarned = false;
            }

            // 路径预检: **全维度**执行 (悬崖/深坑到处都有; 末地无岩浆, 岩浆判据自然
            // 不命中)。开销见注释: 24 个动作里斜跳通常个位数, 每个查 5 格邻域 × 最多
            // 4 格深度 ≈ 百次 getBlockState / 0.25s, 比中心扫描小一个量级。
            boolean pathHit = pathDiagonalDanger();
            boolean danger = triggerHit || pathHit;
            // 路径变干净 (重算成功 / 本就无危险斜跳) ⇒ 退避计数清零。
            if (!pathHit) {
                _lavaNavReplanStreak = 0;
            }

            boolean suppress = _lavaNavSuppressActive;
            // 三类"不需要保守导航"的情形:
            //   ① 有防火效果 (2026-09-19 17:20 用户"喝药水的情况就不要惩罚");
            //   ② 正卡在刷怪笼造墙施工 (2026-09-19 用户"造墙期间强制关闭") — 关掉
            //      斜向升格会让 bot 在笼边原地绕/跳不动, 干扰施工;
            //   ③ 飞行中 (创造/鞘翅, 2026-09-19 用户"开创造飞到空中反而触发") — 飞在
            //      空中既不会摔, 也不受脚下岩浆影响, 关跳纯属干扰。
            // 三者都**立即**解除、不等迟滞窗: 施工一结束 (或药效一过 / 一落地), 下一
            // 轮扫描就回到正常判据, 危险仍在会立刻重新触发 (触发本身零延迟)。
            boolean fireRes = getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE);
            boolean building = adris.altoclef.tasks.resources.CollectBlazeRodsTask.isEnclosureBuildWorkActive();
            boolean flying = getPlayer().getAbilities().flying || getPlayer().isCreative();
            boolean exempt = fireRes || building || flying;
            if (!suppress) {
                if (danger && !exempt) {
                    if (adris.altoclef.util.helpers.PillarJumpHelper.countBaritonePlaceable(this) >= LAVA_NAV_MIN_PLACEABLE) {
                        suppress = true;
                        _lavaNavClearTimer.reset();
                        // 最小保持: 触发后 8s 内不允许解除, 掐掉实跑里那串
                        // "触发 187 → 解除 181 → 触发 187" 的 4~5s 周期横跳。
                        _lavaNavHoldTimer.reset();
                        // 已算好的路径里那个斜跳不会因为开关关闭而消失 (baritone 只在
                        // 算路时读设置), 必须请求重算才能真的绕开。
                        if (pathHit) requestLavaNavReplan();
                    } else if (!_lavaNavNoBlockWarned) {
                        _lavaNavNoBlockWarned = true;
                        Debug.logToFile("altoclef_diag.log",
                                "[LavaNav] danger detected but fewer than " + LAVA_NAV_MIN_PLACEABLE
                                        + " baritone-placeable blocks — keeping diagonal ascend ON");
                    }
                }
            } else if (exempt) {
                // 豁免 (防火/施工/飞行) 立即解除, 不受最小保持限制。
                suppress = false;
                _lavaNavReplanStreak = 0;
            } else if (holdHit || pathHit) {
                _lavaNavClearTimer.reset();
                // 抑制期间路径仍在危险区: 继续尝试重算 (内部有 3 次退避)。
                if (pathHit) requestLavaNavReplan();
            } else if (_lavaNavClearTimer.elapsed() && _lavaNavHoldTimer.elapsed()) {
                suppress = false;
            }

            if (suppress != _lavaNavSuppressActive) {
                _lavaNavSuppressActive = suppress;
                String pos = getPlayer().getBlockPos().toShortString();
                // 触发原因只写一条主因, 便于日志判读: 路径预检命中优先 (它更早)。
                String why = pathHit ? "路径斜跳落点高危(岩浆/深坑)"
                        : (triggerHit ? "脚下邻域岩浆(水平<=" + LAVA_NAV_TRIGGER_RADIUS
                                + "/下" + LAVA_NAV_TRIGGER_DOWN + "/上" + LAVA_NAV_TRIGGER_UP + ")" : "?");
                String m = suppress
                        ? "[LavaNav] 触发: " + why + ", 关斜向升格(旋转跳) pos=" + pos
                        : "[LavaNav] 解除: 外围(<=" + LAVA_NAV_HOLD_RADIUS + ") 连续 "
                                + LAVA_NAV_RELEASE_CONFIRM_SEC + "s 无岩浆且路径无危险斜跳"
                                + (exempt ? " / 已豁免(防火或造墙施工或飞行)" : "")
                                + ", 恢复斜向升格 pos=" + pos;
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
        }
        // 每 tick 强制写 (applyState 可能随时把任务栈里的旧值写回 baritone settings)。
        // 未抑制时写回基线 true, 与 initializeBaritoneSettings 的原值一致。
        // 只动这一个开关: blockPlacementPenalty 已于 2026-09-19 移出本保险。
        // (2026-09-28) 抑制源合并: 岩浆危险 (LavaNav, 预测式) ∨ 斜跳反复失败
        // (DiagonalJumpFailDetector, 反应式)。
        getClientBaritoneSettings().allowDiagonalAscend.value =
                !(_lavaNavSuppressActive
                        || adris.altoclef.util.helpers.DiagonalJumpFailDetector.isSuppressing());
    }

    /**
     * (2026-09-19 用户: "导航是一次性的, 在安全区算好的路径会穿过危险区") 剩余路线里
     * 是否存在**经过危险区的斜向升格**。只看 MovementDiagonal —— 关跳只影响它, 路径
     * 里没有斜跳就不触发 (顺带治好"洞里一直触发": 洞里多是平走/上下行); 只查该动作
     * 的落脚点邻域, 不扫立方体, 所以很便宜。
     *
     * <p>路线来源: {@code getPathingBehavior().getCurrent()} → {@code IPathExecutor.getPath()}
     * → {@code path.movements()} 从 {@code exec.getPosition()} (已走到第几个动作) 起,
     * 每个 {@code IMovement.getDest()} 就是落脚点。与 BaritoneHelper.isBaritoneBuilding
     * 同一套接口。
     */
    private boolean pathDiagonalDanger() {
        try {
            var baritone = getClientBaritone();
            if (!baritone.getPathingBehavior().isPathing()) return false;
            var exec = baritone.getPathingBehavior().getCurrent();
            if (exec == null) return false;
            var path = exec.getPath();
            if (path == null) return false;
            var movements = path.movements();
            int start = Math.max(0, exec.getPosition());
            int end = Math.min(movements.size(), start + LAVA_NAV_PATH_LOOKAHEAD);
            for (int i = start; i < end; i++) {
                Object mv = movements.get(i);
                // 只关心斜向**升格**: 关掉 allowDiagonalAscend 后唯一会消失的就是它。
                // 下行的斜跳 (dest.y <= src.y) 不受这个开关影响 —— 把它算作危险只会
                // 白白重算: 2026-09-19 19:55 场实证每 3s 一次连续 10 次重算, 路径
                // 始终换不掉, 白烧一次全量 A* 还打断走位。
                if (!(mv instanceof baritone.pathing.movement.movements.MovementDiagonal)) continue;
                baritone.api.pathing.movement.IMovement im = (baritone.api.pathing.movement.IMovement) mv;
                BlockPos src = im.getSrc();
                BlockPos dest = im.getDest();
                if (src == null || dest == null) continue;
                if (dest.getY() <= src.getY()) continue;
                if (destDangerous(dest)) return true;
            }
        } catch (Throwable ignored) {
            return false; // 拿不到路径按"无危险"处理, 不阻塞正常寻路
        }
        return false;
    }

    /**
     * 斜跳落脚点是否高危: dest 及其水平 4 邻里有岩浆 (掉进去即死), 或其下方是
     * ≥ LAVA_NAV_FALL_RISK_DEPTH 格的深坑/悬崖 (斜跳失败掉下去摔死)。
     * 之所以连邻格一起查: 斜跳失败不是精确落回原点, 而是掉到 dest 周边。
     */
    private boolean destDangerous(BlockPos dest) {
        if (!getChunkTracker().isChunkLoaded(dest)) return false;
        if (isLavaAt(dest) || isLavaAt(dest.down())) return true;
        if (cliffDepth(dest) >= LAVA_NAV_FALL_RISK_DEPTH) return true;
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            if (!dir.getAxis().isHorizontal()) continue;
            BlockPos side = dest.offset(dir);
            if (!getChunkTracker().isChunkLoaded(side)) continue;
            if (isLavaAt(side) || isLavaAt(side.down())) return true;
            if (cliffDepth(side) >= LAVA_NAV_FALL_RISK_DEPTH) return true;
        }
        return false;
    }

    private boolean isLavaAt(BlockPos p) {
        if (!getChunkTracker().isChunkLoaded(p)) return false;
        return getWorld().getBlockState(p).getBlock() == Blocks.LAVA;
    }

    /**
     * p 下方连续非实心 (且非水) 的格数, 上限 LAVA_NAV_FALL_RISK_DEPTH。
     * 下方是水返回 0 (摔不死, 反而可能是有意的跳水); 区块未加载返回 0 (按安全处理)。
     */
    private int cliffDepth(BlockPos p) {
        int depth = 0;
        for (int d = 1; d <= LAVA_NAV_FALL_RISK_DEPTH; d++) {
            BlockPos q = p.down(d);
            if (!getChunkTracker().isChunkLoaded(q)) return 0;
            net.minecraft.block.BlockState st = getWorld().getBlockState(q);
            if (st.getBlock() == Blocks.WATER) return 0;
            if (adris.altoclef.util.helpers.WorldHelper.isSolid(this, q)) break;
            depth = d;
        }
        return depth;
    }

    /**
     * 请求 baritone 重算当前路径: 开关关掉**不会**让已算好的路径里的斜跳消失
     * (baritone 只在算路时读 allowDiagonalAscend), 必须重算才能真的绕开。
     * forceCancel 保留目标、下一 tick 由 PathingControlManager 重新规划; 节流防抖。
     */
    private void requestLavaNavReplan() {
        // 退避: 连续重算命中说明"关跳换不掉这条路"(多半是必经地形), 再算只是每 3s
        // 烧一次全量 A* 并打断走位 —— 2026-09-19 19:54:44~19:55:33 连续 10 次实证。
        if (_lavaNavReplanStreak >= LAVA_NAV_REPLAN_MAX_STREAK) return;
        if (!_lavaNavReplanTimer.elapsed()) return;
        _lavaNavReplanTimer.reset();
        _lavaNavReplanStreak++;
        try {
            getClientBaritone().getPathingBehavior().forceCancel();
            String m = "[LavaNav] 路径预检命中危险斜跳, 已请求重算 (第 " + _lavaNavReplanStreak
                    + "/" + LAVA_NAV_REPLAN_MAX_STREAK + " 次, 节流 " + LAVA_NAV_REPLAN_THROTTLE_SEC
                    + "s; 达到上限则只关跳不再重算) pos=" + getPlayer().getBlockPos().toShortString();
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Throwable ignored) {
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

    // ==== Slept-in bed spawn point (2026-09-28 睡成的重生点床不收) ====
    // 最近一次"真睡进去(设了重生点)"的床位置。vanilla 睡床=必然 setSpawnPoint,
    // 睡成的床就是重生点载体, 拆了重生点失效。收床逻辑(speedrunner 带走床)只该
    // 收"没睡成的滞留床", 不该收这张重生点床 —— 用本字段区分二者。
    private BlockPos _lastSleptInBed = null;

    public BlockPos getLastSleptInBed() {
        return _lastSleptInBed;
    }

    public void setLastSleptInBed(BlockPos pos) {
        _lastSleptInBed = pos;
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
