package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * (2026-08-22 矿洞内安全烧制) 用 throwaway 方块把玩家封在 1x1 空间里
 * (水平 8 邻位 + 头顶 1 格), 防怪物在烧制/开容器界面时攻击。
 *
 * 用户设计: 在矿洞里烧制矿物前, 先有个前置子任务把自己用方块包围起来,
 * 确保安全。粘性: 选中一个位置后保持到"放置成功 (非空气)"或"超时 8 秒
 * 放弃"才换下一个 — 避免每 tick 重选位置与 PlaceBlockTask 内部换目标
 * 打架 (用户实测 17:55: Place structure 在 298,75,113↔114 横跳)。围住的
 * 方块是 throwaway (圆石/泥土等), 后续离开时 Baritone 挖开即可。
 */
public class SecurePerimeterTask extends Task {

    private final BlockPos _center;
    private BlockPos _activePos = null;
    private long _activeSinceMs = 0;
    private final Set<BlockPos> _failed = new HashSet<>();
    // (2026-08-23 重设计) 依次放置: 脚一圈(y+0) → 头一圈(y+1) → 顶盖(y+2)。
    // 原设计有 (0,1,0)"头顶块" — 那是玩家头部所占格, MC 不允许把方块
    // 放进玩家身体 → PlaceBlockTask 永远失败 → 走"替代路径"(GetToBlock
    // 到目标上方) → bot 脚下垫方块把自己搭出围蔽, 最后站在围墙上
    // (用户实测"铺完脚边 9 块又把自己搭高, 根本没围住")。
    // 现在所有目标位都不与玩家重叠: 脚圈挡近战, 头圈挡骷髅箭平射,
    // 顶盖防高处坠物/幻翼; 头圈借脚圈顶面、顶盖借头圈顶面做支撑。
    private static final BlockPos[] PLACEMENTS = new BlockPos[]{
            // 脚一圈 (y+0): 挡近战
            new BlockPos(-1, 0, -1), new BlockPos(0, 0, -1), new BlockPos(1, 0, -1),
            new BlockPos(-1, 0, 0), new BlockPos(1, 0, 0),
            new BlockPos(-1, 0, 1), new BlockPos(0, 0, 1), new BlockPos(1, 0, 1),
            // 头一圈 (y+1): 挡箭 (只封脚圈时骷髅能从头部高度射进来)
            new BlockPos(-1, 1, -1), new BlockPos(0, 1, -1), new BlockPos(1, 1, -1),
            new BlockPos(-1, 1, 0), new BlockPos(1, 1, 0),
            new BlockPos(-1, 1, 1), new BlockPos(0, 1, 1), new BlockPos(1, 1, 1),
            // 顶盖 (y+2): 玩家头顶上方一格, 不与玩家重叠, 可直接放
            new BlockPos(0, 2, 0),
    };
    private static final long PLACE_TIMEOUT_MS = 8000;

    public SecurePerimeterTask(BlockPos center) {
        _center = center;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // (2026-08-23 位置守卫) 围蔽期间玩家不得离开中心 — 放块任务若
        // 移动了玩家 (baritone 为放置寻路), 整个围蔽已无意义, 立即放弃
        // (视为完成)。防"围蔽时站在要放方块的位置上" (用户实测: 围脚圈
        // +头圈时玩家移位站到了目标位上)。
        if (playerLeftCenter(mod)) {
            _activePos = null;
            return null;
        }
        // (2026-08-22 完善) 背包没有可放方块 (圆石/泥土/地狱岩) 时跳过围住 —
        // PlaceStructureBlockTask 会自动去收集圆石 ("No structure items,
        // collecting cobblestone"), 慢且被挖矿任务抢占, 围不住 (用户实测
        // 18:06/18:07: Securing perimeter 被 Mining or Collecting 打断)。
        // 有方块才围 (秒级完成), 没有直接跳过, 不阻塞烧制。
        if (PlaceBlockTask.getMaterialCount(mod) <= 0) {
            _activePos = null;
            return null;
        }
        // 粘性: 正在放置的位置保持到成功/超时, 不每 tick 重选。
        if (_activePos != null) {
            if (!WorldHelper.isAir(mod, _activePos)) {
                _activePos = null; // 放好了, 下一个
            } else if (System.currentTimeMillis() - _activeSinceMs > PLACE_TIMEOUT_MS) {
                adris.altoclef.util.helpers.TimeoutLog.hit("SecurePerimeterTask.placeTimeout", "8s place timeout at " + _activePos.toShortString());
                _failed.add(_activePos); // 8 秒没放好, 放弃该位置
                _activePos = null;
            } else {
                setDebugState("Securing perimeter at " + _activePos.toShortString());
                return new PlaceStructureBlockTask(_activePos);
            }
        }
        for (BlockPos off : PLACEMENTS) {
            BlockPos target = _center.add(off);
            if (_failed.contains(target)) continue;
            if (!WorldHelper.isAir(mod, target)) continue;
            // (2026-08-23 无支撑跳过) 悬空位 (矿洞地面缺洞) 没有可贴的
            // 放置面, baritone 为放置会寻路移动玩家 → 位置守卫触发 →
            // 整个围蔽放弃。无支撑面直接记失败跳过, 围能围的部分。
            if (!hasSupportFace(mod, target)) {
                _failed.add(target);
                continue;
            }
            _activePos = target;
            _activeSinceMs = System.currentTimeMillis();
            setDebugState("Securing perimeter at " + target.toShortString());
            return new PlaceStructureBlockTask(target);
        }
        return null; // 全部放好或全部失败
    }

    /** 玩家是否已离开围蔽中心 (水平/垂直 >1 格)。 */
    private boolean playerLeftCenter(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        return Math.abs(p.getX() - _center.getX()) > 1
                || Math.abs(p.getY() - _center.getY()) > 1
                || Math.abs(p.getZ() - _center.getZ()) > 1;
    }

    /** 目标位 6 邻居是否有实体方块 (有面可贴着放置)。 */
    private static boolean hasSupportFace(AltoClef mod, BlockPos pos) {
        return WorldHelper.isSolid(mod, pos.north()) || WorldHelper.isSolid(mod, pos.south())
                || WorldHelper.isSolid(mod, pos.east()) || WorldHelper.isSolid(mod, pos.west())
                || WorldHelper.isSolid(mod, pos.up()) || WorldHelper.isSolid(mod, pos.down());
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // 玩家已离开中心 → 放弃围蔽 (见 onTick 位置守卫)。
        if (playerLeftCenter(mod)) return true;
        // 背包无方块时跳过围住 (见 onTick 注释), 视为已完成。
        if (PlaceBlockTask.getMaterialCount(mod) <= 0) return true;
        for (BlockPos off : PLACEMENTS) {
            BlockPos target = _center.add(off);
            if (_failed.contains(target)) continue;
            if (WorldHelper.isAir(mod, target)) {
                // 无支撑位视同放弃 (与 onTick 的 _failed 登记同步)。
                if (hasSupportFace(mod, target)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SecurePerimeterTask t && t._center.equals(_center);
    }

    @Override
    protected String toDebugString() {
        return "Securing perimeter around " + _center.toShortString();
    }
}
