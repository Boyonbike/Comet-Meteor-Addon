# Combat Recorder

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **combat-recorder**.

**combat-recorder** is a development tool for CrystalCombat. While it is on it appends every audited combat
observation to a JSON Lines file, so later phases can replay real 26.1.2 event order instead of assuming it.
It is **off by default** and **sends nothing**: it never places, attacks, swaps, rotates or chats to the server.

This tree targets **Minecraft 26.1.2** with Meteor Client **26.1.2-20260810.135545-42** (build 42). The
Phase 0.4 fixtures under `src/test/resources/combat/traces` were recorded on Minecraft 26.2 and are used
read-only here; the port audit re-verified the packet types and client-observed order they depend on (see
the trace README in that directory).

## Output

Each time the module is enabled it writes a new file:

```
<minecraft directory>/comet/combat-traces/<timestamp>.jsonl
```

Every line is one JSON object. Observation lines carry the connection id, receipt sequence, bundle id and
position, receipt `nanoTime`, client tick, observation kind, whether vanilla had applied the packet yet, the
applied-state revision, entity id, UUID, resolved position and a payload. The final line is a summary label.
Lines that never received an application stamp are still written, marked `applied: false` with a reason, so a
trace never hides a dropped or cancelled packet.

Recorded kinds: entity spawn/move/removal, block updates (including moving-piston blocks and piston block
events), explosions, self health/food, entity data (player health and absorption), effects, equipment, damage
events, entity status and totem pops, position setbacks, death, respawn/dimension change and connection
changes. The block-change acknowledgement sequence is recorded as a `BLOCK_UPDATE` line with
`blockChangedAck: true`.

## Controls

- Toggle the module, or use `combat-recorder on` / `combat-recorder off`.
- `combat-recorder label <free text>` writes a marker line. The **label-key** setting opens chat with that
  command pre-filled; it is unbound by default.
- `max-file-megabytes` bounds the trace file; the writer stops accepting lines at the limit and the summary
  records how many were dropped.

## Limits

- The recorder buffers at most 8192 unresolved receipts; overflow is reported in the trace as a
  `recorder-overflow` label.
- It reads packet fields on the network thread and resolves entities only after vanilla applies the packet.
- It is not a validation tool by itself: Phase 0.4 pairs each trace with server-side ground truth. A
  server-side logger is not part of this build.
