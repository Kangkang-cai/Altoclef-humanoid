package adris.altoclef.tasks.speedrun;

public class BeatMinecraftConfig {
    public int targetEyes = 16; // how many eyes of ender to collect (2026-08-25 用户 14→16: 多一点保险)
    public int minimumEyes = 12; // the MINIMUM amount of eyes of ender to have, assuming we don't have our stronghold portal opened yet
    public boolean placeSpawnNearEndPortal = true;
    public boolean barterPearlsInsteadOfEndermanHunt;
    public boolean sleepThroughNight = true;
    public boolean rePickupCraftingTable = true;
    public boolean rePickupFurnace = true;
    public boolean searchRuinedPortals = true;
    public boolean searchDesertTemples = true;
    public int minFoodUnits = 20;
    public int foodUnits = 300;
    // (2026-09-15 进下界前食物硬闸, 用户"去地狱之前必须达到150units") 进下界
    // (_goToNetherTask) 前背包食物分低于该值 → 先 CollectFoodTask 收满。
    // 不受觅食远征"超时降低"影响 (硬闸优先)。<=0 时禁用。
    public int preNetherFoodUnits = 150;
    // 木头囤货：开局阶段至少囤的原木数（配合斧子收集）
    // (2026-08-25 64→48 足够整局; 2026-08-31 用户降 48→25: 25 根足够, 减少囤货时间/抢占)
    public int minWoodLogs = 25;
    // 囤满一次后的【补货水位】: 只有原木低于该值才重新去囤。注意它只是
    // 【触发线】, 不是补货量 —— 一旦触发, 收集目标仍是 minWoodLogs(25) 根,
    // 所以补一次能用很久, 不会"少一个就回来补"(见 2026-08-21 用户反馈的
    // 优先级过高问题)。
    // (2026-09-01 用户: 跌破 4 再补货) 降到 4, 放宽烧肉/挖石期间的木头消耗。
    // (2026-09-17 用户: "补货值改为 6, 补货不应该频繁触发, 要收集就一次多收集点")
    // 4→6: 抬高水位让补货更早介入(原木 5 根时就开始补), 补货量仍 = minWoodLogs,
    // 因此触发频次由"补到 25 后消耗掉 19 根"决定, 不会变频繁。
    public int woodRestockThreshold = 6;
    public int requiredBeds = 10;
    public boolean alwaysCookRawFood = true;
    public int minBuildMaterialCount = 5;
    public int buildMaterialCount = 40;
    public double dragonHeadCloseEnoughClickBedRange = 5.3;
}
