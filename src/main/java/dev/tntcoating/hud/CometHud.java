package dev.tntcoating.hud;

import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;

/** Comet's movable HUD elements, added from Meteor's HUD editor. */
public final class CometHud {
    public static final HudGroup GROUP = new HudGroup("Comet");

    private CometHud() {}

    public static void register() {
        Hud hud = Hud.get();
        hud.register(EngineHud.ADAPTIVE_ELYTRA_FLY);
        hud.register(PlayerSpeedHud.INFO);
    }
}
