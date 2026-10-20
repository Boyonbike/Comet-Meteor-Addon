package dev.tntcoating.compat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of Comet's upgrades to Meteor modules were skipped because this Meteor build no longer matches them.
 * Filled by {@link CometMixinPlugin} before any target class loads; read by the addon at runtime.
 */
public final class MeteorUpgrades {
    /** Mixins that only work together: if one cannot apply, none of them is applied. */
    private static final List<List<String>> GROUPS = List.of();
    private static final Map<String, String> DISABLED = new ConcurrentHashMap<>();

    private MeteorUpgrades() {}

    /** True unless the named mixin (simple class name, such as "ScaffoldMixin") was skipped. */
    public static boolean applied(String mixin) { return !DISABLED.containsKey(mixin); }

    /** Skipped mixins and why, in name order. */
    public static Map<String, String> disabled() { return new TreeMap<>(DISABLED); }

    static List<String> group(String mixin) {
        for (List<String> group : GROUPS) if (group.contains(mixin)) return group;
        return List.of(mixin);
    }

    static void disable(String mixin, String reason) { DISABLED.put(mixin, reason); }
}
