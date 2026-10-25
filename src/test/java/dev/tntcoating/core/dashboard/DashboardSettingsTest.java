package dev.tntcoating.core.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashboardSettingsTest {
    @Test void masksWebhookUrlsTokensPasswordsAndSecrets() {
        assertTrue(DashboardSettings.isSensitive("webhook-url"));
        assertTrue(DashboardSettings.isSensitive("access-token"));
        assertTrue(DashboardSettings.isSensitive("MyPassword"));
        assertTrue(DashboardSettings.isSensitive("discord-secret"));
        assertFalse(DashboardSettings.isSensitive("mention"));
        assertFalse(DashboardSettings.isSensitive(null));
        assertFalse(DashboardSettings.isSensitive(""));
    }

    @Test void hiddenMarkerIsStable() {
        assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022", DashboardSettings.HIDDEN);
    }

    @Test void signatureIsOrderStableAndContentSensitive() {
        Map<String, List<DashboardSnapshot.SettingValue>> first = new LinkedHashMap<>();
        first.put("Route", List.of(new DashboardSnapshot.SettingValue("Route", "destination", "Destination",
            "0, 120, 1000", "BlockPosSetting", true)));
        int signature = DashboardSettings.signature(first);

        Map<String, List<DashboardSnapshot.SettingValue>> same = new LinkedHashMap<>();
        same.put("Route", List.of(new DashboardSnapshot.SettingValue("Route", "destination", "Destination",
            "0, 120, 1000", "BlockPosSetting", true)));
        assertEquals(signature, DashboardSettings.signature(same));

        Map<String, List<DashboardSnapshot.SettingValue>> changed = new LinkedHashMap<>();
        changed.put("Route", List.of(new DashboardSnapshot.SettingValue("Route", "destination", "Destination",
            "1, 120, 1000", "BlockPosSetting", true)));
        assertNotEquals(signature, DashboardSettings.signature(changed));
    }

    @Test void emptySettingsHaveAStableSignature() {
        Map<String, List<DashboardSnapshot.SettingValue>> empty = new LinkedHashMap<>();
        assertEquals(DashboardSettings.signature(empty), DashboardSettings.signature(new LinkedHashMap<>()));
    }
}
