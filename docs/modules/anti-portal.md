# Anti Portal

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **anti-portal**.

When **Anti Portal** is enabled independently or requested by a trip, it rewrites outgoing movement packets that would enter a protected portal to the last position the server saw outside it. **nether-portals** defaults on, **end-portals** defaults off, and **notify** reports the first blocked entry. Protection must be running before the server first sees you inside; passengers and vehicles are not covered. Highway Fly’s **portal-mode** setting still chooses whether its flight planner avoids, passes or stops at a Nether portal; **Pass** does not disable packet protection.

For a walking recovery route through a Nether portal, Highway Fly pauses Baritone, checks a straight perpendicular crossing with continuous floor support, and resumes the same walking goal after clearing the portal. Flight **Pass** uses the same committed crossing without changing waypoints inside it. A blocked route, correction during the crossing or three-second crossing timeout ends the unattended trip with a deliberate safety logout. A portal entry packet is cancelled even when no safe anchor is available.

Protection is suspended throughout lobby recovery so the player can enter its recovery portal. It restarts when flight resumes in the original dimension. Live portal behavior still needs gameplay verification.

Terrain collision queries are cached only within one tick and world-update revision, with a fixed entry limit; ending a trip releases that cache. Arrival uses actual horizontal destination distance, and portal/lava lookahead checks the swept flight body rather than every block in a diagonal bounding rectangle.

## Independent and trip-managed activation

Enable this helper independently in Comet or allow Highway Fly to request it through Integrations. Stopping Highway Fly releases its request and preserves independent activation. **Keep enabled independently** retains a trip-managed helper after the trip; explicitly disabling one also disables its Highway Fly integration. See [Highway Fly](highway-fly.md).
