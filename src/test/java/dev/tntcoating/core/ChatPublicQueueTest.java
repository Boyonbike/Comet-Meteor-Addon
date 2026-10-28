package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChatPublicQueueTest {
    @Test void messagesAreSpacedByTheConfiguredCooldown() {
        var queue = new ChatPublicQueue();
        queue.minimum(5_000);
        assertTrue(queue.add("one"));
        assertTrue(queue.add("two"));
        assertTrue(queue.add("three"));
        assertEquals("one", queue.poll(0), "The first message goes at once");
        assertNull(queue.poll(4_999));
        assertEquals("two", queue.poll(5_000));
        assertEquals(List.of(5_000L), queue.entries(5_000).stream().map(ChatPublicQueue.Queued::eta).toList(),
            "The next message waits one full cooldown");
        assertEquals("three", queue.poll(10_000));
        assertTrue(queue.entries(10_000).isEmpty());
    }

    @Test void entriesReportEachMessagesWaitInOrder() {
        var queue = new ChatPublicQueue();
        queue.minimum(5_000);
        queue.add("now");
        queue.poll(0);
        queue.add("a");
        queue.add("b");
        var entries = queue.entries(1_000);
        assertEquals(List.of("a", "b"), entries.stream().map(ChatPublicQueue.Queued::text).toList());
        assertEquals(List.of(4_000L, 9_000L), entries.stream().map(ChatPublicQueue.Queued::eta).toList());
        assertEquals(4_000, queue.waiting(1_000));
    }

    @Test void aCancelledMessageNeverSends() {
        var queue = new ChatPublicQueue();
        queue.minimum(5_000);
        queue.add("first");
        queue.poll(0);
        queue.add("keep");
        queue.add("drop");
        long drop = queue.entries(0).get(1).id();
        assertTrue(queue.cancel(drop));
        assertFalse(queue.cancel(drop));
        assertEquals("keep", queue.poll(5_000));
        assertNull(queue.poll(20_000));
    }

    @Test void chatTypedElsewhereRestartsTheCooldown() {
        var queue = new ChatPublicQueue();
        queue.minimum(5_000);
        queue.sent("typed in normal chat", 1_000);
        queue.add("queued");
        assertNull(queue.poll(5_999));
        assertEquals("queued", queue.poll(6_000));
        queue.sent("queued", 6_050);
        queue.add("next");
        assertNull(queue.poll(10_999), "The echo of a queued message does not restart the cooldown again");
        assertEquals("next", queue.poll(11_000));
    }

    @Test void aRefusedMessageIsResentAfterTheServersWait() {
        var queue = new ChatPublicQueue();
        queue.minimum(1_000);
        queue.add("hello");
        assertEquals("hello", queue.poll(0));
        assertTrue(queue.refused("Please wait 3 seconds before sending another message!", 200));
        assertNull(queue.poll(3_000));
        assertEquals("hello", queue.poll(3_450), "Waits the reported time plus a margin");
        queue.add("later");
        assertNull(queue.poll(4_000), "The learned wait spaces out later messages too");
        assertEquals("later", queue.poll(6_700));
    }

    @Test void onlyTheServersChatRefusalCounts() {
        assertTrue(ChatPublicQueue.isRefusal("§cPlease wait 5 seconds before sending another message."));
        assertTrue(ChatPublicQueue.isRefusal("Please wait 2 seconds before chatting again"));
        for (String line : List.of("<Mallory> Please wait 5 seconds before sending another message!",
            "Please wait 2 seconds before sending another whisper!", "You have to wait 9m 35s to teleport again."))
            assertFalse(ChatPublicQueue.isRefusal(line), line);
    }

    @Test void lengthIsBoundedAndClearEmptiesIt() {
        var queue = new ChatPublicQueue();
        for (int i = 0; i < ChatPublicQueue.MAX_QUEUED; i++) assertTrue(queue.add("m" + i));
        assertFalse(queue.add("overflow"));
        assertFalse(queue.add("   "), "Blank messages are refused");
        queue.clear();
        assertTrue(queue.entries(0).isEmpty());
        assertEquals(0, queue.waiting(0));
    }
}
