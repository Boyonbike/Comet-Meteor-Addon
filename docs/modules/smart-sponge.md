# Smart Sponge

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **smart-sponge**. Open **Meteor → Comet → smart-sponge**.

A separate module. Put **dry sponges** in your hotbar or offhand.

- Searches a cube (**search-radius**, default 5) for valid, unobstructed target cells with a visible, reachable support.
- Scores each spot by the connected drainable water it would absorb: water sources and flowing water, waterlogged blocks, kelp and seagrass. It models vanilla 1.21.11's six-step absorption traversal and 64-cell cap, counting water displaced by the sponge itself. Waterlogged solids are drained by adjacent placement, never replaced or mined.
- Places the highest-scoring reachable spot (ties go to the closer one). This is a best-next-placement heuristic, not a minimum-sponge solution; flowing water and server changes can alter results.
- Places one sponge, waits for the server update (up to 40 ticks), lets it settle, then rescans (**scan-interval**, default 5 ticks).
- **break-wet-sponges** (default off) mines only server-confirmed wet sponges this module placed during the current activation, with the currently held tool. Drops must be picked up normally.

- Shares Block Coat's **max-packets-per-second** (150 without Block Coat): placement and wet-sponge mining wait while the last second's packets are at the limit (`Packet limit`).
- Pauses while riding, for the same rotation reason as Block Coat.

When both modules are on, Smart Sponge takes priority while a sponge is queued or pending, for two ticks after confirmation while water settles, and while collecting wet sponges. Block Coat cancels its unsent interactions and resumes automatically afterwards. Having no water target or no sponge does not pause coating.
