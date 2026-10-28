package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoSetHomeHudTextTest {
    @Test void reportedCooldownAndNextAttemptUseTheSameShortSchedule() {
        var scheduler = new SetHomeScheduler(() -> 1_000_000,
            new SetHomeScheduler.State(0, 0, 0, 43_200_000, 0, 0, List.of()));
        scheduler.sent("Test", new SetHomeScheduler.Location(0, 70, 0, "minecraft:the_nether"), false, 10_000);
        scheduler.message("You have to wait 4m 54s to teleport again.", 60_000);
        var lines = AutoSetHomeHudText.lines("Auto detect", "Test", false, false,
            scheduler.snapshot(false, 60_000, 10_000));
        assertTrue(lines.contains("Next: 5m 4s"));
        assertTrue(lines.contains("Server cooldown: 4m 54s"));
        assertFalse(lines.stream().anyMatch(line -> line.contains("12h")));
    }
    @Test void estimatesAreLabelledAndPausedAndConfirmationStatesStayVisible() {
        var scheduler = new SetHomeScheduler(() -> 1_000_000,
            new SetHomeScheduler.State(0, 0, 0, 300_000, 0, 0, List.of()));
        var location = new SetHomeScheduler.Location(0, 70, 0, "minecraft:the_nether");
        scheduler.sent("Test", location, false, 10_000);
        var waiting = AutoSetHomeHudText.lines("Auto detect", "Test", false, false,
            scheduler.snapshot(false, 60_000, 10_000));
        assertTrue(waiting.contains("Confirm: 15s"));
        scheduler.message("Successfully moved Home: Test", 60_000);
        var lines = AutoSetHomeHudText.lines("Auto detect", "Test", false, true,
            scheduler.snapshot(false, 60_000, 10_000));
        assertTrue(lines.contains("Estimated cooldown: 5m"));
        assertTrue(lines.contains("Paused: countdown continues"));
        assertTrue(lines.contains("Next: 5m 10s"));
    }
    @Test void fixedModeShowsAnIntervalAndOnlyInformationalServerCooldown() {
        var scheduler = new SetHomeScheduler(() -> 1_000_000, SetHomeScheduler.State.empty());
        scheduler.sent("Test", new SetHomeScheduler.Location(0, 70, 0, "minecraft:the_nether"), false, 10_000);
        scheduler.message("You have to wait 4m 54s to teleport again.", 60_000);
        var lines = AutoSetHomeHudText.lines("Fixed timer", "Test", true, false,
            scheduler.snapshot(true, 60_000, 10_000));
        assertTrue(lines.contains("Next: 1m"));
        assertTrue(lines.contains("Interval: 1m"));
        assertTrue(lines.contains("Server cooldown: 4m 54s (informational)"));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("Buffer:")));
    }
}
