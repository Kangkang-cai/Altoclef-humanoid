package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.entity.GiveItemToPlayerTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.item.ItemStack;

public class GiveCommand extends Command {
    public GiveCommand() throws CommandException {
        super("give", "Collects an item and gives it to you or someone else", new Arg(String.class, "username", null, 2), new Arg(String.class, "item"), new Arg(Integer.class, "count", 1, 1));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String username = parser.get(String.class);
        if (username == null) {
            if (mod.getButler().hasCurrentUser()) {
                username = mod.getButler().getCurrentUser();
            } else {
                mod.logWarning("No butler user currently present. Running this command with no user argument can ONLY be done via butler.");
                // (2026-10-03 回执) 未起任务 → 不自报失败的话 bridge 的 action_done 永不会来
                adris.altoclef.commandsystem.CommandFeedback.fail(
                        "做不到：没有指定玩家名，而当前没有 butler 用户可代指。用法 @give <玩家名> <物品> <数量>");
                finish();
                return;
            }
        }
        String item = parser.get(String.class);
        int count = parser.get(Integer.class);
        ItemTarget target = null;
        if (TaskCatalogue.taskExists(item)) {
            // Registered item with task.
            target = TaskCatalogue.getItemTarget(item, count);
        } else {
            // Unregistered item, might still be in inventory though.
            for (int i = 0; i < mod.getPlayer().getInventory().size(); ++i) {
                ItemStack stack = mod.getPlayer().getInventory().getStack(i);
                if (!stack.isEmpty()) {
                    String name = ItemHelper.stripItemName(stack.getItem());
                    if (name.equals(item)) {
                        target = new ItemTarget(stack.getItem(), count);
                        break;
                    }
                }
            }
        }
        if (target != null) {
            Debug.logMessage("USER: " + username + " : ITEM: " + item + " x " + count);
            mod.runUserTask(new GiveItemToPlayerTask(username, target), this::finish);
        } else {
            mod.log("Item not found or task does not exist for item: " + item);
            // (2026-10-03 回执) 物品既不在背包、也没有能收集它的配方任务 → 同样是"未起任务"，
            // 必须自报失败，否则 AI 只能等超时（用户点名的"丢东西失败"场景）。
            adris.altoclef.commandsystem.CommandFeedback.fail(
                    "做不到：背包里没有 " + item + "，也没有能收集它的方法（物品名可能拼错）。");
            finish();
        }
    }

}