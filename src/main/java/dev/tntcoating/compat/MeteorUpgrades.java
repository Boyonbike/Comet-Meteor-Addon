package dev.tntcoating.compat;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of Comet's upgrades to Meteor modules were skipped because this Meteor build no longer matches them.
 * Filled by {@link CometMixinPlugin} before any target class loads; read by the addon at runtime.
 */
public final class MeteorUpgrades {
    private static final Map<String, String> DISABLED = new ConcurrentHashMap<>();

    private MeteorUpgrades() {}

    /** True unless the named mixin (simple class name, such as "ScaffoldMixin") was skipped. */
    public static boolean applied(String mixin) { return !DISABLED.containsKey(mixin); }

    /** Skipped mixins and why, in name order. */
    public static Map<String, String> disabled() { return new TreeMap<>(DISABLED); }

    static void disable(String mixin, String reason) { DISABLED.put(mixin, reason); }
}
