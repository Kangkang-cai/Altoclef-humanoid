package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class CollectBedTask extends CraftWithMatchingWoolTask {

    public static final Block[] BEDS = ItemHelper.itemsToBlocks(ItemHelper.BED);

    // (2026-08-27 收床来源距离) 感知到的现成床只有在 96 格内才"挖去拿";
    // 超过则走合成分支(杀羊/羊毛做床), 不为一张床长途折返。
    private static final double BED_GRAB_RANGE = 96.0;

    // Visual-only: used to keep isEqualResource stable across craft/mine swings.
    private final ItemTarget _visualBedTarget;

    // (2026-09-08 挖床站桩兜底, 用户"晚上想睡但感知不到床/羊, bot 呆住 39.7s 直到调白天"
    //  日志实锤 altoclef_tasks-20260908-180420.log t=51→t=844) 挖床分支 MineAndCollect(bed)
    //  锁不到目标时内层每 tick 返回 null 站桩, 而所有放弃兜底(gaveUpWandering 2s/床无目标
    //  放弃)都挂在 MineAndCollectTask.isFinished — Task.tickInternal 从不调用子任务
    //  isFinished, 床链(CollectBedTask/SleepThroughNight)也无人查 → 兜底死代码, 站到天亮。
    //  且挖床分支 12s 滞回 + anyFound 持续 true → 永不回落杀羊取毛。
    //  修复: 父层(CollectBedTask)显式查 gaveUpWandering, 放弃后 30s 冷却挖床分支(触发
    //  条件层冷却, 同 6eedb369), 回落 super 杀羊取毛路径(无羊时 BiasedExplore 主动找)。
    //  SleepThroughNightTask 每 tick new PlaceBedAndSetSpawnTask → 本任务每 tick 重建,
    //  冷却与缓存实例必须 static(同 9dbeb779 教训), 否则重建绕过一切兜底。
    private static final long MINE_BED_GIVEUP_COOLDOWN_MS = 30_000;
    private static long s_mineBedGiveUpUntilMs = 0;
    private static MineAndCollectTask s_bedMineTask = null;

    // (2026-09-08 B修: 闸门/选目标口径对齐) 分支闸门与 MineAndCollectTask.getClosestTo
    // 的候选过滤必须同一套, 否则"闸门说有床、挖床锁不到"→ 只能靠放弃兜底兜住。
    // 闸门此前缺: ①真实方块仍是床(缓存陈旧/床被拆 → canBreak 对空气照样过);
    // ②区块已加载才信世界校验; ③贴液体不挖。isBlockPerceivable 对床是
    // 无条件通过(VisibilityHelper 床保险, 穿墙), 重复判无意义, 移除。
    private String bedRejectReason(AltoClef mod, BlockPos bed, BlockPos playerPos) {
        if (!bed.isWithinDistance(playerPos, BED_GRAB_RANGE)) return "far96";
        if (!mod.getChunkTracker().isChunkLoaded(bed)) return "chunk-unloaded";
        Block actual = mod.getWorld().getBlockState(bed).getBlock();
        if (!(actual instanceof BedBlock)) return "not-bed(" + actual.asItem() + ")";
        if (WorldHelper.isAdjacentToLiquid(mod, bed)) return "liquid";
        String cbFail = WorldHelper.canBreakFailReason(mod, bed);
        return cbFail == null ? null : ("canBreak:" + cbFail);
    }

    // 闸门命中诊断(5s 节流): 记录选中的床坐标与距离, 复现"切了挖床却锁不到"时直接定位。
    private static long s_lastGateDiagMs = 0;

    // (2026-09-21 合成优先, 用户"爬上去 vs 自己做, bot 选了更费劲的, 似乎跳过了制作床")
    // 160148 实锤: 地下 y≈18, 背包 27 原木+羊毛够做全部缺口床, 合成链刚起了头
    // (Crafting 12 planks → Receiving output) 就被挖床分支抢走 → "Baritone pillar
    // to 9829,69,-10662" 垫塔 51 格去拆村庄床。根因: 挖床分支闸门只看
    // "12s 滞回 + 96 格内可挖床", 不看合成可行性 → 原料够也永远优先挖床。
    // 修复: 缺口床原料够就地合成 → 一律走合成分支; 床在 BED_GRAB_NEAR_RANGE 内
    // 仍优先挖 (走两步的事, 不必耗合成台)。合成完床数达标任务自然结束, 无回摆。
    private static final double BED_GRAB_NEAR_RANGE = 16.0;
    private static long s_lastCraftOverrideLogMs = 0;

    /** 缺口床数 = 目标 − 背包床 (本任务内口径; 自放床/末地掉落床由上层 getBedGap 管)。 */
    private int bedGapHere(AltoClef mod) {
        return Math.max(0, _visualBedTarget.getTargetCount()
                - mod.getItemStorage().getItemCount(ItemHelper.BED));
    }

    /**
     * 背包原料够不够就地合成 {@code gap} 张床: 床配方 = 同色羊毛 3 + 木板 3
     * (按任一单色计), 木板可由原木合成 (1 原木→4 木板)。合成台缺失不阻断
     * (原木能造合成台)。与 BeatMinecraft2Task.canCraftBedsFromInventory 同口径。
     */
    private boolean canCraftBedsFromInventory(AltoClef mod, int gap) {
        if (gap <= 0) return false;
        int woolNeeded = 3 * gap;
        int bestSingleColor = 0;
        for (Item wool : ItemHelper.WOOL) {
            bestSingleColor = Math.max(bestSingleColor, mod.getItemStorage().getItemCount(wool));
        }
        if (bestSingleColor < woolNeeded) return false;
        int planks = 0;
        for (Item plank : ItemHelper.PLANKS) {
            planks += mod.getItemStorage().getItemCount(plank);
        }
        int logs = 0;
        for (Item log : ItemHelper.LOG) {
            logs += mod.getItemStorage().getItemCount(log);
        }
        return planks + logs * 4 >= woolNeeded;
    }

    // (2026-09-09 23:20 判别日志, 用户"别废话了直接加日志, 我复现一遍你亲自部署")
    // 22:58 场次三个事实互相矛盾, 无法用当前源码解释:
    //   ① 挖床分支已派发 (tasks log t=3 -> <Mine And Collect [[beds]]> 持续 13s)
    //   ② 本类 GateHit/MineGiveUp 在 diag 全文 0 条 — 父任务每 tick 重跑
    //      (Task.tickInternal:67), 闸门若通过 5s 节流下 13s 应有 2-3 条 GateHit
    //   ③ 内层 [Diag-blind] cached=22 valid=0 — canBreak 对全部 22 张床失败
    // 本轮日志覆盖 onResourceTick 每条 return 路径:
    //   START 行(构建标记, 证明新 jar 在跑) + 每秒决策行(闸门输入/最终路径)
    //   + 逐床淘汰原因(bedRejectReason, canBreak 细分到四子条件) + 闸门翻转日志。
    private static long s_lastDecisionDiagMs = 0;
    private static BlockPos s_lastGateBed = null;

    public CollectBedTask(Item[] beds, ItemTarget wool, int count) {
        // Top 3 are wool, must be the same.
        super(new ItemTarget(beds, count), colorfulItems -> colorfulItems.wool, colorfulItems -> colorfulItems.bed, createBedRecipe(wool), new boolean[]{true, true, true, false, false, false, false, false, false});
        _visualBedTarget = new ItemTarget(beds, count);
    }

    public CollectBedTask(Item bed, String woolCatalogueName, int count) {
        this(new Item[]{bed}, new ItemTarget(woolCatalogueName, 1), count);
    }

    public CollectBedTask(int count) {
        this(ItemHelper.BED, TaskCatalogue.getItemTarget("wool", 1), count);
    }

    private static CraftingRecipe createBedRecipe(ItemTarget wool) {
        ItemTarget w = wool;
        ItemTarget p = TaskCatalogue.getItemTarget("planks", 1);
        return CraftingRecipe.newShapedRecipe(new ItemTarget[]{w, w, w, p, p, p, null, null, null}, 1);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        Debug.logMessage("[CollectBed] START build=" + Debug.BUILD_ID);
        Debug.logToFile("altoclef_diag.log", "[CollectBed]\tSTART\tbuild=" + Debug.BUILD_ID);
        mod.getBlockTracker().trackBlock(BEDS);
        super.onResourceStart(mod);
    }

    // 挖床分支决策滞回 (2026-08-21 挖床↔收集木头横跳修复):
    // anyFound(BEDS) 会随 BlockTracker 缓存闪烁 — 导航中途一次进度
    // 检查失败 → 床被黑名单 2 秒 → 下次重扫(约 7 秒间隔)时从缓存移除
    // → anyFound 翻 false → 本任务切到合成分支(收集羊毛/木板=砍树) →
    // bot 放弃走到一半的导航; 黑名单过期后重扫又把床加回来 → anyFound
    // 翻 true → 又切回挖床... 12 秒窗口完全覆盖"黑名单 2s + 重扫 7s"的
    // 闪烁周期: 只要床在 12 秒内还被看见过, 就保持挖床分支不翻转。
    private final TimerGame _bedsSeenRecently = new TimerGame(12);

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // (2026-08-31 修正"可感知床"判定, 用户"96格挖床逻辑对, 刚才是不可感知床触发了")
        // 96 格内挖现成床的分支是正确的, 但之前的判定用 getKnownLocations(缓存里
        // 有位置就算"有床"), 没有做"可感知(LOS/视野)"过滤 —— 于是"不可感知"的床
        // (被岩石挡住/在身后)也会触发 → MineAndCollectTask(bed) 因那些床 getClosestTo
        // 的 LOS 感知拒绝而挖不到 → 无目标 → 偏置探索乱逛/卡死不动。
        // 现在: 仅当 96 格内存在【真正可感知 + 可挖】的床才切挖床; 否则一律走
        // super 的杀羊做床正路。
        long nowMs = System.currentTimeMillis();
        boolean logDecision = nowMs - s_lastDecisionDiagMs > 1000;
        if (mod.getBlockTracker().anyFound(BEDS)) {
            _bedsSeenRecently.reset();
        }
        boolean hysteresisActive = !_bedsSeenRecently.elapsed();
        boolean coolingDown = nowMs < s_mineBedGiveUpUntilMs;

        if (hysteresisActive && !coolingDown) {
            List<BlockPos> knownBeds = mod.getBlockTracker().getKnownLocations(ItemHelper.itemsToBlocks(ItemHelper.BED));
            BlockPos playerPos = mod.getPlayer() != null ? mod.getPlayer().getBlockPos() : null;
            BlockPos gateBed = null;
            String rejects = "player-null";
            if (playerPos != null) {
                double bestSq = Double.POSITIVE_INFINITY;
                StringBuilder rj = new StringBuilder();
                int shown = 0;
                for (BlockPos bed : knownBeds) {
                    String reason = bedRejectReason(mod, bed, playerPos);
                    if (reason != null) {
                        if (shown < 3) {
                            if (shown > 0) rj.append("; ");
                            rj.append(bed.toShortString()).append("@")
                                    .append((int) Math.sqrt(bed.getSquaredDistance(playerPos))).append("m:")
                                    .append(reason);
                            shown++;
                        }
                        continue;
                    }
                    double sq = bed.getSquaredDistance(playerPos);
                    if (sq < bestSq) {
                        bestSq = sq;
                        gateBed = bed;
                    }
                }
                rejects = rj.toString();
            }
            if (logDecision) {
                s_lastDecisionDiagMs = nowMs;
                Debug.logToFile("altoclef_diag.log",
                        "[CollectBed]\ttick build=" + Debug.BUILD_ID
                                + " hysteresis=true cooldownMs=" + (coolingDown ? (s_mineBedGiveUpUntilMs - nowMs) : 0)
                                + " knownBeds=" + knownBeds.size()
                                + " gateBed=" + (gateBed == null ? "NONE" : gateBed.toShortString())
                                + " rejects=[" + rejects + "]");
            }
            if (gateBed == null) {
                if (!bedPosEquals(gateBed, s_lastGateBed)) {
                    s_lastGateBed = null;
                    Debug.logToFile("altoclef_diag.log", "[CollectBed]\tGATE-CHANGE\t-> NONE (wool path)");
                }
                // 96 格内没有可感知/可挖的床 → 回到作者正路: 杀羊取毛做床。
                markProtectedBedsUnreachable(mod);
                return super.onResourceTick(mod);
            }
            if (!bedPosEquals(gateBed, s_lastGateBed)) {
                s_lastGateBed = gateBed;
                Debug.logToFile("altoclef_diag.log", "[CollectBed]\tGATE-CHANGE\t-> "
                        + gateBed.toShortString() + " dist="
                        + (int) Math.sqrt(gateBed.getSquaredDistance(playerPos)));
            }
            // (2026-09-21 合成优先) 原料够就地合成且床不在顺手距离内 → 走合成,
            // 不为挖现成床垫塔/远征 (160148 实锤: 合成刚起头被挖床分支抢走,
            // 垫塔 51 格爬去拆村庄床)。
            int gapHere = bedGapHere(mod);
            if (gapHere > 0 && canCraftBedsFromInventory(mod, gapHere)
                    && !gateBed.isWithinDistance(playerPos, BED_GRAB_NEAR_RANGE)) {
                if (nowMs - s_lastCraftOverrideLogMs > 5000) {
                    s_lastCraftOverrideLogMs = nowMs;
                    Debug.logToFile("altoclef_diag.log",
                            "[CollectBed]\t[CraftPriority]\tgap=" + gapHere
                                    + " gateBed=" + gateBed.toShortString() + " dist="
                                    + (int) Math.sqrt(gateBed.getSquaredDistance(playerPos))
                                    + " -> craft branch (inventory materials suffice)");
                }
                markProtectedBedsUnreachable(mod);
                return super.onResourceTick(mod);
            }
            // (B修诊断) 闸门命中: 打出选中的床与距离, 便于对账挖床子任务是否真锁到了它。
            if (nowMs - s_lastGateDiagMs > 5000) {
                s_lastGateDiagMs = nowMs;
                Debug.logToFile("altoclef_diag.log",
                        "[CollectBed]\t[GateHit]\tbed=" + gateBed.toShortString()
                                + " dist=" + (int) Math.sqrt(gateBed.getSquaredDistance(playerPos))
                                + " -> mine branch");
            }
            // 真实可感知的床就在附近 → 拆它拿床。
            markProtectedBedsUnreachable(mod);
            // (2026-09-08 挖床站桩兜底) 缓存实例保活(9dbeb779 模式), 并显式查
            // gaveUpWandering: 内层 2 秒完全无动作(目标锁不到+surface 探索=null)
            // → 放弃本次挖床, 冷却 30s, 回杀羊取毛做床 — 不再站到天亮。
            if (s_bedMineTask == null) {
                s_bedMineTask = new MineAndCollectTask(new ItemTarget(ItemHelper.BED, 1), BEDS, MiningRequirement.HAND);
                Debug.logToFile("altoclef_diag.log", "[CollectBed]\tMINE-TASK\tnew MineAndCollect instance created");
            }
            if (s_bedMineTask.gaveUpWandering()) {
                Debug.logMessage("[CollectBed] mine-bed subtask gave up (2s no action), cooldown "
                        + (MINE_BED_GIVEUP_COOLDOWN_MS / 1000) + "s, falling back to sheep/wool path.");
                Debug.logToFile("altoclef_diag.log",
                        "[CollectBed]\t[MineGiveUp]\tmine-bed subtask 2s idle -> cooldown "
                                + (MINE_BED_GIVEUP_COOLDOWN_MS / 1000) + "s -> wool path fallback");
                s_mineBedGiveUpUntilMs = System.currentTimeMillis() + MINE_BED_GIVEUP_COOLDOWN_MS;
                s_bedMineTask = null;
                s_lastGateBed = null;
                return super.onResourceTick(mod);
            }
            return s_bedMineTask;
        }
        if (logDecision) {
            s_lastDecisionDiagMs = nowMs;
            Debug.logToFile("altoclef_diag.log",
                    "[CollectBed]\ttick build=" + Debug.BUILD_ID
                            + " hysteresis=" + hysteresisActive + " cooldownMs="
                            + (coolingDown ? (s_mineBedGiveUpUntilMs - nowMs) : 0)
                            + " -> wool path (gate skipped)");
        }
        markProtectedBedsUnreachable(mod);
        return super.onResourceTick(mod);
    }

    private static boolean bedPosEquals(BlockPos a, BlockPos b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        // 缓存挖床实例随任务停发作废(冷却 s_mineBedGiveUpUntilMs 保留, 跨重建生效);
        // 正常拿到床/被打断都清, 下一晚全新开始。
        s_bedMineTask = null;
        mod.getBlockTracker().stopTracking(BEDS);
        super.onResourceStop(mod, interruptTask);
    }

    /**
     * (2026-08-31) 把"自己放的重生点床"标为不可达, 防止做床/收床过程中把
     * 刚放的出生点床撸掉。
     * (2026-09-21 移除"玩家身旁 8 格内床"保护, 用户"还没拆掉一个就切下一张床")
     * 20260921-110154 实锤: 村庄收床时 bot 每走近一张床(&lt;8 格), 该床即被
     * 本保护标记 unreachable → 闸门以 canBreak:canReach:false(tracker-unreachable)
     * 淘汰 → 切下一张 → 走近再被标 → 连环换目标 (tasks.log 11:07:21 起 6 次
     * Destroy block 目标切换全数未破块, knownBeds 14→4)。挖掘距离 4.5 格 &lt;
     * 8 格保护半径, 该保护与"拆床进背包"在本任务里天然互斥; 出生点床的语义
     * 由 selfBed 记录全覆盖 (PlaceBedAndSetSpawnTask 只认自放床), 8 格规则无
     * 独立价值。
     */
    private void markProtectedBedsUnreachable(AltoClef mod) {
        BlockPos selfBed = mod.getLastSelfPlacedBed();
        if (selfBed != null) {
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dz = -1; dz <= 1; ++dz) {
                    BlockPos c = selfBed.add(dx, 0, dz);
                    if (mod.getWorld().getBlockState(c).getBlock() instanceof BedBlock) {
                        mod.getBlockTracker().requestBlockUnreachable(c);
                    }
                }
            }
        }
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBedTask task) {
            return task._visualBedTarget.equals(_visualBedTarget);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting bed: " + _visualBedTarget;
    }
}
