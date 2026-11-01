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
| 1. Desk audit | DONE | `1137fa1` | — | self-check + fix round; independent review pending |
| 2. Pure core | TODO | — | — | — |
| 3. Minecraft/Meteor adapters | TODO | — | — | — |
| 4. Meteor suppression + capability checks | TODO | — | — | — |
| 5. Module, settings, tester tooling | TODO | — | — | — |
| 6. Docs | TODO | — | — | — |
| 7. Tester build | TODO | — | — | — |
| 8. Target report | TODO | — | — | — |

## Notes

- Nothing pushed; all commits local to branch `26.1.2`.
- No dependency or vendored-Meteor changes are permitted; stop and report if one appears needed.
