package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public class WorldSurvivalChain extends SingleTaskChain {

    private final TimerGame _wasInLavaTimer = new TimerGame(1);
    // (2026-09-14 防火岩浆自救取证) 逃岩浆任务运行期 2s 一条诊断节流 (static:
    // 链实例重建不洗掉)。
    private static long _lastLavaEscapeLogMs = 0;
    // (2026-09-14 坠落取证) 上一次是否处于自由落体, 用于抓"进入坠落"的边沿。
    private static boolean _wasFalling = false;
    private boolean _wasAvoidingDrowning;
    private TimerGame _portalStuckTimer = new TimerGame(5);

    private BlockPos _extinguishWaterPosition;

    // 着火期间为"找附近的水"临时跟踪 WATER 方块，火灭后停止（引用计数）。
    private boolean _wasTrackingWaterForFire = false;

    // ==== 防溺水"找空气" (2026-09-10, 13:15 场次) ====
    // 旧 handleDrowning 双重失效: (a) isPathing 闸门——baritone 寻路中完全不生效
    // (现场 pathing=true 全程); (b) 只会 hold(JUMP) 上浮, 隧道顶封死时按跳无效,
    // bot 在水下 air=-0.04 掉血 18s 淹死。新增: 空气危急(<25%)且在水中时,
    // 以高优先级导航到最近可呼吸方块 (AIR/CAVE_AIR/VOID_AIR)。
    private static final double DROWN_ESCAPE_AIR_FRACTION = 0.25;
    private static final int DROWN_ESCAPE_SEARCH_RADIUS = 16;
    private static final float DROWN_ESCAPE_PRIORITY = 95; // 低于岩浆/火焰(100), 高于常规任务
    private final TimerGame _drownReissueTimer = new TimerGame(1); // 溺水时限紧, 1s 重发冷却
    private final TimerGame _drownPathlessTimer = new TimerGame(1.5); // baritone 无路径观察期
    private boolean _loggedNoBreathable = false;

    // ==== 盾牌看门狗 (2026-09-11, 用户复盘 21:10 死于骷髅射杀) ====
    // 每 60s 检查一次: 副手+背包都没盾时, 材料够(1 铁锭+6 木板)就马上合成,
    // 材料不够跳过不管(不主动去挖铁, 用户原话"没有就跳过不管了")。
    // 背景: 死亡清空背包后旧逻辑没人补盾, 下次遇骷髅必吃箭。副手装备由
    // MobDefenseChain/AbstractKillEntityTask 现有反应式装备负责, 这里只管"造出来"。
    // prio 55: 抢占常规任务(50), 让位战斗(65+)/死亡恢复(70)/岩浆火溺水(90+)。
    private static final double SHIELD_WATCHDOG_INTERVAL = 60;
    private static final float SHIELD_WATCHDOG_PRIORITY = 55;
    private static final int SHIELD_PLANKS_REQUIRED = 6;
    private final TimerGame _shieldWatchdogTimer = new TimerGame(SHIELD_WATCHDOG_INTERVAL);
    // (2026-09-14 续租, 232015 场次) 已派发的盾牌合成任务引用。原实现只在计时
    // 到点那一 tick 返回 55, 下一 tick 落到方法末尾 NEGATIVE_INFINITY → 所有权
    // 54ms 内弹回 User Tasks (日志 23:21:35.983→36.037), 合成任务挂链上一 tick
    // 没跑, 卡死 "Waiting for recipe book click" → 半成品: 4 木板留 2x2 格 +
    // 合成台留输出格, 整场未清。铁律: "返回任务"≠"任务会跑完", 侧线任务必须续租。
    private Task _shieldCraftTask = null;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
    }

    private void stopFireWaterTracking(AltoClef mod) {
        if (!_wasTrackingWaterForFire) return;
        _wasTrackingWaterForFire = false;
        try {
            mod.getBlockTracker().stopTracking(Blocks.WATER);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean shouldTickEveryTick() {
        // 反射链：岩浆/火焰/溺水逃生需要 tick 级反应，不能降频。
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public float getPriority(AltoClef mod) {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        // Drowning
        handleDrowning(mod);

        // (2026-09-14 坠落取证) 进入自由落体的瞬间快照。用户现场"空中搭路没搭好掉进
        // 岩浆", 需要区分三种假设: 格挡抢寻路 (MobDefenseChain [ShieldVsPath]) /
        // JumpStuckResolver 误触发 / 上层拆任务。零行为, 只写诊断文件。
        traceFallEdge(mod);

        // Lava Escape
        if (isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()) {
            setTask(new EscapeFromLavaTask());
            // (2026-09-14 防火岩浆自救取证) 2s 一条: 位置/速度/防火/baritone 是否在
            // 规划。用于区分"baritone 在游"与"baritone 又 A* 起点判据全灭"。
            long nowMs = System.currentTimeMillis();
            if (nowMs - _lastLavaEscapeLogMs > 2000) {
                _lastLavaEscapeLogMs = nowMs;
                Vec3d v = mod.getPlayer().getVelocity();
                boolean pathing = false;
                try {
                    pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
                } catch (Throwable ignored) {
                }
                Debug.logToFile("altoclef_diag.log", "[LavaEscape]\tpos="
                        + mod.getPlayer().getBlockPos().toShortString()
                        + " y=" + String.format("%.2f", mod.getPlayer().getY())
                        + " vel=" + String.format("%.2f/%.2f/%.2f", v.x, v.y, v.z)
                        + " onGround=" + mod.getPlayer().isOnGround()
                        + " fireRes=" + mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                        + " onFire=" + mod.getPlayer().isOnFire()
                        + " pathing=" + pathing);
            }
            return 100;
        }

        // Fire escape
        if (isInFire(mod)) {
            setTask(new DoToClosestBlockTask(PutOutFireTask::new, Blocks.FIRE, Blocks.SOUL_FIRE));
            return 100;
        }

        // Drowning escape: 找空气 (置于岩浆/火焰之后, 那两者死得更快)
        float drownEscape = tryDrowningEscape(mod);
        if (drownEscape != Float.NEGATIVE_INFINITY) return drownEscape;

        // Extinguish with water
        if (mod.getModSettings().shouldExtinguishSelfWithWater()) {
            if (!(_mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && !mod.getWorld().getDimension().isUltrawarm()) {
                // Extinguish ourselves
                if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    BlockPos targetWaterPos = mod.getPlayer().getBlockPos();
                    if (WorldHelper.isSolid(mod, targetWaterPos.down()) && WorldHelper.canPlace(mod, targetWaterPos)) {
                        Optional<Rotation> reach = LookHelper.getReach(targetWaterPos.down(), Direction.UP);
                        if (reach.isPresent()) {
                            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                            if (mod.getClientBaritone().getPlayerContext().isLookingAt(targetWaterPos.down())) {
                                if (mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                                    _extinguishWaterPosition = targetWaterPos;
                                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                                    setTask(null);
                                    return 90;
                                }
                            }
                        }
                    }
                    // 放水条件暂时不满足（脚下不实/放不了/够不着）时不再去
                    // 找远处的水——原地等待或长距离寻路只会让 bot 高优先级
                    // 卡死（2026-08-21 用户反馈：着火时原地站着不动）。
                    // 火焰 ~8 秒自然熄灭，直接放行让正常任务继续（bot 保持
                    // 移动比站着烧好）。
                    setTask(null);
                    return Float.NEGATIVE_INFINITY;
                }
                // 没有水桶：只有 32 格内有"可见的"水才值得去泡。找不到就
                // 放行正常任务（继续寻路/干活），而不是原地 wander 卡死。
                mod.getBlockTracker().trackBlock(Blocks.WATER);
                _wasTrackingWaterForFire = true;
                Optional<BlockPos> water = mod.getBlockTracker().getNearestTracking(Blocks.WATER);
                if (water.isPresent() && water.get().isWithinDistance(mod.getPlayer().getPos(), 32)) {
                    setTask(new GetToBlockTask(water.get()));
                    return 90;
                }
                setTask(null);
                return Float.NEGATIVE_INFINITY;
            } else if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null && mod.getBlockTracker().blockIsValid(_extinguishWaterPosition, Blocks.WATER)) {
                // Pick up the water
                setTask(new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), Direction.UP, _extinguishWaterPosition.down(), true));
                return 60;
            } else {
                _extinguishWaterPosition = null;
            }
        } else if (_wasTrackingWaterForFire) {
            stopFireWaterTracking(mod);
        }
        if (!mod.getPlayer().isOnFire() && _wasTrackingWaterForFire) {
            stopFireWaterTracking(mod);
        }

        // 盾牌看门狗 (2026-09-11): 每 60s 检查, 没盾+材料够 → 合成 (prio 55)。
        // 计时器无条件重置, 材料不够也只是跳过本轮, 下轮再查。
        if (_shieldWatchdogTimer.elapsed()) {
            _shieldWatchdogTimer.reset();
            boolean offHandShield = mod.getPlayer().getOffHandStack().getItem() == Items.SHIELD;
            if (!offHandShield && !mod.getItemStorage().hasItem(Items.SHIELD)
                    && mod.getItemStorage().hasItem(Items.IRON_INGOT)
                    && mod.getItemStorage().getItemCount(ItemHelper.PLANKS) >= SHIELD_PLANKS_REQUIRED) {
                // (2026-09-14 交易让位, 232015 场次) 交易进行中插队会开背包屏打断
                // 丢金锭节奏 → 跳过本轮, 交易结束 (金锭耗尽/任务停 3s) 后下轮再合成。
                if (TradeWithPiglinsTask.isTradeInProgress()) {
                    Debug.logMessage("[Shield-Watchdog] no shield + materials present, but piglin trading in progress — deferring to next check.");
                } else {
                    Debug.logMessage("[Shield-Watchdog] no shield in inventory/offhand + materials present (iron ingot + "
                            + mod.getItemStorage().getItemCount(ItemHelper.PLANKS) + " planks), crafting one.");
                    _shieldCraftTask = TaskCatalogue.getItemTask(Items.SHIELD, 1);
                    setTask(_shieldCraftTask);
                    return SHIELD_WATCHDOG_PRIORITY;
                }
            }
        }

        // Portal stuck
        if (isStuckInNetherPortal(mod)) {
            // We can't break or place while inside a portal (not really)
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else {
            // We're no longer stuck, but we might want to move AWAY from our stuck position.
            _portalStuckTimer.reset();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        if (_portalStuckTimer.elapsed()) {
            // We're stuck inside a portal, so get out.
            // Don't allow breaking while we're inside the portal.
            setTask(new SafeRandomShimmyTask());
            return 60;
        }

        // (2026-09-14 续租) 已派发的盾牌合成未完成前持续持有 prio 55, 保证任务
        // 拿得到 tick 直至跑完。放在岩浆/火/溺水/传送门卡住等紧急分支之后: 紧急
        // 任务 (90/60) 仍可 setTask 顶掉合成 (引用比对自动放开续租), 与优先级
        // 设计一致 —— 紧急 > 合成(55) > 常规(50)。
        if (_shieldCraftTask != null) {
            if (_mainTask == _shieldCraftTask && !_shieldCraftTask.isFinished(mod)) {
                return SHIELD_WATCHDOG_PRIORITY;
            }
            // 被更高优先级紧急任务顶掉或已完成 → 放开; 材料条件仍真时下一轮
            // 看门狗 (60s 内) 会重新派发。
            _shieldCraftTask = null;
        }

        return Float.NEGATIVE_INFINITY;
    }

    /**
     * 防溺水"找空气"逃生 (2026-09-10)：水中且空气 <25% 时，以 DROWN_ESCAPE_PRIORITY
     * 导航/挖穿到最近可呼吸方块。
     * (2026-09-10 13:52 场次补强) 目标空气被封在石头后时 baritone 水下寻路
     * 长期 path[present=false] (8s+ 实证)，bot 原地漂浮淹死。补两类能力:
     *  (a) GetToBlockTask 无路径持续 >1.5s → 转直线挖穿 (eye→目标连线上
     *      reach 内第一块实体方块交给 DestroyBlockTask，挖掉一块重评，逐块打穿);
     *  (b) DestroyBlockTask 挖穿期间粘性 (setTask 等值去重防重启)。
     * 任务在跑期间不换目标防抖动；失败/被打断后经 1s 重发冷却再重找。
     * 找不到目标时不动 (留旧 handleDrowning 的按跳上浮兜底)。
     */
    private float tryDrowningEscape(AltoClef mod) {
        var p = mod.getPlayer();
        if (p == null || !p.isTouchingWater() || p.getMaxAir() <= 0) {
            return Float.NEGATIVE_INFINITY;
        }
        double airFrac = (double) p.getAir() / p.getMaxAir();
        if (airFrac > DROWN_ESCAPE_AIR_FRACTION) {
            _loggedNoBreathable = false;
            return Float.NEGATIVE_INFINITY;
        }

        // 已有逃生任务在跑:
        if (isCurrentlyRunning(mod)) {
            if (_mainTask instanceof DestroyBlockTask) {
                // 挖穿障碍中: 粘性直至挖掉或任务自身放弃 (6s 无进度自兜底)。
                return DROWN_ESCAPE_PRIORITY;
            }
            if (_mainTask instanceof GetToBlockTask) {
                if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                    _drownPathlessTimer.reset();
                    return DROWN_ESCAPE_PRIORITY; // 正常导航中
                }
                if (!_drownPathlessTimer.elapsed()) {
                    return DROWN_ESCAPE_PRIORITY; // 短暂无路径(重算中), 再观察
                }
                // 无路径超 1.5s → 掉到下方重评: 直线挖穿
            } else {
                // 其他未知任务在跑, 不干预。
                return DROWN_ESCAPE_PRIORITY;
            }
        } else if (!_drownReissueTimer.elapsed()) {
            // 冷却中: 手上有活就继续拿着 (避免让权→onInterrupt→重派 的抖动)。
            return _mainTask != null ? DROWN_ESCAPE_PRIORITY : Float.NEGATIVE_INFINITY;
        }

        BlockPos air = findNearestBreathable(mod);
        if (air == null) {
            if (!_loggedNoBreathable) {
                _loggedNoBreathable = true;
                String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                        + " 但 " + DROWN_ESCAPE_SEARCH_RADIUS + " 格内无可达呼吸方块, 退回按跳上浮兜底 pos=" + p.getBlockPos();
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
            return Float.NEGATIVE_INFINITY;
        }
        _loggedNoBreathable = false;
        BlockPos blocker = findBlockerOnLine(mod, air);
        if (blocker != null) {
            setTask(new DestroyBlockTask(blocker));
            _drownReissueTimer.reset();
            _drownPathlessTimer.reset();
            String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                    + " -> DIG THROUGH blocker " + blocker.toShortString()
                    + " (target air " + air.toShortString() + ") player=" + p.getBlockPos();
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        } else {
            setTask(new GetToBlockTask(air));
            _drownReissueTimer.reset();
            _drownPathlessTimer.reset();
            String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                    + " -> navigate to breathable " + air.toShortString()
                    + " player=" + p.getBlockPos();
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        }
        return DROWN_ESCAPE_PRIORITY;
    }

    /**
     * 直线视线 eye→target 上、reach 内的第一块实体方块 (需挖穿的障碍)。
     * 中途全为空气/水 → null (baritone 可直接寻路)。
     */
    private BlockPos findBlockerOnLine(AltoClef mod, BlockPos target) {
        Vec3d eye = mod.getPlayer().getEyePos();
        Vec3d to = Vec3d.ofCenter(target).subtract(eye);
        double len = to.length();
        if (len < 0.5) return null;
        Vec3d dir = to.normalize();
        for (double d = 0.6; d <= Math.min(len, 4.5); d += 0.4) {
            BlockPos bp = new BlockPos(eye.add(dir.multiply(d)));
            if (bp.equals(target)) break;
            if (!mod.getChunkTracker().isChunkLoaded(bp)) continue;
            BlockState st = mod.getWorld().getBlockState(bp);
            if (!st.isAir() && !(st.getBlock() instanceof FluidBlock) && WorldHelper.canBreak(mod, bp)) {
                return bp.toImmutable();
            }
        }
        return null;
    }

    /** 最近可呼吸方块: iterateOutwards 按距离递增遍历, 命中即返回 = 最近优先。 */
    private BlockPos findNearestBreathable(AltoClef mod) {
        BlockPos center = mod.getPlayer().getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(center,
                DROWN_ESCAPE_SEARCH_RADIUS, DROWN_ESCAPE_SEARCH_RADIUS, DROWN_ESCAPE_SEARCH_RADIUS)) {
            if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
            if (!WorldHelper.isAir(mod, pos)) continue;
            boolean connected = false;
            for (Direction d : Direction.values()) {
                BlockPos n = pos.offset(d);
                if (!mod.getChunkTracker().isChunkLoaded(n)) continue;
                BlockState ns = mod.getWorld().getBlockState(n);
                if (ns.isAir() || ns.getBlock() == Blocks.WATER) {
                    connected = true;
                    break;
                }
            }
            if (!connected) continue;
            return pos.toImmutable();
        }
        return null;
    }

    private void handleDrowning(AltoClef mod) {
        // Swim
        boolean avoidedDrowning = false;
        if (mod.getModSettings().shouldAvoidDrowning()) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                if (mod.getPlayer().isTouchingWater() && mod.getPlayer().getAir() < mod.getPlayer().getMaxAir()) {
                    // Swim up!
                    mod.getInputControls().hold(Input.JUMP);
                    //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    avoidedDrowning = true;
                    _wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (_wasAvoidingDrowning && !avoidedDrowning) {
            _wasAvoidingDrowning = false;
            mod.getInputControls().release(Input.JUMP);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

    /**
     * (2026-09-14 坠落取证) 进入自由落体那一刻打一条快照, 不改变任何行为。
     * 判定口径与 MLG 水桶链一致 (vy < -0.6 且非 onGround), 只在"开始坠落"的边沿记一次。
     */
    private void traceFallEdge(AltoClef mod) {
        boolean falling = !mod.getPlayer().isOnGround() && mod.getPlayer().getVelocity().y < -0.6;
        if (falling && !_wasFalling) {
            try {
                Vec3d v = mod.getPlayer().getVelocity();
                boolean pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
                Task userTask = mod.getUserTaskChain().getCurrentTask();
                Debug.logToFile("altoclef_diag.log", "[FallTrace]\tpos="
                        + mod.getPlayer().getBlockPos().toShortString()
                        + " y=" + String.format("%.2f", mod.getPlayer().getY())
                        + " vel=" + String.format("%.2f/%.2f/%.2f", v.x, v.y, v.z)
                        + " pathing=" + pathing
                        + " userTask=" + (userTask == null ? "null" : userTask.toString()));
            } catch (Throwable ignored) {
            }
        }
        _wasFalling = falling;
    }

    /**
     * 是否要立刻脱离岩浆。
     *
     * (2026-09-14 防火岩浆自救, 用户现场) 旧判据把带 FIRE_RESISTANCE 的 bot 直接从
     * "岩浆危险"里剔除 → EscapeFromLavaTask 永不派发 → 它 onStart 里的
     * allowSwimThroughLava(true) 永不执行 → baritone 不认岩浆可游泳 → 玩家所在格
     * 既非整高碰撞盒也非 canWalkOn → A* 一步不展开。latest.log 09:29 现场:
     * "Open set size: 0 / PathNode map size: 1 / 22 movements considered" ×80,
     * [Baritone] Failed 刷屏, 所有移动 (含 Wander 兜底) 全部 fail → bot 不死但
     * 永久静止。与灵魂沙那次同构: A* 一步不展开永远先怀疑起点判据。
     *
     * 有防火只代表"烧不死", 不代表"可以留在岩浆里" —— bot 必须能动才能上岸。
     * 所以只要身体浸在岩浆里就派任务, 让 baritone 拿到可游泳模式自行规划上岸。
     * EscapeFromLavaGoal 给岩浆格 +100 惩罚、水格 -100, 不会主动规划穿岩浆。
     */
    private boolean isInLavaOhShit(AltoClef mod) {
        if (mod.getPlayer().isInLava()) {
            _wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !_wasInLavaTimer.elapsed();
    }
    private boolean isInFire(AltoClef mod) {
        if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            for (BlockPos pos : WorldHelper.getBlocksTouchingPlayer(mod)) {
                Block b = mod.getWorld().getBlockState(pos).getBlock();
                if (b instanceof AbstractFireBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStuckInNetherPortal(AltoClef mod) {
        return WorldHelper.isInNetherPortal(mod) && !mod.getUserTaskChain().getCurrentTask().thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask);
    }

    @Override
    public String getName() {
        return "Misc World Survival Chain";
    }

    @Override
    public boolean isActive() {
        // Always check for survival.
        return true;
    }

    @Override
    protected void onStop(AltoClef mod) {
        super.onStop(mod);
    }
}
