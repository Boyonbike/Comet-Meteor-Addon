package dev.tntcoating.mixin;

import dev.tntcoating.core.InventorySlotLoan;
import dev.tntcoating.core.InventoryWeaponAttack;
import dev.tntcoating.modules.AllayShulkerKiller;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.combat.AutoWeapon;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(value = AutoWeapon.class, remap = false)
public abstract class AutoWeaponMixin {
    @Shadow @Final private SettingGroup sgGeneral;

    @Shadow private int getBestWeapon(LivingEntity target) { throw new AssertionError(); }

    @Unique private Setting<Boolean> comet$useInventory;
    @Unique private Setting<Double> comet$minimumGain;
    @Unique private boolean comet$hotbarOnly;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void comet$addInventorySetting(CallbackInfo ci) {
        comet$useInventory = sgGeneral.add(new BoolSetting.Builder()
            .name("use-inventory")
            .description("Temporarily pull the best weapon from the main inventory and return it after attacking.")
            .defaultValue(true)
            .build());
        comet$minimumGain = sgGeneral.add(new DoubleSetting.Builder()
            .name("inventory-min-gain")
            .description("Only pull a main-inventory weapon that deals at least this much more damage than your best hotbar weapon. Its first hit waits for the server and the weapon's cooldown.")
            .defaultValue(1).min(0).sliderRange(0, 5)
            .visible(comet$useInventory::get)
            .build());
    }

    @ModifyConstant(method = "getBestWeapon", constant = @Constant(intValue = 9))
    private int comet$searchMainInventory(int hotbarEnd) {
        return comet$useInventory.get() && !comet$hotbarOnly ? 36 : hotbarEnd;
    }

    @Inject(method = "onAttack", at = @At("HEAD"), cancellable = true)
    private void comet$restoreBeforeScoring(AttackEntityEvent event, CallbackInfo ci) {
        if (InventoryWeaponAttack.INSTANCE.isReplaying()) {
            ci.cancel();
            return;
        }
        if (InventoryWeaponAttack.INSTANCE.isPending()
            || !InventorySlotLoan.INSTANCE.prepare()) {
            event.cancel();
            ci.cancel();
        }
    }

    @Redirect(
        method = "onAttack",
        at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/utils/player/InvUtils;swap(IZ)Z")
    )
    private boolean comet$switchWeapon(int slot, boolean swapBack, AttackEntityEvent event) {
        if (slot < 9) return InvUtils.swap(slot, swapBack);
        // A marginal upgrade is not worth delaying the hit: use Meteor's own hotbar choice instead.
        // So is any upgrade for a hit that must land in the air, before the player lands and a sword would sweep.
        boolean instant = AllayShulkerKiller.isSweepFreeHit();
        if (event.entity instanceof LivingEntity target && (instant || comet$minimumGain.get() > 0)) {
            int hotbar;
            comet$hotbarOnly = true;
            try {
                hotbar = getBestWeapon(target);
            } finally {
                comet$hotbarOnly = false;
            }
            var inventory = mc.player.getInventory();
            double gain = DamageUtils.getAttackDamage(mc.player, target, inventory.getItem(slot))
                - DamageUtils.getAttackDamage(mc.player, target, inventory.getItem(hotbar));
            if (instant || gain < comet$minimumGain.get()) return InvUtils.swap(hotbar, swapBack);
        }
        var previous = mc.player.getMainHandItem().copy();
        var weapon = mc.player.getInventory().getItem(slot).copy();
        if (!comet$useInventory.get()
            || !InventorySlotLoan.INSTANCE.borrow(InventorySlotLoan.Owner.AutoWeapon, slot,
                () -> InventoryWeaponAttack.INSTANCE.defer(event, previous, weapon))) {
            event.cancel();
            return false;
        }
        return true;
    }
}
