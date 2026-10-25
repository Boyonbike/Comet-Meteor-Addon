package dev.tntcoating.core.dashboard;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashboardUrlsTest {
    @Test void wildcardBindsAlwaysIncludeALoopbackFallback() {
        List<String> urls = DashboardUrls.resolve("0.0.0.0", 8734);

        assertFalse(urls.isEmpty());
        assertEquals("http://127.0.0.1:8734", urls.get(urls.size() - 1));
        assertTrue(urls.stream().allMatch(url -> url.startsWith("http://")));
    }

    @Test void explicitLoopbackNeedsNoFallback() {
        assertEquals(List.of("http://127.0.0.1:8080"), DashboardUrls.resolve("127.0.0.1", 8080));
    }

    @Test void explicitLanAddressIsFirstAndLoopbackIsAdded() {
        List<String> urls = DashboardUrls.resolve("192.168.1.50", 8734);

        assertEquals("http://192.168.1.50:8734", urls.get(0));
        assertTrue(urls.contains("http://127.0.0.1:8734"));
    }

    @Test void ipv6HostsAreBracketed() {
        assertEquals(List.of("http://[::1]:8734"), DashboardUrls.resolve("::1", 8734));
    }

    @Test void localhostIsNotDuplicated() {
        assertEquals(List.of("http://localhost:8734"), DashboardUrls.resolve("localhost", 8734));
    }

    @Test void invalidPortsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DashboardUrls.resolve("0.0.0.0", 0));
        assertThrows(IllegalArgumentException.class, () -> DashboardUrls.resolve("0.0.0.0", 70_000));
    }
}
