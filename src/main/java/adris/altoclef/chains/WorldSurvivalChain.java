package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.misc.DrinkFireResPotionTask;
import adris.altoclef.tasks.movement.*;
import adris.altoclef.tasks.resources.CollectBlazeRodsTask;
import adris.altoclef.tasks.resources.SatisfyMiningRequirementTask;
import adris.altoclef.tasks.resources.TradeWithPiglinsTask;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;

public class WorldSurvivalChain extends SingleTaskChain {

    // (2026-09-14 横跳修复, 用户"岩浆中游动上下浮动, 每次短暂离开岩浆任务就切换,
    // 反复横跳") 原 1s 窗口太短: bot 在岩浆面 bob 浮出水面 >1s 时 isInLava=false 且
    // 计时器到期 → 本链优先级掉回 -INF → User Tasks(50) 接管 → 沉回岩浆又抢回
    // (100) → latest.log 20:54:01~20:55:25 每秒一轮 "Chain Interrupted" +
    // "Task START: <Escaping lava>" 死循环。拉长到 4s: 浮出 4s 内 priority 保持
    // 100, 任务连续; 而 EscapeFromLavaTask.isFinished 的离场判据不变, 真上岸后
    // 自然让位。4s 里 bot 若真上岸, 任务空转几秒无害 (isFinished 会因火灭/离场收尾)。
    private final TimerGame _wasInLavaTimer = new TimerGame(4);
    // (2026-09-14 防火岩浆自救取证) 逃岩浆任务运行期 2s 一条诊断节流 (static:
    // 链实例重建不洗掉)。
    private static long _lastLavaEscapeLogMs = 0;

    // ===== (2026-09-14 防火药水自动补给, 用户"如果背包有额外药水, 然后要烧死了,
    //       会自动补药水对吧") =====
    // 旧版**完全不会**: 全仓 DrinkFireResPotionTask 只有一处调用
    // (CollectBlazeRodsTask:129 打烈焰人前的门槛), 与"身上着火 / 泡在岩浆里"无关;
    // WorldSurvivalChain 的所有保命分支都不碰药水。
    //
    // 门槛 (用户 2026-09-14 选"严一点", 只认两个时刻, 其余不浪费药):
    //   ① 补杯: 药效将尽 (≤ FIRE_RES_TOPUP_TICKS) 且身处危险 (着火或岩浆)。
    //      趁药效还在时喝 —— 此时停在岩浆里不挨任何伤害, 喝完无缝衔接。
    //   ② 急救: 已断档 + 身处危险。
    //      (2026-09-29 政策反转, 用户"bot 刚刚掉进岩浆…逃离岩浆抢住了不喝, 导致死了")
    //      旧口径"断档 + 在岩浆里故意不喝"实测致死: 逃跑要穿 3 格岩浆, 任务链让
    //      EscapeFromLavaTask 一直抢着跑, 血被烧空。新口径 (用户定):
    //        - 血 ≤ FIRE_RES_LAVA_EMERGENCY_HEALTH(8) → 喝 (岩浆 4/s, 8 = 喝完
    //          1.6s 还剩 2s 余量);
    //        - 逃跑要穿过 > FIRE_RES_LAVA_DRINK_DISTANCE(2) 格岩浆 (BFS 数岩浆格
    //          到最近上岸格) → 直接喝。
    //      喷溅型需要脚下固体方块才有效, 选择逻辑在 DrinkFireResPotionTask:
    //      能喷就喷 (瞬发), 否则喝饮用的 (1.6s)。喝药 1.6s 内逃岩浆被顶掉是有意
    //      代价 —— 换来 8 分钟防火, 比"不喝烧死"划算。真正防横跳仍靠 ① 补杯。
    /** 药效剩余低于此 tick 数 = "将尽" (15s @ 20tps)。 */
    private static final int FIRE_RES_TOPUP_TICKS = 300;
    /**
     * 已断档 + 只是着火 (不在岩浆) 时的急救健康值门槛。
     * (2026-10-01 由 10 降为 6, 用户定) 1001 16:46 现场: 蹲珍珠路过沾火掉 1 HP
     * (10.9→9.9) 即触发, 火 ~0.5s 后自熄, 药 0.45s 被打断没喝成还白重建了一拍
     * BeatMinecraft2Task —— 普通火 1/s 且会自熄, 与岩浆 4/s 不同档, 阈值须更低。
     * 6 = 6s 站火余量, 且远低于 autoEat 回血线, 路过沾火基本不再触发。
     */
    private static final float FIRE_RES_EMERGENCY_HEALTH = 6.0f;
    /** (2026-09-29) 已断档 + 在岩浆里时的急救健康值门槛 (岩浆 4/s, 8 = 1.6s 喝完剩 2s)。 */
    private static final float FIRE_RES_LAVA_EMERGENCY_HEALTH = 8.0f;
    /** (2026-09-29) 逃跑要穿过的岩浆格数 > 此值 → 直接喝 (用户定 2)。 */
    private static final int FIRE_RES_LAVA_DRINK_DISTANCE = 2;
    /** (2026-09-29) 岩浆 BFS 半径上限; 超过视为"很远"→ 触发喝药。 */
    private static final int FIRE_RES_LAVA_BFS_LIMIT = 16;
    /** 喝药任务连续占链硬顶 (8s): 喝完/喝不成都必须放行, 防 prio 105 焊死在链上。 */
    private static final long FIRE_RES_DRINK_TIMEOUT_MS = 8000;
    /** 喝不成后的静默期 (30s): 防"立刻重试 → 再喝不成"死循环。 */
    private static final long FIRE_RES_DRINK_GIVEUP_MS = 30000;
    /** 本轮喝药开始时刻 (static: 链实例重建不洗掉)。 */
    private static long _fireResDrinkSinceMs = 0;
    /** 放弃重试的截止时刻。 */
    private static long _fireResDrinkGiveUpUntilMs = 0;
    // (2026-09-14 坠落取证) 上一次是否处于自由落体, 用于抓"进入坠落"的边沿。
    private static boolean _wasFalling = false;
    private boolean _wasAvoidingDrowning;
    private TimerGame _portalStuckTimer = new TimerGame(5);

    private BlockPos _extinguishWaterPosition;

    // 着火期间为"找附近的水"临时跟踪 WATER 方块，火灭后停止（引用计数）。
    private boolean _wasTrackingWaterForFire = false;

    // ==== 防溺水"找空气" (2026-09-10, 13:15 场次) ====
    // 旧 handleDrowning 双重失效: (a) isPathing 闸门——baritone 寻路中完全不生效
    // (现场 pathing=true 全程); (b) 只会 hold(JUMP) 上浮, 隧道顶封死时按跳无效,
    // bot 在水下 air=-0.04 掉血 18s 淹死。新增: 空气危急(<25%)且在水中时,
    // 以高优先级导航到最近可呼吸方块 (AIR/CAVE_AIR/VOID_AIR)。
    // (2026-09-23 用户 12:13 淹死复盘: 空气 0.25 才救太晚) 现场 bot 水底采掘时
    // air 从 0.77 一路耗到 0.25 才触发逃生, 氧气白白耗掉大半 + 逃生又卡压顶挖不了,
    // 最终空气 -0.04 健康归零淹死。水下采掘任务(SwimDive)只在 air<0.5 才让位,
    // 逃生阈值若不匹配就会造成"0.5~0.25 之间的氧气被采掘任务无脑耗干"。
    // (2026-09-23 用户实测) 0.5 仍偶发压顶逃生慢半拍, 实测 0.7 触发最稳妥:
    // 氧气还剩 7 成就立即抬头/挖压顶, 给食物采购等水下动作留足余量, 呼吸永远优先。
    private static final double DROWN_ESCAPE_AIR_FRACTION = 0.7;
    private static final int DROWN_ESCAPE_SEARCH_RADIUS = 16;
    private static final float DROWN_ESCAPE_PRIORITY = 95; // 低于岩浆/火焰(100), 高于常规任务
    private final TimerGame _drownReissueTimer = new TimerGame(1); // 溺水时限紧, 1s 重发冷却
    private final TimerGame _drownPathlessTimer = new TimerGame(1.5); // baritone 无路径观察期
    private boolean _loggedNoBreathable = false;
    // (2026-09-22 水下近距离直接游动兜底) baritone 水下寻路在"头顶有方块、需先侧
    // 向游再上浮"场景下长期无路径 (现场 20260922-172819: 空气在 (100,63,-231),
    // bot 在 (101,61,-231), 每 1.5s 重发 GetToBlockTask 却导航不到, 30s 淹死)。
    // 目标欧氏距离小于此阈值且 GetToBlockTask 无路径超时 → 直接面向目标 hold
    // 前进+跳跃游过去, 绕过 baritone 寻路。
    private static final double DROWN_DIRECT_SWIM_DIST_SQ = 5.0 * 5.0;
    private BlockPos _drownSwimTarget = null;
    // (2026-09-22) 直接游动超时: 超过此时长水平位移不足 → 直游被挡 (前方/头顶
    // 有方块), 回退到挖穿兜底。溺水时限紧, 给 3s 足够试出"游不动"。
    private static final int DROWN_DIRECT_SWIM_TIMEOUT_TICKS = 60;
    private static final double DROWN_DIRECT_SWIM_MIN_MOVE_SQ = 0.5 * 0.5;
    private long _drownSwimStartTick = Long.MIN_VALUE;
    private Vec3d _drownSwimStartPos = null;
    // (2026-09-22 垂直上浮优先) 只在进入该分支那一下打日志, 避免每 tick 刷屏。
    private boolean _loggedSwimUpOnce = false;

    // ==== (2026-09-27 用户定案: 统一靠挖头顶逃生 + 弃挖改游门槛) ====
    // 触发阈值 0.7 不变 (DROWN_ESCAPE_AIR_FRACTION)。本次统一的是【逃生动作用什么】:
    // 一律先竖着挖头顶 (从 feet.up(1) 起向上扫 DROWN_DIG_UP_MAX 格, 取第一块
    // 非空气/非水的方块), 挖得动就一直挖; 只有挖不动才允许"游出去"。
    // 动机 (用户原话): "所有的水中脱困都要挖头顶, 游出去不可靠, 地形复杂"。
    //   之前的落地只有一半 —— c5d3bf52 插了垂直挖顶, 但 fbcd9f90/b3d75bc6 加进来的
    //   DIRECT SWIM (_drownSwimTarget) 判定排在整条链最前面, 一激活就整段跳过挖顶
    //   (20260927-171238 现场 17:17:04.653 就是这么抢先的)。
    //
    // 弃挖改游判据 (用户口径: "空气在一定值时进度没超过一个值就触发游泳")：
    //   ① 空气硬线 + 进度落后: air <= 0.2 且 breakProgress < 0.2;  或
    //   ② 进度停滞: 正在挥击 (isBreakingBlock) 却连续 60 tick (3s) 进度零增长。
    // 阈值怎么来的 (不拍脑袋, 用用户实测反推)：
    //   实测基准 = 石镐 + 石头方块, 空气 50% 开挖, 满血, 掉 ~12 滴血后逃生成功。
    //   模型: maxAir 300t; 水下(无水下速掘)÷5 × 悬空÷5 = 25x 惩罚;
    //         石镐 speed=4.0, 石头 hardness=1.5 ⇒ 每 tick 进度 = (4.0/25)/1.5/30
    //         ⇒ 一格 = 281 tick ≈ 14.05s; 空气 50% = 150t = 7.5s; 空气耗尽后
    //         20t(1s) 到 -20 起掉血 2 HP/s ⇒ 7.5 + 1 + 5.55 ≈ 14.05s 完成
    //         ⇒ 掉血 ≈ 11 滴 —— 与实测 12 滴吻合 (误差 <1 滴), 模型可用。
    //   ⇒ 生存时间 T_surv(a) = 15a + 11 秒 (300a/20 秒耗尽 + 1s 宽限 + 满血 10s)。
    //   取 air 门槛 0.2: T_surv(0.2) = 14s; 本次要游 4.5 格 ≈ 2.3s + 出水 2s ≈ 4.3s
    //     ⇒ 余 ~9.7s, 够用; 再往后就必须动掉血预算了。
    //   取 progress 门槛 0.2 (与 air 同值): 按上面基准, 空气掉到 0.2 时正常进度应约
    //     90/281 ≈ 0.32 ⇒ 门槛 0.2 = "只比基准落后约 1/3 才弃挖", 既保住实测那次
    //     成功 (0.32 > 0.2 不弃), 又能挡住恒 0 的情况。
    private static final int DROWN_DIG_UP_MAX = 4;
    private static final double DROWN_DIG_BAIL_AIR_FRACTION = 0.2;
    private static final double DROWN_DIG_BAIL_PROGRESS = 0.2;
    private static final int DROWN_DIG_STALL_TICKS = 60;
    private static final int DROWN_DIG_DIAG_TICKS = 10;
    private long _drownDigLastProgressTick = Long.MIN_VALUE;
    private double _drownDigLastProgress = -1;
    private long _drownDigDiagTick = Long.MIN_VALUE;

    // ==== 盾牌看门狗 (2026-09-11, 用户复盘 21:10 死于骷髅射杀) ====
    // 每 30s 检查一次 (2026-09-22 用户 60→30): 副手+背包都没盾时, 材料够
    // (1 铁锭+6 木板)就马上合成, 材料不够跳过不管(不主动去挖铁, 用户原话"没有就跳过不管了")。
    // (2026-09-22 进洞即查) MineDownExploreTask FIRE 时调 requestShieldCheckNow,
    // 不等 30s 周期马上检查 —— 进洞=遇骷髅风险, 盾要在进洞前备好。
    // 背景: 死亡清空背包后旧逻辑没人补盾, 下次遇骷髅必吃箭。副手装备由
    // MobDefenseChain/AbstractKillEntityTask 现有反应式装备负责, 这里只管"造出来"。
    // prio 55: 抢占常规任务(50), 让位战斗(65+)/死亡恢复(70)/岩浆火溺水(90+)。
    private static final double SHIELD_WATCHDOG_INTERVAL = 30;
    private static final float SHIELD_WATCHDOG_PRIORITY = 55;
    private static final int SHIELD_IRON_REQUIRED = 1;
    private static final int SHIELD_PLANKS_REQUIRED = 6;
    // (2026-09-23) 每个原木/木头/菌柄可合成的木板数 (含去皮变种, 见 ItemHelper.LOG)。
    private static final int PLANKS_PER_LOG = 4;
    private final TimerGame _shieldWatchdogTimer = new TimerGame(SHIELD_WATCHDOG_INTERVAL);
    // (2026-09-22 进洞即查) 外部 kick 标记: true = 下一次 getPriority 不等计时
    // 马上跑盾牌检查, 消耗后复位并重置 30s 周期。
    private static boolean _shieldCheckKick = false;

    /**
     * 触发矿洞探索时由 MineDownExploreTask 调用: 马上检查一次"没盾+材料够→合成",
     * 不等下一个 30s 周期 (用户"触发矿洞探索时马上检查一下是否能做盾")。
     */
    public static void requestShieldCheckNow() {
        _shieldCheckKick = true;
    }
    // (2026-09-14 续租, 232015 场次) 已派发的盾牌合成任务引用。原实现只在计时
    // 到点那一 tick 返回 55, 下一 tick 落到方法末尾 NEGATIVE_INFINITY → 所有权
    // 54ms 内弹回 User Tasks (日志 23:21:35.983→36.037), 合成任务挂链上一 tick
    // 没跑, 卡死 "Waiting for recipe book click" → 半成品: 4 木板留 2x2 格 +
    // 合成台留输出格, 整场未清。铁律: "返回任务"≠"任务会跑完", 侧线任务必须续租。
    private Task _shieldCraftTask = null;

    // ==== 镐子维持看门狗 (2026-09-25, 实例20260925-111302实锤) ====
    // 现场链: 就近搜刮透视 (问题一, KnowledgeMemory Y 约束另案修) → baritone
    // 垂直下挖奔箱子 → 木镐挖穿途中爆耐久 (diag 11:14:41 hand=wooden_pickaxe →
    // 11:14:43 hand=empty) → baritone 导航挖块不经过 DestroyBlockTask.ToolGate /
    // MineAndCollectTask 的反应式派发点, 造镐链 (SatisfyMiningRequirementTask)
    // 永远无人派发 → 空手挖石 7 倍慢, 寻路连环失败 "Failed, blacklisting and
    // wandering" ×3, 用户手动停任务。
    // 修: 维持语义 "永远随身有镐" (同 SatisfyMiningRequirementTask 09-11
    // needsAnyPickaxeFallback 注释, 拟人原则)。背包无任何镐 → 派
    // SatisfyMiningRequirementTask(WOOD) (内部自带钻石/铁优先逻辑)。轮询 10s,
    // prio 55 (同盾牌: 抢常规 50, 让位战斗/死亡恢复/岩浆火溺水)。
    // 续租至完成; 150s 无果 = 链路死锁 (做不出工作台等) → 释放 + 5min 冷却,
    // 不焊死优先级。150s = 覆盖 SatisfyMiningRequirementTask 内部 120s 承诺锁
    // 逃生窗 (铁/钻石镐链合法工期可达 120s, 90s 会掐断健康链, 09-25 审查 M1)。
    private static final double PICKAXE_WATCHDOG_INTERVAL = 10;
    private static final float PICKAXE_WATCHDOG_PRIORITY = 55;
    private static final long PICKAXE_WATCHDOG_TIMEOUT_MS = 150_000;
    private static final long PICKAXE_WATCHDOG_COOLDOWN_MS = 5 * 60_000;
    private final TimerGame _pickaxeWatchdogTimer = new TimerGame(PICKAXE_WATCHDOG_INTERVAL);
    private static long _pickaxeWatchdogCooldownUntilMs = 0;
    private Task _pickaxeCraftTask = null;
    private long _pickaxeCraftSinceMs = 0;

    // ==== 通用进洞边沿 (2026-09-23, 用户 09:54 死于矿洞骷髅射杀复盘) ====
    // 贪心挖矿经 MineAndCollectTask→baritone 直接追矿石进天然洞, 不经过
    // MineDownExploreTask 的 FIRE kick 点; 任何任务路径(贪心/主线/纯寻路/捡物)
    // 进入天然洞都应在进洞瞬间踢一次盾牌检查。1s 节流扫描, 边沿 false→true
    // 才踢, 同一次驻留 120s 冷却不重复踢。
    private static final long CAVE_ENTRY_SCAN_INTERVAL_MS = 1000;
    private static final long CAVE_ENTRY_KICK_COOLDOWN_MS = 120000;
    private long _lastCaveEntryScanMs = Long.MIN_VALUE;
    private long _lastCaveEntryKickMs = Long.MIN_VALUE;
    private boolean _inNaturalCavePrev = false;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
    }

    private void stopFireWaterTracking(AltoClef mod) {
        if (!_wasTrackingWaterForFire) return;
        _wasTrackingWaterForFire = false;
        try {
            mod.getBlockTracker().stopTracking(Blocks.WATER);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean shouldTickEveryTick() {
        // 反射链：岩浆/火焰/溺水逃生需要 tick 级反应，不能降频。
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    /**
     * (2026-09-23 通用进洞边沿) 1s 一查 MineDownExploreTask.isInNaturalCave;
     * false→true 边沿且 120s 冷却已过 → requestShieldCheckNow (同 tick 看门狗
     * 兑付: 没盾+铁锭+6木板就合成)。必打一条 diag: 材料状态一眼可验证机制
     * 是否生效 (如本次场景会显示 hasIronIngot=false——生铁不算铁锭)。
     */
    private void detectCaveEntry(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastCaveEntryScanMs < CAVE_ENTRY_SCAN_INTERVAL_MS) return;
        _lastCaveEntryScanMs = now;
        boolean inCave = MineDownExploreTask.isInNaturalCave(mod);
        if (inCave && !_inNaturalCavePrev
                && now - _lastCaveEntryKickMs >= CAVE_ENTRY_KICK_COOLDOWN_MS) {
            _lastCaveEntryKickMs = now;
            requestShieldCheckNow();
            boolean offhandShield = mod.getPlayer().getOffHandStack().getItem() == Items.SHIELD;
            Debug.logToFile("altoclef_diag.log", "[CaveEntry] entered natural cave at "
                    + mod.getPlayer().getBlockPos().toShortString()
                    + " — shield check kicked. offhandShield=" + offhandShield
                    + " hasShield=" + mod.getItemStorage().hasItem(Items.SHIELD)
                    + " hasIronIngot=" + mod.getItemStorage().hasItem(Items.IRON_INGOT)
                    + " planks=" + mod.getItemStorage().getItemCount(ItemHelper.PLANKS));
        }
        _inNaturalCavePrev = inCave;
    }

    @Override
    public float getPriority(AltoClef mod) {
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        // Drowning
        handleDrowning(mod);

        // (2026-09-14 坠落取证) 进入自由落体的瞬间快照。用户现场"空中搭路没搭好掉进
        // 岩浆", 需要区分三种假设: 格挡抢寻路 (MobDefenseChain [ShieldVsPath]) /
        // JumpStuckResolver 误触发 / 上层拆任务。零行为, 只写诊断文件。
        traceFallEdge(mod);

        // Fire resistance potion (2026-09-14) —— 必须在 Lava Escape **之前**:
        // 补杯发生在"已有防火 + 人还在岩浆里", 而岩浆分支会先 return 100 把机会
        // 吃掉。给 105 (>100) 是为了让喝药任务不被下一 tick 的岩浆分支顶掉
        // (同类型 setTask + isEqual 保留实例 → 任务能连续拿到 tick, 见 TaskSystem)。
        float fireResDrink = tryDrinkFireResPotion(mod);
        if (fireResDrink != Float.NEGATIVE_INFINITY) return fireResDrink;

        // Lava Escape
        // (2026-09-19 16:05 用户"岩浆从高处流下来时专心放方块"; 16:05 补充: 免逃
        // 只限建围墙窗口) 豁免条件 = 有 FIRE_RESISTANCE 且围场墙施工进行中
        // (CollectBlazeRodsTask.isEnclosureBuildWorkActive) —— 二者缺一不可,
        // 其他情况(防火但不在施工)照旧逃岩浆。
        if (isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()
                && !(mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                        && CollectBlazeRodsTask.isEnclosureBuildWorkActive())) {
            setTask(new EscapeFromLavaTask());
            // (2026-09-14 防火岩浆自救取证) 2s 一条: 位置/速度/防火/baritone 是否在
            // 规划。用于区分"baritone 在游"与"baritone 又 A* 起点判据全灭"。
            long nowMs = System.currentTimeMillis();
            if (nowMs - _lastLavaEscapeLogMs > 2000) {
                _lastLavaEscapeLogMs = nowMs;
                Vec3d v = mod.getPlayer().getVelocity();
                boolean pathing = false;
                try {
                    pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
                } catch (Throwable ignored) {
                }
                Debug.logToFile("altoclef_diag.log", "[LavaEscape]\tpos="
                        + mod.getPlayer().getBlockPos().toShortString()
                        + " y=" + String.format("%.2f", mod.getPlayer().getY())
                        + " vel=" + String.format("%.2f/%.2f/%.2f", v.x, v.y, v.z)
                        + " onGround=" + mod.getPlayer().isOnGround()
                        + " fireRes=" + mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                        + " onFire=" + mod.getPlayer().isOnFire()
                        + " pathing=" + pathing);
            }
            return 100;
        }

        // Fire escape
        // (2026-09-19 16:05) 豁免同逃岩浆: 防火 + 围场墙施工中 才跳过灭火干扰,
        // 其他情况照旧。
        if (isInFire(mod) && !(mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)
                && CollectBlazeRodsTask.isEnclosureBuildWorkActive())) {
            setTask(new DoToClosestBlockTask(PutOutFireTask::new, Blocks.FIRE, Blocks.SOUL_FIRE));
            return 100;
        }

        // Drowning escape: 找空气 (置于岩浆/火焰之后, 那两者死得更快)
        float drownEscape = tryDrowningEscape(mod);
        if (drownEscape != Float.NEGATIVE_INFINITY) return drownEscape;

        // Extinguish with water
        if (mod.getModSettings().shouldExtinguishSelfWithWater()) {
            if (!(_mainTask instanceof EscapeFromLavaTask && isCurrentlyRunning(mod)) && mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE) && !mod.getWorld().getDimension().isUltrawarm()) {
                // Extinguish ourselves
                if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    BlockPos targetWaterPos = mod.getPlayer().getBlockPos();
                    if (WorldHelper.isSolid(mod, targetWaterPos.down()) && WorldHelper.canPlace(mod, targetWaterPos)) {
                        Optional<Rotation> reach = LookHelper.getReach(targetWaterPos.down(), Direction.UP);
                        if (reach.isPresent()) {
                            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
                            if (mod.getClientBaritone().getPlayerContext().isLookingAt(targetWaterPos.down())) {
                                if (mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                                    _extinguishWaterPosition = targetWaterPos;
                                    mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                                    setTask(null);
                                    return 90;
                                }
                            }
                        }
                    }
                    // 放水条件暂时不满足（脚下不实/放不了/够不着）时不再去
                    // 找远处的水——原地等待或长距离寻路只会让 bot 高优先级
                    // 卡死（2026-08-21 用户反馈：着火时原地站着不动）。
                    // 火焰 ~8 秒自然熄灭，直接放行让正常任务继续（bot 保持
                    // 移动比站着烧好）。
                    setTask(null);
                    return Float.NEGATIVE_INFINITY;
                }
                // 没有水桶：只有 32 格内有"可见的"水才值得去泡。找不到就
                // 放行正常任务（继续寻路/干活），而不是原地 wander 卡死。
                mod.getBlockTracker().trackBlock(Blocks.WATER);
                _wasTrackingWaterForFire = true;
                Optional<BlockPos> water = mod.getBlockTracker().getNearestTracking(Blocks.WATER);
                if (water.isPresent() && water.get().isWithinDistance(mod.getPlayer().getPos(), 32)) {
                    setTask(new GetToBlockTask(water.get()));
                    return 90;
                }
                setTask(null);
                return Float.NEGATIVE_INFINITY;
            } else if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null && mod.getBlockTracker().blockIsValid(_extinguishWaterPosition, Blocks.WATER)) {
                // Pick up the water
                setTask(new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), Direction.UP, _extinguishWaterPosition.down(), true));
                return 60;
            } else {
                _extinguishWaterPosition = null;
            }
        } else if (_wasTrackingWaterForFire) {
            stopFireWaterTracking(mod);
        }
        if (!mod.getPlayer().isOnFire() && _wasTrackingWaterForFire) {
            stopFireWaterTracking(mod);
        }

        // (2026-09-23) 通用进洞边沿: 任何路径进天然洞立即踢盾牌检查。必须放在
        // 看门狗兑付之前, 使 kick 同 tick 生效。
        detectCaveEntry(mod);

        // 盾牌看门狗 (2026-09-11): 每 30s 检查, 没盾+材料够 → 合成 (prio 55)。
        // (2026-09-22) 触发矿洞探索的 kick 也在此立即兑付。计时器无条件重置,
        // 材料不够也只是跳过本轮, 下轮再查。
        boolean shieldKick = _shieldCheckKick;
        if (shieldKick || _shieldWatchdogTimer.elapsed()) {
            _shieldCheckKick = false;
            _shieldWatchdogTimer.reset();
            boolean offHandShield = mod.getPlayer().getOffHandStack().getItem() == Items.SHIELD;
            // (2026-09-23 用户"木板不够可肯定有原木") 木板材料按潜力计:
            // 已合成木板 + 原木数×4 ≥ 6 (每个原木/木头/菌柄去皮与否都出4木板,
            // 派发的造盾流程会先把原木转成木板)。旧口径只数已合成木板, 有原木
            // 没木板时误判材料不够 (20260923-140827: planks=3 logs=17)。
            int plankCount = mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
            int logCount = mod.getItemStorage().getItemCount(ItemHelper.LOG);
            int plankPotential = plankCount + PLANKS_PER_LOG * logCount;
            // (2026-09-23 用户"生铁肯定也要算") 铁材料按潜力计: 铁锭+生铁,
            // 1生铁→1铁锭 —— 造盾资源链注册了 smelt(iron_ingot <- raw_iron)
            // (TaskCatalogue:207), 派发后内部自动熔炉烧铁, 无需先烧成锭。
            int ingotCount = mod.getItemStorage().getItemCount(Items.IRON_INGOT);
            int rawIronCount = mod.getItemStorage().getItemCount(Items.RAW_IRON);
            int ironPotential = ingotCount + rawIronCount;
            if (!offHandShield && !mod.getItemStorage().hasItem(Items.SHIELD)
                    && ironPotential >= SHIELD_IRON_REQUIRED
                    && plankPotential >= SHIELD_PLANKS_REQUIRED) {
                // (2026-09-14 交易让位, 232015 场次) 交易进行中插队会开背包屏打断
                // 丢金锭节奏 → 跳过本轮, 交易结束 (金锭耗尽/任务停 3s) 后下轮再合成。
                // (2026-09-25) 镐维持任务进行中也让位 (防同 tick setTask 互顶拆半成品)。
                if (TradeWithPiglinsTask.isTradeInProgress()) {
                    Debug.logMessage("[Shield-Watchdog] no shield + materials present, but piglin trading in progress — deferring to next check.");
                } else if (_pickaxeCraftTask != null && _mainTask == _pickaxeCraftTask
                        && !_pickaxeCraftTask.isFinished(mod)) {
                    // 镐子合成链在跑, 下轮 30s 再看
                } else if (_shieldCraftTask != null && _mainTask == _shieldCraftTask
                        && !_shieldCraftTask.isFinished(mod)) {
                    // (2026-09-25 续租断链修复, 215310 会话 22:01:54 实锤) 上一派发的
                    // 盾牌链还在跑 (典型: 熔炉烧铁中+守卫驻留): 绝不重新派发。
                    // newDetachedItemTask 产出的新实例与在跑实例 isEqual 相同 →
                    // setTask 判 equals 空转 (_mainTask 仍指旧实例), 但 _shieldCraftTask
                    // 引用已被换成新实例 → 续租判据 (_mainTask == _shieldCraftTask)
                    // 永假 → 下一 tick 链掉回 prio 50 被 User Tasks 抢走, 烧到一半的
                    // 熔炉被抛弃, BeatMinecraft2 重启转去挖矿探洞。跳过重派, 下方
                    // 续租块照常 return 55 保住控制权直至跑完。
                } else {
                    Debug.logMessage("[Shield-Watchdog] no shield in inventory/offhand + materials present ("
                            + ingotCount + " ingots/" + rawIronCount + " raw iron = " + ironPotential
                            + " iron; " + plankCount + " planks/" + logCount + " logs = " + plankPotential
                            + " planks potential), crafting one.");
                    // (2026-09-24 用户"干净的, 盾牌触发, 开一个熔炉, 放一个铁进去")
                    // 走 detached 隔离通道: 独立炉子只烧 1 铁, 不挂 gear 链的 x9 炉子。
                    _shieldCraftTask = TaskCatalogue.newDetachedItemTask(Items.SHIELD, 1);
                    setTask(_shieldCraftTask);
                    return SHIELD_WATCHDOG_PRIORITY;
                }
            }
        }

        // Portal stuck
        if (isStuckInNetherPortal(mod)) {
            // We can't break or place while inside a portal (not really)
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
        } else {
            // We're no longer stuck, but we might want to move AWAY from our stuck position.
            _portalStuckTimer.reset();
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
        }
        if (_portalStuckTimer.elapsed()) {
            // We're stuck inside a portal, so get out.
            // Don't allow breaking while we're inside the portal.
            setTask(new SafeRandomShimmyTask());
            return 60;
        }

        // (2026-09-14 续租) 已派发的盾牌合成未完成前持续持有 prio 55, 保证任务
        // 拿得到 tick 直至跑完。放在岩浆/火/溺水/传送门卡住等紧急分支之后: 紧急
        // 任务 (90/60) 仍可 setTask 顶掉合成 (引用比对自动放开续租), 与优先级
        // 设计一致 —— 紧急 > 合成(55) > 常规(50)。
        if (_shieldCraftTask != null) {
            if (_mainTask == _shieldCraftTask && !_shieldCraftTask.isFinished(mod)) {
                return SHIELD_WATCHDOG_PRIORITY;
            }
            // 被更高优先级紧急任务顶掉或已完成 → 放开; 材料条件仍真时下一轮
            // 看门狗 (30s 内) 会重新派发。
            _shieldCraftTask = null;
        }

        // ==== 镐子维持看门狗 (2026-09-25) 派发 ====
        // 放在盾牌续租之后: 盾牌任务活跃时上面已 return, 不会同 tick 抢 setTask。
        boolean pickaxeCraftRunning = _pickaxeCraftTask != null
                && _mainTask == _pickaxeCraftTask && !_pickaxeCraftTask.isFinished(mod);
        if (_pickaxeWatchdogTimer.elapsed()) {
            _pickaxeWatchdogTimer.reset();
            if (!pickaxeCraftRunning
                    && System.currentTimeMillis() >= _pickaxeWatchdogCooldownUntilMs
                    // 链已在跑 (如 DestroyBlockTask.ToolGate 自己派的造镐链) 不重复派
                    && !SatisfyMiningRequirementTask.isToolChainActive()
                    // "无任何镐" 判据与 ToolGate/SatisfyMiningRequirementTask.isFinished
                    // 同一函数 (miningRequirementMetInventory), 口径一致
                    && !StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD)
                    // (09-25 审查 M2) 棒料门只对非主世界生效 (下界/末地没树, 派了
                    // 也做不出来)。主世界【不设材料门】: 最典型断镐场景是"上一把
                    // 木镐合成耗尽木板/木棍", 镐坏瞬间棒料=0, 若设门则永远不触发;
                    // 主世界造镐链自身会去砍树收集 (getItemTask 链路), 派发非白派。
                    && (WorldHelper.getCurrentDimension() != adris.altoclef.util.Dimension.OVERWORLD
                        || SatisfyMiningRequirementTask.hasStickMaterial(mod))) {
                String m = "[Pickaxe-Watchdog] no pickaxe in inventory -> dispatching SatisfyMiningRequirementTask(WOOD) (dim="
                        + WorldHelper.getCurrentDimension() + " player="
                        + mod.getPlayer().getBlockPos().toShortString() + ")";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
                // (2026-09-28 续租断链修复) setTask 按 isEqual 去重: 若 _mainTask 已挂着
                // 同 requirement 的旧实例, new 出来的新实例不会成为 _mainTask → 续租判据
                // (_mainTask == _pickaxeCraftTask) 永假 → 每次重派只拿 1 tick 就让位,
                // 10s 周期空转 (20260928-221851 现场: latch 883 冻结 + 90s 零进展,
                // 断镐后造镐链永远做不完)。与盾牌续租断链修复 (0925) 同族 —— 复用
                // _mainTask 上的旧实例, 保持引用一致。
                if (_mainTask instanceof SatisfyMiningRequirementTask existing
                        && existing.equals(new SatisfyMiningRequirementTask(MiningRequirement.WOOD, "pickaxe-watchdog"))) {
                    _pickaxeCraftTask = existing;
                } else {
                    _pickaxeCraftTask = new SatisfyMiningRequirementTask(MiningRequirement.WOOD, "pickaxe-watchdog");
                }
                _pickaxeCraftSinceMs = System.currentTimeMillis();
                setTask(_pickaxeCraftTask);
                return PICKAXE_WATCHDOG_PRIORITY;
            }
        }

        // (2026-09-25) 镐维持任务续租 + 超时降级: 未完成前持续持有 prio 55 保证
        // 拿得到 tick; 150s 无果释放 + 5min 冷却 (防"做不出还每 10s 重派"焊死)。
        if (_pickaxeCraftTask != null) {
            if (_mainTask == _pickaxeCraftTask && !_pickaxeCraftTask.isFinished(mod)) {
                if (System.currentTimeMillis() - _pickaxeCraftSinceMs > PICKAXE_WATCHDOG_TIMEOUT_MS) {
                    String m = "[Pickaxe-Watchdog] pickaxe crafting exceeded 150s without success -> release + 5min cooldown";
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                    adris.altoclef.util.helpers.TimeoutLog.hit("WorldSurvivalChain.pickaxeWatchdog",
                            "150s crafting pickaxe without success, release + cooldown 300s");
                    _pickaxeCraftTask = null;
                    _pickaxeWatchdogCooldownUntilMs = System.currentTimeMillis() + PICKAXE_WATCHDOG_COOLDOWN_MS;
                } else {
                    return PICKAXE_WATCHDOG_PRIORITY;
                }
            } else {
                // 已完成或被更高优先级顶掉 → 放开; 条件仍真时下一轮 (10s) 重派
                _pickaxeCraftTask = null;
            }
        }

        return Float.NEGATIVE_INFINITY;
    }

    /**
     * 防溺水"找空气"逃生 (2026-09-10)：水中且空气 <25% 时，以 DROWN_ESCAPE_PRIORITY
     * 导航/挖穿到最近可呼吸方块。
     * (2026-09-10 13:52 场次补强) 目标空气被封在石头后时 baritone 水下寻路
     * 长期 path[present=false] (8s+ 实证)，bot 原地漂浮淹死。补两类能力:
     *  (a) GetToBlockTask 无路径持续 >1.5s → 转直线挖穿 (eye→目标连线上
     *      reach 内第一块实体方块交给 DestroyBlockTask，挖掉一块重评，逐块打穿);
     *  (b) DestroyBlockTask 挖穿期间粘性 (setTask 等值去重防重启)。
     * 任务在跑期间不换目标防抖动；失败/被打断后经 1s 重发冷却再重找。
     * 找不到目标时不动 (留旧 handleDrowning 的按跳上浮兜底)。
     */
    private float tryDrowningEscape(AltoClef mod) {
        var p = mod.getPlayer();
        if (p == null || !p.isTouchingWater() || p.getMaxAir() <= 0) {
            return Float.NEGATIVE_INFINITY;
        }
        double airFrac = (double) p.getAir() / p.getMaxAir();
        if (airFrac > DROWN_ESCAPE_AIR_FRACTION) {
            _loggedNoBreathable = false;
            _drownSwimTarget = null;
            return Float.NEGATIVE_INFINITY;
        }

        // (2026-09-22) 直接游动模式: 绕过 baritone 寻路, 面向目标前进+上浮。
        // 进入条件见下方 GetToBlockTask 无路径重评分支。
        if (_drownSwimTarget != null) {
            if (!p.isTouchingWater()) {
                _drownSwimTarget = null;
                return Float.NEGATIVE_INFINITY;
            }
            // 到达目标附近 (欧氏 < 1.5 格) → 退出直接游动, 让常规逻辑接管。
            if (p.getBlockPos().getSquaredDistance(_drownSwimTarget) < 1.5 * 1.5) {
                _drownSwimTarget = null;
                return Float.NEGATIVE_INFINITY;
            }
            // 超时无进展 → 直游被挡 (前方/头顶有方块), 回退挖穿兜底。
            long nowTick = WorldHelper.getTicks();
            if (_drownSwimStartTick != Long.MIN_VALUE && _drownSwimStartPos != null
                    && nowTick - _drownSwimStartTick >= DROWN_DIRECT_SWIM_TIMEOUT_TICKS) {
                double dx = p.getX() - _drownSwimStartPos.x;
                double dz = p.getZ() - _drownSwimStartPos.z;
                if (dx * dx + dz * dz < DROWN_DIRECT_SWIM_MIN_MOVE_SQ) {
                    String m = "[DROWN-ESCAPE] DIRECT SWIM stalled -> dig fallback player="
                            + p.getBlockPos() + " target=" + _drownSwimTarget.toShortString();
                    Debug.logMessage(m);
                    Debug.logToFile("altoclef_diag.log", m);
                    return startDigFallback(mod, p);
                }
                // 有进展: 重置计时继续直游。
                _drownSwimStartTick = nowTick;
                _drownSwimStartPos = p.getPos();
            }
            Vec3d eye = p.getEyePos();
            Vec3d target = Vec3d.ofCenter(_drownSwimTarget);
            double dx = target.x - eye.x;
            double dz = target.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // 面向目标 (保持当前 pitch, 水中水平移动方向由 yaw 决定, JUMP 负责上浮)。
            mod.getClientBaritone().getLookBehavior().updateTarget(new Rotation(yaw, p.getPitch()), true);
            var h = mod.getClientBaritone().getInputOverrideHandler();
            h.setInputForceState(Input.MOVE_FORWARD, true);
            h.setInputForceState(Input.JUMP, true);
            h.setInputForceState(Input.MOVE_BACK, false);
            h.setInputForceState(Input.MOVE_LEFT, false);
            h.setInputForceState(Input.MOVE_RIGHT, false);
            return DROWN_ESCAPE_PRIORITY;
        }

        // 已有逃生任务在跑:
        if (isCurrentlyRunning(mod)) {
            if (_mainTask instanceof DestroyBlockTask) {
                // (2026-09-27) 挖顶/挖穿期间评估"还挖不挖得动": 不合格就弃挖改游。
                if (shouldBailDigToSwim(mod, airFrac)) {
                    BlockPos bailAir = findNearestBreathable(mod);
                    if (bailAir != null) {
                        setTask(null);
                        _drownSwimTarget = bailAir;
                        _drownSwimStartTick = WorldHelper.getTicks();
                        _drownSwimStartPos = p.getPos();
                        try { mod.getClientBaritone().getPathingBehavior().forceCancel(); } catch (Throwable ignored) {}
                        String m = "[DROWN-ESCAPE] dig bailed -> SWIM OUT to " + bailAir.toShortString()
                                + " (air=" + String.format("%.2f", airFrac)
                                + " progress=" + String.format("%.2f", mod.getControllerExtras().getBreakingBlockProgress())
                                + ") player=" + p.getBlockPos();
                        Debug.logMessage(m);
                        Debug.logToFile("altoclef_diag.log", m);
                        return DROWN_ESCAPE_PRIORITY;
                    }
                }
                // 挖穿障碍中: 粘性直至挖掉或任务自身放弃。
                return DROWN_ESCAPE_PRIORITY;
            }
            if (_mainTask instanceof GetToBlockTask) {
                if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                    _drownPathlessTimer.reset();
                    return DROWN_ESCAPE_PRIORITY; // 正常导航中
                }
                if (!_drownPathlessTimer.elapsed()) {
                    return DROWN_ESCAPE_PRIORITY; // 短暂无路径(重算中), 再观察
                }
                // 无路径超 1.5s → 掉到下方重评: 直线挖穿
            } else {
                // 其他未知任务在跑, 不干预。
                return DROWN_ESCAPE_PRIORITY;
            }
        } else if (!_drownReissueTimer.elapsed()) {
            // 冷却中: 手上有活就继续拿着 (避免让权→onInterrupt→重派 的抖动)。
            return _mainTask != null ? DROWN_ESCAPE_PRIORITY : Float.NEGATIVE_INFINITY;
        }

        // ★★ (2026-09-22 用户定案: 垂直上浮优先, 2026-09-23 补"先从 feet.up(1) 抠压顶")
        // 溺水最自然的出路就是向上浮。自下往上找第一块非空气/非水的方块(压顶):
        //  - 它若是空气/水 → 直接上浮, 完全不依赖 baritone (它水下会因头顶挡块而寻路失败);
        //  - 它是可碎方块 → 挖掉这一块 (现场 (1030,19,-320) 水底, feet.up(1)=(1030,20,-320)
        //    铁矿石压顶, feet.up(2)=(1030,21,-320) 才是旧的 cap。旧代码只查 up(2), 漏掉
        //    紧贴头顶的 up(1), 反让 DestroyBlockTask 去清 cap 前方 occluder 卡死淹死);
        //  - 垂直方向从 up(1) 起被实心厚顶/不可碎封死 → 回退 findNearestBreathable。
        // (2026-09-27) 扫描深度已从"必须覆盖 feet.up(1) 与 feet.up(2) 两格"泛化为
        // DROWN_DIG_UP_MAX 格: 水底时脚在 y、身体占 y~y+2, 但 bot 半浮/下潜过后
        // 压顶完全可能落在第 3、4 格 (本次现场就是 up(3)), 只扫两格会漏。
        BlockPos feetPos = p.getBlockPos();
        // (2026-09-27 从"只看 up(1)/up(2)"泛化为 DROWN_DIG_UP_MAX 格)
        // 从 up(1) 起向上找第一块"非空气且非水"的方块 = 真正挡住上浮的压顶。
        //   · 扫描窗口内全是空气/水 → 上方是通的, 直接向上游;
        //   · 找到且可挖 → 挖它 (统一逃生动作);
        //   · 找到但不可挖 (厚顶/不可碎/区块未载) → 回退 findNearestBreathable。
        // 现场依据 20260927-171238: bot 卡在 y=34, 压顶其实在 up(3) (-42,37,-99),
        // 旧版只扫两格 ⇒ 直接落到侧向路径 ⇒ "挖头顶"这条规矩在这类现场根本不生效。
        BlockPos upBlocker = null;
        boolean upBlockerUnbreakable = false;
        for (int i = 1; i <= DROWN_DIG_UP_MAX; i++) {
            BlockPos cell = feetPos.up(i);
            if (!mod.getChunkTracker().isChunkLoaded(cell)) {
                upBlockerUnbreakable = true; // 未载 = 不能断言可挖, 按封死处理
                break;
            }
            BlockState cs = mod.getWorld().getBlockState(cell);
            if (cs.isAir() || cs.getBlock() == Blocks.WATER) continue; // 可穿过, 继续往上找
            upBlocker = cell;
            if (!WorldHelper.canBreak(mod, cell)) upBlockerUnbreakable = true;
            break;
        }
        if (upBlocker == null) {
            // 头顶扫描窗口全通 → 直接向上游, 绕过 baritone 水下寻路死区。
            _loggedNoBreathable = false;
            _drownSwimTarget = null;
            try { mod.getClientBaritone().getPathingBehavior().forceCancel(); } catch (Throwable ignored) {}
            var h = mod.getClientBaritone().getInputOverrideHandler();
            h.setInputForceState(Input.JUMP, true);
            h.setInputForceState(Input.MOVE_FORWARD, false);
            h.setInputForceState(Input.MOVE_BACK, false);
            h.setInputForceState(Input.MOVE_LEFT, false);
            h.setInputForceState(Input.MOVE_RIGHT, false);
            if (!_loggedSwimUpOnce) {
                _loggedSwimUpOnce = true;
                String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                        + " -> SWIM UP (vertical) player=" + feetPos
                        + " head=" + feetPos.up(1).toShortString();
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
            return DROWN_ESCAPE_PRIORITY;
        }
        _loggedSwimUpOnce = false;
        // (2026-09-27) 统一逃生动作 = 挖头顶: 挖掉 up1..up4 里第一块实心可碎的方块,
        // 挖通后该格变空气/水, 下一轮扫描自然继续往上一格推进, 直到通到水面。
        // skipWaterExit=true —— 否则 DestroyBlockTask 自己的"泡水漂浮就上岸"规则
        // 会把这条唯一活路切成 GetToLandTask (20260927-171238 死因, 见该类字段区注释)。
        if (upBlocker != null && !upBlockerUnbreakable) {
            _drownSwimTarget = null;
            setTask(new DestroyBlockTask(upBlocker, true));
            _drownReissueTimer.reset();
            _drownPathlessTimer.reset();
            resetDrownDigTracking();
            String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                    + " -> DIG UP cap " + upBlocker.toShortString()
                    + " (up" + (upBlocker.getY() - feetPos.getY()) + ")"
                    + " player=" + feetPos;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return DROWN_ESCAPE_PRIORITY;
        }
        // 压顶不可挖 (实心厚顶/不可碎/区块未载) → 回退 findNearestBreathable 找侧向出路。
        _drownSwimTarget = null;
        BlockPos air = findNearestBreathable(mod);
        if (air == null) {
            if (!_loggedNoBreathable) {
                _loggedNoBreathable = true;
                String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                        + " 但 " + DROWN_ESCAPE_SEARCH_RADIUS + " 格内无可达呼吸方块, 退回按跳上浮兜底 pos=" + p.getBlockPos();
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
            return Float.NEGATIVE_INFINITY;
        }
        _loggedNoBreathable = false;
        BlockPos blocker = findBlockerOnLine(mod, air);
        if (blocker != null) {
            setTask(new DestroyBlockTask(blocker, true));
            _drownReissueTimer.reset();
            _drownPathlessTimer.reset();
            resetDrownDigTracking();
            String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                    + " -> DIG THROUGH blocker " + blocker.toShortString()
                    + " (target air " + air.toShortString() + ") player=" + p.getBlockPos();
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
        } else {
            // (2026-09-22) 目标很近但 baritone 水下寻路失败 (头顶有方块等) →
            // 直接面向目标游动, 绕过 baritone A*。现场: 空气距 bot 2.2 格,
            // GetToBlockTask 每 1.5s 重发却导航不到, 30s 淹死。
            double distSq = p.getBlockPos().getSquaredDistance(air);
            if (distSq < DROWN_DIRECT_SWIM_DIST_SQ) {
                _drownSwimTarget = air;
                _drownSwimStartTick = WorldHelper.getTicks();
                _drownSwimStartPos = p.getPos();
                // 作废 baritone 当前路径, 避免它的输入控制与直接游动冲突。
                try { mod.getClientBaritone().getPathingBehavior().forceCancel(); } catch (Throwable ignored) {}
                _drownReissueTimer.reset();
                _drownPathlessTimer.reset();
                String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                        + " -> DIRECT SWIM to " + air.toShortString()
                        + " (baritone pathing failed, dist="
                        + String.format("%.1f", Math.sqrt(distSq))
                        + ") player=" + p.getBlockPos();
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            } else {
                setTask(new GetToBlockTask(air));
                _drownReissueTimer.reset();
                _drownPathlessTimer.reset();
                String m = "[DROWN-ESCAPE] air=" + String.format("%.2f", airFrac)
                        + " -> navigate to breathable " + air.toShortString()
                        + " (targetY=" + air.getY() + " vs playerY=" + p.getBlockPos().getY() + ")"
                        + " player=" + p.getBlockPos();
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
        }
        return DROWN_ESCAPE_PRIORITY;
    }

    /** 换挖顶目标时重置进度跟踪 (见字段区 2026-09-27 注释)。 */
    private void resetDrownDigTracking() {
        _drownDigLastProgressTick = WorldHelper.getTicks();
        _drownDigLastProgress = -1;
    }

    /**
     * (2026-09-27) 逃生挖顶"弃挖改游"判据 (阈值推导见字段区注释)。
     *
     * 返回 true = 放弃挖掘、改为直接游向最近呼吸点：
     *   ① air <= 0.2 且破坏进度 < 0.2 (空气硬线 + 进度落后); 或
     *   ② 正在挥击却连续 DROWN_DIG_STALL_TICKS(60t) 进度零增长
     *      (本次事故形态: DigGuard 202 tick 恒零进度)。
     * 顺带每 DROWN_DIG_DIAG_TICKS 打一条零行为诊断 [DROWN-DIG-DIAG]:
     *   "在挖却零进度"到底是进度被反复清零、还是攻击根本没生效、还是准星漂了,
     *   靠 progress / stall / breaking / crosshairOn 四列区分。不改任何行为。
     */
    private boolean shouldBailDigToSwim(AltoClef mod, double airFrac) {
        var p = mod.getPlayer();
        if (p == null) return false;
        var extras = mod.getControllerExtras();
        boolean breaking = extras.isBreakingBlock();
        double progress = extras.getBreakingBlockProgress();
        long nowTick = WorldHelper.getTicks();

        // 进度有增长 → 刷新跟踪; 没在挥击 (baritone 还在接近目标) → 同样刷新, 不计停滞。
        if (!breaking || progress > _drownDigLastProgress) {
            _drownDigLastProgress = progress;
            _drownDigLastProgressTick = nowTick;
        }
        long stallTicks = _drownDigLastProgressTick == Long.MIN_VALUE
                ? 0 : nowTick - _drownDigLastProgressTick;

        if (nowTick - _drownDigDiagTick >= DROWN_DIG_DIAG_TICKS) {
            _drownDigDiagTick = nowTick;
            try {
                BlockPos bt = extras.getBreakingBlockPos();
                Debug.logToFile("altoclef_diag.log", "[DROWN-DIG-DIAG] air="
                        + String.format("%.2f", airFrac)
                        + " progress=" + String.format("%.3f", progress)
                        + " stall=" + stallTicks + "/" + DROWN_DIG_STALL_TICKS
                        + " breaking=" + breaking
                        + " target=" + (bt == null ? "none" : bt.toShortString())
                        + " crosshairOn=" + (bt != null && LookHelper.isLookingAt(mod, bt))
                        + " onGround=" + p.isOnGround()
                        + " inWater=" + p.isTouchingWater()
                        + " pos=" + p.getBlockPos().toShortString());
            } catch (Throwable ignored) {
            }
        }

        if (!breaking) return false; // 还没开挥 (接近途中) 不算挖不动
        if (airFrac <= DROWN_DIG_BAIL_AIR_FRACTION && progress < DROWN_DIG_BAIL_PROGRESS) {
            return true;
        }
        return stallTicks >= DROWN_DIG_STALL_TICKS;
    }

    /**
     * 直线视线 eye→target 上、reach 内的第一块实体方块 (需挖穿的障碍)。
     * 中途全为空气/水 → null (baritone 可直接寻路)。
     * (2026-09-22 用户建议) 优先挖头顶: 沿直线挖若有多块障碍需两轮, 而挖头顶
     * feet.up(2) 一块通常就能腾出上浮通道, 溺水时限紧, 选更快的。
     */
    private BlockPos findBlockerOnLine(AltoClef mod, BlockPos target) {
        var p = mod.getPlayer();
        BlockPos feet = p.getBlockPos();
        // 优先头顶: 有可挖方块就先挖它 (上浮比沿直线凿穿更快)。
        BlockPos headBlocker = feet.up(2);
        if (mod.getChunkTracker().isChunkLoaded(headBlocker)) {
            BlockState hs = mod.getWorld().getBlockState(headBlocker);
            if (!hs.isAir() && !(hs.getBlock() instanceof FluidBlock)
                    && WorldHelper.canBreak(mod, headBlocker)) {
                return headBlocker.toImmutable();
            }
        }
        Vec3d eye = p.getEyePos();
        Vec3d to = Vec3d.ofCenter(target).subtract(eye);
        double len = to.length();
        if (len < 0.5) return null;
        Vec3d dir = to.normalize();
        for (double d = 0.6; d <= Math.min(len, 4.5); d += 0.4) {
            BlockPos bp = new BlockPos(eye.add(dir.multiply(d)));
            if (bp.equals(target)) break;
            if (!mod.getChunkTracker().isChunkLoaded(bp)) continue;
            BlockState st = mod.getWorld().getBlockState(bp);
            if (!st.isAir() && !(st.getBlock() instanceof FluidBlock) && WorldHelper.canBreak(mod, bp)) {
                return bp.toImmutable();
            }
        }
        return null;
    }

    /**
     * (2026-09-22) 直接游动超时无进展后的挖穿兜底。
     * 直游被挡说明前方或头顶有方块。优先挖头顶 (feet.up(2), 现场"头上有方块
     * 挡着"), 其次挖面向方向前方 1 格。找到可挖方块就 setTask(DestroyBlockTask)
     * 并返回逃生优先级; 都不可挖则清除直游状态回到常规重评。
     */
    private float startDigFallback(AltoClef mod, net.minecraft.entity.player.PlayerEntity p) {
        BlockPos feet = p.getBlockPos();
        // 头顶两格 (bot 在水中 bobbing, 头可能在 feet.up(1), 真正挡的是 feet.up(2))。
        BlockPos headBlocker = feet.up(2);
        if (mod.getChunkTracker().isChunkLoaded(headBlocker)
                && WorldHelper.canBreak(mod, headBlocker)
                && !mod.getWorld().getBlockState(headBlocker).isAir()) {
            _drownSwimTarget = null;
            setTask(new DestroyBlockTask(headBlocker, true));
            _drownReissueTimer.reset();
            _drownPathlessTimer.reset();
            resetDrownDigTracking();
            String m = "[DROWN-ESCAPE] dig HEAD blocker " + headBlocker.toShortString()
                    + " (direct swim stalled) player=" + feet;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return DROWN_ESCAPE_PRIORITY;
        }
        // 前方 1 格 (面向方向, 脚部高度)。
        Direction fwd = p.getHorizontalFacing();
        BlockPos frontBlocker = feet.offset(fwd);
        if (mod.getChunkTracker().isChunkLoaded(frontBlocker)
                && WorldHelper.canBreak(mod, frontBlocker)
                && !mod.getWorld().getBlockState(frontBlocker).isAir()) {
            _drownSwimTarget = null;
            setTask(new DestroyBlockTask(frontBlocker, true));
            _drownReissueTimer.reset();
            _drownPathlessTimer.reset();
            resetDrownDigTracking();
            String m = "[DROWN-ESCAPE] dig FRONT blocker " + frontBlocker.toShortString()
                    + " (direct swim stalled) player=" + feet;
            Debug.logMessage(m);
            Debug.logToFile("altoclef_diag.log", m);
            return DROWN_ESCAPE_PRIORITY;
        }
        // 都不可挖: 清除直游状态, 下一 tick 走常规重评 (findBlockerOnLine / GetToBlockTask)。
        _drownSwimTarget = null;
        return DROWN_ESCAPE_PRIORITY;
    }

    /**
     * 最近可呼吸方块: iterateOutwards 按距离递增遍历, 命中即返回 = 最近优先。
     * 改两遍扫描 (2026-09-16 死亡#7 实锤修复):
     *  - 第一遍只在玩家脚部Y之上的候选里找最近呼吸点。原因: 水下洞穴气室往往比
     *    水面更近(欧氏距离), 原逻辑按距离递增遍历会先命中脚下深处的气室, 把 bot
     *    一路"往下游"导航直到淹死; 向上(水面/高处气室)优先才能避免越游越深。
     *  - 第一遍无果(如玩家贴水底、唯一气室在脚下)才退回第二遍任意方向, 兜底旧场景。
     * 返回值签名与调用方保持不变。
     */
    private BlockPos findNearestBreathable(AltoClef mod) {
        BlockPos center = mod.getPlayer().getBlockPos();
        // 第一遍: 仅脚部Y之上的候选, 向上优先防"越游越深淹死"。
        BlockPos up = scanBreathable(mod, center, center.getY() + 1);
        if (up != null) return up;
        // 第二遍: 任意方向, 兜底水下深处气室场景。
        return scanBreathable(mod, center, Integer.MIN_VALUE);
    }

    /** 在 center 周围 DROWN_ESCAPE_SEARCH_RADIUS 内扫描可呼吸方块, 仅考虑 y >= minY 的候选。 */
    private BlockPos scanBreathable(AltoClef mod, BlockPos center, int minY) {
        for (BlockPos pos : BlockPos.iterateOutwards(center,
                DROWN_ESCAPE_SEARCH_RADIUS, DROWN_ESCAPE_SEARCH_RADIUS, DROWN_ESCAPE_SEARCH_RADIUS)) {
            if (pos.getY() < minY) continue;
            if (!mod.getChunkTracker().isChunkLoaded(pos)) continue;
            if (!WorldHelper.isAir(mod, pos)) continue;
            boolean connected = false;
            for (Direction d : Direction.values()) {
                BlockPos n = pos.offset(d);
                if (!mod.getChunkTracker().isChunkLoaded(n)) continue;
                BlockState ns = mod.getWorld().getBlockState(n);
                if (ns.isAir() || ns.getBlock() == Blocks.WATER) {
                    connected = true;
                    break;
                }
            }
            if (!connected) continue;
            return pos.toImmutable();
        }
        return null;
    }

    private void handleDrowning(AltoClef mod) {
        // Swim
        boolean avoidedDrowning = false;
        if (mod.getModSettings().shouldAvoidDrowning()) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                if (mod.getPlayer().isTouchingWater() && mod.getPlayer().getAir() < mod.getPlayer().getMaxAir()) {
                    // Swim up!
                    mod.getInputControls().hold(Input.JUMP);
                    //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    avoidedDrowning = true;
                    _wasAvoidingDrowning = true;
                }
            }
        }
        // Stop swimming up if we just swam.
        if (_wasAvoidingDrowning && !avoidedDrowning) {
            _wasAvoidingDrowning = false;
            mod.getInputControls().release(Input.JUMP);
            //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

    /**
     * (2026-09-14 坠落取证) 进入自由落体那一刻打一条快照, 不改变任何行为。
     * 判定口径与 MLG 水桶链一致 (vy < -0.6 且非 onGround), 只在"开始坠落"的边沿记一次。
     */
    private void traceFallEdge(AltoClef mod) {
        boolean falling = !mod.getPlayer().isOnGround() && mod.getPlayer().getVelocity().y < -0.6;
        if (falling && !_wasFalling) {
            try {
                Vec3d v = mod.getPlayer().getVelocity();
                boolean pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
                Task userTask = mod.getUserTaskChain().getCurrentTask();
                Debug.logToFile("altoclef_diag.log", "[FallTrace]\tpos="
                        + mod.getPlayer().getBlockPos().toShortString()
                        + " y=" + String.format("%.2f", mod.getPlayer().getY())
                        + " vel=" + String.format("%.2f/%.2f/%.2f", v.x, v.y, v.z)
                        + " pathing=" + pathing
                        + " userTask=" + (userTask == null ? "null" : userTask.toString()));
            } catch (Throwable ignored) {
            }
        }
        _wasFalling = falling;
    }

    /**
     * (2026-09-14 防火药水自动补给) 现在是否该喝一瓶防火药水。
     * 判据与理由见字段区注释; 这里只管"该不该", 派发与超时兜底在本方法内完成。
     *
     * @return 任务优先级 105 (必须 > Lava Escape 的 100, 否则下一 tick 就被顶掉);
     *         {@link Float#NEGATIVE_INFINITY} = 不需要 / 本轮已放弃。
     */
    private float tryDrinkFireResPotion(AltoClef mod) {
        if (mod.getPlayer() == null) return Float.NEGATIVE_INFINITY;
        boolean inLava = mod.getPlayer().isInLava();
        boolean onFire = mod.getPlayer().isOnFire();
        if (!inLava && !onFire) {
            // 脱离危险 → 本轮作废, 下次从零计时。
            _fireResDrinkSinceMs = 0;
            return Float.NEGATIVE_INFINITY;
        }
        if (ItemHelper.countFireResPotions(mod) <= 0) {
            // 没药 (本次事故现场就是这种: 两瓶都喝完了) → 交既有保命链。
            _fireResDrinkSinceMs = 0;
            return Float.NEGATIVE_INFINITY;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs < _fireResDrinkGiveUpUntilMs) return Float.NEGATIVE_INFINITY;

        StatusEffectInstance eff = mod.getPlayer().getStatusEffect(StatusEffects.FIRE_RESISTANCE);
        boolean hasRes = eff != null;
        boolean want;
        int lavaDist = -1;
        if (hasRes) {
            // ① 补杯: 药效将尽 + 身处危险。
            want = eff.getDuration() <= FIRE_RES_TOPUP_TICKS;
        } else if (inLava) {
            // ② 断档 + 在岩浆里 (2026-09-29 政策反转, 用户现场: 逃跑要穿 3 格岩浆,
            //    旧口径"故意不喝"被逃岩浆任务抢住 → 烧死):
            //    - 血 ≤ 8 → 喝 (岩浆 4/s, 喝完 1.6s 还剩 2s 余量);
            //    - 逃跑要穿过 >2 格岩浆 → 直接喝。
            //    短路: 血低先判, BFS 只在血还够时跑 (省 tick)。
            lavaDist = countLavaCellsToShore(mod);
            want = mod.getPlayer().getHealth() <= FIRE_RES_LAVA_EMERGENCY_HEALTH
                    || lavaDist > FIRE_RES_LAVA_DRINK_DISTANCE;
        } else {
            // ③ 断档 + 只是着火 (不在岩浆): 掉血 1/s, 停 1.6s 成本低, 血低就喝。
            want = mod.getPlayer().getHealth() <= FIRE_RES_EMERGENCY_HEALTH;
        }
        if (!want) {
            _fireResDrinkSinceMs = 0;
            return Float.NEGATIVE_INFINITY;
        }

        if (_fireResDrinkSinceMs == 0) {
            _fireResDrinkSinceMs = nowMs;
            Debug.logToFile("altoclef_diag.log", "[FireResDrink] start (pos="
                    + mod.getPlayer().getBlockPos().toShortString()
                    + " inLava=" + inLava + " onFire=" + onFire
                    + " health=" + String.format("%.1f", mod.getPlayer().getHealth())
                    + " hasRes=" + hasRes
                    + " lavaDist=" + (lavaDist >= 0 ? lavaDist : "n/a")
                    + " potions=" + ItemHelper.countFireResPotions(mod) + ")");
        } else if (nowMs - _fireResDrinkSinceMs > FIRE_RES_DRINK_TIMEOUT_MS) {
            // 硬顶: 喝不下去 (槽位操作失败 / 被反复打断) 就放弃 30s,
            // 绝不把 prio 105 焊死在链上 (铁律: 占链必须有超时放弃)。
            _fireResDrinkSinceMs = 0;
            _fireResDrinkGiveUpUntilMs = nowMs + FIRE_RES_DRINK_GIVEUP_MS;
            Debug.logToFile("altoclef_diag.log", "[FireResDrink] GIVE UP after "
                    + FIRE_RES_DRINK_TIMEOUT_MS + "ms (pos="
                    + mod.getPlayer().getBlockPos().toShortString()
                    + " inLava=" + inLava + " onFire=" + onFire
                    + " potions=" + ItemHelper.countFireResPotions(mod) + ")");
            return Float.NEGATIVE_INFINITY;
        }

        setTask(new DrinkFireResPotionTask());
        return 105;
    }

    /**
     * (2026-09-29 岩浆内喝药判据) 从脚下方格起、只沿岩浆格 BFS, 数"到最近可上岸格
     * 要穿过的岩浆格数" (当前格 = 1)。上岸格 = 非岩浆 且 (是水 或 下方是固体)。
     *
     * - 只在人在岩浆里时调用 (短路: 血 ≤ 8 时根本不进这里);
     * - BFS 深度上限 {@link #FIRE_RES_LAVA_BFS_LIMIT}, 节点上限 512 双保险
     *   (性能红线: 不做无界扫描); 超限视为"很远" → 返回 limit+1 → 触发喝药
     *   (找不到岸 = 逃跑距离必然很远, 喝药是保守正确方向)。
     */
    private int countLavaCellsToShore(AltoClef mod) {
        BlockPos start = mod.getPlayer().getBlockPos();
        // 层序 BFS: current 层全是"离起点 depth 格的岩浆格" (起点 depth=1)。
        ArrayDeque<BlockPos> current = new ArrayDeque<>();
        ArrayDeque<BlockPos> next = new ArrayDeque<>();
        HashSet<BlockPos> visited = new HashSet<>();
        current.add(start);
        visited.add(start);
        int depth = 1;
        int scanned = 0;
        while (!current.isEmpty()) {
            if (depth > FIRE_RES_LAVA_BFS_LIMIT || ++scanned > 512) {
                return FIRE_RES_LAVA_BFS_LIMIT + 1;
            }
            for (BlockPos pos : current) {
                for (Direction dir : Direction.values()) {
                    BlockPos nb = pos.offset(dir);
                    if (!visited.add(nb)) continue;
                    if (!mod.getWorld().getBlockState(nb).getBlock().equals(Blocks.LAVA)) {
                        // 非岩浆邻居: 是水或下方固体 = 可上岸, 岩浆格数 = 当前层 depth。
                        if (mod.getWorld().getBlockState(nb).getBlock().equals(Blocks.WATER)
                                || WorldHelper.isSolid(mod, nb.down())) {
                            return depth;
                        }
                        // 非岩浆但既非水也无法站立 (如悬空空气) → 不算岸, 不展开。
                        continue;
                    }
                    // 岩浆格 → 下一层继续。
                    if (next.size() < 512) next.add(nb);
                }
            }
            current = next;
            next = new ArrayDeque<>();
            depth++;
        }
        // 整片岩浆湖 BFS 耗尽都没找到岸 → 视为很远。
        return FIRE_RES_LAVA_BFS_LIMIT + 1;
    }

    /**
     * 是否要立刻脱离岩浆。
     *
     * (2026-09-14 防火岩浆自救, 用户现场) 旧判据把带 FIRE_RESISTANCE 的 bot 直接从
     * "岩浆危险"里剔除 → EscapeFromLavaTask 永不派发 → 它 onStart 里的
     * allowSwimThroughLava(true) 永不执行 → baritone 不认岩浆可游泳 → 玩家所在格
     * 既非整高碰撞盒也非 canWalkOn → A* 一步不展开。latest.log 09:29 现场:
     * "Open set size: 0 / PathNode map size: 1 / 22 movements considered" ×80,
     * [Baritone] Failed 刷屏, 所有移动 (含 Wander 兜底) 全部 fail → bot 不死但
     * 永久静止。与灵魂沙那次同构: A* 一步不展开永远先怀疑起点判据。
     *
     * 有防火只代表"烧不死", 不代表"可以留在岩浆里" —— bot 必须能动才能上岸。
     * 所以只要身体浸在岩浆里就派任务, 让 baritone 拿到可游泳模式自行规划上岸。
     * EscapeFromLavaGoal 给岩浆格 +100 惩罚、水格 -100, 不会主动规划穿岩浆。
     */
    private boolean isInLavaOhShit(AltoClef mod) {
        if (mod.getPlayer().isInLava()) {
            _wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !_wasInLavaTimer.elapsed();
    }
    private boolean isInFire(AltoClef mod) {
        if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            for (BlockPos pos : WorldHelper.getBlocksTouchingPlayer(mod)) {
                Block b = mod.getWorld().getBlockState(pos).getBlock();
                if (b instanceof AbstractFireBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStuckInNetherPortal(AltoClef mod) {
        return WorldHelper.isInNetherPortal(mod) && !mod.getUserTaskChain().getCurrentTask().thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask);
    }

    @Override
    public String getName() {
        return "Misc World Survival Chain";
    }

    @Override
    public boolean isActive() {
        // Always check for survival.
        return true;
    }

    @Override
    protected void onStop(AltoClef mod) {
        super.onStop(mod);
    }
}
