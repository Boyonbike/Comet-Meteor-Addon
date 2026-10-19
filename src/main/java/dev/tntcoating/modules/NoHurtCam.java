package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.BoolSetting.Builder;
import meteordevelopment.meteorclient.systems.modules.Module;

public class NoHurtCam extends Module {
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final Setting<Boolean> disableHurtCam = this.sgGeneral
      .add(new Builder().name("disable-hurt-cam").description("Disables the camera shake/tilt when taking damage.").defaultValue(true).build());

   public NoHurtCam() {
      super(TntCoatingAddon.CATEGORY, "no-hurt-cam", "Removes the hurt camera tilt and shake effect when taking damage.");
   }

   public boolean shouldDisableHurtCam() {
      return this.isActive() && this.disableHurtCam.get();
   }
}
