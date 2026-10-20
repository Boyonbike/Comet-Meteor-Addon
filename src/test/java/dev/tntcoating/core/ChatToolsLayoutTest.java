package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChatToolsLayoutTest {
    @Test void headerBodyAndComposerStackInsideEveryWindowSize() {
        for (int width : List.of(320, 400, 579, 580, 720, 1100)) {
            for (int height : List.of(220, 280, 339, 340, 480, 800)) {
                var frame = ChatToolsLayout.frame(43, 27, width, height);
                var header = frame.header();
                var body = frame.content();
                var footer = frame.footer();
                var composer = frame.composer();
                assertEquals(27, header.y());
                assertEquals(27 + height, footer.bottom());
                assertTrue(body.x() > 43 && body.right() < 43 + width, "The body keeps side padding");
                assertEquals(header.bottom(), body.y(), "Pages scroll directly under the header divider");
                assertEquals(footer.y(), body.bottom(), "Pages scroll directly above the composer bar");
                assertTrue(body.width() > 0 && body.height() >= 100, "Even the smallest window keeps a usable body");
                assertTrue(composer.y() > footer.y() && composer.bottom() < footer.bottom());
                assertEquals(body.x(), composer.x());
                assertEquals(body.right(), composer.right());
            }
        }
    }

    @Test void favoritesFillAcrossBeforeWrappingEvenInTheSmallestFrame() {
        for (int windowWidth : List.of(320, 400, 579, 580, 720, 1100)) {
            for (int windowHeight : List.of(220, 480)) {
                var body = ChatToolsLayout.frame(12, 10, windowWidth, windowHeight).content();
                var tiles = ChatToolsLayout.favoriteTiles(body.x(), body.y(), body.width(), 25);
                assertEquals(25, tiles.size());
                assertEquals(tiles.get(0).y(), tiles.get(1).y(), "Favourites must run horizontally, not become one vertical list");
                assertTrue(tiles.get(1).x() > tiles.get(0).right());
                for (int i = 0; i < tiles.size(); i++) {
                    var tile = tiles.get(i);
                    assertTrue(tile.x() >= body.x() && tile.right() <= body.right());
                    assertTrue(tile.width() >= 128 && tile.height() > 0);
                    for (int j = 0; j < i; j++) {
                        var other = tiles.get(j);
                        assertTrue(tile.x() >= other.right() || tile.right() <= other.x()
                            || tile.y() >= other.bottom() || tile.bottom() <= other.y(), "Favourite hit areas must not overlap");
                    }
                }
                assertTrue(tiles.getLast().y() > tiles.getFirst().y());
            }
        }
    }

    @Test void singleFavoritesKeepShortcutSizeAndEmptyListsUseNoSpace() {
        assertTrue(ChatToolsLayout.favoriteTiles(0, 0, 288, 0).isEmpty());
        assertTrue(ChatToolsLayout.favoriteTiles(0, 0, 0, 4).isEmpty());
        var tile = ChatToolsLayout.favoriteTiles(20, 30, 560, 1).getFirst();
        assertEquals(20, tile.x());
        assertEquals(30, tile.y());
        assertTrue(tile.width() <= 180, "One favourite should look like a shortcut rather than stretch into a full-width row");
        var tiny = ChatToolsLayout.favoriteTiles(0, 0, 100, 3);
        assertTrue(tiny.stream().allMatch(t -> t.width() <= 100 && t.right() <= 100));
    }
}
