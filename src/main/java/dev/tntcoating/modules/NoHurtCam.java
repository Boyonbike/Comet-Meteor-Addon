package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import meteordevelopment.meteorclient.systems.modules.Module;

public final class NoHurtCam extends Module {
    public NoHurtCam() {
        super(TntCoatingAddon.CATEGORY, "no-hurt-cam", "Removes the hurt camera tilt and shake effect when taking damage.");
    }

    public boolean shouldDisableHurtCam() {
        return isActive();
    }
}
