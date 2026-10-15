package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlacementPriorityTest {
    @Test void queuedWorkBlocksBeforeSendAndUntilResolved() {
        var gate = new PlacementPriority();
        assertFalse(gate.blocks(10)); gate.reserve();
        assertTrue(gate.blocks(10)); assertTrue(gate.blocks(50));
        gate.resolve(50,0); assertFalse(gate.blocks(50));
    }
    @Test void confirmationKeepsWaterSettlingWindowThenResumes() {
        var gate = new PlacementPriority(); gate.reserve(); gate.resolve(20,2);
        assertTrue(gate.blocks(20)); assertTrue(gate.blocks(21)); assertFalse(gate.blocks(22));
    }
    @Test void nextSpongeTakesPriorityBeforeCoatingCanResume() {
        var gate = new PlacementPriority(); gate.reserve(); gate.resolve(20,2); gate.reserve();
        assertTrue(gate.blocks(22));
        gate.resolve(23,0); assertFalse(gate.blocks(23));
    }
    @Test void disableOrWorldResetCannotLeaveCoatingBlocked() {
        var gate = new PlacementPriority(); gate.reserve(); gate.resolve(30,10); gate.reset();
        assertFalse(gate.blocks(0)); gate.reserve(); gate.reset(); assertFalse(gate.blocks(1));
    }
}
