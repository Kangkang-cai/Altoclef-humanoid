package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

/**
 * (2026-09-17 终极丢垃圾, 用户钦定升级阶梯第 3 级) 反复触发丢垃圾后不再扔地上:
 * 就近放一个箱子, 之后垃圾【全部】存进箱子, 不再抛丢。
 *
 * 流程: 找/放箱子 → 右键打开 → 委托 StoreGarbageInContainerTask 把垃圾全部
 * 存入 (复用既有状态机, 已知坑已处理: 存前确认容器装得下, 防光标死锁;
 * 只认 CHEST/SHULKER 类容器)。
 *
 * 设计要点:
 *  - 箱子位置跨实例 static 共享 (EnsureFreeInventorySlotTask 每次腾位可能新建
 *    本任务实例, 状态不能丢)。方块被撸/换世界时校验失效自动遗忘。
 *  - 距离遗忘刷新 (用户钦定): 垃圾触发点离箱子超过 FORGET_DISTANCE → 忘记,
 *    就近重放新箱子, 绝不远在天边跑回来放垃圾。
 *  - 木料闸门 (用户钦定"木头足够木板足够的情况下"): 无箱子且 (无 4 木板 且
 *    无 1 原木) → 本任务 blocked, 上层落回 L2 抛丢。TaskCatalogue 负责原木→
 *    木板→箱子的整条合成链。
 *  - 30s 全局失败冷却: 开箱/放置反复失败时短暂放弃箱子路线, 防实例重建
 *    死循环 (实例字段计数防不住上层每 tick new)。
 */
public class GarbageChestDisposalTask extends Task {

    // 就近箱子 (static: 跨实例共享, 见类注释)。
    private static BlockPos _chestPos = null;
    // 距离遗忘阈值: 超过就忘掉箱子重放, 不跑远路。
    public static final double FORGET_DISTANCE = 24.0;
    // 放置点扫描半径 (相对脚下)。
    private static final int PLACE_SCAN_RADIUS = 3;
    // 全局失败冷却: 开箱/放置反复失败后 30s 内直接 blocked (落回抛丢)。
    private static long _failedUntilMs = 0;
    private static final long FAILURE_COOLDOWN_MS = 30_000;
    // (2026-09-17 防重入) 箱子流程进行中的实例标记: 箱子合成/开箱链内部也会派发
    // EnsureFreeInventorySlotTask (腾位递归入口), 该场景必须跳过 L3 路由, 防两条
    // 箱子流程并行重复合成/放置 (同 ThrowCursorTask._activeInstance 口径)。
    private static GarbageChestDisposalTask _flowInstance = null;

    public static boolean isFlowActive() {
        return _flowInstance != null;
    }

    private boolean _blocked = false;
    private Task _catalogueTask;
    private PlaceBlockTask _placeTask;
    private BlockPos _pendingPlace;
    private InteractWithBlockTask _openTask;
    private int _openAttempts;
    private StoreGarbageInContainerTask _storeTask;

    /**
     * 木料闸门: 手上有箱子 / 板够 4 / 原木够 1 (可合成) 才启用箱子路线。
     */
    public static boolean hasChestMaterials(AltoClef mod) {
        if (mod.getItemStorage().hasItem(Items.CHEST)) return true;
        return mod.getItemStorage().getItemCount(ItemHelper.PLANKS) >= 4
                || mod.getItemStorage().getItemCount(ItemHelper.LOG) >= 1;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _blocked = false;
        _catalogueTask = null;
        _placeTask = null;
        _pendingPlace = null;
        _openTask = null;
        _openAttempts = 0;
        _storeTask = null;
        _flowInstance = this;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_blocked) {
            return null;
        }
        // 全局失败冷却期内: 不折腾箱子, 交回上层抛丢。
        if (System.currentTimeMillis() < _failedUntilMs) {
            _blocked = true;
            return null;
        }
        // 1) 已有箱子: 校验有效性 (方块还在 + 距离遗忘)。
        if (_chestPos != null) {
            boolean stillChest = mod.getWorld() != null
                    && mod.getWorld().getBlockState(_chestPos).getBlock() == Blocks.CHEST;
            double dist = mod.getPlayer() != null
                    ? mod.getPlayer().getEyePos().distanceTo(new net.minecraft.util.math.Vec3d(
                            _chestPos.getX() + 0.5, _chestPos.getY() + 0.5, _chestPos.getZ() + 0.5))
                    : Double.MAX_VALUE;
            if (!stillChest || dist > FORGET_DISTANCE) {
                Debug.logMessage("(GarbageChest) forget chest " + _chestPos.toShortString()
                        + (stillChest ? " — too far (" + (int) dist + " > " + (int) FORGET_DISTANCE + "), placing new one nearby"
                        : " — block gone"));
                _chestPos = null;
            }
        }
        // 2) 箱子可用: 开箱 → 存垃圾 (StoreGarbageInContainerTask 会连光标上
        //    已拿的垃圾一起放进去, 然后逐件清空背包垃圾)。
        if (_chestPos != null) {
            if (StoreGarbageInContainerTask.isStorageContainerOpen()) {
                if (_storeTask == null) {
                    _storeTask = new StoreGarbageInContainerTask();
                }
                if (_storeTask.isFinished(mod)) {
                    // 存完 (没垃圾了) 或箱子满: 都算本任务完成, 上层重新决策
                    // (箱子满时 StoreGarbageInContainerTask 自己 blocked, 上层
                    // EnsureFree 落回抛丢级联, 箱子位置保留不遗忘)。
                    _blocked = true;
                    return null;
                }
                setDebugState("Storing garbage into chest");
                return _storeTask;
            }
            if (_openTask == null || _openTask.isFinished(mod)) {
                _openAttempts++;
                if (_openAttempts > 2) {
                    // 反复打不开 (被实体挡/路径不通): 遗忘箱子, 冷却后重放。
                    Debug.logMessage("(GarbageChest) cannot open chest after "
                            + _openAttempts + " attempts, cooling down");
                    _chestPos = null;
                    _failedUntilMs = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
                    _blocked = true;
                    return null;
                }
                Debug.logMessage("(GarbageChest) [GARBAGE-CHEST] opening chest " + _chestPos.toShortString());
                // 容器交互必须 wanderOnFailure=false (见 InteractWithBlockTask 注释,
                // 防 1 格外箱子被误拉黑)。
                _openTask = new InteractWithBlockTask(_chestPos, false);
            }
            setDebugState("Walking to garbage chest " + _chestPos.toShortString());
            return _openTask;
        }
        // 3) 没有可用箱子: 木料闸门。
        if (!mod.getItemStorage().hasItem(Items.CHEST)) {
            if (mod.getItemStorage().getItemCount(ItemHelper.PLANKS) < 4
                    && mod.getItemStorage().getItemCount(ItemHelper.LOG) < 1) {
                Debug.logMessage("(GarbageChest) no wood/planks for chest — falling back to throwing");
                _blocked = true;
                return null;
            }
            if (_catalogueTask == null || _catalogueTask.isFinished(mod)) {
                Debug.logMessage("(GarbageChest) crafting chest (4 planks)");
                _catalogueTask = TaskCatalogue.getItemTask(Items.CHEST, 1);
            }
            setDebugState("Crafting chest for garbage disposal");
            return _catalogueTask;
        }
        // 4) 手上有箱子: 就近找放置点 → 放置。
        if (_placeTask == null) {
            BlockPos spot = findPlaceSpot(mod);
            if (spot == null) {
                Debug.logMessage("(GarbageChest) no placeable spot nearby — falling back to throwing");
                _failedUntilMs = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
                _blocked = true;
                return null;
            }
            _pendingPlace = spot;
            _placeTask = new PlaceBlockTask(spot, Blocks.CHEST);
        }
        if (!_placeTask.isFinished(mod)) {
            setDebugState("Placing garbage chest at " + _pendingPlace.toShortString());
            return _placeTask;
        }
        // 放置任务结束: 校验方块真的放上了 (PlaceBlockTask 失败也会 isFinished)。
        if (mod.getWorld().getBlockState(_pendingPlace).getBlock() == Blocks.CHEST) {
            Debug.logMessage("(GarbageChest) [GARBAGE-CHEST] chest placed at " + _pendingPlace.toShortString());
            _chestPos = _pendingPlace;
            _placeTask = null;
            _pendingPlace = null;
            return null; // 下一 tick 进入开箱流程。
        }
        Debug.logMessage("(GarbageChest) place failed at " + _pendingPlace.toShortString() + ", cooling down");
        _placeTask = null;
        _pendingPlace = null;
        _failedUntilMs = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
        _blocked = true;
        return null;
    }

    /**
     * 就近找箱子放置点: 优先脚所在层 (贴地、右键可达), 再看脚下一层 (坑沿),
     * 切比雪夫半径 3。要求: 目标格非固体/非液体、下方固体、不是 bot 自己的格。
     */
    private BlockPos findPlaceSpot(AltoClef mod) {
        if (mod.getPlayer() == null) return null;
        BlockPos feet = mod.getPlayer().getBlockPos();
        BlockPos c = findInRing(mod, feet, 0, PLACE_SCAN_RADIUS);
        if (c != null) return c;
        return findInRing(mod, feet, -1, PLACE_SCAN_RADIUS);
    }

    /** 环形扫描 (切比雪夫距离 1..r) 找可放置格。 dyOff: 相对脚层的垂直偏移。 */
    private BlockPos findInRing(AltoClef mod, BlockPos feet, int dyOff, int maxR) {
        for (int r = 1; r <= Math.max(1, maxR); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = feet.add(dx, dyOff, dz);
                    if (p.equals(feet) || p.equals(feet.up())) continue;
                    if (WorldHelper.isSolid(mod, p)) continue;
                    if (!mod.getWorld().getBlockState(p).getFluidState().isEmpty()) continue;
                    if (!WorldHelper.isSolid(mod, p.down())) continue;
                    // 头顶别是固体也无所谓 (箱子 1 格), 但别放在贴脸挡路: 距脚 ≥1 格已保证。
                    return p;
                }
            }
        }
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 光标还拿着东西必须继续 (与 StoreGarbageInContainerTask 同口径, 防光标遗留)。
        if (!adris.altoclef.util.helpers.StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return false;
        }
        return _blocked;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _blocked = false;
        if (_flowInstance == this) {
            _flowInstance = null;
        }
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof GarbageChestDisposalTask;
    }

    @Override
    protected String toDebugString() {
        return "Disposing garbage into chest";
    }
}
