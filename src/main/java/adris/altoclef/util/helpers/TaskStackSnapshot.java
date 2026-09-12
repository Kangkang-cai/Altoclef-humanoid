package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * [SNAPSHOT] (2026-09-11 任务栈全栈快照日志, 纯只读诊断)
 *
 * 背景: 现有日志全部是事件驱动 + 节流 (gridwar 400ms / craftclick 300ms /
 * debugstate 仅状态变化时写 / tasks.log 只记 START/STOP/SUB 切换), 因此
 * "父任务每 tick 重派同一子任务 (被 isEqual 挡下)" 与 "子任务每 tick 空转
 * 返回 null" 这类【无切换】的死锁在日志里完全不可见。
 *
 * 22:17 冻结 39s 实锤 (20260911-220819 会话): CollectFuelTask 燃料缺口 4.5
 * 每 tick 重派 "Crafting 3 planks" vs CollectPlanksTask 木板目标 3/3 已达成
 * 走清格分支 null-spin。两端证据分属不同文件且都被节流吞掉, 事后只能靠
 * Arbiter 日志节奏 + 源码逐层反推定位。
 *
 * 本快照一次性写出: 完整任务栈 + 【每层 isFinished】 + 关键资源计数 +
 * 2x2 格/光标内容。有了每层 isFinished, "叶子任务 finished=true 却仍挂在
 * 栈上" (框架从不检查中间层 isFinished) 与 "父任务 finished=false 且状态
 * 长期不变" 这两类死锁一眼可见。
 *
 * 频控: 常态 5s 一条; NAV-STUCK 冻结期间 1s 一条 (isStuck() 由看门狗提供)。
 * 零行为改动: 只读, 任何异常吞掉, 绝不影响主逻辑。
 */
public final class TaskStackSnapshot {

    private TaskStackSnapshot() {}

    private static final long NORMAL_INTERVAL_MS = 5000;
    private static final long STUCK_INTERVAL_MS = 1000;

    private static long _lastSnapMs = 0;

    /** 由 AltoClef.onClientTick 每游戏 tick 调用 (内部自节流)。 */
    public static void periodicTick(AltoClef mod) {
        try {
            long now = System.currentTimeMillis();
            long interval = NavStuckWatchdog.isStuck() ? STUCK_INTERVAL_MS : NORMAL_INTERVAL_MS;
            if (now - _lastSnapMs < interval) return;
            _lastSnapMs = now;
            dump(mod, NavStuckWatchdog.isStuck() ? "stuck" : "periodic");
        } catch (Throwable ignored) {
            // 诊断日志绝不影响主逻辑
        }
    }

    /** 立即写一份快照 (供其它诊断点按需调用)。 */
    public static void dump(AltoClef mod, String reason) {
        try {
            TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
            List<Task> tasks = chain != null ? chain.getTasks() : null;
            if (tasks == null || tasks.isEmpty()) return; // 空闲不刷屏

            StringBuilder sb = new StringBuilder();
            sb.append("[SNAPSHOT] reason=").append(reason)
                    .append(" chain=").append(chain.getName())
                    .append(" depth=").append(tasks.size());
            for (int i = 0; i < tasks.size(); i++) {
                Task t = tasks.get(i);
                String state;
                String fin;
                try {
                    state = t.toString();
                } catch (Throwable e) {
                    state = "<toString-err>";
                }
                try {
                    fin = String.valueOf(t.isFinished(mod));
                } catch (Throwable e) {
                    fin = "err";
                }
                sb.append("\n  L").append(i).append(' ').append(state).append(" finished=").append(fin);
            }

            // 关键资源计数 (木板/原木/煤/燃料单位) — 死锁常是"父任务缺口 vs
            // 子任务目标"口径脱节, 同一行对齐才能一眼看出来。
            int planks = mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
            int logs = mod.getItemStorage().getItemCount(ItemHelper.LOG);
            int coal = mod.getItemStorage().getItemCount(Items.COAL);
            double fuelUnits = StorageHelper.calculateInventoryFuelCount(mod);
            sb.append("\n  RES planks=").append(planks)
                    .append(" logs=").append(logs)
                    .append(" coal=").append(coal)
                    .append(" fuelUnits=").append(String.format("%.1f", fuelUnits))
                    // (2026-09-12) 容器类现场: 手头到底有没有炉/台 (放置不下时最
                    // 先要排除的假设 —— "没物品的放置是纯游荡")。
                    .append(" furnace=").append(mod.getItemStorage().getItemCount(Items.FURNACE))
                    .append(" table=").append(mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE));

            // (2026-09-12 目标位现场) 最深一个有目标位的任务: 打印目标格方块、
            // 可放置面、下方支撑、3x3x3 邻块 (仅非空气)。20260912-084704 场次
            // 实锤缺口: `Place structure{furnace} at 20,57,152> Letting baritone
            // place a block.` 空转 9.1s, 但日志里 20,57,152 到底是什么方块、
            // 有没有可放置面, 一个字都没有 → 事后无法判断"放不下"的原因。
            appendTargetBlockContext(sb, mod, tasks);

            // 2x2 合成格 + 输出格 + 光标
            sb.append("\n  GRID");
            for (int i = 0; i < PlayerSlot.CRAFT_INPUT_SLOTS.length; i++) {
                sb.append(" in").append(i + 1).append('=').append(fmtStack(
                        StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_INPUT_SLOTS[i])));
            }
            sb.append(" out=").append(fmtStack(StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT)))
                    .append(" cursor=").append(fmtStack(StorageHelper.getItemStackInCursorSlot()));

            Debug.logToFile("altoclef_snapshot.log", sb.toString());
        } catch (Throwable ignored) {
            // 诊断日志绝不影响主逻辑
        }
    }

    private static String fmtStack(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "EMPTY"
                : stack.getItem().toString() + "x" + stack.getCount();
    }

    /**
     * (2026-09-12) 目标位方块现场: 目标格状态 + 六向可放置面 + 下方支撑 +
     * 3x3x3 非空气邻块 + 与 bot 距离 + 是否被 BlockTracker 拉黑。
     *
     * 取【最深一个】有目标位的任务 (叶子层最贴近现场)。纯只读, 全包 try/catch。
     */
    private static void appendTargetBlockContext(StringBuilder sb, AltoClef mod, List<Task> tasks) {
        try {
            if (mod.getWorld() == null) return;
            Task owner = null;
            BlockPos target = null;
            for (int i = tasks.size() - 1; i >= 0; i--) {
                BlockPos p = null;
                try {
                    p = tasks.get(i).getDiagTargetBlock();
                } catch (Throwable ignored) {
                }
                if (p != null) {
                    owner = tasks.get(i);
                    target = p;
                    break;
                }
            }
            if (target == null) return;

            BlockState st = mod.getWorld().getBlockState(target);
            sb.append("\n  BLOCK from=").append(shortName(owner))
                    .append(" target=").append(target.toShortString())
                    .append(" block=").append(st.getBlock())
                    .append(" air=").append(st.isAir())
                    .append(" solid=").append(WorldHelper.isSolid(mod, target))
                    .append(" fluid=").append(!st.getFluidState().isEmpty())
                    .append(" blacklisted=").append(mod.getBlockTracker().unreachable(target));

            if (mod.getPlayer() != null) {
                double d = Math.sqrt(mod.getPlayer().squaredDistanceTo(WorldHelper.toVec3d(target)));
                sb.append(" dist=").append(String.format("%.2f", d));
            }

            // 六个相邻面: 哪些方向有实心面可点击放置 (无面 = 天然放不下)。
            StringBuilder faces = new StringBuilder();
            int faceCount = 0;
            for (Direction dir : Direction.values()) {
                BlockPos nb = target.offset(dir);
                if (WorldHelper.isSolid(mod, nb)) {
                    if (faceCount > 0) faces.append(',');
                    faces.append(dir.getName());
                    faceCount++;
                }
            }
            sb.append(" placeFaces=").append(faceCount == 0 ? "NONE" : faces.toString());

            BlockPos below = target.down();
            sb.append("\n  BLOCK below=").append(below.toShortString())
                    .append(" block=").append(mod.getWorld().getBlockState(below).getBlock())
                    .append(" solid=").append(WorldHelper.isSolid(mod, below));

            // 3x3x3 邻块, 只列非空气 (空气在放置场景无信息量, 会淹没日志)。
            StringBuilder nbs = new StringBuilder();
            int shown = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos np = target.add(dx, dy, dz);
                        BlockState ns = mod.getWorld().getBlockState(np);
                        if (ns.isAir()) continue;
                        if (shown > 0) nbs.append(' ');
                        nbs.append(dx).append('/').append(dy).append('/').append(dz)
                                .append('=').append(ns.getBlock());
                        shown++;
                    }
                }
            }
            sb.append("\n  BLOCK neighbors(非空气, 偏移/方块) ").append(shown == 0 ? "none" : nbs.toString());
        } catch (Throwable ignored) {
            // 诊断日志绝不影响主逻辑
        }
    }

    private static String shortName(Task t) {
        try {
            String s = t.toString();
            return s.length() > 40 ? s.substring(0, 40) + "…" : s;
        } catch (Throwable e) {
            return "?";
        }
    }
}
