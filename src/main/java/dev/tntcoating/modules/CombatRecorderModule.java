/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.combat.mc.CombatRecorderHost;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.client.gui.screens.ChatScreen;

import java.io.IOException;

/**
 * Phase 0 development tool: records every audited combat observation to
 * {@code <minecraft dir>/comet/combat-traces/<timestamp>.jsonl}.
 *
 * <p>Off by default. Read-only: it has no send path and never places, attacks, swaps or rotates.</p>
 */
public final class CombatRecorderModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> labelKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("label-key")
        .description("Opens chat with the trace label command filled in. Unbound by default.")
        .defaultValue(Keybind.none())
        .action(this::openLabelPrompt)
        .build());

    private final Setting<Integer> maxMegabytes = sgGeneral.add(new IntSetting.Builder()
        .name("max-file-megabytes")
        .description("The trace file stops accepting lines after this size.")
        .defaultValue(64)
        .min(1)
        .sliderRange(1, 512)
        .build());

    private CombatRecorderHost host;

    public CombatRecorderModule() {
        super(TntCoatingAddon.CATEGORY, "combat-recorder",
            "Records every CrystalCombat observation to JSON Lines for development. Read-only: it sends nothing.");
        // Start recording even from the main menu, so enabling it never leaves a silently idle module.
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        try {
            host = CombatRecorderHost.start(mc, mc.gameDirectory.toPath().resolve("comet").resolve("combat-traces"),
                maxMegabytes.get() * 1024L * 1024L);
            info("Recording to %s", host.path());
        } catch (IOException error) {
            host = null;
            error("Could not start the combat recorder: %s", error.getMessage());
            disable();
        }
    }

    @Override
    public void onDeactivate() {
        if (host != null) {
            host.stop();
            host = null;
        }
    }

    /** Writes a free-text marker line; false when the recorder is not running. */
    public boolean label(String text) {
        if (host == null) return false;
        host.label(text);
        return true;
    }

    @Override
    public String getInfoString() {
        return host == null ? null : "recording";
    }

    private void openLabelPrompt() {
        mc.setScreen(new ChatScreen(Config.get().prefix.get() + "combat-recorder label ", true));
    }
}
