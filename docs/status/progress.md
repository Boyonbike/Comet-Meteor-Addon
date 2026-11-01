# CrystalCombat completion — progress log

Orchestrated run against `docs/superpowers/plans/2026-10-29-crystal-combat-v2.1.md`.
Repository: `~/Desktop/Comet Metior Addon/26.2` (branch `26.2`, common git dir `../26.1.2/.git`).

| Stage | Status | Date | Commits | Tests | Reviewer |
| --- | --- | --- | --- | --- | --- |
| 1 Secure the work | DONE | 2026-11-01 | see below | 2620 / 276 suites, 0 failures | orchestrator spot-check |
| 2 Plan and docs match reality | DONE | 2026-11-01 | `01d0e75` | 2620 / 276 suites, 0 failures | PASS after 1 fix round |
| 3 Phase 6 gaps | DONE | 2026-11-01 | `b7bec51` | 2645 / 276 suites, 0 failures | PASS after 2 fix rounds |
| 4 Phase 5 test gaps + auto-cleanup | IN PROGRESS (part A DONE, part B pending) | 2026-11-01 | `ec3d365` (part A) | 2664 / 277 suites, 0 failures | PASS after 2 fix rounds (part A) |
| 5 Phase 8 UI and lifecycle | | | | | |
| 6 risk-model setting | | | | | |
| 7 Tester build 3 | | | | | |
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

### Part B — beta automatic cleanup (`PISTON_AUTO_CLEANUP: build-opt-in`)

Not started at the time of this entry; next in this stage.
