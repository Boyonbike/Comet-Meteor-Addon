package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AutoTpyPolicyTest {
    @Test void blocklistAlwaysWinsAndFriendsBypassIsOptional() {
        var p = new AutoTpyPolicy(Set.of(" Alice "), Set.of("Bob"), true, true, true, false);
        assertTrue(p.allows("alice", false, false));
        assertTrue(p.allows("Carol", true, false));
        assertFalse(p.allows("Bob", true, false));
        assertFalse(p.allows("Carol", true, true));
        assertFalse(p.allows("bad;name", true, false));
        assertFalse(new AutoTpyPolicy(Set.of(), Set.of(), true, false, true, true).allows("Carol", true, false));
        assertTrue(new AutoTpyPolicy(Set.of(), Set.of(), false, false, true, true).allows("Carol", false, true));
    }
    @Test void selectedRolesReplaceManualWhitelistWithoutBypassingBlocklistOrDirection() {
        var p = new AutoTpyPolicy(Set.of("Manual"), Set.of("Blocked"), true, true, true, false,
            Set.of("RoleMember", "Blocked"), true);
        assertTrue(p.allows("rolemember", false, false));
        assertFalse(p.allows("Manual", true, false));
        assertFalse(p.allows("Blocked", true, false));
        assertFalse(p.allows("RoleMember", false, true));
    }

    @Test void missingRoleMetadataFailsClosedWhenFilterIsActive() {
        var p = new AutoTpyPolicy(Set.of("Alice"), Set.of(), false, true, true, true, Set.of(), true);
        assertFalse(p.allows("Alice", true, false));
    }

    @Test void clearingRoleFilterRestoresWhitelistAndFriendRules() {
        var p = new AutoTpyPolicy(Set.of("Alice"), Set.of(), true, true, true, true, Set.of(), false);
        assertTrue(p.allows("Alice", false, false));
        assertTrue(p.allows("Friend", true, false));
        assertFalse(p.allows("Unknown", false, false));
    }
}
