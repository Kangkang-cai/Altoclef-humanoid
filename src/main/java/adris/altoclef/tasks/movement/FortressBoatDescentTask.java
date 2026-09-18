package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * (2026-09-18 要塞船降) "下不去"兜底: bot 站在要塞上部, 可行走地面 (走廊/桥面)
 * 都在下方大落差处, baritone 拒绝跳 —— 用"船免摔落"方案下降。
 *
 * 机制依据 (Java 版, Minecraft Wiki): 船完全抵消自身和骑乘者的所有摔落伤害。
 *
 * 几何 (dir = 从岸边块 S 指向落点列 C 的水平单位向量):
 *
 *   B  S  C
 *   ▓  ▓  .   ← bot 站 B (落点列方向前 2 格), 船放 S (前 1 格) 顶面
 *   ▓  ▓  .      S 与 C 之间的 C 列从脚平面到落点上方必须贯通 (少量遮挡可挖)
 *   ░  ░  .   ← C 列垂直贯通到落点, 船载 bot 直落
 *         ▓▓  ← 要塞桥面 (落点: 下界砖地面 + 上方 2 格空气)
 *
 * 步骤: ①确保背包有船 (或附近已有船) ②走到 B 并挖通 C 列顶部遮挡 ③瞄 S 顶面
 * 靠 C 侧放船 (放置朝向决定船头朝向 → forward 直驶出边缘) ④上船按住前进滑落
 * ⑤潜行下船 + 收船 (打掉捡回, 超时弃船 —— 船只值 5 块木板, 不恋战)。
 *
 * 触发方: FortressExploreTask 走廊模式 "no walkable floor found yet" 持续
 * ≥ fortressBoatDescentStuckSeconds (用户拍板 15s) 后扫描落点并启动本任务;
 * 结算/冷却/失败计数都在父任务里 (maybeBoatDescent)。
 */
public class FortressBoatDescentTask extends Task implements ITaskRequiresGrounded {

    private enum State { ENSURE_BOAT, DIG, PLACE_BOAT, MOUNT, RIDE, DISMOUNT_COLLECT }

    // 4 个水平朝向: dir[0]/dir[1] = 从 S 指向 C 的单位向量。
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // 超时 (秒)
    private static final long TIMEOUT_TOTAL_SECONDS = 90;
    private static final long TIMEOUT_ENSURE_BOAT_SECONDS = 60;
    private static final long TIMEOUT_DIG_SECONDS = 30;
    private static final long TIMEOUT_MOUNT_SECONDS = 6;
    private static final long TIMEOUT_RIDE_STUCK_SECONDS = 6;
    private static final long TIMEOUT_COLLECT_SECONDS = 15;
    private static final int MAX_PLACE_ATTEMPTS = 6;
    /** C 列顶部遮挡只挖"距脚平面 ≤ 此深度"的格子 (再深超出边缘可达距离)。 */
    private static final int MAX_DIG_DEPTH = 2;

    private final BlockPos _landing;
    private State _state = State.ENSURE_BOAT;
    private Entity _boat = null;
    private long _startTick = -1;
    private long _stateEnterTick = -1;
    private int _placeAttempts = 0;
    private boolean _done = false;
    private String _abortReason = null;

    private final TimerGame _placeTimer = new TimerGame(1.0f);
    private final TimerGame _mountTimer = new TimerGame(1.0f);
    private final TimerGame _attackTimer = new TimerGame(1.0f);

    public FortressBoatDescentTask(BlockPos landing) {
        _landing = landing;
    }

    /** 落点走廊格 (其 down() 为下界砖地面)。 */
    public BlockPos getLanding() {
        return _landing;
    }

    // ------------------------------------------------------------------ //
    // 落点几何校验 (静态: 父任务扫描时预筛 + 本任务运行时复核, 同一实现)
    // ------------------------------------------------------------------ //

    /**
     * 落点是否可船降: 存在某朝向, 使 S/B 同层实心、C 列 (脚平面→落点上方)
     * 贯通或浅遮挡可挖、落点不邻岩浆。
     */
    public static boolean isLandingViable(AltoClef mod, BlockPos player, BlockPos landing) {
        return findApproachDir(mod, player.getY(), landing) != null;
    }

    private static int[] findApproachDir(AltoClef mod, int feetY, BlockPos landing) {
        for (int[] d : DIRS) {
            BlockPos s = new BlockPos(landing.getX() - d[0], feetY - 1, landing.getZ() - d[1]);
            BlockPos b = new BlockPos(landing.getX() - 2 * d[0], feetY - 1, landing.getZ() - 2 * d[1]);
            // S 顶实心 (放船面) + S 上方空气 (船体空间) + B 顶实心 (站位)。
            if (!isSolidTop(mod, s) || !isAirAt(mod, s.up()) || !isSolidTop(mod, b)) continue;
            if (!columnOpen(mod, feetY, landing)) continue;
            if (lavaAdjacent(mod, landing)) continue;
            return d;
        }
        return null;
    }

    private static boolean isSolidTop(AltoClef mod, BlockPos pos) {
        BlockState st = mod.getWorld().getBlockState(pos);
        return !st.isAir() && st.isSolidBlock(mod.getWorld(), pos);
    }

    private static boolean isAirAt(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).isAir();
    }

    private static boolean isDeadly(BlockState st) {
        return st.getBlock() == Blocks.LAVA || st.getBlock() == Blocks.WATER
                || st.getBlock() == Blocks.FIRE || st.getBlock() == Blocks.SOUL_FIRE;
    }

    /** C 列从脚平面到落点上方一格: 全空气, 或顶部 ≤MAX_DIG_DEPTH 的实心遮挡 (可挖)。 */
    private static boolean columnOpen(AltoClef mod, int feetY, BlockPos landing) {
        for (int y = feetY; y > landing.getY(); y--) {
            BlockState st = mod.getWorld().getBlockState(new BlockPos(landing.getX(), y, landing.getZ()));
            if (st.isAir()) continue;
            if (isDeadly(st)) return false;
            if (!st.isSolidBlock(mod.getWorld(), new BlockPos(landing.getX(), y, landing.getZ()))) return false;
            if (feetY - y > MAX_DIG_DEPTH) return false;
        }
        return true;
    }

    private static boolean lavaAdjacent(AltoClef mod, BlockPos landing) {
        for (int[] d : DIRS) {
            if (mod.getWorld().getBlockState(new BlockPos(landing.getX() + d[0], landing.getY(), landing.getZ() + d[1])).getBlock() == Blocks.LAVA) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ //
    // 状态机
    // ------------------------------------------------------------------ //

    @Override
    protected void onStart(AltoClef mod) {
        _state = State.ENSURE_BOAT;
        _boat = null;
        _startTick = WorldHelper.getTicks();
        _stateEnterTick = _startTick;
        _placeAttempts = 0;
        _done = false;
        _abortReason = null;
        Debug.logMessage("[BOAT-DROP] 启动船降: 落点 " + _landing.toShortString()
                + " (落差 " + (mod.getPlayer() != null ? (mod.getPlayer().getBlockPos().getY() - _landing.getY()) : "?") + ")");
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        long now = WorldHelper.getTicks();
        if (now - _startTick > TIMEOUT_TOTAL_SECONDS * 20) {
            return abort(mod, "总超时 " + TIMEOUT_TOTAL_SECONDS + "s");
        }
        switch (_state) {
            case ENSURE_BOAT:
                return tickEnsureBoat(mod, now);
            case DIG:
                return tickDig(mod, now);
            case PLACE_BOAT:
                return tickPlaceBoat(mod);
            case MOUNT:
                return tickMount(mod, now);
            case RIDE:
                return tickRide(mod, now);
            case DISMOUNT_COLLECT:
                return tickDismountCollect(mod, now);
        }
        return null;
    }

    private Task tickEnsureBoat(AltoClef mod, long now) {
        // 附近已有船 (上次放的) → 直接去上船。必须在 hasItem 之前判:
        // 放船成功后背包里的船已被消耗 (同 BoatCrossingTask 教训)。
        Optional<Entity> nearby = findNearbyBoat(mod, 5);
        if (nearby.isPresent()) {
            _boat = nearby.get();
            enterState(State.MOUNT);
            return null;
        }
        if (mod.getItemStorage().hasItem(ItemHelper.WOOD_BOAT)) {
            enterState(State.DIG);
            return null;
        }
        // 下界没有可造船的木头 (crimson/warped 不行) —— 只在背包里有主世界
        // 木板时才尝试合成, 否则立刻放弃 (防止 CollectBoatTask 跑去别的维度找木头)。
        if (hasOverworldPlanks(mod)) {
            if (secondsInState(now) > TIMEOUT_ENSURE_BOAT_SECONDS) {
                return abort(mod, "造船超时 " + TIMEOUT_ENSURE_BOAT_SECONDS + "s");
            }
            setDebugState("Crafting boat from planks.");
            return TaskCatalogue.getItemTask("boat", 1);
        }
        return abort(mod, "背包无船且无主世界木板 (下界造不了船) —— 需要 loadout 预带船/木板");
    }

    private Task tickDig(AltoClef mod, long now) {
        if (secondsInState(now) > TIMEOUT_DIG_SECONDS) {
            return abort(mod, "挖通道超时 " + TIMEOUT_DIG_SECONDS + "s");
        }
        int feetY = mod.getPlayer().getBlockPos().getY();
        int[] dir = findApproachDir(mod, feetY, _landing);
        if (dir == null) {
            return abort(mod, "落点几何失效 (现场变了或朝向全灭)");
        }
        BlockPos b = new BlockPos(_landing.getX() - 2 * dir[0], feetY - 1, _landing.getZ() - 2 * dir[1]);
        BlockPos s = new BlockPos(_landing.getX() - dir[0], feetY - 1, _landing.getZ() - dir[1]);
        // 先走到站位 B (站在 S 上放不出被自己压住的船? 不 —— 放船面是 S, 人必须站在 B)。
        double dx = mod.getPlayer().getX() - (b.getX() + 0.5);
        double dz = mod.getPlayer().getZ() - (b.getZ() + 0.5);
        if (Math.sqrt(dx * dx + dz * dz) > 0.8) {
            setDebugState("Walking to launch stand " + b.toShortString());
            return new GetToXZTask(b.getX(), b.getZ());
        }
        // 挖: ①S 顶上那格 (放船空间) ②C 列自上而下的实心遮挡。
        BlockPos sHead = new BlockPos(s.getX(), feetY, s.getZ());
        if (!mod.getWorld().getBlockState(sHead).isAir()) {
            setDebugState("Digging boat slot above S.");
            return new DestroyBlockTask(sHead);
        }
        for (int y = feetY; y > _landing.getY(); y--) {
            BlockPos p = new BlockPos(_landing.getX(), y, _landing.getZ());
            BlockState st = mod.getWorld().getBlockState(p);
            if (st.isAir()) continue;
            if (isDeadly(st)) return abort(mod, "C 列出现液体/火, 放弃");
            setDebugState("Digging shaft cell y=" + y);
            return new DestroyBlockTask(p);
        }
        // 列已贯通 → 放船。
        enterState(State.PLACE_BOAT);
        return null;
    }

    private Task tickPlaceBoat(AltoClef mod) {
        // 放出了船 → 上船。先于 hasItem 判 (放船消耗背包的船)。
        Optional<Entity> nearby = findNearbyBoat(mod, 5);
        if (nearby.isPresent()) {
            _boat = nearby.get();
            enterState(State.MOUNT);
            return null;
        }
        if (!mod.getItemStorage().hasItem(ItemHelper.WOOD_BOAT)) {
            // 船没了也没放出 → 回去重新拿。
            enterState(State.ENSURE_BOAT);
            return null;
        }
        int feetY = mod.getPlayer().getBlockPos().getY();
        int[] dir = findApproachDir(mod, feetY, _landing);
        if (dir == null) {
            return abort(mod, "放船阶段几何失效");
        }
        if (!mod.getSlotHandler().forceEquipItem(ItemHelper.WOOD_BOAT)) {
            setDebugState("Waiting to equip boat.");
            return null;
        }
        // 瞄 S 顶面靠 C 侧: 船头朝向 = 放置时玩家视线方向 → 落后按住前进即滑向 C。
        Vec3d aim = new Vec3d(
                _landing.getX() - dir[0] + 0.5 + dir[0] * 0.4,
                feetY,
                _landing.getZ() - dir[1] + 0.5 + dir[1] * 0.4);
        LookHelper.lookAt(mod, aim);
        if (_placeTimer.elapsed()) {
            mod.getInputControls().tryPress(Input.CLICK_RIGHT); // 人类式右键 (同 BoatCrossingTask)
            _placeTimer.reset();
            _placeAttempts++;
            if (_placeAttempts >= MAX_PLACE_ATTEMPTS) {
                return abort(mod, "放船 " + MAX_PLACE_ATTEMPTS + " 次未成功");
            }
        }
        setDebugState("Placing boat (attempt " + _placeAttempts + "/" + MAX_PLACE_ATTEMPTS + ").");
        return null;
    }

    private Task tickMount(AltoClef mod, long now) {
        if (mod.getPlayer().getVehicle() instanceof BoatEntity) {
            enterState(State.RIDE);
            return null;
        }
        if (secondsInState(now) > TIMEOUT_MOUNT_SECONDS) {
            enterState(State.PLACE_BOAT); // 回去重新放/找
            _placeAttempts = 0;
            return null;
        }
        _boat = findNearbyBoat(mod, 5).orElse(null);
        if (_boat == null) {
            enterState(State.PLACE_BOAT);
            _placeAttempts = 0;
            return null;
        }
        LookHelper.lookAt(mod, _boat.getPos());
        mod.getController().interactEntity(mod.getPlayer(), _boat, Hand.MAIN_HAND);
        setDebugState("Mounting boat.");
        return null;
    }

    private Task tickRide(AltoClef mod, long now) {
        if (!(mod.getPlayer().getVehicle() instanceof BoatEntity)) {
            // 中途下船/船碎: 已到底 → 收尾; 没到底 → 放弃。
            if (mod.getPlayer().getBlockPos().getY() <= _landing.getY() + 2) {
                enterState(State.DISMOUNT_COLLECT);
                return null;
            }
            return abort(mod, "骑行途中船消失且未到底");
        }
        if (isDeadly(mod.getWorld().getBlockState(mod.getPlayer().getBlockPos()))) {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            return abort(mod, "骑行途中接触岩浆, 立即放弃 (交给应急链)");
        }
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        if (mod.getPlayer().getBlockPos().getY() <= _landing.getY() + 1) {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            enterState(State.DISMOUNT_COLLECT);
            return null;
        }
        // 卡顶检测: 骑了很久还在出发高度 → 船没滑出去。
        if (mod.getPlayer().isOnGround()
                && mod.getPlayer().getBlockPos().getY() >= _landing.getY() + MAX_DIG_DEPTH + 4
                && secondsInState(now) > TIMEOUT_RIDE_STUCK_SECONDS) {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            return abort(mod, "船未滑落边缘 (卡顶 " + TIMEOUT_RIDE_STUCK_SECONDS + "s)");
        }
        setDebugState("Riding boat down (y=" + mod.getPlayer().getBlockPos().getY()
                + " → " + _landing.getY() + ")");
        return null;
    }

    private Task tickDismountCollect(AltoClef mod, long now) {
        if (mod.getPlayer().getVehicle() instanceof BoatEntity) {
            mod.getInputControls().tryPress(Input.SNEAK);
            setDebugState("Dismounting.");
            return null;
        }
        if (secondsInState(now) > TIMEOUT_COLLECT_SECONDS) {
            Debug.logMessage("[BOAT-DROP] 弃船 (收船超时 " + TIMEOUT_COLLECT_SECONDS + "s, 船只值 5 块木板)");
            finish("下船完成 (弃船)");
            return null;
        }
        // 船实体还在 → 打掉。
        Optional<Entity> boat = findNearbyBoat(mod, 6);
        if (boat.isPresent()) {
            _boat = boat.get();
            LookHelper.lookAt(mod, _boat.getPos());
            if (_attackTimer.elapsed()) {
                mod.getControllerExtras().attack(_boat);
                _attackTimer.reset();
            }
            setDebugState("Breaking boat to collect.");
            return null;
        }
        // 掉落物 → 捡回 (PickupDroppedItemTask: 满包腾位 + 同高度抓取, 同 BoatCrossingTask)。
        ItemEntity item = findNearbyBoatItem(mod, 8);
        if (item != null) {
            setDebugState("Picking up boat item.");
            return new PickupDroppedItemTask(item.getStack().getItem(), 1);
        }
        // 既无实体也无物品 → 已吸收进背包。
        finish("船降完成");
        return null;
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private void enterState(State next) {
        if (next != _state) {
            _state = next;
            _stateEnterTick = WorldHelper.getTicks();
        }
    }

    private long secondsInState(long nowTick) {
        return (nowTick - _stateEnterTick) / 20;
    }

    private Task abort(AltoClef mod, String reason) {
        _abortReason = reason;
        mod.getInputControls().release(Input.MOVE_FORWARD);
        Debug.logWarning("[BOAT-DROP] 放弃船降: " + reason);
        return null;
    }

    private void finish(String note) {
        _done = true;
        Debug.logMessage("[BOAT-DROP] " + note + " (落点 " + _landing.toShortString() + ")");
    }

    private Optional<Entity> findNearbyBoat(AltoClef mod, double range) {
        Vec3d p = mod.getPlayer().getPos();
        for (Entity e : mod.getWorld().getEntities()) {
            if (e instanceof BoatEntity && e.isAlive() && e.squaredDistanceTo(p) < range * range) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private ItemEntity findNearbyBoatItem(AltoClef mod, double range) {
        Vec3d p = mod.getPlayer().getPos();
        for (Entity e : mod.getWorld().getEntities()) {
            if (e instanceof ItemEntity ie && ie.isAlive()
                    && ie.getStack().getItem() instanceof Item
                    && isBoatItem(ie.getStack().getItem())
                    && ie.squaredDistanceTo(p) < range * range) {
                return ie;
            }
        }
        return null;
    }

    private static boolean isBoatItem(Item item) {
        for (Item b : ItemHelper.WOOD_BOAT) {
            if (b == item) return true;
        }
        return false;
    }

    private static boolean hasOverworldPlanks(AltoClef mod) {
        return mod.getItemStorage().hasItem(
                Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
                Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (!isActive()) return false; // 未启动不给判完成 (同 DigOutEscapeTask 教训)
        return _done || _abortReason != null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FortressBoatDescentTask
                && ((FortressBoatDescentTask) other)._landing.equals(_landing);
    }

    @Override
    protected String toDebugString() {
        return "Fortress boat descent -> " + _landing.toShortString()
                + (abortReasonForDebug() != null ? " (" + abortReasonForDebug() + ")" : "");
    }

    private String abortReasonForDebug() {
        return _abortReason;
    }
}
