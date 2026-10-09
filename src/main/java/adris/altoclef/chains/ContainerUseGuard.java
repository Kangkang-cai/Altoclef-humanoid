package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientTickEvent;
import baritone.api.utils.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * (2026-10-09) 容器界面"泄漏按住右键"守卫 ContainerUseGuard。
 *
 * <p>现场 (20261009-175149 / 20261009-182321): 金锭 31/32 合成时服务端每 ~200ms
 * 关一次合成台容器 ([ServerClose] 23 次/23s, syncId 每秒 +4~5 ≈ vanilla
 * itemUseCooldown=4tick 的"按住右键"重触发率), 而 vanilla 服务端
 * {@code openHandledScreen} 开新容器前必先 {@code closeHandledScreen} 关旧
 * (字节码实锤) —— 每次关窗把 3x3 合成格里的材料退回背包 = "金粒回到背包"、
 * 成品永远收不到; 收货点击又被 200ms 槽位点击冷却竞争饿死 (fill 每拍重置)。
 * 1.18.2 客户端收到服务端关屏包后不销毁 GUI ([CPE-Close] 全场仅 4 次, 用户目视
 * "背包窗口其实没有关") —— bot 对着僵尸界面继续操作, 恶性循环直到手动停。
 *
 * <p>驱动源: 某条并行链 {@code hold(Input.CLICK_RIGHT)} 后未 release
 * (InputControls.hold 无引用计数、无自动释放, 泄漏后与任务树无关 —— 循环期间
 * tasks.log 零启停、17:52 场换代一直刷到 53:16 用户手动停)。空手按住右键 +
 * 准星落在合成台上 ⇒ vanilla 每帧 doItemUse ⇒ 200ms 一次"关旧开新"。
 * 同参成功场 (18:30 bulk=true / 18:35 remaining=1) 全程零 [ServerClose],
 * 证明分叉变量就是这个泄漏的按住右键, 与金锭数量/remaining/配方无关。
 *
 * <p>守卫不变量 (保守收窄, 不碰任何合法 hold):
 * <b>容器界面打开 + 主手空 + 副手空 + 右键持续按住 ≥300ms</b>。
 * 空手(含副手无盾)对容器界面的按住右键只可能产生"再开一次容器"——进食/喝药/拉弓
 * 需要主手物品, 举盾需要副手物品, 全部被条件排除; tryPress/forcePress 的单帧短按
 * 帧间不满足"持续按住"不会被误伤 (帧间未按住即重置计时); 开门等空手合法右键不发生
 * 在容器界面打开期。命中 ⇒ 强制 release + [UseGuard] 每次事件留痕 (含持住时长),
 * 为定位泄漏链提供现场指纹。
 */
public final class ContainerUseGuard {

    private static final long HOLD_LEAK_MS = 300;

    private final AltoClef _mod;
    private long _heldSinceMs = -1;
    private boolean _loggedThisEpisode;

    public ContainerUseGuard(AltoClef mod) {
        _mod = mod;
        EventBus.subscribe(ClientTickEvent.class, evt -> onTick());
    }

    private void onTick() {
        ClientPlayerEntity player = _mod.getPlayer();
        if (player == null) {
            reset();
            return;
        }
        boolean containerOpen = player.currentScreenHandler != null
                && player.currentScreenHandler != player.playerScreenHandler;
        if (!containerOpen) {
            reset();
            return;
        }
        ItemStack main = player.getMainHandStack();
        ItemStack off = player.getOffHandStack();
        // 盾/物品在副手时的举盾是合法持续右键, 不归本守卫管。
        if (!main.isEmpty() || !off.isEmpty()) {
            reset();
            return;
        }
        if (!_mod.getInputControls().isHeldDown(Input.CLICK_RIGHT)) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (_heldSinceMs < 0) {
            _heldSinceMs = now;
            return;
        }
        if (!_loggedThisEpisode && now - _heldSinceMs >= HOLD_LEAK_MS) {
            _loggedThisEpisode = true;
            String msg = "[UseGuard] leaked held CLICK_RIGHT " + (now - _heldSinceMs)
                    + "ms with EMPTY hands while container open ("
                    + player.currentScreenHandler.getClass().getSimpleName()
                    + " syncId=" + player.currentScreenHandler.syncId + ") — releasing.";
            Debug.logMessage(msg);
            Debug.logToFile("altoclef_diag.log", msg);
        }
        // 持续按住期每拍强制松开: vanilla 失去 isPressed ⇒ doItemUse 停发,
        // 服务端不再"关旧开新", 合成格保住。
        _mod.getInputControls().release(Input.CLICK_RIGHT);
    }

    private void reset() {
        _heldSinceMs = -1;
        _loggedThisEpisode = false;
    }
}
