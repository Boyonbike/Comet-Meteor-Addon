package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class PlacementOrder {
    private PlacementOrder() {}
    /** Distance controls eligible retry order too; backoff is handled before sending. */
    public static <T> List<T> nearFirst(Collection<T> candidates, Comparator<T> priority) {
        List<T> ordered = new ArrayList<>(candidates);
        ordered.sort(priority);
        return ordered;
    }
}
