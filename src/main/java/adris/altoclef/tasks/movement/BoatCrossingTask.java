package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.wood.CollectBoatTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * 开船穿越 ≥15 格宽的大水面 (2026-08-25 用户设计)。
 *
 * 状态机:
 *   0 确保有船 (没有 → CollectBoatTask 合成: 5 木板 → 船)
 *   1 走到岸边 (目标方向上有水的陆地); 意外在水里先游回岸
 *   2 装备船, 右键水面放船, 等船实体出现
 *   3 靠近船, 右键上船 (上船后进入驾驶)
 *   4 面向远岸目标按住前进驾驶; 接近远岸/失速 → 下船
 *   5 潜行下船 → 装备剑/斧 → 左键连击打掉船(捡回) → 上岸
 *   6 完成
 *
 * (2026-08-25 rev3) 目标 = 【远岸】(第一块越过水域的陆地), 不是丢眼时算的
 * 固定 XZ — 否则海太大时船开到海中间就"到达"下船, 困在水里。
 * (2026-08-25 rev3) 已骑船进入 → 直接 S_DRIVE 续驾 (中途重定位/继续跨海)。
 * (2026-08-25 rev3) 驾驶中可选定期丢眼重定位 (_rethrowEyes=true, 海太大时
 * 海上重新确认方向; 船驶过落水的眼睛自动拾取)。
 *
 * 兜底: 驾驶中船消失/掉船 → 回 3 重新上船; 打船时船先没了 → 直接完成。
 */
public class BoatCrossingTask extends Task {

    private final BlockPos _initialDestXZ;
    private final boolean _rethrowEyes;
    // (2026-08-26) 目标物品位置 (含 y): 非 null = 目标是"水里/水面的掉落物
    // (如末影之眼)" — 船开向物品 XZ (而非远岸), 靠近后下船并完成, 交还父任务
    // 做下潜捡取 (深水物品船驶过吸不到)。null = 普通跨海到远岸。
    private final Vec3d _grabItemPos;
    // 船距目标物品多近就下船 (格)。
    private static final double GRAB_DISTANCE = 10;
    // 当前目标 (rev3 丢眼重定位后会更新); 初始 = 原始目标。
    private BlockPos _currentDestXZ = null;
    private static final int ARRIVE_DISTANCE = 12;
    private static final int DRIVE_STALL_SECONDS = 6;
    private static final double BREAK_ATTACK_INTERVAL = 0.3;
    private static final float THROW_PITCH = -35f;
    // 丢眼重定位周期 (秒)。海上每隔这么久丢一只眼确认方向。
    private static final double RETHROW_INTERVAL_SECONDS = 20;
    // 造船超时 (秒): 超过就放弃造船改游泳, 绝不卡在原地 (用户实测: 造船走
    // 工作台 3x3 流程 + 光标卡顿, 慢吞吞像"卡住不走向海边")。
    private static final double BOAT_CRAFT_TIMEOUT_SECONDS = 20;
    // 判定"大海"的最小水面宽度 (格) (2026-08-26 用户指定 ≥30, 小水塘不算)。
    private static final int MIN_CROSSING_WIDTH = 30;

    // 状态
    private static final int S_ENSURE_BOAT = 0;
    private static final int S_GO_SHORE = 1;
    private static final int S_PLACE_BOAT = 2;
    private static final int S_MOUNT = 3;
    private static final int S_DRIVE = 4;
    private static final int S_BREAK = 5;
    private static final int S_DONE = 6;
    private static final int S_SWIM = 7; // 反复失败兜底: 直接游泳
    private static final int S_WADE = 8; // 岸边放船失败 → 游进水里再放船 (用户设计)
    private static final int S_GRAB = 9; // 物品模式: 船到物品旁 → 下船 (2026-08-26)
    private int _state = S_ENSURE_BOAT;

    private Entity _boat = null;
    private double _bestDist = Double.MAX_VALUE;
    private final TimerGame _stallTimer = new TimerGame(DRIVE_STALL_SECONDS);
    private final TimerGame _attackTimer = new TimerGame(BREAK_ATTACK_INTERVAL);
    // 转向诊断日志节流: 每 1 秒打一条, 排查"船不转向" (2026-08-26 用户要求)。
    private final TimerGame _steerLogTimer = new TimerGame(1);
    // 转向微点击 (2026-08-26 用户: 船转向惯性大, 一直按到底会过冲, 要间隔性
    // 微点击微调方向; 09:29 日志实锤 diff 从 -19 冲到 +37 来回摆):
    // - 频率再降一倍: 按 2 tick / 停 16 tick (原 2/8, 用户 12:30 实测惯性仍太大)。
    // - 过冲沉降冷却: diff 符号翻转 (船冲过目标) 后停桨 STEER_SETTLE_TICKS
    //   tick 让惯性沉降, 再恢复微点击, 打破振荡。
    private static final int STEER_TAP_ON_TICKS = 2;
    private static final int STEER_TAP_OFF_TICKS = 16;
    private static final int STEER_SETTLE_TICKS = 15;
    private boolean _steerTapOn = false;
    private int _steerTapTicks = 0;
    private int _steerSettleTicks = 0;
    private boolean _steerHasPrevDiff = false;
    private boolean _steerPrevDiffPositive = false;
    // 放船右键点击间隔: 1 秒 (人类式点一下, 等船出现, 再点下一格距离)。
    private final TimerGame _placeTimer = new TimerGame(1);
    // 放船后等船实体出现的超时: 6 秒没出现 → 回岸边重试 (防静默卡死)。
    private final TimerGame _placeStallTimer = new TimerGame(6);
    // 放船瞄准距离 (格): 从近往远扫掠, 视角自然上抬 (不超过水平) 直到船放下
    // (2026-08-26 用户指定: 瞄准的水块太近放不下)。
    private int _placeAimDistance = 4;
    // 当前瞄准距离的点击次数: 点满 2 下船还没出现 → 换更远距离 (快速扫掠)。
    private int _placeAttempts = 0;
    // 造船超时: 20 秒没造出船 → 放弃改游泳 (防卡在原地)。
    private final TimerGame _ensureBoatTimer = new TimerGame(BOAT_CRAFT_TIMEOUT_SECONDS);
    // GO_SHORE 的持久行走子任务 (2026-08-25 修复 churn): 同一目标反复返回
    // 直到走完再复查, 绝不每 tick 新建 GetToXZ (否则 TaskPingPong 疯狂重启,
    // bot 在水边游不上岸, 日志: 'Getting to (87,636)' <-> 'Getting to (88,636)' 6x/5s)。
    private Task _goShoreWalk = null;
    // 远岸目标 (rev3): 越过当前水域的第一块陆地。找不到则用初始目标。
    private BlockPos _farShoreTarget = null;
    // 上船位置: 用于"已驶离起航岸"判定, 防止把起航岸当远岸。
    private Vec3d _launchPos = null;
    // 驾驶中丢眼重定位 (rev3)
    private final TimerGame _rethrowTimer = new TimerGame(RETHROW_INTERVAL_SECONDS);
    private boolean _throwPending = false;
    private Entity _eyeEntity = null;
    private Vec3d _eyeStart = null;
    private Vec3d _eyeEnd = null;

    public BoatCrossingTask(BlockPos destXZ) {
        this(destXZ, false, null);
    }

    public BoatCrossingTask(BlockPos destXZ, boolean rethrowEyes) {
        this(destXZ, rethrowEyes, null);
    }

    /**
     * @param grabItemPos 目标物品位置 (含 y)。非 null 表示目标是"水里/水面的
     *                    掉落物 (如末影之眼)": 船开向物品 XZ (而非远岸), 靠近后
     *                    下船并完成, 交还父任务做下潜捡取 — 深水物品船驶过吸不到。
     */
    public BoatCrossingTask(BlockPos destXZ, boolean rethrowEyes, Vec3d grabItemPos) {
        _initialDestXZ = new BlockPos(destXZ.getX(), 0, destXZ.getZ());
        _currentDestXZ = _initialDestXZ;
        _rethrowEyes = rethrowEyes;
        _grabItemPos = grabItemPos;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _boat = null;
        _bestDist = Double.MAX_VALUE;
        _stallTimer.reset();
        _attackTimer.reset();
        _placeTimer.reset();
        _placeStallTimer.reset();
        _placeAimDistance = 4;
        _placeAttempts = 0;
        _ensureBoatTimer.reset();
        _goShoreWalk = null;
        _farShoreTarget = null;
        _currentDestXZ = _initialDestXZ;
        _launchPos = null;
        _rethrowTimer.reset();
        _throwPending = false;
        _eyeEntity = null;
        _eyeStart = null;
        _eyeEnd = null;
        if (mod.getPlayer().getVehicle() instanceof BoatEntity) {
            // 已在船上 (中途续驾/重定位): 直接驾驶, 不造船不找岸边。
            _state = S_DRIVE;
            _launchPos = mod.getPlayer().getPos();
            _bestDist = Double.MAX_VALUE;
            _stallTimer.reset();
            // 物品模式目标就是物品本身, 不需要算远岸 (驾驶目标直接用物品 XZ)。
            if (_grabItemPos == null) {
                computeFarShore(mod);
            }
        } else {
            _state = S_ENSURE_BOAT;
        }
        mod.getInputControls().release(Input.MOVE_FORWARD);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_state) {
            case S_ENSURE_BOAT: return tickEnsureBoat(mod);
            case S_GO_SHORE: return tickGoShore(mod);
            case S_PLACE_BOAT: return tickPlaceBoat(mod);
            case S_MOUNT: return tickMount(mod);
            case S_DRIVE: return tickDrive(mod);
            case S_BREAK: return tickBreak(mod);
            case S_SWIM: return tickSwim(mod);
            case S_WADE: return tickWade(mod);
            case S_GRAB: return tickGrab(mod);
            default: return null;
        }
    }

    private Task tickEnsureBoat(AltoClef mod) {
        if (hasBoatAvailable(mod)) {
            _state = S_GO_SHORE;
            return null;
        }
        // 造船超时 (20 秒) 还没造出船 → 放弃造船直接游泳, 绝不在原地耗着。
        if (_ensureBoatTimer.elapsed()) {
            Debug.logWarning("Boat crafting timed out, swimming across instead.");
            _goShoreWalk = null;
            _state = S_SWIM;
            return null;
        }
        setDebugState("Crafting a boat (5 planks).");
        return new CollectBoatTask(1);
    }

    private Task tickGoShore(AltoClef mod) {
        // 持久走: 同一行走目标反复返回直到走完再复查 (防每 tick 新建 GetToXZ churn)。
        if (_goShoreWalk != null && !_goShoreWalk.isFinished(mod)) {
            setDebugState("Walking toward the sea to place boat.");
            return _goShoreWalk;
        }
        _goShoreWalk = null;
        // 已到大海边缘 (前方 ≥30 格连续水面) → 放船。
        if (isAtLargeWater(mod)) {
            _placeStallTimer.reset();
            _state = S_PLACE_BOAT;
            return null;
        }
        // 还没到大水: 朝目标方向走一小段 (5 格, 持久, 走完复查)。
        // (2026-08-26 用户指定: 每次走 5 格, 大步 24 格容易越过水边/越界,
        // 小步到水边才放船, 复查更频繁更稳。)
        Vec3d dir = dirToDest(mod);
        _goShoreWalk = new GetToXZTask(
                (int) (mod.getPlayer().getX() + dir.x * 5),
                (int) (mod.getPlayer().getZ() + dir.z * 5));
        setDebugState("Walking toward the sea to place boat.");
        return _goShoreWalk;
    }

    /**
     * 是否已到大海边缘: 目标方向前方 4 格起连续 ≥MIN_CROSSING_WIDTH 格水面。
     * (2026-08-26 用户实测: 小水塘只有 7~8 格, 不算大海, 走到它边上一律跳过,
     * 只有真正的大水面才在这里放船。)
     */
    private boolean isAtLargeWater(AltoClef mod) {
        Vec3d dir = dirToDest(mod);
        BlockPos start = mod.getPlayer().getBlockPos();
        int run = 0;
        for (int s = 4; s <= 60; s += 4) {
            BlockPos p = new BlockPos((int) (start.getX() + dir.x * s), 0, (int) (start.getZ() + dir.z * s));
            if (WorldHelper.isSeaLevelWater(mod, p)) {
                run += 4;
            } else {
                break; // 碰到陆地停止
            }
        }
        return run >= MIN_CROSSING_WIDTH;
    }

    private Task tickPlaceBoat(AltoClef mod) {
        // 先检测附近已有船 (刚放下的) → 上船。必须放在 hasItem 之前:
        // 放船成功后背包里的船被消耗, 若先判 hasItem 会误以为没船去造船
        // (2026-08-26 用户实测: 放了一艘船马上又去做船)。
        Optional<Entity> nearby = findNearbyBoat(mod);
        if (nearby.isPresent()) {
            _boat = nearby.get();
            _placeStallTimer.reset();
            _placeAimDistance = 4;
            _placeAttempts = 0;
            _state = S_MOUNT;
            return null;
        }
        // 船没了 → 回去重新造。
        if (!hasBoatAvailable(mod)) {
            _state = S_ENSURE_BOAT;
            return null;
        }
        // 离水远了 (水不在 3 格交互范围内) → 回岸边状态, 走到水边再放。
        if (findWaterTowardDest(mod, 3) == null) {
            _placeStallTimer.reset();
            _state = S_GO_SHORE;
            return null;
        }
        if (!mod.getSlotHandler().forceEquipItem(ItemHelper.WOOD_BOAT)) {
            setDebugState("Waiting to equip boat.");
            // 装备船一直失败 (有船但换不出) → 兜底游泳, 防静默卡死。
            if (_placeStallTimer.elapsed()) {
                Debug.logWarning("Could not equip boat, swimming across instead.");
                _placeStallTimer.reset();
                _goShoreWalk = null;
                _state = S_SWIM;
                return null;
            }
            return null;
        }
        // 目视前方水面, 人类式右键放船 (2026-08-26 用户指定: 瞄准方向右键即可
        // 放置, 直接调 interactBlock 不可靠, 改用标准右键 tryPress)。从近到远
        // 扫掠瞄准, 每距离点 2 下船没出现就换更远 (快速, 不等 6 秒)。
        BlockPos water = findPlacementWaterAhead(mod, _placeAimDistance);
        if (water == null) {
            // 该距离没水面 → 回岸边再走。
            _placeAimDistance = 4;
            _placeAttempts = 0;
            _placeStallTimer.reset();
            _state = S_GO_SHORE;
            return null;
        }
        Vec3d waterCenter = new Vec3d(water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
        LookHelper.lookAt(mod, waterCenter);
        if (_placeTimer.elapsed()) {
            mod.getInputControls().tryPress(Input.CLICK_RIGHT); // 人类式右键
            _placeTimer.reset();
            _placeAttempts++;
            if (_placeAttempts >= 2) {
                // 该距离点满 2 下船还没出现 → 换更远距离 (快速扫掠)。
                _placeAttempts = 0;
                _placeAimDistance++;
                Debug.logMessage("Shore boat aim swept to distance " + _placeAimDistance);
                if (_placeAimDistance > 8) {
                    Debug.logWarning("Shore boat placement failed, wading into water to retry.");
                    _placeAimDistance = 4;
                    _goShoreWalk = null;
                    _state = S_WADE;
                    return null;
                }
            }
        }
        setDebugState("Placing boat (aim " + _placeAimDistance + ", click " + _placeAttempts + ").");
        return null;
    }

    private Task tickMount(AltoClef mod) {
        if (mod.getPlayer().getVehicle() instanceof BoatEntity) {
            // 已骑上 → 记录起航位置, 算远岸, 开始驾驶。
            _launchPos = mod.getPlayer().getPos();
            _bestDist = Double.MAX_VALUE;
            _stallTimer.reset();
            _rethrowTimer.reset();
            computeFarShore(mod);
            _state = S_DRIVE;
            return null;
        }
        if (_boat == null || !_boat.isAlive()) {
            Optional<Entity> boat = findNearbyBoat(mod);
            if (boat.isEmpty()) {
                _state = S_PLACE_BOAT; // 船没了 → 重新放
                return null;
            }
            _boat = boat.get();
        }
        if (mod.getPlayer().distanceTo(_boat) > 3) {
            setDebugState("Getting close to boat to mount.");
            return new GetToEntityTask(_boat);
        }
        // 面向船, 右键上船。
        LookHelper.lookAt(mod, _boat.getPos());
        mod.getController().interactEntity(mod.getPlayer(), _boat, Hand.MAIN_HAND);
        setDebugState("Mounting boat.");
        return null;
    }

    private Task tickDrive(AltoClef mod) {
        if (!(mod.getPlayer().getVehicle() instanceof BoatEntity)) {
            // 掉下船/船消失 → 重新上船。
            mod.getInputControls().release(Input.MOVE_FORWARD);
            _state = S_MOUNT;
            return null;
        }
        // (rev3) 驾驶中定期丢眼重定位 (海太大时在海上重新确认方向)。
        if (_rethrowEyes) {
            tickEyeRethrow(mod);
        }
        // 面向目标, 用 A/D 划桨转向 (2026-08-26 用户实测: 只转相机不转船,
        // MC 1.18 船不会自动朝相机转, 需左右移动键划桨才能转航向)。
        // (2026-08-26 物品模式) 目标是掉落物 (grabItemPos!=null) → 直接开向物品
        // XZ, 不去远岸; 普通跨海仍开向远岸。
        BlockPos target = _grabItemPos != null
                ? _currentDestXZ
                : (_farShoreTarget != null ? _farShoreTarget : _currentDestXZ);
        Vec3d dest = new Vec3d(target.getX() + 0.5, mod.getPlayer().getY(), target.getZ() + 0.5);
        LookHelper.lookAt(mod, dest);
        steerBoat(mod, target);
        double dist = horizontalDist(mod, target);
        if (dist < _bestDist - 3) {
            _bestDist = dist;
            _stallTimer.reset();
        }
        // (2026-08-26 物品模式) 接近目标物品 → 下船, 交还父任务做下潜捡取
        // (深水物品船驶过吸不到, 必须下船)。先于远岸判定。
        if (_grabItemPos != null && dist <= GRAB_DISTANCE) {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
            _state = S_GRAB;
            return null;
        }
        // 到达远岸 (接近目标 / 附近是陆地且已驶离起航岸) → 下船。
        if (dist <= ARRIVE_DISTANCE || reachedFarShore(mod)) {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
            _attackTimer.reset();
            _state = S_BREAK;
            return null;
        }
        // 失速: 海中央 (附近无陆地) 不下船 — 转不了向/被浪卡住时重置继续开,
        // 只有岸边/近陆才下船 (2026-08-26 用户: 中途老下船丢眼, 海中央下船
        // 就困水里)。
        if (_stallTimer.elapsed()) {
            if (findNearbyLand(mod, 24) == null) {
                Debug.logMessage("Boat stalled mid-ocean, keep driving (no nearby land).");
                _stallTimer.reset();
                _bestDist = Double.MAX_VALUE;
                return null;
            }
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
            _attackTimer.reset();
            _state = S_BREAK;
            return null;
        }
        setDebugState("Driving boat toward (" + target.getX() + ", " + target.getZ() + "), dist=" + (int) dist);
        return null;
    }

    /** 驾驶中丢眼重定位 (rev3): 眼睛在飞 → 跟踪; 飞完 → 更新目标方向; 定期丢眼。 */
    private void tickEyeRethrow(AltoClef mod) {
        // 飞行中的眼睛 → 跟踪并更新终点。
        List<EyeOfEnderEntity> eyes = mod.getEntityTracker().getTrackedEntities(EyeOfEnderEntity.class);
        if (!eyes.isEmpty()) {
            _eyeEntity = eyes.get(0);
            _eyeEnd = _eyeEntity.getPos();
            LookHelper.lookAt(mod, _eyeEnd);
            setDebugState("Boat tracking eye for re-locate.");
            return;
        }
        // 眼睛飞完 → 用它的方向更新目标 (船驶过落水的眼睛自动拾取)。
        if (_eyeEntity != null && _eyeEnd != null && _eyeStart != null) {
            Vec3d delta = _eyeEnd.subtract(_eyeStart);
            double h = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            if (h > 3) {
                Vec3d dir = new Vec3d(delta.x / h, 0, delta.z / h);
                Vec3d t = _eyeEnd.add(dir.multiply(240));
                _currentDestXZ = new BlockPos((int) t.x, 0, (int) t.z);
                _bestDist = Double.MAX_VALUE;
                _stallTimer.reset();
                computeFarShore(mod);
                Debug.logMessage("Boat re-located: eye flew " + (int) h + " blocks, new target (" + (int) t.x + ", " + (int) t.z + ")");
            }
            _eyeEntity = null;
            _eyeStart = null;
            _eyeEnd = null;
            return;
        }
        // 定期丢眼 (20 秒), 丢出后待相机到位再投掷。
        if (_rethrowTimer.elapsed() && mod.getItemStorage().hasItem(Items.ENDER_EYE)) {
            _rethrowTimer.reset();
            _throwPending = true;
        }
        if (_throwPending) {
            if (mod.getSlotHandler().forceEquipItem(Items.ENDER_EYE)) {
                Rotation throwRotation = new Rotation(LookHelper.getLookRotation().getYaw(), THROW_PITCH);
                LookHelper.lookAt(mod, throwRotation);
                if (LookHelper.isLookingAt(mod, throwRotation) && LookHelper.tryAvoidingInteractable(mod)) {
                    MinecraftClient.getInstance().interactionManager.interactItem(mod.getPlayer(), mod.getWorld(), Hand.MAIN_HAND);
                    _eyeStart = mod.getPlayer().getPos().add(0, 1.6, 0);
                    _eyeEnd = null;
                    _throwPending = false;
                    Debug.logMessage("Boat throwing eye to re-locate.");
                }
            }
        }
    }

    private Task tickBreak(AltoClef mod) {
        // 下船: 按潜行。
        if (mod.getPlayer().getVehicle() instanceof BoatEntity) {
            mod.getInputControls().tryPress(Input.SNEAK);
            return null;
        }
        // 打船 (优先剑/斧, 用户指定更快)。
        if (_boat != null && _boat.isAlive()) {
            equipWeaponIfAny(mod);
            if (mod.getPlayer().distanceTo(_boat) > 3) {
                return new GetToEntityTask(_boat);
            }
            LookHelper.lookAt(mod, _boat.getPos());
            if (_attackTimer.elapsed()) {
                mod.getControllerExtras().attack(_boat);
                _attackTimer.reset();
            }
            setDebugState("Breaking boat to recover it.");
            return null;
        }
        // 船已打掉 → 捡回掉落的船物品 (浮在水面可能没被自动吸收, 丢了的话
        // 下个船渡任务 hasItem=false 又要重新造船 → 用户实测"打掉后切换去做船")。
        ItemEntity boatItem = findNearbyBoatItem(mod, 8);
        if (boatItem != null) {
            setDebugState("Picking up broken boat item.");
            // (2026-08-26 修复) 之前用 GetToEntityTask: ①满背包不会丢东西 → 一直
            // 捡不上 (14:27 日志: "Swimming directly to target" 26s 后放弃游回岸,
            // 船物品丢在海里); ②水面船物品目标 y 取 bot 当前 y, 停在物品上方
            // 1 格够不着 (用户实测)。改用 PickupDroppedItemTask: 满背包自动
            // EnsureFreeInventorySlotTask 腾位 + 水面物品贴近后用 DiveToGrabItemTask
            // 同高度抓取。
            return new PickupDroppedItemTask(boatItem.getStack().getItem(), 1);
        }
        // 上岸收尾。
        if (mod.getPlayer().isTouchingWater()) {
            BlockPos land = findNearbyLand(mod, 16);
            if (land != null) {
                setDebugState("Swimming to shore after breaking boat.");
                return new GetToXZTask(land.getX(), land.getZ());
            }
        }
        _state = S_DONE;
        return null;
    }

    /**
     * (2026-08-26 物品模式) 船已开到目标物品旁: 下船。下船后直接完成 (S_DONE),
     * 交还父任务 (PickupDroppedItemTask) 做"下潜到物品 y 捡取" — 这里只负责把
     * bot 从船上弄下来, 不重复实现下潜。物品若已被船驶过自动吸走, 同样直接完成。
     */
    private Task tickGrab(AltoClef mod) {
        // 还在船上 → 潜行下船。
        if (mod.getPlayer().getVehicle() instanceof BoatEntity) {
            mod.getInputControls().tryPress(Input.SNEAK);
            setDebugState("Dismounting to grab dropped item.");
            return null;
        }
        // 已下船 → 完成, 交还父任务继续 (下潜捡取由父任务的 GetToBlockTask 做)。
        _state = S_DONE;
        return null;
    }

    /**
     * 造船/装备/放船反复失败的兜底 (rev4): 直接朝目标游泳过去 (持久 GetToXZ,
     * 水中直游已在 CustomBaritoneGoalTask 实现)。游到目标附近就完成。
     */
    private Task tickSwim(AltoClef mod) {
        if (_goShoreWalk != null && !_goShoreWalk.isFinished(mod)) {
            setDebugState("Swimming across water toward (" + _currentDestXZ.getX() + ", " + _currentDestXZ.getZ() + ").");
            return _goShoreWalk;
        }
        _goShoreWalk = null;
        if (horizontalDist(mod, _currentDestXZ) <= ARRIVE_DISTANCE) {
            _state = S_DONE;
            return null;
        }
        _goShoreWalk = new GetToXZTask(_currentDestXZ.getX(), _currentDestXZ.getZ());
        setDebugState("Swimming across water.");
        return _goShoreWalk;
    }

    /**
     * 岸边放船反复失败的兜底 (rev4, 用户设计): 游进水里一点, 直接在水里放船
     * (水里右键水面也能放出船), 而不是游完全程。水里也放不出 → S_SWIM。
     */
    private Task tickWade(AltoClef mod) {
        // 还没下水 → 朝目标方向游进水里 (持久 GetToXZ)。
        if (!mod.getPlayer().isTouchingWater()) {
            if (_goShoreWalk != null && !_goShoreWalk.isFinished(mod)) {
                setDebugState("Wading into water to place boat.");
                return _goShoreWalk;
            }
            Vec3d dir = dirToDest(mod);
            _goShoreWalk = new GetToXZTask(
                    (int) (mod.getPlayer().getX() + dir.x * 6),
                    (int) (mod.getPlayer().getZ() + dir.z * 6));
            setDebugState("Wading into water to place boat.");
            return _goShoreWalk;
        }
        _goShoreWalk = null;
        _placeStallTimer.reset(); // 开始水里放船计时 (防涉水途中已耗尽)
        // (2026-08-26 用户建议) 已在水里放船阶段: 一直长按空格 (游泳姿态,
        // 提高放船成功率), 各退出路径都要释放。
        mod.getInputControls().hold(Input.JUMP);
        // 先检测附近已有船 (刚放下的) → 上船。必须放在 hasItem 之前:
        // 放船成功后背包里的船被消耗, 若先判 hasItem 会误以为没船去造船
        // (2026-08-26 用户实测: 放了一艘船马上又去做船)。
        Optional<Entity> nearby = findNearbyBoat(mod);
        if (nearby.isPresent()) {
            mod.getInputControls().release(Input.JUMP);
            _boat = nearby.get();
            _placeStallTimer.reset();
            _placeAimDistance = 4;
            _placeAttempts = 0;
            _state = S_MOUNT;
            return null;
        }
        // 船没了 → 回去重新造。
        if (!hasBoatAvailable(mod)) {
            mod.getInputControls().release(Input.JUMP);
            _state = S_ENSURE_BOAT;
            return null;
        }
        if (!mod.getSlotHandler().forceEquipItem(ItemHelper.WOOD_BOAT)) {
            setDebugState("Waiting to equip boat.");
            return null;
        }
        // 目视前方, 人类式右键放船 (2026-08-26 用户指定: 瞄准方向右键即可放置,
        // 直接调 interactBlock 不可靠, 改用标准右键 tryPress)。从近到远扫掠
        // 瞄准 (每距离点 2 下没出现就换更远, 快速不等 6 秒)。
        BlockPos water = findPlacementWaterAhead(mod, _placeAimDistance);
        if (water == null) {
            // 该距离没水面 → 再往前游一点。
            Vec3d dir = dirToDest(mod);
            mod.getInputControls().release(Input.JUMP);
            _placeAimDistance = 4;
            _placeAttempts = 0;
            _goShoreWalk = new GetToXZTask(
                    (int) (mod.getPlayer().getX() + dir.x * 4),
                    (int) (mod.getPlayer().getZ() + dir.z * 4));
            setDebugState("Swimming forward to place boat.");
            return _goShoreWalk;
        }
        Vec3d waterCenter = new Vec3d(water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
        LookHelper.lookAt(mod, waterCenter);
        if (_placeTimer.elapsed()) {
            mod.getInputControls().tryPress(Input.CLICK_RIGHT); // 人类式右键
            _placeTimer.reset();
            _placeAttempts++;
            if (_placeAttempts >= 2) {
                // 该距离点满 2 下船还没出现 → 换更远距离 (快速扫掠)。
                _placeAttempts = 0;
                _placeAimDistance++;
                Debug.logMessage("Boat aim swept to distance " + _placeAimDistance);
                if (_placeAimDistance > 8) {
                    mod.getInputControls().release(Input.JUMP);
                    Debug.logWarning("Boat placement from water failed, swimming across instead.");
                    _placeAimDistance = 4;
                    _state = S_SWIM;
                    return null;
                }
            }
        }
        setDebugState("Placing boat from water (aim " + _placeAimDistance + ", click " + _placeAttempts + ").");
        return null;
    }

    /**
     * 用 A/D 划桨控制船转向目标方向 (2026-08-26 用户实测: 只转相机不转船,
     * MC 1.18 船不会自动朝相机转, 需左右移动键划桨才能转航向; 按住前进)。
     * 根据船当前航向 (船实体 yaw) 与目标方向的夹角, 决定按左/右划桨。
     */
    private void steerBoat(AltoClef mod, BlockPos target) {
        Entity vehicle = mod.getPlayer().getVehicle();
        if (!(vehicle instanceof BoatEntity)) {
            Debug.logMessage("Steer: no boat vehicle!");
            return;
        }
        Vec3d p = mod.getPlayer().getPos();
        double dx = (target.getX() + 0.5) - p.x;
        double dz = (target.getZ() + 0.5) - p.z;
        // MC yaw: 0=南(+Z), 90=西(-X), ±180=北, -90=东; 方向向量 → yaw。
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        // 船当前航向: 优先用【速度方向】(实际移动方向, 比 getYaw 可靠),
        // 静止时才用船实体 yaw。
        Vec3d vel = vehicle.getVelocity();
        double headingYaw;
        if (vel.x * vel.x + vel.z * vel.z > 0.01) {
            headingYaw = Math.toDegrees(Math.atan2(-vel.x, vel.z));
        } else {
            headingYaw = vehicle.getYaw();
        }
        double diff = ((targetYaw - headingYaw) % 360.0 + 540.0) % 360.0 - 180.0;
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        // 过冲沉降: diff 符号翻转 (船冲过目标) → 停桨让惯性沉降, 打破振荡
        // (09:29 日志实锤 diff -19 → +37 来回摆)。
        boolean diffPositive = diff > 0;
        if (_steerHasPrevDiff && diffPositive != _steerPrevDiffPositive && Math.abs(diff) > 12) {
            _steerSettleTicks = STEER_SETTLE_TICKS;
            _steerTapOn = false;
            _steerTapTicks = 0;
        }
        _steerHasPrevDiff = true;
        _steerPrevDiffPositive = diffPositive;
        // 期望的划桨方向: 1=右(D), -1=左(A), 0=直行。
        int wantPaddle = diff > 8 ? 1 : (diff < -8 ? -1 : 0);
        boolean left = false, right = false;
        if (_steerSettleTicks > 0) {
            // 沉降冷却中: 停桨, 只前进。
            _steerSettleTicks--;
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
        } else if (wantPaddle != 0) {
            // 间隔性微点击: 按 2 tick / 停 8 tick (频率减半, 惯性大防过冲)。
            _steerTapTicks++;
            if (!_steerTapOn && _steerTapTicks >= STEER_TAP_OFF_TICKS) {
                _steerTapOn = true;
                _steerTapTicks = 0;
            }
            if (_steerTapOn) {
                if (wantPaddle == 1) {
                    mod.getInputControls().hold(Input.MOVE_RIGHT);
                    mod.getInputControls().release(Input.MOVE_LEFT);
                    right = true;
                } else {
                    mod.getInputControls().hold(Input.MOVE_LEFT);
                    mod.getInputControls().release(Input.MOVE_RIGHT);
                    left = true;
                }
                if (_steerTapTicks >= STEER_TAP_ON_TICKS) {
                    _steerTapOn = false;
                    _steerTapTicks = 0;
                }
            } else {
                mod.getInputControls().release(Input.MOVE_LEFT);
                mod.getInputControls().release(Input.MOVE_RIGHT);
            }
        } else {
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
            _steerTapOn = false;
            _steerTapTicks = 0;
        }
        // 转向诊断日志 (每 1 秒一条): 排查"船不转向"。
        if (_steerLogTimer.elapsed()) {
            Debug.logMessage("Steer: boatYaw=" + (int) vehicle.getYaw()
                    + " heading=" + (int) headingYaw
                    + " targetYaw=" + (int) targetYaw
                    + " diff=" + (int) diff
                    + " left=" + left + " right=" + right
                    + " velLen=" + String.format("%.2f", Math.sqrt(vel.x * vel.x + vel.z * vel.z)));
        }
        setDebugState("Driving boat toward (" + target.getX() + ", " + target.getZ() + "), yaw-diff=" + (int) diff);
    }

    /**
     * 是否"有船可用": 正在骑船 / 背包有船 / 附近有船实体 (船没打掉, 还能上)。
     * (2026-08-26 用户实测: 骑在船上但 hasItem=false (船在背包外) → 以为没船
     * 在海面上造船 → 卡死; 船没破时附近船实体也算有船, 不造船。)
     */
    private boolean hasBoatAvailable(AltoClef mod) {
        if (mod.getPlayer() != null && mod.getPlayer().getVehicle() instanceof BoatEntity) {
            return true; // 骑船中
        }
        if (mod.getItemStorage().hasItem(ItemHelper.WOOD_BOAT)) {
            return true; // 背包有船
        }
        return findNearbyBoat(mod) != null; // 附近有船实体 (没破, 还能上)
    }

    /** 计算远岸目标: 从当前(船)位置沿目标方向扫描第一块越过水域的陆地。 */
    private void computeFarShore(AltoClef mod) {
        Vec3d dir = dirToDest(mod);
        BlockPos far = WorldHelper.findFarShore(mod, mod.getPlayer().getBlockPos(), dir, 400, MIN_CROSSING_WIDTH);
        if (far != null) {
            _farShoreTarget = far;
            Debug.logMessage("Boat far-shore target at (" + far.getX() + ", " + far.getZ() + ")");
        } else {
            _farShoreTarget = null; // 找不到岸 → 用初始目标
        }
    }

    /** 是否已到达远岸: 驶离起航岸 > 40 格 且 附近 (6 格) 有陆地。 */
    private boolean reachedFarShore(AltoClef mod) {
        if (_launchPos == null) return false;
        Vec3d p = mod.getPlayer().getPos();
        double traveled = Math.sqrt((p.x - _launchPos.x) * (p.x - _launchPos.x) + (p.z - _launchPos.z) * (p.z - _launchPos.z));
        if (traveled < 40) return false; // 还没离开起航岸
        return findNearbyLand(mod, 6) != null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _state == S_DONE;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.MOVE_LEFT);
        mod.getInputControls().release(Input.MOVE_RIGHT);
        mod.getInputControls().release(Input.JUMP);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BoatCrossingTask && ((BoatCrossingTask) other)._initialDestXZ.equals(_initialDestXZ);
    }

    @Override
    protected String toDebugString() {
        return "Crossing water by boat to (" + _initialDestXZ.getX() + ", " + _initialDestXZ.getZ() + ")";
    }

    /** 目标方向 (水平单位向量)。 */
    private Vec3d dirToDest(AltoClef mod) {
        BlockPos ref = _farShoreTarget != null ? _farShoreTarget : _currentDestXZ;
        Vec3d p = mod.getPlayer().getPos();
        Vec3d d = new Vec3d(ref.getX() + 0.5 - p.x, 0, ref.getZ() + 0.5 - p.z);
        double len = d.length();
        return len > 0.001 ? d.multiply(1.0 / len) : new Vec3d(1, 0, 0);
    }

    private double horizontalDist(AltoClef mod, BlockPos target) {
        Vec3d p = mod.getPlayer().getPos();
        double dx = (target.getX() + 0.5) - p.x;
        double dz = (target.getZ() + 0.5) - p.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 在目标方向 ±range 内找最近的水面块 (用于放船)。
     * (2026-08-26 用户实测: 之前对准了非水面方块) 每列从海平面附近往下找
     * 【最上层水】(水面), 返回水面块而不是脚下的深层水/普通方块。
     */
    private BlockPos findWaterTowardDest(AltoClef mod, int range) {
        BlockPos p = mod.getPlayer().getBlockPos();
        Vec3d dir = dirToDest(mod);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                double dist2 = dx * dx + dz * dz;
                if (dist2 > range * range) continue;
                double dot = dx * dir.x + dz * dir.z;
                if (dot <= 0) continue;
                // 从玩家高度往上 5 格往下找: 该列最上层水 (水面)。
                for (int dy = 5; dy >= -3; dy--) {
                    BlockPos c = new BlockPos(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    if (!mod.getChunkTracker().isChunkLoaded(c)) break;
                    Block b = mod.getWorld().getBlockState(c).getBlock();
                    if (b == Blocks.WATER) {
                        double score = dist2 - dot * 2;
                        if (score < bestScore) {
                            bestScore = score;
                            best = c;
                        }
                        break;
                    }
                    if (b != Blocks.AIR) break; // 非空气 → 陆地, 此列无表面水
                }
            }
        }
        return best;
    }

    /**
     * 找附近刚放下的船 (直接扫世界实体, 不依赖 EntityTracker 感知缓存 —
     * 2026-08-26 用户实测: 放完船后 getClosestEntity 读的是未刷新缓存, 检测
     * 不到刚放的船 → 误以为没船去造船 → 兜底游泳往前游)。船是 bot 自己刚放
     * 的, 就在眼前, 10 格内直接感知。
     */
    private Optional<Entity> findNearbyBoat(AltoClef mod) {
        Vec3d p = mod.getPlayer().getPos();
        for (Entity e : mod.getWorld().getEntities()) {
            if (e instanceof BoatEntity && e.isAlive()
                    && e.squaredDistanceTo(p) < 10.0 * 10.0) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /**
     * 找附近掉落的船物品 (破船后回收用, 直接扫世界实体; 浮在水面可能没被
     * 自动吸收, 不捡的话下个船渡任务又要重新造船)。
     */
    private ItemEntity findNearbyBoatItem(AltoClef mod, int range) {
        Vec3d p = mod.getPlayer().getPos();
        double r2 = range * range;
        for (Entity e : mod.getWorld().getEntities()) {
            if (e instanceof ItemEntity ie && ie.isAlive()) {
                Item it = ie.getStack().getItem();
                boolean isBoat = false;
                for (Item b : ItemHelper.WOOD_BOAT) {
                    if (b == it) {
                        isBoat = true;
                        break;
                    }
                }
                if (isBoat && ie.squaredDistanceTo(p) < r2) {
                    return ie;
                }
            }
        }
        return null;
    }

    /**
     * 在目标方向前方 distance 格附近 (±1) 找水面块, 用于放船瞄准。
     * (2026-08-26 用户指定: 瞄准的水块太近放不下 → 从近到远扫掠, 视角自然
     * 上抬 (瞄准远处水面即趋近水平, 不高于水平) 直到船放下。)
     */
    private BlockPos findPlacementWaterAhead(AltoClef mod, int distance) {
        Vec3d dir = dirToDest(mod);
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int d = Math.max(2, distance - 1); d <= distance + 1; d++) {
            for (int dy = 5; dy >= -3; dy--) {
                BlockPos c = new BlockPos(
                        (int) (p.getX() + dir.x * d),
                        p.getY() + dy,
                        (int) (p.getZ() + dir.z * d));
                if (!mod.getChunkTracker().isChunkLoaded(c)) break;
                Block b = mod.getWorld().getBlockState(c).getBlock();
                if (b == Blocks.WATER) return c;
                if (b != Blocks.AIR) break; // 非空气 → 陆地, 此列无表面水
            }
        }
        return null;
    }

    /** 找附近可站的陆地块 (非水, 用于上岸/远岸判定)。 */
    private BlockPos findNearbyLand(AltoClef mod, int radius) {
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos c = new BlockPos(p.getX() + dx, p.getY(), p.getZ() + dz);
                    if (!mod.getChunkTracker().isChunkLoaded(c)) continue;
                    Block b = mod.getWorld().getBlockState(c).getBlock();
                    if (b == Blocks.WATER || b == Blocks.AIR) continue;
                    if (!WorldHelper.isSolid(mod, c.down()) && !WorldHelper.isSolid(mod, c)) continue;
                    return c;
                }
            }
        }
        return null;
    }

    /** 装备一把剑/斧 (打船更快)。没有就空手打。 */
    private void equipWeaponIfAny(AltoClef mod) {
        Item[] weapons = new Item[]{
                Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD,
                Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE
        };
        for (Item w : weapons) {
            if (mod.getItemStorage().hasItem(w)) {
                mod.getSlotHandler().forceEquipItem(w);
                return;
            }
        }
    }
}
