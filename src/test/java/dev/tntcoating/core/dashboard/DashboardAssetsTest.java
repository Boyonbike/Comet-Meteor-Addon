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
}
