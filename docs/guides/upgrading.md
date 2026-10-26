# Upgrading from 0.6.1

[Documentation index](../README.md) · [Project README](../../README.md)

- **placement-priority** and **prefer-nearby** are merged into **placement-order** (default Nearest). Old saved values are not carried over.
- **auto-switch** keeps your saved value (probably Silent); select **Sticky** to save two packets per placement.
- **rotate** now also applies in Adaptive and Conservative. If you had it saved off, those modes now place without rotating.
- New under Server Pacing: **max-packets-per-second**, **extra-rotation-packets**. With extra rotation packets off, Adaptive places at most one rotated block per tick (it previously could ramp to two).
