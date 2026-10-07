package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.speedrun.BeatMinecraftConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.trackers.KnowledgeMemory;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
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

    /**
     * 当前实际在干的子任务中文短名（取任务链最深处）。允许高频变化，注入侧不进变化键。
     *
     * <p>(1005 目的子串) 深层标签只有"动作+物品"（"收集金子"），AI 角色不知道目的
     * （"为换末影珍珠"）—— 目的在任务栈上层。拼为 {@code 深层标签 + "，为" + 目的}，
     * 目的推导见 {@link #purposeFor}；无目的或推导异常时退回纯深层标签。
     */
    static String taskDetail(AltoClef mod) {
        try {
            var runner = mod.getTaskRunner();
            TaskChain chain = runner == null ? null : runner.getCurrentTaskChain();
            if (chain == null) return "";
            List<Task> stack = chain.getTasks();
            if (stack == null || stack.isEmpty()) return "";
            // addTaskToChain 是"父→子"顺序（Task.java:106），最深处 = 末元素
            Task deep = stack.get(stack.size() - 1);
            String base;
            // (2026-10-04) FortressExploreTask 两种模式动作完全不同: 外围=找路,
            // 走廊=已在堡垒里巡逻 —— 统一译"探堡垒"会让对端把"正在找"理解成"在里面探索"。
            if (deep instanceof adris.altoclef.tasks.movement.FortressExploreTask ft) {
                base = ft.isPiglinVariant() ? "找猪灵"
                        : (ft.isCorridorMode() ? "巡堡垒走廊" : "找堡垒");
            } else {
                String verb = verbFor(deep);
                // ResourceTask 系能拿到目标物 → "合成钻石镐" / "挖钻石" 这类具体描述
                String item = deep instanceof ResourceTask rt ? firstName(rt) : "";
                if (!item.isEmpty()) {
                    base = verb + item;
                } else {
                    String mapped = CLASS_ZH.get(deep.getClass().getSimpleName());
                    if (mapped != null) {
                        base = mapped;
                    } else {
                        String state = deep.debugState();
                        base = (state != null && !state.isBlank())
                                ? truncate(state, 24)
                                : truncate(deep.getClass().getSimpleName(), 24);
                    }
                }
            }
            String purpose = purposeFor(stack, deep);
            return truncate(purpose.isEmpty() ? base : base + "，为" + purpose, 32);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 目的标签：沿任务栈<b>从顶（最外层）向下</b>找"为什么做当前动作"
     * （1005 用户需求实锤：AI 角色知道"在收金子"，不知道"为了什么"——标签只报
     * 动作+物品，目的在上层）。
     *
     * <p>方向必须是外层优先（1005 20:1x 用户纠正"金子是为了和猪灵交易获得防火药水，
     * 防火药水为了打烈焰人"）：TradeWithPiglinsTask 是被多个父任务复用的<b>机制</b>，
     * 不是目的——CollectFireResPotionsTask 内层的交易子任务目标甚至是
     * {@code DRAGON_EGG×999999} 永不满足占位（CollectFireResPotionsTask.java:59），
     * 深层优先会撞上它误报"换龙蛋"。只有最外层的显式目的任务才是真目的：
     * 药水阶段栈 [BeatMinecraft2, CollectFireResPotionsTask, TradeWithPiglins,
     * CollectGoldIngotTask, …] 顶向下先命中 CollectFireResPotionsTask → "备防火药水打烈焰人"；
     * 珍珠阶段栈顶直接是 TradeWithPiglinsTask(ENDER_PEARL) → "换末影珍珠"，两相正确。
     * <ol>
     *   <li><b>显式目的任务</b>（{@link #PURPOSE_ZH} / TradeWithPiglinsTask 按交易
     *       目标物动态译"换X"）；</li>
     *   <li><b>通用兜底</b>：第一个目标物与深层任务<b>不同</b>的祖先 {@link ResourceTask}
     *       （"挖金矿"的祖先是"合成金靴子"）。目标物相同 = 同物递进（MineAndCollectTask
     *       → CollectGoldIngotTask），是机制不是目的，跳过；类名含 Catalogued 的
     *       备料机制任务同样跳过。</li>
     * </ol>
     * BeatMinecraft2Task 不是 ResourceTask 也不进静态表 —— "为通关"粒度太粗，宁可不给。
     */
    private static String purposeFor(List<Task> stack, Task deep) {
        try {
            // 第一遍：显式目的任务（外层优先，见方法注释）
            for (int i = 0; i <= stack.size() - 2; i++) {
                Task a = stack.get(i);
                if (a instanceof TradeWithPiglinsTask tw) {
                    Item[] m = ItemTarget.getMatches(tw.getItemTargets());
                    if (m != null && m.length > 0) {
                        String zh = firstNameItem(m[0]);
                        if (!zh.isEmpty()) return "换" + zh;
                    }
                    return "与猪灵交易";
                }
                String mapped = PURPOSE_ZH.get(a.getClass().getSimpleName());
                if (mapped != null) return mapped;
            }
            // 第二遍：通用兜底 —— 同样外层优先
            String deepItem = deep instanceof ResourceTask rt ? firstName(rt) : "";
            for (int i = 0; i <= stack.size() - 2; i++) {
                Task a = stack.get(i);
                if (!(a instanceof ResourceTask art)) continue;
                if (a.getClass().getSimpleName().contains("Catalogued")) continue; // 备料机制≠目的
                String n = firstName(art);
                if (n.isEmpty() || n.equals(deepItem)) continue; // 同物递进≠目的
                return verbFor(a) + n;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** ResourceTask 的第一个目标物中文名（无目标物/译名缺失返回空串）。 */
    private static String firstName(ResourceTask rt) {
        try {
            Item[] m = ItemTarget.getMatches(rt.getItemTargets());
            return (m == null || m.length == 0) ? "" : firstNameItem(m[0]);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String firstNameItem(Item item) {
        String zh = ItemNames.name(new ItemStack(item));
        return zh == null ? "" : zh;
    }

    /**
     * 显式目的任务表（1005）：这些任务类本身<b>就是</b>下层收集动作的目的。
     * TradeWithPiglinsTask 不在表里 —— 它的目标物在参数里（换珍珠/换黑曜石/换龙蛋），
     * 由 {@link #purposeFor} 按 instanceof 动态写"换X"；它同时也是被多个父任务
     * 复用的机制（CollectFireResPotionsTask 内层的交易目标是 DRAGON_EGG×999999
     * 永不满足占位），所以扫描必须外层优先，机制任务先被外层真目的截住。
     */
    private static final Map<String, String> PURPOSE_ZH = new HashMap<>();

    static {
        PURPOSE_ZH.put("CollectObsidianByBarterTask", "换黑曜石"); // 内部就是 TradeWithPiglinsTask(OBSIDIAN)
        PURPOSE_ZH.put("CollectObsidianTask", "备黑曜石");
        // (1005 20:1x 用户纠正) 金子→猪灵交易→防火药水→打烈焰人：药水是手段，
        // 打烈焰人才是目的，写成完整链
        PURPOSE_ZH.put("CollectFireResPotionsTask", "备防火药水打烈焰人");
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
        CLASS_ZH.put("FollowAndWorkTask", "跟随+顺手干活");
        CLASS_ZH.put("SubBotBootstrapTask", "次bot引导");
        CLASS_ZH.put("EatItemTask", "吃东西");
        CLASS_ZH.put("IdleTask", "待命");
        CLASS_ZH.put("ExploreCaveTask", "探洞");
        CLASS_ZH.put("ExploreStrongholdTask", "找要塞");
        CLASS_ZH.put("FortressExploreTask", "找堡垒"); // 兜底（正常已被上方 instanceof 分支按模式细分）
        CLASS_ZH.put("OverworldExpeditionTask", "觅食远征");
        CLASS_ZH.put("WarpedForestExpeditionTask", "找诡异森林");
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

    // ===================== 3b. 玩家（多人协调用，2026-10-03） =====================

    /**
     * 服务器在线玩家名（tab 列表），逗号分隔；无则空串（/status 整键省略）。
     * 供"多人一起睡""会合"这类同步型动作判断"还有谁在线"。
     */
    static String onlinePlayers(AltoClef mod) {
        try {
            var names = mod.getEntityTracker().getServerPlayerNames();
            return (names == null || names.isEmpty()) ? "" : String.join(",", names);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 正在睡觉的玩家名，逗号分隔；无人睡则空串。
     * ⚠ 只统计<b>已加载实体</b>的玩家 —— 追踪范围外（第三方服默认 48 格）拿不到姿态，
     * 所以这是"可见范围内谁在睡"，不是服务器全量的睡况。
     */
    static String sleepingPlayers(AltoClef mod) {
        try {
            var et = mod.getEntityTracker();
            var names = et.getServerPlayerNames();
            if (names == null || names.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String n : names) {
                if (!et.isPlayerLoaded(n)) continue;
                boolean sleeping;
                try {
                    sleeping = et.getPlayerEntity(n).map(p -> p.isSleeping()).orElse(false);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!sleeping) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(n);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * (2026-10-07) 完整背包的<b>原名</b>清单，同名合并计数（{@code "oak_log x12, iron_ingot x3"}）。
     *
     * <p>为什么必须有它：{@code keyItems} 是<b>中文</b>名（{@link ItemNames}，供对端做变化检测），
     * 而 {@code @give <玩家> <物品>} 认的是 {@code blaze_rod} 这类 ID —— 对端拿着"烈焰棒"没法直接用。
     * 本字段与 {@code @give} 的背包匹配、{@code @inventory} 命令<b>同一来源</b>
     * （{@code ItemHelper.stripItemName}）⇒ 对端从常规轮询的 /status 里<b>照抄</b>即可发 @give，
     * 不需要再发一条 {@code @inventory}，也不需要凭记忆写 ID。
     *
     * <p>由快照（客户端线程、5s 一次）调用，线程安全；失败返回空串（/status 整键省略）。
     */
    static String inventory(AltoClef mod) {
        try {
            if (mod.getPlayer() == null) return "";
            var inv = mod.getPlayer().getInventory();
            java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (int i = 0; i < inv.size(); ++i) {
                ItemStack st = inv.getStack(i);
                if (st == null || st.isEmpty()) continue;
                counts.merge(ItemHelper.stripItemName(st.getItem()), st.getCount(), Integer::sum);
            }
            StringBuilder sb = new StringBuilder();
            for (var e : counts.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey()).append(" x").append(e.getValue());
            }
            return sb.toString();
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
