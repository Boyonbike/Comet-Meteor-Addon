/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.tester.BuildInfo;
import dev.tntcoating.core.tester.CombatExportBundle;
import dev.tntcoating.core.tester.CombatExportRedactor;
import dev.tntcoating.modules.CrystalCombat;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Tester evidence export. {@code .crystalcombat export} renders the exact archive plan and writes nothing;
 * {@code .crystalcombat export confirm} re-renders that plan and then writes the zip under
 * {@code <game dir>/comet/exports/}.
 *
 * <p>Privacy is default-deny: other players' names and every UUID are replaced by a per-export salted hash,
 * and chat/server addresses are removed even when the opt-in identities setting is on. The salt is never
 * written into the archive.</p>
 */
public final class CombatExportCommand extends Command {
    private static final DateTimeFormatter FILE_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
    private final SecureRandom random = new SecureRandom();

    public CombatExportCommand() {
        super("crystalcombat", "Exports sanitized CrystalCombat tester evidence.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(context -> {
            info("Usage: .crystalcombat export (plan only), .crystalcombat export confirm (write zip), "
                + "or .crystalcombat cleanup confirm after manually removing a piston trap.");
            return SINGLE_SUCCESS;
        });
        builder.then(literal("export")
            .executes(context -> export(false))
            .then(literal("confirm").executes(context -> export(true))));
        builder.then(literal("cleanup")
            .executes(context -> {
                info("Manually remove the trap's crystal, piston, power and supports first; then run "
                    + ".crystalcombat cleanup confirm. Hazards and sent risk are retained.");
                return SINGLE_SUCCESS;
            })
            .then(literal("confirm").executes(context -> cleanup())));
    }

    /** Explicit tester reconciliation: releases the trap slot, never clears hazards or sent risk. */
    private int cleanup() {
        CrystalCombat module = Modules.get().get(CrystalCombat.class);
        if (module == null) {
            error("CrystalCombat is not registered.");
            return 0;
        }
        if (!module.confirmManualCleanup()) {
            error("No active piston trap to reconcile.");
            return 0;
        }
        info("Piston trap slot released; hazards and sent risk were kept. No packets were sent.");
        return SINGLE_SUCCESS;
    }

    private int export(boolean write) {
        CrystalCombat module = Modules.get().get(CrystalCombat.class);
        if (module == null) {
            error("CrystalCombat is not registered.");
            return 0;
        }
        CombatExportBundle.Request request = request(module);
        List<CombatExportBundle.Entry> entries = CombatExportBundle.create(request);
        for (String line : CombatExportBundle.preview(request)) info("%s", line);
        if (write) {
            return write(entries);
        }
        return SINGLE_SUCCESS;
    }

    private int write(List<CombatExportBundle.Entry> entries) {
        Path directory = FabricLoader.getInstance().getGameDir().resolve("comet").resolve("exports");
        Path target = directory.resolve("crystalcombat-" + FILE_TIMESTAMP.format(Instant.now()) + ".zip");
        try {
            CombatExportBundle.write(target, entries);
            long bytes = 0L;
            for (CombatExportBundle.Entry entry : entries) bytes += entry.content().length;
            info("Wrote %s (%d files, %d bytes).", target, entries.size(), bytes);
            return SINGLE_SUCCESS;
        } catch (IOException failure) {
            error("Could not write %s: %s", target, failure.getMessage());
            return 0;
        }
    }

    private CombatExportBundle.Request request(CrystalCombat module) {
        BuildInfo build = TntCoatingAddon.buildInfo();
        CombatExportBundle.BuildStamp stamp = new CombatExportBundle.BuildStamp(
            build.version(), build.commit(), build.testerLabel(), minecraftVersion(), meteorVersion());
        return new CombatExportBundle.Request(stamp, settings(module), module.debugLines(),
            module.debugDroppedLines(), notices(), redactor(module), System.currentTimeMillis());
    }

    private CombatExportRedactor redactor(CrystalCombat module) {
        return new CombatExportRedactor(random.nextLong(), module.includeIdentitiesInExport(),
            otherPlayerNames());
    }

    private static List<CombatExportBundle.SettingValue> settings(CrystalCombat module) {
        List<CombatExportBundle.SettingValue> values = new ArrayList<>();
        for (SettingGroup group : module.settings) {
            for (Setting<?> setting : group) {
                values.add(new CombatExportBundle.SettingValue(group.name, setting.name,
                    String.valueOf(setting.get())));
            }
        }
        return values;
    }

    /** Current level players excluding the local player; names only, UUIDs are hashed by JSON field. */
    private static Collection<String> otherPlayerNames() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return List.of();
        List<String> names = new ArrayList<>();
        for (var player : minecraft.level.players()) {
            if (player == minecraft.player) continue;
            if (player.getUUID().equals(minecraft.player.getUUID())) continue;
            String name = player.getScoreboardName();
            if (name != null && !name.isBlank()) names.add(name);
        }
        return names;
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    private static String meteorVersion() {
        try {
            return MeteorClient.MOD_META.getVersion().getFriendlyString();
        } catch (RuntimeException unavailable) {
            return "unknown";
        }
    }

    private static String notices() {
        try (InputStream in = CombatExportCommand.class.getResourceAsStream("/THIRD_PARTY_NOTICES.md")) {
            if (in == null) return "THIRD_PARTY_NOTICES.md was not packaged in this build.\n";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return "THIRD_PARTY_NOTICES.md could not be read: " + failure.getMessage() + "\n";
        }
    }
}
