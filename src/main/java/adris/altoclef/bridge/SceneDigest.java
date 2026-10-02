package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.speedrun.BeatMinecraftConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.trackers.KnowledgeMemory;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.SurfaceHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /status 的"任务细化 + 环境感知"字段采集（阶段三感知补全，1002 需求）。
 *
 * <p>动机：task 只有顶层链名（{@code BeatMinecraft2Task} 长驻 ⇒ 永远"主线任务"），
 * 环境完全没报 ⇒ LLM 缺场景锚点开始编造游戏事实。本类补 7 个字段：
 * {@code taskDetail / mainlineProgress / timeOfDay / weather / environment / biome / nearbyHostiles}。
 *
 * <p>口径要点：
 * <ul>
 *   <li><b>不进 L0 事件</b>：只随 5s 快照拉取（这些字段高频变化，进事件流会刷屏）。</li>
 *   <li><b>environment 复用既有迟滞</b>：地表/洞穴判定直接调
 *       {@link SurfaceHelper#isBelowSurfaceDebounced}（项目已调好的顶板厚度 + 确认窗迟滞），
 *       避免在洞穴口来回跳；维度与"完全淹没"立即翻面（不同维度/危险态没有抖动的语义）。</li>
 *   <li><b>biome 取法照 {@code record/FrameSampler} 的 1.18.2 口径</b>：走
 *       {@code RegistryEntry.getKey()}（本仓是 1.18.2，没有 1.19+ 的 {@code Registry.BIOME}）。</li>
 *   <li><b>全部 try/catch 兜底</b>：采样在客户端线程热路径上，任何异常都必须降级为空串/0，
 *       绝不能把异常抛进快照构造。</li>
 * </ul>
 *
 * <p>线程模型：仅客户端线程（{@link BotSnapshot#snapshot} 内）调用。
 */
final class SceneDigest {

    private SceneDigest() {}

    // ===================== 1. taskDetail =====================

    /** 当前实际在干的子任务中文短名（取任务链最深处）。允许高频变化，注入侧不进变化键。 */
    static String taskDetail(AltoClef mod) {
        try {
            var runner = mod.getTaskRunner();
            TaskChain chain = runner == null ? null : runner.getCurrentTaskChain();
            if (chain == null) return "";
            List<Task> stack = chain.getTasks();
            if (stack == null || stack.isEmpty()) return "";
            // addTaskToChain 是"父→子"顺序（Task.java:106），最深处 = 末元素
            Task deep = stack.get(stack.size() - 1);
            String verb = verbFor(deep);
            // ResourceTask 系能拿到目标物 → "合成钻石镐" / "挖钻石" 这类具体描述
            if (deep instanceof ResourceTask rt) {
                Item[] matches = ItemTarget.getMatches(rt.getItemTargets());
                if (matches != null && matches.length > 0) {
                    String zh = ItemNames.name(new ItemStack(matches[0]));
                    if (!zh.isEmpty()) return verb + zh;
                }
            }
            String mapped = CLASS_ZH.get(deep.getClass().getSimpleName());
            if (mapped != null) return mapped;
            String state = deep.debugState();
            if (state != null && !state.isBlank()) return truncate(state, 24);
            return truncate(deep.getClass().getSimpleName(), 24);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 任务类名前缀 → 中文动词（粗粒度动作词）。 */
    private static String verbFor(Task t) {
        String n = t.getClass().getSimpleName();
        if (n.startsWith("Craft")) return "合成";
        if (n.contains("Smelt") || n.contains("Fuel")) return "冶炼";
        if (n.startsWith("Mine")) return "挖";
        if (n.contains("Kill")) return "击杀";
        if (n.contains("Equip")) return "穿戴";
        if (n.contains("Eat") || n.contains("Food")) return "觅食";
        if (n.contains("Catalogued")) return "备料";
        if (n.contains("Deposit") || n.contains("Stash")) return "存放";
        if (n.startsWith("Explore")) return "探索";
        if (n.startsWith("Get") || n.startsWith("Go") || n.contains("Travel")) return "前往";
        if (n.contains("Collect")) return "收集";
        return "进行";
    }

    /** 常见任务类 → 中文短名（无目标物可达时用）。 */
    private static final Map<String, String> CLASS_ZH = new HashMap<>();

    static {
        CLASS_ZH.put("GetToBlockTask", "赶路");
        CLASS_ZH.put("GetToXZTask", "赶路");
        CLASS_ZH.put("GetToYTask", "上下走");
        CLASS_ZH.put("GetToEntityTask", "靠近");
        CLASS_ZH.put("FollowPlayerTask", "跟随");
        CLASS_ZH.put("EatItemTask", "吃东西");
        CLASS_ZH.put("IdleTask", "待命");
        CLASS_ZH.put("ExploreCaveTask", "探洞");
        CLASS_ZH.put("ExploreStrongholdTask", "找要塞");
        CLASS_ZH.put("FortressExploreTask", "探堡垒");
        CLASS_ZH.put("CollectBlazeRodsTask", "找烈焰棒");
        CLASS_ZH.put("CollectEnderPearlsInForestTask", "蹲林收珍珠");
        CLASS_ZH.put("GoToStrongholdPortalTask", "去要塞传送门");
        CLASS_ZH.put("EnterNetherPortalTask", "进下界门");
        CLASS_ZH.put("KillEnderDragonTask", "打末影龙");
        CLASS_ZH.put("KillEnderDragonWithBedsTask", "炸床打龙");
        CLASS_ZH.put("GetBuildingMaterialsTask", "备建材");
        CLASS_ZH.put("ChunkSearchTask", "扫区块");
        CLASS_ZH.put("DestroyBlockTask", "拆方块");
        CLASS_ZH.put("EquipArmorTask", "穿甲");
        CLASS_ZH.put("CraftInTableTask", "合成台合成");
        CLASS_ZH.put("PickupDroppedItemTask", "捡东西");
        CLASS_ZH.put("CollectFoodTask", "囤食物");
        CLASS_ZH.put("GetToSurfaceTask", "回地表");
        CLASS_ZH.put("DiveToGrabItemTask", "下水捞物");
        CLASS_ZH.put("KillEntityTask", "打怪");
    }

    // ===================== 2. mainlineProgress =====================

    private static final int ROD_TARGET = 8;   // 16 eye ÷ 2（1 眼 = 1 粉 = 0.5 棒）
    private static final int POWDER_TARGET = 16;

    /**
     * 主线阶段 + 进度（如 "下界·烈焰棒2/4"）。主线没在跑返回空串（对端据此省略该字段）。
     *
     * <p>只读合成：维度 + 关键物品计数 + KnowledgeMemory 结构记忆，不查 BeatMinecraft2Task
     * 私有阶段状态（那需要新增访问器；等真正需要更细的阶段名再加窄接口）。
     */
    static String mainlineProgress(AltoClef mod) {
        try {
            var chain = mod.getUserTaskChain();
            Task cur = chain == null ? null : chain.getCurrentTask();
            if (!(cur instanceof BeatMinecraft2Task)) return "";

            Dimension dim = null;
            try {
                dim = WorldHelper.getCurrentDimension();
            } catch (Throwable ignored) {
            }
            var storage = mod.getItemStorage();
            int eyes = storage == null ? 0 : storage.getItemCount(Items.ENDER_EYE);
            int rods = storage == null ? 0 : storage.getItemCount(Items.BLAZE_ROD);
            int powder = storage == null ? 0 : storage.getItemCount(Items.BLAZE_POWDER);

            int targetEyes = 16, minEyes = 12;
            try {
                BeatMinecraftConfig cfg = BeatMinecraft2Task.getConfig();
                if (cfg != null) {
                    targetEyes = cfg.targetEyes;
                    minEyes = cfg.minimumEyes;
                }
            } catch (Throwable ignored) {
            }

            if (dim == Dimension.END) return "末地·屠龙";
            boolean strongholdKnown = hasStructure(mod, KnowledgeMemory.StructureType.STRONGHOLD);
            if (eyes >= minEyes || strongholdKnown) {
                return "要塞·末影之眼" + eyes + "/" + targetEyes;
            }
            boolean fortressKnown = hasStructure(mod, KnowledgeMemory.StructureType.NETHER_FORTRESS)
                    || hasStructure(mod, KnowledgeMemory.StructureType.BLAZE_SPAWNER);
            if (dim == Dimension.NETHER || fortressKnown) {
                String s = "下界·烈焰棒" + rods + "/" + ROD_TARGET;
                if (powder > 0) s += "·粉" + powder + "/" + POWDER_TARGET;
                return s;
            }
            return "主世界·准备";
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean hasStructure(AltoClef mod, KnowledgeMemory.StructureType type) {
        try {
            var km = mod.getKnowledgeMemory();
            var p = mod.getPlayer();
            if (km == null || p == null) return false;
            List<?> centers = km.getRememberedCenters(type, p.getPos(), Double.MAX_VALUE);
            return centers != null && !centers.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // ===================== 3~7. 环境 =====================

    /** day [0,13000) ∪ [23000,24000)（黄昏/黎明归入相邻档），night [13000,23000)。 */
    static String timeOfDay(ClientWorld world) {
        try {
            long t = world.getTimeOfDay() % 24000L;
            if (t < 0) t += 24000L;
            return (t >= 13000L && t < 23000L) ? "night" : "day";
        } catch (Throwable t) {
            return "";
        }
    }

    /** clear / rain / thunder。注意 isRaining 是世界级标志（干燥群系也报 rain）。 */
    static String weather(ClientWorld world) {
        try {
            if (world.isThundering()) return "thunder";
            if (world.isRaining()) return "rain";
            return "clear";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 中文粗判环境：地表 / 洞穴 / 水下 / 下界 / 末地。
     * 维度与水淹立即翻面；地表↔洞穴走 {@link SurfaceHelper#isBelowSurfaceDebounced} 的既有迟滞。
     */
    static String environment(AltoClef mod, ClientPlayerEntity p) {
        try {
            Dimension dim = WorldHelper.getCurrentDimension();
            if (dim == Dimension.NETHER) return "下界";
            if (dim == Dimension.END) return "末地";
            if (p.isSubmergedInWater()) return "水下";
            return SurfaceHelper.isBelowSurfaceDebounced(mod) ? "洞穴" : "地表";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 当前生物群系中文短名（未收录退回注册表 path）。 */
    static String biome(ClientWorld world, ClientPlayerEntity p) {
        try {
            var entry = world.getBiome(p.getBlockPos());
            String path = entry.getKey().map(k -> k.getValue().getPath()).orElse(null);
            if (path == null || path.isEmpty()) return "";
            String zh = BIOME_ZH.get(path);
            return zh != null ? zh : path;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 16 格内敌对生物数（无则 0）。 */
    static int nearbyHostiles(ClientWorld world, ClientPlayerEntity p) {
        try {
            Box box = p.getBoundingBox().expand(16.0);
            List<HostileEntity> list = world.getEntitiesByClass(HostileEntity.class, box, Entity::isAlive);
            return list == null ? 0 : list.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 常见生物群系中文短名（1.18.2 注册表 path 口径）。 */
    private static final Map<String, String> BIOME_ZH = new HashMap<>();

    static {
        BIOME_ZH.put("plains", "平原");
        BIOME_ZH.put("sunflower_plains", "向日葵平原");
        BIOME_ZH.put("forest", "森林");
        BIOME_ZH.put("flower_forest", "繁花森林");
        BIOME_ZH.put("birch_forest", "桦木森林");
        BIOME_ZH.put("dark_forest", "黑森林");
        BIOME_ZH.put("taiga", "针叶林");
        BIOME_ZH.put("snowy_taiga", "积雪针叶林");
        BIOME_ZH.put("old_growth_pine_taiga", "原始松林");
        BIOME_ZH.put("old_growth_spruce_taiga", "原始云杉林");
        BIOME_ZH.put("snowy_plains", "雪原");
        BIOME_ZH.put("ice_spikes", "冰刺之地");
        BIOME_ZH.put("desert", "沙漠");
        BIOME_ZH.put("savanna", "热带草原");
        BIOME_ZH.put("savanna_plateau", "热带高原");
        BIOME_ZH.put("jungle", "丛林");
        BIOME_ZH.put("bamboo_jungle", "竹林");
        BIOME_ZH.put("swamp", "沼泽");
        BIOME_ZH.put("mangrove_swamp", "红树林沼泽");
        BIOME_ZH.put("badlands", "恶地");
        BIOME_ZH.put("eroded_badlands", "风蚀恶地");
        BIOME_ZH.put("wooded_badlands", "疏林恶地");
        BIOME_ZH.put("mushroom_fields", "蘑菇岛");
        BIOME_ZH.put("ocean", "海洋");
        BIOME_ZH.put("deep_ocean", "深海");
        BIOME_ZH.put("warm_ocean", "温暖海洋");
        BIOME_ZH.put("lukewarm_ocean", "温水海洋");
        BIOME_ZH.put("deep_lukewarm_ocean", "深温水海洋");
        BIOME_ZH.put("cold_ocean", "冷水海洋");
        BIOME_ZH.put("deep_cold_ocean", "深冷水海洋");
        BIOME_ZH.put("frozen_ocean", "冰冻海洋");
        BIOME_ZH.put("deep_frozen_ocean", "深冻海洋");
        BIOME_ZH.put("river", "河流");
        BIOME_ZH.put("frozen_river", "冻河");
        BIOME_ZH.put("beach", "沙滩");
        BIOME_ZH.put("snowy_beach", "积雪沙滩");
        BIOME_ZH.put("stony_shore", "石岸");
        BIOME_ZH.put("mountains", "山地");
        BIOME_ZH.put("gravelly_mountains", "沙砾山地");
        BIOME_ZH.put("wooded_mountains", "林地山地");
        BIOME_ZH.put("snowy_mountains", "雪山");
        BIOME_ZH.put("stony_peaks", "裸岩山峰");
        BIOME_ZH.put("jagged_peaks", "尖峭山峰");
        BIOME_ZH.put("frozen_peaks", "冰封山峰");
        BIOME_ZH.put("snowy_slopes", "积雪山坡");
        BIOME_ZH.put("meadow", "草甸");
        BIOME_ZH.put("grove", "雪林");
        BIOME_ZH.put("lush_caves", "繁茂洞穴");
        BIOME_ZH.put("dripstone_caves", "溶洞");
        BIOME_ZH.put("nether_wastes", "下界荒地");
        BIOME_ZH.put("soul_sand_valley", "灵魂沙峡谷");
        BIOME_ZH.put("crimson_forest", "绯红森林");
        BIOME_ZH.put("warped_forest", "诡异森林");
        BIOME_ZH.put("basalt_deltas", "玄武岩三角洲");
        BIOME_ZH.put("the_end", "末地");
        BIOME_ZH.put("end_highlands", "末地高地");
        BIOME_ZH.put("end_midlands", "末地内陆");
        BIOME_ZH.put("small_end_islands", "末地小岛");
        BIOME_ZH.put("end_barrens", "末地荒岛");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
