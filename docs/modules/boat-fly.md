# Boat Fly

[Documentation index](../README.md) · [Project README](../../README.md)

Boat Fly flies and phases a boat that you steer yourself. Sit in a boat's driver seat with the module on:
**WASD** steers, **jump** rises and **sprint** descends. It has no heading, helpers or safety logouts; for
unattended travel along a fixed heading use [Auto Boat Fly](auto-boat-fly.md), which drives with the same
engine and the same settings sections.

Only one boat controller runs at a time. Enabling Auto Boat Fly turns Boat Fly off (and the reverse), and
enabling Highway Fly turns either off. Modules paused for the ride are handed over, so they are restored
once, when you finally leave the boat.

## Settings

| Section | Settings |
| --- | --- |
| **Controls** | **dismount-key**, **lock-boat-yaw**, **pause-conflicting-modules** |
| **Speed** | **air-substeps**, **substep-speed**, **limited-speed**, **vertical-speed**, **air-acceleration**, **anti-kick** |
| **Phasing** | **phase**, **adaptive-phase**, **adaptive-phase-speed**, **in-block-speed** |
| **Safety** | **stop-before-lava**, **lava-avoidance-mode** |
| **Correction Recovery** | **correction-hold**, **retry-after-remount**, **retry-after-landing**, **landed-retry-speed** |
| **Diagnostics** | **debug-file** |

Speeds are in blocks per tick; multiply by 20 for blocks per second.

- **substep-speed** (default 3.2) is the open-air speed while **air-substeps** splits each move into small
  packets. If the server rejects substeps, travel falls back to **limited-speed**.
- **limited-speed** (default 0.99) is the single-packet speed: it is used with substeps off or after a
  fallback, and it caps adaptive phasing. It replaces the old **horizontal-speed** and **horizontal-limit**
  pair, which travel always combined as the smaller of the two; profiles keep that smaller value.
- **adaptive-phase** keeps full speed for the collision-free part of a move and slows only inside blocks,
  where **in-block-speed** (default 0.24, the proven safe value) applies. **adaptive-phase-speed** never
  exceeds **limited-speed**.
- **retry-after-remount** retries full speed whenever you re-board after a server rejection. With it off,
  **retry-after-landing** retries substeps after a landed re-board, at **landed-retry-speed**; that retry no
  longer overwrites your saved **substep-speed**.

The module screen shows the current speed mode and has **Resume adaptive phase** and **Retry fast air**
buttons for clearing a fallback without re-boarding.

## Lava avoidance mode

With **stop-before-lava** on, **lava-avoidance-mode** defaults to **BoatAndRiders**, which checks the boat and
every rider before movement, including phasing. **BoatOnly** checks only the boat, allowing deliberate rider
contact with overhead lava. Turning **stop-before-lava** off disables predictive lava avoidance.

## Dismount key

Set **Controls → dismount-key** to a keyboard key or mouse button. While a boat controller is active in a
boat, it replaces sneak for dismounting; unbound uses Minecraft's sneak key. The custom key is ignored while
typing or when the window is unfocused. On-foot sneaking and other vehicles keep their usual controls.

## Upgrading

Before this split, **boat-fly** was the AFK controller. Its saved record becomes **auto-boat-fly**, keeping
every setting and its enabled state; Boat Fly keeps a copy of the movement settings and starts disabled.
