package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ChatCommandTemplateTest {
    @Test void expandsNamedPlaceholderAndPreservesTrailingSpace() {
        ChatCommandTemplate template = ChatCommandTemplate.parse("Message | /msg {player} ");
        assertEquals("Message", template.label());
        assertEquals("/msg Alice ", template.expand("Alice"));
    }

    @Test void supportsPercentPlaceholderAndAddsSlash() {
        assertEquals("/tpa Alice", ChatCommandTemplate.parse("TPA | tpa %player%").expand("Alice"));
    }

    @Test void appendsPlayerWhenPlaceholderIsOmitted() {
        ChatCommandTemplate template = ChatCommandTemplate.parse("/ignore");
        assertEquals("/ignore", template.label());
        assertEquals("/ignore Alice", template.expand("Alice"));
    }

    @Test void rejectsBlankCommands() {
        assertFalse(ChatCommandTemplate.parse("Label |   ").valid());
        assertTrue(ChatCommandTemplate.parse("/w {player} ").valid());
    }
}
