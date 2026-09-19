package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ScaffoldBlocks;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Place a block type at a position
 */
public class PlaceBlockTask extends Task implements ITaskRequiresGrounded {

    private static final int MIN_MATERIALS = 1;
    private static final int PREFERRED_MATERIALS = 32;
    private final BlockPos _target;
    private final Block[] _toPlace;
    private final boolean _useThrowaways;
    private final boolean _autoCollectStructureBlocks;
    // (2026-09-12 观感) 「站住不动」容忍从默认 6s 收紧到 2s。现场
    // 20260912-084704: Place structure{furnace} at 20,57,152 站桩 6s 才放弃换位
    // (08:48:13→20.6), 单次放置总耗时 13.1s, 用户反馈观感差。移动中 distance
    // 有进展不会触发, 故 2s 只压缩"真卡住"的等待。其余参数保持默认。
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(2, 0.1, 0.5, 0.001, 1);
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(3, true); // This can get stuck forever, so we increase the range.
    private Task _materialTask;
    private int _failCount = 0;
    // (2026-09-05 传送门放置硬守卫) 拒绝日志只打一次的标记。
    private boolean _protectedRefusalLogged = false;

    public PlaceBlockTask(BlockPos target, Block[] toPlace, boolean useThrowaways, boolean autoCollectStructureBlocks) {
        _target = target;
        _toPlace = toPlace;
        _useThrowaways = useThrowaways;
        _autoCollectStructureBlocks = autoCollectStructureBlocks;
    }

    public PlaceBlockTask(BlockPos target, Block... toPlace) {
        // (2026-08-26 用户要求: 没方块搭时自动去收集, 收集完再恢复任务) 旧默认
        // autoCollectStructureBlocks=false → 材料收集逻辑不启用, bot 没方块时
        // 空手跳跃手足无措。改为默认 true: 当 getMaterialCount < 1 (一块都没有)
        // 时, 自动派发 getMaterialTask(32) 去收集泥土/圆石/深板岩原石/下界岩,
        // 收集完 (count>=32) 再恢复本任务搭方块。
        this(target, toPlace, false, true);
    }

    public static int getMaterialCount(AltoClef mod) {
        // (2026-08-23 统计修正) 旧逻辑依赖 ItemStorageTracker 缓存, 与
        // Restocking 原木同款 bug: 背包实际有 dirt/cobblestone/netherrack 却
        // 统计为 0 → PlaceBlockTask 误判"没有结构方块" → 反复触发收集 32 个
        // 结构方块 (日志 15:19:52 "No structure items, collecting cobblestone
        // + dirt as default") → 被高优先级任务抢占 → 建传送门卡死。直接扫玩家
        // 背包 (main+offhand), 不依赖缓存刷新时机。
        // (2026-08-23 深板岩原石) 用户实测背包有 47 个深板岩原石仍报"缺方块" —
        // 结构方块候选只含 dirt/cobblestone/netherrack, 不含 cobbled_deepslate。
        // 深板岩原石是深地下最常见的可放置方块, 必须计入。
        if (mod.getPlayer() == null || mod.getPlayer().getInventory() == null) return 0;
        int count = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (isStructureMaterial(item)) {
                count += stack.getCount();
            }
        }
        ItemStack offhand = mod.getPlayer().getInventory().offHand.get(0);
        if (offhand != null && !offhand.isEmpty()) {
            if (isStructureMaterial(offhand.getItem())) {
                count += offhand.getCount();
            }
        }
        return count;
    }

    /** (2026-08-23) 可作为 CAST_FRAME 支撑的结构方块。
     *  (2026-08-26 讨论: 沙子/沙砾不能加入 — 重力方块水平搭路(悬空)时无支撑会
     *  落下去, 桥搭不起来反而更危险。只能用于有下方支撑的填埋场景, 需单独机制。)
     *  (2026-09-19 21:20 统一口径) 收编 ScaffoldBlocks 中心清单 (非重力, 同约束)。 */
    private static boolean isStructureMaterial(Item item) {
        return ScaffoldBlocks.isScaffoldBlock(item);
    }

    public static Task getMaterialTask(int count) {
        ItemTarget[] targets = new ItemTarget[ScaffoldBlocks.SAFE_MATERIALS.length];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = new ItemTarget(ScaffoldBlocks.SAFE_MATERIALS[i], count);
        }
        return TaskCatalogue.getSquashedItemTask(targets);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _progressChecker.reset();
        // If we get interrupted by another task, this might cause problems...
        //_wanderTask.resetWander();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-05 传送门放置硬守卫, 用户"bot 想把熔炉放进下界门内") 放置前
        // 兜底检查: 目标位命中放置保护 (含门体黑名单) → 拒绝交给 Baritone builder。
        // 根因: builder 放置前会"清场"——目标位方块状态不匹配就挖掉, 门内紫色方块
        // 被替换/门框被挖, 门就熄了。选点端 canPlace 已过滤, 此处防"选点后现场变化"
        // 与"被其他任务直接构造"两种绕过路径。
        if (refuseIfProtected(mod)) {
            setDebugState("Refusing to place at protected position");
            return null;
        }

        // Perform timeout wander
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering.");
            _progressChecker.reset();
            return _wanderTask;
        }

        // (2026-09-14 站在目标格里) 必须在 progressChecker 之前: 站在目标格内
        // 必然"无移动进展", 走 checker 会先被超时打断成 wander → 又掉回坑里
        // 死循环。这里确定性走出目标格后再交给 baritone。
        if (mod.getPlayer().getBlockPos().equals(_target)) {
            BlockPos stepOut = pickStepOutPos(mod);
            if (stepOut != null) {
                setDebugState("Standing inside target cell, stepping out first");
                return new GetToBlockTask(stepOut, false);
            }
            setDebugState("Standing inside target cell, no walkable neighbor, wandering");
            _progressChecker.reset();
            return _wanderTask;
        }

        if (_autoCollectStructureBlocks) {
            if (_materialTask != null && _materialTask.isActive() && !_materialTask.isFinished(mod)) {
                setDebugState("No structure items, collecting cobblestone + dirt as default.");
                if (getMaterialCount(mod) < PREFERRED_MATERIALS) {
                    return _materialTask;
                } else {
                    _materialTask = null;
                }
            }

            //Item[] items = Util.toArray(Item.class, mod.getClientBaritoneSettings().acceptableThrowawayItems.value);
            if (getMaterialCount(mod) < MIN_MATERIALS) {
                // TODO: Mine items, extract their resource key somehow.
                _materialTask = getMaterialTask(PREFERRED_MATERIALS);
                _progressChecker.reset();
                return _materialTask;
            }
        }


        // Check if we're approaching our point. If we fail, wander for a bit.
        if (!_progressChecker.check(mod)) {
            _failCount++;
            // (2026-09-16 放床铺平台死循环) 下方 L190 只在 `!BuilderProcess.isActive()`
            // 时才 build(...)。builder 一旦被激活, 即使它算不出任何放置方案
            // (builder=true pathing=false path[present=false], 玩家原地不动),
            // 也会永远保持 isActive → 后续每 tick 都不再重新 build → 必然再次超时,
            // 配合 TimeoutWanderTask 半径递增形成无限环。判负时强制释放 builder,
            // 让下一 tick 重新 build 一次 (目标悬空/无依附面时至少会显式失败一次)。
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            Debug.logMessage("[PlaceStuck] placement progress failed at " + _target.toShortString()
                    + " (failCount=" + _failCount + "), released baritone builder to force re-plan");
            if (!tryingAlternativeWay()) {
                Debug.logMessage("Failed to place, wandering timeout.");
                return _wanderTask;
            } else {
                Debug.logMessage("Trying alternative way of placing block...");
            }
        }


        // Place block
        if (tryingAlternativeWay()) {
            setDebugState("Alternative way: Trying to go above block to place block.");
            return new GetToBlockTask(_target.up(), false);
        } else {
            setDebugState("Letting baritone place a block.");

            // Perform baritone placement
            if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
                Debug.logInternal("Run Structure Build");
                ISchematic schematic = new PlaceStructureSchematic(mod);
                mod.getClientBaritone().getBuilderProcess().build("structure", schematic, _target);
            }
        }

        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getBuilderProcess().onLostControl();
    }

    //TODO: Place structure where a leaf block was???? Might need to delete the block first if it's not empty/air/water.

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceBlockTask task) {
            return task._target.equals(_target) && task._useThrowaways == _useThrowaways && Arrays.equals(task._toPlace, _toPlace);
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        assert MinecraftClient.getInstance().world != null;
        // (2026-09-05 传送门放置硬守卫) 目标位受放置保护 → 直接放弃 (视作完成,
        // 让上层另选点位)。否则上层 (如 DoStuffInContainerTask) 会因本任务"永远
        // 未完成"而卡死在放置分支。
        if (refuseIfProtected(mod)) {
            return true;
        }
        if (_useThrowaways) {
            return WorldHelper.isSolid(mod, _target);
        }
        BlockState state = mod.getWorld().getBlockState(_target);
        return ArrayUtils.contains(_toPlace, state.getBlock());
    }

    @Override
    protected String toDebugString() {
        return "Place structure" + ArrayUtils.toString(_toPlace) + " at " + _target.toShortString();
    }

    /** (2026-09-12 诊断) 放置目标位, 供 snapshot 打印目标格方块/可放置面现场。 */
    @Override
    public net.minecraft.util.math.BlockPos getDiagTargetBlock() {
        return _target;
    }

    private boolean tryingAlternativeWay() {
        return _failCount % 4 == 3;
    }

    /**
     * (2026-09-05 传送门放置硬守卫) 目标位命中放置保护 (门体黑名单/显式保护区/
     * 任务栈放置避让) → 记一次 [PortalGuard] 日志并返回 true。onTick 与 isFinished
     * 共用, 保证无论从哪条路进都能留下拒绝痕迹且只记一次。
     */
    private boolean refuseIfProtected(AltoClef mod) {
        if (mod.getExtraBaritoneSettings().shouldAvoidPlacingAt(_target)) {
            if (!_protectedRefusalLogged) {
                _protectedRefusalLogged = true;
                Debug.logMessage("[PortalGuard] 拒绝在受保护位置放置: " + _target.toShortString() + " (门体/显式保护区), 放弃该点位");
            }
            return true;
        }
        return false;
    }

    // (2026-09-14 站在目标格里) 水平四向, 供走出目标格用。
    private static final Vec3i[] STEP_OUT_OFFSETS = new Vec3i[]{
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)
    };

    /**
     * (2026-09-14 站在目标格里) 玩家 feet 正好站在目标格内时, vanilla 禁止往
     * 自身占据的格子放方块 → baritone 不放置也不报错, 只空转寻路 (现场
     * 20260914 传送门浇铸: 模具残块围出 1 格深坑, wander 掉进去后
     * "Failed to place, wandering timeout" 每 5s 死循环, 见
     * memory/detail/04-symptoms.md「bot 站在目标格里」)。返回一个可站立的
     * 水平邻格 (非固体 + 头顶非固体), 让 bot 先走出目标格。
     */
    private BlockPos pickStepOutPos(AltoClef mod) {
        for (Vec3i offset : STEP_OUT_OFFSETS) {
            BlockPos p = _target.add(offset);
            if (WorldHelper.isSolid(mod, p)) continue;      // 被堵
            if (WorldHelper.isSolid(mod, p.up())) continue; // 头顶被堵
            return p;
        }
        return null;
    }

    private class PlaceStructureSchematic extends AbstractSchematic {

        private final AltoClef _mod;

        public PlaceStructureSchematic(AltoClef mod) {
            super(1, 1, 1);
            _mod = mod;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState blockState, List<BlockState> available) {
            if (x == 0 && y == 0 && z == 0) {
                // Place!!
                for (BlockState possible : available) {
                    if (possible == null) continue;
                    if (_useThrowaways && _mod.getClientBaritoneSettings().acceptableThrowawayItems.value.contains(possible.getBlock().asItem())) {
                        return possible;
                    }
                    if (Arrays.asList(_toPlace).contains(possible.getBlock())) {
                        return possible;
                    }
                }
                Debug.logInternal("Failed to find throwaway block");
                // No throwaways available!!
                return new BlockOptionalMeta(Blocks.COBBLESTONE).getAnyBlockState();
            }
            // Don't care.
            return blockState;
        }
    }
}
