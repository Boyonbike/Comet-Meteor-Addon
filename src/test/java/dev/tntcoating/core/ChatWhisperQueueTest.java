package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChatWhisperQueueTest {
    private static final String REFUSAL = "Please wait 2 seconds before sending another whisper!";

    private static ChatWhisperQueue.Whisper whisper(String player, String text) {
        return new ChatWhisperQueue.Whisper(player, text, "/msg " + player + " " + text);
    }

    @Test void aRefusedWhisperIsResentOnceTheServersWaitIsOver() {
        var queue = new ChatWhisperQueue();
        queue.add(whisper("Alice", "one"));
        assertEquals("one", queue.poll(0).text(), "With no delay known yet, the first whisper goes at once");
        queue.add(whisper("Alice", "two"));
        assertEquals("two", queue.poll(50).text());
        assertTrue(queue.refused(REFUSAL, 400), "The refusal is handled, so it can be hidden");
        assertEquals(List.of("two"), queue.pending("alice", 400).stream().map(ChatWhisperQueue.Whisper::text).toList());
        assertNull(queue.poll(2_600), "Waits the reported 2 seconds plus a margin");
        assertEquals(2_250, queue.waiting(400));
        assertEquals("two", queue.poll(2_650).text());
        queue.delivered("Alice");
        queue.add(whisper("Alice", "three"));
        assertNull(queue.poll(3_000), "The learned delay spaces out later whispers before the server refuses them");
        assertEquals("three", queue.poll(4_900).text());
    }

    @Test void onlyTheWhisperJustSentIsResent() {
        var queue = new ChatWhisperQueue();
        queue.add(whisper("Alice", "hi"));
        var sent = queue.poll(0);
        queue.sent(sent.command().substring(1), 10);
        queue.delivered("ALICE");
        assertFalse(queue.refused(REFUSAL, 100), "An echoed whisper arrived, so a refusal is not about it");
        assertTrue(queue.pending("Alice", 100).isEmpty());

        queue.add(whisper("Bob", "queued"));
        assertEquals("queued", queue.poll(3_000).text());
        queue.sent("msg Carol typed in normal chat", 3_100);
        assertFalse(queue.refused(REFUSAL, 3_200), "A whisper typed in normal chat owns the next refusal and is left to the player");
        assertTrue(queue.pending("Bob", 3_200).isEmpty());

        queue.add(whisper("Bob", "late"));
        assertEquals("late", queue.poll(9_000).text());
        assertFalse(queue.refused(REFUSAL, 15_000), "A refusal long after the send cannot belong to it");
    }

    @Test void whispersTypedElsewhereStillDelayTheQueue() {
        var queue = new ChatWhisperQueue();
        queue.refused(REFUSAL, 0);
        queue.sent("w Alice from normal chat", 5_000);
        queue.add(whisper("Alice", "from chat tools"));
        assertNull(queue.poll(6_000));
        assertEquals(1_250, queue.waiting(6_000));
        assertNotNull(queue.poll(7_250));
    }

    @Test void retriesAndQueueLengthAreBounded() {
        var queue = new ChatWhisperQueue();
        queue.add(whisper("Alice", "stuck"));
        long now = 0;
        for (int i = 0; i < 3; i++) {
            assertNotNull(queue.poll(now));
            assertTrue(queue.refused(REFUSAL, now + 100));
            now += 5_000;
        }
        assertNotNull(queue.poll(now));
        assertFalse(queue.refused(REFUSAL, now + 100), "After three refusals the server's message is shown instead");
        assertNull(queue.poll(now + 10_000));
        for (int i = 0; i < ChatWhisperQueue.MAX_QUEUED; i++) assertTrue(queue.add(whisper("Bob", "m" + i)));
        assertFalse(queue.add(whisper("Bob", "overflow")));
        queue.clear();
        assertEquals(0, queue.waiting(0));
        assertTrue(queue.pending("Bob", 0).isEmpty());
    }

    @Test void aConfiguredMinimumSpacesWhispersBeforeAnyRefusal() {
        var queue = new ChatWhisperQueue();
        queue.minimum(2_500);
        queue.add(whisper("Alice", "one"));
        queue.add(whisper("Bob", "two"));
        assertEquals("one", queue.poll(0).text());
        assertNull(queue.poll(2_499));
        var waiting = queue.entries(1_000);
        assertEquals(1, waiting.size());
        assertEquals("two", waiting.getFirst().whisper().text());
        assertEquals(1_500, waiting.getFirst().eta());
        assertEquals("two", queue.poll(2_500).text());
        queue.refused(REFUSAL, 2_600);
        queue.add(whisper("Carol", "three"));
        assertTrue(queue.entries(2_600).stream().allMatch(e -> e.eta() >= 2_250), "A longer learned wait still wins");
    }

    @Test void aCancelledWhisperLeavesTheQueue() {
        var queue = new ChatWhisperQueue();
        queue.minimum(2_500);
        queue.add(whisper("Alice", "sent"));
        queue.poll(0);
        queue.add(whisper("Alice", "keep"));
        queue.add(whisper("Alice", "drop"));
        long drop = queue.entries(0).get(1).id();
        assertTrue(queue.cancel(drop));
        assertEquals(List.of("sent", "keep"), queue.pending("Alice", 0).stream().map(ChatWhisperQueue.Whisper::text).toList());
        assertEquals("keep", queue.poll(2_500).text());
        assertNull(queue.poll(10_000));
    }

    @Test void onlyTheServersOwnRefusalCounts() {
        assertTrue(ChatWhisperQueue.isRefusal(REFUSAL));
        assertTrue(ChatWhisperQueue.isRefusal("§cPlease wait 1 second before sending another whisper."));
        for (String line : List.of("<Mallory> Please wait 2 seconds before sending another whisper!",
            "[unknown player head] Mallory whispers: Please wait 2 seconds before sending another whisper!",
            "Please wait 2 seconds before sending another message", "You have to wait 9m 35s to teleport again."))
            assertFalse(ChatWhisperQueue.isRefusal(line), line);
        var queue = new ChatWhisperQueue();
        queue.add(whisper("Alice", "hi"));
        queue.poll(0);
        assertFalse(queue.refused("<Mallory> " + REFUSAL, 10));
    }

    @Test void recognisesWhisperCommandsAndTheirRecipient() {
        var msg = ChatWhisperQueue.parse("/msg Bob hi there", "/msg {player}");
        assertEquals(new ChatWhisperQueue.Whisper("Bob", "hi there", "/msg Bob hi there"), msg);
        assertEquals("Bob", ChatWhisperQueue.parse("/minecraft:tell Bob x", "/msg {player}").player());
        var reply = ChatWhisperQueue.parse("/r on my way", "/msg {player}");
        assertNull(reply.player());
        assertEquals("on my way", reply.text());
        assertEquals("Bob", ChatWhisperQueue.parse("/m Bob hey", "/m {player}").player(), "The message-command verb counts too");
        assertNull(ChatWhisperQueue.parse("/m Bob hey", "/msg {player}"));
        for (String command : List.of("/msg Bob", "/msg Bob   ", "/r", "/home base", "/tpa Bob"))
            assertNull(ChatWhisperQueue.parse(command, "/msg {player}"), command);
        assertTrue(ChatWhisperQueue.isWhisper("w Bob hi", "/msg {player}"));
        assertFalse(ChatWhisperQueue.isWhisper("hotspot", "/msg {player}"));
    }
}
