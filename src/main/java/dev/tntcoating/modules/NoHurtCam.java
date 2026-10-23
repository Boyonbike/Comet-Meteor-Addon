/*
 * Original Comet implementation by Boyonabike.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import meteordevelopment.meteorclient.systems.modules.Module;

public final class NoHurtCam extends Module {
    public NoHurtCam() {
        super(TntCoatingAddon.CATEGORY, "no-hurt-cam", "Cancels the camera tilt and shake vanilla applies when you take damage.");
    }

    public boolean shouldDisableHurtCam() {
        return isActive();
    }
}
