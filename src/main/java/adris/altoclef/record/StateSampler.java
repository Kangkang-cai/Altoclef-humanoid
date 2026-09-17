package adris.altoclef.record;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * (2026-09-17 数据集录制) 通道 C —— 自身状态 (每帧全量)。
 *
 * <p>给的是"bot 自己身上发生了什么": 位置/速度/朝向/血量/饥饿/氧气/环境标志/
 * 手持物/背包物品计数/状态效果/当前使用中的物品。三层任务都需要它, 只是各自
 * 关注点不同 (L1 看血量定生死, L2 看背包决定拿什么, L3 看位姿决定怎么走)。
 *
 * <p>严格只读: 只调对象 getter, 不写任何字段; 任何异常由上层 RecordManager 兜住。
 */
final class StateSampler {

    private StateSampler() {}

    // (2026-09-17) 物品/效果的注册名缓存。20Hz × 每次几十个 stack, 若每帧都
    // Registry.getId(...).toString() 会产生大量短命字符串; 注册表内容有限且稳定
    // (物品约千级), 缓存是有界且划算的。仅客户线程访问。
    private static final Map<Item, String> _itemIdCache = new IdentityHashMap<>();
    private static final Map<net.minecraft.entity.effect.StatusEffect, String> _effectIdCache = new IdentityHashMap<>();

    static String itemId(Item item) {
        String s = _itemIdCache.get(item);
        if (s == null) {
            try {
                s = Registry.ITEM.getId(item).toString();
            } catch (Throwable t) {
                s = "unknown";
            }
            _itemIdCache.put(item, s);
        }
        return s;
    }

    private static String effectId(net.minecraft.entity.effect.StatusEffect effect) {
        String s = _effectIdCache.get(effect);
        if (s == null) {
            try {
                s = Registry.STATUS_EFFECT.getId(effect).toString();
            } catch (Throwable t) {
                s = "unknown";
            }
            _effectIdCache.put(effect, s);
        }
        return s;
    }

    /** 返回 "self" 通道的 JSON 对象字面量 (无玩家时返回 null 字面量)。 */
    static String sample(AltoClef mod) {
        ClientPlayerEntity p = mod.getPlayer();
        if (p == null) return "null";

        StringBuilder sb = new StringBuilder(768);
        sb.append('{');

        Vec3d pos = p.getPos();
        Vec3d vel = p.getVelocity();
        sb.append("\"pos\":");
        Json.vec(sb, pos.x, pos.y, pos.z);
        sb.append(",\"vel\":");
        Json.vec(sb, vel.x, vel.y, vel.z);
        sb.append(",\"block\":");
        Json.ivec(sb, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        sb.append(",\"yaw\":").append(Json.f(p.getYaw()));
        sb.append(",\"pitch\":").append(Json.f(p.getPitch()));
        sb.append(",\"headYaw\":").append(Json.f(p.getHeadYaw()));
        sb.append(",\"fallDist\":").append(Json.f(p.fallDistance));

        // 生命体征
        sb.append(",\"hp\":").append(Json.f(p.getHealth()));
        sb.append(",\"maxHp\":").append(Json.f(p.getMaxHealth()));
        sb.append(",\"food\":").append(p.getHungerManager().getFoodLevel());
        sb.append(",\"sat\":").append(Json.f(p.getHungerManager().getSaturationLevel()));
        sb.append(",\"air\":").append(p.getAir());
        sb.append(",\"xpLevel\":").append(p.experienceLevel);

        // 环境/姿态标志
        sb.append(",\"onGround\":").append(p.isOnGround());
        sb.append(",\"inWater\":").append(p.isTouchingWater());
        sb.append(",\"submerged\":").append(p.isSubmergedInWater());
        sb.append(",\"inLava\":").append(p.isInLava());
        sb.append(",\"onFire\":").append(p.isOnFire());
        sb.append(",\"sneaking\":").append(p.isSneaking());
        sb.append(",\"sprinting\":").append(p.isSprinting());
        sb.append(",\"swimming\":").append(p.isSwimming());
        sb.append(",\"riding\":").append(p.getVehicle() != null);
        sb.append(",\"dead\":").append(p.isDead());

        // 手持
        sb.append(",\"main\":");
        Json.str(sb, stackId(p.getMainHandStack()));
        sb.append(",\"off\":");
        Json.str(sb, stackId(p.getOffHandStack()));
        sb.append(",\"hotbarSel\":").append(p.getInventory().selectedSlot);
        sb.append(",\"usingItem\":");
        Json.str(sb, p.isUsingItem() ? itemId(p.getActiveItem().getItem()) : null);
        sb.append(",\"cursor\":");
        Json.str(sb, stackId(StorageHelper.getItemStackInCursorSlot()));

        // 状态效果
        sb.append(",\"effects\":[");
        boolean firstEffect = true;
        for (StatusEffectInstance e : p.getStatusEffects()) {
            if (e == null) continue;
            if (!firstEffect) sb.append(',');
            firstEffect = false;
            sb.append("{\"id\":");
            Json.str(sb, effectId(e.getEffectType()));
            sb.append(",\"amp\":").append(e.getAmplifier());
            sb.append(",\"dur\":").append(e.getDuration());
            sb.append('}');
        }
        sb.append(']');

        // 背包全量计数 (可由设置关闭: 重复度极高, 体积大头)
        if (mod.getModSettings() == null || mod.getModSettings().shouldRecordInventoryPerTick()) {
            sb.append(",\"inv\":");
            appendInventory(sb, p);
        }

        sb.append('}');
        return sb.toString();
    }

    private static String stackId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return itemId(stack.getItem());
    }

    private static void appendInventory(StringBuilder sb, ClientPlayerEntity p) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        try {
            countInto(counts, p.getInventory().main);
            countInto(counts, p.getInventory().armor);
            countInto(counts, p.getInventory().offHand);
        } catch (Throwable ignored) {
            // 背包读取失败就当空背包, 不打断整帧
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Json.str(sb, e.getKey());
            sb.append(':').append(e.getValue());
        }
        sb.append('}');
    }

    private static void countInto(Map<String, Integer> counts, java.util.List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) continue;
            counts.merge(itemId(s.getItem()), s.getCount(), Integer::sum);
        }
    }
}