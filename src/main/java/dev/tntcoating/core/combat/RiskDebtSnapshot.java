/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the pinned 26.2 artifact; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable, read-only view of the risk debt and suspension causes the client tick last sampled.
 *
 * <p>This record exists so the render thread never touches the ledger, scheduler or risk budget: the
 * controller publishes one volatile snapshot per client tick and render code only formats it. A zero
 * bound with a positive count is still displayed, because an unretirable component still counts as debt.
 */
public record RiskDebtSnapshot(int count, double bound, List<SuspensionCause> causes) {

    public RiskDebtSnapshot {
        if (count < 0) throw new IllegalArgumentException("count must be >= 0, was " + count);
        if (!Double.isFinite(bound) || bound < 0.0) {
            throw new IllegalArgumentException("bound must be finite and >= 0, was " + bound);
        }
        Objects.requireNonNull(causes, "causes");
        List<SuspensionCause> copy = new ArrayList<>(causes.size());
        for (SuspensionCause cause : causes) copy.add(Objects.requireNonNull(cause, "cause"));
        causes = List.copyOf(copy);
    }

    /** Nothing to show: no debt and no suspension. */
    public static RiskDebtSnapshot empty() {
        return new RiskDebtSnapshot(0, 0.0, List.of());
    }

    /** Whether the renderer should draw anything at all. */
    public boolean visible() {
        return count > 0 || bound > 0.0 || !causes.isEmpty();
    }

    /**
     * The two short lines the renderer draws, in stable order: debt first (when present), then the
     * suspension causes. The renderer never formats causes itself.
     */
    public List<String> displayLines() {
        List<String> lines = new ArrayList<>(2);
        if (count > 0 || bound > 0.0) {
            lines.add(String.format(Locale.ROOT, "risk debt: %d outstanding (bound %.2f)", count, bound));
        }
        if (!causes.isEmpty()) {
            lines.add("suspended: " + causes.stream()
                .map(cause -> cause.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .collect(Collectors.joining(", ")));
        }
        return List.copyOf(lines);
    }
}
