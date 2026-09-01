package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.mob.CreeperEntity;

/**
 * Rates how dangerous an entity is to the player right now.
 *
 * Higher score = more dangerous. Used for "fight or flight" decisions
 * and for picking which enemy to attack first.
 */
public final class ThreatEvaluator {

    private ThreatEvaluator() {}

    /**
     * Evaluate how much of a threat `entity` is to the player this tick.
     */
    public static double evaluate(AltoClef mod, Entity entity) {
        if (entity == null || !entity.isAlive()) return 0;
        double base = getBaseThreat(mod, entity);
        double distance = Math.sqrt(entity.squaredDistanceTo(mod.getPlayer()));
        double factor;
        if (entity instanceof AbstractSkeletonEntity) {
            // Ranged attackers: threat doesn't drop off with distance.
            factor = 1.0;
        } else if (distance < 5) {
            factor = 1.5;
        } else if (distance < 10) {
            factor = 1.0;
        } else if (distance < 20) {
            factor = 0.6;
        } else {
            factor = 0.3;
        }
        return base * factor;
    }

    private static double getBaseThreat(AltoClef mod, Entity entity) {
        if (entity instanceof CreeperEntity creeper) {
            // A fusing creeper is an emergency.
            if (creeper.getClientFuseTime(1) > 0.001f) return 100;
            return 40;
        }
        if (entity instanceof WitherSkeletonEntity) return 70;
        if (entity instanceof SkeletonEntity) return 60;
        if (entity instanceof WitchEntity) return 50;
        if (entity instanceof BlazeEntity) return 55;
        if (entity instanceof HoglinEntity || entity instanceof ZoglinEntity) return 70;
        if (entity instanceof EndermanEntity) return 70;
        if (entity instanceof PiglinBruteEntity) return 45;
        if (entity instanceof PiglinEntity) return 30;
        if (entity instanceof AbstractPiglinEntity) return 30;
        if (entity instanceof GhastEntity) return 35;
        // Generic melee (zombie, spider, cave spider, drowned, etc.)
        return 25;
    }

    /**
     * (2026-08-23) 是否有盾牌 (副手或背包)。战斗任务会在 FIGHT 时把
     * 背包盾牌装到副手, 所以"背包有"也算"有"。
     */
    public static boolean hasShield(AltoClef mod) {
        return mod.getPlayer().getOffHandStack().getItem() == net.minecraft.item.Items.SHIELD
                || mod.getItemStorage().hasItem(net.minecraft.item.Items.SHIELD);
    }

    /**
     * Rough estimate of how much total threat the player can handle.
     * Tunable constants live here so combat feel can be adjusted in one place.
     */
    public static double estimatePlayerStrength(AltoClef mod) {
        float health = mod.getPlayer().getHealth();
        int armor = mod.getPlayer().getArmor();
        // (2026-08-22 有盾牌多战斗) 盾牌判定含背包 — 见 hasShield 注释;
        // 加成 1.6→2.0: 没盾牌多跑, 有盾牌多战斗 (用户设计)。
        boolean hasShield = hasShield(mod);

        // Rough: full health + iron armor ≈ enough to take on ~2 zombies.
        double strength = health * (1.0 + armor * 0.1);
        if (hasShield) strength *= 2.0;
        return strength;
    }

    /**
     * Whether we think we can win against the given total threat.
     */
    public static boolean canWin(double totalThreat, double ourStrength) {
        // Safety factor: only fight when we have a comfortable margin.
        return totalThreat * 1.5 < ourStrength;
    }
}
