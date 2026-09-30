package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ScaffoldBlocks;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Place a block type at a position
 */
public class PlaceBlockTask extends Task implements ITaskRequiresGrounded {

    private static final int MIN_MATERIALS = 1;
    private static final int PREFERRED_MATERIALS = 32;
    private final BlockPos _target;
    private final Block[] _toPlace;
    private final boolean _useThrowaways;
    private final boolean _autoCollectStructureBlocks;
    // (2026-09-12 观感) 「站住不动」容忍从默认 6s 收紧到 2s。现场
    // 20260912-084704: Place structure{furnace} at 20,57,152 站桩 6s 才放弃换位
    // (08:48:13→20.6), 单次放置总耗时 13.1s, 用户反馈观感差。移动中 distance
    // 有进展不会触发, 故 2s 只压缩"真卡住"的等待。其余参数保持默认。
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker(2, 0.1, 0.5, 0.001, 1);

    // (2026-09-28 让位硬上限, 与 PlaceBlockNearbyTask 同族) 本字段原注释就写着
    // "This can get stuck forever, so we increase the range", 但只放大了 range,
    // 没给出口: 下方 L134 的让位分支唯一出口是 `_wanderTask.isFinished()` = 走满
    // 3 格, baritone 无路径时 (pathPresent=false) 永不满足, 而该分支每 tick
    // `_progressChecker.reset()` ⇒ 永不再判负 ⇒ 僵尸态。同族现场 20260928-091258
    // 在 PlaceBlockNearbyTask 上实锤 89 秒零位移卡死。这里补同款墙钟硬上限。
    private static final long WANDER_GIVEUP_MS = 8000;
    private TimeoutWanderTask _wanderTask = new TimeoutWanderTask(3, true);
    /** 本轮让位的起始墙钟 (ms); 0 = 未开始计时。 */
    private long _wanderSinceMs = 0;
    private Task _materialTask;
    private int _failCount = 0;
    // (2026-09-30 擦边微位移) true = 正在按前进脱离目标格擦边。脱离/任务结束必须释放。
    private boolean _nudgeActive = false;
    /** 本轮 nudge 起始 tick; 0 = 未在 nudge。 */
    private int _nudgeSinceTick = 0;
    /** nudge 硬上限: 1.5s (30 tick) 仍未脱离 → 判定方向被堵, 松键落 wander 兜底。 */
    private static final int NUDGE_MAX_TICKS = 30;
    // (2026-09-05 传送门放置硬守卫) 拒绝日志只打一次的标记。
    private boolean _protectedRefusalLogged = false;

    // (2026-09-30 方案A 悬空垫脚) 放置的物理前提 = 目标格至少有一个实心邻格提供
    // 可点击依附面。废墟门悬空缺框场景 (20260930-221044): 模具格 (-255,73,172)
    // 六邻全空气 (placeFaces=NONE), baritone builder 不自主搭脚手架, 放置必然失败
    // → [PlaceStuck] 无限重试 (failCount=1..4 + wander 循环, 31.5s 零进展)。
    // 处置: 目标悬空时 one-shot 先垫下方一格 —— 垫脚格自身必须有依附面, 否则继续
    // 下探 (总深度上限 _scaffoldMaxDepth, 子任务递减继承); 垫好后目标获得顶面,
    // 回到正常放置。垫不上 (垫脚任务结束仍未就位) 或探底无面 → _scaffoldGaveUp,
    // 落回原有 wander/alternative 失败处理, 不无限重试。
    private static final int SCAFFOLD_MAX_DEPTH = 3;
    /** 本任务实例还允许的垫脚下探深度 (子任务递减继承, 防无界递归)。 */
    private final int _scaffoldMaxDepth;
    private Task _scaffoldTask;
    /** 当前正在垫的格子 (一定在 _target 正下方链上)。 */
    private BlockPos _scaffoldCell;
    /** one-shot 放弃标记: 垫脚失败/探底无面后本实例不再尝试。 */
    private boolean _scaffoldGaveUp = false;

    public PlaceBlockTask(BlockPos target, Block[] toPlace, boolean useThrowaways, boolean autoCollectStructureBlocks) {
        this(target, toPlace, useThrowaways, autoCollectStructureBlocks, SCAFFOLD_MAX_DEPTH);
    }

    protected PlaceBlockTask(BlockPos target, Block[] toPlace, boolean useThrowaways, boolean autoCollectStructureBlocks, int scaffoldMaxDepth) {
        _target = target;
        _toPlace = toPlace;
        _useThrowaways = useThrowaways;
        _autoCollectStructureBlocks = autoCollectStructureBlocks;
        _scaffoldMaxDepth = scaffoldMaxDepth;
    }

    public PlaceBlockTask(BlockPos target, Block... toPlace) {
        // (2026-08-26 用户要求: 没方块搭时自动去收集, 收集完再恢复任务) 旧默认
        // autoCollectStructureBlocks=false → 材料收集逻辑不启用, bot 没方块时
        // 空手跳跃手足无措。改为默认 true: 当 getMaterialCount < 1 (一块都没有)
        // 时, 自动派发 getMaterialTask(32) 去收集泥土/圆石/深板岩原石/下界岩,
        // 收集完 (count>=32) 再恢复本任务搭方块。
        // (2026-09-29 口径修正) 上面那句"泥土/圆石/深板岩原石/下界岩"已成历史:
        // 现在只补【当前维度那一种】, 见 getMaterialTask 注释 (旧全量清单会把
        // "放一张床"撑成"下界远征")。
        this(target, toPlace, false, true);
    }

    public static int getMaterialCount(AltoClef mod) {
        // (2026-08-23 统计修正) 旧逻辑依赖 ItemStorageTracker 缓存, 与
        // Restocking 原木同款 bug: 背包实际有 dirt/cobblestone/netherrack 却
        // 统计为 0 → PlaceBlockTask 误判"没有结构方块" → 反复触发收集 32 个
        // 结构方块 (日志 15:19:52 "No structure items, collecting cobblestone
        // + dirt as default") → 被高优先级任务抢占 → 建传送门卡死。直接扫玩家
        // 背包 (main+offhand), 不依赖缓存刷新时机。
        // (2026-08-23 深板岩原石) 用户实测背包有 47 个深板岩原石仍报"缺方块" —
        // 结构方块候选只含 dirt/cobblestone/netherrack, 不含 cobbled_deepslate。
        // 深板岩原石是深地下最常见的可放置方块, 必须计入。
        if (mod.getPlayer() == null || mod.getPlayer().getInventory() == null) return 0;
        int count = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (isStructureMaterial(item)) {
                count += stack.getCount();
            }
        }
        ItemStack offhand = mod.getPlayer().getInventory().offHand.get(0);
        if (offhand != null && !offhand.isEmpty()) {
            if (isStructureMaterial(offhand.getItem())) {
                count += offhand.getCount();
            }
        }
        return count;
    }

    /** (2026-08-23) 可作为 CAST_FRAME 支撑的结构方块。
     *  (2026-08-26 讨论: 沙子/沙砾不能加入 — 重力方块水平搭路(悬空)时无支撑会
     *  落下去, 桥搭不起来反而更危险。只能用于有下方支撑的填埋场景, 需单独机制。)
     *  (2026-09-19 21:20 统一口径) 收编 ScaffoldBlocks 中心清单 (非重力, 同约束)。 */
    private static boolean isStructureMaterial(Item item) {
        return ScaffoldBlocks.isScaffoldBlock(item);
    }

    public static Task getMaterialTask(AltoClef mod, int count) {
        // (2026-09-29 清单语义修正, 现场 logs/altoclef/20260929-192036 首帧任务栈)
        // 旧实现把 ScaffoldBlocks.SAFE_MATERIALS **全量** (15 项 x count) 当成"要去
        // 收集"的目标清单 —— 但那个清单是"背包【有】就能用"的【计数/放置】宽口径,
        // 其中 netherrack/blackstone/basalt/soul_sand/soul_soil 挂 forceDimension(NETHER)、
        // end_stone 挂 forceDimension(END)。实测后果:
        //   L2 <Placing a bed nearby + resetting spawn point>
        //   L3 <Place structure{} at 173,90,36> "No structure items, collecting..."
        //   L4 <Get catalogued: {netherrack x32, ..., end_stone x32}>
        //   L6 <Going to dimension: NETHER> → L7 <Construct Nether Portal> → 要 6 铁锭
        // 即"放一张床过夜"被自己的建材清单撑成了"造下界传送门远征"; 而每次死亡
        // [DeathReset] 都会整树重派发 ⇒ 一辈子在木器/铁器之间重爬 (全天进度止步铁器时代)。
        // 现在只向【当前维度可得】的那一种补货 —— 复用 ScaffoldBlocks.preferredRestockItem
        // 这个既有窄口径入口 (与 FortressExploreTask:1027 / CollectBlazeRodsTask:1480
        // 同源), 不另开副本: 主世界→圆石/深板岩圆石, 下界→地狱岩/黑石/玄武岩,
        // 末地→末地石, 三者均已注册 TaskCatalogue, 维度串门路径被彻底切断。
        //
        // (2026-09-29 崩溃修复层 ②, 保留) taskExists 闸门不可省: TaskCatalogue.getItemTask
        // 返回 null 会被 CataloguedResourceTask 塞进 squasher ⇒ t.getClass() NPE 崩客户端
        // (现场 20260929-18:48 末地折返平台建造, 背包建材耗尽时走到这条收集路径)。
        if (mod == null || mod.getPlayer() == null) return null;
        Item want = ScaffoldBlocks.preferredRestockItem(mod);
        if (!TaskCatalogue.taskExists(want)) {
            if (_unregisteredMaterialLogged.add(want)) {
                Debug.logWarning("[PlaceBlock] structure material " + want.getTranslationKey()
                        + " has no TaskCatalogue entry — not requesting it (still usable if already carried)");
            }
            return null;
        }
        return TaskCatalogue.getSquashedItemTask(new ItemTarget(want, count));
    }

    /** 已就"无 TaskCatalogue 条目"报过警的材料 (静态去重, 防每 tick 刷屏)。 */
    private static final java.util.Set<Item> _unregisteredMaterialLogged =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override
    protected void onStart(AltoClef mod) {
        _progressChecker.reset();
        _wanderSinceMs = 0;
        _nudgeSinceTick = 0;
        // (2026-09-30 悬空垫脚) 每次启动重置垫脚 one-shot 状态 (任务被父链重建
        // 后应允许重新尝试垫脚; 垫脚任务实例本身不保留, 防跨重启引用失效)。
        _scaffoldTask = null;
        _scaffoldCell = null;
        _scaffoldGaveUp = false;
        // If we get interrupted by another task, this might cause problems...
        //_wanderTask.resetWander();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // (2026-09-05 传送门放置硬守卫, 用户"bot 想把熔炉放进下界门内") 放置前
        // 兜底检查: 目标位命中放置保护 (含门体黑名单) → 拒绝交给 Baritone builder。
        // 根因: builder 放置前会"清场"——目标位方块状态不匹配就挖掉, 门内紫色方块
        // 被替换/门框被挖, 门就熄了。选点端 canPlace 已过滤, 此处防"选点后现场变化"
        // 与"被其他任务直接构造"两种绕过路径。
        if (refuseIfProtected(mod)) {
            setDebugState("Refusing to place at protected position");
            return null;
        }

        // Perform timeout wander
        // (2026-09-28 让位硬上限, 见字段区注释) 超时 ⇒ 换新 wander 实例 (isActive()
        // 归假 ⇒ 本拍不再 return 它), 落到下方继续尝试 (baritone 放置 / alternative way)。
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            if (_wanderSinceMs == 0) {
                _wanderSinceMs = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - _wanderSinceMs > WANDER_GIVEUP_MS) {
                Debug.logMessage("[PlaceStuck] 让位 wander 超时 " + (WANDER_GIVEUP_MS / 1000)
                        + "s 未产生位移 at " + _target.toShortString() + " (baritone 疑似无路径), 丢弃本轮让位");
                _wanderTask = new TimeoutWanderTask(3, true);
                _wanderSinceMs = 0;
                _progressChecker.reset();
            } else {
                setDebugState("Wandering.");
                _progressChecker.reset();
                return _wanderTask;
            }
        }

        // (2026-09-14 站在目标格里) 必须在 progressChecker 之前: 站在目标格内
        // 必然"无移动进展", 走 checker 会先被超时打断成 wander → 又掉回坑里
        // 死循环。这里确定性走出目标格后再交给 baritone。
        //
        // (2026-09-27 身位占格扩展) 判据从"脚格 == 目标"扩为"脚格 **或头格** == 目标"。
        // 现场 20260927-111838: 目标格 -399,7,175 (CAST_FRAME 的 (0,-1,0) 底托格) 是
        // 空气, 而 bot 脚在 -399,6,175 —— 它的上半身/头正好占着目标格, vanilla 禁止
        // 把方块放进自己身体 ⇒ baritone 不放置也不报错; 旧判据只认脚格, 于是落到下面的
        // 距离判据, 每 2s 判一次 `actual=0.0` 并 `Failed to place, wandering timeout.`
        // (11:27:16 `[PlaceStuck] placement progress failed at -399, 7, 175 (failCount=1)`)
        // 用户现场: "他自己站在那一个, 刚要跳起来放方块结果任务就跳了" —— 就是这条判负
        // 把刚要起跳放置的动作腰斩。修 = 先认身位占格, 让位后再交给 baritone。
        // (2026-09-30 方案B) 身位占格判据从"脚格/头格 == 目标 (精确坐标 floor)"
        // 升级为"玩家碰撞箱与目标格相交": bot 贴着格边界站时 floor 会在占格/
        // 不占格之间抖动 (20260930-192728 实锤), 而 vanilla 禁止把方块放进与
        // 玩家碰撞箱相交的格子 —— 碰撞箱求交才是与放置成败同口径的判据。
        // 玩家箱 0.6x1.8, 至多跨 2x2x2=8 格, 逐格比对开销可忽略。
        // (方案A 配套) 让位目标用严格到达 GetToBlockTask: 旧版 bot 在 1.4 格外
        // 被"≤2 格 holding"豁免停走, 让位永不完成 (同场 50.4s 死锁)。
        if (playerOccupiesTargetCell(mod)) {
            // (2026-09-30 擦边微位移, 方案B 补丁) 21:26 现场: bot 已站进让位格
            // (-254,73,172), 但身体箱 minX=-254.01 擦进目标格 (-255,73,172)
            // 0.01 格 (脱出需中心 x≥-253.70, bot 在 -253.71, 差 1cm)。此时
            // GetToBlockTask 的格口径 isFinished 已为 true (floor 格 == 让位格),
            // 派导航它永远不再动; 而本任务箱口径恒判占格 → 每拍互等死锁 4m42s。
            // 处置: floor 格已在目标格之外 (只是擦边) 时, 不派导航, 直接朝
            // 远离目标格中心的方向微位移, 每拍复查, 脱离即松键。
            BlockPos playerCellNow = mod.getPlayer().getBlockPos();
            if (!playerCellNow.equals(_target) && !playerCellNow.up().equals(_target)) {
                setDebugState("Brushing target cell edge, nudging away");
                return nudgeAwayFromTarget(mod);
            }
            BlockPos stepOut = pickStepOutPos(mod);
            if (stepOut != null) {
                setDebugState("Standing in/under target cell, stepping out first");
                return new GetToBlockTask(stepOut, false, true);
            }
            setDebugState("Standing in/under target cell, no walkable neighbor, wandering");
            _progressChecker.reset();
            return _wanderTask;
        }
        // (擦边补丁收尾) 已脱离目标格 → 任何残留的 nudge 按键必须释放。
        releaseNudge(mod);

        // (2026-09-30 方案A 悬空垫脚) 无依附面 = 放置物理不可能, 不用烧 progress
        // 失败次数, 确定性判据直接垫。见字段区注释。
        if (hasAttachFace(mod, _target)) {
            // 有依附面 (含垫脚完成后的情况): 清理垫脚状态, 走正常放置。
            if (_scaffoldTask != null) {
                Debug.logMessage("[PlaceScaffold] 垫脚格 " + _scaffoldCell.toShortString()
                        + " 已就位, 继续放置 " + _target.toShortString());
                _scaffoldTask = null;
                _scaffoldCell = null;
            }
        } else if (!_scaffoldGaveUp && _scaffoldMaxDepth > 0) {
            if (_scaffoldTask != null) {
                if (WorldHelper.isSolid(mod, _scaffoldCell)) {
                    Debug.logMessage("[PlaceScaffold] 垫脚格 " + _scaffoldCell.toShortString()
                            + " 已就位, 继续放置 " + _target.toShortString());
                    _scaffoldTask = null;
                    _scaffoldCell = null;
                } else if (_scaffoldTask.isActive() && !_scaffoldTask.isFinished(mod)) {
                    setDebugState("Scaffolding support below at " + _scaffoldCell.toShortString());
                    _progressChecker.reset();
                    return _scaffoldTask;
                } else {
                    Debug.logMessage("[PlaceScaffold] 垫脚任务结束仍未垫上 " + _scaffoldCell.toShortString()
                            + " (one-shot 放弃, 落回 wander/alternative)");
                    _scaffoldTask = null;
                    _scaffoldCell = null;
                    _scaffoldGaveUp = true;
                }
            } else {
                BlockPos cell = _target;
                int depth = 0;
                while (!hasAttachFace(mod, cell) && depth < _scaffoldMaxDepth) {
                    cell = cell.down();
                    depth++;
                }
                if (!cell.equals(_target) && hasAttachFace(mod, cell)) {
                    Debug.logMessage("[PlaceScaffold] 目标 " + _target.toShortString()
                            + " 悬空无依附面, 先垫 " + cell.toShortString() + " (下探 " + depth + " 格)");
                    _scaffoldCell = cell;
                    _scaffoldTask = new PlaceStructureBlockTask(cell, _scaffoldMaxDepth - depth);
                    _progressChecker.reset();
                    return _scaffoldTask;
                }
                Debug.logMessage("[PlaceScaffold] 目标 " + _target.toShortString() + " 悬空且下探 "
                        + _scaffoldMaxDepth + " 格仍无依附面 (one-shot 放弃)");
                _scaffoldGaveUp = true;
            }
        }

        if (_autoCollectStructureBlocks) {
            if (_materialTask != null && _materialTask.isActive() && !_materialTask.isFinished(mod)) {
                // (2026-09-29) 文案改口径: 旧文案写死 "cobblestone + dirt", 而旧实现
                // 实际上在收集 SAFE_MATERIALS 全量 15 项 —— 文案与行为早就不一致,
                // 也是 20260929-192036 现场判读被误导的原因之一。
                setDebugState("No structure items, collecting scaffold material for this dimension.");
                if (getMaterialCount(mod) < PREFERRED_MATERIALS) {
                    return _materialTask;
                } else {
                    _materialTask = null;
                }
            }

            //Item[] items = Util.toArray(Item.class, mod.getClientBaritoneSettings().acceptableThrowawayItems.value);
            if (getMaterialCount(mod) < MIN_MATERIALS) {
                // TODO: Mine items, extract their resource key somehow.
                Task materialErrand = getMaterialTask(mod, PREFERRED_MATERIALS);
                // (2026-09-29) 补货任务可能为 null (当前维度那一种没注册 / 玩家未就绪)。
                // 旧写法无脑 `return _materialTask` ⇒ 每 tick 返回 null 的静默空转。
                // 拿不到就如常往下走: baritone builder 仍能用背包里已有的垫脚方块施工。
                if (materialErrand != null) {
                    _materialTask = materialErrand;
                    _progressChecker.reset();
                    return _materialTask;
                }
                _materialTask = null;
            }
        }


        // Check if we're approaching our point. If we fail, wander for a bit.
        if (!_progressChecker.check(mod)) {
            _failCount++;
            // (2026-09-16 放床铺平台死循环) 下方 L190 只在 `!BuilderProcess.isActive()`
            // 时才 build(...)。builder 一旦被激活, 即使它算不出任何放置方案
            // (builder=true pathing=false path[present=false], 玩家原地不动),
            // 也会永远保持 isActive → 后续每 tick 都不再重新 build → 必然再次超时,
            // 配合 TimeoutWanderTask 半径递增形成无限环。判负时强制释放 builder,
            // 让下一 tick 重新 build 一次 (目标悬空/无依附面时至少会显式失败一次)。
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            Debug.logMessage("[PlaceStuck] placement progress failed at " + _target.toShortString()
                    + " (failCount=" + _failCount + "), released baritone builder to force re-plan");
            if (!tryingAlternativeWay()) {
                Debug.logMessage("Failed to place, wandering timeout.");
                return _wanderTask;
            } else {
                Debug.logMessage("Trying alternative way of placing block...");
            }
        }


        // Place block
        if (tryingAlternativeWay()) {
            setDebugState("Alternative way: Trying to go above block to place block.");
            return new GetToBlockTask(_target.up(), false);
        } else {
            setDebugState("Letting baritone place a block.");

            // Perform baritone placement
            if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
                Debug.logInternal("Run Structure Build");
                ISchematic schematic = new PlaceStructureSchematic(mod);
                mod.getClientBaritone().getBuilderProcess().build("structure", schematic, _target);
            }
        }

        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // (2026-09-30 擦边微位移) 任务被打断时 nudge 前进键可能还按着, 必须释放。
        releaseNudge(mod);
        mod.getClientBaritone().getBuilderProcess().onLostControl();
    }

    //TODO: Place structure where a leaf block was???? Might need to delete the block first if it's not empty/air/water.

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceBlockTask task) {
            return task._target.equals(_target) && task._useThrowaways == _useThrowaways && Arrays.equals(task._toPlace, _toPlace);
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        assert MinecraftClient.getInstance().world != null;
        // (2026-09-05 传送门放置硬守卫) 目标位受放置保护 → 直接放弃 (视作完成,
        // 让上层另选点位)。否则上层 (如 DoStuffInContainerTask) 会因本任务"永远
        // 未完成"而卡死在放置分支。
        if (refuseIfProtected(mod)) {
            return true;
        }
        if (_useThrowaways) {
            return WorldHelper.isSolid(mod, _target);
        }
        BlockState state = mod.getWorld().getBlockState(_target);
        return ArrayUtils.contains(_toPlace, state.getBlock());
    }

    @Override
    protected String toDebugString() {
        return "Place structure" + ArrayUtils.toString(_toPlace) + " at " + _target.toShortString();
    }

    /** (2026-09-12 诊断) 放置目标位, 供 snapshot 打印目标格方块/可放置面现场。 */
    @Override
    public net.minecraft.util.math.BlockPos getDiagTargetBlock() {
        return _target;
    }

    private boolean tryingAlternativeWay() {
        return _failCount % 4 == 3;
    }

    /**
     * (2026-09-05 传送门放置硬守卫) 目标位命中放置保护 (门体黑名单/显式保护区/
     * 任务栈放置避让) → 记一次 [PortalGuard] 日志并返回 true。onTick 与 isFinished
     * 共用, 保证无论从哪条路进都能留下拒绝痕迹且只记一次。
     */
    private boolean refuseIfProtected(AltoClef mod) {
        if (mod.getExtraBaritoneSettings().shouldAvoidPlacingAt(_target)) {
            if (!_protectedRefusalLogged) {
                _protectedRefusalLogged = true;
                Debug.logMessage("[PortalGuard] 拒绝在受保护位置放置: " + _target.toShortString() + " (门体/显式保护区), 放弃该点位");
            }
            return true;
        }
        return false;
    }

    // (2026-09-14 站在目标格里) 水平四向, 供走出目标格用。
    private static final Vec3i[] STEP_OUT_OFFSETS = new Vec3i[]{
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1)
    };

    /**
     * (2026-09-14 站在目标格里) 玩家 feet 正好站在目标格内时, vanilla 禁止往
     * 自身占据的格子放方块 → baritone 不放置也不报错, 只空转寻路 (现场
     * 20260914 传送门浇铸: 模具残块围出 1 格深坑, wander 掉进去后
     * "Failed to place, wandering timeout" 每 5s 死循环, 见
     * memory/detail/04-symptoms.md「bot 站在目标格里」)。返回一个可站立的
     * 水平邻格 (非固体 + 头顶非固体), 让 bot 先走出目标格。
     *
     * (2026-09-27 身位占格) 目标格 = 自己**头格**时 (脚在 _target.down(), 见 onTick 注释),
     * 目标同层的水平邻格基本会被"头顶被堵"整批判否 —— 1.5 格高的横洞里 y+1 就是天花板
     * (现场 20260927-111838: 四邻 -398/-400,7,175 与 -399,7,174/176 的 y+1 全是实心)。
     * 但 bot **自己那一层**的水平邻格其实能站也能伸手放 (现场: bot 脚在 -399,6,175 沿 x
     * 一路可走)。故补一层"玩家脚所在层"的候选, 让 bot 侧移半步再交给 baritone。
     */
    private BlockPos pickStepOutPos(AltoClef mod) {
        for (Vec3i offset : STEP_OUT_OFFSETS) {
            BlockPos p = _target.add(offset);
            if (WorldHelper.isSolid(mod, p)) continue;      // 被堵
            if (WorldHelper.isSolid(mod, p.up())) continue; // 头顶被堵
            return p;
        }
        BlockPos feet = mod.getPlayer().getBlockPos();
        for (Vec3i offset : STEP_OUT_OFFSETS) {
            BlockPos p = feet.add(offset);
            if (p.equals(_target)) continue;                // 别又退回目标格
            if (WorldHelper.isSolid(mod, p)) continue;
            if (WorldHelper.isSolid(mod, p.up())) continue;
            return p;
        }
        return null;
    }

    /**
     * (2026-09-30 擦边微位移) 朝远离目标格中心的水平方向按前进, 侧移 1~2cm 脱离
     * 擦边重叠。进入时记起始 tick; 脱离由 onTick 的占格复查收口 (releaseNudge);
     * {@link #NUDGE_MAX_TICKS} 内未脱离 → 方向被堵, 松键落 wander 兜底 (防按死)。
     */
    private Task nudgeAwayFromTarget(AltoClef mod) {
        if (_nudgeSinceTick == 0) {
            _nudgeSinceTick = WorldHelper.getTicks();
        } else if (WorldHelper.getTicks() - _nudgeSinceTick > NUDGE_MAX_TICKS) {
            Debug.logMessage("[PlaceNudge] 擦边脱离 1.5s 未成功 (方向疑似被堵), 松键落 wander");
            releaseNudge(mod);
            _progressChecker.reset();
            return _wanderTask;
        }
        _nudgeActive = true;
        Vec3d p = mod.getPlayer().getPos();
        double dx = p.x - (_target.getX() + 0.5);
        double dz = p.z - (_target.getZ() + 0.5);
        // dx/dz 至少一个非零: floor 格 != 目标格时玩家中心必在别的格, 不可能与
        // 目标格中心完全重合。lookAt 只改 yaw (y 取玩家当前 y, 不抬头/低头)。
        LookHelper.lookAt(mod, new Vec3d(p.x + dx, p.y, p.z + dz));
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.SNEAK);
        return null;
    }

    /** (擦边微位移) 释放 nudge 按键, 复位状态。幂等。 */
    private void releaseNudge(AltoClef mod) {
        if (!_nudgeActive) return;
        mod.getInputControls().release(Input.MOVE_FORWARD);
        _nudgeActive = false;
        _nudgeSinceTick = 0;
    }

    /**
     * (2026-09-30 方案B) 玩家碰撞箱是否与目标格相交。
     *
     * <p>旧判据 {@code getBlockPos()}(精确坐标 floor) == 目标 的缺陷: bot 贴着
     * 格边界站时, 坐标微动就让脚格在"目标格/邻格"之间抖动 (现场
     * 20260930-192728 19:44:08~19:44:57, 搭下界门 (-148,53,253) 时 bot 冻在
     * (-148,53,254) 边缘 50.4s, 让位要求每拍重触发)。而 vanilla 能不能把方块
     * 放进一格, 真正取决于【玩家碰撞箱与该格是否相交】—— 用它作判据才能与
     * 放置成败同口径, 且天然涵盖旧判据的"脚格/头格"两种情况 (箱高 1.8 含头)。
     */
    private boolean playerOccupiesTargetCell(AltoClef mod) {
        Box box = mod.getPlayer().getBoundingBox();
        BlockPos min = new BlockPos(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ));
        BlockPos max = new BlockPos(MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ));
        for (BlockPos p : BlockPos.iterate(min, max)) {
            if (p.equals(_target)) return true;
        }
        return false;
    }

    /**
     * (2026-09-30 方案A 悬空垫脚) 目标格六邻是否至少有一个实心方块 —— 即 vanilla
     * 放置是否存在可点击的依附面。液体/空气都算无面。悬空无面时放置物理不可能,
     * baritone builder 也不会自主搭脚手架 (20260930-221044 实证), 由垫脚逻辑收口。
     */
    private static boolean hasAttachFace(AltoClef mod, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (WorldHelper.isSolid(mod, pos.offset(dir))) {
                return true;
            }
        }
        return false;
    }

    private class PlaceStructureSchematic extends AbstractSchematic {

        private final AltoClef _mod;

        public PlaceStructureSchematic(AltoClef mod) {
            super(1, 1, 1);
            _mod = mod;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState blockState, List<BlockState> available) {
            if (x == 0 && y == 0 && z == 0) {
                // Place!!
                for (BlockState possible : available) {
                    if (possible == null) continue;
                    if (_useThrowaways && _mod.getClientBaritoneSettings().acceptableThrowawayItems.value.contains(possible.getBlock().asItem())) {
                        return possible;
                    }
                    if (Arrays.asList(_toPlace).contains(possible.getBlock())) {
                        return possible;
                    }
                }
                Debug.logInternal("Failed to find throwaway block");
                // No throwaways available!!
                return new BlockOptionalMeta(Blocks.COBBLESTONE).getAnyBlockState();
            }
            // Don't care.
            return blockState;
        }
    }
}
