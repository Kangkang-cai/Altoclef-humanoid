package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import baritone.api.utils.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;

/**
 * [NAV-STUCK] (2026-09-09 原地不动/水中浮动排查, 纯只读诊断)
 *
 * 背景: 用户多次遇到 "bot 泡在水里 / 站在原地不动, 但身上明明挂着导航任务
 * (如 GetToBlock)" 的观感, 且现有日志没有一条能直接证明"没在动"。任务/链层
 * 日志只记状态切换, baritone 层有自带日志但量大且不直观。
 *
 * 本看门狗: AltoClef.onTick 每 tick 喂入 (零行为改动, 只读采样), 250ms 采一次
 * 位置, 滑动窗口 4s 评估:
 *   - 净水平位移 < 0.5 块  → "没在动"
 *   - 全程触水 + Y 在 0.08~1.2 间起伏 → "水中原地浮动" (踩水/贴墙反复重算)
 *   - 满足"没在动"且 (有任务在跑 或 baritone 有活跃寻路) → 记 [NAV-STUCK]
 * 每条日志带: 持续时长/位移/Y起伏/精确坐标/速度/触水/实心地面/baritone 三个
 * process 活跃态/当前按住的移动键/主手/任务链+最内层任务(debugState)。
 *
 * 关键判据是 inputs: 若 baritone 正在寻路 (pathing=true) 但 MOVE_FORWARD
 * 没按 → 键被某处抢着松开; 若按了却没位移 → 物理/障碍/寻路目标异常。
 * 一条日志即可区分"没给键"和"给了键走不动"两大类根因。
 *
 * 卡住结束后 (窗口重新有位移) 记一条 [NAV-STUCK-END], 便于对齐"是怎么脱困的"。
 */
public final class NavStuckWatchdog {

    private NavStuckWatchdog() {}

    /** 当前是否处于"判卡"状态 (供 TaskStackSnapshot 提频用)。 */
    public static boolean isStuck() {
        return _stuckSinceMs != 0;
    }

    private static final long SAMPLE_INTERVAL_MS = 250;
    private static final long WINDOW_MS = 4000;
    private static final long EVAL_INTERVAL_MS = 1000;
    private static final long LOG_INTERVAL_MS = 2000;

    /** 4s 窗口内净水平位移小于此值 → 判"没在动" (0.5 块, 走路 4s 至少 ~8 块)。 */
    private static final double STUCK_NET_XZ = 0.5;
    /** 水中浮动: Y 起伏下限 (低于此 = 完全静止), 上限 (高于此 = 在真的上浮/下潜)。 */
    private static final double BOB_MIN_YRANGE = 0.08;
    private static final double BOB_MAX_YRANGE = 1.2;

    private static class Sample {
        final long t;
        final double x, y, z;
        final boolean water;
        Sample(long t, double x, double y, double z, boolean water) {
            this.t = t;
            this.x = x;
            this.y = y;
            this.z = z;
            this.water = water;
        }
    }

    private static final ArrayDeque<Sample> _samples = new ArrayDeque<>();
    private static long _lastSampleMs = 0;
    private static long _lastEvalMs = 0;
    private static long _lastLogMs = 0;
    /** 本次卡住的起始时间, 0 = 未卡住。 */
    private static long _stuckSinceMs = 0;
    /** 上一次卡住持续时长 (供 END 日志用)。 */
    private static long _lastStuckDurMs = 0;

    /** 由 AltoClef.onTick 每游戏 tick 调用。任何异常都被吞掉, 绝不影响主逻辑。 */
    public static void tick(AltoClef mod) {
        try {
            if (!AltoClef.inGame() || mod.getPlayer() == null) {
                reset();
                return;
            }
            long now = System.currentTimeMillis();
            ClientPlayerEntity p = mod.getPlayer();

            // ---- 采样 ----
            if (now - _lastSampleMs >= SAMPLE_INTERVAL_MS) {
                _lastSampleMs = now;
                _samples.addLast(new Sample(now, p.getX(), p.getY(), p.getZ(), p.isTouchingWater()));
                while (!_samples.isEmpty() && now - _samples.peekFirst().t > WINDOW_MS) {
                    _samples.removeFirst();
                }
            }

            // ---- 评估 (1s 节流) ----
            if (now - _lastEvalMs < EVAL_INTERVAL_MS) return;
            _lastEvalMs = now;
            if (_samples.size() < 4) return;

            Sample oldest = _samples.peekFirst();
            Sample newest = _samples.peekLast();
            double netXZ = Math.hypot(newest.x - oldest.x, newest.z - oldest.z);
            double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
            int waterCount = 0;
            for (Sample s : _samples) {
                if (s.y < yMin) yMin = s.y;
                if (s.y > yMax) yMax = s.y;
                if (s.water) waterCount++;
            }
            double yRange = yMax - yMin;
            boolean mostlyWater = waterCount * 2 >= _samples.size();
            boolean stuck = netXZ < STUCK_NET_XZ;

            if (!stuck) {
                if (_stuckSinceMs != 0) {
                    long durMs = now - _stuckSinceMs;
                    // 只在卡了足够久后恢复才记 (短暂停顿不值得记)
                    if (durMs >= 6000) {
                        Debug.logToFile("altoclef_diag.log",
                                "[NAV-STUCK-END] 卡住 " + (durMs / 1000.0) + "s 后恢复移动"
                                        + " netXZ=" + String.format("%.2f", netXZ)
                                        + " pos=(" + fmt(p.getX()) + "," + fmt(p.getY()) + "," + fmt(p.getZ()) + ")");
                    }
                    _lastStuckDurMs = durMs;
                    _stuckSinceMs = 0;
                }
                return;
            }

            // ---- 卡住成立: 只有"身上有任务 或 baritone 有活跃寻路"才值得记 ----
            TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
            boolean hasTask = chain != null && !chain.getTasks().isEmpty();
            boolean baritoneActive;
            try {
                baritoneActive = mod.getClientBaritone().getCustomGoalProcess().isActive()
                        || mod.getClientBaritone().getPathingBehavior().isPathing()
                        || mod.getClientBaritone().getBuilderProcess().isActive();
            } catch (Throwable t) {
                baritoneActive = false;
            }
            if (!hasTask && !baritoneActive) {
                // 空闲站着, 正常行为
                if (_stuckSinceMs != 0) _stuckSinceMs = 0;
                return;
            }
            if (_stuckSinceMs == 0) {
                _stuckSinceMs = now;
                _lastLogMs = 0;
            }

            // 2s 节流打印现场
            if (now - _lastLogMs < LOG_INTERVAL_MS) return;
            _lastLogMs = now;

            String mode;
            if (mostlyWater && yRange >= BOB_MIN_YRANGE && yRange <= BOB_MAX_YRANGE) {
                mode = "water-bob(水中原地浮动)";
            } else if (mostlyWater) {
                mode = "water-still(水中静止)";
            } else {
                mode = "frozen(原地不动)";
            }

            boolean customGoal, pathing, builder;
            String pathInfo;
            String baritoneInputs;
            try {
                customGoal = mod.getClientBaritone().getCustomGoalProcess().isActive();
                pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
                builder = mod.getClientBaritone().getBuilderProcess().isActive();
                // (2026-09-09 补测量盲区) vanilla KeyBinding 读不到 baritone 的按键 —
                // baritone 走 InputOverrideHandler 覆盖, 不 setPressed。inputs[fwd=...]
                // 只反映 AltoClef 自己 InputControls 的按键 (直游/进食等)。
                // baritone 是否真的在开键必须读 isInputForcedFor。
                baritoneInputs = "bIn[fwd=" + mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD)
                        + " jump=" + mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(Input.JUMP)
                        + " sprint=" + mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(Input.SPRINT)
                        + " sneak=" + mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(Input.SNEAK) + "]";
                // 当前路径: 是否存在 / 段长 / 目的地 — pathing=true 但无 path 或
                // 目的地就在脚下反复重算时, 即为"自称寻路实际不动"的直接证据。
                java.util.Optional<baritone.api.pathing.calc.IPath> bPath =
                        mod.getClientBaritone().getPathingBehavior().getPath();
                pathInfo = "path[present=" + bPath.isPresent();
                if (bPath.isPresent()) {
                    pathInfo += " segLen=" + bPath.get().length()
                            + " dest=" + bPath.get().getDest();
                }
                pathInfo += "]";
            } catch (Throwable t) {
                customGoal = pathing = builder = false;
                pathInfo = "path[err]";
                baritoneInputs = "bIn[err]";
            }

            // (2026-09-26 "有路径但零输入"深挖) 现场 20260926-160606 16:13:32 / 16:14:58
            // 两段实锤: baritone[customGoal=true pathing=true] path[present=true segLen=45]
            // 而 bIn 全 false, 净位移 0, 平地无阻挡; JumpStuck 9.9s 后 forceCancel+清草
            // 才脱困。字节码取证 (baritone-unoptimized-fabric-1.18.2 patched):
            // tickPath 若未走 pause 分支则必然执行 executor.onTick → PathExecutor.onTick
            // 只在三处 clearKeys-and-return: ①"destination at edge of loaded chunks"
            // ②shouldPause()=true("current best path is a backtrack") ③Movement.update
            // → MovementTraverse.updateState 的非 RUNNING 早退分支 (walkWhileBreaking/
            // PREPPING/avoidWalkThroughForce patch 门)。本段只读反射采样区分:
            //   calc[inProgress=true] + 长冻结     → A* 重算进行中 (②的必要条件);
            //   exec[status=PREPPING] 持续          → movement.prepared() 永假 (③ gate);
            //   exec[status=WAITING/RUNNING] 零输入 → MovementTraverse 早退分支本体。
            String calcInfo = "calc[?]";
            String execInfo = "exec[?]";
            String wwbInfo = "";
            try {
                baritone.api.behavior.IPathingBehavior bh = mod.getClientBaritone().getPathingBehavior();
                try {
                    Object inProg = bh.getClass().getMethod("getInProgress").invoke(bh);
                    boolean present = inProg != null
                            && (Boolean) inProg.getClass().getMethod("isPresent").invoke(inProg);
                    calcInfo = "calc[inProgress=" + present + "]";
                } catch (Throwable ignored) {
                }
                try {
                    Object exec = bh.getClass().getMethod("getCurrent").invoke(bh);
                    if (exec == null) {
                        execInfo = "exec[null]";
                    } else {
                        int pos = (Integer) exec.getClass().getMethod("getPosition").invoke(exec);
                        Object pathObj = exec.getClass().getMethod("getPath").invoke(exec);
                        java.util.List<?> mvs = (java.util.List<?>) pathObj.getClass()
                                .getMethod("movements").invoke(pathObj);
                        StringBuilder sb = new StringBuilder("exec[pos=").append(pos)
                                .append("/").append(mvs == null ? "?" : mvs.size()).append("]");
                        if (mvs != null && !mvs.isEmpty()) {
                            Object mv = mvs.get(Math.min(Math.max(pos, 0), mvs.size() - 1));
                            sb.append(" mv=").append(mv.getClass().getSimpleName())
                                    .append(" src=").append(safeInvoke(mv, "getSrc"))
                                    .append(" dest=").append(safeInvoke(mv, "getDest"));
                            // 执行器视角"这段要挖的方块" (PathExecutor.toBreak() 公共方法) —
                            // prepared() 持续 false (PREPPING 循环) 时这就是要瞄/要挖的目标。
                            try {
                                Object tbSet = exec.getClass().getMethod("toBreak").invoke(exec);
                                sb.append(" tb=").append(tbSet);
                            } catch (Throwable ignored) {
                            }
                            Object state = getField(mv, "currentState");
                            if (state != null) {
                                sb.append(" status=").append(safeInvoke(state, "getStatus"));
                                // baritone 想看的朝向 (MovementTarget.rotation) — 与玩家实际
                                // 朝向 pRot 对不上且持续 = "转向权被抢, isLookingAt 永假" 实锤。
                                Object target = safeInvokeObj(state, "getTarget");
                                if (target != null) {
                                    Object rotOpt = safeInvokeObj(target, "getRotation");
                                    if (rotOpt instanceof java.util.Optional
                                            && ((java.util.Optional<?>) rotOpt).isPresent()) {
                                        sb.append(" bRot=").append(((java.util.Optional<?>) rotOpt).get());
                                    }
                                }
                            }
                        }
                        execInfo = sb.toString();
                    }
                } catch (Throwable t) {
                    execInfo = "exec[err:" + t + "]";
                }
                try {
                    wwbInfo = " wwb=" + baritone.api.BaritoneAPI.getSettings().walkWhileBreaking.value;
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }

            Vec3d vel = p.getVelocity();
            double hSpeed = Math.hypot(vel.x, vel.z);
            ItemStack main = p.getMainHandStack();

            // (2026-09-26 freeze-1 新指纹) 18:03:26 冻结里 vel 恒定 (0.11,-0.08,0.11)
            // 而 netXZ=0 —— "行走力在施加但位置被钳住"。加只读物理探针:
            // 碰撞标志 / 入墙 / 2 格内最近实体 (实体推挤/卡体)。
            String physInfo;
            try {
                boolean hCol = p.horizontalCollision;
                boolean vCol = p.verticalCollision;
                boolean inWall = p.isInsideWall();
                String ent = "none";
                java.util.List<net.minecraft.entity.Entity> others = p.getEntityWorld()
                        .getOtherEntities(p, p.getBoundingBox().expand(2.0));
                double bestD = Double.MAX_VALUE;
                for (net.minecraft.entity.Entity e : others) {
                    double d = e.squaredDistanceTo(p);
                    if (d < bestD) {
                        bestD = d;
                        ent = e.getType().toString() + "@" + String.format("%.1f", Math.sqrt(d));
                    }
                }
                physInfo = " phys[hCol=" + hCol + " vCol=" + vCol + " inWall=" + inWall
                        + " ent=" + ent + "]";
            } catch (Throwable t) {
                physInfo = " phys[err]";
            }

            // 任务链: 链名 + 最内层任务 (toString 含 toDebugString + debugState)
            String taskStr;
            if (hasTask) {
                Task inner = chain.getTasks().get(chain.getTasks().size() - 1);
                taskStr = "<" + (inner != null ? inner.toString() : "null") + ">";
            } else {
                taskStr = "none";
            }

            long durMs = now - _stuckSinceMs;
            String m = "[NAV-STUCK] dur=" + (durMs / 1000.0) + "s"
                    + " netXZ=" + String.format("%.2f", netXZ)
                    + " yRange=" + String.format("%.2f", yRange)
                    + " mode=" + mode
                    + " pos=" + p.getBlockPos()
                    + " xyz=(" + fmt(p.getX()) + "," + fmt(p.getY()) + "," + fmt(p.getZ()) + ")"
                    + " vel=(" + fmt(vel.x) + "," + fmt(vel.y) + "," + fmt(vel.z) + ")"
                    + " hSpeed=" + String.format("%.3f", hSpeed)
                    + " onGround=" + p.isOnGround()
                    + " inWater=" + p.isTouchingWater()
                    + " swimming=" + p.isSwimming()
                    + " onLadder=" + p.isClimbing()
                    + " baritone[customGoal=" + customGoal + " pathing=" + pathing + " builder=" + builder + "]"
                    + " " + baritoneInputs
                    + " " + pathInfo
                    + " " + calcInfo
                    + " " + execInfo
                    + wwbInfo
                    + physInfo
                    + " pRot=(" + String.format("%.1f", p.getYaw()) + "," + String.format("%.1f", p.getPitch()) + ")"
                    + " inputs[fwd=" + mod.getInputControls().isHeldDown(Input.MOVE_FORWARD)
                    + " jump=" + mod.getInputControls().isHeldDown(Input.JUMP)
                    + " sprint=" + mod.getInputControls().isHeldDown(Input.SPRINT)
                    + " sneak=" + mod.getInputControls().isHeldDown(Input.SNEAK) + "]"
                    + " hand=" + (main.isEmpty() ? "empty" : main.getItem().toString())
                    + " chain=" + (chain != null ? chain.getName() : "none")
                    + " task=" + taskStr;
            Debug.logToFile("altoclef_diag.log", m);
        } catch (Throwable t) {
            // 诊断日志绝不影响主逻辑
            reset();
        }
    }

    private static void reset() {
        _samples.clear();
        _lastSampleMs = 0;
        _stuckSinceMs = 0;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /** 反射安全调用: 失败返回 "err"。 */
    private static String safeInvoke(Object obj, String method) {
        try {
            return String.valueOf(obj.getClass().getMethod(method).invoke(obj));
        } catch (Throwable t) {
            return "err";
        }
    }

    /** 反射安全调用: 失败返回 null。 */
    private static Object safeInvokeObj(Object obj, String method) {
        try {
            return obj.getClass().getMethod(method).invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射读字段: 沿继承链向上找 (Movement.currentState 声明在基类), 失败返回 null。 */
    private static Object getField(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
