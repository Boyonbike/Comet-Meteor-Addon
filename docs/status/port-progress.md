# CrystalCombat port progress — Minecraft 1.21.11 (T2)

**Historical port report:** tester.4 subsequently unlocked motion prediction and
hurt-sync on all versions for testers. Any disabled-feature statements below describe
tester.3. See [current tester notes](../testers/tester-4-handout.md).


Port of CrystalCombat from the Minecraft 26.2 tree. Source of every copied file is the
26.2 commit recorded as `PORT_BASE` below; no file is copied from the 26.2 working tree.
T2 runs after T1 (26.1.2); T1's committed work may be read as a reference, but every
Minecraft/Meteor-facing line is taken from T2's own audit of the pinned build 82.

| Field | Value |
|---|---|
| Target tree | `1.21.11/` (git worktree, branch `1.21.11`) |
| Minecraft | 1.21.11 |
| Java | 21 (toolchain) |
| Meteor build | 1.21.11-82 (vendored under `vendor/`) |
| PORT_BASE (26.2 source commit) | `363b410fa945e361c707b997a97556d0e5ef7398` |
| Baseline before any change | `./gradlew test` — 1664 tests, 0 failures, 0 errors, 0 skipped (2026-11-01) |
| Pre-existing uncommitted files | `docs/status/` untracked (this port's progress scaffolding); no tracked modifications |

## Stages

| Stage | Status | Commit | Tests | Review |
|---|---|---|---|---|
| 1. Desk audit | DONE | `9b7f70b` | See final parity verification | See parity report |
| 2. Pure core | DONE | `6d72ac4` | See final parity verification | See parity report |
| 3. Minecraft/Meteor adapters | DONE | `fb30aa9` | See final parity verification | See parity report |
| 4. Meteor suppression + capability checks | DONE | `2495225` | See final parity verification | See parity report |
| 5. Module, settings, tester tooling | DONE | `741e421` | See final parity verification | See parity report |
| 6. Docs | DONE | `deb0b20` | See final parity verification | See parity report |
| 7. Tester build | DONE | parity pass | 2658 passed | No blocking parity findings |
| 8. Target report | DONE | parity pass | 0 failures/errors/skips | See linked report |

## Notes

- Nothing pushed; all commits local to branch `1.21.11`.
- No dependency or vendored-Meteor changes are permitted; stop and report if one appears needed.
- Build uses `net.fabricmc.fabric-loom-remap` and `modImplementation`/`modCompileOnly`
  (unlike 26.2/26.1.2, which use non-remap `fabric-loom` + `implementation`).

## Final parity verification — 2026-11-01

The stale TODO table above has been reconciled with the existing stage commits.
`./gradlew cleanTest build -PtesterSuffix=tester.3 --offline` passed: 2658 tests,
0 failures, 0 errors, 0 skipped. The tester jar and source archive are built.
See [the three-version parity report](version-parity-2026-11-01.md) for the two
adapter fixes, artifact paths and remaining experimental feature gates.
