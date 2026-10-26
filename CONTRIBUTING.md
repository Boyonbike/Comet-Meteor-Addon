# Contributing to Comet

Thanks for helping. Comet is a client-side Meteor Client addon maintained as three
parallel Minecraft branches.

## Branches

| Branch | Minecraft | Java | Meteor Client |
| --- | --- | --- | --- |
| `26.1.2` (default) | 26.1.2 | 25 | build 42 |
| `26.2` | 26.2 | 25 | build 35 |
| `1.21.11` | 1.21.11 | 21 | build 82 (kept in `vendor/`) |

Target the branch that matches the Minecraft version you use. Fixes that apply to
every version are mirrored across branches; mention which branches you have tested.

## Build and test

You need JDK 25 (JDK 21 on `1.21.11`). The Gradle Wrapper downloads the toolchain
and dependencies.

```sh
./gradlew clean build
./gradlew test
python3 scripts/test_check_docs.py
python3 scripts/check_docs.py
```

`build` also writes `build/distributions/comet-<version>-mc<minecraft>-source.zip`.
Publish that archive together with the JAR: the GPL-3.0-only adaptations need their
corresponding source. Public module documentation under `docs/` is included; internal planning notes are excluded.

## Code

- Keep the existing package layout (`dev.tntcoating`) and four-space indentation.
- Modules run on the client and use Meteor's settings, events and HUD APIs.
- New third-party code must be GPL-3.0-only-compatible. Record its author, upstream
  URL, revision, licence and the changes made in the file header and in
  `THIRD_PARTY_NOTICES.md`.
- Match the existing settings grouping, chat notices and HUD conventions.
- Add or update tests under `src/test/java/...` when behaviour changes, and run
  `./gradlew build` before opening a pull request.

## Releases

Tag the commit on the matching branch as `v<version>-mc<minecraft>`, for example
`v0.26.26-mc26.1.2`, and attach both `build/libs/comet-<version>-mc<minecraft>.jar`
and `build/distributions/comet-<version>-mc<minecraft>-source.zip` to the GitHub
release. Tags matching `v*` run the branch build workflow.

## Licensing of contributions

By submitting a pull request you agree that your contribution may be distributed
under **GPL-3.0-only** as part of Comet. Original code may also remain available
under CC0 1.0 (`LICENSE-CC0`).

## Reporting a problem

Include the branch, Minecraft version, Meteor build, steps to reproduce and the
relevant log lines. Remove webhook URLs, tokens and other secrets before posting.
