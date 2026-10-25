package dev.tntcoating.core.dashboard;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashboardJsonTest {
    @Test void roundsToThreeDecimals() {
        assertEquals(1.235, DashboardJson.rounded(1.23456));
        assertEquals(-2.5, DashboardJson.rounded(-2.5));
    }

    @Test void nonFiniteValuesBecomeNull() {
        assertNull(DashboardJson.rounded(Double.NaN));
        assertNull(DashboardJson.rounded(Double.POSITIVE_INFINITY));
        assertNull(DashboardJson.rounded(Double.NEGATIVE_INFINITY));
    }

    @Test void serialisesNullsExplicitly() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a", null);
        value.put("b", 1);

        assertEquals("{\"a\":null,\"b\":1}", DashboardJson.toJson(value));
    }

    @Test void convertsTicksToSeconds() {
        assertEquals(1.0, DashboardJson.secondsFromTicks(20));
        assertEquals(0.5, DashboardJson.secondsFromTicks(10));
    }
}
