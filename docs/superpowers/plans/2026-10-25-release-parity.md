# Comet 0.26.25 release preparation

**Goal:** Use the existing 26.1.2 working tree as the feature reference, align 1.21.11 and 26.2, and prepare a local release for all three Minecraft versions.

**Scope:** Preserve Minecraft-specific APIs, Java 21/25 toolchains and pinned Meteor versions. Preserve and include the existing Scaffold ports and documentation edits. Prepare locally; publishing is a separate action.

- [x] Run baseline test suites in the existing version worktrees.
- [x] Add regression coverage for Highway Fly claiming Comet Scaffold as incompatible; observe failure on the two ports, add the missing conflict entry, and verify all three.
- [x] Align module registration and dashboard/helper tests with 26.1.2 where version-independent.
- [x] Set every tree to 0.26.25, update README artifact names and changelogs, and record gameplay validation limits.
- [x] Run clean builds and full tests for all versions; inspect packaged metadata, dashboard assets, Scaffold mixins, licences and corresponding source, including 1.21.11 vendored build inputs.
- [x] Review the changes, commit the prepared trees, fast-forward the local named 26.1.2 branch, and assemble a release folder with six artifacts, checksums, release notes and verification results.

**Validation focus:** Missing conflict handling; API compatibility with pinned Meteor; expanded release metadata; source completeness; no claim of live gameplay validation from unit tests.

## Verification record

- Minecraft 26.1.2: 1,321 tests; Minecraft 26.2: 1,321 tests; Minecraft 1.21.11: 1,323 tests. Zero failures, errors or skips.
- Clean builds and 224 rendered-pixel shader checks per version passed. Packaged metadata, Java bytecode, mixins, dashboard assets, notices and corresponding source were inspected.
- Independent review found two 1.21.11 runtime compatibility blockers; missing-class fallback and runtime mapping were fixed and covered by regressions.
- Existing unrelated version adaptations were retained. Live gameplay remains explicitly unverified.
- Six release assets, checksums, release notes, verification data and build logs are prepared under `../reports/release-0.26.25/`. No remote publication was performed.
