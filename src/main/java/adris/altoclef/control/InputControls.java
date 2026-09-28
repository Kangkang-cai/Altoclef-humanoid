package adris.altoclef.control;

import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Sometimes we want to trigger a "press" for one frame, or do other input forcing.
 * <p>
 * Dealing with keeping track of a press and timing each time you do this is annoying.
 * <p>
 * For some reason using baritone's "Forcestate" doesn't always work, perhaps that's my bad.
 * <p>
 * But this will alleviate all confusion.
 */
@SuppressWarnings("UnnecessaryDefault")
public class InputControls {

    private final Queue<Input> _toUnpress = new ArrayDeque<>();
    private final Set<Input> _waitForRelease = new HashSet<>(); // a click requires a release.

    private static KeyBinding inputToKeyBinding(Input input) {
        GameOptions o = MinecraftClient.getInstance().options;
        return switch (input) {
            case MOVE_FORWARD -> o.forwardKey;
            case MOVE_BACK -> o.backKey;
            case MOVE_LEFT -> o.leftKey;
            case MOVE_RIGHT -> o.rightKey;
            case CLICK_LEFT -> o.attackKey;
            case CLICK_RIGHT -> o.useKey;
            case JUMP -> o.jumpKey;
            case SNEAK -> o.sneakKey;
            case SPRINT -> o.sprintKey;
            default -> throw new IllegalArgumentException("Invalid key input/not accounted for: " + input);
        };
    }

    /**
     * 按一下键 —— 但**实际效果是"按住到下一帧"**, 调用方必须知道这一点。
     *
     * (2026-09-28 事故 20260928-145156 复盘) 本方法只负责按下, 真正的
     * `setPressed(false)` 推迟到**下一帧**的 `onTickPre()` (走队列 `_toUnpress`);
     * 而 `onTickPost()` 只清 `_waitForRelease`、不清键。AltoClef 的帧结构:
     *     AltoClef:315 onTickPre()   ← 清上一帧留下的按下
     *                   … TaskRunner.tick → 本方法 → setPressed(true) …
     *     AltoClef:410 onTickPost()
     * ⇒ 键从"本帧按下"一直保持 true 到"下一帧 onTickPre", **跨帧边界**。
     *
     * 下游后果 (vanilla `MinecraftClient.handleInputEvents`):
     *     中断"持续使用物品"状态的唯一条件 = `isUsingItem() && !useKey.isPressed()`
     *     ⇒ **每帧都调本方法 ⇒ 键恒 pressed ⇒ `stopUsingItem` 永不触发**
     *     ⇒ 举盾 / 进食 / 拉弓 / 望远镜 这类"持续使用"状态一旦开始就**永不被打断**。
     *
     * ⇒ **调用纪律: 绝不每帧无节流地重复调用本方法。**
     *   单次点击只让状态多持续一帧(下一帧被 onTickPre 清掉, 无感); 但**每帧点击**
     *   会让任何"持续使用"状态永久粘住。需要重复点击时**必须自带节流**
     *   (先例: `MLGBucketFallChain._pickupRepeatTimer`), 或改用 `hold()` +
     *   在 onStop / 收尾路径里成对 release。
     *   反例: `PlaceBedAndSetSpawnTask` 旧版点床分支每帧 `tryPress(CLICK_RIGHT)`,
     *   当副手持盾且 MC 准星 MISS(落进 `doItemUse` 的 `interactItem(OFF_HAND)`
     *   分支 → 盾牌 use())时, 表现为"床边持续举盾 30s"。详见
     *   `detail/03-consistency.md` 第 4 节。
     *   注: `forcePress()` 是"release + tryPress", 同样具备上述跨帧特性。
     */
    public void tryPress(Input input) {
        // We just pressed, so let us release.
        if (_waitForRelease.contains(input)) {
            return;
        }
        inputToKeyBinding(input).setPressed(true);
        // Also necessary to ensure the game registers the input as "pressed"
        KeyBinding.onKeyPressed(inputToKeyBinding(input).getDefaultKey());
        _toUnpress.add(input);
        _waitForRelease.add(input);
    }

    /**
     * (2026-09-01 容器交互短按, 用户"不应该是长按反而举盾") 清除之前 hold(战斗举盾)
     * 残留的按下/等待释放状态, 强制发一次干净短按。否则 tryPress 因 _waitForRelease
     * 短路不触发, 持续的长按会让 offhand 盾举起来, 右键开容器失效。
     */
    public void forcePress(Input input) {
        release(input);
        _waitForRelease.remove(input);
        tryPress(input);
    }

    public void hold(Input input) {
        if (!inputToKeyBinding(input).isPressed()) {
            KeyBinding.onKeyPressed(inputToKeyBinding(input).getDefaultKey());
        }
        inputToKeyBinding(input).setPressed(true);
    }

    public void release(Input input) {
        inputToKeyBinding(input).setPressed(false);
    }

    public boolean isHeldDown(Input input) {
        return inputToKeyBinding(input).isPressed();
    }

    // Before the user calls input commands for the frame
    public void onTickPre() {
        while (!_toUnpress.isEmpty()) {
            inputToKeyBinding(_toUnpress.remove()).setPressed(false);
        }
    }

    // After the user calls input commands for the frame
    public void onTickPost() {
        _waitForRelease.clear();
    }
}
