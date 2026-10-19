package dev.tntcoating.core;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** One-pass replacement for "stable sort, then take the first": only the first element is placed. */
public final class NearestFirst {
    private NearestFirst() {}

    /** Moves the first minimum, as a stable sort would order it, to index 0; other elements may move. */
    public static <T> void moveMinimumToFront(List<T> list, Comparator<? super T> order) {
        int best = 0;
        for (int i = 1; i < list.size(); i++) {
            // Strict comparison keeps the earliest of equal elements, matching a stable sort.
            if (order.compare(list.get(i), list.get(best)) < 0) best = i;
        }
        if (best != 0) Collections.swap(list, 0, best);
    }
}
