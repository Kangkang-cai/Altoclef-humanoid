package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
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
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(3, true); // This can get stuck forever, so we increase the range.
    private Task _materialTask;
    private int _failCount = 0;

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

    /** (2026-08-23) 可作为 CAST_FRAME 支撑的结构方块: 泥土/圆石/深板岩原石/下界岩。
     *  (2026-08-26 讨论: 沙子/沙砾不能加入 — 重力方块水平搭路(悬空)时无支撑会
     *  落下去, 桥搭不起来反而更危险。只能用于有下方支撑的填埋场景, 需单独机制。) */
    private static boolean isStructureMaterial(Item item) {
        return item == Items.DIRT || item == Items.COBBLESTONE
                || item == Items.COBBLED_DEEPSLATE || item == Items.NETHERRACK;
    }

    public static Task getMaterialTask(int count) {
        return TaskCatalogue.getSquashedItemTask(new ItemTarget(Items.DIRT, count), new ItemTarget(Items.COBBLESTONE, count),
                new ItemTarget(Items.COBBLED_DEEPSLATE, count), new ItemTarget(Items.NETHERRACK, count));
    }

    @Override
    protected void onStart(AltoClef mod) {
        _progressChecker.reset();
        // If we get interrupted by another task, this might cause problems...
        //_wanderTask.resetWander();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // Perform timeout wander
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering.");
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

    private boolean tryingAlternativeWay() {
        return _failCount % 4 == 3;
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
