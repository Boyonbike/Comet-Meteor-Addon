package dev.tntcoating.core;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChatClanTest {
    @Test void typedNamesSplitOnCommasSemicolonsAndSpaces() {
        var result = ChatClan.typed(" Steve, Alex;Bob  Carl ,, ");
        assertEquals(List.of("Steve", "Alex", "Bob", "Carl"), result.names());
        assertEquals(0, result.invalid());
        assertEquals(new ChatClan.Import(List.of(), 0), ChatClan.typed("   "));
    }

    @Test void typedNamesDropDuplicatesKeepingTheFirstSpellingAndCountInvalidEntries() {
        var result = ChatClan.typed("Steve steve STEVE not-a-name ThisNameIsWayTooLong Alex");
        assertEquals(List.of("Steve", "Alex"), result.names());
        assertEquals(2, result.invalid());
    }

    @Test void singleLineCsvIsAListOfNames() {
        assertEquals(List.of("Steve", "Alex", "Bob"), ChatClan.csv("Steve,Alex,\"Bob\"\n").names());
    }

    @Test void severalLinesWithoutHeaderTakeTheFirstColumn() {
        var result = ChatClan.csv("Steve,Leader,2024-01-01\r\nAlex,Member,2024-02-03\r\n\r\nBob,Member,2024-05-06\r\n");
        assertEquals(List.of("Steve", "Alex", "Bob"), result.names());
        assertEquals(0, result.invalid());
    }

    @Test void headerSelectsTheUsernameColumnWhereverItIs() {
        assertEquals(List.of("Steve", "Alex"), ChatClan.csv("Rank,IGN,Joined\nLeader,Steve,2024\nMember,Alex,2025").names());
        assertEquals(List.of("Steve", "Alex"), ChatClan.csv("Minecraft Username;Rank\nSteve;Leader\nAlex;Member").names());
        assertEquals(List.of("Steve", "Alex"), ChatClan.csv("name\nSteve\nAlex").names());
        // A player whose name merely contains a header word is still a player.
        assertEquals(List.of("Nameless", "Steve"), ChatClan.csv("Nameless\nSteve").names());
    }

    @Test void spreadsheetCopiesAndExportsAreUnderstood() {
        assertEquals(List.of("Steve", "Alex"), ChatClan.csv("Player\tRank\nSteve\tLeader\nAlex\tMember\n").names());
        assertEquals(List.of("Steve", "Alex"), ChatClan.csv("﻿Steve\nAlex").names());
        var quoted = ChatClan.csv("Name,Note\n\"Steve\",\"likes, commas\"\n\"Al\"\"ex\",x");
        assertEquals(List.of("Steve"), quoted.names());
        assertEquals(1, quoted.invalid());
    }

    @Test void filesDecodeAsUtf8OrUtf16WithByteOrderMark() {
        assertEquals("Steve\nAlex", ChatClan.decode("Steve\nAlex".getBytes(StandardCharsets.UTF_8)));
        byte[] utf16 = "﻿Steve\nAlex".getBytes(StandardCharsets.UTF_16LE);
        assertEquals(List.of("Steve", "Alex"), ChatClan.csv(ChatClan.decode(utf16)).names());
    }

    @Test void addingMergesWithoutDuplicatesAndRemovingIgnoresCase() {
        var saved = ChatClan.add(List.of("Steve"), List.of("steve", "Alex", "bad name", "Alex"));
        assertEquals(List.of("Steve", "Alex"), saved);
        assertTrue(ChatClan.contains(saved, "ALEX"));
        assertEquals(List.of("Steve"), ChatClan.remove(saved, "alex"));
        assertThrows(UnsupportedOperationException.class, () -> saved.add("Bob"));
    }

    @Test void handEditedEntriesAreTrimmedAndInvalidOnesSkipped() {
        var saved = List.of(" Steve ", "", "two words", "steve", "Alex");
        assertEquals(List.of("Steve", "Alex"), ChatClan.members(saved));
        assertEquals(Set.of("steve", "alex"), ChatClan.keys(saved));
        assertTrue(ChatClan.contains(saved, "steve"));
        assertEquals(List.of("", "two words", "Alex"), ChatClan.remove(saved, "Steve"));
    }

    @Test void friendsComeFirstWithOnlineFriendsOnTopThenOnlineThenEveryoneElse() {
        var friends = Set.of("zed", "amy", "kim");
        var online = Set.of("zed", "bob", "carl");
        var groups = ChatClan.groups(List.of("dave", "Carl", "kim", "Amy", "Zed", "bob", "eve"),
            name -> friends.contains(name.toLowerCase()), name -> online.contains(name.toLowerCase()));
        assertEquals(List.of("Zed", "Amy", "kim"), groups.friends());
        assertEquals(List.of("bob", "Carl"), groups.online());
        assertEquals(List.of("dave", "eve"), groups.offline());
        assertTrue(ChatClan.groups(List.of(), name -> true, name -> true).isEmpty());
    }

    @Test void summaryReportsWhatHappened() {
        assertEquals("Added 3 members.", ChatClan.summary(3, 0, 0));
        assertEquals("Added 1 member, 2 already in the clan, skipped 1 invalid name.", ChatClan.summary(1, 2, 1));
        assertEquals("1 already in the clan.", ChatClan.summary(0, 1, 0));
        assertEquals("Skipped 2 invalid names.", ChatClan.summary(0, 0, 2));
        assertEquals("No usernames found.", ChatClan.summary(0, 0, 0));
    }
}
