/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * An arrival interval in server ticks, not wall-clock milliseconds. A server stall or low TPS separates
 * the two clocks, so every risk arrival is expressed in this unit.
 */
public record ServerTickInterval(long earliest, long latest) {
    public ServerTickInterval {
        if (earliest > latest) throw new IllegalArgumentException("earliest must not exceed latest");
    }

    public boolean contains(long tick) {
        return tick >= earliest && tick <= latest;
    }
}
