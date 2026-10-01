package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

/**
 * Make sure we have a tool at or above a mining level.
 */
public class SatisfyMiningRequirementTask extends Task {

    private final MiningRequirement _requirement;
    /** 谁派发了本任务 (诊断用, 见 onStart 注释)。 */
    private final String _src;

    // ==================== (2026-09-25 造镐链活跃标志, 挂死修复) ====================
    // 现场 20260925-103444 (实例冻结在 10:48:49.008, 无 crash report): 挖隧道遇
    // 红石矿挡路 → DestroyBlockTask [ToolGate] 派本任务(IRON) → 铁镐链 → iron_ingot
    // → raw_iron MineAndCollectTask → 附近感知不到铁矿 → 退回挖隧道 → 又遇红石 →
    // [ToolGate] 再派本任务 → 【跨资源逻辑环】。diag 实锤 cycle-guard fresh
    // iron_ingot/raw_iron (instance on tick stack) = 环已在 tick 栈上, 同毫秒任务
    // 风暴后渲染线程死在无深度保护的链遍历 (同 19:15:20 教训: 保险丝防崩不防挂死)。
    // 修: 本任务存活期间(镐链正在造) DestroyBlockTask.ToolGate 全局抑制 —— 第一层
    // ToolGate 正常触发造镐, 链内一切挖块不再展开新工具链, 环断; 链内遇高档块退回
    // 旧行为(现有工具硬挖/绕行), 镐到手 isFinished 链结束 → 计数归零 → ToolGate
    // 自然恢复。消费方: DestroyBlockTask.toolRequirementMissing 顶部。
    private static int _activeToolChains = 0;

    /** 当前是否有镐合成链在跑 (含其材料子链)。 */
    public static boolean isToolChainActive() {
        return _activeToolChains > 0;
    }

    // (2026-09-15 承诺锁) 一旦本任务选中上档镐(钻石/铁), 存活期间锁定该选择,
    // 不再每 tick 重估档位 — 修 21:30 场次: 选铁镐 → 生铁塞进熔炉+关炉门 →
    // shouldPreferIronPickaxe 的"可达口径"计数骤降(<3) → 下一 tick 翻回石镐
    // 分支, 石镐链抢断铁镐链, 熔炉里 3 个生铁无人认领 (skip stone tier 被自己
    // 打脸)。故意不扩计数口径(炉内物品) — 项目铁律: 不新增计数源, 避开
    // "缓存 vs 直读"不一致一族。逃生窗: 锁定超过 COMMIT_ESCAPE_TICKS 仍没拿到
    // 镐 → 视为铁镐链死锁(炉不可达/没燃料等), 释放重估, 允许降级石镐;
    // 材料若回到背包, shouldPrefer* 条件自然重新成立, 不依赖逃生窗。
    private Item _committedPickaxe = null;
    private int _commitTicks = 0;
    private static final int COMMIT_ESCAPE_TICKS = 20 * 120; // 120s: 3 锭顺序烤 30s + 走位/收集/合成, 余量充足

    /** 进入承诺锁 (commit)。只在 WOOD/STONE 档的上档分支调用。 */
    private void commitPickaxe(Item pickaxe) {
        _committedPickaxe = pickaxe;
        _commitTicks = 0;
        Debug.logMessage("(SatisfyMiningReq) 承诺锁: 已选定 " + pickaxe + ", 存活期间不再回退石/木镐档 (材料瞬时不可见不算数)");
    }

    public SatisfyMiningRequirementTask(MiningRequirement requirement) {
        this(requirement, "unlabeled");
    }

    /**
     * @param src 触发来源标记 (固定短字符串, 如 "mine-and-collect")。用途: 本任务
     *            有 7 个派出点, 而 onStart 一次性快照要能自证"是谁在什么场景下要镐"。
     */
    public SatisfyMiningRequirementTask(MiningRequirement requirement, String src) {
        _requirement = requirement;
        _src = src;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // (2026-09-25 挂死修复) 链活跃标志: onStart/onStop 由框架保证配对
        // (tickInternal _first 时 onStart, stop/interrupt 均调 onStop), 钳制防漏减。
        _activeToolChains++;
        // (2026-08-23 诊断) 需要镐却触发本任务 → 记录当前背包镐状态, 排查
        // "背包有铁/钻石镐却还要重做镐" (用户反馈: 它似乎把背包里的东西跳过)。
        //
        // (2026-09-13 修正) 旧版本这行被读成 "bot 在下界毫无装备", 是误读 —
        // 旧探针本身有两个缺陷:
        //   缺陷 A 采样时机 + 无上下文: 本行是 onStart 一次性快照, 而死亡/重生/
        //     主链重启都会重建本任务 → 快照系统性落在"世界状态刚被重置"那一刻。
        //     122323 场次: 死亡 12:40:29.025 → [RESPAWN] → SESSION START 12:40:29.526
        //     → 本行 12:40:29.546 (死亡后 0.52s), 它描述的是重生点而非下界的背包。
        //     旧消息不带维度/坐标/触发原因, 读的人无法判断它说的是哪张地图。
        //   缺陷 B 计数走缓存且"读不到"与"0 个"同码: getItemCountInventoryOnly →
        //     ItemStorageTracker:106 → InventorySubTracker.getItemCount 读缓存 map;
        //     而 InventorySubTracker.updateState():199-207 是先 clear 四个 map,
        //     若 player/handler 为 null 则直接 return → 缓存留空 → 之后全读 0。
        //     164633 场次实锤 (该场无死亡): 同一诊断 267ms 内给出 ingots=0 与
        //     ingots=28 (16:52:56.624 / .891), 至少一次为假。
        // 修正: ① 改用 StorageHelper.countItemInInventoryReal (直读玩家实体:
        // 36 主背包 + 护甲 + 副手 + 光标, 不过缓存); ② 补 dim / pos / player 空标记 /
        // src 来源 / hand 主手 / met_cache 与 met_real 双判据, 让这行能自证。
        String dim = "?";
        String pos = "?";
        String hand = "?";
        boolean playerOk = false;
        try {
            ClientPlayerEntity p = mod.getPlayer();
            playerOk = (p != null);
            if (p != null) {
                dim = p.getWorld().getRegistryKey().getValue().toString();
                BlockPos bp = p.getBlockPos();
                pos = "(" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + ")";
                ItemStack main = p.getMainHandStack();
                hand = main.isEmpty() ? "empty" : main.getItem().toString();
            }
        } catch (Throwable ignored) {
            // 采样期间跨世界切换: 保留已取到的字段, 诊断绝不向上抛
        }
        int wooden = countReal(mod, Items.WOODEN_PICKAXE);
        int stone = countReal(mod, Items.STONE_PICKAXE);
        int iron = countReal(mod, Items.IRON_PICKAXE);
        int golden = countReal(mod, Items.GOLDEN_PICKAXE);
        int diamond = countReal(mod, Items.DIAMOND_PICKAXE);
        int netherite = countReal(mod, Items.NETHERITE_PICKAXE);
        // 双判据: met_cache 走既有(缓存+直扫兜底)判定; met_real 纯用上面的真实计数。
        // 两者不一致 = 计数层在撒谎, 这行自己就能暴露缺陷 B, 不必再去比对别的日志。
        boolean metCache = false;
        try {
            metCache = StorageHelper.miningRequirementMetInventory(mod, _requirement);
        } catch (Throwable ignored) {
        }
        boolean metReal = realMeets(wooden, stone, iron, golden, diamond, netherite);
        String m = "(SatisfyMiningReq) " + _requirement + " not met."
                + " dim=" + dim + " pos=" + pos + " player=" + (playerOk ? "ok" : "NULL")
                + " src=" + _src + " hand=" + hand
                + " met_cache=" + metCache + " met_real=" + metReal
                + " Pickaxes(real): wood=" + wooden + " stone=" + stone + " iron=" + iron
                + " golden=" + golden + " diamond=" + diamond + " netherite=" + netherite;
        // (2026-09-25 工具闸误判取证) 0925 15:50 溺死事故: 手持石镐却报全镐=0,
        // 循环合成木镐直到淹死。全槽快照 (含光标/2x2/3x3/打开容器) 挂在触发沿,
        // 下次复现直接定位镐子真实所在槽位。
        m += " inv=[" + StorageHelper.describeInventoryRealSnapshot(mod) + "]";
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    /** 直读玩家实体计数 (不走 tracker 缓存)。player 为 null 时返回 0 (由 player=NULL 标记区分)。 */
    private static int countReal(AltoClef mod, Item item) {
        try {
            return StorageHelper.countItemInInventoryReal(mod, item);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 纯用真实计数复算"本档位是否达标", 与 miningRequirementMetInner 的分档口径保持一致。 */
    private boolean realMeets(int wooden, int stone, int iron, int golden, int diamond, int netherite) {
        return switch (_requirement) {
            case HAND -> true;
            case WOOD -> (wooden + stone + iron + golden + diamond + netherite) > 0;
            case STONE -> (stone + iron + golden + diamond + netherite) > 0;
            case IRON -> (iron + golden + diamond + netherite) > 0;
            case DIAMOND -> (diamond + netherite) > 0;
        };
    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_requirement) {
            case HAND:
                // Will never happen if you program this right
                break;
            case WOOD:
            case STONE:
                // (2026-09-15 承诺锁) 已承诺上档镐 → 持续返回同一选择, 不重估。
                if (_committedPickaxe != null) {
                    if (++_commitTicks > COMMIT_ESCAPE_TICKS) {
                        Debug.logMessage("(SatisfyMiningReq) 承诺锁超时 " + (COMMIT_ESCAPE_TICKS / 20) + "s 未见 "
                                + _committedPickaxe + " → 上档镐链疑似死锁, 释放重估 (允许降级石镐)");
                        _committedPickaxe = null;
                        _commitTicks = 0;
                    } else {
                        setDebugState("Committed to " + _committedPickaxe + " (latch " + _commitTicks + "/" + COMMIT_ESCAPE_TICKS + ", ignore transient material moves)");
                        Task committed = TaskCatalogue.getItemTask(_committedPickaxe, 1);
                        if (committed != null) {
                            return committed;
                        }
                        _committedPickaxe = null; // 理论不可达: 镐配方必注册
                    }
                }
                // (2026-09-10 钻石镐优先, 用户"有钻石应做钻石镐而非石镐/铁镐")
                // 钻石 ≥3 + 棒料够 → 直接钻石镐: 全档位天花板(耐久 1562), 且主线
                // 后期挖黑曜石本就需要钻石镐, 早做不亏。
                if (shouldPreferDiamondPickaxe(mod)) {
                    commitPickaxe(Items.DIAMOND_PICKAXE);
                    setDebugState("Preferring DIAMOND pickaxe (diamonds+wood at hand, skip lower tiers)");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                }
                // (2026-09-09 铁镐优先, 用户"背包里有生铁和木头, 有能力制作铁镐
                // 但没有触发") 下界没有圆石, STONE 档默认做石镐 → 挖竖井找圆石
                // → "Shaft done, no ores found" 死循环 (14:25-14:27 tasks log ×3 轮)。
                // 背包有 ≥3 铁锭(或 ≥3 生铁, iron_ingot 任务链自带熔炼)且木棍/
                // 木板/原木够做 2 根棒子时, 直接走铁镐任务链 — 铁镐同样满足
                // WOOD/STONE 档位 (miningRequirementMetInner 认高阶镐)。
                if (shouldPreferIronPickaxe(mod)) {
                    commitPickaxe(Items.IRON_PICKAXE);
                    setDebugState("Preferring IRON pickaxe (iron+wood at hand, skip stone tier)");
                    return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
                }
                if (_requirement == MiningRequirement.WOOD) {
                    // (2026-10-01 断镐做木镐修复, 用户"他不做石头镐子反而做木镐, 错")
                    // 现场 20261001-155158: 15:54:47 [Pickaxe-Watchdog] 派 WOOD 档,
                    // 背包圆石 14+64x9+深板岩圆石 135, 铁锭 0 钻石 0 → 无条件做木镐。
                    // 木镐唯一存在意义是"挖到圆石", 圆石已够时直接做石镐 (合成不需要
                    // 镐, 石镐更快/耐久 131 vs 59, 同样满足 WOOD 档) — 与上方
                    // "钻石优先/铁镐优先"同族的跳档判定。口径用含打开容器可达计数,
                    // 与 shouldPreferIronPickaxe 一致 (防配方中间态翻假)。
                    if (StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.COBBLESTONE) >= 3) {
                        setDebugState("Preferring STONE pickaxe (cobblestone at hand, skip wooden tier)");
                        return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
                    }
                    return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
            case IRON:
                if (shouldPreferDiamondPickaxe(mod)) {
                    setDebugState("Preferring DIAMOND pickaxe (diamonds+wood at hand, skip iron tier)");
                    return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
                }
                // (2026-09-11 无镐死锁兜底, 用户"他其实可以制作一个镐子的") 铁镐链
                // 需要熔炉烧锭, 而 2026-08-30 的"没镐别挖石头墙"闸门
                // (AltoClef.avoidBlockBreak → WorldHelper.shouldAvoidUnminable) 在
                // _currentMiningReq=HAND 时禁止 baritone 挖一切石头 → 熔炉被石头挡
                // 时: 无镐→石头禁挖→熔炉不可达→铁锭烧不了→铁镐做不出→永远无镐。
                // 144656 场次实锤: Pickaxes 全 0 + rawIron=41 + 原木24, 冻结在
                // 熔炉交互 path[present=false] 打转。兜底: 背包无任何镐 且 铁锭
                // 不够直接合成(仍需熔炉) 且 有木料 → 先做一把木镐。木镐即解锁
                // _currentMiningReq≥WOOD → baritone 可破石清障, 循环打破;
                // 成本≈1 原木, 且"永远随身有镐"本就是拟人行为。
                if (needsAnyPickaxeFallback(mod)) {
                    // (2026-10-01 同根因修正) 圆石 ≥3 时直接石镐: 木镐的意义是"挖到
                    // 圆石", 圆石已够则石镐严格更优 (更快/更耐久), 同样解锁石头挖掘。
                    // 石镐不满足 IRON 档 → 拿到后本任务继续走铁镐链, 此时破石已解锁。
                    if (StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.COBBLESTONE) >= 3) {
                        setDebugState("No pickaxe at all — crafting STONE pickaxe first (cobblestone at hand)");
                        return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
                    }
                    setDebugState("No pickaxe at all — crafting WOODEN pickaxe first (unblocks stone breaking for furnace route)");
                    return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
                }
                return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
            case DIAMOND:
                return TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
        }
        return null;
    }

    /**
     * (2026-09-09) 是否手头材料足以直接做铁镐, 应跳过石/木镐档位。
     * 铁源: 铁锭 ≥3 或 生铁 ≥3 (TaskCatalogue.smelt("iron_ingot", ...) 链
     * 自带"生铁→熔炉→铁锭", 燃料由 SmeltInFurnaceTask 自取)。
     * 棒源: 木棍 ≥2, 或 木板 ≥2, 或 原木 ≥1 (可合成木棍/木板)。
     *
     * (2026-09-09 口径修正, 用户目击"原木放进合成格→任务翻转收集金子") 计数
     * 必须用含 2x2 合成格口径: 配方链把原木/木棍/木板放进 2x2 格做中间合成时,
     * 真背包口径计数变 0 → 本判定翻 false → SatisfyMiningReq 重选石镐 → 石镐链
     * "Breaking the crafting grid" 清格打断中间合成 → 原木回背包 → 判定又 true
     * → 无限翻转 (16:36 场次实锤, ~6.3s/圈)。与项目约定"配方判定用含格口径"
     * 对齐 (StorageHelper.countItemInInventoryRealIncludingCraftGrid)。
     */
    private boolean shouldPreferIronPickaxe(AltoClef mod) {
        // (2026-09-10 口径升级) 计数含打开容器自有槽位: 材料被搬进工作台 3x3 的
        // 瞬间不再被判"缺失" → 档位选择不翻转 (横跳根因之一, 18:28 场次)。
        int ingots = StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.IRON_INGOT);
        int rawIron = StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.RAW_IRON);
        if (ingots < 3 && rawIron < 3) return false;
        return hasStickMaterial(mod);
    }

    /**
     * (2026-09-10, 用户"有钻石应做钻石镐而非石镐/铁镐") 钻石 ≥3 且棒料够 →
     * 直接走钻石镐任务链 (WOOD/STONE/IRON 档统一适用)。钻石镐是全档位天花板
     * (挖速最快/耐久 1562), 主线后期挖黑曜石本来就必须钻石镐 — 有钻早做不亏,
     * 避免铁/石镐损耗后反复重做。棒料判定与铁镐优先共用, 含 2x2 合成格口径
     * (防配方中间态计数翻假 → 档位选择抖动, 同 shouldPreferIronPickaxe 教训)。
     */
    private boolean shouldPreferDiamondPickaxe(AltoClef mod) {
        // (2026-09-10 口径升级) 含打开容器自有槽位: 钻石被搬进工作台 3x3 等待合成
        // 的瞬间, 阈值判定不再骤降 → 不翻回铁镐档 (横跳根因之一)。
        int diamonds = StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.DIAMOND);
        return diamonds >= 3 && hasStickMaterial(mod);
    }

    /**
     * 棒料: 木棍 ≥2, 或 木板 ≥2, 或 任一原木 ≥1 (均可合成出 2 根棒子)。
     * (2026-09-25 static 公开) 镐子维持看门狗 (WorldSurvivalChain) 复用同一
     * 口径判定"现在派造镐链做不做得出来", 避免两处各写一份漂移。
     */
    public static boolean hasStickMaterial(AltoClef mod) {
        if (StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.STICK) >= 2) return true;
        if (StorageHelper.countItemsInInventoryRealIncludingCraftGrid(mod, ItemHelper.PLANKS) >= 2) return true;
        for (Item log : ItemHelper.LOG) {
            if (StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, log) >= 1) return true;
        }
        return false;
    }

    /**
     * (2026-09-11 无镐死锁兜底) 是否应先做一把木镐。条件三条:
     * ① 背包没有任何满足 WOOD 档的镐 (全档位裸奔);
     * ② 铁锭(可达口径) < 3 — 若锭够, 铁镐链走工作台直合, 不过熔炉, 无死锁;
     * ③ 有棒料 (原木/木板/木棍) — 否则做不出来, 挂着也没用。
     */
    private boolean needsAnyPickaxeFallback(AltoClef mod) {
        if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD)) return false;
        if (StorageHelper.countItemsAccessibleIncludingOpenContainer(mod, Items.IRON_INGOT) >= 3) return false;
        if (!hasStickMaterial(mod)) return false;
        Debug.logMessage("(SatisfyMiningReq) 无镐死锁兜底: 先做木镐解锁石头挖掘 (熔炉路线被石头挡时铁镐链永不收敛)");
        return true;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // (2026-09-25 挂死修复) 链活跃标志解除 (防御性钳制, 不允许负数)。
        if (_activeToolChains > 0) {
            _activeToolChains--;
        }
        // 承诺锁随任务实例终结 (拿到镐=isFinished 自然结束, 或被更高优先级打断;
        // 重启后按当时材料重新评估, 不跨任务实例继承旧选择)。
        _committedPickaxe = null;
        _commitTicks = 0;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof SatisfyMiningRequirementTask task) {
            return task._requirement == _requirement;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Satisfy Mining Req: " + _requirement;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.miningRequirementMetInventory(mod, _requirement);
    }
}
