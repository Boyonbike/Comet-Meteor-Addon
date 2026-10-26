package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AutoTpyWhitelistImportTest {
    private byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    @Test void appendCountsDuplicatesAndBlockedOverlaps() {
        var r = AutoTpyWhitelistImport.parse(List.of("Bob"), Set.of("alice"), List.of(bytes("Alice,alice,Bob")));
        assertEquals(List.of("Bob", "Alice"), r.merged());
        assertEquals(1, r.added()); assertEquals(2, r.duplicates()); assertEquals(1, r.blocked());
    }
    @Test void staleCompletionCannotOverwriteEditsOrAnotherSession() {
        var ticket = new AutoTpyWhitelistImport.Ticket(1,"server/account",List.of("Alice"));
        assertTrue(ticket.canApply(1,"server/account",List.of("Alice")));
        assertFalse(ticket.canApply(2,"server/account",List.of("Alice")));
        assertFalse(ticket.canApply(1,"other/account",List.of("Alice")));
        assertFalse(ticket.canApply(1,"server/account",List.of("Bob")));
    }
    @Test void supportsSpreadsheetEncodingsHeadersAndDelimiters() {
        for (String separator : List.of(",", ";", "\t")) {
            var r = AutoTpyWhitelistImport.parse(List.of(), Set.of(), List.of(bytes("\ufeffRank"+separator+"Username\nadmin"+separator+"\"Alice\"\nuser"+separator+"Bob")));
            assertEquals(List.of("Alice", "Bob"), r.merged());
        }
        assertEquals(List.of("Alice"), AutoTpyWhitelistImport.parse(List.of(), Set.of(), List.of("Username\nAlice".getBytes(StandardCharsets.UTF_16))).merged());
        assertEquals(List.of("Alice", "Bob"), AutoTpyWhitelistImport.parse(List.of(), Set.of(), List.of(bytes("Alice,rank\nBob,rank"))).merged());
    }
    @Test void invalidAndEmptyNamesAreReportedWithoutPartialImport() {
        var r = AutoTpyWhitelistImport.parse(List.of(), Set.of(), List.of(bytes("Username\nAlice\nnot a name\n"), bytes("")));
        assertEquals(1,r.invalid()); assertEquals(List.of("Alice"),r.merged());
        var saved = List.of("Existing");
        assertThrows(IllegalArgumentException.class, () -> AutoTpyWhitelistImport.parse(saved, Set.of(), List.of(bytes("Alice"), bytes("\"Bob"))));
        assertEquals(List.of("Existing"),saved);
        assertThrows(IllegalArgumentException.class, () -> AutoTpyWhitelistImport.parse(saved, Set.of(), List.of(bytes("\"Alice\nBob\""))));
        assertThrows(IllegalArgumentException.class, () -> AutoTpyWhitelistImport.parse(saved, Set.of(), List.of(new byte[(1<<20)+1])));
    }
}
