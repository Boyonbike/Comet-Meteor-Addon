# Where we are — CrystalCombat, 1 November 2026

Audit of `~/Desktop/Comet Metior Addon`, 2026-11-01 09:40 UTC (Europe/London).
Read-only: no commits, no worktree changes, no Gradle, no client or server launch.
`git fsck` was run without `--lost-found` because that flag writes to `.git/lost-found`.

## 1. Summary

The latest phase whose implementation actually landed is Phase 5 (single-push piston), built on 29–30 October and packaged as tester build 2 on 30 October; Phase 6 (hybrid direct + piston) is the phase actually in progress, with its architecture and wiring present but behaviour/test gaps open. No phase since Phase 2 is closed by its own exit gate: Phase 3's 100-cycle live run and paired Meteor comparison have never happened, Phase 4's two remaining items are blocked on a Speedmine contract that does not exist, and Phase 5's local-server 8 layouts × 2 power kinds gate has not run. The dominant risk is custody, not code quality: the whole combat implementation — 223 files and 42,877 lines under `core/combat`, 430 untracked paths containing `combat`, 472 untracked files in total — exists only in the 26.2 working tree. It is in no commit, branch, stash, dangling object, remote or other worktree, and the 31 October release jar was built from it. A reset, a `git clean`, or a lost worktree destroys work that git cannot restore; the only external copies are 34 Claude edit-history backups covering 26 recorder-era classes, with no engine, planner or module among them.

## 2. Where the work is

**Verdict: no other copy of the combat implementation exists.** All three worktrees exist on disk; none is locked or prunable; `git worktree prune -n -v` reports nothing; there are no stashes; and no branch, dangling commit, agent ref, archive or stray directory contains `CrystalCombat`, `CombatEngine`, `PistonPlanner` or any `core/combat` source.

### Worktrees (`git worktree list --porcelain`, run from `26.1.2`)

| Path | Branch | HEAD | On disk | `git status --short` | Uncommitted detail | Holds work missing from 26.2 |
|---|---|---|---|---:|---|---|
| `26.2/` | `refs/heads/26.2` | `fb59152` | yes | 48 | 17 modified + 472 untracked files (31 untracked entries) | n/a — this *is* 26.2, and it holds the only copy of the combat work; none of it is committed |
| `26.1.2/` | `refs/heads/26.1.2` (main) | `9901c53` | yes | 13 | 11 modified + 2 untracked | Yes, but not combat: 20 commits unique to the 26.1.2 line and 3 plan docs. Its 2 untracked Elytra files are byte-identical to 26.2's, and its 11 modified files are also modified in 26.2 |
| `1.21.11/` | `refs/heads/1.21.11` | `7d1fcd9` | yes | 13 | 11 modified + 2 untracked | Yes, but not combat: 17 commits unique to the 1.21.11 line, plus version-specific `compat/BoatInteractionCompatibility.java` and `vendor/`. Its 2 untracked Elytra files are byte-identical to 26.2's |

The common git dir is `26.1.2/.git`; `26.2/.git` and `1.21.11/.git` are gitdir files into `26.1.2/.git/worktrees/`. `./.git` at the workspace root is an empty directory, not a repository.

### Branches, refs and stashes

| Ref | Commit | Date | Commits not in 26.2 | Verdict |
|---|---|---|---:|---|
| `26.2` | `fb59152` | 2026-10-29 09:25 | 0 | local branch; 6 commits ahead of `origin/26.2` (`0433e29`), 0 behind |
| `origin/26.2` | `0433e29` | 2026-10-29 09:25 | — | remote is 6 docs commits behind local; nothing pushed since 29 Oct |
| `26.1.2` | `9901c53` | 2026-10-29 08:50 | 20 | different Minecraft version line; all parity/release/Highway work; no combat |
| `1.21.11` | `7d1fcd9` | 2026-10-29 08:50 | 17 | different version line; no combat |
| `codex/comet-scaffold-presets` | `b830f5f` | 2026-10-25 18:25 | 11 | all 11 are on the 26.1.2 line; the ref adds no unique work and predates combat |
| `release/0.25.15` | `4ba0141` | 2026-10-23 | 0 | ancestor of 26.2; upstream `origin/release/0.25.15` is gone |
| `refs/cline/checkpoints` (5) | `5f9ae5d` … `ed99a26` | 2026-10-23 | — | autosave WIP snapshots, 737 files each, no combat paths |
| `refs/codex/turn-diffs/checkpoints` (38) | tree objects | 2026-10-18 … 10-25 | — | 115–205 files each, no combat paths |
| tags `comet-0.27.1-Bugfuxes`, `0.27.0-release`, `Release` | `9901c53`, `ed9e32a`, `b13fe2d` | Oct | — | all on the 26.1.2/release lineage; none reachable from 26.2 |
| **stashes** | — | — | — | `git stash list` is empty; `refs/stash` does not exist |

Remotes other than origin are stale: `tnt-coating/26.1.2` (`7a903fa`) is 20 behind local 26.1.2 and `tnt-coating/1.21.11` (`8de34f5`) 16 behind local 1.21.11. Merge bases: 26.2/26.1.2 at `7a903fa` (23 Oct), 26.2/1.21.11 at `17e3199` (23 Oct).

### Dangling objects and reflog

`git fsck --no-reflogs` finds **8 dangling commits, 41 dangling trees, 5 dangling blobs**, none reachable from any ref and **none containing combat**:

- `9903f7f`, `6313f7a`, `2a9f405` — cline checkpoint autosaves, 23 Oct.
- `50c48e4` — WIP on `bd94565`, 21 Oct, README + `build.gradle.kts` only.
- `0d5ff53`, `c033945`, `29be536` — three variants of the 23 Oct licensing rewrite, superseded by reachable commits.
- `73ae501` — pre-amend duplicate of the 1.21.11 Highway Fly consolidation (25 Oct), differs from `17529cc` by +144 test lines.

The 41 dangling trees have 0 paths matching `core/combat|CrystalCombat|CombatEngine|PistonPlanner|combat`. The dangling blobs are old scaffold sources plus `b2138603…`, which is exactly `26.2/1.txt` (21,979 B). The reflog since 28 Oct has 42 entries, all ordinary commits or push updates: no reset, checkout, stash, branch delete or rebase.

### Commits since the project started (`git log --all --since=2026-10-28`)

18 commits, all on 28–29 October. There is **no commit at all dated 30 or 31 October**, although the working trees were heavily changed on those days:

| Date (UTC) | Commits | What |
|---|---|---|
| 10-29 09:06–09:25 | `0cd2e5f`, `c56ba49`, `281ad4c`, `d9fca5b`, `ca3c36d`, `fb59152` | CrystalCombat audit, plan, rulings — 6 unpushed docs commits on 26.2 |
| 10-29 08:50 | `7d1fcd9`, `9901c53`, `0433e29` | same Highway Fly / Elytra fix on 1.21.11, 26.1.2, origin/26.2 |
| 10-28 13:51 | `ed9e32a`, `2ea0bb4`, `18c96c2`, `48a458d`, `1318c72`, `3c4e667` | boat automation docs; Discord IGN relay |
| 10-28 12:49 | `45c7e84`, `49c4433`, `dc8386f` | release 0.26.27 |

### Stray copies and other places work lives

| Location | What it is | Holds missing work? |
|---|---|---|
| `~/.claude/file-history` | 34 hashed edit-history files (6,560 lines) from 4 sessions, 29 Oct 09:52–15:54; 26 `core/combat` classes (recorder, trace log/codec, observation kinds, session, ledger, TraceReplayer, mixins, tests) | No. All 26 still exist in 26.2; 13/34 are byte-identical; there are no backups of `CombatEngine`, `CrystalCombat` or `PistonPlanner` |
| `~/Desktop/2026-10-29-crystal-combat-v2.1.md` | older plan copy, 97,378 B, 29 Oct 09:13 | No — superseded by 26.2's 111,220 B working copy (30 Oct 14:10) |
| `~/Desktop/Comet Metior Addon.zip` | 16 Oct archive, 381 entries | No — pre-combat; 0 combat/crystal entries |
| `docs/` at workspace root (not a repo) | 10 plans/specs with no version tree, including `plans/2026-10-31-highway-elytra-hardening.md` (2,271 B) | Yes, as documentation: the 31 Oct hardening plan exists only here |
| `26.1.2/docs/superpowers/plans/` | `2026-10-24-comet-scaffold.md`, `2026-10-25-highway-safety.md`, `2026-10-25-release-parity.md` | Yes, as documentation: these 3 plans were never added to 26.2 or 1.21.11 |
| `1.21.11/` only | `compat/BoatInteractionCompatibility.java` + test, `vendor/` Meteor 82 | Version-specific, not portable; no combat |
| `reports/` (394 files), `research_notes/` (25) | combat reports, external Meteor reference sources, non-combat backups | No source: reports/research only, no Comet combat Java |
| `26.2/1.txt` | 422-line Claude Code TUI transcript, 28 Oct 12:25 | No — untracked session artefact that should not be committed |

A home-wide `find` for `-name combat -path '*dev/tntcoating/core/*'` returns only 26.2 paths (`src/main`, `src/test`, `bin/*`, `build/classes/*`); a named-file search for `CombatEngine.java`, `CrystalCombat.java`, `PistonPlanner.java`, `CombatRecorder.java` and `CrystalAura.java` across the workspace, `/tmp`, `/var/tmp`, `~/Desktop` and `~/.claude` finds only the 26.2 copies. All 26 `.zip`/`.jar` archives under `reports/` were listed and none contains combat packages. There is no `.claude/worktrees` anywhere in the workspace.

### Agent artefacts (names/dates only)

- `~/.claude/projects/-home-boyonabike-Desktop-Comet-Metior-Addon`: 15 `.jsonl` transcripts + `memory/{MEMORY.md,build-environment.md,three-version-porting.md}`, 28 Oct 12:26 → 29 Oct 16:21 (largest 17.9 MB).
- `…-Comet-Metior-Addon-26-1-2`: 11 transcripts + 4 memory files, 21–23 Oct; an older `…-26-1-2-Comet-Meteor-Addon` path has 1 transcript, 21 Oct.
- `~/.claude/file-history`: 955 files / 17 MB, 86 modified since 28 Oct, 34 combat-class backups.
- `~/.codex/sessions`: 58 files since 28 Oct (24 on the 28th, 20 on the 29th, 0 on the 30th, 11 on the 31st, 3 on 1 Nov); 20 mention combat paths, 3 mention `ElytraSwapRecovery`.
- `~/.dsh/sessions`: 3 workspace-related dirs, all 23 Oct, no 26.2 dir. `~/.cc-safety-net/logs`: last 26.2 activity 30 Oct 12:27.
- `26.1.2/.claude/`: only `settings.json`, 23 Oct. `26.2/1.txt`: transcript, 28 Oct.
- No TODO, handoff or notes file under the workspace modified since 28 Oct.

## 3. Uncommitted work in 26.2

`git status --short` gives 48 lines: **0 staged, 17 modified tracked files, 31 untracked entries expanding to 472 files**. `git status --short -uall` gives 489 lines (17 + 472). `git diff --stat` over the 17 tracked files: 972 insertions, 219 deletions. Last commit `fb59152` is **72.2 hours old** (29 Oct 09:25:38 UTC).

### Untracked combat code

| Path | Files | Lines |
|---|---:|---:|
| `src/main/java/dev/tntcoating/core/combat/` (incl. `mc/`) | 138 | 22,023 |
| `src/test/java/dev/tntcoating/core/combat/` (incl. `mc/`) | 85 | 20,854 |
| **`core/combat` total** | **223** | **42,877** |
| `src/test/resources/combat/` | 181 (60 `.json`, 120 `.jsonl`, 1 `.md`) | — |
| `src/gametest/` | 32 (30 Java + 2 resources) | — |
| `src/main/java/dev/tntcoating/commands/` | 2 | — |
| `src/main/java/dev/tntcoating/core/tester/` + test mirror | 3 + 3 | — |
| `docs/testers/`, `docs/validation/` | 3 + 3 | — |

The exact command in the brief gives **22,023 lines in `src/main`** and **20,854 in `src/test`** for paths matching `*combat*/*.java`. Broader "Combat"-cased naming adds ~3,700 lines (`CrystalCombat.java` 695, `CombatCapabilities`, mixins, tests), for ~46,600 lines of combat-named Java. 430 untracked paths contain `combat`.

Other untracked files that matter:

- **`src/main/resources/comet-build.properties`** — required build input: `build.gradle.kts:142` expands it, yet it is untracked (a tracked sibling `comet-scaffold-build.properties` exists). Losing it breaks the build.
- `modules/CrystalCombat.java` (695 lines), `modules/CombatRecorderModule.java`, 4 combat mixins (`CombatConnectionHooksMixin`, `CombatPacketHooksMixin`, `CombatLivingEntityDataAccessor`, `CombatPlayerDataAccessor`), `compat/CombatCapabilities.java`, `core/ElytraSwapRecovery.java`.
- 5 root test files, `scripts/combat_trace_report.py`, `docs/modules/{crystal-combat,combat-recorder}.md`, the trace report, `1.txt`.

### Modified tracked files (17)

- **Build/config (2):** `build.gradle.kts` (+127), `gradle/libs.versions.toml`.
- **Source (6):** `TntCoatingAddon.java`, `gui/ChatToolsScreen.java`, `mixin/CrystalAuraMixin.java`, `modules/ChatTools.java`, `modules/HighwayFly.java`, `resources/comet.mixins.json` (the 4 combat mixins are registered at lines 51–54).
- **Docs (9):** `CHANGELOG.md` (+23), `CONTRIBUTING.md`, `README.md`, `THIRD_PARTY_NOTICES.md`, `docs/README.md`, `docs/guides/building.md`, `docs/modules/chat-tools.md`, the audit doc (+160), the plan (**445 changed lines**).

Two consequences:

1. The plan and audit documents themselves have large uncommitted edits. The box counts below describe the working copy, not `HEAD`. The plan's last commit is `ca3c36d` (29 Oct 09:25); its 30 October Phase 5 status note and the other post-commit edits (445 changed lines in total) exist only on disk.
2. The 31 October release jar contains 291 `dev/tntcoating/core/combat/*` entries. The shipped artifact includes code that git has never seen, so it is not reproducible from `HEAD`.

## 4. Tests and builds

### Tests

Parsed from `build/test-results/test/*.xml` (no Gradle run — sources have not changed since the results):

| Suites | Tests | Failures | Errors | Skipped | Run window |
|---:|---:|---:|---:|---:|---|
| 276 | 2,620 | 0 | 0 | 0 | 2026-10-31T16:08:19.964Z – 16:08:28.704Z |

Newest result file is `TEST-dev.tntcoating.core.combat.mc.CombatDamageAdapterTest.xml` (31 Oct 16:08:29). `find src -newer <that file>` returns 0 files, so the recorded run is current and **Gradle was not re-run**. Combat scope is 86 suites / 956 tests, all passing. Caveats: these are JUnit results only — the client gametest suite has no XML output, and no live-server combat test has ever been recorded. 288 test `.java` files exist: 276 suites plus 12 shared helpers.

### Builds

| Artefact | Size | Built | Manifest |
|---|---:|---|---|
| `libs/comet-0.27.0-mc26.2.jar` | 2,277,751 B | 31 Oct 16:08:17 | `Comet-Version: 0.27.0`, `Comet-Git-Commit: fb59152-dirty`, tester false, suffix empty |
| `libs/comet-0.27.0-tester.2-mc26.2.jar` | 2,218,697 B | 30 Oct 12:36:22 | `0.27.0-tester.2`, `fb59152-dirty`, tester true, suffix `tester.2` |
| `libs/comet-0.27.0-tester.1-mc26.2.jar` | 2,050,912 B | 29 Oct 22:40:35 | `0.27.0-tester.1`, `fb59152-dirty`, tester true, suffix `tester.1` |
| `distributions/comet-0.27.0-mc26.2-source.zip` | 2,727,879 B | 30 Oct 12:24:35 | — (predates the 31 Oct jar) |
| `distributions/comet-0.27.0-tester.2-mc26.2-source.zip` | 2,732,370 B | 30 Oct 12:39:25 | — |
| `distributions/comet-0.27.0-tester.1-mc26.2-source.zip` | 2,566,160 B | 29 Oct 22:40:43 | — |

All three jars report the same commit, `fb59152-dirty`, and only the version/suffix distinguish them; none corresponds to a commit. The release source zip is from 30 October — before the 31 October rebuild — so it does not match the release jar. Toolchain: Gradle wrapper 9.6.1, JDK 25 toolchain, `build/` is 114 MB.

## 5. Plan progress

Counts are from the working copy of `docs/superpowers/plans/2026-10-29-crystal-combat-v2.1.md`: **160 boxes ticked, 127 open**, plus 9 open boxes in the later "Risks, open questions and decisions" section (not part of any phase).

| Phase | Ticked | Open | Verified implemented | Genuinely missing | Blocked | Unclear | Assessment |
|---|---:|---:|---:|---:|---:|---:|---|
| 0 — API audit + recorder | 0 | 44 | n/v | 1 (+1 partial) | 0 | 0 | Everything delivered (audit, recorder, 60 traces, Q1–Q7), but none of the boxes were ticked and two trace-plan requirements are unmet |
| 1 — Session, ledger, models | 49 | 0 | — | 0 | 0 | 0 | No open boxes; code and tests present |
| 2 — Scheduler, runtime, suppression | 31 | 0 | — | 0 | 0 | 0 | All ticked, but 2b and 2c are overstated (see drift) |
| 3 — Verified direct baseline | 38 | 2 | 0 | 2 | 0 | 0 | Only the two live gates remain |
| 4 — Advanced direct tactics, defence | 26 | 2 | 0 | 0 | 2 | 0 | Code-complete except the two Speedmine items |
| 5 — Single-push piston | 3 | 38 | 31 | 2 | 0 | 5 | Implementation built as tester build 2; boxes never updated |
| 6 — Hybrid with footprint concurrency | 13 | 4 | 1 | 2 | 1 | 0 | Architecture present; in progress |
| 7 — Gated experiments | 0 | 14 | 0 | 0 (14 deferred) | 0 | 0 | Not started, deferred by the dated evidence/latency gates |
| 8 — Module, UI, lifecycle | 0 | 7 | 0 | 7 | 0 | 0 | Partial groundwork only |
| 9 — Validation and release prep | 0 | 11 | 0 | 11 | 0 | 0 | Preparation only |
| 10 — Version ports | 0 | 5 | 0 | 5 | 0 | 0 | Not started; no combat code in 26.1.2 or 1.21.11 |

Box-by-box verification was done for every open box in Phases 3–6 (the scope in the brief): **of 46 open boxes, 32 are actually implemented, 6 genuinely missing, 3 blocked and 5 unclear**. Phase 0's assessment comes from artefact inspection, not 44 individual checks; Phases 7–10 come from code search. "n/v" means not verified box-by-box.

### The 14 open items that are not implemented

| Phase | Item | Verdict | Evidence |
|---|---|---|---|
| 3 | 100 consecutive verified cycles on an authorised local server, zero violations | Missing | `plan:813`; `combatHarness` task exists (`build.gradle.kts:240`) but no run; `docs/validation/crystal-combat-meteor-parity.md:305-307` repeats that it is open |
| 3 | Paired AB/BA comparison against Meteor CrystalAura | Missing | `plan:814`; no result table anywhere; parity doc `:440` says it "is not run" |
| 4 | Speedmine reservation on one surround block, owner-scoped request + cancel | Blocked | `plan:854`; `SpeedminePolicy.java` / `SpeedmineTargeting.java` expose predicates only — there is no request/cancel contract |
| 4 | Mine the power source (`MINE_POWER`) | Blocked | `plan:881` records it as blocked, not attempted; `Action.java:17` declares it but no planner emits it; tests assert it is never chosen |
| 5 | Cleanup uses ledger ownership; auto-cleanup only for `CONFIRMED_OURS` | Missing | `PistonSequence.cleanupAction` is decision-only with no caller under `mc/`; `PistonTrapController.java:575-577` is a no-op ("automatic cleanup/mining is out of scope"); `CleanupPriority.java:21-33` records the gap |
| 5 | Local server, 8 layouts × 2 power kinds, ≥19/20 cycles, zero violations | Missing | No server run; the trace corpus has one push-only run per layout; the module doc states no client or server was launched |
| 5 | Test: happy path replayed from piston traces | Unclear | `PistonSequenceTest.happyPathFollowsTheExactOrderedDependencies` is synthetic; piston fixtures are replayed only at ledger level in `CrystalLedgerTraceReplayTest` |
| 5 | Test: swept mid-path point more exposed than either endpoint | Unclear | max-over-path exists in `PistonPlanner`, but no test isolates a mid-path exposure (hull tests are endpoint/lower-bound) |
| 5 | Test: target pushed vertically out of a hole by an outside explosion | Unclear | Envelope-abort tests exist; no explosion-push-vertical scenario |
| 5 | Exit: all tests pass | Unclear | 2,620 pass in the 31 Oct XML, but not re-run in this audit; CHANGELOG claims the offline suite passes |
| 5 | Exit: unsupported layouts listed explicitly in module docs | Unclear | The doc lists the 16 beta layouts and limits, but no explicit unsupported list |
| 6 | Cleanup intents share budgets; 20 ticks → next free slot | Blocked | `CleanupPriority.STARVATION_TICKS=20` exists, but there is no cleanup-intent origin and no owner-scoped mining API; `HybridEngineTest.cleanupWaitReachesTheStarvationDeadlineInServerTicks` tests a synthetic clock |
| 6 | Surrounded target where direct faceplace wins → direct wins | Missing | Ranking exists (`CombatChoice.java:39`), but no scenario or test covers the direct-wins case |
| 6 | Missing piston materials → piston plan absent with a visible reason | Missing | The planner has no inventory input; `PistonTrapController.dispatchStructural` reserves and then fails at dispatch; no test |

The 32 implemented items are mostly Phase 5: geometry and legality (`PistonGeometry`), planner candidate cap 64, measured 1.02/0.51 push rule, stage timeouts, structural retries, ambiguous-spawn quarantine, envelope aborts, computed-position damage check, deadlines that never retire risk, contested-block handling, disable/death behaviour, and the ~65 named piston tests. Phase 6's footprint concurrency gate (`FootprintConflict.java:69-81`, six rows) is also implemented and ships off.

### Boxes that are wrong in both directions (drift)

- **Phase 0 shows 0/44 but the deliverables exist**: audit doc, recorder + 3 named tests, 60 fixtures, trace report answering Q1–Q7. The real gaps are the 0/40/100 ms × 20/15/10 TPS matrix (never run) and piston layouts recorded once instead of five times; the trace report self-scores 8 Pass / 1 Partial / 1 Fail.
- **Phase 5 shows 3/38 but ~31 are implemented** and the module registers the controller (`CrystalCombat.java:576-579`), contradicting the phase's own header note that "the production module does not register it yet".
- **Phase 2b** mandated reuse of `PlacementTracker`, `ServerPacer` and `PacketBudget`; there are zero references in `core/combat` or `CrystalCombat`, and packet counting is re-implemented in `CombatScheduler.packetCapacity`/`packetUsed(int)`.
- **Phase 2c's "integration test"** (`CrystalCombatGateTest.java:151-166`, `bothModulesEnabledSendsNothingOverTwoHundredTicks`) is a 200-iteration synthetic loop, not an in-game integration test. The mixin wiring it claims to cover is real (`CrystalAuraMixin.java:43-123`, descriptor-checked by `MeteorUpgradeCheck.java:92-93,114-126`).
- `docs/testers/README.md` and `CHANGELOG` say Hybrid "still refuses to enable", while `CrystalCombat.settingsRefusal` accepts HYBRID and `CrystalCombatSettingsTest.betaPistonOnlyIsAcceptedAndHybridStillRefused` asserts it is accepted.
- Phase 6.5 ticks "defensive break allowed only if the engine first invalidates the trap" although no invalidation API exists; the break is impossible rather than gated.
- Phase 4's `**Files:**` list names a non-existent `DirectTacticsTest` and places `CombatInvalidationTest` at `core/combat` when it is under `core/combat/mc/`.
- `docs/README.md` does not index `docs/superpowers/audit/`.

### Which phase is really complete?

**Phase 2 is the last phase with every box ticked**, and even that closure is overstated by the two deviations above. **Phase 5 is the latest phase whose implementation landed end-to-end**, but it is not closed: one code gap, three test gaps and the live-server gate remain. **Phase 6 is the phase in progress**; Phase 3's and Phase 4's exit criteria have been open since 29 October.

## 6. Tester feedback

**No feedback has come back.** There is no tester report, no completed checklist, no debug export and no round-results file anywhere in the workspace.

- `26.2/docs/testers/` contains only the templates: `README.md` (11,265 B, 30 Oct 12:37), a blank `feedback-template.md` (2,430 B, 30 Oct 12:22) and `checklist.md` (41,695 B, 30 Oct 13:45) with **zero checked or unchecked checkboxes**.
- `26.2/docs/validation/` has three internal documents (`harness.md`, `crystal-combat-ablation-plan.md`, `crystal-combat-meteor-parity.md`) and nothing from a tester. The `tester-round-<N>.md` result file that `crystal-combat-ablation-plan.md:4-5` expects does not exist.
- No `crystalcombat-*.zip`, no `comet/exports/` directory, and no export manifest outside `build/`. The only archives created since the testing round are Gradle source zips.
- Client logs are empty (gzipped logs 125–127 B; `latest.log` 241 B) and `26.2/run/` has not been touched since 23 October, so nothing was played from this checkout.
- The last combat change is 30 Oct 14:10 and tester build 2 was built at 12:36 — roughly 43–45 hours of silence. Any later changes are the 31 Oct Highway/Elytra work.
- **Delivery gap:** `docs/testers/README.md:184` tells testers to send the template plus the export zip but names no destination — no folder, host, issue tracker or Discord. Exports land in the tester's own game directory under `comet/exports/crystalcombat-<timestamp>.zip` (`README.md:132`), which no one has collected.

So: no written feedback, and no channel defined to receive it. A verbal/Discord report is the one thing this audit cannot rule out. The parity doc already says the piston trap is "not tester-ready" (`:399-400`) and the paired comparison "is not run" (`:440`).

## 7. Recommended next steps

Nothing below has been carried out.

1. **Commit and push the 26.2 combat work today, before any cleanup or pruning.** It is the only copy. Suggested split: (a) `core/combat` main + `mc/`, (b) tests, (c) trace fixtures, (d) mixins/module/wiring + `build.gradle.kts` + `comet.mixins.json`, (e) gametest/commands/docs. Then push to `origin/26.2` (currently 6 commits behind). Do not run `git clean`, `git reset --hard`, `git worktree prune` or `git gc` until this is done.
2. **Decide the two special untracked files while committing:** add `src/main/resources/comet-build.properties` (the build expands it at `build.gradle.kts:142`) or generate it in Gradle; and keep `1.txt` (a session transcript) out of the repository.
3. **Commit the 31 October Elytra/Highway hardening in all three worktrees.** The same 11 modified + 2 untracked files sit uncommitted in 26.2, 26.1.2 and 1.21.11; the two untracked files are byte-identical everywhere. Leaving it three-way uncommitted invites divergence.
4. **Rebuild from a clean commit and regenerate the source zips.** All three jars say `fb59152-dirty`, the release source zip predates the release jar by about 28 hours, and there is no way to tell which code a jar came from. After committing, rebuild so the manifest carries the real commit, and rebuild the source distribution.
5. **Bring the plan back in line with reality.** Tick Phase 0 and Phase 5 from the evidence, record the Phase 2b/2c deviations, and either implement or explicitly mark the 14 non-implemented items. As it stands the plan cannot be used to judge progress.
6. **Run the three live gates that actually block closure**, on an authorised local server: the Phase 0 delay × TPS matrix (the one Fail), the Phase 3 100-cycle zero-violation run, and the Phase 5 8 layouts × 2 power kinds ≥19/20 gate. Record outputs next to the existing trace report.
7. **Fix the tester route before recruiting testers.** Name a destination for feedback and exports in `docs/testers/README.md`, ask the build-2 testers for their exports (nothing has arrived), then build and hand out tester build 3 only after step 4 so the jar is identifiable.
8. **Then close the remaining code gaps or mark them blocked:** auto-cleanup with ownership (Phase 5.4), the two Phase 6 missing behaviours (direct-wins scenario, missing-materials reason), the five unclear Phase 5 tests, and decide formally that Speedmine reservation and `MINE_POWER` are blocked pending an owner-scoped API.
9. **Housekeeping, after the above:** delete `codex/comet-scaffold-presets` (its 11 commits all exist on 26.1.2), retire `release/0.25.15` (upstream gone), refresh or abandon the stale `tnt-coating` mirror (20/16 commits behind), port the three 26.1.2-only plan documents, and decide whether the 10 workspace-root `docs/` plans — especially the 31 October hardening plan — belong in the repo.

---

### Method notes and limits

- Read-only throughout: no file inside the repo was modified except creating this report; the only writes were under the report directory and temporary files in `/tmp` used by the worktree sub-audit and deleted immediately.
- Gradle was not run: the recorded test run (31 Oct 16:08) is newer than every source file, so there was nothing new to test and launching Gradle was unnecessary under the read-only brief.
- No Minecraft client or server was launched; live-server behaviour is therefore unverified here, as it is in the plan's own gates.
- `git fsck --lost-found` was intentionally replaced by `git fsck --no-reflogs` to avoid writing `.git/lost-found`.
- Time figures are as of 2026-11-01 09:40 UTC. The last 26.2 commit was `fb59152` at 2026-10-29 09:25:38 UTC, 72.2 hours earlier.
