package adris.altoclef.util.progresscheck;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class MovementProgressChecker {

    private final IProgressChecker<Vec3d> _distanceChecker;
    private final IProgressChecker<Double> _mineChecker;

    private BlockPos _lastBreakingBlock = null;

    public MovementProgressChecker(double distanceTimeout, double minDistance, double mineTimeout, double minMineProgress, int attempts) {
        _distanceChecker = new ProgressCheckerRetry<>(new DistanceProgressChecker(distanceTimeout, minDistance), attempts);
        _mineChecker = new LinearProgressChecker(mineTimeout, minMineProgress);
    }

    public MovementProgressChecker(double distanceTimeout, double minDistance, double mineTimeout, double minMineProgress) {
        this(distanceTimeout, minDistance, mineTimeout, minMineProgress, 1);
    }

    public MovementProgressChecker(int attempts) {
        this(6, 0.1, 0.5, 0.001, attempts);
    }

    public MovementProgressChecker() {
        this(1);
    }

    public boolean check(AltoClef mod) {

        // Allow pause on eat
        if (mod.getFoodChain().isTryingToEat()) {
            _distanceChecker.reset();
            _mineChecker.reset();
        }

        if (mod.getControllerExtras().isBreakingBlock()) {
            BlockPos breakBlock = mod.getControllerExtras().getBreakingBlockPos();
            // If we broke a block, we made progress.
            // (2026-09-27) 这里原先也顺带 reset 距离表; 现已改为挖矿期无条件停表
            // (见下方注释), 故只剩挖掘进度表需要"挖穿一格 = 新窗口"。
            if (_lastBreakingBlock != null && WorldHelper.isAir(mod, _lastBreakingBlock)) {
                _mineChecker.reset();
            }
            _lastBreakingBlock = breakBlock;
            // (2026-09-27 I) 挖矿期距离表**停表**, 而不是空转。
            // 距离表只在下面 else 分支被喂 setProgress, 但它的计时器与 _lastProgress
            // 在挖矿期照常保留 ⇒ 连续挖矿超过 distanceTimeout(默认 6s) 后, 只要出现
            // 一拍非挖掘 (reach 闪断 / LookHelper 未对准 / 目标切换 / 背包刷新打断),
            // 立刻拿 6s 前的位置比现在: bot 原地挖矿位置没变 ⇒ improvement=0
            // < minDistance ⇒ 判负弃挖。黑曜石(hardness 50, 钻石镐 ≈9.4s)必中,
            // 石头(0.4s)几乎撞不上 —— 这就是"快挖满突然掉头走开"的另一半成因。
            // 语义正确: 挖矿期"移动进度"本就无意义(bot 不需要移动), 停表贴合意图;
            // "挖矿却挖不动"由 _mineChecker(默认 0.5s/0.001)兜底, 比 6s 距离判据更灵敏,
            // 故停表不损失任何检测能力(旧注释担心的"一直挖不成功却不失败"已由它覆盖)。
            _distanceChecker.reset();
            _mineChecker.setProgress(mod.getControllerExtras().getBreakingBlockProgress());
            return !_mineChecker.failed();
        } else {
            _mineChecker.reset();
            _distanceChecker.setProgress(mod.getPlayer().getPos());
            return !_distanceChecker.failed();
        }
    }

    public void reset() {
        _distanceChecker.reset();
        _mineChecker.reset();
    }

}
