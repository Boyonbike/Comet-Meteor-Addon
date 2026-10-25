/*
 * Comet trip health notifications.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.tntcoating.core;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transfers authoritative health samples from the connection to trip safety accounting. */
public final class HighwayHealthUpdates {
    private record Sample(double health, boolean baseline) {}

    private final HighwayTravelSafety safety;
    private final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean nextSampleIsBaseline = new AtomicBoolean();
    private double latestHealth;

    public HighwayHealthUpdates(HighwayTravelSafety safety) {
        this.safety = safety;
    }

    /** Starts a fresh trip's damage accounting on the game thread. */
    public void reset(double health) {
        clear();
        latestHealth = health;
        safety.reset(health);
    }

    /** The next health packet belongs to a new connection; earlier queued damage still belongs to this trip. */
    public void connectionStarted() {
        nextSampleIsBaseline.set(true);
    }

    /** Called on the connection thread, preserving the baseline marker with its authoritative sample. */
    public void accept(double health) {
        samples.add(new Sample(health, nextSampleIsBaseline.getAndSet(false)));
    }

    /** Drains every sample on the game thread, retaining damage even when healing arrived before the next tick. */
    public boolean exceeded(double maxDamage) {
        boolean exceeded = false;
        Sample sample;
        while ((sample = samples.poll()) != null) {
            latestHealth = sample.health();
            if (sample.baseline()) {
                safety.rebaseHealth(latestHealth);
            } else if (safety.damageLimitReached(latestHealth, maxDamage)) {
                exceeded = true;
            }
        }
        return safety.damageLimitReached(latestHealth, maxDamage) || exceeded;
    }

    /** Drops pending connection updates when the trip ends. */
    public void clear() {
        samples.clear();
        nextSampleIsBaseline.set(false);
    }
}
