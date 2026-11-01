/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * The server-tick interval in which a packet arrival may have been observed. Everything time-dependent in the
 * combat model uses server ticks and intervals, never client milliseconds.
 */
public record Arrival(long earliest, long latest) {
    public Arrival {
        if (earliest < 0L) throw new IllegalArgumentException("earliest must be non-negative: " + earliest);
        if (latest < earliest) {
            throw new IllegalArgumentException("latest must be >= earliest: " + earliest + ".." + latest);
        }
    }

    public static Arrival at(long tick) {
        return new Arrival(tick, tick);
    }

    public boolean contains(long tick) {
        return tick >= earliest && tick <= latest;
    }

    /** True when every tick in this interval is strictly before {@code tick}. */
    public boolean entirelyBefore(long tick) {
        return latest < tick;
    }

    /** True when every tick in this interval is at or after {@code tick}. */
    public boolean startsAtOrAfter(long tick) {
        return earliest >= tick;
    }

    /** The smallest interval covering both, used when several observations constrain one arrival. */
    public Arrival union(Arrival other) {
        return new Arrival(Math.min(earliest, other.earliest), Math.max(latest, other.latest));
    }

    public Arrival shifted(long ticks) {
        return new Arrival(earliest + ticks, latest + ticks);
    }
}
