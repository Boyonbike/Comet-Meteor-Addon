# CrystalCombat vs Meteor CrystalAura: Phase 3 correctness parity

This table compares every Meteor CrystalAura setting that can change what CrystalCombat sends, with
the Phase 3 "verified direct aura baseline". It is a scope document: every row is either an
equivalent mechanism, a deliberate difference with a reason, or a listed gap with the phase that
owns it. There are no silent correctness differences.

Phase 4.2 adds one opt-in row (`predict-movement` / `motion-prediction`): the default is still the
Phase 3 current-box model, and the experimental tactic has its own one-directional bounds documented
below; it is not promoted until Phase 9 evidence.

## References

- Meteor build 35 source: `meteordevelopment/meteorclient/systems/modules/combat/CrystalAura.java`
  (1391 lines), inspected from the pinned build-35 sources artifact (Meteor `26.2-20261009.074120-35`).
- Comet implementation: `DirectPlanner`, `DirectSequence`, `CombatEngine`, `DirectSettings`,
  `DirectWorldReader`, `CombatRuntime.dispatch`, `CombatRiskBudget`, `CrystalLedger`,
  `MotionPrediction`, `CombatMotionModel`.
- Plan: `docs/superpowers/plans/2026-10-29-crystal-combat-v2.1.md`, Phase 1.6, Phase 3, Phase 4.2
  and the Safety model.
- Meteor settings were read from source, not from memory. Meteor defaults are quoted as writen.

## Status legend

| Status | Meaning |
| --- | --- |
| Equivalent | Same decision rule and same default behaviour; implementation differs only in data source. |
| Deliberate difference | Behaviour differs on purpose; the reason is given. |
| Gap (planned) | Not implemented in Phase 3; the owning phase is named in the gap register. |
| Gap (accepted) | Not implemented and intentionally out of scope; the reason is given. |

## General

| Meteor setting | Meteor default | Comet Phase 3 | Status | Notes |
| --- | --- | --- | --- | --- |
| `target-range` | 10 | `target-range` 6 | Deliberate difference | Phase 3 default is 6 (plan defaults table); the candidate filter is the same (players only, not self/friends/dead/creative/spectator) and is configurable. |
| `predict-movement` | false | `motion-prediction` false by default (Phase 4.2) | Deliberate difference (opt-in, experimental) | With the tactic off the target box is the current box, exactly the Phase 3 row and Meteor's default. When on, the target's expected value is the uniform mean over sampled reachable positions, but the guaranteed lower bound is **0.0**: `CombatMotionModel` exposes a hull, not an exhaustive reachable set, and an interior reachable point can be less exposed than every corner, so no sampled minimum is a proven bound (deliberate deviation from the plan wording; a zero lower also keeps the lethal override ineligible from prediction). Self/friend upper bounds take the maximum of the baseline and the whole-hull worst case within one tick (including vertical movement): the true closest point of the translated body box to the crystal at full exposure (cover ignored) pushed through the weakest mitigation envelope. Unknown/low-confidence motion keeps the exact baseline bounds and only lowers ranking confidence. A mid-session change aborts the in-flight action generation and sends nothing, then installs the new planner/cache on the existing runtime stack immediately; hazards, sent risk and the ledger are preserved. The change does not restart the client. Not promoted until Phase 9 evidence. |
| `min-damage` | 6 | `min-target-damage` 4 | Deliberate difference | Same policy role (minimum expected damage). 4 is the plan default; the safety model then adds reservation-level bounds that Meteor does not have. |
| `max-damage` | 6 | `max-self-damage` 6 | Equivalent | Same value and role (self upper bound). |
| `anti-suicide` | true | always enforced; `anti-suicide` module gate | Equivalent (stricter) | Comet always requires self upper bound <= `max-self-damage` and remaining health margin > `remaining-health-margin`; there is no "off" switch inside safety. The module refuses to enable when anti-suicide is disabled, matching the intent. |
| `ignore-nakeds` | false | none | Gap (accepted) | No naked-player filter in Phase 3. At the default `false` behaviour is identical; enabling this Meteor option is not part of the DirectOnly baseline. |
| `rotate` | true | rotation always requested through the runtime | Deliberate difference | No user-facing rotate toggle in Phase 3. `CombatRuntime.dispatch` requests a rotation when not aligned and refuses to send until `rotationSatisfied`; the planner never sends without it. |
| `yaw-steps-mode` / `yaw-steps` | Break / 180 | none (delegates to Meteor Rotations; dispatch rejects unsatisfied rotation) | Deliberate difference | Comet does not emulate partial per-tick yaw steps. It uses Meteor's rotation service inside the single dispatch path and fails closed (`ROTATION_NOT_READY`) instead of rotating slowly. |
| `entities` | players, warden, wither | players only | Deliberate difference | Plan 3.1 targets players. Non-player targets (warden/wither) are not enabled in this phase. |

## Switch

| Meteor setting | Meteor default | Comet Phase 3 | Status | Notes |
| --- | --- | --- | --- | --- |
| `auto-switch` | Normal | hotbar-only switch via `CombatInventoryAdapter` | Deliberate difference | Comet never touches the offhand (reserved for totems in the module defaults) and never sends container clicks. Switching is done by selecting a hotbar slot through the runtime, and every send validates the selected item immediately before the causative packet. |
| `switch-delay` | 0 | `switchDelayTicks` 0 | Equivalent | Planner skips a break whose Weakness tool switch is younger than the delay. |
| `no-gap-switch` | true | none | Deliberate difference | Comet pauses while eating/drinking instead of checking the held item, and does not switch away from a held gapple while merely holding it. The difference is in what stops work, not in packet correctness. |
| `no-bow-switch` | true | none | Deliberate difference | Same rationale as `no-gap-switch`: no held-item inspection; active use is only paused for food/potions (see pause rows). |
| `anti-weakness` | true | Weakness tool rule in `DirectPlanner` | Equivalent | Uses Meteor's own `DamageUtils.getAttackDamage(attacker, target, stack) > 0` for tool validity; skips when no hotbar tool works and no main-hand tool works; honours `switch-delay`; records the tool switch tick. |

## Place

| Meteor setting | Meteor default | Comet Phase 3 | Status | Notes |
| --- | --- | --- | --- | --- |
| `place` | true | placement is part of DirectOnly; module enable/disable is the toggle | Equivalent | The module is the user-facing switch; there is no second "place" toggle yet. |
| `place-delay` | 0 | at most 1 placement per engine tick | Deliberate difference | Conservative cap from plan 3.6; no sub-tick delay chain. |
| `place-range` | 4.5 | `place-range` 4.5 | Equivalent | Reach is measured to the clicked face point and revalidated inside `dispatch`. |
| `walls-range` | 4.5 | none for placement (`walls-range` 3 applies to breaks) | Deliberate difference (stricter) | Comet requires the placement face to be visible and the base cell reachable straight-line; it does not place crystals through walls. This is a safety/consistency trade-off, not an accidental omission. |
| `1.12-placement` | false | strict vanilla rule | Equivalent | First cell above the base must be air; the 1x2x1 entity volume must be free; crystals count as present until removal is observed, even after an attack. |
| optional two-air profile | n/a | `twoAirProfile` false default | Deliberate difference | Equivalent to `1.12-placement` when enabled; a deliberate stricter profile, disabled by default. |
| `support` | Disabled | `supportEnabled` false default; one support block | Deliberate difference | When enabled, only the single obsidian/base support block is proposed (never a chain), and it is only used when no direct obsidian/bedrock base candidate passes. Meteor's `Fast` recursion is Phase 5 support depth work. |
| `support-delay` | 1 | support then crystal on later passes, no configurable delay | Deliberate difference | The support is verified as a block before the crystal placement is planned; there is no recursive same-tick crystal send. |

## Face Place

| Meteor setting | Meteor default | Comet Phase 3 | Status | Notes |
| --- | --- | --- | --- | --- |
| `face-place` | true | `faceplaceEnabled` true | Equivalent | Same relaxation trigger. |
| `face-place-health` | 8 | `faceplaceHealth` 8 | Equivalent | Target effective health <= threshold. |
| `face-place-durability` | 2 | `faceplaceArmourFraction` 10% | Deliberate difference | Plan 3.3 uses 10% (any single armour piece at or below the fraction). The mechanism is armour pressure, and it only ever relaxes the minimum target damage. |
| `face-place-missing-armor` | false | not implemented | Gap (accepted) | At the default `false` there is no behavioural difference. Enabling it is not part of the baseline. |
| `force-face-place` | keybind none | not implemented | Gap (planned) | Phase 8 UI/keybind surface. Faceplace still triggers automatically by health/armour pressure. |

## Break

| Meteor setting | Meteor default | Comet Phase 3 | Status | Notes |
| --- | --- | --- | --- | --- |
| `break` | true | breaks are part of DirectOnly | Equivalent | Module enable/disable is the user-facing switch. |
| `break-delay` | 0 | at most 1 verified break per engine tick | Deliberate difference | Conservative cap; no sub-tick delay chain. |
| `smart-delay` | false | not implemented | Gap (accepted) | At the default `false` parity. No `hurtTime`-style skip; the attempt cap and one-break-per-tick cap bound the sends. |
| `break-range` | 4.5 | `attack-range` 3.0 + real entity reach | Deliberate difference | Plan defaults 3.0; the range check uses the real entity interaction range and the ledger-observed crystal box, then is revalidated inside dispatch. |
| `walls-range` (Break) | 4.5 | `walls-range` 3.0 | Deliberate difference | Same wall/visibility fallback shape as Meteor: only when the straight eye ray is blocked by something other than the crystal cell does the walls range apply. Default is 3.0. |
| `only-own` | false | `own-only` false | Equivalent | "Own" is stricter than Meteor's `placedCrystals`: it means the crystal is bound to a spawn hazard this module reserved and marked sent (ledger binding), not merely observed after a placement. |
| `break-attempts` | 2 | `max-attempts` 2 | Equivalent | Per-crystal attempt cap in the planner and the engine's per-entity attempt map. |
| `ticks-existed` | 0 | `min-age` 0 | Equivalent | Crystal age comes from the observed spawn tick. |
| `attack-frequency` | 25 | none (attempt cap + per-tick cap) | Deliberate difference | Comet does not throttle by a 25-tick clock; it bounds by verified attempts and the Conservative per-tick cap, and it retires risk on terminal evidence instead of assuming a hit. |
| `fast-break` | true | spawn attack in the same client-thread task as the observation | Equivalent | `CombatEngine.onSpawnObserved` binds the unique spawn and dispatches the attack immediately, guarded against the tick/event duplicate. |

## Pause

| Meteor setting | Meteor default | Comet Phase 3 | Status | Notes |
| --- | --- | --- | --- | --- |
| `pause-on-use` | Place | pause on eating/drinking (data-driven), plus open screen | Deliberate difference | Plan 3.5 names eating and drinking explicitly; generic "using item" (bow, shield, block use) is not a Phase 3 pause. Open screens and Baritone are additional guards. |
| `pause-on-mine` | None | `pause-when-mining` true | Deliberate difference | Plan 3.5 makes it configurable and defaults it on. |
| `pause-on-lag` | true | TPS < 10 or last server feedback older than 1 s | Equivalent (stricter) | Meteor pauses when `timeSinceLastTick >= 1.0 s`; Comet adds a TPS floor. Both are checked before any plan is made. |
| `pause-modules` | BedAura | `pause-on-baritone` true | Deliberate difference | Plan 3.5 names Baritone, not Meteor module lists. The reader exposes a guarded Baritone pathing check. |
| `pause-health` | 5 | `health-floor` 6 | Deliberate difference | Plan default 6; also applied to the effective health (health + absorption) so a low-health player cannot be talked into a send. |

## Hurt-aware timing (Phase 4.3)

Meteor CrystalAura has no setting that estimates a target's damage-immunity window; this is an opt-in
Comet tactic, not a parity toggle. Its pure arithmetic and engine deferral are tested, but the production
path has no landed-raw-hit observation yet: in a normal client the model stays unknown, so the tactic is
exactly the Phase 3 estimate with a zero guaranteed lower bound.

| Tactic | Default | Status | Notes |
| --- | --- | --- | --- |
| `hurt-sync` | false | Gap (accepted) | When fed a raw landed hit, `HurtSync` lowers the target expected/guaranteed estimate from `CombatHurtModel` and may wait for the window to expire when the 20-tick expected gain beats acting now. It never delays a lethal estimate, never raises a lower bound without evidence, and never changes a self/friend upper bound. The current client builds an unknown window because no packet carries a real raw damage amount; `CrystalCombatController.observeRawHit` is the seam and has no production caller. Do not claim a measured improvement until a raw-hit source and a Phase 9 ablation exist. |

The target estimate, including the policy minimum-damage check, uses the hurt-adjusted expected value when
the window is known; self/friend bounds continue to come from the unadjusted exposure path. Off is exactly
the Phase 3 estimate.

## Defence (Phase 4.6, opt-in Comet-only tactics)

Meteor CrystalAura has no defensive planner; these are deliberate additions, gated independently and off
by default. They use the same identity, range, item, visibility and safety checks as offence and can never
relax a self/friend ceiling.

| Tactic | Default | Status | Notes |
| --- | --- | --- | --- |
| `dangerous-crystal-break` | false | Deliberate difference | Breaks an observed foreign crystal within attack range when the whole-hull worst self detonation bound plus the engine's current outstanding self bound would breach the remaining margin. Detection uses `selfUpperWorst`/weakest-envelope pricing, never current mitigation, and only when the self lower health bound is known (`Capability.KNOWN`). The break is an ordinary `ATTACK_CRYSTAL` direct plan and reserves its own risk through the scheduler/risk budget; dispatch revalidates only the self/friend ceilings and margin (`allowsDefensive`) because a defensive break has no target-damage claim. Own/ledger-protected crystals are skipped: **this build has no trap-invalidation API**, so the plan's "unless the trap is first invalidated" clause is not implemented. |
| `anti-piston` | false | Deliberate difference | Detects a piston base within 2 blocks of self's feet/head cell, facing toward self, with a crystal or an empty crystal-ready cell in front. Front cell = `piston cell + facing`. Crystal in front is broken via the defensive break/risk path when safe; otherwise an obsidian `PLACE_BLOCKER` is placed in the front cell (sturdy face below, air/replaceable, entity-free 1x1x1, reach, visibility, obsidian item). A front cell equal to self's feet/head, an unsafe front-crystal break, or an occupied/unplaceable front cell drops the opportunity (it never blocks into the crystal). |
| Power-source mining | n/a | Gap (blocked) | Not attempted. Speedmine has no owner-scoped request/cancel contract, so `MINE_POWER` is never planned and no mining packet is sent. This mirrors the Phase 4.5 mining gap and must stay explicit until Speedmine exposes that contract. |

Defence runs before offence only for an imminent threat (arrival <= 4 ticks); a non-imminent opportunity
runs after offence and only when offence sends nothing that tick. A failed defensive reserve/dispatch falls
through to normal offence in the same tick, and no tick can send two actions. A
defensive break never creates a `DirectSequence`, never binds a spawn hazard and never touches the
ledger's placement bookkeeping; a `PLACE_BLOCKER` reserves zero exposure with no bound crystal and shares
the packet budget/lease accounting.

## Render

The Meteor Render group (`swing-mode`, `render-mode`, `render-place`, `place-render-time`,
`render-break`, `break-render-time`, `smoothness`, `height`, `render-time`, `shape-mode`, colours,
`render-damage-text`, `damage-text-scale`) is visual only. It cannot change a send decision and is
out of scope for this parity table; `swing-mode` is reproduced by the runtime's swing packet after
the causative packet, matching Meteor's default `Both`.

## Gaps register

Every non-equivalent row is listed here with an owner or an explicit acceptance reason.

| Gap | Owner | State |
| --- | --- | --- |
| Movement prediction | Phase 4.2 | Implemented as the opt-in `motion-prediction` tactic. Default `false` keeps the `predict-movement=false` parity above. Experimental: not promoted until Phase 9 evidence. When on it re-scores opportunity ranking, reports a guaranteed target lower bound of `0.0` (a hull cannot prove the least exposed interior point), and only raises self/friend upper bounds through a whole-hull closest-point worst case; low/unknown confidence keeps every safety bound at the baseline. |
| Hurt-aware timing raw-hit feed | Phase 4.3 / Phase 9 | Pure tactic, policy integration and a test seam (`observeRawHit`) exist; no packet carries a real landed raw damage amount, so the production model stays unknown in this build. Not promoted until a raw-hit source exists and a Phase 9 ablation is run; health/absorption drops must not be substituted for the raw amount. |
| Defence trap invalidation | Phase 4.6 (gap) | No engine API invalidates our own active trap; defensive break therefore skips own and ledger-protected crystals instead of breaking them. Recorded deviation from the plan's "unless first invalidated" clause. |
| Defensive power-source mining | Blocked (Speedmine) | Anti-piston never attempts `MINE_POWER`; Speedmine has no owner-scoped request/cancel contract. The break-or-block responses ship without it. |
| Defence paired ablation | Phase 9 | Both defensive tactics are independently gated and off by default; the paired ablation scenario with a prespecified improvement is not yet run (no live evidence claimed). |
| `ignore-nakeds` | Not planned | Accepted; default `false`. |
| Per-tick yaw step limiting | Not planned | Accepted; dispatch fails closed until Meteor Rotations reports alignment. |
| Held-gapple / held-bow switch guards | Not planned | Accepted; only active eating/drinking is paused. |
| `face-place-missing-armor` | Not planned | Accepted; default `false`. |
| `force-face-place` keybind | Phase 8 UI | Not started. |
| `smart-delay` | Not planned | Accepted; default `false`, attempt caps bound sends. |
| Placement through walls | Not planned | Accepted as a stricter rule: visible face required. |
| Support `Fast` recursion / support depth | Phase 5 | Not started; one support block in Phase 3. |
| Offhand auto-switch | Not planned | Accepted; offhand reserved for the module's totem behaviour. |
| Generic item-use pause (bow/shield/block) | Not planned | Accepted; plan 3.5 only names food/potion, screen, mining, Baritone. |
| Meteor module pause list (`pause-modules`) | Not planned | Replaced by the plan-mandated Baritone check. |

## Comet-only safety behaviour (not a Meteor setting)

These are additions required by the plan's Safety model; they do not contradict any Meteor row above:

- Every placement reserves a spawn hazard in `CrystalLedger` and a crystal risk in
  `CombatRiskBudget` before dispatch. A unique matching spawn binds both; an unmatched spawn leaves
  the hazard and risk in place and the cell blocked until resolved.
- A break shares the crystal's existing risk via `CombatScheduler.attach` when the crystal is bound
  to our hazard; foreign breaks reserve their own risk with the crystal key and position as the
  attribution anchor.
- Risk is retired only by attributed evidence: an explosion observed at the bound crystal within the
  tolerance, followed by a causally later coherent effective-health snapshot. Unattributed
  explosions (the open_target trace records one with payload entity id -1) leave the debt
  outstanding.
- The friend ceiling is a per-friend upper bound, not a single summed target estimate; a friend next
  to the target rejects the placement/break when that friend's bound exceeds `max-friend-damage`.
- Self and per-friend upper bounds always price the weakest mitigation envelope: an armour piece at
  or below the breakable-durability fraction is assumed broken and an expiring Resistance is assumed
  gone. Pricing the current mitigation would understate the bound; `DirectWorldReader` routes both
  bounds through `CombatDamageAdapter.upperBound(RawDamage, MitigationView)` and has no current-state
  upper-bound call site.
- Pauses never clear hazards or sent risk; they only stop new sends. Disable/death/respawn/config/
  dimension/connection close end the engine's action generation synchronously: the active placement
  sequence aborts, a spawn bound to a pre-boundary hazard can never be attacked afterwards, and the
  spawn hazard plus sent risk stay in the ledger/budget. Re-enable starts from IDLE, never from the
  pre-disable sequence.
- Motion prediction (Phase 4.2) is one-directional and evidence-gated: `MotionPrediction` only engages
  when `CombatMotionModel` reports `STRONG` confidence. The target's expected value is the uniform mean
  over sampled reachable positions, but its guaranteed lower bound is `0.0` because the model exposes a
  hull and cannot prove the least exposed interior point (the plan's corner minima were rejected as
  unsound). Self/friend upper bounds are the maximum of the baseline and the whole-hull worst case
  within one tick (including vertical escape): the true closest point of the translated body box to
  the crystal at full exposure, through the weakest mitigation envelope. Low confidence only reduces
  the opportunity ranking score (`expected * confidence`); it never changes a safety bound. Unknown
  motion, teleports/setbacks, WEAK/LOST confidence and the off tactic keep the Phase 3 baselines
  exactly, and a mid-session setting change sends nothing before it takes effect at the next stack
  build.

## Verification

The rows above are covered by:

- `DirectPlannerTest` — target selection/exclusion/deterministic order/stickiness, break and place
  filters, reach/walls, Weakness tool switch and delay, faceplace triggers and limits, support
  fallback, occupied volumes, same-snapshot break-vs-place.
- `CombatEngineTest` — place -> spawn -> break -> removal, rejected spawn no retry, tick/event
  duplicate suppression, pause/disable/death/respawn behaviour, caps, item-entity and occupied
  volumes, high self/friend bounds, health floor.
- `EngineHarnessTraceTest` — the open_target Phase 0 trace through the engine, the unattributed
  explosion, verified retirement, and an unmatched trace spawn that never retries the cell.
- `CombatSchedulerAttachTest` — shared-risk attack path and its refusals.
- `CombatRuntimeSupportDispatchTest` — `PLACE_SUPPORT` through the single dispatch path, faceplace
  policy revalidation, and the fact that faceplace never relaxes self bounds.
- `DirectWorldReaderTest` — self/friend upper bounds price the weakest envelope (breakable armour,
  expiring Resistance) and the production call sites cannot regress to current mitigation.
- `CrystalCombatModuleTest` / `CrystalCombatEngineModuleTest` — capability-first enable, gate
  ordering, direct-only dispatch, disable/death/respawn/config/dimension/connection-close silence,
  and re-enable never attacking a pre-boundary spawn.
- `MotionPredictionTest` / `MotionPredictionPlannerTest` — a `STRONG` hull replaces the target lower
  with `0.0` (including a regression where every corner is exposed but an interior reachable point is
  safe, so a corner minimum would have overclaimed), self/friend uppers use the whole-hull worst point
  including vertical movement and never fall, WEAK/LOST/unknown/teleported motion keeps the exact
  baseline, WEAK motion only lowers the opportunity score, the horizon comes from the settings (2 ticks
  Conservative, 3 accepted for an Anarchy config), and a lethal override cannot fire from prediction.
- `DirectWorldReaderTest` — the whole-hull bound uses the true closest point of the body box to the
  crystal (diagonal regression) at full exposure, not the minimum corner, and still routes through the
  weakest-envelope helper. `CombatDebugHostHeaderTest` — the debug-log header reports the tactic state
  and horizon.
- `MotionPredictionEngineTest` — end-to-end on the fake world/connection: off never samples motion,
  on-without-motion reproduces the Phase 3 placement/attack sequence and send count, whole-hull raised
  self/friend bounds only block sends (with the off control still placing), low-confidence prediction
  keeps the baseline, and the horizon comes from the active settings.
- `HurtSyncTest` — unknown/straddling windows keep a zero guaranteed lower, expired windows restore the
  full estimate, the 20-tick horizon is inclusive/exclusive at its edge, a lethal immediate estimate is
  never delayed, and self/friend inputs do not exist on the hurt path.
- `HurtSyncEngineTest` — a deferral sends nothing, keeps the spawn hazard and sent risk, survives duplicate
  spawn events, sends once at the wait tick, off is exactly Phase 3, an unfed model never defers, and
  self/friend bounds are unchanged.
- `DirectPlannerTest` — hurt-sync changes the target policy estimate and ranking, never exposure bounds;
  placements do not defer; a break carries the deferral tick.
- `DefensePlannerTest` — dangerous-crystal detection uses the whole-hull worst bound and the current
  outstanding self bound, skips own/ledger-protected crystals, enforces the self and per-friend ceilings
  with no minimum target damage, rejects unsafe re-prices, and covers the anti-piston four directions,
  feet/head, crystal present (safe break) vs absent (blocker), unsafe break drop, support/entity-free/face/
  reach refusals, facing-away/extended/out-of-range skips, independent gates and the no-mining rule.
- `DefenseEngineTest` — defence before offence with the imminent (<=4 tick) break, the non-imminent
  wait-for-offence and offence-empty fallback, the gates-off path
  identical to Phase 4.5, risk-budget rejection, blocker through the runtime with zero debt, unsent-lease
  rollback, failed-dispatch fall-through to offence, and defensive plans never binding ledger hazards.
- `CombatRuntimeBlockerDispatchTest` — `PLACE_BLOCKER` accepted through the single dispatch path with
  zero risk and no bound crystal, `NO_SUPPORT` refusal rolls the lease back, a defensive break revalidates
  the self ceiling at dispatch without a target-damage floor, and ordinary offence still enforces it.
- `CombatDebugHostHeaderTest` / module tests — the debug header reports both new gates and the module
  passes them through `DirectSettings`.

## Defaults comparison

| Knob | Meteor | Comet Phase 3 |
| --- | --- | --- |
| target range | 10 | 6 |
| place range | 4.5 | 4.5 |
| break range | 4.5 | 3.0 |
| break walls range | 4.5 | 3.0 |
| min target damage | 6 | 4 |
| max self damage | 6 | 6 |
| friend ceiling | n/a | 2 |
| remaining margin | n/a | > 2 |
| place delay | 0 | 1 place / tick |
| break delay | 0 | 1 break / tick |
| attempts | 2 | 2 |
| ticks existed | 0 | 0 |
| faceplace health | 8 | 8 |
| faceplace armour | 2% | 10% |
| pause health | 5 | 6 |
| lag pause | >= 1 s since tick | >= 1 s since feedback or TPS < 10 |
| `predict-movement` | false | `motion-prediction` false |
| motion prediction horizon | n/a | 2 ticks (Conservative); 3 accepted for an Anarchy config |
| self/friend prediction horizon | n/a | 1 tick when the tactic is on |
| guaranteed target lower when the tactic is on with usable motion | n/a | 0.0 (hull cannot prove the interior least exposed point) |
| self/friend worst bound when the tactic is on | n/a | max(baseline, whole-hull closest point at full exposure, weakest envelope) |
| `hurt-sync` | n/a (no Meteor equivalent) | false; no production raw-hit feed yet |
| hurt-sync horizon | n/a | 20 ticks; deferral only for an attack, never a placement or a lethal immediate estimate |
| `surround-gap` | n/a (no Meteor equivalent) | false; detection + placement preference only, no mining |
| `dangerous-crystal-break` | n/a (no Meteor equivalent) | false; whole-hull worst self bound + current outstanding bound vs the remaining margin, own/protected crystals skipped |
| `anti-piston` | n/a (no Meteor equivalent) | false; front cell = piston + facing, break crystal if safe else obsidian `PLACE_BLOCKER`; mining never attempted |
| mid-session tactic toggle | n/a | aborts in-flight actions, sends nothing, installs a new planner/cache on the existing stack immediately |

Phase 4.5 `surround-gap` only detects a one-column hole and prefers a normal direct placement in that
column when a legal obsidian/bedrock base already exists below it; every damage/reach/visibility/entity
guard is unchanged and no block is mined. The gap choice intentionally outranks a higher-priced non-gap
base (experimental hole exploit, not a damage claim), and while it is on the planner bypasses the Phase
4.1 candidate cache/128-cap (off by default; the engine still reprices before dispatch). The support scan
never fills the detected gap column; a support under the gap column that enables a crystal in the gap is
the intended chain. Speedmine cooperation is blocked until Speedmine gains an owner-scoped request and
cancel contract, so this build never mines a surround block.

Live items (100 consecutive verified cycles on the local test server, and the paired Meteor
comparison on the Phase 9.2 protocol) remain open for the P3.2/tester build; they are not claimed by
this document.

## Phase 5 (v2.1) — single-push piston trap, code-level

**Scope claimed.** Phase 5.1–5.5 pure contracts and code-level verification, plus the 2026-10-30
P5.1 runtime-integration gap closure: `PLACE_PISTON`/`PLACE_POWER` use the single dispatch path and
`PistonTrapController` drives the planner/sequence from a fake world, inventory and sink. No Minecraft
client or server was started, no packets were observed live, and no live P5.2 cycle is claimed here.
The production module registers the controller (`CrystalCombat.buildStack()` builds `PistonTrapWorld`
and `PistonTrapController` and passes it to `CrystalCombatController.Stack`, `CrystalCombat.java:575-581`);
the trap is user-reachable only with Mode `PistonOnly` or `Hybrid` (Hybrid only while a trap is
selected/committed), `piston-trap` on and at least one beta layout enabled, and is dormant otherwise
(it never arms in `DirectOnly`).

**Implemented.**

- `PistonGeometry`: 8 base layouts (`piston_<dir>_<feet|eye>`) × both power kinds = the same 16
  scenarios as the Phase 0 trace table. Per layout: source crystal cell, piston cell/facing,
  extension cell, power cell and the swept crystal path. Crystal base placement is part of the
  structural plan (`source().below()` on obsidian), supports are depth-limited to 2, structural
  blocks are capped at 6, and placement checks cover early power/piston powering, wrong observed
  facing, power that would activate another component, blocked push cells and entity occupancy.
- `PistonPlanner`: stationary (<0.05 blocks/tick for 10 ticks), hole/full-surround eligibility;
  64-candidate resumable scan inside the caller's tick budget; both power kinds enumerated; target
  damage uses a **lower bound** (minimum over the swept crystal path × the caller-supplied feasible
  target set; the planner adds current/destination/midpoint samples); self/friend bounds are upper
  bounds over the caller-supplied three-dimensional feasible position set with the weakest
  mitigation envelope, and unknown motion is **rejected** (`MOTION_UNKNOWN`) rather than sampled
  from the current point only; the plan carries ordered intents, the trace-derived
  expected pushed position, `PistonStage` list, `Footprint` (structural/swept/blast-search bounds)
  and an empty `Optional<Opportunity>` placeholder. Risk is reserved at `PLACE_CRYSTAL`.
- `PistonSequence`: strict `RESERVE → SUPPORTS → PISTON → CRYSTAL → POWER → WAIT_PUSH → ATTACK →
  WAIT_REMOVE → CLEANUP → COOLDOWN` order plus `ABORTED`/`MANUAL_CLEANUP`; power-before-crystal
  plans are rejected in code; spawn binding accepts only one **new** end-crystal spawn after the
  crystal placement tick in the reserved cell, excluding the caller's baseline UUID/entity set
  (pre-existing, foreign, duplicate or at-placement-tick spawns never bind); WAIT_PUSH completes from
  authoritative piston block events plus the measured push rule with a 0.05 cross-check tolerance
  and never waits for a crystal move observation (C21). Deadlines change retry/backoff only; risk is
  never retired by a deadline, disable or death, and cleanup/cooldown completion never clears the
retained-risk flag (only the risk budget retires risk on evidence; `riskRetirementRequests()==0`).

- `CombatRuntime` action support: `PLACE_PISTON` and `PLACE_POWER` are dispatched by the same
  validation and send path as the other actions. Each requires its real hotbar item
  (`minecraft:piston`, `minecraft:redstone_torch` or `minecraft:redstone_block`), an entity-free and
  replaceable cell with full support below, reach and visibility, no offhand change, and a horizontal
  aim that makes a newly placed piston face the planned direction. Structural placements are exempt
  from the target-damage policy but still pass self/friend ceilings and the suspension, risk and
  inventory checks; a stale cell or piston state fails with a typed reason (`OCCUPIED`,
  `WRONG_FACING`, `NO_SUPPORT`, `ITEM_MISSING`, ...) instead of throwing.

- `PistonTrapController` (`core/combat/mc`): finds at most one stationary/holed target per pass and
  stops scanning once a plan exists; one active trap until the sequence reconciles. Structural intents
  reserve a zero-bound risk through the scheduler and are dispatched via `CombatRuntime.dispatch`
  only, in plan dependency order. The crystal risk is reserved exactly at the `PLACE_CRYSTAL` intent
  with the planner-computed self/friend upper bounds, target estimate and target health; after a
  unique new spawn the bound crystal is attached to that sent risk (`CombatRiskBudget.bindCrystal`).
  Power is dispatched only after the crystal dispatch, then the final position comes from
  authoritative piston block events plus `PistonSequence`'s measured 1.02 rule (C21: no crystal-move
  observation). Damage and reach are evaluated at that computed position and exactly one attack of the
  bound entity is sent. Observation callbacks never send. Wrong facing, early power, ambiguous/
  foreign/pre-existing spawn, damage failure, target loss and timeouts abort or quarantine without
  retiring embedded risk; disable/death/disconnect send nothing and keep hazards and sent risk.

**Measured push conflict (resolved in favour of the traces).** The plan's literal
`0.5 + 0.01 = 0.51`/tick × 2 ticks predicts **1.04 blocks**; every Phase 0 piston trace measured
**1.02 blocks** (0.51/tick, final reached on the second extension tick). This build uses the measured
1.02 with the 0.05 cross-check tolerance and treats a layout without trace evidence as unsupported.

**Supported layouts.** All 16 horizontal layouts (4 directions × feet/eye × torch/redstone-block).
**Unsupported layouts.** Any vertical piston push, sticky-piston retraction-dependent push, and any
layout not present in the Phase 0 trace table; these are rejected (`UNSUPPORTED_LAYOUT`), never
guessed.

**Tests (code-level).** `PistonGeometryTest`, `PistonPlannerTest`, `PistonSequenceTest` cover the
Phase 5 test boxes that do not need a game: 16 layout table, with/without base, support depth 2 vs 3,
6 vs 7 structural blocks, torch/redstone-block legality, power activating another component, wrong
facing, measured 1.02 position and C21, ambiguous spawns and ID reuse, pre-existing/foreign/wrong-UUID/
duplicate/late spawns, target movement before/after POWER, disable/death at each phase, deadline
behaviour, cleanup ownership (manual default, contested untouched), cleanup/cooldown never retiring
risk, vertical self-exposure through the 3-D feasible set, unknown-motion rejection, full-feasible-set
target lower bound, and power-before-crystal rejection.
`PistonTrapControllerTest` (14 tests) covers structural dispatch order and zero-bound structural risk,
risk reserved exactly at the crystal intent, no sends from observation callbacks, failed structural
placement, unique spawn binding/rejection (pre-existing/foreign/duplicate/ambiguous), computed 1.02
position without a crystal-move observation, attack only after power and piston progress, wrong facing
and early power aborts, target loss before/after power, timeout/manual-cleanup risk retention,
disable/death/disconnect with no sends and hazards kept, one trap until reconciled, and the Phase 3/4
runtime regression through the same fake harness.

**Exit gate (code-level).** Pure contracts, geometry/planner/sequence tests and the new runtime path
tests pass offline. `PLACE_PISTON`/`PLACE_POWER` can only be produced by a controller instance, and
nothing is dispatched from an observation callback. **Gaps:** automatic piston/power mining does not
exist (`CONFIRMED_OURS` only, manual by default); live P5.2 local-server cycles and the paired Meteor
comparison remain open, so the trap is beta (tester build 2) and not server-confirmed. *Updated
2026-11-01:* the production hook now exists — `CrystalCombat.buildStack` constructs the controller and
the `piston-trap` setting arms it in `PistonOnly` or `Hybrid` (Hybrid only while a trap is
selected/committed), never in `DirectOnly`, so the earlier "not user-reachable" state no
longer holds.

## Phase 6.2 footprint-scoped concurrency (experimental, off by default)

No Meteor setting has a counterpart: Meteor CrystalAura has no trap, so footprint-scoped concurrency is
a Comet stage between serialised Hybrid and any future promotion. It is recorded here because it gates
an accepted configuration, not because it is a parity toggle.

| Comet stage | Default | Status | Evidence |
| --- | --- | --- | --- |
| Serialised Hybrid (P6.4.1) | shipped | Accepted | Choice/hysteresis/safety tests plus `HybridInterleavingTest`; metrics record each rejection reason. |
| Footprint-scoped (P6.4.2) | `footprint-concurrency` false | Experimental, not promoted | Pure `FootprintConflict` implements all six 6.3 rows; `FootprintConflictTest` (row matrix, unknown→conflict, unrelated→compatible), `CombatRiskGateTest` (self/friend ceilings), `HybridConcurrencyTest` (off/unavailable blocked, compatible admitted, conflict serialised, alive trap sentinel, P6.1 friend ceiling), and `ConcurrentInterleavingTest` (1,000 seeds on-path with the real risk budget) pass offline. No live paired comparison exists, so the stage stays off. |

**Wiring and invariants.**

- `core/combat/FootprintConflict` is pure and game-free. Rows: structural (piston/power cells, audited
  ray rule with the pinned 26.2 per-step decay), entity (bound crystal or the audited 12-block power-6
  entity search), swept (12-block damage/displacement reach of the swept volume), target motion
  (audited 3-D knockback against collision geometry and the trap's priced window, including vertical
  escape), resources (same slot/rotation → serialise), risk (combined reservations fail the budget →
  conflict). Unknown evidence returns conflict; distance alone never proves compatibility.
- The engine's committed-plan gate consults the trap footprint only when `footprint-concurrency` is on.
  Off, the gate is the serialised stage word for word (`setting` bucket). An active trap with no
  ranking view is represented as an active-but-empty view, and an empty view conflicts, so the
  concurrency path can never be opened by a null committed plan.
- Resources serialise at the existing scheduler: the direct plan waits when a live slot/rotation owner
  exists or when the trap's next stage claims the slot/rotation this tick. No second send path, no new
  scheduler lock model.
- `HybridGate.riskAllows` now delegates to a pure gate that mirrors `CombatRiskBudget.reserve`: self
  per-request ceiling, self cumulative margin, per-friend cumulative `maxFriend`, and KNOWN-friend
  survival. Unknown/censored friends get the ceiling only; no health is fabricated.
- Metrics: `concurrency_admitted`, `concurrency_serialised` (by row), `concurrency_setting` (`on`/`off`),
  `concurrency_modes`. The debug log prints setting/mode/phase on change only.

**Corrected audited fact.** The Phase 0 audit's §3.10.3 summary omitted the per-ray-step decay; the
pinned 26.2 bytecode subtracts `0.22500001` from the ray intensity every 0.3-block step in addition to
`(resistance+0.3)*0.3` for non-air blocks. The implementation uses the bytecode rule (maximum air reach
for a power-6 ray ≈ 10.4 blocks; torch ≈ 10.3, piston ≈ 9.7, redstone block ≈ 6.7, obsidian immune).
The 12-block entity search and the "no second explosion" crystal rule are unchanged.

**Open.** The paired serialised-vs-footprint-scoped comparison (tester checklist items 77–80) is not
run; there is no promotion claim, and P6.2 remains off in every preset. The cleanup-starvation item is
the accepted manual-cleanup gap: no cleanup intent or packet path exists.
