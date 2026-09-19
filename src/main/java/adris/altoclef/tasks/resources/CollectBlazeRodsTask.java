package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.FortressExploreTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.misc.DrinkFireResPotionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.KnowledgeMemory;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.ThreatEvaluator;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.WorldIdentity;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class CollectBlazeRodsTask extends ResourceTask {

    private static final double SPAWNER_BLAZE_RADIUS = 32;

    private static final int TOO_MANY_BLAZES = 5;
    private static final double TOO_LITTLE_HEALTH_BLAZE = 5;

    // =========================================================================
    // (2026-09-18 烈焰刷怪笼记忆, 用户"刷怪笼是像堡垒那样的关键位置, 记住后
    // 直接去那边刷, 不用再找") 原实现只把刷怪笼存【实例字段】(第 54 行附近),
    // 任务实例一重建就清零, 于是每次都得靠走廊巡逻重新"亲眼看见"才能确立目标。
    // 两层记忆补上:
    //   ① 会话内: 按 存档:维度 隔离的 static map —— 覆盖"任务实例重建"这一类
    //      最高频丢失 (_liveInstance 缓存只在 isFinished / 停泊超 8s / 重开游戏
    //      时才重建, 但下界战斗频繁让位, 8s 宽限很容易被击穿)。
    //   ② 跨会话: KnowledgeMemory.StructureType.BLAZE_SPAWNER (亲眼看见过就
    //      记、落盘 knowledge.log), 下次直接从记忆点导航过去。
    // ⚠ 安全底线: 记忆点只是【候选】, 必须走到现场用 getSpawnerEntity 验明是
    //   烈焰实体才确立。蜘蛛笼/僵尸笼方块完全相同, 按坐标远程认亲会骗到主世界去。
    // =========================================================================
    // 记忆点召回的最大距离 (下界格)。1024 = 与 FortressExploreTask 的
    // REMEMBERED_CENTER_MAX_DISTANCE 同口径: 人类"记得那边有座堡垒"就该直接去。
    private static final double SPAWNER_RECALL_RANGE = 1024;
    // 走到候选点多近才够验明 (需 chunk 已加载才能读实体)。
    private static final double SPAWNER_RECALL_ARRIVE = 12;
    // 前往候选点的超时 (秒)。超时 = 够不着, 登记排除, 换下一个候选。
    private static final double SPAWNER_RECALL_TIMEOUT = 90;
    // 排除表的容量/判定半径 (与 FortressExploreTask 记忆查讫同款思路)。
    private static final double REJECT_RADIUS = 8;
    private static final int MAX_REJECTED_SPAWNERS = 64;

    private static final Map<String, BlockPos> _confirmedSpawnerByKey = new HashMap<>();
    private static final Map<String, Set<BlockPos>> _rejectedSpawnerByKey = new HashMap<>();

    // (2026-09-06 药水门槛, 用户) 喝防火药水的最低烈焰人数: 落单烈焰人走盾战, 不喝药。
    private static final int POTION_MIN_BLAZES = 2;
    // 血量低 (半血) 时即便落单也允许喝 — 盾战容错太差, 保命优先。
    private static final float POTION_LOW_HEALTH = 10.0f;

    // =========================================================================
    // (2026-09-19 刷怪笼包围策略, 用户; 17:20 终版规格) 结构 = 三件套:
    //   地板: 刷怪笼低一格 (y-1) 5x5 补实
    //   围墙: y+1 一圈 7x7 外圈 (单圈)
    //   顶盖: y+3 5x5 补实 ("封顶")
    //   内圈 5x5 在 y..y+2 敞开 (3 格高), bot 与烈焰人同场。
    // 搭建顺序自上向下: 顶盖 → 围墙 → 地板 (用户)。
    // =========================================================================
    private static final int ENCLOSURE_NETHERRACK_PREP = 128;
    private static final int ENCLOSURE_RESTOCK_TRIGGER = 32;
    private static final int ENCLOSURE_RESTOCK_TOPUP = 32;
    // 脚底地板 5x5 (低一格) / 顶盖 5x5 (高 3 格) / 围墙 7x7 外圈半径。
    private static final int SPAWNER_FLOOR_HALF = 2;
    private static final int SPAWNER_ROOF_HEIGHT = 3;
    private static final int SPAWNER_WALL_HEIGHT = 1;
    private static final int SPAWNER_WALL_HALF = 3;
    // 破洞重扫间隔 / 回场检查间隔 (秒, 用户 13:05)。
    private static final int HOLE_CHECK_INTERVAL_S = 15;
    private static final int RETURN_CHECK_INTERVAL_S = 10;
    // 围场内烈焰人才打 (刷怪笼中心 4.5 格 ≈ 7x7 围场对角半径)。
    private static final double ENCLOSURE_KILL_RANGE = 4.5;
    private final int _count;
    // (2026-09-04 人类式改造) 默认走 FortressExploreTask: 循声/循可见下界砖
    // 偏置游走 + 踏上要塞后只踩走廊地面, 不再 chunk 透视。
    private final Task _fortressSearcher = new FortressExploreTask();
    // 超时兜底: 人类式探索太久没找到时, 降级回旧的 chunk 扫描 (Settings 开关)。
    private final Task _searcher = new SearchChunkForBlockTask(Blocks.NETHER_BRICKS);
    private final TimerGame _searchTimer = new TimerGame(Integer.MAX_VALUE);
    private boolean _fallbackEngaged = false;

    // Why was this here???
    //private Entity _toKill;
    private BlockPos _foundBlazeSpawner = null;

    // (2026-09-18 刷怪笼记忆召回) 当前 存档:维度 key / 正在前往的候选点 / 前往计时。
    private String _worldKey = null;
    private BlockPos _recallTarget = null;
    private final TimerGame _recallTimer = new TimerGame(SPAWNER_RECALL_TIMEOUT);

    // (2026-09-19 16:22) 自上向下搭建时顶圈可能暂无贴面放不了: 同一目标卡
    // 超 10s 就轮到队尾 (优先级让位, 不死等)。
    private static final long BUILD_TARGET_STALL_MS = 10_000;
    private BlockPos _currentBuildTarget = null;
    private long _currentBuildStartMs = 0;

    // (2026-09-19 13:05) 破洞检查缓存 (每 15s 全扫一次, 期间只对缓存清单做
    // 廉价的 isSolid 刷新) 与回场检查计时 (每 10s 一次)。
    private final TimerGame _holeCheckTimer = new TimerGame(HOLE_CHECK_INTERVAL_S);
    private List<BlockPos> _cachedMissingEnclosure = null;
    private final TimerGame _returnCheckTimer = new TimerGame(RETURN_CHECK_INTERVAL_S);

    public CollectBlazeRodsTask(int count) {
        super(Items.BLAZE_ROD, count);
        _count = count;
    }

    private static boolean isHoveringAboveLavaOrTooHigh(AltoClef mod, Entity entity) {
        int MAX_HEIGHT = 23;
        for (BlockPos check = entity.getBlockPos(); entity.getBlockPos().getY() - check.getY() < MAX_HEIGHT; check = check.down()) {
            if (mod.getWorld().getBlockState(check).getBlock() == Blocks.LAVA) return true;
            if (WorldHelper.isSolid(mod, check)) return false;
        }
        return true;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.SPAWNER);
        // (2026-09-04) 人类式找要塞: 计时器从进下界找要塞开始算, 触发超时兜底。
        _searchTimer.reset();
        _fallbackEngaged = false;
        // (2026-09-18 刷怪笼记忆) 实例即便被重建也要把上一段确认过的刷怪笼接回来,
        // key 与 FortressExploreTask / KnowledgeMemory 同口径 (WorldIdentity)。
        _worldKey = WorldIdentity.key(mod);
        _recallTarget = null;
        _recallTimer.reset();
        // (2026-09-19 13:05) 实例重建 → 围场缓存清零, 到场后立即重扫一次。
        _cachedMissingEnclosure = null;
        _holeCheckTimer.reset();
        _returnCheckTimer.reset();
        BlockPos remembered = _confirmedSpawnerByKey.get(_worldKey);
        if (remembered != null) {
            _foundBlazeSpawner = remembered;
            Debug.logMessage("[BLAZE-SPAWNER] 沿用已确认刷怪笼 " + remembered.toShortString()
                    + " (实例重建, key=" + _worldKey + ")");
            registerEnclosureProtection(mod);
        } else {
            // Session-static 里也没有 → 交给每层 tick 走"亲眼看见 / 记忆召回"两条路。
            _foundBlazeSpawner = null;
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {

        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }

        // (2026-09-19 包围策略④) 围场未完工 (没喝药 / 缺块未补) 时不开杀 —
        // 半路去追打飘在空中的烈焰人正是 0919 摔死事故的根源, 先把围场立起来。
        boolean spawnerWorkPending = isSpawnerWorkPending(mod);

        Optional<Entity> toKill = Optional.empty();
        // If there is a blaze, kill it.
        if (mod.getEntityTracker().entityFound(BlazeEntity.class)) {

            // (2026-09-19 用户) 有防火效果时不逃跑 — 火球的火焰伤害被完全免疫,
            // 剩下的只有轻微碰撞伤害, 贴身近战收割才是正解。
            if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                    && mod.getEntityTracker().getTrackedEntities(BlazeEntity.class).size() >= TOO_MANY_BLAZES
                    && mod.getPlayer().getHealth() <= TOO_LITTLE_HEALTH_BLAZE) {
                setDebugState("Running away as there are too many blazes nearby.");
                return new TimeoutWanderTask();
            }

            // (2026-09-19 13:05 用户) 只打【我们自己围起来的区域】里的烈焰人;
            // 走廊/别的房间里的暂定直接不打 — 追出去打正是摔死事故的根源。
            if (_foundBlazeSpawner != null && !spawnerWorkPending) {
                Vec3d spawnerCenter = Vec3d.ofCenter(_foundBlazeSpawner);
                toKill = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), BlazeEntity.class)
                        .filter(b -> b.squaredDistanceTo(spawnerCenter) <= ENCLOSURE_KILL_RANGE * ENCLOSURE_KILL_RANGE);
            }
        }

        if (toKill.isPresent() && toKill.get().isAlive()) {
            // (2026-09-06 药水门槛, 用户) 落单烈焰人不再浪费药水 — 改走盾牌
            // 波次格挡 (AbstractKillEntityTask)。只有以下情况才喝:
            //   1) 感知到 ≥ POTION_MIN_BLAZES 只烈焰人 (刷怪笼房, 火球密度太高);
            //   2) 盾战不可行: 没盾 (ThreatEvaluator.hasShield 含背包) 或血量低。
            // 有 FIRE_RESISTANCE 效果时不喝, 效果消失后才按上面门槛续杯。
            if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                    && ItemHelper.countFireResPotions(mod) > 0) {
                int blazeCount = mod.getEntityTracker().getTrackedEntities(BlazeEntity.class).size();
                boolean manyBlazes = blazeCount >= POTION_MIN_BLAZES;
                boolean shieldImpractical = !ThreatEvaluator.hasShield(mod)
                        || mod.getPlayer().getHealth() <= POTION_LOW_HEALTH;
                if (manyBlazes || shieldImpractical) {
                    setDebugState("Drinking fire resistance potion before fighting blaze");
                    return new DrinkFireResPotionTask();
                }
            }
            setDebugState("Killing blaze inside our enclosure");
            // 击杀谓词限定围场内: KillEntitiesTask 否则会追到走廊里打外面的
            // 烈焰人 (用户 13:05: 外面的暂定直接不打)。
            Vec3d spawnerCenter = Vec3d.ofCenter(_foundBlazeSpawner);
            return new KillEntitiesTask(entity ->
                            entity.squaredDistanceTo(spawnerCenter) <= ENCLOSURE_KILL_RANGE * ENCLOSURE_KILL_RANGE
                                    && !isHoveringAboveLavaOrTooHigh(mod, entity),
                    BlazeEntity.class);
        }


        // If the blaze spawner somehow isn't valid
        if (_foundBlazeSpawner != null && mod.getChunkTracker().isChunkLoaded(_foundBlazeSpawner) && !isValidBlazeSpawner(mod, _foundBlazeSpawner)) {
            Debug.logMessage("Blaze spawner at " + _foundBlazeSpawner + " too far away or invalid. Re-searching.");
            // (2026-09-18) 失效要连记忆一起废掉: 否则下次实例重建/记忆召回又把
            // bot 送回这个已经不是烈焰笼的地方, 形成来回拉扯。
            invalidateSpawner(_foundBlazeSpawner, "invalidated (not a blaze spawner / despawned)");
            _foundBlazeSpawner = null;
        }

        // If we have a blaze spawner, go near it.
        if (_foundBlazeSpawner != null) {
            int netherrackHave = StorageHelper.countItemInInventoryReal(mod, Items.NETHERRACK);

            // (2026-09-19 13:05 用户⑥) 围场阶段地狱岩 <32 → 随时补 32 个
            // (2 组备料用完后的续补口径)。粘性实例: 补货中途 have 渐涨但目标
            // 已固定, 只在目标上调时重建, 防 MineAndCollect 反复重启 (铁律 #3)。
            if (netherrackHave < ENCLOSURE_RESTOCK_TRIGGER) {
                setDebugState("Restocking netherrack (have " + netherrackHave + ", topup "
                        + ENCLOSURE_RESTOCK_TOPUP + ")");
                return getEnclosureRestockTask(mod, netherrackHave + ENCLOSURE_RESTOCK_TOPUP);
            }
            // (2026-09-19 17:20) 不再无条件 clear —— 之前这里每 tick 清空, 而
            // !arrived 分支每 tick 重建 128 任务, "Preparing 128" 一秒刷 20 条 +
            // MineAndCollect 实例反复重建 (171228 实锤, 补货后期卡顿源之一)。
            // 复用/重建策略统一收敛到 getEnclosureRestockTask (isFinished 才重建)。

            boolean arrived = _foundBlazeSpawner.isWithinDistance(mod.getPlayer().getPos(), 4);
            if (!arrived) {
                // (2026-09-19 包围策略①, 用户) 出发前置: 准备 2 组 (128) 搭路
                // 方块。一次性闩: 补满一次即闩上, 不再反复检查 —— 否则路上
                // baritone 每垫一块脚手架就跌破阈值又弹回补货, 2s 一轮
                // Restock↔Going 死循环 (123515 实锤)。路上的消耗是正常开销,
                // 由上面的 <32 续补口径兜底。
                String stockKey = enclosureStockKey();
                boolean stockedForThisSpawner = stockKey != null && stockKey.equals(_enclosureStockedFor);
                if (!stockedForThisSpawner && netherrackHave < ENCLOSURE_NETHERRACK_PREP) {
                    setDebugState("Preparing " + ENCLOSURE_NETHERRACK_PREP + " netherrack (2 stacks) for spawner enclosure");
                    return getEnclosureRestockTask(mod, ENCLOSURE_NETHERRACK_PREP);
                }
                if (!stockedForThisSpawner) {
                    _enclosureStockedFor = stockKey;
                    clearEnclosureRestockTask();
                    Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] stock latch set (netherrack="
                            + netherrackHave + ", spawner=" + _foundBlazeSpawner.toShortString() + ")");
                }
                setDebugState("Going to blaze spawner");
                return new GetToBlockTask(_foundBlazeSpawner.up(), false);
            }

            // (2026-09-19 包围策略②, 用户) 到达刷怪笼先喝药水。包里没药不卡
            // 在这一步 (防火效果本身不是围场的硬前置, 硬等会死循环)。
            if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                    && ItemHelper.countFireResPotions(mod) > 0) {
                setDebugState("Drinking fire resistance at spawner (before enclosure)");
                return new DrinkFireResPotionTask();
            }

            // (2026-09-19 13:05 包围策略③⑤) 围场: 同层 5x5 地板 + 高一格/高两
            // 层各一圈 7x7 外圈围墙。破洞全扫每 15s 一次 (不每 tick 扫), 期间只
            // 对缓存清单做廉价的 isSolid 刷新 (防已补位置被当缺口循环)。
            if (_holeCheckTimer.elapsed() || _cachedMissingEnclosure == null) {
                _holeCheckTimer.reset();
                _cachedMissingEnclosure = findMissingEnclosureBlocks(mod, _foundBlazeSpawner);
                if (_cachedMissingEnclosure.isEmpty()) {
                    Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] enclosure complete (scan at tick "
                            + WorldHelper.getTicks() + ")");
                }
                // (2026-09-19 16:05 问题三, 用户) 高处岩浆流进围场区域: 有防火药
                // 硬抗, 不逃不躲专心放方块 (墙盖好岩浆自然被挡住); 持续 45s 且
                // 记忆里有别的刷怪笼才换笼。
                if (isLavaInEnclosureRegion(mod, _foundBlazeSpawner)) {
                    if (_lavaTroubleSinceMs == 0) {
                        _lavaTroubleSinceMs = System.currentTimeMillis();
                        Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] lava pouring into region, keep building with fire res");
                    } else if (System.currentTimeMillis() - _lavaTroubleSinceMs > LAVA_TROUBLE_SWITCH_MS) {
                        trySwitchAwayFromLavaSpawner(mod);
                        if (_foundBlazeSpawner == null) {
                            setDebugState("Switching away from lava-flooded spawner");
                            return _fortressSearcher;
                        }
                    }
                } else {
                    _lavaTroubleSinceMs = 0;
                }
            }
            List<BlockPos> missing = _cachedMissingEnclosure;
            // (2026-09-19 16:05) 围墙施工窗口标志: 有缺块就处于施工中。
            _enclosureBuildActive = missing != null && !missing.isEmpty();
            if (missing != null) {
                missing.removeIf(p -> WorldHelper.isSolid(mod, p));
                if (!missing.isEmpty()) {
                    BlockPos next = missing.get(0);
                    // (2026-09-19 16:22) 自上向下优先, 但顶圈暂无贴面放不了时
                    // 不死等: 同一目标超 10s 轮到队尾。
                    long nowMs = System.currentTimeMillis();
                    if (!next.equals(_currentBuildTarget)) {
                        _currentBuildTarget = next;
                        _currentBuildStartMs = nowMs;
                    } else if (nowMs - _currentBuildStartMs > BUILD_TARGET_STALL_MS) {
                        missing.remove(0);
                        missing.add(next);
                        _currentBuildTarget = null;
                        Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] build target stalled, rotating "
                                + next.toShortString() + " to back of queue");
                        return null;
                    }
                    setDebugState("Building enclosure: " + missing.size() + " missing, next " + next.toShortString());
                    return new PlaceStructureBlockTask(next);
                }
            }
            _currentBuildTarget = null;

            // (2026-09-19 17:33 用户"最后清理刷怪空间") 结构完工后清空内圈
            // 5x5 × y..y+2 的障碍 (不含刷怪笼本体), 给烈焰人生成和走位腾地方。
            BlockPos interior = findInteriorObstructor(mod, _foundBlazeSpawner);
            if (interior != null) {
                setDebugState("Clearing spawn space inside enclosure: " + interior.toShortString());
                return new DestroyBlockTask(interior);
            }

            // (2026-09-19 13:05 用户) bot 留在自己围起来的区域里打最方便最安全:
            // 每 10s 检查一次, 离开围场就回去 (允许短暂离开)。
            if (_returnCheckTimer.elapsed()) {
                _returnCheckTimer.reset();
                if (!isInsideEnclosure(mod.getPlayer().getBlockPos())) {
                    setDebugState("Returning to our enclosure");
                    return new GetToBlockTask(_foundBlazeSpawner.up(), false);
                }
            }

            // Put out fire that might mess with us.
            Optional<BlockPos> nearestFire = mod.getBlockTracker().getNearestWithinRange(_foundBlazeSpawner, 5, Blocks.FIRE);
            if (nearestFire.isPresent()) {
                setDebugState("Clearing fire around spawner to prevent loss of blaze rods.");
                return new PutOutFireTask(nearestFire.get());
            }

            setDebugState("Waiting inside our enclosure for blazes to spawn");
            return null;
        } else {
            // Search for blaze
            // (2026-09-04 人类式感知) 原来 getKnownLocations(SPAWNER) 绕过了
            // 感知过滤 —— 岩石后面的刷怪笼也会被"知道"。改为 getPerceivedLocations:
            // 只有 LOS+FOV 亲眼看见的刷怪笼才能确立目标; 走廊巡逻自然会看见它。
            for (BlockPos pos : mod.getBlockTracker().getPerceivedLocations(Blocks.SPAWNER)) {
                // (2026-09-19 17:20) 排除表同样拦感知重确认 —— 否则刚被换掉的
                // 岩浆笼就在眼前、每 tick 被"亲眼看见"重新确立, 无限回拉 (171228
                // 实锤: 换笼后 0.3s 内又确认回同一个岩浆笼)。
                if (isRejectedSpawner(pos)) continue;
                if (isValidBlazeSpawner(mod, pos)) {
                    _foundBlazeSpawner = pos;
                    // (2026-09-18) 亲眼确认 → 写 static, 后续实例重建能直接接回。
                    if (_worldKey != null) {
                        if (_confirmedSpawnerByKey.put(_worldKey, pos.toImmutable()) == null) {
                            Debug.logMessage("[BLAZE-SPAWNER] 亲眼确认刷怪笼并记住 " + pos.toShortString());
                        }
                    }
                    registerEnclosureProtection(mod);
                    break;
                }
            }
            // (2026-09-18) 亲眼也没看见 → 从跨会话记忆里召回一个候选点直接过去。
            if (_foundBlazeSpawner == null) {
                Task recall = recallSpawnerFromMemory(mod);
                if (recall != null) return recall;
            }
        }

        // We need to find our fortress.
        // (2026-09-04 人类式探索) 默认 FortressExploreTask (循声+走廊地面);
        // 超过 fortressSearchFallbackSeconds 仍未找到时降级回旧 chunk 扫描兜底。
        int fallbackSeconds = mod.getModSettings().getFortressSearchFallbackSeconds();
        if (fallbackSeconds > 0 && _searchTimer.getDuration() > fallbackSeconds) {
            if (!_fallbackEngaged) {
                _fallbackEngaged = true;
                Debug.logMessage("[FortressSearch] human-like explore timed out (" + fallbackSeconds
                        + "s), falling back to privileged chunk scan.");
            }
            setDebugState("Searching for fortress (fallback chunk scan)");
            return _searcher;
        }
        setDebugState("Searching for fortress (human-like explore)");
        return _fortressSearcher;
    }

    // ------------------------------------------------------------------ //
    // (2026-09-18 刷怪笼记忆) 召回 / 排除 / 失效
    // ------------------------------------------------------------------ //

    /**
     * 从跨会话记忆 ({@link KnowledgeMemory.StructureType#BLAZE_SPAWNER}) 取最近
     * 的、未被排除过的刷怪笼候选点, 直接导航过去。返回 null = 没有候选, 交由
     * 下面的走廊探索。
     *
     * 三段确认串起来才是"人记得并直接去": ① 记忆里有坐标 (跨会话落盘); ② 走到
     * 现场 (成功导航, 不是远程猜); ③ 现场验明确实是烈焰笼。
     */
    private Task recallSpawnerFromMemory(AltoClef mod) {
        if (_worldKey == null || mod.getPlayer() == null) return null;
        Vec3d pos = mod.getPlayer().getPos();
        List<BlockPos> candidates = new ArrayList<>(mod.getKnowledgeMemory()
                .getRememberedCenters(KnowledgeMemory.StructureType.BLAZE_SPAWNER, pos, SPAWNER_RECALL_RANGE));
        candidates.removeIf(this::isRejectedSpawner);
        if (candidates.isEmpty()) return null;

        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (BlockPos c : candidates) {
            double sq = c.getSquaredDistance(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = c;
            }
        }
        if (best == null) return null;

        if (_recallTarget == null || !_recallTarget.equals(best)) {
            _recallTarget = best;
            _recallTimer.reset();
            Debug.logMessage("[BLAZE-SPAWNER] 记忆召回候选 " + best.toShortString()
                    + " (距离 " + String.format(java.util.Locale.ROOT, "%.0f", Math.sqrt(bestSq)) + ", 候选数 " + candidates.size() + ")");
        }

        // 走到现场且区块可读 → 验明正身。
        if (mod.getChunkTracker().isChunkLoaded(best) && best.isWithinDistance(pos, SPAWNER_RECALL_ARRIVE)) {
            if (isValidBlazeSpawner(mod, best)) {
                _foundBlazeSpawner = best;
                _confirmedSpawnerByKey.put(_worldKey, best.toImmutable());
                Debug.logMessage("[BLAZE-SPAWNER] 现场验明通过, 确立刷怪笼 " + best.toShortString());
                registerEnclosureProtection(mod);
            } else {
                // 记得这里有刷怪笼, 但它不是烈焰人的 (主世界蜘蛛笼之类)。
                rejectSpawner(best, "记忆点不是烈焰刷怪笼");
            }
            _recallTarget = null;
            return null;
        }

        // 太久没走到 = 够不着/被地形挡死, 排除后换下一个候选。
        if (_recallTimer.getDuration() > SPAWNER_RECALL_TIMEOUT) {
            Debug.logToFile("altoclef_diag.log", "[BLAZE-SPAWNER] 召回前往超时 " + SPAWNER_RECALL_TIMEOUT
                    + "s (停在 " + mod.getPlayer().getBlockPos().toShortString() + ", 目标 " + best.toShortString() + ")");
            rejectSpawner(best, "前往超时");
            _recallTarget = null;
            return null;
        }

        setDebugState("Going to remembered blaze spawner");
        return new GetToBlockTask(best.up(), false);
    }

    /** 该候选是否已被标记排除 (记忆点与实际不符 / 够不着)。 */
    private boolean isRejectedSpawner(BlockPos candidate) {
        Set<BlockPos> rejected = _rejectedSpawnerByKey.get(_worldKey);
        if (rejected == null || rejected.isEmpty()) return false;
        for (BlockPos p : rejected) {
            if (p.isWithinDistance(candidate, REJECT_RADIUS)) return true;
        }
        return false;
    }

    private void rejectSpawner(BlockPos pos, String reason) {
        if (_worldKey == null || pos == null) return;
        Set<BlockPos> rejected = _rejectedSpawnerByKey.computeIfAbsent(_worldKey, k -> new HashSet<>());
        if (rejected.size() >= MAX_REJECTED_SPAWNERS) {
            // 容量兜底: 整体作废重来, 宁可偶尔重试一次旧的错误点, 也不要无限增长。
            rejected.clear();
        }
        rejected.add(pos.toImmutable());
        Debug.logToFile("altoclef_diag.log", "[BLAZE-SPAWNER] 登记排除 " + pos.toShortString() + " — " + reason);
    }

    /** 已确认的刷怪笼失效: 清 static 记忆并登记排除, 免得下次又被召回。 */
    private void invalidateSpawner(BlockPos pos, String reason) {
        if (_worldKey != null) {
            _confirmedSpawnerByKey.remove(_worldKey, pos);
        }
        // (2026-09-19 17:20) 保护【不】随失效清除 —— 换笼后 bot 还在旧笼边上
        // 补货, 保护若立刻解除, 近场链挖会把刚搭好的墙当最近下界岩挖掉 (171228
        // 实锤: 换笼后 restock 挖掉自己围场的整圈墙)。保护一直持续到新刷怪笼
        // 确立 (registerEnclosureProtection 覆写) 或换存档。
        rejectSpawner(pos, reason);
    }

    /**
     * (2026-09-18) 换存档时清空本任务的全部 static 状态 —— 这类【坐标】残留 =
     * 上个世界的刷怪笼被当成这个世界的导航目标, 与 FortressExploreTask 锚点同例。
     */
    public static void onWorldBoundary() {
        _confirmedSpawnerByKey.clear();
        _rejectedSpawnerByKey.clear();
        _enclosureStockedFor = null;
        _protectedSpawnerPos = null;
        _lavaTroubleSpawnerKeys.clear();
    }

    /**
     * (2026-09-19 16:05 问题二b) 围场结构位置判定 (静态供行为谓词用)。
     * 材质无关: 围墙"只要求有东西堵着"—— 栅栏等任何实体方块都算 (isSolid 口径),
     * 不要求非得是地狱岩。
     */
    private static BlockPos _protectedSpawnerPos = null;
    private static boolean _protectionRegistered = false;

    /**
     * (2026-09-19 16:22 修订) 围场保护盒: 刷怪笼周围 9x9 × y-2..+4 整盒禁拆 +
     * 附近 16 格内的栅栏/墙类方块禁拆。上一版只保护"精确结构位", 用户自摆的
     * 栅栏位置/高度不固定落在保护集外, 照样被 baritone 寻路拆掉 (16:22 实锤)。
     * 材质无关: 围墙"只要求有东西堵着"。
     */
    private static final int SPAWNER_PROTECT_HALF = 4;

    private static boolean isEnclosureStructurePos(AltoClef mod, BlockPos pos) {
        BlockPos s = _protectedSpawnerPos;
        if (s == null) return false;
        int dx = Math.abs(pos.getX() - s.getX());
        int dz = Math.abs(pos.getZ() - s.getZ());
        int dy = pos.getY() - s.getY();
        // (2026-09-19 17:33 用户"最后要清理刷怪空间") 内圈 5x5 × y..y+2 (刷怪笼
        // 本体除外) 是要清空的刷怪空间, 不在保护集里 —— 否则清障拆不动。
        if (dy >= 0 && dy <= 2 && dx <= SPAWNER_FLOOR_HALF && dz <= SPAWNER_FLOOR_HALF) {
            if (!(dx == 0 && dz == 0 && dy == 0)) return false;
        }
        // 整盒保护: 9x9 水平 × 低两格到高四格。
        if (dx <= SPAWNER_PROTECT_HALF && dz <= SPAWNER_PROTECT_HALF && dy >= -2 && dy <= 4) return true;
        // 栅栏/墙类方块在更大范围 (16 格) 内也保护 — 用户拿它们当围墙用。
        Block b = mod.getWorld().getBlockState(pos).getBlock();
        if ((b instanceof FenceBlock || b instanceof WallBlock)
                && pos.isWithinDistance(s, 16)) return true;
        return false;
    }

    /** 注册围场防拆谓词 (整个会话一次; 谓词读静态刷怪笼位, 换存档自动失效)。 */
    private void registerEnclosureProtection(AltoClef mod) {
        _protectedSpawnerPos = _foundBlazeSpawner.toImmutable();
        if (_protectionRegistered) return;
        _protectionRegistered = true;
        mod.getBehaviour().avoidBlockBreaking(pos -> isEnclosureStructurePos(mod, pos));
        Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] structure break-protection registered (9x9 box + nearby fences)");
    }

    /** (2026-09-19 16:05 问题三) 岩浆 Trouble 登记表 (存档key@刷怪笼坐标)。 */
    private static final Set<String> _lavaTroubleSpawnerKeys = new HashSet<>();
    private static final long LAVA_TROUBLE_SWITCH_MS = 45_000;
    private long _lavaTroubleSinceMs = 0;

    // (2026-09-19 16:05 用户补充: 免逃岩浆只针对建围墙时的特殊宽限, 其他情况不变)
    // 围场施工进行中 (已到笼、缺块未补) 的全局标志 —— WorldSurvivalChain 只在
    // [防火效果 + 本标志] 同时成立时才豁免逃岩浆/灭火, 其余场景一律照旧。
    private static volatile boolean _enclosureBuildActive = false;

    /** 围场墙施工是否进行中 (供 WorldSurvivalChain 的岩浆/火焰豁免判定)。 */
    public static boolean isEnclosureBuildWorkActive() {
        return _enclosureBuildActive;
    }

    private String lavaTroubleKey() {
        return (_worldKey == null || _foundBlazeSpawner == null)
                ? null : _worldKey + "@" + _foundBlazeSpawner.toShortString();
    }

    /** 围场区域 (7x7 水平 × y-1..y+3) 内是否有岩浆。 */
    private boolean isLavaInEnclosureRegion(AltoClef mod, BlockPos spawner) {
        for (int dy = -1; dy <= 3; dy++) {
            for (int dx = -SPAWNER_WALL_HALF; dx <= SPAWNER_WALL_HALF; dx++) {
                for (int dz = -SPAWNER_WALL_HALF; dz <= SPAWNER_WALL_HALF; dz++) {
                    if (mod.getWorld().getBlockState(spawner.add(dx, dy, dz)).getBlock() == Blocks.LAVA) return true;
                }
            }
        }
        return false;
    }

    /**
     * (2026-09-19 16:05 问题三, 用户) 当前刷怪笼持续 45s 有岩浆 → 若记忆里有
     * 别的 (非排除、非岩浆) 刷怪笼候选就切换过去; 没有就留下来继续盖 (墙能挡)。
     */
    private void trySwitchAwayFromLavaSpawner(AltoClef mod) {
        if (_worldKey == null || _foundBlazeSpawner == null || mod.getPlayer() == null) return;
        Vec3d pos = mod.getPlayer().getPos();
        List<BlockPos> candidates = new ArrayList<>(mod.getKnowledgeMemory()
                .getRememberedCenters(KnowledgeMemory.StructureType.BLAZE_SPAWNER, pos, SPAWNER_RECALL_RANGE));
        candidates.removeIf(c -> isRejectedSpawner(c)
                || _lavaTroubleSpawnerKeys.contains(_worldKey + "@" + c.toShortString())
                || c.getSquaredDistance(_foundBlazeSpawner) < REJECT_RADIUS * REJECT_RADIUS);
        if (candidates.isEmpty()) {
            Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] lava persists but no alternative spawner known, keep building");
            _lavaTroubleSinceMs = System.currentTimeMillis(); // 重置, 45s 后再查
            return;
        }
        String key = lavaTroubleKey();
        if (key != null) _lavaTroubleSpawnerKeys.add(key);
        Debug.logMessage("[BLAZE-ENCLOSURE] spawner lava-troubled (45s+), switching to another known spawner ("
                + candidates.size() + " candidates)");
        Debug.logToFile("altoclef_diag.log", "[BLAZE-ENCLOSURE] switch away from lava spawner " + _foundBlazeSpawner.toShortString());
        invalidateSpawner(_foundBlazeSpawner, "lava pouring into region, switching away");
        _foundBlazeSpawner = null;
        _recallTarget = null;
        _recallTimer.reset();
        _cachedMissingEnclosure = null;
        _lavaTroubleSinceMs = 0;
        clearEnclosureRestockTask();
    }

    /**
     * (2026-09-19 17:33 用户"最后清理刷怪空间") 内圈 5x5 × y..y+2 (不含刷怪笼
     * 本体) 里第一个可拆的障碍块; 全空返回 null。岩浆/基岩等拆不动的跳过。
     */
    private BlockPos findInteriorObstructor(AltoClef mod, BlockPos spawner) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -SPAWNER_FLOOR_HALF; dx <= SPAWNER_FLOOR_HALF; dx++) {
                for (int dz = -SPAWNER_FLOOR_HALF; dz <= SPAWNER_FLOOR_HALF; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue; // 刷怪笼本体
                    BlockPos p = spawner.add(dx, dy, dz);
                    if (mod.getWorld().getBlockState(p).isAir()) continue;
                    if (!WorldHelper.canBreak(mod, p)) continue;
                    return p.toImmutable();
                }
            }
        }
        return null;
    }

    private boolean isValidBlazeSpawner(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            // If unloaded, go to it. Unless it's super far away.
            return false;
            //return pos.isWithinDistance(mod.getPlayer().getPos(),3000);
        }
        return WorldHelper.getSpawnerEntity(mod, pos) instanceof BlazeEntity;
    }

    /**
     * (2026-09-19 包围策略④) 已到达确认刷怪笼旁、且围场作业还没做完 (该喝的药
     * 没喝 / 围场缺块未补或还没扫过) → 期间不开杀, 由刷怪笼分支执行喝药与建围
     * 场。不在场或已确认完工则返回 false, 围场内击杀照常。
     */
    private boolean isSpawnerWorkPending(AltoClef mod) {
        if (_foundBlazeSpawner == null) return false;
        if (!_foundBlazeSpawner.isWithinDistance(mod.getPlayer().getPos(), 4)) return false;
        // 该喝药没喝 (包里有药且没效果) — 没药时不算 pending, 不死等。
        if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                && ItemHelper.countFireResPotions(mod) > 0) {
            return true;
        }
        // 还没扫过 (缓存 null) 或扫出缺口 → 围场未确认完工, 不开杀。
        List<BlockPos> missing = _cachedMissingEnclosure;
        if (missing == null || !missing.isEmpty()) return true;
        // (2026-09-19 17:33) 刷怪空间还没清完也算 pending — 先清场再开杀。
        return findInteriorObstructor(mod, _foundBlazeSpawner) != null;
    }

    /**
     * (2026-09-19 17:20 终版规格) 围场缺口全扫, 顺序自上向下:
     * 顶盖 (y+3, 5x5) → 围墙 (y+1, 7x7 外圈单圈) → 地板 (y-1, 5x5)。
     */
    private List<BlockPos> findMissingEnclosureBlocks(AltoClef mod, BlockPos spawner) {
        List<BlockPos> missing = new ArrayList<>();
        // ① 顶盖 y+3, 5x5。
        for (int dx = -SPAWNER_FLOOR_HALF; dx <= SPAWNER_FLOOR_HALF; dx++) {
            for (int dz = -SPAWNER_FLOOR_HALF; dz <= SPAWNER_FLOOR_HALF; dz++) {
                BlockPos p = spawner.add(dx, SPAWNER_ROOF_HEIGHT, dz);
                if (!WorldHelper.isSolid(mod, p)) missing.add(p.toImmutable());
            }
        }
        // ② 围墙 y+1, 7x7 外圈。
        for (int dx = -SPAWNER_WALL_HALF; dx <= SPAWNER_WALL_HALF; dx++) {
            for (int dz = -SPAWNER_WALL_HALF; dz <= SPAWNER_WALL_HALF; dz++) {
                if (Math.abs(dx) != SPAWNER_WALL_HALF && Math.abs(dz) != SPAWNER_WALL_HALF) continue;
                BlockPos p = spawner.add(dx, SPAWNER_WALL_HEIGHT, dz);
                if (!WorldHelper.isSolid(mod, p)) missing.add(p.toImmutable());
            }
        }
        // ③ 脚底地板 y-1, 5x5。
        for (int dx = -SPAWNER_FLOOR_HALF; dx <= SPAWNER_FLOOR_HALF; dx++) {
            for (int dz = -SPAWNER_FLOOR_HALF; dz <= SPAWNER_FLOOR_HALF; dz++) {
                BlockPos p = spawner.add(dx, -1, dz);
                if (!WorldHelper.isSolid(mod, p)) missing.add(p.toImmutable());
            }
        }
        return missing;
    }

    /**
     * (2026-09-19 13:05) bot 是否还在自己围起来的 7x7 区域内 (只看水平投影,
     * 允许任何高度 — 被顶一下/跳一下不算离开)。
     */
    private boolean isInsideEnclosure(BlockPos pos) {
        if (_foundBlazeSpawner == null) return false;
        int dx = pos.getX() - _foundBlazeSpawner.getX();
        int dz = pos.getZ() - _foundBlazeSpawner.getZ();
        return Math.abs(dx) <= SPAWNER_WALL_HALF && Math.abs(dz) <= SPAWNER_WALL_HALF;
    }

    // ------------------------------------------------------------------ //
    // (2026-09-19 卡死修复 120616) 包围补货任务粘性。父任务每 tick 重估子任务,
    // 每 tick new 一个 MineAndCollect 会反复重启寻路/追踪 (铁律 #3 同款);
    // 这里复用实例, 只在目标上调时重建, 缺料解决后由调用方 clear。
    // ------------------------------------------------------------------ //
    private Task _enclosureRestockTask = null;
    private int _enclosureRestockTarget = -1;

    // (2026-09-19 123515 追加) "出发前置 32 已备足"的一次性闩, 记到 存档key@刷怪笼坐标。
    // 不闩的话路上 baritone 每垫一块脚手架 31<32 就弹回补货 (恰好 32 零余量),
    // 2s 一轮 Restock↔Going 死循环。换存档 (onWorldBoundary) 自动失效。
    private static String _enclosureStockedFor = null;

    /** 当前 (存档key @ 已确认刷怪笼) 的备料闩 key; 无刷怪笼返回 null。 */
    private String enclosureStockKey() {
        return (_worldKey == null || _foundBlazeSpawner == null)
                ? null : _worldKey + "@" + _foundBlazeSpawner.toShortString();
    }

    private Task getEnclosureRestockTask(AltoClef mod, int target) {
        // (2026-09-19 17:20) 重建条件 = 未创建 / 目标上调 / 旧任务已凑满。同目标
        // 重建不刷日志 (避免 一秒 20 条 CHAT 刷屏, 171228 实锤)。
        boolean recreate = _enclosureRestockTask == null
                || target > _enclosureRestockTarget
                || _enclosureRestockTask.isFinished(mod);
        boolean logIt = recreate && target != _enclosureRestockTarget;
        if (recreate) {
            _enclosureRestockTarget = target;
            _enclosureRestockTask = TaskCatalogue.getItemTask(Items.NETHERRACK, target);
            if (logIt) {
                Debug.logMessage("[BLAZE-ENCLOSURE] restock task target=" + target
                        + " (netherrack now " + StorageHelper.countItemInInventoryReal(mod, Items.NETHERRACK) + ")");
            }
        }
        return _enclosureRestockTask;
    }

    private void clearEnclosureRestockTask() {
        _enclosureRestockTask = null;
        _enclosureRestockTarget = -1;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.SPAWNER);
        clearEnclosureRestockTask();
        _enclosureBuildActive = false;
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectBlazeRodsTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " blaze rods";
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }
}
