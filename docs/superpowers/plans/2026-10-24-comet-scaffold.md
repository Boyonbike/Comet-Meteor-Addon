# Comet Scaffold implementation

Approved specification: the user's Wide and Narrow implementation request of 2026-10-24.

## Constraints

- Implement 26.1.2 only, Meteor build 42, existing Java 25 toolchain. No dependency upgrades.
- Independent `comet-scaffold`, exactly Wide and Narrow presets, Narrow initially. Keep upgraded Meteor Scaffold; last enabled wins immediately.
- Wide: full contact-face-reachable transverse strip, no nine-block width cap, stable heading, confirmed frontier, hole repair, fair sides, reachable old rows, no movement hold for outer coverage.
- Narrow: swept footpath only, sprint/sprint-jump prediction and earliest support deadlines, latency-adaptive lookahead capped at one second, confirmed-only anchors and edge protection. Hold floor level through ordinary jumps.
- Shared optional height/down/layer, tower/autojump/sneak, inventory switching/filter, air-place/container and preview features. No specialised bridge techniques, no extra standalone rotation packets.
- Recheck context/generation, material, reach, OUTLINE raycast and BlockPlaceContext destination at dispatch; use collision geometry for support/edge protection.
- Own ordinary movement rotation slot yields to Meteor queue/holds, restores view, checks actual observed facing. Hooks fail closed when incompatible.
- Preserve sent attempts/quarantine while inactive and through preset/module handoffs; authoritative updates alone resolve prediction. Bound outstanding attempts to 6 and tracked state to 256, never evict unconfirmed quarantine to make support trusted.
- Pause BlockCoat/SmartSponge without toggling; settle sent work, cancel deferred work, fresh resume without catch-up.
- Monotonic packet window includes pre-enable connection traffic. Default 150 observed packets/second, at least 50ms and at most one interaction/client tick. Atomic actual-versus-reserved reconciliation.
- Confirmation timeout initially 1s then clamp(3*EMA+400ms,1s,10s), EMA 0.8/0.2. Ordinary retry 150ms exponential to 2s, urgent 50ms then100ms, correction pause500ms, watch10s. These are starting values, not verified 6b6t limits.
- 128 rays/client tick including verification, complete bounded reach envelope with persistent fair scanning.
- Async Summary(default)/Off/Trace JSONL, 4MiB/session in meteor-client/comet/comet-scaffold-logs, metadata/settings/preset changes, 5s summaries/final, bounded 2s event ring, packet categories/attempt events/coverage/deadline/latency/corrections/drops/failures.
- Preserve GPL distribution/source packaging. Original implementation of functional ideas; attribute any actual adaptations and verify grants. No Trouser-specific source copying.

## Task 1: Pure geometry and preset planner

Implement core/scaffold/ScaffoldPlanner plus meaningful JUnit tests. Public nested records Cell, Snapshot, Candidate, Plan and Preset enum form the Minecraft adapter boundary. Wide full reach envelope/fairness/history/holes and Narrow swept deadline footprint must be deterministic and independently testable. Publish exact API early.

## Task 2: Time, packet reservations and placement lifecycle

Implement pure core/scaffold runtime classes and tests. Packed-long positions avoid coupling to planner. Monotonic clock injection, atomic window/credit reconciliation, actual-send state, quarantine, retries/confirmation/watch/corrections, bounded state, observable events. Publish exact API early.

## Task 3: Coordination and ordinary movement rotation adapter

Implement coordinator, compatibility-checked rotation bridge, authoritative feedback and builder suspension. Root module contract: prepare/sent/feedback methods agreed in brief; independent module remains outside old Scaffold upgrade compatibility gate. Meaningful coordination/ordering tests, no standalone rotation packet creation.

## Task 4: Minecraft module, resolver and diagnostics

Connect planners/runtime to own registered module, shared/per-preset settings, shape/context verified interaction, inventory, movement policy, bounded preview, diagnostics. Coordinate actual Sent callbacks and asynchronous authoritative state application.

## Task 5: Review, documentation, packaging and verification

Review each component and integration, fix findings with regression tests. Update README/changelog/notices/build metadata. Run full test/build, inspect JAR/source archive. Controlled game/server validation is reported separately from automated evidence; do not claim gameplay verification without an actual client run.
