package dev.tntcoating.mixin;

import dev.tntcoating.modules.AdaptiveSpeed;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.speed.Speed;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Upgrades the registered Meteor module in place, retaining its settings, binds and Strafe mode. */
@Mixin(value = Speed.class, remap = false)
public abstract class SpeedMixin extends Module {
    @Unique private AdaptiveSpeed comet$adaptiveSpeed;

    protected SpeedMixin(Category category, String name, String description) { super(category, name, description); }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void comet$settings(CallbackInfo ci) { comet$adaptiveSpeed = new AdaptiveSpeed((Speed) (Object) this); }

    @Inject(method = {"onActivate", "onDeactivate"}, at = @At("HEAD"))
    private void comet$reset(CallbackInfo ci) { comet$adaptiveSpeed.reset(); }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void comet$move(PlayerMoveEvent event, CallbackInfo ci) {
        if (comet$adaptiveSpeed.move(event)) ci.cancel();
    }

    @Inject(method = "onPreTick", at = @At("HEAD"), cancellable = true)
    private void comet$tick(TickEvent.Pre event, CallbackInfo ci) {
        if (comet$adaptiveSpeed.tick()) ci.cancel();
    }

    @Inject(method = "onPacketReceive", at = @At("HEAD"))
    private void comet$correction(PacketEvent.Receive event, CallbackInfo ci) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) comet$adaptiveSpeed.corrected();
    }

    @EventHandler private void comet$disconnect(GameLeftEvent event) { comet$adaptiveSpeed.reset(); }

    @Inject(method = "getInfoString", at = @At("HEAD"), cancellable = true)
    private void comet$info(CallbackInfoReturnable<String> cir) {
        if (comet$adaptiveSpeed.selected()) cir.setReturnValue(comet$adaptiveSpeed.info());
    }

    @Override public WWidget getWidget(GuiTheme theme) { return comet$adaptiveSpeed.widget(theme); }
}
