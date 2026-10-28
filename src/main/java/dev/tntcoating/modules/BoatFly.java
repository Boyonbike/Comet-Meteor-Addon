/*
 * Comet manual boat flight. Flight engine adapted from Quiettee Utils (GPL-3.0).
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.tntcoating.modules;

import dev.tntcoating.modules.boatfly.BoatFlyEngine;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;

/** Hand-steered boat flight and phasing; no heading, helpers or logouts. Auto Boat Fly adds those on the same engine. */
public final class BoatFly extends BoatFlyEngine {
    public BoatFly() {
        super("boat-fly", "Fly and phase a boat by hand: WASD steers, jump rises, sprint descends.");
        runInMainMenu = true;
    }

    @Override public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        list.add(theme.label(isBoatDriver() ? "Driving · " + engineStatus() : isActive() ? "Board a boat's driver seat to fly." : "Off"));
        list.add(theme.label("WASD steers · jump rises · sprint descends · " + dismountKeyName() + " dismounts"))
            .widget().color = theme.textSecondaryColor();
        list.add(theme.label("For unattended travel along a fixed heading, use Auto Boat Fly."))
            .widget().color = theme.textSecondaryColor();
        list.add(speedRecoveryWidget(theme)).expandX();
        return list;
    }
}
