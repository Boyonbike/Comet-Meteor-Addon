package dev.tntcoating.mixin;

import dev.tntcoating.gui.CometModuleSettings;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ModuleScreen.class, remap = false)
public abstract class CometModuleSettingsMixin extends WindowScreen {
    @Shadow @Final private Module module;

    protected CometModuleSettingsMixin(GuiTheme theme, String title) {
        super(theme, title);
    }

    @Inject(method = "initWidgets", at = @At(value = "INVOKE",
        target = "Lmeteordevelopment/meteorclient/systems/modules/Module;getWidget(Lmeteordevelopment/meteorclient/gui/GuiTheme;)Lmeteordevelopment/meteorclient/gui/widgets/WWidget;"))
    private void comet$addUpgradeLinks(CallbackInfo ci) {
        var section = CometModuleSettings.createLinks(theme, module);
        if (section != null) add(section).expandX();
    }
}
