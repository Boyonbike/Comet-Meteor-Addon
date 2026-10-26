package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ChatTpaRequestsTest {
    private final ChatTpaRequests state = new ChatTpaRequests();

    private void message(String text, long now) { state.message(text, List.of(), "Me", now, 120); }

    @Test void configuredCapacityPreservesBurstAndChangesDoNotTreatSendAsSuccess() {
        state.capacity(1024);
        for(int i=0;i<1000;i++) message("P"+i+" has requested to teleport to you.",i);
        assertEquals(1000,state.pending(1000).size());
        assertEquals(1000,state.drainChanges().size());
        state.sent("tpy P0",1001);
        assertTrue(state.drainChanges().isEmpty());
        message("P1 cancelled their teleport request.",1002);
        assertEquals(ChatTpaRequests.ChangeKind.Cancelled,state.drainChanges().getFirst().kind());
    }
    @Test void configuredCapacityRejectsNewPlayersWithoutEvictingOld() {
        state.capacity(64);
        for(int i=0;i<65;i++) message("P"+i+" has requested to teleport to you.",0);
        assertEquals("P0",state.pending(1).getFirst().player());
        assertEquals(1,state.rejected());
    }
    @Test void incomingRequestProvidesNamedYesAndNoCommands() {
        message("§6[TPA] §fAlice has requested to teleport to you.", 100);
        var request = state.pending(100).getFirst();
        assertEquals("Alice", request.player());
        assertEquals("/tpy Alice", request.yesCommand());
        assertEquals("/tpn Alice", request.noCommand());
        assertFalse(request.toThem());
    }

    @Test void recognizesHereRequestsAndMultilinePrompts() {
        message("[TPA] [ELITE] Alice has requested that you teleport to them.\nType /tpy Alice or /tpn Alice.", 0);
        assertTrue(state.pending(10).getFirst().toThem());
        assertEquals("Teleport to Alice", state.pending(10).getFirst().description());
    }

    @Test void supportsReceivedAndSentRequestWordings() {
        message("You have received a teleport request from Alice.", 0);
        message("Bob sent you a teleport request.", 10);
        message("» Carol wants to teleport to you!", 20);
        assertEquals(List.of("Alice", "Bob", "Carol"), state.pending(20).stream().map(ChatTpaRequests.Request::player).toList());
    }

    @Test void clickableCommandPairsWorkWhenServerWordingIsUnrecognized() {
        state.message("New invitation!", List.of("/tpy Alice", "/tpn Alice"), "Me", 0, 120);
        assertEquals("Alice", state.pending(0).getFirst().player());
        state.message("Invitation", List.of("/essentials:tpaccept Bob", "/essentials:tpdeny Bob"), "Me", 10, 120);
        assertEquals(2, state.pending(10).size());
    }

    @Test void manualAndButtonResponsesOnlyRemoveTheNamedRequest() {
        message("Alice has requested to teleport to you.", 0);
        message("Bob has requested to teleport to you.", 10);
        var alice = state.pending(10).getFirst();
        state.sent(alice.yesCommand(), 20);
        assertEquals(List.of("Bob"), state.pending(20).stream().map(ChatTpaRequests.Request::player).toList());
        state.sent("/plugin:tpn bOb", 30);
        assertTrue(state.pending(30).isEmpty());
    }

    @Test void explicitExpiryOverridesFallbackAndSeparateExpiryLineIsApplied() {
        message("Teleport request from Alice. This request expires in 3 seconds.", 0);
        message("Bob has requested to teleport to you.", 10);
        message("This teleport request will expire after 1 minute.", 20);
        assertEquals(2, state.pending(2_999).size());
        assertEquals(List.of("Bob"), state.pending(3_000).stream().map(ChatTpaRequests.Request::player).toList());
        assertTrue(state.pending(60_020).isEmpty());
    }

    @Test void fallbackTimeoutAndSessionResetRemoveRequests() {
        state.message("Alice has requested to teleport to you.", List.of(), "Me", 0, 30);
        assertEquals(1, state.pending(29_999).size());
        assertTrue(state.pending(30_000).isEmpty());
        message("Bob has requested to teleport to you.", 30_010);
        state.clear();
        assertTrue(state.pending(30_010).isEmpty());
    }

    @Test void cancellationAndExpiryMessagesKeepOtherPlayersPending() {
        message("Alice has requested to teleport to you.", 0);
        message("Bob has requested to teleport to you.", 10);
        message("Alice cancelled their teleport request.", 20);
        assertEquals("Bob", state.pending(20).getFirst().player());
        message("Teleport request from Bob has expired.", 30);
        assertTrue(state.pending(30).isEmpty());
    }

    @Test void outgoingRequestsAndChatImitationsDoNotCreateOrConsumeIncomingRequests() {
        message("Teleport request sent to Alice.", 0);
        message("Alice has requested to teleport to Bob.", 10);
        message("[ELITE] Mallory: Alice has requested to teleport to you.", 20);
        message("<Mallory> Alice has requested to teleport to you.", 30);
        assertTrue(state.pending(30).isEmpty());
        message("Alice has requested to teleport to you.", 40);
        message("Mallory: Alice's teleport request has expired.", 50);
        message("Alice accepted your teleport request.", 60);
        assertEquals("Alice", state.pending(60).getFirst().player());
    }

    @Test void malformedCommandsAndUnpairedPlayerNamesAreIgnored() {
        state.message("Invitation", List.of("/tpy Alice", "/tpn Bob", "/tpy Eve;kill", "/tpn Eve;kill"), "Me", 0, 120);
        assertTrue(state.pending(0).isEmpty());
        state.message("Invitation", List.of("/tpy Me", "/tpn Me"), "Me", 10, 120);
        assertTrue(state.pending(10).isEmpty());
    }

    @Test void repeatRequestReplacesOldButtonsWithoutDuplicatingPlayer() {
        message("Alice has requested to teleport to you.", 0);
        var old = state.pending(0).getFirst();
        message("alice has requested to teleport to you.", 20);
        assertEquals(1, state.pending(20).size());
        assertFalse(state.isPending(old, 20));
        assertTrue(state.isPending(state.pending(20).getFirst(), 20));
    }

    @Test void denyingIncomingAliceDoesNotClearAnOutgoingTimerForBob() {
        var timers = new ChatTeleportState();
        timers.sent("tpa Bob", 0);
        timers.sent("tpn Alice", 10);
        assertEquals("Bob", timers.timers(10).getFirst().target());
    }

    @Test void clickablePromptsKeepTheServersOwnAnswerCommands() {
        state.message("Invitation", List.of("/essentials:tpaccept Bob", "/essentials:tpdeny Bob"), "Me", 0, 120);
        var request = state.pending(0).getFirst();
        assertTrue(request.verbsKnown());
        assertEquals("/essentials:tpaccept Bob", request.yesCommand());
        assertEquals("/essentials:tpdeny Bob", request.noCommand());
        message("This teleport request will expire after 1 minute.", 10);
        assertEquals("/essentials:tpaccept Bob", state.pending(10).getFirst().yesCommand());
        message("Alice has requested to teleport to you.", 20);
        assertFalse(state.pending(20).getLast().verbsKnown());
        assertEquals("/tpy Alice", state.pending(20).getLast().yesCommand());
    }

    @Test void onlyPlainNamespacedVerbsAreAccepted() {
        state.message("Invitation", List.of("/a;b:tpaccept Bob", "/a;b:tpdeny Bob"), "Me", 0, 120);
        assertTrue(state.pending(0).isEmpty());
    }
}
