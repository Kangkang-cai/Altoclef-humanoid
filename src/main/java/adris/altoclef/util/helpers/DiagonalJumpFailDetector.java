package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * (2026-09-28) 斜向升格"反复跳不上去"检测 + 短暂禁跳处置。
 *
 * 背景: baritone 的斜向升格 (allowDiagonalAscend, 俗称"旋转跳") 执行失败率
 * 实测 >50% (AltoClef.tickLavaNavGuard 注释)。危险区 (岩浆/深坑) 已由 LavaNav
 * **预测式**接管; 本件补的是**反应式**缺口 —— 普通地形上 bot 对同一边的斜跳
 * 反复失败 (起跳→空中→落回原地循环), 现有任何兜底都接不住:
 *   - JumpStuckResolver 要求"连续 20 tick 水平近零速", 而斜跳循环的空中阶段
 *     水平速度 >0.1, 每 tick 都把 streak 清零 → 永远凑不满 1s, 检测天然失明;
 *   - LavaNav 只在"落点危险"时触发, 普通地形不适用。
 *
 * 检测口径 = "跳跃尝试 → 落地结算" (每 tick 只做几次字段比较, 零扫描):
 *   ① 起跳臂上 (onGround true→false): 查 baritone 当前执行的动作, **是
 *      MovementDiagonal 且 dest.y == src.y+1 才臂上** —— 普通直跳 / 垫柱
 *      (水平位移≈0) / 平跳沟 (dest.y 不升) 都不会臂上, 不误伤。
 *   ② 落地结算 (onGround false→true): 落地 Y ≥ 起跳 Y+1 = 成功 (清计数);
 *      落地 Y ≤ 起跳 Y 且水平位移在 [MIN_HDISP, MAX_HDISP] = 该 dest 记一次失败
 *      (水平位移下限排除原地直跳/落地抖动, 上限排除"其实走过去了")。
 *   ③ 反复判定: 同一落点 dest (±1 格容差) 在 FAIL_WINDOW_TICKS 内失败
 *      ≥ FAILS_TO_TRIGGER 次 → 触发 (2026-09-28 用户拍板 2 次)。
 *
 * 处置 (与 LavaNav 同一套杠杆, **短窗口不记黑名单**, 2026-09-28 用户:
 * "短暂触发, 不是之后就一直惩罚"):
 *   - PillarJumpHelper.countBaritonePlaceable ≥ MIN_PLACEABLE 时: 关斜向升格
 *     保持 SUPPRESS_HOLD_TICKS + forceCancel 重算一次 → A* 在禁跳状态下改选
 *     垫块路线 (blockPlacementPenalty 不动, 与 LavaNav 09-19 拍板一致);
 *   - 窗口到期自动恢复; 恢复后计数已清零, 再触发需要全新的 2 次失败;
 *   - 垫块不足时保持跳 + 记日志 (2026-09-28 用户拍板: 垫不出替代路线时禁跳
 *     反把 bot 冻住, 跳总比冻死强)。
 *
 * 开关写回: 不自己写 settings —— AltoClef.tickLavaNavGuard 每 tick 强制写
 * allowDiagonalAscend (防 applyState 写回旧值), 本件只暴露 {@link #isSuppressing()},
 * 由写回点合并两个抑制源 (岩浆危险 ∨ 斜跳反复失败)。
 *
 * 全部状态 static (任务实例频繁重建会绕过实例层冷却, 20de6b0c 铁律);
 * 跨世界清空挂 WorldStateGuard (detail/10)。
 */
public final class DiagonalJumpFailDetector {

    private DiagonalJumpFailDetector() {
    }

    // ===== 阈值 (2026-09-28 用户拍板) =====
    /** 同一落点连续失败 N 次即触发。bot 白跳时间 ≈ N × 跳跃周期(约 1.5~2s)。 */
    private static final int FAILS_TO_TRIGGER = 2;
    /** 失败计数窗口: 超窗清零重来 (10s ≈ 5 个跳跃周期, 覆盖 2 次尝试绰绰有余)。 */
    private static final int FAIL_WINDOW_TICKS = 200;
    /** 触发后关跳保持时长 (与 LavaNav 的最小保持 8s 对齐); 短窗口, 到期自动恢复。 */
    private static final int SUPPRESS_HOLD_TICKS = 160;
    /** 关跳所需的 baritone 可放置垫块数, 与 AltoClef.LAVA_NAV_MIN_PLACEABLE 同值同义
     *  (1 块撑不起替代路线; 私有常量不便跨类引用, 改动时两处同步)。 */
    private static final int MIN_PLACEABLE = 8;
    /** 落地水平位移下限: 排除原地直跳/垫柱 (位移≈0) 与站地落地抖动。 */
    private static final double MIN_HDISP = 0.3;
    /** 落地水平位移上限: 超过 = 其实已经走过去了, 不算失败。 */
    private static final double MAX_HDISP = 1.8;

    // ===== 起跳/落地沿追踪 =====
    private static boolean _wasOnGround = true;
    private static boolean _armed = false;
    private static Vec3d _takeoffPos = null;
    private static int _takeoffY = Integer.MIN_VALUE;
    /** 臂上时 baritone 当前 MovementDiagonal 的落点 (失败计数的 key)。 */
    private static BlockPos _armedDest = null;

    // ===== 失败计数 =====
    private static BlockPos _failKey = null;
    private static int _failCount = 0;
    private static int _firstFailTick = 0;

    // ===== 抑制窗口 =====
    private static int _suppressUntilTick = Integer.MIN_VALUE;
    /** 垫块不足警告: 每次触发尝试只警告一次, 防刷屏。 */
    private static boolean _insufficientWarned = false;

    /** 每 tick 调用 (AltoClef.onClientTick, 必须先于 tickLavaNavGuard —— 后者每 tick 写回开关)。 */
    public static void tick(AltoClef mod) {
        boolean onGround = mod.getPlayer().isOnGround();

        // 落地沿: 空中 → 地面, 结算这次尝试。
        if (!_wasOnGround && onGround) {
            if (_armed && _takeoffPos != null) {
                settle(mod);
            }
            _armed = false;
        }
        // 起跳沿: 地面 → 空中, 臂上这次尝试。
        else if (_wasOnGround && !onGround) {
            _armed = false;
            _takeoffPos = null;
            _armedDest = null;
            if (isSuppressing()) return; // 抑制期间不该再出现斜跳, 不臂上
            Vec3d p = mod.getPlayer().getPos();
            // 飞行/创造: 斜跳无意义 (同 LavaNav 豁免③)。
            if (mod.getPlayer().getAbilities().flying || mod.getPlayer().isCreative()) return;
            BlockPos dest = currentDiagonalAscendDest(mod);
            if (dest != null) {
                _armed = true;
                _takeoffPos = p;
                _takeoffY = mod.getPlayer().getBlockPos().getY();
                _armedDest = dest;
            }
        }
        _wasOnGround = onGround;

        // 失败计数窗口过期: 清零重来 (不依赖触发, 保持"反复"的时效语义)。
        if (_failCount > 0
                && adris.altoclef.util.helpers.WorldHelper.getTicks() - _firstFailTick > FAIL_WINDOW_TICKS) {
            _failCount = 0;
            _failKey = null;
        }
    }

    /** 当前是否处于"斜跳反复失败"禁跳窗口内。 */
    public static boolean isSuppressing() {
        return adris.altoclef.util.helpers.WorldHelper.getTicks() < _suppressUntilTick;
    }

    /** 落地结算: 这次斜跳尝试是成功还是失败。 */
    private static void settle(AltoClef mod) {
        Vec3d land = mod.getPlayer().getPos();
        int gainY = mod.getPlayer().getBlockPos().getY() - _takeoffY;
        double hdisp = Math.hypot(land.x - _takeoffPos.x, land.z - _takeoffPos.z);

        if (gainY >= 1) {
            // 成功上去了: 该路线能用, 清计数。
            resetFails();
            return;
        }
        // 水平位移过小 = 原地直跳/落地抖动, 与斜跳失败无关; 过大 = 走过去了。
        if (hdisp < MIN_HDISP || hdisp > MAX_HDISP) {
            return;
        }
        registerFail(mod, _armedDest);
    }

    /** 记一次失败; 同一落点窗口内凑满即触发处置。 */
    private static void registerFail(AltoClef mod, BlockPos dest) {
        if (dest == null || isSuppressing()) return;
        int now = adris.altoclef.util.helpers.WorldHelper.getTicks();
        if (_failKey != null
                && Math.abs(dest.getX() - _failKey.getX()) <= 1
                && Math.abs(dest.getZ() - _failKey.getZ()) <= 1
                && now - _firstFailTick <= FAIL_WINDOW_TICKS) {
            _failCount++;
        } else {
            _failKey = dest.toImmutable();
            _failCount = 1;
            _firstFailTick = now;
        }
        Debug.logToFile("altoclef_diag.log", "[DiagJump] 斜跳失败 x" + _failCount
                + " dest=" + dest.toShortString()
                + " player=" + mod.getPlayer().getBlockPos().toShortString());
        if (_failCount >= FAILS_TO_TRIGGER) {
            trigger(mod);
        }
    }

    /** 触发处置: 短窗口禁跳 + 重算 (垫块足够), 或保持跳 + 警告 (垫块不足)。 */
    private static void trigger(AltoClef mod) {
        _failCount = 0;
        _failKey = null;
        BlockPos key = _armedDest;
        int placeable = PillarJumpHelper.countBaritonePlaceable(mod);
        if (placeable < MIN_PLACEABLE) {
            if (!_insufficientWarned) {
                _insufficientWarned = true;
                String m = "[DiagJump] 斜跳反复失败 (dest=" + (key == null ? "?" : key.toShortString())
                        + ") 但 baritone 可放置垫块 " + placeable + " < " + MIN_PLACEABLE
                        + " — 保持跳, 不禁跳 (垫不出替代路线)";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
            return;
        }
        _suppressUntilTick = adris.altoclef.util.helpers.WorldHelper.getTicks() + SUPPRESS_HOLD_TICKS;
        try {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        } catch (Throwable ignored) {
        }
        String m = "[DiagJump] 触发: 同一斜跳落点 (" + (key == null ? "?" : key.toShortString())
                + ") 连续 " + FAILS_TO_TRIGGER + " 次跳不上去, 短暂关斜向升格 "
                + (SUPPRESS_HOLD_TICKS / 20) + "s 并重算 (A* 将改选垫块路线), 到期自动恢复 pos="
                + mod.getPlayer().getBlockPos().toShortString();
        Debug.logMessage(m);
        Debug.logToFile("altoclef_diag.log", m);
    }

    private static void resetFails() {
        _failCount = 0;
        _failKey = null;
    }

    /**
     * baritone 当前执行的动作是否为"斜向升格" (MovementDiagonal 且 dest.y == src.y+1)。
     * 是 → 返回落点 dest (作为失败计数的 key); 否 → null。
     * 接口与 AltoClef.pathDiagonalDanger 同一套 (getCurrent → getPath → movements)。
     */
    private static BlockPos currentDiagonalAscendDest(AltoClef mod) {
        try {
            var baritone = mod.getClientBaritone();
            if (!baritone.getPathingBehavior().isPathing()) return null;
            var exec = baritone.getPathingBehavior().getCurrent();
            if (exec == null) return null;
            var path = exec.getPath();
            if (path == null) return null;
            var movements = path.movements();
            int idx = Math.min(movements.size() - 1, Math.max(0, exec.getPosition()));
            Object mv = movements.get(idx);
            // 只关心斜向**升格**: 关掉 allowDiagonalAscend 后唯一会消失的就是它
            // (下行斜跳 dest.y <= src.y 不受开关影响, 禁跳救不了它, 不臂上)。
            if (!(mv instanceof baritone.pathing.movement.movements.MovementDiagonal)) return null;
            baritone.api.pathing.movement.IMovement im = (baritone.api.pathing.movement.IMovement) mv;
            BlockPos src = im.getSrc();
            BlockPos dest = im.getDest();
            if (src == null || dest == null) return null;
            if (dest.getY() != src.getY() + 1) return null;
            return dest.toImmutable();
        } catch (Throwable t) {
            return null; // 拿不到路径按"无从臂上"处理
        }
    }

    /** 跨世界清空 (WorldStateGuard 注册): 计数/臂上/抑制窗口全部归零。 */
    public static void onWorldBoundary() {
        _wasOnGround = true;
        _armed = false;
        _takeoffPos = null;
        _takeoffY = Integer.MIN_VALUE;
        _armedDest = null;
        _failKey = null;
        _failCount = 0;
        _firstFailTick = 0;
        _suppressUntilTick = Integer.MIN_VALUE;
        _insufficientWarned = false;
    }
}
