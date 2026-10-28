package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DiscordIgnSyncStateTest {
    private final DiscordIgnSyncState state = new DiscordIgnSyncState();
    private final DiscordIgnSyncState.Settings settings = new DiscordIgnSyncState.Settings(
        "https://relay.example.com", true, true);

    @Test void cancelledCompletionCannotAffectANewerSync() {
        var old = state.begin(settings, List.of(), List.of());
        state.cancel();
        var next = state.begin(settings, List.of(), List.of());
        assertFalse(state.complete(old, settings, List.of(), List.of()));
        assertTrue(state.busy());
        assertFalse(state.isCurrent(old));
        assertTrue(state.isCurrent(next));
        assertTrue(state.complete(next, settings, List.of(), List.of()));
        assertFalse(state.busy());
    }

    @Test void changingConfigurationDiscardsTheOldResult() {
        var ticket = state.begin(settings, List.of(), List.of());
        var changed = new DiscordIgnSyncState.Settings("https://other.example.com", true, true);
        assertFalse(state.complete(ticket, changed, List.of(), List.of()));
        assertFalse(state.busy());
    }

    @Test void editingEitherDestinationDiscardsTheOldResult() {
        var ticket = state.begin(settings, List.of("Alice"), List.of("Bob"));
        assertFalse(state.complete(ticket, settings, List.of("Manual", "Alice"), List.of("Bob")));
        ticket = state.begin(settings, List.of("Alice"), List.of("Bob"));
        assertFalse(state.complete(ticket, settings, List.of("Alice"), List.of("Manual", "Bob")));
    }

    @Test void preventsOverlappingReadsAndAllowsAnotherAfterCompletion() {
        var ticket = state.begin(settings, List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> state.begin(settings, List.of(), List.of()));
        assertTrue(state.complete(ticket, settings, List.of(), List.of()));
        assertDoesNotThrow(() -> state.begin(settings, List.of(), List.of()));
    }
}
