package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.PortalExitHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Some generic tasks require us to go to the nether/overworld/end.
 * <p>
 * The user should be able to specify how this should be done in settings
 * (ex, craft a new portal from scratch or check particular portal areas first or highway or whatever)
 */
public class DefaultGoToDimensionTask extends Task {

    private final Dimension _target;
    // Cached to keep build properties alive if this task pauses/resumes.
    private final Task _cachedNetherBucketConstructionTask = new ConstructNetherPortalBucketTask();

    // (2026-09-20 搭了两块就跑去翻旧门) 下界回家"直接造门"的粘滞闩。
    // canBuildPortalHome 是**瞬时**判据 (背包黑曜石 ≥ 10), 而搭门本身会消耗黑曜石;
    // 内层 ConstructNetherPortalObsidianTask 用的是另一个口径 (还需要 10 - 已就位),
    // 两者在搭门过程中必然分叉。实测 20260920-205755:
    //   21:02:09.489 背包 11 块过闸 "building portal home directly"
    //   21:02:09.539 放第 1 块 (剩 10)
    //   21:02:11.585 放第 2 块 (剩 9)
    //   21:02:13.291 外层重判 canBuildPortalHome=false → 撤回构造任务转
    //                "Going to last nether portal pos" (跑去 -18,72,32 的旧门)
    // 门只搭了两块就永远搭不完。修 = 一旦过闸决定直造就锁住该决策, 仅超时兜底
    // 释放 (门已建好时由维度切换自然结束本任务)。
    private boolean _buildingPortalHome = false;
    private long _portalHomeBuildStartMs = 0L;
    private static final long PORTAL_HOME_BUILD_TIMEOUT_MS = 180_000;

    // (2026-09-22 进下界门前就地设重生点, 用户"进入下界传送门前也要设置一下,
    // 参考末地的") 对齐末地门 BeatMinecraft2Task#setSpawnNearPortalTask:
    // 走到已点亮的下界门 XZ 8 格内 → 先执行 PlaceBedAndSetSpawnTask (身边有床
    // 直接右键设点; 有床物品就地放床; 没床就 CollectBedTask 收一张), 完成
    // (设点成功或任务自身超时放弃) 后再进传送门。对所有主世界→下界的调用方
    // 全局生效 (用户拍板"所有进下界都生效, 没床就收集一张")。
    // 任务系统按 isEqual(_target) 保活首个实例 (Task.tickInternal), 闩与床
    // 任务实例跨 tick 不丢, 与 _buildingPortalHome 同理。
    private final PlaceBedAndSetSpawnTask _bedSpawnBeforeNetherTask = new PlaceBedAndSetSpawnTask();
    private boolean _netherBedSpawnStarted = false;
    private boolean _netherBedSpawnDone = false;
    private static final double NETHER_PORTAL_BED_SPAWN_RANGE = 8.0;
    // 门洞里往外撤的粘滞落脚点 (选定后不每 tick 重选, 避免 GetToBlock 目标抖动)
    private BlockPos _netherExitTarget = null;

    // (2026-10-11 熄灭门到场重燃) "紫块不在"≠"门废了": 熄灭门通常黑曜石框完好。
    // 旧链在 getLastUsedNetherPortal / WorldMemory.getPortalLocations 里把紫块缺失
    // 的门址直接清除 (内存+落盘), 导致跨维度回家永远不走来时的门, 直接掉进重造链
    // (用户现场: bot 在下界找人, 回程没走去时的门)。新分支在这两个清除点之前拦截:
    // 导航到已知门址 → 有打火石点燃 (缺铁锭+燧石现场合, 下界缺料直接放弃防
    // CollectIronIngotTask→DefaultGoToDimensionTask 递归), 3 分钟超时放弃, 失败
    // 门址拉黑后回落旧链。出口全部在调用侧 portalRelightTask (本仓"子任务
    // isFinished 不被框架消费"铁律)。
    private RelightNetherPortalTask _relightTask = null;
    // 拉黑集合跨实例存活 (FollowPlayerTask 等调用方可能反复 new 本任务), 上限粗控。
    private static final Set<BlockPos> _relightFailedSites = new HashSet<>();
    private static final int RELIGHT_FAILED_SITES_CAP = 64;

    public DefaultGoToDimensionTask(Dimension target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() == _target) return null;

        switch (_target) {
            case OVERWORLD:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        return goToOverworldFromNetherTask(mod);
                    case END:
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case NETHER:
                switch (WorldHelper.getCurrentDimension()) {
                    case OVERWORLD:
                        return goToNetherFromOverworldTask(mod);
                    case END:
                        // First go to the overworld
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case END:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        // First go to the overworld
                        return goToOverworldFromNetherTask(mod);
                    case OVERWORLD:
                        return goToEndTask(mod);
                }
                break;
        }

        setDebugState(WorldHelper.getCurrentDimension() + " -> " + _target + " is NOT IMPLEMENTED YET!");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DefaultGoToDimensionTask task) {
            return task._target == _target;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Going to dimension: " + _target + " (default version)";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        boolean finished = WorldHelper.getCurrentDimension() == _target;
        // (2026-09-28 根修) 设点闩锁在"进下界"这一动作完成时使命即结束, 立即重置。
        // 旧实现 _netherBedSpawnDone 只在 netherPortalBedSpawnTask 里置 true 后
        // 永不重置, 而 _goToNetherTask 是粘性实例(BeatMinecraft2Task:461)跨多次进
        // 下界复用 ⇒ 第一次进下界(18:53)设点后, 之后从下界回来再进下界(19:17 起)
        // 一律跳过设点(现场 latest.log 反复 Going to nether portal 却无 Setting
        // spawn, 重生点早已失效/消耗)。这里在"已抵达目标维度"的瞬间重置, 让下一次
        // 主世界→下界重新走 netherPortalBedSpawnTask 设重生点。
        if (finished && _netherBedSpawnDone) {
            _netherBedSpawnDone = false;
            _netherBedSpawnStarted = false;
            Debug.logMessage("(NetherBedSpawn) entered nether, resetting spawn latch for next visit.");
            Debug.logToFile("altoclef_diag.log", "[NetherBedSpawn]\treset latch on dimension entry");
        }
        return finished;
    }

    private Task goToOverworldFromNetherTask(AltoClef mod) {
        // (2026-09-13 用户"黑曜石够且有打火石/材料就直接造门, 别走原来的门")
        // 有料直接原地造门回家, 不再长途跋涉去翻 last-used / 记忆旧门 (旧门
        // 记忆可能过期/死门, 徒步也可能几百上千格)。唯一例外: 眼前 64 格内就
        // 有已点亮的门, 走进去比烧 10 块黑曜石划算, 人类也会直接进。
        // 材料闸收死: 黑曜石 ≥10, 且有打火石或"铁锭+燧石"能现场合成 ——
        // 绝不让 ConstructNetherPortalObsidianTask 在下界触发黑曜石收集
        // (那条路绕猪灵交易, 是另一个阶段的事)。
        // 粘滞闩 (见字段 _buildingPortalHome 注释): 过闸后不再因背包数量下降撤回。
        // 超时兜底: 锁了这么久还没进主世界, 放弃直造回到旧的找门链。
        if (_buildingPortalHome && System.currentTimeMillis() - _portalHomeBuildStartMs > PORTAL_HOME_BUILD_TIMEOUT_MS) {
            Debug.logMessage("(PortalHome) Direct build latched for " + (PORTAL_HOME_BUILD_TIMEOUT_MS / 1000)
                    + "s without finishing; falling back to portal search chain.");
            Debug.logToFile("altoclef_diag.log", "[PortalHome]\tlatch TIMEOUT " + PORTAL_HOME_BUILD_TIMEOUT_MS
                    + "ms, release to portal search chain");
            _buildingPortalHome = false;
        }

        if (_buildingPortalHome || canBuildPortalHome(mod)) {
            Optional<BlockPos> litNearby = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), Blocks.NETHER_PORTAL);
            if (litNearby.isEmpty() || !litNearby.get().isWithinDistance(mod.getPlayer().getPos(), 64)) {
                if (!_buildingPortalHome) {
                    _buildingPortalHome = true;
                    _portalHomeBuildStartMs = System.currentTimeMillis();
                    int obsidian = mod.getItemStorage().getItemCount(Items.OBSIDIAN);
                    Debug.logMessage("(PortalHome) Materials available (obsidian=" + obsidian
                            + ") — latching direct build until portal is finished.");
                    Debug.logToFile("altoclef_diag.log", "[PortalHome]\tlatch ON, obsidian=" + obsidian
                            + " — sticky until built (timeout " + (PORTAL_HOME_BUILD_TIMEOUT_MS / 1000) + "s)");
                }
                setDebugState("Has obsidian + flint&steel (or materials), building portal home directly");
                return new ConstructNetherPortalObsidianTask();
            }
            setDebugState("Lit nether portal within 64 blocks, entering instead of building");
            _buildingPortalHome = false;
        }

        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }

        // (2026-10-11) 熄灭门到场重燃: 必须在 getLastUsedNetherPortal 之前 ——
        // 旧方法会对"紫块缺失"的门址直接清记忆, 这里不拦就永远轮不到重燃。
        Task relightNether = portalRelightTask(mod, Dimension.NETHER);
        if (relightNether != null) return relightNether;

        Optional<BlockPos> closest = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
        if (closest.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(closest.get());
        }

        Optional<BlockPos> remembered = mod.getWorldMemory().getNearestPortal(mod.getPlayer().getPos());
        if (remembered.isPresent()) {
            setDebugState("Going to remembered nether portal pos");
            return new GetToBlockTask(remembered.get());
        }

        setDebugState("Constructing nether portal with obsidian");
        return new ConstructNetherPortalObsidianTask();
    }

    private Task goToOverworldFromEndTask(AltoClef mod) {
        setDebugState("TODO: Go to center portal (at 0,0). If it doesn't exist, kill ender dragon lol");
        return null;
    }

    private Task goToNetherFromOverworldTask(AltoClef mod) {
        // (2026-09-23 床闸门提到所有进门路由之前) 旧写法把闸门放在
        // netherPortalIsClose 分支里, 而刚被打火石点亮的门受 BlockTracker 扫描
        // 节流尚未入缓存 (20260923-085300 实锤: 点亮后 0.1s 外层重判
        // anyFound(NETHER_PORTAL)=false) → 闸门被整体绕过, 落到 lastUsed 分支
        // GetToBlock(门洞内部坐标) 原样走进去, vanilla 4s 后传走, 床一次没放。
        Task bedSpawn = netherPortalBedSpawnTask(mod);
        if (bedSpawn != null) {
            return bedSpawn;
        }

        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }

        // (2026-10-11) 熄灭门到场重燃, 同 goToOverworldFromNetherTask 口径 (对称方向)。
        Task relightOverworld = portalRelightTask(mod, Dimension.OVERWORLD);
        if (relightOverworld != null) return relightOverworld;

        Optional<BlockPos> lastUsed = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.OVERWORLD);
        if (lastUsed.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(lastUsed.get());
        }

        Optional<BlockPos> remembered = mod.getWorldMemory().getNearestPortal(mod.getPlayer().getPos());
        if (remembered.isPresent()) {
            setDebugState("Going to remembered nether portal pos");
            return new GetToBlockTask(remembered.get());
        }

        return switch (mod.getModSettings().getOverworldToNetherBehaviour()) {
            case BUILD_PORTAL_VANILLA -> _cachedNetherBucketConstructionTask;
            case GO_TO_HOME_BASE -> new GetToBlockTask(mod.getModSettings().getHomeBasePosition());
        };
    }

    private Task goToEndTask(AltoClef mod) {
        // Keep in mind that getting to the end requires going to the nether first.
        setDebugState("TODO: Get to End, Same as BeatMinecraft");
        return null;
    }

    private boolean netherPortalIsClose(AltoClef mod) {
        if (mod.getBlockTracker().anyFound(Blocks.NETHER_PORTAL)) {
            Optional<BlockPos> closest = mod.getBlockTracker().getNearestTracking(mod.getPlayer().getPos(), Blocks.NETHER_PORTAL);
            return closest.isPresent() && closest.get().isWithinDistance(mod.getPlayer().getPos(), 2000);
        }
        return false;
    }

    /**
     * (2026-10-11 熄灭门到场重燃) 门址候选 = lastUsed（含熄灭，见
     * MiscBlockTracker#getLastUsedNetherPortalSiteIncludingExtinguished）∪ 本会话确认
     * 熄灭的门（WorldMemory lost，按当前维度——调用点仅在对应维度的分支里触发，
     * dim 恒等于当前维度）。取离玩家最近的未拉黑候选；无候选返回 null 放行旧链。
     * 出口语义（调用侧消费）：点亮成功 → 放行走旧链自然进门；放弃/超时 → 门址
     * 拉黑走旧链重造。任务实例粘滞复用，门址变化才重建。
     */
    private Task portalRelightTask(AltoClef mod, Dimension dim) {
        BlockPos site = nearestRelightCandidate(mod, dim);
        if (site == null) return null;
        if (_relightTask == null || !_relightTask.isForSite(site)) {
            _relightTask = new RelightNetherPortalTask(site);
            Debug.logMessage("(PortalRelight) heading to portal site " + site.toShortString()
                    + " — purple blocks missing, will relight if frame intact");
            Debug.logToFile("altoclef_diag.log", "[PortalRelight]\tENGAGE site=" + site.toShortString()
                    + " dim=" + dim);
        }
        if (_relightTask.isFinished(mod)) {
            if (_relightTask.gaveUp()) {
                if (_relightFailedSites.size() >= RELIGHT_FAILED_SITES_CAP) _relightFailedSites.clear();
                _relightFailedSites.add(site);
                Debug.logToFile("altoclef_diag.log", "[PortalRelight]\tBLACKLIST site=" + site.toShortString());
            }
            _relightTask = null;
            return null; // 点亮 → 旧链 lastUsed/EnterNetherPortalTask 接管；放弃 → 旧链重造
        }
        return _relightTask;
    }

    private BlockPos nearestRelightCandidate(AltoClef mod, Dimension dim) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        Optional<BlockPos> used = mod.getMiscBlockTracker()
                .getLastUsedNetherPortalSiteIncludingExtinguished(dim);
        if (used.isPresent() && !_relightFailedSites.contains(used.get())) {
            best = used.get().toImmutable();
            bestSq = best.getSquaredDistance(mod.getPlayer().getPos());
        }
        // WorldMemory lost 按当前维度返回，与本方法 dim 口径一致（调用点已保证）
        for (BlockPos p : mod.getWorldMemory().getLostPortalLocations()) {
            if (_relightFailedSites.contains(p)) continue;
            double distSq = p.getSquaredDistance(mod.getPlayer().getPos());
            if (distSq < bestSq) {
                bestSq = distSq;
                best = p.toImmutable();
            }
        }
        return best;
    }

    /**
     * (2026-09-22) 进下界门前的就地重生点闸门 (对齐末地门
     * BeatMinecraft2Task#setSpawnNearPortalTask + END_PORTAL_BED_SPAWN_RANGE)。
     * <ul>
     *   <li>未开始: 必须已走到最近的已点亮下界门 XZ {@value #NETHER_PORTAL_BED_SPAWN_RANGE}
     *       格内才启动 — 远的时候让 EnterNetherPortalTask/导航先走, 绝不会在离门
     *       很远的地方提前放床。门检测先走 BlockTracker, 刚点亮/扫描滞后时世界
     *       直扫兜底 (20260923-085300: 旧实现只认缓存, 打火后被直接绕过);</li>
     *   <li>已开始: 保活床任务到 isFinished (设点成功 / 睡眠放弃 / 收床失败),
     *       期间 CollectBedTask 可能为收床走到 96 格外, 不中途撤回 (同末地
     *       shouldForce 语义)。若人已站在门洞里 (桶浇造门结束时人就在门里),
     *       先导航到门外落脚点 — vanilla 要在门内连续站满 4s 才传送, 离开即
     *       清零, 否则床还没放就被传走;</li>
     *   <li>完成: 闩 {@code _netherBedSpawnDone} 永久放行进门; 床任务自身有
     *       超时/放弃出口, 不会卡死进门流程。</li>
     * </ul>
     * @return 要执行的床任务/撤出任务; null = 放行进入传送门
     */
    private Task netherPortalBedSpawnTask(AltoClef mod) {
        if (_netherBedSpawnDone) {
            return null;
        }
        if (_netherBedSpawnStarted) {
            Task exit = stepOutOfNetherPortalTask(mod);
            if (exit != null) {
                return exit;
            }
            if (_bedSpawnBeforeNetherTask.isFinished(mod)) {
                _netherBedSpawnStarted = false;
                _netherBedSpawnDone = true;
                Debug.logMessage("(NetherBedSpawn) Spawn step finished (spawnSet="
                        + _bedSpawnBeforeNetherTask.isSpawnSet() + "), entering nether portal.");
                Debug.logToFile("altoclef_diag.log", "[NetherBedSpawn]\tdone spawnSet="
                        + _bedSpawnBeforeNetherTask.isSpawnSet() + " -> enter portal");
                return null;
            }
            setDebugState("Setting spawn near nether portal");
            return _bedSpawnBeforeNetherTask;
        }
        BlockPos portal = nearestLitNetherPortal(mod);
        if (portal != null
                && WorldHelper.inRangeXZ(mod.getPlayer(), WorldHelper.toVec3d(portal),
                        NETHER_PORTAL_BED_SPAWN_RANGE)) {
            _netherBedSpawnStarted = true;
            Debug.logMessage("(NetherBedSpawn) At nether portal " + portal.toShortString()
                    + ", setting spawn point in place before entering.");
            Debug.logToFile("altoclef_diag.log", "[NetherBedSpawn]\tstart at portal "
                    + portal.toShortString() + " player="
                    + mod.getPlayer().getBlockPos().toShortString());
            // 造门任务刚结束时人可能正站在门洞里: 先撤出来再放床。
            Task exit = stepOutOfNetherPortalTask(mod);
            if (exit != null) {
                return exit;
            }
            setDebugState("Setting spawn near nether portal");
            return _bedSpawnBeforeNetherTask;
        }
        return null;
    }

    /**
     * (2026-09-23) 最近的已点亮下界门。BlockTracker 有直接返回; 缓存未收录
     * (刚打火点亮 / 扫描节流滞后) 时以玩家为中心直扫真实世界兜底, 并顺手把门块
     * 注册回 tracker (注册后 netherPortalIsClose / EnterNetherPortalTask 也能看见)。
     * 扫描盒 17x11x17: XZ r=8 与启动门限同口径 — 造门结束人就在门旁/门里,
     * 远处的门由 lastUsed/remembered 导航负责, 不需要这里看见。
     */
    private BlockPos nearestLitNetherPortal(AltoClef mod) {
        Optional<BlockPos> tracked = mod.getBlockTracker().getNearestTracking(
                mod.getPlayer().getPos(), Blocks.NETHER_PORTAL);
        if (tracked.isPresent()) {
            return tracked.get();
        }
        BlockPos origin = mod.getPlayer().getBlockPos();
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : WorldHelper.scanRegion(mod, origin.add(-8, -5, -8), origin.add(8, 5, 8))) {
            if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
            if (mod.getWorld().getBlockState(pos).getBlock() != Blocks.NETHER_PORTAL) continue;
            mod.getBlockTracker().addBlock(Blocks.NETHER_PORTAL, pos);
            double distSq = pos.getSquaredDistance(mod.getPlayer().getPos());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = pos;
            }
        }
        return nearest;
    }

    /**
     * (2026-09-23) 玩家正站在下界门洞里时, 导航到门外最近的落脚点 (头脚两格
     * 空气 + 脚下实心 + 均非门块), 抢在 vanilla 4s 传送计时走完前撤出; 离开
     * 门洞即返回 null。落脚点粘滞复用 {@link #_netherExitTarget}, 人离开后清空。
     */
    private Task stepOutOfNetherPortalTask(AltoClef mod) {
        // (2026-10-08) inside 判定收口到 PortalExitHelper (唯一判定源, 碰撞箱
        // 口径 — vanilla NetherPortalBlock#onEntityCollision 按碰撞箱相交触发
        // 4s 传送计时)。旧脚方块写法漏判贴门框站立, 20261008-101824 实锤:
        // 点火后玩家 xyz=(-302.22,62,691.47) 全程未动, 脚方块 -303 恒非门块,
        // 点亮恰 4s 被 vanilla 强传进下界 — 预存在 bug, 非本次新引入。
        boolean inside = PortalExitHelper.isPlayerIntersectingNetherPortal(mod);
        if (!inside) {
            _netherExitTarget = null;
            return null;
        }
        BlockPos safe = _netherExitTarget;
        if (safe != null && !isSafeStandPos(mod, safe)) {
            safe = null;
        }
        if (safe == null) {
            BlockPos origin = mod.getPlayer().getBlockPos();
            double safeDistSq = Double.MAX_VALUE;
            for (BlockPos pos : WorldHelper.scanRegion(mod, origin.add(-3, -1, -3), origin.add(3, 1, 3))) {
                if (!isSafeStandPos(mod, pos)) continue;
                double distSq = pos.getSquaredDistance(mod.getPlayer().getPos());
                if (distSq < safeDistSq) {
                    safeDistSq = distSq;
                    safe = pos;
                }
            }
        }
        if (safe == null) {
            // 门洞外 3 格内一无可站 (极罕见, 三面封死): 不硬挡, 交给床任务自己
            // 找放床区域 — 桶浇门本来就没有四角/侧墙, 两侧通常开放。
            Debug.logWarning("(NetherBedSpawn) Inside portal but no safe stand within 3 blocks, bed task takes over");
            return null;
        }
        if (!safe.equals(_netherExitTarget)) {
            _netherExitTarget = safe;
            Debug.logMessage("(NetherBedSpawn) Standing inside lit portal, stepping out to "
                    + safe.toShortString() + " before setting spawn.");
            Debug.logToFile("altoclef_diag.log", "[NetherBedSpawn]\tinside portal -> exit to "
                    + safe.toShortString() + " player=" + mod.getPlayer().getBlockPos().toShortString());
        }
        setDebugState("Stepping out of nether portal before setting spawn");
        return new GetToBlockTask(safe, false);
    }

    private boolean isSafeStandPos(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) return false;
        if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.NETHER_PORTAL) return false;
        if (!WorldHelper.isAir(mod, pos) || !WorldHelper.isAir(mod, pos.up())) return false;
        BlockPos below = pos.down();
        if (mod.getWorld().getBlockState(below).getBlock() == Blocks.NETHER_PORTAL) return false;
        return WorldHelper.isSolid(mod, below);
    }

    /**
     * (2026-09-13) 下界回家"直接造门"材料闸: 黑曜石 ≥10 (门框最低 10 块无四角),
     * 且有打火石, 或有铁锭+燧石可现场合成打火石。缺任一都走旧的找门链。
     */
    private boolean canBuildPortalHome(AltoClef mod) {
        if (mod.getItemStorage().getItemCount(Items.OBSIDIAN) < 10) return false;
        if (mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)) return true;
        return mod.getItemStorage().hasItem(Items.IRON_INGOT) && mod.getItemStorage().hasItem(Items.FLINT);
    }

    public enum OVERWORLD_TO_NETHER_BEHAVIOUR {
        BUILD_PORTAL_VANILLA,
        GO_TO_HOME_BASE
    }
}
