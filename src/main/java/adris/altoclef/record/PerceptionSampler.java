package adris.altoclef.record;

import adris.altoclef.AltoClef;
import adris.altoclef.Settings;
import adris.altoclef.trackers.SoundTracker;
import adris.altoclef.util.helpers.VisibilityHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * (2026-09-17 数据集录制) 通道 D —— 可见世界。
 *
 * <h3>用的是项目自己那套视觉系统</h3>
 * <ul>
 *   <li><b>看得见/看不见的规则</b>: {@link VisibilityHelper#perceiveSource} —— 与 bot
 *       决策同一条 LOS/FOV/推断判定 (单一事实来源, 录制端不另立标准)。</li>
 *   <li><b>近场扫 + 远处稀疏扫</b>: 与 {@code BlockTracker} 的双区扫描同构 ——
 *       近场半径内 step=1 逐格看; 远处按 step 稀疏看 (对应它的 远区 r128/step2)。
 *       区别只在于: BlockTracker 只扫"任务 trackBlock 过的类型", 而录制器扫
 *       <b>全类型</b> —— 否则守炉/旅行/战斗时候选集≈空 (20260917-100000 场次实测
 *       2495 帧总共只录到 2377 个方块, 全是那台熔炉自己)。</li>
 *   <li><b>瞬时记忆</b>: {@code BlockTracker} 无共享实现, 项目的 5s/40s 记忆是
 *       {@code MineAndCollectTask} 私有的。所以这里自持一份 <b>录制专用</b> 的视觉
 *       记忆 (pos → 最后可见 tick) —— 完全不碰任务状态, 关掉录制就不存在。</li>
 * </ul>
 *
 * <h3>扫描分期 (性能)</h3>
 * 近场 12 格 = 25³ 格, 逐 tick 全扫不可能。按 X 列分期: 每 tick 只扫 1 列
 * (近场 25×25 + 远场 13×25 ≈ 950 格 getBlockState), 25 tick (1.25s) 走完整圈。
 * 每格都先判空气 (绝大多数是空气, 直接跳过), 再走 {@code perceiveSource}
 * (其内部 LOS 复用 {@code _losCache} 400ms 缓存)。
 *
 * <h3>每条感知都带来源分级 (硬规则)</h3>
 * <ul>
 *   <li>{@code observed} —— 真视线: los / fluid_los / fluid_surface / portal / bed;
 *       实体的 sight / sound</li>
 *   <li>{@code inferred} —— 推断: structure_inference / canopy_inference /
 *       stone_medium / experience / crafting_table_near</li>
 *   <li>{@code remembered} —— 本圈没再确认、但仍在 {@code recordSightMemorySeconds}
 *       窗口内 (方块), 或仍在 EntityTracker 20s 窗口内的实体</li>
 * </ul>
 * 被岩石包住/隔墙的方块永远不会进记忆 —— 反透视边界不变。
 */
final class PerceptionSampler {

    private PerceptionSampler() {}

    /** 远场竖直范围 (格), 与 BlockTracker 远区的"广覆盖"取向一致。 */
    private static final int FAR_Y_RANGE = 24;

    /** 一条视觉记忆: 最近一次被判定"看得见"时的方块与来源。 */
    private static final class Sight {
        final String block;
        String src;
        long lastSeenTick;

        Sight(String block, String src, long tick) {
            this.block = block;
            this.src = src;
            this.lastSeenTick = tick;
        }
    }

    /** 视觉记忆: 坐标 → 最近可见信息。只装"过 perceiveSource 的方块"(反透视)。 */
    private static final Map<BlockPos, Sight> _sight = new HashMap<>();
    /** 记忆上限: 正常远达不到 (可视方块量级在千), 仅防异常膨胀。 */
    private static final int SIGHT_CAP = 60000;
    private static int _sliceCursor = 0;
    private static long _lastPruneTick = Long.MIN_VALUE;

    static String sample(AltoClef mod) {
        Settings s = mod.getModSettings();
        int maxBlocks = s == null ? 512 : s.getRecordPerceptionMaxBlocks();
        int maxEntities = s == null ? 64 : s.getRecordPerceptionMaxEntities();
        double range = s == null ? 48 : s.getRecordPerceptionRange();
        int nearR = s == null ? 12 : Math.max(0, s.getRecordNearScanRadius());
        int farStep = s == null ? 4 : Math.max(1, s.getRecordFarScanStep());
        long memoryTicks = (long) ((s == null ? 20 : s.getRecordSightMemorySeconds()) * 20);

        ClientPlayerEntity p = mod.getPlayer();
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        sb.append("\"seq\":").append(RecordManager.currentSeq());
        sb.append(",\"t\":").append(WorldHelper.getTicks());

        if (p == null || mod.getWorld() == null) {
            _sight.clear();
            sb.append(",\"blocks\":[],\"entities\":[],\"sounds\":[],\"stats\":{\"scan\":\"no-player\"}}");
            return sb.toString();
        }

        long now = WorldHelper.getTicks();
        Vec3d eye = p.getPos();
        double rangeSq = range * range;
        int nearCols = 2 * nearR + 1;
        int slices = Math.max(1, nearCols);

        scanSlice(mod, p, now, nearR, farStep, slices, (int) Math.max(nearR, range));
        if (now - _lastPruneTick >= slices) {
            _lastPruneTick = now;
            pruneSight(now, memoryTicks);
        }

        // ---- 方块: 从视觉记忆里取最近的 maxBlocks 个 ----
        int candBlocks = 0;
        List<BlockPos> picked = new ArrayList<>();
        if (maxBlocks > 0 && !_sight.isEmpty()) {
            // 有界最大堆: 队首永远是最远的那个, 满了就挤掉它 —— 避免每帧对全记忆排序。
            PriorityQueue<BlockPos> heap = new PriorityQueue<>(
                    Comparator.comparingDouble(b -> -b.getSquaredDistance(eye)));
            for (Map.Entry<BlockPos, Sight> e : _sight.entrySet()) {
                long age = now - e.getValue().lastSeenTick;
                if (age > memoryTicks) continue;
                double dSq = e.getKey().getSquaredDistance(eye);
                if (dSq > rangeSq) continue;
                candBlocks++;
                heap.offer(e.getKey());
                if (heap.size() > maxBlocks) heap.poll();
            }
            while (!heap.isEmpty()) picked.add(heap.poll());
            picked.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(eye)));
        }
        sb.append(",\"blocks\":[");
        boolean first = true;
        for (BlockPos bp : picked) {
            Sight sight = _sight.get(bp);
            if (sight == null) continue;
            long age = now - sight.lastSeenTick;
            // 本圈内确认过 → 用它自己的来源分级; 只在本圈之外但记忆窗口内 → remembered
            boolean fresh = age < slices;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"p\":");
            Json.ivec(sb, bp.getX(), bp.getY(), bp.getZ());
            sb.append(",\"b\":");
            Json.str(sb, sight.block);
            sb.append(",\"d\":").append(Json.f(Math.sqrt(bp.getSquaredDistance(eye))));
            sb.append(",\"src\":");
            Json.str(sb, fresh ? sight.src : "remembered");
            sb.append(",\"g\":");
            Json.str(sb, fresh ? grade(sight.src) : "remembered");
            sb.append(",\"age\":").append(age);
            sb.append('}');
        }
        sb.append(']');

        // ---- 实体 ----
        int candEntities = 0;
        List<Entity> entityCandidates = new ArrayList<>();
        if (maxEntities > 0) {
            try {
                for (Entity e : mod.getEntityTracker().getAllPerceivedEntities()) {
                    if (e == null || e == p) continue;
                    if (e.getPos().squaredDistanceTo(eye) > rangeSq) continue;
                    candEntities++;
                    entityCandidates.add(e);
                }
            } catch (Throwable ignored) {
            }
        }
        if (entityCandidates.size() > maxEntities) {
            entityCandidates.sort(Comparator.comparingDouble(e -> e.getPos().squaredDistanceTo(eye)));
        }
        sb.append(",\"entities\":[");
        first = true;
        int shownEntities = 0;
        for (int i = 0; i < entityCandidates.size() && i < maxEntities; i++) {
            Entity e = entityCandidates.get(i);
            String src;
            try {
                if (VisibilityHelper.isEntityVisible(mod, e)) {
                    src = "sight";
                } else if (mod.getSoundTracker().isEntityAudible(e.getPos())) {
                    src = "sound";
                } else {
                    src = "remembered"; // 20s 感知记忆窗口内
                }
            } catch (Throwable t) {
                continue;
            }
            if (!first) sb.append(',');
            first = false;
            shownEntities++;
            Vec3d ep = e.getPos();
            Vec3d ev = e.getVelocity();
            sb.append("{\"id\":").append(e.getId());
            sb.append(",\"type\":");
            Json.str(sb, entityTypeId(e));
            sb.append(",\"p\":");
            Json.vec(sb, ep.x, ep.y, ep.z);
            sb.append(",\"v\":");
            Json.vec(sb, ev.x, ev.y, ev.z);
            sb.append(",\"d\":").append(Json.f(Math.sqrt(ep.squaredDistanceTo(eye))));
            String hp = null;
            if (e instanceof LivingEntity le) {
                hp = Json.f(le.getHealth());
            }
            sb.append(",\"hp\":").append(hp == null ? "null" : hp);
            if (e instanceof ItemEntity ie) {
                sb.append(",\"item\":");
                Json.str(sb, StateSampler.itemId(ie.getStack().getItem()));
                sb.append(",\"n\":").append(ie.getStack().getCount());
            }
            sb.append(",\"src\":");
            Json.str(sb, src);
            sb.append(",\"g\":");
            Json.str(sb, "remembered".equals(src) ? "remembered" : "observed");
            sb.append('}');
        }
        sb.append(']');

        // ---- 声音 (听觉通道, 独立于视觉) ----
        sb.append(",\"sounds\":[");
        first = true;
        try {
            long nowMs = System.currentTimeMillis();
            for (SoundTracker.RecentSound snd : mod.getSoundTracker().dumpRecentSounds()) {
                if (snd == null) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":");
                Json.str(sb, snd.soundIdPath);
                sb.append(",\"p\":");
                Json.vec(sb, snd.pos.x, snd.pos.y, snd.pos.z);
                sb.append(",\"ageMs\":").append(nowMs - snd.timeMs);
                sb.append(",\"d\":").append(Json.f(Math.sqrt(snd.pos.squaredDistanceTo(eye))));
                sb.append('}');
            }
        } catch (Throwable ignored) {
        }
        sb.append(']');

        // ---- 统计 (离线分析感知密度/截断/扫描开销用) ----
        sb.append(",\"stats\":{\"candBlocks\":").append(candBlocks)
                .append(",\"shownBlocks\":").append(picked.size())
                .append(",\"candEntities\":").append(candEntities)
                .append(",\"shownEntities\":").append(shownEntities)
                .append(",\"sightCache\":").append(_sight.size())
                .append(",\"slice\":").append(_sliceCursor)
                .append(",\"slices\":").append(slices)
                .append(",\"nearR\":").append(nearR)
                .append(",\"farStep\":").append(farStep)
                .append(",\"maxBlocks\":").append(maxBlocks)
                .append(",\"maxEntities\":").append(maxEntities)
                .append('}');

        sb.append('}');
        return sb.toString();
    }

    /**
     * 扫一片世界并更新视觉记忆。
     *
     * <p>按 X 列分期: 本 tick 只处理 {@code col % slices == _sliceCursor} 的列
     * (近场每列是 y×z 一整个竖直面)。近场半径内逐格看; 远场按 {@code farStep}
     * 稀疏看, 且跳过已被近场覆盖的区域 (不重复扫)。
     */
    private static void scanSlice(AltoClef mod, ClientPlayerEntity p, long now,
                                 int nearR, int farStep, int slices, int farR) {
        BlockPos origin = p.getBlockPos();
        int slice = _sliceCursor;
        _sliceCursor = (_sliceCursor + 1) % slices;

        int bottom;
        int top;
        try {
            bottom = mod.getWorld().getBottomY();
            top = mod.getWorld().getTopY();
        } catch (Throwable t) {
            return;
        }

        int nearCols = 2 * nearR + 1;
        for (int ci = 0; ci < nearCols; ci++) {
            if (ci % slices != slice) continue;
            int dx = -nearR + ci;
            int x = origin.getX() + dx;
            for (int dy = -nearR; dy <= nearR; dy++) {
                int y = origin.getY() + dy;
                if (y < bottom || y >= top) continue;
                for (int dz = -nearR; dz <= nearR; dz++) {
                    tryOne(mod, x, y, origin.getZ() + dz, now);
                }
            }
        }

        // 远场: 同一套分期切片, 稀疏采样 (对应 BlockTracker 远区的视野取向)。
        // 近场立方之内的格子跳过 —— 那边已经逐格看过了, 不重复扫。
        if (farR <= nearR) return;
        int farCols = 2 * (farR / farStep) + 1;
        for (int ci = 0; ci < farCols; ci++) {
            if (ci % slices != slice) continue;
            int dx = -farR + ci * farStep;
            int x = origin.getX() + dx;
            for (int dy = -FAR_Y_RANGE; dy <= FAR_Y_RANGE; dy += farStep) {
                int y = origin.getY() + dy;
                if (y < bottom || y >= top) continue;
                for (int dz = -farR; dz <= farR; dz += farStep) {
                    if (Math.abs(dx) <= nearR && Math.abs(dy) <= nearR && Math.abs(dz) <= nearR) continue;
                    tryOne(mod, x, y, origin.getZ() + dz, now);
                }
            }
        }
    }

    /** 扫一格: 空气直接跳过, 过不了 perceiveSource 的一律不进记忆 (反透视)。 */
    private static void tryOne(AltoClef mod, int x, int y, int z, long now) {
        // 必须是不可变 BlockPos: perceiveSource 内部会把它当 _losCache 的 key 用。
        BlockPos pos = new BlockPos(x, y, z);
        Block block;
        try {
            net.minecraft.block.BlockState st = mod.getWorld().getBlockState(pos);
            if (st.isAir()) return; // 绝大多数格子是空气, 一次查询就挡掉
            block = st.getBlock();
        } catch (Throwable t) {
            return;
        }
        String src = VisibilityHelper.perceiveSource(mod, pos);
        if (src == null) return;
        String id = blockId(block);
        Sight prev = _sight.get(pos);
        if (prev != null && prev.block.equals(id)) {
            prev.src = src;
            prev.lastSeenTick = now;
        } else {
            if (_sight.size() >= SIGHT_CAP) return;
            _sight.put(pos, new Sight(id, src, now));
        }
    }

    /** 清掉超出记忆窗口的条目 (每整圈一次, 不在每帧做)。 */
    private static void pruneSight(long now, long memoryTicks) {
        Iterator<Map.Entry<BlockPos, Sight>> it = _sight.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeenTick > memoryTicks) it.remove();
        }
    }

    /**
     * (2026-09-17) 会话边界复位: 记忆里的 tick 是"上个世界"的, 不清会污染新会话
     * 前若干帧的 age 判定 (新世界 tick 从 0 重算)。
     */
    static void reset() {
        _sight.clear();
        _sliceCursor = 0;
        _lastPruneTick = Long.MIN_VALUE;
    }

    /** 细粒度来源 → 粗粒度等级。未知来源按 inferred 处理 (保守: 不当作真观测)。 */
    private static String grade(String src) {
        switch (src) {
            case "los":
            case "fluid_los":
            case "fluid_surface":
            case "portal":
            case "bed":
                return "observed";
            default:
                return "inferred";
        }
    }

    private static String blockId(Block block) {
        try {
            return Registry.BLOCK.getId(block).toString();
        } catch (Throwable t) {
            return String.valueOf(block);
        }
    }

    private static String entityTypeId(Entity e) {
        try {
            return Registry.ENTITY_TYPE.getId(e.getType()).toString();
        } catch (Throwable t) {
            return e.getClass().getSimpleName();
        }
    }
}