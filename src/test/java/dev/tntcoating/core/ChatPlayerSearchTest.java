package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChatPlayerSearchTest {
    @Test void matchesOnlyTheStartOfNamesAsTheQueryGrows() {
        var names = List.of("Steve", "Sam", "Alice", "Oscar", "sammy");
        assertEquals(List.of("Steve", "Sam", "sammy"), ChatPlayerSearch.matching("s", names));
        assertEquals(List.of("Sam", "sammy"), ChatPlayerSearch.matching("sa", names));
        assertEquals(List.of("sammy"), ChatPlayerSearch.matching("samm", names));
        assertTrue(ChatPlayerSearch.matching("xyz", names).isEmpty());
    }

    @Test void ignoresCaseAndSurroundingWhitespaceAndSupportsUsernameCharacters() {
        var names = List.of("Steve", "SAM", "_Sam", "7Sam");
        assertEquals(List.of("Steve", "SAM"), ChatPlayerSearch.matching(" S ", names));
        assertEquals(List.of("_Sam"), ChatPlayerSearch.matching("_s", names));
        assertEquals(List.of("7Sam"), ChatPlayerSearch.matching("7", names));
    }

    @Test void mergesRecentFriendOnlineAndRequestMatchesWithoutDuplicatesOrReordering() {
        assertEquals(List.of("Sam", "Steve", "Sarah", "Simon"), ChatPlayerSearch.matching("s",
            List.of("Sam"), List.of("SAM", "Steve"), List.of("Sarah", "steve"), List.of("Simon", "sam")));
    }

    @Test void clearingSearchRestoresAllValidNamesAndResultsAreSnapshots() {
        var names = new java.util.ArrayList<>(List.of("Bob", "Sam", "bad name", "/spawn", "Steve"));
        var result = ChatPlayerSearch.matching("  ", names);
        assertEquals(List.of("Bob", "Sam", "Steve"), result);
        names.clear();
        assertEquals(List.of("Bob", "Sam", "Steve"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add("Alice"));
    }
}
