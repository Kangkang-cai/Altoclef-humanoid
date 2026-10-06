package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SleepingChatScreen;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * (2026-10-06 多 bot 全员入睡) <b>睡眠判据的唯一来源</b>。
 *
 * <h3>为什么需要它</h3>
 * 多人服里「<b>全员入睡才跳夜</b>」，所以两个方向都需要判据：
 * <ul>
 *   <li>次 bot 判断"本夜还有没有睡成的机会"（有则继续找床，没有就放弃回跟随）；</li>
 *   <li>主 bot 判断"我躺下多久了"（躺了 30s 夜还没跳 ⇒ 有队友没睡 ⇒ 自己也别僵躺）。</li>
 * </ul>
 * 此前这两套判据都长在 {@code BeatMinecraft2Task} 的私有方法里（{@code :4764} / {@code :4796}），
 * 次 bot 用不上，只能另写一份 —— 那是"判定源两份"的经典翻车姿势。
 *
 * <p>本类把 <b>纯函数 / 纯动作</b> 抽出来共用：不含任何实例状态，主线、次 bot 跟随睡眠、
 * {@code @sleep} 命令三方共用同一份口径。
 */
public final class SleepHelper {

    private SleepHelper() {
    }

    /** "就近睡眠机会"半径（格）。与主线 {@code nightBedOpportunityNearby} 原口径一致。 */
    public static final int BED_OPPORTUNITY_RANGE = 32;

    /**
     * 附近 {@link #BED_OPPORTUNITY_RANGE} 格内是否存在"做床 / 睡床"的机会：
     * 床方块 / 羊毛方块 / 羊。
     *
     * <p>为什么是"就近"而非"整区块"：遥远(&gt;32 格)的床/羊会反复把 bot 从砍木/挖矿拉走
     * （主线 2026-09-01 用户实测收窄）。
     *
     * <p>纯读世界与实体追踪，无副作用。原实现 {@code BeatMinecraft2Task:4764}，
     * 2026-10-06 迁至此处（行为不变）。
     */
    public static boolean nightBedOpportunityNearby(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        Vec3d pos = mod.getPlayer().getPos();
        // 1) 附近已感知/追踪到的床方块
        Optional<BlockPos> nearestBed = mod.getBlockTracker().getNearestTracking(ItemHelper.itemsToBlocks(ItemHelper.BED));
        if (nearestBed.isPresent() && nearestBed.get().isWithinDistance(pos, BED_OPPORTUNITY_RANGE)) return true;
        // 2) 附近羊毛方块（劫掠者塔/村庄羊毛堆，就地能做成床）
        Optional<BlockPos> nearestWool = mod.getBlockTracker().getNearestTracking(ItemHelper.itemsToBlocks(ItemHelper.WOOL));
        if (nearestWool.isPresent() && nearestWool.get().isWithinDistance(pos, BED_OPPORTUNITY_RANGE)) return true;
        // 3) 附近有羊（现成羊毛来源，能原地杀羊做床）
        for (SheepEntity sheep : mod.getEntityTracker().getTrackedEntities(SheepEntity.class)) {
            if (sheep != null && sheep.isAlive() && sheep.getBlockPos().isWithinDistance(pos, BED_OPPORTUNITY_RANGE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 背包里是否已有【同色 ≥3 羊毛】= 做一张床只差木板。
     *
     * <p>必须同色（床配方是 3 块同色羊毛，混色凑不出，不能放宽成"总羊毛数 ≥3"）；
     * 计数含合成格（材料摆进 3×3 不算缺失），与全项目口径一致。
     *
     * <p>原实现 {@code BeatMinecraft2Task:4796}，2026-10-06 迁至此处（行为不变）。
     */
    public static boolean hasBedWoolInBag(AltoClef mod) {
        for (Item wool : ItemHelper.WOOL) {
            if (StorageHelper.countItemInInventoryRealIncludingCraftGrid(mod, wool) >= 3) return true;
        }
        return false;
    }

    /**
     * 本夜是否还存在"睡成"的可能 = 世界里就近有机会 <b>或</b> 背包羊毛够做床。
     *
     * <p>这是主线与次 bot 共用的<b>放弃判据</b>：两边都在"开始尝试睡"后每 30 秒查一次，
     * 不成立就本夜放弃（起床 / 回跟随 + {@code SleepOverride.skip}）。
     */
    public static boolean hasSleepChanceTonight(AltoClef mod) {
        return nightBedOpportunityNearby(mod) || hasBedWoolInBag(mod);
    }

    /**
     * bot 正睡在床里 → 起床。模仿原版 "Leave bed" 按钮的路径：先关
     * {@link SleepingChatScreen}（否则它会吞掉移动输入），再调
     * {@code player.wakeUp(updateSleepingPlayers=false, setSpawn=false)}。
     *
     * <p>setSpawn 固定 false —— 重生点在右键上床那一刻已被服务端设置，起床时不能也不需要再动它。
     *
     * <p>原实现是 {@code SleepCommand} 的私有静态方法，2026-10-06 迁至此处共用
     * （主线的"队友没睡"放弃、次 bot 的"随主 bot 睡觉"放弃都要起床）。
     *
     * @param tag 打点前缀（如 {@code "SleepSkip"} / {@code "SleepPeerWait"}），便于日志检索
     * @return 本次调用是否真的让一个睡着的 bot 起了床
     */
    public static boolean wakeUpIfSleeping(AltoClef mod, String tag) {
        try {
            if (mod.getPlayer() == null) return false;
            boolean wasSleeping = mod.getPlayer().isSleeping();
            if (MinecraftClient.getInstance().currentScreen instanceof SleepingChatScreen) {
                MinecraftClient.getInstance().setScreen(null);
            }
            if (wasSleeping) {
                mod.getPlayer().wakeUp(false, false);
                String m = "[" + tag + "] bot was sleeping, woke up";
                Debug.logMessage(m);
                Debug.logToFile("altoclef_diag.log", m);
            }
            return wasSleeping;
        } catch (Throwable ignored) {
            // 世界未就绪等极端情况：不把异常抛进任务/命令链，下一拍自会接管。
            return false;
        }
    }
}
