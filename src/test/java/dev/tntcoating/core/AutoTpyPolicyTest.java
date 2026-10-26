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
}
