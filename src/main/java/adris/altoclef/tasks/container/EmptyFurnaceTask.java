package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
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
 *
 * (2026-09-09) 上层只对【非空】熔炉派发本任务 (BeatMinecraft2Task.findNearbyFurnace
 * 过滤, 空炉不触发侧线); 本任务定位熔炉时非空优先于距离, 且已锁定坐标但未贴到时
 * 直接 GetToBlockTask 走过去 (Fix-A), 不再依赖父类 tracker 型最近目标查找。
 */
public class EmptyFurnaceTask extends DoStuffInContainerTask {

    private boolean _emptied = false;
    private MineAndCollectTask _mineTask = null;

    // (2026-09-07 遗留熔炉定位) 16 格内真实熔炉的节流扫描缓存。上次会话/上次任务
    // 留下的熔炉不在本次 BlockTracker 里 (本次从未追踪过它), 父类 getNearestTracking
    // 必然漏判 → nearest 空 → 走进"新造容器"分支 (本类已禁止, 会返回 null 空转)。
    // 范围与 BeatMinecraft2Task.findNearbyFurnace 的 FURNACE_GRAB_RANGE 保持一致。
    // (2026-09-22 用户定案 16→25) 加大熔炉近场扫描半径, 降低"离开几步杀羊回来扫不到
    // 刚烧过肉的炉"的概率 (配合 8s 无进展超时兜底, 不再狱死)。
    private static final int SCAN_RANGE = 25;
    private final TimerGame _rescanTimer = new TimerGame(1.0);
    private BlockPos _cachedFurnace = null;

    // 已经贴到炉边却始终打不开 (够不着/被挡/被别的链条抢开) 的容忍时间, 超时直接
    // 拆走 — 否则本任务会站着空转, 又被上层反复重建。
    private final TimerGame _stuckOpenTimer = new TimerGame(8.0);

    // (2026-09-22 空值路径兜底, 用户"没找到容器停了 24s 冻死") 父类 DoStuffInContainerTask
    // 在 nearestMissing 且禁止新造时返回 null (不派移动/不开炉/不产子任务), 而本类
    // isFinished 又是 false → 整条链原地空转 (20260922-183317 实锤 18:43:20~44 冻 24s,
    // 直到手动推进 4 格才"再找到")。加"无进展超时": 连续 N 秒 super.onTick 都返回 null
    // (未开炉/未取空/未拆炉) → 判放弃, isFinished=true 放行父任务 (冶炼/食物) 继续,
    // 而不是冻死。_stuckOpenTimer 只管"贴炉打不开", 本计时器补"连炉都建立不了"。
    private final TimerGame _noProgressTimer = new TimerGame(4.0);
    private boolean _gaveUp = false;

    // (2026-09-09 Fix-A) 直走已知熔炉坐标的移动子任务 (同目标复用, 不每 tick new)。
    private GetToBlockTask _walkTask = null;
    private BlockPos _walkTaskTarget = null;

    public EmptyFurnaceTask() {
        super(Blocks.FURNACE, new ItemTarget(Items.FURNACE));
        // TimerGame 基类 _prevTime 默认 0, 长会话 ticks 大时初始即 elapsed → seed 一次,
        // 让 _noProgressTimer 从本任务开始计时, 避免单次瞬时 null 就误判放弃。
        _noProgressTimer.reset();
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
                adris.altoclef.util.helpers.TimeoutLog.hit("EmptyFurnaceTask.stuckOpen", "8s next to furnace without opening, mining it away");
                Debug.logToFile("altoclef_diag.log",
                        "[MAIN-FIX]\t[EmptyFurnace]\tstuck next to furnace @" + near.toShortString()
                                + " for 8s without opening it -> mining it away");
                _emptied = true;
                return null;
            }

            // (2026-09-09 Fix-A 无限wander根因修复) 已锁定熔炉坐标但还没贴到 →
            // 直接 GetToBlockTask 走过去, 绕过父类 _openTableTask 的 tracker 型最近
            // 目标查找 — 那套只认 BlockTracker 缓存 + 玩家 6 格兜底, 熔炉在两者
            // 夹缝 (本例 14 格, y 差 5) 时双 miss → "Wander for Infinity" 永不自退
            // (9月8日 23:26 铁匠铺熔炉 276,60,179 实锤, 卡 62 秒直到手动停止)。
            // 自己扫到的炉自己走过去; 贴到 4 格内才交回父类走开&打开逻辑
            // (届时 6 格兜底能命中, 正常交互)。任务超时兜底仍是上面的 8s 贴炉强拆。
            if (!open && near != null
                    && mod.getWorld().getBlockState(near).getBlock() == Blocks.FURNACE
                    && !mod.getPlayer().getBlockPos().isWithinDistance(near, 4.0)) {
                if (_walkTask == null || !near.equals(_walkTaskTarget)) {
                    _walkTaskTarget = near;
                    _walkTask = new GetToBlockTask(near);
                }
                setDebugState("Walking straight to known furnace " + near.toShortString());
                return _walkTask;
            }
        }
        Task sub = super.onTick(mod);
        if (sub != null) {
            _noProgressTimer.reset(); // 有真实子任务 → 有进展, 无进展计时清零
            return sub;
        }
        // 父类返回 null (典型: nearestMissing + 禁止新造 → "No container to take from,
        // placing is not allowed here")。此刻没有移动/开炉/取物子任务, 若不设截止时限就
        // 原地冻结 (18:43:20~44 冻 24s 实锤)。连续 N 秒无进展 → 判放弃, 让父任务继续,
        // 而不是冻死。
        if (_gaveUp) {
            return null; // 已放弃: 保持 null, 下一 tick 由 isFinished=true 释放本任务
        }
        if (_noProgressTimer.elapsed()) {
            setDebugState("Giving up emptying furnace (no findable/openable furnace)");
            Debug.logToFile("altoclef_diag.log",
                    "[MAIN-FIX]\t[EmptyFurnace]\tno-progress 4s (no findable/openable furnace) -> giving up, letting parent proceed");
            _gaveUp = true;
        }
        return null;
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
        // (2026-09-09 非空优先) 与 scanFurnaceAround 同理: 非空熔炉优先于距离,
        // 防止近场更近的空炉 (铁匠铺) 把真目标顶掉。
        if (mod.getPlayer() == null) return getTargetContainerPosition();
        BlockPos player = mod.getPlayer().getBlockPos();
        int r = 4;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos bestNonEmpty = null;
        double bestNonEmptySq = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = player.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE) {
                        double sq = pos.getSquaredDistance(player);
                        if (WorldHelper.isFurnaceNonEmpty(mod, pos)) {
                            if (sq < bestNonEmptySq) {
                                bestNonEmptySq = sq;
                                bestNonEmpty = pos;
                            }
                        } else if (sq < bestSq) {
                            bestSq = sq;
                            best = pos;
                        }
                    }
                }
            }
        }
        if (bestNonEmpty != null) {
            return bestNonEmpty;
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
     * 以玩家为中心扫 r 格立方, 返回最近的一个【可挖】真实熔炉; 没有则 null。
     * 立方是 33³ 量级, 故只在 onTick 里按 1 秒节流调用 (与 BeatMinecraft2Task
     * 的 findNearbyFurnace 同款成本/同款范围)。
     * (2026-09-09 非空优先) 本任务由"附近有非空熔炉"触发; 近场若同时有更近的
     * 空炉 (如村庄铁匠铺), 锁错空炉会把真目标漏在原地 — 非空熔炉优先于距离,
     * 空炉仅作非空全部失效时的兜底 (BE 滞后误判"空"时仍能走到并打开确认)。
     */
    private BlockPos scanFurnaceAround(AltoClef mod, BlockPos center, int r) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos bestNonEmpty = null;
        double bestNonEmptySq = Double.MAX_VALUE;
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
                    if (WorldHelper.isFurnaceNonEmpty(mod, pos)) {
                        if (sq < bestNonEmptySq) {
                            bestNonEmptySq = sq;
                            bestNonEmpty = pos;
                        }
                    } else if (sq < bestSq) {
                        bestSq = sq;
                        best = pos;
                    }
                }
            }
        }
        return bestNonEmpty != null ? bestNonEmpty : best;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-22 放弃逃生) 找不到/建不成要取空的熔炉时, 空转超时判放弃 → isFinished
        // 放行父任务 (冶炼/食物) 继续, 而不是永久冻死 (20260922-183317 实锤 24s 冻结)。
        if (_gaveUp) {
            return true;
        }
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