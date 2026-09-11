package adris.altoclef.anomaly;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.List;

/**
 * (2026-09-11 异常看门狗) 一次采样得到的只读状态快照。
 *
 * 由 AnomalyWatchdog 每 tick 构建一次, 传给所有检测器。检测器只能从这里读,
 * **拿不到 mod 引用** —— 这是"检测层零副作用"约束在类型层面的落实:
 * 想动手改状态, 它连入口都摸不到。
 *
 * 所有取值都做了异常兜底。采样期间世界切换 / 玩家为 null 都不应抛到主循环。
 */
public final class AnomalyContext {

    private static final int MAX_STACK_IN_PATH = 8;

    private final long _tick;
    private final boolean _inGame;
    private final boolean _paused;
    private final boolean _hasScreen;
    private final boolean _sleeping;
    private final Vec3d _playerPos;
    private final String _dimension;
    private final float _health;
    private final int _food;
    private final String _chainName;
    private final List<Task> _taskStack;
    private final boolean _baritonePathing;
    private final boolean _baritonePathPresent;
    private final boolean _baritoneAvailable;

    private AnomalyContext(long tick, boolean inGame, boolean paused, boolean hasScreen,
                          boolean sleeping, Vec3d playerPos, String dimension,
                          float health, int food, String chainName, List<Task> taskStack,
                          boolean baritonePathing, boolean baritonePathPresent,
                          boolean baritoneAvailable) {
        _tick = tick;
        _inGame = inGame;
        _paused = paused;
        _hasScreen = hasScreen;
        _sleeping = sleeping;
        _playerPos = playerPos;
        _dimension = dimension;
        _health = health;
        _food = food;
        _chainName = chainName;
        _taskStack = taskStack;
        _baritonePathing = baritonePathing;
        _baritonePathPresent = baritonePathPresent;
        _baritoneAvailable = baritoneAvailable;
    }

    /**
     * 采样当前世界状态。winnerChain 为 TaskRunner 本 tick 选出的控制链 (可 null)。
     */
    public static AnomalyContext sample(AltoClef mod, TaskChain winnerChain, long tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean inGame = AltoClef.inGame();
        boolean paused = mc != null && mc.isPaused();
        boolean hasScreen = mc != null && mc.currentScreen != null;

        ClientPlayerEntity p = inGame ? mod.getPlayer() : null;

        Vec3d pos = null;
        boolean sleeping = false;
        String dimension = "unknown";
        float health = -1f;
        int food = -1;
        if (p != null) {
            try {
                pos = p.getPos();
                sleeping = p.isSleeping();
                health = p.getHealth();
                food = p.getHungerManager().getFoodLevel();
                dimension = p.getWorld().getRegistryKey().getValue().toString();
            } catch (Throwable ignored) {
                // 采样期间跨世界切换: 保留已取到的字段, 不向上抛
            }
        }

        String chainName = winnerChain == null ? null : winnerChain.getName();
        // 直接引用链的任务列表 (每 tick 由 TaskChain.tick 重建)。约定:
        // 只在本次 tick 内消费, 检测器不得跨 tick 持有该引用。
        List<Task> stack = Collections.emptyList();
        if (winnerChain != null) {
            try {
                stack = winnerChain.getTasks();
            } catch (Throwable ignored) {
            }
        }

        boolean pathing = false;
        boolean pathPresent = false;
        boolean baritoneOk = false;
        if (inGame && mod.getClientBaritone() != null) {
            try {
                pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
                pathPresent = mod.getClientBaritone().getPathingBehavior().getPath().isPresent();
                baritoneOk = true;
            } catch (Throwable ignored) {
                // baritone 未就绪: 标记"查不到", 检测器应据此跳过而不是误判成故障
            }
        }

        return new AnomalyContext(tick, inGame, paused, hasScreen, sleeping, pos, dimension,
                health, food, chainName, stack, pathing, pathPresent, baritoneOk);
    }

    /**
     * 任务栈 (从根到叶)。只读约定: 检测器可以遍历, 但**不得跨 tick 持有引用** ——
     * 底层列表每个 tick 由 TaskChain.tick 清空重建。
     */
    public List<Task> taskStack() {
        return _taskStack;
    }

    /** 任务栈上是否存在指定类型 (含子类) 的任务。 */
    public boolean stackHasTask(Class<?> type) {
        for (Task t : _taskStack) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }

    /** 任务栈从根到叶的类名路径, 最多 MAX_STACK_IN_PATH 层。 */
    public String stackPath() {
        if (_taskStack.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Task t : _taskStack) {
            if (shown > 0) sb.append(" > ");
            sb.append(t.getClass().getSimpleName());
            if (++shown >= MAX_STACK_IN_PATH) {
                sb.append(" > ...");
                break;
            }
        }
        return sb.toString();
    }

    /** 任务栈最深的那个任务类名 (即"当前正在干什么")。 */
    public String deepestTaskClass() {
        if (_taskStack.isEmpty()) return "none";
        return _taskStack.get(_taskStack.size() - 1).getClass().getSimpleName();
    }

    public long tick() {
        return _tick;
    }

    public boolean inGame() {
        return _inGame;
    }

    public boolean paused() {
        return _paused;
    }

    /** 是否打开了 GUI (箱子 / 工作台 / 背包)。这期间的静止是合法的。 */
    public boolean hasScreen() {
        return _hasScreen;
    }

    public boolean sleeping() {
        return _sleeping;
    }

    public Vec3d playerPos() {
        return _playerPos;
    }

    public String dimension() {
        return _dimension;
    }

    public float health() {
        return _health;
    }

    public int food() {
        return _food;
    }

    /** 本 tick 控制 bot 的链名; null 表示没有链在跑 (bot 空闲等指令)。 */
    public String chainName() {
        return _chainName;
    }

    public boolean baritonePathing() {
        return _baritonePathing;
    }

    public boolean baritonePathPresent() {
        return _baritonePathPresent;
    }

    /** baritone 状态是否成功读到。false 时检测器不应据此下任何结论。 */
    public boolean baritoneAvailable() {
        return _baritoneAvailable;
    }
}
