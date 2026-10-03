package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.LavaLakeHelper;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Human-like "experience memory".
 *
 * A human that sees a village corner knows chests are probably nearby, even
 * without seeing the chests. This class models that: it remembers the type and
 * center of structures the player has actually SEEN (visibility filtered), and
 * can generate candidate resource areas near those structures.
 *
 * IMPORTANT: candidates are INFERENCES, not perceptions. Anything generated here
 * must be labeled as a guess during recording, never as an observation.
 */
public final class KnowledgeMemory {

    // How often we run a landmark scan (ticks).
    private static final int SCAN_INTERVAL_TICKS = 20;
    // =========================================================================
    // (2026-09-16 视力范围感知, 用户"24 格太小, 原本视力范围多远就用多远")
    // 旧实现自己扫 24 格箱子当候选源 → 隔 30+ 格亲眼望见的堡垒根本记不住。
    // 改为直接复用 BlockTracker 感知缓存当候选 (近区 5Hz 半径 16 + 远区 4s
    // 半径 128, 即 bot 现成的"视力范围"), 候选按距离排序后每轮只对最近的
    // MAX_LOS_CHECKS_PER_ROUND 个做 isBlockPerceivable 真视线判定 (防透视,
    // 开销有界, 与 FortressExploreTask 找下界砖同款护栏)。扫描迭代按位置
    // 遍历 + 集合判定, 多跟几种地标方块几乎不增加扫描成本; 缓存每类型
    // 封顶 100 (保最近的)。
    // =========================================================================
    private static final int MAX_LOS_CHECKS_PER_ROUND = 12;
    // Merge centers that are closer than this (blocks).
    private static final double MERGE_DISTANCE = 8;
    // Max remembered centers per structure type.
    private static final int MAX_CENTERS_PER_TYPE = 20;
    // Don't generate candidates from centers farther than this from the player.
    private static final double CANDIDATE_MAX_DISTANCE = 192;

    public enum StructureType {
        VILLAGE(
                // (2026-09-30 去掉 OAK_LOG) OAK_LOG 是森林通用方块: 橡树林/山地里
                // 任意一根过了真视线的橡木都被记成村庄中心 (20260930 实锤 4 连报,
                // knowledge.log 积累 459 条假 VILLAGE, 全在无村庄的橡树地形)。
                // 曾考虑"橡木须与土径/耕地共现"的守卫, 用户拍板直接删 —— 只留
                // 村庄专属的 DIRT_PATH/FARMLAND。
                // (2026-10-01 mergeDistance 8→64) 村庄是成片大结构 (实锤: 一个村
                // xz 跨度 85×60, DIRT_PATH/FARMLAND 散布全村), 8 格合并 = 沿路每
                // 走 8 格记一个中心, 直到 maxCenters=20 灌满 —— knowledge.log 20
                // 条 VILLAGE 全是同一个村。与 WARPED_FOREST 同款处理 (大片结构用
                // 大合并距离, 该枚举注释早写过这条教训)。
                new Block[]{Blocks.DIRT_PATH, Blocks.FARMLAND},
                8, 32, 4, true, 64),
        RUINED_PORTAL(
                new Block[]{Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN},
                // (2026-10-01 mergeDistance 8→16) 废墟门框黑曜石对角散布可超 8 格
                // (实锤: 一个门 6 条 RUINED_PORTAL, -251~-254,71-75,172), 16 格
                // = 一个门一个中心。
                4, 16, 3, true, 16),
        NETHER_FORTRESS(
                new Block[]{Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE},
                16, 48, 5, true),
        // (2026-09-18 烈焰刷怪笼记忆, 用户"刷怪笼是像堡垒那样的关键位置")
        // 记你【亲眼看见过】的刷怪笼坐标, 供下次刷烈焰棒时直接奔过去, 不再靠
        // 走廊巡逻重新碰运气 (CollectBlazeRodsTask 负责消费)。
        // ⚠ 消费前必须到现场用 WorldHelper.getSpawnerEntity 验明 BlazeEntity
        //   —— 主世界废弃矿井的蜘蛛笼/僵尸笼方块完全相同, 按坐标远程认亲会把
        //   bot 骗过去。
        // ⚠ inference=false: 不参与"随机候选区偏置"与"结构邻近推理可见"两种
        //   结构级消费, 理由见 INFERENCE_TYPES 注释。
        BLAZE_SPAWNER(
                new Block[]{Blocks.SPAWNER},
                0, 2, 1, false),
        // (2026-09-20 林子记忆, 用户"记忆中有森林就直接过去, 不要只刷出 chunk 就
        // wander") 记你【亲眼看见过】的诡异森林信号方块 (菌岩/菌菇/菌索/疣块),
        // 供收珍珠时直接奔回去, 不再逐区块透视搜索。与堡垒"同行同款"处理。
        // ⚠ inference=false: 森林中心不该给挖金探索当"这里有矿"的假线索, 也不
        //   该触发"结构邻近推理可见" (菌岩成片不等于有箱子)。
        // ⚠ mergeDistance=64: 森林是无边界大片结构, 全局 8 格合并会让一片林子
        //   刷爆 MAX_CENTERS_PER_TYPE=20 的额度; 64 格合并 = 一片林子只留沿路
        //   少数几个中心, "回去"语义足够。
        // ⚠ remember 侧有群系守卫: 散落菌菇 (废墟/洞穴, 群系非 WARPED_FOREST)
        //   不入账 —— 20260920-155006 实锤 2 个坑底菌菇曾被当成"最近的林子"。
        WARPED_FOREST(
                new Block[]{Blocks.WARPED_NYLIUM, Blocks.WARPED_FUNGUS,
                        Blocks.WARPED_ROOTS, Blocks.WARPED_WART_BLOCK},
                0, 0, 1, false, 64),
        // (2026-09-21 要塞/末地门记忆, 用户"有记忆时就不用再丢眼睛了") 记
        // 【亲眼确认过】的要塞坐标 (丢眼垂直下落确认那一刻) 与末地门框架位置
        // (探索任务看到/近距擦过)。跨会话持久化到 knowledge.log, 消费点
        // GoToStrongholdPortalTask: 重启后命中记忆就直接去下挖/探索,
        // 不再从头丢眼。
        // ⚠ landmarks 留空: 不走 KnowledgeMemory.tick 的地标自动感知 ——
        //   要塞地标若挂石砖系方块会放大 BlockTracker 追踪面 (perf 红线:
        //   trackBlock 新方块触发全量重扫, 0918 卡死事故), 且石砖在主世界
        //   太常见会刷爆候选。记录只走两条显式路径 (LocateStronghold-
        //   CoordinatesTask 眼确认 / ExploreStrongholdTask 见门)。
        // ⚠ inference=false: 要塞/末地门是地下结构, 不给随机候选偏置与
        //   "结构邻近推理可见"当线索 (与 BLAZE_SPAWNER 同理, 防透视)。
        STRONGHOLD(new Block[]{}, 0, 0, 1, false),
        END_PORTAL(new Block[]{}, 0, 0, 1, false),
        // (2026-09-23 岩浆湖记忆, 用户"路过的岩浆湖也要像堡垒那样记住, 找岩浆时
        // 直接用") 记【亲眼看见】的岩浆湖锚点 (成片静止岩浆源中通过真视线判定的
        // 那个源), 供舀岩浆/造门时直接导航过去, 不再无方向 wander; 跨会话落盘。
        // ⚠ landmarks 留空: 绝不走 BlockTracker 全局追踪 LAVA —— 下界岩浆海会
        //   刷爆感知缓存 (trackBlock 全量重扫, 0918 卡死事故同款 perf 红线)。
        //   记录走专属字面扫描器 scanLavaLake (有界椭球 + 真视线 + "成片"确认,
        //   单格渗漏/小水洼不认 —— 用户强调"一定是岩浆湖")。
        // ⚠ inference=false: 湖不是结构线索, 不参与候选偏置/推理可见。
        // ⚠ mergeDistance=48: 一片湖只留一个锚点; maxCenters=40: 沿岩浆海长
        //   跑也够用 (其余类型沿用 20)。
        LAVA_LAKE(new Block[]{}, 0, 0, 1, false, 48, 40);

        final Block[] landmarks;
        final double minCandidateRadius;
        final double maxCandidateRadius;
        final int candidatesPerCenter;
        /**
         * 是否参与【结构级推理消费】: {@link #getCandidateAreas(Vec3d)} 无参版
         * (随机偏置候选) 与 {@link #isNearRememberedStructure} (推理可见)。
         */
        final boolean inference;

        /**
         * (2026-09-20) 同类型两个中心的合并距离。大片无边界结构 (森林) 需要大
         * 合并距离, 否则沿路刷爆中心额度; 点状结构用全局默认。
         */
        final double mergeDistance;

        /**
         * (2026-09-23) 该类型最多记住的中心数。岩浆湖沿下界岩浆海会持续遇到,
         * 给到 40; 其余类型沿用全局默认 {@value #MAX_CENTERS_PER_TYPE}。
         */
        final int maxCenters;

        StructureType(Block[] landmarks, double minRadius, double maxRadius, int candidatesPerCenter,
                      boolean inference) {
            this(landmarks, minRadius, maxRadius, candidatesPerCenter, inference, MERGE_DISTANCE,
                    MAX_CENTERS_PER_TYPE);
        }

        StructureType(Block[] landmarks, double minRadius, double maxRadius, int candidatesPerCenter,
                      boolean inference, double mergeDistance) {
            this(landmarks, minRadius, maxRadius, candidatesPerCenter, inference, mergeDistance,
                    MAX_CENTERS_PER_TYPE);
        }

        StructureType(Block[] landmarks, double minRadius, double maxRadius, int candidatesPerCenter,
                      boolean inference, double mergeDistance, int maxCenters) {
            this.landmarks = landmarks;
            this.minCandidateRadius = minRadius;
            this.maxCandidateRadius = maxRadius;
            this.candidatesPerCenter = candidatesPerCenter;
            this.inference = inference;
            this.mergeDistance = mergeDistance;
            this.maxCenters = maxCenters;
        }
    }

    // =========================================================================
    // (2026-09-18 inference 隔离) 加 BLAZE_SPAWNER 后必须把它从两处结构级
    // 消费里摘出去, 否则会顺着两条路偷渡成错误行为:
    //   ① MineAndCollectTask:2297 用 getCandidateAreas(pos) 无参版当探索偏置 —
    //      地下刷怪笼会变成"这里有矿"的假线索, 把挖矿探索拉偏;
    //   ② VisibilityHelper:405 用 isNearRememberedStructure 做【推理可见】—
    //      认过一个刷怪笼 = 它周围整片的箱子/容器都被判成"看得见", 无需真正望
    //      见。而地下结构本就刻意不进这类记忆 (见本类原注释), 用意正是"埋着的
    //      容器不透视"。
    // 二者都只关心【成片结构】, 单点刷怪笼不是这个语义, 故按 inference 标志过滤。
    // =========================================================================
    private static final StructureType[] INFERENCE_TYPES =
            java.util.Arrays.stream(StructureType.values())
                    .filter(t -> t.inference)
                    .toArray(StructureType[]::new);

    private final AltoClef _mod;
    private final Map<StructureType, Set<BlockPos>> _knownStructures = new EnumMap<>(StructureType.class);
    private int _scanCounter = 0;

    public KnowledgeMemory(AltoClef mod) {
        _mod = mod;
    }

    // (2026-09-16) 地标方块首次进游戏时注册到 BlockTracker (引用计数 +1 且
    // 不释放, 与 FortressExploreTask 等任务的 track/stopTracking 计数共存)。
    private boolean _landmarksTracked = false;

    public void tick() {
        if (!AltoClef.inGame() || _mod.getPlayer() == null) return;
        // 首个游戏 tick 注册地标方块 (运行时注册, 避开构造期 tracker 未就绪)。
        if (!_landmarksTracked) {
            _landmarksTracked = true;
            for (StructureType type : StructureType.values()) {
                _mod.getBlockTracker().trackBlock(type.landmarks);
            }
        }
        _scanCounter++;
        if (_scanCounter % SCAN_INTERVAL_TICKS != 0) return;
        scanLandmarksByPerception();
        scanLavaLakeByLiteralPerception();
    }

    /**
     * (2026-09-16 视力范围感知) 从 BlockTracker 感知缓存取地标候选 (bot 视力
     * 范围全量), 按距离排序, 每轮只对最近 MAX_LOS_CHECKS_PER_ROUND 个做真视线
     * 判定 —— 只有"亲眼看见"的地标才 remember, 不透视。
     */
    private void scanLandmarksByPerception() {
        BlockPos player = _mod.getPlayer().getBlockPos();
        for (StructureType type : StructureType.values()) {
            List<BlockPos> candidates = _mod.getBlockTracker().getKnownLocations(type.landmarks);
            if (candidates.isEmpty()) continue;
            candidates.sort((a, b) -> Double.compare(a.getSquaredDistance(player), b.getSquaredDistance(player)));
            int checks = 0;
            for (BlockPos pos : candidates) {
                // 只做有限次射线: 最近的几个都看不见 (背对/遮挡) 就等下一轮。
                if (++checks > MAX_LOS_CHECKS_PER_ROUND) break;
                if (VisibilityHelper.isBlockPerceivable(_mod, pos)) {
                    remember(type, pos);
                }
            }
        }
    }

    // =========================================================================
    // (2026-09-23 岩浆湖记忆) 路过式扫描 —— 不依赖 BlockTracker 追踪 LAVA
    // (下界岩浆海会刷爆感知缓存, trackBlock 全量重扫是 perf 红线), 改为每 20t
    // 做一次有界字面扫描, 三道闸认"湖":
    //   ① 字面椭球 (h24/v5) 找【暴露的静止岩浆源】(level==8 且正上方空气) ——
    //      被岩石压住的不算, 流动岩浆不算;
    //   ② 只对最近的 LAKE_LOS_CHECKS 个候选做真视线判定 (isBlockPerceivable),
    //      亲眼看见才认, 不透视 (同地标扫描护栏);
    //   ③ 锚点周围 h8/v4 盒内暴露静止源 ≥ LAKE_MIN_EXPOSED_SOURCES (12) 才入账
    //      —— 单格渗漏/小水洼不是"湖" (用户"一定是岩浆湖")。
    // 已在记住的湖 (mergeDistance 内, 含跨会话持久化锚点) 直接早退: 沿湖长跑
    // 时扫描成本近乎零, 也防止跨会话重复落盘。
    // =========================================================================
    // (2026-09-27 口径收编) "暴露静止源 ≥12 = 湖" 的判据本体已迁到
    // adris.altoclef.util.helpers.LavaLakeHelper —— 传送门锚点扫描 (原本是"只要有岩浆
    // 就选"的旧口径, 会把单格源瀑布当湖) 现在与本处共用同一份, 不再有第二口径。
    private static final int LAKE_SCAN_RADIUS_XZ = 24;
    private static final int LAKE_SCAN_RADIUS_Y = 5;
    private static final int LAKE_LOS_CHECKS = 4;

    private void scanLavaLakeByLiteralPerception() {
        if (_mod.getWorld() == null || _mod.getPlayer() == null) return;
        BlockPos feet = _mod.getPlayer().getBlockPos();
        // 已在记住的湖附近 → 不重扫 (getRememberedCenters 含跨会话持久化锚点,
        // 按存档:维度隔离)。
        Vec3d feetCenter = new Vec3d(feet.getX() + 0.5, feet.getY() + 0.5, feet.getZ() + 0.5);
        if (!getRememberedCenters(StructureType.LAVA_LAKE, feetCenter,
                StructureType.LAVA_LAKE.mergeDistance).isEmpty()) {
            return;
        }
        int hSq = LAKE_SCAN_RADIUS_XZ * LAKE_SCAN_RADIUS_XZ;
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -LAKE_SCAN_RADIUS_Y; dy <= LAKE_SCAN_RADIUS_Y; dy++) {
            for (int dx = -LAKE_SCAN_RADIUS_XZ; dx <= LAKE_SCAN_RADIUS_XZ; dx++) {
                for (int dz = -LAKE_SCAN_RADIUS_XZ; dz <= LAKE_SCAN_RADIUS_XZ; dz++) {
                    if (dx * dx + dz * dz > hSq) continue; // 水平圆截断
                    BlockPos p = feet.add(dx, dy, dz);
                    if (LavaLakeHelper.isExposedLavaSource(_mod, p)) candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty()) return;
        candidates.sort((a, b) -> Double.compare(a.getSquaredDistance(feet), b.getSquaredDistance(feet)));
        int checks = 0;
        for (BlockPos candidate : candidates) {
            // 最近的几个都看不见 (背对/遮挡) 就等下一轮, 与地标扫描同款节流。
            if (++checks > LAKE_LOS_CHECKS) return;
            if (!VisibilityHelper.isBlockPerceivable(_mod, candidate)) continue;
            if (LavaLakeHelper.countExposedLavaSourcesAround(_mod, candidate)
                    >= LavaLakeHelper.MIN_EXPOSED_SOURCES) {
                remember(StructureType.LAVA_LAKE, candidate);
                return;
            }
        }
    }

    // (2026-09-27 口径收编) 原 isExposedLavaSource / countExposedLavaSourcesAround
    // 已迁到 adris.altoclef.util.helpers.LavaLakeHelper, 与传送门锚点扫描共用同一份。

    /**
     * Candidate resource areas inferred from remembered structures.
     * These are guesses, not observations.
     */
    public List<BlockPos> getCandidateAreas(Vec3d fromPos) {
        return getCandidateAreas(fromPos, INFERENCE_TYPES);
    }

    /**
     * (2026-09-13 类型过滤版) 旧版把三种结构混在一起出候选 —— 其中
     * RUINED_PORTAL 的 landmark 是 OBSIDIAN/CRYING_OBSIDIAN, 而 bot 自建的
     * 下界门正是黑曜石 → 门被记成"废弃传送门地标", 候选区全锚在门附近。
     * 下界远征任务因此每轮都先绕门打转, 永远走不到"锁定方向"分支
     * ([Expedition] clue=memory 实锤: 目标全在门 ±50 格, radius 11~48 震荡,
     * bearing 每轮跳变)。按类型取用: 远征只认 NETHER_FORTRESS。
     */
    public List<BlockPos> getCandidateAreas(Vec3d fromPos, StructureType... types) {
        List<BlockPos> out = new ArrayList<>();
        for (StructureType type : types) {
            Set<BlockPos> centers = _knownStructures.getOrDefault(type, Collections.emptySet());
            for (BlockPos center : centers) {
                if (center.isWithinDistance(fromPos, CANDIDATE_MAX_DISTANCE)) {
                    for (int i = 0; i < type.candidatesPerCenter; i++) {
                        double r = type.minCandidateRadius + Math.random() * (type.maxCandidateRadius - type.minCandidateRadius);
                        double a = Math.random() * 2 * Math.PI;
                        out.add(center.add((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r)));
                    }
                }
            }
        }
        return out;
    }

    public boolean hasKnowledge() {
        return _knownStructures.values().stream().anyMatch(s -> !s.isEmpty());
    }

    /**
     * (2026-09-25 透视地牢箱子修复, 实例20260925-111302) 推理可见的 Y 邻近约束。
     * 结构中心记的是【地表地标】(村庄小路/耕地/原木、废弃传送门黑曜石), "容器在
     * 结构里"的人类推断只在结构本体的高度带成立 (房屋/地下室 ±数格)。现网实锤:
     * 村庄中心 (33,71,209) 记忆后 2ms, 同 tick 就近搜刮把 27.7 格外 y=45 的地牢
     * 箱子 (刷怪笼旁) 判为可感知 (32 格 3D 球把 dy=26 的地下容器也包住) → 派
     * LootContainerTask → baritone 垂直下挖 20 格。|dy|≤8 后地牢箱子不再被村庄
     * 推断, bot 真正走到地牢口有视线时才感知 (届时 nearbySpawnerBlocked 照常
     * 抑制开箱)。注意: 该方法当前唯一消费方是 VisibilityHelper 容器推理可见
     * (仅主世界), NETHER_FORTRESS 虽在 INFERENCE_TYPES 但从不进入本判定。
     */
    private static final double STRUCTURE_INFERENCE_MAX_Y_DELTA = 8;

    /**
     * Whether {@code pos} is within {@code radius} of a structure center we
     * have actually SEEN (village / ruined portal / fortress). Used by
     * VisibilityHelper for inference-based perception: a chest inside a
     * village house is "known" once the village itself has been seen, even
     * though a wall blocks the direct sightline (2026-08-21). Underground
     * dungeons/mineshafts are never remembered here, so their buried
     * containers stay invisible until the bot walks in and sees them.
     * (2026-09-25) 另加 {@link #STRUCTURE_INFERENCE_MAX_Y_DELTA} 的 Y 邻近约束。
     */
    public boolean isNearRememberedStructure(BlockPos pos, double radius) {
        // (2026-09-18) 只按 INFERENCE_TYPES 推理 —— 单点刷怪笼不在此列, 见该字段注释。
        for (StructureType type : INFERENCE_TYPES) {
            for (BlockPos center : _knownStructures.getOrDefault(type, Collections.emptySet())) {
                // (2026-09-25) Y 邻近约束: 地下 20+ 格的容器不算"结构里的箱子"。
                if (Math.abs(center.getY() - pos.getY()) > STRUCTURE_INFERENCE_MAX_Y_DELTA) continue;
                if (center.isWithinDistance(pos, radius)) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // (2026-09-16 跨会话地标记忆, 用户"挖金时看到堡垒, 回头找堡垒任务要能想起
    // 来") 旧记忆三个缺口: ① 纯内存, 游戏重开全丢; ② 只有"随机偏移候选",
    // 找堡垒任务拿不到精确记忆点; ③ _knownStructures 不分维度, 换维度残留。
    // 修法: 地标中心按 存档:维度 落盘 logs/altoclef/memory/knowledge.log
    // (每行 "存档:维度 TYPE x y z", 与 fortress_visited.log 同款机制), 并新增
    // getRememberedCenters 直读接口 (按维度隔离, 不再被 getCandidateAreas 的
    // 192 格距离过滤挡掉)。
    // =========================================================================
    private static final String KNOWLEDGE_FILE = "altoclef_knowledge.log";
    private static final Map<String, Map<StructureType, LinkedHashSet<BlockPos>>> _centersByKey = new HashMap<>();
    private static boolean _centersLoaded = false;

    private static java.nio.file.Path knowledgeFile() {
        return adris.altoclef.Debug.memoryFile(KNOWLEDGE_FILE, "knowledge.log");
    }

    /** 当前 存档:维度 key (与 FortressExploreTask.fortressVisitedKey 同口径)。 */
    private String worldKey() {
        // (2026-09-17 跨世界污染) 显示名 → 存档目录名, 统一走 WorldIdentity。
        // 旧 key(显示名:维度) 的存量记录自然作废 —— 它们本身就有"新建世界默认
        // 同名"的互灌风险, 换掉正是目的。
        return adris.altoclef.util.helpers.WorldIdentity.key(_mod);
    }

    /**
     * (2026-09-17 世界边界丢弃) 换存档时清空地标中心内存。
     * 刻意不动 `_centersLoaded`: 保持 true 才是不重载磁盘(丢弃语义), 否则下次
     * 访问会把另一个存档的地标读进来当导航目标。
     */
    public static void onWorldBoundary() {
        _centersByKey.clear();
    }

    private static void ensureCentersLoaded() {
        if (_centersLoaded) return;
        _centersLoaded = true;
        // (2026-09-17 数据集录制) 只有真的读进来内容才推进版本号 —— 旧实现无条件
        // +1, 导致录制器的记忆通道写出一条"rev 变了但内容还是空"的假快照
        // (20260917-100000 场次第 2 行实测: rev.knowledge=1 而 knowledge={})。
        boolean loaded = false;
        try {
            java.nio.file.Path f = knowledgeFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                // (2026-09-27 空格 key 修复) 统一走 WorldIdentity.decodeLine —— key 取存档
                // 目录名 (可含空格, 如 "新的世界 (5)"), 旧空格行按尾部定长字段右锚定兼容。
                // 旧写法 `split(" ") + length != 5 → continue` 会把含空格 key 的行静默丢弃
                // (实测本文件 487 行丢 423, 即 09-17 换目录名口径后的全部记录)。
                String[] parts = adris.altoclef.util.helpers.WorldIdentity.decodeLine(line, 4);
                if (parts == null) continue;
                StructureType type = structureTypeByName(parts[1]);
                if (type == null) continue;
                try {
                    BlockPos p = new BlockPos(Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                    Map<StructureType, LinkedHashSet<BlockPos>> byType =
                            _centersByKey.computeIfAbsent(parts[0], k -> new EnumMap<>(StructureType.class));
                    LinkedHashSet<BlockPos> set = byType.computeIfAbsent(type, t -> new LinkedHashSet<>());
                    if (set.size() < type.maxCenters && set.add(p)) loaded = true;
                } catch (NumberFormatException ignored) {
                }
            }
            if (loaded) {
                Debug.logMessage("[Knowledge] loaded "
                        + _centersByKey.values().stream().mapToInt(m -> m.values().stream().mapToInt(Set::size).sum()).sum()
                        + " remembered structure centers from " + KNOWLEDGE_FILE);
            }
        } catch (Exception e) {
            Debug.logMessage("[Knowledge] failed to load " + KNOWLEDGE_FILE + ": " + e.getMessage());
        } finally {
            if (loaded) _revision++;
        }
    }

    private static StructureType structureTypeByName(String name) {
        for (StructureType t : StructureType.values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    private void persistCenter(String key, StructureType type, BlockPos pos) {
        ensureCentersLoaded();
        Map<StructureType, LinkedHashSet<BlockPos>> byType =
                _centersByKey.computeIfAbsent(key, k -> new EnumMap<>(StructureType.class));
        LinkedHashSet<BlockPos> set = byType.computeIfAbsent(type, t -> new LinkedHashSet<>());
        if (set.contains(pos)) return;
        if (set.size() >= type.maxCenters) return;
        set.add(pos);
        try {
            java.nio.file.Files.write(knowledgeFile(),
                    adris.altoclef.util.helpers.WorldIdentity
                            .encodeLine(key, type.name(), pos.getX(), pos.getY(), pos.getZ())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 落盘失败不影响本会话内的记忆。
        }
    }

    /**
     * (2026-09-16) "亲眼看见过的地标中心"直读 (含跨会话持久化, 按 存档:维度
     * 隔离)。与 getCandidateAreas 的随机推断候选不同, 这里返回的是真实记忆点 —
     * FortressExploreTask 外围模式可直接导航过去, 不受 CANDIDATE_MAX_DISTANCE
     * (192 格) 限制 —— 挖金路过看到堡垒后哪怕跑远了, 回头找堡垒任务仍能想起。
     */
    public List<BlockPos> getRememberedCenters(StructureType type, Vec3d fromPos, double maxDistance) {
        List<BlockPos> out = new ArrayList<>();
        ensureCentersLoaded();
        Map<StructureType, LinkedHashSet<BlockPos>> byType = _centersByKey.get(worldKey());
        if (byType == null) {
            logCenterRead(type, out);
            return out;
        }
        Set<BlockPos> set = byType.get(type);
        if (set == null) {
            logCenterRead(type, out);
            return out;
        }
        double maxSq = maxDistance * maxDistance;
        for (BlockPos p : set) {
            if (p.getSquaredDistance(fromPos) <= maxSq) out.add(p);
        }
        logCenterRead(type, out);
        return out;
    }

    /** (2026-09-17 数据集录制) 记忆读事件 (决策入口, 同 tick 按类型去重)。 */
    private static void logCenterRead(StructureType type, List<BlockPos> found) {
        adris.altoclef.record.RecordManager.eventThrottled("memory_read", "centers:" + type.name(),
                "{\"kind\":\"centers\",\"key\":\"" + type.name() + "\",\"n\":" + found.size() + "}");
    }

    /**
     * (2026-09-21 要塞/末地门记忆) 显式记忆入口: 供任务在"亲眼确认结构"的
     * 决定性时刻直接写入 (要塞=丢眼垂直确认, 末地门=探索任务看见/近距擦过
     * 门框)。不走 tick 的地标自动感知 —— 这两类结构的地标不适合全局追踪
     * (见枚举注释), 记录点收敛在任务内。
     * 合并/落盘/录制事件与自动感知共用 {@link #remember} 一条管道。
     */
    public void rememberAt(StructureType type, BlockPos pos) {
        remember(type, pos.toImmutable());
    }

    private void remember(StructureType type, BlockPos pos) {
        // (2026-09-21 刷怪笼验明守卫) BLAZE_SPAWNER 的地标是通用刷怪笼方块,
        // 记录端原样裸奔: 主世界要塞的蠹虫笼/废弃矿井的蜘蛛笼方块完全相同,
        // 看到 ANY 刷怪笼就记成烈焰笼 (20260921-120334 实锤: 12:28:30 要塞
        // 蠹虫笼 9835,20,-10695 被记成 BLAZE_SPAWNER)。记录端必须先验明渲染
        // 实体是 BlazeEntity 才入账 —— 与消费端 WorldHelper.getSpawnerEntity
        // 同一款口径, 双保险。森林侧群系守卫同款先例。
        if (type == StructureType.BLAZE_SPAWNER
                && !(WorldHelper.getSpawnerEntity(_mod, pos) instanceof BlazeEntity)) {
            return;
        }
        // (2026-09-20 森林记忆) 散落信号方块守卫: 真林子里的菌岩/菌菇所在群系
        // 必然是 WARPED_FOREST; 废墟/洞穴里的零星菌菇不是林子, 不入账
        // (20260920-155006 实锤 2 个坑底菌菇曾被当成"最近的林子"蹲守)。
        // 只对森林做 —— 其余类型的地标 (下界砖/黑曜石/刷怪笼) 本身就是结构本体。
        if (type == StructureType.WARPED_FOREST) {
            var biome = _mod.getWorld().getBiome(pos);
            if (biome == null || !biome.matchesKey(net.minecraft.world.biome.BiomeKeys.WARPED_FOREST)) {
                return;
            }
        }
        Set<BlockPos> centers = _knownStructures.computeIfAbsent(type, t -> new HashSet<>());
        // Merge with an existing nearby center if we have one.
        for (BlockPos existing : centers) {
            if (existing.isWithinDistance(pos, type.mergeDistance)) {
                return;
            }
        }
        // (2026-10-01 跨会话合并缺口, 预先存在: 0916 落盘机制起) 合并只查内存
        // set —— 每次重启内存清零, 同一个村庄/门每会话再记几个新角落盘
        // (persistCenter 只查精确坐标), 多会话累计灌满 maxCenters=20 (实锤:
        // knowledge.log 20 条 VILLAGE 全是同一个村; 一个废墟门 6 条)。
        // 合并检查同样覆盖跨会话持久化中心, 同款 mergeDistance。
        ensureCentersLoaded();
        Map<StructureType, LinkedHashSet<BlockPos>> persistedByKey =
                _centersByKey.get(worldKey());
        if (persistedByKey != null) {
            for (BlockPos existing : persistedByKey.getOrDefault(type, new LinkedHashSet<>())) {
                if (existing.isWithinDistance(pos, type.mergeDistance)) {
                    return;
                }
            }
        }
        if (centers.size() >= type.maxCenters) {
            return;
        }
        BlockPos center = pos.toImmutable();
        centers.add(center);
        // (2026-09-17 数据集录制) 记忆写事件 + 版本号 (快照只在变化时写)。
        _revision++;
        adris.altoclef.record.RecordManager.event("memory_write",
                "{\"kind\":\"structure\",\"key\":\"" + type.name() + "\",\"pos\":["
                        + center.getX() + "," + center.getY() + "," + center.getZ() + "]}");
        // (2026-09-16) 落盘 + 首记日志 (验证"挖金时看到堡垒被记住"用)。
        persistCenter(worldKey(), type, center);
        Debug.logMessage("[Knowledge] remembered " + type.name() + " center at " + center.toShortString());
    }

    // ===== (2026-10-03 @forgetmem) 自动结构记忆作废 =====

    /**
     * 作废本 存档:维度 的自动结构记忆（{@code type == null} = 清空全部类型）。
     *
     * <p><b>为什么必须重写文件</b>：knowledge.log 是<b>只追加</b>的（{@link #persistCenter}
     * 只 append），只在内存里删的话下次启动会把行重新读回来。所以这里
     * ①清内存 {@code _centersByKey}（召回的唯一来源）与 {@code _knownStructures}
     * （会话集合，顺带解掉 remember 的合并阻挡），②<b>重写</b>文件——只丢弃目标
     * (key,type) 的行，其余（别的存档 / 别的类型 / 解不出的行）逐行原样保留。
     *
     * <p>动机（用户 1003）：bot 能自动<b>记住</b>结构，此前却<b>没有任何手段作废</b> ——
     * 若 STRONGHOLD 记忆本身记错了（投掷异常 / 地形噪声把落点确认到错处），它会跨会话
     * 一直信，每次重启都白跑。注意这<b>不是</b>要塞旁路眼闸那条缺陷的修法（那条在
     * BeatMinecraft2Task 的判据里，两者互不依赖）。
     *
     * @return 被清掉的条数（内存侧计数）
     */
    public int forgetCenters(StructureType type) {
        ensureCentersLoaded();
        String key = worldKey();
        int removed = 0;
        Map<StructureType, LinkedHashSet<BlockPos>> byType = _centersByKey.get(key);
        if (byType != null) {
            if (type == null) {
                for (Set<BlockPos> s : byType.values()) removed += s.size();
                byType.clear();
            } else {
                Set<BlockPos> s = byType.get(type);
                if (s != null) {
                    removed += s.size();
                    s.clear();
                }
            }
        }
        if (type == null) {
            for (Set<BlockPos> s : _knownStructures.values()) s.clear();
        } else {
            Set<BlockPos> s = _knownStructures.get(type);
            if (s != null) s.clear();
        }
        rewriteCenterFile(key, type);
        _revision++;
        return removed;
    }

    /** 重写 knowledge.log：只丢掉属于 (key, type) 的行，其余逐行原样保留。 */
    private static void rewriteCenterFile(String key, StructureType type) {
        try {
            java.nio.file.Path f = knowledgeFile();
            if (!java.nio.file.Files.exists(f)) return;
            List<String> keep = new ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = adris.altoclef.util.helpers.WorldIdentity.decodeLine(line, 4);
                if (parts != null && parts[0].equals(key)) {
                    StructureType t = structureTypeByName(parts[1]);
                    if (t != null && (type == null || t == type)) continue; // 丢弃
                }
                keep.add(line);
            }
            String body = keep.isEmpty() ? "" : (String.join("\n", keep) + "\n");
            java.nio.file.Files.write(f, body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
        } catch (Exception e) {
            Debug.logMessage("[Knowledge] rewrite " + KNOWLEDGE_FILE + " failed: " + e.getMessage());
        }
    }

    // ===== (2026-09-17 数据集录制) 记忆只读导出 =====

    /** 记忆写版本号 (静态: 跨会话回填也走它, 见 ensureCentersLoaded)。 */
    private static volatile long _revision = 0;

    public static long getMemoryRevision() {
        return _revision;
    }

    /**
     * (2026-09-17 数据集录制) 当前存档:维度全部地标记忆的只读快照
     * (键形如 {@code center:FORTRESS})。合并"本会话看到过的"与"跨会话持久化的"
     * 两个来源 —— 对 bot 而言两者都是它"记得"的东西, 不分家。
     */
    public Map<String, List<BlockPos>> dumpMemoryForRecord() {
        Map<String, List<BlockPos>> out = new java.util.LinkedHashMap<>();
        try {
            for (Map.Entry<StructureType, Set<BlockPos>> e : _knownStructures.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                out.computeIfAbsent("center:" + e.getKey().name(), k -> new ArrayList<>()).addAll(e.getValue());
            }
            ensureCentersLoaded();
            Map<StructureType, LinkedHashSet<BlockPos>> persisted = _centersByKey.get(worldKey());
            if (persisted != null) {
                for (Map.Entry<StructureType, LinkedHashSet<BlockPos>> e : persisted.entrySet()) {
                    if (e.getValue().isEmpty()) continue;
                    List<BlockPos> list = out.computeIfAbsent("center:" + e.getKey().name(), k -> new ArrayList<>());
                    for (BlockPos p : e.getValue()) {
                        if (!list.contains(p)) list.add(p);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean contains(Block[] arr, Block b) {
        for (Block block : arr) {
            if (block == b) return true;
        }
        return false;
    }
}
