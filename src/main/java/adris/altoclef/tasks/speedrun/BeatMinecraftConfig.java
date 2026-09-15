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
    // 囤满一次后的补货水位：只有原木低于该值才重新去囤（64 根用掉 1 根
    // 就回去挖 1 根的"优先级过高"问题，见 2026-08-21 用户反馈）
    // (2026-09-01 用户: 跌破 4 再补货) 降到 4, 放宽烧肉/挖石期间的木头消耗,
    // 避免原木一跌破 16 就频繁打断挖石/烧肉去补货。
    public int woodRestockThreshold = 4;
    public int requiredBeds = 10;
    public boolean alwaysCookRawFood = true;
    public int minBuildMaterialCount = 5;
    public int buildMaterialCount = 40;
    public double dragonHeadCloseEnoughClickBedRange = 5.3;
}
