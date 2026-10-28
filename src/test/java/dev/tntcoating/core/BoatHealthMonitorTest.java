package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatHealthMonitorTest {
    @Test void authoritativeHealthWinsOverStalePlayerHealthAcrossWorldChanges() {
        var monitor = new BoatHealthMonitor(); var connection = new Object();
        monitor.begin(connection);
        assertFalse(monitor.lowHealth(20, 8));
        monitor.health(connection, 4);
        assertTrue(monitor.lowHealth(20, 8));
        monitor.health(connection, 18);
        assertFalse(monitor.lowHealth(4, 8));
        monitor.begin(connection);
        assertFalse(monitor.lowHealth(4, 8), "Same-connection world changes preserve authoritative health");
    }
    @Test void oldConnectionPacketsCannotPoisonNewHealthOrDeath() {
        var monitor = new BoatHealthMonitor(); var old = new Object(); var current = new Object();
        monitor.begin(old); monitor.health(old, 1); monitor.death(old);
        monitor.begin(current); monitor.health(current, 20);
        monitor.health(old, 1); monitor.death(old);
        assertFalse(monitor.lowHealth(20, 8)); assertFalse(monitor.dead());
        monitor.death(current); assertTrue(monitor.dead());
        monitor.clear(); assertFalse(monitor.dead());
    }
    @Test void invalidPacketsAreIgnoredAndDisabledHealthCheckStaysDisabled() {
        var monitor = new BoatHealthMonitor(); var connection = new Object(); monitor.begin(connection);
        monitor.health(connection, Double.NaN);
        assertFalse(monitor.lowHealth(20, 8));
        monitor.health(connection, 1); assertFalse(monitor.lowHealth(20, 0));
    }
    @Test void earlyPacketsSurviveJoinWithoutAllowingLatePacketsToRebindTheMonitor() {
        var monitor = new BoatHealthMonitor(); var old = new Object(); var current = new Object();
        monitor.begin(old); monitor.health(old, 20);
        monitor.health(current, 4);
        monitor.begin(current);
        assertTrue(monitor.lowHealth(20, 8), "Health received before GameJoined must survive the join");
        monitor.health(current, 20);
        monitor.health(old, 1); monitor.death(old);
        assertFalse(monitor.lowHealth(20, 8)); assertFalse(monitor.dead());
    }
}
