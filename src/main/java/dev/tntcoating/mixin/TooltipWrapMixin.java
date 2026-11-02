package dev.tntcoating.mixin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WTooltip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Meteor builds every hover tooltip (setting and module descriptions) from a single-line label, so long
 * descriptions run off the side of the screen. Route the tooltip through Meteor's own word-wrapping label
 * instead; short descriptions still render on one line. If a Meteor build changes this method, the mixin
 * plugin skips this mixin with a warning and tooltips simply stay unwrapped.
 */
@Mixin(value = WTooltip.class, remap = false)
public abstract class TooltipWrapMixin {
    /** Unscaled maximum tooltip width; Meteor applies the GUI scale inside the wrapping label. */
    private static final double COMET$MAX_WIDTH = 320.0;

    @Redirect(method = "init()V", at = @At(value = "INVOKE",
        target = "Lmeteordevelopment/meteorclient/gui/GuiTheme;label(Ljava/lang/String;)"
            + "Lmeteordevelopment/meteorclient/gui/widgets/WLabel;"))
    private WLabel comet$wrapTooltip(GuiTheme theme, String text) {
        return theme.label(text, COMET$MAX_WIDTH);
    }
}
