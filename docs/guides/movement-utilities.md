# Movement and player utilities

[Documentation index](../README.md) · [Project README](../../README.md)

These modules are original Comet implementations; see
[THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) for third-party provenance.

- **no-hurt-cam** removes the camera tilt and shake when damaged while it is on.
- **angle-calculator** aims your view at the centre of the configured **to** block, using its X/Z coordinates. **level-pitch** defaults on and keeps pitch at zero. Riding also turns the vehicle. This controls your view; it does not move you automatically.
- **anti-spawn-set** cancels outgoing bed and respawn-anchor interactions before the server sees them. **block-beds**, **block-anchors** and **notify** default on. It blocks every interaction with those blocks, including sleeping, anchor charging and explosive use in other dimensions.
