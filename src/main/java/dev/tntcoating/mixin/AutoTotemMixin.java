package dev.tntcoating.mixin;

import dev.tntcoating.core.AutoTotemOptimization;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.combat.AutoTotem;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Optimize Meteor's existing module, retaining its settings, lock, tick priority and pop-delay handler. */
@Mixin(value = AutoTotem.class, remap = false)
public abstract class AutoTotemMixin {
    @Shadow @Final private Setting<AutoTotem.Mode> mode;
    @Shadow @Final private Setting<Integer> delay;
    @Shadow @Final private Setting<Integer> health;
    @Shadow @Final private Setting<Boolean> elytra;
    @Shadow @Final private Setting<Boolean> fall;
    @Shadow @Final private Setting<Boolean> explosion;
    @Shadow public boolean locked;
    @Shadow private int totems;
    @Shadow private int ticks;

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void comet$efficientTotem(TickEvent.Pre event, CallbackInfo ci) {
        ci.cancel();
        var result = InvUtils.find(Items.TOTEM_OF_UNDYING);
        totems = result.count();
        if (totems <= 0) locked = false;
        else if (ticks >= delay.get()) {
            boolean flying = elytra.get() && mc.player.isFallFlying()
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
            locked = AutoTotemOptimization.shouldHold(mode.get() == AutoTotem.Mode.Strict, flying,
                mc.player.getHealth() + mc.player.getAbsorptionAmount(), health.get(),
                () -> PlayerUtils.possibleHealthReductions(explosion.get(), fall.get()));

            if (locked && !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
                var menu = mc.player.containerMenu;
                int slot = AutoTotemOptimization.offhandSwapSlot(menu, mc.player.getInventory(),
                    result.slot(), mc.player.getOffhandItem());
                if (slot >= 0 && menu.getSlot(slot).mayPickup(mc.player) && !mc.player.isCreative()) {
                    // Button 40 means offhand, independent of the current menu's slot layout.
                    // SWAP preserves the cursor and returns the displaced item in one normal click packet.
                    mc.gameMode.handleContainerInput(menu.containerId, slot, 40, ContainerInput.SWAP, mc.player);
                } else {
                    // Retain Meteor's handling for creative or custom slots that cannot use a normal swap.
                    InvUtils.move().from(result.slot()).toOffhand();
                }
            }
            ticks = 0;
            return;
        }
        ticks++;
    }
}
