package dev.tntcoating.core;

import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Gates one inventory-weapon attack on server equipment processing and the new weapon's cooldown. */
public final class WeaponAttackReadiness {
    private static final int MINIMUM_TICKS = 2;
    private static final int TIMEOUT_TICKS = 60;

    public enum Result { WAIT, ATTACK, CANCEL }

    private final int entityId;
    private final Map<Identifier, AttributeModifier> expectedSpeedModifiers;
    private final Set<Identifier> removedSpeedModifiers;
    private volatile Acknowledgement acknowledgement;
    private Acknowledgement charging;
    private int rechargeTicks;
    private int ticks;
    private boolean finished;

    public WeaponAttackReadiness(ItemStack previous, ItemStack weapon, int entityId) {
        this.entityId = entityId;
        expectedSpeedModifiers = speedModifiers(weapon);
        var removed = new HashSet<>(speedModifiers(previous).keySet());
        removed.removeAll(expectedSpeedModifiers.keySet());
        removedSpeedModifiers = Set.copyOf(removed);
        // Even identical weapon speeds need a packet received after this request was created.
        if (expectedSpeedModifiers.isEmpty() && removedSpeedModifiers.isEmpty()) {
            acknowledgement = new Acknowledgement(0);
        }
    }

    /** May be called by the network thread. Only a fresh self attack-speed snapshot can acknowledge a swap. */
    public void observe(ClientboundUpdateAttributesPacket packet) {
        if (packet.getEntityId() != entityId) return;
        for (var snapshot : packet.getValues()) {
            if (!snapshot.attribute().equals(Attributes.ATTACK_SPEED)) continue;

            var relevant = new HashMap<Identifier, AttributeModifier>();
            boolean oldModifierPresent = false;
            for (var modifier : snapshot.modifiers()) {
                if (removedSpeedModifiers.contains(modifier.id())) oldModifierPresent = true;
                if (expectedSpeedModifiers.containsKey(modifier.id())) relevant.put(modifier.id(), modifier);
            }
            // Compare amounts and operations as well as IDs; different weapons reuse the base IDs.
            if (oldModifierPresent || !expectedSpeedModifiers.equals(relevant)) {
                acknowledgement = null;
            } else if (acknowledgement == null) {
                var speed = new AttributeInstance(Attributes.ATTACK_SPEED, ignored -> {});
                speed.setBaseValue(snapshot.base());
                snapshot.modifiers().forEach(speed::addOrUpdateTransientModifier);
                double value = speed.getValue();
                int delay = value > 0 ? (int) Math.ceil(20 / value) : Integer.MAX_VALUE;
                acknowledgement = new Acknowledgement(delay);
            }
        }
    }

    /** Called once per client tick. An attack or cancellation permanently consumes this request. */
    public Result tick(boolean valid, float cooldown) {
        if (finished) return Result.CANCEL;
        if (!valid || ++ticks >= TIMEOUT_TICKS) {
            finished = true;
            return Result.CANCEL;
        }
        var acknowledged = acknowledgement;
        if (acknowledged != charging) {
            charging = acknowledged;
            rechargeTicks = 0;
        } else if (acknowledged != null && rechargeTicks < acknowledged.delay()) {
            rechargeTicks++;
        }
        if (ticks < MINIMUM_TICKS || acknowledged == null
            || rechargeTicks < acknowledged.delay() || !(cooldown >= 1.0f)) return Result.WAIT;

        finished = true;
        return Result.ATTACK;
    }

    private static Map<Identifier, AttributeModifier> speedModifiers(ItemStack stack) {
        var modifiers = new HashMap<Identifier, AttributeModifier>();
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_SPEED)) modifiers.put(modifier.id(), modifier);
        });
        return Map.copyOf(modifiers);
    }

    private record Acknowledgement(int delay) {}
}
