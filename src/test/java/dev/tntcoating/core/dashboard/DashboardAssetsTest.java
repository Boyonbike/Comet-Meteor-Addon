package dev.tntcoating.core.dashboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashboardAssetsTest {
    @Test void bundledPageReferencesItsLocalStylesheetAndScript() {
        String html = DashboardAssets.html();

        assertFalse(html.isBlank());
        assertTrue(html.toLowerCase().contains("<!doctype html"));
        assertTrue(html.contains("app.css"));
        assertTrue(html.contains("app.js"));
        assertTrue(html.contains("Highway Fly"));
    }

    @Test void stylesheetAndScriptAreBundled() {
        assertFalse(DashboardAssets.css().isBlank());
        assertFalse(DashboardAssets.js().isBlank());
        assertTrue(DashboardAssets.js().contains("EventSource"));
    }

    @Test void pageLoadsEverythingFromTheSameOrigin() {
        String html = DashboardAssets.html();

        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
    }

    @Test void onePageServesHighwayAndAutoBoatFly() {
        String html = DashboardAssets.html();
        String js = DashboardAssets.js();
        assertTrue(html.contains("data-only=\"highway\""));
        assertTrue(html.contains("data-only=\"boat\""));
        assertTrue(html.contains("id=\"boat-egaps\""));
        assertTrue(html.contains("Heading &amp; travel"));
        assertTrue(js.contains("Auto Boat Fly"));
        assertTrue(js.contains("snapshot.mode === 'boat'"));
        assertTrue(DashboardAssets.css().contains("body[data-mode=\"boat\"] [data-only=\"highway\"]"));
        assertFalse(js.contains("innerHTML"), "Telemetry must be rendered as text");
        assertNull(DashboardAssets.class.getResource("/assets/tnt-coating/boat-dashboard/index.html"),
            "The separate boat page was retired");
    }
}
