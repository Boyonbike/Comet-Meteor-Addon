/*
 * Comet highway dashboard settings digest helpers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core.dashboard;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Rules for safely exposing addon settings on the local dashboard. */
public final class DashboardSettings {
    /** What sensitive values are rendered as; the real value never leaves the game thread unmasked. */
    public static final String HIDDEN = "\u2022\u2022\u2022\u2022\u2022\u2022";

    private DashboardSettings() {}

    /** True for credentials and endpoints that must never be shown on the page. */
    public static boolean isSensitive(String name) {
        if (name == null || name.isBlank()) return false;
        String normalised = name.toLowerCase(Locale.ROOT);
        return normalised.equals("webhook-url") || normalised.contains("password") || normalised.contains("token")
            || normalised.contains("secret");
    }

    /**
     * Order-stable digest of the rendered settings. The page only rebuilds its settings markup when this changes, so
     * hidden values may change without forcing a re-render.
     */
    public static int signature(Map<String, List<DashboardSnapshot.SettingValue>> settings) {
        int result = 1;
        for (Map.Entry<String, List<DashboardSnapshot.SettingValue>> entry : settings.entrySet()) {
            result = 31 * result + Objects.hashCode(entry.getKey());
            for (DashboardSnapshot.SettingValue value : entry.getValue()) {
                result = 31 * result + Objects.hash(value.name(), value.title(), value.value(), value.type(),
                    value.visible());
            }
        }
        return result;
    }
}
