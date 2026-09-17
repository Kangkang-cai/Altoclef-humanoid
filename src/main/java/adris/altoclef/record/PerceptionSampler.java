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
import java.util.List;

/**
 * (2026-09-17 数据集录制) 通道 D —— 可见世界 (每帧全量, 只有 bot 真能感知的东西)。
 *
 * <h3>候选来源 (绝不做全方块扫描)</h3>
 * 方块: {@code BlockTracker.getTrackedBlockTypes()} (即"任务此刻在关注的方块类型")
 * × 该类型的缓存坐标, 再按 {@code recordPerceptionRange} 与
 * {@link VisibilityHelper#perceiveSource} 双重过滤。没有任务在关注的方块不进数据 ——
 * bot 也不会因为不存在的东西改变行为, 录进去只是噪音。
 * <p>
 * 实体: {@code EntityTracker.getAllPerceivedEntities()} —— tracker 内部已按拟人
 * 感知 (视线/听觉/20s 记忆窗口) 过滤过, 与 bot 决策口径完全一致。
 *
 * <h3>感知来源分级 (硬规则)</h3>
 * 每条感知都带 {@code src} (细粒度来源) 与 {@code g} (粗粒度等级):
 * <ul>
 *   <li>{@code observed} —— 真视线: los / fluid_los / fluid_surface / portal / bed,
 *       实体的 sight / sound</li>
 *   <li>{@code inferred} —— 推断: structure_inference / canopy_inference /
 *       stone_medium / experience / crafting_table_near</li>
 *   <li>{@code remembered} —— 实体的 20s 感知记忆窗口 (曾经看到/听到, 此刻看不到)</li>
 * </ul>
 * 方块侧的 "remembered"(见过即记忆的贵重方块) 不在本通道, 而在记忆通道 (E) ——
 * 本通道只回答"此刻能感知什么", 长期记忆是另一个通道的事。
 *
 * <p>性能: 复用 {@code VisibilityHelper._losCache} (400ms TTL), 因此同一方块在
 * 400ms 内不重打射线; 且先按距离截断再算来源, 射线次数被 maxBlocks 卡住。
 */
final class PerceptionSampler {

    private PerceptionSampler() {}

    static String sample(AltoClef mod) {
        Settings s = mod.getModSettings();
        int maxBlocks = s == null ? 512 : s.getRecordPerceptionMaxBlocks();
        int maxEntities = s == null ? 64 : s.getRecordPerceptionMaxEntities();
        double range = s == null ? 48 : s.getRecordPerceptionRange();

        ClientPlayerEntity p = mod.getPlayer();
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        sb.append("\"seq\":").append(RecordManager.currentSeq());
        sb.append(",\"t\":").append(WorldHelper.getTicks());

        if (p == null || mod.getWorld() == null) {
            sb.append(",\"blocks\":[],\"entities\":[],\"sounds\":[]}");
            return sb.toString();
        }

        Vec3d eye = p.getPos();
        double rangeSq = range * range;

        // ---- 方块 ----
        int candBlocks = 0;
        List<BlockPos> blockCandidates = new ArrayList<>();
        if (maxBlocks > 0) {
            try {
                for (Block type : mod.getBlockTracker().getTrackedBlockTypes()) {
                    for (BlockPos bp : mod.getBlockTracker().getKnownLocations(type)) {
                        if (bp.getSquaredDistance(eye) > rangeSq) continue;
                        candBlocks++;
                        blockCandidates.add(bp);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (blockCandidates.size() > maxBlocks) {
            blockCandidates.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(eye)));
        }
        sb.append(",\"blocks\":[");
        boolean first = true;
        int shownBlocks = 0;
        for (int i = 0; i < blockCandidates.size() && i < maxBlocks; i++) {
            BlockPos bp = blockCandidates.get(i);
            String src = VisibilityHelper.perceiveSource(mod, bp);
            if (src == null) continue; // 该类型被追踪, 但此刻这一格并不"可感知"
            Block block;
            try {
                block = mod.getWorld().getBlockState(bp).getBlock();
            } catch (Throwable t) {
                continue;
            }
            if (!first) sb.append(',');
            first = false;
            shownBlocks++;
            sb.append("{\"p\":");
            Json.ivec(sb, bp.getX(), bp.getY(), bp.getZ());
            sb.append(",\"b\":");
            Json.str(sb, blockId(block));
            sb.append(",\"d\":").append(Json.f(Math.sqrt(bp.getSquaredDistance(eye))));
            sb.append(",\"src\":");
            Json.str(sb, src);
            sb.append(",\"g\":");
            Json.str(sb, grade(src));
            // 该坐标是否已在"见过即记忆"表里 (第一次看到 vs 早就知道)
            sb.append(",\"known\":").append(VisibilityHelper.isValuableRemembered(mod, block, bp));
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
                hp = String.valueOf(Json.f(le.getHealth()));
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
            long now = System.currentTimeMillis();
            for (SoundTracker.RecentSound snd : mod.getSoundTracker().dumpRecentSounds()) {
                if (snd == null) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":");
                Json.str(sb, snd.soundIdPath);
                sb.append(",\"p\":");
                Json.vec(sb, snd.pos.x, snd.pos.y, snd.pos.z);
                sb.append(",\"ageMs\":").append(now - snd.timeMs);
                sb.append(",\"d\":").append(Json.f(Math.sqrt(snd.pos.squaredDistanceTo(eye))));
                sb.append('}');
            }
        } catch (Throwable ignored) {
        }
        sb.append(']');

        // ---- 统计 (供离线分析感知密度/截断是否发生) ----
        sb.append(",\"stats\":{\"candBlocks\":").append(candBlocks)
                .append(",\"shownBlocks\":").append(shownBlocks)
                .append(",\"candEntities\":").append(candEntities)
                .append(",\"shownEntities\":").append(shownEntities)
                .append(",\"maxBlocks\":").append(maxBlocks)
                .append(",\"maxEntities\":").append(maxEntities)
                .append('}');

        sb.append('}');
        return sb.toString();
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