package dev.tntcoating.mixin;

import dev.tntcoating.core.InventorySlotLoan;
import dev.tntcoating.core.InventoryWeaponAttack;
import dev.tntcoating.core.ReplenishPolicy;
import dev.tntcoating.core.ReplenishPolicy.StackPreference;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.player.AutoReplenish;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Adds Comet's refill controls to the original Meteor instance and lifecycle. */
@Mixin(value = AutoReplenish.class, remap = false)
public abstract class AutoReplenishMixin {
    @Shadow @Final private SettingGroup sgGeneral;
    @Shadow @Final private Setting<Integer> minCount;
    @Shadow @Final private Setting<Boolean> unstackable;
    @Shadow @Final private Setting<Boolean> sameEnchants;
    @Shadow @Final private Setting<Boolean> searchHotbar;
    @Shadow @Final private Setting<List<Item>> excludedItems;
    @Shadow @Final private ItemStack[] items;

    @Unique private Setting<Boolean> comet$pauseOnUse;
    @Unique private Setting<Boolean> comet$smartRefill;
    @Unique private Setting<StackPreference> comet$preference;
    @Unique private Setting<Integer> comet$maxRefills;
    @Unique private Setting<Boolean> comet$respectNames;
    @Unique private Setting<Boolean> comet$allStackable;
    @Unique private Setting<Boolean> comet$blocks;
    @Unique private Setting<Boolean> comet$food;
    @Unique private Setting<Boolean> comet$tools;
    @Unique private Setting<Boolean> comet$weapons;
    @Unique private Setting<Boolean> comet$projectiles;
    @Unique private Setting<Boolean> comet$pearls;
    @Unique private Setting<Boolean> comet$potions;
    @Unique private Setting<Boolean> comet$totems;
    @Unique private Setting<Boolean> comet$gaps;
    @Unique private Setting<Boolean> comet$fireworks;
    @Unique private int comet$refills;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void comet$addSettings(CallbackInfo ci) {
        comet$pauseOnUse = comet$bool("pause-on-use", "Pause while eating, drinking, drawing a bow or using another item.", true);
        comet$smartRefill = comet$bool("smart-refill", "Refill remaining stacks only at one item; still restore emptied slots.", false);
        comet$preference = sgGeneral.add(new EnumSetting.Builder<StackPreference>()
            .name("stack-preference").description("Which eligible source stack to use first.")
            .defaultValue(StackPreference.SmallStacks).build());
        comet$maxRefills = sgGeneral.add(new IntSetting.Builder().name("max-refills-per-tick")
            .description("Maximum targeted transfers each refill tick.").defaultValue(1).range(1, 5).sliderRange(1, 5).build());
        comet$respectNames = comet$bool("respect-custom-names", "Require matching custom names when replacing an emptied slot. Partial stacks always require matching components.", true);
        comet$allStackable = comet$bool("refill-all-stackable", "Refill every stackable item, overriding category filters. Excluded items still apply.", true);
        comet$blocks = comet$bool("refill-blocks", "Top up building blocks.", true);
        comet$food = comet$bool("refill-food", "Top up food.", true);
        comet$tools = comet$bool("refill-tools", "Replace consumed or broken tools with the same item and material.", false);
        comet$weapons = comet$bool("refill-weapons", "Replace consumed or broken weapons with the same item.", false);
        comet$projectiles = comet$bool("refill-projectiles", "Top up arrows and firework rockets.", true);
        comet$pearls = comet$bool("refill-ender-pearls", "Top up ender pearls.", true);
        comet$potions = comet$bool("refill-potions", "Top up potions.", true);
        comet$totems = comet$bool("refill-totems", "Top up totems when Meteor's Auto Totem is not managing the slot.", true);
        comet$gaps = comet$bool("refill-golden-apples", "Top up golden and enchanted golden apples.", true);
        comet$fireworks = comet$bool("refill-fireworks", "Top up firework rockets.", true);
    }

    @Unique private Setting<Boolean> comet$bool(String name, String description, boolean value) {
        return sgGeneral.add(new BoolSetting.Builder().name(name).description(description).defaultValue(value).build());
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void comet$beginTick(TickEvent.Pre event, CallbackInfo ci) {
        comet$refills = 0;
        if (mc.player == null || mc.level == null) ci.cancel();
    }

    // Let Meteor observe open screens and rebase snapshots after manual inventory changes first.
    @Inject(method = "onTick", at = @At(value = "FIELD",
        target = "Lmeteordevelopment/meteorclient/systems/modules/player/AutoReplenish;prevHadOpenScreen:Z",
        opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER), cancellable = true)
    private void comet$pauseTransfers(TickEvent.Pre event, CallbackInfo ci) {
        if (mc.player == null || mc.level == null || mc.gameMode == null
            || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()
            || comet$pauseOnUse.get() && mc.player.isUsingItem()
            || InventorySlotLoan.INSTANCE.isBorrowing() || InventoryWeaponAttack.INSTANCE.isPending()) ci.cancel();
    }

    @Inject(method = "checkSlot", at = @At("HEAD"), cancellable = true)
    private void comet$refillSlot(int trackedSlot, ItemStack stack, CallbackInfo ci) {
        ci.cancel();
        // Keep a pending empty-slot snapshot when this tick's transfer budget is exhausted.
        if (comet$refills >= comet$maxRefills.get()) return;
        ItemStack previous = items[trackedSlot];
        items[trackedSlot] = stack.copy();
        ItemStack wanted = stack.isEmpty() ? previous : stack;
        if (wanted == null || wanted.isEmpty() || excludedItems.get().contains(wanted.getItem()) || !comet$allowed(wanted)) return;
        if (stack.isEmpty()) {
            if (wanted.getMaxStackSize() == 1 && !unstackable.get()) return;
        } else if (!ReplenishPolicy.needsRefill(stack.getCount(), stack.getMaxStackSize(), minCount.get(), comet$smartRefill.get())) return;

        int target = trackedSlot == 9 ? SlotUtils.OFFHAND : trackedSlot;
        int source = comet$source(wanted, target, !stack.isEmpty(), 9, 36);
        if (source < 0 && searchHotbar.get()) source = comet$source(wanted, target, !stack.isEmpty(), 0, 9);
        if (source < 0) {
            // Retry an emptied slot after the next delay, including after a budget-limited tick.
            if (stack.isEmpty()) items[trackedSlot] = wanted.copy();
            return;
        }
        InvUtils.move().from(source).to(target);
        comet$refills++;
        items[trackedSlot] = mc.player.getInventory().getItem(target).copy();
        // Search-hotbar is a last resort: consuming a donor must not refill it back from the destination.
        if (source < 9) items[source] = mc.player.getInventory().getItem(source).copy();
    }

    @Unique private int comet$source(ItemStack wanted, int target, boolean combine, int start, int end) {
        return ReplenishPolicy.chooseSource(start, end, comet$preference.get(), slot -> {
            if (slot == target) return false;
            // Keep Meteor's protection against taking a stack out of the selected hand.
            if (slot < 9 && slot == mc.player.getInventory().getSelectedSlot()) return false;
            ItemStack candidate = mc.player.getInventory().getItem(slot);
            if (candidate.isEmpty() || candidate.getItem() != wanted.getItem()) return false;
            if (combine && !ItemStack.isSameItemSameComponents(candidate, wanted)) return false;
            if (sameEnchants.get() && !candidate.getEnchantments().equals(wanted.getEnchantments())) return false;
            return !comet$respectNames.get() || java.util.Objects.equals(candidate.get(DataComponents.CUSTOM_NAME), wanted.get(DataComponents.CUSTOM_NAME));
        }, slot -> mc.player.getInventory().getItem(slot).getCount());
    }

    @Unique private boolean comet$allowed(ItemStack stack) {
        Item item = stack.getItem();
        return comet$allStackable.get() && stack.getMaxStackSize() > 1
            || comet$blocks.get() && item instanceof BlockItem
            || comet$food.get() && stack.has(DataComponents.FOOD)
            || comet$tools.get() && (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES))
            || comet$weapons.get() && (stack.is(ItemTags.SWORDS) || item instanceof BowItem || item instanceof CrossbowItem || item == Items.TRIDENT || item == Items.MACE)
            || comet$projectiles.get() && (item instanceof ArrowItem || item == Items.FIREWORK_ROCKET)
            || comet$pearls.get() && item == Items.ENDER_PEARL
            || comet$potions.get() && item instanceof PotionItem
            || comet$totems.get() && item == Items.TOTEM_OF_UNDYING
            || comet$gaps.get() && (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE)
            || comet$fireworks.get() && item == Items.FIREWORK_ROCKET;
    }
}
