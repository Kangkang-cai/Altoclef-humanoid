package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper functions to interpret entity state
 */
public class EntityHelper {

    public static final double ENTITY_GRAVITY = 0.08; // per second

    public static boolean isAngryAtPlayer(AltoClef mod, Entity mob) {
        boolean hostile = isGenerallyHostileToPlayer(mod, mob);
        if (mob instanceof LivingEntity entity) {
            return hostile && entity.canSee(mod.getPlayer());
        }
        return hostile;
    }

    public static boolean isGenerallyHostileToPlayer(AltoClef mod, Entity hostile) {
        // TODO: Ignore on Peaceful difficulty.
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        // NOTE: These do not work.
        if (hostile instanceof EndermanEntity enderman) {
            return enderman.isAngryAt(player) && enderman.isAngry();
        }
        if (hostile instanceof PiglinEntity) {
            // Angry if we're not wearing gold
            return !StorageHelper.isArmorEquipped(mod, ItemHelper.GOLDEN_ARMORS);
        }
        if (hostile instanceof ZombifiedPiglinEntity zombie) {
            // Will ALWAYS be false.
            return zombie.hasAngerTime() && zombie.isAngryAt(player);
        }
        return !isTradingPiglin(hostile);
    }

    public static boolean isTradingPiglin(Entity entity) {
        if (entity instanceof PiglinEntity pig) {
            for (ItemStack stack : pig.getItemsHand()) {
                if (stack.getItem().equals(Items.GOLD_INGOT)) {
                    // We're trading with this one, ignore it.
                    return true;
                }
            }
        }
        return false;
    }

    // [PIGLIN-DIAG] (2026-09-06 Phase 0 只读诊断) 0.5s 节流, 打印 32 格内猪灵系实体
    // 的客户端可读激怒信号。目的: 验证 anger 状态在客户端的同步情况, 决定 Phase 1
    // 判定层走真实 API 还是纯事件记忆 (HitRegistry)。不改变任何行为。
    private static long _lastPiglinDiagMs = 0;

    public static void logPiglinDiag(AltoClef mod) {
        long now = System.currentTimeMillis();
        if (now - _lastPiglinDiagMs < 500) return;
        _lastPiglinDiagMs = now;
        try {
            List<Entity> found;
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                found = new ArrayList<>(mod.getEntityTracker().getTrackedEntities(PiglinEntity.class));
                found.addAll(mod.getEntityTracker().getTrackedEntities(ZombifiedPiglinEntity.class));
                found.addAll(mod.getEntityTracker().getTrackedEntities(PiglinBruteEntity.class));
            }
            StringBuilder sb = new StringBuilder("[PIGLIN-DIAG] goldArmor=")
                    .append(StorageHelper.isArmorEquipped(mod, ItemHelper.GOLDEN_ARMORS))
                    .append(" nearby:");
            boolean any = false;
            for (Entity e : found) {
                if (!e.isAlive() || !e.isInRange(mod.getPlayer(), 32)) continue;
                any = true;
                sb.append(" | ").append(e.getClass().getSimpleName())
                        .append("@").append(e.getBlockPos())
                        .append(" dist=").append(String.format("%.1f", e.distanceTo(mod.getPlayer())));
                if (e instanceof PiglinEntity pig) {
                    // 成年猪灵: anger 走 brain (ANGRY_AT 记忆)。若客户端 brain 未同步,
                    // 这里将恒为空 → Phase 1 必须走事件记忆。
                    sb.append(" angryAtMem=").append(pig.getBrain().getOptionalMemory(MemoryModuleType.ANGRY_AT).isPresent())
                            .append(" trading=").append(isTradingPiglin(pig))
                            .append(" baby=").append(pig.isBaby())
                            .append(" sees=").append(pig.canSee(mod.getPlayer()));
                } else if (e instanceof ZombifiedPiglinEntity zombie) {
                    // 僵尸猪灵: anger 走服务端 AngerableManager, 客户端预期恒 false (Phase 0 实测确认)。
                    sb.append(" hasAngerTime=").append(zombie.hasAngerTime())
                            .append(" angryAtMem=").append(zombie.getAngryAt())
                            .append(" isAngryAtPlayer=").append(zombie.isAngryAt(mod.getPlayer()));
                }
                // PiglinBruteEntity: 无 anger 概念, 仅报位置与距离。
            }
            if (!any) sb.append(" none");
            Debug.logToFile("altoclef_diag.log", sb.toString());
        } catch (Exception ignored) { }
    }

    /**
     * Calculate the resulting damage dealt to a player as a result of some damage.
     * If this player were to receive this damage, the player's health will be subtracted by the resulting value.
     */
    public static double calculateResultingPlayerDamage(PlayerEntity player, DamageSource source, double damageAmount) {
        // Copied logic from `PlayerEntity.applyDamage`

        if (player.isInvulnerableTo(source))
            return 0;

        // Armor Base
        if (!source.bypassesArmor()) {
            damageAmount = DamageUtil.getDamageLeft((float) damageAmount, (float) player.getArmor(), (float) player.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS));
        }

        // Enchantments & Potions
        if (!source.isUnblockable()) {
            int k;
            if (player.hasStatusEffect(StatusEffects.RESISTANCE) && source != DamageSource.OUT_OF_WORLD) {
                //noinspection ConstantConditions
                k = (player.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1) * 5;
                int j = 25 - k;
                double f = damageAmount * (double)j;
                double g = damageAmount;
                damageAmount = Math.max(f / 25.0F, 0.0F);
            }

            if (damageAmount <= 0.0) {
                damageAmount = 0.0;
            } else {
                k = EnchantmentHelper.getProtectionAmount(player.getArmorItems(), source);
                if (k > 0) {
                    damageAmount = DamageUtil.getInflictedDamage((float)damageAmount, (float)k);
                }
            }
        }

        // Absorption
        damageAmount = Math.max(damageAmount - player.getAbsorptionAmount(), 0.0F);
        return damageAmount;
    }
}
