/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure incident rules for the tester debug log. These helpers only classify and format; the Minecraft-facing
 * half ({@code CombatDebugHost}) supplies observations, budget state and marker output.
 *
 * <p>Incidents are diagnostics, not gameplay: nothing here sends, reserves, schedules or retires risk.</p>
 */
public final class CombatIncidents {
    /** The prefix every tester-visible incident marker carries. */
    public static final String PREFIX = "incident: ";

    /** Continuous self risk debt longer than this is reported once per episode. */
    public static final long DEBT_STUCK_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    /** Damage must exceed the predicted bound by this much; float health packets carry sub-unit noise. */
    public static final double EPSILON = 1.0e-9;

    private CombatIncidents() {
    }

    /**
     * True when effective health fell further than the predicted upper bound for the preceding coherent
     * snapshot. {@code predicted} is {@link CombatRiskBudget#current(EntityKey)} captured before the health
     * observation was applied.
     */
    public static boolean selfDamageExceedsBound(double previousEffectiveHealth, double currentEffectiveHealth,
                                                Bound predicted) {
        if (!Double.isFinite(previousEffectiveHealth) || !Double.isFinite(currentEffectiveHealth)
            || predicted == null) {
            return false;
        }
        double damage = previousEffectiveHealth - currentEffectiveHealth;
        return damage > EPSILON && damage > predicted.value() + EPSILON;
    }

    public static String selfDamageLabel(double damage, Bound predicted) {
        return PREFIX + "self-damage damage=" + number(damage)
            + " predicted_upper_bound=" + number(predicted.value())
            + " components=" + predicted.components();
    }

    public static String selfDeathLabel(boolean moduleEnabled) {
        return PREFIX + "self-death module=" + (moduleEnabled ? "enabled" : "disabled");
    }

    public static String suspensionLabel(Set<SuspensionCause> before, Set<SuspensionCause> after) {
        Set<SuspensionCause> previous = before == null ? Set.of() : before;
        Set<SuspensionCause> current = after == null ? Set.of() : after;
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (SuspensionCause cause : current) {
            if (!previous.contains(cause)) added.add(cause.name());
        }
        for (SuspensionCause cause : previous) {
            if (!current.contains(cause)) removed.add(cause.name());
        }
        added.sort(Comparator.naturalOrder());
        removed.sort(Comparator.naturalOrder());
        List<String> causes = new ArrayList<>();
        for (SuspensionCause cause : current) causes.add(cause.name());
        causes.sort(Comparator.naturalOrder());
        return PREFIX + "suspension-changed suspended=" + !current.isEmpty()
            + " added=" + added + " removed=" + removed + " causes=" + causes;
    }

    public static String debtStuckLabel(Debt debt, long elapsedNanos) {
        return PREFIX + "risk-debt-stuck seconds=" + number(elapsedNanos / 1.0e9)
            + " count=" + debt.count() + " bound=" + number(debt.bound());
    }

    public static String dispatchInvariantLabel(String status, String reason) {
        return PREFIX + "dispatch-invariant status=" + status + " reason=" + reason;
    }

    /** Manual marker from the "something went wrong here" hotkey. Text is flattened and bounded. */
    public static String manualMarker(String text) {
        String flattened = text == null ? "" : text.replaceAll("[\\r\\n\\t]", " ").trim();
        if (flattened.length() > 160) flattened = flattened.substring(0, 160);
        return flattened.isEmpty() ? PREFIX + "manual" : PREFIX + "manual " + flattened;
    }

    /** Ensures a marker line carries exactly one incident prefix. */
    public static String ensurePrefix(String marker) {
        if (marker == null || marker.isBlank()) return PREFIX + "manual";
        return marker.startsWith(PREFIX) ? marker : PREFIX + marker.trim();
    }

    /** Wall-clock-free debt episode timer. One report per continuous episode. */
    public static final class DebtWatch {
        private boolean active;
        private boolean reported;
        private long sinceNanos;

        /** Returns true exactly once when the episode has lasted at least {@link #DEBT_STUCK_NANOS}. */
        public boolean update(boolean inDebt, long nowNanos) {
            if (!inDebt) {
                active = false;
                reported = false;
                return false;
            }
            if (!active) {
                active = true;
                reported = false;
                sinceNanos = nowNanos;
                return false;
            }
            if (!reported && nowNanos - sinceNanos >= DEBT_STUCK_NANOS) {
                reported = true;
                return true;
            }
            return false;
        }

        public boolean inEpisode() {
            return active;
        }

        public boolean reported() {
            return reported;
        }

        public long elapsedNanos(long nowNanos) {
            return active ? Math.max(0L, nowNanos - sinceNanos) : 0L;
        }

        public void reset() {
            active = false;
            reported = false;
            sinceNanos = 0L;
        }
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
