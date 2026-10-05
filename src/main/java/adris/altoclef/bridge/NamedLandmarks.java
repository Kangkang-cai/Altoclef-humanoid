package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.trackers.KnowledgeMemory;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.WorldIdentity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * (2026-10-03 重要位置记忆共享, 桥接接口定稿 mod-bridge-landmark.md §3)
 * 命名地标存储 —— 与 KnowledgeMemory 平级的第二类"重要位置记忆"。
 *
 * <p>为什么与 KnowledgeMemory 分离（定稿 §3, 三条理由）：
 * <ul>
 *   <li><b>防伪造闸门</b>：KnowledgeMemory 的入账口全是"亲眼看见"守卫（LOS 真视线、
 *       刷怪笼验 BlazeEntity、森林验群系）；听来的坐标是"听说"，塞进去等于绕过全部闸门；</li>
 *   <li><b>消费语义</b>：KnowledgeMemory 被任务当结构线索直航/偏置（FortressExploreTask
 *       记忆中心直航、候选区推理），来源混入会改变导航行为；</li>
 *   <li><b>录制纯净</b>：memory_write 事件进录制管道（拟人 bot = 专家数据生成器），
 *       假观察会污染训练数据。</li>
 * </ul>
 *
 * <p>职责边界：
 * <ul>
 *   <li>写入只经 {@code @remember} 命令（bridge /command 白名单），重名覆盖更新，
 *       上限 {@value #MAX_LANDMARKS} 条、满则淘汰最旧（写入永远成功）；</li>
 *   <li>{@code @forget} 只删命名地标 —— KnowledgeMemory 永不可经命令删除（定稿 §7.2）；
 *       AI 起名撞自动中心名（如 remember "下界堡垒"）时，forget 同名只删命名这条；</li>
 *   <li>导出经 /status {@code landmarks} 键：命名地标（source=self/shared:名）+
 *       KnowledgeMemory 自动记忆中心（name=类型中文名, source=self）合计 ≤20 条。
 *       截断规则确定（定稿 §7.1）：命名地标优先，两组各按【离 bot 距离升序】（导航最有用），
 *       同距按名称/坐标字典序稳定 tie-break —— 同一状态重复快照内容不抖动；</li>
 *   <li>无任务消费：AI 用现成 @goto 坐标直达。</li>
 * </ul>
 *
 * <p>持久化：{@code logs/altoclef/memory/landmarks.log}，行格式
 * {@code 存档key\t名称\tdim\tx\ty\tz\tsource\ttsMs}（WorldIdentity.encodeLine，
 * TAB 分隔 —— 0927 空格 key 静默丢行教训的统一口径）。key 只按【存档】隔离
 * （{@code w2:目录名}，不含维度）：条目自带 dim 字段，主世界也能记"下界的传送门"。
 * 换存档清内存不重载磁盘（丢弃语义，同 KnowledgeMemory/WorldStateGuard 拍板）。
 *
 * <p>线程模型：remember/forget（命令派发）与快照构建（5s tick）都在客户端线程，
 * 全部方法单线程访问；emit 回执经 BridgeHttpServer 静态访问器（bridge 未启动时跳过，
 * 游戏内手动敲 @remember 仍可用，只是没有事件）。
 */
public final class NamedLandmarks {

    /** 命名地标上限（定稿 §4.2），满则淘汰最旧（插入序 = LinkedHashMap）。 */
    public static final int MAX_LANDMARKS = 20;
    /** 名称/来源最大字符数（定稿 §4.2：名称 ≤16、不允许空格）。 */
    public static final int MAX_NAME_LEN = 16;

    private static final String FILE_LEGACY = "altoclef_landmarks.log";
    private static final String FILE_NAME = "landmarks.log";

    /** 定稿 §4.1 维度枚举（与 dimFromToken 一致；聊天侧已归一化，模组不认中文）。 */
    private static final Set<String> DIM_TOKENS = Set.of("overworld", "the_nether", "the_end");

    /**
     * 自动记忆中心 → 中文短名（/status landmarks 的 name 字段）。
     * 同类型多中心允许重名（定稿 §4.1），对端渲染始终带坐标区分。
     */
    private static final Map<String, String> AUTO_TYPE_ZH = Map.of(
            "VILLAGE", "村庄",
            "RUINED_PORTAL", "废弃传送门",
            "NETHER_FORTRESS", "下界堡垒",
            "BLAZE_SPAWNER", "烈焰刷怪笼",
            "WARPED_FOREST", "诡异森林",
            "STRONGHOLD", "要塞",
            "END_PORTAL", "末地传送门",
            "LAVA_LAKE", "岩浆湖");

    /** 按存档 key 分桶；LinkedHashMap 插入序 = 最旧在前（淘汰/持久化序一致）。 */
    private static final Map<String, LinkedHashMap<String, Entry>> _bySave = new HashMap<>();
    /** 与 KnowledgeMemory._centersLoaded 同款语义：true 后不再读盘；换存档保持 true（丢弃）。 */
    private static boolean _loaded = false;

    /** 一条命名地标。坐标取整（方块精度，与 KnowledgeMemory 中心同款）。 */
    public static final class Entry {
        public final String name;
        public final int x, y, z;
        public final String dim;   // overworld / the_nether / the_end
        public final String source; // "self" 或 "shared:<名字>"
        public final long tsMs;
        /**
         * (2026-10-05 任务自动消费) 结构类型标记（{@link KnowledgeMemory.StructureType}
         * 名，如 LAVA_LAKE），null = 未标类型。有类型的条目才能被任务当结构线索召回
         * （{@link #recallClueCenters}）；AI 起的任意名（"我的基地"）不标类型、不进导航。
         */
        public final String type;

        Entry(String name, int x, int y, int z, String dim, String source, long tsMs, String type) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.source = source;
            this.tsMs = tsMs;
            this.type = type;
        }
    }

    /** 命令侧结果（事件回执已由本类发出，命令只管 finish）。 */
    public static final class Result {
        public final boolean ok;
        public final String reason;

        private Result(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }
    }

    private NamedLandmarks() {
    }

    // ===================== 写入 / 删除（@remember / @forget） =====================

    /**
     * 写入一条命名地标（重名覆盖更新）。校验失败发 landmark_failed，不抛异常 ——
     * 参数非法走事件回执而非 CommandException，LLM 才能从 /events 拿到失败原因
     * （定稿 §4.3；ActionDispatcher 对 INSTANT 命令的 action_done 只会说"已执行"）。
     *
     * @param dimArg    维度 token（overworld/the_nether/the_end）；空 = 当前维度
     * @param sourceArg 来源（"self" 或分享者名字，落库规范化为 "shared:<名>"）；空 = "self"
     * @param typeArg   结构类型 token（{@link KnowledgeMemory.StructureType} 名，大小写
     *                  不敏感）；空 = 不标类型（不参与任务自动消费）。(2026-10-05) 补的
     *                  可选尾参 —— 对端转发 {@code memory_shared} 时带上真实类型，
     *                  接收侧才有"当什么结构线索用"的依据。
     */
    public static Result remember(AltoClef mod, String name, double x, double y, double z,
                                  String dimArg, String sourceArg, String typeArg) {
        String err = validateName(name);
        if (err == null) err = validateToken(sourceArg, "来源");
        if (err == null) err = validateCoord(x, y, z);
        String type = null;
        if (err == null && typeArg != null && !typeArg.trim().isEmpty()) {
            KnowledgeMemory.StructureType st = typeFromToken(typeArg);
            if (st == null) {
                err = "认不出结构类型 —— " + typeArg.trim() + "（可选：" + allTypeTokens() + "）";
            } else {
                type = st.name();
            }
        }
        String dim = null;
        if (err == null) {
            dim = (dimArg == null || dimArg.trim().isEmpty())
                    ? currentDimToken() : dimArg.trim().toLowerCase(Locale.ROOT);
            if (!DIM_TOKENS.contains(dim)) err = "维度只认 overworld/the_nether/the_end";
        }
        if (err != null) {
            emit(false, "没记下「" + safe(name) + "」：" + err,
                    detailJson(name, null, 0, 0, dim, "failed", err));
            return new Result(false, err);
        }
        String source = (sourceArg == null || sourceArg.trim().isEmpty())
                ? "self" : "shared:" + sourceArg.trim();
        // (2026-10-05 记录侧否决口) 对端编排层把别人的 memory_shared 自动转成
        // 本 bot 的 @remember(source=shared:名) = "默认记"；AI 角色不想记就
        // @autorecord off，共享来源的写入在此被拒（AI 手动 @forget 仍是事后兜底）。
        if (source.startsWith("shared:") && !_autoRecordShared) {
            String reason = "自动记录共享坐标已关闭（@autorecord off），要记请让 AI 打开或手动改设置";
            emit(false, "没记下「" + safe(name) + "」：" + reason,
                    detailJson(name, null, 0, 0, dim, "failed", reason));
            return new Result(false, reason);
        }
        int ix = (int) Math.round(x), iy = (int) Math.round(y), iz = (int) Math.round(z);
        ensureLoaded();
        LinkedHashMap<String, Entry> set = _bySave.computeIfAbsent(worldKey(), k -> new LinkedHashMap<>());
        boolean overwrite = set.containsKey(name);
        set.remove(name);
        if (set.size() >= MAX_LANDMARKS) {
            set.remove(firstKey(set)); // 满则淘汰最旧（写入永远成功, 定稿 §4.2）
        }
        set.put(name, new Entry(name, ix, iy, iz, dim, source, System.currentTimeMillis(), type));
        rewriteFile();
        Debug.logMessage("[Landmarks] " + (overwrite ? "updated" : "remembered") + " \""
                + name + "\" at " + ix + "," + iy + "," + iz + " (" + dim + ", " + source
                + (type == null ? "" : ", " + type) + ")");
        String text = (overwrite ? "更新了重要位置「" : "记下了重要位置「") + safe(name) + "」("
                + ix + "," + iy + "," + iz + "," + dim + ")";
        emit(true, text, detailJson(name, ix, iy, iz, dim, "ok", null));
        return new Result(true, null);
    }

    /**
     * 删除一条命名地标。只作用于本存储 —— KnowledgeMemory 永不可经命令删除（定稿 §7.2）。
     * 名称不存在 → landmark_failed("没有这个位置")。
     */
    public static Result forget(String name) {
        String err = validateName(name);
        if (err != null) {
            emit(false, "没忘掉「" + safe(name) + "」：" + err,
                    detailJson(name, null, 0, 0, null, "failed", err));
            return new Result(false, err);
        }
        ensureLoaded();
        LinkedHashMap<String, Entry> set = _bySave.get(worldKey());
        Entry removed = set == null ? null : set.remove(name);
        if (removed == null) {
            String reason = "没有这个位置";
            emit(false, "没忘掉「" + safe(name) + "」：" + reason,
                    detailJson(name, null, 0, 0, null, "failed", reason));
            return new Result(false, reason);
        }
        rewriteFile();
        Debug.logMessage("[Landmarks] forgot \"" + name + "\"");
        emit(true, "忘掉了重要位置「" + safe(name) + "」",
                detailJson(name, removed.x, removed.y, removed.z, removed.dim, "ok", null));
        return new Result(true, null);
    }

    /** 换存档清内存（WorldStateGuard 调用；保持 _loaded=true 阻止磁盘重载，丢弃语义）。 */
    public static void onWorldBoundary() {
        _bySave.clear();
    }

    // ===================== /status 导出 =====================

    /**
     * /status landmarks 键的 JSON 数组片段；无任何内容返回 null（整键省略）。
     * 截断规则确定（定稿 §7.1）：命名地标优先，命名/自动两组各按离 bot 距离升序，
     * 同距按名称（命名）或 类型+坐标（自动）字典序 tie-break —— 规则确定不抖动。
     *
     * @param botPos bot 当前位置（距离排序用）
     * @param mod    取 KnowledgeMemory 自动中心（dumpMemoryForRecord：本存档本维度双源合并）
     */
    static String snapshotJsonOrNull(Vec3d botPos, AltoClef mod) {
        try {
            ensureLoaded();
            LinkedHashMap<String, Entry> named = _bySave.get(worldKey());
            List<Entry> namedList = named == null || named.isEmpty()
                    ? Collections.emptyList() : new ArrayList<>(named.values());
            namedList.sort((a, b) -> {
                int c = Double.compare(distSq(a, botPos), distSq(b, botPos));
                return c != 0 ? c : a.name.compareTo(b.name);
            });

            List<String> parts = new ArrayList<>();
            for (Entry e : namedList) {
                if (parts.size() >= MAX_LANDMARKS) break;
                parts.add(entryJson(e.name, e.x, e.y, e.z, e.dim, e.source));
            }
            // 自动中心补位（KnowledgeMemory 中心在本存档本维度内, dim 恒为当前维度）
            if (mod != null && parts.size() < MAX_LANDMARKS) {
                Map<String, List<BlockPos>> auto = mod.getKnowledgeMemory().dumpMemoryForRecord();
                List<String> autoKeys = new ArrayList<>(auto.keySet());
                Collections.sort(autoKeys);
                for (String key : autoKeys) {
                    String zh = AUTO_TYPE_ZH.get(key.startsWith("center:")
                            ? key.substring("center:".length()) : key);
                    if (zh == null) continue;
                    List<BlockPos> centers = new ArrayList<>(auto.get(key));
                    centers.sort((a, b) -> {
                        int c = Double.compare(a.getSquaredDistance(botPos), b.getSquaredDistance(botPos));
                        return c != 0 ? c : comparePos(a, b);
                    });
                    for (BlockPos p : centers) {
                        if (parts.size() >= MAX_LANDMARKS) break;
                        parts.add(entryJson(zh, p.getX(), p.getY(), p.getZ(),
                                currentDimToken(), "self"));
                    }
                    if (parts.size() >= MAX_LANDMARKS) break;
                }
            }
            return parts.isEmpty() ? null : "[" + String.join(",", parts) + "]";
        } catch (Throwable t) {
            Debug.logWarning("[Landmarks] snapshot export failed: " + t);
            return null;
        }
    }

    // ===================== 内部实现 =====================

    // ---- (2026-10-05 被动式记忆分享: 分享/记录策略静态单一源) ----
    // 口径 = "默认做, AI 只否决": bot 自动入账的新记忆默认推送 memory_shared 给
    // 对端; 对端编排层默认把收到的共享坐标自动转 @remember 落库。AI 角色的两个
    // 否决口: @share off <type|all>（事前不推）/ @autorecord off（事前不记）+
    // @forget / @forgetmem（事后删除）。与 DemandQuota/SleepOverride 同款静态形态
    // （BeatMinecraft2Task 等会被反复 new, 实例字段会丢）。
    /** 被 AI 关闭分享的结构类型（token 大写）; _shareAllDenied = 全部不推。 */
    private static final Set<String> _shareDeniedTypes = new HashSet<>();
    private static boolean _shareAllDenied = false;
    /** 共享来源（shared:名）的坐标是否自动落库。默认 true = 默认记。 */
    private static boolean _autoRecordShared = true;

    /** 该类型的新记忆是否允许推送 memory_shared（@share off 关闭）。 */
    public static boolean isTypeShared(KnowledgeMemory.StructureType type) {
        return !_shareAllDenied && !_shareDeniedTypes.contains(type.name());
    }

    /** @share 命令入口：{@code typeToken} = 类型 token 或 all；denied = true 关闭/false 打开。 */
    public static String setShareDenied(String typeToken, boolean denied) {
        if ("all".equalsIgnoreCase(typeToken) || "*".equals(typeToken)) {
            _shareAllDenied = denied;
            if (denied) _shareDeniedTypes.clear();
        } else {
            KnowledgeMemory.StructureType st = typeFromToken(typeToken);
            if (st == null) return null; // 命令侧报"认不出类型"
            if (denied) _shareDeniedTypes.add(st.name());
            else _shareDeniedTypes.remove(st.name());
        }
        return sharePolicySummary();
    }

    public static String sharePolicySummary() {
        if (_shareAllDenied) return "分享=全关（@share off all）";
        if (_shareDeniedTypes.isEmpty()) return "分享=全开（默认）";
        return "分享=全开，但已关闭: " + String.join(",", _shareDeniedTypes);
    }

    /** @autorecord 命令入口。返回设置后的摘要文案。 */
    public static String setAutoRecordShared(boolean enabled) {
        _autoRecordShared = enabled;
        return enabled ? "自动记录共享坐标=开（默认）" : "自动记录共享坐标=关（@autorecord off）";
    }

    public static boolean isAutoRecordShared() {
        return _autoRecordShared;
    }

    // ---- (2026-10-05) 类型 token 工具 ----

    /** token（大小写不敏感）→ StructureType；认不出返回 null。 */
    public static KnowledgeMemory.StructureType typeFromToken(String token) {
        if (token == null) return null;
        String t = token.trim().toUpperCase(Locale.ROOT);
        for (KnowledgeMemory.StructureType st : KnowledgeMemory.StructureType.values()) {
            if (st.name().equals(t)) return st;
        }
        return null;
    }

    /** 全部类型 token（小写 `|` 分隔），供命令报错提示。 */
    public static String allTypeTokens() {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeMemory.StructureType t : KnowledgeMemory.StructureType.values()) {
            if (sb.length() > 0) sb.append('|');
            sb.append(t.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    // ---- (2026-10-05) 记忆分享推送（被动式分享的出口） ----

    /**
     * KnowledgeMemory 新记忆入账后的推送钩子：策略允许时 emit {@code memory_shared}
     * 事件（text 面向 LLM，detail 面向编排层解析转发）。只推新入账（remember 的
     * 合并早退保证不会重复推）；bridge 未启动时静默跳过（同 landmark 回执口径）。
     * 调用方 = KnowledgeMemory.remember（客户端 tick 线程, 与本类其余写入同线程）。
     */
    public static void emitMemorySharedIfAllowed(KnowledgeMemory.StructureType type, BlockPos pos) {
        try {
            if (!isTypeShared(type)) return;
            String zh = zhName(type);
            String dim = currentDimToken();
            String text = "发现了" + zh + " (" + pos.getX() + "," + pos.getY() + ","
                    + pos.getZ() + "," + dim + ")";
            StringBuilder d = new StringBuilder(96);
            d.append('{');
            BridgeJson.str(d, "type");
            d.append(':');
            BridgeJson.str(d, type.name());
            d.append(",\"pos\":[").append(pos.getX()).append(',')
                    .append(pos.getY()).append(',').append(pos.getZ()).append(']');
            d.append(',');
            BridgeJson.str(d, "dim");
            d.append(':');
            BridgeJson.str(d, dim);
            d.append('}');
            EventBuffer b = BridgeHttpServer.buffer();
            if (b != null) b.emitImmediate("memory_shared", text, false, d.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 类型中文短名（AUTO_TYPE_ZH 消费侧只读出口；未知类型回退枚举名）。 */
    public static String zhName(KnowledgeMemory.StructureType type) {
        String zh = AUTO_TYPE_ZH.get(type.name());
        return zh != null ? zh : type.name();
    }

    // ---- (2026-10-05) 任务自动消费：双源线索召回 ----

    /**
     * 结构线索中心召回 = KnowledgeMemory 自动记忆中心（亲眼见, 证据强）
     * ∪ NamedLandmarks 带类型标记的条目（听说/命名, 证据弱）。
     *
     * <p><b>为什么是独立 API 而不是改 getRememberedCenters</b>（1003 §8 教训, 证据
     * 强度不同的输入不得混进同一判定源）：眼闸旁路（recallEndPortalFromKnowledge）、
     * MobDefenseChain 传送门锚点、getCandidateAreas/isNearRememberedStructure 推理、
     * dumpMemoryForRecord（录制纯净）等消费点必须<b>继续只吃亲眼记忆</b> —— 它们
     * 的判据前提是"这一条是观察"。本 API 只供"线索型"消费点（到不了就换目标/
     * 到场有验证守卫）：FortressExploreTask（查讫+冷却）、CollectBucketLiquidTask
     * （锚点拉黑+|dy| 闸）、CollectBlazeRodsTask（到场验 BlazeEntity）、
     * BeatMinecraft2Task 诡异森林（群系校验+失信拉黑）。听说的坐标最坏结果 =
     * 白跑一趟被守卫拉黑；到了现场亲眼看见后由既有感知管道自动晋升进
     * KnowledgeMemory, 不需要新的晋升代码。
     *
     * <p>维度过滤：命名地标按存档落库、条目自带 dim, 只取当前维度的; 自动记忆
     * 中心天然按 存档:维度 隔离。去重：同一坐标两源都有时只留一条（晋升后常见）。
     * 任何异常吞掉返回已收集部分 —— 导航消费点不允许因导出问题抛错。
     */
    public static List<BlockPos> recallClueCenters(AltoClef mod, KnowledgeMemory.StructureType type,
                                                   Vec3d fromPos, double maxDistance) {
        List<BlockPos> out = new ArrayList<>();
        try {
            if (mod.getKnowledgeMemory() != null) {
                out.addAll(mod.getKnowledgeMemory().getRememberedCenters(type, fromPos, maxDistance));
            }
        } catch (Throwable ignored) {
        }
        try {
            ensureLoaded();
            LinkedHashMap<String, Entry> set = _bySave.get(worldKey());
            if (set != null) {
                String curDim = currentDimToken();
                double maxSq = maxDistance * maxDistance;
                for (Entry e : set.values()) {
                    if (!type.name().equals(e.type)) continue;
                    if (!curDim.equals(e.dim)) continue;
                    BlockPos p = new BlockPos(e.x, e.y, e.z);
                    if (p.getSquaredDistance(fromPos) > maxSq) continue;
                    if (!out.contains(p)) out.add(p);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String worldKey() {
        // 只按存档隔离（不含维度）：条目自带 dim, 跨维度记录合法（定稿 §3）。
        return "w2:" + WorldIdentity.saveDirName();
    }

    private static Path file() {
        return Debug.memoryFile(FILE_LEGACY, FILE_NAME);
    }

    private static void ensureLoaded() {
        if (_loaded) return;
        _loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            int count = 0;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                // 新格式 [key, name, dim, x, y, z, source, tsMs, type] —— key 后 8 字段;
                // 旧格式 (2026-10-05 前无 type) key 后 7 字段, 向后兼容回退解析。
                String[] parts = WorldIdentity.decodeLine(line, 8);
                String type = null;
                if (parts != null) {
                    type = parts[8].isEmpty() ? null : parts[8];
                } else {
                    parts = WorldIdentity.decodeLine(line, 7);
                    if (parts == null) continue;
                }
                try {
                    LinkedHashMap<String, Entry> set =
                            _bySave.computeIfAbsent(parts[0], k -> new LinkedHashMap<>());
                    if (!set.containsKey(parts[1]) && set.size() >= MAX_LANDMARKS) continue;
                    // 同名行后者覆盖（历史 append 时代可能留重复行, last-wins）
                    set.put(parts[1], new Entry(parts[1],
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                            Integer.parseInt(parts[5]), parts[2], parts[6],
                            Long.parseLong(parts[7]), type));
                    count++;
                } catch (NumberFormatException ignored) {
                }
            }
            if (count > 0) {
                Debug.logMessage("[Landmarks] loaded " + count + " named landmarks from " + FILE_NAME);
            }
        } catch (Exception e) {
            Debug.logMessage("[Landmarks] failed to load " + FILE_NAME + ": " + e.getMessage());
        }
    }

    /**
     * 全量重写落盘（remember 覆盖/淘汰与 forget 都会破坏 append 语义, 重写最省心；
     * 文件极小——每存档 ≤20 行）。文件含所有存档的条目, 与 KnowledgeMemory 同目录。
     */
    private static void rewriteFile() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, LinkedHashMap<String, Entry>> bySave : _bySave.entrySet()) {
                for (Entry e : bySave.getValue().values()) {
                    sb.append(WorldIdentity.encodeLine(bySave.getKey(), e.name, e.dim,
                            e.x, e.y, e.z, e.source, e.tsMs,
                            e.type == null ? "" : e.type));
                }
            }
            Files.write(file(), sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            // 落盘失败不影响本会话内的记忆（同 persistCenter 口径）。
            Debug.logWarning("[Landmarks] persist failed: " + e);
        }
    }

    // ---- 校验 ----

    private static String validateName(String name) {
        return validateToken(name, "名称");
    }

    private static String validateToken(String token, String label) {
        if (token == null || token.trim().isEmpty()) return label + "不能为空";
        if (token.length() > MAX_NAME_LEN) return label + "超长（≤" + MAX_NAME_LEN + " 字符）";
        if (token.contains(" ") || token.contains("\t")) return label + "不能含空格";
        return null;
    }

    private static String validateCoord(double x, double y, double z) {
        if (Math.abs(x) > 3.0e7 || Math.abs(z) > 3.0e7 || y < -2032 || y > 2032) {
            return "坐标超出合理范围";
        }
        return null;
    }

    private static String currentDimToken() {
        try {
            Dimension d = WorldHelper.getCurrentDimension();
            // 定稿 §4.1 枚举口径（SummaryGenerator.dimToken 是 nether/end, 不通用）
            return d == Dimension.NETHER ? "the_nether" : d == Dimension.END ? "the_end" : "overworld";
        } catch (Throwable t) {
            return "overworld";
        }
    }

    // ---- 回执事件（emitImmediate, 同 action_done 通道; 定稿 §4.3）----

    private static void emit(boolean ok, String text, String detail) {
        try {
            EventBuffer b = BridgeHttpServer.buffer();
            if (b != null) {
                b.emitImmediate(ok ? "landmark_saved" : "landmark_failed", text, false, detail);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String detailJson(String name, Integer x, Integer y, Integer z,
                                     String dim, String status, String reason) {
        StringBuilder sb = new StringBuilder(96);
        sb.append('{');
        BridgeJson.str(sb, "name");
        sb.append(':');
        BridgeJson.str(sb, safe(name));
        if (x != null) {
            sb.append(',');
            BridgeJson.str(sb, "pos");
            sb.append(":[").append(x).append(',').append(y).append(',').append(z).append(']');
        }
        if (dim != null && !dim.isEmpty()) {
            sb.append(',');
            BridgeJson.str(sb, "dim");
            sb.append(':');
            BridgeJson.str(sb, dim);
        }
        sb.append(',');
        BridgeJson.str(sb, "status");
        sb.append(':');
        BridgeJson.str(sb, status);
        if (reason != null && !reason.isEmpty()) {
            sb.append(',');
            BridgeJson.str(sb, "reason");
            sb.append(':');
            BridgeJson.str(sb, reason);
        }
        sb.append('}');
        return sb.toString();
    }

    private static String entryJson(String name, int x, int y, int z, String dim, String source) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        BridgeJson.str(sb, "name");
        sb.append(':');
        BridgeJson.str(sb, name);
        sb.append(",\"x\":").append(x).append(",\"y\":").append(y).append(",\"z\":").append(z);
        sb.append(',');
        BridgeJson.str(sb, "dim");
        sb.append(':');
        BridgeJson.str(sb, dim);
        sb.append(',');
        BridgeJson.str(sb, "source");
        sb.append(':');
        BridgeJson.str(sb, source);
        sb.append('}');
        return sb.toString();
    }

    // ---- 小工具 ----

    private static double distSq(Entry e, Vec3d p) {
        double dx = e.x - p.x, dy = e.y - p.y, dz = e.z - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int comparePos(BlockPos a, BlockPos b) {
        int c = Integer.compare(a.getX(), b.getX());
        if (c != 0) return c;
        c = Integer.compare(a.getY(), b.getY());
        if (c != 0) return c;
        return Integer.compare(a.getZ(), b.getZ());
    }

    private static String firstKey(LinkedHashMap<String, Entry> set) {
        for (String k : set.keySet()) return k;
        return null;
    }

    /** 文本/JSON 里的名称兜底（null 安全, 防把 "null" 字面量当名称）。 */
    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
