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
                if (body.width() >= 266) {
                    assertEquals(tiles.get(0).y(), tiles.get(1).y(), "Favourites fill available columns before wrapping");
                    assertTrue(tiles.get(1).x() > tiles.get(0).right());
                } else assertTrue(tiles.get(1).y() > tiles.get(0).y());
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
    @Test void sidebarKeepsContentAndComposerSeparateAtEverySize() {
        for (int width : List.of(320, 480, 620, 800, 1100)) {
            for (int height : List.of(220, 360, 480, 800)) {
                var frame = ChatToolsLayout.frame(43, 27, width, height);
                assertTrue(frame.sidebar().width() >= 40);
                assertEquals(frame.header().bottom(), frame.sidebar().y());
                assertTrue(frame.content().x() > frame.sidebar().right());
                assertTrue(frame.composer().x() > frame.sidebar().right());
                assertTrue(frame.content().width() >= 230);
                assertTrue(frame.sidebar().bottom() <= 27 + height);
            }
        }
    }

    @Test void theSendQueueTrayGrowsTheFooterWithoutCrowdingOutTheBody() {
        for (int height : List.of(220, 360, 800)) {
            var plain = ChatToolsLayout.frame(43, 27, 720, height);
            var queued = ChatToolsLayout.frame(43, 27, 720, height, 90);
            assertEquals(plain.footer().bottom(), queued.footer().bottom());
            assertTrue(queued.footer().height() > plain.footer().height(), "The tray sits inside the footer");
            assertEquals(queued.footer().y(), queued.content().bottom());
            assertEquals(plain.composer().bottom(), queued.composer().bottom(), "The composer stays on the bottom edge");
            assertTrue(queued.tray().bottom() <= queued.composer().y() && queued.tray().y() >= queued.footer().y());
            assertTrue(queued.content().height() >= 100, "Even with a full tray the body stays usable");
        }
        assertEquals(0, ChatToolsLayout.frame(0, 0, 720, 480).tray().height());
    }

    @Test void shortSidebarNavigationCanScrollToTheLastDestination() {
        var frame = ChatToolsLayout.frame(0, 0, 320, 220);
        int max = ChatToolsLayout.navigationScrollMax(frame, 8);
        assertTrue(max > 0);
        var last = ChatToolsLayout.navigationItem(frame, 7, max);
        assertTrue(last.y() >= frame.sidebar().y());
        assertTrue(last.bottom() <= frame.sidebar().bottom() - 24);
    }
}
