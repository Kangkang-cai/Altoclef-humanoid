package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.Arrays;

public class EscapeFromLavaTask extends CustomBaritoneGoalTask {

    private final float _strength;

    // (2026-09-14 逃生僵持兜底) EscapeFromLavaTask 的 _checker 是 999999 —— 刻意
    // 关掉无进展检查, "do NOT ever wander": 岩浆里按 wander 随机选点是向着更深的
    // 岩浆送。代价是 baritone 一旦拿到一条走不通的路径 (落点就在岩浆边上 / 算出的
    // 路径要跨它跨不过去), 本任务不会自己换路, 只能等更高优先级链抢占, 或者玩家手
    // 动介入 —— 而这正是最要命的时机。
    //   20260914-152654 实锤: 15:28:48.538 着火切入 Escaping lava, baritone 锁
    //   dest=(-234,-59,233) 后零位移 (NAV-STUCK dur 一路累到 12.2s), diag 显示
    //   bIn[fwd=true jump=true] 而 inputs[fwd=false jump=true] —— 路径要求和实际
    //   输入已经脱节。最后是 JumpStuckResolver 判 "no obstruction -> forceCancel"
    //   (15:28:50.389) 才重新动起来, 用户以为是自己介入救的场。
    // 兜底 (改写自 GetToLandTask 的同一套模板, 仓内已验证): 连续 LAVA_STUCK_MS
    // 水平位移不足 ⇒ forceCancel 作废当前路径逼 baritone 重算。不 wander、不主动
    // 往任何方向迈步, 只是把卡住的规划丢掉重来 —— 保留"绝不乱走"的原意。
    private static final long LAVA_STUCK_MS = 2500;
    private static final double STUCK_MIN_XZ_SQ = 0.25; // 0.5 格
    private long _stuckSinceMs = 0;
    private double _stuckLastX = 0;
    private double _stuckLastZ = 0;
    private boolean _stuckPrimed = false;
    private long _lastRerouteMs = 0;

    // ===== (2026-09-14 横跳修复) 防火状态下"浮出岩浆一瞬即完成"滞回 =====
    // 有 FIRE_RESISTANCE 时 isFinished 只看 isInLava() —— 游动 bob 浮出水面的
    // 半秒里任务直接判完成 → 链让位 → 沉回去再重开 (latest.log 20:54 每秒一轮
    // "Task START: <Escaping lava>")。要求离岩浆持续 out-of-lava 1.5s 才算完成;
    // 期间重新入水即重置计时。无防火路径维持原语义 (等火灭), 不受影响。
    private static final double OUT_OF_LAVA_GRACE_S = 1.5;
    private final TimerGame _outOfLavaTimer = new TimerGame(OUT_OF_LAVA_GRACE_S);
    // (EatItemTask 同款防御) SingleTaskChain 可能在 onStart 前就查 isFinished,
    // BaseTimer 初始 _prevTime=0 会误判 elapsed —— onStart 没跑过绝不判完成。
    private boolean _started = false;

    // ===== (2026-09-14 岸边垫块上岸, 用户"差一步上岸爬不上去, 岸边放方块走上去
    //       就逃生") =====
    // 20260914-205250 场次实锤: bot 贴岸 y=31.20 零位移 40s+, baritone
    // "22 movements considered / Open set size: 0" 算不出路 (从岩浆里够不着 +2
    // 高的岸), 本任务只会 2.5s 一轮 forceCancel 重算, 永远同一条死路。
    // 处置: 僵持 >=1s 时扫四个水平方向, 若存在"可垫的岸"——
    //   T = 脚边相邻格 (岩浆/可替换), B = T 再往前一格的实心岸块,
    //   B 上方两格 + T 上方可通行 (垫完能站上 T, 再一步踏上 B 顶)——
    // 就朝 B 朝向 T 的面右键放方块, 造一级台阶。材料用可丢弃方块
    // (getThrowawayItems 过滤 BlockItem, 与 BeatMinecraft2Task 建筑料同源)。
    // 放块动作交给 InteractWithBlockTask (wanderOnFailure=false: 岩浆里绝不高优 wander)。
    private static final long BANK_PLACE_MIN_STALL_MS = 1000;
    private static final long BANK_PLACE_COOLDOWN_MS = 4000;
    private long _lastBankPlaceMs = 0;
    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    public EscapeFromLavaTask(float strength) {
        _strength = strength;
    }
    public EscapeFromLavaTask() {
        this(100);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBehaviour().allowSwimThroughLava(true);
        // Encourage placing of all blocks!
        mod.getBehaviour().setBlockPlacePenalty(0);
        mod.getBehaviour().setBlockBreakAdditionalPenalty(20); // Normally 2

        // do NOT ever wander
        _checker = new MovementProgressChecker(999999);
        // (2026-09-14 横跳修复) 离场滞回计时从"进任务"起算。
        _started = true;
        _outOfLavaTimer.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Sprint through lava + jump, it's faster
        if (mod.getPlayer().isInLava() || mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().down()).getBlock() == Blocks.LAVA) {
            mod.getInputControls().hold(Input.JUMP);
            mod.getInputControls().hold(Input.SPRINT);
        }
        checkEscapeStall(mod);
        // (2026-09-14 岸边垫块上岸) 僵持且岸边可垫 → 返回放块子任务 (跑完自动回本任务)。
        Task bankStep = tryPlaceBankStep(mod);
        if (bankStep != null) return bankStep;
        return super.onTick(mod);
    }

    /**
     * (2026-09-14 岸边垫块上岸) 僵持 >= BANK_PLACE_MIN_STALL_MS 且四个方向里
     * 找得到"可垫的岸"时, 返回一个朝岸块侧面右键放方块的子任务; 否则 null。
     * 判据见字段区注释。冷却 BANK_PLACE_COOLDOWN_MS, 失败也不刷屏 (重算路径
     * 的 forceCancel 兜底仍在 checkEscapeStall 里独立运行)。
     */
    private Task tryPlaceBankStep(AltoClef mod) {
        if (_stuckSinceMs == 0) return null;
        long now = System.currentTimeMillis();
        if (now - _stuckSinceMs < BANK_PLACE_MIN_STALL_MS) return null;
        if (now - _lastBankPlaceMs < BANK_PLACE_COOLDOWN_MS) return null;
        // 建筑料: 可丢弃方块 (速度跑流程背的圆石/黑石/泥土都在这里)。
        Item[] mats = Arrays.stream(mod.getModSettings().getThrowawayItems(mod, true))
                .filter(item -> item instanceof BlockItem).toArray(Item[]::new);
        if (mats.length == 0 || mod.getItemStorage().getItemCount(mats) == 0) return null;

        BlockPos feet = mod.getPlayer().getBlockPos();
        for (Direction dir : HORIZONTALS) {
            BlockPos t = feet.offset(dir);
            BlockState ts = mod.getWorld().getBlockState(t);
            boolean replaceable = ts.isAir() || ts.getBlock() == Blocks.LAVA || ts.getMaterial().isReplaceable();
            if (!replaceable) continue;
            BlockPos bank = t.offset(dir);
            if (!WorldHelper.isSolid(mod, bank)) continue;
            // 垫完要能站上 T (T 上方空气), 并从 T 踏上 B 顶 (B 上方两格通行)。
            if (!mod.getWorld().getBlockState(t.up()).isAir()) continue;
            if (!isPassableForStand(mod, bank.up()) || !isPassableForStand(mod, bank.up(2))) continue;

            _lastBankPlaceMs = now;
            Debug.logMessage("[LavaEscape] 岸边垫块上岸: 放 " + t.toShortString()
                    + " (岸块 " + bank.toShortString() + ")");
            Debug.logToFile("altoclef_diag.log", "[LavaEscape-BankStep] place="
                    + t.toShortString() + " bank=" + bank.toShortString()
                    + " player=" + mod.getPlayer().getBlockPos().toShortString());
            // direction = 岸块上朝向 T 的面 (从 B 指向 T)。walkInto=false:
            // 不主动走进岩浆格, 站原地够得着 B 的面即可点。
            return new InteractWithBlockTask(new ItemTarget(mats, 1), dir.getOpposite(),
                    bank, Input.CLICK_RIGHT, false, Vec3i.ZERO, true, false);
        }
        return null;
    }

    private static boolean isPassableForStand(AltoClef mod, BlockPos pos) {
        BlockState s = mod.getWorld().getBlockState(pos);
        return s.isAir() || s.getMaterial().isReplaceable();
    }

    /**
     * (2026-09-14 逃生僵持兜底) 见字段区 LAVA_STUCK_MS 注释。
     * 这里补一道占位检查: 仅水平方向判僵硬 —— 垂直不动不算 (原地跳处理岩浆是合法的、
     * 站在方块上/下坠都可能), 只看"没往外挪"。
     */
    private void checkEscapeStall(AltoClef mod) {
        long now = System.currentTimeMillis();
        // 已经站在"不在岩浆里、四邻也没岩浆"的位置: 剩下只是等火灭 (isFinished 的
        // 判据), 站着不动是对的, 不该重算路径刷日志。
        BlockPos here = mod.getPlayer().getBlockPos();
        if (newGoal(mod).isInGoal(here.getX(), here.getY(), here.getZ())) {
            _stuckSinceMs = 0;
            return;
        }
        double x = mod.getPlayer().getX();
        double z = mod.getPlayer().getZ();
        double dx = x - _stuckLastX;
        double dz = z - _stuckLastZ;
        _stuckLastX = x;
        _stuckLastZ = z;
        if (!_stuckPrimed) {
            _stuckPrimed = true; // 首帧只有基准值, 没有位移可言
            return;
        }
        if (dx * dx + dz * dz >= STUCK_MIN_XZ_SQ) {
            _stuckSinceMs = 0;
            return;
        }
        if (_stuckSinceMs == 0) {
            _stuckSinceMs = now;
            return;
        }
        if (now - _stuckSinceMs < LAVA_STUCK_MS) return;
        if (now - _lastRerouteMs < LAVA_STUCK_MS) return; // 重算要有节奏, 别每 tick 作废路径
        long stalledMs = now - _stuckSinceMs;
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _lastRerouteMs = now;
        _stuckSinceMs = 0;
        Debug.logMessage("[LavaEscape] 逃生僵持, 重算路径");
        Debug.logToFile("altoclef_diag.log", "[LavaEscape-Stuck] 逃生 " + stalledMs
                + "ms 无水平位移 -> forceCancel 重算路径 at "
                + mod.getPlayer().getBlockPos().toShortString()
                + " xyz=(" + String.format("%.2f,%.2f,%.2f", mod.getPlayer().getX(),
                        mod.getPlayer().getY(), mod.getPlayer().getZ()) + ") onFire="
                + mod.getPlayer().isOnFire() + " inLava=" + mod.getPlayer().isInLava());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SPRINT);
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new EscapeFromLavaGoal();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EscapeFromLavaTask;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-14 防火岩浆自救) 有 FIRE_RESISTANCE 时出了岩浆身上仍会带着火
        // (防火不阻止着火状态, 只免疫伤害) → 旧的 isOnFire 判据会让任务粘着不放,
        // prio 100 长占主链。有防火时"离开岩浆"即完成 —— 但要滞回: 游动 bob 浮出
        // 水面的半秒不算逃生, 持续离场 OUT_OF_LAVA_GRACE_S 才收尾 (横跳修复,
        // 见字段区注释)。无防火维持原语义: 要继续到火灭 (火还在掉血)。
        if (mod.getPlayer().isInLava()) {
            _outOfLavaTimer.reset();
            return false;
        }
        if (!_started) return false; // onStart 前计时器不可信 (BaseTimer 初始态)
        if (mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            return _outOfLavaTimer.elapsed();
        }
        return !mod.getPlayer().isOnFire();
    }

    @Override
    protected String toDebugString() {
        return "Escaping lava";
    }

    private class EscapeFromLavaGoal implements Goal {

        private static boolean isLava(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return MovementHelper.isLava(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }
        private static boolean isLavaAdjacent(int x, int y, int z) {
            return isLava(x+1,y,z) || isLava(x-1,y,z) || isLava(x,y,z+1) || isLava(x,y,z-1);
        }
        private static boolean isWater(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return MovementHelper.isWater(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return !isLava(x, y, z) && !isLavaAdjacent(x,y,z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isLava(x, y, z)) {
                return _strength;
            } else if (isLavaAdjacent(x, y, z)) {
                return _strength * 0.5f;
            }
            if (isWater(x, y, z)) {
                return -100;
            }
            return 0;
        }
    }
}
