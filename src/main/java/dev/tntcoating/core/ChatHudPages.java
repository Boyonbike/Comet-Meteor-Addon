package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;

/** Packs a single HUD row, keeping every choice reachable even at small GUI widths. */
public final class ChatHudPages {
    public record Item(int index, int width) {}
    private ChatHudPages() {}

    public static List<List<Item>> pack(List<Integer> widths, int available) {
        int limit = Math.max(1, available);
        List<List<Item>> pages = new ArrayList<>();
        List<Item> page = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < widths.size(); i++) {
            int width = Math.min(limit, Math.max(1, widths.get(i)));
            if (!page.isEmpty() && used + 2 + width > limit) {
                pages.add(List.copyOf(page));
                page.clear();
                used = 0;
            }
            page.add(new Item(i, width));
            used += (page.size() > 1 ? 2 : 0) + width;
        }
        if (!page.isEmpty()) pages.add(List.copyOf(page));
        return pages.isEmpty() ? List.of(List.of()) : List.copyOf(pages);
    }
}
