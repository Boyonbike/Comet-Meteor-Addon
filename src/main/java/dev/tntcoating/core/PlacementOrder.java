package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Retry fairness takes precedence over geometric priority so failures cannot starve coverage. */
public final class PlacementOrder {
    private PlacementOrder() {}
    /** Distance controls eligible retry order too; backoff is handled before sending. */
    public static <T> List<T> nearFirst(Collection<T> candidates, Comparator<T> priority) {
        List<T> ordered = new ArrayList<>(candidates);
        ordered.sort(priority);
        return ordered;
    }
    public static <T> List<T> order(Collection<T> candidates, Map<T, Integer> lastAttempt, Comparator<T> priority) {
        List<T> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.<T>comparingInt(pos -> lastAttempt.getOrDefault(pos, Integer.MIN_VALUE)).thenComparing(priority));
        return ordered;
    }

}
