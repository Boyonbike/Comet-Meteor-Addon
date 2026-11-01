/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.tntcoating.modules.CombatRecorderModule;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * Chat control for the combat trace recorder. {@code label} is the free-text marker the label hotkey pre-fills.
 */
public final class CombatRecorderCommand extends Command {
    public CombatRecorderCommand() {
        super("combat-recorder", "Controls the CrystalCombat observation recorder.", "comet-trace");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(context -> status());
        builder.then(literal("status").executes(context -> status()));
        builder.then(literal("on").executes(context -> {
            module().enable();
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("off").executes(context -> {
            module().disable();
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("label").then(argument("text", StringArgumentType.greedyString())
            .executes(context -> {
                String text = StringArgumentType.getString(context, "text");
                CombatRecorderModule module = module();
                if (!module.isActive() || !module.label(text)) {
                    ChatUtils.warning("The combat recorder is off.");
                    return 0;
                }
                return SINGLE_SUCCESS;
            })));
    }

    private static CombatRecorderModule module() {
        return Modules.get().get(CombatRecorderModule.class);
    }

    private static int status() {
        ChatUtils.info("Combat recorder: %s", module().isActive() ? "on" : "off");
        return SINGLE_SUCCESS;
    }
}
