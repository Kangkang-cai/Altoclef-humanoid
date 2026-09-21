package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * (2026-08-25 二次重写, 修复"误判到达"与"重复丢眼")
 *
 * 关键事实 (wiki): 末影之眼无论地牢多远, 都大约朝地牢方向向上斜飞 12~15 格
 * (3D), 飞行 2~3 秒后悬浮/落地。因此:
 *   - "3D 距离 < 20 格 = 短飞 = 到达" 的判定从根上就是错的 — 眼睛永远飞
 *     ~14 格, 第二次投掷必然误判到达 (用户实测 19:06: 四只眼全部 14 格,
 *     x2 即挖进地牢, 其实根本没到)。
 *   - 真正"到达"的信号是【方向】与【垂直运动】: 距地牢 ~20 格内投掷时,
 *     眼睛会向下俯冲 (朝传送门方向的地面飞), 而不是向上斜飞。
 *
 * 新逻辑 (人类式, 跟随眼睛迭代前进):
 *  1. 以固定仰角 (向上 35°) 投掷末影之眼 (避免上一只眼的视线残留导致朝天/
 *     朝地投掷, 出现 0 距离假象)
 *  2. 记录眼睛飞行终点 (寿命结束位置) 与投掷点
 *  3. 眼睛【向上飞】→ 地牢还很远 → 朝眼睛水平方向走 60 格, 再丢眼逼近
 *  4. 眼睛【向下俯冲】(终点 y 低于投掷点 y) → 已在地牢上方附近 → 连续两次
 *     俯冲才确认到达
 *  5. 确认后记录地牢估计坐标, 交给 GoToStrongholdPortalTask 下挖探索
 */
public class LocateStrongholdCoordinatesTask extends Task {

    // ===== 自适应行走距离 (2026-08-25 用户设计 + 几何分析) =====
    // 眼睛始终指向地牢, 相邻两次眼睛方向的夹角 α 反映"本轮走了剩余距离的
    // 多少比例": 若本轮走了 W, 剩余距离约 R≈W/α(弧度) (垂直分量近似)。
    // 目标: 让下一轮步长是剩余距离的一小部分 (≤~0.5·R), 避免大步越过地牢。
    // (2026-08-26 用户设计: 方向改变程度决定"上一次距离的倍数", 非绝对档位)
    // 初始步长 240 (方向稳定=还远走大步), 之后有状态地逐次衰减:
    // 方向没差太多 (<30°) → 不衰减; 明显改变/反转 → 按改变程度缩到上一次的
    // 一半 (240→120→60…, 反转 90° 即减半, 更大封底 0.5)。修复"反向后第三
    // 次同向又走回 240"的 bug (14:27 日志: dir 反向两次却都 walkStep=240)。
    private static final double WALK_STEP_LONG = 240;
    private static final double WALK_STEP_MIN = 10;
    // 相邻两次眼睛水平方向夹角的两个阈值 (度)。
    private static final double ANGLE_THRESHOLD_MID = 30;
    private static final double ANGLE_THRESHOLD_REVERSED = 90;
    // 投掷仰角: 固定向上 35°。旧逻辑用当前视线 (常残留上一只眼的朝向) 投掷,
    // 可能朝天/朝地 → 眼睛 0 距离落地 (0 距离假象) 或方向不稳定。
    private static final float THROW_PITCH = -35f;
    // 眼睛"向下俯冲"判定: 终点 y 比投掷点 y 低超过该值才认为俯冲。远距离时
    // 眼睛向上飞 ~15 格 (终点 y 显著高于投掷点), 不会触发; 仅在地牢上方附近
    // 眼睛才朝地面下坠。
    private static final double EYE_DIVE_DOWN_MARGIN = -1;
    // (2026-08-26 安全兜底) 俯冲逼近走 1/2 递减但始终没垂直 → 多次俯冲后就近开挖,
    // 防无限循环。正常收敛 2~4 次就垂直, 6 次是宽松上限。
    private static final int EYE_DIVE_MAX_COUNT = 6;
    // 眼睛首次俯冲后, 朝眼睛水平方向走的小步长 (2026-08-26 用户修改: 原来 20
    // 格太大, 接近地牢时应小步逼近 — 首步 5 格, 之后每次俯冲距离减半 (2 格),
    // 直到眼睛近乎垂直直接下挖)。
    private static final double EYE_DIVE_STEP = 5;
    // 俯冲接近的 1/2 递减下限 (每次俯冲走步减半, 最小 2 格)。
    private static final double EYE_DIVE_STEP_MIN = 2;
    // 眼睛水平飞行距离小于此值视为"没飞" (投掷失败/感知异常), 不算方向重新丢。
    private static final double EYE_MIN_HORIZONTAL_DISTANCE = 3;
    // (2026-08-26 修复) 眼睛实体 pop 成掉落物后继续下落, 等待掉落物落定的最大
    // tick 数 (~2.5s, 下落约 1~2s)。落定后终点 y = 掉落物最低点, 用于俯冲判定。
    private static final int EYE_ITEM_SETTLE_MAX_TICKS = 50;
    // (2026-08-26 用户设计) 眼睛近乎垂直向下 = 正下方就是地牢, 一次确认直接下挖。
    // 水平距 bot <此值 + 相对 bot 的俯冲角 ≥ EYE_DIRECTLY_BELOW_ANGLE (约 89°,
    // 用户指定"角度至少要 89 度左右才开挖", 避免俯冲但不够垂直就挖错位置)。
    private static final double EYE_DIRECTLY_BELOW_HDIST = 2;
    private static final double EYE_DIRECTLY_BELOW_ANGLE = 89.0;

    private final int _targetEyes;
    private Entity _currentThrownEye = null;
    private Vec3d _eyeStartPos = null;
    private Vec3d _eyeEndPos = null;
    // (2026-08-26 诊断) 眼睛飞行期间的峰值高度, 用于判断弧线高低 (高弧=还远,
    // 平弧/下坠=接近地牢)。用户实测"丢出就向下飞"但日志 vertical delta +7,
    // 需轨迹数据确认是测量问题还是眼睛真的上冲。
    private double _eyePeakY = 0;
    // (2026-08-26 修复: 眼睛下落没被监测) 眼睛实体 pop 点=抛物线顶端, 之后
    // 眼睛掉落物继续下落 (可能沉入地牢区域)。等待掉落物落定并记录其最低点。
    private int _itemSettleWaitTicks = 0;
    // (2026-08-26 用户设计: 直接看眼睛轨迹) 眼睛飞行期间是否曾近乎垂直向下
    // (水平距 bot <3 格且在下方) — 正下方就是地牢, 一次确认直接下挖。
    // 19:04 日志实锤: 垂直向下飞被 horizontalDist<3 判成"没水平飞"→ rethrow 死循环。
    private boolean _eyeWasDirectlyBelow = false;
    private Vec3d _strongholdEstimatePos = null;
    // 眼睛飞完后的下一步动作 (朝眼睛方向走)。【持久子任务】: 每 tick 返回同一
    // 个行走任务直到真正走完 (接近目标 XZ), 绝不一次性消费 — 否则下 tick 走到
    // 丢眼分支返回 null, 任务系统会把行走子任务 STOP 掉, 根本没走就丢下一只眼
    // (用户实测 20:39: 每只眼丢完 1 tick 后 "GetToXZ STOP (clean)" 立刻再丢)。
    private Task _pendingNext = null;
    // 当前行走目标 XZ (用于"已接近=走完"判定, 防精确踩点被地形挡住卡死)。
    private BlockPos _walkTarget = null;
    // 连续"眼睛向下俯冲"次数, 达到阈值才确认到达地牢。
    private int _diveCount = 0;
    // (2026-08-26 用户设计) 俯冲接近的递减步长: 首次俯冲走 5 格, 之后每次减半 (2)。
    private double _diveWalkStep = EYE_DIVE_STEP;
    // 上一只眼的水平方向 (单位向量, y=0), 用于计算相邻两次眼睛方向的夹角
    // 以决定下一步走多远。null = 无先例 (首只眼/没飞) → 走 240 大步。
    private Vec3d _prevEyeDir = null;
    // (2026-08-26 用户设计) 当前行走步长 (有状态, 随方向改变逐次衰减, 不再用
    // 绝对档位): 方向没差太多 → 不衰减; 明显改变/反转 → 按改变程度缩小到上
    // 一次的一半 (240→120→60…)。修复"反向后第三次同向又走回 240"的 bug。
    private double _currentWalkStep = WALK_STEP_LONG;
    private final TimerGame _throwTimer = new TimerGame(5);
    // 丢眼环境缓存 (3 秒复查一次), 避免每 tick 扫 20 格半径太贵。
    private boolean _throwAreaOpen = true;
    private final TimerGame _throwAreaTimer = new TimerGame(3);

    /** (2026-09-21 用户拍板) 末影之眼低于此值才触发"重收集", 且补齐到本值。 */
    private static final int MIN_EYES_BEFORE_RECOLLECT = 10;
    /**
     * 缺眼确认窗 (ms): 眼数必须【持续】低于阈值这么久才派发收集链。
     * 理由见 onTick 丢眼块注释 —— 跨维度/物品栏刷新会让 hasItem/getItemCount 瞬时读空。
     * static: 任务实例可能被 TaskCatalogue/父任务重建, 实例字段不足以跨实例保护。
     */
    private static final long EYE_SHORTAGE_CONFIRM_MS = 2000;
    private static long _eyeShortageSinceMs = 0;

    public LocateStrongholdCoordinatesTask(int targetEyes) {
        _targetEyes = targetEyes;
    }

    public boolean isSearching() {
        return _eyeStartPos != null;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to overworld");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }

        // 捡回地上掉落的末影之眼 (丢完先捡回循环利用, 不依赖"背包眼睛数")。
        // 只在没有眼睛实体在飞时捡 (避免刚丢出去还没落地就被当成"待捡"打断记录方向)。
        // (2026-08-26 修复) 船渡期间 (BoatCrossingTask 激活) 不派发捡眼:
        // 船会驶过落水的眼睛靠碰撞自动拾取, 派发捡眼会让 bot 下船去捡
        // (海中央下船困水里)。
        // (2026-08-26 用户实测) 丢眼下船后不捡眼睛(被方向调整任务拦截):
        // 直接扫世界找附近(6格)的掉落眼睛, 不依赖 itemDropped 缓存(可能延迟/
        // 漏检); 骑船时不捡(靠船驶过自动拾取), 下船后离眼睛近优先捡。
        boolean ridingBoat = mod.getPlayer() != null
                && mod.getPlayer().getVehicle() instanceof BoatEntity;
        // (2026-08-26 修复: itemDropped 缓存陈旧且双向不可靠 — 既会漏检(眼睛落地
        // 后缓存不刷新) 也会残留 true(捡走/消失后仍 true), 残留时门控每 tick 触发
        // PickupDroppedItemTask, bot 卡死原地 (12:30 日志实锤:
        // "Pickup gate FIRED (itemDropped=true, eyeInReach=false)" 刷屏 16s)。
        // 捡眼触发只用【世界扫描 eyeInReach】, 不用 itemDropped 缓存。
        boolean eyeInReach = !ridingBoat && findNearbyDroppedEye(mod, 32) != null;
        boolean eyeFlying = mod.getEntityTracker().entityFound(EyeOfEnderEntity.class);
        // (2026-08-26 关键日志) 追溯捡眼门控: 为什么"看到眼睛却不捡"。
        if (eyeInReach && !eyeFlying && (_pendingNext instanceof BoatCrossingTask)) {
            Debug.logMessage("[Locate] Pickup gate BLOCKED: dropped eye present, pending="
                    + (_pendingNext == null ? "null" : _pendingNext.getClass().getSimpleName())
                    + " (eyeInReach=" + eyeInReach + ", flying=" + eyeFlying
                    + ", ridingBoat=" + ridingBoat + "). Boat will drive over it.");
        }
        if (eyeInReach && !eyeFlying && !(_pendingNext instanceof BoatCrossingTask)) {
            setDebugState("Picking up dropped ender eye.");
            Debug.logMessage("[Locate] Pickup gate FIRED: returning PickupDroppedItemTask (eyeInReach="
                    + eyeInReach + ", flying=" + eyeFlying + ", pending="
                    + (_pendingNext == null ? "null" : _pendingNext.getClass().getSimpleName()) + ").");
            return new PickupDroppedItemTask(Items.ENDER_EYE, _targetEyes);
        }

        // 执行行走子任务 (持久): 每 tick 返回同一个行走任务直到走完 (接近
        // 目标 XZ), 走完才丢下一只眼。捡眼分支在前面, 会先捡眼再继续走。
        if (_pendingNext != null) {
            // (2026-08-25 开船) 船渡期间不触发"接近目标"判定 (目标可能在海上),
            // 完全交给 BoatCrossingTask 自己的远岸/重定位逻辑; 只有它完成
            // (isFinished) 才结束, 避免 Locate 在船上丢眼+走路捡眼崩溃。
            boolean nearTarget = !(_pendingNext instanceof BoatCrossingTask)
                    && _walkTarget != null
                    && Math.abs(mod.getPlayer().getX() - _walkTarget.getX()) <= 5
                    && Math.abs(mod.getPlayer().getZ() - _walkTarget.getZ()) <= 5;
            if (nearTarget) {
                // 已接近目标 (防精确踩点被地形挡住卡死), 走完 → 丢下一只眼。
                Debug.logMessage("[Locate] Walk done (near target " + _walkTarget.getX() + "," + _walkTarget.getZ() + "), ready for next eye.");
                _pendingNext = null;
                _walkTarget = null;
            } else if (_pendingNext.isFinished(mod)) {
                Debug.logMessage("[Locate] Walk done (isFinished " + _pendingNext.getClass().getSimpleName() + "), ready for next eye.");
                _pendingNext = null;
                _walkTarget = null;
            } else {
                return _pendingNext;
            }
        }

        // 有眼睛实体在飞 → 更新飞行终点 + 视角跟踪眼睛。
        if (mod.getEntityTracker().entityFound(EyeOfEnderEntity.class)) {
            if (_currentThrownEye == null || !_currentThrownEye.isAlive()) {
                _currentThrownEye = mod.getEntityTracker().getTrackedEntities(EyeOfEnderEntity.class).get(0);
                Debug.logMessage("Eye thrown: tracking eye at " + (int) _currentThrownEye.getPos().x + ", " + (int) _currentThrownEye.getPos().z);
            }
            _eyeEndPos = _currentThrownEye.getPos();
            _eyePeakY = Math.max(_eyePeakY, _eyeEndPos.y);
            // (2026-08-26 用户设计: 直接看眼睛轨迹) 眼睛近乎垂直向下 (水平距 bot
            // 很小 + 角度≥89°) → 正下方就是地牢。飞行中实时检测, 飞完直接确认。
            // 19:04 日志实锤: 垂直向下飞被 horizontalDist<3 判成"没水平飞"→
            // rethrow 死循环。19:26 用户实测: 俯冲但角度不够 (没到 89°) 不能挖。
            double eyeHDist = Math.sqrt(
                    (_eyeEndPos.x - mod.getPlayer().getX()) * (_eyeEndPos.x - mod.getPlayer().getX())
                            + (_eyeEndPos.z - mod.getPlayer().getZ()) * (_eyeEndPos.z - mod.getPlayer().getZ()));
            double eyeVDist = mod.getPlayer().getY() - _eyeEndPos.y; // 眼睛低于 bot 的距离
            if (eyeVDist > 1.0 && eyeHDist < EYE_DIRECTLY_BELOW_HDIST
                    && Math.toDegrees(Math.atan2(eyeVDist, eyeHDist)) >= EYE_DIRECTLY_BELOW_ANGLE) {
                if (!_eyeWasDirectlyBelow) {
                    Debug.logMessage("[Locate] Eye nearly directly below bot (hDist=" + (int) eyeHDist
                            + ", vDist=" + (int) eyeVDist + ", angle=" + (int) Math.toDegrees(Math.atan2(eyeVDist, eyeHDist))
                            + "): stronghold directly below, will confirm.");
                }
                _eyeWasDirectlyBelow = true;
            }
            // (2026-08-25 用户指定) 视角跟踪末影之眼, 让屏幕显示眼睛飞行方向。
            LookHelper.lookAt(mod, _currentThrownEye.getPos());
            setDebugState("Waiting for eye to travel.");
            return null;
        }

        // 眼睛飞完 (实体消失: 悬浮结束/落地/破碎) → 决定下一步。
        if (_currentThrownEye != null && _eyeEndPos != null && _eyeStartPos != null) {
            // (2026-08-26 修复: 眼睛下落没被监测) 末影之眼实体在抛物线顶端
            // "pop" 成掉落物, 用户看到的"向下飞/掉进土里"是 pop 后的掉落物
            // 下落到地下 (沉入地牢区域)。只测实体 pop 点会 miss 下落 → 接近
            // 地牢时终点 y 仍高于投掷点, 俯冲判定不触发 (17:14 日志实锤:
            // verticalDelta=+7, startY=78, endY=86, 用户实测眼睛掉进土里)。
            // 追踪掉落物的下落, 终点 y 取其最低点, 直到落定 (或超时)。
            ItemEntity fallingEye = findNearbyDroppedEye(mod, 48);
            if (fallingEye != null && !fallingEye.isOnGround() && !fallingEye.isTouchingWater()) {
                if (fallingEye.getY() < _eyeEndPos.y) {
                    _eyeEndPos = new Vec3d(_eyeEndPos.x, fallingEye.getY(), _eyeEndPos.z);
                }
                // (2026-08-26 修复: 眼睛实体短飞就 pop, 主要下落发生在掉落物阶段
                // — 20:21 日志实锤: horizontal=0, verticalDelta=-5, 但实体追踪没
                // 捕捉到低于 bot 的飞行 (短飞即 pop), 掉落物下落到正下方才算"垂直
                // 向下")。掉落物下落阶段也做垂直向下检测 → 确认地牢。
                double hFalling = Math.sqrt(
                        (fallingEye.getX() - mod.getPlayer().getX()) * (fallingEye.getX() - mod.getPlayer().getX())
                                + (fallingEye.getZ() - mod.getPlayer().getZ()) * (fallingEye.getZ() - mod.getPlayer().getZ()));
                if (hFalling < EYE_DIRECTLY_BELOW_HDIST && fallingEye.getY() < mod.getPlayer().getY() - 1.0) {
                    if (!_eyeWasDirectlyBelow) {
                        Debug.logMessage("[Locate] Eye item falling nearly directly below bot (hDist=" + (int) hFalling
                                + "): stronghold directly below, will confirm.");
                    }
                    _eyeWasDirectlyBelow = true;
                }
                if (++_itemSettleWaitTicks <= EYE_ITEM_SETTLE_MAX_TICKS) {
                    setDebugState("Waiting for eye item to settle.");
                    return null;
                }
                _itemSettleWaitTicks = 0;
            } else {
                _itemSettleWaitTicks = 0;
            }
            // (2026-08-26 修复: 丢完眼不捡就开船) 眼睛刚落定, 优先捡回循环利用
            // (每颗要末影珍珠+烈焰粉)。此时 _pendingNext 还是旧任务 (或 null),
            // 捡眼不受"船渡门控"影响; 不消费眼睛数据, 捡完下 tick 再继续处理。
            // 否则 setupWalk 建出 BoatCrossingTask 后, 捡眼门控会被
            // !(_pendingNext instanceof BoatCrossingTask) 挡死, 眼睛留在原地
            // (12:20 日志: "Dropped eye present but boat crossing active" 刷屏)。
            ItemEntity landedEye = findNearbyDroppedEye(mod, 32);
            if (landedEye != null) {
                setDebugState("Picking up dropped eye before walking.");
                Debug.logMessage("[Locate] Eye landed at (" + (int) landedEye.getX() + ", " + (int) landedEye.getY() + ", "
                        + (int) landedEye.getZ() + "), picking it up BEFORE walking/boat. pending="
                        + (_pendingNext == null ? "null" : _pendingNext.getClass().getSimpleName()) + ".");
                return new PickupDroppedItemTask(Items.ENDER_EYE, _targetEyes);
            }
            Vec3d delta = _eyeEndPos.subtract(_eyeStartPos);
            double horizontalDist = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            double verticalDelta = _eyeEndPos.y - _eyeStartPos.y;
            Vec3d eyeEndPos = _eyeEndPos;
            Vec3d eyeStartPos = _eyeStartPos;
            double peakY = _eyePeakY;
            // (2026-08-26 诊断) 轨迹: startY/endY/peakY 判断弧线高低。
            Debug.logMessage("[Locate] Eye flew: horizontal=" + (int) horizontalDist
                    + ", verticalDelta=" + (int) verticalDelta
                    + ", startY=" + (int) eyeStartPos.y
                    + ", endY=" + (int) eyeEndPos.y
                    + ", peakY=" + (int) peakY + ".");

            // (2026-08-26 用户设计: 直接看眼睛轨迹) 眼睛近乎垂直向下 = 正下方就是
            // 地牢, 一次确认直接下挖。两种信号合并:
            // ① _eyeWasDirectlyBelow: 飞行/下落中实时检测 (水平距 bot 很小 + 角度
            //     ≥89°, 见上)。
            // ② straightDown: 眼睛几乎没有水平位移 (horizontal<3) 且向下飞
            //    (verticalDelta<0) → 垂直向下。用户实测: 眼睛垂直下落到脚下方块,
            //    掉落物被实心地面弹起, 发射点与最终点只差 ~1 格 — 垂直位移信号小,
            //    但"水平≈0 + 向下"本身就是"正下方就是地牢"的可靠信号, 不依赖精确
            //    的垂直距离 (20:21 日志: horizontal=0, verticalDelta=-5 仍被
            //    horizontalDist<3 判成"没水平飞"重丢)。
            boolean straightDown = horizontalDist < EYE_MIN_HORIZONTAL_DISTANCE && verticalDelta < -0.3;
            if (_eyeWasDirectlyBelow || straightDown) {
                // (2026-08-26 用户修正: 下挖 XZ 应选眼睛正下方, 不是 bot 当前位置)
                // 眼睛垂直向下, 地牢在眼睛正下方。用眼睛落点 XZ (竖直投影) 作为
                // 下挖坐标, 防止 bot 捡眼/走动后当前位置偏离眼睛正下方, 挖到别处
                // 好挖的位置 (用户实测: 开始挖时选了另外一处好挖的, 没选眼睛下方)。
                _strongholdEstimatePos = new Vec3d(eyeEndPos.x, 0, eyeEndPos.z);
                Debug.logMessage("[Locate] Eye flew straight down (horizontal=" + (int) horizontalDist
                        + ", verticalDelta=" + (int) verticalDelta + "): stronghold directly below, CONFIRMING at "
                        + (int) _strongholdEstimatePos.getX() + ", " + (int) _strongholdEstimatePos.getZ() + ".");
                _currentThrownEye = null;
                _eyeStartPos = null;
                _eyeEndPos = null;
                _eyePeakY = 0;
                _eyeWasDirectlyBelow = false;
                return null;
            }

            // 眼睛水平方向 (单位向量, y=0) — 指向地牢。
            Vec3d dir = horizontalDist > 0.001
                    ? new Vec3d(delta.x / horizontalDist, 0, delta.z / horizontalDist)
                    : null;
            _currentThrownEye = null;
            _eyeStartPos = null;
            _eyeEndPos = null;
            _eyePeakY = 0;
            _eyeWasDirectlyBelow = false;

            if (horizontalDist < EYE_MIN_HORIZONTAL_DISTANCE || dir == null) {
                // 眼睛几乎没水平飞且没向下 (投掷失败/垂直朝天/感知异常): 重新丢,
                // 不算方向。(垂直向下已在上面 straightDown 分支确认, 到不了这里。)
                setDebugState("Eye didn't fly horizontally, rethrowing.");
                _diveCount = 0;
                _diveWalkStep = EYE_DIVE_STEP;
                _prevEyeDir = null;
            } else if (verticalDelta < EYE_DIVE_DOWN_MARGIN) {
                // (2026-08-26 用户修改: 俯冲≠垂直, 不能直接开挖) 眼睛向下俯冲
                // 只说明接近地牢, 还没完全垂直向下。走 1/2 递减步 (5→2→2...)
                // 逼近, 直到眼睛近乎垂直 (hDist<3, 由上方 _eyeWasDirectlyBelow
                // 分支) 才确认下挖。不再按俯冲次数直接开挖 — 19:24 日志实锤:
                // 俯冲但未垂直就 "Going to y=45" 挖错位置。
                _diveCount++;
                _prevEyeDir = dir;
                Debug.logMessage("Eye dove down (vertical " + (int) verticalDelta + "), dive-count=" + _diveCount);
                if (_diveCount >= EYE_DIVE_MAX_COUNT) {
                    // 安全兜底: 多次俯冲仍没垂直 (地形/方向噪音) → 就近开挖, 防死循环。
                    _strongholdEstimatePos = eyeEndPos;
                    Debug.logMessage("[Locate] Dive x" + _diveCount + " (max): digging here. Estimate at "
                            + (int) eyeEndPos.x + ", " + (int) eyeEndPos.z);
                } else {
                    Vec3d target = eyeEndPos.add(dir.multiply(_diveWalkStep));
                    Debug.logMessage("[Locate] Dive (count=" + _diveCount + "), walking " + (int) _diveWalkStep
                            + " blocks toward (" + (int) target.x + "," + (int) target.z + ") for vertical confirmation.");
                    setupWalk(mod, target);
                    _diveWalkStep = Math.max(EYE_DIVE_STEP_MIN, _diveWalkStep * 0.5);
                }
            } else {
                // 眼睛正常向上斜飞向地牢方向 → 按方向变化自适应走一段, 再丢眼逼近。
                _diveCount = 0;
                _diveWalkStep = EYE_DIVE_STEP;
                double walkStep = chooseWalkDistance(dir);
                Vec3d target = eyeEndPos.add(dir.multiply(walkStep));
                Debug.logMessage("[Locate] Eye flew up normally: horizontal=" + (int) horizontalDist
                        + ", dir=(" + String.format("%.2f", dir.x) + "," + String.format("%.2f", dir.z)
                        + "), walkStep=" + (int) walkStep + ", target=(" + (int) target.x + "," + (int) target.z + ").");
                setupWalk(mod, target);
            }
            // 下 tick 若地上有眼睛掉落会先捡回, 再执行 _pendingNext。
            return null;
        }

        // 丢末影之眼。
        if (_strongholdEstimatePos == null) {
            // (2026-09-20 用户"寻找地牢的远征不该在地下派发") 地表硬前置闸。
            // 旧实现把 isDeepUnderground 塞在 isThrowSurroundingsOpen 的【内层】:
            // 投掷环境一旦通过 (大溶洞/深板岩大厅/大峡谷底 —— 头顶 3 格是空气、
            // 20 格内也没有 16 格以上山体) 就再也查不到地表判据 → bot 就地丢眼,
            // 紧接着 setupWalk 派 240 格远征, 全程在地下 (GetToXZ 走 GoalXZ, 无 Y
            // 约束 → baritone 只能挖穿/贴墙, 跨海段还依赖水面)。
            // 现在前移为硬前置: 只要判在地下, 先回地表再谈投掷环境/远征。
            // 判据复用全项目唯一口径 WorldHelper.isDeepUnderground (= SurfaceHelper
            // 迟滞版), 与 GoToSurfaceTask.isFinished 同源, 不会来回乒乓。
            // 已知代价: 地牢就在正下方 (眼睛本会垂直向下直接确认) 时会先回地表再
            // 下来, 多一次往返; 若实测往返过多, 回退点 = 把本闸移到 setupWalk
            // (只拦"确实要远征"的那一步)。
            if (WorldHelper.isDeepUnderground(mod)) {
                setDebugState("Digging up to open sky before stronghold expedition.");
                return new GoToSurfaceTask();
            }
            // (2026-09-21 用户拍板) 只有末影之眼 < 10 个时才触发"重收集", 且补齐到 10。
            // 旧实现是 hasItem(≥1 就放行), 两个缺陷:
            //   ① 只剩十几个眼时照样会丢眼(够走到地牢), 但任何一次瞬时读空 —— 跨维度后
            //      物品栏同步空窗, 实锤 20260921-091405: 09:18:30 刚用 6 珍珠+6 粉合成到
            //      16 眼, 09:18:46.742 hasItem 却读成 false —— 都立刻派发整条
            //      Craft ender_eye → blaze_powder → Collect 1 blaze rods → Going to nether,
            //      1.5s 后才自愈 (09:18:48.302 回到 Throwing eye)。bot 被白白打发去下界。
            //   ② 收集目标 getItemTask(EYE, 1) 与判定同源: 已有 9 个眼时"至少 1 个"立刻
            //      满足 → 任务当拍完成 → 每 tick 派发一次空收集 (死循环)。
            // 现在: 阈值判定 + 补齐到阈值 + 确认窗 (瞬时读空不许触发)。
            int eyesHeld = mod.getItemStorage().getItemCount(Items.ENDER_EYE);
            if (eyesHeld < MIN_EYES_BEFORE_RECOLLECT) {
                long nowMs = System.currentTimeMillis();
                if (_eyeShortageSinceMs == 0) _eyeShortageSinceMs = nowMs;
                if (nowMs - _eyeShortageSinceMs < EYE_SHORTAGE_CONFIRM_MS) {
                    setDebugState("Ender eye count low (" + eyesHeld + "/" + MIN_EYES_BEFORE_RECOLLECT
                            + "), re-checking before collecting...");
                    return null;
                }
                setDebugState("Collecting eye of ender (" + eyesHeld + "/" + MIN_EYES_BEFORE_RECOLLECT + ").");
                return TaskCatalogue.getItemTask(Items.ENDER_EYE, MIN_EYES_BEFORE_RECOLLECT);
            }
            _eyeShortageSinceMs = 0;
            // (2026-08-25 用户要求) 不需要固定高度: 只要四周空旷 (头顶无遮挡 +
            // 20 格内无巨大山体) 就丢, 不再强制爬升到固定高度。山脚/峡谷 →
            // 短暂 wander 找开阔地 (眼睛是实体不受方块碰撞)。
            // (深地下分支已上移到本块开头的地表硬闸, 此处不再重复判。)
            if (!isThrowSurroundingsOpen(mod)) {
                setDebugState("Wandering to find open area to throw eye.");
                return new TimeoutWanderTask(5);
            }
            setDebugState("Throwing eye.");
            if (mod.getSlotHandler().forceEquipItem(Items.ENDER_EYE)) {
                if (_throwTimer.elapsed()) {
                    // (2026-08-25 修复投掷角度) 固定仰角投掷: 用当前 yaw + 固定
                    // pitch 向上 35°, 保证眼睛以稳定抛物线飞向地牢方向; 等相机
                    // 真正转到该角度再丢, 避免朝地/朝天投掷 (0 距离假象)。
                    Rotation throwRotation = new Rotation(LookHelper.getLookRotation().getYaw(), THROW_PITCH);
                    LookHelper.lookAt(mod, throwRotation);
                    if (LookHelper.isLookingAt(mod, throwRotation) && LookHelper.tryAvoidingInteractable(mod)) {
                        assert MinecraftClient.getInstance().interactionManager != null;
                        MinecraftClient.getInstance().interactionManager.interactItem(mod.getPlayer(), mod.getWorld(), Hand.MAIN_HAND);
                        // 丢出瞬间记录投掷点作为眼睛飞行起点。
                        _eyeStartPos = mod.getPlayer().getPos().add(0, 1.6, 0);
                        _eyePeakY = _eyeStartPos.y;
                        _eyeWasDirectlyBelow = false;
                        _throwTimer.reset();
                        Debug.logMessage("[Locate] Threw eye from (" + (int) _eyeStartPos.x + "," + (int) _eyeStartPos.y + "," + (int) _eyeStartPos.z
                                + "), yaw=" + (int) throwRotation.getYaw() + ", pitch=" + (int) throwRotation.getPitch() + ".");
                    }
                } else {
                    MinecraftClient.getInstance().interactionManager.stopUsingItem(mod.getPlayer());
                }
            } else {
                Debug.logWarning("Failed to equip eye of ender to throw.");
            }
            return null;
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    /**
     * 丢眼环境检查 (2026-08-25 用户要求): 四周空旷即可丢, 不需要固定高度。
     * 满足条件: 头顶 3 格无实心遮挡 (不在洞穴/屋檐下) + 20 格半径 (步长 4
     * 采样) 内无高于玩家 16 格以上的巨大山体。结果缓存 3 秒。
     */
    private boolean isThrowSurroundingsOpen(AltoClef mod) {
        if (!_throwAreaTimer.elapsed()) {
            return _throwAreaOpen;
        }
        _throwAreaTimer.reset();
        BlockPos p = mod.getPlayer().getBlockPos();
        for (int dy = 1; dy <= 3; dy++) {
            if (WorldHelper.isSolid(mod, p.up(dy))) {
                _throwAreaOpen = false;
                return false;
            }
        }
        for (int dx = -20; dx <= 20; dx += 4) {
            for (int dz = -20; dz <= 20; dz += 4) {
                if (dx * dx + dz * dz > 400) continue;
                for (int dy = 16; dy <= 32; dy += 8) {
                    if (WorldHelper.isSolid(mod, p.add(dx, dy, dz))) {
                        _throwAreaOpen = false;
                        return false;
                    }
                }
            }
        }
        _throwAreaOpen = true;
        return true;
    }

    /**
     * 设置朝目标 XZ 的行走子任务 (2026-08-25 开船过海): 若从玩家到目标的
     * 直线路径横跨 ≥30 格连续水面 → 用 BoatCrossingTask 开船, 否则 GetToXZ
     * 直接走。
     */
    private void setupWalk(AltoClef mod, Vec3d target) {
        BlockPos targetXZ = new BlockPos((int) target.x, 0, (int) target.z);
        if (WorldHelper.isLargeWaterCrossing(mod, mod.getPlayer().getBlockPos(), targetXZ, 30)) {
            // rethrowEyes=true: 海太大时驾驶中定期丢眼重定位方向 (用户设计)。
            Debug.logMessage("[Locate] setupWalk: large water to (" + targetXZ.getX() + "," + targetXZ.getZ()
                    + ") from player (" + mod.getPlayer().getBlockPos().getX() + "," + mod.getPlayer().getBlockPos().getZ()
                    + ") → BoatCrossingTask(rethrowEyes=true).");
            setDebugState("Large water ahead, crossing by boat to (" + targetXZ.getX() + ", " + targetXZ.getZ() + ")");
            _pendingNext = new BoatCrossingTask(targetXZ, true);
        } else {
            Debug.logMessage("[Locate] setupWalk: walk to (" + targetXZ.getX() + "," + targetXZ.getZ()
                    + ") from player (" + mod.getPlayer().getBlockPos().getX() + "," + mod.getPlayer().getBlockPos().getZ()
                    + ") → GetToXZTask.");
            setDebugState("Walking toward eye direction (" + targetXZ.getX() + ", " + targetXZ.getZ() + ")");
            _pendingNext = new GetToXZTask((int) target.x, (int) target.z);
        }
        _walkTarget = targetXZ;
    }

    /**
     * (2026-08-26 用户设计) 相邻两次眼睛方向夹角 → 决定"上一次距离的倍数",
     * 不再是绝对档位 (240/60/20)。这样连续同向时不衰减、一旦反向逐次减半,
     * 修复"反向后第三次同向又走回 240"的 bug。
     */
    private double chooseWalkDistance(Vec3d newDir) {
        if (_prevEyeDir == null) {
            // (2026-08-26 修复: 之前这里没给 _prevEyeDir 赋值就返回, 导致它永远
            // 是 null → 每只眼都走大步 (240)。首只眼也要记录方向供下次比较。)
            _prevEyeDir = newDir;
            return _currentWalkStep; // 首只眼/重丢后无方向先例: 沿用当前步长。
        }
        double dot = _prevEyeDir.x * newDir.x + _prevEyeDir.z * newDir.z;
        double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        _prevEyeDir = newDir;
        double factor;
        if (angleDeg < ANGLE_THRESHOLD_MID) {
            // 方向没差太多 (<30°) → 不衰减, 保持上一次 (防止反向后又走回大步)。
            factor = 1.0;
        } else {
            // 方向明显改变/反转 → 按改变程度缩到上一次的倍数:
            // 30°→1.0(临界), 90°(反转)→0.5, 更大封底 0.5。
            factor = Math.max(0.5, 1.0 - (angleDeg - ANGLE_THRESHOLD_MID) / 120.0);
        }
        _currentWalkStep = Math.max(WALK_STEP_MIN, _currentWalkStep * factor);
        return _currentWalkStep;
    }

    public Optional<BlockPos> getStrongholdCoordinates() {
        if (_strongholdEstimatePos == null) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(_strongholdEstimatePos));
    }

    /**
     * 直接扫世界实体找附近 (range 格内) 掉落的末影之眼 ItemEntity。
     * (2026-08-26 用户实测: 丢眼下船后不捡眼睛, 被方向调整任务拦截 —
     * itemDropped 读缓存可能延迟/漏检, 直接扫最稳。)
     */
    private ItemEntity findNearbyDroppedEye(AltoClef mod, int range) {
        Vec3d p = mod.getPlayer().getPos();
        double r2 = range * range;
        for (Entity e : mod.getWorld().getEntities()) {
            if (e instanceof ItemEntity ie
                    && ie.getStack().getItem() == Items.ENDER_EYE
                    && ie.isAlive()
                    && ie.squaredDistanceTo(p) < r2) {
                return ie;
            }
        }
        return null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LocateStrongholdCoordinatesTask;
    }

    @Override
    protected String toDebugString() {
        return "Locating stronghold coordinates";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _strongholdEstimatePos != null;
    }
}
