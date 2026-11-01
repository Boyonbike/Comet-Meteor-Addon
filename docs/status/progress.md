# CrystalCombat completion — progress log

Orchestrated run against `docs/superpowers/plans/2026-10-29-crystal-combat-v2.1.md`.
Repository: `~/Desktop/Comet Metior Addon/26.2` (branch `26.2`, common git dir `../26.1.2/.git`).

| Stage | Status | Date | Commits | Tests | Reviewer |
| --- | --- | --- | --- | --- | --- |
| 1 Secure the work | DONE | 2026-11-01 | see below | 2620 / 276 suites, 0 failures | orchestrator spot-check |
| 2 Plan and docs match reality | DONE | 2026-11-01 | `01d0e75` | 2620 / 276 suites, 0 failures | PASS after 1 fix round |
| 3 Phase 6 gaps | | | | | |
| 4 Phase 5 test gaps + auto-cleanup | | | | | |
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
