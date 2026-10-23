package dev.tntcoating.mixin;

import dev.tntcoating.proxy.ProxiedBrowser;
import dev.tntcoating.proxy.VerifyLinks;
import meteordevelopment.meteorclient.systems.proxies.Proxies;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Servers that refuse a proxied connection answer with a verification link on the disconnect
 * screen. Pull the link out of that message and offer both ways to open it: on the normal
 * connection, or through the proxy the game is using.
 */
@Mixin(value = DisconnectedScreen.class, priority = 800)
public abstract class DisconnectedScreenMixin extends Screen {
    @Shadow @Final private LinearLayout layout;
    @Shadow @Final private DisconnectionDetails details;

    protected DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V", shift = At.Shift.BEFORE))
    private void comet$addVerifyButtons(CallbackInfo ci) {
        String url = VerifyLinks.find(details.reason().getString());
        if (url == null) return;

        layout.addChild(new Button.Builder(Component.literal("Open Verify Link"), _ -> {
            if (!ProxiedBrowser.openDirect(url)) ProxiedBrowser.openInSystemBrowser(url);
        }).width(180).build());

        if (Proxies.get().getEnabled() != null) {
            layout.addChild(new Button.Builder(Component.literal("Open Via Proxy"), _ -> ProxiedBrowser.openThroughProxy(url, true))
                .width(180)
                .build());
        }
    }
}
