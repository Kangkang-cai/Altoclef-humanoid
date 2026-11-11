package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
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

    // ===== (2026-10-26 逃生收尾判据三条腿) "逃出来 != 安全" =====
    // 现场 20261025-232203 (bot2 跟随 charlie, death #2, cause=onFire)：
    //   23:21:48 落岩浆 (inLava 瞬时 true) → 23:21:51 僵持 forceCancel →
    //   23:21:52.499 采样 onFire=true / inLava=false / pathing=false / 零水平位移 →
    //   23:21:53 isFinished 判 "!isInLava && !isOnFire" → Completing goal →
    //   让位给跟随任务 → 23:22:03 死亡，坐标仍是 (88,-54,183) **一格未挪**。
    //   23:27:46~23:28:02 同型第二次 (128,-54,205 → 204)，两次都死在原地。
    // 两条结构性缺陷：
    //   ① 脚下仍浸岩浆 (方块口径 inLava=false、但该格接触岩浆面) 时, MC 会周期性
    //      重新点燃 ⇒ isOnFire 在 tick 之间抖成 false。瞬时判据把"灭了一瞬"当脱险，
    //      与 0920 灵魂沙那次"判据被瞬时值骗过"同源；
    //   ② 完成判据里完全没有"站位是否还危险"这一维 —— EscapeFromLavaGoal 早就把
    //      "自身格 + 四邻无岩浆"写成了 goal，却只喂 baritone、不参与收尾（判定源分裂，
    //      违反"判定源单一化"）。
    // 现在：身体(不着火·不在岩浆) ∧ 站位(goal.isInGoal) ∧ 持续 OUT_OF_LAVA_GRACE_S。
    /**
     * 站位危险但火已灭时的硬顶：四面被岩浆围死、baritone 无路可走时，继续占 prio 100
     * 只会把跟随/进食等链全饿死。该时刻已不掉血，放手不会造成"交回跟随任务再烧死"。
     * 45s = 覆盖 15s 火 tick 复燃窗 + 多轮 forceCancel/垫块尝试。
     */
    private static final long LAVA_ESCAPE_STANCE_CAP_MS = 45000;
    /** 连续站位危险的起点时刻（火已灭口径）；0 = 当前不处于该状态。 */
    private long _stanceUnsafeSinceMs = 0;

    // ===== (2026-09-20 灵魂沙边缘浸岩浆) "贴地锚点" latch =====
    // 危险判据 (EscapeFromLavaGoal) 必须跑在一个**稳定**的坐标上。用
    // player.getBlockPos() 会撞上"瞬时判据横跳":
    //   本任务的求生动作 (上浮) 把 bot 从 y=31 (危险 —— 同层相邻格正是岩浆, 脚因
    //   灵魂沙矮 0.125 而被液面浸到) 抬到 y=32/33 (同一套判据看那些 y 层却是"空气
    //   邻格", 于是判安全) → checkEscapeStall 的豁免条件每周期被满足一次 →
    //   _stuckSinceMs 反复清零 → 12.5 秒里 forceCancel 与岸边垫块一次都没触发。
    //   现场 20260920-155006 实锤: 15:51:57.528 进入 Escaping lava, 到 15:52:10.578
    //   死亡 (cause=lava), 全程 [LavaEscape-Stuck] 与 "岸边垫块上岸" 日志双双 0 条,
    //   而 NAV-STUCK 记录 netXZ=0.00 / yRange=1.67。
    // 口径 = "最近一次贴地 / 身处流体时的格", 只在 onGround() 或 isInLava() 时更新;
    // 空中飞行的帧不更新 (日志里 y=32.29/32.40/32.71/32.78 那些采样均落在此类)。
    // 对灵魂沙场景等价于"我就站在 (17,31,71) 这个事实", 上浮全过程恒定。
    private BlockPos _anchor = null;

    /** 见 _anchor 字段注释。返回 null 表示玩家尚未就绪。 */
    private BlockPos standingAnchor(AltoClef mod) {
        ClientPlayerEntity p = mod.getPlayer();
        if (p == null) return null;
        if (_anchor == null || p.isOnGround() || p.isInLava()) {
            _anchor = p.getBlockPos();
        }
        return _anchor;
    }

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
        _anchor = null;
        _stanceUnsafeSinceMs = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-09-20 灵魂沙边缘浸岩浆) 上浮条件由 "isInLava()"(AABB 接触口径) 收紧为
        // "脚所在格 / 脚下方格是岩浆方块"(格子口径) —— 两者之差正是"擦到"与"泡在":
        //   · 泡在岩浆里 / 悬在岩浆面上 → 上浮有意义 (在流体内按住 JUMP = 上浮)。
        //   · 站在实心面上、仅脚部被相邻岩浆浸到 → 上浮不但无意义, 而且有害:
        //     isInLava() 为真时 MC 走流体移动分支, JUMP 被当作上浮 → 抬升 1.6 格
        //     (远超普通跳跃 1.25) → 脱离岩浆 → isInLava()=false → 掉回原位 → 再上浮,
        //     形成"原地上下跳"死循环 (20260920-155006: netXZ=0.00 / yRange=1.67 /
        //     inputs[jump=true] 对 bIn[jump=false], 12.5s 烧死)。
        //   · 更致命的是它把 bot 抬进"看起来安全"的 y 层, 使危险判据横跳 (见 _anchor)。
        //   收紧后, 站在灵魂沙上时不再自锁弹跳, 脱险完全交给 baritone 走位 +
        //   tryPlaceBankStep 盖掉脚边岩浆格这两条正路。
        BlockPos feet = mod.getPlayer().getBlockPos();
        boolean feetIsLava = mod.getWorld().getBlockState(feet).getBlock() == Blocks.LAVA;
        boolean belowIsLava = mod.getWorld().getBlockState(feet.down()).getBlock() == Blocks.LAVA;
        if (feetIsLava || belowIsLava) {
            // Sprint through lava + jump, it's faster
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

        BlockPos feet = standingAnchor(mod);
        if (feet == null) return null;
        for (Direction dir : HORIZONTALS) {
            BlockPos t = feet.offset(dir);
            BlockState ts = mod.getWorld().getBlockState(t);
            boolean replaceable = ts.isAir() || ts.getBlock() == Blocks.LAVA || ts.getMaterial().isReplaceable();
            if (!replaceable) continue;
            BlockPos bank = t.offset(dir);
            // (2026-09-20 灵魂沙边缘浸岩浆) 原判据 `WorldHelper.isSolid(bank)` 走的是
            // BlockState.isSolidBlock, 对灵魂沙口径存疑 (灵魂沙碰撞盒 0.875、非满立方);
            // 而灵魂沙谷恰恰是本逻辑最需要生效的场景 (脚边岩浆 + 前方灵魂沙岸)。
            // 放宽为"是个实体方块 (非空气、非流体)"——比原判据更宽, 不会收窄任何原有
            // 可放置面, 零回归。
            BlockState bs = mod.getWorld().getBlockState(bank);
            if (bs.isAir() || !bs.getFluidState().isEmpty()) continue;
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
     * 仅水平方向判僵硬 —— 垂直位移不计入"有进展"(下坠与浮沉都不算脱险), 只看"有没有
     * 往外挪"。
     *
     * (2026-09-20 更正) 本注释原文是"垂直不动不算 (原地跳处理岩浆是合法的)" —— **这句
     * 是错的**, 且正是 20260920-155006 那次 12.5s 烧死的帮凶:
     *   · 站在灵魂沙上被相邻岩浆浸到时, 上浮只是"抬起→脱离→掉回"的死循环, 12.5s 零水平
     *     位移, 从来不是"合法的处理岩浆方式";
     *   · 更糟的是上浮把 bot 抬进 y=32/33 的"安全 y 层", 使下面的豁免条件每周期成立,
     *     本方法 12.5s 内一次都没走到 forceCancel —— 所谓"不判垂直"直接变成了"不判"。
     * 现在豁免必须同时满足 goal 判据与身体判据 (见下方 "两道加固")。
     */
    private void checkEscapeStall(AltoClef mod) {
        long now = System.currentTimeMillis();
        // 已经站在"不在岩浆里、四邻也没岩浆"的位置: 剩下只是等火灭 (isFinished 的
        // 判据), 站着不动是对的, 不该重算路径刷日志。
        //
        // (2026-09-20 灵魂沙边缘浸岩浆) 两道加固, 缺一条就会回到 12.5s 烧死的现场:
        //   ① 判据坐标改用**贴地锚点** (见 _anchor 注释)。原来用 getBlockPos(), 本任务
        //      自己的上浮动作会把它抬到 y=32/33 的"安全 y 层", 使这里每周期豁免一次。
        //   ② 加**身体状态闸门**: 仍在着火 / 仍被岩浆浸 (isInLava) 时不得清零计时。
        //      这两个信号是"我还没脱险"的硬事实, 身份高于 goal 的格子推断 ——
        //      goal 说安全而身体在烧, 恰恰是"判据没覆盖到真实危险"的签名。
        BlockPos here = standingAnchor(mod);
        if (here == null) return;
        boolean goalSaysSafe = newGoal(mod).isInGoal(here.getX(), here.getY(), here.getZ());
        boolean bodySaysSafe = !mod.getPlayer().isOnFire() && !mod.getPlayer().isInLava();
        if (goalSaysSafe && bodySaysSafe) {
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
        // ===== 三条腿同时成立才收尾 (见字段区 LAVA_ESCAPE_STANCE_CAP_MS 上方注释) =====
        // ① 身体硬闸：泡在岩浆里 —— 无论有没有防火，逃出去之前一律不算完成。
        if (mod.getPlayer().isInLava()) {
            _outOfLavaTimer.reset();
            _stanceUnsafeSinceMs = 0;
            return false;
        }
        if (!_started) return false; // onStart 前计时器不可信 (BaseTimer 初始态)
        // ② 身体硬闸：身上还在着火 = 还在按秒掉血，一律不算完成。
        //    旧实现只有在"有防火"时才走滞回，无防火直接 `return !isOnFire()` —— 那
        //    正是 20261025 两次死亡的口子：浸脚格反复点燃，抖出一个 false 帧就放行。
        if (mod.getPlayer().isOnFire()) {
            _outOfLavaTimer.reset();
            _stanceUnsafeSinceMs = 0;
            return false;
        }
        // ③ 站位闸：自己这一格或水平四邻仍是岩浆 ⇒ 一放开就会被跟随任务带回原格
        //    (两次现场都是"完成后一格未挪、8 秒后复燃致死")。baritone 的目标本来就是
        //    这样的格，用它当收尾判据 = 目标与完成条件同源，不再分裂。
        BlockPos here = standingAnchor(mod);
        boolean stanceSafe = here != null
                && newGoal(mod).isInGoal(here.getX(), here.getY(), here.getZ());
        if (!stanceSafe) {
            _outOfLavaTimer.reset();
            long now = System.currentTimeMillis();
            if (_stanceUnsafeSinceMs == 0) _stanceUnsafeSinceMs = now;
            if (now - _stanceUnsafeSinceMs >= LAVA_ESCAPE_STANCE_CAP_MS) {
                String where = here == null ? "?" : here.toShortString();
                Debug.logMessage("[LavaEscape] 火已灭但站位仍贴岩浆 "
                        + ((now - _stanceUnsafeSinceMs) / 1000) + "s 走不出去, 硬顶放手 at " + where);
                Debug.logToFile("altoclef_diag.log", "[LavaEscape-Exit] stance-unsafe "
                        + ((now - _stanceUnsafeSinceMs) / 1000) + "s, give up (onFire=false, 不掉血) at "
                        + where + " inLava=" + mod.getPlayer().isInLava());
                return true;
            }
            return false;
        }
        _stanceUnsafeSinceMs = 0;
        // ④ 滞回：全场景统一 1.5s（旧行为只有防火时有，无防火走瞬时值）
        return _outOfLavaTimer.elapsed();
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
