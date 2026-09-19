package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
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
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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
    // (2026-09-19 刷怪笼包围策略, 用户) 浮在空中的烈焰人要去搭路追打, 极危险
    // (0919 搭路靠近烈焰人摔死实锤)。改为"先封顶关笼子, 再在罩子里收割":
    //   ① 出发前背包至少 32 地狱岩 (封顶 5x5=25 格 + 余量);
    //   ② 到达刷怪笼先喝防火药水 (有药才等, 没药直接往下走, 不死等);
    //   ③ 刷怪笼上方两格高度的 5x5 区域用方块封顶, 烈焰人飞不出去;
    //   ④ 封顶完成前不开杀 (半路追打空中烈焰人正是摔死事故的根源)。
    // =========================================================================
    private static final int ENCLOSURE_NETHERRACK_MIN = 32;
    // 封顶层相对刷怪笼的高度偏移 ("上面两格高度") 与 5x5 的半径 (±2)。
    private static final int SPAWNER_CEILING_HEIGHT = 2;
    private static final int SPAWNER_CEILING_HALF = 2;
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
        BlockPos remembered = _confirmedSpawnerByKey.get(_worldKey);
        if (remembered != null) {
            _foundBlazeSpawner = remembered;
            Debug.logMessage("[BLAZE-SPAWNER] 沿用已确认刷怪笼 " + remembered.toShortString()
                    + " (实例重建, key=" + _worldKey + ")");
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

        // (2026-09-19 包围策略④) 刷怪笼作业未完成 (没喝药 / 没封顶) 时不开杀 —
        // 半路去追打飘在空中的烈焰人正是 0919 摔死事故的根源, 先把笼子关上。
        boolean spawnerWorkPending = isSpawnerWorkPending(mod);

        Optional<Entity> toKill = Optional.empty();
        // If there is a blaze, kill it.
        if (mod.getEntityTracker().entityFound(BlazeEntity.class)) {

            // If we're in danger and there are too many blazes, run away.
            if (mod.getEntityTracker().getTrackedEntities(BlazeEntity.class).size() >= TOO_MANY_BLAZES && mod.getPlayer().getHealth() <= TOO_LITTLE_HEALTH_BLAZE) {
                setDebugState("Running away as there are too many blazes nearby.");
                return new TimeoutWanderTask();
            }

            toKill = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), BlazeEntity.class);

            if (_foundBlazeSpawner != null && toKill.isPresent()) {
                Entity kill = toKill.get();
                Vec3d nearest = kill.getPos();

                double sqDistanceToPlayer = nearest.squaredDistanceTo(mod.getPlayer().getPos());//_foundBlazeSpawner.getX(), _foundBlazeSpawner.getY(), _foundBlazeSpawner.getZ());
                // Ignore if the blaze is too far away.
                if (sqDistanceToPlayer > SPAWNER_BLAZE_RADIUS * SPAWNER_BLAZE_RADIUS) {
                    // If the blaze can see us it needs to go lol
                    BlockHitResult hit = mod.getWorld().raycast(new RaycastContext(mod.getPlayer().getCameraPosVec(1.0F), kill.getCameraPosVec(1.0F), RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mod.getPlayer()));
                    if (hit != null && hit.getBlockPos().getSquaredDistance(mod.getPlayer().getPos()) < sqDistanceToPlayer) {
                        toKill = Optional.empty();
                    }
                }
            }
        }

        if (toKill.isPresent() && toKill.get().isAlive() && !spawnerWorkPending) {
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
            setDebugState("Killing blaze");
            return new KillEntitiesTask(entity -> !isHoveringAboveLavaOrTooHigh(mod, entity), BlazeEntity.class);
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
            boolean arrived = _foundBlazeSpawner.isWithinDistance(mod.getPlayer().getPos(), 4);
            int netherrackHave = StorageHelper.countItemInInventoryReal(mod, Items.NETHERRACK);
            if (!arrived) {
                // (2026-09-19 包围策略①, 用户) 出发前置: 背包至少 32 地狱岩
                // (封顶 5x5=25 格 + 余量), 不够先挖满 — 到了现场再下矿等于裸奔。
                // (2026-09-19 123515 追加) 一次性闩: 补满一次即闩上, 不再反复检查
                // —— 否则路上 baritone 每垫一块脚手架 31<32 又弹回补货, 恰好 32
                // 无余量, 2s 一轮 Restock↔Going 死循环 (123515 实锤)。路上的
                // 脚手架消耗是正常开销, 到笼后由"缺几补几"的精确口径接管。
                String stockKey = enclosureStockKey();
                boolean stockedForThisSpawner = stockKey != null && stockKey.equals(_enclosureStockedFor);
                if (!stockedForThisSpawner && netherrackHave < ENCLOSURE_NETHERRACK_MIN) {
                    setDebugState("Restocking " + ENCLOSURE_NETHERRACK_MIN + " netherrack for spawner enclosure");
                    return getEnclosureRestockTask(mod, ENCLOSURE_NETHERRACK_MIN);
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
            // 在这一步 (防火效果本身不是封顶的硬前置, 硬等会死循环)。
            if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                    && ItemHelper.countFireResPotions(mod) > 0) {
                setDebugState("Drinking fire resistance at spawner (before enclosure)");
                return new DrinkFireResPotionTask();
            }

            // (2026-09-19 包围策略③) 封顶: 刷怪笼上方两格高度 5x5, 缺哪补哪。
            // 封死烈焰人往上飞出去的路, 之后在罩子里近战收割, 不再搭路追打。
            // (2026-09-19 卡死修复 120616) 封顶中途缺料只补【剩余缺口】—— 旧的
            // "任何时候 <32 就中断去补满 32" 把封顶打到一半拆去补货 (放掉几块就
            // 31<32), 补货又追一个洞沿掉落物死循环 5 分钟 (20260919-120616 实锤)。
            // 32 只是出发前置, 不是封顶全程的库存红线。
            int missingCount = countMissingCeilingBlocks(mod, _foundBlazeSpawner);
            if (missingCount > 0) {
                if (netherrackHave < missingCount) {
                    setDebugState("Restocking netherrack for ceiling (missing " + missingCount
                            + ", have " + netherrackHave + ")");
                    // 目标 = missing (ResourceTask 是"凑够 n 个"语义): 放一块
                    // have/missing 同减, have≥missing 恒保持, 恰好够用。+have 会
                    // 过量收集, 且目标每 tick 随 have 变 → 任务反复重建 (123515 复盘)。
                    return getEnclosureRestockTask(mod, missingCount);
                }
                clearEnclosureRestockTask();
                BlockPos missingCeiling = findMissingCeilingBlock(mod, _foundBlazeSpawner);
                setDebugState("Enclosing spawner: ceiling 5x5 at +"
                        + SPAWNER_CEILING_HEIGHT + ", missing " + missingCeiling.toShortString());
                return new PlaceStructureBlockTask(missingCeiling);
            }

            // Put out fire that might mess with us.
            Optional<BlockPos> nearestFire = mod.getBlockTracker().getNearestWithinRange(_foundBlazeSpawner, 5, Blocks.FIRE);
            if (nearestFire.isPresent()) {
                setDebugState("Clearing fire around spawner to prevent loss of blaze rods.");
                return new PutOutFireTask(nearestFire.get());
            }

            setDebugState("Waiting near blaze spawner for blazes to spawn");
            return null;
        } else {
            // Search for blaze
            // (2026-09-04 人类式感知) 原来 getKnownLocations(SPAWNER) 绕过了
            // 感知过滤 —— 岩石后面的刷怪笼也会被"知道"。改为 getPerceivedLocations:
            // 只有 LOS+FOV 亲眼看见的刷怪笼才能确立目标; 走廊巡逻自然会看见它。
            for (BlockPos pos : mod.getBlockTracker().getPerceivedLocations(Blocks.SPAWNER)) {
                if (isValidBlazeSpawner(mod, pos)) {
                    _foundBlazeSpawner = pos;
                    // (2026-09-18) 亲眼确认 → 写 static, 后续实例重建能直接接回。
                    if (_worldKey != null) {
                        if (_confirmedSpawnerByKey.put(_worldKey, pos.toImmutable()) == null) {
                            Debug.logMessage("[BLAZE-SPAWNER] 亲眼确认刷怪笼并记住 " + pos.toShortString());
                        }
                    }
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
     * (2026-09-19 包围策略) 已到达确认刷怪笼旁、且包围作业还没做完 (该喝的药没喝 /
     * 封顶没封完) → 期间不开杀, 由刷怪笼分支执行喝药与封顶。不在场或作业完成
     * 则返回 false, 击杀逻辑照常。
     */
    private boolean isSpawnerWorkPending(AltoClef mod) {
        if (_foundBlazeSpawner == null) return false;
        if (!_foundBlazeSpawner.isWithinDistance(mod.getPlayer().getPos(), 4)) return false;
        // 该喝药没喝 (包里有药且没效果) — 没药时不算 pending, 不死等。
        if (!mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                && ItemHelper.countFireResPotions(mod) > 0) {
            return true;
        }
        return findMissingCeilingBlock(mod, _foundBlazeSpawner) != null;
    }

    /**
     * (2026-09-19 包围策略③) 找封顶层 (刷怪笼上方 {@link #SPAWNER_CEILING_HEIGHT}
     * 格, 5x5) 里第一块还没被实体方块遮住的位置; 全遮住返回 null。
     */
    private BlockPos findMissingCeilingBlock(AltoClef mod, BlockPos spawner) {
        BlockPos layer = spawner.up(SPAWNER_CEILING_HEIGHT);
        for (int dx = -SPAWNER_CEILING_HALF; dx <= SPAWNER_CEILING_HALF; dx++) {
            for (int dz = -SPAWNER_CEILING_HALF; dz <= SPAWNER_CEILING_HALF; dz++) {
                BlockPos p = layer.add(dx, 0, dz);
                if (!WorldHelper.isSolid(mod, p)) return p.toImmutable();
            }
        }
        return null;
    }

    /** 封顶层还缺几块 (5x5 里非实体方块的格数)。 */
    private int countMissingCeilingBlocks(AltoClef mod, BlockPos spawner) {
        BlockPos layer = spawner.up(SPAWNER_CEILING_HEIGHT);
        int missing = 0;
        for (int dx = -SPAWNER_CEILING_HALF; dx <= SPAWNER_CEILING_HALF; dx++) {
            for (int dz = -SPAWNER_CEILING_HALF; dz <= SPAWNER_CEILING_HALF; dz++) {
                if (!WorldHelper.isSolid(mod, layer.add(dx, 0, dz))) missing++;
            }
        }
        return missing;
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
        if (_enclosureRestockTask == null || target > _enclosureRestockTarget) {
            _enclosureRestockTarget = target;
            _enclosureRestockTask = TaskCatalogue.getItemTask(Items.NETHERRACK, target);
            Debug.logMessage("[BLAZE-ENCLOSURE] restock task target=" + target
                    + " (netherrack now " + StorageHelper.countItemInInventoryReal(mod, Items.NETHERRACK) + ")");
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
