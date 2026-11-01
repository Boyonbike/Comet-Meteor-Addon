/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Anarchy-only statistical quiet window. <strong>Superseded on 2026-11-01 by
 * {@link RiskModel#BARRIER}.</strong> The risk-model setting replaced the labelled statistical rule as the
 * way to stop zero-damage explosions from becoming permanent debt; elapsed time is not terminal evidence and
 * remains unreachable in production. This class and its budget hook are retained only because the
 * retirement tests still pin the old, always-disabled path, and {@link CombatProfiles#profileBudget} still
 * rejects an enabled window. No module setting, profile or production constructor can enable it.
 *
 * <p>The window was always <strong>labelled statistical</strong>: a later health snapshot can show that it
 * retired too early, so it records every retirement and every detected miss through the injected log
 * consumer. It is disabled by default and must never be used with Conservative settings, where elapsed time
 * may not retire anything.
 */
public final class AnarchyQuietWindow {
    public static final long DEFAULT_QUIET_TICKS = 40L;

    private final boolean enabled;
    private final long quietTicks;
    private final Consumer<String> log;
    private int retirements;
    private int misses;

    public AnarchyQuietWindow(boolean enabled, long quietTicks, Consumer<String> log) {
        if (quietTicks < 0) throw new IllegalArgumentException("quietTicks must be non-negative");
        if (enabled) {
            Objects.requireNonNull(log, "log");
            if (quietTicks == 0) throw new IllegalArgumentException("enabled window must be positive");
        }
        this.enabled = enabled;
        this.quietTicks = quietTicks;
        this.log = log;
    }

    public static AnarchyQuietWindow disabled() {
        return new AnarchyQuietWindow(false, 0L, null);
    }

    public static AnarchyQuietWindow statistical(long quietTicks, Consumer<String> log) {
        return new AnarchyQuietWindow(true, quietTicks, log);
    }

    public boolean enabled() {
        return enabled;
    }

    public long quietTicks() {
        return quietTicks;
    }

    public int retirements() {
        return retirements;
    }

    public int misses() {
        return misses;
    }

    void recordRetirement(String message) {
        if (!enabled) return;
        retirements++;
        log.accept(message);
    }

    void recordMiss(String message) {
        if (!enabled) return;
        misses++;
        log.accept(message);
    }
}
