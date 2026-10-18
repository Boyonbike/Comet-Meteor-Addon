package dev.tntcoating.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Item identity is supplied by the caller; components such as damage and enchantments are ignored. */
public final class ShulkerIconSelector {
    private ShulkerIconSelector() {}

    public record Entry<T>(T item, int count) {}

    public enum Style { Normal, SolidColour }

    public record Icon<T>(T item, Style style, int colour) {}

    public record Rule<T>(String name, boolean enabled, List<T> required, T icon, Style style, int colour) {
        public Rule(String name, boolean enabled, List<T> required, T icon) {
            this(name, enabled, required, icon, Style.Normal, 0xffffffff);
        }

        public Rule {
            required = List.copyOf(required);
            if (style == null) style = Style.Normal;
        }
    }

    /** First matching combo wins. Otherwise count quantities, breaking ties by the first occupied slot. */
    public static <T> T select(Iterable<Entry<T>> contents, List<Rule<T>> rules) {
        Icon<T> icon = selectIcon(contents, rules);
        return icon == null ? null : icon.item();
    }

    public static <T> Icon<T> selectIcon(Iterable<Entry<T>> contents, List<Rule<T>> rules) {
        Map<T, Long> counts = new LinkedHashMap<>();
        for (Entry<T> entry : contents) {
            if (entry.item() != null && entry.count() > 0) counts.merge(entry.item(), (long) entry.count(), Long::sum);
        }
        if (counts.isEmpty()) return null;
        for (Rule<T> rule : rules) {
            if (rule.enabled() && rule.icon() != null && !rule.required().isEmpty()
                && rule.required().stream().allMatch(counts::containsKey)) return new Icon<>(rule.icon(), rule.style(), rule.colour());
        }
        T best = null;
        long largest = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > largest) {
                best = entry.getKey();
                largest = entry.getValue();
            }
        }
        return new Icon<>(best, Style.Normal, 0xffffffff);
    }
}
