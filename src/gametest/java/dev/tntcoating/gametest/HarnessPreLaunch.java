/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2 and
 * Fabric API client game-test behaviour; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.lwjgl.glfw.GLFW;

/**
 * Selects GLFW's Wayland backend when the harness runs inside the desktop's Wayland
 * session. Minecraft otherwise chooses X11, which is absent in this Flatpak session.
 */
public final class HarnessPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (System.getenv("WAYLAND_DISPLAY") != null) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_WAYLAND);
        }
    }
}
