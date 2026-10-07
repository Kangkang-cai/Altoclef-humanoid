package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.commandsystem.CommandFeedback;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.registry.Registry;

/**
 * (2026-10-07 交付闭环) {@code @pickup <item>} —— 走过去把<b>附近地上的该物品</b>捡起来。
 *
 * <h3>为什么需要它</h3>
 * {@code @give} 的交付方式只能是把物品<b>丢在地上</b>（MC 没有"塞进对方背包"的原语）。
 * 给方丢在接收方脚边之后，接收方得自己走过去才能拾取 —— 真人玩家会自己捡，
 * 而另一个 bot 不会，于是出现"丢了但对方不捡"（1007 14:10 现场）。
 * 本条命令就是让<b>接收方 bot</b>主动去捡的入口，由给方在交付完成后自动调用
 * （见 {@code bridge/PeerCommandSender}）。
 *
 * <h3>按类型找最近的，不按坐标</h3>
 * 底层 {@link PickupDroppedItemTask} 是按<b>物品类型</b>匹配最近的掉落物。
 * 所以附近若恰有同名掉落物，可能捡到那一份而不是刚丢的那份 —— 用户 1007 拍板
 * "小概率事件、问题不大"，换取实现简单（不必传坐标 + 半径）。
 *
 * <h3>追距上限</h3>
 * {@link #MAX_PURSUIT_RANGE_SQ} = 32 格。交付场景掉落物就在脚边，该上限足够宽松；
 * 设上限是为了防"别处几十格外的陈旧同名掉落物"把 bot 拽跑长途。
 * 真实出口由任务自身保证：无可追目标即结束（含掉落物被捡走 / despawn / 全部拉黑）。
 */
public class PickupCommand extends Command {

    /** 追距上限（平方）；见类注释。 */
    private static final double MAX_PURSUIT_RANGE_SQ = 32 * 32;

    public PickupCommand() throws CommandException {
        super("pickup", "Picks up nearby dropped items of the given type",
                new Arg(String.class, "item"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String itemName = parser.get(String.class);
        Item item = resolveItem(mod, itemName);
        if (item == null) {
            // 未起任务 → 必须自报失败，否则 bridge 的 action_done 永不会来（同 GiveCommand 口径）
            CommandFeedback.fail("做不到：认不出物品 \"" + itemName + "\"（物品名可能拼错）。");
            finish();
            return;
        }
        Debug.logMessage("USER: pickup " + itemName + " -> " + ItemHelper.stripItemName(item));
        mod.runUserTask(
                new PickupDroppedItemTask(new ItemTarget[]{new ItemTarget(item, 1)}, true, MAX_PURSUIT_RANGE_SQ),
                this::finish);
    }

    /**
     * 物品名解析（三级，前级命中即返回）：
     * <ol>
     *   <li>{@link TaskCatalogue} 注册名 —— 支持 {@code wool}/{@code planks} 这类"一类多物品"别名；</li>
     *   <li>全物品注册表按短名（{@code cooked_porkchop}）匹配 —— <b>必需</b>：冶炼产物等
     *       没有收集任务链，第 1 级查不到，而交付场景恰好以这类物品为主（1007 实测就是熟猪排）；</li>
     *   <li>背包内已有同类物品的反查 —— 与 {@code GiveCommand} 同款兜底，覆盖"名称为本地化键"等边角。</li>
     * </ol>
     */
    private static Item resolveItem(AltoClef mod, String itemName) {
        if (itemName == null || itemName.isBlank()) return null;
        if (TaskCatalogue.taskExists(itemName)) {
            ItemTarget target = TaskCatalogue.getItemTarget(itemName, 1);
            if (target != null && target.getMatches().length > 0) {
                return target.getMatches()[0];
            }
        }
        String normalized = itemName.trim().toLowerCase();
        for (Item item : Registry.ITEM) {
            String shortName = ItemHelper.stripItemName(item);
            if (shortName != null && shortName.equalsIgnoreCase(normalized)) return item;
            if (item.getTranslationKey().equalsIgnoreCase(normalized)) return item;
        }
        for (int i = 0; i < mod.getPlayer().getInventory().size(); ++i) {
            ItemStack stack = mod.getPlayer().getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (ItemHelper.stripItemName(stack.getItem()).equalsIgnoreCase(normalized)) {
                return stack.getItem();
            }
        }
        return null;
    }
}
