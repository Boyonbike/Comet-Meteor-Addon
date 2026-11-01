# Combat harness

The Phase 0.4/0.5 combat harness is a **test-only Fabric client game test**. It starts an
in-process dedicated server, connects a scripted Fabric client, drives the client through
the same packet paths as a real player, and records both the client observation trace and
the server-side ground truth. It is not shipped in the mod JAR and is not part of the normal
`runClient` classpath.

## Prerequisites

- JDK 25 at `$HOME/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2` (the repository's normal
  toolchain).
- The pinned Minecraft 26.2 / Fabric Loader 0.19.3 / Meteor build 35 artifacts already
  downloaded by Gradle.
- A graphical session. This machine runs the client under the existing Wayland compositor;
  the Gradle task sets `MC_DEBUG_ENABLED=true` and `MC_DEBUG_PREFER_WAYLAND=true` so GLFW
  does not try the absent X11 display. In this Flatpak session, export:

  ```sh
  export WAYLAND_DISPLAY=wayland-1
  export XDG_RUNTIME_DIR=/run/user/1000
  unset DISPLAY
  ```

- The test-only Fabric API client game-test module
  `net.fabricmc.fabric-api:fabric-client-gametest-api-v1:6.0.2+0f4f1dca9e`, declared in
  `gradle/libs.versions.toml` and added only to the `gametest` source set. It is the module
  bundled by Fabric API `0.161.0+26.2` and requires Fabric Loader ≥ 0.18.4.

## Run

From `26.2/`:

```sh
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2"
export WAYLAND_DISPLAY=wayland-1
export XDG_RUNTIME_DIR=/run/user/1000
unset DISPLAY

./gradlew combatHarness --offline \
  -Pcombat.scenarios=open_target,holed_target,ack_barrier \
  -Pcombat.delays=0,40,100 \
  -Pcombat.tps=20,15,10 \
  -Pcombat.repeats=5 \
  -Pcombat.output=build/combat-harness/my-run
```

`combatHarness` depends on Loom's `runClientGameTest`. The task creates and clears
`build/run/clientGameTest`, accepts the local test-server EULA (Loom's `eula` option), and
launches the client. It never contacts a public server. Do not run two harness tasks at the
same time: they share Loom's client run directory. A run writes its traces under the absolute
`combat.output` root, not under the client run directory.

### Gradle properties

| Property | Default | Meaning |
| --- | --- | --- |
| `combat.scenarios` | `open_target` | Comma-separated scenario names. |
| `combat.delays` | `0,40,100` | One-way milliseconds added to each direction by the in-process Netty handler. |
| `combat.tps` | `20,15,10` | Server target tick rate set through `MinecraftServer.tickRateManager()`. |
| `combat.repeats` | `5` | Repetitions of every scenario × delay × TPS combination. |
| `combat.jitter` | `0` | Maximum extra one-way jitter in milliseconds; FIFO order is preserved. |
| `combat.seed` | `26432022` | Base world seed; each run derives a deterministic seed. |
| `combat.output` | `build/combat-harness` | Run root. Relative paths are resolved against the project directory. |

## What a run writes

For every repetition:

```text
<output>/<scenario>/delay-<one-way-ms>-tps-<tps>/run-<n>/
  client.jsonl    CombatRecorder observations in receipt order
  server.jsonl    Test-mod ground truth from the server thread
  metadata.json   Scenario, delay, TPS, seed, trace-complete flag, failure
```

`client.jsonl` is the existing `CombatRecorder` format: receipt nanos, client tick, bundle
position, observation kind, applied revision, entity/UUID and payload. `server.jsonl` is
written by the test mod's mixins and contains:

- `server_meta`, `server_tick_start`, `server_tick_end` with every player's and end crystal's
  authoritative position, health, absorption and delta movement;
- `use_item_on_packet`, `attack_packet`, `interact_packet`, `attack_accepted`;
- `hurt_attempt`, `player_hurt_attempt`, `player_hurt_result`, `damage_applied_start/end`;
- `explosion_start/end`, `crystal_hurt`, `crystal_hurt_result`;
- `piston_tick_start/end`, `block_change`, `entity_event`, `fake_player_spawn/remove`.

The server logger filters packet-handler entries to the server thread. Vanilla's
`PacketUtils.ensureRunningOnSameThread` calls a packet handler first on the Netty thread
(which only schedules it), then on the server thread; logging both would double-count.
The client recorder also ignores non-`CLIENTBOUND` connections; without that filter the
in-process server's `Connection` objects overwrite the client connection epoch.

## Targets and terrain

- **Target:** a test-only `ServerPlayer` with a no-op `Connection`, registered through
  `PlayerList.placeNewPlayer` and marked client-loaded. It is visible to the real client and
  receives normal health/absorption metadata. Its `tick()` applies its own delta movement,
  because a real player's movement normally comes from its client.
- **Friend:** the same fake-player factory, used for friend health metadata and the totem
  scenario.
- **Terrain:** flat stone/obsidian arenas built through the dedicated server's command
  source. `CombatArena` holds the flat pad, hole and armour helpers.
- **Actions:** `ClientActions` uses `MultiPlayerGameMode.useItemOn`, `attack`, hotbar
  selection (`ServerboundSetCarriedItemPacket`) and client look input. These are the packet
  paths a real client uses; they are test code, not product combat code.

## Scenario names

| Family | Names |
| --- | --- |
| Direct crystal | `open_target`, `holed_target`, `holed_vertical` |
| Immunity window | `two_crystals_weaker`, `two_crystals_stronger`, `window_boundary` |
| Absorption | `absorption_bundle`, `self_absorption` |
| Rejection | `rejected_placement`, `out_of_reach_attack` |
| Crystal-on-crystal | `crystal_destroyed_by_explosion` |
| Barrier | `ack_barrier`, `ack_zero_damage` |
| Self damage | `self_damage_near`, `self_damage_mid`, `self_damage_zero` |
| Target state | `totem_friend`, `c6_stronger_in_window` |
| Timing | `server_stall` |
| Lifecycle | `death_respawn`, `disconnect_reconnect`, `dimension_change` |
| Piston | `piston_{east,west,north,south}_{feet,eye}_{redstone,torch}` (16) |

### Piston layouts

`PistonPushScenario` places a server-spawned end crystal at `(0.5, y, 0.5)`, a piston behind
it facing the push direction, and a redstone block or torch behind the piston. It waits 20
ticks and records the crystal position on every server tick. The measured final displacement
is 1.02 blocks after two extension ticks; the report compares this with the plan's literal
`0.51 + 0.01` per-tick formula.

## Analysis

```sh
python3 -I scripts/combat_trace_report.py \
  --input build/combat-harness/my-run \
  --report docs/superpowers/audit/2026-10-29-trace-report.md \
  --fixtures src/test/resources/combat/traces
```

The analyzer is dependency-free. It computes event order and p50/p95/max delays, checks the
expected and missing observations, answers the 0.5 questions, and produces the fixture index.
Fixtures are copied with client trace, server log and metadata beside each other.

## Scope and limitations

- Everything is localhost and offline. The test server is a vanilla dedicated server with a
  flat world; it does not model a public server's plugins, anticheat, lag or datapack tags.
- The fake target's movement is server-driven. Explosion knockback is recorded through
  `Entity.move` on the server, not through a second real client.
- Explosion damage is recorded as the server applied it. Scenarios that need stable geometry
  set max health and explosion-knockback resistance in the test world; those values are
  metadata, not product defaults.
- The `ack_barrier` interaction is an empty-hand use on stone, sent from the
  after-application explosion hook. It is side-effect-free, but the ack ordering remains a
  local-server result, not a public-server guarantee.
- The harness is test-only. It does not call `CombatRuntime.dispatch` and does not exercise
  product code paths; P2/P3/P5.2/P9 should reuse the DSL and logging layer, not the scenarios'
  hard-coded geometry.
