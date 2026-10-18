package dev.tntcoating.mixin;

import dev.tntcoating.core.InventorySlotLoan;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.combat.AutoWeapon;
import meteordevelopment.meteorclient.utils.player.InvUtils;
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

@Mixin(value = AutoWeapon.class, remap = false)
public abstract class AutoWeaponMixin {
    @Shadow @Final private SettingGroup sgGeneral;

    @Unique private Setting<Boolean> comet$useInventory;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void comet$addInventorySetting(CallbackInfo ci) {
        comet$useInventory = sgGeneral.add(new BoolSetting.Builder()
            .name("use-inventory")
            .description("Temporarily pulls the best weapon from the main inventory and returns it after attacking.")
            .defaultValue(true)
            .build());
    }

    @ModifyConstant(method = "getBestWeapon", constant = @Constant(intValue = 9))
    private int comet$searchMainInventory(int hotbarEnd) {
        return comet$useInventory.get() ? 36 : hotbarEnd;
    }

    @Inject(method = "onAttack", at = @At("HEAD"), cancellable = true)
    private void comet$restoreBeforeScoring(AttackEntityEvent event, CallbackInfo ci) {
        if (!InventorySlotLoan.INSTANCE.prepare(InventorySlotLoan.Owner.AutoWeapon)) ci.cancel();
    }

    @Redirect(
        method = "onAttack",
        at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/utils/player/InvUtils;swap(IZ)Z")
    )
    private boolean comet$switchWeapon(int slot, boolean swapBack) {
        if (slot < 9) return InvUtils.swap(slot, swapBack);
        if (!comet$useInventory.get()
            || !InventorySlotLoan.INSTANCE.borrow(InventorySlotLoan.Owner.AutoWeapon, slot)) return false;

        InventorySlotLoan.INSTANCE.restoreAfterAttack();
        return true;
    }
}
