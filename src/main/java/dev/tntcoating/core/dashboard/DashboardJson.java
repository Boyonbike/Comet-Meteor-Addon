/*
 * Comet highway dashboard JSON encoding.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Shared Gson configuration and number rounding for the dashboard snapshot. */
public final class DashboardJson {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final double MAX_ROUNDING_SCALE = 9.0e15;

    private DashboardJson() {}

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    /** Rounds to three decimals; non-finite values become null so the page never receives invalid JSON numbers. */
    public static Double rounded(double value) {
        return rounded(value, 3);
    }

    /** Rounds to {@code places} decimals, returning null for NaN or infinity. */
    public static Double rounded(double value, int places) {
        if (!Double.isFinite(value)) return null;
        if (places <= 0) return (double) Math.round(value);
        double factor = Math.pow(10, places);
        double scaled = value * factor;
        if (!Double.isFinite(scaled) || Math.abs(scaled) > MAX_ROUNDING_SCALE) return value;
        return Math.round(scaled) / factor;
    }

    /** Converts a tick count into seconds rounded to two decimals, for dashboard display. */
    public static Double secondsFromTicks(int ticks) {
        return rounded(ticks / 20.0, 2);
    }
}
