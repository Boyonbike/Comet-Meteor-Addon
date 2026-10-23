package dev.tntcoating.mixin;

import dev.tntcoating.proxy.ProxiedBrowser;
import dev.tntcoating.proxy.ProxyLinkSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens clicked web links in a browser instance bound to the proxy Meteor is currently using,
 * instead of the default browser that would go out on the real connection.
 */
@Mixin(value = Screen.class, priority = 800)
public abstract class ScreenProxyUrlMixin {
    @Inject(method = "defaultHandleClickEvent", at = @At("HEAD"), cancellable = true)
    private static void comet$openUrlThroughProxy(ClickEvent event, Minecraft minecraft, Screen screen, CallbackInfo ci) {
        if (!ProxyLinkSettings.isEnabled()) return;
        if (event instanceof ClickEvent.OpenUrl openUrl && ProxiedBrowser.openThroughProxy(openUrl.uri().toString())) {
            ci.cancel();
        }
    }
}
