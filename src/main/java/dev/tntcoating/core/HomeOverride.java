/*
 * Part of Comet's auto-set-home module.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

import java.util.List;
import java.util.Locale;

/** Matching rules for the server dialog that asks whether to replace an existing home. */
public final class HomeOverride {
    private HomeOverride() {}

    /** Whether text contains the configured phrase, ignoring case. A blank phrase never matches. */
    public static boolean matches(String text, String phrase) {
        if (text == null || phrase == null) return false;
        String needle = phrase.strip();
        return !needle.isEmpty() && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** The confirmation dialog's title or one of its widget labels mentions the configured phrase. */
    public static boolean isHomeDialog(String title, List<String> widgetTexts, String phrase) {
        if (matches(title, phrase)) return true;
        for (String text : widgetTexts) {
            if (matches(text, phrase)) return true;
        }
        return false;
    }

    /** The button label contains the configured phrase. */
    public static boolean isOverrideButton(String label, String phrase) {
        return matches(label, phrase);
    }

    /** The command that moves the named home to the player, without a leading slash. */
    public static String command(String home) {
        String name = home.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("home must not be blank");
        return "sethome " + name;
    }
}
