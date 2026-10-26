package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Synthetic protocol examples; real server wording still needs multiplayer validation. */
class AutoTpyFeedbackTest {
    @Test void namedCancellationSurvivesManualPendingRowRemoval() {
        assertEquals("Alice",AutoTpyFeedback.parse("Alice's teleport request has expired.").orElseThrow().player());
        assertEquals(AutoTpyFeedback.Kind.Cancelled,AutoTpyFeedback.parse("You have denied Bob's teleport request.").orElseThrow().kind());
    }
    @Test void recognizesOnlyUnambiguousReplies() {
        assertEquals("Alice",AutoTpyFeedback.parse("You accepted Alice's teleport request.").orElseThrow().player());
        assertEquals("Bob",AutoTpyFeedback.parse("Teleport request from Bob has expired.").orElseThrow().player());
        assertEquals(AutoTpyFeedback.Kind.Cancelled,AutoTpyFeedback.parse("Alice cancelled their teleport request.").orElseThrow().kind());
        assertEquals(2000L,AutoTpyFeedback.parse("Please wait 2 seconds before using this command again.").orElseThrow().waitMs());
        for(String s:new String[]{"Alice accepted your teleport request.","You have not accepted Alice's teleport request.","You will accept Alice's teleport request.","<Mallory> You accepted Alice's teleport request.","Unknown reply"}) assertTrue(AutoTpyFeedback.parse(s).isEmpty(),s);
    }
}
