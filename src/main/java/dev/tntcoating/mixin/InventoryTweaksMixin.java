package dev.tntcoating.mixin;

import dev.tntcoating.core.InventoryMoveScheduler;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.InventorySorter;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Keep Meteor's controls while replacing sleeping executor tasks with a cancellable client-thread job. */
@Mixin(value = InventoryTweaks.class, remap = false)
public abstract class InventoryTweaksMixin {
    @Shadow @Final private Setting<Boolean> stealDrop;
    @Shadow @Final private Setting<Boolean> dropBackwards;
    @Shadow @Final private Setting<InventoryTweaks.ListMode> dumpFilter;
    @Shadow @Final private Setting<List<Item>> dumpItems;
    @Shadow @Final private Setting<InventoryTweaks.ListMode> stealFilter;
    @Shadow @Final private Setting<List<Item>> stealItems;
    @Shadow @Final private Setting<Integer> autoStealDelay;
    @Shadow @Final private Setting<Integer> autoStealInitDelay;
    @Shadow @Final private Setting<Integer> autoStealRandomDelay;
    @Shadow private InventorySorter sorter;

    @Unique private final InventoryMoveScheduler<ItemStack> comet$mover = new InventoryMoveScheduler<>();
    @Unique private volatile long comet$session;
    @Unique private long comet$clientTick;
    @Unique private boolean comet$stealing;

    @Inject(method = "steal", at = @At("HEAD"), cancellable = true)
    private void comet$steal(AbstractContainerMenu menu, CallbackInfo ci) {
        ci.cancel();
        comet$request(menu, true);
    }

    @Inject(method = "dump", at = @At("HEAD"), cancellable = true)
    private void comet$dump(AbstractContainerMenu menu, CallbackInfo ci) {
        ci.cancel();
        comet$request(menu, false);
    }

    @Inject(method = {"onActivate", "onDeactivate"}, at = @At("HEAD"))
    private void comet$resetTransfers(CallbackInfo ci) {
        comet$cancel();
    }

    @Inject(method = "onOpenScreen", at = @At("HEAD"))
    private void comet$screenChanged(OpenScreenEvent event, CallbackInfo ci) {
        comet$cancel();
    }

    @Inject(method = "sort", at = @At("RETURN"))
    private void comet$manualSort(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) comet$cancel();
    }

    @Inject(method = "onTickPre", at = @At("HEAD"))
    private void comet$tickTransfers(TickEvent.Pre event, CallbackInfo ci) {
        comet$clientTick++;
        comet$pump();
    }

    // Render2DEvent is suppressed while normal container screens are open; world rendering still runs.
    @Unique @EventHandler
    private void comet$renderTransfers(Render3DEvent event) {
        comet$pump();
    }

    @Unique @EventHandler
    private void comet$disconnectTransfers(GameLeftEvent event) {
        comet$cancel();
    }

    @Unique
    private void comet$cancel() {
        comet$session++;
        comet$mover.cancel();
    }

    @Unique
    private void comet$request(AbstractContainerMenu menu, boolean steal) {
        if (!mc.isSameThread()) {
            long session = comet$session;
            mc.execute(() -> {
                if (session == comet$session) comet$request(menu, steal);
            });
            return;
        }
        var context = comet$context();
        if (context == null || context.menu() != menu) return;
        int offset = SlotUtils.indexToId(SlotUtils.MAIN_START);
        int start = steal ? 0 : offset;
        int end = Math.min(menu.slots.size(), steal ? offset : offset + 36);
        if (comet$mover.start(context, start, end, steal, System.nanoTime())) comet$stealing = steal;
        comet$pump();
    }

    @Unique
    private InventoryMoveScheduler.Context comet$context() {
        if (!((InventoryTweaks) (Object) this).isActive() || !Utils.canUpdate() || mc.gameMode == null || sorter != null
            || !(mc.screen instanceof AbstractContainerScreen<?> screen)
            || screen.getMenu() != mc.player.containerMenu || !mc.player.containerMenu.getCarried().isEmpty()) return null;
        return new InventoryMoveScheduler.Context(mc.player, mc.level, screen, mc.player.containerMenu);
    }

    @Unique
    private int comet$normalDelay() {
        int random = autoStealRandomDelay.get();
        return autoStealDelay.get() + (random > 0 ? ThreadLocalRandom.current().nextInt(random) : 0);
    }

    @Unique
    private boolean comet$eligible(ItemStack stack, boolean steal) {
        if (stack.isEmpty()) return false;
        var mode = steal ? stealFilter.get() : dumpFilter.get();
        var items = (steal ? stealItems : dumpItems).get();
        return switch (mode) {
            case None -> true;
            case Whitelist -> items.contains(stack.getItem());
            case Blacklist -> !items.contains(stack.getItem());
        };
    }

    @Unique
    private boolean comet$canClick(InventoryMoveScheduler.Action<ItemStack> action) {
        var context = comet$context();
        if (!comet$mover.isCurrent(action, context)) return false;
        var menu = mc.player.containerMenu;
        if (action.slot() < 0 || action.slot() >= menu.slots.size()) return false;
        var slot = menu.getSlot(action.slot());
        return slot.mayPickup(mc.player) && comet$eligible(slot.getItem(), action.steal())
            && ItemStack.matches(action.snapshot(), slot.getItem());
    }

    @Unique
    private void comet$pump() {
        if (!comet$mover.hasWork()) return;
        var context = comet$context();
        if (context == null) {
            comet$mover.cancel();
            return;
        }
        var menu = mc.player.containerMenu;
        // Tick and render callbacks share the same budget; zero-delay transfers never form an unbounded burst.
        for (int count = 0; count < InventoryMoveScheduler.MAX_ACTIONS_PER_TICK; count++) {
            var action = comet$mover.poll(context, System.nanoTime(), comet$clientTick,
                autoStealInitDelay.get(), this::comet$normalDelay, slot -> {
                    var stack = menu.getSlot(slot).getItem();
                    return comet$eligible(stack, comet$stealing) ? stack.copy() : null;
                });
            if (action == null) break;
            if (!comet$canClick(action)) {
                comet$mover.complete(action, System.nanoTime());
                continue;
            }
            // A passenger sends no movement packets, so Meteor never runs rotation callbacks: drop without turning.
            if (action.steal() && stealDrop.get() && dropBackwards.get() && !mc.player.isPassenger()) {
                Rotations.rotate(mc.player.getYRot() - 180, mc.player.getXRot(), () -> {
                    if (stealDrop.get() && dropBackwards.get() && comet$canClick(action))
                        InvUtils.drop().slotId(action.slot());
                    comet$mover.complete(action, System.nanoTime());
                });
                break;
            }
            if (action.steal() && stealDrop.get()) InvUtils.drop().slotId(action.slot());
            else InvUtils.shiftClick().slotId(action.slot());
            comet$mover.complete(action, System.nanoTime());
        }
    }

}
