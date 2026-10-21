package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

final class ChatFriendHighlightTest {
    private static final int FRIEND = 0x00ffb4;
    private static final int ORIGINAL = 0xff5555;

    @Test void highlightsExactFriendNamesIgnoringCaseAndPreservesOtherText() {
        var original = Component.literal("<ALICE> hello Bob, Alice!").withColor(ORIGINAL);
        var highlighted = highlight(original);
        assertEquals(original.getString(), highlighted.getString());
        var styles = styles(highlighted);
        for (int i = 1; i < 6; i++) assertEquals(FRIEND, styles.get(i).getColor().getValue());
        assertEquals(ORIGINAL, styles.get(0).getColor().getValue());
        assertEquals(ORIGINAL, styles.get(13).getColor().getValue());
        assertEquals(ORIGINAL, original.getStyle().getColor().getValue());
    }

    @Test void doesNotHighlightFriendNamesInsideOtherUsernames() {
        var message = Component.literal("Alice2 xAlice Alice_ _Alice Alice1234567890123");
        assertSame(message, highlight(message));
    }

    @Test void nameSplitAcrossServerStylesRetainsClickHoverInsertionAndFormatting() {
        var click = new ClickEvent.SuggestCommand("/msg Alice ");
        var hover = new HoverEvent.ShowText(Component.literal("Player details"));
        Style parent = Style.EMPTY.withColor(ORIGINAL).withClickEvent(click).withHoverEvent(hover).withInsertion("Alice");
        var message = Component.empty().setStyle(parent)
            .append(Component.literal("<Al").withStyle(Style.EMPTY.withBold(true)))
            .append(Component.literal("ice>").withStyle(Style.EMPTY.withItalic(true)));
        var result = highlight(message);
        assertEquals("<Alice>", result.getString());
        var before = styles(message);
        var after = styles(result);
        for (int i = 0; i < after.size(); i++) {
            assertEquals(click, after.get(i).getClickEvent());
            assertEquals(hover, after.get(i).getHoverEvent());
            assertEquals("Alice", after.get(i).getInsertion());
            assertEquals(before.get(i).isBold(), after.get(i).isBold());
            assertEquals(before.get(i).isItalic(), after.get(i).isItalic());
            assertEquals(i > 0 && i < 6 ? FRIEND : ORIGINAL, after.get(i).getColor().getValue());
        }
    }

    @Test void supportsUnicodeSurroundingsAndMultipleMentionsWithoutChangingCharacters() {
        var message = Component.literal("🚀 Alice\n你好 Alice and Bob").withColor(ORIGINAL);
        var highlighted = highlight(message);
        assertEquals(message.getString(), highlighted.getString());
        int first = message.getString().indexOf("Alice");
        int second = message.getString().lastIndexOf("Alice");
        assertEquals(FRIEND, styles(highlighted).get(first).getColor().getValue());
        assertEquals(FRIEND, styles(highlighted).get(second).getColor().getValue());
    }

    @Test void removingFriendOrDisablingHighlightRestoresTheOriginalServerColour() {
        var original = Component.literal("Alice: hello").withColor(ORIGINAL);
        assertEquals(FRIEND, styles(highlight(original)).getFirst().getColor().getValue());
        assertSame(original, ChatFriendHighlight.apply(original, name -> false, FRIEND));
        assertEquals(ORIGINAL, styles(original).getFirst().getColor().getValue());
        assertEquals(0x123456, styles(ChatFriendHighlight.apply(original, name -> true, 0x88123456)).getFirst().getColor().getValue());
    }

    @Test void eachNameCanTakeItsOwnColourInOnePass() {
        int clan = 0xffaa3c;
        var message = Component.literal("Alice and Bob met Carol").withColor(ORIGINAL);
        var result = ChatFriendHighlight.apply(message, name -> name.equals("Alice") ? Integer.valueOf(FRIEND)
            : name.equals("Bob") ? Integer.valueOf(clan) : null);
        var styles = styles(result);
        assertEquals(message.getString(), result.getString());
        assertEquals(FRIEND, styles.get(0).getColor().getValue());
        assertEquals(ORIGINAL, styles.get(6).getColor().getValue());
        assertEquals(clan, styles.get(10).getColor().getValue());
        assertEquals(ORIGINAL, styles.get(18).getColor().getValue());
    }

    @Test void recolourPaintsEveryPartButKeepsFormattingAndInteractions() {
        var click = new ClickEvent.SuggestCommand("/msg Nick ");
        var name = Component.literal("[VIP] ").withColor(ORIGINAL)
            .append(Component.literal("Nick").withStyle(Style.EMPTY.withBold(true).withClickEvent(click)));
        var result = ChatFriendHighlight.recolor(name, 0x88123456);
        assertEquals(name.getString(), result.getString());
        var before = styles(name);
        var after = styles(result);
        for (int i = 0; i < after.size(); i++) {
            assertEquals(0x123456, after.get(i).getColor().getValue());
            assertEquals(before.get(i).isBold(), after.get(i).isBold());
            assertEquals(before.get(i).getClickEvent(), after.get(i).getClickEvent());
        }
    }

    private static Component highlight(Component message) {
        return ChatFriendHighlight.apply(message, name -> name.equalsIgnoreCase("Alice"), FRIEND);
    }

    private static List<Style> styles(Component message) {
        List<Style> result = new ArrayList<>();
        message.visit((Style style, String text) -> {
            for (int i = 0; i < text.length(); i++) result.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }
}
