# CrystalCombat port progress — Minecraft 26.1.2 (T1)

Port of CrystalCombat from the Minecraft 26.2 tree. Source of every copied file is the
26.2 commit recorded as `PORT_BASE` below; no file is copied from the 26.2 working tree.

| Field | Value |
|---|---|
| Target tree | `26.1.2/` (git worktree, branch `26.1.2`) |
| Minecraft | 26.1.2 |
| Java | 25 (toolchain) |
| Meteor build | 26.1.2-20260810.135545-42 (pinned `gradle/libs.versions.toml`) |
| PORT_BASE (26.2 source commit) | `363b410fa945e361c707b997a97556d0e5ef7398` |
| Baseline before any change | `./gradlew test` — 1660 tests, 0 failures, 0 errors, 0 skipped (2026-11-01) |
| Pre-existing uncommitted files | none (`git status --short` empty at start) |

## Stages

| Stage | Status | Commit | Tests | Review |
|---|---|---|---|---|
| 1. Desk audit | DONE | `1137fa1`, `e47b6c2`, `5d2abd3` | — | round 1 BLOCKING (suppression surface) → fixed doc-only in `5d2abd3`; re-review PASS |
| 2. Pure core | DONE | `677b52b` | 2313 tests, 0 failures | PASS WITH NOTES; notes applied (trace README piston wording, 26.2-only regeneration commands) |
| 3. Minecraft/Meteor adapters | DONE | `b3dab25` | 246 mc tests, 0 failures | self-check |
| 4. Meteor suppression + capability checks | DONE | `b9dc5be` | 25 capability/suppression tests, 0 failures | self-check |
| 5. Module, settings, tester tooling | DONE | `f7d5c04` | 265 module/tester tests, 0 failures | self-check; motion-prediction and hurt-sync disabled (audit §6.2) |
| 6. Docs | DONE | `b4a09ec` | docs check PASS | self-check |
| 7. Tester build | DONE | `b4a09ec` (clean tree) | `build` + `sourceDistribution` succeeded | manifest and build stamp verified |
| 8. Target report | DONE | this commit | 2651 tests, 0 failures, 0 errors, 0 skipped | independent whole-branch review: PASS (no Critical/Important; two status-doc gaps fixed here) |

## Final report (stage 8)

### Commits

| Stage | Commits |
|---|---|
| 1. Desk audit | `1137fa1`, `e47b6c2`, `5d2abd3`, `6fd2b38` |
| 2. Pure core | `677b52b`, `50cc85c` |
| Plan copy (C1 action) | `1f27b8d` |
| 3. Minecraft/Meteor adapters | `b3dab25` |
| 4. Suppression + capability checks | `b9dc5be` |
| 5. Module, settings, tester tooling | `f7d5c04` |
| 6. Docs | `b4a09ec` |
| 7–8. Build and report | this commit |

All commits are local to branch `26.1.2`; nothing was pushed.

### Tests

- Full `./gradlew test --offline`: **2651 tests, 0 failures, 0 errors, 0 skipped**.
- Baseline before the port: 1660 tests, 0 failures.
- `verifyCombatPurity` ran in `check` during the tester build and passed.
- `python3 scripts/check_docs.py`: PASS (README links, local links/fragments, module-guide coverage).

### Review verdicts

- Stage 1 audit: round 1 BLOCKING (suppression surface) → fixed doc-only in `5d2abd3` → re-review PASS.
- Stage 2 pure core: PASS WITH NOTES; notes applied (trace README piston wording, 26.2-only regeneration commands).
- Stages 3–8: independent whole-branch review PASS — no Critical or Important findings. The review verified the audited suppression surface, the bytecode-level capability gate, the 26.1.2/build-42 descriptor set, the single dispatch send path, the two version-gated features, and the bytecode-pinning tests against the real pinned classes. Its only items were the two stale status documents, corrected by this commit.

### Differences from 26.2

The stage 1 table audits 55 items: **27 SAME**, **26 DIFFERENT**, **1 UNCONFIRMED**.

- The 26 DIFFERENT rows are naming/API-shape changes with the same behaviour (knockback call shapes, `EntityTypes`→`EntityType`, `ServerExplosion`/`EndCrystal` constant references, the T1 `ServerEntity` position gate, `mc.gui.screen()`→`mc.screen`, Meteor build-35→build-42 class bytes).
- The 1 UNCONFIRMED row is the numeric absorption entity-data id; all adapter code resolves it through `Player.DATA_PLAYER_ABSORPTION_ID`, never a literal.
- Blocking differences handled: `EntityTypes`→`EntityType` hazard, T1 knockback shapes, `mc.screen`, the T1 position gate, the plan copy, and the rule that 26.2 traces are not T1 evidence.

### Features disabled on this version

- `motion-prediction` — behaviour depends on the unvalidated stronger-hit knockback/motion model (audit §6.2).
- `hurt-sync` — same dependency (audit §6.2).

Both settings stay visible, their descriptions say **not available on Minecraft 26.1.2**, and activation is refused (including a mid-session toggle, which disables the module). The piston trap remains available: its unconfirmed per-tick displacement (§6.4) is not used to choose the attack entity, which is UUID-verified, and every push stage waits for block confirmation.

### Tester build

| Field | Value |
|---|---|
| Jar | `build/libs/comet-0.27.0-tester.3-mc26.1.2.jar` |
| Source zip | `build/distributions/comet-0.27.0-tester.3-mc26.1.2-source.zip` |
| Manifest version | `0.27.0-tester.3` |
| Manifest commit | `b4a09ec` (no `-dirty`; tree clean at build time) |
| Minecraft | `26.1.2` |
| Meteor build | `26.1.2-20260810.135545-42` |
| Tester marker | `Comet-Tester-Build: true`, `Comet-Tester-Suffix: tester.3` |
| Embedded stamp | `version=0.27.0-tester.3`, `commit=b4a09ec`, `tester=tester.3` |

### Rulings and decisions

- **No synthetic trace variants.** The audit found no fixture depends on an order/timing difference, so fabricating a T1 variant would be speculation; the 26.2 fixtures are used read-only and the README says so.
- **Piston trap kept enabled.** §6.4 leaves the measured per-tick displacement unconfirmed, but the controller confirms every block and UUID-verifies the attacked crystal, so the prediction is not load-bearing for safety. The beta label remains.
- **Only motion prediction and hurt-sync disabled.** They are the two features whose decision model directly trusts the unconfirmed stronger-hit motion behaviour (§6.2); the health/absorption ordering (§6.3) is handled by the observation layer, and the absorption id is resolved through accessors.
- **Ported 26.2 mc tests were adapted with fixture aliases** (`budget`/`crystals`/`adapter`) and a `breaking()` accessor rather than renaming the re-implemented target fixture, so the 26.2 assertions run unchanged.
- **No combat harness/gametest port.** Stage 2 forbids launching the harness and the user's stage 3–8 scope does not include it; traces therefore stay read-only.

### Residual uncertainty (audit §6)

The stronger-hit runtime behaviour, the health/absorption co-delivery order, the piston per-tick displacement, and whether Meteor's `CrystalAura` bookkeeping resumes cleanly after suppression cancellation are all still unconfirmed for 26.1.2. They are live-testing items in the [tester checklist](../testers/checklist.md), not silent assumptions.


## Notes

- Nothing pushed; all commits local to branch `26.1.2`.
- No dependency or vendored-Meteor changes are permitted; stop and report if one appears needed.

## Later parity pass — 2026-11-01

See [the three-version parity report](version-parity-2026-11-01.md) for the current
verification totals, adapter corrections, tester builds and compatibility exceptions.
The earlier stage report above is retained as historical evidence.
