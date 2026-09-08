package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.slot.ClickSlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * (2026-08-31) 收熔炉前的"取尽内容物"任务。
 *
 * 用途: BeatMinecraft2Task 里"顺路收熔炉"分支在拆炉之前先派本任务, 把炉内
 * input / fuel / output 三个槽里的物品【全部】取出放进背包, 确认取尽后才拆炉
 * 带走。用户实测: 熔炼 9 铁锭到一半被 "Pick up furnace while we're at it"
 * 拦腰打断, 炉内还烧着/还没取走的肉被连炉一起挖掉丢失 (2026-08-31 12:42 日志)。
 *
 * 流程: 打开最近真实熔炉 → 逐槽 PICKUP 取物 → 光标物品放回背包 → 三槽全空即
 * _emptied → 关屏 → 用 MineAndCollectTask 把已清空的熔炉拆走。全程复用
 * DoStuffInContainerTask 的"走到/打开容器"逻辑与 ClickSlotTask 槽位点击, 不新造轮子。
 */
public class EmptyFurnaceTask extends DoStuffInContainerTask {

    private boolean _emptied = false;
    private MineAndCollectTask _mineTask = null;

    // (2026-09-07 遗留熔炉定位) 16 格内真实熔炉的节流扫描缓存。上次会话/上次任务
    // 留下的熔炉不在本次 BlockTracker 里 (本次从未追踪过它), 父类 getNearestTracking
    // 必然漏判 → nearest 空 → 走进"新造容器"分支 (本类已禁止, 会返回 null 空转)。
    // 范围与 BeatMinecraft2Task.findNearbyFurnace 的 FURNACE_GRAB_RANGE 保持一致。
    private static final int SCAN_RANGE = 16;
    private final TimerGame _rescanTimer = new TimerGame(1.0);
    private BlockPos _cachedFurnace = null;

    // 已经贴到炉边却始终打不开 (够不着/被挡/被别的链条抢开) 的容忍时间, 超时直接
    // 拆走 — 否则本任务会站着空转, 又被上层反复重建。
    private final TimerGame _stuckOpenTimer = new TimerGame(8.0);

    public EmptyFurnaceTask() {
        super(Blocks.FURNACE, new ItemTarget(Items.FURNACE));
    }

    @Override
    protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
        return other instanceof EmptyFurnaceTask;
    }

    @Override
    protected boolean isContainerOpen(AltoClef mod) {
        return mod.getPlayer() != null && mod.getPlayer().currentScreenHandler instanceof FurnaceScreenHandler;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-09-07) 内容已取尽 / 确认打不开 → 锁定"拆炉"阶段, 不再回容器分支反复打开。
        if (_emptied) {
            if (_mineTask == null) {
                // (2026-09-07) 熔炉必须用镐才掉落物品: 旧值 MiningRequirement.HAND
                // 会让 bot 空手/错工具去挖, 挖完不掉熔炉 → 永远收不到, 触发条件不消失。
                _mineTask = new MineAndCollectTask(Items.FURNACE, 1, new Block[]{Blocks.FURNACE}, MiningRequirement.WOOD);
            }
            setDebugState("Mining the emptied furnace away");
            return _mineTask;
        }

        // 刷新近场熔炉缓存, 并在"贴着炉子却长时间打不开"时兜底强拆。
        if (mod.getPlayer() != null && mod.getWorld() != null) {
            if (_rescanTimer.elapsed()) {
                _rescanTimer.reset();
                _cachedFurnace = scanFurnaceAround(mod, mod.getPlayer().getBlockPos(), SCAN_RANGE);
            }
            boolean open = isContainerOpen(mod);
            BlockPos near = _cachedFurnace;
            if (open || near == null || !mod.getPlayer().getBlockPos().isWithinDistance(near, 4.0)) {
                // 界面开着、或还没走到炉边 → 不算卡住, 计时重置。
                _stuckOpenTimer.reset();
            } else if (_stuckOpenTimer.elapsed()) {
                Debug.logToFile("altoclef_diag.log",
                        "[MAIN-FIX]\t[EmptyFurnace]\tstuck next to furnace @" + near.toShortString()
                                + " for 8s without opening it -> mining it away");
                _emptied = true;
                return null;
            }
        }
        return super.onTick(mod);
    }

    @Override
    protected boolean isAllowedToPlaceNewContainer() {
        // 我们是来"取空并拆走眼前真实熔炉"的, 绝不为了取空而先放一个新炉 —
        // 那等于自己给自己造活干, 且新炉让"附近还有炉可收"恒成立 (死循环)。
        return false;
    }

    /** (2026-09-08) 拆炉子收集任务是否因无目标空转而放弃 (供上层触发冷却)。 */
    public boolean gaveUpWandering() {
        return _mineTask != null && _mineTask.gaveUpWandering();
    }

    @Override
    protected Task containerSubTask(AltoClef mod) {
        // 光标先清回背包 (PICKUP 拿起的东西要落袋才算取走)。
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false)
                    .or(() -> StorageHelper.getGarbageSlot(mod));
            if (toFit.isPresent()) {
                setDebugState("Storing furnace content");
                return new ClickSlotTask(toFit.get());
            }
            setDebugState("Closing screen to free cursor");
            StorageHelper.closeScreen();
            return null;
        }

        // 逐个槽取尽: input / fuel / output。燃料槽(FUEL)同样必须取走 — 用户实测
        // "收熔炉时只拿产出入包、燃料留在炉里", 收炉必须连燃料一起带走。
        for (Slot slot : new Slot[]{FurnaceSlot.INPUT_SLOT_MATERIALS, FurnaceSlot.INPUT_SLOT_FUEL, FurnaceSlot.OUTPUT_SLOT}) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!stack.isEmpty()) {
                setDebugState("Taking " + stack.getItem() + " out of furnace");
                String slotName = (slot == FurnaceSlot.INPUT_SLOT_FUEL) ? "FUEL"
                        : (slot == FurnaceSlot.INPUT_SLOT_MATERIALS) ? "INPUT" : "OUTPUT";
                Debug.logToFile("altoclef_diag.log",
                        "[MAIN-FIX]\t[EmptyFurnace]\tTaking " + slotName + " " + stack.getItem() + " x" + stack.getCount());
                return new ClickSlotTask(slot, SlotActionType.PICKUP);
            }
        }

        // 三槽(input/fuel/output)全空 → 确认取尽(含燃料), 关屏去拆炉。
        _emptied = true;
        setDebugState("Furnace emptied");
        Debug.logToFile("altoclef_diag.log",
                "[MAIN-FIX]\t[EmptyFurnace]\tFurnace emptied (input/fuel/output all taken), mining it away");
        StorageHelper.closeScreen();
        return null;
    }

    @Override
    protected double getCostToMakeNew(AltoClef mod) {
        // 我们是去"取空并拆走眼前真实熔炉", 绝不合成新的。
        return 9999999;
    }

    @Override
    protected BlockPos overrideContainerPosition(AltoClef mod) {
        // 优先锁定玩家近场真实存在的熔炉 (与 DoSmeltInFurnaceTask 同款兜底), 不依赖
        // 异步缓存; 正在收熔炉的场景熔炉必在近场。
        if (mod.getPlayer() == null) return getTargetContainerPosition();
        BlockPos player = mod.getPlayer().getBlockPos();
        int r = 4;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = player.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE) {
                        double sq = pos.getSquaredDistance(player);
                        if (sq < bestSq) {
                            bestSq = sq;
                            best = pos;
                        }
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        // (2026-09-07 遗留熔炉) 4 格内没有 → 用 16 格节流扫描缓存。本次从未追踪过的
        // 熔炉 (上次任务/上次会话留下) 不在 BlockTracker 里, 父类取 nearest 会漏掉它。
        if (_cachedFurnace != null && WorldHelper.canReach(mod, _cachedFurnace)) {
            return _cachedFurnace;
        }
        return getTargetContainerPosition();
    }

    /**
     * 以玩家为中心扫 r 格立方, 返回最近的一个【可挖】的真实熔炉; 没有则 null。
     * 立方是 33³ 量级, 故只在 onTick 里按 1 秒节流调用 (与 BeatMinecraft2Task
     * 的 findNearbyFurnace 同款成本/同款范围)。
     */
    private BlockPos scanFurnaceAround(AltoClef mod, BlockPos center, int r) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(pos).getBlock() != Blocks.FURNACE) {
                        continue;
                    }
                    if (!WorldHelper.canBreak(mod, pos)) {
                        continue;
                    }
                    double sq = pos.getSquaredDistance(center);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-07 死循环根因修复) 取尽内容后绝不能立刻算完成。
        // 旧实现 `return _emptied`: 取尽的那一 tick 就返回 true, 上层
        // BeatMinecraft2Task 见 isFinished 立刻丢弃本实例另建一个新的,
        // 而"拆炉"子任务 _mineTask 要等取尽后的下一 tick 才创建 → 拆炉阶段
        // 永远没有执行机会, 熔炉永远留在世界里, "附近还有炉可收"的触发条件
        // 永不消失 (2026-09-07 23:07 日志实锤: 60 秒内 3 轮 "Furnace emptied",
        // 0 次 "Mining the emptied furnace away", 期间还被打去放新炉两次)。
        // 因此: 取尽之后必须等拆炉任务真正跑完才算完成。
        if (_emptied) {
            return _mineTask != null && _mineTask.isFinished(mod);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Emptying furnace before removing it";
    }
}