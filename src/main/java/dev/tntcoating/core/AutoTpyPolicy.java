package dev.tntcoating.core;

import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;

/** Admission is separate from priority: friends only bypass the whitelist when explicitly enabled. */
public record AutoTpyPolicy(Set<String> whitelist, Set<String> blocklist, boolean whitelistOnly,
                            boolean allowFriends, boolean acceptToMe, boolean acceptToThem,
                            Set<String> roleMembers, boolean filterByRole) {
    public AutoTpyPolicy {
        whitelist = keys(whitelist);
        blocklist = keys(blocklist);
        roleMembers = keys(roleMembers);
    }
    public AutoTpyPolicy(Set<String> whitelist, Set<String> blocklist, boolean whitelistOnly,
                         boolean allowFriends, boolean acceptToMe, boolean acceptToThem) {
        this(whitelist, blocklist, whitelistOnly, allowFriends, acceptToMe, acceptToThem, Set.of(), false);
    }
    public static String key(String name) { return name.strip().toLowerCase(Locale.ROOT); }
    public static Set<String> keys(Set<String> names) {
        return names.stream().map(AutoTpyPolicy::key).filter(ChatMessages::validName).collect(Collectors.toUnmodifiableSet());
    }
    public boolean allows(String player, boolean friend, boolean toThem) {
        if (player == null || !ChatMessages.validName(player.strip())) return false;
        String key = key(player);
        return !blocklist.contains(key) && (toThem ? acceptToThem : acceptToMe)
            && (filterByRole ? roleMembers.contains(key) : !whitelistOnly || whitelist.contains(key) || allowFriends && friend);
    }
}
