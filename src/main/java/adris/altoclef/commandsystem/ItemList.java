package adris.altoclef.commandsystem;

import adris.altoclef.util.ItemTarget;

import java.util.HashMap;

public class ItemList {
    public ItemTarget[] items;

    public ItemList(ItemTarget[] items) {
        this.items = items;
    }

    public static ItemList parseRemainder(String line) throws CommandException {
        line = line.trim();
        if (line.startsWith("[") && line.endsWith("]")) {
            line = line.substring(1, line.length() - 1);
            String[] parts = line.split(",");
            HashMap<String, Integer> items = new HashMap<>();
            for (String part : parts) {
                part = part.trim();
                String[] itemQuantityPair = part.split(" ");
                if (itemQuantityPair.length > 2 || itemQuantityPair.length <= 0) {
                    // Must be either "item count" or "item"
                    throw new CommandException("Resource array element must be either \"item count\" or \"item\", but \"" + part + "\"" + " has " + itemQuantityPair.length + " parts.");
                }
                String item = itemQuantityPair[0];
                int count = 1;
                if (itemQuantityPair.length > 1) {
                    try {
                        count = Integer.parseInt(itemQuantityPair[1]);
                    } catch (Exception iex) {
                        throw new CommandException("Failed to parse count for array element \"" + part + "\".");
                    }
                }
                // (2026-10-22 物品名容错) 原来直接 taskExists 硬失败，AI 拼错一个字母
                // 就整个命令作废。改走 ItemNameResolver：规范化 → 别名 → 模糊自动修正
                // → 都不行才抛（错误文案附最接近候选，AI 拿到回执能自纠重发）。
                String resolved = ItemNameResolver.resolve(item);
                items.put(resolved, items.getOrDefault(resolved, 0) + count);
            }
            if (items.size() != 0) {
                return new ItemList(items.entrySet().stream().map(entry -> new ItemTarget(entry.getKey(), entry.getValue())).toArray(ItemTarget[]::new));
            }
        } else {
            // We must be of type "item <?count>"
            String[] items = line.split(" ");
            if (items.length >= 1) {
                // (2026-10-22 物品名容错) 同数组分支：解析失败时错误文案带最接近候选。
                String name = ItemNameResolver.resolve(items[0]);
                int count = 1;
                if (items.length == 2) {
                    try {
                        count = Integer.parseInt(items[1]);
                    } catch (NumberFormatException e) {
                        throw new CommandException("Failed to parse the following argument into type " + Integer.class + ": " + items[1] + ".");
                    }
                } else if (items.length > 2) {
                    throw new CommandException("Invalid item argument structure: Must be of form `<item>` or `<item> <count>`");
                }
                return new ItemList(new ItemTarget[] {new ItemTarget(name, count)});
            }
        }
        return new ItemList(new ItemTarget[0]);
    }
}
