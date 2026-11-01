# CrystalCombat ports overview

Status of the CrystalCombat port from the Minecraft 26.2 tree to Minecraft 26.1.2 and
Minecraft 1.21.11.

| Field | Value |
|---|---|
| PORT_BASE (26.2 source commit) | `363b410fa945e361c707b997a97556d0e5ef7398` |
| 26.2 | Minecraft 26.2, Java 25, Meteor `26.2-20261009.074120-35` (port source; read-only) |
| 26.1.2 | Minecraft 26.1.2, Java 25, Meteor `26.1.2-20260810.135545-42` |
| 1.21.11 | Minecraft 1.21.11, Java 21, Meteor `1.21.11-82` (vendored under `1.21.11/vendor/`) |

Every copied file comes from `PORT_BASE` via `git show`/`git archive`; nothing is copied from
the 26.2 working tree. Both targets use the same `PORT_BASE`.

## Per-version status

| Version | Features available | Features disabled | Unconfirmed | Test totals | Tester jar |
|---|---|---|---|---|---|
| 26.2 | all CrystalCombat features (reference implementation) | — | — | 1681 passed (Phase 9 close) | `26.2/build/libs/comet-0.27.0-tester.3-mc26.2.jar` |
| 26.1.2 | pending | pending | pending | baseline 1660 passed before port | pending |
| 1.21.11 | pending | pending | pending | baseline 1664 passed before port | pending |
