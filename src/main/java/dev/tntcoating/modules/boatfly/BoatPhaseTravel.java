/*
 * Adapted from Quiettee Utils BoatPhase by Quiettee and contributors.
 * Copyright (c) Quiettee Utils contributors; Comet adaptations (c) 2026 Comet contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.tntcoating.modules.boatfly;

import java.util.EnumSet;

public final class BoatPhaseTravel {
    public enum Mode { LIMITED, SUBSTEPS }
    private final EnumSet<Mode> rejected = EnumSet.noneOf(Mode.class);
    private Mode lastFastMode = Mode.LIMITED;
    private int lastFastTick = -100;

    public Mode select(boolean substeps) {
        return substeps && !rejected(Mode.SUBSTEPS) ? Mode.SUBSTEPS : Mode.LIMITED;
    }

    public void sent(Mode mode, int tick, double horizontal, double limit) {
        if (mode != Mode.LIMITED && Double.isFinite(horizontal) && horizontal > limit + 1e-6) {
            lastFastMode = mode;
            lastFastTick = tick;
        }
    }

    public Mode corrected(int tick) {
        int age = tick - lastFastTick;
        if (age < 0 || age > 20 || lastFastMode == Mode.LIMITED || rejected(lastFastMode)) return Mode.LIMITED;
        reject(lastFastMode);
        return lastFastMode;
    }

    public void reject(Mode mode) { if (mode != Mode.LIMITED) rejected.add(mode); }
    public void retry(Mode mode) { rejected.remove(mode); newRide(); }
    public boolean rejected(Mode mode) { return rejected.contains(mode); }
    public boolean flightRejected() { return rejected(Mode.SUBSTEPS); }

    public void retryFlight() {
        rejected.remove(Mode.SUBSTEPS);
        newRide();
    }
    public void newRide() { lastFastMode = Mode.LIMITED; lastFastTick = -100; }
    public void retry() { rejected.clear(); newRide(); }
}
