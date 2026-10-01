package adris.altoclef.bridge;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasksystem.Task;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.registry.Registry;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 事件采集器：把游戏内信号转成 bridge 事件，喂给 {@link EventBuffer}。
 *
 * <p>数据源（契约 §3.4 事件源选择原则）：
 * <ul>
 *   <li><b>采集类（"挖到铁矿"）</b>：以背包增量为源（{@code player.getInventory()} 槽位 diff），
 *       不用 BlockBrokenEvent 反推 —— 方块消失 ≠ 物品到手（熔炉烧炼、爆炸掉落、别人挖的
 *       都不是"我挖到了"）。每 ~0.5s 扫一次背包，白名单物品的增量进 3s 聚合窗口。</li>
 *   <li><b>task_done</b>：订阅 {@link TaskFinishedEvent}（仅 UserTaskChain 顶层任务结束才发，
 *       低频，不聚合，直接发）。</li>
 *   <li><b>death</b>（strong）：检测 {@code player.isDead()} 的 alive→dead 边沿。</li>
 *   <li><b>help</b>（strong）：订阅 {@link ChatMessageEvent}，命令前缀 + 给物/跟随/求助语义
 *       的聊天视为对方求助（对端据此强制开轮）。普通公屏聊天不进事件（克制即真实）。</li>
 * </ul>
 *
 * <p>线程模型：EventBus 发布在客户端线程（tick 内同步发布），ClientTickEvent 同理；
 * 故本类全部回调都在客户端线程，安全读 player/ inventory。EventBuffer 本身线程安全。
 */
final class EventCollector {

    private final AltoClef _mod;
    private final EventBuffer _buffer;
    private final String _botId;
    /** 背包读失败防抖（传送门/维度切换瞬零），冻结窗口内不产生事件、不动基线。 */
    private final InventoryGuard _invGuard;

    /** 白名单：Item → (中文名, 动词, type, rare)。只这些物品的增量会变成事件。 */
    private final Map<Item, ItemInfo> _watched = new IdentityHashMap<>();

    /** 上一次背包计数快照（仅白名单物品）。 */
    private final Map<Item, Integer> _lastCounts = new IdentityHashMap<>();
    private boolean _hasLastCounts = false;
    /** 冻结窗口结束后，首个有效采样先静默重建基线（跳过窗口内增量）。 */
    private boolean _pendingRebaseline = false;

    private boolean _wasDead = false;
    private int _tickCounter = 0;
    private static final int INV_SCAN_INTERVAL_TICKS = 10; // 0.5s

    /** 订阅句柄，dispose 时统一退订。 */
    private Subscription<TaskFinishedEvent> _subTask;
    private Subscription<ChatMessageEvent> _subChat;
    private Subscription<ClientTickEvent> _subTick;

    EventCollector(AltoClef mod, EventBuffer buffer, String botId, InventoryGuard invGuard) {
        this._mod = mod;
        this._buffer = buffer;
        this._botId = botId;
        this._invGuard = invGuard;
        registerWatchedItems();
    }

    private void registerWatchedItems() {
        // 矿石/粗矿（挖到）
        watch(Items.IRON_ORE, "铁矿石", "挖到", "collected", false);
        watch(Items.DEEPSLATE_IRON_ORE, "深板岩铁矿石", "挖到", "collected", false);
        watch(Items.RAW_IRON, "粗铁", "挖到", "collected", false);
        watch(Items.GOLD_ORE, "金矿石", "挖到", "collected", false);
        watch(Items.DEEPSLATE_GOLD_ORE, "深板岩金矿石", "挖到", "collected", false);
        watch(Items.RAW_GOLD, "粗金", "挖到", "collected", false);
        watch(Items.COPPER_ORE, "铜矿石", "挖到", "collected", false);
        watch(Items.DEEPSLATE_COPPER_ORE, "深板岩铜矿石", "挖到", "collected", false);
        watch(Items.RAW_COPPER, "粗铜", "挖到", "collected", false);
        watch(Items.COAL, "煤炭", "挖到", "collected", false);
        watch(Items.REDSTONE, "红石粉", "挖到", "collected", false);
        watch(Items.LAPIS_LAZULI, "青金石", "挖到", "collected", false);
        watch(Items.QUARTZ, "下界石英", "挖到", "collected", false);
        // 锭/产物（捡到，来自烧炼或拾取）
        watch(Items.IRON_INGOT, "铁锭", "捡到", "collected", false);
        watch(Items.GOLD_INGOT, "金锭", "捡到", "collected", false);
        watch(Items.COPPER_INGOT, "铜锭", "捡到", "collected", false);
        // 稀有（rare_item）
        watch(Items.DIAMOND, "钻石", "挖到", "rare_item", true);
        watch(Items.DIAMOND_ORE, "钻石矿石", "挖到", "rare_item", true);
        watch(Items.DEEPSLATE_DIAMOND_ORE, "深板岩钻石矿石", "挖到", "rare_item", true);
        watch(Items.EMERALD, "绿宝石", "挖到", "rare_item", true);
        watch(Items.EMERALD_ORE, "绿宝石矿石", "挖到", "rare_item", true);
        watch(Items.ANCIENT_DEBRIS, "远古残骸", "挖到", "rare_item", true);
        watch(Items.NETHERITE_INGOT, "下界合金锭", "捡到", "rare_item", true);
        watch(Items.BLAZE_ROD, "烈焰棒", "捡到", "rare_item", true);
        watch(Items.ENDER_PEARL, "末影珍珠", "捡到", "rare_item", true);
        watch(Items.GHAST_TEAR, "恶魂之泪", "捡到", "rare_item", true);
        watch(Items.WITHER_SKELETON_SKULL, "凋灵骷髅头", "捡到", "rare_item", true);
        watch(Items.NETHER_STAR, "下界之星", "捡到", "rare_item", true);
        // ---- 食物（值得说的；腐肉/蜘蛛眼/毒马铃薯刻意不进，捡了别刷屏）----
        watch(Items.BREAD, "面包", "捡到", "collected", false);
        watch(Items.APPLE, "苹果", "捡到", "collected", false);
        watch(Items.GOLDEN_CARROT, "金胡萝卜", "捡到", "collected", false);
        watch(Items.GOLDEN_APPLE, "金苹果", "捡到", "rare_item", true);
        watch(Items.ENCHANTED_GOLDEN_APPLE, "附魔金苹果", "捡到", "rare_item", true);
        watch(Items.COOKED_BEEF, "牛排", "捡到", "collected", false);
        watch(Items.COOKED_PORKCHOP, "熟猪排", "捡到", "collected", false);
        watch(Items.COOKED_CHICKEN, "熟鸡肉", "捡到", "collected", false);
        watch(Items.COOKED_MUTTON, "熟羊肉", "捡到", "collected", false);
        watch(Items.COOKED_COD, "熟鳕鱼", "捡到", "collected", false);
        watch(Items.COOKED_SALMON, "熟鲑鱼", "捡到", "collected", false);
        watch(Items.BAKED_POTATO, "烤马铃薯", "捡到", "collected", false);
        watch(Items.PUMPKIN_PIE, "南瓜派", "捡到", "collected", false);
        // ---- 工具/护甲/武器：铁系及以上（木/石随手可造不进事件；钻石系经 keyItems 同口径）----
        watch(Items.IRON_PICKAXE, "铁镐", "捡到", "collected", false);
        watch(Items.IRON_AXE, "铁斧", "捡到", "collected", false);
        watch(Items.IRON_SHOVEL, "铁铲", "捡到", "collected", false);
        watch(Items.IRON_HOE, "铁锄", "捡到", "collected", false);
        watch(Items.IRON_SWORD, "铁剑", "捡到", "collected", false);
        watch(Items.IRON_HELMET, "铁头盔", "捡到", "collected", false);
        watch(Items.IRON_CHESTPLATE, "铁胸甲", "捡到", "collected", false);
        watch(Items.IRON_LEGGINGS, "铁护腿", "捡到", "collected", false);
        watch(Items.IRON_BOOTS, "铁靴子", "捡到", "collected", false);
        watch(Items.DIAMOND_PICKAXE, "钻石镐", "捡到", "rare_item", true);
        watch(Items.DIAMOND_AXE, "钻石斧", "捡到", "rare_item", true);
        watch(Items.DIAMOND_SHOVEL, "钻石铲", "捡到", "rare_item", true);
        watch(Items.DIAMOND_HOE, "钻石锄", "捡到", "rare_item", true);
        watch(Items.DIAMOND_SWORD, "钻石剑", "捡到", "rare_item", true);
        watch(Items.DIAMOND_HELMET, "钻石头盔", "捡到", "rare_item", true);
        watch(Items.DIAMOND_CHESTPLATE, "钻石胸甲", "捡到", "rare_item", true);
        watch(Items.DIAMOND_LEGGINGS, "钻石护腿", "捡到", "rare_item", true);
        watch(Items.DIAMOND_BOOTS, "钻石靴子", "捡到", "rare_item", true);
        watch(Items.SHIELD, "盾牌", "捡到", "collected", false);
        watch(Items.BOW, "弓", "捡到", "collected", false);
        watch(Items.CROSSBOW, "弩", "捡到", "collected", false);
    }

    private void watch(Item item, String zh, String verb, String type, boolean rare) {
        _watched.put(item, new ItemInfo(zh, verb, rare ? "rare_item" : "collected", rare));
    }

    /** 注册 EventBus 订阅。由 BridgeHttpServer 在启动时调用。 */
    void subscribe() {
        _subTask = EventBus.subscribe(TaskFinishedEvent.class, this::onTaskFinished);
        _subChat = EventBus.subscribe(ChatMessageEvent.class, this::onChatMessage);
        _subTick = EventBus.subscribe(ClientTickEvent.class, e -> onClientTick());
    }

    /** 退订。由 BridgeHttpServer 在停止时调用。 */
    void dispose() {
        EventBus.unsubscribe(_subTask);
        EventBus.unsubscribe(_subChat);
        EventBus.unsubscribe(_subTick);
        _subTask = null;
        _subChat = null;
        _subTick = null;
    }

    private void onTaskFinished(TaskFinishedEvent evt) {
        Task t = evt.lastTaskRan;
        String label;
        if (t == null) {
            label = "完成了一项任务";
        } else {
            String raw = t.toString();
            if (raw == null || raw.isEmpty()) raw = t.getClass().getSimpleName();
            label = "完成：" + truncate(raw, 30);
        }
        // task_done 不聚合（UserTaskChain 顶层任务结束，本就低频）
        _buffer.emitImmediate("task_done", label, false);
    }

    private void onChatMessage(ChatMessageEvent evt) {
        if (evt == null || evt.message == null) return;
        String text;
        try {
            text = evt.message.getString();
        } catch (Throwable ignored) {
            return;
        }
        if (text == null || text.isEmpty()) return;

        String prefix;
        try {
            prefix = _mod.getModSettings().getCommandPrefix();
        } catch (Throwable ignored) {
            prefix = "@";
        }
        if (prefix == null) prefix = "@";

        // 只把"命令前缀 + 给物/跟随/求助语义"的聊天当 strong help 事件。
        // 普通公屏聊天不进事件（克制即真实，§0）。但ler 已专门解析 whisper 做控制，
        // 这里只做"社交感知"层：让对端 LLM 知道有人在 MC 里发了求助类消息。
        String lower = text.toLowerCase();
        if (text.contains(prefix) && (
                lower.contains("give") || lower.contains("follow") || lower.contains("help")
                        || lower.contains("come") || text.contains("给") || text.contains("来")
                        || text.contains("帮") || text.contains("救"))) {
            _buffer.emitImmediate("help", "收到求助：" + truncate(text, 40), true);
        }
        // 其余聊天一律不进事件，避免公屏噪声。
    }

    private void onClientTick() {
        if (!AltoClef.inGame()) {
            _hasLastCounts = false;
            _wasDead = false;
            return;
        }
        var player = _mod.getPlayer();
        if (player == null) return;

        // 死亡边沿：alive → dead
        boolean dead = player.isDead();
        if (!_wasDead && dead) {
            _buffer.emitImmediate("death", "死了", true);
        }
        _wasDead = dead;

        // 背包增量扫描：每 INV_SCAN_INTERVAL_TICKS 一次
        if (++_tickCounter < INV_SCAN_INTERVAL_TICKS) return;
        _tickCounter = 0;

        Map<Item, Integer> now = scanInventory(player);
        // 读失败防抖：总量骤降/归零（传送门/维度切换瞬零）→ 冻结窗口，跳过增量与基线
        boolean valid = _invGuard.onSample(inventoryTotal(player), System.currentTimeMillis());
        if (!valid) {
            _pendingRebaseline = true;
            return;
        }
        if (!_hasLastCounts) {
            _lastCounts.clear();
            _lastCounts.putAll(now);
            _hasLastCounts = true;
            return;
        }
        // 冻结窗口刚结束：静默重建基线，窗口内的增量（含伪拾取）全部跳过
        if (_pendingRebaseline) {
            _pendingRebaseline = false;
            _lastCounts.clear();
            _lastCounts.putAll(now);
            return;
        }
        // 增量：now > last 的白名单物品
        for (Map.Entry<Item, Integer> e : now.entrySet()) {
            Item item = e.getKey();
            int cur = e.getValue();
            int prev = _lastCounts.getOrDefault(item, 0);
            if (cur > prev) {
                ItemInfo info = _watched.get(item);
                if (info != null) {
                    int delta = cur - prev;
                    String itemKey = itemId(item);
                    _buffer.collectOrAccumulate(info.type, itemKey, info.zh, info.verb,
                            delta, false); // 采集类一律 strong=false
                }
            }
        }
        _lastCounts.clear();
        _lastCounts.putAll(now);
    }

    /** 扫玩家背包（主+护甲+副手），只统计白名单物品的计数。 */
    private Map<Item, Integer> scanInventory(net.minecraft.client.network.ClientPlayerEntity player) {
        Map<Item, Integer> counts = new IdentityHashMap<>();
        var inv = player.getInventory();
        if (inv == null) return counts;
        addInto(counts, inv.main);
        addInto(counts, inv.armor);
        addInto(counts, inv.offHand);
        // 只保留白名单的，省内存（removeIf 走 keySet 的活视图，原地删）
        counts.keySet().removeIf(k -> !_watched.containsKey(k));
        return counts;
    }

    /** 背包全量物品计数和（所有非空栈，不限白名单）——防抖骤降判定的输入。 */
    private static int inventoryTotal(net.minecraft.client.network.ClientPlayerEntity player) {
        var inv = player.getInventory();
        if (inv == null) return 0;
        int total = 0;
        total += countStacks(inv.main);
        total += countStacks(inv.armor);
        total += countStacks(inv.offHand);
        return total;
    }

    private static int countStacks(java.util.List<ItemStack> stacks) {
        int n = 0;
        for (ItemStack s : stacks) {
            if (s != null && !s.isEmpty()) n += s.getCount();
        }
        return n;
    }

    private void addInto(Map<Item, Integer> counts, java.util.List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) continue;
            Item it = s.getItem();
            if (!_watched.containsKey(it)) continue;
            counts.merge(it, s.getCount(), Integer::sum);
        }
    }

    private String itemId(Item item) {
        try {
            return Registry.ITEM.getId(item).toString();
        } catch (Throwable t) {
            return item.toString();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record ItemInfo(String zh, String verb, String type, boolean rare) {}
}
