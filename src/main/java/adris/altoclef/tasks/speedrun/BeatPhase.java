package adris.altoclef.tasks.speedrun;

/**
 * (2026-10-06 多 bot 阶段跟随) 主线「大阶段」的<b>稳定标识</b>。
 *
 * <h3>为什么需要它</h3>
 * 用户要求：主 bot 干某些大任务时次 bot 跟随、其余时间次 bot 自己跑主线。为此次 bot 必须
 * 知道"主 bot 现在在哪个大任务"。但 {@code /status.taskDetail} 是<b>给人看</b>的显示串
 * （中文/英文混排，随时会为可读性改），把它当跨进程契约 = 次 bot 的判据随文案漂移。
 * 故本枚举是主线上报的<b>唯一阶段词表</b>，取值稳定、与显示串解耦。
 *
 * <h3>取值（编号 = 用户 2026-10-06 22:5x 清单序号；10/11 合并）</h3>
 * <pre>
 *   1 WOOD           囤木头（含"先造石斧"）
 *   2 GEAR           铁装 / 钻石装
 *   3 MINING         挖矿（贪心挖可见矿）
 *   4 FOOD           收食物              ← 用户要跟
 *   5 PORTAL         建传送门 / 进下界    ← 用户要跟
 *   6 FIRE_RES       防火药水
 *   7 BLAZE_RODS     找堡垒 / 收烈焰棒    ← 用户要跟
 *   8 ENDER_PEARLS   收末影珍珠（含诡异森林蹲守） ← 用户要跟
 *   9 CRAFT_EYES     合成烈焰粉 / 末影之眼
 *  10+11 STRONGHOLD  丢眼定位要塞 + 要塞探索 ← 用户要跟
 *  12 OPEN_PORTAL    插眼开末地门        ← 用户要跟
 *  13 END_FIGHT      进末地 / 打龙
 *  UNKNOWN          未识别 / 主线未跑
 * </pre>
 */
public enum BeatPhase {
    UNKNOWN,
    WOOD,
    GEAR,
    MINING,
    FOOD,
    PORTAL,
    FIRE_RES,
    BLAZE_RODS,
    ENDER_PEARLS,
    CRAFT_EYES,
    STRONGHOLD,
    OPEN_PORTAL,
    END_FIGHT;

    /**
     * 从 {@code BeatMinecraft2Task.setDebugState} 的显示串映射到阶段。
     *
     * <p><b>单一注入点</b>：主线的 {@code setDebugState} 覆写是全任务"当前阶段"的唯一出口
     * （每拍只会设一个显示串），所以这张映射表只写在这里；日后改显示文案也只需改这一处。
     *
     * <p><b>匹配顺序：先特化、后泛化</b>。反例：{@code "Crafting blaze powder"} 同时含
     * "blaze"（像收棒）与合成语义；{@code "Getting Food for Ender Eye journey"} 含 "Food" 但也
     * 属于眼睛线。若先判泛化词就会被吞错，故合成/末地/要塞/下界这些特化项一律排在
     * WOOD/FOOD/GEAR/MINING 之前。
     */
    public static BeatPhase fromDebugState(String s) {
        if (s == null || s.isEmpty()) return UNKNOWN;

        // ---- 合成（最特化，必须先判）----
        if (s.contains("Crafting blaze powder") || s.contains("Crafting Ender Eyes")) return CRAFT_EYES;

        // ---- 末地 ----
        if (s.contains("Entering End") || s.contains("Bed strats") || s.contains("regular strats")
                || s.contains("dragon") || s.contains("ESCAPE dragons") || s.contains("EVADE dragon")) {
            return END_FIGHT;
        }

        // ---- 要塞 / 末地门 ----
        if (s.contains("Opening End Portal")) return OPEN_PORTAL;
        if (s.contains("Locating End Portal") || s.contains("Setting spawn near end portal")
                || s.contains("Collecting eyes for portal frames")) return STRONGHOLD;

        // ---- 下界 ----
        if (s.contains("Getting Blaze Rods")) return BLAZE_RODS;
        if (s.contains("Ender Pearls") || s.contains("warped forest") || s.contains("enderman")) return ENDER_PEARLS;
        if (s.contains("fire resistance")) return FIRE_RES;
        if (s.contains("Going to Nether") || s.contains("nether loot")) return PORTAL;

        // ---- 主世界：采集 / 装备 ----
        if (s.contains("Stockpiling wood") || s.contains("Restocking wood")
                || s.contains("collect wood") || s.contains("stone axe")) return WOOD;
        if (s.contains("Food") || s.contains("food") || s.contains("Smelting raw")) return FOOD;
        if (s.contains("iron armor") || s.contains("diamond gear") || s.contains("Equipping owned")) return GEAR;
        if (s.contains("Greedy mining")) return MINING;

        return UNKNOWN;
    }
}
