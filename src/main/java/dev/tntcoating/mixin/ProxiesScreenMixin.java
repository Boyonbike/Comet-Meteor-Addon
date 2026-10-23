package dev.tntcoating.mixin;

import dev.tntcoating.proxy.FreeProxyImporter;
import dev.tntcoating.proxy.ProxyLinkSettings;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.screens.ProxiesScreen;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the free-proxy fetch button and the "proxy links" switch to Meteor's proxy screen. */
@Mixin(value = ProxiesScreen.class, remap = false)
public abstract class ProxiesScreenMixin {
    @Inject(method = "initWidgets", at = @At("TAIL"))
    private void comet$addButtons(CallbackInfo ci) {
        WidgetScreen screen = (WidgetScreen) (Object) this;

        WButton fetch = screen
            .add(GuiThemes.get().button("Fetch Free Proxies"))
            .expandX()
            .widget();

        fetch.action = FreeProxyImporter::fetchAndImport;
        fetch.tooltip = "Fill the list from the free proxy mirror, then check them.";

        WButton links = screen
            .add(GuiThemes.get().button("Proxy links: " + (ProxyLinkSettings.isEnabled() ? "ON" : "OFF")))
            .expandX()
            .widget();

        links.action = () -> {
            ProxyLinkSettings.set(!ProxyLinkSettings.isEnabled());
            links.set("Proxy links: " + (ProxyLinkSettings.isEnabled() ? "ON" : "OFF"));
        };
        links.tooltip = "Open clicked web links in a browser bound to your active proxy.";
    }
}
