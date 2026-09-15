package adris.altoclef.tasks.slot;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.TurnToOpenAreaTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 丢弃光标上的物品。
 *
 * (2026-09-01 用户纠正: 丢出是朝向视角的, 不是随机初速度 — 之前我误判成随机,
 * Q键/快捷栏重设计已废弃回退) 点窗外(-999)丢光标物品的方向由玩家朝向决定,
 * 所以: 先交给独立 TurnToOpenAreaTask 转向"前方最开阔"的方向, 视角稳定后再
 * 点窗外丢出 — 物品带朝向初速度飞向开阔处, 不再落回脚下被满背包立刻捡回。
 *
 * (2026-09-03 主动挖开堆头, 用户"四周封闭无开阔空间→丢出落回脚下被重拾→卡死"):
 * 转向后若 TurnToOpenAreaTask 判定四周仍封闭 (最佳方向前方紧贴实心块), 直接丢
 * 会把物品甩到贴脸处被满背包立刻捡回。
 *
 * (2026-09-12 用户钦定隧道丢弃流程, 取代旧"挖一格碰运气"):
 *   封闭时挖掉【和自己头一样高的、面前两个方块】(d=1 与 d=2 的头高层),
 *   然后【对准这个洞的中心】(远格中心), 准星命中后丢出 —
 *   物品沿视线飞进隧道落在 ≥2 格外, 满背包拾取半径够不着, 不再反复吸回。
 *   近→远顺序挖 (同实例续租), 25s 总超时/2s 瞄准超时兜底照丢。
 *
 * (2026-09-11 弹回熔断器, 现场 11:12:53-11:13:27 实锤: 泥土"开阔"方向丢出撞
 * 3-4 格外墙壁反弹落点 1-2 格内, 原版自动拾取塞回刚腾出的槽位, 2s 一轮循环
 * 17 次直到用户干预。原版拾取拦不住, 只能丢出侧熔断):
 *   - onStart 检测光标物品 8s 内刚被扔过 (ItemHelper 记忆) = 弹回被重拾实锤;
 *   - 第 1 次弹回: 扔前先 RunAwayFromPositionTask 离开上次落点 ≥4 格再丢;
 *   - 第 2 次弹回: 该物品进入 60s 丢弃冷却 (选择器跳过, 见 ItemHelper),
 *     本轮仍照常丢出 (光标必须清空), 之后 60s 内不再主动重扔该物品。
 *
 * (2026-09-15 丢坑优先三档, 用户钦定: 现成坑→丢坑里 > 现挖一格埋掉 > 都没有
 * 才挖隧道。现场 08:48 实锤: 深板岩层四周封闭, 空手 ~7.5s/格 vs 光标滞留
 * 8s 超时 → 挖远格永远差半格被收回重捡重挖, 死循环 54s 直到手动停。):
 *   1. 现成坑: 脚下(-1层)半径 6 格扫一次, 开口/上方无盖/非液体即合格;
 *      优先 ≥2 格深的坑 (路过不会踩进去重拾), 对准坑底中心丢, 不挖任何方块。
 *   2. 现挖一格: 封闭且无现成坑 → 挖脚前一格地板成洞 (正下方非液体, 防挖开
 *      岩浆地板), 对准洞中心丢; 30s 挖不动兜底照丢。
 *   3. 隧道兜底: 连地板都没得挖 (基岩/岩浆) 才走 09-12 两格隧道流程。
 *   挖坑/隧道进行中向 PlayerInteractionFixChain 暴露 isDiggingForThrow(),
 *   豁免 8s 光标滞留超时 (防重捡死循环); 本任务自带 25s/30s 超时兜底照丢。
 *   防重拾低风险实现: 洞口目标 ≥2 格远 + 洞底比脚低 1 格 (原版拾取够不着),
 *   万一仍被重拾, 既有弹回熔断 (走开 ≥4 格 / 60s 冷却) 接管。
 */
public class ThrowCursorTask extends Task {

    private final Task _throwTask = new ClickSlotTask(Slot.UNDEFINED);
    // 独立转向任务: 先转向开阔处, 视角稳定后才丢。
    private TurnToOpenAreaTask _turnTask;
    // 主动挖通关卡的任务 (封闭时清理该方向前方实心块)。
    private DestroyBlockTask _digTask;
    // (2026-09-11 弹回熔断) 弹回后先离开上次落点再丢。
    private RunAwayFromPositionTask _runAwayTask;
    // (2026-09-11) 挖开处计数, 上限防无限挖。 → (2026-09-12 废弃: 改用户钦定隧道流程)
    // (2026-09-12 用户钦定隧道流程) 隧道目标: 近格(d=1 头高)/远格(d=2 头高)。
    private BlockPos _tunnelNear;
    private BlockPos _aimFar;
    private int _tunnelStartTick = -1;
    private int _aimStartTick = -1;
    // 空手 ~7.5s/格 × 2 格 + 富余; 超时照丢 (光标必须清空)。
    private static final int TUNNEL_TIMEOUT_TICKS = 20 * 25;
    // 瞄准就位等待上限 (lookAt 是平滑转头)。
    private static final int AIM_TIMEOUT_TICKS = 20 * 2;
    // (2026-09-12 v10) 弹回熔断"走开"步骤超时: 封闭空间 baritone 无路,
    // RunAwayFromPositionTask 永不 finished → ThrowCursor 卡死在走开这步。
    // 走不动就放弃走开, 照常丢 (死点兜底)。
    private static final int RUNAWAY_TIMEOUT_TICKS = 20 * 5;
    private int _runAwayStartTick = -1;
    // (2026-09-11) 丢出记录只做一次 (转向/挖格期间会多次走到 _throwTask 分支)。
    private boolean _throwRecorded = false;
    // (2026-09-15 丢坑优先) 现成坑/现挖洞的瞄准目标。
    private BlockPos _pitHole;
    private int _pitAimStartTick = -1;
    // 现成坑每实例只扫一次 (扫描是全半径方块循环, 不每 tick 做)。
    private boolean _pitScanned = false;
    // (2026-09-15 现挖一格) 封闭且无现成坑 → 挖脚前一格地板成洞。
    private BlockPos _digFloor;
    private int _digFloorStartTick = -1;
    // 现挖一格超时: 有 8s 豁免后空手一格 7.5s 足够, 30s 已是极端冗余 → 兜底照丢。
    private static final int DIG_FLOOR_TIMEOUT_TICKS = 20 * 30;
    // 现成坑扫描半径 / 最小水平距离 (太近丢进去也可能被拾取半径够到)。
    private static final int PIT_SCAN_RADIUS = 6;
    private static final int PIT_MIN_DIST = 2;
    // (2026-09-15) 豁免标记: 挖坑/隧道进行中, PlayerInteractionFixChain 不收回光标。
    private static ThrowCursorTask _activeInstance;

    /** 挖坑/隧道进行中 → 8s 光标滞留超时豁免 (7.5s/格 vs 8s 超时死循环, 见类注释)。 */
    public static boolean isDiggingForThrow() {
        ThrowCursorTask t = _activeInstance;
        return t != null && (t._tunnelNear != null || t._digFloor != null || t._pitHole != null);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _turnTask = new TurnToOpenAreaTask();
        _digTask = null;
        _runAwayTask = null;
        _throwRecorded = false;
        _runAwayStartTick = -1;
        _tunnelNear = null;
        _aimFar = null;
        _tunnelStartTick = -1;
        _aimStartTick = -1;
        _pitHole = null;
        _pitAimStartTick = -1;
        _pitScanned = false;
        _digFloor = null;
        _digFloorStartTick = -1;
        _activeInstance = this;
        // 弹回检测: 光标物品 8s 内刚被扔过 → 计入弹回, 视弹回次数升级处理。
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty() && mod.getPlayer() != null) {
            Item item = cursor.getItem();
            long lastThrow = ItemHelper.getLastCursorThrowMs(item);
            long now = System.currentTimeMillis();
            if (lastThrow > 0 && now - lastThrow <= ItemHelper.getThrowBounceWindowMs()) {
                ItemHelper.notifyGarbageReturned(item);
                BlockPos lastDrop = ItemHelper.getLastThrowDropPos(item);
                if (lastDrop != null
                        && lastDrop.isWithinDistance(mod.getPlayer().getBlockPos(), 6)) {
                    // 先离开上次落点 ≥4 格, 再走正常转向+丢弃流程。
                    _runAwayTask = new RunAwayFromPositionTask(4, lastDrop);
                    Debug.logMessage("(ThrowCursorTask) [ThrowBreaker] " + item
                            + " bounced back within " + (now - lastThrow) / 1000 + "s — walking away from last drop pos before rethrow");
                }
            }
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // 0) (2026-09-11) 弹回后先离开上次落点, 走开后再转向丢弃。
        //    (2026-09-12 v10) 封闭空间兜底: baritone 无路时 RunAway 永不完成 →
        //    卡死死点。5s 走不动就放弃, 直接进入转向+丢弃流程。
        if (_runAwayTask != null) {
            if (_runAwayStartTick < 0) _runAwayStartTick = WorldHelper.getTicks();
            if (!_runAwayTask.isFinished(mod)
                    && WorldHelper.getTicks() - _runAwayStartTick < RUNAWAY_TIMEOUT_TICKS) {
                return _runAwayTask;
            }
            _runAwayTask = null;
            _turnTask = new TurnToOpenAreaTask();
        }
        // 0.5) (2026-09-15 丢坑优先档 1) 一次性扫描现成坑: 脚下(-1层)半径 6 格内,
        //      开口/上方无盖/非液体即合格, 优先 ≥2 格深 (路过不会踩进去重拾)。
        //      找到就不挖任何方块, 直接对准坑底中心丢。
        if (!_pitScanned && mod.getPlayer() != null) {
            _pitScanned = true;
            BlockPos pit = findNearbyPit(mod);
            if (pit != null) {
                _pitHole = pit;
                Debug.logMessage("(ThrowCursorTask) [ThrowPit] using pit " + pit.toShortString()
                        + " depth=" + getPitDepth(mod, pit));
            }
        }
        // 1) (2026-09-15) 有洞口目标 (现成坑 / 现挖脚前洞) → 对准洞底中心, 命中即丢。
        if (_pitHole != null) {
            if (_pitAimStartTick < 0) _pitAimStartTick = WorldHelper.getTicks();
            LookHelper.lookAt(mod, new Vec3d(_pitHole.getX() + 0.5, _pitHole.getY() + 0.2,
                    _pitHole.getZ() + 0.5));
            if (LookHelper.isLookingAt(mod, _pitHole)
                    || WorldHelper.getTicks() - _pitAimStartTick > AIM_TIMEOUT_TICKS) {
                _pitHole = null;
                return throwNow(mod);
            }
            setDebugState("Throw: aiming into pit " + _pitHole.toShortString());
            return null;
        }
        // 1.5) (2026-09-15 丢坑优先档 2) 现挖一格: 封闭且无现成坑 → 挖脚前一格地板
        //      成洞 (选点时已排除岩浆正上方) → 转 1) 丢洞里。30s 挖不动兜底照丢。
        if (_digFloor != null) {
            if (WorldHelper.getTicks() - _digFloorStartTick > DIG_FLOOR_TIMEOUT_TICKS) {
                Debug.logMessage("(ThrowCursorTask) [ThrowDigFloor] timeout, throwing anyway");
                _digFloor = null;
                return throwNow(mod);
            }
            if (!WorldHelper.isSolid(mod, _digFloor)) {
                // 挖穿了 → 洞口即目标, 下一 tick 走瞄准。
                _pitHole = _digFloor;
                _pitAimStartTick = -1;
                _digFloor = null;
                return null;
            }
            if (!WorldHelper.canBreak(mod, _digFloor)) {
                // 选点时已查过 canBreak, 这里只是防御: 直接兜底照丢。
                _digFloor = null;
                return throwNow(mod);
            }
            _digTask = new DestroyBlockTask(_digFloor);
            setDebugState("Throw: digging floor pit " + _digFloor.toShortString());
            return _digTask;
        }
        // 1) (2026-09-12 用户钦定流程) 隧道丢弃: 挖面前两个头高方块 (d=1,d=2)
        //    → 对准洞中心 (远格中心) → 丢。顺序: 近格→远格→瞄准→丢。
        //    洞打穿后物品沿视线飞进隧道落在 ≥2 格外, 满背包拾取半径够不着,
        //    不再"丢在脚边→立刻吸回"。
        if (_aimFar != null) {
            // 总超时兜底: 空手 7.5s/格 × 2 + 瞄准富余。超时就照丢 (光标必须清空)。
            if (WorldHelper.getTicks() - _tunnelStartTick > TUNNEL_TIMEOUT_TICKS) {
                Debug.logMessage("(ThrowCursorTask) [ThrowTunnel] timeout, throwing anyway");
                resetTunnel();
                return throwNow(mod);
            }
            // 近格还实心 → 挖近格。
            if (WorldHelper.isSolid(mod, _tunnelNear)) {
                if (!WorldHelper.canBreak(mod, _tunnelNear)) {
                    // 不可挖 (基岩/保护方块) → 隧道放弃, 直接丢兜底。
                    resetTunnel();
                    return throwNow(mod);
                }
                _digTask = new DestroyBlockTask(_tunnelNear);
                setDebugState("Throw: digging tunnel near " + _tunnelNear);
                return _digTask;
            }
            // 远格还实心 → 挖远格。
            if (WorldHelper.isSolid(mod, _aimFar)) {
                if (!WorldHelper.canBreak(mod, _aimFar)) {
                    resetTunnel();
                    return throwNow(mod);
                }
                _digTask = new DestroyBlockTask(_aimFar);
                setDebugState("Throw: digging tunnel far " + _aimFar);
                return _digTask;
            }
            // 洞已打穿 (两格都空) → 每 tick 对准远格中心, 准星命中即丢。
            if (_aimStartTick < 0) _aimStartTick = WorldHelper.getTicks();
            LookHelper.lookAt(mod, new Vec3d(_aimFar.getX() + 0.5, _aimFar.getY() + 0.5,
                    _aimFar.getZ() + 0.5));
            if (LookHelper.isLookingAt(mod, _aimFar)
                    || WorldHelper.getTicks() - _aimStartTick > AIM_TIMEOUT_TICKS) {
                resetTunnel();
                return throwNow(mod);
            }
            setDebugState("Throw: aiming at tunnel center");
            return null;
        }
        // 2) 转向中: 完成前持续转向。
        if (_turnTask != null && !_turnTask.isFinished(mod)) {
            return _turnTask;
        }
        // 3) 转向完成。判定封闭 → 建立隧道目标 (近/远两格头高), 交给上面流程。
        if (_turnTask != null) {
            boolean enclosed = _turnTask.isEnclosed();
            float bestYaw = _turnTask.getBestYaw();
            _turnTask = null;
            if (enclosed && mod.getPlayer() != null) {
                // (2026-09-15 丢坑优先档 2 先于档 3) 封闭 → 先试挖脚前一格地板成洞,
                // 没有可挖地板 (全是基岩/岩浆正上方) 才退到两格隧道兜底。
                BlockPos floor = findFloorPitCandidate(mod);
                if (floor != null) {
                    _digFloor = floor;
                    _digFloorStartTick = WorldHelper.getTicks();
                    return null; // 下一 tick 进入 1.5) 挖地板。
                }
                double rad = Math.toRadians(bestYaw);
                int dx = (int) Math.round(-Math.sin(rad));
                int dz = (int) Math.round(Math.cos(rad));
                BlockPos p = mod.getPlayer().getBlockPos();
                // 头高层 = 脚下 +1: 丢弃物品沿胸前 (y=+1) 飞行, 挡路的正是这层。
                _tunnelNear = p.add(dx, 1, dz);
                _aimFar = p.add(dx * 2, 1, dz * 2);
                _tunnelStartTick = WorldHelper.getTicks();
                _aimStartTick = -1;
                return null; // 下一 tick 进入隧道流程 (挖近格)。
            }
            // 开阔 → 直接丢。
            return throwNow(mod);
        }
        // 4) 兜底: 无转向任务 → 直接丢。
        return throwNow(mod);
    }

    /** 清掉隧道状态 (超时/不可挖/已丢弃), 回到普通直接丢路径。 */
    private void resetTunnel() {
        _tunnelNear = null;
        _aimFar = null;
        _digTask = null;
        _aimStartTick = -1;
    }

    // (2026-09-11) 真正进入丢出前的唯一记录点: 记录物品+落点 (供弹回熔断用)。
    private Task throwNow(AltoClef mod) {
        if (!_throwRecorded && mod.getPlayer() != null) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (!cursor.isEmpty()) {
                ItemHelper.recordCursorThrown(cursor.getItem(), mod.getPlayer().getBlockPos());
            }
            _throwRecorded = true;
        }
        return _throwTask;
    }

    // (2026-09-12 挖墙选点逻辑废弃: findFirstWallInBestDir 删除, 改为用户钦定的
    //  "挖面前两个头高方块 → 对准洞中心 → 丢" 隧道流程, 见 onTick 分支 1/3。)

    /** (2026-09-15) 找脚下(-1层)的现成坑: 开口、上方无盖、非液体; 优先 ≥2 格深的。 */
    private BlockPos findNearbyPit(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        BlockPos bestShallow = null, bestDeep = null;
        double bestShallowDist = Double.MAX_VALUE, bestDeepDist = Double.MAX_VALUE;
        for (int dx = -PIT_SCAN_RADIUS; dx <= PIT_SCAN_RADIUS; dx++) {
            for (int dz = -PIT_SCAN_RADIUS; dz <= PIT_SCAN_RADIUS; dz++) {
                double distSq = dx * dx + dz * dz;
                if (distSq < PIT_MIN_DIST * (double) PIT_MIN_DIST) continue;
                BlockPos h = feet.add(dx, -1, dz);
                if (WorldHelper.isSolid(mod, h)) continue;          // 不是开口
                if (WorldHelper.isSolid(mod, h.up())) continue;     // 上方有盖, 丢不进
                if (!mod.getWorld().getBlockState(h).getFluidState().isEmpty()) continue;
                if (!mod.getWorld().getBlockState(h.up()).getFluidState().isEmpty()) continue;
                if (getPitDepth(mod, h) >= 2) {
                    if (distSq < bestDeepDist) {
                        bestDeepDist = distSq;
                        bestDeep = h;
                    }
                } else if (distSq < bestShallowDist) {
                    bestShallowDist = distSq;
                    bestShallow = h;
                }
            }
        }
        return bestDeep != null ? bestDeep : bestShallow;
    }

    /** (2026-09-15) 坑深: 从洞口向下数连续非固体格数 (上限 4, 只为排序用)。 */
    private int getPitDepth(AltoClef mod, BlockPos h) {
        int depth = 0;
        BlockPos cur = h.down();
        while (depth < 4 && !WorldHelper.isSolid(mod, cur)) {
            depth++;
            cur = cur.down();
        }
        return depth;
    }

    /**
     * (2026-09-15 现挖一格) 脚前一格地板候选: 当前朝向优先, 其余四向兜底。
     * 要求可挖、正下方非液体 (防挖开岩浆地板掉自己进去)。
     */
    private BlockPos findFloorPitCandidate(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        double rad = Math.toRadians(mod.getPlayer().getYaw());
        int fx = (int) Math.round(-Math.sin(rad));
        int fz = (int) Math.round(Math.cos(rad));
        int[][] dirs = { {fx, fz}, {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] d : dirs) {
            BlockPos f = feet.add(d[0], -1, d[1]);
            if (!WorldHelper.isSolid(mod, f)) continue;   // 已是洞
            if (!WorldHelper.canBreak(mod, f)) continue;
            if (!mod.getWorld().getBlockState(f.down()).getFluidState().isEmpty()) continue;
            return f;
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // (2026-09-15) 实例结束必须摘掉豁免标记, 否则 8s 光标超时永久失效。
        if (_activeInstance == this) {
            _activeInstance = null;
        }
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof ThrowCursorTask;
    }

    @Override
    protected String toDebugString() {
        return "Throwing Cursor";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _throwTask.isFinished(mod);
    }
}
