package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiscordIgnTest {
    @Test void linksAllGuildMembersRegardlessOfRoles() {
        DiscordIgn.Result result = DiscordIgn.parse(
            List.of(array(
                member("1", false, false, "10"),
                member("2", false, false, "20"),
                member("3", true, false, "10"),
                member("4", false, true, "10"),
                member("5", false, false))),
            List.of(array(
                message("100", "1", false, "Alice"),
                message("99", "2", false, "Bob"),
                message("98", "5", false, "Nobody"),
                message("97", "3", true, "Bot"))));

        assertEquals(List.of("Alice", "Bob", "Nobody"), result.names());
        assertEquals(3, result.members());
        assertTrue(result.links().containsKey("1"));
        assertTrue(result.links().containsKey("2"));
    }

    @Test void latestMessagePerAuthorWins() {
        DiscordIgn.Result result = DiscordIgn.parse(
            List.of(array(member("1", false, false, "10"))),
            List.of(array(
                message("3", "1", false, "NewName"),
                message("2", "1", false, "OldName"))));

        assertEquals(List.of("NewName"), result.names());
    }

    @Test void acceptsPrefixAndBackticksAndCountsInvalidText() {
        DiscordIgn.Result result = DiscordIgn.parse(
            List.of(array(
                member("1", false, false, "10"),
                member("2", false, false, "10"),
                member("3", false, false, "10"),
                member("4", false, false, "10"))),
            List.of(array(
                message("4", "1", false, "ign: Alice"),
                message("3", "2", false, "`Bob`"),
                message("2", "3", false, "hello world"),
                message("1", "4", false, "Ab"))));

        assertEquals(List.of("Alice", "Bob"), result.names());
        assertEquals(2, result.invalid());
    }

    @Test void conflictingClaimIsSkippedAndReported() {
        DiscordIgn.Result result = DiscordIgn.parse(
            List.of(array(
                member("1", false, false, "10"),
                member("2", false, false, "10"),
                member("3", false, false, "10"))),
            List.of(array(
                message("3", "1", false, "Shared"),
                message("2", "2", false, "Shared"),
                message("1", "3", false, "Solo"))));

        assertEquals(List.of("Solo"), result.names());
        assertEquals(List.of("Shared"), result.conflicts());
        assertFalse(result.links().containsKey("1"));
        assertTrue(result.links().containsKey("3"));
    }

    @Test void messagesWithoutContentMeanUnreadable() {
        DiscordIgn.Result result = DiscordIgn.parse(
            List.of(array(member("1", false, false, "10"))),
            List.of(array(message("1", "1", false, ""))));

        assertTrue(result.unreadable());
        assertEquals(List.of(), result.names());
        assertEquals(1, result.members());
    }

    @Test void emptyMessageListIsReadable() {
        DiscordIgn.Result result = DiscordIgn.parse(
            List.of(array(member("1", false, false, "10"))),
            List.of(array()));

        assertFalse(result.unreadable());
        assertEquals(List.of(), result.names());
    }

    @Test void messagesFromNonMembersAreIgnored() {
        DiscordIgn.Result result = DiscordIgn.parse(
            List.of(array(member("1", false, false, "10"))),
            List.of(array(message("2", "9", false, ""), message("1", "9", false, "Ghost"))));

        assertEquals(List.of(), result.names());
        assertEquals(0, result.invalid());
        assertFalse(result.unreadable());
    }

    @Test void reconcileKeepsManualNamesAndRemovesPreviouslySyncedOnes() {
        List<String> merged = DiscordIgn.reconcile(List.of("Manual", "Alice"), List.of("Alice"), List.of("Bob"));

        assertEquals(List.of("Manual", "Bob"), merged);
    }

    @Test void reconcileIgnoresCaseWhenKeepingSyncedNames() {
        List<String> merged = DiscordIgn.reconcile(List.of("alice"), List.of("Alice"), List.of("ALICE"));

        assertEquals(1, merged.size());
        assertTrue(merged.getFirst().equalsIgnoreCase("alice"));
    }

    @Test void malformedMemberDoesNotSilentlyRemoveAnExistingPlayer() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DiscordIgn.parse(
            List.of("[{\"roles\":[\"10\"]}]"),
            List.of(array(message("1", "1", false, "Alice")))));
    }

    @Test void malformedMessageDoesNotSilentlyRemoveAnExistingPlayer() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DiscordIgn.parse(
            List.of(array(member("1", false, false, "10"))),
            List.of("[{\"content\":\"Alice\"}]")));
    }

    @Test void manualNameAlsoPresentInDiscordStaysManualAcrossSyncs() {
        List<String> before = List.of("Manual", "Alice");
        List<String> next = List.of("Alice", "Bob");
        List<String> managed = DiscordIgn.managedNames(before, List.of(), next);
        assertEquals(List.of("Bob"), managed);
        List<String> after = DiscordIgn.reconcile(before, List.of(), next);
        assertEquals(List.of("Manual", "Alice", "Carol"),
            DiscordIgn.reconcile(after, managed, List.of("Carol")));
    }

    @Test void previouslyManagedNameKeepsItsOwnershipWhenStillPresent() {
        assertEquals(List.of("ALICE", "Bob"),
            DiscordIgn.managedNames(List.of("alice", "Manual"), List.of("Alice"), List.of("ALICE", "Bob")));
    }

    @Test void mixedReadableAndBlankMessagesCannotPublishAPartialRoster() {
        var result = DiscordIgn.parse(List.of(array(member("1", false, false, "10"),
            member("2", false, false, "10"))),
            List.of(array(message("2", "1", false, ""), message("1", "2", false, "Bob"))));
        assertTrue(result.unreadable());
    }

    @Test void malformedRolesCannotSilentlyExcludeAMember() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DiscordIgn.parse(
            List.of("[{\"user\":{\"id\":\"1\"},\"roles\":[{}]}]"),
            List.of(array(message("1", "1", false, "Alice")))));
    }

    @Test void malformedPendingFlagCannotAdmitAnUnscreenedMember() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DiscordIgn.parse(
            List.of("[{\"user\":{\"id\":\"1\"},\"pending\":\"true\",\"roles\":[\"10\"]}]"),
            List.of(array(message("1", "1", false, "Alice")))));
    }

    @Test void acceptsBackticksAfterTheIgnPrefix() {
        var result = DiscordIgn.parse(List.of(array(member("1", false, false, "10"))),
            List.of(array(message("1", "1", false, "ign: `Alice`"))));
        assertEquals(List.of("Alice"), result.names());
    }

    @Test void legacyOwnershipCannotDeleteAnOverlappingManualEntry() {
        var update = DiscordIgn.update(List.of("Manual", "Alice"), List.of("Alice"), List.of("Bob"), false);
        assertEquals(List.of("Manual", "Alice", "Bob"), update.names());
        assertEquals(List.of("Bob"), update.managed());
        var later = DiscordIgn.update(update.names(), update.managed(), List.of("Carol"), true);
        assertEquals(List.of("Manual", "Alice", "Carol"), later.names());
    }

    @Test void acceptsCommaSeparatedJavaAccounts() {
        assertEquals(List.of("immabigback", "Kay_Rage"), accountPost("Kay_Rage, immabigback").names());
    }

    @Test void acceptsSlashSeparatedJavaAccounts() {
        assertEquals(List.of("0r7i", "aagaming22", "fffxioxmain", "FRM"),
            accountPost("aagaming22 / 0r7i / FRM / fffxioxmain").names());
    }

    @Test void acceptsMultilineAndMixedSeparators() {
        assertEquals(List.of("4T9BOT", "_Lem__", "kubvru", "mronkorn", "muchomor3k", "omg_naran", "techni49th", "zjemcikolacje"),
            accountPost("_Lem__, techni49th, omg_naran\nmronkorn, zjemcikolacje, kubvru\n4T9BOT, muchomor3k").names());
        assertEquals(List.of("C1awz", "PiercingC1aws"), accountPost("C1awz\nPiercingC1aws").names());
    }

    @Test void keepsJavaAccountsAlongsideUnsupportedBedrockNamesWithoutRenamingThem() {
        assertEquals(List.of("1nvoke_", "C1awz"), accountPost("C1awz, .Neuro, 1nvoke_").names());
        assertEquals(List.of(), accountPost(".Neuro").names());
    }

    @Test void newestValidAccountListReplacesThePreviousList() {
        var result = DiscordIgn.parse(List.of(array(member("1", false, false, "10"))),
            List.of(array(message("3", "1", false, "xrayeassy, user93829473"),
                message("2", "1", false, "SkyUser112G"))));
        assertEquals(List.of("user93829473", "xrayeassy"), result.names());
        assertEquals(List.of("xrayeassy", "user93829473"), result.links().get("1"));
    }

    @Test void conflictExcludesOnlyTheSharedAccount() {
        var result = DiscordIgn.parse(List.of(array(member("1", false, false, "10"), member("2", false, false, "10"))),
            List.of(array(message("3", "1", false, "Alice, Shared"), message("2", "2", false, "shared, Bob"))));
        assertEquals(List.of("Alice", "Bob"), result.names());
        assertEquals(List.of("Shared"), result.conflicts());
        assertEquals(List.of("Alice"), result.links().get("1"));
        assertEquals(List.of("Bob"), result.links().get("2"));
    }

    @Test void repeatedAccountsInOnePostDoNotConflictWithThemselves() {
        var result = accountPost("Kay_Rage, kay_rage / Kay_Rage");
        assertEquals(List.of("Kay_Rage"), result.names());
        assertEquals(List.of(), result.conflicts());
    }

    @Test void supportsPrefixesAndInlineCodeForAccountLists() {
        assertEquals(List.of("F1remast3r", "Mr_M4st3r"), accountPost("ign: `F1remast3r`, `Mr_M4st3r`").names());
    }

    @Test void doesNotExtractNamesFromAnnouncementsOrDiscussion() {
        for (String content : List.of("@everyone write ur ign\nuriauria", "CSV of existing members\nname\nuriauria",
                "whats a CSV", "comma seperated values", "Alice, this is my alt", "/home", "Alice /", "Alice,,Bob")) {
            assertEquals(List.of(), accountPost(content).names(), content);
        }
    }

    @Test void newerBedrockOnlyListDoesNotResurrectAnOldJavaClaim() {
        var result = DiscordIgn.parse(List.of(array(member("1", false, false, "10"), member("2", false, false, "10"))),
            List.of(array(message("3", "1", false, ".Neuro"), message("2", "1", false, "Alice"),
                message("1", "2", false, "Bob"))));
        assertEquals(List.of("Bob"), result.names());
        assertEquals(List.of("Bob"), DiscordIgn.reconcile(List.of("Alice", "Bob"), List.of("Alice", "Bob"), result.names()));
        assertEquals(0, result.invalid());
    }

    @Test void commaAndSlashAltsShareTheirCurrentDiscordUsername() {
        var result = DiscordIgn.parse(
            List.of("[{\"user\":{\"id\":\"1\",\"username\":\"discord_owner\"},\"roles\":[\"10\"]}]"),
            List.of(array(message("1", "1", false, "Alice,Bob / Charlie"))));
        assertEquals(List.of("Alice", "Bob", "Charlie"), result.names());
        assertEquals(java.util.Map.of("Alice", "discord_owner", "Bob", "discord_owner", "Charlie", "discord_owner"), result.usernames());
    }

    @Test void currentMemberUsernameWinsOverAnOlderMessageAuthorName() {
        var result = DiscordIgn.parse(
            List.of("[{\"user\":{\"id\":\"1\",\"username\":\"new_handle\",\"global_name\":\"Display Name\"},\"nick\":\"Server Nick\",\"roles\":[]}]"),
            List.of("[{\"id\":\"1\",\"author\":{\"id\":\"1\",\"username\":\"old_handle\"},\"content\":\"Alice\"}]"));
        assertEquals("new_handle", result.usernames().get("Alice"));
    }

    @Test void usernameCanFallBackToMessageAuthorWhenAbsentFromMemberData() {
        var result = DiscordIgn.parse(List.of(array(member("1", false, false))),
            List.of("[{\"id\":\"1\",\"author\":{\"id\":\"1\",\"username\":\"owner_handle\"},\"content\":\"Alice/Bob\"}]"));
        assertEquals(java.util.Map.of("Alice", "owner_handle", "Bob", "owner_handle"), result.usernames());
    }

    @Test void conflictedAccountsDoNotReceiveAnArbitraryDiscordOwner() {
        var result = DiscordIgn.parse(
            List.of("[{\"user\":{\"id\":\"1\",\"username\":\"owner_one\"},\"roles\":[]},"
                + "{\"user\":{\"id\":\"2\",\"username\":\"owner_two\"},\"roles\":[]}]"),
            List.of(array(message("2", "1", false, "Alice,Shared"), message("1", "2", false, "Bob/Shared"))));
        assertEquals(java.util.Map.of("Alice", "owner_one", "Bob", "owner_two"), result.usernames());
        assertEquals(List.of("Shared"), result.conflicts());
    }

    @Test void savedDiscordUsernamesSurviveRestartWithCaseInsensitiveLookup() {
        String saved = DiscordIgn.encodeUsernames(java.util.Map.of("Alice", "discord_owner"));
        assertEquals(java.util.Map.of("alice", "discord_owner"), DiscordIgn.decodeUsernames(saved));
        assertEquals(java.util.Map.of(), DiscordIgn.decodeUsernames("not JSON"));
    }

    private static DiscordIgn.Result accountPost(String content) {
        return DiscordIgn.parse(List.of(array(member("1", false, false, "10"))),
            List.of(array(message("1", "1", false, content))));
    }

    private static String array(String... values) {
        return "[" + String.join(",", values) + "]";
    }

    private static String member(String id, boolean bot, boolean pending, String... roles) {
        StringBuilder ids = new StringBuilder();
        for (String role : roles) {
            if (!ids.isEmpty()) ids.append(',');
            ids.append('"').append(role).append('"');
        }
        return "{\"user\":{\"id\":\"" + id + "\",\"bot\":" + bot + "},\"pending\":" + pending
            + ",\"roles\":[" + ids + "]}";
    }

    private static String message(String id, String author, boolean bot, String content) {
        return "{\"id\":\"" + id + "\",\"content\":" + new com.google.gson.Gson().toJson(content)
            + ",\"author\":{\"id\":\"" + author + "\",\"bot\":" + bot + "}}";
    }
}
