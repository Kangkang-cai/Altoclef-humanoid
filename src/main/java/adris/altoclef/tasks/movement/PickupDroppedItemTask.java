package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class PickupDroppedItemTask extends AbstractDoToClosestObjectTask<ItemEntity> implements ITaskRequiresGrounded {

    // Am starting to regret not making this a singleton
    private AltoClef _mod;

    private static final Task getPickaxeFirstTask = new SatisfyMiningRequirementTask(MiningRequirement.STONE, "pickup-dropped-item/reach-drop");
    // Not clean practice, but it helps keep things self contained I think.
    private static boolean isGettingPickaxeFirstFlag = false;
    private final ItemTarget[] _itemTargets;
    // (2026-09-19 15:45) 追掉落物的最大距离 (平方), 0 = 不限。默认不限
    // (稀有掉落不设上限); 垫脚补料等"遍地都是"的目标传入小半径, 防
    // 陈旧掉落 (区块卸载冻结 despawn 后"复活") 把 bot 拽去几十格外。
    private final double _maxPursuitRangeSq;
    private final Set<ItemEntity> _blacklist = new HashSet<>();
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(3);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(20);
    private final boolean _freeInventoryIfFull;
    private boolean _collectingPickaxeForThisResource = false;
    private ItemEntity _currentDrop = null;
    // (2026-08-31) 掉水里物品导航诊断节流时间戳。
    private long _lastPickupDiagMs = 0;
    // (2026-09-08) 导航目标方块诊断节流时间戳 ([PickupGoal])。
    private long _lastGoalDiagMs = 0;

    // (2026-08-26 下潜捡眼失败) 掉落物在动 (下落/漂移) 时, 直接用实时 pos 做导航
    // 目标 → 目标每 tick 变 → GetToBlockTask 每 tick 重启 → TaskPingPong 永远追不上
    // (12:06 日志: Getting to (858,y,1273) y 62→40→47 每 tick 变, 6x/5s 告警)。
    // ① 未落地未入水 (正在下落) → 先等落定再导航; ② 落地/入水后 → 缓存导航目标,
    // 只有位置变化超阈值或过了冷却才更新 (平滑追, 防每 tick 重启)。
    private ItemEntity _pickupNavItem = null;
    private Vec3d _pickupNavTarget = null;
    private final TimerGame _pickupNavTimer = new TimerGame(3);
    private int _pickupNavWaitTicks = 0;
    // (2026-09-22 水面粘滞) 掉落物一旦沾水就按水中物品处置 (永不进"等落地"分支):
    // 水面漂浮物 isTouchingWater 随浮沉逐 tick 翻面 —— 出水的瞬间 falling 判据
    // 翻真 → 等落地 → 入水翻假 → 发导航 → 永不落地的循环 (20260922 另机日志
    // 00:02-00:08 停滞 5 分钟窗口内 "Waiting for dropped item to land" 与
    // isTouchingWater=true 诊断并存)。isOnGround 对水中物品恒 false, 只有
    // isTouchingWater 是单 tick 瞬时值, 必须粘滞。
    private boolean _pickupItemSeenInWater = false;
    // 等待落定的上限 (tick): 超过就放弃等待改慢追 (防死等/被进度检查判困)。
    private static final int PICKUP_NAV_WAIT_MAX_TICKS = 200;
    // 导航目标更新阈值 (平方距离): 物品移动超过该距离才重建导航任务。
    private static final double PICKUP_NAV_UPDATE_DIST_SQ = 9;

    // (2026-09-26 水面漂浮物卡死·问题一, 用户拍板"4s 就拉黑") 停滞拉黑看门狗:
    // 追踪当前掉落物期间, 玩家净位移 <1 格持续 4s → 判定"导航不到/够不着" →
    // 拉黑 (任务级 _blacklist + tracker requestEntityUnreachable)。
    // 现场实锤 20260926-101726: 洞穴水塘漂浮粗铁 (9,7,224), 玩家 2 格外
    // baritone 穷举 8.9M movements 算不出路径, "At goal vicinity holding" +
    // FakePath 4s 重寻路死循环 105s+; Try 4/3、5/3 永不生效 —— 扫世界兜底
    // (findClosestDropByScan) 把被 tracker 拉黑的实体又捞回来 (本类同步修)。
    private static final double PICKUP_STALL_DISTANCE_SQ = 1 * 1;
    private Vec3d _stallAnchorPos = null;
    private final TimerGame _stallTimer = new TimerGame(4);
    // 封闭水体贴近直抓的诊断节流。
    private long _lastDiveDiagMs = 0;
    // (2026-09-30) 直抓被 bot 不在水中闸门拦下的诊断节流。
    private long _lastDiveSkipDiagMs = 0;

    // (2026-08-22) 做镐子切换的被困前置: 用户原则"感知目标 = 承诺目标,
    // 不论多远不设时限; 超时/上限仅限探索"。捡不到掉落物的常见原因是
    // 游泳慢/绕路/腾背包, 不是没镐子; 原逻辑一次 progress 失败(3x6s 静止)
    // 就切去做石镐(挖圆石要上岸), 造成"收集中途换任务"。
    // 唯一合法的放弃条件: 真被困(15 秒内位移 < 2 格)或掉落物消失(despawn)。
    private static final long STUCK_WINDOW_MS = 15000;
    private static final double STUCK_DISTANCE_SQ = 2 * 2;
    private Vec3d _stuckAnchorPos = null;
    private long _stuckAnchorMs = 0;

    /** 位置长时间不动 (15 秒内位移 < 2 格) 才算真被困。 */
    private boolean isStuckInPlace(AltoClef mod) {
        Vec3d pos = mod.getPlayer().getPos();
        long now = System.currentTimeMillis();
        if (_stuckAnchorPos == null || pos.squaredDistanceTo(_stuckAnchorPos) > STUCK_DISTANCE_SQ) {
            _stuckAnchorPos = pos;
            _stuckAnchorMs = now;
            return false;
        }
        return now - _stuckAnchorMs > STUCK_WINDOW_MS;
    }

    /**
     * (2026-09-26 问题一) 停滞拉黑看门狗: 玩家 4s 净位移 &lt;1 格 → 拉黑当前
     * 掉落物。合法静止场景不计时: 等待下落物落地 / 物品已贴身 (腾背包/等
     * 自动拾取) / 正在挖方块开路。
     */
    private void tickStallWatchdog(AltoClef mod) {
        if (_currentDrop == null || !_currentDrop.isAlive() || _blacklist.contains(_currentDrop)) {
            _stallAnchorPos = null;
            return;
        }
        boolean waitingForLand = !_pickupItemSeenInWater
                && !_currentDrop.isOnGround() && !_currentDrop.isTouchingWater();
        boolean colliding = mod.getEntityTracker().isCollidingWithPlayer(_currentDrop);
        if (waitingForLand || colliding || mod.getControllerExtras().isBreakingBlock()) {
            _stallAnchorPos = null;
            return;
        }
        Vec3d pos = mod.getPlayer().getPos();
        if (_stallAnchorPos == null || pos.squaredDistanceTo(_stallAnchorPos) > PICKUP_STALL_DISTANCE_SQ) {
            _stallAnchorPos = pos;
            _stallTimer.reset();
            return;
        }
        if (_stallTimer.elapsed()) {
            _stallAnchorPos = null;
            Debug.logMessage("[PickupStall] 追踪掉落物停滞 4s 无位移 (导航不到/够不着), 拉黑: "
                    + _currentDrop.getStack().getItem().getTranslationKey() + " at "
                    + _currentDrop.getBlockPos().toShortString());
            _blacklist.add(_currentDrop);
            mod.getEntityTracker().requestEntityUnreachable(_currentDrop);
            _progressChecker.reset();
        }
    }

    public PickupDroppedItemTask(ItemTarget[] itemTargets, boolean freeInventoryIfFull) {
        this(itemTargets, freeInventoryIfFull, 0);
    }

    public PickupDroppedItemTask(ItemTarget[] itemTargets, boolean freeInventoryIfFull, double maxPursuitRangeSq) {
        _itemTargets = itemTargets;
        _freeInventoryIfFull = freeInventoryIfFull;
        _maxPursuitRangeSq = maxPursuitRangeSq > 0 ? maxPursuitRangeSq : Double.POSITIVE_INFINITY;
    }

    public PickupDroppedItemTask(ItemTarget target, boolean freeInventoryIfFull) {
        this(new ItemTarget[]{target}, freeInventoryIfFull);
    }

    public PickupDroppedItemTask(Item item, int targetCount, boolean freeInventoryIfFull) {
        this(new ItemTarget(item, targetCount), freeInventoryIfFull);
    }
    public PickupDroppedItemTask(Item item, int targetCount) {
        this(item, targetCount, true);
    }

    public static boolean isIsGettingPickaxeFirst(AltoClef mod) {
        return isGettingPickaxeFirstFlag && mod.getModSettings().shouldCollectPickaxeFirst();
    }

    /**
     * 贵重掉落物：重要物品（钻石/下界合金/附魔金苹果…）或当前行为保护
     * 物品（任务声明保护的工具/床等）或常见贵重物（锭/工具/床）。
     * 这些掉落物失败时不永久拉黑、不走远。
     */
    private static boolean isValuableDrop(AltoClef mod, ItemEntity drop) {
        Item item = drop.getStack().getItem();
        return mod.getModSettings().isImportant(item)
                || mod.getBehaviour().isProtected(item)
                || ItemHelper.isValuableDropItem(item);
    }

    public boolean isCollectingPickaxeForThis() {
        return _collectingPickaxeForThisResource;
    }

    // (2026-09-01 用户"肉已进包仍捡 + Wander for Infinity" 卡死修复)
    // 父类 AbstractDoToClosestObjectTask 在【找不到匹配掉落物】(getClosestTo 返回空,
    // 常见于掉落物已被拾取/despawn) 时会调用 getWanderTask() 兜底。默认是无穷
    // TimeoutWanderTask(INFINITY) — isFinished 恒 false → 任务永不结束 → CollectFood
    // 的 _currentResourceTask 粘住它 → 永久卡在 "Wander for Infinity" (肉已在包)。
    // 改成有限的 20 格游荡: 游荡完 isFinished=true → 任务自然结束 → 父任务放手。
    // 必须返回【持久复用】的 _wanderTask 字段, 不能每次 new —— 否则 onTick 每次
    // getWanderTask 都新建一个, _origin 在 onStart(每次注册时) 重置, 永远游不够 20 格。
    @Override
    protected Task getWanderTask(AltoClef mod) {
        return _wanderTask;
    }

    // (2026-09-01 根因第二层: "肉已进包仍卡拾取/无限游荡") 任务系统的子任务
    // 不会被 isFinished 自动终止 (Task.tickInternal 无 isFinished 检查,
    // 终止权在父任务)。而本任务从未覆写 isFinished → 基类默认恒 false →
    // 粘性持有者 (CollectFoodTask._foodDropPickupTask / CollectWoolTask /
    // CollectCropTask._collectSeedTask / ResourceTask L170) 判
    // "isActive && !isFinished" 恒真, 永不放手 → 游荡子任务即使走完 20 格
    // 也无人停止, bot 无限游荡 (17:05:03-17:05:28 日志 25 秒零任务切换实锤)。
    // 语义: 正在追的掉落物仍有效, 或附近还能找到匹配掉落物 → 未完成;
    // 没有任何可追目标(已被拾取/despawn/全部拉黑) → 完成, 持有者放手。
    // 注: _currentDrop 优先判定 — tracker 感知记忆(20s)过期后 getClosestTo
    // 会漏掉远处仍在追的实体, 不能因此误判完成。
    @Override
    public boolean isFinished(AltoClef mod) {
        if (_currentDrop != null && isValid(mod, _currentDrop)) {
            return false;
        }
        return getClosestTo(mod, getOriginPos(mod)).isEmpty();
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        _mod = mod;
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering after blacklisting item...");
            _progressChecker.reset();
            return _wanderTask;
        }

        // If we're getting a pickaxe for THIS resource...
        if (isIsGettingPickaxeFirst(mod) && _collectingPickaxeForThisResource && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
            _progressChecker.reset();
            setDebugState("Collecting pickaxe first");
            return getPickaxeFirstTask;
        } else {
            if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                isGettingPickaxeFirstFlag = false;
            }
            _collectingPickaxeForThisResource = false;
        }

        // (2026-09-26 问题一) 停滞拉黑看门狗, 见字段注释。放在兜底分支之前,
        // 父类每 tick 都会回到本 onTick, 无论当前子任务是导航还是直抓都覆盖。
        tickStallWatchdog(mod);

        if (!_progressChecker.check(mod)) {
            _progressChecker.reset();
            if (_currentDrop != null && !_currentDrop.getStack().isEmpty()) {
                // (2026-09-26) 掉落物已贴身(碰撞范围) ≠ 够不着: 正在腾背包/等
                // 自动拾取, 此时拉黑 = 主动丢弃脚下物品, 不计失败。
                if (mod.getEntityTracker().isCollidingWithPlayer(_currentDrop)) {
                    return super.onTick(mod);
                }
                // (2026-08-22) 感知目标无时限原则: 掉落物是感知目标,
                // 游泳慢/绕路/腾背包一律继续追(不切任务不拉黑)。
                // 只有真被困(15 秒位移 < 2 格)才允许降级处理;
                // 掉落物自然消失(despawn)由 isValid 的 isAlive 兜底。
                if (!isStuckInPlace(mod)) {
                    return super.onTick(mod);
                }
                // We might want to get a pickaxe first.
                if (!isGettingPickaxeFirstFlag && mod.getModSettings().shouldCollectPickaxeFirst() && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)) {
                    Debug.logMessage("Stuck in place trying to reach drop, will collect a stone pickaxe first and try again!");
                    _collectingPickaxeForThisResource = true;
                    isGettingPickaxeFirstFlag = true;
                    return getPickaxeFirstTask;
                }

                // 贵重掉落物（2026-08-21 用户原则：重要物品持续感知）：
                // 床/金属锭/工具/钻石系等即使暂时捡不起来（典型：满背包
                // ——EnsureFreeInventorySlotTask 正在腾位）也【绝不永久拉黑、
                // 绝不 wander 走远】。只做 3 秒临时拉黑作为重试节流，保持
                // 追踪直到捡到或实体 despawn/离开渲染距离（= 距离太远，
                // 自然舍弃）。满背包场景下 wander 反而会走远导致掉落物
                // 卸载，等于主动丢弃。
                if (isValuableDrop(mod, _currentDrop)) {
                    Debug.logMessage("(PickupDroppedItemTask) Failed to pick up VALUABLE drop "
                            + _currentDrop.getStack().getItem().getTranslationKey() + " at "
                            + _currentDrop.getBlockPos().toShortString()
                            + " — keeping it tracked, retrying (no permanent blacklist).");
                    mod.getEntityTracker().requestEntityUnreachable(_currentDrop);
                    return null;
                }

                Debug.logMessage(StlHelper.toString(_blacklist, element -> element == null ? "(null)" : element.getStack().getItem().getTranslationKey()));
                Debug.logMessage("Failed to pick up drop, suggesting it's unreachable.");
                _blacklist.add(_currentDrop);
                mod.getEntityTracker().requestEntityUnreachable(_currentDrop);
                return _wanderTask;
            }
        }

        return super.onTick(mod);
    }


    @Override
    protected boolean isEqual(Task other) {
        // Same target items
        if (other instanceof PickupDroppedItemTask task) {
            return Arrays.equals(task._itemTargets, _itemTargets) && task._freeInventoryIfFull == _freeInventoryIfFull;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        StringBuilder result = new StringBuilder();
        result.append("Pickup Dropped Items: [");
        int c = 0;
        for (ItemTarget target : _itemTargets) {
            result.append(target.toString());
            if (++c != _itemTargets.length) {
                result.append(", ");
            }
        }
        result.append("]");
        return result.toString();
    }

    @Override
    protected Vec3d getPos(AltoClef mod, ItemEntity obj) {
        // (2026-08-31 排查诊断) 掉水里物品导航仍到树冠(y=78) — 打印掉落物实体真实
        // 状态, 确认到底走哪分支。每 5s 打印一次避免刷屏。
        {
            long now = System.currentTimeMillis();
            if (now - _lastPickupDiagMs > 5000) {
                _lastPickupDiagMs = now;
                Vec3d p = obj.getPos();
                // (2026-09-08 零行为改动诊断增强) 补玩家完整坐标、水平/3D 距离、
                // 物品所在格与脚下方块的方块 id — 定位"baritone 疯狂寻路/不动、
                // 眼前几格吸不到"时双方到底各在哪、脚下是什么方块。
                Vec3d me = (_mod != null && _mod.getPlayer() != null)
                        ? _mod.getPlayer().getPos() : Vec3d.ZERO;
                double ddx = p.x - me.x;
                double ddz = p.z - me.z;
                double hDist = Math.sqrt(ddx * ddx + ddz * ddz);
                String itemBlock = _mod != null
                        ? String.valueOf(_mod.getWorld().getBlockState(obj.getBlockPos()).getBlock()) : "?";
                String groundBlock = _mod != null
                        ? String.valueOf(_mod.getWorld().getBlockState(obj.getBlockPos().down()).getBlock()) : "?";
                Debug.logMessage("[PickupTarget] item=(" + (int)p.x + "," + (int)p.y + "," + (int)p.z
                        + ") isTouchingWater=" + obj.isTouchingWater()
                        + " isOnGround=" + obj.isOnGround()
                        + " player=(" + String.format("%.2f", me.x) + "," + String.format("%.2f", me.y) + "," + String.format("%.2f", me.z) + ")"
                        + " hDist=" + String.format("%.2f", hDist)
                        + " dist3d=" + String.format("%.2f", me.distanceTo(p))
                        + " itemBlock=" + itemBlock
                        + " groundBlock=" + groundBlock
                        + " openWater=" + isOpenWaterColumn(mod, obj));
            }
        }
        // (2026-08-25 水面掉落物) 掉在水里/水面的物品 (如末影之眼): getLandSurfaceY
        // 对水面列返回 -1 会跳到岸上陆地高度 → 目标 Y 比水面高 → bot 在物品上方
        // 搭路死等, 不跟物品同水平面 (用户实测: 眼睛掉水里, bot 搭路到眼睛上方等,
        // 一直捡不到)。水面物品直接以物品当前位置为目标, 游过去靠碰撞拾取。
        // (2026-08-26 下潜捡眼) 但沉水物品的 y 会随水流/浮力逐 tick 变, 直接用
        // 实时 pos 做导航目标 → GetToBlockTask 目标每 tick 变 → TaskPingPong 追
        // 不上 (12:51 日志实锤)。水面物品导航目标 y 用【玩家当前 y】(稳定水平面),
        // 只追 XZ; 贴近后用 DiveToGrabItemTask 直接下潜/上浮抓取。
        // (2026-08-26 用户反馈: 地下水场景) 只有【敞开水体】 (物品所在水柱向上
        // 到水面/天空没有实心方块挡住) 才能靠下潜捡 — 眼睛掉进地下洞穴的水塘时
        // 被岩石盖住, 下潜不成立, 要转为正常分支 (精确 3D 位置, baritone 下挖)。
        // (2026-08-31 掉水里物品, 用户定位) 目标 Y 用【掉落物实体实际高度】
        // (obj.getY())。旧实现用 player.getY() —— bot 站在树冠/高处时 target Y
        // 也在高处, 于是悬在物品正上方(树冠盖住水面)不下来, 够不到水里物品
        // (用户实测: 失去的木头掉落物在水里 y=66, 上方树冠, 玩家 y=77, bot 却导航
        // 到树顶 77)。
        // 关键修复: 触水(isTouchingWater=true)就用物品真实 y。旧代码还要求
        // isOpenWaterColumn(水柱上方无实心) —— 树冠把水柱上方挡成实心 → false →
        // 没走此分支, 反而走到下方 getLandSurfaceY 把树冠当地表返回 77 → bot 爬树。
        // 物品既然在水里的低位, 导航就应到物品真实 y 让 bot 游过去/钻过去捡,
        // 不该受"上方是否敞开"影响(那只决定能否下潜, 不影响目标高度)。
        if (obj.isTouchingWater()) {
            return new Vec3d(obj.getX(), obj.getY(), obj.getZ());
        }
        // (2026-08-26 用户反馈: 眼睛掉进洞穴/空洞, bot 只到地表不捡) 已落地的
        // 物品 → 直接导航到物品【精确 3D 位置】, baritone GoalBlock 会下挖/绕路
        // 到达物品真正所在高度。旧实现用 getLandSurfaceY 地表投影 — 物品掉进
        // 地表下方的洞穴/空洞时, 地表 Y 比物品高, bot 只走到物品正上方方块就
        // "Completing goal" 停住, 永远不下去捡 (日志实锤: 捡眼导航到 (791,88,-1591)
        // 卡住, 眼睛在下方洞里)。水面场景由上面的 isTouchingWater 分支 + 
        // DiveToGrabItemTask 下潜处理, 这里只管"落地/在洞穴/在树顶"等陆地情况。
        if (obj.isOnGround()) {
            BlockPos ib = obj.getBlockPos();
            // (2026-09-30 农田内嵌掉落物) farmland/泥土路等方块碰撞面只有 15/16 格,
            // 落在其上的物品脚底 y≈89.94 <90 → floor 后 blockpos 落在【农田块内部】
            // (20260930-150356 实锤: 本场全部 wheat 目标 itemBlock=farmland)。
            // 非空气目标块 baritone 无法"站入" → A* 穷举爆炸(8.8M movements 算不
            // 出路径) → PickupStall 4s 拉黑换下一颗麦子再爆算, 农田上散落的每颗
            // 掉落麦子都要烧一轮, 外观即"永远在导航去捡麦子、任务永不超时"。
            // 物品所在格非空气 → 目标改其上一格 (空气/作物, 无碰撞可站), 站上去
            // 即与物品同格碰撞拾取。同族: 泥土路/灵魂沙上的掉落物 (14/16 碰撞)。
            if (!mod.getWorld().getBlockState(ib).isAir()
                    && !WorldHelper.isSolid(mod, ib.up())) {
                return new Vec3d(ib.getX() + 0.5, ib.getY() + 1, ib.getZ() + 0.5);
            }
            // (2026-09-19 洞沿掉落物) 物品"落地"但其坐标列下方 2 格内无实心 —
            // 卡在洞沿/方块棱边上 (支撑在邻列, 坐标列读空气) → GoalBlock 站不
            // 进去, baritone active-stale 每 4s 强制重寻路 + 触发全图重扫, bot
            // 永远到不了 (20260919-120616 实锤: 地狱岩补货追 (776,65,128) 5 分
            // 钟, 玩家距物品 0.6 格就是不吸)。改为导航到 ±1 邻列中【物品同高
            // 可站】的格子, 贴身 0.5~0.9 格即可吸起。找不到可站邻列才退回实体
            // 精确位置 (旧行为)。
            if (!WorldHelper.isSolid(mod, ib.down()) && !WorldHelper.isSolid(mod, ib.down(2))) {
                Vec3d stand = findStandableNeighbor(mod, ib);
                if (stand != null) return stand;
            }
            return obj.getPos();
        }
        // (2026-08-25 用户实测: 捡末影之眼导航到"天空") 掉落物还在空中/半空下落
        // 未落地时, 直接导航到实体 pos 会搭高/爬到高处追移动目标。此时才用地表
        // 高度投影 (末影之眼这类掉落物会落到地面/滚下来), 避免搭高到空中。
        int groundY = WorldHelper.getLandSurfaceY(mod, obj.getBlockPos().getX(), obj.getBlockPos().getZ(), 8);
        if (groundY > 0) {
            return new Vec3d(obj.getX(), groundY, obj.getZ());
        }
        if (!obj.isOnGround() && !obj.isTouchingWater()) {
            // Assume we'll land down one or two blocks from here. We could do this more advanced but whatever.
            BlockPos p = obj.getBlockPos();
            if (!WorldHelper.isSolid(mod, p.down(3))) {
                return obj.getPos().subtract(0,2,0);
            }
            return obj.getPos().subtract(0,1,0);
        }
        return obj.getPos();
    }

    /**
     * (2026-09-19 洞沿掉落物) 物品坐标列不可站立时, 找 ±1 邻列中"同高度可站"
     * (本列与上列非实心 + 下方实心) 且离物品最近的格子中心; 找不到返回 null。
     */
    private Vec3d findStandableNeighbor(AltoClef mod, BlockPos itemPos) {
        Vec3d itemCenter = new Vec3d(itemPos.getX() + 0.5, itemPos.getY() + 0.5, itemPos.getZ() + 0.5);
        Vec3d best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos p = new BlockPos(itemPos.getX() + dx, itemPos.getY(), itemPos.getZ() + dz);
                if (!mod.getChunkTracker().isChunkLoaded(p)) continue;
                if (WorldHelper.isSolid(mod, p) || WorldHelper.isSolid(mod, p.up())) continue;
                if (!WorldHelper.isSolid(mod, p.down())) continue;
                Vec3d c = new Vec3d(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                double sq = c.squaredDistanceTo(itemCenter);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = c;
                }
            }
        }
        return best;
    }

    @Override
    protected Optional<ItemEntity> getClosestTo(AltoClef mod, Vec3d pos) {
        Optional<ItemEntity> tracked = mod.getEntityTracker().getClosestItemDrop(
                pos,
                _itemTargets);
        // (2026-09-19 15:45 追距上限) 超出追距的掉落不算目标 (见 _maxPursuitRangeSq)。
        if (tracked.isPresent() && inPursuitRange(tracked.get(), pos)) {
            return tracked;
        }
        // (2026-08-26 修复: 掉落眼追不到) EntityTracker 的 _itemDropLocations 只
        // 缓存"已落地"的掉落物 (isOnGround/isTouchingWater/下方实心), 刚落/
        // 还在下落的眼睛不在缓存 → getClosestItemDrop 返回空 → 拾取任务直接
        // wandering, 配合门控每 tick 重发, bot 原地打转 (12:30 日志实锤:
        // "Eye landed at ..." 后一直 "Waiting for calculations/wandering")。
        // 兜底直接扫世界找匹配掉落物 (含下落中的), 交给 getGoalTask 等落定。
        return findClosestDropByScan(mod, pos);
    }

    /** (2026-09-19) 该掉落物是否在追距内 (无上限构造时恒 true)。 */
    private boolean inPursuitRange(ItemEntity drop, Vec3d pos) {
        return drop.squaredDistanceTo(pos) <= _maxPursuitRangeSq;
    }

    /** 直接扫世界找最近的匹配掉落物 (含下落中/未落地, 兜底 EntityTracker 缓存)。 */
    private Optional<ItemEntity> findClosestDropByScan(AltoClef mod, Vec3d pos) {
        ItemEntity best = null;
        double bestDist = 64 * 64;
        for (Entity e : mod.getWorld().getEntities()) {
            if (!(e instanceof ItemEntity ie) || !ie.isAlive()) continue;
            if (_blacklist.contains(ie)) continue;
            // (2026-09-26 问题一) tracker 已判 unreachable 的实体不再被扫世界
            // 兜底捞回 —— 旧行为是 "Try 4/3 之后又被本方法复活 → 永不生效"
            // 的直接根因 (20260926-101726 实锤 105s+ 死循环)。
            if (!mod.getEntityTracker().isEntityReachable(ie)) continue;
            if (!matchesAnyTarget(ie)) continue;
            // (2026-09-19) 追距上限: 超距的陈旧掉落直接跳过。
            if (!inPursuitRange(ie, pos)) continue;
            double d = ie.squaredDistanceTo(pos);
            if (d < bestDist) {
                bestDist = d;
                best = ie;
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean matchesAnyTarget(ItemEntity ie) {
        for (ItemTarget target : _itemTargets) {
            for (Item item : target.getMatches()) {
                if (ie.getStack().getItem() == item) return true;
            }
        }
        return false;
    }

    /**
     * (2026-08-26 用户反馈: 地下水场景) 物品在水里时, 只有物品所在水柱向上
     * 一路都是水/空气 (敞开水体, 比如河流/湖泊/海洋) 才能靠"水面游过去+下潜"
     * 捡取。若向上扫到实心方块 (地下洞穴的水塘被岩石盖住) → 下潜路线被挡,
     * 返回 false, 调用方转为正常分支 (导航到精确 3D 位置, baritone 下挖过去)。
     * 区块未加载时保守返回 false (无法确认水体敞开, 交给正常导航)。
     */
    private boolean isOpenWaterColumn(AltoClef mod, ItemEntity obj) {
        int x = obj.getBlockPos().getX();
        int y = obj.getBlockPos().getY();
        int z = obj.getBlockPos().getZ();
        for (int up = y; up <= y + 12; up++) {
            BlockPos p = new BlockPos(x, up, z);
            if (!mod.getChunkTracker().isChunkLoaded(p)) return false;
            BlockState s = mod.getWorld().getBlockState(p);
            if (s.getBlock() == Blocks.WATER || s.isAir()) continue;
            return false; // 遇到实心 → 地下水, 不能下潜
        }
        return true;
    }

    @Override
    protected Vec3d getOriginPos(AltoClef mod) {
        return mod.getPlayer().getPos();
    }

    @Override
    protected Task getGoalTask(ItemEntity itemEntity) {
        if (!itemEntity.equals(_currentDrop)) {
            _currentDrop = itemEntity;
            _progressChecker.reset();
            if (isGettingPickaxeFirstFlag && _collectingPickaxeForThisResource) {
                Debug.logMessage("New goal, no longer collecting a pickaxe.");
                _collectingPickaxeForThisResource = false;
                isGettingPickaxeFirstFlag = false;
            }
        }
        // Ensure our inventory is free if we're close
        boolean touching = _mod.getEntityTracker().isCollidingWithPlayer(itemEntity);
        if (touching) {
            if (_freeInventoryIfFull) {
                if (_mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(itemEntity.getStack(), false).isEmpty()) {
                    return new EnsureFreeInventorySlotTask();
                }
            }
        }
        // (2026-08-25 用户实测"捡末影之眼导航到天空") 导航目标用 getPos 计算的
        // 地面投影, 而不是实体实时 pos — 末影之眼刚从高处落地/半空时实体 pos
        // 在天空, GetToEntityTask 会搭高/导航到天上。掉落物落地后位置基本
        // 固定, 直接导航到地面高度位置即可。
        // (2026-08-26 修复: 捡眼睛/掉落物跨大水面时"不会划船") 到物品需跨 ≥30 格
        // 大水面 → 用开船导航 (普通 GetToBlockTask 在水上不会转向船/只会慢游
        // 卡住)。已骑船 (船渡中途续驾) 也一样开船 — GetToBlockTask 在骑乘状态
        // 无效。物品在水中 → BoatCrossingTask 带 grabItemPos: 船开到物品旁下船,
        // 交还本任务下潜捡取 (深水物品船驶过吸不到); 物品在岸 → 开船到远岸再走。
        // (2026-08-26 下潜捡眼失败) 先稳定导航目标, 见字段注释。
        if (_pickupNavItem != itemEntity) {
            _pickupNavWaitTicks = 0;
            _pickupNavItem = itemEntity;
            _pickupNavTarget = null;
            _pickupItemSeenInWater = false;
        }
        if (itemEntity.isTouchingWater()) {
            _pickupItemSeenInWater = true;
        }
        // (2026-09-02 修: 已消失掉落物不再等落地) 旧 `!isOnGround() && !isTouchingWater()`
        // 对【已被捡走/despawn 的陈旧实体】(tracker 感知记忆 20s 未过期) 恒 true →
        // falling 分支无限循环 "Waiting for dropped item to land" 卡死 (日志实锤
        // 19:07 Greedy mining diamond 挖完钻石后卡 18 秒)。实体已不在世界 → 不走
        // "等落地", 交给 isValid(isAlive)/进度检查释放。
        // (2026-09-22) 水面粘滞: 见 _pickupItemSeenInWater 注释 —— 漂浮物出水
        // 瞬间两条件同时翻真, 等落地分支被瞬时误触发, 加 _pickupItemSeenInWater
        // 后沾过水的物品永不进等待分支。
        boolean falling = itemEntity.isAlive()
                && !_pickupItemSeenInWater
                && !itemEntity.isOnGround() && !itemEntity.isTouchingWater();
        if (falling) {
            // 还在下落 (未落地未入水): 等落定, 不追实时 pos (防 TaskPingPong)。
            // 超时后兜底改为慢追 (见字段注释)。
            if (++_pickupNavWaitTicks <= PICKUP_NAV_WAIT_MAX_TICKS) {
                setDebugState("Waiting for dropped item to land.");
                return null;
            }
            _pickupNavWaitTicks = 0;
        }
        Vec3d liveTarget = getPos(_mod, itemEntity);
        if (_pickupNavTarget == null
                || _pickupNavTimer.elapsed()
                || liveTarget.squaredDistanceTo(_pickupNavTarget) > PICKUP_NAV_UPDATE_DIST_SQ) {
            _pickupNavTarget = liveTarget;
            _pickupNavTimer.reset();
        }
        Vec3d targetPos = _pickupNavTarget;
        BlockPos target = new BlockPos(targetPos);
        // (2026-09-08 零行为改动诊断) baritone "疯狂寻路/站着不动"时, 目标方块到底
        // 是什么 id、是否实心 — 与 [PickupTarget] 对齐时间窗一起看。独立 5s 节流。
        {
            long now = System.currentTimeMillis();
            if (now - _lastGoalDiagMs > 5000) {
                _lastGoalDiagMs = now;
                Debug.logMessage("[PickupGoal] goal=" + target.toShortString()
                        + " solid=" + WorldHelper.isSolid(_mod, target)
                        + " solidBelow=" + WorldHelper.isSolid(_mod, target.down())
                        + " block=" + _mod.getWorld().getBlockState(target).getBlock()
                        + " below=" + _mod.getWorld().getBlockState(target.down()).getBlock()
                        + " navItem=" + (_pickupNavItem == null ? "null" : _pickupNavItem.getBlockPos()));
            }
        }
        boolean riding = _mod.getPlayer().getVehicle() instanceof BoatEntity;
        // (2026-08-26 下潜捡眼) Baritone 不支持可靠水下下潜 (只会带到水面 XZ,
        // 12:51 日志: GetToBlockTask 追水下目标 y 逐 tick 变 → TaskPingPong, bot
        // 在水面等物品漂上来)。贴到水下物品水平 ≤8 格 → 直接下潜/上浮抓取。
        // (2026-08-26 用户实测: 导航会搭路到物品正上方, bot 站在自己搭的方块上
        // 不算入水 → 这里不要求 isTouchingWater, 交给 DiveToGrabItemTask 判断
        // "搭在水上的平台则打掉脚下块掉进水里再潜"。)
        // (2026-09-26 问题二: 水上掉落物不再受 baritone"到不了水方块"限制)
        // 触水且贴近(h<=8)一律直接抓取。原分支要求敞开水体 (isOpenWaterColumn)
        // 才走直抓 —— 那只决定"能否下潜到深水", 不该决定"能否贴近": 20260926
        // 实锤洞穴水塘漂浮粗铁 openWater=false(水柱上方 12 格内有实心) → 落到
        // GetToBlockTask(水方块) → baritone 穷举 8.9M movements 算不出路径 →
        // "At goal vicinity holding" 死循环。漂浮水面的物品走 DiveToGrab 平游
        // 分支 = 直接走/游过去 (含从岸边踏入浅水); 真沉底的深水物品有潜行下沉
        // + 打掉脚下块兜底。远距离大水面开船逻辑不变 (grabItemPos 只在敞开
        // 水体传入, 船驶过吸不到深水物品的旧行为保留)。
        // (2026-09-30 用户口径: 直抓只对 bot 已在水中的情况生效) 本分支原本为
        // "海上末影之眼"设计, 前提是 bot 已经在水里。20260930-6 场次 13:10:20-52
        // 实锤: bot 站在岸边泥土上 (y=87), 橡木原木漂在水面 (-272,89,237,
        // openWater=false), 旧闸门只查 itemInWater + h<=8 就直派 dive →
        // DiveToGrabItemTask 在岸上 dy>1.5 只会 JUMP+前进 → bot 原地反复起跳
        // 32s 不进水, 连锁触发 PickupStall 拉黑/Stuck-break 连挖/SWING-BREAK 盲挥。
        // 修复: 加 bot isTouchingWater 闸门。bot 不在水中 → 走下面的正常导航
        // (GetToBlockTask 到掉落物位置, 靠近碰撞拾取); 导航不到由 PickupStall
        // 4s 停滞拉黑 (Try 1/3) 超时兜底, 不再派 dive。
        boolean itemInWater = itemEntity.isTouchingWater();
        boolean itemInOpenWater = itemInWater && isOpenWaterColumn(_mod, itemEntity);
        boolean botInWater = _mod.getPlayer().isTouchingWater();
        if (itemInWater && !botInWater && !riding) {
            double h = Math.sqrt(
                    (targetPos.x - _mod.getPlayer().getX()) * (targetPos.x - _mod.getPlayer().getX())
                            + (targetPos.z - _mod.getPlayer().getZ()) * (targetPos.z - _mod.getPlayer().getZ()));
            if (h <= 8) {
                // (2026-09-30) 闸门外诊断: 旧版在这里直派 dive, 现在改正常导航。
                // 独立 5s 节流, 验证判据 = 日志出现 SKIP 且任务状态是导航而非
                // "Diving to grab dropped item."
                long nowMs = System.currentTimeMillis();
                if (nowMs - _lastDiveSkipDiagMs > 5000) {
                    _lastDiveSkipDiagMs = nowMs;
                    Debug.logMessage("[PickupDive] SKIP: bot 不在水中, 正常导航靠近碰撞拾取 (旧版此处直派 dive): item="
                            + target.toShortString() + " h=" + String.format("%.1f", h));
                }
            }
        }
        if (itemInWater && botInWater && !riding) {
            double h = Math.sqrt(
                    (targetPos.x - _mod.getPlayer().getX()) * (targetPos.x - _mod.getPlayer().getX())
                            + (targetPos.z - _mod.getPlayer().getZ()) * (targetPos.z - _mod.getPlayer().getZ()));
            if (h <= 8) {
                if (!itemInOpenWater) {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - _lastDiveDiagMs > 5000) {
                        _lastDiveDiagMs = nowMs;
                        Debug.logMessage("[PickupDive] 封闭水体(openWater=false)贴近直抓, 绕过 baritone 水域寻路: item="
                                + target.toShortString() + " h=" + String.format("%.1f", h));
                    }
                }
                setDebugState("Diving to grab dropped item.");
                return new DiveToGrabItemTask(itemEntity);
            }
        }
        if (riding || WorldHelper.isLargeWaterCrossing(_mod, _mod.getPlayer().getBlockPos(), target, 30)) {
            // (2026-09-30) 落回开船/正常导航时同步刷新状态：旧实例的 setDebugState
            // 会跨重派发残留（20260930-142545 实锤：14:30:23 dive 一次后状态恒显
            // "Diving to grab dropped item."，实际已走 GetToBlockTask，误导现场判读）。
            setDebugState("Boat crossing to dropped item.");
            return new BoatCrossingTask(
                    new BlockPos(target.getX(), 0, target.getZ()),
                    false,
                    itemInOpenWater ? targetPos : null);
        }
        setDebugState("Navigating to dropped item.");
        return new GetToBlockTask(target);
    }

    @Override
    protected boolean isValid(AltoClef mod, ItemEntity obj) {
        // (2026-09-19) 追距上限兜底: 粘住的目标若因位移超距 (bot 被击退/绕路),
        // 立即失效重选, 不再"感知目标死追到底"。
        if (obj.squaredDistanceTo(getOriginPos(mod)) > _maxPursuitRangeSq) return false;
        return obj.isAlive() && !_blacklist.contains(obj);
    }

}
