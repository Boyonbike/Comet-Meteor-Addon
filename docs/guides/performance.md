# Performance

[Documentation index](../README.md) · [Project README](../../README.md)

Block updates refresh only the changed cell and its neighbours. Moving one block reuses the overlapping region and reads only newly entered cells (121 instead of 1,331 at radius 5). A full rescan still runs on settings changes, resets and every 40 ticks. Support points are cached and revalidated before sending. With TNT, the two-block redstone buffer reads blocks only in chunk sections whose palette can hold a redstone component, so most checks cost a few palette lookups instead of 125 block reads. Ray casts are capped per tick, and positions that fail visibility wait three ticks. Smart Sponge scores all spots first and ray-casts supports only in score order. **placement-order** (default Nearest) sorts candidates, including retries, by distance.
