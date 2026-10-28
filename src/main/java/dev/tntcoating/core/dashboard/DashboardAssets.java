/*
 * Comet flight dashboard classpath assets.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads the dashboard page from the addon jar; there is no filesystem path involved. */
public final class DashboardAssets {
    private static final String ROOT = "/assets/tnt-coating/dashboard/";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** Immutable classpath bundle selected by a dashboard, never by an HTTP request. */
    public record Bundle(String html, String css, String js) {
        public Bundle {
            java.util.Objects.requireNonNull(html);
            java.util.Objects.requireNonNull(css);
            java.util.Objects.requireNonNull(js);
        }
    }

    private DashboardAssets() {}

    /** The one page served for Highway Fly and Auto Boat Fly. */
    public static Bundle flight() { return new Bundle(html(), css(), js()); }

    public static String html() {
        return load("index.html");
    }

    public static String css() {
        return load("app.css");
    }

    public static String js() {
        return load("app.js");
    }

    private static String load(String name) {
        return CACHE.computeIfAbsent(ROOT + name, key -> {
            try (InputStream stream = DashboardAssets.class.getResourceAsStream(key)) {
                if (stream == null) throw new IllegalStateException("Missing dashboard resource " + key);
                byte[] bytes = stream.readAllBytes();
                if (bytes.length == 0) throw new IllegalStateException("Empty dashboard resource " + key);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalStateException("Could not read dashboard resource " + key, error);
            }
        });
    }
}
