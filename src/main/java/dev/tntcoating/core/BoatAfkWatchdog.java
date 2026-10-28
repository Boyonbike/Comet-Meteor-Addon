/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

/** Bounds an uninterrupted wait; a brief rejoin cannot renew the recovery budget. */
public final class BoatAfkWatchdog {
    private boolean waiting, recoveringWait, settling, advanced;
    private long since, readySince;
    public void reset() { waiting = recoveringWait = settling = advanced = false; }
    public void confirmTravel() { if (waiting && settling) advanced = true; }
    public String evaluate(long now, boolean tripWorld, boolean recovering, boolean ready, int worldSeconds, int recoverySeconds) {
        if (tripWorld && !recovering) {
            if (!waiting) return null;
            if (!ready) { settling = advanced = false; }
            else {
                if (!settling) { settling = true; readySince = now; }
                if (advanced && now - readySince >= 5_000_000_000L) { reset(); return null; }
            }
        } else { settling = advanced = false; }
        if (!waiting) { waiting = true; since = now; }
        recoveringWait |= recovering;
        int seconds = recoveringWait ? recoverySeconds : worldSeconds;
        if (seconds <= 0 || now - since < seconds * 1_000_000_000L) return null;
        return recoveringWait ? "Boat recovery exceeded its time limit." : "The original trip world did not return in time.";
    }
}
