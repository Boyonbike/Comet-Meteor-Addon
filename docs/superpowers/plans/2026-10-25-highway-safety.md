# Highway Fly safety implementation plan

**Goal:** Improve 26.1.2 Highway Fly lava recovery, safety logouts and Discord notifications.

**Architecture:** Keep Highway Fly's existing flight/Baritone handoff and safety logout path. Add small tested helpers for lava sweeps, cumulative damage, forward progress and a monotonic 30-minute status timer. Preserve the existing working tree and other Minecraft versions.

**Tech stack:** Java 25, Meteor/Fabric, optional Baritone, JUnit 5, Gradle.

## Requirements and implementation choices

- Lava ahead stops forward flight and hands recovery to Baritone; unavailable recovery logs out.
- Lava is unsafe in every flight probe, including non-solid fluid blocks and descending moves.
- Current lava immersion causes immediate safety logout during flight or lobby recovery.
- Damage limit is configurable, default 8 health points (4 hearts), cumulative for a trip; healing does not erase damage. Zero disables this limit. Sample actual health, excluding absorption decay.
- A configurable forward-progress timeout (default 30 seconds) covers blocked, takeoff and walking states. First timeout attempts Baritone; no progress through recovery logs out. Walking-target retries cannot restart the watchdog.
- Every safety logout clears saved recovery and disables AutoReconnect before disconnecting.
- Lava handoff and each safety logout produce a webhook when configured/enabled. Death sends one alert and ends movement. Status is sent every 30 minutes, with position, state, health and route distance; no catch-up bursts.
- No real webhook requests during tests.

## Additional user requirements

- Preserve flying over lava when early lava handoff is disabled. Baritone must still avoid lava during recovery.
- Walking endpoints use a stable standard axis/diagonal through the configured destination, advancing the configured distance along that line; no sideways drift from local clearance scans or nearby-goal tolerance.

## Tasks

- [x] Add damage/progress helper with tests for exact damage limit, healing, new trips, forward movement, backwards movement and oscillation.
- [x] Add monotonic status timer and lava/status event payload tests.
- [x] Add lava sweep helper with tests for diagonal, vertical, non-solid lava and unloaded chunks.
- [x] Integrate helpers with flight, walking recovery, death handling and existing logout lifecycle.
- [x] Update Highway Fly documentation/changelog with new settings and behavior.
- [x] Run full tests/build and independent integration review; report gameplay verification limits.

## Review focus

- Recovery paths and target retries must not reset damage or stalled-walking budgets.
- Death must be handled before ordinary flight and safety actions, including instant respawn evidence.
- Lava checks include full movement/standing body and avoid reading unloaded chunks.
- Logout must prevent both Meteor and Highway Fly reconnect/recovery.
- Status timers must not emit every tick or burst after pauses.

## Verification record

- Independent review found tick-only damage sampling, unrelated-server recovery checks, the old fixed watchdog and a login/health receipt ordering race; all were corrected.
- Authoritative health samples are tagged at login-packet receipt and drained in order, preserving initial connection health through GameJoined and counting damage followed by healing before a client tick.
- Root verification: Java 25 `./gradlew test build`; final test count and artifact metadata checked before delivery.
- Live server movement and real Discord delivery remain unverified.

## Gameplay feedback correction: smart lava routing

- User reported predictive logout during a legitimate Baritone lava detour; only actual lava immersion may trigger a lava logout.
- Flight gets its planner/detour attempt before lava handoff, using actual fluid bounds. An elevated path releases only when returning to cruise height is safe.
- Active Baritone controls stepping, intermediate route heights and movement; flight predictions cannot interrupt it.
- Walking idle detection uses monotonic movement indices within a stable Baritone path, rather than destination distance or an unconditional 30-second route deadline. Path replacement alone does not count as progress.
- Correct stable highway-line endpoints and exact block goals remain in use.
- Verification covers fluid surfaces, safe overflight, altitude limits, loaded chunks, safe descent and walking progress; live gameplay remains to be checked.
