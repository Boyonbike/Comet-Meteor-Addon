/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Selects which retirement evidence the risk budget accepts. Every model keeps the same reservation,
 * per-crystal self-damage limit, remaining-health margin, friend limits, anti-suicide floor and packet
 * rules; the model only changes how a sent risk can become terminal.
 *
 * <ul>
 *   <li>{@link #STRICT}: rule 3 stays unsatisfied ({@link ProcessingBarrier}); a risk retires only on a
 *       causally later coherent effective-health snapshot, self respawn or connection close.</li>
 *   <li>{@link #BARRIER}: strict plus one side-effect-free sequenced interaction after an observed
 *       explosion. Its block-change acknowledgement, one server tick later, retires only reservations
 *       whose explosion predates the probe and that saw no health change.</li>
 *   <li>{@link #METEOR_STYLE}: a sent reservation retires as soon as the bound crystal's explosion or
 *       removal is observed; no health update or acknowledgement is waited for. Sent-but-not-exploded
 *       crystals still count against the next placement, so several hits landing before the first health
 *       update cannot silently sum past the margin.</li>
 * </ul>
 *
 * <p>This enum is pure combat state: no Minecraft or Meteor types may appear here. The
 * display strings are part of the setting contract and are asserted verbatim by tests.
 */
public enum RiskModel {
    STRICT("Strict", "strict",
        "Strict — Safest. Every crystal that might still hurt you is counted until proven harmless; can slow the aura in long fights."),
    BARRIER("Barrier", "barrier",
        "Barrier — Strict, plus a check packet that clears harmless explosions so the aura doesn't slow down. Confirmed on a local vanilla server only."),
    METEOR_STYLE("Meteor-style", "meteor-style",
        "Meteor-style — Checks each crystal against current health like Meteor and Future. Fastest; less protection when several hits land before your health updates.");

    private final String label;
    private final String key;
    private final String description;

    RiskModel(String label, String key, String description) {
        this.label = label;
        this.key = key;
        this.description = description;
    }

    /** User-facing option label shown by the Meteor enum widget. */
    public String label() {
        return label;
    }

    /** Stable lower-case identifier used in info strings and debug lines. */
    public String key() {
        return key;
    }

    /** Exact setting option description. */
    public String description() {
        return description;
    }

    /** Meteor's enum widget, saved values and suggestions all use {@code toString()}. */
    @Override
    public String toString() {
        return label;
    }

    /** Strict is the default in every profile, including a freshly created setting. */
    public static RiskModel defaultModel() {
        return STRICT;
    }
}
