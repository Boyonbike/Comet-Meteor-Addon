# CrystalCombat completion — progress log

Orchestrated run against `docs/superpowers/plans/2026-10-29-crystal-combat-v2.1.md`.
Repository: `~/Desktop/Comet Metior Addon/26.2` (branch `26.2`, common git dir `../26.1.2/.git`).

| Stage | Status | Date | Commits | Tests | Reviewer |
| --- | --- | --- | --- | --- | --- |
| 1 Secure the work | DONE | 2026-11-01 | see below | 2620 / 276 suites, 0 failures | orchestrator spot-check |
| 2 Plan and docs match reality | DONE | 2026-11-01 | `01d0e75` | 2620 / 276 suites, 0 failures | PASS after 1 fix round |
| 3 Phase 6 gaps | DONE | 2026-11-01 | `b7bec51` | 2645 / 276 suites, 0 failures | PASS after 2 fix rounds |
| 4 Phase 5 test gaps + auto-cleanup | DONE | 2026-11-01 | `ec3d365` (part A), `361e5f8` (part B) | 2699 / 277 suites, 0 failures | part A PASS after 2 fix rounds; part B PASS after 1 fix round |
| 5 Phase 8 UI and lifecycle | DONE | 2026-11-01 | `166cf57` | 2733 / 282 suites, 0 failures | PASS after 1 follow-up fix round + delta re-review PASS |
| 6 risk-model setting | DONE | 2026-11-01 | `067cfd5` | 2776 / 287 suites, 0 failures | PASS after 2 fix rounds (same-bundle health evidence both orders) + final delta re-review PASS |
| 7 Tester build 3 | DONE | 2026-11-01 | `4f0e141` (docs) + tester.3 build | 2776 / 287 suites, 0 failures | PASS (7 non-blocking, 6 fixed in the same commit) |
| 8 Final report | | | | | |

## Stage 1 — Secure the work — DONE (2026-11-01)

Tree was committed in eight ordered commits (Stage 1 a–h), then `1.txt` was added to `.gitignore`.

| Commit | Contents |
| --- | --- |
| `7487801` | (a) `src/main/java/dev/tntcoating/core/combat` including `mc/` — pure core + runtime adapter, 139 files |
| `63d71f3` | (b) combat test suites, 95 files |
| `0c4c373` | (c) `src/test/resources/combat` recorded trace fixtures, 181 files |
| `cd72f09` | (d) `CrystalCombat`, `CombatRecorderModule`, mixins, `comet.mixins.json`, `TntCoatingAddon`, `CrystalAuraMixin`, commands, `core/tester`, `build.gradle.kts`, `libs.versions.toml`, `comet-build.properties` |
| `6166577` | (e) `src/gametest` and `scripts/combat_trace_report.py` |
| `06fb691` | (f) all docs (plan, audit, trace report, tester material) |
| `6166fd4` | (g) 31 Oct Elytra/Highway hardening |
| `3a36aae` | (h) ChatTools changes |

- Sibling worktrees: `26.1.2` `8d61af3` and `1.21.11` `59c5840` carry the same Elytra/Highway hardening (plus their pending 0.27.0 work) as one commit each.
- `PUSH: never` — nothing was pushed. `origin/26.2` remains at `0433e29`.
- Test totals before and after the commits: **2620 tests, 276 suites, 0 failures, 0 errors, 0 skipped** (`./gradlew test --no-build-cache`, 276 XML result files).
- Non-blocking: none.

### Environment notes

- `./gradlew test` is served from the Gradle build cache unless `--no-build-cache` is passed; orchestration runs use `--no-build-cache` for real execution.
- JDK 27 is the toolchain in this environment (project targets Java 25 via Gradle toolchain).

## Carried-forward issues (from the plan/audit, before Stage 2)

- Cleanup intents share budgets and cannot be starved (Phase 6.5) — no real cleanup-intent origin exists yet.
- Phase 7.1 head-height/double push blocked on traces; Phase 7.2 render hiding blocked on an RTT source.
- Footprint-scoped concurrency ships off pending the paired comparison.

## Stage 2 — Plan and docs match reality — DONE (2026-11-01)

Commit `01d0e75` (six docs files). Tests: **2620 tests, 276 suites, 0 failures, 0 errors, 0 skipped** (`./gradlew cleanTest test --no-build-cache`, newest result 2026-11-01T10:19:50Z). `python3 scripts/check_docs.py` PASS.

Reviewer verdict: **PASS after one fix round.** First review failed six blocking claims; the fix round unticked three boxes that had no test evidence (fixture-replay happy path, swept intermediate-frame bound, every-phase disable/death loop), corrected four PistonOnly-only statements to PistonOnly-or-Hybrid, fixed the stale "no defence logic" line and the false "five-run requirement met" note, and corrected six smaller accuracy issues (Speedmine `requestBreak` exists but has no owner-scoped cancel; PacketLedger "cleanup" removed; unsupported-layouts list deferred; respawn wording narrowed; test path; placeholder audit filename). 75 plan boxes are ticked after the fix round (44 Phase 0, 31 Phase 5).

Carried-forward non-blocking issues:
- plan:439 cites the trace report's :13-15 for the Partial status; :303 would be cleaner and :13-15 applies only to the piston one/two-run figure.
- The DEFERRED notes say "Stage 4/5" where the plan uses "Phase" numbering; a plan-only reader could confuse them.
- plan:1029 calls the deferred test "intermediate-frame self-bound"; the box says only "bound" — broaden if it is meant to cover target bounds too.
- plan:1045 says Stage 4 adds the unsupported-layouts list although all sixteen traced layouts are supported today (the list may end up empty).
- CHANGELOG.md:6 keeps the historical "HYBRID is still refused" clause before its supersede note (intentional history).

Deferred to later stages (boxes now unticked with DEFERRED notes): plan:1027 fixture-replay happy path and plan:1029 swept intermediate-frame bound → Stage 4; plan:1034 every-phase disable/death loop → Stage 5; plan:1045 unsupported-layouts list → Stage 4. Still unticked for their own stages: plan:1027 vertical push out of the hole (→ Stage 4), plan:1028 ID reuse across connections (→ Stage 4), plan:1086/1088 direct-wins-on-surrounded-target and missing piston materials (→ Stage 3).

## Stage 3 — Phase 6 gaps — DONE (2026-11-01)

Commit `b7bec51` (10 files: `PistonPlanner`, `PistonTrapController`, `PistonTrapWorld`, `CrystalCombatController`, four test suites, module doc, plan). Tests: **2645 tests, 276 suites, 0 failures, 0 errors, 0 skipped** (`./gradlew cleanTest test --no-build-cache`, newest result 2026-11-01T11:22:58Z). `python3 scripts/check_docs.py` PASS.

Delivered:
- **Missing piston materials (6.1 gap):** `PistonPlanner.World.hasItem(String)` plus planner gate `hasRequiredMaterials` (piston, end crystal, obsidian, at least one enabled layout's power item) before any candidate work; `Reject.MISSING_MATERIALS`; per-candidate rejection when that candidate's own power kind is absent. `PistonTrapController.missingMaterials()` / `missingMaterialsText()` gate `previewPlan`, `scan` and `beginPlan`; `missingPlanMaterials(plan)` re-checks the previewed plan's own power item before `new PistonSequence`/`slot.begin`, so a plan that lost its power never reserves. `CrystalCombatController.waitReason()` surfaces `piston trap: missing materials: …`; the debug log records each distinct missing set once per change.
- **Direct wins when better (6.1 gap):** `CombatChoiceTest.higherUtilityDirectFaceplaceWinsOverASurroundedTrapCandidate` (trap utility 4.2 vs direct 8.0) and `HybridEngineTest.surroundedTargetWhereDirectFaceplaceRanksHigherChoosesDirect` (Hybrid picks DIRECT, `trapSelected` false, direct placement sent). A committed/live trap still serialises; only an uncommitted preview loses.
- **Cleanup starvation:** documented as an accepted gap (module doc + plan:1101-1103) with the synthetic tests retained.
- **Two latent defects found by the orchestrator while re-checking the fix (both from `7487801`), fixed here:**
  1. Hybrid preview starvation: stationary counters advanced only in `tickInternal`, but the trap is only ticked when selected/active, so a fresh Hybrid session could never preview a trap. `previewPlan` now takes one observational sample per server tick (`world.onServerTick`) and `PistonTrapWorld.onServerTick` dedupes per tick; preview still never scans, starts, reserves, leases or sends.
  2. Planner never rescanned: a completed empty pass was sticky forever (`reset()` only on reconcile), starving PistonOnly and Hybrid. `PistonPlanner.tick` now restarts only an exhausted empty pass within the caller's remaining budget; a completed pass that found a plan stays sticky so a Hybrid preview survives until selection.

Review: first reviewer failed two blockers (plan-own power item not re-checked; stale trap world snapshot in Hybrid preview); a fix round closed both with tests. A fresh read-only reviewer then reviewed the complete diff and returned **PASS** after verifying 2645 tests and `check_docs`. Non-blocking carried forward:
- The engine "surrounded faceplace" test seals a tube around TARGET but offers a trap for a different unbound entity and the direct plan is an ordinary base placement — real for ranking, overstated for the scenario.
- Plan-own-power shortfall is debug-only: after swapping torch→block, `previewPlan` returns empty and `waitReason` falls through to "hybrid: idle" until `reconcile()`; the sticky planner cannot re-plan an available alternative layout.
- Production `PistonTrapWorld.onServerTick` dedupe/refresh has no direct unit test (fakes cover the controller seam).
- Planner material-gate `Result(..., complete=true, MISSING_MATERIALS)` can report complete while internal scan state is untouched (diagnostic inconsistency).
- `lastSampleTick` is not reset on reconcile/reconnect — one-tick stale sample if a new session's tick equals the previous one.
- Pre-existing: a sticky completed plan survives `setLayouts`, so a plan for a now-disabled layout can still be previewed/started until reconcile.


## Stage 4 — Phase 5 test gaps + auto-cleanup

### Part A — replays, swept midpoint, vertical-push abort, truthful cleanup text — DONE (2026-11-01)

Commit `ec3d365` (10 files: `PistonSequence`, `CombatRuntime`, `CrystalCombatController`, `PistonTrapController`, `PistonPlannerTest`, `PistonTrapControllerTest`, `CombatRuntimeLifecycleTest`, new `PistonTraceReplayTest`, module doc, plan). Tests: **2664 tests, 277 suites, 0 failures, 0 errors, 0 skipped** (`./gradlew cleanTest test --no-build-cache`). `python3 scripts/check_docs.py` PASS.

Delivered:
- **Recorded-trace happy path:** `PistonTraceReplayTest` drives the real controller and `CombatRuntime` through all 16 `piston_*` fixtures. It feeds the recorded `piston_tick_start`/`piston_tick_end` progress values (0.0/0.5/1.0) at the recorded tick offsets, checks the computed final crystal position against the recorded `server_tick_end.crystals[]` per UUID with 0.01 tolerance (so a 1.04-vs-1.02 push constant fails), asserts the attack is sent after the recorded extension ticks, and asserts one PISTON/PLACE/POWER/ATTACK, the recorded crystal UUID, no sends in observation callbacks or for 3 ticks after the attack, and no incidents.
- **Swept mid-point bound:** `PistonPlannerTest.selfBoundPricesTheSweptMidPointNotOnlyItsEndpoints` gives a frame at the mid-point inside the self blast while both endpoints sit outside; the planner rejects `SELF_BOUNDS` (production `exposedDamage` already maxes over `push.positions()`).
- **Vertical-push abort:** in POWER/WAIT_PUSH a rise faster than 0.45 blocks/tick (vanilla jump peak ≈0.42) plus evidence — an applied explosion within 8 blocks or a non-plan block change within 4 blocks, either within 3 server ticks — aborts the trap through `PistonSequence.abortTrap` (no structural retry), keeping risk and hazards and naming the manual cleanup. `CombatRuntime` gained a passive `setExplosionObserver(Consumer<Vec>)` client-thread seam fed from applied `EXPLOSION` observations; the trap registers in its constructor and only records evidence. A normal jump, a distant explosion and our own placement/extension packets do not abort.
- **Manual cleanup text:** `manualCleanupText()` names only cells whose placement packet was SENT, in order power, piston, supports, then the crystal while it is still standing (source before the push, computed pushed cell after). `CrystalCombatController.waitReason()` publishes `manual cleanup required (reason): remove <blocks>; .crystalcombat cleanup confirm`; no packet is sent.
- **Unsupported layouts:** `docs/modules/crystal-combat.md` has an explicit "Unsupported or unconfirmed layouts" section; all 16 traced layouts are beta and the list is currently empty (Q7 found no trace disagreements), with `UNSUPPORTED_LAYOUT` for arrangements outside them.

Review: first reviewer failed three blockers (jump-strength false positive; the abort window missed POWER; the replay hard-coded piston ticks and compared production against production). Fix round 1 added the disturbance/velocity detector, the POWER window and a fixture-driven replay; the second reviewer then proved a remaining false negative (an explosion that lifts the target with no non-plan block update, or only plan-cell updates) and a strict-0.5 miss. Fix round 2 added the real explosion-observation seam and the 0.45 threshold; the same reviewer returned **PASS** with no blockers. Carried-forward non-blocking issues:
- The test harness's `explode(...)` helper calls the runtime observer directly, bypassing `applyObservation`; the combined production path was proven only by a reviewer's temporary test (the runtime seam itself is unit-tested).
- The replay's attack-tick assertion is weak: progress is fed after the controller tick, so it catches an attack before the recorded ticks but not a one-tick regression that still follows the last extension.
- Evidence-window off-by-one: an explosion applied before the controller tick records `lastServerTick` (N−1), so a rise at N+3 falls outside the documented 3-tick window (tests use N/N+1).
- No coverage for an explosion arriving after the target movement, or for a same-tick CRYSTAL/POWER transition after evidence.
- Jump boost above 0.45 with a real explosion nearby aborts conservatively; the docs only mention the vanilla jump height.
- A sticky completed plan can still name blocks for a layout that was disabled before reconcile (pre-existing).

### Part B — beta automatic cleanup (`PISTON_AUTO_CLEANUP: build-opt-in`) — DONE (2026-11-01)

Commit `361e5f8` (26 files: 13 production, 7 test classes, 3 docs, plus the two extra ledger/scheduler classes). Tests: **2699 tests, 277 suites, 0 failures, 0 errors, 0 skipped** (`./gradlew cleanTest test --no-build-cache`). `python3 scripts/check_docs.py` PASS; `git diff --check` clean.

Delivered:
- **Setting** `auto-cleanup` (General group, default `false` in every profile) with the exact required description; live through `configChanged` → `CrystalCombatController.setAutoCleanup` → trap.
- **Ownership gate:** every structural send registers a ledger placement before dispatch and marks it sent on SENT (cancelled on any non-SENT result); production block updates now feed `CrystalLedger` with old/new ids. Cleanup only touches cells that are in this trap's `sentStructural` **and** `ledger.ownership(ticket.conn(), cell) == INFERRED_OURS`; `CONTESTED`/`NOT_OURS` are never touched, and a state-only update keeps a confirmed or pending placement intact while air→block still contests. Ownership and the exact expected block are re-checked immediately before every action.
- **Dispatch-only removal:** new `MINE_POWER`/`MINE_PISTON`/`MINE_SUPPORT` actions through `CombatRuntime.dispatch` → `CombatPacketSink.breakBlock` → vanilla `mc.gameMode.continueDestroyBlock` on the client thread, with a hotbar tool from `CombatInventoryAdapter` (slots 0-8 only, never the offhand, no Speedmine). Order power → piston → supports.
- **Safety:** cleanup never starts while the trap crystal is unresolved, never detonates, keeps risk and hazards, and stops its own vanilla destruction (`CombatPacketSink.stopBreak` → `mc.gameMode.stopDestroyBlock`, guarded by client thread + open session/sink/connection) before disable, death, respawn, reconcile, setting-off, contested/changed mid-break, deadline, completion, abort and `BLOCK_CHANGED`. A new mine on a different cell auto-stops the previous one; connection close forgets the local state without sending.
- **Scheduling:** cleanup uses a packet-only lease from `CombatScheduler.reservePackets` (no risk entry, no debt or hazard, released on send/failure), the shared packet budget and `CleanupPriority`'s 20-tick starvation window with a real feasibility probe. The engine yields the tick to cleanup only when the probe confirms the trap can reserve; a refused probe lets direct offence run. 40-tick deadline → `MANUAL_CLEANUP` with the remaining blocks listed.
- Docs: module doc and tester README document manual (default) and beta auto-cleanup, the exact description, ticket-scoped inferred ownership, hotbar tool rule, unresolved-crystal gate, starvation window, deadline, and the obsidian-time limitation; plan Phase 5.5 boxes cite tests, the old cleanup-starvation BLOCKED note is replaced and a decision-log row added.

Review: the first read-only reviewer failed one blocker — the vanilla destruction was never cancelled, so the server could finish a contested block after a halt. The fix round added the stop path and the destruction-state tests, plus the reviewer's non-blocking items (ticket-scoped ownership, packet-only lease, engine feasibility gate, pending-placement state-only update, a real pre-existing-block scenario, docs wording). The same reviewer returned **PASS** (277 suites / 2699 tests), also simulating the production controller disable order with a temporary test. Carried-forward non-blocking issues:
- `autoCleanupNeverBreaksBlocksThatExistedBeforeTheTrap` is weak: cleanup keeps re-breaking the power cell, so the support assertion is vacuous and the plan:1018 claim that pre-existing obsidian stays in the manual remainder is an overclaim (manual text lists only this trap's cells).
- `cleanupCanReserve` uses `scheduler.clock()` instead of the current engine tick and omits `risk.suspended()`; a stale-true probe can yield the engine's slot once and be refused (direct offence loses that tick). Covered only for probe=false.
- `tickCleanup` clears priority when no next cell (e.g. tool removed mid-break) without stopping an already-started destruction; several halt stop paths are code-only or uncovered; `onDimensionChanged` neither stops nor forgets.
- `stopDestruction()` cancels whatever block `mc.gameMode` is destroying, including a player's manual break of a different block.
- `stopBreak` is the only send outside `CombatRuntime.dispatch`; it is a guarded cancellation, not a combat action, documented as the exception.
- Tests do not exercise the production controller disable order (reviewer's temporary test passed); the no-risk test does not assert the packet-ledger spend.

## Stage 5 — Phase 8 UI and lifecycle — DONE (2026-11-01)

Commit `166cf57` (17 files: `CrystalCombat`, `CrystalCombatController`, new `CrystalCombatSettingsMigration`, `RiskDebtSnapshot`, `CrystalCombatRender`, five new test classes, two updated test classes, module doc, tester README, plan). Tests: **2733 tests, 282 suites, 0 failures, 0 errors, 0 skipped** (`./gradlew cleanTest test --no-build-cache`). `python3 scripts/check_docs.py` PASS; `git diff --check` clean.

Delivered:
- **Read-only debt/suspension display:** `RiskDebtSnapshot` is an immutable record (`count`, `bound`, `causes`) with `empty()`, `visible()` and `displayLines()` (`risk debt: N outstanding (bound X)`, `suspended: …`). `CrystalCombatController.sampleRisk()` publishes a volatile snapshot on the client tick; `disable()` and `onLeft()` clear it. The module's `onRender2D` is gated on the new `show-risk-debt` Render setting (default on) and only hands the snapshot's lines to `CrystalCombatRender`, which fills/draws text with `event.graphics`/the font — it never touches the stack, world, budget or ledger and never advances a sequence or sends. Meteor's twice-per-frame `getInfoString` stays a cheap volatile read.
- **Settings re-groups + persistence migration:** groups are General (mode, profile, target-range), Safety (anti-suicide, four thresholds), Direct (ranges, own-only, support, surround-gap), Timing (motion-prediction, hurt-sync), Piston (piston-trap, auto-cleanup, 16 layouts), Defence (dangerous-crystal-break, anti-piston), Pauses (7), Experimental (footprint-concurrency) and Render (show-risk-debt); Diagnostics is kept as a documented deviation and Inventory is absent because lending is deferred. `CrystalCombat.fromTag` runs `CrystalCombatSettingsMigration` before `super.fromTag`; the pure NBT transform moves the 15 old keys and every `piston-layout-*` into the new groups, keeps unknown data, lets destination values win, is idempotent and does not mutate its input.
- **Warnings and profiles:** one-line `Experimental:`/`Beta:` descriptions on all nine experimental/beta settings and the 16 layouts, enforced by `CrystalCombatPhase8SettingsTest`. `Profile` is now `CONSERVATIVE, CUSTOM` (ANARCHY still not offered); Conservative re-applies the four audited defaults (4/6/2/2), Custom keeps the player's values, and both pass `settingsRefusal`/`CombatProfiles.productionSafety`. Switching profile runs through the real controller path: queued unsent intents are invalidated while sent risk, outstanding debt, suspension causes and unresolved hazards are retained (`CrystalCombatConfigChangeTest`).
- **Lifecycle proof:** `PistonTrapControllerTest.disableAtEveryPistonAndCleanupPhaseSendsNothingAfterwards` covers RESERVE, SUPPORTS, PISTON, CRYSTAL, POWER, WAIT_PUSH, ATTACK, WAIT_REMOVE, CLEANUP, COOLDOWN and MANUAL_CLEANUP with pre-disable attempts/outstanding/hazard captures and post-disable hazard-retention asserts; `CrystalCombatEngineModuleTest` covers direct placement and a sent attack. Leaving the world clears the published snapshot and joining does not resurrect it (`CrystalCombatRiskSnapshotTest`). Inventory lending is not built and is marked deferred in the plan and module doc; `Speedmine cooperation` and the other plan-table settings with no backend are documented as not implemented; the render deliverable is the debt/suspension display only (no outline, pushed-position marker or damage label).

Review: `a2cb0853` PASS with six non-blocking findings (stale snapshot after leaving a world; up-to-five `configChanged()` runs when selecting Conservative; weaker RESERVE/hazard coverage in the disable test; source-introspection settings test limits; four plan/doc overclaims; rule-7 boundary note). A follow-up fix round cleared the product bug (snapshot cleared in `onLeft`), hardened the disable test (real prepared RESERVE, dispatched crystal hazard, unresolved-hazard asserts), corrected the plan/doc claims, and added the escaped-`\n` warning check; the same reviewer's delta re-review returned **PASS** (282/2733) and added three nits, of which the stale total and the universal lifecycle wording were fixed before commit.

Carried-forward non-blocking issues:
- `CrystalCombatPhase8SettingsTest` is source introspection: it walks names/groups and warning text but does not assert defaults, min/max, types or semantics, and its warning list is hard-coded, so a new experimental setting needs a test edit.
- The piston-disable retention assert is guarded by `if (outstanding > 0)`; the new hazard-retention asserts back it up. `unresolvedHazard()` scans `HazardId` 1..32 (test-only brittleness).
- Selecting Conservative sets four settings whose `onChanged -> configChanged()` handlers plus the switch call can run the reconfigure path up to five times. It is safe (no sends, no hazard/SENT-risk change, legal intermediate values) but chatty; documented in the plan decision log and module doc.
- The migration keeps unknown groups/settings only in the migrated tag; Meteor drops unknown data on the next save.
- `CrystalCombatSettingsMigration` lives in `dev.tntcoating.core` and imports `net.minecraft.nbt`, matching the existing `HighwayFlyConfigMigration` precedent; rule 7's letter is satisfied because rule 7 scopes `core/combat`, not `core`.

## Stage 6 — risk-model setting — DONE (2026-11-01)

Commit `067cfd5 feat(combat): add three-option risk model with barrier probe` — 25 files: new `core/combat/RiskModel.java`; `Action`/`Strategy` (PROBE), `AnarchyQuietWindow` (superseded docs), `ProcessingBarrier` (strict-marker docs), `CombatScheduler` (packet-only CLEANUP/PROBE leases), `CombatRiskBudget` (BARRIER probe lifecycle, METEOR retirements, health-evidence tracking, event sink), `mc/CombatPacketSink` + `mc/ClientPacketSink` (real prediction sequence, ABORT_DESTROY_BLOCK probe), `mc/CombatRuntime` (one-probe-per-tick dispatch, ack forwarding, enabled state), `mc/CrystalCombatController`, `mc/CombatDebugHost`, `modules/CrystalCombat`, five new test classes plus four updated test classes; module doc and plan. Tests: **2776 tests, 287 suites, 0 failures, 0 errors, 0 skipped** (`./gradlew cleanTest test --offline --no-build-cache`); `verifyCombatPurity` pass; `python3 scripts/check_docs.py` PASS; `git diff --check` clean.

Delivered:
- **Three models, Strict default:** `RiskModel` enum `STRICT` (default in every profile), `BARRIER`, `METEOR_STYLE`; setting `risk-model` lives in Safety directly after the locked anti-suicide toggle, description carries the three audited option strings (prefixed `Strict — `, `Barrier — `, `Meteor-style — ` because Meteor 26.2 has no per-option description API). Changing it goes through the config-change path; the model is applied live to the existing budget without rebuilding the stack, and switching never clears sent risk.
- **STRICT:** unchanged rule-1 debt tracking; the processing barrier remains never satisfied; no extra packets.
- **BARRIER:** after one of our crystals' explosions is applied, one side-effect-free sequenced probe (`ServerboundPlayerActionPacket` `ABORT_DESTROY_BLOCK` at the first air cell within four blocks above the eye) is sent through `CombatRuntime.dispatch` as a packet-only `Strategy.PROBE` action (one packet, no risk/debt/hazard, client thread only, never while disabled/dead/disconnected or while our own cleanup destroy is running). It uses the real vanilla prediction sequence via the already-registered `SpeedminePredictionAccessor` + `BlockStatePredictionHandler`, so the server's `ClientboundBlockChangedAckPacket` carries a real sequence. Only when that exact ack arrives (once, at/after the send tick) and one more server tick passes does the budget retire, with zero damage, every reservation whose explosion was observed at or before the probe and which has no coherent health evidence at/after that explosion — in either arrival order (health-then-explosion or explosion-then-health at the same applied revision). Lost, late or out-of-order acks keep the debt; an unacked probe is pruned after 40 ticks and the risk is left abandoned (never re-armed) rather than retired. The probe is skipped while a cleanup destroy is active and while a previous probe is in flight.
- **METEOR_STYLE:** a sent reservation retires as soon as its bound crystal's explosion is observed, or the crystal is seen removed (`REMOVED`/`TRACKING_REMOVED`) on the same connection without dimension migration; new checks still price against current effective health minus the sent-but-not-exploded bounds, because `CombatRiskBudget.current()` is unchanged and the Hybrid gate reads it, so several crystals in flight are still summed and a third that would cross the margin is blocked. Timeouts still never retire; a crystal never observed exploding stays counted until seen gone or connection close.
- **Visibility and diagnostics:** info string appends `[risk: <key>]`; the debug-log header records `risk-model=<key>`; every SENT dispatch logs `send outstanding=<n> ack=<seq>`; model changes, BARRIER retirements and METEOR retirements are logged (Strict rule-1 retirements are not separately logged). Retirement logging and per-send lines reach the recorder only while the debug log is running.
- **Quiet window retired:** `AnarchyQuietWindow` is documented as superseded and unreachable from settings, with a decision-log row dated 2026-11-01; `ProcessingBarrier` is re-documented as the strict-model marker rather than a dormant feature.

Review: `10f62cc4` initially FAILED on B1 — BARRIER could retire a risk whose coherent health snapshot shared the explosion's own applied revision (only reachable through bundled or proxied packets, but the documented condition had no bundle exception); it proved it with a temporary test. Fix round 1 added a per-risk `healthEvidenceAfterExplosion` flag and cleared it on new explosions, but the delta review found B2: health applied *before* the explosion at the same revision was discarded because the flag setter ran after the `explosionRevision < 0` gate and `observeExplosion` cleared unconditionally. Fix round 2 replaced the flag-only approach with a per-risk `latestHealthRevision` map (bound self/friends, same connection, newer than `sentRevision`, max per entity, cleared only on dimension change and termination) that `observeExplosion` recomputes from, so evidence is order-independent while rule-1 retirement still needs a strictly later revision. The final delta re-review returned **PASS** with 287/2776 and no test weakened.

Deviations recorded in the plan/module doc: the existing `SpeedminePredictionAccessor` mixin was reused instead of adding a duplicate; `EnumSetting` has no per-option description so the three descriptions are concatenated in one setting description; the probe cell is the first air cell within four blocks above the eye (confined/underwater positions refuse and keep debt); the Barrier model is confirmed only on a local vanilla server; the harmless server "Mismatch in destroy block pos" WARN can appear on a normal probe.

Carried-forward non-blocking issues:
- No dedicated regression test for `latestHealthRevision` map growth/stale-connection hygiene; bounded by construction (self + current friend keys, cleared on dimension change and terminate, connection epochs) but a future friend-set mutation would invalidate that reasoning.
- The METEOR crystal-gone outstanding path has no direct test (the explosion path is tested and the code is symmetric).
- The BARRIER probe delay guard has no liveness watchdog if `destructionCell` ever leaks; documented behaviour is probe suppression until abandonment with debt kept.
- `BARRIER_RETIRE_TOTAL` is an extra summary line and "Barrier retirements are logged once each" is slightly ambiguous.
- Zero-bound self/friend health snapshots still block the barrier (conservative: may slow the aura, cannot cause unsafe placement).
- `CrystalCombatRuntimeBarrierProbeTest` and the other new suites do not cover planner integration or a render-thread call path.


## Stage 7 — Tester build 3 — DONE (2026-11-01)

Documentation commit `4f0e141` (no `src/`, `build.gradle.kts` or `libs.versions.toml` change):

- `docs/testers/README.md` retitled for build 3 and gained `## Sending your report`: send reports **directly to the developer** (not a public tracker, public chat or Discord), use `.crystalcombat export confirm` first so other players' names/UUIDs are hashed, zip `comet/exports/` together with the filled-in template and send that one zip. Per-OS paths: Windows `%APPDATA%\.minecraft\comet\exports\`, macOS `~/Library/Application Support/minecraft/comet/exports/`, Linux `~/.minecraft/comet/exports/`; custom launchers use the instance game directory containing `mods/` and `config/`. Per-OS zip steps included.
- Triage location documented as `testers/reports/<round>/<tester>/`: for this build `testers/reports/tester-3/<tester>/`, the input to Phase 9 triage; testers never commit anything.
- `CHANGELOG.md` top entry `## 0.27.0-tester.3 — 2026-11-01` (all three modes, three-option risk model, debt HUD, settings re-groups/migration, materials wait, direct-over-preview, beta auto-cleanup, piston trace/vertical-push hardening, tester docs) with an explicit known-limitations list.
- New `docs/testers/tester-3-handout.md`: install (MC 26.2, Fabric Loader, Java 25, pinned Meteor build, jar + source zip), test order DirectOnly → PistonOnly (beta) → Hybrid, risk-model order Strict → Barrier (local vanilla only) → Meteor-style, how to read debt/suspension text, debug log + export flow, privacy, where to send, what to include, limitations and a stop-and-send list.
- `docs/testers/checklist.md`: stale ack wording corrected (ordinary dispatch keeps `ackSequence = -1`; Barrier is the exception), cleanup wait text updated, `footprint-concurrency` moved to Experimental, and `## Tester build 3 additions` items 81–87 (risk-model selection and debt retention, Strict retirement, Barrier on local vanilla, Meteor-style, debt/suspension display incl. clear on leaving world, auto-cleanup, settings migration).
- `docs/README.md` links the handout; `docs/modules/crystal-combat.md` moves to `-tester.3` and links the tester docs.

Review: reviewer `9c237c76` (read-only) returned PASS with no blocking issues; 7 non-blocking nits, of which 6 were fixed in the same commit (migration durability wording, Pauses destination list, real info-string shape `[risk: ...] [v0.27.0-tester.3 <commit> tester.3]`, auto-cleanup stop list including respawn and mode switch, "when risk is suspended" wording, ordinary-path ack wording). The seventh (tester.3 artifacts not built yet) is the handoff gate resolved by the build below.

Build verification is recorded in Stage 8.
