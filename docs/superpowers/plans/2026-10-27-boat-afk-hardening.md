# Boat AFK hardening

Scope authorized by the user: fix all gaps identified against Highway Fly. Continue inline in the existing dirty checkout; preserve unrelated work.

- [x] Reproduce backtracking/sideways movement hiding a stall, then track only a new best projection along configured yaw.
- [x] Introduce a bounded recovery/world wait guard. Health/lava/death run before any world or recovery early return; sample authoritative health/death packets and ignore stale connection updates.
- [x] Test reachable food selection against Meteor AutoEat's rules, including full hotbar, offhand, blacklist, container/cursor safety. Detect sustained eating demand without consumption/recovery; logout rather than silently carrying on.
- [x] Require a valid native durable recovery marker for automatic profile restart; retain its heading and server identity. Explicit new trips require the driver's seat. Disconnect with recovery off ends the trip; reconnect remains server-bound and stable connections reset backoff.
- [x] Verify safety terminal ordering, profile save/load, waiting-world protection and helper ownership. Update docs, run full build/tests and obtain an independent review.

Defaults chosen: food failure 15 seconds; unexpected-world wait 30 seconds; total recovery/reconnect wait 300 seconds, all configurable with zero disabling. Recovery never resets its deadline on stage changes or fresh reconnect attempts. No routing/walking fallback for boat travel: bounded recovery or terminal logout preserves the fixed-heading scope.

## Verification ledger

Reproduced oscillation and backward/sideways false progress in failing tests before changing the detector. Added food access/failure, connection health ordering, recovery ownership, saved marker/server/heading and bounded-wait regressions. Independent review found unsafe managed eating, recovery without its owner, marker loss during controller transfer, equivalent north headings, a packet rebinding race and overlapping dismount/world timers. Fixed each; final review reports no remaining important issue in reviewed changes.

Recovery completion now requires five ready seconds and new forward progress; repeated reconnects and brief returns do not reset the deadline. Health callbacks only publish samples; the game thread binds connection identity. Unsafe inventory access suppresses managed AutoEat while keeping its lease and food-failure deadline. Native markers cannot authorize recovery without the safety controller.

Final verification: Gradle build successful, 1,519 Java tests with zero failures/errors/skips (67 boat-related tests), all 13 dashboard JavaScript checks pass, documentation validator passes, git diff whitespace check passes. JAR contains the four new safety policies; source distribution rebuilt. No Minecraft/server or extended AFK soak test performed. No deployment, installation or commits requested.
