package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Dig a staircase shaft down to find ores. Humans don't find ores by staring
 * at the surface; they dig down.
 *
 * Rules:
 *  - Dig a 45-degree staircase (never a straight drop) so we can walk back up.
 *  - While digging, watch for caves: if an open cave space is detected, switch
 *    to biased exploration INSIDE the cave (prefer exploring caves over blind
 *    digging — caves expose far more ore-bearing walls than fresh tunnels).
 *  - Stop when we reach the target Y depth or after {@value MAX_DIG_STEPS}
 *    steps with nothing found; the parent task then resumes surface exploration.
 *
 * If the player is ALREADY at/below the target depth, the task runs in TUNNEL
 * mode: dig a 2-block-high tunnel that slowly DESCENDS (1 block every
 * {@value TUNNEL_DESCEND_EVERY} steps, floored at y={@value TUNNEL_MIN_Y}).
 * Repeated tunnel runs sink toward diamond depth and then branch-mine flat —
 * this is what a human does at mining depth: ores don't come to you, you dig
 * corridors and watch the walls.
 *
 * Cave detection (2026-09-01 最终方案): 深度闸门(y≤45) + 露天闸门(头顶有岩顶)
 * + 连通空气 BFS(防透视) + 【天然空气统计】(排除自挖块、y≤55, 数量≥24 且
 * X/Z/Y 三维跨度达标 — 多条自挖隧道缝合也绝不会误判) → ExploreCaveTask 进洞。
 */
public class MineDownExploreTask extends Task implements ITaskRequiresGrounded {

    // Default target Y depth if the caller doesn't specify one.
    private static final int DEFAULT_TARGET_Y = 10;
    // Maximum number of staircase steps before giving up.
    private static final int MAX_DIG_STEPS = 40;
    // How far around the player we look for a cave while digging (blocks).
    // 8 (was 5): diag logs showed the bot mining straight past caves whose
    // mouths were 6-7 blocks away — a human would notice and walk in.
    private static final int CAVE_SCAN_RADIUS = 8;
    // (2026-09-01 三维开阔度判定, 取代旧 CAVE_AREA_THRESHOLD=40 空气计数)
    // 天然洞在 X/Z/Y 三维都开阔, 自挖楼梯/隧道是 1-2 格宽、2 格高的细长通道。
    // 连通空气集合的 X/Z/Y 跨度分别达到下面阈值才算"开阔天然洞", 触发矿洞探索。
    private static final int CAVE_OPEN_SPAN_X = 4;
    private static final int CAVE_OPEN_SPAN_Z = 4;
    private static final int CAVE_OPEN_SPAN_Y = 3;
    // (2026-09-01 自挖块排除, 用户"又错误探测到矿洞", 现场确认=全是自挖通道)
    // 三维跨度只挡得住【单条】自挖隧道; 多条隧道(多次任务实例/换向重挖)交叉缝合后
    // 连通空气 X/Z/Y 跨度全部达标(实测 20:42: X=7,Z=9,Y=7) → 仍误判。
    // 本修复: 开阔度只统计【天然空气】= 连通空气中排除自挖记录、
    // 且 y 不高于 CAVE_NATURAL_AIR_MAX_Y(用户: 只认 y≤45 的空气, 与深度闸门对齐,
    // 防自挖楼梯上半段/浅层洞口被计入)。
    // 自挖网络里天然空气≈0 → 永不误判; 挖穿进真洞时洞内天然空气连通 → 正常触发。
    private static final int CAVE_NATURAL_AIR_MAX_Y = 45;
    // 天然空气的最少数量: 配合三维跨度, 过滤"恰好一条天然窄缝穿过扫描区"的小概率情形。
    private static final int MIN_NATURAL_CAVE_AIR = 24;
    // 自挖位置记录上限(每维度, 容量封顶, 超限逐出最旧并重写文件), 防内存/文件无限增长。
    private static final int MAX_SELF_DUG_MEMORY = 8192;
    // (2026-09-01 跨会话持久化, 用户"一到y=45就触发") 静态集合随 JVM 重启清零 →
    // 上一局挖的旧隧道在新会话里全被当"天然空气"。自挖记忆持久化到游戏目录
    // altoclef_selfdug.log(每行 "维度 x y z"), 启动后首次访问时加载。记录入口在
    // DestroyBlockTask(中央: 所有任务真实挖开的方块都记, 含主线 MineAndCollect 挖矿)。
    private static final String SELF_DUG_FILE = "altoclef_selfdug.log";
    private static final java.util.Map<String, java.util.LinkedHashSet<BlockPos>> _selfDugByDim
            = new java.util.HashMap<>();
    private static boolean _selfDugLoaded = false;
    // Check for caves every N steps.
    private static final int CAVE_SCAN_EVERY_STEPS = 3;
    // Horizontal direction of the staircase (dig east as we descend).
    private static final int STAIR_DIR_X = 1;
    private static final int STAIR_DIR_Z = 0;
    // Horizontal directions a tunnel may pick from (cardinal only).
    private static final int[][] TUNNEL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // Tunnel mode descends 1 block every this many steps (a walkable 2:1 slope).
    private static final int TUNNEL_DESCEND_EVERY = 2;
    // Tunnels never descend below this Y — at the floor they run flat, which is
    // exactly how humans branch-mine for diamonds. 1.18 diamond band: stop
    // sinking around y=-24 and branch-mine flat from there.
    private static final int TUNNEL_MIN_Y = -24;
    // How long we may spend exploring a cave before resuming the dig.
    // (2026-09-01 用户"矿洞探索超时太短") 25s 对大洞穴偏短: 纯走路就能
    // 掐断一次探索。提到 90s; 且挖到矿会重置计时 (markOreMined), 有产出的
    // 探索不会被超时打断, 只有"长时间没挖到矿的干逛"才会被限时。
    private static final double CAVE_EXPLORE_TIMEOUT = 90;

    // How deep we're willing to dig for this run (differs by target ore).
    private final int _targetY;

    private final Deque<BlockPos> _digQueue = new ArrayDeque<>();

    private BlockPos _digStart;
    private int _step = 0;
    private boolean _inCave = false;
    private Task _subTask;
    // Tunnel mode: we're already at the target depth, dig horizontally instead.
    private boolean _tunnelMode = false;
    private int _dirX = STAIR_DIR_X;
    private int _dirZ = STAIR_DIR_Z;
    // [Diag-water] how many water blocks we've skipped so far this run.
    private int _waterSkipped = 0;
    // 远离液体策略：因队列头贴着水/岩浆而放弃轨迹、换方向重挖的次数。
    // 上限防止在湖泊/岩浆湖上方无限横跳。
    private int _liquidRetreats = 0;
    private static final int MAX_LIQUID_RETREATS = 8;
    // Cave exploration watchdog.
    private final TimerGame _caveExploreTimer = new TimerGame(CAVE_EXPLORE_TIMEOUT);

    public MineDownExploreTask() {
        this(DEFAULT_TARGET_Y);
    }

    public MineDownExploreTask(int targetY) {
        _targetY = targetY;
    }

    /**
     * (2026-09-01 用户"一旦挖到矿就应该重置矿洞探索超时") 矿洞探索期间挖到矿
     * = 有产出, 看门狗不应把活跃的探索掐断。由 MineOrCollectTask 每次开始追
     * 矿石时调用, 重置探索计时 — 边挖矿边探索的会话不会被超时打断, 只有
     * "长时间没挖到矿的干逛"才被限时。
     */
    public void markOreMined() {
        _caveExploreTimer.reset();
    }

    @Override
    protected void onStart(AltoClef mod) {
        _digStart = mod.getPlayer().getBlockPos().down();
        _step = 0;
        _inCave = false;
        _waterSkipped = 0;
        _liquidRetreats = 0;
        _digQueue.clear();
        // Already at mining depth? Don't dig a pointless 1-block "shaft" that
        // finishes instantly and leaves the bot wandering — dig a tunnel instead.
        _tunnelMode = _digStart.getY() <= _targetY;
        if (_tunnelMode) {
            // Pick a random cardinal direction so consecutive tunnels don't
            // forever dig the same way through the same rock.
            int[] dir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
            _dirX = dir[0];
            _dirZ = dir[1];
            Debug.logMessage("Already at depth " + _digStart.getY() + " (target " + _targetY
                    + "), digging a slowly-descending tunnel (floor y=" + TUNNEL_MIN_Y + ") instead.");
        } else {
            _dirX = STAIR_DIR_X;
            _dirZ = STAIR_DIR_Z;
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_inCave) {
            // We found a cave while digging: prefer exploring inside it.
            // A watchdog bounds the detour so a huge cave network can't trap
            // us forever — after CAVE_EXPLORE_TIMEOUT seconds we resume digging.
            if (_subTask != null && !_subTask.isFinished(mod) && !_caveExploreTimer.elapsed()) {
                setDebugState("Exploring cave found while digging");
                return _subTask;
            }
            if (_caveExploreTimer.elapsed()) {
                Debug.logMessage("Cave exploration timed out after " + CAVE_EXPLORE_TIMEOUT + "s, resuming digging.");
                Debug.logToFile("altoclef_diag.log", "[MineDown-Cave] exploration timed out, resuming digging.");
            } else {
                Debug.logMessage("Cave exploration done, resuming digging.");
                Debug.logToFile("altoclef_diag.log", "[MineDown-Cave] exploration done (BiasedExplore finished), resuming digging.");
            }
            _inCave = false;
            _subTask = null;
        }

        // Check if we should keep digging.
        if (reachedBottom(mod)) {
            // (2026-08-31 进矿洞却卡 shaft done, 用户重即"进入矿洞不动") 到井底/挖穿后,
            // 洞穴检测原在 reachedBottom 之后、且受 _step%3 门控 → 井底 _step 不推进,
            // findNearbyCave 永不走 → 挖穿进洞也不触发探索, 反复 "Shaft done" 空转卡死。
            // 这里在判定"结束"前先扫一次: 井底已有连通洞空气(从洞口可达)→ 进洞探索,
            // 否则才真正 shaft done。
            if (!_inCave) {
                Optional<BlockPos> cave = findNearbyCave(mod);
                if (cave.isPresent()) {
                    _inCave = true;
                    BlockPos anchor = cave.get();
                    Debug.logMessage("Found cave at shaft bottom at " + anchor.toShortString() + ", exploring it.");
                    _caveExploreTimer.reset();
                    // (2026-09-01 真正进洞) 专用洞内导航任务, 不再用 BiasedExploreTask
                    // (其落点被强制抬到 48 格外地表, 从未真正走进洞里)。
                    _subTask = new ExploreCaveTask();
                    return _subTask;
                }
            }
            setDebugState("Shaft done, no ores found.");
            // FIX (stuck bug): if a cave exploration interrupted the FINAL step,
            // the queue may still hold undug blocks. isFinished used to require an
            // empty queue, so the task could never finish while onTick returned
            // null here — the bot stood still forever with "Shaft done" on screen.
            _digQueue.clear();
            return null;
        }

        // Watch for caves periodically.
        if (_step % CAVE_SCAN_EVERY_STEPS == 0) {
            Optional<BlockPos> cave = findNearbyCave(mod);
            if (cave.isPresent()) {
                _inCave = true;
                BlockPos anchor = cave.get();
                Debug.logMessage("Found cave while digging at " + anchor.toShortString() + ", exploring it.");
                Debug.logToFile("altoclef_diag.log",
                        "[MineDown-Cave] found cave while digging at " + anchor.toShortString() + ", exploring it first.");
                _caveExploreTimer.reset();
                // (2026-09-01 真正进洞) 专用洞内导航任务: 直奔最远天然洞空气,
                // 到达后重扫跟深(见 ExploreCaveTask), 25s 看门狗兜底不影响挖矿主线。
                _subTask = new ExploreCaveTask();
                return _subTask;
            }
        }

        // Advance the staircase when the current step is fully dug out.
        // WATER FIX: DestroyBlockTask can never "break" a water block (attacking
        // water does nothing), so if the queue head was water the bot used to
        // freeze forever trying to dig it. Water/air blocks at the head are
        // simply skipped — we dig the solid blocks BEHIND the water instead
        // (slow but works underwater).
        skipAirAndWater(mod);
        // FAST-FORWARD: if the whole step was already mined (re-entering our
        // own tunnel), advance immediately instead of one step per tick.
        while (_digQueue.isEmpty() && !reachedBottom(mod)) {
            _step++;
            enqueueNextStep();
            skipAirAndWater(mod);
        }
        // 远离液体策略（用户原则）：挖开贴着水/岩浆的方块会让液体灌进
        // 通道（bot 在水里移动极慢 → 卡顿；岩浆 → 伤害+掉落物被烧）。
        // 精确堵水需要流体 BFS 找全部源头 + 放置规划 + 失败重试，复杂度
        // 过高不实现。这里直接回避：放弃当前轨迹，从当前位置换个方向
        // 重新挖。重试有上限，防在液体区域上方无限换向。
        while (!_digQueue.isEmpty() && WorldHelper.isAdjacentToLiquid(mod, _digQueue.peek())) {
            if (_liquidRetreats >= MAX_LIQUID_RETREATS) {
                Debug.logMessage("[Liquid-avoid] too many liquid encounters, ending this dig run.");
                _digQueue.clear();
                _step = MAX_DIG_STEPS;
                return null;
            }
            _liquidRetreats++;
            Debug.logMessage("[Liquid-avoid] tunnel head at " + _digQueue.peek().toShortString()
                    + " is adjacent to liquid, retreating and changing direction ("
                    + _liquidRetreats + "/" + MAX_LIQUID_RETREATS + ")");
            retreatAndRedig(mod);
            skipAirAndWater(mod);
            while (_digQueue.isEmpty() && !reachedBottom(mod)) {
                _step++;
                enqueueNextStep();
                skipAirAndWater(mod);
            }
        }
        if (_digQueue.isEmpty()) {
            return null;
        }

        // (2026-08-30 探洞镐门槛, 用户"探洞不检查镐子不合理") 探洞就是挖石头找矿。
        // 直接 DestroyBlockTask 手挖石头: 石头硬度高, 主手无镐/拿方块极慢, 探洞形同
        // 废了 (用户实测: 背包有铁/木头却手挖)。与主线挖石头(MineAndCollectTask 的
        // onResourceTick)保持一致: 挖前先保证有镐, 没有就去 SatisfyMiningRequirement
        // 合成。WOOD=木镐及以上即可挖石头, 保底最低成本、立即能解锁, 主线挖矿时再升级。
        if (!StorageHelper.miningRequirementMet(mod, MiningRequirement.WOOD)) {
            return new SatisfyMiningRequirementTask(MiningRequirement.WOOD);
        }

        setDebugState((_tunnelMode ? "Digging a tunnel to find ores (step " : "Digging down to find ores (step ") + _step + "/" + MAX_DIG_STEPS + ")");
        return new DestroyBlockTask(_digQueue.peek());
    }

    /**
     * 远离液体：放弃当前挖掘轨迹，从玩家当前位置随机换一个水平方向
     * （与旧方向不同）重新开始楼梯/隧道。四种方向都有液体时由
     * MAX_LIQUID_RETREATS 上限兜底终止。
     */
    private void retreatAndRedig(AltoClef mod) {
        _digQueue.clear();
        _step = 0;
        _digStart = mod.getPlayer().getBlockPos().down();
        int[] newDir;
        do {
            newDir = TUNNEL_DIRS[(int) (Math.random() * TUNNEL_DIRS.length)];
        } while (newDir[0] == _dirX && newDir[1] == _dirZ);
        _dirX = newDir[0];
        _dirZ = newDir[1];
    }

    private void skipAirAndWater(AltoClef mod) {
        while (!_digQueue.isEmpty()
                && (WorldHelper.isAir(mod, _digQueue.peek()) || isWater(mod, _digQueue.peek()))) {
            if (isWater(mod, _digQueue.peek())) {
                logWaterEncounter(mod, _digQueue.peek());
            }
            _digQueue.poll();
        }
    }

    private void enqueueNextStep() {
        // Each step: the block forward(-down for stairs) + the block above it (headroom).
        // (自挖记录不在此做: 入队≠挖开, 废弃队列会误标真洞空气。真实挖开由
        //  DestroyBlockTask.isFinished 中央记录, 见 recordBrokenBlock。)
        int k = _step;
        BlockPos ground;
        if (_tunnelMode) {
            // Descend 1 block every TUNNEL_DESCEND_EVERY steps (walkable slope),
            // clamped at TUNNEL_MIN_Y so we sink toward diamond depth and then
            // branch-mine flat — repeated tunnel runs keep getting deeper, which
            // sets up later diamond tasks.
            int drop = Math.max(0, Math.min(_digStart.getY() - TUNNEL_MIN_Y, k / TUNNEL_DESCEND_EVERY));
            ground = _digStart.add(k * _dirX, -drop, k * _dirZ);
        } else {
            ground = _digStart.add(k * _dirX, -k, k * _dirZ);
        }
        _digQueue.add(ground.up());
        _digQueue.add(ground);
    }

    private boolean isWater(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).getBlock() instanceof net.minecraft.block.FluidBlock;
    }

    /**
     * [Diag-water] log every water encounter (throttled to the first few +
     * every 10th, so a flooded shaft doesn't spam the log).
     */
    private void logWaterEncounter(AltoClef mod, BlockPos pos) {
        _waterSkipped++;
        if (_waterSkipped <= 3 || _waterSkipped % 10 == 0) {
            String m = "[Diag-water] shaft/tunnel hit water at " + pos.toShortString()
                    + " (y=" + pos.getY() + ", skipped so far: " + _waterSkipped + ")"
                    + " player=" + mod.getPlayer().getBlockPos().toShortString()
                    + " mode=" + (_tunnelMode ? "tunnel" : "staircase")
                    + " step=" + _step + "/" + MAX_DIG_STEPS;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
    }

    private boolean reachedBottom(AltoClef mod) {
        // Pre-start safety: isFinished() may be called before onStart() (the task
        // framework evaluates candidate subtasks before swapping them in — if the
        // swap is blocked by canBeInterrupted, the task sits unstarted). An
        // unstarted shaft is by definition not at the bottom yet.
        if (_digStart == null) return false;
        if (_step >= MAX_DIG_STEPS) return true;
        // Tunnel mode: only the step limit applies (we're already at depth).
        if (_tunnelMode) return false;
        // FIX (task ping-pong): use the player's ACTUAL Y, not the old
        // `_digStart.getY() - _step` formula. The step counter increments even
        // when the step's blocks were ALREADY air (re-entering a previously dug
        // tunnel), so the formula reported a "ghost depth" the player never
        // reached. The task then finished at the ghost depth, the parent created
        // a NEW MineDownExploreTask at nearly the same spot, whose step 1/2
        // blocks were the already-mined tunnel blocks — the bot ping-ponged
        // between "dig 2 already-gone blocks" task instances forever
        // (see task_switch.log 10:04:54-10:04:56).
        return mod.getPlayer().getBlockPos().getY() <= _targetY;
    }

    /**
     * (中央记录入口, 由 DestroyBlockTask 调用) 记录一个本 mod【真实挖开】的方块位置:
     * 任务开始时是实心、结束时变空气 ⇒ 这格空气是我们造的, 不是天然洞空气。
     * 同时追加持久化文件, 跨游戏重启(新会话)后旧隧道仍被认得, 不再被当天然洞。
     */
    public static void recordBrokenBlock(AltoClef mod, BlockPos pos) {
        if (mod.getWorld() == null || pos == null) return;
        ensureSelfDugLoaded();
        String dim = selfDugDimKey(mod);
        java.util.LinkedHashSet<BlockPos> set = _selfDugByDim.computeIfAbsent(dim, k -> new java.util.LinkedHashSet<>());
        if (!set.add(pos.toImmutable())) return; // 已记录
        appendSelfDugLine(dim, pos);
        if (set.size() > MAX_SELF_DUG_MEMORY) {
            // 容量封顶: 逐出最旧并整体重写文件(罕见操作)。
            java.util.Iterator<BlockPos> it = set.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
            rewriteSelfDugFile();
        }
    }

    /**
     * 该位置是否为本 mod 自己挖出来的空气(查当前维度的持久化记忆)。
     */
    static boolean isSelfDugAir(AltoClef mod, BlockPos pos) {
        if (mod.getWorld() == null) return false;
        ensureSelfDugLoaded();
        java.util.Set<BlockPos> set = _selfDugByDim.get(selfDugDimKey(mod));
        return set != null && set.contains(pos);
    }

    private static String selfDugDimKey(AltoClef mod) {
        return mod.getWorld().getRegistryKey().getValue().toString();
    }

    private static java.nio.file.Path selfDugFile() {
        // 必须用 runDirectory(游戏目录 D:\minecraft\.minecraft), 相对 CWD 会找不到文件。
        return MinecraftClient.getInstance().runDirectory.toPath().resolve(SELF_DUG_FILE);
    }

    private static void ensureSelfDugLoaded() {
        if (_selfDugLoaded) return;
        _selfDugLoaded = true;
        try {
            java.nio.file.Path f = selfDugFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 4) continue;
                try {
                    BlockPos p = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    _selfDugByDim.computeIfAbsent(parts[0], k -> new java.util.LinkedHashSet<>()).add(p);
                } catch (NumberFormatException ignored) {
                }
            }
            Debug.logMessage("[SelfDug] loaded " + _selfDugByDim.values().stream().mapToInt(Set::size).sum()
                    + " self-dug positions from " + SELF_DUG_FILE);
        } catch (Exception e) {
            Debug.logMessage("[SelfDug] failed to load " + SELF_DUG_FILE + ": " + e.getMessage());
        }
    }

    private static void appendSelfDugLine(String dim, BlockPos pos) {
        try {
            java.nio.file.Files.write(selfDugFile(),
                    (dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 记录失败不阻断挖掘, 只是跨会话记忆缺一格。
            Debug.logMessage("[SelfDug] failed to append: " + e.getMessage());
        }
    }

    private static void rewriteSelfDugFile() {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, java.util.LinkedHashSet<BlockPos>> e : _selfDugByDim.entrySet()) {
                for (BlockPos p : e.getValue()) {
                    sb.append(e.getKey()).append(' ').append(p.getX()).append(' ')
                            .append(p.getY()).append(' ').append(p.getZ()).append('\n');
                }
            }
            java.nio.file.Files.write(selfDugFile(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Debug.logMessage("[SelfDug] failed to rewrite: " + e.getMessage());
        }
    }

    /**
     * 玩家当前连通空间里【离参考点 fromRef 最远的天然空气】(排除自挖位置、
     * y≤CAVE_NATURAL_AIR_MAX_Y、排除调用方已访问/已失败的点)。
     *
     * (2026-09-01 策略修正, 用户"随机探索原地打转") 旧版从【玩家当前位置】取最远:
     * collectConnectedAir 只扫 ±8 格, 目标总在近旁, 且"最远"随玩家移动左右翻转
     * → 在小空间里来回绕圈。改为:
     *   - BFS 仍以玩家当前位置为中心(只认连通可达的空气, 防透视);
     *   - 距离参考点 fromRef 固定为【探索起点】 — 每次选点都朝"离入口更远"的方向
     *     推, 目标一致向外, 不再左右横跳;
     *   - exclude 由调用方传入 visited ∪ failed — 到过的点绝不回头。
     * 无天然空气 → null(洞已探索完/根本不是洞)。
     */
    static BlockPos pickFarthestNaturalCaveAir(AltoClef mod, BlockPos center, BlockPos fromRef, Set<BlockPos> exclude) {
        Set<BlockPos> reachable = collectConnectedAir(mod, center);
        BlockPos best = null;
        double bestDistSq = Double.NEGATIVE_INFINITY;
        for (BlockPos p : reachable) {
            if (p.getY() > CAVE_NATURAL_AIR_MAX_Y) continue;
            if (isSelfDugAir(mod, p)) continue;
            if (exclude != null && exclude.contains(p)) continue;
            double d = p.getSquaredDistance(fromRef);
            if (d > bestDistSq) {
                bestDistSq = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Look around the player for an open cave space (CAVE_AIR), preferring
     * positions below/at eye level since that's where we're digging toward.
     * Returns the FARTHEST qualifying cave air from the player so the
     * exploration bias pulls the bot INTO the cave, not just to its mouth.
     */
    /**
         * (2026-08-31) BFS 收集玩家当前所在开放空间的全部空气块(普通 air + cave_air),
         * 半径 ±CAVE_SCAN_RADIUS、容量封顶, 供 findNearbyCave 判断洞空气是否「已挖开/可达」。
         */
        static Set<BlockPos> collectConnectedAir(AltoClef mod, BlockPos start) {
            Set<BlockPos> visited = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty() && visited.size() < 512) {
                BlockPos cur = queue.poll();
                if (cur == null || !visited.add(cur)) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos n = cur.add(dx, dy, dz);
                            if (Math.abs(n.getX() - start.getX()) > CAVE_SCAN_RADIUS
                                    || Math.abs(n.getZ() - start.getZ()) > CAVE_SCAN_RADIUS) continue;
                            net.minecraft.block.Block nb = mod.getWorld().getBlockState(n).getBlock();
                            if (nb == Blocks.AIR || nb == Blocks.CAVE_AIR) {
                                queue.add(n);
                            }
                        }
                    }
                }
            }
            return visited;
        }

        // (2026-09-01 三维开阔度) 连通空气集合的 X/Z/Y 跨度是否都达到阈值。
        // 自挖楼梯(1-2格宽、2格高)至少一个方向跨度不足 → false, 不会把自己
        // 挖的通道当成天然洞。天然洞/繁茂洞穴 X/Z/Y 都开阔 → true。
        private boolean isOpenCaveSpace(Set<BlockPos> airs) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : airs) {
                minX = Math.min(minX, p.getX());
                maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxZ = Math.max(maxZ, p.getZ());
            }
            return maxX - minX >= CAVE_OPEN_SPAN_X
                    && maxZ - minZ >= CAVE_OPEN_SPAN_Z
                    && maxY - minY >= CAVE_OPEN_SPAN_Y;
        }

        private Optional<BlockPos> findNearbyCave(AltoClef mod) {
        BlockPos center = mod.getPlayer().getBlockPos();
        // (2026-09-01 矿洞深度感知, 用户"刚挖进去头上有方块就又检测到外面是矿洞")
        // 露天闸门不够: 玩家刚往下挖几格头顶就有方块(不露天), 但玩家仍在浅层,
        // 旁边/上方就是地表空气 → 仍误判"发现矿洞"。人类刚挖进地下时知道自己还
        // 在浅层, 不会"发现矿洞" — 洞检测必须要求玩家已挖到真正的地下矿洞层:
        // 玩家 y 低于 CAVE_EXPLORE_MIN_DEPTH_Y 才允许检测。地表/浅层(y 高)即使
        // 头顶有方块(挖开的楼梯)也不检测。
        if (center.getY() > CAVE_EXPLORE_MIN_DEPTH_Y) {
            return Optional.empty();
        }
        // (2026-09-01 露天不检测洞, 二次保险) 玩家头顶无实心覆盖(露天) → 关闭检测。
        // 深度闸门放行后(已在地下)本判据几乎恒真, 但保留防"挖穿到地表天窗"等
        // 罕见场景。树冠不算顶(树下仍算露天)。
        if (!hasOverheadCover(mod, center)) {
            return Optional.empty();
        }
        // (2026-08-31 挖穿才进洞探索, 用户"挖一半墙后探测到洞就触发探索, 不让挖方块还绕远路")
        // 只认「与玩家开放空间连通」的洞空气: 墙后隔着实心、还没挖开的洞空气不连通 →
        // 不触发探索, 继续挖穿; 一旦挖开/从洞口进入, 洞空气进入连通集 → 才触发 BiasedExplore
        // 进洞。杜绝"挖一半被墙后预知触发的探索抢走挖矿、绕远路"。
        Set<BlockPos> reachableAir = collectConnectedAir(mod, center);
        // (2026-09-01 自挖块排除, 核心闸门) 连通空气里只认【天然空气】:
        // 排除本 mod 静态记录的自挖位置(多条自挖隧道缝合的跨度再大也不算),
        // 排除 y>CAVE_NATURAL_AIR_MAX_Y 的高处空气(防自挖楼梯连到地表被计入)。
        // 自挖网络天然空气≈0 → 数量闸门直接拦下; 挖穿进真洞 → 洞内天然空气
        // 经自挖口连通进集合, 数量+三维跨度均达标 → 正常触发。
        Set<BlockPos> naturalAir = new HashSet<>();
        for (BlockPos p : reachableAir) {
            if (p.getY() > CAVE_NATURAL_AIR_MAX_Y) continue;
            if (isSelfDugAir(mod, p)) continue;
            naturalAir.add(p);
        }
        if (naturalAir.size() < MIN_NATURAL_CAVE_AIR) {
            return Optional.empty();
        }
        // (2026-09-01 三维开阔度判定, 用户"又把自己的洞当成矿洞") 天然洞与
        // 通道的本质区别是【空间形状】: 天然洞在 X/Z/Y 三维都开阔。跨度判定
        // 现在只统计天然空气 — 自挖楼梯/隧道即使连通集合庞大, 天然空气侧
        // 约束为零。不依赖空气类型(cave_air vs 普通 air), 繁茂洞穴自然覆盖。
        if (!isOpenCaveSpace(naturalAir)) {
            return Optional.empty();
        }
        BlockPos best = null;
        double bestDistSq = Double.NEGATIVE_INFINITY;
        for (BlockPos check : naturalAir) {
            // 只认与玩家连通的空间(reachableAir 已保证, 防透视: 墙后未挖开的洞空气不连通, 不触发)。
            // Require a couple of adjacent air blocks so a single hole doesn't count as a cave.
            if (!hasCaveNeighbors(mod, check, 2)) continue;
            double distSq = check.getSquaredDistance(center);
            if (distSq > bestDistSq) {
                bestDistSq = distSq;
                best = check;
            }
        }
        // (2026-08-30 诊断) 打印"是否判定为矿洞"及连通/天然空气规模, 便于区分
        // "没扫到开阔洞" vs "扫到了但没切探索"。
        if (MinecraftClient.getInstance().world != null
                && MinecraftClient.getInstance().world.getTime() % 200L == 0L) {
            Debug.logToFile("altoclef_diag.log",
                    String.format("[MineDown-CaveScan] center=(%d,%d,%d) R=%d connectedAir=%d naturalAir=%d hasCave=%s",
                            center.getX(), center.getY(), center.getZ(), CAVE_SCAN_RADIUS,
                            reachableAir.size(), naturalAir.size(), best != null));
        }
        return Optional.ofNullable(best);
    }

    // (2026-09-01 矿洞深度感知) 只有玩家 y 低于此值(已挖到地下矿洞层)才允许
    // 洞检测。地表/浅层(y 高)即使头顶有方块(挖开的楼梯)也不算"发现矿洞"。
    // 海平面 63, 地表 70-80; 主流矿洞/洞穴系统在 y<45 以下较丰富, 取 45。
    private static final int CAVE_EXPLORE_MIN_DEPTH_Y = 45;

    // (2026-09-01 地表误判矿洞修复) 判定一块空气上方是否有"岩顶"覆盖。
    // 向上扫 CAVE_COVER_SCAN_UP 格: 遇到实心方块 → 有顶(true); 全是空气/
    // 流体/树冠(树叶/原木) → 露天(false)。树冠不算岩顶, 避免砍树区域误判。
    // 未加载列保守视为有顶(不冒险把已见区域当洞外的天)。繁茂洞穴顶通常在
    // 玩家上方几格到几十格内, 24 格上限足够覆盖, 也避免地表向上扫 60 格。
    private static final int CAVE_COVER_SCAN_UP = 24;

    private boolean hasOverheadCover(AltoClef mod, BlockPos pos) {
        BlockPos up = pos;
        for (int i = 0; i < CAVE_COVER_SCAN_UP; i++) {
            up = up.up();
            if (!mod.getChunkTracker().isChunkLoaded(up)) return true;
            net.minecraft.block.Block b = mod.getWorld().getBlockState(up).getBlock();
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR) continue;
            if (b instanceof net.minecraft.block.LeavesBlock
                    || b instanceof net.minecraft.block.PillarBlock
                    || b instanceof net.minecraft.block.AzaleaBlock) continue; // 树冠/树干不算岩顶
            if (!b.getDefaultState().getFluidState().isEmpty()) continue; // 流体不算
            return true; // 实心覆盖
        }
        return false;
    }

    private boolean hasCaveNeighbors(AltoClef mod, BlockPos pos, int required) {
        int count = 0;
        for (int dy = -1; dy <= 1 && count < required; dy++) {
            for (int dz = -1; dz <= 1 && count < required; dz++) {
                for (int dx = -1; dx <= 1 && count < required; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (WorldHelper.isAir(mod, pos.add(dx, dy, dz))) count++;
                }
            }
        }
        return count >= required;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _inCave = false;
        _subTask = null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // FIX (stuck bug): once we've hit the bottom the shaft's job is done.
        // Requiring an empty queue here could deadlock the task (and the bot)
        // forever when leftover blocks remained from an interrupted final step.
        return !_inCave && reachedBottom(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        // Identity comparison. The parent task creates a NEW instance when a
        // dig run finishes (isFinished). With plain "instanceof" equality the
        // Task framework would treat the fresh task as equal to the finished
        // one, never swap it in, and keep ticking the DEAD task forever —
        // the bot freezes on "Shaft done". Each dig run is a distinct task.
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return _tunnelMode ? "Digging a tunnel to find ores" : "Digging down to find ores";
    }
}
