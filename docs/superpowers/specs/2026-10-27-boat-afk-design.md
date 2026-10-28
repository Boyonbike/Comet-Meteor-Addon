# Fixed-heading AFK Boat Fly

User-approved scope: continuous fixed-heading AFK travel, integrating with needed modules like Highway Fly. Retain basic boat movement and phasing; replace the copied multipurpose controls.

Boat Fly owns a configurable Minecraft yaw, starts automatic travel when enabled, and offers pause/start for manual positioning. It manages AutoWalk, AutoEat, AutoSetHome and AutoLobby, restores borrowed preferences, exposes helper settings screens, and suspends conflicting Angle Calculator steering. Recovery temporarily owns direction and movement. No destination routing or automatic boat acquisition.

Remove hash experiments, adjustable experimental air travel, quick dive, passenger dive, cruise, surface finding, return-height and copied speedometer UI. Retain proven substep movement, collision/phasing, correction fallback, chunk/lava guards and dismount controls. Preserve upstream attribution.

Safety defaults: logout at zero carried eGaps, health at or below 8 points (real health), actual lava immersion, 30 seconds without the driver's seat, or 60 seconds without meaningful progress. All limits configurable; timeout zero disables that check. Recovery excludes travel timeouts and inventory checks. Logout ends recovery and disables AutoReconnect before disconnecting. Dashboard and webhook show heading, supply counts and safety outcomes without Angle Calculator destination controls.

Implementation choices belong to the implementer. Scope is Minecraft 26.1.2, where native Boat Fly exists. Preserve existing uncommitted changes and other Minecraft versions.
