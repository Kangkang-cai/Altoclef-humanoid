package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.ThreatEvaluator;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controls and applies killaura
 */
public class KillAura {

    // Smart aura data
    private final List<Entity> _targets = new ArrayList<>();
    private final TimerGame _hitDelay = new TimerGame(0.2);
    private double _forceFieldRange = Double.POSITIVE_INFINITY;
    private Entity _forceHit = null;

    public void tickStart(AltoClef mod) {
        _targets.clear();
        _forceHit = null;
    }

    public void applyAura(AltoClef mod, Entity entity) {
        _targets.add(entity);
        // Always hit ghast balls.
        if (entity instanceof FireballEntity) _forceHit = entity;
    }

    public void tickEnd(AltoClef mod) {
        // Run force field on map
        switch (mod.getModSettings().getForceFieldStrategy()) {
            case FASTEST:
                performFastestAttack(mod);
                break;
            case SMART:

                if (_targets.size() <= 2 || _targets.stream().allMatch(entity -> entity instanceof SkeletonEntity) ) {
                    performDelayedAttack(mod);
                } else {
                    // Attack force mobs ALWAYS.
                    if (_forceHit != null) {
                        attack(mod, _forceHit);
                        break;
                    }
                    if (_hitDelay.elapsed()) {
                        _hitDelay.reset();

                        Optional<Entity> toHit = getMostThreateningTarget(mod);

                        toHit.ifPresent(entity -> attack(mod, entity));
                    }
                }
                break;
            case DELAY:
                performDelayedAttack(mod);
                break;
            case OFF:
                break;
        }
    }

    public void setRange(double range) {
        _forceFieldRange = range;
    }

    private void performDelayedAttack(AltoClef mod) {
        // wait for the attack delay
        if (_targets.isEmpty()) {
            return;
        }

        Optional<Entity> toHit = getMostThreateningTarget(mod);

        if (mod.getPlayer() == null || mod.getPlayer().getAttackCooldownProgress(0) < 1) {
            return;
        }

        toHit.ifPresent(entity -> attack(mod, entity, true));
    }

    /**
     * Pick the most dangerous nearby target, breaking ties by distance.
     * (2026-09-03 用户"两只僵尸应优先打近的, 远近会交换") 旧实现只按威胁值
     * max: 两只同类型僵尸威胁值完全相同(如都在 5 格内 factor=1.5) → Stream.max
     * 保留遍历顺序里"最后一个", 顺序每 tick 抖动 → 挥击目标在两只之间来回换。
     * 威胁值相等(或极度接近)时改选最近的, 让贴身近战稳定打在脸上那只。
     */
    private Optional<Entity> getMostThreateningTarget(AltoClef mod) {
        return _targets.stream().max((a, b) -> {
            int cmp = Double.compare(ThreatEvaluator.evaluate(mod, a), ThreatEvaluator.evaluate(mod, b));
            if (cmp != 0) return cmp;
            // 威胁相同时选更近的: 返回正表示 a 更大 → a 近(距离小)时返回正。
            return -Double.compare(
                    a.squaredDistanceTo(mod.getPlayer()),
                    b.squaredDistanceTo(mod.getPlayer()));
        });
    }

    private void performFastestAttack(AltoClef mod) {
        // Just attack whenever you can
        for (Entity entity : _targets) {
            attack(mod, entity);
        }
    }

    private void attack(AltoClef mod, Entity entity) {
        attack(mod, entity, false);
    }
    private void attack(AltoClef mod, Entity entity, boolean equipSword) {
        if (entity == null) return;
        if (Double.isInfinite(_forceFieldRange) || entity.squaredDistanceTo(mod.getPlayer()) < _forceFieldRange * _forceFieldRange) {
            boolean canAttack;
            if (equipSword) {
                // Equip sword, or if we don't have one just use our fists.
                Item[] swordsTopPriorityFirst = new Item[] {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD};
                if (mod.getItemStorage().hasItem(swordsTopPriorityFirst)) {
                    canAttack = mod.getSlotHandler().forceEquipItem(swordsTopPriorityFirst);
                } else {
                    canAttack = mod.getSlotHandler().forceDeequipHitTool();
                }
            } else {
                // Equip non-tool
                canAttack = mod.getSlotHandler().forceDeequipHitTool();
            }
            if (canAttack) {
                // Log the attack so it can be extracted as an action label
                // (killaura attacks happen outside the task tree).
                Debug.logInternal("KillAura attacking " + entity.getType().getTranslationKey());
                mod.getControllerExtras().attack(entity);
            }
        }
    }

    public enum Strategy {
        OFF,
        FASTEST,
        DELAY,
        SMART
    }

}
