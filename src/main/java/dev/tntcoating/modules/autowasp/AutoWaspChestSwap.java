/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

/** Auto Wasp's chest-slot choices, independent of Minecraft's click and flight handling. */
public final class AutoWaspChestSwap {
    /** An inventory item that fits the chest slot; durability is what remains, or MAX_VALUE when unbreakable. */
    public record Candidate(int slot, boolean glider, int durability, double armor, double toughness) {}

    private AutoWaspChestSwap() {}

    /** Durable gliders, or chestplates with the most armor, toughness, then durability; -1 when none fits. */
    public static int best(Iterable<Candidate> inventory, boolean glider) {
        Candidate best = null;
        for (Candidate candidate : inventory) {
            // A glider on its last point of durability cannot glide.
            if (candidate.glider() != glider || candidate.durability() <= (glider ? 1 : 0)) continue;
            if (best == null || better(candidate, best, glider)) best = candidate;
        }
        return best == null ? -1 : best.slot();
    }

    /**
     * Whether to wear a chestplate beside the target. Entering takes the target within every range; once a chestplate
     * is on, the target must move {@code hysteresis} blocks beyond a range before the elytra goes back on, so standing at
     * the edge of a range cannot swap back and forth on every cooldown.
     */
    public static boolean wearChestplate(boolean wearingChestplate, double horizontal, double vertical, double ground,
                                         double horizontalRange, double verticalRange, double groundRange,
                                         double hysteresis) {
        double extra = wearingChestplate ? Math.max(0, hysteresis) : 0;
        return horizontal <= horizontalRange + extra && vertical <= verticalRange + extra
            && ground <= groundRange + extra;
    }

    private static boolean better(Candidate candidate, Candidate current, boolean glider) {
        if (!glider) {
            int armor = Double.compare(candidate.armor(), current.armor());
            if (armor != 0) return armor > 0;
            int toughness = Double.compare(candidate.toughness(), current.toughness());
            if (toughness != 0) return toughness > 0;
        }
        return candidate.durability() > current.durability();
    }
}
