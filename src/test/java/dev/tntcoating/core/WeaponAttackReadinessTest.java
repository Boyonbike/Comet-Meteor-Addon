package dev.tntcoating.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.tntcoating.core.WeaponAttackReadiness.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class WeaponAttackReadinessTest {
    private static final int PLAYER_ID = 17;

    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void oldEquipmentCannotReleaseAnAttackEvenWhenItsCooldownIsFull() {
        var previous = new ItemStack(Items.STONE_AXE);
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(previous, weapon, PLAYER_ID);
        request.observe(speedPacket(PLAYER_ID, previous));
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(WAIT, request.tick(true, 1));
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertRechargeThenAttack(request, 13);
    }

    @Test void inventorySwapMustWaitForBothFreshEquipmentAndFullNewWeaponCooldown() {
        var weapon = new ItemStack(Items.DIAMOND_AXE);
        var request = new WeaponAttackReadiness(new ItemStack(Items.STICK), weapon, PLAYER_ID);
        request.observe(speedPacket(PLAYER_ID, weapon));
        for (int tick = 0; tick < 22; tick++) assertEquals(WAIT, request.tick(true, 0));
        assertEquals(WAIT, request.tick(true, 0.99f));
        assertEquals(WAIT, request.tick(true, Float.NaN));
        assertEquals(ATTACK, request.tick(true, 1));
        assertEquals(CANCEL, request.tick(true, 1), "A finished request must not attack twice");
    }

    @Test void acknowledgementsCannotBypassTheTwoTickEquipmentDelay() {
        var weapon = stackWithSpeed(modifier("fast_weapon", 20));
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(ATTACK, request.tick(true, 1));
    }

    @Test void sameSpeedSwordSwapStillRequiresAFreshServerAcknowledgement() {
        var previous = new ItemStack(Items.WOODEN_SWORD);
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var oldAttributes = attributes(previous);
        var newAttributes = attributes(weapon);
        assertEquals(oldAttributes.getValue(Attributes.ATTACK_SPEED), newAttributes.getValue(Attributes.ATTACK_SPEED));
        assertEquals(4, oldAttributes.getValue(Attributes.ATTACK_DAMAGE));
        assertEquals(8, newAttributes.getValue(Attributes.ATTACK_DAMAGE));

        var request = new WeaponAttackReadiness(previous, weapon, PLAYER_ID);
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(WAIT, request.tick(true, 1), "Local matching speed is not a server acknowledgement");
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertRechargeThenAttack(request, 13);
    }

    @Test void wrongEntityWrongAttributeAndWrongModifierAmountDoNotAcknowledgeTheSwap() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        request.observe(speedPacket(PLAYER_ID + 1, weapon));
        request.observe(new ClientboundUpdateAttributesPacket(PLAYER_ID,
            List.of(attributes(weapon).getInstance(Attributes.ATTACK_DAMAGE))));
        request.observe(speedPacket(PLAYER_ID, new ItemStack(Items.STONE_AXE)));
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(WAIT, request.tick(true, 1));
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertRechargeThenAttack(request, 13);
    }

    @Test void unrelatedSpeedBuffsAreIgnoredButOldOnlyWeaponModifiersMustDisappear() {
        var oldOnly = modifier("old_weapon_speed", -1);
        var previous = stackWithSpeed(oldOnly);
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(previous, weapon, PLAYER_ID);
        var speed = attributes(weapon).getInstance(Attributes.ATTACK_SPEED);
        speed.addTransientModifier(oldOnly);
        speed.addTransientModifier(modifier("unrelated_buff", 0.2));
        request.observe(packet(speed));
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(WAIT, request.tick(true, 1));
        speed.removeModifier(oldOnly);
        request.observe(packet(speed));
        assertRechargeThenAttack(request, 12);
    }

    @Test void modifierOperationMustMatchAsWellAsIdAndAmount() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        var speed = attributes(weapon).getInstance(Attributes.ATTACK_SPEED);
        var expected = speed.getModifiers().iterator().next();
        speed.removeModifier(expected);
        speed.addTransientModifier(new AttributeModifier(expected.id(), expected.amount(),
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        request.observe(packet(speed));
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(WAIT, request.tick(true, 1));
    }

    @Test void newerWrongEquipmentRevokesAnEarlierAcknowledgement() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        request.observe(speedPacket(PLAYER_ID, weapon));
        request.observe(speedPacket(PLAYER_ID, new ItemStack(Items.STONE_AXE)));
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(WAIT, request.tick(true, 1));
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertRechargeThenAttack(request, 13);
    }

    @Test void weaponsWithoutAnySpeedModifiersNeedOnlyTheDelayAndCooldown() {
        var request = new WeaponAttackReadiness(new ItemStack(Items.STICK), new ItemStack(Items.BLAZE_ROD), PLAYER_ID);
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(ATTACK, request.tick(true, 1));
    }

    @Test void removingTheLastWeaponSpeedModifierStillRequiresAConfirmation() {
        var request = new WeaponAttackReadiness(new ItemStack(Items.STONE_AXE), new ItemStack(Items.STICK), PLAYER_ID);
        assertEquals(WAIT, request.tick(true, 1));
        assertEquals(WAIT, request.tick(true, 1));
        request.observe(speedPacket(PLAYER_ID, new ItemStack(Items.STICK)));
        assertRechargeThenAttack(request, 5);
    }

    @Test void invalidationIsTerminalEvenIfEquipmentAndCooldownBecomeReadyLater() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        assertEquals(CANCEL, request.tick(false, 0));
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertEquals(CANCEL, request.tick(true, 1));
    }

    @Test void requestsExpireAtSixtyTicksAndCannotBeRevivedByALateAcknowledgement() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        for (int tick = 1; tick < 60; tick++) assertEquals(WAIT, request.tick(true, 1), "Tick " + tick);
        assertEquals(CANCEL, request.tick(true, 1));
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertEquals(CANCEL, request.tick(true, 1));
    }

    @Test void expectedModifiersAreCapturedBeforeLaterStackChanges() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var initialPacket = speedPacket(PLAYER_ID, weapon);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        weapon.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        request.observe(initialPacket);
        assertRechargeThenAttack(request, 13);
    }

    @Test void highLatencyCannotUseTheLocallyChargedAttackBeforeTheServerFinishesCharging() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        for (int tick = 0; tick < 20; tick++) assertEquals(WAIT, request.tick(true, 1));
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertRechargeThenAttack(request, 13);
    }

    @Test void repeatedMatchingPacketsDoNotRestartTheServerRechargeDelay() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        for (int tick = 0; tick < 13; tick++) {
            request.observe(speedPacket(PLAYER_ID, weapon));
            assertEquals(WAIT, request.tick(true, 1));
        }
        request.observe(speedPacket(PLAYER_ID, weapon));
        assertEquals(ATTACK, request.tick(true, 1));
    }

    @Test void serverRechargeUsesTheSnapshotBaseSpeedAndUnrelatedBuffs() {
        var weapon = new ItemStack(Items.NETHERITE_SWORD);
        var request = new WeaponAttackReadiness(ItemStack.EMPTY, weapon, PLAYER_ID);
        var speed = attributes(weapon).getInstance(Attributes.ATTACK_SPEED);
        speed.setBaseValue(5);
        speed.addTransientModifier(new AttributeModifier(Identifier.fromNamespaceAndPath("comet_test", "speed_buff"),
            0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        assertEquals(6, (int) Math.ceil(20 / speed.getValue()));
        request.observe(packet(speed));
        assertRechargeThenAttack(request, 6);
    }

    private static void assertRechargeThenAttack(WeaponAttackReadiness request, int rechargeTicks) {
        // The first tick observes the acknowledgement; only subsequent ticks count as elapsed time.
        for (int tick = 0; tick < rechargeTicks; tick++) {
            assertEquals(WAIT, request.tick(true, 1), "Recharge tick " + tick);
        }
        assertEquals(ATTACK, request.tick(true, 1));
    }

    private static AttributeMap attributes(ItemStack stack) {
        var attributes = new AttributeMap(Player.createAttributes().build());
        stack.forEachModifier(EquipmentSlot.MAINHAND,
            (attribute, modifier) -> attributes.getInstance(attribute).addTransientModifier(modifier));
        return attributes;
    }

    private static ClientboundUpdateAttributesPacket speedPacket(int entityId, ItemStack stack) {
        return new ClientboundUpdateAttributesPacket(entityId, List.of(attributes(stack).getInstance(Attributes.ATTACK_SPEED)));
    }

    private static ClientboundUpdateAttributesPacket packet(AttributeInstance speed) {
        return new ClientboundUpdateAttributesPacket(PLAYER_ID, List.of(speed));
    }

    private static AttributeModifier modifier(String path, double amount) {
        return new AttributeModifier(Identifier.fromNamespaceAndPath("comet_test", path), amount,
            AttributeModifier.Operation.ADD_VALUE);
    }

    private static ItemStack stackWithSpeed(AttributeModifier modifier) {
        var stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_SPEED, modifier, EquipmentSlotGroup.MAINHAND).build());
        return stack;
    }
}
