package dev.tntcoating.core;

import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoWeapon;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Keeps the first inventory-weapon hit behind the server's equipment update and swap cooldown. */
public final class InventoryWeaponAttack {
    public static final InventoryWeaponAttack INSTANCE = new InventoryWeaponAttack();

    private volatile Pending pending;
    private boolean replaying;

    private InventoryWeaponAttack() {}

    public boolean isPending() {
        return pending != null;
    }

    public boolean isReplaying() {
        return replaying;
    }

    public void defer(AttackEntityEvent event, ItemStack previous, ItemStack weapon) {
        boolean aimedAttack = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == event.entity;
        pending = new Pending(mc.player, mc.level, mc.screen, event.entity, aimedAttack,
            mc.player.getInventory().getSelectedSlot(),
            weapon.copy(), new WeaponAttackReadiness(previous, weapon, mc.player.getId()));
        event.cancel();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPacket(PacketEvent.Receive event) {
        Pending attack = pending;
        // Receive runs on the network thread. Only inspect immutable packet data here.
        if (attack != null && !event.isCancelled()
            && event.packet instanceof ClientboundUpdateAttributesPacket attributes) {
            attack.readiness.observe(attributes);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Post event) {
        Pending attack = pending;
        if (attack == null) return;
        if (mc.player != attack.player || mc.level != attack.level) {
            pending = null;
            InventorySlotLoan.INSTANCE.discard(InventorySlotLoan.Owner.AutoWeapon);
            return;
        }

        boolean valid = mc.gameMode != null
            && mc.screen == attack.screen
            && Modules.get().isActive(AutoWeapon.class) && mc.player.isAlive() && !mc.player.isSpectator()
            && attack.target.level() == mc.level && attack.target.isAlive() && !attack.target.isRemoved()
            && mc.player.getInventory().getSelectedSlot() == attack.slot
            && ItemStack.matches(attack.weapon, mc.player.getMainHandItem())
            && mc.player.isWithinAttackRange(attack.weapon, attack.target.getBoundingBox(), 0)
            && (!attack.aimedAttack
                || mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == attack.target)
            && !mc.player.isUsingItem();
        WeaponAttackReadiness.Result result = attack.readiness.tick(valid,
            valid ? mc.player.getAttackStrengthScale(0) : 0);
        if (result == WeaponAttackReadiness.Result.WAIT) return;

        pending = null;
        if (result == WeaponAttackReadiness.Result.CANCEL) {
            InventorySlotLoan.INSTANCE.restore(InventorySlotLoan.Owner.AutoWeapon);
            return;
        }

        // Skip Auto Weapon's scoring on the replay: prepare() would otherwise put the weapon away.
        replaying = true;
        try {
            mc.gameMode.attack(mc.player, attack.target);
            mc.player.swing(InteractionHand.MAIN_HAND);
        } finally {
            replaying = false;
            InventorySlotLoan.INSTANCE.restoreAfterAttack();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        pending = null;
        replaying = false;
    }

    private record Pending(LocalPlayer player, ClientLevel level, Screen screen, Entity target, boolean aimedAttack, int slot,
                           ItemStack weapon, WeaponAttackReadiness readiness) {}
}
