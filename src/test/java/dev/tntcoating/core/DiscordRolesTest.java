package dev.tntcoating.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DiscordRolesTest {
    private static final String ROLES = """
        [{"id":"1","name":"@everyone","position":0},
         {"id":"10","name":"Member","position":1},
         {"id":"20","name":"Officer","position":5},
         {"id":"30","name":"Ally","position":2}]
        """;

    @Test void allRoleNamesFollowHierarchyAndEveryoneIsExcluded() throws Exception {
        var roles = DiscordRoles.bind(Map.of("Alice", List.of("10", "20", "1"), "Bob", List.of()), "1", ROLES);
        assertEquals("Officer · Member", DiscordRoles.label(roles, "ALICE"));
        assertEquals("No role", DiscordRoles.label(roles, "Bob"));
        assertEquals("", DiscordRoles.label(roles, "Unknown"));
    }

    @Test void friendsStayFirstAndOthersFollowRoleHierarchyInsteadOfOnlineStatus() throws Exception {
        var roles = DiscordRoles.bind(Map.of("Alice", List.of("20"), "Bob", List.of("10"),
            "Charlie", List.of("30"), "Friend", List.of("10"), "NoRole", List.of()), "1", ROLES);
        var groups = DiscordRoles.groups(List.of("Bob", "Charlie", "NoRole", "Friend", "Alice", "OnlineFriend"),
            Set.of("Friend", "OnlineFriend")::contains, Set.of("OnlineFriend", "Bob")::contains, roles);
        assertEquals(List.of("OnlineFriend", "Friend"), groups.friends());
        assertEquals(List.of("Alice", "Charlie", "Bob", "NoRole"), groups.others());
    }

    @Test void sameRoleMembersAreAlphabeticalIgnoringCase() throws Exception {
        var roles = DiscordRoles.bind(Map.of("Zed", List.of("10"), "alice", List.of("10")), "1", ROLES);
        assertEquals(List.of("alice", "Zed"), DiscordRoles.groups(List.of("Zed", "alice"), n -> false, n -> false, roles).others());
    }

    @Test void metadataSurvivesRestartAndKeepsRoleOrdering() throws Exception {
        var roles = DiscordRoles.bind(Map.of("Alice", List.of("10", "20")), "1", ROLES);
        var restored = DiscordRoles.decode(DiscordRoles.encode(roles));
        assertEquals("Officer · Member", DiscordRoles.label(restored, "alice"));
        assertEquals(roles, restored);
        assertEquals(Map.of(), DiscordRoles.decode("not valid JSON"));
    }

    @Test void roleChangesReplaceOldNamesAndHierarchy() throws Exception {
        var old = DiscordRoles.bind(Map.of("Alice", List.of("20")), "1", ROLES);
        var next = DiscordRoles.bind(Map.of("Alice", List.of("10")), "1", ROLES);
        assertEquals("Officer", DiscordRoles.label(old, "Alice"));
        assertEquals("Member", DiscordRoles.label(next, "Alice"));
    }

    @Test void missingRoleDefinitionsRefusePartialMetadata() {
        assertThrows(java.io.IOException.class, () -> DiscordRoles.bind(Map.of("Alice", List.of("999")), "1", ROLES));
    }

    @Test void tiedRolePositionsUseNumericRoleIds() throws Exception {
        var roles = DiscordRoles.bind(Map.of("Newer", List.of("100"), "Older", List.of("20")), "1",
            "[{\"id\":\"100\",\"name\":\"New\",\"position\":5},{\"id\":\"20\",\"name\":\"Old\",\"position\":5}]");
        assertEquals(List.of("Older", "Newer"), DiscordRoles.groups(List.of("Newer", "Older"), n -> false, n -> false, roles).others());
    }

    @Test void emptyEveryoneOnlyRoleListHasNoBadgeBeyondNoRole() throws Exception {
        var roles = DiscordRoles.bind(Map.of("Alice", List.of("1")), "1", ROLES);
        assertEquals("No role", DiscordRoles.label(roles, "Alice"));
    }

    @Test void ordinaryRoleNamedEveryoneIsNotTheImplicitGuildRole() throws Exception {
        var roles = DiscordRoles.bind(Map.of("Alice", List.of("10")), "1",
            "[{\"id\":\"10\",\"name\":\"@everyone\",\"position\":1}]");
        assertEquals("@everyone", DiscordRoles.label(roles, "Alice"));
    }

    @Test void roleTextCannotInjectMinecraftFormattingOrLineBreaks() throws Exception {
        var roles = DiscordRoles.bind(Map.of("Alice", List.of("10")), "1",
            "[{\"id\":\"10\",\"name\":\"Officer\\n§k\",\"position\":1}]");
        assertFalse(DiscordRoles.label(roles, "Alice").contains("\n"));
        assertFalse(DiscordRoles.label(roles, "Alice").contains("§"));
    }
    @Test void availableRolesAreDistinctByIdAndFollowHierarchy() throws Exception {
        var roster = DiscordRoles.bind(Map.of("Alice", List.of("10", "20"), "Alt", List.of("20", "30")), "1", ROLES);
        assertEquals(List.of("20", "30", "10"), DiscordRoles.availableRoles(roster).stream().map(DiscordRoles.Role::id).toList());
    }

    @Test void selectedIdsMatchEveryAccountAndSurviveRenamedRoles() throws Exception {
        var roster = DiscordRoles.bind(Map.of("Alice", List.of("20"), "AliceAlt", List.of("20"),
            "Bob", List.of("10"), "NoRole", List.of()), "1", ROLES.replace("Officer", "Renamed"));
        assertEquals(Set.of("alice", "alicealt"), DiscordRoles.matchingMembers(roster, Set.of("20")));
        assertEquals(Set.of("alice", "alicealt", "bob"), DiscordRoles.matchingMembers(roster, Set.of("20", "10")));
        assertEquals(Set.of(), DiscordRoles.matchingMembers(roster, Set.of("Officer")));
    }

    @Test void unknownRolesAndMissingMetadataNeverAdmitAccounts() throws Exception {
        var roster = DiscordRoles.bind(Map.of("Alice", List.of("20"), "NoRole", List.of()), "1", ROLES);
        assertEquals(Set.of(), DiscordRoles.matchingMembers(roster, Set.of("999")));
        assertEquals(Set.of(), DiscordRoles.matchingMembers(Map.of(), Set.of("20")));
        assertEquals(Set.of(), DiscordRoles.matchingMembers(roster, Set.of()));
    }

}
