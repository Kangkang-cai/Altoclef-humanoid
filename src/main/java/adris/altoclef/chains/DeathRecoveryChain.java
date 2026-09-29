package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Settings;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.DeathRecoveryTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.trackers.WorldMemory;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ClientDamageSensor;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * Low-priority chain that returns to a recently-remembered death point and
 * picks up the player's own drops before they despawn (5 minute window).
 *
 * Extends SingleTaskChain because we drive exactly one {@link DeathRecoveryTask}
 * at a time, just like WorldSurvivalChain / MobDefenseChain.
 *
 * Priority balance (returned float, compare against sibling chains):
 *   WorldSurvivalChain — 60..100  (lava / fire / drowning / portal stuck)
 *   MobDefenseChain    — 65..90   (creeper about to blow, arrows, block path)
 *   DeathRecoveryChain —    55    (runs only when the bot is not in danger)
 *
 * Activation guards (in getPriority priority-check order):
 *   1. `shouldRecoverDropsAfterDeath` master switch is on.
 *   2. There is a grave memory for the CURRENT dimension only (no teleport).
 *   3. The consecutive-death streak is below
 *      `deathRecoveryMaxConsecutiveDeaths` (otherwise the bot keeps dying on
 *      the way back; memory is cleared).
 *   3.5 (2026-09-29) Respawn settled — 玩家已存活 且 复活包已处理且位置包已应用。
 *      Otherwise we'd "arrive" at the grave at the corpse position on the very
 *      death frame and clear the memory without picking anything up.
 *   4. Grave younger than `deathRecoveryForgetAfterSeconds` (otherwise the
 *      drops have already despawned; memory is cleared).
 *   5. Horizontal distance ≤ `deathRecoveryMaxDistance` (otherwise the
 *      walk would take too long; memory is cleared).
 *   6. If `deathRecoveryRequireStonePickaxe`, we defer WITHOUT clearing the
 *      memory until the bot has at least a stone pickaxe (ExperienceRecall
 *      lesson: temporary conditions must not be turned into permanent skips).
 *
 * Once the DeathRecoveryTask reports isFinished=true, the grave memory is
 * purged so we don't loop forever.
 */
public class DeathRecoveryChain extends SingleTaskChain {

    // Priority — (2026-08-30 再提高, 用户实测"仍被拿工作台/restock wood 强走")
    // 62 → 66: 常规推进任务 (拿工作台/收原木等, 50~60) 仍会偶发抢占死亡掉落。
    // 提至 66: 稳稳压过常规任务与常规生存(60), 只在面对高 fuse 战斗(67+)时才
    // 让位 —— 捡尸时就算有零星怪也坚持把装备捡回来, 但不硬顶爆炸级威胁。
    // (2026-09-01 用户"死亡回收应最高优先", 提至 70): 先前 66 只比常规 UserTasks(50)
    // 高一点, 守卫(无镐/太远)一旦返回 NEGATIVE_INFINITY 就整链让位, 被木头/挖石
    // 主链抢走(用户实测死亡掉落被 Stockpiling wood 抢)。提到 70 稳压常规推进与
    // 常见战斗(65), 仅让位给最高战斗(95)。真正防"被抢"仍需守卫不轻易让位, 见
    // activate 处的守卫收敛 —— 但数值先提高, 保证守卫通过后拾取持续压过主链。
    private static final float ACTIVE_PRIORITY = 70.0f;

    /**
     * (2026-09-29 食物救援, 用户定案) 墓碑掉落物含食物 ≥ 此阈值 (units) 时
     * 跳过"先造石镐"前置, 直接激活 {@link DeathRecoveryTask} 导航拾取。
     * 单位口径与 StorageHelper.calculateInventoryFoodScore 严格一致
     * (foodComponent.hunger × count, 排除蜘蛛眼)。
     *
     * <p>现场 20260929-222238: 死于 mob, 掉落 468 units (熟猪排×48+熟牛肉×8
     * +腐肉×5), 但镐闸 (Guard 3) 把空背包的 bot 派去平原造镐 —— 探索方向
     * 反向, dist 179.7→191.3→200.7 越过 max 200, Guard 2 (TOO_FAR) 撤权,
     * Food 链(55) 接管后朝 +Z 远征, dist 涨到 226.8, 墓到 360s TOO_OLD 才
     * 被清。掉的食物比 Food 链要收集的 (240) 还多, 但 Food 链对墓碑掉落物
     * 完全不感知 —— 两链信息不互通。
     *
     * <p>为什么捡食物可以不持镐: mob 击杀的掉落物就在尸体旁 (地表或矿道),
     * 走过去即可拾取, 不需要挖; 导航真被堵住时, DeathRecoveryTask 内部的
     * GOTO 失速兜底 ([PICKAXE UPGRADE START], 20s 窗口净位移 <3 格) 会自动
     * 挂起导航补石镐, 补完回来继续 — 造镐前置的初衷 (无镐别裸奔下墓) 由
     * 任务层失速兜底接管, 链层不再盲目前置。
     */
    private static final int FOOD_RESCUE_UNITS_THRESHOLD = 80;

    /**
     * (2026-09-29 多人首测) 复活稳定期: 收到复活包后等这么多 tick 才放行激活。
     * 位置包 (PlayerPositionLookS2CPacket) 在复活包之后同批下发, 1 tick 内即应用;
     * 10 tick 的余量足够, 又把"激活前空等"压到 0.5s 量级。见 Guard 0.5。
     */
    private static final long RESPAWN_SETTLE_TICKS = 10L;

    /** 已就"复活未生效"打过日志的墓(同一墓只首发一次, 之后 5s 节流)。 */
    private BlockPos _preRespawnLoggedGrave = null;

    public DeathRecoveryChain(TaskRunner runner) {
        super(runner);
    }

    // (2026-08-31 根因修复) SingleTaskChain 默认 isActive()=_mainTask!=null, 而 TaskRunner 只仲裁
    // isActive() 的链 → 本链初始 _mainTask=null → 永不 active → getPriority 永不调用 → 死亡恢复
    // 7 轮从不生效。按作者约定(SingleTaskChain L60-63)除 UserTaskChain 外每链都应 override true。
    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public float getPriority(AltoClef mod) {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        if (mod.getPlayer() == null) return Float.NEGATIVE_INFINITY;

        Settings s = mod.getModSettings();
        // (2026-08-31 诊断) 用户实测死亡后不走拾取、被 Getting a bed 抢; 本链 ACTIVE_PRIORITY=66
        // 高于 UserTaskChain(50), 若正确激活本应赢。判断是"config 被关"还是"到这之前被拦"。
        if (!s.shouldRecoverDropsAfterDeath()) {
            Debug.logToFile("death_recovery.log",
                    "[DIAG-DISABLED] shouldRecoverDropsAfterDeath() == false at runtime — 这就是死亡恢复不激活的原因");
            // Master switch off. If we were running a task for some reason
            // (e.g. settings hot-reloaded), drop it cleanly.
            if (getCurrentTask() instanceof DeathRecoveryTask) {
                setTask(null);
            }
            return Float.NEGATIVE_INFINITY;
        }

        Dimension currentDim = WorldHelper.getCurrentDimension();
        WorldMemory worldMemory = mod.getWorldMemory();
        Optional<WorldMemory.DeathGraveRecord> maybeGrave = worldMemory.getDeathGrave(currentDim);
        if (maybeGrave.isEmpty()) {
            // No grave.
            if (getCurrentTask() instanceof DeathRecoveryTask) setTask(null);
            // (2026-08-30 诊断) 此分支静默返回, 不写任何日志 — 用户实测: 死亡后
            // 掉落物从不拾取, death_recovery.log 只有 [RECORD] 从无 [ACTIVATE]/
            // [DEFER]/[ABANDON], 且文件在 [RECORD] 后不再更新。最可能是复活后
            // getDeathGrave(当前维度) 取到空。节流打印, 定位是"维度不匹配 /
            // grave 没写入 / 链压根没被框架评估"。
            if (MinecraftClient.getInstance().world != null
                    && MinecraftClient.getInstance().world.getTime() % 100L == 0L) {
                Debug.logToFile("death_recovery.log",
                        "[DIAG] getDeathGrave(" + currentDim + ") EMPTY — pickup chain silently inactive; "
                                + "consecutiveDeaths=" + worldMemory.getConsecutiveDeathCount());
            }
            return Float.NEGATIVE_INFINITY;
        }

        WorldMemory.DeathGraveRecord grave = maybeGrave.get();

        // ------------------------------------------------------------
        // Guard 0: repeated deaths. If we keep dying (likely ON THE WAY to
        // the grave), going back for the drops is futile — leave them and
        // move on instead of feeding the death loop.
        // ------------------------------------------------------------
        int consecutiveDeaths = worldMemory.getConsecutiveDeathCount();
        int maxConsecutive = s.getDeathRecoveryMaxConsecutiveDeaths();
        if (maxConsecutive > 0 && consecutiveDeaths >= maxConsecutive) {
            String msg = String.format(
                    "Repeated deaths (streak %d >= %d); abandoning grave=%s dim=%s",
                    consecutiveDeaths, maxConsecutive, grave.position, currentDim);
            Debug.logWarning(msg);
            Debug.logToFile("death_recovery.log", "[ABANDON DEATH_STREAK] " + msg);
            worldMemory.clearDeathGrave(currentDim);
            if (getCurrentTask() instanceof DeathRecoveryTask) setTask(null);
            return Float.NEGATIVE_INFINITY;
        }

        // ------------------------------------------------------------
        // Guard 0.5 (2026-09-29 多人首测 复活时序): 复活落地前不激活。
        //
        // 现场 20260929-181904 (专用服首测): 墓碑记录 (18:22:13.799) 当帧链就
        // 激活 —— 此时客户端的重生包还没到达, 玩家坐标仍是【尸体坐标】:
        //   [ACTIVATE] dist=0.0 → [GOTO DONE] 假到达 → 抢跑扫描 (物品实体未同步/
        //   区块刚回载) → [PICKUP EMPTY] → [FINISH CLEAR] 清碑
        // 整链 4ms 跑完, 掉落物永久丢失且无重试。单人时代被"共享 JVM + 坐标
        // 恰好一致"掩盖; 既有 PICKUP 阶段的 [STALE POS] 复核也拦不住 —— 到达
        // 分支在同一 tick 内就把 phase 推到 DONE 了。
        //
        // 判据 (用户 2026-09-29 拍板) = 玩家已存活 且 重生传送已生效:
        //   ① health > 0 (死亡屏期间为 0/负);
        //   ② 客户端已处理过复活包 (PlayerRespawnS2CPacket);
        //   ③ 距复活已过 RESPAWN_SETTLE_TICKS, 保证位置包已应用。
        // ③ 用"时间"而非"距离≠0"来判: 重生点恰好等于死亡点时距离恒 0,
        // 用距离会把墓永久锁死。
        // ------------------------------------------------------------
        if (!isRespawnSettled(mod)) {
            boolean firstForThisGrave = !grave.position.equals(_preRespawnLoggedGrave);
            long t = MinecraftClient.getInstance().world != null
                    ? MinecraftClient.getInstance().world.getTime() : 0L;
            if (firstForThisGrave || t % 100L == 0L) {
                _preRespawnLoggedGrave = grave.position;
                Debug.logToFile("death_recovery.log", String.format(
                        "[DEFER PRE_RESPAWN] grave=%s dim=%s health=%s respawnSeen=%s sinceRespawn=%s; waiting for respawn teleport to apply",
                        grave.position, currentDim,
                        mod.getPlayer() == null ? "null" : String.format("%.1f", mod.getPlayer().getHealth()),
                        ClientDamageSensor.respawnSeenSinceLastDeath(),
                        ClientDamageSensor.ticksSinceLastRespawn()));
            }
            if (getCurrentTask() instanceof DeathRecoveryTask) setTask(null);
            return Float.NEGATIVE_INFINITY;
        }

        // ------------------------------------------------------------
        // Guard (用户定案 2026-09-16): drown / lava 墓不拾取, 直接切主任务。
        // 为什么只算 drown 与 lava(精确小写比较, 见 isAbandonCause): 用户拍板——
        // 淹死、直接死在岩浆里才放弃掉落物拾取; 着火烧死(inFire/onFire)、被岩浆块
        // 烫死(hotFloor)、爆炸/cactus 等掉落物没被销毁, 仍正常拾取。1.18.2 里
        // DamageSource.getName() 形如 "death.attack.drown"/"death.attack.lava",
        // PlayerDamageMixin 已剥前缀存成 "drown"/"lava"。注意: 岩浆块烫死是
        // hotFloor 不是 lava, 故本 Guard 不命中, 符合用户"岩浆块烫死仍拾取"的意图。
        // 本 Guard 紧跟 streak(Guard 0) 之后、其余 Guard 之前: streak 计数逻辑
        // 完全不变; 只要 streak 未触发且死因为 drown/lava, 就不再走 too_old/too_far/
        // pickaxe 那套"是否值得去捡"的判定, 直接弃墓切主任务(食物/原木)。
        //
        // (2026-09-22 地表溺水例外, 用户"死于地表的水的掉落物可以拾取")
        // drown 默认弃墓, 但若墓地在地表 (heightmap 地表 y - 墓 y <= BURIED_DEPTH_THRESHOLD),
        // 说明淹死在河流/海洋/湖面等开阔水域, 掉落物漂在水面仍可拾取。复用
        // DeathRecoveryTask.isGraveBuried 的地表口径, 避免重写一套 heightmap 判断。
        // lava 不享受此例外(岩浆会销毁掉落物)。
        // ------------------------------------------------------------
        if (isAbandonCause(grave.deathCause) && !isSurfaceDrownException(mod, grave, currentDim)) {
            if (!grave.altTaskDispatched) {
                // 玩家还躺在死亡界面(health<=0)时不派发, 等复活(health>0)后再切主任务,
                // 避免在一个"死人"身上跑需要移动采集的觅食/囤木任务(且此时坐标仍是死亡点)。
                if (mod.getPlayer() != null && mod.getPlayer().getHealth() > 0f) {
                    // 扫描墓碑 PICKUP_RADIUS 内掉落物: 含食物 → 觅食任务; 否则 → 囤积原木 25。
                    // 掉落物已消失 / 区块未加载时 getDroppedItems() 为空 → 默认派囤原木。
                    boolean hasFood = hasFoodDropNearGrave(mod, grave.position, DeathRecoveryTask.getPickupRadius());
                    Task alt = hasFood
                            ? new CollectFoodTask((double) mod.getModSettings().getFoodUnitsToCollect())
                            : TaskCatalogue.getItemTask("log", 25);
                    if (alt == null) {
                        // 兜底: 万一 "log" 任务构造失败(极端), 不卡死, 清 memory 让位。
                        Debug.logWarning("Alternative dispatch for " + grave.deathCause
                                + " grave failed (null task); clearing grave.");
                        Debug.logToFile("death_recovery.log", String.format(
                                "[ABANDON CAUSE] cause=%s grave=%s dim=%s altTask=NULL_FAIL",
                                grave.deathCause, grave.position, currentDim));
                        worldMemory.clearDeathGrave(currentDim);
                        if (getCurrentTask() instanceof DeathRecoveryTask) setTask(null);
                        return Float.NEGATIVE_INFINITY;
                    }
                    // 一次性派发: 标已派, 防每 tick 重派; 用本链 ACTIVE_PRIORITY 运行,
                    // 复用 :211 已有的"主动派发"模式(不搞 UserTaskChain, 避免改动其它文件)。
                    grave.altTaskDispatched = true;
                    setTask(alt);
                    Debug.logWarning(String.format(
                            "Grave cause=%s at %s dim=%s: NOT picking up drops (drowned/lava); "
                                    + "dispatching %s instead.",
                            grave.deathCause, grave.position, currentDim,
                            hasFood ? "CollectFoodTask" : "stockpile 25 logs"));
                    Debug.logToFile("death_recovery.log", String.format(
                            "[ABANDON CAUSE] cause=%s grave=%s dim=%s altTask=%s",
                            grave.deathCause, grave.position, currentDim,
                            hasFood ? "FOOD" : "LOG"));
                }
                // 尚未复活: 不派发, 整链让位; 复活后本 Guard 还会再被评估并派发。
                return Float.NEGATIVE_INFINITY;
            }
            // 已派发过一次: 替代任务还在跑 → 继续以本链优先级运行; 任务已结束 → 清墓 memory 让位。
            Task cur = getCurrentTask();
            if (cur == null || cur.isFinished(mod) || cur.stopped()) {
                worldMemory.clearDeathGrave(currentDim);
                if (cur != null) setTask(null);
                return Float.NEGATIVE_INFINITY;
            }
            return ACTIVE_PRIORITY;
        }

        BlockPos playerPos = mod.getPlayer().getBlockPos();
        long nowTick = 0;
        if (MinecraftClient.getInstance().world != null) {
            nowTick = MinecraftClient.getInstance().world.getTime();
        }
        long ageTicks = nowTick - grave.deathGameTick;
        long ageSeconds = ageTicks / 20L;
        long forgetSeconds = s.getDeathRecoveryForgetAfterSeconds();

        // ------------------------------------------------------------
        // Guard 1: too old (past the 5-minute despawn + grace period).
        // Clear memory and bail.
        // ------------------------------------------------------------
        if (forgetSeconds > 0 && ageSeconds > forgetSeconds) {
            String msg = String.format(
                    "Grave too old (%ds > forget %ds); abandoning grave=%s dim=%s",
                    ageSeconds, forgetSeconds, grave.position, currentDim);
            Debug.logWarning(msg);
            Debug.logToFile("death_recovery.log", "[ABANDON TOO_OLD] " + msg);
            worldMemory.clearDeathGrave(currentDim);
            if (getCurrentTask() instanceof DeathRecoveryTask) setTask(null);
            return Float.NEGATIVE_INFINITY;
        }

        // ------------------------------------------------------------
        // Guard 2: too far to walk right now → DEFER (keep memory), do NOT abandon.
        // (2026-08-31 用户实测) 死亡点距重生点/当前位置常 > maxDistance, 旧逻辑
        // 直接 ABANDON 清掉 memory → getDeathGrave 永久 EMPTY → 拾取链永不触发
        // (13:24 日志: [RECORD] 后 52ms 即 [ABANDON TOO_FAR] 424.5>150, 随后
        // 一直 [DIAG] EMPTY)。改为保留记忆、本刻只是不触发 (DEFER), 待 bot
        // 走回范围内即可触发拾取; 仍由 Guard 1 (TOO_OLD, 5 分钟掉落消失) 兜底清除。
        // ------------------------------------------------------------
        int maxDist = s.getDeathRecoveryMaxDistance();
        double distXZ = horizontalDistanceXZ(playerPos, grave.position);
        if (maxDist > 0 && distXZ > maxDist) {
            if (ageTicks % 100L == 0L) {
                Debug.logToFile("death_recovery.log", String.format(
                        "[GRAVE-FIX][DEFER TOO_FAR] grave=%s dim=%s dist=%.1f > max %d age=%ds; keeping memory (will trigger when in range)",
                        grave.position, currentDim, distXZ, maxDist, ageSeconds));
            }
            // 遗失镐中途退出的任务同样停掉(不等它), 但不碰 memory。
            if (getCurrentTask() instanceof DeathRecoveryTask) setTask(null);
            return Float.NEGATIVE_INFINITY;
        }

        // ------------------------------------------------------------
        // Guard 3: waiting for a stone pickaxe. TEMPORARY defer — keep
        // the grave memory intact. (ExperienceRecall lesson.)
        // Log only once per 5 seconds to keep the log file sane.
        // (2026-08-31 用户确认合理并应保留: 无镐去捡无法自保/采集, DEFER 等镐是正确行为。)
        // ------------------------------------------------------------
        // (2026-09-12 动态镐门槛, 用户定案"需要镐子的死亡恢复才搞镐") 全局开关
        // deathRecoveryRequireStonePickaxe 保留为总闸; 总闸开时按墓地深度动态
        // 判断: 墓地埋在地下 (heightmap 差 >8 格) 才要求石镐, 地表/浅层死亡
        // 直接导航不折腾。实锤 20260912-114028: 总闸被配置关成 false → 空手
        // 复活后 baritone 对"地表挖 89 格下墓"成本爆炸 path[present=false] →
        // wander 3 分钟零进展。另: DeathRecoveryTask 内部镐子升级进行中时本闸
        // 让路, 避免链层把正在升级的恢复任务整个替换成搞镐任务。
        boolean deathRecoveryTaskUpgradingPickaxe = false;
        if (getCurrentTask() instanceof DeathRecoveryTask) {
            deathRecoveryTaskUpgradingPickaxe = ((DeathRecoveryTask) getCurrentTask()).isPickaxeUpgradeActive();
        }
        // (2026-09-29 食物救援) 只在镐闸其余条件全部命中 (即"确实要因无镐而
        // 前置造镐") 时才扫描墓碑掉落物 —— 距离闸/弃墓分支不受影响, 避免每
        // tick 白扫一遍实体列表。掉落物食物充足 ⇒ 无镐也能捡 (mob 掉落物在
        // 尸体旁), 镐闸让位; 导航被堵由 DeathRecoveryTask 失速兜底自己补镐。
        boolean pickaxeGateCandidate = s.shouldDeathRecoveryRequireStonePickaxe()
                && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.STONE)
                && !deathRecoveryTaskUpgradingPickaxe
                && DeathRecoveryTask.isGraveBuried(mod, grave.position);
        int droppedFoodUnits = -1;
        boolean foodRescueActive = false;
        if (pickaxeGateCandidate) {
            droppedFoodUnits = droppedFoodUnitsNearGrave(mod, grave.position, DeathRecoveryTask.getPickupRadius());
            foodRescueActive = droppedFoodUnits >= FOOD_RESCUE_UNITS_THRESHOLD;
        }
        boolean pickaxeGateActive = pickaxeGateCandidate && !foodRescueActive;
        if (foodRescueActive && ageTicks % 100L == 0L) {
            Debug.logToFile("death_recovery.log", String.format(
                    "[FOOD-RESCUE] dropped=%d units >= %d at grave=%s; skipping pickaxe gate "
                            + "(pickup needs no pickaxe, task self-heals via GOTO stall fallback)",
                    droppedFoodUnits, FOOD_RESCUE_UNITS_THRESHOLD, grave.position));
        }
        if (pickaxeGateActive) {
            if (ageTicks % 100L == 0L) {
                Debug.logToFile("death_recovery.log", String.format(
                        "[DEFER NO_PICKAXE] grave=%s dim=%s dist=%.1f age=%ds; actively crafting stone pickaxe",
                        grave.position, currentDim, distXZ, ageSeconds));
            }
            // (2026-09-02 主动搞镐, 用户"恢复任务被长收集任务抢走/镐永远等不来") 旧逻辑
            // 只 DEFER 返回负无穷, 把优先级全让给普通任务; 但没有任何任务负责去造镐
            // → 背包没镐就永远 DEFER, 直到 Guard 1 (TOO_OLD, 5 分钟掉落消失) 放弃、
            // 装备真丢 (23:04 日志: age=120s 仍 NO_PICKAXE)。改为【主动派发石镐获取
            // 任务】并以死亡恢复优先级运行, 保证不被耗时长的一般收集任务饿死; 镐一到
            // 位 Guard 3 即放行、转入真正去墓地的拾取。主动搞镐不违背"没镐不裸捡"。
            if (!(getCurrentTask() instanceof SatisfyMiningRequirementTask)) {
                setTask(new SatisfyMiningRequirementTask(MiningRequirement.STONE,
                        "death-recovery-chain/no-pickaxe"));
            }
            return ACTIVE_PRIORITY;
        }

        // ------------------------------------------------------------
        // All guards passed. Activate or re-use the running task.
        // If the task already finished we'll clear it in onTaskFinish.
        // ------------------------------------------------------------
        if (!(getCurrentTask() instanceof DeathRecoveryTask existing)
                || !matches(existing, grave, s)) {
            int timeoutSeconds = s.getDeathRecoveryTimeoutSeconds();
            Debug.logMessage(String.format(
                    "Activating death recovery: grave=(%d,%d,%d) dist=%.1f age=%ds timeout=%ds",
                    grave.position.getX(), grave.position.getY(), grave.position.getZ(),
                    distXZ, ageSeconds, timeoutSeconds));
            Debug.logToFile("death_recovery.log", String.format(
                    "[ACTIVATE] grave=%s dim=%s dist=%.1f age=%ds timeout=%ds pickaxeReq=%s gate=%s",
                    grave.position, currentDim, distXZ, ageSeconds, timeoutSeconds,
                    s.shouldDeathRecoveryRequireStonePickaxe() ? "Y" : "N",
                    pickaxeGateActive ? "GATED" : "pass"));
            setTask(new DeathRecoveryTask(grave.position, currentDim, timeoutSeconds));
        }

        return ACTIVE_PRIORITY;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Runs whenever SingleTaskChain detects that _mainTask.isFinished
        // or _mainTask.stopped().
        if (getCurrentTask() instanceof DeathRecoveryTask drt) {
            Dimension currentDim = WorldHelper.getCurrentDimension();
            WorldMemory worldMemory = mod.getWorldMemory();
            Optional<WorldMemory.DeathGraveRecord> maybeGrave = worldMemory.getDeathGrave(currentDim);
            // Only clear the memory if it actually still refers to this
            // task's grave (the player could have died again mid-recovery,
            // overwriting the memory with a fresher record — we must not
            // accidentally erase the newer grave).
            maybeGrave.ifPresent(grave -> {
                if (grave.position.equals(drt.getGravePosition())
                        && currentDim == drt.getGraveDimension()) {
                    Debug.logMessage(String.format(
                            "Death recovery complete: (%d,%d,%d) dim=%s",
                            grave.position.getX(), grave.position.getY(), grave.position.getZ(),
                            currentDim));
                    Debug.logToFile("death_recovery.log", String.format(
                            "[FINISH CLEAR] grave=%s dim=%s", grave.position, currentDim));
                    worldMemory.clearDeathGrave(currentDim);
                } else {
                    Debug.logToFile("death_recovery.log", String.format(
                            "[FINISH KEEP] task ended but memory now points elsewhere. " +
                                    "taskPos=%s taskDim=%s vs memPos=%s memDim=%s",
                            drt.getGravePosition(), drt.getGraveDimension(),
                            grave.position, currentDim));
                }
            });
            if (maybeGrave.isEmpty()) {
                Debug.logToFile("death_recovery.log",
                        "[FINISH] task ended with no grave memory (already cleared).");
            }
        } else {
            // (2026-09-16) 替代派发(食物/原木)任务跑完 → 清墓 memory 让位。
            // 主清理在 getPriority 的 altTaskDispatched 分支(更稳, 覆盖任务被其它链
            // 打断后本链重新夺回的窗口); 这里兜底, 防止任务结束但 getPriority 尚未
            // 轮到的瞬间残留墓 memory。
            WorldMemory worldMemory = mod.getWorldMemory();
            Dimension currentDim = WorldHelper.getCurrentDimension();
            worldMemory.getDeathGrave(currentDim).ifPresent(g -> {
                if (g.altTaskDispatched) worldMemory.clearDeathGrave(currentDim);
            });
        }
        setTask(null);
    }

    @Override
    public String getName() {
        return "Death Recovery / Drop Retrieval";
    }

    // ------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------

    /** Returns true iff the existing task is bound to the same grave/config. */
    private static boolean matches(DeathRecoveryTask task, WorldMemory.DeathGraveRecord grave, Settings s) {
        if (!grave.position.equals(task.getGravePosition())) return false;
        if (task.getTimeoutSeconds() != s.getDeathRecoveryTimeoutSeconds()) return false;
        // grave dimension can't be compared directly because the task stores
        // it privately. But the task is only ever instantiated with
        // `currentDim`, so the current memory is for that same dimension and
        // a position match is enough.
        return true;
    }

    private static double horizontalDistanceXZ(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * (2026-09-29 多人首测 复活时序) 复活传送是否已生效 —— 死后能否激活捡尸的前置条件。
     *
     * <p>为什么必须有这道闸: 死亡当帧客户端的玩家坐标仍是【尸体坐标】, 而链层按
     * 墓碑坐标 = 玩家坐标判定"已到达"; 于是整链在同一 tick 内 ACTIVATE → GOTO DONE
     * → EMPTY → FINISH CLEAR, 掉落物连同步的机会都没有 (现场 20260929-181904)。
     *
     * <p>三条判据缺一不可: health>0 (死亡屏期间不放行) / 已收到复活包 /
     * 距复活过了 {@link #RESPAWN_SETTLE_TICKS}。第三条用"时间"而不是"距离不等于 0",
     * 是为了不把"重生点恰好就是死亡点"的墓永久锁死。
     *
     * <p>代价 (记录在案): 若某服务端从不回血 (重生了但 health 恒 0), 本闸会一直
     * DEFER, 该墓最后由 Guard 1 (TOO_OLD, 5 分钟) 清掉而不是去捡 —— 这种服务端
     * 本身已不可玩, 且 [DEFER PRE_RESPAWN] 日志会直接暴露 health=0.0/respawnSeen=true。
     */
    private static boolean isRespawnSettled(AltoClef mod) {
        PlayerEntity player = mod.getPlayer();
        if (player == null || player.getHealth() <= 0f) return false;
        if (!ClientDamageSensor.respawnSeenSinceLastDeath()) return false;
        long since = ClientDamageSensor.ticksSinceLastRespawn();
        // -1 = 世界/tick 未知 → 按已稳定处理, 避免硬锁 (此时活着的玩家坐标已可用)。
        return since < 0L || since >= RESPAWN_SETTLE_TICKS;
    }

    // ------------------------------------------------------------
    // (2026-09-16) drown/lava 弃墓辅助方法
    // ------------------------------------------------------------

    /**
     * 死因是否"放弃拾取"。只认精确小写 "drown" 与 "lava"。
     * 用户拍板只算这两种; 其它(inFire/onFire/hotFloor/cactus/explosion/outOfWorld...)
     * 都正常拾取。精确小写比较避免误命中(比如模组自定义 "mydrown" 来源名)。
     */
    private static boolean isAbandonCause(String cause) {
        return cause != null && (cause.equals("drown") || cause.equals("lava"));
    }

    /**
     * (2026-09-22 地表溺水例外) 死因为 drown 且墓地在地表时, 掉落物仍可拾取。
     * 复用 {@link DeathRecoveryTask#isGraveBuried} 的 heightmap 口径: 墓 y 距
     * 地表 <= BURIED_DEPTH_THRESHOLD(8 格) 即视为地表/浅层, 对应河流/海洋/湖面
     * 等开阔水域溺亡。lava 不在此例外范围(岩浆销毁掉落物)。
     * 区块未加载时 isGraveBuried 返回 false(当作地表), 保守走拾取。
     */
    private static boolean isSurfaceDrownException(AltoClef mod, WorldMemory.DeathGraveRecord grave, Dimension dim) {
        if (!"drown".equals(grave.deathCause)) return false;
        boolean buried = DeathRecoveryTask.isGraveBuried(mod, grave.position);
        if (!buried) {
            Debug.logToFile("death_recovery.log", String.format(
                    "[SURFACE DROWN] cause=drown grave=%s dim=%s — surface water death, proceeding with normal pickup",
                    grave.position, dim));
        }
        return !buried;
    }

    /**
     * 墓碑 radius 范围内掉落物的食物单位总量 (2026-09-29 食物救援)。
     * 口径与 {@link StorageHelper#calculateInventoryFoodScore} 严格一致:
     * foodComponent.hunger × count, 排除蜘蛛眼 (FoodChain 拒绝吃蜘蛛眼,
     * 把它算进储备会让"只剩蜘蛛眼"误判成食物充足)。
     * 区块未加载 / 掉落物已消失 / 离墓超半径 → 0, 不触发救援, 走原判据。
     */
    private static int droppedFoodUnitsNearGrave(AltoClef mod, BlockPos grave, int radius) {
        List<ItemEntity> drops = mod.getEntityTracker().getDroppedItems();
        if (drops.isEmpty()) return 0;
        Vec3d centre = Vec3d.ofCenter(grave);
        double radiusSq = (double) radius * radius;
        int units = 0;
        for (ItemEntity drop : drops) {
            if (drop == null || !drop.isAlive()) continue;
            if (drop.squaredDistanceTo(centre) > radiusSq) continue;
            ItemStack stack = drop.getStack();
            if (stack.isEmpty() || !stack.isFood()) continue;
            if (stack.getItem() == Items.SPIDER_EYE) continue;
            units += stack.getItem().getFoodComponent().getHunger() * stack.getCount();
        }
        return units;
    }

    /**
     * 墓碑 radius 范围内是否存在食物掉落物。复用 DeathRecoveryTask 的 PICKUP_RADIUS,
     * 与正常拾取扫描同量级。掉落物已消失 / 区块未加载 → getDroppedItems() 为空
     * → 返回 false → 上层默认派囤原木。
     */
    private static boolean hasFoodDropNearGrave(AltoClef mod, BlockPos grave, int radius) {
        List<ItemEntity> drops = mod.getEntityTracker().getDroppedItems();
        if (drops.isEmpty()) return false;
        Vec3d centre = Vec3d.ofCenter(grave);
        double radiusSq = (double) radius * radius;
        for (ItemEntity drop : drops) {
            if (drop == null || !drop.isAlive()) continue;
            if (drop.squaredDistanceTo(centre) > radiusSq) continue;
            if (isFoodItem(drop.getStack().getItem())) return true;
        }
        return false;
    }

    /**
     * 是否食物: 任何可食用物都算(生/熟肉、面包、薯、浆果…)。
     * 优先用 Minecraft 自带 Item.isFood() 兜底所有可食物品; 同时兼容
     * ItemHelper.RAW_FOODS 常量的生食(用户定案引用的现成常量)。
     */
    private static boolean isFoodItem(Item item) {
        if (item == null) return false;
        if (item.isFood()) return true;
        for (Item raw : ItemHelper.RAW_FOODS) {
            if (raw == item) return true;
        }
        return false;
    }
}
