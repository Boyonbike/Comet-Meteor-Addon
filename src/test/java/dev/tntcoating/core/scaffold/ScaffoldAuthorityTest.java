package dev.tntcoating.core.scaffold;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldAuthorityTest {
    private record State(String block, String property) {}
    @Test void rejectedAirRemainsQuarantinedUntilAckActuallyRemovesGhost() {
        var authority = new ScaffoldAuthority<State>();
        var air = new State("air", ""); var ghost = new State("stone", "");
        Map<Long, State> live = new HashMap<>(); live.put(7L, ghost);
        var delivered = new ArrayList<State>();
        authority.receive(7, air, live::get, (pos, state) -> delivered.add(state));
        assertTrue(authority.blocked(7)); assertTrue(delivered.isEmpty());
        for (int tick = 0; tick < 10000; tick++) authority.reconcile(live::get, (pos, state) -> delivered.add(state));
        assertTrue(authority.blocked(7)); assertTrue(delivered.isEmpty(), "time alone cannot promote a prediction");
        live.put(7L, air); // prediction acknowledgement has applied the retained server state
        authority.reconcile(live::get, (pos, state) -> delivered.add(state));
        assertFalse(authority.blocked(7)); assertEquals(java.util.List.of(air), delivered);
    }
    @Test void propertiesMustMatchAndLatestPacketSupersedesEarlierExpectedState() {
        var authority = new ScaffoldAuthority<State>();
        var bottom = new State("slab", "bottom"); var top = new State("slab", "top"); var air = new State("air", "");
        Map<Long, State> live = new HashMap<>(); live.put(1L, bottom);
        var delivered = new ArrayList<State>();
        authority.receive(1, top, live::get, (pos, state) -> delivered.add(state));
        assertTrue(authority.blocked(1)); assertTrue(delivered.isEmpty());
        authority.receive(1, air, live::get, (pos, state) -> delivered.add(state));
        live.put(1L, top); authority.reconcile(live::get, (pos, state) -> delivered.add(state));
        assertTrue(authority.blocked(1)); assertTrue(delivered.isEmpty());
        live.put(1L, air); authority.reconcile(live::get, (pos, state) -> delivered.add(state));
        assertEquals(java.util.List.of(air), delivered);
    }
    @Test void matchingPacketResolvesImmediatelyAndFreshChunkSupersedesStaleExpectation() {
        var authority = new ScaffoldAuthority<State>();
        var stone = new State("stone", ""); var air = new State("air", "");
        Map<Long, State> live = new HashMap<>(); live.put(1L, stone); live.put(2L, stone);
        var delivered = new ArrayList<State>();
        authority.receive(1, stone, live::get, (pos, state) -> delivered.add(state));
        assertFalse(authority.blocked(1)); assertEquals(java.util.List.of(stone), delivered);
        authority.receive(2, air, live::get, (pos, state) -> delivered.add(state));
        authority.replaceRegion(pos -> pos == 2, live::get, (pos, state) -> delivered.add(state));
        assertFalse(authority.blocked(2)); assertEquals(java.util.List.of(stone, stone), delivered);
    }
    @Test void unloadedPositionsWaitAndCapacityCannotEvictOrSilentlyTrustAnUntrackedGhost() {
        var authority = new ScaffoldAuthority<State>();
        var air = new State("air", "");
        for (long pos = 0; pos < 256; pos++) authority.receive(pos, air, key -> null, (key, state) -> fail("not loaded"));
        assertEquals(256, authority.size()); assertFalse(authority.overflowed());
        authority.receive(256, air, key -> null, (key, state) -> fail("not loaded"));
        assertEquals(256, authority.size()); assertTrue(authority.overflowed());
        assertTrue(authority.blocked(0)); assertTrue(authority.blocked(256)); assertTrue(authority.blocked(999));
        authority.reconcile(key -> null, (key, state) -> fail("not loaded"));
        assertEquals(256, authority.size());
        authority.clear(); assertFalse(authority.overflowed()); assertFalse(authority.blocked(999));
    }
}
