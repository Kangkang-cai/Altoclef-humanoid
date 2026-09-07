package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.chains.MobDefenseChain;
import adris.altoclef.tasks.entity.TowerKillEntityTask;
import adris.altoclef.tasks.misc.EatItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-07 塔式逃生, 用户设计) 被纯近战怪围殴时 (无远程/无苦力怕) 的
 * 确定性逃生策略: 逃到空旷处 → 跳柱搭高 3 块悬空方块 → 塔上进食 → 塔上打怪。
 *
 * 替代场景: 旧逃跑链失败后升级为挖通道 (DigOutEscapeTask), 被追杀时挖洞
 * 来不及, 导航还会钻进墙里 (2026-09-07 实测 3 小僵尸+蜘蛛追杀致死)。
 *
 * 四阶段状态机 (参考 ShelterAndRecoverTask 的带超时设计, 绝不死循环):
 *   0 FIND_OPEN: 用现成的动态逃跑 goal (GoalRunAwayFromEntities, 边跑边
 *                重算, 天然"先跑着") 拉开距离, 每 tick 采样当前位置是否
 *                可搭柱 (脚下实心 + 头顶 5 格无实体 + 不在水/岩浆) ——
 *                小范围边跑边看, 不是一次性大范围搜索, 找到立即停。
 *   1 BUILD:     经典 pillar-jump。状态无关设计: 每 tick 直接从世界状态
 *                重算"脚下连续 3 块悬空方块是否达成", 被打断重入无需断点
 *                续传 (吸取 64290c94 教训: 状态尽量无状态化)。
 *   2 EAT:       塔上安全后前台进食 (EatItemTask, 现成组件)。
 *   3 FIGHT:     站桩版击杀 (TowerKillEntityTask, 蜘蛛优先), 不走位。
 *
 * 中途出现远程怪/苦力怕 → 立即中止 (ABORT_THREAT): 塔已完成时由
 * MobDefenseChain 转 SHELTER (ShelterAndRecoverTask 会在塔顶四面+顶盖
 * 封箱, 天然掩体); 塔未完成时降级 LONG_RETREAT。
 */
public class TowerEscapeTask extends Task {

    public enum Result { RUNNING, SUCCESS, ABORT_THREAT, TIMEOUT }

    // 可放置方块 (与 ShelterAndRecoverTask 同款清单, 不用会下落的沙/沙砾)。
    private static final Item[] TOWER_ITEMS = new Item[]{
            Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.STONE, Items.COBBLED_DEEPSLATE
    };

    // 用户拍板: 脚下 3 块"悬空方块"(水平四方向无方块) → 近战怪不可能上来。
    private static final int TOWER_HEIGHT = 3;
    // 空旷判定: 头顶 5 格无实体方块 (搭 3 格 + 跳跃余量 + 站立 2 格)。
    private static final int HEADROOM = 5;
    // 塔上监视半径: 出现远程怪/苦力怕即中止塔策略。
    private static final double THREAT_WATCH_RANGE = 20;

    private static final int FIND_TIMEOUT_TICKS = 20 * 12;
    private static final int BUILD_TIMEOUT_TICKS = 20 * 15;
    private static final int EAT_TIMEOUT_TICKS = 20 * 30;
    private static final int FIGHT_TIMEOUT_TICKS = 20 * 45;

    private int _phase = 0; // 0=FIND_OPEN 1=BUILD 2=EAT 3=FIGHT
    private long _phaseStartTick = -1;
    private Result _result = Result.RUNNING;

    private RunAwayFromHostilesTask _runTask;
    private EatItemTask _eatTask;
    private TowerKillEntityTask _killTask;

    @Override
    protected void onStart(AltoClef mod) {
        // 只重置阶段推进; 不需要"断点续传"字段 —— BUILD 的完成判定每 tick
        // 从世界状态重算, 中断重入天然安全。
        _phase = 0;
        _result = Result.RUNNING;
        _phaseStartTick = WorldHelper.getTicks();
        _runTask = null;
        _eatTask = null;
        _killTask = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long nowTick = WorldHelper.getTicks();

        // 任意阶段: 出现远程怪/苦力怕 (20 格内) → 立即中止。
        // (塔完成后 bot 在 3 格高塔上, 链层会转 SHELTER 封顶; 未完成则降级长跑。)
        if (rangedOrCreeperNear(mod)) {
            abort(mod, Result.ABORT_THREAT, "ranged threat / creeper appeared");
            return null;
        }

        switch (_phase) {
            case 0: { // FIND_OPEN: 边跑边看, 找到可搭柱的点立即停。
                if (nowTick - _phaseStartTick > FIND_TIMEOUT_TICKS) {
                    abort(mod, Result.TIMEOUT, "no pillarable spot found");
                    return null;
                }
                if (isPillarableSpot(mod)) {
                    Debug.logMessage("[TowerEscape] pillarable spot found, building up.");
                    cancelRun(mod);
                    _phase = 1;
                    _phaseStartTick = nowTick;
                    return null;
                }
                // 动态逃跑 goal: 随怪物位置实时重算 (现成"先跑着"机制)。
                if (_runTask == null || _runTask.isFinished(mod)) {
                    _runTask = new RunAwayFromHostilesTask(12);
                }
                setDebugState("Tower escape: running to open area");
                return _runTask;
            }
            case 1: { // BUILD: pillar-jump 搭高。
                if (nowTick - _phaseStartTick > BUILD_TIMEOUT_TICKS) {
                    abort(mod, Result.TIMEOUT, "build timeout");
                    return null;
                }
                if (isTowerComplete(mod)) {
                    mod.getInputControls().release(Input.JUMP);
                    Debug.logMessage("[TowerEscape] tower complete (3 floating blocks), eating.");
                    _phase = 2;
                    _phaseStartTick = nowTick;
                    return null;
                }
                pillarStep(mod);
                setDebugState("Tower escape: pillaring up");
                return null;
            }
            case 2: { // EAT: 塔上安全, 前台进食。
                if (nowTick - _phaseStartTick > EAT_TIMEOUT_TICKS || !mod.getFoodChain().needsToEat(mod)) {
                    _phase = 3;
                    _phaseStartTick = nowTick;
                    _eatTask = null;
                    return null;
                }
                if (_eatTask == null) {
                    _eatTask = new EatItemTask(20);
                }
                setDebugState("Tower escape: eating on tower");
                return _eatTask;
            }
            case 3: // FIGHT: 站桩击杀, 16 格内清空即成功。
            default: {
                if (!hostilesNear(mod, 16)) {
                    mod.getInputControls().release(Input.JUMP);
                    Debug.logMessage("[TowerEscape] hostiles cleared, tower escape success.");
                    _result = Result.SUCCESS;
                    return null;
                }
                if (nowTick - _phaseStartTick > FIGHT_TIMEOUT_TICKS) {
                    // 打不完也视为成功 (塔上是安全位), 交还链层重新评估。
                    _result = Result.SUCCESS;
                    return null;
                }
                if (_killTask == null) {
                    _killTask = new TowerKillEntityTask();
                }
                setDebugState("Tower escape: fighting from tower");
                return _killTask;
            }
        }
    }

    /** 中止: 松开跳跃、取消寻路, 记录结果。 */
    private void abort(AltoClef mod, Result result, String reason) {
        Debug.logMessage("[TowerEscape] abort: " + reason + " (phase=" + _phase + ")");
        Debug.logToFile("altoclef_diag.log", "[TowerEscape] abort: " + reason + " (phase=" + _phase + ")");
        mod.getInputControls().release(Input.JUMP);
        cancelRun(mod);
        _result = result;
    }

    /** 当前位置是否可搭柱: 脚下实心、头顶 HEADROOM 格无实体、不在液体里。 */
    private boolean isPillarableSpot(AltoClef mod) {
        if (mod.getPlayer().isTouchingWater() || mod.getPlayer().isInLava()) return false;
        BlockPos p = mod.getPlayer().getBlockPos();
        if (!WorldHelper.isSolid(mod, p.down())) return false;
        for (int i = 1; i <= HEADROOM; i++) {
            if (WorldHelper.isSolid(mod, p.up(i))) return false;
        }
        return true;
    }

    /**
     * 塔完成判定 (用户定义): 脚下往下连续 TOWER_HEIGHT 块实体方块, 且每块
     * 水平四方向均无实体方块 ("悬空")。每 tick 从世界状态重算, 无本地状态。
     * public: MobDefenseChain 在塔逃结束后也要据此分流 (塔顶遇远程 → SHELTER)。
     */
    public boolean isTowerComplete(AltoClef mod) {
        BlockPos q = mod.getPlayer().getBlockPos().down();
        for (int i = 0; i < TOWER_HEIGHT; i++) {
            if (!WorldHelper.isSolid(mod, q)) return false;
            if (WorldHelper.isSolid(mod, q.north()) || WorldHelper.isSolid(mod, q.south())
                    || WorldHelper.isSolid(mod, q.east()) || WorldHelper.isSolid(mod, q.west())) {
                return false;
            }
            q = q.down();
        }
        return true;
    }

    /**
     * pillar-jump 单步: 在地面 → hold 跳; 腾空且脚下出现"空气+其下实体"
     * 的放置窗口 → 瞄准脚下块顶面 interactBlock 放置。
     */
    private void pillarStep(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        BlockPos below = feet.down();
        if (WorldHelper.isSolid(mod, below)) {
            // 站在实体上: 起跳。
            mod.getInputControls().hold(Input.JUMP);
            return;
        }
        // 腾空放置窗口: 脚下是空气、其下是实体 (跳过 1 格后的经典窗口)。
        if (WorldHelper.isSolid(mod, below.down())
                && !WorldHelper.isInsidePlayer(mod, below)
                && WorldHelper.canPlace(mod, below)) {
            // 放置瞬间松跳, 避免连跳把放置窗口抢掉。
            mod.getInputControls().release(Input.JUMP);
            tryPlaceBelow(mod, below);
        } else {
            mod.getInputControls().hold(Input.JUMP);
        }
    }

    /** 瞄准目标块顶面 → 准心落上后 interactBlock 放置 (与 PlaceBlockNearbyTask.place 同款路径)。 */
    private void tryPlaceBelow(AltoClef mod, BlockPos pos) {
        if (!StorageHelper.isEquipped(mod, TOWER_ITEMS)) {
            if (!mod.getSlotHandler().forceEquipItem(TOWER_ITEMS)) {
                return; // 没方块了 (链层入口已检查过库存, 这里是保险)
            }
        }
        Vec3d topCenter = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        LookHelper.lookAt(mod, topCenter);
        HitResult hit = MinecraftClient.getInstance().crosshairTarget;
        if (hit instanceof BlockHitResult bhit
                && bhit.getBlockPos().equals(pos)
                && MinecraftClient.getInstance().interactionManager != null) {
            ActionResult r = MinecraftClient.getInstance().interactionManager.interactBlock(
                    mod.getPlayer(), mod.getWorld(), Hand.MAIN_HAND, bhit);
            if (r == ActionResult.SUCCESS) {
                mod.getPlayer().swingHand(Hand.MAIN_HAND);
            }
        }
    }

    /** 20 格内有远程怪或苦力怕 (塔策略的禁入条件)。 */
    private boolean rangedOrCreeperNear(AltoClef mod) {
        for (Entity e : mod.getEntityTracker().getHostiles()) {
            if (e == null || !e.isAlive()) continue;
            if (!e.isInRange(mod.getPlayer(), THREAT_WATCH_RANGE)) continue;
            if (e instanceof CreeperEntity || MobDefenseChain.isRangedThreat(e)) return true;
        }
        return false;
    }

    private static boolean hostilesNear(AltoClef mod, double range) {
        for (Entity e : mod.getEntityTracker().getHostiles()) {
            if (e != null && e.isAlive() && e.isInRange(mod.getPlayer(), range)) return true;
        }
        return false;
    }

    /** 取消遗留的 Baritone 寻路 (进入站桩/搭塔阶段前调用)。 */
    private void cancelRun(AltoClef mod) {
        _runTask = null;
        try {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // 释放搭塔期 hold 的跳跃键; 子任务 (逃跑/进食/击杀) 自行收尾。
        // 不清阶段结果字段 —— 中断重入由 onStart 重置, 这里不吞状态。
        mod.getInputControls().release(Input.JUMP);
    }

    /** 塔逃可放置方块清单 (链层入口的库存检查与任务内装备共用)。 */
    public static Item[] getTowerItems() {
        return TOWER_ITEMS;
    }

    public Result getResult() {
        return _result;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _result != Result.RUNNING;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TowerEscapeTask;
    }

    @Override
    protected String toDebugString() {
        return "Tower escape (phase " + _phase + ")";
    }
}
